package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.InlineRemoteContentExporter
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.toUiBuilderDocument

/**
 * One `remote-compose/inline` subtree, captured into the Remote Compose document it describes.
 *
 * ## Why a capture and not a renderer
 *
 * The canvas draws an inline subtree with the ordinary Compose stand-ins `remote-m3` publishes for
 * those ids — a `RemoteColumn` drawn by a `Column` — inside a marked frame. That frame is honest
 * and it is also a strictly weaker guarantee than the neighbouring `remote-compose/document`, which
 * is played for real by `RcComposePlayer` on real bytes. Closing the gap needs **bytes**, and the
 * browser has no Remote Compose writer to make them with: `rc-player-protocol` has `RcWireWriter`
 * and `RcDocumentCodec.encode`, but nothing that turns a layout/text/modifier tree into the
 * operation list they serialize.
 *
 * The Android creation library does have one, and this host can already run it. So the bytes come
 * from where they already come from — a real `captureSingleRemoteDocument` on a real Robolectric
 * daemon — and every surface that already plays a document plays these too.
 *
 * ## The lane is four things that all existed
 *
 * 1. [InlineRemoteContentExporter] writes the subtree's `@RemoteComposable` body. It has since the
 *    inline node existed; what it deliberately does not write is a **call site**, because
 *    `captureSingleRemoteDocument` takes a `RemoteCreationDisplayInfo`, a `RemoteDensity` and a
 *    density behaviour that disagree with one another, and choosing for an application is not a
 *    generator's decision to make.
 * 2. `RemoteOverridablePreview` is that call site, made once, in one place a human reviewed, with
 *    the reasoning written down beside it — `RemoteDensityBehavior.Legacy`, and a generation
 *    density stamped into the header afterwards so the player can scale the dp-typed dimensions
 *    back. This lane names it rather than restating the decision.
 * 3. [PlaygroundCompileService] in `remote-compose` mode compiles a snippet, renders it on the
 *    Android daemon, drains the captured `.rc` off the daemon's `data/remotecompose` product and
 *    publishes it as a `/d/<id>` permalink. That is exactly this capture, minus the design.
 * 4. `remote-compose/document`'s `documentUrl` is already resolved by the editor and played by
 *    `RcComposePlayer`.
 *
 * This class is the join: design → body → snippet → document URL. It adds no compiler, no renderer
 * and no second density opinion.
 *
 * ## Which catalog it compiles against
 *
 * Not the design's. An inline body is written entirely in the Remote Compose vocabulary —
 * `RemoteColumn`, `RemoteText`, `RemoteCustomComponent` — which comes from
 * `remote-creation-compose` and `remote-material3` rather than from whichever Material catalog the
 * screen around it is pinned to. So the capture target is a property of the **host**: whichever
 * served catalog this box can compile Remote Compose against ([captureCatalog]). A host with none
 * refuses by naming that, because it is an operator's fact rather than the designer's.
 */
