package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFrame
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePicture
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The pictures a guidelines prompt attaches, drawn once per design revision and kept on disk beside
 * the design list's thumbnails.
 *
 * ## Why
 *
 * Every frame [DesignGuidelineFrames.plan] names is a native render: generated Compose, compiled
 * and drawn on a daemon. Drawn on every request, a widget's two containers took 78s and a scrolling
 * Wear screen's device and unrolled pictures 118s — and the same again when asked twice, so the
 * editor's **Show the prompt** sat for two minutes and an MCP call could outlast the client. Kept
 * here, a second ask of the same revision costs a file read.
 *
 * ## The same picture is not drawn twice
 *
 * A frame is keyed by everything that decides its pixels: the design and revision, the [generation]
 * the thumbnails are drawn at (server version and renderer identity), the frame's kind, its size
 * and its widget host shape. Size is in the key on purpose: a phone and a tablet picture of one
 * revision are two renders, and must never answer for each other. Where the design list's thumbnail
 * *is* the frame — a Wear widget's thumbnail is its native render in the
 * [WearWidgetHostShape.Squircle] container, the Pixel Watch frame — the thumbnail is taken instead
 * of drawing it again; [ServeUiBuilderThumbnails] says when that holds.
 *
 * ## Ahead of the reader, behind everything else
 *
 * One worker draws frames, one at a time (native renders queue behind each other anyway, and a
 * burst is compose-preview-server#1421). A reader waiting on frames jumps the queue, and they draw
 * in the order it asked for them; a frame queued to warm the cache waits while the thumbnail worker
 * has work, so the design list and the editor's own exports go first. Warming follows an accepted
 * edit after a quiet period, so a design being edited is drawn once it settles rather than at every
 * keystroke, and only its latest revision is drawn.
 *
 * Warming compiles Kotlin, which a caller may only ask for with the `ui-builder-export` route
 * capability. An edit carries no route check, so a design is warmed only once somebody holding that
 * capability has asked for its prompt with pictures; the warm runs as that actor.
 */
class ServeUiBuilderGuidelineFrames
internal constructor(
  private val directory: Path,
  val generation: String,
  /** Whether the thumbnail worker is idle, so a warming frame does not queue ahead of it. */
  private val thumbnailsIdle: () -> Boolean = { true },
  private val quietPeriodMillis: Long = QUIET_PERIOD_MILLIS,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : Closeable {

  /** What decides a frame's pixels; see "The same picture is not drawn twice". */
  internal data class Key(
    val designId: String,
    val revision: Long,
    val kind: String,
    val widthDp: Int,
    val heightDp: Int,
    val hostShape: String?,
  )

  /** Draws [document] in [shape]; null when the host could not. Set once the host has a lane. */
  @Volatile internal var render: ((DesignDocumentV1, WearWidgetHostShape) -> ByteArray?)? = null

  /**
   * What the warm of a design draws: its latest document and the frames planned for it, read as
   * [actor]; null when there is nothing to draw. Set by the MCP tools, which own the plan.
   */
  @Volatile
  internal var planner:
    (suspend (designId: String, actor: AuthenticatedUiBuilderActor) -> Pair<
        DesignDocumentV1,
        List<DesignGuidelineFrame>,
      >?)? =
    null

  private val memory = ConcurrentHashMap<Key, ByteArray>()

  private class Job(
    val key: Key,
    val document: DesignDocumentV1,
    val frame: DesignGuidelineFrame,
    val background: Boolean,
    val result: CompletableFuture<ByteArray?> = CompletableFuture(),
  )

  /**
   * Frames a reader is waiting on, in the order asked: drawn before any warm. A queue of their own,
   * not the front of [warms], because pushing a reader's frames onto the front one by one drew them
   * last-asked first, so the slowest frame of a request could hold back the one it listed first
   * past the budget.
   */
  private val readers = LinkedBlockingQueue<Job>(QUEUE)
  private val warms = LinkedBlockingDeque<Job>(QUEUE)
  /** One permit per job offered to either queue; the worker takes one before each poll. */
  private val queued = Semaphore(0)
  private val pending = ConcurrentHashMap<Key, Job>()

  /** Designs a capable caller has asked for pictures of, and as whom to warm them. */
  private val warmable = ConcurrentHashMap<String, AuthenticatedUiBuilderActor>()
  private val scheduled = ConcurrentHashMap<String, ScheduledFuture<*>>()
  private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "ui-builder-guideline-frames-warm").apply { isDaemon = true }
  }

  private val worker =
    Thread(
        {
          while (!Thread.currentThread().isInterrupted) {
            try {
              queued.acquire()
            } catch (_: InterruptedException) {
              break
            }
            // A warm promoted to a reader leaves its permit behind; the poll then finds nothing.
            val job = readers.poll() ?: warms.poll() ?: continue
            // A warming frame waits while the design list's thumbnails are drawing, unless a reader
            // has since asked for it (it is then no longer in [pending] as a background job).
            if (job.background && !thumbnailsIdle() && readers.isEmpty()) {
              warms.offerLast(job)
              queued.release()
              try {
                Thread.sleep(BACKGROUND_BACKOFF_MILLIS)
              } catch (_: InterruptedException) {
                break
              }
              continue
            }
            val png =
              try {
                cached(job.key) ?: draw(job.document, job.frame)?.also { store(job.key, it) }
              } catch (failure: Exception) {
                onLog("serve: UI-builder guideline frame ${job.key} not drawn: ${failure.message}")
                null
              }
            pending.remove(job.key, job)
            job.result.complete(png)
          }
        },
        "ui-builder-guideline-frames",
      )
      .apply { isDaemon = true }

  init {
    ServeOwnerOnlyFiles.createDirectories(directory)
    worker.start()
  }

  /** The key [frame] of [document] is kept under. */
  internal fun keyOf(designId: String, document: DesignDocumentV1, frame: DesignGuidelineFrame) =
    Key(
      designId,
      document.revision,
      frame.kind,
      frame.widthDp,
      frame.heightDp,
      frame.environment[WearWidgetHostShape.ENVIRONMENT_KEY]?.content,
    )

  /** The kept picture for [key], from memory or disk; null when it was never drawn. */
  internal fun cached(key: Key): ByteArray? =
    memory[key]
      ?: runCatching { Files.readAllBytes(fileOf(key)) }.getOrNull()?.also { memory[key] = it }

  /** Keeps [png] as [key]'s picture, and forgets the design's other revisions. */
  internal fun store(key: Key, png: ByteArray) {
    memory[key] = png
    memory.keys.removeIf { it.designId == key.designId && it.revision != key.revision }
    runCatching {
      val dir = directory.resolve(designBase(key.designId))
      ServeOwnerOnlyFiles.createDirectories(dir)
      Files.newDirectoryStream(dir, "*.png").use { files ->
        files
          .filterNot { it.fileName.toString().startsWith("r${key.revision}-") }
          .forEach { Files.deleteIfExists(it) }
      }
      val temp = Files.createTempFile(dir, ".frame", ".tmp")
      try {
        Files.write(temp, png)
        Files.move(
          temp,
          fileOf(key),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        Files.deleteIfExists(temp)
      }
    }
      .onFailure { onLog("serve: UI-builder guideline frame ${key} not saved: ${it.message}") }
  }

  /**
   * The drawing of [frame] queued or running, or a new one: a reader's behind the other frames
   * readers are waiting on and ahead of every warm, a warm at the back. A reader asking for a frame
   * already queued to warm moves it to the readers' queue rather than queueing it twice.
   */
  internal fun submit(
    key: Key,
    document: DesignDocumentV1,
    frame: DesignGuidelineFrame,
    background: Boolean,
  ): CompletableFuture<ByteArray?> {
    cached(key)?.let {
      return CompletableFuture.completedFuture(it)
    }
    val job = Job(key, document, frame, background)
    val existing = pending.putIfAbsent(key, job)
    if (existing != null) {
      if (!background && existing.background && warms.remove(existing)) {
        val promoted = Job(key, existing.document, existing.frame, false, existing.result)
        pending[key] = promoted
        if (readers.offer(promoted)) {
          queued.release()
        } else {
          pending.remove(key, promoted)
          existing.result.complete(null)
        }
      }
      return existing.result
    }
    val offered = if (background) warms.offerLast(job) else readers.offer(job)
    if (offered) queued.release()
    if (!offered) {
      pending.remove(key, job)
      job.result.complete(null)
    }
    return job.result
  }

  /**
   * [frames] of [designId]'s [document]: each from the cache, from [seeds] (pictures the caller
   * already holds, such as the a11y check's device render), or drawn — one at a time, within
   * [budgetMillis] in all. A frame not drawn in time is left out of the answer and keeps drawing
   * into the cache, so the next ask includes it; [Drawn.pending] names those.
   */
  internal suspend fun pictures(
    designId: String,
    document: DesignDocumentV1,
    frames: List<DesignGuidelineFrame>,
    budgetMillis: Long,
    seeds: Map<String, ByteArray> = emptyMap(),
  ): Drawn {
    val deadline = System.nanoTime() + budgetMillis * 1_000_000
    val keys = frames.associateWith { keyOf(designId, document, it) }
    // Everything missing is queued at once, so the worker never idles between two frames this
    // reader is waiting on; they still draw one at a time.
    val futures = frames.associateWith { frame ->
      val key = keys.getValue(frame)
      cached(key)?.let { CompletableFuture.completedFuture(it) }
        ?: seeds[frame.kind]?.let { seed ->
          store(key, seed)
          CompletableFuture.completedFuture(seed)
        }
        ?: submit(key, document, frame, background = false)
    }
    val drawn = mutableListOf<Pair<DesignGuidelineFrame, ByteArray>>()
    val late = mutableListOf<DesignGuidelineFrame>()
    for (frame in frames) {
      val future = futures.getValue(frame)
      val left = (deadline - System.nanoTime()) / 1_000_000
      val png =
        if (future.isDone) future.getNow(null)
        else if (left <= 0) null
        // A copy is awaited: `await` cancels the future it waits on when the timeout cancels it,
        // and this one is the worker's, which must go on drawing into the cache.
        else withTimeoutOrNull(left) { future.copy().await() }
      when {
        png != null -> drawn += frame to png
        !future.isDone -> late += frame
      }
    }
    return Drawn(drawn, late)
  }

  /** What [pictures] could attach now, and what is still drawing. */
  internal data class Drawn(
    val pictures: List<Pair<DesignGuidelineFrame, ByteArray>>,
    val pending: List<DesignGuidelineFrame>,
  )

  /** [designId] may be warmed after edits, as [actor]; see the class comment. */
  internal fun rememberWarmable(designId: String, actor: AuthenticatedUiBuilderActor) {
    warmable[designId] = actor
  }

  /**
   * After an accepted edit to [designId]: once nothing has changed it for the quiet period, queue
   * its frames at its then-latest revision. Further edits inside the period push the warm back, so
   * one burst of edits draws once. A design nobody capable has asked about is not warmed.
   */
  internal fun edited(designId: String) {
    val actor = warmable[designId] ?: return
    scheduled
      .put(
        designId,
        scheduler.schedule(
          { warmNow(designId, actor) },
          quietPeriodMillis,
          TimeUnit.MILLISECONDS,
        ),
      )
      ?.cancel(false)
  }

  /** Queues [designId]'s planned frames as background work; the quiet period has passed. */
  internal fun warmNow(designId: String, actor: AuthenticatedUiBuilderActor) {
    scheduled.remove(designId)
    val plan = planner ?: return
    val planned =
      try {
        runBlocking { plan(designId, actor) }
      } catch (failure: Exception) {
        onLog("serve: UI-builder guideline frames for $designId not planned: ${failure.message}")
        null
      } ?: return
    val (document, frames) = planned
    frames.forEach { frame ->
      submit(keyOf(designId, document, frame), document, frame, background = true)
    }
  }

  /** Forget [designId]'s frames, in memory and on disk; its id may come back as another design. */
  internal fun evict(designId: String) {
    memory.keys.removeIf { it.designId == designId }
    warmable.remove(designId)
    scheduled.remove(designId)?.cancel(false)
    runCatching {
      val dir = directory.resolve(designBase(designId))
      if (Files.isDirectory(dir)) {
        Files.newDirectoryStream(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(dir)
      }
    }
      .onFailure {
        onLog("serve: UI-builder guideline frames for $designId not removed: ${it.message}")
      }
  }

  private fun draw(document: DesignDocumentV1, frame: DesignGuidelineFrame): ByteArray? {
    val lane = render ?: return null
    val shape =
      frame.environment[WearWidgetHostShape.ENVIRONMENT_KEY]?.content?.let {
        WearWidgetHostShape.fromId(it)
      } ?: WearWidgetHostShape.Default
    return lane(framed(document, frame), shape)
  }

  private fun fileOf(key: Key): Path =
    directory
      .resolve(designBase(key.designId))
      .resolve(
        "r${key.revision}-" +
          digest(
            "${key.revision}|$generation|${key.kind}|${key.widthDp}x${key.heightDp}|" +
              "${key.hostShape}"
          ) +
          ".png"
      )

  override fun close() {
    worker.interrupt()
    scheduler.shutdownNow()
    (readers + warms).forEach { it.result.complete(null) }
    readers.clear()
    warms.clear()
  }

  internal companion object {
    const val QUEUE = 256

    /** How long a design stays unedited before its frames are warmed. */
    const val QUIET_PERIOD_MILLIS = 30_000L

    private const val BACKGROUND_BACKOFF_MILLIS = 1_000L

    /** [document] with [frame]'s size written over its environment, as the renderer draws it. */
    fun framed(document: DesignDocumentV1, frame: DesignGuidelineFrame): DesignDocumentV1 =
      if (frame.kind == DesignGuidelinePicture.DEVICE && frame.environment.isEmpty()) document
      else
        document.copy(
          environment =
            document.environment.copy(widthDp = frame.widthDp, heightDp = frame.heightDp).let {
              environment ->
              // An evidence frame's overrides: the dark theme, a larger font scale.
              val theme =
                frame.environment["theme"]?.content?.let { name ->
                  ee.schimke.composeai.uibuilder.protocol.ThemeV1.entries.firstOrNull {
                    it.name.equals(name, ignoreCase = true)
                  }
                }
              val fontScale = frame.environment["fontScale"]?.content?.toDoubleOrNull()
              environment.copy(
                theme = theme ?: environment.theme,
                fontScale = fontScale ?: environment.fontScale,
              )
            }
        )

    /**
     * Whether [png] is a picture of [frame] rather than of some other size: its pixel extent in the
     * frame's aspect, so both axes are drawn at one density. A renderer that ignored the frame and
     * drew its default size (a 412×915 phone frame and a 1280×800 tablet frame both coming back as
     * the same 400×800) fails this, and a frame failing it is left out rather than shown to the
     * model as something it is not.
     */
    fun matchesFrame(png: ByteArray, frame: DesignGuidelineFrame): Boolean {
      val (width, height) = pngSize(png) ?: return false
      if (frame.widthDp <= 0 || frame.heightDp <= 0) return true
      val xScale = width.toDouble() / frame.widthDp
      val yScale = height.toDouble() / frame.heightDp
      return xScale > 0 && abs(xScale - yScale) / maxOf(xScale, yScale) <= SCALE_TOLERANCE
    }

    /** Within this, two axes' px-per-dp are one density; rounding to whole pixels stays inside. */
    private const val SCALE_TOLERANCE = 0.03

    private fun pngSize(png: ByteArray): Pair<Int, Int>? {
      if (png.size < 24 || png[12] != 'I'.code.toByte() || png[13] != 'H'.code.toByte()) return null
      fun int(offset: Int) =
        ((png[offset].toInt() and 0xff) shl 24) or
          ((png[offset + 1].toInt() and 0xff) shl 16) or
          ((png[offset + 2].toInt() and 0xff) shl 8) or
          (png[offset + 3].toInt() and 0xff)
      return int(16) to int(20)
    }

    private fun designBase(designId: String): String = digest(designId)

    private fun digest(text: String): String =
      MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(40)
  }
}
