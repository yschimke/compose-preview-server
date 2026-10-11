package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
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
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
  /** Stable server address for documents created through the REST transport. */
  serverOrigin: () -> String?,
  /**
   * The native render lane, on a host that can compile; null leaves the route out so clients
   * discover its absence.
   */
  nativePreview: UiBuilderNativePreviewLane? = null,
  /** The inline Remote Compose capture lane, left out on a host that cannot compile — as above. */
  inlineCapture: UiBuilderInlineCaptureLane? = null,
  /**
   * Sign-in state and why a write would be refused, for the identity endpoint, supplied by the
   * host; null leaves those fields out.
   */
  identityDetails: (call: ApplicationCall, canWrite: Boolean) -> UiBuilderIdentityDetails? =
    { _, _ ->
      null
    },
  /**
   * Turns a native render's minted token into a live streamed session, or null where this host
   * can't. Redeemed here (rather than the browser following the `/pg/` redirect) so the editor gets
   * `{sessionId, previewId}` and opens `/{sessionId}/ws/{previewId}`, the viewer's Live lane. Null
   * leaves the live fields absent, which the editor reads as still-only.
   */
  liveNativeSession: ((token: String, previewId: String) -> UiBuilderNativeLiveSession?)? = null,
  agentPresence: ServeUiBuilderAgentPresence? = null,
) {
  if (agentPresence != null) {
    get("/api/ui-builder/v1/designs/{designId}/agents") {
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
      // Public viewers get anonymous labels, just as they do for browser presence and comments.
      val redact = service.publicReaderView(actor, designId) != null
      call.respondText(
        UI_BUILDER_JSON.encodeToString(agentPresence.roster(designId, redact)),
        ContentType.Application.Json,
      )
    }
  }
  installUiBuilderLiveExportRoutes(service, authorization)
  installUiBuilderCatalogRecoveryRoutes(service, authorization)
  get("/api/ui-builder/v1/designs/{designId}/visibility") {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      (authorization.authorize(call, UiBuilderRouteCapability.READ)
          as? UiBuilderAuthorizationDecision.Authorized)
        ?.actor
    val designId = call.parameters["designId"].orEmpty()
    val actions = actor?.let { service.designActions(it, designId) }
    if (actions == null) {
      call.respondText("not found", status = HttpStatusCode.NotFound)
      return@get
    }
    val publicRead =
      service.canRead(
        AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID),
        designId,
      )
    val canWrite =
      ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1.WRITE in actions &&
        authorization.authorize(call, UiBuilderRouteCapability.WRITE) is
          UiBuilderAuthorizationDecision.Authorized
    val canManage =
      canWrite &&
        ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1.MANAGE_ACCESS in actions
    call.respondText(
      UI_BUILDER_JSON.encodeToString(
        UiBuilderVisibilityPayload(
          if (publicRead) "public" else "private",
          canWrite,
          canManage,
        )
      ),
      ContentType.Application.Json,
    )
  }
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
    val actor =
      when (decision) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
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
    if (envelope.actorId != actor.actorId) {
      call.respondProtocolError(
        envelope.requestId,
        ServiceErrorCodeV1.UNAUTHORIZED,
        "request actor does not match the authenticated actor",
        HttpStatusCode.Forbidden,
      )
      return@post
    }

    val mapping = UiBuilderProtocolMapper.toServiceCall(actor, envelope.request)
    val response =
      when (mapping) {
        is ProtocolRequestMapping.Mapped ->
          try {
            // A public reader is told who is on the design no more than it needs; see
            // [PublicReaderView]. Everyone else gets the service's answer untouched.
            service.shapeForReader(actor, service.execute(mapping.call))
          } catch (cancelled: CancellationException) {
            throw cancelled
          } catch (failure: Exception) {
            logUiBuilderServiceFailure(failure)
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

  /**
   * `GET` one design's document from its own URL, the read half of the `PUT` below; previously
   * readable only via MCP (compose-ui-builder#492). Same document as `ui_builder_get_design`'s
   * `snapshot.state.document`. `?revision=N` reads a retained revision. Unreadable designs answer
   * 404, never 403, so existence doesn't leak.
   */
  get(UI_BUILDER_DESIGN_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
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
    val designId = call.parameters["designId"].orEmpty()
    if (designId.isBlank()) {
      call.respondText("a design id is required", status = HttpStatusCode.BadRequest)
      return@get
    }
    val revisionParameter = call.request.queryParameters["revision"]
    val revision = revisionParameter?.toLongOrNull()
    if (revisionParameter != null && (revision == null || revision < 0)) {
      call.respondText(
        "revision must be a non-negative integer",
        status = HttpStatusCode.BadRequest,
      )
      return@get
    }
    val response =
      service.shapeForReader(
        actor,
        service.execute(
          UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetSnapshot(designId, revision))
        ),
      )
    when (response) {
      is UiBuilderServiceResponse.Snapshot ->
        call.respondText(
          UI_BUILDER_JSON.encodeToString(
            DesignDocumentV1.serializer(),
            response.snapshot.state.document,
          ),
          ContentType.Application.Json,
        )
      is UiBuilderServiceResponse.Error ->
        call.respondText(response.error.message, status = response.httpStatus())
      else ->
        call.respondText(
          "the design service did not answer with a document",
          status = HttpStatusCode.InternalServerError,
        )
    }
  }

  /**
   * `PUT` one design into existence at its own URL: the same create command with resource
   * semantics. `If-None-Match: *` is required, so a client expecting replace is told this service
   * never overwrites. `201` carries `Location` pointing at the editor permalink.
   */
  put(UI_BUILDER_DESIGN_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.WRITE)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondText("authentication is required", status = HttpStatusCode.Unauthorized)
          return@put
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondText("UI-builder write access required", status = HttpStatusCode.Forbidden)
          return@put
        }
      }
    val designId = call.parameters["designId"].orEmpty()
    if (designId.isBlank()) {
      call.respondText("a design id is required", status = HttpStatusCode.BadRequest)
      return@put
    }
    if (call.request.headers[HttpHeaders.IfNoneMatch]?.trim() != "*") {
      call.respondText(
        "send `If-None-Match: *`. This route creates a design and never replaces one, so a " +
          "PUT without that precondition is asking for something it cannot do.",
        status = PRECONDITION_REQUIRED,
      )
      return@put
    }
    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { input -> input.readNBytes(MAX_UI_BUILDER_REQUEST_BYTES + 1) }
      }
    if (bytes.size > MAX_UI_BUILDER_REQUEST_BYTES) {
      call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
      return@put
    }
    val incoming =
      try {
        UI_BUILDER_JSON.decodeFromString(
          DesignDocumentV1.serializer(),
          bytes.toString(StandardCharsets.UTF_8),
        )
      } catch (_: SerializationException) {
        call.respondText("body is not a DesignDocumentV1", status = HttpStatusCode.BadRequest)
        return@put
      }
    if (incoming.id != designId) {
      call.respondText(
        "the document's id (${incoming.id}) is not the design this URL names",
        status = HttpStatusCode.BadRequest,
      )
      return@put
    }
    // The service reports create-onto-existing as a generic bad request, so the precondition is
    // checked with a read first; a later failure is the race between two creates.
    val existing = service.executeMapped(OpenDesignRequestV1(designId), actor)
    if (
      existing !is UiBuilderServiceResponse.Error ||
        existing.error.code != ServiceErrorCodeV1.NOT_FOUND
    ) {
      if (existing is UiBuilderServiceResponse.Error) {
        call.respondText(existing.error.message, status = existing.httpStatus())
        return@put
      }
      val outcome =
        (existing as? UiBuilderServiceResponse.Snapshot)?.let {
          existingDesignOutcome(designId, incoming, serverOrigin())
        }
      if (outcome is ServeUiBuilderCreate.Outcome.Refused) {
        call.respondText(outcome.reason, status = HttpStatusCode.PreconditionFailed)
      } else {
        call.respondText(
          "$designId already exists; If-None-Match: * requires that it does not",
          status = HttpStatusCode.PreconditionFailed,
        )
      }
      return@put
    }
    incomingHomeRefusal(incoming, serverOrigin())?.let { reason ->
      call.respondText(reason, status = HttpStatusCode.Conflict)
      return@put
    }
    val document = incoming.withServerHome(serverOrigin())
    when (val created = service.executeMapped(CreateDesignRequestV1(document), actor)) {
      is UiBuilderServiceResponse.Error -> {
        // A bad request is the race only if the design exists now; limits, quotas and invalid
        // documents are also bad requests.
        val raced =
          created.error.code == ServiceErrorCodeV1.BAD_REQUEST &&
            (service.executeMapped(OpenDesignRequestV1(designId), actor)
              is UiBuilderServiceResponse.Snapshot)
        val status = if (raced) HttpStatusCode.PreconditionFailed else created.httpStatus()
        call.respondText(created.error.message, status = status)
      }
      else -> {
        call.response.headers.append(HttpHeaders.Location, "/ui-builder/$designId")
        call.respondText("created $designId", status = HttpStatusCode.Created)
      }
    }
  }

  if (nativePreview != null) {
    /**
     * One design compiled and rendered by real Compose on this host. A plain POST because
     * `UiBuilderRequestV1` has no native-render request. Gated on EXPORT, since it runs the
     * exported Kotlin.
     */
    post(UI_BUILDER_NATIVE_PREVIEW_PATH) {
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
      if (designId.isBlank()) {
        call.respondText("a design id is required", status = HttpStatusCode.BadRequest)
        return@post
      }
      // Which revision to render, from the query like the export routes; absent means current. A
      // pinned editor needs it, or a historical page would show a render of the head.
      val revisionParameter = call.request.queryParameters["revision"]
      val revision = revisionParameter?.toLongOrNull()
      if (revisionParameter != null && (revision == null || revision < 0)) {
        call.respondText(
          "revision must be a non-negative integer",
          status = HttpStatusCode.BadRequest,
        )
        return@post
      }
      // Read through the service as this actor, so the design's access control decides whether
      // there is anything to render.
      val mapping =
        UiBuilderProtocolMapper.toServiceCall(
          actor,
          GetSnapshotRequestV1(designId = designId, revision = revision),
        )
      val snapshot =
        (mapping as? ProtocolRequestMapping.Mapped)?.let { service.execute(it.call) }
          as? UiBuilderServiceResponse.Snapshot
      if (snapshot == null) {
        call.respondText("no such design", status = HttpStatusCode.NotFound)
        return@post
      }
      val document = snapshot.snapshot.state.document
      // Widget host shape from the body; absent means the squircle. An undecodable body is a client
      // bug (400); a decodable but unknown shape is forward compatibility and falls back to the
      // default, since the shape is just a view.
      val requestBytes =
        withContext(Dispatchers.IO) {
          call.receiveStream().use { input -> input.readNBytes(MAX_UI_BUILDER_REQUEST_BYTES + 1) }
        }
      if (requestBytes.size > MAX_UI_BUILDER_REQUEST_BYTES) {
        call.respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
        return@post
      }
      val decoded =
        try {
          val body = requestBytes.toString(StandardCharsets.UTF_8).ifBlank { "{}" }
          UI_BUILDER_JSON.decodeFromString(NativePreviewRequestV1.serializer(), body)
        } catch (_: SerializationException) {
          call.respondText(
            "native render request body is not valid JSON for this route",
            status = HttpStatusCode.BadRequest,
          )
          return@post
        }
      val hostShape = WearWidgetHostShape.fromId(decoded.hostShape)
      val result = withContext(Dispatchers.IO) { nativePreview.render(document, hostShape) }
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
                live = nativePreviewLiveOf(result.response, liveNativeSession),
                imageBase64 = result.response.image,
                taggedNodeIds = result.taggedNodeIds,
                nodeBounds = result.nodeBounds.mapValues { (_, box) -> box.toNodeBoundsV1() },
                compileError = result.failure,
                warnings = result.warnings,
              ),
            ),
            ContentType.Application.Json,
            HttpStatusCode.OK,
          )
      }
    }
  }

  if (inlineCapture != null) {
    /**
     * One design's inline Remote Compose content captured into a document. A POST gated like the
     * native render (no protocol request; runs exported Kotlin). The node id is in the path since
     * each inline node is a separate document.
     */
    post(UI_BUILDER_INLINE_CAPTURE_PATH) {
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
      val nodeId = call.parameters["nodeId"].orEmpty()
      if (designId.isBlank() || nodeId.isBlank()) {
        call.respondText(
          "a design id and a node id are required",
          status = HttpStatusCode.BadRequest,
        )
        return@post
      }
      // Read through the service, as this actor, exactly as the native render does: a lane that
      // took the document from anywhere else would be a way to capture a design you cannot open.
      val mapping =
        UiBuilderProtocolMapper.toServiceCall(
          actor,
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
      val result = withContext(Dispatchers.IO) { inlineCapture.capture(document, nodeId) }
      when (result) {
        is UiBuilderInlineCaptureOutcome.Refused ->
          call.respondText(
            UI_BUILDER_JSON.encodeToString(
              InlineCaptureRefusalV1.serializer(),
              InlineCaptureRefusalV1(code = result.code, reasons = result.reasons),
            ),
            ContentType.Application.Json,
            // Not a 500, for the reason the native refusal is not: the subtree captures or it does
            // not, and which one is a fact about the design and this host rather than a failure.
            HttpStatusCode.UnprocessableEntity,
          )
        is UiBuilderInlineCaptureOutcome.Captured ->
          call.respondText(
            UI_BUILDER_JSON.encodeToString(
              InlineCaptureResultV1.serializer(),
              InlineCaptureResultV1(
                designId = designId,
                revision = document.revision,
                nodeId = result.nodeId,
                functionName = result.functionName,
                documentUrl = result.documentUrl,
              ),
            ),
            ContentType.Application.Json,
            HttpStatusCode.OK,
          )
      }
    }
  }

  /**
   * Who the server decided this caller is: the editor can't derive its actor id (it comes from the
   * token, session or grant server-side). Read-gated, plain GET (no new protocol type).
   */
  get(UI_BUILDER_IDENTITY_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actorId =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
        UiBuilderAuthorizationDecision.Missing -> {
          // Still a bearer-style 401, but carrying a sign-in route so a signed-out visitor is
          // offered one.
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondText(
            UI_BUILDER_JSON.encodeToString(
              UiBuilderIdentityRefusalV1(
                message = "authentication is required",
                signInUrl = identityDetails(call, false)?.signInUrl,
              )
            ),
            ContentType.Application.Json,
            HttpStatusCode.Unauthorized,
          )
          return@get
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondText("UI-builder read access required", status = HttpStatusCode.Forbidden)
          return@get
        }
      }
    val canWrite =
      authorization.authorize(call, UiBuilderRouteCapability.WRITE) is
        UiBuilderAuthorizationDecision.Authorized
    val details = identityDetails(call, canWrite)
    call.respondText(
      UI_BUILDER_JSON.encodeToString(
        UiBuilderIdentityV1(
          actorId = actorId,
          signedIn = details?.signedIn,
          canWrite = canWrite,
          writeDeniedReason = details?.writeDeniedReason?.takeIf { !canWrite },
          signInUrl = details?.signInUrl,
        )
      ),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }

  /**
   * The device frames the Screen inspector offers. A plain GET (compile-time data, same for every
   * actor, no protocol change), read-gated like everything else the editor fetches.
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
    val actor = (decision as? UiBuilderAuthorizationDecision.Authorized)?.actor
    if (actor == null) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UI-builder read access required"))
      return@webSocket
    }

    val view = service.publicReaderView(actor, designId)
    val updates = Channel<ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate>(256)
    val overflowed = AtomicBoolean(false)
    val subscription =
      try {
        service.subscribe(
          UiBuilderSubscriptionCall(
            actor = actor,
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
            val shaped = view?.update(update) ?: update
            val envelope = UiBuilderProtocolMapper.toProtocolUpdate(designId, shaped)
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

internal fun ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1.requiredCapability():
  UiBuilderRouteCapability =
  when (this) {
    ListCatalogsRequestV1,
    is ListDesignsRequestV1,
    is OpenDesignRequestV1,
    is GetDesignAccessRequestV1,
    is GetSnapshotRequestV1,
    // A catalog-upgrade preview writes nothing, and the service admits it on READ.
    is PreviewCatalogUpgradeRequestV1,
    is GetDeltaRequestV1 -> UiBuilderRouteCapability.READ
    is ExportDesignRequestV1 -> UiBuilderRouteCapability.EXPORT
    is ApplyOperationRequestV1,
    is CreateDesignRequestV1,
    is UpdateDesignAccessRequestV1,
    is UpdatePresenceRequestV1 -> UiBuilderRouteCapability.WRITE
  }

/**
 * Run one protocol request through the mapper and the service as this actor, for resource routes
 * that have no envelope.
 */
internal suspend fun UiBuilderServicePort.executeMapped(
  request: ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1,
  actor: AuthenticatedUiBuilderActor,
): UiBuilderServiceResponse =
  when (val mapping = UiBuilderProtocolMapper.toServiceCall(actor, request)) {
    is ProtocolRequestMapping.Mapped -> execute(mapping.call)
    is ProtocolRequestMapping.Rejected -> UiBuilderServiceResponse.Error(mapping.error)
  }

/** The refusal status as a bare code, for callers that answer outside Ktor's status type. */
internal fun UiBuilderServiceResponse.httpStatusValue(): Int = httpStatus().value

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
internal data class UiBuilderIdentityV1(
  val schemaVersion: Int = 1,
  val designVisibilitySupported: Boolean = true,
  val actorId: String,
  /**
   * Whether a person or operator token is behind this request, rather than a `--public` anonymous
   * reader. Absent from older hosts.
   */
  val signedIn: Boolean? = null,
  /**
   * Whether this caller may create and edit designs, from the same authorizer and capability as the
   * write routes. A hint, never a gate; absent (older host) reads as "may write".
   */
  val canWrite: Boolean? = null,
  /** Why [canWrite] is false, in words the person can act on. */
  val writeDeniedReason: String? = null,
  /** Where to sign in and come back to the editor, on a host with GitHub sign-in. */
  val signInUrl: String? = null,
)

/** The 401 for an unauthenticated identity request, saying where to sign in. */
@kotlinx.serialization.Serializable
internal data class UiBuilderIdentityRefusalV1(
  val schemaVersion: Int = 1,
  val message: String,
  val signInUrl: String? = null,
)

/**
 * What the identity endpoint says beyond the actor, from the host that knows how people sign in.
 */
internal data class UiBuilderIdentityDetails(
  val signedIn: Boolean,
  val writeDeniedReason: String?,
  val signInUrl: String?,
)

internal val UI_BUILDER_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = false
}

