package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderPublicAccess
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ServeUiBuilderVisibilityTest {
  private val calls = mutableListOf<UiBuilderServiceCall>()

  /** Answers a create with an error, so the wrapper's reaction to the answer is what is tested. */
  private val refusingCreate =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        calls += call
        return UiBuilderServiceResponse.Catalogs(emptyList())
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  @Test
  fun `private is the default and leaves the service untouched`() {
    assertEquals(UiBuilderDefaultVisibility.PRIVATE, UiBuilderDefaultVisibility.parse(null))
    assertEquals(UiBuilderDefaultVisibility.PUBLIC, UiBuilderDefaultVisibility.parse("Public"))
    assertFailsWith<IllegalArgumentException> { UiBuilderDefaultVisibility.parse("open") }
    assertSame(
      refusingCreate,
      ServeUiBuilderVisibility.withDefault(refusingCreate, UiBuilderDefaultVisibility.PRIVATE),
    )
  }

  @Test
  fun `a public default only follows a create that succeeded`() {
    val wrapped =
      ServeUiBuilderVisibility.withDefault(refusingCreate, UiBuilderDefaultVisibility.PUBLIC)
    val actor = AuthenticatedUiBuilderActor("github:owner")
    runBlocking {
      wrapped.execute(
        UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListDesigns(cursor = null, limit = 1))
      )
    }
    assertEquals(1, calls.size, "nothing but a create is followed by a grant")
  }

  @Test
  fun `the public grant only ever lets people look`() {
    val grant = ServeUiBuilderVisibility.makePublic()
    assertEquals(UiBuilderPublicAccess.ANYONE_ACTOR_ID, grant.actorId)
    assertTrue(grant.allowedActions.all { it in UiBuilderPublicAccess.PUBLIC_ACTIONS })
    assertTrue(
      ServeUiBuilderVisibility.isReservedActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID)
    )
    assertTrue(ServeUiBuilderVisibility.isReservedActor(grant.actorId))
    assertEquals(false, ServeUiBuilderVisibility.isReservedActor("github:octocat"))
    assertTrue(grant is GrantActorAccessMutationV1)
  }
}
