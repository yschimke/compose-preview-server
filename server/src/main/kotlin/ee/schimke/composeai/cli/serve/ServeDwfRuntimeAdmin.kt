package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `GET`/`PUT`/`DELETE /admin/dwf-runtime`: inspects, validates, and atomically installs or removes
 * `DeclarativeWatchFaceRuntime.apk` + `metadata.json` in `composeAiCacheDir("dwf-runtime")` so the
 * `dwf-apk` watch-face renderer can run without SSHing into the host.
 */
class ServeDwfRuntimeAdmin(
  private val cacheRoot: File = composeAiCacheDir(CACHE_SUBDIR),
  private val env: (String) -> String? = System::getenv,
  private val sysProp: (String) -> String? = System::getProperty,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  /** Matches `DwfRuntimeCommand.RuntimeMetadata` in compose-ai-tools. */
  @Serializable
  data class Metadata(
    val packageName: String,
    val sha256: String,
    val sizeBytes: Long,
    val source: String,
    val path: String,
  )

  @Serializable
  data class Status(
    val schema: String = "compose-preview-serve/admin-dwf-runtime/v1",
    val cached: Boolean,
    val cacheDir: String,
    val apkPath: String,
    val metadata: Metadata? = null,
    val activeSource: String,
    val envPath: String? = null,
    val envValid: Boolean = false,
    val restartRequired: Boolean = false,
  )

  sealed interface Result {
    data class Ok(
      val status: String,
      val cached: Boolean,
      val metadata: Metadata?,
      val restartRequired: Boolean,
    ) : Result

    /** Empty, oversized, or malformed APK — a 400. */
    data class Invalid(val reason: String) : Result

    /** Same SHA-256 is already cached, or DELETE when nothing is cached — a 409. */
    data class Conflict(val reason: String) : Result
  }

  private val lock = Any()

  /**
   * The SHA-256 of the runtime APK that was active at startup when no env/sysprop override was set,
   * or null when no runtime APK was cached at startup. Used to compute [restartRequired] accurately
   * because `DwfApkDaemonBridge` keeps a started simulator daemon in memory until restart.
   */
  private val servingCacheSha256: String? = run {
    val envOverride = resolveEnvOverride()
    if (envOverride.valid) {
      null
    } else {
      val apk = cachedApkFile()
      if (apk.isFile && inspectRuntimeApk(apk) != null) sha256(apk) else null
    }
  }

  private data class EnvOverride(val path: String?, val valid: Boolean)

  private fun cachedApkFile(): File = File(cacheRoot, APK_FILE_NAME)

  private fun cachedMetaFile(): File = File(cacheRoot, METADATA_FILE_NAME)

  private fun resolveEnvOverride(): EnvOverride {
    val raw =
      sysProp(APK_PATH_SYS_PROP)?.takeIf { it.isNotBlank() }
        ?: env(APK_PATH_ENV)?.takeIf { it.isNotBlank() }
        ?: env(RUNTIME_APK_ENV)?.takeIf { it.isNotBlank() }
        ?: env(RUNTIME_APK_ENV_ALIAS)?.takeIf { it.isNotBlank() }
        ?: return EnvOverride(path = null, valid = false)
    val file = File(raw).absoluteFile
    val valid = file.isFile && inspectRuntimeApk(file) != null
    return EnvOverride(path = raw, valid = valid)
  }

  private fun readCachedMetadata(apkFile: File): Metadata? {
    if (!apkFile.isFile) return null
    val metaFile = cachedMetaFile()
    if (metaFile.isFile) {
      val parsed = runCatching {
        prettyJson.decodeFromString(Metadata.serializer(), metaFile.readText())
      }
        .getOrNull()
      if (parsed != null) return parsed
    }
    val info = inspectRuntimeApk(apkFile) ?: return null
    return Metadata(
      packageName = info.packageName,
      sha256 = sha256(apkFile),
      sizeBytes = apkFile.length(),
      source = "cache",
      path = apkFile.absolutePath,
    )
  }

  private fun isRestartRequired(envOverride: EnvOverride, currentCachedSha256: String?): Boolean {
    if (envOverride.valid) return false
    val serving = servingCacheSha256 ?: return false
    return currentCachedSha256 != serving
  }

  fun status(): Status =
    synchronized(lock) {
      val envOverride = resolveEnvOverride()
      val apkFile = cachedApkFile()
      val meta = readCachedMetadata(apkFile)
      val cached = apkFile.isFile && meta != null
      val activeSource =
        when {
          envOverride.valid -> "env"
          cached -> "cache"
          else -> "none"
        }
      return Status(
        cached = cached,
        cacheDir = cacheRoot.absolutePath,
        apkPath = apkFile.absolutePath,
        metadata = if (cached) meta else null,
        activeSource = activeSource,
        envPath = envOverride.path,
        envValid = envOverride.valid,
        restartRequired = isRestartRequired(envOverride, if (cached) meta.sha256 else null),
      )
    }

  fun install(apkBytes: ByteArray): Result =
    synchronized(lock) {
      if (apkBytes.isEmpty()) {
        return Result.Invalid("request body is empty; expected DeclarativeWatchFaceRuntime.apk")
      }
      cacheRoot.mkdirs()
      val tempApk = Files.createTempFile(cacheRoot.toPath(), ".dwf-runtime-", ".apk.tmp")
      val tempMeta = Files.createTempFile(cacheRoot.toPath(), ".dwf-metadata-", ".json.tmp")
      try {
        Files.write(tempApk, apkBytes)
        val tempFile = tempApk.toFile()
        val info =
          inspectRuntimeApk(tempFile)
            ?: return Result.Invalid(
              "uploaded file is not a valid DeclarativeWatchFaceRuntime APK (must be a zip " +
                "containing classes*.dex and AndroidManifest.xml declaring $WATCH_FACE_CONTROL_ACTION)",
            )
        val digest = sha256(tempFile)
        val targetApk = cachedApkFile()
        if (targetApk.isFile && sha256(targetApk) == digest) {
          return Result.Conflict("DWF runtime APK with sha256 $digest is already cached")
        }
        val metadata =
          Metadata(
            packageName = info.packageName,
            sha256 = digest,
            sizeBytes = tempFile.length(),
            source = "admin-api",
            path = targetApk.absolutePath,
          )
        Files.writeString(tempMeta, prettyJson.encodeToString(Metadata.serializer(), metadata))
        atomicReplace(tempApk, targetApk.toPath())
        atomicReplace(tempMeta, cachedMetaFile().toPath())
        onLog(
          "serve: installed DWF runtime APK (${info.packageName}, sha256=$digest) via admin API",
        )
        val envOverride = resolveEnvOverride()
        return Result.Ok(
          status = "installed",
          cached = true,
          metadata = metadata,
          restartRequired = isRestartRequired(envOverride, digest),
        )
      } finally {
        runCatching { Files.deleteIfExists(tempApk) }
        runCatching { Files.deleteIfExists(tempMeta) }
      }
    }

  fun delete(): Result =
    synchronized(lock) {
      val apkFile = cachedApkFile()
      val metaFile = cachedMetaFile()
      if (!apkFile.isFile && !metaFile.isFile) {
        return Result.Conflict("no DWF runtime APK is cached")
      }
      runCatching { Files.deleteIfExists(apkFile.toPath()) }
      runCatching { Files.deleteIfExists(metaFile.toPath()) }
      onLog("serve: removed cached DWF runtime APK via admin API")
      val envOverride = resolveEnvOverride()
      return Result.Ok(
        status = "deleted",
        cached = false,
        metadata = null,
        restartRequired = isRestartRequired(envOverride, null),
      )
    }

  companion object {
    const val CACHE_SUBDIR: String = "dwf-runtime"
    const val APK_FILE_NAME: String = "DeclarativeWatchFaceRuntime.apk"
    const val METADATA_FILE_NAME: String = "metadata.json"
    const val WATCH_FACE_CONTROL_ACTION: String =
      "com.google.android.wearable.action.WATCH_FACE_CONTROL"
    const val APK_PATH_SYS_PROP: String = "composeai.watchface.apkRenderer"
    const val APK_PATH_ENV: String = "COMPOSE_PREVIEW_DWF_RENDERER_APK"
    const val RUNTIME_APK_ENV: String = "COMPOSE_PREVIEW_DWF_RUNTIME_APK"
    const val RUNTIME_APK_ENV_ALIAS: String = "DWF_RUNTIME_APK"
    const val MAX_APK_BYTES: Long = 100L * 1024 * 1024

    val CANDIDATE_PACKAGES: Set<String> =
      setOf(
        "com.google.wear.watchface.runtime",
        "com.android.wearable.watchface.runtime",
        "com.google.android.wearable.watchface.rwf",
      )

    private val prettyJson = Json {
      prettyPrint = true
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    internal data class RuntimeApkInfo(val packageName: String)

    internal fun inspectRuntimeApk(apk: File): RuntimeApkInfo? = runCatching {
      if (!apk.isFile) return null
      ZipFile(apk).use { zip ->
        val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return null
        val manifestBytes = zip.getInputStream(manifestEntry).readBytes()
        val hasDex =
          zip.entries().asSequence().any {
            !it.isDirectory && it.name.startsWith("classes") && it.name.endsWith(".dex")
          }
        if (!hasDex) return null

        val packageName: String
        val hasAction: Boolean
        if (isBinaryXml(manifestBytes)) {
          packageName = readBinaryXmlPackageName(manifestBytes).orEmpty()
          hasAction =
            binaryXmlContainsString(manifestBytes, WATCH_FACE_CONTROL_ACTION) ||
              packageName in CANDIDATE_PACKAGES
        } else {
          val text = manifestBytes.toString(Charsets.UTF_8)
          packageName =
            Regex("""\bpackage\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1).orEmpty()
          hasAction = text.contains(WATCH_FACE_CONTROL_ACTION) || packageName in CANDIDATE_PACKAGES
        }
        if (packageName.isBlank() || !hasAction) return null
        RuntimeApkInfo(packageName = packageName)
      }
    }
      .getOrNull()

    private fun isBinaryXml(bytes: ByteArray): Boolean =
      bytes.size >= 8 &&
        (ByteBuffer.wrap(bytes, 0, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF) ==
          0x0003

    /** Extracts the `<manifest package="…">` attribute from an Android Binary XML (AXML) buffer. */
    private fun readBinaryXmlPackageName(bytes: ByteArray): String? {
      if (!isBinaryXml(bytes)) return null
      val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val headerSize = buf.getShort(2).toInt() and 0xFFFF
      var offset = headerSize
      var strings: List<String>? = null

      while (offset + 8 <= bytes.size) {
        val chunkType = buf.getShort(offset).toInt() and 0xFFFF
        val chunkHeaderSize = buf.getShort(offset + 2).toInt() and 0xFFFF
        val chunkSize = buf.getInt(offset + 4)
        if (chunkSize <= 0 || offset + chunkSize > bytes.size) break

        when (chunkType) {
          0x0001 -> strings = parseStringPool(bytes, offset, chunkHeaderSize, chunkSize)
          0x0102 -> {
            val pool = strings ?: return null
            if (offset + chunkHeaderSize + 20 > bytes.size) return null
            val body = offset + chunkHeaderSize
            val nameIdx = buf.getInt(body + 4)
            if (pool.getOrNull(nameIdx) == "manifest") {
              val attrStart = buf.getShort(body + 8).toInt() and 0xFFFF
              val attrSize = buf.getShort(body + 10).toInt() and 0xFFFF
              val attrCount = buf.getShort(body + 12).toInt() and 0xFFFF
              val attrsOffset = body + attrStart
              for (i in 0 until attrCount) {
                val attrOffset = attrsOffset + i * attrSize
                if (attrOffset + 20 > offset + chunkSize) break
                val attrNameIdx = buf.getInt(attrOffset + 4)
                if (pool.getOrNull(attrNameIdx) == "package") {
                  val rawValueIdx = buf.getInt(attrOffset + 8)
                  if (rawValueIdx >= 0) {
                    return pool.getOrNull(rawValueIdx)
                  }
                  val dataType = bytes[attrOffset + 15].toInt() and 0xFF
                  val data = buf.getInt(attrOffset + 16)
                  if (dataType == 0x03) {
                    return pool.getOrNull(data)
                  }
                }
              }
            }
          }
        }
        offset += chunkSize
      }
      return null
    }

    private fun parseStringPool(
      bytes: ByteArray,
      chunkOffset: Int,
      chunkHeaderSize: Int,
      chunkSize: Int,
    ): List<String> {
      val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      val stringCount = buf.getInt(chunkOffset + 8)
      val flags = buf.getInt(chunkOffset + 16)
      val stringsStart = buf.getInt(chunkOffset + 20)
      val isUtf8 = (flags and 0x100) != 0
      val poolDataStart = chunkOffset + stringsStart
      val chunkEnd = chunkOffset + chunkSize
      val offsetsBase = chunkOffset + chunkHeaderSize
      if (stringCount <= 0 || offsetsBase + stringCount * 4 > chunkEnd) return emptyList()

      val result = ArrayList<String>(stringCount)
      for (i in 0 until stringCount) {
        val strOffset = poolDataStart + buf.getInt(offsetsBase + i * 4)
        if (strOffset < poolDataStart || strOffset >= chunkEnd) {
          result += ""
          continue
        }
        if (isUtf8) {
          var p = strOffset
          // Skip UTF-16 length (1 or 2 bytes)
          val u16First = bytes[p++].toInt() and 0xFF
          if ((u16First and 0x80) != 0 && p < chunkEnd) p++
          if (p >= chunkEnd) {
            result += ""
            continue
          }
          val u8First = bytes[p++].toInt() and 0xFF
          val byteLen =
            if ((u8First and 0x80) != 0 && p < chunkEnd) {
              ((u8First and 0x7F) shl 8) or (bytes[p++].toInt() and 0xFF)
            } else {
              u8First
            }
          val safeLen = byteLen.coerceAtMost((chunkEnd - p).coerceAtLeast(0))
          result += String(bytes, p, safeLen, Charsets.UTF_8)
        } else {
          var p = strOffset
          if (p + 2 > chunkEnd) {
            result += ""
            continue
          }
          val first = buf.getShort(p).toInt() and 0xFFFF
          p += 2
          val charLen =
            if ((first and 0x8000) != 0 && p + 2 <= chunkEnd) {
              val low = buf.getShort(p).toInt() and 0xFFFF
              p += 2
              ((first and 0x7FFF) shl 16) or low
            } else {
              first
            }
          val byteLen = (charLen * 2).coerceAtMost((chunkEnd - p).coerceAtLeast(0))
          result += String(bytes, p, byteLen, Charsets.UTF_16LE)
        }
      }
      return result
    }

    private fun binaryXmlContainsString(bytes: ByteArray, needle: String): Boolean {
      val utf8 = needle.toByteArray(Charsets.UTF_8)
      val utf16 = needle.toByteArray(Charsets.UTF_16LE)
      return indexOfBytes(bytes, utf8) >= 0 || indexOfBytes(bytes, utf16) >= 0
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
      if (needle.isEmpty() || haystack.size < needle.size) return -1
      val max = haystack.size - needle.size
      outer@ for (i in 0..max) {
        if (haystack[i] != needle[0]) continue
        for (j in 1 until needle.size) {
          if (haystack[i + j] != needle[j]) continue@outer
        }
        return i
      }
      return -1
    }

    private fun atomicReplace(source: Path, target: Path) {
      try {
        Files.move(
          source,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }

    private fun sha256(file: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(8192)
        while (true) {
          val r = input.read(buf)
          if (r <= 0) break
          md.update(buf, 0, r)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }
  }
}
