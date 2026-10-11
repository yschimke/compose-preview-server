package ee.schimke.composeai.cli.serve

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * How long a GitHub sign-in lasts and how it renews. The session is a signed cookie with an
 * embedded expiry and no server-side store, so an active visitor is kept signed in by re-issuing it
 * as it ages.
 */
class ServeGithubSessionTest {

  /** Advanceable, so a fortnight of session ageing costs a field assignment. */
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

  private val auth =
    ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
      clock = clock,
    )

  private val registry = ServeSessionRegistry(open = { null })

  private val fs = FakeFileSystem()

  /**
   * Wired so `/playground` exists: it consults the session (302 without one, 200 for a visitor the
   * fake GitHub grants repo rights), making it the probe for an authenticated session. Nothing is
   * compiled.
   */
  private val playground =
    PlaygroundCompileService(
      catalogClasspath = { _, _ -> null },
      compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
      discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> emptyList() },
      tokenStore = PlaygroundTokenStore(fileSystem = fs),
      newWorkDir = { "/work/run".toPath() },
      fileSystem = fs,
    )

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        githubAuth = auth,
        playgroundService = playground,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()
  private val noRedirect by lazy { client.newBuilder().followRedirects(false).build() }

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  /** Runs the OAuth round-trip against the fake GitHub and returns the raw `Set-Cookie` line. */
  private fun signIn(): String {
    val start =
      noRedirect
        .newCall(Request.Builder().url("http://127.0.0.1:${server.port}/auth/github/start").build())
        .execute()
        .use { resp ->
          assertEquals(302, resp.code)
          resp.header("Location").orEmpty() to
            resp.header("Set-Cookie").orEmpty().substringBefore(";")
        }
    val state = start.first.substringAfter("state=").substringBefore("&")
    return noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/auth/github/callback?code=ok&state=$state")
          .header("Cookie", start.second)
          .build()
      )
      .execute()
      .use { resp ->
        assertEquals(302, resp.code)
        resp.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") }
      }
  }

  /** A plain gated-server request, returning the re-issued session cookie line if there was one. */
  private fun visit(cookie: String): String? =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/version")
          .header("Cookie", cookie.substringBefore(";"))
          .build()
      )
      .execute()
      .use { resp -> resp.headers("Set-Cookie").firstOrNull { it.startsWith("cp_gh_auth=") } }

  private fun maxAge(setCookie: String): Long =
    setCookie
      .split(";")
      .map { it.trim() }
      .first { it.startsWith("Max-Age=", ignoreCase = true) }
      .substringAfter("=")
      .toLong()

  @Test
  fun `a sign-in lasts a week idle, not an afternoon`() {
    assertEquals(7L * 24 * 60 * 60, ServeGithubAuth.SESSION_TTL_SECONDS)
    assertEquals(ServeGithubAuth.SESSION_TTL_SECONDS, maxAge(signIn()))
  }

  @Test
  fun `a young session is left alone`() {
    val cookie = signIn()
    now = now.plusSeconds(60 * 60)
    // Nothing to extend yet, so the response carries no Set-Cookie at all — a page view under a
    // fresh session is byte-identical to one with none.
    assertNull(visit(cookie))
  }

  @Test
  fun `visiting past the half-life slides the session forward`() {
    val cookie = signIn()
    now = now.plusSeconds(ServeGithubAuth.SESSION_REFRESH_AFTER_SECONDS + 60)
    val refreshed = visit(cookie)
    assertTrue(refreshed != null, "a session past its half-life must be re-minted")
    assertEquals(ServeGithubAuth.SESSION_TTL_SECONDS, maxAge(refreshed))
    assertTrue(
      refreshed.substringAfter("cp_gh_auth=").substringBefore(";") !=
        cookie.substringAfter("cp_gh_auth=").substringBefore(";"),
      "the re-minted cookie must carry a later expiry than the one it replaces",
    )
    // …and the slid session is still live a full TTL past the expiry the original cookie carried,
    // which is the point of the exercise: a regular visitor never sees GitHub again.
    now = now.plusSeconds(ServeGithubAuth.SESSION_TTL_SECONDS - 120)
    assertTrue(
      visit(refreshed) != null,
      "the slid session must outlive the expiry baked into the cookie it replaced",
    )
  }

  @Test
  fun `an expired session is not resurrected`() {
    val cookie = signIn()
    now = now.plusSeconds(ServeGithubAuth.SESSION_TTL_SECONDS + 60)
    assertNull(visit(cookie), "an expired cookie must be re-authenticated, not extended")
  }

  /**
   * The absolute cap that makes sliding expiry safe: a refreshed cookie copies `repositoryAccess`
   * from sign-in with no token to re-check, so revoked access would otherwise persist by visiting.
   */
  @Test
  fun `sliding never carries a session past its absolute cap`() {
    val signedInAt = now
    var cookie = signIn()
    assertTrue(signedIn(cookie), "the front door greets a fresh session by name")
    // Visit diligently, every half-life, for well past the cap.
    var slides = 0
    while (now < signedInAt.plusSeconds(ServeGithubAuth.SESSION_ABSOLUTE_TTL_SECONDS * 2)) {
      now = now.plusSeconds(ServeGithubAuth.SESSION_REFRESH_AFTER_SECONDS + 60)
      val refreshed = visit(cookie) ?: continue
      slides++
      cookie = refreshed
      assertTrue(
        now.plusSeconds(maxAge(refreshed)) <=
          signedInAt.plusSeconds(ServeGithubAuth.SESSION_ABSOLUTE_TTL_SECONDS),
        "a slide must never push the expiry past the cap stamped at sign-in",
      )
    }
    assertTrue(slides > 0, "the session must have slid at least once before the cap bit")
    // Past the cap the visitor is signed out and has to go through GitHub again, which is what
    // re-computes their repository access.
    assertNull(visit(cookie), "a session at its absolute cap must not be extended")
    assertFalse(signedIn(cookie), "a session past its absolute cap must not authenticate")
  }

  /**
   * Whether this cookie still opens the repo-gated playground (200) rather than redirecting to
   * sign-in.
   */
  private fun signedIn(cookie: String): Boolean =
    client
      .newBuilder()
      .followRedirects(false)
      .build()
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/playground")
          .header("Cookie", cookie.substringBefore(";"))
          .build()
      )
      .execute()
      .use { resp -> resp.code == 200 }

  /** `POST /auth/github/logout`, returning the response's `cp_gh_auth` line and its `Location`. */
  private fun logout(cookie: String, query: String = ""): Pair<String?, String?> =
    noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}${ServeGithubAuth.LOGOUT_PATH}$query")
          .header("Cookie", cookie.substringBefore(";"))
          .post(EMPTY_FORM)
          .build()
      )
      .execute()
      .use { resp ->
        assertEquals(302, resp.code)
        resp.headers("Set-Cookie").firstOrNull { it.startsWith("cp_gh_auth=") } to
          resp.header("Location")
      }

  /** Sign-out, so a visitor on a borrowed machine need not clear site data. */
  @Test
  fun `signing out ends the session`() {
    val cookie = signIn()
    assertTrue(signedIn(cookie), "precondition: the fresh session opens the playground")
    val (cleared, location) = logout(cookie)
    assertTrue(cleared != null, "signing out must write a cp_gh_auth cookie of its own")
    // Both halves of the deletion: mismatched attributes leave the old cookie, and a browser
    // keeping an expired cookie must still get a meaningless value.
    assertEquals(0L, maxAge(cleared))
    assertEquals("", cleared.substringAfter("cp_gh_auth=").substringBefore(";"))
    assertEquals("/", location)
    assertFalse(signedIn(cleared), "the cleared cookie must not authenticate")
  }

  /**
   * `POST` only, so prefetchers, unfurlers or `<img src>` cannot sign someone out; no `GET` is
   * registered.
   */
  @Test
  fun `following the logout URL does not sign anyone out`() {
    val cookie = signIn()
    val response =
      noRedirect
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}${ServeGithubAuth.LOGOUT_PATH}")
            .header("Cookie", cookie.substringBefore(";"))
            .build()
        )
        .execute()
        .use { resp ->
          resp.code to resp.headers("Set-Cookie").firstOrNull { it.startsWith("cp_gh_auth=") }
        }
    assertNull(response.second, "a GET must not clear the session cookie")
    assertTrue(signedIn(cookie), "the session survives a GET of the logout path")
  }

  /**
   * Sign-out returns to the same page via the `return` parameter [ServeGithubAuth.loginPath] uses;
   * only same-origin relative paths survive, so it cannot be an open redirect.
   */
  @Test
  fun `signing out returns to the page it was invoked from`() {
    val cookie = signIn()
    assertEquals("/playground", logout(cookie, "?return=%2Fplayground").second)
    assertEquals("/", logout(cookie, "?return=https%3A%2F%2Fevil.example%2Fx").second)
    assertEquals("/", logout(cookie, "?return=%2F%2Fevil.example%2Fx").second)
  }

  /**
   * The sign-out response carries only the expired cookie: `refreshSession` skips `/auth/github/`,
   * so no fresh cookie is appended beside the deletion.
   */
  @Test
  fun `signing out past the half-life is not re-minted`() {
    val cookie = signIn()
    now = now.plusSeconds(ServeGithubAuth.SESSION_REFRESH_AFTER_SECONDS + 60)
    val cleared = logout(cookie).first
    assertTrue(cleared != null, "the deletion is still written")
    assertEquals("", cleared.substringAfter("cp_gh_auth=").substringBefore(";"))
    assertFalse(signedIn(cleared), "an aged session that signs out stays signed out")
  }

  private companion object {
    /** Ktor's CIO engine wants a body on a POST; the logout form carries no fields. */
    private val EMPTY_FORM = "".toRequestBody("application/x-www-form-urlencoded".toMediaType())
  }
}
