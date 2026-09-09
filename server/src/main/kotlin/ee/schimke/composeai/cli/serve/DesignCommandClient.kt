package ee.schimke.composeai.cli.serve

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The half of `design` that talks: one MCP tool call, and the device-code flow behind it.
 *
 * Everything here is behind [DesignMcpTransport] so [DesignCommandRunner] — which decides what a
 * reply means, what lands on stderr and what the exit code is — is testable against canned
 * envelopes rather than a socket.
 */
internal interface DesignMcpTransport {

  /**
   * Call one UI-builder MCP tool and return the envelope's `response` object.
   *
   * Throws [DesignAuthorizationRequired] when the server will not answer without a credential —
   * which is also how an expired grant arrives, since a server restart drops every grant and the
   * token it minted then authenticates nothing. Throws [DesignCommandFailure] for a refusal the
   * service itself spelled.
   */
  fun call(tool: String, arguments: JsonObject): JsonObject
}

/** A refusal worth printing verbatim: the server's own words, not an interpretation of them. */
internal class DesignCommandFailure(message: String) : RuntimeException(message)

/**
 * The server answered "not without a credential".
 *
 * [requestUrl] is where a grant is asked for, as the server itself names it — a deployment behind a
 * reverse proxy knows its external origin and this command does not.
 */
internal class DesignAuthorizationRequired(val requestUrl: String?, message: String) :
  RuntimeException(message)

/**
 * `POST <server>/mcp`, the same door `design-sync.mjs` and every hand-rolled `curl` used.
 *
 * Stateless Streamable HTTP: no `initialize` handshake, one POST per call. The reply is either JSON
 * or an SSE frame carrying the same JSON, and both are accepted because which one arrives depends
 * on the deployment rather than on the request.
 *
 * The credential rides in headers — `X-Compose-Preview-Token` (what this server reads) and
 * `Authorization: Bearer` (what a proxy in front of it may) — and never on the URL. That is the
 * export routes' own rule: a shared link is an address, not a credential.
 */
internal class DesignHttpTransport(
  server: String,
  /**
   * Read afresh on each call rather than captured, because the device-code flow may mint a token
   * *between* two calls of one invocation.
   */
  private val token: () -> String?,
  private val timeout: Duration,
  private val http: HttpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
) : DesignMcpTransport {

  private val base: URI = normalize(server)

  override fun call(tool: String, arguments: JsonObject): JsonObject {
    val body = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", 1)
      put("method", "tools/call")
      put(
        "params",
        buildJsonObject {
          put("name", tool)
          put("arguments", arguments)
        },
      )
    }
      .toString()
    val request =
      HttpRequest.newBuilder(base.resolve("/mcp"))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", MCP_PROTOCOL_VERSION)
        .apply {
          token()
            ?.takeIf { it.isNotBlank() }
            ?.let {
              header(ServeHttpServer.TOKEN_HEADER, it)
              header("Authorization", "Bearer $it")
            }
        }
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response =
      try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw DesignCommandFailure("$tool: interrupted")
      } catch (e: Exception) {
        throw DesignCommandFailure(
          "$tool: cannot reach $base — ${e.message ?: e::class.simpleName}"
        )
      }
    if (response.statusCode() == 401 || response.statusCode() == 403) {
      throw DesignAuthorizationRequired(
        requestUrl = response.grantRequestUrl(),
        message = response.authorizationMessage(),
      )
    }
    if (response.statusCode() !in 200..299) {
      throw DesignCommandFailure(
        "$tool: $base answered HTTP ${response.statusCode()} — ${response.body().firstLine()}"
      )
    }
    return unwrap(tool, response.body())
  }

  /** The `agentAccessRequestUrl` the 401 body carries, or the header that says the same thing. */
  private fun HttpResponse<String>.grantRequestUrl(): String? =
    runCatching {
      Json.parseToJsonElement(body())
        .jsonObject["agentAccessRequestUrl"]
        ?.jsonPrimitive
        ?.contentOrNull
    }
      .getOrNull()
      ?.takeIf { it.isNotBlank() }
      ?: headers().firstValue("X-Compose-Preview-Agent-Access").orElse(null)
      ?: base.resolve(ServeAgentGrants.REQUEST_PATH).toString()

  private fun HttpResponse<String>.authorizationMessage(): String =
    runCatching {
      Json.parseToJsonElement(body()).jsonObject["message"]?.jsonPrimitive?.contentOrNull
    }
      .getOrNull()
      ?.takeIf { it.isNotBlank() } ?: body().firstLine().ifBlank { "authorization is required" }

  private companion object {
    /** What `.mcp.json` and `design-sync.mjs` already send; this server is version-tolerant. */
    const val MCP_PROTOCOL_VERSION = "2025-06-18"
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(15)

    fun normalize(server: String): URI {
      val trimmed = server.trim().trimEnd('/')
      val spelled = if ("://" in trimmed) trimmed else "http://$trimmed"
      return runCatching { URI(spelled) }
        .getOrElse { throw DesignCommandFailure("design: --server is not a URL: '$server'") }
    }
  }
}

