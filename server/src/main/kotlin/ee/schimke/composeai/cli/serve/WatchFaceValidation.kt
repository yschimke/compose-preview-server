package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * `wff-packager validate`'s report, as the packager writes it to `--report-out`. Decoded with
 * unknown keys ignored, so a field the packager adds (a validation token above all) never reaches a
 * caller unless it is named here.
 */
@Serializable
internal data class WatchFacePackagerReportV1(
  val schemaVersion: Int,
  val wffVersion: Int,
  val minimumWffVersion: Int? = null,
  val passed: Boolean,
  val checks: List<WatchFaceValidationCheckV1> = emptyList(),
  /** `full` (Google's validators) or `estimate` (the packager's SDK-free path). */
  val mode: String? = null,
)

@Serializable
internal data class WatchFaceValidationCheckV1(
  val id: String,
  val title: String,
  /** `passed`, `failed`, or the packager's word for a check it did not run. */
  val status: String,
  val findings: List<WatchFaceValidationFindingV1> = emptyList(),
  val memory: WatchFaceValidationMemoryV1? = null,
  /**
   * On `memory-footprint`: each resource's cost, most expensive first. Present even when Google's
   * evaluator failed (a missing asset, say), from the packager's own measurement.
   */
  val contributors: List<WatchFaceValidationContributorV1>? = null,
  /** The names of the checks the push validator reports as passed. */
  val passedChecks: List<String>? = null,
  /** Whether the validator would issue a Watch Face Push token; the token itself is never sent. */
  val tokenIssued: Boolean? = null,
)

@Serializable
internal data class WatchFaceValidationFindingV1(
  /** `error`, `warning` or `info`. */
  val severity: String,
  val message: String,
  val line: Int? = null,
  val column: Int? = null,
  val path: String? = null,
  /** What to do about it, in words an authoring agent can act on. */
  val suggestion: String? = null,
)

@Serializable
internal data class WatchFaceValidationContributorV1(
  /** `drawable/case_steel`. */
  val resource: String,
  /** `image`, `font` or `other`. */
  val kind: String,
  val width: Int? = null,
  val height: Int? = null,
  /** `png`, `jpeg`, `webp`, `ttf`, `otf` or `unknown`. */
  val format: String? = null,
  val fileBytes: Long? = null,
  val decodedBytes: Long? = null,
  val activeBytes: Long? = null,
  val ambientBytes: Long? = null,
  val renderedMaxWidth: Int? = null,
  val renderedMaxHeight: Int? = null,
  /** The face references this resource and the bundle does not ship it. */
  val missing: Boolean = false,
  val suggestion: String? = null,
)

@Serializable
internal data class WatchFaceValidationMemoryV1(
  val totalBytes: Long,
  val maxActiveBytes: Long,
  val maxAmbientBytes: Long,
  val activeLimitBytes: Long,
  val ambientLimitBytes: Long,
)

/**
 * `POST|GET /api/ui-builder/v1/designs/{id}/watchface-validation`: the packager's report on one
 * revision's face, plus which face it is. A failing face is a report with `passed: false`, not an
 * error.
 */
@Serializable
internal data class WatchFaceValidationReportV1(
  val schemaVersion: Int,
  val wffVersion: Int,
  val minimumWffVersion: Int? = null,
  val passed: Boolean,
  val checks: List<WatchFaceValidationCheckV1>,
  val mode: String? = null,
  val designId: String,
  val revision: Long,
  /** [DwfSource.digest] of the validated face: the same digest is the same report. */
  val digest: String,
  /** When the validator ran, epoch milliseconds; a cached report keeps its first run's time. */
  val ranAtEpochMillis: Long,
)

/** The validator as a function: a WFF source bundle in, the packager's JSON report out. */
internal fun interface WatchFaceValidator {
  /**
   * @throws DwfPackagingFailed when the packager could not produce a report on the bundle, with its
   *   diagnostics.
   * @throws DwfRuntimeException when it did not finish.
   */
  fun validate(files: Map<String, ByteArray>): String
}

/** Whether faces can be validated here, and with what. */
internal sealed interface WatchFaceValidatorAvailability {
  class Ready(val validator: WatchFaceValidator) : WatchFaceValidatorAvailability

  data class Unavailable(val code: String, val reason: String) : WatchFaceValidatorAvailability

