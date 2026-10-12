package ee.schimke.composeai.mcp

import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `render_preview`'s opt-in `details` (issue #1170): the a11y findings plus overlay and the layout
 * bounds, fetched in the same call. The model sees one summary line per detail; the full detail
 * only travels inside the Antigravity card, where the viewer toggles between them without calls.
 */
internal enum class RenderDetail(val wire: String) {
  A11Y("a11y"),
  LAYOUT("layout");

  companion object {
    fun parse(value: String): RenderDetail? = entries.firstOrNull { it.wire == value }
  }
}

/** What [DaemonMcpServer] fetched for the requested details. */
internal class RenderDetails(
  /** One model-facing line per requested detail, including the unavailable ones. */
  val summaries: List<String>,
  /** The card's `details` block: `a11y` / `layout` objects plus `unavailable` reasons. */
  val card: JsonObject,
  /** The `a11y/overlay` PNG, when it was produced. */
  val overlayPng: ByteArray?,
) {
  val isEmpty: Boolean
    get() = summaries.isEmpty()

  companion object {
    val NONE = RenderDetails(emptyList(), JsonObject(emptyMap()), null)

    /** `_meta` key marking a card content block as a detail rather than the render itself. */
    const val META_KEY = "composePreview/detail"
  }
}

/** Pure payload readers, kept apart from the daemon plumbing so they are easy to test. */
internal object RenderDetailReaders {
  /** Bound on the layout boxes a card carries; the summary still counts every node. */
  const val MAX_LAYOUT_BOXES = 400

  /** Bound on the findings a card carries. */
  const val MAX_FINDINGS = 200

  class A11y(val summary: String, val findings: JsonArray)

