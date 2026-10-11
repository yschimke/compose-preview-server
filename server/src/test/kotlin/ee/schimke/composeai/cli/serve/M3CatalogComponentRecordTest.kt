package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.AssetKeyValueV1
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
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
 * The authored join between the capability catalog and the component record.
 *
 * `ScreenGeneratorComposeExportExecutor` needs a `ComponentRecordFile` for the catalog it exports;
 * `m3-catalog-components-v1.json` is that record. It is authored rather than generated because the
 * capability catalog lacks what the generator needs: `jsonType` instead of a Kotlin `typeFqn`, one
 * `code.symbol` for ids that pick a different callable by property (`m3/card` is `Card`,
 * `ElevatedCard` or `OutlinedCard` by `variant`), and properties that aren't parameters
 * (`scrollStateKey`, `stableKey`, `themeTypeScale`).
 *
 * Components without an unambiguous Compose mapping are absent rather than guessed: the generator
 * refuses unknown ids and undeclared properties by name, which is safe, whereas a wrong record
 * yields Kotlin that doesn't compile. [uncovered] lists them explicitly.
 *
 * `ElevatedCard` and `OutlinedCard` have empty `componentIds`: the catalog spells all three cards
 * `m3/card` and selects by `variant`, so `ScreenDocumentProjection` reaches them by canonical id.
 * Coverage counts catalog ids, so the tests still balance.
 *
 * `m3/icon`'s `code.call` is authored with `TODO()` for the `ImageVector` (discovery refuses
 * because the placeholder table lacks one); `ScreenGenerator` treats `code.call` only as a licence
 * and fills arguments from the document, which always supplies the vector via
 * `ScreenDocumentProjection.ICON_MEMBERS`.
 *
 * The lazy containers (`layout/lazy-column`, `-row`, `-grid`) use
 * `TargetParameter.scopeDslReceiver` on `content` (making the slot fillable) and
 * `ScreenNode.slotItems` from `ScreenDocumentProjection.SLOT_ITEMS` (wrapping each child in `item {
 * }`); the generator checks one against the other. `LazyVerticalGrid`'s call needs `TODO()` for
 * `columns` (no `GridCells` placeholder); the projection supplies `GridCells.Adaptive(…)`.
 */
class M3CatalogComponentRecordTest {

  private val json = Json { ignoreUnknownKeys = true }

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

  private val record: ComponentRecordFile = ExportRecords.m3Catalog()

  /** Capability ids the record deliberately does not cover yet, each with the reason. */
  private val uncovered =
    mapOf(
      // Not a scope DSL: the content is a trailing composable slot taking an item index, so
      // `slotItems` doesn't reach it. Both blockers are lambdas: `rememberCarouselState { n }` and
      // the per-index slot.
      "layout/horizontal-carousel" to
        "takes a CarouselState from rememberCarouselState { n }, whose argument is a lambda, and its content slot is called per item index rather than per child (compose-ai-tools#5218)",
      // Not a component: a loop over the design's rows becomes a `forEach` the exporter writes, not
      // a symbol discovery could find.
      "layout/for-each" to
        "a loop over the design's rows, generated as a forEach around its template rather than as a call to any component",
      // Not the factory (`rememberDatePickerState` has a `$default` bridge): `selectedDate` is an
      // ISO-8601 string while the factory takes `initialSelectedDateMillis: Long?`, a conversion
      // this projection refuses to guess.
      "m3/date-picker" to
        "selectedDate is an ISO-8601 YYYY-MM-DD string and rememberDatePickerState takes " +
          "initialSelectedDateMillis: Long?; converting between them is a computation this " +
          "projection will not invent",
      "m3/dialog" to
        "AlertDialog is a window and needs an onDismissRequest a design cannot write; the builder draws and emits its surface inline instead",
      "m3/snackbar-host" to "takes a SnackbarHostState, which no ScreenValue expresses",
      // Nothing about the component blocks it (`hour`, `minute`, `is24Hour` map onto
      // `rememberTimePickerState`, and `mode` picks `TimePicker` / `TimeInput` like `m3/card`'s
      // variant), but the record carries neither callable yet, so a variant entry would resolve to
      // nothing.
      "m3/time-picker" to
        "the record carries neither TimePicker nor TimeInput, so no variant entry could name a " +
          "callable that resolves",
      // The call is to a declaration flexpress generates at export, which the projection records
      // itself (`VariableFontTextRecord`).
      "m3/variable-font-text" to
        "calls a declaration flexpress generates at export; the projection records it, no catalog record could name it",
      "remote-compose/document" to "typed embed, kept out of the Compose exporter by design",
      "remote-compose/inline" to
        "the vocabulary switch: its subtree is @RemoteComposable and InlineRemoteContentExporter writes it, not the Compose exporter",
      "remote-compose/custom" to
        "a Remote Compose custom operation naming a host renderer; no published creation API writes one, so no record could back it",
      "shape/radial-gradient" to "a Modifier, not a component",
    )

