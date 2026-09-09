package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminRepair
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `/admin/ui-builder`: the operator's screen over every design, gated by the admin token alone.
 *
 * The server here is public and has no UI-builder actor authorization at all, which is the point:
 * neither browsing being open nor a `ui-builder-*` grant reaches this surface, only
 * `--admin-token`.
 */
class ServeUiBuilderAdminRoutingTest {
  private val jsonMediaType = "application/json".toMediaType()
  private val adminToken = "admin-secret"
  private val designs =
    linkedMapOf(
      "cheeky-raccoon" to summary("cheeky-raccoon", "Home", "operator"),
      "shady-goose" to summary("shady-goose", "Watch face", "github:someone"),
    )
  private val deleted = mutableListOf<String>()
  private val repaired = mutableListOf<Pair<String, String>>()
  private val port =
    object : UiBuilderAdminPort {
      override fun adminListDesigns() = designs.values.toList()

      override fun adminDeleteDesign(designId: String): Boolean {
        deleted += designId
        return designs.remove(designId) != null
      }

      override fun adminUnusableDesigns(): Map<String, String> =
        mapOf(
          "shady-goose" to "catalog unavailable for stored design shady-goose",
          // A store quarantine: never a design in memory, so never in `adminListDesigns`.
          "sunken-otter" to "stored design cannot be read: document-a1b2.json is missing",
        )

      override fun adminUnreadableDesigns(): Set<String> = setOf("sunken-otter")

      override fun adminDesignDocument(designId: String): String? =
        designs[designId]?.let { """{"id":"${it.designId}"}""" }

      override fun adminRepairDesign(
        designId: String,
        documentJson: String,
      ): UiBuilderAdminRepair =
        when {
          designId !in designs -> UiBuilderAdminRepair.NotFound(designId)
          documentJson.contains("still-broken") ->
            UiBuilderAdminRepair.Rejected("stored node count exceeds configured limit")
          else -> {
            repaired += designId to documentJson
            UiBuilderAdminRepair.Repaired(designId, revision = 3, previousReason = "catalog gone")
          }
        }
    }
  private val registry = ServeSessionRegistry(open = { null })

