package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class ServeNativePreviewTest {
  @Test
  fun `native preview is off unless the setting turns it on`() {
    assertFalse(ServeNativePreview.enabled(emptyMap()))
    assertFalse(ServeNativePreview.enabled(mapOf("SERVE_NATIVE_PREVIEW" to "")))
    // settings.json's `false` reaches the process as `0`.
    assertFalse(ServeNativePreview.enabled(mapOf("SERVE_NATIVE_PREVIEW" to "0")))
    assertTrue(ServeNativePreview.enabled(mapOf("SERVE_NATIVE_PREVIEW" to "1")))
    assertTrue(ServeNativePreview.enabled(mapOf("SERVE_NATIVE_PREVIEW" to "true")))
  }

  @Test
  fun `the flag is a managed setting the entrypoint exports as one or zero`() {
    val setting = ServeSettings.ALL.single { it.key == "nativePreview" }
    assertEquals("SERVE_NATIVE_PREVIEW", setting.env)
    assertEquals(ServeSettings.Apply.RESTART, setting.apply)
    assertEquals("1", setting.type.envValue(JsonPrimitive(true)))
    assertEquals("0", setting.type.envValue(JsonPrimitive(false)))
    assertTrue(ServeNativePreview.startupLine(false).contains("OFF"))
  }
}
