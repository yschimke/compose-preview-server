package ee.schimke.composeai.mcp

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * A catalogued preview as the Previews tray (#1238) and the composer mention search (#1239) see it.
 * [DaemonMcpServer] snapshots its catalog into these, so neither feature reads the catalog's
 * internals.
 */
data class CatalogPreview(
  /** The `compose-preview://` URI, without overrides. */
  val uri: String,
  val fqn: String,
  /** Bare `@Composable` function name, when discovery sent one. */
  val functionName: String?,
  val displayName: String?,
  val modulePath: String,
  /** Canonical source path, as `resources/list` reports it in `_meta.sourceFile`. */
  val sourceFile: String?,
  val sourceLine: Int?,
) {
  /** The function name, or the last segment of the FQN. */
  val simpleName: String
    get() = functionName?.takeIf { it.isNotBlank() } ?: fqn.substringAfterLast('.')

  val fileName: String?
    get() = sourceFile?.let { File(it).name }
}

/**
 * What this MCP server has seen happen in the session: the source files that changed, the previews
 * that rendered or that a client subscribed to, and the previews the person pinned to the tray.
 * Ordered by a monotonic sequence rather than the clock, so two events in one millisecond still
 * have an order.
 *
 * One instance per [DaemonMcpServer]. A stdio server is one host session; the streamable HTTP
 * surface shares the process, and with it these lists, which is what a person with the tray open
 * beside an agent working on the same checkout wants.
 */
class PreviewActivity(private val clock: () -> Long = System::currentTimeMillis) {

  /** When something happened: [seq] orders events, [atMs] is for display. */
  data class Mark(val seq: Long, val atMs: Long)

  data class Rendered(val mark: Mark, val pngPath: String)

  private val sequence = AtomicLong()
  private val changedSources = ConcurrentHashMap<String, Mark>()
  private val rendered = ConcurrentHashMap<String, Rendered>()
  private val watched = ConcurrentHashMap<String, Mark>()
  private val pins = ConcurrentHashMap<String, Mark>()

  private fun next(): Mark = Mark(sequence.incrementAndGet(), clock())

  /** A source file changed: `notify_file_changed`, or the freshness check before a render. */
  fun sourceChanged(path: String) {
    if (path.isBlank()) return
    changedSources[canonical(path)] = next()
  }

  /** A render of [uri] finished, writing [pngPath]. Only the path is kept, never the bytes. */
  fun rendered(uri: String, pngPath: String) {
    val key = previewKey(uri) ?: return
    rendered[key] = Rendered(next(), pngPath)
  }

  /** A client subscribed to [uri]. */
  fun watched(uri: String) {
    val key = previewKey(uri) ?: return
    watched[key] = next()
  }

  /** Pins [uri] to the tray; false when it is not a `compose-preview://` URI. */
  fun pin(uri: String): Boolean {
    if (PreviewUri.parseOrNull(uri) == null) return false
    pins[uri] = next()
    return true
  }

  fun unpin(uri: String): Boolean = pins.remove(uri) != null

  /** Changed source paths, most recent first. */
  fun changedSources(): List<Pair<String, Mark>> =
    changedSources.entries.map { it.key to it.value }.sortedByDescending { it.second.seq }

  /** Pinned URIs, most recently pinned first. */
  fun pins(): List<Pair<String, Mark>> =
    pins.entries.map { it.key to it.value }.sortedByDescending { it.second.seq }

  /** The sequence of the last render of or subscription to [uri], or null when neither happened. */
  fun recency(uri: String): Long? {
    val key = previewKey(uri) ?: return null
    return listOfNotNull(rendered[key]?.mark?.seq, watched[key]?.seq).maxOrNull()
  }

  /** Where the last render of [uri] was written, or null when it has not rendered here. */
  fun lastRenderPng(uri: String): String? = previewKey(uri)?.let { rendered[it]?.pngPath }

  /**
   * The sequence of the last render of [uri], or null. The tray viewer compares it with the one it
   * saw after its own render, to re-open a preview someone else re-rendered without looping on its
   * own renders.
   */
  fun renderSeq(uri: String): Long? = previewKey(uri)?.let { rendered[it]?.mark?.seq }

  private fun canonical(path: String): String = runCatching {
    File(path).canonicalPath
  }
    .getOrDefault(path)

  /** [uri] without its overrides, so a render with overrides still counts for the preview. */
  private fun previewKey(uri: String): String? =
    PreviewUri.parseOrNull(uri)?.copy(overridesJson = null)?.toUri()
}

/**
 * Small PNG thumbnails of cached renders. Reads the file the last render wrote and scales it down;
 * it never renders. Memoised by path, size and mtime, so a picker that searches on every keystroke
 * decodes each render once.
 */
class RenderThumbnails(private val maxEntries: Int = 256) {
  private data class Key(val path: String, val length: Long, val modifiedMs: Long, val edge: Int)

  private val cache = ConcurrentHashMap<Key, String>()

  /** Base64 PNG no larger than [maxEdge] on its long edge, or null when [pngPath] is unreadable. */
  fun base64(pngPath: String?, maxEdge: Int): String? {
    if (pngPath == null) return null
    val file = File(pngPath)
    if (!file.isFile) return null
    val key = Key(file.absolutePath, file.length(), file.lastModified(), maxEdge)
    cache[key]?.let {
      return it
    }
    val encoded = runCatching { scale(file, maxEdge) }.getOrNull() ?: return null
    if (cache.size >= maxEntries) cache.clear()
    cache[key] = encoded
    return encoded
  }

  private fun scale(file: File, maxEdge: Int): String? {
    val source = ImageIO.read(file) ?: return null
    val longEdge = maxOf(source.width, source.height)
    val bytes =
      if (longEdge <= maxEdge) {
        file.readBytes()
      } else {
        val ratio = maxEdge.toDouble() / longEdge
        val width = maxOf(1, (source.width * ratio).toInt())
        val height = maxOf(1, (source.height * ratio).toInt())
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
          graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
          )
          graphics.setRenderingHint(
            RenderingHints.KEY_RENDERING,
            RenderingHints.VALUE_RENDER_QUALITY,
          )
          graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
          graphics.dispose()
        }
        ByteArrayOutputStream().also { ImageIO.write(scaled, "png", it) }.toByteArray()
      }
    return Base64.getEncoder().encodeToString(bytes)
  }
}
