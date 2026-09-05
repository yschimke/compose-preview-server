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
) : UiBuilderNativePreviewLane {

  override fun render(document: DesignDocumentV1): UiBuilderNativePreviewOutcome {
    val generated =
      when (val outcome = executor.generate(document, tagNodes = true)) {
        is ScreenGeneratorComposeExportExecutor.Generated.Emitted -> outcome
        is ScreenGeneratorComposeExportExecutor.Generated.Refused ->
          return UiBuilderNativePreviewOutcome.Refused(outcome.code, outcome.reasons)
      }
    val environment = document.environment
    val response =
      compile(
        UiBuilderGeneratedCompose(
          source = generated.source,
          composableName = generated.screenName,
          // The design's own catalog, not a default. A design pinned to one catalog and compiled
          // against another is a screen made of different components that happens to type-check.
          catalog = document.catalogPin.systemId,
          // The document's own frame, so the streamed render and the browser canvas are the same
          // size and a bounds rectangle means the same thing in both.
          widthDp = environment.widthDp,
          heightDp = environment.heightDp,
        )
      )
    // Reported rather than inferred by the caller: the tag set is what a bounds lookup is keyed by,
    // and a client that recomputed it from the document would drift the moment the projection
    // stopped tagging something.
    return UiBuilderNativePreviewOutcome.Rendered(response, document.nodes.keys.sorted())
  }
}

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
  /** The compile lane's answer, first frame and stream token included. */
  data class Rendered(val response: PlaygroundRunResponse, val taggedNodeIds: List<String>) :
    UiBuilderNativePreviewOutcome

  /** The generator's own reasons, unchanged. */
  data class Refused(val code: String, val reasons: List<String>) : UiBuilderNativePreviewOutcome
}
