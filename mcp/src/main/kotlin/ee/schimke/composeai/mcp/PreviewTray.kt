package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * `previews_tray` (#1238): the thread **Previews** tab. It lists the previews declared in the
 * source files this session changed, most recently changed first, after the previews the person
 * pinned, and renders them in `ui://compose-preview/viewer`'s tray mode, where pointing at a node
 * makes it context for the next prompt.
 *
 * The listing uses the same match as `find_previews_for_file` — a catalog entry's canonical source
 * path equals the changed file's — and it never renders: a thumbnail comes from the last render's
 * PNG when there is one. The viewer renders the preview the person opens, and re-renders follow the
 * existing resource subscriptions.
 *
 * The thread entrypoint is OpenAI-specific metadata; every other MCP Apps host sees an ordinary
 * app-linked tool the model or the viewer can call.
 */
object PreviewTray {
  const val TOOL: String = "previews_tray"

  /** Tab label: the spec asks for a thread entrypoint title that differs from the plugin name. */
  const val TITLE: String = "Previews"

  /** Most previews one tray lists. */
  const val MAX_ITEMS: Int = 50

  /** Most thumbnails one result embeds, and their long edge in pixels. */
  const val MAX_THUMBNAILS: Int = 24
  const val THUMBNAIL_EDGE_PX: Int = 256

  /** `_meta` key of the `{uri: base64 PNG}` thumbnails; kept out of the model's view. */
  const val THUMBNAILS_META: String = "composePreview/trayPngs"

  fun toolDef(): ToolDef {
    val schema = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("pin") {
          put("type", "string")
          put("description", "Optional compose-preview:// URI to pin to the tray.")
        }
        putJsonObject("unpin") {
          put("type", "string")
          put("description", "Optional compose-preview:// URI to remove from the tray's pins.")
        }
      }
    }
    val tool =
      ToolDef(
        name = TOOL,
        description =
          "Open the Previews tray: the previews for the Kotlin files changed in this session " +
            "(most recent first) plus pinned previews, re-rendered as they change. The person " +
            "can point at a node in a render to attach it to their next prompt. Accepts {}; " +
            "`pin`/`unpin` take a compose-preview:// URI.",
        inputSchema = schema,
        meta =
          OpenAiUi.appToolMeta(
            resourceUri = DaemonMcpServer.MCP_APP_VIEWER_URI,
            entrypoints = emptyList(),
            visibility = listOf("model", "app"),
          ),
      )
    return OpenAiUi.entrypointTool(tool, listOf(OpenAiEntrypoint.Thread), title = TITLE)
  }

  /** One row of the tray. [preview] is null for a pinned URI the catalog does not know (yet). */
  data class Item(
    val uri: String,
    val preview: CatalogPreview?,
    val pinned: Boolean,
    val changed: PreviewActivity.Mark?,
  )

  /**
   * Pinned previews (most recent pin first), then the previews of each changed source file (most
   * recently changed file first, and within a file in declaration order). Each URI appears once.
   */
  fun list(catalog: List<CatalogPreview>, activity: PreviewActivity): List<Item> {
    val byUri = catalog.associateBy { it.uri }
    val bySource = catalog.filter { it.sourceFile != null }.groupBy { it.sourceFile!! }
    val changedByUri = mutableMapOf<String, PreviewActivity.Mark>()
    val changedOrder = mutableListOf<CatalogPreview>()
    for ((path, mark) in activity.changedSources()) {
      val previews =
        bySource[path]
          .orEmpty()
          .sortedWith(compareBy({ it.sourceLine ?: Int.MAX_VALUE }, { it.uri }))
      for (preview in previews) {
        if (changedByUri.putIfAbsent(preview.uri, mark) == null) changedOrder += preview
      }
    }
    val out = LinkedHashMap<String, Item>()
    for ((uri, _) in activity.pins()) {
      out[uri] = Item(uri, byUri[uri], pinned = true, changed = changedByUri[uri])
    }
    for (preview in changedOrder) {
      out.putIfAbsent(
        preview.uri,
        Item(preview.uri, preview, pinned = false, changed = changedByUri[preview.uri]),
      )
    }
    return out.values.take(MAX_ITEMS)
  }

  /** Handles a `previews_tray` call: applies `pin` / `unpin`, then lists. */
  fun call(
    args: JsonObject,
    catalog: List<CatalogPreview>,
    activity: PreviewActivity,
    thumbnails: RenderThumbnails,
  ): CallToolResult {
    val pin = args.string("pin")
    val unpin = args.string("unpin")
    if (pin != null && !activity.pin(pin)) {
      return errorCallToolResult("$TOOL: 'pin' must be a compose-preview:// URI, got '$pin'")
    }
    if (unpin != null) activity.unpin(unpin)
    val items = list(catalog, activity)
    val changedFiles = activity.changedSources()
    val structured = buildJsonObject {
      put("mode", "tray")
      putJsonArray("previews") { items.forEach { add(itemJson(it, activity)) } }
      putJsonArray("changedFiles") {
        changedFiles.forEach { (path, mark) ->
          add(
            buildJsonObject {
              put("path", path)
              put("changedAt", Instant.ofEpochMilli(mark.atMs).toString())
              put("previews", catalog.count { it.sourceFile == path })
            }
          )
        }
      }
    }
    val pngs = buildJsonObject {
      items
        .asSequence()
        .mapNotNull { item ->
          thumbnails.base64(activity.lastRenderPng(item.uri), THUMBNAIL_EDGE_PX)?.let {
            item.uri to it
          }
        }
        .take(MAX_THUMBNAILS)
        .forEach { (uri, png) -> put(uri, png) }
    }
    val summary =
      if (items.isEmpty()) {
        "Previews tray: no previews yet. Edit a Kotlin file that declares a @Preview (or call " +
          "notify_file_changed), or pin one with pin=<compose-preview:// URI>."
      } else {
        "Previews tray: ${items.size} preview(s) for ${changedFiles.size} changed file(s), " +
          "${items.count { it.pinned }} pinned. $structured"
      }
    return CallToolResult(
      content = listOf(ContentBlock.Text(summary)),
      structuredContent = structured,
      meta = if (pngs.isEmpty()) null else buildJsonObject { put(THUMBNAILS_META, pngs) },
    )
  }

  private fun itemJson(item: Item, activity: PreviewActivity): JsonObject = buildJsonObject {
    val preview = item.preview
    put("uri", item.uri)
    put("name", preview?.simpleName ?: previewNameOf(item.uri))
    preview?.displayName?.takeIf { it != preview.fqn }?.let { put("displayName", it) }
    preview?.let {
      put("fqn", it.fqn)
      put("module", it.modulePath)
    }
    preview?.sourceFile?.let { put("sourceFile", it) }
    preview?.sourceLine?.let { put("sourceLine", it) }
    put("pinned", item.pinned)
    item.changed?.let { put("changedAt", Instant.ofEpochMilli(it.atMs).toString()) }
    activity.renderSeq(item.uri)?.let { put("rendered", it) }
    if (preview == null) put("inCatalog", false)
  }

  private fun previewNameOf(uri: String): String =
    PreviewUri.parseOrNull(uri)?.previewFqn?.substringAfterLast('.') ?: uri
}

