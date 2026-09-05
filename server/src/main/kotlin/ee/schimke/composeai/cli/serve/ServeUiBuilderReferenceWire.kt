package ee.schimke.composeai.cli.serve

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The reference-overlay payload, on the wire and on disk.
 *
 * One shape for both because they carry the same facts and nothing is gained by translating between
 * two of them: the file *is* the response body, plus a design id and a timestamp an operator
 * looking at the directory will want.
 *
 * Its mirror in the editor (`:ui-builder`'s wasm host) is a separate declaration decoding the same
 * JSON leniently, the way the device-preset payload already works — the two modules cannot share a
 * type, and a tolerant client is what lets this payload gain a field without blanking someone's
 * overlay mid-release.
 */
@Serializable
data class StoredReference(
  @SerialName("schemaVersion") val schemaVersion: Int = SCHEMA_VERSION,
  val designId: String,
  /** The base picture, fitted to the frame. Null when only pieces and marks have been left here. */
  val image: StoredReferenceImage? = null,
  val settings: StoredReferenceSettings = StoredReferenceSettings(),
  /** Pictures placed at a point on the frame rather than fitted to it. */
  val pieces: List<StoredReferencePiece> = emptyList(),
  /** Annotations drawn over the frame, each removable on its own. */
  val marks: List<StoredReferenceMark> = emptyList(),
  val updatedAtEpochMillis: Long = 0,
) {
  /** Every embedded picture this record carries, for the byte budget. */
  val images: List<StoredReferenceImage>
    get() = listOfNotNull(image) + pieces.map { it.image }

  companion object {
    const val SCHEMA_VERSION: Int = 1
  }
}

@Serializable
data class StoredReferenceImage(
  /** Content digest, assigned by the host. A client's proposal is overwritten, never trusted. */
  val id: String = "",
  /** What the operator will recognise it by; the file name they chose, usually. */
  val name: String = "reference",
  val mediaType: String,
  /** Standard base64, no data-URI prefix. */
  val base64: String,
  /** Natural size, read from the bytes by the host where the format allows it; 0 when unknown. */
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  /**
   * Where the picture came from, kept for provenance and never fetched.
   *
   * A Figma node URL belongs here. This host holds no Figma credential and makes no outbound call
   * for a reference — the import path is an export from Figma pasted or picked by the operator, and
   * this field is the link back, not a fetch instruction.
   */
  val sourceUrl: String? = null,
)

@Serializable
data class StoredReferenceSettings(
  val mode: String = "overlay",
  val visible: Boolean = true,
  val opacityPercent: Int = 50,
  val offsetXDp: Float = 0f,
  val offsetYDp: Float = 0f,
  val scalePercent: Int = 100,
  val splitPercent: Int = 50,
  val alwaysShowBoxes: Boolean = false,
) {
  /**
   * Clamped before storage, so a client cannot persist an overlay nobody can see back out of.
   *
   * The editor clamps the same values for its own drawing; this one exists because the store is
   * reachable by anything holding a write capability, not only by that editor.
   */
  fun sanitized(): StoredReferenceSettings =
    copy(
      mode = if (mode in KNOWN_MODES) mode else "overlay",
      opacityPercent = opacityPercent.coerceIn(0, 100),
      offsetXDp =
        if (offsetXDp.isFinite()) offsetXDp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP) else 0f,
      offsetYDp =
        if (offsetYDp.isFinite()) offsetYDp.coerceIn(-MAX_OFFSET_DP, MAX_OFFSET_DP) else 0f,
      scalePercent = scalePercent.coerceIn(MIN_SCALE_PERCENT, MAX_SCALE_PERCENT),
      splitPercent = splitPercent.coerceIn(0, 100),
    )

  companion object {
    /**
     * Mirrors `ReferenceDiffMode` in `:ui-builder`; an unknown value falls back rather than 400s.
     */
    val KNOWN_MODES: Set<String> = setOf("overlay", "difference", "split", "boxes")

    const val MIN_SCALE_PERCENT: Int = 10
    const val MAX_SCALE_PERCENT: Int = 400
    const val MAX_OFFSET_DP: Float = 4000f
  }
}

/**
 * A picture placed on the frame rather than fitted to it, in fractions of the frame.
 *
 * Fractions rather than dp so a piece survives a device-frame change: one placed over the top third
 * of a phone is still over the top third of the tablet the operator switches to.
 */
@Serializable
data class StoredReferencePiece(
  val id: String,
  val image: StoredReferenceImage,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
  val opacityPercent: Int = 100,
  /**
   * The catalog component this piece is a picture of, when it is a picture of one.
   *
   * Provenance, never behaviour: this host draws nothing from it and resolves nothing with it. It
   * exists so that a piece rasterised out of a live preview can later be rebuilt as real nodes by
   * whoever asks for that, rather than being a picture nobody can trace.
   */
  val componentId: String? = null,
)

