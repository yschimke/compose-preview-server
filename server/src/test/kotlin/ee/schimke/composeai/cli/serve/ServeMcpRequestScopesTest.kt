package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ServeMcpRequestScopesTest {
  private val protocolVersion = ServeCatalogMcp.MCP_PROTOCOL_VERSION

  @Test
  fun `form elicitation correlates accepted declined and cancelled responses`() = runBlocking {
    val scopes = ServeMcpRequestScopes()
    val scope = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))

    for ((wire, expected) in
      listOf(
        "accept" to ServeCatalogMcp.FormElicitationAction.ACCEPT,
        "decline" to ServeCatalogMcp.FormElicitationAction.DECLINE,
        "cancel" to ServeCatalogMcp.FormElicitationAction.CANCEL,
      )) {
      var emitted: JsonObject? = null
      val interaction =
        scopes.interaction(scope) { request ->
          emitted = request
          val id = request["id"]!!.jsonPrimitive.content
          val response = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put(
              "result",
              buildJsonObject {
                put("action", wire)
                if (wire == "accept") {
                  put("content", buildJsonObject { put("choice", "save") })
                }
              },
            )
          }
          assertEquals(
            ServeMcpRequestScopes.ResponseDisposition.ACCEPTED,
            scopes.acceptResponse(scope.id, response),
          )
        }

      val result =
        interaction.elicitForm(
          message = "Choose what to do",
          requestedSchema = buildJsonObject { put("type", "object") },
          timeoutMillis = 1_000,
        )

      assertTrue(interaction.formElicitationSupported)
      assertEquals("elicitation/create", emitted!!["method"]!!.jsonPrimitive.content)
      assertEquals(expected, result?.action)
      if (wire == "accept") {
        assertEquals("save", result?.content?.get("choice")?.jsonPrimitive?.content)
      } else {
        assertNull(result?.content)
      }
    }
  }

  @Test
  fun `unsupported malformed and timed out interactions return text fallback signal`() =
    runBlocking {
      val scopes = ServeMcpRequestScopes()
      val unsupported =
        assertNotNull(scopes.open(protocolVersion, formElicitationSupported = false))
      var emitted = false
      assertNull(
        scopes
          .interaction(unsupported) { emitted = true }
          .elicitForm("Choose", JsonObject(emptyMap()), 1_000)
      )
      assertFalse(emitted)

      val supported = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
      val malformed =
        scopes.interaction(supported) { request ->
          val id = request["id"]!!.jsonPrimitive.content
          assertEquals(
            ServeMcpRequestScopes.ResponseDisposition.INVALID_RESPONSE,
            scopes.acceptResponse(
              supported.id,
              buildJsonObject {
                put("jsonrpc", "1.0")
                put("id", id)
                put("result", buildJsonObject { put("action", "accept") })
              },
            ),
          )
        }
      assertNull(malformed.elicitForm("Choose", JsonObject(emptyMap()), 10))

      assertNull(scopes.interaction(supported) {}.elicitForm("Choose", JsonObject(emptyMap()), 10))

      val bounded =
        ServeMcpRequestScopes(maxInteractionTimeoutMillis = 10).let { registry ->
          val scope = assertNotNull(registry.open(protocolVersion, true))
          registry.interaction(scope) { delay(1_000) }
        }
      assertNull(bounded.elicitForm("Choose", JsonObject(emptyMap()), Long.MAX_VALUE))
    }

  @Test
  fun `sessions expire close and stay bounded`() {
    var now = 1_000L
    val scopes =
      ServeMcpRequestScopes(maxSessions = 1, idleTimeoutMillis = 100, nowMillis = { now })
    val first = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
    assertNotNull(scopes.find(first.id))

    assertNull(scopes.open(protocolVersion, formElicitationSupported = true))
    assertNotNull(scopes.find(first.id))
    assertTrue(scopes.close(first.id))
    assertFalse(scopes.close(first.id))

    val expiring = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
    now += 101
    assertNull(scopes.find(expiring.id))
  }

  @Test
  fun `active request survives idle sweep and rejects wrong correlation and a second call`() =
    runBlocking {
      var now = 1_000L
      val scopes = ServeMcpRequestScopes(idleTimeoutMillis = 100, nowMillis = { now })
      val active = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
      val emitted = CompletableDeferred<JsonObject>()
      val interaction = scopes.interaction(active) { emitted.complete(it) }
      val first = async {
        interaction.elicitForm("Choose", JsonObject(emptyMap()), timeoutMillis = 5_000)
      }
      val request = emitted.await()
      val requestId = request["id"]!!.jsonPrimitive.content

      now += 101
      assertNotNull(scopes.find(active.id), "a pending request must pin its session")
      val other = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
      assertNull(
        interaction.elicitForm("Second", JsonObject(emptyMap()), timeoutMillis = 10),
        "a concurrent interaction must not disturb the first",
      )
      val response = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", requestId)
        put(
          "result",
          buildJsonObject {
            put("action", "accept")
            put("content", buildJsonObject { put("choice", "save") })
          },
        )
      }
      assertEquals(
        ServeMcpRequestScopes.ResponseDisposition.UNKNOWN_REQUEST,
        scopes.acceptResponse(other.id, response),
      )
      assertEquals(
        ServeMcpRequestScopes.ResponseDisposition.UNKNOWN_REQUEST,
        scopes.acceptResponse(
          active.id,
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", "wrong-id")
            put("result", buildJsonObject { put("action", "cancel") })
          },
        ),
      )
      assertEquals(
        ServeMcpRequestScopes.ResponseDisposition.ACCEPTED,
        scopes.acceptResponse(active.id, response),
      )
      assertEquals(ServeCatalogMcp.FormElicitationAction.ACCEPT, first.await()?.action)
    }
}
