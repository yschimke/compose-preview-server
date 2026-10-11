package ee.schimke.composeai.mcp

import io.modelcontextprotocol.kotlin.sdk.server.ServerSession
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.BaseRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.CustomRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.RequestMeta
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI form elicitation: the `openai/elicitation` extension's resource-selection fields, with
 * thumbnails and previews. Spec: https://github.com/openai/mcp-extensions/blob/main/docs/spec.md
 * ("OpenAI Form Elicitation"); wire shapes follow the TS SDK's `server/forms` and the Python SDK's
 * `form/_resource_picker.py`.
 *
 * Only the legacy `openai/elicitation/create` request is implemented: the Kotlin MCP SDK negotiates
 * at most `2025-11-25` and has no MRTR, so clients needing `2026-07-28` get plain MCP behaviour.
 * The SDK's typed `elicitation/create` would drop `x-openai-input`, so the request is a
 * [CustomRequest] carrying real params under [RAW_PARAMS_META_KEY], swapped in by
 * [RawParamsTransport]; the answer decodes as an ordinary [ElicitResult].
 */
object OpenAiForms {
  /** `capabilities.extensions` key a host declares (`{ form: {} }`) when it renders these forms. */
  const val EXTENSION_ID: String = "openai/elicitation"

  /** The legacy direct-connection request method. */
  const val METHOD: String = "openai/elicitation/create"

  /** Field-level key declaring a resource picker. */
  const val INPUT_KEY: String = "x-openai-input"

  /** Resource-option `_meta` key holding an `MCP.Icon` thumbnail. */
  const val THUMBNAIL_KEY: String = "openai/thumbnail"

  /** Resource-option `_meta` key holding `{ target: PreviewTarget }`. */
  const val PREVIEW_KEY: String = "openai/preview"

  /** Private `_meta` key [RawParamsTransport] replaces with the request's real params. */
  internal const val RAW_PARAMS_META_KEY: String = "ee.schimke.composeai/rawParams"

  /**
   * True when the client declared `capabilities.extensions["openai/elicitation"].form` as an
   * object, the same check the TS and Python SDKs make.
   */
  fun supportsForms(capabilities: ClientCapabilities?): Boolean =
    capabilities?.extensions?.get(EXTENSION_ID)?.get("form") is JsonObject

  /** Multi-select modes; the spec's default is [EXPLICIT]. */
  enum class Selection(val wire: String) {
    /** Items are chosen by selecting or deselecting them. */
    EXPLICIT("explicit"),

    /** Items are chosen by adding or removing them; everything left is the answer. */
    IMPLICIT("implicit"),
  }

  /** One option: an `MCP.Resource` whose `_meta` may carry a thumbnail and a preview target. */
  data class ResourceOption(
    val uri: String,
    val name: String,
    val title: String? = null,
    val description: String? = null,
    val mimeType: String? = null,
    /** Base64 PNG for `_meta["openai/thumbnail"]`; null sends no thumbnail. */
    val thumbnailPngBase64: String? = null,
    /** `_meta["openai/preview"].target`; null sends no preview. */
    val previewTarget: JsonObject? = null,
  ) {
    /** The option on the wire; [uri] travels [encodeUri]'d, as the field's `uri` format needs. */
    fun toJson(): JsonObject = buildJsonObject {
      put("uri", encodeUri(uri))
      put("name", name)
      title?.let { put("title", it) }
      description?.let { put("description", it) }
      mimeType?.let { put("mimeType", it) }
      if (thumbnailPngBase64 != null || previewTarget != null) {
        putJsonObject("_meta") {
          thumbnailPngBase64?.let { put(THUMBNAIL_KEY, thumbnailIcon(it)) }
          previewTarget?.let { target -> putJsonObject(PREVIEW_KEY) { put("target", target) } }
        }
      }
    }
  }

  /** An `MCP.Icon` for a thumbnail; always a data URI, since a local server has no HTTPS origin. */
  fun thumbnailIcon(pngBase64: String): JsonObject = buildJsonObject {
    put("src", "data:image/png;base64,$pngBase64")
    put("mimeType", "image/png")
  }

