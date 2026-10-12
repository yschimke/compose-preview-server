package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposeOverride
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Base64
import java.util.concurrent.CompletableFuture
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The seat a player daemon's held session takes on a box whose live-seat budget is already spent
 * down to the stream reserve — preview.coo.ee's steady state, where resident catalogs and spare
 * sandboxes hold everything but [LiveSeatLimiter.STREAM_RESERVE].
 *
 * A held session is a viewer's interactive stream, so it starts the daemon on a foreground seat
 * (the reserve exists for it) and may take over the socket's own stream seat to fit. Priced as
 * background work, as it was, every start was refused without a word: the lane stayed on its
 * input-less replay, and `/status` read "idle, renders 0, no error" for both player daemons.
 */
class ServeCarriedRcSeatTest {

  private val catalogDoc = ServeDocFixtures.remoteComposeDoc(width = 120, height = 60)

  private val heldInput = CopyOnWriteArrayList<Triple<InteractiveInputKind, Int?, Int?>>()

  private val heldOpen = AtomicInteger()

  private val hostsOpened = AtomicInteger()

  private val hostsClosed = AtomicInteger()

  /** A player daemon that holds carried documents live and answers input with a new frame. */
  private inner class LivePlayerHost : ServeHost, ServeRcPlayerDaemon.LiveDocuments {
    override val previews = listOf(ServeRcPlayerDaemon.PREVIEW)
    override val label = "Remote Compose players"
    override val playsCarriedDocumentsLive: Boolean = true

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.Ok(
        ServeCarriedRcStreamTest.png(overrides.captureAdvanceMs?.toInt() ?: -1),
        RenderOutcome.Generation.DAEMON,
      )

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? {
      // As a real player daemon: one held session at a time.
      if (heldOpen.get() > 0) {
        onUnavailable?.invoke("the daemon already holds a live session")
        return null
      }
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

    override fun close() {
      hostsClosed.incrementAndGet()
    }
  }

  private val documentCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(EXAMPLE_ID, "Toggle button"))
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

  /**
   * preview.coo.ee's budget at rest, in miniature: three permits, one of them the per-preview
   * slice, so the general lane holds exactly [LiveSeatLimiter.STREAM_RESERVE] and nothing for
   * background work.
   */
  private fun spentBox() =
    LiveSeatLimiter(3).also {
      assertEquals(LiveSeatLimiter.STREAM_RESERVE, 3 - it.perPreviewPermits)
    }

  private fun androidDaemon(seats: LiveSeatLimiter, clock: () -> Long = System::currentTimeMillis) =
    ServeRcPlayerDaemon(
        players = listOf(RcPlayerBackend.ANDROIDX_EMBEDDED, RcPlayerBackend.ANDROIDX_VIEW),
        openHost = {
          hostsOpened.incrementAndGet()
          LivePlayerHost()
        },
        liveSeats = seats,
        clock = clock,
      )
      .also { daemon = it }

  private var desktop: ServeRcPlayerDaemon? = null

  private fun desktopDaemon(seats: LiveSeatLimiter) =
    ServeRcPlayerDaemon(
        players = listOf(RcPlayerBackend.CMP_JVM),
        openHost = { LivePlayerHost() },
        liveSeats = seats,
        label = ServeRcPlayerDaemon.DESKTOP_LABEL,
        seatWeight = 1,
      )
      .also { desktop = it }

  @AfterTest
  fun stop() {
    viewers.forEach { runCatching { it.cancel() } }
    runCatching { socket?.cancel() }
    runCatching { server?.stop() }
    runCatching { daemon?.close() }
    runCatching { desktop?.close() }
    runCatching { registry.close() }
  }

  @Test
  fun `a held session starts the daemon on the stream reserve that a render may not touch`() {
    val seats = spentBox()
    val daemon = androidDaemon(seats)
    // A render start is background work: it leaves the reserve alone and says why.
    val refused = assertIs<ServeRcPlayerDaemon.Outcome.Unavailable>(daemon.render(carried()))
    assertTrue("no live seat" in refused.reason, refused.reason)
    assertEquals(refused.reason, daemon.snapshot().lastError, "/status says why it is idle")
    assertEquals(0, hostsOpened.get())
    assertFalse(daemon.holdsSeat)
    // A viewer's held session is the stream the reserve is kept for.
    assertEquals(ServeRcPlayerDaemon.LiveOutcome.Warming, daemon.openLive(carried(), null, null) {})
    awaitRunning(daemon)
    assertTrue(daemon.holdsSeat, "a socket on its player needs no seat of its own")
    assertEquals(1, hostsOpened.get())
    assertEquals(
      seats.perPreviewPermits,
      seats.availablePermits(),
      "the daemon holds the general lane's Android seat",
    )
    assertIs<ServeRcPlayerDaemon.LiveOutcome.Streaming>(daemon.openLive(carried(), null, null) {})
  }

