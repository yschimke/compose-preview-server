package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

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
 * redraw of the design it changed, and the listing page queues every card it finds out of date.
 * One worker, because the renderer answers a second concurrent render with "busy" and the editor's
 * interactive exports matter more than a thumbnail.
 *
 * ## Access
 *
 * The bytes on disk carry no access record, so every serve asks the design's own access control
 * first ([designActions]): a reader must hold the design's EXPORT action, which is the check the
 * export itself would have made. Queued redraws run as the actor whose edit or page view queued
 * them, so the redraw is an export that actor was already allowed to make.
 */
class ServeUiBuilderThumbnails internal constructor(
  private val directory: Path,
  val generation: String,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : Closeable {
  /** One cached picture: the bytes, and what they were drawn from. */
  internal data class Entry(val revision: Long, val generation: String, val png: ByteArray)

  private val memory = ConcurrentHashMap<String, Entry>()
  private val queued = ConcurrentHashMap.newKeySet<String>()

  @Volatile private var service: UiBuilderServicePort? = null

  private val executor =
    ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        LinkedBlockingDeque(QUEUE),
        { runnable -> Thread(runnable, "ui-builder-thumbnails").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
      )
      .apply { allowCoreThreadTimeOut(true) }

  init {
    Files.createDirectories(directory)
  }

  /**
   * [delegate], with every accepted edit queueing a redraw of the design it changed; the result is
   * also what this cache renders through.
   */
  internal fun warming(delegate: UiBuilderServicePort): UiBuilderServicePort {
    val wrapped =
      object : UiBuilderServicePort by delegate {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
          val response = delegate.execute(call)
          if (response !is UiBuilderServiceResponse.Error) {
            when (val request = call.request) {
              is UiBuilderServiceRequest.ApplyOperation ->
                warm(request.submission.designId, call.actor)
              else -> Unit
            }
          }
          return response
        }
      }
    service = delegate
    return wrapped
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
  internal fun warm(designId: String, actor: AuthenticatedUiBuilderActor, knownRevision: Long? = null) {
    // A design with no picture at all is drawn by the card's own request, which is already on its
    // way; queueing it too would only race that render for the one renderer.
    if (knownRevision != null && (cached(designId) == null || isCurrent(designId, knownRevision)))
      return
    if (!queued.add(designId)) return
    // A full queue would discard the task without running its `finally`, so the claim would stick.
    if (executor.queue.remainingCapacity() == 0) {
      queued.remove(designId)
      return
    }
    try {
      executor.execute {
        try {
          runBlocking { render(designId, actor) }
        } catch (failure: Exception) {
          onLog("serve: UI-builder thumbnail for $designId failed: ${failure.message}")
        } finally {
          queued.remove(designId)
        }
      }
    } catch (rejected: java.util.concurrent.RejectedExecutionException) {
      queued.remove(designId)
    }
  }

  /** Render [designId] now at its latest revision and keep it; null when the export refused. */
  internal suspend fun render(designId: String, actor: AuthenticatedUiBuilderActor): Entry? {
    val port = service ?: return null
    val response =
      try {
        port.executeMapped(
          ExportDesignRequestV1(designId = designId, revision = null, format = ExportFormatV1.PNG),
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
    val revision = artifact.servedRevision()?.toLongOrNull() ?: return null
    val entry = Entry(revision, generation, Base64.getDecoder().decode(artifact.content))
    store(designId, entry)
    return entry
  }

  private fun store(designId: String, entry: Entry) {
    memory[designId] = entry
    runCatching {
        val base = fileBase(designId)
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

  private fun readEntry(designId: String): Entry? {
    val base = fileBase(designId)
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
    executor.shutdownNow()
  }

  internal companion object {
    /** Deep enough for every card on a large listing; beyond it a card waits for the next view. */
    const val QUEUE = 512

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
      // The renderer answers "busy" rather than queueing, and a background redraw may hold it.
      else -> retrying { thumbnails.render(designId, actor) }
    }
  if (entry == null) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText("no thumbnail", status = HttpStatusCode.NotFound)
    return
  }
  val fresh = revision != null && entry.revision == revision && entry.generation == thumbnails.generation
  response.headers.append(
    HttpHeaders.CacheControl,
    // Keyed by revision in the URL, so a current picture never changes under it; private because
    // it was served behind the reader's own credential.
    if (fresh) "private, max-age=86400" else "no-store",
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
