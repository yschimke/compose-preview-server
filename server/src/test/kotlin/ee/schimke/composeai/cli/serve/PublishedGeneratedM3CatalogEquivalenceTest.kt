package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What `m3-catalog` gains and what it loses if `--ui-builder-published-catalogs` names it.
 *
 * The sibling [PublishedM3CatalogEquivalenceTest] composes a **hand-built** published file and says
 * so in its KDoc: that is a real test of the composer and no test at all of the catalog. This pair
 * is **generated** — captured verbatim from `:catalog:composePreviewDiscover` in
 * yschimke/m3-catalog — which is the same way `remote-m3` got its gate, and the difference that
 * turned 14 green tests into 11 failures the first time it was tried.
 *
 * The comparison is not the one the name "equivalence" suggests, and
 * `UI_BUILDER_CATALOG_CONTRACT.md` says so outright: the frozen `m3-catalog` capability document is
 * a hand-transcribed *Jetcaster* catalog, unrelated to yschimke/m3-catalog, and "the two describe
 * different component sets on purpose … so here the reviewed difference list is the point and a
 * byte-equal result would be the surprising outcome". So this test does not ask for equality. It
 * asks three separable questions and pins each as an exact set:
 * - which of the frozen twenty-five the real catalog can offer (**twenty-four**, and the one it
 *   cannot is named with a reason);
 * - what each of those twenty-four offers, field by field, against the frozen vocabulary a saved
 *   design was authored against;
 * - what the real catalog adds (**eighty-six**), because a shelf growing by that much is a product
 *   change rather than a rounding error.
 */
