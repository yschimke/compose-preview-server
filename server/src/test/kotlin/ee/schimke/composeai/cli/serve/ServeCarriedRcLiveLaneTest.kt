package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The live lane of a document-only catalog's preview on an Android player: the socket streams a
 * replay of the catalog's document ([ServeCarriedRcStream]) on the server's player daemon, or on a
 * resident catalog daemon when that one has no seat, because no daemon's `stream/start` can hold a
 * carried document.
 */
class ServeCarriedRcLiveLaneTest {

  private val playerRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private val donorRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private val catalogDoc = ServeDocFixtures.remoteComposeDoc(width = 120, height = 60)

  /** The player daemon's host: each frame's pixels name the instant it was drawn at. */
  private inner class FakePlayerHost : ServeHost {
    override val previews = listOf(ServeRcPlayerDaemon.PREVIEW)
    override val label = "Remote Compose players"

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      playerRenders += overrides
      return RenderOutcome.Ok(
        ServeCarriedRcStreamTest.png(overrides.captureAdvanceMs?.toInt() ?: -1),
        RenderOutcome.Generation.DAEMON,
      )
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

  /** A published document-only catalog: a captured document and its render spec, no daemon. */
  private val documentCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(EXAMPLE_ID, "Count down"))
      override val label = "Remote Compose examples"

      override fun remoteComposeDoc(previewId: String) = catalogDoc.takeIf {
        previewId == EXAMPLE_ID
      }

      override fun remoteComposeRenderSpec(previewId: String) =
        RcJvmRenderSpec(widthPx = 300, heightPx = 150, density = 1f).takeIf {
          previewId == EXAMPLE_ID
        }

      override fun render(previewId: String, overrides: PreviewOverrides) =
        RenderOutcome.Ok(BAKED_PNG, RenderOutcome.Generation.BAKED)

      override fun bakedRender(previewId: String, overrides: PreviewOverrides) =
        RenderOutcome.Ok(BAKED_PNG, RenderOutcome.Generation.BAKED)

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

