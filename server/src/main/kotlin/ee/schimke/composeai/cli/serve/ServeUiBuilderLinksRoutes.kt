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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * The links routes: read what a design is for, replace it, clear it — and, the other way round, ask
 * which designs cite an issue.
 *
 * Plain REST rather than protocol requests, for the reason the reference and comment routes already
 * give: the released `UiBuilderRequestV1` union has no request for any of this, and adding one
 * means releasing `ui-builder-protocol` — to carry something that deliberately is not part of the
 * design document (see [ServeUiBuilderLinksStore]).
 *
 * **Authorised twice, on purpose.** The route capability decides whether this caller may use the
 * UI-builder at all, and then the design's own access control decides what this actor may do to
 * *this* design — READ to be told what it is for, its own WRITE action to change that. A host
 * capability is permission to use the door, never permission to edit somebody else's design.
 * Without the second check, an actor with a write capability could park a record against a design
 * they cannot open, and enumerate which design ids exist by watching which writes succeeded. The
 * reverse lookup is the same rule read backwards: a directory scan finds the records, and each
 * one's design is read as the caller before its id is named, so the answer never says a design
 * exists to somebody who cannot open it.
 */
internal fun Route.installUiBuilderLinksRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderLinksStore,
) {
  get(UI_BUILDER_LINKS_PATH) {
    val designId =
      call.authorizedLinkedDesign(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    val stored = withContext(Dispatchers.IO) { store.read(designId) }
    if (stored == null) {
      // 404 rather than an empty record: "nobody has said what this design is for" and "this design
      // is for nothing" are different answers, and only the first one is true.
      call.respondLinksError(HttpStatusCode.NotFound, "no links are recorded for this design")
      return@get
    }
    call.respondLinks(stored)
  }

  put(UI_BUILDER_LINKS_PATH) {
    val designId =
      call.authorizedLinkedDesign(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@put
    val request = call.receiveLinksBody() ?: return@put
    when (val result = withContext(Dispatchers.IO) { store.replace(designId, request) }) {
      is LinksWriteResult.Refused ->
        // 422 rather than 400: the body parsed and the request was understood; this value is not
        // one this host will keep, which is a fact about the link rather than about the call.
        call.respondLinksError(HttpStatusCode.UnprocessableEntity, result.reason)
      // 500 rather than 422: the record was fine and the disk was not, so this is worth retrying
      // and a 422 would tell the caller to stop sending a payload that would have worked.
      is LinksWriteResult.Failed ->
        call.respondLinksError(HttpStatusCode.InternalServerError, result.reason)
      // An all-empty record stored nothing — the store deleted the file — and the reply says so by
      // handing back the record that is now in force, which is a record with nothing in it.
      is LinksWriteResult.Stored -> call.respondLinks(result.links)
    }
  }

  delete(UI_BUILDER_LINKS_PATH) {
    val designId =
      call.authorizedLinkedDesign(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@delete
    when (withContext(Dispatchers.IO) { store.delete(designId) }) {
      // 204 whether or not there was one: deleting what is already gone is the state the caller
      // asked for, and a 404 here only tells them whether somebody else got there first.
      LinksDeleteResult.REMOVED,
      LinksDeleteResult.ABSENT -> call.respondText("", status = HttpStatusCode.NoContent)
      // A record still on disk is not the state the caller asked for, and saying 204 over it is
      // how an issue or a pull request survives the request to forget it.
      LinksDeleteResult.FAILED ->
        call.respondLinksError(
          HttpStatusCode.InternalServerError,
          "the links record could not be removed",
        )
    }
  }

  get(UI_BUILDER_LINKS_LOOKUP_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor = call.authorizedActor(authorization, UiBuilderRouteCapability.READ) ?: return@get
    val issue = call.request.queryParameters["issue"].orEmpty().trim()
    if (issue.isEmpty()) {
      call.respondLinksError(HttpStatusCode.BadRequest, "an `issue` URL is required")
      return@get
    }
    val cited = withContext(Dispatchers.IO) { store.citing(issue) }
    val readable = cited.filter { service.canRead(actor, it) }
    call.respondText(
      LINKS_ROUTE_JSON.encodeToString(
        LinksLookupResponse.serializer(),
        LinksLookupResponse(issue = issue, designIds = readable),
      ),
      ContentType.Application.Json,
      HttpStatusCode.OK,
    )
  }
}

/**
 * The design id this call may act on, or null once the refusal has been written.
 *
 * Reads the design through [service] as the authenticated actor, which is what makes this an
 * authorisation check rather than a path parse.
 */
private suspend fun ApplicationCall.authorizedLinkedDesign(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): String? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor = authorizedActor(authorization, capability) ?: return null
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondLinksError(HttpStatusCode.BadRequest, "a design id is required")
    return null
  }
  // READ decides whether there is a design here to answer about; WRITE decides whether this actor
  // may change it. Both refuse as 404, so a design the caller cannot open and one they may only
  // read are told apart by what they may do, never by whether an id exists.
  val actions = service.designActions(actor, designId)
  if (actions == null) {
    respondLinksError(HttpStatusCode.NotFound, "no such design")
    return null
  }
  if (
    capability == UiBuilderRouteCapability.WRITE && !actions.contains(DesignAccessActionV1.WRITE)
  ) {
    respondLinksError(
      HttpStatusCode.Forbidden,
      "the design's own access control does not permit writing it",
    )
    return null
  }
  return designId
}

/** The authenticated actor for [capability], or null once the refusal has been written. */
private suspend fun ApplicationCall.authorizedActor(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): AuthenticatedUiBuilderActor? =
  when (val decision = authorization.authorize(this, capability)) {
    is UiBuilderAuthorizationDecision.Authorized -> decision.actor
    UiBuilderAuthorizationDecision.Missing -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondLinksError(HttpStatusCode.Unauthorized, "authentication is required")
      null
    }
    UiBuilderAuthorizationDecision.Forbidden -> {
      respondLinksError(HttpStatusCode.Forbidden, "UI-builder access is required")
      null
    }
  }

