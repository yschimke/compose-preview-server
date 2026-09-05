package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ShapeTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A screen spelled the way the **builder** spells one, exported through the **shipped** record.
 *
 * ## The gap this closes
 *
 * `exportDesign format=compose` produced zero lines of Kotlin for every `m3-catalog` design a
 * person could author in the UI builder — 119 refusals on a 387-node design, and nothing on a
 * two-node cut of it (issue #332). Two golden tests were green throughout, and both were honest
 * about their own scope rather than wrong:
 *
 * - `ScreenGeneratorScreenFixture` states that "every value kind the projection can express appears
 *   exactly once", so it holds no enum value at all, and it runs against a purpose-built test
 *   record rather than the one the deployment installs.
 * - `M3CatalogComponentRecordTest`'s end-to-end does use the shipped record — and authors
 *   `"color"`, which is `Surface`'s **parameter** name. No builder writes that; the inspector
 *   writes `containerColor`, and that is the write that refused.
 *
 * So nothing anywhere proved that the catalog this server *serves* and the record it *ships* can
 * turn a document somebody *authored* into Kotlin. This does, and it is written to fail the moment
 * they drift apart again: [`every property this screen sets is one the served catalog declares`]
 * checks the vocabulary against `m3-catalog-capabilities-v1.json` before the export is even run, so
 * a document that quietly starts spelling properties the way the record wants stops counting as
 * evidence.
 *
 * ## Why the screen is shaped like this
 *
 * Every node here is one the refusal list named: an `m3/text` carrying a `style` (51 of the 119
 * refusals), an `m3/icon` (29, plus an unproven call site behind them), an `m3/card` with a
 * `containerColor` and an `elevationDp`, a `layout/column` with a `verticalSpacingDp`, and an
 * `m3/surface` with a `containerColor` and a `shapeDp`. None of them is exotic; together they are
 * roughly what a screen is.
 */
class M3CatalogAuthoredExportTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val record: ComponentRecordFile = json.decodeFromString(File(RECORD).readText())

  private val catalog =
    CatalogCapabilityV1(
      schema = "compose-catalog-capabilities/v1",
      benchmark = CatalogBenchmarkV1("m3", "source", "m3-catalog", "candidate", "candidate"),
      components = emptyList(),
      exportCapabilities = ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
    )

  private val nodes =
    listOf(
      DesignNodeV1(
        id = "surface",
        componentId = "m3/surface",
        properties =
          mapOf(
            "containerColor" to ColorTokenValueV1("surfaceContainer"),
            "shapeDp" to DecimalValueV1(12.0),
          ),
        slots = mapOf("content" to listOf("column")),
      ),
      DesignNodeV1(
        id = "column",
        componentId = "layout/column",
        properties =
          mapOf(
            "verticalSpacingDp" to DecimalValueV1(8.0),
            "horizontalAlignment" to EnumValueV1("start"),
          ),
        slots = mapOf("children" to listOf("heading", "row", "card", "divider")),
      ),
      DesignNodeV1(
        id = "heading",
        componentId = "m3/text",
        properties =
          mapOf(
            "text" to StringValueV1("Discover"),
            "style" to EnumValueV1("headlineSmall"),
            "fontWeight" to EnumValueV1("semiBold"),
          ),
      ),
      DesignNodeV1(
        id = "row",
        componentId = "layout/row",
        properties =
          mapOf(
            "horizontalSpacingDp" to DecimalValueV1(4.0),
            "verticalAlignment" to EnumValueV1("center"),
          ),
        slots = mapOf("children" to listOf("icon", "caption")),
      ),
      DesignNodeV1(
        id = "icon",
        componentId = "m3/icon",
        properties =
          mapOf(
            "iconKey" to EnumValueV1("accountCircle"),
            "contentDescription" to StringValueV1("Account"),
            "color" to ColorTokenValueV1("onSurfaceVariant"),
            "sizeDp" to DecimalValueV1(24.0),
          ),
      ),
      DesignNodeV1(
        id = "caption",
        componentId = "m3/text",
        properties =
          mapOf("text" to StringValueV1("Signed in"), "style" to EnumValueV1("bodySmall")),
      ),
      DesignNodeV1(
        id = "card",
        componentId = "m3/card",
        properties =
          mapOf(
            "containerColor" to ColorTokenValueV1("surfaceVariant"),
            "elevationDp" to DecimalValueV1(2.0),
            "shape" to ShapeTokenValueV1("medium"),
          ),
        slots = mapOf("content" to listOf("body")),
      ),
      DesignNodeV1(
        id = "body",
        componentId = "m3/text",
        properties =
          mapOf("text" to StringValueV1("Latest episode"), "style" to EnumValueV1("bodyLarge")),
      ),
      DesignNodeV1(id = "divider", componentId = "m3/horizontal-divider"),
    )

  private val document =
    ScreenGeneratorScreenFixture.document()
      .copy(roots = listOf("surface"), nodes = nodes.associateBy(DesignNodeV1::id))

  private fun servedProperties(): Map<String, Set<String>> =
    json
      .parseToJsonElement(File(CAPABILITIES).readText())
      .jsonObject
      .getValue("components")
      .jsonArray
      .associate { component ->
        val declared = component.jsonObject
        declared.getValue("componentId").jsonPrimitive.content to
          (declared["properties"]?.jsonArray ?: return@associate "" to emptySet()).mapTo(
            mutableSetOf()
          ) {
            it.jsonObject.getValue("name").jsonPrimitive.content
          }
      }

  @Test
  fun `every property this screen sets is one the served catalog declares`() {
    // The guard that makes the export below evidence rather than a restatement. A test document
    // that drifts towards the record's parameter names would keep passing the export and would
    // stop covering anything a person can author, which is precisely how the shipped catalog and
    // the shipped record diverged without a red build.
    val served = servedProperties()
    val undeclared = nodes.flatMap { node ->
      val declared = served[node.componentId] ?: return@flatMap listOf("${node.componentId}: *")
      node.properties.keys.filterNot { it in declared }.map { "${node.componentId}.$it" }
    }
    assertEquals(emptyList(), undeclared, "properties m3-catalog does not declare")
  }

  @Test
  fun `a builder-authored m3-catalog screen exports as Kotlin`() {
    val artifact =
      ScreenGeneratorComposeExportExecutor(
          { ComponentRecordSource.Lookup.Found(record) },
          ScreenGeneratorScreenFixture.PACKAGE_NAME,
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
    val source = artifact.content
    assertTrue(
      artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      "refused: ${artifact.diagnostics.map { it.message }}\n$source",
    )

    // The five translations that were each a refusal of their own, asserted where they land rather
    // than as a count: a count goes green for the wrong reason the first time one of them is
    // silently dropped instead of refused.
    assertTrue(
      source.contains(
        "color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer"
      ),
      source,
    )
    assertTrue(
      source.contains("shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)"),
      source,
    )
    assertTrue(
      source.contains(
        "verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)"
      ),
      source,
    )
    assertTrue(
      source.contains("style = androidx.compose.material3.MaterialTheme.typography.headlineSmall"),
      source,
    )
    assertTrue(
      source.contains(
        "colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = " +
          "androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)"
      ),
      source,
    )
    assertTrue(
      source.contains(
        "elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 2.dp)"
      ),
      source,
    )

    // The icon, which is the one value that is an extension property rather than a path: the
    // expression reads as a qualified member and only compiles because the import travels with it.
    assertTrue(
      source.contains("imageVector = androidx.compose.material.icons.Icons.Filled.AccountCircle"),
      source,
    )
    assertTrue(
      source.contains("import androidx.compose.material.icons.filled.AccountCircle"),
      source,
    )
    // `sizeDp` is not a parameter of `Icon` and never was; the catalog declares `size` among the
    // component's modifier capabilities, and this is where it goes.
    assertTrue(source.contains("Modifier.size(24.dp)"), source)
    assertTrue(
      source.contains(
        "tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant"
      ),
      source,
    )
  }

  private companion object {
    const val RECORD = "../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"
    const val CAPABILITIES = "../docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json"
  }
}
