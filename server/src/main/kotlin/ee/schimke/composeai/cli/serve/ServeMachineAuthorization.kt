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
  /**
   * Whether this box serves its published catalogs token-free — `--public`.
   *
   * It decides one thing here: whether `preview` scope is satisfied by presenting nothing. On a
   * public box it is, because that is already what `--public` means everywhere else on the server —
   * `GET /api/previews` answers an anonymous caller with the whole preview listing, and the viewer
   * is a page anyone can open. Demanding a grant for the *same* reads over MCP protected nothing;
   * it only meant an agent had to ask a human for permission to see what any passer-by can already
   * see, and — because the refusal arrives during the handshake, on `resources/list` — that a
   * client showed the whole server as needing authorization rather than as connected.
   *
   * A token-gated box is unchanged. There `preview` still requires the operator token, a GitHub
   * session, or a grant, exactly as before.
   */
  private val isPublic: Boolean = false,
) {
  sealed interface Decision {
    /**
     * [onBehalfOfActorId] is the human this credential delegates for, and only an agent grant has
     * one: a grant exists because a person clicked *approve*, so the actor behind it is that
     * person's delegate rather than a stranger who happens to hold a bearer token. Null for every
     * credential that speaks for itself — the operator token and a GitHub session.
     *
     * What consumes it is the UI builder, where authority is per design and a design outlives any
     * grant. Nothing else reads it: a scope ladder asks how much of the machine may be spent, not
     * whose it is.
     */
    data class Authorized(val actorId: String, val onBehalfOfActorId: String? = null) : Decision

    data object Missing : Decision

    data class Forbidden(val message: String) : Decision
  }

  fun authorizeScope(
    call: ApplicationCall,
    required: AgentGrantScope,
    presentedToken: String? = null,
  ): Decision {
    if (hasOperatorToken(call)) return Decision.Authorized(OPERATOR_ACTOR_ID)

    presentedGrant(call, presentedToken)?.let { grant ->
      return if (grant.allows(required)) {
        grant.authorized()
      } else {
        Decision.Forbidden(
          "This agent grant covers ${grant.scopes.joinToString(", ") { it.wire }}; " +
            "'${required.wire}' was not approved."
        )
      }
    }

    val login = githubAuth?.currentLogin(call)
    if (login == null) {
      // Nothing was presented. On a public box the bottom rung is still open, because the same
      // bytes are already served to anyone over HTTP; anything above it needs a real credential.
      return if (isPublic && required == AgentGrantScope.PREVIEW) {
        Decision.Authorized("anonymous")
      } else {
        Decision.Missing
      }
    }
    val allowed =
      when (required) {
        AgentGrantScope.PREVIEW,
        AgentGrantScope.LIVE -> true
        AgentGrantScope.PLAYGROUND -> githubAuth.hasRepositoryAccess(call)
      }
    return if (allowed) Decision.Authorized(ServeAgentGrants.githubActorId(login))
    else Decision.Forbidden("Repository access is required for '${required.wire}'.")
  }

  fun authorizeCapability(
    call: ApplicationCall,
    required: AgentGrantCapability,
    presentedToken: String? = null,
  ): Decision {
    if (hasOperatorToken(call)) return Decision.Authorized(OPERATOR_ACTOR_ID)

    val login = githubAuth?.currentLogin(call)
    if (login != null && githubAuth.hasRepositoryAccess(call)) {
      return Decision.Authorized(ServeAgentGrants.githubActorId(login))
    }

    presentedGrant(call, presentedToken)?.let { grant ->
      return if (grant.allows(required)) {
        grant.authorized()
      } else {
        Decision.Forbidden("This agent grant does not include '${required.wire}'.")
      }
    }
    return Decision.Missing
  }

  /**
   * The grant behind this call, if any.
   *
   * [presentedToken] is a credential that arrived in the request *body* rather than on the call.
   * Only the MCP lane has one ([ServeCatalogMcp.TOKEN_ARGUMENT]), and only because an MCP client
   * fixes its headers when it connects: an agent handed a token by `poll_access` mid-session has no
   * other way to use it. It is looked up in the same store as every other token and is tried LAST,
   * so a call that already carries a credential keeps it — a body cannot quietly re-identify a
   * request that the transport already spoke for.
   */
  fun presentedGrant(
    call: ApplicationCall,
    presentedToken: String? = null,
  ): ServeAgentGrantStore.Grant? {
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
        presentedToken,
      )
      .firstNotNullOfOrNull(store::grantForToken)
  }

  /**
   * The long-lived operator token, and only ever off the call.
   *
   * Deliberately not read from a request body: the in-band door exists for a short-lived grant a
   * human just approved, and widening it to the box's own standing credential would let one leak
   * into a place it was never meant to travel — a tool argument, in a transcript, chosen by a
   * model. An operator who can set a header has no need of it.
   */
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

    const val OPERATOR_ACTOR_ID = ServeAgentGrants.OPERATOR_ACTOR_ID

    /**
     * The agent's own identity, and the approver it acts for.
     *
     * The delegation is dropped when the store never recorded an approver — a grant restored from
     * an older process, or minted by a test — because an actor that claims to act for nobody must
     * not silently become that nobody.
     */
    fun ServeAgentGrantStore.Grant.authorized(): Decision.Authorized =
      Decision.Authorized(
        actorId = ServeAgentGrants.agentActorId(fingerprint),
        onBehalfOfActorId = approvedByActorId.takeIf { it.isNotBlank() },
      )
  }
}
