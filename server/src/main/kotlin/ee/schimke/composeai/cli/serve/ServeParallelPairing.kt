package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which of a sibling catalog's renders of one component is the counterpart of this render, for the
 * `compareWith` + `parallel` pairing. Taking the sibling's first preview for the component left
 * variant cells (`disabled`, `icon`, `@OverrideVariant` cells) without counterparts.
 *
 * The key is the cell, not the preview id, since ids diverge across catalogs (`__compact` vs
 * `__192dp`, `iconbutton-filled` vs `button-icon-filled`). Ranked:
 * 1. the **design-kit node** each render is specified by (`figma:<fileKey>/<nodeId>`, republished
 *    per preview in `references/index.json`): proof the two renders show one kit cell;
 * 2. the render's **variant coordinates** (`state`, `props`, `size` on [ServePreview]), available
 *    for every catalog but occasionally coincidental.
 *
 * Falling back to the canonical sticker is the floor, so this is strictly additive; the fallback is
 * stated ([Pairing.basis], [cellLabel]) rather than presented as a match, since a cell missing on
 * one side matters in a parity comparison.
 */
internal object ServeParallelPairing {

  /** How a counterpart was arrived at — the difference between a comparison and a near-miss. */
  enum class Basis {
    /** Both renders are specified by the same design-kit node. The strongest pairing there is. */
    KIT_CELL,
    /** Both renders carry the same `state` / `props` / `size` coordinates. */
    VARIANT_CELL,
    /** No counterpart for this cell; the component's first published render, as before. */
    CANONICAL,
  }

  /** The chosen counterpart and what justifies it. */
  data class Pairing(val preview: ServePreview, val basis: Basis)

  /**
   * Choose [candidates]'s counterpart for [preview], or null when the sibling publishes none.
   * [kitNodesOf] is applied to the caller's reference lookup, since references live on the host,
   * not [ServePreview]. [candidates] must be in the sibling manifest's order, which decides the
   * canonical sticker and ties.
   */
  fun pair(
    preview: ServePreview,
    kitNodes: Set<String>,
    candidates: List<ServePreview>,
    kitNodesFor: (ServePreview) -> Set<String>,
  ): Pairing? {
    val canonical = candidates.firstOrNull() ?: return null
    val axes = axesOf(preview)
    val axesNoSize = axes - SIZE
    // (cell rank, theme rank), lexicographic, first-wins on a tie — so an unranked field never
    // reorders the manifest and the canonical sticker stays reachable by falling through.
    var best: ServePreview? = null
    var bestRank = 0 to 0
    for (candidate in candidates) {
      val cellRank =
        when {
          kitNodes.isNotEmpty() && kitNodesFor(candidate).any { it in kitNodes } -> 3
          axesOf(candidate) == axes -> 2
          axesOf(candidate) - SIZE == axesNoSize -> 1
          else -> 0
        }
      if (cellRank == 0) continue
      val rank = cellRank to themeRank(preview.theme, candidate.theme)
      if (
        best == null ||
          rank.first > bestRank.first ||
          (rank.first == bestRank.first && rank.second > bestRank.second)
      ) {
        best = candidate
        bestRank = rank
      }
    }
    val chosen = best ?: return Pairing(canonical, Basis.CANONICAL)
    return Pairing(chosen, if (bestRank.first == 3) Basis.KIT_CELL else Basis.VARIANT_CELL)
  }

  /**
   * The design-kit nodes [references] specify, as `<fileKey>/<nodeId>`. Figma references only: HTML
   * exports and PNGs are addressed by paths inside one catalog's branch, so only node handles are
   * shared across catalogs.
   */
  fun kitNodesOf(references: List<DesignReference>): Set<String> =
    references.mapNotNullTo(LinkedHashSet()) { ServeFigmaSpec.nodeHandle(it) }

  /**
   * How a reader is told which cell was compared — `state=disabled`, `size=192dp, content=icon` —
   * or empty for the component's plain default render.
   */
  fun cellLabel(preview: ServePreview): String =
    axesOf(preview).entries.joinToString(", ") { (key, value) -> "$key=$value" }

  /** `state` is one axis of the variant vector, spelled with its own field. */
  private const val STATE = "state"
  private const val SIZE = "size"

  /**
   * The render's variant coordinates, lower-cased, with "default" spellings stripped: a default
   * cell is the empty vector on both sides (`null` and `"default"` mean the same), so
   * default↔default is an ordinary cell match.
   */
  private fun axesOf(preview: ServePreview): Map<String, String> {
    val axes = LinkedHashMap<String, String>()
    preview.state?.normalized()?.takeIf { it != "default" }?.let { axes[STATE] = it }
    preview.size?.normalized()?.let { axes[SIZE] = it }
    preview.props?.let { axes.putAll(propsOf(it)) }
    return axes
  }

  /**
   * The `{"locale":"ar-XB"}` axis object as plain pairs. Non-primitive values are dropped: nested
   * objects have no spelling two catalogs could agree on.
   */
  private fun propsOf(props: JsonObject): Map<String, String> =
    props.entries
      .mapNotNull { (key, value) ->
        val text =
          (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: return@mapNotNull null
        val axis = key.normalized() ?: return@mapNotNull null
        val normalized = text.normalized() ?: return@mapNotNull null
        axis to normalized
      }
      .toMap()

  private fun String.normalized(): String? = trim().lowercase().takeIf { it.isNotEmpty() }

  /**
   * Theme agreement: same (2), one unthemed (1), different (0). A tiebreak, not a gate: a sibling
   * baking only one theme must still pair, but the same-theme render wins when there is one.
   */
  private fun themeRank(theme: String?, candidate: String?): Int {
    val a = theme?.normalized()
    val b = candidate?.normalized()
    return when {
      a != null && a == b -> 2
      a == null || b == null -> 1
      else -> 0
    }
  }
}
