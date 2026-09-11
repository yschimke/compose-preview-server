package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.REMOTE_CONTENT_MODIFIERS
import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CodeCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * A catalog's published `ui-builder.json`, composed with its component record into the capability
 * catalog the builder serves.
 *
 * This is the read half of `docs/design/UI_BUILDER_CATALOG_CONTRACT.md`. The catalog repositories
 * publish two files and this composes them:
 *
 * - **`components.json`** is the INVENTORY: every composable the catalog's previews render, with
 *   the signature discovery recovered. It says what exists.
 * - **`ui-builder.json`** is the POLICY: platform, shelves, frame, templates, screen strategy, and
 *   per-component editor knowledge no signature holds. It says what a builder should do with it.
 *
 * Neither restates the other, and [UiBuilderComponentPolicy.record] is the join — the record's
 * `canonicalId`. Composing them here is what lets a catalog this binary has never heard of appear
 * in the chooser, which is the whole point of the contract.
 *
 * ## Why the policy is read from the published file rather than the record
 *
 * The record also carries each component's resolved `builder` policy, which would make this a
 * one-file read. The published file is used anyway, and not as a workaround:
 *
 * - **A catalog need not have a record here at all.** The whole point is that a catalog this binary
 *   has never heard of can be served, and its record is a separate file that may be absent, stale,
 *   or on a schema this build will not read. Policy that only arrives with an inventory is policy
 *   that cannot describe a catalog of builtins.
 * - **`statusSemantics.components` exists for exactly this reader.** It is published so a consumer
 *   holding the file can pair a builder id with a record entry, and dropping it in favour of the
 *   record's copy would make a published field nothing reads — which is how a field stops being
 *   maintained.
 *
 * The two agree by construction, because the generator writes this file FROM that field, so this is
 * a choice about which of two equal sources is the contract — not about which is available.
 *
 * ## What this does not do
 *
 * It does not decide anything the published file does not say. A component the file has no policy
 * for is still admitted — an unannotated record component belongs on the shelf, which is why
 * [UiBuilderStatusSemantics.componentIdPrefix] is published at all — and it is admitted with the
 * defaults the record supports, not with values invented on the catalog's behalf. Where the file
 * and this reader could disagree about a derived id, the frozen goldens and
 * `.github/scripts/ui-builder-equivalence.sh` are what catch it: that gate exists so this
 * composition can be proved equal to what the server synthesises today, per catalog, before any
 * catalog stops being synthesised.
 */
internal object PublishedUiBuilderCatalog {

  /** The outcome of composing one catalog, which is never an exception. */
  sealed interface Result {
    /**
     * [catalog] is ready to serve; [note] is the one startup line saying where it came from.
     *
     * [records] is each component of [catalog] that came from the record, under the builder id it
     * is served as — the join this composition performs and nothing else can. A design node names a
     * builder id; only the published file states which record component that id was derived from,
     * so a reader handed the record alone would have to re-run [derivedId] and the policy's
     * `record` field to get back here. Carried rather than re-derived, because two implementations
     * of one derivation is how a saved design and the code generated for it come to disagree.
     *
     * Builtins are absent by construction: a builtin is declared precisely because no record
     * component backs it.
     */
    data class Composed(
      val catalog: CatalogCapabilityV1,
      val note: String,
      val records: Map<String, ComponentRecord>,
    ) : Result