  // Use the vocabulary packaged with the builder code, including composite checkout changes.
  private fun capabilityIds(): Set<String> =
    json
      .parseToJsonElement(
        checkNotNull(
            CurrentM3UiBuilderCatalogExecutor::class
              .java
              .getResourceAsStream("/ee/schimke/composeai/uibuilder/catalogs/m3-catalog-v1.json")
          )
          .bufferedReader()
          .use { it.readText() }
      )
      .jsonObject
      .getValue("components")
      .jsonArray
      .map { it.jsonObject.getValue("componentId").jsonPrimitive.content }
      .toSet()

  @Test
  fun `every record answers to a capability id the catalog declares`() {
    val declared = capabilityIds()
    val claimed = record.components.flatMap { it.componentIds }
    // A typo here is the worst failure this file can have: the record would look complete and the
    // export would refuse the one id nobody thought to try.
    claimed.forEach {
      assertTrue(it in declared, "record claims `$it`, which the catalog does not")
    }
    assertEquals(claimed.size, claimed.toSet().size, "two records claim one capability id")
  }

  @Test
  fun `covered plus uncovered accounts for the whole catalog`() {
    val declared = capabilityIds()
    val covered = record.components.flatMap { it.componentIds }.toSet()
    assertEquals(
      emptySet(),
      declared - covered - uncovered.keys,
      "capability ids that are neither covered nor listed as uncovered — add a record or a reason",
    )
    assertEquals(
      emptySet(),
      uncovered.keys - declared,
      "uncovered names an id the catalog no longer declares",
    )
    assertEquals(emptySet(), covered intersect uncovered.keys, "an id is both covered and not")
  }

  /**
   * The authored record and real discovery must name the same JVM facade for shared symbols. A
   * `canonicalId` is `<module>/<jvmOwner>.<name>`, so the facade is the record's identity: aliases
   * and the projection's variant table derive from it. Hand-authored entries once named facades
   * that don't exist (e.g. `HorizontalDividerKt` for `DividerKt`) unnoticed, since calls use
   * `symbol.callable`; `m3-catalog-generated-record-v1.json` comes from discovery reading class
   * files, so it is the check.
   */
  @Test
  fun `the authored record names the facade discovery found`() {
    val discovered =
      json
        .decodeFromString<ComponentRecordFile>(
          File("../docs/design/fixtures/ui-builder/m3-catalog-generated-record-v1.json").readText()
        )
        .components
        .associateBy { it.symbol.name }
    val disagree =
      record.components
        .mapNotNull { authored ->
          val found = discovered[authored.symbol.name] ?: return@mapNotNull null
          if (authored.symbol.jvmOwner == found.symbol.jvmOwner) null
          else
            "${authored.symbol.name}: authored ${authored.symbol.jvmOwner}, discovered ${found.symbol.jvmOwner}"
        }
        .sorted()
    assertEquals(
      emptyList(),
      disagree,
      "the authored record names a JVM facade discovery did not find on the classpath",
    )
  }

