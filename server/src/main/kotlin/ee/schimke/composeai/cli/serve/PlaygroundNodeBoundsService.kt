package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.DataFetchResult
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsPayload
import ee.schimke.composeai.data.layoutinspector.ComposeSemanticsProduct
import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Capture **where each UI-builder node drew** on a compiled snippet's frame: `testTag → box in
 * render pixels`, read off the render's `compose/semantics` tree.
 *
 * ## Why this exists
 *
 * The UI-builder's native lane tags every node it generates (`ScreenDocumentProjection.project(…,
 * tagNodes = true)` appends `Modifier.testTag("<nodeId>")`, and only that lane asks for it). Tags
 * alone make the frame no less of a picture: without the rectangle each tag occupies, the pane
 * cannot outline the selected node or turn a click into a selection. The semantics product is
 * exactly that mapping — every [ee.schimke.composeai.data.layoutinspector.ComposeSemanticsNode]
 * carries its `testTag` and its `boundsInRoot` — so the capture is one more fetch beside the frame
 * rather than any new machinery in the renderer.
 *
 * ## Shape
 *
 * The same open → enable → render → await → fetch shape as [PlaygroundRcCaptureService], over a
 * bundle-less daemon standing on the snippet's own compiled classes. `compose/semantics`, like
 * every extension, registers **inactive**: enable it before the render or the product is never
 * populated and `fetchData` rejects the kind as not advertised.
 *
 * The projection into boxes is [ServeSemanticsTags.index], which the parity lane already uses for
 * the same question ("which pixels does this tag own"), including its two load-bearing rules: an
 * unplaced trial-measured copy is not a second use of a tag, and a tag is only an identity while
 * exactly one node carries it. A tag whose nodes have no usable box, and a duplicated tag, are
 * dropped here — an overlay drawn on a guess is worse than no overlay.
 *
 * [openSession] is injected exactly as in the sibling services, so the orchestration is testable
 * against a fake `RenderSession` without a daemon subprocess. Every miss is an empty map, never an
 * exception: a design whose semantics capture fails still has its frame, and the overlay is an
 * addition to the picture rather than a precondition for it.
 */
class PlaygroundNodeBoundsService(
  private val openSession: PlaygroundAndroidSessionOpener,
  /** Mints a fresh scratch dir per capture (holds the synthesized manifest + render outputs). */
  private val newWorkDir: () -> File,
  private val renderBudget: Duration = DEFAULT_RENDER_BUDGET,
  private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
) {

  /** Snippet → `testTag`-keyed boxes in render pixels, or an empty map on any miss. */
  fun capture(snippet: PlaygroundTokenStore.PlaygroundSnippet): Map<String, AnnotationBounds> {
    val workDir = newWorkDir().apply { mkdirs() }
    return try {
      val previewsJson =
        File(workDir, "previews.json").apply {
          writeText(PlaygroundPreviews.previewManifestJson(snippet))
        }
      // The playground's classpath entries are already absolute okio paths; File(toString()) is the
      // safe bridge to the java.io.File the render-session API takes.
      val classesDir = File(snippet.classesDir.toString())
      val userClasspath = snippet.classpath.map { File(it.toString()).absolutePath }
      val session = openSession.open(classesDir, previewsJson, workDir, userClasspath)
      try {
        renderAndFetch(session, snippet.previewId)
      } finally {
        runCatching { session.close() }
      }
    } catch (_: Exception) {
      // A capture failure is a clean "no bounds" to the caller; it must never escape as a throwable
      // and take the frame down with it.
      emptyMap()
    } finally {
      runCatching { workDir.deleteRecursively() }
    }
  }

  private fun renderAndFetch(
    session: RenderSession,
    previewId: String,
  ): Map<String, AnnotationBounds> {
    // Enable before rendering: the semantics snapshot is collected by the extension's own render
    // hook, which runs only while the extension is active. A backend that reports the id unknown
    // carries no semantics producer — a clean "no bounds", like every other miss here.
    val enabled =
      runCatching { session.enableExtensions(listOf(SEMANTICS_EXTENSION_ID)) }.getOrNull()
        ?: return emptyMap()
    if (SEMANTICS_EXTENSION_ID in enabled.unknown) return emptyMap()

    val latch = CountDownLatch(1)
    val failed = AtomicReference(false)
    val handle = session.onNotification { method, params ->
      if (params == null) return@onNotification
      val id = params["id"]?.jsonPrimitive?.contentOrNull ?: return@onNotification
      if (id != previewId) return@onNotification
      when (method) {
        "renderFinished" -> latch.countDown()
        "renderFailed" -> {
          failed.set(true)
          latch.countDown()
        }
      }
    }
    return handle.use {
      val ack =
        session.renderNow(
          listOf(previewId),
          reason = "ui-builder-node-bounds",
          timeout = ackTimeout,
        )
      if (ack.rejected.isNotEmpty()) return@use emptyMap()
      if (!latch.await(renderBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS))
        return@use emptyMap()
      if (failed.get()) return@use emptyMap()
      val fetched =
        runCatching {
          session.fetchData(previewId, ComposeSemanticsProduct.KIND, inline = true)
        }
          .getOrNull() ?: return@use emptyMap()
      val payload = runCatching { decode(fetched) }.getOrNull() ?: return@use emptyMap()
      ServeSemanticsTags.index(payload)
        .mapNotNull { (tag, entry) ->
          // `count > 1` is a tag two nodes carry, which no longer identifies one of them. Compose
          // does not enforce uniqueness and the projection tags by node id, so this should not
          // happen — and if it does, the honest answer is no rectangle rather than one of two.
          if (entry.count != 1) null else entry.bounds?.let { tag to it }
        }
        .toMap()
    }
  }

  /**
   * Read a `compose/semantics` result off whichever transport the daemon answered on.
   *
   * All three are legal answers to one `data/fetch`, and which one arrives depends on the daemon
   * and the size of the tree rather than on anything this caller controls — `inline = true` is a
   * request, not a guarantee. Reading only the inline payload would make the capture work against
   * one backend and silently produce no overlay on another.
   */
  private fun decode(result: DataFetchResult): ComposeSemanticsPayload {
    result.payload?.let {
      return json.decodeFromJsonElement(ComposeSemanticsPayload.serializer(), it)
    }
    result.bytes?.let {
      val text = String(Base64.getDecoder().decode(it), Charsets.UTF_8)
      return json.decodeFromString(ComposeSemanticsPayload.serializer(), text)
    }
    result.path?.let {
      return json.decodeFromString(ComposeSemanticsPayload.serializer(), File(it).readText())
    }
    error("empty data/fetch result (no payload, bytes, or path)")
  }

  companion object {
    /**
     * The daemon's semantics extension **id** (`DaemonMain`'s `tryAdd("compose/semantics")`, in
     * both the desktop and the Android daemon). It happens to read the same as
     * [ComposeSemanticsProduct.KIND], the data-product kind, and the two are resolved by different
     * registries — `extensions/enable` by id, `data/fetch` by kind.
     */
    const val SEMANTICS_EXTENSION_ID: String = "compose/semantics"

    /** Cold Android/Robolectric renders take tens of seconds; budget generously. */
    val DEFAULT_RENDER_BUDGET: Duration = 180.seconds
    val DEFAULT_ACK_TIMEOUT: Duration = 30.seconds

    private val json = Json {
      encodeDefaults = true
      ignoreUnknownKeys = true
    }
  }
}