class PublishedGeneratedM3CatalogEquivalenceTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

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
   * The record behind each published component, under the builder id a design names it with — the
   * composition's own join, and the map `ServeRunner` hands the export executor.
   */
  private val composedRecords: Map<String, ComponentRecord>
    get() = result.records

  /**
   * The one of the frozen twenty-five the published catalog cannot offer, and why.
   *
   * Asserted ABSENT rather than skipped: a gap nothing states is a gap that stops being noticed,
   * and if it starts composing this test fails and says to move it out rather than passing quietly.
   *
   * Two left this list on the way here, and both were the same kind of thing — a callable the
   * record could not REACH rather than one the catalog does not draw. `m3/time-picker` went when
   * the scan classpath started resolving dependencies by coordinate rather than by cache path;
   * `m3/date-picker` went when the walk learned to follow a singleton lambda held by another
   * singleton lambda, which is how `DatePickerModalSticker` reaches `DatePicker` four frames in
   * (both yschimke/compose-ai-tools#5354; the diagnosis is yschimke/m3-catalog#317).
   *
   * What is left is different in kind, which is why the reason and not just the id is in the map.
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
   * What the real catalog ADDS, as an exact set.
   *
   * The contract document predicted this ("fifty-nine rendered components against twenty-five
   * transcribed ones") and the measured number is eighty-six, so the prediction was the right shape
   * and the wrong size — which is the argument for pinning it rather than bounding it. Every entry
   * is a component a design could be built on, so the list growing or shrinking is a change to what
   * the builder offers and belongs in a diff someone reads.
   *
   * Note what is NOT asserted here: that each is exportable. m3-catalog's lane writes Compose
   * source through the record-driven generator rather than through `RemoteContentEmitter`'s
   * hand-written cases, so the blocker that stops `remote-m3` does not apply — but "does every one
   * of these eighty-six round-trip to compiling Kotlin" is a separate question and this is not it.
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

  /** The same comparison [PublishedM3CatalogEquivalenceTest] makes, for the same reasons. */
  /**
   * The two fields the comparison above leaves out: what the shelf CLAIMS about the canvas, and
   * what an export calls.
   *
   * `compare()` checked names, roles, traits, slots, modifiers and properties, and said nothing
   * about `wasm` or `code`. Codex raised that on #673 and it was worth fixing — but the first
   * version of this test drew the wrong conclusion from it, and the wrong conclusion is worth
   * keeping written down because it is the more tempting reading.
   *
   * **Every component's `wasm` status goes UNSUPPORTED, and the canvas does not change.**
   * `PublishedUiBuilderCatalog.wasm()` computes `drawn` from the policy's `canvas`, m3-catalog's
   * policy declares none, so all twenty-five compose to `platformSupported = false` with the note
   * "drawn on the canvas as a named placeholder: this catalog claims no adapter". That note is
   * false, and so was this test's first KDoc, which called it a cutover blocker.
   *
   * What actually draws the canvas is `UiBuilderRenderer.RenderNode`, a `when (componentId)` over
   * literal ids compiled into `:ui-builder` for Wasm. It never reads `adapterStatus` or
   * `platformSupported` — nothing does, outside `CapabilityValidator`, whose two derived fields
   * feed the harness diagnostic publisher and nothing else. The adapter id itself never leaves
   * `wasm()`: it appears only inside that prose note. A registry keyed by adapter id is a planned
   * future (`UI_BUILDER_CATALOG_CONTRACT.md` § item 17), not the present.
   *
   * So `m3/button` keeps drawing after the swap because it is still called `m3/button`. What breaks
   * is the STATUS: a shelf that reports every component unsupported while drawing it. That is a
   * real defect — a surface lying about another surface — and it is not the canvas going blank.
   *
   * Both halves of that were true when this test was written and the first is now fixed:
   * m3-catalog#327 declares `canvas` for the twenty-four components the renderer has a case for, so
   * the shelf reports what the builder actually draws and this assertion is `emptyList()` rather
   * than the list of everything drawn.
   *
   * The canvas gap that IS real belongs to the components this pair ADDS, and this test does not
   * measure it: 108 published `m3/` ids, 24 with a case in the renderer, **84 falling to the `else`
   * branch** and drawing `UnsupportedComponentDiagnostic`. See yschimke/m3-catalog#324.
   *
   * The `code` differences are not losses and are asserted as such so they cannot quietly become
   * some other difference: the composed symbol is the FQN whose simple name is the frozen one (the
   * frozen file abbreviates), and the composed imports are the frozen ones minus the sibling
   * variants a hand-transcribed entry merged into one component.
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
    assertEquals(
      emptyList(),
      lostCanvas,
      "a drawn component lost its `wasm` status — the shelf is reporting a component " +
        "UNSUPPORTED that the builder draws, which is a surface lying about another surface",
    )

    for (component in shared) {
      val got = composedById.getValue(component.componentId)
      val frozenSymbol = component.code?.symbol ?: continue
      val composedSymbol = got.code?.symbol
      // One component is a member of an object rather than a top-level callable. The frozen file
      // writes it the way it is called — symbol `SearchBarDefaults.InputField`, importing the
      // object — and the record knows only the callable's own name, so the composed entry names
      // `InputField` and imports a member. It is not a different component; it is the same one,
      // uncallable, which the call-site test below reports for it by name.
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

    // Where the import LISTS differ, and they differ in both directions. The frozen file merged
    // sibling variants into one entry and imported all of them (`Button`, `OutlinedButton`,
    // `TextButton` under `m3/button`); the composed entry imports the one callable it names, plus
    // whatever the recorded call site actually needs — `m3/text-field` gains
    // `rememberTextFieldState`, which the hand-written entry never had and a compiling call does.
    // Pinned so a new divergence is a review rather than a surprise.
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
    // `wasm` and `code` are deliberately NOT compared here, and this comparison used to leave
    // them out without saying so — which is the finding. They differ for every single component,
    // systematically, and burying twenty-five identical entries in this list would hide the two
    // fields rather than check them. They get their own test below, which states what the
    // difference IS. Raised in review on #673.
    val want = expected.properties.associateBy { it.name }
    val got = actual.properties.associateBy { it.name }
    check("properties", want.keys.sorted(), got.keys.sorted())
    for ((name, w) in want) {
      val g = got[name] ?: continue
      check("properties[$name].jsonType", w.jsonType, g.jsonType)
      check("properties[$name].required", w.required, g.required)
      allowed(name, w, g)?.let { out += it }
    }
    return out
  }

  /**
   * Properties whose allowed values are a GENERATED inventory rather than an authored enumeration.
   * See [allowed].
   */
  private val generatedInventory = setOf("iconKey")

  /**
   * The one property field where equality is the wrong question: an **inventory**, not a choice.
   *
   * Every other allowed-value list on this shelf is an enumeration somebody authored —
   * `m3/text`.`style`'s fifteen typography roles — and equality is exactly right for those: a value
   * appearing or disappearing is a design decision, and it belongs in a diff.
   *
   * `m3/icon`.`iconKey` is not that. It is the Material icon set, generated, and the two sides are
   * pinned independently: the frozen catalog is packaged in THIS repository, and the composed one
   * is whatever m3-catalog last published. #710 exposed the complete inventory here while
   * m3-catalog still ships the forty-six-icon hand-picked list, and equality then made this
   * comparison red on a fact neither repository can act on from the other side — printing all
   * 11,431 names into the failure, which is also how nobody reads it.
   *
   * The question worth asking survives the growth: **does the published catalog offer an icon the
   * builder cannot draw?** That is containment. It is stable while the inventory grows, it fails on
   * the thing that would actually break a design, and it names only the offending values.
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
   * The builder's own vocabulary survives the swap.
   *
   * All seventeen of the frozen catalog's non-`m3/` entries are in a donor namespace, so unlike
   * `remote-m3` — which borrows `m3/surface` and `m3/text` and loses both — m3 has no borrowed
   * component to lose. That is worth an assertion rather than a sentence: the donor list is a
   * constant in `ProductionUiBuilderRuntime` and a catalog gaining a borrowed component would make
   * this the same blocker it is there.
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
   * Every shelf the components use has to be in the menu order, or the editor invents one.
   *
   * `UiBuilderEditorState` appends a group the order does not name after every group it does and
   * sorts those alphabetically, so a catalog that states an order and then uses a group outside it
   * has published an order the builder will not follow. The mirror of the `remote-m3` check, and
   * the reason that one exists: four wear shelves were re-sorted silently.
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
   * A component with no menu entry is a component the editor files under a role heading.
   *
   * The insert panel groups by `componentMenu`, and an entry it cannot find falls back to a generic
   * "Container" / "Leaf" heading — so this is not a cosmetic gap: it is where a person looks for
   * the component and does not find it. Twenty-eight of the hundred and eight were in that state
   * until the generator learned to shelve a component by the catalog id it is published under
   * (yschimke/compose-ai-tools#5354).
   *
   * The six left are the ones that fix cannot reach: nothing declares them, so they carry no
   * catalog id and there is no shelf to inherit. Only `ui-builder.policy.json` can place them, and
   * where an adaptive-layout scaffold belongs on a Material 3 shelf is a catalog decision rather
   * than something this test can assert — m3-catalog#323.
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
   * What the catalog decided about the four components whose shelf nobody had chosen — and the one
   * place that decision has not landed yet.
   *
   * One component is one SYMBOL, and a symbol several stickers draw is filed under whichever
   * catalog id sorts first. Four survived every earlier correction because the catalog had no
   * section they belonged to, so placing them meant inventing a shelf. m3-catalog#327 made all four
   * calls: `Icons` and `Surfaces` are new headings for `m3/icon` and `m3/surface`, and the other
   * two are **not components** — `Sticker` is the frame every preview is drawn inside and
   * `MaterialExpressiveTheme` is the theme scope wrapping them, so both are `excluded` with the
   * reason published.
   *
   * The exclusions take effect where it matters — neither is served, so neither can be placed in a
   * design. They are still on the MENU, which is the generator writing a shelf entry for a
   * component the consumer refuses to serve: a palette item that disappears on insert. Fixed in
   * yschimke/compose-ai-tools#5378 and not yet released, and m3-catalog pins the released plugin —
   * so this fixture captures the bug, and this test says so rather than leaving it unremarked. When
   * the catalog bumps its plugin and the fixture is re-captured, the last assertion here fails and
   * is deleted.
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
   * The question that stops `remote-m3`, asked of m3 — and the answer is not the one this test used
   * to give.
   *
   * It asked `signatureKnown`, which every one of the 110 record components sets, and concluded
   * that all 108 offered components were writable. That is the same mistake #690's review found on
   * the Remote side, in the same words: **a recovered signature is not a callable**. A component
   * can be a member of a `Defaults` object, need a receiver scope around it, declare type
   * parameters, or require a parameter of a type no design value becomes — and its signature is
   * recovered all the same.
   *
   * The record already states the stronger fact, because discovery computes it: `code.call` is the
   * call site it could write, and `code.refusedReason` says why it could not. Asked that way,
   * **twenty-five of the offered components have no call site**, and the reasons split in two:
   * - *structural* — not public, a member of an object, a scope receiver, type parameters. These
   *   are unconditional: no design can supply its way past them.
   * - *a required parameter no value becomes* — `SearchBarState`, `CarouselState`,
   *   `PaneScaffoldDirective`, `ImageVector`, `ToggleableState`, a `ClosedFloatingPointRange`.
   *   Discovery is judging a standalone snippet, and a design authoring that parameter would be
   *   judged by the generator instead — but none of these six is a type this builder's value
   *   vocabulary has, so in practice they land the same way.
   *
   * Pinned as the reviewed set rather than as `isEmpty()`, so the day discovery learns one of them
   * this test fails and says to shorten the list.
   *
   * What this still is not: the generator-driven measurement `remote-m3` has, where every offered
   * component is put through the real exporter. Running the generator over this pair asks a
   * question this fixture cannot answer honestly — the generated record's `componentIds` are the
   * catalog's own taxonomy (`Dialog/Basic`), not the `m3/…` ids the published file names, so every
   * component refuses to resolve before its call site is ever considered. Whether that is a real
   * cutover blocker or the wrong record for the question is compose-preview-server#674.
   *
   * `m3/current-scheme` is pinned separately: it is a companion property rather than a callable
   * taking arguments, so an empty parameter list is correct for it and suspicious for anything
   * else.
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
          // Not `?: return@mapNotNull null`, which is how this read before: a published component
          // whose `record` names a canonical id the record file does not carry was silently
          // dropped from the measurement rather than reported. Nothing carries that shape today
          // and this is what says so.
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
   * How much of the published m3 shelf the generator can actually write, component by component.
   *
   * The remote-m3 sibling has asked this of its exporter since #673. m3 could not be asked until
   * now: `ScreenGenerator` resolves a node against the record's `componentIds`, the generated
   * record carries the catalog's own taxonomy (`Dialog/Basic`), and the published file names `m3/…`
   * — so every component refused to resolve before its call site was considered, and #691 said so
   * rather than pinning a number that described the fixture pairing. Aliasing the record with the
   * published ids (compose-preview-server#694) is what makes the question answerable.
   *
   * Asked the way an author would: a screen whose root IS the component, with a value authored for
   * every required property the shelf declares. What refuses is pinned with the generator's own
   * first reason, so teaching it one shortens this list and says so.
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

    // Every ALLOWED VALUE of every enumerated required property, not just the first.
    //
    // The loop above authors `authored(property)`, which takes `allowedValues.first()`. That is
    // enough to ask whether a component exports at all and blind to the thing an enumeration is
    // for: `m3/text-field` passed on `filled` while `outlined` refused, because the variant table
    // spelled `TextFieldKt.OutlinedTextField` and Material declares that callable in
    // `OutlinedTextFieldKt`. One wrong string, invisible to a measurement that only ever asked
    // for the first value — the shelf advertised the variant as exportable and the server said
    // `no component`. Raised by the review bot on #703 and true.
    //
    // Asserted as `emptyList()` rather than as a reviewed set: a component that exports on one
    // value of a property and refuses on another is a defect every time, not a decision. It costs
    // one more export per allowed value on the components that already export.
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

    // The one component on this shelf whose properties reach a *state factory* rather than its
    // own parameters, and the reason `M3_EXPORT_REFUSALS` is shorter than it was. Pinned as the
    // emitted call, because "it no longer refuses" is also what a `TimePicker` that silently
    // dropped the authored hour would look like.
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
   * The two things `BuilderTimePicker` does to a property before it reaches the state factory, and
   * an export that skipped either would draw a different picture from the canvas that accepted it.
   *
   * `is24Hour` is OPTIONAL and the canvas defaults it to `true` (`UiBuilderRenderer`:
   * `node.bool("is24Hour", true)`). Omitting the argument is not neutral — Material's own default
   * is the device locale, so the same design is a 24-hour dial in the builder and a 12-hour one on
   * a US phone. `hour` and `minute` are clamped there too (`coerceIn(0, 23)` / `coerceIn(0, 59)`),
   * and the catalog validator only checks that a property is an integer, so 25 reaches here from an
   * MCP client or an imported document and reaches Material as an hour it rejects at composition.
   * Clamped rather than refused, because the builder already drew this design with the clamped
   * value and the export that matches the picture is that one.
   *
   * Both raised by the review bot on #713 and both true.
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
   * A screen whose root IS [component], with every required property the shelf declares — and,
   * where [choose] names one, a specific value rather than the first allowed one.
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
      // The subject IS the root. A `layout/column` wrapper reads naturally and is not in the
      // catalog's record — it is one of the builder's own donor components, synthesised by the
      // runtime — so every component would refuse for the wrapper rather than for itself.
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
     * The twenty-six published m3 components the generator cannot write, each with its first
     * reason. **Eighty-two of the hundred and eight export.** Teaching the generator one of these
     * shortens the list, and the test above fails until it is shortened here.
     *
     * Twenty-five are discovery's own "no call site" judgement — a member of a `Defaults` object, a
     * scope receiver, type parameters, not public, or a required parameter of a type no design
     * value becomes. Those are upstream API shapes rather than gaps here.
     *
     * The last one is ours: a property that configures the component's remembered STATE rather than
     * its call. `m3/date-picker` declares `selectedDate`, which reaches
     * `rememberDatePickerState(initialSelectedDateMillis = …)` — a date string where the factory
     * wants a `Long`, and this projection has no vocabulary for that conversion. Its `mode` is the
     * first reason recorded, but teaching `mode` alone would leave the date behind.
     *
     * `m3/time-picker` was here on the same grounds until `STATE_BUNDLES` learned it, and it is the
     * worked precedent: `hour` and `minute` are now `rememberTimePickerState(initialHour = …,
     * initialMinute = …)`, pinned by the test above. `mode` was a separate refusal until the
     * variant table learned it: `dial` and `input` are `TimePicker` and `TimeInput`, two callables
     * of identical shape, which is the `m3/progress-indicator` precedent the catalog's own note
     * names.
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

    /**
     * The seven components whose composed import list is not the frozen one. See the test above:
     * the frozen file merged sibling variants and the composed entry imports what the recorded call
     * needs.
     */
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
     * The twenty-five offered components discovery could not write a call site for, each with the
     * reason it recorded. See the test above for what the two kinds of reason mean.
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

    /**
     * The eighty-six components the real catalog adds; see the test above for why they are pinned.
     */
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
        "m3/navigation-suite-item",
        "m3/navigation-suite-scaffold",
        "m3/outlined-button",
        "m3/outlined-card",
        "m3/outlined-icon-button",
        "m3/outlined-leading-button",
        "m3/outlined-text-field",
        "m3/outlined-toggle-button",
        "m3/outlined-trailing-button",
        "m3/primary-scrollable-tab-row",
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

    /** Reviewed differences between an offered component and the frozen vocabulary. */
    val REVIEWED_DIFFERENCES = listOf<String>()

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
