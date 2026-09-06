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
  /**
   * [presentedToken] is a grant token that arrived in the request body rather than on the call —
   * the MCP lane's [ServeCatalogMcp.TOKEN_ARGUMENT], which exists because an MCP client cannot
   * change its request headers mid-session. Null everywhere a route reads the call itself.
   */
  fun authorize(
    call: ApplicationCall,
    capability: UiBuilderRouteCapability,
    presentedToken: String?,
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
    ): ServeUiBuilderAuthorization = ServeUiBuilderAuthorization { call, capability, presented ->
      when (
        val decision =
          authorization.authorizeCapability(call, capability.agentGrantCapability(), presented)
      ) {
        is ServeMachineAuthorization.Decision.Authorized ->
          UiBuilderAuthorizationDecision.Authorized(decision.actorId)
        ServeMachineAuthorization.Decision.Missing -> UiBuilderAuthorizationDecision.Missing
        is ServeMachineAuthorization.Decision.Forbidden -> UiBuilderAuthorizationDecision.Forbidden
      }
    }
  }
}

/** How a UI-builder route's capability names itself in a grant. */
internal fun UiBuilderRouteCapability.agentGrantCapability(): AgentGrantCapability =
  when (this) {
    UiBuilderRouteCapability.READ -> AgentGrantCapability.UI_BUILDER_READ
    UiBuilderRouteCapability.WRITE -> AgentGrantCapability.UI_BUILDER_WRITE
    UiBuilderRouteCapability.EXPORT -> AgentGrantCapability.UI_BUILDER_EXPORT
  }

/** The ordinary call-only form: routes that read the credential off the request present nothing. */
fun ServeUiBuilderAuthorization.authorize(
  call: ApplicationCall,
  capability: UiBuilderRouteCapability,
): UiBuilderAuthorizationDecision = authorize(call, capability, presentedToken = null)
