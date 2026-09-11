package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
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
import io.ktor.server.routing.post
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/**
 * The design as an exported artifact, at a URL.
 *
 * ## Why a GET beside the protocol request
 *
 * Every export already runs through `POST /api/ui-builder/v1/requests` as an
 * `ExportDesignRequestV1`, and that lane stays the authoritative one: revision-pinned, digest and
 * provenance in the artifact, admitted through the same export gate. But a POST with a JSON
 * envelope is not a thing a person can paste into Figma, a README, a pull request, a chat, or an
 * `<img>`. The catalog viewer's preview pages have had `/render/<id>.svg` and `.png` for exactly
 * that reason — "Copy link" copies a URL that renders the preview as it stands — and the builder's
 * designs had no such address.
 *
 * These routes are that address. They are the same export, reached by a URL: the handler builds the
 * very request the envelope route would have received, runs it through the same service as the same
 * actor, and serves the artifact's bytes with the artifact's media type. No second renderer, no
 * second gate, no second capability check.
 *
 * ## Live, not pinned
 *
 * Without `?revision=` the URL renders **the current committed revision on every request**, which
 * is what makes it a link worth sharing: the picture behind it follows the design. The response
 * says which revision it served (`X-UI-Builder-Revision`) and carries the artifact's digest as its
 * `ETag`, so a consumer that cares can tell whether the design moved. `Cache-Control: no-store`
 * follows from the same choice — the whole point is that the bytes go stale. `?revision=N` pins,
 * for a caller that wants the artifact the protocol lane would have handed it at N.
 *
 * ## Download
 *
 * `?download=1` answers with `Content-Disposition: attachment` and a filename, so a plain `<a href
 * download>` — or a browser address bar — saves the file instead of displaying it. Inline
 * otherwise, which is what an `<img>` and a pasted link both want.
 *
 * ## What is deliberately absent
 *
 * No credential rides on the URL. A caller presents the same operator token, session cookie or
 * agent grant every other UI-builder route demands, and the editor's "Copy link" copies the plain
 * URL for that reason: a shared link is a shared address, never a shared credential.
 */
internal fun Route.installUiBuilderLiveExportRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
) {
  get(UI_BUILDER_EXPORT_SVG_PATH) {
    call.serveLiveExport(service, authorization, ExportFormatV1.SVG)
  }
  RemoteDocumentExportSupport.formats.forEach { format ->
    post("/api/ui-builder/v1/documents/export.${format.name.lowercase()}") {
      call.serveSuppliedDocument(service, authorization, format)
    }
    get("/api/ui-builder/v1/designs/{designId}/export.${format.name.lowercase()}") {
      call.serveLiveExport(service, authorization, format)
    }
  }
  get(UI_BUILDER_EXPORT_PNG_PATH) {
    call.serveLiveExport(service, authorization, ExportFormatV1.PNG)
  }
}

private suspend fun ApplicationCall.serveLiveExport(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  format: ExportFormatV1,
) {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor = authorizeExport(authorization) ?: return
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondText("a design id is required", status = HttpStatusCode.BadRequest)
    return
  }
  val revisionParameter = request.queryParameters["revision"]
  val revision = revisionParameter?.toLongOrNull()
  if (revisionParameter != null && (revision == null || revision < 0)) {
    respondText("revision must be a non-negative integer", status = HttpStatusCode.BadRequest)
    return
  }
  val outcome =
    service.executeMapped(
      ExportDesignRequestV1(designId = designId, revision = revision, format = format),
      actor,
    )
  serveExportArtifact(outcome, format, designId)
}

private suspend fun ApplicationCall.serveExportArtifact(
  outcome: UiBuilderServiceResponse,
  format: ExportFormatV1,
  designId: String,
) {
  val artifact =
    when (outcome) {
      is UiBuilderServiceResponse.Export -> outcome.artifact
      is UiBuilderServiceResponse.Error -> {
        respondText(
          outcome.error.message,
          status = HttpStatusCode.fromValue(outcome.httpStatusValue()),
        )
        return
      }
      else -> {
        respondText(
          "the export answered with something other than an artifact",
          status = HttpStatusCode.InternalServerError,
        )
        return
      }
    }
  if (artifact.format != format) {
    respondText(
      "the export answered in ${artifact.format} rather than $format",
      status = HttpStatusCode.InternalServerError,
    )
    return
  }
  val errors = artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
  if (errors.isNotEmpty()) {
    respondText(
      errors.joinToString("\n") { "${it.code}: ${it.message}" },
      status = HttpStatusCode.UnprocessableEntity,
    )
    return
  }
  val bytes =
    when (artifact.encoding) {
      ExportEncodingV1.BASE64 -> Base64.getDecoder().decode(artifact.content)
      ExportEncodingV1.UTF8 -> artifact.content.toByteArray(Charsets.UTF_8)
    }
  val filename = "$designId.${format.fileExtension()}"
  val disposition = if (request.queryParameters["download"].isDownload()) "attachment" else "inline"
  response.headers.append(HttpHeaders.ContentDisposition, "$disposition; filename=\"$filename\"")
  response.headers.append(HttpHeaders.ETag, "\"${artifact.contentDigest}\"")
  artifact.servedRevision()?.let { response.headers.append(UI_BUILDER_REVISION_HEADER, it) }
  respondBytes(bytes, ContentType.parse(artifact.mediaType), HttpStatusCode.OK)
}

