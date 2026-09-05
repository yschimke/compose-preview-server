package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.uibuilder.service.PackagedUiBuilderRenderBundle
import ee.schimke.composeai.uibuilder.service.UiBuilderRenderPort
import ee.schimke.composeai.uibuilder.service.UiBuilderRenderRequest
import java.nio.file.Path

/** Server-side adapter from the UI-builder runtime's narrow port to the existing render host. */
internal class ServeUiBuilderRenderPort private constructor(private val host: ServeRenderHost) :
  UiBuilderRenderPort {
  override val supportsSvg: Boolean = host.hasSvgExport

  override fun renderPng(request: UiBuilderRenderRequest): ByteArray =
    when (
      val outcome = host.render(PackagedUiBuilderRenderBundle.PREVIEW_ID, request.overrides())
    ) {
      is RenderOutcome.Ok -> outcome.png
      RenderOutcome.NotFound -> error("packaged UI-builder preview is missing")
      RenderOutcome.Busy -> error("UI-builder renderer is busy")
      is RenderOutcome.Failed -> error(outcome.reason)
    }

  override fun renderSvg(request: UiBuilderRenderRequest): ByteArray =
    when (
      val outcome = host.renderSvg(PackagedUiBuilderRenderBundle.PREVIEW_ID, request.overrides())
    ) {
      is SvgOutcome.Ok -> outcome.svg
      SvgOutcome.NotFound -> error("packaged UI-builder SVG producer is unavailable")
      is SvgOutcome.Failed -> error(outcome.reason)
    }

  override fun close() = host.close()

  private fun UiBuilderRenderRequest.overrides(): PreviewOverrides =
    PreviewOverrides(
      widthPx = widthPx,
      heightPx = heightPx,
      density = density,
      localeTag = localeTag,
      fontScale = fontScale,
      namedOverrides =
        mapOf(
          PackagedUiBuilderRenderBundle.DOCUMENT_OVERRIDE_KEY to
            PreviewOverrideValue.StringValue(encodedDocument)
        ),
    )

  companion object {
    fun open(
      root: Path,
      onLog: (String) -> Unit = { System.err.println("[ui-builder renderer] $it") },
    ): ServeUiBuilderRenderPort {
      preflightJvm()
      val bundle = PackagedUiBuilderRenderBundle.copyTo(root)
      val generation = bundle.parent
      val state =
        ServeBundleDaemon.materialize(
          bundleFile = bundle.toFile(),
          destDir = generation.resolve("runtime").toFile(),
          system = "ui-builder-renderer",
          onLog = onLog,
        ) ?: error("could not materialize packaged UI-builder renderer bundle")
      require(state.previews.any { it.id == PackagedUiBuilderRenderBundle.PREVIEW_ID }) {
        "packaged UI-builder preview id is missing"
      }
      return ServeUiBuilderRenderPort(
        ServeRenderHost.open(
          descriptorPath = state.descriptor,
          workspaceRoot = state.workspaceRoot,
          workspaceName = state.workspaceName,
          previews = state.previews,
          label = "UI builder renderer",
          onLog = onLog,
        )
      )
    }

    /**
     * Fails before materializing a bundle this JVM cannot load, and says so in one sentence.
     *
     * The bundle carries `:ui-builder`'s compiled previews, which sit above the floor of every
     * artifact a consumer resolves. Rendering them happens in a daemon `ServeRenderHost` starts
     * from `java.home` — this process's own JVM — so on an older host the failure lands as an
     * `UnsupportedClassVersionError` on the stderr of a process the operator never launched, with
     * no mention of Java versions. `ServeRunner` catches what this throws and prints it beside the
     * export capabilities that go with it, which is where an operator is already looking
     * ([#344](https://github.com/yschimke/compose-preview-server/issues/344)).
     *
     * The required version is read from the bundle rather than written here: a copy in the server
     * would be a second number to raise, and the one that drifts is the one nobody rebuilds.
     */
    private fun preflightJvm() {
      jvmPreflightFailure(
          required = PackagedUiBuilderRenderBundle.requiredJavaFeatureVersion(),
          running = Runtime.version().feature(),
          javaHome = System.getProperty("java.home"),
        )
        ?.let { error(it) }
    }

    /**
     * The message, separated from the two values so it can be asserted without a second JVM.
     *
     * Null when this JVM can load the bundle. What a test pins is not the wording but the three
     * things an operator has to be told and cannot get anywhere else: the version found, the
     * version needed, and what to change.
     */
    internal fun jvmPreflightFailure(required: Int, running: Int, javaHome: String?): String? =
      if (running >= required) null
      else
        "the packaged UI-builder renderer needs Java $required or newer, but this server is " +
          "running Java $running from $javaHome — point JAVA_HOME at a Java $required JDK and " +
          "restart, or pass --ui-builder-state-dir none to run without the UI builder"

    internal fun forHost(host: ServeRenderHost): ServeUiBuilderRenderPort =
      ServeUiBuilderRenderPort(host)
  }
}
