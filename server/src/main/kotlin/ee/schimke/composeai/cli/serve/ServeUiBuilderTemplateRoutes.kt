package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Starting documents are public catalog data, gated like the catalog list, never private designs.
 */
internal fun Route.installUiBuilderTemplateRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  seeds: UiBuilderCatalogSeeds,
  catalogScope: (ApplicationCall) -> Set<String>?,
) {
  get("/api/ui-builder/v1/catalogs/{system}/templates") {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          call.respondText("authentication is required", status = HttpStatusCode.Unauthorized)
          return@get
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondText("catalog access is forbidden", status = HttpStatusCode.Forbidden)
          return@get
        }
      }
    val system = call.parameters["system"].orEmpty()
    val scope = catalogScope(call)
    if (scope != null && system !in scope) {
      call.respondText("catalog not found", status = HttpStatusCode.NotFound)
      return@get
    }
    val listed = service.executeMapped(ListCatalogsRequestV1, actor)
    if (listed is UiBuilderServiceResponse.Error) {
      call.respondText(
        listed.error.message,
        status = HttpStatusCode.fromValue(listed.httpStatusValue()),
      )
      return@get
    }
    val benchmark =
      (listed as? UiBuilderServiceResponse.Catalogs)
        ?.catalogs
        ?.singleOrNull { it.benchmark.catalogSystemId == system }
        ?.benchmark
    if (benchmark == null) {
      call.respondText("catalog not found", status = HttpStatusCode.NotFound)
      return@get
    }
    val documents =
      seeds.publishedDocuments(system, benchmark.catalogRevision, benchmark.nativeRuntimeId)
    if (documents == null) {
      call.respondText("catalog templates are refreshing; retry", status = HttpStatusCode.Conflict)
      return@get
    }
    // Clients cache this whole envelope under its exact catalog pin. A refresh between listing and
    // fetching must never silently attach the new templates to the old remembered capability.
    call.respondText(
      UI_BUILDER_JSON.encodeToString(
        UiBuilderTemplateDocuments(
          system,
          benchmark.catalogRevision,
          benchmark.nativeRuntimeId,
          documents,
        )
      ),
      ContentType.Application.Json,
    )
  }
}

@Serializable
internal data class UiBuilderTemplateDocuments(
  val catalogSystemId: String,
  val catalogRevision: String,
  val nativeRuntimeId: String,
  val templates: Map<String, UiBuilderDocument>,
)
