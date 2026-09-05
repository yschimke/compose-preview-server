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
    internal fun previewEntry(composableName: String, widthDp: Int, heightDp: Int): String =
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
  }
}
