package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.render.session.RenderSession
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Opens a bundle-less render session over a compiled playground snippet, shared by
 * [PlaygroundAndroidRenderService] and [PlaygroundRcCaptureService]. Production binds it to
 * [SubprocessRenderSessions.openBundleDaemon]; tests supply a fake.
 */
fun interface PlaygroundAndroidSessionOpener {
  fun open(
    classesDir: File,
    previewsJson: File,
    workspaceRoot: File,
    userClasspath: List<String>,
  ): RenderSession
}

/**
 * The production [PlaygroundCompileService] `renderFirstFrame`: render a freshly compiled snippet
 * on a daemon and return its PNG (`docs/design/PLAYGROUND.md` §7). Backend-agnostic: [openSession]
 * picks desktop Skiko or Robolectric.
 *
 * Flow: synthesize a `previews.json` from the discovered `@Preview` ids ([PlaygroundPreviews]);
 * [openSession] over the snippet's `classesDir` and compile classpath; `renderNow` and await
 * `renderFinished` / `renderFailed`; read the PNG at `pngPath` (no data-product fetch, unlike RC
 * capture). Returns null on any miss, which only means the response carries no still; the token is
 * still minted.
 */
class PlaygroundAndroidRenderService(
  private val openSession: PlaygroundAndroidSessionOpener,
  /** Mints a fresh scratch dir per render (holds the synthesized manifest + render outputs). */
  private val newWorkDir: () -> File,
  private val renderBudget: Duration = DEFAULT_RENDER_BUDGET,
  private val ackTimeout: Duration = DEFAULT_ACK_TIMEOUT,
) {

  /**
   * The [PlaygroundCompileService] `renderFirstFrame` seam: snippet → first-frame PNG bytes, or
   * null.
   */
  fun render(snippet: PlaygroundTokenStore.PlaygroundSnippet): ByteArray? = renderFrame(snippet).png

  /**
   * [render] plus the reason when there is no frame, so causes like `UnsatisfiedLinkError` reach
   * the UI builder rather than only the host log.
   */
  fun renderFrame(snippet: PlaygroundTokenStore.PlaygroundSnippet): PlaygroundFirstFrame {
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
        // Enable the named-override connector before the render, since its data product is
        // registered inactive. Best-effort: without it there are just no knobs.
        val knobsArmed =
          runCatching { session.enableExtensions(listOf(OVERRIDES_EXTENSION_ID)) }
            .getOrNull()
            ?.let { OVERRIDES_EXTENSION_ID !in it.unknown } == true
        val attempt = renderFirstFrame(session, snippet.previewId)
        val png = attempt.png
        if (png == null) reportNoFrame(snippet, attempt.reason)
        if (png != null && knobsArmed) drainOverrideDeclarations(session, snippet)
        PlaygroundFirstFrame(png, if (png == null) attempt.reason else null)
      } finally {
        runCatching { session.close() }
      }
    } catch (t: Exception) {
      // A render failure becomes a clean "no frame", never a throwable, but is logged so the cause
      // isn't lost.
      val reason = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
      reportNoFrame(snippet, reason)
      PlaygroundFirstFrame(null, reason)
    } finally {
      runCatching { workDir.deleteRecursively() }
    }
  }

  /**
   * The one place a swallowed render failure is logged: [render] returns only `ByteArray?`, so
   * nothing downstream can explain the absence.
   */
  private fun reportNoFrame(snippet: PlaygroundTokenStore.PlaygroundSnippet, reason: String?) {
    System.err.println(
      "serve: no first frame for ${snippet.previewId} (${snippet.mode.name}) — " +
        (reason ?: "no reason reported")
    )
  }

  /**
   * One render attempt: the PNG, or why not. Six distinct causes (rejected request, expired budget,
   * `renderFailed`, no file named, bad path, unreadable file) each get their own reason.
   */
  private data class FrameAttempt(val png: ByteArray?, val reason: String? = null)

  private fun renderFirstFrame(session: RenderSession, previewId: String): FrameAttempt {
    val latch = CountDownLatch(1)
    val pngPath = AtomicReference<String?>()
    val failure = AtomicReference<String?>()
    val handle = session.onNotification { method, params ->
      if (params == null) return@onNotification
      val id = (params["id"] as? JsonPrimitive)?.contentOrNull ?: return@onNotification
      if (id != previewId) return@onNotification
      when (method) {
        "renderFinished" -> {
          try {
            (params["pngPath"] as? JsonPrimitive)?.contentOrNull?.let { pngPath.set(it) }
          } finally {
            latch.countDown()
          }
        }
        "renderFailed" -> {
          // Released whatever the payload shape: the daemon's `error` is an object, and parsing it
          // used to throw before the count-down, turning an instant failure into a budget expiry
          // minutes later.
          try {
            failure.set(
              renderFailureDetail(params)?.let { "daemon reported renderFailed: $it" }
                ?: "daemon reported renderFailed"
            )
          } finally {
            latch.countDown()
          }
        }
      }
    }
    return handle.use {
      val ack =
        session.renderNow(
          listOf(previewId),
          reason = "playground-first-frame",
          timeout = ackTimeout,
        )
      if (ack.rejected.isNotEmpty()) {
        return@use FrameAttempt(null, "the daemon rejected the render request")
      }
      if (!latch.await(renderBudget.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
        return@use FrameAttempt(null, "the render budget of $renderBudget expired")
      }
      failure.get()?.let {
        return@use FrameAttempt(null, it)
      }
      val path =
        pngPath.get() ?: return@use FrameAttempt(null, "the render finished without a PNG path")
      val file = File(path)
      if (!file.isFile) return@use FrameAttempt(null, "the rendered PNG $path is not a file")
      runCatching { file.readBytes() }
        .fold(
          onSuccess = { FrameAttempt(it) },
          onFailure = {
            FrameAttempt(null, "the rendered PNG $path could not be read: ${it.message}")
          },
        )
    }
  }

  /**
   * Persist the knobs the snippet's `@Preview` declared during the render (`compose/overrides`)
   * into the snippet's own work dir as `previews/<id>.overrides.json`, the sidecar
   * [ServeBundleDaemon.readPreviews] folds into [ServePreview.overrides], so a redeemed `/pg/`
   * session gets the live knob drawer. Written to `snippet.workDir` (owned by the token) since this
   * service's [workDir] is deleted. Best-effort; covers only the rendered preview.
   */
  private fun drainOverrideDeclarations(
    session: RenderSession,
    snippet: PlaygroundTokenStore.PlaygroundSnippet,
  ) {
    runCatching {
      val payload =
        session.fetchData(snippet.previewId, OVERRIDES_KIND).payload?.jsonObject ?: return
      // An empty declaration list is the common case (a preview with no knobs); writing it would
      // just leave a useless file for readOverrideSidecar to parse into nothing.
      if (payload["declarations"]?.jsonArray?.isEmpty() != false) return
      val previewsDir = File(snippet.workDir.toString(), "previews").apply { mkdirs() }
      File(previewsDir, "${snippet.previewId}$OVERRIDES_SIDECAR_SUFFIX")
        .writeText(payload.toString())
    }
  }

  companion object {
    /** The property `ServeRenderHost` reads for the same quantity — a cold-start render budget. */
    internal const val RENDER_BUDGET_PROPERTY: String = "composeai.serve.renderTimeoutSeconds"

    /** The value this budget falls back to, and what it meant before it was configurable. */
    internal val FALLBACK_RENDER_BUDGET: Duration = 180.seconds

    /**
     * Cold render budget for one first frame: `composeai.serve.renderTimeoutSeconds`, default 180s.
     * Every render here is cold (a fresh daemon per call), hence the cold-start property rather
     * than the per-frame one. Shares the property `ServeRenderHost` honours, so an operator raising
     * it affects both lanes (#481).
     */
    val DEFAULT_RENDER_BUDGET: Duration
      get() = renderBudgetFrom(System.getProperty(RENDER_BUDGET_PROPERTY))

    /**
     * The property as a budget, or [FALLBACK_RENDER_BUDGET] when absent or invalid. Clamped to at
     * least one second, like `ServeRenderHost`, since a non-positive budget would report an expiry
     * it never waited for.
     */
    internal fun renderBudgetFrom(property: String?): Duration =
      property?.toLongOrNull()?.coerceAtLeast(1)?.seconds ?: FALLBACK_RENDER_BUDGET

    val DEFAULT_ACK_TIMEOUT: Duration = 30.seconds

    /**
     * The named-override connector's id (`DaemonMain`'s `tryAdd("data/overrides")`), distinct from
     * the data-product kind; `extensions/enable` resolves by id.
     */
    const val OVERRIDES_EXTENSION_ID: String = "data/overrides"

    /** The data-product kind carrying the declared knobs. */
    const val OVERRIDES_KIND: String = "compose/overrides"

    /**
     * Sidecar suffix, in lockstep with `PreviewBundleFormat.BUNDLE_OVERRIDES_SIDECAR_EXT` and
     * [ServeBundleDaemon.readPreviews]'s `OVERRIDES_SUFFIX`.
     */
    const val OVERRIDES_SIDECAR_SUFFIX: String = ".overrides.json"

    /**
     * Where a `renderFailed` notification may carry its cause, most specific first; backends spell
     * it differently.
     */
    private val FAILURE_DETAIL_KEYS = listOf("message", "reason", "error")

    /**
     * The cause a `renderFailed` notification carries, as one line, or null. The daemon sends
     * `error` as `{kind, message, suggestion}` and kind and suggestion are kept as the actionable
     * half; a bare string under any [FAILURE_DETAIL_KEYS] is also read. Never throws: it runs in a
     * notification listener, where a throw would turn into a full budget wait.
     */
    internal fun renderFailureDetail(params: JsonObject): String? {
      val error = params["error"] as? JsonObject
      if (error != null) {
        fun text(key: String) =
          (error[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val message = text("message")
        val kind = text("kind")
        val suggestion = text("suggestion")
        if (message != null || suggestion != null) {
          return buildString {
            if (kind != null) append("[").append(kind).append("] ")
            append(message ?: "no message")
            if (suggestion != null) append(" — ").append(suggestion)
          }
        }
      }
      return FAILURE_DETAIL_KEYS.firstNotNullOfOrNull { key ->
        (params[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
      }
    }
  }
}
