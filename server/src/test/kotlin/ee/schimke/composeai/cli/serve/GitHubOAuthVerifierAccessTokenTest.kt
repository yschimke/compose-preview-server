package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * [GitHubOAuthVerifier.verifyAccessToken] against a fake GitHub: which token kinds the image lane
 * accepts under each [ImageUploadTokenPolicy], and the `POST /applications/{client_id}/token` check
 * that tells this server's own tokens from other apps'.
 */
class GitHubOAuthVerifierAccessTokenTest {

  private val repository = "yschimke/compose-preview-server"
  private val app = GitHubOAuthApp("client-id", "client-secret")
  private val seen = mutableListOf<String>()

  /**
   * [appCheck] is the status `POST /applications/.../token` answers; [user] the status of `GET
   * /user` (403 is what an installation token gets). The repo grants write.
   */
  private fun verifier(appCheck: Int = 404, user: Int = 200): GitHubOAuthVerifier {
    val client =
      OkHttpClient.Builder()
        .addInterceptor(
          Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            seen += "${request.method} ${request.url.encodedPath}"
            val (code, body) =
              when {
                url.endsWith("/applications/client-id/token") -> {
                  assertEquals("POST", request.method)
                  assertEquals(
                    Credentials.basic("client-id", "client-secret"),
                    request.header("Authorization"),
                  )
                  val sent = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
                  assertTrue(sent.contains("\"access_token\""), sent)
                  appCheck to "{}"
                }
                url.endsWith("/user") -> user to """{"login":"octocat"}"""
                url.endsWith("/repos/$repository") ->
                  200 to """{"private":false,"permissions":{"push":true,"pull":true}}"""
                else -> error("unexpected request to $url")
              }
            Response.Builder()
              .request(request)
              .protocol(Protocol.HTTP_1_1)
              .code(code)
              .message("x")
              .body(body.toResponseBody("application/json".toMediaType()))
              .build()
          }
        )
        .build()
    return GitHubOAuthVerifier(client)
  }

  private val appDefault = ImageUploadTokenPolicy.parse(null, appConfigured = true)
  private val noAppDefault = ImageUploadTokenPolicy.parse(null, appConfigured = false)

  @Test
  fun `a user token issued to this app is accepted`() {
    val user =
      verifier(appCheck = 200)
        .verifyAccessToken("gho_mine", repository, tokens = appDefault, app = app)
        .getOrThrow()
    assertEquals("octocat", user.login)
    assertTrue(user.repositoryAccess)
    assertTrue("POST /applications/client-id/token" in seen, seen.toString())
  }

  @Test
  fun `a user token issued to another app is refused when this server has one`() {
    for (status in listOf(404, 422)) {
      val error =
        verifier(appCheck = status)
          .verifyAccessToken("gho_elsewhere", repository, tokens = appDefault, app = app)
          .exceptionOrNull()
      assertIs<ImageUploadTokenRefusedException>(error)
      assertTrue(error.message!!.contains("different OAuth app"), error.message)
    }
  }

  @Test
  fun `a GitHub App user token and an unprefixed token get the same check`() {
    for (token in listOf("ghu_elsewhere", "0123456789abcdef0123456789abcdef01234567")) {
      val error =
        verifier(appCheck = 404)
          .verifyAccessToken(token, repository, tokens = appDefault, app = app)
          .exceptionOrNull()
      assertIs<ImageUploadTokenRefusedException>(error, token)
    }
  }

  @Test
  fun `other apps' tokens are accepted without a check when the operator allows them`() {
    val policy = ImageUploadTokenPolicy.parse("app,other-apps", appConfigured = true)
    val user =
      verifier(appCheck = 500)
        .verifyAccessToken("gho_gh_cli", repository, tokens = policy, app = app)
        .getOrThrow()
    assertEquals("octocat", user.login)
    assertFalse(seen.any { it.contains("/applications/") }, seen.toString())
  }

  @Test
  fun `without an app of its own the default keeps accepting user tokens`() {
    val user =
      verifier().verifyAccessToken("gho_gh_cli", repository, tokens = noAppDefault).getOrThrow()
    assertEquals("octocat", user.login)
  }

  @Test
  fun `without an app, a policy that leaves out other-apps refuses user tokens`() {
    val policy = ImageUploadTokenPolicy.parse("personal,installation", appConfigured = false)
    val error =
      verifier().verifyAccessToken("gho_gh_cli", repository, tokens = policy).exceptionOrNull()
    assertIs<ImageUploadTokenRefusedException>(error)
  }

  @Test
  fun `a personal access token needs no app check`() {
    for (token in listOf("ghp_abc", "github_pat_abc")) {
      seen.clear()
      val user =
        verifier(appCheck = 500)
          .verifyAccessToken(token, repository, tokens = appDefault, app = app)
          .getOrThrow()
      assertEquals("octocat", user.login)
      assertFalse(seen.any { it.contains("/applications/") }, seen.toString())
    }
  }

  @Test
  fun `personal access tokens can be turned off`() {
    val policy = ImageUploadTokenPolicy.parse("app,installation", appConfigured = true)
    val error =
      verifier()
        .verifyAccessToken("ghp_abc", repository, tokens = policy, app = app)
        .exceptionOrNull()
    assertIs<ImageUploadTokenRefusedException>(error)
    assertTrue(seen.isEmpty(), "refused by shape, before any round trip: $seen")
  }

  @Test
  fun `an installation token with write is accepted by default`() {
    val user =
      verifier(user = 403)
        .verifyAccessToken("ghs_actions", repository, tokens = appDefault, app = app)
        .getOrThrow()
    assertEquals(GitHubOAuthVerifier.INSTALLATION_LOGIN, user.login)
    assertTrue(user.repositoryAccess)
  }

  @Test
  fun `installation tokens can be turned off`() {
    val policy = ImageUploadTokenPolicy.parse("app,personal", appConfigured = true)
    val byShape =
      verifier(user = 403)
        .verifyAccessToken("ghs_actions", repository, tokens = policy, app = app)
        .exceptionOrNull()
    assertIs<ImageUploadTokenRefusedException>(byShape)
    assertTrue(seen.isEmpty(), seen.toString())
    // An unprefixed credential that /user refuses doesn't fall through to the installation path.
    val unprefixed =
      verifier(user = 403)
        .verifyAccessToken("legacy", repository, tokens = policy, app = app)
        .exceptionOrNull()
    assertTrue(unprefixed != null && unprefixed !is GitHubCheckUnavailableException)
    assertFalse(seen.any { it.contains("/repos/") }, seen.toString())
  }

  @Test
  fun `GitHub not answering is not a verdict on the token`() {
    for (status in listOf(500, 401)) {
      val error =
        verifier(appCheck = status)
          .verifyAccessToken("gho_mine", repository, tokens = appDefault, app = app)
          .exceptionOrNull()
      assertIs<GitHubCheckUnavailableException>(error, "app check $status")
    }
    for (status in listOf(429, 502)) {
      val error =
        verifier(user = status)
          .verifyAccessToken("gho_mine", repository, tokens = appDefault, app = app)
          .exceptionOrNull()
      assertIs<GitHubCheckUnavailableException>(error, "user lookup $status")
    }
  }

  @Test
  fun `the policy flag parses, defaults by configuration, and rejects unknown kinds`() {
    assertEquals(
      ImageUploadTokenPolicy(personal = true, otherApps = false, installation = true),
      appDefault,
    )
    assertEquals(ImageUploadTokenPolicy.ANY, noAppDefault)
    assertEquals("app,personal,installation", appDefault.describe(appConfigured = true))
    assertEquals(
      ImageUploadTokenPolicy(personal = false, otherApps = false, installation = false),
      ImageUploadTokenPolicy.parse(" App ", appConfigured = true),
    )
    assertFailsWith<IllegalArgumentException> {
      ImageUploadTokenPolicy.parse("actions", appConfigured = true)
    }
  }

  @Test
  fun `the app's secret stays out of its string form`() {
    assertFalse(app.toString().contains("client-secret"), app.toString())
  }
}
