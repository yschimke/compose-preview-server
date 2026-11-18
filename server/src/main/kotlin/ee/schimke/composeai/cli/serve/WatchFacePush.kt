package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The Watch Face Push site API (`/api/watchface-push/v1`): the contract the Wear OS push app in
 * yschimke/watchface-tools (`:push-app`, `docs/PUSH_API.md`) talks to, so a watch can list the
 * `wff-watchface` designs drawn in this server's UI builder and install them. This file holds what
 * the routes ([installWatchFacePushRoutes]) stand on: the package naming rule, the marketplace
 * signing key, the push build, the durable state, and the device-code linking.
 *
 * The API is documented in `docs/serve/WATCHFACE_PUSH.md`.
 */
internal object WatchFacePushNaming {
  /** The push app's applicationId; the Watch Face Push API rejects any other prefix. */
  const val MARKETPLACE_PACKAGE = "ee.schimke.watchface.push"

  const val PACKAGE_PREFIX = "$MARKETPLACE_PACKAGE.watchfacepush."

  /** Longest design id kept as-is; anything longer is shortened and given a hash suffix. */
  private const val MAX_PLAIN = 48

  private val PLAIN = Regex("[A-Za-z][A-Za-z0-9_]*")
  private val FACE_NAME = Regex("[A-Za-z0-9_]+")

  /**
   * Words a Java package segment cannot be. The packager does not check them, but a package that
   * `aapt2` or the platform later refuses is worse than a hash suffix now.
   */
  private val RESERVED =
    setOf(
      "abstract",
      "assert",
      "boolean",
      "break",
      "byte",
      "case",
      "catch",
      "char",
      "class",
      "const",
      "continue",
      "default",
      "do",
      "double",
      "else",
      "enum",
      "extends",
      "false",
      "final",
      "finally",
      "float",
      "for",
      "goto",
      "if",
      "implements",
      "import",
      "instanceof",
      "int",
      "interface",
      "long",
      "native",
      "new",
      "null",
      "package",
      "private",
      "protected",
      "public",
      "return",
      "short",
      "static",
      "strictfp",
      "super",
      "switch",
      "synchronized",
      "this",
      "throw",
      "throws",
      "transient",
      "true",
      "try",
      "void",
      "volatile",
      "while",
    )

  /**
   * The `faceName` for [designId]: `[A-Za-z0-9_]+`, starting with a letter so the package is a
   * valid one, and one per design id.
   *
   * A design id that already is such a name (and is not too long or a Java keyword) is used as-is,
   * so `aurora` installs as `….watchfacepush.aurora`. Anything else is sanitised — every character
   * outside `[A-Za-z0-9_]` becomes `_`, a leading non-letter gains `d_` — and given `_` plus ten
   * hex characters of the id's SHA-256, so `my-face` and `my_face` never share a package (and so
   * never replace each other's install on a watch).
   */
  fun faceName(designId: String): String {
    require(designId.isNotEmpty()) { "a design id is required" }
    if (
      PLAIN.matches(designId) && designId.length <= MAX_PLAIN && designId.lowercase() !in RESERVED
    ) {
      return designId
    }
    val sanitized =
      designId
        .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_') it else '_' }
        .joinToString("")
        .take(MAX_PLAIN)
        .trimEnd('_')
    val head =
      if (sanitized.firstOrNull()?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) sanitized
      else "d_$sanitized".trimEnd('_')
    return head + "_" + sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)).take(10)
  }

  fun packageName(faceName: String): String = PACKAGE_PREFIX + faceName

  /** Whether [packageName] is `<marketplace>.watchfacepush.<faceName>`, as the app checks. */
  fun followsRule(packageName: String): Boolean =
    packageName.startsWith(PACKAGE_PREFIX) &&
      FACE_NAME.matches(packageName.removePrefix(PACKAGE_PREFIX))
}

