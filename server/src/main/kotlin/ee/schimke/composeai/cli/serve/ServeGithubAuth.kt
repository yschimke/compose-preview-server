package ee.schimke.composeai.cli.serve

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.host
import io.ktor.server.request.uri
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class ServeGithubAuthConfig(
  val clientId: String,
  val clientSecret: String,
  val cookieSecret: String,
  val repository: String,
  /**
   * A second repository whose access the session also records, for a lane gated elsewhere (today
   * the image lane's `--image-upload-repo`). The visitor's token isn't retained, so any bit not
   * computed at sign-in can never be recovered; computing it in the same round trip is what lets
   * the two gates differ. Null or equal to [repository] means no extra call.
   *
   * Never widens consent: the token only carries [ServeGithubAuth.USER_SCOPE], so a private
   * repository here reads as no access ([ServeGithubAuth.requestedScope]).
   */
  val imageRepository: String? = null,
  val allowedUsers: Set<String> = emptySet(),
  /**
   * `--github-auth-orgs`: organizations whose members are admitted as members, as if named in
   * [allowedUsers]. Read once at sign-in and baked into the session, so someone leaving the org
   * keeps access until the absolute cap. Requires `read:org` (added by
   * [ServeGithubAuth.requestedScope]); private memberships are visible only if the org allows this
   * OAuth app ([GitHubOAuthVerifier.isOrgMember]).
   */
  val allowedOrgs: Set<String> = emptySet(),
  /**
   * `--github-auth-guests`: admit accounts outside [allowedUsers] as guests.
   * [ServeGithubAuth.currentLogin] answers null for a guest, so every existing gate treats it as
   * anonymous; only the UI builder reads its identity ([ServeGithubAuth.currentSignedInLogin]) to
   * show designs shared with it, read-only. No repository access is looked up for a guest.
   */
  val allowGuests: Boolean = false,
  /**
   * `--github-auth-open-ui-builder`: every signed-in member may create, edit and export UI-builder
   * designs (and grant those to agents) without write access to [repository]. Opens only the
   * builder; the playground and image uploads still check [repository], and each design's sharing
   * still applies. Guests stay read-only. Decided per request, so turning it off is immediate.
   */
  val openUiBuilder: Boolean = false,
  val callbackBaseUrl: String? = null,
  /**
   * Domain the auth cookies are scoped to, so one sign-in covers a parent host and every site host
   * under it. Needed for a pinned callback to work from a site host: host-only `cp_gh_state`
   * wouldn't reach the callback origin, failing CSRF.
   *
   * Null keeps cookies host-only, the only safe default: it must be the operator's explicit choice,
   * never derived from `Host`. Every host under this domain is inside the session's blast radius;
   * public suffixes are refused.
   */
  val cookieDomain: String? = null,
  /**
   * Overrides the OAuth scope; null asks for [ServeGithubAuth.USER_SCOPE]
   * ([ServeGithubAuth.requestedScope]). Only read-only identity scopes in
   * [ServeGithubAuth.ALLOWED_SCOPES] are accepted; repository or write scopes are refused at
   * startup.
   */
  val oauthScope: String? = null,
  /**
   * Read host and scheme from `X-Forwarded-Host` / `-Proto` (from `--trust-forwarded-for`). Off,
   * forwarded headers are ignored; [callbackBaseUrl] still decides the public origin when set.
   */
  val trustForwardedHeaders: Boolean = false,
) {
  init {
    require(clientId.isNotBlank()) { "GitHub OAuth client id is required" }
    require(clientSecret.isNotBlank()) { "GitHub OAuth client secret is required" }
    require(cookieSecret.length >= MIN_COOKIE_SECRET_CHARS) {
      "GitHub auth cookie secret must be at least $MIN_COOKIE_SECRET_CHARS characters"
    }
    require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
      "GitHub auth repository must be owner/repo"
    }
    val unsupportedScopes =
      oauthScope.orEmpty().split(' ', ',').filter { it.isNotBlank() } -
        ServeGithubAuth.ALLOWED_SCOPES
    require(unsupportedScopes.isEmpty()) {
      "GitHub auth scope ${unsupportedScopes.joinToString(" ")} is not allowed: sign-in only asks " +
        "for read-only identity scopes (${ServeGithubAuth.ALLOWED_SCOPES.joinToString(" ")}), " +
        "never repository access"
    }
    require(allowedOrgs.all { it.matches(GITHUB_ORG) }) {
      "GitHub auth orgs must be organization logins such as 'google'"
    }
    val domain = cookieDomain?.trim()?.removePrefix(".")?.takeIf { it.isNotEmpty() }
    if (domain != null) {
      val normalized = ServeSites.normalizeHost(domain)
      require(normalized != null) { "GitHub auth cookie domain '$cookieDomain' is not a hostname" }
      // A single-label domain or two-label public suffix would scope the session across a registry;
      // browsers would reject the Set-Cookie later, so refuse here.
      val labels = normalized.split(".")
      require(labels.size >= 2) {
        "GitHub auth cookie domain '$cookieDomain' must have at least two labels"
      }
      require(normalized !in PUBLIC_SUFFIXES) {
        "GitHub auth cookie domain '$cookieDomain' is a public suffix — no cookie may span it"
      }
      // The pinned callback is where the cookies are actually written, so a domain that doesn't
      // cover it produces a sign-in the browser drops on the floor.
      val callbackHost =
        callbackBaseUrl
          ?.substringAfter("://", "")
          ?.substringBefore('/')
          ?.takeIf { it.isNotEmpty() }
          ?.let { ServeSites.normalizeHost(it) }
      require(
        callbackHost == null || callbackHost == normalized || callbackHost.endsWith(".$normalized")
      ) {
        "GitHub auth cookie domain '$cookieDomain' does not cover the callback host '$callbackHost'"
      }
    }
  }

  /** Whether sign-in admits only some accounts as members — by login, by org, or both. */
  val restrictsMembership: Boolean
    get() = allowedUsers.isNotEmpty() || allowedOrgs.isNotEmpty()

  companion object {
    const val MIN_COOKIE_SECRET_CHARS = 32

    /** GitHub's own rule for an organization login: alphanumerics and single inner hyphens. */
    private val GITHUB_ORG = Regex("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")

    /**
     * Two-label names that are registries, not registrable domains. Not a full public-suffix list,
     * just the plausible typos.
     */
    private val PUBLIC_SUFFIXES =
      setOf(
        "co.uk",
        "org.uk",
        "ac.uk",
        "gov.uk",
        "co.jp",
        "co.nz",
        "co.za",
        "com.au",
        "com.br",
        "com.cn",
        "github.io",
      )
  }
}

