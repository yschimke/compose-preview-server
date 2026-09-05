package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Creating a design is a request with a method, and its answer is where the design lives.
 *
 * Two routes, one rule: the id is chosen by the caller, and neither route ever overwrites. The form
 * `POST` exists so a browser can submit it and follow the `303` itself; the `PUT` exists so a
 * programmatic caller can say the same thing about a document it already has, with the precondition
 * (`If-None-Match: *`) spelled out rather than implied.
 */
class ServeUiBuilderCreateRoutesTest {
  private val created = CopyOnWriteArrayList<String>()
  private val existing = CopyOnWriteArrayList<String>()

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.OpenDesign ->
            if (request.designId in existing) UiBuilderServiceResponse.Catalogs(emptyList())
            else
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "missing")
              )
          is UiBuilderServiceRequest.ListCatalogs ->
            UiBuilderServiceResponse.Catalogs(
              listOf(
                CatalogCapabilityV1(
                  schema = "compose-catalog-capabilities/v1",
                  benchmark =
                    CatalogBenchmarkV1("m3", "source", "m3-catalog", "candidate", "candidate"),
                  components = emptyList(),
                  exportCapabilities =
                    ExportCapabilitiesV1(composeCode = true, svg = false, png = false),
                )
              )
            )
          is UiBuilderServiceRequest.CreateDesign -> {
            created += request.document.id
            existing += request.document.id
            UiBuilderServiceResponse.Catalogs(emptyList())
          }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val authorization = ServeUiBuilderAuthorization { call, _ ->
    when (val actor = call.request.headers["X-Test-Actor"]) {
      null -> UiBuilderAuthorizationDecision.Missing
      "forbidden" -> UiBuilderAuthorizationDecision.Forbidden
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }

  private val builderDir =
    Files.createTempDirectory("serve-ui-builder-create").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html").writeText("<!doctype html><title>Compose UI builder</title>")
      File(dir, "jetcaster-discover-operations-v1.json")
        .writeText(
          File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .takeIf { it.isFile }
            ?.readText()
            ?: File("docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
              .readText()
        )
    }

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderDir = builderDir,
        uiBuilderCatalogs = setOf("m3-catalog"),
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  @Test
  fun `the New design form creates once and redirects to the design's permalink`() {
    val form = FormBody.Builder().add("designId", "mywidget3").add("template", "blank").build()
    val request =
      Request.Builder()
        .url(url("/ui-builder/m3-catalog?token=t"))
        .header("X-Test-Actor", "operator")
        .header("Origin", "http://127.0.0.1:${server.port}")
        .post(form)
        .build()
    client.newCall(request).execute().use { response ->
      assertEquals(303, response.code)
      assertEquals("/ui-builder/m3-catalog/mywidget3?token=t", response.header("Location"))
    }
    assertEquals(listOf("mywidget3"), created)

    // Submitting the same form again is not an error and is not a second design: creation never
    // overwrites, so the answer is the same redirect to the design that is already there.
    client
      .newCall(
        Request.Builder()
          .url(url("/ui-builder/m3-catalog"))
          .header("X-Test-Actor", "operator")
          .post(FormBody.Builder().add("designId", "mywidget3").add("template", "blank").build())
          .build()
      )
      .execute()
      .use { response ->
        assertEquals(303, response.code)
        assertEquals("/ui-builder/m3-catalog/mywidget3", response.header("Location"))
      }
    assertEquals(listOf("mywidget3"), created)
  }

  @Test
  fun `a create form refuses what it cannot honour`() {
    fun post(
      path: String,
      form: FormBody,
      actor: String? = "operator",
      origin: String? = null,
    ): Int {
      val builder = Request.Builder().url(url(path)).post(form)
      if (actor != null) builder.header("X-Test-Actor", actor)
      if (origin != null) builder.header("Origin", origin)
      return client.newCall(builder.build()).execute().use { it.code }
    }
    val blank = FormBody.Builder().add("designId", "another").add("template", "blank").build()

    assertEquals(404, post("/ui-builder/not-served", blank))
    assertEquals(401, post("/ui-builder/m3-catalog", blank, actor = null))
    assertEquals(403, post("/ui-builder/m3-catalog", blank, actor = "forbidden"))
    assertEquals(403, post("/ui-builder/m3-catalog", blank, origin = "https://evil.example"))
    assertEquals(
      400,
      post(
        "/ui-builder/m3-catalog",
        FormBody.Builder().add("designId", "../escape").add("template", "blank").build(),
      ),
    )
    assertEquals(
      400,
      post(
        "/ui-builder/m3-catalog",
        FormBody.Builder().add("designId", "fine").add("template", "wear-widget-small").build(),
      ),
    )
    assertTrue(created.isEmpty(), "no refusal may have created a design: $created")
  }

  @Test
  fun `PUT creates a design at its own URL, and only when it is not there yet`() {
    val document =
      """
      {"schema":"compose-ui-builder/v1","id":"put-design","title":"Put","revision":0,
       "catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate",
       "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":1280,"heightDp":800,"density":1.0,"theme":"dark","locale":"en-US",
       "fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":[],"nodes":{}}
      """
        .trimIndent()
    fun put(id: String, body: String, ifNoneMatch: String? = "*"): Pair<Int, String?> {
      val builder =
        Request.Builder()
          .url(url("/api/ui-builder/v1/designs/$id"))
          .header("X-Test-Actor", "operator")
          .put(body.toRequestBody())
      if (ifNoneMatch != null) builder.header("If-None-Match", ifNoneMatch)
      return client.newCall(builder.build()).execute().use { it.code to it.header("Location") }
    }

    // Without the precondition the request is asking for PUT-as-replace, which this route does not
    // do — and it is told so rather than being quietly treated as a create.
    assertEquals(428, put("put-design", document, ifNoneMatch = null).first)
    assertTrue(created.isEmpty())

    val (code, location) = put("put-design", document)
    assertEquals(201, code)
    assertEquals("/ui-builder/m3-catalog/put-design", location)
    assertEquals(listOf("put-design"), created)

    // The second one fails its own precondition rather than replacing the first.
    assertEquals(412, put("put-design", document).first)
    assertEquals(listOf("put-design"), created)

    // The URL names the design, so a document that claims to be another one is a bad request.
    assertEquals(400, put("elsewhere", document).first)
  }
}
