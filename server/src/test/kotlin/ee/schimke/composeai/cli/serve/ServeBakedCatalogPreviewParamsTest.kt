package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-only catalog path resolving a per-preview ground and device frame.
 *
 * This is the path a **published catalog is normally served on**: `ServeCatalogStore` stages
 * `previews/variants.json` and no root `previews.json`, and there is no trusted live daemon whose
 * twin could supply the missing params. Everything a preview says about itself in `@Preview` was
 * therefore lost here — `showBackground` / `backgroundColor` came back as the annotation defaults,
 * so `PreviewBackdrop` fell back to the catalog's declared stage for every card, and once the
 * device clip arrived it never resolved at all, drawing round Wear comparisons on a square stage on
 * this path and nowhere else.
 *
 * The fixtures below are deliberately shaped like the production manifest — a staging directory
 * with baked PNGs and a `variants.json`, no `previews.json` anywhere — because a test that handed
 * the host a bundle manifest would pass without the feature under test existing.
 */
class ServeBakedCatalogPreviewParamsTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /** A published catalog staged the way the store writes it: baked pixels + `variants.json`. */
  private fun bakedCatalog(variantsJson: String): ServeBundleHost {
    val dir = Files.createTempDirectory("baked-catalog").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/timetext.png").writeBytes(png())
    File(dir, "previews/sticker.png").writeBytes(png())
    File(dir, "previews/variants.json").writeText(variantsJson)
    return ServeBundleHost(dir, label = "wear-m3", title = "Wear M3")
  }

  private fun previewOf(host: ServeBundleHost, id: String): ServePreview =
    host.previews.first { it.id == id }

  @Test
  fun `a baked catalog preview keeps the ground and frame its annotation stated`() {
    val host =
      bakedCatalog(
        """
        {"timetext":{"componentId":"Template/TimeText",
          "previewParams":{"uiMode":32,"showBackground":true,"backgroundColor":4278190080,
            "device":"id:wearos_large_round","widthDp":227,"heightDp":227}}}
        """
          .trimIndent()
      )
    val preview = previewOf(host, "timetext")
    assertTrue(preview.showBackground, "showBackground survives the catalog round trip")
    assertEquals(0xFF000000L, preview.backgroundColor)
    assertEquals(32, preview.uiMode)
    assertEquals(ServeDeviceFrame(227.0, 227.0, isRound = true), preview.deviceFrame)
  }

  /**
   * The density the page converts dp boxes against, resolved per preview rather than assumed.
   *
   * `data-render-density` was the constant `2` on every page of every catalog. The size-override
   * inputs are authored in dp and multiplied by it before they go on the wire as `widthPx` /
   * `min…Px` / `max…Px`, so on any preview rendering at another density the reader's number reached
   * the renderer in the wrong unit — and 42 of the 56 device ids `DeviceDimensions` knows are not
   * 2.0. A published catalog is where this bites hardest: `PreviewParamsMeta` carries no density
   * field at all, so the only thing that can answer is the `@Preview(device = …)` the entry does
   * carry, resolved through the same catalog the renderer resolves it through.
   */
  @Test
  fun `a published catalog resolves its render density from the device it names`() {
    val host =
      bakedCatalog(
        """
        {"timetext":{"componentId":"Template/TimeText",
          "previewParams":{"device":"id:wearos_large_round","widthDp":227,"heightDp":227}},
         "sticker":{"componentId":"Template/Sticker",
          "previewParams":{"device":"id:pixel_5"}}}
        """
          .trimIndent()
      )
    // Wear is 2.0, which is why the Wear catalogs were right by luck under the old constant…
    assertEquals(2.0f, host.renderDensityFor("timetext"))
    // …and a phone is not, which is why nothing else was.
    assertEquals(2.75f, host.renderDensityFor("sticker"))
  }

  @Test
  fun `a preview naming no device leaves the density to the page's fallback`() {
    val host =
      bakedCatalog(
        """{"timetext":{"componentId":"Template/TimeText","previewParams":{"widthDp":227}}}"""
      )
    assertNull(
      host.renderDensityFor("timetext"),
      "null is 'nothing here knows', which is not the same claim as 'it renders at 2'",
    )
    assertNull(host.renderDensityFor("no-such-preview"))
  }

  @Test
  fun `the attribute keeps a whole density short and a fractional one exact`() {
    // The viewer reads it with `parseFloat`, which takes either — but the attribute is also what a
    // reader inspecting the page sees when they ask why a dp box became the px it did.
    assertEquals("2", ServeWeb.renderDensityAttr(null), "the documented fallback")
    assertEquals("2", ServeWeb.renderDensityAttr(2.0f))
    assertEquals("2.75", ServeWeb.renderDensityAttr(2.75f))
    assertEquals("2.625", ServeWeb.renderDensityAttr(2.625f))
    // A manifest can say anything; a nonsense density is the fallback rather than a broken page.
    assertEquals("2", ServeWeb.renderDensityAttr(0f))
    assertEquals("2", ServeWeb.renderDensityAttr(-1f))
    assertEquals("2", ServeWeb.renderDensityAttr(Float.NaN))
  }

  @Test
  fun `the stated ground reaches the comparison stage, rather than the catalog's default`() {
    // The point of the whole change, asserted where a reader would see it. `PreviewBackdrop` would
    // otherwise answer from `declaredSurface` — the catalog's dark plate `#1C1B1F` — for a preview
    // that explicitly named opaque black, and the stage would be the wrong colour on every card.
    val host =
      bakedCatalog(
        """
        {"timetext":{"previewParams":{"showBackground":true,"backgroundColor":4278190080,
          "device":"id:wearos_large_round","widthDp":227,"heightDp":227}}}
        """
          .trimIndent()
      )
    val preview = previewOf(host, "timetext")
    val backdrop = ServeWeb.backdropFor(preview, darkFirst = true)
    assertEquals("#FF000000", backdrop.color)
    assertEquals(PreviewBackdropSourceOfTest, backdrop.source.wire)
    // …and the shape with it, which is what made this visible: a round device on a square stage.
    assertEquals("circle(50% at 50% 50%)", ServeWeb.stageClipFor(preview))
  }

  @Test
  fun `a catalog published before this existed keeps exactly its old behaviour`() {
    // Every field defaults and the record is absent, so an older delivery branch must still parse
    // and must not gain an invented ground or a clip. This is the state of every published catalog
    // until `design-artifacts.yml` regenerates it, so it is the common case for a while.
    val host =
      bakedCatalog("""{"sticker":{"componentId":"Button/Filled","section":"Components"}}""")
    val preview = previewOf(host, "sticker")
    assertEquals(false, preview.showBackground)
    assertEquals(0L, preview.backgroundColor)
    assertEquals(0, preview.uiMode)
    assertNull(preview.deviceFrame)
    assertNull(ServeWeb.stageClipFor(preview))
  }

  @Test
  fun `a preview with no variant entry at all is unaffected`() {
    // `sticker` is staged as pixels but named by no manifest entry — a flat catalog's ordinary
    // shape. It must not throw and must not acquire params from its neighbour.
    val host = bakedCatalog("""{"timetext":{"previewParams":{"device":"id:wearos_large_round"}}}""")
    assertNull(previewOf(host, "sticker").deviceFrame)
    assertTrue(previewOf(host, "timetext").deviceFrame?.isRound == true)
  }

  private companion object {
    /** `PreviewBackdrop.Source.PREVIEW_BACKGROUND_COLOR`'s wire form. */
    const val PreviewBackdropSourceOfTest = "preview.backgroundColor"
  }
}
