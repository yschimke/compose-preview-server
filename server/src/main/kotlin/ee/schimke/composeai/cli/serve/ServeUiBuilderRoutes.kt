package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetDeltaRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.PreviewCatalogUpgradeRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorV1
import ee.schimke.composeai.uibuilder.protocol.UI_BUILDER_SCHEMA_VERSION_V1
import ee.schimke.composeai.uibuilder.protocol.UpdateDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.UpdatePresenceRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal fun Route.installUiBuilderRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  /**
   * The native render lane, on a host that can compile. Null simply leaves the route out: a box
   * with no playground bundle has no Kotlin compiler and no catalog classpath, and a route that
   * always answers "unavailable" is worse than one a client can discover the absence of.
   */
  nativePreview: UiBuilderNativePreviewLane? = null,
) {
  post(UI_BUILDER_REQUEST_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { input -> input.readNBytes(MAX_UI_BUILDER_REQUEST_BYTES + 1) }
      }
    if (bytes.size > MAX_UI_BUILDER_REQUEST_BYTES) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val envelope =
      try {
        UI_BUILDER_JSON.decodeFromString(
          HttpRequestEnvelopeV1.serializer(),
          bytes.toString(StandardCharsets.UTF_8),
        )
      } catch (_: SerializationException) {
        call.respondProtocolError(
          requestId = INVALID_REQUEST_ID,
          code = ServiceErrorCodeV1.BAD_REQUEST,
          message = "invalid UI-builder request envelope",
          status = HttpStatusCode.BadRequest,
        )
        return@post
      }
    if (envelope.schemaVersion != UI_BUILDER_SCHEMA_VERSION_V1 || envelope.requestId.isBlank()) {
      call.respondProtocolError(
        requestId = envelope.requestId.ifBlank { INVALID_REQUEST_ID },
        code = ServiceErrorCodeV1.BAD_REQUEST,
        message = "unsupported schema version or blank request id",
        status = HttpStatusCode.BadRequest,
      )
      return@post
    }

    val decision = authorization.authorize(call, envelope.request.requiredCapability())
    val actorId =
      when (decision) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondProtocolError(
            envelope.requestId,
            ServiceErrorCodeV1.UNAUTHORIZED,
            "authentication is required",
            HttpStatusCode.Unauthorized,
          )
          return@post
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondProtocolError(
            envelope.requestId,
            ServiceErrorCodeV1.FORBIDDEN,
            "the presented identity lacks the required UI-builder capability",
            HttpStatusCode.Forbidden,
          )
          return@post
        }
      }
    if (envelope.actorId != actorId) {
      call.respondProtocolError(
        envelope.requestId,
        ServiceErrorCodeV1.UNAUTHORIZED,
        "request actor does not match the authenticated actor",
        HttpStatusCode.Forbidden,
      )
      return@post
    }

    val mapping =
      UiBuilderProtocolMapper.toServiceCall(AuthenticatedUiBuilderActor(actorId), envelope.request)
    val response =
      when (mapping) {
        is ProtocolRequestMapping.Mapped ->
          try {
            service.execute(mapping.call)
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (_: Exception) {
            UiBuilderServiceResponse.Error(
              ee.schimke.composeai.uibuilder.service.UiBuilderServiceError(
                ServiceErrorCodeV1.INTERNAL,
                "UI-builder service failed",
                retryable = true,
              )
            )
          }
        is ProtocolRequestMapping.Rejected -> UiBuilderServiceResponse.Error(mapping.error)
      }
    val protocol = UiBuilderProtocolMapper.toProtocolResponse(response)
    call.respondText(
      UI_BUILDER_JSON.encodeToString(
        HttpResponseEnvelopeV1(requestId = envelope.requestId, response = protocol)
      ),
      ContentType.Application.Json,
      response.httpStatus(),
    )
  }

  if (nativePreview != null) {
    /**
     * One design, compiled and rendered by real Compose on this host.
     *
     * A plain POST rather than a protocol request for the same reason the device presets are a
     * plain GET: the released `UiBuilderRequestV1` union has no native-render request, and adding
     * one means releasing `ui-builder-protocol`. Gated on EXPORT rather than READ because it
     * compiles and runs the Kotlin an export hands back — an actor that may not read that source
     * may not run it.
     */
    post(UI_BUILDER_NATIVE_PREVIEW_PATH) {
      call.response.headers.append(HttpHeaders.CacheControl, "no-store")
      val actorId =
        when (val decision = authorization.authorize(call, UiBuilderRouteCapability.EXPORT)) {
          is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
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
      if (designId.isBlank()) {
        call.respondText("a design id is required", status = HttpStatusCode.BadRequest)
        return@post
      }
      // Read through the service, as this actor, so the design's own access control decides
      // whether there is anything to render. A lane that took the document from anywhere else
      // would be a way to render a design you cannot open.
      val mapping =
        UiBuilderProtocolMapper.toServiceCall(
          AuthenticatedUiBuilderActor(actorId),
          GetSnapshotRequestV1(designId = designId, revision = null),
        )
      val snapshot =
        (mapping as? ProtocolRequestMapping.Mapped)?.let { service.execute(it.call) }
          as? UiBuilderServiceResponse.Snapshot
      if (snapshot == null) {
        call.respondText("no such design", status = HttpStatusCode.NotFound)
        return@post
      }
      val document = snapshot.snapshot.state.document
      val result = withContext(Dispatchers.IO) { nativePreview.render(document) }
      when (result) {
        is UiBuilderNativePreviewOutcome.Refused ->
          call.respondText(
            UI_BUILDER_JSON.encodeToString(
              NativePreviewRefusalV1.serializer(),
              NativePreviewRefusalV1(code = result.code, reasons = result.reasons),
            ),
            ContentType.Application.Json,
            // Not a 500: the design is expressible or it is not, and that is a fact about the
            // document the caller sent rather than a failure of this host.
            HttpStatusCode.UnprocessableEntity,
          )
        is UiBuilderNativePreviewOutcome.Rendered ->
          call.respondText(
            UI_BUILDER_JSON.encodeToString(
              NativePreviewResultV1.serializer(),
              NativePreviewResultV1(
                designId = designId,
                revision = document.revision,
                previewId = result.response.previewId,
                previewToken = result.response.previewToken,
                previewUrl = result.response.previewUrl,
                imageBase64 = result.response.image,
                taggedNodeIds = result.taggedNodeIds,
                compileError = result.response.exception,
              ),
            ),
            ContentType.Application.Json,
            HttpStatusCode.OK,
          )
      }
    }
  }

  /**
   * Who the server decided this caller is.
   *
   * The browser editor cannot know its own actor id: it is derived from the operator token, the
   * GitHub session or the presented agent grant, all of which live on the server side of the
   * request. Without this the wasm host had to guess ("browser-user"), and every request it sent
   * was rejected by the actor checks in this route and in `UiBuilderProtocolMapper`. Read-gated
   * like the device presets, and a plain GET for the same reason: no new protocol request type.
   */
  get(UI_BUILDER_IDENTITY_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actorId =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondText("authentication is required", status = HttpStatusCode.Unauthorized)
          return@get
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondText("UI-builder read access required", status = HttpStatusCode.Forbidden)
          return@get
        }
      }
    call.respondText(
      UI_BUILDER_JSON.encodeToString(UiBuilderIdentityV1(actorId = actorId)),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }

  /**
   * The device frames the builder's Screen inspector offers.
   *
   * A plain GET rather than a protocol request because the payload is derived from a compile-time
   * catalog, is identical for every actor, and adding a request type would mean releasing
   * `ui-builder-protocol`. Read-gated all the same, so the editor's frame menu lives behind the
   * same door as everything else it fetches — the browser sends cookies on a same-origin GET, so
   * the wasm host needs no transport of its own.
   */
  get(UI_BUILDER_DEVICE_PRESETS_PATH) {
    when (authorization.authorize(call, UiBuilderRouteCapability.READ)) {
      is UiBuilderAuthorizationDecision.Authorized -> Unit
      UiBuilderAuthorizationDecision.Missing -> {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        call.respondText("authentication is required", status = HttpStatusCode.Unauthorized)
        return@get
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        call.respondText("UI-builder read access required", status = HttpStatusCode.Forbidden)
        return@get
      }
    }
    // The catalog cannot change without a redeploy, so the response is immutable for the life of
    // the process; the editor fetches it once per page load and an ETag saves the second one.
    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=300")
    call.respondText(
      UI_BUILDER_JSON.encodeToString(UiBuilderDevicePresets.payload),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }

  webSocket(UI_BUILDER_UPDATES_PATH) {
    val designId = call.parameters["designId"].orEmpty()
    val afterSequence = call.request.queryParameters["afterSequence"]?.toLongOrNull()
    if (
      designId.isBlank() ||
        call.request.queryParameters["afterSequence"]?.let {
          afterSequence == null || afterSequence < 0
        } == true
    ) {
      close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid design id or sequence cursor"))
      return@webSocket
    }
    val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)
    val actorId = (decision as? UiBuilderAuthorizationDecision.Authorized)?.actorId
    if (actorId == null) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UI-builder read access required"))
      return@webSocket
    }

    val updates = Channel<ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate>(256)
    val overflowed = AtomicBoolean(false)
    val subscription =
      try {
        service.subscribe(
          UiBuilderSubscriptionCall(
            actor = AuthenticatedUiBuilderActor(actorId),
            designId = designId,
            afterSequence = afterSequence,
          )
        ) { update ->
          if (updates.trySend(update).isFailure) {
            overflowed.set(true)
            updates.close()
          }
        }
      } catch (_: Exception) {
        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "design subscription refused"))
        return@webSocket
      }

    try {
      coroutineScope {
        val sender = launch {
          for (update in updates) {
            val envelope = UiBuilderProtocolMapper.toProtocolUpdate(designId, update)
            send(Frame.Text(UI_BUILDER_JSON.encodeToString(envelope)))
          }
          if (overflowed.get()) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "subscriber is too slow"))
          }
        }
        try {
          for (ignored in incoming) {
            // v1 is server-push only; mutations and presence use the authenticated HTTP endpoint.
          }
        } finally {
          updates.close()
          sender.cancelAndJoin()
        }
      }
    } finally {
      subscription.close()
    }
  }
}

