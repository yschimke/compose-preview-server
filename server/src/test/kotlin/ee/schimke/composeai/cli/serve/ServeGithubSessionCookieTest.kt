package ee.schimke.composeai.cli.serve

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
import kotlin.test.assertNotEquals
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
 * What the session and sign-in state cookies are bound to: the sign-in configuration they were
 * issued under, one value per name, their own purpose, and — for the state — a lifetime the server
 * holds them to.
 */
class ServeGithubSessionCookieTest {

  private var now: Instant = Instant.parse("2026-01-01T00:00:00Z")
  private val clock =
    object : Clock() {
      override fun getZone() = ZoneOffset.UTC

      override fun withZone(zone: java.time.ZoneId?) = this

      override fun instant(): Instant = now
    }

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> """{"access_token":"token"}"""
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

  private fun config(
    allowedUsers: Set<String> = emptySet(),
    allowedOrgs: Set<String> = emptySet(),
    allowGuests: Boolean = false,
    imageRepository: String? = null,
    cookieDomain: String? = null,
  ) =
    ServeGithubAuthConfig(
      clientId = "client",
      clientSecret = "secret",
      cookieSecret = SECRET,
      repository = REPO,
      imageRepository = imageRepository,
      allowedUsers = allowedUsers,
      allowedOrgs = allowedOrgs,
      allowGuests = allowGuests,
      cookieDomain = cookieDomain,
    )

  private fun auth(config: ServeGithubAuthConfig = config()) =
    ServeGithubAuth(config, verifier = GitHubOAuthVerifier(fakeGitHub), clock = clock)

  private val servers = mutableListOf<ServeHttpServer>()
  private val registry = ServeSessionRegistry(open = { null })