/**
 * `preview_mentions` (#1239): the composer `@`-mention search. The person types `@ProfileScr…`,
 * picks a preview, and the message carries its exact `compose-preview://` URI, so the agent renders
 * it directly instead of searching source files for it (#1163).
 *
 * Matches case-insensitive subsequences over the function name, display name, FQN and file name;
 * orders exact, then prefix, then subsequence matches, and within each most recently rendered or
 * watched first. An empty query lists the previews rendered or watched in this session. A preview
 * that rendered here gets a thumbnail icon from that render; nothing is rendered to make one. Until
 * a catalog has loaded the answer is an empty list, not an error.
 */
object PreviewMentions {
  const val TOOL: String = "preview_mentions"

  const val MAX_ITEMS: Int = 20

  /** Thumbnail long edge; the spec recommends at least 128 px. */
  const val ICON_EDGE_PX: Int = 128

  fun toolDef(): ToolDef =
    ToolDef(
      name = TOOL,
      description =
        "Composer @-mention search over this workspace's @Preview functions. Returns " +
          "compose-preview:// resource links; render one with render_preview uri=<uri>.",
      inputSchema = OpenAiUi.MENTION_SEARCH_INPUT_SCHEMA,
      title = "Compose previews",
      meta = OpenAiUi.mentionSearchToolMeta(),
      icons = listOf(OpenAiUi.svgIcon()),
    )

