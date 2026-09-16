package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live lane makes its canvas focusable so keystrokes reach the running composable, which used
 * to opt it into the site-wide `[tabindex]:focus-visible` ring — a page-chrome outline drawn around
 * the render itself. Focus indication inside the stage is the rendered UI's job, so the ring is
 * suppressed here while the tab stop stays.
 */
class ServeLiveCanvasFocusRingTest {

  private val css = ServeWebAssets.load("serve.css")!!.bytes.decodeToString()

  @Test
  fun `the shared focus ring still covers everything focusable on the page`() {
    assertTrue(
      css.contains(
        ":where(a, button, summary, input, select, textarea, [tabindex]):focus-visible {\n" +
          "  outline: 3px solid var(--md-sys-color-secondary);"
      ),
      "the site-wide M3 focus indicator is intact",
    )
  }

  @Test
  fun `the live canvas draws no focus ring of its own`() {
    assertTrue(
      css.contains(
        ".cp-stage canvas#cp-canvas:focus, .cp-stage canvas#cp-canvas:focus-visible { outline: none; }"
      ),
      "the live render pane suppresses the page's focus ring",
    )
  }
}
