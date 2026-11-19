package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.web.WebEscaping
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Everything the push routes need from the host. Null on a host without the UI builder's durable
 * state: there is then nothing to publish and nowhere to keep a device.
 */
class WatchFacePush
internal constructor(
  internal val store: WatchFacePushStore,
  internal val packaging: () -> WatchFacePushPackaging,
  /** The WFF a design exports ([deriveDwfSource] in production); null when it does not. */
  internal val sourceFor: (DesignDocumentV1) -> DwfSource?,
  /** Whether a builder catalog draws watch faces (its native backend is `android-dwf`). */
  internal val isWatchFaceCatalog: (catalogId: String) -> Boolean,
  /** A picture of a design at a revision, drawn as [AuthenticatedUiBuilderActor]; null if none. */
  internal val preview:
    suspend (actor: AuthenticatedUiBuilderActor, designId: String, revision: Long) -> ByteArray? =
    { _, _, _ ->
      null
    },
  /** Where a signed-out person signs in and comes back to the given path; null: no sign-in. */
  internal val signInPath: ((returnTo: String) -> String)? = null,
  internal val clock: () -> Long = System::currentTimeMillis,
  /**
   * One design's owner, revision and catalog, as the administrator sees it; the automatic listing
   * of public watch faces reads it, one design at a time ([WatchFacePushAutomatic]). Null: no such
   * design (and, by default, nothing is listed automatically).
   */
  internal val designSummary: (designId: String) -> UiBuilderAdminDesignSummary? = { null },
  /** How often the automatic listing's scheduler wakes; null: never on its own. */
  automaticTickMillis: Long? = WatchFacePushAutomatic.DEFAULT_TICK_MILLIS,
  /** `watchFacePushBuilds`: whether public designs are built on their own. Off by default. */
  automaticBuilds: Boolean = false,
  /** The designs on disk, for the automatic listing's one startup sweep. */
  storedDesigns: () -> List<WatchFacePushStoredDesigns.Head> = { emptyList() },
  /** The pause between two designs the startup sweep looks at. */
  automaticSweepPauseMillis: Long = WatchFacePushAutomatic.DEFAULT_SWEEP_PAUSE_MILLIS,
  /**
   * Where a design's uploaded asset bytes are, so a published face carries the drawables its WFF
   * names ([WatchFaceDrawables]).
   */
  internal val assets: UiBuilderAssetStore? = null,
) {
  internal val codes = WatchFacePushDeviceCodes(store, clock)
  internal val builds = WatchFacePushBuilds(store, packaging, clock = clock)
  internal val catalogFaces = WatchFacePushCatalogFaces(store.catalogFacesFile)
  /** The one queue every build here runs through, with each design's progress. */
  internal val queue = WatchFacePushBuildQueue(clock)
  internal val automatic =
    WatchFacePushAutomatic(
      this,
      enabled = automaticBuilds,
      storedDesigns = storedDesigns,
      sweepPauseMillis = automaticSweepPauseMillis,
      tickMillis = automaticTickMillis,
    )

  private val reconciling = java.util.concurrent.atomic.AtomicBoolean(false)

  /** The sources [startReconciling] was given, for [reconcileNow]. */
  @Volatile private var reconcileSources: () -> List<CatalogFaceSource> = { emptyList() }

  @Volatile private var reconciler: java.util.concurrent.ScheduledExecutorService? = null

  /**
   * Stop the reconcile pass and the build worker, when the server that installed the routes stops;
   * neither then holds the stopped server or its state directory.
   */
  internal fun close() {
    reconciler?.shutdownNow()
    reconciler = null
    reconcileSources = { emptyList() }
    builds.close()
    // The owner build queue and the automatic listing's scheduler, which runs on its thread.
    queue.close()
  }

  /** One reconcile pass now, against the sources the routes were installed with. */
  internal fun reconcileNow(): Int = reconcile(reconcileSources)

  /**
   * Queue a background build for everything a watch could be offered and has no APK: a published
   * design whose APK is missing (a lost cache) and a catalog face whose digest is new (an import, a
   * republished catalog, a first start). Cheap and never packages on this thread, so it is safe to
   * run on a timer. Returns how many builds were queued.
   */
  internal fun reconcile(catalogFaceSources: () -> List<CatalogFaceSource>): Int {
    var queued = 0
    for (publication in store.published()) {
      if (builds.enqueue(publication, cause = "reconcile: unbuilt publication")) queued++
    }
    for (face in catalogFaces.resolve(catalogFaceSources()).faces) {
      if (builds.enqueue(face.publication, { face.dwf }, cause = "catalog face digest changed")) {
        queued++
      }
    }
    return queued
  }

  /**
   * Run [reconcile] shortly after start and then every [periodMillis], on one daemon thread. Only
   * the first call starts it; later ones (a second route install) are no-ops.
   */
  internal fun startReconciling(
    catalogFaceSources: () -> List<CatalogFaceSource>,
    initialDelayMillis: Long = 15_000L,
    periodMillis: Long = 60_000L,
  ) {
    if (!reconciling.compareAndSet(false, true)) return
    reconcileSources = catalogFaceSources
    val executor =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "watchface-push-reconcile").apply { isDaemon = true }
      }
    reconciler = executor
    executor.scheduleWithFixedDelay(
      {
        try {
          val queued = reconcile(catalogFaceSources)
          if (queued > 0) {
            System.err.println(
              "serve: watchface-push: queued $queued APK build(s) in the background",
            )
          }
        } catch (e: Exception) {
          System.err.println("serve: watchface-push: reconcile failed: $e")
        }
      },
      initialDelayMillis,
      periodMillis,
      java.util.concurrent.TimeUnit.MILLISECONDS,
    )
  }

  internal val csrf = ServeAgentGrants.Csrf()

  /**
   * The owner's own publication calls (`…/watch-face-publication`), per account: a publish can run
   * the packager, so a person (or a page left polling) gets thirty a minute and two at once.
   */
  internal val publicationLimiter =
    ServeRateLimiter(permitsPerWindow = 30, windowSeconds = 60, maxConcurrent = 2, clock = clock)

  /** Starting a link: a person does it once per watch, so ten a minute per address is plenty. */
  internal val codeLimiter =
    ServeRateLimiter(permitsPerWindow = 10, windowSeconds = 60, maxConcurrent = 4, clock = clock)

  /** Polls, per address; each code is paced on its own by [WatchFacePushDeviceCodes]. */
  internal val pollLimiter =
    ServeRateLimiter(permitsPerWindow = 120, windowSeconds = 60, maxConcurrent = 8, clock = clock)

  /** Anonymous reads (listing, APKs, pictures), per address. */
  internal val anonymousLimiter =
    ServeRateLimiter(permitsPerWindow = 60, windowSeconds = 60, maxConcurrent = 4, clock = clock)

  /** Anonymous APK downloads, per address, on top of [anonymousLimiter]: they are the big ones. */
  internal val anonymousApkLimiter =
    ServeRateLimiter(permitsPerWindow = 20, windowSeconds = 60, maxConcurrent = 2, clock = clock)

  /** Reads with a device token, per device. */
  internal val deviceLimiter =
    ServeRateLimiter(permitsPerWindow = 240, windowSeconds = 60, maxConcurrent = 4, clock = clock)

  // ---------------------------------------------------------------- publishing

  sealed interface Outcome {
    data class Ok(val status: WatchFacePushPublicationV1) : Outcome

    /** Queued to build in the background: `202`, with the status as it is now. */
    data class Accepted(val status: WatchFacePushPublicationV1) : Outcome

    data class Refused(
      val status: HttpStatusCode,
      val code: String,
      val reason: String,
      /** The packager's (or validator's) own lines, for a face it refused. */
      val diagnostics: List<String> = emptyList(),
    ) : Outcome
  }

  /** What [stage] did with a candidate. */
  internal sealed interface Staged {
    data class Committed(
      val publication: WatchFacePushStore.Publication,
      val build: WatchFacePushBuilds.Result,
    ) : Staged

    /** The packager refused it; nothing was stored. */
    data class Refused(val diagnostics: List<String>, val retryable: Boolean = false) : Staged

    /** This host cannot build, and the caller asked for a built face; nothing was stored. */
    data class Unavailable(val build: WatchFacePushBuilds.Result.Unavailable) : Staged

    /** What is stored now may not be replaced (an explicit publication, for the automatic one). */
    data object Superseded : Staged

    /** Concurrent publishes kept moving the version. */
    data object Conflict : Staged
  }

  /**
   * The one publish path, for the owner's routes, the MCP tool and the automatic listing alike:
   * build [candidate] first, then commit it only at the version that was built. A face the packager
   * refuses leaves the release already on watches exactly as it was; a concurrent publish of the
   * same design that moves the stored version sends this one round again, so the APK on disk always
   * carries the versionCode its listing says, and no version is ever stored twice or lowered.
   */
  internal suspend fun stage(
    candidate: () -> WatchFacePushStore.Publication,
    replaces: (WatchFacePushStore.Publication?) -> Boolean = { true },
    requireBuild: Boolean = false,
    /**
     * An owner's publish skips a transient failure's backoff; the automatic listing waits it out.
     */
    retryTransient: Boolean = false,
    cause: String = "publish",
  ): Staged {
    repeat(PUBLISH_ATTEMPTS) {
      val next = withContext(Dispatchers.IO) { candidate() }
      val build =
        withContext(Dispatchers.IO) {
          builds.build(next, retryTransient = retryTransient, cause = cause)
        }
      when (build) {
        is WatchFacePushBuilds.Result.Failed ->
          return Staged.Refused(build.diagnostics, build.retryable)
        is WatchFacePushBuilds.Result.Unavailable ->
          if (requireBuild) return Staged.Unavailable(build)
        is WatchFacePushBuilds.Result.Built -> Unit
      }
      // Once the server stops, a build that was already running is not committed.
      withContext(Dispatchers.IO) {
          store.commitIfBuiltVersion(next) { !queue.closed && replaces(it) }
        }
        ?.let {
          return Staged.Committed(it, build)
        }
      if (queue.closed || !replaces(store.publication(next.designId))) return Staged.Superseded
    }
    return Staged.Conflict
  }

  /**
   * Publish [designId]'s current revision to watches, as its owner. Builds the APK straight away
   * when this host can, so a face the packager refuses is refused here, to the person who can fix
   * it, with the packager's own diagnostics — not silently left out of every watch's list.
   */
  /** A publish that passed every check made before building: what to build. */
  private class Ready(val designId: String, val candidate: () -> WatchFacePushStore.Publication)

  /**
   * Everything a publish checks before the packager: the owner, the catalog, the visibility, the
   * export and its drawables. A refusal here is immediate (a `422`), with nothing queued.
   */
  private suspend fun prepare(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    visibility: String?,
  ): Any {
    require(visibility == null || visibility in VISIBILITIES) { "visibility $visibility" }
    val (document, owner) =
      when (val owned = ownedDocument(service, actor, designId)) {
        is Owned.Refused -> return owned.outcome
        is Owned.Document -> owned.document to owned.ownerActorId
      }
    // A public face is one every watch may install, so it needs a design anyone may read: the
    // design's own visibility is the owner's decision, made in the editor, not here.
    if (visibility == VISIBILITY_PUBLIC && !service.canRead(ANONYMOUS, designId)) {
      return Outcome.Refused(
        HttpStatusCode.UnprocessableEntity,
        "design_not_public",
        "this design is private; make it public first, or publish it to your own watches as private",
      )
    }
    val exported =
      sourceFor(document)
        ?: return Outcome.Refused(
          HttpStatusCode.UnprocessableEntity,
          "not_exportable",
          "this design does not export to Watch Face Format; the editor's code pane lists why",
        )
    val packaged = packagedWithPinnedDrawables(document, exported, emptyMap(), assets)
    if (packaged.missing.isNotEmpty()) {
      return Outcome.Refused(
        HttpStatusCode.UnprocessableEntity,
        WatchFaceDrawables.MISSING_RESOURCE,
        packaged.missing.joinToString("\n"),
      )
    }
    val source = packaged.source
    source.problem()?.let {
      return Outcome.Refused(HttpStatusCode.UnprocessableEntity, "not_exportable", it)
    }
    // The owner's account, not the caller's: an agent publishing for its approver publishes to
    // the approver's watches.
    return Ready(designId) {
      store.candidate(
        designId,
        owner,
        document.revision,
        document.title,
        source,
        document.createdAtEpochMillis,
        visibility = visibility,
      )
    }
  }

  /**
   * Each design's owner-publish generation: an unpublish moves it, so a publish queued before the
   * unpublish neither builds nor commits after it. Checked under the store's lock at commit.
   */
  private val generations = java.util.concurrent.ConcurrentHashMap<String, Long>()

  private fun generation(designId: String): Long = generations[designId] ?: 0L

  /** Build and commit [ready]; the caller holds the build queue's lock. */
  private suspend fun finish(
    service: UiBuilderServicePort,
    ready: Ready,
    replaces: (WatchFacePushStore.Publication?) -> Boolean = { true },
  ): Outcome {
    val designId = ready.designId
    return when (
      val staged = stage(ready.candidate, replaces, retryTransient = true, cause = "publish")
    ) {
      is Staged.Committed -> {
        refusals.remove(designId)
        automatic.forget(designId)
        Outcome.Ok(status(service, staged.publication, staged.build))
      }
      is Staged.Refused -> {
        refusals[designId] = staged.diagnostics
        Outcome.Refused(
          HttpStatusCode.UnprocessableEntity,
          "packaging_failed",
          staged.diagnostics.joinToString("\n"),
          staged.diagnostics,
        )
      }
      Staged.Superseded ->
        Outcome.Refused(HttpStatusCode.Conflict, SUPERSEDED, "unpublished before it was built")
      is Staged.Unavailable,
      Staged.Conflict ->
        Outcome.Refused(
          HttpStatusCode.Conflict,
          "publish_conflict",
          "this design is being published concurrently; try again",
        )
    }
  }

  /**
   * Publish [designId]'s current revision now and wait for the build: the MCP tool's path, whose
   * caller wants the result in its reply. It still takes its turn in the one build queue.
   */
  internal suspend fun publish(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    /** [VISIBILITY_PUBLIC], [VISIBILITY_PRIVATE], or null to follow the design's own. */
    visibility: String? = null,
  ): Outcome =
    when (val prepared = prepare(service, actor, designId, visibility)) {
      is Outcome.Refused -> prepared
      is Ready -> queue.exclusive.withLock { finish(service, prepared) }
      else -> error("prepare answered $prepared")
    }

  /**
   * Publish [designId]'s current revision in the background: the owner's route. Every check that
   * can refuse up front does, as a `422`; otherwise the build is queued and this answers at once
   * ([Outcome.Accepted]), its progress on the publication's `build`.
   */
  internal suspend fun publishInBackground(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
    visibility: String? = null,
  ): Outcome {
    val ready =
      when (val prepared = prepare(service, actor, designId, visibility)) {
        is Outcome.Refused -> return prepared
        is Ready -> prepared
        else -> error("prepare answered $prepared")
      }
    // An unpublish after this answers cancels it: not built if it has not started, not committed
    // if it has.
    val generation = generation(designId)
    val current = { generation(designId) == generation }
    queue.submit(designId) {
      if (!current()) return@submit null
      when (val outcome = finish(service, ready) { current() }) {
        is Outcome.Ok ->
          WatchFacePushBuildStatusV1(
            WatchFacePushBuildQueue.BUILT,
            version = outcome.status.version,
          )
        is Outcome.Refused ->
          if (outcome.code == SUPERSEDED) null
          else if (outcome.code == "packaging_failed") {
            WatchFacePushBuildStatusV1(
              WatchFacePushBuildQueue.REFUSED,
              reason = "the packager refused it",
              diagnostics = outcome.diagnostics,
            )
          } else WatchFacePushBuildStatusV1(WatchFacePushBuildQueue.FAILED, reason = outcome.reason)
        is Outcome.Accepted -> error("a build answered accepted")
      }
    }
    val now =
      when (val current = publicationStatus(service, actor, designId)) {
        is Outcome.Ok -> current.status
        else -> unpublishedStatus(designId)
      }
    return Outcome.Accepted(now)
  }

  internal suspend fun unpublish(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
  ): Outcome {
    val owned =
      when (val owned = ownedDocument(service, actor, designId)) {
        is Owned.Refused -> return owned.outcome
        is Owned.Document -> owned
      }
    // Any publish still queued or building is cancelled: the owner's last word wins.
    generations.merge(designId, 1L, Long::plus)
    // Bound to this owner and this design, and written even before anything was built: for a
    // public design it is the owner's opt-out of the automatic listing.
    val updated =
      withContext(Dispatchers.IO) {
        store.unpublish(designId, owned.ownerActorId, owned.document.createdAtEpochMillis)
      }
    refusals.remove(designId)
    automatic.forget(designId)
    return Outcome.Ok(
      updated?.let { status(service, it, null) } ?: unpublishedStatus(designId),
    )
  }

  /**
   * Where [designId] is on watches, for its owner: published or not, how, at which version, and the
   * packager's last word on it.
   */
  internal suspend fun publicationStatus(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
  ): Outcome {
    val owned =
      when (val owned = ownedDocument(service, actor, designId)) {
        is Owned.Refused -> return owned.outcome
        is Owned.Document -> owned
      }
    val publication =
      store.publication(designId)?.takeIf {
        it.isExplicitFor(owned.ownerActorId, owned.document.createdAtEpochMillis) ||
          (it.automatic && isSameDesign(service, it))
      }
    val status =
      if (publication == null) unpublishedStatus(designId)
      else status(service, publication, builds.cached(publication) ?: builds.failure(publication))
    // The packager's last word when it refused the latest attempt — the owner's publish, or the
    // automatic listing of a public design — beside whatever is still on watches.
    val refusal = refusals[designId] ?: automatic.refusal(designId)
    return Outcome.Ok(status.copy(refusal = refusal))
  }

  /** The packager's diagnostics for each design's last refused publish, until one succeeds. */
  private val refusals = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

  private fun unpublishedStatus(designId: String) =
    WatchFacePushPublicationV1(
      designId = designId,
      published = false,
      faceName = WatchFacePushNaming.faceName(designId),
      packageName = WatchFacePushNaming.packageName(WatchFacePushNaming.faceName(designId)),
      packaging = packagingStatus().takeIf { !it.available },
      build = queue.status(designId),
    )

  internal sealed interface Installs {
    data class Ok(val installs: WatchFacePushInstallsV1) : Installs

    data class Refused(val refused: Outcome.Refused) : Installs
  }

  /** [designId]'s latest install report from each of its owner's linked watches; owner only. */
  internal suspend fun installs(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
  ): Installs {
    val owner =
      when (val owned = ownedDocument(service, actor, designId)) {
        is Owned.Refused -> return Installs.Refused(owned.outcome)
        is Owned.Document -> owned.ownerActorId
      }
    val reports = withContext(Dispatchers.IO) { store.installs(designId, owner) }
    return Installs.Ok(
      WatchFacePushInstallsV1(
        reports.map { (device, event) -> WatchFacePushInstalls.wire(device, event) },
      ),
    )
  }

  private sealed interface Owned {
    data class Document(val document: DesignDocumentV1, val ownerActorId: String) : Owned

    data class Refused(val outcome: Outcome.Refused) : Owned
  }

  /**
   * The design as its owner reads it. Publishing to watches is the owner's decision, like making a
   * design public: the runtime only tells the owner who the owner is, so a design whose access list
   * comes back is one this actor owns (or acts for the owner of).
   */
  private suspend fun ownedDocument(
    service: UiBuilderServicePort,
    actor: AuthenticatedUiBuilderActor,
    designId: String,
  ): Owned {
    val notFound =
      Owned.Refused(Outcome.Refused(HttpStatusCode.NotFound, "not_found", "no such design"))
    if (designId.isBlank()) return notFound
    val actions = service.designActions(actor, designId) ?: return notFound
    val snapshot =
      service.executeMapped(GetSnapshotRequestV1(designId = designId), actor)
        as? UiBuilderServiceResponse.Snapshot ?: return notFound
    val owner = snapshot.snapshot.access?.ownerActorId
    if (
      DesignAccessActionV1.WRITE !in actions || owner == null || owner !in actor.accessIdentities
    ) {
      return Owned.Refused(
        Outcome.Refused(
          HttpStatusCode.Forbidden,
          "owner_required",
          "only the design's owner can publish it to watches",
        ),
      )
    }
    val document = snapshot.snapshot.state.document
    if (!isWatchFaceCatalog(document.catalogPin.systemId)) {
      return Owned.Refused(
        Outcome.Refused(
          HttpStatusCode.UnprocessableEntity,
          "not_a_watch_face",
          "catalog `${document.catalogPin.systemId}` does not draw watch faces, so this design " +
            "cannot be installed on a watch",
        ),
      )
    }
    return Owned.Document(document, owner)
  }

  internal suspend fun status(
    service: UiBuilderServicePort,
    publication: WatchFacePushStore.Publication,
    build: WatchFacePushBuilds.Result?,
  ): WatchFacePushPublicationV1 =
    WatchFacePushPublicationV1(
      designId = publication.designId,
      published = publication.published,
      visibility = if (publication.published) effectiveVisibility(service, publication) else null,
      requestedVisibility = publication.visibility,
      automatic = publication.automatic,
      packaging = packagingStatus().takeIf { !it.available },
      build = queue.status(publication.designId),
      // Version 0 is an opt-out recorded before anything was built: nothing was ever shipped.
      version = publication.version.takeIf { it > 0 },
      revision = publication.revision.takeIf { publication.version > 0 },
      title = publication.title,
      faceName = publication.faceName,
      packageName = WatchFacePushNaming.packageName(publication.faceName),
      wffVersion = publication.wffVersion,
      publishedAtEpochMillis = publication.publishedAtEpochMillis,
      apk =
        when (build) {
          is WatchFacePushBuilds.Result.Built ->
            WatchFacePushApkStatusV1(
              status = "ready",
              sha256 = build.apk.sha256,
              sizeBytes = build.apk.sizeBytes,
            )
          is WatchFacePushBuilds.Result.Unavailable ->
            WatchFacePushApkStatusV1(
              status = "unavailable",
              code = build.code,
              reason = build.reason,
            )
          is WatchFacePushBuilds.Result.Failed ->
            WatchFacePushApkStatusV1(
              status = "failed",
              code = "packaging_failed",
              reason = build.diagnostics.joinToString("\n"),
              diagnostics = build.diagnostics,
            )
          null ->
            when {
              !publication.published -> null
              else ->
                when (val packaging = packaging()) {
                  // Not built because this host cannot build: say so, not "pending".
                  is WatchFacePushPackaging.Unavailable ->
                    WatchFacePushApkStatusV1(
                      status = "unavailable",
                      code = packaging.code,
                      reason = packaging.reason,
                    )
                  is WatchFacePushPackaging.Ready -> WatchFacePushApkStatusV1(status = "pending")
                }
            }
        },
    )

  // ------------------------------------------------------------------- reading

  /**
   * How a caller reaches [publication], or null when they may not: `public` for a design whose
   * owner made it public (anyone, no token), `private` for one only the linked account reads.
   */
  internal suspend fun reach(
    service: UiBuilderServicePort,
    publication: WatchFacePushStore.Publication,
    accountActorId: String?,
  ): String? {
    if (!publication.published || !isSameDesign(service, publication)) return null
    if (
      publication.visibility != VISIBILITY_PRIVATE &&
        service.canRead(ANONYMOUS, publication.designId)
    ) {
      return VISIBILITY_PUBLIC
    }
    // Listed only because it was public: once it is not, it is on nobody's watch.
    if (publication.automatic) return null
    if (
      accountActorId != null &&
        publication.accountActorId == accountActorId &&
        service.canRead(AuthenticatedUiBuilderActor(accountActorId), publication.designId)
    ) {
      return VISIBILITY_PRIVATE
    }
    return null
  }

  /**
   * Who a published [publication] reaches now: `public` (every watch), `private` (its owner's
   * linked watches), or null (none: an automatic listing whose design is no longer public).
   */
  private suspend fun effectiveVisibility(
    service: UiBuilderServicePort,
    publication: WatchFacePushStore.Publication,
  ): String? =
    when {
      publication.visibility != VISIBILITY_PRIVATE &&
        service.canRead(ANONYMOUS, publication.designId) -> VISIBILITY_PUBLIC
      publication.automatic -> null
      else -> VISIBILITY_PRIVATE
    }

  /**
   * Whether the design now at [publication]'s id is the one that was published: still owned by the
   * account that published it, and created when it was. Asked as that owner, since the runtime
   * names a design's owner only to its owner. A design deleted and recreated under the same id — by
   * another account, or made public — is a different design and gets nothing of this one.
   */
  internal suspend fun isSameDesign(
    service: UiBuilderServicePort,
    publication: WatchFacePushStore.Publication,
  ): Boolean {
    if (publication.accountActorId.isBlank()) return false
    val snapshot =
      service.executeMapped(
        GetSnapshotRequestV1(designId = publication.designId),
        AuthenticatedUiBuilderActor(publication.accountActorId),
      ) as? UiBuilderServiceResponse.Snapshot ?: return false
    if (snapshot.snapshot.access?.ownerActorId != publication.accountActorId) return false
    val created = publication.designCreatedAtEpochMillis ?: return true
    return snapshot.snapshot.state.document.createdAtEpochMillis == created
  }

  companion object {
    const val BASE_PATH = "/api/watchface-push/v1"
    private const val PUBLISH_ATTEMPTS = 5
    const val LINK_PATH = "/link"
    const val PUBLICATION_PATH = "/api/ui-builder/v1/designs/{designId}/watch-face-publication"
    const val INSTALLS_PATH = "/api/ui-builder/v1/designs/{designId}/watch-installs"
    const val VISIBILITY_PUBLIC = "public"
    const val VISIBILITY_PRIVATE = "private"
    val VISIBILITIES = setOf(VISIBILITY_PUBLIC, VISIBILITY_PRIVATE)
    private const val SUPERSEDED = "superseded"
    internal val ANONYMOUS =
      AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID)
  }
}

