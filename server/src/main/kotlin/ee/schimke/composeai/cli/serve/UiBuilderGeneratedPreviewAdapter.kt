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
  /**
   * True when [composableName] names a **Wear widget**'s three declarations rather than a screen.
   *
   * A widget's generated source declares `<name>Content` (a `@RemoteComposable` body),
   * `<name>Background` (its `WearWidgetBrush`) and `<name>Params` (the container spec its scaffold
   * describes). There is no screen in it to call, so the entry composes none: it records the three
   * through Glance Wear's own `WearWidgetDocument`, which draws the host's container around the
   * body exactly as the launcher does — the frame the builder's canvas draws beside it — and plays
   * the document with the player [widgetPlayer] names.
   *
   * Exclusive with [remoteCapture] in practice and not asserted so: that mode wraps a body in
   * `RemoteOverridablePreview` to publish a document, and a widget is drawn rather than published.
   * A caller setting both would get the widget entry, which is the one that can draw.
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

    /**
     * The entry for a **Wear widget**, drawn inside the container its host draws.
     *
     * `WearWidgetPreview` is upstream's own tooling wrapper
     * (`androidx.glance.wear:wear-tooling-preview`): it builds the `WearWidgetDocument` from the
     * brush and the body, runs it through the same `WearWidgetContainer` the real widget pipeline
     * does — the rounded background, the host padding, the content inset inside it — and rasters
     * the result. Naming it rather than composing the body directly is the difference between a
     * render of the widget and a render of its contents floating on nothing.
     *
     * Not `CapturingWearWidgetPreview`, the compose-ai-tools wrapper that additionally offers the
     * encoded document to the render harness's `.rc` sidecar. This lane wants a **frame**; a
     * document is what the inline-capture lane exists to produce, and naming the wrapper here would
     * put `ee.schimke.composeai:wear-preview-runtime` on the list of things a bundle must carry
     * before a widget design can be previewed against it, to publish bytes nobody reads.
     *
     * The `@Preview` canvas is the container's whole frame — content plus the padding the design
     * authored — because that is what the wrapper sizes itself to and what the builder's canvas
     * draws. The design's `environment` is a screen's and is deliberately not used here.
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
     * The widget entry [UiBuilderWidgetPlayer.CMP] selects: the same recording, a different player.
     *
     * The document is captured exactly as [wearWidgetEntry]'s `WearWidgetPreview` captures it — the
     * generated params carry Glance Wear's default `SAFE_FALLBACK_VERSION`, so the AndroidX writer
     * records the operations the oldest widget host accepts, inside the same `WearWidgetContainer`.
     * Only the playback differs: the bytes go to the Compose Multiplatform `RcComposePlayer`
     * (cmp-android) rather than AndroidX's `RemoteDocumentPreview`.
     *
     * That player is the reason for this entry. AndroidX's drops a `DrawPath` sized from the
     * component it draws behind, so a `RemoteButton` came back as its label with no container while
     * the editor drew the filled pill (yschimke/compose-ui-builder#511). The CMP player draws it,
     * from the same bytes. The recording is still the AndroidX writer's, which is what keeps this
     * lane the authoritative one.
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
 * Which player draws a Wear widget's recorded document in the native lane.
 *
 * Both record through the AndroidX writer on the Android daemon; they differ only in playback. Kept
 * as a switch rather than a replacement so the lane can go back to the upstream preview the day its
 * player draws what the CMP one does (`--ui-builder-widget-player androidx`).
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
