package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The cross-catalog layer diff as data — `GET /{system}/parallel/{preview}?format=json`.
 *
 * Its own wire type rather than the internal [ServeParallelLayers] shapes, for the reason
 * [ServeDesignPagesPayload] is: what a consumer reads has to be stable across a refactor of the
 * projection behind it, and the internal type carries derived Kotlin properties (`notable`,
 * `agrees`) that are cheaper to state once here as a `status` string than to re-derive in every
 * reader.
 *
 * The JSON is the point of the surface as much as the page is. "Do our two runtimes resolve the
 * same font family for this cell?" is a question a CI job should be able to gate on, and a check
 * that has to parse markup to answer it is a check nobody writes.
 */
object ServeParallelLayersPayload {

  const val SCHEMA = "compose-preview-parallel-layers/v1"

  @Serializable
  data class Document(
    val schema: String = SCHEMA,
    val system: String,
    val previewId: String,
    val componentId: String? = null,
    /** This render's own cell (`state=disabled`), or absent for the component's default render. */
    val cell: String? = null,
    val sibling: Sibling,
    val layers: List<Layer> = emptyList(),
    /** Rows where both sides resolved a property differently — the count a gate reads. */
    val differing: Int = 0,
    /** Rows only one side draws at all. Stated, never dropped; see [ServeParallelLayers]. */
    val unpaired: Int = 0,
  )

  @Serializable
  data class Sibling(
    val system: String,
    val label: String,
    val previewId: String,
    val componentId: String? = null,
    /** `kitCell` / `variantCell` / `canonical` — how the two renders came to be paired. */
    val pairedBy: String,
  )

  @Serializable
  data class Layer(
    val kind: String,
    val paired: Int = 0,
    val differing: Int = 0,
    val onlyHere: Int = 0,
    val onlyThere: Int = 0,
    val rows: List<Row> = emptyList(),
  )

  @Serializable
  data class Row(
    val subject: String,
    /** `both` / `onlyHere` / `onlyThere`. */
    val presence: String,
    val here: String? = null,
    val there: String? = null,
    val fields: List<Field> = emptyList(),
  )

  @Serializable
  data class Field(
    val name: String,
    val here: String? = null,
    val there: String? = null,
    /** `agrees` / `differs` / `onlyHere` / `onlyThere`. */
    val status: String,
  )

  private val JSON = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  fun encode(
    system: String,
    previewId: String,
    componentId: String?,
    cell: String,
    sibling: Sibling,
    diff: ServeParallelLayers.Diff,
  ): String =
    JSON.encodeToString(
      Document(
        system = system,
        previewId = previewId,
        componentId = componentId,
        cell = cell.takeIf { it.isNotEmpty() },
        sibling = sibling,
        layers =
          diff.layers.map { layer ->
            Layer(
              kind = layer.kind,
              paired = layer.paired,
              differing = layer.differing,
              onlyHere = layer.onlyHere,
              onlyThere = layer.onlyThere,
              rows =
                layer.rows.map { row ->
                  Row(
                    subject = row.subject,
                    presence = presenceWire(row.presence),
                    here = row.here,
                    there = row.there,
                    fields =
                      row.fields.map { field ->
                        Field(
                          name = field.name,
                          here = field.here,
                          there = field.there,
                          status = fieldStatus(field),
                        )
                      },
                  )
                },
            )
          },
        differing = diff.differing,
        unpaired = diff.unpaired,
      )
    )

  internal fun pairedByWire(basis: ServeParallelPairing.Basis): String =
    when (basis) {
      ServeParallelPairing.Basis.KIT_CELL -> "kitCell"
      ServeParallelPairing.Basis.VARIANT_CELL -> "variantCell"
      ServeParallelPairing.Basis.CANONICAL -> "canonical"
    }

  private fun presenceWire(presence: ServeParallelLayers.Presence): String =
    when (presence) {
      ServeParallelLayers.Presence.BOTH -> "both"
      ServeParallelLayers.Presence.ONLY_HERE -> "onlyHere"
      ServeParallelLayers.Presence.ONLY_THERE -> "onlyThere"
    }

  private fun fieldStatus(field: ServeParallelLayers.Field): String =
    when {
      field.agrees -> "agrees"
      field.differs -> "differs"
      field.here != null -> "onlyHere"
      else -> "onlyThere"
    }
}
