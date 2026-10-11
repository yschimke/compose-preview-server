package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.SystemFontLookups
import ee.schimke.composeai.uibuilder.export.TypefaceTarget
import ee.schimke.composeai.uibuilder.export.VariableFontExportMode
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * A design rendered by real Compose, on the host, instead of by the browser's Wasm renderer.
 *
 * ## Why the builder needs a second renderer at all
 *
 * The editor's canvas is Compose Multiplatform for Wasm, which is the right default: it is
 * immediate, it costs the server nothing, and it draws the same Material 3 components. What it
 * cannot do is answer "what does this look like on **Android**" — Robolectric-backed Android
 * rendering, platform text metrics, the device frames the render lane knows — and that question is
 * the one a designer eventually has to ask before shipping a screen.
 *
 * ## Everything here already existed and nothing called it
 *
 * [UiBuilderGeneratedPreviewAdapter] has wrapped generated Compose in a deterministic `@Preview`
 * and submitted it to [PlaygroundCompileService] since it was written, and its test proves the
 * result "compiles, discovers and enters first-frame render". No route, tool or service ever
 * invoked it. This class is the caller: design → generated Kotlin → the existing compile and render
 * lane, whose response already carries the first frame and the token the live `/ws/{name}` stream
 * is opened with.
 *
 * ## Why the nodes are tagged
 *
 * A streamed frame is a picture. A picture is not an editor — you cannot select the card you meant
 * or see which rectangle a node draws. So the generated source for this lane, and **only** for this
 * lane, carries `Modifier.testTag("<nodeId>")` on every node: the server's existing annotation lane
 * reports authored test tags with their bounds in render pixels, so the same frame comes back with
 * a map from design node id to rectangle. That is what makes overlays and clickable regions
 * possible over an image the browser did not draw. An export artifact is left untagged, because a
 * test tag is not something a designer asked for in source they keep.
 *
 * ## Why this is not a public arbitrary-Kotlin endpoint
 *
 * The adapter's own KDoc requires its caller to authorize the design before setting
 * `isSecurityChecked`. Two things make that true here. The caller reaches this class only after the
 * `ui-builder-export` capability check, so the design is one the actor may already export as
 * source. And the source is not the actor's: `ScreenGenerator` emits it from the component record,
 * refusing any callable outside [ScreenExportGate.EXPRESSION_PACKAGES] — a document naming
 * `java.nio.file.Files.readString` is a refusal, not a compile. The playground lane's own sandbox
 * still applies underneath.
 */
