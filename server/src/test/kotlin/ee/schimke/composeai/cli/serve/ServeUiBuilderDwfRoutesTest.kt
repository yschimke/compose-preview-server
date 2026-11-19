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
    sourceFor: (ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1) -> DwfSource? = { null },
    pinned: Map<String, ByteArray> = emptyMap(),
    validator: WatchFaceValidator? = null,
  ): ServeHttpServer {
    runtime.availability = availability
    val assets = FileUiBuilderAssetStore(directory.resolve("assets"))
    val service =
      PersistentUiBuilderService(
          storage = FileUiBuilderStateStorage(directory),
          limits = UiBuilderServiceLimits(),
          catalogs = benchmarkCatalogExecutor(),
          exporter = UiBuilderExportExecutor { error("no export in this test") },
          assets = assets,
        )
        .also {
          runBlocking {
            it.execute(
              UiBuilderServiceCall(actor, UiBuilderServiceRequest.CreateDesign(document())),
            )
            for ((key, bytes) in pinned) {
              assertIs<UiBuilderServiceResponse.OperationOutcome>(
                it.putAsset(UiBuilderAssetWrite(actor, "watch-1", key, bytes)),
              )
            }
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
            sourceFor = sourceFor,
            assets = assets,
            validation =
              WatchFaceValidation(
                {
                  validator?.let(WatchFaceValidatorAvailability::Ready)
                    ?: WatchFaceValidatorAvailability.Unavailable(
                      "NO_VALIDATOR",
                      "not in this test",
                    )
                },
              ),
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

  /** A face drawing one bitmap, `case_steel`, as the editor sends it: XML and no assets. */
  private val imageFace =
    """<WatchFace width="450" height="450"><Scene><PartImage x="0" y="0" width="450" """ +
      """height="450"><Image resource="case_steel"/></PartImage></Scene></WatchFace>"""

  private fun startFace(body: String) =
    post(
      base,
      buildJsonObject { put("wffXml", imageFace) }
        .let {
          if (body.isEmpty()) it.toString()
          else JsonObject(it + Json.parseToJsonElement(body).jsonObject).toString()
        },
    )

  @Test
  fun `a design's pinned assets are packaged as the drawables its XML names`() {
    start(pinned = mapOf("case_steel" to PNG))
    val (code, body) = startFace("")
    assertEquals(201, code, body)
    val packaged = runtime.packaged.last()
    assertContentEquals(PNG, packaged.assets["drawable/case_steel.png"])
  }

  @Test
  fun `an asset the request supplies wins over the pinned one at the same path`() {
    start(pinned = mapOf("case_steel" to PNG))
    val sent = byteArrayOf(9, 9, 9)
    val (code, body) =
      startFace(
        buildJsonObject {
          put(
            "assets",
            buildJsonObject {
              put("drawable/case_steel.png", Base64.getEncoder().encodeToString(sent))
            },
          )
        }
          .toString(),
      )
    assertEquals(201, code, body)
    assertContentEquals(sent, runtime.packaged.last().assets["drawable/case_steel.png"])
  }

  @Test
  fun `a request asset replaces a pinned one of the same resource under another extension`() {
    val merged =
      WatchFaceDrawables.merged(
        pinned =
          mapOf("drawable/logo.png" to byteArrayOf(1), "drawable-hdpi/logo.png" to byteArrayOf(2)),
        requested = mapOf("drawable/logo.webp" to byteArrayOf(3)),
      )
    // One `drawable/logo` (the request's), and the other density's variant kept.
    assertEquals(setOf("drawable/logo.webp", "drawable-hdpi/logo.png"), merged.keys)
  }

  @Test
  fun `a bitmap the face names and nothing supplies is refused before packaging`() {
    start()
    val (code, body) = startFace("")
    assertEquals(422, code, body)
    val refusal = obj(body)
    assertEquals(WatchFaceDrawables.MISSING_RESOURCE, refusal["code"]!!.jsonPrimitive.content)
    assertContains(refusal["reasons"]!!.jsonArray.single().jsonPrimitive.content, "case_steel")
    assertTrue(runtime.packaged.isEmpty(), "nothing reaches the packager")
  }

  private val validation = "/api/ui-builder/v1/designs/watch-1/watchface-validation"

  private fun packagerReport(passed: Boolean) =
    """{"schemaVersion":1,"wffVersion":4,"minimumWffVersion":2,"passed":$passed,""" +
      """"validationToken":"SECRET-TOKEN","checks":[{"id":"wff-xsd","title":"Schema",""" +
      """"status":"${if (passed) "passed" else "failed"}","findings":""" +
      (if (passed) "[]" else """[{"severity":"error","message":"bad","line":3,"column":7}]""") +
      """},{"id":"push","title":"Watch Face Push","status":"passed","tokenIssued":true,"passedChecks":["a","b"],""" +
      """"token":"SECRET-TOKEN","memory":{"totalBytes":10,"maxActiveBytes":5,""" +
      """"maxAmbientBytes":2,"activeLimitBytes":100,"ambientLimitBytes":50},""" +
      """"contributors":[{"resource":"drawable/case_steel","kind":"image","width":1024,""" +
      """"height":1024,"format":"png","fileBytes":81234,"decodedBytes":4194304,""" +
      """"activeBytes":4194304,"ambientBytes":0,"suggestion":"downscale"}]}],"mode":"full"}"""

  @Test
  fun `validation returns the packager's report with the face it is on, never the token`() {
    val seen = mutableListOf<Map<String, ByteArray>>()
    start(
      validator = { files ->
        seen += files
        packagerReport(passed = true)
      },
    )
    assertEquals(404, get(validation).first, "nothing validated yet")
    val (code, body) = post(validation, """{"wffXml":"<WatchFace/>"}""")
    assertEquals(200, code, body)
    assertFalse(body.contains("SECRET-TOKEN"), body)
    val report = obj(body)
    assertEquals(true, report["passed"]!!.jsonPrimitive.boolean)
    assertEquals("watch-1", report["designId"]!!.jsonPrimitive.content)
    assertEquals(64, report["digest"]!!.jsonPrimitive.content.length)
    assertTrue(report["ranAtEpochMillis"]!!.jsonPrimitive.long > 0)
    val push = report["checks"]!!.jsonArray[1].jsonObject
    assertEquals(true, push["tokenIssued"]!!.jsonPrimitive.boolean)
    assertEquals(100, push["memory"]!!.jsonObject["activeLimitBytes"]!!.jsonPrimitive.int)
    assertEquals(
      listOf("a", "b"),
      push["passedChecks"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    val contributor = push["contributors"]!!.jsonArray.single().jsonObject
    assertEquals("downscale", contributor["suggestion"]!!.jsonPrimitive.content)
    assertEquals("full", report["mode"]!!.jsonPrimitive.content)
    assertEquals("<WatchFace/>", seen.single()["res/raw/watchface.xml"]!!.decodeToString())
    // The same face again is the cached report, and GET reads it back.
    assertEquals(body, post(validation, """{"wffXml":"<WatchFace/>"}""").second)
    assertEquals(1, seen.size)
    val (getCode, getBody) = get(validation)
    assertEquals(200, getCode)
    assertEquals(body, getBody)
  }

  @Test
  fun `a failing face is a 200 report, not an error`() {
    start(validator = { packagerReport(passed = false) })
    val (code, body) = post(validation, """{"wffXml":"<WatchFace/>"}""")
    assertEquals(200, code, body)
    val report = obj(body)
    assertEquals(false, report["passed"]!!.jsonPrimitive.boolean)
    val finding = report["checks"]!!.jsonArray[0].jsonObject["findings"]!!.jsonArray.single()
    assertEquals(3, finding.jsonObject["line"]!!.jsonPrimitive.int)
  }

  @Test
  fun `a bitmap nothing supplies is a failed check, and the packager is not run`() {
    var ran = 0
    start(
      validator = {
        ran++
        packagerReport(passed = true)
      },
    )
    val (code, body) = post(validation, buildJsonObject { put("wffXml", imageFace) }.toString())
    assertEquals(200, code, body)
    val report = obj(body)
    assertEquals(false, report["passed"]!!.jsonPrimitive.boolean)
    val check = report["checks"]!!.jsonArray.single().jsonObject
    assertEquals(WatchFaceValidation.MISSING_RESOURCE_CHECK, check["id"]!!.jsonPrimitive.content)
    assertContains(check["findings"]!!.jsonArray.single().toString(), "case_steel")
    assertEquals(0, ran)
  }

  @Test
  fun `validation packages the design's pinned drawables`() {
    val seen = mutableListOf<Map<String, ByteArray>>()
    start(
      pinned = mapOf("case_steel" to PNG),
      validator = { files ->
        seen += files
        packagerReport(passed = true)
      },
    )
    val (code, body) = post(validation, buildJsonObject { put("wffXml", imageFace) }.toString())
    assertEquals(200, code, body)
    assertContentEquals(PNG, seen.single()["res/drawable/case_steel.png"])
  }

  @Test
  fun `a packager refusal before any report is a failed check with its diagnostics`() {
    start(validator = { throw DwfPackagingFailed(listOf("error[aapt2]: link failed")) })
    val (code, body) = post(validation, """{"wffXml":"<WatchFace/>"}""")
    assertEquals(200, code, body)
    val check = obj(body)["checks"]!!.jsonArray.single().jsonObject
    assertEquals(WatchFaceValidation.PACKAGER_CHECK, check["id"]!!.jsonPrimitive.content)
    val finding = check["findings"]!!.jsonArray.single().jsonObject
    assertEquals("link failed", finding["message"]!!.jsonPrimitive.content)
    assertEquals("aapt2", finding["path"]!!.jsonPrimitive.content)
  }

  @Test
  fun `validation without a validator is a 503, and an unusable request a 422`() {
    start()
    assertEquals(503, post(validation, """{"wffXml":"<WatchFace/>"}""").first)
    assertEquals(422, post(validation, "{}").first)
    assertEquals(400, post(validation, "not json").first)
  }

  @Test
  fun `a design that is not a watch face derives no source`() {
    assertEquals(null, deriveDwfSource(document()))
  }

  @Test
  fun `a started session streams frames and maps controls onto daemon commands`() {
    start()
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
        sent.map { it.cmd }.filter { it != "screenshot" && it != "watchface" },
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
      socket.send("""{"type":"controls","controls":{"timeMillis":1000,"ticking":true}}""")
      var last = 0
      repeat(4) { last = frames.poll(10, TimeUnit.SECONDS) ?: fail("ticking stopped") }
      assertTrue(last >= 4)
      val clocks = runtime.commands.filter { it.cmd == "clock" }
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

  private companion object {
    /** A real 1x1 PNG: the asset port sniffs uploads and refuses anything else. */
    val PNG: ByteArray =
      java.io
        .ByteArrayOutputStream()
        .also {
          javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
            "png",
            it,
          )
        }
        .toByteArray()
  }
}
