package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.ToolDef
import ee.schimke.composeai.mcp.protocol.ToolIcon
import java.util.Base64
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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
 * OpenAI MCP Extensions metadata (issue #1235): `openai/…` keys that ChatGPT and Codex desktop read
 * from tool and resource `_meta`, and that every other host ignores. Spec:
 * https://github.com/openai/mcp-extensions/blob/main/docs/spec.md — sections "MCP App Entrypoints",
 * "File Extension Entrypoint", "Display Modes", "Icon Guidelines" and "Composer At-Mentions".
 *
 * Everything here is **additive**: it merges `openai/ui` / `openai/extensions` into a `_meta` that
 * already carries the portable MCP Apps keys (`ui.resourceUri`, the legacy flat `ui/resourceUri`,
 * `ui.visibility`), and never rewrites those, so Claude Code, Antigravity and any plain MCP Apps
 * host keep today's behaviour.
 */
object OpenAiUi {
  /** `_meta` key for entrypoints (tools) and display modes (UI resource contents). */
  const val UI_KEY: String = "openai/ui"

  /** `_meta` key for opt-in OpenAI capabilities on a tool, e.g. [MENTIONS_SEARCH]. */
  const val EXTENSIONS_KEY: String = "openai/extensions"

  /** `_meta["openai/extensions"]` key marking the composer `@`-mention search tool. */
  const val MENTIONS_SEARCH: String = "mentions/search"

  /**
   * `_meta` key the host adds to an app→server `tools/call` inside a file entrypoint (`path`), and
   * puts on `resources/read` contents (`writable`, `etag`).
   */
  const val RESOURCE_KEY: String = "openai/resource"

  /** `hostContext` key carrying a deep link's app-relative URL (`{ url }`). */
  const val DEEP_LINK_KEY: String = "openai/deepLink"

  /** The `inputSchema` an entrypoint tool must accept: global and thread entrypoints pass `{}`. */
  val EMPTY_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {}
  }

  /**
   * `FileInput`, the arguments a file entrypoint tool receives: `{ file: { name, resourceUri } }`.
   */
  val FILE_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      putJsonObject("file") {
        put("type", "object")
        putJsonObject("properties") {
          putJsonObject("name") {
            put("type", "string")
            put("description", "Name of the opened file, with extension, without the path.")
          }
          putJsonObject("resourceUri") {
            put("type", "string")
            put("description", "Opaque host-resource:// URI; read it through the host.")
          }
        }
        putJsonArray("required") {
          add(JsonPrimitive("name"))
          add(JsonPrimitive("resourceUri"))
        }
      }
    }
    putJsonArray("required") { add(JsonPrimitive("file")) }
  }

  /** `MentionSearchParams`: `{ query }`, which may be empty. */
  val MENTION_SEARCH_INPUT_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
      putJsonObject("query") {
        put("type", "string")
        put("description", "Typeahead search text from the composer; may be empty.")
      }
    }
    putJsonArray("required") { add(JsonPrimitive("query")) }
  }

  /** ChatGPT supports `inline` and `fullscreen`; `pip` is accepted by the schema but not shown. */
  enum class DisplayMode(val wire: String) {
    INLINE("inline"),
    FULLSCREEN("fullscreen"),
    PIP("pip"),
  }

  /**
   * The monochrome 20x20 stroke icon the spec's icon guidelines ask for: transparent background,
   * `currentColor`, 1.33px strokes. A preview frame with a picture in it.
   */
  const val PREVIEW_ICON_SVG: String =
    """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.33" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3.5" width="14" height="13" rx="2"/><circle cx="7.5" cy="8" r="1.25"/><path d="M3.5 14.5l4-3.5 3 2.5 2.5-2 3.5 3"/></svg>"""

  /** [svg] as an MCP `Icon` with a base64 `data:` URI, so it needs no server to fetch from. */
  fun svgIcon(svg: String = PREVIEW_ICON_SVG): ToolIcon =
    ToolIcon(
      src =
        "data:image/svg+xml;base64," +
          Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8)),
      mimeType = "image/svg+xml",
      sizes = listOf("any"),
    )

  /**
   * `_meta` for an MCP App tool that the host can also open directly: the portable `ui.resourceUri`
   * (+ legacy `ui/resourceUri`, + `ui.visibility` when given) plus `openai/ui.entrypoints`.
   */
  fun appToolMeta(
    resourceUri: String,
    entrypoints: List<OpenAiEntrypoint>,
    visibility: List<String>? = null,
  ): JsonObject {
    val portable = buildJsonObject {
      putJsonObject("ui") {
        put("resourceUri", resourceUri)
        if (visibility != null) putJsonArray("visibility") { visibility.forEach { add(it) } }
      }
      put("ui/resourceUri", resourceUri)
    }
    return withEntrypoints(portable, entrypoints)
  }

  /**
   * [meta] with `openai/ui.entrypoints` set to [entrypoints]; every other key, including other
   * `openai/ui` fields, is kept. An empty list leaves [meta] as it was.
   */
  fun withEntrypoints(meta: JsonObject?, entrypoints: List<OpenAiEntrypoint>): JsonObject {
    val base = meta ?: JsonObject(emptyMap())
    if (entrypoints.isEmpty()) return base
    require(entrypoints.map { it.type }.toSet().size == entrypoints.size) {
      "at most one entrypoint of each type per tool: ${entrypoints.map { it.type }}"
    }
    return mergeOpenAiUi(
      base,
      mapOf("entrypoints" to JsonArray(entrypoints.map { it.toJson() })),
    )
  }

  /**
   * `_meta["openai/ui"]` display modes for a UI **resource** content item (and its `resources/list`
   * descriptor), merged into [meta]. [preferred], when given, must be one of [available].
   */
  fun withDisplayModes(
    meta: JsonObject?,
    available: List<DisplayMode> = listOf(DisplayMode.INLINE, DisplayMode.FULLSCREEN),
    preferred: DisplayMode? = null,
  ): JsonObject {
    require(available.isNotEmpty()) { "availableDisplayModes must not be empty" }
    require(preferred == null || preferred in available) {
      "preferredDisplayMode $preferred is not in availableDisplayModes $available"
    }
    val fields = buildMap {
      put("availableDisplayModes", JsonArray(available.map { JsonPrimitive(it.wire) }))
      if (preferred != null) put("preferredDisplayMode", JsonPrimitive(preferred.wire))
    }
    return mergeOpenAiUi(meta ?: JsonObject(emptyMap()), fields)
  }

  /**
   * `_meta` for the composer `@`-mention search tool: `openai/extensions["mentions/search"] = {}`
   * and `ui.visibility = ["app"]`, which the spec requires of a mention tool. [extra] is merged
   * underneath, so a caller can add keys but cannot drop the `app` visibility.
   */
  fun mentionSearchToolMeta(extra: JsonObject? = null): JsonObject {
    val base = extra ?: JsonObject(emptyMap())
    val ui = (base["ui"] as? JsonObject).orEmpty()
    val visibility =
      ((ui["visibility"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
          ?: emptyList())
        .let { if ("app" in it) it else it + "app" }
    val extensions = (base[EXTENSIONS_KEY] as? JsonObject).orEmpty()
    return JsonObject(
      base +
        mapOf(
          "ui" to JsonObject(ui + ("visibility" to JsonArray(visibility.map(::JsonPrimitive)))),
          EXTENSIONS_KEY to JsonObject(extensions + (MENTIONS_SEARCH to JsonObject(emptyMap()))),
        )
    )
  }

  /**
   * [tool] turned into an entrypoint tool: `openai/ui.entrypoints` merged into its existing
   * `_meta`, a [title] (the spec's first choice for the entrypoint label) and an SVG icon. The
   * existing `ui.resourceUri` must already be there — an entrypoint without an app has nothing to
   * open.
   */
  fun entrypointTool(
    tool: ToolDef,
    entrypoints: List<OpenAiEntrypoint>,
    title: String? = tool.title,
    icon: ToolIcon = svgIcon(),
  ): ToolDef {
    val ui = tool.meta?.get("ui") as? JsonObject
    requireNotNull(ui?.get("resourceUri")) {
      "${tool.name}: an entrypoint tool needs _meta.ui.resourceUri"
    }
    return tool.copy(
      meta = withEntrypoints(tool.meta, entrypoints),
      title = title,
      icons = listOf(icon),
    )
  }

  /**
   * The absolute path the host injects as `_meta["openai/resource"].path` on an app→server tool
   * call inside a file entrypoint, or null outside one. [requestMeta] is the `tools/call`
   * `params._meta`.
   */
  fun resourcePath(requestMeta: JsonObject?): String? =
    ((requestMeta?.get(RESOURCE_KEY) as? JsonObject)?.get("path") as? JsonPrimitive)
      ?.contentOrNull
      ?.takeIf { it.isNotBlank() }

  /** [resourcePath] for the `tools/call` currently being served (see [OpenAiRequestMeta]). */
  suspend fun currentResourcePath(): String? = resourcePath(currentRequestMeta())

  /** The `tools/call` `params._meta` currently being served, or null outside a tool call. */
  suspend fun currentRequestMeta(): JsonObject? = coroutineContext[OpenAiRequestMeta]?.meta

  private fun mergeOpenAiUi(meta: JsonObject, fields: Map<String, JsonElement>): JsonObject {
    val existing = (meta[UI_KEY] as? JsonObject).orEmpty()
    return JsonObject(meta + (UI_KEY to JsonObject(existing + fields)))
  }

  private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}

/** One `_meta["openai/ui"].entrypoints[]` entry. At most one of each type per tool. */
sealed interface OpenAiEntrypoint {
  val type: String

  fun toJson(): JsonObject

  /** Sidebar navigation; opens fullscreen. The tool must accept `{}`. */
  data object Global : OpenAiEntrypoint {
    override val type: String = "global"

    override fun toJson(): JsonObject = buildJsonObject { put("type", type) }
  }

  /** A content tab inside a thread, one instance per thread. The tool must accept `{}`. */
  data object Thread : OpenAiEntrypoint {
    override val type: String = "thread"

    override fun toJson(): JsonObject = buildJsonObject { put("type", type) }
  }

  /**
   * A viewer for files with these [extensions] (each `.xxx`), replacing the host's default viewer.
   * The tool takes [OpenAiUi.FILE_INPUT_SCHEMA]. Desktop only. Claim only formats this server owns
   * — never `.kt` (#1235).
   */
  data class File(val extensions: List<String>) : OpenAiEntrypoint {
    init {
      require(extensions.isNotEmpty()) { "a file entrypoint needs at least one extension" }
      extensions.forEach { ext ->
        require(ext.length > 1 && ext.startsWith(".") && ext.none { it.isWhitespace() }) {
          "file entrypoint extensions use HTML accept's '.ext' form: '$ext'"
        }
      }
    }

    override val type: String = "file"

    override fun toJson(): JsonObject = buildJsonObject {
      put("type", type)
      putJsonArray("extensions") { extensions.forEach { add(JsonPrimitive(it)) } }
    }
  }
}

/**
 * The `tools/call` request's `params._meta`, carried in the coroutine context by
 * [installComposePreviewHandlers] so a tool can read host-injected keys (OpenAI's
 * `openai/resource.path`) without a parameter on every tool. Read it with
 * [OpenAiUi.currentRequestMeta].
 */
class OpenAiRequestMeta(val meta: JsonObject?) :
  AbstractCoroutineContextElement(OpenAiRequestMeta) {
  companion object Key : CoroutineContext.Key<OpenAiRequestMeta>
}
