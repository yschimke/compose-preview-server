package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Which of a sibling catalog's renders of one component is the counterpart of *this* render.
 *
 * The `compareWith` + `parallel` pairing (issue #4621) names a sibling SYSTEM and a counterpart
 * COMPONENT, and stopped there: the walk took the sibling's first preview for that component id, so
 * `Button/Child` paired with whichever render the sibling's manifest happened to list first — its
 * default, in practice. A component drawn as a kit set is mostly *not* its default: the `disabled`,
 * `icon` and `left` cells had no counterpart even where the sibling drew every one of them, and
 * `@CatalogVariant.parallel`'s own promise that a variant is "compared in its own right, not
 * through its parent" was not kept for `@OverrideVariant` cells (issue #4838).
 *
 * The key is the **cell**, not the preview id. Ids diverge across two catalogs of one design system
 * on the breakpoint suffix (`__compact` vs `__192dp`) and on component spelling
 * (`iconbutton-filled` vs `button-icon-filled`), so id matching covers about half of a real pairing
 * today and breaks again at the next rename. Two things both sides already publish do not:
 *
 * 1. the **design-kit node** each render is specified by. A resolved `design-map.json` entry pins
 *    every cell it could place to a `figma:<fileKey>/<nodeId>` handle, and that handle is
 *    republished per preview in `references/index.json` — so two catalogs reproducing one kit file
 *    name the same node for the same cell without either naming its previews the same, and without
 *    the kit's own vocabulary (`kitAxis` / `kitValue`, which the resolver has already consumed by
 *    this point) having to reach the server at all;
 * 2. the render's own **variant coordinates** — `state`, `props`, `size` — which reach the server
 *    on [ServePreview] for every catalog, including one that publishes no design map.
 *
 * Ranked in that order, because they are honest about different things: a shared kit node says the
 * two renders are pictures of one cell of one kit, while equal coordinates say only that two
 * catalogs spell an axis alike — usually the same fact, occasionally a coincidence.
 *
 * **Falling back to the canonical sticker is the floor**, which makes this strictly additive: a
 * cell with no counterpart pairs exactly as it did before, and no pairing that worked stops
 * working. What changes is that the fallback is now *stated* — see [Pairing.basis] and [cellLabel]
 * — rather than presented as though the sibling drew this cell. A cell present on one side and
 * absent on the other is the more interesting half of a parity comparison, so it is said out loud
 * rather than dropped or quietly papered over with a different render.
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
   * Choose [candidates]'s counterpart for [preview], or null when the sibling publishes none at
   * all.
   *
   * [kitNodesOf] is applied to the caller's reference lookup on both sides rather than taken from
   * the previews, because references live on the host and not on [ServePreview].
   *
   * [candidates] must be in the sibling manifest's own order: it is the producer's precedence, and
   * it decides both the canonical sticker and any tie between equally-good cells.
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
   * The design-kit nodes [references] specify, as `<fileKey>/<nodeId>`.
   *
   * Figma-backed references only, and that is the whole set that can key anything: an HTML export
   * or a committed PNG is addressed by a path inside *one* catalog's own delivery branch, so two
   * catalogs pointing at "the same" mock still carry two unrelated strings. A node handle is the
   * one identity the two sides genuinely share.
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
   * The render's variant coordinates, lower-cased and stripped of the "this is the default"
   * spellings.
   *
   * A default cell is the EMPTY vector on both sides — `null` and `"default"` say the same thing,
   * and a catalog that spells it either way must pair with one that spells it the other. Which also
   * makes default↔default an ordinary cell match rather than a fallback: the old walk arrived at
   * the same render for the common case, but by taking the first one published rather than by
   * establishing that it is the same cell.
   */
  private fun axesOf(preview: ServePreview): Map<String, String> {
    val axes = LinkedHashMap<String, String>()
    preview.state?.normalized()?.takeIf { it != "default" }?.let { axes[STATE] = it }
    preview.size?.normalized()?.let { axes[SIZE] = it }
    preview.props?.let { axes.putAll(propsOf(it)) }
    return axes
  }

  /**
   * The `{"locale":"ar-XB"}` axis object as plain pairs. Non-primitive values are dropped rather
   * than stringified: a nested object has no spelling two catalogs could be expected to agree on,
   * and `{"a":{"b":1}}` matching by its JSON text would be a coincidence presented as a pairing.
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
   * How well two renders agree about the theme they were drawn under: both the same (2), one of
   * them unthemed (1), or plainly different (0).
   *
   * A tiebreak rather than a gate. A sibling that bakes one theme where this catalog bakes two must
   * still pair — its render is the only one it has — and the old walk did not consider the theme at
   * all, so a dark page could be handed a light counterpart with nothing said. Ranking it keeps the
   * same-theme render when there is one and takes the other only when there is not.
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
