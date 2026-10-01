package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
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
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The review routes: read a design's verdicts and implementation record, record a verdict, replace
 * the implementation record, and find the designs a pull request implements
 * (compose-preview-server#1255).
 *
 * These are how a **person** decides. The MCP tools record an agent's verdict and say so; a verdict
 * posted here takes its decider kind from the credential ([commentAuthorKindOf]), exactly as a
 * comment's author kind is, so a browser session records a person's approval and an agent's grant
 * presented here still records an agent's. An agent waiting with `ui_builder_await_decision` is
 * woken by either, through the same store.
 *
 * Authorised twice, as the links routes are: the route capability decides whether this caller may
 * use the builder, and then the design is read as this actor. Recording a verdict needs only that
 * read — a reviewer need not be an editor — and replacing the implementation record needs the
 * design's own WRITE action.
 */
internal fun Route.installUiBuilderReviewRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderReviewStore,
) {
  get(UI_BUILDER_REVIEW_PATH) {
    val (_, designId) =
      call.authorizedReviewDesign(service, authorization, UiBuilderRouteCapability.READ, false)
        ?: return@get
    call.respondReview(
      StoredDesignReview.serializer(),
      withContext(Dispatchers.IO) { store.readOrEmpty(designId) },
    )
  }

  post(UI_BUILDER_DECISIONS_PATH) {
    val (actor, designId) =
      call.authorizedReviewDesign(service, authorization, UiBuilderRouteCapability.WRITE, false)
        ?: return@post
    val request = call.receiveReviewBody(DecisionRequest.serializer()) ?: return@post
    val result =
      withContext(Dispatchers.IO) {
        store.decide(designId, actor.actorId, commentAuthorKindOf(actor.actorId), request)
      }
    call.respondReviewResult(result)
  }

  put(UI_BUILDER_IMPLEMENTATION_PATH) {
    val (actor, designId) =
      call.authorizedReviewDesign(service, authorization, UiBuilderRouteCapability.WRITE, true)
        ?: return@put
    val request = call.receiveReviewBody(ImplementationRequest.serializer()) ?: return@put
    val result =
      withContext(Dispatchers.IO) { store.setImplementation(designId, actor.actorId, request) }
    call.respondReviewResult(result)
  }

  get(UI_BUILDER_IMPLEMENTATIONS_LOOKUP_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      call.authorizedReviewActor(authorization, UiBuilderRouteCapability.READ) ?: return@get
    val pr = call.request.queryParameters["pr"].orEmpty().trim()
    if (pr.isEmpty()) {
      call.respondReviewError(HttpStatusCode.BadRequest, "a `pr` URL is required")
      return@get
    }
    val found = withContext(Dispatchers.IO) { store.implementedBy(pr) }
    call.respondReview(
      UiBuilderPrLookupV1.serializer(),
      UiBuilderPrLookupV1(
        summary = "",
        pr = pr,
        designs =
          found
            .filter { service.canRead(actor, it) }
            .map { designId ->
              val implementation = store.read(designId)?.implementation
              UiBuilderPrDesignV1(designId, implementation?.status, implementation?.revision)
            },
      ),
    )
  }
}

private suspend fun ApplicationCall.authorizedReviewDesign(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
  needsDesignWrite: Boolean,
): Pair<AuthenticatedUiBuilderActor, String>? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor = authorizedReviewActor(authorization, capability) ?: return null
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondReviewError(HttpStatusCode.BadRequest, "a design id is required")
    return null
  }
  val actions = service.designActions(actor, designId)
  if (actions == null) {
    respondReviewError(HttpStatusCode.NotFound, "no such design")
    return null
  }
  if (needsDesignWrite && !actions.contains(DesignAccessActionV1.WRITE)) {
    respondReviewError(
      HttpStatusCode.Forbidden,
      "the design's own access control does not permit writing it",
    )
    return null
  }
  return actor to designId
}

private suspend fun ApplicationCall.authorizedReviewActor(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): AuthenticatedUiBuilderActor? =
  when (val decision = authorization.authorize(this, capability)) {
    is UiBuilderAuthorizationDecision.Authorized -> decision.actor
    UiBuilderAuthorizationDecision.Missing -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondReviewError(HttpStatusCode.Unauthorized, "authentication is required")
      null
    }
    UiBuilderAuthorizationDecision.Forbidden -> {
      respondReviewError(HttpStatusCode.Forbidden, "UI-builder access is required")
      null
    }
  }

private suspend fun <T> ApplicationCall.receiveReviewBody(serializer: KSerializer<T>): T? {
  val bytes =
    withContext(Dispatchers.IO) { receiveStream().use { it.readNBytes(MAX_REVIEW_BODY_BYTES + 1) } }
  if (bytes.size > MAX_REVIEW_BODY_BYTES) {
    respondReviewError(HttpStatusCode.PayloadTooLarge, "the review request is too large")
    return null
  }
  return try {
    REVIEW_ROUTE_JSON.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
  } catch (_: SerializationException) {
    respondReviewError(HttpStatusCode.BadRequest, "the review request could not be read")
    null
  } catch (_: IllegalArgumentException) {
    respondReviewError(HttpStatusCode.BadRequest, "the review request could not be read")
    null
  }
}

private suspend fun ApplicationCall.respondReviewResult(result: ReviewWriteResult) {
  when (result) {
    is ReviewWriteResult.Refused ->
      respondReviewError(HttpStatusCode.UnprocessableEntity, result.reason)
    is ReviewWriteResult.Failed ->
      respondReviewError(HttpStatusCode.InternalServerError, result.reason)
    is ReviewWriteResult.Stored -> respondReview(StoredDesignReview.serializer(), result.review)
  }
}

private suspend fun <T> ApplicationCall.respondReview(serializer: KSerializer<T>, value: T) {
  respondText(
    REVIEW_ROUTE_JSON.encodeToString(serializer, value),
    ContentType.Application.Json,
    HttpStatusCode.OK,
  )
}

private suspend fun ApplicationCall.respondReviewError(status: HttpStatusCode, message: String) {
  respondText(
    REVIEW_ROUTE_JSON.encodeToString(LinksErrorResponse.serializer(), LinksErrorResponse(message)),
    ContentType.Application.Json,
    status,
  )
}

internal const val UI_BUILDER_REVIEW_PATH = "/api/ui-builder/v1/designs/{designId}/review"
internal const val UI_BUILDER_DECISIONS_PATH = "/api/ui-builder/v1/designs/{designId}/decisions"
internal const val UI_BUILDER_IMPLEMENTATION_PATH =
  "/api/ui-builder/v1/designs/{designId}/implementation"
internal const val UI_BUILDER_IMPLEMENTATIONS_LOOKUP_PATH = "/api/ui-builder/v1/implementations"

private const val MAX_REVIEW_BODY_BYTES = 16 * 1024

private val REVIEW_ROUTE_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}
