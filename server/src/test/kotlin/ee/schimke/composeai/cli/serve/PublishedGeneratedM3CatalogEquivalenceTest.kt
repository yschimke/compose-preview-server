package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.BooleanValueV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `m3-catalog` gains and loses if `--ui-builder-published-catalogs` names it. Unlike the
 * hand-built [PublishedM3CatalogEquivalenceTest], this pair is generated (captured from
 * `:catalog:composePreviewDiscover` in yschimke/m3-catalog). The frozen `m3-catalog` capability
 * document is a hand-transcribed Jetcaster catalog (see `UI_BUILDER_CATALOG_CONTRACT.md`), so this
 * doesn't ask for equality; it pins three exact sets:
 * - which of the frozen twenty-five the real catalog can offer (twenty-four, the missing one named
 *   with a reason);
 * - what each of those offers, field by field, against the frozen vocabulary;
 * - what the real catalog adds (eighty-six).
 */
class PublishedGeneratedM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports =
    ExportCapabilitiesV1.Builder()
      .also {
        it.composeCode = true
        it.svg = false
        it.png = false
      }
      .build()

  private fun fixture(name: String) = File("../docs/design/fixtures/ui-builder/$name").readText()

  private val frozen: CatalogCapabilityV1 =
    json.decodeFromString(fixture("m3-catalog-capabilities-v1.json"))

  private val result: PublishedUiBuilderCatalog.Result.Composed by lazy {
    val record =
      json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-generated-record-v1.json"))
    val result =
      PublishedUiBuilderCatalog.compose(
        fixture("m3-catalog-generated-published-v1.json"),
        record,
        exports,
      )
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the generated pair must compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    result as PublishedUiBuilderCatalog.Result.Composed
  }

  private val composed: CatalogCapabilityV1
    get() = result.catalog

  /**
   * The record behind each published component, under the builder id a design names it with (the
   * map `ServeRunner` hands the export executor).
   */
  private val composedRecords: Map<String, ComponentRecord>
    get() = result.records

  /**
   * The frozen component the published catalog can't offer, and why. Asserted absent rather than
   * skipped, so if it starts composing this test says to move it.
   */
  private val knownAbsent =
    mapOf(
      "m3/snackbar-host" to
        "not a discovery gap. There is no SnackbarHost preview, deliberately: m3-catalog's " +
          "Snackbar.kt says the host is a dispatcher and the catalog composes snackbars " +
          "directly, so the record cannot carry a callable no sticker draws"
    )

  private fun frozenOwned() = frozen.components.filter { it.componentId.startsWith("m3/") }

  @Test
  fun `every component the frozen catalog owns is offered, or known absent for a stated reason`() {
    val composedIds = composed.components.map { it.componentId }.toSet()
    val missing = frozenOwned().map { it.componentId }.filterNot { it in composedIds }
    assertEquals(
      knownAbsent.keys.sorted(),
      missing.sorted(),
      "the set of frozen components the real catalog cannot offer has changed — see m3-catalog#317",
    )
  }

  /**
   * What the real catalog adds, as an exact set: each is a component a design could use, so changes
   * belong in a reviewed diff. Not asserted: that each exports (a separate question).
   */
  @Test
  fun `the components the real catalog adds are the reviewed set`() {
    val frozenIds = frozen.components.map { it.componentId }.toSet()
    val added = composed.components.map { it.componentId }.filterNot { it in frozenIds }.sorted()
    assertEquals(ADDED, added, "the set of components the real catalog adds has changed")
  }

  @Test
  fun `an offered component matches the frozen one field by field`() {
    val byId = composed.components.associateBy { it.componentId }
    val differences = mutableListOf<String>()
    for (expected in frozenOwned()) {
      if (expected.componentId in knownAbsent) continue
      val actual = byId[expected.componentId] ?: continue
      differences += compare(expected, actual)
    }
    assertEquals(
      REVIEWED_DIFFERENCES,
      differences.sorted(),
      "the composed shelf differs from the frozen one in a way nobody has reviewed",
    )
  }

  @Test
  fun `record conventions add the reviewed vocabulary`() {
    val frozenById = frozen.components.associateBy { it.componentId }
    val additions =
      composed.components
        .mapNotNull { component ->
          val before = frozenById[component.componentId] ?: return@mapNotNull null
          val added =
            component.properties.map { it.name }.toSet() - before.properties.map { it.name }
          added.takeIf { it.isNotEmpty() }?.let { component.componentId to it.sorted() }
        }
        .toMap()

    assertEquals(
      mapOf(
        "m3/button" to listOf("shape"),
        "m3/center-aligned-top-app-bar" to listOf("expandedHeightDp"),
        "m3/dialog" to listOf("iconContentColor", "shape", "textContentColor", "titleContentColor"),
        "m3/horizontal-floating-toolbar" to
          listOf("collapsedShadowElevationDp", "expandedShadowElevationDp", "shape"),
        "m3/icon" to listOf("tint"),
        "m3/list-item" to listOf("shadowElevationDp", "tonalElevationDp"),
        "m3/navigation-suite-item" to listOf("enabled"),
        "m3/navigation-suite-scaffold" to listOf("containerColor", "contentColor"),
        "m3/primary-scrollable-tab-row" to
          listOf(
            "containerColor",
            "contentColor",
            "edgePaddingDp",
            "minTabWidthDp",
            "selectedTabIndex",
          ),
        "m3/primary-tab-row" to listOf("containerColor", "contentColor", "selectedTabIndex"),
        "m3/progress-indicator" to listOf("color", "gapSizeDp", "trackColor"),
        "m3/search-bar" to listOf("shadowElevationDp", "shape"),
        "m3/search-input-field" to listOf("shape"),
        "m3/surface" to listOf("color", "shadowElevationDp", "shape"),
        "m3/tab" to listOf("enabled", "selectedContentColor", "unselectedContentColor"),
        "m3/text-field" to listOf("shape"),
      ),
      additions,
    )
  }

  /** The same comparison [PublishedM3CatalogEquivalenceTest] makes, for the same reasons. */
  /**
   * The two fields `compare()` leaves out: `wasm` (what the shelf claims about the canvas) and
   * `code` (what an export calls).
   *
   * The canvas is drawn by `UiBuilderRenderer.RenderNode`, a `when (componentId)` over literal ids;
   * it never reads `adapterStatus` / `platformSupported`. So a missing policy `canvas` doesn't
   * blank the canvas, it makes the shelf misreport components as unsupported. m3-catalog#327
   * declares `canvas` for the components the renderer handles, so this is now `emptyList()`. The
   * real canvas gap is the added components with no renderer case (yschimke/m3-catalog#324), not
   * measured here.
   *
   * The `code` differences are asserted as such: the composed symbol is the FQN of the frozen
   * simple name, and composed imports are the frozen ones minus sibling variants the transcription
   * merged.
   */
  @Test
  fun `the published shelf keeps a drawn component's canvas status, and spells its calls out`() {
    val composedById = composed.components.associateBy { it.componentId }
    val shared = frozen.components.filter { it.componentId in composedById }

    val lostCanvas =
      shared
        .filter { it.wasm.platformSupported == JsonPrimitive(true) }
        .map { it.componentId }
        .filter { composedById.getValue(it).wasm.platformSupported == JsonPrimitive(false) }
        .sorted()
    // The three components compose-ui-builder#241 added have no `canvas` in m3-catalog's policy
    // yet; that policy change empties this list.
    assertEquals(
      listOf(
        "m3/navigation-suite-item",
        "m3/navigation-suite-scaffold",
        "m3/primary-scrollable-tab-row",
      ),
      lostCanvas,
      "a drawn component lost its `wasm` status — the shelf is reporting a component " +
        "UNSUPPORTED that the builder draws, which is a surface lying about another surface",
    )

    for (component in shared) {
      val got = composedById.getValue(component.componentId)
      val frozenSymbol = component.code?.symbol ?: continue
      val composedSymbol = got.code?.symbol
      // `m3/search-input-field` is an object member (`SearchBarDefaults.InputField`); the record
      // knows only the callable's name. Same component, uncallable; the call-site test reports it.
      if (component.componentId == "m3/search-input-field") continue
      assertEquals(
        frozenSymbol,
        composedSymbol?.substringAfterLast('.'),
        "${component.componentId} composes a call to a different symbol, not the same one spelled " +
          "in full",
      )
      assertTrue(
        composedSymbol in got.code?.imports.orEmpty(),
        "${component.componentId} names a symbol it does not import: " +
          "$composedSymbol not in ${got.code?.imports}",
      )
    }

    // Import lists differ both ways: the frozen file merged sibling variants under one entry, while
    // the composed entry imports its one callable plus what the recorded call needs (e.g.
    // `rememberTextFieldState`).
    val importsDiffer =
      shared
        .filter {
          composedById.getValue(it.componentId).code?.imports?.sorted() !=
            it.code?.imports?.sorted()
        }
        .map { it.componentId }
        .sorted()
    assertEquals(
      IMPORTS_DIFFER,
      importsDiffer,
      "the set of components whose composed imports differ from the frozen ones has changed",
    )
  }

  private fun compare(
    expected: ComponentCapabilityV1,
    actual: ComponentCapabilityV1,
  ): List<String> {
    val id = expected.componentId
    val out = mutableListOf<String>()
    fun check(field: String, want: Any?, got: Any?) {
      if (want != got) out += "$id.$field: frozen=$want composed=$got"
    }
    check("displayName", expected.displayName, actual.displayName)
    check("role", expected.role, actual.role)
    check("traits", expected.traits.sorted(), actual.traits.sorted())
    check("slots", expected.slots.map { it.name }.sorted(), actual.slots.map { it.name }.sorted())
    val wantSlots = expected.slots.associateBy { it.name }
    val gotSlots = actual.slots.associateBy { it.name }
    for ((name, w) in wantSlots) {
      val g = gotSlots[name] ?: continue
      check("slots[$name].cardinality.min", w.cardinality.min, g.cardinality.min)
      check("slots[$name].cardinality.max", w.cardinality.max, g.cardinality.max)
      check("slots[$name].ordered", w.ordered, g.ordered)
      check("slots[$name].acceptedRoles", w.acceptedRoles.sorted(), g.acceptedRoles.sorted())
      check("slots[$name].acceptedTraits", w.acceptedTraits.sorted(), g.acceptedTraits.sorted())
    }
    check(
      "modifierCapabilities",
      expected.modifierCapabilities.sorted(),
      actual.modifierCapabilities.sorted(),
    )
    // `wasm` and `code` differ for every component systematically, so they get their own test below
    // rather than burying the fields here.
    val builderOwned = builderOwnedProperties[id].orEmpty()
    val want = expected.properties.filterNot { it.name in builderOwned }.associateBy { it.name }
    val got = actual.properties.associateBy { it.name }
    check("missingProperties", emptyList<String>(), (want.keys - got.keys).sorted())
    for ((name, w) in want) {
      val g = got[name] ?: continue
      check("properties[$name].jsonType", w.jsonType, g.jsonType)
      check("properties[$name].required", w.required, g.required)
      allowed(name, w, g)?.let { out += it }
    }
    return out
  }

  /**
   * Properties the builder owns on `m3/icon` that m3-catalog doesn't publish (Material Symbols
   * naming and axes). Safe one way only: the frozen shelf may add properties (designs still
   * validate), but the composed catalog offering one the builder can't handle is still compared
   * strictly (`got` is never filtered). Cost: designs naming an icon can't be authored against
   * m3-catalog's shelf until it publishes these.
   */
  private val builderOwnedProperties =
    mapOf(
      "m3/icon" to
        setOf(
          "iconName",
          "iconStyle",
          "iconFill",
          "iconWeight",
          "iconGrade",
          "iconOpticalSize",
          "iconAutoMirror",
        )
    )

  /**
   * Properties whose allowed values are a generated inventory rather than an authored enumeration.
   * See [allowed].
   */
  private val generatedInventory = setOf("iconKey")

  /**
   * The one field where equality is wrong: `m3/icon`.`iconKey` is the generated Material icon set,
   * pinned independently in each repository. The meaningful question is containment: does the
   * published catalog offer an icon the builder can't draw? Stable as the inventory grows, and
   * names only the offending values.
   */
  private fun allowed(
    name: String,
    want: PropertyCapabilityV1,
    got: PropertyCapabilityV1,
  ): String? {
    val field = "properties[$name].allowedValues"
    if (name !in generatedInventory) {
      return if (want.allowedValues == got.allowedValues) null
      else "$field: frozen=${want.allowedValues} composed=${got.allowedValues}"
    }
    val undrawable = got.allowedValues.filterNot { it in want.allowedValues }
    return if (undrawable.isEmpty()) null
    else
      "$field: composed offers ${undrawable.size} value(s) the frozen shelf does not: $undrawable"
  }

  /**
   * The builder's own vocabulary survives the swap: all of the frozen catalog's non-`m3/` entries
   * are in a donor namespace, so (unlike `remote-m3`) nothing borrowed is lost.
   */
  @Test
  fun `a published m3 catalog is still served the builder's own vocabulary`() {
    val served =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = linkedSetOf("m3-catalog"),
          published = mapOf("m3-catalog" to composed),
        )
        .listCatalogs()
        .single()
    val builderOwned =
      frozen.components.map { it.componentId }.filterNot { it.startsWith("m3/") }.sorted()
    val servedIds = served.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      builderOwned.filterNot { it in servedIds },
      "a published catalog lost builder components the synthesised one offers",
    )
  }

  /**
   * Every group the components use must be in the menu order, or `UiBuilderEditorState` appends it
   * alphabetically after the named ones.
   */
  @Test
  fun `the menu order covers the groups the shelf actually uses`() {
    val menu =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject
    val order = (menu["groupOrder"] as JsonArray).map { it.jsonPrimitive.content }
    val used =
      menu["components"]!!
        .jsonObject
        .values
        .mapNotNull { it.jsonObject["group"]?.jsonPrimitive?.content }
        .distinct()
        .sorted()
    assertEquals(
      emptyList(),
      used.filterNot { it in order },
      "groups the shelf uses that the order does not name — the editor sorts these alphabetically " +
        "after every ordered group, so the catalog's stated order is not the one a person sees",
    )
    assertEquals(
      UNUSED_GROUPS,
      order.filterNot { it in used }.sorted(),
      "groups the order names that no component is on",
    )
  }

  /**
   * A component with no menu entry falls under a generic role heading in the insert panel, where
   * nobody finds it. The remaining ones carry no catalog id, so only `ui-builder.policy.json` can
   * place them (m3-catalog#323).
   */
  @Test
  fun `every component is on a shelf, or is one nothing declares`() {
    val menu =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["componentMenu"]!!
        .jsonObject["components"]!!
        .jsonObject
    val unshelved = composed.components.map { it.componentId }.filterNot { it in menu }.sorted()
    assertEquals(UNSHELVED.keys.sorted(), unshelved, "the set of unshelved components has changed")
  }

  /**
   * The four components whose shelf nobody had chosen: m3-catalog#327 gave `m3/icon` and
   * `m3/surface` new headings and excluded `Sticker` and `MaterialExpressiveTheme` (frame and theme
   * scope, not components). They aren't served, but still appear on the menu: fixed in
   * yschimke/compose-ai-tools#5378, not yet released. When the fixture is re-captured, the last
   * assertion fails and is deleted.
   */
  @Test
  fun `an excluded component is not served, though the menu still lists it`() {
    val semantics =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject
    val excluded =
      semantics["components"]!!
        .jsonObject
        .filterValues { component ->
          component.jsonObject["excluded"].let { it != null && it !is JsonNull }
        }
        .keys
        .sorted()
    assertEquals(EXCLUDED, excluded, "the set of components the catalog excludes has changed")

    val offered = composed.components.map { it.componentId }.toSet()
    assertEquals(
      emptyList(),
      excluded.filter { it in offered },
      "an excluded component is being served — the published reason says the catalog refuses it",
    )

    val menu = semantics["componentMenu"]!!.jsonObject["components"]!!.jsonObject
    assertEquals(
      EXCLUDED,
      excluded.filter { it in menu },
      "an excluded component left the menu — compose-ai-tools#5378 has reached this catalog, so " +
        "delete this assertion rather than shortening it",
    )
  }

  /**
   * Which offered components have no call site. `signatureKnown` is set for all of them, but a
   * recovered signature isn't a callable; the record's `code.call` / `code.refusedReason` says so
   * directly. The reasons split into structural ones (not public, object member, scope receiver,
   * type parameters) and a required parameter of a type no design value becomes. Pinned as a
   * reviewed set, so discovery learning one shortens the list. `m3/current-scheme` is a companion
   * property, pinned separately.
   */
  @Test
  fun `every component the catalog offers has a recorded call site, or a stated reason`() {
    val record =
      json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-generated-record-v1.json"))
    val byCanonicalId = record.components.associateBy { it.canonicalId }
    val recordOf =
      json
        .parseToJsonElement(fixture("m3-catalog-generated-published-v1.json"))
        .jsonObject["statusSemantics"]!!
        .jsonObject["components"]!!
        .jsonObject
        .mapValues { (_, v) -> v.jsonObject["record"]!!.jsonPrimitive.content }

    val offered = composed.components.map { it.componentId }
    val unwritable =
      offered
        .mapNotNull { id ->
          // Not silently skipped: a published component whose `record` names an id missing from the
          // record file is reported.
          val component =
            requireNotNull(byCanonicalId[recordOf[id]]) {
              "published component `$id` names record `${recordOf[id]}`, which the component " +
                "record does not carry"
            }
          val reason =
            when {
              !component.signatureKnown -> "the signature was never recovered"
              component.code?.call == null ->
                component.code?.refusedReason ?: "discovery recorded no call site"
              else -> null
            }
          reason?.let { id to it }
        }
        .sortedBy { it.first }
        .toMap()
    assertEquals(
      UNCALLABLE,
      unwritable,
      "the set of shelf components with no recorded call site has changed — teaching discovery " +
        "one of these shortens the list, and a new entry is a component that became insertable " +
        "and unexportable",
    )

    val noArguments =
      offered.filter { byCanonicalId[recordOf[it]]?.parameters.orEmpty().isEmpty() }.sorted()
    assertEquals(
      listOf("m3/current-scheme"),
      noArguments,
      "a component with no recorded parameter takes no argument a design can set",
    )
  }

  /**
   * How much of the published m3 shelf the generator can write, per component: a screen whose root
   * is the component, with a value for every required property. Possible since the record is
   * aliased with the published ids (compose-preview-server#694). Refusals are pinned with the
   * generator's first reason.
   */
  @Test
  fun `what the published m3 shelf can export is the reviewed set`() {
    val record =
      json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-generated-record-v1.json"))
    val executor =
      ScreenGeneratorComposeExportExecutor(
        { systemId ->
          if (systemId == "m3-catalog") ComponentRecordSource.Lookup.Found(record)
          else ComponentRecordSource.Lookup.Unconfigured
        },
        "generated.uibuilder",
        publishedComponents = { systemId ->
          if (systemId == "m3-catalog") composedRecords else emptyMap()
        },
      )

    val refused = sortedMapOf<String, String>()
    val offered = composed.components.filter { it.componentId.startsWith("m3/") }
    for (component in offered) {
      val generated = executor.generate(screenAround(component))
      if (generated is ScreenGeneratorComposeExportExecutor.Generated.Refused) {
        val reason = generated.reasons.first()
        refused[component.componentId] = reason.substringAfter("has no call site: ", reason)
      }
    }

    assertEquals(
      108,
      offered.size,
      "the number of published m3 components measured for export has changed",
    )
    assertEquals(
      M3_EXPORT_REFUSALS,
      refused.toMap(),
      "the set of published m3 components the generator cannot write has changed",
    )

    // Every allowed value of every enumerated required property, not just the first: e.g.
    // `m3/text-field` passed on `filled` while `outlined` refused due to a wrong callable in the
    // variant table. Asserted empty, since a component exporting on one value and refusing another
    // is always a defect.
    val variantRefusals = sortedMapOf<String, String>()
    for (component in offered) {
      if (component.componentId in M3_EXPORT_REFUSALS) continue
      for (property in component.properties.filter { it.required }) {
        val values =
          property.allowedValues.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.drop(1)
        for (value in values) {
          val generated = executor.generate(screenAround(component, mapOf(property.name to value)))
          if (generated is ScreenGeneratorComposeExportExecutor.Generated.Refused) {
            variantRefusals["${component.componentId}.${property.name}=$value"] =
              generated.reasons.first()
          }
        }
      }
    }
    assertEquals(
      emptyList(),
      variantRefusals.keys.toList(),
      "a component that exports on one allowed value refuses on another: $variantRefusals",
    )

    // `m3/time-picker`'s properties reach a state factory; pinned as the emitted call, since "no
    // longer refuses" would also describe one that dropped the hour.
    val picker = offered.single { it.componentId == "m3/time-picker" }
    val emitted = executor.generate(screenAround(picker))
    assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(emitted)
    assertContains(
      emitted.source,
      "TimePicker(state = rememberTimePickerState(initialHour = 1, initialMinute = 1, " +
        "is24Hour = true))",
      message = "a time picker's hour and minute reach `rememberTimePickerState`",
    )
  }

  /**
   * `BuilderTimePicker` transforms properties before the state factory, and the export must match:
   * `is24Hour` defaults to `true` on the canvas (Material's default is the device locale, so
   * omitting it isn't neutral), and `hour` / `minute` are clamped (the validator only checks
   * integer type).
   */
  @Test
  fun `a time picker's state carries the canvas's default and its clamp`() {
    val record =
      json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-generated-record-v1.json"))
    val executor =
      ScreenGeneratorComposeExportExecutor(
        { systemId ->
          if (systemId == "m3-catalog") ComponentRecordSource.Lookup.Found(record)
          else ComponentRecordSource.Lookup.Unconfigured
        },
        "generated.uibuilder",
        publishedComponents = { systemId ->
          if (systemId == "m3-catalog") composedRecords else emptyMap()
        },
      )
    val picker = composed.components.single { it.componentId == "m3/time-picker" }

    val out = screenAround(picker)
    val subject = out.nodes.getValue("subject")
    val outOfRange =
      out.copy(
        nodes =
          linkedMapOf(
            "subject" to
              subject.copy(
                properties =
                  subject.properties +
                    mapOf("hour" to IntegerValueV1(25), "minute" to IntegerValueV1(-1))
              )
          )
      )
    val clamped = executor.generate(outOfRange)
    assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(clamped)
    assertContains(
      clamped.source,
      "rememberTimePickerState(initialHour = 23, initialMinute = 0, is24Hour = true)",
      message = "an hour outside 0..23 reaches Material clamped, as the canvas clamps it",
    )

    // And an authored `is24Hour` wins over the canvas's default rather than being overwritten by
    // it — the default is a fallback, not a constant.
    val twelveHour =
      out.copy(
        nodes =
          linkedMapOf(
            "subject" to
              subject.copy(
                properties = subject.properties + mapOf("is24Hour" to BooleanValueV1(false))
              )
          )
      )
    val authored = executor.generate(twelveHour)
    assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(authored)
    assertContains(
      authored.source,
      "is24Hour = false",
      message = "an authored `is24Hour` is what reaches the state factory",
    )
  }

  /**
   * The browser's export lane (`ScreenExportGate`) and the server's, asked about all 108 published
   * components. The gate used the build-time embedded record, missing most of them, so the pane
   * said "no component" for Kotlin the export wrote.
   * [ScreenGeneratorComposeExportExecutor.exportRecord] gives the browser the executor's record;
   * asserted as per-component verdict pairs (either mismatch direction is a defect). Source isn't
   * compared (screen name and header differ).
   */
  @Test
  fun `the code pane and the export agree about every published component`() {
    val record =
      json.decodeFromString<ComponentRecordFile>(fixture("m3-catalog-generated-record-v1.json"))
    val executor =
      ScreenGeneratorComposeExportExecutor(
        { systemId ->
          if (systemId == "m3-catalog") ComponentRecordSource.Lookup.Found(record)
          else ComponentRecordSource.Lookup.Unconfigured
        },
        "generated.uibuilder",
        publishedComponents = { systemId ->
          if (systemId == "m3-catalog") composedRecords else emptyMap()
        },
      )
    // What the route hands the editor, from the executor that serves the export.
    val served =
      assertNotNull(
        executor.exportRecord("m3-catalog"),
        "the host serves no record for a catalog it exports",
      )
    val offered = composed.components.filter { it.componentId.startsWith("m3/") }

    val disagreed = sortedMapOf<String, String>()
    for (component in offered) {
      val document = screenAround(component)
      val exported = executor.generate(document)
      val pane = ScreenExportGate.refusals(document, served)
      val exportWrites = exported is ScreenGeneratorComposeExportExecutor.Generated.Emitted
      if (exportWrites != pane.isEmpty()) {
        disagreed[component.componentId] =
          if (exportWrites) "the export writes it and the pane refuses: ${pane.first()}"
          else
            "the pane writes it and the export refuses: " +
              (exported as ScreenGeneratorComposeExportExecutor.Generated.Refused).reasons.first()
      }
    }

    assertEquals(
      emptyMap(),
      disagreed.toMap(),
      "the browser and the server disagree about what this shelf exports",
    )
  }

  /**
   * The record the editor was built with, asked the same question: the gap that serving the record
   * fixes. An exact set like [M3_EXPORT_REFUSALS]; each id is a component that reads as missing in
   * the editor while the export writes it.
   */
  @Test
  fun `the embedded record cannot answer for most of the published shelf`() {
    val authored = ExportRecords.m3Catalog()
    val offered = composed.components.filter { it.componentId.startsWith("m3/") }

    val unknown =
      offered
        .map { it.componentId }
        .filter { id ->
          ScreenExportGate.refusals(
              screenAround(offered.single { it.componentId == id }),
              authored,
            )
            .any { it == "no component `$id` in this catalog" }
        }

    assertEquals(
      76,
      unknown.size,
      "how much of the published m3 shelf the record embedded in the editor cannot name has " +
        "changed: $unknown",
    )
    // `m3/badge` is the plainest case: one callable, no arguments, exported fine, invisible to the
    // editor.
    assertContains(unknown, "m3/badge")
  }

  /**
   * A screen whose root is [component], with every required property, using [choose]'s value where
   * given instead of the first allowed one.
   */
  private fun screenAround(
    component: ComponentCapabilityV1,
    choose: Map<String, String> = emptyMap(),
  ): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "m3-export-parity",
      title = "Export parity",
      revision = 1,
      catalogPin =
        CatalogReferenceV1(
          systemId = "m3-catalog",
          catalogRevision = "candidate",
          capabilityDigest = "candidate",
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
      // The subject is the root; a `layout/column` wrapper is a builder donor component not in the
      // record, so everything would refuse for the wrapper.
      roots = listOf("subject"),
      nodes =
        linkedMapOf(
          "subject" to
            DesignNodeV1(
              id = "subject",
              componentId = component.componentId,
              properties =
                component.properties
                  .filter { it.required }
                  .associate { property ->
                    property.name to
                      (choose[property.name]?.let { EnumValueV1(it) } ?: authored(property))
                  },
            )
        ),
    )

  /** A value of the declared `jsonType`, or the first allowed value where the shelf states one. */
  private fun authored(property: PropertyCapabilityV1): UiValueV1 {
    property.allowedValues?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let {
      return EnumValueV1(it)
    }
    val declared =
      (property.jsonType as? JsonPrimitive)?.contentOrNull
        ?: (property.jsonType as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
    return when (declared) {
      "boolean" -> BooleanValueV1(true)
      "integer" -> IntegerValueV1(1)
      "number" -> DecimalValueV1(0.5)
      else -> StringValueV1("value")
    }
  }

  private companion object {
    /**
     * The published m3 components the generator can't write, with each first reason; the test fails
     * until this shrinks when the generator learns one. Most are discovery's "no call site"
     * judgements (upstream API shapes). `m3/date-picker` is ours: `selectedDate` configures
     * `rememberDatePickerState(initialSelectedDateMillis = …)` and needs a date-to-`Long`
     * conversion this projection lacks. `m3/time-picker` was fixed the same way via `STATE_BUNDLES`
     * and the variant table.
     */
    val M3_EXPORT_REFUSALS =
      mapOf(
        "m3/adaptive-sticker" to "not public or internal, so a generated file cannot call it",
        "m3/animated-pane" to
          "declares type parameters that a call omitting defaulted arguments cannot infer",
        "m3/app-bar-with-search" to
          "no placeholder can be written for required parameter `state: SearchBarState`",
        "m3/centered-track" to
          "a member of androidx.compose.material3.SliderDefaults, so a call site needs an instance of it",
        "m3/date-picker" to
          "node `subject`.`mode` is the enum value `picker`, and nothing maps this catalog property's values to Kotlin members",
        "m3/elevated-leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/elevated-trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/horizontal-centered-hero-carousel" to
          "no placeholder can be written for required parameter `state: CarouselState`",
        "m3/horizontal-multi-browse-carousel" to
          "no placeholder can be written for required parameter `state: CarouselState`",
        "m3/horizontal-uncontained-carousel" to
          "no placeholder can be written for required parameter `state: CarouselState`",
        "m3/leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/list-detail-pane-scaffold" to
          "no placeholder can be written for required parameter `directive: PaneScaffoldDirective`",
        "m3/outlined-leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/outlined-trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/range-slider" to
          "no placeholder can be written for required parameter `value: ClosedFloatingPointRange<Float>`",
        "m3/search-bar" to
          "no placeholder can be written for required parameter `state: SearchBarState`",
        "m3/search-input-field" to
          "a member of androidx.compose.material3.SearchBarDefaults, so a call site needs an instance of it",
        "m3/segmented-button" to
          "declared on androidx.compose.material3.MultiChoiceSegmentedButtonRowScope, so a call site needs that scope around it",
        "m3/supporting-pane-scaffold" to
          "no placeholder can be written for required parameter `directive: PaneScaffoldDirective`",
        "m3/thumb" to
          "a member of androidx.compose.material3.SliderDefaults, so a call site needs an instance of it",
        "m3/tonal-leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/tonal-trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/track" to
          "a member of androidx.compose.material3.SliderDefaults, so a call site needs an instance of it",
        "m3/trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/tri-state-checkbox" to
          "no placeholder can be written for required parameter `state: ToggleableState`",
      )

    /** Components whose composed import list differs from the frozen one (see the test above). */
    val IMPORTS_DIFFER =
      listOf(
        "m3/button",
        "m3/card",
        "m3/icon-button",
        "m3/progress-indicator",
        "m3/search-input-field",
        "m3/text-field",
        "m3/time-picker",
      )

    /**
     * Offered components discovery couldn't write a call site for, with its reason (see the test
     * above).
     */
    val UNCALLABLE =
      mapOf(
        "m3/adaptive-sticker" to "not public or internal, so a generated file cannot call it",
        "m3/animated-pane" to
          "declares type parameters that a call omitting defaulted arguments cannot infer",
        "m3/app-bar-with-search" to
          "no placeholder can be written for required parameter `state: SearchBarState`",
        "m3/centered-track" to
          "a member of androidx.compose.material3.SliderDefaults, so a call site needs an instance of it",
        "m3/elevated-leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/elevated-trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/horizontal-centered-hero-carousel" to
          "no placeholder can be written for required parameter `state: CarouselState`",
        "m3/horizontal-multi-browse-carousel" to
          "no placeholder can be written for required parameter `state: CarouselState`",
        "m3/horizontal-uncontained-carousel" to
          "no placeholder can be written for required parameter `state: CarouselState`",
        "m3/icon" to
          "no placeholder can be written for required parameter `imageVector: ImageVector`",
        "m3/leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/list-detail-pane-scaffold" to
          "no placeholder can be written for required parameter `directive: PaneScaffoldDirective`",
        "m3/outlined-leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/outlined-trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/range-slider" to
          "no placeholder can be written for required parameter `value: ClosedFloatingPointRange<Float>`",
        "m3/search-bar" to
          "no placeholder can be written for required parameter `state: SearchBarState`",
        "m3/search-input-field" to
          "a member of androidx.compose.material3.SearchBarDefaults, so a call site needs an instance of it",
        "m3/segmented-button" to
          "declared on androidx.compose.material3.MultiChoiceSegmentedButtonRowScope, so a call site needs that scope around it",
        "m3/supporting-pane-scaffold" to
          "no placeholder can be written for required parameter `directive: PaneScaffoldDirective`",
        "m3/thumb" to
          "a member of androidx.compose.material3.SliderDefaults, so a call site needs an instance of it",
        "m3/tonal-leading-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/tonal-trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/track" to
          "a member of androidx.compose.material3.SliderDefaults, so a call site needs an instance of it",
        "m3/trailing-button" to
          "a member of androidx.compose.material3.SplitButtonDefaults, so a call site needs an instance of it",
        "m3/tri-state-checkbox" to
          "no placeholder can be written for required parameter `state: ToggleableState`",
      )

    /** The components the real catalog adds (see the test above). */
    val ADDED =
      listOf(
        "m3/adaptive-sticker",
        "m3/animated-pane",
        "m3/app-bar-with-search",
        "m3/assist-chip",
        "m3/badge",
        "m3/bottom-app-bar",
        "m3/centered-track",
        "m3/circular-progress-indicator",
        "m3/circular-wavy-progress-indicator",
        "m3/contained-loading-indicator",
        "m3/current-scheme",
        "m3/date-picker-dialog",
        "m3/date-range-picker",
        "m3/docked-search-bar",
        "m3/elevated-assist-chip",
        "m3/elevated-button",
        "m3/elevated-card",
        "m3/elevated-filter-chip",
        "m3/elevated-leading-button",
        "m3/elevated-suggestion-chip",
        "m3/elevated-toggle-button",
        "m3/elevated-trailing-button",
        "m3/extended-floating-action-button",
        "m3/filled-icon-button",
        "m3/filled-tonal-button",
        "m3/filled-tonal-icon-button",
        "m3/floating-action-button",
        "m3/horizontal-centered-hero-carousel",
        "m3/horizontal-multi-browse-carousel",
        "m3/horizontal-uncontained-carousel",
        "m3/input-chip",
        "m3/large-extended-floating-action-button",
        "m3/large-floating-action-button",
        "m3/large-top-app-bar",
        "m3/leading-button",
        "m3/linear-wavy-progress-indicator",
        "m3/list-detail-pane-scaffold",
        "m3/loading-indicator",
        "m3/medium-extended-floating-action-button",
        "m3/medium-floating-action-button",
        "m3/medium-top-app-bar",
        "m3/multi-choice-segmented-button-row",
        "m3/navigation-rail",
        "m3/navigation-rail-item",
        "m3/outlined-button",
        "m3/outlined-card",
        "m3/outlined-icon-button",
        "m3/outlined-leading-button",
        "m3/outlined-text-field",
        "m3/outlined-toggle-button",
        "m3/outlined-trailing-button",
        "m3/provide-text-style",
        "m3/range-slider",
        "m3/secondary-scrollable-tab-row",
        "m3/secondary-tab-row",
        "m3/segmented-button",
        "m3/short-navigation-bar",
        "m3/short-navigation-bar-item",
        "m3/single-choice-segmented-button-row",
        "m3/small-extended-floating-action-button",
        "m3/small-floating-action-button",
        "m3/snackbar",
        "m3/split-button-layout",
        "m3/suggestion-chip",
        "m3/supporting-pane-scaffold",
        "m3/text-button",
        "m3/thumb",
        "m3/time-input",
        "m3/toggle-button",
        "m3/tonal-leading-button",
        "m3/tonal-toggle-button",
        "m3/tonal-trailing-button",
        "m3/top-app-bar",
        "m3/track",
        "m3/trailing-button",
        "m3/tri-state-checkbox",
        "m3/vertical-divider",
        "m3/vertical-floating-toolbar",
        "m3/vertical-slider",
        "m3/wide-navigation-rail",
        "m3/wide-navigation-rail-item",
      )

    /**
     * Reviewed differences between an offered component and the frozen vocabulary.
     * `m3/search-input-field`'s query: the frozen record also accepts a literal string;
     * m3-catalog's policy still says `object` (narrower, not wrong).
     */
    val REVIEWED_DIFFERENCES =
      listOf(
        // The three components compose-ui-builder#241 added; m3-catalog's policy doesn't describe
        // them yet, so each line is removed when it does.
        "m3/navigation-suite-item.displayName: frozen=Navigation item composed=NavigationSuiteItem",
        "m3/navigation-suite-item.modifierCapabilities: frozen=[align, alignHorizontal, alignVertical, alpha, aspectRatio, height, heightIn, offset, padding, rotate, scale, testTag, weight, width, widthIn, zIndex] composed=[align, alignHorizontal, alignVertical, alpha, aspectRatio, background, border, fillMaxHeight, fillMaxSize, fillMaxWidth, height, heightIn, offset, padding, rotate, scale, shadow, size, testTag, verticalScroll, weight, width, widthIn, wrapContentSize, zIndex]",
        "m3/navigation-suite-item.properties[selected].jsonType: frozen=[\"boolean\",\"string\",\"object\"] composed=\"boolean\"",
        "m3/navigation-suite-item.slots: frozen=[icon, label] composed=[badge, icon, label]",
        "m3/navigation-suite-item.slots[icon].acceptedRoles: frozen=[Leaf] composed=[]",
        "m3/navigation-suite-item.slots[icon].cardinality.max: frozen=1 composed=null",
        "m3/navigation-suite-item.slots[icon].cardinality.min: frozen=1 composed=0",
        "m3/navigation-suite-item.slots[icon].ordered: frozen=false composed=true",
        "m3/navigation-suite-item.slots[label].acceptedRoles: frozen=[Leaf] composed=[]",
        "m3/navigation-suite-item.slots[label].acceptedTraits: frozen=[TextContent] composed=[]",
        "m3/navigation-suite-item.slots[label].cardinality.max: frozen=1 composed=null",
        "m3/navigation-suite-item.slots[label].ordered: frozen=false composed=true",
        "m3/navigation-suite-item.traits: frozen=[NavigationItem, SelectionControl] composed=[]",
        "m3/navigation-suite-scaffold.displayName: frozen=Navigation suite composed=NavigationSuiteScaffold",
        "m3/navigation-suite-scaffold.modifierCapabilities: frozen=[align, alignHorizontal, alignVertical, alpha, aspectRatio, background, border, fillMaxHeight, fillMaxSize, height, heightIn, offset, padding, rotate, scale, shadow, size, testTag, weight, width, widthIn, wrapContentSize, zIndex] composed=[align, alignHorizontal, alignVertical, alpha, aspectRatio, background, border, fillMaxHeight, fillMaxSize, fillMaxWidth, height, heightIn, offset, padding, rotate, scale, shadow, size, testTag, verticalScroll, weight, width, widthIn, wrapContentSize, zIndex]",
        "m3/navigation-suite-scaffold.role: frozen=Scaffold composed=Container",
        "m3/navigation-suite-scaffold.slots: frozen=[content, navigationItems, primaryAction] composed=[content, navigationItems, primaryActionContent]",
        "m3/navigation-suite-scaffold.slots[content].acceptedRoles: frozen=[Container, Leaf, Scaffold] composed=[]",
        "m3/navigation-suite-scaffold.slots[content].cardinality.min: frozen=1 composed=0",
        "m3/navigation-suite-scaffold.slots[navigationItems].acceptedRoles: frozen=[Container] composed=[]",
        "m3/navigation-suite-scaffold.slots[navigationItems].acceptedTraits: frozen=[NavigationItem] composed=[]",
        "m3/navigation-suite-scaffold.slots[navigationItems].cardinality.min: frozen=1 composed=0",
        "m3/navigation-suite-scaffold.traits: frozen=[AdaptiveNavigation, ScreenContent] composed=[]",
        "m3/primary-scrollable-tab-row.displayName: frozen=Scrollable tab row composed=PrimaryScrollableTabRow",
        "m3/primary-scrollable-tab-row.missingProperties: frozen=[] composed=[selectedIndex]",
        "m3/primary-scrollable-tab-row.modifierCapabilities: frozen=[align, alignHorizontal, alignVertical, alpha, aspectRatio, fillMaxWidth, height, heightIn, offset, padding, rotate, scale, testTag, weight, width, widthIn, zIndex] composed=[align, alignHorizontal, alignVertical, alpha, aspectRatio, background, border, fillMaxHeight, fillMaxSize, fillMaxWidth, height, heightIn, offset, padding, rotate, scale, shadow, size, testTag, verticalScroll, weight, width, widthIn, wrapContentSize, zIndex]",
        "m3/primary-scrollable-tab-row.slots: frozen=[tabs] composed=[divider, indicator, tabs]",
        "m3/primary-scrollable-tab-row.slots[tabs].acceptedRoles: frozen=[Container] composed=[]",
        "m3/primary-scrollable-tab-row.slots[tabs].acceptedTraits: frozen=[TabItem] composed=[]",
        "m3/primary-scrollable-tab-row.slots[tabs].cardinality.min: frozen=1 composed=0",
        "m3/primary-scrollable-tab-row.traits: frozen=[OrderedContent, TabBar] composed=[]",
        "m3/search-input-field.properties[value].jsonType: frozen=[\"object\",\"string\"] " +
          "composed=\"object\"",
      )

    /** Components no sticker declares, so no catalog id gives them a shelf; see the test above. */
    val UNSHELVED =
      mapOf(
        "m3/adaptive-sticker" to "the catalog's own sticker frame, drawn by no sticker of its own",
        "m3/animated-pane" to "an adaptive-layout pane; the catalog has no section for one",
        "m3/list-detail-pane-scaffold" to "the same, and the frozen shelf calls it a Scaffold",
        "m3/navigation-suite-item" to "the same",
        "m3/navigation-suite-scaffold" to "the same",
        "m3/supporting-pane-scaffold" to "the same",
      )

    /** The components the catalog publishes a refusal for, rather than serving; see above. */
    val EXCLUDED = listOf("m3/material-expressive-theme", "m3/sticker")

    /** Shelves the order names that no component is on today; see the test above. */
    val UNUSED_GROUPS = listOf("Bottom sheets", "Menus", "Shapes", "Side sheets", "Tooltips")
  }
}