class ServeGithubAuth(
  private val config: ServeGithubAuthConfig,
  private val verifier: GitHubOAuthVerifier = GitHubOAuthVerifier(),
  private val clock: Clock = Clock.systemUTC(),
) {
  /**
   * `GET /auth/github/start`. [siteHosts] ([ServeSites.hosts]) are the only hosts a sign-in may
   * return to, re-checked in [handleCallback]. Empty means the sign-in ends where it started.
   */
  suspend fun RoutingContext.handleStart(siteHosts: Set<String> = emptySet()) {
    val returnTo = safeReturnTo(call.request.queryParameters["return"] ?: "/")
    // Null on the ordinary same-host sign-in, which is then byte-for-byte what it always was.
    val originHost = originHostFor(call, siteHosts)
    val state = signedState(nonce(), returnTo, originHost)
    val secure = isSecure(call, config.callbackBaseUrl, config.trustForwardedHeaders)
    // Stale `cp_gh_state` variants from an older cookie domain are stored separately by the
    // browser, so line order doesn't matter.
    clearStaleVariants(call, STATE_COOKIE, secure)
    call.response.cookies.append(stateCookie(state, maxAge = STATE_TTL_SECONDS, secure = secure))
    call.respondRedirect(authorizeUrl(call, state))
  }

  /**
   * `GET /auth/github/callback`, which with a pinned callback always lands on the pinned origin. A
   * same-host sign-in returns to a relative path; a cross-host one (started on a site) returns to
   * an absolute URL on that site.
   *
   * The CSRF check is unchanged: with [ServeGithubAuthConfig.cookieDomain] set, `cp_gh_state` is
   * sent to the callback host too. The cross-host leg adds only a redirect target, not a
   * credential, and the session cookie already covers every site host.
   */
  suspend fun RoutingContext.handleCallback(siteHosts: Set<String> = emptySet()) {
    val state = call.request.queryParameters["state"].orEmpty()
    // Every value, not a sole one: a stale state cookie from a narrower domain can't be cleared
    // from a sibling host. The query's state must still match one this browser holds, which a
    // cross-site request can't arrange.
    val held = call.request.cookieValues(STATE_COOKIE)
    val code = call.request.queryParameters["code"].orEmpty()
    val statePayload = verifyState(state)
    if (
      state.isBlank() ||
        code.isBlank() ||
        statePayload == null ||
        held.none { expected -> tokensMatch(state, expected) }
    ) {
      // Clear the leftovers anyway, so a retry from this host starts from a single value.
      val secure = isSecure(call, config.callbackBaseUrl, config.trustForwardedHeaders)
      clearStaleVariants(call, STATE_COOKIE, secure)
      call.respondText("GitHub sign-in failed.", status = HttpStatusCode.Unauthorized)
      return
    }
    // The origin host is re-validated against the live site list: the signature proves we minted
    // the state, not that the host is still ours, and an unchecked value would be an open redirect.
    // Unrecognised hosts fall back to a same-origin relative return.
    val returnHost = statePayload.originHost?.takeIf { it in siteHosts && withinCookieDomain(it) }
    val secure = isSecure(call, config.callbackBaseUrl, config.trustForwardedHeaders)
    val user =
      withContext(Dispatchers.IO) { verifier.verify(code, callbackUrl(call), config) }
        .getOrElse { failure ->
          if (failure is GitHubGrantTooBroadException) {
            respondToTooBroadGrant(failure, statePayload.returnTo, returnHost, secure)
          } else {
            call.respondText("GitHub sign-in failed.", status = HttpStatusCode.Forbidden)
          }
          return
        }
    // This is the moment GitHub actually vouched for the visitor, so it anchors the absolute cap
    // that [refreshSession] may never slide a session past.
    val authenticatedAt = clock.millis()
    val session =
      signedSession(
        user.login,
        user.repositoryAccess,
        user.imageRepositoryAccess,
        authenticatedAt,
        guest = user.guest,
      )
    call.response.cookies.append(authCookie(session, maxAge = SESSION_TTL_SECONDS, secure = secure))
    call.response.cookies.append(stateCookie("", maxAge = 0, secure = secure))
    call.response.cookies.append(regrantCookie("", maxAge = 0, secure = secure))
    // Written after the session above, and distinct from it in the browser's store (no `Domain`,
    // or a narrower one), so this removes only leftovers and never the cookie just minted.
    clearStaleVariants(call, AUTH_COOKIE, secure)
    clearStaleVariants(call, STATE_COOKIE, secure)
    call.respondRedirect(
      if (returnHost == null) statePayload.returnTo
      else "https://$returnHost${statePayload.returnTo}"
    )
  }

  /**
   * The visitor's authorization carries more than [ALLOWED_SCOPES] (usually an old `repo` grant
   * GitHub keeps re-issuing). [GitHubOAuthVerifier.verify] has tried to revoke it; if that worked,
   * the visitor is sent through sign-in once more for a fresh consent screen (a [REGRANT_COOKIE]
   * prevents loops), otherwise told how to remove it.
   */
  private suspend fun RoutingContext.respondToTooBroadGrant(
    failure: GitHubGrantTooBroadException,
    returnTo: String,
    returnHost: String?,
    secure: Boolean,
  ) {
    // Any value at all counts: this cookie only ever stops a retry, so more than one is still one.
    val alreadyRetried = call.request.cookieValues(REGRANT_COOKIE).isNotEmpty()
    if (failure.revoked && !alreadyRetried) {
      call.response.cookies.append(regrantCookie("1", maxAge = STATE_TTL_SECONDS, secure = secure))
      val start = "$START_PATH?return=${urlEncode(returnTo)}"
      call.respondRedirect(if (returnHost == null) start else "https://$returnHost$start")
      return
    }
    call.respondText(
      "Your GitHub authorization for this site grants more than it needs " +
        "(${failure.extraScopes.sorted().joinToString(" ")}). Revoke it at " +
        "https://github.com/settings/applications, then sign in again.",
      status = HttpStatusCode.Forbidden,
    )
  }

  /**
   * `POST /auth/github/logout`: overwrite `cp_gh_auth` with an empty, expired cookie and return to
   * [safeReturnTo] of the `return` parameter.
   *
   * POST only, so prefetchers and `<img src>` can't fire it. The deletion uses the same
   * [sessionCookie] attributes (a mismatch would store a second cookie), and the empty value fails
   * [verifySession] regardless. With a cookie domain, the host-only variant is cleared too, since
   * two session values read as signed out.
   *
   * This can't revoke a stateless cookie copied off the wire; server-side revocation is a different
   * design ([#280](https://github.com/yschimke/compose-preview-server/issues/280)).
   */
  suspend fun RoutingContext.handleLogout() {
    val returnTo = safeReturnTo(call.request.queryParameters["return"] ?: "/")
    val secure = isSecure(call, config.callbackBaseUrl, config.trustForwardedHeaders)
    call.response.cookies.append(authCookie("", maxAge = 0, secure = secure))
    clearStaleVariants(call, AUTH_COOKIE, secure)
    call.respondRedirect(returnTo)
  }

  /**
   * Whether a sign-in started on [rawHost] can come back to it: true when the callback isn't
   * pinned, on the pinned host, or for a configured site host inside the cookie domain. False
   * otherwise, since the visitor would land back signed out; [ServeHttpServer] uses it to decide
   * whether to offer sign-in.
   */
  fun canRoundTrip(rawHost: String?, siteHosts: Set<String>): Boolean {
    if (!hasPinnedCallback) return true
    val host = rawHost?.let { ServeSites.normalizeHost(it) } ?: return false
    if (host == pinnedCallbackHost()) return true
    return host in siteHosts && withinCookieDomain(host)
  }

  fun isAuthenticated(call: ApplicationCall): Boolean {
    return currentLogin(call) != null
  }

  /**
   * Slide a still-valid session forward so an active visitor stays signed in, never past the
   * absolute cap stamped at sign-in.
   *
   * The cookie is self-contained and the token isn't kept, so refreshing copies the sign-in-time
   * `repositoryAccess` flag (the playground gate); the cap ([SESSION_ABSOLUTE_TTL_SECONDS]) bounds
   * how long a revoked user keeps it. Idle expiry [SESSION_TTL_SECONDS] slides once past its
   * half-life ([SESSION_REFRESH_AFTER_SECONDS]); otherwise no cookie is set.
   *
   * Skipped on OAuth routes ([handleCallback] mints its own cookie) and on `Cache-Control: public`
   * responses, which a shared cache might store with the cookie; hence it runs at
   * `ResponseBodyReadyForSend` when [contentCacheControl] is known. With a cookie domain, duplicate
   * host-only or narrower session cookies are cleared here.
   */
  fun refreshSession(call: ApplicationCall, contentCacheControl: List<String> = emptyList()) {
    if (call.request.uri.substringBefore('?').startsWith(AUTH_PATH_PREFIX)) return
    val secure = isSecure(call, config.callbackBaseUrl, config.trustForwardedHeaders)
    // Before the public-cache check: a request with two session values reads as signed out and gets
    // the public cache policy, so returning early would leave the stale copy forever. The clearing
    // cookie carries no session.
    if (call.request.cookieValues(AUTH_COOKIE).size > 1) {
      clearStaleVariants(call, AUTH_COOKIE, secure)
      return
    }
    val cacheControl = call.response.headers.values(HttpHeaders.CacheControl) + contentCacheControl
    if (cacheControl.any { directive -> isPublicCacheControl(directive) }) return
    val session = call.request.soleCookieValue(AUTH_COOKIE)?.let { verifySession(it) } ?: return
    val now = clock.millis()
    if (session.expiresAt - now > SESSION_REFRESH_AFTER_SECONDS * 1000) return
    val authenticatedAt = session.authenticatedAt
    val expiresAt =
      minOf(now + SESSION_TTL_SECONDS * 1000, authenticatedAt + SESSION_ABSOLUTE_TTL_SECONDS * 1000)
    if (expiresAt <= session.expiresAt) return
    call.response.cookies.append(
      authCookie(
        signedSession(
          session.login,
          session.repositoryAccess,
          session.imageRepositoryAccess,
          authenticatedAt,
          expiresAt,
          guest = session.guest,
        ),
        // The cookie dies with the payload it carries, rather than outliving it as a cookie the
        // browser keeps sending and the server keeps rejecting.
        maxAge = (expiresAt - now) / 1000,
        secure = secure,
      )
    )
  }

  /**
   * The signed-in member (allowlisted, or any account without an allowlist). Null for a guest,
   * which keeps every member gate closed to one.
   */
  fun currentLogin(call: ApplicationCall): String? {
    val cookie = call.request.soleCookieValue(AUTH_COOKIE) ?: return null
    return verifySession(cookie)?.takeIf { !it.guest }?.login
  }

  /**
   * Whoever GitHub vouched for, member or guest: an identity, never a permission. Used only by the
   * UI builder's read-only access and page chrome.
   */
  fun currentSignedInLogin(call: ApplicationCall): String? {
    val cookie = call.request.soleCookieValue(AUTH_COOKIE) ?: return null
    return verifySession(cookie)?.login
  }

  /** The login a raw session cookie value speaks for, member or guest; null when it is refused. */
  internal fun sessionLogin(cookie: String): String? = verifySession(cookie)?.login

  /** True when the session belongs to a guest rather than a member. */
  fun isGuest(call: ApplicationCall): Boolean {
    val cookie = call.request.soleCookieValue(AUTH_COOKIE) ?: return false
    return verifySession(cookie)?.guest == true
  }

  fun hasRepositoryAccess(call: ApplicationCall): Boolean {
    val cookie = call.request.soleCookieValue(AUTH_COOKIE) ?: return false
    return verifySession(cookie)?.let { it.repositoryAccess && !it.guest } == true
  }

  /**
   * Access to the image lane's gating repository ([imageAccessRepository]), deciding whether an
   * approver may grant `images` to an agent.
   */
  fun hasImageRepositoryAccess(call: ApplicationCall): Boolean {
    val cookie = call.request.soleCookieValue(AUTH_COOKIE) ?: return false
    return hasImageRepositoryAccess(cookie)
  }

  /** [hasImageRepositoryAccess] on a raw cookie value, so the rule can be tested without a call. */
  internal fun hasImageRepositoryAccess(cookie: String): Boolean {
    // Every accepted cookie carries its own image bit; older cookies without the field no longer
    // verify ([verifySession]).
    return verifySession(cookie)?.imageRepositoryAccess == true
  }

  fun loginPath(call: ApplicationCall): String {
    val current = call.uriWithQuery()
    return "$START_PATH?return=${urlEncode(current)}"
  }

  /** Where a "Sign out" form posts, returning to the current page; mirrors [loginPath]. */
  fun logoutPath(call: ApplicationCall): String {
    val current = call.uriWithQuery()
    return "$LOGOUT_PATH?return=${urlEncode(current)}"
  }

  /**
   * The pinned callback's origin, or null when it follows the request's host. Listed in pages'
   * `form-action` ([ServePagePolicy]).
   */
  fun callbackOrigin(): String? = config.callbackBaseUrl?.let(ServePagePolicy::formActionSource)

  fun accessRepository(): String = config.repository

  /** The GitHub orgs whose members sign in as members (`--github-auth-orgs`); empty when none. */
  fun allowedOrgs(): Set<String> = config.allowedOrgs

  /**
   * The repository [hasImageRepositoryAccess] speaks for: `--image-upload-repo`, else the sign-in
   * repository. Compare lane gates against this, not [accessRepository].
   */
  fun imageAccessRepository(): String =
    config.imageRepository?.takeIf { it.isNotBlank() } ?: config.repository

  fun isRestrictedToAllowedUsers(): Boolean = config.restrictsMembership

  /**
   * Whether any signed-in member may write UI-builder designs; see
   * [ServeGithubAuthConfig.openUiBuilder].
   */
  fun opensUiBuilder(): Boolean = config.openUiBuilder

  private fun authorizeUrl(call: ApplicationCall, state: String): String {
    val params =
      listOf(
          "client_id" to config.clientId,
          "redirect_uri" to callbackUrl(call),
          "scope" to requestedScope(),
          "state" to state,
        )
        .joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    return "https://github.com/login/oauth/authorize?$params"
  }

  /**
   * The OAuth scope to request: identity only, nothing about repositories. Never `repo` (full
   * control of private repositories) just to answer one access question.
   *
   * [USER_SCOPE] covers `GET /user` and `GET /repos/{owner}/{repo}`
   * ([GitHubOAuthVerifier.fetchRepositoryAccess]), which reports the visitor's permissions on a
   * public repo; a private gating repo is invisible and grants no access, the safe side. `read:org`
   * is added with `--github-auth-orgs`. [ServeGithubAuthConfig.oauthScope] may replace the base
   * only with [ALLOWED_SCOPES].
   */
  internal fun requestedScope(): String {
    val base = config.oauthScope?.trim()?.takeIf { it.isNotEmpty() } ?: USER_SCOPE
    val needsOrg = config.allowedOrgs.isNotEmpty() && ORG_SCOPE !in base.split(' ', ',')
    return if (needsOrg) "$base $ORG_SCOPE" else base
  }

  /**
   * Whether the callback is pinned (`--github-auth-callback-base-url`) rather than request-derived,
   * so a sign-in begun on a site host must be handed back to it. Use [canRoundTrip] for a specific
   * host.
   */
  val hasPinnedCallback: Boolean
    get() = !config.callbackBaseUrl.isNullOrBlank()

  private fun callbackUrl(call: ApplicationCall?): String =
    config.callbackBaseUrl?.trimEnd('/')?.plus(CALLBACK_PATH)
      ?: call?.let { externalOrigin(it, config.trustForwardedHeaders) + CALLBACK_PATH }
      ?: CALLBACK_PATH

  /**
   * The host to return to after the callback, or null when the sign-in ends where it started. Only
   * configured site hosts inside the cookie domain qualify (preventing an open redirect),
   * re-checked in [handleCallback] since the value round-trips through the client.
   */
  private fun originHostFor(call: ApplicationCall, siteHosts: Set<String>): String? {
    if (!hasPinnedCallback || siteHosts.isEmpty()) return null
    val host = requestHost(call, config.trustForwardedHeaders) ?: return null
    if (host == pinnedCallbackHost()) return null
    return host.takeIf { it in siteHosts && withinCookieDomain(it) }
  }

  /**
   * Whether a cookie for [ServeGithubAuthConfig.cookieDomain] would be sent to [host] (the domain
   * or a subdomain). The dot check stops `notpreview.coo.ee` matching `preview.coo.ee`. No domain ⇒
   * false.
   */
  private fun withinCookieDomain(host: String): Boolean {
    val domain = cookieDomain ?: return false
    return host == domain || host.endsWith(".$domain")
  }

  /** The configured cookie domain, normalised; null when cookies stay host-only. */
  private val cookieDomain: String? by lazy {
    config.cookieDomain
      ?.trim()
      ?.removePrefix(".")
      ?.takeIf { it.isNotEmpty() }
      ?.let { ServeSites.normalizeHost(it) }
  }

  /** The host of the pinned callback, so a sign-in already on it is never handed off to itself. */
  private fun pinnedCallbackHost(): String? =
    config.callbackBaseUrl
      ?.substringAfter("://", "")
      ?.substringBefore('/')
      ?.takeIf { it.isNotEmpty() }
      ?.let { ServeSites.normalizeHost(it) }

  /**
   * `nonce|issuedAt|originHost|returnTo`. None of the first three can contain `|`, so `returnTo`
   * (which may contain anything) keeps the rest of the string. The issue time lets [verifyState]
   * enforce [STATE_TTL_SECONDS] itself.
   */
  private fun signedState(nonce: String, returnTo: String, originHost: String?): String =
    sign(STATE_PURPOSE, "$nonce|${clock.millis()}|${originHost.orEmpty()}|$returnTo")

  /**
   * Null unless this is a state we minted within [STATE_TTL_SECONDS]. Older-shape states are
   * refused; only a sign-in in flight across a deploy is affected.
   */
  private fun verifyState(value: String): StatePayload? {
    val parts = verifySigned(STATE_PURPOSE, value)?.split("|", limit = 4) ?: return null
    if (parts.size != 4 || parts[0].isEmpty()) return null
    val issuedAt = parts[1].toLongOrNull() ?: return null
    if (clock.millis() - issuedAt !in 0..STATE_TTL_SECONDS * 1000) return null
    val originHost =
      if (parts[2].isEmpty()) null else ServeSites.normalizeHost(parts[2]) ?: return null
    return StatePayload(parts[0], safeReturnTo(parts[3]), originHost)
  }

  /**
   * `login|repo|expiresAt|authenticatedAt|image|role|configFingerprint`. Fields are only ever
   * appended so older ones keep their positions.
   */
  private fun signedSession(
    login: String,
    repositoryAccess: Boolean,
    imageRepositoryAccess: Boolean,
    authenticatedAt: Long,
    expiresAt: Long = clock.millis() + SESSION_TTL_SECONDS * 1000,
    guest: Boolean = false,
  ): String {
    val repoFlag = if (repositoryAccess && !guest) "repo" else "no-repo"
    val imageFlag = if (imageRepositoryAccess && !guest) "image-repo" else "no-image-repo"
    val role = if (guest) GUEST_FLAG else MEMBER_FLAG
    return sign(
      SESSION_PURPOSE,
      "${login.lowercase()}|$repoFlag|$expiresAt|$authenticatedAt|$imageFlag|$role|$configFingerprint",
    )
  }

  /**
   * Null unless [value] is a session we signed, unexpired, under the current sign-in configuration
   * ([configFingerprint]). The fingerprint makes config changes (e.g. a new allowlist) apply to
   * existing sessions, which simply sign in again silently. Cookies without a fingerprint, or older
   * shapes signed without the purpose tag, are refused.
   */
  private fun verifySession(value: String): SessionPayload? {
    val parts = verifySigned(SESSION_PURPOSE, value)?.split("|") ?: return null
    if (parts.size != 7) return null
    if (!tokensMatch(configFingerprint, parts[6])) return null
    val login = parts[0]
    val expiresAt = parts[2].toLongOrNull() ?: return null
    val authenticatedAt = parts[3].toLongOrNull() ?: return null
    if (expiresAt <= clock.millis() || login.isBlank()) return null
    val guest =
      when (parts[5]) {
        GUEST_FLAG -> true
        MEMBER_FLAG -> false
        else -> return null
      }
    // Covered by the fingerprint already (it includes the guest setting), and kept as its own rule:
    // a guest cookie is never read while guests are turned off.
    if (guest && !config.allowGuests) return null
    return SessionPayload(
      login = login,
      repositoryAccess = parts[1] == "repo" && !guest,
      imageRepositoryAccess = parts[4] == "image-repo" && !guest,
      expiresAt = expiresAt,
      authenticatedAt = authenticatedAt,
      guest = guest,
    )
  }

  /**
   * The sign-in configuration digest sessions are checked against ([verifySession]). Order- and
   * case-insensitive, so a restart with the same settings keeps everyone signed in.
   */
  private val configFingerprint: String by lazy { configFingerprint(config) }

  /**
   * `base64url(payload).mac`, with the MAC also covering [purpose], so a session value can't verify
   * as a state (one secret signs both).
   */
  private fun sign(purpose: String, payload: String): String {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val sig = hmac("$purpose:$encoded")
    return "$encoded.$sig"
  }

  private fun verifySigned(purpose: String, value: String): String? {
    val parts = value.split(".", limit = 2)
    if (parts.size != 2 || !tokensMatch(hmac("$purpose:${parts[0]}"), parts[1])) return null
    return runCatching { Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8) }
      .getOrNull()
  }

  private fun hmac(value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(config.cookieSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray()))
  }

  /**
   * With a cookie domain, expire every other variant of cookie [name] this host can reach
   * (host-only and each intermediate domain), which would otherwise linger after the domain was set
   * or widened. Deriving the narrower domains from `Host` is safe since these cookies only expire
   * things.
   */
  private fun clearStaleVariants(call: ApplicationCall, name: String, secure: Boolean) {
    val domain = cookieDomain ?: return
    call.response.cookies.append(sessionCookie(name, "", 0, secure, domain = null))
    val host = requestHost(call, config.trustForwardedHeaders) ?: return
    if (!host.endsWith(".$domain")) return
    // `m3.preview.coo.ee` under `coo.ee` yields `m3.preview.coo.ee` and `preview.coo.ee`.
    generateSequence(host) { it.substringAfter('.', "") }
      .takeWhile { it.length > domain.length }
      .forEach { narrower ->
        call.response.cookies.append(sessionCookie(name, "", 0, secure, domain = narrower))
      }
  }

  private fun nonce(): String {
    val bytes = ByteArray(18)
    SECURE_RANDOM.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  private fun stateCookie(value: String, maxAge: Long, secure: Boolean): Cookie =
    sessionCookie(STATE_COOKIE, value, maxAge, secure)

  private fun regrantCookie(value: String, maxAge: Long, secure: Boolean): Cookie =
    sessionCookie(REGRANT_COOKIE, value, maxAge, secure)

  private fun authCookie(value: String, maxAge: Long, secure: Boolean): Cookie =
    sessionCookie(AUTH_COOKIE, value, maxAge, secure)

  /**
   * [secure] is derived per request ([isSecure]): TLS-terminated deployments must not send the
   * cookie over plaintext, while `http://localhost` must still get it back.
   */
  private fun sessionCookie(
    name: String,
    value: String,
    maxAge: Long,
    secure: Boolean,
    domain: String? = cookieDomain,
  ): Cookie =
    Cookie(
      name = name,
      value = value,
      path = "/",
      maxAge = maxAge.toInt(),
      // Null ⇒ host-only. Always the operator's configured value, never request-derived.
      domain = domain,
      secure = secure,
      httpOnly = true,
      encoding = CookieEncoding.URI_ENCODING,
      extensions = mapOf("SameSite" to "Lax"),
    )

  private data class StatePayload(
    val nonce: String,
    val returnTo: String,
    /** The site host to hand the finished sign-in back to; null on the same-host flow. */
    val originHost: String?,
  )

  private data class SessionPayload(
    val login: String,
    val repositoryAccess: Boolean,
    /**
     * Access to the image lane's gating repository; see [ServeGithubAuthConfig.imageRepository].
     */
    val imageRepositoryAccess: Boolean,
    val expiresAt: Long,
    /** When GitHub last vouched for this visitor. */
    val authenticatedAt: Long,
    /** Signed in outside the allowlist; see [ServeGithubAuthConfig.allowGuests]. */
    val guest: Boolean = false,
  )

  companion object {
    const val START_PATH = "/auth/github/start"
    const val CALLBACK_PATH = "/auth/github/callback"

    /**
     * POST-only, and under [AUTH_PATH_PREFIX] so [refreshSession] doesn't mint a fresh session
     * beside the expired one.
     */
    const val LOGOUT_PATH = "/auth/github/logout"

    private const val AUTH_COOKIE = "cp_gh_auth"

    /** The sixth session field: who the session belongs to. */
    private const val GUEST_FLAG = "guest"
    private const val MEMBER_FLAG = "member"

    /** Mixed into each signature so a value signed as one kind never verifies as the other. */
    private const val SESSION_PURPOSE = "session"
    private const val STATE_PURPOSE = "state"
    private const val STATE_COOKIE = "cp_gh_state"

    /** Marks the one automatic re-sign-in after an over-broad grant was revoked. */
    private const val REGRANT_COOKIE = "cp_gh_regrant"
    /**
     * Enough to read `/user` and a public repo's payload. No repository write, no private repos.
     */
    const val USER_SCOPE = "read:user"

    /** Added to the scope when `--github-auth-orgs` is set, so private membership reads. */
    const val ORG_SCOPE = "read:org"

    /**
     * Every scope sign-in may request, override included: read-only identity, nothing reaching a
     * repository.
     */
    val ALLOWED_SCOPES: Set<String> = setOf(USER_SCOPE, "user:email", ORG_SCOPE)

    /** Both OAuth routes, so [refreshSession] can leave the cookie-minting ones alone. */
    private const val AUTH_PATH_PREFIX = "/auth/github/"

    internal const val STATE_TTL_SECONDS = 10L * 60

    /** Length of [configFingerprint] in hex characters: 64 bits of SHA-256. */
    private const val CONFIG_FINGERPRINT_CHARS = 16

    /**
     * Short digest of everything deciding what a session may carry: both repositories, allowed
     * logins and orgs, and guest admission.
     */
    internal fun configFingerprint(config: ServeGithubAuthConfig): String {
      val imageRepository = config.imageRepository?.takeIf { it.isNotBlank() } ?: config.repository
      val canonical =
        listOf(
            config.repository.lowercase(),
            imageRepository.lowercase(),
            config.allowedUsers.map { it.lowercase() }.sorted().joinToString(","),
            config.allowedOrgs.map { it.lowercase() }.sorted().joinToString(","),
            config.allowGuests.toString(),
          )
          .joinToString("\n")
      val digest =
        MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
      return digest.joinToString("") { "%02x".format(it) }.take(CONFIG_FINGERPRINT_CHARS)
    }

    /** Whether a `Cache-Control` value lets a shared cache store the response. */
    internal fun isPublicCacheControl(value: String): Boolean =
      value.split(',').any { it.trim().equals("public", ignoreCase = true) }

    /**
     * Idle expiry: how long a session survives without a visit; [refreshSession] slides it up to
     * [SESSION_ABSOLUTE_TTL_SECONDS]. A week, so ordinary occasional visits never hit a sign-in.
     */
    internal const val SESSION_TTL_SECONDS = 7L * 24 * 60 * 60

    /**
     * Absolute expiry from sign-in, never extended: the ceiling on how stale the cached
     * `repositoryAccess` (playground gate) can be. Re-authorising an approved OAuth app needs no
     * consent screen, so reaching it is cheap.
     */
    internal const val SESSION_ABSOLUTE_TTL_SECONDS = 14L * 24 * 60 * 60

    /** Sessions are re-minted past half their idle expiry, rather than on every response. */
    internal const val SESSION_REFRESH_AFTER_SECONDS = SESSION_TTL_SECONDS / 2
    private val SECURE_RANDOM = SecureRandom()

    /**
     * [value] when it is a same-origin path, else `/`. Browsers treat `\` as `/` and drop
     * tabs/newlines, so any backslash or control character is refused along with `//`.
     */
    fun safeReturnTo(value: String): String =
      if (
        value.startsWith("/") &&
          !value.startsWith("//") &&
          value.none { it == '\\' || it.isISOControl() }
      )
        value
      else "/"

    fun tokensMatch(expected: String, provided: String?): Boolean {
      if (provided == null) return false
      return MessageDigest.isEqual(expected.toByteArray(), provided.toByteArray())
    }
  }
}

