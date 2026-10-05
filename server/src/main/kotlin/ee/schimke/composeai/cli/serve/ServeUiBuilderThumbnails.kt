package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
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
 * The picture on each card of `/ui-builder/designs`, kept on disk and redrawn ahead of the reader.
 *
 * ## Why the listing does not link the live export
 *
 * The cards used to point their `<img>` at `/designs/{id}/export.svg`, which renders the design on
 * every request: a second or more each on the one renderer the editor's own exports also queue on,
 * a page of cards at a time. Cards that lost that race drew nothing, and the SVG itself pins every
 * line of text to the width the renderer measured (`textLength`), so a browser whose font is not
 * the renderer's squeezed the lettering. The PNG export is the renderer's own pixels and draws the
 * same everywhere, so a thumbnail is that PNG.
 *
 * ## Stale is fine, missing is not
 *
 * A thumbnail is kept per design with the revision and the server generation it was drawn at. A
 * request for a newer revision still gets the picture this cache holds — a card showing the design
 * as it was a minute ago is better than a blank card — and the redraw is queued behind it, so the
 * next page view has the current one. Only a design never drawn at all is rendered while the reader
 * waits. The generation is the server version, so a deploy (a new renderer) redraws every design in
 * the background while the old pictures keep serving.
 *
 * ## Ahead of the reader
 *
 * [warming] wraps the service so every accepted edit, from any lane — editor, MCP, admin — queues a
 * redraw of the design it changed, and the listing page queues every card it finds out of date. One
 * worker, because the renderer answers a second concurrent render with "busy" and the editor's
 * interactive exports matter more than a thumbnail.
 *
 * ## Wear widgets are drawn in their host
 *
 * A Wear widget's PNG export is the widget's content at its environment size, with square corners
 * and no host container around it, so on the card it read as a squashed rectangle rather than the
 * widget a person put on their watch. Where the host has the [nativePreview] lane, a widget design
 * is drawn through it instead: the generated `WearWidgetPreview`, compiled and rendered on the
 * device renderer, inside the [WearWidgetHostShape.Squircle] container — the round-rect frame the
 * host really draws, and the render recommended for the widget picker — rather than corners faked
 * in CSS. Anything that lane cannot draw falls back to the export, because an approximate picture
 * is still better than a blank card.
 *
 * ## Access
 *
 * The bytes on disk carry no access record, so every serve asks the design's own access control
 * first ([designActions]): a reader must hold the design's EXPORT action, which is the check the
 * export itself would have made. Queued redraws run as the actor whose edit or page view queued
 * them, so the redraw is an export that actor was already allowed to make.
 */