private fun ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1.requiredCapability():
  UiBuilderRouteCapability =
  when (this) {
    ListCatalogsRequestV1,
    is ListDesignsRequestV1,
    is OpenDesignRequestV1,
    is GetDesignAccessRequestV1,
    is GetSnapshotRequestV1,
    is GetDeltaRequestV1 -> UiBuilderRouteCapability.READ
    is ExportDesignRequestV1 -> UiBuilderRouteCapability.EXPORT
    is ApplyOperationRequestV1,
    is CreateDesignRequestV1,
    is PreviewCatalogUpgradeRequestV1,
    is UpdateDesignAccessRequestV1,
    is UpdatePresenceRequestV1 -> UiBuilderRouteCapability.WRITE
  }

private fun UiBuilderServiceResponse.httpStatus(): HttpStatusCode =
  when (this) {
    is UiBuilderServiceResponse.Error ->
      when (error.code) {
        ServiceErrorCodeV1.BAD_REQUEST -> HttpStatusCode.BadRequest
        ServiceErrorCodeV1.ACCESS_REVISION_MISMATCH -> HttpStatusCode.Conflict
        ServiceErrorCodeV1.UNAUTHORIZED -> HttpStatusCode.Unauthorized
        ServiceErrorCodeV1.FORBIDDEN -> HttpStatusCode.Forbidden
        ServiceErrorCodeV1.NOT_FOUND -> HttpStatusCode.NotFound
        ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
        ServiceErrorCodeV1.MIGRATION_REQUIRED,
        ServiceErrorCodeV1.SNAPSHOT_REQUIRED -> HttpStatusCode.Conflict
        ServiceErrorCodeV1.INTERNAL -> HttpStatusCode.InternalServerError
      }
    else -> HttpStatusCode.OK
  }

