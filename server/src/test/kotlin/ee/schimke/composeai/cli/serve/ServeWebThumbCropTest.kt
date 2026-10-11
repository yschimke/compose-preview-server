package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.CropOffset
import ee.schimke.composeai.imagecrop.RenderSize
import ee.schimke.composeai.imagecrop.WindowSize
import ee.schimke.composeai.imagecrop.computeGutterCrop
import ee.schimke.composeai.imagecrop.computeThumbCrop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the thumbnail crop wiring: with a [ContentCrop] the `<img>` is wrapped in a `.cp-crop`
 * window sized by aspect-ratio and framed in percentages (so it shrinks with narrow cards); without
 * one the card keeps the plain fit-to-box `<img>`.
 */
class ServeWebThumbCropTest {

  private val previews =
    listOf(ServePreview(id = "filled-button__ideal__default__compact", label = "Filled"))
  private val crop =
    ContentCrop(
      window = WindowSize(120, 48),
      render = RenderSize(454, 454),
      offset = CropOffset(-167, -203),
      nativeWindowW = 120,
      nativeCapAxis = 120,
    )

  @Test
  fun `a catalog card with a crop wraps the image in an aspect-sized clip window`() {
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { crop },
      )
    // The window's natural width is the box, but it sizes by aspect-ratio (so `max-width: 100%` can
    // shrink it on a narrow card) rather than a fixed height.
    assertTrue(
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:2.5;--cp-crop-max-w:120px;aspect-ratio:120/48\""
      ),
      "clip window sized to the box by aspect-ratio",
    )
    // Render img framed in percentages of the box (454/120, -167/120, -203/48), so the whole frame
    // scales as one when the window shrinks.
    assertTrue(
      html.contains("style=\"width:378.3333%;left:-139.1667%;top:-422.9167%\""),
      "render img sized + offset in box-percentages to show only the component",
    )
  }

  @Test
  fun `a catalog card with no crop keeps the plain image (no clip window)`() {
    val html = ServeWeb.landingPage("compose-m3", previews, token = "t", basePath = "/compose-m3")
    // The `.cp-crop` CSS rule ships on every page; assert the absence of the *wrapper element*.
    assertFalse(html.contains("class=\"cp-crop\""), "uncropped cards carry no clip window")
    assertTrue(html.contains("<img loading=\"lazy\" alt=\"Filled\""), "plain fit-to-box image")
  }

  @Test
  fun `the home hero card is framed when the system carries a hero crop`() {
    val system =
      ServeWeb.HomeSystem(
        system = "wear-m3",
        title = "Wear Compose Material 3",
        subtitle = null,
        previewCount = 34,
        trust = null,
        heroPreviewId = "filled-button__ideal__default__compact",
        heroCrop = crop,
      )
    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)
    assertTrue(
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:2.5;--cp-crop-max-w:120px;aspect-ratio:120/48\""
      ),
      "hero framed to its box",
    )
  }

  @Test
  fun `a gutter window is marked so its overflow is not hidden`() {
    // The pixels outside a capture gutter's box are the component's own shadow — the reason the
    // gutter was captured. The window lines the box up with its neighbours; it must not crop.
    val html =
      ServeWeb.landingPage(
        "compose-m3",
        previews,
        token = "t",
        basePath = "/compose-m3",
        thumbCrop = { crop.copy(clip = false) },
      )
    assertTrue(html.contains("class=\"cp-crop cp-crop--bleed\""), "bleeding window marked")
    val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertTrue(css.contains(".cp-crop--bleed { overflow: visible; }"), "and allowed to overflow")
    // …through the card too, which otherwise clips its own content to its rounded edge.
    assertTrue(
      css.contains(".cp-card:has(.cp-crop--bleed) { overflow: visible; }"),
      "the card lets a bleeding window's shadow reach the grid gap",
    )
  }

  @Test
  fun `a capped window publishes its width relative to the display cap, not as frozen px`() {
    // A 300x100 component on a 600x600 render draws 240x80 at the 240px cap. The window publishes
    // the ratio (width per px of cap, plus the 1x ceiling) rather than a fixed width, so the
    // stylesheet can re-derive it at the narrow-viewport cap (#4544).
    val capped = computeThumbCrop(svg("0 0 300 100", "translate(-150, -250)"), 600, 600)!!
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { capped },
      )
    // min(300px, 1 * cap): 240px at desktop, 200px narrow — the same drop as the plain `<img>`. The
    // two ratios differ here (1 against the largest edge, 3 against height) because a content crop
    // caps on `max(w, h)`; the front door's fixed-height well must therefore size on
    // `--cp-crop-w-per-h`.
    assertTrue(
      html.contains(
        "class=\"cp-crop\" style=\"--cp-crop-w-per-cap:1;--cp-crop-w-per-h:3;--cp-crop-max-w:300px;aspect-ratio:240/80\""
      ),
      "window width published as a ratio of the cap",
    )
  }

  @Test
  fun `a gutter window is capped on its height, so it matches a plain image's max-height`() {
    // A 249x126 button in a 271x150 render with an 11/11/11/13 gutter; the cap acts on height, so
    // the ratio is 249/126.
    val gutter = computeGutterCrop(11, 11, 11, 13, 271, 150)!!
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { gutter },
      )
    // For a gutter crop the cap axis is the height, so both ratios are the same number.
    assertTrue(
      html.contains("--cp-crop-w-per-cap:1.9762;--cp-crop-w-per-h:1.9762;--cp-crop-max-w:249px"),
      "gutter window width published per cap PIXEL of height (249/126), the cap axis being height",
    )
  }

  @Test
  fun `a crop with no native size keeps the fixed-px window`() {
    val handmade =
      ContentCrop(
        window = WindowSize(120, 48),
        render = RenderSize(454, 454),
        offset = CropOffset(-167, -203),
      )
    val html =
      ServeWeb.landingPage(
        "wear-m3",
        previews,
        token = "t",
        basePath = "/wear-m3",
        thumbCrop = { handmade },
      )
    assertTrue(
      html.contains("class=\"cp-crop\" style=\"width:120px;aspect-ratio:120/48\""),
      "no native size to re-derive from, so the window stays at its computed px",
    )
  }

  /** A minimal figma-svg carrying a content [viewBox] and placing [translate]. */
  private fun svg(viewBox: String, translate: String) =
    """<svg viewBox="$viewBox"><g transform="$translate"></g></svg>"""

  @Test
  fun `the crop CSS is present so the clip window actually clips`() {
    val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertTrue(
      css.contains(".cp-crop { position: relative; overflow: hidden;"),
      "clip style shipped",
    )
    assertTrue(
      css.contains(".cp-imgwrap .cp-crop img { position: absolute; max-width: none;"),
      "img escapes the fit-to-box cap",
    )
    // No `max-height` on the window: with an inline `aspect-ratio` it would squash rather than
    // scale. The cap is applied by `computeThumbCrop`/`computeGutterCrop`.
    assertFalse(
      css.contains(
        ".cp-crop { position: relative; overflow: hidden; display: block; max-width: 100%; max-height"
      ),
      "no CSS height cap on an aspect-ratio window",
    )
    // The cap the window resolves its ratio against, and the narrow-viewport drop (#4544).
    assertTrue(
      css.contains(
        "width: min(var(--cp-crop-max-w, 100%), calc(var(--cp-crop-w-per-cap, 9999) * var(--cp-thumb-cap, 240px)));"
      ),
      "window width derived from the cap, defaulting to the desktop 240px",
    )
    assertTrue(css.contains(".cp-crop { --cp-thumb-cap: 200px; }"), "narrow-viewport cap")
    // ...and it must drop in lockstep with the plain image's cap, or the mismatch just moves.
    assertTrue(css.contains(".cp-imgwrap img { max-height: 200px; }"), "plain image's narrow cap")
    // A front-door system card's hero sits in a fixed 220px row where the plain hero is exempt from
    // the grid's cap; a cropped hero takes its cap through the variable, so it must be set to the
    // row's content height (196px, after 12px padding each side) or it draws at the grid's cap.
    assertTrue(
      css.contains(
        "width: min(var(--cp-crop-max-w, 100%), calc(var(--cp-crop-w-per-h, var(--cp-crop-w-per-cap, 9999)) * 196px)); }"
      ),
      "a system-card window sizes against the box's own height, capped at the well's 196px",
    )
    // Not `--bleed` and not `--cp-thumb-cap`: neither distinguishes a gutter crop's height axis
    // from a content crop's largest edge, so a landscape window would shrink needlessly.
    assertFalse(
      css.contains(".cp-syslist .cp-crop--bleed { --cp-thumb-cap:"),
      "--bleed is not a proxy for a height-capped crop",
    )
    // The padding that makes it 196 rather than 220 — if this moves, so must the number above.
    assertTrue(
      css.contains("background: var(--cp-surface); padding: 12px; }"),
      "the image well's padding, which the hero cap is derived from",
    )
  }

  /** The section an operator's `catalogs.json` declares for Android's samples. */
  private val androidSamples =
    ServeWeb.HomeGroup(
      heading = "android/compose-samples",
      noun = "sample(s)",
      // The preview branches currently live in the fork; both spellings are Android's samples.
      repos = setOf("android/compose-samples", "yschimke/compose-samples"),
    )

  private val designSystems =
    ServeWeb.HomeGroup(
      heading = "Design Systems",
      noun = "design system(s)",
      repos = setOf("yschimke/compose-ai-tools"),
    )

  @Test
  fun `all compose sample catalogs are attributed to android and shown on the homepage`() {
    val sampleIds =
      listOf("jetnews", "jetcaster", "jetcaster-wear", "jetchat", "jetsnack", "jetlagged", "reply")
    val systems = sampleIds.map { id ->
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        // The preview branches currently live in this fork. That fetch/trust origin must not
        // make the public homepage attribute Android's samples to the fork owner.
        trust = "branch:yschimke/compose-samples@design-artifacts/$id",
        sourceRepo = "yschimke/compose-samples",
        heroPreviewId = null,
        group = androidSamples,
      )
    }

    val html = ServeWeb.homeIndexPage(systems, token = "t", isPublic = true)

    assertTrue(html.contains("<h1 class=\"cp-head\">android/compose-samples</h1>"))
    assertFalse(html.contains("<h1 class=\"cp-head\">yschimke org</h1>"))
    sampleIds.forEach { id ->
      assertTrue(html.contains("href=\"/$id/\""), "$id is linked from the homepage")
    }
  }

  @Test
  fun `a reused sample id is attributed to its actual catalog repository`() {
    // The config declares the samples section, but these bytes came from an unrelated repo — the
    // claim doesn't hold, so the card falls back to its own publisher rather than Android's.
    val system =
      ServeWeb.HomeSystem(
        system = "jetnews",
        title = "Unrelated Jetnews",
        subtitle = null,
        previewCount = 1,
        trust = "branch:someorg/unrelated@design-artifacts/jetnews",
        sourceRepo = "someorg/unrelated",
        heroPreviewId = null,
        group = androidSamples,
      )

    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)

    assertFalse(html.contains("<h1 class=\"cp-head\">android/compose-samples</h1>"))
    assertTrue(html.contains("<h1 class=\"cp-head\">someorg repositories</h1>"))
    assertTrue(html.contains("href=\"/jetnews/\""))
  }

  @Test
  fun `a reused design-system id is attributed to its actual catalog repository`() {
    // A catalog id is claimed by whoever publishes it, so `compose-m3` from someone else must not
    // read as the official design system.
    val impostor =
      ServeWeb.HomeSystem(
        system = "compose-m3",
        title = "Definitely Material 3",
        subtitle = null,
        previewCount = 1,
        trust = "branch:someorg/unrelated@design-artifacts/compose-m3",
        sourceRepo = "someorg/unrelated",
        heroPreviewId = null,
        group = designSystems,
      )

    val sections = ServeWeb.homeSections(listOf(impostor))

    assertEquals(listOf("someorg repositories"), sections.map { it.heading })
  }

  @Test
  fun `the real design systems are grouped by their source repository`() {
    val real =
      listOf("compose-m3", "wear-m3", "remote-m3").map { id ->
        ServeWeb.HomeSystem(
          system = id,
          title = id,
          subtitle = null,
          previewCount = 1,
          trust = "branch:yschimke/compose-ai-tools@design-artifacts/$id",
          sourceRepo = "yschimke/compose-ai-tools",
          heroPreviewId = null,
          group = designSystems,
        )
      }

    val sections = ServeWeb.homeSections(real)

    assertEquals(listOf("Design Systems"), sections.map { it.heading })
    assertEquals(3, sections.single().systems.size)
    assertEquals("design system(s)", sections.single().noun)
  }

  @Test
  fun `a catalog with no provenance is never promoted into a curated section`() {
    // Unattributed bytes: an old catalog with no provenance carries no publisher claim at all, so
    // it lands in Other rather than inheriting a curated section from its config entry.
    val unattributed =
      ServeWeb.HomeSystem(
        system = "wear-m3",
        title = "Wear Compose Material 3",
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = null,
        heroPreviewId = null,
        group = designSystems,
      )

    assertEquals(listOf("Other"), ServeWeb.homeSections(listOf(unattributed)).map { it.heading })
  }

  @Test
  fun `an ungrouped catalog is sectioned by its repo owner, and Other reads last`() {
    // Nothing here is hardcoded per catalog: a server publishing catalogs this build has never
    // heard of still gets one section per publisher, with the unattributed bucket pinned last.
    fun system(id: String, repo: String?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
      )

    val sections =
      ServeWeb.homeSections(
        listOf(
          system("mystery", null),
          system("confetti-wear", "joreilly/Confetti"),
          system("cadence", "yschimke/cadence"),
          system("confetti-mobile", "joreilly/Confetti"),
        )
      )

    assertEquals(
      listOf("joreilly repositories", "yschimke repositories", "Other"),
      sections.map { it.heading },
    )
    assertEquals(2, sections.first().systems.size)
    assertEquals("catalog(s)", sections.first().noun)
  }

  @Test
  fun `a group priority lifts its section above the ones the catalog list reaches first`() {
    // Without grouping, sections come out in first-appearance order, so a later-registered design
    // system reads last.
    fun system(id: String, repo: String, group: ServeWeb.HomeGroup?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
        group = group,
      )

    val systems =
      listOf(
        system("jetnews", "yschimke/compose-samples", androidSamples),
        system("confetti-wear", "joreilly/Confetti", null),
        system("m3-catalog", "yschimke/compose-ai-tools", designSystems),
      )

    assertEquals(
      listOf("android/compose-samples", "joreilly repositories", "Design Systems"),
      ServeWeb.homeSections(systems).map { it.heading },
      "with no priority declared, order is still where the catalog list first reaches a section",
    )

    val lifted = systems.map {
      if (it.group == designSystems) it.copy(group = designSystems.copy(priority = 100)) else it
    }

    assertEquals(
      listOf("Design Systems", "android/compose-samples", "joreilly repositories"),
      ServeWeb.homeSections(lifted).map { it.heading },
      "a declared priority orders the sections; the rest keep first-appearance order",
    )
  }

  @Test
  fun `a section that two claims spell the same way takes the highest priority declared`() {
    // Headings are operator text, not unique keys; recording only the first claim's priority would
    // strand a lifted group under an earlier heading-mate.
    fun system(id: String, repo: String, group: ServeWeb.HomeGroup?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
        group = group,
      )

    val unlifted = ServeWeb.HomeGroup(heading = "Design Systems", repos = setOf("someorg/legacy"))
    val lifted =
      ServeWeb.HomeGroup(
        heading = "Design Systems",
        repos = setOf("yschimke/m3-catalog"),
        priority = 100,
      )

    val sections =
      ServeWeb.homeSections(
        listOf(
          system("legacy", "someorg/legacy", unlifted),
          system("jetnews", "yschimke/compose-samples", androidSamples),
          system("m3-catalog", "yschimke/m3-catalog", lifted),
        )
      )

    assertEquals(listOf("Design Systems", "android/compose-samples"), sections.map { it.heading })
    assertEquals(listOf("legacy", "m3-catalog"), sections.first().systems.map { it.system })
  }

  @Test
  fun `Other stays pinned last however it is claimed, and cards keep list order`() {
    fun system(id: String, repo: String?, group: ServeWeb.HomeGroup?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
        group = group,
      )

    val sections =
      ServeWeb.homeSections(
        listOf(
          // No provenance: falls into Other, and a priority on the claim it can't satisfy must
          // not promote it out of the unattributed bucket.
          system("mystery", null, designSystems.copy(priority = 500)),
          system(
            "wear-m3-catalog",
            "yschimke/compose-ai-tools",
            designSystems.copy(priority = 100),
          ),
          system("m3-catalog", "yschimke/compose-ai-tools", designSystems.copy(priority = 100)),
        )
      )

    assertEquals(listOf("Design Systems", "Other"), sections.map { it.heading })
    assertEquals(
      listOf("wear-m3-catalog", "m3-catalog"),
      sections.first().systems.map { it.system },
      "priority orders sections only — inside one, the configured catalog order still decides",
    )
  }

  @Test
  fun `small sections share a row, and a big one keeps its own`() {
    // Only adjacency is banded: the page's order is meaningful, so a small section is never pulled
    // past a big one to fill a row.
    val rows =
      ServeWeb.homeRows(listOf("big" to 5, "one" to 1, "two" to 2, "solo" to 1)) { it.second }

    assertEquals(
      listOf(listOf("big"), listOf("one", "two", "solo")),
      rows.map { row -> row.map { it.first } },
    )
  }

  @Test
  fun `a lone small section is not banded`() {
    // Nothing to share the row with, and a band would only shrink its cards.
    val rows = ServeWeb.homeRows(listOf("one" to 1, "big" to 4, "two" to 2)) { it.second }

    assertEquals(
      listOf(listOf("one"), listOf("big"), listOf("two")),
      rows.map { r -> r.map { it.first } },
    )
  }

  @Test
  fun `adjacent one and two catalog sections render side by side on the front page`() {
    fun system(id: String, repo: String) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = repo,
        heroPreviewId = null,
      )

    val html =
      ServeWeb.homeIndexPage(
        listOf(
          system("bitwarden-android", "bitwarden/android"),
          system("home-assistant-android", "home-assistant/android"),
          system("home-assistant-wear", "home-assistant/wear"),
        ),
        token = "t",
        isPublic = true,
      )

    assertTrue(html.contains("<div class=\"cp-section-band\">"), "small sections share a band")
    assertTrue(html.contains("<section class=\"cp-section-unit\" data-span=\"1\">"))
    assertTrue(html.contains("<section class=\"cp-section-unit\" data-span=\"2\">"))
    // Grid ids still follow section order, so nothing after a band is renumbered.
    assertTrue(html.contains("id=\"cp-grid\" data-cols=\"1\""))
    assertTrue(html.contains("id=\"cp-grid-1\" data-cols=\"2\""))
    assertTrue(html.contains("<h1 class=\"cp-head\">bitwarden repositories</h1>"))
    assertTrue(html.contains("<h1 class=\"cp-head\">home-assistant repositories</h1>"))
  }

  @Test
  fun `a section of three keeps a full-width row of its own`() {
    fun system(id: String) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = "joreilly/$id",
        heroPreviewId = null,
      )

    val html =
      ServeWeb.homeIndexPage(
        listOf(system("confetti"), system("bikeshare"), system("climatetrace")),
        token = "t",
        isPublic = true,
      )

    assertFalse(html.contains("cp-section-band"), "3+ catalogs are not collapsed onto a shared row")
    assertTrue(html.contains("<div class=\"cp-grid cp-syslist\" id=\"cp-grid\">"))
  }

  @Test
  fun `a section heading from config is escaped, never injected into the page`() {
    val system =
      ServeWeb.HomeSystem(
        system = "somecat",
        title = "Some Catalog",
        subtitle = null,
        previewCount = 1,
        trust = null,
        sourceRepo = "someorg/somecat",
        heroPreviewId = null,
        group =
          ServeWeb.HomeGroup(heading = "<script>x</script>", repos = setOf("someorg/somecat")),
      )

    val html = ServeWeb.homeIndexPage(listOf(system), token = "t", isPublic = true)

    assertFalse(html.contains("<script>x</script>"), "config text is data, not markup")
    assertTrue(html.contains("&lt;script&gt;x&lt;/script&gt;"))
  }
}
