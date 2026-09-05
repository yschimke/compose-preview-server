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
 * How long a GitHub sign-in lasts, and how it renews.
 *
 * The session is a signed cookie with a baked-in expiry and no server-side store, so the only way
 * to keep an active visitor signed in is to hand back a fresh cookie as the old one ages. Before
 * this existed the expiry was a flat 12 hours from sign-in with no renewal at all, which is shorter
 * than the gap between one working day and the next — a visitor who signed in yesterday was
 * reliably signed out this morning.
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
      anonymousClient = fakeGitHub,
    )

  private val registry = ServeSessionRegistry(open = { null })

  private val fs = FakeFileSystem()

  /**
   * Wired only so `/playground` exists: it is the route that actually *consults* the session (302
   * to sign-in when there is none, 200 for a signed-in visitor whom the fake GitHub grants repo
   * rights), which makes it the honest probe for "is this cookie still an authenticated visitor
   * with playground rights". No compile is performed.
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
   * The cap that makes the sliding expiry safe. A refreshed cookie copies the `repositoryAccess`
   * flag GitHub computed at sign-in — the playground gate — and there is no stored access token to
   * re-ask with, so without an absolute ceiling somebody whose repo access was revoked would keep
   * the gate open simply by continuing to visit. Sliding must therefore run out.
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
   * Whether this cookie still opens the repo-gated playground — 200 for a signed-in visitor the
   * fake GitHub granted push rights, a redirect to the sign-in for anyone the session no longer
   * authenticates. This is the gate the absolute cap exists to close.
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

  /**
   * The eject button. Before it existed the only way to end a session was DevTools: the cookie is
   * self-renewing for a week of idle and capped at a fortnight, so a visitor on a borrowed machine
   * had to clear site data.
   */
  @Test
  fun `signing out ends the session`() {
    val cookie = signIn()
    assertTrue(signedIn(cookie), "precondition: the fresh session opens the playground")
    val (cleared, location) = logout(cookie)
    assertTrue(cleared != null, "signing out must write a cp_gh_auth cookie of its own")
    // Both halves of the deletion, because either alone is a way for this to silently not work: an
    // attribute set the browser does not match leaves the old cookie in place, and a browser that
    // keeps an expired cookie anyway still has to be told the value means nothing.
    assertEquals(0L, maxAge(cleared))
    assertEquals("", cleared.substringAfter("cp_gh_auth=").substringBefore(";"))
    assertEquals("/", location)
    assertFalse(signedIn(cleared), "the cleared cookie must not authenticate")
  }

  /**
   * `POST` only, for the reason the approval flow is: a sign-out a prefetcher, a link-unfurler or
   * somebody else's `<img src>` can fire by *looking at a URL* is not one. No `GET` is registered,
   * so following the path leaves the session exactly where it was.
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
   * Signing out of `/playground` lands back on `/playground` rather than at the index — the same
   * `return` parameter [ServeGithubAuth.loginPath] already round-trips, and the same safety rule:
   * only a same-origin relative path survives, so the control can never become an open redirect.
   */
  @Test
  fun `signing out returns to the page it was invoked from`() {
    val cookie = signIn()
    assertEquals("/playground", logout(cookie, "?return=%2Fplayground").second)
    assertEquals("/", logout(cookie, "?return=https%3A%2F%2Fevil.example%2Fx").second)
    assertEquals("/", logout(cookie, "?return=%2F%2Fevil.example%2Fx").second)
  }

  /**
   * The sign-out response carries the expired cookie and nothing else. `refreshSession` skips
   * everything under `/auth/github/`, so an ageing session cannot have a freshly-minted cookie
   * appended beside its own deletion — two `Set-Cookie` lines for one name being a coin flip
   * between them.
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