internal const val UI_BUILDER_REQUEST_PATH = "/api/ui-builder/v1/requests"
internal const val UI_BUILDER_DESIGN_PATH = "/api/ui-builder/v1/designs/{designId}"
internal const val UI_BUILDER_UPDATES_PATH = "/api/ui-builder/v1/designs/{designId}/updates"
internal const val UI_BUILDER_DEVICE_PRESETS_PATH = "/api/ui-builder/v1/device-presets"
internal const val UI_BUILDER_IDENTITY_PATH = "/api/ui-builder/v1/identity"
internal const val UI_BUILDER_NATIVE_PREVIEW_PATH =
  "/api/ui-builder/v1/designs/{designId}/native-preview"
internal const val UI_BUILDER_INLINE_CAPTURE_PATH =
  "/api/ui-builder/v1/designs/{designId}/remote-content/{nodeId}/capture"
/** 428, which Ktor's [HttpStatusCode] does not name. RFC 6585: the precondition is missing. */
private val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")

private const val INVALID_REQUEST_ID = "invalid"
internal const val MAX_UI_BUILDER_REQUEST_BYTES = 8 * 1024 * 1024

/**
 * What a native render produced.
 *
 * Its own shape, because the released protocol defines none — and deliberately not squeezed into
 * `ExportArtifactV1`, which describes source rather than a running preview. [taggedNodeIds] names
 * the design nodes the render is tagged with, so a client knows which ids to expect a rectangle
 * for, and [nodeBounds] is those rectangles — together they are what puts selectable regions over
 * an image the browser did not draw.
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
  /**
   * Design node id → drawn box in render pixels. A subset of [taggedNodeIds]: nodes never placed
   * get no rectangle rather than a zero-area box. Empty without a semantics producer.
   */
  val nodeBounds: Map<String, NativePreviewNodeBoundsV1> = emptyMap(),
  /**
   * Why there is no [imageBase64] (compiler error, compile exception, or no frame); null when a
   * frame arrived. Name kept as the field grew.
   */
  val compileError: String? = null,
  /**
   * How [imageBase64] differs from the design despite rendering, e.g. a typeface drawn in the
   * default face (`TYPEFACE_SYSTEM_FONT_LOOKUP`). Empty when faithful.
   */
  val warnings: List<String> = emptyList(),
  /**
   * Where to open this render's live stream, or null (no live backend, no token, or no daemon for
   * the mode); the client then draws the still.
   */
  val live: NativePreviewLiveV1? = null,
)

