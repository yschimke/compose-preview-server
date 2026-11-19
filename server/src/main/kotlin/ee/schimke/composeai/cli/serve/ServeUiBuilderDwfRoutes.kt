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
import kotlinx.coroutines.isActive
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
  /** How often a ticking session redraws. One second is the watch's own granularity. */
  internal val tickMillis: Long = 1000,
)

internal const val UI_BUILDER_DWF_PREVIEW_PATH = "/api/ui-builder/v1/designs/{designId}/dwf-preview"
internal const val UI_BUILDER_DWF_STATUS_PATH = "$UI_BUILDER_DWF_PREVIEW_PATH/status"
internal const val UI_BUILDER_DWF_SESSION_PATH = "$UI_BUILDER_DWF_PREVIEW_PATH/{sessionId}"
internal const val UI_BUILDER_DWF_SOCKET_PATH = "$UI_BUILDER_DWF_SESSION_PATH/ws"

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
  /**
   * "Publish to watch": present only when this host builds push APKs and the design's catalog draws
   * watch faces ([WatchFacePush.capability]). Independent of [available], which is the preview's.
   */
  val watchFacePush: WatchFacePushCapabilityV1? = null,
)

internal fun Route.installUiBuilderDwfRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  dwf: UiBuilderDwfPreview,
  /** Watch Face Push on this host, for [DwfPreviewStatusV1.watchFacePush]; null: none. */
  watchFacePush: WatchFacePush? = null,
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
    val availability = dwf.sessions.availability()
    val unavailable = availability as? DwfApkRuntime.Availability.Unavailable
    call.respondText(
      UI_BUILDER_JSON.encodeToString(
        DwfPreviewStatusV1(
          available = unavailable == null,
          code = unavailable?.code,
          reason = unavailable?.reason,
          sessions = dwf.sessions.sessionCount,
          maxSessions = DwfPreviewSessions.DEFAULT_MAX_SESSIONS,
          watchFacePush = watchFacePush?.capability(service, authorization, call, designId),
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
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.EXPORT)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondText("authentication is required", status = HttpStatusCode.Unauthorized)
          return@post
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondText("UI-builder export access required", status = HttpStatusCode.Forbidden)
          return@post
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
      return@post
    }
    val snapshot =
      service.executeMapped(GetSnapshotRequestV1(designId = designId, revision = revision), actor)
        as? UiBuilderServiceResponse.Snapshot
    if (snapshot == null) {
      call.respondText("no such design", status = HttpStatusCode.NotFound)
      return@post
    }
    val document = snapshot.snapshot.state.document
    if (!dwf.isDwfCatalog(document.catalogPin.systemId)) {
      call.respondDwfRefusal(
        HttpStatusCode.UnprocessableEntity,
        DwfPreviewSurface.SOURCE_INVALID,
        "catalog `${document.catalogPin.systemId}` does not declare the " +
          "`${DwfPreviewSurface.BACKEND}` native backend, so this design is not a watch face",
      )
      return@post
    }
    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { it.readNBytes(MAX_UI_BUILDER_REQUEST_BYTES + 1) }
      }
    if (bytes.size > MAX_UI_BUILDER_REQUEST_BYTES) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val request =
      try {
        UI_BUILDER_JSON.decodeFromString(
          DwfPreviewStartRequestV1.serializer(),
          bytes.toString(StandardCharsets.UTF_8).ifBlank { "{}" },
        )
      } catch (_: SerializationException) {
        call.respondText(
          "dwf-preview request body is not valid JSON for this route",
          status = HttpStatusCode.BadRequest,
        )
        return@post
      }
    val source =
      when (val resolved = resolveDwfSource(dwf, document, request)) {
        is ResolvedSource.Ok -> resolved.source
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
        dwf.sessions.start(designId, document.revision, actor.actorId, source)
      }
    when (started) {
      is DwfPreviewSessions.Started.Refused ->
        call.respondDwfRefusal(started.code.httpStatus(), started.code, started.reason)
      is DwfPreviewSessions.Started.Ok -> {
        val id = started.session.id
        call.respondText(
          UI_BUILDER_JSON.encodeToString(
            DwfPreviewStartedV1(
              designId = designId,
              revision = document.revision,
              sessionId = id,
              wffVersion = source.wffVersion,
              socketPath = "/api/ui-builder/v1/designs/$designId/dwf-preview/$id/ws",
              pagePath = "/ui-builder/$designId/watch-face?session=$id",
            ),
          ),
          ContentType.Application.Json,
          HttpStatusCode.Created,
        )
      }
    }
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

/**
 * The tab's socket: the server half of `composeai-stream/1` (`lane`, `frame`, `error`, the same
 * shapes the viewer's own socket uses, so a frame handler written for that works here), with one
 * client message of its own, `controls`, because the viewer's `setOverrides` is a flat string map
 * keyed on a served preview, and a watch face's controls are typed.
 *
 * Client → server: `{"type":"controls","controls":{…}}` (a partial [DwfControls.patch]),
 * `{"type":"requestFrame"}`, `{"type":"visibility","visible":false}` (a hidden tab stops ticking).
 * A frame follows every accepted `controls` and, while `ticking`, every
 * [UiBuilderDwfPreview.tickMillis].
 */
private suspend fun DefaultWebSocketServerSession.serveDwfSocket(
  dwf: UiBuilderDwfPreview,
  session: DwfPreviewSessions.Session,
) {
  var visible = true
  send(Frame.Text(dwfLaneMessage(session)))

  suspend fun drawFrame() {
    val frame = withContext(Dispatchers.IO) { dwf.sessions.frame(session.id) }
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

  try {
    drawFrame()
    // One loop does both jobs: block for a client message for at most a tick, and if nothing came
    // and the face is ticking, redraw. `receiveCatching`-with-timeout keeps frames and control
    // handling on one coroutine, so they can never interleave on the daemon.
    while (isActive) {
      val ticking = session.controls.ticking && visible
      val message =
        if (ticking)
          kotlinx.coroutines.withTimeoutOrNull(dwf.tickMillis) { incoming.receiveCatching() }
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
              send(Frame.Text(dwfLaneMessage(session)))
            }
            is DwfControls.Patched.Invalid ->
              send(Frame.Text(ServeStreamProtocol.errorMessage(patched.reason)))
          }
        DwfClientMessage.RequestFrame -> drawFrame()
        is DwfClientMessage.Visibility -> {
          visible = parsed.visible
          if (visible) drawFrame()
        }
        is DwfClientMessage.Unsupported ->
          send(Frame.Text(ServeStreamProtocol.errorMessage(parsed.reason)))
      }
    }
  } catch (_: CancellationException) {
    // The tab went away; the session stays until it is closed or idles out, so a reload resumes.
  }
}

private fun dwfLaneMessage(session: DwfPreviewSessions.Session): String =
  kotlinx.serialization.json
    .buildJsonObject {
      put("type", JsonPrimitive("lane"))
      put("input", JsonPrimitive(false))
      put("renderer", JsonPrimitive("dwf-apk"))
      put("wffVersion", JsonPrimitive(session.source.wffVersion))
      put("ticking", JsonPrimitive(session.controls.ticking))
    }
    .toString()

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
    return ResolvedSource.Refused(WatchFaceDrawables.MISSING_RESOURCE, packaged.missing)
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
