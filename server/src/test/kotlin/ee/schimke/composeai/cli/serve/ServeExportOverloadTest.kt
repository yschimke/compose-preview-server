package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

class ServeExportOverloadTest {
  @TempDir lateinit var directory: Path
  private val actor = AuthenticatedUiBuilderActor("load-fixture")
  private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
  private val registries = mutableListOf<ServeSessionRegistry>()

  @AfterTest
  fun closeRegistries() {
    registries.forEach { it.close() }
  }

  @Test
  fun `REST and MCP shed a concurrent export burst and recover without blocking reads`() {
    val entered = CountDownLatch(4)
    val release = CountDownLatch(1)
    val exporter = UiBuilderExportExecutor { request ->
      entered.countDown()
      check(release.await(10, TimeUnit.SECONDS))
      artifact(request.format)
    }
    val service = service(exporter)
    val thumbnails = ServeUiBuilderThumbnails(directory.resolve("thumbnails"), "load-fixture")
    val server = server(thumbnails.warming(service), thumbnails = thumbnails)
    val workers = Executors.newFixedThreadPool(4)
    try {
      val held = (0..3).map { workers.submit<Int> { get(server, "export.compose").first } }
      assertTrue(entered.await(5, TimeUnit.SECONDS), "four runtime workers must be occupied")
      val start = System.nanoTime()
      repeat(64) {
        val (status, body, retry) = get(server, "export.${listOf("compose", "png", "svg")[it % 3]}")
        assertEquals(503, status, body)
        assertEquals("1", retry)
        assertEquals("concurrent export limit reached", body)
        val mcp = call(server, "ui_builder_export", """{"designId":"load-design","format":"png"}""")
        assertEquals(true, mcp["result"]!!.jsonObject["isError"]!!.jsonPrimitive.boolean)
        val structured = mcp["result"]!!.jsonObject["structuredContent"]!!.jsonObject
        assertEquals("export_overloaded", structured["code"]!!.jsonPrimitive.content)
        assertEquals(true, structured["retryable"]!!.jsonPrimitive.boolean)
        assertEquals(1, structured["retryAfterSeconds"]!!.jsonPrimitive.int)
      }
      assertTrue(
        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 5,
        "overload must fail fast",
      )
      assertNotNull(
        call(server, "ui_builder_get_design", """{"designId":"load-design"}""")["result"]
      )
      assertNotNull(post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"])
      assertEquals(400, get(server, "export.png?revision=invalid").first)
      val envelope =
        HttpRequestEnvelopeV1(
          requestId = "overload",
          actorId = actor.actorId,
          request = ExportDesignRequestV1("load-design", null, ExportFormatV1.PNG),
        )
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
            .post(
              Json.encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
                .toRequestBody("application/json".toMediaType())
            )
            .build()
        )
        .execute()
        .use {
          val body = it.body.string()
          assertEquals(503, it.code, body)
          assertEquals("1", it.header("Retry-After"))
          val error =
            Json.parseToJsonElement(body).jsonObject["response"]!!.jsonObject["error"]!!.jsonObject
          assertEquals("internal", error["code"]!!.jsonPrimitive.content)
          assertEquals(true, error["retryable"]!!.jsonPrimitive.boolean)
        }
      assertEquals(503, get(server, "thumbnail.png").first)
      assertEquals(4, service.diagnostics().activeExports)
      assertEquals(4L, service.diagnostics().peakExports)
      release.countDown()
      held.forEach { assertEquals(200, it.get(5, TimeUnit.SECONDS)) }
      assertEquals(0, service.diagnostics().activeExports)
      assertEquals(200, get(server, "export.compose").first)
      assertEquals(200, get(server, "export.png").first)
      assertNull(
        call(server, "ui_builder_export", """{"designId":"load-design"}""")["result"]!!
          .jsonObject["isError"]
      )
    } finally {
      release.countDown()
      workers.shutdownNow()
      server.stop()
      thumbnails.close()
    }
  }