  @Test
  fun `the viewer's own stream seat is given up when the daemon would not fit beside it`() {
    val seats = spentBox()
    val daemon = androidDaemon(seats)
    val streamSeat = ServeCarriedRcStreamSeat(seats, 1).also { assertTrue(it.acquire()) }
    assertEquals(
      ServeRcPlayerDaemon.LiveOutcome.Warming,
      daemon.openLive(carried(), null, null, streamSeat = streamSeat) {},
    )
    awaitRunning(daemon)
    assertFalse(streamSeat.holdsTicket, "the daemon's seat is made of it now")
    assertEquals(seats.perPreviewPermits, seats.availablePermits())
    assertTrue(streamSeat.ensureCharged(), "a replay meanwhile is paid for by that daemon")
    // The socket still closes its seat when it ends; a handed-over ticket must not pay back twice.
    streamSeat.close()
    assertEquals(seats.perPreviewPermits, seats.availablePermits())
    daemon.close()
    assertEquals(3, seats.availablePermits(), "the daemon returns both parts of its seat")
  }

  @Test
  fun `an admission racing the hand-over leaves the viewer's seat held`() {
    val seats = spentBox()
    val daemon = androidDaemon(seats)
    val own = assertNotNull(seats.acquire(1))
    var intruder: LiveSeatLimiter.Ticket? = null
    var taken = false
    // Another socket claims the last free permit the moment the daemon reads the viewer's seat —
    // between deciding to take it over and acquiring the rest of the weight.
    val racing =
      object : ServeRcPlayerDaemon.SeatHandOver {
        override val permits: Int
          get() {
            intruder = intruder ?: seats.acquire(1)
            return own.permits
          }

        override fun take(daemon: ServeRcPlayerDaemon): LiveSeatLimiter.Ticket? {
          taken = true
          return own
        }
      }
    assertIs<ServeRcPlayerDaemon.LiveOutcome.Busy>(
      daemon.openLive(carried(), null, null, streamSeat = racing) {}
    )
    assertNotNull(intruder, "the race happened")
    assertFalse(taken, "nothing is taken over unless the rest of the weight was had first")
    assertEquals(seats.perPreviewPermits, seats.availablePermits(), "the viewer still holds its")
    own.close()
    assertEquals(seats.perPreviewPermits + 1, seats.availablePermits())
  }

  @Test
  fun `the stream seat is kept when giving it up would still not fit the daemon`() {
    val seats = spentBox()
    val daemon = androidDaemon(seats)
    assertNotNull(seats.acquire(1))
    val streamSeat = ServeCarriedRcStreamSeat(seats, 1).also { assertTrue(it.acquire()) }
    val busy =
      assertIs<ServeRcPlayerDaemon.LiveOutcome.Busy>(
        daemon.openLive(carried(), null, null, streamSeat = streamSeat) {}
      )
    assertTrue(streamSeat.holdsTicket)
    assertTrue("no live seat" in busy.reason, busy.reason)
    assertEquals("idle", daemon.snapshot().state)
    assertEquals(
      seats.perPreviewPermits,
      seats.availablePermits(),
      "the stream still holds its seat",
    )
  }

  @Test
  fun `waiting on a starting daemon is not charged, and falling back to the replay is`() {
    val seats = spentBox()
    val daemon = androidDaemon(seats)
    val streamSeat = ServeCarriedRcStreamSeat(seats, 1)
    // Another viewer's open started the daemon, on the whole general lane.
    daemon.openLive(carried(), null, null) {}
    awaitRunning(daemon)
    assertTrue(streamSeat.ensureCharged(startingFor = daemon), "its seat stands for the session")
    assertFalse(streamSeat.holdsTicket)
    // The session went elsewhere: the replay must be paid for, and there is nothing free.
    assertFalse(streamSeat.ensureCharged())
    daemon.close()
    assertTrue(streamSeat.ensureCharged(), "once a seat is free, it is taken")
    assertTrue(streamSeat.holdsTicket)
    streamSeat.close()
    assertEquals(3, seats.availablePermits())
  }

