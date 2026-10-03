package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A Google Fonts family's TrueType file at one weight, fetched once and kept: what the UI builder
 * draws a design's `typeface` in when it names a family the editor bundle does not vendor.
 *
 * The editor and its runtimes are Compose on Skia and need font *bytes*, which the page's own
 * `connect-src` will not let them fetch from `fonts.gstatic.com` — and a sandboxed runtime frame
 * could not send a credential there anyway. So the host fetches, and answers same-origin. Before
 * this a design naming any family outside the vendored nine (an agent picking a face for a theme,
 * typically) was silently drawn in the default face.
 *
 * Bounded on purpose, because the route is ungated like `/rc-fonts`: only a family in the committed
 * fonts.google.com list ([families]) and a weight on the hundreds can be asked for, so the cache
 * holds at most the catalog and no request makes this server fetch an arbitrary URL.
 *
 * The fetch mirrors `deploy/image/prewarm-fonts.sh` (and through it the Android renderer's
 * `GoogleFontInterceptor`): the CSS2 endpoint with a pre-KitKat UA so it answers `truetype`, the
 * exact weight first, the whole axis only when that carried no file, then the bare family (a
 * static family with a single face), and the closest declared weight wins. The cache files share that script's `<slug>-<weight>.ttf` naming.
 */
