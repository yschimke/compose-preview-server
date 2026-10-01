package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeContactSheetTest {

  private fun png(width: Int, height: Int): ByteArray =
    ByteArrayOutputStream().use {
      ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
      it.toByteArray()
    }

  @Test
  fun `the grid is near-square and never wider than its column cap`() {
    assertEquals(1, ServeContactSheet.columnsFor(1))
    assertEquals(2, ServeContactSheet.columnsFor(4))
    assertEquals(3, ServeContactSheet.columnsFor(6))
    assertEquals(5, ServeContactSheet.columnsFor(24))
    assertEquals(ServeContactSheet.MAX_COLUMNS, ServeContactSheet.columnsFor(48))
  }

  @Test
  fun `tiles shrink to the tile edge and are never enlarged`() {
    assertEquals(100 to 50, ServeContactSheet.scaledSize(100, 50))
    assertEquals(144 to 320, ServeContactSheet.scaledSize(1080, 2400))
  }

  @Test
  fun `a 24-cell matrix of phone screens fits a slack attachment`() {
    val sheet = assertNotNull(ServeContactSheet.render(List(24) { png(1080, 2400) }))
    val image = ImageIO.read(ByteArrayInputStream(sheet))

    assertTrue(sheet.size < 3_750_000, "sheet was ${sheet.size} bytes")
    assertTrue(
      image.width <= 12 + ServeContactSheet.MAX_COLUMNS * (ServeContactSheet.MAX_TILE_EDGE + 12)
    )
  }

  @Test
  fun `nothing to draw is no sheet`() {
    assertNull(ServeContactSheet.render(emptyList()))
    assertNull(ServeContactSheet.render(listOf(byteArrayOf(1, 2, 3))))
  }

  @Test
  fun `an undecodable cell keeps its slot so badges still match indices`() {
    val sheet = assertNotNull(ServeContactSheet.render(listOf(byteArrayOf(1), png(10, 10))))
    val image = ImageIO.read(ByteArrayInputStream(sheet))
    // Two slots wide even though only one cell decoded.
    assertTrue(image.width > 2 * 10, "width ${image.width}")
  }

  @Test
  fun `captioned tiles report where each landed, and a byte budget shrinks them`() {
    val tiles = List(4) { ServeContactSheet.Tile(png(400, 800), caption = "Phone $it") }

    val roomy = ServeContactSheet.compose(tiles)
    assertEquals(2, roomy.columns)
    assertEquals(2, roomy.rows)
    assertEquals(4, roomy.placed.size)
    // 400×800 fits a 320 edge as 160×320, and the second column starts a slot and a gap later.
    assertEquals(160, roomy.placed[0].width)
    assertEquals(320, roomy.placed[0].height)
    assertEquals(roomy.placed[0].x + 160 + 12, roomy.placed[1].x)

    val tight = ServeContactSheet.compose(tiles, maxBytes = 1)
    assertTrue(tight.placed[0].height < roomy.placed[0].height, "shrank to fit the budget")
  }

  @Test
  fun `a missing tile still gets a placement`() {
    val sheet =
      ServeContactSheet.compose(
        listOf(ServeContactSheet.Tile(null, caption = "gone"), ServeContactSheet.Tile(png(10, 20)))
      )
    assertEquals(2, sheet.placed.size)
    assertEquals(sheet.placed[1].height, sheet.placed[0].height)
  }
}
