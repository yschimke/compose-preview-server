package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteNamedValue
import ee.schimke.composeai.data.remotecompose.RemoteComposeKnobDeclaration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Pins the **component-state** wiring in [ServeWeb]: baked non-default states
 * (`unchecked`/`pressed`/…) are folded out of the landing grid so a component shows ONE card, and
 * the viewer grows a `.cp-axes-tree` subtree — the catalog tree filtered to this component — of
 * plain links to its other renders *in the same theme*. Stateless previews (a plain uploaded
 * bundle) are untouched.
 */
class ServeWebTest {

  private fun jsonProps(vararg entries: Pair<String, String>): JsonObject = buildJsonObject {
    for ((key, value) in entries) put(key, JsonPrimitive(value))
  }

  /**
   * A themed, state-bearing preview (id carries the theme token so the grid's theme swap still
   * pairs it).
   */
  private fun preview(slug: String, state: String, theme: String) =
    ServePreview(
      id = "${slug}__ideal__${state}__${theme}",
      label = slug,
      state = state,
      theme = theme,
    )

  // A checkbox with a default + an unchecked render, each in light and dark.
  private val checkbox =
    listOf(
      preview("checkbox", "default", "light"),
      preview("checkbox", "default", "dark"),
      preview("checkbox", "unchecked", "light"),
      preview("checkbox", "unchecked", "dark"),
    )

  @Test
  fun `catalog component ids become readable labels without changing preview routes`() {
    val previews =
      listOf(
          "appcard" to "AppCard",
          "buttongroup" to "ButtonGroup",
          "edgebutton" to "EdgeButton",
          "transforminglazycolumn" to "TransformingLazyColumn",
          "podcastdetails" to "PodcastDetails",
          "listitem" to "ListItem",
          "urlbutton" to "URLButton",
        )
        .map { (slug, componentId) ->
          ServePreview(
            id = "${slug}__ideal__default__light",
            label = "${slug}__ideal__default__light",
            componentId = componentId,
          )
        }

    val html = ServeWeb.landingPage("catalog", previews, token = "t", basePath = "/catalog")

    for (label in
      listOf(
        "App Card",
        "Button Group",
        "Edge Button",
        "Transforming Lazy Column",
        "Podcast Details",
        "List Item",
        "URL Button",
      )) {
      assertTrue(html.contains(">$label</"), "$label is shown with readable word boundaries")
    }
    assertTrue(
      html.contains("/catalog/p/appcard__ideal__default__light"),
      "the human label does not alter the stable preview route",
    )
  }

  @Test
  fun `the grid folds a non-default state into the default card`() {
    val html = ServeWeb.landingPage("compose-m3", checkbox, token = "t", basePath = "/compose-m3")

    // Exactly one card — the default (a light/dark swap card), no separate 'unchecked' card.
    assertEquals(1, Regex("class=\"cp-card\"").findAll(html).count(), "one card per component")
    assertTrue(html.contains("checkbox__ideal__default__light"), "default render is the card")
    assertFalse(html.contains("unchecked"), "the non-default state is folded out of the grid")
  }

  @Test
  fun `the viewer renders a same-theme state switcher with the current state active`() {
    val current = checkbox[0] // default, light
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/compose-m3", siblings = checkbox)

    assertTrue(html.contains("class=\"cp-tree cp-axes-tree\""), "component subtree rendered")
    // Isolate the subtree — other page chrome (the component nav drawer) also links siblings, so
    // the theme-scoping assertion must look only inside the `.cp-axes-tree` nav.
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    // Links to the SAME-THEME (light) unchecked sibling…
    assertTrue(
      nav.contains("/compose-m3/p/checkbox__ideal__unchecked__light"),
      "switcher links the same-theme sibling state",
    )
    // …and never to the dark render (that would jump the visitor's theme).
    assertFalse(
      nav.contains("/compose-m3/p/checkbox__ideal__unchecked__dark"),
      "switcher stays within the current theme",
    )
    // The current (default) state is marked active with a human label.
    assertTrue(
      nav.contains("aria-current=\"page\"><span class=\"cp-tree-label\">checkbox"),
      "the default render IS the component row, and it is marked active",
    )
  }

  @Test
  fun `a single-state component renders no switcher`() {
    val button = listOf(preview("button", "default", "light"), preview("button", "default", "dark"))
    val html =
      ServeWeb.viewerPage(button[0], token = "t", basePath = "/compose-m3", siblings = button)
    // The tree CSS ships on every page; assert the absence of the nav *element*.
    assertFalse(
      html.contains("class=\"cp-tree cp-axes-tree\""),
      "no subtree for a one-render component",
    )
  }

  // A component whose non-default states are published UNTAGGED on the primary (light) lane while
  // their dark twins carry the theme — the shape an `@OverrideVariant` matrix exports, because the
  // synthetic capture inherits the base `@Preview`'s `uiMode` param (dark) but not its `name`
  // ("Light"). m3-catalog publishes 446 renders like this; the whole size x shape matrix used to be
  // unreachable from the light lane, which is the one the grid links to.
  private val mixedTagging =
    listOf(
      ServePreview(
        "button-filled__ideal__default__light",
        "Filled",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "button-filled__ideal__default__dark",
        "Filled",
        state = "default",
        theme = "dark",
      ),
      ServePreview("button-filled__ideal__xs", "Filled", state = "xs"),
      ServePreview("button-filled__ideal__xs__dark", "Filled", state = "xs", theme = "dark"),
      ServePreview("button-filled__ideal__xl-square", "Filled", state = "xl-square"),
      ServePreview(
        "button-filled__ideal__xl-square__dark",
        "Filled",
        state = "xl-square",
        theme = "dark",
      ),
    )

  // An exhaustively drawn kit set: two cells a reader browses by, and one of the eighty-eight that
  // exist to be compared against a kit node rather than navigated to.
  private val exhaustive =
    listOf(
      ServePreview("progress__ideal__default", "Progress", state = "default"),
      ServePreview("progress__ideal__disabled", "Progress", state = "disabled"),
      ServePreview(
        "progress__ideal__segments-13-small-stroke",
        "Progress",
        state = "segments-13-small-stroke",
        secondary = true,
      ),
    )

  @Test
  fun `a second-tier cell stays out of the component subtree`() {
    val html =
      ServeWeb.viewerPage(
        exhaustive[0],
        token = "t",
        basePath = "/wear-m3-catalog",
        siblings = exhaustive,
      )
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")

    assertTrue(nav.contains("/p/progress__ideal__disabled"), "a primary cell is still listed")
    assertFalse(
      nav.contains("segments-13-small-stroke"),
      "the exhaustive cell is not a row a reader has to scroll past",
    )
  }

  @Test
  fun `a second-tier cell reached by its own link still says where it is`() {
    // The whole point of the tier is that the render stays addressable — a kit page links straight
    // to it — so the page it lands on has to be a page, tree and all, not a dead end.
    val html =
      ServeWeb.viewerPage(
        exhaustive[2],
        token = "t",
        basePath = "/wear-m3-catalog",
        siblings = exhaustive,
      )
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")

    assertTrue(
      nav.contains("progress__ideal__segments-13-small-stroke"),
      "the render on screen is a row of its own tree",
    )
    assertTrue(
      nav.contains("/p/progress__ideal__disabled"),
      "and the primary cells are still the way back",
    )
  }

