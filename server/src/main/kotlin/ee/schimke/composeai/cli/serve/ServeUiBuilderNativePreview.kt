package ee.schimke.composeai.cli.serve

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
   * The compile lane, as a function rather than the adapter itself.
   *
   * Production passes `{ adapter.compile(it, isSecurityChecked = true) }`, and the `true` belongs
   * at that call site rather than in here: it is the wiring that knows the `ui-builder-export`
   * capability was checked and that the source came from the generator. A seam also means this
   * class can be tested without standing up a Kotlin compiler and a catalog bundle, which is the
   * difference between testing the lane and not testing it.
   */
  private val compile: (UiBuilderGeneratedCompose) -> PlaygroundRunResponse,
  /**
   * Where each tagged node drew on the frame the compile lane produced, keyed by design node id.
   *
   * A second seam beside [compile] rather than a field on its response: `PlaygroundRunResponse` is
   * published from compose-ai-tools' `render-host` and cannot gain one from this repository. It
   * does not need to — the bounds are a second capture, and this class owns the payload they ride
   * on. Defaults to no bounds, which is what a host with no semantics-capable backend has and what
   * every test that only cares about the frame wants: the overlay is an addition to the picture,
   * never a precondition for it.
   */
  private val captureNodeBounds: (PlaygroundRunResponse) -> Map<String, AnnotationBounds> = {
    emptyMap()
  },
  /**
   * Where a design's catalog is compiled and rendered, or null when this host cannot.
   *
   * A UI-builder catalog id and a served-catalog id are not the same namespace and only looked like
   * it while `m3-catalog` was the only catalog with a native lane: a `wear-m3` design has to be
   * built against a bundle that carries `androidx.wear.compose:compose-material3`, which the host
   * serves under whatever `--catalogs` id its operator gave it. The daemon comes with it — Wear
   * Compose is an Android AAR, so that bundle is a `compose-android` one — which is why this
   * returns the pair rather than a string.
   *
   * Defaults to the identity mapping on the desktop daemon, which is exactly what this lane did
   * before there was anything else to do.
   */
  private val nativeTarget: (String) -> UiBuilderNativeTarget? = {
    UiBuilderNativeTarget(catalog = it, confType = UiBuilderGeneratedCompose.COMPOSE_CMP)
  },
) : UiBuilderNativePreviewLane {

  override fun render(document: DesignDocumentV1): UiBuilderNativePreviewOutcome {
    val generated =
      when (val outcome = executor.generate(document, tagNodes = true)) {
        is ScreenGeneratorComposeExportExecutor.Generated.Emitted -> outcome
        is ScreenGeneratorComposeExportExecutor.Generated.Refused ->
          return UiBuilderNativePreviewOutcome.Refused(outcome.code, outcome.reasons)
      }
    // The design's own catalog, not a default. A design pinned to one catalog and compiled against
    // another is a screen made of different components that happens to type-check. Refused before
    // the compile rather than after, and by name: "this host has no bundle for `wear-m3`" is an
    // operator's line of configuration, where a compiler error about an unresolved
    // `androidx.wear.compose.material3.ScreenScaffold` reads like a bug in the design.
    val catalogSystemId = document.catalogPin.systemId
    val target =
      nativeTarget(catalogSystemId)
        ?: return UiBuilderNativePreviewOutcome.Refused(
          NO_NATIVE_CATALOG,
          listOf(
            "this host compiles no bundle for catalog `$catalogSystemId`, so there is nothing to " +
              "render this design against; serve that catalog's bundle and map it with " +
              "`--ui-builder-native-catalog $catalogSystemId=<served catalog>`"
          ),
        )
    val environment = document.environment
    val response =
      compile(
        UiBuilderGeneratedCompose(
          source = generated.source,
          composableName = generated.screenName,
          catalog = target.catalog,
          // The document's own frame, so the streamed render and the browser canvas are the same
          // size and a bounds rectangle means the same thing in both.
          widthDp = environment.widthDp,
          heightDp = environment.heightDp,
          confType = target.confType,
        )
      )
    // Only asked for a frame: a compile that failed has no render to read bounds off, and asking
    // anyway would stand up a second daemon session to answer nothing.
    val bounds = if (response.image == null) emptyMap() else captureNodeBounds(response)
    // The tag set is reported rather than inferred by the caller: it is what a bounds lookup is
    // keyed by, and a client that recomputed it from the document would drift the moment the
    // projection stopped tagging something.
    return UiBuilderNativePreviewOutcome.Rendered(
      response,
      document.nodes.keys.sorted(),
      bounds,
      failure = if (response.image == null) response.noFrameReason() else null,
    )
  }

  internal companion object {
    /**
     * The design is fine and this host cannot build it: no served bundle is mapped to its catalog.
     *
     * Distinct from the generator's own codes on purpose. `UNPROVEN_CALL_SITE` and
     * `RECORD_FREE_DESIGN` are things to change about the *design*; this one is a thing to change
     * about the *host*, and telling a designer to edit their screen because an operator has not
     * served a Wear bundle is the wrong half of the system to send them to.
     */
    const val NO_NATIVE_CATALOG = "NO_NATIVE_CATALOG"
  }
}

