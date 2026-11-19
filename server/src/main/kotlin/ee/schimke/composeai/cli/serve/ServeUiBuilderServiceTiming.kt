package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse

/**
 * [delegate], with every call slower than [thresholdMillis] logged by request type and design id.
 *
 * The design service serialises every call behind one lock, and a write there (any commit,
 * including the audit record each export appends) fsyncs the store while holding it. When reads
 * queue for a minute, the slow call that holds the lock is logged here too, by type, so the log
 * says what held it rather than only that everything waited.
 */
internal class ServeUiBuilderServiceTiming(
  private val delegate: UiBuilderServicePort,
  private val thresholdMillis: Long = SLOW_REQUEST_MILLIS,
  private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
  private val log: (String) -> Unit = System.err::println,
) : UiBuilderServicePort by delegate {
  /** When a call last took [thresholdMillis] or more, on [clock]; null until one does. */
  @Volatile private var lastSlowAt: Long? = null

  /**
   * Whether a call was slow within the last [windowMillis]: the service's lock is contended, so
   * optional work that would take it (a thumbnail redraw is an export, and every export commits)
   * should wait.
   */
  fun slowRecently(windowMillis: Long = SLOW_WINDOW_MILLIS): Boolean =
    lastSlowAt?.let { clock() - it < windowMillis } == true

  override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
    val started = clock()
    val response = delegate.execute(call)
    val ended = clock()
    val millis = ended - started
    if (millis >= thresholdMillis) {
      lastSlowAt = ended
      val request = call.request
      val error = (response as? UiBuilderServiceResponse.Error)?.error?.code
      log(
        "serve: ui-builder ${request::class.simpleName}" +
          (designIdOf(request)?.let { " $it" } ?: "") +
          " took $millis ms" +
          (error?.let { " (error $it)" } ?: ""),
      )
    }
    return response
  }

  companion object {
    const val SLOW_WINDOW_MILLIS = 60_000L
  }

  private fun designIdOf(request: UiBuilderServiceRequest): String? =
    when (request) {
      is UiBuilderServiceRequest.OpenDesign -> request.designId
      is UiBuilderServiceRequest.GetSnapshot -> request.designId
      is UiBuilderServiceRequest.GetDelta -> request.designId
      is UiBuilderServiceRequest.ApplyOperation -> request.submission.designId
      is UiBuilderServiceRequest.ExportDesign -> request.designId
      is UiBuilderServiceRequest.ReplaceDesignDocument -> request.designId
      is UiBuilderServiceRequest.RenameDesign -> request.designId
      is UiBuilderServiceRequest.UpdatePresence -> request.designId
      is UiBuilderServiceRequest.UpdateDesignAccess -> request.designId
      is UiBuilderServiceRequest.RestoreRevision -> request.designId
      is UiBuilderServiceRequest.DeleteDesign -> request.designId
      is UiBuilderServiceRequest.CreateDesign -> request.document.id
      else -> null
    }
}
