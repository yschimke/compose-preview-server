package ee.schimke.composeai.cli.serve

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import okio.FileSystem
import okio.Path

/**
 * Stage-1 playground orchestrator (`docs/design/PLAYGROUND.md` §2): stage a [PlaygroundRunRequest]
 * to a temp dir, compile it against the mode's catalog classpath, and on success mint an expiring
 * preview token Stage 2 redeems into a live session. Compile errors return diagnostics and no
 * token.
 *
 * All files in a request go to one compile, so multi-file snippets work; the rendered `@Preview` is
 * chosen by sorted id for determinism ([compileAndMint]). [PlaygroundMode.REMOTE_COMPOSE] instead
 * captures the `.rc` and publishes a `/d/<id>` permalink ([remoteComposeResult]) with no token or
 * daemon (§3).
 *
 * Every collaborator touching the daemon, compiler or catalog is injected, so orchestration is
 * unit-testable. [catalogClasspath] is backed in production by the liveBundle resolution
 * ([ServeBundleDaemon.materialize]): the bundle's `classes/app.jar` plus its resolved Maven
 * classpath, so snippets can import the full library and whatever catalog composables survived
 * minimization (§8).
 */
class PlaygroundCompileService(
  /**
   * Compile+render classpath for a mode and optional named catalog, or null when unavailable. A
   * null catalog means the pinned `--playground-bundle` default; a named one is a runtime selector
   * choice ([PlaygroundCatalogTargets]).
   */
  private val catalogClasspath: (PlaygroundMode, String?) -> Classpath?,
  private val compiler: Compiler,
  private val discoverer: PreviewDiscoverer,
  private val tokenStore: PlaygroundTokenStore,
  /** A fresh temp work dir per run; the token store deletes it when the token drops. */
  private val newWorkDir: () -> Path,
  private val fileSystem: FileSystem = FileSystem.SYSTEM,
  /** Optional first-frame render; returns PNG bytes or null. Defaults to no image (wired later). */
  private val renderFirstFrame: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = { null },
  /**
   * [renderFirstFrame] plus the reason it drew nothing, returned as
   * [PlaygroundRunResponse.exception] beside the token so renderer errors reach the caller.
   * Defaults to no reason (no renderer).
   */
  private val renderFirstFrameWithReason:
    (PlaygroundTokenStore.PlaygroundSnippet) -> PlaygroundFirstFrame =
    {
      PlaygroundFirstFrame(renderFirstFrame(it))
    },
  /**
   * [PlaygroundMode.REMOTE_COMPOSE] capture: run the snippet's `@Preview` under an RC-capable
   * render and return the `.rc` bytes, or null (no document or no engine). Defaults to no capture.
   */
  private val captureRemoteDocument: (PlaygroundTokenStore.PlaygroundSnippet) -> ByteArray? = {
    null
  },
  /**
   * Publish captured `.rc` bytes and return the `/d/<id>` permalink, or null. The `Boolean` is the
   * [isSecurityChecked] marker, as for [ServeDocStore.add]. Defaults to no publisher.
   */
  private val publishRemoteDocument: (String, ByteArray, Boolean) -> String? = { _, _, _ -> null },
  /**
   * Served catalogs a request may name, read fresh per call (catalogs load after wiring). Null
   * means pinned-only with no selector; non-null returning empty means `--playground` is on but
   * nothing loaded yet. The editor shows a selector only for the latter, so the two must stay
   * distinct.
   */
  private val catalogTargets: (() -> List<PlaygroundCatalogTarget>)? = null,
  /**
   * The served-catalog id a pinned mode compiles against when `--playground-bundle` named one; null
   * for local paths or no pin. Lets [compilesCatalog] answer for the pin, which the selector
   * reports under id `""`.
   */
  private val pinnedCatalogSystem: (PlaygroundMode) -> String? = { null },
  /** Explicitly opt in the one-host-wide authenticated stateful editing lease. */
  val editLeasesEnabled: Boolean = false,
  private val editLeaseTtlMillis: Long = DEFAULT_EDIT_LEASE_TTL_MILLIS,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  /** Schedules idle-lease reclamation; injectable so expiry behavior is deterministic in tests. */
  private val scheduleEditLeaseExpiry: (Long, () -> Unit) -> (() -> Unit) = { delayMillis, task ->
    val future = EDIT_LEASE_EXPIRY_EXECUTOR.schedule(task, delayMillis, TimeUnit.MILLISECONDS)
    val cancel: () -> Unit = { future.cancel(false) }
    cancel
  },
) {

  private val editLock = Any()
  private var editLease: EditLease? = null
  private var cancelEditLeaseExpiry: (() -> Unit)? = null
  private var editLeaseAcquisitions = 0L
  private var editCompileAttempts = 0L
  private var editIncrementalCompiles = 0L
  private var editFullFallbacks = 0L
  private var editLastRevision: Long? = null
  private var editLastCompileMillis: Long? = null
  @Volatile
  private var editHealthSnapshot =
    PlaygroundHealth.Editing(
      enabled = editLeasesEnabled,
      active = false,
      acquisitions = 0,
      compileAttempts = 0,
      incrementalCompiles = 0,
      fullFallbacks = 0,
    )

  private data class EditLease(
    val id: String,
    val owner: String,
    val workDir: Path,
    var expiresAt: Long,
    var lastRevision: Long = 0,
    var config: Pair<PlaygroundMode, String?>? = null,
    var files: Map<String, String> = emptyMap(),
    var incrementalReady: Boolean = false,
    val holders: MutableSet<String> = mutableSetOf(),
  )

  /**
   * True when `--playground` enabled a runtime catalog selector, loaded or not; drives whether the
   * editor shows the Catalog control.
   */
  val catalogSelectorEnabled: Boolean
    get() = catalogTargets != null

  /**
   * Modes the pinned default can serve (classpath resolves with no catalog named), so the editor
   * never offers an unavailable mode. Computed per read because a served-catalog pin resolves only
   * after its catalog loads (#3212); [catalogClasspath] memoizes.
   */
  val availableModes: List<PlaygroundMode>
    get() = PlaygroundMode.entries.filter { catalogClasspath(it, null) != null }

  /**
   * The editor's catalog choices: the pinned default (if any), then every served catalog that can
   * back a compile. The pinned entry may pay a first resolve like [availableModes]; served entries
   * report memoized state, so listing doesn't unpack bundles.
   */
  fun catalogChoices(): List<PlaygroundCatalogInfo> {
    val pinned = availableModes
    val default =
      if (pinned.isEmpty()) null
      else
        PlaygroundCatalogInfo(
          id = "",
          label = "Server default",
          modes = pinned,
          // The pinned bundle resolved — that is exactly what `availableModes` just proved.
          resolved = true,
        )
    return listOfNotNull(default) +
      catalogTargets?.invoke().orEmpty().map {
        PlaygroundCatalogInfo(
          id = it.id,
          label =
            if (it.module.isBlank()) "${it.system} (${it.backend})"
            else "${it.system} · ${it.module} (${it.backend})",
          backend = it.backend,
          modes = it.modes,
          resolved = it.resolved,
          system = it.system,
          module = it.module,
        )
      }
  }

  /**
   * Served-catalog ids the pinned default compiles against; empty when nothing (or only local
   * files) is pinned.
   */
  val pinnedCatalogSystems: Set<String>
    get() = availableModes.mapNotNull { pinnedCatalogSystem(it) }.toSet()

  /**
   * Whether a snippet from [system]'s catalog can be compiled here (a selector entry, or the pinned
   * catalog itself). Browsing surfaces ask before offering a playground handoff, so a link is never
   * offered that would open the snippet against another design system; [ServeHttpServer] omits it
   * and [ServeWeb.playgroundPage] explains stale ones. Answers offerability without resolving a
   * bundle.
   */
  fun compilesCatalog(system: String): Boolean {
    if (system.isBlank()) return false
    if (catalogChoices().any { it.system == system && it.modes.isNotEmpty() }) return true
    return system in pinnedCatalogSystems
  }

  /**
   * The resolved compile/render classpath (the liveBundle `userClassPath`); [moduleName] matches
   * the catalog's Kotlin module so `kotlin.Metadata` stays consistent.
   */
  data class Classpath(val moduleName: String, val entries: List<Path>)

  /** Compiles [sources] against [classpath], emitting `.class` files into [outputDir]. */
  fun interface Compiler {
    fun compile(
      sources: List<Path>,
      classpath: List<Path>,
      outputDir: Path,
    ): List<PlaygroundDiagnostic>

    /**
     * Stateful variant. Implementations without BTA IC do a full compile;
     * [IncrementalCompileResult.incremental] says which.
     */
    fun compileIncremental(
      sources: List<Path>,
      classpath: List<Path>,
      outputDir: Path,
      workingDir: Path,
      modified: List<Path>,
      removed: List<Path>,
      firstBuild: Boolean,
    ): IncrementalCompileResult =
      IncrementalCompileResult(compile(sources, classpath, outputDir), incremental = false)
  }

  data class IncrementalCompileResult(
    val diagnostics: List<PlaygroundDiagnostic>,
    val incremental: Boolean,
  )

  /**
   * Finds the `@Preview` id(s) in freshly compiled [classesDir]; empty ⇒ the snippet declared none.
   */
  fun interface PreviewDiscoverer {
    fun discover(classesDir: Path, classpath: List<Path>): List<String>

    /** The `@Preview` FQNs [discover] accepts, so the empty-result message can name them. */
    val recognisedAnnotationFqns: Set<String>
      get() = PlaygroundPreviewDiscoverer.DEFAULT_PREVIEW_ANNOTATION_FQNS

    /**
     * Preview-shaped annotations in [classesDir] that [discover] rejected, distinguishing "declared
     * none" from "declared one this host can't render". Only consulted when [discover] is empty.
     */
    fun unrecognisedPreviewAnnotations(classesDir: Path): List<String> = emptyList()
  }

  /**
   * Compile [request] and, on success, mint a preview token. [isSecurityChecked] is the audit
   * marker forwarded to [PlaygroundTokenStore.add]; the route passes `true` only after the
   * playground gate.
   */
  fun run(
    request: PlaygroundRunRequest,
    isSecurityChecked: Boolean,
    authenticatedOwner: String? = null,
  ): PlaygroundRunResponse {
    if (request.editLease.isNotEmpty()) {
      return try {
        runWithEditLease(request, isSecurityChecked, authenticatedOwner)
      } catch (t: Throwable) {
        failure("playground live edit failed: ${t.message ?: t.javaClass.simpleName}")
      }
    }
    val files = request.files.filter { it.text.isNotBlank() }
    if (files.isEmpty()) return failure("no source files supplied")

    val mode = PlaygroundMode.fromConfType(request.confType)
    val catalog = request.catalog.trim().takeIf { it.isNotEmpty() }
    val classpath =
      catalogClasspath(mode, catalog)
        ?: return failure(
          if (catalog == null) "mode ${mode.name} is not available on this host"
          else "catalog '$catalog' cannot serve mode ${mode.name} on this host"
        )

    var workDir: Path? = null
    return try {
      workDir = newWorkDir()
      compileAndMint(request, files, mode, classpath, workDir, isSecurityChecked)
    } catch (t: Throwable) {
      // Any failure, including `newWorkDir()` throwing, returns the JSON exception contract. An
      // aborted run cleans its own dir; the token store owns one only once it accepts it.
      workDir?.let { cleanup(it) }
      failure("playground compile failed: ${t.message ?: t.javaClass.simpleName}")
    }
  }

  fun acquireEditLease(owner: String, client: String = ""): PlaygroundEditLeaseResponse =
    synchronized(editLock) {
      if (!editLeasesEnabled) {
        return@synchronized PlaygroundEditLeaseResponse(
          false,
          message = "Live editing is disabled.",
        )
      }
      if (owner.isBlank()) {
        return@synchronized PlaygroundEditLeaseResponse(
          false,
          message = "GitHub sign-in is required for live editing.",
        )
      }
      if (client.length > MAX_EDIT_LEASE_CLIENT_LENGTH) {
        return@synchronized PlaygroundEditLeaseResponse(
          false,
          message = "The live-edit client id is too long.",
        )
      }
      purgeExpiredEditLeaseLocked()
      val existing = editLease
      if (existing != null) {
        if (existing.owner == owner) {
          if (
            client.isNotBlank() &&
              client !in existing.holders &&
              existing.holders.size >= MAX_EDIT_LEASE_HOLDERS
          ) {
            return@synchronized PlaygroundEditLeaseResponse(
              false,
              message = "The live-edit lease already has too many browser tabs attached.",
            )
          }
          if (client.isNotBlank()) existing.holders += client
          renewEditLeaseLocked(existing)
          refreshEditHealthLocked()
          return@synchronized existing.response("You already hold the live-edit lease.")
        }
        return@synchronized PlaygroundEditLeaseResponse(
          acquired = false,
          expiresAtEpochMs = existing.expiresAt,
          message = "The live-edit lease is in use. Try again after it expires.",
        )
      }
      val workDir =
        try {
          newWorkDir().also(fileSystem::createDirectories)
        } catch (t: Throwable) {
          return@synchronized PlaygroundEditLeaseResponse(
            acquired = false,
            message =
              "Live editing could not allocate its workspace: ${t.message ?: t.javaClass.simpleName}",
          )
        }
      val lease =
        EditLease(
          id = newEditLeaseId(),
          owner = owner,
          workDir = workDir,
          expiresAt = nowMillis() + editLeaseTtlMillis,
          holders = mutableSetOf<String>().apply { if (client.isNotBlank()) add(client) },
        )
      editLease = lease
      editLeaseAcquisitions++
      scheduleEditLeaseExpiryLocked(lease)
      refreshEditHealthLocked()
      lease.response("Live editing acquired.")
    }

  /** Cheap, owner-free and lock-free projection: status polling never waits behind a compile. */
  fun editLeaseHealth(): PlaygroundHealth.Editing {
    val snapshot = editHealthSnapshot
    return if (
      snapshot.active &&
        snapshot.expiresAtEpochMs != null &&
        snapshot.expiresAtEpochMs <= nowMillis()
    ) {
      snapshot.copy(active = false, expiresAtEpochMs = null)
    } else {
      snapshot
    }
  }

  fun releaseEditLease(owner: String, id: String, client: String = ""): Boolean =
    synchronized(editLock) {
      purgeExpiredEditLeaseLocked()
      val lease = editLease ?: return@synchronized false
      if (lease.owner != owner || lease.id != id) return@synchronized false
      if (client.isNotBlank()) {
        if (!lease.holders.remove(client)) return@synchronized false
        if (lease.holders.isNotEmpty()) {
          refreshEditHealthLocked()
          return@synchronized true
        }
      }
      editLease = null
      cancelEditLeaseExpiryLocked()
      cleanup(lease.workDir)
      refreshEditHealthLocked()
      true
    }

  private fun EditLease.response(message: String) =
    PlaygroundEditLeaseResponse(
      acquired = true,
      lease = id,
      expiresAtEpochMs = expiresAt,
      revision = lastRevision,
      message = message,
    )

  private fun purgeExpiredEditLeaseLocked() {
    val lease = editLease ?: return
    if (lease.expiresAt > nowMillis()) return
    editLease = null
    cancelEditLeaseExpiryLocked()
    cleanup(lease.workDir)
    refreshEditHealthLocked()
  }

  private fun renewEditLeaseLocked(lease: EditLease) {
    lease.expiresAt = nowMillis() + editLeaseTtlMillis
    scheduleEditLeaseExpiryLocked(lease)
  }

  private fun scheduleEditLeaseExpiryLocked(lease: EditLease) {
    cancelEditLeaseExpiryLocked()
    val leaseId = lease.id
    val deadline = lease.expiresAt
    cancelEditLeaseExpiry =
      scheduleEditLeaseExpiry((deadline - nowMillis()).coerceAtLeast(0L)) {
        synchronized(editLock) {
          val current = editLease ?: return@synchronized
          // A release/reacquire or renewal supersedes this particular deadline's task.
          if (current.id != leaseId || current.expiresAt != deadline) return@synchronized
          cancelEditLeaseExpiry = null
          if (current.expiresAt <= nowMillis()) {
            purgeExpiredEditLeaseLocked()
          } else {
            // Wall-clock adjustment or an early scheduler wakeup: retain the lease and try again at
            // the remaining deadline rather than leaking its workspace.
            scheduleEditLeaseExpiryLocked(current)
          }
        }
      }
  }

  private fun cancelEditLeaseExpiryLocked() {
    cancelEditLeaseExpiry?.invoke()
    cancelEditLeaseExpiry = null
  }

  private fun refreshEditHealthLocked() {
    val lease = editLease?.takeIf { it.expiresAt > nowMillis() }
    editHealthSnapshot =
      PlaygroundHealth.Editing(
        enabled = editLeasesEnabled,
        active = lease != null,
        expiresAtEpochMs = lease?.expiresAt,
        // Process-lifetime soak telemetry, like the neighboring counters: retain the last accepted
        // revision after its lease releases or expires so operators can inspect a completed trial.
        lastRevision = editLastRevision,
        acquisitions = editLeaseAcquisitions,
        compileAttempts = editCompileAttempts,
        incrementalCompiles = editIncrementalCompiles,
        fullFallbacks = editFullFallbacks,
        lastCompileMillis = editLastCompileMillis,
      )
  }

  private fun runWithEditLease(
    request: PlaygroundRunRequest,
    isSecurityChecked: Boolean,
    owner: String?,
  ): PlaygroundRunResponse =
    synchronized(editLock) {
      if (!editLeasesEnabled) return@synchronized failure("Live editing is disabled.")
      purgeExpiredEditLeaseLocked()
      val lease = editLease ?: return@synchronized failure("The live-edit lease has expired.")
      if (owner.isNullOrBlank() || lease.owner != owner || lease.id != request.editLease) {
        return@synchronized failure("This live-edit lease is not yours.")
      }
      if (request.revision <= lease.lastRevision) {
        return@synchronized failure(
          "Revision ${request.revision} is stale; the lease is already at ${lease.lastRevision}."
        )
      }
      val files = request.files.filter { it.text.isNotBlank() }
      if (files.isEmpty()) return@synchronized failure("no source files supplied")

      val mode = PlaygroundMode.fromConfType(request.confType)
      val catalog = request.catalog.trim().takeIf { it.isNotEmpty() }
      val classpath =
        catalogClasspath(mode, catalog)
          ?: return@synchronized failure(
            if (catalog == null) "mode ${mode.name} is not available on this host"
            else "catalog '$catalog' cannot serve mode ${mode.name} on this host"
          )

      renewEditLeaseLocked(lease)
      refreshEditHealthLocked()
      val config = mode to catalog
      if (lease.config != null && lease.config != config) {
        cleanup(lease.workDir)
        fileSystem.createDirectories(lease.workDir)
        lease.files = emptyMap()
        lease.incrementalReady = false
      }
      lease.config = config

      val srcDir = lease.workDir / "src"
      val classesDir = lease.workDir / "classes"
      val icDir = lease.workDir / "bta-ic"
      fileSystem.createDirectories(srcDir)
      fileSystem.createDirectories(classesDir)
      val desired = stagedFileMap(files)
      val removed = (lease.files.keys - desired.keys).map { srcDir / it }
      val modified =
        desired
          .filter { (name, text) -> lease.files[name] != text }
          .map { (name, text) ->
            (srcDir / name).also { path -> fileSystem.write(path) { writeUtf8(text) } }
          }
      removed.forEach { fileSystem.delete(it, mustExist = false) }
      val sources = desired.keys.map { srcDir / it }
      val firstBuild = !lease.incrementalReady
      val compileStarted = System.nanoTime()
      editCompileAttempts++
      refreshEditHealthLocked()
      var compile =
        try {
          compiler.compileIncremental(
            sources = sources,
            classpath = classpath.entries,
            outputDir = classesDir,
            workingDir = icDir,
            modified = modified,
            removed = removed,
            firstBuild = firstBuild,
          )
        } catch (t: Throwable) {
          renewEditLeaseLocked(lease)
          refreshEditHealthLocked()
          throw t
        }
      // Admission precedes the jailed compile, so a busy response compiled nothing; don't accept
      // the staged files, so the full dirty set retries next Run.
      if (compile.diagnostics.isCompileAdmissionFailure()) {
        restoreStagedFiles(srcDir, accepted = lease.files, staged = desired)
        editLastCompileMillis = (System.nanoTime() - compileStarted) / 1_000_000
        renewEditLeaseLocked(lease)
        refreshEditHealthLocked()
        return@synchronized PlaygroundRunResponse(
          diagnostics = compile.diagnostics,
          errors = PlaygroundErrorsWire.project(compile.diagnostics),
          editLease = lease.id,
          revision = lease.lastRevision.takeIf { it > 0 },
        )
      }
      // An internal BTA/IC failure has no source position. Reset only this lease and retry the same
      // revision through the proven full path; ordinary source diagnostics stay incremental.
      if (compile.diagnostics.isInfrastructureCompileFailure()) {
        editFullFallbacks++
        refreshEditHealthLocked()
        fileSystem.deleteRecursively(classesDir, mustExist = false)
        fileSystem.deleteRecursively(icDir, mustExist = false)
        fileSystem.createDirectories(classesDir)
        compile =
          IncrementalCompileResult(
            compiler.compile(sources, classpath.entries, classesDir),
            incremental = false,
          )
        lease.incrementalReady = false
      } else {
        lease.incrementalReady = true
      }
      if (compile.incremental) editIncrementalCompiles++
      editLastCompileMillis = (System.nanoTime() - compileStarted) / 1_000_000
      lease.files = desired
      lease.lastRevision = request.revision
      editLastRevision = request.revision
      refreshEditHealthLocked()

      val diagnostics = compile.diagnostics
      if (diagnostics.any { it.severity == PlaygroundSeverity.ERROR }) {
        renewEditLeaseLocked(lease)
        refreshEditHealthLocked()
        return@synchronized PlaygroundRunResponse(
          diagnostics = diagnostics,
          errors = PlaygroundErrorsWire.project(diagnostics),
          editLease = lease.id,
          revision = request.revision,
          incremental = compile.incremental,
        )
      }

      // BTA needs a mutable output directory for the next edit. Preview tokens need immutable
      // bytecode. Snapshot the successful revision into an ordinary token-owned work directory.
      val revisionWorkDir = newWorkDir()
      val revisionClasses = revisionWorkDir / "classes"
      val response =
        try {
          fileSystem.createDirectories(revisionClasses)
          copyDirectory(classesDir, revisionClasses)
          publishCompiled(
              mode = mode,
              classpath = classpath,
              classesDir = revisionClasses,
              workDir = revisionWorkDir,
              diagnostics = diagnostics,
              isSecurityChecked = isSecurityChecked,
            )
            .copy(
              editLease = lease.id,
              revision = request.revision,
              incremental = compile.incremental,
            )
        } catch (t: Throwable) {
          cleanup(revisionWorkDir)
          failure("playground revision snapshot failed: ${t.message ?: t.javaClass.simpleName}")
        }
      // The TTL is an idle timeout. Give a continuously active owner a full window after
      // compilation, discovery, snapshotting and first-frame rendering finish.
      renewEditLeaseLocked(lease)
      refreshEditHealthLocked()
      response
    }

  private fun compileAndMint(
    request: PlaygroundRunRequest,
    files: List<PlaygroundFile>,
    mode: PlaygroundMode,
    classpath: Classpath,
    workDir: Path,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val srcDir = workDir / "src"
    val classesDir = workDir / "classes"
    fileSystem.createDirectories(srcDir)
    fileSystem.createDirectories(classesDir)
    val sources = stageSources(files, srcDir)

    val diagnostics = compiler.compile(sources, classpath.entries, classesDir)
    if (diagnostics.any { it.severity == PlaygroundSeverity.ERROR }) {
      cleanup(workDir)
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = PlaygroundErrorsWire.project(diagnostics),
      )
    }

    return publishCompiled(
      mode = mode,
      classpath = classpath,
      classesDir = classesDir,
      workDir = workDir,
      diagnostics = diagnostics,
      isSecurityChecked = isSecurityChecked,
    )
  }

  /**
   * Why nothing rendered, in the author's terms: the snippet compiled, so name the preview imports
   * this host accepts and any preview-shaped annotation it found but doesn't read (usually the
   * wrong import).
   */
  private fun noPreviewFoundMessage(classesDir: Path): String {
    val accepted = discoverer.recognisedAnnotationFqns.joinToString(", ")
    val found =
      try {
        discoverer.unrecognisedPreviewAnnotations(classesDir)
      } catch (t: Throwable) {
        emptyList()
      }
    return if (found.isEmpty()) {
      "no @Preview found — a playground snippet must declare one @Preview composable, " +
        "imported from one of: $accepted"
    } else {
      "no renderable @Preview found — the snippet declares " +
        found.joinToString(", ") { "@$it" } +
        ", which this host does not render; import @Preview from one of: $accepted"
    }
  }

  private fun publishCompiled(
    mode: PlaygroundMode,
    classpath: Classpath,
    classesDir: Path,
    workDir: Path,
    diagnostics: List<PlaygroundDiagnostic>,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val renderClasspath = classpath.entries + classesDir
    // Sorted, since ClassGraph's scan order isn't guaranteed and multi-file snippets often declare
    // several previews; the full list rides the response.
    val previews = discoverer.discover(classesDir, renderClasspath).sorted()
    if (previews.isEmpty()) {
      val message = noPreviewFoundMessage(classesDir)
      cleanup(workDir)
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = PlaygroundErrorsWire.project(diagnostics),
        exception = message,
      )
    }

    val snippet =
      PlaygroundTokenStore.PlaygroundSnippet(
        mode = mode,
        workDir = workDir,
        classesDir = classesDir,
        classpath = renderClasspath,
        moduleName = classpath.moduleName,
        previewId = previews.first(),
        // Carry all of them: the still draws the first, but the live session lists every one for
        // navigation.
        previewIds = previews,
      )

    if (mode == PlaygroundMode.REMOTE_COMPOSE) {
      return remoteComposeResult(snippet, previews, diagnostics, workDir, isSecurityChecked)
    }

    val frame = renderFirstFrameWithReason(snippet)
    val image = frame.png?.let(::toDataUri)
    // From here the token owns workDir; do NOT cleanup on this path.
    val token = tokenStore.add(snippet, isSecurityChecked = isSecurityChecked)
    return PlaygroundRunResponse(
      diagnostics = diagnostics,
      errors = PlaygroundErrorsWire.project(diagnostics),
      // A server-side failure that is not a compile error, which is what the field is for. The
      // token is still minted: the snippet compiled, and a live session may yet draw it.
      exception = frame.failure?.let { "$FIRST_FRAME_FAILED$it" },
      image = image,
      previewToken = token.id,
      previewUrl = token.path,
      previewId = snippet.previewId,
      previews = previews,
    )
  }

  /**
   * [PlaygroundMode.REMOTE_COMPOSE] terminal: capture the `.rc` and publish it as an expiring
   * `/d/<id>` permalink. No daemon session (the document is the deliverable), so the work dir is
   * released immediately and no token is minted; no document or a refused store is a clean failure.
   */
  private fun remoteComposeResult(
    snippet: PlaygroundTokenStore.PlaygroundSnippet,
    previews: List<String>,
    diagnostics: List<PlaygroundDiagnostic>,
    workDir: Path,
    isSecurityChecked: Boolean,
  ): PlaygroundRunResponse {
    val errors = PlaygroundErrorsWire.project(diagnostics)
    val bytes = captureRemoteDocument(snippet)
    // The capture reads the compiled classes, then RC is done with them — it never stands up a live
    // session — so the work dir goes now regardless of what the capture produced.
    cleanup(workDir)
    if (bytes == null) {
      return PlaygroundRunResponse(
        diagnostics = diagnostics,
        errors = errors,
        exception =
          "no Remote Compose document was captured — a remote-compose snippet must declare an " +
            "@Preview that emits a RemoteDocument",
      )
    }
    val url =
      publishRemoteDocument(documentLabel(snippet), bytes, isSecurityChecked)
        ?: return PlaygroundRunResponse(
          diagnostics = diagnostics,
          errors = errors,
          exception = "remote-compose documents are not accepted on this host",
        )
    return PlaygroundRunResponse(
      diagnostics = diagnostics,
      errors = errors,
      documentUrl = url,
      previewId = snippet.previewId,
      previews = previews,
    )
  }

  /**
   * A short, human label for the published document — the preview's simple name, `.rc`-suffixed.
   */
  private fun documentLabel(snippet: PlaygroundTokenStore.PlaygroundSnippet): String =
    snippet.previewId.substringAfterLast('.').ifBlank { "snippet" } + ".rc"

  /**
   * Write each file to [srcDir] under a safe, unique `.kt` name; return the staged source paths.
   */
  private fun stageSources(files: List<PlaygroundFile>, srcDir: Path): List<Path> {
    val used = mutableSetOf<String>()
    return files.map { file ->
      val name = uniqueName(safeKtName(file.name), used)
      val path = srcDir / name
      fileSystem.write(path) { writeUtf8(file.text) }
      path
    }
  }

  private fun stagedFileMap(files: List<PlaygroundFile>): Map<String, String> {
    val used = mutableSetOf<String>()
    return buildMap {
      files.forEach { file -> put(uniqueName(safeKtName(file.name), used), file.text) }
    }
  }

  /** Restore the source tree after admission rejects a request before compilation starts. */
  private fun restoreStagedFiles(
    srcDir: Path,
    accepted: Map<String, String>,
    staged: Map<String, String>,
  ) {
    (staged.keys - accepted.keys).forEach { name ->
      fileSystem.delete(srcDir / name, mustExist = false)
    }
    accepted.forEach { (name, text) ->
      if (staged[name] != text) fileSystem.write(srcDir / name) { writeUtf8(text) }
    }
  }

  private fun copyDirectory(from: Path, to: Path) {
    check(fileSystem.metadataOrNull(from)?.isDirectory == true) {
      "compiled classes directory is missing: $from"
    }
    fileSystem.createDirectories(to)
    fileSystem.list(from).forEach { source ->
      val target = to / source.name
      if (fileSystem.metadata(source).isDirectory) {
        copyDirectory(source, target)
      } else {
        fileSystem.copy(source, target)
      }
    }
  }

  private fun List<PlaygroundDiagnostic>.isInfrastructureCompileFailure(): Boolean =
    size == 1 &&
      single().file == null &&
      (single().message.startsWith("compilation failed:") ||
        single().message.startsWith("compile sandbox error:") ||
        single().message.startsWith("compile sandbox failed:") ||
        single().message.startsWith("could not launch the compile sandbox") ||
        single().message.startsWith("the compile sandbox produced no result") ||
        single().message.startsWith("unreadable compile report:"))

  private fun List<PlaygroundDiagnostic>.isCompileAdmissionFailure(): Boolean =
    size == 1 &&
      single().file == null &&
      single().message.startsWith("the playground is busy compiling")

  private fun cleanup(workDir: Path) {
    try {
      fileSystem.deleteRecursively(workDir, mustExist = false)
    } catch (_: Exception) {
      // Best-effort — leaking one temp dir beats throwing out of an error path.
    }
  }

  private fun failure(message: String) = PlaygroundRunResponse(exception = message)

  companion object {
    const val DEFAULT_EDIT_LEASE_TTL_MILLIS: Long = 15 * 60 * 1000L
    private const val MAX_EDIT_LEASE_CLIENT_LENGTH = 128
    private const val MAX_EDIT_LEASE_HOLDERS = 32

    private val EDIT_LEASE_RANDOM = SecureRandom()
    private val EDIT_LEASE_EXPIRY_EXECUTOR =
      ScheduledThreadPoolExecutor(1) { runnable ->
          Thread(runnable, "playground-edit-lease-expiry").apply { isDaemon = true }
        }
        .apply {
          // Renewals cancel the old deadline; remove it immediately rather than retaining a
          // cancelled task in the delayed queue until the original TTL elapses.
          removeOnCancelPolicy = true
        }

    private fun newEditLeaseId(): String {
      val bytes = ByteArray(18)
      EDIT_LEASE_RANDOM.nextBytes(bytes)
      return "pge_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Reduce a client-supplied name to a safe `.kt` filename (last segment, printable, non-empty).
     */
    internal fun safeKtName(raw: String): String {
      val base =
        raw
          .substringAfterLast('/')
          .substringAfterLast('\\')
          .filter { it.isLetterOrDigit() || it in "._-" }
          .removeSuffix(".kt")
          .takeIf { it.isNotBlank() } ?: "Snippet"
      return "$base.kt"
    }

    /**
     * Make the name unique within a run by appending `_<n>` before `.kt`. Case-folded so
     * case-insensitive filesystems can't silently overwrite `A.kt` with `a.kt`; harmless on
     * case-sensitive ones.
     */
    private fun uniqueName(name: String, usedLowercase: MutableSet<String>): String {
      if (usedLowercase.add(name.lowercase())) return name
      val stem = name.removeSuffix(".kt")
      var i = 1
      while (true) {
        val candidate = "${stem}_$i.kt"
        if (usedLowercase.add(candidate.lowercase())) return candidate
        i++
      }
    }

    internal fun toDataUri(png: ByteArray): String =
      "data:image/png;base64," + Base64.getEncoder().encodeToString(png)

    /**
     * How [PlaygroundRunResponse.exception] begins when the snippet compiled but its first frame
     * failed; the page keys on it to keep the live-preview link.
     */
    internal const val FIRST_FRAME_FAILED: String = "compiled, but the first frame failed: "
  }
}

/**
 * A first-frame render: the PNG, or why there is none. [failure] is null on success and when no
 * renderer is wired.
 */
class PlaygroundFirstFrame(val png: ByteArray?, val failure: String? = null)
