package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.ServerCapabilities
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A document-only preview's live lane as a real, input-taking session: when the server's player
 * daemon advertises [ServeRcPlayerDaemon.CARRIED_LIVE_CAPABILITY], the socket holds a
 * `stream/start` session carrying the document and forwards the viewer's input to it; when it does
 * not (a daemon from before compose-preview-daemon#254), the lane stays the repeated-render replay.
 *
 * The daemon is a fake host: it records the overrides each held session opened with and the input
 * that reached it, and answers each input with a new frame, as a real held composition would.
 */
class ServeCarriedRcInteractiveTest {

  private val catalogDoc = ServeDocFixtures.remoteComposeDoc(width = 120, height = 60)

  private val playerRenders = CopyOnWriteArrayList<PreviewOverrides>()

  /** Overrides each held session was opened with, in order. */
  private val heldStarts = CopyOnWriteArrayList<PreviewOverrides>()

  private val heldInput = CopyOnWriteArrayList<Triple<InteractiveInputKind, Int?, Int?>>()

  private val heldOpen = AtomicInteger()

  /** The player daemon's host; [live] is what its `initialize` would have advertised. */
  private inner class FakePlayerHost(private val live: Boolean) :
    ServeHost, ServeRcPlayerDaemon.LiveDocuments {
    override val previews = listOf(ServeRcPlayerDaemon.PREVIEW)
    override val label = "Remote Compose players"
    override val playsCarriedDocumentsLive: Boolean
      get() = live

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
    ): StreamHandle {
      assertEquals(ServeRcPlayerDaemon.PREVIEW_ID, previewId)
      heldStarts += overrides
      heldOpen.incrementAndGet()
      val seq = AtomicLong()
      fun frame(marker: Int) =
        onFrame(
          StreamFrameParams(
            frameStreamId = "held",
            seq = seq.getAndIncrement(),
            ptsMillis = 0,
            widthPx = 300,
            heightPx = 150,
            codec = StreamCodec.PNG,
            keyframe = true,
            final = false,
            payloadBase64 =
              Base64.getEncoder().encodeToString(ServeCarriedRcStreamTest.png(marker)),
          )
        )
      frame(HELD_FIRST_FRAME)
      return object : StreamHandle {
        override fun input(
          kind: InteractiveInputKind,
          pixelX: Int?,
          pixelY: Int?,
          pointerId: Int?,
          scrollDeltaY: Float?,
          keyCode: String?,
          text: String?,
          pointerType: String?,
        ) {
          heldInput += Triple(kind, pixelX, pixelY)
          frame(HELD_FIRST_FRAME + heldInput.size)
        }

        override fun close() {
          heldOpen.decrementAndGet()
        }
      }
    }

    override fun activeStreamCount(): Int = heldOpen.get()

    override fun close() {}
  }

  private val documentCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(EXAMPLE_ID, "Click counter"))
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

  private val registry =
    ServeSessionRegistry(open = { null }).also {
      it.register("examples", host = documentCatalog, pinned = true)
    }

  private var daemon: ServeRcPlayerDaemon? = null
  private var server: ServeHttpServer? = null
  private var socket: WebSocket? = null
  private val client = OkHttpClient()

  private fun playerDaemon(live: Boolean, clock: () -> Long = System::currentTimeMillis) =
    ServeRcPlayerDaemon(
        players = listOf(RcPlayerBackend.ANDROIDX_EMBEDDED, RcPlayerBackend.ANDROIDX_VIEW),
        openHost = { FakePlayerHost(live) },
        clock = clock,
      )
      .also { daemon = it }

  private fun serve(rcPlayerDaemon: ServeRcPlayerDaemon) =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "examples",
        isPublic = false,
        rcPlayerDaemon = rcPlayerDaemon,
      )
      .also {
        it.start()
        server = it
      }

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

  private fun LinkedBlockingQueue<JsonObject>.next(
    type: String,
    where: (JsonObject) -> Boolean = { true },
  ): JsonObject {
    val deadline = System.currentTimeMillis() + 15_000
    while (true) {
      val left = deadline - System.currentTimeMillis()
      val message = assertNotNull(poll(left.coerceAtLeast(1), TimeUnit.MILLISECONDS), "no $type")
      if (message["type"]?.jsonPrimitive?.content == type && where(message)) return message
    }
  }

  private fun JsonObject.payload() = this["dataBase64"]!!.jsonPrimitive.content

  private fun pngOf(marker: Int) =
    Base64.getEncoder().encodeToString(ServeCarriedRcStreamTest.png(marker))

  /** Wait for [daemon] to come up, the way the first `/render` of a cold daemon does. */
  private fun warm(daemon: ServeRcPlayerDaemon) {
    val overrides = PreviewOverrides(widthPx = 10, heightPx = 10)
    val deadline = System.currentTimeMillis() + 10_000
    while (daemon.render(overrides) !is ServeRcPlayerDaemon.Outcome.Rendered) {
      assertTrue(System.currentTimeMillis() < deadline, "the fake daemon never started")
      Thread.sleep(20)
    }
  }

  @Test
  fun `the capability needs both the token and a daemon that holds sessions`() {
    fun caps(interactive: Boolean, overrides: List<String>) =
      ServerCapabilities(
        incrementalDiscovery = false,
        sandboxRecycle = false,
        leakDetection = emptyList(),
        interactive = interactive,
        supportedOverrides = overrides,
      )
    val token = ServeRcPlayerDaemon.CARRIED_LIVE_CAPABILITY
    assertEquals("remoteCompose.documentBase64.live", token)
    assertTrue(ServeRcPlayerDaemon.playsCarriedDocumentsLive(caps(true, listOf("widthPx", token))))
    assertFalse(ServeRcPlayerDaemon.playsCarriedDocumentsLive(caps(true, listOf("widthPx"))))
    assertFalse(ServeRcPlayerDaemon.playsCarriedDocumentsLive(caps(false, listOf(token))))
  }

  @Test
  fun `a daemon that advertises the token holds the carried document live`() {
    val daemon = playerDaemon(live = true)
    assertEquals(null, daemon.knownToPlayCarriedDocumentsLive)
    val overrides = carried()
    // Cold: the call starts the daemon and asks the caller to come back.
    assertEquals(ServeRcPlayerDaemon.LiveOutcome.Warming, daemon.openLive(overrides, null, null) {})
    warm(daemon)
    assertEquals(true, daemon.knownToPlayCarriedDocumentsLive)
    val frames = CopyOnWriteArrayList<StreamFrameParams>()
    val live =
      assertIs<ServeRcPlayerDaemon.LiveOutcome.Streaming>(
        daemon.openLive(overrides, StreamCodec.WEBP, 12) { frames += it }
      )
    assertEquals(overrides, heldStarts.single(), "the held session carries the document as given")
    assertEquals(1, frames.size)
    live.handle.input(InteractiveInputKind.CLICK, 40, 20)
    assertEquals(Triple(InteractiveInputKind.CLICK, 40, 20), heldInput.single())
    assertEquals(2, frames.size)
    live.handle.close()
    assertEquals(0, heldOpen.get())
  }

  @Test
  fun `a daemon without the token is never asked to hold the document`() {
    val daemon = playerDaemon(live = false)
    warm(daemon)
    assertEquals(false, daemon.knownToPlayCarriedDocumentsLive)
    val outcome =
      assertIs<ServeRcPlayerDaemon.LiveOutcome.Unsupported>(
        daemon.openLive(carried(), null, null) {}
      )
    assertTrue(ServeRcPlayerDaemon.CARRIED_LIVE_CAPABILITY in outcome.reason, outcome.reason)
    assertTrue(heldStarts.isEmpty())
  }

  @Test
  fun `an open live session keeps the daemon from closing as idle`() {
    var now = 0L
    val daemon = playerDaemon(live = true, clock = { now })
    warm(daemon)
    val live =
      assertIs<ServeRcPlayerDaemon.LiveOutcome.Streaming>(daemon.openLive(carried(), null, null) {})
    now += ServeRcPlayerDaemon.DEFAULT_IDLE_MILLIS * 2
    daemon.closeIfIdle()
    assertEquals("running", daemon.snapshot().state, "a viewer is still interacting")
    live.handle.close()
    daemon.closeIfIdle()
    assertEquals("running", daemon.snapshot().state, "the idle clock restarts when it leaves")
    now += ServeRcPlayerDaemon.DEFAULT_IDLE_MILLIS
    daemon.closeIfIdle()
    assertEquals("idle", daemon.snapshot().state)
  }

  @Test
  fun `the socket switches from the replay to the held session and forwards input`() {
    serve(playerDaemon(live = true))
    val messages = openLive("rcPlayer=androidx-view")
    // Cold daemon: the lane opens on the replay, which says it takes no input…
    assertFalse(messages.next("lane")["input"]!!.jsonPrimitive.boolean)
    // …then, once the daemon is up and has said it plays documents live, the held session: its
    // opening keyframe arrives with `stream/start`, and the lane change right after it.
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME) }
    assertTrue(messages.next("lane")["input"]!!.jsonPrimitive.boolean)
    val start = heldStarts.last()
    val rc = assertNotNull(start.remoteCompose)
    assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
    assertEquals(RcPlayerBackend.ANDROIDX_VIEW.playerKind, rc.player)
    assertEquals(300, start.widthPx)
    assertEquals(150, start.heightPx)
    assertEquals(null, start.captureAdvanceMs, "a held session has no settle point to sample")

    socket!!.send("""{"type":"input","kind":"click","pixelX":40,"pixelY":20}""")
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME + 1) }
    assertEquals(Triple(InteractiveInputKind.CLICK, 40, 20), heldInput.single())

    // A page loaded now knows the lane takes input before any socket says so.
    assertFalse(page().contains("data-live-input"))

    socket!!.close(1000, null)
    val deadline = System.currentTimeMillis() + 5_000
    while (heldOpen.get() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals(0, heldOpen.get(), "closing the socket stops the held session")
  }

  @Test
  fun `without the token the socket keeps the replay and drops input`() {
    val daemon = playerDaemon(live = false)
    serve(daemon)
    warm(daemon)
    val html = page()
    assertTrue(html.contains("data-live-input=\"none\""), "the page says input is not forwarded")
    val messages = openLive("rcPlayer=androidx-view")
    assertFalse(messages.next("lane")["input"]!!.jsonPrimitive.boolean)
    repeat(2) { messages.next("frame") }
    socket!!.send("""{"type":"input","kind":"click","pixelX":40,"pixelY":20}""")
    messages.next("frame")
    assertTrue(heldStarts.isEmpty(), "no held session was asked for")
    assertTrue(heldInput.isEmpty())
    assertTrue(playerRenders.any { it.captureAdvanceMs != null }, "frames are replays")
  }

  private fun carried() =
    PreviewOverrides(
      widthPx = 300,
      heightPx = 150,
      density = 1f,
      remoteCompose =
        ee.schimke.composeai.daemon.protocol.RemoteComposeOverride.Builder()
          .also {
            it.player = RcPlayerBackend.ANDROIDX_VIEW.playerKind
            it.playerId = RcPlayerBackend.ANDROIDX_VIEW.daemonPlayerId
            it.documentBase64 = Base64.getEncoder().encodeToString(catalogDoc)
          }
          .build(),
    )

  private companion object {
    const val TOKEN = "carried-rc-interactive"
    const val EXAMPLE_ID = "androidx-demos__click-counter"
    const val HELD_FIRST_FRAME = 1000
    val BAKED_PNG = ServeCarriedRcStreamTest.png(-2)
  }
}
