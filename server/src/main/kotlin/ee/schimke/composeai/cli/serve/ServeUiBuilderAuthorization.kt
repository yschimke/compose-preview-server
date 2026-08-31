package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import io.ktor.http.HttpHeaders
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
    ): ServeUiBuilderAuthorization = ServeUiBuilderAuthorization { call, capability ->
      val provided =
        call.request.queryParameters["token"] ?: call.request.headers[ServeHttpServer.TOKEN_HEADER]
      if (serverToken.isNotBlank() && ServeUrls.tokensMatch(serverToken, provided)) {
        return@ServeUiBuilderAuthorization UiBuilderAuthorizationDecision.Authorized("operator")
      }

      val login = githubAuth?.currentLogin(call)
      if (login != null && githubAuth.hasRepositoryAccess(call)) {
        return@ServeUiBuilderAuthorization UiBuilderAuthorizationDecision.Authorized(
          "github:$login"
        )
      }

      val presentedGrant = agentGrants?.presentedGrant(call)
      if (presentedGrant != null) {
        val required = capability.agentGrantCapability()
        return@ServeUiBuilderAuthorization if (presentedGrant.allows(required)) {
          UiBuilderAuthorizationDecision.Authorized("agent:${presentedGrant.fingerprint}")
        } else {
          UiBuilderAuthorizationDecision.Forbidden
        }
      }
      UiBuilderAuthorizationDecision.Missing
    }
  }
}

private fun UiBuilderRouteCapability.agentGrantCapability(): AgentGrantCapability =
  when (this) {
    UiBuilderRouteCapability.READ -> AgentGrantCapability.UI_BUILDER_READ
    UiBuilderRouteCapability.WRITE -> AgentGrantCapability.UI_BUILDER_WRITE
    UiBuilderRouteCapability.EXPORT -> AgentGrantCapability.UI_BUILDER_EXPORT
  }

private fun ServeAgentGrantStore.presentedGrant(
  call: ApplicationCall
): ServeAgentGrantStore.Grant? {
  val bearer =
    call.request.headers[HttpHeaders.Authorization]
      ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
      ?.substring(BEARER_PREFIX.length)
      ?.trim()
  return sequenceOf(
      call.request.headers[ServeHttpServer.TOKEN_HEADER],
      bearer,
      call.request.queryParameters["token"],
    )
    .firstNotNullOfOrNull(::grantForToken)
}

private const val BEARER_PREFIX = "Bearer "
