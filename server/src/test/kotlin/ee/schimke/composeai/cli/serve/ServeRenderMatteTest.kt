package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.render.PreviewBackdrop
import ee.schimke.composeai.data.render.PreviewClip
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeRenderMatteTest {

  private val darkStage =
    ServeRenderMatte.Stage(
      backdrop = PreviewBackdrop.Backdrop("#FF1C1B1F", PreviewBackdrop.Source.CATALOG_SURFACE)
    )

  private val lightStage =
    ServeRenderMatte.Stage(
      backdrop = PreviewBackdrop.Backdrop("#FFFFFFFF", PreviewBackdrop.Source.CATALOG_SURFACE)
    )

  @Test
  fun `parses the wire values and the page toggle's aliases`() {
    assertEquals(ServeRenderMatte.Mode.AUTO, ServeRenderMatte.Mode.parse("auto"))
    assertEquals(ServeRenderMatte.Mode.PLATES, ServeRenderMatte.Mode.parse("PLATES"))
    assertEquals(ServeRenderMatte.Mode.SQUARE, ServeRenderMatte.Mode.parse(" square "))
    assertEquals(ServeRenderMatte.Mode.CIRCLE, ServeRenderMatte.Mode.parse("circle"))
    assertEquals(ServeRenderMatte.Mode.OFF, ServeRenderMatte.Mode.parse("off"))
    // The landing/viewer pages already spell their stage toggle `?bg=on|off`, so a URL that carried
    // one onto a render lane means the nearest sensible thing rather than 400.
    assertEquals(ServeRenderMatte.Mode.AUTO, ServeRenderMatte.Mode.parse("on"))
    assertNull(ServeRenderMatte.Mode.parse("chartreuse"))
    assertNull(ServeRenderMatte.Mode.parse(null))
  }

  @Test
  fun `off returns the very same bytes`() {
    val png = png(sticker())
    assertTrue(png.contentEquals(ServeRenderMatte.apply(png, ServeRenderMatte.Mode.OFF, darkStage)))
  }

  @Test
  fun `a backdrop with no colour leaves the render alone`() {
    val png = png(sticker())
    val none =
      ServeRenderMatte.Stage(backdrop = PreviewBackdrop.Backdrop(null, PreviewBackdrop.Source.NONE))
    assertTrue(png.contentEquals(ServeRenderMatte.apply(png, ServeRenderMatte.Mode.AUTO, none)))
  }

  @Test
  fun `bytes that are not an image are handed back untouched`() {
    val junk = byteArrayOf(1, 2, 3, 4)
    assertTrue(
      junk.contentEquals(ServeRenderMatte.apply(junk, ServeRenderMatte.Mode.AUTO, darkStage))
    )
  }

  @Test
  fun `auto plates a pale sticker on transparency`() {
    val out = decode(ServeRenderMatte.apply(png(sticker()), ServeRenderMatte.Mode.AUTO, darkStage))
    // Beside the content, inside the plate's padding: now the stage.
    assertEquals(STAGE_RGB, out.rgb(46, 30) and 0xFFFFFF)
    assertEquals(0xFF, out.rgb(46, 30) ushr 24)
    // The content itself still shows through, unmodified.
    assertEquals(0xFFFFFF, out.rgb(50, 30) and 0xFFFFFF)
    // A far corner is outside every plate and stays transparent — this is a stage, not a fill.
    assertEquals(0, out.rgb(199, 99) ushr 24)
  }

  @Test
  fun `auto leaves a render that paints its own dark ground`() {
    // The `scaffold` case: mostly solid, near-black ink. Legible on a white page unaided, and a
    // plate under it would only poke corners out past what the render drew.
    val png = png(solid(0xFF101014.toInt(), inset = 10))
    assertTrue(
      png.contentEquals(ServeRenderMatte.apply(png, ServeRenderMatte.Mode.AUTO, darkStage))
    )
  }

  @Test
  fun `auto plates a render that is translucent all the way through`() {
    // Wear's disabled states: the whole control drawn at reduced alpha, so it has NO solid pixels
    // at all — invisible on white, and invisible to any signal that averages ink luminance over
    // opaque pixels. The interior-translucency signal is what catches it.
    val image = blank()
    for (y in 20 until 80) for (x in 20 until 180) image.setRGB(x, y, 0x60FFFFFF)
    val out = decode(ServeRenderMatte.apply(png(image), ServeRenderMatte.Mode.AUTO, darkStage))
    // The plate's padding, just outside the translucent block: opaque stage.
    assertEquals(0xFF, out.rgb(15, 50) ushr 24, "the plate's padding is opaque stage")
    assertEquals(STAGE_RGB, out.rgb(15, 50) and 0xFFFFFF)
    // And a corner well clear of it is still transparent.
    assertEquals(0, out.rgb(199, 99) ushr 24)
  }

  @Test
  fun `auto does nothing on a light stage`() {
    val png = png(sticker())
    assertTrue(
      png.contentEquals(ServeRenderMatte.apply(png, ServeRenderMatte.Mode.AUTO, lightStage))
    )
  }

  @Test
  fun `auto does nothing to an empty render`() {
    val png = png(blank())
    assertTrue(
      png.contentEquals(ServeRenderMatte.apply(png, ServeRenderMatte.Mode.AUTO, darkStage))
    )
  }

  @Test
  fun `auto takes the device circle on a round preview, and stops at the bezel`() {
    val stage =
      ServeRenderMatte.Stage(
        backdrop = darkStage.backdrop,
        clip = PreviewClip.Shape.Circle(centerXDp = 96.0, centerYDp = 96.0, radiusDp = 96.0),
        frameWidthDp = 192.0,
        frameHeightDp = 192.0,
      )
    val image = BufferedImage(384, 384, BufferedImage.TYPE_INT_ARGB)
    for (y in 180 until 204) for (x in 100 until 284) image.setRGB(x, y, 0xFFEEEEFF.toInt())
    val out = decode(ServeRenderMatte.apply(png(image), ServeRenderMatte.Mode.AUTO, stage))
    // Inside the circle but away from the content: staged.
    assertEquals(STAGE_RGB, out.rgb(192, 60) and 0xFFFFFF)
    assertEquals(0xFF, out.rgb(192, 60) ushr 24)
    // The corner is outside the bezel and must stay transparent, or the watch is drawn as a
    // rectangle — the fault `wear-device-clip` was written to prevent.
    assertEquals(0, out.rgb(3, 3) ushr 24)
  }

  @Test
  fun `square fills the whole frame`() {
    val out =
      decode(ServeRenderMatte.apply(png(sticker()), ServeRenderMatte.Mode.SQUARE, darkStage))
    assertEquals(0xFF, out.rgb(0, 0) ushr 24)
    assertEquals(STAGE_RGB, out.rgb(0, 0) and 0xFFFFFF)
    assertEquals(0xFF, out.rgb(199, 99) ushr 24)
  }

  @Test
  fun `plates merge where they overlap and ignore antialiasing specks`() {
    val image = blank()
    // Two blocks 8px apart: each plate pads by 10, so they overlap and become one.
    for (y in 40 until 60) for (x in 40 until 60) image.setRGB(x, y, WHITE)
    for (y in 40 until 60) for (x in 68 until 88) image.setRGB(x, y, WHITE)
    // A 4-pixel speck far away: under the island floor, so it earns no plate of its own.
    for (y in 90 until 92) for (x in 190 until 192) image.setRGB(x, y, WHITE)
    val out = decode(ServeRenderMatte.apply(png(image), ServeRenderMatte.Mode.PLATES, darkStage))
    // The gap between the two blocks is inside the merged plate.
    assertEquals(0xFF, out.rgb(64, 50) ushr 24)
    assertEquals(STAGE_RGB, out.rgb(64, 50) and 0xFFFFFF)
    // The speck's own pixels survive, but nothing was staged around them.
    assertEquals(0, out.rgb(185, 91) ushr 24)
  }

  @Test
  fun `a capture too large to be worth a pass is handed back untouched`() {
    // A `?scroll=long` full-page render can be tens of thousands of pixels tall. The ceiling is
    // read off the header, so this never allocates the frame it declines.
    val image = BufferedImage(MAX_SIDE + 1, 40, BufferedImage.TYPE_INT_ARGB)
    for (y in 10 until 30) for (x in 10 until 4000) image.setRGB(x, y, WHITE)
    val png = png(image)
    assertTrue(
      png.contentEquals(ServeRenderMatte.apply(png, ServeRenderMatte.Mode.AUTO, darkStage))
    )
  }

  @Test
  fun `the frame keeps its dimensions`() {
    val out = decode(ServeRenderMatte.apply(png(sticker()), ServeRenderMatte.Mode.AUTO, darkStage))
    assertEquals(200, out.width)
    assertEquals(100, out.height)
  }

  @Test
  fun `an explicit mode overrides what auto would have decided`() {
    // Auto leaves this one alone (it paints its own dark ground); `square` is the caller saying so
    // anyway, and must be obeyed — that is what makes the decision inspectable.
    val png = png(solid(0xFF101014.toInt(), inset = 10))
    val out = ServeRenderMatte.apply(png, ServeRenderMatte.Mode.SQUARE, darkStage)
    assertNotEquals(png.toList(), out.toList())
    assertEquals(STAGE_RGB, decode(out).rgb(2, 2) and 0xFFFFFF)
  }

  /** A dark-first component sticker: pale ink on transparency, the case #284 is about. */
  private fun sticker(): BufferedImage {
    val image = blank()
    for (y in 25 until 35) for (x in 50 until 150) image.setRGB(x, y, WHITE)
    return image
  }

  /** A render that paints its own ground: [colour] over all but an [inset] margin. */
  private fun solid(colour: Int, inset: Int): BufferedImage {
    val image = blank()
    for (y in inset until 100 - inset) for (x in inset until 200 - inset) image.setRGB(x, y, colour)
    return image
  }

  private fun blank() = BufferedImage(200, 100, BufferedImage.TYPE_INT_ARGB)

  private fun BufferedImage.rgb(x: Int, y: Int): Int = getRGB(x, y)

  private fun png(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

  private fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png))

  private companion object {
    const val WHITE = 0xFFFFFFFF.toInt()
    /** `#1C1B1F`, the dark stage this suite resolves. */
    const val STAGE_RGB = 0x1C1B1F
    /** `ServeRenderMatte.MAX_SIDE_PX`, which is private; kept in step by the test above. */
    const val MAX_SIDE = 4096
  }
}
