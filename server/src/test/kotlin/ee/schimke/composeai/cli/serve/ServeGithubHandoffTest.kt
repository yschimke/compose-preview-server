package ee.schimke.composeai.cli.serve

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Signing in on a site host **without a cookie domain**: the callback stays pinned to one host, the
 * cookies stay host-only, and a site host gets its own session from the callback host through a
 * one-time handoff code ([ServeGithubAuth.handleHandoff]).
 *
 * A shared cookie domain sends the session to every host under it, including hosts that are not
 * this server's. These tests pin the replacement: the cookies never name a `Domain`, and the code
 * that carries a sign-in across hosts is single-use, short-lived, bound to one host and redeemable
 * only by the browser that asked for it.
 */
class ServeGithubHandoffTest {

  private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
  private val clock =
    object : Clock() {
      override fun getZone() = ZoneOffset.UTC

      override fun withZone(zone: java.time.ZoneId?) = this

      override fun instant(): Instant = now
    }

  /** GitHub calls made, so a test can tell a silent handoff from a real sign-in. */
  private val tokenExchanges = java.util.concurrent.atomic.AtomicInteger()

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> {
              tokenExchanges.incrementAndGet()
              """{"access_token":"token"}"""
            }
            "/user" -> """{"login":"octo"}"""
            else -> """{"private":false,"permissions":{"push":true}}"""
          }
        Response.Builder()
          .request(request)
          .protocol(Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(body.toResponseBody("application/json".toMediaType()))
          .build()
      }
      .build()

  private fun config(cookieDomain: String? = null) =
    ServeGithubAuthConfig(
      clientId = "client",
      clientSecret = "secret",
      cookieSecret = SECRET,
      repository = "example/repo",
      callbackBaseUrl = "https://$MAIN",
      cookieDomain = cookieDomain,
      // These requests stand in for the proxy, which sets `X-Forwarded-Host`.
      trustForwardedHeaders = true,
    )

  private fun auth(cookieDomain: String? = null) =
    ServeGithubAuth(config(cookieDomain), verifier = GitHubOAuthVerifier(fakeGitHub), clock = clock)

  private val sites = ServeSiteRegistry.of(listOf(M3 to "m3-catalog", WEAR to "wear-m3"))

  private val registries = mutableListOf<ServeSessionRegistry>()
  private val servers = mutableListOf<ServeHttpServer>()

  private fun server(
    auth: ServeGithubAuth = auth(),
    siteRegistry: ServeSiteRegistry = sites,
  ): ServeHttpServer {
    val registry = ServeSessionRegistry(open = { null }).also { registries += it }
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        githubAuth = auth,
        trustForwardedFor = true,
        sites = siteRegistry,
        uiBuilderHost = UI,
        uiBuilderProfile =
          ServeUiBuilderProfile(DESIGNER, "Example designer", listOf("m3-catalog")),
      )
      .also {
        it.start()
        servers += it
      }
  }

  @AfterTest
  fun stop() {
    servers.forEach { runCatching { it.stop() } }
    registries.forEach { runCatching { it.close() } }
  }

  private val noRedirect = OkHttpClient.Builder().followRedirects(false).build()

  private fun get(
    server: ServeHttpServer,
    pathAndQuery: String,
    host: String,
    cookie: String? = null,
  ): Response {
    val builder = Request.Builder().url("http://127.0.0.1:${server.port}$pathAndQuery")
    builder.header("X-Forwarded-Host", host)
    if (cookie != null) builder.header("Cookie", cookie)
    return noRedirect.newCall(builder.build()).execute()
  }

  private fun post(
    server: ServeHttpServer,
    pathAndQuery: String,
    host: String,
    cookie: String,
  ): Response =
    noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}$pathAndQuery")
          .header("X-Forwarded-Host", host)
          .header("Cookie", cookie)
          .post(ByteArray(0).toRequestBody(null))
          .build()
      )
      .execute()

  /** Every `Set-Cookie` for [name], value first. */
  private fun Response.setCookies(name: String): List<String> =
    headers("Set-Cookie").filter { it.startsWith("$name=") }

  /** The one non-empty value set for [name], or null. */
  private fun Response.cookieValue(name: String): String? =
    setCookies(name)
      .map { it.substringBefore(';').substringAfter('=') }
      .singleOrNull { it.isNotEmpty() }

  private fun Response.location(): String = header("Location").orEmpty()

  private fun query(url: String, name: String): String? =
    url
      .substringAfter('?', "")
      .split('&')
      .firstOrNull { it.startsWith("$name=") }
      ?.substringAfter('=')
      ?.let { URLDecoder.decode(it, "UTF-8") }

  /** A started sign-in on a site host: the nonce it set, and where it sent the browser next. */
  private data class SiteStart(val nonce: String, val next: String)

  private fun startOnSite(
    server: ServeHttpServer,
    host: String = M3,
    returnPath: String = "/p/badge",
    cookie: String? = null,
  ): SiteStart =
    get(server, "$START?return=${enc(returnPath)}", host, cookie).use {
      assertEquals(302, it.code)
      SiteStart(assertNotNull(it.cookieValue(HANDOFF_COOKIE)), it.location())
    }

  /** Follow a site start through the callback host and GitHub; returns the callback response. */
  private fun <T> throughGitHub(
    server: ServeHttpServer,
    start: SiteStart,
    callbackCookie: String? = null,
    block: (Response) -> T,
  ): T {
    assertTrue(start.next.startsWith("https://$MAIN$START?"), start.next)
    val (state, stateCookie) =
      get(server, start.next.removePrefix("https://$MAIN"), MAIN, callbackCookie).use {
        assertEquals(302, it.code)
        assertTrue(it.location().startsWith("https://github.com/login/oauth/authorize?"))
        assertNotNull(query(it.location(), "state")) to assertNotNull(it.cookieValue(STATE_COOKIE))
      }
    return get(
        server,
        "$CALLBACK?code=ok&state=${enc(state)}",
        MAIN,
        "$STATE_COOKIE=$stateCookie",
      )
      .use(block)
  }

  /** A full sign-in begun on [host]: the handoff URL and the nonce to redeem it with. */
  private fun handoffFor(server: ServeHttpServer, host: String = M3): Pair<String, String> {
    val start = startOnSite(server, host)
    val handoff =
      throughGitHub(server, start) {
        assertEquals(302, it.code)
        it.location()
      }
    return handoff to start.nonce
  }

  private fun redeem(
    server: ServeHttpServer,
    handoffUrl: String,
    host: String,
    nonce: String?,
  ): Response =
    get(
      server,
      handoffUrl.substring(handoffUrl.indexOf(HANDOFF)),
      host,
      nonce?.let { "$HANDOFF_COOKIE=$it" },
    )

  /** Whether the server reads [session] as signed in, by the page cache policy it answers with. */
  private fun signedIn(server: ServeHttpServer, session: String): Boolean =
    get(server, "/missing", M3, "$AUTH_COOKIE=$session").use {
      assertEquals(404, it.code)
      it.header("Cache-Control") == ServeHttpServer.SIGNED_IN_PAGE_CACHE_CONTROL
    }

  @Test
  fun `a site-host sign-in ends with a host-only session on that host, by way of the callback host`() {
    val server = server()
    val auth = auth()
    val start = startOnSite(server)
    get(server, "$START?return=%2Fp%2Fbadge", M3).use { started ->
      val nonceCookie = started.setCookies(HANDOFF_COOKIE).single()
      assertFalse(nonceCookie.contains("domain=", ignoreCase = true), nonceCookie)
      assertTrue(nonceCookie.contains("Secure", ignoreCase = true), nonceCookie)
      assertTrue(nonceCookie.contains("HttpOnly", ignoreCase = true), nonceCookie)
      assertTrue(nonceCookie.contains("SameSite=Lax", ignoreCase = true), nonceCookie)
      // The nonce stays on the site host; only its digest travels.
      assertFalse(started.location().contains(assertNotNull(started.cookieValue(HANDOFF_COOKIE))))
      assertEquals(M3, query(started.location(), "handoff_host"))
      assertEquals("/p/badge", query(started.location(), "return"))
    }

    val handoff =
      throughGitHub(server, start) {
        assertEquals(302, it.code)
        assertEquals("no-referrer", it.header("Referrer-Policy"))
        assertTrue(it.location().startsWith("https://$M3$HANDOFF?code="), it.location())
        // The callback host is signed in too, with its own host-only cookie.
        val callbackSession =
          it.setCookies(AUTH_COOKIE).single { c -> !c.startsWith("$AUTH_COOKIE=;") }
        assertFalse(callbackSession.contains("domain=", ignoreCase = true), callbackSession)
        assertEquals("octo", auth.sessionLogin(assertNotNull(it.cookieValue(AUTH_COOKIE))))
        assertTrue(
          it.headers("Set-Cookie").none { c -> c.contains("domain=", ignoreCase = true) },
          "no cookie may span a domain: ${it.headers("Set-Cookie")}",
        )
        it.location()
      }

    redeem(server, handoff, M3, start.nonce).use {
      assertEquals(302, it.code)
      assertEquals("/p/badge", it.location())
      assertEquals("no-referrer", it.header("Referrer-Policy"))
      val session = it.setCookies(AUTH_COOKIE).single()
      assertFalse(session.contains("domain=", ignoreCase = true), session)
      assertTrue(session.contains("Secure", ignoreCase = true), session)
      assertTrue(session.contains("HttpOnly", ignoreCase = true), session)
      assertEquals("octo", auth.sessionLogin(assertNotNull(it.cookieValue(AUTH_COOKIE))))
      // The nonce stays for another tab's handoff; it expires on its own.
      assertTrue(it.setCookies(HANDOFF_COOKIE).isEmpty())
    }
  }

  @Test
  fun `a handoff session is read on its own host`() {
    val server = server()
    val (handoff, nonce) = handoffFor(server)
    val session =
      redeem(server, handoff, M3, nonce).use { assertNotNull(it.cookieValue(AUTH_COOKIE)) }
    assertTrue(signedIn(server, session))
    // The cookie under its old name is not read.
    get(server, "/missing", M3, "cp_gh_auth=$session").use {
      assertEquals(ServeHttpServer.ANON_PAGE_CACHE_CONTROL, it.header("Cache-Control"))
    }
  }

  @Test
  fun `a handoff code is spent by its first use`() {
    val server = server()
    val (handoff, nonce) = handoffFor(server)
    redeem(server, handoff, M3, nonce).use { assertEquals(302, it.code) }
    redeem(server, handoff, M3, nonce).use {
      assertEquals(401, it.code)
      assertTrue(it.setCookies(AUTH_COOKIE).isEmpty())
    }
  }

  @Test
  fun `a handoff code is redeemed only on the host it was minted for`() {
    val server = server()
    val (handoff, nonce) = handoffFor(server)
    val code = assertNotNull(query(handoff, "code"))
    for (host in listOf(WEAR, MAIN, UI)) {
      get(server, "$HANDOFF?code=$code", host, "$HANDOFF_COOKIE=$nonce").use {
        assertEquals(401, it.code, host)
        assertTrue(it.setCookies(AUTH_COOKIE).isEmpty(), host)
      }
    }
    // The wrong-host attempt spent it, so it is no good on the right host afterwards either.
    redeem(server, handoff, M3, nonce).use { assertEquals(401, it.code) }
  }

  @Test
  fun `a handoff code needs the nonce of the browser that asked for it`() {
    val server = server()
    val (missing, _) = handoffFor(server)
    redeem(server, missing, M3, nonce = null).use { assertEquals(401, it.code) }

    // An attacker's own code, sent to a victim whose browser holds a different nonce, signs no one
    // in: login CSRF needs the attacker's nonce in the victim's cookie jar.
    val (attackers, _) = handoffFor(server)
    val victim = startOnSite(server).nonce
    redeem(server, attackers, M3, victim).use {
      assertEquals(401, it.code)
      assertTrue(it.setCookies(AUTH_COOKIE).isEmpty())
    }

    val (twoNonces, nonce) = handoffFor(server)
    get(
        server,
        twoNonces.substringAfter(M3),
        M3,
        "$HANDOFF_COOKIE=$nonce; $HANDOFF_COOKIE=$victim",
      )
      .use { assertEquals(401, it.code, "two nonces are no nonce") }
  }

  @Test
  fun `a handoff code expires within a minute`() {
    val server = server()
    val (handoff, nonce) = handoffFor(server)
    now = now.plusSeconds(ServeGithubAuth.HANDOFF_TTL_SECONDS + 1)
    redeem(server, handoff, M3, nonce).use { assertEquals(401, it.code) }
  }

  @Test
  fun `a made-up or empty code is refused`() {
    val server = server()
    val nonce = startOnSite(server).nonce
    for (code in listOf("", "x".repeat(43), "not-a-code")) {
      get(server, "$HANDOFF?code=$code", M3, "$HANDOFF_COOKIE=$nonce").use {
        assertEquals(401, it.code, code)
      }
    }
    get(server, HANDOFF, M3, "$HANDOFF_COOKIE=$nonce").use { assertEquals(401, it.code) }
  }

  @Test
  fun `with a session on the callback host a site-host sign-in skips GitHub`() {
    val server = server()
    val auth = auth()
    val start = startOnSite(server, WEAR)
    // Signing in on M3 also signed the callback host in; take that cookie as the browser would.
    val callbackSession =
      throughGitHub(server, startOnSite(server, M3)) { assertNotNull(it.cookieValue(AUTH_COOKIE)) }
    val exchanges = tokenExchanges.get()

    val silent =
      get(server, start.next.removePrefix("https://$MAIN"), MAIN, "$AUTH_COOKIE=$callbackSession")
        .use {
          assertEquals(302, it.code)
          assertEquals("no-referrer", it.header("Referrer-Policy"))
          assertTrue(it.location().startsWith("https://$WEAR$HANDOFF?code="), it.location())
          assertTrue(it.setCookies(STATE_COOKIE).isEmpty(), "no GitHub round trip, so no state")
          it.location()
        }
    assertEquals(exchanges, tokenExchanges.get(), "GitHub was not asked again")
    redeem(server, silent, WEAR, start.nonce).use {
      assertEquals(302, it.code)
      assertEquals("/p/badge", it.location())
      assertEquals("octo", auth.sessionLogin(assertNotNull(it.cookieValue(AUTH_COOKIE))))
    }
  }

  @Test
  fun `two tabs signing in at once both complete`() {
    val server = server()
    val first = startOnSite(server)
    val second = startOnSite(server, cookie = "$HANDOFF_COOKIE=${first.nonce}")
    assertEquals(first.nonce, second.nonce)
    val firstHandoff = throughGitHub(server, first) { it.location() }
    val secondHandoff = throughGitHub(server, second) { it.location() }
    // Redeeming one tab's code must not cost the other tab its nonce.
    redeem(server, firstHandoff, M3, first.nonce).use { assertEquals(302, it.code) }
    redeem(server, secondHandoff, M3, second.nonce).use {
      assertEquals(302, it.code)
      assertNotNull(it.cookieValue(AUTH_COOKIE))
    }
  }

  @Test
  fun `the callback host hands back only to a configured site host`() {
    val server = server()
    val digest = "a".repeat(43)
    for (target in listOf("evil.example.net", MAIN, "other.preview.example.com")) {
      val state =
        get(server, "$START?return=%2Fp%2Fbadge&handoff_host=$target&handoff=$digest", MAIN).use {
          assertEquals(302, it.code)
          assertTrue(it.location().startsWith("https://github.com/"), target)
          query(it.location(), "state") to it.cookieValue(STATE_COOKIE)
        }
      get(
          server,
          "$CALLBACK?code=ok&state=${enc(state.first!!)}",
          MAIN,
          "$STATE_COOKIE=${state.second}",
        )
        .use {
          assertEquals(302, it.code)
          assertEquals(
            "/p/badge",
            it.location(),
            "$target is not a site, so the return is relative",
          )
        }
    }
    // A malformed digest is no handoff either, even for a real site host.
    get(server, "$START?handoff_host=$M3&handoff=short", MAIN).use {
      assertTrue(it.location().startsWith("https://github.com/"))
    }
    // An absolute return can't name another host through the handoff path either.
    get(server, "$START?return=${enc("https://evil.example.net/x")}", M3).use {
      assertEquals("/", query(it.location(), "return"))
    }
  }

  @Test
  fun `a handoff state for a host this server no longer serves returns relative`() {
    val minted = server()
    val start = startOnSite(minted)
    val (state, stateCookie) =
      get(minted, start.next.removePrefix("https://$MAIN"), MAIN).use {
        query(it.location(), "state")!! to it.cookieValue(STATE_COOKIE)!!
      }
    // Same secret and settings, but M3 is no longer one of its sites.
    val narrowed = server(siteRegistry = ServeSiteRegistry.of(listOf(WEAR to "wear-m3")))
    get(narrowed, "$CALLBACK?code=ok&state=${enc(state)}", MAIN, "$STATE_COOKIE=$stateCookie").use {
      assertEquals(302, it.code)
      assertEquals("/p/badge", it.location())
    }
  }

  @Test
  fun `a callback with no matching state cookie is still refused`() {
    val server = server()
    val start = startOnSite(server)
    val state =
      get(server, start.next.removePrefix("https://$MAIN"), MAIN).use {
        query(it.location(), "state")!!
      }
    get(server, "$CALLBACK?code=ok&state=${enc(state)}", MAIN).use { assertEquals(401, it.code) }
    // A state cookie set on the site host would not reach the callback host, and the old name is
    // not read here.
    get(server, "$CALLBACK?code=ok&state=${enc(state)}", MAIN, "cp_gh_state=$state").use {
      assertEquals(401, it.code)
    }
  }

  @Test
  fun `every configured site host round-trips a sign-in, an outsider does not`() {
    val auth = auth()
    assertTrue(auth.canRoundTrip(M3, sites.hosts))
    assertTrue(auth.canRoundTrip(WEAR, sites.hosts))
    assertTrue(auth.canRoundTrip(MAIN, sites.hosts))
    assertFalse(auth.canRoundTrip("other.preview.example.com", sites.hosts))
    assertFalse(auth.canRoundTrip(null, sites.hosts))
  }

  @Test
  fun `an absolute return to the white-labelled builder host starts the sign-in over there`() {
    val server = server()
    get(server, "$START?return=${enc("https://$DESIGNER/my-design")}", M3).use {
      assertEquals(302, it.code)
      assertEquals("https://$DESIGNER$START?return=%2Fmy-design", it.location())
    }
    // On that host it is an ordinary site-host sign-in.
    val start = startOnSite(server, DESIGNER, "/my-design")
    assertEquals(DESIGNER, query(start.next, "handoff_host"))
    assertEquals(
      DESIGNER to "/my-design",
      auth().absoluteReturn("https://$DESIGNER/my-design", setOf(DESIGNER)),
    )
  }

  @Test
  fun `signing out on one host signs that sign-in out on every host, and no other`() {
    val server = server()
    val start = startOnSite(server)
    var callbackSession = ""
    val handoff =
      throughGitHub(server, start) {
        callbackSession = assertNotNull(it.cookieValue(AUTH_COOKIE))
        it.location()
      }
    val siteSession =
      redeem(server, handoff, M3, start.nonce).use { assertNotNull(it.cookieValue(AUTH_COOKIE)) }
    // Another browser's sign-in, a moment later.
    now = now.plusSeconds(1)
    val elsewhere =
      throughGitHub(server, startOnSite(server)) { assertNotNull(it.cookieValue(AUTH_COOKIE)) }

    post(server, "${ServeGithubAuth.LOGOUT_PATH}?return=%2F", M3, "$AUTH_COOKIE=$siteSession").use {
      assertEquals(302, it.code)
      assertTrue(it.setCookies(AUTH_COOKIE).single().startsWith("$AUTH_COOKIE=;"))
    }
    // The record is the serving instance's own, so ask the server rather than a fresh instance.
    assertFalse(signedIn(server, callbackSession))
    assertFalse(signedIn(server, siteSession))
    assertTrue(signedIn(server, elsewhere), "another sign-in stays signed in")
    // A silent handoff can't resurrect it from the callback host's copy.
    val again = startOnSite(server)
    get(server, again.next.removePrefix("https://$MAIN"), MAIN, "$AUTH_COOKIE=$callbackSession")
      .use { assertTrue(it.location().startsWith("https://github.com/")) }
  }

  @Test
  fun `a session minted for a shared cookie domain is not accepted host-only`() {
    val auth = auth()
    val expiry = now.toEpochMilli() + ServeGithubAuth.SESSION_TTL_SECONDS * 1000
    val payload =
      "octo|repo|$expiry|${now.toEpochMilli()}|image-repo|member|" +
        ServeGithubAuth.configFingerprint(config())
    // Under a cookie domain, a session without the host-only mark is the normal shape…
    assertEquals("octo", auth(cookieDomain = MAIN).sessionLogin(signed(payload)))
    // …but one may have been sent to every host under that domain, so here it is refused.
    assertNull(auth.sessionLogin(signed(payload)))
    assertEquals("octo", auth.sessionLogin(signed("$payload|host-only")))
  }

  @Test
  fun `a leftover domain-wide session cookie is expired for every parent domain`() {
    val server = server()
    val (handoff, nonce) = handoffFor(server)
    get(server, handoff.substringAfter(M3), M3, "$HANDOFF_COOKIE=$nonce; cp_gh_auth=old").use {
      assertEquals(302, it.code)
      val cleared = it.setCookies("cp_gh_auth").map { c -> c.lowercase() }
      for (domain in listOf("m3.preview.example.com", "preview.example.com", "example.com")) {
        assertTrue(cleared.any { c -> c.contains("domain=$domain") }, "$domain: $cleared")
      }
      assertTrue(
        cleared.all { c -> c.startsWith("cp_gh_auth=;") || c.startsWith("cp_gh_auth=\"\"") }
      )
    }
  }

  @Test
  fun `with a cookie domain the sign-in is the shared-cookie one it always was`() {
    val server = server(auth(cookieDomain = "preview.example.com"))
    val (state, stateCookie) =
      get(server, "$START?return=%2Fp%2Fbadge", M3).use {
        assertEquals(302, it.code)
        assertTrue(it.location().startsWith("https://github.com/"), "no handoff leg")
        assertTrue(it.setCookies(HANDOFF_COOKIE).isEmpty())
        val cookie = it.setCookies("cp_gh_state").single { c -> !c.startsWith("cp_gh_state=;") }
        assertTrue(cookie.contains("domain=preview.example.com", ignoreCase = true), cookie)
        query(it.location(), "state")!! to cookie.substringBefore(';')
      }
    get(server, "$CALLBACK?code=ok&state=${enc(state)}", MAIN, stateCookie).use {
      assertEquals(302, it.code)
      assertEquals("https://$M3/p/badge", it.location())
      val session = it.setCookies("cp_gh_auth").single { c -> !c.startsWith("cp_gh_auth=;") }
      assertTrue(session.contains("domain=preview.example.com", ignoreCase = true), session)
      assertTrue(it.setCookies(AUTH_COOKIE).isEmpty())
    }
    get(server, HANDOFF, M3).use { assertEquals(401, it.code) }
  }

  private fun signed(payload: String): String {
    val encoded =
      Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val sig =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal("session:$encoded".toByteArray(Charsets.UTF_8)))
    return "$encoded.$sig"
  }

  private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

  private companion object {
    const val SECRET = "handoff-test-cookie-secret-0123456789"
    const val MAIN = "preview.example.com"
    const val M3 = "m3.preview.example.com"
    const val WEAR = "wear.preview.example.com"
    const val UI = "ui.example.com"
    const val DESIGNER = "designer.example.org"
    const val START = ServeGithubAuth.START_PATH
    const val CALLBACK = ServeGithubAuth.CALLBACK_PATH
    const val HANDOFF = ServeGithubAuth.HANDOFF_PATH
    const val AUTH_COOKIE = "__Host-cp_gh_auth"
    const val STATE_COOKIE = "__Host-cp_gh_state"
    const val HANDOFF_COOKIE = "__Host-cp_gh_handoff"
  }
}