    /**
     * The published file could not be used, and [reason] says why in a form an operator can act on.
     *
     * Never fatal by itself. A catalog whose published file will not compose falls back to whatever
     * the server can synthesise, exactly as one that publishes nothing does — the cutover is per
     * catalog and reversible, and a host that refused to start over another repository's bad
     * publish would be down until that repository's CI ran again.
     */
    data class Unusable(val reason: String) : Result
  }

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = false
  }

  /**
   * Compose [publishedJson] with [record] into a capability catalog.
   *
   * [record] may be null: a catalog that publishes policy and no inventory is still a catalog — one
   * whose components are all builtins — and refusing it here would be refusing the simplest thing
   * the contract can express.
   */
  fun compose(
    publishedJson: String,
    record: ComponentRecordFile?,
    exportCapabilities: ExportCapabilitiesV1,
  ): Result {
    val root =
      runCatching { json.parseToJsonElement(publishedJson) as? JsonObject }
        .getOrElse {
          return Result.Unusable("ui-builder.json did not parse: ${it.message}")
        } ?: return Result.Unusable("ui-builder.json is not a JSON object")
    val file = runCatching {
      json.decodeFromJsonElement<PublishedFile>(root)
    }
      .getOrElse {
        return Result.Unusable("ui-builder.json did not parse: ${it.message}")
      }
    // The whole published block, kept verbatim for the readers this one does not interpret.
    val rawSemantics = root["statusSemantics"] as? JsonObject ?: JsonObject(emptyMap())
    if (file.schema != UI_BUILDER_CATALOG_SCHEMA) {
      // A major this reader does not know is refused by name rather than read optimistically. The
      // fallback keeps the catalog served, so the cost of refusing is a stale shelf and a line
      // saying so, and the cost of guessing is a shelf that silently means something else.
      return Result.Unusable(
        "ui-builder.json declares schema ${file.schema}; this server reads $UI_BUILDER_CATALOG_SCHEMA"
      )
    }
    val semantics = file.statusSemantics
    val prefix = semantics.componentIdPrefix.trim()
    if (prefix.isEmpty()) {
      return Result.Unusable("ui-builder.json declares no componentIdPrefix")
    }
    val id = file.catalog.id.trim()
    if (id.isEmpty()) return Result.Unusable("ui-builder.json declares no catalog id")

    // A `jsonType` the design validator can read, and a slot cardinality that can be satisfied,
    // or the file is refused.
    //
    // `PropertyCapabilityV1.jsonType` is a free-form `JsonElement`, and the runtime's
    // `JsonElement.accepts` reads it as `jsonPrimitive.content` — or, for an array, each entry's.
    // So `"jsonType": {}`, or `["string", 7]`, decodes here and THROWS there, while a design is
    // being written. That is the worst shape a bad catalog can take: not a shelf that refuses to
    // load, but an authoring path that crashes on save.
    //
    // BUILTINS are checked alongside components, and were not on the first cut of this — the same
    // "some of the places" this file keeps being corrected for. A builtin's properties reach
    // `builtinCapability` by the identical route and are read by the identical validator.
    //
    // Cardinality is here rather than in the runtime's `validateCatalog` because that function
    // requires `catalogSystemId == "m3-catalog"` and so can only ever see the packaged catalog. A
    // published one reaches the shelf unvalidated, and `max < min` makes every child count invalid
    // — a component nobody can author, on a shelf that loaded cleanly.
    val unreadable = mutableListOf<String>()
    fun checkProperties(owner: String, properties: List<UiBuilderPropertyPolicy>) {
      // Unique names, because `CapabilityCatalogParser.validateCatalogShape` requires them and
      // THROWS while installing the catalog — so a duplicate does not degrade the editor, it stops
      // it opening, which is the one outcome the per-catalog fallback exists to prevent.
      properties
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { unreadable += "$owner.$it (declared twice)" }
      for (property in properties) {
        when (val type = property.jsonType) {
          is JsonArray -> {
            // `[]` is a union of nothing: no value satisfies it, so a required property declaring
            // it is a component nobody can author. `all {}` is vacuously true, which is how it got
            // past the first cut of this check.
            if (type.isEmpty()) unreadable += "$owner.${property.name} (jsonType is empty)"
            else
              type.forEach { entry ->
                val name = (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (name == null || name !in SUPPORTED_JSON_TYPES)
                  unreadable += "$owner.${property.name} (jsonType $entry is not a type name)"
              }
          }
          is JsonPrimitive ->
            if (!type.isString || type.content !in SUPPORTED_JSON_TYPES)
              unreadable += "$owner.${property.name} (jsonType $type is not a type name)"
          else -> unreadable += "$owner.${property.name} (jsonType is neither a name nor a list)"
        }
        // The EDITOR seeds a new node from the first allowed value and reads it as a primitive, so
        // a non-primitive one is a component that cannot be inserted. Refused rather than
        // stringified: a summary can degrade, an authoring path cannot.
        property.allowedValues.forEachIndexed { index, value ->
          if (value !is JsonPrimitive)
            unreadable += "$owner.${property.name}.allowedValues[$index] (not a literal)"
        }
      }
    }
    fun checkSlots(owner: String, slots: List<UiBuilderSlotPolicy>) {
      slots
        .groupBy { it.name }
        .filterValues { it.size > 1 }
        .keys
        .forEach { unreadable += "$owner.$it (slot declared twice)" }
      for (slot in slots) {
        val min = slot.cardinality.min
        val max = slot.cardinality.max
        if (min < 0) unreadable += "$owner.${slot.name} (cardinality min $min is negative)"
        else if (max != null && max < min)
          unreadable += "$owner.${slot.name} (cardinality max $max is below min $min)"
      }
    }
    // The rule `checkSlots` applies to a component's slots, over the pair a builtin derives:
    // `required` sets the minimum, `max` bounds it above. This could not fail until a builtin
    // could state `max` — the minimum is 0 or 1 and the maximum was always unbounded — which is
    // why the builtins loop below checked only properties. Now that the bound is writable, an
    // impossible one reaches the shelf the same way a component's would, and the paragraph above
    // says what that costs: a component nobody can author, on a catalog that loaded cleanly.
    //
    // No duplicate-name arm, unlike its sibling: a builtin's slots are a map, so the wire cannot
    // carry the same name twice.
    fun checkBuiltinSlots(owner: String, slots: Map<String, UiBuilderBuiltinSlot>) {
      for ((name, slot) in slots) {
        val min = if (slot.required) 1 else 0
        val max = slot.max ?: continue
        if (max < min) unreadable += "$owner.$name (cardinality max $max is below min $min)"
      }
    }
    semantics.components.forEach { (componentId, policy) ->
      checkProperties(componentId, policy.propertyCapabilities.orEmpty())
      checkSlots(componentId, policy.slotCapabilities.orEmpty())
    }
    semantics.builtins.forEach { (builtinId, builtin) ->
      checkProperties(builtinId, builtin.properties.orEmpty())
      checkBuiltinSlots(builtinId, builtin.slots)
    }
    if (unreadable.isNotEmpty()) {
      return Result.Unusable(
        "${unreadable.size} declaration(s) the builder cannot serve: " +
          unreadable.sorted().take(8).joinToString(", ") +
          (if (unreadable.size > 8) ", …" else "")
      )
    }

    val policyByRecordId =
      semantics.components.entries.associateBy({ it.value.record }, { it.key to it.value })
    val taken = linkedMapOf<String, ComponentCapabilityV1>()
    // The same join as `taken`, kept as the records rather than the capabilities. See
    // [Result.Composed.records].
    val recordsById = linkedMapOf<String, ComponentRecord>()
    val skipped = mutableListOf<String>()
    // Counted apart from the rest of `skipped`, because a collision means something the other skip
    // reasons do not: two components claimed one identity. See [COLLISION_REFUSAL_RATE].
    var collisions = 0
    // Entries that actually competed for an identity. An excluded component never enters the shelf,
    // so counting it in the denominator lets a policy excluding most of its record hide a shelf
    // where everything left collides: 100 entries, 90 excluded, the remaining 10 all deriving one
    // id is 9 collisions against an allowance of 10 — composed, with a one-component shelf.
    var eligible = 0

    record?.components.orEmpty().forEach { component ->
      val declared = policyByRecordId[component.canonicalId]
      val policy = declared?.second
      val componentId = declared?.first ?: derivedId(prefix, component)
      val excluded = policy?.excluded
      when {
        excluded != null -> skipped += "$componentId — $excluded"
        // Everything below this arm competed for `componentId`, so everything below counts.
        // A duplicate id is the catalog's to fix and is reported rather than resolved: picking a
        // winner silently would bind saved designs to whichever entry happened to sort first.
        taken.containsKey(componentId) -> {
          eligible++
          collisions++
          skipped += "$componentId — a component of the same id was already taken from this record"
        }
        else -> {
          eligible++
          taken[componentId] = capability(componentId, component, policy, semantics.platform)
          recordsById[componentId] = component
        }
      }
    }

    semantics.builtins.forEach { (builtinId, builtin) ->
      if (taken.containsKey(builtinId)) {
        skipped += "$builtinId — declared as a builtin, but a record component publishes this id"
      } else {
        taken[builtinId] = builtinCapability(builtinId, builtin, semantics.platform)
      }
    }

    if (taken.isEmpty()) {
      return Result.Unusable(
        "composing ui-builder.json with the component record yielded no components"
      )
    }
    // Collisions in bulk mean the ids are not identities.
    //
    // A single collision is one catalog bug and the shelf around it is still the right shelf, so it
    // is skipped and reported. A large fraction is a different claim: the file is not naming
    // components, and what survives is whichever entry happened to be walked first. The case this
    // was written from published a policy declaring no `components`, so every id fell to
    // `derivedId`, and its `componentIds` were a `Group/Variant` taxonomy whose LEAF is the
    // variant. One variant word was claimed by 15 components. 63 of 104 collided, and the 41
    // survivors shared exactly ONE id with the catalog this server synthesises.
    //
    // That is the shape this refusal is for, and note what did NOT catch it: the composition
    // produced 41 components against a frozen shelf of 41, so any check comparing counts reports a
    // match. Only the ids say otherwise.
    // `maxOf(1, …)` rather than the bare rate, so ONE collision never refuses whatever the record's
    // size. On a two-component record a single collision is 50% and would have tripped a plain
    // rate — which contradicts the paragraph above, and did: it broke
    // `PublishedUiBuilderCatalogHostileInputTest`'s two-component collision fixture, which expects
    // a skip. The rate is for the bulk case; the floor keeps the stated "one is a catalog bug"
    // true at every scale.
    val allowed = maxOf(1, (eligible * COLLISION_REFUSAL_RATE).toInt())
    if (eligible > 0 && collisions > allowed) {
      return Result.Unusable(
        "$collisions of $eligible eligible record components collided on an already-taken " +
          "component id, leaving ${taken.size} — the published file is not naming components " +
          "distinctly, so which one survives is an accident of record order"
      )
    }
    // The file says how many record components it was generated against. If it expected an
    // inventory and none arrived, this composed to its builtins alone — which does not fail, it
    // quietly serves a near-empty shelf in place of whatever the server would otherwise offer.
    // Refusing is the safe direction: the fallback keeps the catalog whole, and the reason names
    // the missing file rather than leaving an operator to notice that a shelf got shorter.
    val expected = file.record?.components ?: 0
    if (expected > 0 && record == null) {
      return Result.Unusable(
        "ui-builder.json was generated against a $expected-component record and none is available " +
          "here, so it would compose to its builtins alone"
      )
    }

    val catalog =
      CatalogCapabilityV1(
        schema = CAPABILITY_SCHEMA,
        benchmark =
          CatalogBenchmarkV1(
            catalogRevision = revisionOf(publishedJson),
            sourceRevision = file.record?.file ?: UI_BUILDER_CATALOG_FILE_NAME,
            catalogSystemId = id,
            nativeRuntimeId = NATIVE_RUNTIME_ID,
            id = id,
          ),
        components = taken.values.toList(),
        exportCapabilities = exportCapabilities,
        statusSemantics = rawSemantics,
      )
    val note =
      "$id — published ui-builder.json (${taken.size} component(s)" +
        (if (skipped.isEmpty()) "" else ", ${skipped.size} skipped") +
        ")"
    return Result.Composed(catalog, note, recordsById)
  }

  /**
   * The builder id of a record component the published file says nothing about.
   *
   * Reproduces the generator's derivation, which is the one place this reader and the producer have
   * to agree without a field to agree through: an unannotated component is deliberately absent from
   * `statusSemantics.components` and still belongs on the shelf, so its id must be DERIVABLE. The
   * leaf is the component's own catalog id where it has one, because that is the name its author
   * chose; the equivalence gate is what proves the two derivations still match.
   */
  private fun derivedId(prefix: String, component: ComponentRecord): String {
    val leaf =
      component.componentIds.firstOrNull()?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: component.symbol.name
    return "$prefix${slug(leaf)}"
  }

  /**
   * `CheckboxButton` → `checkbox-button`, `RTLText` → `rtl-text`, `Button2` → `button2`.
   *
   * Splits on a lower-to-upper boundary and on any run of non-alphanumerics; a run of capitals is
   * one word, because splitting it letter by letter produces ids nobody would type. The same rule
   * the generator applies, stated here because a derived id is a saved design's identity and the
   * two sides must not drift.
   */
  internal fun slug(name: String): String {
    val out = StringBuilder()
    name.forEachIndexed { index, ch ->
      when {
        ch.isLetterOrDigit() -> {
          val previous = name.getOrNull(index - 1)
          val next = name.getOrNull(index + 1)
          val startsWord =
            previous != null &&
              ch.isUpperCase() &&
              (previous.isLowerCase() ||
                previous.isDigit() ||
                (previous.isUpperCase() && next?.isLowerCase() == true))
          if (startsWord && out.isNotEmpty() && out.last() != '-') out.append('-')
          out.append(ch.lowercaseChar())
        }
        out.isNotEmpty() && out.last() != '-' -> out.append('-')
      }
    }
    return out.toString().trim('-')
  }

  /** One record component, as the builder offers it, with the catalog's policy applied. */
  private fun capability(
    componentId: String,
    component: ComponentRecord,
    policy: UiBuilderComponentPolicy?,
    platform: String,
  ): ComponentCapabilityV1 {
    // The catalog's slots, or failing that the composable's. See
    // `UiBuilderComponentPolicy.slotCapabilities`.
    val slots =
      policy?.slotCapabilities?.map { stated ->
        SlotCapabilityV1(
          name = stated.name,
          cardinality =
            SlotCardinalityV1(min = stated.cardinality.min, max = stated.cardinality.max),
          ordered = stated.ordered,
          acceptedRoles = stated.acceptedRoles,
          acceptedTraits = stated.acceptedTraits,
        )
      }
        ?: component.slots.map { slot ->
          SlotCapabilityV1(
            name = slot.name,
            cardinality = SlotCardinalityV1(min = 0, max = null),
            ordered = true,
          )
        }
    val slotNames = slots.map { it.name }.toSet()
    // What the catalog says it offers, and only failing that what its call site happens to take.
    // See `UiBuilderComponentPolicy.propertyCapabilities` for why the two are not the same
    // question.
    val properties =
      policy?.propertyCapabilities?.map { it.toCapability() }
        ?: component.parameters
          .filterNot { it.composableSlot || it.name in slotNames }
          .mapNotNull { parameter ->
            val jsonType = ComponentRecordPacks.jsonTypeOf(parameter) ?: return@mapNotNull null
            PropertyCapabilityV1(
              name = parameter.name,
              jsonType = JsonPrimitive(jsonType),
              required = !parameter.hasDefault && !parameter.nullable,
              notes = "`${parameter.name}: ${parameter.type}` on `${component.symbol.callable}`.",
            )
          }
    return ComponentCapabilityV1(
      componentId = componentId,
      displayName = policy?.displayName ?: component.symbol.name,
      role = if (slots.isNotEmpty()) "Container" else "Leaf",
      traits = policy?.traits.orEmpty(),
      slots = slots,
      properties = properties,
      modifierCapabilities =
        (policy?.modifierCapabilities ?: structuralModifiers(slots.isNotEmpty())).writableOn(
          platform
        ),
      wasm = wasm(policy?.canvas, policy?.nativeOnly == true, component.symbol.callable),
      code =
        CodeCapabilityV1(
          symbol = component.symbol.callable,
          imports = component.code?.imports.orEmpty().ifEmpty { listOf(component.symbol.callable) },
        ),
    )
  }

  private fun UiBuilderPropertyPolicy.toCapability(): PropertyCapabilityV1 =
    PropertyCapabilityV1(
      name = name,
      jsonType = jsonType,
      required = required,
      allowedValues = allowedValues,
      notes = notes,
    )

  /**
   * What a component accepts when the catalog does not say.
   *
   * Deliberately NOT an attempt to reproduce a frozen catalog's editorial list — those differ by
   * component in ways nothing structural predicts. This is the honest fallback: the same
   * container/leaf split a pack component already gets, so a catalog that states nothing is usable
   * rather than inert. A catalog replacing a synthesised shelf states `modifiers` and does not
   * reach this.
   */
  private fun structuralModifiers(container: Boolean): List<String> =
    ComponentRecordPacks.structuralModifiers(container)

  /**
   * The shelf role of a builtin: `Scaffold`, `Container` or `Leaf`.
   *
   * Two different words are spelled `role` around here and they are not the same vocabulary. A
   * builtin's own `role` is the STRUCTURAL one — the template engine's closed set (`screen-root`,
   * `list`, `list-item`, `overlay`, `controlled`, `decoration`) — which says which template writes
   * it. The shelf's is `Scaffold` / `Container` / `Leaf`, which decides what the editor calls it
   * and which slots will take it.
   *
   * The structural one was read and then dropped, and the shelf role derived from whether there
   * were slots at all. That makes a design ROOT — a Wear catalog's `widget-container-small`, whose
   * synthesised twin in `ProductionUiBuilderRuntime.widget()` is a `Scaffold` — arrive as an
   * ordinary `Container`. `screen-root` is the one structural role that names a scaffold outright,
   * so it is the one that maps; every other builtin keeps the derivation, because `list` and
   * `overlay` say how a thing is WRITTEN and not what shape it is on the shelf.
   */
  private fun shelfRole(builtin: UiBuilderBuiltin): String =
    when {
      builtin.role == "screen-root" -> "Scaffold"
      builtin.slots.isNotEmpty() -> "Container"
      else -> "Leaf"
    }

  /**
   * A builtin, as a component.
   *
   * A builtin exists because it has NO call site — there is nothing in the record to discover — so
   * everything it offers comes from the policy. It carries no [CodeCapabilityV1] for the same
   * reason: the templates named by its role are what write it.
   */
  private fun builtinCapability(
    id: String,
    builtin: UiBuilderBuiltin,
    platform: String,
  ): ComponentCapabilityV1 =
    ComponentCapabilityV1(
      componentId = id,
      displayName = builtin.displayName ?: id.substringAfterLast('/'),
      role = shelfRole(builtin),
      traits = builtin.traits,
      slots =
        builtin.slots.map { (name, slot) ->
          SlotCapabilityV1(
            name = name,
            // `required` means at least one child; `max` bounds it above, and null there is the
            // unbounded slot every builtin had before it could say otherwise.
            cardinality = SlotCardinalityV1(min = if (slot.required) 1 else 0, max = slot.max),
            ordered = true,
            acceptedRoles = slot.acceptedRoles,
            acceptedTraits = slot.acceptedTraits,
          )
        },
      properties = builtin.properties.orEmpty().map { it.toCapability() },
      modifierCapabilities =
        (builtin.modifierCapabilities ?: structuralModifiers(builtin.slots.isNotEmpty()))
          .writableOn(platform),
      wasm = wasm(builtin.canvas, nativeOnly = false, callable = null),
    )

  /**
   * How the canvas draws this component.
   *
   * The catalog's `canvas` word is an ADAPTER ID, and a build that lacks the adapter draws a named
   * placeholder rather than nothing — the same honest shape a pack component already uses. Which
   * adapters exist is the renderer's business, so this reports what the catalog asked for and lets
   * the canvas resolve it; a catalog naming an adapter this build has never heard of degrades to a
   * placeholder instead of failing to load.
   */
  private fun wasm(canvas: String?, nativeOnly: Boolean, callable: String?): WasmCapabilityV1 {
    val drawn = !nativeOnly && canvas != null && canvas != PLACEHOLDER_CANVAS
    return WasmCapabilityV1(
      platformSupported = JsonPrimitive(drawn),
      adapterStatus = if (drawn) WasmAdapterStatusV1.SUPPORTED else WasmAdapterStatusV1.UNSUPPORTED,
      notes =
        when {
          nativeOnly ->
            "Rendered only on the native lane; the canvas draws a named placeholder." +
              (callable?.let { " The native preview compiles `$it`." } ?: "")
          drawn -> "Drawn on the canvas by the `$canvas` adapter."
          else -> "Drawn on the canvas as a named placeholder: this catalog claims no adapter."
        },
    )
  }

  /**
   * The modifiers this platform's emitter can actually write, or null where every modifier the
   * structural default offers is writable.
   *
   * A shelf must never offer what an export then refuses — the invariant
   * `RemoteM3VocabularyParityTest` holds the synthesised palette to, because a control you can
   * apply and cannot export is worse than one that is missing: the author finds out at the end,
   * with the design already built.
   *
   * The structural default is the server's own invention (`ComponentRecordPacks`), and it is a
   * JETPACK COMPOSE default: it carries `testTag`, `aspectRatio` and, for a container, `shadow`.
   * `RemoteContentEmitter` writes none of the three, so a published `remote-compose` catalog
   * advertised three controls whose use made every export of that design fail
   * (compose-preview-server#674 blocker 3).
   *
   * Keyed on the platform the CATALOG declares rather than on anything about the catalog itself:
   * which modifiers an emitter can write is the server's fact about its own emitters, and the
   * platform word is the catalog saying which emitter it is for.
   *
   * A stated `modifierCapabilities` is narrowed too, not only the default. A catalog naming a
   * modifier its own lane cannot write is the same defect written by hand, and the honest shelf is
   * the same either way.
   */
  private fun writableModifiers(platform: String): Set<String>? =
    when (UiBuilderCatalogPlatform.fromWord(platform)) {
      UiBuilderCatalogPlatform.REMOTE_COMPOSE -> REMOTE_CONTENT_MODIFIERS
      else -> null
    }

  private fun List<String>.writableOn(platform: String): List<String> {
    val writable = writableModifiers(platform) ?: return this
    return filter { it in writable }
  }

  /**
   * The revision this catalog is pinned by: a digest of the published bytes.
   *
   * A design pins the catalog it was authored against, and the pin has to change when the catalog
   * does. A published catalog has no revision of its own to borrow — the delivery branch's commit
   * describes the whole branch, not this file — so the file's own content is what identifies it.
   * Two hosts fetching the same published file therefore agree on the pin without coordinating,
   * which a branch commit would not give.
   */
  private fun revisionOf(published: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(published.toByteArray())
    return "sha256:" + digest.joinToString("") { "%02x".format(it) }.take(32)
  }

  /**
   * The share of a record's components that may collide on an already-taken id before the whole
   * published file is refused.
   *
   * A tenth, which is deliberately loose. The number that matters is not this threshold but the gap
   * either side of it: a catalog naming its components correctly collides on **zero**, and a
   * catalog whose ids are not identities collides on most of them — the two measured cases sit at
   * 61% and 36%. Nothing real sits near 10%, so the threshold does not have to be argued about; it
   * only has to separate a stray duplicate, which is one catalog bug worth skipping past, from a
   * file that is not describing components at all. The measurements, with the catalogs named, are
   * in `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` § Phase 4 — this file may not name one.
   *
   * Raising it is not the fix if a real catalog ever trips this. The collisions are its own to
   * resolve, by declaring `statusSemantics.components` rather than leaving every id to be derived.
   */
  private const val COLLISION_REFUSAL_RATE = 0.10

  /**
   * The type names a design's value can be checked against.
   *
   * `ProductionUiBuilderRuntime`'s `JsonElement.accepts` is the list, and this mirrors it — a name
   * outside it matches nothing, so a property declaring one can never hold a value and a REQUIRED
   * property declaring one is a component nobody can author. I argued in review that an unknown
   * name degrades safely to a type mismatch; that is true for an optional property and false for a
   * required one, which is the same "impossible to author" this file already refuses a `max < min`
   * cardinality for.
   *
   * Mirrored rather than shared because `accepts` is a private `when` in a module `:server` does
   * not depend on. Adding a name there means adding it here; the equivalence test's check that
   * every frozen catalog's declared types are in this set is what makes the drift visible.
   */
  private val SUPPORTED_JSON_TYPES =
    setOf("null", "string", "boolean", "number", "integer", "array", "object")

  private const val UI_BUILDER_CATALOG_SCHEMA = "compose-ui-builder-catalog/v1"
  private const val CAPABILITY_SCHEMA = "compose-catalog-capabilities/v1"
  private const val UI_BUILDER_CATALOG_FILE_NAME = "ui-builder.json"
  private const val PLACEHOLDER_CANVAS = "placeholder"

  /**
   * What a published catalog claims of the native lane.
   *
   * `candidate` is what every catalog this server serves already declares, and a published catalog
   * is not making a stronger claim than a synthesised one; the native lane's own compile is the
   * check either way.
   */
  private const val NATIVE_RUNTIME_ID = "candidate"

  // The wire shape of `ui-builder.json`, as this reader needs it. Deliberately a narrow mirror of
  // the generator's output rather than a shared type: the generator's model lives in a
  // compose-ai-tools module `checkUiBuilderRuntimeBoundary` keeps off this classpath, and a reader
  // that decodes only what it reads cannot be broken by a field it ignores.

  @Serializable
  private data class PublishedFile(
    val schema: String = "",
    val catalog: PublishedIdentity = PublishedIdentity(),
    val record: PublishedRecordRef? = null,
    val statusSemantics: UiBuilderStatusSemantics = UiBuilderStatusSemantics(),
  )

  @Serializable private data class PublishedIdentity(val id: String = "", val title: String = "")

  @Serializable
  private data class PublishedRecordRef(val file: String? = null, val components: Int = 0)

  @Serializable
  internal data class UiBuilderStatusSemantics(
    val componentIdPrefix: String = "",
    /**
     * The word the catalog uses for its lane — `mobile`, `wear`, `remote-compose`.
     *
     * Read for one purpose: a platform whose emitter has a narrower modifier vocabulary than the
     * structural default cannot be offered the difference. See [writableModifiers].
     */
    val platform: String = "",
    val builtins: Map<String, UiBuilderBuiltin> = emptyMap(),
    val components: Map<String, UiBuilderComponentPolicy> = emptyMap(),
  )

  @Serializable
  internal data class UiBuilderBuiltin(
    val role: String = "",
    val displayName: String? = null,
    val canvas: String? = null,
    val traits: List<String> = emptyList(),
    val slots: Map<String, UiBuilderBuiltinSlot> = emptyMap(),
    /**
     * A builtin has no record, so this is its only source.
     *
     * Named `properties`, unlike [UiBuilderComponentPolicy.propertyCapabilities] beside it, because
     * that is the name on the wire: `ui-builder.policy.schema.json` spells a builtin's list
     * `properties` with `additionalProperties: false`, and the generator's own `UiBuilderBuiltin`
     * carries it through under that name. This read `propertyCapabilities` — a name no schema-valid
     * catalog can write — so every builtin composed with zero properties and the one test that
     * covered the path wrote the server's name into its own fixture and passed. The argument for
     * the distinct `…Capabilities` names is a real one and it is about COMPONENTS, where `slots`
     * and `properties` are already taken by other types; a builtin has no such collision, and
     * copying the convention across cost the field its only writer.
     */
    val properties: List<UiBuilderPropertyPolicy>? = null,
    /** See [UiBuilderComponentPolicy.modifierCapabilities]. */
    val modifierCapabilities: List<String>? = null,
  )

  /**
   * One slot a builtin declares, as `ui-builder.policy.schema.json` spells it.
   *
   * Decoded as `Map<String, JsonElement>` before, which parsed every shape and read none: a slot
   * marked `required` composed with `min = 0`, and its `acceptedTraits` were dropped, so the rules
   * that stop a design putting a scaffold inside a widget's background slot were gone. The `role`
   * here is the STRUCTURAL role each child is written with — the template engine's closed set, not
   * the shelf's `Container`/`Leaf` — so it is carried and not turned into `acceptedRoles`.
   */
  @Serializable
  internal data class UiBuilderBuiltinSlot(
    val acceptedRoles: List<String> = emptyList(),
    val acceptedTraits: List<String> = emptyList(),
    val required: Boolean = false,
    /**
     * The most children this slot admits, or null for unbounded.
     *
     * A builtin is the only way a catalog offers a component with no call site, so what it declares
     * is all there is — and its slots could say what they accept but not how many. A host that
     * draws exactly one child composed unbounded either way, so the shelf offered a container a
     * design could put three children into while the host drew one of them.
     *
     * Absent stays unbounded, which is what every builtin slot was before this field: no existing
     * declaration acquires a bound it never asked for. `required` sets the other end.
     */
    val max: Int? = null,
    val role: String? = null,
  )

  @Serializable
  internal data class UiBuilderComponentPolicy(
    /** The record's `canonicalId` — the join back to the inventory. */
    val record: String = "",
    @SerialName("catalogId") val catalogId: String? = null,
    val displayName: String? = null,
    val canvas: String? = null,
    val nativeOnly: Boolean = false,
    val traits: List<String> = emptyList(),
    val excluded: String? = null,
    /**
     * The properties this component offers a design, or null to derive them from the record.
     *
     * A catalog's vocabulary is not its component's parameter list. `m3/button` offers `style`,
     * `selected` and `containerColor`; `androidx.compose.material3.Button` takes `onClick`,
     * `shape`, `colors`, `elevation`, `border`, `contentPadding` and `interactionSource`. Deriving
     * from the record produced the second, which is a different vocabulary rather than a different
     * spelling of the first — and made `onClick` REQUIRED, since it carries no default, so every
     * design that had ever placed a button failed validation.
     *
     * So a catalog that means to replace a synthesised shelf states this. Null keeps the derived
     * behaviour, which is right for a catalog whose components ARE their call sites.
     */
    val propertyCapabilities: List<UiBuilderPropertyPolicy>? = null,
    /**
     * The slots this component offers a design, or null to derive them from the record.
     *
     * Curated for the same reason properties are, and one level further: a catalog's slot is not
     * always its composable's parameter. `m3/list-item` offers `headline`, `supporting` and
     * `trailing`; `ListItem` takes `headlineContent`, `leadingContent`, `overlineContent`,
     * `supportingContent` and `trailingContent`. Deriving renamed three slots and invented two, and
     * because a component's role follows from whether it has any, it also turned `m3/switch` from a
     * Leaf into a Container by finding its `thumbContent`.
     *
     * The acceptance model is the other half. A derived slot accepts anything — no cardinality, no
     * `acceptedRoles`, no `acceptedTraits` — so the rules that stop a design putting a scaffold
     * inside a chip's label simply vanish.
     */
    val slotCapabilities: List<UiBuilderSlotPolicy>? = null,
    /**
     * The modifiers this component accepts, or null for the structural default.
     *
     * `ProductionUiBuilderRuntime` rejects any modifier a component does not declare, so the empty
     * list this used to pass meant no design could set `padding` on anything. It cannot be inferred
     * either: the frozen catalog gives `m3/icon` 17, `m3/text` 18 and `layout/box` 28, and the
     * difference is editorial — whether `fillMaxWidth` makes sense on an icon — not structural.
     */
    val modifierCapabilities: List<String>? = null,
  )

  /**
   * One property a catalog states, mirroring `PropertyCapabilityV1`.
   *
   * Declared here rather than reusing the protocol type because this is the AUTHORED shape: a
   * catalog writes it into `ui-builder.json`, and the protocol type is what the builder is served.
   * They agree field for field today; if the protocol gains a field the catalog cannot state, only
   * this one stays still.
   */
  /**
   * Why these three are `…Capabilities` and not `properties` / `slots` / `modifiers`.
   *
   * The generator's own `UiBuilderComponentPolicy` — compose-ai-tools, published in
   * `preview-discovery`, and the thing that WRITES the file this reads — already spells two of
   * those names for different types: a component's `slots` is a `Map<String, List<String>>` of
   * `@BuilderComponent` content hints, and a builtin's `properties` is a `List<JsonElement>`. Both
   * are empty in every catalog published today, so reusing the names would have decoded fine right
   * up until the first catalog annotated a slot, and then failed the whole file rather than one
   * field. Distinct names cost nothing and cannot collide.
   */
  /** One slot a catalog states, mirroring `SlotCapabilityV1`. See [UiBuilderPropertyPolicy]. */
  @Serializable
  internal data class UiBuilderSlotPolicy(
    val name: String,
    val cardinality: UiBuilderSlotCardinality = UiBuilderSlotCardinality(),
    val ordered: Boolean = false,
    val acceptedRoles: List<String> = emptyList(),
    val acceptedTraits: List<String> = emptyList(),
  )

  /** How many children a stated slot takes; `max` null is unbounded. */
  @Serializable internal data class UiBuilderSlotCardinality(val min: Int = 0, val max: Int? = null)

  @Serializable
  internal data class UiBuilderPropertyPolicy(
    val name: String,
    val jsonType: JsonElement,
    val required: Boolean = false,
    val allowedValues: List<JsonElement> = emptyList(),
    val notes: String = "",
  )
}
