package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * Storybook-compatibility shim for `compose-preview serve`, emitting the two contracts the
 * Storybook tool ecosystem (Chromatic, Percy, storycap, the test-runner, Storybook MCP servers) is
 * built on:
 *
 * 1. **`/index.json`**: the stories index (`{ "v": 5, "entries": { <storyId>: … } }`) a tool crawls
 *    to enumerate renderable units. See [Index] / [Entry].
 * 2. **`iframe.html?id=<storyId>`**: one story in isolation, no chrome, embedding the rendered PNG
 *    as a `data:` URI ([iframePage]) so no token is threaded onto a sub-resource.
 *
 * The story id is minted like CSF's `toId(title, name)` so ids look native. [resolvePreviewId] maps
 * it back and also accepts a raw native id, for deep links like `iframe.html?id=<fqn>`.
 *
 * Pure and IO-free, like [ServeUrls]; the HTTP glue lives in [ServeHttpServer].
 */
object StorybookCompat {

  /**
   * Storybook `index.json` schema version (`"v": 5` since SB8). Advisory; consumers key off
   * `entries`.
   */
  const val INDEX_VERSION: Int = 5

  /**
   * Synthetic `importPath` prefix carrying the native preview id; there is no CSF source, and
   * visual tools only navigate `iframe.html?id=`.
   */
  private const val IMPORT_PATH_PREFIX = "virtual:compose-preview/"

  /** Tag stamped on every entry so a consumer can tell these stories are Compose previews. */
  private val TAGS = listOf("compose-preview")

  /** The Storybook stories index served at `/index.json`. */
  @Serializable data class Index(val v: Int = INDEX_VERSION, val entries: Map<String, Entry>)

  /**
   * One [Index] entry, mirroring a Storybook `'story'` entry: [id] (also the map key), [title]
   * sidebar path, [name], and a synthetic [importPath]. [type] is always `"story"`.
   */
  @Serializable
  data class Entry(
    val id: String,
    val title: String,
    val name: String,
    val importPath: String,
    val type: String = "story",
    val tags: List<String> = TAGS,
  )

  /**
   * A resolved story: its minted Storybook [storyId] and the native compose-preview [previewId].
   */
  data class Story(val storyId: String, val previewId: String, val title: String, val name: String)

  /** CSF `sanitize`: lowercase, collapse runs of non-`[a-z0-9]` to `-`, trim `-`. */
  fun sanitize(raw: String): String = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

  /**
   * CSF `toId(kind, name)` → `sanitize(kind)--sanitize(name)`, dropping a side that sanitizes to
   * blank.
   */
  fun toId(title: String, name: String): String =
    listOf(sanitize(title), sanitize(name)).filter { it.isNotBlank() }.joinToString("--")

  /**
   * Minted stories for [previews], in order, with deterministic collision suffixes (`-2`, `-3`, …)
   * so the mapping stays 1:1. Both [index] and [resolvePreviewId] use this, so they can't disagree.
   */
  fun stories(previews: List<ServePreview>): List<Story> {
    val used = HashSet<String>()
    return previews.map { preview ->
      val title = deriveTitle(preview.id)
      val name = deriveName(preview)
      val base = toId(title, name).ifBlank { sanitize(preview.id).ifBlank { "preview" } }
      var candidate = base
      var n = 2
      while (!used.add(candidate)) candidate = "$base-${n++}"
      Story(storyId = candidate, previewId = preview.id, title = title, name = name)
    }
  }

  /** Build the `/index.json` payload from a session's preview list. */
  fun index(previews: List<ServePreview>): Index =
    Index(
      entries =
        stories(previews).associate { s ->
          s.storyId to
            Entry(
              id = s.storyId,
              title = s.title,
              name = s.name,
              importPath = IMPORT_PATH_PREFIX + s.previewId,
            )
        }
    )