  @Test
  fun `a foreground seat goes back soon after the last viewer leaves when it cannot be repriced`() {
    var now = 0L
    val seats = spentBox()
    val daemon = androidDaemon(seats, clock = { now })
    daemon.openLive(carried(), null, null) {}
    awaitRunning(daemon)
    val live =
      assertIs<ServeRcPlayerDaemon.LiveOutcome.Streaming>(daemon.openLive(carried(), null, null) {})
    now += ServeRcPlayerDaemon.DEFAULT_IDLE_MILLIS * 2
    daemon.closeIfIdle()
    assertEquals("running", daemon.snapshot().state, "a viewer is still interacting")
    live.handle.close()
    now += ServeRcPlayerDaemon.DEFAULT_FOREGROUND_IDLE_MILLIS - 1
    daemon.closeIfIdle()
    assertEquals("running", daemon.snapshot().state, "a reload finds it warm")
    now += 1
    daemon.closeIfIdle()
    assertEquals("idle", daemon.snapshot().state)
    assertEquals(1, hostsClosed.get())
    assertEquals(3, seats.availablePermits(), "the reserve is free for the next stream")
  }

  @Test
  fun `a foreground seat is repriced to background when the box has the headroom`() {
    var now = 0L
    val seats = LiveSeatLimiter(10)
    val daemon = androidDaemon(seats, clock = { now })
    daemon.openLive(carried(), null, null) {}
    awaitRunning(daemon)
    val live =
      assertIs<ServeRcPlayerDaemon.LiveOutcome.Streaming>(daemon.openLive(carried(), null, null) {})
    live.handle.close()
    val held = 10 - seats.availablePermits()
    now += ServeRcPlayerDaemon.DEFAULT_FOREGROUND_IDLE_MILLIS
    daemon.closeIfIdle()
    assertEquals("running", daemon.snapshot().state, "a background seat keeps the normal idle")
    assertEquals(held, 10 - seats.availablePermits(), "repricing swaps the seat, never adds one")
    now += ServeRcPlayerDaemon.DEFAULT_IDLE_MILLIS
    daemon.closeIfIdle()
    assertEquals("idle", daemon.snapshot().state)
    assertEquals(10, seats.availablePermits())
  }

