package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

  private fun serve(roots: Map<String, File>, rebuild: ServeLocalRebuild? = null): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = roots.keys.first(),
        isPublic = false,
        localSourceRoots = roots,
        localRebuild = rebuild,
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
    assertFalse("data-reload-url" in page, "nothing to follow without a rebuild")
    assertEquals(404, get(server, "/modules/reload?token=$token").first)
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

  /** A rebuild whose compile is [compile]; it never reaches a manifest, so only failures show. */
  private fun rebuild(roots: Map<String, File>, compile: () -> Boolean = { false }) =
    ServeLocalRebuild(
      registry = registry,
      modules = roots.mapValues { (id, dir) -> PreviewModule(id, dir) },
      compile = { compile() },
      previewsOf = { _, manifest -> manifest.previews.map { ServePreview(it.id, it.id) } },
    )

  @Test
  fun `the page follows edits to every module over one long poll`() {
    val roots = mapOf(module("ui", "UI", "Button"), module("feature", "Feature", "Login"))
    val rebuild = rebuild(roots)
    val server = serve(roots, rebuild)

    val (_, page) = get(server, "/modules/?token=$token")
    assertTrue("data-reload-url=\"/modules/reload?token=$token\"" in page, page.take(4000))
    for (session in listOf("ui", "feature")) {
      assertTrue("data-session=\"$session\"" in page, page.take(4000))
    }
    assertTrue("cp-rebuild-status" in page)

    fun reload(after: Long): Map<String, Long> {
      val (code, body) = get(server, "/modules/reload?after=$after&token=$token")
      assertEquals(200, code, body)
      val json = Json.parseToJsonElement(body).jsonObject
      return json.getValue("modules").jsonObject.mapValues { (_, state) ->
        state.jsonObject.getValue("version").jsonPrimitive.long
      } + ("" to json.getValue("version").jsonPrimitive.long)
    }
    assertEquals(mapOf("ui" to 0L, "feature" to 0L, "" to 0L), reload(after = -1))

    rebuild.rebuild("feature")
    val (_, body) = get(server, "/modules/reload?after=0&token=$token")
    assertTrue("\"status\":\"failed\"" in body, body)
    assertEquals(mapOf("ui" to 0L, "feature" to 2L, "" to 2L), reload(after = 0))
  }

  @Test
  fun `a wait on every module returns when any one of them moves`() = runBlocking {
    val roots = mapOf(module("ui", "UI", "Button"), module("feature", "Feature", "Login"))
    val release = CountDownLatch(1)
    val rebuild = rebuild(roots) { release.await(10, TimeUnit.SECONDS) }

    val idle = rebuild.awaitReloadStates(roots.keys, afterVersion = 0, timeoutMillis = 50)
    assertEquals(setOf("ui", "feature"), idle?.keys, "nothing new times out with every state")
    assertEquals(null, rebuild.awaitReloadStates(listOf("compose-m3"), -1, 50))

    val waiting = async {
      rebuild.awaitReloadStates(roots.keys, afterVersion = 0, timeoutMillis = 10_000)
    }
    val pool = Executors.newSingleThreadExecutor()
    try {
      val done = pool.submit<LocalRebuildResult> { rebuild.rebuild("ui") }
      val states = assertNotNull(waiting.await())
      assertTrue(states.getValue("ui").building, "$states")
      assertEquals(0, states.getValue("feature").version)
      release.countDown()
      done.get(10, TimeUnit.SECONDS)
    } finally {
      release.countDown()
      pool.shutdownNow()
    }
  }

  @Test
  fun `browse opens every module on one page when it opened several`() {
    assertEquals("/modules/", browseOpenPath("/", localModules = 3))
    assertEquals("/", browseOpenPath("/", localModules = 1))
    assertEquals("/ui-builder/", browseOpenPath("/ui-builder/", localModules = 3))
  }
}
