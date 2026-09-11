@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package poc

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
import ee.schimke.composeai.rcplayer.protocol.RcValueStringChangeAction
import ee.schimke.composeai.rcplayer.runtime.RcClickActionBlock
import ee.schimke.composeai.rcplayer.runtime.RcLinkedNode
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import org.junit.Assume.assumeTrue

class MutableStringJsonTest {
  @Test
  fun productionStringAssignmentsPreserveExactLiteralsAndIndependentVariables() {
    val directory = System.getProperty("productionStringJsonDir")
    assumeTrue(directory != null && java.lang.Boolean.getBoolean("verifySharedJsonCompiler"))
    val files = File(directory!!).listFiles()!!.filter { it.extension == "json" }
    assertEquals(6, files.size)
    files.forEach { file ->
      val document = RcDocumentCodec.decode(RemoteComposeJson.compile(file.readText()))
      val state = RcPlayerState(document)
      val actions = document.operations.filterIsInstance<RcValueStringChangeAction>()
      assertEquals(2, actions.size)
      val expected = File(file.parentFile, file.nameWithoutExtension + ".expected.txt").readText()
      assertEquals(RcNamedValue.Text("Ready"), state.namedValue("first"))
      assertEquals(RcNamedValue.Text("Ready"), state.namedValue("second"))
      repeat(2) {
        state.executeClick(RcClickActionBlock(actions.map { RcLinkedNode.Operation(it) }))
        assertEquals(RcNamedValue.Text(expected), state.namedValue("first"), file.name)
        assertEquals(RcNamedValue.Text("Ready"), state.namedValue("second"), file.name)
        assertEquals(RcNamedValue.Text("Ready"), state.namedValue("__rc_text_0"))
        assertEquals(
          expected,
          state.text(actions.last().valueId),
          "Action literal must stay immutable",
        )
      }
      state.setNamedValue("second", RcNamedValue.Text("Review"))
      assertEquals(RcNamedValue.Text(expected), state.namedValue("first"))
    }
  }

  @Test
  fun equalInitialTextStateAndLiteralStayIndependentAfterActionsAndHostUpdates() {
    assumeTrue(java.lang.Boolean.getBoolean("verifySharedJsonCompiler"))
    val source =
      """{
      "compilerProfile":"compose-preview-state-v1",
      "header":{"width":360,"height":180},
      "root":[
        {"type":"mutableString","name":"first","value":"Ready"},
        {"type":"mutableString","name":"second","value":"Ready"},
        {"type":"column","modifiers":["fillMaxSize",{"background":"#FFFFFFFF"}],"children":[
          {"type":"text","text":"@first","fontSize":24,"modifiers":["fillMaxWidth",{"height":60},{"onClick":[{"type":"valueStringChange","target":"@first","value":"Changed"}]}]},
          {"type":"text","text":"@second","fontSize":24,"modifiers":["fillMaxWidth",{"height":60}]},
          {"type":"text","text":"Ready","fontSize":24,"modifiers":["fillMaxWidth",{"height":60}]}
        ]}
      ]
    }"""
    val bytes = RemoteComposeJson.compile(source)
    File("build/evidence").mkdirs()
    File("build/evidence/mutable-strings.json").writeText(source)
    File("build/evidence/mutable-strings.rc").writeBytes(bytes)
    runSkikoComposeUiTest(size = Size(360f, 180f), density = Density(1f)) {
      val overrides = mutableStateMapOf<String, RcNamedValue>()
      setContent { RcComposePlayer(bytes, namedValues = overrides) }
      waitForIdle()
      onAllNodesWithText("Ready").assertCountEquals(3)
      save("mutable-strings-before")
      onAllNodesWithText("Ready")[0].performClick()
      waitForIdle()
      onNodeWithText("Changed").assertExists()
      onAllNodesWithText("Ready").assertCountEquals(2)
      runOnIdle { overrides["second"] = RcNamedValue.Text("Review") }
      waitForIdle()
      onNodeWithText("Changed").assertExists()
      onNodeWithText("Review").assertExists()
      onNodeWithText("Ready").assertExists()
      save("mutable-strings-after")
    }
  }

  private fun SkikoComposeUiTest.save(name: String) {
    val pixels = onRoot().captureToImage().toPixelMap()
    val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(
      x,
      y,
      pixels[x, y].toArgb(),
    )
    ImageIO.write(image, "png", File("build/evidence/$name.png"))
  }
}
