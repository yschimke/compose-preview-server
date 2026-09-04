package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import kotlinx.serialization.Serializable

/**
 * The UI builder's device-frame menu, derived from the render lane's own catalog.
 *
 * **No geometry is authored here.** Every dp and density comes from [DeviceDimensions.resolve], the
 * same call `ServeDeviceFrame.from` makes when it decides what a `@Preview(device = …)` actually
 * renders at, so a frame the builder offers is a frame the backend will produce. That catalog is
 * already duplicated once — the daemon's copy and `:gradle-plugin`'s discovery copy, which
 * `DeviceDimensionsCatalogDriftTest` guards because they did drift — and the browser editor cannot
 * depend on it directly (`daemon-devices` applies `kotlin.jvm`; `:ui-builder` ships a `wasmJs`
 * target). Hence this: the JVM resolves the catalog, the editor is handed the result.
 *
 * What *is* authored here is presentation — [labelFor] and [groupFor] turn a device id into a
 * display name and a menu section. Both read the id string and nothing else, so a device the
 * catalog learns appears in the menu with no edit here.
 */
internal object UiBuilderDevicePresets {

  /** Menu sections, in the order the editor shows them. Handhelds first; the long tail last. */
  private val GROUP_ORDER =
    listOf("Phones", "Foldables", "Tablets", "Wear OS", "Desktop", "TV", "Automotive", "XR")

  val payload: UiBuilderDevicePresetsV1 by lazy {
    UiBuilderDevicePresetsV1(
      presets =
        DeviceDimensions.KNOWN_DEVICE_IDS.map { id ->
            val spec = DeviceDimensions.resolve(id)
            UiBuilderDevicePresetV1(
              id = id,
              label = labelFor(id),
              group = groupFor(id),
              widthDp = spec.widthDp,
              heightDp = spec.heightDp,
              density = spec.density.toDouble(),
            )
          }
          // Stable ordering: sections in GROUP_ORDER, and within a section the catalog's own
          // insertion order, which already runs oldest-to-newest inside each family.
          .sortedBy {
            GROUP_ORDER.indexOf(it.group).takeIf { index -> index >= 0 } ?: GROUP_ORDER.size
          }
    )
  }

  /**
   * `id:pixel_9_pro_fold` → "Pixel 9 Pro Fold".
   *
   * Token-wise so a device the catalog adds gets a reasonable name for free. [SPECIAL_TOKENS]
   * covers the ones plain capitalisation gets wrong; a bare number or a number-with-suffix (`9`,
   * `3a`, `720p`) is left exactly as the id spells it.
   */
  internal fun labelFor(id: String): String =
    id.removePrefix("id:").split('_').filter(String::isNotEmpty).joinToString(" ") { token ->
      SPECIAL_TOKENS[token]
        ?: if (token.first().isDigit()) token else token.replaceFirstChar(Char::uppercaseChar)
    }

  private val SPECIAL_TOKENS =
    mapOf(
      "wearos" to "Wear OS",
      "xl" to "XL",
      "xr" to "XR",
      "tv" to "TV",
      "4k" to "4K",
      "apis" to "APIs",
      "c" to "C",
    )

  /** The menu section a device id belongs to. Prefix rules only — never geometry. */
  internal fun groupFor(id: String): String {
    val name = id.removePrefix("id:")
    return when {
      name.endsWith("_fold") -> "Foldables"
      name.startsWith("wearos_") -> "Wear OS"
      name.startsWith("desktop_") -> "Desktop"
      name.startsWith("tv_") -> "TV"
      name.startsWith("automotive_") -> "Automotive"
      name.startsWith("xr_") -> "XR"
      name.endsWith("_tablet") || name == "pixel_c" -> "Tablets"
      else -> "Phones"
    }
  }
}

/** One frame the builder's Screen inspector can apply. Mirrored by `UiBuilderDevicePreset`. */
@Serializable
internal data class UiBuilderDevicePresetV1(
  val id: String,
  val label: String,
  val group: String,
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
)

@Serializable
internal data class UiBuilderDevicePresetsV1(
  val schemaVersion: Int = 1,
  val presets: List<UiBuilderDevicePresetV1>,
)