  private fun server(auth: ServeGithubAuth): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        githubAuth = auth,
      )
      .also {
        it.start()
        servers += it
      }

  private val noRedirect = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun stop() {
    servers.forEach { runCatching { it.stop() } }
    runCatching { registry.close() }
  }

  private fun url(server: ServeHttpServer, path: String) = "http://127.0.0.1:${server.port}$path"

  /** `/auth/github/start`: the OAuth `state` parameter and the `cp_gh_state=…` cookie pair. */
  private fun start(server: ServeHttpServer): Pair<String, String> =
    noRedirect
      .newCall(Request.Builder().url(url(server, "/auth/github/start")).build())
      .execute()
      .use { resp ->
        assertEquals(302, resp.code)
        resp.header("Location").orEmpty().substringAfter("state=").substringBefore("&") to
          resp
            .headers("Set-Cookie")
            // With a cookie domain the response also clears a host-only variant, whose value is
            // empty; the state is the one with a value.
            .first { it.startsWith("cp_gh_state=") && !it.startsWith("cp_gh_state=;") }
            .substringBefore(";")
      }

  /** The callback leg: its status and every `Set-Cookie` line it wrote. */
  private fun callback(
    server: ServeHttpServer,
    state: String,
    cookie: String,
  ): Pair<Int, List<String>> =
    noRedirect
      .newCall(
        Request.Builder()
          .url(url(server, "/auth/github/callback?code=ok&state=$state"))
          .header("Cookie", cookie)
          .build()
      )
      .execute()
      .use { resp -> resp.code to resp.headers("Set-Cookie") }

  /** A full sign-in; returns the bare session cookie value. */
  private fun signIn(server: ServeHttpServer): String {
    val (state, stateCookie) = start(server)
    val (code, cookies) = callback(server, state, stateCookie)
    assertEquals(302, code)
    return cookies.first { it.startsWith("cp_gh_auth=") }.substringAfter("=").substringBefore(";")
  }

  /** Whether the front door greets [cookieHeader] as a signed-in visitor. */
  private fun greeted(server: ServeHttpServer, cookieHeader: String): Boolean =
    noRedirect
      .newCall(Request.Builder().url(url(server, "/")).header("Cookie", cookieHeader).build())
      .execute()
      .use { resp -> resp.body.string().contains("octo") }

  @Test
  fun `a session is refused once the sign-in configuration changes`() {
    val open = auth()
    val cookie = signIn(server(open))
    assertEquals("octo", open.sessionLogin(cookie))
    // The same settings on a restarted box, lists in any order or case, keep everybody signed in.
    assertEquals("octo", auth().sessionLogin(cookie))
    assertEquals(
      ServeGithubAuth.configFingerprint(config(allowedUsers = setOf("octo", "hubot"))),
      ServeGithubAuth.configFingerprint(config(allowedUsers = setOf("HUBOT", "octo"))),
    )
    // Each setting that decides what a session carries re-issues it when changed.
    for (changed in
      listOf(
        config(allowedUsers = setOf("octo")),
        config(allowedOrgs = setOf("google")),
        config(allowGuests = true),
        config(imageRepository = "yschimke/compose-preview-server"),
      )) {
      assertNull(auth(changed).sessionLogin(cookie), "$changed must re-issue the session")
    }
  }

  @Test
  fun `two session cookies read as signed out`() {
    val server = server(auth())
    val cookie = signIn(server)
    assertTrue(greeted(server, "cp_gh_auth=$cookie"), "precondition: one value signs in")
    assertFalse(greeted(server, "cp_gh_auth=$cookie; cp_gh_auth=$cookie"))
    assertFalse(greeted(server, "cp_gh_auth=$cookie; cp_gh_auth=other"))
  }

  @Test
  fun `two state cookies fail the callback`() {
    val server = server(auth())
    val (state, stateCookie) = start(server)
    assertEquals(401, callback(server, state, "$stateCookie; $stateCookie").first)
    assertEquals(302, callback(server, state, stateCookie).first, "one value still completes")
  }

  @Test
  fun `an expired state fails the callback even if the browser still sends it`() {
    val server = server(auth())
    val (state, stateCookie) = start(server)
    now = now.plusSeconds(ServeGithubAuth.STATE_TTL_SECONDS + 1)
    assertEquals(401, callback(server, state, stateCookie).first)
  }

  @Test
  fun `a state value is not a session and a session value is not a state`() {
    val auth = auth()
    val server = server(auth)
    val (state, _) = start(server)
    assertNull(auth.sessionLogin(state))
    val session = signIn(server)
    assertEquals(401, callback(server, session, "cp_gh_state=$session").first)
  }

  /**
   * The purpose tag is what separates the two: the very same session payload verifies only when it
   * was signed as a session.
   */
  @Test
  fun `a session payload signed for the state purpose does not verify`() {
    val auth = auth()
    val expiry = now.toEpochMilli() + ServeGithubAuth.SESSION_TTL_SECONDS * 1000
    val payload =
      "octo|repo|$expiry|${now.toEpochMilli()}|image-repo|member|" +
        ServeGithubAuth.configFingerprint(config())
    assertEquals("octo", auth.sessionLogin(signed("session", payload)))
    assertNull(auth.sessionLogin(signed("state", payload)))
  }

  @Test
  fun `a refreshed session is never written onto a publicly cacheable response`() {
    val server = server(auth())
    val cookie = signIn(server)
    now = now.plusSeconds(ServeGithubAuth.SESSION_REFRESH_AFTER_SECONDS + 60)
    fun visit(path: String): Pair<String?, String?> =
      noRedirect
        .newCall(
          Request.Builder().url(url(server, path)).header("Cookie", "cp_gh_auth=$cookie").build()
        )
        .execute()
        .use { resp ->
          resp.header("Cache-Control") to
            resp.headers("Set-Cookie").firstOrNull { it.startsWith("cp_gh_auth=") }
        }
    val (assetCacheControl, assetCookie) = visit("/rc-player/bundle.js")
    assertTrue(
      assetCacheControl.orEmpty().contains("public"),
      "precondition: the bundle is public: $assetCacheControl",
    )
    assertNull(assetCookie, "a public response must not carry a session cookie")
    val (_, pageCookie) = visit("/version")
    assertTrue(pageCookie != null, "an ordinary response still slides the session")
  }

  @Test
  fun `signing out clears the host-only cookie too when a cookie domain is set`() {
    val server = server(auth(config(cookieDomain = "preview.coo.ee")))
    val cleared =
      noRedirect
        .newCall(
          Request.Builder()
            .url(url(server, ServeGithubAuth.LOGOUT_PATH))
            .post("".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        )
        .execute()
        .use { resp -> resp.headers("Set-Cookie").filter { it.startsWith("cp_gh_auth=;") } }
    assertEquals(2, cleared.size, cleared.toString())
    assertEquals(1, cleared.count { it.contains("Domain=preview.coo.ee", ignoreCase = true) })
    assertEquals(1, cleared.count { !it.contains("Domain=", ignoreCase = true) })
  }

  @Test
  fun `a stray host-only session is cleared so the next request reads as signed in`() {
    val server = server(auth(config(cookieDomain = "preview.coo.ee")))
    val cookie = signIn(server)
    val cleared =
      noRedirect
        .newCall(
          Request.Builder()
            .url(url(server, "/version"))
            .header("Cookie", "cp_gh_auth=stale; cp_gh_auth=$cookie")
            .build()
        )
        .execute()
        .use { resp -> resp.headers("Set-Cookie").filter { it.startsWith("cp_gh_auth=") } }
    assertEquals(1, cleared.size, cleared.toString())
    assertTrue(cleared.single().startsWith("cp_gh_auth=;"), cleared.toString())
    assertFalse(cleared.single().contains("Domain=", ignoreCase = true), cleared.toString())
  }

  @Test
  fun `a stray host-only session is cleared on a publicly cacheable response too`() {
    val server = server(auth(config(cookieDomain = "preview.coo.ee")))
    val cookie = signIn(server)
    val (cacheControl, cleared) =
      noRedirect
        .newCall(
          Request.Builder()
            .url(url(server, "/rc-player/bundle.js"))
            .header("Cookie", "cp_gh_auth=stale; cp_gh_auth=$cookie")
            .build()
        )
        .execute()
        .use { resp ->
          resp.header("Cache-Control") to
            resp.headers("Set-Cookie").filter { it.startsWith("cp_gh_auth=") }
        }
    assertTrue(cacheControl.orEmpty().contains("public"), "precondition: $cacheControl")
    assertEquals(1, cleared.size, cleared.toString())
    assertTrue(cleared.single().startsWith("cp_gh_auth=;"), "only an empty clearing cookie")
    assertFalse(cleared.single().contains("Domain=", ignoreCase = true), cleared.toString())
  }

  @Test
  fun `the fingerprint is short and stable`() {
    val fingerprint = ServeGithubAuth.configFingerprint(config())
    assertEquals(16, fingerprint.length)
    assertEquals(fingerprint, ServeGithubAuth.configFingerprint(config(imageRepository = REPO)))
    assertNotEquals(fingerprint, ServeGithubAuth.configFingerprint(config(allowGuests = true)))
  }

  /** Signs [payload] the way the server does, with the MAC covering [purpose]. */
  private fun signed(purpose: String, payload: String): String {
    val encoded =
      Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val sig =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal("$purpose:$encoded".toByteArray(Charsets.UTF_8)))
    return "$encoded.$sig"
  }

  private companion object {
    const val SECRET = "x-cookie-secret-that-is-long-enough-0123"
    const val REPO = "yschimke/compose-ai-tools"
  }
}
