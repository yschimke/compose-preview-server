package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The export a served host actually returns, asserted as text.
 *
 * A golden rather than a set of `contains` checks, and the reason is the thing being replaced. The
 * old executor's artifact could not be asserted as text usefully — it always came back with a
 * `WARNING ALMOST_COMPILING_PROJECTION` saying edits "may" be required, so no assertion could
 * distinguish an export that worked from one that did not. The whole claim of this change is that
 * the output is exact and complete, and a golden is what that claim looks like as a test.
 *
 * **The golden below was compiled.** It was written into a Compose source set with material3,
 * foundation and ui on the classpath and `compileKotlinJvm` accepted it — the qualified
 * `MaterialTheme` reads, the qualified `Color(…)` call, and the imported `Modifier` extensions.
 * Turning that into a standing CI gate (a checked-in fixture a Compose module compiles) is a
 * follow-up; what is asserted here is that the generator still produces exactly the text that was
 * compiled.
 *
 * It was regenerated once, when `preview-discovery` reached the version that stopped qualifying a
 * component inside a receiver-scoped slot: the generator had assumed an import could not reach into
 * one, and compose-ai-tools #5123 established by compiling it that an imported top-level composable
 * resolves there perfectly well. Every component is imported and called by its simple name now.
 * That was a real improvement arriving as a red golden, which is what a golden is for — but note
 * the shape of it, because this repository pins that dependency and the next such change lands the
 * same way.
 *
 * And once more when a card's content gained the `Box` this catalog says it is
 * (`ScreenDocumentProjection.cardContentBox`): the two texts inside the card now sit in a
 * `Box(modifier = Modifier.fillMaxWidth())` rather than straight under `Card`'s `ColumnScope`,
 * which is what the canvas and the capability exporter had drawn all along. `Box` is a plain
 * foundation call site the same classpath resolves; the change is structural, not lexical.
 */
class ScreenGeneratorComposeExportExecutorTest {

  @Test
  fun `semantic loops and reusable components have identical browser and service source`() {
    // Opt-in build surface, so this case belongs to the `ui-builder-remote-compose` job rather
    // than to the shipping default. A semantic loop is a `layout/for-each`, and
    // `ScreenDocumentProjection` refuses any document carrying one when the feature is off
    // ("stated authoring and reusable source export are disabled in this build") — so the export
    // comes back with a diagnostic and `assertEquals(emptyList(), artifact.diagnostics)` below
    // fails on the refusal rather than on anything about browser/service agreement.
    //
    // The fifth instance of the same shape as #792, which guarded four siblings and missed this
    // one: it fixed `:ui-builder:jvmTest` for the default build and `:server:test` for the flagged
    // build, and this is `:server:test` in the DEFAULT build. Guarded per test rather than per
    // class because the other eleven cases here do not need the feature.
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
    val root =
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json").isFile }
    val document =
      Json.decodeFromString<DesignDocumentV1>(
        File(
            root,
            "docs/design/evidence/ui-builder-repetition-export/repetition-initial.document.json",
          )
          .readText()
      )
    val record =
      Json.decodeFromString<ComponentRecordFile>(
        File(
            root,
            "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json",
          )
          .readText()
      )
    val browser = ScreenExportGate.export(document, record)
    val scopedGeneratorAvailable = runCatching {
      Json.decodeFromString<ee.schimke.composeai.discovery.ScreenDocument>(
        """{"name":"Probe","functions":[],"root":{"componentId":"","repetition":{"fields":{},"rows":[]}}}"""
      )
    }
      .isSuccess
    if (!scopedGeneratorAvailable) {
      assertTrue(
        browser is ScreenExportGate.Outcome.Refused &&
          browser.reasons.any { "shared generator support" in it }
      )
      return
    }
    val artifact =
      ScreenGeneratorComposeExportExecutor(
          { ComponentRecordSource.Lookup.Found(record) },
          ScreenExportGate.PACKAGE_NAME,
        )
        .export(
          RevisionPinnedUiBuilderExport(
            actor = AuthenticatedUiBuilderActor("tester"),
            designId = document.id,
            revision = document.revision,
            documentHash = "hash",
            document = document,
            catalog = catalog,
            format = ExportFormatV1.COMPOSE,
          )
        )
    assertEquals(emptyList(), artifact.diagnostics)
    val provenance =
      "// Generated by compose-preview serve from a UI-builder design.\n" +
        "// Design ${document.id} revision ${document.revision}\n" +
        "// Document SHA-256: hash\n" +
        "// Catalog ${document.catalogPin.systemId}@${document.catalogPin.catalogRevision}; capability ${document.catalogPin.capabilityDigest}\n\n"
    assertEquals(
      provenance + (browser as ScreenExportGate.Outcome.Emitted).source,
      artifact.content,
    )
    assertTrue(artifact.content.orEmpty().contains(".forEach"))
    assertTrue(artifact.content.orEmpty().contains("private fun Pair("))
  }

