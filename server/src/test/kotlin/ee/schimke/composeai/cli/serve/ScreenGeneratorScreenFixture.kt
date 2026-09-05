package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ClipModifierV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxSizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.FillMaxWidthModifierV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.PaddingModifierV1
import ee.schimke.composeai.uibuilder.protocol.ShapeTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The test catalog and the small screen built from it — shared by the unit tests and by the task
 * that regenerates the checked-in Compose fixture.
 *
 * Two artefacts on purpose, not one. `components.json` is a **JSON file** because it is the shape a
 * real build emits and reading it here exercises the parse that a served host does; the design
 * document is **Kotlin** because its `UiValueV1` polymorphism has serial names this repository does
 * not otherwise pin, and hand-writing them into a fixture would test my spelling rather than the
 * projection.
 *
 * The screen is deliberately small and deliberately awkward: every value kind the projection can
 * express appears exactly once, and the nesting puts children inside two different slot shapes — a
 * `Surface` slot with no receiver and a `Column`/`Card` slot with a `ColumnScope` receiver.
 *
 * Those two used to produce different output from the same node, and that difference is what this
 * fixture was built around: the generator qualified a component inside a receiver-scoped slot,
 * believing an import could not reach in there. It can — an imported top-level composable resolves
 * inside a receiver-scoped lambda, which compose-ai-tools #5123 established by compiling it — so
 * every component is imported and called by its simple name now, whichever slot it sits in. The
 * nesting is kept: a slot with a receiver is still the case a future regression would break first.
 */
object ScreenGeneratorScreenFixture {

  /** The generated screen, as checked in under `generated/uibuilder`. */
  const val SCREEN_NAME = "ScheduleOperations"

  const val PACKAGE_NAME = "generated.uibuilder"

  fun components(): ComponentRecordFile = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(componentsFile().readText())

  fun componentsFile(): File {
    // Walked up rather than resolved from a property, so the fixture is found whether the test runs
    // from the module directory or the root.
    val relative = "docs/design/fixtures/ui-builder/screen-generator-components-v1.json"
    var directory: File? = File(".").absoluteFile
    while (directory != null) {
      val candidate = File(directory, relative)
      if (candidate.isFile) return candidate
      directory = directory.parentFile
    }
    error("could not find $relative from ${File(".").absolutePath}")
  }

  fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "screen-generator-fixture",
      title = "Schedule operations",
      revision = 7,
      catalogPin =
        CatalogReferenceV1(
          systemId = "test-catalog",
          catalogRevision = "candidate",
          capabilityDigest = "fixture",
          nativeRuntimeId = "candidate",
        ),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("surface"),
      nodes =
        linkedMapOf(
          "surface" to
            DesignNodeV1(
              id = "surface",
              componentId = "m3/surface",
              properties = mapOf("color" to ColorTokenValueV1("surfaceContainer")),
              modifiers = listOf(FillMaxSizeModifierV1),
              slots = mapOf("content" to listOf("column")),
            ),
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              modifiers =
                listOf(
                  PaddingModifierV1(
                    startDp = JsonPrimitive(16),
                    topDp = JsonPrimitive(24),
                    endDp = JsonPrimitive(16),
                    bottomDp = JsonPrimitive(16),
                  )
                ),
              slots = mapOf("content" to listOf("heading", "card")),
            ),
          "heading" to
            DesignNodeV1(
              id = "heading",
              componentId = "m3/text",
              properties =
                mapOf(
                  "text" to StringValueV1("Schedule"),
                  "style" to TypographyTokenValueV1("headlineSmall"),
                  "color" to ColorTokenValueV1("onBackground"),
                ),
            ),
          "card" to
            DesignNodeV1(
              id = "card",
              componentId = "m3/card",
              properties = mapOf("shape" to ShapeTokenValueV1("medium")),
              modifiers = listOf(FillMaxWidthModifierV1, ClipModifierV1(shape = "medium")),
              slots = mapOf("content" to listOf("session", "time")),
            ),
          "session" to
            DesignNodeV1(
              id = "session",
              componentId = "m3/text",
              properties =
                mapOf(
                  "text" to StringValueV1("Opening keynote"),
                  "style" to TypographyTokenValueV1("bodyMedium"),
                ),
              modifiers = listOf(SizeModifierV1(widthDp = JsonPrimitive(120), heightDp = JsonNull)),
            ),
          "time" to
            DesignNodeV1(
              id = "time",
              componentId = "m3/text",
              properties =
                mapOf("text" to StringValueV1("09:00"), "color" to ColorValueV1("#FF6750A4")),
            ),
        ),
    )
}
