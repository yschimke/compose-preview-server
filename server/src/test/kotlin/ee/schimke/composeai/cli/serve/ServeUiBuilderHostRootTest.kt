package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The UI builder at the ROOT of its own host (`--ui-builder-host-root`): `ui.coo.ee/` is the
 * editor's home and `ui.coo.ee/<design>` a design, while every server route keeps its meaning there
 * and every other host is untouched. See `ServeUiBuilderHostRoot.kt`.
 *
 * The host is chosen the way production chooses it — Caddy's `X-Forwarded-Host`, trusted — so one
 * server answers as the builder host and as `preview.coo.ee` in the same test.
 */
class ServeUiBuilderHostRootTest {

  private val servers = mutableListOf<ServeHttpServer>()

  @AfterTest
  fun stop() {
    servers.forEach { runCatching { it.stop() } }
  }

  private val bundle: File =
    Files.createTempDirectory("ui-builder-bundle").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html").writeText(SHELL)
      File(dir, "app.mjs").writeText("export const a = 1")
    }

  private fun server(rooted: Boolean = true): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
        trustForwardedFor = true,
        uiBuilderDir = bundle,
        uiBuilderHost = BUILDER,
        uiBuilderStartUrl = START,
        uiBuilderHostRoot = rooted,
      )
      .also {
        it.start()
        servers += it
      }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun ServeHttpServer.call(
    path: String,
    host: String?,
    html: Boolean = true,
    method: String = "GET",
  ): Response {
    val builder = Request.Builder().url("http://127.0.0.1:$port$path")
    if (host != null) builder.header("X-Forwarded-Host", host)
    if (html) builder.header("Accept", "text/html,application/xhtml+xml")
    if (method == "POST")
      builder.post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
    return client.newCall(builder.build()).execute()
  }

  private fun Response.isShell(): Boolean = use { code == 200 && "app.mjs" in body.string() }

  private fun ServeHttpServer.shell(path: String, host: String?): String =
    call(path, host).use { response ->
      assertEquals(200, response.code, "$host$path")
      response.body.string().also {
        assertTrue("app.mjs" in it, "$host$path should be the shell: $it")
      }
    }

  @Test
  fun `the builder host's root and a design path are the editor, telling it its base path`() {
    val server = server()

    val home = server.shell("/", BUILDER)
    assertTrue(BASE_PATH_META in home, "the rooted shell names its base path: $home")
    val design = server.shell("/my-design", BUILDER)
    assertTrue(BASE_PATH_META in design, design)
    // The shell's assets stay on the immutable, prefixed bundle path — they load from any page URL.
    assertTrue(Regex("""src="/ui-builder/v/[^/"]+/app\.mjs"""").containsMatchIn(design), design)
  }

  @Test
  fun `the builder host keeps every server route at its own path`() {
    val server = server()

    // The same answer as on any other host, byte for byte where there is a body: a carved-out
    // route never reaches the rooted builder routes.
    for (path in listOf("/healthz", "/version", "/robots.txt", "/api/ui-builder/v1/catalogs")) {
      val elsewhere = server.call(path, OTHER, html = false).use { it.code to it.body.string() }
      val onBuilder = server.call(path, BUILDER, html = false).use { it.code to it.body.string() }
      assertEquals(elsewhere, onBuilder, "$path must not change meaning on the builder host")
      assertFalse("app.mjs" in onBuilder.second, "$path is not the editor shell")
    }
    // `/start` is the builder host's guide, as before.
    server.call("/start", BUILDER).use { response ->
      assertEquals(302, response.code)
      assertEquals(START, response.header("Location"))
    }
  }

  @Test
  fun `a legacy ui-builder page is redirected to its rooted URL, on navigation only`() {
    val server = server()

    server.call("/ui-builder/my-design?node=n1", BUILDER).use { response ->
      assertEquals(302, response.code)
      assertEquals("/my-design?node=n1", response.header("Location"))
    }
    server.call("/ui-builder/", BUILDER).use { response ->
      assertEquals(302, response.code)
      assertEquals("/", response.header("Location"))
    }
    // Not a navigation: a fetch, an older editor's asset load, a form POST — served where it is.
    assertTrue(server.call("/ui-builder/my-design", BUILDER, html = false).isShell())
    server.call("/ui-builder/designs", BUILDER, method = "POST").use { response ->
      assertTrue(
        response.code != 302 || response.header("Location") != "/designs",
        "a POST is never moved",
      )
    }
    // The versioned bundle is an asset, whatever the Accept header says.
    val version =
      Regex("""/ui-builder/v/([^/"]+)/app\.mjs""").find(server.shell("/", BUILDER))!!.groupValues[1]
    server.call("/ui-builder/v/$version/app.mjs", BUILDER).use { assertEquals(200, it.code) }
    // A design named after a server route has no rooted URL, so it is left alone.
    assertTrue(server.call("/ui-builder/api", BUILDER).isShell())
  }

  @Test
  fun `every other host is unchanged`() {
    val server = server()

    assertFalse(server.call("/", OTHER).isShell(), "preview.coo.ee's root is its landing page")
    assertFalse(server.call("/my-design", OTHER).isShell(), "a bare id is a catalog id there")
    val prefixed = server.shell("/ui-builder/my-design", OTHER)
    assertFalse(BASE_PATH_META in prefixed, "only the rooted host names a base path: $prefixed")
    server.call("/ui-builder/my-design", OTHER).use { assertEquals(200, it.code, "no redirect") }
  }

  @Test
  fun `without root mode the builder host keeps the ui-builder prefix`() {
    val server = server(rooted = false)

    server.call("/", BUILDER).use { response ->
      assertEquals(302, response.code)
      assertEquals("/ui-builder/", response.header("Location"))
    }
    assertFalse(server.call("/my-design", BUILDER).isShell())
    val prefixed = server.shell("/ui-builder/my-design", BUILDER)
    assertFalse(BASE_PATH_META in prefixed, prefixed)
  }

  @Test
  fun `the legacy redirect target is same-origin and only for pages`() {
    fun redirect(
      path: String,
      method: String = "GET",
      accept: String? = "text/html",
      query: String = "",
    ) = uiBuilderRootRedirect(method, path, query, accept)

    assertEquals("/d1", redirect("/ui-builder/d1"))
    assertEquals("/d1/history", redirect("/ui-builder/d1/history"))
    assertEquals("/designs?folder=x", redirect("/ui-builder/designs", query = "folder=x"))
    assertEquals("/", redirect("/ui-builder"))
    // Never a protocol-relative URL to another origin.
    assertEquals("/evil.example", redirect("/ui-builder//evil.example"))
    assertNull(redirect("/ui-builder/d1", method = "POST"))
    assertNull(redirect("/ui-builder/d1", accept = "application/json"))
    assertNull(redirect("/ui-builder/d1", accept = null))
    assertNull(redirect("/ui-builder/v/abc/app.mjs"))
    assertNull(redirect("/ui-builder/runtime/r1/index.js"))
    assertNull(redirect("/ui-builder/ui-builder-sw.js"))
    assertNull(redirect("/ui-builder/api"), "a reserved id has no rooted URL")
    assertNull(redirect("/ui-builderx/d1"))
    assertNull(redirect("/p/x"))
  }

  private companion object {
    const val BUILDER = "ui.coo.ee"
    const val OTHER = "preview.coo.ee"
    const val START = "https://yschimke.github.io/compose-ui-builder/"
    const val SHELL =
      """<html><head></head><body><script type="module" src="app.mjs"></script></body></html>"""
    const val BASE_PATH_META = """<meta name="ui-builder-base-path" content="/">"""
  }
}
