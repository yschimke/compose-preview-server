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
  const val WASM_ACCESS: String = "agent-cookie"

  data class Exchange(
    val target: String,
    val credential: ServeAgentGrantStore.BrowserCredential,
  )

  /** A live grant cookie on [call], if any. */
  fun grant(
    call: ApplicationCall,
    store: ServeAgentGrantStore,
  ): ServeAgentGrantStore.Grant? =
    store.grantForBrowserCredential(call.request.cookies.rawCookies[NAME])

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
      target = path + ServeBrowseCookie.queryWithoutToken(call.request.queryString()),
      credential = credential,
    )
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
}
