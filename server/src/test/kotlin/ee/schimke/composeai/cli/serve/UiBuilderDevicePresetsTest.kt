package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiBuilderDevicePresetsTest {

  private val presets = UiBuilderDevicePresets.payload.presets

  @Test
  fun `every catalog device is offered exactly once`() {
    assertEquals(
      DeviceDimensions.KNOWN_DEVICE_IDS.toList().sorted(),
      presets.map { it.id }.sorted(),
    )
  }

  @Test
  fun `geometry is the catalog's, never restated`() {
    // The point of the whole feature: a preset describes the frame the render lane resolves. If
    // this fails, the builder is offering a size the backend will not produce.
    presets.forEach { preset ->
      val spec = DeviceDimensions.resolve(preset.id)
      assertEquals(spec.widthDp, preset.widthDp, preset.id)
      assertEquals(spec.heightDp, preset.heightDp, preset.id)
      assertEquals(spec.density.toDouble(), preset.density, preset.id)
    }
  }

  @Test
  fun `every preset satisfies the editor's screen-environment bounds`() {
    // Restated from `ScreenEnvironmentSettings.validationError` in `:ui-builder`, which `:server`
    // cannot see (it consumes the editor as a built wasm bundle, not as Kotlin). This is the drift
    // guard: a device the catalog learns that falls outside these bounds would appear in the menu
    // and then be rejected on click, so it fails here instead.
    presets.forEach { preset ->
      assertTrue(preset.widthDp in 180..3840, "${preset.id} width ${preset.widthDp}")
      assertTrue(preset.heightDp in 180..3840, "${preset.id} height ${preset.heightDp}")
      assertTrue(preset.density in 0.5..4.0, "${preset.id} density ${preset.density}")
    }
  }

  @Test
  fun `sections run handhelds first and hold every device`() {
    assertEquals(
      listOf("Phones", "Foldables", "Tablets", "Wear OS", "Desktop", "TV", "Automotive", "XR"),
      presets.map { it.group }.distinct(),
    )
  }

  @Test
  fun `labels are derived from the device id`() {
    assertEquals("Pixel 9 Pro Fold", UiBuilderDevicePresets.labelFor("id:pixel_9_pro_fold"))
    assertEquals("Pixel 3a XL", UiBuilderDevicePresets.labelFor("id:pixel_3a_xl"))
    assertEquals("Wear OS Small Round", UiBuilderDevicePresets.labelFor("id:wearos_small_round"))
    assertEquals("TV 4K", UiBuilderDevicePresets.labelFor("id:tv_4k"))
    assertEquals("Pixel C", UiBuilderDevicePresets.labelFor("id:pixel_c"))
    assertEquals(
      "Automotive 1408p Landscape With Google APIs",
      UiBuilderDevicePresets.labelFor("id:automotive_1408p_landscape_with_google_apis"),
    )
  }

  @Test
  fun `groups are derived from the device id`() {
    assertEquals("Foldables", UiBuilderDevicePresets.groupFor("id:pixel_fold"))
    assertEquals("Tablets", UiBuilderDevicePresets.groupFor("id:medium_tablet"))
    assertEquals("Tablets", UiBuilderDevicePresets.groupFor("id:pixel_c"))
    assertEquals("Phones", UiBuilderDevicePresets.groupFor("id:resizable"))
    assertEquals("Wear OS", UiBuilderDevicePresets.groupFor("id:wearos_square"))
  }

  @Test
  fun `the three frames a designer actually switches between are present and distinct`() {
    val byId = presets.associateBy { it.id }
    val phone = checkNotNull(byId["id:pixel_7"])
    val foldable = checkNotNull(byId["id:pixel_fold"])
    val tablet = checkNotNull(byId["id:pixel_tablet"])
    assertEquals(
      listOf(411 to 914, 841 to 701, 1280 to 800),
      listOf(phone, foldable, tablet).map { it.widthDp to it.heightDp },
    )
    assertEquals(listOf(2.625, 2.625, 2.0), listOf(phone, foldable, tablet).map { it.density })
  }
}
