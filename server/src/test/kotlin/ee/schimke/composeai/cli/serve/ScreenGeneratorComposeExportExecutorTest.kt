package ee.schimke.composeai.cli.serve

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
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
 */
class ScreenGeneratorComposeExportExecutorTest {

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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ScheduleOperations() {
    Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer, content = {
        Column(modifier = androidx.compose.ui.Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 16.dp), content = {
            Text(text = "Schedule", color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            Card(modifier = androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.material3.MaterialTheme.shapes.medium), shape = androidx.compose.material3.MaterialTheme.shapes.medium, content = {
                Text(text = "Opening keynote", modifier = androidx.compose.ui.Modifier.width(120.dp), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Text(text = "09:00", color = androidx.compose.ui.graphics.Color(4284960932L))
            })
        })
    })
}
"""
        .trimStart('\n')
  }
}
