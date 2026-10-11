package ee.schimke.composeai.cli.serve

/**
 * Output from `CapabilityComposeCodeExporter`, ready for the server's compile lane. The exporter
 * owns source and name; the server owns catalog target, compiler and render job, which need the
 * live-bundle classpath and sidecars. Source-only so the UI-builder runtime depends on none of
 * those.
 */
data class UiBuilderGeneratedCompose(
  val source: String,
  val composableName: String,
  /** Exact served-catalog target understood by [PlaygroundCompileService]. */
  val catalog: String,
  val widthDp: Int,
  val heightDp: Int,
  /**
   * The playground `confType`, which also chooses the daemon: `compose-cmp` is the desktop Skiko
   * daemon, `compose-android` the Robolectric one, required for Wear (Wear Material 3 is an Android
   * AAR that won't even compile on desktop). Defaulted; the design's catalog now decides it
   * ([UiBuilderNativeTarget]).
   */
  val confType: String = COMPOSE_CMP,
  /**
   * True when the synthesized `@Preview` should capture the generated composable as a Remote
   * Compose document: a `@RemoteComposable` body draws nothing when composed, so the entry wraps it
   * in `RemoteOverridablePreview` (where `captureSingleRemoteDocument` and its display/density
   * choices live). Explicit rather than inferred from [confType].
   */
  val remoteCapture: Boolean = false,
  /**
   * True when [composableName] names a Wear widget's three declarations (`<name>Content` body,
   * `<name>Background` brush, `<name>Params` container spec) rather than a screen. The entry
   * records them through Glance Wear's `WearWidgetDocument` inside the host's container and plays
   * it with [widgetPlayer]. Effectively exclusive with [remoteCapture]; if both are set the widget
   * entry wins.
   */
  val wearWidget: Boolean = false,
  /** Which player draws a [wearWidget]'s recorded document; ignored for anything else. */
  val widgetPlayer: UiBuilderWidgetPlayer = UiBuilderWidgetPlayer.DEFAULT,
  /** The stock Material 3 design environment; null preserves the catalog theme. */
  val material3Theme: String? = null,
) {
  companion object {
    /** The Skiko desktop daemon — every catalog whose components are Compose Multiplatform. */
    const val COMPOSE_CMP = "compose-cmp"

    /** The Robolectric daemon, and the only one that can load an Android AAR. */
    const val COMPOSE_ANDROID = "compose-android"
  }
}

/**
 * Submits capability-generated Compose to the existing playground compile and render lane. No
 * compiler, discovery or renderer of its own: it adds one deterministic `@Preview` file and
 * delegates to [PlaygroundCompileService], so results enter the same first-frame and redeem paths.
 * An internal trusted-source seam, not an arbitrary-Kotlin endpoint; the caller must authorize the
 * export before setting [isSecurityChecked].
 */
class UiBuilderGeneratedPreviewAdapter(private val playground: PlaygroundCompileService) {

  fun compile(
    generated: UiBuilderGeneratedCompose,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    require(generated.source.isNotBlank()) { "generated Compose source must not be blank" }
    require(generated.composableName.matches(KOTLIN_IDENTIFIER)) {
      "generated composable name is not a simple Kotlin identifier"
    }
    require(generated.catalog.isNotBlank()) {
      "UI-builder generated previews require an exact catalog target"
    }
    require(generated.widthDp > 0 && generated.heightDp > 0) {
      "generated preview dimensions must be positive"
    }
    require(generated.confType.isNotBlank()) {
      "UI-builder generated previews require an exact playground mode"
    }

    return playground.run(
      PlaygroundRunRequest(
        files =
          listOf(
            PlaygroundFile(GENERATED_SOURCE_FILE, generated.source),
            PlaygroundFile(
              PREVIEW_SOURCE_FILE,
              previewEntry(
                composableName = generated.composableName,
                widthDp = generated.widthDp,
                heightDp = generated.heightDp,
                material3Theme = generated.material3Theme,
                remoteCapture = generated.remoteCapture,
                wearWidget = generated.wearWidget,
                widgetPlayer = generated.widgetPlayer,
              ),
            ),
          ),
        confType = generated.confType,
        catalog = generated.catalog,
      ),
      isSecurityChecked = isSecurityChecked,
    )
  }

