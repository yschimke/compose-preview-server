package ee.schimke.composeai.uibuilder

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class WearWidgetCodeExporterTest {
  private val pin = JsonObject(emptyMap())
  private val environment = JsonObject(emptyMap())

  @Test
  fun `hello generates the sample's own shape`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(helloWidgetUiBuilderDocument("hello", pin, environment))
        )
        .source

    write("HelloWidget.kt", source)
    // The host's container appears nowhere: on-device the launcher draws it.
    assertTrue("WidgetContainer" !in source, source)
    assertTrue("widget-container" !in source, source)
    assertTrue("class HelloWidget : GlanceWearWidget()" in source, source)
    assertTrue(
      "WearWidgetDocument(background = WearWidgetBrush.color(colorScheme.primary))" in source,
      source,
    )
    assertTrue("fun HelloWidgetContent()" in source, source)
    assertTrue("SquircleSmallWidgetPreviewParams::class" in source, source)
  }

  @Test
  fun `weather generates its literal colours and its column`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(weatherWidgetUiBuilderDocument("weather", pin, environment))
        )
        .source

    write("WeatherWidget.kt", source)
    assertTrue("WearWidgetBrush.color(Color(0xFF2196F3).rc)" in source, source)
    assertTrue("RemoteColumn(" in source, source)
    assertTrue("SquircleLargeWidgetPreviewParams::class" in source, source)
  }

  /**
   * A gradient in the background slot becomes the `WearWidgetBrush` chain the container takes.
   *
   * `RemoteContentEmitter` has written this since the slot existed, but nothing could author it:
   * the reviewed `remote-m3` subset carried no component with a `DrawLayer` trait, so the slot was
   * unfillable from the palette and from any document the catalog validator would accept
   * (yschimke/compose-preview-server#428). `shape/linear-gradient` is in that subset now, and this
   * is the export end of it.
   */
  @Test
  fun `a linear gradient in the background slot is written as a brush chain`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val gradient =
      UiBuilderNode(
        id = "sky",
        componentId = "shape/linear-gradient",
        properties =
          JsonObject(
            mapOf(
              "startColor" to literal("color", "#FF2196F3"),
              "endColor" to literal("color", "#FF0D47A1"),
              "direction" to literal("enum", "leftToRight"),
            )
          ),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              gradient.id to gradient,
              scaffold.id to
                scaffold.copy(slots = scaffold.slots + ("background" to listOf(gradient.id))),
            )
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    write("GradientWidget.kt", source)
    // The chain is written across lines rather than on one: a colour plus a two-stop gradient is
    // 111 columns, and this file's whole line-budget promise is that its output survives ktfmt
    // unchanged. The assertion is on the calls in order, not on where the breaks fall.
    assertTrue("WearWidgetBrush.color(Color(0xFF2196F3).rc)" in source, source)
    assertTrue(".horizontalGradient(" in source, source)
    assertTrue("Color(0xFF2196F3).rc, Color(0xFF0D47A1).rc" in source, source)
    assertTrue("import androidx.glance.wear.horizontalGradient" in source, source)
    assertNoLineExceedsBudget(source)
  }

  /**
   * A Lottie element compiles into the document, and the animation lands in a constant.
   *
   * The three assertions are the three halves of the promise (the call, the import, the bytes): the
   * body calls Horologist's `LottieAnimation`, the file imports it from the module
   * `yschimke/rc-players` vendors, and the animation itself is a top-level constant rather than a
   * few thousand columns inside the design.
   */
  @Test
  fun `a lottie element is compiled into the widget's document`() {
    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(
          WearWidgetCodeExporter.export(lottieWidget(LOTTIE_JSON))
        )
        .source

    write("LottieWidget.kt", source)
    assertTrue("LottieAnimation(json = LOTTIE_ANIMATION" in source, source)
    assertTrue(
      "import com.google.android.horologist.remotecompose.lottie.LottieAnimation" in source,
      source,
    )
    assertTrue("private const val LOTTIE_ANIMATION = \"{" in source, source)
    // Reparsed and reprinted: the indentation the author's file carried is most of its bytes, and
    // all of them wasted inside a string literal.
    assertTrue("\\n" !in source, source)
    // Unset progress is what makes the compiled document run the animation off its own clock.
    assertTrue("progress" !in source, source)
  }

  /** A pinned frame is an argument; the absence of one is what makes the animation loop. */
  @Test
  fun `a pinned progress is emitted as a remote float`() {
    val base = lottieWidget(LOTTIE_JSON)
    val lottie = base.nodes.values.first { it.componentId == LOTTIE_COMPONENT_ID }
    val document =
      base.copy(
        nodes =
          base.nodes +
            (lottie.id to
              lottie.copy(
                properties =
                  JsonObject(lottie.properties + ("progress" to literalNumber("number", 0f)))
              ))
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    assertTrue("progress = 0.rf" in source, source)
    assertTrue("import androidx.compose.remote.creation.compose.state.rf" in source, source)
  }

  /**
   * A URL alone is refused, because the generated widget has no network at the moment it needs the
   * animation. The builder resolves a URL into the JSON while the design is open; an element that
   * arrived here without one is unfinished, and saying so beats writing a file that cannot fetch.
   */
  @Test
  fun `a lottie element carrying only a url is refused by name`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(
          lottieWidget(json = "", url = "https://example.test/spin.json")
        )
      )

    assertEquals(1, refused.reasons.size)
    assertTrue(
      "https://example.test/spin.json" in refused.reasons.single(),
      refused.reasons.single(),
    )
  }

  /** Not JSON is caught here rather than by the compiler of whoever pasted the file. */
  @Test
  fun `a lottie element holding something other than json is refused`() {
    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(
        WearWidgetCodeExporter.export(lottieWidget("not an animation"))
      )

    assertTrue("valid JSON" in refused.reasons.single(), refused.reasons.single())
  }

  /** A small, real Lottie: one solid layer, which is enough for the compiler to have something. */
  private val LOTTIE_JSON =
    """
    {
      "v": "5.9.6",
      "fr": 30,
      "ip": 0,
      "op": 30,
      "w": 64,
      "h": 64,
      "layers": [
        { "ty": 1, "ind": 1, "sc": "#2196f3", "sw": 64, "sh": 64, "ip": 0, "op": 30, "st": 0 }
      ]
    }
    """
      .trimIndent()

  private fun lottieWidget(json: String, url: String = ""): UiBuilderDocument {
    val base = helloWidgetUiBuilderDocument("lottie", pin, environment)
    val scaffold =
      base.nodes.values.first { it.componentId.startsWith("remote-m3/widget-container") }
    val lottie =
      UiBuilderNode(
        id = "spinner",
        componentId = LOTTIE_COMPONENT_ID,
        properties =
          JsonObject(
            buildMap {
              if (json.isNotEmpty()) put("json", literal("string", json))
              if (url.isNotEmpty()) put("url", literal("string", url))
            }
          ),
      )
    return base.copy(
      nodes =
        base.nodes +
          mapOf(
            lottie.id to lottie,
            scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to listOf(lottie.id))),
          )
    )
  }

  private fun literalNumber(type: String, value: Float): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun literal(type: String, value: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  /** A screen is the Compose exporter's job, and saying so beats emitting something plausible. */
  @Test
  fun `a design that is not a widget is refused by name`() {
    val blank = blankUiBuilderDocument("screen", pin, environment)

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(blank))

    assertEquals(1, refused.reasons.size)
    assertTrue("layout/scaffold" in refused.reasons.single(), refused.reasons.single())
  }

  /**
   * The Code pane routes a widget design here rather than to the Compose gate.
   *
   * Without the branch a widget shows the gate's refusal — "no component record for remote-m3" —
   * which is true and useless: the design has generated code, just not that generator's.
   */
  @Test
  fun `the editor's code pane generates the widget, not a compose refusal`() {
    val catalog =
      ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
      )
    val reducer = UiBuilderEditorReducer(catalog)

    val generated = reducer.generatedCode(helloWidgetUiBuilderDocument("hello", pin, environment))

    val source = assertIs<EditorGeneratedCode.Source>(generated).kotlin
    assertTrue("class HelloWidget : GlanceWearWidget()" in source, source)
  }

  /**
   * The modifiers a widget is actually drawn with, which used to be refused wholesale.
   *
   * `size`, `background` and `weight` have Remote Compose counterparts — `background` needs its
   * shape as a separate `clip`, since `RemoteModifier.background` takes a colour alone — and
   * `align` does not: `RemoteBoxScope` has no member for it, so it is hoisted onto the parent's
   * `contentAlignment`, which is where RemoteBox states the same thing. Before this, a design as
   * ordinary as a pill-shaped progress bar refused with four reasons and generated nothing
   * (yschimke/compose-preview-server#583 diagnosis).
   */
  @Test
  fun `size, background, weight and a hoisted align are written`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val fill =
      UiBuilderNode(
        id = "fill",
        componentId = "layout/box",
        modifiers =
          JsonArray(
            listOf(
              modifier("size", "widthDp" to JsonPrimitive(71), "heightDp" to JsonPrimitive(3)),
              modifier(
                "background",
                "color" to literal("color", "#FF1DB954"),
                "shape" to JsonPrimitive("999"),
              ),
              modifier("align", "alignment" to JsonPrimitive("centerStart")),
            )
          ),
      )
    val track =
      UiBuilderNode(
        id = "track",
        componentId = "layout/box",
        modifiers = JsonArray(listOf(modifier("weight", "weight" to JsonPrimitive(1.0)))),
        slots = mapOf("children" to listOf(fill.id)),
      )
    val row =
      UiBuilderNode(
        id = "bar",
        componentId = "layout/row",
        slots = mapOf("children" to listOf(track.id)),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              fill.id to fill,
              track.id to track,
              row.id to row,
              scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to listOf(row.id))),
            )
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    write("ModifierWidget.kt", source)
    // `weight` is a row-scope member, written because the box sits inside a RemoteRow.
    assertTrue("RemoteModifier.weight(1f)" in source, source)
    // The shape becomes a `clip` BEFORE the fill, which is what makes the fill take that shape.
    assertTrue("size(71.rdp, 3.rdp)" in source, source)
    assertTrue(".clip(RemoteRoundedCornerShape(999.rdp))" in source, source)
    assertTrue(".background(Color(0xFF1DB954).rc)" in source, source)
    // Hoisted, not written on the child: the child carries no `align` call of its own.
    assertTrue("contentAlignment = RemoteAlignment.CenterStart" in source, source)
    assertTrue(".align(" !in source, source)
    assertTrue("import androidx.compose.remote.creation.compose.modifier.size" in source, source)
    assertTrue(
      "import androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape" in source,
      source,
    )
    assertNoLineExceedsBudget(source)
  }

  /**
   * An `align` outside a box is refused rather than written, because it would not compile.
   *
   * The hoist above is only sound where the parent is the thing that states the alignment. A
   * `RemoteRow` has no `contentAlignment` to hoist onto and `RemoteRowScope` has no `align`, so the
   * only honest answers are a refusal or a file the user's compiler rejects.
   */
  @Test
  fun `an align modifier outside a box is refused`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val child =
      UiBuilderNode(
        id = "child",
        componentId = "layout/box",
        modifiers =
          JsonArray(listOf(modifier("align", "alignment" to JsonPrimitive("centerStart")))),
      )
    val row =
      UiBuilderNode(
        id = "bar",
        componentId = "layout/row",
        slots = mapOf("children" to listOf(child.id)),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              child.id to child,
              row.id to row,
              scaffold.id to scaffold.copy(slots = scaffold.slots + ("content" to listOf(row.id))),
            )
      )

    val refused =
      assertIs<WearWidgetCodeExporter.Result.Refused>(WearWidgetCodeExporter.export(document))
    assertTrue(refused.reasons.any { "align" in it && "RemoteBox" in it }, "${refused.reasons}")
  }

  /**
   * An image names the bitmap the widget has to supply, rather than refusing outright.
   *
   * `RemoteImageBitmap(String)` is the named-bitmap overload, so an asset key IS nameable from
   * generated source — the pixels are supplied under that name in `provideWidgetData`. This holds
   * for a background fill and for an image in the content, which are the same seam.
   */
  @Test
  fun `an image names its bitmap in the content and in the background`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val art =
      UiBuilderNode(
        id = "art",
        componentId = "asset/image",
        properties =
          JsonObject(
            mapOf(
              "assetKey" to literal("string", "cover-wide"),
              "contentScale" to literal("enum", "crop"),
            )
          ),
      )
    val icon =
      UiBuilderNode(
        id = "icon",
        componentId = "asset/image",
        properties =
          JsonObject(
            mapOf(
              "assetKey" to literal("string", "play-icon"),
              "contentDescription" to literal("string", "Play"),
              "contentScale" to literal("enum", "fit"),
            )
          ),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              art.id to art,
              icon.id to icon,
              scaffold.id to
                scaffold.copy(
                  slots =
                    scaffold.slots +
                      ("background" to listOf(art.id)) +
                      ("content" to listOf(icon.id))
                ),
            )
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    write("ImageWidget.kt", source)
    // The template's own background colour heads the chain, so the fill is the link after it.
    assertTrue(
      ".image(RemoteImageBitmap(\"cover-wide\"), ContentScale.Crop)" in source,
      source,
    )
    assertTrue("RemoteImage(" in source, source)
    assertTrue("RemoteImageBitmap(\"play-icon\")" in source, source)
    assertTrue("contentDescription = \"Play\".rs" in source, source)
    assertTrue("contentScale = ContentScale.Fit" in source, source)
    assertTrue("import androidx.compose.ui.layout.ContentScale" in source, source)
    assertNoLineExceedsBudget(source)
  }

  /**
   * A six-digit colour is opaque, not invisible.
   *
   * `Color(0x1DB954)` is Spotify green at **zero alpha** — a widget that draws nothing. A document
   * may write a colour either way, and the canvas reads both, so the generator pads to match it.
   */
  @Test
  fun `a colour without an alpha pair is written opaque`() {
    val base = weatherWidgetUiBuilderDocument("weather", pin, environment)
    val scaffold = base.nodes.values.first { it.componentId.startsWith("remote-m3/") }
    val swatch =
      UiBuilderNode(
        id = "swatch",
        componentId = "layout/box",
        modifiers =
          JsonArray(listOf(modifier("background", "color" to literal("color", "#1DB954")))),
      )
    val document =
      base.copy(
        nodes =
          base.nodes +
            mapOf(
              swatch.id to swatch,
              scaffold.id to
                scaffold.copy(slots = scaffold.slots + ("content" to listOf(swatch.id))),
            )
      )

    val source =
      assertIs<WearWidgetCodeExporter.Result.Emitted>(WearWidgetCodeExporter.export(document))
        .source

    assertTrue("Color(0xFF1DB954)" in source, source)
    assertTrue("Color(0x1DB954)" !in source, source)
  }

  private fun modifier(type: String, vararg fields: Pair<String, JsonElement>): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type)) + fields.toMap())

  /**
   * No line runs past ktfmt's default, which is the promise the generator makes about its output.
   *
   * Asserted rather than assumed because the two places that can break it — a long call and a long
   * modifier chain — wrap by different rules, and a regression in either writes a file whose first
   * `ktfmtFormat` is a diff.
   */
  private fun assertNoLineExceedsBudget(source: String) {
    val long = source.lines().filter { it.length > 100 }
    assertTrue(long.isEmpty(), "lines past 100 columns:\n${long.joinToString("\n")}")
  }

  private fun write(name: String, source: String) {
    val directory = Path.of("build", "generated-widget-source")
    Files.createDirectories(directory)
    Files.writeString(directory.resolve(name), source)
  }
}
