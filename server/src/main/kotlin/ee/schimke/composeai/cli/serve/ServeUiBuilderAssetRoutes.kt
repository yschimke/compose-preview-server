package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CommandOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetRead
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetReadResult
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetWrite
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The bytes behind a design's `assets` map, over HTTP.
 *
 * A plain `PUT`/`GET` pair beside the design API rather than a protocol request, for the reason the
 * native-preview route is one: the released `UiBuilderRequestV1` union has no asset write, and
 * adding one means releasing `ui-builder-protocol`. What the route does is the service's
 * ([UiBuilderAssetPort]) — this file only reads a body, chooses a status and writes JSON.
 *
 * `PUT` is gated on WRITE and `GET` on READ, the same capabilities the design's other routes use,
 * and the service applies the design's own access list on top: a design one may not open has no
 * pictures one may fetch. The body of a `PUT` is the raw image — no JSON, no base64 — because the
 * caller is usually an agent holding a file, and a megabyte of base64 inside a JSON string is a
 * megabyte and a third for nothing.
 */
internal fun Route.installUiBuilderAssetRoutes(
  authorization: ServeUiBuilderAuthorization,
  assets: UiBuilderAssetPort,
  /** The most a `PUT` body may be; the service has its own, smaller, per-asset ceiling. */
  maximumBodyBytes: Int = MAX_UI_BUILDER_ASSET_BODY_BYTES,
) {
  put(UI_BUILDER_ASSET_PATH) {
    val (actor, designId, assetKey) =
      call.authorizedAsset(authorization, UiBuilderRouteCapability.WRITE) ?: return@put
    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { input -> input.readNBytes(maximumBodyBytes + 1) }
      }
    if (bytes.size > maximumBodyBytes) {
      call.respondAssetError(HttpStatusCode.PayloadTooLarge, "the asset body is too large")
      return@put
    }
    when (
      val response =
        assets.putAsset(
          UiBuilderAssetWrite(
            actor = actor,
            designId = designId,
            assetKey = assetKey,
            bytes = bytes,
          )
        )
    ) {
      is UiBuilderServiceResponse.OperationOutcome ->
        call.respondText(
          ASSET_ROUTE_JSON.encodeToString(
            AssetStoredResponse.serializer(),
            response.toStored(assetKey),
          ),
          ContentType.Application.Json,
          HttpStatusCode.OK,
        )
      is UiBuilderServiceResponse.Error -> call.respondAssetError(response.error)
      else -> call.respondAssetError(HttpStatusCode.InternalServerError, "unexpected asset reply")
    }
  }

  get(UI_BUILDER_ASSET_PATH) {
    val (actor, designId, assetKey) =
      call.authorizedAsset(authorization, UiBuilderRouteCapability.READ) ?: return@get
    when (val result = assets.readAsset(UiBuilderAssetRead(actor, designId, assetKey))) {
      is UiBuilderAssetReadResult.Failed -> call.respondAssetError(result.error)
      is UiBuilderAssetReadResult.Found -> {
        // Immutable by construction — the digest is in the binding, and a re-pointed key is a
        // different snapshot — but private to whoever may read the design, so never shared-cached.
        call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=300")
        call.response.headers.append(HttpHeaders.ETag, "\"${result.binding.contentDigest}\"")
        call.respondBytes(
          result.bytes,
          ContentType.parse(result.binding.mediaType.ifBlank { "application/octet-stream" }),
          HttpStatusCode.OK,
        )
      }
    }
  }
}

/** What a successful `PUT` answers: the binding as pinned, and the revision it moved to. */
@Serializable
internal data class AssetStoredResponse(
  val assetKey: String,
  val outcome: CommandOutcomeV1,
)

private fun UiBuilderServiceResponse.OperationOutcome.toStored(assetKey: String) =
  AssetStoredResponse(assetKey = assetKey, outcome = outcome)

private data class AuthorizedAsset(
  val actor: AuthenticatedUiBuilderActor,
  val designId: String,
  val assetKey: String,
)

private suspend fun ApplicationCall.authorizedAsset(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): AuthorizedAsset? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor =
    when (val decision = authorization.authorize(this, capability)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondAssetError(HttpStatusCode.Unauthorized, "authentication is required")
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondAssetError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return null
      }
    }
  val designId = parameters["designId"].orEmpty()
  val assetKey = parameters["assetKey"].orEmpty()
  if (designId.isBlank() || assetKey.isBlank()) {
    respondAssetError(HttpStatusCode.BadRequest, "a design id and an asset key are required")
    return null
  }
  return AuthorizedAsset(actor, designId, assetKey)
}

private suspend fun ApplicationCall.respondAssetError(error: UiBuilderServiceError) =
  respondAssetError(
    when (error.code) {
      ServiceErrorCodeV1.BAD_REQUEST -> HttpStatusCode.UnprocessableEntity
      ServiceErrorCodeV1.UNAUTHORIZED -> HttpStatusCode.Unauthorized
      ServiceErrorCodeV1.FORBIDDEN -> HttpStatusCode.Forbidden
      ServiceErrorCodeV1.NOT_FOUND -> HttpStatusCode.NotFound
      ServiceErrorCodeV1.ACCESS_REVISION_MISMATCH,
      ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
      ServiceErrorCodeV1.MIGRATION_REQUIRED,
      ServiceErrorCodeV1.SNAPSHOT_REQUIRED -> HttpStatusCode.Conflict
      ServiceErrorCodeV1.INTERNAL -> HttpStatusCode.InternalServerError
    },
    error.message,
  )

private suspend fun ApplicationCall.respondAssetError(status: HttpStatusCode, message: String) {
  respondText(
    ASSET_ROUTE_JSON.encodeToString(AssetErrorResponse.serializer(), AssetErrorResponse(message)),
    ContentType.Application.Json,
    status,
  )
}

@Serializable private data class AssetErrorResponse(val error: String)

internal const val UI_BUILDER_ASSET_PATH = "/api/ui-builder/v1/designs/{designId}/assets/{assetKey}"

/**
 * Bounds the body before it is buffered; the service's `maximumAssetBytes` (a megabyte by default)
 * is the ceiling that actually decides, and this only stops a caller streaming far past it.
 */
internal const val MAX_UI_BUILDER_ASSET_BODY_BYTES = 8 * 1024 * 1024

private val ASSET_ROUTE_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  classDiscriminator = "type"
}
