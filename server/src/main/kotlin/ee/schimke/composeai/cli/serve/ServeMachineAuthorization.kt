package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * Shared authentication boundary for headless clients of this server.
 *
 * Catalog MCP and UI-builder MCP are deliberately separate product surfaces, but they should not
 * grow separate bearer parsing, token comparison, or grant-lifetime rules. Both resolve the same
 * operator token, GitHub session, and short-lived agent grant here; each then asks a different
 * authorization question (the catalog scope ladder or a UI-builder capability).
 */
class ServeMachineAuthorization(
  private val serverToken: String,
  private val githubAuth: ServeGithubAuth?,
  private val agentGrants: ServeAgentGrantStore?,
) {
  sealed interface Decision {
    data class Authorized(val actorId: String) : Decision

    data object Missing : Decision

    data class Forbidden(val message: String) : Decision
  }

  fun authorizeScope(call: ApplicationCall, required: AgentGrantScope): Decision {
    if (hasOperatorToken(call)) return Decision.Authorized("operator")

    presentedGrant(call)?.let { grant ->
      return if (grant.allows(required)) {
        Decision.Authorized("agent:${grant.fingerprint}")
      } else {
        Decision.Forbidden(
          "This agent grant covers ${grant.scopes.joinToString(", ") { it.wire }}; " +
            "'${required.wire}' was not approved."
        )
      }
    }

    val login = githubAuth?.currentLogin(call) ?: return Decision.Missing
    val allowed =
      when (required) {
        AgentGrantScope.PREVIEW,
        AgentGrantScope.LIVE -> true
        AgentGrantScope.PLAYGROUND -> githubAuth.hasRepositoryAccess(call)
      }
    return if (allowed) Decision.Authorized("github:$login")
    else Decision.Forbidden("Repository access is required for '${required.wire}'.")
  }

  fun authorizeCapability(call: ApplicationCall, required: AgentGrantCapability): Decision {
    if (hasOperatorToken(call)) return Decision.Authorized("operator")

    val login = githubAuth?.currentLogin(call)
    if (login != null && githubAuth.hasRepositoryAccess(call)) {
      return Decision.Authorized("github:$login")
    }

    presentedGrant(call)?.let { grant ->
      return if (grant.allows(required)) {
        Decision.Authorized("agent:${grant.fingerprint}")
      } else {
        Decision.Forbidden("This agent grant does not include '${required.wire}'.")
      }
    }
    return Decision.Missing
  }

  fun presentedGrant(call: ApplicationCall): ServeAgentGrantStore.Grant? {
    val store = agentGrants ?: return null
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
      .firstNotNullOfOrNull(store::grantForToken)
  }

  private fun hasOperatorToken(call: ApplicationCall): Boolean {
    if (serverToken.isBlank()) return false
    return sequenceOf(
        call.request.headers[ServeHttpServer.TOKEN_HEADER],
        call.request.queryParameters["token"],
      )
      .filterNotNull()
      .any { ServeUrls.tokensMatch(serverToken, it) }
  }

  private companion object {
    const val BEARER_PREFIX = "Bearer "
  }
}
