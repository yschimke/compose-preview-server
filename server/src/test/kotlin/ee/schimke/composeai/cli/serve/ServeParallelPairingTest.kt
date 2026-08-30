package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Cell-granular pairing across two catalogs of one design system (issue #4838): which of the
 * sibling's renders of a component is the counterpart of this one, and what the reader is told when
 * there isn't one.
 */
class ServeParallelPairingTest {

  private fun preview(
    id: String,
    componentId: String = "Button/Child",
    state: String? = null,
    theme: String? = null,
    size: String? = null,
    props: Map<String, String>? = null,
  ) =
    ServePreview(
      id = id,
      label = id,
      state = state,
      theme = theme,
      size = size,
      props = props?.let { JsonObject(it.mapValues { (_, v) -> JsonPrimitive(v) }) },
      componentId = componentId,
    )

  private fun figmaReference(id: String, node: String) =
    DesignReference(
      id = id,
      previewId = id,
      raster = DesignReferenceRaster(path = "references/$id.png"),
      source =
        DesignReferenceSource(provider = "figma", uri = "figma:B24oss2tTeXAFykyeyusz0/$node"),
    )

  private fun pair(
    preview: ServePreview,
    candidates: List<ServePreview>,
    kitNodes: Set<String> = emptySet(),
    kitNodesFor: (ServePreview) -> Set<String> = { emptySet() },
  ) = ServeParallelPairing.pair(preview, kitNodes, candidates, kitNodesFor)

  @Test
  fun `a state cell pairs with the sibling's same state, not with its default`() {
    // The gap this fixes: the walk took the first published render of the component, so every
    // non-default cell of a kit set compared against the sibling's `default` sticker.
    val disabled = preview("button-child__disabled", state = "disabled")
    val candidates =
      listOf(
        preview("child-button__default", state = "default"),
        preview("child-button__disabled", state = "disabled"),
        preview("child-button__icon", state = "icon"),
      )
    val pairing = pair(disabled, candidates)
    assertEquals("child-button__disabled", pairing?.preview?.id)
    assertEquals(ServeParallelPairing.Basis.VARIANT_CELL, pairing?.basis)
  }

  @Test
  fun `the shared design-kit node outranks the catalogs' own spelling of the axis`() {
    // Two catalogs of one kit need not spell an axis alike (`icon` vs `leading-icon`); the node
    // both design maps resolved to says they are the same cell regardless.
    val ours = preview("button-child__icon", state = "icon")
    val theirs = preview("child-button__leading-icon", state = "leading-icon")
    val pairing =
      pair(
        ours,
        listOf(preview("child-button__default", state = "default"), theirs),
        kitNodes = ServeParallelPairing.kitNodesOf(listOf(figmaReference("ours", "73:6"))),
        kitNodesFor = { candidate ->
          if (candidate.id == theirs.id)
            ServeParallelPairing.kitNodesOf(listOf(figmaReference("theirs", "73-6")))
          else ServeParallelPairing.kitNodesOf(listOf(figmaReference("other", "12:4")))
        },
      )
    assertEquals("child-button__leading-icon", pairing?.preview?.id)
    assertEquals(ServeParallelPairing.Basis.KIT_CELL, pairing?.basis)
  }

  @Test
  fun `a cell the sibling does not draw falls back to its canonical sticker`() {
    // The floor: today's behaviour, kept — but reported as CANONICAL so the caller can say so.
    val pairing =
      pair(
        preview("button-child__left", state = "left"),
        listOf(
          preview("child-button__default", state = "default"),
          preview("child-button__disabled", state = "disabled"),
        ),
      )
    assertEquals("child-button__default", pairing?.preview?.id)
    assertEquals(ServeParallelPairing.Basis.CANONICAL, pairing?.basis)
  }

  @Test
  fun `a sibling publishing nothing for the component pairs with nothing`() {
    assertNull(pair(preview("button-child"), emptyList()))
  }

  @Test
  fun `the default cell pairs by being the default, however the two catalogs spell it`() {
    val pairing =
      pair(
        preview("button-child"),
        listOf(
          preview("child-button__disabled", state = "disabled"),
          preview("child-button__default", state = "default"),
        ),
      )
    assertEquals("child-button__default", pairing?.preview?.id)
    assertEquals(ServeParallelPairing.Basis.VARIANT_CELL, pairing?.basis)
  }

  @Test
  fun `the same-theme render wins, and an unthemed sibling is still paired`() {
    val dark = preview("button-child__dark", state = "disabled", theme = "dark")
    val themed =
      listOf(
        preview("child-button__light", state = "disabled", theme = "light"),
        preview("child-button__dark", state = "disabled", theme = "dark"),
      )
    assertEquals("child-button__dark", pair(dark, themed)?.preview?.id)
    val unthemed = listOf(preview("child-button__disabled", state = "disabled"))
    assertEquals("child-button__disabled", pair(dark, unthemed)?.preview?.id)
  }

  @Test
  fun `a breakpoint the two catalogs name differently still pairs on the rest of the cell`() {
    // `__compact` on one sheet is `__192dp` on the other. The size is preferred where both agree
    // and dropped where they cannot, rather than costing the cell its counterpart.
    val ours = preview("button-child__compact", state = "disabled", size = "compact")
    val pairing =
      pair(
        ours,
        listOf(
          preview("child-button__default", state = "default"),
          preview("child-button__192dp", state = "disabled", size = "192dp"),
        ),
      )
    assertEquals("child-button__192dp", pairing?.preview?.id)
    assertEquals(ServeParallelPairing.Basis.VARIANT_CELL, pairing?.basis)
  }

  @Test
  fun `an exact size match beats one that agrees only on the rest of the cell`() {
    val ours = preview("button-child__192dp", state = "disabled", size = "192dp")
    val pairing =
      pair(
        ours,
        listOf(
          preview("child-button__compact", state = "disabled", size = "compact"),
          preview("child-button__192dp", state = "disabled", size = "192dp"),
        ),
      )
    assertEquals("child-button__192dp", pairing?.preview?.id)
  }

  @Test
  fun `props axes pair on their own`() {
    val ours = preview("button-child__rtl", props = mapOf("direction" to "RTL"))
    val pairing =
      pair(
        ours,
        listOf(
          preview("child-button__default"),
          preview("child-button__rtl", props = mapOf("direction" to "rtl")),
        ),
      )
    assertEquals("child-button__rtl", pairing?.preview?.id)
  }

  @Test
  fun `the cell label names what was compared, and is empty for a default render`() {
    assertEquals(
      "state=disabled, size=192dp, content=icon",
      ServeParallelPairing.cellLabel(
        preview("x", state = "disabled", size = "192dp", props = mapOf("content" to "icon"))
      ),
    )
    assertEquals("", ServeParallelPairing.cellLabel(preview("x", state = "default")))
  }

  @Test
  fun `only figma-backed references key a kit cell`() {
    // An HTML export or a committed PNG is a path inside one catalog's own delivery branch, so it
    // identifies nothing the sibling could share.
    assertEquals(
      setOf("B24oss2tTeXAFykyeyusz0/73:6"),
      ServeParallelPairing.kitNodesOf(
        listOf(
          figmaReference("a", "73:6"),
          DesignReference(
            id = "b",
            previewId = "b",
            raster = DesignReferenceRaster(path = "references/b.png"),
            source = DesignReferenceSource(provider = "file", uri = "references/b.png"),
          ),
        )
      ),
    )
  }
}
