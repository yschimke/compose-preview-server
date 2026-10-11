package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end check that the viewer offers the per-preview "report an issue" form through the real
 * route, since `ServeWebFixtureTest` calls [ServeWeb.viewerPage] directly and cannot see whether
 * the handler wires it. Body shape is [ServeIssueReportTest]'s; this checks the form is emitted,
 * targets the repo owning the Kotlin, carries the overrides and design reference, and omits the
 * parity score.
 */
class ServeViewerIssueReportRouteTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(
    label: String,
    previewIds: List<String>,
    /**
     * Whether this host can answer a `?at=<sha>` pin (needs a delivery-branch read seam). Off by
     * default so other cases don't get the revision chrome.
     */
    pinnable: Boolean = false,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("viewer-report-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    File(dir, "previews/variants.json")
      .writeText(
        previewIds.joinToString(prefix = "{", postfix = "}") { id ->
          "\"$id\":{\"componentId\":\"Button/Filled\"}"
        }
      )
    // A design reference for the first preview, so the focused comparison is a real pair.
    File(dir, "references").apply { mkdirs() }
    File(dir, "references/button.png").writeBytes(png())
    File(dir, "references/index.json")
      .writeText(
        """
        {"schema":"compose-preview-references/v1","references":[{
           "id":"button-figma","previewId":"${previewIds.first()}","label":"Figma button",
           "raster":{"path":"references/button.png","width":2,"height":2},
           "source":{"provider":"figma"}}]}
        """
          .trimIndent()
      )
    File(dir, "parity").mkdirs()
    File(dir, "parity/issues.json")
      .writeText(
        """
        {"schema":"compose-preview-issues/v1","issues":[
          {"repository":"example/design-catalog","number":902,
           "title":"Only the large sibling","url":"https://github.com/example/design-catalog/issues/902",
           "state":"open","component":"Button/Filled","scope":"variant",
           "previewIds":["button-large"]}
        ]}
        """
          .trimIndent()
      )
    return ServeBundleHost(
      dir,
      label = label,
      title = "Compose Material 3",
      // Source and delivery live in different repos so the assertions can tell the source repo from
      // the delivery repo and [ServeIssueReport.FALLBACK_REPO].
      catalogSource =
        ServeWeb.CatalogSource(
          repo = "example/design-catalog",
          ref = "main",
          module = "samples/design-catalog-compose-m3",
        ),
      provenance =
        ServeWeb.CatalogProvenance(
          repo = "yschimke/compose-ai-tools",
          branch = "design-artifacts/compose-m3",
          toolVersion = "0.16.54",
        ),
      declaredBaked = previewIds,
      bakedBranchPaths =
        if (pinnable) previewIds.associateWith { "images/$it.png" } else emptyMap(),
      fetchPinnedAssetOutcome = if (pinnable) ({ _, _ -> BranchFetch.Ok(png()) }) else null,
    )
  }

  private val registry = ServeSessionRegistry(open = { null })

  private fun newServer(pinnable: Boolean = false): ServeHttpServer {
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled"), pinnable = pinnable),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = registry,
        defaultSessionId = "compose-m3",
        isPublic = true,
        catalogSessions = listOf("compose-m3"),
      )
      .also { it.start() }
  }

  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  private fun get(path: String): Pair<Int, String> {
    val url = "http://127.0.0.1:${server!!.port}$path"
    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
      return r.code to r.body.string()
    }
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  /**
   * The prefilled issue body from the page's hidden input, unescaped. Asserting on the body rather
   * than the page, because the page itself shows the same URLs.
   */
  private fun reportBody(html: String): String =
    html
      .substringAfter("id=\"cp-report-body\"")
      .substringAfter("value=\"")
      .substringBefore("\"")
      // `&amp;` last: the entities below expand to text that must not be re-read as an entity.
      .replace("&quot;", "\"")
      .replace("&#39;", "'")
      .replace("&amp;", "&")

  @Test
  fun `the viewer offers a report filed against the repo that owns the preview`() {
    server = newServer()
    val (code, body) = get("/compose-m3/p/button-filled")
    assertEquals(200, code)
    // Named for its tracker, to distinguish it from the footer's server bug report.
    assertTrue(body.contains("report a catalog issue"), body)
    assertTrue(
      body.contains("action=\"https://github.com/example/design-catalog/issues/new\""),
      body,
    )
    // Not the delivery repo, and not the fallback — a preview bug goes where the Kotlin lives.
    assertFalse(
      body.contains("action=\"https://github.com/yschimke/compose-ai-tools/issues/new\""),
      body,
    )
  }

  @Test
  fun `the report names the preview and the catalog build it came from`() {
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    // The preview's identity is a row of the body's "Which preview" table, not a server-written
    // title — the title is the reporter's to write.
    assertTrue(body.contains("| Preview | `button-filled` |"), body)
    assertTrue(body.contains("design-artifacts/compose-m3"), body)
    assertTrue(body.contains("compose-ai-tools 0.16.54"), body)
  }

  @Test
  fun `the served page asks the reporter for a title and will not take a blank one`() {
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    assertTrue(
      body.contains(
        "<input class=\"cp-report-summary-input\" type=\"text\" name=\"title\" required"
      ),
      body,
    )
    assertFalse(body.contains("type=\"hidden\" name=\"title\""), body)
  }

  @Test
  fun `the report carries the overrides in force, not the preview's defaults`() {
    // The whole point of reporting from the viewer: the bug is usually in what the knobs produced.
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled?uiMode=dark")
    assertTrue(body.contains("/compose-m3/render/button-filled.png?uiMode=dark"), body)
  }

  @Test
  fun `a report filed from the comparison names both panels, not just the render`() {
    // A comparison report carries both the reference and the render — here as links, since a
    // loopback host is not reachable by GitHub's camo proxy ([ServeIssueReport.isEmbeddable]).
    server = newServer()
    val (code, html) = get("/compose-m3/compare/button-filled")
    assertEquals(200, code)
    val body = reportBody(html)
    assertTrue(body.contains("[PNG at these settings](http://127.0.0.1:"), body)
    assertTrue(body.contains("/compose-m3/render/button-filled.png"), body)
    assertTrue(body.contains("[Design reference PNG](http://127.0.0.1:"), body)
    assertTrue(body.contains("/compose-m3/reference/button-figma.png"), body)
  }

  @Test
  fun `the comparison's two panels are scoped to one publish, as the page's own frames are`() {
    // Both halves take the same suffix or the report is a comparison across two states of the
    // catalog — the coupling `assetQuery` gives the panels on screen, kept for the report.
    server = newServer()
    val body = reportBody(get("/compose-m3/compare/button-filled?uiMode=dark").second)
    assertTrue(body.contains("/compose-m3/render/button-filled.png?uiMode=dark"), body)
    assertTrue(body.contains("/compose-m3/reference/button-figma.png?uiMode=dark"), body)
  }

  @Test
  fun `the viewer's report stays a single render even where the catalog has a reference`() {
    // The viewer's plain lane has nothing beside the render, and its spec lane keeps the picked
    // source in the DOM rather than the URL — so this page's report must not assert a pair.
    server = newServer()
    val body = reportBody(get("/compose-m3/p/button-filled").second)
    assertFalse(body.contains("Design reference PNG"), body)
    assertFalse(body.contains("| Design reference | Render |"), body)
  }

  @Test
  fun `a report filed from the viewer carries a parity locator`() {
    // `parity/issues.json` is built from this fence, so a viewer report without one would be
    // labelled `parity:` yet missing from the index. The reference resolves as the comparison link
    // beside it does.
    server = newServer()
    val body = reportBody(get("/compose-m3/p/button-filled").second)
    assertTrue(body.contains("```${ServeIssueReport.LOCATOR_FENCE}"), body)
    assertTrue(body.contains("system: compose-m3"), body)
    assertTrue(body.contains("component: Button/Filled"), body)
    assertTrue(body.contains("preview: button-filled"), body)
    assertTrue(body.contains("reference: button-figma"), body)
    assertTrue(body.contains("overrides: {}"), body)
  }

  @Test
  fun `the viewer's served body states the overrides it was served at`() {
    // What a visitor with scripting off files. The placeholder below is for the live case; this
    // one has to be a real, parseable locator on its own.
    server = newServer()
    val body = reportBody(get("/compose-m3/p/button-filled?uiMode=dark").second)
    assertTrue(body.contains("overrides: {\"uiMode\":\"dark\"}"), body)
    assertFalse(body.contains(ServeIssueReport.OVERRIDES_PLACEHOLDER), body)
  }

  @Test
  fun `an accepted baked fallback records only the overrides the pixels could have used`() {
    // With `?fallback=baked` the render applies none of the dropped overrides, so the locator must
    // not claim them either; this is the body a scripting-off visitor files, so nothing corrects it
    // later.
    server = newServer()
    val body = reportBody(get("/compose-m3/p/button-filled?uiMode=dark&fallback=baked").second)
    assertTrue(body.contains("```${ServeIssueReport.LOCATOR_FENCE}"), body)
    assertTrue(body.contains("overrides: {}"), body)
    assertFalse(body.contains("\"uiMode\""), body)
  }

  @Test
  fun `the viewer's template leaves the overrides for its own script to fill`() {
    // The controls re-render in place, so the template hands the overrides to the page beside
    // `{{render}}`, filled on one pass so identity and pixels name one frame.
    server = newServer()
    val (_, html) = get("/compose-m3/p/button-filled")
    val template =
      html.substringAfter("data-report-template=\"").substringBefore("\"").replace("&amp;", "&")
    assertTrue(template.contains("overrides: ${ServeIssueReport.OVERRIDES_PLACEHOLDER}"), template)
    assertTrue(template.contains(ServeIssueReport.RENDER_PLACEHOLDER), template)
  }

  @Test
  fun `a pinned viewer files no locator, because its identity would describe another frame`() {
    // `?at=<sha>` shows a historical artifact while the reference mapping and `revision:` describe
    // the catalog today, so a locator would index the wrong comparison. No row beats a wrong one.
    server = newServer(pinnable = true)
    val (code, html) =
      get("/compose-m3/p/button-filled?at=0123456789abcdef0123456789abcdef01234567")
    assertEquals(200, code)
    val body = reportBody(html)
    assertTrue(body.contains("cp-report-body") || body.isNotEmpty(), body)
    assertFalse(body.contains(ServeIssueReport.LOCATOR_FENCE), body)
    assertFalse(html.contains(ServeIssueReport.OVERRIDES_PLACEHOLDER), html)
    // …and the same server, unpinned, still does file one — so this proves the gate rather than a
    // host that could never emit a locator anyway.
    assertTrue(
      reportBody(get("/compose-m3/p/button-filled").second)
        .contains("```${ServeIssueReport.LOCATOR_FENCE}"),
      "the unpinned page on the same host still carries a locator",
    )
  }

  @Test
  fun `the parity score stays on the comparison, which is the page that measures one`() {
    // The viewer's number is a render-fidelity score against generated SVG, not the design
    // reference, so a `Raw comparison` row would be mislabelled or a verbatim placeholder.
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    assertTrue(body.contains("cp-report-body"), body)
    assertFalse(body.contains(ServeIssueReport.RAW_SCORES_PLACEHOLDER), body)
    assertFalse(body.contains("Raw comparison"), body)
  }

  @Test
  fun `the viewer does not broaden an exact issue from a sibling variant`() {
    server = newServer()
    val (_, body) = get("/compose-m3/p/button-filled")
    assertFalse(body.contains("/issues/902"), body)
  }
}
