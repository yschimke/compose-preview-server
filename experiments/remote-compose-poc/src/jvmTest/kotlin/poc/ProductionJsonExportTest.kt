@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package poc

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
import ee.schimke.composeai.rcplayer.runtime.RcNamedValue
import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import java.io.File
import kotlin.test.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue

/** Actual shared exporter output → shared compiler → ordinary player clicks and named updates. */
class ProductionJsonExportTest {
  @Test
  fun stockMutableStringsShareAnIdWhenTheirInitialTextMatches() {
    val source =
      """{"header":{"width":100,"height":100},"root":[
      {"type":"variable","vtype":"string","name":"first","value":"Ready","export":true},
      {"type":"variable","vtype":"string","name":"second","value":"Ready","export":true},
      {"type":"box"}]}"""
    val operations =
      JSONObject(RemoteComposeJson.dump(RemoteComposeJson.compile(source)))
        .getJSONArray("operations")
    val names =
      (0 until operations.length())
        .map { operations.getJSONObject(it) }
        .filter { it.optString("type") == "NamedVariable" }
    assertEquals(setOf("first", "second"), names.map { it.getString("varName") }.toSet())
    // Retain the concrete upstream limitation that prevents silently exporting independent state.
    assertEquals(1, names.map { it.getInt("varId") }.toSet().size)
  }

  @Test
  fun productionDocumentsRetainSelectionActionsAndPadding() {
    val directory = System.getProperty("productionJsonDir")
    assumeTrue("Run with -PproductionJsonDir and -PlocalJsonCompilerManifest", directory != null)
    check(java.lang.Boolean.getBoolean("verifySharedJsonCompiler")) {
      "The shared compiler manifest is required"
    }
    val files =
      File(directory!!).listFiles()!!.filter { it.extension == "json" }.sortedBy { it.name }
    assertEquals(10, files.size, "Five production scenarios at two densities")
    files.forEach { file ->
      val source = file.readText()
      val header = JSONObject(source).getJSONObject("header")
      val size = header.getInt("width")
      val density = size / 100f
      val bytes = RemoteComposeJson.compile(source)
      assertContentEquals(bytes, RemoteComposeJson.compile(source))
      runSkikoComposeUiTest(
        size = Size(size.toFloat(), size.toFloat()),
        density = Density(density),
      ) {
        val overrides = mutableStateMapOf<String, RcNamedValue>()
        setContent { RcComposePlayer(bytes, namedValues = overrides) }
        waitForIdle()
        mainClock.advanceTimeBy(1000)
        val coordinate = (20 * density).toInt()
        val initial = onRoot().captureToImage().toPixelMap()
        assertTrue(initial[coordinate, coordinate].red > .9f, "${file.name}: initial case")
        assertTrue(
          initial[(2 * density).toInt(), (2 * density).toInt()].alpha < .1f,
          "${file.name}: retained padding",
        )
        onRoot().performTouchInput { click(Offset(coordinate.toFloat(), coordinate.toFloat())) }
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val selected = onRoot().captureToImage().toPixelMap()[coordinate, coordinate]
        assertTrue(
          // The player's standard clickable indication can tint the fill after activation.
          selected.green > .8f && selected.red < .1f && selected.blue < .1f,
          "${file.name}: ordered click actions: $selected",
        )
        if (file.name.startsWith("Boolean")) {
          onRoot().performTouchInput { click(Offset(coordinate.toFloat(), coordinate.toFloat())) }
          mainClock.advanceTimeBy(1000)
          waitForIdle()
          val toggledBack = onRoot().captureToImage().toPixelMap()[coordinate, coordinate]
          assertTrue(
            toggledBack.red > .8f && toggledBack.green < .1f,
            "${file.name}: repeated toggle reads updated state",
          )
        } else {
          runOnIdle { overrides["page"] = RcNamedValue.Integer(999) }
          mainClock.advanceTimeBy(1000)
          waitForIdle()
          val fallback = onRoot().captureToImage().toPixelMap()[coordinate, coordinate]
          assertTrue(
            fallback.blue > .8f && fallback.red < .1f && fallback.green < .1f,
            "${file.name}: fallback: $fallback",
          )
        }
      }
    }
  }
}