/**
 * Which served bundle, on which daemon, a UI-builder catalog's designs are rendered against.
 *
 * Two fields rather than one because they are not independently choosable: a bundle's dependency
 * set decides its backend, so naming a bundle names a daemon. Carrying them together is what stops
 * a host from asking the Skiko daemon to load an Android AAR and reporting the resulting compile
 * failure as the design's fault.
 */
data class UiBuilderNativeTarget(val catalog: String, val confType: String)

/**
 * Why this response carries no frame, in one sentence a designer can act on.
 *
 * The compile lane reports a failure in whichever field fits it, and only one of those is an
 * `exception`: a snippet that does not compile comes back with ERROR [diagnostics] and a null
 * exception, because the playground frontend draws those as inline squiggles rather than as a
 * message. The UI builder has no such editor to squiggle — it sent a design, not source — so a
 * caller that forwarded the exception alone reported "compiled, no frame" for a compile that
 * failed, which is the one reading that is never true. Read every field, in the order that puts the
 * most specific cause first, and fall back to naming the renderer rather than to silence.
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
  // Compiled, a @Preview was discovered (that failure sets `exception`), and the render seam still
  // came back empty: no sidecar for this mode, or a render this host swallowed. Say which half of
  // the lane it was, so the next question is asked of the host rather than of the design.
  return "the design compiled, but this host's renderer produced no frame for it"
}

/**
 * One diagnostic as text: the compiler's own message, anchored where it has a position.
 *
 * [PlaygroundDiagnostic.line]/[PlaygroundDiagnostic.ch] are 0-based, because CodeMirror is; a human
 * counts from one, so they are shifted here and nowhere else.
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
 * The seam `ServeHttpServer` takes, so the lane itself can stay internal.
 *
 * A public constructor parameter cannot name an internal type, and widening
 * [ScreenGeneratorComposeExportExecutor] and `ComponentRecordSource` to satisfy that would export
 * three implementation types to make one wiring possible.
 */
fun interface UiBuilderNativePreviewLane {
  fun render(document: DesignDocumentV1): UiBuilderNativePreviewOutcome
}

sealed interface UiBuilderNativePreviewOutcome {
  /**
   * The compile lane's answer, first frame and stream token included.
   *
   * [nodeBounds] maps a design node id to the box it drew, in the frame's own render pixels — the
   * space [imageBase64][NativePreviewResultV1.imageBase64] is in, so a client scales both by the
   * one factor it already computes to fit the image. A node the render never placed simply has no
   * entry, which is the honest outcome and the one `UiBuilderInspectionSnapshot` already gives a
   * lazy slot that never composed.
   */
  data class Rendered(
    val response: PlaygroundRunResponse,
    val taggedNodeIds: List<String>,
    val nodeBounds: Map<String, AnnotationBounds> = emptyMap(),
    /**
     * Why there is no frame, or null when there is one.
     *
     * Derived here rather than at each caller so the HTTP route and the MCP tool cannot report a
     * different reason for the same response — and so neither has to know that the compile lane
     * spreads its failures across `exception` and `diagnostics`. See [noFrameReason].
     */
    val failure: String? = null,
  ) : UiBuilderNativePreviewOutcome

  /** The generator's own reasons, unchanged. */
  data class Refused(val code: String, val reasons: List<String>) : UiBuilderNativePreviewOutcome
}
