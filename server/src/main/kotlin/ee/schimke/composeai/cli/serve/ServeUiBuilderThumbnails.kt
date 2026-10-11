package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchCall
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The picture on each `/ui-builder/designs` card, kept on disk and redrawn ahead of the reader.
 *
 * A PNG export rather than linking the live SVG export: the SVG re-rendered on every request
 * (queuing behind the editor's own exports) and pins text widths, which squeezed lettering in other
 * fonts.
 *
 * Stale is fine, missing is not: each thumbnail records the revision and generation
 * ([generationOf]: server version plus renderer identity) it was drawn at. A newer revision still
 * gets the cached picture while a redraw is queued; only a never-drawn design renders while the
 * reader waits. [warming] queues a redraw on every accepted edit from any lane; one worker, because
 * the renderer answers concurrent renders with "busy".
 *
 * Wear widgets are drawn via the [nativePreview] lane inside the [WearWidgetHostShape.Squircle]
 * container, since the export is a bare rectangle. That compiles Kotlin, so it only runs for
 * callers with the `ui-builder-export` capability (`native = true`); others get the export, kept as
 * [Entry.unframed] and redrawn natively by the next capable viewer. Failed native draws are retried
 * up to [NATIVE_ATTEMPTS] times per revision.
 *
 * Access: cached bytes carry no ACL, so every serve checks the design's EXPORT action
 * ([designActions]). Queued redraws run as the actor who queued them.
 */
class ServeUiBuilderThumbnails
internal constructor(
  private val directory: Path,
  val generation: String,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : Closeable {
  /** One cached picture: the bytes, and what they were drawn from. */
  internal data class Entry(
    val revision: Long,
    val generation: String,
    val png: ByteArray,
    /** A Wear widget drawn by the export, without its host frame; see the class comment. */
    val unframed: Boolean = false,
  )

  /**
   * Native draws that failed, per `designId@revision`, so a later view retries a bounded number.
   */
  private val nativeFailures = ConcurrentHashMap<String, Int>()

  private val memory = ConcurrentHashMap<String, Entry>()

  /** One render waiting for, or holding, the worker; every caller asking for it shares [result]. */
  private class Job(
    val designId: String,
    val actor: AuthenticatedUiBuilderActor,
    /** A retained revision to draw, for the history view; null draws the latest. */
    val revision: Long? = null,
    /** Whether the caller that queued this may have a widget compiled natively. */
    val native: Boolean = false,
    val result: CompletableFuture<Entry?> = CompletableFuture(),
  ) {
    val key: String
      get() = revisionKey(designId, revision) + if (native) NATIVE_SUFFIX else ""
  }

  private val jobs = LinkedBlockingDeque<Job>(QUEUE)
  private val pending = ConcurrentHashMap<String, Job>()

  @Volatile private var service: UiBuilderServicePort? = null

  /**
   * Pictures guidelines prompts attach, kept beside thumbnails at the same [generation]
   * ([ServeUiBuilderGuidelineFrames]). Null if its directory couldn't be made.
   */
  val guidelineFrames: ServeUiBuilderGuidelineFrames? = runCatching {
    ServeUiBuilderGuidelineFrames(
      directory.resolve(GUIDELINE_FRAMES_DIRECTORY),
      generation,
      thumbnailsIdle = ::isIdle,
      onLog = onLog,
    )
  }
    .onFailure { onLog("serve: UI-builder guideline frame cache unavailable: ${it.message}") }
    .getOrNull()

  /**
   * The native render lane, once the server has one; null draws everything through the export. Also
   * used by the guideline frames.
   */
  @Volatile
  internal var nativePreview: UiBuilderNativePreviewLane? = null
    set(lane) {
      field = lane
      guidelineFrames?.render = lane?.let { native ->
        { document, shape ->
          (native.render(document, shape) as? UiBuilderNativePreviewOutcome.Rendered)
            ?.takeIf { it.failure == null }
            ?.response
            ?.image
            ?.let {
              runCatching { Base64.getDecoder().decode(it.removePrefix(PNG_DATA_URI)) }.getOrNull()
            }
        }
      }
    }

  /** Whether nothing is queued or drawing; guideline frames warm only then. */
  internal fun isIdle(): Boolean = jobs.isEmpty() && pending.isEmpty()

  /**
   * [designId]'s thumbnail at [revision] when it is the native Squircle render of a Wear widget
   * (the same render the guidelines' Pixel Watch frame needs); null otherwise. The caller asserts
   * it is a widget.
   */
  internal fun nativeWidgetThumbnail(designId: String, revision: Long): ByteArray? =
    cached(designId)
      ?.takeIf { it.revision == revision && it.generation == generation && !it.unframed }
      ?.png

  /**
   * The one worker: every render goes through it since the renderer rejects concurrent renders; a
   * card's first picture jumps the queue ([submit] `urgent`).
   */
  private val worker =
    Thread(
        {
          while (!Thread.currentThread().isInterrupted) {
            val job =
              try {
                jobs.take()
              } catch (interrupted: InterruptedException) {
                break
              }
            val entry =
              try {
                runBlocking {
                  retrying { render(job.designId, job.actor, job.revision, job.native) }
                }
              } catch (failure: Exception) {
                onLog("serve: UI-builder thumbnail for ${job.designId} failed: ${failure.message}")
                null
              }
            pending.remove(job.key, job)
            job.result.complete(entry)
          }
        },
        "ui-builder-thumbnails",
      )
      .apply { isDaemon = true }

  init {
    ServeOwnerOnlyFiles.createDirectories(directory)
    worker.start()
  }

  /**
   * [delegate] with each accepted edit queueing a redraw and each deletion or creation evicting the
   * id's picture; also what this cache renders through.
   */
  internal fun warming(delegate: UiBuilderServicePort): UiBuilderServicePort {
    val wrapped =
      object : UiBuilderServicePort by delegate {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
          val request = call.request
          // Before and after: a newly created design must never show the picture of a previous
          // design with this id.
          if (request is UiBuilderServiceRequest.CreateDesign) evict(request.document.id)
          val response = delegate.execute(call)
          when {
            response is UiBuilderServiceResponse.DesignDeleted -> evict(response.designId)
            response is UiBuilderServiceResponse.Error -> Unit
            request is UiBuilderServiceRequest.ApplyOperation ->
              warm(request.submission.designId, call.actor)
            request is UiBuilderServiceRequest.RestoreRevision -> warm(request.designId, call.actor)
            request is UiBuilderServiceRequest.MoveDesignHome -> warm(request.designId, call.actor)
            request is UiBuilderServiceRequest.ReplaceDesignDocument ->
              warm(request.designId, call.actor)
          }
          if (response !is UiBuilderServiceResponse.Error)
            editedDesignId(request)?.let { guidelineFrames?.edited(it) }
          return response
        }
      }
    service = delegate
    return wrapped
  }

  /**
   * The branch lane, redrawing what it changes: merges commit outside [UiBuilderServicePort], and
   * new branches need a card.
   */
  internal fun warmingBranches(delegate: UiBuilderBranchPort): UiBuilderBranchPort =
    object : UiBuilderBranchPort {
      override suspend fun executeBranch(call: UiBuilderBranchCall): UiBuilderBranchResponse {
        val response = delegate.executeBranch(call)
        when (response) {
          is UiBuilderBranchResponse.Merge ->
            if (response.report.merged && !response.report.dryRun) {
              warm(response.report.parentDesignId, call.actor)
            }
          is UiBuilderBranchResponse.Branch ->
            if (call.request is UiBuilderBranchRequest.CreateBranch) {
              warm(response.branch.branchId, call.actor)
            }
          else -> Unit
        }
        return response
      }
    }

  /**
   * Forget [designId]'s picture in memory and on disk. Ids outlive designs and may be reused by
   * another owner, so every lane that deletes or replaces a design calls this.
   */
  internal fun evict(designId: String) {
    guidelineFrames?.evict(designId)
    memory.remove(designId)
    nativeFailures.keys.removeIf { it.startsWith("$designId@") }
    memory.keys.removeIf { it.startsWith("$designId$REVISION_SEPARATOR") }
    runCatching {
      val base = fileBase(designId)
      Files.deleteIfExists(directory.resolve("$base.png"))
      Files.deleteIfExists(directory.resolve("$base.meta"))
      // Every retained revision's picture goes with the design: the id may come back as another.
      Files.newDirectoryStream(directory, "$base-r*").use { stale ->
        stale.forEach { Files.deleteIfExists(it) }
      }
    }
      .onFailure { onLog("serve: UI-builder thumbnail for $designId not removed: ${it.message}") }
  }

  /**
   * The cached picture of [designId] at [revision], for history; retained revisions never change.
   */
  internal fun cachedRevision(designId: String, revision: Long): Entry? {
    val key = revisionKey(designId, revision)
    return memory[key]
      ?: runCatching { readEntry(designId, revision) }.getOrNull()?.also { memory[key] = it }
  }

  /** The cached picture for [designId], from memory or disk, whatever it was drawn at. */
  internal fun cached(designId: String): Entry? =
    memory[designId]
      ?: runCatching { readEntry(designId) }.getOrNull()?.also { memory[designId] = it }

  /**
   * Whether the cache holds [designId] at [revision] for this generation and, for a [native]
   * caller, in its host frame (unless native draws keep failing).
   */
  internal fun isCurrent(designId: String, revision: Long, native: Boolean = false): Boolean =
    cached(designId)?.let {
      it.revision == revision &&
        it.generation == generation &&
        !awaitsNativeRedraw(it, designId, revision, native)
    } == true

  /**
   * Whether an unframed widget [entry] should be redrawn natively for a [native] caller: a lane
   * exists and fewer than [NATIVE_ATTEMPTS] draws of this revision have failed.
   */
  internal fun awaitsNativeRedraw(
    entry: Entry,
    designId: String,
    revision: Long,
    native: Boolean,
  ): Boolean =
    native &&
      entry.unframed &&
      nativePreview != null &&
      (nativeFailures["$designId@$revision"] ?: 0) < NATIVE_ATTEMPTS

  /**
   * Queue a redraw of [designId] at its latest revision as [actor]; no-op when already queued.
   * [knownRevision] skips designs already current.
   */
  internal fun warm(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    knownRevision: Long? = null,
    native: Boolean = false,
  ) {
    if (knownRevision != null && isCurrent(designId, knownRevision, native)) return
    submit(designId, actor, urgent = false, native = native)
  }

  /**
   * The queued or running render of [designId], or a new one (front of the queue when [urgent]). A
   * full queue answers null.
   */
  internal fun submit(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    urgent: Boolean,
    revision: Long? = null,
    native: Boolean = false,
  ): CompletableFuture<Entry?> {
    val job = Job(designId, actor, revision, native)
    val existing = pending.putIfAbsent(job.key, job)
    if (existing != null) {
      // A card waiting on a redraw the listing queued moves that redraw to the front.
      if (urgent && jobs.remove(existing)) jobs.offerFirst(existing)
      return existing.result
    }
    val offered = if (urgent) jobs.offerFirst(job) else jobs.offerLast(job)
    if (!offered) {
      pending.remove(job.key, job)
      job.result.complete(null)
    }
    return job.result
  }

  /**
   * Render [designId] now (latest, or [revision] for history) and keep it; null when the export
   * refused.
   */
  internal suspend fun render(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    revision: Long? = null,
    native: Boolean = false,
  ): Entry? {
    val port = service ?: return null
    val widget = widgetDocument(port, designId, actor, revision)
    if (widget != null && native) {
      nativeWidget(designId, widget)?.let {
        nativeFailures.remove("$designId@${it.revision}")
        store(designId, it, pinned = revision != null)
        return it
      }
      nativeFailures.merge("$designId@${widget.revision}", 1, Int::plus)
    }
    val response =
      try {
        port.executeMapped(
          ExportDesignRequestV1(
            designId = designId,
            revision = revision,
            format = ExportFormatV1.PNG,
          ),
          actor,
        )
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        onLog("serve: UI-builder thumbnail for $designId not drawn: ${failure.message}")
        return null
      }
    val artifact = (response as? UiBuilderServiceResponse.Export)?.artifact ?: return null
    if (artifact.diagnostics.any { it.severity == DiagnosticSeverityV1.ERROR }) return null
    if (artifact.encoding != ExportEncodingV1.BASE64) return null
    val servedRevision = artifact.servedRevision()?.toLongOrNull() ?: return null
    val entry =
      Entry(
        servedRevision,
        generation,
        Base64.getDecoder().decode(artifact.content),
        unframed = widget != null,
      )
    store(designId, entry, pinned = revision != null)
    return entry
  }

  /**
   * [designId]'s document when it is a natively drawable Wear widget, else null. Checks EXPORT and
   * reads as [actor], so it never pictures a design the actor couldn't export.
   */
  private suspend fun widgetDocument(
    port: UiBuilderServicePort,
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    revision: Long?,
  ): DesignDocumentV1? {
    if (nativePreview == null) return null
    if (DesignAccessActionV1.EXPORT !in port.designActions(actor, designId).orEmpty()) return null
    val snapshot =
      try {
        port.executeMapped(GetSnapshotRequestV1(designId = designId, revision = revision), actor)
          as? UiBuilderServiceResponse.Snapshot
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        null
      } ?: return null
    return snapshot.snapshot.state.document.takeIf(RecordFreeExport::isWearWidget)
  }

  /**
   * [document] drawn natively inside its widget host, or null. Only for callers holding the export
   * capability.
   */
  private fun nativeWidget(designId: String, document: DesignDocumentV1): Entry? {
    val lane = nativePreview ?: return null
    val outcome =
      try {
        lane.render(document, WearWidgetHostShape.Squircle)
      } catch (failure: Exception) {
        onLog("serve: UI-builder widget thumbnail for $designId not drawn: ${failure.message}")
        return null
      }
    val rendered = outcome as? UiBuilderNativePreviewOutcome.Rendered
    if (rendered == null || rendered.failure != null) {
      onLog(
        "serve: UI-builder widget thumbnail for $designId fell back to the export: " +
          ((outcome as? UiBuilderNativePreviewOutcome.Refused)?.reasons?.joinToString("; ")
            ?: rendered?.failure)
      )
      return null
    }
    val png =
      rendered.response.image
        ?.takeIf { it.startsWith(PNG_DATA_URI) }
        ?.let {
          runCatching { Base64.getDecoder().decode(it.removePrefix(PNG_DATA_URI)) }.getOrNull()
        } ?: return null
    return Entry(document.revision, generation, png)
  }

  private fun store(designId: String, entry: Entry, pinned: Boolean = false) {
    memory[if (pinned) revisionKey(designId, entry.revision) else designId] = entry
    runCatching {
      val base = fileBase(designId) + if (pinned) "-r${entry.revision}" else ""
      val png = directory.resolve("$base.png")
      val meta = directory.resolve("$base.meta")
      writeAtomically(png, entry.png)
      writeAtomically(
        meta,
        ("${entry.revision}\n${entry.generation}\n$designId\n" +
            if (entry.unframed) "$UNFRAMED\n" else "")
          .toByteArray(Charsets.UTF_8),
      )
    }
      .onFailure { onLog("serve: UI-builder thumbnail for $designId not saved: ${it.message}") }
  }

  private fun readEntry(designId: String, revision: Long? = null): Entry? {
    val base = fileBase(designId) + (revision?.let { "-r$it" } ?: "")
    val meta = directory.resolve("$base.meta")
    val png = directory.resolve("$base.png")
    if (!Files.isRegularFile(meta) || !Files.isRegularFile(png)) return null
    val lines = Files.readAllLines(meta, Charsets.UTF_8)
    if (lines.size < 3 || lines[2] != designId) return null
    return Entry(
      lines[0].toLong(),
      lines[1],
      Files.readAllBytes(png),
      unframed = lines.getOrNull(3) == UNFRAMED,
    )
  }

  private fun writeAtomically(target: Path, bytes: ByteArray) {
    val temp = Files.createTempFile(directory, ".thumb", ".tmp")
    try {
      Files.write(temp, bytes)
      Files.move(
        temp,
        target,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } finally {
      Files.deleteIfExists(temp)
    }
  }

  override fun close() {
    guidelineFrames?.close()
    worker.interrupt()
    jobs.forEach { it.result.complete(null) }
    jobs.clear()
  }

  internal companion object {
    /** Deep enough for every card on a large listing; beyond it a card waits for the next view. */
    const val QUEUE = 512

    private const val PNG_DATA_URI = "data:image/png;base64,"

    /** Beside the thumbnails, so one directory holds every picture drawn of a design. */
    const val GUIDELINE_FRAMES_DIRECTORY = "guideline-frames"

    /** The design an accepted [request] changed, when it is an edit; see [warming]. */
    private fun editedDesignId(request: UiBuilderServiceRequest): String? =
      when (request) {
        is UiBuilderServiceRequest.ApplyOperation -> request.submission.designId
        is UiBuilderServiceRequest.RestoreRevision -> request.designId
        is UiBuilderServiceRequest.MoveDesignHome -> request.designId
        is UiBuilderServiceRequest.ReplaceDesignDocument -> request.designId
        else -> null
      }

    /** Native draws of one revision before an unframed picture of it stops counting as stale. */
    const val NATIVE_ATTEMPTS = 3

    private const val NATIVE_SUFFIX = "\u0000n"

    /** The `.meta` line marking an [Entry.unframed] picture; absent in files written before it. */
    private const val UNFRAMED = "unframed"

    /** Cannot appear in a design id segment, so a revision key never collides with a design. */
    private const val REVISION_SEPARATOR = "\u0000r"

    private fun revisionKey(designId: String, revision: Long?): String =
      if (revision == null) designId else "$designId$REVISION_SEPARATOR$revision"

    /**
     * A picture's generation: [serveVersion] plus the PNG pipeline's identity ([renderer],
     * `PackagedUiBuilderRenderBundle.digest()`), so a renderer-only upgrade still invalidates old
     * pictures. Falls back to the server version if the identity can't be read.
     */
    internal fun generationOf(serveVersion: String, renderer: () -> String): String =
      runCatching(renderer).map { "$serveVersion+$it" }.getOrDefault(serveVersion)

    /** A file name for [designId] that no id can escape the directory with. */
    fun fileBase(designId: String): String =
      MessageDigest.getInstance("SHA-256")
        .digest(designId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(40)
  }
}

/**
 * `GET …/designs/{designId}/thumbnail.png[?revision=N]`: the listing card image. Cached at the
 * listed revision ⇒ cacheable; older ⇒ served `no-store` and redrawn behind; none ⇒ rendered now.
 */
internal suspend fun ApplicationCall.serveUiBuilderThumbnail(
  thumbnails: ServeUiBuilderThumbnails,
  service: UiBuilderServicePort,
  actor: AuthenticatedUiBuilderActor,
  designId: String,
  revision: Long?,
  /**
   * Whether this caller passed the `ui-builder-export` route check; see [ServeUiBuilderThumbnails].
   */
  native: Boolean = false,
) {
  val allowed = service.designActions(actor, designId)
  if (allowed == null || DesignAccessActionV1.EXPORT !in allowed) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("not found", status = HttpStatusCode.NotFound)
    return
  }
  val cached = thumbnails.cached(designId)
  val current = revision != null && thumbnails.isCurrent(designId, revision, native)
  val entry =
    when {
      current -> cached
      cached != null -> {
        thumbnails.warm(designId, actor, native = native)
        cached
      }
      // Drawn by the one worker, ahead of any redraw, and shared with the listing's own request.
      else ->
        withTimeoutOrNull(COLD_RENDER_TIMEOUT_MS) {
          thumbnails.submit(designId, actor, urgent = true, native = native).await()
        }
    }
  if (entry == null) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("no thumbnail", status = HttpStatusCode.NotFound)
    return
  }
  val fresh =
    revision != null &&
      entry.revision == revision &&
      entry.generation == thumbnails.generation &&
      // An unframed widget a caller who may compile is about to have redrawn is not kept.
      (!native || !entry.unframed || thumbnails.isCurrent(designId, revision, native = true))
  response.headers.append(
    HttpHeaders.CacheControl,
    // Keyed by revision in the URL, so a current picture never changes under it; private because
    // it was served behind the reader's own credential.
    if (fresh) "private, max-age=86400" else "no-store",
  )
  response.headers.append(UI_BUILDER_REVISION_HEADER, entry.revision.toString())
  respondBytes(entry.png, ContentType.Image.PNG, HttpStatusCode.OK)
}

/**
 * `GET …/designs/{designId}/revisions/{revision}/thumbnail.png`: one retained revision's picture
 * for history, cacheable once drawn, first drawn through the same worker.
 */
internal suspend fun ApplicationCall.serveUiBuilderRevisionThumbnail(
  thumbnails: ServeUiBuilderThumbnails,
  service: UiBuilderServicePort,
  actor: AuthenticatedUiBuilderActor,
  designId: String,
  revision: Long,
  /**
   * Whether this caller passed the `ui-builder-export` route check; see [ServeUiBuilderThumbnails].
   */
  native: Boolean = false,
) {
  val allowed = service.designActions(actor, designId)
  if (allowed == null || DesignAccessActionV1.EXPORT !in allowed) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("not found", status = HttpStatusCode.NotFound)
    return
  }
  val cached = thumbnails.cachedRevision(designId, revision)
  val entry =
    if (cached != null && !thumbnails.awaitsNativeRedraw(cached, designId, revision, native)) cached
    else
      withTimeoutOrNull(COLD_RENDER_TIMEOUT_MS) {
        thumbnails
          .submit(designId, actor, urgent = false, revision = revision, native = native)
          .await()
      } ?: cached
  if (entry == null || entry.revision != revision) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("no thumbnail", status = HttpStatusCode.NotFound)
    return
  }
  response.headers.append(
    HttpHeaders.CacheControl,
    // A retained revision never changes, unless it is an unframed widget still due its native draw.
    if (thumbnails.awaitsNativeRedraw(entry, designId, revision, native)) "no-store"
    else "private, max-age=604800, immutable",
  )
  response.headers.append(UI_BUILDER_REVISION_HEADER, entry.revision.toString())
  respondBytes(entry.png, ContentType.Image.PNG, HttpStatusCode.OK)
}

private suspend fun <T : Any> retrying(block: suspend () -> T?): T? {
  repeat(THUMBNAIL_RENDER_ATTEMPTS - 1) {
    block()?.let {
      return it
    }
    kotlinx.coroutines.delay(THUMBNAIL_RETRY_DELAY_MS)
  }
  return block()
}

private const val THUMBNAIL_RENDER_ATTEMPTS = 3
private const val THUMBNAIL_RETRY_DELAY_MS = 750L

/** How long a card waits for its first picture before answering without one. */
private const val COLD_RENDER_TIMEOUT_MS = 60_000L
