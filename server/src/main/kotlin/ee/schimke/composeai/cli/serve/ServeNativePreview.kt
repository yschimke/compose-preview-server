package ee.schimke.composeai.cli.serve

/**
 * The server's `nativePreview` flag (`settings.json` key `nativePreview`, variable
 * `SERVE_NATIVE_PREVIEW`): whether native preview may start a runtime host at all. Off unless set,
 * so a production box draws nothing with the real Wear OS runtime until it is turned on in
 * reviewable settings; locally, `SERVE_NATIVE_PREVIEW=1` turns it on.
 *
 * Bound at startup ([ServeSettings.Apply.RESTART]), and read by [DwfPreviewSessions] through
 * `enabled`, so the routes answer `native_preview_disabled` rather than starting anything.
 */
internal object ServeNativePreview {
  const val SETTING_KEY = "nativePreview"
  const val ENV = "SERVE_NATIVE_PREVIEW"

  /** `1` or `true`; the entrypoint exports a `false` in `settings.json` as `0`. */
  fun enabled(env: Map<String, String> = System.getenv()): Boolean =
    env[ENV]?.trim()?.lowercase() in setOf("1", "true")

  fun startupLine(enabled: Boolean): String =
    "serve: native preview (the real watch-face runtime) is " +
      (if (enabled) "ON" else "OFF") +
      " ($SETTING_KEY / $ENV)"
}
