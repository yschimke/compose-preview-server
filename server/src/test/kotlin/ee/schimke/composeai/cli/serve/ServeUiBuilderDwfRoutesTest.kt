package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderPreviewSurfaces
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.io.TempDir

/**
 * The `android-dwf` surface over HTTP and its socket, against a fake daemon: routing, the missing-
 * runtime answer the tab must show, session ownership, and control → daemon command over a real
 * WebSocket.
 */
class ServeUiBuilderDwfRoutesTest {
  @TempDir lateinit var directory: Path
  private val actor = AuthenticatedUiBuilderActor("dwf-fixture")
  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
  private val registries = mutableListOf<ServeSessionRegistry>()
  private val runtime = FakeDwfRuntime()
  private var server: ServeHttpServer? = null
  private var currentActor = actor.actorId

  @AfterTest
  fun tearDown() {
    server?.stop()
    registries.forEach { it.close() }
  }

  private fun start(
    availability: DwfApkRuntime.Availability = DwfApkRuntime.Availability.Ready,
    dwfCatalog: String = "m3-catalog",
    tickMillis: Long = 1000,
    animatedTickMillis: Long = 250,
    sourceFor: (ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1) -> DwfSource? = { null },
  ): ServeHttpServer {
    runtime.availability = availability
    val service =
      PersistentUiBuilderService(
          storage = FileUiBuilderStateStorage(directory),
          limits = UiBuilderServiceLimits(),
          catalogs = benchmarkCatalogExecutor(),
          exporter = UiBuilderExportExecutor { error("no export in this test") },
        )
        .also {
          runBlocking {
            it.execute(
              UiBuilderServiceCall(actor, UiBuilderServiceRequest.CreateDesign(document())),
            )
          }
        }
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "local-fixture",
        sessions = ServeSessionRegistry(open = { null }).also(registries::add),
        defaultSessionId = "unused",
        machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
        uiBuilderService = service,
        uiBuilderDwfPreview =
          UiBuilderDwfPreview(
            sessions = DwfPreviewSessions(runtime),
            isDwfCatalog = { it == dwfCatalog },
            tickMillis = tickMillis,
            animatedTickMillis = animatedTickMillis,
            sourceFor = sourceFor,
          ),
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization { _, _, _ ->
            UiBuilderAuthorizationDecision.Authorized(currentActor)
          },
      )
      .also {
        it.start()
        server = it
      }
  }

  private fun url(path: String) = "http://127.0.0.1:${server!!.port}$path"

  private fun get(path: String): Pair<Int, String> =
    client
      .newCall(
        Request.Builder()
          .url(url(path))
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .build(),
      )
      .execute()
      .use { it.code to it.body.string() }

  private fun post(path: String, body: String): Pair<Int, String> =
    client
      .newCall(
        Request.Builder()
          .url(url(path))
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .post(body.toRequestBody("application/json".toMediaType()))
          .build(),
      )
      .execute()
      .use { it.code to it.body.string() }

  private val base = "/api/ui-builder/v1/designs/watch-1/dwf-preview"

  private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

  @Test
  fun `a host without the runtime says so on the status route, with the reason`() {
    start(
      DwfApkRuntime.Availability.Unavailable(
        DwfPreviewSurface.RUNTIME_MISSING,
        "runtime is not installed",
      ),
    )
    val (code, body) = get("$base/status")
    assertEquals(200, code)
    val json = obj(body)
    assertEquals(false, json["available"]!!.jsonPrimitive.boolean)
    assertEquals(DwfPreviewSurface.RUNTIME_MISSING, json["code"]!!.jsonPrimitive.content)
    assertEquals("runtime is not installed", json["reason"]!!.jsonPrimitive.content)
  }

  @Test
  fun `starting without the runtime is a 503 naming the missing runtime`() {
    start(DwfApkRuntime.Availability.Unavailable(DwfPreviewSurface.RUNTIME_MISSING, "no runtime"))
    val (code, body) = post(base, """{"wffXml":"<WatchFace/>"}""")
    assertEquals(503, code)
    assertEquals(DwfPreviewSurface.RUNTIME_MISSING, obj(body)["code"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the status route is read-gated by design`() {
    start()
    val (code, _) = get("/api/ui-builder/v1/designs/nope/dwf-preview/status")
    assertEquals(404, code)
  }

  @Test
  fun `a design whose catalog is not a watch-face catalog is refused`() {
    start(dwfCatalog = "some-other-catalog")
    val (code, body) = post(base, """{"wffXml":"<WatchFace/>"}""")
    assertEquals(422, code)
    assertTrue(body.contains(DwfPreviewSurface.BACKEND))
  }

  @Test
  fun `a bad source is a 422 and a bad body a 400`() {
    start()
    assertEquals(422, post(base, """{"wffXml":"<WatchFace/>","wffVersion":9}""").first)
    assertEquals(422, post(base, """{"wffXml":"<WatchFace/>","assets":{"../x":"AAAA"}}""").first)
    assertEquals(422, post(base, """{"wffXml":"<WatchFace/>","assets":{"a.png":"!!"}}""").first)
    assertEquals(400, post(base, "not json").first)
    // No XML and no server-side exporter yet: said plainly, not a 500.
    val (code, body) = post(base, "{}")
    assertEquals(422, code)
    assertEquals(DwfPreviewSurface.SOURCE_UNAVAILABLE, obj(body)["code"]!!.jsonPrimitive.content)
  }

  @Test
  fun `an empty start body uses the WFF the server derives from the revision`() {
    var derived = 0
    start(
      sourceFor = {
        derived++
        DwfSource(wffXml = "<WatchFace/>", label = "derived")
      },
    )
    val (code, body) = post(base, "{}")
    assertEquals(201, code, body)
    assertEquals(1, derived)
  }

  @Test
  fun `a design that is not a watch face derives no source`() {
    assertEquals(null, deriveDwfSource(document()))
  }

  @Test
  fun `a started session streams frames and maps controls onto daemon commands`() {
    // A session ticks on the wall clock by default; a long tick keeps its frames out of the way.
    start(tickMillis = 600_000)
    val (code, body) = post("$base?revision=0", """{"wffXml":"<WatchFace/>","wffVersion":3}""")
    assertEquals(201, code, body)
    val started = obj(body)
    val id = started["sessionId"]!!.jsonPrimitive.content
    assertEquals(3, started["wffVersion"]!!.jsonPrimitive.int)
    assertEquals(
      "/ui-builder/watch-1/watch-face?session=$id",
      started["pagePath"]!!.jsonPrimitive.content,
    )
    assertEquals("$base/$id/ws", started["socketPath"]!!.jsonPrimitive.content)

    val inbox = LinkedBlockingQueue<JsonObject>()
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server!!.port}$base/$id/ws")
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            inbox.add(obj(text))
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            inbox.add(
              buildJsonObject {
                put("type", "failure")
                put("message", t.toString())
              },
            )
          }
        },
      )
    try {
      fun next(type: String): JsonObject {
        while (true) {
          val m = inbox.poll(10, TimeUnit.SECONDS) ?: fail("no `$type` message")
          if (m["type"]!!.jsonPrimitive.content == type) return m
          if (m["type"]!!.jsonPrimitive.content == "failure") fail(m.toString())
        }
      }
      assertEquals("dwf-apk", next("lane")["renderer"]!!.jsonPrimitive.content)
      val first = next("frame")
      assertEquals(1, first["seq"]!!.jsonPrimitive.int)
      assertEquals("png", first["codec"]!!.jsonPrimitive.content)
      assertTrue(
        Base64.getDecoder().decode(first["dataBase64"]!!.jsonPrimitive.content).isNotEmpty(),
      )

      val seen = runtime.commands.size
      socket.send(
        """{"type":"controls","controls":{"batteryPercent":64,"heartRate":88,"ambient":true}}""",
      )
      assertEquals(2, next("frame")["seq"]!!.jsonPrimitive.int)
      val sent = runtime.commands.drop(seen)
      assertEquals(
        listOf("ambient", "health", "battery"),
        // `clock` too: a ticking session's every frame carries the advanced wall clock.
        sent.map { it.cmd }.filter { it != "screenshot" && it != "watchface" && it != "clock" },
      )
      assertTrue(sent.any { it.cmd == "battery" && it.payload.toString() == """{"level":64}""" })
      assertTrue(
        sent.any { it.cmd == "ambient" && it.payload.toString() == """{"state":"AMBIENT"}""" },
      )

      // A bad control is an error message; the socket stays open and no daemon command is sent.
      val before = runtime.commands.size
      socket.send("""{"type":"controls","controls":{"batteryPercent":400}}""")
      assertTrue(next("error")["message"]!!.jsonPrimitive.content.contains("batteryPercent"))
      assertEquals(before, runtime.commands.size)
      socket.send("""{"type":"requestFrame"}""")
      assertEquals(3, next("frame")["seq"]!!.jsonPrimitive.int)
      socket.send("garbage")
      assertEquals("error", next("error")["type"]!!.jsonPrimitive.content)
    } finally {
      socket.close(1000, null)
    }

    // The session is closed by its owner; the daemon goes with the last one.
    val deleted =
      client
        .newCall(
          Request.Builder()
            .url(url("$base/$id"))
            .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
            .delete()
            .build(),
        )
        .execute()
        .use { it.code }
    assertEquals(204, deleted)
    assertTrue(runtime.connections.single().closed)
  }

  @Test
  fun `a ticking session redraws on its own`() {
    start(tickMillis = 50)
    val id =
      obj(post(base, """{"wffXml":"<WatchFace/>"}""").second)["sessionId"]!!.jsonPrimitive.content
    val frames = LinkedBlockingQueue<Int>()
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server!!.port}$base/$id/ws")
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            val m = obj(text)
            if (m["type"]!!.jsonPrimitive.content == "frame")
              frames.add(m["seq"]!!.jsonPrimitive.int)
          }
        },
      )
    try {
      assertEquals(1, frames.poll(10, TimeUnit.SECONDS))
      // Nothing asked for it: a new session runs on the wall clock, so the face moves on its own.
      assertEquals(2, frames.poll(10, TimeUnit.SECONDS))
      val mark = runtime.commands.size
      socket.send("""{"type":"controls","controls":{"timeMillis":1000,"ticking":true}}""")
      var last = 0
      repeat(4) { last = frames.poll(10, TimeUnit.SECONDS) ?: fail("ticking stopped") }
      assertTrue(last >= 4)
      // From the set time on, the clock only moves forward.
      val clocks = runtime.commands.drop(mark).filter { it.cmd == "clock" }
      assertTrue(clocks.size >= 3, "each tick sends the advanced clock: $clocks")
      assertTrue(
        clocks.zipWithNext().all { (a, b) ->
          b.payload["timeMillis"]!!.jsonPrimitive.long >=
            a.payload["timeMillis"]!!.jsonPrimitive.long
        },
      )
    } finally {
      socket.close(1000, null)
    }
  }

  private fun openSocket(id: String, inbox: LinkedBlockingQueue<JsonObject>): WebSocket =
    client.newWebSocket(
      Request.Builder()
        .url("ws://127.0.0.1:${server!!.port}$base/$id/ws")
        .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
        .build(),
      object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
          inbox.add(obj(text))
        }
      },
    )

  @Test
  fun `an async start answers at once and the socket reports each stage, then the frame`() {
    start(tickMillis = 600_000)
    val (code, body) = post(base, """{"wffXml":"<WatchFace/>","async":true}""")
    assertEquals(202, code, body)
    val started = obj(body)
    val id = started["sessionId"]!!.jsonPrimitive.content
    assertEquals("$base/$id", started["statusPath"]!!.jsonPrimitive.content)
    assertNotNull(started["stage"])

    val inbox = LinkedBlockingQueue<JsonObject>()
    val socket = openSocket(id, inbox)
    try {
      val stages = mutableListOf<JsonObject>()
      var frame: JsonObject? = null
      while (frame == null) {
        val m = inbox.poll(10, TimeUnit.SECONDS) ?: fail("no frame; stages: $stages")
        when (m["type"]!!.jsonPrimitive.content) {
          "stage" -> stages += m
          "frame" -> frame = m
        }
      }
      assertEquals(1, frame["seq"]!!.jsonPrimitive.int)
      // The live stage follows the frame, and every stage carries the step and elapsed time.
      val live = generateSequence {
        inbox.poll(10, TimeUnit.SECONDS)
      }
        .first { it["type"]!!.jsonPrimitive.content == "stage" }
      assertEquals("live", live["stage"]!!.jsonPrimitive.content)
      assertEquals(5, live["step"]!!.jsonPrimitive.int)
      assertEquals(5, live["steps"]!!.jsonPrimitive.int)
      assertTrue(live["elapsedMillis"]!!.jsonPrimitive.long >= 0)
      (stages + live).forEach { assertNotNull(it["label"], it.toString()) }

      val (statusCode, statusBody) = get("$base/$id")
      assertEquals(200, statusCode, statusBody)
      val status = obj(statusBody)
      assertEquals("live", status["stage"]!!.jsonPrimitive.content)
      assertEquals("Live", status["label"]!!.jsonPrimitive.content)
      assertEquals(600_000, status["frameIntervalMillis"]!!.jsonPrimitive.long)
    } finally {
      socket.close(1000, null)
    }
  }

  @Test
  fun `an async start the packager refuses fails on the socket with the packager's text`() {
    start()
    runtime.packagingRefusal = listOf("error[wff]: line 3: unknown element <Bogus>")
    val (code, body) = post(base, """{"wffXml":"<WatchFace/>","async":true}""")
    assertEquals(202, code, body)
    val id = obj(body)["sessionId"]!!.jsonPrimitive.content
    val inbox = LinkedBlockingQueue<JsonObject>()
    val socket = openSocket(id, inbox)
    try {
      val failed = generateSequence {
        inbox.poll(10, TimeUnit.SECONDS)
      }
        .first {
          it["type"]!!.jsonPrimitive.content == "stage" &&
            it["stage"]!!.jsonPrimitive.content == "failed"
        }
      assertEquals(DwfPreviewSurface.PACKAGING_FAILED, failed["code"]!!.jsonPrimitive.content)
      assertTrue(failed["reason"]!!.jsonPrimitive.content.contains("<Bogus>"))
      assertEquals(2, failed["step"]!!.jsonPrimitive.int)
      val error = generateSequence {
        inbox.poll(10, TimeUnit.SECONDS)
      }
        .first { it["type"]!!.jsonPrimitive.content == "error" }
      assertTrue(error["message"]!!.jsonPrimitive.content.contains("<Bogus>"))
      assertTrue(runtime.connections.isEmpty(), "a refused face never reaches the daemon")
    } finally {
      socket.close(1000, null)
    }
    val status = obj(get("$base/status").second)
    assertEquals(0, status["sessions"]!!.jsonPrimitive.int, "a failed session holds no slot")
  }

  @Test
  fun `a face that moves between seconds is redrawn faster than once a second`() {
    start(tickMillis = 600_000, animatedTickMillis = 20)
    val xml = """<WatchFace><Transform target="alpha" value="[SECOND_MILLISECOND]"/></WatchFace>"""
    val id =
      obj(post(base, Json.encodeToString(buildJsonObject { put("wffXml", xml) })).second)[
          "sessionId"]!!
        .jsonPrimitive
        .content
    val inbox = LinkedBlockingQueue<JsonObject>()
    val socket = openSocket(id, inbox)
    try {
      val lane = generateSequence {
        inbox.poll(10, TimeUnit.SECONDS)
      }
        .first { it["type"]!!.jsonPrimitive.content == "lane" }
      assertEquals(20, lane["frameIntervalMillis"]!!.jsonPrimitive.long)
      val seqs = generateSequence {
        inbox.poll(10, TimeUnit.SECONDS)
      }
        .filter { it["type"]!!.jsonPrimitive.content == "frame" }
        .take(4)
        .map { it["seq"]!!.jsonPrimitive.int }
        .toList()
      assertEquals(listOf(1, 2, 3, 4), seqs)
      // A hidden tab gets nothing until it is visible again.
      socket.send("""{"type":"visibility","visible":false}""")
      Thread.sleep(200)
      inbox.clear()
      Thread.sleep(200)
      assertTrue(inbox.none { it["type"]!!.jsonPrimitive.content == "frame" })
    } finally {
      socket.close(1000, null)
    }
  }

  @Test
  fun `another actor cannot open or close someone else's session`() {
    start()
    val id =
      obj(post(base, """{"wffXml":"<WatchFace/>"}""").second)["sessionId"]!!.jsonPrimitive.content
    currentActor = "mallory"
    val deleted =
      client
        .newCall(
          Request.Builder()
            .url(url("$base/$id"))
            .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
            .delete()
            .build(),
        )
        .execute()
        .use { it.code }
    assertEquals(404, deleted)
    val opened = LinkedBlockingQueue<String>()
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:${server!!.port}$base/$id/ws")
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            opened.add(text)
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            opened.add("closed:$reason")
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            opened.add("closed:$reason")
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            opened.add("closed:failure")
          }
        },
      )
    try {
      val first = opened.poll(10, TimeUnit.SECONDS)
      assertTrue(first!!.startsWith("closed:"), "the intruder gets no lane or frame: $first")
    } finally {
      socket.cancel()
    }
  }

  @Test
  fun `the tab page is served for a design and escapes its id`() {
    start()
    val (code, html) = get("/ui-builder/watch-1/watch-face")
    assertEquals(200, code)
    assertTrue(html.contains("""data-design="watch-1""""))
    assertTrue(html.contains("not available on this server"))
    assertEquals(
      "&lt;script&gt;&quot;&amp;",
      Regex("""data-design="([^"]*)"""")
        .find(UiBuilderDwfPage.html("<script>\"&"))!!
        .groupValues[1],
    )
  }

  // ---- routing --------------------------------------------------------------------------

  @Test
  fun `the android-dwf backend resolves to the watch-face surface whatever is served`() {
    for (served in listOf(null, "android", "desktop")) {
      assertEquals(
        UiBuilderNativeTargets.Resolution.WatchFace,
        UiBuilderNativeTargets.resolve(
          "wear-wff",
          "wear-wff",
          DwfPreviewSurface.BACKEND,
          served,
          null,
        ),
      )
    }
    // The existing decisions are untouched.
    assertIs<UiBuilderNativeTargets.Resolution.Target>(
      UiBuilderNativeTargets.resolve(
        "m3",
        "m3",
        "android",
        "android",
        UiBuilderCatalogPlatform.MOBILE,
      ),
    )
  }

  @Test
  fun `the builder library carries the android-dwf backend through unchanged`() {
    val surfaces =
      UiBuilderPreviewSurfaces.from(
        buildJsonObject {
          put(
            "previewSurfaces",
            buildJsonObject {
              put(
                "native",
                buildJsonObject {
                  put("fidelity", "authoritative")
                  put("backend", "android-dwf")
                },
              )
            },
          )
        },
      )
    assertEquals("android-dwf", surfaces.native.backend)
  }

  @Test
  fun `the compose native lane refuses a watch-face design by name before generating anything`() {
    val lane =
      ServeUiBuilderNativePreview(
        executor =
          ScreenGeneratorComposeExportExecutor({ ComponentRecordSource.Lookup.Unconfigured }),
        compile = { error("a watch face must never reach the Compose compiler") },
        isDwfCatalog = { it == "wear-wff" },
      )
    val refused =
      assertIs<UiBuilderNativePreviewOutcome.Refused>(lane.render(document(catalog = "wear-wff")))
    assertEquals(DwfPreviewSurface.SEPARATE_SURFACE, refused.code)
    assertTrue(refused.reasons.single().contains("dwf-preview"))
  }

  private fun document(catalog: String = "m3-catalog") =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "watch-1",
      title = "Watch face",
      revision = 0,
      catalogPin = CatalogReferenceV1(catalog, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 227,
          heightDp = 227,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("12:00")),
            ),
        ),
    )
}