internal class ServeUiBuilderInlineCapture(
  /**
   * The compile lane, as a function rather than the adapter itself — the same seam
   * [ServeUiBuilderNativePreview] takes, and for the same two reasons: the `isSecurityChecked`
   * `true` belongs at the wiring that knows the capability was checked, and a seam means this class
   * is testable without a Kotlin compiler and a Robolectric daemon.
   */
  private val compile: (UiBuilderGeneratedCompose) -> PlaygroundRunResponse,
  /**
   * The served catalog whose bundle compiles a `@RemoteComposable` body, or null on a host that
   * serves none.
   *
   * A supplier rather than a value because catalogs are fetched in the background after the lane is
   * wired: a value captured at startup would be null for ever on a freshly started host.
   */
  private val captureCatalog: () -> String?,
) : UiBuilderInlineCaptureLane {

  override fun capture(document: DesignDocumentV1, nodeId: String): UiBuilderInlineCaptureOutcome {
    val builderDocument = runCatching {
      document.toUiBuilderDocument()
    }
      .getOrElse { failure ->
        return UiBuilderInlineCaptureOutcome.Refused(
          UNREADABLE_DESIGN,
          listOf(
            "this design could not be read as a builder document" +
              (failure.message?.let { ": $it" } ?: "")
          ),
        )
      }
    // Asked before the catalog, because it is a fact about the document: a node that is not inline
    // remote content has no body to capture on any host, and reporting the host's configuration
    // for it would send a designer to the wrong half of the system.
    val body =
      when (
        val emitted =
          InlineRemoteContentExporter.export(
            builderDocument,
            nodeId,
            packageName = ScreenExportGate.PACKAGE_NAME,
          )
      ) {
        is InlineRemoteContentExporter.Result.Emitted -> emitted
        is InlineRemoteContentExporter.Result.Refused ->
          return UiBuilderInlineCaptureOutcome.Refused(NO_BODY, emitted.reasons)
      }
    val catalog =
      captureCatalog()
        ?: return UiBuilderInlineCaptureOutcome.Refused(
          NO_REMOTE_COMPOSE_CATALOG,
          listOf(
            "this host serves no catalog that compiles Remote Compose, so there is nothing to " +
              "capture `$nodeId` against — serve an Android-backend catalog whose bundle carries " +
              "the Remote Compose creation library, and the canvas plays this content for real"
          ),
        )
    val environment = document.environment
    val response =
      compile(
        UiBuilderGeneratedCompose(
          source = body.source,
          composableName = body.functionName,
          catalog = catalog,
          // The screen's own frame rather than the node's. A body is captured against the space the
          // design gives it, so a `fillMaxWidth` inside it resolves to the width it will be played
          // at; capturing against the node's box would bake the node's current size into a document
          // that then plays at whatever size the design later gives it.
          widthDp = environment.widthDp,
          heightDp = environment.heightDp,
          confType = REMOTE_COMPOSE_CONF_TYPE,
          remoteCapture = true,
        )
      )
    val url = response.documentUrl
    if (url.isNullOrBlank()) {
      // The compile lane spreads its failures across `exception` and `diagnostics` and this lane
      // has no editor to draw squiggles in, so the same reader the native lane uses reports them.
      // Its own last-resort sentence is unreachable here: the RC terminal sets `exception` by name
      // when a snippet compiles and captures nothing, which is the sentence worth forwarding.
      return UiBuilderInlineCaptureOutcome.Refused(NO_DOCUMENT, listOf(response.noFrameReason()))
    }
    return UiBuilderInlineCaptureOutcome.Captured(
      nodeId = nodeId,
      functionName = body.functionName,
      documentUrl = url,
    )
  }

  internal companion object {
    /** [PlaygroundMode.REMOTE_COMPOSE]'s own id, which is what the compile lane resolves. */
    const val REMOTE_COMPOSE_CONF_TYPE = "remote-compose"

    /** The saved document would not read back as a builder document — a host or storage fault. */
    const val UNREADABLE_DESIGN = "UNREADABLE_DESIGN"

    /**
     * The node names nothing this generator can write a body for: not an inline node at all, or an
     * inline node whose subtree [InlineRemoteContentExporter] refuses. Its own reasons are
     * forwarded rather than summarised — each is a sentence about one node.
     */
    const val NO_BODY = "NO_INLINE_BODY"

    /** A thing to change about the **host**, exactly as `NO_NATIVE_CATALOG` is. */
    const val NO_REMOTE_COMPOSE_CATALOG = "NO_REMOTE_COMPOSE_CATALOG"

    /** The body reached a compiler and no document came back. */
    const val NO_DOCUMENT = "NO_REMOTE_COMPOSE_DOCUMENT"
  }
}

/**
 * The seam `ServeHttpServer` takes, so the lane itself can stay internal — the same split
 * [UiBuilderNativePreviewLane] makes, and for the same reason.
 */
fun interface UiBuilderInlineCaptureLane {
  fun capture(document: DesignDocumentV1, nodeId: String): UiBuilderInlineCaptureOutcome
}

sealed interface UiBuilderInlineCaptureOutcome {
  /**
   * @property functionName the `@RemoteComposable` function the body was captured from, reported so
   *   a caller can say which generated body produced these bytes rather than only that some did.
   * @property documentUrl where the captured `.rc` is served — a `/d/<id>` permalink, which is the
   *   same shape a `remote-compose/document` node's `documentUrl` already carries and is resolved
   *   by the same host-side resolver.
   */
  data class Captured(
    val nodeId: String,
    val functionName: String,
    val documentUrl: String,
  ) : UiBuilderInlineCaptureOutcome

  data class Refused(val code: String, val reasons: List<String>) : UiBuilderInlineCaptureOutcome
}