data class GitHubOAuthUser(
  val login: String,
  val repositoryAccess: Boolean,
  /**
   * Access to [ServeGithubAuthConfig.imageRepository]; defaults to [repositoryAccess] when it is
   * the same repository or unset.
   */
  val imageRepositoryAccess: Boolean = repositoryAccess,
  /** Admitted as a guest — outside the allowlist — so carrying no access of its own. */
  val guest: Boolean = false,
)

class GitHubOAuthVerifier(private val client: OkHttpClient = OkHttpClient()) {
  fun verify(
    code: String,
    redirectUri: String,
    config: ServeGithubAuthConfig,
  ): Result<GitHubOAuthUser> = runCatching {
    val grant = exchangeCode(code, redirectUri, config)
    val token = grant.accessToken
    val extraScopes = grant.scopes - ServeGithubAuth.ALLOWED_SCOPES
    if (extraScopes.isNotEmpty()) {
      // Revoking the grant revokes this token with it, and every other token the visitor issued
      // to this app — which is exactly the old `repo` consent we want gone.
      throw GitHubGrantTooBroadException(extraScopes, revoked = revokeGrant(token, config))
    }
    try {
      identify(token, config)
    } finally {
      // Nothing keeps the token past this call, so nothing should be able to use it either.
      revokeToken(token, config)
    }
  }

