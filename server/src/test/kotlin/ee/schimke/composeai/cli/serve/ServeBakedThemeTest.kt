package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ServeBakedTheme] and the routing it feeds — the question "what mode is this sticker drawn in?",
 * whose old id-only answer sent every light browse of an untagged catalog render to the daemon
 * (compose-ai-tools#4997).
 */
class ServeBakedThemeTest {

  private val untagged = "button-filled__ideal__l-square"
  private val darkTwin = "button-filled__ideal__l-square__dark"
  private val sizedLight = "bottomappbar-standard__ideal__four-actions__compact"
  private val sizedDark = "bottomappbar-standard__ideal__four-actions__dark__compact"

  @Test
  fun `an explicit token names the theme`() {
    assertEquals(UiMode.LIGHT, ServeBakedTheme.token("button-filled__ideal__default__light"))
    assertEquals(UiMode.DARK, ServeBakedTheme.token(darkTwin))
    assertNull(ServeBakedTheme.token(untagged))
  }

  /**
   * The scan takes the LAST theme segment, so a component whose *state* is called `dark` in an
   * otherwise-light variant doesn't read as a dark render — that would treat `uiMode=dark` as a
   * no-op and hand back the wrong pixels.
   */
  @Test
  fun `a trailing theme segment wins over an earlier one`() {
    assertEquals(UiMode.LIGHT, ServeBakedTheme.token("toggle__ideal__dark__light"))
  }

  @Test
  fun `an untagged render is the light half of a published pair`() {
    assertEquals(UiMode.LIGHT, ServeBakedTheme.resolve(untagged) { it == darkTwin })
  }

  /**
   * The theme segment sits BEFORE the size and props, so a sticker drawn at a breakpoint pairs
   * across the middle of its id, not its end. Appending was the first cut of this and it missed
   * every such render — 99 of `m3-catalog`'s 1973 pairs.
   */
  @Test
  fun `a sized render pairs across the middle of its id`() {
    assertEquals(sizedDark, ServeBakedTheme.twinIn(sizedLight, UiMode.DARK) { it == sizedDark })
    assertEquals(sizedLight, ServeBakedTheme.twinIn(sizedDark, UiMode.LIGHT) { it == sizedLight })
    assertEquals(UiMode.LIGHT, ServeBakedTheme.resolve(sizedLight) { it == sizedDark })
    assertNull(
      ServeBakedTheme.twinIn(sizedLight, UiMode.DARK) { it == sizedLight + "__dark" },
      "the twin is not the id with __dark appended",
    )
  }

  /** A props fan-out pairs the same way — the theme still precedes every `<key>-<value>`. */
  @Test
  fun `a props render pairs across the middle of its id`() {
    val props = "slider-continuous__ideal__default__medium__value-50"
    val propsDark = "slider-continuous__ideal__default__dark__medium__value-50"
    assertEquals(propsDark, ServeBakedTheme.twinIn(props, UiMode.DARK) { it == propsDark })
    assertEquals(props, ServeBakedTheme.twinIn(propsDark, UiMode.LIGHT) { it == props })
  }

  /**
   * The light half may be spelled either way — untagged (what the default mode produces) or with an
   * explicit `__light`, which 44 of `m3-catalog`'s records carry. Untagged is tried first.
   */
  @Test
  fun `the light twin is found under either spelling`() {
    val dark = "badge-number__ideal__default__dark__size-small"
    val tagged = "badge-number__ideal__default__light__size-small"
    val bare = "badge-number__ideal__default__size-small"

    assertEquals(tagged, ServeBakedTheme.twinIn(dark, UiMode.LIGHT) { it == tagged })
    assertEquals(bare, ServeBakedTheme.twinIn(dark, UiMode.LIGHT) { it == bare })
    assertEquals(bare, ServeBakedTheme.twinIn(dark, UiMode.LIGHT) { it == bare || it == tagged })
  }

  @Test
  fun `an unpublished twin is not invented`() {
    assertNull(ServeBakedTheme.twinIn(untagged, UiMode.DARK) { false })
    // A contrast specimen names its CONTRAST in the state and has no theme pair at all.
    val specimen = "color-role-grid__ideal__dark-high-contrast__medium"
    assertNull(ServeBakedTheme.token(specimen))
    assertNull(ServeBakedTheme.twinIn(specimen, UiMode.DARK) { false })
  }

  /**
   * With no dark twin the catalog has said nothing about which mode it baked, so neither does this
   * — and a `uiMode` request keeps routing to a real render rather than replaying pixels whose
   * theme nothing established.
   */
  @Test
  fun `an untagged render with no twin names no theme`() {
    assertNull(ServeBakedTheme.resolve(untagged) { false })
  }

  @Test
  fun `the record's own declaration outranks the id`() {
    assertEquals(UiMode.DARK, ServeBakedTheme.resolve(untagged, declaredTheme = "dark"))
    assertEquals(UiMode.LIGHT, ServeBakedTheme.resolve(darkTwin, declaredTheme = "Light"))
    assertNull(ServeBakedTheme.resolve(untagged, declaredTheme = "  "))
  }

  /**
   * The whole point: a `?uiMode=light` on the light half of a folded pair asks for the pixels
   * already on disk, so it replays the sticker instead of waking a daemon — while `uiMode=dark`
   * there is still a real request, and both stay reportable for a baked fallback (#3449).
   */
  @Test
  fun `routing replays an untagged pair member for its own theme`() {
    val alias = mapOf(untagged to "FilledButton_Light_VARIANT_l-square")
    val light = PreviewOverrides(uiMode = UiMode.LIGHT)
    val dark = PreviewOverrides(uiMode = UiMode.DARK)
    val baked = ServeBakedTheme.resolve(untagged) { it == darkTwin }

    assertFalse(CatalogLiveRouting.overridesAffectRender(untagged, light, baked))
    assertEquals(emptyList(), CatalogLiveRouting.droppedOverrideNames(untagged, light, baked))
    assertNull(
      CatalogLiveRouting.daemonIdForRender(untagged, light, alias, emptySet(), baked),
      "the light half of a pair replays its own sticker",
    )

    assertTrue(CatalogLiveRouting.overridesAffectRender(untagged, dark, baked))
    assertEquals(listOf("uiMode"), CatalogLiveRouting.droppedOverrideNames(untagged, dark, baked))
    assertEquals(
      "FilledButton_Light_VARIANT_l-square",
      CatalogLiveRouting.daemonIdForRender(untagged, dark, alias, emptySet(), baked),
    )
  }

  /** Nothing named the theme ⇒ the old behaviour, verbatim: both modes reach the daemon. */
  @Test
  fun `routing still renders a uiMode no theme was named for`() {
    val alias = mapOf(untagged to "FilledButton_VARIANT_l-square")
    for (mode in listOf(UiMode.LIGHT, UiMode.DARK)) {
      val o = PreviewOverrides(uiMode = mode)
      assertTrue(CatalogLiveRouting.overridesAffectRender(untagged, o, bakedTheme = null))
      assertEquals(
        "FilledButton_VARIANT_l-square",
        CatalogLiveRouting.daemonIdForRender(untagged, o, alias, emptySet(), bakedTheme = null),
      )
    }
  }

  /**
   * The manifest is what knows about the pair, so the baked host is what answers — the composites
   * in front of it delegate, and the id-only default is only for a host with no manifest.
   */
  @Test
  fun `the bundle host answers from what it publishes`() {
    val dir =
      java.nio.file.Files.createTempDirectory("baked-theme").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    val previews = File(dir, "previews").apply { mkdirs() }
    for (id in listOf(untagged, darkTwin, sizedLight, sizedDark, "badge-number__ideal__solo")) {
      File(previews, "$id.png").writeBytes(byteArrayOf(4, 2))
    }

    val host = ServeBundleHost(dir, label = "catalog")

    assertEquals(UiMode.LIGHT, host.bakedTheme(untagged), "published beside its dark twin")
    assertEquals(UiMode.DARK, host.bakedTheme(darkTwin))
    assertEquals(
      UiMode.LIGHT,
      host.bakedTheme(sizedLight),
      "a sized render pairs across the middle of its id",
    )
    assertNull(host.bakedTheme("badge-number__ideal__solo"), "no twin, no claim")
    assertNull(host.bakedTheme("not-published__ideal__default"))
  }
}
