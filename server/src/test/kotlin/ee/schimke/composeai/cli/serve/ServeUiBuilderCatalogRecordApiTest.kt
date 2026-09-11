package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `/api/ui-builder/v1/catalogs/{system}/component-record`: the record the browser's code pane
 * generates from.
 *
 * What it is for is `ScreenGeneratorComposeExportExecutor.exportRecord`'s to state and
 * `PublishedGeneratedM3CatalogEquivalenceTest`'s to prove. These are the route's own questions:
 * that it hands back the record unchanged, that the builder's own credential opens it and nothing
 * else does, and that a catalog this host cannot answer for is a 404 rather than an empty record —
 * the editor's fallback is "judge by the record I was built with", and an empty one would instead
 * mean "this catalog has no components", which is the same lie in the other direction.
 */
class ServeUiBuilderCatalogRecordApiTest {
  private val viewerToken = "viewer-token"
  private val strangerToken = "stranger-token"
  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  @Test
  fun `a read credential gets the record the export generates from`() {
    start()

    val (code, body) = send("$RECORDS/m3-catalog/component-record", viewerToken)

    assertEquals(200, code)
    val decoded = Json.parseToJsonElement(body).jsonObject
    assertEquals("m3-catalog", decoded["module"]!!.jsonPrimitive.content)
    val component = decoded["components"]!!.jsonArray.single().jsonObject
    // The ids it answers to matter more than anything else on the entry: they are what a design
    // node names, and the whole defect this route closes was the browser holding a record that
    // did not carry them.
    assertEquals(
      listOf("m3/badge"),
      component["componentIds"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    // And it round-trips as the type the editor decodes, not merely as JSON.
    assertEquals(
      served,
      Json { ignoreUnknownKeys = true }.decodeFromString<ComponentRecordFile>(body),
    )
  }

  @Test
  fun `a catalog this host has no record for is not found`() {
    start()

    assertEquals(404, send("$RECORDS/wear-m3/component-record", viewerToken).first)
  }

  /**
   * No credential is `401` with a challenge; one this host does not let into the builder is `403`.
   *
   * Neither is `404`, for the reason the component library gives: a caller who can open the builder
   * sees the shelf composed from this record already, so which components it proves a call site for
   * is not a secret being kept from them.
   */
  @Test
  fun `an absent credential is challenged and a refused one is forbidden`() {
    start()

    assertEquals(401, send("$RECORDS/m3-catalog/component-record", token = null).first)
    assertEquals(403, send("$RECORDS/m3-catalog/component-record", strangerToken).first)
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

  private fun start() {
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "unused",
          isPublic = true,
          uiBuilderAuthorization = credentials(),
          uiBuilderCatalogRecord = { systemId -> served.takeIf { systemId == "m3-catalog" } },
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
    const val RECORDS = "/api/ui-builder/v1/catalogs"

    /** One component, already wearing the builder id the published file gave it. */
    val served: ComponentRecordFile = Json {
      ignoreUnknownKeys = true
    }
      .decodeFromString(
        """
          {
            "schemaVersion": 2,
            "module": "m3-catalog",
            "variant": "test",
            "components": [
              {
                "canonicalId": "catalog/androidx.compose.material3.BadgeKt.Badge",
                "componentIds": ["m3/badge"],
                "symbol": {
                  "jvmOwner": "androidx.compose.material3.BadgeKt",
                  "callable": "androidx.compose.material3.Badge",
                  "name": "Badge",
                  "origin": "LIBRARY"
                },
                "parameters": [],
                "slots": [],
                "code": { "call": "Badge()", "imports": ["androidx.compose.material3.Badge"] },
                "signatureKnown": true
              }
            ]
          }
          """
      )
  }
}
