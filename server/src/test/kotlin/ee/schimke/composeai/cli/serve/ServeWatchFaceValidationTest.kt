package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class ServeWatchFaceValidationTest {
  @Test
  fun `watch-face validation is off unless the setting turns it on`() {
    assertFalse(ServeWatchFaceValidation.enabled(emptyMap()))
    assertFalse(ServeWatchFaceValidation.enabled(mapOf("SERVE_WATCH_FACE_VALIDATION" to "")))
    // settings.json's `false` reaches the process as `0`.
    assertFalse(ServeWatchFaceValidation.enabled(mapOf("SERVE_WATCH_FACE_VALIDATION" to "0")))
    assertTrue(ServeWatchFaceValidation.enabled(mapOf("SERVE_WATCH_FACE_VALIDATION" to "1")))
    assertTrue(ServeWatchFaceValidation.enabled(mapOf("SERVE_WATCH_FACE_VALIDATION" to "true")))
  }

  @Test
  fun `the flag is a managed setting the entrypoint exports as one or zero`() {
    val setting = ServeSettings.ALL.single { it.key == "watchFaceValidation" }
    assertEquals("SERVE_WATCH_FACE_VALIDATION", setting.env)
    assertEquals(ServeSettings.Apply.RESTART, setting.apply)
    assertEquals("1", setting.type.envValue(JsonPrimitive(true)))
    assertEquals("0", setting.type.envValue(JsonPrimitive(false)))
    assertTrue(ServeWatchFaceValidation.startupLine(false).contains("OFF"))
  }
}
