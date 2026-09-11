package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The proof the catalog contract exists for: a catalog **this binary has never heard of** becomes a
 * servable capability catalog from its own published files.
 *
 * `test-catalog` is deliberately not one of the three ids in `ProductionUiBuilderRuntime` — there
 * is no `testCatalog()` synthesiser, no enum value, no `when` branch. If this passes, the only
 * thing the server knows about it came out of the two files below, which is exactly what
 * `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` sets as the test of the whole design.
 *
 * Kept trivial on purpose. The real catalogs are proved equivalent to what the server synthesises
 * by `.github/scripts/ui-builder-equivalence.sh` against the frozen goldens; this is about the
 * *route* being open, not about any particular catalog's contents.
 */
class PublishedUiBuilderCatalogTest {

  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  private val record = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString<ComponentRecordFile>(
      """
      {
        "schemaVersion": 1,
        "module": ":test-catalog",
        "variant": "debug",
        "components": [
          {
            "canonicalId": ":test-catalog/com.example.TestKt.Widget",
            "componentIds": ["Widgets/Widget"],
            "symbol": {
              "name": "Widget",
              "callable": "com.example.Widget",
              "jvmOwner": "com.example.TestKt",
              "origin": "PROJECT"
            },
            "parameters": [
              { "name": "label", "type": "String", "typeFqn": "kotlin.String", "hasDefault": false },
              { "name": "count", "type": "Int", "typeFqn": "kotlin.Int", "hasDefault": true }
            ],
            "slots": [],
            "code": { "imports": ["com.example.Widget"] }
          },
          {
            "canonicalId": ":test-catalog/com.example.TestKt.Unlabelled",
            "componentIds": [],
            "symbol": {
              "name": "RTLText",
              "callable": "com.example.RTLText",
              "jvmOwner": "com.example.TestKt",
              "origin": "PROJECT"
            },
            "parameters": [],
            "slots": [],
            "code": { "imports": ["com.example.RTLText"] }
          }
        ]
      }
      """
        .trimIndent()
    )

  private fun published(
    prefix: String = "test-catalog/",
    schema: String = "compose-ui-builder-catalog/v1",
    extra: String = "",
  ) =
    """
    {
      "schema": "$schema",
      "catalog": { "id": "test-catalog", "title": "Test catalog" },
      "record": { "file": "components.json", "schemaVersion": 1, "components": 2 },
      "statusSemantics": {
        "platform": "test",
        "platformLabel": "Test",
        "componentIdPrefix": "$prefix",
        "frame": { "adapter": "frame/rect" },
        "componentMenu": { "groupOrder": ["Widgets"] },
        "builtins": {
          "test-catalog/screen": { "role": "screen-root", "slots": { "content": {} } }
        },
        "components": {
          "test-catalog/widget": {
            "record": ":test-catalog/com.example.TestKt.Widget",
            "displayName": "The Widget",
            "canvas": "box",
            "traits": ["scrollable"]
          }
        }$extra
      }
    }
    """
      .trimIndent()

  @Test
  fun `a catalog the server has never heard of composes from its published files`() {
    val result = PublishedUiBuilderCatalog.compose(published(), record, exports)
    val composed =
      assertTrue(result is PublishedUiBuilderCatalog.Result.Composed).let {
        result as PublishedUiBuilderCatalog.Result.Composed
      }
    val catalog = composed.catalog

    assertEquals("test-catalog", catalog.benchmark.catalogSystemId)
    // Three: the annotated component under its declared id, the unannotated one under a DERIVED id,
    // and the builtin — which has no call site and so exists only because the policy says so.
    assertEquals(
      listOf("test-catalog/widget", "test-catalog/rtl-text", "test-catalog/screen"),
      catalog.components.map { it.componentId },
    )

    val widget = assertNotNull(catalog.components.first { it.componentId == "test-catalog/widget" })
    assertEquals("The Widget", widget.displayName)
    assertEquals(listOf("scrollable"), widget.traits)
    // `label` has no default and is not nullable; `count` defaults.
    assertEquals(
      listOf("label" to true, "count" to false),
      widget.properties.map { it.name to it.required },
    )
    assertEquals(WasmAdapterStatusV1.SUPPORTED, widget.wasm?.adapterStatus)

    // The builtin is a container because the policy gave it a slot, and carries no code capability:
    // there is no call site to compile, which is what makes it a builtin.
    val screen = assertNotNull(catalog.components.first { it.componentId == "test-catalog/screen" })
    assertEquals(listOf("content"), screen.slots.map { it.name })
    assertNull(screen.code)

    // Every reader downstream looks in `statusSemantics`, so the published block is passed through
    // whole — including the fields this composer does not itself interpret.
    assertTrue("frame" in catalog.statusSemantics.keys)
    assertTrue("componentMenu" in catalog.statusSemantics.keys)
    assertTrue(composed.note.startsWith("test-catalog — published ui-builder.json"))
  }