/**
 * The JSON-RPC reply → the UI-builder envelope's `response` object.
 *
 * Three envelopes are peeled here and each one can carry the refusal: JSON-RPC's own `error`, the
 * MCP `CallToolResult`'s `isError` (whose text is a sentence, not JSON), and the UI-builder
 * `McpResponseEnvelopeV1`'s `error` response. Losing any one of them is how a failing call comes
 * back looking like an empty artifact.
 */
internal fun unwrap(tool: String, raw: String): JsonObject {
  // An SSE frame carries the same JSON on a `data:` line. Deployments differ; callers should not.
  val payload =
    if (raw.startsWith("event:") || raw.startsWith("data:")) {
      raw.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
        ?: throw DesignCommandFailure("$tool: an SSE reply with no data frame")
    } else raw
  val message = runCatching {
    Json.parseToJsonElement(payload).jsonObject
  }
    .getOrElse {
      throw DesignCommandFailure("$tool: the reply is not JSON — ${payload.firstLine()}")
    }
  message["error"]?.let { throw DesignCommandFailure("$tool: $it") }
  val result =
    message["result"]?.jsonObject
      ?: throw DesignCommandFailure("$tool: the reply carries neither a result nor an error")
  val text =
    (result["content"] as? JsonArray)
      ?.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
      ?.joinToString("\n")
      .orEmpty()
  if (result["isError"]?.jsonPrimitive?.booleanOrNull == true) {
    // A tool error is prose — "authorization_required", "unknown UI-builder tool" — so the text is
    // the whole diagnosis and parsing it as JSON would throw the useful part away.
    if (text.containsAuthorizationRefusal()) throw DesignAuthorizationRequired(null, "$tool: $text")
    throw DesignCommandFailure("$tool: $text")
  }
  val envelope = runCatching {
    Json.parseToJsonElement(text).jsonObject
  }
    .getOrElse { throw DesignCommandFailure("$tool: the tool answered with ${text.firstLine()}") }
  val response = envelope["response"]?.jsonObject ?: envelope
  if (response["type"]?.jsonPrimitive?.contentOrNull == "error") {
    val error = response["error"]?.jsonObject
    val code = error?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
    val detail = error?.get("message")?.jsonPrimitive?.contentOrNull ?: text.firstLine()
    // `unauthorized` and `forbidden` from the service are the grant-expiry case wearing the
    // service's clothes, and offering the flow again beats making the caller read the code.
    if (code == "unauthorized" || code == "forbidden") {
      throw DesignAuthorizationRequired(null, "$tool: $detail")
    }
    throw DesignCommandFailure("$tool: $detail" + if (code.isBlank()) "" else " ($code)")
  }
  return response
}

private fun String.containsAuthorizationRefusal(): Boolean =
  contains("authorization_required") || contains("unauthorized", ignoreCase = true)

private fun String.firstLine(): String = lineSequence().firstOrNull()?.trim().orEmpty().take(400)