  /**
   * Resolve a story id to a native preview id. Minted ids match first so an advertised entry always
   * round-trips; only then does a raw native id apply, so it can never shadow an indexed story.
   * Null when nothing matches.
   */
  fun resolvePreviewId(storyId: String, previews: List<ServePreview>): String? {
    stories(previews)
      .firstOrNull { it.storyId == storyId }
      ?.let {
        return it.previewId
      }
    return previews.firstOrNull { it.id == storyId }?.id
  }

  /**
   * The isolation page for `iframe.html?id=<storyId>`: the PNG at intrinsic size on white, inlined
   * as a `data:` URI. [storyId] is HTML-escaped into title/alt.
   */
  fun iframePage(storyId: String, pngBytes: ByteArray): String {
    val (w, h) = WebEscaping.pngDimensions(pngBytes)
    val b64 = Base64.getEncoder().encodeToString(pngBytes)
    val esc = WebEscaping.htmlEscape(storyId)
    val sizeAttrs = if (w > 0 && h > 0) " width=\"$w\" height=\"$h\"" else ""
    return buildString {
      append("<!doctype html>\n")
      append("<html><head><meta charset=\"utf-8\">\n")
      append("<title>").append(esc).append(" · compose-preview</title>\n")
      append("<style>html,body{margin:0;padding:0;background:#fff}img{display:block}</style>\n")
      append("</head><body>\n")
      append("<img alt=\"").append(esc).append("\"").append(sizeAttrs)
      append(" src=\"data:image/png;base64,").append(b64).append("\">\n")
      append("</body></html>\n")
    }
  }

  /**
   * The SVG isolation page for `iframe.html?id=<storyId>&format=svg`: the figma-svg export as a
   * vector render that DOM-serializing tools (Percy, Chromatic) re-render.
   *
   * **Deliberately an `<img>` with a `data:image/svg+xml` URI, not inline `<svg>` (security):** the
   * bytes may come from an unverified, unsanitised catalog (`ServeCatalogStore.fetchFigmaSvgs`),
   * and inline markup in a same-origin document could run scripts. SVG via `<img>` runs in
   * restricted mode, with no script or external fetches. [storyId] is HTML-escaped.
   */
  fun iframeSvgPage(storyId: String, svgBytes: ByteArray): String {
    val b64 = Base64.getEncoder().encodeToString(svgBytes)
    val esc = WebEscaping.htmlEscape(storyId)
    return buildString {
      append("<!doctype html>\n")
      append("<html><head><meta charset=\"utf-8\">\n")
      append("<title>").append(esc).append(" · compose-preview</title>\n")
      append("<style>html,body{margin:0;padding:0;background:#fff}img{display:block}</style>\n")
      append("</head><body>\n")
      append("<img alt=\"").append(esc).append("\" src=\"data:image/svg+xml;base64,")
      append(b64).append("\">\n")
      append("</body></html>\n")
    }
  }

  /**
   * Sidebar grouping ([Entry.title]) for a native preview id: the enclosing class/file simple name
   * for an FQN (`com.example.PreviewsKt.RedBoxPreview…` → `Previews`, trailing `Kt` dropped), the
   * component slug for a catalog id (`button__dark` → `button`), else the id itself.
   */
  private fun deriveTitle(id: String): String {
    val beforeAxis = id.substringBefore("__")
    if (beforeAxis.contains('.')) {
      val container = beforeAxis.substringBeforeLast('.')
      val simple = container.substringAfterLast('.')
      return simple.removeSuffix("Kt").ifBlank { simple }.ifBlank { beforeAxis }
    }
    return beforeAxis
  }

  /**
   * Story [Entry.name] for a preview: its human label when it has one (the viewer already shows
   * it), else the trailing segment of the native id.
   */
  private fun deriveName(preview: ServePreview): String {
    val label = preview.label.trim()
    if (label.isNotBlank()) return label
    val beforeAxis = preview.id.substringBefore("__")
    return if (beforeAxis.contains('.')) beforeAxis.substringAfterLast('.') else beforeAxis
  }
}
