package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `.uid` UI Builder designs as an MCP App (compose-ui-builder#364, server side): the `design_open`
 * file-entrypoint tool and the `ui://compose-ui-builder/editor` resource.
 *
 * The editor is compose-ui-builder's (#366). It does its own file IO through the host — it reads
 * the opened file with `resources/read` on the entrypoint's opaque `host-resource://` URI, saves
 * with `openai/resources/write` and `ifMatch`, and subscribes for external edits — so this server
 * only has to:
 * 1. declare the tool, with `FileInput` and a `[".uid"]` file entrypoint, and acknowledge the call;
 * 2. serve the archive's MCP App shell with its one placeholder, the asset base, filled in, and the
 *    asset origin in its CSP; and
 * 3. serve the editor's files from that origin ([UiBuilderAssetOrigin]).
 *
 * **Model calls with `{path}` are deliberately not offered.** The editor reads its file only from
 * `ui/notifications/tool-input`'s `file.resourceUri` and needs the host's `openai/resource`
 * capability to read and write it; a host without file entrypoints (Claude Code, Antigravity) has
 * neither, so a server-backed `{path}` resource would open an editor that cannot load or save.
 *
 * Registered only when the editor archive's manifest carries a supported `mcpApp` contract
 * ([fromArchive]); with an older archive, or none, the tool and the resource are simply absent.
 */
class UiBuilderDesignMcp
internal constructor(
  private val archive: UiBuilderWebArchive,
  private val shell: String,
  val editorVersion: String,
  private val assets: UiBuilderAssetOrigin = UiBuilderAssetOrigin(archive, editorVersion),
) : AutoCloseable {

  fun toolDefs(): List<ToolDef> = listOf(toolDef)

  fun resourceDescriptors(): List<ResourceDescriptor> =
    listOf(
      ResourceDescriptor(
        uri = EDITOR_URI,
        name = "Compose UI Builder",
        description = "Edits a .uid UI Builder design, saving back to the file.",
        mimeType = MCP_APP_MIME_TYPE,
        // The CSP needs the asset origin's port, which exists only once the listener has started on
        // the first read; it is on the read result, where MCP Apps hosts take it from.
        meta = OpenAiUi.withDisplayModes(null, DISPLAY_MODES, OpenAiUi.DisplayMode.FULLSCREEN),
      )
    )

  /** The shell for [EDITOR_URI], starting the asset origin; null for any other URI. */
  fun readResource(uri: String): ReadResourceResult? {
    if (uri != EDITOR_URI) return null
    val base = assets.base().toString()
    val origin = assets.origin()
    return ReadResourceResult(
      contents =
        listOf(
          ResourceContents.Text(
            uri = uri,
            mimeType = MCP_APP_MIME_TYPE,
            text = shell.replace(ASSET_BASE_PLACEHOLDER, base),
            meta = resourceMeta(origin),
          )
        )
    )
  }

  /** `design_open`'s result, or null when [name] is not this tool. */
  fun handle(name: String, args: JsonObject): CallToolResult? {
    if (name != TOOL_NAME) return null
    val file =
      args["file"] as? JsonObject
        ?: return error(
          INVALID_ARGUMENTS,
          "design_open: pass `file` (FileInput {name, resourceUri}). The host sends it when a " +
            "$EXTENSION file is opened; the editor reads and saves the file through the host.",
        )
    val name = (file["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val resourceUri = (file["resourceUri"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (name.isEmpty() || resourceUri.isEmpty()) {
      return error(INVALID_ARGUMENTS, "design_open: `file` needs a non-blank name and resourceUri.")
    }
    val baseName = name.substringAfterLast('/').substringAfterLast('\\')
    if (!baseName.lowercase().endsWith(EXTENSION)) {
      return error(UNSUPPORTED_EXTENSION, "design_open: $baseName is not a $EXTENSION design.")
    }
    return CallToolResult(
      content = listOf(ContentBlock.Text("Opened $baseName in the Compose UI Builder.")),
      structuredContent =
        buildJsonObject {
          put("schema", RESULT_SCHEMA)
          put("name", baseName)
          put("resourceUri", resourceUri)
          put("editorVersion", editorVersion)
        },
    )
  }

  override fun close() {
    runCatching { assets.close() }
    runCatching { archive.close() }
  }

  private val toolDef: ToolDef by lazy {
    OpenAiUi.entrypointTool(
      ToolDef(
        name = TOOL_NAME,
        description =
          "Open a Compose UI Builder design (.uid) in the visual editor. Hosts with a file " +
            "entrypoint call it with `file` (FileInput) when the person opens a .uid file; the " +
            "editor then reads and saves that file through the host. Selecting a layer in the " +
            "editor shares it with you as context: edit that node, by id, in the .uid file.",
        inputSchema = OpenAiUi.FILE_INPUT_SCHEMA,
        meta = OpenAiUi.appToolMeta(EDITOR_URI, entrypoints = emptyList()),
      ),
      entrypoints = listOf(OpenAiEntrypoint.File(listOf(EXTENSION))),
      title = "Compose UI Builder",
      icon = OpenAiUi.svgIcon(DESIGN_ICON_SVG),
    )
  }

  private fun resourceMeta(origin: String): JsonObject =
    OpenAiUi.withDisplayModes(
      buildJsonObject {
        putJsonObject("ui") {
          putJsonObject("csp") {
            putJsonArray("resourceDomains") { add(JsonPrimitive(origin)) }
            putJsonArray("connectDomains") { add(JsonPrimitive(origin)) }
          }
        }
      },
      DISPLAY_MODES,
      OpenAiUi.DisplayMode.FULLSCREEN,
    )

  private fun error(code: String, message: String): CallToolResult =
    errorCallToolResult(
      message,
      buildJsonObject {
        put("schema", RESULT_SCHEMA)
        putJsonObject("error") {
          put("code", code)
          put("message", message)
        }
      },
    )

  companion object {
    const val TOOL_NAME: String = "design_open"
    const val EDITOR_URI: String = "ui://compose-ui-builder/editor"
    const val MCP_APP_MIME_TYPE: String = "text/html;profile=mcp-app"
    const val EXTENSION: String = ".uid"
    const val RESULT_SCHEMA: String = "compose-preview/design-open/v1"

    /** The shell's path in the archive, fixed by `mcpApp` 1. */
    const val SHELL_PATH: String = "mcp-app/ui-builder-mcp-app.html"

    /** The one placeholder `mcpApp` 1 says a server fills in: the absolute asset base URL. */
    const val ASSET_BASE_PLACEHOLDER: String = "__COMPOSE_UI_BUILDER_ASSET_BASE__"

    /**
     * The `mcpApp` contracts this server fills in. compose-ui-builder bumps the number only for a
     * shell change a server that fills in version 1 would get wrong, so a higher one is refused
     * rather than assumed compatible.
     */
    val SUPPORTED_MCP_APP: Set<Int> = setOf(1)

    const val INVALID_ARGUMENTS: String = "invalid_arguments"
    const val UNSUPPORTED_EXTENSION: String = "unsupported_extension"

    private val DISPLAY_MODES = listOf(OpenAiUi.DisplayMode.FULLSCREEN)

    /** A layout grid with a pointer; the spec's 20x20 monochrome stroke icon. */
    private const val DESIGN_ICON_SVG: String =
      """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.33" stroke-linecap="round" stroke-linejoin="round"><rect x="2.5" y="2.5" width="15" height="15" rx="2"/><path d="M2.5 7h15"/><path d="M7 7v10.5"/><path d="M10.5 10.5l5 2-2 .75-.75 2z"/></svg>"""

    /**
     * The `design_open` surface for the editor archive this process can find
     * ([UiBuilderWebArchive.locate]), or null when there is none or it predates the MCP App shell.
     */
    fun fromEnvironment(
      environment: Map<String, String> = System.getenv(),
      onLog: (String) -> Unit = { System.err.println(it) },
    ): UiBuilderDesignMcp? {
      val file = UiBuilderWebArchive.locate(environment) ?: return null
      val archive = UiBuilderWebArchive.open(file) ?: return null
      return fromArchive(archive, onLog).also { if (it == null) archive.close() }
    }

    /** [archive]'s `design_open` surface, or null when its manifest has no supported `mcpApp`. */
    internal fun fromArchive(
      archive: UiBuilderWebArchive,
      onLog: (String) -> Unit = { System.err.println(it) },
    ): UiBuilderDesignMcp? {
      val manifest = archive.manifest ?: return null
      // Absent or 0: an archive that predates the shell. Not worth a log line; it is the normal
      // state for every release before compose-ui-builder 3.70.0.
      val contract = manifest.mcpApp?.takeIf { it > 0 } ?: return null
      if (contract !in SUPPORTED_MCP_APP) {
        onLog(
          "compose-preview-mcp: UI Builder ${manifest.version} declares mcpApp $contract; this " +
            "server fills in ${SUPPORTED_MCP_APP.sorted().joinToString()}. design_open is off."
        )
        return null
      }
      val shell = archive.readText(SHELL_PATH)?.takeIf { ASSET_BASE_PLACEHOLDER in it }
      if (shell == null) {
        onLog(
          "compose-preview-mcp: UI Builder ${manifest.version} declares mcpApp $contract but " +
            "$SHELL_PATH is missing or has no asset-base placeholder. design_open is off."
        )
        return null
      }
      return UiBuilderDesignMcp(
        archive,
        shell,
        manifest.version,
        UiBuilderAssetOrigin(archive, manifest.version, onLog = onLog),
      )
    }
  }
}
