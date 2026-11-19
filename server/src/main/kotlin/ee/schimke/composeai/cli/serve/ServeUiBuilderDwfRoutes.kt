package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What the `android-dwf` routes need from the host. Null on a host with no UI builder; a host that
 * has the builder but not the runtime still gets the routes, and they say so ([status]).
 */
class UiBuilderDwfPreview
internal constructor(
  internal val sessions: DwfPreviewSessions,
  /**
   * Whether a builder catalog's native backend is `android-dwf`
   * (`statusSemantics.previewSurfaces.native.backend`), so a Compose catalog's design is not
   * offered a watch-face session.
   */
  internal val isDwfCatalog: (catalogId: String) -> Boolean,
  /**
   * The WFF a revision exports, derived by the server from the document; null until the exporter
   * (compose-ui-builder) is released and consumed. Until then the editor sends its own XML in the
   * start request.
   */
  internal val sourceFor: (DesignDocumentV1) -> DwfSource? = { null },
  /**
   * Where a design's uploaded asset bytes are, so its pinned assets are packaged as the drawables
   * its WFF names ([WatchFaceDrawables]); the editor sends only the XML.
   */
  internal val assets: UiBuilderAssetStore? = null,
  /**
   * The packager's validators run on a revision's face (`watchface-validation`). Unavailable by
   * default; the host wires [WatchFaceValidatorAvailability.production].
   */
  internal val validation: WatchFaceValidation =
    WatchFaceValidation(
      {
        WatchFaceValidatorAvailability.Unavailable(
          DwfPreviewSurface.PACKAGING_UNAVAILABLE,
          "this server does not validate watch faces",
        )
      },
    ),
  /** How often a ticking session redraws. One second is the watch's own granularity. */
  internal val tickMillis: Long = 1000,
  /**
   * How often a ticking session redraws a face that moves between seconds ([DwfSource.animates]).
   * Each frame is a screenshot of a Robolectric sandbox, so this is a ceiling, not a promise: the
   * loop never asks for the next frame before the last one has been sent.
   */
  internal val animatedTickMillis: Long = 250,
)

internal const val UI_BUILDER_DWF_PREVIEW_PATH = "/api/ui-builder/v1/designs/{designId}/dwf-preview"
internal const val UI_BUILDER_DWF_STATUS_PATH = "$UI_BUILDER_DWF_PREVIEW_PATH/status"
internal const val UI_BUILDER_DWF_SESSION_PATH = "$UI_BUILDER_DWF_PREVIEW_PATH/{sessionId}"
internal const val UI_BUILDER_DWF_SOCKET_PATH = "$UI_BUILDER_DWF_SESSION_PATH/ws"
internal const val UI_BUILDER_WATCHFACE_VALIDATION_PATH =
  "/api/ui-builder/v1/designs/{designId}/watchface-validation"

/**
 * The editor-supplied part of a start request. All optional: a bare `{}` asks the server to derive
 * the source from the design ([UiBuilderDwfPreview.sourceFor]).
 */
@Serializable
internal data class DwfPreviewStartRequestV1(
  val wffXml: String? = null,
  val wffVersion: Int = DwfPreviewSurface.DEFAULT_WFF_VERSION,
  /** Path inside `res/` → base64 bytes. */
  val assets: Map<String, String> = emptyMap(),
  val label: String? = null,
  /**
   * Answer at once (`202 Accepted`) and do the export, the packaging and the first frame in the
   * background, reporting each [DwfStage] on the session's status and socket. Without it the
   * request packages before answering, as it always has, and a packager refusal is its `422`.
   */
  val async: Boolean = false,
)

@Serializable
internal data class DwfPreviewStartedV1(
  val schema: String = "compose-preview/ui-builder-dwf-preview/v1",
  val designId: String,
  val revision: Long,
  val sessionId: String,
  val wffVersion: Int,
  /** The session's socket, relative to this server's origin; the page picks `ws:`/`wss:`. */
  val socketPath: String,
  /** The tab: open this in a new window. */
  val pagePath: String,
  /** The session's [DwfPreviewSessionStatusV1], relative to this server's origin. */
  val statusPath: String? = null,
  /** Where the session is now: `queued` or the first working stage for an async start. */
  val stage: String? = null,
)

