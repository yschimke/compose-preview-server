package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The Noto slices Compose Multiplatform's web text fallback downloads, fetched once and kept: what
 * the UI builder draws a glyph in when no loaded font has it (`∞` in Noto Sans Math, `☀` in Noto
 * Color Emoji, CJK in Noto Sans SC…).
 *
 * Compose's `NotoFontDownloader` fetches `https://fonts.gstatic.com/s/<path>` itself, and the
 * page's `connect-src` refuses it, so every such glyph was a CSP error and a tofu box. The editor
 * and runtime bundles redirect that fetch here instead (compose-ui-builder's
 * `routeFallbackFontsThroughHost`), as they do the families behind [ServeGoogleFonts].
 *
 * Bounded like that route: only a path in the committed list of slices Compose knows ([slices],
 * `noto-fallback-slices.txt`, read from the Compose release this repository pins), so the cache
 * holds at most that list, a caller cannot make this server send gstatic a request for a path of
 * its own invention, and the remembered misses are bounded by the same list. Each file is held to
 * [ServeGoogleFonts.MAX_FONT_BYTES]. A slice a newer Compose asks for and the list lacks is a 404,
 * and its glyph stays undrawn until the list is regenerated, as it was before the route existed.
 */
internal class ServeNotoFallbackFonts(
  /** Where the files are kept, mirroring their gstatic paths. */
  val cacheDirectory: File,
  /** The paths under [GSTATIC_BASE] this will fetch (`notosansmath/v18/….woff2`). */
  private val slices: Set<String>,
  /**
   * The body of a GET; null when the server answered 4xx, and a throw for anything that is not an
   * answer — so an outage is a 502 now, never a cached "missing".
   */
  private val fetch: (url: String) -> ByteArray?,
) {
  /** Listed paths Google answered no file for, so asking again costs nothing. */
  private val missing = mutableSetOf<String>()

  /**
   * The slice at [path] (`notosansmath/v18/….woff2`), cached or fetched; null when there is none.
   */
  fun font(path: String): ByteArray? {
    if (!knows(path)) return null
    val cached = File(cacheDirectory, path)
    if (cached.isFile && cached.length() > 0) return cached.readBytes()
    synchronized(this) {
      if (cached.isFile && cached.length() > 0) return cached.readBytes()
      if (path in missing) return null
      val bytes = fetch("$GSTATIC_BASE$path")?.takeIf { it.isNotEmpty() }
      if (bytes == null) {
        missing += path
        return null
      }
      cached.parentFile.mkdirs()
      val temp = File.createTempFile("font", ".tmp", cached.parentFile)
      try {
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
      } finally {
        temp.delete()
      }
      return bytes
    }
  }

  /** Whether [path] is a slice this will serve. */
  fun knows(path: String): Boolean = path in slices

  companion object {
    const val ROUTE: String = "/api/fonts/noto"

    /** Compose's `FONT_FALLBACK_BASE_URL`. */
    const val GSTATIC_BASE: String = "https://fonts.gstatic.com/s/"

    /** Compose's slice list, `noto-fallback-slices.txt`; its header says how to regenerate it. */
    val composeSlices: Set<String> by lazy {
      ServeNotoFallbackFonts::class
        .java
        .classLoader
        .getResourceAsStream("ee/schimke/composeai/cli/serve/noto-fallback-slices.txt")
        ?.bufferedReader()
        ?.useLines { lines ->
          lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        }
        .orEmpty()
    }

    /** The cache under the UI builder's app directory [dir], fetching through [client]. */
    fun overHttp(dir: File, client: okhttp3.OkHttpClient): ServeNotoFallbackFonts =
      ServeNotoFallbackFonts(File(dir, "noto-fallback-fonts"), composeSlices) { url ->
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
          if (response.code in 400..499) return@use null
          check(response.isSuccessful) { "$url answered ${response.code}" }
          val body = checkNotNull(response.body) { "$url answered no body" }
          check(body.contentLength() <= ServeGoogleFonts.MAX_FONT_BYTES) {
            "$url declared ${body.contentLength()} bytes; refusing to read it"
          }
          val bytes =
            ee.schimke.composeai.cli.serve.icons.MaterialSymbolsSource.readAtMost(
              body.byteStream(),
              ServeGoogleFonts.MAX_FONT_BYTES,
            )
          check(bytes.size <= ServeGoogleFonts.MAX_FONT_BYTES) { "$url is too large" }
          bytes
        }
      }
  }
}
