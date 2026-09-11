@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package poc

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import kotlin.test.*
import org.json.JSONArray
import org.json.JSONObject

class IntegerSelectionJsonTest {
  private fun json(initial: Int, values: List<Int>): String {
    fun expression(name: String, value: String) =
      JSONObject().put("type", "integerExpression").put("name", name).put("value", value)
    val children = JSONArray()
    // Quotients and remainders preserve all 32 bits without needing unsupported bitwise syntax.
    children.put(expression("low", "@page % 65536"))
    children.put(expression("high", "@page / 65536"))
    var ordinal = values.size.toString()
    values.withIndex().reversed().forEach { (index, value) ->
      children.put(expression("low$index", "1 - min(1, abs(@low - (${value % 65536})))"))
      children.put(expression("high$index", "1 - min(1, abs(@high - (${value / 65536})))"))
      children.put(expression("match$index", "@low$index * @high$index"))
      children.put(
        expression("index$index", "@match$index * $index + (1 - @match$index) * $ordinal")
      )
      ordinal = "@index$index"
    }
    val branches = JSONArray()
    (values + 0).forEachIndexed { index, _ ->
      val color =
        if (index == 0) "#FFFF0000" else if (index == values.size) "#FF0000FF" else "#FF00FF00"
      branches.put(
        JSONObject()
          .put("type", "box")
          .put(
            "modifiers",
            JSONArray().put("fillMaxSize").put(JSONObject().put("background", color)),
          )
      )
    }
    children.put(
      JSONObject()
        .put("type", "stateLayout")
        .put("indexId", ordinal)
        .put("modifiers", JSONArray().put("fillMaxSize"))
        .put("children", branches)
    )
    return JSONObject()
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
                "integers",
                JSONObject().put("page", JSONObject().put("value", initial).put("export", true)),
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
  }

  @Test
  fun stockParserDoesNotPretendToSupportTheExtension() {
    assertFails { compileRemote(json(10, listOf(10, 20))) }
  }

  @Test
  fun authoredIntegerValuesSelectAndFallbackInTheRealPlayer() = verify(10, listOf(10, 20), 20, 30)

  @Test
  fun allIntegerBitsRemainSignificant() =
    verify(16777216, listOf(16777216, 16777217), 16777217, 16777218)

  @Test
  fun integerExtremesRemainExact() =
    verify(Int.MIN_VALUE, listOf(Int.MIN_VALUE, Int.MAX_VALUE), Int.MAX_VALUE, 0)

  @Test fun manyCasesUseBoundedExpressions() = verify(10, (10..130 step 10).toList(), 130, 999)

  private fun verify(initial: Int, values: List<Int>, next: Int, fallback: Int) =
    runSkikoComposeUiTest(size = Size(100f, 100f), density = Density(1f)) {
      val bytes = compileRemoteWithIntegerExpressions(json(initial, values))
      assertContentEquals(bytes, compileRemoteWithIntegerExpressions(json(initial, values)))
      val state = mutableStateMapOf<String, RcNamedValue>("page" to RcNamedValue.Integer(initial))
      setContent { RcComposePlayer(bytes, namedValues = state) }
      waitForIdle()
      val before = onRoot().captureToImage().toPixelMap()[50, 50]
      assertTrue(before.red > .9f && before.green < .1f, "initial: $before")
      state["page"] = RcNamedValue.Integer(next)
      mainClock.advanceTimeBy(1000)
      waitForIdle()
      val selected = onRoot().captureToImage().toPixelMap()[50, 50]
      assertTrue(selected.green > .9f && selected.red < .1f, "selected: $selected")
      state["page"] = RcNamedValue.Integer(fallback)
      mainClock.advanceTimeBy(1000)
      waitForIdle()
      val other = onRoot().captureToImage().toPixelMap()[50, 50]
      assertTrue(other.blue > .9f && other.green < .1f, "fallback: $other")
    }
}
