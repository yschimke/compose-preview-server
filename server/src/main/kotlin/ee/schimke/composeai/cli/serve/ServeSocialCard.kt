package ee.schimke.composeai.cli.serve

import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.security.MessageDigest
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The link-unfurl card: a 1200×630 picture of a page, drawn once per distinct input and served
 * immutable from `/social/`. Drawn rather than a hero render (portrait screenshots crop badly and
 * show one sample), reusing already-baked [ServeHeroImages.Hero] thumbnails without a render
 * permit. Content-addressed and memoised by input ([cacheKey]); nothing is evicted, so subtitles
 * must never carry per-request values (see [ServeSocialCard.Spec]).
 */
internal class ServeSocialCard {

  /** One baked card: the bytes to serve, how to name and validate them, and the declared size. */
  data class Card(
    val bytes: ByteArray,
    /** Content hash + `.png`; the `/social/{name}` segment and the ETag. */
    val fileName: String,
    val etag: String,
    /**
     * Catalogs allowed to serve this card; empty for the front door's. A set because identical
     * cards from two catalogs share one content hash, and each must still serve it. See
     * [Spec.system].
     */
    val systems: Set<String> = emptySet(),
  ) {
    /** Always [WIDTH] — declared so callers fill `og:image:width` from the card, not a constant. */
    val width: Int = WIDTH

    /** Always [HEIGHT]. */
    val height: Int = HEIGHT

    // See [ServeHeroImages.Hero]: identity equality. Cards are compared by hash, never by content,
    // and a data class over a ByteArray would otherwise generate an array-identity equals.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * What a card says and shows. [title] and [subtitle] are part of the cache key and must stay
   * stable for the page's life (counts are fine; timestamps are not, as each value mints a
   * never-evicted card). [heroes] are baked front-door thumbnails in order, at most [MAX_HEROES];
   * empty lays the text across the full width.
   */
  data class Spec(
    val title: String,
    val subtitle: String,
    val heroes: List<ServeHeroImages.Hero> = emptyList(),
    /**
     * The catalog this card depicts, or null for the whole-server card. Lets a top-level site
     * ([ServeSites]) refuse a neighbour's card: `/social/` is ungated and content-hashed, so
     * otherwise another catalog's card could be fetched through a one-catalog hostname.
     */
    val system: String? = null,
  )

  private val byFileName = ConcurrentHashMap<String, Card>()

  /** Bake memo by [cacheKey]; failures are cached too. */
  private val baked = ConcurrentHashMap<String, Optional<Card>>()

  /** The card for [spec], drawing it on first sight. Null only if PNG encoding fails. */
  fun cardFor(spec: Spec): Card? {
    val key = cacheKey(spec)
    baked[key]?.let {
      return it.orElse(null)
    }
    val card = draw(spec)
    baked[key] = Optional.ofNullable(card)
    return card
  }

  /** The baked card a `/social/{fileName}` request names, or null when unknown. */
  fun byFileName(fileName: String): Card? = byFileName[fileName]

  /**
   * Memo key: text plus each hero's content hash (not identity, since a refresh installs new hero
   * objects with usually unchanged bytes). Fields are joined with [FIELD_SEPARATOR], which can't
   * appear in a title.
   */
  private fun cacheKey(spec: Spec): String =
    (listOf(spec.system.orEmpty(), spec.title, spec.subtitle) +
        spec.heroes.take(MAX_HEROES).map { it.fileName })
      .joinToString(FIELD_SEPARATOR)

  /** Draw [spec] and register it under its content hash. Visible only for tests. */
  internal fun draw(spec: Spec): Card? {
    val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    try {
      ServeBrand.quality(g)
      g.color = ServeBrand.BG
      g.fillRect(0, 0, WIDTH, HEIGHT)
      val heroes =
        spec.heroes.take(MAX_HEROES).mapNotNull { hero -> ServeBrand.decodePng(hero.bytes) }
      // The text column narrows only when there is art to sit beside; a card with no thumbnails
      // spends the whole width on words rather than leaving a hole where the art would have been.
      val textWidth = if (heroes.isEmpty()) WIDTH - PAD * 2 else TEXT_COLUMN
      drawBrandRow(g)
      drawText(g, spec, textWidth)
      if (heroes.isNotEmpty()) drawHeroes(g, heroes)
    } finally {
      g.dispose()
    }
    val bytes = ServeBrand.encodePng(image) ?: return null
    val hash = sha256Hex(bytes).take(HASH_CHARS)
    val card =
      Card(
        bytes = bytes,
        fileName = "$hash.png",
        etag = "\"$hash\"",
        systems = setOfNotNull(spec.system),
      )
    // Identical bytes share a URL, so only the owners accumulate; first-wins would 404 the second
    // catalog's own `og:image`.
    return byFileName.compute(card.fileName) { _, existing ->
      when {
        existing == null -> card
        // Identical spec, identical owner: the registration already there IS the right answer, and
        // handing back the same instance is what callers (and ServeSocialCardTest) rely on.
        card.systems.all { it in existing.systems } -> existing
        else -> existing.copy(systems = existing.systems + card.systems)
      }
    } ?: card
  }

