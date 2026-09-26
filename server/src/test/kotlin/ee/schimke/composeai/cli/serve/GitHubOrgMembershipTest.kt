package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * `--github-auth-orgs`: members of a named GitHub organization sign in as members, exactly as a
 * login on `--github-auth-users` does, and everybody else is a guest or refused.
 */
class GitHubOrgMembershipTest {

  private fun config(
    users: Set<String> = emptySet(),
    orgs: Set<String> = setOf("google"),
    guests: Boolean = true,
    scope: String? = null,
  ) =
    ServeGithubAuthConfig(
      clientId = "id",
      clientSecret = "secret",
      cookieSecret = "0123456789012345678901234567890123",
      repository = "yschimke/compose-ai-tools",
      allowedUsers = users,
      allowedOrgs = orgs,
      allowGuests = guests,
      oauthScope = scope,
    )

  /**
   * GitHub as seen by `verify`, with the two membership endpoints answering [membership] (the
   * private `/user/memberships/orgs/{org}` view: a state, or null for 403) and [publicMember] (the
   * scope-free `/orgs/{org}/public_members/{login}` view: 204 or 404).
   */
  private fun verifier(
    membership: String?,
    publicMember: Boolean,
    requests: MutableList<String> = mutableListOf(),
  ): GitHubOAuthVerifier {
    val client =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          val url = chain.request().url.toString()
          requests += url
          val (code, body) =
            when {
              url.contains("login/oauth/access_token") -> 200 to """{"access_token":"t"}"""
              url.endsWith("/user") -> 200 to """{"login":"Octocat"}"""
              url.contains("/user/memberships/orgs/") ->
                if (membership == null) 403 to "{}" else 200 to """{"state":"$membership"}"""
              url.contains("/public_members/") -> (if (publicMember) 204 else 404) to ""
              url.contains("/repos/") -> 200 to """{"private":false}"""
              url.contains("/collaborators/") -> 200 to """{"permission":"read"}"""
              else -> error("unexpected request to $url")
            }
          Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("x")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        }
        .build()
    return GitHubOAuthVerifier(client)
  }

  private fun signIn(
    membership: String?,
    publicMember: Boolean,
    config: ServeGithubAuthConfig = config(),
  ): GitHubOAuthUser =
    verifier(membership, publicMember).verify("code", "https://x.test/cb", config).getOrThrow()

  @Test
  fun `an active org member signs in as a member`() {
    assertFalse(signIn(membership = "active", publicMember = false).guest)
  }

  /**
   * The case a large org's OAuth-app restriction produces: 403 privately, but public on the org.
   */
  @Test
  fun `a public member counts when the private view is refused`() {
    assertFalse(signIn(membership = null, publicMember = true).guest)
  }

  @Test
  fun `a pending invitation is not membership`() {
    assertTrue(signIn(membership = "pending", publicMember = false).guest)
  }

  @Test
  fun `a non-member is a guest`() {
    assertTrue(signIn(membership = null, publicMember = false).guest)
  }

  @Test
  fun `a non-member is refused when guests are off`() {
    assertFailsWith<IllegalStateException> {
      signIn(membership = null, publicMember = false, config = config(guests = false))
    }
  }

  @Test
  fun `a named login is admitted without asking GitHub about orgs`() {
    val requests = mutableListOf<String>()
    val user =
      verifier(membership = null, publicMember = false, requests = requests)
        .verify("code", "https://x.test/cb", config(users = setOf("octocat")))
        .getOrThrow()
    assertFalse(user.guest)
    assertTrue(requests.none { "/orgs/" in it })
  }

  @Test
  fun `orgs add read org to the default and to a pinned scope`() {
    assertEquals("read:user read:org", ServeGithubAuth(config()).requestedScope())
    assertEquals(
      "user:email read:org",
      ServeGithubAuth(config(scope = "user:email")).requestedScope(),
    )
    assertEquals(
      "read:user read:org",
      ServeGithubAuth(config(scope = "read:user read:org")).requestedScope(),
    )
    assertEquals("read:user", ServeGithubAuth(config(orgs = emptySet())).requestedScope())
  }

  @Test
  fun `orgs alone restrict membership`() {
    assertTrue(config().restrictsMembership)
    assertFalse(config(orgs = emptySet()).restrictsMembership)
  }

  @Test
  fun `an org that is not a GitHub login is refused at startup`() {
    assertFailsWith<IllegalArgumentException> { config(orgs = setOf("google/android")) }
  }
}
