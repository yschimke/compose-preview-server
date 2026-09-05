package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.NewDesignState
import ee.schimke.composeai.uibuilder.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.toDesignDocumentV1
import java.io.File
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
) {

  sealed interface Outcome {
    /** The design did not exist and now does. */
    data object Created : Outcome

    /** It existed already. Creation never overwrites, so this is a success with nothing done. */
    data object AlreadyExists : Outcome

    data class Refused(val status: Int, val reason: String) : Outcome
  }

  suspend fun create(
    actorId: String,
    catalogSystemId: String,
    designId: String,
    templateId: String,
    state: List<NewDesignState>,
  ): Outcome {
    val actor = AuthenticatedUiBuilderActor(actorId)
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

  private companion object {
    const val NEW_DESIGN_FIXTURE = "jetcaster-discover-operations-v1.json"
  }
}
