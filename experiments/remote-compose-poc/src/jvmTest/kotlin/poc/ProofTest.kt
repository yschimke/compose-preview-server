@file:OptIn(
  androidx.compose.ui.test.ExperimentalTestApi::class,
  androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package poc

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.compose.composeSupportReport
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class ProofTest {
  @Test
  fun actualJsonCompilesAndPlayerSupportsIt() {
    val bytes = compileRemote(File("fixture.remote.json").readText())
    val document = RcDocumentCodec.decode(bytes)
    println("Operations: " + document.operations.map { it::class.simpleName })
    val report = document.composeSupportReport()
    println("Support: $report")
    assertTrue(report.fullyRenderable, report.toString())
    assertContentEquals(bytes, compileRemote(File("fixture.remote.json").readText()))
  }

  @Test
  fun rejectsEmptyDocument() {
    assertFailsWith<IllegalArgumentException> { compileRemote("{}") }
  }

  @Test
  fun generatedDocumentWorksAtRetinaDensity() =
    runSkikoComposeUiTest(size = Size(640f, 520f), density = Density(2f)) {
      val bytes = compileRemote(File("fixture-density2.remote.json").readText())
      setContent { MaterialTheme { RcComposePlayer(bytes) } }
      waitForIdle()
      val before = onRoot().captureToImage().toPixelMap()
      assertTrue(before[480, 170].blue > before[480, 170].red, "full-sized Off panel")
      onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(360f, 170f)) }
      mainClock.advanceTimeBy(1000)
      waitForIdle()
      val after = onRoot().captureToImage().toPixelMap()
      assertTrue(after[480, 170].green > after[480, 170].red + 0.15f, "full-sized On panel")
      save("remote-density2-after")
    }

  @Test
  fun realDocumentSwitchesAndCallsHostWithoutRecompilation() =
    runSkikoComposeUiTest(size = Size(320f, 260f), density = Density(1f)) {
      val bytes = compileRemote(File("fixture.remote.json").readText())
      val events = mutableListOf<String>()
      setContent { MaterialTheme { RcComposePlayer(bytes, onEvent = { events += it.toString() }) } }
      waitForIdle()
      save("remote-before")
      onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(180f, 85f)) }
      mainClock.advanceTimeBy(1000)
      waitForIdle()
      save("remote-after")
      assertTrue(events.any { "lighting.changed" in it }, events.toString())
      val pixel = onRoot().captureToImage().toPixelMap()[240, 85]
      assertTrue(pixel.green > pixel.red + 0.15f, "Expected green On branch; got $pixel")
      onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(180f, 85f)) }
      mainClock.advanceTimeBy(1000)
      waitForIdle()
      assertEquals(2, events.count { "lighting.changed" in it })
      val off = onRoot().captureToImage().toPixelMap()[240, 85]
      assertTrue(off.blue > off.green, "Expected grey-blue Off branch; got $off")
    }

  @Test
  fun generatedComposeCompilesAndSwitchesAndCallsHost() =
    runSkikoComposeUiTest(size = Size(320f, 260f), density = Density(1f)) {
      val events = mutableListOf<String>()
      setContent { MaterialTheme { GeneratedInterface(events::add) } }
      waitForIdle()
      onNodeWithText("Off").assertExists()
      save("compose-before")
      onNodeWithText("Off").performClick()
      waitForIdle()
      onNodeWithText("On").assertExists()
      assertEquals(listOf("lighting.changed"), events)
      save("compose-after")
    }

  private fun SkikoComposeUiTest.save(name: String) {
    val pixels = onRoot().captureToImage().toPixelMap()
    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
      val c = pixels[x, y]
      image.setRGB(
        x,
        y,
        ((c.alpha * 255).toInt() shl 24) or
          ((c.red * 255).toInt() shl 16) or
          ((c.green * 255).toInt() shl 8) or
          (c.blue * 255).toInt(),
      )
    }
    File("build/evidence").mkdirs()
    ImageIO.write(image, "png", File("build/evidence/$name.png"))
  }
}