  /** `PreviewTarget` that opens an option through an MCP App tool on this server. */
  fun appToolTarget(tool: String, arguments: JsonObject): JsonObject {
    require(tool.isNotBlank()) { "PreviewTarget.name must not be blank" }
    return buildJsonObject {
      put("type", "mcp_app_tool")
      put("name", tool)
      put("arguments", arguments)
    }
  }

  /**
   * A single-select resource field: a `uri`-format string that submits one URI. Selection modes are
   * multi-select only, so none is sent. [default] must name one of [options].
   */
  fun singleResourceField(
    options: List<ResourceOption>,
    title: String? = null,
    description: String? = null,
    default: String? = null,
  ): JsonObject {
    require(default == null || options.any { it.uri == default }) {
      "Defaults must name supplied resources"
    }
    return buildJsonObject {
      put("type", "string")
      title?.let { put("title", it) }
      description?.let { put("description", it) }
      put("format", "uri")
      put(INPUT_KEY, resourceInput(options, selection = null))
      default?.let { put("default", encodeUri(it)) }
    }
  }

  /**
   * A multi-select resource field: an array of `uri`-format strings. [defaults] must name supplied
   * options and are refused with [Selection.IMPLICIT], as the spec requires.
   */
  fun multiResourceField(
    options: List<ResourceOption>,
    title: String? = null,
    description: String? = null,
    selection: Selection = Selection.EXPLICIT,
    defaults: List<String>? = null,
    minItems: Int? = null,
    maxItems: Int? = null,
  ): JsonObject {
    if (defaults != null) {
      require(selection != Selection.IMPLICIT) { "Implicit selection cannot specify a default" }
      require(defaults.all { uri -> options.any { it.uri == uri } }) {
        "Defaults must name supplied resources"
      }
    }
    return buildJsonObject {
      put("type", "array")
      title?.let { put("title", it) }
      description?.let { put("description", it) }
      minItems?.let { put("minItems", it) }
      maxItems?.let { put("maxItems", it) }
      putJsonObject("items") {
        put("type", "string")
        put("format", "uri")
      }
      put(INPUT_KEY, resourceInput(options, selection))
      defaults?.let { uris -> putJsonArray("default") { uris.forEach { add(encodeUri(it)) } } }
    }
  }

  /** The `ResourceInput`: no `userOptions`, so explicit selection shows no upload input. */
  private fun resourceInput(options: List<ResourceOption>, selection: Selection?): JsonObject =
    buildJsonObject {
      put("type", "resource")
      selection?.let { put("selection", it.wire) }
      putJsonArray("options") { options.forEach { add(it.toJson()) } }
    }

  /** A form's `requestedSchema`: an object of [properties], with [required] field names. */
  fun form(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
      put("type", "object")
      putJsonObject("properties") { properties.forEach { (name, field) -> put(name, field) } }
      if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

  /**
   * [uri] as a valid RFC 3986 URI: everything outside the unreserved set and `:/?@!$&'()*+,;=` is
   * percent-encoded as UTF-8 (`%` included) so [decodeUri] reverses it exactly. Hosts that validate
   * the `uri` format reject the spaces in multipreview names.
   */
  fun encodeUri(uri: String): String {
    if (uri.all { it.isUriSafe() }) return uri
    return buildString {
      // Byte by byte: a non-ASCII byte is never safe, so every multi-byte character is escaped.
      uri.encodeToByteArray().forEach { byte ->
        val char = (byte.toInt() and 0xFF).toChar()
        if (char.isUriSafe()) append(char)
        else {
          append('%')
          append(HEX[(byte.toInt() shr 4) and 0xF])
          append(HEX[byte.toInt() and 0xF])
        }
      }
    }
  }

  /** Reverses [encodeUri]; null when [encoded] holds a malformed escape. */
  fun decodeUri(encoded: String): String? {
    if ('%' !in encoded) return encoded
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < encoded.length) {
      val char = encoded[i]
      if (char == '%') {
        if (i + 3 > encoded.length) return null
        val high = encoded[i + 1].digitToIntOrNull(16) ?: return null
        val low = encoded[i + 2].digitToIntOrNull(16) ?: return null
        bytes.write(high * 16 + low)
        i += 3
      } else {
        // encodeUri leaves only ASCII unescaped; anything else came from the host verbatim.
        val end = if (char.isHighSurrogate() && i + 1 < encoded.length) i + 2 else i + 1
        bytes.write(encoded.substring(i, end).encodeToByteArray())
        i = end
      }
    }
    return bytes.toByteArray().decodeToString()
  }