  companion object {
    /** The server flag that turns Google's validators on; off, the route is [DISABLED]. */
    const val DISABLED = "watch_face_validation_disabled"

    /**
     * The production answer, re-read per call, like the push packaging's. [enabled] is the
     * deployment's flag: the validators start a packager JVM per face, so a server runs them only
     * when its operator chose to, and the editor otherwise keeps to its in-browser checks.
     */
    fun production(
      home: File,
      enabled: () -> Boolean,
      sdk: () -> DwfAndroidSdk? = { DwfAndroidSdk.locate() },
      libDir: () -> File? = { locateWffPackagerDir() },
    ): () -> WatchFaceValidatorAvailability = available@{
      if (!enabled()) {
        return@available Unavailable(
          DISABLED,
          "this server does not run Google's watch-face validators (its " +
            "`watchFaceValidation` flag is off); the editor's in-browser checks still apply",
        )
      }
      val located = sdk()
      val lib = libDir()
      when {
        located == null ->
          Unavailable(
            DwfPreviewSurface.ANDROID_SDK_MISSING,
            "validating a watch face needs an Android SDK on this server: " +
              DwfAndroidSdk.INSTALL_HINT,
          )
        lib == null ->
          Unavailable(
            DwfPreviewSurface.PACKAGING_UNAVAILABLE,
            "this install has no lib-wff-packager/ (the watch-face packager and its validator); " +
              "it ships in the server distribution",
          )
        else -> Ready(WatchFaceSubprocessValidator(lib, located, home))
      }
    }
  }
}

/**
 * `wff-packager validate --bundle <dir> --report-out <file>` in its own JVM ([WffPackagerProcess]).
 * It signs nothing, so the child gets no signing environment. A non-zero exit that still wrote a
 * report is a failing face, which is a report like any other.
 */
internal class WatchFaceSubprocessValidator(
  private val libDir: File,
  private val sdk: DwfAndroidSdk,
  private val home: File,
  private val java: String = File(System.getProperty("java.home"), "bin/java").path,
  private val timeoutSeconds: Long = 180,
) : WatchFaceValidator {
  override fun validate(files: Map<String, ByteArray>): String =
    WffPackagerProcess(libDir, sdk, home, java, timeoutSeconds).run(
      "validate",
      files,
      // With a marketplace the push validator runs too; it needs no signing key, and the token
      // it would issue is never read from the report.
      arguments = { work ->
        listOf(
          "--report-out",
          File(work, REPORT_FILE).path,
          "--push-marketplace",
          WatchFacePushNaming.MARKETPLACE_PACKAGE,
        )
      },
      environment = emptyMap(),
      succeeded = { exit, work -> exit == 0 || File(work, REPORT_FILE).isFile },
    ) { work ->
      File(work, REPORT_FILE).takeIf { it.isFile }?.readText()
        ?: throw DwfPackagingFailed(listOf("error[validate]: the packager wrote no report"))
    }

  private companion object {
    const val REPORT_FILE = "validation-report.json"
  }
}

/**
 * Runs the validator off the event loop, at most [maxConcurrent] at once, and remembers its
 * reports: by [DwfSource.digest] (an unchanged face is not validated twice) and each design's
 * latest, for `GET`. In memory and bounded; a restart validates again on the next request.
 */
