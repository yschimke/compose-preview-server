package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.previewdata.PreviewInfo
import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Rebuild after an edit, for a local project session: compile through the build host, then either
 * reload the classes in the running daemon or, when the daemon cannot pick the change up, restart
 * it. The compile is a fake; the module's `build/compose-previews/` is real files.
 */
class ServeLocalRebuildTest {
  private val operatorToken = "rebuild-test-token"
  private val projectDir: File =
    Files.createTempDirectory("local-rebuild").toFile().also { it.deleteOnExit() }
  private val output = File(projectDir, "build/compose-previews").apply { mkdirs() }
  private val module = PreviewModule("app", projectDir)

  /** A live host that records reloads and closes; [reloads] false models a host that cannot. */
  private inner class FakeHost(
    override val previews: List<ServePreview>,
    private val reloads: Boolean = true,
  ) : ServeHost {
    val sourcesChanges = CopyOnWriteArrayList<List<String>>()
    val closed = AtomicBoolean(false)
    override val label = "app"

    override fun sourcesChanged(paths: List<String>): Boolean {
      sourcesChanges += paths
      return reloads
    }

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

    override fun close() {
      closed.set(true)
    }
  }

  private val opened = CopyOnWriteArrayList<ServeSessionState>()
  private val registry =
    ServeSessionRegistry(
      open = { state ->
        opened += state
        FakeHost(state.previews)
      }
    )

  private fun writeOutput(
    descriptor: String = "{\"classpath\":[\"a.jar\"]}",
    previews: List<String>,
  ) {
    File(output, "daemon-launch.json").writeText(descriptor)
    File(output, "previews.json")
      .writeText(
        Json.encodeToString(
          PreviewManifest.serializer(),
          PreviewManifest(
            module = "app",
            variant = "",
            previews = previews.map { PreviewInfo(id = it, functionName = it, className = "Kt") },
          ),
        )
      )
  }

  private fun register(
    previews: List<String>,
    host: FakeHost = FakeHost(previews.map { ServePreview(it, it) }),
  ): FakeHost {
    writeOutput(previews = previews)
    registry.register(
      "app",
      ServeSessionState(
        descriptor = File(output, "daemon-launch.json"),
        workspaceRoot = projectDir,
        workspaceName = "app",
        previews = previews.map { ServePreview(it, it) },
        label = "app",
      ),
      host = host,
    )
    return host
  }

  private fun rebuild(compile: (PreviewModule) -> Boolean) =
    ServeLocalRebuild(
      registry = registry,
      modules = mapOf("app" to module),
      compile = compile,
      previewsOf = { _, manifest -> manifest.previews.map { ServePreview(it.id, it.id) } },
    )

  @AfterTest
  fun tearDown() {
    registry.close()
  }

  @Test
  fun `an edit that compiles reloads the classes in the running daemon`() {
    val host = register(listOf("Red"))

    val result = rebuild { true }.rebuild("app")

    assertIs<LocalRebuildResult.Reloaded>(result)
    assertEquals(listOf(emptyList<String>()), host.sourcesChanges)
    assertSame(host, registry.peekHost("app"), "the daemon keeps running")
    assertFalse(host.closed.get())
  }

  @Test
  fun `a changed classpath restarts the daemon from the new descriptor`() {
    val host = register(listOf("Red"))

    val result = rebuild {
      File(output, "daemon-launch.json").writeText("{\"classpath\":[\"a.jar\",\"b.jar\"]}")
      true
    }
      .rebuild("app")

    val restarted = assertIs<LocalRebuildResult.Restarted>(result)
    assertTrue(restarted.reason.contains("classpath"), restarted.reason)
    assertTrue(host.sourcesChanges.isEmpty(), "a swap cannot pick up a new classpath")
    assertTrue(host.closed.get(), "the old daemon is closed")
    assertNull(registry.peekHost("app"))
    assertNotNull(registry.acquire("app"), "the next request starts a daemon from the descriptor")
  }

  @Test
  fun `an added preview restarts the session with the new preview set`() {
    val host = register(listOf("Red"))

    val result = rebuild {
      writeOutput(previews = listOf("Red", "Blue"))
      true
    }
      .rebuild("app")

    val restarted = assertIs<LocalRebuildResult.Restarted>(result)
    assertTrue(restarted.reason.contains("preview set"), restarted.reason)
    assertTrue(host.closed.get())
    assertEquals(listOf("Red", "Blue"), registry.peekState("app")!!.previews.map { it.id })
  }

  @Test
  fun `a failed build keeps the last good classes`() {
    val host = register(listOf("Red"))

    val result = rebuild { false }.rebuild("app")

    val failed = assertIs<LocalRebuildResult.Failed>(result)
    assertTrue(failed.reason.contains("composePreviewCompile"), failed.reason)
    assertTrue(host.sourcesChanges.isEmpty())
    assertFalse(host.closed.get())
    assertSame(host, registry.peekHost("app"))
  }

  @Test
  fun `a host that cannot reload in place is restarted instead`() {
    val host =
      register(listOf("Red"), FakeHost(listOf(ServePreview("Red", "Red")), reloads = false))

    val result = rebuild { true }.rebuild("app")

    assertIs<LocalRebuildResult.Restarted>(result)
    assertTrue(host.closed.get())
  }

  @Test
  fun `only local sessions rebuild, one at a time`() {
    register(listOf("Red"))
    val compiling = CountDownLatch(1)
    val release = CountDownLatch(1)
    val local = rebuild {
      compiling.countDown()
      release.await(10, TimeUnit.SECONDS)
    }
    assertEquals(LocalRebuildResult.NotFound, local.rebuild("compose-m3"))
    assertFalse(local.canRebuild("compose-m3"))

    val executor = Executors.newSingleThreadExecutor()
    try {
      val first = executor.submit<LocalRebuildResult> { local.rebuild("app") }
      assertTrue(compiling.await(10, TimeUnit.SECONDS))
      assertEquals(LocalRebuildResult.Busy, local.rebuild("app"))
      release.countDown()
      assertIs<LocalRebuildResult.Reloaded>(first.get(10, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `the rebuild route reports each outcome and the page offers the button`() {
    val host = register(listOf("Red"))
    val compiles = AtomicBoolean(true)
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = operatorToken,
          sessions = registry,
          defaultSessionId = "app",
          isPublic = false,
          localRebuild = rebuild { compiles.get() },
        )
        .also { it.start() }
    val client = OkHttpClient()
    fun call(path: String, post: Boolean): Pair<Int, String> {
      val request =
        Request.Builder()
          .url("http://127.0.0.1:${server.port}$path")
          .header(ServeHttpServer.TOKEN_HEADER, operatorToken)
          .apply { if (post) post("".toRequestBody("application/json".toMediaType())) }
          .build()
      return client.newCall(request).execute().use { it.code to it.body.string() }
    }
    try {
      val reloaded = call("/app/rebuild", post = true)
      assertEquals(200, reloaded.first, reloaded.second)
      assertTrue(reloaded.second.contains("\"status\":\"reloaded\""), reloaded.second)
      assertEquals(1, host.sourcesChanges.size)

      compiles.set(false)
      val failed = call("/rebuild?session=app", post = true)
      assertEquals(422, failed.first, failed.second)
      assertTrue(failed.second.contains("\"status\":\"failed\""), failed.second)

      assertEquals(404, call("/compose-m3/rebuild", post = true).first)

      val page = call("/app/", post = false)
      assertEquals(200, page.first)
      assertTrue(page.second.contains("data-rebuild-url=\"/app/rebuild"), page.second.take(3000))
    } finally {
      server.stop()
    }
  }
}