/**
 * One session's way to its first frame. The same fields arrive on the socket as a `stage` message
 * (`{"type":"stage",…}`) whenever the stage changes, and about once a second while it has not.
 */
@Serializable
internal data class DwfPreviewSessionStatusV1(
  val schema: String = "compose-preview/ui-builder-dwf-preview-session/v1",
  val sessionId: String,
  /**
   * `queued`, `exporting`, `packaging`, `starting-runtime`, `installing`, `loading-face`, `live`,
   * `failed`.
   */
  val stage: String,
  /** The stage's place among [steps] working stages; 0 queued, [steps] live, kept on failure. */
  val step: Int,
  val steps: Int,
  /** A sentence for the pane: "Packaging the watch face", "Using the cached package", … */
  val label: String,
  val elapsedMillis: Long,
  val stageElapsedMillis: Long,
  val cachedPackage: Boolean,
  /** Sessions ahead of this one for a free runtime; only while `queued`. */
  val queueAhead: Int? = null,
  /** On `failed`: the refusal code and the diagnostic (a packager refusal's text, verbatim). */
  val code: String? = null,
  val reason: String? = null,
  /** How often a live, ticking session sends a frame. */
  val frameIntervalMillis: Long? = null,
)

@Serializable
internal data class DwfPreviewRefusalV1(
  val schema: String = "compose-preview/ui-builder-dwf-preview-refusal/v1",
  val code: String,
  val reasons: List<String>,
)

@Serializable
internal data class DwfPreviewStatusV1(
  val schema: String = "compose-preview/ui-builder-dwf-preview-status/v1",
  val available: Boolean,
  val code: String? = null,
  val reason: String? = null,
  val sessions: Int,
  val maxSessions: Int,
  /** Async starts waiting for one of the [maxSessions] runtime slots. */
  val queued: Int = 0,
)

