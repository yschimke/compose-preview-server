package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `ui-builder-destructive` reaches the design service as
 * [ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor.mayDeleteForPrincipal], and
 * only from a grant whose approver ticked it.
 */
class ServeUiBuilderDestructiveGrantTest {
  private val grants =
    ServeAgentGrantStore(
      maxCapabilities =
        setOf(
          AgentGrantCapability.UI_BUILDER_READ,
          AgentGrantCapability.UI_BUILDER_WRITE,
          AgentGrantCapability.UI_BUILDER_DESTRUCTIVE,
        )
    )

  private fun grant(capabilities: Set<AgentGrantCapability>): ServeAgentGrantStore.Grant {
    val request =
      checkNotNull(
        grants.openRequest(
          label = "clean up",
          client = "agent",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 3600,
          requestedCapabilities = capabilities,
        )
      )
    return checkNotNull(
      grants.approve(
        request.id,
        approvedBy = "@owner",
        scope = AgentGrantScope.PREVIEW,
        ttlSeconds = 3600,
        capabilities = capabilities,
        approvedByActorId = OWNER,
      )
    )
  }

  private fun actorFor(grant: ServeAgentGrantStore.Grant) =
    with(ServeMachineAuthorization) { grant.authorized() }
      .let {
        UiBuilderAuthorizationDecision.Authorized(
          it.actorId,
          it.onBehalfOfActorId,
          it.mayDeleteForPrincipal,
        )
      }
      .actor

  @Test
  fun `a grant whose approver ticked destructive may delete for that approver`() {
    val actor =
      actorFor(
        grant(
          setOf(
            AgentGrantCapability.UI_BUILDER_READ,
            AgentGrantCapability.UI_BUILDER_WRITE,
            AgentGrantCapability.UI_BUILDER_DESTRUCTIVE,
          )
        )
      )
    assertEquals(OWNER, actor.onBehalfOfActorId)
    assertTrue(actor.mayDeleteForPrincipal)
  }

  @Test
  fun `write alone never implies destructive`() {
    val actor =
      actorFor(
        grant(setOf(AgentGrantCapability.UI_BUILDER_READ, AgentGrantCapability.UI_BUILDER_WRITE))
      )
    assertEquals(OWNER, actor.onBehalfOfActorId)
    assertFalse(actor.mayDeleteForPrincipal)
  }

  @Test
  fun `the flag is dropped when there is nobody to delete for`() {
    val actor =
      UiBuilderAuthorizationDecision.Authorized(
          "agent:abc",
          onBehalfOfActorId = null,
          mayDeleteForPrincipal = true,
        )
        .actor
    assertFalse(actor.mayDeleteForPrincipal)
    assertFalse(
      UiBuilderAuthorizationDecision.Authorized(
          "agent:abc",
          onBehalfOfActorId = "agent:abc",
          mayDeleteForPrincipal = true,
        )
        .actor
        .mayDeleteForPrincipal,
      "an actor acting for itself has no principal",
    )
  }

  private companion object {
    const val OWNER = "github:owner"
  }
}
