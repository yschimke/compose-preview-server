package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** One labelled fact about an ingested document, shown on the document page's detail list. */
data class ServeDocFact(val key: String, val value: String)

/** A document's declared drawing size, when its format announces one. */
data class ServeDocSize(val width: Int, val height: Int)

/**
 * A known document format the serve host can ingest ([ServeDocStore]) and hand back as an expiring
 * permalink. Formats are data-only: the server stores and sniffs the bytes, and the browser plays
 * them with the format's vendored player. Adding a format is one entry plus its player bundle.
 *
 * @param id stable wire id (`remotecompose`, `lottie`), used in the upload response and
 *   `/doc-player/<id>/bundle.js`.
 * @param label human name for the document page.
 * @param extension canonical file extension for the raw download's filename.
 * @param contentType what `GET /d/<id>/raw` responds with.
 * @param playerResource classpath path of the vendored player bundle served at
 *   `/doc-player/<id>/bundle.js`.
 * @param detect content sniff, run before anything else touches the upload so a mislabelled or
 *   hostile file is rejected on shape, not name.
 * @param describe best-effort facts for the document page (dimensions, duration, version …).
 * @param size the declared drawing size, if any; the page sizes the player's stage with it before
 *   load, since a canvas player can't recover from a later resize.
 */
data class ServeDocFormat(
  val id: String,
  val label: String,
  val extension: String,
  val contentType: String,
  val playerResource: String,
  val detect: (ByteArray) -> Boolean,
  val describe: (ByteArray) -> List<ServeDocFact>,
  val size: (ByteArray) -> ServeDocSize?,
  /**
   * Why an otherwise-recognised document can't be played here, or null. Checked at upload so the
   * uploader gets a refusal rather than a permalink that plays wrong.
   */
  val unsupported: (ByteArray) -> String? = { null },
) {
  /** URL of this format's browser player bundle (mounted by `ServeHttpServer`). */
  val playerPath: String
    get() = "/doc-player/$id/bundle.js"
}

/**
 * The known document formats and the sniffing that maps uploaded bytes onto one. Both are data-only
 * tiers (see `docs/public-preview-server.md`): playback runs in the viewer's browser, never on the
 * server, so anonymous uploads are safe.
 */
object ServeDocFormats {

  /**
   * Remote Compose document (`.rc`), played by the vendored `RC.RcdPlayer`.
   *
   * Sniffed on the `Header` operation (opcode `0x00`, then a big-endian `major` int) in the two
   * forms `RcDocumentCodec`'s `HeaderCodec` accepts:
   * - **tagged**: high 16 bits are the magic (`0x048C`), low 16 the major version, then minor,
   *   patch and a property table carrying the declared size;
   * - **untagged** (AndroidX writer): high 16 bits zero; major, minor, patch, then fixed width,
   *   height and capabilities.
   */
  val REMOTE_COMPOSE =
    ServeDocFormat(
      id = "remotecompose",
      label = "Remote Compose",
      extension = ".rc",
      contentType = "application/octet-stream",
      playerResource = "/rc-player/bundle.js",
      detect = ::isRemoteComposeDoc,
      describe = ::describeRemoteCompose,
      size = ::remoteComposeSize,
    )

  /**
   * Lottie animation (Bodymovin JSON), played by the vendored `lottie-web`. Sniffed on shape: an
   * object with a `layers` array plus `fr`/`ip`/`op`.
   */
  val LOTTIE =
    ServeDocFormat(
      id = "lottie",
      label = "Lottie",
      extension = ".json",
      contentType = "application/json",
      playerResource = "/lottie-player/bundle.js",
      detect = { bytes -> parseLottie(bytes) != null },
      describe = ::describeLottie,
      size = ::lottieSize,
      unsupported = ::lottieUnsupported,
    )

  /** Every known format, in the order the upload path sniffs them. */
  val ALL: List<ServeDocFormat> = listOf(REMOTE_COMPOSE, LOTTIE)

  fun byId(id: String): ServeDocFormat? = ALL.firstOrNull { it.id == id }

  /** The format [bytes] are, or null. Content-sniffed; the filename never decides the format. */
  fun detect(bytes: ByteArray): ServeDocFormat? = ALL.firstOrNull { it.detect(bytes) }

  /** Human list of what an upload may be, for the error a rejected upload gets back. */
  fun knownSummary(): String = ALL.joinToString(", ") { "${it.label} (${it.extension})" }

  // ---- Remote Compose ----------------------------------------------------------------------

  private const val RC_MAGIC = 0x048C

