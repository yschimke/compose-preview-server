package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
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
 * `MaterialTheme` reads, the qualified `Color(…)` call, the imported `Modifier` extensions and the
 * fully-qualified calls inside the two receiver-scoped slots. Turning that into a standing CI gate
 * (a checked-in fixture a Compose module compiles) is a follow-up; what is asserted here is that
 * the generator still produces exactly the text that was compiled.
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
    components: (String) -> ComponentRecordFile? = { ScreenGeneratorScreenFixture.components() },
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
    val artifact = export(components = { null })
    val diagnostic = artifact.diagnostics.single()
    assertEquals(DiagnosticSeverityV1.ERROR, diagnostic.severity)
    assertEquals(ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD, diagnostic.code)
    assertTrue(diagnostic.message.contains("catalog `test-catalog`"), diagnostic.message)
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
package generated.uibuilder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun ScheduleOperations() {
    Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer, content = {
        Column(modifier = androidx.compose.ui.Modifier.padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 16.dp), content = {
            androidx.compose.material3.Text(text = "Schedule", color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground, style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
            androidx.compose.material3.Card(modifier = androidx.compose.ui.Modifier.fillMaxWidth().clip(androidx.compose.material3.MaterialTheme.shapes.medium), shape = androidx.compose.material3.MaterialTheme.shapes.medium, content = {
                androidx.compose.material3.Text(text = "Opening keynote", modifier = androidx.compose.ui.Modifier.width(120.dp), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                androidx.compose.material3.Text(text = "09:00", color = androidx.compose.ui.graphics.Color(4284960932L))
            })
        })
    })
}
"""
        .trimStart('\n')
  }
}
