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
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `GET /api/ui-builder/v1/designs/{designId}/component-drift`: has this design's library moved?
 *
 * The other end of an import. A design holds the body of every shared component it uses — that is
 * what lets it draw and export without asking anything — and the digest recorded beside each one is
 * what turns the copy back into a reference. This route computes those digests again and says which
 * no longer match.
 *
 * **Reporting, never repairing.** Nothing here writes. A drifted design keeps drawing exactly what
 * it has always drawn until the person who owns it decides to take the new version, which is the
 * bargain a catalog pin already strikes: a design must never quietly become a different design.
 *
 * **Authorised twice**, unlike the library listing beside it. That one answers a catalog-level
 * question every builder user may ask; this one reads *a design*, so the route capability decides
 * whether the caller may use the builder and the design's own access control — enforced by the
 * snapshot request itself, not re-implemented here — decides whether they may read this one. A
 * design they cannot open answers exactly as a design that does not exist.
 */
internal fun Route.installUiBuilderComponentDriftRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  drift: ServeUiBuilderComponentDrift,
  catalogs: () -> List<ServeUiBuilderDesignLibrary.Coordinate>,
) {
  get(UI_BUILDER_COMPONENT_DRIFT_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val actor =
      when (val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)) {
        is UiBuilderAuthorizationDecision.Authorized -> decision.actor
        UiBuilderAuthorizationDecision.Missing -> {
          call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
          call.respondDriftError(HttpStatusCode.Unauthorized, "authentication is required")
          return@get
        }
        UiBuilderAuthorizationDecision.Forbidden -> {
          call.respondDriftError(HttpStatusCode.Forbidden, "UI-builder access is required")
          return@get
        }
      }
    val designId = call.parameters["designId"].orEmpty()
    if (designId.isBlank()) {
      call.respondDriftError(HttpStatusCode.BadRequest, "a design id is required")
      return@get
    }
    val document = service.documentFor(designId, actor)
    if (document == null) {
      // One answer for "no such design" and "not yours": the second must not be distinguishable
      // from the first, or this route enumerates design ids for anyone holding a read capability.
      call.respondDriftError(HttpStatusCode.NotFound, "no design `$designId` this actor can read")
      return@get
    }
    val findings = withContext(Dispatchers.IO) { drift.check(document.components, catalogs()) }
    call.respondText(
      DRIFT_JSON.encodeToString(
        ComponentDriftResponse.serializer(),
        ComponentDriftResponse(
          designId = designId,
          revision = document.revision,
          components =
            findings.map {
              ComponentDriftDto(
                componentKey = it.componentKey,
                system = it.source.system,
                componentId = it.source.componentId,
                paletteId = ServeUiBuilderComponentLibrary.paletteId(it.source.componentId),
                state = it.state.name.lowercase(),
                importedDigest = it.source.digest,
                currentDigest = it.currentDigest,
              )
            },
        ),
      ),
      ContentType.Application.Json,
    )
  }
}

/**
 * The design as this actor may read it, or null when they may not — the two are one answer here.
 */
private suspend fun UiBuilderServicePort.documentFor(
  designId: String,
  actor: AuthenticatedUiBuilderActor,
) =
  (UiBuilderProtocolMapper.toServiceCall(
      actor,
      GetSnapshotRequestV1(designId = designId, revision = null),
    ) as? ProtocolRequestMapping.Mapped)
    ?.let { withContext(Dispatchers.IO) { execute(it.call) } }
    .let { it as? UiBuilderServiceResponse.Snapshot }
    ?.snapshot
    ?.state
    ?.document

private suspend fun ApplicationCall.respondDriftError(status: HttpStatusCode, message: String) {
  respondText(
    DRIFT_JSON.encodeToString(
      ComponentDriftErrorResponse.serializer(),
      ComponentDriftErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

@Serializable private data class ComponentDriftErrorResponse(val error: String)

/**
 * One imported component's verdict.
 *
 * [state] is `unchanged`, `drifted`, `withdrawn` or `unusable`. The last two are separate on
 * purpose: "the project removed this" and "the project's copy is broken today" call for different
 * actions, and a branch that is briefly unreachable must not read as a deletion.
 */
@Serializable
internal data class ComponentDriftDto(
  val componentKey: String,
  val system: String,
  val componentId: String,
  /** How the same symbol is named on the palette, so a client need not re-derive the rule. */
  val paletteId: String,
  val state: String,
  /** The digest recorded when this design imported the symbol. */
  val importedDigest: String,
  /** What the library computes now — present only when it differs. */
  val currentDigest: String? = null,
)

@Serializable
internal data class ComponentDriftResponse(
  val schema: String = "compose-preview-serve/ui-builder-component-drift/v1",
  val designId: String,
  /** The revision the answer was computed over, so a later write is legibly not covered by it. */
  val revision: Long,
  /** Imported components only, in key order; a component authored here has nothing to drift. */
  val components: List<ComponentDriftDto> = emptyList(),
)

internal const val UI_BUILDER_COMPONENT_DRIFT_PATH =
  "/api/ui-builder/v1/designs/{designId}/component-drift"

// `encodeDefaults` so the schema and an empty component list are always written; `explicitNulls`
// off so `currentDigest` is absent rather than null when nothing changed — the field is evidence
// for a claim of drift, and a present-but-null one reads as a digest that could not be computed.
private val DRIFT_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
}