  private fun identify(token: String, config: ServeGithubAuthConfig): GitHubOAuthUser {
    val login = fetchLogin(token)
    if (!isAdmitted(token, login, config.allowedUsers, config.allowedOrgs)) {
      if (!config.allowGuests) error("GitHub user $login is not allowed")
      // A guest is an identity and nothing more. No repository is asked about, so no access bit
      // exists for a later gate to misread.
      return GitHubOAuthUser(
        login,
        repositoryAccess = false,
        imageRepositoryAccess = false,
        guest = true,
      )
    }
    val repositoryAccess = fetchRepositoryAccess(token, config.repository, login)
    return GitHubOAuthUser(
      login,
      repositoryAccess = repositoryAccess,
      // Only now does the visitor's token exist here, so the second bit must be taken now or never.
      imageRepositoryAccess =
        config.imageRepository
          ?.takeIf { !it.equals(config.repository, ignoreCase = true) }
          ?.let { fetchRepositoryAccess(token, it, login) } ?: repositoryAccess,
    )
  }

  /**
   * The same identity and access decision as [verify], for a caller already holding a GitHub token
   * (no OAuth exchange or client secret). Agents are measured against exactly the same bar on one
   * code path, so the gate can't diverge.
   *
   * Which tokens count is [tokens] ([ImageUploadTokenPolicy]): a user token issued to [app] always;
   * PATs, other apps' user tokens and installation tokens only when allowed. The token is never
   * retained or logged. A failure to reach GitHub is a [GitHubCheckUnavailableException], not a
   * verdict.
   */
  fun verifyAccessToken(
    token: String,
    repository: String,
    allowedUsers: Set<String> = emptySet(),
    allowedOrgs: Set<String> = emptySet(),
    tokens: ImageUploadTokenPolicy = ImageUploadTokenPolicy.ANY,
    app: GitHubOAuthApp? = null,
  ): Result<GitHubOAuthUser> = runCatching {
    val kind = GitHubTokenKind.of(token)
    // Refused by shape before any round trip: the prefix is part of the token, so it can't be
    // dressed up as another kind.
    if (kind == GitHubTokenKind.INSTALLATION && !tokens.installation) {
      throw ImageUploadTokenRefusedException(
        "this host does not accept GitHub App installation tokens for image uploads"
      )
    }
    if (kind == GitHubTokenKind.PERSONAL && !tokens.personal) {
      throw ImageUploadTokenRefusedException(
        "this host does not accept personal access tokens for image uploads"
      )
    }
    // Null when `/user` refuses the credential, which is the normal answer for a GitHub **App
    // installation** token rather than a sign of a bad one — see [verifyInstallationToken].
    val login = lookupLogin(token)
    if (login == null) {
      if (!tokens.installation) {
        error("user lookup refused that token, and this host does not accept installation tokens")
      }
      return@runCatching verifyInstallationToken(
        token,
        repository,
        restricted = allowedUsers.isNotEmpty() || allowedOrgs.isNotEmpty(),
      )
    }
    if (kind == GitHubTokenKind.APP_USER && !tokens.otherApps) {
      // A personal access token is the user's own; any other user token was issued to *some* app,
      // and only this server's own is one we can recognise.
      if (app == null) {
        throw ImageUploadTokenRefusedException(
          "this host does not accept user tokens issued to OAuth apps"
        )
      }
      if (!isIssuedToApp(token, app)) {
        throw ImageUploadTokenRefusedException(
          "that token was issued to a different OAuth app than this server's"
        )
      }
    }
    if (!isAdmitted(token, login, allowedUsers, allowedOrgs)) {
      error("GitHub user $login is not allowed")
    }
    GitHubOAuthUser(login, repositoryAccess = fetchRepositoryAccess(token, repository, login))
  }