  private fun server(admin: ServeUiBuilderAdmin?, token: String? = adminToken) =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "unused",
        isPublic = true,
        uiBuilderAdmin = admin,
        adminToken = token,
      )
      .also(ServeHttpServer::start)

  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  private fun send(
    path: String,
    method: String = "GET",
    token: String? = adminToken,
    body: String? = null,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply {
          if (token != null) header(ServeHttpServer.ADMIN_TOKEN_HEADER, token)
          if (method == "DELETE") delete()
          if (method == "PUT") put(body.orEmpty().toRequestBody(jsonMediaType))
        }
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  @Test
  fun `the routes are gated by the admin token even on a public server`() {
    server = server(ServeUiBuilderAdmin(port, onLog = {}))
    assertEquals(404, send("/admin/ui-builder", token = null).first)
    assertEquals(404, send("/admin/ui-builder", token = "wrong").first)
    assertEquals(404, send("/admin/ui-builder/designs", token = null).first)
    assertEquals(
      404,
      send("/admin/ui-builder/designs/shady-goose", "DELETE", token = "wrong").first,
    )
    assertEquals(listOf("cheeky-raccoon", "shady-goose"), designs.keys.toList(), "nothing deleted")
    assertEquals(200, send("/admin/ui-builder").first)
    // The query form is what a browser opens the page with.
    assertEquals(200, send("/admin/ui-builder?token=$adminToken", token = null).first)
  }

  @Test
  fun `without an admin or a token the routes do not exist`() {
    server = server(admin = null)
    assertEquals(404, send("/admin/ui-builder").first)
    assertEquals(404, send("/admin/ui-builder/designs").first)
    server!!.stop()
    server = server(ServeUiBuilderAdmin(port, onLog = {}), token = null)
    assertEquals(404, send("/admin/ui-builder", token = null).first)
  }

  @Test
  fun `the list names every design with its owner and the page drives it`() {
    server = server(ServeUiBuilderAdmin(port, onLog = {}))
    val (code, body) = send("/admin/ui-builder/designs")
    assertEquals(200, code)
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals(
      "compose-preview-serve/admin-ui-builder-designs/v1",
      json.getValue("schema").jsonPrimitive.content,
    )
    val listed = json.getValue("designs").jsonArray.map { it.jsonObject }
    assertEquals(
      // The designs the host serves, then the one it holds but cannot read — which has no owner,
      // no catalog and no revision to report, because it never became a design in memory.
      listOf("cheeky-raccoon", "shady-goose", "sunken-otter"),
      listed.map { it.getValue("designId").jsonPrimitive.content },
    )
    assertEquals(
      listOf("operator", "github:someone", ""),
      listed.map { it.getValue("ownerActorId").jsonPrimitive.content },
    )
    assertEquals("m3-catalog", listed.first().getValue("catalogSystemId").jsonPrimitive.content)
    assertEquals("Home", listed.first().getValue("title").jsonPrimitive.content)

    val (pageCode, page) = send("/admin/ui-builder?token=$adminToken", token = null)
    assertEquals(200, pageCode)
    assertTrue(page.contains("UI-builder designs"), page)
    assertTrue(page.contains("/admin/ui-builder/designs"), "the page fetches the JSON route")
    assertTrue(page.contains("X-Compose-Preview-Admin-Token"), "the page sends the admin header")
    assertTrue(page.contains("\"$adminToken\""), "the page re-sends the token it was opened with")
    assertFalse(page.contains("cheeky-raccoon"), "rows are fetched, not baked into the page")
  }

  @Test
  fun `a design the host cannot read has a row, and only the action that works on it`() {
    server = server(ServeUiBuilderAdmin(port, onLog = {}))

    val (code, body) = send("/admin/ui-builder/designs")

    assertEquals(200, code)
    val listed =
      Json.parseToJsonElement(body).jsonObject.getValue("designs").jsonArray.map { it.jsonObject }
    // It is not a design in memory, so nothing would have put it here — and this is the page the
    // startup warning sends an operator to, offering the one recovery it has.
    val quarantined = listed.single {
      it.getValue("designId").jsonPrimitive.content == "sunken-otter"
    }
    assertTrue(
      quarantined.getValue("unusableReason").jsonPrimitive.content.contains("cannot be read"),
      body,
    )
    assertFalse(quarantined.getValue("documentAvailable").jsonPrimitive.boolean, body)
    // And the quarantine whose document is fine keeps both actions that need one.
    val outgrown = listed.single { it.getValue("designId").jsonPrimitive.content == "shady-goose" }
    assertTrue(outgrown.getValue("documentAvailable").jsonPrimitive.boolean, body)

    val (_, page) = send("/admin/ui-builder?token=$adminToken", token = null)
    assertTrue(page.contains("documentAvailable"), "the page decides the buttons on it")
  }

  @Test
  fun `a delete removes the design and reports what it found`() {
    server = server(ServeUiBuilderAdmin(port, onLog = {}))
    val (code, body) = send("/admin/ui-builder/designs/shady-goose", "DELETE")
    assertEquals(200, code)
    assertEquals("""{"designId":"shady-goose","status":"deleted"}""", body)
    assertEquals(listOf("shady-goose"), deleted)
    assertEquals(listOf("cheeky-raccoon"), designs.keys.toList())

    assertEquals(404, send("/admin/ui-builder/designs/shady-goose", "DELETE").first)
    assertEquals(400, send("/admin/ui-builder/designs/%20", "DELETE").first)
  }

  @Test
  fun `a design's document can be copied out, including one the host cannot serve`() {
    server = server(ServeUiBuilderAdmin(port, onLog = {}))

    // `shady-goose` is quarantined: the list says why, and the document route still answers for it.
    // That combination is the point — without it, deleting is the only thing left to do with a
    // design a rule change invalidated, and the document goes with it.
    val listed =
      Json.parseToJsonElement(send("/admin/ui-builder/designs").second)
        .jsonObject
        .getValue("designs")
        .jsonArray
        .map { it.jsonObject }
    assertEquals(
      "catalog unavailable for stored design shady-goose",
      listed
        .single { it.getValue("designId").jsonPrimitive.content == "shady-goose" }
        .getValue("unusableReason")
        .jsonPrimitive
        .content,
    )

    val (code, body) = send("/admin/ui-builder/designs/shady-goose/document")
    assertEquals(200, code)
    assertEquals("""{"id":"shady-goose"}""", body)

    assertEquals(404, send("/admin/ui-builder/designs/no-such-design/document").first)
    assertEquals(400, send("/admin/ui-builder/designs/%20/document").first)
    // Reading a document is as much the operator's alone as deleting one.
    assertEquals(
      404,
      send("/admin/ui-builder/designs/shady-goose/document", token = null).first,
    )
  }

  @Test
  fun `a repaired document goes back the way it came, and one that is not is refused`() {
    server = server(ServeUiBuilderAdmin(port, onLog = {}))
    val path = "/admin/ui-builder/designs/shady-goose/document"

    val (code, body) = send(path, "PUT", body = """{"id":"shady-goose"}""")
    assertEquals(200, code)
    assertEquals("""{"designId":"shady-goose","status":"repaired","revision":3}""", body)
    assertEquals(listOf("shady-goose" to """{"id":"shady-goose"}"""), repaired)

    // A candidate that does not repair the design comes back with what is still wrong, so the
    // operator can edit and try again rather than guess.
    val (rejectedCode, rejected) = send(path, "PUT", body = """{"id":"still-broken"}""")
    assertEquals(400, rejectedCode)
    assertTrue(rejected.contains("node count"), rejected)

    assertEquals(400, send(path, "PUT", body = "").first)
    assertEquals(
      404,
      send("/admin/ui-builder/designs/no-such-design/document", "PUT", body = "{}").first,
    )
    // Writing a design is as much the operator's alone as reading or deleting one.
    assertEquals(404, send(path, "PUT", token = null, body = "{}").first)
    assertEquals(1, repaired.size, "no repair slipped past the token or the checks")
  }

  private fun summary(id: String, title: String, owner: String) =
    UiBuilderAdminDesignSummary(
      designId = id,
      title = title,
      revision = 2,
      catalogPin = CatalogReferenceV1("m3-catalog", "rev", "rev", "runtime"),
      ownerActorId = owner,
      collaborators = 0,
      createdAtEpochMillis = 1_000,
      updatedAtEpochMillis = 2_000,
      activeSubscribers = 0,
    )
}
