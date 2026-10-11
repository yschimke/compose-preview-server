package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Page theme setting: the site chrome follows the selected preview theme (`?theme=dark`, a
 * chip, the viewer's Theme select) unless turned off in Settings. Structural assertions on what the
 * server emits; the behaviour is captured in a browser by `serve-landing-catalog-palette`'s
 * `theme-sync`, `theme-sync-menu` and `theme-sync-off` states in `preview-harness`.
 */
class ServePageThemeTest {

  private val previews =
    listOf(
      ServePreview("button__ideal__default__light", "Button (light)", theme = "light"),
      ServePreview("button__ideal__default__dark", "Button (dark)", theme = "dark"),
    )

  private fun landing() =
    ServeWeb.landingPage("wear-m3", previews, token = "t", basePath = "/wear-m3")

  private fun viewer() = ServeWeb.viewerPage(previews.first(), token = "t", basePath = "/wear-m3")

  private val status =
    ServeWeb.StatusView(
      version = "0",
      public = true,
      nowMillis = 0L,
      overallOk = true,
      summary = emptyList(),
      config = emptyList(),
      catalogs = emptyList(),
      servers = emptyList(),
      failures = emptyList(),
    )

  @Test
  fun `Wear themes infer explicit modes before falling back to dark`() {
    fun wearTheme(name: String, fqn: String) =
      PreviewInfo(
        id = name,
        functionName = name,
        className = "Catalog",
        params = PreviewParams(name = name, kind = "WEAR_THEME_CATALOG", wrapperClassName = fqn),
      )

    val themes =
      declaredThemesFromPreviews(
        listOf(
          wearTheme("Light", "com.example.LightWearTheme"),
          wearTheme("Coral", "com.example.CoralWearTheme"),
        )
      )

    assertTrue(themes.first { it.name == "Light" }.mode == "light")
    assertTrue(themes.first { it.name == "Coral" }.mode == "dark")
  }

  /**
   * A catalog declaring a dark stage offers no Light chip whatever its id: deciding from the id
   * alone offered `remote-m3` (dark-only documents, no `wear` token) a Light chip nothing could
   * honour.
   */
  @Test
  fun `a catalog declaring a dark surface offers no day-night choice`() {
    val darkOnly =
      ServeWeb.viewerPage(
        ServePreview("appcard__ideal__default__compact", "AppCard"),
        token = "t",
        basePath = "/remote-m3",
        sessionId = "remote-m3",
        declaredSurface = "dark",
      )

    assertTrue(darkOnly.contains("data-always-dark=\"1\""), darkOnly)
    assertFalse(
      darkOnly.contains("data-theme-choice=\"light\""),
      "a declared-dark catalog must not offer a Light chip: $darkOnly",
    )

    // …and a catalog that declares nothing, on an id that reads like neither a watch nor a dark
    // surface, keeps the pair it always had.
    val unstated =
      ServeWeb.viewerPage(
        ServePreview("button__ideal__default__light", "Button", theme = "light"),
        token = "t",
        basePath = "/compose-m3",
        sessionId = "compose-m3",
      )

    assertFalse(unstated.contains("data-always-dark=\"1\""), unstated)
    assertTrue(unstated.contains("data-theme-choice=\"light\""), unstated)
  }

  /**
   * A dark stage isn't a dark-only catalog: `display.surface` is a stage colour, and a catalog may
   * publish light/dark pairs on a dark ground. Only a declared-dark catalog with no light render is
   * dark-only.
   */
  @Test
  fun `a declared dark stage keeps the pair when the catalog bakes a light render`() {
    val html =
      ServeWeb.viewerPage(
        previews[1],
        token = "t",
        basePath = "/compose-m3",
        sessionId = "compose-m3",
        siblings = previews,
        declaredSurface = "dark",
      )

    assertFalse(html.contains("data-always-dark=\"1\""), html)
    assertTrue(html.contains("data-theme-choice=\"light\""), html)
  }

  /**
   * A declaration can add an always-dark catalog but can't remove a Wear id's: `uiMode` is dropped
   * for Wear/watch ids on every lane, so a Light choice would change nothing.
   */
  @Test
  fun `a Wear id keeps its veto over a declared light surface`() {
    val wearLight =
      ServeWeb.viewerPage(
        ServePreview("button__ideal__default__light", "Button", theme = "light"),
        token = "t",
        basePath = "/confetti-wear",
        sessionId = "confetti-wear",
        declaredSurface = "light",
      )

    assertTrue(wearLight.contains("data-always-dark=\"1\""), wearLight)
    assertFalse(
      wearLight.contains("data-theme-choice=\"light\""),
      "the render lane drops uiMode for a Wear id, so the control must not offer Light: $wearLight",
    )
    assertTrue(
      ServeWeb.SystemDisplay.normalizeOverrideParams(
          "confetti-wear",
          mapOf("uiMode" to "light"),
        )
        .isEmpty(),
      "the premise of the assertion above",
    )
  }

  @Test
  fun `the resolved scheme is pinned before first paint, not after the page loads`() {
    // Inline in the head, ahead of the body; deferring to the shell bundle would flash the wrong
    // mode.
    val html = landing()
    val script = html.substringAfter("<script>try{var p=new URLSearchParams").substringBefore("\n")
    assertTrue(script.isNotBlank(), "no pre-paint page-theme script emitted")
    assertTrue(
      html.indexOf("cp-scheme-") < html.indexOf("<body>"),
      "the scheme must be pinned in the head, before the body paints",
    )
    // The URL outranks the remembered choice, exactly as the theme itself does.
    assertTrue(
      script.contains(
        "p.get(\"theme\")||(p.get(\"themeProvider\")?\"theme:\"+p.get(\"themeProvider\"):\"\")||p.get(\"uiMode\")"
      ),
      script,
    )
    // Per tab, and above the baked id: the viewer applies this tab's choice to a `__light` preview
    // too, so the chrome follows it. The baked theme is `r`'s fallback.
    assertTrue(script.contains("r(sessionStorage.getItem(\"cp-theme:wear-m3\"))"), script)
    assertFalse(
      script.contains("p.get(\"uiMode\")||((decodeURIComponent"),
      "the baked theme is not a peer of the memory in the chain, it is what `r` falls back to: " +
        script,
    )
    assertTrue(
      script.contains("match(/(?:^|__)(light|dark)(?:__|$)/)"),
      "a clean baked preview URL must recover its light/dark variant before first paint: $script",
    )
    // …and only an explicit light/dark says anything about the page's mode.
    assertTrue(script.contains("if(t===\"light\"||t===\"dark\")"), script)
  }

  /**
   * A remembered value the mode table can't resolve (e.g. a provider no longer declared) must not
   * shadow the baked theme; each candidate is resolved in turn.
   */
  @Test
  fun `an unresolvable remembered theme falls through to the baked one`() {
    val script = landing().substringAfter("<script>try{var p=new URLSearchParams")
    assertTrue(
      script.contains("r(sessionStorage.getItem(\"cp-theme:wear-m3\"))"),
      "the remembered value is decided by `r`, not read straight into the chain: $script",
    )
    assertTrue(
      script.contains(
        "r=function(t){t=t;return t===\"light\"||t===\"dark\"?t:((decodeURIComponent"
      ),
      "…and one that names no mode gives way to the theme the id bakes: $script",
    )
  }

  /** A viewer that can't re-render ignores the memory: the stage keeps its baked image. */
  @Test
  fun `a viewer that cannot apply a theme resolves the chrome from its baked one`() {
    val html = viewer()
    val script = html.substringAfter("<script>try{var p=new URLSearchParams").substringBefore("\n")
    assertTrue(
      html.contains("id=\"cp-theme\"") && html.contains(" disabled>"),
      "this fixture is meant to have no live tier behind its Theme control: $html",
    )
    assertFalse(
      script.contains("sessionStorage.getItem"),
      "a page that cannot apply a remembered theme must not resolve the chrome from one: $script",
    )
    assertTrue(
      script.contains("match(/(?:^|__)(light|dark)(?:__|$)/)"),
      "it still opens on the theme its own id bakes: $script",
    )
  }

  /**
   * A remembered choice the destination doesn't offer gives way to its baked theme (one key serves
   * a catalog's viewer, landing and wall).
   */
  @Test
  fun `the pre-paint script checks a remembered theme against what the viewer offers`() {
    val dark = ServePreview("watch__ideal__default__dark", "Watch", theme = "dark")
    val html =
      ServeWeb.viewerPage(
        dark,
        token = "t",
        basePath = "/wear-m3",
        catalogName = "wear-m3",
        canApplyOverrides = true,
        declaredThemes = listOf(ServeTheme("Coral", "com.example.CoralWearTheme")),
        siblings = listOf(dark),
      )
    val script = html.substringAfter("<script>try{var p=new URLSearchParams").substringBefore("\n")
    assertTrue(
      script.contains("o={\"dark\":1,\"theme:com.example.CoralWearTheme\":1}"),
      "a dark-first catalog offers Dark and its declared themes — never Light: $script",
    )
    assertTrue(
      script.contains("r=function(t){return t&&o[t]?"),
      "and the remembered value is checked against it, not merely resolved: $script",
    )
  }

  @Test
  fun `the setting can turn the whole thing off`() {
    val script = landing().substringAfter("<script>try{var p=new URLSearchParams")
    assertTrue(
      script.contains("localStorage.getItem(\"cp-page-theme\")===\"system\"?\"\""),
      "the stored setting must be able to resolve to no pin at all",
    )
  }

  @Test
  fun `every page carries the Settings menu and the script that wires it`() {
    for ((name, html) in
      mapOf(
        "landing" to landing(),
        "viewer" to viewer(),
        "front door" to ServeWeb.homeIndexPage(emptyList(), token = "t", version = "0"),
        "status" to ServeWeb.statusPage(status, token = "t"),
      )) {
      assertTrue(html.contains("class=\"cp-settings\""), "$name has no Settings menu")
      val hasPreview = name == "landing" || name == "viewer"
      assertEquals(
        hasPreview,
        html.contains("data-cp-page-theme value=\"match\"") ||
          html.contains("value=\"match\" data-cp-page-theme"),
        "$name Page theme setting visibility",
      )
      // The setting ships in the page-shell bundle now (`cli/serve-web/src/chrome/pageTheme.ts`),
      // which every page emits; its behaviour is covered in `cli/serve-web/test/chrome.test.ts`.
      assertTrue(
        html.contains("""<script src="${ServeWebAssets.href("serve-chrome.js")}"></script>"""),
        "$name never loads the shell bundle",
      )
      assertTrue(
        html.contains("data-cp-keyboard-navigation"),
        "$name offers no power-user navigation setting",
      )
      assertTrue(
        html.contains(
          """<script src="${ServeWebAssets.href("keyboard-navigation.js")}"></script>"""
        ),
        "$name never loads keyboard-navigation.js",
      )
    }
  }

  @Test
  fun `only a page with a theme control publishes a theme key to resolve from`() {
    assertTrue(landing().contains("data-cp-theme-key=\"cp-theme:wear-m3\""))
    assertTrue(viewer().contains("data-cp-theme-key=\"cp-theme:wear-m3\""))
    // The front door has no theme control, so there is nothing to follow and no key to read.
    assertFalse(
      ServeWeb.homeIndexPage(emptyList(), token = "t", version = "0").contains("data-cp-theme-key")
    )
  }

  @Test
  fun `picking a theme turns the page over with the previews`() {
    assertTrue(
      landing().contains("if (window.cpPageTheme) window.cpPageTheme.follow(theme);"),
      "the grid's theme apply must hand the choice to page-theme.js",
    )
    assertTrue(
      viewer().contains("if (window.cpPageTheme) window.cpPageTheme.follow(el.value);"),
      "the viewer's Theme select must hand the choice to page-theme.js",
    )
    assertTrue(
      landing().contains("c.setAttribute(\"aria-label\", lbl);"),
      "a swapped card's accessible name must follow its visible theme variant",
    )
    // The comparison page's Theme control is tested as behaviour in `compareWallElement.test.ts`.
  }

  @Test
  fun `Back and Forward repaint the chrome with the entry they restore`() {
    // Every pop path restores its theme by assigning the value (no `change`), so it must hand over
    // the active choice itself, not the displayed one (which would pin a baked default nobody
    // picked).
    val viewerJs = viewerSource()
    assertTrue(
      viewerJs
        .substringAfter("function hydrateFromUrl")
        .contains("window.cpPageTheme.follow(activeThemeChoice())"),
      "the viewer's Back/Forward hydrate must repaint the chrome, from the active choice",
    )
    // The comparison page's pop path is driven by the element test too.
  }

  @Test
  fun `theme chips with a resolved mode are painted in their own theme`() {
    // Each chip pins its own `color-scheme`, re-resolving every `light-dark()` pair (including a
    // catalog's palette) in that chip's mode.
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertTrue(
      sheet.contains("""[data-compare-theme="light"]) { color-scheme: light; }"""),
      "the Light chip must resolve the token layer in light",
    )
    assertTrue(
      sheet.contains("""[data-compare-theme="dark"]) { color-scheme: dark; }"""),
      "the Dark chip must resolve it in dark",
    )
    // Selection is an inset ring, not a fill swap (the fill is the swatch); inset because the
    // viewer's theme bar is a padded scroller that clips outward rings.
    val pressed =
      sheet
        .substringAfter("""[data-compare-theme="dark"])[aria-pressed="true"] {""")
        .substringBefore("}")
    assertTrue(pressed.contains("box-shadow: inset 0 0 0 2px"), pressed)
    assertTrue(pressed.contains("--md-sys-color-surface-container-low"), pressed)
  }

  @Test
  fun `a named declared theme paints the page and card stage in its mode`() {
    val darkTheme = ServeTheme("Dark Medium Contrast", "com.example.DarkMediumContrastTheme")
    val html =
      ServeWeb.landingPage(
        "compose-m3",
        previews,
        token = "t",
        declaredThemes = listOf(darkTheme),
        canRenderThemeFor = { true },
      )

    assertTrue(
      html.contains(
        "data-theme-choice=\"theme:${darkTheme.providerFqn}\" data-theme-mode=\"dark\""
      ),
      "the declared-theme chip must carry its resolved mode",
    )
    assertTrue(
      html.contains("\"theme:${darkTheme.providerFqn}\":\"dark\""),
      "the pre-paint script must resolve a shared declared-theme URL before first paint",
    )
    assertTrue(
      html.contains("if (selectedThemeMode) c.setAttribute(\"data-bg-theme\", selectedThemeMode)"),
      "a themed render's card stage must follow that theme's mode",
    )
  }

  @Test
  fun `a disabled chip is dimmed inside its own scheme, not against the page`() {
    // A disabled chip keeps its own surface under the dimmed label and its scheme pin, so it reads
    // as unavailable rather than vanishing against the page.
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    val disabled =
      sheet.substringAfter("""[data-compare-theme="dark"]):disabled {""").substringBefore("}")
    assertTrue(disabled.contains("background: var(--md-sys-color-surface-container-low)"), disabled)
    assertTrue(disabled.contains("--md-sys-color-on-surface) 38%"), disabled)
    assertFalse(
      sheet.contains("""[data-theme-choice="dark"]:not(:disabled) { color-scheme"""),
      "the scheme pin must not be dropped on a state change",
    )
  }

  @Test
  fun `the stylesheet resolves both modes from color-scheme alone`() {
    // The setting works via `color-scheme` on <html>, which only re-resolves `light-dark()` pairs;
    // a `prefers-color-scheme` block would be immune to the pin.
    val sheet = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()
    assertFalse(
      sheet.contains("@media (prefers-color-scheme"),
      "serve.css must express modes as light-dark() pairs, not a media query",
    )
    assertTrue(sheet.contains(":root.cp-scheme-light { color-scheme: light; }"))
    assertTrue(sheet.contains(":root.cp-scheme-dark { color-scheme: dark; }"))
    val playground = ServeWebAssets.load("playground.css")!!.bytes.decodeToString()
    assertFalse(
      playground.contains("@media (prefers-color-scheme"),
      "the playground's editor must follow the pinned scheme too",
    )
  }
}