/** One annotation. [points] alternates x and y, in frame fractions, tail first. */
@Serializable
data class StoredReferenceMark(
  val id: String,
  val kind: String,
  val points: List<Float>,
  /** `0xAARRGGBB`. A Long because JSON has no unsigned integer and this one has the top bit set. */
  val colorArgb: Long,
  val strokeWidthDp: Float = 2f,
  /** The words a text mark draws, and the caption on an image placeholder. */
  val text: String? = null,
)

/**
 * The request body for `PUT …/reference`.
 *
 * The whole stack in one write, and deliberately so: pieces and marks are positioned against the
 * base picture, so a store holding a new base beside the previous round's marks is a store nobody
 * asked for. Settings-only changes — a slider being dragged — go to the sibling route instead, so
 * that re-aiming an overlay does not re-upload several megabytes per frame.
 */
@Serializable
data class ReferenceUploadRequest(
  val image: StoredReferenceImage? = null,
  val settings: StoredReferenceSettings = StoredReferenceSettings(),
  val pieces: List<StoredReferencePiece> = emptyList(),
  val marks: List<StoredReferenceMark> = emptyList(),
)

/** The request body for `PUT …/reference/settings`: the cheap half, sent as sliders move. */
@Serializable
data class ReferenceSettingsRequest(
  val settings: StoredReferenceSettings,
  /**
   * Marks and pieces, when they moved too.
   *
   * They are here rather than only on the upload route because they are small — a stroke is a few
   * dozen floats — and because rubbing out a mark must not require re-uploading the picture it was
   * drawn over. Null leaves what is stored alone; an empty list clears it.
   */
  val pieces: List<StoredReferencePiece>? = null,
  val marks: List<StoredReferenceMark>? = null,
)

/** What a refusal says, in the one shape every reference route answers errors in. */
@Serializable data class ReferenceErrorResponse(val message: String)

/**
 * Clamped to a rectangle that is on the frame and big enough to grab again.
 *
 * The editor clamps the same way while dragging; this exists because the store is reachable by
 * anything holding a write capability, and a piece persisted at `NaN` would be a piece the operator
 * could never select, move or delete.
 */
fun StoredReferencePiece.sanitized(): StoredReferencePiece {
  val left = left.finiteOr(0f).coerceIn(-1f, 1f)
  val top = top.finiteOr(0f).coerceIn(-1f, 1f)
  val right = right.finiteOr(left + MIN_PIECE_FRACTION).coerceIn(left + MIN_PIECE_FRACTION, 2f)
  val bottom = bottom.finiteOr(top + MIN_PIECE_FRACTION).coerceIn(top + MIN_PIECE_FRACTION, 2f)
  return copy(
    left = left,
    top = top,
    right = right,
    bottom = bottom,
    opacityPercent = opacityPercent.coerceIn(0, 100),
  )
}

/**
 * A mark with a drawable point list, or null.
 *
 * Null rather than a clamp for a malformed one: a stroke with an odd number of coordinates has no
 * repair that is more faithful than dropping it, and a mark nobody can see is worse than a mark
 * that is gone — it still counts against the limit and still comes back on every open.
 */
fun StoredReferenceMark.sanitizedOrNull(): StoredReferenceMark? {
  if (points.size < 4 || points.size % 2 != 0 || points.size > MAX_MARK_POINTS) return null
  if (points.any { !it.isFinite() }) return null
  if (kind !in KNOWN_MARK_KINDS) return null
  return copy(
    points = points.map { it.coerceIn(-1f, 2f) },
    strokeWidthDp = strokeWidthDp.finiteOr(2f).coerceIn(0.5f, 32f),
    text = text?.take(MAX_MARK_TEXT),
  )
}

/**
 * Mirrors `ReferenceMarkupKind` in `:ui-builder`. An unknown kind has no drawing, so it is dropped.
 */
val KNOWN_MARK_KINDS: Set<String> =
  setOf(
    "pen",
    "rectangle",
    "roundedRectangle",
    "ellipse",
    "arrow",
    "fill",
    "text",
    "imagePlaceholder",
  )

/**
 * The ceiling on a markup label, matching the editor's own.
 *
 * Enforced again here because the store is reachable by anything holding a write capability, and a
 * label of unbounded length is a way to make a design's reference arbitrarily large one mark at a
 * time, under the byte budget that only the pictures are checked against.
 */
const val MAX_MARK_TEXT: Int = 160

/**
 * The point ceiling on one freehand stroke.
 *
 * A pen stroke samples per pointer event, so a slow drag across a large canvas is a few thousand
 * coordinates. 8000 floats is a long deliberate stroke and still a few tens of kilobytes; past it,
 * something is generating rather than drawing.
 */
const val MAX_MARK_POINTS: Int = 8000

private const val MIN_PIECE_FRACTION = 0.02f

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
