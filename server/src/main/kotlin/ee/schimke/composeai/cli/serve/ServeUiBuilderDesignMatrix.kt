package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One design on several devices, as one picture (compose-preview-server#1255).
 *
 * ## Why one image
 *
 * An agent asked "does this work on a tablet and a watch?" used to call `ui_builder_view` once per
 * device after editing the environment each time — moving the design's revision for a question that
 * changes nothing — and then hand the person five separate pictures. A contact sheet answers it in
 * one call and one image, which is also what a chat surface can actually show: a single https link
 * under the size a chat client will inline.
 *
 * The cells are the editor's own PNG export of the design with its environment swapped for each
 * device — the same renderer `ui_builder_view` uses — so the sheet shows what a person opening the
 * design at that size would see, and nothing is written to the design to get it.
 */
internal object UiBuilderDesignMatrix {

  /** A device a cell is drawn at. [round] devices are masked to a circle on the sheet. */
  data class Device(
    val id: String,
    val label: String,
    val formFactor: String,
    val widthDp: Int,
    val heightDp: Int,
    val round: Boolean = false,
  )

  val PRESETS: List<Device> =
    listOf(
      Device("phone_small", "Small phone", FORM_PHONE, 360, 640),
      Device("phone", "Phone", FORM_PHONE, 412, 915),
      Device("phone_landscape", "Phone landscape", FORM_PHONE, 915, 412),
      Device("foldable_folded", "Foldable, folded", FORM_FOLDABLE, 412, 915),
      Device("foldable_unfolded", "Foldable, unfolded", FORM_FOLDABLE, 673, 841),
      Device("tablet_portrait", "Tablet portrait", FORM_TABLET, 800, 1280),
      Device("tablet_landscape", "Tablet landscape", FORM_TABLET, 1280, 800),
      Device("wear_small_round", "Wear small round", FORM_WEAR, 192, 192, round = true),
      Device("wear_large_round", "Wear large round", FORM_WEAR, 227, 227, round = true),
      Device("wear_square", "Wear square", FORM_WEAR, 180, 180),
      Device("tv_1080p", "TV 1080p", FORM_TV, 960, 540),
    )

  /** The set a form factor means when a caller names it instead of devices. */
  val FORM_FACTOR_DEFAULTS: Map<String, List<String>> =
    mapOf(
      FORM_PHONE to listOf("phone_small", "phone", "phone_landscape"),
      FORM_FOLDABLE to listOf("foldable_folded", "foldable_unfolded"),
      FORM_TABLET to listOf("tablet_portrait", "tablet_landscape"),
      FORM_WEAR to listOf("wear_small_round", "wear_large_round"),
      FORM_TV to listOf("tv_1080p"),
      // What "does this adapt?" means for a mobile catalog: one of each window size class.
      FORM_ADAPTIVE to listOf("phone", "foldable_unfolded", "tablet_landscape"),
    )

  val FORM_FACTORS: List<String> = FORM_FACTOR_DEFAULTS.keys.toList()

  fun preset(id: String): Device? = PRESETS.firstOrNull { it.id == id }

  /** The form factor a design is for, read off its catalog's platform. */
  fun defaultFormFactor(platform: String?): String =
    when (platform?.lowercase()) {
      "wear",
      "wearos",
      "wear-os" -> FORM_WEAR
      "tv" -> FORM_TV
      else -> FORM_ADAPTIVE
    }

  /** One rendered cell, or the reason it could not be. */
  class Cell(
    val device: Device,
    val theme: String,
    val fontScale: Double,
    val png: ByteArray?,
    val problem: String?,
  )

  /**
   * Lays [cells] out with a caption under each — device, size, theme, font scale — through the one
   * contact-sheet renderer this server has ([ServeContactSheet]), shrinking the tiles until the PNG
   * is under [maxBytes]: a sheet too large to inline is a sheet a chat client shows as a broken
   * link.
   */
  fun compose(cells: List<Cell>, maxBytes: Int = MAX_SHEET_BYTES): ServeContactSheet.Sheet =
    ServeContactSheet.compose(
      cells.map { cell ->
        ServeContactSheet.Tile(png = cell.png, caption = cell.caption(), round = cell.device.round)
      },
      maxBytes = maxBytes,
    )

  private fun Cell.caption(): String = buildString {
    append(device.label)
    append(" · ${device.widthDp}×${device.heightDp}")
    if (theme != THEME_LIGHT) append(" · $theme")
    if (fontScale != 1.0) append(" · ${(fontScale * 100).roundToInt()}%")
  }

  fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

  const val FORM_PHONE = "phone"
  const val FORM_FOLDABLE = "foldable"
  const val FORM_TABLET = "tablet"
  const val FORM_WEAR = "wear"
  const val FORM_TV = "tv"
  const val FORM_ADAPTIVE = "adaptive"
  const val THEME_LIGHT = "light"
  const val THEME_DARK = "dark"

  /** The most cells one call renders: each is an export, and the sheet has to stay legible. */
  const val MAX_CELLS = 16

  /** Under the 3.75 MB a chat client inlines (compose-preview-server#1254), with margin. */
  const val MAX_SHEET_BYTES = 3_500_000

  /** A device's own size for a custom cell is bounded like the design environment's. */
  const val MAX_DEVICE_DP = 4096
}

internal const val UI_BUILDER_DESIGN_MATRIX_SCHEMA = "compose-preview/ui-builder-design-matrix/v1"

/**
 * `ui_builder_render_design_matrix`'s reply: a sentence, the sheet, and each cell's device and box
 * on it. The picture is a signed https link by default, like `ui_builder_view`'s; [imageBase64] is
 * taken off the reply before it is sent and becomes the link, or an image block.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderDesignMatrixV1(
  @EncodeDefault val schema: String = UI_BUILDER_DESIGN_MATRIX_SCHEMA,
  val summary: String,
  val designId: String,
  val revision: Long,
  val columns: Int,
  val rows: Int,
  val image: UiBuilderViewImageV1,
  val cells: List<UiBuilderDesignMatrixCellV1>,
  val imageBase64: String? = null,
)

@Serializable
internal data class UiBuilderDesignMatrixCellV1(
  val index: Int,
  val device: String,
  val label: String,
  val formFactor: String,
  val widthDp: Int,
  val heightDp: Int,
  val round: Boolean = false,
  val theme: String,
  val fontScale: Double,
  /** Where the cell's picture sits on the sheet, in sheet pixels. */
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val rendered: Boolean,
  /** Why the cell is blank, when it is. */
  val problem: String? = null,
)