  /**
   * `GET /user`: the login, or null when GitHub refuses the token (`401`/`403`). Network, `429` or
   * `5xx` throw [GitHubCheckUnavailableException] so an outage isn't cached against a good token.
   */
  private fun lookupLogin(token: String): String? {
    val request =
      Request.Builder()
        .url("https://api.github.com/user")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    val response =
      try {
        client.newCall(request).execute()
      } catch (e: java.io.IOException) {
        throw GitHubCheckUnavailableException("user lookup failed: ${e.javaClass.simpleName}")
      }
    return response.use {
      when {
        it.isSuccessful ->
          JSON.decodeFromString(GitHubUserResponse.serializer(), it.body.string()).login
        it.code == 429 || it.code >= 500 ->
          throw GitHubCheckUnavailableException("user lookup failed: ${it.code}")
        else -> null
      }
    }
  }

  /**
   * Whether [token] was issued to [app] (`POST /applications/{client_id}/token`): `200` yes,
   * `404`/`422` no, anything else [GitHubCheckUnavailableException].
   */
  internal fun isIssuedToApp(token: String, app: GitHubOAuthApp): Boolean {
    val body =
      JSON.encodeToString(GitHubAccessTokenBody.serializer(), GitHubAccessTokenBody(token))
        .toRequestBody("application/json".toMediaType())
    val request =
      Request.Builder()
        .url("https://api.github.com/applications/${urlEncode(app.clientId)}/token")
        .header(HttpHeaders.Authorization, Credentials.basic(app.clientId, app.clientSecret))
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .post(body)
        .build()
    val response =
      try {
        client.newCall(request).execute()
      } catch (e: java.io.IOException) {
        throw GitHubCheckUnavailableException("app token check failed: ${e.javaClass.simpleName}")
      }
    return response.use {
      when (it.code) {
        200 -> true
        404,
        422 -> false
        else -> throw GitHubCheckUnavailableException("app token check failed: ${it.code}")
      }
    }
  }