  companion object {
    const val GENERATED_SOURCE_FILE = "UiBuilderGeneratedScreen.kt"
    const val PREVIEW_SOURCE_FILE = "UiBuilderGeneratedPreview.kt"
    const val PREVIEW_ID =
      "generated.uibuilder.preview.UiBuilderGeneratedPreviewKt.UiBuilderGeneratedPreview"

    private val KOTLIN_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** The only Kotlin added to [UiBuilderGeneratedCompose.source], stable byte-for-byte. */
    internal fun previewEntry(
      composableName: String,
      widthDp: Int,
      heightDp: Int,
      remoteCapture: Boolean = false,
      wearWidget: Boolean = false,
      widgetPlayer: UiBuilderWidgetPlayer = UiBuilderWidgetPlayer.DEFAULT,
      material3Theme: String? = null,
    ): String =
      if (wearWidget)
        when (widgetPlayer) {
          UiBuilderWidgetPlayer.CMP -> cmpWearWidgetEntry(composableName, widthDp, heightDp)
          UiBuilderWidgetPlayer.ANDROIDX -> wearWidgetEntry(composableName, widthDp, heightDp)
        }
      else if (remoteCapture) remoteCaptureEntry(composableName, widthDp, heightDp)
      else
        """
        package generated.uibuilder.preview

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import generated.uibuilder.$composableName as GeneratedUiBuilderScreen

        @Preview(widthDp = $widthDp, heightDp = $heightDp)
        @Composable
        fun UiBuilderGeneratedPreview() {
          ${if (material3Theme == null) "GeneratedUiBuilderScreen()" else "androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.${if (material3Theme == "dark") "darkColorScheme" else "lightColorScheme"}()) { GeneratedUiBuilderScreen() }"}
        }
        """
          .trimIndent() + "\n"

    /**
     * The entry for a `@RemoteComposable` body, wrapped in a capture. `RemoteOverridablePreview` is
     * called in the body rather than via `@PreviewWrapper`, whose BINARY retention is invisible at
     * runtime and only recovered from discovered `previews.json` params, which a playground
     * manifest lacks; the annotation would be silently ignored. `RcPlatformProfiles.ANDROIDX` is
     * named explicitly since this call site doesn't inherit the wrapper's default.
     */
    private fun remoteCaptureEntry(composableName: String, widthDp: Int, heightDp: Int): String =
      """
      package generated.uibuilder.preview

      import androidx.compose.remote.creation.profile.RcPlatformProfiles
      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import ee.schimke.composeai.daemon.RemoteOverridablePreview
      import generated.uibuilder.$composableName as GeneratedRemoteContent

      @Preview(widthDp = $widthDp, heightDp = $heightDp)
      @Composable
      fun UiBuilderGeneratedPreview() {
        RemoteOverridablePreview(profile = RcPlatformProfiles.ANDROIDX) { GeneratedRemoteContent() }
      }
      """
        .trimIndent() + "\n"

    /**
     * The entry for a Wear widget, drawn inside its host container via upstream's
     * `WearWidgetPreview` (`androidx.glance.wear:wear-tooling-preview`), which runs the same
     * `WearWidgetContainer` as the real pipeline. Not `CapturingWearWidgetPreview`: this lane wants
     * a frame, and that wrapper would require an extra runtime in every bundle. The canvas is the
     * container's whole frame (content plus authored padding), not the design's screen
     * `environment`.
     */
    private fun wearWidgetEntry(name: String, widthDp: Int, heightDp: Int): String =
      """
      package generated.uibuilder.preview

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.glance.wear.tooling.preview.WearWidgetPreview
      import generated.uibuilder.${name}Background as generatedWidgetBackground
      import generated.uibuilder.${name}Content as GeneratedWidgetContent
      import generated.uibuilder.${name}Params as generatedWidgetParams

      @Preview(widthDp = $widthDp, heightDp = $heightDp)
      @Composable
      fun UiBuilderGeneratedPreview() {
        WearWidgetPreview(
          params = generatedWidgetParams(),
          background = generatedWidgetBackground(),
        ) {
          GeneratedWidgetContent()
        }
      }
      """
        .trimIndent() + "\n"

    /**
     * The widget entry for [UiBuilderWidgetPlayer.CMP]: the same AndroidX-written recording
     * (default `SAFE_FALLBACK_VERSION`, same `WearWidgetContainer`), played by the Compose
     * Multiplatform `RcComposePlayer` instead of AndroidX's `RemoteDocumentPreview`, which drops a
     * `RemoteButton`'s container (yschimke/compose-ui-builder#511). The AndroidX writer keeps this
     * lane authoritative.
     */
    private fun cmpWearWidgetEntry(name: String, widthDp: Int, heightDp: Int): String =
      """
      @file:Suppress("RestrictedApi", "RestrictedApiAndroidX")

      package generated.uibuilder.preview

      import androidx.compose.foundation.layout.Box
      import androidx.compose.foundation.layout.fillMaxSize
      import androidx.compose.runtime.Composable
      import androidx.compose.runtime.remember
      import androidx.compose.ui.Modifier
      import androidx.compose.ui.platform.LocalContext
      import androidx.compose.ui.tooling.preview.Preview
      import androidx.glance.wear.WearWidgetDocument
      import ee.schimke.composeai.rcplayer.compose.RcComposePlayer
      import ee.schimke.composeai.rcplayer.compose.rcGoogleFontsTypefaceLoader
      import ee.schimke.composeai.rcplayer.protocol.RcDocumentCodec
      import generated.uibuilder.${name}Background as generatedWidgetBackground
      import generated.uibuilder.${name}Content as GeneratedWidgetContent
      import generated.uibuilder.${name}Params as generatedWidgetParams
      import kotlinx.coroutines.runBlocking

      @Preview(widthDp = $widthDp, heightDp = $heightDp)
      @Composable
      fun UiBuilderGeneratedPreview() {
        val context = LocalContext.current
        val document =
          remember(context) {
            val bytes = runBlocking {
              WearWidgetDocument(generatedWidgetBackground()) { GeneratedWidgetContent() }
                .captureRawContent(context, generatedWidgetParams(), isInspectionMode = true)
                .rcDocument
            }
            RcDocumentCodec.decode(bytes)
          }
        Box(Modifier.fillMaxSize()) {
          RcComposePlayer(
            document,
            modifier = Modifier.fillMaxSize(),
            typefaces = rcGoogleFontsTypefaceLoader(document),
          )
        }
      }
      """
        .trimIndent() + "\n"
  }
}

/**
 * Which player draws a Wear widget's recorded document in the native lane; both record via AndroidX
 * and differ only in playback. A switch (`--ui-builder-widget-player androidx`) so the lane can
 * return to upstream once its player catches up.
 */
enum class UiBuilderWidgetPlayer(val flagValue: String) {
  /** The Compose Multiplatform `RcComposePlayer` on Android (cmp-android). */
  CMP("cmp-android"),

  /** Upstream `androidx.glance.wear.tooling.preview.WearWidgetPreview` and its AndroidX player. */
  ANDROIDX("androidx");

  companion object {
    val DEFAULT: UiBuilderWidgetPlayer = CMP

    /** The canonical id, or `cmp` for [CMP]: the same ids `--rc-default-player` reads. */
    fun fromFlag(value: String): UiBuilderWidgetPlayer? =
      when (val id = value.trim().lowercase()) {
        "cmp" -> CMP
        else -> entries.firstOrNull { it.flagValue == id }
      }
  }
}
