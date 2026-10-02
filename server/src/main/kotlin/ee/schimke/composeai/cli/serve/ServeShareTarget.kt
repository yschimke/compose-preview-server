package ee.schimke.composeai.cli.serve

import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The installed app's **share target** — what the web app manifest names, so that "Share → Compose
 * Preview" in a phone's share sheet lands somewhere useful.
 *
 * Two shapes arrive, and each goes where this server already has a home for it rather than to a new
 * surface invented for the purpose:
 * * **An image** — almost always a screenshot of something that looked wrong — becomes a capture on
 *   [ServeBugReport.PATH], exactly as if the reporter had taken it with the page's own capture
 *   tool. The report page already hosts or copies captures, so nothing downstream changes.
 * * **A link or some text.** A link to one of this server's own pages simply opens it (sharing a
 *   preview's URL from a chat into the installed app is "open this"). Anything else prefills the
 *   bug report's "What went wrong" section, which is the only free-text box this server has.
 *
 * The share POST cannot write into the browser's `sessionStorage`, which is where the capture pile
 * lives, so the image is parked here under an unguessable id for a few minutes and the report page
 * pulls it into the pile on load. The parking lot is deliberately small — a handful of entries, a
 * size cap, a short expiry — because it is reachable by anyone who may open `/report-bug` at all,
 * and it must not become an anonymous image host.
 */
internal object ServeShareTarget {

  /** Where the manifest's `share_target.action` points. Under the reserved `report-bug` segment. */
  const val ACTION_PATH: String = "${ServeBugReport.PATH}/share"

  /** `GET <this>/<id>`: the parked image, for the report page to import. */
  const val SHARED_PATH: String = "${ServeBugReport.PATH}/shared"

  /** Query parameter the report page reads the parked share's id from. */
  const val SHARED_PARAM: String = "shared"

  const val TITLE_FIELD: String = "title"
  const val TEXT_FIELD: String = "text"
  const val URL_FIELD: String = "url"
  const val FILE_FIELD: String = "image"

  /** What the manifest says the target accepts. The route re-checks by content, not by label. */
  val ACCEPTED_IMAGE_TYPES: List<String> = listOf("image/png", "image/jpeg", "image/webp")

  /**
   * The largest share body read at all. A phone screenshot is one to four megabytes as PNG; ten
   * leaves room for a large tablet capture without letting a POST hold an unbounded buffer.
   */
  const val MAX_BODY_BYTES: Long = 10L * 1024 * 1024

  /** Shared text kept for the report, after trimming: a paragraph, not a document. */
  const val MAX_TEXT_CHARS: Int = 2_000

  /** How many parked shares are held at once; the oldest is dropped past this. */
  const val MAX_ENTRIES: Int = 8

  /** How long a parked share survives — long enough to reload the report page, no longer. */
  const val TTL_MILLIS: Long = 10L * 60 * 1000

  /** One parked share. [image] is null for a text-only share. */
  class Shared(
    val image: ByteArray?,
    val imageType: String?,
    val text: String?,
    val createdAt: Long,
  )

  /** The decoded form fields of a share POST. */
  data class Fields(
    val title: String? = null,
    val text: String? = null,
    val url: String? = null,
    val image: ByteArray? = null,
    val imageType: String? = null,
  )

  /** Where to send the browser after a share. */
  sealed interface Outcome {
    /** A same-origin page to open, as a path plus query. */
    data class Open(val path: String) : Outcome

    /** Park [shared] and open the bug report with it. */
    data class Report(val shared: Shared) : Outcome

    /** Nothing usable arrived. */
    data object Empty : Outcome
  }

  /**
   * Decide what a share means. [sameOrigin] says whether an absolute URL names this server — the
   * caller knows its own external origin and site hosts; this object does not.
   */
  fun outcome(fields: Fields, now: Long, sameOrigin: (URI) -> Boolean): Outcome {
    val imageType = fields.image?.let { sniffImageType(it) }
    val text =
      listOfNotNull(fields.title, fields.text, fields.url)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n")
        .take(MAX_TEXT_CHARS)
        .takeIf { it.isNotEmpty() }
    if (fields.image != null && imageType != null) {
      return Outcome.Report(Shared(fields.image, imageType, text, now))
    }
    // A link to this server opens it. Android's share sheet often puts the URL in `text` rather
    // than `url`, so the first http(s) URL anywhere in the share counts.
    val candidate =
      fields.url?.trim()?.takeIf { it.isNotEmpty() }
        ?: URL_IN_TEXT.find(fields.text.orEmpty())?.value
    val local = candidate?.let { localPath(it, sameOrigin) }
    if (local != null) return Outcome.Open(local)
    return if (text != null) Outcome.Report(Shared(null, null, text, now)) else Outcome.Empty
  }