internal fun sha256Hex(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun sha256Hex(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(64 * 1024)
    while (true) {
      val n = input.read(buffer)
      if (n < 0) break
      digest.update(buffer, 0, n)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * The marketplace signing key every push APK is signed with.
 *
 * Android installs an update only when it is signed by the certificate that signed the installed
 * APK, so a push marketplace signs **every** face with one long-lived key. It is therefore read
 * from the operator's configuration and never generated: a throwaway key would make every face
 * installed from this server un-updatable the next time the container is rebuilt. With no key
 * configured the push API stays up and says so ([Unavailable]); nothing is signed.
 *
 * Read from the environment with the names `wff-packager --keystore-from-env` itself uses, each
 * also as `NAME_FILE` ([ServeSecretEnv]):
 *
 * | Variable                        |                                                              |
 * |---------------------------------|--------------------------------------------------------------|
 * | `WFF_SIGNING_KEYSTORE_PATH`     | path to the PKCS12 keystore file, or                         |
 * | `WFF_SIGNING_KEYSTORE_B64`      | the keystore itself, base64                                  |
 * | `WFF_SIGNING_KEYSTORE_PASSWORD` | its store password (required)                                |
 * | `WFF_SIGNING_KEY_ALIAS`         | optional; defaults to the only key entry                     |
 * | `WFF_SIGNING_KEY_PASSWORD`      | optional; defaults to the store password                     |
 * | `WFF_SIGNING_CERT_SHA256`       | optional pin: refuse a key whose certificate is not this one |
 *
 * The keystore is opened here at startup, so a wrong password or pin is reported once, by name,
 * rather than on every build; the packager is handed exactly these variables and nothing else
 * signing-related ([WffPackagerProcess]).
 */
internal sealed interface WatchFacePushSigning {
  /** [environment] is what the packager's `--keystore-from-env` reads; never logged. */
  class Configured(
    val environment: Map<String, String>,
    val certificateSha256: String,
  ) : WatchFacePushSigning

  data class Unavailable(val reason: String) : WatchFacePushSigning

  companion object {
    const val KEYSTORE_PATH = "WFF_SIGNING_KEYSTORE_PATH"
    const val KEYSTORE_B64 = "WFF_SIGNING_KEYSTORE_B64"
    const val KEYSTORE_PASSWORD = "WFF_SIGNING_KEYSTORE_PASSWORD"
    const val KEY_ALIAS = "WFF_SIGNING_KEY_ALIAS"
    const val KEY_PASSWORD = "WFF_SIGNING_KEY_PASSWORD"
    const val CERT_SHA256 = "WFF_SIGNING_CERT_SHA256"

    fun fromEnvironment(
      env: Map<String, String> = System.getenv(),
      warn: (String) -> Unit = System.err::println,
    ): WatchFacePushSigning {
      val path = env[KEYSTORE_PATH]?.trim()?.takeIf { it.isNotEmpty() }
      val encoded = ServeSecretEnv.read(KEYSTORE_B64, env, warn)
      if (path == null && encoded == null) {
        return Unavailable(
          "no marketplace signing key is configured on this server: set $KEYSTORE_PATH (a " +
            "PKCS12 file) or $KEYSTORE_B64, and $KEYSTORE_PASSWORD",
        )
      }
      val keystore =
        try {
          if (path != null) {
            if (encoded != null)
              warn("serve: both $KEYSTORE_PATH and $KEYSTORE_B64 are set; using the file")
            File(path).readBytes()
          } else {
            Base64.getMimeDecoder().decode(encoded)
          }
        } catch (e: IOException) {
          return Unavailable("$KEYSTORE_PATH names $path, which could not be read")
        } catch (e: IllegalArgumentException) {
          return Unavailable("$KEYSTORE_B64 is not valid base64")
        }
      val password =
        ServeSecretEnv.read(KEYSTORE_PASSWORD, env, warn)
          ?: return Unavailable("$KEYSTORE_PASSWORD is required with the signing keystore")
      return load(
        keystore,
        password,
        alias = env[KEY_ALIAS]?.trim()?.takeIf { it.isNotEmpty() },
        keyPassword = ServeSecretEnv.read(KEY_PASSWORD, env, warn),
        pin = env[CERT_SHA256]?.trim()?.takeIf { it.isNotEmpty() },
      )
    }

    /** Open [keystore] and check its key against [pin]; public for tests. */
    fun load(
      keystore: ByteArray,
      password: String,
      alias: String? = null,
      keyPassword: String? = null,
      pin: String? = null,
    ): WatchFacePushSigning {
      val store =
        try {
          KeyStore.getInstance("PKCS12").apply {
            load(ByteArrayInputStream(keystore), password.toCharArray())
          }
        } catch (e: Exception) {
          return Unavailable(
            "the signing keystore could not be opened (${e.javaClass.simpleName}); check that it " +
              "is PKCS12 and that $KEYSTORE_PASSWORD is its password",
          )
        }
      val keyAliases = store.aliases().toList().filter(store::isKeyEntry)
      val chosen =
        alias
          ?: keyAliases.singleOrNull()
          ?: return Unavailable(
            "the signing keystore holds ${keyAliases.size} keys; set $KEY_ALIAS to choose one",
          )
      if (chosen !in keyAliases) return Unavailable("the signing keystore has no key `$chosen`")
      try {
        store.getKey(chosen, (keyPassword ?: password).toCharArray())
          ?: return Unavailable("the signing keystore has no private key `$chosen`")
      } catch (e: Exception) {
        return Unavailable("the signing key could not be read; check $KEY_PASSWORD")
      }
      val certificate =
        store.getCertificate(chosen)?.encoded
          ?: return Unavailable("the signing key `$chosen` has no certificate")
      val sha = sha256Hex(certificate)
      val wanted = pin?.replace(":", "")?.lowercase()
      if (wanted != null && wanted != sha) {
        return Unavailable(
          "the signing key's certificate SHA-256 is $sha, not the pinned $CERT_SHA256; a face " +
            "signed with it could not update one installed from this server",
        )
      }
      val environment = buildMap {
        put(KEYSTORE_B64, Base64.getEncoder().encodeToString(keystore))
        put(KEYSTORE_PASSWORD, password)
        put(KEY_ALIAS, chosen)
        keyPassword?.let { put(KEY_PASSWORD, it) }
        put(CERT_SHA256, sha)
      }
      return Configured(environment, sha)
    }
  }
}

/** A push build: a bundle in, a signed APK at `out`, the validation token out. */
internal fun interface WatchFacePushPackager {
  /**
   * Build [files] as `<marketplace>.watchfacepush.<faceName>` with [versionCode] into [out].
   *
   * @return the Watch Face Push validation token for the APK.
   * @throws DwfPackagingFailed when the packager or Google's validator refuses the face.
   */
  fun pack(files: Map<String, ByteArray>, faceName: String, versionCode: Int, out: File): String
}

/**
 * The push build through `wff-packager`'s CLI in its own JVM ([WffPackagerProcess]):
 * `--push-marketplace ee.schimke.watchface.push --face-name <faceName>` is
 * `PackageTarget.WatchFacePush(marketplacePackage, faceName)`, `--keystore-from-env` signs with
 * [signing], and `--token-out` hands back the validator's token.
 */
internal class WatchFacePushSubprocessPackager(
  private val libDir: File,
  private val sdk: DwfAndroidSdk,
  private val home: File,
  private val signing: WatchFacePushSigning.Configured,
  private val java: String = File(System.getProperty("java.home"), "bin/java").path,
  private val timeoutSeconds: Long = 180,
) : WatchFacePushPackager {
  override fun pack(
    files: Map<String, ByteArray>,
    faceName: String,
    versionCode: Int,
    out: File,
  ): String =
    WffPackagerProcess(libDir, sdk, home, java, timeoutSeconds).pack(
      files,
      arguments = { work ->
        listOf(
          "--out",
          out.path,
          "--version-code",
          versionCode.toString(),
          "--push-marketplace",
          WatchFacePushNaming.MARKETPLACE_PACKAGE,
          "--face-name",
          faceName,
          "--token-out",
          File(work, TOKEN_FILE).path,
          "--keystore-from-env",
          "true",
        )
      },
      environment = signing.environment,
    ) { work ->
      File(work, TOKEN_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw DwfPackagingFailed(
          listOf("error[push]: the packager issued no Watch Face Push validation token"),
        )
    }

  private companion object {
    const val TOKEN_FILE = "validation-token.txt"
  }
}

/** Whether push APKs can be built here, and with what. */
internal sealed interface WatchFacePushPackaging {
  class Ready(val packager: WatchFacePushPackager, val signerCertificateSha256: String?) :
    WatchFacePushPackaging

  data class Unavailable(val code: String, val reason: String) : WatchFacePushPackaging

  companion object {
    const val SIGNING_KEY_MISSING = "signing_key_unavailable"
    const val ANDROID_SDK_MISSING = "android_sdk_missing"
    const val PACKAGER_MISSING = "packager_missing"

    /**
     * The production answer, re-read per call so an SDK installed while the server runs is found.
     * The signing key is read once, at startup: the environment does not change under a process.
     */
    fun production(
      signing: WatchFacePushSigning,
      home: File,
      sdk: () -> DwfAndroidSdk? = { DwfAndroidSdk.locate() },
      libDir: () -> File? = { locateWffPackagerDir() },
    ): () -> WatchFacePushPackaging = {
      when {
        signing is WatchFacePushSigning.Unavailable ->
          Unavailable(SIGNING_KEY_MISSING, signing.reason)
        else -> {
          val located = sdk()
          val lib = libDir()
          when {
            located == null ->
              Unavailable(
                ANDROID_SDK_MISSING,
                "building a watch face needs an Android SDK on this server: " +
                  DwfAndroidSdk.INSTALL_HINT,
              )
            lib == null ->
              Unavailable(
                PACKAGER_MISSING,
                "this install has no lib-wff-packager/ (the watch-face packager); it ships in " +
                  "the server distribution",
              )
            else -> {
              val configured = signing as WatchFacePushSigning.Configured
              Ready(
                WatchFacePushSubprocessPackager(lib, located, home, configured),
                configured.certificateSha256,
              )
            }
          }
        }
      }
    }
  }
}

/**
 * What the push API keeps on disk, under the UI builder's state directory (`watchface-push/`):
 * ```text
 * devices.json                       linked watches: account, name, SHA-256 of the token
 * publications/<faceName>.json       one per design ever published to watches
 * apks/<faceName>-v<n>-<digest>.apk  built APKs, with a .json beside each
 * preview.key                        HMAC key for the signed preview URLs
 * ```
 *
 * A device token is never stored, only its SHA-256, so a copy of this directory cannot be used to
 * read anyone's designs. A publication is never deleted, only marked unpublished, because its
 * `version` must keep increasing: a face re-published after an unpublish must still be a higher
 * `versionCode` than the one already on somebody's watch.
 */
internal class WatchFacePushStore(
  private val root: Path,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  @Serializable
  data class Device(
    val id: String,
    val accountActorId: String,
    val deviceName: String,
    val wearOsApi: Int? = null,
    val tokenSha256: String,
    val createdAtEpochMillis: Long,
    val lastUsedEpochMillis: Long? = null,
  )

  @Serializable private data class Devices(val devices: List<Device> = emptyList())

  /**
   * One design's watch publication. [wffXml] and [wffVersion] are the WFF exported from [revision]
   * when it was published, kept here so the APK can be rebuilt after the revision has been pruned
   * from the design's history. [version] is the APK `versionCode`.
   */
  @Serializable
  data class Publication(
    val designId: String,
    val accountActorId: String,
    val faceName: String,
    val published: Boolean,
    val version: Long,
    val revision: Long,
    val title: String,
    val wffVersion: Int,
    val wffXml: String,
    val label: String,
    val digest: String,
    val publishedAtEpochMillis: Long,
  ) {
    fun source(): DwfSource = DwfSource(wffXml = wffXml, wffVersion = wffVersion, label = label)
  }

  /** A built APK's facts; the bytes are the `.apk` file beside it. */
  @Serializable
  data class Apk(
    val faceName: String,
    val version: Long,
    val digest: String,
    val packageName: String,
    val sha256: String,
    val sizeBytes: Long,
    val validationToken: String,
  )

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private val lock = Any()
  private val devicesFile = root.resolve("devices.json")
  private val publicationsDir = root.resolve("publications")
  val apkDir: Path = root.resolve("apks")
  private var devices: MutableList<Device>
  private val publications = ConcurrentHashMap<String, Publication>()

  /** The key behind [previewSignature]; random once per server state, kept across restarts. */
  private val previewKey: ByteArray

  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    ServeOwnerOnlyFiles.createDirectories(publicationsDir)
    ServeOwnerOnlyFiles.createDirectories(apkDir)
    devices =
      readJson(devicesFile, Devices.serializer())?.devices?.toMutableList() ?: mutableListOf()
    Files.list(publicationsDir).use { files ->
      files
        .filter { it.fileName.toString().endsWith(".json") }
        .forEach { file ->
          readJson(file, Publication.serializer())?.let { publications[it.designId] = it }
        }
    }
    val keyFile = root.resolve("preview.key")
    previewKey =
      runCatching { Files.readAllBytes(keyFile) }.getOrNull()?.takeIf { it.size >= 32 }
        ?: ByteArray(32).also {
          SecureRandom().nextBytes(it)
          writeAtomically(keyFile, it)
        }
  }

  // ------------------------------------------------------------------ devices

  fun devices(accountActorId: String): List<Device> =
    synchronized(lock) { devices.filter { it.accountActorId == accountActorId } }

  fun deviceForToken(token: String): Device? {
    val hash = tokenHash(token)
    return synchronized(lock) {
      devices.firstOrNull { ServeUrls.tokensMatch(it.tokenSha256, hash) }
    }
  }

  /** Mint a device token for [accountActorId]; the token itself is returned and never stored. */
  fun link(accountActorId: String, deviceName: String, wearOsApi: Int?): Pair<Device, String> {
    val token = TOKEN_PREFIX + randomToken(32)
    val device =
      Device(
        id = randomToken(12),
        accountActorId = accountActorId,
        deviceName = deviceName,
        wearOsApi = wearOsApi,
        tokenSha256 = tokenHash(token),
        createdAtEpochMillis = clock(),
      )
    synchronized(lock) {
      devices.add(device)
      persistDevices()
    }
    return device to token
  }

  /** Revoke [deviceId] if it belongs to [accountActorId] (null: any account); true if it did. */
  fun revoke(deviceId: String, accountActorId: String?): Boolean =
    synchronized(lock) {
      val removed = devices.removeIf {
        it.id == deviceId && (accountActorId == null || it.accountActorId == accountActorId)
      }
      if (removed) persistDevices()
      removed
    }

  /** Record a use, persisting at most hourly per device: it is a hint for the person, not audit. */
  fun touch(device: Device) {
    val now = clock()
    val last = device.lastUsedEpochMillis ?: 0
    if (now - last < TOUCH_PERSIST_MILLIS) return
    synchronized(lock) {
      val index = devices.indexOfFirst { it.id == device.id }
      if (index < 0) return
      devices[index] = devices[index].copy(lastUsedEpochMillis = now)
      persistDevices()
    }
  }

  private fun persistDevices() {
    writeAtomically(
      devicesFile,
      json.encodeToString(Devices.serializer(), Devices(devices.toList())).toByteArray(),
    )
  }

  // ------------------------------------------------------------- publications

  fun publication(designId: String): Publication? = publications[designId]

  /** Every design currently published to watches, newest first. */
  fun published(): List<Publication> =
    publications.values.filter { it.published }.sortedByDescending { it.publishedAtEpochMillis }

  /**
   * Publish [source], exported from [revision], as [designId]'s watch face for [accountActorId].
   *
   * The version moves only when the face does: re-publishing an unchanged face keeps its version,
   * so watches are not offered an "update" to the same APK; any change to the WFF (or to an asset
   * or the WFF version) is `version + 1`. Unpublished faces keep their counter.
   */
  fun publish(
    designId: String,
    accountActorId: String,
    revision: Long,
    title: String,
    source: DwfSource,
  ): Publication =
    synchronized(lock) {
      val previous = publications[designId]
      val digest = source.digest()
      val version =
        when {
          previous == null -> 1L
          previous.digest == digest -> previous.version
          else -> previous.version + 1
        }
      val publication =
        Publication(
          designId = designId,
          accountActorId = accountActorId,
          faceName = WatchFacePushNaming.faceName(designId),
          published = true,
          version = version,
          revision = revision,
          title = title,
          wffVersion = source.wffVersion,
          wffXml = source.wffXml,
          label = source.label,
          digest = digest,
          publishedAtEpochMillis =
            if (previous?.published == true && previous.digest == digest) {
              previous.publishedAtEpochMillis
            } else clock(),
        )
      store(publication)
      publication
    }

  /** Take [designId] off watches; its version counter is kept. Null when it was never published. */
  fun unpublish(designId: String): Publication? =
    synchronized(lock) {
      val previous = publications[designId] ?: return null
      val updated = previous.copy(published = false)
      store(updated)
      updated
    }

  private fun store(publication: Publication) {
    writeAtomically(
      publicationsDir.resolve("${publication.faceName}.json"),
      json.encodeToString(Publication.serializer(), publication).toByteArray(),
    )
    publications[publication.designId] = publication
  }

  // --------------------------------------------------------------------- APKs

  fun apkFile(publication: Publication): File =
    apkDir.resolve(apkBase(publication) + ".apk").toFile()

  fun apk(publication: Publication): Apk? {
    val file = apkFile(publication)
    if (!file.isFile) return null
    return readJson(apkDir.resolve(apkBase(publication) + ".json"), Apk.serializer())
  }

  /** Record [built] (already at [apkFile]) with its token; returns its facts. */
  fun recordApk(publication: Publication, token: String): Apk {
    val file = apkFile(publication)
    val apk =
      Apk(
        faceName = publication.faceName,
        version = publication.version,
        digest = publication.digest,
        packageName = WatchFacePushNaming.packageName(publication.faceName),
        sha256 = sha256Hex(file),
        sizeBytes = file.length(),
        validationToken = token,
      )
    writeAtomically(
      apkDir.resolve(apkBase(publication) + ".json"),
      json.encodeToString(Apk.serializer(), apk).toByteArray(),
    )
    return apk
  }

  private fun apkBase(publication: Publication): String =
    "${publication.faceName}-v${publication.version}-${publication.digest.take(16)}"

  // ------------------------------------------------------------------ preview

  /** An unguessable, per-version signature, so the watch can fetch a picture with no header. */
  fun previewSignature(designId: String, version: Long): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(previewKey, "HmacSHA256"))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(mac.doFinal("$designId|$version".toByteArray(StandardCharsets.UTF_8)))
      .take(32)
  }

  // ------------------------------------------------------------------ helpers

  private fun <T> readJson(file: Path, serializer: kotlinx.serialization.KSerializer<T>): T? {
    if (!Files.isRegularFile(file)) return null
    return try {
      json.decodeFromString(serializer, Files.readString(file, StandardCharsets.UTF_8))
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      System.err.println("serve: watchface-push: $file is unreadable; ignoring it")
      null
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private fun writeAtomically(target: Path, bytes: ByteArray) {
    val tmp = Files.createTempFile(target.parent, ".tmp-", ".part")
    try {
      Files.write(tmp, bytes)
      Files.move(
        tmp,
        target,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  companion object {
    /** Marks a device token in logs and leaked-secret scanners; carries no meaning. */
    const val TOKEN_PREFIX = "wfp_"
    private const val TOUCH_PERSIST_MILLIS = 60 * 60 * 1000L
    private val random = SecureRandom()

    fun tokenHash(token: String): String = sha256Hex(token.toByteArray(StandardCharsets.UTF_8))

    fun randomToken(bytes: Int): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(ByteArray(bytes).also(random::nextBytes))
  }
}

/**
 * Device-code linking ("OAuth device flow" shaped): the watch asks for a code, shows it, and polls
 * with a secret only it knows while a signed-in person confirms the code on `/link`. In memory on
 * purpose — a code lives ten minutes, and a restart costs the person one retry — while the device
 * token an approval mints is durable ([WatchFacePushStore.link]).
 */
internal class WatchFacePushDeviceCodes(
  private val store: WatchFacePushStore,
  private val clock: () -> Long = System::currentTimeMillis,
  val lifetimeSeconds: Int = DEFAULT_LIFETIME_SECONDS,
  val pollIntervalSeconds: Int = DEFAULT_POLL_INTERVAL_SECONDS,
  private val maxPending: Int = DEFAULT_MAX_PENDING,
) {
  enum class State {
    PENDING,
    APPROVED,
    DENIED,
  }

  class Pending(
    val code: String,
    val pollSecretHash: String,
    val deviceName: String,
    val wearOsApi: Int?,
    val expiresAtEpochMillis: Long,
  ) {
    @Volatile var state: State = State.PENDING
    @Volatile var lastPollEpochMillis: Long = 0
    @Volatile var approvedAccount: String? = null
    @Volatile var approvedDeviceId: String? = null

    /** Held only from approval until the one poll that delivers it. */
    @Volatile var token: String? = null
  }

  sealed interface Poll {
    data object Pending : Poll

    data class Approved(val deviceToken: String, val accountName: String) : Poll

    data object Denied : Poll

    data object Expired : Poll

    /** Unknown, consumed, or the wrong poll secret: indistinguishable on purpose. */
    data object NotFound : Poll

    data class TooFast(val retryAfterSeconds: Long) : Poll
  }

  private val codes = ConcurrentHashMap<String, Pending>()

  /** Null when too many codes are pending (the caller answers 429). */
  fun issue(deviceName: String, wearOsApi: Int?): Pair<Pending, String>? {
    sweep()
    if (codes.size >= maxPending) return null
    val secret = WatchFacePushStore.randomToken(32)
    while (true) {
      val code = newCode()
      val pending =
        Pending(
          code = code,
          pollSecretHash = WatchFacePushStore.tokenHash(secret),
          deviceName = deviceName,
          wearOsApi = wearOsApi,
          expiresAtEpochMillis = clock() + lifetimeSeconds * 1000L,
        )
      if (codes.putIfAbsent(code, pending) == null) return pending to secret
    }
  }

  /** The pending code a person typed or scanned, normalised; null when unknown or expired. */
  fun pending(raw: String?): Pending? {
    val code = normalize(raw) ?: return null
    val pending = codes[code] ?: return null
    return pending.takeIf { it.expiresAtEpochMillis > clock() }
  }

  fun poll(raw: String, secret: String?): Poll {
    val code = normalize(raw) ?: return Poll.NotFound
    val pending = codes[code] ?: return Poll.NotFound
    if (
      secret == null ||
        !ServeUrls.tokensMatch(pending.pollSecretHash, WatchFacePushStore.tokenHash(secret))
    ) {
      return Poll.NotFound
    }
    val now = clock()
    if (now >= pending.expiresAtEpochMillis) {
      expire(pending)
      return Poll.Expired
    }
    synchronized(pending) {
      // One second of slack for a client polling on time over a slow network.
      val early = pending.lastPollEpochMillis + (pollIntervalSeconds - 1) * 1000L - now
      if (pending.lastPollEpochMillis > 0 && early > 0) {
        return Poll.TooFast(pollIntervalSeconds.toLong())
      }
      pending.lastPollEpochMillis = now
      return when (pending.state) {
        State.PENDING -> Poll.Pending
        State.DENIED -> {
          codes.remove(code, pending)
          Poll.Denied
        }
        State.APPROVED -> {
          // Delivered once: the code is consumed with this answer.
          codes.remove(code, pending)
          val token = pending.token ?: return Poll.NotFound
          pending.token = null
          Poll.Approved(token, accountName(pending.approvedAccount.orEmpty()))
        }
      }
    }
  }

  /** Approve [code] for [accountActorId]; false when it is no longer pending. */
  fun approve(code: String, accountActorId: String): Boolean {
    val pending = pending(code) ?: return false
    synchronized(pending) {
      if (pending.state != State.PENDING) return false
      val (device, token) = store.link(accountActorId, pending.deviceName, pending.wearOsApi)
      pending.approvedAccount = accountActorId
      pending.approvedDeviceId = device.id
      pending.token = token
      pending.state = State.APPROVED
      return true
    }
  }

  fun deny(code: String): Boolean {
    val pending = pending(code) ?: return false
    synchronized(pending) {
      if (pending.state != State.PENDING) return false
      pending.state = State.DENIED
      return true
    }
  }

  /**
   * Forget expired codes. An approval the watch never collected is revoked with it, so a token
   * nobody holds does not sit in the person's device list.
   */
  fun sweep() {
    val now = clock()
    codes.values.filter { now >= it.expiresAtEpochMillis + SWEEP_GRACE_MILLIS }.forEach(::expire)
  }

  private fun expire(pending: Pending) {
    codes.remove(pending.code, pending)
    synchronized(pending) {
      if (pending.token != null) {
        pending.approvedDeviceId?.let { store.revoke(it, null) }
        pending.token = null
      }
    }
  }

  private fun newCode(): String {
    val chars = CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] }
    return String(chars, 0, 4) + "-" + String(chars, 4, 4)
  }

  companion object {
    /** No `0`/`O` or `1`/`I`: a code is read off a watch and typed. */
    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val DEFAULT_LIFETIME_SECONDS = 600
    const val DEFAULT_POLL_INTERVAL_SECONDS = 5
    const val DEFAULT_MAX_PENDING = 2000

    /** An expired code still answers `expired` (rather than 404) for this long. */
    private const val SWEEP_GRACE_MILLIS = 10 * 60 * 1000L
    private val random = SecureRandom()

    /** `ab7k 92qx`, `AB7K92QX` and `AB7K-92QX` are one code; anything else is none. */
    fun normalize(raw: String?): String? {
      val compact = raw?.uppercase()?.filter { it != '-' && !it.isWhitespace() } ?: return null
      if (compact.length != 8 || compact.any { it !in ALPHABET }) return null
      return compact.substring(0, 4) + "-" + compact.substring(4)
    }

    /** What the watch shows as "linked to": the GitHub login for a GitHub actor. */
    fun accountName(accountActorId: String): String = accountActorId.removePrefix("github:")
  }
}

/**
 * Builds and caches push APKs, one at a time: the packager runs `aapt2` and Google's validator,
 * which are memory-hungry, and a listing that finds several faces unbuilt should not start them all
 * at once. A build is keyed by `(faceName, version, digest)`, so an unchanged revision is never
 * rebuilt — across requests and across restarts.
 */
internal class WatchFacePushBuilds(
  private val store: WatchFacePushStore,
  private val packaging: () -> WatchFacePushPackaging,
) {
  sealed interface Result {
    data class Built(val apk: WatchFacePushStore.Apk, val file: File) : Result

    data class Unavailable(val code: String, val reason: String) : Result

    data class Failed(val diagnostics: List<String>) : Result
  }

  private val lock = Any()

  /** Failures by build key, so a face the packager refuses is not re-run on every listing. */
  private val failures = ConcurrentHashMap<String, List<String>>()

  fun cached(publication: WatchFacePushStore.Publication): Result.Built? =
    store.apk(publication)?.let { Result.Built(it, store.apkFile(publication)) }

  fun build(publication: WatchFacePushStore.Publication): Result {
    cached(publication)?.let {
      return it
    }
    val key = "${publication.faceName}|${publication.version}|${publication.digest}"
    failures[key]?.let {
      return Result.Failed(it)
    }
    val ready =
      when (val available = packaging()) {
        is WatchFacePushPackaging.Unavailable ->
          return Result.Unavailable(available.code, available.reason)
        is WatchFacePushPackaging.Ready -> available
      }
    synchronized(lock) {
      cached(publication)?.let {
        return it
      }
      val out = store.apkFile(publication)
      val tmp = File(out.parentFile, out.name + ".tmp")
      return try {
        val source = publication.source()
        source.problem()?.let { throw DwfPackagingFailed(listOf("error[bundle]: $it")) }
        val files =
          DwfPackaging.bundleFiles(
            source,
            WatchFacePushNaming.packageName(publication.faceName),
          )
        val token =
          ready.packager.pack(
            files,
            publication.faceName,
            publication.version.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            tmp,
          )
        check(tmp.isFile && tmp.length() > 0L) { "the packager wrote no APK" }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        Result.Built(store.recordApk(publication, token), out)
      } catch (e: DwfPackagingFailed) {
        failures[key] = e.diagnostics
        Result.Failed(e.diagnostics)
      } catch (e: Exception) {
        // Not remembered: a timeout or a full disk may well succeed next time.
        Result.Failed(listOf("error[push]: ${e.message ?: e.javaClass.simpleName}"))
      } finally {
        tmp.delete()
      }
    }
  }
}