/** Supplied content is compiled without allocating a saved design or revision. */
private suspend fun ApplicationCall.serveSuppliedDocument(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  format: ExportFormatV1,
) {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor = authorizeExport(authorization) ?: return
  val bytes =
    withContext(Dispatchers.IO) {
      receiveStream().use { it.readNBytes(MAX_UI_BUILDER_REQUEST_BYTES + 1) }
    }
  if (bytes.size > MAX_UI_BUILDER_REQUEST_BYTES) {
    respondText("request body too large", status = HttpStatusCode.PayloadTooLarge)
    return
  }
  val document =
    try {
      UI_BUILDER_JSON.decodeFromString(
        DesignDocumentV1.serializer(),
        bytes.toString(Charsets.UTF_8),
      )
    } catch (_: SerializationException) {
      respondText("expected a DesignDocumentV1 payload", status = HttpStatusCode.BadRequest)
      return
    }
  val outcome =
    service.execute(
      UiBuilderServiceCall(actor, UiBuilderServiceRequest.ExportDocument(document, format))
    )
  serveExportArtifact(outcome, format, "document")
}

private suspend fun ApplicationCall.authorizeExport(
  authorization: ServeUiBuilderAuthorization
): AuthenticatedUiBuilderActor? {
  val actor =
    when (val decision = authorization.authorize(this, UiBuilderRouteCapability.EXPORT)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondText("authentication is required", status = HttpStatusCode.Unauthorized)
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondText("UI-builder export access required", status = HttpStatusCode.Forbidden)
        return null
      }
    }
  return actor
}

/**
 * The revision the artifact was rendered at, read from its provenance diagnostic.
 *
 * `ExportArtifactV1` carries no revision field of its own — the protocol request pinned it — so the
 * only place it is written down is the `REVISION_PINNED_DAEMON_RENDER` diagnostic the runtime
 * attaches. Absent from a diagnostic-free artifact, and then absent from the response too, rather
 * than guessed.
 */
private fun ExportArtifactV1.servedRevision(): String? =
  diagnostics
    .asSequence()
    .filter {
      it.code == REVISION_PINNED_DIAGNOSTIC_CODE || it.code == "REVISION_PINNED_REMOTE_EXPORT"
    }
    .mapNotNull { REVISION_IN_DIAGNOSTIC.find(it.message)?.groupValues?.get(1) }
    .firstOrNull()

private const val REVISION_PINNED_DIAGNOSTIC_CODE = "REVISION_PINNED_DAEMON_RENDER"
private val REVISION_IN_DIAGNOSTIC = Regex("""\brevision (\d+)\b""")

private fun String?.isDownload(): Boolean =
  this != null && this != "0" && !this.equals("false", ignoreCase = true)

private fun ExportFormatV1.fileExtension(): String =
  when (this) {
    ExportFormatV1.SVG -> "svg"
    ExportFormatV1.PNG -> "png"
    ExportFormatV1.COMPOSE -> "kt"
    // An archive: source plus the picture bytes as files. `application/zip` per the contract.
    ExportFormatV1.BUNDLE -> "zip"
    // Added by contracts 2.17.0. A remote document is `.rc`, and its JSON form is `.json` —
    // the same extension a null format already means here, because both are the design's own
    // JSON.
    ExportFormatV1.JSON -> "json"
    ExportFormatV1.RC -> "rc"
  }

/** The live SVG of one design: the current committed revision, rendered on every request. */
internal const val UI_BUILDER_EXPORT_SVG_PATH = "/api/ui-builder/v1/designs/{designId}/export.svg"

/** The live PNG of one design; see [UI_BUILDER_EXPORT_SVG_PATH]. */
internal const val UI_BUILDER_EXPORT_PNG_PATH = "/api/ui-builder/v1/designs/{designId}/export.png"

/** Which revision a live export actually served, since the URL names none. */
internal const val UI_BUILDER_REVISION_HEADER = "X-UI-Builder-Revision"
