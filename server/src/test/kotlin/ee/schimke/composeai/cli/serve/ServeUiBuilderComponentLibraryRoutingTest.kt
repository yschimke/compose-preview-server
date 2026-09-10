package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `/admin/ui-builder/component-library`: the components a project shares, over HTTP.
 *
 * The server here is public and has no UI-builder actor authorization, so what the tests show is
 * that the admin token alone reaches this surface — the same posture the design library has, for
 * the same reason: it exposes the projects a host serves.
 */
class ServeUiBuilderComponentLibraryRoutingTest {
  private val adminToken = "admin-secret"
  private val dir = createTempDirectory("component-library").toFile()
  private val components =
    File(dir, ServeUiBuilderComponentLibrary.COMPONENTS_DIR).apply { mkdirs() }
  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
    dir.deleteRecursively()
  }

  @Test
  fun `a host with no component library registers no routes`() {
    start(library = null)

    // With the correct token, so this is the absence of the route rather than the token gate.
    assertEquals(404, send("/admin/ui-builder/component-library").first)
  }

  /**
   * A missing or wrong admin token gets the same `404` an absent route does, which is this host's
   * convention rather than an oversight: an admin surface does not confirm its own existence to a
   * caller who cannot use it.
   */
  @Test
  fun `the routes are gated by the admin token and do not announce themselves`() {
    publish()
    start()

    assertEquals(404, send("/admin/ui-builder/component-library", token = null).first)
    assertEquals(404, send("/admin/ui-builder/component-library", token = "wrong").first)
    assertEquals(
      404,
      send("/admin/ui-builder/component-library/local/contribution-cell", token = null).first,
    )
    // The same path with the right token answers, so the 404s above are the gate and not the route
    // being missing.
    assertEquals(200, send("/admin/ui-builder/component-library").first)
  }

  @Test
  fun `the listing names each symbol and the project it came from`() {
    publish()
    start()

    val (code, body) = send("/admin/ui-builder/component-library")

    assertEquals(200, code)
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals(
      listOf("local"),
      json["catalogsSearched"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    val entry = json["components"]!!.jsonArray.single().jsonObject
    assertEquals("contribution-cell", entry["componentId"]!!.jsonPrimitive.content)
    assertEquals("project/contribution-cell", entry["paletteId"]!!.jsonPrimitive.content)
    assertEquals("Contribution cell", entry["title"]!!.jsonPrimitive.content)
  }

  @Test
  fun `one symbol answers with its body and the digest a design would record`() {
    publish()
    start()

    val (code, body) = send("/admin/ui-builder/component-library/local/contribution-cell")

    assertEquals(200, code)
    val json = Json.parseToJsonElement(body).jsonObject
    assertTrue(json["digest"]!!.jsonPrimitive.content.startsWith("sha256:"), body)
    assertEquals("m3-catalog", json["catalogPin"]!!.jsonObject["systemId"]!!.jsonPrimitive.content)
    assertEquals(setOf("cell"), json["nodes"]!!.jsonObject.keys)
    assertEquals(
      "Contribution cell",
      json["component"]!!.jsonObject["name"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `an unknown project or an unusable symbol is a not-found rather than an error`() {
    publish()
    start()

    assertEquals(404, send("/admin/ui-builder/component-library/nowhere/anything").first)
    assertEquals(404, send("/admin/ui-builder/component-library/local/absent").first)

    // Published, indexed, and refused by the library's own rules: from here it is simply not a
    // component this host can offer, which is the same answer as never having published it.
    File(components, "broken.json").writeText("{ not a document")
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"broken","title":"Broken"}]}"""
      )
    assertEquals(404, send("/admin/ui-builder/component-library/local/broken").first)
  }

  private fun publish() {
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"contribution-cell","title":"Contribution cell"}]}"""
      )
    File(components, "contribution-cell.json")
      .writeText(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "contribution-cell",
          "title": "Contribution cell",
          "revision": 0,
          "catalogPin": {
            "systemId": "m3-catalog", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
          },
          "environment": {
            "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "dark",
            "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
            "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
            "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
          },
          "stateVariables": {},
          "roots": ["cell"],
          "nodes": {
            "cell": {
              "id": "cell", "componentId": "m3/card",
              "properties": {}, "modifiers": [], "slots": {}, "eventBindings": {}
            }
          },
          "components": {
            "contribution-cell": {"name": "Contribution cell", "root": "cell"}
          }
        }
        """
          .trimIndent()
      )
  }

  private fun start(
    library: ServeUiBuilderComponentLibrary? =
      ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })
  ) {
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = registry,
          defaultSessionId = "unused",
          isPublic = true,
          uiBuilderComponentLibrary = library,
          uiBuilderDesignCatalogs = {
            listOf(
              ServeUiBuilderDesignLibrary.Coordinate(
                system = "local",
                source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
              )
            )
          },
          adminToken = adminToken,
        )
        .also(ServeHttpServer::start)
  }

  private fun send(path: String, token: String? = adminToken): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply { if (token != null) header(ServeHttpServer.ADMIN_TOKEN_HEADER, token) }
        .build()
    client.newCall(request).execute().use {
      return it.code to (it.body?.string() ?: "")
    }
  }
}
