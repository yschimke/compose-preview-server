package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The whole OAuth exchange over real HTTP, walked exactly the way an MCP client walks it.
 *
 * This is the test that would have caught the original bug, and it is worth being explicit about
 * what that bug was: every piece of the agent-grant flow worked, and a client still could not
 * authenticate, because nothing in the protocol told it where to start. So the first assertion here
 * is not about tokens at all — it is that a `401` from `/mcp` names its resource metadata.
 */
class ServeMcpOAuthRoutingTest {

  private val operatorToken = "operator-secret-token"

  private val registry = ServeSessionRegistry(open = { null })

  private val grants =
    ServeAgentGrantStore(maxScope = AgentGrantScope.PLAYGROUND, maxGrantTtlSeconds = 3600)

  private val server: ServeHttpServer by lazy {
    val dir = Files.createTempDirectory("oauth").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/example.png")
      .writeBytes(
        Base64.getDecoder()
          .decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUB" +
              "AScY42YAAAAASUVORK5CYII="
          )
      )
    registry.register("demo", host = ServeBundleHost(dir, label = "demo"), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = false,
        agentGrants = grants,
        catalogMcpEnabled = true,
        machineAuthorization =
          ServeMachineAuthorization(operatorToken, githubAuth = null, agentGrants = grants),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun get(path: String, token: String? = null): Triple<Int, String, String?> {
    val request =
      Request.Builder()
        .url(url(path))
        .apply { token?.let { header(ServeHttpServer.TOKEN_HEADER, it) } }
        .build()
    client.newCall(request).execute().use {
      return Triple(it.code, it.body.string(), it.header("Location"))
    }
  }

  private fun post(
    path: String,
    body: String,
    contentType: String = "application/json",
    token: String? = null,
    bearer: String? = null,
  ): Triple<Int, String, String?> {
    val request =
      Request.Builder()
        .url(url(path))
        .post(body.toRequestBody(contentType.toMediaType()))
        .apply {
          token?.let { header(ServeHttpServer.TOKEN_HEADER, it) }
          bearer?.let { header("Authorization", "Bearer $it") }
          header("Accept", "application/json")
        }
        .build()
    client.newCall(request).execute().use {
      return Triple(it.code, it.body.string(), it.header("WWW-Authenticate"))
    }
  }

  private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

  private fun str(text: String, key: String) = json(text)[key]!!.jsonPrimitive.content

  private fun field(html: String, name: String): String =
    Regex("name=\"$name\" value=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
      ?: error("no $name field in the page")

  private fun query(location: String, key: String): String? =
    URI(location).query?.split('&')?.firstNotNullOfOrNull {
      val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
      if (k == key) java.net.URLDecoder.decode(v, "UTF-8") else null
    }

  private val verifier = "verifier-".padEnd(64, 'x')

  private val challenge =
    Base64.getUrlEncoder()
      .withoutPadding()
      .encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
      )

  private val redirectUri = "http://127.0.0.1:8976/callback"

  private fun registerClient(): String {
    val (code, body, _) =
      post(
        ServeMcpOAuth.REGISTER_PATH,
        """{"client_name":"Test MCP client","redirect_uris":["$redirectUri"]}""",
      )
    assertEquals(201, code)
    return str(body, "client_id")
  }

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  // ------------------------------------------------------------- discovery

  /**
   * A message that actually needs a grant. `tools/list` is deliberately open — a client with no
   * credential has to be able to discover the tool that asks a human for one — so testing the
   * challenge against it would assert nothing.
   */
  private val gatedCall =
    """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
      """"params":{"name":"list_projects","arguments":{}}}"""

  @Test
  fun `an unauthenticated MCP call names the resource metadata discovery starts from`() {
    val (code, _, challengeHeader) = post("/mcp", gatedCall)
    assertEquals(401, code)
    val header = assertNotNull(challengeHeader)
    assertTrue(header.startsWith("Bearer "), header)
    assertTrue(header.contains("resource_metadata="), header)
    assertTrue(header.contains(ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_MCP_PATH), header)
  }

  @Test
  fun `protected resource metadata points at this server as its own authorization server`() {
    for (path in
      listOf(
        ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_PATH,
        ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_MCP_PATH,
      )) {
      val (code, body, _) = get(path)
      assertEquals(200, code, path)
      assertTrue(str(body, "resource").endsWith("/mcp"), path)
      assertTrue(
        json(body)["authorization_servers"]!!.jsonArray.isNotEmpty(),
        path,
      )
    }
  }

  @Test
  fun `authorization server metadata advertises registration and S256 only`() {
    for (path in
      listOf(
        ServeMcpOAuth.AUTHORIZATION_SERVER_METADATA_PATH,
        ServeMcpOAuth.AUTHORIZATION_SERVER_METADATA_MCP_PATH,
        ServeMcpOAuth.OPENID_CONFIGURATION_PATH,
      )) {
      val (code, body, _) = get(path)
      assertEquals(200, code, path)
      val payload = json(body)
      assertTrue(str(body, "registration_endpoint").endsWith(ServeMcpOAuth.REGISTER_PATH), path)
      assertTrue(str(body, "authorization_endpoint").endsWith(ServeMcpOAuth.AUTHORIZE_PATH), path)
      assertTrue(str(body, "token_endpoint").endsWith(ServeMcpOAuth.TOKEN_PATH), path)
      assertEquals(
        listOf("S256"),
        payload["code_challenge_methods_supported"]!!.jsonArray.map { it.jsonPrimitive.content },
        path,
      )
    }
  }

  @Test
  fun `the metadata documents are readable without a credential on a token-gated box`() {
    // They are what a caller reads BECAUSE it has no credential. Gating them would make the whole
    // exchange unreachable in exactly the configuration it exists to serve.
    assertEquals(200, get(ServeMcpOAuth.PROTECTED_RESOURCE_METADATA_MCP_PATH).first)
    assertEquals(200, get(ServeMcpOAuth.AUTHORIZATION_SERVER_METADATA_PATH).first)
  }

  // ---------------------------------------------------------- registration

  @Test
  fun `a client can register itself and gets no secret`() {
    val (code, body, _) =
      post(
        ServeMcpOAuth.REGISTER_PATH,
        """{"client_name":"Test MCP client","redirect_uris":["$redirectUri"]}""",
      )
    assertEquals(201, code)
    assertTrue(str(body, "client_id").isNotEmpty())
    assertEquals("none", str(body, "token_endpoint_auth_method"))
    assertNull(json(body)["client_secret"])
  }

  @Test
  fun `registration ignores metadata this server has no use for`() {
    // The real regression. RFC 7591 §2 requires a server to ignore metadata it does not
    // understand, and the first client to reach this endpoint sent `application_type: "native"` —
    // a field registered in RFC 7591 itself. Refusing it rejected a client for being more
    // spec-compliant than the server, at the one step that must work before anything else can.
    val body = buildString {
      append("{")
      append(""""client_name":"Test MCP client",""")
      append(""""redirect_uris":["$redirectUri"],""")
      append(""""token_endpoint_auth_method":"none",""")
      append(""""application_type":"native",""")
      append(""""not_a_real_field_at_all":{"nested":[1,2,3]}""")
      append("}")
    }
    val (code, response, _) = post(ServeMcpOAuth.REGISTER_PATH, body)
    assertEquals(201, code, response)
    assertTrue(str(response, "client_id").isNotEmpty())
  }

  @Test
  fun `registration without a redirect URI is refused`() {
    val (code, body, _) = post(ServeMcpOAuth.REGISTER_PATH, """{"client_name":"no callback"}""")
    assertEquals(400, code)
    assertEquals("invalid_redirect_uri", str(body, "error"))
  }

  // ------------------------------------------------------------- the flow

  @Test
  fun `the whole exchange yields a bearer the MCP endpoint accepts`() {
    val clientId = registerClient()

    // 1. The client sends the human to /oauth/authorize, which redirects to the approval page that
    //    already existed — there is only ever one page where access is granted.
    val (authorizeCode, _, approvalLocation) =
      get(
        "${ServeMcpOAuth.AUTHORIZE_PATH}?response_type=code&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_challenge=$challenge&code_challenge_method=S256" +
          "&state=xyz&scope=live",
        token = operatorToken,
      )
    assertEquals(302, authorizeCode)
    val approvalPath = assertNotNull(approvalLocation)
    assertTrue(approvalPath.startsWith(ServeAgentGrants.BASE_PATH), approvalPath)

    // 2. The human approves on that page, with its own CSRF seal.
    val (_, page, _) = get(approvalPath, operatorToken)
    val (decisionCode, _, clientLocation) =
      postForm(
        approvalPath,
        "action=approve&csrf=${field(page, "csrf")}&scope=live&ttl=1800",
      )
    assertEquals(302, decisionCode)

    // 3. …and the browser comes back to the client with the code and its state, rather than
    //    stopping on a page a human would have to relay.
    val back = assertNotNull(clientLocation)
    assertTrue(back.startsWith(redirectUri), back)
    assertEquals("xyz", query(back, "state"))
    val authorizationCode = assertNotNull(query(back, "code"))

    // 4. The client redeems it with the verifier.
    val (tokenCode, tokenBody, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=authorization_code&code=$authorizationCode&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_verifier=$verifier",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(200, tokenCode, tokenBody)
    val accessToken = str(tokenBody, "access_token")
    assertEquals("Bearer", str(tokenBody, "token_type"))
    assertTrue(str(tokenBody, "scope").contains("live"), tokenBody)

    // 5. And the token opens the door that answered 401 at the top of this test.
    val (mcpCode, _, _) = post("/mcp", gatedCall, bearer = accessToken)
    assertEquals(200, mcpCode)
  }

  private fun postForm(path: String, body: String): Triple<Int, String, String?> {
    val request =
      Request.Builder()
        .url(
          url(
            if (path.contains('?')) "$path&token=$operatorToken" else "$path?token=$operatorToken"
          )
        )
        .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        .header(ServeHttpServer.TOKEN_HEADER, operatorToken)
        .build()
    client.newCall(request).execute().use {
      return Triple(it.code, it.body.string(), it.header("Location"))
    }
  }

  // ------------------------------------------------------------- refusals

  @Test
  fun `an unregistered redirect URI is rendered, never redirected to`() {
    val clientId = registerClient()
    val (code, body, location) =
      get(
        "${ServeMcpOAuth.AUTHORIZE_PATH}?response_type=code&client_id=$clientId" +
          "&redirect_uri=https://evil.example/steal&code_challenge=$challenge" +
          "&code_challenge_method=S256",
        token = operatorToken,
      )
    assertEquals(400, code)
    assertNull(location)
    assertFalse(body.contains("evil.example"))
  }

  @Test
  fun `a code is worthless without its verifier`() {
    val clientId = registerClient()
    val authorizationCode = approvedCode(clientId)
    val (code, body, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=authorization_code&code=$authorizationCode&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_verifier=${"wrong-".padEnd(64, 'y')}",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(400, code)
    assertEquals("invalid_grant", str(body, "error"))
  }

  @Test
  fun `a code cannot be redeemed twice`() {
    val clientId = registerClient()
    val authorizationCode = approvedCode(clientId)
    val body =
      "grant_type=authorization_code&code=$authorizationCode&client_id=$clientId" +
        "&redirect_uri=$redirectUri&code_verifier=$verifier"
    assertEquals(
      200,
      post(ServeMcpOAuth.TOKEN_PATH, body, "application/x-www-form-urlencoded").first,
    )
    val (second, secondBody, _) =
      post(ServeMcpOAuth.TOKEN_PATH, body, "application/x-www-form-urlencoded")
    assertEquals(400, second)
    assertEquals("invalid_grant", str(secondBody, "error"))
  }

  @Test
  fun `a declined authorization comes back as access_denied rather than timing out`() {
    val clientId = registerClient()
    val (_, _, approvalLocation) =
      get(
        "${ServeMcpOAuth.AUTHORIZE_PATH}?response_type=code&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_challenge=$challenge&code_challenge_method=S256" +
          "&state=xyz",
        token = operatorToken,
      )
    val approvalPath = assertNotNull(approvalLocation)
    val (_, page, _) = get(approvalPath, operatorToken)
    val (code, _, location) =
      postForm(approvalPath, "action=deny&denyCsrf=${field(page, "denyCsrf")}")
    assertEquals(302, code)
    val back = assertNotNull(location)
    assertEquals("access_denied", query(back, "error"))
    assertEquals("xyz", query(back, "state"))
  }

  @Test
  fun `a refresh token renews within the approved session`() {
    val clientId = registerClient()
    val authorizationCode = approvedCode(clientId)
    val (code, body, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=authorization_code&code=$authorizationCode&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_verifier=$verifier",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(200, code, body)
    val refresh = str(body, "refresh_token")
    assertTrue(refresh.isNotEmpty())

    val (refreshed, refreshedBody, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=refresh_token&refresh_token=$refresh&client_id=$clientId",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(200, refreshed, refreshedBody)
    // The same session, not a new one: the bearer is the grant's, and the scope is unchanged.
    assertEquals(str(body, "access_token"), str(refreshedBody, "access_token"))
    assertEquals(str(body, "scope"), str(refreshedBody, "scope"))
    // Rotated (RFC 9700 4.14.2): the presented token is spent.
    assertTrue(str(refreshedBody, "refresh_token") != refresh)

    val (replayed, _, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=refresh_token&refresh_token=$refresh&client_id=$clientId",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(400, replayed)
  }

  @Test
  fun `a refresh token is refused once its grant is revoked`() {
    // The bound that makes refresh safe to have at all: it renews within a decision, never past it.
    val clientId = registerClient()
    val authorizationCode = approvedCode(clientId)
    val (_, body, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=authorization_code&code=$authorizationCode&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_verifier=$verifier",
        contentType = "application/x-www-form-urlencoded",
      )
    val refresh = str(body, "refresh_token")
    assertTrue(grants.revokeToken(str(body, "access_token"), by = "test"))

    val (code, refusal, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=refresh_token&refresh_token=$refresh&client_id=$clientId",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(400, code)
    assertEquals("invalid_grant", str(refusal, "error"))
  }

  @Test
  fun `a refresh token issued to one client is refused to another`() {
    val clientId = registerClient()
    val other = registerClient()
    val authorizationCode = approvedCode(clientId)
    val (_, body, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=authorization_code&code=$authorizationCode&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_verifier=$verifier",
        contentType = "application/x-www-form-urlencoded",
      )
    val (code, _, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=refresh_token&refresh_token=${str(body, "refresh_token")}&client_id=$other",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(400, code)
  }

  @Test
  fun `an unknown grant type is still refused`() {
    val (code, body, _) =
      post(
        ServeMcpOAuth.TOKEN_PATH,
        "grant_type=client_credentials&whatever=1",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(400, code)
    assertEquals("unsupported_grant_type", str(body, "error"))
  }

  /** Drive an authorization to approval and return the code the client would receive. */
  private fun approvedCode(clientId: String): String {
    val (_, _, approvalLocation) =
      get(
        "${ServeMcpOAuth.AUTHORIZE_PATH}?response_type=code&client_id=$clientId" +
          "&redirect_uri=$redirectUri&code_challenge=$challenge&code_challenge_method=S256" +
          "&scope=live",
        token = operatorToken,
      )
    val approvalPath = assertNotNull(approvalLocation)
    val (_, page, _) = get(approvalPath, operatorToken)
    val (_, _, location) =
      postForm(approvalPath, "action=approve&csrf=${field(page, "csrf")}&scope=live&ttl=1800")
    return assertNotNull(query(assertNotNull(location), "code"))
  }
}
