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
 * A published index is per **repository**, and a repository may declare several design systems —
 * `yschimke/wear-m3-catalog` publishes `:catalog` as `wear-m3-catalog` and `:remote-catalog` as
 * `remote-m3`, and `parity-issues.yml` pushes the identical file onto both delivery branches. Every
 * page that draws issue badges must therefore draw only the rows filed against the system it is
 * serving.
 *
 * A route test rather than a page assertion, for the reason [ServeViewerIssueReportRouteTest]
 * gives: the scope is applied by the handler that reads the host's index, so a `ServeWeb` golden
 * would keep passing for the whole period a route was passing the whole file through. Every surface
 * that shows a badge is requested here, because they read the index at four separate call sites and
 * it was one of them being fixed alone that let this survive on the others.
 */
class ServeParityIssueSystemScopeRouteTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  /**
   * Four rows over ONE component and ONE preview id, differing only in the system they name — the
   * shape two catalogs built from one repository actually produce, since they share both
   * vocabularies. #12 is a variant-scoped row naming this catalog's preview id exactly, which is
   * the collision the component match cannot explain away: four such ids collide between the two
   * wear catalogs today.
   */
  private fun bundle(): ServeBundleHost {
    val dir = Files.createTempDirectory("parity-issue-scope").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/button-filled.png").writeBytes(png())
    File(dir, "previews/variants.json")
      .writeText("{\"button-filled\":{\"componentId\":\"Button/Filled\"}}")
    File(dir, "references").apply { mkdirs() }
    File(dir, "references/button.png").writeBytes(png())
    File(dir, "references/index.json")
      .writeText(
        """
        {"schema":"compose-preview-references/v1","references":[{
           "id":"button-figma","previewId":"button-filled","label":"Figma button",
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
          {"repository":"yschimke/wear-m3-catalog","number":10,
           "title":"Filed against this catalog",
           "url":"https://github.com/yschimke/wear-m3-catalog/issues/10",
           "state":"open","system":"wear-m3-catalog","component":"Button/Filled",
           "scope":"component","previewIds":["button-filled"]},
          {"repository":"yschimke/wear-m3-catalog","number":11,
           "title":"Filed against the sibling catalog",
           "url":"https://github.com/yschimke/wear-m3-catalog/issues/11",
           "state":"open","system":"remote-m3","component":"Button/Filled",
           "scope":"component","previewIds":["button-filled__compact"]},
          {"repository":"yschimke/wear-m3-catalog","number":12,
           "title":"Sibling catalog, colliding preview id",
           "url":"https://github.com/yschimke/wear-m3-catalog/issues/12",
           "state":"open","system":"remote-m3","component":"Button/Filled",
           "scope":"variant","previewIds":["button-filled"]},
          {"repository":"yschimke/wear-m3-catalog","number":13,
           "title":"Published before the producer emitted a system",
           "url":"https://github.com/yschimke/wear-m3-catalog/issues/13",
           "state":"open","component":"Button/Filled","scope":"component",
           "previewIds":["button-filled"]}
        ]}
        """
          .trimIndent()
      )
    return ServeBundleHost(
      dir,
      label = "wear-m3-catalog",
      title = "M3 Wear OS Apps Design Kit",
      catalogSource =
        ServeWeb.CatalogSource(
          repo = "yschimke/wear-m3-catalog",
          ref = "main",
          module = "catalog",
        ),
      provenance =
        ServeWeb.CatalogProvenance(
          repo = "yschimke/wear-m3-catalog",
          branch = "design-artifacts/wear-m3-catalog",
        ),
      declaredBaked = listOf("button-filled"),
    )
  }

  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  private fun newServer(): ServeHttpServer {
    registry.register("wear-m3-catalog", host = bundle(), pinned = true)
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = registry,
        defaultSessionId = "wear-m3-catalog",
        isPublic = true,
        catalogSessions = listOf("wear-m3-catalog"),
      )
      .also { it.start() }
  }

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

  private fun assertScoped(path: String) {
    val (code, body) = get(path)
    assertEquals(200, code, path)
    assertTrue("/issues/10" in body, "$path drops this catalog's own issue")
    assertTrue("/issues/13" in body, "$path drops a row that names no system")
    assertFalse("/issues/11" in body, "$path draws a sibling system's component-scoped issue")
    assertFalse("/issues/12" in body, "$path draws a sibling system's colliding preview id")
  }

  @Test
  fun `the comparison wall draws only this system's issues`() {
    server = newServer()
    assertScoped("/wear-m3-catalog/compare")
  }

  @Test
  fun `the focused comparison draws only this system's issues`() {
    server = newServer()
    assertScoped("/wear-m3-catalog/compare/button-filled?reference=button-figma")
  }

  @Test
  fun `the viewer draws only this system's issues`() {
    server = newServer()
    assertScoped("/wear-m3-catalog/p/button-filled")
  }

  @Test
  fun `the landing's card badge counts only this system's issues`() {
    // The landing's badge is a count and a tooltip rather than links, so it is asserted on its own
    // terms — and the count is the reason it matters: a card reading "4 open issues" where two of
    // them are the sibling catalog's is the wall's problem in miniature.
    server = newServer()
    val (code, body) = get("/wear-m3-catalog/")
    assertEquals(200, code)
    assertTrue("2 open issues" in body, body.substringAfter("cp-issue-badge").take(400))
    assertTrue("#10 Filed against this catalog" in body, body)
    assertTrue("#13 Published before the producer emitted a system" in body, body)
    assertFalse("#11 Filed against the sibling catalog" in body, body)
    assertFalse("#12 Sibling catalog, colliding preview id" in body, body)
  }

  @Test
  fun `the parity dashboard draws only this system's issues`() {
    server = newServer()
    assertScoped("/wear-m3-catalog/parity")
  }

  @Test
  fun `the dashboard's json answers with the scoped index`() {
    server = newServer()
    val (code, body) = get("/wear-m3-catalog/parity?format=json")
    assertEquals(200, code)
    assertTrue("\"number\":10" in body, body)
    assertFalse("\"number\":11" in body, body)
  }
}
