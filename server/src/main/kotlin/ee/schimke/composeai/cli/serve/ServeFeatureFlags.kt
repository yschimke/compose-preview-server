package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Switches for the server's expensive optional work, read once at startup and reported in the log
 * and on `/status.json`.
 *
 * Each is a `SERVE_*` variable ([ServeSettings.ALL]), on only for `1` or `true`. Absent, empty or
 * anything else is OFF, everywhere: a deployment that forgets to set one stays lean, and a test
 * that needs a lane turns it on explicitly. This is the one definition of these switches; a route
 * reads its value from the instance [ServeHttpServer] holds.
 */
@Serializable
data class ServeFeatureFlags(
  /**
   * Starting the Android watch-face runtime for a design's Native pane (`dwf-preview`). Off: the
   * start answers 503 `native_preview_disabled` and the pane's status says so.
   */
  val nativePreview: Boolean = false,
  /** The Google watch-face validator route. */
  val watchFaceValidation: Boolean = false,
) {
  fun describe(): String =
    "serve: feature flags: nativePreview=$nativePreview, watchFaceValidation=$watchFaceValidation"

  companion object {
    const val NATIVE_PREVIEW_ENV = "SERVE_NATIVE_PREVIEW"
    const val WATCH_FACE_VALIDATION_ENV = "SERVE_WATCH_FACE_VALIDATION"

    /** Every lane on: for a test, or a box that wants all of it. */
    val ALL_ON = ServeFeatureFlags(nativePreview = true, watchFaceValidation = true)

    /** The 503 code a disabled Native pane answers with. */
    const val NATIVE_PREVIEW_DISABLED = "native_preview_disabled"

    fun fromEnvironment(env: Map<String, String> = System.getenv()): ServeFeatureFlags =
      ServeFeatureFlags(
        nativePreview = flag(env, NATIVE_PREVIEW_ENV),
        watchFaceValidation = flag(env, WATCH_FACE_VALIDATION_ENV),
      )

    private fun flag(env: Map<String, String>, name: String): Boolean {
      return env[name]?.trim()?.lowercase() in setOf("1", "true")
    }
  }
}