  /**
   * GitHub App installation tokens (e.g. `GITHUB_TOKEN` in Actions): no user behind them, but `GET
   * /repos/{owner}/{repo}` reports the installation's own permissions.
   *
   * Two narrowings versus users: write is always required (a fork's read-only workflow token must
   * not post), and they are refused outright when sign-in is narrowed by users or orgs. The
   * identity is [INSTALLATION_LOGIN], since an installation token can't name its app; this admits
   * any app installed with write, which `--image-upload-tokens` can turn off.
   */
  private fun verifyInstallationToken(
    token: String,
    repository: String,
    restricted: Boolean,
  ): GitHubOAuthUser {
    if (restricted) {
      error("this host admits only named GitHub users, and that is not a user credential")
    }
    val permissions =
      repositoryView(token, repository)?.permissions
        ?: error("credential is not a GitHub user, and cannot read $repository as an installation")
    return GitHubOAuthUser(INSTALLATION_LOGIN, repositoryAccess = permissions.write())
  }

  /**
   * Whether [login] is a member: no restriction, a name on [allowedUsers] (checked first, no round
   * trip), or membership of an [allowedOrgs] org.
   */
  private fun isAdmitted(
    token: String,
    login: String,
    allowedUsers: Set<String>,
    allowedOrgs: Set<String>,
  ): Boolean {
    if (allowedUsers.isEmpty() && allowedOrgs.isEmpty()) return true
    if (login.lowercase() in allowedUsers) return true
    return allowedOrgs.any { isOrgMember(token, it, login) }
  }

