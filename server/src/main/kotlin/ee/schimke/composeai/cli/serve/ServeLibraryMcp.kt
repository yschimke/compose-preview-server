package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The hosted server's sidebar apps (issue #1241): `catalog_library`, the catalog browser, and
 * `ui_builder_open`, the UI Builder's design list, both OpenAI global entrypoints
 * (`_meta["openai/ui"].entrypoints = [{type: "global"}]`) that ChatGPT / Codex open full screen
 * from the sidebar without asking the model.
 *
 * Both open the same MCP App as the local server's `previews_library`:
 * `mcp-app/preview-library.html` at [RESOURCE_URI], told apart by the `mode` and the tool names in
 * the opening result. Deep links arrive as `hostContext["openai/deepLink"].url`:
 * `/project/<catalog>`, `/preview/<uri>` and, for `ui_builder_open`, `/design/<id>`.
 *
 * The `_meta` shapes are the ones `:mcp`'s `OpenAiUi` emits. `:server` does not link `:mcp` (it
 * would bring the MCP SDK and the daemon supervisor with it), and this surface builds its JSON by
 * hand anyway, so the few keys are written here; `ServeLibraryMcpTest` pins them.
 */
internal object ServeLibraryMcp {
  /** Canonical name; the wire name carries the `catalog_` prefix (#1105, #1225). */
  const val LIBRARY: String = "library"
  const val UI_BUILDER_OPEN: String = "ui_builder_open"

  const val RESOURCE_URI: String = "ui://compose-preview/library"
  private const val ASSET: String = "preview-library.html"
  private const val MIME_TYPE: String = "text/html;profile=mcp-app"

  /** Result `structuredContent.schema`, shared with the local `previews_library`. */
  const val SCHEMA: String = "compose-preview-library/v1"

  /** A catalog lists at most this many previews in one result; search narrows the rest. */
  const val MAX_PREVIEWS: Int = 2_000

  /** One catalog as the library shows it; null [previews] means not loaded yet. */
  data class Catalog(val id: String, val label: String, val previews: List<Preview>?)

  data class Preview(val uri: String, val id: String, val label: String)

  /** `catalog_library`'s declaration; [tool] is `ServeCatalogMcp`'s own builder. */
  fun libraryTool(tool: (String, String, String) -> JsonObject): JsonObject =
    entrypoint(
      tool(
        LIBRARY,
        "Open the hosted preview catalog browser: every catalog, its previews with search, and " +
          "the published render of the one selected. Takes no arguments (optional projectId " +
          "loads one catalog's previews). Also opens from the ChatGPT/Codex sidebar.",
        """{"type":"object","properties":{"projectId":{"type":"string","description":"A catalog id from catalog_list_projects whose previews to list."}}}""",
      ),
      title = "Preview Catalogs",
      svg = LIBRARY_ICON_SVG,
    )

  /** `ui_builder_open`'s declaration, only on a box that serves the UI Builder. */
  fun uiBuilderOpenTool(tool: (String, String, String) -> JsonObject): JsonObject =
    entrypoint(
      tool(
        UI_BUILDER_OPEN,
        "Open the UI Builder's design list: pick a design to see it as the editor draws it. " +
          "Takes no arguments. Also opens from the ChatGPT/Codex sidebar, and a deep link to " +
          "/design/<id> opens one design.",
        """{"type":"object","properties":{}}""",
      ),
      title = "UI Builder",
      svg = UI_BUILDER_ICON_SVG,
    )