  /**
   * opcode(1) + magic|major(4) + minor(4) + patch(4): the smallest tagged header worth accepting.
   */
  private const val TAGGED_HEADER_MIN_BYTES = 13

  /**
   * opcode(1) + major(4) + minor(4) + patch(4) + width(4) + height(4) + capabilities(8): an
   * untagged header has no property table, so the whole fixed layout must be present.
   */
  private const val UNTAGGED_HEADER_BYTES = 29

  /**
   * Which header form [bytes] open with, or null when they are not a Remote Compose document.
   *
   * Mirrors rc-players' `HeaderCodec.decode`: a `major` word below `0x10000` is untagged, otherwise
   * its high 16 bits must be [RC_MAGIC]. Having no magic, the untagged form is held to what a real
   * writer emits (major ≥ 1, positive width and height) so arbitrary buffers are refused.
   */
  private fun remoteComposeHeaderForm(bytes: ByteArray): HeaderForm? {
    if (bytes.size < TAGGED_HEADER_MIN_BYTES) return null
    if (bytes[0].toInt() != 0) return null
    val encodedMajor = bytes.intAt(1)
    if (encodedMajor ushr 16 == RC_MAGIC) return HeaderForm.TAGGED
    if (encodedMajor ushr 16 != 0 || encodedMajor < 1) return null
    if (bytes.size < UNTAGGED_HEADER_BYTES) return null
    val width = bytes.intAt(13)
    val height = bytes.intAt(17)
    return if (width > 0 && height > 0) HeaderForm.UNTAGGED else null
  }

  private enum class HeaderForm {
    TAGGED,
    UNTAGGED,
  }

  private fun ByteArray.intAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
      ((this[offset + 1].toInt() and 0xFF) shl 16) or
      ((this[offset + 2].toInt() and 0xFF) shl 8) or
      (this[offset + 3].toInt() and 0xFF)

  private fun isRemoteComposeDoc(bytes: ByteArray): Boolean = remoteComposeHeaderForm(bytes) != null

  /** Version + declared size from the document header. */
  private fun describeRemoteCompose(bytes: ByteArray): List<ServeDocFact> {
    val header = readRemoteComposeHeader(bytes) ?: return emptyList()
    val facts = mutableListOf<ServeDocFact>()
    facts += ServeDocFact("Format version", header.version)
    header.size?.let { facts += ServeDocFact("Document size", "${it.width} × ${it.height}") }
    return facts
  }

  private fun remoteComposeSize(bytes: ByteArray): ServeDocSize? =
    readRemoteComposeHeader(bytes)?.size

  private class RemoteComposeHeader(val version: String, val size: ServeDocSize?)

  /**
   * Read the `Header` operation's version and declared size, in either form. Total: a malformed
   * table stops the walk and yields what was read, since this only feeds display.
   */
  private fun readRemoteComposeHeader(bytes: ByteArray): RemoteComposeHeader? {
    val form = remoteComposeHeaderForm(bytes) ?: return null
    var version = "unknown"
    var width: Int? = null
    var height: Int? = null
    try {
      val reader = ByteReader(bytes, offset = 1)
      val major = reader.int() and 0xFFFF
      val minor = reader.int()
      val patch = reader.int()
      version = "$major.$minor.$patch"
      when (form) {
        HeaderForm.UNTAGGED -> {
          // Fixed layout: width, height, then a capabilities long nothing here needs.
          width = reader.int()
          height = reader.int()
        }
        HeaderForm.TAGGED -> {
          val propertyCount = reader.int()
          // Property table: a short tag (dataType = tag shr 10, key = tag and 0x3F, as AndroidX
          // `Header.readMap` masks it), a short byte length, then the value. Unknown types are
          // skipped by length.
          repeat(propertyCount.coerceIn(0, MAX_HEADER_PROPERTIES)) {
            val tag = reader.short()
            val dataType = tag shr 10
            val key = tag and 0x3F
            val length = reader.short()
            if (dataType == DATA_TYPE_INT) {
              val value = reader.int()
              if (key == DOC_WIDTH) width = value
              if (key == DOC_HEIGHT) height = value
            } else {
              reader.skip(length)
            }
          }
        }
      }
    } catch (e: IndexOutOfBoundsException) {
      // Truncated header — keep whatever was read.
    }
    val w = width
    val h = height
    return RemoteComposeHeader(version, if (w != null && h != null) ServeDocSize(w, h) else null)
  }

  private const val DOC_WIDTH = 5
  private const val DOC_HEIGHT = 6
  private const val DATA_TYPE_INT = 0

