package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.UiBuilderServiceLimits
import ee.schimke.composeai.uibuilder.service.UiBuilderStoreLimits

/**
 * The UI-builder service's retention and the per-design store's byte budget, chosen together so the
 * first always fits inside the second (yschimke/watchface-tools#45).
 *
 * The released defaults do not: the service keeps at least `minimumRetainedRevisionSnapshots` (32)
 * whole copies of a design however large it is, while the store refuses a design past
 * `maximumDesignBytes` (64 MiB). A watch face of about 3,900 nodes is about 1.8 MB canonical, so
 * past revision 28 or so every write to it, a catalog upgrade included, failed in the store, and
 * the failure surfaced as a retryable `internal` error that no retry could fix.
 *
 * Here the floor is [MINIMUM_RETAINED_REVISIONS] copies and the budget is [MAXIMUM_DESIGN_BYTES],
 * so even a document at the service's own `maximumSerializedDocumentBytes` ceiling fits with its
 * retained revisions, the current document and room for the journal. The next commit to an
 * oversized design prunes its retained revisions to the new floor, so it heals on its first write.
 */
internal object ServeUiBuilderStorageLimits {
  /** Undo and rebase depth for a large design; small designs still keep up to 128 by bytes. */
  const val MINIMUM_RETAINED_REVISIONS = 8

  const val MAXIMUM_DESIGN_BYTES: Long = 160L * 1_024 * 1_024

  val service: UiBuilderServiceLimits =
    UiBuilderServiceLimits(minimumRetainedRevisionSnapshots = MINIMUM_RETAINED_REVISIONS)

  val store: UiBuilderStoreLimits = UiBuilderStoreLimits(maximumDesignBytes = MAXIMUM_DESIGN_BYTES)

  /**
   * The worst case [service] can ask [store] to hold: the current document, every retained copy,
   * and the same again in slack for position maps and the journal before it is compacted.
   */
  fun worstCaseDesignBytes(service: UiBuilderServiceLimits = this.service): Long =
    2L * (service.minimumRetainedRevisionSnapshots + 1) * service.maximumSerializedDocumentBytes
}