  /** The opening result of `catalog_library`: the catalogs, with previews where loaded. */
  fun libraryResult(catalogs: List<Catalog>): JsonObject {
    val loaded = catalogs.sumOf { it.previews?.size ?: 0 }
    return result(
      "${catalogs.size} catalog(s), $loaded preview(s) listed; the library app shows them.",
      buildJsonObject {
        put("schema", SCHEMA)
        put("mode", "library")
        put("host", "hosted")
        putJsonObject("tools") {
          put("refresh", "catalog_$LIBRARY")
          put("render", "catalog_render_preview")
        }
        // The published PNG is readable with preview scope; a made-to-order render needs live.
        put("renderVia", "resource")
        putJsonArray("projects") {
          catalogs.forEach { catalog ->
            addJsonObject {
              put("id", catalog.id)
              put("name", catalog.label)
              val previews = catalog.previews
              if (previews == null) {
                putJsonArray("modules") {}
              } else {
                put("previewCount", previews.size)
                if (previews.size > MAX_PREVIEWS) put("truncated", true)
                putJsonArray("modules") {
                  addJsonObject {
                    put("path", "")
                    putJsonArray("previews") {
                      previews.take(MAX_PREVIEWS).forEach { preview ->
                        addJsonObject {
                          put("uri", preview.uri)
                          put("name", preview.label)
                          put("sourceFile", preview.id)
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      },
    )
  }

  /** The opening result of `ui_builder_open`: the app lists designs itself, under its grant. */
  fun uiBuilderOpenResult(): JsonObject =
    result(
      "The UI Builder app lists your designs; pick one to see it.",
      buildJsonObject {
        put("schema", SCHEMA)
        put("mode", "designs")
        put("host", "hosted")
        putJsonObject("tools") {
          put("listDesigns", ServeUiBuilderMcp.LIST_DESIGNS)
          put("view", ServeUiBuilderMcp.VIEW)
        }
        putJsonArray("projects") {}
      },
    )

  fun resourceDescriptor(): JsonObject = buildJsonObject {
    put("uri", RESOURCE_URI)
    put("name", "Compose Preview library")
    put("description", "Browse catalogs and previews, or UI Builder designs.")
    put("mimeType", MIME_TYPE)
    put("_meta", resourceMeta())
  }

  fun readResource(): JsonObject = buildJsonObject {
    putJsonArray("contents") {
      addJsonObject {
        put("uri", RESOURCE_URI)
        put("mimeType", MIME_TYPE)
        put("text", html())
        put("_meta", resourceMeta())
      }
    }
  }

  /**
   * [tool] with the MCP Apps keys, `openai/ui` global entrypoint, a [title] and a monochrome SVG
   * icon — the spec's entrypoint requirements.
   */
  private fun entrypoint(tool: JsonObject, title: String, svg: String): JsonObject =
    JsonObject(
      tool +
        mapOf(
          "title" to JsonPrimitive(title),
          "icons" to
            buildJsonArray {
              addJsonObject {
                put(
                  "src",
                  "data:image/svg+xml;base64," +
                    Base64.getEncoder().encodeToString(svg.toByteArray(Charsets.UTF_8)),
                )
                put("mimeType", "image/svg+xml")
                putJsonArray("sizes") { add("any") }
              }
            },
          "_meta" to
            buildJsonObject {
              putJsonObject("ui") {
                put("resourceUri", RESOURCE_URI)
                putJsonArray("visibility") {
                  add("model")
                  add("app")
                }
              }
              put("ui/resourceUri", RESOURCE_URI)
              putJsonObject("openai/ui") {
                putJsonArray("entrypoints") { addJsonObject { put("type", "global") } }
              }
            },
        )
    )

  private fun resourceMeta(): JsonObject = buildJsonObject {
    putJsonObject("ui") { put("prefersBorder", false) }
    putJsonObject("openai/ui") {
      put(
        "availableDisplayModes",
        JsonArray(listOf(JsonPrimitive("inline"), JsonPrimitive("fullscreen"))),
      )
      put("preferredDisplayMode", "fullscreen")
    }
  }

  private fun result(text: String, structured: JsonObject): JsonObject = buildJsonObject {
    putJsonArray("content") {
      addJsonObject {
        put("type", "text")
        put("text", text)
      }
    }
    put("structuredContent", structured)
  }

  private fun html(): String =
    checkNotNull(ServeLibraryMcp::class.java.classLoader.getResourceAsStream(ASSET)) {
        "missing bundled MCP App preview library: $ASSET"
      }
      .bufferedReader()
      .use { it.readText() }

  /** 20x20, `currentColor`, 1.33px strokes (the spec's icon guidelines): a grid of previews. */
  const val LIBRARY_ICON_SVG: String =
    """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.33" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="6" height="6" rx="1.5"/><rect x="11" y="3" width="6" height="6" rx="1.5"/><rect x="3" y="11" width="6" height="6" rx="1.5"/><rect x="11" y="11" width="6" height="6" rx="1.5"/></svg>"""

  /** The same guidelines: a canvas with a selected block. */
  const val UI_BUILDER_ICON_SVG: String =
    """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.33" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="14" height="14" rx="2"/><path d="M3 7h14"/><rect x="6" y="9.5" width="8" height="4.5" rx="1" stroke-dasharray="1.5 1.5"/></svg>"""
}
