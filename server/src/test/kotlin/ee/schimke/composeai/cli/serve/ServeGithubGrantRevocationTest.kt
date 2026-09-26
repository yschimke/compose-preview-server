package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Old `repo` grants, and tokens in general, do not outlive sign-in.
 *
 * Sign-in used to ask for `repo`, and GitHub keeps handing a returning visitor every scope they
 * approved before — asking for less does not shrink it. So a token that comes back broader than
 * [ServeGithubAuth.ALLOWED_SCOPES] gets the visitor's whole authorization revoked and the visitor
 * sent round once more for a fresh, narrow consent. A token that is fine is revoked as soon as the
 * callback has read what it needs, since nothing here keeps it.
 */
class ServeGithubGrantRevocationTest {

  private class FakeGitHub(
    var grantedScope: String,
    var revokeCode: Int = 204,
  ) {
    val requests = mutableListOf<String>()
    val authorizations = mutableListOf<String?>()
    val bodies = mutableListOf<String>()

    val client: OkHttpClient =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          val request = chain.request()
          val path = request.url.encodedPath
          requests += "${request.method} $path"
          val (code, body) =
            when {
              path == "/login/oauth/access_token" ->
                200 to """{"access_token":"tok","scope":"$grantedScope"}"""
              path == "/user" -> 200 to """{"login":"octo"}"""
              path.startsWith("/applications/") -> {
                authorizations += request.header("Authorization")
                bodies += Buffer().also { request.body?.writeTo(it) }.readUtf8()
                revokeCode to ""
              }
              else -> 200 to """{"private":false,"permissions":{"push":true}}"""
            }
          Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()
  }

  private val config =
    ServeGithubAuthConfig(
      clientId = "client",
      clientSecret = "secret",
      cookieSecret = "x".repeat(32),
      repository = "yschimke/compose-ai-tools",
    )

  private fun verify(gitHub: FakeGitHub) =
    GitHubOAuthVerifier(gitHub.client).verify("code", "http://localhost/cb", config)

  @Test
  fun `a narrow grant signs in and its token is revoked straight after`() {
    val gitHub = FakeGitHub(grantedScope = "read:user")

    val user = verify(gitHub).getOrThrow()

    assertEquals("octo", user.login)
    assertEquals("DELETE /applications/client/token", gitHub.requests.last())
    assertTrue(gitHub.requests.none { it.endsWith("/grant") }, gitHub.requests.toString())
    // Basic auth with the app's own credentials, and the token named in the body.
    assertEquals(okhttp3.Credentials.basic("client", "secret"), gitHub.authorizations.single())
    assertEquals("""{"access_token":"tok"}""", gitHub.bodies.single())
  }

  @Test
  fun `a failed token revoke does not fail the sign-in`() {
    val gitHub = FakeGitHub(grantedScope = "read:user", revokeCode = 500)
    assertEquals("octo", verify(gitHub).getOrThrow().login)
  }

  @Test
  fun `a leftover repo grant is revoked whole and refused`() {
    val gitHub = FakeGitHub(grantedScope = "read:user,repo")

    val failure = verify(gitHub).exceptionOrNull()

    assertIs<GitHubGrantTooBroadException>(failure)
    assertEquals(setOf("repo"), failure.extraScopes)
    assertTrue(failure.revoked)
    assertTrue("DELETE /applications/client/grant" in gitHub.requests, gitHub.requests.toString())
    // Refused before the token is used for anything.
    assertTrue(gitHub.requests.none { it.endsWith("/user") }, gitHub.requests.toString())
  }

  @Test
  fun `a grant that could not be revoked says so`() {
    val gitHub = FakeGitHub(grantedScope = "repo", revokeCode = 404)

    val failure = verify(gitHub).exceptionOrNull()

    assertIs<GitHubGrantTooBroadException>(failure)
    assertFalse(failure.revoked)
  }

  // --- The callback, end to end ---

  private val gitHub = FakeGitHub(grantedScope = "read:user,repo")
  private val registry = ServeSessionRegistry(open = { null })
  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        githubAuth = ServeGithubAuth(config, verifier = GitHubOAuthVerifier(gitHub.client)),
      )
      .also { it.start() }
  }
  private val noRedirect = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  /** Starts a sign-in and runs its callback, returning (status, Location, Set-Cookie lines). */
  private fun callback(extraCookie: String? = null): Triple<Int, String, List<String>> {
    val (location, stateCookie) =
      noRedirect
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}/auth/github/start?return=%2Fplayground")
            .build()
        )
        .execute()
        .use { it.header("Location").orEmpty() to it.header("Set-Cookie")!!.substringBefore(";") }
    val state = location.substringAfter("state=").substringBefore("&")
    val cookies = listOfNotNull(stateCookie, extraCookie).joinToString("; ")
    return noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/auth/github/callback?code=ok&state=$state")
          .header("Cookie", cookies)
          .build()
      )
      .execute()
      .use { Triple(it.code, it.header("Location").orEmpty(), it.headers("Set-Cookie")) }
  }

  @Test
  fun `a revoked broad grant sends the visitor round for a fresh consent, once`() {
    val (code, location, cookies) = callback()

    assertEquals(302, code)
    assertEquals("/auth/github/start?return=%2Fplayground", location)
    assertTrue(cookies.any { it.startsWith("cp_gh_regrant=1") }, cookies.toString())
    assertTrue(cookies.none { it.startsWith("cp_gh_auth=") && !it.startsWith("cp_gh_auth=;") })

    // Came back broad again despite the revoke: explain rather than loop.
    val (again, _, _) = callback(extraCookie = "cp_gh_regrant=1")
    assertEquals(403, again)
  }

  @Test
  fun `a narrow grant after the retry signs in and clears the marker`() {
    gitHub.grantedScope = "read:user"

    val (code, location, cookies) = callback(extraCookie = "cp_gh_regrant=1")

    assertEquals(302, code)
    assertEquals("/playground", location)
    assertTrue(cookies.any { it.startsWith("cp_gh_auth=") }, cookies.toString())
    assertTrue(
      cookies.any { it.startsWith("cp_gh_regrant=;") || it.startsWith("cp_gh_regrant=\"\"") }
    )
  }
}