  /** The product mark and wordmark, top-left — the same pair the site header opens with. */
  private fun drawBrandRow(g: Graphics2D) {
    ServeBrand.drawMark(g, PAD.toDouble(), PAD.toDouble(), MARK_SIZE.toDouble())
    g.font = ServeBrand.font(26f, medium = true)
    g.color = ServeBrand.FG_MUTED
    val metrics = g.fontMetrics
    // Optically centred on the mark rather than sharing its baseline: the wordmark is half the
    // mark's height, and a shared baseline would hang it off the bottom of the circle.
    val baseline = PAD + MARK_SIZE / 2f + (metrics.ascent - metrics.descent) / 2f
    g.drawString("compose-preview", (PAD + MARK_SIZE + 18).toFloat(), baseline)
  }

  /**
   * Headline and supporting line, vertically centred under the brand row. The headline shrinks to
   * fit rather than truncating (titles are operator data of varying length), ellipsizing only if
   * two lines still overflow at the smallest size.
   */
  private fun drawText(g: Graphics2D, spec: Spec, width: Int) {
    val headline =
      HEADLINE_SIZES.firstNotNullOfOrNull { size ->
        val font = ServeBrand.font(size, medium = true)
        wrap(g, spec.title, font, width, MAX_HEADLINE_LINES)?.let { lines ->
          Laid(font, lines, size)
        }
      }
        ?: ServeBrand.font(HEADLINE_SIZES.last(), medium = true).let { font ->
          Laid(
            font,
            ellipsize(g, spec.title, font, width, MAX_HEADLINE_LINES),
            HEADLINE_SIZES.last(),
          )
        }
    val subFont = ServeBrand.font(SUBTITLE_SIZE, medium = false)
    val subLines =
      wrap(g, spec.subtitle, subFont, width, MAX_SUBTITLE_LINES)
        ?: ellipsize(g, spec.subtitle, subFont, width, MAX_SUBTITLE_LINES)

    val headlineLead = headline.size * LEADING
    val subLead = SUBTITLE_SIZE * LEADING
    val blockHeight =
      headline.lines.size * headlineLead +
        (if (subLines.isEmpty()) 0f else GAP + subLines.size * subLead)
    val bandTop = PAD + MARK_SIZE + BRAND_GAP
    val bandHeight = HEIGHT - PAD - bandTop
    var y = bandTop + max(0f, (bandHeight - blockHeight) / 2f)

    g.color = ServeBrand.ACCENT
    g.fillRect(
      PAD,
      (y - ACCENT_RULE_GAP - ACCENT_RULE_H).roundToInt(),
      ACCENT_RULE_W,
      ACCENT_RULE_H,
    )

    g.font = headline.font
    g.color = ServeBrand.FG
    headline.lines.forEach { line ->
      y += headline.font.size2D
      g.drawString(line, PAD.toFloat(), y)
      y += headlineLead - headline.font.size2D
    }
    if (subLines.isEmpty()) return
    y += GAP
    g.font = subFont
    g.color = ServeBrand.FG_MUTED
    subLines.forEach { line ->
      y += SUBTITLE_SIZE
      g.drawString(line, PAD.toFloat(), y)
      y += subLead - SUBTITLE_SIZE
    }
  }

  /** A headline resolved to the largest size that fits, with the lines that size produced. */
  private data class Laid(val font: Font, val lines: List<String>, val size: Float)

  /**
   * Thumbnails bottom-aligned on tonal panels down the right: a shared floor reads as a shelf of
   * differently shaped devices, and the panel gives dark screenshots an edge against the dark card.
   */
  private fun drawHeroes(g: Graphics2D, heroes: List<BufferedImage>) {
    val columnX = PAD + TEXT_COLUMN + COLUMN_GAP
    val columnWidth = WIDTH - PAD - columnX
    val slotWidth = (columnWidth - HERO_GAP * (heroes.size - 1)) / heroes.size
    val maxImageW = min(slotWidth - PANEL_INSET * 2, MAX_HERO_WIDTH)
    val maxImageH = HEIGHT - PAD * 2 - PANEL_INSET * 2
    val floor = HEIGHT - PAD - PANEL_INSET
    heroes.forEachIndexed { index, hero ->
      // Never upscale: a square 480² watch face fitted to the card's height would dwarf the phone
      // beside it.
      val fit = minOf(maxImageW.toDouble() / hero.width, maxImageH.toDouble() / hero.height, 1.0)
      val w = max(1, (hero.width * fit).roundToInt())
      val h = max(1, (hero.height * fit).roundToInt())
      val x = columnX + index * (slotWidth + HERO_GAP) + (slotWidth - w) / 2
      val y = floor - h
      val panel =
        RoundRectangle2D.Double(
          (x - PANEL_INSET).toDouble(),
          (y - PANEL_INSET).toDouble(),
          (w + PANEL_INSET * 2).toDouble(),
          (h + PANEL_INSET * 2).toDouble(),
          PANEL_RADIUS,
          PANEL_RADIUS,
        )
      g.color = ServeBrand.PANEL
      g.fill(panel)
      g.color = ServeBrand.BORDER
      g.draw(panel)
      // Clip the render to a soft corner so it sits *in* the panel rather than on top of it.
      val clip = g.clip
      g.clip(
        RoundRectangle2D.Double(
          x.toDouble(),
          y.toDouble(),
          w.toDouble(),
          h.toDouble(),
          IMAGE_RADIUS,
          IMAGE_RADIUS,
        )
      )
      g.drawImage(hero, x, y, w, h, null)
      g.clip = clip
    }
  }

