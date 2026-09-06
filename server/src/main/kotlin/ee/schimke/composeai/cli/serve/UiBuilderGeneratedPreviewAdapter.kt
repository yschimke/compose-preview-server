package ee.schimke.composeai.cli.serve

/**
 * Controlled output from `CapabilityComposeCodeExporter` ready for the server's compile lane.
 *
 * The exporter owns the screen source and name. The server owns the catalog target, compiler and
 * render job because those require the resolved live-bundle classpath and the installed BTA/render
 * sidecars. Keeping this value source-only prevents the UI-builder runtime from depending on any of
 * those host implementations.
 */
data class UiBuilderGeneratedCompose(
  val source: String,
  val composableName: String,
  /** Exact served-catalog target understood by [PlaygroundCompileService]. */
  val catalog: String,
  val widthDp: Int,
  val heightDp: Int,
  /**
   * The playground `confType`, which is the same choice as "which daemon draws this".
   *
   * `compose-cmp` is the Skiko desktop daemon and was the only value while `m3-catalog` was the
   * only catalog with a native lane. `compose-android` is the Robolectric daemon, and it is not an
   * optimisation for Wear — it is the requirement. `androidx.wear.compose:compose-material3` is an
   * Android AAR: a Wear screen does not compile on the desktop classpath at all, let alone render.
   *
   * Defaulted, so every existing caller and test keeps the mode it had; the field exists because
   * the design's catalog now decides it ([UiBuilderNativeTarget]).
   */
  val confType: String = COMPOSE_CMP,
  /**
   * True when the synthesized `@Preview` should **capture** the generated composable as a Remote
   * Compose document rather than compose it.
   *
   * A `@RemoteComposable` body is not a screen: composing it draws nothing, because its
   * declarations are recorded into a document by whatever wraps the composition. So the entry wraps
   * it in `RemoteOverridablePreview`, which is where `captureSingleRemoteDocument` is called and
   * where the display-info and density-behaviour choice was made once, with its reasoning written
   * down beside it. Nothing here re-decides that; it names the call site that already did.
   *
   * Not inferred from [confType]. `remote-compose` is the mode that *publishes* a document, and a
   * snippet reaching it could equally have wrapped its own body; a generated body never does, and
   * saying so explicitly is what keeps the entry generator from guessing.
   */
  val remoteCapture: Boolean = false,
) {
  companion object {
    /** The Skiko desktop daemon — every catalog whose components are Compose Multiplatform. */
    const val COMPOSE_CMP = "compose-cmp"

    /** The Robolectric daemon, and the only one that can load an Android AAR. */
    const val COMPOSE_ANDROID = "compose-android"
  }
}

/**
 * Submits capability-generated Compose to the existing Playground compile and render lane.
 *
 * This adapter deliberately has no compiler, discovery or renderer implementation of its own. It
 * adds one deterministic `@Preview` source file beside the exporter's source, then delegates the
 * complete job to [PlaygroundCompileService]. A successful response therefore has the same
 * compiled-snippet token and enters the same first-frame and redeem paths as an ordinary Playground
 * request.
 *
 * This is an internal trusted-source seam, not a new public arbitrary-Kotlin endpoint. Its caller
 * must snapshot/authorize the design export before setting [isSecurityChecked].
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
                remoteCapture = generated.remoteCapture,
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
    ): String =
      if (remoteCapture) remoteCaptureEntry(composableName, widthDp, heightDp)
      else
        """
        package generated.uibuilder.preview

        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import generated.uibuilder.$composableName as GeneratedUiBuilderScreen

        @Preview(widthDp = $widthDp, heightDp = $heightDp)
        @Composable
        fun UiBuilderGeneratedPreview() {
          GeneratedUiBuilderScreen()
        }
        """
          .trimIndent() + "\n"

    /**
     * The same entry for a `@RemoteComposable` body, wrapped in the capture the mode needs.
     *
     * `RemoteOverridablePreview` is called in the **body** rather than pinned with
     * `@PreviewWrapper(RemoteOverridablePreviewWrapper::class)`, and that is not a style choice:
     * the upstream annotation is `AnnotationRetention.BINARY` and invisible to runtime reflection,
     * so the only path that recovers a wrapper FQN is `previews.json`'s `params.wrapperClassName`,
     * which the Gradle plugin fills at discovery time. A playground snippet's manifest is
     * synthesized from discovered ids and carries no params, so an annotation here would compile
     * and then be silently ignored — a capture that returns no document for a reason nothing
     * reports.
     *
     * `RcPlatformProfiles.ANDROIDX` is the profile the connector's own wrapper defaults to, named
     * explicitly because this call site does not inherit that default.
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
  }
}
