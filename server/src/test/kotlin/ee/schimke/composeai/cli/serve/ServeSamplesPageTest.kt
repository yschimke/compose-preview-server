package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The **samples** page role — what a catalog of call sites looks like, and why it is not the
 * ordinary component page with things switched off by a reader.
 *
 * A sample is not a rendition of a reference. That single fact decides both halves of the shape:
 * every comparison lane goes, because there is nothing this render is meant to match and a
 * difference is not a defect; and the source stands beside the render rather than behind a chip
 * that swaps it out, because the code is what the page is for.
 *
 * The role is DECLARED by the catalog (`catalog.json`'s `display.role`) and never inferred from its
 * name — `.github/scripts/ui-builder-catalog-literals.sh` is the standing reason this module may
 * not know which catalogs exist.
 */
class ServeSamplesPageTest {

  private val token = "t"

  private val preview =
    ServePreview("button-sample__ideal__default__light", "Button sample", state = "default")

  private fun page(
    role: ServeWeb.PageRole,
    usageHref: String? = "/usage/button-sample",
    withReference: Boolean = true,
  ) =
    ServeWeb.viewerPage(
      preview,
      token,
      pageRole = role,
      usageHref = usageHref,
      designReference =
        if (!withReference) null
        else
          DesignReference(
            id = "button-figma",
            previewId = preview.id,
            label = "Button",
            raster = DesignReferenceRaster(path = "references/button-figma.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
    )

  @Test
  fun `an unknown or absent role is the ordinary catalog page`() {
    // A catalog published by a newer producer declares a role this server has never heard of. It
    // has to degrade to the page it would have rendered anyway — the alternative is a catalog that
    // stops working when the producer moves ahead of the server.
    assertEquals(ServeWeb.PageRole.CATALOG, ServeWeb.PageRole.of(null))
    assertEquals(ServeWeb.PageRole.CATALOG, ServeWeb.PageRole.of(""))
    assertEquals(ServeWeb.PageRole.CATALOG, ServeWeb.PageRole.of("  "))
    assertEquals(ServeWeb.PageRole.CATALOG, ServeWeb.PageRole.of("tiles-from-the-future"))
  }

  @Test
  fun `the role is read case and whitespace insensitively`() {
    // It is a hand-authored field in a hand-authored file. A leading space is a typo, not a
    // different role.
    assertEquals(ServeWeb.PageRole.SAMPLES, ServeWeb.PageRole.of("samples"))
    assertEquals(ServeWeb.PageRole.SAMPLES, ServeWeb.PageRole.of("Samples"))
    assertEquals(ServeWeb.PageRole.SAMPLES, ServeWeb.PageRole.of(" SAMPLES "))
  }

  @Test
  fun `a samples page stands the source beside the render`() {
    val html = page(ServeWeb.PageRole.SAMPLES)
    assertTrue(html.contains("data-source-lane=\"side\""), html)
  }

  @Test
  fun `an ordinary catalog page keeps the source behind its chip`() {
    assertFalse(page(ServeWeb.PageRole.CATALOG).contains("data-source-lane"))
  }

  @Test
  fun `a samples page with no usage source keeps the single-column stage`() {
    // A column of nothing is worse than no column: the lane exists to put the code beside the
    // render, and a preview whose source could not be derived has no code to put there.
    val html = page(ServeWeb.PageRole.SAMPLES, usageHref = null)
    assertFalse(html.contains("data-source-lane"), html)
  }

  @Test
  fun `a samples page drops the design comparison`() {
    // The lane, the chip and the reference's own raster all go together: each asks "does this match
    // the reference", and a sample is not an answer to that question.
    val samples = page(ServeWeb.PageRole.SAMPLES)
    val catalog = page(ServeWeb.PageRole.CATALOG)
    assertTrue(catalog.contains("button-figma"), "the ordinary page still compares")
    assertFalse(samples.contains("button-figma"), samples)
  }

  @Test
  fun `the samples role is not the reader's Catalog mode`() {
    // Catalog mode is how much chrome the READER wants and applies to every catalog alike; a role
    // is a property of the thing being shown. So a samples page keeps the affordances Catalog mode
    // strips — the preview id, the component drawer, the dev controls — and drops only what its own
    // subject makes meaningless.
    val samples = page(ServeWeb.PageRole.SAMPLES)
    assertTrue(
      samples.contains("cp-preview-id"),
      "a samples page is not a stripped reading surface",
    )
  }
}
