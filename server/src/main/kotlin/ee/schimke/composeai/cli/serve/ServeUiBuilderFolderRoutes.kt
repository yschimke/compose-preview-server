package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Shared persistent file-manager folders, deliberately separate from design revisions. */
internal fun Route.installUiBuilderFolderRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderFolderStore,
) {
  get(UI_BUILDER_FOLDERS_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      call.authorizedFolderActor(authorization, UiBuilderRouteCapability.READ) ?: return@get
    val stored = withContext(Dispatchers.IO) { store.readAll() }
    val readable = stored.filterKeys { service.canRead(actor, it) }
    call.respondFolders(readable)
  }

  put(UI_BUILDER_FOLDER_PATH) {
    val actor = call.authorizedFolderDesign(service, authorization) ?: return@put
    val designId = call.parameters["designId"].orEmpty()
    val request = call.receiveFolderBody() ?: return@put
    when (val result = withContext(Dispatchers.IO) { store.move(designId, request.folder) }) {
      FolderWriteResult.Stored ->
        call.respondFolders(
          withContext(Dispatchers.IO) { store.readAll() }.filterKeys { service.canRead(actor, it) }
        )
      is FolderWriteResult.Refused ->
        call.respondFolderError(HttpStatusCode.UnprocessableEntity, result.reason)
      is FolderWriteResult.Failed ->
        call.respondFolderError(HttpStatusCode.InternalServerError, result.reason)
    }
  }

  delete(UI_BUILDER_FOLDER_PATH) {
    val actor = call.authorizedFolderDesign(service, authorization) ?: return@delete
    val designId = call.parameters["designId"].orEmpty()
    when (val result = withContext(Dispatchers.IO) { store.move(designId, null) }) {
      FolderWriteResult.Stored ->
        call.respondFolders(
          withContext(Dispatchers.IO) { store.readAll() }.filterKeys { service.canRead(actor, it) }
        )
      is FolderWriteResult.Refused ->
        call.respondFolderError(HttpStatusCode.UnprocessableEntity, result.reason)
      is FolderWriteResult.Failed ->
        call.respondFolderError(HttpStatusCode.InternalServerError, result.reason)
    }
  }
}

private suspend fun ApplicationCall.authorizedFolderDesign(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
): AuthenticatedUiBuilderActor? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor = authorizedFolderActor(authorization, UiBuilderRouteCapability.WRITE) ?: return null
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondFolderError(HttpStatusCode.BadRequest, "a design id is required")
    return null
  }
  val actions = service.designActions(actor, designId)
  if (actions == null) {
    respondFolderError(HttpStatusCode.NotFound, "the design was not found")
    return null
  }
  if (!actions.contains(DesignAccessActionV1.WRITE)) {
    respondFolderError(
      HttpStatusCode.Forbidden,
      "the design's own access control does not permit moving it",
    )
    return null
  }
  return actor
}

private suspend fun ApplicationCall.authorizedFolderActor(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): AuthenticatedUiBuilderActor? =
  when (val decision = authorization.authorize(this, capability)) {
    is UiBuilderAuthorizationDecision.Authorized -> decision.actor
    UiBuilderAuthorizationDecision.Missing -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondFolderError(HttpStatusCode.Unauthorized, "authentication is required")
      null
    }
    UiBuilderAuthorizationDecision.Forbidden -> {
      respondFolderError(HttpStatusCode.Forbidden, "UI-builder access is required")
      null
    }
  }

private suspend fun ApplicationCall.receiveFolderBody(): FolderMoveRequest? {
  val body = receiveText()
  if (body.toByteArray().size > MAX_FOLDER_REQUEST_BYTES) {
    respondFolderError(HttpStatusCode.PayloadTooLarge, "the folder request is too large")
    return null
  }
  return try {
    FOLDER_ROUTE_JSON.decodeFromString(FolderMoveRequest.serializer(), body)
  } catch (_: SerializationException) {
    respondFolderError(HttpStatusCode.BadRequest, "the folder request could not be read")
    null
  }
}

private suspend fun ApplicationCall.respondFolders(folders: Map<String, String>) {
  respondText(
    FOLDER_ROUTE_JSON.encodeToString(
      FolderListResponse.serializer(),
      FolderListResponse(folders = folders),
    ),
    ContentType.Application.Json,
    HttpStatusCode.OK,
  )
}

private suspend fun ApplicationCall.respondFolderError(status: HttpStatusCode, message: String) {
  respondText(
    FOLDER_ROUTE_JSON.encodeToString(
      FolderErrorResponse.serializer(),
      FolderErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

@Serializable private data class FolderMoveRequest(val folder: String? = null)

@Serializable
private data class FolderListResponse(
  val schema: String = "compose-preview/ui-builder-folders/v1",
  val folders: Map<String, String>,
)

@Serializable private data class FolderErrorResponse(val error: String)

internal const val UI_BUILDER_FOLDERS_PATH = "/api/ui-builder/v1/home-folders"
internal const val UI_BUILDER_FOLDER_PATH = "/api/ui-builder/v1/home-folders/{designId}"
private const val MAX_FOLDER_REQUEST_BYTES = 1024
private val FOLDER_ROUTE_JSON = Json { ignoreUnknownKeys = true }