// ---------------------------------------------------------------------- wire

@Serializable
internal data class WatchFacePushDeviceCodeRequestV1(
  val deviceName: String? = null,
  val wearOsApi: Int? = null,
)

@Serializable
internal data class WatchFacePushDeviceCodeV1(
  val code: String,
  val pollSecret: String,
  val verificationUrl: String,
  val expiresInSeconds: Int,
  val pollIntervalSeconds: Int,
)

@Serializable internal data class WatchFacePushPollRequestV1(val pollSecret: String? = null)

@Serializable
internal data class WatchFacePushPollResponseV1(
  val status: String,
  val deviceToken: String? = null,
  val accountName: String? = null,
)

@Serializable
internal data class WatchFacePushDesignV1(
  val id: String,
  val title: String,
  val description: String? = null,
  /**
   * Present on every built entry. With `?includePending=1` an entry not built yet may lack it, and
   * lacks every install field below ([version], [apkUrl], [sha256], [sizeBytes],
   * [validationToken]): it is shown with its [build], not offered.
   */
  val previewUrl: String? = null,
  /** The newest BUILT version; absent on an entry not built yet. */
  val version: Long? = null,
  val versionName: String? = null,
  /** Absent only on an unbuilt entry whose face has not been exported yet. */
  val wffVersion: Int? = null,
  val packageName: String,
  val apkUrl: String? = null,
  val sha256: String? = null,
  val sizeBytes: Long? = null,
  val validationToken: String? = null,
  /** Not in the app's model (it ignores unknown keys): `public` or `private`. */
  val visibility: String? = null,
  /** `design` (a UI-builder design) or `catalog` (a face from a served catalog). */
  val source: String? = null,
  /** For `source: catalog`: the catalog it comes from. */
  val catalog: WatchFacePushCatalogV1? = null,
  /** For `source: catalog`: where the face's source lives and under which licence. */
  val attribution: WatchFacePushAttributionV1? = null,
  /**
   * With `?includePending=1` only: the newest REQUESTED build — pending, queued, building, built,
   * refused or failed — while the top-level fields stay the newest built one. Absent: built.
   */
  val build: WatchFacePushBuildStatusV1? = null,
)

