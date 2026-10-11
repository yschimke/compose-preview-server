package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/**
 * The OAuth 2.1 façade an MCP client speaks, layered over the agent-grant flow in
 * [ServeAgentGrants]. MCP clients discover auth only through RFC 9728 / RFC 8414 metadata, RFC 7591
 * registration and an authorization-code + PKCE exchange, so the device grant alone was
 * undiscoverable to them.
 *
 * An **adapter**, not a second authorization system:
 * * `/oauth/authorize` opens an ordinary [ServeAgentGrantStore] request and redirects to the
 *   existing approval page (same scopes, ceilings and audit);
 * * [ServeHttpServer.handleAgentGrantDecision] still mints the grant; a pending authorization only
 *   adds the redirect back to the client;
 * * `/oauth/token` returns the grant's own `cpat_…` token.
 *
 * Refresh tokens are session-scoped: they renew within the lifetime the approver chose and die with
 * the grant; they rotate on every use (RFC 9700 §4.14.2). Every client is public: registration
 * returns an id only, and PKCE is `S256` only.
 */
object ServeMcpOAuth {

  /** Every route below this prefix; a constant first segment, so it outscores `/{system}`. */
  const val BASE_PATH = "/oauth"

  const val AUTHORIZE_PATH = "$BASE_PATH/authorize"
  const val TOKEN_PATH = "$BASE_PATH/token"
  const val REGISTER_PATH = "$BASE_PATH/register"

  /**
   * RFC 9728 §3. The suffixed form is what a spec-following client builds for `/mcp`; the bare form
   * is what a path-ignoring client asks for. Both are served because a discovery miss surfaces only
   * as an unexplained registration failure.
   */
  const val PROTECTED_RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource"

  const val PROTECTED_RESOURCE_METADATA_MCP_PATH = "/.well-known/oauth-protected-resource/mcp"

  /** RFC 8414 §3, with the same two-form reasoning as the resource metadata above. */
  const val AUTHORIZATION_SERVER_METADATA_PATH = "/.well-known/oauth-authorization-server"

  const val AUTHORIZATION_SERVER_METADATA_MCP_PATH = "/.well-known/oauth-authorization-server/mcp"

  /**
   * Not OIDC: several clients probe this before the RFC 8414 path and treat a 404 as "no
   * authorization server".
   */
  const val OPENID_CONFIGURATION_PATH = "/.well-known/openid-configuration"

  /** The MCP resource these metadata documents describe. */
  const val MCP_RESOURCE_PATH = "/mcp"

  /**
   * How long an authorization lives, from `/oauth/authorize` to the redeemed code.
   *
   * One window covers both legs because the code is minted before the human decides, so a tighter
   * bound would expire on their deliberation. That is sound: RFC 6749 §4.1.2's short-lived code
   * guards disclosure, and this code isn't disclosed until the post-approval redirect. Matches the
   * approval page ([ServeAgentGrantStore.DEFAULT_REQUEST_TTL_SECONDS]) since ten minutes expired
   * sign-ins with a GitHub 2FA round trip; single use is enforced in [Store.redeem] and PKCE makes
   * a stolen code inert.
   */
  const val AUTHORIZATION_TTL_SECONDS = ServeAgentGrantStore.DEFAULT_REQUEST_TTL_SECONDS