private suspend fun io.ktor.server.application.ApplicationCall.respondProtocolError(
  requestId: String,
  code: ServiceErrorCodeV1,
  message: String,
  status: HttpStatusCode,
) {
  val envelope =
    HttpResponseEnvelopeV1(
      requestId = requestId,
      response = ErrorResponseV1(ServiceErrorV1(code = code, message = message)),
    )
  respondText(
    UI_BUILDER_JSON.encodeToString(envelope),
    ContentType.Application.Json,
    status,
  )
}

@kotlinx.serialization.Serializable
internal data class UiBuilderIdentityV1(val schemaVersion: Int = 1, val actorId: String)

private val UI_BUILDER_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = false
}

internal const val UI_BUILDER_REQUEST_PATH = "/api/ui-builder/v1/requests"
internal const val UI_BUILDER_UPDATES_PATH = "/api/ui-builder/v1/designs/{designId}/updates"
internal const val UI_BUILDER_DEVICE_PRESETS_PATH = "/api/ui-builder/v1/device-presets"
internal const val UI_BUILDER_IDENTITY_PATH = "/api/ui-builder/v1/identity"
internal const val UI_BUILDER_NATIVE_PREVIEW_PATH =
  "/api/ui-builder/v1/designs/{designId}/native-preview"
private const val INVALID_REQUEST_ID = "invalid"
private const val MAX_UI_BUILDER_REQUEST_BYTES = 8 * 1024 * 1024

/**
 * What a native render produced.
 *
 * Its own shape, because the released protocol defines none — and deliberately not squeezed into
 * `ExportArtifactV1`, which describes source rather than a running preview. [taggedNodeIds] names
 * the design nodes the render is tagged with, so a client knows which ids `get_preview_data` will
 * report bounds for and can put selectable regions over an image the browser did not draw.
 */
@kotlinx.serialization.Serializable
internal data class NativePreviewResultV1(
  val schema: String = "compose-preview/ui-builder-native-preview/v1",
  val designId: String,
  val revision: Long,
  val previewId: String? = null,
  val previewToken: String? = null,
  val previewUrl: String? = null,
  val imageBase64: String? = null,
  val taggedNodeIds: List<String> = emptyList(),
  val compileError: String? = null,
)

/** Why there was no native render — the generator's own reasons, not a second vocabulary. */
@kotlinx.serialization.Serializable
internal data class NativePreviewRefusalV1(
  val schema: String = "compose-preview/ui-builder-native-preview-refusal/v1",
  val code: String,
  val reasons: List<String>,
)
