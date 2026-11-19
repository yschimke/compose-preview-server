package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.watchface.packager.WffEstimate
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What a watch-face design costs on a watch, from the packager's SDK-free estimate (`WffEstimate`):
 * one XML parse and image headers, no packager JVM and no Android SDK, so it is cheap enough to
 * return after every edit an agent makes. Google's validators are `ui_builder_validate_watch_face`;
 * this is the running total an agent optimises against between them.
 */
@Serializable
internal data class WatchFaceCostSummaryV1(
  val mode: String = "estimate",
  val revision: Long,
  /** Whether the estimate found no error: every drawn bitmap present, both modes within limits. */
  val passed: Boolean,
  val active: WatchFaceBudgetV1? = null,
  val ambient: WatchFaceBudgetV1? = null,
  /** The resources that cost the most, most expensive first; at most [MAX_CONTRIBUTORS]. */
  val topContributors: List<WatchFaceCostContributorV1> = emptyList(),
  /** The estimate's errors, each with what to do; at most [MAX_ERRORS]. */
  val errors: List<WatchFaceCostFindingV1> = emptyList(),
  val note: String = NOTE,
) {
  companion object {
    const val MAX_CONTRIBUTORS = 3
    const val MAX_ERRORS = 5
    const val NOTE =
      "An estimate from the face's XML and its images' sizes. Keep both modes under their limits; " +
        "run ui_builder_validate_watch_face for Google's own checks before publishing."
  }
}

@Serializable
internal data class WatchFaceBudgetV1(
  val usedBytes: Long,
  val limitBytes: Long,
  val percentOfLimit: Int,
)

@Serializable
internal data class WatchFaceCostContributorV1(
  val resource: String,
  val activeBytes: Long? = null,
  val ambientBytes: Long? = null,
  val percentOfActiveLimit: Int? = null,
  val percentOfAmbientLimit: Int? = null,
  val width: Int? = null,
  val height: Int? = null,
  val renderedMaxWidth: Int? = null,
  val renderedMaxHeight: Int? = null,
  val missing: Boolean = false,
  val suggestion: String? = null,
)

@Serializable
internal data class WatchFaceCostFindingV1(val message: String, val suggestion: String? = null)

/**
 * [summary] of a design's current face, cached by [DwfSource.digest] so an edit that does not
 * change the face (a rename, a comment) costs a digest. [estimate] is the packager's estimate as
 * its v1 JSON; a function so tests can stand in for it.
 */
class WatchFaceCostEstimator
internal constructor(
  private val estimate: (Map<String, ByteArray>) -> String = { WffEstimate.report(it).toJson() },
) {
  private val lock = Any()
  private val cache =
    object : LinkedHashMap<String, WatchFacePackagerReportV1>(16, 0.75f, true) {
      override fun removeEldestEntry(
        eldest: MutableMap.MutableEntry<String, WatchFacePackagerReportV1>?,
      ): Boolean = size > MAX_CACHED
    }

  /**
   * The cost of [document]'s face as [dwf] exports and packages it (its pinned drawables included),
   * or null when it exports no face or the estimate cannot read it. Never throws.
   */
  internal fun summary(
    dwf: UiBuilderDwfPreview,
    document: DesignDocumentV1,
  ): WatchFaceCostSummaryV1? =
    try {
      val exported = dwf.sourceFor(document)
      if (exported == null) null
      else {
        val source = packagedWithPinnedDrawables(document, exported, emptyMap(), dwf.assets).source
        report(source)?.let { summarize(document.revision, it) }
      }
    } catch (failed: DwfPackagingFailed) {
      null
    } catch (_: SerializationException) {
      null
    } catch (_: IllegalArgumentException) {
      null
    } catch (_: IllegalStateException) {
      null
    }

  private fun report(source: DwfSource): WatchFacePackagerReportV1? {
    if (source.problem() != null) return null
    val digest = source.digest()
    synchronized(lock) { cache[digest] }
      ?.let {
        return it
      }
    val report =
      REPORT_JSON.decodeFromString(
        WatchFacePackagerReportV1.serializer(),
        estimate(DwfPackaging.bundleFiles(source)),
      )
    synchronized(lock) { cache[digest] = report }
    return report
  }

  companion object {
    private const val MAX_CACHED = 256
    private val REPORT_JSON = Json { ignoreUnknownKeys = true }

    internal fun summarize(
      revision: Long,
      report: WatchFacePackagerReportV1,
    ): WatchFaceCostSummaryV1 {
      val memoryCheck = report.checks.firstOrNull { it.memory != null || it.contributors != null }
      val memory = memoryCheck?.memory
      fun percent(used: Long, limit: Long): Int =
        if (limit <= 0) 0 else ((used * 100) / limit).toInt()
      return WatchFaceCostSummaryV1(
        revision = revision,
        passed = report.checks.none { check -> check.findings.any { it.severity == "error" } },
        active =
          memory?.let {
            WatchFaceBudgetV1(
              it.maxActiveBytes,
              it.activeLimitBytes,
              percent(it.maxActiveBytes, it.activeLimitBytes),
            )
          },
        ambient =
          memory?.let {
            WatchFaceBudgetV1(
              it.maxAmbientBytes,
              it.ambientLimitBytes,
              percent(it.maxAmbientBytes, it.ambientLimitBytes),
            )
          },
        topContributors =
          memoryCheck?.contributors.orEmpty().take(WatchFaceCostSummaryV1.MAX_CONTRIBUTORS).map { c
            ->
            WatchFaceCostContributorV1(
              resource = c.resource,
              activeBytes = c.activeBytes,
              ambientBytes = c.ambientBytes,
              percentOfActiveLimit =
                memory?.let { m -> c.activeBytes?.let { percent(it, m.activeLimitBytes) } },
              percentOfAmbientLimit =
                memory?.let { m -> c.ambientBytes?.let { percent(it, m.ambientLimitBytes) } },
              width = c.width,
              height = c.height,
              renderedMaxWidth = c.renderedMaxWidth,
              renderedMaxHeight = c.renderedMaxHeight,
              missing = c.missing,
              suggestion = c.suggestion,
            )
          },
        errors =
          report.checks
            .flatMap { it.findings }
            .filter { it.severity == "error" }
            .take(WatchFaceCostSummaryV1.MAX_ERRORS)
            .map { WatchFaceCostFindingV1(it.message, it.suggestion) },
      )
    }
  }
}