/** The request body, or null once the refusal has been written. */
private suspend fun ApplicationCall.receiveLinksBody(): StoredLinks? {
  val bytes =
    withContext(Dispatchers.IO) { receiveStream().use { it.readNBytes(MAX_LINKS_BODY_BYTES + 1) } }
  if (bytes.size > MAX_LINKS_BODY_BYTES) {
    respondLinksError(HttpStatusCode.PayloadTooLarge, "the links request is too large")
    return null
  }
  val text = bytes.toString(StandardCharsets.UTF_8)
  val parsed =
    try {
      LINKS_ROUTE_JSON.decodeFromString(StoredLinks.serializer(), text)
    } catch (_: SerializationException) {
      respondLinksError(HttpStatusCode.BadRequest, "the links request could not be read")
      return null
    }
  if (parsed.isEmpty && !text.isDeliberateClear()) {
    // Tolerating an unknown key is how a newer client keeps the rest of its record; reading a body
    // made *only* of unknown keys as a clear is how one misspelled field name silently deletes the
    // issue and the pull request. An empty record has to be asked for, not arrived at.
    respondLinksError(
      HttpStatusCode.UnprocessableEntity,
      "the links request names no link this host knows; send an empty object to clear the record",
    )
    return null
  }
  return parsed
}

/**
 * Whether this body asks for an empty record rather than merely failing to name a link.
 *
 * A clear is `{}`, or an object carrying only the fields this host assigns itself. Anything else
 * that decodes to an empty record got there by naming things this host does not know, which is
 * client skew or a typo — neither of which is a request to forget what a design is for.
 */
private fun String.isDeliberateClear(): Boolean {
  val body =
    try {
      LINKS_ROUTE_JSON.parseToJsonElement(this) as? kotlinx.serialization.json.JsonObject
    } catch (_: SerializationException) {
      null
    } ?: return false
  return body.keys.all { it in HOST_ASSIGNED_LINK_KEYS }
}

/** The fields a client may echo back without meaning anything by them. */
private val HOST_ASSIGNED_LINK_KEYS = setOf("schemaVersion", "designId", "updatedAtEpochMillis")

private suspend fun ApplicationCall.respondLinks(links: StoredLinks) {
  respondText(
    LINKS_ROUTE_JSON.encodeToString(StoredLinks.serializer(), links),
    ContentType.Application.Json,
    HttpStatusCode.OK,
  )
}

private suspend fun ApplicationCall.respondLinksError(status: HttpStatusCode, message: String) {
  respondText(
    LINKS_ROUTE_JSON.encodeToString(LinksErrorResponse.serializer(), LinksErrorResponse(message)),
    ContentType.Application.Json,
    status,
  )
}

/** The designs citing one issue: the "feature view", answered by a directory scan. */
@kotlinx.serialization.Serializable
internal data class LinksLookupResponse(
  val schema: String = "compose-preview/ui-builder-links-lookup/v1",
  val issue: String,
  val designIds: List<String>,
)

@kotlinx.serialization.Serializable internal data class LinksErrorResponse(val error: String)

internal const val UI_BUILDER_LINKS_PATH = "/api/ui-builder/v1/designs/{designId}/links"

internal const val UI_BUILDER_LINKS_LOOKUP_PATH = "/api/ui-builder/v1/links"

/** Five short strings and the JSON around them; nothing here carries bytes. */
private const val MAX_LINKS_BODY_BYTES = 16 * 1024

private val LINKS_ROUTE_JSON =
  kotlinx.serialization.json.Json {
    encodeDefaults = true
    explicitNulls = false
    // Tolerant on the way in, so a client from a newer release that sends a link this host has not
    // learned yet still gets the rest of its record stored rather than a 400.
    ignoreUnknownKeys = true
  }