internal fun Route.installUiBuilderDwfRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  dwf: UiBuilderDwfPreview,
  /**
   * [ServeFeatureFlags.nativePreview]: off, the runtime is never started, the status says so, and a
   * start answers 503 [ServeFeatureFlags.NATIVE_PREVIEW_DISABLED].
   */
  enabled: Boolean = true,
) {
  /**
   * Whether the real runtime can run here. Read-gated and design-scoped like every other route
   * under a design id, so a caller cannot probe host configuration for a design it cannot open.
   */
  get(UI_BUILDER_DWF_STATUS_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      (authorization.authorize(call, UiBuilderRouteCapability.READ)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    val designId = call.parameters["designId"].orEmpty()
    if (actor == null || !service.canRead(actor, designId)) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return@get
    }
    val unavailable =
      if (!enabled) {
        DwfApkRuntime.Availability.Unavailable(
          ServeFeatureFlags.NATIVE_PREVIEW_DISABLED,
          "this server does not offer the Native watch-face preview",
        )
      } else {
        dwf.sessions.availability() as? DwfApkRuntime.Availability.Unavailable
      }
    call.respondText(
      UI_BUILDER_JSON.encodeToString(
        DwfPreviewStatusV1(
          available = unavailable == null,
          code = unavailable?.code,
          reason = unavailable?.reason,
          sessions = dwf.sessions.sessionCount,
          maxSessions = DwfPreviewSessions.DEFAULT_MAX_SESSIONS,
          queued = dwf.sessions.queuedCount,
        ),
      ),
      ContentType.Application.Json,
    )
  }

  /**
   * Start a session on one revision. A POST on EXPORT, like the native render: it feeds a design's
   * content to `aapt2` and a sandboxed runtime, which is the same trust as exporting it.
   */
  post(UI_BUILDER_DWF_PREVIEW_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val (actor, designId, document, request) =
      call.receiveDwfDesignRequest(service, authorization, dwf) ?: return@post
    // After authorization and the design's own checks, before anything is packaged or started,
    // and before the async start too: every session, async and queued ones included, is born here.
    if (!enabled) {
      call.respondDwfRefusal(
        HttpStatusCode.ServiceUnavailable,
        ServeFeatureFlags.NATIVE_PREVIEW_DISABLED,
        "this server does not offer the Native watch-face preview",
      )
      return@post
    }
    if (request.async) {
      val editorSource =
        request.wffXml?.let {
          when (val resolved = resolveDwfSource(dwf, document, request)) {
            is ResolvedSource.Ok -> resolved.source
            is ResolvedSource.Missing -> {
              call.respondDwfRefusal(
                HttpStatusCode.UnprocessableEntity,
                WatchFaceDrawables.MISSING_RESOURCE,
                resolved.missing,
              )
              return@post
            }
            is ResolvedSource.Refused -> {
              call.respondDwfRefusal(
                HttpStatusCode.UnprocessableEntity,
                resolved.code,
                resolved.reasons,
              )
              return@post
            }
          }
        }
      val started =
        dwf.sessions.startAsync(
          designId = designId,
          revision = document.revision,
          ownerActorId = actor.actorId,
          wffVersion = editorSource?.wffVersion ?: request.wffVersion,
          exports = editorSource == null,
          controls = LIVE_CONTROLS,
        ) {
          if (editorSource != null) DwfPreviewSessions.Resolution.Ok(editorSource)
          else
            when (val resolved = resolveDwfSource(dwf, document, request)) {
              is ResolvedSource.Ok -> DwfPreviewSessions.Resolution.Ok(resolved.source)
              is ResolvedSource.Missing ->
                DwfPreviewSessions.Resolution.Refused(
                  WatchFaceDrawables.MISSING_RESOURCE,
                  resolved.missing.joinToString("\n"),
                )
              is ResolvedSource.Refused ->
                // A session's failure is one diagnostic; each missing resource keeps its line.
                DwfPreviewSessions.Resolution.Refused(
                  resolved.code,
                  resolved.reasons.joinToString("\n"),
                )
            }
        }
      call.respondDwfStarted(designId, document.revision, started, HttpStatusCode.Accepted)
      return@post
    }
    val source =
      when (val resolved = resolveDwfSource(dwf, document, request)) {
        is ResolvedSource.Ok -> resolved.source
        is ResolvedSource.Missing -> {
          call.respondDwfRefusal(
            HttpStatusCode.UnprocessableEntity,
            WatchFaceDrawables.MISSING_RESOURCE,
            resolved.missing,
          )
          return@post
        }
        is ResolvedSource.Refused -> {
          call.respondDwfRefusal(
            HttpStatusCode.UnprocessableEntity,
            resolved.code,
            resolved.reasons,
          )
          return@post
        }
      }
    // Packaging (aapt2, zipalign, the validators) happens here so the packager's diagnostics are a
    // 422 on this request; the daemon boot is on the first frame. Off the event loop either way.
    val started =
      withContext(Dispatchers.IO) {
        dwf.sessions.start(designId, document.revision, actor.actorId, source, LIVE_CONTROLS)
      }
    call.respondDwfStarted(designId, document.revision, started, HttpStatusCode.Created)
  }

  /** One session's stage, for a client that polls rather than holds the socket. */
  get(UI_BUILDER_DWF_SESSION_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      (authorization.authorize(call, UiBuilderRouteCapability.EXPORT)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    val session = actor?.let { ownedSession(dwf, it, call) }
    if (session == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(
      UI_BUILDER_JSON.encodeToString(dwfSessionStatus(dwf, session)),
      ContentType.Application.Json,
    )
  }

  /**
   * Validate one revision's face with the packager's validators (Google's WFF validator, the memory
   * footprint, the Watch Face Push checks) and say what each found. EXPORT, like starting a
   * session: the same source goes to the same packager. A failing face is a 200 report with
   * `passed: false`; only a request that names no usable face is a 422.
   */
  post(UI_BUILDER_WATCHFACE_VALIDATION_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val (_, designId, document, request) =
      call.receiveDwfDesignRequest(service, authorization, dwf) ?: return@post
    val result =
      when (val validated = dwf.validateDesign(designId, document, request)) {
        is DwfDesignValidation.Ran -> validated.result
        is DwfDesignValidation.Refused -> {
          call.respondDwfRefusal(
            HttpStatusCode.UnprocessableEntity,
            validated.code,
            validated.reasons,
          )
          return@post
        }
      }
    when (result) {
      is WatchFaceValidation.Result.Ok ->
        call.respondText(
          UI_BUILDER_JSON.encodeToString(WatchFaceValidationReportV1.serializer(), result.report),
          ContentType.Application.Json,
        )
      is WatchFaceValidation.Result.Unavailable ->
        call.respondDwfRefusal(HttpStatusCode.ServiceUnavailable, result.code, result.reason)
      is WatchFaceValidation.Result.Failed ->
        call.respondDwfRefusal(HttpStatusCode.BadGateway, result.code, result.reason)
    }
  }

  /** The last report on the design's current revision (or `?revision=`), else 404. */
  get(UI_BUILDER_WATCHFACE_VALIDATION_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      (authorization.authorize(call, UiBuilderRouteCapability.READ)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    val designId = call.parameters["designId"].orEmpty()
    val revisionParameter = call.request.queryParameters["revision"]
    val revision = revisionParameter?.toLongOrNull()
    if (revisionParameter != null && (revision == null || revision < 0)) {
      call.respondText("a revision, if given, is non-negative", status = HttpStatusCode.BadRequest)
      return@get
    }
    val snapshot =
      actor
        ?.takeIf { designId.isNotBlank() }
        ?.let {
          service.executeMapped(GetSnapshotRequestV1(designId = designId, revision = revision), it)
            as? UiBuilderServiceResponse.Snapshot
        }
    val report = snapshot?.let {
      it.snapshot.state.document.let { document ->
        dwf.validation.latest(designId, document.createdAtEpochMillis, document.revision)
      }
    }
    if (report == null) {
      call.respondText("no validation report for this revision", status = HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(
      UI_BUILDER_JSON.encodeToString(WatchFaceValidationReportV1.serializer(), report),
      ContentType.Application.Json,
    )
  }

  delete(UI_BUILDER_DWF_SESSION_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      (authorization.authorize(call, UiBuilderRouteCapability.EXPORT)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    val session = actor?.let { ownedSession(dwf, it, call) }
    if (session == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return@delete
    }
    dwf.sessions.close(session.id)
    call.respondText("", status = HttpStatusCode.NoContent)
  }

  webSocket(UI_BUILDER_DWF_SOCKET_PATH) {
    val actor =
      (authorization.authorize(call, UiBuilderRouteCapability.EXPORT)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    if (actor == null) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UI-builder export access required"))
      return@webSocket
    }
    val session = ownedSession(dwf, actor, call)
    if (session == null) {
      close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "no such preview session"))
      return@webSocket
    }
    serveDwfSocket(dwf, session)
  }
}

/** [UiBuilderDwfPreview.validateDesign]'s answer: a report (or why none), or an unusable face. */
internal sealed interface DwfDesignValidation {
  data class Ran(val result: WatchFaceValidation.Result) : DwfDesignValidation

  /** The request names no face that can be validated (a 422 over HTTP). */
  data class Refused(val code: String, val reasons: List<String>) : DwfDesignValidation
}

/**
 * Validate [document]'s face as `watchface-validation` does, for the route and the MCP tool alike:
 * the editor's XML if [request] carries any, else the server's export, with the design's pinned
 * drawables; a bitmap nothing supplies is a failed check, not a refusal.
 */
internal suspend fun UiBuilderDwfPreview.validateDesign(
  designId: String,
  document: DesignDocumentV1,
  request: DwfPreviewStartRequestV1 = DwfPreviewStartRequestV1(),
): DwfDesignValidation =
  when (val resolved = resolveDwfSource(this, document, request)) {
    is ResolvedSource.Ok ->
      DwfDesignValidation.Ran(
        validation.validate(
          designId,
          document.createdAtEpochMillis,
          document.revision,
          resolved.source,
        ),
      )
    is ResolvedSource.Missing ->
      DwfDesignValidation.Ran(
        WatchFaceValidation.Result.Ok(
          validation.missingResources(
            designId,
            document.createdAtEpochMillis,
            document.revision,
            resolved.source,
            resolved.missing,
          ),
        ),
      )
    is ResolvedSource.Refused -> DwfDesignValidation.Refused(resolved.code, resolved.reasons)
  }

private data class DwfDesignRequest(
  val actor: AuthenticatedUiBuilderActor,
  val designId: String,
  val document: DesignDocumentV1,
  val request: DwfPreviewStartRequestV1,
)

/**
 * An EXPORT-authorised request on one revision of a watch-face design, with its optional
 * [DwfPreviewStartRequestV1] body; null once a refusal has been written.
 */
private suspend fun ApplicationCall.receiveDwfDesignRequest(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  dwf: UiBuilderDwfPreview,
): DwfDesignRequest? {
  val call = this
  val actor =
    when (val decision = authorization.authorize(call, UiBuilderRouteCapability.EXPORT)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        call.respondText("authentication is required", status = HttpStatusCode.Unauthorized)
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        call.respondText("UI-builder export access required", status = HttpStatusCode.Forbidden)
        return null
      }
    }
  val designId = call.parameters["designId"].orEmpty()
  val revisionParameter = call.request.queryParameters["revision"]
  val revision = revisionParameter?.toLongOrNull()
  if (designId.isBlank() || (revisionParameter != null && (revision == null || revision < 0))) {
    call.respondText(
      "a design id and, if given, a non-negative revision are required",
      status = HttpStatusCode.BadRequest,
    )
    return null
  }
  val snapshot =
    service.executeMapped(GetSnapshotRequestV1(designId = designId, revision = revision), actor)
      as? UiBuilderServiceResponse.Snapshot
  if (snapshot == null) {
    call.respondText("no such design", status = HttpStatusCode.NotFound)
    return null
  }
  val document = snapshot.snapshot.state.document
  if (!dwf.isDwfCatalog(document.catalogPin.systemId)) {
    call.respondDwfRefusal(
      HttpStatusCode.UnprocessableEntity,
      DwfPreviewSurface.SOURCE_INVALID,
      "catalog `${document.catalogPin.systemId}` does not declare the " +
        "`${DwfPreviewSurface.BACKEND}` native backend, so this design is not a watch face",
    )
    return null
  }
  val bytes =
    withContext(Dispatchers.IO) {
      call.receiveStream().use { it.readNBytes(MAX_UI_BUILDER_REQUEST_BYTES + 1) }
    }
  if (bytes.size > MAX_UI_BUILDER_REQUEST_BYTES) {
    call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
    return null
  }
  val request =
    try {
      UI_BUILDER_JSON.decodeFromString(
        DwfPreviewStartRequestV1.serializer(),
        bytes.toString(StandardCharsets.UTF_8).ifBlank { "{}" },
      )
    } catch (_: SerializationException) {
      call.respondText(
        "request body is not valid JSON for this route",
        status = HttpStatusCode.BadRequest,
      )
      return null
    }
  return DwfDesignRequest(actor, designId, document, request)
}

/**
 * The tab's socket: the server half of `composeai-stream/1` (`lane`, `frame`, `error`, the same
 * shapes the viewer's own socket uses, so a frame handler written for that works here), with
 * `stage` messages of its own ([DwfPreviewSessionStatusV1] plus `"type":"stage"`) while the session
 * is on its way to its first frame, and one client message of its own, `controls`, because the
 * viewer's `setOverrides` is a flat string map keyed on a served preview, and a watch face's
 * controls are typed.
 *
 * Client → server: `{"type":"controls","controls":{…}}` (a partial [DwfControls.patch]),
 * `{"type":"requestFrame"}`, `{"type":"visibility","visible":false}` (a hidden tab gets no frames
 * until it says it is visible again). A frame follows every accepted `controls` and, while the
 * session is live and `ticking` (the default: a session starts on the wall clock), every
 * [UiBuilderDwfPreview.tickMillis] — or [UiBuilderDwfPreview.animatedTickMillis] for a face that
 * moves between seconds.
 *
 * The socket holds the session: while one is attached the session is never released as abandoned,
 * and when the last one goes the session survives a reload's worth of grace and no more.
 */
private suspend fun DefaultWebSocketServerSession.serveDwfSocket(
  dwf: UiBuilderDwfPreview,
  session: DwfPreviewSessions.Session,
) {
  var visible = true
  dwf.sessions.attach(session)
  try {
    send(Frame.Text(dwfLaneMessage(dwf, session)))

    suspend fun sendFrame(frame: DwfPreviewSessions.Frame) {
      when (frame) {
        is DwfPreviewSessions.Frame.Ok ->
          send(
            Frame.Text(
              ServeStreamProtocol.frameMessage(
                frame.seq,
                frame.widthPx ?: 0,
                frame.heightPx ?: 0,
                frame.png,
              ),
            ),
          )
        is DwfPreviewSessions.Frame.Failed ->
          send(Frame.Text(ServeStreamProtocol.errorMessage("${frame.code}: ${frame.reason}")))
      }
    }

    suspend fun drawFrame() {
      sendFrame(withContext(Dispatchers.IO) { dwf.sessions.frame(session.id) })
    }

    // The way to the first frame: stage messages while an async start prepares (or while this
    // socket's own first frame boots the runtime), then that frame.
    coroutineScope {
      val reporter = launch {
        var last: DwfStage? = null
        var sentAt = 0L
        while (isActive) {
          val progress = dwf.sessions.progress(session)
          val at = System.currentTimeMillis()
          if (progress.stage != last || at - sentAt >= STAGE_HEARTBEAT_MILLIS) {
            send(Frame.Text(dwfStageMessage(dwf, session)))
            last = progress.stage
            sentAt = at
          }
          if (progress.stage.terminal && !session.preparing) break
          delay(STAGE_POLL_MILLIS)
        }
      }
      while (session.preparing) delay(STAGE_POLL_MILLIS)
      val warm = session.warmFrame?.also { session.warmFrame = null }
      when {
        warm != null -> sendFrame(warm)
        session.stage != DwfStage.FAILED -> drawFrame()
      }
      reporter.cancelAndJoin()
      send(Frame.Text(dwfStageMessage(dwf, session)))
      if (session.stage == DwfStage.FAILED) {
        session.failure?.let { sendFrame(it) }
      }
    }

    // One loop does both jobs: block for a client message for at most a tick, and if nothing came
    // and the face is ticking, redraw. `receiveCatching`-with-timeout keeps frames and control
    // handling on one coroutine, so they can never interleave on the daemon.
    while (isActive) {
      val ticking = session.controls.ticking && visible && session.stage == DwfStage.LIVE
      val message =
        if (ticking)
          kotlinx.coroutines.withTimeoutOrNull(frameInterval(dwf, session)) {
            incoming.receiveCatching()
          }
        else incoming.receiveCatching()
      if (message == null) {
        drawFrame()
        continue
      }
      val frame = message.getOrNull() ?: break
      val text = (frame as? Frame.Text)?.readText() ?: continue
      when (val parsed = parseDwfClientMessage(text)) {
        is DwfClientMessage.Controls ->
          when (val patched = dwf.sessions.patch(session.id, parsed.patch)) {
            is DwfControls.Patched.Ok -> {
              drawFrame()
              send(Frame.Text(dwfLaneMessage(dwf, session)))
            }
            is DwfControls.Patched.Invalid ->
              send(Frame.Text(ServeStreamProtocol.errorMessage(patched.reason)))
          }
        DwfClientMessage.RequestFrame -> {
          drawFrame()
          send(Frame.Text(dwfStageMessage(dwf, session)))
        }
        is DwfClientMessage.Visibility -> {
          visible = parsed.visible
          if (visible) drawFrame()
        }
        is DwfClientMessage.Unsupported ->
          send(Frame.Text(ServeStreamProtocol.errorMessage(parsed.reason)))
      }
    }
  } catch (_: CancellationException) {
    // The tab went away; the session stays for the reload grace, so a reload resumes it.
  } finally {
    dwf.sessions.detach(session)
  }
}

private const val STAGE_POLL_MILLIS = 250L
private const val STAGE_HEARTBEAT_MILLIS = 1_000L

/** Faster for a face that moves between seconds; one second, the watch's own, for the rest. */
private fun frameInterval(dwf: UiBuilderDwfPreview, session: DwfPreviewSessions.Session): Long =
  if (session.sourceOrNull?.animates == true) dwf.animatedTickMillis else dwf.tickMillis

/** A new session runs on the wall clock, so a face shows the time and moves without a click. */
internal val LIVE_CONTROLS = DwfControls(ticking = true)

private fun dwfLaneMessage(dwf: UiBuilderDwfPreview, session: DwfPreviewSessions.Session): String =
  kotlinx.serialization.json
    .buildJsonObject {
      put("type", JsonPrimitive("lane"))
      put("input", JsonPrimitive(false))
      put("renderer", JsonPrimitive("dwf-apk"))
      put("wffVersion", JsonPrimitive(session.wffVersion))
      put("ticking", JsonPrimitive(session.controls.ticking))
      put("frameIntervalMillis", JsonPrimitive(frameInterval(dwf, session)))
    }
    .toString()

internal fun dwfSessionStatus(
  dwf: UiBuilderDwfPreview,
  session: DwfPreviewSessions.Session,
): DwfPreviewSessionStatusV1 {
  val p = dwf.sessions.progress(session)
  return DwfPreviewSessionStatusV1(
    sessionId = session.id,
    stage = p.stage.wire,
    step = p.step,
    steps = p.steps,
    label = p.label,
    elapsedMillis = p.elapsedMillis,
    stageElapsedMillis = p.stageElapsedMillis,
    cachedPackage = p.cachedPackage,
    queueAhead = p.queueAhead,
    code = p.code,
    reason = p.reason,
    frameIntervalMillis = frameInterval(dwf, session).takeIf { session.controls.ticking },
  )
}

private fun dwfStageMessage(dwf: UiBuilderDwfPreview, session: DwfPreviewSessions.Session): String {
  val status =
    UI_BUILDER_JSON.encodeToJsonElement(
      DwfPreviewSessionStatusV1.serializer(),
      dwfSessionStatus(dwf, session),
    ) as JsonObject
  return JsonObject(
      buildMap {
        put("type", JsonPrimitive("stage"))
        status.forEach { (k, v) -> if (k != "schema") put(k, v) }
      },
    )
    .toString()
}

internal sealed interface DwfClientMessage {
  data class Controls(val patch: JsonObject) : DwfClientMessage

  data object RequestFrame : DwfClientMessage

  data class Visibility(val visible: Boolean) : DwfClientMessage

  data class Unsupported(val reason: String) : DwfClientMessage
}

/** Never throws: a malformed message is an [DwfClientMessage.Unsupported], not a closed socket. */
internal fun parseDwfClientMessage(text: String): DwfClientMessage =
  try {
    val obj = UI_BUILDER_JSON.parseToJsonElement(text).jsonObject
    when (val type = (obj["type"] as? JsonPrimitive)?.contentOrNull) {
      "controls" ->
        (obj["controls"] as? JsonObject)?.let(DwfClientMessage::Controls)
          ?: DwfClientMessage.Unsupported("controls message needs a `controls` object")
      "requestFrame" -> DwfClientMessage.RequestFrame
      "visibility" ->
        (obj["visible"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()?.let {
          DwfClientMessage.Visibility(it)
        } ?: DwfClientMessage.Unsupported("visibility needs a boolean `visible`")
      else -> DwfClientMessage.Unsupported("unsupported message type: $type")
    }
  } catch (_: Exception) {
    DwfClientMessage.Unsupported("not a JSON message")
  }

private sealed interface ResolvedSource {
  data class Ok(val source: DwfSource) : ResolvedSource

  /** [source] names bitmaps nothing supplies; [missing] says which, for each. */
  data class Missing(val source: DwfSource, val missing: List<String>) : ResolvedSource

  data class Refused(val code: String, val reasons: List<String>) : ResolvedSource {
    constructor(code: String, reason: String) : this(code, listOf(reason))
  }
}

/** The editor's XML if it sent any, else what the server derives; never both silently merged. */
private fun resolveDwfSource(
  dwf: UiBuilderDwfPreview,
  document: DesignDocumentV1,
  request: DwfPreviewStartRequestV1,
): ResolvedSource {
  val xml = request.wffXml
  if (xml == null) {
    val derived =
      dwf.sourceFor(document)
        ?: return ResolvedSource.Refused(
          DwfPreviewSurface.SOURCE_UNAVAILABLE,
          "this design does not export to Watch Face Format (the code pane lists why), or this " +
            "server cannot derive it; send the editor's `wffXml` (and `assets`) in the request body",
        )
    return withPinnedDrawables(dwf, document, derived, requested = emptyMap())
  }
  val assets = LinkedHashMap<String, ByteArray>()
  for ((path, encoded) in request.assets) {
    assets[path] =
      try {
        Base64.getDecoder().decode(encoded)
      } catch (_: IllegalArgumentException) {
        return ResolvedSource.Refused(
          DwfPreviewSurface.SOURCE_INVALID,
          "asset `$path` is not valid base64",
        )
      }
  }
  val source =
    DwfSource(
      wffXml = xml,
      wffVersion = request.wffVersion,
      label = request.label?.take(64)?.takeIf { it.isNotBlank() } ?: "watch face",
    )
  return withPinnedDrawables(dwf, document, source, requested = assets)
}

/**
 * [source] with [document]'s pinned assets as drawables under [requested] (which win for the same
 * path), refused when its XML names a bitmap neither supplies: the packager's own report of that is
 * an opaque `memory-footprint` error from the runtime.
 */
private fun withPinnedDrawables(
  dwf: UiBuilderDwfPreview,
  document: DesignDocumentV1,
  source: DwfSource,
  requested: Map<String, ByteArray>,
): ResolvedSource {
  val packaged = packagedWithPinnedDrawables(document, source, requested, dwf.assets)
  if (packaged.missing.isNotEmpty()) {
    return ResolvedSource.Missing(packaged.source, packaged.missing)
  }
  packaged.source.problem()?.let {
    return ResolvedSource.Refused(DwfPreviewSurface.SOURCE_INVALID, it)
  }
  return ResolvedSource.Ok(packaged.source)
}

/** The session, if it exists, is on this design, and belongs to this actor. */
private fun ownedSession(
  dwf: UiBuilderDwfPreview,
  actor: AuthenticatedUiBuilderActor,
  call: ApplicationCall,
): DwfPreviewSessions.Session? {
  val session = dwf.sessions.get(call.parameters["sessionId"].orEmpty()) ?: return null
  return session.takeIf {
    it.designId == call.parameters["designId"] && it.ownerActorId == actor.actorId
  }
}

private fun String.httpStatus(): HttpStatusCode =
  when (this) {
    DwfPreviewSurface.RUNTIME_MISSING,
    DwfPreviewSurface.ANDROID_SDK_MISSING,
    DwfPreviewSurface.SIMULATOR_UNAVAILABLE,
    DwfPreviewSurface.PACKAGING_UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    DwfPreviewSurface.TOO_MANY_SESSIONS -> HttpStatusCode.TooManyRequests
    else -> HttpStatusCode.UnprocessableEntity
  }

private suspend fun ApplicationCall.respondDwfStarted(
  designId: String,
  revision: Long,
  started: DwfPreviewSessions.Started,
  status: HttpStatusCode,
) {
  when (started) {
    is DwfPreviewSessions.Started.Refused ->
      respondDwfRefusal(started.code.httpStatus(), started.code, started.reason)
    is DwfPreviewSessions.Started.Ok -> {
      val session = started.session
      val id = session.id
      val base = "/api/ui-builder/v1/designs/$designId/dwf-preview/$id"
      respondText(
        UI_BUILDER_JSON.encodeToString(
          DwfPreviewStartedV1(
            designId = designId,
            revision = revision,
            sessionId = id,
            wffVersion = session.wffVersion,
            socketPath = "$base/ws",
            pagePath = "/ui-builder/$designId/watch-face?session=$id",
            statusPath = base,
            stage = session.stage.wire,
          ),
        ),
        ContentType.Application.Json,
        status,
      )
    }
  }
}

private suspend fun ApplicationCall.respondDwfRefusal(
  status: HttpStatusCode,
  code: String,
  reason: String,
) = respondDwfRefusal(status, code, listOf(reason))

private suspend fun ApplicationCall.respondDwfRefusal(
  status: HttpStatusCode,
  code: String,
  reasons: List<String>,
) {
  respondText(
    UI_BUILDER_JSON.encodeToString(DwfPreviewRefusalV1(code = code, reasons = reasons)),
    ContentType.Application.Json,
    status,
  )
}
