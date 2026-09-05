package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
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
import io.ktor.server.routing.put
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * The reference-overlay routes: read what a design has attached, replace it, re-aim it, remove it.
 *
 * Plain REST rather than protocol requests, for the reason the device-preset and native-preview
 * routes already give: the released `UiBuilderRequestV1` union has no request for any of this, and
 * adding one means releasing `ui-builder-protocol` — to carry something that deliberately is not
 * part of the design document (see [ServeUiBuilderReferenceStore]).
 *
 * **Authorised twice, on purpose.** The route capability decides whether this caller may use the
 * UI-builder at all, and then every request reads the design *through the service, as that actor*,
 * so the design's own access control decides whether there is a design here to attach anything to.
 * Without the second check, an actor with a write capability could park megabytes against a design
 * they cannot open, and enumerate which design ids exist by watching which writes succeeded.
 */
internal fun Route.installUiBuilderReferenceRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderReferenceStore,
) {
  get(UI_BUILDER_REFERENCE_PATH) {
    val designId =
      call.authorizedDesign(service, authorization, UiBuilderRouteCapability.READ) ?: return@get
    val stored = withContext(Dispatchers.IO) { store.read(designId) }
    if (stored == null) {
      // 404 rather than an empty record: "this design has no reference" and "this design has a
      // reference with no picture in it" are different answers, and only the first one is true.
      call.respondReferenceError(HttpStatusCode.NotFound, "no reference is attached")
      return@get
    }
    call.respondText(
      REFERENCE_ROUTE_JSON.encodeToString(StoredReference.serializer(), stored),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }

  put(UI_BUILDER_REFERENCE_PATH) {
    val designId =
      call.authorizedDesign(service, authorization, UiBuilderRouteCapability.WRITE) ?: return@put
    val request =
      call.receiveReferenceBody(ReferenceUploadRequest.serializer(), store.maximumBytes)
        ?: return@put
    when (val result = withContext(Dispatchers.IO) { store.replace(designId, request) }) {
      is ReferenceWriteResult.Refused ->
        // 422 rather than 400: the body parsed and the request was understood; these bytes are not
        // ones this host will keep, which is a fact about the picture rather than about the call.
        call.respondReferenceError(HttpStatusCode.UnprocessableEntity, result.reason)
      is ReferenceWriteResult.Stored ->
        call.respondText(
          REFERENCE_ROUTE_JSON.encodeToString(StoredReference.serializer(), result.reference),
          ContentType.Application.Json,
          HttpStatusCode.OK,
        )
    }
  }

  put(UI_BUILDER_REFERENCE_SETTINGS_PATH) {
    val designId =
      call.authorizedDesign(service, authorization, UiBuilderRouteCapability.WRITE) ?: return@put
    val request =
      call.receiveReferenceBody(ReferenceSettingsRequest.serializer(), MAX_SETTINGS_BODY_BYTES)
        ?: return@put
    val result = withContext(Dispatchers.IO) { store.replaceSettings(designId, request) }
    when (result) {
      null -> call.respondReferenceError(HttpStatusCode.NotFound, "no reference is attached")
      is ReferenceWriteResult.Refused ->
        call.respondReferenceError(HttpStatusCode.UnprocessableEntity, result.reason)
      is ReferenceWriteResult.Stored ->
        call.respondText(
          REFERENCE_ROUTE_JSON.encodeToString(StoredReference.serializer(), result.reference),
          ContentType.Application.Json,
          HttpStatusCode.OK,
        )
    }
  }

  delete(UI_BUILDER_REFERENCE_PATH) {
    val designId =
      call.authorizedDesign(service, authorization, UiBuilderRouteCapability.WRITE) ?: return@delete
    withContext(Dispatchers.IO) { store.delete(designId) }
    // 204 whether or not there was one: deleting what is already gone is the state the caller
    // asked for, and a 404 here only tells them whether somebody else got there first.
    call.respondText("", status = HttpStatusCode.NoContent)
  }
}

/**
 * The design id this call may act on, or null once the refusal has been written.
 *
 * Reads the design through [service] as the authenticated actor, which is what makes this an
 * authorisation check rather than a path parse.
 */
private suspend fun ApplicationCall.authorizedDesign(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): String? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actorId =
    when (val decision = authorization.authorize(this, capability)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondReferenceError(HttpStatusCode.Unauthorized, "authentication is required")
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondReferenceError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return null
      }
    }
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondReferenceError(HttpStatusCode.BadRequest, "a design id is required")
    return null
  }
  val mapping =
    UiBuilderProtocolMapper.toServiceCall(
      AuthenticatedUiBuilderActor(actorId),
      GetSnapshotRequestV1(designId = designId, revision = null),
    )
  val snapshot = (mapping as? ProtocolRequestMapping.Mapped)?.let { service.execute(it.call) }
  if (snapshot !is UiBuilderServiceResponse.Snapshot) {
    respondReferenceError(HttpStatusCode.NotFound, "no such design")
    return null
  }
  return designId
}

/** The request body, or null once the refusal has been written. */
private suspend fun <T> ApplicationCall.receiveReferenceBody(
  serializer: kotlinx.serialization.DeserializationStrategy<T>,
  maximumPayloadBytes: Int,
): T? {
  // Base64 is four bytes per three, plus the JSON around it. Bounded here as well as in the store
  // so that an oversized upload is refused before it is buffered rather than after.
  val limit = maximumPayloadBytes / 3 * 4 + REFERENCE_ENVELOPE_HEADROOM_BYTES
  val bytes = withContext(Dispatchers.IO) { receiveStream().use { it.readNBytes(limit + 1) } }
  if (bytes.size > limit) {
    respondReferenceError(HttpStatusCode.PayloadTooLarge, "the reference request is too large")
    return null
  }
  return try {
    REFERENCE_ROUTE_JSON.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
  } catch (_: SerializationException) {
    respondReferenceError(HttpStatusCode.BadRequest, "the reference request could not be read")
    null
  }
}

private suspend fun ApplicationCall.respondReferenceError(
  status: HttpStatusCode,
  message: String,
) {
  respondText(
    REFERENCE_ROUTE_JSON.encodeToString(
      ReferenceErrorResponse.serializer(),
      ReferenceErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

internal const val UI_BUILDER_REFERENCE_PATH = "/api/ui-builder/v1/designs/{designId}/reference"

internal const val UI_BUILDER_REFERENCE_SETTINGS_PATH =
  "/api/ui-builder/v1/designs/{designId}/reference/settings"

/** Room for the JSON keys, the settings, the marks and the piece rectangles around the base64. */
private const val REFERENCE_ENVELOPE_HEADROOM_BYTES = 512 * 1024

/** The settings route carries no pictures, so its body is small by construction. */
private const val MAX_SETTINGS_BODY_BYTES = 512 * 1024

private val REFERENCE_ROUTE_JSON =
  kotlinx.serialization.json.Json {
    encodeDefaults = true
    explicitNulls = false
    // Tolerant on the way in, so an editor from a newer release that sends a field this host has
    // not
    // learned yet still gets its overlay stored rather than a 400.
    ignoreUnknownKeys = true
  }
