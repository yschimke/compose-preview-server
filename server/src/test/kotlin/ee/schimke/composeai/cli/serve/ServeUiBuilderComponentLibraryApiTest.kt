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
 * `/api/ui-builder/v1/component-library`: the door a palette can actually reach.
 *
 * The admin pair that shipped first is gated by `--admin-token`, which the Wasm editor does not
 * hold — so what these tests are really about is that the builder's own credential opens this and
 * an absent or insufficient one does not.
 */
class ServeUiBuilderComponentLibraryApiTest {
  private val viewerToken = "viewer-token"
  private val strangerToken = "stranger-token"
  private val dir = createTempDirectory("component-api").toFile()
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
  fun `a read credential lists what the served projects share`() {
    publish()
    start()

    val (code, body) = send(LIBRARY, viewerToken)

    assertEquals(200, code)
    val json = Json.parseToJsonElement(body).jsonObject
    val entry = json["components"]!!.jsonArray.single().jsonObject
    assertEquals("contribution-cell", entry["componentId"]!!.jsonPrimitive.content)
    assertEquals("project/contribution-cell", entry["paletteId"]!!.jsonPrimitive.content)
  }

  @Test
  fun `one symbol answers with the body and the digest a design records beside it`() {
    publish()
    start()

    val (code, body) = send("$LIBRARY/local/contribution-cell", viewerToken)

    assertEquals(200, code)
    val json = Json.parseToJsonElement(body).jsonObject
    assertTrue(json["digest"]!!.jsonPrimitive.content.startsWith("sha256:"), body)
    assertEquals(setOf("cell"), json["nodes"]!!.jsonObject.keys)
  }

  /**
   * No credential is `401` with a challenge; a credential this host does not recognise for the
   * builder is `403`. Neither is `404`, because unlike the admin door this surface is not secret —
   * a caller who can open the builder at all can see what its projects publish.
   */
  @Test
  fun `an absent credential is challenged and a refused one is forbidden`() {
    publish()
    start()

    val (missing, _) = send(LIBRARY, token = null)
    assertEquals(401, missing)

    val (forbidden, _) = send(LIBRARY, strangerToken)
    assertEquals(403, forbidden)
  }

  @Test
  fun `a host serving no component library registers no routes`() {
    start(library = null)

    assertEquals(404, send(LIBRARY, viewerToken).first)
  }

  /**
   * An unknown project, an unknown id and a published-but-unusable symbol are one answer.
   *
   * From out here they are the same fact — there is no component of that name to import — and
   * telling them apart would say which projects a host serves to a caller who cannot list them.
   */
  @Test
  fun `every way of not finding a symbol answers the same`() {
    publish()
    start()

    assertEquals(404, send("$LIBRARY/nowhere/anything", viewerToken).first)
    assertEquals(404, send("$LIBRARY/local/absent", viewerToken).first)

    File(components, "broken.json").writeText("{ not a document")
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"broken","title":"Broken"}]}"""
      )
    assertEquals(404, send("$LIBRARY/local/broken", viewerToken).first)
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

  /** A reader, and a caller this host knows but does not let into the builder. */
  private fun credentials(): ServeUiBuilderAuthorization =
    ServeUiBuilderAuthorization { call, _, presented ->
      when (presented ?: call.request.headers[ServeHttpServer.TOKEN_HEADER]) {
        viewerToken -> UiBuilderAuthorizationDecision.Authorized("viewer")
        strangerToken -> UiBuilderAuthorizationDecision.Forbidden
        else -> UiBuilderAuthorizationDecision.Missing
      }
    }

  private fun start(
    library: ServeUiBuilderComponentLibrary? =
      ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })
  ) {
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "unused",
          isPublic = true,
          uiBuilderAuthorization = credentials(),
          uiBuilderComponentLibrary = library,
          uiBuilderDesignCatalogs = {
            listOf(
              ServeUiBuilderDesignLibrary.Coordinate(
                system = "local",
                source = ServeUiBuilderDesignLibrary.Source.Directory(dir),
              )
            )
          },
        )
        .also(ServeHttpServer::start)
  }

  private fun send(path: String, token: String?): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply { if (token != null) header(ServeHttpServer.TOKEN_HEADER, token) }
        .build()
    client.newCall(request).execute().use {
      return it.code to (it.body?.string() ?: "")
    }
  }

  private companion object {
    const val LIBRARY = "/api/ui-builder/v1/component-library"
  }
}
