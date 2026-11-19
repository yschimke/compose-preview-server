package ee.schimke.composeai.cli.serve

import java.io.File
import kotlinx.coroutines.asCoroutineDispatcher
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
  val schemaVersion: Int = 1,
  /** Null when the packager could not read one from the bundle; the source's is used then. */
  val wffVersion: Int? = null,
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
 * report is a failing face, which is a report like any other. The child runs at a lowered OS
 * priority ([NICENESS]) and is killed, with its whole process tree, after [timeoutSeconds].
 */
internal class WatchFaceSubprocessValidator(
  private val libDir: File,
  private val sdk: DwfAndroidSdk,
  private val home: File,
  private val java: String = File(System.getProperty("java.home"), "bin/java").path,
  private val timeoutSeconds: Long = 180,
) : WatchFaceValidator {
  override fun validate(files: Map<String, ByteArray>): String =
    WffPackagerProcess(libDir, sdk, home, java, timeoutSeconds, niceness = NICENESS).run(
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

  internal companion object {
    const val REPORT_FILE = "validation-report.json"

    /** `nice -n` for the validator's JVM: below requests, above nothing in particular. */
    const val NICENESS = 10
  }
}

/**
 * Runs the validator, and remembers its reports: by [DwfSource.digest] (an unchanged face is not
 * validated twice) and each design's latest, for `GET`. In memory and bounded; a restart validates
 * again on the next request.
 *
 * Only an explicit request reaches [validate]: `POST …/watchface-validation` (the Validate panel's
 * Run button) and the `ui_builder_validate_watch_face` MCP tool. An edit, a read, a listing, a
 * publish and the automatic Watch Face Push listing never do; the per-edit `watchFaceCost` is the
 * cheap estimate between runs. Each run is a packager JVM, so it is kept lean:
 * - at most [maxConcurrent] at once (one), on low-priority threads of its own, the packager itself
 *   at a lowered OS priority ([WatchFaceSubprocessValidator]) and under its timeout;
 * - at most [maxQueued] more waiting; past that the caller is told [Result.Busy] (a 429 with
 *   `Retry-After`) rather than queued without end;
 * - at most [perActorRuns] runs per actor per [perActorWindowMillis]; a cached report does not
 *   count, since it runs nothing. Past that, [Result.RateLimited].
 */
internal class WatchFaceValidation(
  private val validator: () -> WatchFaceValidatorAvailability,
  private val clock: () -> Long = System::currentTimeMillis,
  private val maxConcurrent: Int = 1,
  private val maxQueued: Int = 3,
  private val perActorRuns: Int = 10,
  private val perActorWindowMillis: Long = 10 * 60 * 1000L,
  /** What one run is assumed to take, for a [Result.Busy]'s `Retry-After`. */
  private val typicalRunSeconds: Long = 30,
) {
  sealed interface Result {
    data class Ok(val report: WatchFaceValidationReportV1) : Result

    /** This host cannot validate (503). */
    data class Unavailable(val code: String, val reason: String) : Result

    /** The validator ran and gave nothing usable: timed out, or wrote an unreadable report. */
    data class Failed(val code: String, val reason: String) : Result

    /** Too many validations are already waiting here (429); try again after [retryAfterSeconds]. */
    data class Busy(val reason: String, val retryAfterSeconds: Long) : Result {
      val code: String
        get() = BUSY
    }

    /** This actor has used its validations for now (429); try again after [retryAfterSeconds]. */
    data class RateLimited(val reason: String, val retryAfterSeconds: Long) : Result {
      val code: String
        get() = RATE_LIMITED
    }
  }

  private sealed interface Attempt {
    class Failed(val reason: String) : Attempt
  }

  private class Ran(val report: WatchFacePackagerReportV1, val ranAt: Long) : Attempt

  private val permits = Semaphore(maxConcurrent)
  private val lock = Any()
  private val byDigest = lru<String, Ran>(MAX_CACHED)
  private val latest = lru<String, WatchFaceValidationReportV1>(MAX_DESIGNS)
  /** Runs admitted and not yet finished: running plus waiting for a permit. */
  private var admitted = 0
  /** Validations running or waiting for the packager now. */
  internal val pending: Int
    get() = synchronized(lock) { admitted }

  /** Each actor's recent run start times, oldest first. */
  private val runsByActor = lru<String, ArrayDeque<Long>>(MAX_ACTORS)

  /** Low-priority daemon threads of their own, so a run never takes an IO thread from a request. */
  private val dispatcher by lazy {
    val count = java.util.concurrent.atomic.AtomicInteger()
    java.util.concurrent.Executors.newFixedThreadPool(maxConcurrent) { runnable ->
        Thread(runnable, "wff-validate-${count.incrementAndGet()}").apply {
          isDaemon = true
          priority = Thread.MIN_PRIORITY
        }
      }
      .asCoroutineDispatcher()
  }

  /**
   * The packager's report on [source], from the cache when this face was validated before.
   *
   * [designCreatedAtEpochMillis] is which design [designId] is: a design deleted and recreated
   * under the same id is another one, and never reads this one's report. [actorId] is who asked,
   * for the per-actor limit.
   */
  suspend fun validate(
    designId: String,
    designCreatedAtEpochMillis: Long?,
    revision: Long,
    source: DwfSource,
    actorId: String,
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
    val chargedAt =
      when (val admission = admit(actorId)) {
        is Admission.Refused -> return admission.result
        is Admission.Admitted -> admission.chargedAt
      }
    val attempt =
      try {
        permits.withPermit {
          withContext(dispatcher) {
            // Another request ran this face while this one waited: the cache answers, and a run
            // that never happened is given back to the actor's allowance.
            synchronized(lock) { byDigest[digest] }?.also { refund(actorId, chargedAt) }
              ?: run(ready, source).also { ran ->
                // Cached before the permit is released, so a request waiting on it finds the
                // report instead of running the same face again.
                if (ran is Ran) synchronized(lock) { byDigest[digest] = ran }
              }
          }
        }
      } finally {
        synchronized(lock) { admitted-- }
      }
    return when (attempt) {
      is Attempt.Failed -> Result.Failed(VALIDATOR_FAILED, attempt.reason)
      is Ran -> {
        Result.Ok(remember(designId, designCreatedAtEpochMillis, revision, digest, attempt))
      }
    }
  }

  private sealed interface Admission {
    class Admitted(val chargedAt: Long) : Admission

    class Refused(val result: Result) : Admission
  }

  /** Undo [admit]'s charge to [actorId] at [chargedAt], for a request the cache answered. */
  private fun refund(actorId: String, chargedAt: Long) {
    synchronized(lock) { runsByActor[actorId]?.remove(chargedAt) }
  }

  /** Admit one run for [actorId], or say why not; admitted, the caller must release `admitted`. */
  private fun admit(actorId: String): Admission =
    synchronized(lock) {
      val now = clock()
      val runs = runsByActor.getOrPut(actorId) { ArrayDeque() }
      while (runs.isNotEmpty() && runs.first() <= now - perActorWindowMillis) runs.removeFirst()
      if (runs.size >= perActorRuns) {
        val retry = (runs.first() + perActorWindowMillis - now + 999) / 1000
        return@synchronized Admission.Refused(
          Result.RateLimited(
            "this account has run $perActorRuns watch-face validations in the " +
              "last ${perActorWindowMillis / 60_000} minutes; an unchanged face answers from " +
              "cache, so validate when an edit pass is done rather than after every edit",
            retry.coerceAtLeast(1),
          ),
        )
      }
      if (admitted >= maxConcurrent + maxQueued) {
        val ahead = admitted - maxConcurrent + 1
        return@synchronized Admission.Refused(
          Result.Busy(
            "$admitted watch-face validations are already running or waiting on this " +
              "server; try again shortly",
            (ahead * typicalRunSeconds / maxConcurrent).coerceAtLeast(typicalRunSeconds),
          ),
        )
      }
      runs.addLast(now)
      admitted++
      Admission.Admitted(now)
    }

  private fun run(validator: WatchFaceValidator, source: DwfSource): Attempt =
    try {
      val text = validator.validate(DwfPackaging.bundleFiles(source))
      val report = REPORT_JSON.decodeFromString(WatchFacePackagerReportV1.serializer(), text)
      Ran(report.copy(wffVersion = report.wffVersion ?: source.wffVersion), clock())
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
        wffVersion = ran.report.wffVersion ?: DwfPreviewSurface.DEFAULT_WFF_VERSION,
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
    /** [Result.Busy]'s code: the queue is full. */
    const val BUSY = "watch_face_validation_busy"
    /** [Result.RateLimited]'s code: this actor's runs for the window are used. */
    const val RATE_LIMITED = "watch_face_validation_rate_limited"
    private const val MAX_ACTORS = 1024
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
