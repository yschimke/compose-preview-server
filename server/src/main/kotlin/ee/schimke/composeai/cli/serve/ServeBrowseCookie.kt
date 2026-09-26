package ee.schimke.composeai.cli.serve

import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The browse token, held by a browser as a cookie instead of in every page URL.
 *
 * A browser opens a token-gated box from a `?token=` link once; the server answers that first page
 * load with this cookie and a redirect to the same URL without the token (see [exchangeTarget]).
 * From then on the browser presents the cookie on every same-origin request — page loads, `fetch`,
 * WebSocket upgrades — and the pages it is served carry no token in their links at all.
 *
 * The cookie never holds the token itself: its value is an HMAC of a fixed label keyed by the
 * token, so it is only ever as good as the token it was minted from (a new `--token` invalidates
 * every cookie), and it cannot be turned back into a token a header or query would accept.
 *
 * Machine clients are unaffected. A header or a query token on anything that is not a top-level
 * browser page load is authorised exactly as before and never redirected.
 */
internal object ServeBrowseCookie {
  const val NAME: String = "cp_browse"

  /**
   * Thirty days: the token it stands for does not expire, and re-opening a token link renews it.
   */
  private const val MAX_AGE_SECONDS: Int = 30 * 24 * 60 * 60

  private const val COOKIE_LABEL = "compose-preview/browse-cookie/v1"
  private const val WASM_ACCESS_LABEL = "compose-preview/wasm-private-access/v1"

  /** The cookie value for [serverToken]. */
  fun value(serverToken: String): String = derive(serverToken, COOKIE_LABEL)

  /**
   * The path segment `/wasm-private/{access}/…` carries for the operator: derived from the token
   * rather than the token itself, so the iframe URL a page embeds is good for those assets and
   * nothing else.
   */
  fun wasmAccess(serverToken: String): String = derive(serverToken, WASM_ACCESS_LABEL)

  /**
   * Whether [call] carries a cookie minted from [serverToken]. A state-changing request or a
   * WebSocket upgrade must also come from a page this server served: the cookie is sent by the
   * browser on its own, so it counts only where the request's origin says the page asked for it.
   */
  fun presents(call: ApplicationCall, serverToken: String): Boolean {
    if (serverToken.isBlank()) return false
    val presented = call.request.cookies.rawCookies[NAME] ?: return false
    if (!ServeUrls.tokensMatch(value(serverToken), presented)) return false
    return !ServeSameOriginRequests.isStateChangingOrUpgrade(call) ||
      ServeSameOriginRequests.isSameOrigin(call, emptySet())
  }

  fun cookie(serverToken: String, secure: Boolean): Cookie =
    Cookie(
      name = NAME,
      value = value(serverToken),
      path = "/",
      maxAge = MAX_AGE_SECONDS,
      secure = secure,
      httpOnly = true,
      encoding = CookieEncoding.RAW,
      // Lax rather than Strict: a `?token=` link followed from another site (a PR comment, a chat
      // message) must still land, and under Strict the redirect that ends the exchange would
      // arrive without the cookie it just set.
      extensions = mapOf("SameSite" to "Lax"),
    )

  /**
   * Where to send a browser that opened a page with [serverToken] in its query — the same path and
   * query with `token` removed — or null when this request is not that exchange.
   *
   * Only a top-level `GET` document load qualifies: `Sec-Fetch-Mode: navigate` with a `document`
   * destination where the browser sends fetch metadata, and an `Accept` naming `text/html` where it
   * does not. A grant, a wrong token, an API call, an image or a socket falls through untouched.
   * `/admin` keeps its own token handling.
   */
  fun exchangeTarget(call: ApplicationCall, serverToken: String): String? {
    if (serverToken.isBlank()) return null
    if (call.request.httpMethod != HttpMethod.Get) return null
    if (ServeSameOriginRequests.isWebSocketUpgrade(call)) return null
    if (!isDocumentNavigation(call)) return null
    val path = call.request.path()
    if (path == "/admin" || path.startsWith("/admin/")) return null
    val presented = call.request.queryParameters.getAll("token") ?: return null
    if (presented.size != 1 || !ServeUrls.tokensMatch(serverToken, presented.single())) return null
    return path + queryWithoutToken(call.request.queryString())
  }

  internal fun isDocumentNavigation(call: ApplicationCall): Boolean {
    val headers = call.request.headers
    val mode = headers["Sec-Fetch-Mode"]
    if (mode != null) {
      val dest = headers["Sec-Fetch-Dest"]
      return mode == "navigate" && (dest == null || dest == "document")
    }
    return headers[HttpHeaders.Accept]?.contains("text/html", ignoreCase = true) == true
  }

  /** [rawQuery] minus every `token` parameter, other parameters kept byte for byte. */
  internal fun queryWithoutToken(rawQuery: String): String {
    val kept =
      rawQuery
        .split('&')
        .filter { it.isNotEmpty() }
        .filterNot { part ->
          val key = part.substringBefore('=')
          runCatching { URLDecoder.decode(key, Charsets.UTF_8) }.getOrDefault(key) == "token"
        }
    return if (kept.isEmpty()) "" else kept.joinToString("&", prefix = "?")
  }

  private fun derive(serverToken: String, label: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(serverToken.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(mac.doFinal(label.toByteArray(Charsets.UTF_8)))
  }
}
