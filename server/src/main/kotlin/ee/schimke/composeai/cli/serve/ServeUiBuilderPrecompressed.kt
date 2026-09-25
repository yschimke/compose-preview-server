package ee.schimke.composeai.cli.serve

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/**
 * Gzipped copies of the UI-builder bundle's files, made once each, off the request path.
 *
 * ## Why the bundle needs this
 *
 * The editor is a Wasm application: a browser opening a design first downloads `uiBuilder.wasm`
 * (tens of megabytes) and `skiko.wasm`, and cannot draw anything until both have arrived. Wasm
 * compresses well — 4 to 5 times with gzip — but `application/wasm` stays off the server's
 * `Compression` allowlist on purpose, because compressing a file that size on every request would
 * take CPU from the render daemons on the same box. The effect was the worst of both: a direct
 * client got the full uncompressed bytes, and behind the deploy's Caddy (`encode zstd gzip`) the
 * proxy recompressed the whole file on every cache miss, which is exactly the CPU cost the
 * allowlist was avoiding.
 *
 * Compressing each file once and serving the result costs one core for a few seconds per bundle,
 * per process. Caddy passes a response that already has a `Content-Encoding` through untouched.
 *
 * ## The rules
 *
 * - **Never on the request path.** [ready] only returns a copy that is already finished. Until then
 *   the caller serves the original, which is what every request got before this existed. One
 *   low-priority thread does the work, so the builder can never take more than one core from the
 *   render lanes.
 * - **Keyed by the file, not by a name.** Path, length and modification time together name the
 *   copy, so a file changed in place can never be answered with the gzip of its old bytes.
 * - **Outside the bundle.** The bundle's version prefix is a digest of every file under it, so a
 *   cache there would change the version the first time it filled and again after each restart —
 *   retiring every viewer's immutable copy for nothing.
 * - **Only where it pays.** A copy that is not at least 10% smaller is not kept; the original is
 *   served as it is.
 */
internal class UiBuilderPrecompressedAssets(
  private val cacheDir: () -> File = { defaultCacheDir }
) {
  private val copies = ConcurrentHashMap<String, Future<File?>>()

  private val worker: ExecutorService by lazy {
    Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "ui-builder-gzip").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
      }
    }
  }

  /** Whether [file] is a kind worth compressing: text, Wasm and fonts, above a minimum size. */
  fun compressible(file: File): Boolean =
    file.extension.lowercase() in COMPRESSIBLE_EXTENSIONS && file.length() >= MINIMUM_BYTES

  /**
   * The finished gzip copy of [file], or null — and in that case the copy is started, so a later
   * request finds it. Never blocks.
   */
  fun ready(file: File): File? {
    if (!compressible(file)) return null
    val copy =
      copies.computeIfAbsent(key(file)) { key -> worker.submit<File?> { compress(file, key) } }
    if (!copy.isDone) return null
    return runCatching { copy.get() }.getOrNull()?.takeIf { it.isFile }
  }

  private val warmed = ConcurrentHashMap.newKeySet<String>()

  /**
   * Queue the compressible files at the top of [bundle], largest first, so the Wasm is the first
   * copy ready. Once per bundle, and on the worker rather than the caller, so a page load never
   * waits on a directory walk.
   *
   * The top level only: that is where the Wasm, its modules and the catalog JSON live — everything
   * a cold open is waiting for. Deeper files still get a copy on their first request, and the
   * bundle directory also holds the icon cache, which is served by its own route and is not this
   * one's to compress.
   */
  fun warm(bundle: File) {
    if (!warmed.add(bundle.canonicalPath)) return
    worker.execute {
      bundle
        .listFiles()
        .orEmpty()
        .filter { it.isFile && compressible(it) }
        .sortedByDescending { it.length() }
        .forEach { ready(it) }
    }
  }

  private fun compress(source: File, key: String): File? {
    val dir = cacheDir().apply { mkdirs() }
    val target = File(dir, "$key.gz")
    if (target.isFile) return target
    val partial = File.createTempFile(key, ".partial", dir)
    try {
      source.inputStream().use { input ->
        BestGzipOutputStream(partial.outputStream().buffered()).use { input.copyTo(it) }
      }
      if (partial.length() > source.length() * 9 / 10) return null
      Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
      return target
    } catch (failure: Exception) {
      println("compose-preview-server: could not precompress ${source.name}: $failure")
      return null
    } finally {
      partial.delete()
    }
  }

  private fun key(file: File): String {
    val identity = "${file.canonicalPath}\u0000${file.length()}\u0000${file.lastModified()}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }

  /** Level 9: the cost is paid once per file, and every download after it gets the saving. */
  private class BestGzipOutputStream(out: java.io.OutputStream) : GZIPOutputStream(out, 64 * 1024) {
    init {
      def.setLevel(Deflater.BEST_COMPRESSION)
    }
  }

  internal companion object {
    val COMPRESSIBLE_EXTENSIONS = setOf("wasm", "mjs", "js", "json", "css", "svg", "ttf", "txt")

    const val MINIMUM_BYTES = 1024L

    private val defaultCacheDir: File by lazy {
      Files.createTempDirectory("compose-ui-builder-gzip").toFile()
    }

    /** Whether the request's `Accept-Encoding` takes gzip at a non-zero quality. */
    fun acceptsGzip(acceptEncoding: String?): Boolean {
      if (acceptEncoding == null) return false
      var gzip: Double? = null
      var wildcard: Double? = null
      for (entry in acceptEncoding.split(',')) {
        val parts = entry.split(';').map { it.trim() }
        val quality =
          parts
            .drop(1)
            .firstOrNull { it.startsWith("q=", ignoreCase = true) }
            ?.substring(2)
            ?.toDoubleOrNull() ?: 1.0
        when (parts.first().lowercase()) {
          "gzip",
          "x-gzip" -> gzip = quality
          "*" -> wildcard = quality
        }
      }
      return (gzip ?: wildcard ?: 0.0) > 0.0
    }
  }
}

/**
 * [original]'s bytes, declared as already gzip-encoded.
 *
 * On the body rather than on the call's headers, because that is where the server's `Compression`
 * plugin looks for an existing encoding: an encoding set only on the call is invisible to it, and
 * the gzip copy would be gzipped a second time. The same shape Ktor's own pre-compressed static
 * content uses.
 */
internal class GzipEncodedContent(private val original: OutgoingContent.ReadChannelContent) :
  OutgoingContent.ReadChannelContent() {
  override val contentType = original.contentType
  override val contentLength = original.contentLength
  override val headers: Headers = headersOf(HttpHeaders.ContentEncoding, "gzip")

  override fun readFrom(): ByteReadChannel = original.readFrom()

  override fun readFrom(range: LongRange): ByteReadChannel = original.readFrom(range)
}
