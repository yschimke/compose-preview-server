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
import kotlinx.coroutines.async
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
  private lateinit var service: PersistentUiBuilderService
  private lateinit var dwfPreview: UiBuilderDwfPreview

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
    pinned: Map<String, ByteArray> = emptyMap(),
    validator: WatchFaceValidator? = null,
    featureFlags: ServeFeatureFlags = ServeFeatureFlags(nativePreview = true),
    validation: WatchFaceValidation? = null,
  ): ServeHttpServer {
    runtime.availability = availability
    val assets = FileUiBuilderAssetStore(directory.resolve("assets"))
    service =
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
    dwfPreview =
      UiBuilderDwfPreview(
        sessions = DwfPreviewSessions(runtime),
        isDwfCatalog = { it == dwfCatalog },
        tickMillis = tickMillis,
        animatedTickMillis = animatedTickMillis,
        sourceFor = sourceFor,
        assets = assets,
        validation =
          validation
            ?: WatchFaceValidation(
              {
                validator?.let(WatchFaceValidatorAvailability::Ready)
                  ?: WatchFaceValidatorAvailability.Unavailable(
                    "NO_VALIDATOR",
                    "not in this test",
                  )
              },
            ),
      )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "local-fixture",
        sessions = ServeSessionRegistry(open = { null }).also(registries::add),
        defaultSessionId = "unused",
        machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
        uiBuilderService = service,
        uiBuilderDwfPreview = dwfPreview,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization { _, _, _ ->
            UiBuilderAuthorizationDecision.Authorized(currentActor)
          },
        featureFlags = featureFlags,
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
  fun `with the Native preview switched off the runtime is never started and the pane says why`() {
    start(featureFlags = ServeFeatureFlags(nativePreview = false))
    val status = obj(get("$base/status").second)
    assertEquals(false, status["available"]!!.jsonPrimitive.boolean)
    assertEquals(
      ServeFeatureFlags.NATIVE_PREVIEW_DISABLED,
      status["code"]!!.jsonPrimitive.content,
    )
    val (code, body) = post(base, """{"wffXml":"<WatchFace/>"}""")
    assertEquals(503, code)
    assertEquals(
      ServeFeatureFlags.NATIVE_PREVIEW_DISABLED,
      obj(body)["code"]!!.jsonPrimitive.content,
    )
    assertTrue(runtime.packaged.isEmpty(), "nothing was packaged")
    assertTrue(runtime.connections.isEmpty(), "the runtime was never started")
  }

  @Test
  fun `with the Native preview switched off an async start is refused before any session exists`() {
    start(featureFlags = ServeFeatureFlags(nativePreview = false))
    // Both the editor-sent and the server-derived async starts, which would otherwise answer 202
    // and package, queue or boot in the background.
    for (body in listOf("""{"wffXml":"<WatchFace/>","async":true}""", """{"async":true}""")) {
      val (code, answer) = post(base, body)
      assertEquals(503, code, answer)
      assertEquals(
        ServeFeatureFlags.NATIVE_PREVIEW_DISABLED,
        obj(answer)["code"]!!.jsonPrimitive.content,
      )
    }
    assertEquals(0, obj(get("$base/status").second)["sessions"]!!.jsonPrimitive.int)
    assertTrue(runtime.packaged.isEmpty(), "nothing was packaged")
    assertTrue(runtime.connections.isEmpty(), "the runtime was never started")
  }

  @Test
  fun `feature flags are off unless set to 1 or true`() {
    assertEquals(
      ServeFeatureFlags(false, false, false),
      ServeFeatureFlags.fromEnvironment(emptyMap()),
    )
    assertEquals(ServeFeatureFlags(false, false, false), ServeFeatureFlags())
    assertEquals(
      ServeFeatureFlags(false, false, false),
      ServeFeatureFlags.fromEnvironment(
        mapOf(
          ServeFeatureFlags.NATIVE_PREVIEW_ENV to "",
          ServeFeatureFlags.WATCH_FACE_VALIDATION_ENV to "false",
          ServeFeatureFlags.CATALOG_NATIVE_RENDER_ENV to "0",
        ),
      ),
    )
    assertEquals(
      ServeFeatureFlags(nativePreview = true, watchFaceValidation = false),
      ServeFeatureFlags.fromEnvironment(
        mapOf(
          ServeFeatureFlags.NATIVE_PREVIEW_ENV to "1",
          ServeFeatureFlags.WATCH_FACE_VALIDATION_ENV to "yes",
        ),
      ),
    )
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
  fun `a design recreated under the same id never reads the old one's report`() {
    val validation =
      WatchFaceValidation(
        { WatchFaceValidatorAvailability.Ready { packagerReport(passed = true) } },
      )
    val source = DwfSource(wffXml = "<WatchFace/>")
    runBlocking { validation.validate("watch-1", 100L, 3, source, actor.actorId) }
    assertNotNull(validation.latest("watch-1", 100L, 3))
    assertNull(validation.latest("watch-1", 200L, 3), "another design at the same id and revision")
  }

  @Test
  fun `with the flag off the validators are never looked for, and the route says so`() {
    var looked = 0
    val off =
      WatchFaceValidatorAvailability.production(
        home = directory.toFile(),
        enabled = { false },
        sdk = {
          looked++
          null
        },
        libDir = {
          looked++
          null
        },
      )
    val answer = assertIs<WatchFaceValidatorAvailability.Unavailable>(off())
    assertEquals(WatchFaceValidatorAvailability.DISABLED, answer.code)
    assertEquals(0, looked)
    val on =
      WatchFaceValidatorAvailability.production(
        home = directory.toFile(),
        enabled = { true },
        sdk = { null },
        libDir = { null },
      )
    assertEquals(
      DwfPreviewSurface.ANDROID_SDK_MISSING,
      assertIs<WatchFaceValidatorAvailability.Unavailable>(on()).code,
    )
  }

  @Test
  fun `validation runs one packager at a time and refuses past a short queue`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val running = java.util.concurrent.atomic.AtomicInteger()
    val most = java.util.concurrent.atomic.AtomicInteger()
    val validation =
      WatchFaceValidation(
        {
          WatchFaceValidatorAvailability.Ready {
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            release.await(10, TimeUnit.SECONDS)
            running.decrementAndGet()
            packagerReport(passed = true)
          }
        },
        maxQueued = 1,
      )
    fun face(n: Int) = DwfSource(wffXml = "<WatchFace id=\"$n\"/>")
    runBlocking {
      val first =
        async(kotlinx.coroutines.Dispatchers.Default) {
          validation.validate("watch-1", 1L, 1, face(1), "a")
        }
      val second =
        async(kotlinx.coroutines.Dispatchers.Default) {
          validation.validate("watch-1", 1L, 2, face(2), "b")
        }
      // Both admitted (one running, one waiting): the third is told to come back.
      val deadline = System.currentTimeMillis() + 10_000
      while (validation.pending < 2 && System.currentTimeMillis() < deadline) {
        kotlinx.coroutines.delay(10)
      }
      assertEquals(2, validation.pending)
      val busy =
        assertIs<WatchFaceValidation.Result.Busy>(
          validation.validate("watch-1", 1L, 3, face(3), "c"),
        )
      assertEquals(WatchFaceValidation.BUSY, busy.code)
      assertTrue(busy.retryAfterSeconds > 0)
      release.countDown()
      assertIs<WatchFaceValidation.Result.Ok>(first.await())
      assertIs<WatchFaceValidation.Result.Ok>(second.await())
    }
    assertEquals(1, most.get(), "never two packager JVMs at once")
  }

  @Test
  fun `each account has a few validations per window, and a cached report costs none`() {
    var now = 1_000_000L
    var runs = 0
    val validation =
      WatchFaceValidation(
        {
          WatchFaceValidatorAvailability.Ready {
            runs++
            packagerReport(passed = true)
          }
        },
        clock = { now },
        perActorRuns = 2,
        perActorWindowMillis = 60_000,
      )
    fun face(n: Int) = DwfSource(wffXml = "<WatchFace id=\"$n\"/>")
    runBlocking {
      assertIs<WatchFaceValidation.Result.Ok>(validation.validate("w", 1L, 1, face(1), "a"))
      assertIs<WatchFaceValidation.Result.Ok>(validation.validate("w", 1L, 2, face(2), "a"))
      // The same face again is the cache: no run, no count.
      assertIs<WatchFaceValidation.Result.Ok>(validation.validate("w", 1L, 2, face(2), "a"))
      val limited =
        assertIs<WatchFaceValidation.Result.RateLimited>(
          validation.validate("w", 1L, 3, face(3), "a"),
        )
      assertEquals(WatchFaceValidation.RATE_LIMITED, limited.code)
      assertEquals(60L, limited.retryAfterSeconds)
      // Another account is not held back by this one.
      assertIs<WatchFaceValidation.Result.Ok>(validation.validate("w", 1L, 3, face(3), "b"))
      now += 60_000
      assertIs<WatchFaceValidation.Result.Ok>(validation.validate("w", 1L, 4, face(4), "a"))
    }
    assertEquals(4, runs)
  }

  @Test
  fun `requests that wait for the same face and are answered from cache cost no allowance`() {
    val release = java.util.concurrent.CountDownLatch(1)
    var runs = 0
    val validation =
      WatchFaceValidation(
        {
          WatchFaceValidatorAvailability.Ready {
            runs++
            release.await(10, TimeUnit.SECONDS)
            packagerReport(passed = true)
          }
        },
        perActorRuns = 2,
      )
    val face = DwfSource(wffXml = "<WatchFace id=\"same\"/>")
    runBlocking {
      // Two tabs ask for the same uncached face at once: one runs, the other waits for it.
      val first =
        async(kotlinx.coroutines.Dispatchers.Default) { validation.validate("w", 1L, 1, face, "a") }
      val second =
        async(kotlinx.coroutines.Dispatchers.Default) { validation.validate("w", 1L, 1, face, "a") }
      val deadline = System.currentTimeMillis() + 10_000
      while (validation.pending < 2 && System.currentTimeMillis() < deadline) {
        kotlinx.coroutines.delay(10)
      }
      release.countDown()
      assertIs<WatchFaceValidation.Result.Ok>(first.await())
      assertIs<WatchFaceValidation.Result.Ok>(second.await())
      assertEquals(1, runs)
      // One run was charged, so one more face still fits the allowance of two.
      assertIs<WatchFaceValidation.Result.Ok>(
        validation.validate("w", 1L, 2, DwfSource(wffXml = "<WatchFace id=\"next\"/>"), "a"),
      )
    }
    assertEquals(2, runs)
  }

  @Test
  fun `a rate-limited validation is a 429 with Retry-After`() {
    start(
      validation =
        WatchFaceValidation(
          { WatchFaceValidatorAvailability.Ready { packagerReport(passed = true) } },
          perActorRuns = 1,
        ),
    )
    assertEquals(200, post(validation, """{"wffXml":"<WatchFace/>"}""").first)
    client
      .newCall(
        Request.Builder()
          .url(url(validation))
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .post(
            """{"wffXml":"<WatchFace id=\"2\"/>"}"""
              .toRequestBody("application/json".toMediaType()),
          )
          .build(),
      )
      .execute()
      .use {
        assertEquals(429, it.code)
        assertTrue(it.header("Retry-After")!!.toLong() > 0)
        assertEquals(
          WatchFaceValidation.RATE_LIMITED,
          obj(it.body.string())["code"]!!.jsonPrimitive.content,
        )
      }
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
  fun `the MCP tool returns the same report, exists only with the lane, and is an export`() {
    start(
      sourceFor = { DwfSource(wffXml = "<WatchFace/>", label = "derived") },
      validator = { packagerReport(passed = false) },
    )
    val mcp = ServeUiBuilderMcp(service, watchFaces = dwfPreview)
    assertEquals(
      UiBuilderRouteCapability.EXPORT,
      mcp.capabilityFor(ServeUiBuilderMcp.VALIDATE_WATCH_FACE),
    )
    assertNull(ServeUiBuilderMcp(service).capabilityFor(ServeUiBuilderMcp.VALIDATE_WATCH_FACE))
    val reply = runBlocking {
      mcp.call(
        ServeUiBuilderMcp.VALIDATE_WATCH_FACE,
        buildJsonObject { put("designId", "watch-1") },
        actor,
        callId = "t",
      )
    }
    val report = obj(reply)
    assertEquals(false, report["passed"]!!.jsonPrimitive.boolean)
    assertEquals("watch-1", report["designId"]!!.jsonPrimitive.content)
    assertFalse(reply.contains("SECRET-TOKEN"))
    // The HTTP GET reads the report the tool ran.
    assertEquals(200, get(validation).first)

    val declared =
      ServeUiBuilderMcp.declarations(
        { name, description, _ ->
          buildJsonObject {
            put("name", name)
            put("description", description)
          }
        },
        watchFacePush = true,
        watchFaceValidation = true,
      )
    val names = declared.map { it["name"]!!.jsonPrimitive.content }
    assertContains(names, ServeUiBuilderMcp.VALIDATE_WATCH_FACE)
    val publish = declared.first {
      it["name"]!!.jsonPrimitive.content == ServeUiBuilderMcp.PUBLISH_WATCH_FACE
    }
    assertContains(publish["description"]!!.jsonPrimitive.content, "run ui_builder_validate")
    assertFalse(
      ServeUiBuilderMcp.declarations({ name, _, _ -> buildJsonObject { put("name", name) } }).any {
        it["name"]!!.jsonPrimitive.content == ServeUiBuilderMcp.VALIDATE_WATCH_FACE
      },
    )
  }

  @Test
  fun `the MCP tool refuses a design that is not a watch face`() {
    start(dwfCatalog = "some-other-catalog", validator = { packagerReport(passed = true) })
    val mcp = ServeUiBuilderMcp(service, watchFaces = dwfPreview)
    val refused = runCatching {
      runBlocking {
        mcp.call(
          ServeUiBuilderMcp.VALIDATE_WATCH_FACE,
          buildJsonObject { put("designId", "watch-1") },
          actor,
          callId = "t",
        )
      }
    }
    assertContains(refused.exceptionOrNull()?.message ?: refused.getOrThrow(), "watch faces")
  }

  private val estimateJson =
    """{"schemaVersion":1,"mode":"estimate","passed":false,"checks":[""" +
      """{"id":"wff-xsd","title":"Schema","status":"skipped","findings":[""" +
      """{"severity":"warning","message":"Not run: estimate only"}]},""" +
      """{"id":"memory-footprint","title":"Memory footprint (estimate)","status":"failed",""" +
      """"findings":[{"severity":"error","message":"ambient over budget",""" +
      """"suggestion":"hide case_steel in ambient"}],""" +
      """"memory":{"totalBytes":5000000,"maxActiveBytes":4194304,"maxAmbientBytes":4194304,""" +
      """"activeLimitBytes":10000000,"ambientLimitBytes":4000000},""" +
      """"contributors":[{"resource":"drawable/case_steel","kind":"image","width":1024,""" +
      """"height":1024,"format":"png","fileBytes":81234,"decodedBytes":4194304,""" +
      """"activeBytes":4194304,"ambientBytes":4194304,"renderedMaxWidth":450,""" +
      """"renderedMaxHeight":450,"missing":false,"suggestion":"downscale to 450x450"}]}]}"""

  @Test
  fun `the cost summary keeps the budgets, the top contributors and the errors`() {
    val summary =
      WatchFaceCostEstimator.summarize(
        7,
        Json { ignoreUnknownKeys = true }
          .decodeFromString(WatchFacePackagerReportV1.serializer(), estimateJson),
      )
    assertEquals(7, summary.revision)
    assertFalse(summary.passed)
    assertEquals(104, summary.ambient!!.percentOfLimit)
    assertEquals(41, summary.active!!.percentOfLimit)
    val top = summary.topContributors.single()
    assertEquals("drawable/case_steel", top.resource)
    assertEquals(104, top.percentOfAmbientLimit)
    assertEquals("downscale to 450x450", top.suggestion)
    assertEquals("hide case_steel in ambient", summary.errors.single().suggestion)
  }

  @Test
  fun `a watch-face edit over MCP replies with its cost, and nothing runs the packager`() {
    var estimated = 0
    start(
      sourceFor = { DwfSource(wffXml = imageFace, label = "derived") },
      validator = { error("the full validator must not run on an edit") },
    )
    val mcp =
      ServeUiBuilderMcp(
        service,
        watchFaces = dwfPreview,
        assets = service,
        watchFaceCost =
          WatchFaceCostEstimator { files ->
            estimated++
            assertContentEquals(PNG, files["res/drawable/case_steel.png"])
            estimateJson
          },
      )
    val reply = runBlocking {
      mcp.call(
        ServeUiBuilderMcp.PUT_ASSET,
        buildJsonObject {
          put("designId", "watch-1")
          put("assetKey", "case_steel")
          put("imageBase64", Base64.getEncoder().encodeToString(PNG))
        },
        actor,
        callId = "t",
      )
    }
    val cost = obj(reply)[ServeUiBuilderMcp.WATCH_FACE_COST_KEY]!!.jsonObject
    assertEquals("estimate", cost["mode"]!!.jsonPrimitive.content)
    assertEquals(
      "drawable/case_steel",
      cost["topContributors"]!!.jsonArray.single().jsonObject["resource"]!!.jsonPrimitive.content,
    )
    assertEquals(1, estimated)
    // A read is not an edit, and carries no cost.
    val read = runBlocking {
      mcp.call(
        ServeUiBuilderMcp.GET_DESIGN,
        buildJsonObject { put("designId", "watch-1") },
        actor,
        callId = "t",
      )
    }
    assertNull(obj(read)[ServeUiBuilderMcp.WATCH_FACE_COST_KEY])
    assertEquals(1, estimated)
  }

  @Test
  fun `the real estimate reads a pinned bitmap and flags a missing one`() {
    val withAsset =
      start(sourceFor = { DwfSource(wffXml = imageFace) }, pinned = mapOf("case_steel" to PNG))
    val document = runBlocking {
      (service.execute(
          UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetSnapshot("watch-1", null)),
        ) as UiBuilderServiceResponse.Snapshot)
        .snapshot
        .state
        .document
    }
    withAsset.stop()
    val estimator = WatchFaceCostEstimator()
    val present = assertNotNull(estimator.summary(dwfPreview, document))
    assertEquals("estimate", present.mode)
    assertNotNull(present.active)
    assertTrue(
      present.topContributors.any { it.resource.endsWith("case_steel") && !it.missing },
      present.toString(),
    )
    val bare =
      UiBuilderDwfPreview(
        sessions = DwfPreviewSessions(runtime),
        isDwfCatalog = { true },
        sourceFor = { DwfSource(wffXml = imageFace) },
      )
    val missing = assertNotNull(estimator.summary(bare, document.copy(assets = emptyMap())))
    assertFalse(missing.passed, missing.toString())
    assertTrue(missing.errors.isNotEmpty(), missing.toString())
  }

  /** A new revision of the design, as the editor's next save would make. */
  private fun editDesign(text: String) = runBlocking {
    val opened =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        service.execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.OpenDesign("watch-1"))),
      )
    val edited =
      service.execute(
        UiBuilderServiceCall(
          actor,
          UiBuilderServiceRequest.ApplyOperation(
            UiBuilderSubmission.Batch(
              "watch-1",
              "edit-$text",
              "test",
              opened.snapshot.state.document.revision,
              listOf(SetPropertyMutationV1("text", "text", StringValueV1(text))),
            ),
          ),
        ),
      )
    assertIs<AcceptedOutcomeV1>(assertIs<UiBuilderServiceResponse.OperationOutcome>(edited).outcome)
  }

  @Test
  fun `an edit never repackages a running session, which reports itself stale until a refresh`() {
    start(sourceFor = { DwfSource(wffXml = "<WatchFace/>", label = "derived") })
    val (code, body) = post(base, "{}")
    assertEquals(201, code, body)
    val started = obj(body)
    val sessionId = started["sessionId"]!!.jsonPrimitive.content
    val revision = started["revision"]!!.jsonPrimitive.long
    assertEquals(1, runtime.packaged.size)

    editDesign("edited")
    // Nothing repackaged or restarted on the edit, and reading the status does not either.
    val (statusCode, statusBody) = get("$base/$sessionId")
    assertEquals(200, statusCode, statusBody)
    val status = obj(statusBody)
    assertEquals(true, status["stale"]!!.jsonPrimitive.boolean)
    assertEquals(revision, status["revision"]!!.jsonPrimitive.long)
    assertEquals(revision + 1, status["designRevision"]!!.jsonPrimitive.long)
    assertEquals(1, runtime.packaged.size)
    assertTrue(runtime.connections.isEmpty(), "the edit booted nothing")

    // The editor's one check after an edit settles: the design status route, for its session.
    val (designCode, designBody) = get("$base/status?session=$sessionId")
    assertEquals(200, designCode, designBody)
    val design = obj(designBody)
    assertEquals(true, design["stale"]!!.jsonPrimitive.boolean)
    assertEquals(revision, design["revision"]!!.jsonPrimitive.long)
    assertEquals(revision + 1, design["currentRevision"]!!.jsonPrimitive.long)
    // Without `session`, or for one that is not the caller's, the status is what it was.
    assertNull(obj(get("$base/status").second)["stale"])
    currentActor = "someone-else"
    val (otherCode, otherBody) = get("$base/status?session=$sessionId")
    assertTrue(otherCode == 404 || obj(otherBody)["stale"] == null, otherBody)
    currentActor = actor.actorId
    assertEquals(1, runtime.packaged.size)

    // "Refresh" is an explicit start: a new session on the current revision, not stale.
    val (refreshCode, refreshBody) = post(base, "{}")
    assertEquals(201, refreshCode, refreshBody)
    val refreshed = obj(refreshBody)["sessionId"]!!.jsonPrimitive.content
    val fresh = obj(get("$base/$refreshed").second)
    assertEquals(false, fresh["stale"]!!.jsonPrimitive.boolean)
    assertEquals(revision + 1, fresh["revision"]!!.jsonPrimitive.long)
    assertEquals(
      false,
      obj(get("$base/status?session=$refreshed").second)["stale"]!!.jsonPrimitive.boolean,
    )
    // The face did not change, so the refresh asked for the same package (cached by digest in
    // the real runtime: RenderHostDwfApkRuntimeTest).
    assertEquals(2, runtime.packaged.size)
    assertEquals(runtime.packaged[0].digest(), runtime.packaged[1].digest())
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