  @Test
  fun `the state switcher reaches untagged siblings from the primary-lane default`() {
    val current = mixedTagging[0] // default, light
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/m3-catalog", siblings = mixedTagging)

    assertTrue(
      html.contains("class=\"cp-tree cp-axes-tree\""),
      "subtree rendered on the light lane",
    )
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      nav.contains("/m3-catalog/p/button-filled__ideal__xs") &&
        nav.contains("/m3-catalog/p/button-filled__ideal__xl-square"),
      "an untagged sibling is reachable from the light default",
    )
    assertFalse(nav.contains("__xs__dark"), "the dark twin stays out of the light lane")
  }

  @Test
  fun `an untagged render links back to the primary-lane default`() {
    val current = mixedTagging[2] // xs, untagged
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/m3-catalog", siblings = mixedTagging)

    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      nav.contains("/m3-catalog/p/button-filled__ideal__default__light"),
      "the light default is reachable back from an untagged state",
    )
    assertTrue(nav.contains("aria-current=\"page\">Xs</a>"), "the current state is marked active")
    assertFalse(nav.contains("__dark"), "an untagged render stays on the primary lane")
  }

  @Test
  fun `the dark lane of a mixed-tagging component keeps only its dark siblings`() {
    val current = mixedTagging[1] // default, dark
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/m3-catalog", siblings = mixedTagging)

    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(nav.contains("/m3-catalog/p/button-filled__ideal__xs__dark"), "dark siblings link")
    assertFalse(
      nav.contains("/m3-catalog/p/button-filled__ideal__xs\"") ||
        nav.contains("/m3-catalog/p/button-filled__ideal__xs?"),
      "the untagged (light) render does not leak into the dark lane",
    )
  }

  @Test
  fun `a state named dark is not mistaken for a theme`() {
    // An unthemed component may legitimately call a STATE `dark` (or `light`). Reading the lane off
    // the raw id would find that token and file the state in the dark lane while its own default
    // sits in the light one — the grid folds the state out, so it would then be unreachable.
    val toggle =
      listOf(
        ServePreview("toggle__ideal__default", "Toggle", state = "default"),
        ServePreview("toggle__ideal__dark", "Toggle", state = "dark"),
      )
    val html = ServeWeb.viewerPage(toggle[0], token = "t", basePath = "/catalog", siblings = toggle)

    assertTrue(html.contains("class=\"cp-tree cp-axes-tree\""), "the component subtree is rendered")
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      nav.contains("/catalog/p/toggle__ideal__dark"),
      "a state named `dark` stays in its component's lane rather than being read as a theme",
    )
  }

  @Test
  fun `the variant switcher pairs a themed default with an untagged props sibling`() {
    // The props-family key needs the same theme normalisation as the state key: the family check
    // runs before the lane comparison, so without it the lanes agreeing never gets to matter.
    val mixed =
      listOf(
        ServePreview("button__ideal__default__light", "Button", state = "default", theme = "light"),
        ServePreview(
          "button__ideal__default__content-icon-label",
          "Button · Icon+label",
          state = "default",
          props = jsonProps("content" to "icon+label"),
        ),
      )
    val html = ServeWeb.viewerPage(mixed[0], token = "t", basePath = "/catalog", siblings = mixed)

    assertTrue(html.contains("class=\"cp-tree cp-axes-tree\""), "component subtree rendered")
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      nav.contains("/catalog/p/button__ideal__default__content-icon-label"),
      "an untagged props sibling is reachable from the themed default",
    )
  }

  @Test
  fun `on a dark-first system an untagged render lanes with dark`() {
    // Wear catalogs draw for a black watch face, so their untagged renders are the DARK lane — the
    // same rule the stage backing already uses (`bgTheme`), applied to switcher grouping.
    val wear =
      listOf(
        ServePreview("edgebutton__ideal__default__dark", "Edge", state = "default", theme = "dark"),
        ServePreview("edgebutton__ideal__pressed", "Edge", state = "pressed"),
      )
    val html = ServeWeb.viewerPage(wear[0], token = "t", basePath = "/wear-m3", siblings = wear)

    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      nav.contains("/wear-m3/p/edgebutton__ideal__pressed"),
      "an untagged Wear render joins the dark lane rather than stranding alone",
    )
  }

  @Test
  fun `the state switcher stays within the current variant axis, not just the slug`() {
    // Button/Filled varies on BOTH a state axis (default/pressed) and a content-props axis
    // (label-only default vs a content=icon+label render, which keeps state=default). All share the
    // `button-filled` slug, so keying on slug alone would cross-link the two axes.
    val labelDefault =
      ServePreview(
        "button-filled__ideal__default__light",
        "Filled",
        state = "default",
        theme = "light",
      )
    val labelPressed =
      ServePreview(
        "button-filled__ideal__pressed__light",
        "Filled",
        state = "pressed",
        theme = "light",
      )
    val iconLabel =
      ServePreview(
        "button-filled__ideal__default__light__content-icon-label",
        "Filled · icon+label",
        state = "default",
        theme = "light",
      )
    val all = listOf(labelDefault, labelPressed, iconLabel)

    // The label-only default page toggles between its OWN states (default/pressed) and never links
    // the icon+label render (a different variant axis).
    val labelHtml =
      ServeWeb.viewerPage(labelDefault, token = "t", basePath = "/compose-m3", siblings = all)
    val labelNav =
      labelHtml.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      labelNav.contains("aria-current=\"page\"><span class=\"cp-tree-label\">Filled"),
      "current state active",
    )
    assertTrue(
      labelNav.contains("/p/button-filled__ideal__pressed__light"),
      "links its own pressed state",
    )
    assertFalse(labelNav.contains("content-icon-label"), "does not cross into the content axis")

    // The icon+label render has no sibling STATE of its own. The old chip switcher, keyed to the
    // current render's axes, therefore showed it nothing at all — a dead end you could navigate
    // into and not back out of. A subtree roots at the COMPONENT, so arriving on a props variant
    // shows the same tree every other render of that component shows, with this one marked: the
    // component's renders are a property of the component, not of where you happened to enter.
    val iconHtml =
      ServeWeb.viewerPage(iconLabel, token = "t", basePath = "/compose-m3", siblings = all)
    val iconNav =
      iconHtml.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    assertTrue(
      iconNav.contains(
        "/p/button-filled__ideal__default__light__content-icon-label?token=t\" aria-current=\"page\""
      ),
      "the render on screen is a row of its own component's tree, and marked: $iconNav",
    )
    assertTrue(
      iconNav.contains("/p/button-filled__ideal__default__light?token=t\"") &&
        iconNav.contains("/p/button-filled__ideal__pressed__light"),
      "…and can reach the component's default and its states: $iconNav",
    )
  }

  @Test
  fun `a plain stateless catalog renders grid and viewer unchanged`() {
    val plain =
      listOf(
        ServePreview(id = "com.example.Red", label = "Red"),
        ServePreview(id = "com.example.Blue", label = "Blue"),
      )

    val grid = ServeWeb.landingPage("bundle", plain, token = "t", basePath = "/bundle")
    assertEquals(2, Regex("class=\"cp-card\"").findAll(grid).count(), "both stateless cards shown")

    val viewer = ServeWeb.viewerPage(plain[0], token = "t", basePath = "/bundle", siblings = plain)
    assertFalse(
      viewer.contains("class=\"cp-tree cp-axes-tree\""),
      "no subtree without state metadata",
    )
  }

  @Test
  fun `spatial viewer loads only for a preview with a scene`() {
    val spatial = ServePreview(id = "com.example.Xr", label = "XR preview", spatial = true)
    val html =
      ServeWeb.viewerPage(
        spatial,
        token = "t",
        basePath = "/bundle",
        spatialSceneUrl = "/bundle/spatial/com.example.Xr/scene.json?token=t",
      )

    assertTrue(html.contains("<cp-spatial-view"), html)
    assertTrue(
      html.contains("scene-url=\"/bundle/spatial/com.example.Xr/scene.json?token=t\""),
      html,
    )
    assertTrue(html.contains("spatial-view.js"), html)
    assertFalse(html.contains("/viewer.js"), html)

    val flat = ServeWeb.viewerPage(ServePreview("flat", "Flat"), token = "t")
    assertFalse(flat.contains("<cp-spatial-view"), flat)
    assertFalse(flat.contains("spatial-view.js"), flat)
  }

  // Button/Filled with its default render plus two props-axis variants (an RTL render and an ar-XB
  // pseudo-locale), each in light + dark — the shape the compose-m3 catalog folds via `variants`.
  private val buttonVariants =
    listOf(
      ServePreview(
        "button-filled__ideal__default__light",
        "Filled",
        state = "default",
        theme = "light",
      ),
      ServePreview(
        "button-filled__ideal__default__dark",
        "Filled",
        state = "default",
        theme = "dark",
      ),
      ServePreview(
        "button-filled__ideal__default__light__direction-rtl",
        "Filled · RTL",
        state = "default",
        theme = "light",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__direction-rtl",
        "Filled · RTL",
        state = "default",
        theme = "dark",
        props = jsonProps("direction" to "rtl"),
      ),
      ServePreview(
        "button-filled__ideal__default__light__locale-ar-xb",
        "Filled · ar-XB",
        state = "default",
        theme = "light",
        props = jsonProps("locale" to "ar-XB"),
      ),
      ServePreview(
        "button-filled__ideal__default__dark__locale-ar-xb",
        "Filled · ar-XB",
        state = "default",
        theme = "dark",
        props = jsonProps("locale" to "ar-XB"),
      ),
    )

  @Test
  fun `the grid folds props variants into the default card`() {
    val html =
      ServeWeb.landingPage("compose-m3", buttonVariants, token = "t", basePath = "/compose-m3")

    // Exactly one card — the default (a light/dark swap card), no separate RTL / locale card.
    assertEquals(1, Regex("class=\"cp-card\"").findAll(html).count(), "one card per component")
    assertTrue(html.contains("button-filled__ideal__default__light"), "default render is the card")
    assertFalse(html.contains("direction-rtl"), "the RTL variant is folded out of the grid")
    assertFalse(html.contains("locale-ar-xb"), "the locale variant is folded out of the grid")
  }

  @Test
  fun `the viewer renders a same-theme variant switcher with the current variant active`() {
    val current = buttonVariants[0] // default, light
    val html =
      ServeWeb.viewerPage(current, token = "t", basePath = "/compose-m3", siblings = buttonVariants)

    assertTrue(html.contains("class=\"cp-tree cp-axes-tree\""), "component subtree rendered")
    val nav = html.substringAfter("class=\"cp-tree cp-axes-tree\"").substringBefore("</nav>")
    // Links the SAME-THEME (light) RTL + locale variants…
    assertTrue(
      nav.contains("/compose-m3/p/button-filled__ideal__default__light__direction-rtl"),
      "switcher links the same-theme RTL variant",
    )
    // …and never the dark render (that would jump the visitor's theme).
    assertFalse(nav.contains("__dark__direction-rtl"), "switcher stays within the current theme")
    // The default is marked active, and the variants carry human labels.
    assertTrue(
      nav.contains("aria-current=\"page\"><span class=\"cp-tree-label\">Filled"),
      "the default is marked active",
    )
    assertTrue(
      nav.contains(">RTL</a>") && nav.contains(">Locale ar-XB</a>"),
      "props variants render human labels",
    )
  }

  @Test
  fun `a component with no props variants renders no variant switcher`() {
    val plain =
      listOf(
        ServePreview("button__ideal__default__light", "button", state = "default", theme = "light"),
        ServePreview("button__ideal__default__dark", "button", state = "default", theme = "dark"),
      )
    val html =
      ServeWeb.viewerPage(plain[0], token = "t", basePath = "/compose-m3", siblings = plain)
    assertFalse(
      html.contains("aria-label=\"Component variant\""),
      "no variant switcher for a component without props variants",
    )
  }

  @Test
  fun `viewer advertises its rendered png to link unfurlers`() {
    val html =
      ServeWeb.viewerPage(
        preview = ServePreview("red", "Red & \"Blue\""),
        token = "unused",
        unfurl =
          ServeWeb.UnfurlMetadata(
            pageUrl = "https://preview.example/p/red?theme=dark&fontScale=1.5",
            imageUrl = "https://preview.example/render/red.png?theme=dark&fontScale=1.5",
          ),
      )

    assertTrue(
      html.contains("<meta property=\"og:title\" content=\"Red &amp; &quot;Blue&quot;\">"),
      "Open Graph title is present and escaped",
    )
    assertTrue(
      html.contains(
        "<meta property=\"og:image\" content=\"https://preview.example/render/red.png?" +
          "theme=dark&amp;fontScale=1.5\">"
      ),
      "Open Graph image points at the rendered PNG",
    )
    assertTrue(
      html.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"),
      "large-image Twitter card is present",
    )
    assertTrue(
      html.contains(
        "<meta name=\"twitter:image\" content=\"https://preview.example/render/red.png?" +
          "theme=dark&amp;fontScale=1.5\">"
      ),
      "Twitter card uses the same rendered PNG",
    )
    assertTrue(
      html.contains("<title>Red &amp; &quot;Blue&quot; — compose-preview</title>"),
      "document title is escaped exactly once",
    )
  }

  /**
   * Declared dimensions let an unfurler lay the card out without downloading and measuring the
   * image first — the step both Slack and Google skip (dropping the image) when it is slow.
   */
  @Test
  fun `a known image size is declared, and sizes the card honestly`() {
    fun viewerWith(w: Int?, h: Int?): String =
      ServeWeb.viewerPage(
        preview = ServePreview("red", "Red"),
        token = "unused",
        unfurl =
          ServeWeb.UnfurlMetadata(
            pageUrl = "https://preview.example/p/red",
            imageUrl = "https://preview.example/render/red.png",
            imageWidth = w,
            imageHeight = h,
          ),
      )

    val big = viewerWith(1024, 768)
    assertTrue(big.contains("<meta property=\"og:image:width\" content=\"1024\">"), big)
    assertTrue(big.contains("<meta property=\"og:image:height\" content=\"768\">"), big)
    assertTrue(big.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"), big)

    // A single component render is a thumbnail. Asking for the large card and getting the small one
    // anyway is worse than asking for the small one: the fetcher was told something untrue.
    val small = viewerWith(300, 210)
    assertTrue(small.contains("<meta property=\"og:image:width\" content=\"300\">"), small)
    assertTrue(small.contains("<meta name=\"twitter:card\" content=\"summary\">"), small)

    // Unknown size is not evidence of a small image — the fetcher measures it itself, and the
    // dimensions are omitted rather than guessed.
    val unknown = viewerWith(null, null)
    assertFalse(unknown.contains("og:image:width"), unknown)
    assertTrue(
      unknown.contains("<meta name=\"twitter:card\" content=\"summary_large_image\">"),
      unknown,
    )

    // Half a size is no size: a fetcher can't sanity-check one axis against the pixels.
    val partial = viewerWith(1024, null)
    assertFalse(partial.contains("og:image:width"), partial)
  }

  /**
   * Being big enough was never sufficient. A large-image card is laid out at ~1.91:1 and the image
   * is cropped to fill it, so what a portrait render actually shows is a horizontal band through
   * its middle. The front door's own hero — 1078×2399 — cleared the min-edge test comfortably and
   * unfurled as a strip of the empty half of an app scaffold.
   */
  @Test
  fun `a large card is claimed only for a shape that can fill one`() {
    fun cardFor(w: Int, h: Int): String {
      val html =
        ServeWeb.viewerPage(
          preview = ServePreview("red", "Red"),
          token = "unused",
          unfurl =
            ServeWeb.UnfurlMetadata(
              pageUrl = "https://preview.example/p/red",
              imageUrl = "https://preview.example/render/red.png",
              imageWidth = w,
              imageHeight = h,
            ),
        )
      return Regex("<meta name=\"twitter:card\" content=\"([a-z_]+)\">").find(html)!!.groupValues[1]
    }

    // The drawn unfurl card, and ordinary landscape renders down to 4:3 — the crop still leaves
    // about two thirds of those.
    assertEquals("summary_large_image", cardFor(1200, 630), "the card's own 1.90 aspect")
    assertEquals("summary_large_image", cardFor(1024, 640))
    assertEquals("summary_large_image", cardFor(1024, 768), "4:3 survives the crop")

    // The regression this exists for: a phone screenshot, and the front door's real hero.
    assertEquals("summary", cardFor(1078, 2399), "a portrait render can't fill a banner")
    assertEquals("summary", cardFor(945, 1376))
    // A square watch face is closer to the slot than a phone is, and still loses a third of itself.
    assertEquals("summary", cardFor(454, 454))
    // Too wide is only trimmed at the sides, so the band is generous — but not unbounded.
    assertEquals("summary_large_image", cardFor(1200, 520))
    assertEquals("summary", cardFor(3000, 600), "a panorama is not a card either")
  }

  /**
   * Every page carries the site icon links. Without them an unfurl card shows a generic globe
   * beside itself, whatever the picture on it is.
   */
  @Test
  fun `every page advertises the site icon`() {
    val html = ServeWeb.viewerPage(preview = ServePreview("red", "Red"), token = "unused")

    assertTrue(
      html.contains("<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"),
      html,
    )
    assertTrue(
      html.contains("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"),
      html,
    )
  }

  /**
   * The front door used to call itself two different things — "Design systems" in the tab and
   * "Compose previews" in the Open Graph block — so a link's name depended on which one the
   * consumer preferred.
   */
  @Test
  fun `the front door's tab and card agree on its name`() {
    val html =
      ServeWeb.homeIndexPage(
        systems = emptyList(),
        token = "unused",
        isPublic = true,
        unfurl = ServeWeb.UnfurlMetadata(pageUrl = "https://preview.example/"),
      )

    assertTrue(html.contains("<title>Design systems — compose-preview</title>"), html)
    assertTrue(html.contains("<meta property=\"og:title\" content=\"Design systems\">"), html)
    assertTrue(html.contains("<meta name=\"twitter:title\" content=\"Design systems\">"), html)
  }

  private fun builderSystem(id: String) =
    ServeWeb.HomeSystem(
      system = id,
      title = id,
      subtitle = null,
      previewCount = 1,
      trust = null,
      heroPreviewId = null,
    )

  private fun builderHome(invite: ServeWeb.UiBuilderInvite?, componentBrowser: Boolean = false) =
    ServeWeb.homeIndexPage(
      listOf(builderSystem("m3-catalog"), builderSystem("plain")),
      token = "unused",
      isPublic = true,
      uiBuilder = invite,
      componentBrowser = componentBrowser,
    )

  /**
   * The builder was reachable only by knowing its URL, so the whole authoring surface was invisible
   * from the front door. The card offers it — on the catalogs the builder actually runs for, and to
   * the visitors whose credential the create route will accept.
   */
  @Test
  fun `a front-door card offers the UI builder to a permitted visitor`() {
    val html =
      builderHome(
        ServeWeb.UiBuilderInvite(
          systems = setOf("m3-catalog"),
          signedIn = true,
          permitted = true,
        )
      )

    assertTrue(
      html.contains(
        "<a class=\"cp-action-chip cp-action-chip--primary\" href=\"/ui-builder/m3-catalog/\" " +
          "aria-label=\"m3-catalog: open the UI Builder\">"
      ),
      html,
    )
    // …but only for a catalog the builder is configured for. Every other card is unchanged.
    assertFalse(html.contains("/ui-builder/plain/"), html)
    assertEquals(1, Regex("cp-action-chip--primary").findAll(html).count(), html)
  }

  /**
   * The refusal is the point. A signed-in visitor whose account does not carry the write capability
   * used to get *nothing* — no chip, no explanation, no way to learn that the builder exists or
   * what would let them in. Silence is indistinguishable from the feature not being there.
   */
  @Test
  fun `a visitor without access gets the reason rather than an absence`() {
    val html =
      builderHome(
        ServeWeb.UiBuilderInvite(
          systems = setOf("m3-catalog"),
          signedIn = true,
          permitted = false,
          deniedReason = "Creating a design needs write access to acme/design.",
        )
      )

    assertTrue(html.contains("cp-action-chip cp-action-chip--locked"), html)
    assertTrue(
      html.contains(
        "<span class=\"cp-action-note-body\">" +
          "Creating a design needs write access to acme/design.</span>"
      ),
      html,
    )
    // Explained, not offered: nothing on the page links the create route.
    assertFalse(html.contains("/ui-builder/m3-catalog/"), html)
  }

  /** Creating a design is a write, so an anonymous visitor is offered the header's sign-in only. */
  @Test
  fun `a signed-out visitor is offered no builder action at all`() {
    val html =
      builderHome(
        ServeWeb.UiBuilderInvite(
          systems = setOf("m3-catalog"),
          signedIn = false,
          permitted = false,
          deniedReason = "Creating a design needs write access to acme/design.",
        )
      )

    assertFalse(html.contains("UI Builder"), html)
    // …and a host that does not run the builder says nothing about it either.
    assertFalse(builderHome(null).contains("UI Builder"), html)
  }

  /** Catalog mode is for browsing components, not authoring against them — as with the compare. */
  @Test
  fun `component-browser mode drops the builder action`() {
    val html =
      builderHome(
        ServeWeb.UiBuilderInvite(
          systems = setOf("m3-catalog"),
          signedIn = true,
          permitted = true,
        ),
        componentBrowser = true,
      )

    assertFalse(html.contains("UI Builder"), html)
  }

  /**
   * The front door's search is one field over two indexes: the catalog cards in the DOM, and the
   * cross-catalog component index the command palette already reads. Typing a component's name used
   * to empty the page — nothing on a card names the components inside it.
   */
  @Test
  fun `the front-door search reaches component names, and collapses into the bar`() {
    val html =
      ServeWeb.homeIndexPage(
        listOf(builderSystem("m3-catalog")),
        token = "unused",
        isPublic = true,
      )

    // Collapsed: the control in the bar is a disclosure button over a hidden field.
    assertTrue(html.contains("id=\"cp-site-search-toggle\""), html)
    assertTrue(
      html.contains("aria-expanded=\"false\" aria-controls=\"cp-site-search-field\""),
      html,
    )
    assertTrue(
      html.contains("<div class=\"cp-site-search-field\" id=\"cp-site-search-field\" hidden>"),
      html,
    )
    // The header search sits in the header, not in the page body it filters.
    assertTrue(html.indexOf("cp-site-search-toggle") < html.indexOf("<main"), html)
    // Components: the same index the palette reads, matched by label AND by keywords, and the card
    // whose catalog published a hit stays visible even when its own text matched nothing.
    assertTrue(html.contains("data-cp-global-components=\"/api/components\""), html)
    assertTrue(html.contains("(c.label||\"\")+\" \"+(c.keywords||\"\")"), html)
    assertTrue(html.contains("owners[c.getAttribute(\"data-cp-system\")]===true"), html)
    assertTrue(html.contains("id=\"cp-home-components\""), html)
    // Component labels come from a catalog's own export: they are text, never markup.
    assertTrue(html.contains("name.textContent=c.label"), html)
    assertFalse(html.contains("innerHTML"), html)
  }

  /**
   * The comparison used to be reachable only from a catalog's own landing page, so "compare this
   * system against its Figma" cost a visit to the catalog first and was invisible from `/`
   * (compose-ai-tools#4324).
   */
  @Test
  fun `a front-door card offers the comparison, named after the tool the catalog names`() {
    fun system(id: String, compares: Boolean, tool: String?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        heroPreviewId = null,
        hasReferenceComparison = compares,
        designToolLabel = tool,
      )

    val html =
      ServeWeb.homeIndexPage(
        listOf(
          system("compose-m3", compares = true, tool = "Figma"),
          system("penpot-kit", compares = true, tool = "Penpot"),
          // Publishes references whose provider names no design tool — a checked-in `png`, an
          // `svg`, an unmapped token. The route works, so the action stays and takes the neutral
          // wording; gating it on the vendor label instead dropped it entirely (#4349).
          system("png-kit", compares = true, tool = null),
          system("plain", compares = false, tool = null),
        ),
        token = "unused",
        isPublic = true,
      )

    // The verb is said once by the row; the chip is left holding only where it goes, so a second
    // destination can sit beside it on one line.
    assertTrue(html.contains(">Compare to</span>"), html)
    assertTrue(
      html.contains(
        "<a class=\"cp-action-chip cp-action-chip--compact\" " +
          "href=\"/compose-m3/compare?format=reference\" " +
          "aria-label=\"compose-m3: compare to Figma\" " +
          "title=\"compose-m3: compare to Figma\">Figma</a>"
      ),
      html,
    )
    // The label follows the catalog's own design tool rather than being hardcoded to Figma.
    assertTrue(html.contains(">Penpot</a>"), html)
    // …and falls back to the landing page's own neutral wording when there is no tool to name,
    // rather than the action disappearing.
    assertTrue(html.contains("/png-kit/compare?format=reference"), html)
    assertTrue(html.contains(">design references</a>"), html)
    // A catalog that publishes no design references has nothing behind `format=reference`, so it
    // gets no action rather than a chip that deep-links a format the comparison page won't offer.
    assertFalse(html.contains("/plain/compare"), html)
    // …and no EMPTY row stands in for it. The chip lives inside the card now, so the grid's own
    // stretch is what makes a card with an action and one without the same size — the reserved
    // placeholder row the outside-the-card layout needed is gone.
    assertEquals(3, Regex("<div class=\"cp-sys-actions\">").findAll(html).count(), html)
    assertFalse(html.contains("<div class=\"cp-sys-actions\"></div>"), html)
  }

  /**
   * The PAIRED catalog's comparison, on the card.
   *
   * `remote-m3` and `wear-m3-catalog` sit as adjacent cards on the real front door and nothing
   * there said they were a pair: "how does the Remote Compose rendition differ from the Wear one"
   * cost a visit to one catalog's landing first, which is exactly the journey the Figma action
   * above exists to remove. `docs/design/COMPARE_NAVIGATION.md` §1 names this the comparison the
   * reader most wants and the one with the fewest ways in.
   */
  @Test
  fun `a front-door card offers the paired catalog's comparison, named after the sibling`() {
    fun system(id: String, sibling: ServeWeb.ParallelComparison?) =
      ServeWeb.HomeSystem(
        system = id,
        title = id,
        subtitle = null,
        previewCount = 1,
        trust = null,
        heroPreviewId = null,
        hasReferenceComparison = true,
        designToolLabel = "Figma",
        parallelComparison = sibling,
      )

    val html =
      ServeWeb.homeIndexPage(
        listOf(
          system(
            "remote-m3",
            ServeWeb.ParallelComparison("wear-m3-catalog", "M3 Wear OS Apps Design Kit"),
          ),
          // Every catalog that declares no `compareWith`, which is most of them.
          system("compose-m3", sibling = null),
        ),
        token = "unused",
        isPublic = true,
      )

    // The chip reads the SYSTEM id — short, bounded, and the handle the card already prints under
    // its own title — while the whole sentence, with the catalog's real name, stays in the
    // accessible name and the tooltip where its length costs nothing.
    assertTrue(
      html.contains(
        "<a class=\"cp-action-chip cp-action-chip--compact\" " +
          "href=\"/remote-m3/compare?format=parallel\" " +
          "aria-label=\"remote-m3: compare to M3 Wear OS Apps Design Kit\" " +
          "title=\"remote-m3: compare to M3 Wear OS Apps Design Kit\">wear-m3-catalog</a>"
      ),
      html,
    )
    // It stands BESIDE the design-tool chip under one "Compare to", rather than replacing it or
    // repeating the verb: they are two different comparisons, and a catalog with a sibling still
    // has a design file.
    assertTrue(html.contains("/remote-m3/compare?format=reference"), html)
    assertEquals(2, Regex(">Compare to</span>").findAll(html).count(), html)
    // …and a catalog with no pairing gets no second chip rather than a dead one.
    assertFalse(html.contains("/compose-m3/compare?format=parallel"), html)
    assertEquals(1, Regex("format=parallel").findAll(html).count(), html)
  }

  /**
   * A front door lists many catalogs, and several may name the same tool — so several sibling links
   * are announced identically as "compare to Figma". The tile that gives each one its context is a
   * SIBLING, so nothing labels the chip by it: a screen-reader link list or a voice command has
   * nothing to tell them apart unless the accessible name carries the catalog.
   */
  @Test
  fun `front-door comparison links are told apart by catalog, keeping their visible text`() {
    fun system(id: String, title: String) =
      ServeWeb.HomeSystem(
        system = id,
        title = title,
        subtitle = null,
        previewCount = 1,
        trust = null,
        heroPreviewId = null,
        hasReferenceComparison = true,
        designToolLabel = "Figma",
      )

    val html =
      ServeWeb.homeIndexPage(
        listOf(system("compose-m3", "Compose Material 3"), system("wear-m3", "Wear Material 3")),
        token = "unused",
        isPublic = true,
      )

    val names =
      Regex("aria-label=\"([^\"]*compare to[^\"]*)\"")
        .findAll(html)
        .map { it.groupValues[1] }
        .toList()
    assertEquals(
      listOf("Compose Material 3: compare to Figma", "Wear Material 3: compare to Figma"),
      names,
      html,
    )
    // WCAG 2.5.3 Label in Name: the visible string survives INTACT inside the accessible name, so
    // "click Figma" still matches. A name like "compare Wear Material 3 to Figma" would read fine
    // and break that — and so would shortening the chip to something the name does not contain.
    names.forEach { assertTrue(it.contains("Figma"), it) }
    // The chip itself stays short — the verb is on the row and the catalog's name is in the
    // accessible name, neither of them repeated on screen once per destination.
    assertTrue(
      html.contains(
        "aria-label=\"Wear Material 3: compare to Figma\" " +
          "title=\"Wear Material 3: compare to Figma\">Figma</a>"
      ),
      html,
    )
  }

  /** The token has to ride the compare link too, or a gated box 403s the destination. */
  @Test
  fun `the front door's comparison link carries the token on a gated box`() {
    val system =
      ServeWeb.HomeSystem(
        system = "compose-m3",
        title = "Material 3",
        subtitle = null,
        previewCount = 1,
        trust = null,
        heroPreviewId = null,
        hasReferenceComparison = true,
        designToolLabel = "Figma",
      )

    val gated = ServeWeb.homeIndexPage(listOf(system), token = "a token", isPublic = false)
    assertTrue(gated.contains("/compose-m3/compare?format=reference&amp;token=a%20token"), gated)
  }

  /**
   * Catalog mode strips the format comparisons from a catalog's landing page — it is for browsing
   * components, not for auditing them against a design file — so the front door has to agree.
   */
  @Test
  fun `catalog mode offers no comparison on the front door`() {
    val system =
      ServeWeb.HomeSystem(
        system = "compose-m3",
        title = "Material 3",
        subtitle = null,
        previewCount = 1,
        trust = null,
        heroPreviewId = null,
        hasReferenceComparison = true,
        designToolLabel = "Figma",
      )

    val browser =
      ServeWeb.homeIndexPage(
        listOf(system),
        token = "unused",
        isPublic = true,
        componentBrowser = true,
      )
    assertFalse(browser.contains("compare to Figma"), browser)
  }

  /**
   * The chip lives INSIDE the card, so the card cannot be one big `<a>` — a link inside a link is
   * not a thing HTML has, and the browser would reparent the inner one out of the card. The tile is
   * still one click target, via the title link's stretched overlay.
   */
  @Test
  fun `the front door's card is a div whose title links, with the chip outside that link`() {
    val html =
      ServeWeb.homeIndexPage(
        listOf(
          ServeWeb.HomeSystem(
            system = "compose-m3",
            title = "Material 3",
            subtitle = null,
            previewCount = 1,
            trust = null,
            heroPreviewId = null,
            hasReferenceComparison = true,
            designToolLabel = "Figma",
          )
        ),
        token = "unused",
        isPublic = true,
      )

    // The card is a div, and it holds BOTH links.
    assertTrue(html.contains("<div class=\"cp-card cp-sys\" data-browser-search="), html)
    assertFalse(html.contains("<a class=\"cp-card cp-sys\""), html)
    val card =
      html.substringAfter("<div class=\"cp-card cp-sys\"").substringBefore("\n      </div>")
    assertTrue(card.contains("cp-sys-open"), card)
    assertTrue(card.contains("cp-action-chip"), card)
    // …but the chip is NOT inside the tile link.
    val openLink = card.substringAfter("<a class=\"cp-sys-open\"").substringBefore("</a>")
    assertFalse(openLink.contains("cp-action-chip"), openLink)
    // The search filter is back on the card itself: hiding it takes the chip with it, because the
    // chip is part of the card rather than a sibling that a filter could leave behind.
    assertTrue(html.contains("document.querySelectorAll(\".cp-sys\")"), html)
    assertFalse(html.contains("cp-sys-cell"), html)
  }

  @Test
  fun `the front door advertises lazy global component search only when catalogs exist`() {
    val system =
      ServeWeb.HomeSystem(
        system = "compose-m3",
        title = "Material 3",
        subtitle = null,
        previewCount = 1,
        trust = null,
        heroPreviewId = null,
      )

    val gated = ServeWeb.homeIndexPage(listOf(system), token = "a token", isPublic = false)
    assertTrue(
      gated.contains("data-cp-global-components=\"/api/components?token=a%20token\""),
      gated,
    )
    val empty = ServeWeb.homeIndexPage(emptyList(), token = "unused", isPublic = true)
    assertFalse(empty.contains("data-cp-global-components"), empty)
  }

  @Test
  fun `global component search collapses catalog render variants like the landing grid`() {
    val entries =
      ServeWeb.componentSearchEntries(
        listOf(
          ServePreview(
            id = "button-filled__ideal__default__light",
            label = "button-filled",
            componentId = "Button/Filled",
          ),
          ServePreview(
            id = "button-filled__ideal__default__dark",
            label = "button-filled",
            componentId = "Button/Filled",
          ),
          ServePreview(
            id = "button-filled__ideal__pressed__light",
            label = "button-filled pressed",
            componentId = "Button/Filled",
            state = "pressed",
          ),
        )
      )

    assertEquals(1, entries.size)
    assertEquals("Button Filled", entries.single().label)
    assertEquals("button-filled__ideal__default__light", entries.single().previewId)
  }

  @Test
  fun `the renderer combo lists every player with the unavailable ones disabled`() {
    // A Remote Compose preview on an Android daemon: js (client canvas) + java + cmp-android are
    // enabled; the opt-in CMP/Wasm and unadvertised cmp-jvm lanes remain disabled.
    val preview = ServePreview(id = "widget.Chip", label = "chip")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/remote-m3",
        siblings = listOf(preview),
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "java", "cmp-android"),
      )

    assertTrue(html.contains("id=\"cp-lane-select\""), "the renderer combo is rendered")
    // Every universe entry is an option — the unavailable ones included, so the set of players
    // stays legible from any session.
    for (wire in listOf("js", "cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      assertTrue(html.contains("value=\"rc:$wire\""), "option for $wire present")
    }
    // CMP Android is the seeded default: both the combo's selection and the chip's opening label.
    // It opens on the embedded player because that is the lane whose output is a real Compose tree
    // — editable figma-svg geometry and a described semantics tree, rather than one interop leaf
    // (#3936). `?rcPlayer=java` still selects the view player.
    assertTrue(
      html.contains("data-rc-default=\"cmp-android\""),
      "cmp-android is the default player",
    )
    assertTrue(html.contains("<option value=\"rc:java\">Java</option>"), html)
    // The combo itself rests on its placeholder — the chip is what names the current lane, and a
    // combo repeating that name beside it read as two controls arguing about the same fact.
    assertTrue(html.contains("<option value=\"\" selected>Switch renderer…</option>"), html)
    assertTrue(
      html.contains("<span id=\"cp-live-toggle-label\">CMP Android</span>"),
      "the chip names the lane it opens on",
    )
    // cmp-jvm is the disabled option (and says why in its own label); the enabled ones are not.
    assertTrue(
      html.contains("<option value=\"rc:cmp-jvm\" disabled>CMP JVM (unavailable)</option>"),
      html,
    )
    val android = Regex("<option value=\"rc:cmp-android\"[^>]*>").find(html)?.value ?: ""
    assertFalse(android.contains(" disabled"), "cmp-android is offered: '$android'")
    // …and the step out to every player side by side.
    assertTrue(
      html.contains("href=\"/remote-m3/compare?format=rc&preview=widget.Chip&token=t\""),
      html,
    )
    assertTrue(html.contains(">compare players →</a>"), "the compare link names what it does")
  }

  @Test
  fun `a js-only host disables the server-side player options and offers no comparison`() {
    // A static bundle carries the `.rc` doc (js works client-side) but has no daemon, so the
    // server-side java / cmp-android lanes are disabled alongside the never-available cmp-jvm.
    val preview = ServePreview(id = "widget.Chip", label = "chip")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/remote-m3",
        siblings = listOf(preview),
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js"),
      )

    assertTrue(
      html.contains("data-rc-default=\"js\""),
      "js is the default when it is the only lane",
    )
    for (wire in listOf("cmp-wasm", "java", "cmp-android", "cmp-jvm")) {
      val option = Regex("<option value=\"rc:$wire\"[^>]*>").find(html)?.value ?: ""
      assertTrue(option.contains(" disabled"), "$wire disabled on a js-only host: '$option'")
    }
    val js = Regex("<option value=\"rc:js\"[^>]*>").find(html)?.value ?: ""
    assertFalse(js.contains(" disabled"), "js is offered: '$js'")
    // One player is nothing to compare against, so the link stays off.
    assertFalse(html.contains("compare players"), "no comparison link with a single player")
  }

  @Test
  fun `cmp wasm backend gets its own iframe and mode`() {
    val preview =
      ServePreview(
        id = "widget.Chip",
        label = "chip",
        remoteComposeKnobs =
          listOf(RemoteComposeKnobDeclaration("label", RemoteNamedValue.StringValue("Hello"))),
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        hasRemoteComposeDoc = true,
        enabledRcPlayers = listOf("js", "cmp-wasm"),
      )

    val option = Regex("<option value=\"rc:cmp-wasm\"[^>]*>").find(html)?.value ?: ""
    assertFalse(option.contains(" disabled"), "cmp-wasm is offered: '$option'")
    assertTrue(html.contains("id=\"cp-rc-wasm\""), "dedicated CMP/Wasm iframe is present")
    assertTrue(html.contains("value=\"rc-wasm\""), "dedicated CMP/Wasm mode is present")
    assertTrue(
      html.contains("sandbox=\"allow-scripts allow-same-origin\""),
      "repository-owned player can fetch the tokened document from its own origin",
    )
    val knob = Regex("<input[^>]*data-rc-name=\"label\"[^>]*>").find(html)?.value ?: ""
    assertFalse(knob.contains(" disabled"), "CMP/Wasm can apply named values: '$knob'")
    val viewerJs = viewerSource()
    assertTrue(viewerJs.contains("namedValues="), "named values are passed to the isolated host")
    assertTrue(viewerJs.contains("e.origin !== location.origin"), "messages are origin checked")
    assertTrue(
      viewerJs.contains("new CustomEvent(e.data.type"),
      "validated host actions are exposed without executing their payload",
    )
  }

  @Test
  fun `a non-rc preview renders no backend selector`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("id=\"cp-lane-select\""), "no combo for a single-lane preview")
  }

  @Test
  fun `the viewer links the preview source when a source href is supplied`() {
    val preview = ServePreview(id = "plain.Button", label = "button", sourceFile = "src/main/A.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        sourceHref = "https://github.com/o/r/blob/main/src/main/A.kt",
      )
    assertTrue(html.contains("class=\"cp-source\""), "source link block rendered")
    assertTrue(
      html.contains("href=\"https://github.com/o/r/blob/main/src/main/A.kt\""),
      "links the resolved blob url",
    )
    // The module-relative path is surfaced as the link tooltip.
    assertTrue(html.contains("title=\"src/main/A.kt\""), "source path shown as tooltip")
  }

  @Test
  fun `landing and viewer surface preview engagement counts`() {
    val previews =
      listOf(
        ServePreview(id = "plain.Button", label = "button"),
        ServePreview(id = "plain.Card", label = "card"),
      )
    val landing =
      ServeWeb.landingPage(
        "bundle",
        previews,
        token = "t",
        engagement = mapOf("plain.Button" to ServeWeb.PreviewEngagement(12)),
        systemViews = 1234,
      )
    assertTrue(
      landing.contains(
        """<div class="cp-engage"><span ${ServeWeb.VOLATILE_ATTR}>12 views</span></div>"""
      ),
      landing,
    )
    assertTrue(
      landing.contains("2 previews · <span ${ServeWeb.VOLATILE_ATTR}>1.2k views</span>"),
      landing,
    )

    val viewer =
      ServeWeb.viewerPage(
        previews[0],
        token = "t",
        siblings = previews,
        engagement = ServeWeb.PreviewEngagement(13),
      )
    assertTrue(
      viewer.contains(
        """<span class="cp-viewer-engage"><span ${ServeWeb.VOLATILE_ATTR}>13 views</span></span>"""
      ),
      viewer,
    )
  }

  @Test
  fun `home cards subtly surface catalog engagement`() {
    val html =
      ServeWeb.homeIndexPage(
        systems =
          listOf(
            ServeWeb.HomeSystem(
              system = "compose-m3",
              title = "Material 3",
              subtitle = null,
              previewCount = 42,
              trust = null,
              heroPreviewId = null,
              views = 12_345,
            )
          ),
        token = "t",
      )
    assertTrue(
      html.contains("42 previews · <span ${ServeWeb.VOLATILE_ATTR}>12.3k views</span>"),
      html,
    )
  }

  @Test
  fun `the viewer offers a prefilled issue link beside the source link`() {
    val preview = ServePreview(id = "plain.Button", label = "button", sourceFile = "src/main/A.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        sourceHref = "https://github.com/o/r/blob/main/src/main/A.kt",
        reportIssue =
          ServeWeb.ReportIssue(
            action = "https://github.com/o/r/issues/new",
            body = "render: https://host/render/x.png",
            bodyTemplate = "render: {{render}}",
            repo = "o/r",
            login = "octocat",
          ),
      )
    assertTrue(html.contains("class=\"cp-preview-links\""), "source + report share one row")
    assertTrue(html.contains("id=\"cp-report\""), "report affordance rendered")
    // A GET form, not a link: nothing page-derived may reach a navigation sink, so the action is a
    // server-rendered literal and the prefill rides in a hidden input the browser encodes on
    // submit. The link-styled toggle is the disclosure's own <summary>, so it works with JS off.
    assertTrue(
      html.contains(
        "<details class=\"cp-report\" id=\"cp-report\" data-cp-repo=\"o/r\"" +
          " data-cp-subject=\"this preview\">"
      ) &&
        html.contains("<summary class=\"cp-report-link\"") &&
        html.contains("<form class=\"cp-report-form\" method=\"get\"") &&
        html.contains("action=\"https://github.com/o/r/issues/new\""),
      "the issue form posts to the resolved repo",
    )
    // The reporter writes the title — the server no longer derives one from the preview's name,
    // and `required` is what stops an untitled report whether or not the page's script ran.
    assertTrue(
      html.contains(
        "<input class=\"cp-report-summary-input\" type=\"text\" name=\"title\" required"
      ),
      "the reporter is asked for a summary, and cannot skip it",
    )
    assertFalse(
      html.contains("type=\"hidden\" name=\"title\""),
      "no server-written title rides along behind the reporter's back",
    )
    assertTrue(
      html.contains("name=\"body\" id=\"cp-report-body\"") &&
        html.contains("value=\"render: https://host/render/x.png\""),
      "the server-filled prefill works without JS",
    )
    assertTrue(
      html.contains("data-report-template=\"render: {{render}}\""),
      "carries the template the viewer JS re-substitutes at the current overrides",
    )
    // The toggle's tooltip and the panel's note both name the repo the issue lands on, and — when
    // this box knows the visitor's GitHub session — whose account will author it. Both also say
    // what the report is ABOUT: this server has a second one a click away in the footer, and the
    // two used to be distinguishable only by where they sat on the page.
    assertTrue(
      html.contains("title=\"Something wrong with this preview — files against o/r as @octocat\""),
      html,
    )
    assertTrue(html.contains("Files against <code>o/r</code> as @octocat"), html)
    assertTrue(html.contains("<em>not</em> the preview server"), html)
  }

  @Test
  fun `the report form asks where the difference belongs, and files the answer as a label`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        reportIssue =
          ServeWeb.ReportIssue(
            action = "https://github.com/o/r/issues/new",
            body = "### What's wrong",
            bodyTemplate = "### What's wrong",
            repo = "o/r",
          ),
      )
    // `name="labels"` is the whole transport: GitHub's new-issue form reads it straight from the
    // query, so the answer reaches the filed issue with the browser's own control and no script.
    assertTrue(html.contains("<select class=\"cp-report-class-input\" name=\"labels\">"), html)
    // The three answers, in the `parity:` vocabulary the catalog's own issue index speaks — a value
    // outside it comes back from `parity/issues.json` as no classification at all.
    for (value in listOf("parity:upstream", "parity:catalog", "parity:verification-needed")) {
      assertTrue(html.contains("<option value=\"$value\""), "$value offered: $html")
    }
    // Not knowing is the default, and a first-class answer: a report filed without a thought about
    // this is an unclassified one, and saying so beats a confident wrong label.
    assertTrue(
      html.contains("whether this is ours or upstream.\" selected>Needs investigating"),
      html,
    )
    // Every option carries the sentence `<cp-report-classification>` writes into the body, so the
    // issue states the answer in prose as well — for the reader, and for a repository that has no
    // such label to apply.
    assertTrue(html.contains("data-cp-sentence=\"Upstream: the framework"), html)
  }

  @Test
  fun `the report form chooses component-wide or exact-variant issue scope`() {
    val preview = ServePreview(id = "button__ideal__large", label = "button")
    val locator =
      ServeIssueReport.locatorBlock(
        ServeIssueReport.Locator(
          repository = "o/r",
          system = "compose-m3",
          componentId = "Button/Filled",
          previewId = preview.id,
          referenceId = preview.id,
          variant = "ideal/large",
          overrides = emptyMap(),
        )
      )
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        reportIssue =
          ServeWeb.ReportIssue(
            action = "https://github.com/o/r/issues/new",
            body = locator,
            bodyTemplate = locator,
            repo = "o/r",
          ),
      )
    assertTrue(html.contains("<cp-report-scope"), html)
    assertTrue(html.contains("value=\"component\" selected>This component"), html)
    assertTrue(
      html.contains("value=\"variant\" disabled hidden>This component + variant"),
      "the script-dependent choice stays unavailable until its body writer attaches: $html",
    )
  }

  @Test
  fun `the report body carries a classification line pointing at the label`() {
    // Written by the server in BOTH the plain body and the template, and pointing at the label
    // rather than pre-writing an answer: a visitor with scripting off can still pick one — the
    // select works — but cannot have the body rewritten, so any specific sentence here would be a
    // claim the label beside it could contradict.
    val body =
      ServeIssueReport.body(
        ServeIssueReport.Context(repo = "yschimke/m3-catalog", previewId = "button")
      )
    assertTrue(
      body.contains(
        "${ServeIssueReport.CLASSIFICATION_PREFIX}${ServeIssueReport.CLASSIFICATION_UNSTATED}"
      ),
      body,
    )
  }

  @Test
  fun `the viewer links the figma node a preview is specified by`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        figmaSpec =
          ServeWeb.FigmaSpec(
            url = "https://www.figma.com/design/abc123?node-id=73-6",
            label = "Contact chat",
          ),
      )
    assertTrue(html.contains("class=\"cp-preview-links\""), "the provenance row is rendered")
    assertTrue(html.contains("class=\"cp-figma-link\""), "figma spec link rendered")
    assertTrue(
      html.contains("href=\"https://www.figma.com/design/abc123?node-id=73-6\""),
      "links the resolved node",
    )
    // Opened in a new tab, and the label names which spec it is.
    assertTrue(html.contains("rel=\"noopener noreferrer\""), html)
    assertTrue(html.contains("specified by — Contact chat"), "the tooltip names the reference")
  }

  @Test
  fun `the viewer offers the imported spec as a lane beside the renderers`() {
    val preview = ServePreview(id = "com.example.ProfileScreenPreview", label = "Profile")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "contact-chat-figma",
            previewId = preview.id,
            label = "Contact chat",
            raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
        referenceAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 20, y = 30, width = 120, height = 24),
              label = "titleLarge 22sp/28sp",
              role = "Title",
              detail = mapOf("token" to "titleLarge", "fontFamily" to "Roboto Flex"),
            )
          ),
      )
    assertTrue(html.contains("id=\"cp-spec-lane\""), "the spec lane carrier is rendered")
    // The lane is a top-level chip named after the design tool it imported from — NOT an option
    // inside the renderer combo, which is where it used to sit behind five player names. The raster
    // is served from THIS server's reference route; nothing points at figma.com.
    assertTrue(
      html.contains("id=\"cp-spec-chip\"") && html.contains(">Figma</button>"),
      "the spec lane has its own chip, named after the design tool: $html",
    )
    assertFalse(
      html.contains("<option value=\"spec\""),
      "the spec lane is no longer hidden inside the renderer combo: $html",
    )
    assertTrue(
      html.contains("data-spec-src=\"/meshcore-mobile/reference/contact-chat-figma.png?token=t\""),
      html,
    )
    // A hidden mode radio + a stage image, so the lane joins the same mode machinery as the
    // player lanes (bookmarkable `?mode=spec`, Back/Forward, one lane on the stage at a time).
    assertTrue(html.contains("value=\"spec\" id=\"cp-spec-toggle\""), "the mode radio is rendered")
    assertTrue(html.contains("id=\"cp-spec-img\""), "the stage image is rendered")
    assertTrue(
      html.contains("id=\"cp-inspect-typography\"") && html.contains("<cp-inspect-layers>"),
      "published reference typography remains inspectable without a live annotation host: $html",
    )
    // …and the step from "look at the spec" to "diff it" against this render.
    assertTrue(
      html.contains(
        "/meshcore-mobile/compare/com.example.ProfileScreenPreview?token=t" +
          "&amp;reference=contact-chat-figma"
      ),
      html,
    )
  }

  @Test
  fun `a static catalog's published typography offers the Typography layer but not Theme`() {
    // The two lanes behind one layer. A published bundle carries typography measured over its baked
    // frame, so `.annotations` answers with no daemon at all — but theme attributes are projected
    // live from a semantics tree and nothing authors them into a bundle, so that row must stay off.
    // Folding the two into one flag would either hide a layer that works or offer one that cannot.
    val preview = ServePreview(id = "com.example.ProfileScreenPreview", label = "Profile")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        hasDesignAnnotations = false,
        hasPublishedTypography = true,
      )
    assertTrue(
      html.contains("id=\"cp-inspect-typography\"") && html.contains("<cp-inspect-layers>"),
      "published typography is inspectable without a daemon: $html",
    )
    assertFalse(
      html.contains("id=\"cp-inspect-theme\""),
      "no semantics lane ⇒ no Theme attributes row: $html",
    )

    // A daemon-backed session keeps both, unchanged.
    val live =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        hasDesignAnnotations = true,
      )
    assertTrue(live.contains("id=\"cp-inspect-typography\""), live)
    assertTrue(live.contains("id=\"cp-inspect-theme\""), live)
  }

  /** A viewer page for one preview carrying a Figma reference with [match]. */
  private fun chipHtmlFor(match: DesignReferenceMatch?): String {
    val preview = ServePreview(id = "com.example.ProfileScreenPreview", label = "Profile")
    return ServeWeb.viewerPage(
      preview,
      token = "t",
      basePath = "/meshcore-mobile",
      siblings = listOf(preview),
      designReference =
        DesignReference(
          id = "contact-chat-figma",
          previewId = preview.id,
          label = "Contact chat",
          raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
          source = DesignReferenceSource(provider = "figma"),
          match = match,
        ),
    )
  }

  @Test
  fun `the design-spec chip states the published match score`() {
    val html = chipHtmlFor(DesignReferenceMatch(percent = 71.52, changedPercent = 29.7))
    // The catalog exists to answer "does this render match its design?", and before the verdict
    // moved onto the chip no page answered it at rest — the number was a click and two raster
    // decodes away, on every page, including the ones where the answer is 57%.
    assertTrue(html.contains(">Figma 71.5%</button>"), "the chip states the score: $html")
    assertTrue(html.contains("data-spec-match=\"off\""), "71.5% is below the close band: $html")
    // The exact numbers stay available without entering the lane.
    assertTrue(
      html.contains("71.5% match against the imported Figma spec · 29.70% pixels differ"),
      "the tooltip carries the full comparison: $html",
    )
    // The bare provider name is kept so the client can rebuild the label around a live score.
    assertTrue(html.contains("data-spec-chip-name=\"Figma\""), html)
    // And what the chip says once the render has left the snapshot the verdict was measured
    // against. The spec is imported once, never re-exported per theme, so picking a theme moves
    // one side of the comparison and not the other — and the published number then describes a
    // frame that is off the stage, generously: a pair that publishes at 99.6% scores 88.9% under
    // Light High Contrast, which reads as the spec lane being broken rather than the chip stale.
    assertTrue(
      html.contains("data-spec-chip-stale-tip=\"The published match is measured against this"),
      "the chip carries the off-baseline tooltip: $html",
    )
  }

  @Test
  fun `a preview with no published match has no stale tooltip to swap in`() {
    // Nothing to suppress: the chip already shows the plain provider label, and an off-baseline
    // tooltip there would announce a verdict that was never taken.
    val html = chipHtmlFor(null)
    assertTrue(html.contains(">Figma</button>"), html)
    assertFalse(html.contains("data-spec-chip-stale-tip"), html)
  }

  @Test
  fun `a disabled theme control keeps the published spec verdict at baseline`() {
    // Static viewers cannot apply either a URL-selected or remembered theme. The sticky bootstrap
    // may still display that choice and mark it active, but it must not suppress a score for pixels
    // that remain exactly the baked snapshot.
    val html = chipHtmlFor(DesignReferenceMatch(percent = 99.6))
    assertTrue(
      Regex("<select id=\"cp-theme\"[^>]* disabled>").containsMatchIn(html),
      "the static viewer's theme control is disabled: $html",
    )
    assertTrue(
      html.contains(
        "var atSpecBaseline = el.disabled || " + "el.getAttribute(\"data-theme-active\") !== \"1\";"
      ),
      "a disabled control cannot move the initial spec verdict off baseline: $html",
    )
  }

  @Test
  fun `the match band colours the chip without deciding whether the number shows`() {
    // Bands are read off the distribution a real catalog produces, not off round numbers: across
    // wear-m3-catalog's 186 published pairs the median is 91, scored over drawn content rather
    // than over the whole canvas (issue #4290), so `match` is the quiet majority from 95 up.
    listOf(100.0 to "match", 95.0 to "match", 94.99 to "close", 85.0 to "close", 84.99 to "off")
      .forEach { (percent, band) ->
        val html = chipHtmlFor(DesignReferenceMatch(percent = percent))
        assertTrue(
          html.contains("data-spec-match=\"$band\""),
          "$percent%% falls in the $band band: $html",
        )
        // Always printed, never hidden behind a "clean" threshold — suppressing it would make its
        // absence ambiguous with "not scored".
        assertTrue(
          html.contains(">Figma ${"%.1f".format(java.util.Locale.ROOT, percent)}%</button>"),
          "$percent%% is printed on the chip: $html",
        )
      }
  }

  @Test
  fun `a reference with no published score keeps the plain provider chip`() {
    // Every catalog published before the producer existed, and any run whose driver had no browser
    // to score with. The lane still computes the same number live on entry, so this must degrade to
    // exactly the chip it had before rather than to an empty or zeroed verdict.
    val html = chipHtmlFor(null)
    assertTrue(html.contains(">Figma</button>"), "the chip is the bare provider name: $html")
    assertFalse(html.contains("data-spec-match="), "no band is claimed: $html")
    assertTrue(
      html.contains("Put the imported Figma spec on the stage instead of the render"),
      "the original tooltip is kept: $html",
    )
  }

  @Test
  fun `the spec lane offers diff triptych and slider beside the plain spec`() {
    val preview = ServePreview(id = "com.example.ProfileScreenPreview", label = "Profile")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/meshcore-mobile",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "contact-chat-figma",
            previewId = preview.id,
            label = "Contact chat",
            raster = DesignReferenceRaster(path = "references/contact-chat-figma.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
        referenceAnnotations =
          listOf(
            DesignAnnotation(
              kind = AnnotationKind.TYPOGRAPHY,
              bounds = AnnotationBounds(x = 20, y = 30, width = 120, height = 24),
              label = "titleLarge 22sp/28sp",
              role = "Title",
              detail = mapOf("token" to "titleLarge", "fontFamily" to "Roboto Flex"),
            )
          ),
      )
    // Four ways to look at the same pair, all four on the stage rather than behind a navigation to
    // /compare — which is the point: the render worth comparing is the one the viewer's overrides,
    // knobs and theme just produced, and leaving the page loses it.
    listOf("spec", "diff", "triptych", "slider").forEach { view ->
      assertTrue(html.contains("data-cp-spec-view=\"$view\""), "the $view view is offered: $html")
    }
    // `triptych` is the default and the only pressed one (#4376), so a visitor who ignores the
    // group is already comparing rather than looking at the reference on its own.
    assertTrue(
      html.contains("data-cp-spec-view=\"triptych\" aria-pressed=\"true\""),
      "the triptych is the default view",
    )
    assertEquals(
      1,
      Regex("data-cp-spec-view=\"[^\"]+\" aria-pressed=\"true\"").findAll(html).count(),
      "one spec view is pressed",
    )
    // Hidden until the lane is entered — while a render is on the stage there is no pair to
    // compare, and `<cp-spec-compare>` reveals the group from openSpec().
    assertTrue(
      html.contains("id=\"cp-spec-views\" role=\"group\" aria-label=\"Design comparison\" hidden"),
      html,
    )
    // The comparison surface: three canvas panels plus the wipe, hidden until a view is picked,
    // and carrying the reference raster it normalises against.
    assertTrue(
      html.contains(
        "id=\"cp-spec-compare\" hidden data-view=\"triptych\" " +
          "data-reference=\"/meshcore-mobile/reference/contact-chat-figma.png?token=t\""
      ),
      html,
    )
    listOf("cp-spec-reference", "cp-spec-diff", "cp-spec-actual", "cp-spec-wipe-canvas").forEach {
      assertTrue(html.contains("id=\"$it\""), "the $it canvas is rendered: $html")
    }
    assertTrue(html.contains("id=\"cp-spec-wipe-range\""), "the wipe carries a range control")
    assertTrue(html.contains("id=\"cp-spec-score\""), "the match readout is rendered")
    assertTrue(
      html.contains("id=\"cp-spec-annotations\"") && html.contains("Roboto Flex"),
      "the Figma raster carries its own typography for the overlay",
    )
    // Load order is load-bearing: `viewer.js` calls `window.cpSpecCompare` on the way into the
    // lane, and `<cp-spec-compare>` draws every surface from format-compare.js's primitives. The
    // element wires itself up as its tag upgrades, so the components bundle has to be requested
    // before viewer.js, and the tag itself before the bundle that defines it. The inline theme
    // bootstrap must publish the baseline before that upgrade too: otherwise a cold themed deep
    // link can paint the baked verdict while the parser waits for the later scripts.
    val tag = html.indexOf("<cp-spec-compare>")
    val baseline = html.indexOf("root.setAttribute(\"data-spec-baseline\"")
    val runtime = html.indexOf("vue-runtime.js")
    val components = html.indexOf("viewer-components.js")
    val formatCompare = html.indexOf("format-compare.js")
    val viewer = html.indexOf("/viewer.js")
    assertTrue(tag in 1 until components, "the tag is parsed before the bundle upgrades it")
    assertTrue(runtime in 1 until components, "Vue loads once before the viewer controls")
    assertTrue(
      baseline in (tag + 1) until components,
      "the inline theme bootstrap publishes the baseline before the component upgrades",
    )
    assertTrue(components in 1 until viewer, "the components bundle precedes viewer.js")
    assertTrue(formatCompare in 1 until viewer, "format-compare.js precedes viewer.js")
  }

  @Test
  fun `the viewer offers no spec lane when the catalog publishes no reference`() {
    // Every catalog that has not adopted design-parity: no lane, no stage image, no mode radio.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("cp-spec-lane"), "no spec lane without a reference")
    assertFalse(html.contains("cp-spec-img"), "no spec stage image without a reference")
    assertFalse(html.contains("id=\"cp-spec-toggle\""), "no spec mode radio without a reference")
    // …and none of the comparison surface either: no canvases, no view group, and no request for
    // the script that drives them.
    assertFalse(html.contains("cp-spec-compare"), "no comparison surface without a reference")
    assertFalse(html.contains("data-cp-spec-view"), "no diff options without a reference")
    assertFalse(html.contains("cp-spec-compare"), "no <cp-spec-compare> without a lane")
  }

  @Test
  fun `a non-figma design reference is still offered as a spec lane`() {
    // design-parity's other adapters (a committed PNG bundle, an HTML export, Stitch) publish the
    // same canonical raster, so the lane is provider-neutral — only the chip's wording changes.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "button-primary",
            previewId = preview.id,
            label = "Button / Primary",
            raster = DesignReferenceRaster(path = "references/button-primary.png"),
            source = DesignReferenceSource(provider = "png"),
          ),
      )
    assertTrue(html.contains("id=\"cp-spec-lane\""), "the lane is offered for any provider")
    assertTrue(
      html.contains("id=\"cp-spec-chip\"") && html.contains(">Design spec</button>"),
      "a non-Figma provider reads as a plain design spec: $html",
    )
  }

  @Test
  fun `the spec lane offers the compareWith sibling as a second source`() {
    // The cross-system pairing, reachable from the component page rather than only from the static
    // `matches.html` (issue #4621). A SECOND SOURCE for the lane, not a second mode: the four views
    // are untouched and the kit reference stays the default pair.
    val preview = ServePreview(id = "remote.FilledRemoteButton", label = "filled")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/remote-m3",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "button-filled",
            previewId = preview.id,
            label = "Button / Filled",
            raster = DesignReferenceRaster(path = "references/button-filled.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
        parallelSource =
          ServeWeb.SpecSource(
            id = "parallel",
            label = "wear-m3-catalog",
            rasterUrl = "/wear-m3-catalog/render/button-filled.png",
            provenance = "wear-m3-catalog's own render, under that catalog's theme and knobs.",
          ),
      )
    assertTrue(html.contains("id=\"cp-spec-sources\""), "the picker is offered: $html")
    assertTrue(html.contains("data-cp-spec-source=\"kit\""), "the kit is a source")
    assertTrue(html.contains("data-cp-spec-source=\"parallel\""), "the sibling is a source")
    assertTrue(
      html.contains("/wear-m3-catalog/render/button-filled.png"),
      "the sibling's own render is same-origin on this server: $html",
    )
    assertTrue(
      html.contains("under that catalog&#39;s theme and knobs"),
      "the panel says whose render it is, rather than implying symmetry: $html",
    )
    // The kit leads, so the pair the lane opens on does not move for a paired catalog.
    val kitAt = html.indexOf("data-cp-spec-source=\"kit\"")
    val parallelAt = html.indexOf("data-cp-spec-source=\"parallel\"")
    assertTrue(kitAt in 0 until parallelAt, "the imported spec is still the default pair")
  }

  @Test
  fun `a catalog with no parallel keeps exactly the lane it had`() {
    // Every catalog that declares no `compareWith` pairing — which is most of them. One source is
    // not a picker with a single button; it is no picker, because a control that acts on nothing is
    // worse than no control.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
        designReference =
          DesignReference(
            id = "button-primary",
            previewId = preview.id,
            label = "Button / Primary",
            raster = DesignReferenceRaster(path = "references/button-primary.png"),
            source = DesignReferenceSource(provider = "figma"),
          ),
      )
    assertTrue(html.contains("id=\"cp-spec-lane\""), "the lane itself is unchanged")
    assertFalse(html.contains("id=\"cp-spec-sources\""), "no picker for a single source: $html")
    assertFalse(html.contains("data-cp-spec-source"), "and no source buttons at all")
    // The carrier still describes the one source the way it always has, which is what the backend
    // badge reads.
    assertTrue(html.contains("data-spec-src=\"/compose-m3/reference/"), "the carrier is intact")
  }

  @Test
  fun `the viewer renders no figma link when the catalog names no spec`() {
    // The common case: a catalog with no references, or whose references are HTML/PNG exports.
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("cp-figma-link"), "no figma link when no spec is supplied")
    assertFalse(
      html.contains("class=\"cp-preview-links\""),
      "the links row is omitted entirely when nothing fills it",
    )
  }

  @Test
  fun `the viewer renders no report link without a report target`() {
    val preview = ServePreview(id = "plain.Button", label = "button")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("id=\"cp-report\""), "no report link when no target is supplied")
    assertFalse(
      html.contains("class=\"cp-preview-links\""),
      "the links row is omitted entirely when neither link exists",
    )
  }

  @Test
  fun `the viewer renders no source link without a source href`() {
    // A local / unprovenanced session (or a preview with no recorded source) passes sourceHref
    // null.
    val preview = ServePreview(id = "plain.Button", label = "button", sourceFile = "src/main/A.kt")
    val html =
      ServeWeb.viewerPage(
        preview,
        token = "t",
        basePath = "/compose-m3",
        siblings = listOf(preview),
      )
    assertFalse(html.contains("class=\"cp-source\""), "no source link when no href is supplied")
  }

  @Test
  fun `the viewer carries history attributes when the catalog has delivery provenance`() {
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyManifestUrl =
          ServeUrls.historyManifestUrl("yschimke/compose-ai-tools", "compose-preview/main"),
        historyRepo = "yschimke/compose-ai-tools",
      )

    assertTrue(
      html.contains(
        "data-history-url=\"https://raw.githubusercontent.com/yschimke/compose-ai-tools/compose-preview/main/history.json\""
      ),
      "viewer must tell the client where the manifest lives",
    )
    assertTrue(html.contains("data-history-repo=\"yschimke/compose-ai-tools\""))
    assertTrue(
      html.contains("<cp-history-menu></cp-history-menu>"),
      "the timeline control is declared beside the other head toggles",
    )
  }

  @Test
  fun `the viewer omits history attributes without delivery provenance`() {
    // An uploaded bundle or local project has no branch to read history from. Emitting the
    // attributes anyway would ship a timeline that can only fail to load.
    val html = ServeWeb.viewerPage(checkbox[0], token = "t", basePath = "/compose-m3")

    assertFalse(html.contains("data-history-url"))
    assertFalse(html.contains("data-history-repo"))
  }

  @Test
  fun `a half-configured history is omitted rather than half-rendered`() {
    // A timeline the visitor cannot click through to an old render is worse than no timeline.
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyManifestUrl = "https://raw.githubusercontent.com/o/r/b/history.json",
        historyRepo = null,
      )

    assertFalse(html.contains("data-history-url"))
  }

  @Test
  fun `an inline history payload is embedded for offline rendering`() {
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyRepo = "o/r",
        historyInlineJson = """{"previews":{}}""",
      )

    assertTrue(html.contains("<script type=\"application/json\" id=\"cp-history-data\">"))
    assertTrue(html.contains("""{"previews":{}}"""))
  }

  @Test
  fun `an inline payload cannot close the script element early`() {
    // The only sequence that can break out of <script> is `</`. A payload carrying it verbatim
    // would end the element and spill the rest into the document as markup.
    val html =
      ServeWeb.viewerPage(
        checkbox[0],
        token = "t",
        basePath = "/compose-m3",
        historyRepo = "o/r",
        historyInlineJson = """{"x":"</script><img src=x onerror=alert(1)>"}""",
      )

    assertFalse(html.contains("</script><img"), "raw </script> must not survive into the page")
    assertTrue(html.contains("<\\/script>"), "it is escaped, not dropped")
  }

  @Test
  fun `no inline payload emits no data element`() {
    val html =
      ServeWeb.viewerPage(checkbox[0], token = "t", basePath = "/compose-m3", historyRepo = "o/r")

    assertFalse(html.contains("cp-history-data"))
  }

  @Test
  fun `failed renders are diagnostic cards grouped by their error`() {
    val failure =
      CatalogRenderFailure(
        id = "render-failed--button",
        componentId = "Button/Filled",
        preview = "com.example.ButtonPreview",
        phase = "render",
        errorClass = "java.lang.NoSuchMethodError",
        message = "MaterialTheme.colors()",
        stackTrace =
          "java.lang.NoSuchMethodError: MaterialTheme.colors()\n  at com.example.ButtonKt",
      )
    val previews =
      listOf(
        ServePreview(
          "render-failed--button",
          "Button",
          state = "disabled",
          renderFailure = failure,
        ),
        ServePreview(
          "render-failed--button-dark",
          "Button dark",
          props = jsonProps("locale" to "ar-XB"),
          renderFailure = failure,
        ),
      )

    val html = ServeWeb.landingPage("broken", previews, token = "t", basePath = "/broken")

    assertTrue(html.contains("cp-card--render-failed"))
    assertTrue(html.contains("2 failed renders"))
    assertTrue(html.contains("NoSuchMethodError: MaterialTheme.colors()"))
    assertTrue(html.contains("×2"), "identical failures are aggregated")
    assertTrue(html.contains("Stack trace"))
    assertFalse(html.contains("/render/render-failed--button.png"))
  }

  @Test
  fun `representative preview ignores render failures`() {
    val failure = CatalogRenderFailure(id = "failed", message = "boom")
    assertEquals(
      "working",
      ServeWeb.representativePreviewId(
        listOf(
          ServePreview("failed", "Failed", section = "Screens", renderFailure = failure),
          ServePreview("working", "Working"),
        )
      ),
    )
    assertEquals(
      null,
      ServeWeb.representativePreviewId(
        listOf(ServePreview("failed", "Failed", renderFailure = failure))
      ),
    )
  }

  /**
   * A design reference for [previewId], so the comparison page keeps that variant as its own row.
   */
  private fun referenceFor(previewId: String) =
    DesignReference(
      id = previewId,
      previewId = previewId,
      label = previewId,
      raster = DesignReferenceRaster("references/$previewId.png", 320, 160),
      source = DesignReferenceSource(provider = "figma"),
    )

  @Test
  fun `a component with a reference per state gets a row per state, each named and each selectable`() {
    // A design reference names one exact state/props mapping, so every referenced variant is kept
    // out of the landing grid's fold and gets a comparison row of its own. m3-catalog publishes
    // fourteen for `button-elevated` — and every one of them printed the bare component name, in no
    // stated order, so a page whose pairings are in fact correct reads as if the references were
    // mapped to the wrong renders.
    val previews =
      listOf("default", "hovered", "pressed").map { state ->
        ServePreview(
          id = "button-elevated__ideal__$state",
          label = "Button · Elevated · $state",
          state = state,
        )
      }
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
      )
    val labels = Regex("data-label=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(
      listOf("button-elevated", "button-elevated — Hovered", "button-elevated — Pressed"),
      labels,
      "each row names its variant; the plain default stays the bare component name: $html",
    )
    assertTrue(
      html.contains("<span class=\"cp-compare-variant\">Hovered</span>"),
      "the variant renders as its own line under the component name: $html",
    )
    // And each row answers for its own variant only. `previewIdsByCard` is keyed state- and
    // props-invariantly, so every row used to carry every sibling's id — filtering the page by one
    // variant's id matched all fourteen rows at once, which is no filter at all.
    val ids =
      Regex("data-preview-ids=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(
      listOf(
        "button-elevated__ideal__default",
        "button-elevated__ideal__hovered",
        "button-elevated__ideal__pressed",
      ),
      ids,
      "a variant that has a row of its own selects that row, not its siblings': $html",
    )
  }

  @Test
  fun `a folded-out variant still aliases onto exactly one row`() {
    // The alias exists for ids with NO row of their own — a variant folded out of the comparison
    // page has to select SOMETHING rather than land on an empty page. It goes to the first row of
    // its comparison card, not to every row of the component.
    val previews =
      listOf(
        ServePreview("button-elevated__ideal__default", "Default", state = "default"),
        ServePreview("button-elevated__ideal__pressed", "Pressed", state = "pressed"),
        ServePreview(
          "button-elevated__ideal__default__direction-rtl",
          "RTL",
          state = "default",
          props = jsonProps("direction" to "rtl"),
        ),
      )
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        // Only the two states are referenced, so the RTL render is folded out and has no row.
        referencesFor = { id -> if (id.endsWith("rtl")) emptyList() else listOf(referenceFor(id)) },
      )
    val ids =
      Regex("data-preview-ids=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(2, ids.size, "two referenced states, two rows: $html")
    // The row carries its OWN ids and a KEY into the page's alias table; the folded sibling is
    // written once, there. Only the row that claimed the card carries the key, which is what still
    // aliases the fold onto exactly one row.
    assertTrue(ids.none { it.contains("direction-rtl") }, "no fold on the row itself: $html")
    val keys =
      Regex("data-alias-card=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(
      listOf("button-elevated__ideal"),
      keys,
      "exactly one row claims the card whose fold it stands for: $html",
    )
    val table = html.substringAfter("id=\"cp-compare-aliases\">").substringBefore("</script>")
    assertTrue(
      table.contains("button-elevated__ideal__default__direction-rtl"),
      "and the folded id is published once, in the table: $table",
    )
    // `rowed` is what the wall subtracts, so the two states that have rows of their own do not
    // alias onto each other's.
    val rowed = table.substringAfter("\"rowed\":\"").substringBefore("\"")
    assertTrue(rowed.contains("button-elevated__ideal__default"), table)
    assertTrue(rowed.contains("button-elevated__ideal__pressed"), table)
    assertFalse(rowed.contains("direction-rtl"), "a folded id has no row: $table")
  }

  @Test
  fun `the comparison wall carries the catalog report the launcher's catalog half needs`() {
    // The floating launcher unhides its catalog choice only on a page carrying `#cp-report`, so
    // without one this page offered the SERVER tracker as its only route — which is how a
    // comparison that looked wrong got filed against the preview server (issue #4289).
    val preview = ServePreview(id = "button-elevated__ideal__default", label = "Button")
    val html =
      ServeWeb.comparisonPage(
        "wear-m3-catalog",
        listOf(preview),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
        reportIssue =
          ServeWeb.ReportIssue(
            action = "https://github.com/yschimke/wear-m3-catalog/issues/new",
            body = "### Which page",
            bodyTemplate = "### Which page",
            repo = "yschimke/wear-m3-catalog",
            subject = "these comparisons",
          ),
      )
    assertTrue(
      html.contains(
        "<details class=\"cp-report\" id=\"cp-report\"" +
          " data-cp-repo=\"yschimke/wear-m3-catalog\" data-cp-subject=\"these comparisons\">"
      ),
      "the launcher reads both the repo and what the report is about: $html",
    )
    // The wall names no single preview, so neither does the offer.
    assertTrue(html.contains("code declares these comparisons"), html)
    assertFalse(html.contains("code declares this preview"), html)
    // Wrapped in the viewer's provenance row: `.cp-report`'s panel is anchored to that row rather
    // than to its own toggle, which is what keeps it on screen at every width.
    assertTrue(html.contains("class=\"cp-preview-links cp-compare-links\""), html)
  }

  @Test
  fun `the viewer compares every variant of the component on its stage`() {
    // The viewer could put ONE baseline behind ONE variant. Asking "is this component wrong, or is
    // this STATE of it wrong?" meant leaving for a wall of the whole catalog and narrowing by hand
    // — from a page that already knew which component you were looking at.
    // See `docs/design/COMPARE_NAVIGATION.md`, F4 and §3.1.
    val html =
      ServeWeb.viewerPage(
        ServePreview("card__ideal__default__light", "Card", componentId = "Card"),
        token = "t",
        basePath = "/m3",
        catalogName = "Material 3",
        componentVariants =
          listOf(
            ServeWeb.ComponentVariant(
              previewId = "card__ideal__default__light",
              variant = "default · light",
              referenceId = "card-figma",
              matchPercent = 96.4,
            ),
            ServeWeb.ComponentVariant(
              previewId = "card__ideal__outlined__light",
              variant = "outlined",
              referenceId = "card-outlined-figma",
              matchPercent = 71.9,
            ),
            ServeWeb.ComponentVariant(
              previewId = "card__ideal__long__light",
              variant = "long text",
            ),
          ),
      )
    assertTrue(html.contains("id=\"cp-compare-strip\""), html)
    // Every variant, including the one on the stage — which is marked and deliberately NOT a link,
    // because a link that reloads the page you are on reads as a control that does nothing.
    assertTrue(html.contains("aria-current=\"true\""), html)
    assertFalse(html.contains("href=\"/m3/p/card__ideal__default__light?"), html)
    assertTrue(html.contains("href=\"/m3/p/card__ideal__outlined__light?"), html)
    // Baseline left, ours right — the same order the wall and the triptych read in.
    assertTrue(
      html.indexOf("/m3/reference/card-figma.png") <
        html.indexOf("/m3/render/card__ideal__default__light.png"),
      "the baseline picture leads the pair: $html",
    )
    // The published score, banded the way the design-spec chip bands it, so one number does not
    // change colour between the chip above the stage and the row below it.
    assertTrue(html.contains("data-spec-match=\"match\">96.4%"), html)
    assertTrue(html.contains("data-spec-match=\"off\">71.9%"), html)
    // A variant nothing in the design file is mapped to says so, rather than showing a score it
    // does not have or vanishing from a strip that claims to list every variant.
    assertTrue(html.contains("not scored"), html)
    // And the way out to the full instruments, scoped so it opens on this component.
    assertTrue(html.contains("/m3/compare?format=reference&amp;component=Card"), html)
  }

  @Test
  fun `the viewer draws no compare strip for a component with one unmapped variant`() {
    // Nothing to compare and nothing to navigate between. An empty panel under every one-off
    // preview is worse than no panel.
    val html =
      ServeWeb.viewerPage(
        ServePreview("card__ideal__default__light", "Card"),
        token = "t",
        componentVariants =
          listOf(
            ServeWeb.ComponentVariant(previewId = "card__ideal__default__light", variant = "")
          ),
      )
    assertFalse(html.contains("cp-compare-strip"), html)
    // …and a viewer told about no variants at all is byte-identical to one that never had the
    // feature, which is what keeps a plain module's page unchanged.
    val plain =
      ServeWeb.viewerPage(ServePreview("card__ideal__default__light", "Card"), token = "t")
    assertFalse(plain.contains("cp-compare-strip"), plain)
  }

  @Test
  fun `the viewer bar keeps its view controls in one group`() {
    // Loose in the bar, `SVG` / `3D` / `Transparent` / `Fit width` were sorted by nothing, and the
    // line each landed on depended on how wide the design-spec lane beside them happened to be —
    // a lane that grows when it is entered. Pressing the spec chip rearranged controls that have
    // nothing to do with it. See `docs/design/COMPARE_NAVIGATION.md`, F1.
    val html =
      ServeWeb.viewerPage(
        ServePreview("card__ideal__default__light", "Card"),
        token = "t",
        hasSvgExport = true,
      )
    assertTrue(html.contains("class=\"cp-view-group\""), html)
    val group = html.substringAfter("class=\"cp-view-group\"").substringBefore("</span></div>")
    for (control in listOf("cp-bg-btn cp-zoom-toggle", "Transparent")) {
      assertTrue(group.contains(control), "$control belongs to the view group: $html")
    }
  }

  @Test
  fun `the wall writes every folded preview id once, in one table`() {
    // Each row used to carry its comparison card's whole id list, and the haystack carried it a
    // second time. On `remote-m3` that was 19,188 mentions of 538 distinct ids — 967 KB of
    // `data-preview-ids` and most of another 1.08 MB of `data-hay`, on a 6.4 MB page that took two
    // minutes to arrive. See `docs/design/COMPARE_NAVIGATION.md`, F2.
    val previews =
      listOf(
        ServePreview("button__ideal__default", "Default", state = "default"),
        ServePreview("button__ideal__pressed", "Pressed", state = "pressed"),
      ) +
        (1..4).map {
          ServePreview(
            "button__ideal__default__variant-$it",
            "Variant $it",
            state = "default",
            props = jsonProps("variant" to "$it"),
          )
        }
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        referencesFor = { id ->
          if (id.contains("variant-")) emptyList() else listOf(referenceFor(id))
        },
      )
    // Every folded id — one with no row of its own — appears exactly once on the whole page.
    for (n in 1..4) {
      assertEquals(
        1,
        Regex(Regex.escape("button__ideal__default__variant-$n")).findAll(html).count(),
        "a folded id is written once: $html",
      )
    }
    // …and the haystack repeats none of it.
    val hay = Regex("data-hay=\"([^\"]*)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertTrue(hay.isNotEmpty(), html)
    assertTrue(hay.none { it.contains("__ideal__") }, "no ids in the haystack: $hay")
  }

  @Test
  fun `the alias table names both facts, because the two walls apply different rules`() {
    // `rowed` is what the comparison wall subtracts: a design reference names one exact
    // state/props mapping, so that variant gets a row of its own and must not also alias onto its
    // siblings' rows. The Remote Compose lane wall, whose rows are one per preview, does not
    // subtract it. Publishing both facts once and letting each caller pick is what keeps the
    // browser from re-deriving a rule with one right answer.
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(
          ServePreview("button__ideal__default", "Default", state = "default"),
          ServePreview(
            "button__ideal__default__rtl",
            "RTL",
            state = "default",
            props = jsonProps("direction" to "rtl"),
          ),
        ),
        token = "t",
        referencesFor = { id -> if (id.endsWith("rtl")) emptyList() else listOf(referenceFor(id)) },
      )
    val table = html.substringAfter("id=\"cp-compare-aliases\">").substringBefore("</script>")
    assertTrue(table.contains("\"cards\":{"), table)
    assertTrue(table.contains("\"rowed\":"), table)
    // Nothing in it can close the element early — the one way a JSON island becomes an injection.
    assertFalse(table.contains("</"), table)
  }

  @Test
  fun `the baseline leads the pair on every lane`() {
    // ONE order, every lane: baseline · diff · ours. The viewer's spec lane says it three ways
    // already (the Spec / Diff / Render triptych, the wipe's seam, the focused Reference / Diff /
    // Actual page). The wall's `svg` and `rc` lanes used to read the other way round, so pressing
    // a baseline button swapped both pictures' sides as well as relabelling both headers — the one
    // control that changes the question also moved the answer.
    // See `docs/design/COMPARE_NAVIGATION.md`, F3.
    val reference =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(ServePreview(id = "button", label = "Button")),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
      )
    val svg =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(ServePreview(id = "button", label = "Button")),
        token = "t",
        hasSvgFor = { true },
      )
    for ((lane, html) in listOf("reference" to reference, "svg" to svg)) {
      assertTrue(
        html.indexOf("cp-compare-target-cell") < html.indexOf("cp-compare-render-cell"),
        "the baseline's cell comes first on the $lane lane: $html",
      )
      assertTrue(
        html.indexOf("cp-compare-target-head") < html.indexOf("cp-compare-render-head"),
        "and its header moves with it on the $lane lane: $html",
      )
    }
    // Named for the lane it is showing, not the constant `SVG` this head used to be — a header
    // reading `SVG` over the Figma column would state the pair backwards.
    assertTrue(reference.contains(">Figma</span>"), reference)
    assertTrue(svg.contains(">SVG</span>"), svg)
    // The button that enters the lane names the pair in the order the columns stand.
    assertTrue(reference.contains(">Figma ↔ PNG</button>"), reference)
  }

  @Test
  fun `the render column is named after the catalog, and each column says which half it is`() {
    // "Rendered PNG" named a FILE FORMAT where the reader wanted to know whose picture this is,
    // and it was the odd one out in a row whose other header is a design tool's name.
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(ServePreview(id = "button", label = "Button")),
        token = "t",
        displayTitle = "Material 3",
        referencesFor = { listOf(referenceFor(it)) },
      )
    assertFalse(html.contains("Rendered PNG"), html)
    assertTrue(
      html.contains(
        "<th class=\"cp-compare-render-head\"><span class=\"cp-compare-head-name\">" +
          "Material 3</span><span class=\"cp-compare-head-role\">ours</span></th>"
      ),
      html,
    )
    assertTrue(html.contains("<span class=\"cp-compare-head-role\">baseline</span>"), html)
  }

  @Test
  fun `each picture cell carries an empty caption for its own pixel size`() {
    // The two panels are one fixed frame each now, so a baseline exported at a different scale no
    // longer looks bigger than the render. `<cp-compare-wall>` fills these from the DECODED raster
    // — the wall chooses which theme variant is on screen, so a size printed here by the server
    // would be describing a picture the reader may not be looking at.
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(ServePreview(id = "button", label = "Button")),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
      )
    assertTrue(html.contains("<span class=\"cp-compare-dim\" data-dim-for=\"png\"></span>"), html)
    assertTrue(
      html.contains("<span class=\"cp-compare-dim\" data-dim-for=\"target\"></span>"),
      html,
    )
  }

  @Test
  fun `a comparison wall with no catalog to file against renders no report affordance`() {
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(ServePreview(id = "button", label = "Button")),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
      )
    assertFalse(html.contains("id=\"cp-report\""), html)
    assertFalse(html.contains("cp-compare-links"), html)
  }

  /** [referenceFor], carrying the score the delivery branch bakes in at publish time. */
  private fun scoredReferenceFor(previewId: String, percent: Double): DesignReference =
    referenceFor(previewId)
      .copy(
        match =
          DesignReferenceMatch(
            percent = percent,
            scoreVersion = ServeDesignReferenceStore.SCORE_VERSION,
          )
      )

  @Test
  fun `the reference wall is served worst first, on the scores the branch already measured`() {
    // The order is the wall's whole argument, and it used to exist only AFTER the browser had
    // decoded and scored two rasters per row — tens of seconds of catalog order on a real catalog,
    // which is the one order that says nothing about which pair is wrong (issue #4624).
    val previews =
      listOf("good" to 98.5, "awful" to 41.0, "middling" to 84.25).map { (id, _) ->
        ServePreview(id = id, label = id)
      }
    val scores = mapOf("good" to 98.5, "awful" to 41.0, "middling" to 84.25)
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        referencesFor = { id -> listOf(scoredReferenceFor(id, scores.getValue(id))) },
      )
    val labels = Regex("data-label=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(listOf("awful", "middling", "good"), labels, "worst first: $html")
    // And the numbers themselves ride along per variant, so `<cp-compare-wall>` can seed the score
    // cell — and re-seed it from the OTHER theme's number when the visitor switches.
    assertTrue(html.contains("data-match-neutral=\"41.00\""), html)
  }

  @Test
  fun `a pair the branch never scored trails the ones it did`() {
    // "Nobody has measured this yet" is not a finding. A catalog baked before the score producer
    // existed carries none at all, and leading with them would serve its whole table under a banner
    // of rows claiming to be the worst.
    val previews = listOf("unscored", "scored").map { ServePreview(id = it, label = it) }
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        referencesFor = { id ->
          if (id == "scored") listOf(scoredReferenceFor(id, 30.0)) else listOf(referenceFor(id))
        },
      )
    val labels = Regex("data-label=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(listOf("scored", "unscored"), labels, "a 30% pair still outranks silence: $html")
  }

  @Test
  fun `the vector lanes keep catalog order rather than borrowing the design lane's`() {
    // `svg` and `rc` publish no score of their own, and re-ordering their rows by a number about a
    // different comparison is worse than the order the catalog chose.
    //
    // A catalog with NO references, because that is the only one the vector lane is served as the
    // default for now (see the lane-order test below). The gate in `orderedCards` is what keeps the
    // rule true for the pages that still reach it.
    val previews = listOf("alpha", "beta").map { ServePreview(id = it, label = it) }
    val html = ServeWeb.comparisonPage("m3-catalog", previews, token = "t", hasSvgFor = { true })
    val labels = Regex("data-label=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
    assertEquals(listOf("alpha", "beta"), labels, html)
  }

  @Test
  fun `the wall opens on a raster pair, not on the SVG lane`() {
    // `svg` led because it was the first lane this page had. It is the slowest pair to put on
    // screen — a vector document laid out and rasterised per row, against a PNG the decoder hands
    // back whole — and the only one that can be wrong through no fault of the renderer: an SVG
    // resolves its own typefaces at paint time, so a face the visitor's browser cannot get draws
    // TOFU, on the page whose whole job is to say what looks wrong.
    // See `docs/design/COMPARE_NAVIGATION.md`, §3.2.
    val previews = listOf("alpha", "beta").map { ServePreview(id = it, label = it) }
    val withReference =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        hasSvgFor = { true },
        referencesFor = { listOf(referenceFor(it)) },
      )
    assertTrue(withReference.contains("data-default-format=\"reference\""), withReference)

    // …and with a reference lane to lead with, the served order is worst-first, which the vector
    // default used to withhold from exactly the catalogs that publish scores.
    val scored =
      ServeWeb.comparisonPage(
        "m3-catalog",
        previews,
        token = "t",
        hasSvgFor = { true },
        referencesFor = { id -> listOf(scoredReferenceFor(id, if (id == "alpha") 99.0 else 12.0)) },
      )
    val labels = Regex("data-label=\"([^\"]+)\"").findAll(scored).map { it.groupValues[1] }.toList()
    assertEquals(listOf("beta", "alpha"), labels, scored)

    // A catalog with only an SVG export still opens on it — the order is a preference among the
    // lanes a catalog HAS, not a refusal to serve the one it has.
    val svgOnly = ServeWeb.comparisonPage("m3-catalog", previews, token = "t", hasSvgFor = { true })
    assertTrue(svgOnly.contains("data-default-format=\"svg\""), svgOnly)
  }

  @Test
  fun `the Bugs column names what is already filed, and offers a route to file more`() {
    val preview = ServePreview(id = "button", label = "Button", componentId = "Button/Filled")
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(preview),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
        parityIssuesGeneratedAt = "2026-09-05T20:08:06.488Z",
        parityIssues =
          listOf(
            ParityIssue(
              repository = "yschimke/m3-catalog",
              number = 41,
              title = "Verified after the token update",
              url = "https://github.com/yschimke/m3-catalog/issues/41",
              state = "closed",
              component = "Button/Filled",
            ),
            ParityIssue(
              repository = "yschimke/m3-catalog",
              number = 40,
              title = "Glyph colour is darker than the design token",
              url = "https://github.com/yschimke/m3-catalog/issues/40",
              state = "open",
              parity = "known-difference",
              previewIds = listOf("button"),
            ),
            ParityIssue(
              repository = "yschimke/m3-catalog",
              number = 39,
              title = "Large variant only",
              url = "https://github.com/yschimke/m3-catalog/issues/39",
              state = "open",
              component = "Button/Filled",
              scope = "variant",
              previewIds = listOf("button__ideal__large"),
            ),
          ),
      )
    assertTrue(html.contains("<th class=\"cp-compare-bugs-head\">Bugs</th>"), html)
    val cell = html.substringAfter("class=\"cp-compare-bugs\"").substringBefore("</td>")
    val summary = cell.substringAfter("cp-compare-bug-summary").substringBefore("</summary>")
    // Collapsed, the cell is the OPEN numbers and nothing else — the line a reader scanning the
    // wall for "does anyone know about this?" reads without opening anything.
    assertTrue(
      summary.contains(
        "<span class=\"cp-compare-bug-chip\" data-bug-scope=\"component\">#40</span>"
      ),
      cell,
    )
    assertFalse(
      summary.contains("#41"),
      "a closed report does not spend a number on the collapsed line: $cell",
    )
    // …but it is not silent about the closed one either: a row where everything filed is closed
    // must not read like a row nobody has ever looked at. No count on it — see the marker's note.
    assertTrue(summary.contains("cp-compare-bug-chip--closed\">closed</span>"), cell)
    // Open before closed in the panel: the column is read for "does someone already know?", and a
    // closed report answers that more weakly than an open one.
    assertTrue(cell.indexOf(">#40<") < cell.indexOf(">#41<"), cell)
    assertTrue(cell.contains("cp-compare-bug-item--closed"), "the closed one says so: $cell")
    assertTrue(cell.contains("<span class=\"cp-compare-bug-state\">closed</span>"), cell)
    // Matched on the component as well as on the preview id — an issue may name either.
    assertTrue(cell.contains("/issues/41"), cell)
    assertFalse(cell.contains("/issues/39"), "an exact-variant issue must not broaden: $cell")
    // Opened, the panel says what each issue IS, which is the fact the collapsed number cannot
    // carry and the reason the disclosure exists at all.
    assertTrue(
      cell.contains(
        "<span class=\"cp-compare-bug-title\" title=\"Glyph colour is darker than the design " +
          "token\">Glyph colour is darker than the design token</span>"
      ),
      cell,
    )
    // The reporter's classification rides along, since "known-difference" and "nobody has verified
    // this" are different answers to the question the panel was opened to settle.
    assertTrue(cell.contains("<span class=\"cp-compare-bug-tag\">known-difference</span>"), cell)
    // "+ file" is offered on every row, including rows with nothing filed: an unfiled bad score is
    // exactly what a reader is scanning this wall for. It lands on the focused comparison, which
    // files a report naming that exact preview AND reference. OUTSIDE the disclosure, because a row
    // with nothing filed has no disclosure to put it in.
    assertTrue(cell.substringAfter("</details>").contains("cp-compare-bug-new"), cell)
    // The panel closes by saying what it is as of. Nothing here re-checks GitHub, so a `closed`
    // with no date would invite more trust than a render-time snapshot can carry.
    assertTrue(
      cell.contains("<p class=\"cp-compare-bug-asof\">index as of 2026-09-05 20:08 UTC</p>"),
      cell,
    )
    assertTrue(cell.contains("/compare/button?token=t&amp;reference=button"), cell)
    // The numbers join the haystack, so `#40` narrows the wall to the rows a report names — and so
    // do the titles, because the pill now shows them and a filter has to match what the reader can
    // see.
    // The haystack is the row's LABEL and the issues filed against it. The preview ids used to be
    // copied in here as well — the same list the row already carried in `data-preview-ids`, and
    // the single biggest thing on a real wall. They are written once in the page's alias table
    // now, and `keepRow` matches a typed id against the resolved list.
    // See `docs/design/COMPARE_NAVIGATION.md`, F2.
    assertTrue(
      html.contains(
        "data-hay=\"button #40 glyph colour is darker than the design token " +
          "#41 verified after the token update\""
      ),
      html,
    )
  }

  @Test
  fun `the Bugs column serializes an inactive theme's exact issue but keeps it hidden`() {
    val light =
      ServePreview(
        id = "button__ideal__default__light",
        label = "Button",
        componentId = "Button/Filled",
        state = "default",
        theme = "light",
      )
    val dark = light.copy(id = "button__ideal__default__dark", theme = "dark")
    fun issue(number: Int, scope: String) =
      ParityIssue(
        repository = "yschimke/m3-catalog",
        number = number,
        title = "Dark preview issue",
        url = "https://github.com/yschimke/m3-catalog/issues/$number",
        state = "open",
        component = "Button/Filled",
        scope = scope,
        previewIds = listOf(dark.id),
      )
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(light, dark),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
        parityIssues = listOf(issue(42, "component"), issue(43, "variant")),
      )
    val cell = html.substringAfter("class=\"cp-compare-bugs\"").substringBefore("</td>")
    assertTrue(cell.contains("/issues/42"), "component scope crosses the theme pair: $cell")
    // The contract rides on BOTH halves — the collapsed number and its panel entry — because they
    // are one claim about one preview and `CompareWall` hides them together.
    assertTrue(
      cell.contains(
        "<span class=\"cp-compare-bug-chip\" data-bug-scope=\"variant\" " +
          "data-bug-preview-ids=\"${dark.id}\" hidden>#43</span>"
      ),
      "the dark issue is serialized for the browser's theme switch but hidden at light: $cell",
    )
    assertTrue(
      cell.contains(
        "<li class=\"cp-compare-bug-item\" data-bug-scope=\"variant\" " +
          "data-bug-preview-ids=\"${dark.id}\" hidden>"
      ),
      "…and so is the panel entry it belongs to: $cell",
    )
  }

  @Test
  fun `the wall offers a picker per row, and the facts a browser needs to write its locator`() {
    val preview =
      ServePreview(
        id = "button__ideal__default__light",
        label = "Button",
        componentId = "Button/Filled",
      )
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(preview),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
        reportIssue =
          ServeWeb.ReportIssue(
            action = "https://github.com/o/r/issues/new",
            body = "### Which page",
            bodyTemplate = "### Which page\n${ServeIssueReport.LOCATORS_PLACEHOLDER}\n",
            repo = "o/r",
            subject = "these comparisons",
            locatorSystem = "m3-catalog",
            locatorRevision = "o/r@design-artifacts/m3-catalog",
          ),
      )
    // The two page-level halves of a locator, which no row can know. Their presence is also the
    // switch: `<cp-compare-wall>` shows the row checkboxes only where it can turn a tick into a
    // block, and a wall with no report to file leaves them hidden.
    assertTrue(html.contains("data-cp-locator-system=\"m3-catalog\""), html)
    assertTrue(
      html.contains("data-cp-locator-revision=\"o/r@design-artifacts/m3-catalog\""),
      html,
    )
    // The row's own half: the component identity the locator names, taken from the catalog rather
    // than re-derived in the browser from the preview id.
    assertTrue(html.contains("data-component-id=\"Button/Filled\""), html)
    // The picker itself, beside the row's name — server-rendered on every row and hidden by CSS
    // until the wall marks itself pickable, because a tick means nothing without the script.
    assertTrue(html.contains("<input type=\"checkbox\" class=\"cp-compare-pick-input\""), html)
    assertTrue(html.contains("id=\"cp-compare-picked\""), html)
  }

  @Test
  fun `a page report names no locator until a page can pick one`() {
    // The placeholder is a line the browser either fills or deletes. On a page with nothing to fill
    // it — every page-scoped report but the wall's — it would be filed verbatim, so it is not
    // written at all, and the body a visitor with JS off files is unchanged either way.
    val context = ServeIssueReport.Context(repo = "yschimke/m3-catalog", system = "m3-catalog")
    val plain = ServeIssueReport.body(context, renderPlaceholder = true)
    assertFalse(plain.contains(ServeIssueReport.LOCATORS_PLACEHOLDER), plain)
    val pickable =
      ServeIssueReport.body(context, renderPlaceholder = true, locatorsPlaceholder = true)
    assertTrue(pickable.contains("\n${ServeIssueReport.LOCATORS_PLACEHOLDER}\n"), pickable)
    // Deleting the placeholder LINE has to reproduce the other body byte for byte — that is what a
    // report filed with nothing ticked must be, and what a visitor with no script files.
    assertEquals(plain, pickable.replace("${ServeIssueReport.LOCATORS_PLACEHOLDER}\n", ""))
  }

  @Test
  fun `a catalog with no published issue index carries no Bugs column at all`() {
    // With nothing to join, the column would be a row of bare "+ file" links — a route every
    // reference row already has by opening its focused comparison.
    val html =
      ServeWeb.comparisonPage(
        "m3-catalog",
        listOf(ServePreview(id = "button", label = "Button")),
        token = "t",
        referencesFor = { listOf(referenceFor(it)) },
      )
    assertFalse(html.contains("cp-compare-bugs"), html)
  }
}
