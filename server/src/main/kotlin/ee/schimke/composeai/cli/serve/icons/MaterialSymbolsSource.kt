package ee.schimke.composeai.cli.serve.icons

import java.io.File
import java.security.MessageDigest

/** One Material Symbols face, pinned by the content of its variable font. */
internal data class MaterialSymbolsStyle(
  val id: String,
  val fontUrl: String,
  val fontDigest: String,
  val fontBytes: Int,
)

/**
 * The pinned fonts, fetched once into a cache and verified by digest.
 *
 * This is the whole of "nothing large is bundled or pre-generated": a face is 10.68 MB of source
 * data that answers every axis value, and it lives in neither git nor the distribution. It arrives
 * the way the Robolectric `android-all` jars already arrive in these repositories — downloaded on
 * first use, keyed by content, and never fetched again.
 *
 * **The digest is the pin, not the URL.** Upstream publishes these on a moving branch, so the
 * guarantee has to come from the bytes: a file whose SHA-256 does not match is discarded and the
 * fetch fails loudly. That turns an upstream redraw into an obvious, one-line failure someone
 * updates deliberately, rather than a silent change of every icon in every design — which is the
 * property
 * [`UI_BUILDER_MATERIAL_SYMBOLS.md`](../../../../../../../../../docs/design/UI_BUILDER_MATERIAL_SYMBOLS.md)
 * calls determinism, and the reason the live Google endpoints are not the runtime source.
 *
 * [fetch] is injected so that tests never reach the network and so an offline host can be handed a
 * warm cache instead. A host with neither is refused with a message naming the file it wanted.
 */
internal class MaterialSymbolsSource(
  private val cacheDirectory: File,
  private val styles: List<MaterialSymbolsStyle> = STYLES,
  private val codePointsUrl: String = CODE_POINTS_URL,
  private val codePointsDigest: String = CODE_POINTS_DIGEST,
  private val fetch: (String) -> ByteArray = { url -> error("no fetcher configured for $url") },
) {

  private val catalogs = HashMap<String, MaterialSymbolsCatalog>()

  val styleIds: List<String>
    get() = styles.map { it.id }

  /**
   * A short token identifying the pinned data a style's answers come from.
   *
   * It travels in the request URL so that an `immutable` response cannot outlive the pin that
   * produced it: change a digest in a later release and every URL changes with it, rather than
   * shared caches serving last year's outlines for the next twelve months.
   */
  fun pin(styleId: String): String? {
    val style = styles.firstOrNull { it.id == styleId } ?: return null
    return style.fontDigest.take(8) + codePointsDigest.take(8)
  }

  /**
   * The icon names, which need the 79 KB code point list and none of the 10 MB font.
   *
   * Split out because the picker asks for names the moment it opens: resolving them through
   * [catalog] would download and parse a whole face to hand back a list that is identical for all
   * three of them, which is the transfer the names route exists to avoid.
   */
  @Synchronized
  fun names(styleId: String): List<String>? {
    if (styles.none { it.id == styleId }) return null
    return MaterialSymbolsCatalog.readCodePoints(codePointsFile().decodeToString()).keys.toList()
  }

  private fun codePointsFile(): ByteArray =
    file("symbols", "codepoints", codePointsUrl, codePointsDigest)

  /**
   * The catalog for [styleId], reading the cache or filling it, or null for an unknown style.
   *
   * Faces are held once loaded: the parse is cheap but the 10.68 MB array is not, and a host serves
   * the same three faces for its whole life.
   */
  @Synchronized
  fun catalog(styleId: String): MaterialSymbolsCatalog? {
    catalogs[styleId]?.let {
      return it
    }
    val style = styles.firstOrNull { it.id == styleId } ?: return null
    val font = file(style.id, "ttf", style.fontUrl, style.fontDigest)
    val codePoints = codePointsFile()
    return MaterialSymbolsCatalog.read(font, codePoints.decodeToString()).also {
      catalogs[styleId] = it
    }
  }

  private fun file(styleId: String, extension: String, url: String, digest: String): ByteArray {
    val cached = File(cacheDirectory, "$styleId.$extension")
    if (cached.isFile) {
      val bytes = cached.readBytes()
      // A cache entry is re-verified rather than trusted: the bytes may have been truncated by a
      // host that died mid-write before this used a temporary file, or edited by hand.
      if (sha256(bytes) == digest) return bytes
      cached.delete()
    }
    val bytes = fetch(url)
    val actual = sha256(bytes)
    check(actual == digest) {
      "$styleId.$extension from $url has SHA-256 $actual, expected $digest; refusing to use it"
    }
    cacheDirectory.mkdirs()
    // A temporary name per writer, not per file: two hosts sharing a cold directory would otherwise
    // race on one `.part`, and the one that lost could see its own source vanish under it. Whoever
    // arrives second finds the destination already correct — the bytes are content-addressed, so
    // losing the race is not a failure — and drops its copy.
    val partial =
      File(
        cacheDirectory,
        "$styleId.$extension.${ProcessHandle.current().pid()}-${Thread.currentThread().id}.part",
      )
    try {
      partial.writeBytes(bytes)
      if (!partial.renameTo(cached) && !(cached.isFile && sha256(cached.readBytes()) == digest)) {
        partial.copyTo(cached, overwrite = true)
      }
    } finally {
      partial.delete()
    }
    return bytes
  }

  internal companion object {
    private const val FONT_BASE =
      "https://raw.githubusercontent.com/google/material-design-icons/master/variablefont/"

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * The three faces, pinned by content.
     *
     * Sizes are carried beside the digests because they are the cheap half of the same check: a
     * proxy serving an error page or a truncated transfer is recognisable before hashing 10 MB, and
     * the number is what a reader needs to judge whether a host wants to pre-warm its cache.
     */
    val STYLES =
      listOf(
        MaterialSymbolsStyle(
          id = "outlined",
          fontUrl = FONT_BASE + "MaterialSymbolsOutlined%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf",
          fontDigest = "bf4c94b08c06c6b17e9d1a2d54a68c3f2c3435454761d487768df137a81f851d",
          fontBytes = 10_678_200,
        ),
        MaterialSymbolsStyle(
          id = "rounded",
          fontUrl = FONT_BASE + "MaterialSymbolsRounded%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf",
          fontDigest = "f1472f172c0fc4a922be22972e4752ccc54fe795ed82564ab6f6b097782f2dbc",
          fontBytes = 15_141_224,
        ),
        MaterialSymbolsStyle(
          id = "sharp",
          fontUrl = FONT_BASE + "MaterialSymbolsSharp%5BFILL%2CGRAD%2Copsz%2Cwght%5D.ttf",
          fontDigest = "53f7747272a09f35171452c5cab6f147f32a7e6df30d684b2a5010ad8810eb7c",
          fontBytes = 8_868_020,
        ),
      )

    /**
     * One code point list for all three faces, because upstream publishes three identical copies.
     *
     * Each face ships its own `.codepoints` beside its font and all three hash to the same 79,029
     * bytes — the styles are the same icon set drawn three ways, so a name means the same glyph in
     * each. Fetching one file rather than three is not a shortcut: a second copy that could drift
     * from the first is exactly the kind of thing this design keeps removing.
     */
    const val CODE_POINTS_URL =
      FONT_BASE + "MaterialSymbolsOutlined%5BFILL%2CGRAD%2Copsz%2Cwght%5D.codepoints"

    const val CODE_POINTS_DIGEST =
      "c18564f64d7d92dd3a6895a2c59ea69adfb56d6f553bcbbc88811c328159d715"
  }
}
