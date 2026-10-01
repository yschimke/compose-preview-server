package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
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
  private val maxInteractionTimeoutMillis: Long = DEFAULT_INTERACTION_TIMEOUT_MILLIS,
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
    val pending: ConcurrentHashMap<String, Pending> = ConcurrentHashMap(),
    /** The client declared `extensions["openai/elicitation"].form`; see [ServeOpenAiForms]. */
    val openAiFormsSupported: Boolean = false,
  )

  /**
   * One server request awaiting its answer. [credential] is the fingerprint of the credential the
   * eliciting POST presented (see [credentialFingerprint]); the answer must arrive with the same
   * one, so knowing a session id alone is never enough to answer on somebody else's behalf.
   */
  internal class Pending(val response: CompletableDeferred<JsonObject>, val credential: ByteArray)

  private val random = SecureRandom()
  private val scopes = ConcurrentHashMap<String, Scope>()

  /**
   * Opens a scope, or returns null when every slot holds a pending interaction. `initialize` is
   * ungated, so a full registry evicts its least recently used idle scope rather than refusing: an
   * anonymous caller refreshing 64 sessions must not lock legitimate clients out. An evicted client
   * loses nothing but elicitation, because an unknown session id falls back to the stateless JSON
   * path.
   */
  @Synchronized
  fun open(
    protocolVersion: String,
    formElicitationSupported: Boolean,
    openAiFormsSupported: Boolean = false,
  ): Scope? {
    require(protocolVersion.isNotBlank())
    expireIdle()
    if (scopes.size >= maxSessions) {
      val evictable =
        scopes.values.filter { it.pending.isEmpty() }.minByOrNull { it.lastUsedMillis.get() }
          ?: return null
      close(evictable.id)
    }
    while (true) {
      val scope =
        Scope(
          id = newId(),
          protocolVersion = protocolVersion,
          formElicitationSupported = formElicitationSupported,
          lastUsedMillis = AtomicLong(nowMillis()),
          pendingPermits = Semaphore(maxPendingPerSession),
          openAiFormsSupported = openAiFormsSupported,
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
    scope.pending.values.forEach { it.response.cancel() }
    scope.pending.clear()
    return true
  }

  /**
   * Delivers a client's answer to the pending server request it names. [credential] is the raw
   * credential material of the POST carrying the answer; it must fingerprint the same as the POST
   * that asked, or the answer is refused as [ResponseDisposition.UNKNOWN_REQUEST] and the pending
   * request is left untouched for its real owner (and, failing that, its timeout).
   */
  fun acceptResponse(
    sessionId: String?,
    response: JsonObject,
    credential: String? = null,
  ): ResponseDisposition {
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
    val pending = scope.pending[id] ?: return ResponseDisposition.UNKNOWN_REQUEST
    if (!MessageDigest.isEqual(pending.credential, credentialFingerprint(credential))) {
      return ResponseDisposition.UNKNOWN_REQUEST
    }
    if (!scope.pending.remove(id, pending)) return ResponseDisposition.UNKNOWN_REQUEST
    return if (pending.response.complete(response)) {
      ResponseDisposition.ACCEPTED
    } else {
      ResponseDisposition.UNKNOWN_REQUEST
    }
  }

  fun interaction(
    scope: Scope,
    /** The eliciting POST's credential material; its answer must present the same. */
    credential: String? = null,
    emit: suspend (JsonObject) -> Unit,
  ): ServeCatalogMcp.ClientInteraction =
    object : ServeCatalogMcp.ClientInteraction {
      override val formElicitationSupported = scope.formElicitationSupported
      override val openAiFormsSupported = scope.openAiFormsSupported

      override suspend fun elicitForm(
        message: String,
        requestedSchema: JsonObject,
        timeoutMillis: Long,
      ): ServeCatalogMcp.FormElicitationResult? {
        if (!formElicitationSupported || message.isBlank() || timeoutMillis <= 0) return null
        val params = buildJsonObject {
          put("message", message)
          put("requestedSchema", requestedSchema)
        }
        val response =
          exchange(scope, credential, emit, "elicitation/create", params, timeoutMillis)
            as? Exchange.Response ?: return null
        return parseElicitationResponse(response.body)
      }

      override suspend fun elicitOpenAiForm(
        message: String,
        requestedSchema: JsonObject,
        timeoutMillis: Long,
      ): OpenAiFormElicitation {
        if (!openAiFormsSupported || message.isBlank() || timeoutMillis <= 0) {
          return OpenAiFormElicitation.Unsupported
        }
        val params = ServeOpenAiForms.requestParams(message, requestedSchema)
        return when (
          val exchanged =
            exchange(scope, credential, emit, ServeOpenAiForms.METHOD, params, timeoutMillis)
        ) {
          // Another interaction holds the session's only slot: ask some other way.
          Exchange.Busy -> OpenAiFormElicitation.Unsupported
          Exchange.TimedOut -> OpenAiFormElicitation.NoAnswer
          is Exchange.Response ->
            // A JSON-RPC error is the client refusing the method, as #1253 reads it: fall back.
            if (exchanged.body["error"] != null) OpenAiFormElicitation.Unsupported
            else
              parseElicitationResponse(exchanged.body)?.let(OpenAiFormElicitation::Answered)
                ?: OpenAiFormElicitation.NoAnswer
        }
      }
    }

  private sealed interface Exchange {
    data object Busy : Exchange

    data object TimedOut : Exchange

    data class Response(val body: JsonObject) : Exchange
  }

  /** Sends one server-to-client request on [scope] and waits, bounded, for its answer. */
  private suspend fun exchange(
    scope: Scope,
    credential: String?,
    emit: suspend (JsonObject) -> Unit,
    method: String,
    params: JsonObject,
    timeoutMillis: Long,
  ): Exchange {
    if (!scope.pendingPermits.tryAcquire()) return Exchange.Busy
    val id = "elicit-${newId()}"
    val response = CompletableDeferred<JsonObject>()
    val pending = Pending(response, credentialFingerprint(credential))
    if (scope.pending.putIfAbsent(id, pending) != null) {
      scope.pendingPermits.release()
      return Exchange.Busy
    }
    scope.lastUsedMillis.set(nowMillis())
    return try {
      withTimeoutOrNull(timeoutMillis.coerceAtMost(maxInteractionTimeoutMillis)) {
        emit(
          buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
          }
        )
        Exchange.Response(response.await())
      } ?: Exchange.TimedOut
    } finally {
      scope.pending.remove(id, pending)
      scope.pendingPermits.release()
    }
  }

  /**
   * Runs one request inside [scope] and picks the response shape lazily. A call that never sends
   * the client anything completes through [onReply] exactly as the stateless JSON path would; only
   * once the first server-to-client message is emitted does [onStream] take over, receiving that
   * message, the rest of the outbound queue (closed when the call finishes) and the final reply.
   */
  suspend fun <R> dispatchLazily(
    scope: Scope,
    credential: String? = null,
    dispatch: suspend (ServeCatalogMcp.ClientInteraction) -> R,
    onReply: suspend (R) -> Unit,
    onStream:
      suspend (first: JsonObject, rest: ReceiveChannel<JsonObject>, reply: Deferred<R>) -> Unit,
  ) = coroutineScope {
    val outbound = Channel<JsonObject>(Channel.UNLIMITED)
    val reply = async {
      try {
        dispatch(interaction(scope, credential) { outbound.send(it) })
      } finally {
        outbound.close()
      }
    }
    val first =
      select<JsonObject?> {
        reply.onAwait { null }
        outbound.onReceiveCatching { it.getOrNull() }
      }
    if (first == null) onReply(reply.await()) else onStream(first, outbound, reply)
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

  companion object {
    /** The whole send-and-wait of one interaction; a person needs time to read and choose. */
    const val DEFAULT_INTERACTION_TIMEOUT_MILLIS: Long = 2 * 60 * 1000L
  }

  private fun credentialFingerprint(credential: String?): ByteArray =
    MessageDigest.getInstance("SHA-256")
      .digest((if (credential == null) "\u0000none" else "c:$credential").encodeToByteArray())

  private fun newId(): String {
    val bytes = ByteArray(24)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
