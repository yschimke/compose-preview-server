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

private val UI_BUILDER_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = false
}

internal const val UI_BUILDER_REQUEST_PATH = "/api/ui-builder/v1/requests"
internal const val UI_BUILDER_UPDATES_PATH = "/api/ui-builder/v1/designs/{designId}/updates"
private const val INVALID_REQUEST_ID = "invalid"
private const val MAX_UI_BUILDER_REQUEST_BYTES = 8 * 1024 * 1024
