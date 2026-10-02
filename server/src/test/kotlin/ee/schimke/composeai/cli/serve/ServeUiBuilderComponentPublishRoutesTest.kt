package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * `PUT /api/ui-builder/v1/component-library/{system}/{componentId}`: an editor publishing one of a
 * design's components, so every other design on the host can place it.
 */
class ServeUiBuilderComponentPublishRoutesTest {
  @TempDir lateinit var state: Path

  private val writerToken = "writer-token"
  private val readerToken = "reader-token"
  private val project = createTempDirectory("component-publish").toFile()
  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
    project.deleteRecursively()
  }

  @Test
  fun `a published component is listed and fetched like a committed one`() {
    start()

    val (code, body) = put("inbox-email", publishRequest())

    assertEquals(201, code, body)
    val digest = Json.parseToJsonElement(body).jsonObject["digest"]!!.jsonPrimitive.content
    val listing = Json.parseToJsonElement(get(LIBRARY).second).jsonObject
    val row = listing["components"]!!.jsonArray.single().jsonObject
    assertEquals("inbox-email", row["componentId"]!!.jsonPrimitive.content)
    assertEquals("Inbox email", row["title"]!!.jsonPrimitive.content)
    val (fetched, symbol) = get("$LIBRARY/$SYSTEM/inbox-email")
    assertEquals(200, fetched, symbol)
    assertEquals(
      digest,
      Json.parseToJsonElement(symbol).jsonObject["digest"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `an update names the digest it replaces, and a stale one is told the current one`() {
    start()
    val first = Json.parseToJsonElement(put("inbox-email", publishRequest()).second).jsonObject
    val firstDigest = first["digest"]!!.jsonPrimitive.content

    val (again, refusal) = put("inbox-email", publishRequest(text = "Changed"))
    assertEquals(409, again, refusal)
    assertEquals(
      firstDigest,
      Json.parseToJsonElement(refusal).jsonObject["digest"]!!.jsonPrimitive.content,
    )

    val (updated, body) =
      put("inbox-email", publishRequest(text = "Changed", replacesDigest = firstDigest))
    assertEquals(200, updated, body)
    assertEquals(409, put("inbox-email", publishRequest(replacesDigest = firstDigest)).first)
  }

  @Test
  fun `publishing needs write access`() {
    start()

    assertEquals(403, put("inbox-email", publishRequest(), readerToken).first)
    assertEquals(401, put("inbox-email", publishRequest(), token = null).first)
    assertEquals(200, get(LIBRARY).first)
  }

  @Test
  fun `a component the project has committed is changed in the project, not shadowed here`() {
    val committed = File(project, ServeUiBuilderComponentLibrary.COMPONENTS_DIR).apply { mkdirs() }
    File(committed, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"inbox-email","title":"Inbox email"}]}"""
      )
    start()

    val (code, body) = put("inbox-email", publishRequest())

    assertEquals(409, code, body)
    assertTrue("committed to the project" in body, body)
  }

  @Test
  fun `what the host's own service would refuse is refused with its reasons`() {
    start(
      validator = { _, document, _ ->
        document.nodes.values
          .filter { it.componentId == "m3/text" }
          .map {
            UiBuilderValidationProblemV1(
              source = SOURCE_DOCUMENT,
              code = "UNKNOWN_COMPONENT",
              message = "m3/text is not in this catalog",
              nodeId = it.id,
            )
          }
      }
    )

    val (code, body) = put("inbox-email", publishRequest())

    assertEquals(422, code, body)
    assertTrue("sender: m3/text is not in this catalog" in body, body)
    assertEquals(
      0,
      Json.parseToJsonElement(get(LIBRARY).second).jsonObject["components"]!!.jsonArray.size,
    )
  }

  private fun publishRequest(text: String = "Sender", replacesDigest: String? = null): String =
    JsonObject(
        buildMap {
          put("title", JsonPrimitive("Inbox email"))
          replacesDigest?.let { put("replacesDigest", JsonPrimitive(it)) }
          put("document", Json.parseToJsonElement(document(text)))
        }
      )
      .toString()

  private fun document(text: String) =
    """
    {
      "schema": "compose-ui-builder-document/v1-candidate",
      "id": "inbox-email",
      "title": "InboxEmail",
      "revision": 0,
      "catalogPin": {
        "systemId": "$SYSTEM", "catalogRevision": "candidate",
        "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
      },
      "environment": {
        "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "light",
        "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
        "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
        "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
      },
      "stateVariables": {},
      "roots": ["row"],
      "nodes": {
        "row": {
          "id": "row", "componentId": "layout/row",
          "properties": {}, "modifiers": [], "slots": {"children": ["sender"]}, "eventBindings": {}
        },
        "sender": {
          "id": "sender", "componentId": "m3/text",
          "properties": {"text": {"type": "string", "value": "$text"}},
          "modifiers": [], "slots": {}, "eventBindings": {}
        }
      },
      "components": {"inbox-email": {"name": "InboxEmail", "root": "row"}}
    }
    """

  /** A writer, a reader, and nobody. */
  private fun credentials(): ServeUiBuilderAuthorization =
    ServeUiBuilderAuthorization { call, capability, presented ->
      when (presented ?: call.request.headers[ServeHttpServer.TOKEN_HEADER]) {
        writerToken -> UiBuilderAuthorizationDecision.Authorized("writer")
        readerToken ->
          if (capability == UiBuilderRouteCapability.READ) {
            UiBuilderAuthorizationDecision.Authorized("reader")
          } else UiBuilderAuthorizationDecision.Forbidden
        else -> UiBuilderAuthorizationDecision.Missing
      }
    }

  private fun start(validator: UiBuilderDraftValidator? = null) {
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "unused",
          isPublic = true,
          uiBuilderAuthorization = credentials(),
          uiBuilderComponentLibrary = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null }),
          uiBuilderComponentStore = ServeUiBuilderComponentStore(state),
          uiBuilderValidator = validator,
          uiBuilderDesignCatalogs = {
            listOf(
              ServeUiBuilderDesignLibrary.Coordinate(
                system = SYSTEM,
                source = ServeUiBuilderDesignLibrary.Source.Directory(project),
              )
            )
          },
        )
        .also(ServeHttpServer::start)
  }

  private fun get(path: String, token: String? = writerToken): Pair<Int, String> =
    send(Request.Builder().url(url(path)), token)

  private fun put(
    componentId: String,
    body: String,
    token: String? = writerToken,
  ): Pair<Int, String> =
    send(
      Request.Builder()
        .url(url("$LIBRARY/$SYSTEM/$componentId"))
        .put(body.toRequestBody("application/json".toMediaType())),
      token,
    )

  private fun url(path: String) = "http://127.0.0.1:${server!!.port}$path"

  private fun send(builder: Request.Builder, token: String?): Pair<Int, String> {
    val request =
      builder.apply { if (token != null) header(ServeHttpServer.TOKEN_HEADER, token) }.build()
    client.newCall(request).execute().use {
      return it.code to (it.body?.string() ?: "")
    }
  }

  private companion object {
    const val LIBRARY = "/api/ui-builder/v1/component-library"
    const val SYSTEM = "m3-catalog"
  }
}