  @Test
  fun `every covered record can print a call site`() {
    // `code.call` is the generator's licence to call at all: a record without one refuses, so a
    // record that claims coverage and cannot print is worse than one that never claimed it.
    record.components.forEach { component ->
      assertTrue(
        component.code?.call != null,
        "${component.componentIds} has no call site: ${component.code?.refusedReason}",
      )
      assertTrue(component.signatureKnown, "${component.componentIds} has an unread signature")
    }
  }

  @Test
  fun `a screen built from covered ids generates against this record`() {
    // End to end: a design using the authored ids becomes Kotlin through `ScreenDocumentProjection`
    // and the real `ScreenGenerator`. `layout/column` is filled through its catalog slot name
    // (`children`), the case the slot mapping exists for.
    val document =
      ScreenGeneratorScreenFixture.document()
        .copy(
          roots = listOf("surface"),
          nodes =
            linkedMapOf(
              "surface" to
                DesignNodeV1(
                  id = "surface",
                  componentId = "m3/surface",
                  properties = mapOf("color" to ColorTokenValueV1("surfaceContainer")),
                  slots = mapOf("content" to listOf("column")),
                ),
              "column" to
                DesignNodeV1(
                  id = "column",
                  componentId = "layout/column",
                  slots = mapOf("children" to listOf("heading", "divider", "photo", "agree")),
                ),
              "heading" to
                DesignNodeV1(
                  id = "heading",
                  componentId = "m3/text",
                  properties =
                    mapOf(
                      "text" to StringValueV1("Discover"),
                      "style" to TypographyTokenValueV1("headlineSmall"),
                    ),
                ),
              "divider" to DesignNodeV1(id = "divider", componentId = "m3/horizontal-divider"),
              // A picture: its bytes live in the design's asset store, so the record lane writes
              // `Image(...)` with a placeholder painter and a warning.
              "photo" to
                DesignNodeV1(
                  id = "photo",
                  componentId = "asset/image",
                  properties =
                    mapOf(
                      "assetKey" to AssetKeyValueV1("avatar-lain"),
                      "contentDescription" to StringValueV1("lain"),
                      "contentScale" to EnumValueV1("crop"),
                    ),
                ),
              // A selection control: its required `onCheckedChange` is nullable (written as `null`)
              // and `checked` is a boolean from the document.
              "agree" to
                DesignNodeV1(
                  id = "agree",
                  componentId = "m3/checkbox",
                  properties = mapOf("checked" to BooleanValueV1(true)),
                ),
            ),
        )

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
    assertTrue(source.contains("Surface("), source)
    // The alias did its work: the catalog's `children` reached `Column`'s `content` parameter.
    assertTrue(source.contains("Column {"), source)
    assertTrue(source.contains("""Text(text = "Discover""""), source)
    assertTrue(source.contains("HorizontalDivider("), source)
    assertTrue(source.contains("Image("), source)
    assertTrue(source.contains("ColorPainter("), source)
    assertTrue(source.contains("ContentScale.Crop"), source)
    assertTrue(source.contains("contentDescription = \"lain\""), source)
    assertTrue(source.contains("Asset placeholder: node photo draws asset 'avatar-lain'"), source)
    val placeholder =
      artifact.diagnostics.single {
        it.code == ScreenGeneratorComposeExportExecutor.ASSET_PLACEHOLDER
      }
    assertEquals(DiagnosticSeverityV1.WARNING, placeholder.severity)
    assertTrue("avatar-lain" in placeholder.message, placeholder.message)
    assertTrue(source.contains("Checkbox("), source)
    assertTrue(source.contains("checked = true"), source)
    assertTrue(!source.contains("children ="), source)
  }
}
