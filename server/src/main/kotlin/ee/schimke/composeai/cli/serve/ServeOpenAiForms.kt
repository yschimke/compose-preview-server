package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI form elicitation on the hosted `/mcp`: the `openai/elicitation` extension's single-select
 * resource picker, with a thumbnail and a preview target on each option. Spec:
 * https://github.com/openai/mcp-extensions/blob/main/docs/spec.md, section "OpenAI Form
 * Elicitation".
 *
 * **A deliberate, minimal duplicate** of the pure schema builders in `:mcp`'s `OpenAiForms`
 * (#1253). `:server` may not depend on `:mcp` — `checkServeModuleBoundary` keeps the two shipping
 * modules apart, and `:mcp`'s copy is typed against the Kotlin MCP SDK's `ClientCapabilities` and
 * `ServerSession`, which the hosted endpoint does not link (it speaks JSON-RPC over its own
 * request-scoped transport, [ServeMcpRequestScopes]). Only what the hosted picker needs is here:
 * the capability check, the single-select field, an option, a thumbnail icon, a preview target and
 * answer validation. The wire shapes are the spec's and must stay byte-identical to `:mcp`'s; both
 * are pinned by tests against the spec's own example.
 *
 * Only the legacy direct-connection `openai/elicitation/create` request is sent — the same gap
 * `:mcp` documents: the MRTR flow of MCP `2026-07-28` is not negotiated by this endpoint.
 */
internal object ServeOpenAiForms {
  /** `capabilities.extensions` key a host declares (`{ form: {} }`) when it renders these forms. */
  const val EXTENSION_ID = "openai/elicitation"

  /** The legacy direct-connection request method. */
  const val METHOD = "openai/elicitation/create"

  /** Field-level key declaring a resource picker. */
  const val INPUT_KEY = "x-openai-input"

  /** Resource-option `_meta` key holding an `MCP.Icon` thumbnail. */
  const val THUMBNAIL_KEY = "openai/thumbnail"

  /** Resource-option `_meta` key holding `{ target: PreviewTarget }`. */
  const val PREVIEW_KEY = "openai/preview"

  /**
   * True when an `initialize` request's `params.capabilities.extensions["openai/elicitation"].form`
   * is an object — exactly the check the TypeScript and Python SDKs make. Plain MCP
   * `elicitation.form` does not count: it says nothing about rendering `x-openai-input`.
   */
  fun declaredIn(initializeParams: JsonObject?): Boolean {
    val capabilities = initializeParams?.get("capabilities") as? JsonObject ?: return false
    val extensions = capabilities["extensions"] as? JsonObject ?: return false
    val extension = extensions[EXTENSION_ID] as? JsonObject ?: return false
    return extension["form"] is JsonObject
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
    fun toJson(): JsonObject = buildJsonObject {
      put("uri", uri)
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

  /**
   * An `MCP.Icon`. The spec takes an HTTPS URL or a base64 data URI; the data URI is used even on a
   * host with a public origin, so a thumbnail never depends on a signed link outliving the form.
   */
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

  /** A single-select resource field: a `uri`-format string; selection modes are multi-only. */
  fun singleResourceField(
    options: List<ResourceOption>,
    title: String? = null,
    description: String? = null,
  ): JsonObject = buildJsonObject {
    put("type", "string")
    title?.let { put("title", it) }
    description?.let { put("description", it) }
    put("format", "uri")
    putJsonObject(INPUT_KEY) {
      put("type", "resource")
      putJsonArray("options") { options.forEach { add(it.toJson()) } }
    }
  }

  /** A form's `requestedSchema`: an object of [properties], with [required] field names. */
  fun form(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
      put("type", "object")
      putJsonObject("properties") { properties.forEach { (name, field) -> put(name, field) } }
      if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(it) } }
    }

  /**
   * The URI a single-select field submitted, or null when it is missing, not a string, or not one
   * of [offered]: without `userOptions` a picker may only answer with a supplied option.
   */
  fun selectedUri(content: JsonObject?, field: String, offered: Collection<String>): String? =
    (content?.get(field) as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.contentOrNull
      ?.takeIf { it in offered }

  /** `openai/elicitation/create`'s params: the spec's `mode: form` plus the ordinary pair. */
  fun requestParams(message: String, requestedSchema: JsonObject): JsonObject = buildJsonObject {
    put("mode", "form")
    put("message", message)
    put("requestedSchema", requestedSchema)
  }
}

/** What came of an `openai/elicitation/create`, for [ServeCatalogMcp.ClientInteraction]. */
sealed interface OpenAiFormElicitation {
  /** Not declared, or the client answered the request with a JSON-RPC error: ask another way. */
  data object Unsupported : OpenAiFormElicitation

  /** The client took the request but nobody answered in time, or the answer was malformed. */
  data object NoAnswer : OpenAiFormElicitation

  data class Answered(val result: ServeCatalogMcp.FormElicitationResult) : OpenAiFormElicitation
}