@Serializable internal data class WatchFacePushCatalogV1(val system: String, val title: String)

@Serializable
internal data class WatchFacePushAttributionV1(
  val repoUrl: String? = null,
  val sourceUrl: String? = null,
  /** SPDX id (or expression), always one on [WatchFacePushLicenses.REDISTRIBUTABLE]. */
  val license: String,
)

/** `/status`'s account of the catalog faces: how many are listed, and why the rest are not. */
@Serializable
data class WatchFacePushCatalogFacesStatusV1(
  val listed: Int,
  val skipped: List<WatchFacePushSkippedFaceV1>,
  /** Faces whose APK is not built yet; the background worker builds them, never a listing. */
  val pending: List<String> = emptyList(),
)

@Serializable data class WatchFacePushSkippedFaceV1(val id: String, val reason: String)

@Serializable
internal data class WatchFacePushDesignListV1(
  val designs: List<WatchFacePushDesignV1>,
  /** Present only when this host cannot build APKs, saying why; the list is then empty. */
  val packaging: WatchFacePushStatusV1? = null,
)

@Serializable
internal data class WatchFacePushInstallEventV1(
  val designId: String,
  val version: Long,
  val result: String,
  val errorKind: String? = null,
  val wearOsApi: Int? = null,
  val packageName: String? = null,
  /** One of [WatchFacePushInstalls.ACTIVATIONS]; anything else is kept as null. */
  val activation: String? = null,
  val activationError: String? = null,
  val deviceModel: String? = null,
  val wearOsVersion: String? = null,
  /** The androidx Watch Face Push library's version string. */
  val watchFacePushApiVersion: String? = null,
  val appVersion: String? = null,
)

/** `GET /api/ui-builder/v1/designs/{id}/watch-installs`: the owner's watches' latest reports. */
@Serializable
internal data class WatchFacePushInstallsV1(val installs: List<WatchFacePushInstallV1>)

@Serializable
internal data class WatchFacePushInstallV1(
  val deviceName: String,
  val deviceModel: String? = null,
  val wearOsApi: Int? = null,
  val version: Long,
  /** The design revision [version] was published from, when it was still the published one. */
  val revisionPublished: Long? = null,
  val result: String,
  val activation: String? = null,
  val errorKind: String? = null,
  /** When the server received the report. */
  val atEpochMillis: Long,
)

