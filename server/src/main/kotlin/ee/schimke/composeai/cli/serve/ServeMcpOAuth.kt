package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The OAuth 2.1 façade an MCP client speaks, layered over the agent-grant flow in
 * [ServeAgentGrants].
 *
 * ## Why this exists beside a device grant that already works
 *
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md)
 * picked RFC 8628 for the right reason — the party needing the credential cannot render an approval
 * page — and the flow is sound. What it is not is *discoverable*. An MCP client that meets a `401`
 * has one script: read `WWW-Authenticate` for `resource_metadata`, fetch
 * [RFC 9728](https://datatracker.ietf.org/doc/html/rfc9728) protected-resource metadata, fetch
 * [RFC 8414](https://datatracker.ietf.org/doc/html/rfc8414) authorization-server metadata, register
 * itself with [RFC 7591](https://datatracker.ietf.org/doc/html/rfc7591), then run an authorization
 * code + PKCE exchange. It cannot be told in prose to POST `/agent-access/request` instead, because
 * nothing in the protocol gives an agent a place to read prose.
 *
 * So a client hitting this server got as far as the guess it makes when discovery 404s — an
 * unprompted registration attempt — and reported `Dynamic Client Registration rejected (HTTP 404)`.
 * The bespoke flow was reachable only by a human relaying `approveUrl` and a token by hand.
 *
 * ## What is actually new
 *
 * Very little, deliberately. This is an **adapter**, not a second authorization system:
 *
 * * `/oauth/authorize` opens an ordinary [ServeAgentGrantStore] request and redirects the browser
 *   to the approval page that already exists, so the human sees the same page, the same scope
 *   ladder, the same approver ceilings and the same audit line as before.
 * * The approval itself is unchanged — [ServeHttpServer.handleAgentGrantDecision] mints the grant.
 *   All this adds is a return leg: a pending authorization bound to that request id turns the
 *   decision page into a redirect back to the client.
 * * `/oauth/token` hands back **the grant's own token**, the same `cpat_…` string the device poll
 *   returns, which every gate on this server already accepts as `Authorization: Bearer`.
 *
 * There is no new credential type, no new lifetime, no new revocation path, and nothing here can
 * grant what an approver could not grant on the page.
 *
 * ## What is not implemented, and why that is allowed
 *
 * No refresh tokens: a grant is short-lived by design and re-authorization is a human decision, so
 * a silent renewal would launder exactly the property the grants doc is built around. No client
 * secrets: every MCP client here is a public client, so [RFC 7591] registration returns an id only
 * and the token endpoint is `none`-authenticated with PKCE carrying the proof — `S256` only, never
 * `plain`.
 */
object ServeMcpOAuth {

  /** Every route below this prefix; a constant first segment, so it outscores `/{system}`. */
  const val BASE_PATH = "/oauth"

  const val AUTHORIZE_PATH = "$BASE_PATH/authorize"
  const val TOKEN_PATH = "$BASE_PATH/token"
  const val REGISTER_PATH = "$BASE_PATH/register"

  /**
   * RFC 9728 §3. The suffixed form is the one a spec-following client builds for a resource at
   * `/mcp`; the bare form is what a client that ignores the path component asks for. Both are
   * served, because getting this wrong is invisible — the client simply gives up and reports a
   * registration failure with no hint that discovery is what actually failed.
   */
  const val PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource"

  const val PROTECTED_RESOURCE_METADATA_MCP_PATH = "/.well-known/oauth-protected-resource/mcp"

  /** RFC 8414 §3, with the same two-form reasoning as the resource metadata above. */
  const val AUTHORIZATION_SERVER_METADATA_PATH = "/.well-known/oauth-authorization-server"

  const val AUTHORIZATION_SERVER_METADATA_MCP_PATH = "/.well-known/oauth-authorization-server/mcp"

  /**
   * OpenID Connect discovery. Not because anything here speaks OIDC, but because several clients
   * probe it before the RFC 8414 path and treat a 404 as "no authorization server".
   */
  const val OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration"

  /** The MCP resource these metadata documents describe. */
  const val MCP_RESOURCE_PATH = "/mcp"

  /**
   * How long an issued code may sit unredeemed. RFC 6749 §4.1.2 says a code SHOULD be short-lived
   * and recommends a maximum of ten minutes; the exchange here happens on the client's own redirect
   * handler within a second or two, so a minute is generous and bounds the map.
   */
  const val CODE_TTL_SECONDS = 60L

  /**
   * How long an authorization may wait for the human between `/oauth/authorize` and the decision.
   * Matched to the grant request's own window rather than the code's — the person has to read the
   * page, and the request they are reading expires on the store's schedule regardless.
   */
  const val AUTHORIZATION_TTL_SECONDS = 600L

  /** Bound on both maps. Anonymous callers drive registration and authorization alike. */
  const val MAX_PENDING_AUTHORIZATIONS = 256

  const val MAX_REGISTERED_CLIENTS = 256

  /** The only challenge method accepted. `plain` proves nothing and OAuth 2.1 removes it. */
  const val CODE_CHALLENGE_S256 = "S256"

  // ------------------------------------------------------------------- wire

  /** RFC 9728 §2 protected-resource metadata. */
  @Serializable
  data class ProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers") val authorizationServers: List<String>,
    @SerialName("scopes_supported") val scopesSupported: List<String>,
    @SerialName("bearer_methods_supported")
    val bearerMethodsSupported: List<String> = listOf("header"),
    @SerialName("resource_documentation") val resourceDocumentation: String? = null,
  )

  /** RFC 8414 §2 authorization-server metadata, trimmed to what this server actually does. */
  @Serializable
  data class AuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("registration_endpoint") val registrationEndpoint: String,
    @SerialName("scopes_supported") val scopesSupported: List<String>,
    @SerialName("response_types_supported")
    val responseTypesSupported: List<String> = listOf("code"),
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String> = listOf("authorization_code"),
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String> = listOf(CODE_CHALLENGE_S256),
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String> = listOf("none"),
    /**
     * RFC 8707. Advertised because the MCP authorization spec requires clients to bind a token to
     * the resource they mean to call, and a client that sees this will send `resource=` on both
     * legs.
     */
    @SerialName("resource_indicators_supported") val resourceIndicatorsSupported: Boolean = true,
  )

  /** RFC 7591 §2 registration request. Every field optional; unknown members are ignored. */
  @Serializable
  data class ClientRegistrationRequest(
    @SerialName("redirect_uris") val redirectUris: List<String> = emptyList(),
    @SerialName("client_name") val clientName: String = "",
    @SerialName("grant_types") val grantTypes: List<String> = emptyList(),
    @SerialName("response_types") val responseTypes: List<String> = emptyList(),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "",
    val scope: String = "",
  )

  /** RFC 7591 §3.2.1 registration response. No secret: these are public clients. */
  @Serializable
  data class ClientRegistrationResponse(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_id_issued_at") val clientIdIssuedAt: Long,
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("client_name") val clientName: String,
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
  )

  /** RFC 6749 §5.1 token response. */
  @Serializable
  data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_in") val expiresIn: Long,
    val scope: String,
  )

  /** RFC 6749 §5.2 / RFC 7591 §3.2.2 error body. */
  @Serializable
  data class ErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String,
  )

  // ---------------------------------------------------------------- clients

  /** A client that registered itself. Public, so the id is a handle rather than a credential. */
  data class RegisteredClient(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String>,
    val issuedAtMillis: Long,
  )

  /**
   * An authorization waiting on a human, bound to the grant request whose approval page they were
   * sent to.
   *
   * [code] is minted at [AUTHORIZE_PATH] time rather than at decision time, so the decision handler
   * — which knows only a request id — can complete the redirect without reaching back in here for a
   * mutation. It is worthless until the grant behind [requestId] exists, which is the property that
   * makes issuing it early safe: the token endpoint resolves the grant, and an unapproved or denied
   * request has none.
   */
  data class PendingAuthorization(
    val code: String,
    val requestId: String,
    val clientId: String,
    val redirectUri: String,
    val codeChallenge: String,
    val state: String,
    val resource: String,
    val createdAtMillis: Long,
  ) {
    fun isExpired(nowMillis: Long): Boolean =
      nowMillis - createdAtMillis > AUTHORIZATION_TTL_SECONDS * 1000
  }

  // ----------------------------------------------------------------- store

  /**
   * The two maps this façade owns, and nothing else. Grants, scopes, ceilings and revocation all
   * stay in [ServeAgentGrantStore]; what lives here is the correspondence between an OAuth exchange
   * and a grant request, which is meaningless outside the exchange and dies with it.
   *
   * Deliberately in memory, like the grant store itself: a restart drops every grant, so an
   * authorization that outlived one would redeem to a token that no longer exists. Losing both
   * together is the honest behaviour, and the client's answer is the same either way — start over.
   */
  class Store(private val clock: () -> Long = System::currentTimeMillis) {

    private val clients = ConcurrentHashMap<String, RegisteredClient>()
    private val pending = ConcurrentHashMap<String, PendingAuthorization>()
    /** Request id → code, so the decision handler can find the return leg by what it holds. */
    private val byRequest = ConcurrentHashMap<String, String>()

    fun register(name: String, redirectUris: List<String>): RegisteredClient? {
      purge()
      if (clients.size >= MAX_REGISTERED_CLIENTS) return null
      val client =
        RegisteredClient(
          clientId = randomId(),
          clientName = ServeAgentGrantStore.sanitizeLabel(name),
          redirectUris = redirectUris,
          issuedAtMillis = clock(),
        )
      clients[client.clientId] = client
      return client
    }

    fun client(clientId: String?): RegisteredClient? = clientId?.let { clients[it] }

    fun open(
      requestId: String,
      clientId: String,
      redirectUri: String,
      codeChallenge: String,
      state: String,
      resource: String,
    ): PendingAuthorization? {
      purge()
      if (pending.size >= MAX_PENDING_AUTHORIZATIONS) return null
      val authorization =
        PendingAuthorization(
          code = randomId(),
          requestId = requestId,
          clientId = clientId,
          redirectUri = redirectUri,
          codeChallenge = codeChallenge,
          state = state,
          resource = resource,
          createdAtMillis = clock(),
        )
      pending[authorization.code] = authorization
      byRequest[requestId] = authorization.code
      return authorization
    }

    /** The return leg for a grant request, if that request came in through this façade. */
    fun forRequest(requestId: String?): PendingAuthorization? {
      val code = requestId?.let { byRequest[it] } ?: return null
      return pending[code]?.takeIf { !it.isExpired(clock()) }
    }

    /**
     * Redeem [code] once. Removal happens before any check so that a replay — the case RFC 6749
     * §10.5 calls out — cannot find the entry a second time even if the first attempt is still in
     * flight or failed its PKCE check.
     */
    fun redeem(code: String?): PendingAuthorization? {
      val key = code ?: return null
      val authorization = pending.remove(key) ?: return null
      byRequest.remove(authorization.requestId, key)
      val now = clock()
      // The code's own short window, not the authorization's: the human may have taken ten minutes
      // to decide, but the client redeems on its redirect handler immediately afterwards.
      return authorization.takeIf { now - it.createdAtMillis <= AUTHORIZATION_TTL_SECONDS * 1000 }
    }

    fun purge() {
      val now = clock()
      pending.entries.removeIf { (code, authorization) ->
        authorization.isExpired(now).also {
          if (it) byRequest.remove(authorization.requestId, code)
        }
      }
    }

    fun clear() {
      clients.clear()
      pending.clear()
      byRequest.clear()
    }

    fun pendingCount(): Int = pending.size

    fun clientCount(): Int = clients.size
  }

  // ------------------------------------------------------------- decisions

  /** What [AUTHORIZE_PATH] refuses before it has anywhere safe to redirect an error to. */
  sealed interface AuthorizeRejection {
    /**
     * The redirect target itself is untrustworthy, so the error must be **rendered**, never
     * redirected. RFC 6749 §4.1.2.1 is explicit about this: bouncing an error to an unvalidated URI
     * is an open redirector.
     */
    data class Unredirectable(val error: String, val description: String) : AuthorizeRejection

    /** Safe to hand back to the client on its own registered redirect URI. */
    data class Redirectable(val error: String, val description: String) : AuthorizeRejection
  }

  /**
   * Validate an `/oauth/authorize` query. Split out from the route so the precedence — which
   * failures may be redirected and which may not — is testable without Ktor, because that
   * precedence is the security-relevant part and it is easy to get subtly wrong.
   */
  fun validateAuthorize(
    client: RegisteredClient?,
    redirectUri: String?,
    responseType: String?,
    codeChallenge: String?,
    codeChallengeMethod: String?,
  ): AuthorizeRejection? {
    if (client == null) {
      return AuthorizeRejection.Unredirectable(
        "invalid_client",
        "Unknown client_id. Register at $REGISTER_PATH first; a server restart drops every " +
          "registration, so re-register rather than reusing an id from a previous run.",
      )
    }
    if (redirectUri.isNullOrBlank()) {
      return AuthorizeRejection.Unredirectable(
        "invalid_request",
        "redirect_uri is required.",
      )
    }
    if (!isRegisteredRedirect(client, redirectUri)) {
      return AuthorizeRejection.Unredirectable(
        "invalid_request",
        "redirect_uri does not exactly match one registered by this client.",
      )
    }
    // Everything past here has a validated place to go, so the client learns why rather than
    // staring at a page it cannot read.
    if (responseType != "code") {
      return AuthorizeRejection.Redirectable(
        "unsupported_response_type",
        "Only response_type=code is supported.",
      )
    }
    if (codeChallenge.isNullOrBlank()) {
      return AuthorizeRejection.Redirectable(
        "invalid_request",
        "code_challenge is required; this server does not accept an authorization request " +
          "without PKCE.",
      )
    }
    if (codeChallengeMethod != CODE_CHALLENGE_S256) {
      return AuthorizeRejection.Redirectable(
        "invalid_request",
        "code_challenge_method must be $CODE_CHALLENGE_S256; 'plain' proves nothing and is " +
          "refused.",
      )
    }
    return null
  }

  /**
   * Exact string match against the registered set, which is what RFC 7591 registration is for.
   *
   * No prefix or wildcard matching, and none of the "same origin is close enough" shortcuts:
   * loopback clients register the port they actually listen on, and an agent that re-registers per
   * run pays nothing for it. The one concession RFC 8252 §7.3 asks for — a loopback redirect whose
   * port is chosen at bind time — is handled by ignoring the port on `127.0.0.1` and `[::1]`
   * **only**, never on a named host.
   */
  fun isRegisteredRedirect(client: RegisteredClient, redirectUri: String): Boolean =
    client.redirectUris.any { registered ->
      registered == redirectUri || loopbackMatch(registered, redirectUri)
    }

  private fun loopbackMatch(registered: String, presented: String): Boolean {
    val a = runCatching { URI(registered) }.getOrNull() ?: return false
    val b = runCatching { URI(presented) }.getOrNull() ?: return false
    if (!isLoopbackHost(a.host) || !isLoopbackHost(b.host)) return false
    return a.scheme == b.scheme && a.host == b.host && a.path == b.path
  }

  private fun isLoopbackHost(host: String?): Boolean =
    host == "127.0.0.1" || host == "::1" || host == "[::1]"

  /**
   * RFC 7636 §4.6: the challenge is the base64url-of-SHA256 of the verifier, unpadded. Compared in
   * constant time — this is the only thing standing between a stolen code and a token.
   */
  fun verifyPkce(codeChallenge: String, codeVerifier: String?): Boolean {
    if (codeVerifier.isNullOrBlank()) return false
    // RFC 7636 §4.1 bounds the verifier; a caller outside them is malformed rather than merely
    // wrong, and hashing an unbounded string on an anonymous endpoint is not free.
    if (codeVerifier.length !in 43..128) return false
    val digest =
      MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
    val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return MessageDigest.isEqual(
      expected.toByteArray(Charsets.US_ASCII),
      codeChallenge.toByteArray(Charsets.US_ASCII),
    )
  }

  /**
   * Build the client's redirect. [state] is echoed verbatim when present and omitted when not — RFC
   * 6749 §4.1.2 requires exactly that, and a client relying on `state` for its own CSRF check will
   * reject a response that dropped it.
   */
  fun redirectWithCode(redirectUri: String, code: String, state: String): String =
    appendQuery(
      redirectUri,
      buildList {
        add("code" to code)
        if (state.isNotEmpty()) add("state" to state)
      },
    )

  fun redirectWithError(
    redirectUri: String,
    error: String,
    description: String,
    state: String,
  ): String =
    appendQuery(
      redirectUri,
      buildList {
        add("error" to error)
        add("error_description" to description)
        if (state.isNotEmpty()) add("state" to state)
      },
    )

  private fun appendQuery(uri: String, params: List<Pair<String, String>>): String {
    val encoded = params.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
    val separator = if (uri.contains('?')) "&" else "?"
    return uri + separator + encoded
  }

  private fun urlEncode(raw: String): String =
    java.net.URLEncoder.encode(raw, Charsets.UTF_8.name())

  /**
   * The `scope` an OAuth client asks for, mapped onto this server's ladder and capability set.
   *
   * One flat space, because OAuth has one: the ladder rungs keep their wire names (`preview`,
   * `live`, `playground`) and each capability keeps its own (`ui-builder-write`, …). A client that
   * sends no scope at all gets [AgentGrantScope.DEFAULT_REQUEST] and no capabilities, which is the
   * same floor `POST /agent-access/request` with an empty body lands on.
   */
  data class RequestedAccess(
    val scope: AgentGrantScope,
    val capabilities: Set<AgentGrantCapability>,
  )

  fun parseScope(raw: String?): RequestedAccess {
    val tokens = raw.orEmpty().split(' ', '\t', '\n').filter { it.isNotBlank() }
    val scope = tokens.mapNotNull { AgentGrantScope.parse(it) }.maxOrNull()
    val capabilities = tokens.mapNotNull { AgentGrantCapability.parse(it) }.toSet()
    return RequestedAccess(scope ?: AgentGrantScope.DEFAULT_REQUEST, capabilities)
  }

  /** What a minted grant is described as in the token response, in the same flat space. */
  fun formatScope(grant: ServeAgentGrantStore.Grant): String =
    (grant.scopes.map { it.wire } + AgentGrantCapability.wireNames(grant.capabilities))
      .joinToString(" ")

  fun scopesSupported(
    maxScope: AgentGrantScope,
    maxCapabilities: Set<AgentGrantCapability>,
  ): List<String> =
    AgentGrantScope.upTo(maxScope).map { it.wire } + AgentGrantCapability.wireNames(maxCapabilities)

  /**
   * The `WWW-Authenticate` value a `401` from the MCP resource carries.
   *
   * The `resource_metadata` parameter is the entire point of this file: it is the only thing in the
   * whole exchange that tells a client where discovery starts. Without it a client guesses, and
   * guessing is what produced the "Dynamic Client Registration rejected (HTTP 404)" this replaces.
   */
  fun challenge(
    externalOrigin: String,
    error: String? = null,
    description: String? = null,
  ): String = buildString {
    append("Bearer realm=\"compose-preview-catalog-mcp\"")
    append(", resource_metadata=\"")
    append(externalOrigin)
    append(PROTECTED_RESOURCE_METADATA_MCP_PATH)
    append('"')
    if (error != null) append(", error=\"$error\"")
    if (description != null) append(", error_description=\"${description.replace('"', '\'')}\"")
  }

  // ----------------------------------------------------------------- randoms

  private val random = SecureRandom()

  private fun randomId(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
