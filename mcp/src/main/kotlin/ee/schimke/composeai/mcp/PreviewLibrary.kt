package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The preview library as a sidebar app (issue #1241): `previews_library` is a global entrypoint
 * (`_meta["openai/ui"].entrypoints = [{type: "global"}]`) that ChatGPT / Codex open full screen
 * without asking the model, and that any MCP Apps host can also show when the model calls it.
 *
 * The app is its own small asset, `mcp-app/preview-library.html` at [RESOURCE_URI], rather than a
 * mode of the main viewer: it lists registered projects, then modules, then previews, with search,
 * and renders the selected one through `render_preview`. It follows deep links: the host's
 * `hostContext["openai/deepLink"].url` (see [LibraryRoute]) at initialization and on
 * `ui/notifications/host-context-changed`.
 *
 * The tool result is the whole tree ([structured]), so the first render needs no second call; the
 * app calls the tool again with `projectId` to refresh one project and start its daemons.
 *
 * The same asset serves `settings_register_project` (the settings page's "Register project…" modal)
 * and the hosted server's `catalog_library` / `ui_builder_open`, told apart by the `mode` and the
 * tool names in the result, so there is one library UI rather than three.
 */
class PreviewLibrary(
  /**
   * Registered projects with their modules and discovered previews. With a `projectId`, that
   * project's daemons are started first (in the background) so its previews get discovered.
   */
  private val snapshot: suspend (projectId: String?) -> List<Project>
) {
  data class Project(val id: String, val name: String, val path: String, val modules: List<Module>)

  data class Module(val path: String, val previews: List<Preview>)

  data class Preview(
    val uri: String,
    val name: String,
    val displayName: String?,
    val sourceFile: String?,
    val sourceLine: Int?,
  )

  fun toolDefs(): List<ToolDef> =
    listOf(
      OpenAiUi.entrypointTool(
        ToolDef(
          name = TOOL,
          description =
            "Open the Compose preview library: registered projects, their modules and every " +
              "discovered @Preview, with search; selecting one renders it. Takes no arguments " +
              "(optional projectId refreshes one project and starts its daemons). Also opens " +
              "from the ChatGPT/Codex sidebar.",
          inputSchema = INPUT_SCHEMA,
          meta = appToolMeta(),
        ),
        listOf(OpenAiEntrypoint.Global),
        title = "Compose Previews",
      )
    )

  fun resources(): List<ResourceDescriptor> =
    listOf(
      ResourceDescriptor(
        uri = RESOURCE_URI,
        name = "Compose Preview library",
        description = "Browse registered projects, modules and previews, and render one.",
        mimeType = DaemonMcpServer.MCP_APP_MIME_TYPE,
        meta = resourceMeta(),
      )
    )

  fun readResource(uri: String): ReadResourceResult? {
    if (uri != RESOURCE_URI) return null
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

  /** Serves [TOOL] and the settings page's register modal; null for any other tool. */
  suspend fun handle(name: String, args: JsonObject): CallToolResult? =
    when (name) {
      TOOL -> {
        val projectId =
          (args["projectId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val projects = snapshot(projectId)
        val previews = projects.sumOf { p -> p.modules.sumOf { it.previews.size } }
        CallToolResult(
          content =
            listOf(
              ContentBlock.Text(
                if (projects.isEmpty())
                  "No projects are registered. Call register_project with a Gradle project path."
                else
                  "${projects.size} project(s), $previews discovered preview(s); the library " +
                    "app lists them. Render one with render_preview(uri)."
              )
            ),
          structuredContent = structured(MODE_LIBRARY, projects),
        )
      }
      PreviewSettingsMcp.REGISTER_PROJECT_TOOL ->
        CallToolResult(
          content = listOf(ContentBlock.Text("Enter a Gradle project path to register it.")),
          structuredContent = structured(MODE_REGISTER, snapshot(null)),
        )
      else -> null
    }

  private fun structured(mode: String, projects: List<Project>): JsonObject = buildJsonObject {
    put("schema", SCHEMA)
    put("mode", mode)
    put("host", "local")
    putJsonObject("tools") {
      // Refresh keeps the mode: the register modal refreshes through its own tool.
      put("refresh", if (mode == MODE_REGISTER) PreviewSettingsMcp.REGISTER_PROJECT_TOOL else TOOL)
      put("render", "render_preview")
      put("register", "register_project")
    }
    // Explicit, so a setting such as "render_preview result = file" cannot take the pixels away
    // from the one caller that needs them.
    putJsonObject("renderArgs") {
      put("inline", true)
      put("observe", "png")
    }
    putJsonArray("projects") {
      projects.forEach { project ->
        addJsonObject {
          put("id", project.id)
          put("name", project.name)
          put("path", project.path)
          putJsonArray("modules") {
            project.modules.forEach { module ->
              addJsonObject {
                put("path", module.path)
                putJsonArray("previews") {
                  module.previews.forEach { preview ->
                    addJsonObject {
                      put("uri", preview.uri)
                      put("name", preview.name)
                      preview.displayName?.let { put("displayName", it) }
                      preview.sourceFile?.let { put("sourceFile", File(it).name) }
                      preview.sourceLine?.let { put("sourceLine", it) }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  private fun html(): String =
    checkNotNull(javaClass.classLoader.getResourceAsStream(ASSET)) {
        "missing bundled MCP App preview library: $ASSET"
      }
      .bufferedReader()
      .use { it.readText() }

  companion object {
    const val TOOL: String = "previews_library"
    const val RESOURCE_URI: String = "ui://compose-preview/library"
    const val ASSET: String = "preview-library.html"

    /** `structuredContent.schema` of a library result, shared with the hosted server. */
    const val SCHEMA: String = "compose-preview-library/v1"
    const val MODE_LIBRARY: String = "library"
    const val MODE_REGISTER: String = "register"

    /** `{}` plus the optional `projectId` the app sends to refresh one project. */
    val INPUT_SCHEMA: JsonObject = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("projectId") {
          put("type", "string")
          put(
            "description",
            "Optional workspace id from list_projects: refresh that project and start its daemons.",
          )
        }
      }
    }

    /**
     * `_meta` for a tool that opens the library app: the portable MCP Apps keys, visible to both
     * the model and the app (the app calls [TOOL] again to refresh).
     */
    fun appToolMeta(): JsonObject = buildJsonObject {
      putJsonObject("ui") {
        put("resourceUri", RESOURCE_URI)
        putJsonArray("visibility") {
          add("model")
          add("app")
        }
      }
      put("ui/resourceUri", RESOURCE_URI)
    }

    /** Full screen first: every entrypoint opens that way, and a library does not fit inline. */
    fun resourceMeta(): JsonObject =
      OpenAiUi.withDisplayModes(
        buildJsonObject { putJsonObject("ui") { put("prefersBorder", false) } },
        preferred = OpenAiUi.DisplayMode.FULLSCREEN,
      )
  }
}
