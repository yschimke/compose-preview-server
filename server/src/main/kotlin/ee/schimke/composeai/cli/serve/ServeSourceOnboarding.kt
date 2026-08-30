package ee.schimke.composeai.cli.serve

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * **Onboarding a repository that has never heard of this tool** — paste a GitHub URL, find out what
 * Compose previews are in it, and (on a box that opted in) build them from source and serve them.
 *
 * [ServeOnboarding] registers what a repository already *publishes*; this one handles the case that
 * flow answers 404 for, which is every project the first time (issue #12). The four sample
 * multiplatform apps this was written against — GalwayBus, BikeShare, ClimateTraceKMP,
 * PeopleInSpace — have no delivery branch, no preview plugin in their build files, and no reason to
 * add one. Onboarding them must not require a fork, a pull request, or any edit upstream.
 *
 * ### Two passes, because they carry very different risk
 *
 * **Scan** ([scan]) clones shallowly and *reads* the checkout ([ServeSourceScan]). It executes
 * nothing, so it is available on any box with an admin token, and it is most of the perceived
 * magic: telling someone which of their modules hold previews, and which the plugin can be injected
 * into, is a useful answer on its own and the honest input to deciding whether to run the second
 * pass.
 *
 * **Build** ([startBuild]) runs the checkout's own `./gradlew` — the producer's build scripts, as
 * the server user. That is unrestricted code execution and is gated accordingly:
 *
 * - the caller holds the **admin token** (enforced by the route);
 * - the box **opted into executing foreign code** — no [builder] is supplied otherwise, so a box
 *   that did not opt in has no build path at all rather than a disabled one ([buildEnabled]);
 * - only modules a scan called [ServeSourceModule.buildable] are attempted, and at most
 *   [maxModules] of them;
 * - the build runs on a **single background lane** ([executor]) with the builder's own timeout, so
 *   a cold Gradle build of an hour-long project cannot block a request thread or crowd out the
 *   box's live traffic; the caller polls [job] instead.
 *
 * ### The plugin is injected, never required
 *
 * Nothing asks the pasted repository to apply `ee.schimke.composeai.preview`. The [builder] handed
 * in carries the auto-inject init-script arguments the CLI already materialises
 * ([ServeBuildHost.autoInjectInitScriptArgs]) as its extra Gradle arguments, and that init script
 * applies the plugin to every module that applies an Android or Compose Multiplatform plugin. That
 * is the whole reason onboarding an upstream project needs no upstream change — and equally the
 * reason a box whose build host injects nothing (the standalone distribution, unless the operator
 * points `--onboard-init-script` at a materialised one) can only build projects that already apply
 * the plugin themselves.
 *
 * ### What comes out, and what it is trusted for
 *
 * A built module is registered as an ordinary **project session** through [register] — the same
 * kind of session `serve` registers for its own checkout — reachable at `/<id>/`. It is
 * emphatically *not* a trusted catalog: nothing here verifies a producer, so a built-from-source
 * session badges exactly as an unverified one does. Building a repository is a statement about this
 * box's willingness to run code, never about the repository.
 *
 * The registration is in-memory and deliberately not persisted: a built session's artifacts live in
 * a scratch checkout, so a restart correctly forgets it rather than resurrecting a session whose
 * build tree may be gone. Re-onboarding rebuilds, on the current head.
 */
class ServeSourceOnboarding(
  private val checkouts: ServeSourceCheckouts,
  /**
   * Builds one module inside the checkout. **Null means this box does not execute foreign code** —
   * the scan pass still works and the build pass reports itself unavailable.
   */
  private val builder: RevisionBuilder?,
  /** Registers a built module as a served session id. */
  private val register: (id: String, state: ServeSessionState) -> Unit,
  /**
   * Whether a session id is already in use on this box, so onboarding cannot silently replace a
   * catalog someone is browsing.
   */
  private val isTaken: (String) -> Boolean = { false },
  private val scanner: (File) -> ServeSourceScanResult = { ServeSourceScan.scan(it) },
  /** Most modules built from one repository, so a monorepo can't spend the box on one paste. */
  private val maxModules: Int = DEFAULT_MAX_MODULES,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = {},
  /**
   * One build at a time, server-wide: the seat budget for foreign builds is deliberately one.
   *
   * An [Executor] rather than an `ExecutorService` so a test can run the lane inline and assert a
   * finished job instead of a timing window; [close] shuts down whatever service is behind it.
   */
  private val executor: Executor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "serve-source-onboard").apply { isDaemon = true }
  },
) : AutoCloseable {

  private val jobs = ConcurrentHashMap<String, MutableJob>()
  private val sequence = AtomicLong()
  private val lock = ReentrantLock()

  /** Whether this box can build a pasted repository at all. */
  val buildEnabled: Boolean
    get() = builder != null

  /** The outcome of a scan. */
  sealed interface ScanResult {
    /** Not a GitHub project URL — a 400. */
    data class Invalid(val reason: String) : ScanResult

    /** The repository couldn't be cloned: missing, private, bad ref, or git trouble — a 502. */
    data class Unreachable(val repo: String, val reason: String) : ScanResult

    /**
     * The checkout was read. [modules] may be empty or hold nothing buildable; [notes] says why.
     */
    data class Ok(
      val repo: String,
      val ref: String,
      val sha: String,
      val modules: List<ServeSourceModule>,
      val notes: List<String>,
    ) : ScanResult {
      val buildable: List<ServeSourceModule>
        get() = modules.filter { it.buildable }
    }
  }

  /** What [startBuild] did. */
  sealed interface BuildStart {
    /** The build is queued on the background lane; poll [Job.id]. */
    data class Started(val job: Job) : BuildStart

    /** This box does not execute foreign build scripts — a 403, and not a transient one. */
    data class Unavailable(val reason: String) : BuildStart

    /** The repository couldn't be read; mapped exactly as the same [ScanResult] is for a scan. */
    data class Rejected(val scan: ScanResult) : BuildStart

    /**
     * The repository was read and holds nothing this box can build — a 404, with the scan attached
     * so the caller sees the modules that *were* found and why each was passed over.
     */
    data class NothingToBuild(
      val repo: String,
      val ref: String,
      val modules: List<ServeSourceModule>,
      val notes: List<String>,
    ) : BuildStart
  }

  /** A build's progress, as the caller polls it. */
  data class Job(
    val id: String,
    val repo: String,
    val ref: String,
    val sha: String,
    /** `queued`, `running`, `succeeded`, `partial`, `failed`. */
    val status: String,
    val modules: List<ModuleOutcome>,
    val startedAt: Long,
    val finishedAt: Long?,
    /** Why the job as a whole failed, when nothing module-specific explains it. */
    val detail: String? = null,
  ) {
    /** Session ids serving as a result of this job. */
    val served: List<String>
      get() = modules.mapNotNull { it.sessionId.takeIf { _ -> it.status == BUILT } }
  }

  /** What became of one module in a build. */
  data class ModuleOutcome(
    val gradlePath: String,
    /** `pending`, `building`, `built`, `failed`, `skipped`. */
    val status: String,
    /** Where it is served, once built. */
    val sessionId: String? = null,
    val previewCount: Int = 0,
    val detail: String? = null,
  )

  /**
   * Clone [rawUrl] shallowly and report the Compose modules in it. Executes nothing in the
   * checkout.
   */
  fun scan(rawUrl: String, ref: String? = null): ScanResult {
    val project =
      GithubProject.parse(rawUrl)
        ?: return ScanResult.Invalid("'$rawUrl' is not a GitHub project URL")
    if (ref != null && !isSafeRef(ref)) return ScanResult.Invalid("'$ref' is not a usable git ref")
    val repo = project.slug
    val checkout =
      checkouts.checkout(repo, ref).getOrElse {
        return ScanResult.Unreachable(repo, it.message ?: "could not check out $repo")
      }
    val scanned = runCatching {
      scanner(checkout.dir)
    }
      .getOrElse {
        return ScanResult.Unreachable(repo, "could not read the checkout: ${it.message}")
      }
    onLog(
      "serve: scanned $repo@${checkout.ref} — ${scanned.buildable.size}/${scanned.modules.size} " +
        "module(s) hold buildable previews"
    )
    return ScanResult.Ok(
      repo = repo,
      ref = checkout.ref,
      sha = checkout.sha,
      modules = scanned.modules,
      notes = scanned.notes,
    )
  }

  /**
   * Scan [rawUrl] and start building its previewable modules in the background.
   *
   * The repository is read exactly as [scan] reads it, and a repository that can't be read comes
   * back as [BuildStart.Rejected] carrying that same [ScanResult] — so the caller maps one set of
   * failures to one set of statuses instead of two that drift apart.
   *
   * [modules] restricts the build to those Gradle paths; empty builds everything the scan found
   * buildable, up to [maxModules]. A named module the scan didn't call buildable is still reported
   * (as `skipped`), because "I asked for `:app` and got nothing" needs an answer that names `:app`.
   */
  fun startBuild(
    rawUrl: String,
    ref: String? = null,
    modules: List<String> = emptyList(),
  ): BuildStart {
    if (builder == null) {
      return BuildStart.Unavailable(
        "this server does not build pasted repositories — start it with --onboard-build " +
          "(and --allow-render-trusted) to opt into executing foreign build scripts"
      )
    }
    val scanned = scan(rawUrl, ref)
    if (scanned !is ScanResult.Ok) return BuildStart.Rejected(scanned)
    val requested = modules.map { it.trim().trimStart(':') }.filter { it.isNotEmpty() }.distinct()
    val selected =
      if (requested.isEmpty()) {
        scanned.buildable.take(maxModules)
      } else {
        scanned.modules.filter { it.gradlePath in requested }
      }
    val outcomes =
      selected.map {
        if (it.buildable) {
          ModuleOutcome(it.gradlePath, PENDING, previewCount = it.previewCount)
        } else {
          ModuleOutcome(
            it.gradlePath,
            SKIPPED,
            previewCount = it.previewCount,
            detail = it.skipReason,
          )
        }
      } +
        // A path the caller named that isn't in the build at all. Reported rather than dropped:
        // the usual cause is a typo'd Gradle path, and silence reads as "built nothing, no reason".
        requested
          .filter { path -> selected.none { it.gradlePath == path } }
          .map { ModuleOutcome(it, SKIPPED, detail = "no such module in this repository") }
    val job =
      MutableJob(
        id = "job-${sequence.incrementAndGet()}",
        repo = scanned.repo,
        ref = scanned.ref,
        sha = scanned.sha,
        startedAt = clock(),
      )
    job.modules.addAll(outcomes)
    if (outcomes.none { it.status == PENDING }) {
      // Nothing to run, so no job is created: an empty job that is born failed reads as "the build
      // broke" when what happened is that this repository has no previews the plugin can reach.
      return BuildStart.NothingToBuild(
        repo = scanned.repo,
        ref = scanned.ref,
        modules = scanned.modules,
        notes =
          scanned.notes.ifEmpty {
            listOf("no module in ${scanned.repo} holds buildable previews")
          } + outcomes.mapNotNull { it.detail },
      )
    }
    jobs[job.id] = job
    executor.execute { runJob(job) }
    return BuildStart.Started(job.snapshot())
  }

  /** A job by id, or null. */
  fun job(id: String): Job? = jobs[id]?.snapshot()

  /** Every job this process has run, newest first. */
  fun jobs(): List<Job> = jobs.values.map { it.snapshot() }.sortedByDescending { it.startedAt }

  override fun close() {
    (executor as? ExecutorService)?.shutdownNow()
  }

  /** The build pass itself, on the single background lane. */
  private fun runJob(job: MutableJob) {
    val builder = this.builder ?: return
    job.status = RUNNING
    // Re-checked out under the job rather than reusing the scan's directory reference: the scan
    // may have been minutes ago, and a second job for another repository shares this lane and the
    // same cache root.
    val checkout =
      checkouts.checkout(job.repo, job.ref).getOrElse {
        job.fail(it.message ?: "could not check out ${job.repo}", clock())
        return
      }
    job.sha = checkout.sha
    for ((index, outcome) in job.modules.withIndex()) {
      if (outcome.status != PENDING) continue
      job.set(index, outcome.copy(status = BUILDING))
      val moduleRef =
        ServeModuleRef(
          gradlePath = outcome.gradlePath,
          relativePath = outcome.gradlePath.replace(':', '/'),
        )
      // isSecurityChecked = true: the two gates this flow has — an admin-token holder named this
      // repository, and the box opted into executing foreign code — were both cleared before any
      // job existed. Unlike project mode there is no ref allowlist to clear; see the class kdoc.
      val built = runCatching {
        builder.build(checkout.dir, moduleRef, isSecurityChecked = true)
      }
        .getOrElse {
          job.set(index, outcome.copy(status = FAILED, detail = "build threw: ${it.message}"))
          null
        }
      if (built == null) {
        if (job.modules[index].status != FAILED) {
          job.set(
            index,
            outcome.copy(
              status = FAILED,
              detail = "the module's own Gradle build or preview discovery failed",
            ),
          )
        }
        continue
      }
      val sessionId = allocateId(job.repo, outcome.gradlePath)
      if (sessionId == null) {
        job.set(
          index,
          outcome.copy(status = FAILED, detail = "no free session id for this module on this box"),
        )
        continue
      }
      register(
        sessionId,
        ServeSessionState(
          descriptor = built.descriptor,
          workspaceRoot = built.moduleDir,
          workspaceName = built.moduleDir.name,
          previews = built.previews,
          label = "${job.repo}:${outcome.gradlePath}@${job.ref}",
          declaredThemes = built.declaredThemes,
        ),
      )
      job.set(
        index,
        outcome.copy(
          status = BUILT,
          sessionId = sessionId,
          previewCount = built.previews.size,
          detail = null,
        ),
      )
      onLog("serve: onboarded ${job.repo}:${outcome.gradlePath} from source as /$sessionId/")
    }
    val built = job.modules.count { it.status == BUILT }
    val attempted = job.modules.count { it.status == BUILT || it.status == FAILED }
    job.status =
      when {
        built == 0 -> FAILED
        built < attempted -> PARTIAL
        else -> SUCCEEDED
      }
    job.finishedAt = clock()
  }

  /**
   * A free session id for one built module: `owner-repo` for a single module, suffixed with the
   * module path when that is taken.
   *
   * Serialised, because two modules of the same repository are allocated back to back and both
   * would otherwise see the same free id. Null when even the suffixed forms collide — better than
   * replacing a catalog someone is browsing.
   */
  private fun allocateId(repo: String, gradlePath: String): String? = lock.withLock {
    // This job's own siblings are claimed here and not yet registered anywhere, so [isTaken]
    // cannot see them: a repository whose two modules both want `owner-repo` would otherwise get
    // the same id twice and the second registration would evict the first.
    val claimed = jobs.values.flatMap { j -> j.modules.mapNotNull { it.sessionId } }.toSet()
    val base = slug(repo.substringBefore('/') + "-" + repo.substringAfter('/'))
    val candidates =
      listOf(base, slug("$base-$gradlePath")) +
        (2..9).map { slug("$base-$gradlePath-$it") } +
        (2..9).map { slug("$base-$it") }
    candidates.firstOrNull {
      it.isNotEmpty() && it !in claimed && it !in ServeSites.RESERVED_SYSTEMS && !isTaken(it)
    }
  }

  /** A route-safe id: lowercase, digits, letters and single dashes. */
  private fun slug(raw: String): String =
    raw
      .lowercase()
      .map { if (it.isLetterOrDigit()) it else '-' }
      .joinToString("")
      .replace(Regex("-+"), "-")
      .trim('-')
      .take(MAX_ID_LENGTH)
      .trim('-')

  /**
   * Refs that may be handed to `git clone --branch` / `git fetch`. Conservative on purpose: a ref
   * reaches a command line, and a value that starts with `-` is an argument, not a branch.
   */
  private fun isSafeRef(ref: String): Boolean =
    ref.isNotBlank() &&
      ref.length <= MAX_REF_LENGTH &&
      !ref.startsWith("-") &&
      ref.all { it.isLetterOrDigit() || it in "._/-" }

  /** The mutable half of a [Job]; snapshotted under its own lock for every read. */
  private class MutableJob(
    val id: String,
    val repo: String,
    val ref: String,
    @Volatile var sha: String,
    val startedAt: Long,
  ) {
    private val lock = ReentrantLock()
    val modules = mutableListOf<ModuleOutcome>()
    @Volatile var status: String = QUEUED
    @Volatile var finishedAt: Long? = null
    @Volatile var detail: String? = null

    fun set(index: Int, outcome: ModuleOutcome) = lock.withLock { modules[index] = outcome }

    fun fail(reason: String, now: Long) {
      status = FAILED
      detail = reason
      finishedAt = now
    }

    fun snapshot(): Job = lock.withLock {
      Job(
        id = id,
        repo = repo,
        ref = ref,
        sha = sha,
        status = status,
        modules = modules.toList(),
        startedAt = startedAt,
        finishedAt = finishedAt,
        detail = detail,
      )
    }
  }

  companion object {
    const val QUEUED = "queued"
    const val RUNNING = "running"
    const val SUCCEEDED = "succeeded"
    const val PARTIAL = "partial"
    const val FAILED = "failed"

    const val PENDING = "pending"
    const val BUILDING = "building"
    const val BUILT = "built"
    const val SKIPPED = "skipped"

    /** Modules built from one repository when the caller named none. */
    const val DEFAULT_MAX_MODULES = 4

    private const val MAX_ID_LENGTH = 48
    private const val MAX_REF_LENGTH = 200
  }
}
