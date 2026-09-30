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

      val nonPrimitiveAction =
        scopes.interaction(supported) { request ->
          val id = request["id"]!!.jsonPrimitive.content
          assertEquals(
            ServeMcpRequestScopes.ResponseDisposition.ACCEPTED,
            scopes.acceptResponse(
              supported.id,
              buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                  "result",
                  buildJsonObject { put("action", buildJsonObject { put("type", "accept") }) },
                )
              },
            ),
          )
        }
      assertNull(
        nonPrimitiveAction.elicitForm("Choose", JsonObject(emptyMap()), 1_000),
        "a non-primitive action is malformed and must preserve the caller's text fallback",
      )

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

    // A full registry evicts its least recently used idle scope instead of refusing: initialize is
    // ungated, so refusing would let an anonymous caller lock everyone else out.
    val second = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
    assertNull(scopes.find(first.id))
    assertNotNull(scopes.find(second.id))
    assertTrue(scopes.close(second.id))
    assertFalse(scopes.close(second.id))

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

  @Test
  fun `a registry full of pending interactions issues no new scope`() = runBlocking {
    val scopes = ServeMcpRequestScopes(maxSessions = 1)
    val busy = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
    val emitted = CompletableDeferred<JsonObject>()
    val waiting = async {
      scopes
        .interaction(busy) { emitted.complete(it) }
        .elicitForm("Choose", JsonObject(emptyMap()), timeoutMillis = 5_000)
    }
    emitted.await()
    assertNull(scopes.open(protocolVersion, formElicitationSupported = true))
    assertTrue(scopes.close(busy.id))
    waiting.cancel()
  }

  @Test
  fun `a call that never elicits is answered without opening a stream`() = runBlocking {
    val scopes = ServeMcpRequestScopes()
    val scope = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
    var replied: String? = null
    var streamed = false
    scopes.dispatchLazily(
      scope,
      dispatch = { "plain" },
      onReply = { replied = it },
      onStream = { _, _, _ -> streamed = true },
    )
    assertEquals("plain", replied)
    assertFalse(streamed)
  }

  @Test
  fun `the first emitted message switches the call to a stream`() = runBlocking {
    val scopes = ServeMcpRequestScopes()
    val scope = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
    val streamed = mutableListOf<JsonObject>()
    var finalReply: String? = null
    var replied = false
    scopes.dispatchLazily(
      scope,
      dispatch = { interaction ->
        val answer = async {
          interaction.elicitForm("Choose", JsonObject(emptyMap()), timeoutMillis = 5_000)
        }
        // Answer the elicitation the moment it is pending, as the client's second POST would.
        while (scope.pending.isEmpty()) delay(1)
        val id = scope.pending.keys.single()
        assertEquals(
          ServeMcpRequestScopes.ResponseDisposition.ACCEPTED,
          scopes.acceptResponse(
            scope.id,
            buildJsonObject {
              put("jsonrpc", "2.0")
              put("id", id)
              put("result", buildJsonObject { put("action", "decline") })
            },
          ),
        )
        "done:${answer.await()?.action}"
      },
      onReply = { replied = true },
      onStream = { first, rest, reply ->
        streamed += first
        for (message in rest) streamed += message
        finalReply = reply.await()
      },
    )
    assertFalse(replied)
    assertEquals("elicitation/create", streamed.single()["method"]!!.jsonPrimitive.content)
    assertEquals("done:${ServeCatalogMcp.FormElicitationAction.DECLINE}", finalReply)
  }

  @Test
  fun `an answer from a different credential is refused and leaves the request pending`() =
    runBlocking {
      val scopes = ServeMcpRequestScopes()
      val scope = assertNotNull(scopes.open(protocolVersion, formElicitationSupported = true))
      val emitted = CompletableDeferred<JsonObject>()
      val waiting = async {
        scopes
          .interaction(scope, credential = "Bearer owner") { emitted.complete(it) }
          .elicitForm("Choose", JsonObject(emptyMap()), timeoutMillis = 5_000)
      }
      val id = emitted.await()["id"]!!.jsonPrimitive.content
      val response = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", buildJsonObject { put("action", "decline") })
      }
      for (other in listOf(null, "Bearer someone-else")) {
        assertEquals(
          ServeMcpRequestScopes.ResponseDisposition.UNKNOWN_REQUEST,
          scopes.acceptResponse(scope.id, response, other),
        )
      }
      assertEquals(
        ServeMcpRequestScopes.ResponseDisposition.ACCEPTED,
        scopes.acceptResponse(scope.id, response, "Bearer owner"),
      )
      assertEquals(ServeCatalogMcp.FormElicitationAction.DECLINE, waiting.await()?.action)
    }

  @Test
  fun `an accepted answer counts only while the original authorization still holds`() =
    runBlocking {
      fun answering(action: ServeCatalogMcp.FormElicitationAction) =
        object : ServeCatalogMcp.ClientInteraction {
          override val formElicitationSupported = true

          override suspend fun elicitForm(
            message: String,
            requestedSchema: JsonObject,
            timeoutMillis: Long,
          ) =
            ServeCatalogMcp.FormElicitationResult(
              action,
              if (action == ServeCatalogMcp.FormElicitationAction.ACCEPT) JsonObject(emptyMap())
              else null,
            )
        }
      val accept = ServeCatalogMcp.FormElicitationAction.ACCEPT
      val decline = ServeCatalogMcp.FormElicitationAction.DECLINE
      suspend fun ServeCatalogMcp.ClientInteraction.ask() =
        elicitForm("Choose", JsonObject(emptyMap()), 1_000)?.action

      assertEquals(accept, answering(accept).reauthorizedOnAccept { true }.ask())
      assertNull(answering(accept).reauthorizedOnAccept { false }.ask())
      assertEquals(decline, answering(decline).reauthorizedOnAccept { false }.ask())
      val unsupported = ServeCatalogMcp.ClientInteraction.Unsupported
      assertTrue(unsupported.reauthorizedOnAccept { true } === unsupported)
    }
}
