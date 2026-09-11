package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rules [ServeRelatedCatalogs] applies to a component's declared `related` links.
 *
 * All of it is producer-facing policy — what happens to a slip, a self-link, a system this box does
 * not serve — so it is worth holding still independently of wherever the links eventually appear.
 * The surface is still undecided; none of these assert anything about it.
 */
class ServeRelatedCatalogsTest {

  private fun declared(vararg entries: Pair<String, String?>) =
    entries.map { (system, componentId) ->
      ServeRelatedCatalogs.Declared(system, componentId)
    }

  @Test
  fun `keeps declaration order`() {
    // The producer's order is the catalog author's order, and there is no better one to impose:
    // alphabetising by system would put the samples before the tiles for no reason a reader knows.
    val out =
      ServeRelatedCatalogs.declaredFor(
        declared("z-samples" to null, "a-tiles" to null),
        selfSystem = "kit",
      )
    assertEquals(listOf("z-samples", "a-tiles"), out.map { it.system })
  }

  @Test
  fun `drops a blank system`() {
    val out =
      ServeRelatedCatalogs.declaredFor(
        declared("" to null, "  " to null, "ok" to null),
        selfSystem = null,
      )
    assertEquals(listOf("ok"), out.map { it.system })
  }

  @Test
  fun `drops a link back to this catalog`() {
    // A spec is written against a system NAME, so the export cannot always tell it is describing
    // the catalog it will be published as. Self-links are a producer slip, not a reader's problem.
    val out =
      ServeRelatedCatalogs.declaredFor(
        declared("kit" to null, "samples" to null),
        selfSystem = "kit",
      )
    assertEquals(listOf("samples"), out.map { it.system })
  }

  @Test
  fun `a miscased system is a different catalog, not a self-link`() {
    // A catalog id is a case-sensitive key everywhere else — the session map, catalogs.json's
    // duplicate check, the URL segment — so `Kit` and `kit` are two catalogs and folding them here
    // would drop a real link. A merely miscased self-link is not registered under that spelling, so
    // resolve drops it anyway and nothing is owed to it.
    val out = ServeRelatedCatalogs.declaredFor(declared("KIT" to null), selfSystem = "kit")
    assertEquals(listOf("KIT"), out.map { it.system })
  }

  @Test
  fun `resolve collapses a short form and a restated one naming the same component`() {
    // The case declaredFor cannot see: a component inherits `("samples")` from its @CatalogGroup
    // and restates it as `("samples", "Button")`. Two declarations, one destination, and only the
    // fallback makes that visible. First wins, as it does in declaredFor.
    val links =
      ServeRelatedCatalogs.resolve(
        listOf(
          ServeRelatedCatalogs.Declared("samples", label = "call sites"),
          ServeRelatedCatalogs.Declared("samples", "Button", "call sites, again"),
        ),
        componentId = "Button",
        selfSystem = "kit",
        registered = setOf("samples"),
        isLive = { true },
      )
    assertEquals(1, links.size)
    assertEquals("call sites", links.single().label)
  }

  @Test
  fun `deduplicates on system and component, first declaration winning`() {
    // A component can inherit a link from its @CatalogGroup and restate it; showing it twice is
    // never what was meant.
    val out =
      ServeRelatedCatalogs.declaredFor(
        listOf(
          ServeRelatedCatalogs.Declared("samples", "Button", "call sites"),
          ServeRelatedCatalogs.Declared("samples", "Button", "something else"),
          ServeRelatedCatalogs.Declared("samples", "Card"),
        ),
        selfSystem = null,
      )
    assertEquals(2, out.size)
    assertEquals("call sites", out[0].label)
    assertEquals("Card", out[1].componentId)
  }

  @Test
  fun `blank component and label normalise to null rather than empty`() {
    // Null is "the other catalog spells it the same way" and "" is a producer slip; collapsing them
    // means a surface only ever has to test for null.
    val out =
      ServeRelatedCatalogs.declaredFor(
        listOf(ServeRelatedCatalogs.Declared("samples", "  ", "  ")),
        selfSystem = null,
      )
    assertEquals(null, out.single().componentId)
    assertEquals(null, out.single().label)
  }

  @Test
  fun `keeps an unserved system at declaration time`() {
    // declaredFor runs once at catalog load and must be stable across a reload; what this box
    // serves changes between requests, so that filter belongs to resolve and not here.
    val out =
      ServeRelatedCatalogs.declaredFor(declared("never-heard-of-it" to null), selfSystem = null)
    assertEquals(1, out.size)
  }