class ServeUiBuilderThumbnails
internal constructor(
  private val directory: Path,
  val generation: String,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : Closeable {
  /** One cached picture: the bytes, and what they were drawn from. */
  internal data class Entry(val revision: Long, val generation: String, val png: ByteArray)

  private val memory = ConcurrentHashMap<String, Entry>()

  /** One render waiting for, or holding, the worker; every caller asking for it shares [result]. */
  private class Job(
    val designId: String,
    val actor: AuthenticatedUiBuilderActor,
    /** A retained revision to draw, for the history view; null draws the latest. */
    val revision: Long? = null,
    val result: CompletableFuture<Entry?> = CompletableFuture(),
  ) {
    val key: String
      get() = revisionKey(designId, revision)
  }

  private val jobs = LinkedBlockingDeque<Job>(QUEUE)
  private val pending = ConcurrentHashMap<String, Job>()

  @Volatile private var service: UiBuilderServicePort? = null

  /**
   * The native render lane, set once the server has one; null draws every design through the
   * export. See "Wear widgets are drawn in their host" above.
   */
  @Volatile internal var nativePreview: UiBuilderNativePreviewLane? = null

  /**
   * The one worker. Every render goes through it, a card's own request included, because the
   * renderer answers a second concurrent render with "busy": a card waiting for its first picture
   * jumps the queue ([submit] with `urgent`) rather than racing the redraws behind it.
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
                runBlocking { retrying { render(job.designId, job.actor, job.revision) } }
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
   * [delegate], with every accepted edit queueing a redraw of the design it changed, and every
   * deletion or creation forgetting the picture held under that id; the result is also what this
   * cache renders through.
   */
  internal fun warming(delegate: UiBuilderServicePort): UiBuilderServicePort {
    val wrapped =
      object : UiBuilderServicePort by delegate {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
          val request = call.request
          // Before as well as after: a picture of the design an id used to name must not be what
          // a design created under that id shows, however the create itself turns out.
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
          return response
        }
      }
    service = delegate
    return wrapped
  }

  /**
   * The branch lane, redrawing what it changes: a merge commits to the parent outside
   * [UiBuilderServicePort], so without this the parent's card would show it as it was before the
   * merge, and a new branch would have no card until its first edit.
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
   * Forget [designId]'s picture, in memory and on disk.
   *
   * Pictures are keyed by id, and an id outlives its design: deleted, it can be created or put back
   * as a different design by a different owner, who must never be served the old pixels. So every
   * lane that deletes or replaces a design calls this — the service wrapper for the HTTP and MCP
   * lanes, and the admin lane, which deletes beneath the service port.
   */
  internal fun evict(designId: String) {
    memory.remove(designId)
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
   * The cached picture of [designId] as it was at [revision], for the history view. A retained
   * revision never changes, so once drawn it is kept until the design is deleted.
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

  /** Whether the cache holds [designId] drawn at [revision] by this generation. */
  internal fun isCurrent(designId: String, revision: Long): Boolean =
    cached(designId)?.let { it.revision == revision && it.generation == generation } == true

  /**
   * Queue a redraw of [designId] at its latest revision, as [actor]; a no-op when one is queued.
   *
   * [knownRevision] lets a caller that already knows the revision (the listing) skip a design the
   * cache already has current.
   */
  internal fun warm(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    knownRevision: Long? = null,
  ) {
    if (knownRevision != null && isCurrent(designId, knownRevision)) return
    submit(designId, actor, urgent = false)
  }

  /**
   * The render of [designId] that is queued or running, or a new one: at the front of the queue
   * when [urgent], at the back otherwise. A full queue answers null at once.
   */
  internal fun submit(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    urgent: Boolean,
    revision: Long? = null,
  ): CompletableFuture<Entry?> {
    val job = Job(designId, actor, revision)
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
   * Render [designId] now — at its latest revision, or at [revision] for the history view — and
   * keep it; null when the export refused.
   */
  internal suspend fun render(
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    revision: Long? = null,
  ): Entry? {
    val port = service ?: return null
    nativeWidget(port, designId, actor, revision)?.let {
      store(designId, it, pinned = revision != null)
      return it
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
    val entry = Entry(servedRevision, generation, Base64.getDecoder().decode(artifact.content))
    store(designId, entry, pinned = revision != null)
    return entry
  }

  /**
   * [designId] drawn by the [nativePreview] lane inside its widget host, or null when it is not a
   * Wear widget, this host has no such lane, or the lane could not draw it.
   *
   * Asks the same EXPORT question the export would, and reads the document through the service as
   * [actor], so this is never a way to picture a design the actor could not export.
   */
  private suspend fun nativeWidget(
    port: UiBuilderServicePort,
    designId: String,
    actor: AuthenticatedUiBuilderActor,
    revision: Long?,
  ): Entry? {
    val lane = nativePreview ?: return null
    if (DesignAccessActionV1.EXPORT !in port.designActions(actor, designId).orEmpty()) return null
    val snapshot =
      try {
        port.executeMapped(GetSnapshotRequestV1(designId = designId, revision = revision), actor)
          as? UiBuilderServiceResponse.Snapshot
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        null
      } ?: return null
    val document = snapshot.snapshot.state.document
    if (!RecordFreeExport.isWearWidget(document)) return null
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
        "${entry.revision}\n${entry.generation}\n$designId\n".toByteArray(Charsets.UTF_8),
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
    return Entry(lines[0].toLong(), lines[1], Files.readAllBytes(png))
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
    worker.interrupt()
    jobs.forEach { it.result.complete(null) }
    jobs.clear()
  }

  internal companion object {
    /** Deep enough for every card on a large listing; beyond it a card waits for the next view. */
    const val QUEUE = 512

    private const val PNG_DATA_URI = "data:image/png;base64,"

    /** Cannot appear in a design id segment, so a revision key never collides with a design. */
    private const val REVISION_SEPARATOR = "\u0000r"

    private fun revisionKey(designId: String, revision: Long?): String =
      if (revision == null) designId else "$designId$REVISION_SEPARATOR$revision"

    /** A file name for [designId] that no id can escape the directory with. */
    fun fileBase(designId: String): String =
      MessageDigest.getInstance("SHA-256")
        .digest(designId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(40)
  }
}

/**
 * `GET /api/ui-builder/v1/designs/{designId}/thumbnail.png[?revision=N]`: the listing card's image.
 *
 * `revision` is the revision the listing saw. A picture cached at it (by this generation) is
 * answered as cacheable; anything older is answered at once with `no-store` and redrawn behind the
 * response; nothing cached at all is rendered now.
 */
internal suspend fun ApplicationCall.serveUiBuilderThumbnail(
  thumbnails: ServeUiBuilderThumbnails,
  service: UiBuilderServicePort,
  actor: AuthenticatedUiBuilderActor,
  designId: String,
  revision: Long?,
) {
  val allowed = service.designActions(actor, designId)
  if (allowed == null || DesignAccessActionV1.EXPORT !in allowed) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("not found", status = HttpStatusCode.NotFound)
    return
  }
  val cached = thumbnails.cached(designId)
  val current = revision != null && thumbnails.isCurrent(designId, revision)
  val entry =
    when {
      current -> cached
      cached != null -> {
        thumbnails.warm(designId, actor)
        cached
      }
      // Drawn by the one worker, ahead of any redraw, and shared with the listing's own request.
      else ->
        withTimeoutOrNull(COLD_RENDER_TIMEOUT_MS) {
          thumbnails.submit(designId, actor, urgent = true).await()
        }
    }
  if (entry == null) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("no thumbnail", status = HttpStatusCode.NotFound)
    return
  }
  val fresh =
    revision != null && entry.revision == revision && entry.generation == thumbnails.generation
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
 * `GET /api/ui-builder/v1/designs/{designId}/revisions/{revision}/thumbnail.png`: one retained
 * revision's picture, for the history view. A revision never changes, so a drawn one is served as
 * cacheable; the first request draws it through the same single worker the listing uses.
 */
internal suspend fun ApplicationCall.serveUiBuilderRevisionThumbnail(
  thumbnails: ServeUiBuilderThumbnails,
  service: UiBuilderServicePort,
  actor: AuthenticatedUiBuilderActor,
  designId: String,
  revision: Long,
) {
  val allowed = service.designActions(actor, designId)
  if (allowed == null || DesignAccessActionV1.EXPORT !in allowed) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("not found", status = HttpStatusCode.NotFound)
    return
  }
  val entry =
    thumbnails.cachedRevision(designId, revision)
      ?: withTimeoutOrNull(COLD_RENDER_TIMEOUT_MS) {
        thumbnails.submit(designId, actor, urgent = false, revision = revision).await()
      }
  if (entry == null || entry.revision != revision) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("no thumbnail", status = HttpStatusCode.NotFound)
    return
  }
  response.headers.append(HttpHeaders.CacheControl, "private, max-age=604800, immutable")
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
