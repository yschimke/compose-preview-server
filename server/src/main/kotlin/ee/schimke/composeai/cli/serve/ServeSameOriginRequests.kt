package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod

/**
 * Which requests carrying an ambient browser credential are accepted from where.
 *
 * The session cookie is `SameSite=Lax`, so a browser attaches it to a request from any page on the
 * same *site*, and the cookie domain can make that site a whole family of hosts. The routes that
 * change state on the strength of that cookie therefore also ask the browser where the request came
 * from, and accept it only from a page this server served: this request's own host, or one of the
 * configured site hosts.
 *
 * Only the cookie is judged. A request carrying a header credential
 * ([ServeHttpServer.TOKEN_HEADER], [ServeHttpServer.ADMIN_TOKEN_HEADER] or `Authorization`) is a
 * machine client — the CLI, an agent, the MCP server, an editor extension — or a page that set the
 * header itself, which a page on another origin cannot do without a CORS approval this server never
 * gives. Neither is affected.
 */
internal object ServeSameOriginRequests {

  /** The browser's GitHub session cookie, as [ServeGithubAuth] writes it. */
  const val SESSION_COOKIE: String = "cp_gh_auth"

  /**
   * Whether this request was sent by a page on this server.
   *
   * `Sec-Fetch-Site` answers directly when the browser sent it. Otherwise `Origin` is compared with
   * the host the request was addressed to, and with the configured [siteHosts]. A request with
   * neither header is not from a browser page at all (curl, a script), and is left alone.
   */
  fun isSameOrigin(call: ApplicationCall, siteHosts: Set<String>): Boolean {
    val headers = call.request.headers
    val fetchSite = headers["Sec-Fetch-Site"]
    if (fetchSite == "same-origin" || fetchSite == "none") return true
    val origin = headers[HttpHeaders.Origin]
    if (origin != null) return originMatches(origin, call, siteHosts)
    return fetchSite == null
  }

  /**
   * True when this request is authenticated by the session cookie alone and did not come from a
   * page on this server — the requests the session-cookie routes refuse.
   */
  fun isForeignSessionRequest(call: ApplicationCall, siteHosts: Set<String>): Boolean =
    carriesSessionOnly(call) && !isSameOrigin(call, siteHosts)

  /**
   * True when a session-cookie request to a JSON route declares some other body type. A page's own
   * `fetch` always labels its JSON; a body a form or a no-CORS `fetch` can send (`text/plain`,
   * `application/x-www-form-urlencoded`, `multipart/form-data`) is not the JSON these routes read.
   */
  fun isNonJsonSessionRequest(call: ApplicationCall): Boolean =
    carriesSessionOnly(call) &&
      !runCatching { call.request.contentType().match(ContentType.Application.Json) }
        .getOrDefault(false)

  /**
   * Whether a request with this method changes state. A WebSocket upgrade is a `GET` that the
   * browser sends from any origin and whose frames the page can read, so it counts as well.
   */
  fun isStateChangingOrUpgrade(call: ApplicationCall): Boolean {
    val method = call.request.httpMethod
    if (method != HttpMethod.Get && method != HttpMethod.Head && method != HttpMethod.Options) {
      return true
    }
    return isWebSocketUpgrade(call)
  }

  fun isWebSocketUpgrade(call: ApplicationCall): Boolean =
    call.request.headers[HttpHeaders.Upgrade]?.equals("websocket", ignoreCase = true) == true

  /** A session or browser-auth cookie is present and no header credential is. */
  private fun carriesSessionOnly(call: ApplicationCall): Boolean {
    val request = call.request
    val cookies = request.cookies.rawCookies
    if (
      cookies[SESSION_COOKIE] == null &&
        cookies[ServeBrowseCookie.NAME] == null &&
        cookies[ServeAgentGrantCookie.NAME] == null
    ) {
      return false
    }
    val headers = request.headers
    return headers[ServeHttpServer.TOKEN_HEADER] == null &&
      headers[ServeHttpServer.ADMIN_TOKEN_HEADER] == null &&
      headers[HttpHeaders.Authorization] == null
  }

  private fun originMatches(
    origin: String,
    call: ApplicationCall,
    siteHosts: Set<String>,
  ): Boolean {
    // `Origin` is scheme://host[:port], or `null` from an opaque origin, which matches nothing.
    // Authorities are compared rather than full origins: behind a proxy that terminates TLS the
    // schemes legitimately differ.
    if (!origin.contains("://")) return false
    val authority = origin.substringAfter("://").substringBefore('/')
    if (authority.isEmpty()) return false
    val headers = call.request.headers
    val addressed =
      listOfNotNull(
        headers[HttpHeaders.Host],
        headers["X-Forwarded-Host"]?.substringBefore(',')?.trim(),
      )
    if (addressed.any { it.equals(authority, ignoreCase = true) }) return true
    val originHost = ServeSites.normalizeHost(authority) ?: return false
    return originHost in siteHosts
  }
}

/**
 * [ServeUiBuilderAuthorization] that refuses a session-cookie request from a page on another origin
 * when it changes state or opens a socket. Reads over plain `GET` are unchanged: a page elsewhere
 * cannot read their answers.
 */
internal fun ServeUiBuilderAuthorization.acceptingSessionWritesFromSameOrigin(
  siteHosts: () -> Set<String>
): ServeUiBuilderAuthorization {
  val delegate = this
  return ServeUiBuilderAuthorization { call, capability, presented ->
    if (
      ServeSameOriginRequests.isStateChangingOrUpgrade(call) &&
        ServeSameOriginRequests.isForeignSessionRequest(call, siteHosts())
    ) {
      UiBuilderAuthorizationDecision.Forbidden
    } else {
      delegate.authorize(call, capability, presented)
    }
  }
}
