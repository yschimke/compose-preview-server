package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `GET /api/ui-builder/v1/catalogs/{system}/component-record`: the record the export generates
 * from, for the editor that has to agree with it.
 *
 * ## The disagreement this ends
 *
 * Two lanes reach `ScreenGenerator`. The server's is [ScreenGeneratorComposeExportExecutor], which
 * reads the catalog's own discovered record. The browser's is `ScreenExportGate`, behind the code
 * pane and the problems panel, and it read the one record embedded in `:ui-builder` at build time —
 * `m3-catalog`'s authored 34-component file, whatever catalog the design was pinned to.
 *
 * For a **published** catalog that is not an incomplete record, it is the wrong one. The m3 shelf
 * serves 104 components composed from a record this binary has never read; the export writes call
 * sites for most of them, and the pane answered "no component `m3/…` in this catalog" for
 * everything outside the embedded file. Authoring against a shelf whose code pane says the design
 * cannot be exported, and exporting it anyway, is the divergence `ScreenExportGate` was extracted
 * to prevent.
 *
 * Serving the record is the fix that cannot drift back: the browser does not re-derive the export's
 * opinion from the capabilities it was sent, it reads the bytes the export reads. A projection
 * would have been the alternative and a worse one — the published capabilities say nothing about
 * the facts that decide 26 of those 104 (`state: SearchBarState` has no placeholder,
 * `SegmentedButton` needs its row scope around it, `AdaptiveSticker` is not public), so a pane
 * reading them would confidently print Kotlin that does not compile.
 *
 * ## Catalog-level, authorised once
 *
 * The same [UiBuilderRouteCapability.READ] that lets a caller open the builder, for the reason
 * `installUiBuilderComponentLibraryRoutes` states: there is no design here to check an actor
 * against. Which components a catalog's record proves a call site for is a fact about the catalog,
 * and every caller who can author against it already sees the shelf composed from that record.
 *
 * Cacheable, unlike its neighbours. The record is a build output pinned by the catalog load that
 * fetched it — it does not change while a page is open, and it is the largest thing the editor
 * fetches — so it carries a short private max-age rather than `no-store`. A catalog load that
 * replaces it also replaces the capabilities the page composed against, and reloading the page is
 * what picks both up.
 */
internal fun Route.installUiBuilderCatalogRecordRoutes(
  authorization: ServeUiBuilderAuthorization,
  record: (catalogSystemId: String) -> ComponentRecordFile?,
) {
  get(UI_BUILDER_CATALOG_RECORD_PATH) {
    when (authorization.authorize(call, UiBuilderRouteCapability.READ)) {
      is UiBuilderAuthorizationDecision.Authorized -> Unit
      UiBuilderAuthorizationDecision.Missing -> {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        call.respondCatalogRecordError(HttpStatusCode.Unauthorized, "authentication is required")
        return@get
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        call.respondCatalogRecordError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return@get
      }
    }
    val system = call.parameters["system"].orEmpty()
    if (system.isBlank()) {
      call.respondCatalogRecordError(HttpStatusCode.BadRequest, "a catalog system id is required")
      return@get
    }
    val found = record(system)
    if (found == null) {
      // One answer for "no such catalog", "no record configured for it" and "a record on a schema
      // this build will not generate from". From out here they are the same fact — this host
      // cannot tell you what that catalog exports — and the editor's response to all three is the
      // same: fall back to the record it was built with. The export refuses with the sentence that
      // separates them, where an operator can act on it.
      call.respondCatalogRecordError(
        HttpStatusCode.NotFound,
        "no component record is available for catalog $system",
      )
      return@get
    }
    call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=60")
    call.respondText(
      CATALOG_RECORD_JSON.encodeToString(ComponentRecordFile.serializer(), found),
      ContentType.Application.Json,
    )
  }
}

private suspend fun ApplicationCall.respondCatalogRecordError(
  status: HttpStatusCode,
  message: String,
) {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  respondText(
    CATALOG_RECORD_JSON.encodeToString(
      CatalogRecordErrorResponse.serializer(),
      CatalogRecordErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

@Serializable private data class CatalogRecordErrorResponse(val error: String)

internal const val UI_BUILDER_CATALOG_RECORD_PATH =
  "/api/ui-builder/v1/catalogs/{system}/component-record"

private val CATALOG_RECORD_JSON = Json { encodeDefaults = true }
