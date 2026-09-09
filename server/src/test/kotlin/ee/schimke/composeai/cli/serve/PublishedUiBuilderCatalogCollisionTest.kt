package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The refusal that would have caught m3-catalog, written from the real thing.
 *
 * m3-catalog publishes a `ui-builder.json` whose policy declares **no** `components`, so every id
 * falls to `derivedId`. Its `componentIds` are a `Group/Variant` taxonomy — `Dialog/Basic`,
 * `TopAppBar/Small`, `Buttons/Filled` — and `derivedId` takes the LEAF, which is the variant. The
 * measured result against its 104-component record: `Filled` claimed by 15 components, 63
 * collisions, 41 survivors named `m3/filled`, `m3/small`, `m3/standard`, sharing exactly **one**
 * component id with the 41-component catalog this server synthesises.
 *
 * The reason this is a refusal and not a warning is in that last sentence. 41 composed against a
 * frozen 41 — every check that compares counts reports a match, and only the ids disagree. A shelf
 * can be completely wrong and exactly the right size.
 *
 * The fixtures are that shape reduced to what causes it; the real files are not committed because
 * the record is 3.5 MB. `.github/scripts/ui-builder-equivalence.sh --record` is what checks the
 * published files themselves.
 */
class PublishedUiBuilderCatalogCollisionTest {

  private val exports = ExportCapabilitiesV1(composeCode = true, svg = false, png = false)

  /**
   * A record of `Group/Variant` component ids, the way m3-catalog's is shaped.
   *
   * Ids written out one by one rather than as a group × variant cross product, because the cross
   * product hides the very thing under test: the derived id is the LEAF, so "four groups, one
   * variant each" is four components sharing one id, not the clean case it reads as. Spelled out,
   * each case says what it actually is.
   */
  private fun record(vararg componentIds: String): ComponentRecordFile {
    val components = componentIds.mapIndexed { index, id ->
      val group = id.substringBefore('/')
      val variant = id.substringAfter('/')
      """
        {
          "canonicalId": ":m3/androidx.compose.material3.${group}Kt.$variant$group$index",
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

  /** A policy declaring no `components` — m3-catalog's shape, and why every id is derived. */
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
    // three ids and nine collide — 75%, the same register as m3-catalog's measured 61%.
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
    assertTrue("9 of 12" in unusable.reason, unusable.reason)
    assertTrue("accident of record order" in unusable.reason, unusable.reason)
  }

  @Test
  fun `a catalog that names its components distinctly is unaffected`() {
    // Four DISTINCT leaves, so four distinct derived ids and no collisions. This arm keeps the
    // refusal from being a blanket ban on derived ids — deriving is legitimate, and `test-catalog`
    // and wear-m3-catalog both rely on it.
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
    // the threshold exists to avoid, and the reason it is a rate with a floor of one rather than
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
  fun `the floor holds on a record too small for the rate to mean anything`() {
    // Two components, one collision — 50%, which a bare rate would refuse, contradicting the test
    // above at a different scale. This is the case that caught the first version of the threshold:
    // it broke `PublishedUiBuilderCatalogHostileInputTest`'s two-component collision fixture, which
    // expects a skip and gets one only because the floor is `maxOf(1, rate * n)`.
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
