package ee.schimke.composeai.cli.serve

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Kobento component JSON to `.rc`, through Kobento's hosted `convert` (its MCP server's
 * `tools/call`). Only Kobento can expand its component calls, so these bytes are Kobento's own,
 * which decode in AndroidX `remote-core` (compose-ui-builder's `UI_BUILDER_KOBENTO.md` §3b, route
 * 1). One service-account [token] for the whole server, from `SERVE_UI_BUILDER_KOBENTO_TOKEN`.
 */
internal class KobentoConvertCompiler(
  private val token: String,
  private val endpoint: URI = DEFAULT_ENDPOINT,
  private val post: (URI, String, String) -> Pair<Int, String> = ::httpPost,
) : DocumentCompiler {

  override fun compile(content: String): ByteArray {
    val document = runCatching {
      Json.parseToJsonElement(content)
    }
      .getOrElse { throw IllegalArgumentException("the Kobento component JSON did not parse") }
    val request = buildJsonObject {
      put("jsonrpc", "2.0")
      put("id", 1)
      put("method", "tools/call")
      put(
        "params",
        buildJsonObject {
          put("name", "convert")
          put(
            "arguments",
            buildJsonObject {
              put("json", document)
              put("include_base64", true)
            },
          )
        },
      )
    }
    val (status, body) = post(endpoint, token, request.toString())
    if (status == 401 || status == 403)
      throw IllegalStateException("Kobento refused this server's token (HTTP $status)")
    if (status !in 200..299) throw IllegalStateException("Kobento convert answered HTTP $status")
    return rcBytes(body)
  }

  companion object {
    /** The variable (or its `_FILE`, a Docker secret) that holds the service-account token. */
    const val TOKEN_ENV: String = "SERVE_UI_BUILDER_KOBENTO_TOKEN"

    /** The `CatalogDocumentFormat.id` this compiles. */
    const val FORMAT_ID: String = "kobento-component-json"

    val DEFAULT_ENDPOINT: URI = URI.create("https://mcp.kobento.dev/mcp")

    /**
     * The `.rc` bytes in a `convert` answer: a JSON-RPC result whose first text content is a JSON
     * object carrying `rc_base64`. Accepts a single `data:` line of an event stream too, the other
     * shape an MCP server may answer in.
     */
    internal fun rcBytes(body: String): ByteArray {
      val payload =
        body.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim()
          ?: body
      val answer = runCatching {
        Json.parseToJsonElement(payload).jsonObject
      }
        .getOrElse { throw IllegalStateException("Kobento convert's answer was not JSON") }
      (answer["error"] as? JsonObject)?.let { error ->
        throw IllegalArgumentException(
          "Kobento convert refused the design: " +
            ((error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()),
        )
      }
      val result =
        answer["result"] as? JsonObject
          ?: throw IllegalStateException("Kobento convert answered without a result")
      val texts =
        (result["content"] as? JsonArray)
          .orEmpty()
          .mapNotNull { (it as? JsonObject)?.get("text") as? JsonPrimitive }
          .mapNotNull { it.contentOrNull }
      if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true)
        throw IllegalArgumentException(
          "Kobento convert refused the design: ${texts.joinToString(" ").take(500)}",
        )
      val encoded =
        texts.firstNotNullOfOrNull { text ->
          runCatching { Json.parseToJsonElement(text).jsonObject }
            .getOrNull()
            ?.let { (it["rc_base64"] as? JsonPrimitive)?.contentOrNull }
        } ?: throw IllegalStateException("Kobento convert's answer carried no rc_base64")
      return Base64.getDecoder().decode(encoded)
    }

    private val client: HttpClient by lazy {
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    }

    private fun httpPost(endpoint: URI, token: String, body: String): Pair<Int, String> {
      val request =
        HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(60))
          .header("authorization", "Bearer $token")
          .header("content-type", "application/json")
          .header("accept", "application/json, text/event-stream")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
      val response = client.send(request, HttpResponse.BodyHandlers.ofString())
      return response.statusCode() to response.body()
    }
  }
}