internal class ServeUiBuilderNativePreview(
  private val executor: ScreenGeneratorComposeExportExecutor,
  /**
   * The compile lane as a function. Production passes `{ adapter.compile(it, isSecurityChecked =
   * true) }`: the `true` belongs at the call site that knows the export capability was checked and
   * the source is generated. Also testable without a compiler or bundle.
   */
  private val compile: (UiBuilderGeneratedCompose) -> PlaygroundRunResponse,
  /**
   * Where each tagged node drew on the compiled frame, keyed by design node id. A separate seam
   * because `PlaygroundRunResponse` is published from `render-host`. Defaults to no bounds; the
   * overlay is an addition, never a precondition.
   */
  private val captureNodeBounds: (PlaygroundRunResponse) -> Map<String, AnnotationBounds> = {
    emptyMap()
  },
  /**
   * Where a design's catalog is compiled and rendered, or null. Builder ids and served ids are
   * different namespaces (a `wear-m3` design needs a bundle carrying Wear Material 3, served under
   * whatever id), and the bundle decides the daemon, hence a pair. Defaults to identity on the
   * desktop daemon.
   */
  private val nativeTarget: (String) -> UiBuilderNativeTarget? = {
    UiBuilderNativeTarget(catalog = it, confType = UiBuilderGeneratedCompose.COMPOSE_CMP)
  },
  /**
   * Component packs this host admits (same set as the export executor). A design using a pack
   * compiles against the pack's bundle, which carries both the pack and Material 3; two packs have
   * no common bundle and are refused with [MIXED_PACKS].
   */
  private val packs: Set<String> = emptySet(),
  /**
   * Which player draws a Wear widget's recorded document (`--ui-builder-widget-player`); the
   * recording is AndroidX's either way. See [UiBuilderWidgetPlayer].
   */
  private val widgetPlayer: UiBuilderWidgetPlayer = UiBuilderWidgetPlayer.DEFAULT,
  /**
   * Whether a served bundle carries the CMP player (`rc-player-compose`), by served id; without it
   * widgets use [UiBuilderWidgetPlayer.ANDROIDX]. Defaults to yes.
   */
  private val carriesCmpWidgetPlayer: (catalog: String) -> Boolean = { true },
) : UiBuilderNativePreviewLane {

  override fun render(
    document: DesignDocumentV1,
    widgetHostShape: WearWidgetHostShape,
  ): UiBuilderNativePreviewOutcome {
    // Checked before generation: two packs have no bundle regardless of the Kotlin, and that
    // design-level sentence shouldn't be pre-empted by a missing record.
    val usedPacks = ScreenGeneratorComposeExportExecutor.packsUsedBy(document, packs)
    if (usedPacks.size > 1) {
      return UiBuilderNativePreviewOutcome.Refused(
        MIXED_PACKS,
        listOf(
          "this design uses components from ${usedPacks.size} packs " +
            "(${usedPacks.joinToString(", ") { "`$it`" }}), and no served bundle carries all of " +
            "them; a native render can draw a design against one pack's bundle at a time"
        ),
      )
    }
    // The design's own catalog (or its single pack), never a default: compiling against another
    // catalog would type-check a different screen. Resolved once, since typefaces are generated for
    // this bundle's kind and routing may change under a refresh. A missing target is refused after
    // generation so design errors are reported first.
    val catalogSystemId = usedPacks.singleOrNull() ?: document.catalogPin.systemId
    val resolvedTarget = nativeTarget(catalogSystemId)
    val typefaces =
      if (resolvedTarget?.confType == UiBuilderGeneratedCompose.COMPOSE_CMP) TypefaceTarget.DESKTOP
      else TypefaceTarget.DEFAULT
    val generated =
      when (
        val outcome =
          executor.generate(
            document,
            tagNodes = true,
            widgetHostShape = widgetHostShape,
            typefaces = typefaces,
            // The bundle this compiles against carries Compose, not flexpress.
            variableFontMode = VariableFontExportMode.STANDALONE,
          )
      ) {
        is ScreenGeneratorComposeExportExecutor.Generated.Emitted -> outcome
        is ScreenGeneratorComposeExportExecutor.Generated.Refused ->
          return UiBuilderNativePreviewOutcome.Refused(outcome.code, outcome.reasons)
      }
    // Refused by name before compiling: a missing bundle is host configuration, and a compiler
    // error about Wear imports would read as a design bug.
    val target =
      resolvedTarget
        ?: return UiBuilderNativePreviewOutcome.Refused(
          NO_NATIVE_CATALOG,
          listOf(
            "this host compiles no bundle for catalog `$catalogSystemId`, so there is nothing to " +
              "render this design against; serve that catalog's bundle and map it with " +
              "`--ui-builder-native-catalog $catalogSystemId=<served catalog>`"
          ),
        )
    val environment = document.environment
    if (generated.remoteContent && target.confType != UiBuilderGeneratedCompose.COMPOSE_ANDROID) {
      return UiBuilderNativePreviewOutcome.Refused(
        NO_NATIVE_CATALOG,
        listOf("Remote content requires an Android capture/player bundle for `$catalogSystemId`"),
      )
    }
    // A widget renders at its container frame (content box plus authored padding), not the design
    // environment's screen size.
    val widget = generated.widgetFrame
    val player =
      if (
        widget != null &&
          widgetPlayer == UiBuilderWidgetPlayer.CMP &&
          !carriesCmpWidgetPlayer(target.catalog)
      )
        UiBuilderWidgetPlayer.ANDROIDX
      else widgetPlayer
    val response =
      compile(
        UiBuilderGeneratedCompose(
          source = generated.source,
          composableName = generated.screenName,
          catalog = target.catalog,
          // The document's own frame, so the streamed render and the browser canvas are the same
          // size and a bounds rectangle means the same thing in both.
          widthDp = widget?.widthDp ?: environment.widthDp,
          heightDp = widget?.heightDp ?: environment.heightDp,
          material3Theme =
            environment.theme.name.lowercase().takeIf {
              document.catalogPin.systemId == "m3-catalog" && it in setOf("light", "dark")
            },
          confType = target.confType,
          wearWidget = widget != null,
          remoteCapture = generated.remoteContent,
          widgetPlayer = player,
        )
      )
    // Bounds only for a rendered frame, and not for widgets or Remote content, whose nodes carry no
    // test tags; avoids a pointless second daemon session.
    val bounds =
      if (response.image == null || widget != null || generated.remoteContent) emptyMap()
      else captureNodeBounds(response)
    // The tag set is reported, not inferred by clients, since it keys the bounds lookup.
    return UiBuilderNativePreviewOutcome.Rendered(
      response,
      // Recorded Remote content (including widgets) has no tags; claiming them would make the
      // overlay look broken.
      if (widget != null || generated.remoteContent) emptyList() else document.nodes.keys.sorted(),
      bounds,
      failure = if (response.image == null) response.noFrameReason() else null,
      warnings = generated.systemFontFamilies.map(SystemFontLookups::note),
    )
  }

  internal companion object {
    /**
     * The design is fine but this host can't build it: no served bundle is mapped to its catalog.
     * Distinct from generator codes, which point at the design; this points at host configuration.
     */
    const val NO_NATIVE_CATALOG = "NO_NATIVE_CATALOG"

    /**
     * The design uses more than one component pack and no bundle links them all; a design problem,
     * unlike [NO_NATIVE_CATALOG].
     */
    const val MIXED_PACKS = "MIXED_PACKS"
  }
}