  /** A resident catalog daemon that lends itself, as in [ServeDonorRcPlayerLaneTest]. */
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
        return RenderOutcome.Ok(
          ServeCarriedRcStreamTest.png(overrides.captureAdvanceMs?.toInt() ?: -1),
          RenderOutcome.Generation.DAEMON,
        )
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
      it.register("examples", host = documentCatalog, pinned = true)
    }

  private var daemon: ServeRcPlayerDaemon? = null

  private var server: ServeHttpServer? = null

  private var socket: WebSocket? = null

  private val client = OkHttpClient()

  private fun serve(
    rcPlayerDaemon: ServeRcPlayerDaemon?,
    liveSeats: LiveSeatLimiter? = null,
  ): ServeHttpServer {
    daemon = rcPlayerDaemon
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "examples",
        isPublic = false,
        rcPlayerDaemon = rcPlayerDaemon,
        liveSeatLimiter = liveSeats,
      )
      .also {
        it.start()
        server = it
      }
  }

  private fun playerDaemon(liveSeats: LiveSeatLimiter? = null) =
    ServeRcPlayerDaemon(
      players = listOf(RcPlayerBackend.ANDROIDX_EMBEDDED, RcPlayerBackend.ANDROIDX_VIEW),
      openHost = { FakePlayerHost() },
      liveSeats = liveSeats,
    )

  @AfterTest
  fun stop() {
    runCatching { socket?.cancel() }
    runCatching { server?.stop() }
    runCatching { daemon?.close() }
    runCatching { registry.close() }
  }

  private fun page(): String =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server!!.port}/examples/p/$EXAMPLE_ID?token=$TOKEN")
          .build()
      )
      .execute()
      .use { it.body.string() }

  /** Open the live socket and collect what the server sends. */
  private fun openLive(query: String): LinkedBlockingQueue<JsonObject> {
    val messages = LinkedBlockingQueue<JsonObject>()
    socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server!!.port}/examples/ws/$EXAMPLE_ID?token=$TOKEN&$query")
          .build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            messages += Json.parseToJsonElement(text).jsonObject
          }
        },
      )
    return messages
  }

  private fun LinkedBlockingQueue<JsonObject>.next(type: String): JsonObject {
    val deadline = System.currentTimeMillis() + 15_000
    while (true) {
      val left = deadline - System.currentTimeMillis()
      val message = assertNotNull(poll(left.coerceAtLeast(1), TimeUnit.MILLISECONDS), "no $type")
      if (message["type"]?.jsonPrimitive?.content == type) return message
    }
  }

  @Test
  fun `a document-only preview offers live on the player daemon and says input is not forwarded`() {
    serve(playerDaemon())
    val html = page()
    assertFalse(
      Regex("<button[^>]*id=\"cp-live-toggle\"[^>]*disabled").containsMatchIn(html),
      "the live chip is enabled",
    )
    assertTrue(html.contains("data-live-input=\"none\""))
    assertTrue(html.contains("<option value=\"rc:androidx-view\">"))
    assertEquals("idle", daemon!!.snapshot().state, "a page view started nothing")
  }

  @Test
  fun `with no daemon to replay the document there is still no live lane`() {
    serve(rcPlayerDaemon = null)
    val html = page()
    assertTrue(Regex("<button[^>]*id=\"cp-live-toggle\"[^>]*disabled").containsMatchIn(html))
    assertFalse(html.contains("data-live-input"))
  }

  @Test
  fun `the live socket streams the catalog document on the player daemon as it animates`() {
    serve(playerDaemon())
    val messages = openLive("rcPlayer=androidx-view&uiMode=dark")
    // The daemon is cold: the stream waits out its start rather than failing, then paints.
    val painted =
      List(3) { messages.next("frame") }.map { it["dataBase64"]!!.jsonPrimitive.content }
    assertEquals(3, painted.toSet().size, "each frame is a later instant of the document")
    assertTrue(playerRenders.isNotEmpty())
    for (rendered in playerRenders) {
      val rc = rendered.remoteCompose!!
      assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
      assertEquals(RcPlayerBackend.ANDROIDX_VIEW.playerKind, rc.player)
      assertEquals(300, rendered.widthPx)
      assertEquals(150, rendered.heightPx)
      assertEquals(UiMode.DARK, rendered.uiMode)
    }
    val phases = playerRenders.mapNotNull { it.captureAdvanceMs }
    assertEquals(phases.sorted(), phases)
    assertTrue(phases.toSet().size >= 3)
  }

  @Test
  fun `a player the daemon cannot draw falls back to the snapshot lane and says why`() {
    serve(playerDaemon())
    val messages = openLive("rcPlayer=cmp-android")
    // The snapshot lane paints what the host has (the baked picture), and names the live lane's
    // refusal when the visitor tries to interact.
    val frame = messages.next("frame")["dataBase64"]!!.jsonPrimitive.content
    assertEquals(Base64.getEncoder().encodeToString(BAKED_PNG), frame)
    socket!!.send("""{"type":"input","kind":"click","pixelX":1,"pixelY":1}""")
    val error = messages.next("error")["message"]!!.jsonPrimitive.content
    assertTrue("cmp-android cannot stream" in error, error)
    assertTrue(playerRenders.isEmpty(), "nothing was drawn on the player daemon")
  }

  @Test
  fun `a seatless player daemon streams on a resident catalog daemon instead`() {
    registry.register("remote-catalog", host = donorCatalog, pinned = true)
    // As in [ServeRcPlayerDaemonLaneTest]: the daemon's background seat is refused, a stream's
    // one-permit seat is not — and nor is the foreground seat a held session would start it on,
    // even with the stream's seat given up ([ServeRcPlayerDaemon.openLive]).
    val seats =
      LiveSeatLimiter(totalPermits = 8).also {
        assertNotNull(it.acquire(6))
        assertEquals(
          null,
          it.acquireBackground(ServeBundleDaemon.ANDROID_LIVE_SEAT_WEIGHT, dedicatedSlice = false),
        )
      }
    serve(playerDaemon(liveSeats = seats), liveSeats = seats)
    val messages = openLive("rcPlayer=androidx-embedded")
    repeat(2) { messages.next("frame") }
    assertTrue(playerRenders.isEmpty(), "the seatless player daemon never started")
    val rc = donorRenders.first().remoteCompose!!
    assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
    assertEquals(RcPlayerBackend.ANDROIDX_EMBEDDED.playerKind, rc.player)
  }

  private companion object {
    const val TOKEN = "carried-rc-live"
    const val EXAMPLE_ID = "androidx-demos__count-down"
    const val DONOR_ID = "com.example.RemoteButtonKt.RemoteButton"
    val BAKED_PNG = ServeCarriedRcStreamTest.png(-2)
  }
}
