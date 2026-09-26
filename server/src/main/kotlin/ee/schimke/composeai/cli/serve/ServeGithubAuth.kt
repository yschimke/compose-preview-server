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
   * A **second** repository whose access this session should also record, for a lane gated on
   * something other than [repository] — today the image lane's `--image-upload-repo`.
   *
   * The session cookie is the only thing an approval page has to reason with: the visitor's token
   * is deliberately not retained, so a bit that was not computed at sign-in can never be recovered
   * later. One boolean therefore speaks for exactly one repository, and a lane gated elsewhere used
   * to have no honest answer available at all — which is why the combination was refused at
   * startup. Computing the second bit here, from the same token and in the same round trip that
   * computes the first, is what lets the two gates differ.
   *
   * Null, or equal to [repository], means there is no second bit to compute: the flag mirrors
   * [GitHubOAuthUser.repositoryAccess] and no extra GitHub call is made.
   *
   * It never widens the consent asked for at sign-in: the token only ever carries
   * [ServeGithubAuth.USER_SCOPE], so a **private** repository here reads as no access at all. See
   * [ServeGithubAuth.requestedScope].
   */
  val imageRepository: String? = null,
  val allowedUsers: Set<String> = emptySet(),
  /**
   * `--github-auth-orgs`: GitHub organizations whose members are admitted as **members**, exactly
   * as if each were named in [allowedUsers]. It is the way to open a deployment to a whole team
   * without maintaining a login list — `google` on preview.coo.ee — while every other account still
   * lands as a guest (with [allowGuests]) or is refused.
   *
   * Membership is read once, at sign-in, with the visitor's own token, and baked into the session
   * like every other bit here: it lasts until the session's absolute cap, so somebody who leaves
   * the org keeps access for at most that long. Asking needs `read:org`, which
   * [ServeGithubAuth.requestedScope] adds whenever this is non-empty. A private membership is only
   * visible when the org allows this OAuth app; a public one always is — see
   * [GitHubOAuthVerifier.isOrgMember].
   */
  val allowedOrgs: Set<String> = emptySet(),
  /**
   * `--github-auth-guests`: let a GitHub account outside [allowedUsers] sign in anyway, as a
   * **guest**.
   *
   * A guest is deliberately invisible to every existing gate: [ServeGithubAuth.currentLogin]
   * answers null for one, so live sessions, the playground, image uploads, edit leases and grant
   * approval all treat a guest exactly as they treat an anonymous visitor. The one thing a guest
   * session carries is an identity — [ServeGithubAuth.currentSignedInLogin] — which the UI builder
   * reads to let the account see the designs shared with it, read-only, and to ask for more.
   *
   * No repository access is looked up for a guest, so none can be lent by one. Without an allowlist
   * every account is already a member, and this has nothing to do.
   */
  val allowGuests: Boolean = false,
  val callbackBaseUrl: String? = null,
  /**
   * The domain the auth cookies are written for, so **one sign-in covers a parent host and every
   * top-level site under it** — set `preview.coo.ee` and a session established anywhere in the
   * family is valid on `m3.preview.coo.ee` too.
   *
   * This is what makes a pinned callback work from a site host at all. Without it the cookies are
   * host-only: the `cp_gh_state` cookie written on the site host is not sent to the pinned callback
   * origin, so the CSRF check there sees nothing and answers 401, and a session cookie set at the
   * callback would be scoped to the wrong host anyway.
   *
   * Null (the default) keeps cookies host-only, which is right for a single-hostname box and is the
   * only safe default: it must be the operator's explicit choice, never derived from the request's
   * own `Host`, or an attacker-supplied header could widen the scope of a session cookie.
   *
   * **Every host under this domain is inside the session's blast radius**, so it must cover only
   * hosts this deployment controls. A registrable public suffix (`co.uk`, or a bare `com`) is
   * refused outright; beyond that the operator is trusted to know what lives under their own name.
   */
  val cookieDomain: String? = null,
  /**
   * Overrides the OAuth scope. Null (the default) asks for [ServeGithubAuth.USER_SCOPE] — see
   * [ServeGithubAuth.requestedScope]. Only the read-only identity scopes in
   * [ServeGithubAuth.ALLOWED_SCOPES] are accepted; anything that reaches repositories (`repo`,
   * `public_repo`, …) or writes is refused at startup.
   */
  val oauthScope: String? = null,
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
      // A single-label domain (`com`, `localhost`) or a two-label public suffix (`co.uk`) would ask
      // the browser to scope the session across a whole registry. Browsers reject that, so the
      // sign-in would fail at the Set-Cookie rather than here — which is a much worse place to find
      // out. Two labels is the floor, and the known multi-part suffixes are named out.
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
     * Two-label names that are registries rather than registrable domains, so a cookie may not span
     * them. Not a full public-suffix list — that is a large, churning dataset and a `serve` box
     * carries no copy of it. These are the ones a plausible typo lands on; anything past them is
     * the operator's own name to reason about.
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
   * `GET /auth/github/start`. [siteHosts] are the top-level site hostnames this server answers for
   * ([ServeSites.hosts]) — the **only** hosts a sign-in may be returned to, and the list is checked
   * again before [handleCallback] issues that redirect. Empty (the default) means the sign-in ends
   * where it started, which is exactly what this did before sites existed.
   */
  suspend fun RoutingContext.handleStart(siteHosts: Set<String> = emptySet()) {
    val returnTo = safeReturnTo(call.request.queryParameters["return"] ?: "/")
    // Null on the ordinary same-host sign-in, which is then byte-for-byte what it always was.
    val originHost = originHostFor(call, siteHosts)
    val state = signedState(nonce(), returnTo, originHost)
    call.response.cookies.append(
      stateCookie(
        state,
        maxAge = STATE_TTL_SECONDS,
        secure = isSecure(call, config.callbackBaseUrl),
      )
    )
    call.respondRedirect(authorizeUrl(call, state))
  }

  /**
   * `GET /auth/github/callback` — GitHub's return leg, which on a pinned box always lands on the
   * pinned origin whatever host the visitor started from.
   *
   * Two shapes end up here, and they differ only in where the visitor is sent afterwards. A
   * **same-host** sign-in (no origin host in the state) returns to a relative path, as it always
   * did. A **cross-host** sign-in — started on a top-level site — returns to an absolute URL on
   * that site, so the visitor lands back where they were reading rather than being quietly moved to
   * the pinned origin.
   *
   * **The CSRF check is unchanged**, and that is the point of doing this with a cookie domain
   * rather than a token handoff: with [ServeGithubAuthConfig.cookieDomain] set, `cp_gh_state` is
   * written for the parent domain, so it is sent to the pinned callback host too and can be
   * compared here exactly as it always was. All the cross-host leg adds is *where to go back to* —
   * a redirect target, not a credential.
   *
   * The session cookie is likewise written for the parent domain, so one sign-in is already valid
   * on every site host under it. There is nothing to hand over.
   */
  suspend fun RoutingContext.handleCallback(siteHosts: Set<String> = emptySet()) {
    val state = call.request.queryParameters["state"].orEmpty()
    val expected = call.request.cookieValue(STATE_COOKIE).orEmpty()
    val code = call.request.queryParameters["code"].orEmpty()
    val statePayload = verifyState(state)
    if (
      state.isBlank() || code.isBlank() || statePayload == null || !tokensMatch(state, expected)
    ) {
      call.respondText("GitHub sign-in failed.", status = HttpStatusCode.Unauthorized)
      return
    }
    // Where the visitor started, when that was a different host to this one. Re-validated against
    // the live site list rather than trusted from [handleStart]: the signature proves this server
    // minted the state, not that the host is still one of ours, and an unchecked value here is an
    // open redirect off the back of a real sign-in. Anything unrecognised falls back to a
    // same-origin relative return, which is where this route always sent people.
    val returnHost = statePayload.originHost?.takeIf { it in siteHosts && withinCookieDomain(it) }
    val secure = isSecure(call, config.callbackBaseUrl)
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
    call.respondRedirect(
      if (returnHost == null) statePayload.returnTo
      else "https://$returnHost${statePayload.returnTo}"
    )
  }

  /**
   * The visitor's GitHub authorization for this app carries more than [ALLOWED_SCOPES] — almost
   * always a `repo` grant approved back when sign-in still asked for it. GitHub keeps handing a
   * returning visitor the scopes they approved before, so asking for less does not shrink it.
   *
   * [GitHubOAuthVerifier.verify] has already tried to revoke that whole authorization. When it
   * could, the visitor is sent straight back through sign-in, where GitHub shows a fresh consent
   * screen for [requestedScope] alone. That happens once: a [REGRANT_COOKIE] marks the retry, so an
   * authorization that somehow comes back broad again is explained instead of looping. When the
   * revoke failed, the visitor is told how to remove it themselves.
   */
  private suspend fun RoutingContext.respondToTooBroadGrant(
    failure: GitHubGrantTooBroadException,
    returnTo: String,
    returnHost: String?,
    secure: Boolean,
  ) {
    val alreadyRetried = call.request.cookieValue(REGRANT_COOKIE) != null
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
   * `POST /auth/github/logout` — the eject button. Overwrites `cp_gh_auth` with an empty,
   * already-expired cookie and sends the visitor back to [ServeGithubAuth.safeReturnTo] of the
   * `return` query parameter.
   *
   * **`POST`, never `GET`.** A sign-out reachable by following a URL is one a prefetcher, a
   * link-unfurler or an `<img src>` on somebody else's page can fire — annoying rather than
   * dangerous, but the approval flow is a POST for the same reason and there is no cause to be
   * looser here. The return target rides in the query string rather than the body so this stays a
   * two-line handler with nothing to parse: the form's `action` carries it, and only a
   * same-origin-relative path survives [safeReturnTo].
   *
   * The cookie is cleared **twice over**, deliberately. `maxAge = 0` asks the browser to drop it,
   * and it is written through the same [sessionCookie] builder the session itself uses — same
   * `path`, same `domain`, same `secure` — because a deletion whose attributes do not match the
   * cookie's is a second cookie the browser stores beside the first rather than a deletion. The
   * empty **value** is the belt to that braces: whatever the browser makes of the attributes, an
   * empty string carries no signature, so [verifySession] rejects it and this server treats the
   * visitor as signed out from the next request onward.
   *
   * What this cannot do is revoke: the cookie is stateless and self-signed, so a copy taken off the
   * wire is unaffected. This ends *a browser's* session, which is the affordance that was missing;
   * server-side revocation is a different design
   * ([#280](https://github.com/yschimke/compose-preview-server/issues/280)).
   */
  suspend fun RoutingContext.handleLogout() {
    val returnTo = safeReturnTo(call.request.queryParameters["return"] ?: "/")
    call.response.cookies.append(
      authCookie("", maxAge = 0, secure = isSecure(call, config.callbackBaseUrl))
    )
    call.respondRedirect(returnTo)
  }

  /**
   * Whether a sign-in started on [rawHost] can actually come back to it.
   *
   * True when the callback isn't pinned (it is derived from the request, so it never leaves the
   * host), when this *is* the pinned host, or when [rawHost] is a configured site host that the
   * session cookie's domain covers — the case this exists for. False for a site outside the cookie
   * domain: the cookies written at the callback would not be sent to it, so a sign-in started there
   * would appear to succeed and land the visitor back signed-out. [ServeHttpServer] reads this to
   * decide whether to offer the sign-in affordance at all.
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
   * Slide a still-valid session forward, so an active visitor stays signed in instead of being
   * bounced through GitHub on a fixed cadence — but never past the absolute cap stamped at sign-in.
   *
   * The session is a self-contained signed cookie: there is no server-side store to touch and the
   * access token is deliberately not kept, so the only way to extend one is to mint a fresh cookie
   * carrying a later expiry, and there is nothing to re-ask GitHub with at refresh time. That is
   * precisely why the cap exists. A refreshed cookie copies the `repositoryAccess` flag GitHub
   * computed at sign-in, and that flag is the playground gate; without a ceiling, somebody whose
   * access to the gating repo was revoked would keep it for as long as they kept visiting —
   * forever, for a daily visitor. [SESSION_ABSOLUTE_TTL_SECONDS] from [handleCallback] is the
   * ceiling, and reaching it costs the visitor one silent redirect through GitHub (an OAuth app
   * they have already approved re-authorises without a consent screen) which re-computes the flag.
   *
   * So: idle expiry [SESSION_TTL_SECONDS] slides on every visit past its half-life
   * ([SESSION_REFRESH_AFTER_SECONDS]); the absolute expiry never moves. Under the half-life, or
   * once the cap is reached, this does nothing at all — an ordinary page view sets no cookie.
   *
   * Skipped on the OAuth routes: [handleCallback] mints the authoritative cookie itself, and a
   * second `Set-Cookie` for the same name in one response is a coin flip between them.
   */
  fun refreshSession(call: ApplicationCall) {
    if (call.request.uri.substringBefore('?').startsWith(AUTH_PATH_PREFIX)) return
    val session = call.request.cookieValue(AUTH_COOKIE)?.let { verifySession(it) } ?: return
    val now = clock.millis()
    if (session.expiresAt - now > SESSION_REFRESH_AFTER_SECONDS * 1000) return
    // Legacy cookies (minted before the sign-in stamp existed) carry no anchor, so they cannot be
    // shown to be inside the cap and are left to expire on their own terms.
    val authenticatedAt = session.authenticatedAt ?: return
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
        secure = isSecure(call, config.callbackBaseUrl),
      )
    )
  }

  /**
   * The signed-in **member** — an account the allowlist admits, or any account when there is none.
   * Null for a guest ([ServeGithubAuthConfig.allowGuests]), which is what keeps every gate written
   * against this closed to one.
   */
  fun currentLogin(call: ApplicationCall): String? {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return null
    return verifySession(cookie)?.takeIf { !it.guest }?.login
  }

  /**
   * Whoever GitHub vouched for, member or guest — an identity, never a permission. Read only where
   * a guest is meant to count: the UI builder's read-only access and the page chrome that says who
   * is signed in.
   */
  fun currentSignedInLogin(call: ApplicationCall): String? {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return null
    return verifySession(cookie)?.login
  }

  /** True when the session belongs to a guest rather than a member. */
  fun isGuest(call: ApplicationCall): Boolean {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return false
    return verifySession(cookie)?.guest == true
  }

  fun hasRepositoryAccess(call: ApplicationCall): Boolean {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return false
    return verifySession(cookie)?.let { it.repositoryAccess && !it.guest } == true
  }

  /**
   * Access to the repository the **image lane** gates on, which is [accessRepository] unless the
   * operator pointed `--image-upload-repo` somewhere else. This is what decides whether a signed-in
   * approver may pass `images` on to an agent — the "never grant what you do not hold" rule, asked
   * of the repository the grant would actually publish to rather than of the sign-in one.
   */
  fun hasImageRepositoryAccess(call: ApplicationCall): Boolean {
    val cookie = call.request.cookieValue(AUTH_COOKIE) ?: return false
    return hasImageRepositoryAccess(cookie)
  }

  /** [hasImageRepositoryAccess] on a raw cookie value, so the rule can be tested without a call. */
  internal fun hasImageRepositoryAccess(cookie: String): Boolean {
    val session = verifySession(cookie) ?: return false
    // A cookie minted before this field existed carries no image bit, and [verifySession] reads
    // that absence as `false` rather than as a copy of the sign-in bit — correctly, because it
    // cannot know which repository the signing server asked about. THIS layer can: when the box
    // gates both lanes on one repository, the sign-in bit was computed against exactly the
    // repository being asked about, so reading it here is the same question answered, not the
    // conflation the field exists to end. Without this, deploying the field would refuse every
    // live session's uploads on a single-repo box until each visitor happened to sign in again.
    return session.imageRepositoryAccess ||
      (imageGatesOnSignInRepository && session.repositoryAccess)
  }

  /**
   * Whether the image lane gates on the sign-in repository — either because no separate
   * `--image-upload-repo` was given, or because it names the same repository.
   */
  private val imageGatesOnSignInRepository: Boolean
    get() =
      config.imageRepository.isNullOrBlank() ||
        config.imageRepository.equals(config.repository, ignoreCase = true)

  fun loginPath(call: ApplicationCall): String {
    val current = call.uriWithQuery()
    return "$START_PATH?return=${urlEncode(current)}"
  }

  /**
   * Where a "Sign out" form posts to, returning the visitor to the page they were on. Mirrors
   * [loginPath] exactly — same `return` parameter, same encoding — so signing out of `/playground`
   * lands back on `/playground` (anonymously) rather than at the index.
   */
  fun logoutPath(call: ApplicationCall): String {
    val current = call.uriWithQuery()
    return "$LOGOUT_PATH?return=${urlEncode(current)}"
  }

  fun accessRepository(): String = config.repository

  /**
   * The repository [hasImageRepositoryAccess] speaks for: `--image-upload-repo` when the operator
   * pointed the image lane somewhere else, else the sign-in repository it falls back to. A caller
   * comparing a lane's gating repository against a session's verdict must compare against THIS, not
   * against [accessRepository] — that is the whole difference between the two bits.
   */
  fun imageAccessRepository(): String =
    config.imageRepository?.takeIf { it.isNotBlank() } ?: config.repository

  fun isRestrictedToAllowedUsers(): Boolean = config.restrictsMembership

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
   * The OAuth scope to ask a visitor to consent to: who they are, and nothing about their
   * repositories.
   *
   * This used to be `read:user repo` whenever the gating repo was private — or merely *looked*
   * private to an anonymous probe that a rate limit or network blip could fail. `repo` is GitHub's
   * *full control of private repositories*: read and write, code, issues and settings, across every
   * private repo the visitor can reach. Asking every signer-in for that to answer one question
   * about one repository is far more than sign-in needs, so it is never asked for.
   *
   * [USER_SCOPE] is enough for everything the callback does: `GET /user` for the login, and `GET
   * /repos/{owner}/{repo}` ([GitHubOAuthVerifier.fetchRepositoryAccess]), which reports the
   * visitor's own `permissions` on a **public** repo to a token with no repository scope at all. A
   * private gating repo is invisible to such a token, so it simply grants no access — the safe side
   * for a gate on running code.
   *
   * `read:org` is added when `--github-auth-orgs` is set, so a private org membership can be read;
   * it is read-only and names nothing about repositories. [ServeGithubAuthConfig.oauthScope]
   * replaces the base, but only with scopes from [ALLOWED_SCOPES].
   */
  internal fun requestedScope(): String {
    val base = config.oauthScope?.trim()?.takeIf { it.isNotEmpty() } ?: USER_SCOPE
    val needsOrg = config.allowedOrgs.isNotEmpty() && ORG_SCOPE !in base.split(' ', ',')
    return if (needsOrg) "$base $ORG_SCOPE" else base
  }

  /**
   * Whether the OAuth callback is pinned to one origin (`--github-auth-callback-base-url`) rather
   * than derived from the request. A pinned callback means the return leg always lands on that one
   * origin, whatever host the visitor started from — which is why a sign-in begun on a top-level
   * site has to be handed back to it ([handleComplete]) instead of finishing at the callback. Use
   * [canRoundTrip] to ask whether a given host's sign-in can complete; this is the raw setting.
   */
  val hasPinnedCallback: Boolean
    get() = !config.callbackBaseUrl.isNullOrBlank()

  private fun callbackUrl(call: ApplicationCall?): String =
    config.callbackBaseUrl?.trimEnd('/')?.plus(CALLBACK_PATH)
      ?: call?.let { externalOrigin(it) + CALLBACK_PATH }
      ?: CALLBACK_PATH

  /**
   * The host to send the visitor back to once the callback has run, or null when the sign-in ends
   * where it started (the ordinary case: no pinned callback, or already on the pinned origin).
   *
   * Only a configured site host the cookie domain covers is ever returned. That is the allowlist
   * which keeps the state's origin field from becoming an open redirect, and it is applied again in
   * [handleCallback], because between the two legs the value has been round-tripped through the
   * client.
   */
  private fun originHostFor(call: ApplicationCall, siteHosts: Set<String>): String? {
    if (!hasPinnedCallback || siteHosts.isEmpty()) return null
    val host = requestHost(call) ?: return null
    if (host == pinnedCallbackHost()) return null
    return host.takeIf { it in siteHosts && withinCookieDomain(it) }
  }

  /**
   * Whether a cookie written for [ServeGithubAuthConfig.cookieDomain] would actually be sent to
   * [host] — it is the domain itself, or a subdomain of it. No cookie domain configured ⇒ cookies
   * are host-only, so nothing but the host that set them qualifies.
   *
   * The dot matters: a naive `endsWith` would read `notpreview.coo.ee` as being under
   * `preview.coo.ee` and offer a sign-in that silently can't work.
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
   * `nonce|originHost|returnTo`, with an empty origin host on the same-host flow. The nonce is
   * base64url and the origin host is a validated hostname, so neither can contain the separator and
   * `returnTo` keeps its rest-of-string reading — which matters, since a return path may carry a
   * query string containing anything at all.
   */
  private fun signedState(nonce: String, returnTo: String, originHost: String?): String =
    sign("$nonce|${originHost.orEmpty()}|$returnTo")

  private fun verifyState(value: String): StatePayload? {
    val payload = verifySigned(value) ?: return null
    val firstPipe = payload.indexOf('|')
    if (firstPipe <= 0) return null
    val nonce = payload.substring(0, firstPipe)
    val rest = payload.substring(firstPipe + 1)
    val secondPipe = rest.indexOf('|')
    // No second separator ⇒ a state minted before handoff existed: the remainder is the return path
    // and there is no origin host. Kept so sign-ins already in flight across a restart still land.
    if (secondPipe < 0) return StatePayload(nonce, safeReturnTo(rest), null)
    val originField = rest.substring(0, secondPipe)
    val originHost = originField.takeIf { it.isNotEmpty() }?.let { ServeSites.normalizeHost(it) }
    // A non-empty middle field that isn't a hostname means this is the legacy shape after all and
    // the return path simply contained a separator — read it whole rather than truncating it.
    if (originField.isNotEmpty() && originHost == null) {
      return StatePayload(nonce, safeReturnTo(rest), null)
    }
    return StatePayload(nonce, safeReturnTo(rest.substring(secondPipe + 1)), originHost)
  }

  /**
   * The image flag is **appended**, never interleaved: every older cookie shape stays parseable by
   * [verifySession] on its own terms, so a running box does not sign everybody out to gain a field.
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
    val payload = "${login.lowercase()}|$repoFlag|$expiresAt|$authenticatedAt|$imageFlag"
    // Appended, like every field before it, and only for a guest: a member's cookie keeps the
    // five-part shape older servers already read, so rolling this out signs nobody out.
    return sign(if (guest) "$payload|$GUEST_FLAG" else payload)
  }

  private fun verifySession(value: String): SessionPayload? {
    val payload = verifySigned(value) ?: return null
    val parts = payload.split("|")
    val (login, repositoryAccess, expiresAt) =
      when (parts.size) {
        // Backwards compatible with cookies minted before playground repo-rights gating. They stay
        // authenticated for live preview, but do not satisfy the stricter playground gate.
        2 -> Triple(parts[0], false, parts[1].toLongOrNull())
        // One branch for every shape from the 3-part form on: each later field was APPENDED, so
        // the login, the repo flag and the expiry have never moved. The trailing fields the newer
        // shapes carry are read below, by index, where absence has to mean something specific.
        3,
        4,
        5,
        6 -> Triple(parts[0], parts[1] == "repo", parts[2].toLongOrNull())
        else -> return null
      }
    if (expiresAt == null || expiresAt <= clock.millis() || login.isBlank()) return null
    // Absent on the 2- and 3-part forms: those predate the sign-in stamp, so their session simply
    // can't be slid ([refreshSession]) and runs out at its own expiry.
    val authenticatedAt = parts.getOrNull(3)?.toLongOrNull()
    // Absent on every shape minted before the image lane could gate on a second repository. Read as
    // FALSE rather than as a copy of [repositoryAccess]: an older cookie was signed by a server
    // that never asked GitHub about the image repository, so treating its one bit as an answer
    // about a repository it never named is exactly the conflation this field exists to end. The
    // cost is that a visitor holding such a cookie cannot pass on `images` until it is refreshed
    // through GitHub, which the absolute cap guarantees.
    val imageRepositoryAccess = parts.getOrNull(4) == "image-repo"
    // A guest cookie is only ever minted while guests are allowed. One signed before the operator
    // turned them off again is refused outright rather than read as a member, which it never was.
    val guest = parts.getOrNull(5) == GUEST_FLAG
    if (guest && !config.allowGuests) return null
    return SessionPayload(
      login,
      repositoryAccess && !guest,
      imageRepositoryAccess && !guest,
      expiresAt,
      authenticatedAt,
      guest,
    )
  }

  private fun sign(payload: String): String {
    val bytes = payload.toByteArray(Charsets.UTF_8)
    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    val sig = hmac(encoded)
    return "$encoded.$sig"
  }

  private fun verifySigned(value: String): String? {
    val parts = value.split(".", limit = 2)
    if (parts.size != 2 || !tokensMatch(hmac(parts[0]), parts[1])) return null
    return runCatching { Base64.getUrlDecoder().decode(parts[0]).toString(Charsets.UTF_8) }
      .getOrNull()
  }

  private fun hmac(value: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(config.cookieSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray()))
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
   * [secure] is derived per request rather than hardcoded: a deployment terminates TLS at Caddy and
   * must not hand the session cookie back over a plaintext downgrade, while `serve` on
   * `http://localhost` would never see the cookie again if it were always set. See [isSecure].
   */
  private fun sessionCookie(name: String, value: String, maxAge: Long, secure: Boolean): Cookie =
    Cookie(
      name = name,
      value = value,
      path = "/",
      maxAge = maxAge.toInt(),
      // Null ⇒ no Domain attribute ⇒ host-only, the default and the single-hostname behaviour.
      // Set, it scopes the cookie to the parent domain and every site host under it, which is what
      // lets one sign-in cover preview.coo.ee and m3.preview.coo.ee alike. Always the operator's
      // configured value, never anything derived from the request.
      domain = cookieDomain,
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
    /** When GitHub last vouched for this visitor; null on a cookie minted before the stamp. */
    val authenticatedAt: Long?,
    /** Signed in outside the allowlist; see [ServeGithubAuthConfig.allowGuests]. */
    val guest: Boolean = false,
  )

  companion object {
    const val START_PATH = "/auth/github/start"
    const val CALLBACK_PATH = "/auth/github/callback"

    /**
     * `POST`-only, and under [AUTH_PATH_PREFIX] so [refreshSession] leaves it alone — otherwise a
     * sign-out response would carry a freshly-minted session cookie beside the expired one.
     */
    const val LOGOUT_PATH = "/auth/github/logout"

    private const val AUTH_COOKIE = "cp_gh_auth"

    /** The sixth session field, present only on a guest cookie. */
    private const val GUEST_FLAG = "guest"
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
     * Every scope sign-in may ask for, override included: read-only identity, nothing that reaches
     * a repository.
     */
    val ALLOWED_SCOPES: Set<String> = setOf(USER_SCOPE, "user:email", ORG_SCOPE)

    /** Both OAuth routes, so [refreshSession] can leave the cookie-minting ones alone. */
    private const val AUTH_PATH_PREFIX = "/auth/github/"

    private const val STATE_TTL_SECONDS = 10L * 60

    /**
     * The **idle** expiry: how long a session survives without a visit. [refreshSession] slides it
     * forward on each visit, up to [SESSION_ABSOLUTE_TTL_SECONDS].
     *
     * This was 12 hours *absolute*, which is shorter than the gap between one working day and the
     * next: a visitor who signed in yesterday afternoon was reliably signed out this morning, and
     * the server is a preview gallery people drop into occasionally, not a console they live in. A
     * week of idle means the normal rhythm of visiting — daily, or on Monday after a quiet weekend
     * — never lands on a sign-in.
     */
    internal const val SESSION_TTL_SECONDS = 7L * 24 * 60 * 60

    /**
     * The **absolute** expiry, measured from the sign-in itself and never extended: however
     * regularly someone visits, GitHub gets asked about them again this often.
     *
     * It is the ceiling on how stale the cached `repositoryAccess` flag — the playground gate — can
     * be, so revoking someone's access to the gating repo closes the playground behind them within
     * a fortnight rather than never. That is what makes the sliding idle expiry above safe: an
     * entitlement decided once at sign-in cannot ride along indefinitely on the strength of the
     * visitor simply continuing to visit.
     *
     * Reaching it is cheap for the visitor: an OAuth app they have already approved re-authorises
     * without a consent screen, so it reads as a page load rather than a sign-in.
     */
    internal const val SESSION_ABSOLUTE_TTL_SECONDS = 14L * 24 * 60 * 60

    /**
     * Sessions are re-minted once their idle expiry is this close (half of [SESSION_TTL_SECONDS]).
     * Half-life rather than every request: the cookie is only worth rewriting when the extension is
     * meaningful, and a `Set-Cookie` on every page view is noise on responses that are otherwise
     * identical.
     */
    internal const val SESSION_REFRESH_AFTER_SECONDS = SESSION_TTL_SECONDS / 2
    private val SECURE_RANDOM = SecureRandom()

    fun safeReturnTo(value: String): String =
      if (value.startsWith("/") && !value.startsWith("//")) value else "/"

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
   * Access to [ServeGithubAuthConfig.imageRepository], when the box gates a lane on a repository
   * other than the sign-in one. Defaults to [repositoryAccess], which is the answer whenever the
   * two repositories are the same — or when nobody asked about a second one.
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
      // Same token, same rule, one more round trip — and only when the operator actually gated a
      // lane somewhere else. This is the only moment the visitor's token exists here, so a bit not
      // taken now is a bit that cannot be taken at all.
      imageRepositoryAccess =
        config.imageRepository
          ?.takeIf { !it.equals(config.repository, ignoreCase = true) }
          ?.let { fetchRepositoryAccess(token, it, login) } ?: repositoryAccess,
    )
  }

  /**
   * The same identity + access decision as [verify], for a caller who **already holds a GitHub
   * token** — no OAuth code to exchange, and so no client id/secret needed at all.
   *
   * This is the headless half of the same gate: a browser earns its session through [verify]'s
   * redirect flow, while an agent presents the token it was issued (`GITHUB_TOKEN`, `gh auth
   * token`) and is measured against exactly the same bar — [allowedUsers] when the operator
   * narrowed sign-in, then [fetchRepositoryAccess]'s public-vs-private rule. Keeping both on one
   * code path is the point: a second, subtly different notion of "has access" is how a gate ends up
   * admitting people one of its doors was meant to refuse.
   *
   * The token is used for the two reads and dropped; nothing here retains or logs it.
   */
  fun verifyAccessToken(
    token: String,
    repository: String,
    allowedUsers: Set<String> = emptySet(),
    allowedOrgs: Set<String> = emptySet(),
  ): Result<GitHubOAuthUser> = runCatching {
    // Null when `/user` refuses the credential, which is the normal answer for a GitHub **App
    // installation** token rather than a sign of a bad one — see [verifyInstallationToken].
    val login = runCatching { fetchLogin(token) }.getOrNull()
    if (login == null) {
      return@runCatching verifyInstallationToken(
        token,
        repository,
        restricted = allowedUsers.isNotEmpty() || allowedOrgs.isNotEmpty(),
      )
    }
    if (!isAdmitted(token, login, allowedUsers, allowedOrgs)) {
      error("GitHub user $login is not allowed")
    }
    GitHubOAuthUser(login, repositoryAccess = fetchRepositoryAccess(token, repository, login))
  }

  /**
   * The other kind of credential a CI caller actually holds: a **GitHub App installation token** —
   * what `${'$'}{{ github.token }}` / `GITHUB_TOKEN` is inside every GitHub Actions job.
   *
   * There is no user behind one, so `GET /user` answers `403 Resource not accessible by
   * integration` and the user path above can't decide anything about it. What it *can* do is read
   * the repositories its installation covers, and `GET /repos/{owner}/{repo}` reports the
   * installation's own `permissions` — which is the grant that matters here: a workflow token
   * carrying `contents: write` on the gating repo was deliberately given that by the repo's own
   * configuration.
   *
   * Two deliberate narrowings against the user path:
   * - **Write, always**, public or private. The private-repo "any real grant counts" reasoning is
   *   about a *person* somebody let in; a machine credential scoped by a workflow file is not that,
   *   and a read-only workflow token (what a fork's pull_request run gets) must not be able to post
   *   to the host.
   * - **Refused outright when the operator narrowed sign-in** with `--github-auth-users` or
   *   `--github-auth-orgs`. Those name people; an installation token is nobody on them, and
   *   silently admitting one would widen a gate whose whole point is to be narrow.
   *
   * The identity returned is [INSTALLATION_LOGIN] rather than a name, because there isn't one: an
   * installation token cannot read `GET /app` (that needs the app's JWT). The audit trail says
   * "some app installation with write on this repo", which is exactly what was verified.
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
   * Whether [login] is a member rather than a guest: no restriction at all, a name on
   * [allowedUsers], or membership of any of [allowedOrgs]. The login list is checked first so a
   * named user costs no GitHub round trip.
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
   * Whether [login] belongs to [org], asked two ways because GitHub answers them differently.
   *
   * `GET /user/memberships/orgs/{org}` sees a **private** membership, but only with `read:org` and
   * only when the org has not restricted third-party OAuth apps (or has approved this one) — a
   * large org such as `google` commonly has. `GET /orgs/{org}/public_members/{login}` sees only a
   * **public** membership, but needs no scope and no approval at all. Either saying yes is enough;
   * anything else — a 403 from an app restriction, a 404, a network failure — is a no, which is the
   * safe direction for a gate: the visitor lands as a guest and can ask for more.
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
   * Whether [login] has access to [repository] that means something — the gate on the playground,
   * which compiles and runs a stranger's Kotlin.
   *
   * What "means something" is depends on the repository's visibility, and #3313 got this half
   * right. On a **public** repo, `read` is what GitHub reports for *every* authenticated user,
   * because reading is what public means — so a read-level gate there admits the whole of GitHub,
   * which is the hole #3313 closed. On a **private** repo, `read` is the opposite: somebody
   * deliberately granted this person access to a repository nobody else can see. Requiring write
   * everywhere, as #3313 did, locked out read-only collaborators in the one case that was never
   * broken.
   *
   * So: public repo → require `admin` / `maintain` / `write`. Private repo → any permission other
   * than `none`, exactly as before #3313. One extra API call per sign-in, on a path that already
   * makes two.
   *
   * When visibility can't be determined the answer is **write**, the safe side: a token that can't
   * read the repo metadata tells us nothing that should widen a gate on code execution.
   */
  private fun fetchRepositoryAccess(token: String, repository: String, login: String): Boolean {
    // `GET /repos/{owner}/{repo}` answers both halves at once: `private` is the visibility, and
    // `permissions` is *this* user's access as GitHub computes it. That matters for scope as much
    // as for round-trips — this endpoint is readable on a public repo by a token carrying no repo
    // scope at all, where `/collaborators/{login}/permission` is not. See
    // [ServeGithubAuth.requestedScope].
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

  /**
   * Whether [repository] is public. Defaults to **true** when the lookup fails or the field is
   * absent — see [fetchRepositoryAccess]: public is the stricter branch, so an unknown visibility
   * falls back to requiring write rather than accepting a bare `read`.
   */
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

    /**
     * Values meaning "can push", across both the legacy `permission` field and the fine-grained
     * `role_name` one. `maintain` only ever appears in the latter today, but naming it in both
     * costs nothing and survives GitHub widening the legacy field.
     */
    private val WRITE_PERMISSIONS = setOf("admin", "maintain", "write")

    /**
     * The identity a verified **installation** token is attributed to. Not a login — no user is
     * behind one — and shaped so it can never collide with a real GitHub login, which cannot
     * contain a bracket.
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

private fun io.ktor.server.request.ApplicationRequest.cookieValue(name: String): String? =
  headers[HttpHeaders.Cookie]
    ?.split(";")
    ?.map { it.trim() }
    ?.firstNotNullOfOrNull { part ->
      val idx = part.indexOf('=')
      if (idx > 0 && part.substring(0, idx) == name) part.substring(idx + 1) else null
    }

/**
 * Whether this request reached us over TLS, so the cookies can be marked `secure`.
 *
 * The configured `callbackBaseUrl` is the authoritative answer where it exists — it is the operator
 * stating the public origin — and it is what a reverse-proxied deployment is told to set. Otherwise
 * fall back to the request's own view, which behind a proxy means `X-Forwarded-Proto`.
 */
internal fun isSecure(call: ApplicationCall, callbackBaseUrl: String? = null): Boolean =
  callbackBaseUrl?.trim()?.takeIf { it.isNotEmpty() }?.startsWith("https://", ignoreCase = true)
    ?: externalOrigin(call).startsWith("https://", ignoreCase = true)

/**
 * The externally visible hostname of this request, normalised for comparison against the configured
 * site hosts — `X-Forwarded-Host` first (Caddy sets it, and behind a proxy it is the only view of
 * the name the visitor typed), else `Host`. Null when what arrives isn't a hostname at all, so a
 * junk header matches no site and simply gets the pre-handoff behaviour.
 */
internal fun requestHost(call: ApplicationCall): String? {
  val forwarded = call.request.headers["X-Forwarded-Host"]?.substringBefore(',')?.trim()
  val raw = forwarded?.takeIf { it.isNotEmpty() } ?: call.request.headers[HttpHeaders.Host]
  return raw?.let { ServeSites.normalizeHost(it) }
}

private fun externalOrigin(call: ApplicationCall): String {
  val forwardedProto = call.request.headers["X-Forwarded-Proto"]?.substringBefore(",")?.trim()
  val forwardedHost = call.request.headers["X-Forwarded-Host"]?.substringBefore(",")?.trim()
  val proto = forwardedProto?.takeIf { it.isNotBlank() } ?: call.request.origin.scheme
  val host = forwardedHost?.takeIf { it.isNotBlank() } ?: call.request.host()
  return "$proto://$host"
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
