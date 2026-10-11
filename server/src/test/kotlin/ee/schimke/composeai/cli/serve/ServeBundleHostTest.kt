package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.CropOffset
import ee.schimke.composeai.imagecrop.RenderSize
import ee.schimke.composeai.imagecrop.WindowSize
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeBundleHostTest {

  private fun bundle(vararg previews: Pair<String, ByteArray>): File {
    val dir = java.nio.file.Files.createTempDirectory("bundle").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    val previewsDir = File(dir, "previews").apply { mkdirs() }
    previews.forEach { (id, png) ->
      File(previewsDir, "$id.png").apply { parentFile?.mkdirs() }.writeBytes(png)
    }
    return dir
  }

  /** A bundle carrying `annotations/index.json` — the published typography over its baked frame. */
  private fun annotatedBundle(previewId: String, vararg records: String): File {
    val dir = bundle(previewId to byteArrayOf(4, 2))
    File(dir, ServeAnnotationStore.DIRECTORY).mkdirs()
    File(dir, "${ServeAnnotationStore.DIRECTORY}/${ServeAnnotationStore.INDEX_FILE}")
      .writeText(
        """{"schema":"compose-preview-annotations/v1",
           "previews":{"$previewId":[${records.joinToString(",")}]}}"""
      )
    return dir
  }

  @Test
  fun `the known-difference document a host serves is the one its generation was built on`() {
    // A refresh swaps the staged directory before registering a rebuilt host, while everything else
    // this host serves was read at build time. Reading this file per call would pair a new document
    // with an old inventory, producing false `orphaned-target` findings; a rebuild delivers the
    // fresh document within one tick anyway.
    val dir = bundle("com.example.Red" to byteArrayOf(4, 2))
    val file =
      File(dir, "${ServeKnownDifferences.DIRECTORY}/${ServeKnownDifferences.DOCUMENT_FILE}")
    file.parentFile?.mkdirs()
    val built = """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[]}"""
    file.writeText(built)

    val host = ServeBundleHost(dir, label = "generation")
    // The directory is swapped underneath a live host on every refresh; this stands in for that.
    file.writeText(
      """{"schema":"${ServeKnownDifferences.SCHEMA}","acceptances":[
        {"id":"glyph","issue":"https://github.com/yschimke/m3-catalog/issues/40",
         "mask":"mask.png"}]}"""
    )

    val served = host.knownDifferences()
    assertTrue(served is ServeKnownDifferences.Document.Text)
    assertEquals(built, served.text, "the host serves its own generation's document")
  }

  private val typographyRecord =
    """{"kind":"typography","bounds":{"x":4,"y":6,"width":40,"height":12},
       "label":"bodyMedium 14sp/20","role":"Label"}"""

  private val layoutRecord =
    """{"kind":"layout","bounds":{"x":0,"y":0,"width":80,"height":32},"label":"pad 16dp"}"""

  @Test
  fun `published typography answers the annotations lane without a daemon`() {
    // A published catalog measured typography over the PNG this host serves, so the Typography
    // layer has a source without a semantics tree.
    val host = ServeBundleHost(annotatedBundle("button__light", typographyRecord), label = "b")

    assertTrue(host.hasPublishedTypographyFor("button__light"))
    val out = host.renderAnnotations("button__light", PreviewOverrides()) as AnnotationsOutcome.Ok
    val json = out.json.decodeToString()
    assertTrue(json.contains("\"previewId\":\"button__light\""))
    assertTrue(json.contains("bodyMedium 14sp/20"))
    assertTrue(json.contains("\"tags\""), "the tag index travels with the annotations")
  }

  @Test
  fun `the annotations lane draws only the kinds the overlay has layers for`() {
    // `layout` is published for the compare page; `<cp-inspect-layers>` has no layout layer, so it
    // would land under no heading.
    val host =
      ServeBundleHost(
        annotatedBundle("button__light", typographyRecord, layoutRecord),
        label = "b",
      )

    val json =
      (host.renderAnnotations("button__light", PreviewOverrides()) as AnnotationsOutcome.Ok)
        .json
        .decodeToString()
    assertTrue(json.contains("bodyMedium 14sp/20"))
    assertFalse(json.contains("pad 16dp"))
  }

  @Test
  fun `a bundle with nothing published keeps the annotations lane unavailable`() {
    // Layout-only, an unannotated preview, and an id this host doesn't carry: all three must report
    // NotFound so the viewer omits the control rather than offering one that draws an empty legend.
    val layoutOnly = ServeBundleHost(annotatedBundle("button__light", layoutRecord), label = "b")
    assertFalse(layoutOnly.hasPublishedTypographyFor("button__light"))
    assertEquals(
      AnnotationsOutcome.NotFound,
      layoutOnly.renderAnnotations("button__light", PreviewOverrides()),
    )

    val plain = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(4, 2)), label = "b")
    assertFalse(plain.hasPublishedTypographyFor("com.example.Red"))
    assertEquals(
      AnnotationsOutcome.NotFound,
      plain.renderAnnotations("com.example.Red", PreviewOverrides()),
    )

    val annotated = ServeBundleHost(annotatedBundle("button__light", typographyRecord), label = "b")
    assertFalse(annotated.hasPublishedTypographyFor("no.such.Preview"))
    assertEquals(
      AnnotationsOutcome.NotFound,
      annotated.renderAnnotations("no.such.Preview", PreviewOverrides()),
    )
  }

  @Test
  fun `a session with no staging lane is never waiting for a published comparison`() {
    // `pending` gates cacheability. A plain uploaded bundle has no comparison lane, so a missing
    // `rc-compare/index.json` must not mark it pending forever.
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(4, 2)), label = "b")
    assertFalse(host.rcComparePending())

    // A catalog whose lane IS scheduled keeps the provisional answer until the lane settles.
    val staging =
      ServeBundleHost(
        bundle("com.example.Red" to byteArrayOf(4, 2)),
        label = "b",
        stagesRcCompare = true,
      )
    assertTrue(staging.rcComparePending())
  }

  @Test
  fun `the no-admission fast path serves real local pixels`() {
    // Against the real host: a fake returning bytes regardless would hide a fast path that never
    // finds the file.
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(4, 2)), label = "b")

    val ok = host.bakedRender("com.example.Red", PreviewOverrides())
    assertTrue(ok != null, "local pixels must be servable without admission")
    assertTrue(byteArrayOf(4, 2).contentEquals(ok.png))
    assertEquals(RenderOutcome.Generation.BAKED, ok.generation)
    // Nested ids resolve the same way.
    val nested = ServeBundleHost(bundle("group/com.example.Blue" to byteArrayOf(7)), label = "b")
    assertTrue(nested.bakedRender("group/com.example.Blue", PreviewOverrides()) != null)
    // An id this host does not carry has nothing to serve.
    assertNull(host.bakedRender("com.example.Missing", PreviewOverrides()))
  }

  @Test
  fun `nested preview ids (with slashes) are discovered and rendered`() {
    val host = ServeBundleHost(bundle("group/com.example.Red" to byteArrayOf(4, 2)), label = "b")
    assertEquals(listOf("group/com.example.Red"), host.previews.map { it.id })
    val ok = host.render("group/com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(4, 2).contentEquals(ok.png))
  }

  /**
   * Which RC player drew the baked pixels comes from the manifest. Everything bakes through the
   * embedded player (`RemoteOverridablePreview`'s default), except previews pinning
   * `RemoteViewPreviewWrapper`, for which androidx-embedded is a genuine re-render.
   */
  @Test
  fun `the baked player is read from a preview's pinned wrapper`() {
    val dir = bundle("com.example.Card" to byteArrayOf(1), "com.example.Pinned" to byteArrayOf(2))
    // Both are Remote Compose previews — a captured `ir/<id>.rc` is what makes "which player drew
    // the baked PNG" a question with an answer at all.
    File(dir, "ir").mkdirs()
    File(dir, "ir/com.example.Card.rc").writeBytes(byteArrayOf(9))
    File(dir, "ir/com.example.Pinned.rc").writeBytes(byteArrayOf(9))
    File(dir, "previews.json")
      .writeText(
        """
        {
          "module": ":samples:cmp",
          "variant": "debug",
          "previews": [
            { "id": "com.example.Card", "functionName": "Card", "className": "com.example.CardKt" },
            { "id": "com.example.Pinned", "functionName": "Pinned",
              "className": "com.example.CardKt",
              "params": { "wrapperClassName":
                          "ee.schimke.composeai.daemon.RemoteViewPreviewWrapper" } }
          ]
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "compose-m3")
    assertEquals(RemoteComposePlayerKind.EMBEDDED, host.bakedRcPlayer("com.example.Card"))
    assertEquals(RemoteComposePlayerKind.VIEW, host.bakedRcPlayer("com.example.Pinned"))
    // An id with no captured document had no player, so `rcPlayer` requests on it stay genuine
    // overrides.
    assertEquals(null, host.bakedRcPlayer("com.example.Absent"))
  }

  /**
   * A session with no manifest (published catalogs stage no `previews.json`) answers unknown rather
   * than inferring the default, which would be confidently wrong for a view-pinned preview.
   */
  @Test
  fun `a catalog that records no capture player is unknown, not assumed embedded`() {
    val dir = bundle("com.example.Card" to byteArrayOf(1))
    File(dir, "ir").mkdirs()
    File(dir, "ir/com.example.Card.rc").writeBytes(byteArrayOf(9))
    assertEquals(null, ServeBundleHost(dir, label = "remote-m3").bakedRcPlayer("com.example.Card"))
  }

  /** …and one that DOES record it is believed, which is the channel that closes the gap above. */
  @Test
  fun `a catalog's recorded capture player is read back`() {
    val dir = bundle("com.example.Card" to byteArrayOf(1), "com.example.Pinned" to byteArrayOf(2))
    File(dir, "ir").mkdirs()
    File(dir, "ir/com.example.Card.rc").writeBytes(byteArrayOf(9))
    File(dir, "ir/com.example.Pinned.rc").writeBytes(byteArrayOf(9))
    File(dir, "previews").mkdirs()
    File(dir, "previews/variants.json")
      .writeText(
        """
        {
          "com.example.Card": {"previewParams":{"capturePlayer":"cmp-android"}},
          "com.example.Pinned": {"previewParams":{"capturePlayer":"java"}}
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "remote-m3")
    assertEquals(RemoteComposePlayerKind.EMBEDDED, host.bakedRcPlayer("com.example.Card"))
    assertEquals(RemoteComposePlayerKind.VIEW, host.bakedRcPlayer("com.example.Pinned"))

    // ...and the picker offers that lane: a bare URL serves those pixels, and `java` has no
    // rc-compare column to be staged from.
    assertTrue(
      RcPlayerBackend.ANDROIDX_EMBEDDED in host.enabledRcPlayersFor("com.example.Card"),
      "the embedded lane is offered where baked is that player",
    )
    assertTrue(
      RcPlayerBackend.ANDROIDX_VIEW in host.enabledRcPlayersFor("com.example.Pinned"),
      "the view lane is offered where baked is that player: " +
        "${host.enabledRcPlayersFor("com.example.Pinned")}",
    )
    assertFalse(
      RcPlayerBackend.ANDROIDX_VIEW in host.enabledRcPlayersFor("com.example.Card"),
      "…and not offered where it is someone else's pixels",
    )
  }

  /**
   * Newer daemons record players by implementation name; older ones used `cmp-android` for
   * embedded. Both read back the same.
   */
  @Test
  fun `a capture player recorded by its implementation name is read back`() {
    val dir = bundle("com.example.Card" to byteArrayOf(1), "com.example.Pinned" to byteArrayOf(2))
    File(dir, "ir").mkdirs()
    File(dir, "ir/com.example.Card.rc").writeBytes(byteArrayOf(9))
    File(dir, "ir/com.example.Pinned.rc").writeBytes(byteArrayOf(9))
    File(dir, "previews").mkdirs()
    File(dir, "previews/variants.json")
      .writeText(
        """
        {
          "com.example.Card": {"previewParams":{"capturePlayer":"androidx-embedded"}},
          "com.example.Pinned": {"previewParams":{"capturePlayer":"androidx-view"}}
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "remote-m3")
    assertEquals(RemoteComposePlayerKind.EMBEDDED, host.bakedRcPlayer("com.example.Card"))
    assertEquals(RemoteComposePlayerKind.VIEW, host.bakedRcPlayer("com.example.Pinned"))
  }

  @Test
  fun `declared themes are read from the bundle's previews_json when present`() {
    val dir = bundle("com.example.Card" to byteArrayOf(1))
    // A previews.json carrying two synthetic THEME_CATALOG entries (the shape discovery emits) plus
    // an ordinary preview that must NOT be mistaken for a theme.
    File(dir, "previews.json")
      .writeText(
        """
        {
          "module": ":samples:cmp",
          "variant": "debug",
          "previews": [
            { "id": "com.example.Card", "functionName": "Card", "className": "com.example.CardKt" },
            { "id": "themecatalog__Brand_Light", "functionName": "Brand Light theme",
              "className": "com.example.BrandLightTheme",
              "params": { "name": "Brand Light", "group": "Brand", "kind": "THEME_CATALOG",
                          "wrapperClassName": "com.example.BrandLightTheme" } },
            { "id": "themecatalog__Brand_Dark", "functionName": "Brand Dark theme",
              "className": "com.example.BrandDarkTheme",
              "params": { "name": "Brand Dark", "group": "Brand", "kind": "THEME_CATALOG",
                          "wrapperClassName": "com.example.BrandDarkTheme" } }
          ]
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "compose-m3")
    assertEquals(
      listOf(
        "Brand Light" to "com.example.BrandLightTheme",
        "Brand Dark" to "com.example.BrandDarkTheme",
      ),
      host.declaredThemes.map { it.name to it.providerFqn },
    )
    assertEquals(listOf("Brand", "Brand"), host.declaredThemes.map { it.group })
  }

  @Test
  fun `no declared themes without a previews_json (a bare WebEmbed)`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(1)), label = "b")
    assertTrue(host.declaredThemes.isEmpty())
  }

  @Test
  fun `preview uiMode is read from the bundle's previews_json`() {
    val dir = bundle("com.example.NightCard" to byteArrayOf(1))
    File(dir, "previews.json")
      .writeText(
        """
        {
          "module": ":samples:cmp",
          "variant": "debug",
          "previews": [
            { "id": "com.example.NightCard", "functionName": "NightCard",
              "className": "com.example.CardKt", "params": { "uiMode": 32 } }
          ]
        }
        """
          .trimIndent()
      )

    assertEquals(0x20, ServeBundleHost(dir, label = "b").previews.single().uiMode)
  }

  @Test
  fun `previews are discovered from the bundle's png files, sorted`() {
    val host =
      ServeBundleHost(
        bundle("com.example.Red" to byteArrayOf(1), "com.example.Blue" to byteArrayOf(2)),
        label = "demo@abc",
      )
    assertEquals(listOf("com.example.Blue", "com.example.Red"), host.previews.map { it.id })
    assertEquals("demo@abc", host.label)
  }

  @Test
  fun `previews are tagged with state and theme from the variants manifest`() {
    val dir =
      bundle(
        "checkbox__ideal__default__light" to byteArrayOf(1),
        "checkbox__ideal__unchecked__light" to byteArrayOf(2),
      )
    File(dir, "previews/${ServeCatalogStore.VARIANTS_FILE}")
      .writeText(
        """
        {
          "checkbox__ideal__default__light": { "state": "default", "theme": "light" },
          "checkbox__ideal__unchecked__light": { "state": "unchecked", "theme": "light" }
        }
        """
          .trimIndent()
      )
    val host = ServeBundleHost(dir, label = "compose-m3")
    val byId = host.previews.associateBy { it.id }
    assertEquals(
      "default" to "light",
      byId.getValue("checkbox__ideal__default__light").let { it.state to it.theme },
    )
    assertEquals(
      "unchecked" to "light",
      byId.getValue("checkbox__ideal__unchecked__light").let { it.state to it.theme },
    )
  }

  @Test
  fun `a plain bundle without a variants manifest keeps null state and theme`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(1)), label = "b")
    val p = host.previews.single()
    assertNull(p.state)
    assertNull(p.theme)
  }

  @Test
  fun `render returns the baked png and NotFound for unknown ids`() {
    val host = ServeBundleHost(bundle("com.example.Red" to byteArrayOf(9, 8, 7)), label = "b")

    val ok = host.render("com.example.Red", PreviewOverrides()) as RenderOutcome.Ok
    assertTrue(byteArrayOf(9, 8, 7).contentEquals(ok.png))
    assertEquals(RenderOutcome.NotFound, host.render("com.example.Missing", PreviewOverrides()))
  }

  @Test
  fun `renderSvg serves the baked figma svg with hybrid rasters inlined`() {
    val dir = bundle("button-filled__ideal__default__dark" to byteArrayOf(1))
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "button-filled.svg")
      .writeText("<svg><image href=\"button-filled.figma-raster/n0.png\"/></svg>")
    File(figma, "button-filled.figma-raster").mkdirs()
    File(figma, "button-filled.figma-raster/n0.png").writeBytes(byteArrayOf(1, 2, 3))

    val host = ServeBundleHost(dir, label = "compose-m3", figmaDir = figma)
    val ok =
      host.renderSvg("button-filled__ideal__default__dark", PreviewOverrides()) as SvgOutcome.Ok
    val svg = ok.svg.decodeToString()
    val expected = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    assertTrue(svg.contains("data:image/png;base64,$expected"), "raster inlined: $svg")
    assertFalse(svg.contains("figma-raster/"), "no dangling external ref: $svg")
  }

  @Test
  fun `renderSvg prefers the per-variant vector over the light-preferred slug svg`() {
    // The catalog ships both the slug `figma/<slug>.svg` (light-preferred) and per-variant
    // `figma/<slug>/<variant>.svg`; a `…__dark` id must not get the light slug vector.
    val dir =
      bundle(
        "speakerdetails__ideal__default__dark__compact" to byteArrayOf(1),
        "speakerdetails__ideal__default__light__compact" to byteArrayOf(2),
      )
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "speakerdetails.svg").writeText("<svg data-variant=\"slug-light\"/>")
    val slugDir = File(figma, "speakerdetails").apply { mkdirs() }
    File(slugDir, "ideal__default__dark__compact.svg")
      .writeText(
        "<svg data-variant=\"dark\"><image href=\"ideal__default__dark__compact.figma-raster/n.png\"/></svg>"
      )
    File(slugDir, "ideal__default__dark__compact.figma-raster").mkdirs()
    File(slugDir, "ideal__default__dark__compact.figma-raster/n.png").writeBytes(byteArrayOf(7))

    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    val dark =
      host.renderSvg("speakerdetails__ideal__default__dark__compact", PreviewOverrides())
        as SvgOutcome.Ok
    val darkSvg = dark.svg.decodeToString()
    assertTrue(darkSvg.contains("data-variant=\"dark\""), "dark id must serve the dark vector")
    // The variant's crops live in a sibling `<variant>.figma-raster/` dir and must still inline.
    val expected = java.util.Base64.getEncoder().encodeToString(byteArrayOf(7))
    assertTrue(darkSvg.contains("data:image/png;base64,$expected"), "variant raster inlined")

    // A variant with no per-variant file falls back to the slug vector (pre-emit catalogs).
    val light =
      host.renderSvg("speakerdetails__ideal__default__light__compact", PreviewOverrides())
        as SvgOutcome.Ok
    assertTrue(light.svg.decodeToString().contains("data-variant=\"slug-light\""))
  }

  @Test
  fun `renderSvgForWeb links rasters to the catalog branch instead of embedding`() {
    val dir = bundle("speakerdetails__ideal__default__dark__compact" to byteArrayOf(1))
    val figma = File(dir, "figma").apply { mkdirs() }
    val slugDir = File(figma, "speakerdetails").apply { mkdirs() }
    File(slugDir, "ideal__default__dark__compact.svg")
      .writeText("<svg><image href=\"ideal__default__dark__compact.figma-raster/232.png\"/></svg>")
    File(slugDir, "ideal__default__dark__compact.figma-raster").mkdirs()
    File(slugDir, "ideal__default__dark__compact.figma-raster/232.png").writeBytes(byteArrayOf(7))

    val host =
      ServeBundleHost(
        dir,
        label = "confetti-mobile",
        figmaDir = figma,
        provenance =
          ServeWeb.CatalogProvenance(
            repo = "joreilly/Confetti",
            branch = "design-artifacts/confetti-mobile",
          ),
      )
    val ok =
      host.renderSvgForWeb("speakerdetails__ideal__default__dark__compact", PreviewOverrides())
        as SvgOutcome.Ok
    val svg = ok.svg.decodeToString()
    assertTrue(
      svg.contains(
        "href=\"https://raw.githubusercontent.com/joreilly/Confetti/design-artifacts/" +
          "confetti-mobile/figma/speakerdetails/ideal__default__dark__compact.figma-raster/232.png\""
      ),
      "web mode must link the crop to its published branch file: $svg",
    )
    assertFalse(svg.contains("data:image/png"), "web mode must not embed the crop: $svg")
  }

  @Test
  fun `renderSvgForWeb without provenance falls back to the self-contained embed`() {
    val dir = bundle("button-filled__ideal__default__dark" to byteArrayOf(1))
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "button-filled.svg")
      .writeText("<svg><image href=\"button-filled.figma-raster/n0.png\"/></svg>")
    File(figma, "button-filled.figma-raster").mkdirs()
    File(figma, "button-filled.figma-raster/n0.png").writeBytes(byteArrayOf(1, 2, 3))

    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    val ok =
      host.renderSvgForWeb("button-filled__ideal__default__dark", PreviewOverrides())
        as SvgOutcome.Ok
    val expected = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
    assertTrue(
      ok.svg.decodeToString().contains("data:image/png;base64,$expected"),
      "no branch to link → stay self-contained",
    )
  }

  @Test
  fun `renderSvg is NotFound without a figma dir, for unknown ids, and for missing svgs`() {
    val dir =
      bundle("button-filled__ideal__default__dark" to byteArrayOf(1), "badge__x" to byteArrayOf(2))
    val overrides = PreviewOverrides()

    // A plain bundle (no figmaDir) 404s the .svg lane.
    val plain = ServeBundleHost(dir, label = "b")
    assertEquals(
      SvgOutcome.NotFound,
      plain.renderSvg("button-filled__ideal__default__dark", overrides),
    )

    // With a figma dir: a known id whose slug carried no svg, and an unknown id, both 404.
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "button-filled.svg").writeText("<svg/>")
    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    assertEquals(SvgOutcome.NotFound, host.renderSvg("badge__x", overrides)) // slug badge: no svg
    assertEquals(SvgOutcome.NotFound, host.renderSvg("nope__x", overrides)) // unknown id
  }

  @Test
  fun `hasSvgExportFor gates the SVG control on the preview's own baked vector`() {
    val dir =
      bundle("button-filled__ideal__default__dark" to byteArrayOf(1), "badge__x" to byteArrayOf(2))

    // A plain bundle (no figmaDir) advertises no SVG for any preview.
    val plain = ServeBundleHost(dir, label = "b")
    assertFalse(plain.hasSvgExportFor("button-filled__ideal__default__dark"))

    // The session-wide flag is true when a figma dir exists, but per preview only those with a
    // vector report true, so the viewer doesn't offer a failing SVG control.
    val figma = File(dir, "figma").apply { mkdirs() }
    File(figma, "button-filled.svg").writeText("<svg/>")
    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    assertTrue(host.hasSvgExport, "session-wide flag stays true because a figma dir exists")
    assertTrue(host.hasSvgExportFor("button-filled__ideal__default__dark"))
    assertFalse(host.hasSvgExportFor("badge__x"), "slug badge carried no baked svg")
    assertFalse(host.hasSvgExportFor("nope__x"), "unknown id")
  }

  @Test
  fun `renderSvg does not inline a raster href that escapes the figma dir`() {
    val dir = bundle("button-filled__ideal__default__dark" to byteArrayOf(1))
    val figma = File(dir, "figma").apply { mkdirs() }
    // A secret file OUTSIDE the figma dir; a traversal href must not read it.
    File(dir, "secret.png").writeBytes(byteArrayOf(9, 9, 9))
    File(figma, "button-filled.svg")
      .writeText("<svg><image href=\"button-filled.figma-raster/../../secret.png\"/></svg>")

    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    val ok =
      host.renderSvg("button-filled__ideal__default__dark", PreviewOverrides()) as SvgOutcome.Ok
    val svg = ok.svg.decodeToString()
    val leaked = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9, 9, 9))
    assertFalse(svg.contains(leaked), "must not inline a file outside the figma dir: $svg")
    assertTrue(svg.contains("../../secret.png"), "the escaping href is left as a plain ref: $svg")
  }

  @Test
  fun `a bundle host has no live lane`() {
    val host = ServeBundleHost(bundle("p" to byteArrayOf(1)), label = "b")
    assertNull(host.subscribeStream("p", PreviewOverrides(), null, null) {})
    assertEquals(0, host.activeStreamCount())
    host.close() // no-op, must not throw
  }

  @Test
  fun `looksLikeBundle detects a previews directory with pngs`() {
    assertTrue(ServeBundleHost.looksLikeBundle(bundle("p" to byteArrayOf(1))))
    val empty = java.nio.file.Files.createTempDirectory("empty").toFile().also { it.deleteOnExit() }
    assertFalse(ServeBundleHost.looksLikeBundle(empty))
  }

  @Test
  fun `a throttled pinned read is not remembered as a missing file`() {
    // `pinnedMisses` is permanent only for real 404s (`(commit, path)` is immutable); a throttle
    // says nothing about the revision and must not be memoised.
    val commit = "1".repeat(40)
    val previewId = "button-filled__ideal__default__dark"
    val dir = java.nio.file.Files.createTempDirectory("pinned-throttle").toFile()
    dir.deleteOnExit()
    File(dir, "previews").mkdirs()

    val answers =
      ArrayDeque(listOf<BranchFetch>(BranchFetch.Throttled(1), BranchFetch.Ok(byteArrayOf(7))))
    var calls = 0
    val host =
      ServeBundleHost(
        dir,
        label = "compose-m3",
        title = "Compose Material 3",
        bakedBranchPaths = mapOf(previewId to "images/button-filled/ideal__default__dark.png"),
        fetchPinnedAssetOutcome = { _, _ ->
          calls++
          answers.removeFirstOrNull() ?: BranchFetch.NotFound
        },
      )

    // First read is throttled, so the caller gets nothing…
    assertTrue(host.pinnedRender(commit, previewId) is ServeBundleHost.PinnedOutcome.Missing)
    assertEquals(1, calls)
    // …and asking again actually asks again, rather than being refused from memory.
    val second = host.pinnedRender(commit, previewId)
    assertTrue(second is ServeBundleHost.PinnedOutcome.Ok, "a throttle must not poison the cache")
    assertEquals(2, calls)
  }

  @Test
  fun `a genuinely missing pinned asset is still only asked for once`() {
    // The other half of the same property: the permanent negative cache is worth keeping, and this
    // is the case it was built for.
    val commit = "2".repeat(40)
    val previewId = "button-filled__ideal__default__dark"
    val dir = java.nio.file.Files.createTempDirectory("pinned-missing").toFile()
    dir.deleteOnExit()
    File(dir, "previews").mkdirs()

    var calls = 0
    val host =
      ServeBundleHost(
        dir,
        label = "compose-m3",
        title = "Compose Material 3",
        bakedBranchPaths = mapOf(previewId to "images/button-filled/ideal__default__dark.png"),
        fetchPinnedAssetOutcome = { _, _ ->
          calls++
          BranchFetch.NotFound
        },
      )

    assertTrue(host.pinnedRender(commit, previewId) is ServeBundleHost.PinnedOutcome.Missing)
    assertTrue(host.pinnedRender(commit, previewId) is ServeBundleHost.PinnedOutcome.Missing)
    assertEquals(1, calls, "a known-absent asset is refused from memory")
  }

  /** A real PNG, so the host can read its IHDR dimensions the way it does in production. */
  private fun pngOf(width: Int, height: Int): ByteArray {
    val image =
      java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val out = java.io.ByteArrayOutputStream()
    javax.imageio.ImageIO.write(image, "png", out)
    return out.toByteArray()
  }

  @Test
  fun `a declared capture gutter is trimmed off a card's thumbnail`() {
    // A button captured with `@CaptureGutter(all = 4, bottom = 5)` at 2.625 (PNG 271x150): fitting
    // whole canvases drew it smaller than its gutter-less siblings. The renderer recorded the
    // gutter, so the crop is exact.
    val dir = bundle("button-elevated__ideal__default__light" to pngOf(271, 150))
    File(dir, "previews.json")
      .writeText(
        """
        {"module":"catalog","variant":"main","previews":[
          {"id":"button-elevated__ideal__default__light","functionName":"ElevatedButtonSticker",
           "className":"ButtonsKt",
           "params":{"density":2.625,"captureGutter":{"start":4,"top":4,"end":4,"bottom":5}}}
        ]}
        """
          .trimIndent()
      )

    val crop =
      ServeBundleHost(dir, label = "b").contentCrop("button-elevated__ideal__default__light")

    assertEquals(
      ContentCrop(
        window = WindowSize(249, 126),
        render = RenderSize(271, 150),
        offset = CropOffset(-11, -11),
        clip = false,
        // The native box and capped axis ride along so the page can re-derive the window width for
        // a narrower viewport.
        nativeWindowW = 249,
        nativeCapAxis = 126,
      ),
      crop,
    )
  }

  @Test
  fun `a preview with no capture gutter keeps the plain uncropped image`() {
    val dir = bundle("button-filled__ideal__default__light" to pngOf(249, 126))
    File(dir, "previews.json")
      .writeText(
        """
        {"module":"catalog","variant":"main","previews":[
          {"id":"button-filled__ideal__default__light","functionName":"FilledButton",
           "className":"ButtonsKt","params":{"density":2.625}}
        ]}
        """
          .trimIndent()
      )

    assertNull(
      ServeBundleHost(dir, label = "b").contentCrop("button-filled__ideal__default__light")
    )
  }

  @Test
  fun `an RTL capture's leading gutter is published as the right-hand margin`() {
    // The renderer placed `start` per layout direction, so an RTL capture's leading margin is on
    // the right; the crop must be told.
    val dir = bundle("sticker__ideal__rtl" to pngOf(200, 100))
    File(dir, "previews.json")
      .writeText(
        """
        {"module":"catalog","variant":"main","previews":[
          {"id":"sticker__ideal__rtl","functionName":"Sticker","className":"Kt",
           "params":{"density":1.0,"locale":"ar",
                     "captureGutter":{"start":4,"top":1,"end":12,"bottom":5}}}
        ]}
        """
          .trimIndent()
      )

    val crop = ServeBundleHost(dir, label = "b").contentCrop("sticker__ideal__rtl")

    assertEquals(184, crop?.window?.w) // 200 - 4 - 12, whichever way round
    assertEquals(94, crop?.window?.h)
    // …but the render is offset by the RIGHT-hand 12, not by the declared `start`.
    assertEquals(-12, crop?.offset?.left)
    assertEquals(-1, crop?.offset?.top)
  }

  @Test
  fun `a gutter crop served while a vector is still landing is not memoised`() {
    // Vectors fill in the background; answer with the gutter meanwhile, but don't cache it.
    val dir = bundle("sticker__ideal__default" to pngOf(200, 100))
    File(dir, "previews.json")
      .writeText(
        """
        {"module":"catalog","variant":"main","previews":[
          {"id":"sticker__ideal__default","functionName":"Sticker","className":"Kt",
           "params":{"density":1.0,"captureGutter":{"start":4,"top":4,"end":4,"bottom":4}}}
        ]}
        """
          .trimIndent()
      )
    val figma = File(dir, "figma").apply { mkdirs() } // no vector for this id yet

    val host = ServeBundleHost(dir, label = "b", figmaDir = figma)
    val first = host.contentCrop("sticker__ideal__default")
    assertEquals(192, first?.window?.w)

    // The vector lands: its box (not the gutter's) now decides, which could not happen if the
    // first answer had been cached.
    File(figma, "sticker.svg")
      .writeText("""<svg viewBox="0 0 40 20"><g transform="translate(-80, -40)"></g></svg>""")
    val second = host.contentCrop("sticker__ideal__default")
    // The vector's own box, not unioned with the render's drawn extent (which includes the gutter's
    // shadow room and would shrink the component).
    assertEquals(40, second?.window?.w)
    assertEquals(20, second?.window?.h)
    assertEquals(false, second?.clip)
  }

  @Test
  fun `a rebuilt host reuses the crops computed over the same files`() {
    // Rebuilding a host on every resume used to decode every card's PNG again on the request
    // thread; the answer depends only on the files.
    val dir = bundle("sticker__ideal__default" to pngOf(200, 100))
    File(dir, "previews.json")
      .writeText(
        """
        {"module":"catalog","variant":"main","previews":[
          {"id":"sticker__ideal__default","functionName":"Sticker","className":"Kt",
           "params":{"density":1.0,"captureGutter":{"start":4,"top":4,"end":4,"bottom":4}}}
        ]}
        """
          .trimIndent()
      )
    assertEquals(
      192,
      ServeBundleHost(dir, label = "b").contentCrop("sticker__ideal__default")?.window?.w,
    )

    // Same path, size and timestamp but unreadable bytes: only a reused answer can still crop it.
    val png = dir.walkTopDown().single { it.name == "sticker__ideal__default.png" }
    val stamp = png.lastModified()
    png.writeBytes(ByteArray(png.length().toInt()))
    png.setLastModified(stamp)
    assertEquals(
      192,
      ServeBundleHost(dir, label = "b").contentCrop("sticker__ideal__default")?.window?.w,
      "a rebuilt host must not decode the PNG again",
    )

    // A file that changed is a different key, and is computed afresh.
    png.writeBytes(pngOf(300, 100))
    png.setLastModified(stamp + 10_000)
    assertEquals(
      292,
      ServeBundleHost(dir, label = "b").contentCrop("sticker__ideal__default")?.window?.w,
    )
  }
}