  /**
   * Reads an `a11y/atf` payload (`{findings:[{level,type,message,viewDescription?,
   * boundsInScreen?}]}`) into the summary line, e.g. `a11y: 2 errors, 1 warning (TouchTargetSize
   * ×2, Contrast ×1)`.
   */
  fun a11y(payload: JsonElement?): A11y {
    val findings =
      ((payload as? JsonObject)?.get("findings") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.filter { it.string("level")?.uppercase() != "NOT_RUN" }
        .orEmpty()
    val byLevel = findings.groupingBy { it.string("level")?.uppercase() ?: "INFO" }.eachCount()
    val counts =
      listOf("ERROR" to "error", "WARNING" to "warning", "INFO" to "info").mapNotNull {
        (level, noun) ->
        val n = byLevel[level] ?: return@mapNotNull null
        if (noun == "info") "$n info" else "$n $noun${if (n == 1) "" else "s"}"
      }
    val summary =
      if (findings.isEmpty()) "a11y: no findings"
      else {
        val rules =
          findings
            .groupingBy { ruleName(it.string("type") ?: "Unknown") }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString(", ") { "${it.key} ×${it.value}" }
        "a11y: ${counts.joinToString(", ")} ($rules)"
      }
    val cardFindings =
      JsonArray(
        findings
          .sortedBy { levelRank(it.string("level")) }
          .take(MAX_FINDINGS)
          .map { finding ->
            buildJsonObject {
              put("level", finding.string("level")?.uppercase() ?: "INFO")
              put("rule", ruleName(finding.string("type") ?: "Unknown"))
              put("message", finding.string("message").orEmpty())
              finding.string("viewDescription")?.let { put("node", it) }
              parseBounds(finding.string("boundsInScreen"))?.let { bounds ->
                putJsonArray("bounds") { bounds.forEach { add(it) } }
              }
            }
          }
      )
    return A11y(summary, cardFindings)
  }

  class Layout(val summary: String, val boxes: JsonArray, val nodeCount: Int)

  /**
   * Reads a `layout/inspector` payload (`{root:{displayName?,component?,bounds:{left,top,right,
   * bottom},size?,tokens?,transform?,source?,children}}`) or a `compose/semantics` payload
   * (`{root:{boundsInRoot:"l,t,r,b",testTag?,text?,label?,role?,tokens?,children}}`) into boxes the
   * viewer draws over the render, each with the `props` its legend row lists.
   */
  fun layout(kind: String, payload: JsonElement?): Layout {
    val root = (payload as? JsonObject)?.get("root") as? JsonObject
    val boxes = mutableListOf<JsonObject>()
    var count = 0
    fun visit(node: JsonObject, depth: Int) {
      count++
      val bounds =
        (node["bounds"] as? JsonObject)?.let { b ->
          listOf("left", "top", "right", "bottom").map {
            b[it]?.jsonPrimitive?.intOrNull ?: return@let null
          }
        } ?: parseBounds(node.string("boundsInRoot"))
      if (
        bounds != null &&
          boxes.size < MAX_LAYOUT_BOXES &&
          bounds[2] > bounds[0] &&
          bounds[3] > bounds[1]
      ) {
        boxes += buildJsonObject {
          put("label", nodeLabel(node))
          put("depth", depth)
          putJsonArray("bounds") { bounds.forEach { add(it) } }
          val props = layoutProps(node, bounds)
          if (props.isNotEmpty()) putJsonArray("props") { props.forEach { add(it) } }
          (node.string("source") ?: node.string("sourceRef"))?.let { put("source", it.take(120)) }
        }
      }
      (node["children"] as? JsonArray)?.forEach { child ->
        (child as? JsonObject)?.let { visit(it, depth + 1) }
      }
    }
    root?.let { visit(it, 0) }
    val summary = "layout: $count node${if (count == 1) "" else "s"} ($kind)"
    return Layout(summary, JsonArray(boxes), count)
  }

  /**
   * The legend's layout properties for one node, in the serve viewer's Layout-boxes order:
   * `120×48px`, `pad 8.0dp 16.0dp` (top/end/bottom/start, collapsed when equal), `gap 8.0dp`, `min
   * 48.0dp×48.0dp`, `scale 0.82×0.82` when a graphics-layer transform scales the node (Wear's
   * `SurfaceTransformation` near the bezel). Read from `layout/inspector`'s `size` and `tokens`; a
   * `compose/semantics` node carries the same `tokens`, so it gets the same lines minus `scale`.
   */
  private fun layoutProps(node: JsonObject, bounds: List<Int>): List<String> {
    val tokens = node["tokens"] as? JsonObject
    val size = node["size"] as? JsonObject
    val width = size?.get("width")?.jsonPrimitive?.intOrNull ?: (bounds[2] - bounds[0])
    val height = size?.get("height")?.jsonPrimitive?.intOrNull ?: (bounds[3] - bounds[1])
    val transform = node["transform"] as? JsonObject
    return listOfNotNull(
      "${width}×${height}px",
      (tokens?.get("padding") as? JsonObject)?.let { insets(it) }?.let { "pad $it" },
      tokens?.string("gap")?.let { "gap $it" },
      minSize(tokens),
      transform?.let { scale(it) },
    )
  }

  /** `scale 0.83×0.83` when the transform reports `scaled`; null otherwise. */
  private fun scale(transform: JsonObject): String? {
    if ((transform["scaled"] as? JsonPrimitive)?.contentOrNull != "true") return null
    val x = (transform["scaleX"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: return null
    val y = (transform["scaleY"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() ?: return null
    return "scale ${"%.2f".format(Locale.ROOT, x)}×${"%.2f".format(Locale.ROOT, y)}"
  }

  /** CSS order (top end bottom start), collapsed like CSS shorthand; null when no edge resolved. */
  private fun insets(value: JsonObject): String? {
    val edges = listOf("top", "end", "bottom", "start").map { value.string(it) ?: "0" }
    if (listOf("top", "end", "bottom", "start").none { value.string(it) != null }) return null
    return when {
      edges.distinct().size == 1 -> edges[0]
      edges[0] == edges[2] && edges[1] == edges[3] -> "${edges[0]} ${edges[1]}"
      else -> edges.joinToString(" ")
    }
  }

  private fun minSize(tokens: JsonObject?): String? {
    val width = tokens?.string("minWidth")
    val height = tokens?.string("minHeight")
    if (width == null && height == null) return null
    return "min ${width ?: "–"}×${height ?: "–"}"
  }

  /** `TouchTargetSizeCheck` → `TouchTargetSize`; ATF's class names all end in `Check`. */
  private fun ruleName(type: String): String =
    type.substringAfterLast('.').removeSuffix("Check").ifEmpty { type }

  private fun levelRank(level: String?): Int =
    when (level?.uppercase()) {
      "ERROR" -> 0
      "WARNING" -> 1
      else -> 2
    }

  private fun nodeLabel(node: JsonObject): String =
    listOf("testTag", "displayName", "component", "text", "label", "role")
      .firstNotNullOfOrNull { key -> node.string(key)?.takeIf { it.isNotBlank() } }
      ?.take(80) ?: "node"

  /** `left,top,right,bottom` → four ints, or null. */
  fun parseBounds(wire: String?): List<Int>? {
    val parts = wire?.split(',') ?: return null
    if (parts.size != 4) return null
    return parts.map { it.trim().toIntOrNull() ?: return null }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