internal class ServeGoogleFonts(
  /** Where the files are kept, which the UI-builder renderer reads ([warm]). */
  val cacheDirectory: File,
  private val families: List<String>,
  /**
   * The body of a GET; null when the server answered 4xx (Google's "no such face"), and a throw for
   * anything that is not an answer — so an outage is a 502 now, never a cached "missing".
   */
  private val fetch: (url: String, userAgent: String) -> ByteArray?,
) {
  private val byKey: Map<String, String> by lazy { families.associateBy { it.lowercase() } }

  /**
   * Files Google answered no TrueType for, so asking again costs nothing; for the life of the
   * process, since the list behind [families] does not change under it either.
   */
  private val missing = mutableSetOf<String>()

  /** The family as the catalog spells it, for [name] in any case; null when it is not in it. */
  fun canonical(name: String): String? = byKey[name.trim().lowercase()]

  /**
   * The file for [family] at [weight], from the cache or fetched into it; null when the family is
   * not in the catalog, the weight is not one a family can declare, or Google answered no file.
   */
  fun font(family: String, weight: Int): ByteArray? {
    val name = canonical(family) ?: return null
    if (weight !in VALID_WEIGHTS) return null
    val cached = File(cacheDirectory, "${slugify(name)}-$weight.ttf")
    if (cached.isFile && cached.length() > 0) return cached.readBytes()
    synchronized(this) {
      if (cached.isFile && cached.length() > 0) return cached.readBytes()
      if (cached.name in missing) return null
      val url =
        truetypeUrl(cssText(name, "wght@$weight"), weight)
          ?: truetypeUrl(cssText(name, "wght@100..1000"), weight)
          // A static family with one face (Major Mono Display, many display faces) answers both
          // weight queries above with a 400 when the weight asked is not that face's; the bare
          // family query names its face, which is the closest it has.
          ?: truetypeUrl(cssText(name, null), weight)
      if (url == null) {
        missing += cached.name
        return null
      }
      val bytes = fetch(url, TTF_USER_AGENT)?.takeIf { it.isNotEmpty() } ?: return null
      cacheDirectory.mkdirs()
      val temp = File.createTempFile("font", ".tmp", cacheDirectory)
      try {
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
      } finally {
        temp.delete()
      }
      return bytes
    }
  }

  /**
   * Fetch the regular and bold of every family in [typefaces] the catalog lists, so the UI-builder
   * renderer — which reads [cacheDirectory] and fetches nothing — draws a design in its faces.
   *
   * The editor fills the cache as it draws, but a design an agent edited through the API, or a
   * cache that started empty on a new host, has never been drawn there; its thumbnail would be in
   * the platform face. A name outside the catalog, a vendored family, or a fetch that fails is left
   * alone: the render draws that one family in the default face, as the editor would.
   */
  fun warm(typefaces: Collection<String>) {
    typefaces
      .map { it.trim().removePrefix("google:").trim() }
      .filter { canonical(it) != null }
      .toSet()
      .forEach { family ->
        WARMED_WEIGHTS.forEach { weight -> runCatching { font(family, weight) } }
      }
  }

  /**
   * The stylesheet for one query. A 4xx is "no file", the same as an empty sheet: purely variable
   * families answer a single-weight query that way.
   */
  private fun cssText(family: String, axis: String?): String =
    fetch(cssUrl(family, axis), TTF_USER_AGENT)?.decodeToString().orEmpty()

  companion object {
    const val ROUTE: String = "/api/fonts/google"

    /** The only UA the CSS2 endpoint answers with `format('truetype')` rather than WOFF2. */
    const val TTF_USER_AGENT: String =
      "Mozilla/5.0 (Linux; U; Android 2.3.3; en-us) AppleWebKit/533.1 (KHTML, like Gecko)"

    /** The largest font file this will read, well above any single-weight Google Fonts TTF. */
    const val MAX_FONT_BYTES: Int = 16 * 1024 * 1024

    private val VALID_WEIGHTS = (100..900 step 100).toSet()

    /**
     * The weights the renderer reads for a family: compose-ui-builder's `ProductionFontFamilies`.
     */
    private val WARMED_WEIGHTS = listOf(400, 700)

    /**
     * The cache under the UI builder's app directory [dir], fetching through [client] — the one
     * place both the font route and the renderer get it from, so they share files.
     */
    fun overHttp(dir: File, client: okhttp3.OkHttpClient): ServeGoogleFonts =
      ServeGoogleFonts(
        cacheDirectory = File(dir, "google-fonts"),
        families = ServeWeb.googleFontFamilies,
        fetch = { url, userAgent ->
          client
            .newCall(okhttp3.Request.Builder().url(url).header("User-Agent", userAgent).build())
            .execute()
            .use { response ->
              if (response.code in 400..499) return@use null
              check(response.isSuccessful) { "$url answered ${response.code}" }
              val body = checkNotNull(response.body) { "$url answered no body" }
              check(body.contentLength() <= MAX_FONT_BYTES) {
                "$url declared ${body.contentLength()} bytes; refusing to read it"
              }
              val bytes =
                ee.schimke.composeai.cli.serve.icons.MaterialSymbolsSource.readAtMost(
                  body.byteStream(),
                  MAX_FONT_BYTES,
                )
              check(bytes.size <= MAX_FONT_BYTES) { "$url is too large" }
              bytes
            }
        },
      )

    /** `GoogleFontKey.slugify`: lowercase, every non-alphanumeric run one `-`, none at the ends. */
    fun slugify(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** The CSS2 stylesheet url for [family], on [axis] (`wght@700`), or its default face for null. */
    fun cssUrl(family: String, axis: String?): String =
      "https://fonts.googleapis.com/css2?family=${family.replace(" ", "%20")}" +
        (axis?.let { ":$it" } ?: "") +
        "&display=swap"

    private val FACE = Regex("@font-face\\s*\\{([^}]*)}")
    private val WEIGHT = Regex("font-weight:\\s*(\\d+)")
    private val TRUETYPE_URL =
      Regex("url\\((https://fonts\\.gstatic\\.com/[^)\\s]+)\\)\\s*format\\(['\"]truetype['\"]\\)")

    /**
     * The `truetype` url in [css] whose block declares the weight closest to [weight]; a block with
     * no weight counts as 400. Only `fonts.gstatic.com` urls, so the stylesheet cannot point this
     * server anywhere else.
     */
    fun truetypeUrl(css: String, weight: Int): String? =
      FACE.findAll(css)
        .mapNotNull { face ->
          val block = face.groupValues[1]
          val url = TRUETYPE_URL.find(block)?.groupValues?.get(1) ?: return@mapNotNull null
          val declared = WEIGHT.find(block)?.groupValues?.get(1)?.toIntOrNull() ?: 400
          url to kotlin.math.abs(declared - weight)
        }
        .minByOrNull { it.second }
        ?.first
  }
}
