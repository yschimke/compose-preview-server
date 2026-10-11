package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.AlignVerticalModifierV1
import ee.schimke.composeai.uibuilder.protocol.AlphaModifierV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.BorderModifierV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ShadowModifierV1
import ee.schimke.composeai.uibuilder.protocol.ShapeTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.VerticalAlignmentV1
import ee.schimke.composeai.uibuilder.protocol.WidthInModifierV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A screen spelled the way the builder spells it, exported through the shipped record. Other
 * goldens use a purpose-built record or parameter names (e.g. `color`) the builder never writes, so
 * nothing else proves the served catalog and shipped record can export an authored document. The
 * first test checks the vocabulary against `m3-catalog-capabilities-v1.json` before exporting, so a
 * drifting document stops counting as evidence. The nodes are the common refusal cases: `m3/text`
 * with `style`, `m3/icon`, `m3/card` with `containerColor`/`elevationDp`, `layout/column` with
 * `verticalSpacingDp`, `m3/surface` with `containerColor`/`shapeDp`, and a `weight` on a `Row`
 * child.
 */
class M3CatalogAuthoredExportTest {

  private val json = Json { ignoreUnknownKeys = true }

  private val record: ComponentRecordFile = ExportRecords.m3Catalog()

  private val catalog =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("m3", "source", "m3-catalog", "candidate", "candidate").build(),
        emptyList(),
      )
      .also {
        it.exportCapabilities =
          ExportCapabilitiesV1.Builder()
            .also {
              it.composeCode = true
              it.svg = false
              it.png = false
            }
            .build()
      }
      .build()

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
          mapOf(
            "text" to StringValueV1("Signed in"),
            "style" to EnumValueV1("bodySmall"),
            // In the `Row`'s `RowScope`, so this is legal here and refuses anywhere else.
            "weight" to DecimalValueV1(1.0),
          ),
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
    // Guards that the document uses only served property names, so the export below proves
    // something authorable.
    val served = servedProperties()
    val undeclared = nodes.flatMap { node ->
      val declared = served[node.componentId] ?: return@flatMap listOf("${node.componentId}: *")
      node.properties.keys.filterNot { it in declared }.map { "${node.componentId}.$it" }
    }
    assertEquals(emptyList(), undeclared, "properties m3-catalog does not declare")
  }

  private fun export(document: DesignDocumentV1) =
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

  @Test
  fun `a builder-authored m3-catalog screen exports as Kotlin`() {
    val artifact = export(document)
    val source = artifact.content
    assertTrue(
      artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      "refused: ${artifact.diagnostics.map { it.message }}\n$source",
    )

    // Each translation asserted where it lands, not as a count that a silent drop would satisfy.
    assertTrue(
      source.contains("color = MaterialTheme.colorScheme.surfaceContainer"),
      source,
    )
    assertTrue(
      source.contains("shape = RoundedCornerShape(12.dp)"),
      source,
    )
    assertTrue(
      source.contains("verticalArrangement = Arrangement.spacedBy(8.dp)"),
      source,
    )
    assertTrue(
      source.contains("style = MaterialTheme.typography.headlineSmall"),
      source,
    )
    assertTrue(
      source.contains(
        "colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)"
      ),
      source,
    )
    assertTrue(
      source.contains("elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)"),
      source,
    )

    // The icon, which is the one value that is an extension property rather than a path: the
    // expression reads as a qualified member and only compiles because the import travels with it.
    assertTrue(
      source.contains("imageVector = Icons.Filled.AccountCircle"),
      source,
    )
    assertTrue(
      source.contains("import androidx.compose.material.icons.filled.AccountCircle"),
      source,
    )
    // `sizeDp` is not a parameter of `Icon` and never was; the catalog declares `size` among the
    // component's modifier capabilities, and this is where it goes.
    assertTrue(source.contains("Modifier.size(24.dp)"), source)
    // The layout weight is a `Row`-receiver modifier written by simple name, with a `Float`
    // argument.
    assertTrue(source.contains("modifier = Modifier.weight(1f)"), source)
    assertTrue(!source.contains("import androidx.compose.foundation.layout.RowScope"), source)
    assertTrue(
      source.contains("tint = MaterialTheme.colorScheme.onSurfaceVariant"),
      source,
    )
  }

  @Test
  fun `the modifiers the catalog offers reach the source as the calls Compose declares`() {
    // The same screen with authored modifier lists, which the property test doesn't cover
    // (modifiers are admitted by type). Each modifier is asserted to be declared for its component.
    val served = servedModifiers()
    for ((component, capability) in
      listOf(
        "layout/row" to "background",
        "layout/row" to "border",
        "m3/card" to "shadow",
        "m3/card" to "widthIn",
        "m3/text" to "alignVertical",
        "m3/text" to "alpha",
      )) {
      assertTrue(capability in served.getValue(component), "$component does not offer $capability")
    }
    val modified =
      nodes
        .map { node ->
          when (node.id) {
            "row" ->
              node.copy(
                modifiers =
                  listOf(
                    BackgroundModifierV1(ColorTokenValueV1("surfaceVariant"), shape = "medium"),
                    BorderModifierV1(JsonPrimitive(1), ColorValueV1("#6750A4")),
                  )
              )
            "card" ->
              node.copy(
                modifiers =
                  listOf(
                    ShadowModifierV1(JsonPrimitive(4), shape = "medium"),
                    WidthInModifierV1(null, JsonPrimitive(320)),
                  )
              )
            // In the `Row`, so the vertical align is the one that resolves — and it lands on the
            // same chain as the `weight` this node already carried as a property.
            "caption" ->
              node.copy(
                modifiers =
                  listOf(
                    AlignVerticalModifierV1(VerticalAlignmentV1.BOTTOM),
                    AlphaModifierV1(JsonPrimitive(0.6)),
                  )
              )
            else -> node
          }
        }
        .associateBy(DesignNodeV1::id)
    val artifact = export(document.copy(nodes = modified))
    val source = artifact.content
    assertTrue(
      artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      "refused: ${artifact.diagnostics.map { it.message }}\n$source",
    )
    // A colour and a theme shape on one call, and a border whose shape is left to Compose's own
    // default rather than to one this projection invented.
    assertTrue(
      source.contains(
        "Modifier.background(color = MaterialTheme.colorScheme.surfaceVariant, " +
          "shape = MaterialTheme.shapes.medium).border(width = 1.dp, color = Color(0xFF6750A4))"
      ),
      source,
    )
    // One bound of two: an omitted `min` is unconstrained, which is what the document said.
    assertTrue(
      source.contains(
        "Modifier.shadow(elevation = 4.dp, shape = MaterialTheme.shapes.medium).widthIn(max = 320.dp)"
      ),
      source,
    )
    // The scoped `align`, by simple name and imported nowhere, beside a `Float` the API takes as
    // one — and the `weight` the property already produced, on the same chain.
    assertTrue(
      source.contains("modifier = Modifier.align(Alignment.Bottom).alpha(0.6f).weight(1f)"),
      source,
    )
    assertTrue(!source.contains("import androidx.compose.foundation.layout.RowScope"), source)
    for (import in
      listOf(
        "import androidx.compose.foundation.background",
        "import androidx.compose.foundation.border",
        "import androidx.compose.ui.draw.shadow",
        "import androidx.compose.foundation.layout.widthIn",
        "import androidx.compose.ui.draw.alpha",
        "import androidx.compose.ui.Alignment",
      )) {
      assertTrue(source.contains(import), "$import missing from\n$source")
    }
  }

  private fun servedModifiers(): Map<String, Set<String>> =
    json
      .parseToJsonElement(File(CAPABILITIES).readText())
      .jsonObject
      .getValue("components")
      .jsonArray
      .associate { component ->
        val declared = component.jsonObject
        declared.getValue("componentId").jsonPrimitive.content to
          (declared["modifierCapabilities"]?.jsonArray ?: emptyList()).mapTo(mutableSetOf()) {
            it.jsonPrimitive.content
          }
      }

  private companion object {
    const val CAPABILITIES = "../docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json"
  }
}
