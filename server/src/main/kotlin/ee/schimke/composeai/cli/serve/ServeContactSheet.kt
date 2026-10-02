package ee.schimke.composeai.cli.serve

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * **One picture of N renders** — the one contact sheet this server draws.
 *
 * Two callers, one renderer:
 * - `catalog_render_matrix`'s chat fallback captions each tile with its number and overrides (`3 ·
 *   uiMode=dark, fontScale=1.0`). A chat surface is where "try three, pick one" ends up when there
 *   is no MCP App to draw a picker in: Claude in Slack posts text and attachments and steers only
 *   on a reply, so the choice is made the way a person makes it in a thread anyway — look at one
 *   image, answer "2". Each number matches the cell's `index`, so the number in the reply names
 *   exactly one set of overrides. The number sits in the caption strip under the tile, never over
 *   it: a 30 px badge drawn on the picture hid most of a 40 px component (measured on
 *   preview.coo.ee in compose-preview-server#1262).
 * - `ui_builder_render_design_matrix` captions each tile with its device, theme and font scale,
 *   masks a round watch to its circle, and needs to know where each tile landed, which [compose]
 *   reports as [Placement]s.
 *
 * Bounded so it fits where it is going: tiles are scaled down (never up) to a longest edge of
 * [MAX_TILE_EDGE], the sheet is at most [MAX_COLUMNS] wide, and [compose] halves the tile edge
 * until the PNG is under the byte budget it is given — a 24-cell matrix of phone screens comes out
 * well under Slack's 3.75 MB attachment limit.
 */
internal object ServeContactSheet {
  /** The longest edge a tile is drawn at, before any shrinking for the byte budget. */
  const val MAX_TILE_EDGE: Int = 320

  /** Tiles per row, at most; fewer cells than this lay out as a near-square grid. */
  const val MAX_COLUMNS: Int = 6

  /** The smallest tile edge [compose] shrinks to while fitting a byte budget. */
  private const val MIN_TILE_EDGE = 96
  private const val GAP = 12
  private const val CAPTION = 20

  /**
   * The narrowest slot a captioned tile gets. A caption is cut to its slot's width, so without this
   * a small component's caption is cut to "1…" — and the number is the part a person replies with.
   * Kept at or under the design matrix's 160 px phone tile so those sheets keep their layout; a
   * longer label is still cut from the end, after the number, and the full overrides travel in the
   * numbered text list posted with the sheet.
   */
  private const val MIN_CAPTIONED_SLOT_WIDTH = 140
  private val BACKGROUND = Color(0xF2, 0xF2, 0xF2)
  private val MISSING = Color(0xE0, 0xE0, 0xE0)
  private val CAPTION_COLOR = Color(0x30, 0x30, 0x30)
  private val FRAME = Color(0xBD, 0xBD, 0xBD)

  /** One tile: its PNG (null or undecodable keeps the slot, drawn empty), and how to label it. */
  class Tile(val png: ByteArray?, val caption: String? = null, val round: Boolean = false)

  /** Where a tile's picture sits on the sheet, in sheet pixels. */
  data class Placement(val x: Int, val y: Int, val width: Int, val height: Int)

  class Sheet(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    /** One per tile, in order. */
    val placed: List<Placement>,
  )

  /**
   * The numbered sheet `catalog_render_matrix` hands a chat surface, or null when there is nothing
   * to draw or none of [pngs] decodes. Each tile is captioned `<n>` or `<n> · <label>`, with
   * [labels] read by position. A cell that does not decode keeps its slot, drawn empty, so the
   * numbers still line up with indices.
   */
  fun render(pngs: List<ByteArray>, labels: List<String> = emptyList()): ByteArray? {
    if (pngs.isEmpty()) return null
    if (pngs.none { decode(it) != null }) return null
    val tiles = pngs.mapIndexed { index, png ->
      val number = "${index + 1}"
      Tile(
        png,
        labels.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { "$number · $it" } ?: number,
      )
    }
    return compose(tiles).png
  }

  /**
   * Lays [tiles] out and encodes the sheet, shrinking the tile edge until the PNG is under
   * [maxBytes] or the edge reaches its floor.
   */
  fun compose(tiles: List<Tile>, maxBytes: Int = Int.MAX_VALUE): Sheet {
    require(tiles.isNotEmpty()) { "a sheet needs at least one tile" }
    val images = tiles.map { tile -> tile.png?.let(::decode) }
    var edge = MAX_TILE_EDGE
    while (true) {
      val sheet = draw(tiles, images, edge)
      if (sheet.png.size <= maxBytes || edge <= MIN_TILE_EDGE) return sheet
      edge = max(MIN_TILE_EDGE, (edge * 0.7).roundToInt())
    }
  }

  /** A near-square grid: 4 cells are 2×2, 6 are 3×2, 24 are 5×5 with one slot empty. */
  internal fun columnsFor(count: Int): Int =
    min(MAX_COLUMNS, max(1, ceil(sqrt(count.toDouble())).toInt()))

  /** Fit within [edge] on the long edge, keeping the aspect; never enlarged. */
  internal fun scaledSize(width: Int, height: Int, edge: Int = MAX_TILE_EDGE): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= edge) return max(1, width) to max(1, height)
    val scale = edge.toDouble() / longest
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
  }

  private fun decode(bytes: ByteArray): BufferedImage? = runCatching {
    ImageIO.read(ByteArrayInputStream(bytes))
  }
    .getOrNull()

  private fun draw(
    tiles: List<Tile>,
    images: List<BufferedImage?>,
    edge: Int,
  ): Sheet {
    val sizes = images.map { image -> image?.let { scaledSize(it.width, it.height, edge) } }
    // A slot is as big as the biggest tile; an empty one is a square of the edge it was given,
    // unless some other tile says how big a slot is.
    val captioned = tiles.any { it.caption != null }
    val widest = sizes.maxOfOrNull { it?.first ?: 0 }?.takeIf { it > 0 } ?: edge
    val slotWidth = if (captioned) max(widest, MIN_CAPTIONED_SLOT_WIDTH) else widest
    val slotHeight = sizes.maxOfOrNull { it?.second ?: 0 }?.takeIf { it > 0 } ?: edge
    val captionHeight = if (captioned) CAPTION else 0
    val columns = columnsFor(tiles.size)
    val rows = ceil(tiles.size / columns.toDouble()).toInt()
    val width = GAP + columns * (slotWidth + GAP)
    val height = GAP + rows * (slotHeight + captionHeight + GAP)

    val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val placed = ArrayList<Placement>(tiles.size)
    val g = sheet.createGraphics()
    try {
      g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(
        RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
      )
      g.color = BACKGROUND
      g.fillRect(0, 0, width, height)
      tiles.forEachIndexed { index, tile ->
        val left = GAP + (index % columns) * (slotWidth + GAP)
        val top = GAP + (index / columns) * (slotHeight + captionHeight + GAP)
        val image = images[index]
        val size = sizes[index]
        if (image != null && size != null) {
          // Centred in its slot, so tiles of different shapes still read as one grid.
          val x = left + (slotWidth - size.first) / 2
          val y = top + (slotHeight - size.second) / 2
          val clip = g.clip
          if (tile.round) {
            g.clip(
              Ellipse2D.Double(
                x.toDouble(),
                y.toDouble(),
                size.first.toDouble(),
                size.second.toDouble(),
              )
            )
          }
          g.drawImage(image, x, y, size.first, size.second, null)
          g.clip = clip
          if (captioned) {
            g.color = FRAME
            if (tile.round) g.drawOval(x, y, size.first - 1, size.second - 1)
            else g.drawRect(x, y, size.first - 1, size.second - 1)
          }
          placed += Placement(x, y, size.first, size.second)
        } else {
          if (captioned) {
            g.color = MISSING
            g.fillRect(left, top, slotWidth, slotHeight)
          }
          placed += Placement(left, top, slotWidth, slotHeight)
        }
        tile.caption?.let { caption ->
          g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
          g.color = CAPTION_COLOR
          g.drawString(
            ellipsize(caption, g.fontMetrics::stringWidth, slotWidth - 4),
            left + 2,
            top + slotHeight + CAPTION - 6,
          )
        }
      }
    } finally {
      g.dispose()
    }
    val png =
      ByteArrayOutputStream().use { out ->
        ImageIO.write(sheet, "png", out)
        out.toByteArray()
      }
    return Sheet(png, width, height, columns, rows, placed)
  }

  private fun ellipsize(text: String, measure: (String) -> Int, width: Int): String {
    if (measure(text) <= width) return text
    var cut = text.length
    while (cut > 1 && measure(text.take(cut) + "…") > width) cut--
    return text.take(cut) + "…"
  }
}
