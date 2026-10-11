package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The server's own Android player daemon ([ServeRcPlayerDaemon]) as the lender of last resort, and
 * first choice, for the Android Remote Compose players: a baked-only catalog preview and a shared
 * `/d/<id>` document are drawn on it with no catalog daemon resident.
 *
 * The daemon's opener is a fake host that records what it was asked to render, so the test is about
 * routing, availability and lifecycle, not about a real Robolectric daemon.
 */
class ServeRcPlayerDaemonLaneTest {

  private val playerRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private val donorRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private val opened = AtomicInteger()

  private val playerPng = byteArrayOf(0x89.toByte(), 'R'.code.toByte(), 'C'.code.toByte())

  private val bakedPng = byteArrayOf(0x89.toByte(), 'B'.code.toByte(), 'K'.code.toByte())

  private val catalogDoc = ServeDocFixtures.remoteComposeDoc(width = 120, height = 60)

  private val docStore = ServeDocStore(ttlSeconds = 60, allowedHosts = emptyList())

  /** What the production opener spawns: a one-preview daemon that replays carried documents. */
  private inner class FakePlayerHost : ServeHost {
    override val previews = listOf(ServeRcPlayerDaemon.PREVIEW)
    override val label = "Remote Compose players"

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      assertEquals(ServeRcPlayerDaemon.PREVIEW_ID, previewId)
      playerRenders += overrides
      return RenderOutcome.Ok(playerPng, RenderOutcome.Generation.DAEMON)
    }

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

  private fun playerDaemon(
    players: List<RcPlayerBackend> =
      listOf(RcPlayerBackend.ANDROIDX_EMBEDDED, RcPlayerBackend.ANDROIDX_VIEW),
    clock: () -> Long = System::currentTimeMillis,
    liveSeats: LiveSeatLimiter? = null,
    openHost: () -> ServeHost = {
      opened.incrementAndGet()
      FakePlayerHost()
    },
  ) = ServeRcPlayerDaemon(players, openHost, liveSeats = liveSeats, clock = clock)

