package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.util.Base64

/**
 * The design as a picture, at a URL.
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
 * These two routes are that address. They are the same export, reached by a URL: the handler builds
 * the very request the envelope route would have received, runs it through the same service as the
 * same actor, and serves the artifact's bytes with the artifact's media type. No second renderer,
 * no second gate, no second capability check.
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
  val actorId =
    when (val decision = authorization.authorize(this, UiBuilderRouteCapability.EXPORT)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondText("authentication is required", status = HttpStatusCode.Unauthorized)
        return
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondText("UI-builder export access required", status = HttpStatusCode.Forbidden)
        return
      }
    }
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
      AuthenticatedUiBuilderActor(actorId),
    )
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
    .filter { it.code == REVISION_PINNED_DIAGNOSTIC_CODE }
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
  }

/** The live SVG of one design: the current committed revision, rendered on every request. */
internal const val UI_BUILDER_EXPORT_SVG_PATH = "/api/ui-builder/v1/designs/{designId}/export.svg"

/** The live PNG of one design; see [UI_BUILDER_EXPORT_SVG_PATH]. */
internal const val UI_BUILDER_EXPORT_PNG_PATH = "/api/ui-builder/v1/designs/{designId}/export.png"

/** Which revision a live export actually served, since the URL names none. */
internal const val UI_BUILDER_REVISION_HEADER = "X-UI-Builder-Revision"
