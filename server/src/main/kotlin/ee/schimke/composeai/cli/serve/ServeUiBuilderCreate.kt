package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.NewDesignState
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.io.File
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Creating a design from intent — an id, a catalog, a template — rather than from a document.
 *
 * This is what makes the create route a form the browser can submit: the caller says *what kind of
 * design*, and the server produces the document, pinned to the catalog revision it actually serves.
 * The seeding itself is [UiBuilderNewDesignSeed] in `:ui-builder-export`, shared with the browser
 * so a template means one thing on both sides; what lives here is the part that needs a server —
 * the served catalog's revision, the fixture on disk, and the authenticated write.
 */
internal class ServeUiBuilderCreate(
  private val service: UiBuilderServicePort,
  private val uiBuilderDir: File,
  /**
   * This server's configured public origin, normalized; null when the operator has not stated one.
   * Without it no document is stamped with a server home, because a bind address (loopback, an
   * auto-picked port) is not an identity another copy can find its way back to.
   */
  private val serverOrigin: String?,
) {

  sealed interface Outcome {
    /** The design did not exist and now does. */
    data object Created : Outcome

    /** It existed already. Creation never overwrites, so this is a success with nothing done. */
    data object AlreadyExists : Outcome

    data class Refused(val status: Int, val reason: String) : Outcome
  }

  suspend fun create(
    actor: AuthenticatedUiBuilderActor,
    catalogSystemId: String,
    designId: String,
    templateId: String,
    state: List<NewDesignState>,
  ): Outcome {
    when (val existing = service.executeMapped(OpenDesignRequestV1(designId), actor)) {
      is UiBuilderServiceResponse.Error ->
        if (existing.error.code != ServiceErrorCodeV1.NOT_FOUND) {
          return Outcome.Refused(existing.httpStatusValue(), existing.error.message)
        }
      else -> return Outcome.AlreadyExists
    }
    val catalogs =
      when (val listed = service.executeMapped(ListCatalogsRequestV1, actor)) {
        is UiBuilderServiceResponse.Catalogs -> listed.catalogs
        is UiBuilderServiceResponse.Error ->
          return Outcome.Refused(listed.httpStatusValue(), listed.error.message)
        else -> return Outcome.Refused(500, "the design service did not list its catalogs")
      }
    val benchmark =
      catalogs.map { it.benchmark }.singleOrNull { it.catalogSystemId == catalogSystemId }
        ?: return Outcome.Refused(409, "$catalogSystemId is not a catalog this server authors")
    val fixtureFile = File(uiBuilderDir, NEW_DESIGN_FIXTURE)
    if (!fixtureFile.isFile) {
      return Outcome.Refused(
        500,
        "the builder distribution is missing $NEW_DESIGN_FIXTURE, which every template reads its " +
          "environment from",
      )
    }
    val document =
      try {
        UiBuilderNewDesignSeed.document(
            designId = designId,
            catalogSystemId = catalogSystemId,
            templateId = templateId,
            catalogRevision = benchmark.catalogRevision,
            nativeRuntimeId = benchmark.nativeRuntimeId,
            fixture = Json.parseToJsonElement(fixtureFile.readText()).jsonObject,
            state = state,
          )
          .toDesignDocumentV1()
          .withServerHome(serverOrigin)
      } catch (e: IllegalArgumentException) {
        // The template builders refuse a design they know cannot work — a state variable that
        // becomes a Kotlin keyword, two that collide once exported. That is a bad request, and
        // its message is written for the person who typed the name.
        return Outcome.Refused(400, e.message ?: "the design cannot be created as described")
      }
    return when (val created = service.executeMapped(CreateDesignRequestV1(document), actor)) {
      is UiBuilderServiceResponse.Error ->
        // The service reports "already exists" as a bad request, and the existence check above
        // already passed, so a bad request here is the race between two creates of one id: the
        // design exists, which is the outcome the caller wanted anyway.
        if (created.error.code == ServiceErrorCodeV1.BAD_REQUEST) Outcome.AlreadyExists
        else Outcome.Refused(created.httpStatusValue(), created.error.message)
      else -> Outcome.Created
    }
  }

  /**
   * Create a design from a whole document somebody else authored — a catalog project's published
   * design, opened here.
   *
   * The same two guards as [create] and for the same reasons: a design that already exists is left
   * alone rather than overwritten, and a document is refused unless this server actually authors
   * the catalog it pins, because a pin naming a catalog we do not serve produces a design that
   * cannot render, export or be opened. What is deliberately *not* re-checked is the document's
   * shape: the service validates every node against the catalog it resolves, and duplicating that
   * here would be a second opinion that can disagree with the one that counts.
   */
  suspend fun install(actor: AuthenticatedUiBuilderActor, document: DesignDocumentV1): Outcome =
    install(actor, document, published = false)

  /**
   * Open a published library entry. Idempotent: an entry that is already open is
   * [Outcome.AlreadyExists] whatever home it carries. A library entry is a published copy of a
   * design whose canonical home is usually its repository, so a home it already carries is retained
   * as-is — the opened design points back at that original — rather than refused or replaced; only
   * an unhomed entry is stamped with this server's home.
   */
  suspend fun installPublished(
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
  ): Outcome = install(actor, document, published = true)

  private suspend fun install(
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    published: Boolean = false,
  ): Outcome {
    when (val existing = service.executeMapped(OpenDesignRequestV1(document.id), actor)) {
      is UiBuilderServiceResponse.Error ->
        if (existing.error.code != ServiceErrorCodeV1.NOT_FOUND) {
          return Outcome.Refused(existing.httpStatusValue(), existing.error.message)
        }
      is UiBuilderServiceResponse.Snapshot ->
        return if (published) Outcome.AlreadyExists
        else existingDesignOutcome(document.id, document, serverOrigin)
      else -> return Outcome.AlreadyExists
    }
    if (!published) {
      incomingHomeRefusal(document, serverOrigin)?.let {
        return Outcome.Refused(409, it)
      }
    }
    val homed =
      if (published && document.home != null) document else document.withServerHome(serverOrigin)
    val catalogSystemId = document.catalogPin.systemId
    when (val listed = service.executeMapped(ListCatalogsRequestV1, actor)) {
      is UiBuilderServiceResponse.Catalogs ->
        listed.catalogs.map { it.benchmark }.singleOrNull { it.catalogSystemId == catalogSystemId }
          ?: return Outcome.Refused(
            409,
            "$catalogSystemId is not a catalog this server authors, so its designs cannot be opened here",
          )
      is UiBuilderServiceResponse.Error ->
        return Outcome.Refused(listed.httpStatusValue(), listed.error.message)
      else -> return Outcome.Refused(500, "the design service did not list its catalogs")
    }
    return when (val created = service.executeMapped(CreateDesignRequestV1(homed), actor)) {
      is UiBuilderServiceResponse.Error ->
        if (created.error.code == ServiceErrorCodeV1.BAD_REQUEST) Outcome.AlreadyExists
        else Outcome.Refused(created.httpStatusValue(), created.error.message)
      else -> Outcome.Created
    }
  }

  private companion object {
    const val NEW_DESIGN_FIXTURE = "jetcaster-discover-operations-v1.json"
  }
}