  /**
   * [raw] as a path on this server, or null. Only an absolute http(s) URL whose origin [sameOrigin]
   * accepts, reduced to path and query — never a scheme-relative `//host` path, which a redirect
   * would carry off-site.
   */
  fun localPath(raw: String, sameOrigin: (URI) -> Boolean): String? {
    val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host == null) return null
    if (!sameOrigin(uri)) return null
    val path = uri.rawPath?.takeIf { it.startsWith("/") } ?: "/"
    if (path.startsWith("//")) return null
    // The share route itself would loop.
    if (path.startsWith(ACTION_PATH)) return null
    return path + (uri.rawQuery?.let { "?$it" } ?: "")
  }

  /** PNG, JPEG or WebP by magic bytes; null for anything else, whatever its part claimed. */
  fun sniffImageType(bytes: ByteArray): String? {
    fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xff else -1
    return when {
      at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4e && at(3) == 0x47 -> "image/png"
      at(0) == 0xff && at(1) == 0xd8 && at(2) == 0xff -> "image/jpeg"
      at(0) == 'R'.code &&
        at(1) == 'I'.code &&
        at(2) == 'F'.code &&
        at(3) == 'F'.code &&
        at(8) == 'W'.code &&
        at(9) == 'E'.code &&
        at(10) == 'B'.code &&
        at(11) == 'P'.code -> "image/webp"
      else -> null
    }
  }

  private val URL_IN_TEXT = Regex("https?://[^\\s<>\"']+")

  /**
   * Parse a `multipart/form-data` body into [Fields]. Small and strict on purpose: the body is
   * already capped at [MAX_BODY_BYTES] by the caller, only the four named fields are read, and the
   * first file part wins.
   */
  fun parseMultipart(body: ByteArray, contentType: String): Fields? {
    val boundary =
      contentType
        .split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("boundary=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() } ?: return null
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    var fields = Fields()
    var at = indexOf(body, delimiter, 0)
    if (at < 0) return null
    while (true) {
      var start = at + delimiter.size
      // `--` after the delimiter closes the body.
      if (
        start + 1 < body.size &&
          body[start] == '-'.code.toByte() &&
          body[start + 1] == '-'.code.toByte()
      ) {
        break
      }
      start = skipLineBreak(body, start)
      val next = indexOf(body, delimiter, start)
      if (next < 0) break
      // The part ends at the CRLF before the next delimiter.
      var end = next
      if (end >= 2 && body[end - 2] == '\r'.code.toByte() && body[end - 1] == '\n'.code.toByte()) {
        end -= 2
      }
      val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), start)
      if (headerEnd in start until end) {
        val headers = String(body, start, headerEnd - start, Charsets.UTF_8)
        val content = body.copyOfRange(headerEnd + 4, end)
        fields = withPart(fields, headers, content)
      }
      at = next
    }
    return fields
  }

  private fun withPart(fields: Fields, headers: String, content: ByteArray): Fields {
    val disposition =
      headers.lines().firstOrNull { it.startsWith("content-disposition:", ignoreCase = true) }
        ?: return fields
    val name = PART_NAME.find(disposition)?.groupValues?.get(1) ?: return fields
    val isFile = disposition.contains("filename=", ignoreCase = true)
    fun text() = String(content, Charsets.UTF_8).take(MAX_TEXT_CHARS)
    return when {
      name == FILE_FIELD && isFile && fields.image == null && content.isNotEmpty() ->
        fields.copy(image = content, imageType = sniffImageType(content))
      name == TITLE_FIELD && !isFile -> fields.copy(title = text())
      name == TEXT_FIELD && !isFile -> fields.copy(text = text())
      name == URL_FIELD && !isFile -> fields.copy(url = text())
      else -> fields
    }
  }

  private val PART_NAME = Regex("""(?i)\bname="([^"]*)"""")

  private fun skipLineBreak(body: ByteArray, from: Int): Int {
    var i = from
    if (i < body.size && body[i] == '\r'.code.toByte()) i++
    if (i < body.size && body[i] == '\n'.code.toByte()) i++
    return i
  }

  private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
    if (needle.isEmpty()) return from
    outer@ for (i in from..haystack.size - needle.size) {
      for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
      return i
    }
    return -1
  }

  /** The parking lot. One per server process; bounded by [MAX_ENTRIES] and [TTL_MILLIS]. */
  class Store(private val clock: () -> Long = System::currentTimeMillis) {
    private val entries = ConcurrentHashMap<String, Shared>()
    private val random = SecureRandom()

    /** Park [shared] and return its id: 128 random bits, hex. */
    fun put(shared: Shared): String {
      prune()
      synchronized(entries) {
        while (entries.size >= MAX_ENTRIES) {
          val oldest = entries.entries.minByOrNull { it.value.createdAt }?.key ?: break
          entries.remove(oldest)
        }
        val bytes = ByteArray(16).also(random::nextBytes)
        val id = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        entries[id] = shared
        return id
      }
    }

    /** The share parked under [id], while it lasts. */
    fun get(id: String): Shared? {
      if (!ID.matches(id)) return null
      val shared = entries[id] ?: return null
      if (clock() - shared.createdAt > TTL_MILLIS) {
        entries.remove(id)
        return null
      }
      return shared
    }

    private fun prune() {
      val now = clock()
      entries.entries.removeIf { now - it.value.createdAt > TTL_MILLIS }
    }

    private companion object {
      val ID = Regex("[0-9a-f]{32}")
    }
  }
}
