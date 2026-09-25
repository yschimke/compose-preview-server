package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A signed-in reader asks for UI-builder edit access **for themselves**: the request names them,
 * verified from their session; the approver's decision is carried by that same session; and what
 * they then do is theirs by name while reaching only as far as the approver does.
 */
class ServeUiBuilderRequestAccessTest {
  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> """{"access_token":"token"}"""
            "/user" -> """{"login":"stranger"}"""
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

  private val calls = CopyOnWriteArrayList<UiBuilderServiceCall>()
  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        calls += call
        return UiBuilderServiceResponse.Catalogs(emptyList())
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val grants =
    ServeAgentGrantStore(
      maxScope = AgentGrantScope.PLAYGROUND,
      maxCapabilities =
        setOf(
          AgentGrantCapability.UI_BUILDER_READ,
          AgentGrantCapability.UI_BUILDER_WRITE,
          AgentGrantCapability.UI_BUILDER_EXPORT,
        ),
    )

  private val auth =
    ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
        allowedUsers = setOf("yschimke"),
        allowGuests = true,
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
      anonymousClient = fakeGitHub,
    )

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        isPublic = true,
        githubAuth = auth,
        agentGrants = grants,
        uiBuilderService = service,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization.fromMachineAuthorization(
            ServeMachineAuthorization("operator-token", auth, grants, isPublic = true)
          ),
      )
      .also { it.start() }

  private val noRedirect = OkHttpClient.Builder().followRedirects(false).build()
  private val base = "http://127.0.0.1:${server.port}"

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  private fun signIn(): String {
    val (location, stateCookie) =
      noRedirect.newCall(Request.Builder().url("$base/auth/github/start").build()).execute().use {
        it.header("Location").orEmpty() to it.header("Set-Cookie").orEmpty()
      }
    val state = location.substringAfter("state=").substringBefore("&")
    return noRedirect
      .newCall(
        Request.Builder()
          .url("$base/auth/github/callback?code=ok&state=$state")
          .header("Cookie", stateCookie.substringBefore(";"))
          .build()
      )
      .execute()
      .use { response ->
        response.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") }.substringBefore(";")
      }
  }

  private fun page(cookie: String?): Pair<Int, String> {
    val builder = Request.Builder().url("$base${ServeHttpServer.UI_BUILDER_REQUEST_ACCESS_PATH}")
    if (cookie != null) builder.header("Cookie", cookie)
    return noRedirect.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  private fun submit(cookie: String, csrf: String?, ttl: String = "3600"): Pair<Int, String> {
    val form = FormBody.Builder().add("ttl", ttl)
    if (csrf != null) form.add("csrf", csrf)
    return noRedirect
      .newCall(
        Request.Builder()
          .url("$base${ServeHttpServer.UI_BUILDER_REQUEST_ACCESS_PATH}")
          .header("Cookie", cookie)
          .post(form.build())
          .build()
      )
      .execute()
      .use { it.code to it.body.string() }
  }

  private fun csrfOf(html: String): String =
    html.substringAfter("name=\"csrf\" value=\"").substringBefore("\"")

  private fun post(cookie: String, actorId: String, request: UiBuilderRequestV1): Int {
    val body =
      json.encodeToString(
        HttpRequestEnvelopeV1.serializer(),
        HttpRequestEnvelopeV1(requestId = "r", actorId = actorId, request = request),
      )
    return noRedirect
      .newCall(
        Request.Builder()
          .url("$base$UI_BUILDER_REQUEST_PATH")
          .header("Cookie", cookie)
          .post(body.toRequestBody())
          .build()
      )
      .execute()
      .use { it.code }
  }

  @Test
  fun `an anonymous visitor is sent to sign in first`() {
    val (status, _) = page(cookie = null)
    assertEquals(302, status)
  }

  @Test
  fun `a request names the signed-in reader, verified, and hands them a link to send`() {
    val cookie = signIn()
    val (status, form) = page(cookie)
    assertEquals(200, status)

    val (submitted, _) = submit(cookie, csrfOf(form))

    // POST, redirect, GET: the page with the link is drawn by a GET, so a refresh re-reads it.
    assertEquals(303, submitted)
    val pending = grants.pendingRequests().single()
    val landed = landing(cookie, pending.id)
    assertEquals("github:stranger", pending.requesterActorId)
    assertTrue(pending.client.startsWith("@stranger, signed in with GitHub"), pending.client)
    assertEquals(
      setOf(
        AgentGrantCapability.UI_BUILDER_READ,
        AgentGrantCapability.UI_BUILDER_WRITE,
        AgentGrantCapability.UI_BUILDER_EXPORT,
      ),
      pending.requestedCapabilities,
    )
    assertTrue(landed.contains(ServeAgentGrants.approvalPath(pending.id)), "the link to send")
    assertTrue(landed.contains(pending.userCode), "the code the approver confirms")
  }

  @Test
  fun `submitting again reuses the waiting request rather than opening another`() {
    val cookie = signIn()
    val csrf = csrfOf(page(cookie).second)
    assertEquals(303, submit(cookie, csrf).first)
    assertEquals(303, submit(cookie, csrf).first)
    assertEquals(1, grants.pendingRequests().size)
  }

  @Test
  fun `the landing page does not show somebody else's request`() {
    val other =
      checkNotNull(
        grants.openRequest(
          label = "edit",
          client = "x",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 3600,
          requesterActorId = "github:someone-else",
        )
      )
    val landed = landing(signIn(), other.id)
    assertFalse(landed.contains(other.userCode))
  }

  private fun landing(cookie: String, requestId: String): String =
    noRedirect
      .newCall(
        Request.Builder()
          .url("$base${ServeHttpServer.UI_BUILDER_REQUEST_ACCESS_PATH}?request=$requestId")
          .header("Cookie", cookie)
          .build()
      )
      .execute()
      .use {
        assertEquals(200, it.code)
        it.body.string()
      }

  @Test
  fun `a form without this reader's seal opens nothing`() {
    val cookie = signIn()

    assertEquals(403, submit(cookie, csrf = null).first)
    assertEquals(403, submit(cookie, csrf = "forged").first)
    assertTrue(grants.pendingRequests().isEmpty())
  }

  @Test
  fun `once approved, the reader's own session edits as them on behalf of the approver`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie).second))
    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    // Before approval a guest reads and nothing more.
    assertEquals(401, post(cookie, "github:stranger", export))

    val pending = grants.pendingRequests().single()
    assertNotNull(
      grants.approve(
        pending.id,
        approvedBy = "@yschimke",
        scope = AgentGrantScope.PREVIEW,
        ttlSeconds = 3600,
        capabilities = pending.requestedCapabilities,
        approvedByActorId = "github:yschimke",
      )
    )

    assertEquals(200, post(cookie, "github:stranger", export))
    val actor = calls.last().actor
    assertEquals("github:stranger", actor.actorId)
    assertEquals("github:yschimke", actor.onBehalfOfActorId)
  }

  @Test
  fun `an approval narrowed to read lends nothing more`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie).second))
    val pending = grants.pendingRequests().single()
    grants.approve(
      pending.id,
      approvedBy = "@yschimke",
      scope = AgentGrantScope.PREVIEW,
      ttlSeconds = 3600,
      capabilities = setOf(AgentGrantCapability.UI_BUILDER_READ),
      approvedByActorId = "github:yschimke",
    )

    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(401, post(cookie, "github:stranger", export))
  }

  @Test
  fun `a later, shorter approval still counts beside a longer one`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie).second))
    val first = grants.pendingRequests().single()
    grants.approve(
      first.id,
      approvedBy = "@yschimke",
      scope = AgentGrantScope.PREVIEW,
      ttlSeconds = 8 * 3600,
      capabilities = setOf(AgentGrantCapability.UI_BUILDER_WRITE),
      approvedByActorId = "github:yschimke",
    )
    submit(cookie, csrfOf(page(cookie).second))
    val second = grants.pendingRequests().single()
    grants.approve(
      second.id,
      approvedBy = "@yschimke",
      scope = AgentGrantScope.PREVIEW,
      ttlSeconds = 3600,
      capabilities = setOf(AgentGrantCapability.UI_BUILDER_EXPORT),
      approvedByActorId = "github:yschimke",
    )

    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(200, post(cookie, "github:stranger", export))
  }

  @Test
  fun `a revoked approval stops applying to the session`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie).second))
    val pending = grants.pendingRequests().single()
    val grant =
      assertNotNull(
        grants.approve(
          pending.id,
          approvedBy = "@yschimke",
          scope = AgentGrantScope.PREVIEW,
          ttlSeconds = 3600,
          capabilities = pending.requestedCapabilities,
          approvedByActorId = "github:yschimke",
        )
      )
    grants.revoke(grant.id, by = "@yschimke")

    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(401, post(cookie, "github:stranger", export))
    assertNull(grants.activeGrantForRequester("github:stranger"))
  }

  @Test
  fun `a bearer minted for a requester acts under the requester's name`() {
    val opened =
      checkNotNull(
        grants.openRequest(
          label = "edit",
          client = "@stranger",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 3600,
          requestedCapabilities = setOf(AgentGrantCapability.UI_BUILDER_EXPORT),
          requesterActorId = "github:stranger",
        )
      )
    val grant =
      checkNotNull(
        grants.approve(
          opened.id,
          approvedBy = "@yschimke",
          scope = AgentGrantScope.PREVIEW,
          ttlSeconds = 600,
          capabilities = setOf(AgentGrantCapability.UI_BUILDER_EXPORT),
          approvedByActorId = "github:yschimke",
        )
      )

    val code =
      noRedirect
        .newCall(
          Request.Builder()
            .url("$base$UI_BUILDER_REQUEST_PATH")
            .header("Authorization", "Bearer ${grant.token}")
            .post(
              json
                .encodeToString(
                  HttpRequestEnvelopeV1.serializer(),
                  HttpRequestEnvelopeV1(
                    requestId = "r",
                    actorId = "github:stranger",
                    request = ExportDesignRequestV1("design", format = ExportFormatV1.SVG),
                  ),
                )
                .toRequestBody()
            )
            .build()
        )
        .execute()
        .use { it.code }

    assertEquals(200, code)
    assertEquals("github:stranger", calls.last().actor.actorId)
    assertEquals("github:yschimke", calls.last().actor.onBehalfOfActorId)
  }
}
