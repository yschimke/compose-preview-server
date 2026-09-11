@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package poc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue

/** Prototype elaboration → production JSON exporter/compiler → real player, without new opcodes. */
class RepetitionJsonProofTest {
  @Test
  fun rowArgumentsPlacementPaddingAndEachClickSurviveCompilation() {
    val directory = System.getProperty("repetitionProofDir")
    assumeTrue("Run with -PrepetitionProofDir", directory != null)
    for (density in listOf(1, 2)) {
      val source = File(directory!!, "rows-$density.json").readText()
      val bytes = RemoteComposeJson.compile(source)
      assertContentEquals(bytes, RemoteComposeJson.compile(source))
      val output = File("build/repetition-proof").apply { mkdirs() }
      File(output, "rows-$density.rc").writeBytes(bytes)
      File(output, "rows-$density.operations.json").writeText(RemoteComposeJson.dump(bytes))
      assertEquals(100 * density, JSONObject(source).getJSONObject("header").getInt("width"))
      runSkikoComposeUiTest(
        size = Size(100f * density, 120f * density),
        density = Density(density.toFloat()),
      ) {
        setContent { RcComposePlayer(bytes) }
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        fun pixel(x: Int, y: Int) = onRoot().captureToImage().toPixelMap()[x * density, y * density]
        fun expect(expected: Color, x: Int, y: Int) {
          val actual = pixel(x, y)
          assertTrue(
            abs(expected.red - actual.red) < .08f &&
              abs(expected.green - actual.green) < .08f &&
              abs(expected.blue - actual.blue) < .08f &&
              abs(expected.alpha - actual.alpha) < .08f,
            "density=$density ($x,$y): expected $expected, got $actual",
          )
        }
        for (row in 0..2) {
          val y = 2 + row * 24 + 8
          expect(Color.Red, 12, y)
          expect(Color.Green, 28 + row * 8, y)
          expect(Color.Transparent, 2, y)
          if (row > 0) expect(Color.Transparent, 22, y)
        }
        expect(Color.Red, 30, 78)
        val image = onRoot().captureToImage().toPixelMap()
        val png = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) png.setRGB(
          x,
          y,
          image[x, y].toArgb(),
        )
        ImageIO.write(png, "png", File(output, "rows-$density.png"))
        // Each expanded body must own clickable geometry; the last copy cannot shadow the others.
        for (row in 0..2) {
          val y = 10 + row * 24
          onRoot().performTouchInput {
            click(Offset((28 + row * 8f) * density, y.toFloat() * density))
          }
          mainClock.advanceTimeBy(1000)
          waitForIdle()
          expect(Color.Green, 30, 78)
          onRoot().performTouchInput { click(Offset(12f * density, y.toFloat() * density)) }
          mainClock.advanceTimeBy(1000)
          waitForIdle()
          expect(Color.Red, 30, 78)
        }
      }
    }
  }
}
