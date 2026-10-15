package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `GET /modules/`: several local modules (a `browse` over a project, or a Bazel selection kept as
 * separate modules) shown on one page, each from its own session.
 */
class ServeAllModulesTest {
  private val token = "all-modules-token"
  private val root: File = Files.createTempDirectory("all-modules").toFile()

  private class Host(override val label: String, override val previews: List<ServePreview>) :
    ServeHost {
    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(byteArrayOf(1))

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {}
  }

  private val registry = ServeSessionRegistry(open = { Host(it.label, it.previews) })
  private val servers = mutableListOf<ServeHttpServer>()

  @AfterTest
  fun tearDown() {
    servers.forEach { it.stop() }
    registry.close()
    root.deleteRecursively()
  }

  private fun module(id: String, label: String, vararg previews: String): Pair<String, File> {
    val dir = root.resolve(id).apply { mkdirs() }
    val state =
      ServeSessionState(
        descriptor = dir.resolve("daemon-launch.json"),
        workspaceRoot = dir,
        workspaceName = id,
        previews = previews.map { ServePreview(it, "$it label") },
        label = label,
      )
    registry.register(id, state, host = Host(label, state.previews))
    return id to dir
  }

  private fun serve(roots: Map<String, File>): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = roots.keys.first(),
        isPublic = false,
        localSourceRoots = roots,
      )
      .also {
        it.start()
        servers += it
      }

  private fun get(server: ServeHttpServer, path: String): Pair<Int, String> {
    val client = OkHttpClient.Builder().followRedirects(false).build()
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$path")
        .header(ServeHttpServer.TOKEN_HEADER, token)
        .build()
    return client.newCall(request).execute().use { it.code to it.body.string() }
  }

  @Test
  fun `every local module's previews show on one page, each from its own session`() {
    val server =
      serve(mapOf(module("ui", "UI", "Button", "Card"), module("feature", "Feature", "Login")))

    val (code, page) = get(server, "/modules/?token=$token")

    assertEquals(200, code, page.take(2000))
    assertTrue("2 modules" in page, page.take(3000))
    for ((session, preview) in listOf("ui" to "Button", "ui" to "Card", "feature" to "Login")) {
      assertTrue(
        "src=\"/$session/render/$preview.png?token=$token\"" in page,
        "$session/$preview card in ${page.take(4000)}",
      )
      assertTrue("href=\"/$session/p/$preview?token=$token\"" in page, page.take(4000))
    }
    assertTrue("loading=\"lazy\"" in page)
    assertEquals(301, get(server, "/modules").first.let { if (it == 302) 301 else it })

    val (landingCode, landing) = get(server, "/ui/?token=$token")
    assertEquals(200, landingCode)
    assertTrue("href=\"/modules/" in landing, landing.take(3000))
  }

  @Test
  fun `one local module has no all-modules page`() {
    val server = serve(mapOf(module("ui", "UI", "Button")))

    assertEquals(404, get(server, "/modules/").first)
    assertFalse("href=\"/modules/" in get(server, "/ui/").second)
  }
}
