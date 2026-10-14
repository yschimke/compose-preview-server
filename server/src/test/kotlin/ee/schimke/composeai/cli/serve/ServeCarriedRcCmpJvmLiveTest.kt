package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * A document-only preview's live lane on `cmp-jvm`: the server's desktop player daemon
 * ([ServeRcPlayerDaemon.desktopSubprocess]) holds a `stream/start` session that plays the carried
 * document in the CMP player's JVM host when it advertises
 * [ServeRcPlayerDaemon.CARRIED_LIVE_CAPABILITY] (compose-preview-daemon#255), and the viewer's
 * input reaches it. Without the desktop daemon, `rcPlayer=cmp-jvm` on the socket is refused as it
 * always was; with one that does not advertise the token, the lane stays the one-shot replay.
 *
 * The daemon is a fake host, as in [ServeCarriedRcInteractiveTest]: it records the overrides each
 * held session opened with and the input that reached it, and answers each input with a new frame.
 */
class ServeCarriedRcCmpJvmLiveTest {

  private val catalogDoc = ServeDocFixtures.remoteComposeDoc(width = 120, height = 60)

  private val heldStarts = CopyOnWriteArrayList<PreviewOverrides>()

  private val heldInput = CopyOnWriteArrayList<Triple<InteractiveInputKind, Int?, Int?>>()

  private val heldOpen = AtomicInteger()

  private val daemonRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private inner class FakeDesktopHost(private val live: Boolean) :
    ServeHost, ServeRcPlayerDaemon.LiveDocuments {
    override val previews = listOf(ServeRcPlayerDaemon.PREVIEW)
    override val label = "CMP JVM player"
    override val playsCarriedDocumentsLive: Boolean
      get() = live

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      daemonRenders += overrides
      return RenderOutcome.Ok(ServeCarriedRcStreamTest.png(-3), RenderOutcome.Generation.DAEMON)
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
            frameStreamId = "held-desktop",
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

  /** The Android player daemon's host: renders only, so it never takes the cmp-jvm lane. */
  private val androidHost =
    object : ServeHost {
      override val previews = listOf(ServeRcPlayerDaemon.PREVIEW)
      override val label = "Remote Compose players"

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
        RenderOutcome.Ok(ServeCarriedRcStreamTest.png(-4), RenderOutcome.Generation.DAEMON)

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

  private val documentCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(EXAMPLE_ID, "Toggle"))
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

  private val daemons = mutableListOf<ServeRcPlayerDaemon>()
  private var server: ServeHttpServer? = null
  private var socket: WebSocket? = null
  private val client = OkHttpClient()

  private fun desktopDaemon(live: Boolean) =
    ServeRcPlayerDaemon(
        players = listOf(RcPlayerBackend.CMP_JVM),
        openHost = { FakeDesktopHost(live) },
        label = ServeRcPlayerDaemon.DESKTOP_LABEL,
        seatWeight = 1,
      )
      .also { daemons += it }

  private fun androidDaemon() =
    ServeRcPlayerDaemon(
        players = listOf(RcPlayerBackend.ANDROIDX_EMBEDDED),
        openHost = { androidHost },
      )
      .also { daemons += it }

  private fun serve(
    rcPlayerDaemon: ServeRcPlayerDaemon? = null,
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
      )
      .also {
        it.start()
        server = it
      }

  @AfterTest
  fun stop() {
    runCatching { socket?.cancel() }
    runCatching { server?.stop() }
    daemons.forEach { runCatching { it.close() } }
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
    // The earliest match, leaving every other message queued. Messages of different types are not
    // ordered: the replay's first frame can reach the socket before the lane message, and a replay
    // of an unchanging document sends no second frame, so skipping past it would wait forever.
    val deadline = System.currentTimeMillis() + 15_000
    while (true) {
      firstOrNull { it["type"]?.jsonPrimitive?.content == type && where(it) }
        ?.let { if (remove(it)) return it }
      assertTrue(System.currentTimeMillis() < deadline, "no $type")
      Thread.sleep(20)
    }
  }

  private fun JsonObject.payload() = this["dataBase64"]?.jsonPrimitive?.content

  private fun pngOf(marker: Int) =
    Base64.getEncoder().encodeToString(ServeCarriedRcStreamTest.png(marker))

  private fun warm(daemon: ServeRcPlayerDaemon) {
    val overrides = PreviewOverrides(widthPx = 10, heightPx = 10)
    val deadline = System.currentTimeMillis() + 10_000
    while (daemon.render(overrides) !is ServeRcPlayerDaemon.Outcome.Rendered) {
      assertTrue(System.currentTimeMillis() < deadline, "the fake daemon never started")
      Thread.sleep(20)
    }
  }

  @Test
  fun `the cmp-jvm socket holds the carried document on the desktop daemon and forwards input`() {
    val desktop = desktopDaemon(live = true)
    serve(rcPlayerDaemon = androidDaemon(), rcDesktopPlayerDaemon = desktop)
    val messages = openLive("rcPlayer=cmp-jvm")
    // Cold desktop daemon: the lane opens on the replay, then upgrades once it has started and said
    // it plays carried documents live.
    assertFalse(messages.next("lane")["input"]!!.jsonPrimitive.boolean)
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME) }
    assertTrue(messages.next("lane")["input"]!!.jsonPrimitive.boolean)

    val start = heldStarts.last()
    val rc = assertNotNull(start.remoteCompose)
    assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
    assertEquals(ServeRcPlayerIds.CMP_JVM, rc.playerId, "the desktop daemon's player, by id")
    assertNull(rc.player, "the AndroidX player enum is never sent to the desktop daemon")
    assertEquals(300, start.widthPx)
    assertEquals(150, start.heightPx)
    assertEquals(1f, start.density)
    assertNull(start.captureAdvanceMs, "a held session has no settle point to sample")

    socket!!.send("""{"type":"input","kind":"click","pixelX":200,"pixelY":120}""")
    messages.next("frame") { it.payload() == pngOf(HELD_FIRST_FRAME + 1) }
    assertEquals(Triple(InteractiveInputKind.CLICK, 200, 120), heldInput.single())

    socket!!.close(1000, null)
    val deadline = System.currentTimeMillis() + 5_000
    while (heldOpen.get() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertEquals(0, heldOpen.get(), "closing the socket stops the held session")
  }

  @Test
  fun `a warm desktop daemon that plays documents live opens the page on an input-taking lane`() {
    val desktop = desktopDaemon(live = true)
    serve(rcDesktopPlayerDaemon = desktop)
    warm(desktop)
    assertEquals(true, desktop.knownToPlayCarriedDocumentsLive)
    assertFalse(page().contains("data-live-input"), "the page invites input before any socket")
  }

  @Test
  fun `an Android player socket never asks the desktop daemon`() {
    val desktop = desktopDaemon(live = true)
    serve(rcPlayerDaemon = androidDaemon(), rcDesktopPlayerDaemon = desktop)
    val messages = openLive("rcPlayer=androidx-embedded")
    assertFalse(messages.next("lane")["input"]!!.jsonPrimitive.boolean)
    messages.next("frame")
    assertTrue(heldStarts.isEmpty(), "androidx-embedded is not the desktop daemon's player")
    assertEquals("idle", desktop.snapshot().state, "nor is the desktop daemon started for it")
  }

  @Test
  fun `without a desktop daemon cmp-jvm stays refused on the socket`() {
    serve(rcPlayerDaemon = androidDaemon())
    val messages = openLive("rcPlayer=cmp-jvm")
    val error = messages.next("error")
    assertTrue(
      error["message"]!!.jsonPrimitive.content.contains("cmp-jvm"),
      error["message"]!!.jsonPrimitive.content,
    )
    assertTrue(heldStarts.isEmpty())
  }

  @Test
  fun `a desktop daemon without the token keeps the cmp-jvm replay and drops input`() {
    val desktop = desktopDaemon(live = false)
    serve(rcDesktopPlayerDaemon = desktop)
    warm(desktop)
    assertEquals(false, desktop.knownToPlayCarriedDocumentsLive)
    assertTrue(page().contains("data-live-input=\"none\""), "the page says input is not forwarded")
    val messages = openLive("rcPlayer=cmp-jvm")
    assertFalse(messages.next("lane")["input"]!!.jsonPrimitive.boolean)
    socket!!.send("""{"type":"input","kind":"click","pixelX":40,"pixelY":20}""")
    // The replay is the cmp-jvm one-shot lane, which this test JVM has no sidecar for; either way
    // no
    // held session was asked for, and the input reached nothing.
    Thread.sleep(300)
    assertTrue(heldStarts.isEmpty(), "no held session was asked for")
    assertTrue(heldInput.isEmpty())
    assertTrue(daemonRenders.none { it.remoteCompose != null }, "nor a daemon render of it")
  }

  @Test
  fun `cmp-jvm parses as a daemon player id only where the caller says so`() {
    val params = mapOf("rcPlayer" to "cmp-jvm", "widthPx" to "200")
    assertIs<OverrideParse.Invalid>(ServeRcPlayerIds.parseOverrides(params))
    val ok =
      assertIs<OverrideParse.Ok>(ServeRcPlayerIds.parseOverrides(params, cmpJvmPlayerId = true))
    assertEquals(200, ok.overrides.widthPx)
    assertEquals(ServeRcPlayerIds.CMP_JVM, ok.overrides.remoteCompose?.playerId)
    assertNull(ok.overrides.remoteCompose?.player)
    // The legacy spelling still names the same player.
    val legacy =
      assertIs<OverrideParse.Ok>(
        ServeRcPlayerIds.parseOverrides(mapOf("rcPlayer" to "rcplayer-jvm"), cmpJvmPlayerId = true)
      )
    assertEquals(ServeRcPlayerIds.CMP_JVM, legacy.overrides.remoteCompose?.playerId)
    // Other players are untouched by the flag.
    val embedded =
      assertIs<OverrideParse.Ok>(
        ServeRcPlayerIds.parseOverrides(
          mapOf("rcPlayer" to "androidx-embedded"),
          cmpJvmPlayerId = true,
        )
      )
    assertEquals(
      RcPlayerBackend.ANDROIDX_EMBEDDED.playerKind,
      embedded.overrides.remoteCompose?.player,
    )
  }

  @Test
  fun `Skiko must load the native for the bindings it binds`() {
    fun jars(vararg names: String) = names.map { File("/x/$it") }
    assertNull(ServeRcPlayerDaemon.skikoPairingProblem(jars("rc-player-compose-jvm-2.9.0.jar")))
    assertNull(
      ServeRcPlayerDaemon.skikoPairingProblem(
        jars(
          "skiko-awt-0.150.1.jar",
          "skiko-awt-runtime-linux-x64-0.150.1.jar",
          "skiko-awt-runtime-linux-x64-0.144.6.jar",
          "skiko-awt-0.144.6.jar",
        )
      )
    )
    val missing =
      assertNotNull(ServeRcPlayerDaemon.skikoPairingProblem(jars("skiko-awt-0.150.1.jar")))
    assertTrue(missing.contains("0.150.1"), missing)
    // The player's newer bindings ahead of the daemon's native: the split that boots and then
    // throws
    // UnsatisfiedLinkError on the first newer JNI symbol.
    val split =
      assertNotNull(
        ServeRcPlayerDaemon.skikoPairingProblem(
          jars(
            "skiko-awt-0.150.1.jar",
            "skiko-awt-runtime-linux-x64-0.144.6.jar",
            "skiko-awt-0.144.6.jar",
          )
        )
      )
    assertTrue(split.contains("0.150.1") && split.contains("0.144.6"), split)
  }

  @Test
  fun `the desktop daemon is built only from a complete, paired runtime`() {
    val root = createTempDirectory("rc-desktop-sidecars").toFile()
    fun dir(name: String, vararg jars: Pair<String, String?>): File =
      File(root, name).apply {
        mkdirs()
        for ((jar, entry) in jars) zip(File(this, jar), entry ?: "META-INF/MANIFEST.MF")
      }
    val player =
      dir(
        "player",
        "rc-player-compose-jvm-2.9.0.jar" to ServeRcPlayerDaemon.CMP_PLAYER_MARKER,
        "skiko-awt-0.150.1.jar" to null,
        "skiko-awt-runtime-linux-x64-0.150.1.jar" to null,
      )
    val oldDaemon = dir("daemon-old", "daemon-desktop-3.15.3.jar" to null)
    val daemon =
      dir("daemon", "daemon-desktop-3.16.0.jar" to ServeRcPlayerDaemon.DESKTOP_BINDING_MARKER)
    val renderer = dir("renderer", "renderer-desktop-3.16.0.jar" to null)
    // The player's newer `skiko-awt` with no native for it.
    val unpaired =
      dir(
        "player-unpaired",
        "rc-player-compose-jvm-2.9.0.jar" to ServeRcPlayerDaemon.CMP_PLAYER_MARKER,
        "skiko-awt-0.150.1.jar" to null,
      )

    val props =
      mapOf(
        "composeai.cli.lib.rc.player.desktopDir" to player,
        "composeai.cli.libDaemonDesktopDir" to daemon,
        "composeai.cli.libRendererDir" to renderer,
      )
    val saved = (props.keys + "composeai.cli.skikoDir").associateWith { System.getProperty(it) }
    try {
      System.clearProperty("composeai.cli.skikoDir")
      props.forEach { (k, v) -> System.setProperty(k, v.absolutePath) }
      val logs = mutableListOf<String>()
      fun build(): ServeRcPlayerDaemon? =
        ServeRcPlayerDaemon.desktopSubprocess(root, null) { logs += it }?.also { daemons += it }

      val built = assertNotNull(build(), logs.joinToString())
      assertEquals(listOf(RcPlayerBackend.CMP_JVM), built.players)
      assertEquals("idle", built.snapshot().state, "built, not started")

      System.setProperty("composeai.cli.libDaemonDesktopDir", oldDaemon.absolutePath)
      assertNull(build())
      assertTrue(logs.last().contains("#255"), logs.last())

      System.setProperty("composeai.cli.libDaemonDesktopDir", daemon.absolutePath)
      System.setProperty("composeai.cli.lib.rc.player.desktopDir", unpaired.absolutePath)
      assertNull(build())
      assertTrue(logs.last().contains("0.150.1"), logs.last())

      System.setProperty("composeai.cli.lib.rc.player.desktopDir", File(root, "absent").path)
      assertNull(build())
      assertTrue(logs.last().contains(ServeRcPlayerDaemon.DESKTOP_PLAYER_SIDECAR), logs.last())
    } finally {
      saved.forEach { (k, v) ->
        if (v == null) System.clearProperty(k) else System.setProperty(k, v)
      }
      root.deleteRecursively()
    }
  }

  private fun zip(file: File, entry: String) {
    ZipOutputStream(file.outputStream()).use {
      it.putNextEntry(ZipEntry(entry))
      it.write(1)
      it.closeEntry()
    }
  }

  private companion object {
    const val TOKEN = "carried-rc-cmp-jvm"
    const val EXAMPLE_ID = "render__material-toggle-button"
    const val HELD_FIRST_FRAME = 2000
    val BAKED_PNG = ServeCarriedRcStreamTest.png(-2)
  }
}
