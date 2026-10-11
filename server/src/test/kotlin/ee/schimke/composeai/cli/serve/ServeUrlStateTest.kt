package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The serve pages' address-bar state: picks (tab, theme, filter, override) are reflected into the
 * URL so the page is bookmarkable, shareable and Back-navigable. Structural assertions on emitted
 * script; behaviour is driven in a browser by `preview-harness/serve-lanes.spec.mjs`.
 */
class ServeUrlStateTest {

  private val sectioned =
    listOf(
      ServePreview("theme-light__ideal__default__light", "Theme light", section = "Themes"),
      ServePreview("theme-dark__ideal__default__dark", "Theme dark", section = "Themes"),
      ServePreview("button__ideal__default__light", "Button", section = "Components"),
      ServePreview("button__ideal__default__dark", "Button", section = "Components"),
    )

  private val CHROME_TAG = """<script src="${ServeWebAssets.href("serve-chrome.js")}"></script>"""

  private fun landing() =
    ServeWeb.landingPage("meshcore-mobile", sectioned, token = "t", basePath = "/meshcore-mobile")

  @Test
  fun `catalog landing loads the shared url-state helper`() {
    // `window.cpUrlState` ships in the page-shell bundle, emitted ahead of the surface's scripts;
    // the landing must load it before the filter script. Its behaviour is tested in
    // `cli/serve-web/test/chrome.test.ts`.
    val html = landing()
    assertTrue(html.contains(CHROME_TAG), "the landing page must load the shell bundle")
    assertTrue(
      html.indexOf(CHROME_TAG) < html.indexOf("cpUrlState"),
      "…and it must be emitted before the filter script that reads the global",
    )
  }

  @Test
  fun `section tab and theme pick each push a history entry`() {
    val html = landing()
    assertTrue(html.contains("pushUrl({ tab: current });"), "a tab click must push ?tab=")
    assertTrue(html.contains("pushUrl({ theme: theme });"), "a theme chip must push ?theme=")
    // The background toggle's `?bg=` push is tested against the real element in
    // `cli/serve-web/test/bgToggle.test.ts`.
  }

  @Test
  fun `typing in the filter replaces rather than pushes`() {
    val html = landing()
    assertTrue(
      html.contains("replaceUrl({ q: input.value.trim() });"),
      "the filter must replace the current entry — one entry per keystroke is unusable",
    )
    assertFalse(
      html.contains("pushUrl({ q:"),
      "the filter must never push, or Back would walk back through every keystroke",
    )
  }

  @Test
  fun `the url outranks the remembered tab and theme`() {
    val html = landing()
    assertTrue(html.contains("""var urlTab = urlParam("tab");"""), html)
    assertTrue(html.contains("""var urlTheme = urlParam("theme");"""), html)
    assertTrue(
      html.contains("if (urlTheme && chipOffered(urlTheme)) theme = urlTheme;"),
      "an explicit ?theme= is applied — including an app-declared theme, which the stored value " +
        "deliberately never replays",
    )
  }

  @Test
  fun `back and forward restore the whole selection without reloading`() {
    val html = landing()
    assertTrue(html.contains("urlState.onPop(function () {"), "the grid must handle popstate")
    // An entry that names no tab/theme falls back to what THIS load resolved to, not to whatever
    // localStorage was last written with — otherwise Back out of a theme lands on that theme.
    assertTrue(html.contains("""urlParam("tab") || initialTab"""), html)
    assertTrue(html.contains("""urlParam("theme") || initialTheme"""), html)
    // Scoped to the grid's script: the page shell has one deliberate reload (the one-time
    // `cp-interface-mode` localStorage→cookie migration), but the grid restores state by
    // re-pointing images and never reloads.
    val gridScript =
      html
        .substringAfter("""var cards = document.querySelectorAll(".cp-card");""")
        .substringBefore("</script>")
    // `substringAfter` returns the whole string when the delimiter is missing, so pin the slice to
    // the script carrying the popstate wiring.
    assertTrue(
      gridScript.contains("urlState.onPop(function () {"),
      "the extracted slice must be the grid script that restores state",
    )
    assertFalse(
      gridScript.contains("location.reload()"),
      "restoring state must re-point the grid in place, never reload the catalog",
    )
    // …and the shell's migration stays the ONLY reload on the page, so a second one appearing
    // anywhere still fails here rather than hiding behind the exemption above.
    assertEquals(
      1,
      html.split("location.reload()").size - 1,
      "the one-time cp-interface-mode migration is the only reload the landing page emits",
    )
  }

  // "Back restores the background this load opened with" is tested in
  // `cli/serve-web/test/bgToggle.test.ts` against the real element.