  @Test
  fun `an unannotated component gets the derived id a saved design would store`() {
    // `RTLText` -> `rtl-text`: a run of capitals is one word. This is the one derivation the reader
    // and the generator have to agree on without a field to agree through, so it is pinned here as
    // well as by the equivalence gate.
    assertEquals("rtl-text", PublishedUiBuilderCatalog.slug("RTLText"))
    assertEquals("checkbox-button", PublishedUiBuilderCatalog.slug("CheckboxButton"))
    assertEquals("button2", PublishedUiBuilderCatalog.slug("Button2"))
    assertEquals("top-app-bar", PublishedUiBuilderCatalog.slug("TopAppBar"))
    // The two cases that separate this from a naive port, both reported by Codex against the
    // JavaScript one in `.github/scripts/ui-builder-equivalence.sh`, which pins the same table.
    //
    // `lowercaseChar()` is a SINGLE-character mapping: U+0130 lowercases to `i`, where a
    // JavaScript `toLowerCase()` yields `i` plus a combining dot.
    assertEquals("i-button", PublishedUiBuilderCatalog.slug("\u0130Button"))
    // And this loop walks `Char`s, so a supplementary code point is two surrogates, neither of
    // which is a letter — both separate. A port iterating code points keeps it and produces an id
    // with half a surrogate pair in it.
    assertEquals("a-b", PublishedUiBuilderCatalog.slug("A\uD801\uDC00B"))
    // `isLetterOrDigit()` is `isLetter() || isDigit()`, and `isDigit()` is the DECIMAL category
    // alone — a superscript two is numeric but not a digit, so it separates.
    assertEquals("widget-x", PublishedUiBuilderCatalog.slug("Widget\u00B2X"))
    // And the word-boundary test asks `isDigit()` too, so a decimal digit outside ASCII starts a
    // word after it — the same predicate as the admission test, which a port can easily split.
    assertEquals("a\u0662-b", PublishedUiBuilderCatalog.slug("A\u0662B"))
    // `isLowerCase()` is a case PROPERTY, not "differs from its uppercase form": U+02B0 is
    // lowercase to the JVM and has no distinct case conversion, so a round-trip heuristic — which
    // is what a port reaches for — calls it neither upper nor lower and misses the boundary.
    assertEquals("\u02B0-a", PublishedUiBuilderCatalog.slug("\u02B0A"))
  }

  @Test
  fun `a catalog that cannot be composed says why instead of throwing`() {
    // Each of these leaves the catalog to whatever the server can synthesise; none is fatal. A host
    // that died over another repository's bad publish would be down until that repo's CI ran again.
    val futureMajor =
      PublishedUiBuilderCatalog.compose(
        published(schema = "compose-ui-builder-catalog/v2"),
        record,
        exports,
      )
    assertTrue(futureMajor is PublishedUiBuilderCatalog.Result.Unusable)
    assertTrue("v2" in (futureMajor as PublishedUiBuilderCatalog.Result.Unusable).reason)

    val noPrefix = PublishedUiBuilderCatalog.compose(published(prefix = ""), record, exports)
    assertTrue(noPrefix is PublishedUiBuilderCatalog.Result.Unusable)

    val notJson = PublishedUiBuilderCatalog.compose("[]", record, exports)
    assertTrue(notJson is PublishedUiBuilderCatalog.Result.Unusable)
  }

  @Test
  fun `a file that expects a record is refused when none arrived`() {
    // The dangerous case, and the reason it is a refusal rather than a shrug: composing a policy
    // that expects an inventory against no inventory SUCCEEDS, with the builtins alone — so a cold
    // host would replace a full shelf with a nearly empty one and say nothing. The file states the
    // count it was generated against, which is what makes the mistake detectable at all.
    val result = PublishedUiBuilderCatalog.compose(published(), record = null, exports)
    assertTrue(result is PublishedUiBuilderCatalog.Result.Unusable)
    assertTrue(
      "builtins alone" in (result as PublishedUiBuilderCatalog.Result.Unusable).reason,
      result.reason,
    )
  }

