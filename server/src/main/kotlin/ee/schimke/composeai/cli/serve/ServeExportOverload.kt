package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import java.util.concurrent.Semaphore

/**
 * Compatibility with the released runtime's bounded export pool. Both admission refusals are
 * retryable BAD_REQUESTs in builder 3.121.0. Match those exact refusals, not arbitrary retryable
 * errors: a timeout, broken renderer or invalid document must keep its own failure semantics. The
 * render port's explicit Busy outcome is also wrapped as INTERNAL by that runtime; its exact
 * adapter-owned message is the only internal failure treated as admission here.
 */
internal fun UiBuilderServiceResponse.exportOverloadReason(): String? {
  val error = (this as? UiBuilderServiceResponse.Error)?.error ?: return null
  return error.message.takeIf {
    (error.code == ServiceErrorCodeV1.BAD_REQUEST &&
      error.retryable &&
      it in setOf("concurrent export limit reached", "concurrent export worker limit reached")) ||
      (error.code == ServiceErrorCodeV1.INTERNAL &&
        it == "export failed: UI-builder renderer is busy")
  }
}

/** The released wire enum has no overload member; this is a retryable server-side failure. */
internal fun UiBuilderServiceResponse.exportTransportResponse(): UiBuilderServiceResponse =
  if (exportOverloadReason() != null) {
    val failure = this as UiBuilderServiceResponse.Error
    failure.copy(error = failure.error.copy(code = ServiceErrorCodeV1.INTERNAL, retryable = true))
  } else this

internal const val EXPORT_RETRY_AFTER_SECONDS = 1

internal fun ApplicationCall.exportRetryAfter(outcome: UiBuilderServiceResponse) {
  if (outcome.exportOverloadReason() != null) {
    response.headers.append(HttpHeaders.RetryAfter, EXPORT_RETRY_AFTER_SECONDS.toString())
  }
}

/** An execution failure, carried as an MCP tool result rather than a JSON-RPC protocol error. */
internal class ExportOverloadedException(message: String) : RuntimeException(message)

/** One bound shared by REST, MCP and native checks on a server; no waiting HTTP/compile queue. */
internal class BoundedUiBuilderNativePreview(
  private val delegate: UiBuilderNativePreviewLane,
  maximumConcurrent: Int = 4,
) : UiBuilderNativePreviewLane {
  private val permits = Semaphore(maximumConcurrent)

  override fun render(
    document: DesignDocumentV1,
    widgetHostShape: WearWidgetHostShape,
  ): UiBuilderNativePreviewOutcome {
    if (!permits.tryAcquire())
      throw ExportOverloadedException("concurrent native render limit reached")
    try {
      val outcome = delegate.render(document, widgetHostShape)
      if (outcome is UiBuilderNativePreviewOutcome.Rendered && outcome.response.image == null) {
        val diagnostic = outcome.response.diagnostics.singleOrNull()
        if (
          diagnostic?.severity == PlaygroundSeverity.ERROR &&
            diagnostic.file == null &&
            diagnostic.message.startsWith("the playground is busy compiling")
        ) {
          throw ExportOverloadedException("native compile capacity exhausted")
        }
      }
      return outcome
    } finally {
      permits.release()
    }
  }
}

internal suspend fun ApplicationCall.respondExportOverload(failure: ExportOverloadedException) {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  response.headers.append(HttpHeaders.RetryAfter, EXPORT_RETRY_AFTER_SECONDS.toString())
  respondText(failure.message.orEmpty(), status = HttpStatusCode.ServiceUnavailable)
}
