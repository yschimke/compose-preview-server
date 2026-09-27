package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Small, bounded session registry for MCP 2025 request-scoped server interactions.
 *
 * It is not a second application state store. A session remembers only a negotiated client
 * capability and the response currently awaited by one HTTP request. Designs, grants and
 * authorization continue to live in their existing authoritative stores and are rechecked per call.
 */
internal class ServeMcpRequestScopes(
  private val maxSessions: Int = 64,
  private val maxPendingPerSession: Int = 1,
  private val idleTimeoutMillis: Long = 5 * 60 * 1000L,
  private val maxInteractionTimeoutMillis: Long = 2 * 60 * 1000L,
  private val nowMillis: () -> Long = System::currentTimeMillis,
) {
  init {
    require(maxSessions > 0)
    require(maxPendingPerSession > 0)
    require(idleTimeoutMillis > 0)
    require(maxInteractionTimeoutMillis > 0)
  }

  enum class ResponseDisposition {
    ACCEPTED,
    UNKNOWN_SESSION,
    UNKNOWN_REQUEST,
    INVALID_RESPONSE,
  }

  internal data class Scope(
    val id: String,
    val protocolVersion: String,
    val formElicitationSupported: Boolean,
    val lastUsedMillis: AtomicLong,
    val pendingPermits: Semaphore,
    val pending: ConcurrentHashMap<String, CompletableDeferred<JsonObject>> = ConcurrentHashMap(),
  )

  private val random = SecureRandom()
  private val scopes = ConcurrentHashMap<String, Scope>()

  @Synchronized
  fun open(protocolVersion: String, formElicitationSupported: Boolean): Scope? {
    require(protocolVersion.isNotBlank())
    expireIdle()
    if (scopes.size >= maxSessions) return null
    while (true) {
      val scope =
        Scope(
          id = newId(),
          protocolVersion = protocolVersion,
          formElicitationSupported = formElicitationSupported,
          lastUsedMillis = AtomicLong(nowMillis()),
          pendingPermits = Semaphore(maxPendingPerSession),
        )
      if (scopes.putIfAbsent(scope.id, scope) == null) return scope
    }
  }

  fun find(id: String?): Scope? {
    if (id.isNullOrBlank()) return null
    expireIdle()
    return scopes[id]?.also { it.lastUsedMillis.set(nowMillis()) }
  }

  fun close(id: String): Boolean {
    val scope = scopes.remove(id) ?: return false
    scope.pending.values.forEach { it.cancel() }
    scope.pending.clear()
    return true
  }

  fun acceptResponse(sessionId: String?, response: JsonObject): ResponseDisposition {
    val scope = find(sessionId) ?: return ResponseDisposition.UNKNOWN_SESSION
    if ((response["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
      return ResponseDisposition.INVALID_RESPONSE
    }
    val hasResult = response["result"] != null
    val hasError = response["error"] != null
    if (response["method"] != null || hasResult == hasError) {
      return ResponseDisposition.INVALID_RESPONSE
    }
    val id =
      (response["id"] as? JsonPrimitive)?.contentOrNull
        ?: return ResponseDisposition.INVALID_RESPONSE
    val pending = scope.pending.remove(id) ?: return ResponseDisposition.UNKNOWN_REQUEST
    return if (pending.complete(response)) {
      ResponseDisposition.ACCEPTED
    } else {
      ResponseDisposition.UNKNOWN_REQUEST
    }
  }

  fun interaction(
    scope: Scope,
    emit: suspend (JsonObject) -> Unit,
  ): ServeCatalogMcp.ClientInteraction =
    object : ServeCatalogMcp.ClientInteraction {
      override val formElicitationSupported = scope.formElicitationSupported

      override suspend fun elicitForm(
        message: String,
        requestedSchema: JsonObject,
        timeoutMillis: Long,
      ): ServeCatalogMcp.FormElicitationResult? {
        if (!formElicitationSupported || message.isBlank() || timeoutMillis <= 0) return null
        if (!scope.pendingPermits.tryAcquire()) return null

        val id = "elicit-${newId()}"
        val response = CompletableDeferred<JsonObject>()
        if (scope.pending.putIfAbsent(id, response) != null) {
          scope.pendingPermits.release()
          return null
        }
        scope.lastUsedMillis.set(nowMillis())
        return try {
          withTimeoutOrNull(timeoutMillis.coerceAtMost(maxInteractionTimeoutMillis)) {
            emit(
              buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", "elicitation/create")
                put(
                  "params",
                  buildJsonObject {
                    put("message", message)
                    put("requestedSchema", requestedSchema)
                  },
                )
              }
            )
            parseElicitationResponse(response.await())
          }
        } finally {
          scope.pending.remove(id, response)
          scope.pendingPermits.release()
        }
      }
    }

  private fun parseElicitationResponse(
    response: JsonObject
  ): ServeCatalogMcp.FormElicitationResult? {
    if (response["error"] != null) return null
    val result = response["result"] as? JsonObject ?: return null
    val action =
      when ((result["action"] as? JsonPrimitive)?.contentOrNull) {
        "accept" -> ServeCatalogMcp.FormElicitationAction.ACCEPT
        "decline" -> ServeCatalogMcp.FormElicitationAction.DECLINE
        "cancel" -> ServeCatalogMcp.FormElicitationAction.CANCEL
        else -> return null
      }
    val content = result["content"] as? JsonObject
    if (action == ServeCatalogMcp.FormElicitationAction.ACCEPT && content == null) return null
    return ServeCatalogMcp.FormElicitationResult(action, content)
  }

  private fun expireIdle() {
    val cutoff = nowMillis() - idleTimeoutMillis
    scopes.values
      .filter { it.pending.isEmpty() && it.lastUsedMillis.get() <= cutoff }
      .forEach { close(it.id) }
  }

  private fun newId(): String {
    val bytes = ByteArray(24)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