internal object WatchFacePushInstalls {
  val ACTIVATIONS = setOf("activated", "not_requested", "manual", "failed")
  private const val MAX_ID = 200
  private const val MAX_TEXT = 200

  /** [event] as kept: free text truncated, an unknown activation dropped; null when unusable. */
  fun stored(event: WatchFacePushInstallEventV1): WatchFacePushStore.InstallEvent? {
    if (event.designId.isBlank() || event.designId.length > MAX_ID || event.version < 0) return null
    if (event.result.isBlank()) return null
    fun String?.clip(): String? = this?.trim()?.take(MAX_TEXT)?.takeIf { it.isNotEmpty() }
    return WatchFacePushStore.InstallEvent(
      deviceId = "",
      accountActorId = "",
      designId = event.designId,
      version = event.version,
      result = event.result.trim().take(64),
      errorKind = event.errorKind.clip()?.take(64),
      wearOsApi = event.wearOsApi,
      packageName = event.packageName.clip(),
      activation = event.activation?.trim()?.takeIf { it in ACTIVATIONS },
      activationError = event.activationError.clip(),
      deviceModel = event.deviceModel.clip(),
      wearOsVersion = event.wearOsVersion.clip()?.take(64),
      watchFacePushApiVersion = event.watchFacePushApiVersion.clip()?.take(64),
      appVersion = event.appVersion.clip()?.take(64),
      atEpochMillis = 0,
    )
  }

  fun wire(device: WatchFacePushStore.Device, event: WatchFacePushStore.InstallEvent) =
    WatchFacePushInstallV1(
      deviceName = device.deviceName,
      deviceModel = event.deviceModel,
      wearOsApi = event.wearOsApi ?: device.wearOsApi,
      version = event.version,
      revisionPublished = event.revisionPublished,
      result = event.result,
      activation = event.activation,
      errorKind = event.errorKind,
      atEpochMillis = event.atEpochMillis,
    )
}

@Serializable
data class WatchFacePushStatusV1(
  val available: Boolean,
  val code: String? = null,
  val reason: String? = null,
  val marketplacePackage: String = WatchFacePushNaming.MARKETPLACE_PACKAGE,
  val signerCertificateSha256: String? = null,
  /** On `/status` only. */
  val catalogFaces: WatchFacePushCatalogFacesStatusV1? = null,
  /** On `/status` only: how many builds are pending, queued and building. */
  val builds: WatchFacePushBuildSummaryV1? = null,
  /**
   * On `/status` only: the UI-builder designs on the anonymous list, and the public watch-face
   * designs the automatic listing could not build, each with why.
   */
  val designs: WatchFacePushCatalogFacesStatusV1? = null,
)

@Serializable
internal data class WatchFacePushErrorV1(
  val error: String,
  val message: String,
  /** For `packaging_failed`: the packager's and validator's own lines. */
  val diagnostics: List<String>? = null,
)

/** `PUT …/watch-face-publication`'s body. */
@Serializable internal data class WatchFacePushPublishRequestV1(val visibility: String? = null)

/**
 * What the editor reads to offer "Publish to watch" (on the `android-dwf` status it already
 * fetches): present only when this host can build push APKs and the design's catalog draws watch
 * faces.
 */
@Serializable
data class WatchFacePushCapabilityV1(
  /** The publication route for this design, relative to the server's origin. */
  val publicationPath: String,
  /** Whether this caller may publish it: the design's owner, in their own signed-in session. */
  val canPublish: Boolean,
)

@Serializable
internal data class WatchFacePushLinkedDeviceV1(
  val id: String,
  val deviceName: String,
  val wearOsApi: Int? = null,
  val createdAtEpochMillis: Long,
  val lastUsedEpochMillis: Long? = null,
)

@Serializable
internal data class WatchFacePushDevicesV1(val devices: List<WatchFacePushLinkedDeviceV1>)

@Serializable
data class WatchFacePushApkStatusV1(
  /** `ready`, `pending` (built on first listing), `unavailable` (this host), `failed`. */
  val status: String,
  val sha256: String? = null,
  val sizeBytes: Long? = null,
  val code: String? = null,
  val reason: String? = null,
  /** For `failed`: the packager's and validator's own lines. */
  val diagnostics: List<String>? = null,
)

@Serializable
data class WatchFacePushPublicationV1(
  val schema: String = "compose-preview/watchface-push-publication/v1",
  val designId: String,
  val published: Boolean,
  val version: Long? = null,
  val revision: Long? = null,
  val title: String? = null,
  val faceName: String? = null,
  val packageName: String? = null,
  val wffVersion: Int? = null,
  val publishedAtEpochMillis: Long? = null,
  val apk: WatchFacePushApkStatusV1? = null,
  /**
   * Who it reaches now, while published: `public` (every watch) or `private` (the owner's linked
   * watches). Null when it reaches none.
   */
  val visibility: String? = null,
  /** What the owner chose (`public` / `private`); null: follow the design's own visibility. */
  val requestedVisibility: String? = null,
  /** Listed because the design is public, without an explicit publish. */
  val automatic: Boolean = false,
  /** Present when this host cannot build APKs, saying why (the same object as `/status`). */
  val packaging: WatchFacePushStatusV1? = null,
  /**
   * The packager's and validator's lines when it refused the latest attempt (a publish, or the
   * automatic listing of a public design); what is on watches, if anything, is unchanged.
   */
  val refusal: List<String>? = null,
  /** The design's build, while one is pending or running and after it ends. */
  val build: WatchFacePushBuildStatusV1? = null,
)

internal val WATCHFACE_PUSH_JSON = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
  encodeDefaults = true
}

private const val MAX_BODY_BYTES = 16 * 1024
private const val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

/**
 * The design's picture at [revision] for a watch's list, from the listing cards' cache, drawn on
 * demand (as [actor], the design's owner) when it is not there yet; null when it cannot be drawn.
 */
internal suspend fun ServeUiBuilderThumbnails.watchFacePreview(
  actor: AuthenticatedUiBuilderActor,
  designId: String,
  revision: Long,
): ByteArray? {
  cachedRevision(designId, revision)?.let {
    return it.png
  }
  return withTimeoutOrNull(PREVIEW_DRAW_TIMEOUT_MILLIS) {
      runCatching { submit(designId, actor, urgent = false, revision = revision).await() }
        .getOrNull()
    }
    ?.png
}

private const val PREVIEW_DRAW_TIMEOUT_MILLIS = 30_000L

// -------------------------------------------------------------------- routes

/**
 * `/api/watchface-push/v1/…`, `/link`, and the publish flag under a design.
 *
 * The watch's own calls authenticate with nothing or with `Authorization: Bearer <deviceToken>`,
 * read here and never handed to [authorization]: a device token is not a UI-builder credential and
 * grants nothing outside this API. The person's calls (`/link`, the device list, publishing) go
 * through [authorization] like every other UI-builder route.
 */