/**
 * Which served bundle, on which daemon, a builder catalog's designs render against. Together
 * because a bundle's dependencies decide its backend, so the Skiko daemon is never asked to load an
 * Android AAR.
 */
data class UiBuilderNativeTarget(val catalog: String, val confType: String)

/**
 * Why this response has no frame, in one actionable sentence. Compile errors arrive as ERROR
 * [diagnostics] with a null exception (the playground draws them inline), so every field is read,
 * most specific first, falling back to naming the renderer.
 */
internal fun PlaygroundRunResponse.noFrameReason(): String {
  exception
    ?.takeIf { it.isNotBlank() }
    ?.let {
      return it
    }
  val errors = diagnostics.filter { it.severity == PlaygroundSeverity.ERROR }
  if (errors.isNotEmpty()) {
    val shown = errors.take(MAX_REPORTED_DIAGNOSTICS).joinToString("\n") { it.render() }
    val hidden = errors.size - MAX_REPORTED_DIAGNOSTICS
    return if (hidden > 0) "$shown\n… and $hidden more" else shown
  }
  // Compiled and discovered, but the render seam returned nothing (no sidecar, or a swallowed
  // render): point at the host, not the design.
  return "the design compiled, but this host's renderer produced no frame for it"
}

/**
 * One diagnostic as text, anchored where it has a position. Lines and columns are 0-based
 * (CodeMirror) and shifted to 1-based here only.
 */
private fun PlaygroundDiagnostic.render(): String {
  val anchor =
    when {
      file == null -> null
      line == null -> file
      ch == null -> "$file:${line!! + 1}"
      else -> "$file:${line!! + 1}:${ch!! + 1}"
    }
  return if (anchor == null) message else "$anchor: $message"
}

/** Enough to see the shape of a broken compile, short of pasting a whole build log into a pane. */
private const val MAX_REPORTED_DIAGNOSTICS = 5

/**
 * The seam `ServeHttpServer` takes, keeping the lane internal (a public constructor can't name
 * internal types).
 */
fun interface UiBuilderNativePreviewLane {
  /**
   * @param widgetHostShape which host container frames a Wear widget design; ignored otherwise. A
   * `fun interface` method can't have defaults; see the overload below.
   */
  fun render(
    document: DesignDocumentV1,
    widgetHostShape: WearWidgetHostShape,
  ): UiBuilderNativePreviewOutcome
}

/**
 * Render in the editor's default frame (the squircle), for callers with no shape preference (MCP
 * tool, local runner).
 */
fun UiBuilderNativePreviewLane.render(document: DesignDocumentV1): UiBuilderNativePreviewOutcome =
  render(document, WearWidgetHostShape.Default)

sealed interface UiBuilderNativePreviewOutcome {
  /**
   * The compile lane's answer with first frame and stream token. [nodeBounds] maps node ids to
   * boxes in the frame's render pixels (the [imageBase64][NativePreviewResultV1.imageBase64]
   * space); unplaced nodes have no entry.
   */
  data class Rendered(
    val response: PlaygroundRunResponse,
    val taggedNodeIds: List<String>,
    val nodeBounds: Map<String, AnnotationBounds> = emptyMap(),
    /**
     * Why there is no frame, or null; derived here ([noFrameReason]) so HTTP and MCP report the
     * same reason.
     */
    val failure: String? = null,
    /**
     * How the frame differs from the design though it rendered, e.g. typefaces drawn as
     * `SystemFont` lookups (`TYPEFACE_SYSTEM_FONT_LOOKUP`) in the default face.
     */
    val warnings: List<String> = emptyList(),
  ) : UiBuilderNativePreviewOutcome

  /** The generator's own reasons, unchanged. */
  data class Refused(val code: String, val reasons: List<String>) : UiBuilderNativePreviewOutcome
}
