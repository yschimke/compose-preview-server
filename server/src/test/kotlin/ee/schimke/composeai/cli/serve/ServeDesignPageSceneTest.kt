package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageBlendMode
import ee.schimke.composeai.designpages.PageFrame
import ee.schimke.composeai.designpages.PageImage
import ee.schimke.composeai.designpages.PageLayerPlacement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **scene** a design page is drawn on: shared plates beneath the export, and how each layer
 * composites over them.
 *
 * The case that named all of it is the Glimmer kit's Buttons sheet — component sets authored
 * `mix-blend-mode: screen` over five image backplates. Pruning the plates to keep the page under
 * its size cap left that screen-blended drawing compositing over a pale fallback fill, washing the
 * whole sheet toward white. So the two halves are tested together: carrying the plate and
 * compositing over it correctly are one requirement.
 */
class ServeDesignPageSceneTest {

  private val plateId = "a".repeat(64)

  private val svg =
    """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 2000 1000"><g data-node-id="1:1"/></svg>"""

  private fun page(
    designBlend: PageBlendMode = PageBlendMode.SOURCE_OVER,
    renderBlend: PageBlendMode = PageBlendMode.SOURCE_OVER,
  ) =
    DesignPage(
      id = "buttons",
      name = "Buttons",
      nodeId = "1:0",
      frame = PageFrame(2000.0, 1000.0),
      image = PageImage(uri = "buttons.svg"),
      designBlend = designBlend,
      renderBlend = renderBlend,
    )

  private fun placement(over: PageLayerPlacement.() -> PageLayerPlacement = { this }) =
    PageLayerPlacement(asset = plateId, x = 0.0, y = 0.0, width = 2000.0, height = 1000.0).over()

  private fun render(
    page: DesignPage = page(),
    background: List<PageLayerPlacement> = emptyList(),
    assetHref: (String) -> String? = { "/m3/pages/assets/$it" },
  ): String =
    ServeWeb.designPage(
      moduleLabel = "m3",
      page = page,
      svg = svg,
      token = "t",
      background = background,
      assetHref = assetHref,
    )

  // --- the stage ------------------------------------------------------------

  @Test
  fun `a placed plate is drawn beneath the export`() {
    val html = render(background = listOf(placement()))
    assertContains(html, """class="cp-page-plate"""")
    assertContains(html, """src="/m3/pages/assets/$plateId"""")
    // Beneath: the plate must precede the inlined export in document order.
    assertTrue(html.indexOf("cp-page-plate") < html.indexOf("<svg"))
    // And the stage gets a backdrop, so a blended layer has something to blend with.
    assertContains(html, "data-has-plates")
  }

  /**
   * The box is in the page's coordinate space; the stage is that frame scaled to the column. A
   * percentage is the one unit that survives both that scale and the zoom transform.
   */
  @Test
  fun `a placement is positioned as a percentage of the frame`() {
    val html =
      render(
        background =
          listOf(placement { copy(x = 500.0, y = 250.0, width = 1000.0, height = 500.0) })
      )
    assertContains(html, "left:25.0000%")
    assertContains(html, "top:25.0000%")
    assertContains(html, "width:50.0000%")
    assertContains(html, "height:50.0000%")
  }

  @Test
  fun `one plate serves many placements`() {
    val five = (0 until 5).map { i -> placement { copy(x = i * 400.0, width = 400.0) } }
    assertEquals(5, render(background = five).split("cp-page-plate").size - 1)
  }

  // --- compositing ----------------------------------------------------------

  /**
   * The blend reaches the stylesheet as an identifier it enumerates, never as inline style — so the
   * only compositing that can happen is compositing this server compiled in.
   */
  @Test
  fun `blends are emitted as data attributes, not inline style`() {
    val html =
      render(
        page = page(designBlend = PageBlendMode.SCREEN, renderBlend = PageBlendMode.PLUS_LIGHTER)
      )
    assertContains(html, """data-design-blend="screen"""")
    assertContains(html, """data-render-blend="plus-lighter"""")
    assertFalse(html.contains("mix-blend-mode:"), "no inline mix-blend-mode may be emitted")
  }

  /** `source-over` is the default; stating it would be noise in the markup. */
  @Test
  fun `the default blend is omitted`() {
    val html = render()
    assertFalse(html.contains("data-design-blend"))
    assertFalse(html.contains("data-render-blend"))
  }

  @Test
  fun `a placement carries its own blend and fit`() {
    val html =
      render(
        background =
          listOf(
            placement { copy(blend = PageBlendMode.MULTIPLY, fit = PageLayerPlacement.CONTAIN) }
          )
      )
    assertContains(html, """data-blend="multiply"""")
    assertContains(html, """data-fit="contain"""")
  }

  // --- what must not draw ---------------------------------------------------

  /**
   * The caller returns null for a plate that did not survive verification. A hole in the scene is
   * worse than a scene without that layer, so the placement is dropped rather than drawn broken.
   */
  @Test
  fun `a placement whose plate has no url is dropped`() {
    val html = render(background = listOf(placement()), assetHref = { null })
    assertFalse(html.contains("cp-page-plate"))
    assertFalse(html.contains("data-has-plates"))
  }

  @Test
  fun `a placement with no drawable box is dropped`() {
    val html = render(background = listOf(placement { copy(width = 0.0) }))
    assertFalse(html.contains("cp-page-plate"))
  }

  /** A sheet that needs no backdrop renders the stage exactly as it did before any of this. */
  @Test
  fun `a page with no plates is unchanged`() {
    val html = render()
    assertFalse(html.contains("cp-page-plate"))
    assertFalse(html.contains("data-has-plates"))
    assertContains(html, "<svg")
  }

  /** Plate URLs are caller-minted; the markup must escape whatever it is handed. */
  @Test
  fun `a plate url is html-escaped`() {
    val html = render(background = listOf(placement()), assetHref = { "/a?x=1&y=\"2\"" })
    assertContains(html, "&amp;")
    assertFalse(html.contains("""src="/a?x=1&y="2"""""))
  }
}