  /** Match quality; lower is better. */
  enum class Tier {
    EXACT,
    PREFIX,
    SUBSEQUENCE,
  }

  /** How [preview] matches [query] (already lower-cased and trimmed), or null when it does not. */
  fun tierOf(preview: CatalogPreview, query: String): Tier? {
    val fields =
      listOfNotNull(
          preview.simpleName,
          preview.displayName,
          preview.fqn,
          preview.fileName,
          preview.fileName?.substringBeforeLast('.'),
        )
        .map { it.lowercase() }
    return when {
      fields.any { it == query } -> Tier.EXACT
      fields.any { it.startsWith(query) } -> Tier.PREFIX
      fields.any { isSubsequence(query, it) } -> Tier.SUBSEQUENCE
      else -> null
    }
  }

  /** The previews for [query], best first, at most [MAX_ITEMS]. */
  fun search(
    query: String,
    catalog: List<CatalogPreview>,
    activity: PreviewActivity,
  ): List<CatalogPreview> {
    val q = query.trim().lowercase()
    val distinct = catalog.distinctBy { it.uri }
    val byName = compareBy<CatalogPreview>({ it.simpleName.lowercase() }, { it.uri })
    if (q.isEmpty()) {
      val recent =
        distinct
          .mapNotNull { preview -> activity.recency(preview.uri)?.let { preview to it } }
          .sortedByDescending { it.second }
          .map { it.first }
      return recent.ifEmpty { distinct.sortedWith(byName) }.take(MAX_ITEMS)
    }
    return distinct
      .mapNotNull { preview ->
        tierOf(preview, q)?.let { Triple(preview, it, activity.recency(preview.uri)) }
      }
      .sortedWith(
        compareBy<Triple<CatalogPreview, Tier, Long?>> { it.second }
          .thenByDescending { it.third ?: -1L }
          .thenBy { it.first.simpleName.lowercase() }
          .thenBy { it.first.uri }
      )
      .map { it.first }
      .take(MAX_ITEMS)
  }

  /** One `ResourceLink` item, with a thumbnail `icons` entry when [thumbnail] is given. */
  fun item(preview: CatalogPreview, thumbnail: String?): JsonObject = buildJsonObject {
    put("type", "resource_link")
    put("uri", preview.uri)
    put("name", preview.simpleName)
    preview.displayName
      ?.takeIf { it != preview.fqn && it != preview.simpleName }
      ?.let { put("title", it) }
    put(
      "description",
      listOfNotNull(preview.modulePath, preview.fileName).joinToString(" · "),
    )
    put("mimeType", "image/png")
    if (thumbnail != null) {
      putJsonArray("icons") {
        add(
          buildJsonObject {
            put("src", "data:image/png;base64,$thumbnail")
            put("mimeType", "image/png")
            putJsonArray("sizes") { add("${ICON_EDGE_PX}x$ICON_EDGE_PX") }
          }
        )
      }
    }
  }

  /** Handles a `preview_mentions` call. */
  fun call(
    args: JsonObject,
    catalog: List<CatalogPreview>,
    activity: PreviewActivity,
    thumbnails: RenderThumbnails,
  ): CallToolResult {
    val query = args.string("query") ?: ""
    val items =
      search(query, catalog, activity).map { preview ->
        item(preview, thumbnails.base64(activity.lastRenderPng(preview.uri), ICON_EDGE_PX))
      }
    return CallToolResult(
      content = emptyList(),
      structuredContent = buildJsonObject { putJsonArray("items") { items.forEach { add(it) } } },
    )
  }

  /** Whether [needle]'s characters appear in [haystack] in order. */
  fun isSubsequence(needle: String, haystack: String): Boolean {
    var at = 0
    for (c in haystack) {
      if (at < needle.length && needle[at] == c) at++
    }
    return at == needle.length
  }
}

private fun JsonObject.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
