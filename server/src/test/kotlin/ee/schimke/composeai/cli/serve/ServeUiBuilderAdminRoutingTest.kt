package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `/admin/ui-builder`: the operator's screen over every design, gated by the admin token alone.
 *
 * The server here is public and has no UI-builder actor authorization at all, which is the point:
 * neither browsing being open nor a `ui-builder-*` grant reaches this surface, only
 * `--admin-token`.
 */
class ServeUiBuilderAdminRoutingTest {
  private val adminToken = "admin-secret"
  private val designs =
    linkedMapOf(
      "cheeky-raccoon" to summary("cheeky-raccoon", "Home", "operator"),
      "shady-goose" to summary("shady-goose", "Watch face", "github:someone"),
    )
  private val deleted = mutableListOf<String>()
  private val port =
    object : UiBuilderAdminPort {
      override fun adminListDesigns() = designs.values.toList()

      override fun adminDeleteDesign(designId: String): Boolean {
        deleted += designId
        return designs.remove(designId) != null
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
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply {
          if (token != null) header(ServeHttpServer.ADMIN_TOKEN_HEADER, token)
          if (method == "DELETE") delete()
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
      listOf("cheeky-raccoon", "shady-goose"),
      listed.map { it.getValue("designId").jsonPrimitive.content },
    )
    assertEquals(
      listOf("operator", "github:someone"),
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
