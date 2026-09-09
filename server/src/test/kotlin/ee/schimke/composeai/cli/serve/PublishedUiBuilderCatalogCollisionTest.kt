package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The refusal that would have caught a real published catalog, written from the real thing.
 *
 * The case: a policy declaring **no** `components`, so every id falls to `derivedId`, whose leaf
 * comes from the record entry's first `componentIds` value — and that taxonomy is `Group/Variant`
 * (`Dialog/Basic`, `TopAppBar/Small`), so the leaf is the VARIANT. One variant word was claimed by
 * 15 components. 63 of 104 collided, and the 41 survivors shared exactly **one** component id with
 * the 41-component catalog this server synthesises.
 *
 * The reason this is a refusal and not a warning is in that last sentence. 41 composed against a
 * frozen 41 — every check comparing counts reports a match, and only the ids disagree. A shelf can
 * be completely wrong and exactly the right size. The catalog is named in
 * `docs/design/UI_BUILDER_CATALOG_CONTRACT.md` § Phase 4; this file may not name one.
 *
 * The fixtures are that shape reduced to what causes it; the real files are not committed because
 * the record is 3.5 MB. `.github/scripts/ui-builder-equivalence.sh --record` is what checks those.
 */
class PublishedUiBuilderCatalogCollisionTest {

  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  /**
   * The canonical id [record] gives the entry at [index].
   *
   * Shared so a fixture cannot mis-spell it. A first draft of the exclusion case below wrote these
   * out by hand, they matched no record entry, every exclusion was silently ignored and the case
   * composed where it should have refused — passing for the wrong reason is the failure mode this
   * whole file is about.
   */
  private fun canonicalIdAt(componentId: String, index: Int): String {
    val group = componentId.substringBefore('/')
    val variant = componentId.substringAfter('/')
    return ":m3/androidx.compose.material3.${group}Kt.$variant$group$index"
  }

  /**
   * A record of `Group/Variant` component ids, the way the real one is shaped.
   *
   * Ids written out one by one rather than as a group × variant cross product, because the cross
   * product hides the thing under test: the derived id is the LEAF, so "four groups, one variant
   * each" is four components sharing one id, not the clean case it reads as.
   */
  private fun record(vararg componentIds: String): ComponentRecordFile {
    val components = componentIds.mapIndexed { index, id ->
      val group = id.substringBefore('/')
      val variant = id.substringAfter('/')
      """
        {
          "canonicalId": "${canonicalIdAt(id, index)}",
          "componentIds": ["$id"],
          "symbol": {
            "name": "$variant$group",
            "callable": "androidx.compose.material3.$variant$group",
            "jvmOwner": "androidx.compose.material3.${group}Kt",
            "origin": "LIBRARY"
          },
          "parameters": [],
          "slots": [],
          "code": { "imports": [] }
        }
        """
        .trimIndent()
    }
    return Json { ignoreUnknownKeys = true }
      .decodeFromString(
        """
        {"schemaVersion":1,"module":":m3","variant":"debug",
         "components":[${components.joinToString(",")}]}
        """
          .trimIndent()
      )
  }

  /** A policy declaring no `components` — the shape that leaves every id to be derived. */
  private fun published(expected: Int) =
    """
    {
      "schema": "compose-ui-builder-catalog/v1",
      "catalog": { "id": "m3-shaped", "title": "A catalog declaring no components" },
      "record": { "file": "components.json", "schemaVersion": 1, "components": $expected },
      "statusSemantics": { "platform": "mobile", "componentIdPrefix": "m3/" }
    }
    """
      .trimIndent()