  /**
   * Idle lifetime of a public client registration, independent of approval and token lifetimes.
   * Successful client lookups renew it. Anonymous registration is bounded, so unused clients must
   * eventually release their slots; one-day exchange expiry broke clients that cache their IDs.
   */
  const val CLIENT_TTL_SECONDS = 30 * 86_400L

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
    val grantTypesSupported: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String> = listOf(CODE_CHALLENGE_S256),
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String> = listOf("none"),
    /**
     * RFC 8707. Advertised because the MCP authorization spec requires clients to bind a token to
     * its resource; a client seeing this sends `resource=` on both legs.
     */
    @SerialName("resource_indicators_supported") val resourceIndicatorsSupported: Boolean = true,
  )

  /**
   * RFC 7591 §2 registration request. Every field optional; unknown members are ignored, as §2
   * requires (real clients send e.g. `application_type: "native"`, which would otherwise be
   * refused).
   */
  @OptIn(ExperimentalSerializationApi::class)
  @Serializable
  @JsonIgnoreUnknownKeys
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
    /**
     * Session-scoped: renews an access token only within the lifetime the approver chose, and dies
     * with the grant (deadline or revocation). It recovers a lost or rotated token without
     * interrupting a person; it never extends a decision ([docs/design/AGENT_ACCESS_GRANTS.md]).
     */
    @SerialName("refresh_token") val refreshToken: String? = null,
  )

  /** RFC 6749 §5.2 / RFC 7591 §3.2.2 error body. */
  @Serializable
  data class ErrorResponse(
    val error: String,
    @SerialName("error_description") val errorDescription: String,
  )

  // ---------------------------------------------------------------- clients

  /** A client that registered itself. Public, so the id is a handle rather than a credential. */
  @Serializable
  data class RegisteredClient(
    val clientId: String,
    val clientName: String,
    val redirectUris: List<String>,
    val issuedAtMillis: Long,
    val lastUsedAtMillis: Long = issuedAtMillis,
  ) {
    fun isExpired(nowMillis: Long): Boolean =
      nowMillis - lastUsedAtMillis > CLIENT_TTL_SECONDS * 1000
  }

  /**
   * An authorization waiting on a human, bound to its grant request. [code] is minted at
   * [AUTHORIZE_PATH] time so the decision handler can redirect without mutating this store; it is
   * worthless until the grant behind [requestId] exists.
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

  /**
   * A refresh token bound to its grant and client. No expiry: [ServeAgentGrantStore] is asked on
   * every use whether the grant is live, so revocation takes refresh tokens with it.
   */
  data class RefreshBinding(val token: String, val grantId: String, val clientId: String)

  /** Bound on the refresh map, like the other two. */
  const val MAX_REFRESH_TOKENS = 512

  // ----------------------------------------------------------------- store

  /**
   * Public client metadata survives restarts when a registry file is configured. Authorization
   * codes and refresh bindings stay in memory: the grants they refer to do not survive a restart.
   */
  class Store(
    registeredClientsFile: Path? = null,
    private val clock: () -> Long = System::currentTimeMillis,
  ) {

    private val clients = ServeMcpOAuthClients(registeredClientsFile, clock)
    private val refreshTokens = ConcurrentHashMap<String, RefreshBinding>()
    private val pending = ConcurrentHashMap<String, PendingAuthorization>()
    /** Request id → code, so the decision handler can find the return leg by what it holds. */
    private val byRequest = ConcurrentHashMap<String, String>()

    fun register(name: String, redirectUris: List<String>): RegisteredClient? {
      purge()
      return clients.register(name, redirectUris)
    }

    fun client(clientId: String?): RegisteredClient? = clients.client(clientId)

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
     * Redeem [code] once. Removal happens before any check so a replay (RFC 6749 §10.5) can't find
     * the entry even if the first attempt failed PKCE.
     */
    fun redeem(code: String?): PendingAuthorization? {
      val key = code ?: return null
      val authorization = pending.remove(key) ?: return null
      byRequest.remove(authorization.requestId, key)
      val now = clock()
      // One window from authorize; see AUTHORIZATION_TTL_SECONDS.
      return authorization.takeIf { !it.isExpired(now) }
    }

    fun purge() {
      val now = clock()
      pending.entries.removeIf { (code, authorization) ->
        authorization.isExpired(now).also {
          if (it) byRequest.remove(authorization.requestId, code)
        }
      }
      // Sweeping this map is what keeps MAX_REGISTERED_CLIENTS a bound on concurrent use rather
      // than a lifetime quota; see CLIENT_TTL_SECONDS.
      clients.purge()
    }

    /**
     * Mint a refresh token for [grantId], on each code exchange and rotation. Bindings whose grant
     * [isLive] denies are dropped first, so they don't hold [MAX_REFRESH_TOKENS] slots.
     */
    fun issueRefresh(
      grantId: String,
      clientId: String,
      isLive: (grantId: String) -> Boolean = { true },
    ): String? {
      forgetRefreshUnless(isLive)
      if (refreshTokens.size >= MAX_REFRESH_TOKENS) return null
      val binding = RefreshBinding(randomId(), grantId, clientId)
      refreshTokens[binding.token] = binding
      return binding.token
    }

    /**
     * Consume [token] and return its binding. Rotated: the token is removed whatever happens next,
     * so a replay finds nothing ([RFC 9700](https://datatracker.ietf.org/doc/html/rfc9700) §4.14.2
     * for public clients).
     */
    fun redeemRefresh(token: String?, clientId: String?): RefreshBinding? {
      val key = token ?: return null
      val binding = refreshTokens.remove(key) ?: return null
      return binding.takeIf { it.clientId == clientId }
    }

    /** Drop every refresh token bound to [grantId] — used when its grant is gone. */
    fun forgetRefreshFor(grantId: String) {
      refreshTokens.entries.removeIf { (_, binding) -> binding.grantId == grantId }
    }

    /** Drop every refresh token whose grant [isLive] no longer recognises. */
    fun forgetRefreshUnless(isLive: (grantId: String) -> Boolean) {
      val dead = refreshTokens.values.map { it.grantId }.distinct().filterNot(isLive).toSet()
      if (dead.isNotEmpty())
        refreshTokens.entries.removeIf { (_, binding) -> binding.grantId in dead }
    }

    fun refreshCount(): Int = refreshTokens.size

    fun clear() {
      clients.clear()
      pending.clear()
      byRequest.clear()
      refreshTokens.clear()
    }

    fun pendingCount(): Int = pending.size

    fun clientCount(): Int = clients.count()
  }

  // ------------------------------------------------------------- decisions

  /** What [AUTHORIZE_PATH] refuses before it has anywhere safe to redirect an error to. */
  sealed interface AuthorizeRejection {
    /**
     * The redirect target is untrustworthy, so the error must be rendered, never redirected (RFC
     * 6749 §4.1.2.1: otherwise an open redirector).
     */
    data class Unredirectable(val error: String, val description: String) : AuthorizeRejection

    /** Safe to hand back to the client on its own registered redirect URI. */
    data class Redirectable(val error: String, val description: String) : AuthorizeRejection
  }

  /**
   * Validate an `/oauth/authorize` query. Split out so the security-relevant precedence (which
   * failures may be redirected) is testable without Ktor.
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
        "Unknown or expired client_id. Register at $REGISTER_PATH first. Disconnect or remove " +
          "the MCP app, then reconnect it to register a new client; retrying this old id cannot work.",
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
   * Exact string match against the registered set: no prefix, wildcard or same-origin matching. The
   * only concession, per RFC 8252 §7.3, ignores the port on `127.0.0.1` and `[::1]` only, never a
   * named host.
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
   * Where an approval sends the browser — and so the authorization code — as the approval page
   * shows it. [display] is the host (with a non-default port), or `scheme://` for a private-use
   * scheme that has no host.
   */
  data class RedirectTarget(val display: String, val kind: Kind, val uri: String) {
    enum class Kind {
      /** `127.0.0.1`, `[::1]` or `localhost`: a program on the approver's own machine. */
      LOOPBACK,
      /** A non-http(s) scheme (`cursor://…`): whatever app on this device registered it. */
      APP,
      /** Any other host: a site elsewhere on the network. */
      EXTERNAL,
    }
  }

  /** Classify a registered redirect URI for the approval page. Never throws. */
  fun describeRedirect(redirectUri: String): RedirectTarget {
    val uri = runCatching { URI(redirectUri) }.getOrNull()
    val scheme = (uri?.scheme ?: redirectUri.substringBefore(':', "")).lowercase()
    val host = uri?.host?.takeIf { it.isNotBlank() }
    val fetchedFromHost = scheme == "http" || scheme == "https" || scheme in NETWORK_SCHEMES
    if (host == null || !fetchedFromHost) {
      // A private-use scheme is opened by whichever app registered it, not fetched from a host.
      return RedirectTarget(
        display =
          when {
            scheme.isBlank() -> redirectUri
            host == null -> "$scheme://"
            else -> "$scheme://${host.lowercase()}"
          },
        kind = RedirectTarget.Kind.APP,
        uri = redirectUri,
      )
    }
    val lower = host.lowercase()
    val kind =
      if (isLoopbackHost(lower) || lower == "localhost") RedirectTarget.Kind.LOOPBACK
      else RedirectTarget.Kind.EXTERNAL
    val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
    val prefix = if (scheme == "http" || scheme == "https") "" else "$scheme://"
    return RedirectTarget(display = prefix + lower + port, kind = kind, uri = redirectUri)
  }

  /**
   * Non-http(s) schemes that still name a host on the network, so a redirect to one is shown as a
   * site rather than as an app on this device. Custom app schemes (`vscode://`, `cursor://…`) are
   * not on this list and stay [RedirectTarget.Kind.APP].
   */
  private val NETWORK_SCHEMES =
    setOf("ftp", "ftps", "sftp", "ws", "wss", "file", "gopher", "telnet", "ldap", "ldaps", "smb")

  /**
   * RFC 7636 §4.6: the challenge is unpadded base64url SHA-256 of the verifier. Compared in
   * constant time; this is all that stands between a stolen code and a token.
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
   * Build the client's redirect. [state] is echoed verbatim when present and omitted when not (RFC
   * 6749 §4.1.2); clients use it for CSRF checks.
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
   * The OAuth `scope` mapped onto this server's ladder and capabilities, in one flat space: ladder
   * rungs (`preview`, `live`, `playground`) and capability names (`ui-builder-write`, …). No scope
   * yields [AgentGrantScope.DEFAULT_REQUEST] and no capabilities, like an empty `POST
   * /agent-access/request`.
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
   * The `WWW-Authenticate` value on a `401` from the MCP resource. Its `resource_metadata`
   * parameter is the only thing telling a client where discovery starts.
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

  internal fun randomId(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
}