internal class WatchFaceValidation(
  private val validator: () -> WatchFaceValidatorAvailability,
  private val clock: () -> Long = System::currentTimeMillis,
  maxConcurrent: Int = 2,
) {
  sealed interface Result {
    data class Ok(val report: WatchFaceValidationReportV1) : Result

    /** This host cannot validate (503). */
    data class Unavailable(val code: String, val reason: String) : Result

    /** The validator ran and gave nothing usable: timed out, or wrote an unreadable report. */
    data class Failed(val code: String, val reason: String) : Result
  }

  private sealed interface Attempt {
    class Failed(val reason: String) : Attempt
  }

  private class Ran(val report: WatchFacePackagerReportV1, val ranAt: Long) : Attempt

  private val permits = Semaphore(maxConcurrent)
  private val lock = Any()
  private val byDigest = lru<String, Ran>(MAX_CACHED)
  private val latest = lru<String, WatchFaceValidationReportV1>(MAX_DESIGNS)

  /** The packager's report on [source], from the cache when this face was validated before. */
  /**
   * [designCreatedAtEpochMillis] is which design [designId] is: a design deleted and recreated
   * under the same id is another one, and never reads this one's report.
   */
  suspend fun validate(
    designId: String,
    designCreatedAtEpochMillis: Long?,
    revision: Long,
    source: DwfSource,
  ): Result {
    val digest = source.digest()
    synchronized(lock) { byDigest[digest] }
      ?.let {
        return Result.Ok(remember(designId, designCreatedAtEpochMillis, revision, digest, it))
      }
    val ready =
      when (val availability = validator()) {
        is WatchFaceValidatorAvailability.Unavailable ->
          return Result.Unavailable(availability.code, availability.reason)
        is WatchFaceValidatorAvailability.Ready -> availability.validator
      }
    val attempt = permits.withPermit {
      withContext(Dispatchers.IO) { synchronized(lock) { byDigest[digest] } ?: run(ready, source) }
    }
    return when (attempt) {
      is Attempt.Failed -> Result.Failed(VALIDATOR_FAILED, attempt.reason)
      is Ran -> {
        synchronized(lock) { byDigest[digest] = attempt }
        Result.Ok(remember(designId, designCreatedAtEpochMillis, revision, digest, attempt))
      }
    }
  }

  private fun run(validator: WatchFaceValidator, source: DwfSource): Attempt =
    try {
      val text = validator.validate(DwfPackaging.bundleFiles(source))
      Ran(REPORT_JSON.decodeFromString(WatchFacePackagerReportV1.serializer(), text), clock())
    } catch (failed: DwfPackagingFailed) {
      Ran(packagerFailed(source, failed.diagnostics), clock())
    } catch (_: SerializationException) {
      Attempt.Failed("the validator's report could not be read")
    } catch (_: IllegalArgumentException) {
      Attempt.Failed("the validator's report could not be read")
    } catch (failed: DwfRuntimeException) {
      Attempt.Failed(failed.message ?: "the validator failed")
    } catch (failed: java.io.IOException) {
      Attempt.Failed("the validator could not run: ${failed.message}")
    }

  /**
   * The report on a face whose XML names bitmaps nothing supplies ([missing], from
   * [packagedWithPinnedDrawables]): the packager is not run, since its own word for this is an
   * opaque memory-footprint error, and the face fails one bundle check with each name.
   */
  fun missingResources(
    designId: String,
    designCreatedAtEpochMillis: Long?,
    revision: Long,
    source: DwfSource,
    missing: List<String>,
  ): WatchFaceValidationReportV1 {
    val digest = source.digest()
    val ran =
      Ran(
        WatchFacePackagerReportV1(
          schemaVersion = SCHEMA_VERSION,
          wffVersion = source.wffVersion,
          passed = false,
          checks =
            listOf(
              WatchFaceValidationCheckV1(
                id = MISSING_RESOURCE_CHECK,
                title = "Every bitmap the face draws is in the bundle",
                status = STATUS_FAILED,
                findings =
                  missing.map {
                    WatchFaceValidationFindingV1(
                      severity = "error",
                      message = "${WatchFaceDrawables.MISSING_RESOURCE}: $it",
                    )
                  },
              ),
            ),
        ),
        clock(),
      )
    return remember(designId, designCreatedAtEpochMillis, revision, digest, ran)
  }

  /** The last report on [designId] (the one created then) if it was on [revision]. */
  fun latest(
    designId: String,
    designCreatedAtEpochMillis: Long?,
    revision: Long,
  ): WatchFaceValidationReportV1? =
    synchronized(lock) { latest[designKey(designId, designCreatedAtEpochMillis)] }
      ?.takeIf { it.revision == revision }

  private fun remember(
    designId: String,
    designCreatedAtEpochMillis: Long?,
    revision: Long,
    digest: String,
    ran: Ran,
  ): WatchFaceValidationReportV1 {
    val report =
      WatchFaceValidationReportV1(
        schemaVersion = ran.report.schemaVersion,
        wffVersion = ran.report.wffVersion,
        minimumWffVersion = ran.report.minimumWffVersion,
        passed = ran.report.passed,
        checks = ran.report.checks,
        mode = ran.report.mode,
        designId = designId,
        revision = revision,
        digest = digest,
        ranAtEpochMillis = ran.ranAt,
      )
    synchronized(lock) { latest[designKey(designId, designCreatedAtEpochMillis)] = report }
    return report
  }

  /** The packager refused the bundle before it could report: one failed check, its diagnostics. */
  private fun packagerFailed(source: DwfSource, diagnostics: List<String>) =
    WatchFacePackagerReportV1(
      schemaVersion = SCHEMA_VERSION,
      wffVersion = source.wffVersion,
      passed = false,
      checks =
        listOf(
          WatchFaceValidationCheckV1(
            id = PACKAGER_CHECK,
            title = "The packager accepts the bundle",
            status = STATUS_FAILED,
            findings = diagnostics.map(::finding),
          ),
        ),
    )

  companion object {
    const val SCHEMA_VERSION = 1
    const val STATUS_FAILED = "failed"
    const val MISSING_RESOURCE_CHECK = "bundle-resources"
    const val PACKAGER_CHECK = "packager"
    const val VALIDATOR_FAILED = "validator_failed"
    private const val MAX_CACHED = 128
    private const val MAX_DESIGNS = 512
    private val DIAGNOSTIC = Regex("^(error|warning|info)\\[([^\\]]+)\\]: (.*)$")

    private val REPORT_JSON = Json { ignoreUnknownKeys = true }

    /** `error[source]: message` as a finding; any other line is an error with that message. */
    internal fun finding(line: String): WatchFaceValidationFindingV1 {
      val match = DIAGNOSTIC.matchEntire(line.trim())
      return if (match == null) WatchFaceValidationFindingV1("error", line.trim())
      else
        WatchFaceValidationFindingV1(
          severity = match.groupValues[1],
          message = match.groupValues[3],
          path = match.groupValues[2],
        )
    }

    private fun designKey(designId: String, createdAtEpochMillis: Long?) =
      "$createdAtEpochMillis/$designId"

    private fun <K, V> lru(max: Int): MutableMap<K, V> =
      object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > max
      }
  }
}