internal fun Route.installWatchFacePushRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  push: WatchFacePush,
  /** The origin the caller addressed, for the absolute URLs the watch is handed. */
  origin: (ApplicationCall) -> String,
  /** Who to charge for rate limiting: the client address under the forwarding policy. */
  clientAddress: (ApplicationCall) -> String,
  /** A server-styled HTML page around [body]. */
  page: (call: ApplicationCall, title: String, body: String) -> String,
  /**
   * The served, listed catalogs' watch-face previews that carry their WFF source; read per request,
   * resident catalogs only (it never wakes one).
   */
  catalogFaceSources: () -> List<CatalogFaceSource> = { emptyList() },
  /** What the host a request addressed offers a watch ([WatchFacePushSite]). */
  site: (ApplicationCall) -> WatchFacePushSite = { WatchFacePushSite.BOTH },
) {
  // Public designs are built by the automatic listing's own scheduler, never by these routes.
  push.automatic.start(service)
  // Push APKs are built off the request path: publish builds inline, and this pass queues the rest
  // (new catalog faces, lost APKs) onto the one background worker. Listings never build.
  push.startReconciling(catalogFaceSources)
  post("${WatchFacePush.BASE_PATH}/device-codes") {
    call.limited {
      call.noStore()
      if (!call.admit(push.codeLimiter, "ip:" + clientAddress(call))) return@limited
      val request =
        call.receiveJson(WatchFacePushDeviceCodeRequestV1.serializer(), allowEmpty = true)
          ?: return@limited
      val name =
        request.deviceName
          ?.filter { !it.isISOControl() }
          ?.trim()
          ?.take(64)
          ?.takeIf { it.isNotEmpty() } ?: "Wear OS watch"
      val issued = push.codes.issue(name, request.wearOsApi?.takeIf { it in 1..1000 })
      if (issued == null) {
        call.response.headers.append(HttpHeaders.RetryAfter, "60")
        call.respondError(HttpStatusCode.TooManyRequests, "busy", "too many links are in progress")
        return@limited
      }
      val (pending, secret) = issued
      call.respondJson(
        WatchFacePushDeviceCodeV1.serializer(),
        WatchFacePushDeviceCodeV1(
          code = pending.code,
          pollSecret = secret,
          verificationUrl = origin(call) + WatchFacePush.LINK_PATH + "?code=" + pending.code,
          expiresInSeconds = push.codes.lifetimeSeconds,
          pollIntervalSeconds = push.codes.pollIntervalSeconds,
        ),
      )
    }
  }

  post("${WatchFacePush.BASE_PATH}/device-codes/{code}/poll") {
    call.limited {
      call.noStore()
      if (!call.admit(push.pollLimiter, "ip:" + clientAddress(call))) return@limited
      val request = call.receiveJson(WatchFacePushPollRequestV1.serializer()) ?: return@limited
      when (val poll = push.codes.poll(call.parameters["code"].orEmpty(), request.pollSecret)) {
        WatchFacePushDeviceCodes.Poll.NotFound ->
          call.respondError(HttpStatusCode.NotFound, "not_found", "unknown or consumed code")
        is WatchFacePushDeviceCodes.Poll.TooFast -> {
          call.response.headers.append(HttpHeaders.RetryAfter, poll.retryAfterSeconds.toString())
          call.respondError(HttpStatusCode.TooManyRequests, "slow_down", "poll less often")
        }
        WatchFacePushDeviceCodes.Poll.Pending ->
          call.respondPoll(WatchFacePushPollResponseV1("pending"))
        WatchFacePushDeviceCodes.Poll.Denied ->
          call.respondPoll(WatchFacePushPollResponseV1("denied"))
        WatchFacePushDeviceCodes.Poll.Expired ->
          call.respondPoll(WatchFacePushPollResponseV1("expired"))
        is WatchFacePushDeviceCodes.Poll.Approved ->
          call.respondPoll(
            WatchFacePushPollResponseV1("approved", poll.deviceToken, poll.accountName),
          )
      }
    }
  }

  get("${WatchFacePush.BASE_PATH}/status") {
    call.noStore()
    // Each part only on a site that lists it: designs and their builds, catalog faces.
    val offered = site(call)
    val designs =
      if (!offered.designs) null
      else {
        // Reports what the automatic listing found; it builds nothing.
        WatchFacePushCatalogFacesStatusV1(
          listed =
            push.store.published().count {
              push.reach(service, it, null) == WatchFacePush.VISIBILITY_PUBLIC
            },
          skipped =
            push.automatic.skipped(service).map { (id, reason) ->
              WatchFacePushSkippedFaceV1(id, reason)
            },
        )
      }
    val catalogFaces =
      if (!offered.catalogFaces) null
      else {
        val catalog =
          withContext(Dispatchers.IO) { push.catalogFaces.resolve(catalogFaceSources()) }
        // Only a face with a built APK is on `/designs`, so only those count as listed. One the
        // packager refused is skipped, with its first diagnostic as the reason; one not built yet
        // is pending (the background worker builds it), never built here.
        val (refused, buildable) =
          catalog.faces.partition { push.builds.refusal(it.publication) != null }
        val (listed, pending) = buildable.partition { push.builds.cached(it.publication) != null }
        WatchFacePushCatalogFacesStatusV1(
          listed = listed.size,
          pending =
            pending.map { WatchFacePushCatalogFaces.id(it.source.system, it.source.previewId) },
          skipped =
            catalog.skipped.map { WatchFacePushSkippedFaceV1(it.id, it.reason) } +
              refused.map { face ->
                val first = push.builds.refusal(face.publication)?.firstOrNull()
                WatchFacePushSkippedFaceV1(
                  WatchFacePushCatalogFaces.id(face.source.system, face.source.previewId),
                  "the packager refused it: ${first ?: "no diagnostic"}",
                )
              },
        )
      }
    call.respondJson(
      WatchFacePushStatusV1.serializer(),
      push
        .packagingStatus()
        .copy(
          catalogFaces = catalogFaces,
          designs = designs,
          builds = if (offered.designs) push.queue.summary() else null,
        ),
    )
  }

  get("${WatchFacePush.BASE_PATH}/designs") {
    call.limited {
      call.noStore()
      val caller = call.watchCaller(push, clientAddress) ?: return@limited
      if (!call.admit(caller.limiter, caller.key)) return@limited
      val packaging = push.packagingStatus()
      val offered = site(call)
      // Unbuilt entries, and each entry's build, only for a client that asks: the 0.8.0 app's model
      // requires every install field, so an entry without them would fail its whole list.
      val includePending =
        call.request.queryParameters["includePending"]?.lowercase() in setOf("1", "true")
      val designs = mutableListOf<WatchFacePushDesignV1>()
      val listed = mutableSetOf<String>()
      for (publication in if (offered.designs) push.store.published() else emptyList()) {
        val visibility = push.reach(service, publication, caller.account) ?: continue
        // Only what is already built: a listing never packages (the reconcile pass does).
        val built = withContext(Dispatchers.IO) { push.builds.cached(publication) }
        val build = if (includePending) push.designBuild(publication) else null
        if (built == null) {
          if (includePending) {
            designs += push.pendingListing(origin(call), publication, visibility, build)
            listed += publication.designId
          }
          continue
        }
        designs +=
          push.listing(origin(call), publication, built.apk, visibility).copy(build = build)
        listed += publication.designId
      }
      if (includePending && offered.designs) {
        designs += push.queuedOnly(service, origin(call), caller.account, listed)
      }
      // Then the public catalog faces, to every caller alike.
      val catalog =
        if (offered.catalogFaces) {
          withContext(Dispatchers.IO) { push.catalogFaces.resolve(catalogFaceSources()) }.faces
        } else emptyList()
      for (face in catalog) {
        val built = withContext(Dispatchers.IO) { push.builds.cached(face.publication) }
        if (built != null) {
          designs += push.catalogListing(origin(call), face, built.apk)
        } else if (includePending) {
          designs += push.pendingCatalogListing(origin(call), face)
        }
      }
      call.respondJson(
        WatchFacePushDesignListV1.serializer(),
        WatchFacePushDesignListV1(designs, packaging.takeIf { !it.available }),
      )
    }
  }

  // A catalog face, named by query rather than path: a preview id may hold a `/`.
  get("${WatchFacePush.BASE_PATH}/catalog-faces/apk") {
    call.limited {
      if (!call.offersCatalogFaces(site)) return@limited
      val caller = call.watchCaller(push, clientAddress) ?: return@limited
      if (!call.admit(caller.limiter, caller.key)) return@limited
      if (caller.account == null && !call.admit(push.anonymousApkLimiter, caller.key))
        return@limited
      val face = call.catalogFace(push, catalogFaceSources) ?: return@limited
      when (
        val build = withContext(Dispatchers.IO) { push.builds.build(face.publication, face.dwf) }
      ) {
        is WatchFacePushBuilds.Result.Unavailable -> {
          call.noStore()
          call.respondError(HttpStatusCode.ServiceUnavailable, build.code, build.reason)
        }
        is WatchFacePushBuilds.Result.Failed -> {
          call.noStore()
          call.respondError(HttpStatusCode.NotFound, "not_found", "this face has no APK")
        }
        is WatchFacePushBuilds.Result.Built -> call.respondApk(build, face.publication.version)
      }
    }
  }

  get("${WatchFacePush.BASE_PATH}/catalog-faces/preview.png") {
    call.limited {
      if (!call.offersCatalogFaces(site)) return@limited
      if (!call.admit(push.anonymousLimiter, "ip:" + clientAddress(call))) return@limited
      val face = call.catalogFace(push, catalogFaceSources) ?: return@limited
      val png = withContext(Dispatchers.IO) { face.source.png() }
      if (png == null) {
        call.noStore()
        call.respondText("no preview", status = HttpStatusCode.NotFound)
        return@limited
      }
      call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=86400")
      call.respondBytes(png, ContentType.Image.PNG)
    }
  }

  get("${WatchFacePush.BASE_PATH}/designs/{designId}/apk") {
    call.limited {
      if (!call.offersDesigns(site)) return@limited
      val caller = call.watchCaller(push, clientAddress) ?: return@limited
      if (!call.admit(caller.limiter, caller.key)) return@limited
      if (caller.account == null && !call.admit(push.anonymousApkLimiter, caller.key))
        return@limited
      val designId = call.parameters["designId"].orEmpty()
      val publication = push.store.publication(designId)
      val requested = call.request.queryParameters["v"]?.toLongOrNull()
      // A published private face asked for with no token is 401, so the app offers to link (the
      // contract, watchface-tools#46). With somebody else's live token it stays 404: a 401 there
      // would make that watch discard a token that is fine.
      if (
        publication != null &&
          publication.published &&
          // An automatic listing is never private, so a token would not reach it either.
          !publication.automatic &&
          caller.account == null &&
          push.reach(service, publication, null) == null &&
          // Still the design that was published; a recreated id is unknown, so 404.
          push.isSameDesign(service, publication)
      ) {
        call.noStore()
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        call.respondError(
          HttpStatusCode.Unauthorized,
          "unauthorized",
          "link a watch to download this",
        )
        return@limited
      }
      if (
        publication == null ||
          push.reach(service, publication, caller.account) == null ||
          (requested != null && requested != publication.version)
      ) {
        call.noStore()
        call.respondError(HttpStatusCode.NotFound, "not_found", "no such published design")
        return@limited
      }
      when (val build = withContext(Dispatchers.IO) { push.builds.build(publication) }) {
        is WatchFacePushBuilds.Result.Unavailable -> {
          call.noStore()
          call.respondError(HttpStatusCode.ServiceUnavailable, build.code, build.reason)
        }
        is WatchFacePushBuilds.Result.Failed -> {
          call.noStore()
          call.respondError(HttpStatusCode.NotFound, "not_found", "this design has no APK")
        }
        is WatchFacePushBuilds.Result.Built -> {
          val etag = "\"${build.apk.sha256}\""
          call.response.headers.append(HttpHeaders.ETag, etag)
          // Private: it was served behind the reader's token (or to one address, for a public
          // face), and the URL is the same for every version.
          call.response.headers.append(HttpHeaders.CacheControl, "private, no-cache")
          call.response.headers.append("X-Watchface-Version", publication.version.toString())
          if (
            call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim() == etag } ==
              true
          ) {
            call.respondText("", status = HttpStatusCode.NotModified)
            return@limited
          }
          call.response.headers.append(
            HttpHeaders.ContentDisposition,
            "attachment; filename=\"${build.apk.packageName}-${publication.version}.apk\"",
          )
          val bytes = withContext(Dispatchers.IO) { build.file.readBytes() }
          call.respondBytes(bytes, ContentType.parse(APK_CONTENT_TYPE))
        }
      }
    }
  }

  get("${WatchFacePush.BASE_PATH}/designs/{designId}/preview.png") {
    call.limited {
      if (!call.offersDesigns(site)) return@limited
      if (!call.admit(push.anonymousLimiter, "ip:" + clientAddress(call))) return@limited
      val designId = call.parameters["designId"].orEmpty()
      val publication = push.store.publication(designId)
      val version = call.request.queryParameters["v"]?.toLongOrNull()
      val signature = call.request.queryParameters["sig"]
      if (
        publication == null ||
          !publication.published ||
          version != publication.version ||
          !ServeUrls.tokensMatch(push.store.previewSignature(designId, version), signature) ||
          !push.isSameDesign(service, publication)
      ) {
        call.noStore()
        call.respondText("not found", status = HttpStatusCode.NotFound)
        return@limited
      }
      val png =
        push.preview(
          AuthenticatedUiBuilderActor(publication.accountActorId),
          designId,
          publication.revision,
        )
      if (png == null) {
        call.noStore()
        call.respondText("no preview", status = HttpStatusCode.NotFound)
        return@limited
      }
      // Keyed by version and signature in the URL, so the picture behind one never changes.
      call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400")
      call.respondBytes(png, ContentType.Image.PNG)
    }
  }

  post("${WatchFacePush.BASE_PATH}/installs") {
    call.limited {
      call.noStore()
      val caller = call.watchCaller(push, clientAddress, required = true) ?: return@limited
      if (!call.admit(caller.limiter, caller.key)) return@limited
      val event = call.receiveJson(WatchFacePushInstallEventV1.serializer()) ?: return@limited
      val stored =
        WatchFacePushInstalls.stored(event)
          ?: run {
            call.respondError(
              HttpStatusCode.BadRequest,
              "invalid_request",
              "an install report needs a designId, a non-negative version and a result",
            )
            return@limited
          }
      // Fire-and-forget from the app: a report on a face this watch's account did not publish (a
      // catalog face, someone else's public design) is accepted and not kept.
      val device = caller.device ?: return@limited
      withContext(Dispatchers.IO) { push.store.recordInstall(device, stored) }
      call.respondText("", status = HttpStatusCode.NoContent)
    }
  }

  // The person's own linked watches, for a page or a script; `/link` is the page.
  get("${WatchFacePush.BASE_PATH}/devices") {
    call.noStore()
    val account =
      call.signedInAccount(authorization)
        ?: run {
          call.respondError(HttpStatusCode.Unauthorized, "sign_in_required", "sign in first")
          return@get
        }
    call.respondJson(
      WatchFacePushDevicesV1.serializer(),
      WatchFacePushDevicesV1(push.store.devices(account).map { it.wire() }),
    )
  }

  delete("${WatchFacePush.BASE_PATH}/devices/{deviceId}") {
    call.noStore()
    val account =
      call.signedInAccount(authorization)
        ?: run {
          call.respondError(HttpStatusCode.Unauthorized, "sign_in_required", "sign in first")
          return@delete
        }
    val removed =
      withContext(Dispatchers.IO) {
        push.store.revoke(call.parameters["deviceId"].orEmpty(), account)
      }
    if (removed) call.respondText("", status = HttpStatusCode.NoContent)
    else call.respondError(HttpStatusCode.NotFound, "not_found", "no such linked watch")
  }

  get(WatchFacePush.LINK_PATH) {
    call.noStore()
    call.response.headers.append("Referrer-Policy", "no-referrer")
    val account = call.signedInAccount(authorization)
    val code = call.request.queryParameters["code"]
    call.respondText(
      page(call, "Link a watch", push.linkPage(account, code, call.request.origin.uri)),
      ContentType.Text.Html,
    )
  }

  post(WatchFacePush.LINK_PATH) {
    call.noStore()
    call.response.headers.append("Referrer-Policy", "no-referrer")
    val account = call.signedInAccount(authorization)
    if (account == null) {
      call.respondText(
        page(call, "Link a watch", push.linkPage(null, null, WatchFacePush.LINK_PATH)),
        ContentType.Text.Html,
        HttpStatusCode.Forbidden,
      )
      return@post
    }
    val form = call.receiveParameters()
    val action = form["action"].orEmpty()
    val subject = form["subject"].orEmpty()
    if (!push.csrf.verify(subject, account, action, form["seal"])) {
      call.respondText(
        page(call, "Link a watch", push.linkMessage("This form has expired. Open the link again.")),
        ContentType.Text.Html,
        HttpStatusCode.Forbidden,
      )
      return@post
    }
    val message =
      withContext(Dispatchers.IO) {
        when (action) {
          LINK_APPROVE ->
            if (push.codes.approve(subject, account)) {
              "Linked. Your watch will show your watch faces in a few seconds."
            } else "This code has expired or was already used. Start again on the watch."
          LINK_DENY -> {
            push.codes.deny(subject)
            "Not linked. The watch has been told."
          }
          LINK_REVOKE ->
            if (push.store.revoke(subject, account))
              "Unlinked. That watch can no longer read your designs."
            else "That watch was already unlinked."
          else -> "Nothing to do."
        }
      }
    call.respondText(page(call, "Link a watch", push.linkMessage(message)), ContentType.Text.Html)
  }

  // The owner's own publication of a design: their signed-in session only, never a delegated grant
  // or an agent (an agent uses `ui_builder_publish_watch_face`, which its approver's grant scopes).
  get(WatchFacePush.PUBLICATION_PATH) {
    call.limited {
      call.noStore()
      val actor =
        call.ownerSessionOrRespond(authorization, UiBuilderRouteCapability.READ, push)
          ?: return@limited
      call.respondOutcome(
        push.publicationStatus(service, actor, call.parameters["designId"].orEmpty()),
      )
    }
  }

  // The owner's own watches' latest install reports on this design.
  get(WatchFacePush.INSTALLS_PATH) {
    call.noStore()
    val actor =
      call.authorizedActorOrRespond(authorization, UiBuilderRouteCapability.READ) ?: return@get
    when (val installs = push.installs(service, actor, call.parameters["designId"].orEmpty())) {
      is WatchFacePush.Installs.Refused ->
        call.respondError(installs.refused.status, installs.refused.code, installs.refused.reason)
      is WatchFacePush.Installs.Ok ->
        call.respondJson(WatchFacePushInstallsV1.serializer(), installs.installs)
    }
  }

  put(WatchFacePush.PUBLICATION_PATH) {
    call.limited {
      call.noStore()
      val actor =
        call.ownerSessionOrRespond(authorization, UiBuilderRouteCapability.WRITE, push)
          ?: return@limited
      val request =
        call.receiveJson(WatchFacePushPublishRequestV1.serializer(), allowEmpty = true)
          ?: return@limited
      if (request.visibility != null && request.visibility !in WatchFacePush.VISIBILITIES) {
        call.respondError(
          HttpStatusCode.BadRequest,
          "bad_request",
          "`visibility` is `public` or `private`",
        )
        return@limited
      }
      call.respondOutcome(
        push.publishInBackground(
          service,
          actor,
          call.parameters["designId"].orEmpty(),
          request.visibility,
        ),
      )
    }
  }

  delete(WatchFacePush.PUBLICATION_PATH) {
    call.limited {
      call.noStore()
      val actor =
        call.ownerSessionOrRespond(authorization, UiBuilderRouteCapability.WRITE, push)
          ?: return@limited
      call.respondOutcome(push.unpublish(service, actor, call.parameters["designId"].orEmpty()))
    }
  }
}

