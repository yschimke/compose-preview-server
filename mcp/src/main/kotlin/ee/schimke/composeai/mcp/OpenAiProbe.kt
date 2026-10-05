package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Opt-in probe for the OpenAI MCP Extensions (issue #1236): tools that exist only to learn how
 * ChatGPT / Codex desktop treats a **local stdio** MCP server installed from a marketplace plugin —
 * which entrypoints it lists, whether it injects `openai/resource.path`, whether file resources are
 * writable and subscriptions fire, what mention search sends, and what `clientInfo.name` it uses.
 *
 * Registered only when `COMPOSE_PREVIEW_MCP_OPENAI_PROBE=1` ([fromEnvironment]), so no normal tool
 * list carries it. The UI is its own small MCP App (`mcp-app/openai-probe.html`, served at
 * [RESOURCE_URI]) rather than a panel in the main viewer. How a person runs it:
 * `docs/openai-extensions-probe.md`.
 */
class OpenAiProbe(private val log: (String) -> Unit = { System.err.println(it) }) {

  fun toolDefs(): List<ToolDef> =
    listOf(
      OpenAiUi.entrypointTool(
        ToolDef(
          name = PROBE_GLOBAL,
          description =
            "OpenAI extensions probe: opens the probe panel from the sidebar (global entrypoint). " +
              "Diagnostic only; takes no arguments.",
          inputSchema = OpenAiUi.EMPTY_INPUT_SCHEMA,
          meta = appMeta(),
        ),
        listOf(OpenAiEntrypoint.Global),
        title = "Compose Preview Probe",
      ),
      OpenAiUi.entrypointTool(
        ToolDef(
          name = PROBE_THREAD,
          description =
            "OpenAI extensions probe: opens the probe panel as a thread tab (thread entrypoint). " +
              "Diagnostic only; takes no arguments.",
          inputSchema = OpenAiUi.EMPTY_INPUT_SCHEMA,
          meta = appMeta(),
        ),
        listOf(OpenAiEntrypoint.Thread),
        title = "Probe Tab",
      ),
      OpenAiUi.entrypointTool(
        ToolDef(
          name = PROBE_FILE,
          description =
            "OpenAI extensions probe: file viewer for ${FILE_EXTENSIONS.joinToString("/")} files " +
              "(file entrypoint). Reports what the host exposes about the opened file.",
          inputSchema = OpenAiUi.FILE_INPUT_SCHEMA,
          meta = appMeta(),
        ),
        listOf(OpenAiEntrypoint.File(FILE_EXTENSIONS)),
        title = "Compose Probe Viewer",
      ),
      ToolDef(
        name = PROBE_FILE_ECHO,
        description =
          "OpenAI extensions probe, called by the probe panel: echoes the host-injected " +
            "_meta[\"openai/resource\"].path and, with touch=true, rewrites that file's bytes " +
            "unchanged so a resource subscription can be observed.",
        inputSchema =
          parseProbeSchema(
            """{"type":"object","properties":{"touch":{"type":"boolean","description":"Rewrite the opened file with its own bytes and bump its mtime."}}}"""
          ),
        meta =
          buildJsonObject { putJsonObject("ui") { putJsonArray("visibility") { add("app") } } },
        title = "Probe file echo",
      ),
      ToolDef(
        name = PROBE_MENTIONS,
        description =
          "OpenAI extensions probe: composer @-mention search. Returns three fixed resource " +
            "links and logs the query it received to stderr.",
        inputSchema = OpenAiUi.MENTION_SEARCH_INPUT_SCHEMA,
        meta = OpenAiUi.mentionSearchToolMeta(),
        title = "Probe mentions",
      ),
    )

  /** The probe panel, listed so a host can prefetch it; display modes ride on it. */
  fun resources(): List<ResourceDescriptor> =
    listOf(
      ResourceDescriptor(
        uri = RESOURCE_URI,
        name = "OpenAI extensions probe",
        description = "Diagnostic MCP App for the OpenAI MCP Extensions probe (#1236).",
        mimeType = DaemonMcpServer.MCP_APP_MIME_TYPE,
        meta = resourceMeta(),
      )
    )

  /** The probe panel or a mention target; null for any other URI. */
  fun readResource(uri: String): ReadResourceResult? {
    if (uri == RESOURCE_URI) {
      return ReadResourceResult(
        listOf(
          ResourceContents.Text(
            uri = uri,
            mimeType = DaemonMcpServer.MCP_APP_MIME_TYPE,
            text = html(),
            meta = resourceMeta(),
          )
        )
      )
    }
    val item = MENTION_ITEMS.firstOrNull { it.uri == uri } ?: return null
    log("compose-preview-mcp: openai probe resources/read of mention ${item.uri}")
    return ReadResourceResult(
      listOf(
        ResourceContents.Text(
          uri = uri,
          mimeType = "text/plain",
          text = "Probe mention '${item.name}': ${item.description}",
        )
      )
    )
  }

  /** Serves a probe tool call; null when [name] is not a probe tool. */
  suspend fun handle(name: String, args: JsonObject, clientName: String?): CallToolResult? =
    when (name) {
      PROBE_GLOBAL,
      PROBE_THREAD -> opened(name, args, clientName)
      PROBE_FILE -> opened(name, args, clientName)
      PROBE_FILE_ECHO -> fileEcho(args, clientName)
      PROBE_MENTIONS -> mentions(args, clientName)
      else -> null
    }

  private suspend fun opened(name: String, args: JsonObject, clientName: String?): CallToolResult {
    val requestMeta = OpenAiUi.currentRequestMeta()
    log(
      "compose-preview-mcp: openai probe $name client=${clientName ?: "<unknown>"} " +
        "args=$args meta=${requestMeta ?: "{}"}"
    )
    val structured = buildJsonObject {
      put("probe", name)
      put("clientName", clientName?.let(::JsonPrimitive) ?: JsonNull)
      put("arguments", args)
      put("requestMeta", requestMeta ?: JsonObject(emptyMap()))
      put("resourcePath", OpenAiUi.resourcePath(requestMeta)?.let(::JsonPrimitive) ?: JsonNull)
    }
    return CallToolResult(
      content =
        listOf(
          ContentBlock.Text(
            "OpenAI extensions probe '$name' opened for client ${clientName ?: "<unknown>"}. " +
              "The probe panel shows what the host reported."
          )
        ),
      structuredContent = structured,
    )
  }

  private suspend fun fileEcho(args: JsonObject, clientName: String?): CallToolResult {
    val requestMeta = OpenAiUi.currentRequestMeta()
    val path = OpenAiUi.resourcePath(requestMeta)
    val touch = (args["touch"] as? JsonPrimitive)?.booleanOrNull == true
    val file = path?.let(::File)
    var touched: String? = null
    if (touch) {
      touched =
        when {
          file == null -> "no openai/resource.path was injected; nothing to touch"
          FILE_EXTENSIONS.none { file.name.endsWith(it) } ->
            "refused: ${file.name} is not a ${FILE_EXTENSIONS.joinToString("/")} file"
          !file.isFile -> "refused: not a regular file"
          else -> runCatching {
              file.writeBytes(file.readBytes())
              file.setLastModified(System.currentTimeMillis())
              "rewrote ${file.length()} bytes unchanged"
            }
              .getOrElse { "failed: ${it.message}" }
        }
    }
    log(
      "compose-preview-mcp: openai probe $PROBE_FILE_ECHO client=${clientName ?: "<unknown>"} " +
        "path=${path ?: "<none>"} touch=$touch${touched?.let { " ($it)" } ?: ""}"
    )
    val structured = buildJsonObject {
      put("probe", PROBE_FILE_ECHO)
      put("clientName", clientName?.let(::JsonPrimitive) ?: JsonNull)
      put("requestMeta", requestMeta ?: JsonObject(emptyMap()))
      put("resourcePath", path?.let(::JsonPrimitive) ?: JsonNull)
      if (file != null) {
        put("exists", file.isFile)
        if (file.isFile) put("sizeBytes", file.length())
      }
      if (touched != null) put("touch", touched)
    }
    return CallToolResult(
      content =
        listOf(
          ContentBlock.Text(
            if (path == null) "No _meta[\"openai/resource\"].path on this call."
            else "Host-injected openai/resource.path: $path"
          )
        ),
      structuredContent = structured,
    )
  }

  private fun mentions(args: JsonObject, clientName: String?): CallToolResult {
    val query = (args["query"] as? JsonPrimitive)?.contentOrNull
    log(
      "compose-preview-mcp: openai probe mentions/search client=${clientName ?: "<unknown>"} " +
        "query=${query?.let { JsonPrimitive(it).toString() } ?: "<missing>"}"
    )
    return CallToolResult(
      content = MENTION_ITEMS,
      structuredContent =
        buildJsonObject {
          putJsonArray("items") {
            MENTION_ITEMS.forEach { item ->
              add(
                buildJsonObject {
                  put("type", "resource_link")
                  put("uri", item.uri)
                  put("name", item.name)
                  item.mimeType?.let { put("mimeType", it) }
                  item.description?.let { put("description", it) }
                }
              )
            }
          }
        },
    )
  }

  private fun appMeta(): JsonObject = buildJsonObject {
    putJsonObject("ui") {
      put("resourceUri", RESOURCE_URI)
      putJsonArray("visibility") {
        add("model")
        add("app")
      }
    }
    put("ui/resourceUri", RESOURCE_URI)
  }

  private fun resourceMeta(): JsonObject =
    OpenAiUi.withDisplayModes(
      buildJsonObject { putJsonObject("ui") { put("prefersBorder", true) } },
      preferred = OpenAiUi.DisplayMode.FULLSCREEN,
    )

  private fun html(): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(ASSET)) {
        "missing bundled OpenAI probe app: $ASSET"
      }
      .bufferedReader()
      .use { it.readText() }

  companion object {
    /** `1` registers the probe tools and resource; anything else leaves them out. */
    const val ENV: String = "COMPOSE_PREVIEW_MCP_OPENAI_PROBE"

    const val RESOURCE_URI: String = "ui://compose-preview/openai-probe"
    const val ASSET: String = "openai-probe.html"

    const val PROBE_GLOBAL: String = "probe_global"
    const val PROBE_THREAD: String = "probe_thread"
    const val PROBE_FILE: String = "probe_file"
    const val PROBE_FILE_ECHO: String = "probe_file_echo"
    const val PROBE_MENTIONS: String = "probe_mentions"

    /** Only our own formats (#1235): never `.kt`, which would replace the host's Kotlin viewer. */
    val FILE_EXTENSIONS: List<String> = listOf("rc", "uid")

    val TOOL_NAMES: Set<String> =
      setOf(PROBE_GLOBAL, PROBE_THREAD, PROBE_FILE, PROBE_FILE_ECHO, PROBE_MENTIONS)

    internal val MENTION_ITEMS: List<ContentBlock.ResourceLink> =
      (1..3).map { n ->
        ContentBlock.ResourceLink(
          uri = "compose-preview-probe://mention/$n",
          name = "probe-mention-$n",
          mimeType = "text/plain",
          description = "Fixed OpenAI probe mention #$n",
        )
      }

    fun fromEnvironment(env: Map<String, String>): OpenAiProbe? =
      if (env[ENV] == "1") OpenAiProbe() else null

    private fun parseProbeSchema(text: String): JsonElement =
      kotlinx.serialization.json.Json.parseToJsonElement(text)
  }
}
