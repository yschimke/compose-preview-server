package ee.schimke.composeai.cli.serve

import kotlinx.serialization.Serializable

/**
 * Switches for the server's expensive optional work, read once at startup and reported in the log
 * and on `/status.json`.
 *
 * Each is a `SERVE_*` variable ([ServeSettings.ALL]), on only for `1` or `true`. Absent, empty or
 * anything else is OFF in this process: a test that needs a lane turns it on explicitly, and a bare
 * `serve` run stays lean. The production image is where the defaults are chosen: its entrypoint
 * (`deploy/image/entrypoint.sh`) turns [nativePreview] and [watchFaceValidation] on unless the
 * deployment set them to `false`, and leaves [catalogNativeRender] off. This is the one definition
 * of these switches; a route reads its value from the instance [ServeHttpServer] holds.
 */
@Serializable
data class ServeFeatureFlags(
  /**
   * Starting the Android watch-face runtime for a design's Native pane (`dwf-preview`). Off: the
   * start answers 503 `native_preview_disabled` and the pane's status says so. On, a session still
   * packages and boots only on an explicit start (the pane opening, or its Reload): an edit marks a
   * running session `stale` and never repackages it.
   */
  val nativePreview: Boolean = false,
  /**
   * The Google watch-face validator route and MCP tool. On, they still run only when asked: the
   * Validate panel's Run button, or `ui_builder_validate_watch_face`. Nothing else starts them.
   */
  val watchFaceValidation: Boolean = false,
  /**
   * A catalog render on `wfRenderer: "dwf-apk"` (the catalog's own watch faces on the Android
   * runtime). Separate from [nativePreview], and off even in the image: that path still takes the
   * render daemon down (watchface-tools#72), so it is refused until that is fixed.
   */
  val catalogNativeRender: Boolean = false,
) {
  fun describe(): String =
    "serve: feature flags: nativePreview=$nativePreview, " +
      "watchFaceValidation=$watchFaceValidation, catalogNativeRender=$catalogNativeRender"

  companion object {
    const val NATIVE_PREVIEW_ENV = "SERVE_NATIVE_PREVIEW"
    const val WATCH_FACE_VALIDATION_ENV = "SERVE_WATCH_FACE_VALIDATION"
    const val CATALOG_NATIVE_RENDER_ENV = "SERVE_CATALOG_NATIVE_RENDER"

    /** Every lane on: for a test, or a box that wants all of it. */
    val ALL_ON =
      ServeFeatureFlags(
        nativePreview = true,
        watchFaceValidation = true,
        catalogNativeRender = true,
      )

    /** The 503 code a disabled Native pane answers with. */
    const val NATIVE_PREVIEW_DISABLED = "native_preview_disabled"

    /** The code a catalog `wfRenderer: "dwf-apk"` render answers with while it is switched off. */
    const val CATALOG_NATIVE_RENDER_DISABLED = "catalog_native_render_disabled"

    fun fromEnvironment(env: Map<String, String> = System.getenv()): ServeFeatureFlags =
      ServeFeatureFlags(
        nativePreview = flag(env, NATIVE_PREVIEW_ENV),
        watchFaceValidation = flag(env, WATCH_FACE_VALIDATION_ENV),
        catalogNativeRender = flag(env, CATALOG_NATIVE_RENDER_ENV),
      )

    private fun flag(env: Map<String, String>, name: String): Boolean {
      return env[name]?.trim()?.lowercase() in setOf("1", "true")
    }
  }
}
