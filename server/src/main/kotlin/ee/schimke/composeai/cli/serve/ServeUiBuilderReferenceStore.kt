package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The **reference picture** attached to one design: a Figma export, a screenshot, a mock, together
 * with how the operator has it aligned and compared.
 *
 * ### Why this is not in the design document
 *
 * [ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1] has an `assets` map, and this is
 * deliberately not stored in it. Three reasons, in order of weight:
 *
 * 1. **It is not part of the design.** A reference is scaffolding for the person building a screen.
 *    It must never reach the Compose export, the SVG export, or the rendered document — a mock that
 *    turned into an `m3/image` node would ship in the generated Kotlin.
 * 2. **The wire cannot carry it.** `DesignMutationV1` is a closed set with no asset mutation, so
 *    there is no way to write `assets` after `createDesign` without releasing `ui-builder-protocol`
 *    — and doing so would be releasing a protocol change to store something point 1 says should not
 *    be in the document.
 * 3. **It must not cost the document anything.** The document is replayed, hashed, diffed for
 *    catalog upgrades, and pushed to every subscriber on every edit. A multi-megabyte PNG inside it
 *    would be carried through all of that, every time, to be drawn by one person's editor.
 *
 * So it lives here: one file per design, beside the UI-builder state directory, read when a design
 * is opened and replaced when it changes. Losing this directory loses alignment work and no design
 * content, which is the correct blast radius.
 *
 * ### What it will accept
 *
 * Raster (PNG, JPEG, WebP) and SVG, bounded at [maximumBytes]. SVG is admitted here where
 * [ServeImageFormats] deliberately refuses it, and the difference is real rather than an
 * inconsistency: that lane hands bytes back from this origin as a *document* anyone with the link
 * can navigate to, while a reference is returned base64-encoded inside a JSON body and drawn into a
 * Skia canvas by the editor that asked for it. Nothing here is ever served as `image/svg+xml`, so
 * there is no page for active content to run on — and it is checked for active content regardless,
 * by [referenceSvgRefusal], because "nothing navigates to it today" is not a boundary.
 *
 * The same checks exist in the editor, in `:ui-builder`, so that a bad paste is refused without a
 * round trip. That is a second implementation of one rule rather than a shared one because
 * `:server` may not depend on the Compose module; this copy is the authority, since the other runs
 * in a browser this host does not control.
 */
