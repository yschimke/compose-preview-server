package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.*
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ServeUiBuilderProjectRoutesTest {
  private val root = Files.createTempDirectory("project-routes")
  private val projects = ServeUiBuilderProjectStore(root)
  private val registry = ServeSessionRegistry(open = { null })
  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall) =
        UiBuilderServiceResponse.Catalogs(emptyList())

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ) = Closeable {}
    }
  private val auth = ServeUiBuilderAuthorization { call, _, _ ->
    call.request.headers["X-Test-Actor"]?.let { UiBuilderAuthorizationDecision.Authorized(it) }
      ?: UiBuilderAuthorizationDecision.Missing
  }
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderService = service,
        uiBuilderAuthorization = auth,
        uiBuilderProjectStore = projects,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient()

  @AfterTest
  fun cleanup() {
    server.stop()
    registry.close()
    root.toFile().deleteRecursively()
  }

  private fun request(
    path: String = "",
    actor: String? = null,
    body: String? = null,
    method: String = "POST",
  ): Pair<Int, String> {
    val b = Request.Builder().url("http://127.0.0.1:${server.port}/api/ui-builder/v1/projects$path")
    actor?.let { b.header("X-Test-Actor", it) }
    body?.let { b.method(method, it.toRequestBody()) }
    return client.newCall(b.build()).execute().use { it.code to it.body!!.string() }
  }

  @Test
  fun `projects require authentication and project membership`() {
    assertEquals(401, request().first)
    assertEquals(200, request(actor = "github:owner", body = """{"id":"app","name":"App"}""").first)
    assertEquals(404, request("/app", "github:stranger").first)
    assertEquals("[]", request(actor = "github:stranger").second)
    val own = request("/app", "github:owner")
    assertEquals(200, own.first)
    assertEquals(
      "github:owner",
      PROJECT_JSON.parseToJsonElement(own.second).jsonObject["owner"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `action credentials do not become project metadata`() {
    assertEquals(
      200,
      request(
          actor = "github:owner",
          body = """{"id":"app","name":"App","githubToken":"action-only-fixture-secret"}""",
        )
        .first,
    )
    assertFalse(request("/app", "github:owner").second.contains("action-only-fixture-secret"))
    assertFalse(Files.readString(root.resolve("app.json")).contains("action-only-fixture-secret"))
  }

  @Test
  fun `viewers receive forbidden when saving project files`() {
    projects.save(
      BuilderProject(
        id = "app",
        name = "App",
        owner = "github:owner",
        members = mapOf("github:viewer" to ProjectRole.VIEWER),
      ),
      null,
    )
    assertEquals(200, request("/app", "github:viewer").first)
    assertEquals(
      403,
      request(
          "/app/files",
          "github:viewer",
          """{"baseRevision":0,"fileId":"tokens","path":"tokens.json","kind":"tokens","content":"{}"}""",
          "PUT",
        )
        .first,
    )
    assertTrue(projects.read("app")!!.files.isEmpty())
  }

  @Test
  fun `an existing project is never replaced by create`() {
    val body = """{"id":"app","name":"App"}"""
    assertEquals(200, request(actor = "github:owner", body = body).first)
    assertEquals(422, request(actor = "github:stranger", body = body).first)
    assertEquals("github:owner", projects.read("app")!!.owner)
  }

  @Test
  fun `project page is discoverable and renders no private content into public HTML`() {
    projects.save(
      BuilderProject(id = "private", name = "Secret name", owner = "github:owner"),
      null,
    )
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${server.port}/ui-builder/projects").build())
      .execute()
      .use {
        assertEquals(200, it.code)
        val page = it.body!!.string()
        assertTrue(page.contains("UI Builder projects"))
        assertFalse(page.contains("Secret name"))
        assertTrue(it.header("Cache-Control")!!.contains("no-store"))
      }
  }

  @Test
  fun `production file saves retain its authored API and refuse removal of contracted roots`() {
    val text = javaClass.getResource("/ui-builder-project/production.uid")!!.readText()
    val doc = ServeUiBuilderUidProjectFiles.designs(text).single()
    val file = ProjectFile("production", "production.uid", text, mapOf(doc.id to "stored"))
    val output =
      ServeUiBuilderUidProjectFiles.write(
        file,
        mapOf(doc.id to doc.copy(id = "stored", title = "Edited")),
      )
    val before = PROJECT_JSON.parseToJsonElement(text).jsonObject
    val after = PROJECT_JSON.parseToJsonElement(output).jsonObject
    assertEquals(before - "design", after - "design", "production declarations are kept exactly")
    assertEquals(doc.id, ServeUiBuilderUidProjectFiles.designs(output).single().id)
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderUidProjectFiles.write(
        file,
        mapOf(doc.id to doc.copy(id = "stored", roots = emptyList())),
      )
    }
  }
}