  /** Same ceiling the players apply to the header property table — a bound, not a format rule. */
  private const val MAX_HEADER_PROPERTIES = 1000

  /**
   * Minimal big-endian reader over the header prefix; throws past the end, caught by the caller.
   */
  private class ByteReader(private val bytes: ByteArray, private var offset: Int) {
    fun byte(): Int {
      if (offset >= bytes.size) throw IndexOutOfBoundsException()
      return bytes[offset++].toInt() and 0xFF
    }

    fun short(): Int = (byte() shl 8) or byte()

    fun int(): Int = (short() shl 16) or short()

    fun skip(count: Int) {
      if (count < 0 || offset + count > bytes.size) throw IndexOutOfBoundsException()
      offset += count
    }
  }

  // ---- Lottie ------------------------------------------------------------------------------

  private val LENIENT_JSON = Json { ignoreUnknownKeys = true }

  /** The parsed animation object when [bytes] are a Bodymovin/Lottie document; null otherwise. */
  /**
   * The vendored lottie-web light build has no expression engine, so expression-driven documents
   * would play their static fallbacks. Expressions are a string `x` on an animatable property
   * (numeric `x` values are easing handles).
   */
  private fun lottieUnsupported(bytes: ByteArray): String? {
    val root = parseLottie(bytes) ?: return null
    return if (usesExpressions(root)) {
      "Lottie expressions aren't supported here — bake them into keyframes on export and upload again"
    } else {
      null
    }
  }

  private fun usesExpressions(element: JsonElement): Boolean =
    when (element) {
      is JsonObject ->
        (element["x"] as? JsonPrimitive)?.isString == true || element.values.any(::usesExpressions)
      is JsonArray -> element.any(::usesExpressions)
      else -> false
    }

  private fun parseLottie(bytes: ByteArray): JsonObject? {
    // Cheap pre-filter: a Lottie document is a JSON object, so anything that doesn't open like one
    // never reaches the parser (which would otherwise buffer a large binary upload as text).
    val firstByte = bytes.firstOrNull { !it.isJsonWhitespace() } ?: return null
    if (firstByte.toInt().toChar() != '{') return null
    val root =
      try {
        LENIENT_JSON.parseToJsonElement(bytes.decodeToString()) as? JsonObject
      } catch (e: Exception) {
        null
      } ?: return null
    if (root["layers"] !is JsonArray) return null
    // Every Bodymovin export writes `fr`, `ip` and `op`; requiring them keeps other `layers`-shaped
    // JSON out.
    val required = listOf("fr", "ip", "op").mapNotNull { root.number(it) }
    if (required.size != 3) return null
    return root
  }

  private fun describeLottie(bytes: ByteArray): List<ServeDocFact> {
    val root = parseLottie(bytes) ?: return emptyList()
    val facts = mutableListOf<ServeDocFact>()
    root["nm"]
      ?.stringOrNull()
      ?.takeIf { it.isNotBlank() }
      ?.let { facts += ServeDocFact("Name", it) }
    root["v"]?.stringOrNull()?.let { facts += ServeDocFact("Bodymovin version", it) }
    lottieSize(bytes)?.let { facts += ServeDocFact("Size", "${it.width} × ${it.height}") }
    val frameRate = root.number("fr")
    val inPoint = root.number("ip")
    val outPoint = root.number("op")
    if (frameRate != null && inPoint != null && outPoint != null && frameRate > 0) {
      val frames = outPoint - inPoint
      facts += ServeDocFact("Frames", "${frames.toInt()} @ ${trimNumber(frameRate)} fps")
      facts += ServeDocFact("Duration", "${trimNumber(frames / frameRate)}s")
    }
    (root["layers"] as? JsonArray)?.let { facts += ServeDocFact("Layers", it.size.toString()) }
    return facts
  }

  private fun lottieSize(bytes: ByteArray): ServeDocSize? {
    val root = parseLottie(bytes) ?: return null
    val width = root.number("w")?.toInt() ?: return null
    val height = root.number("h")?.toInt() ?: return null
    return if (width > 0 && height > 0) ServeDocSize(width, height) else null
  }

  private fun JsonObject.number(key: String): Double? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

  private fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)
      ?.takeIf { it.isString }
      ?.jsonPrimitive
      ?.content

  private fun trimNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString()
    else String.format("%.2f", value).trimEnd('0').trimEnd('.')

  private fun Byte.isJsonWhitespace(): Boolean =
    this == ' '.code.toByte() ||
      this == '\n'.code.toByte() ||
      this == '\r'.code.toByte() ||
      this == '\t'.code.toByte()
}