  /**
   * [text] broken on spaces into at most [maxLines] lines fitting [width], or null when it doesn't
   * fit (the signal to try a smaller size). No mid-word hyphenation.
   */
  private fun wrap(
    g: Graphics2D,
    text: String,
    font: Font,
    width: Int,
    maxLines: Int,
  ): List<String>? {
    val metrics = g.getFontMetrics(font)
    val lines = mutableListOf<String>()
    var line = StringBuilder()
    for (word in text.split(' ').filter { it.isNotEmpty() }) {
      val candidate = if (line.isEmpty()) word else "$line $word"
      if (metrics.stringWidth(candidate) <= width) {
        line = StringBuilder(candidate)
        continue
      }
      if (line.isEmpty()) return null // A single word wider than the column.
      lines += line.toString()
      if (lines.size == maxLines) return null
      line = StringBuilder(word)
      if (metrics.stringWidth(word) > width) return null
    }
    if (line.isNotEmpty()) lines += line.toString()
    return lines.takeIf { it.isNotEmpty() && it.size <= maxLines }
  }

  /** Last resort when no size fits: fill [maxLines] and ellipsize the last, character-wise. */
  private fun ellipsize(
    g: Graphics2D,
    text: String,
    font: Font,
    width: Int,
    maxLines: Int,
  ): List<String> {
    val metrics = g.getFontMetrics(font)
    val lines = mutableListOf<String>()
    var rest = text
    while (rest.isNotEmpty() && lines.size < maxLines) {
      val last = lines.size == maxLines - 1
      var cut = rest.length
      while (
        cut > 1 &&
          metrics.stringWidth(rest.take(cut) + if (last && cut < rest.length) "…" else "") > width
      ) {
        cut--
      }
      val head = rest.take(cut)
      lines += if (last && cut < rest.length) "$head…" else head
      rest = rest.drop(cut)
    }
    return lines
  }

  companion object {
    /**
     * The card size: 1200×630 is the Open Graph and X `summary_large_image` size and native for
     * Slack, Discord, LinkedIn and iMessage, so it is never cropped.
     */
    const val WIDTH = 1200

    const val HEIGHT = 630

    /** The URL prefix baked cards are served under. See [ServeHttpServer]'s `/social/` route. */
    const val PATH_PREFIX = "/social"

    /**
     * Thumbnails drawn at most: portrait art in a wide card means three would each shrink to
     * slivers at chat-client size; two fill the height and still say "more than one catalog".
     */
    const val MAX_HEROES = 2

    /**
     * Widest a thumbnail is drawn, matching [ServeHeroImages.DISPLAY_CAP], so a phone and a watch
     * keep their relative sizes.
     */
    private const val MAX_HERO_WIDTH = ServeHeroImages.DISPLAY_CAP

    private const val PAD = 64
    private const val MARK_SIZE = 56

    /** Space between the brand row and the text block below it. */
    private const val BRAND_GAP = 40

    /** Width of the words column when there is artwork beside it. */
    private const val TEXT_COLUMN = 520

    /** Gutter between the text column and the artwork. */
    private const val COLUMN_GAP = 40

    private const val HERO_GAP = 20
    private const val PANEL_INSET = 12
    private const val PANEL_RADIUS = 20.0
    private const val IMAGE_RADIUS = 10.0

    /** Headline sizes tried largest-first; the first that fits [MAX_HEADLINE_LINES] wins. */
    private val HEADLINE_SIZES = listOf(66f, 58f, 50f, 42f, 36f)

    private const val MAX_HEADLINE_LINES = 2
    private const val SUBTITLE_SIZE = 28f
    private const val MAX_SUBTITLE_LINES = 2

    /** Line height as a multiple of the font size. */
    private const val LEADING = 1.18f

    /** Space between the headline block and the subtitle. */
    private const val GAP = 26f

    private const val ACCENT_RULE_W = 72
    private const val ACCENT_RULE_H = 5
    private const val ACCENT_RULE_GAP = 28

    private const val HASH_CHARS = 16

    /**
     * Separates [cacheKey] fields: the ASCII unit separator can't be typed into a title, so no
     * fields collide.
     */
    private const val FIELD_SEPARATOR = "\u001F"

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