  /**
   * Whether [login] belongs to [org], asked two ways: `/user/memberships/orgs/{org}` sees private
   * membership (needs `read:org` and org approval of the app), `/orgs/{org}/public_members/{login}`
   * sees public membership with no scope. Either yes suffices; anything else is no (the visitor
   * lands as a guest).
   */
  internal fun isOrgMember(token: String, org: String, login: String): Boolean {
    val membership =
      Request.Builder()
        .url("https://api.github.com/user/memberships/orgs/$org")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    val active = runCatching {
      client.newCall(membership).execute().use { response ->
        response.isSuccessful &&
          JSON.decodeFromString(GitHubMembershipResponse.serializer(), response.body.string())
            .state == "active"
      }
    }
      .getOrDefault(false)
    if (active) return true
    val publicMember =
      Request.Builder()
        .url("https://api.github.com/orgs/$org/public_members/$login")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return runCatching { client.newCall(publicMember).execute().use { it.code == 204 } }
      .getOrDefault(false)
  }

  private fun exchangeCode(
    code: String,
    redirectUri: String,
    config: ServeGithubAuthConfig,
  ): GitHubGrant {
    val body =
      FormBody.Builder()
        .add("client_id", config.clientId)
        .add("client_secret", config.clientSecret)
        .add("code", code)
        .add("redirect_uri", redirectUri)
        .build()
    val request =
      Request.Builder()
        .url("https://github.com/login/oauth/access_token")
        .header(HttpHeaders.Accept, "application/json")
        .post(body)
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("token exchange failed: ${response.code}")
      val payload = JSON.decodeFromString(GitHubTokenResponse.serializer(), response.body.string())
      GitHubGrant(
        accessToken = payload.accessToken ?: error("token exchange did not return access_token"),
        scopes = payload.scope.orEmpty().split(',', ' ').filter { it.isNotBlank() }.toSet(),
      )
    }
  }

  /**
   * `DELETE /applications/{client_id}/grant`: removes the visitor's whole authorization of this
   * app, every token included, so their next sign-in shows a fresh consent screen. True on success.
   */
  private fun revokeGrant(token: String, config: ServeGithubAuthConfig): Boolean =
    deleteApplicationCredential("grant", token, config)

  /** `DELETE /applications/{client_id}/token`: revokes this one token, leaving the grant. */
  private fun revokeToken(token: String, config: ServeGithubAuthConfig): Boolean =
    deleteApplicationCredential("token", token, config)

  private fun deleteApplicationCredential(
    kind: String,
    token: String,
    config: ServeGithubAuthConfig,
  ): Boolean {
    val body =
      JSON.encodeToString(GitHubAccessTokenBody.serializer(), GitHubAccessTokenBody(token))
        .toRequestBody("application/json".toMediaType())
    val request =
      Request.Builder()
        .url("https://api.github.com/applications/${urlEncode(config.clientId)}/$kind")
        .header(HttpHeaders.Authorization, Credentials.basic(config.clientId, config.clientSecret))
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .delete(body)
        .build()
    return runCatching { client.newCall(request).execute().use { it.code == 204 } }
      .getOrDefault(false)
  }

  private fun fetchLogin(token: String): String {
    val request =
      Request.Builder()
        .url("https://api.github.com/user")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("user lookup failed: ${response.code}")
      JSON.decodeFromString(GitHubUserResponse.serializer(), response.body.string()).login
    }
  }

  /**
   * Whether [login] has meaningful access to [repository], the gate on the playground (which runs a
   * stranger's Kotlin). On a public repo every user has `read`, so require
   * `admin`/`maintain`/`write`; on a private repo any permission other than `none` is a deliberate
   * grant (see #3313). Unknown visibility requires write, the safe side.
   */
  private fun fetchRepositoryAccess(token: String, repository: String, login: String): Boolean {
    // One call answers both visibility (`private`) and this user's `permissions`, and works on a
    // public repo with no repo scope, unlike `/collaborators/{login}/permission`
    // ([ServeGithubAuth.requestedScope]).
    repositoryView(token, repository)?.let { repo ->
      val access = repo.permissions ?: return@let // no permissions block — fall through below
      return if (repo.private == true) access.any() else access.write()
    }
    // Fallback for a payload that carries no `permissions` block. Same logic as before, and the
    // same two calls, so a deployment where the block is absent behaves exactly as it did.
    val request =
      Request.Builder()
        .url("https://api.github.com/repos/$repository/collaborators/$login/permission")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) return@use false
      val payload =
        JSON.decodeFromString(GitHubPermissionResponse.serializer(), response.body.string())
      val permission = payload.permission.lowercase()
      val role = payload.roleName?.trim()?.lowercase()
      val write = permission in WRITE_PERMISSIONS || (role != null && role in WRITE_PERMISSIONS)
      if (write) return@use true
      // Not write. Only a private repo can still qualify, and only on a real (non-`none`) grant.
      val readish = permission != "none" || (role != null && role != "none")
      readish && !isPublicRepository(token, repository)
    }
  }

  /** The repo payload, or null when it can't be read — which denies, the safe side. */
  private fun repositoryView(token: String, repository: String): GitHubRepositoryResponse? {
    val request =
      Request.Builder()
        .url("https://api.github.com/repos/$repository")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return runCatching {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) return@use null
        JSON.decodeFromString(GitHubRepositoryResponse.serializer(), response.body.string())
      }
    }
      .getOrNull()
  }

  /** Whether [repository] is public; defaults to true on failure, the stricter branch. */
  private fun isPublicRepository(token: String, repository: String): Boolean {
    val request =
      Request.Builder()
        .url("https://api.github.com/repos/$repository")
        .header(HttpHeaders.Authorization, "Bearer $token")
        .header(HttpHeaders.Accept, "application/vnd.github+json")
        .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
          if (!response.isSuccessful) return@use true
          JSON.decodeFromString(GitHubRepositoryResponse.serializer(), response.body.string())
            .private != true
        }
      }
      .getOrDefault(true)
  }

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true }

    /** "Can push" values across the legacy `permission` and fine-grained `role_name` fields. */
    private val WRITE_PERMISSIONS = setOf("admin", "maintain", "write")

    /**
     * Identity for a verified installation token; brackets can't appear in a real GitHub login, so
     * it never collides.
     */
    const val INSTALLATION_LOGIN = "[app-installation]"
  }
}

