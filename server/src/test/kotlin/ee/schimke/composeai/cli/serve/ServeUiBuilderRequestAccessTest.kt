package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignActorAccessV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
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

  /** Who the next sign-in is: the requester, unless a test signs the approver in. */
  @Volatile private var login = "stranger"

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> """{"access_token":"token"}"""
            "/user" -> """{"login":"$login"}"""
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
        if (call.request is UiBuilderServiceRequest.ListDesigns) {
          return UiBuilderServiceResponse.Designs(
            listOf(listItem("design", "Checkout screen")),
            null,
          )
        }
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

  private fun signIn(who: String = "stranger"): String {
    login = who
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

  private fun page(cookie: String?, query: String = ""): Pair<Int, String> {
    val builder =
      Request.Builder().url("$base${ServeHttpServer.UI_BUILDER_REQUEST_ACCESS_PATH}$query")
    if (cookie != null) builder.header("Cookie", cookie)
    return noRedirect.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  private fun submit(
    cookie: String,
    csrf: String?,
    ttl: String = "3600",
    design: String? = null,
  ): Pair<Int, String> {
    val form = FormBody.Builder().add("ttl", ttl)
    if (csrf != null) form.add("csrf", csrf)
    if (design != null) form.add("design", design)
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

  /** The approver's session: an allowed member of this box, unlike the requester. */
  private fun approver(): String = signIn(who = "yschimke").also { login = "stranger" }

  /** The approval page, opened by a signed-in approver. */
  private fun approvalPage(cookie: String, requestId: String): String =
    noRedirect
      .newCall(
        Request.Builder()
          .url("$base${ServeAgentGrants.approvalPath(requestId)}")
          .header("Cookie", cookie)
          .build()
      )
      .execute()
      .use {
        assertEquals(200, it.code)
        it.body.string()
      }

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

  @Test
  fun `a request made from a design names it, and the approval page names it too`() {
    val cookie = signIn()
    val (status, form) = page(cookie, "?design=design")
    assertEquals(200, status)
    assertTrue(form.contains("name=\"design\" value=\"design\""), "the form carries the design")

    assertEquals(303, submit(cookie, csrfOf(form), design = "design").first)
    val pending = grants.pendingRequests().single()
    val approver = approver()
    assertEquals(setOf("design"), pending.designIds)
    assertTrue(landing(cookie, pending.id).contains("<code>design</code> only"))

    val approval = approvalPage(approver, pending.id)
    assertTrue(approval.contains("Edit access to"), "the page says what the access is to")
    assertTrue(
      approval.contains("Checkout screen (<code>design</code>)"),
      "by the design's title and id",
    )
    assertTrue(approval.contains("name=\"designScope\" value=\"requested\" checked"))
    assertTrue(approval.contains("name=\"designScope\" value=\"all\""))
  }

  @Test
  fun `a request not made from a design asks for no design, and its page offers no choice`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie).second))
    val pending = grants.pendingRequests().single()
    val approver = approver()
    assertTrue(pending.designIds.isEmpty())
    val approval = approvalPage(approver, pending.id)
    assertFalse(approval.contains("designScope"))
    assertFalse(approval.contains("Edit access to"))
  }

  @Test
  fun `a malformed design is refused rather than dropped`() {
    val cookie = signIn()
    assertEquals(400, page(cookie, "?design=%2E%2E%2Fx").first)
    assertEquals(400, submit(cookie, csrfOf(page(cookie).second), design = "../x").first)
    assertTrue(grants.pendingRequests().isEmpty())
  }

  @Test
  fun `a grant for one design lends its approver's authority on that design only`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie, "?design=design").second), design = "design")
    val pending = grants.pendingRequests().single()
    grants.approve(
      pending.id,
      approvedBy = "@yschimke",
      scope = AgentGrantScope.PREVIEW,
      ttlSeconds = 3600,
      capabilities = pending.requestedCapabilities,
      approvedByActorId = "github:yschimke",
    )

    assertEquals(
      200,
      post(cookie, "github:stranger", ExportDesignRequestV1("design", format = ExportFormatV1.SVG)),
    )
    assertEquals("github:yschimke", calls.last().actor.onBehalfOfActorId)

    assertEquals(
      200,
      post(cookie, "github:stranger", ExportDesignRequestV1("other", format = ExportFormatV1.SVG)),
    )
    val other = calls.last().actor
    assertEquals("github:stranger", other.actorId)
    assertNull(other.onBehalfOfActorId, "another design is reached as the requester alone")
  }

  @Test
  fun `the approver may lend every design instead of the one asked for`() {
    val cookie = signIn()
    submit(cookie, csrfOf(page(cookie, "?design=design").second), design = "design")
    val pending = grants.pendingRequests().single()
    val approver = approver()
    val approval = approvalPage(approver, pending.id)
    val decided =
      noRedirect
        .newCall(
          Request.Builder()
            .url("$base${ServeAgentGrants.approvalPath(pending.id)}")
            .header("Cookie", approver)
            .header("Origin", base)
            .post(
              FormBody.Builder()
                .add("csrf", csrfOf(approval))
                .add("action", "approve")
                .add("scope", "preview")
                .add("capability", AgentGrantCapability.UI_BUILDER_WRITE.wire)
                .add("ttl", "3600")
                .add("designScope", "all")
                .build()
            )
            .build()
        )
        .execute()
        .use { it.code }
    assertEquals(303, decided)
    val grant = assertNotNull(grants.activeGrantForRequester("github:stranger"))
    assertTrue(grant.designIds.isEmpty())
  }

  private fun listItem(id: String, title: String) =
    DesignListItemV1(
      designId = id,
      title = title,
      revision = 1,
      accessRevision = 1,
      catalogPin = CatalogReferenceV1("remote-m3", "v", "v", "v"),
      createdAtEpochMillis = 1,
      updatedAtEpochMillis = 2,
      ownerActorId = "github:yschimke",
      requesterAccess =
        DesignActorAccessV1(
          "github:yschimke",
          DesignAccessRoleV1.OWNER,
          DesignAccessActionV1.entries,
        ),
    )
}
