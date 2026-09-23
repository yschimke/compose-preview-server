package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignActorAccessV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ServeUiBuilderDesignAuthorizationTest {
  @Test
  fun `listed access authorizes sidecars when the catalog is unavailable`() = runBlocking {
    val service =
      object : UiBuilderServicePort {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
          when (val request = call.request) {
            is UiBuilderServiceRequest.GetDesignActions ->
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(
                  ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
                  "catalog unavailable for stored design ${request.designId}",
                )
              )
            is UiBuilderServiceRequest.ListDesigns ->
              if (request.cursor == null)
                UiBuilderServiceResponse.Designs(emptyList(), nextCursor = "1")
              else
                UiBuilderServiceResponse.Designs(
                  listOf(listed("old-widget", DesignAccessActionV1.entries)),
                  nextCursor = null,
                )
            else -> error("unexpected request $request")
          }

        override fun subscribe(
          call: UiBuilderSubscriptionCall,
          listener: (UiBuilderServiceUpdate) -> Unit,
        ): Closeable = Closeable {}
      }

    assertEquals(
      DesignAccessActionV1.entries,
      service.designActions(ACTOR, "old-widget"),
    )
  }

  @Test
  fun `an unreadable design stays indistinguishable from a missing design`() = runBlocking {
    val service =
      object : UiBuilderServicePort {
        override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
          when (call.request) {
            is UiBuilderServiceRequest.GetDesignActions ->
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "not found")
              )
            is UiBuilderServiceRequest.ListDesigns ->
              UiBuilderServiceResponse.Designs(emptyList(), nextCursor = null)
            else -> error("unexpected request ${call.request}")
          }

        override fun subscribe(
          call: UiBuilderSubscriptionCall,
          listener: (UiBuilderServiceUpdate) -> Unit,
        ): Closeable = Closeable {}
      }

    assertEquals(null, service.designActions(ACTOR, "not-mine"))
  }

  private fun listed(id: String, actions: List<DesignAccessActionV1>) =
    DesignListItemV1(
      designId = id,
      title = "Old widget",
      revision = 1,
      accessRevision = 1,
      catalogPin = CatalogReferenceV1("remote-m3", "old", "old", "old"),
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 2,
      ownerActorId = ACTOR.actorId,
      requesterAccess =
        DesignActorAccessV1(
          actorId = ACTOR.actorId,
          role = DesignAccessRoleV1.OWNER,
          allowedActions = actions,
        ),
    )

  private companion object {
    val ACTOR = AuthenticatedUiBuilderActor("github:owner")
  }
}
