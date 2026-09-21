package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Preview the one safe recovery for a design whose exact catalog pin disappeared.
 *
 * The service chooses both pins: the stored document supplies the source and the catalog executor
 * supplies the exact reference served now. A browser therefore never guesses a capability digest or
 * silently follows `latest`. This route writes nothing; confirmation is the ordinary, hash-bound
 * `CatalogUpgradeMutationV1` sent through the v1 request endpoint.
 */
internal fun Route.installUiBuilderCatalogRecoveryRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
) {
  get(UI_BUILDER_CATALOG_RECOVERY_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondCatalogRecoveryError(
            HttpStatusCode.Unauthorized,
            "authentication is required",
          )
          return@get
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondCatalogRecoveryError(
            HttpStatusCode.Forbidden,
            "UI-builder read access required",
          )
          return@get
        }
      }
    val designId = call.parameters["designId"].orEmpty()
    if (designId.isBlank()) {
      call.respondCatalogRecoveryError(HttpStatusCode.BadRequest, "a design id is required")
      return@get
    }
    when (
      val response =
        withContext(Dispatchers.IO) {
          service.execute(
            UiBuilderServiceCall(
              actor,
              UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade(designId),
            )
          )
        }
    ) {
      is UiBuilderServiceResponse.CatalogUpgradePreview ->
        call.respondText(
          UI_BUILDER_JSON.encodeToString(
            UiBuilderCatalogRecoveryPreview.serializer(),
            UiBuilderCatalogRecoveryPreview(preview = response.preview),
          ),
          ContentType.Application.Json,
        )
      is UiBuilderServiceResponse.Error ->
        call.respondCatalogRecoveryError(
          HttpStatusCode.fromValue(response.httpStatusValue()),
          response.error.message,
        )
      else ->
        call.respondCatalogRecoveryError(
          HttpStatusCode.InternalServerError,
          "catalog recovery returned an unexpected response",
        )
    }
  }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondCatalogRecoveryError(
  status: HttpStatusCode,
  message: String,
) {
  respondText(
    UI_BUILDER_JSON.encodeToString(
      UiBuilderCatalogRecoveryError.serializer(),
      UiBuilderCatalogRecoveryError(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

@Serializable
internal data class UiBuilderCatalogRecoveryPreview(
  val schema: String = "compose-preview-serve/ui-builder-catalog-recovery/v1",
  val preview: CatalogUpgradePreviewV1,
)

@Serializable private data class UiBuilderCatalogRecoveryError(val error: String)

internal const val UI_BUILDER_CATALOG_RECOVERY_PATH =
  "/api/ui-builder/v1/designs/{designId}/catalog-recovery"