  @Test
  fun `the grid and viewer share Vue but load only their surface controls`() {
    val runtime = """<script src="${ServeWebAssets.href("vue-runtime.js")}"></script>"""
    val catalog = """<script src="${ServeWebAssets.href("catalog-components.js")}"></script>"""
    val viewer = """<script src="${ServeWebAssets.href("viewer-components.js")}"></script>"""
    assertTrue(
      landing().contains(runtime) && landing().contains(catalog),
      "the grid loads catalog controls",
    )
    assertFalse(landing().contains(viewer), "the grid must not pay for viewer controls")
    val preview = ServePreview("plain.Button", "button")
    val html = ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview))
    assertTrue(
      html.contains(runtime) && html.contains(viewer),
      "the viewer shares the runtime and loads viewer controls",
    )
    assertFalse(html.contains(catalog), "the viewer must not pay for catalog controls")
  }

  @Test
  fun `the pre-paint background script honours the url before the sticky choice`() {
    assertTrue(
      landing().contains("""var b=new URLSearchParams(location.search).get("bg")"""),
      "?bg= must be applied before first paint, like the sticky value it outranks",
    )
  }

  @Test
  fun `a catalog with no previews emits no url wiring`() {
    // The shell bundle is unconditional (it also carries the Page theme), so "no wiring" means no
    // filter script and no reader of the global.
    val empty = ServeWeb.landingPage("empty", emptyList(), token = "t")
    assertFalse(empty.contains("cpUrlState"), "nothing to select ⇒ no state to carry")
  }

  @Test
  fun `viewer loads the helper and lets the url outrank the remembered theme`() {
    val preview = ServePreview("plain.Button", "button")
    val html = ServeWeb.viewerPage(preview, token = "t", siblings = listOf(preview))
    assertTrue(html.contains(CHROME_TAG), html)
    assertTrue(html.contains("""var provider = params.get("themeProvider");"""), html)
    assertTrue(html.contains("""var uiMode = params.get("uiMode");"""), html)
    assertTrue(
      html.contains("if (!urlOption && !el.disabled && option && !option.disabled"),
      "the remembered theme applies only when the URL names none — and only where the control " +
        "can actually deliver it",
    )
  }

  @Test
  fun `viewer syncs its overrides into the page url and restores them on popstate`() {
    val script = viewerSource()
    assertTrue(
      script.contains("function ownsUrlParam(name: string)"),
      "the viewer must scope what it owns",
    )
    assertTrue(
      script.contains("window.cpUrlState.sync(values, ownsUrlParam, !push);"),
      "a control returning to its default has to clear its param, not pin a redundant value",
    )
    assertTrue(
      script.contains("function hydrateFromUrl(popped: boolean)"),
      "one restore path serves both the first load and Back/Forward",
    )
    assertTrue(script.contains("window.cpUrlState.onPop("), "the viewer must handle Back/Forward")
    // The interactive lanes render themselves and never reach refreshLinks, so without this the
    // chosen lane never reaches the URL and the pending push lands on some later edit instead.
    val interactiveStart = "// The interactive lanes drive their own render"
    val interactiveEnd = "// SVG format toggle"
    val start = script.indexOf(interactiveStart)
    val end = script.indexOf(interactiveEnd, start + interactiveStart.length)
    assertTrue(start >= 0, "interactive-lane start marker must remain in viewer.ts")
    assertTrue(end > start, "interactive-lane end marker must follow its start marker")
    val interactiveLaneTransition = script.substring(start, end)
    assertTrue(
      interactiveLaneTransition.contains("syncUrl();"),
      "entering Live / Wasm / RC must write ?mode= at the moment of the transition",
    )
    // …and a bookmarked lane opens in that lane: the param is read before the first sync, which
    // would otherwise clear it.
    assertTrue(
      script.contains(
        """var initialUrlMode = new URLSearchParams(location.search).get("mode") || "";"""
      ),
      "the viewer must capture a bookmarked ?mode= before the first URL sync",
    )
    assertTrue(script.contains("var wanted = initialUrlMode;"), "…and apply it on first load")
    // …after the first snapshot has landed; switching immediately would cancel the in-flight render
    // and leave an empty stage.
    assertTrue(
      script.contains("""img.addEventListener("load", enterBookmarkedMode);"""),
      "the bookmarked lane waits for the fallback frame",
    )
    assertTrue(
      script.contains("setTimeout(enterBookmarkedMode, 8000);"),
      "…but is bounded: a render that errors fires no event and must not strand the bookmark",
    )
    assertTrue(
      script.contains("if (!radio || radio.disabled) return;"),
      "a mode this session doesn't offer is ignored, not entered",
    )
    // token / session are the server's, and the viewer must never rewrite them.
    assertFalse(
      script.contains("values.token") || script.contains("values.session"),
      "the viewer owns only its own override params",
    )
  }

  // The `/compare` wall's push/replace rules are tested as behaviour in
  // `compareWallElement.test.ts`.
}
