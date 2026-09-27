package ee.schimke.composeai.cli.serve

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString

/**
 * A short-lived agent grant held by a browser without leaving its `cpat_` bearer in URLs or
 * JavaScript-visible state.
 *
 * A top-level navigation carrying a live grant is exchanged for this HttpOnly cookie and redirected
 * to the same URL without `token`. The cookie carries a non-reversible, domain-separated value;
 * [ServeAgentGrantStore] resolves it back to the original live grant, so actor, delegation, scope,
 * capabilities, design limits, revocation and expiry are exactly the bearer's rather than a copied
 * authorization policy.
 */
internal object ServeAgentGrantCookie {
  const val NAME: String = "cp_agent_grant"

  data class Exchange(
    val target: String,
    val credential: ServeAgentGrantStore.BrowserCredential,
    val grant: ServeAgentGrantStore.Grant,
  )

  /** A live grant cookie on [call], if any. */
  fun grant(
    call: ApplicationCall,
    store: ServeAgentGrantStore,
    siteHosts: Set<String> = emptySet(),
  ): ServeAgentGrantStore.Grant? {
    if (
      ServeSameOriginRequests.isStateChangingOrUpgrade(call) &&
        !ServeSameOriginRequests.isSameOrigin(call, siteHosts)
    ) {
      return null
    }
    return store.grantForBrowserCredential(call.request.cookies.rawCookies[NAME])
  }

  /**
   * Resolve a top-level browser navigation carrying exactly one live `cpat_` grant. Machine/API
   * requests, frames, admin routes, malformed tokens and expired grants fall through.
   */
  fun exchange(
    call: ApplicationCall,
    store: ServeAgentGrantStore,
  ): Exchange? {
    if (call.request.httpMethod != HttpMethod.Get) return null
    if (ServeSameOriginRequests.isWebSocketUpgrade(call)) return null
    if (!ServeBrowseCookie.isDocumentNavigation(call)) return null
    val path = call.request.path()
    if (path == "/admin" || path.startsWith("/admin/")) return null
    val presented = call.request.queryParameters.getAll("token") ?: return null
    if (presented.size != 1) return null
    val grant = store.grantForToken(presented.single()) ?: return null
    val credential = store.browserCredentialFor(grant) ?: return null
    return Exchange(
      target =
        ServeBrowseCookie.localRedirectPath(path) +
          ServeBrowseCookie.queryWithoutToken(call.request.queryString()),
      credential = credential,
      grant = grant,
    )
  }

  /**
   * The live grant this browser already holds under a *different* identity than [exchange]'s, if
   * any. A grant link opened in such a browser must not silently replace it: that is how someone
   * else's `cpat_` link would make this person's later work land under the sender's grant.
   */
  fun conflictingGrant(
    call: ApplicationCall,
    store: ServeAgentGrantStore,
    exchange: Exchange,
  ): ServeAgentGrantStore.Grant? {
    val current =
      store.grantForBrowserCredential(call.request.cookies.rawCookies[NAME]) ?: return null
    return current.takeIf { it.id != exchange.grant.id }
  }

  /**
   * [next], a same-origin path chosen by the confirmation form, made safe to emit as `Location`: a
   * single leading `/` (so `//host` and `/\host` cannot leave this origin), no control characters,
   * and never a `token` parameter.
   */
  fun safeLocalTarget(next: String?): String {
    if (next.isNullOrEmpty() || next.any { it.code < 0x20 || it.code == 0x7f }) return "/"
    val path = next.substringBefore('?')
    val query = if ('?' in next) next.substringAfter('?') else ""
    return ServeBrowseCookie.localRedirectPath(path) + ServeBrowseCookie.queryWithoutToken(query)
  }

  fun cookie(credential: ServeAgentGrantStore.BrowserCredential, secure: Boolean): Cookie =
    Cookie(
      name = NAME,
      value = credential.value,
      path = "/",
      maxAge = credential.maxAgeSeconds,
      secure = secure,
      httpOnly = true,
      encoding = CookieEncoding.RAW,
      extensions = mapOf("SameSite" to "Lax"),
    )

  fun clearedCookie(secure: Boolean): Cookie =
    Cookie(
      name = NAME,
      value = "",
      path = "/",
      maxAge = 0,
      secure = secure,
      httpOnly = true,
      encoding = CookieEncoding.RAW,
      extensions = mapOf("SameSite" to "Lax"),
    )
}
