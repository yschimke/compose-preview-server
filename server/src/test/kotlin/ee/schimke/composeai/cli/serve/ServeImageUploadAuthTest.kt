package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the gate charges an upload to, which is not always who it says uploaded.
 *
 * The verification *rules* live in [GitHubOAuthVerifier] and need GitHub to exercise; what is
 * pinned here is the part that is this lane's own decision — that two GitHub App installations,
 * which necessarily share one placeholder login, do not share one rate-limit bucket.
 */
class ServeImageUploadAuthTest {

  private fun auth(users: Map<String, GitHubOAuthUser>) =
    GithubTokenUploadAuth(
      repository = "yschimke/compose-ai-tools",
      verifier = { token, _, _ ->
        users[token]?.let { Result.success(it) }
          ?: Result.failure(IllegalStateException("user lookup failed: 401"))
      },
    )

  @Test
  fun `a user is charged to their login`() {
    val gate = auth(mapOf("t" to GitHubOAuthUser("octocat", repositoryAccess = true)))
    val ok = gate.identify("t") as ServeImageUploadAuth.Identity.Ok
    assertEquals("octocat", ok.login)
    assertEquals("gh:octocat", ok.budgetKey)
  }

  @Test
  fun `two app installations share a login but not a budget`() {
    val installation = GitHubOAuthUser(GitHubOAuthVerifier.INSTALLATION_LOGIN, true)
    val gate = auth(mapOf("app-one" to installation, "app-two" to installation))
    val first = gate.identify("app-one") as ServeImageUploadAuth.Identity.Ok
    val second = gate.identify("app-two") as ServeImageUploadAuth.Identity.Ok
    assertEquals(first.login, second.login, "there is no user behind either")
    assertNotEquals(
      first.budgetKey,
      second.budgetKey,
      "one app exhausting its budget must not 429 another",
    )
    // The key stands for the credential, and must not be the credential.
    assertTrue(first.budgetKey.startsWith("app:"), first.budgetKey)
    assertTrue("app-one" !in first.budgetKey, first.budgetKey)
  }

  @Test
  fun `an unverifiable credential is refused, and a missing one is not the same thing`() {
    val gate = auth(emptyMap())
    assertEquals(ServeImageUploadAuth.Identity.Missing, gate.identify(null))
    assertEquals(ServeImageUploadAuth.Identity.Missing, gate.identify("   "))
    val refused = gate.identify("bad") as ServeImageUploadAuth.Identity.Refused
    assertEquals(401, refused.status)
    assertTrue("bad" !in refused.reason, "the credential must never travel back out")
  }

  @Test
  fun `an account without repository access is a distinct refusal`() {
    val gate = auth(mapOf("t" to GitHubOAuthUser("stranger", repositoryAccess = false)))
    val refused = gate.identify("t") as ServeImageUploadAuth.Identity.Refused
    assertEquals(403, refused.status)
    assertTrue(refused.reason.contains("stranger"), refused.reason)
  }

  @Test
  fun `a verified token is asked about again after a minute`() {
    var now = 0L
    var calls = 0
    val gate =
      GithubTokenUploadAuth(
        repository = "yschimke/compose-ai-tools",
        verifier = { _, _, _ ->
          calls++
          Result.success(GitHubOAuthUser("octocat", repositoryAccess = true))
        },
        clock = { now },
      )
    gate.identify("t")
    now += 59_000
    gate.identify("t")
    assertEquals(1, calls, "a batch verifies once")
    now += 2_000
    gate.identify("t")
    assertEquals(2, calls, "a minute later GitHub is asked again")
  }

  @Test
  fun `GitHub not answering is retried on the next request, not cached`() {
    var down = true
    val gate =
      GithubTokenUploadAuth(
        repository = "yschimke/compose-ai-tools",
        verifier = { _, _, _ ->
          if (down) Result.failure(GitHubCheckUnavailableException("user lookup failed: 502"))
          else Result.success(GitHubOAuthUser("octocat", repositoryAccess = true))
        },
        clock = { 0L },
      )
    val refused = gate.identify("t") as ServeImageUploadAuth.Identity.Refused
    assertEquals(503, refused.status)
    down = false
    assertTrue(gate.identify("t") is ServeImageUploadAuth.Identity.Ok)
  }

  @Test
  fun `a token kind this host does not accept is a 403 naming what it does`() {
    val gate =
      GithubTokenUploadAuth(
        repository = "yschimke/compose-ai-tools",
        tokens = ImageUploadTokenPolicy.parse(null, appConfigured = true),
        app = GitHubOAuthApp("id", "secret"),
        verifier = { _, _, _ ->
          Result.failure(
            ImageUploadTokenRefusedException(
              "that token was issued to a different OAuth app than this server's"
            )
          )
        },
      )
    val refused = gate.identify("gho_x") as ServeImageUploadAuth.Identity.Refused
    assertEquals(403, refused.status)
    assertTrue(refused.reason.startsWith("That token was issued"), refused.reason)
    assertTrue(refused.reason.contains("app,personal,installation"), refused.reason)
    assertTrue("gho_x" !in refused.reason)
  }
}
