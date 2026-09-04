package ee.schimke.composeai.cli.serve

import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A box whose image lane gates on a **different** repository to sign-in.
 *
 * This combination used to be refused at startup, because the session carried one access bit
 * computed against `--github-auth-repo` and there was nothing honest to answer an `images` approval
 * with. The fix is to compute the second bit in the one moment the visitor's token exists — the
 * OAuth callback — and carry it in the signed cookie, which is what these tests pin: the two bits
 * are independent, the second costs nothing when the repositories are the same, and it survives
 * into the cookie the approval page later reads.
 */
class ServeGithubImageRepoSessionTest {

  private val AUTH_REPO = "yschimke/compose-ai-tools"
  private val IMAGE_REPO = "yschimke/compose-preview-server"

  /** Every `/repos/…` path the verifier asked about, in order. */
  private val repoCalls = CopyOnWriteArrayList<String>()

  /**
   * Grants push on [AUTH_REPO] and refuses it on [IMAGE_REPO], both public — so the two questions
   * have genuinely different answers and a test cannot pass by conflating them.
   */
  private fun gitHub(
    authPush: Boolean = true,
    imagePush: Boolean = false,
  ): OkHttpClient =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        val body =
          when {
            path == "/login/oauth/access_token" -> """{"access_token":"token"}"""
            path == "/user" -> """{"login":"octo"}"""
            path.startsWith("/repos/") -> {
              repoCalls += path
              val push = if (path.contains(IMAGE_REPO)) imagePush else authPush
              """{"private":false,"permissions":{"push":$push}}"""
            }
            else -> "{}"
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

  private fun config(imageRepository: String? = null) =
    ServeGithubAuthConfig(
      clientId = "client",
      clientSecret = "secret",
      cookieSecret = "x".repeat(32),
      repository = AUTH_REPO,
      imageRepository = imageRepository,
    )

  @Test
  fun `the two repositories are asked about separately`() {
    val user =
      GitHubOAuthVerifier(gitHub())
        .verify("code", "http://localhost/callback", config(IMAGE_REPO))
        .getOrThrow()
    assertEquals("octo", user.login)
    assertTrue(user.repositoryAccess, "push on the sign-in repo")
    assertFalse(user.imageRepositoryAccess, "no push on the image repo")
    assertTrue(repoCalls.any { it.contains(AUTH_REPO) })
    assertTrue(repoCalls.any { it.contains(IMAGE_REPO) })
  }

  @Test
  fun `access to the image repo alone does not confer access to the sign-in repo`() {
    val user =
      GitHubOAuthVerifier(gitHub(authPush = false, imagePush = true))
        .verify("code", "http://localhost/callback", config(IMAGE_REPO))
        .getOrThrow()
    assertFalse(user.repositoryAccess)
    assertTrue(user.imageRepositoryAccess)
  }

  @Test
  fun `one repository for both lanes costs no second round trip`() {
    val user =
      GitHubOAuthVerifier(gitHub())
        .verify("code", "http://localhost/callback", config(imageRepository = AUTH_REPO))
        .getOrThrow()
    assertTrue(user.imageRepositoryAccess)
    assertEquals(1, repoCalls.size, "the same repository is not looked up twice")
  }

  @Test
  fun `an unconfigured image repository mirrors the sign-in answer`() {
    val user =
      GitHubOAuthVerifier(gitHub(authPush = false))
        .verify("code", "http://localhost/callback", config(imageRepository = null))
        .getOrThrow()
    assertFalse(user.repositoryAccess)
    assertFalse(user.imageRepositoryAccess)
    assertEquals(1, repoCalls.size)
  }

  /**
   * The bit has to reach the approval page, and the only thing that carries it there is the cookie.
   * Read back through the signed payload rather than a route, because that is where the wire shape
   * — an appended field, so older cookies still parse — actually lives.
   */
  @Test
  fun `the session cookie carries the image bit`() {
    val cookie = signInCookie(gitHub(authPush = true, imagePush = true))
    assertTrue(cookie.endsWith("|image-repo"), cookie)
    val refused = signInCookie(gitHub(authPush = true, imagePush = false))
    assertTrue(refused.endsWith("|no-image-repo"), refused)
    // The sign-in bit is still its own field, unmoved, so the older parsers keep reading it.
    assertTrue(refused.split("|")[1] == "repo", refused)
  }

  /**
   * What the *upload* paths ask, on the cookie shapes a running box actually holds.
   *
   * The image bit reaches every gate through this one call — the bug-report page's uploader as well
   * as the agent-grant approval page — so its treatment of a cookie minted before the field existed
   * decides whether shipping the field signs everybody's uploads out for a week.
   */
  @Test
  fun `a legacy cookie still uploads when both lanes gate on one repository`() {
    val sameRepo = ServeGithubAuth(config(imageRepository = null))
    assertTrue(sameRepo.hasImageRepositoryAccess(legacyCookie(repositoryAccess = true)))
    assertFalse(sameRepo.hasImageRepositoryAccess(legacyCookie(repositoryAccess = false)))
    // Naming the same repository explicitly is the same box, so it answers the same.
    val named = ServeGithubAuth(config(imageRepository = AUTH_REPO.uppercase()))
    assertTrue(named.hasImageRepositoryAccess(legacyCookie(repositoryAccess = true)))
  }

  /**
   * …and never on a box whose lanes gate on different repositories: there the sign-in bit is an
   * answer about a repository the image lane does not publish to, which is the conflation the
   * appended field exists to end. Such a visitor gets the capability back by signing in again.
   */
  @Test
  fun `a legacy cookie confers nothing when the image lane gates elsewhere`() {
    val split = ServeGithubAuth(config(IMAGE_REPO))
    assertFalse(split.hasImageRepositoryAccess(legacyCookie(repositoryAccess = true)))
  }

  /** The repository a session's image verdict speaks for — what an upload path compares against. */
  @Test
  fun `the image verdict names the image repository, falling back to the sign-in one`() {
    assertEquals(IMAGE_REPO, ServeGithubAuth(config(IMAGE_REPO)).imageAccessRepository())
    assertEquals(AUTH_REPO, ServeGithubAuth(config(imageRepository = null)).imageAccessRepository())
    assertEquals(AUTH_REPO, ServeGithubAuth(config(imageRepository = "  ")).imageAccessRepository())
  }

  /**
   * A 4-part cookie — login, repo flag, expiry, sign-in stamp — signed the way the server signs.
   */
  private fun legacyCookie(repositoryAccess: Boolean): String {
    val now = System.currentTimeMillis()
    val expiry = now + ServeGithubAuth.SESSION_TTL_SECONDS * 1000
    val payload = "octo|${if (repositoryAccess) "repo" else "no-repo"}|$expiry|$now"
    val encoded =
      Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec("x".repeat(32).toByteArray(Charsets.UTF_8), "HmacSHA256"))
    val sig =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(encoded.toByteArray(Charsets.UTF_8)))
    return "$encoded.$sig"
  }

  /** Runs the callback against [client] and returns the decoded session payload. */
  private fun signInCookie(client: OkHttpClient): String {
    val auth =
      ServeGithubAuth(
        config(IMAGE_REPO),
        verifier = GitHubOAuthVerifier(client),
        anonymousClient = client,
      )
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused-in-public",
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "none",
          isPublic = true,
          githubAuth = auth,
        )
        .also { it.start() }
    try {
      val http = OkHttpClient.Builder().followRedirects(false).build()
      val start =
        http
          .newCall(
            Request.Builder().url("http://127.0.0.1:${server.port}/auth/github/start").build()
          )
          .execute()
          .use { resp ->
            resp.header("Location").orEmpty() to
              resp.header("Set-Cookie").orEmpty().substringBefore(";")
          }
      val state = start.first.substringAfter("state=").substringBefore("&")
      val session =
        http
          .newCall(
            Request.Builder()
              .url("http://127.0.0.1:${server.port}/auth/github/callback?code=ok&state=$state")
              .header("Cookie", start.second)
              .build()
          )
          .execute()
          .use { resp -> resp.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") } }
      val value = session.substringAfter("cp_gh_auth=").substringBefore(";")
      return String(Base64.getUrlDecoder().decode(value.substringBefore(".")))
    } finally {
      runCatching { server.stop() }
    }
  }
}
