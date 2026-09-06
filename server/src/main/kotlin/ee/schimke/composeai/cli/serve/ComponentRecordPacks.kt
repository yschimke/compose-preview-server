package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.UiBuilderComponentPack
import ee.schimke.composeai.uibuilder.protocol.CodeCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityStatusV1
import ee.schimke.composeai.uibuilder.protocol.SvgCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SvgFallbackV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import ee.schimke.composeai.uibuilder.service.UiBuilderComponentPackSource
import kotlinx.serialization.json.JsonPrimitive

/**
 * A served catalog's component record, projected onto a component pack the builder can merge.
 *
 * ## What this is the smallest form of
 *
 * `docs/design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md` plans to *generate* the builder's capability
 * catalog from `components.json` plus authored policy, and lists the corrections that separate a
 * generator from a regression. This is the generator for the case that needs no authored policy at
 * all: an application's own composables, offered inside a Material 3 screen as **extra** components
 * rather than as the catalog. Nothing here replaces `m3-catalog`'s hand-authored declaration — that
 * one carries editor hints, variant selectors and slot policy a record cannot say — so the rules
 * below are deliberately the safe subset:
 *
 * - **Only the project's own symbols.** A Confetti record also lists every `androidx.compose`
 *   composable its previews render, because the record describes what the previews call. Offering
 *   those as `confetti-mobile/text` would be Material 3 twice, under an id that lies about where it
 *   came from. [ComponentOrigin.PROJECT] is the line.
 * - **Only what the producer proved a call site for.** `code.call` is the licence: a component the
 *   producer refused (a required parameter with no literal, a collided overload, a receiver scope)
 *   is left out rather than offered and refused at export.
 * - **A property is a parameter with a literal.** `String`, `Boolean`, `Int`/`Long` and
 *   `Float`/`Double` become properties, required when the parameter has no default and is not
 *   nullable. Everything else — `Modifier`, callbacks, domain types, enums whose constants the
 *   record does not list — is left for the generator's placeholder table, which is what `code.call`
 *   proved works. The role table in the plan (event, state callback) is authored knowledge and is
 *   not guessed at here.
 * - **A slot is a `@Composable` lambda, and accepts anything.** Which roles and traits a slot
 *   accepts is authored policy the record does not carry, so a pack slot constrains nothing: a
 *   record-derived component can hold whatever the author drops in it, and the compiler is the
 *   check, on the native lane.
 * - **The canvas draws a placeholder, and says so.** The browser cannot link an application's
 *   classes, so `wasm.adapterStatus` is `unsupported` and the editor draws the node as a named
 *   outline — the same honest shape `wear-m3`'s native-only components use.
 *
 * ## One rule for the id, both ways
 *
 * [UiBuilderComponentPack.componentId] names the component; [aliasedRecord] writes the same id back
 * onto the record as a catalog alias so the export resolves it. Both call the one function, which
 * is what keeps the capability the editor offers and the record the export generates from agreeing
 * about what `confetti-mobile/session-card` is.
 */
internal object ComponentRecordPacks {

  /** A pack as the runtime merges it, plus what was left out and why. */
  data class Derived(
    val source: UiBuilderComponentPackSource,
    /** `<component id> — <reason>`, for the startup log. */
    val skipped: List<String>,
  )

  /** Derive the pack [packId] from [record], for the catalogs of [platform]. */
  fun derive(
    packId: String,
    platform: UiBuilderCatalogPlatform,
    record: ComponentRecordFile,
    label: String = labelFor(packId),
  ): Derived {
    val skipped = mutableListOf<String>()
    val taken = mutableSetOf<String>()
    val components =
      record.components.mapNotNull { component ->
        val id = UiBuilderComponentPack.componentId(packId, component.symbol.name)
        val reason = exclusionReason(component)
        when {
          reason != null -> {
            skipped += "$id — $reason"
            null
          }
          !taken.add(id) -> {
            skipped += "$id — a component of the same name was already taken from this record"
            null
          }
          else -> capability(id, component, packId)
        }
      }
    return Derived(
      source =
        UiBuilderComponentPackSource(
          id = packId,
          label = label,
          platform = platform.wireValue,
          nativeCatalog = packId,
          components = components,
          notes =
            "${components.size} of ${record.components.size} components in $packId's record, " +
              "drawn on the canvas as placeholders and rendered natively against the $packId bundle.",
        ),
      skipped = skipped,
    )
  }

  /**
   * [record] with every component this pack offers carrying its pack id as a catalog alias, and
   * every component it does not offer removed.
   *
   * Removed rather than kept, because the merged record an export generates from is the design's
   * catalog record plus this one, and a library symbol the pack declined to offer would still
   * resolve by canonical id — reachable from nothing in the document, but two records for
   * `androidx.compose.material3.Text` is the ambiguity `ScreenGenerator` refuses by alias, and
   * keeping one around for no caller is how that starts.
   */
  fun aliasedRecord(packId: String, record: ComponentRecordFile): ComponentRecordFile {
    val taken = mutableSetOf<String>()
    return record.copy(
      components =
        record.components.mapNotNull { component ->
          if (exclusionReason(component) != null) return@mapNotNull null
          val id = UiBuilderComponentPack.componentId(packId, component.symbol.name)
          if (!taken.add(id)) return@mapNotNull null
          component.copy(componentIds = listOf(id))
        }
    )
  }