  private const val HEX = "0123456789ABCDEF"

  private fun Char.isUriSafe(): Boolean =
    this in 'a'..'z' ||
      this in 'A'..'Z' ||
      this in '0'..'9' ||
      this in "-._~" ||
      this in ":/?@!$&'()*+,;="

  /**
   * The URI a single-select field submitted, or null when missing, not a string, or not in
   * [offered] (without `userOptions` only supplied options are valid).
   */
  fun selectedUri(content: JsonObject?, field: String, offered: Collection<String>): String? =
    (content?.get(field) as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.let { offeredUri(it, offered) }

  /** The member of [offered] that [answer] names, whether sent encoded or as given. */
  private fun offeredUri(answer: String, offered: Collection<String>): String? =
    answer.takeIf { it in offered }
      ?: decodeUri(answer)?.takeIf { it in offered }
      ?: offered.firstOrNull { encodeUri(it) == answer }

  /**
   * The URIs a multi-select field submitted, in order, or null unless an array of distinct offered
   * URIs.
   */
  fun selectedUris(
    content: JsonObject?,
    field: String,
    offered: Collection<String>,
  ): List<String>? {
    val values = content?.get(field) as? JsonArray ?: return null
    val uris = values.map {
      (it as? JsonPrimitive)
        ?.takeIf { p -> p.isString }
        ?.content
        ?.let { answer -> offeredUri(answer, offered) } ?: return null
    }
    if (uris.toSet().size != uris.size) return null
    return uris
  }

  /**
   * Sends `openai/elicitation/create` when the client declared the extension; otherwise, or when
   * the client rejected it, [FormElicitation.Unsupported] so the caller falls back.
   */
  internal suspend fun elicit(
    session: ServerSession?,
    message: String,
    requestedSchema: JsonObject,
    timeoutMs: Long,
  ): FormElicitation {
    if (session == null || !supportsForms(session.clientCapabilities)) {
      return FormElicitation.Unsupported
    }
    val request = request(message, requestedSchema)
    return awaitElicitation(timeoutMs) {
      session.request<ElicitResult>(request, RequestOptions(timeout = timeoutMs.milliseconds))
    }
  }

  /** The [CustomRequest] carrying the real params for [RawParamsTransport]. */
  internal fun request(message: String, requestedSchema: JsonObject): CustomRequest {
    val params = buildJsonObject {
      put("mode", "form")
      put("message", message)
      put("requestedSchema", requestedSchema)
    }
    return CustomRequest(
      Method.Custom(METHOD),
      BaseRequestParams(meta = RequestMeta(buildJsonObject { put(RAW_PARAMS_META_KEY, params) })),
    )
  }

  /**
   * Replaces an outgoing request's params with `_meta[RAW_PARAMS_META_KEY]`; other messages pass
   * through.
   */
  internal fun unwrapRawParams(message: JSONRPCMessage): JSONRPCMessage {
    if (message !is JSONRPCRequest) return message
    val meta = (message.params as? JsonObject)?.get("_meta") as? JsonObject ?: return message
    val raw = meta[RAW_PARAMS_META_KEY] as? JsonObject ?: return message
    val rest = JsonObject(meta - RAW_PARAMS_META_KEY)
    val params: JsonElement = if (rest.isEmpty()) raw else JsonObject(raw + ("_meta" to rest))
    return message.copy(params = params)
  }
}

/** A [Transport] that sends [OpenAiForms.unwrapRawParams]'d messages through [delegate]. */
internal class RawParamsTransport(private val delegate: Transport) : Transport by delegate {
  override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) {
    delegate.send(OpenAiForms.unwrapRawParams(message), options)
  }
}