/**
 * This render's stream coordinates, or null. No backend, no token and no daemon all mean "still
 * only", which isn't an error. Extracted so the gating is testable without ktor, a compiler or a
 * bundle.
 */
internal fun nativePreviewLiveOf(
  response: PlaygroundRunResponse,
  liveNativeSession: ((token: String, previewId: String) -> UiBuilderNativeLiveSession?)?,
): NativePreviewLiveV1? {
  val redeem = liveNativeSession ?: return null
  val token = response.previewToken?.takeIf { it.isNotBlank() } ?: return null
  val preview = response.previewId?.takeIf { it.isNotBlank() } ?: return null
  val session = redeem(token, preview) ?: return null
  return NativePreviewLiveV1(sessionId = session.sessionId, previewId = session.previewId)
}

/**
 * The registered session and preview; two fields rather than a URL, since a server-built absolute
 * URL would have to guess the scheme and host behind a proxy.
 */
@kotlinx.serialization.Serializable
internal data class NativePreviewLiveV1(val sessionId: String, val previewId: String)

/** What a host's redemption answers with: the live session a native render can be streamed from. */
data class UiBuilderNativeLiveSession(val sessionId: String, val previewId: String)

/**
 * One node's rectangle on the native frame, in render pixels from the top-left. A wire type owned
 * here, not `render-host`'s `AnnotationBounds`.
 */