  @Test
  fun `a file whose ids are variants rather than components is refused`() {
    // Four groups sharing three variant words. The leaf is the variant, so twelve components derive
    // three ids and nine collide — 75%, the same register as the measured 61%.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 12),
        record(
          "Button/Filled",
          "Button/Outlined",
          "Button/Small",
          "Card/Filled",
          "Card/Outlined",
          "Card/Small",
          "Dialog/Filled",
          "Dialog/Outlined",
          "Dialog/Small",
          "TopAppBar/Filled",
          "TopAppBar/Outlined",
          "TopAppBar/Small",
        ),
        exports,
      )
    val unusable = assertIs<PublishedUiBuilderCatalog.Result.Unusable>(result)
    // The reason has to carry the numbers: an operator reading one startup line is deciding whether
    // this is their catalog's bug or their server's, and "refused" alone does not say.
    assertTrue("9 of 12 eligible" in unusable.reason, unusable.reason)
    assertTrue("accident of record order" in unusable.reason, unusable.reason)
  }

  @Test
  fun `a catalog that names its components distinctly is unaffected`() {
    // Four DISTINCT leaves, so four distinct derived ids and no collisions. This arm keeps the
    // refusal from being a blanket ban on derived ids — deriving is legitimate, and both
    // `test-catalog` and the Wear catalog rely on it.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 4),
        record("Button/Filled", "Card/Elevated", "Dialog/Basic", "TopAppBar/Centre"),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    assertEquals(4, composed.catalog.components.size)
  }

  @Test
  fun `one stray duplicate is skipped rather than fatal`() {
    // A single collision is one catalog bug, and the shelf around it is still the right shelf.
    // Refusing here would withdraw a whole catalog over one mis-named component — the failure mode
    // the threshold exists to avoid, and why it is a rate with a floor of one rather than
    // `collisions > 0`.
    val ids = (1..20).map { "Group$it/Variant$it" } + "Extra/Variant1"
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 21),
        record(*ids.toTypedArray()),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    // 21 record components, 20 distinct leaves: exactly one collision, under the threshold.
    assertEquals(20, composed.catalog.components.size)
    assertTrue("1 skipped" in composed.note, composed.note)
  }

  @Test
  fun `an excluded component is out of the denominator, not hiding a colliding shelf`() {
    // Reported by Codex on #655, and real. Ten entries all deriving `m3/filled`, ninety the policy
    // excludes. Over the whole record that is 9 collisions against an allowance of 10, which
    // composes to a ONE-component shelf; over the entries that actually competed it is 9 of 10 and
    // is refused. An excluded entry never claims an identity, so it cannot be evidence that
    // identities are being claimed distinctly.
    val ids = (1..10).map { "Button/Filled" } + (1..90).map { "Skip$it/Skip$it" }
    val excluded =
      ids
        .withIndex()
        .filter { (_, id) -> id.startsWith("Skip") }
        .joinToString(",") { (index, id) ->
          """"m3/skip$index": {"record": "${canonicalIdAt(id, index)}", "excluded": "not on the shelf"}"""
        }
    val policy =
      """
      {
        "schema": "compose-ui-builder-catalog/v1",
        "catalog": { "id": "m3-shaped" },
        "record": { "file": "components.json", "schemaVersion": 1, "components": 100 },
        "statusSemantics": { "platform": "mobile", "componentIdPrefix": "m3/",
          "components": { $excluded } }
      }
      """
        .trimIndent()
    val result = PublishedUiBuilderCatalog.compose(policy, record(*ids.toTypedArray()), exports)
    val unusable = assertIs<PublishedUiBuilderCatalog.Result.Unusable>(result)
    assertTrue("9 of 10 eligible" in unusable.reason, unusable.reason)
  }

  @Test
  fun `the floor holds on a record too small for the rate to mean anything`() {
    // Two components, one collision — 50%, which a bare rate would refuse, contradicting
    // `one stray duplicate is skipped rather than fatal` at a different scale. This is the case
    // that caught the first version of the threshold: it broke
    // `PublishedUiBuilderCatalogHostileInputTest`'s two-component collision fixture, which expects
    // a skip and gets one only because the allowance is `maxOf(1, rate * eligible)`.
    val result =
      PublishedUiBuilderCatalog.compose(
        published(expected = 2),
        record("Button/Filled", "Card/Filled"),
        exports,
      )
    val composed = assertIs<PublishedUiBuilderCatalog.Result.Composed>(result)
    assertEquals(listOf("m3/filled"), composed.catalog.components.map { it.componentId })
  }
}