  /** `confetti-mobile` → `Confetti Mobile`. */
  fun labelFor(packId: String): String =
    packId.split('-', '_', '.').filter(String::isNotEmpty).joinToString(" ") { word ->
      word.replaceFirstChar(Char::uppercaseChar)
    }

  private fun exclusionReason(component: ComponentRecord): String? =
    when {
      component.symbol.origin != ComponentOrigin.PROJECT ->
        "a library symbol, not the project's own"
      !component.signatureKnown -> "its signature was not recovered"
      component.overloadsCollided -> "overloads collided into one record"
      component.hasTypeParameters -> "it declares type parameters"
      component.hasContextReceivers -> "it declares a context receiver"
      component.symbol.receiver != null -> "it is declared on `${component.symbol.receiver}`"
      !component.callableFromAnotherFile -> "it is not callable from another file"
      component.code?.call == null ->
        "no call site: ${component.code?.refusedReason ?: "none was recorded"}"
      else -> null
    }

  private fun capability(
    id: String,
    component: ComponentRecord,
    packId: String,
  ): ComponentCapabilityV1 {
    val slots =
      component.slots.map { slot ->
        SlotCapabilityV1(
          name = slot.name,
          cardinality = SlotCardinalityV1(min = 0, max = null),
          ordered = true,
        )
      }
    val slotNames = slots.map { it.name }.toSet()
    val properties =
      component.parameters
        .filterNot { it.composableSlot || it.name in slotNames }
        .mapNotNull { parameter ->
          val jsonType = jsonTypeOf(parameter) ?: return@mapNotNull null
          PropertyCapabilityV1(
            name = parameter.name,
            jsonType = JsonPrimitive(jsonType),
            required = !parameter.hasDefault && !parameter.nullable,
            notes = "`${parameter.name}: ${parameter.type}` on `${component.symbol.callable}`.",
          )
        }
    val container = slots.isNotEmpty()
    return ComponentCapabilityV1(
      componentId = id,
      displayName = displayName(component.symbol.name),
      role = if (container) "Container" else "Leaf",
      traits = listOf(PACK_TRAIT),
      slots = slots,
      properties = properties,
      modifierCapabilities = if (container) CONTAINER_MODIFIERS else LEAF_MODIFIERS,
      wasm =
        WasmCapabilityV1(
          platformSupported = JsonPrimitive(false),
          adapterStatus = WasmAdapterStatusV1.UNSUPPORTED,
          notes =
            "Drawn on the canvas as a named placeholder: the browser cannot link $packId's " +
              "classes. The native preview compiles `${component.symbol.callable}` against the " +
              "served $packId bundle and renders the real component.",
        ),
      code =
        CodeCapabilityV1(
          symbol = component.symbol.callable,
          imports = component.code?.imports.orEmpty().ifEmpty { listOf(component.symbol.callable) },
        ),
      svg =
        SvgCapabilityV1(
          status = SvgCapabilityStatusV1.UNSUPPORTED,
          fallback = SvgFallbackV1.EMBEDDED_RASTER,
          blocksExport = false,
          notes = "A pack component has no vector adapter; SVG export embeds the native raster.",
        ),
    )
  }

  /** The JSON Schema type a parameter's literal has, or null for a parameter with no literal. */
  private fun jsonTypeOf(parameter: TargetParameter): String? =
    when (parameter.typeFqn) {
      "kotlin.String" -> "string"
      "kotlin.Boolean" -> "boolean"
      "kotlin.Int",
      "kotlin.Long" -> "integer"
      "kotlin.Float",
      "kotlin.Double" -> "number"
      else -> null
    }

  /** `SessionCard` → `Session Card`. */
  private fun displayName(name: String): String =
    UiBuilderComponentPack.kebabCase(name).split('-').joinToString(" ") { word ->
      word.replaceFirstChar(Char::uppercaseChar)
    }

  /** The trait every pack component carries, so a slot policy can name pack content. */
  const val PACK_TRAIT: String = "PackContent"

  /** What `m3/text` may carry: the modifiers a leaf can take without a scope it may not have. */
  private val LEAF_MODIFIERS =
    listOf(
      "size",
      "fillMaxWidth",
      "padding",
      "alpha",
      "offset",
      "rotate",
      "scale",
      "zIndex",
      "testTag",
      "width",
      "height",
      "widthIn",
      "heightIn",
      "aspectRatio",
      "align",
      "alignHorizontal",
      "alignVertical",
      "weight",
    )

  /** What `layout/column` may carry: the leaf set plus what a container that fills does. */
  private val CONTAINER_MODIFIERS =
    LEAF_MODIFIERS +
      listOf(
        "fillMaxSize",
        "fillMaxHeight",
        "background",
        "border",
        "shadow",
        "wrapContentSize",
        "verticalScroll",
      )
}