  @Test
  fun `resolve falls back to this component's own id`() {
    // The short "<system>" form means "the same component, over there" — which is the common case
    // and the reason componentId is optional at all.
    val links =
      ServeRelatedCatalogs.resolve(
        declared("samples" to null),
        componentId = "Button/Filled",
        selfSystem = "kit",
        registered = setOf("samples"),
        isLive = { true },
      )
    assertEquals("Button/Filled", links.single().componentId)
  }

  @Test
  fun `resolve prefers a declared counterpart id over this component's`() {
    val links =
      ServeRelatedCatalogs.resolve(
        declared("samples" to "Button/ButtonSample"),
        componentId = "Button/Filled",
        selfSystem = "kit",
        registered = setOf("samples"),
        isLive = { true },
      )
    assertEquals("Button/ButtonSample", links.single().componentId)
  }

  @Test
  fun `resolve drops a system this box does not serve`() {
    // Rendered dead was the alternative and is worse: a catalog declares `related` from its own
    // source tree, so a fork serving one catalog would show a column of links to nothing and be
    // right to think the server was broken.
    val links =
      ServeRelatedCatalogs.resolve(
        declared("samples" to null, "tiles" to null),
        componentId = "Button",
        selfSystem = "kit",
        registered = setOf("samples"),
        isLive = { true },
      )
    assertEquals(listOf("samples"), links.map { it.system })
  }

  @Test
  fun `resolve keeps a registered but not-yet-live system, marked not live`() {
    // Registered-and-loading is a real state on a cold box. It is carried rather than filtered so a
    // surface can choose a disabled affordance; filtering here would make the decision for it.
    val links =
      ServeRelatedCatalogs.resolve(
        declared("samples" to null),
        componentId = "Button",
        selfSystem = "kit",
        registered = setOf("samples"),
        isLive = { false },
      )
    assertEquals(false, links.single().live)
  }

  @Test
  fun `resolve asks isLive only for systems that are registered`() {
    // A box serving none of a catalog's declared links should not pay a session lookup per link.
    val asked = mutableListOf<String>()
    ServeRelatedCatalogs.resolve(
      declared("a" to null, "b" to null, "c" to null),
      componentId = "Button",
      selfSystem = "kit",
      registered = setOf("b"),
      isLive = {
        asked += it
        true
      },
    )
    assertEquals(listOf("b"), asked)
  }

  @Test
  fun `inverse answers which components point at a catalog`() {
    // The back-link's whole job: a samples page asks the kit catalog "does anything in you point at
    // me?", and gets back the component to link to.
    val out =
      ServeRelatedCatalogs.inverse(
        mapOf(
          "Button" to declared("samples" to "ButtonSample", "tiles" to null),
          "Card" to declared("samples" to "CardSample"),
        ),
        targetSystem = "samples",
      )
    assertEquals(mapOf("ButtonSample" to listOf("Button"), "CardSample" to listOf("Card")), out)
  }

  @Test
  fun `inverse falls back to the declaring component's own id`() {
    // The short "<system>" form means "the same component, over there", so its inverse is the same
    // component, back here.
    val out =
      ServeRelatedCatalogs.inverse(
        mapOf("Button" to declared("samples" to null)),
        targetSystem = "samples",
      )
    assertEquals(mapOf("Button" to listOf("Button")), out)
  }

  @Test
  fun `inverse collects every component pointing at one target, without duplicates`() {
    // Two kit components can legitimately point at one sample — a shared call site — and one
    // component restating a link must not show up twice.
    val out =
      ServeRelatedCatalogs.inverse(
        mapOf(
          "Button" to
            listOf(
              ServeRelatedCatalogs.Declared("samples", "ButtonSample"),
              ServeRelatedCatalogs.Declared("samples", "ButtonSample", "again"),
            ),
          "IconButton" to declared("samples" to "ButtonSample"),
        ),
        targetSystem = "samples",
      )
    assertEquals(mapOf("ButtonSample" to listOf("Button", "IconButton")), out)
  }

  @Test
  fun `inverse ignores links to other systems`() {
    val out =
      ServeRelatedCatalogs.inverse(
        mapOf("Button" to declared("tiles" to "ButtonTile")),
        targetSystem = "samples",
      )
    assertTrue(out.isEmpty(), out.toString())
  }

  @Test
  fun `inverse of a catalog that declares nothing is empty`() {
    assertTrue(ServeRelatedCatalogs.inverse(emptyMap(), targetSystem = "samples").isEmpty())
  }

  @Test
  fun `resolve of nothing is empty, not null`() {
    assertTrue(
      ServeRelatedCatalogs.resolve(
          emptyList(),
          componentId = "Button",
          selfSystem = "kit",
          registered = setOf("samples"),
          isLive = { true },
        )
        .isEmpty()
    )
  }
}