internal const val LINK_APPROVE = "approve"
internal const val LINK_DENY = "deny"
internal const val LINK_REVOKE = "revoke"

internal fun WatchFacePush.packagingStatus(): WatchFacePushStatusV1 =
  when (val packaging = packaging()) {
    is WatchFacePushPackaging.Ready ->
      WatchFacePushStatusV1(
        available = true,
        signerCertificateSha256 = packaging.signerCertificateSha256,
      )
    is WatchFacePushPackaging.Unavailable ->
      WatchFacePushStatusV1(available = false, code = packaging.code, reason = packaging.reason)
  }

internal fun WatchFacePush.catalogListing(
  origin: String,
  face: WatchFacePushCatalogFaces.Face,
  apk: WatchFacePushStore.Apk,
): WatchFacePushDesignV1 {
  val query =
    "system=" +
      WebEscaping.urlEncodeSegment(face.source.system) +
      "&preview=" +
      WebEscaping.urlEncodeSegment(face.source.previewId) +
      "&v=" +
      face.publication.version
  val base = "$origin${WatchFacePush.BASE_PATH}/catalog-faces"
  return WatchFacePushDesignV1(
    id = face.publication.designId,
    title = face.source.title,
    previewUrl = "$base/preview.png?$query",
    version = face.publication.version,
    versionName =
      face.manifest.commit?.take(12)?.let { "${face.publication.version} ($it)" }
        ?: face.publication.version.toString(),
    wffVersion = face.dwf.wffVersion,
    packageName = apk.packageName,
    apkUrl = "$base/apk?$query",
    sha256 = apk.sha256,
    sizeBytes = apk.sizeBytes,
    validationToken = apk.validationToken,
    visibility = WatchFacePush.VISIBILITY_PUBLIC,
    source = SOURCE_CATALOG,
    catalog = WatchFacePushCatalogV1(face.source.system, face.source.catalogTitle),
    attribution =
      WatchFacePushAttributionV1(
        repoUrl = face.manifest.repoUrl,
        sourceUrl = face.manifest.sourceUrl,
        license = face.manifest.license!!,
      ),
  )
}

internal const val SOURCE_DESIGN = "design"
internal const val SOURCE_CATALOG = "catalog"

/**
 * The catalog face `?system=&preview=` names, at `?v=` when given; null once a 404 is written. Only
 * a face that is listed (its catalog served and listed, its source present, its licence on the
 * allowlist) is found.
 */
/**
 * What one host offers a watch. A box serving both the UI builder's own host and a catalog site
 * keeps each list on its own site: the builder's host lists the designs made in it, the catalog
 * site lists its catalog faces. A box with no builder host offers both on every host.
 */
internal data class WatchFacePushSite(val designs: Boolean, val catalogFaces: Boolean) {
  companion object {
    val BOTH = WatchFacePushSite(designs = true, catalogFaces = true)
    val DESIGNS_ONLY = WatchFacePushSite(designs = true, catalogFaces = false)
    val CATALOG_FACES_ONLY = WatchFacePushSite(designs = false, catalogFaces = true)
  }
}

/** False, having answered 404, when this host lists no catalog faces. */
private suspend fun ApplicationCall.offersCatalogFaces(
  site: (ApplicationCall) -> WatchFacePushSite,
): Boolean {
  if (site(this).catalogFaces) return true
  noStore()
  respondError(HttpStatusCode.NotFound, "not_found", "this site lists no catalog faces")
  return false
}

/** False, having answered 404, when this host lists no UI-builder designs. */
private suspend fun ApplicationCall.offersDesigns(
  site: (ApplicationCall) -> WatchFacePushSite,
): Boolean {
  if (site(this).designs) return true
  noStore()
  respondError(HttpStatusCode.NotFound, "not_found", "no such published design")
  return false
}

