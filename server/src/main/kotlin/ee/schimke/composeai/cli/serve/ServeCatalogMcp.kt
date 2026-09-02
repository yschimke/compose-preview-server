package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.web.WebEscaping
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Stateless MCP 2025-06-18 surface over one served catalog.
 *
 * Transport is owned by [ServeHttpServer]; this class owns only MCP lifecycle messages and the
 * catalog-facing resources/tools. It shares the HTTP server's render semaphore, so a remote agent
 * cannot open a second, unmetered render lane beside the browser routes.
 */
class ServeCatalogMcp(
  private val renderSemaphore: Semaphore,
  private val renderQueueWaitSeconds: Long = 2,
) {
  data class Reply(val body: JsonObject?, val accepted: Boolean = false)

  suspend fun handle(
    system: String,
    host: ServeHost,
    request: JsonObject,
    liveAuthorization: () -> ServeMachineAuthorization.Decision,
  ): Reply {
    val id = request["id"]
    if ((request["jsonrpc"] as? JsonPrimitive)?.contentOrNull != "2.0") {
      return Reply(error(id, INVALID_REQUEST, "Expected JSON-RPC 2.0"))
    }
    val method =
      (request["method"] as? JsonPrimitive)?.contentOrNull
        ?: return Reply(error(id, INVALID_REQUEST, "Missing JSON-RPC method"))
    if (id == null) return Reply(body = null, accepted = true)
    val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

    val result =
      try {
        when (method) {
          "initialize" -> initialize(params)
          "ping" -> JsonObject(emptyMap())
          "tools/list" -> buildJsonObject { put("tools", tools()) }
          "tools/call" ->
            try {
              callTool(system, host, params, liveAuthorization)
            } catch (e: McpRequestException) {
              toolError(e.message ?: "Tool call failed")
            }
          "resources/list" -> listResources(system, host)
          "resources/read" -> readResource(system, host, params)
          else -> return Reply(error(id, METHOD_NOT_FOUND, "Unknown method '$method'"))
        }
      } catch (e: McpRequestException) {
        return Reply(error(id, INVALID_PARAMS, e.message ?: "Invalid parameters"))
      } catch (e: Exception) {
        return Reply(error(id, INTERNAL_ERROR, e.message ?: "Catalog MCP request failed"))
      }
    return Reply(success(id, result))
  }

  private fun initialize(params: JsonObject): JsonObject {
    val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
    val negotiated =
      when (requested) {
        null,
        MCP_PROTOCOL_VERSION -> MCP_PROTOCOL_VERSION
        MCP_PROTOCOL_VERSION_2025_03 -> MCP_PROTOCOL_VERSION_2025_03
        else -> MCP_PROTOCOL_VERSION
      }
    return buildJsonObject {
      put("protocolVersion", negotiated)
      put(
        "capabilities",
        buildJsonObject {
          put("tools", JsonObject(emptyMap()))
          put("resources", buildJsonObject { put("subscribe", false) })
        },
      )
      put(
        "serverInfo",
        buildJsonObject {
          put("name", "compose-preview-catalog")
          put("version", SERVE_VERSION)
        },
      )
      put(
        "instructions",
        "This endpoint exposes one hosted Compose Preview catalog. Reading published previews " +
          "needs preview access; made-to-order renders and data products need live access.",
      )
    }
  }

  private fun tools(): JsonArray = buildJsonArray {
    add(
      tool(
        "status",
        "Report readiness and the catalog selected by this endpoint.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "list_projects",
        "Describe this remote catalog and its preview count.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "list_previews",
        "List the catalogued Compose previews and their published metadata.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "render_preview",
        "Render one preview. Like local compose-ai-tools, the default semantics observation is " +
          "token-frugal; request observe=png for pixels. This made-to-order lane requires live " +
          "grant scope. Use resources/read for the published snapshot lane.",
        """{"type":"object","properties":{"uri":{"type":"string"},"previewId":{"type":"string"},"observe":{"type":"string","enum":["png","semantics","hash"]},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"anyOf":[{"required":["uri"]},{"required":["previewId"]}]}""",
      )
    )
    add(
      tool(
        "list_data_products",
        "List structured data-product kinds advertised by previews in this catalog.",
        """{"type":"object","properties":{"previewId":{"type":"string"}}}""",
      )
    )
    add(
      tool(
        "get_preview_data",
        "Fetch the merged accessibility or annotation product for a preview. This lane " +
          "requires live grant scope.",
        """{"type":"object","properties":{"uri":{"type":"string"},"previewId":{"type":"string"},"kind":{"type":"string"},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"required":["kind"],"anyOf":[{"required":["uri"]},{"required":["previewId"]}]}""",
      )
    )
    add(
      tool(
        "list-all-documentation",
        "Storybook-MCP-compatible alias that lists every preview as a story.",
        EMPTY_SCHEMA,
      )
    )
    add(
      tool(
        "get-documentation-for-story",
        "Storybook-MCP-compatible preview metadata lookup.",
        """{"type":"object","properties":{"storyId":{"type":"string"},"id":{"type":"string"}},"anyOf":[{"required":["storyId"]},{"required":["id"]}]}""",
      )
    )
    add(
      tool(
        "preview-stories",
        "Storybook-MCP-compatible rendering of one or more story ids. Requires live scope.",
        """{"type":"object","properties":{"storyIds":{"type":"array","items":{"type":"string"}},"storyId":{"type":"string"},"ids":{"type":"array","items":{"type":"string"}},"id":{"type":"string"},"observe":{"type":"string","enum":["png","semantics","hash"]},"overrides":{"type":"object","additionalProperties":{"type":["string","number","boolean"]}}},"anyOf":[{"required":["storyIds"]},{"required":["storyId"]},{"required":["ids"]},{"required":["id"]}]}""",
      )
    )
  }

  private suspend fun callTool(
    system: String,
    host: ServeHost,
    params: JsonObject,
    liveAuthorization: () -> ServeMachineAuthorization.Decision,
  ): JsonObject {
    val name = params.requiredString("name")
    val args = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
    return when (name) {
      "status" -> textResult(statusJson(system, host).toString())
      "list_projects" -> textResult(projectsJson(system, host).toString())
      "list_previews" -> textResult(previewsJson(system, host).toString())
      "list_data_products" -> textResult(dataProductsJson(host, args).toString())
      "list-all-documentation" -> textResult(storiesJson(system, host).toString())
      "get-documentation-for-story" -> {
        val requested = args.firstString("storyId", "id")
        val preview = resolvePreview(host, previewIdArgument(system, requested))
        textResult(storyDocumentationJson(system, preview).toString())
      }
      "render_preview" -> {
        requireLive(liveAuthorization)
        val preview = resolvePreview(host, args.previewId(system))
        val overrides = parseOverrides(preview, args["overrides"] as? JsonObject)
        renderResult(
          host,
          preview.id,
          resourceUri(system, preview.id),
          overrides,
          args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "semantics",
        )
      }
      "preview-stories" -> {
        requireLive(liveAuthorization)
        val ids =
          ((args["storyIds"] ?: args["ids"]) as? JsonArray)?.map { it.jsonPrimitive.content }
            ?: listOf(args.firstString("storyId", "id"))
        if (ids.size > MAX_STORIES_PER_CALL) {
          throw McpRequestException(
            "preview-stories accepts at most $MAX_STORIES_PER_CALL ids per call"
          )
        }
        val observe = args["observe"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "png"
        val content = buildJsonArray {
          ids.forEach { storyId ->
            val preview = resolvePreview(host, previewIdArgument(system, storyId))
            val overrides = parseOverrides(preview, args["overrides"] as? JsonObject)
            renderContent(
                host,
                preview.id,
                resourceUri(system, preview.id),
                overrides,
                observe,
              )
              .forEach(::add)
          }
        }
        buildJsonObject { put("content", content) }
      }
      "get_preview_data" -> {
        requireLive(liveAuthorization)
        val preview = resolvePreview(host, args.previewId(system))
        val overrides = parseOverrides(preview, args["overrides"] as? JsonObject)
        dataProductResult(host, preview.id, args.requiredString("kind"), overrides)
      }
      else -> toolError("unknown tool: $name")
    }
  }

  private fun listResources(system: String, host: ServeHost): JsonObject = buildJsonObject {
    put(
      "resources",
      buildJsonArray {
        host.previews.forEach { preview ->
          add(
            buildJsonObject {
              put("uri", resourceUri(system, preview.id))
              put("name", preview.label)
              put("description", "${host.label}: ${preview.id}")
              put("mimeType", "image/png")
            }
          )
        }
      },
    )
  }

  private suspend fun readResource(
    system: String,
    host: ServeHost,
    params: JsonObject,
  ): JsonObject {
    val uri = params.requiredString("uri")
    val preview = resolvePreview(host, previewIdFromUri(system, uri))
    val png = renderPng(host, preview.id, PreviewOverrides(), preferPublished = true)
    return buildJsonObject {
      put(
        "contents",
        buildJsonArray {
          add(
            buildJsonObject {
              put("uri", uri)
              put("mimeType", "image/png")
              put("blob", Base64.getEncoder().encodeToString(png))
            }
          )
        },
      )
    }
  }

  private suspend fun renderResult(
    host: ServeHost,
    previewId: String,
    uri: String,
    overrides: PreviewOverrides,
    observe: String,
  ): JsonObject = buildJsonObject {
    put("content", JsonArray(renderContent(host, previewId, uri, overrides, observe)))
  }

  private suspend fun renderContent(
    host: ServeHost,
    previewId: String,
    uri: String,
    overrides: PreviewOverrides,
    observe: String,
  ): List<JsonObject> {
    if (observe !in OBSERVATION_MODES) {
      throw McpRequestException("'observe' must be one of png, semantics, or hash")
    }
    val png = renderPng(host, previewId, overrides)
    if (observe == "png") return listOf(imageContent(png))

    val observation = buildJsonObject {
      put("observe", observe)
      put("uri", uri)
      put("sha256", sha256Hex(png))
      put("sizeBytes", png.size)
      pngDimensions(png)?.let { (width, height) ->
        put("widthPx", width)
        put("heightPx", height)
      }
      if (observe == "semantics") {
        val semantics = withRenderPermit {
          when (val outcome = host.renderAnnotations(previewId, overrides)) {
            is AnnotationsOutcome.Ok ->
              runCatching { JSON.parseToJsonElement(outcome.json.decodeToString()) }.getOrNull()
            AnnotationsOutcome.NotFound,
            is AnnotationsOutcome.Failed -> null
          }
        }
        if (semantics == null) {
          put("semanticsUnavailable", "compose/semantics is not available for this catalog preview")
        } else {
          put("semantics", semantics)
        }
      }
    }
    return listOf(
      buildJsonObject {
        put("type", "text")
        put("text", observation.toString())
      }
    )
  }

  private suspend fun dataProductResult(
    host: ServeHost,
    previewId: String,
    kind: String,
    overrides: PreviewOverrides,
  ): JsonObject {
    val bytes = withRenderPermit {
      when {
        kind.startsWith("a11y/") ->
          when (val outcome = host.renderA11y(previewId, overrides)) {
            is A11yOutcome.Ok -> outcome.json
            A11yOutcome.NotFound -> throw McpRequestException("data product '$kind' unavailable")
            is A11yOutcome.Failed -> throw McpRequestException(outcome.reason)
          }
        kind in ANNOTATION_KINDS ->
          when (val outcome = host.renderAnnotations(previewId, overrides)) {
            is AnnotationsOutcome.Ok -> outcome.json
            AnnotationsOutcome.NotFound ->
              throw McpRequestException("data product '$kind' unavailable")
            is AnnotationsOutcome.Failed -> throw McpRequestException(outcome.reason)
          }
        else -> throw McpRequestException("unsupported data product '$kind'")
      }
    }
    return textResult(bytes.decodeToString())
  }

  private suspend fun renderPng(
    host: ServeHost,
    previewId: String,
    overrides: PreviewOverrides,
    preferPublished: Boolean = false,
  ): ByteArray {
    if (preferPublished) {
      return host.bakedRender(previewId, overrides)?.png
        ?: throw McpRequestException(
          "published preview '$previewId' is unavailable; use render_preview with live scope"
        )
    }
    return withRenderPermit {
      when (val outcome = host.render(previewId, overrides)) {
        is RenderOutcome.Ok -> outcome.png
        RenderOutcome.NotFound -> throw McpRequestException("no such preview '$previewId'")
        RenderOutcome.Busy -> throw McpRequestException("render busy; retry shortly")
        is RenderOutcome.Failed -> throw McpRequestException(outcome.reason)
      }
    }
  }

  private suspend fun <T> withRenderPermit(block: () -> T): T =
    withContext(Dispatchers.IO) {
      if (!renderSemaphore.tryAcquire(renderQueueWaitSeconds, TimeUnit.SECONDS)) {
        throw McpRequestException("render queue saturated; retry shortly")
      }
      try {
        block()
      } finally {
        renderSemaphore.release()
      }
    }

  private fun parseOverrides(preview: ServePreview, raw: JsonObject?): PreviewOverrides {
    if (raw == null || raw.isEmpty()) return PreviewOverrides()
    val params = raw.mapValues { (_, value) -> value.asOverrideString() }
    val knobKinds = ServeOverrides.declaredKnobKinds(preview)
    return when (val parsed = ServeOverrides.parse(params, knobKinds)) {
      is OverrideParse.Ok -> parsed.overrides
      is OverrideParse.Invalid -> throw McpRequestException(parsed.message)
    }
  }

  private fun JsonElement.asOverrideString(): String =
    when (this) {
      is JsonPrimitive ->
        when {
          isString -> content
          booleanOrNull != null -> booleanOrNull.toString()
          longOrNull != null -> longOrNull.toString()
          doubleOrNull != null -> doubleOrNull.toString()
          else -> content
        }
      else -> throw McpRequestException("override values must be strings, numbers, or booleans")
    }

  private fun dataProductsJson(host: ServeHost, args: JsonObject): JsonArray {
    val selected = args["previewId"]?.jsonPrimitive?.contentOrNull
    return buildJsonArray {
      host.previews
        .filter { selected == null || it.id == selected }
        .forEach { preview ->
          val kinds = buildSet {
            addAll(preview.dataProductKinds)
            if (host.hasA11yOverlayFor(preview.id)) add("a11y/hierarchy")
            if (
              host.hasDesignAnnotationsFor(preview.id) || host.hasPublishedTypographyFor(preview.id)
            ) {
              add("compose/annotations")
            }
          }
          add(
            buildJsonObject {
              put("previewId", preview.id)
              put("kinds", JsonArray(kinds.sorted().map(::JsonPrimitive)))
            }
          )
        }
    }
  }

  private fun statusJson(system: String, host: ServeHost): JsonObject = buildJsonObject {
    put("schema", "compose-preview-mcp-status/v1")
    put("ready", true)
    put("remote", true)
    put("toolCatalog", buildJsonObject { put("status", "ready") })
    put("projects", projectsJson(system, host)["projects"]!!)
  }

  private fun projectsJson(system: String, host: ServeHost): JsonObject = buildJsonObject {
    put("projects", buildJsonArray { add(projectJson(system, host)) })
  }

  private fun projectJson(system: String, host: ServeHost): JsonObject = buildJsonObject {
    put("workspaceId", system)
    put("rootProjectName", host.label)
    put("catalog", system)
    put("label", host.label)
    put("previewCount", host.previews.size)
    put("remote", true)
  }

  private fun previewsJson(system: String, host: ServeHost): JsonArray =
    JsonArray(host.previews.map { previewJson(system, it) })

  private fun storiesJson(system: String, host: ServeHost): JsonObject = buildJsonObject {
    put("schema", "compose-preview-mcp-storybook/v1")
    put("count", host.previews.size)
    put("stories", JsonArray(host.previews.map { storyJson(system, it) }))
  }

  private fun storyJson(system: String, preview: ServePreview): JsonObject = buildJsonObject {
    put("id", preview.id)
    put("storyId", preview.id)
    put("title", hostTitle(preview))
    put("name", preview.label)
    put("type", "story")
    put("importPath", "virtual:compose-preview/${preview.id}")
    put("uri", resourceUri(system, preview.id))
  }

  private fun storyDocumentationJson(system: String, preview: ServePreview): JsonObject =
    JsonObject(
      storyJson(system, preview) +
        previewJson(system, preview) +
        mapOf(
          "schema" to JsonPrimitive("compose-preview-mcp-storybook/v1"),
          "workspaceId" to JsonPrimitive(system),
          "note" to
            JsonPrimitive(
              "Render with preview-stories. Native render_preview and get_preview_data also " +
                "accept this story's URI."
            ),
        )
    )

  private fun hostTitle(preview: ServePreview): String =
    preview.id.substringBeforeLast('.', missingDelimiterValue = preview.label)

  private fun previewJson(system: String, preview: ServePreview): JsonObject = buildJsonObject {
    put("id", preview.id)
    put("label", preview.label)
    put("uri", resourceUri(system, preview.id))
    put("modes", JsonArray(preview.modes.map { JsonPrimitive(it.wire) }))
    put("dataProductKinds", JsonArray(preview.dataProductKinds.sorted().map(::JsonPrimitive)))
    preview.state?.let { put("state", it) }
    preview.theme?.let { put("theme", it) }
  }

  private fun resolvePreview(host: ServeHost, id: String): ServePreview =
    host.previews.firstOrNull { it.id == id } ?: throw McpRequestException("no such preview '$id'")

  private fun JsonObject.previewId(system: String): String =
    this["previewId"]?.jsonPrimitive?.contentOrNull
      ?: this["uri"]?.jsonPrimitive?.contentOrNull?.let { previewIdFromUri(system, it) }
      ?: throw McpRequestException("'previewId' or 'uri' is required")

  private fun previewIdArgument(system: String, value: String): String =
    if (value.startsWith("compose-preview://catalog/")) previewIdFromUri(system, value) else value

  private fun resourceUri(system: String, previewId: String): String =
    "compose-preview://catalog/${WebEscaping.urlEncodeSegment(system)}/" +
      WebEscaping.urlEncodeSegment(previewId)

  private fun previewIdFromUri(system: String, uri: String): String {
    val prefix = "compose-preview://catalog/"
    if (!uri.startsWith(prefix)) throw McpRequestException("invalid compose-preview resource URI")
    val parts = uri.removePrefix(prefix).split('/', limit = 2)
    if (parts.size != 2 || decode(parts[0]) != system) {
      throw McpRequestException("resource URI does not belong to catalog '$system'")
    }
    return decode(parts[1])
  }

  private fun decode(value: String): String =
    URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

  private fun requireLive(check: () -> ServeMachineAuthorization.Decision) {
    when (val decision = check()) {
      is ServeMachineAuthorization.Decision.Authorized -> Unit
      ServeMachineAuthorization.Decision.Missing ->
        throw McpRequestException("live grant scope is required")
      is ServeMachineAuthorization.Decision.Forbidden -> throw McpRequestException(decision.message)
    }
  }

  private fun tool(name: String, description: String, schema: String): JsonObject =
    buildJsonObject {
      put("name", name)
      put("description", description)
      put("inputSchema", JSON.parseToJsonElement(schema))
    }

  private fun textResult(text: String): JsonObject = buildJsonObject {
    put(
      "content",
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", text)
          }
        )
      },
    )
  }

  private fun toolError(message: String): JsonObject = buildJsonObject {
    put(
      "content",
      buildJsonArray {
        add(
          buildJsonObject {
            put("type", "text")
            put("text", message)
          }
        )
      },
    )
    put("isError", true)
  }

  private fun imageContent(png: ByteArray): JsonObject = buildJsonObject {
    put("type", "image")
    put("data", Base64.getEncoder().encodeToString(png))
    put("mimeType", "image/png")
  }

  private fun success(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id)
    put("result", result)
  }

  private fun error(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    put(
      "error",
      buildJsonObject {
        put("code", code)
        put("message", message)
      },
    )
  }

  private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
      ?: throw McpRequestException("'$name' is required")

  private fun JsonObject.firstString(vararg names: String): String =
    names.firstNotNullOfOrNull { name ->
      this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
    } ?: throw McpRequestException("'${names.joinToString("' or '")}' is required")

  private fun pngDimensions(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < 24) return null
    fun int32(offset: Int): Int =
      ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
    val width = int32(16)
    val height = int32(20)
    return if (width in 1..100_000 && height in 1..100_000) width to height else null
  }

  private fun sha256Hex(bytes: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
      "%02x".format(it)
    }

  private class McpRequestException(message: String) : RuntimeException(message)

  companion object {
    const val MCP_PROTOCOL_VERSION = "2025-06-18"
    const val MCP_PROTOCOL_VERSION_2025_03 = "2025-03-26"
    val SUPPORTED_PROTOCOL_VERSIONS = setOf(MCP_PROTOCOL_VERSION, MCP_PROTOCOL_VERSION_2025_03)

    private const val EMPTY_SCHEMA = """{"type":"object","properties":{}}"""
    private const val INVALID_REQUEST = -32600
    private const val METHOD_NOT_FOUND = -32601
    private const val INVALID_PARAMS = -32602
    private const val INTERNAL_ERROR = -32603
    private const val MAX_STORIES_PER_CALL = 16
    private val OBSERVATION_MODES = setOf("png", "semantics", "hash")
    private val ANNOTATION_KINDS =
      setOf("compose/annotations", "compose/semantics", "compose/typography", "compose/tags")
    private val JSON = Json { ignoreUnknownKeys = false }
  }
}