  private val catalog =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("test", "source", "test-catalog", "candidate", "candidate"),
      components = emptyList(),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
    )

  private fun export(
    document: DesignDocumentV1 = ScreenGeneratorScreenFixture.document(),
    components: (String) -> ComponentRecordSource.Lookup = {
      ComponentRecordSource.Lookup.Found(ScreenGeneratorScreenFixture.components())
    },
  ) =
    ScreenGeneratorComposeExportExecutor(components, ScreenGeneratorScreenFixture.PACKAGE_NAME)
      .export(
        RevisionPinnedUiBuilderExport(
          actor = AuthenticatedUiBuilderActor("tester"),
          designId = document.id,
          revision = document.revision,
          documentHash = "hash",
          document = document,
          catalog = catalog,
          format = ExportFormatV1.COMPOSE,
        )
      )

  @Test
  fun `a design document exports as the complete screen, with no diagnostic at all`() {
    val artifact = export()
    assertEquals(EXPECTED_SOURCE, artifact.content)
    assertEquals(emptyList(), artifact.diagnostics)
    assertEquals(ExportFormatV1.COMPOSE, artifact.format)
    assertEquals("text/x-kotlin; charset=utf-8", artifact.mediaType)
    assertEquals(ExportEncodingV1.UTF8, artifact.encoding)
  }

  @Test
  fun `the same document exports byte-identically twice`() {
    assertEquals(export().contentDigest, export().contentDigest)
  }

  @Test
  fun `a host with no component record says so, rather than refusing every node`() {
    val artifact = export(components = { ComponentRecordSource.Lookup.Unconfigured })
    val diagnostic = artifact.diagnostics.single()
    assertEquals(DiagnosticSeverityV1.ERROR, diagnostic.severity)
    assertEquals(ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD, diagnostic.code)
    assertTrue(diagnostic.message.contains("catalog `test-catalog`"), diagnostic.message)
  }

  @Test
  fun `a configured record that will not load names the path, not the flag`() {
    // The distinction the previous message lost: an operator who never passed
    // `--ui-builder-components` needs to be told to; one who passed a path with a typo in it needs
    // the path. Both used to get the first sentence.
    val artifact =
      export(components = { ComponentRecordSource.Lookup.Unusable("no readable file at `/nope`") })
    val message = artifact.diagnostics.single().message
    assertTrue(message.contains("/nope"), message)
    assertFalse(message.contains("--ui-builder-components"), message)
  }

  @Test
  fun `an unexpressible value is an error naming the node, not a warning about the file`() {
    val document = ScreenGeneratorScreenFixture.document()
    val artifact =
      export(
        document.copy(
          roots = listOf("text"),
          nodes =
            mapOf(
              "text" to
                DesignNodeV1(
                  id = "text",
                  componentId = "m3/text",
                  properties = mapOf("text" to StateValueV1("query")),
                )
            ),
        )
      )
    val diagnostic = artifact.diagnostics.single()
    assertEquals(DiagnosticSeverityV1.ERROR, diagnostic.severity)
    assertEquals(ScreenGeneratorComposeExportExecutor.UNEXPRESSIBLE_DOCUMENT, diagnostic.code)
    assertTrue(diagnostic.message.contains("state variable `query`"), diagnostic.message)
    // The refusal is also the content, so a caller that only renders the body sees the reason.
    assertTrue(artifact.content.startsWith("// node `text`.`text` reads"), artifact.content)
  }

  @Test
  fun `a record on an unreadable schema refuses per export, naming the version`() {
    // The advertised capability is a configuration fact and cannot know what is on disk now, so
    // the version question is asked here — where a repaired or replaced file is seen on the very
    // next request rather than never.
    val artifact =
      export(
        components = {
          ComponentRecordSource.Lookup.Found(
            ScreenGeneratorScreenFixture.components().copy(schemaVersion = 99)
          )
        }
      )
    val diagnostic = artifact.diagnostics.single()
    assertEquals(ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD, diagnostic.code)
    assertTrue(diagnostic.message.contains("is schema 99"), diagnostic.message)
  }

  @Test
  fun `a node the catalog cannot place is a separate code from one it cannot express`() {
    val document = ScreenGeneratorScreenFixture.document()
    val artifact =
      export(
        document.copy(
          roots = listOf("text"),
          nodes =
            mapOf(
              "text" to
                DesignNodeV1(
                  id = "text",
                  componentId = "m3/marquee",
                  properties = mapOf("text" to StringValueV1("hi")),
                )
            ),
        )
      )
    val diagnostic = artifact.diagnostics.single()
    assertEquals(ScreenGeneratorComposeExportExecutor.UNPROVEN_CALL_SITE, diagnostic.code)
    assertEquals("no component `m3/marquee` in this catalog", diagnostic.message)
  }

  @Test
  fun `the default package is the one the preview adapter imports from`() {
    // `UiBuilderGeneratedPreviewAdapter` writes `import generated.uibuilder.$composableName`, and
    // the exporter this replaces emitted the same package. A different default compiles on its own
    // and fails the moment a production artifact reaches that lane — and the golden above would not
    // catch it, because it passes a package explicitly.
    val document = ScreenGeneratorScreenFixture.document()
    val artifact =
      ScreenGeneratorComposeExportExecutor({
          ComponentRecordSource.Lookup.Found(ScreenGeneratorScreenFixture.components())
        })
        .export(
          RevisionPinnedUiBuilderExport(
            actor = AuthenticatedUiBuilderActor("tester"),
            designId = document.id,
            revision = document.revision,
            documentHash = "hash",
            document = document,
            catalog = catalog,
            format = ExportFormatV1.COMPOSE,
          )
        )
    assertTrue(artifact.content.contains("\npackage generated.uibuilder\n"), artifact.content)
    assertTrue(
      UiBuilderGeneratedPreviewAdapter.previewEntry("Screen", 1, 1)
        .contains("import generated.uibuilder.Screen")
    )
  }

  @Test
  fun `a refusal keeps every physical line commented, whatever the document put in it`() {
    // A refusal quotes document-supplied text, and catalog validation admits arbitrary strings in
    // a typed colour property. A newline there used to leave everything after it uncommented in an
    // artifact this executor calls a harmless parseable refusal.
    val document = ScreenGeneratorScreenFixture.document()
    val artifact =
      export(
        document.copy(
          roots = listOf("text"),
          nodes =
            mapOf(
              "text" to
                DesignNodeV1(
                  id = "text",
                  componentId = "m3/text",
                  properties =
                    mapOf(
                      "text" to StringValueV1("hi"),
                      "color" to ColorValueV1("bad\nval injected = 1\u2028val also = 2"),
                    ),
                )
            ),
        )
      )
    val lines = artifact.content.trimEnd('\n').split("\n")
    assertTrue(lines.size >= 3, artifact.content)
    assertTrue(lines.all { it.startsWith("// ") }, artifact.content)
  }

  private companion object {
    /**
     * Long lines, deliberately unformatted.
     *
     * The generator prints one call per line and does not wrap, because a formatter is a better
     * formatter than a code generator: ktfmt reads the whole file and knows where a break helps,
     * and a generator guessing at line breaks produces output a formatter then rewrites — two
     * layouts for one file, and a golden that drifts against whichever ran last.
     */
    val EXPECTED_SOURCE =
      """
// Generated by compose-preview serve from a UI-builder design.
// Design screen-generator-fixture revision 7
// Document SHA-256: hash
// Catalog test-catalog@candidate; capability fixture

package generated.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun ScheduleOperations() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer, content = {
        Column(modifier = Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 16.dp), content = {
            Text(text = "Schedule", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
            Card(modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium), shape = MaterialTheme.shapes.medium, content = {
                Box(modifier = Modifier.fillMaxWidth(), content = {
                    Text(text = "Opening keynote", modifier = Modifier.width(120.dp), style = MaterialTheme.typography.bodyMedium)
                    Text(text = "09:00", color = Color(4284960932L))
                })
            })
        })
    })
}
"""
        .trimStart('\n')
  }

  /**
   * A design that named devices gets one `@Preview(device = …)` per id, and its own frame beside
   * them.
   *
   * The frame comes along deliberately: the design's own size is the canvas its author approved,
   * and a file that draws a screen on a Pixel Fold but not at the size it was designed at has
   * dropped the one picture that was signed off.
   */
  @Test
  fun `a named device set becomes one Preview each, beside the design's own frame`() {
    val base = ScreenGeneratorScreenFixture.document()
    val document =
      base.copy(
        environment =
          base.environment.copy(
            theme = ThemeV1.DARK,
            exportDevices = listOf("id:pixel_6", "id:pixel_fold"),
          )
      )

    val source = export(document).content

    assertTrue("""device = "id:pixel_6"""" in source, source)
    assertTrue("""device = "id:pixel_fold"""" in source, source)
    // The frame the design was drawn at, on its own wrapper.
    assertTrue("widthDp = 400," in source, source)
    assertTrue("heightDp = 800," in source, source)
    assertTrue("UI_MODE_NIGHT_YES" in source, source)
    // …and not on the device wrappers, which supply their own geometry.
    val fanOut = source.substringAfter("""device = "id:pixel_6"""")
    assertFalse("widthDp" in fanOut, fanOut)
  }

  /**
   * A design that named none is untouched, which is what the golden above already pins and what
   * makes this change contained: a `@Preview` is a claim about how a screen should be looked at,
   * and turning it on for every export would put one into files whose authors never asked.
   */
  @Test
  fun `a design naming no devices still carries no preview at all`() {
    val source = export().content

    assertFalse("@Preview" in source, source)
  }
}