private suspend fun ApplicationCall.catalogFace(
  push: WatchFacePush,
  sources: () -> List<CatalogFaceSource>,
): WatchFacePushCatalogFaces.Face? {
  val system = request.queryParameters["system"].orEmpty()
  val previewId = request.queryParameters["preview"].orEmpty()
  val requested = request.queryParameters["v"]?.toLongOrNull()
  val face =
    withContext(Dispatchers.IO) {
      push.catalogFaces
        .resolve(sources().filter { it.system == system && it.previewId == previewId })
        .faces
        .singleOrNull()
    }
  if (face == null || (requested != null && requested != face.publication.version)) {
    noStore()
    respondError(HttpStatusCode.NotFound, "not_found", "no such catalog face")
    return null
  }
  return face
}

/** The APK [build] with its validators, as the design route serves one. */
private suspend fun ApplicationCall.respondApk(
  build: WatchFacePushBuilds.Result.Built,
  version: Long,
) {
  val etag = "\"${build.apk.sha256}\""
  response.headers.append(HttpHeaders.ETag, etag)
  response.headers.append(HttpHeaders.CacheControl, "private, no-cache")
  response.headers.append("X-Watchface-Version", version.toString())
  if (request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any { it.trim() == etag } == true) {
    respondText("", status = HttpStatusCode.NotModified)
    return
  }
  response.headers.append(
    HttpHeaders.ContentDisposition,
    "attachment; filename=\"${build.apk.packageName}-$version.apk\"",
  )
  val bytes = withContext(Dispatchers.IO) { build.file.readBytes() }
  respondBytes(bytes, ContentType.parse(APK_CONTENT_TYPE))
}

internal fun WatchFacePush.listing(
  origin: String,
  publication: WatchFacePushStore.Publication,
  apk: WatchFacePushStore.Apk,
  visibility: String,
): WatchFacePushDesignV1 {
  val id = WebEscaping.urlEncodeSegment(publication.designId)
  val designPath = "$origin${WatchFacePush.BASE_PATH}/designs/$id"
  return WatchFacePushDesignV1(
    id = publication.designId,
    title = publication.title.ifBlank { publication.designId },
    previewUrl =
      "$designPath/preview.png?v=${publication.version}&sig=" +
        store.previewSignature(publication.designId, publication.version),
    version = publication.version,
    versionName = "${publication.version} (revision ${publication.revision})",
    wffVersion = publication.wffVersion,
    packageName = apk.packageName,
    apkUrl = "$designPath/apk?v=${publication.version}",
    sha256 = apk.sha256,
    sizeBytes = apk.sizeBytes,
    validationToken = apk.validationToken,
    visibility = visibility,
    source = SOURCE_DESIGN,
  )
}

/**
 * A published design's build for the list: the queue's state when it has one, else what the builder
 * knows (refused, or queued on the background worker); null when it is simply built.
 */
internal fun WatchFacePush.designBuild(
  publication: WatchFacePushStore.Publication,
): WatchFacePushBuildStatusV1? =
  queue.status(publication.designId)
    ?: builds.refusal(publication)?.let {
      WatchFacePushBuildStatusV1(
        WatchFacePushBuildQueue.REFUSED,
        reason = "the packager refused it",
        diagnostics = it,
      )
    }
    ?: if (builds.cached(publication) == null) {
      WatchFacePushBuildStatusV1(
        if (builds.isPending(publication)) WatchFacePushBuildQueue.QUEUED
        else WatchFacePushBuildQueue.PENDING,
      )
    } else null

/** A published design with no APK yet: shown with its build, offered for nothing. */
internal fun WatchFacePush.pendingListing(
  origin: String,
  publication: WatchFacePushStore.Publication,
  visibility: String,
  build: WatchFacePushBuildStatusV1?,
): WatchFacePushDesignV1 {
  val id = WebEscaping.urlEncodeSegment(publication.designId)
  return WatchFacePushDesignV1(
    id = publication.designId,
    title = publication.title.ifBlank { publication.designId },
    // The picture is signed per version and needs no APK, so an unbuilt face still has one.
    previewUrl =
      "$origin${WatchFacePush.BASE_PATH}/designs/$id/preview.png?v=${publication.version}&sig=" +
        store.previewSignature(publication.designId, publication.version),
    wffVersion = publication.wffVersion.takeIf { it > 0 },
    packageName = WatchFacePushNaming.packageName(publication.faceName),
    visibility = visibility,
    source = SOURCE_DESIGN,
    build = build ?: WatchFacePushBuildStatusV1(WatchFacePushBuildQueue.PENDING),
  )
}

/**
 * Designs with a build in the queue and nothing stored yet (a first publish building, a public
 * design waiting to settle), not already [listed]: an owner's own build only to that owner's
 * watches, an automatic one only while anyone may read the design.
 */
internal suspend fun WatchFacePush.queuedOnly(
  service: UiBuilderServicePort,
  origin: String,
  account: String?,
  listed: Set<String>,
): List<WatchFacePushDesignV1> =
  queue.snapshot().mapNotNull { (designId, build) ->
    if (designId in listed || build.state == WatchFacePushBuildQueue.BUILT) return@mapNotNull null
    val summary = designSummary(designId) ?: return@mapNotNull null
    if (!isWatchFaceCatalog(summary.catalogPin.systemId)) return@mapNotNull null
    val visibility =
      when {
        build.automatic && service.canRead(WatchFacePush.ANONYMOUS, designId) ->
          WatchFacePush.VISIBILITY_PUBLIC
        !build.automatic && account != null && account == summary.ownerActorId ->
          WatchFacePush.VISIBILITY_PRIVATE
        else -> null
      } ?: return@mapNotNull null
    WatchFacePushDesignV1(
      id = designId,
      title = summary.title.ifBlank { designId },
      packageName = WatchFacePushNaming.packageName(WatchFacePushNaming.faceName(designId)),
      visibility = visibility,
      source = SOURCE_DESIGN,
      build = build,
    )
  }

/** A listed catalog face with no APK yet: refused, queued on the background worker, or pending. */
internal fun WatchFacePush.pendingCatalogListing(
  origin: String,
  face: WatchFacePushCatalogFaces.Face,
): WatchFacePushDesignV1 {
  val query =
    "system=" +
      WebEscaping.urlEncodeSegment(face.source.system) +
      "&preview=" +
      WebEscaping.urlEncodeSegment(face.source.previewId) +
      "&v=" +
      face.publication.version
  val refusal = builds.refusal(face.publication)
  return WatchFacePushDesignV1(
    id = face.publication.designId,
    title = face.source.title,
    previewUrl = "$origin${WatchFacePush.BASE_PATH}/catalog-faces/preview.png?$query",
    wffVersion = face.dwf.wffVersion,
    packageName = WatchFacePushNaming.packageName(face.publication.faceName),
    visibility = WatchFacePush.VISIBILITY_PUBLIC,
    source = SOURCE_CATALOG,
    catalog = WatchFacePushCatalogV1(face.source.system, face.source.catalogTitle),
    build =
      when {
        refusal != null ->
          WatchFacePushBuildStatusV1(
            WatchFacePushBuildQueue.REFUSED,
            reason = "the packager refused it",
            diagnostics = refusal,
          )
        builds.isPending(face.publication) ->
          WatchFacePushBuildStatusV1(WatchFacePushBuildQueue.QUEUED)
        else -> WatchFacePushBuildStatusV1(WatchFacePushBuildQueue.PENDING)
      },
  )
}

private fun WatchFacePushStore.Device.wire() =
  WatchFacePushLinkedDeviceV1(id, deviceName, wearOsApi, createdAtEpochMillis, lastUsedEpochMillis)

/** Who is asking on a watch route: anonymous by address, or a linked device by its id. */
private class WatchCaller(
  val account: String?,
  val limiter: ServeRateLimiter,
  val key: String,
  val device: WatchFacePushStore.Device? = null,
)

/**
 * The caller of a watch route, or null once a 401 has been written. No `Authorization` header is an
 * anonymous caller (unless [required]); any `Authorization` that is not a live device token is 401,
 * so a watch whose token was revoked unlinks rather than silently browsing anonymously.
 */
private suspend fun ApplicationCall.watchCaller(
  push: WatchFacePush,
  clientAddress: (ApplicationCall) -> String,
  required: Boolean = false,
): WatchCaller? {
  val header = request.headers[HttpHeaders.Authorization]
  if (header == null && !required) {
    return WatchCaller(null, push.anonymousLimiter, "ip:" + clientAddress(this))
  }
  val token =
    header?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
  val device = token?.takeIf { it.isNotEmpty() }?.let(push.store::deviceForToken)
  if (device == null) {
    noStore()
    response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
    respondError(HttpStatusCode.Unauthorized, "unauthorized", "link this watch again")
    return null
  }
  withContext(Dispatchers.IO) { push.store.touch(device) }
  return WatchCaller(device.accountActorId, push.deviceLimiter, "device:" + device.id, device)
}

/** The signed-in person's account, or null for a signed-out visitor (or an anonymous reader). */
private fun ApplicationCall.signedInAccount(authorization: ServeUiBuilderAuthorization): String? =
  (authorization.authorize(this, UiBuilderRouteCapability.READ, null)
      as? UiBuilderAuthorizationDecision.Authorized)
    ?.takeIf { it.isPersonalSession() }
    ?.actorId

/**
 * Whether [this] is a person in their own session. A delegated grant (an agent acting for someone)
 * may read their designs, but must not mint a durable device token in their name, unlink their
 * watches, or put a design on every watch: those outlive the grant, and are the person's to decide.
 */
private fun UiBuilderAuthorizationDecision.Authorized.isPersonalSession(): Boolean {
  if (!onBehalfOfActorId.isNullOrBlank()) return false
  return !(actorId == ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID ||
    actorId.startsWith("anonymous:") ||
    actorId.startsWith("agent:"))
}

/**
 * The actor of a person in their own signed-in session with [capability], charged one permit of
 * [WatchFacePush.publicationLimiter]; null once a refusal is written. Ownership of the design is
 * checked by the call itself.
 */
