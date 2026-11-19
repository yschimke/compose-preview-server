package ee.schimke.composeai.cli.serve

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `previews/<id>.wff.json`, mirrored from compose-ai-tools' `:bundle-format`
 * (`ee.schimke.composeai.bundle.WffSourceManifest`, compose-ai-tools#6647) until a release of it is
 * pinned here. Read with unknown keys ignored, so an additive change upstream does not break it.
 */
@Serializable
internal data class WffSourceManifestV1(
  val schemaVersion: Int = SCHEMA_VERSION,
  val previewId: String,
  val bundle: String,
  val wffVersion: Int? = null,
  val repoUrl: String? = null,
  val commit: String? = null,
  val sourcePath: String? = null,
  val sourceUrl: String? = null,
  val license: String? = null,
  val licenseFile: String? = null,
  val files: List<String> = emptyList(),
) {
  companion object {
    const val SCHEMA_VERSION = 1
  }
}

/**
 * The licences under which this server redistributes somebody else's watch face as an APK.
 *
 * The producer records what it detects and never filters ([WffSourceManifestV1.license]); this is
 * the consumer's allowlist the format asks for. Each permits redistribution of a modified build
 * with attribution, which the listing carries. An unknown licence (`null`) is not on it: no licence
 * means no permission.
 */
internal object WatchFacePushLicenses {
  val REDISTRIBUTABLE: Set<String> =
    setOf("Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause", "CC0-1.0", "Unlicense", "CC-BY-4.0")

  /**
   * Whether [spdx] permits it: a plain id on the list, or an SPDX expression where some `OR`
   * alternative has every `AND` part on it (`Apache-2.0 OR GPL-3.0` is fine, `MIT AND GPL-3.0` is
   * not). Parentheses and `WITH` exceptions are not understood, so they are refused.
   */
  fun permits(spdx: String?): Boolean {
    val expression = spdx?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    if ('(' in expression || ')' in expression || " WITH " in expression.uppercase()) return false
    val allowed = REDISTRIBUTABLE.map { it.lowercase() }.toSet()
    return expression.split(Regex("\\s+OR\\s+", RegexOption.IGNORE_CASE)).any { alternative ->
      alternative.split(Regex("\\s+AND\\s+", RegexOption.IGNORE_CASE)).all {
        it.trim().lowercase() in allowed
      }
    }
  }
}

/** One catalog watch-face preview that carries its WFF source, as the host found it. */
internal class CatalogFaceSource(
  val system: String,
  val catalogTitle: String,
  val previewId: String,
  val title: String,
  /** The text of `previews/<id>.wff.json`. */
  val manifestJson: String,
  /** `previews/<id>.wff.zip`. */
  val zip: File,
  /** The catalog's baked picture of the face. */
  val png: () -> ByteArray?,
)

/**
 * The faces from the served catalogs (preview.coo.ee's `wear-os-samples-watchfaces`, say) that the
 * Watch Face Push API lists beside UI-builder designs: every listed catalog's watch-face preview
 * whose bundle carries its Watch Face Format source and whose licence is
 * [WatchFacePushLicenses.REDISTRIBUTABLE]. They are public, so anyone may list and install them.
 *
 * Each is packaged by the same push build as a design, signed with the same marketplace key, under
 * its own package name ([faceName]) that never meets a design's or the upstream app's. Its
 * `version` moves when its source does, and is kept in `catalog-faces.json` beside the rest of the
 * push state so it only ever grows.
 */
internal class WatchFacePushCatalogFaces(private val stateFile: Path) {
  /** A face that can be listed: its source, its version, and the attribution to show. */
  class Face(
    val source: CatalogFaceSource,
    val manifest: WffSourceManifestV1,
    val dwf: DwfSource,
    val publication: WatchFacePushStore.Publication,
  )

  /** Why a catalog face is not listed; reported on `/status`. */
  data class Skipped(val id: String, val reason: String)

  class Resolution(val faces: List<Face>, val skipped: List<Skipped>)

  @Serializable private data class Versions(val faces: Map<String, Entry> = emptyMap())

  @Serializable private data class Entry(val digest: String, val version: Long)

  private val json = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
  }
  private val lock = Any()
  private val versions: MutableMap<String, Entry> = readVersions().toMutableMap()

  /** Parsed sources by zip identity, so an unchanged zip is read once. */
  private val parsed = ConcurrentHashMap<String, Pair<String, Any>>()

  fun resolve(sources: List<CatalogFaceSource>): Resolution {
    val faces = mutableListOf<Face>()
    val skipped = mutableListOf<Skipped>()
    for (source in sources) {
      val id = id(source.system, source.previewId)
      when (val result = read(source)) {
        is String -> skipped += Skipped(id, result)
        is Face -> faces += result
      }
    }
    return Resolution(faces, skipped)
  }

  /** A [Face] or the reason (a String) it is skipped. */
  private fun read(source: CatalogFaceSource): Any {
    val identity =
      "${source.zip.path}|${source.zip.length()}|${source.zip.lastModified()}|" +
        source.manifestJson.hashCode()
    val key = id(source.system, source.previewId)
    val cached = parsed[key]?.takeIf { it.first == identity }?.second
    val parsedSource = cached ?: parse(source).also { parsed[key] = identity to it }
    val dwf = parsedSource as? Parsed ?: return parsedSource
    return Face(source, dwf.manifest, dwf.source, publication(source, dwf))
  }

  private class Parsed(val manifest: WffSourceManifestV1, val source: DwfSource)

  private fun parse(source: CatalogFaceSource): Any {
    val manifest =
      try {
        json.decodeFromString(WffSourceManifestV1.serializer(), source.manifestJson)
      } catch (_: SerializationException) {
        return "its .wff.json is not a WFF source manifest"
      } catch (_: IllegalArgumentException) {
        return "its .wff.json is not a WFF source manifest"
      }
    if (manifest.schemaVersion != WffSourceManifestV1.SCHEMA_VERSION) {
      return "its .wff.json is schema ${manifest.schemaVersion}, not ${WffSourceManifestV1.SCHEMA_VERSION}"
    }
    if (!WatchFacePushLicenses.permits(manifest.license)) {
      return "licence ${manifest.license ?: "unknown"} is not one this server redistributes " +
        "(${WatchFacePushLicenses.REDISTRIBUTABLE.joinToString()})"
    }
    val wffVersion = manifest.wffVersion ?: DwfPreviewSurface.DEFAULT_WFF_VERSION
    val entries =
      try {
        readZip(source.zip)
      } catch (e: IOException) {
        return "its .wff.zip could not be read (${e.message})"
      } catch (e: IllegalStateException) {
        return e.message ?: "its .wff.zip is not usable"
      }
    val xml =
      entries[WATCH_FACE_ENTRY]?.toString(StandardCharsets.UTF_8)
        ?: return "its .wff.zip has no $WATCH_FACE_ENTRY"
    // The server writes the manifest and the face info itself, as for a design, so the face is
    // packaged under this marketplace's rules whatever the source module declared.
    val assets =
      entries
        .filterKeys { it.startsWith("res/") }
        .mapKeys { it.key.removePrefix("res/") }
        .filterKeys { DwfSource.normalizedAssetPath(it) !in SERVER_WRITTEN }
    val dwf =
      DwfSource(
        wffXml = xml,
        wffVersion = wffVersion,
        assets = assets,
        label = source.title.replace(Regex("\\p{Cntrl}"), "").take(64).ifBlank { source.previewId },
      )
    dwf.problem()?.let {
      return it
    }
    return Parsed(manifest, dwf)
  }

  /** Every entry of [zip] by name: bounded in count and bytes, and never a path that escapes. */
  private fun readZip(zip: File): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    var total = 0L
    ZipFile(zip).use { file ->
      val entries = file.entries().toList()
      check(entries.size <= MAX_ENTRIES) { "its .wff.zip has more than $MAX_ENTRIES entries" }
      for (entry in entries) {
        if (entry.isDirectory) continue
        val name = entry.name.replace('\\', '/')
        val segments = name.split('/')
        check(!name.startsWith("/") && segments.none { it == ".." || it.isEmpty() }) {
          "its .wff.zip has an unsafe entry name"
        }
        val bytes =
          file.getInputStream(entry).use { input ->
            val read = input.readNBytes((MAX_ZIP_BYTES - total + 1).toInt().coerceAtLeast(1))
            total += read.size
            check(total <= MAX_ZIP_BYTES) { "its .wff.zip is larger than $MAX_ZIP_BYTES bytes" }
            read
          }
        out[name] = bytes
      }
    }
    return out
  }

  /**
   * The publication-shaped record the push build and APK cache key on. Its version is the stored
   * one, raised by one whenever the face's content digest changes.
   */
  private fun publication(
    source: CatalogFaceSource,
    parsed: Parsed,
  ): WatchFacePushStore.Publication {
    val id = id(source.system, source.previewId)
    val digest = parsed.source.digest()
    val version =
      synchronized(lock) {
        val current = versions[id]
        val next =
          when {
            current == null -> Entry(digest, 1)
            current.digest == digest -> current
            else -> Entry(digest, current.version + 1)
          }
        if (next != current) {
          versions[id] = next
          writeVersions()
        }
        next.version
      }
    return WatchFacePushStore.Publication(
      designId = id,
      accountActorId = "",
      faceName = faceName(source.system, source.previewId),
      published = true,
      version = version,
      revision = 0,
      title = source.title,
      wffVersion = parsed.source.wffVersion,
      wffXml = parsed.source.wffXml,
      label = parsed.source.label,
      digest = digest,
      publishedAtEpochMillis = 0,
    )
  }

  private fun readVersions(): Map<String, Entry> =
    try {
      if (!Files.isRegularFile(stateFile)) emptyMap()
      else
        json
          .decodeFromString(
            Versions.serializer(),
            Files.readString(stateFile, StandardCharsets.UTF_8),
          )
          .faces
    } catch (_: Exception) {
      System.err.println(
        "serve: watchface-push: $stateFile is unreadable; catalog versions restart",
      )
      emptyMap()
    }

  private fun writeVersions() {
    Files.createDirectories(stateFile.parent)
    val tmp = Files.createTempFile(stateFile.parent, ".tmp-", ".part")
    try {
      Files.writeString(tmp, json.encodeToString(Versions.serializer(), Versions(versions.toMap())))
      Files.move(
        tmp,
        stateFile,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      Files.deleteIfExists(tmp)
    }
  }

  companion object {
    const val WATCH_FACE_ENTRY = "res/raw/watchface.xml"
    private const val MAX_ENTRIES = 512
    private const val MAX_ZIP_BYTES = 16L * 1024 * 1024
    private val SERVER_WRITTEN = setOf("raw/watchface.xml", "xml/watch_face_info.xml")

    /** The listing id: `catalog:<system>/<previewId>`. Display and matching only, never a path. */
    fun id(system: String, previewId: String): String = "catalog:$system/$previewId"

    /**
     * The face name for a catalog face: `c_`, the sanitised system and preview, `_` and ten hex
     * characters of the SHA-256 of its [id]. It is always hashed, and the hash input starts with
     * `catalog:`, which no design id's hash shares, so it can never be a design's name; and it is
     * under this marketplace's prefix, so never the upstream app's own package.
     */
    fun faceName(system: String, previewId: String): String {
      val sanitized =
        "${system}_$previewId"
          .map { if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') it else '_' }
          .joinToString("")
          .take(40)
          .trimEnd('_')
      return "c_${sanitized}_" +
        sha256Hex(id(system, previewId).toByteArray(StandardCharsets.UTF_8)).take(10)
    }
  }
}