  /**
   * Everything a builtin declares reaches the shelf, which nothing checked.
   *
   * A builtin is the only way a catalog can offer a component the record cannot carry — a host
   * frame, a shape, an image asset with no call site — so what it declares is all there is. Three
   * fields were being read past:
   * - `properties` was decoded under the server's own name `propertyCapabilities`, which
   *   `ui-builder.policy.schema.json` forbids (`additionalProperties: false`), so every builtin a
   *   real catalog could publish composed with **zero** properties;
   * - `slots` was `Map<String, JsonElement>`, so `required` became `min = 0` and `acceptedTraits`
   *   vanished — the rules that stop a design putting a scaffold inside a widget's background slot
   *   with it;
   * - `traits` was hardcoded to the empty list, so no other component's slot could accept one.
   *
   * `max` joined them later, for the same reason and from the same case: a `remote-m3` widget
   * container hosts exactly one child and a builtin had no way to write the bound.
   *
   * Each is asserted here against the wire names, so the fixture is a document a catalog could
   * actually publish rather than one written to match the reader.
   */
  @Test
  fun `a builtin's declared vocabulary reaches the shelf`() {
    val document =
      published(
        extra =
          """,
        "builtins": {
          "test-catalog/host": {
            "role": "screen-root",
            "displayName": "The Host",
            "traits": ["WidgetHost"],
            "slots": {
              "content": {
                "required": true,
                "max": 1,
                "acceptedRoles": ["Container", "Leaf"],
                "acceptedTraits": ["AnyContent"],
                "role": "overlay"
              },
              "background": { "acceptedTraits": ["DrawLayer"] }
            },
            "properties": [
              { "name": "cornerRadiusDp", "jsonType": "number" },
              { "name": "mode", "jsonType": "string", "required": true,
                "allowedValues": ["squircle", "round"] }
            ],
            "modifierCapabilities": ["padding"]
          }
        }"""
      )
    val result = PublishedUiBuilderCatalog.compose(document, record, exports)
    assertTrue(
      result is PublishedUiBuilderCatalog.Result.Composed,
      "the builtin did not compose: ${(result as? PublishedUiBuilderCatalog.Result.Unusable)?.reason}",
    )
    val host =
      (result as PublishedUiBuilderCatalog.Result.Composed).catalog.components.single {
        it.componentId == "test-catalog/host"
      }

    assertEquals("The Host", host.displayName)
    // `screen-root` is the structural role, and the shelf role that follows from it is Scaffold —
    // not the Container that "it has slots" would derive. A design root arriving as an ordinary
    // container is what the editor then calls it.
    assertEquals("Scaffold", host.role)
    assertEquals(listOf("WidgetHost"), host.traits)
    assertEquals(listOf("padding"), host.modifierCapabilities)
    assertEquals(
      listOf("cornerRadiusDp", "mode"),
      host.properties.map { it.name }.sorted(),
      "a builtin's properties are its only source, and they were being dropped",
    )
    assertEquals(true, host.properties.single { it.name == "mode" }.required)
    assertEquals(
      listOf("squircle", "round"),
      host.properties.single { it.name == "mode" }.allowedValues.map { it.toString().trim('"') },
    )
    val content = host.slots.single { it.name == "content" }
    assertEquals(
      1,
      content.cardinality.min,
      "a required slot that accepts zero children is not required",
    )
    // The other end, and the last thing a frozen widget container needed a builtin to say. Without
    // it `remote-m3`'s two `WidgetContainer` components composed with an unbounded `content` slot,
    // so the shelf offered a widget host a design could put three children into.
    assertEquals(
      1,
      content.cardinality.max,
      "a slot declaring `max` composed unbounded, so the shelf admitted children the host cannot draw",
    )
    assertEquals(listOf("AnyContent"), content.acceptedTraits)
    assertEquals(listOf("Container", "Leaf"), content.acceptedRoles)
    assertEquals(
      listOf("DrawLayer"),
      host.slots.single { it.name == "background" }.acceptedTraits,
    )
    assertEquals(0, host.slots.single { it.name == "background" }.cardinality.min)
    // And a slot that says nothing is still unbounded: `max` is an opt-in bound, not a default of
    // one that every existing builtin would silently acquire.
    assertEquals(null, host.slots.single { it.name == "background" }.cardinality.max)
  }

  @Test
  fun `a catalog of builtins alone needs no component record`() {
    // Policy without inventory is the simplest thing the contract can express, and refusing it here
    // would be refusing a catalog whose components all lack call sites.
    val result =
      PublishedUiBuilderCatalog.compose(
        published().replace("\"components\": 2", "\"components\": 0"),
        record = null,
        exports,
      )
    val composed =
      assertTrue(result is PublishedUiBuilderCatalog.Result.Composed).let {
        result as PublishedUiBuilderCatalog.Result.Composed
      }
    assertEquals(listOf("test-catalog/screen"), composed.catalog.components.map { it.componentId })
  }
}
