package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.layoutinspector.ExplodedSvg
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden generator and drift guard for the exploded 3D view's rendering (`ExplodedSvgTest` covers
 * structure):
 * 1. [LAYERED] — a committed layered SVG shaped like a real `compose/figma-svg` export.
 * 2. This test runs the production [ExplodedSvg] over it and commits [EXPLODED].
 * 3. `pages-snapshot.spec.mjs` serves [EXPLODED] as the `?exploded=1` stub, so the
 *    `serve-viewer-exploded` screenshot shows the real drawing.
 *
 * Committing step 2 means camera, split, outline or label changes show as a text diff and as moved
 * pixels. Regenerate with (same env var as [ServeWebFixtureTest], same fixtures directory):
 * ```
 * UPDATE_SERVE_WEB_FIXTURES=true ./gradlew :cli:test --tests '*ExplodedSvgFixtureTest*'
 * ```
 */
class ExplodedSvgFixtureTest {

  private companion object {
    const val PAGES = "preview-harness/fixtures/pages"
    const val LAYERED = "_render-placeholder-layered.svg"
    const val EXPLODED = "_render-placeholder-exploded.svg"
    const val EVIDENCE = "renders/exploded-view"
  }

  @Test
  fun `the exploded placeholder is in sync with the production renderer`() {
    val pages = File(repoRoot(), PAGES)
    val layered = File(pages, LAYERED)
    assertTrue(layered.isFile, "missing $LAYERED — the exploded lane's committed input")
    val exploded = File(pages, EXPLODED)

    val rendered = ExplodedSvg.render(layered.readText()) + "\n"
    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"
    if (update) {
      exploded.writeText(rendered)
      return
    }
    assertTrue(
      exploded.isFile,
      "missing $EXPLODED — regenerate with UPDATE_SERVE_WEB_FIXTURES=true",
    )
    assertEquals(
      exploded.readText(),
      rendered,
      "$EXPLODED is stale. If the exploded view changed on purpose, regenerate with " +
        "UPDATE_SERVE_WEB_FIXTURES=true and review the harness screenshot that moves with it.",
    )
  }

  /**
   * A second golden from real data: `renders/material-icon-refs/`'s `compose-figma.svg`, a genuine
   * export with hoisted `<defs>` geometry, `ReusableComposeNode` layer ids, and a wide 160×48 strip
   * rather than a tall screen.
   */
  @Test
  fun `the real material-icon export explodes, and its committed picture is in sync`() {
    val source = File(repoRoot(), "renders/material-icon-refs/compose-figma.svg")
    assertTrue(source.isFile, "missing the committed material-icon export")
    val out = File(repoRoot(), "$EVIDENCE/material-icon-row.exploded.svg")

    val rendered = ExplodedSvg.render(source.readText()) + "\n"
    // The hoisted icon `<defs>` must ride along exactly once and still resolve: the placements are
    // `<use href="#material-icon-…">`, so losing the defs would silently blank every icon.
    assertTrue(rendered.contains("id=\"material-icon-materialicons-menu\""), "kept the icon defs")
    assertTrue(rendered.contains("href=\"#material-icon-materialicons-menu\""), "kept the uses")
    // `ReusableComposeNode` names no composable, so the label falls back to the icon annotation.
    assertTrue(rendered.contains("menu icon"), "labels see through the fallback layer id")

    val update =
      System.getenv("UPDATE_SERVE_WEB_FIXTURES") == "true" ||
        System.getProperty("updateServeWebFixtures") == "true"
    if (update) {
      out.parentFile.mkdirs()
      out.writeText(rendered)
      return
    }
    assertTrue(out.isFile, "missing ${out.name} — regenerate with UPDATE_SERVE_WEB_FIXTURES=true")
    assertEquals(out.readText(), rendered, "${out.name} is stale — see $EVIDENCE/README.md")
  }

  /**
   * The server's default for a bare `?exploded=1` must match this golden's; they live in different
   * modules.
   */
  @Test
  fun `a bare exploded request uses the same options as the golden`() {
    val none = { _: String -> null }
    assertEquals(ExplodedSvg.Options(), ServeExplodedSvg.optionsFrom(none))
  }

  /**
   * The drawer's slider defaults must equal `ExplodedSvg`'s, so the viewer can omit untouched axes;
   * they are HTML in one module and Kotlin in another.
   */
  @Test
  fun `the drawer sliders default to the renderer's own camera`() {
    val page =
      ServeWeb.viewerPage(
        ServePreview("com.example.ProfileCardPreview", "Profile card"),
        "t",
        hasSvgExport = true,
      )
    val defaults = ExplodedSvg.Options()
    fun sliderDefault(id: String): String =
      Regex("id=\"cp-explode-$id\"[\\s\\S]*?data-cp-default=\"([^\"]*)\"")
        .find(page)
        ?.groupValues
        ?.get(1) ?: error("no cp-explode-$id slider in the viewer")
    assertEquals(defaults.tiltDeg, sliderDefault("tilt").toDouble())
    assertEquals(defaults.spinDeg, sliderDefault("spin").toDouble())
    assertEquals(defaults.maxDepth, sliderDefault("depth").toInt())
    // Separation has no fixed default — 0 on the slider means "derive one from the preview's size",
    // which is what a null `gap` asks the renderer for.
    assertEquals(0.0, sliderDefault("gap").toDouble())
    assertEquals(null, defaults.gap)
  }

  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root from ${System.getProperty("user.dir")}")
  }
}
