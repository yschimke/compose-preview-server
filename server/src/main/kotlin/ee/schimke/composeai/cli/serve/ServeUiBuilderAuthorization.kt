package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import io.ktor.server.application.ApplicationCall

enum class UiBuilderRouteCapability {
  READ,
  WRITE,
  EXPORT,
}

sealed interface UiBuilderAuthorizationDecision {
  data class Authorized(val actorId: String) : UiBuilderAuthorizationDecision {
    init {
      require(actorId.isNotBlank()) { "UI-builder actor id must not be blank" }
    }
  }

  data object Missing : UiBuilderAuthorizationDecision

  data object Forbidden : UiBuilderAuthorizationDecision
}

fun interface ServeUiBuilderAuthorization {
  fun authorize(
    call: ApplicationCall,
    capability: UiBuilderRouteCapability,
  ): UiBuilderAuthorizationDecision

  companion object {
    fun fromServeIdentity(
      serverToken: String,
      githubAuth: ServeGithubAuth?,
      agentGrants: ServeAgentGrantStore?,
    ): ServeUiBuilderAuthorization =
      fromMachineAuthorization(ServeMachineAuthorization(serverToken, githubAuth, agentGrants))

    fun fromMachineAuthorization(
      authorization: ServeMachineAuthorization
    ): ServeUiBuilderAuthorization = ServeUiBuilderAuthorization { call, capability ->
      when (
        val decision = authorization.authorizeCapability(call, capability.agentGrantCapability())
      ) {
        is ServeMachineAuthorization.Decision.Authorized ->
          UiBuilderAuthorizationDecision.Authorized(decision.actorId)
        ServeMachineAuthorization.Decision.Missing -> UiBuilderAuthorizationDecision.Missing
        is ServeMachineAuthorization.Decision.Forbidden -> UiBuilderAuthorizationDecision.Forbidden
      }
    }
  }
}

private fun UiBuilderRouteCapability.agentGrantCapability(): AgentGrantCapability =
  when (this) {
    UiBuilderRouteCapability.READ -> AgentGrantCapability.UI_BUILDER_READ
    UiBuilderRouteCapability.WRITE -> AgentGrantCapability.UI_BUILDER_WRITE
    UiBuilderRouteCapability.EXPORT -> AgentGrantCapability.UI_BUILDER_EXPORT
  }