@Serializable
private data class GitHubTokenResponse(
  @SerialName("access_token") val accessToken: String? = null,
  /** What the visitor actually granted, comma-separated — which can be more than was asked for. */
  val scope: String? = null,
)

private data class GitHubGrant(val accessToken: String, val scopes: Set<String>)

@Serializable
private data class GitHubAccessTokenBody(@SerialName("access_token") val accessToken: String)

/**
 * The token GitHub issued carries [extraScopes] beyond [ServeGithubAuth.ALLOWED_SCOPES], so sign-in
 * refused it. [revoked] says whether the visitor's authorization of this app was revoked with it.
 */
class GitHubGrantTooBroadException(val extraScopes: Set<String>, val revoked: Boolean) :
  IllegalStateException("GitHub granted more than sign-in allows: ${extraScopes.joinToString(" ")}")

@Serializable private data class GitHubUserResponse(val login: String)

@Serializable private data class GitHubMembershipResponse(val state: String? = null)

@Serializable
private data class GitHubPermissionResponse(
  val permission: String = "none",
  @SerialName("role_name") val roleName: String? = null,
)

/** Only [private] is read; absent means "couldn't tell", which resolves to public. */
@Serializable
private data class GitHubRepositoryResponse(
  val private: Boolean? = null,
  /** The *authenticated* user's access, as GitHub computes it. Absent on an anonymous read. */
  val permissions: GitHubRepositoryPermissions? = null,
)

/** GitHub's per-user permission flags on a repo payload. All default false: absent means no. */
@Serializable
private data class GitHubRepositoryPermissions(
  val admin: Boolean = false,
  val maintain: Boolean = false,
  val push: Boolean = false,
  val triage: Boolean = false,
  val pull: Boolean = false,
) {
  /** Write access — the bar on a public repo, where `pull` is true for all of GitHub. */
  fun write(): Boolean = admin || maintain || push

  /** Any real grant — the bar on a private repo, where even `pull` was a deliberate decision. */
  fun any(): Boolean = write() || triage || pull
}

private fun ApplicationCall.uriWithQuery(): String = request.uri

/** Every value sent for the cookie [name], across all `Cookie` headers, in the order received. */
private fun io.ktor.server.request.ApplicationRequest.cookieValues(name: String): List<String> =
  headers.getAll(HttpHeaders.Cookie).orEmpty().flatMap { header ->
    header.split(";").mapNotNull { raw ->
      val part = raw.trim()
      val idx = part.indexOf('=')
      if (idx > 0 && part.substring(0, idx) == name) part.substring(idx + 1) else null
    }
  }

/**
 * The cookie [name]'s value when the request carries exactly one, else null: two same-named cookies
 * (host-only and domain) are indistinguishable, so guessing is refused.
 */
private fun io.ktor.server.request.ApplicationRequest.soleCookieValue(name: String): String? =
  cookieValues(name).singleOrNull()

/**
 * Whether the request arrived over TLS, so cookies can be `secure`. The configured
 * `callbackBaseUrl` is authoritative; otherwise the request's view (`X-Forwarded-Proto` behind a
 * trusted proxy).
 */
internal fun isSecure(
  call: ApplicationCall,
  callbackBaseUrl: String? = null,
  trustForwardedHeaders: Boolean = false,
): Boolean =
  callbackBaseUrl?.trim()?.takeIf { it.isNotEmpty() }?.startsWith("https://", ignoreCase = true)
    ?: externalOrigin(call, trustForwardedHeaders).startsWith("https://", ignoreCase = true)

/**
 * The externally visible hostname, normalised for site-host comparison: `X-Forwarded-Host` when
 * [trustForwardedHeaders], else `Host`. Null for a non-hostname, which matches no site.
 */
internal fun requestHost(call: ApplicationCall, trustForwardedHeaders: Boolean = false): String? {
  val forwarded = forwardedHeader(call, "X-Forwarded-Host", trustForwardedHeaders)
  val raw = forwarded ?: call.request.headers[HttpHeaders.Host]
  return raw?.let { ServeSites.normalizeHost(it) }
}

/**
 * First value of an `X-Forwarded-*` header, only when [trustForwardedHeaders]; a direct request
 * chooses these itself.
 */
internal fun forwardedHeader(
  call: ApplicationCall,
  name: String,
  trustForwardedHeaders: Boolean,
): String? =
  if (!trustForwardedHeaders) null
  else call.request.headers[name]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }

private fun externalOrigin(call: ApplicationCall, trustForwardedHeaders: Boolean): String {
  val proto =
    forwardedHeader(call, "X-Forwarded-Proto", trustForwardedHeaders)?.takeIf {
      it.equals("http", true) || it.equals("https", true)
    } ?: call.request.origin.scheme
  val host = forwardedHeader(call, "X-Forwarded-Host", trustForwardedHeaders) ?: call.request.host()
  return "${proto.lowercase()}://$host"
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