  @Test
  fun `only exact retryable admission errors map to overload`() {
    for (message in
      listOf("concurrent export limit reached", "concurrent export worker limit reached")) {
      val error =
        UiBuilderServiceResponse.Error(
          UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, message, retryable = true)
        )
      assertEquals(503, error.httpStatusValue())
      assertEquals(400, error.copy(error = error.error.copy(retryable = false)).httpStatusValue())
      assertEquals(
        500,
        error.copy(error = error.error.copy(code = ServiceErrorCodeV1.INTERNAL)).httpStatusValue(),
      )
    }
    assertEquals(
      400,
      UiBuilderServiceResponse.Error(
          UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid format", retryable = true)
        )
        .httpStatusValue(),
    )
    assertEquals(
      500,
      UiBuilderServiceResponse.Error(
          UiBuilderServiceError(ServiceErrorCodeV1.INTERNAL, "export timed out", retryable = true)
        )
        .httpStatusValue(),
    )
  }

  @Test
  fun `genuine exporter failures remain internal errors and invalid requests remain client errors`() {
    val server = server(service(UiBuilderExportExecutor { error("fixture exporter failed") }))
    try {
      val (status, body, retry) = get(server, "export.png")
      assertEquals(500, status, body)
      assertTrue(body.contains("fixture exporter failed"), body)
      assertNull(retry)
      assertEquals(400, get(server, "export.compose?revision=-1").first)
      val mcp = call(server, "ui_builder_export", """{"designId":"load-design"}""")
      val envelope = mcp["result"]!!.jsonObject["structuredContent"]!!.jsonObject
      assertEquals(
        "internal",
        envelope["response"]!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content,
      )
    } finally {
      server.stop()
    }
  }

  @Test
  fun `native admission releases capacity after exceptions and overloads`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val native =
      BoundedUiBuilderNativePreview(
        UiBuilderNativePreviewLane { _, _ ->
          entered.countDown()
          check(release.await(5, TimeUnit.SECONDS))
          error("native fixture failure")
        },
        maximumConcurrent = 1,
      )
    val workers = Executors.newSingleThreadExecutor()
    try {
      val held = workers.submit {
        assertFailsWith<IllegalStateException> { native.render(document()) }
      }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      assertFailsWith<ExportOverloadedException> { native.render(document()) }
      release.countDown()
      held.get(5, TimeUnit.SECONDS)
      assertFailsWith<IllegalStateException> { native.render(document()) }
    } finally {
      release.countDown()
      workers.shutdownNow()
    }
  }

  @Test
  fun `REST and MCP share the native bound and recover after load drops`() {
    val entered = CountDownLatch(4)
    val release = CountDownLatch(1)
    val lane = UiBuilderNativePreviewLane { _, _ ->
      entered.countDown()
      check(release.await(10, TimeUnit.SECONDS))
      UiBuilderNativePreviewOutcome.Refused("FIXTURE", listOf("fixture completed"))
    }
    val server = server(service(UiBuilderExportExecutor { artifact(it.format) }), native = lane)
    val workers = Executors.newFixedThreadPool(4)
    try {
      val held =
        (0..3).map {
          workers.submit<JsonObject> {
            call(server, "ui_builder_render_native", """{"designId":"load-design"}""")
          }
        }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val response =
        client
          .newCall(
            Request.Builder()
              .url(
                "http://127.0.0.1:${server.port}/api/ui-builder/v1/designs/load-design/native-preview"
              )
              .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
              .post("{}".toRequestBody("application/json".toMediaType()))
              .build()
          )
          .execute()
      response.use {
        assertEquals(503, it.code, it.body.string())
        assertEquals("1", it.header("Retry-After"))
      }
      for (tool in listOf("ui_builder_render_native", "ui_builder_check_design")) {
        val result =
          call(server, tool, """{"designId":"load-design","rendered":true}""")["result"]!!
            .jsonObject
        assertEquals(true, result["isError"]!!.jsonPrimitive.boolean, result.toString())
        assertEquals(
          true,
          result["structuredContent"]!!.jsonObject["retryable"]!!.jsonPrimitive.boolean,
        )
      }
      release.countDown()
      held.forEach { assertNotNull(it.get(5, TimeUnit.SECONDS)["result"]) }
      assertNull(
        call(server, "ui_builder_render_native", """{"designId":"load-design"}""")["result"]!!
          .jsonObject["isError"]
      )
    } finally {
      release.countDown()
      workers.shutdownNow()
      server.stop()
    }
  }

  @Test
  fun `timed out uncooperative workers stay bounded and return retryable worker overload`() {
    val release = CountDownLatch(1)
    val finished = CountDownLatch(1)
    val service =
      service(
        UiBuilderExportExecutor { request ->
          while (release.count > 0) {
            try {
              release.await()
            } catch (_: InterruptedException) {
              /* Deliberately uncooperative fixture. */
            }
          }
          finished.countDown()
          artifact(request.format)
        },
        UiBuilderServiceLimits(maximumConcurrentExports = 1, exportTimeoutMillis = 100),
      )
    val server = server(service)
    try {
      val timeout = get(server, "export.compose")
      assertEquals(500, timeout.first, timeout.second)
      assertEquals("export timed out", timeout.second)
      assertNull(timeout.third)
      val saturated = get(server, "export.compose")
      assertEquals(503, saturated.first, saturated.second)
      assertEquals("concurrent export worker limit reached", saturated.second)
      assertEquals("1", saturated.third)
      assertEquals(0, service.diagnostics().activeExports)
      assertEquals(1L, service.diagnostics().peakExports)
      release.countDown()
      assertTrue(finished.await(5, TimeUnit.SECONDS))
      var recovered = false
      repeat(20) {
        if (!recovered) {
          val response = get(server, "export.compose")
          assertTrue(response.first in setOf(200, 503), response.toString())
          recovered = response.first == 200
        }
      }
      assertTrue(recovered, "worker must be reusable after its task finishes")
    } finally {
      release.countDown()
      server.stop()
    }
  }

  @Test
  fun `render host contention is retryable while a genuine render failure stays internal`() {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val session =
      FakeRenderSession(
        directory.resolve("render").toFile(),
        renderHook = { _, emit ->
          entered.countDown()
          check(release.await(10, TimeUnit.SECONDS))
          emit("fixture png".toByteArray())
        },
      )
    val host =
      ServeRenderHost(
        session = session,
        previews = listOf(ServePreview(PackagedUiBuilderRenderBundle.PREVIEW_ID, "fixture")),
      )
    val port = ServeUiBuilderRenderPort.forHost(host)
    val request =
      UiBuilderRenderRequest(
        designId = "load-design",
        revision = 0,
        documentHash = "fixture",
        widthPx = 200,
        heightPx = 100,
        density = 1f,
        localeTag = "en-US",
        fontScale = 1f,
        encodedDocument = "fixture",
      )
    val server =
      server(
        service(
          UiBuilderExportExecutor { export ->
            port.renderPng(request)
            artifact(export.format)
          }
        )
      )
    val workers = Executors.newSingleThreadExecutor()
    try {
      val held = workers.submit<Triple<Int, String, String?>> { get(server, "export.png") }
      assertTrue(entered.await(5, TimeUnit.SECONDS))
      val busy = get(server, "export.png")
      assertEquals(503, busy.first, busy.second)
      assertEquals("export failed: UI-builder renderer is busy", busy.second)
      assertEquals("1", busy.third)
      release.countDown()
      assertEquals(200, held.get(5, TimeUnit.SECONDS).first)
      assertEquals(200, get(server, "export.png").first)
    } finally {
      release.countDown()
      workers.shutdownNow()
      server.stop()
      port.close()
    }
  }

  @Test
  fun `catalog render saturation carries retry metadata and recovers`() {
    val bundle = directory.resolve("catalog").toFile().apply { mkdirs() }
    java.io.File(bundle, "index.html").writeText("<html>fixture</html>")
    java.io.File(bundle, "previews").mkdirs()
    java.io
      .File(bundle, "previews/load-preview.png")
      .writeBytes(
        java.util.Base64.getDecoder()
          .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
          )
      )
    val registry = ServeSessionRegistry(open = { null })
    val permits = java.util.concurrent.Semaphore(0)
    registry.register(
      "load-fixture",
      host = ServeBundleHost(bundle, label = "fixture"),
      pinned = true,
    )
    val mcp = ServeCatalogMcp(registry, permits, renderQueueWaitSeconds = 0)
    fun render(): JsonObject = runBlocking {
      mcp
        .handle(
          Json.parseToJsonElement(
              """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"catalog_render_preview","arguments":{"catalog":"load-fixture","previewId":"load-preview","observe":"hash"}}}"""
            )
            .jsonObject
        ) {
          ServeMachineAuthorization.Decision.Authorized("load-fixture")
        }
        .body!!["result"]!!
        .jsonObject
    }
    try {
      val busy = render()
      assertEquals(true, busy["isError"]!!.jsonPrimitive.boolean)
      val metadata = busy["structuredContent"]!!.jsonObject
      assertEquals("export_overloaded", metadata["code"]!!.jsonPrimitive.content)
      assertEquals(true, metadata["retryable"]!!.jsonPrimitive.boolean)
      permits.release()
      assertNull(render()["isError"])
      assertEquals(1, permits.availablePermits())
    } finally {
      registry.close()
    }
  }

  @Test
  fun `native compile admission is distinct from source diagnostics and renderer failures`() {
    fun lane(diagnostic: PlaygroundDiagnostic) =
      BoundedUiBuilderNativePreview(
        UiBuilderNativePreviewLane { _, _ ->
          UiBuilderNativePreviewOutcome.Rendered(
            PlaygroundRunResponse(diagnostics = listOf(diagnostic)),
            emptyList(),
            failure = "fixture",
          )
        }
      )
    val admission =
      PlaygroundDiagnostic(
        PlaygroundSeverity.ERROR,
        "the playground is busy compiling (all 1 compile slots in use) — try again shortly",
      )
    assertFailsWith<ExportOverloadedException> { lane(admission).render(document()) }
    assertIs<UiBuilderNativePreviewOutcome.Rendered>(
      lane(admission.copy(file = "Widget.kt")).render(document())
    )
    assertIs<UiBuilderNativePreviewOutcome.Rendered>(
      lane(admission.copy(severity = PlaygroundSeverity.WARNING)).render(document())
    )
    assertIs<UiBuilderNativePreviewOutcome.Rendered>(
      lane(admission.copy(message = "compile sandbox failed: fixture")).render(document())
    )
  }

  private fun service(
    exporter: UiBuilderExportExecutor,
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  ): PersistentUiBuilderService {
    val caps =
      ExportCapabilitiesV1.Builder()
        .apply {
          png = true
          svg = true
          composeCode = true
        }
        .build()
    return PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(directory),
        limits = limits,
        catalogs = benchmarkCatalogExecutor(exportCapabilities = caps),
        exporter = exporter,
      )
      .also {
        assertIs<UiBuilderServiceResponse.Snapshot>(
          runBlocking {
            it.execute(
              UiBuilderServiceCall(actor, UiBuilderServiceRequest.CreateDesign(document()))
            )
          }
        )
      }
  }

  private fun server(
    service: UiBuilderServicePort,
    native: UiBuilderNativePreviewLane? = null,
    thumbnails: ServeUiBuilderThumbnails? = null,
  ): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "local-fixture",
        sessions = ServeSessionRegistry(open = { null }).also(registries::add),
        defaultSessionId = "unused",
        catalogMcpEnabled = true,
        machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
        uiBuilderService = service,
        uiBuilderNativePreview = native,
        uiBuilderThumbnails = thumbnails,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization { _, _, _ ->
            UiBuilderAuthorizationDecision.Authorized(actor.actorId)
          },
      )
      .also { it.start() }

  private fun get(server: ServeHttpServer, path: String): Triple<Int, String, String?> =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/api/ui-builder/v1/designs/load-design/$path")
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .build()
      )
      .execute()
      .use { Triple(it.code, it.body.string(), it.header("Retry-After")) }

  private fun post(server: ServeHttpServer, body: String): JsonObject =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/mcp")
          .header(ServeHttpServer.TOKEN_HEADER, "local-fixture")
          .header("Accept", "application/json, text/event-stream")
          .post(body.toRequestBody("application/json".toMediaType()))
          .build()
      )
      .execute()
      .use {
        val text = it.body.string()
        assertEquals(200, it.code, text)
        Json.parseToJsonElement(text).jsonObject
      }

  private fun call(server: ServeHttpServer, name: String, args: String): JsonObject =
    post(
      server,
      """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$args}}""",
    )

  private fun document() =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "load-design",
      title = "Load fixture",
      revision = 0,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 200,
          heightDp = 100,
          density = 1.0,
          theme = ThemeV1.LIGHT,
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
              properties = mapOf("text" to StringValueV1("Local export load fixture")),
            )
        ),
    )

  private fun artifact(format: ExportFormatV1): ExportArtifactV1 {
    val content = "fixture export"
    return ExportArtifactV1(
      format,
      "text/plain",
      ExportEncodingV1.UTF8,
      content,
      MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") {
        "%02x".format(it)
      },
      emptyList(),
    )
  }
}