/**
 * A create or import makes this server the document's canonical home.
 *
 * The install paths inspect the incoming home before this is applied, so adopting a repository or
 * another server's canonical document cannot be mistaken for an ordinary create. Once accepted, the
 * server owns the new document and every export must point back here. A server with no configured
 * public origin has no identity to stamp, and leaves the document unhomed exactly as documents were
 * before homes existed.
 */
internal fun DesignDocumentV1.withServerHome(serverOrigin: String?): DesignDocumentV1 =
  copy(
    home =
      serverOrigin?.let { origin ->
        DesignHomeV1.Server(
          requireNotNull(normalizeServerHomeUrl(origin)) {
            "the canonical server origin must be an absolute HTTP(S) URL"
          },
          id,
        )
      }
  )

/** A same-server duplicate is a request to edit the original, not an idempotent import. */
internal fun existingDesignOutcome(
  designId: String,
  incoming: DesignDocumentV1,
  serverOrigin: String?,
): ServeUiBuilderCreate.Outcome =
  when (val home = incoming.home) {
    is DesignHomeV1.Server if sameCanonicalServerHome(home, designId, serverOrigin) ->
      ServeUiBuilderCreate.Outcome.Refused(
        409,
        "$designId already lives on this server; apply changes to the original instead",
      )
    else -> ServeUiBuilderCreate.Outcome.AlreadyExists
  }

/** Refuse implicit adoption; moving a canonical home is a distinct revision-pinned operation. */
internal fun incomingHomeRefusal(
  incoming: DesignDocumentV1,
  serverOrigin: String?,
): String? =
  when (val home = incoming.home) {
    null -> null
    is DesignHomeV1.Server ->
      if (sameCanonicalServerHome(home, incoming.id, serverOrigin)) {
        null
      } else {
        "${incoming.id} already has a different canonical server home; move it explicitly before importing it here"
      }
    is DesignHomeV1.Repo ->
      "${incoming.id} already has a canonical repository home; move it explicitly before importing it here"
  }

private fun sameCanonicalServerHome(
  home: DesignHomeV1.Server,
  designId: String,
  serverOrigin: String?,
): Boolean {
  if (serverOrigin == null || home.designId != designId) return false
  val homeUrl = normalizeServerHomeUrl(home.url) ?: return false
  val originUrl = normalizeServerHomeUrl(serverOrigin) ?: return false
  return homeUrl == originUrl
}

/**
 * Mirrors the design-sync URL identity rule: host/scheme case, default ports and trailing slash.
 */
internal fun normalizeServerHomeUrl(raw: String): String? {
  val parsed = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
  val scheme = parsed.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
  val host = parsed.host?.lowercase() ?: return null
  if (parsed.userInfo != null || parsed.rawQuery != null || parsed.rawFragment != null) return null
  val port =
    parsed.port.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) }
      ?: -1
  val path = parsed.rawPath.orEmpty().let { if (it == "/") "" else it.trimEnd('/') }
  val renderedHost = if (':' in host) "[$host]" else host
  return "$scheme://$renderedHost${if (port == -1) "" else ":$port"}$path"
}
