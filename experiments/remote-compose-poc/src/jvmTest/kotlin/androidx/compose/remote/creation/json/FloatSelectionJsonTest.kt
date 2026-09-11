@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package androidx.compose.remote.creation.json

import androidx.compose.remote.core.RemoteComposeState
import androidx.compose.remote.core.operations.Utils
import androidx.compose.remote.core.operations.utilities.AnimatedFloatExpression
import androidx.compose.remote.creation.RemoteComposeWriter
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.protocol.RcDocument
import ee.schimke.composeai.rcplayer.protocol.RcHeader
import ee.schimke.composeai.rcplayer.protocol.RcVersion
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.rcplayer.runtime.RcPlayerState
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject

/** Feasibility only: match the creation-compose Float equality lowering before extending export. */
class FloatSelectionJsonTest {
  @Test fun ordinaryDecimals() = verify("ordinary", 1.25f, 2.5f, 3.75f)

  @Test fun adjacentFloats() = verify("adjacent", 1f, Float.fromBits(1f.toBits() + 1), 2f)

  @Test fun subnormalFloats() = verify("subnormal", Float.MIN_VALUE, Float.MIN_VALUE * 2, 0f)

  @Test fun oppositeExtremes() = verify("extremes", -Float.MAX_VALUE, Float.MAX_VALUE, 0f)

  @Test
  fun numericStorageMatchesAndroidXReference() {
    val reference = RemoteComposeState()
    val player = RcPlayerState(RcDocument(RcHeader(RcVersion(1, 0, 0)), emptyList()))
    for (value in
      listOf(
        1.25f,
        -2.75f,
        Float.MIN_VALUE,
        Float.MAX_VALUE,
        -Float.MAX_VALUE,
        Float.NaN,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
      )) {
      reference.updateFloat(100, value)
      player.setFloat(100, value)
      assertEquals(reference.getInteger(100), player.integer(100), "value=$value")
    }
  }

  private fun verify(name: String, first: Float, second: Float, fallback: Float) {
    val children = JSONArray()
    for ((i, value) in listOf(first, second).withIndex()) {
      children.put(
        JSONObject()
          .put("type", "floatEquals")
          .put("name", "match$i")
          .put("left", "@page")
          .put("right", value)
      )
    }
    children.put(
      JSONObject()
        .put("type", "integerExpression")
        .put("name", "index")
        .put("value", "(1 - @match0) * (2 - @match1)")
    )
    children.put(
      JSONObject()
        .put("type", "stateLayout")
        .put("indexId", "@index")
        .put(
          "children",
          JSONArray(
            listOf("#FFFF0000", "#FF00FF00", "#FF0000FF").map { color ->
              JSONObject()
                .put("type", "box")
                .put(
                  "modifiers",
                  JSONArray().put("fillMaxSize").put(JSONObject().put("background", color)),
                )
            }
          ),
        )
    )
    val source =
      JSONObject()
        .put(
          "header",
          JSONObject().put("width", 100).put("height", 100).put("apiLevel", 7).put("profiles", 513),
        )
        .put(
          "root",
          JSONArray()
            .put(
              JSONObject()
                .put("type", "resources")
                .put(
                  "variables",
                  JSONObject().put("page", JSONObject().put("value", first).put("export", true)),
                )
            )
            .put(
              JSONObject()
                .put("type", "box")
                .put("modifiers", JSONArray().put("fillMaxSize"))
                .put("children", children)
            ),
        )
        .toString()
    val writer =
      RemoteComposeWriter(
        RemoteComposeJsonParser.DEFAULT_PLATFORM,
        RemoteComposeJsonParser.parseApiLevel(source),
        *RemoteComposeJsonParser.parseHeaderOnly(source).sortedBy { it.tag }.toTypedArray(),
      )
    val parser = RemoteComposeJsonParser(writer)
    IntegerExpressions.install(parser)
    parser.registerComponentParser("floatEquals") { component, _, output, current ->
      val left = current.parseFloat(component.get("left"))
      val right = current.parseFloat(component.get("right"))
      val match =
        output.floatExpression(
          1f,
          0f,
          right,
          left,
          AnimatedFloatExpression.SUB,
          AnimatedFloatExpression.ABS,
          AnimatedFloatExpression.IFELSE,
        )
      val encoded = output.integerExpression(0x100000000L + Utils.idFromNan(match))
      val name = component.getString("name")
      current.mIntegerVariables[name] = encoded
      current.mVariables[name] = Utils.asNan(encoded.toInt())
      current.recordVariable(name, encoded.toInt())
    }
    parser.parse(source)
    val bytes = writer.encodeToByteArray()
    val evidence = File("build/evidence/float-selection").apply { mkdirs() }
    File(evidence, "$name.json").writeText(source)
    File(evidence, "$name.rc").writeBytes(bytes)
    runSkikoComposeUiTest(size = Size(100f, 100f), density = Density(1f)) {
      val state = mutableStateMapOf<String, RcNamedValue>()
      setContent { RcComposePlayer(bytes, namedValues = state) }
      fun check(index: Int) {
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val pixels = onRoot().captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(
          x,
          y,
          pixels[x, y].toArgb(),
        )
        ImageIO.write(image, "png", File(evidence, "$name-$index.png"))
        val color = pixels[50, 50]
        val channels = listOf(color.red, color.green, color.blue)
        assertTrue(
          channels[index] > .9f && channels.filterIndexed { i, _ -> i != index }.all { it < .1f },
          "expected branch $index, got $color; first=$first second=$second",
        )
      }
      check(0)
      runOnIdle { state["page"] = RcNamedValue.FloatValue(second) }
      check(1)
      runOnIdle { state["page"] = RcNamedValue.FloatValue(fallback) }
      check(2)
      runOnIdle { state["page"] = RcNamedValue.FloatValue(first) }
      check(0)
    }
  }
}