  /** A published bundle: the captured document, its render spec and a baked PNG, no daemon. */
  private val bakedOnlyCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(EXAMPLE_ID, "Remote example"))
      override val label = "Remote Compose examples"

      override fun remoteComposeDoc(previewId: String) = catalogDoc.takeIf {
        previewId == EXAMPLE_ID
      }

      override fun remoteComposeRenderSpec(previewId: String) =
        RcJvmRenderSpec(widthPx = 300, heightPx = 150, density = 2.5f).takeIf {
          previewId == EXAMPLE_ID
        }

      override fun bakedRender(previewId: String, overrides: PreviewOverrides) =
        RenderOutcome.Ok(bakedPng, RenderOutcome.Generation.BAKED)

      override fun render(previewId: String, overrides: PreviewOverrides) =
        RenderOutcome.Ok(bakedPng, RenderOutcome.Generation.BAKED)

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

  /** A resident catalog daemon that could lend itself, as in [ServeDonorRcPlayerLaneTest]. */
  private val donorCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(DONOR_ID, "Remote button"))
      override val label = "Remote catalog"
      override val daemonStarted = true

      override fun hasRemoteComposeDoc(previewId: String) = previewId == DONOR_ID

      override fun canRenderOverridesFor(previewId: String) = previewId == DONOR_ID

      override fun enabledRcPlayersFor(previewId: String) =
        listOf(RcPlayerBackend.ANDROIDX_EMBEDDED, RcPlayerBackend.ANDROIDX_VIEW)

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        donorRenders += overrides
        return RenderOutcome.Ok(bakedPng, RenderOutcome.Generation.DAEMON)
      }

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

  private val registry =
    ServeSessionRegistry(open = { null }).also {
      it.register("examples", host = bakedOnlyCatalog, pinned = true)
    }

  private var daemon: ServeRcPlayerDaemon? = null

  private var server: ServeHttpServer? = null

  private fun serve(rcPlayerDaemon: ServeRcPlayerDaemon?): ServeHttpServer {
    daemon = rcPlayerDaemon
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "examples",
        isPublic = false,
        docStore = docStore,
        rcPlayerDaemon = rcPlayerDaemon,
      )
      .also {
        it.start()
        server = it
      }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server?.stop() }
    runCatching { daemon?.close() }
    runCatching { registry.close() }
  }

  private fun url(path: String) =
    "http://127.0.0.1:${server!!.port}$path${if ('?' in path) "&" else "?"}token=$TOKEN"

  private fun get(path: String) = client.newCall(Request.Builder().url(url(path)).build()).execute()

  private fun upload(bytes: ByteArray): String =
    client
      .newCall(
        Request.Builder()
          .url(url("/docs?name=button.rc"))
          .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
          .build()
      )
      .execute()
      .use { response ->
        assertEquals(201, response.code)
        Json.parseToJsonElement(response.body.string()).jsonObject["url"]!!.jsonPrimitive.content
      }

  /** Wait out the daemon's background start, as a viewer honouring `Retry-After` would. */
  private fun awaitRunning(daemon: ServeRcPlayerDaemon) {
    val deadline = System.currentTimeMillis() + 10_000
    while (daemon.snapshot().state != "running") {
      check(System.currentTimeMillis() < deadline) { "daemon never came up: ${daemon.snapshot()}" }
      Thread.sleep(10)
    }
  }

  @Test
  fun `a baked-only preview's android player starts the player daemon and draws the catalog document`() {
    val daemon = playerDaemon()
    serve(daemon)
    val path = "/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-embedded"

    // Cold: the request starts the daemon and is told to come back, never left hanging on a boot.
    get(path).use { response ->
      assertEquals(503, response.code)
      assertEquals("5", response.header("Retry-After"))
    }
    awaitRunning(daemon)
    get(path).use { response ->
      assertEquals(200, response.code)
      assertEquals(playerPng.toList(), response.body.bytes().toList())
      assertEquals(
        RenderOutcome.Generation.DAEMON.wire,
        response.header(ServeHttpServer.GENERATION_HEADER),
      )
    }
    assertEquals(1, opened.get(), "one daemon, started once")
    // The warm-up render was the request's own, so every render carried the catalog's document.
    for (rendered in playerRenders) {
      val rc = rendered.remoteCompose!!
      assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
      assertEquals(RcPlayerBackend.ANDROIDX_EMBEDDED.playerKind, rc.player)
      assertEquals(300, rendered.widthPx)
      assertEquals(150, rendered.heightPx)
      assertEquals(2.5f, rendered.density)
      assertEquals(UiMode.LIGHT, rendered.uiMode)
    }
  }

  @Test
  fun `the player daemon is preferred over a resident catalog daemon`() {
    registry.register("remote-catalog", host = donorCatalog, pinned = true)
    val daemon = playerDaemon()
    serve(daemon)
    val path = "/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-view"
    get(path).use { assertEquals(503, it.code) }
    awaitRunning(daemon)
    get(path).use { assertEquals(200, it.code) }
    assertTrue(donorRenders.isEmpty(), "no catalog daemon was borrowed")
    assertTrue(playerRenders.isNotEmpty())
  }

  /**
   * A live-seat budget whose background lane is refused, as on a box where 4 of 8 permits are free
   * but one of them is the per-preview slice: a background Android acquire needs its weight plus
   * the stream reserve from the general lane, which leaves it no seat.
   */
  private fun seatsRefusingBackground(): LiveSeatLimiter =
    LiveSeatLimiter(totalPermits = 8).also {
      assertTrue(it.acquire(4) != null)
      assertEquals(4, it.availablePermits())
      assertNull(
        it.acquireBackground(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT, dedicatedSlice = false)
      )
    }

  @Test
  fun `a player daemon with no live seat falls back to a resident catalog daemon`() {
    registry.register("remote-catalog", host = donorCatalog, pinned = true)
    val daemon = playerDaemon(liveSeats = seatsRefusingBackground())
    serve(daemon)
    get("/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-view").use { response ->
      assertEquals(200, response.code)
      assertEquals(bakedPng.toList(), response.body.bytes().toList())
      assertEquals(
        RenderOutcome.Generation.DAEMON.wire,
        response.header(ServeHttpServer.GENERATION_HEADER),
      )
    }
    assertEquals(0, opened.get(), "the seatless player daemon never started")
    val rc = donorRenders.single().remoteCompose!!
    assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
    assertEquals(RcPlayerBackend.ANDROIDX_VIEW.playerKind, rc.player)
  }

  @Test
  fun `a player daemon with no live seat and no donor says why`() {
    val daemon = playerDaemon(liveSeats = seatsRefusingBackground())
    serve(daemon)
    get("/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-view").use { response ->
      assertEquals(503, response.code)
      assertEquals("30", response.header("Retry-After"))
      assertTrue("no live seat" in response.body.string())
    }
    assertTrue(donorRenders.isEmpty())
  }

  @Test
  fun `a shared document falls back to a resident catalog daemon when the player daemon has no seat`() {
    registry.register("remote-catalog", host = donorCatalog, pinned = true)
    serve(playerDaemon(liveSeats = seatsRefusingBackground()))
    val bytes = ServeDocFixtures.remoteComposeDoc(width = 320, height = 200)
    val path = upload(bytes)
    get("$path/render.png?rcPlayer=androidx-embedded").use { assertEquals(200, it.code) }
    assertEquals(
      Base64.getEncoder().encodeToString(bytes),
      donorRenders.single().remoteCompose!!.documentBase64,
    )
    assertEquals(0, opened.get())
  }

  @Test
  fun `without a player daemon or a resident catalog the android players stay unavailable`() {
    serve(rcPlayerDaemon = null)
    get("/examples/p/$EXAMPLE_ID").use { response ->
      val html = response.body.string()
      for (id in listOf("androidx-embedded", "androidx-view", "cmp-android")) {
        assertTrue(html.contains("<option value=\"rc:$id\" disabled>"), "$id is greyed out")
      }
    }
    get("/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-embedded").use { response ->
      assertFalse(response.code == 200 && response.body.bytes().contentEquals(playerPng))
    }
    assertTrue(playerRenders.isEmpty())
  }

  @Test
  fun `the viewer offers the player daemon's players before anything has started it`() {
    val daemon = playerDaemon()
    serve(daemon)
    get("/examples/p/$EXAMPLE_ID").use { response ->
      val html = response.body.string()
      for (id in listOf("androidx-embedded", "androidx-view")) {
        assertTrue(html.contains("<option value=\"rc:$id\">"), "$id is offered")
      }
      // The classpath has no CMP player, so that one stays greyed out.
      assertTrue(html.contains("<option value=\"rc:cmp-android\" disabled>"))
      // Offered, never opened on: a page view must not start a daemon.
      assertFalse(html.contains("data-rc-default=\"androidx-embedded\""))
    }
    assertEquals(0, opened.get(), "a page view started nothing")
    assertEquals("idle", daemon.snapshot().state)
  }

  @Test
  fun `a shared document's android lanes draw on the player daemon`() {
    val daemon = playerDaemon()
    serve(daemon)
    val bytes = ServeDocFixtures.remoteComposeDoc(width = 320, height = 200)
    val path = upload(bytes)
    get(path).use { response ->
      val html = response.body.string()
      assertTrue(html.contains("data-doc-lane=\"androidx-embedded\""))
      assertTrue(html.contains("data-doc-lane=\"androidx-view\""))
      assertFalse(html.contains("data-doc-lane=\"cmp-android\""))
    }
    get("$path/render.png?rcPlayer=androidx-view&density=2").use { assertEquals(503, it.code) }
    awaitRunning(daemon)
    get("$path/render.png?rcPlayer=androidx-view&density=2").use { response ->
      assertEquals(200, response.code)
      assertEquals(playerPng.toList(), response.body.bytes().toList())
    }
    val rendered = playerRenders.last()
    assertEquals(Base64.getEncoder().encodeToString(bytes), rendered.remoteCompose!!.documentBase64)
    assertEquals(640, rendered.widthPx)
    assertEquals(400, rendered.heightPx)
  }

  @Test
  fun `status reports the player daemon`() {
    serve(playerDaemon())
    get("/status.json").use { response ->
      val daemons =
        Json.parseToJsonElement(response.body.string()).jsonObject["daemons"]!!.jsonObject
      val player = daemons["rcPlayerDaemon"]!!.jsonObject
      assertEquals("idle", player["state"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun `an idle daemon closes and returns its live seat`() {
    var now = 0L
    val seats = LiveSeatLimiter(totalPermits = 8, perPreviewReserve = 0)
    val daemon = playerDaemon(clock = { now }, liveSeats = seats).also { this.daemon = it }
    assertIs<ServeRcPlayerDaemon.Outcome.Warming>(daemon.render(PreviewOverrides()))
    awaitRunning(daemon)
    assertEquals(6, seats.availablePermits(), "the resident daemon holds an Android seat")

    now += ServeRcPlayerDaemon.DEFAULT_IDLE_MILLIS - 1
    daemon.closeIfIdle()
    assertEquals("running", daemon.snapshot().state)
    now += 1
    daemon.closeIfIdle()
    assertEquals("idle", daemon.snapshot().state)
    assertEquals(8, seats.availablePermits())

    // The next render starts it again.
    assertIs<ServeRcPlayerDaemon.Outcome.Warming>(daemon.render(PreviewOverrides()))
    awaitRunning(daemon)
    assertEquals(2, opened.get())
  }

  @Test
  fun `a daemon that fails to start is not respawned on every poll`() {
    var now = 0L
    val daemon =
      playerDaemon(
          clock = { now },
          openHost = {
            opened.incrementAndGet()
            error("no Robolectric here")
          },
        )
        .also { this.daemon = it }
    assertIs<ServeRcPlayerDaemon.Outcome.Warming>(daemon.render(PreviewOverrides()))
    val deadline = System.currentTimeMillis() + 10_000
    while (daemon.snapshot().state != "failed") {
      check(System.currentTimeMillis() < deadline)
      Thread.sleep(10)
    }
    val refused =
      assertIs<ServeRcPlayerDaemon.Outcome.Unavailable>(daemon.render(PreviewOverrides()))
    assertTrue("no Robolectric here" in refused.reason)
    assertEquals(1, opened.get())

    now += ServeRcPlayerDaemon.DEFAULT_FAILURE_BACKOFF_MILLIS
    assertIs<ServeRcPlayerDaemon.Outcome.Warming>(daemon.render(PreviewOverrides()))
  }

  @Test
  fun `a classpath linkage error fails the start and returns the seat`() {
    val seats = LiveSeatLimiter(totalPermits = 8, perPreviewReserve = 0)
    val daemon =
      playerDaemon(
          liveSeats = seats,
          openHost = {
            opened.incrementAndGet()
            throw NoClassDefFoundError("androidx/compose/remote/player/view/RemoteComposePlayer")
          },
        )
        .also { this.daemon = it }
    assertIs<ServeRcPlayerDaemon.Outcome.Warming>(daemon.render(PreviewOverrides()))
    val deadline = System.currentTimeMillis() + 10_000
    while (daemon.snapshot().state != "failed") {
      check(System.currentTimeMillis() < deadline) { "still ${daemon.snapshot().state}" }
      Thread.sleep(10)
    }
    assertEquals(8, seats.availablePermits(), "the failed start returned its seat")
    val refused =
      assertIs<ServeRcPlayerDaemon.Outcome.Unavailable>(daemon.render(PreviewOverrides()))
    assertTrue("RemoteComposePlayer" in refused.reason)
  }

  @Test
  fun `players are offered by the libraries the staged jars carry`() {
    val dir = Files.createTempDirectory("rc-players").toFile()
    fun jar(name: String, vararg entries: String) =
      File(dir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
          entries.forEach {
            zip.putNextEntry(ZipEntry(it))
            zip.closeEntry()
          }
        }
      }
    val view = jar("view.jar", "androidx/compose/remote/player/view/RemoteComposePlayer.class")
    val embedded = jar("embedded.jar", "ee/schimke/composeai/rcembedded/player/RcPlayerKt.class")
    assertEquals(
      listOf(RcPlayerBackend.ANDROIDX_EMBEDDED, RcPlayerBackend.ANDROIDX_VIEW),
      ServeRcPlayerDaemon.playersIn(listOf(view, embedded)),
    )
    assertEquals(listOf(RcPlayerBackend.ANDROIDX_VIEW), ServeRcPlayerDaemon.playersIn(listOf(view)))
    assertTrue(ServeRcPlayerDaemon.playersIn(emptyList()).isEmpty())
  }

  @Test
  fun `a host with no android runtime builds no player daemon`() {
    // No sidecar is staged for the test JVM, so the production builder declines (and says why).
    val reasons = mutableListOf<String>()
    assertNull(
      ServeRcPlayerDaemon.subprocess(Files.createTempDirectory("rc").toFile(), null) {
        reasons += it
      }
    )
    assertTrue(reasons.isNotEmpty())
  }

  private companion object {
    const val TOKEN = "rc-player-daemon"
    const val DONOR_ID = "com.example.RemoteButtonKt.RemoteButton"
    const val EXAMPLE_ID = "com.example.ExamplesKt.RemoteExample"
  }
}
