package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure decisions behind the OAuth façade: what may be redirected, what may not, and what a
 * stolen code is worth without its verifier.
 *
 * These are the parts worth testing without Ktor, because they are the parts where being subtly
 * wrong is silent — an open redirector and a PKCE check that accepts anything both look exactly
 * like a working login.
 */
class ServeMcpOAuthTest {

  private fun client(vararg redirectUris: String) =
    ServeMcpOAuth.RegisteredClient(
      clientId = "cid",
      clientName = "test client",
      redirectUris = redirectUris.toList(),
      issuedAtMillis = 0,
    )

  private fun challengeFor(verifier: String): String =
    Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
      )

  /** RFC 7636 §4.1 wants 43..128 chars; this is the shortest legal one. */
  private val verifier = "a".repeat(43)

  // ------------------------------------------------------------- redirect URIs

  @Test
  fun `a redirect URI must match the registered one exactly`() {
    val c = client("http://127.0.0.1:8976/callback")
    assertTrue(ServeMcpOAuth.isRegisteredRedirect(c, "http://127.0.0.1:8976/callback"))
    assertFalse(ServeMcpOAuth.isRegisteredRedirect(c, "http://127.0.0.1:8976/callback/evil"))
    assertFalse(ServeMcpOAuth.isRegisteredRedirect(c, "https://127.0.0.1:8976/callback"))
  }

  @Test
  fun `a loopback client may bind a different port than it registered`() {
    // RFC 8252 §7.3: the port is chosen at bind time, so it cannot be known at registration.
    val c = client("http://127.0.0.1:1/callback")
    assertTrue(ServeMcpOAuth.isRegisteredRedirect(c, "http://127.0.0.1:54321/callback"))
    // …but only the port moves. A different path is a different endpoint.
    assertFalse(ServeMcpOAuth.isRegisteredRedirect(c, "http://127.0.0.1:54321/other"))
  }

  @Test
  fun `the loopback concession never applies to a named host`() {
    val c = client("https://app.example.com/callback")
    assertFalse(ServeMcpOAuth.isRegisteredRedirect(c, "https://app.example.com:8443/callback"))
    assertFalse(ServeMcpOAuth.isRegisteredRedirect(c, "https://evil.example.com/callback"))
  }

  // -------------------------------------------------------------- authorize

  @Test
  fun `an unusable redirect target is never redirected to`() {
    // The whole point: an error about the redirect URI must be RENDERED, because redirecting it
    // would make this server an open redirector (RFC 6749 4.1.2.1).
    val unknownClient =
      ServeMcpOAuth.validateAuthorize(null, "https://evil.example/x", "code", "ch", "S256")
    assertTrue(unknownClient is ServeMcpOAuth.AuthorizeRejection.Unredirectable)

    val unregistered =
      ServeMcpOAuth.validateAuthorize(
        client("https://good.example/cb"),
        "https://evil.example/x",
        "code",
        "ch",
        "S256",
      )
    assertTrue(unregistered is ServeMcpOAuth.AuthorizeRejection.Unredirectable)
  }

  @Test
  fun `errors after the redirect URI is trusted go back to the client`() {
    val c = client("https://good.example/cb")
    val badResponseType =
      ServeMcpOAuth.validateAuthorize(c, "https://good.example/cb", "token", "ch", "S256")
    assertTrue(badResponseType is ServeMcpOAuth.AuthorizeRejection.Redirectable)
    assertEquals("unsupported_response_type", badResponseType.error)
  }

  @Test
  fun `PKCE is mandatory and plain is refused`() {
    val c = client("https://good.example/cb")
    val missing =
      ServeMcpOAuth.validateAuthorize(c, "https://good.example/cb", "code", null, "S256")
    assertTrue(missing is ServeMcpOAuth.AuthorizeRejection.Redirectable)

    val plain = ServeMcpOAuth.validateAuthorize(c, "https://good.example/cb", "code", "ch", "plain")
    assertTrue(plain is ServeMcpOAuth.AuthorizeRejection.Redirectable)
    assertTrue(plain.description.contains("plain"))
  }

  @Test
  fun `a well formed authorization request is accepted`() {
    assertNull(
      ServeMcpOAuth.validateAuthorize(
        client("https://good.example/cb"),
        "https://good.example/cb",
        "code",
        challengeFor(verifier),
        "S256",
      )
    )
  }

  // ------------------------------------------------------------------ PKCE

  @Test
  fun `the verifier must hash to the challenge`() {
    val challenge = challengeFor(verifier)
    assertTrue(ServeMcpOAuth.verifyPkce(challenge, verifier))
    assertFalse(ServeMcpOAuth.verifyPkce(challenge, "b".repeat(43)))
    assertFalse(ServeMcpOAuth.verifyPkce(challenge, null))
    assertFalse(ServeMcpOAuth.verifyPkce(challenge, ""))
  }

  @Test
  fun `a verifier outside the RFC's length bounds is rejected before it is hashed`() {
    val short = "a".repeat(42)
    val long = "a".repeat(129)
    assertFalse(ServeMcpOAuth.verifyPkce(challengeFor(short), short))
    assertFalse(ServeMcpOAuth.verifyPkce(challengeFor(long), long))
  }

  // ----------------------------------------------------------------- codes

  @Test
  fun `a code redeems once and never again`() {
    val store = ServeMcpOAuth.Store()
    val authorization =
      assertNotNull(store.open("req", "cid", "https://good.example/cb", "ch", "st", ""))
    assertNotNull(store.redeem(authorization.code))
    assertNull(store.redeem(authorization.code))
  }

  @Test
  fun `an unknown code redeems to nothing`() {
    val store = ServeMcpOAuth.Store()
    assertNull(store.redeem("nope"))
    assertNull(store.redeem(null))
  }

  @Test
  fun `an expired authorization is neither findable nor redeemable`() {
    var now = 0L
    val store = ServeMcpOAuth.Store { now }
    val authorization =
      assertNotNull(store.open("req", "cid", "https://good.example/cb", "ch", "st", ""))
    assertNotNull(store.forRequest("req"))
    now += (ServeMcpOAuth.AUTHORIZATION_TTL_SECONDS + 1) * 1000
    assertNull(store.forRequest("req"))
    assertNull(store.redeem(authorization.code))
  }

  @Test
  fun `the decision handler finds the return leg by the request id it holds`() {
    val store = ServeMcpOAuth.Store()
    val authorization =
      assertNotNull(store.open("req-7", "cid", "https://good.example/cb", "ch", "st", ""))
    assertEquals(authorization.code, store.forRequest("req-7")?.code)
    assertNull(store.forRequest("req-8"))
    assertNull(store.forRequest(null))
  }

  @Test
  fun `pending authorizations are bounded`() {
    val store = ServeMcpOAuth.Store()
    repeat(ServeMcpOAuth.MAX_PENDING_AUTHORIZATIONS) {
      assertNotNull(store.open("req-$it", "cid", "https://good.example/cb", "ch", "", ""))
    }
    assertNull(store.open("one-too-many", "cid", "https://good.example/cb", "ch", "", ""))
  }

  @Test
  fun `registered clients are bounded`() {
    val store = ServeMcpOAuth.Store()
    repeat(ServeMcpOAuth.MAX_REGISTERED_CLIENTS) {
      assertNotNull(store.register("client $it", listOf("https://good.example/cb")))
    }
    assertNull(store.register("one too many", listOf("https://good.example/cb")))
  }

  // ------------------------------------------------------------- redirects

  @Test
  fun `state is echoed when present and omitted when not`() {
    val with = ServeMcpOAuth.redirectWithCode("https://good.example/cb", "CODE", "xyz")
    assertTrue(with.contains("code=CODE"))
    assertTrue(with.contains("state=xyz"))

    val without = ServeMcpOAuth.redirectWithCode("https://good.example/cb", "CODE", "")
    assertFalse(without.contains("state="))
  }

  @Test
  fun `a redirect URI that already carries a query keeps it`() {
    val built = ServeMcpOAuth.redirectWithCode("https://good.example/cb?a=b", "CODE", "")
    assertTrue(built.startsWith("https://good.example/cb?a=b&"))
    assertTrue(built.contains("code=CODE"))
  }

  @Test
  fun `redirect parameters are percent encoded`() {
    val built =
      ServeMcpOAuth.redirectWithError(
        "https://good.example/cb",
        "access_denied",
        "The request was declined & discarded",
        "a b",
      )
    assertTrue(built.contains("error=access_denied"))
    // The ampersand must not be able to forge a parameter of its own.
    assertFalse(built.contains("declined & discarded"))
    assertTrue(built.contains("state=a+b"))
  }

  // -------------------------------------------------------------- scopes

  @Test
  fun `an OAuth scope string maps onto the ladder and the capability set`() {
    val parsed = ServeMcpOAuth.parseScope("live ui-builder-write images")
    assertEquals(AgentGrantScope.LIVE, parsed.scope)
    assertTrue(AgentGrantCapability.UI_BUILDER_WRITE in parsed.capabilities)
    assertTrue(AgentGrantCapability.IMAGES in parsed.capabilities)
  }

  @Test
  fun `several rungs in one scope string resolve to the highest`() {
    assertEquals(AgentGrantScope.PLAYGROUND, ServeMcpOAuth.parseScope("preview playground").scope)
  }

  @Test
  fun `an absent scope lands on the same floor an empty grant request does`() {
    assertEquals(AgentGrantScope.DEFAULT_REQUEST, ServeMcpOAuth.parseScope(null).scope)
    assertEquals(AgentGrantScope.DEFAULT_REQUEST, ServeMcpOAuth.parseScope("  ").scope)
    assertTrue(ServeMcpOAuth.parseScope(null).capabilities.isEmpty())
  }

  @Test
  fun `an unknown scope token is ignored rather than refused`() {
    // Same posture as the device flow's `capabilities`: a newer client naming something this
    // server has never heard of should get the rest of its request honoured.
    val parsed = ServeMcpOAuth.parseScope("live not-a-real-scope")
    assertEquals(AgentGrantScope.LIVE, parsed.scope)
  }

  // ----------------------------------------------------------- the challenge

  @Test
  fun `the 401 challenge points at the resource metadata discovery starts from`() {
    // The one line that makes the whole exchange discoverable. Without it a client guesses, and
    // the guess it makes is an unprompted registration POST.
    val challenge = ServeMcpOAuth.challenge("https://preview.example")
    assertTrue(
      challenge.contains(
        "resource_metadata=\"https://preview.example" +
          "${ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_MCP_PATH}\""
      )
    )
    assertTrue(challenge.startsWith("Bearer "))
  }
}
