package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
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
    // The revision the caller is looking at, when it is not the head. An editor opened at a pinned
    // revision is showing the components *that* revision imported, and a report computed against
    // head would silently miss one it holds and head does not.
    val revision = call.request.queryParameters["revision"]
    val pinned = revision?.toLongOrNull()
    if (revision != null && pinned == null) {
      call.respondDriftError(HttpStatusCode.BadRequest, "`revision` must be a number")
      return@get
    }
    val document =
      when (val read = service.documentFor(designId, pinned, actor)) {
        is DesignRead.Readable -> read.document
        // One answer for "no such design" and "not yours": the second must not be distinguishable
        // from the first, or this route enumerates design ids for anyone holding a read capability.
        DesignRead.Absent -> {
          call.respondDriftError(
            HttpStatusCode.NotFound,
            "no design `$designId` this actor can read",
          )
          return@get
        }
        // Anything else the service refused with. Folding these into the 404 above would tell an
        // owner their own design does not exist during a catalog outage or a failed migration —
        // a retryable condition reported as a permanent one, with nothing to act on.
        is DesignRead.Refused -> {
          call.respondDriftError(HttpStatusCode.fromValue(read.status), read.message)
          return@get
        }
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
 * What reading the design said: the document, "there is no such design for you", or a refusal.
 *
 * [Absent] is deliberately one state for two facts — no such design, and not yours — because the
 * route must not let a read capability enumerate design ids. Everything else keeps its own status:
 * a catalog outage and a required migration are conditions the caller can act on or retry, and
 * reporting them as a missing design tells an owner their own design is gone.
 */
private sealed interface DesignRead {
  data class Readable(val document: DesignDocumentV1) : DesignRead

  data object Absent : DesignRead

  data class Refused(val status: Int, val message: String) : DesignRead
}

private suspend fun UiBuilderServicePort.documentFor(
  designId: String,
  revision: Long?,
  actor: AuthenticatedUiBuilderActor,
): DesignRead {
  val mapping =
    UiBuilderProtocolMapper.toServiceCall(
      actor,
      GetSnapshotRequestV1(designId = designId, revision = revision),
    )
  // The mapper refusing is the actor failing the design's own access control, which is exactly the
  // case that must be indistinguishable from a design that does not exist.
  val mapped = mapping as? ProtocolRequestMapping.Mapped ?: return DesignRead.Absent
  return when (val response = withContext(Dispatchers.IO) { execute(mapped.call) }) {
    is UiBuilderServiceResponse.Snapshot ->
      response.snapshot.state?.document?.let(DesignRead::Readable) ?: DesignRead.Absent
    is UiBuilderServiceResponse.Error ->
      when (response.error.code) {
        ServiceErrorCodeV1.NOT_FOUND,
        ServiceErrorCodeV1.FORBIDDEN,
        ServiceErrorCodeV1.UNAUTHORIZED -> DesignRead.Absent
        else -> DesignRead.Refused(response.httpStatusValue(), response.error.message)
      }
    // A snapshot request that came back as something else entirely is this host's fault, not the
    // caller's, and saying so beats implying the design is missing.
    else ->
      DesignRead.Refused(HttpStatusCode.InternalServerError.value, "the design could not be read")
  }
}

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
  /**
   * The revision the answer was computed over, so a later write is legibly not covered by it — and
   * so a caller that asked for a specific one can see it got that one.
   */
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