/**
 * Ask a human for a grant, and wait for them to decide.
 *
 * This is the flow the server has had all along — `POST /agent-access/request` returns an
 * `approveUrl` and a `userCode`, `POST /agent-access/poll` holds open until somebody clicks — and a
 * CLI is where it always belonged: print the link and the code, wait, carry on. It is strictly
 * better than the shape an agent had, which was to paste both into a chat and hope.
 *
 * The poll is held server-side rather than spun on here: each round trip is a whole request, and
 * the server answers the moment the human decides.
 */
internal class DesignAuthorizer(
  server: String,
  private val timeout: Duration,
  private val log: (String) -> Unit,
  private val http: HttpClient =
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
  private val now: () -> Long = System::currentTimeMillis,
) {

  private val base: URI =
    URI(if ("://" in server) server.trimEnd('/') else "http://${server.trimEnd('/')}")

  /** Runs the flow to a decision and returns the token, or throws with the human's answer. */
  fun authorize(
    scope: String,
    capabilities: List<String>,
    label: String,
    deadlineSeconds: Long,
  ): String {
    val opened =
      JSON.decodeFromString(
        ServeAgentGrants.OpenResponse.serializer(),
        post(
          base.resolve(ServeAgentGrants.REQUEST_PATH),
          JSON.encodeToString(
            ServeAgentGrants.OpenRequest.serializer(),
            ServeAgentGrants.OpenRequest(label = label, scope = scope, capabilities = capabilities),
          ),
        ),
      )
    // Both lines matter and neither is a secret: the code is what proves to the human that the page
    // they opened belongs to the command they just ran.
    log("design: this server needs a grant. Open ${opened.approveUrl}")
    log("design: check the page shows the code ${opened.userCode}, then approve.")
    val missing = capabilities.filterNot { it in opened.maxCapabilities }
    if (missing.isNotEmpty()) {
      log(
        "design: note — this server does not offer ${missing.joinToString(", ")}; " +
          "it grants ${opened.maxCapabilities.joinToString(", ").ifBlank { "no capabilities" }}."
      )
    }
    val deadline = now() + deadlineSeconds * 1000
    while (now() < deadline) {
      val polled =
        JSON.decodeFromString(
          ServeAgentGrants.PollResponse.serializer(),
          post(
            URI(opened.pollUrl),
            JSON.encodeToString(
              ServeAgentGrants.PollRequest.serializer(),
              ServeAgentGrants.PollRequest(
                requestId = opened.requestId,
                deviceSecret = opened.deviceSecret,
                waitSeconds = ServeAgentGrants.MAX_POLL_WAIT_SECONDS,
              ),
            ),
          ),
        )
      when (polled.status) {
        ServeAgentGrants.PollResponse.APPROVED -> {
          val token =
            polled.token
              ?: throw DesignCommandFailure("design: the grant was approved but carried no token")
          log(
            "design: approved" +
              (polled.approvedBy?.let { " by $it" } ?: "") +
              (polled.capabilities.takeIf { it.isNotEmpty() }?.let { " (${it.joinToString(", ")})" }
                ?: "")
          )
          return token
        }
        ServeAgentGrants.PollResponse.PENDING -> Unit
        else ->
          throw DesignCommandFailure(
            "design: the grant was ${polled.status}" +
              (polled.message?.let { " — $it" } ?: "") +
              "."
          )
      }
    }
    throw DesignCommandFailure(
      "design: nobody approved the grant within ${deadlineSeconds}s. Re-run when you have a human."
    )
  }

  private fun post(target: URI, body: String): String {
    val request =
      HttpRequest.newBuilder(target)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build()
    val response =
      try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw DesignCommandFailure("design: interrupted while asking for a grant")
      } catch (e: Exception) {
        throw DesignCommandFailure(
          "design: cannot reach $target — ${e.message ?: e::class.simpleName}"
        )
      }
    if (response.statusCode() !in 200..299) {
      throw DesignCommandFailure(
        "design: $target answered HTTP ${response.statusCode()} — ${response.body().firstLine()}. " +
          "A server started without --agent-grants cannot mint one; set \$${DesignCommand.TOKEN_ENV} instead."
      )
    }
    return response.body()
  }

  private companion object {
    val JSON = Json { ignoreUnknownKeys = true }
  }
}
