package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What we ask a visitor to consent to at sign-in: who they are, and nothing about their
 * repositories.
 *
 * This used to escalate to `read:user repo` whenever the gating repo was private, or whenever an
 * anonymous visibility probe failed. `repo` is full control of *private* repositories — read and
 * write, code and settings, across every private repo the visitor can reach — and sign-in never
 * needs it, so it is never asked for, and an operator cannot pin it either.
 */
class ServeGithubAuthScopeTest {

  private fun config(
    repository: String = "yschimke/compose-ai-tools",
    scope: String? = null,
    imageRepository: String? = null,
  ) =
    ServeGithubAuthConfig(
      clientId = "id",
      clientSecret = "secret",
      cookieSecret = "0123456789012345678901234567890123",
      repository = repository,
      imageRepository = imageRepository,
      oauthScope = scope,
    )

  private fun scopeFor(scope: String? = null, imageRepository: String? = null): String =
    ServeGithubAuth(config(scope = scope, imageRepository = imageRepository)).requestedScope()

  @Test
  fun `sign-in asks only for the visitor's profile`() {
    assertEquals("read:user", scopeFor())
    assertEquals(ServeGithubAuth.USER_SCOPE, scopeFor())
  }

  /** A second gating repository, private or not, must not widen the consent either. */
  @Test
  fun `an image repo does not widen the scope`() {
    assertEquals(ServeGithubAuth.USER_SCOPE, scopeFor(imageRepository = "yschimke/private-uploads"))
  }

  @Test
  fun `a read-only identity override is honoured`() {
    assertEquals("user:email", scopeFor(scope = "user:email"))
    assertEquals("read:user user:email", scopeFor(scope = "read:user user:email"))
  }

  @Test
  fun `a blank override falls back to the default`() {
    assertEquals(ServeGithubAuth.USER_SCOPE, scopeFor(scope = "   "))
  }

  /**
   * Anything that reaches repositories, or writes anything, is refused before the server starts.
   */
  @Test
  fun `repository and write scopes are refused at startup`() {
    for (scope in
      listOf(
        "repo",
        "read:user repo",
        "public_repo",
        "read:user,repo",
        "user",
        "write:org",
        "gist",
      )) {
      val error = assertFailsWith<IllegalArgumentException>(scope) { config(scope = scope) }
      assertTrue(error.message.orEmpty().contains("not allowed"), error.message)
    }
  }

  @Test
  fun `the requested scope never contains repo`() {
    for (scope in listOf(null, "read:user", "user:email", "read:org")) {
      val requested = scopeFor(scope = scope).split(' ', ',')
      assertTrue(requested.none { it == "repo" || it.endsWith("_repo") }, requested.toString())
    }
  }
}