@kotlinx.serialization.Serializable
internal data class NativePreviewNodeBoundsV1(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
)

private fun AnnotationBounds.toNodeBoundsV1() =
  NativePreviewNodeBoundsV1(x = x, y = y, width = width, height = height)

/**
 * Where one inline node's captured document is served: [documentUrl] is a `/d/<id>` permalink like
 * a `remote-compose/document` node's, and [functionName] names the `@RemoteComposable` body it came
 * from.
 */
@kotlinx.serialization.Serializable
internal data class InlineCaptureResultV1(
  val schema: String = "compose-preview/ui-builder-inline-capture/v1",
  val designId: String,
  val revision: Long,
  val nodeId: String,
  val functionName: String,
  val documentUrl: String,
)

/** Why there was no captured document — the lane's own reasons, in its own vocabulary. */
@kotlinx.serialization.Serializable
internal data class InlineCaptureRefusalV1(
  val schema: String = "compose-preview/ui-builder-inline-capture-refusal/v1",
  val code: String,
  val reasons: List<String>,
)

/**
 * What a native-render request may say (currently the host shape). A body type because shape
 * describes what to draw, not which version; all fields optional so `{}` still parses.
 */
@kotlinx.serialization.Serializable
internal data class NativePreviewRequestV1(val hostShape: String? = null)

/** Why there was no native render — the generator's own reasons, not a second vocabulary. */
@kotlinx.serialization.Serializable
internal data class NativePreviewRefusalV1(
  val schema: String = "compose-preview/ui-builder-native-preview-refusal/v1",
  val code: String,
  val reasons: List<String>,
)

@kotlinx.serialization.Serializable
internal data class UiBuilderVisibilityPayload(
  val visibility: String,
  val canWrite: Boolean,
  val canManage: Boolean,
)

/**
 * Logs what the generic "UI-builder service failed" answer hides. The client gets no details
 * (messages may carry paths or other designs' content); the stack goes to the operator's process
 * log.
 */
internal fun logUiBuilderServiceFailure(failure: Exception) {
  System.err.println(
    "serve: UI-builder service failed: ${failure::class.qualifiedName}: ${failure.message}"
  )
  failure.printStackTrace()
}