class ServeUiBuilderReferenceStore(
  private val root: Path,
  /** Ceiling on one decoded picture. Matches the editor's own limit; see `MAX_REFERENCE_BYTES`. */
  val maximumBytes: Int = DEFAULT_MAXIMUM_BYTES,
  /**
   * How many designs may hold a reference at once.
   *
   * A cap rather than a total-byte budget with eviction: evicting one design's reference to make
   * room for another's would silently destroy alignment work, and the operator would find out by
   * opening a design and seeing an empty overlay. A refusal at the door is legible, names the
   * limit, and leaves every existing reference intact.
   */
  private val maximumDesigns: Int = DEFAULT_MAXIMUM_DESIGNS,
) {
  init {
    Files.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder reference root is not a directory: $root" }
  }

  /** What one design has attached, or null when it has nothing. */
  fun read(designId: String): StoredReference? {
    val file = fileFor(designId)
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > maximumBytes.toLong() * 2) null
      else
        REFERENCE_JSON.decodeFromString(
          StoredReference.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      // A file this process cannot read is a file it will happily replace on the next import. The
      // alternative — failing the design's open because its reference is corrupt — makes an
      // optional overlay able to take a design offline.
      null
    }
  }

  /**
   * Replace everything [designId] has attached — base picture, pieces and marks — or explain why
   * not.
   *
   * Replaces rather than versions: a design has one reference, and the previous one is what the
   * operator just chose to stop using. The whole stack moves together because the pieces and the
   * marks are positioned against the base, and a store holding one round's marks over the next
   * round's picture is a store nobody asked for.
   */
  fun replace(designId: String, request: ReferenceUploadRequest): ReferenceWriteResult {
    val candidate =
      StoredReference(
        designId = designId,
        image = request.image,
        settings = request.settings.sanitized(),
        pieces = request.pieces.map { it.sanitized() },
        marks = request.marks.mapNotNull { it.sanitizedOrNull() },
        updatedAtEpochMillis = System.currentTimeMillis(),
      )
    if (candidate.image == null && candidate.pieces.isEmpty() && candidate.marks.isEmpty()) {
      // Nothing to keep is not a refusal; it is the operator having removed everything, and the
      // honest storage for that is no file at all.
      delete(designId)
      return ReferenceWriteResult.Stored(candidate)
    }
    if (candidate.marks.size > MAXIMUM_MARKS) {
      return ReferenceWriteResult.Refused("a design may carry at most $MAXIMUM_MARKS marks")
    }
    if (candidate.pieces.size > MAXIMUM_PIECES) {
      return ReferenceWriteResult.Refused("a design may carry at most $MAXIMUM_PIECES pieces")
    }
    var totalBytes = 0L
    val identified = mutableMapOf<String, StoredReferenceImage>()
    candidate.images.forEach { image ->
      val decoded =
        try {
          Base64.getDecoder().decode(image.base64)
        } catch (_: IllegalArgumentException) {
          return ReferenceWriteResult.Refused("a reference picture is not valid base64")
        }
      refusal(image.mediaType, decoded)?.let {
        return ReferenceWriteResult.Refused(it)
      }
      totalBytes += decoded.size
      if (totalBytes > maximumBytes) {
        return ReferenceWriteResult.Refused(
          "a design's reference must be under ${maximumBytes / (1024 * 1024)} MB in total"
        )
      }
      // The identity the editor caches its decode against is the content, not whatever the client
      // proposed: two designs given the same mock get the same id, and a client cannot make one
      // picture masquerade as another by naming it. Dimensions are read from the bytes for the
      // same reason, where the format states them.
      val size = ServeImageFormats.detect(decoded)?.size?.invoke(decoded)
      identified[image.base64] =
        image.copy(
          id = sha256Hex(decoded),
          widthPx = size?.width ?: image.widthPx,
          heightPx = size?.height ?: image.heightPx,
        )
    }
    val file = fileFor(designId)
    if (!Files.exists(file) && storedDesigns() >= maximumDesigns) {
      return ReferenceWriteResult.Refused(
        "this host already holds references for $maximumDesigns designs"
      )
    }
    val stored =
      candidate.copy(
        image = candidate.image?.let { identified.getValue(it.base64) },
        pieces = candidate.pieces.map { it.copy(image = identified.getValue(it.image.base64)) },
      )
    return write(file, stored)
  }

  /**
   * Re-aim what is already attached, without moving its bytes.
   *
   * The route a dragged slider and a rubbed-out mark take. Null result means there is nothing
   * attached to re-aim, which the route answers as a 404 rather than by inventing an empty record.
   */
  fun replaceSettings(designId: String, request: ReferenceSettingsRequest): ReferenceWriteResult? {
    val current = read(designId) ?: return null
    val pieces = request.pieces?.map { it.sanitized() }
    if (pieces != null && pieces.size > MAXIMUM_PIECES) {
      return ReferenceWriteResult.Refused("a design may carry at most $MAXIMUM_PIECES pieces")
    }
    val marks = request.marks?.mapNotNull { it.sanitizedOrNull() }
    if (marks != null && marks.size > MAXIMUM_MARKS) {
      return ReferenceWriteResult.Refused("a design may carry at most $MAXIMUM_MARKS marks")
    }
    // Pieces name pictures this route never carries, so they may only be *kept or dropped* here,
    // never introduced: a piece whose id is not already stored has no bytes behind it, and
    // accepting it would leave a record pointing at a picture that does not exist.
    val known = current.pieces.associateBy { it.id }
    val resolved = pieces?.mapNotNull { piece ->
      known[piece.id]?.let { piece.copy(image = it.image) }
    }
    return write(
      fileFor(designId),
      current.copy(
        settings = request.settings.sanitized(),
        pieces = resolved ?: current.pieces,
        marks = marks ?: current.marks,
        updatedAtEpochMillis = System.currentTimeMillis(),
      ),
    )
  }

  /** Detach, if anything is attached. Returns whether a file was removed. */
  fun delete(designId: String): Boolean =
    try {
      Files.deleteIfExists(fileFor(designId))
    } catch (_: IOException) {
      false
    }

  /** Why these bytes may not be attached, or null when they may. */
  fun refusal(mediaType: String, decoded: ByteArray): String? {
    if (mediaType !in SUPPORTED_MEDIA_TYPES) {
      return "a reference must be a PNG, JPEG, WebP or SVG"
    }
    if (decoded.isEmpty()) return "the reference is empty"
    if (decoded.size > maximumBytes) {
      return "a reference must be under ${maximumBytes / (1024 * 1024)} MB"
    }
    if (mediaType == SVG_MEDIA_TYPE) {
      return referenceSvgRefusal(decoded.toString(StandardCharsets.UTF_8))
    }
    // Sniffed, not trusted: the declared type decides how the editor decodes, so bytes that are
    // not that format are refused here rather than failing silently in someone's browser.
    if (ServeImageFormats.detect(decoded) == null) return "the bytes are not a readable image"
    return null
  }

  private fun write(file: Path, stored: StoredReference): ReferenceWriteResult {
    val encoded = REFERENCE_JSON.encodeToString(StoredReference.serializer(), stored)
    return try {
      val temporary = Files.createTempFile(root, "reference", ".tmp")
      try {
        Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
      }
      ReferenceWriteResult.Stored(stored)
    } catch (_: IOException) {
      ReferenceWriteResult.Refused("the reference could not be written to disk")
    }
  }

  private fun storedDesigns(): Int =
    try {
      Files.list(root)
        .use { entries -> entries.filter { it.toString().endsWith(".json") }.count() }
        .toInt()
    } catch (_: IOException) {
      0
    }

  /**
   * A design id is caller-supplied text, so it never becomes a path segment: the file is named by
   * the digest of the id, which is fixed-length, path-safe, and cannot escape [root].
   */
  private fun fileFor(designId: String): Path =
    root.resolve(sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)) + ".json")

  companion object {
    const val SVG_MEDIA_TYPE: String = "image/svg+xml"

    val SUPPORTED_MEDIA_TYPES: Set<String> =
      setOf("image/png", "image/jpeg", "image/webp", SVG_MEDIA_TYPE)

    /** See `MAX_REFERENCE_BYTES` in `:ui-builder`; the two are one limit stated twice. */
    const val DEFAULT_MAXIMUM_BYTES: Int = 8 * 1024 * 1024

    const val DEFAULT_MAXIMUM_DESIGNS: Int = 500

    /**
     * Ceilings on the small half of a reference.
     *
     * Marks and pieces are tiny next to the pictures, but they are also the half a client can
     * append to without uploading anything — so they get their own bound rather than relying on the
     * byte budget the pictures are checked against.
     */
    const val MAXIMUM_MARKS: Int = 500

    const val MAXIMUM_PIECES: Int = 32

    private val REFERENCE_JSON = Json {
      encodeDefaults = true
      explicitNulls = false
      ignoreUnknownKeys = true
    }

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
      }
  }
}

sealed interface ReferenceWriteResult {
  data class Stored(val reference: StoredReference) : ReferenceWriteResult

  /** A sentence the route hands back verbatim; it is written to be read by an operator. */
  data class Refused(val reason: String) : ReferenceWriteResult
}