private suspend fun ApplicationCall.ownerSessionOrRespond(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
  push: WatchFacePush,
): AuthenticatedUiBuilderActor? {
  val decision =
    when (val decision = authorization.authorize(this, capability, null)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondError(HttpStatusCode.Unauthorized, "unauthorized", "sign in first")
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondError(HttpStatusCode.Forbidden, "forbidden", "UI-builder access is required")
        return null
      }
    }
  if (!decision.isPersonalSession()) {
    respondError(
      HttpStatusCode.Forbidden,
      "owner_session_required",
      "only the design's owner, signed in on this server, can publish it to watches here",
    )
    return null
  }
  if (!admit(push.publicationLimiter, "account:" + decision.actorId)) return null
  return decision.actor
}

/**
 * What the editor is told about publishing [designId] to watches, for the caller of [call]: null
 * unless this host can build push APKs and the design's catalog draws watch faces.
 */
internal suspend fun WatchFacePush.capability(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  call: ApplicationCall,
  designId: String,
): WatchFacePushCapabilityV1? {
  if (!packagingStatus().available) return null
  val decision =
    authorization.authorize(call, UiBuilderRouteCapability.READ, null)
      as? UiBuilderAuthorizationDecision.Authorized ?: return null
  val snapshot =
    service.executeMapped(GetSnapshotRequestV1(designId = designId), decision.actor)
      as? UiBuilderServiceResponse.Snapshot ?: return null
  if (!isWatchFaceCatalog(snapshot.snapshot.state.document.catalogPin.systemId)) return null
  val owner = snapshot.snapshot.access?.ownerActorId
  val canPublish =
    decision.isPersonalSession() &&
      owner != null &&
      owner in decision.actor.accessIdentities &&
      authorization.authorize(call, UiBuilderRouteCapability.WRITE, null) is
        UiBuilderAuthorizationDecision.Authorized &&
      service.designActions(decision.actor, designId)?.contains(DesignAccessActionV1.WRITE) == true
  return WatchFacePushCapabilityV1(
    publicationPath =
      WatchFacePush.PUBLICATION_PATH.replace("{designId}", WebEscaping.urlEncodeSegment(designId)),
    canPublish = canPublish,
  )
}

private suspend fun ApplicationCall.authorizedActorOrRespond(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): AuthenticatedUiBuilderActor? =
  when (val decision = authorization.authorize(this, capability, null)) {
    is UiBuilderAuthorizationDecision.Authorized -> decision.actor
    UiBuilderAuthorizationDecision.Missing -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondError(HttpStatusCode.Unauthorized, "unauthorized", "authentication is required")
      null
    }
    UiBuilderAuthorizationDecision.Forbidden -> {
      respondError(HttpStatusCode.Forbidden, "forbidden", "UI-builder access is required")
      null
    }
  }

private suspend fun ApplicationCall.respondOutcome(outcome: WatchFacePush.Outcome) {
  when (outcome) {
    is WatchFacePush.Outcome.Ok ->
      respondJson(WatchFacePushPublicationV1.serializer(), outcome.status)
    is WatchFacePush.Outcome.Accepted ->
      respondJson(WatchFacePushPublicationV1.serializer(), outcome.status, HttpStatusCode.Accepted)
    is WatchFacePush.Outcome.Refused ->
      respondJson(
        WatchFacePushErrorV1.serializer(),
        WatchFacePushErrorV1(
          outcome.code,
          outcome.reason,
          outcome.diagnostics.takeIf { it.isNotEmpty() },
        ),
        outcome.status,
      )
  }
}

private val PERMITS =
  io.ktor.util.AttributeKey<MutableList<ServeRateLimiter.Decision.Admitted>>(
    "watchface-push-permits",
  )

/**
 * Run a watch route's handler, releasing every permit [admit] took for it when it finishes, however
 * it finishes — so `maxConcurrent` bounds what one caller has in flight (a listing can build APKs,
 * a picture can wait to be drawn).
 */
private suspend inline fun ApplicationCall.limited(block: () -> Unit) {
  try {
    block()
  } finally {
    attributes.getOrNull(PERMITS)?.forEach { it.release() }
    attributes.remove(PERMITS)
  }
}

/**
 * False (with a 429 and `Retry-After` written) when [key] is over [limiter]'s budget; otherwise the
 * permit is held until the enclosing [limited] block ends.
 */
private suspend fun ApplicationCall.admit(limiter: ServeRateLimiter, key: String): Boolean =
  when (val decision = limiter.tryAcquire(key)) {
    is ServeRateLimiter.Decision.Admitted -> {
      attributes.computeIfAbsent(PERMITS) { mutableListOf() }.add(decision)
      true
    }
    is ServeRateLimiter.Decision.Throttled -> {
      noStore()
      response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
      respondError(HttpStatusCode.TooManyRequests, "rate_limited", decision.reason)
      false
    }
  }

private fun ApplicationCall.noStore() {
  if (response.headers[HttpHeaders.CacheControl] == null) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
  }
}

private suspend fun <T> ApplicationCall.receiveJson(
  serializer: KSerializer<T>,
  allowEmpty: Boolean = false,
): T? {
  val bytes =
    withContext(Dispatchers.IO) { receiveStream().use { it.readNBytes(MAX_BODY_BYTES + 1) } }
  if (bytes.size > MAX_BODY_BYTES) {
    respondError(HttpStatusCode.PayloadTooLarge, "too_large", "request body too large")
    return null
  }
  val text = bytes.toString(StandardCharsets.UTF_8).ifBlank { if (allowEmpty) "{}" else "" }
  return try {
    WATCHFACE_PUSH_JSON.decodeFromString(serializer, text)
  } catch (_: SerializationException) {
    respondError(
      HttpStatusCode.BadRequest,
      "bad_request",
      "the body is not the JSON this route reads",
    )
    null
  } catch (_: IllegalArgumentException) {
    respondError(
      HttpStatusCode.BadRequest,
      "bad_request",
      "the body is not the JSON this route reads",
    )
    null
  }
}

private suspend fun <T> ApplicationCall.respondJson(
  serializer: KSerializer<T>,
  value: T,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  respondText(
    WATCHFACE_PUSH_JSON.encodeToString(serializer, value),
    ContentType.Application.Json,
    status,
  )
}

private suspend fun ApplicationCall.respondPoll(response: WatchFacePushPollResponseV1) =
  respondJson(WatchFacePushPollResponseV1.serializer(), response)

private suspend fun ApplicationCall.respondError(
  status: HttpStatusCode,
  code: String,
  message: String,
) = respondJson(WatchFacePushErrorV1.serializer(), WatchFacePushErrorV1(code, message), status)

// ---------------------------------------------------------------------- page

private fun esc(value: String): String = WebEscaping.htmlEscape(value)

internal fun WatchFacePush.linkMessage(message: String): String =
  """
  <h1 class="cp-head">Link a watch</h1>
  <p class="cp-sub">${esc(message)}</p>
  <p><a href="${WatchFacePush.LINK_PATH}">Your linked watches</a></p>
  """
    .trimIndent()

/**
 * `/link`: confirm a watch's code, or list the person's linked watches with a way to unlink each.
 * Every form carries a seal over (subject, account, action), so a page elsewhere cannot submit one
 * — on top of the same-origin check the authorization already makes on a session-cookie write.
 */
internal fun WatchFacePush.linkPage(account: String?, rawCode: String?, returnTo: String): String {
  if (account == null) {
    val signIn =
      signInPath?.let { """<p><a class="cp-button" href="${esc(it(returnTo))}">Sign in</a></p>""" }
        ?: "<p>Sign in to this server first, then open this link again.</p>"
    return """
      <h1 class="cp-head">Link a watch</h1>
      <p class="cp-sub">Sign in to link a watch to your account. It will be able to list and
        install the watch faces you publish, and nothing else.</p>
      $signIn
      """
      .trimIndent()
  }
  val code = rawCode?.let { WatchFacePushDeviceCodes.normalize(it) }
  if (rawCode != null) {
    val pending = code?.let { codes.pending(it) }
    if (pending == null || pending.state != WatchFacePushDeviceCodes.State.PENDING) {
      return linkMessage("This code has expired or was already used. Start again on the watch.")
    }
    fun form(action: String, label: String) =
      """
      <form method="post" action="${WatchFacePush.LINK_PATH}" style="display:inline">
        <input type="hidden" name="subject" value="${esc(pending.code)}">
        <input type="hidden" name="action" value="$action">
        <input type="hidden" name="seal" value="${esc(csrf.seal(pending.code, account, action))}">
        <button type="submit">${esc(label)}</button>
      </form>
      """
        .trimIndent()
    return """
      <h1 class="cp-head">Link ${esc(pending.deviceName)}?</h1>
      <p class="cp-sub">Check that the watch shows <strong><code>${esc(pending.code)}</code></strong>.
        Linking lets it list and install the watch faces
        <strong>${esc(WatchFacePushDeviceCodes.accountName(account))}</strong> publishes, including
        private ones, until you unlink it here. It cannot edit or read anything else.</p>
      ${form(LINK_APPROVE, "Link")}
      ${form(LINK_DENY, "Don't link")}
      """
      .trimIndent()
  }
  val devices = store.devices(account)
  val rows =
    if (devices.isEmpty()) "<p>No watches are linked to your account.</p>"
    else
      devices.joinToString("\n", "<ul>", "</ul>") { device ->
        """
        <li>${esc(device.deviceName)}
          <form method="post" action="${WatchFacePush.LINK_PATH}" style="display:inline">
            <input type="hidden" name="subject" value="${esc(device.id)}">
            <input type="hidden" name="action" value="$LINK_REVOKE">
            <input type="hidden" name="seal" value="${esc(csrf.seal(device.id, account, LINK_REVOKE))}">
            <button type="submit">Unlink</button>
          </form>
        </li>
        """
          .trimIndent()
      }
  return """
    <h1 class="cp-head">Link a watch</h1>
    <p class="cp-sub">Open the push app on your watch and choose to link it; it shows a code and a
      QR code for this page. Or type the code here:</p>
    <form method="get" action="${WatchFacePush.LINK_PATH}">
      <input name="code" placeholder="ABCD-2345" autocomplete="off" autocapitalize="characters">
      <button type="submit">Continue</button>
    </form>
    <h2>Linked watches</h2>
    $rows
    """
    .trimIndent()
}