  @Test
  fun `on a spent box the socket still upgrades to the held session and forwards a click`() {
    val seats = spentBox()
    serve(androidDaemon(seats), seats)
    val messages = openLive("rcPlayer=androidx-view")
    assertFalse(messages.next("lane")["input"]!!.jsonPrimitive.boolean, "opens on the replay")
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME) }
    assertTrue(messages.next("lane")["input"]!!.jsonPrimitive.boolean, "then takes input")

    socket!!.send("""{"type":"input","kind":"click","pixelX":40,"pixelY":20}""")
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME + 1) }
    assertEquals(Triple(InteractiveInputKind.CLICK, 40, 20), heldInput.single())
    assertEquals(0L, seats.refusalCount(), "no viewer was refused on the way")

    socket!!.close(1000, null)
    val deadline = System.currentTimeMillis() + 5_000
    while (heldOpen.get() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals(0, heldOpen.get(), "closing the socket stops the held session")

    // A reload while the daemon it left is still warm on the stream reserve: admitted, and held
    // straight away, without a second seat it could not have had.
    assertEquals("running", daemon!!.snapshot().state)
    val again = openLive("rcPlayer=androidx-view")
    again.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME) }
    assertTrue(again.next("lane") { it["input"]!!.jsonPrimitive.boolean }.isNotEmpty())
    socket!!.send("""{"type":"input","kind":"click","pixelX":40,"pixelY":20}""")
    again.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME + 2) }
    assertEquals(0L, seats.refusalCount())
  }

  @Test
  fun `a player daemon left warm on the reserve gives it up for another player's viewer`() {
    val seats = spentBox()
    val android = androidDaemon(seats)
    // An androidx-view viewer came and went: the Android daemon is warm on the whole reserve.
    android.openLive(carried(), null, null) {}
    awaitRunning(android)
    assertIs<ServeRcPlayerDaemon.LiveOutcome.Streaming>(android.openLive(carried(), null, null) {})
      .handle
      .close()
    assertEquals(seats.perPreviewPermits, seats.availablePermits())
    assertFalse(desktopDaemon(seats).releaseIfIdleForeground(), "nothing to give back")
    serve(android, seats, rcDesktopPlayerDaemon = desktop)

    // A cmp-jvm viewer is admitted and held rather than turned away at capacity.
    val messages = openLive("rcPlayer=cmp-jvm")
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME) }
    assertTrue(messages.next("lane") { it["input"]!!.jsonPrimitive.boolean }.isNotEmpty())
    assertEquals("idle", android.snapshot().state, "the idle Android daemon made room")
    assertEquals("running", desktop!!.snapshot().state)
    assertEquals(0L, seats.refusalCount())
  }

  @Test
  fun `a second viewer on the replay while the session is held is turned away on a spent box`() {
    val seats = spentBox()
    serve(androidDaemon(seats), seats)
    val first = openLive("rcPlayer=androidx-view")
    assertTrue(first.next("lane") { it["input"]!!.jsonPrimitive.boolean }.isNotEmpty())
    assertEquals(seats.perPreviewPermits, seats.availablePermits(), "the held daemon has it all")

    // The daemon holds a seat, so the second viewer is let in to see whether the held session is
    // theirs; it is not (one at a time), so its lane is the replay, which it cannot pay for.
    val second = viewer("rcPlayer=androidx-view")
    assertEquals(1013, second.closed.get(10, TimeUnit.SECONDS))
    assertEquals(seats.perPreviewPermits, seats.availablePermits(), "and it holds nothing")
    assertEquals(1, heldOpen.get(), "the first viewer keeps its session")
  }

  @Test
  fun `a second viewer on the replay while the session is held pays for its own seat`() {
    val seats = LiveSeatLimiter(6)
    serve(androidDaemon(seats), seats)
    val first = openLive("rcPlayer=androidx-view")
    assertTrue(first.next("lane") { it["input"]!!.jsonPrimitive.boolean }.isNotEmpty())
    val afterFirst = seats.availablePermits()

    val second = viewer("rcPlayer=androidx-view")
    assertFalse(second.messages.next("lane")["input"]!!.jsonPrimitive.boolean, "the replay")
    second.messages.next("frame")
    assertEquals(afterFirst - 1, seats.availablePermits(), "charged one stream seat")
    assertFalse(second.closed.isDone)
    second.socket.close(1000, null)
    val deadline = System.currentTimeMillis() + 5_000
    while (seats.availablePermits() != afterFirst && System.currentTimeMillis() < deadline) {
      Thread.sleep(20)
    }
    assertEquals(afterFirst, seats.availablePermits(), "and returns it when it leaves")
  }

  private class Viewer(
    val socket: WebSocket,
    val messages: LinkedBlockingQueue<JsonObject>,
    val closed: CompletableFuture<Int>,
  )

  private fun viewer(query: String): Viewer {
    val messages = LinkedBlockingQueue<JsonObject>()
    val closed = CompletableFuture<Int>()
    val ws =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server!!.port}/examples/ws/$EXAMPLE_ID?token=$TOKEN&$query")
          .build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            messages += Json.parseToJsonElement(text).jsonObject
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed.complete(code)
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            closed.complete(-1)
          }
        },
      )
    viewers += ws
    return Viewer(ws, messages, closed)
  }

  private val viewers = CopyOnWriteArrayList<WebSocket>()

  private fun serve(
    rcPlayerDaemon: ServeRcPlayerDaemon,
    seats: LiveSeatLimiter,
    rcDesktopPlayerDaemon: ServeRcPlayerDaemon? = null,
  ) =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "examples",
        isPublic = false,
        rcPlayerDaemon = rcPlayerDaemon,
        rcDesktopPlayerDaemon = rcDesktopPlayerDaemon,
        liveSeatLimiter = seats,
      )
      .also {
        it.start()
        server = it
      }

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

  private fun awaitRunning(daemon: ServeRcPlayerDaemon) {
    val deadline = System.currentTimeMillis() + 10_000
    while (daemon.snapshot().state != "running") {
      assertTrue(System.currentTimeMillis() < deadline, "the fake daemon never started")
      Thread.sleep(20)
    }
  }

  private fun carried() =
    PreviewOverrides(
      widthPx = 300,
      heightPx = 150,
      density = 1f,
      remoteCompose =
        RemoteComposeOverride.Builder()
          .also {
            it.player = RcPlayerBackend.ANDROIDX_VIEW.playerKind
            it.playerId = RcPlayerBackend.ANDROIDX_VIEW.daemonPlayerId
            it.documentBase64 = Base64.getEncoder().encodeToString(catalogDoc)
          }
          .build(),
    )

  private companion object {
    const val TOKEN = "carried-rc-seat"
    const val EXAMPLE_ID = "render__material-toggle-button"
    const val HELD_FIRST_FRAME = 2000
    val BAKED_PNG = ServeCarriedRcStreamTest.png(-2)
  }
}
