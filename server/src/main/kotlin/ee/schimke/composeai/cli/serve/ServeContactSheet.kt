package ee.schimke.composeai.cli.serve

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
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
 * **One picture of N alternatives**, each badged with its number.
 *
 * A chat surface is where "try three, pick one" ends up when there is no MCP App to draw a picker
 * in: Claude in Slack posts text and attachments, at most five attachments to a message, and steers
 * only on a reply — not a button and not a reaction. So the choice is made the way a person makes
 * it in a thread anyway: look at one image, answer "2". This draws that image from a
 * `catalog_render_matrix`'s cells, with each tile's badge matching the cell's `index`, so the
 * number in the reply names exactly one set of overrides.
 *
 * Bounded so it fits where it is going: tiles are scaled down (never up) to [MAX_TILE_EDGE], and
 * the sheet is at most [MAX_COLUMNS] wide — a 24-cell matrix of phone screens comes out well under
 * Slack's 3.75 MB attachment limit.
 */
internal object ServeContactSheet {
  /** The longest edge a tile is drawn at. */
  const val MAX_TILE_EDGE: Int = 320

  /** Tiles per row, at most; fewer cells than this lay out as a near-square grid. */
  const val MAX_COLUMNS: Int = 6

  private const val GAP = 12
  private const val BADGE = 30
  private val BACKGROUND = Color(0xF2, 0xF2, 0xF2)
  private val BADGE_FILL = Color(0x1F, 0x1F, 0x1F)

  /**
   * The sheet as PNG bytes, or null when there is nothing to draw or none of [pngs] decodes. A cell
   * that does not decode keeps its slot, drawn empty, so badge numbers still line up with indices.
   */
  fun render(pngs: List<ByteArray>): ByteArray? {
    if (pngs.isEmpty()) return null
    val images = pngs.map { bytes ->
      runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    }
    if (images.all { it == null }) return null
    val tiles = images.map { image -> image?.let { scaledSize(it.width, it.height) } }
    val tileWidth = tiles.maxOf { it?.first ?: 1 }
    val tileHeight = tiles.maxOf { it?.second ?: 1 }
    val columns = columnsFor(pngs.size)
    val rows = ceil(pngs.size / columns.toDouble()).toInt()
    val width = GAP + columns * (tileWidth + GAP)
    val height = GAP + rows * (tileHeight + GAP)

    val sheet = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
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
      g.font = Font(Font.SANS_SERIF, Font.BOLD, 16)
      images.forEachIndexed { index, image ->
        val x = GAP + (index % columns) * (tileWidth + GAP)
        val y = GAP + (index / columns) * (tileHeight + GAP)
        val size = tiles[index]
        if (image != null && size != null) {
          g.drawImage(image, x, y, size.first, size.second, null)
        }
        drawBadge(g, x, y, index + 1)
      }
    } finally {
      g.dispose()
    }
    return ByteArrayOutputStream().use { out ->
      ImageIO.write(sheet, "png", out)
      out.toByteArray()
    }
  }

  /** A near-square grid: 4 cells are 2×2, 6 are 3×2, 24 are 5×5 with one slot empty. */
  internal fun columnsFor(count: Int): Int =
    min(MAX_COLUMNS, max(1, ceil(sqrt(count.toDouble())).toInt()))

  /** Fit within [MAX_TILE_EDGE] on the long edge, keeping the aspect; never enlarged. */
  internal fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
    val longest = max(width, height)
    if (longest <= MAX_TILE_EDGE) return max(1, width) to max(1, height)
    val scale = MAX_TILE_EDGE.toDouble() / longest
    return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
  }

  private fun drawBadge(g: java.awt.Graphics2D, x: Int, y: Int, number: Int) {
    g.color = BADGE_FILL
    g.fillOval(x + 4, y + 4, BADGE, BADGE)
    g.color = Color.WHITE
    val label = number.toString()
    val metrics = g.fontMetrics
    val textX = x + 4 + (BADGE - metrics.stringWidth(label)) / 2
    val textY = y + 4 + (BADGE - metrics.height) / 2 + metrics.ascent
    g.drawString(label, textX, textY)
  }
}
