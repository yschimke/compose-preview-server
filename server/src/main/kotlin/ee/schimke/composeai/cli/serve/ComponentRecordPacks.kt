package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentOrigin
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.TargetParameter
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderComponentPack
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
 * A served catalog's component record projected onto a component pack the builder can merge: an
 * application's own composables offered as extra components inside a Material 3 screen. This is the
 * policy-free subset of the generator planned in
 * `docs/design/UI_BUILDER_ON_THE_COMPONENT_RECORD.md`; it never replaces a hand-authored catalog.
 *
 * - **Only the project's own symbols** ([ComponentOrigin.PROJECT]); records also list library
 *   composables the previews call, which would be Material 3 under a misleading id.
 * - **Only components with a proven call site** (`code.call`); ones the producer refused are left
 *   out rather than refused at export.
 * - **A property is a parameter with a literal** (`String`, `Boolean`, `Int`/`Long`,
 *   `Float`/`Double`), required when it has no default and isn't nullable. Everything else is left
 *   to the generator's placeholder table.
 * - **A slot is a `@Composable` lambda and accepts anything**; slot policy is authored knowledge,
 *   so the compiler is the check on the native lane.
 * - **The canvas draws a named placeholder**: the browser can't link application classes, so
 *   `wasm.adapterStatus` is `unsupported`.
 *
 * [UiBuilderComponentPack.componentId] and [aliasedRecord] share one id function, so the editor's
 * capability and the export's record agree on ids like `confetti-mobile/session-card`.
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
   * [record] with every offered component aliased by its pack id and every other component removed:
   * a declined library symbol would still resolve by canonical id, and duplicate records (e.g. two
   * `androidx.compose.material3.Text`) are an ambiguity `ScreenGenerator` refuses.
   */
  fun aliasedRecord(packId: String, record: ComponentRecordFile): ComponentRecordFile {
    val taken = mutableSetOf<String>()
    return record
      .newBuilder()
      .apply {
        components =
          record.components.mapNotNull { component ->
            if (exclusionReason(component) != null) return@mapNotNull null
            val id = UiBuilderComponentPack.componentId(packId, component.symbol.name)
            if (!taken.add(id)) return@mapNotNull null
            component.newBuilder().apply { componentIds = listOf(id) }.build()
          }
      }
      .build()
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
        SlotCapabilityV1.Builder(
            slot.name,
            SlotCardinalityV1.Builder()
              .also {
                it.min = 0
                it.max = null
              }
              .build(),
            true,
          )
          .build()
      }
    val slotNames = slots.map { it.name }.toSet()
    val properties =
      component.parameters
        .filterNot { it.composableSlot || it.name in slotNames }
        .mapNotNull { parameter ->
          val jsonType = jsonTypeOf(parameter) ?: return@mapNotNull null
          PropertyCapabilityV1.Builder(propertyNameOf(parameter), JsonPrimitive(jsonType))
            .also {
              it.required = !parameter.hasDefault && !parameter.nullable
              it.notes = "`${parameter.name}: ${parameter.type}` on `${component.symbol.callable}`."
            }
            .build()
        }
    val container = slots.isNotEmpty()
    return ComponentCapabilityV1.Builder(
        id,
        displayName(component.symbol.name),
        if (container) "Container" else "Leaf",
        WasmCapabilityV1.Builder(
            platformSupported = JsonPrimitive(false),
            adapterStatus = WasmAdapterStatusV1.UNSUPPORTED,
          )
          .also {
            it.notes =
              "Drawn on the canvas as a named placeholder: the browser cannot link $packId's " +
                "classes. The native preview compiles `${component.symbol.callable}` against the " +
                "served $packId bundle and renders the real component."
          }
          .build(),
      )
      .also {
        it.traits = listOf(PACK_TRAIT)
        it.slots = slots
        it.properties = properties
        it.modifierCapabilities = structuralModifiers(container)
        it.code =
          CodeCapabilityV1.Builder(component.symbol.callable)
            .also {
              it.imports =
                component.code?.imports.orEmpty().ifEmpty { listOf(component.symbol.callable) }
            }
            .build()
        it.svg =
          SvgCapabilityV1.Builder(
              SvgCapabilityStatusV1.UNSUPPORTED,
              SvgFallbackV1.EMBEDDED_RASTER,
              false,
            )
            .also {
              it.notes =
                "A pack component has no vector adapter; SVG export embeds the native raster."
            }
            .build()
      }
      .build()
  }

  /**
   * The JSON Schema type of a parameter's literal, or null. compose-ui-builder's
   * `PublishedUiBuilderCatalog` carries a copy; change them together.
   */
  internal fun jsonTypeOf(parameter: TargetParameter): String? =
    when (parameter.typeFqn) {
      "kotlin.String" -> "string"
      "kotlin.Boolean" -> "boolean"
      "kotlin.Int",
      "kotlin.Long" -> "integer"
      "kotlin.Float",
      "kotlin.Double" -> "number"
      "androidx.compose.ui.graphics.Color",
      "androidx.compose.ui.graphics.Shape",
      "androidx.compose.ui.text.font.FontStyle",
      "androidx.compose.ui.text.font.FontWeight",
      "androidx.compose.ui.text.style.TextAlign",
      "androidx.compose.ui.text.style.TextDecoration",
      "androidx.compose.ui.text.style.TextOverflow" -> "string"
      "androidx.compose.ui.unit.Dp",
      "androidx.compose.ui.unit.TextUnit" -> "number"
      // Remote Compose value types, taken by Remote catalogs instead of Kotlin ones
      // (`RemoteText(text: RemoteString)`); without them those components had no editable
      // properties. The document JSON is the same; only the emitted expression (`"…".rs`) differs.
      "androidx.compose.remote.creation.compose.state.RemoteString",
      // A colour travels as a string in both vocabularies — `#RRGGBB` or a token name — which is
      // what the frozen catalogs already say for every `color` property they carry.
      "androidx.compose.remote.creation.compose.state.RemoteColor" -> "string"
      "androidx.compose.remote.creation.compose.state.RemoteBoolean" -> "boolean"
      "androidx.compose.remote.creation.compose.state.RemoteInt" -> "integer"
      "androidx.compose.remote.creation.compose.state.RemoteFloat" -> "number"
      // A text size. `number` like `RemoteFloat`, but spelled `22.rsp` and with no `Float.rsp`, so
      // only whole numbers have a Kotlin form; `RemoteContentEmitter` refuses a fractional size by
      // name rather than rounding.
      "androidx.compose.remote.creation.compose.state.RemoteTextUnit" -> "number"
      else -> null
    }

  /**
   * The document spelling for a typed parameter. Units get a suffix so the unit is part of the
   * saved vocabulary (`fontSize` → `fontSizeSp`, `tonalElevation` → `tonalElevationDp`); others
   * keep the parameter name.
   */
  internal fun propertyNameOf(parameter: TargetParameter): String =
    when (parameter.typeFqn) {
      "androidx.compose.ui.unit.Dp" -> "${parameter.name}Dp"
      "androidx.compose.ui.unit.TextUnit" -> "${parameter.name}Sp"
      else -> parameter.name
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
  /**
   * The container/leaf split. compose-ui-builder's `PublishedUiBuilderCatalog` carries a copy, and
   * they must agree so a published catalog with no `modifiers` gets the same default as a pack
   * component.
   */
  internal fun structuralModifiers(container: Boolean): List<String> =
    if (container) CONTAINER_MODIFIERS else LEAF_MODIFIERS

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
