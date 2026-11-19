package ee.schimke.composeai.cli.serve

/**
 * The server's `watchFaceValidation` flag (`settings.json` key `watchFaceValidation`, variable
 * `SERVE_WATCH_FACE_VALIDATION`): whether `watchface-validation` and
 * `ui_builder_validate_watch_face` may run Google's validators, which start a packager JVM per
 * face. Off unless set, so a production box keeps to the editor's in-browser checks until it is
 * turned on in reviewable settings; locally, `SERVE_WATCH_FACE_VALIDATION=1` turns it on.
 *
 * Bound at startup ([ServeSettings.Apply.RESTART]), and read through
 * [WatchFaceValidatorAvailability.production]'s `enabled`, so the route answers
 * [WatchFaceValidatorAvailability.DISABLED] rather than looking for a packager.
 */
internal object ServeWatchFaceValidation {
  const val SETTING_KEY = "watchFaceValidation"
  const val ENV = "SERVE_WATCH_FACE_VALIDATION"

  /** `1` or `true`; the entrypoint exports a `false` in `settings.json` as `0`. */
  fun enabled(env: Map<String, String> = System.getenv()): Boolean =
    env[ENV]?.trim()?.lowercase() in setOf("1", "true")

  fun startupLine(enabled: Boolean): String =
    "serve: watch-face validation (Google's validators) is " +
      (if (enabled) "ON" else "OFF") +
      " ($SETTING_KEY / $ENV)"
}
