package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * Grant management on a `--public` box, where every signed-in member is an approver: each sees and
 * revokes only the grants they approved, holds at most the per-approver number of live grants, and
 * is shown only the requests they opened for themselves. The `--token` holder and a configured
 * UI-builder administrator still see and manage everything.
 */
class ServeAgentGrantPublicApproverTest {

  private val operatorToken = "operator-token"

  /** GitHub, answering with whichever login the callback's `code` named. */
  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> {
              val form = Buffer().also { request.body?.writeTo(it) }.readUtf8()
              val code = form.split('&').first { it.startsWith("code=") }.substringAfter('=')
              """{"access_token":"tok-$code"}"""
            }
            "/user" -> {
              val login = request.header("Authorization").orEmpty().substringAfter("tok-")
              """{"login":"$login"}"""
            }
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

  private val grants =
    ServeAgentGrantStore(
      maxScope = AgentGrantScope.LIVE,
      maxActiveGrants = 10,
      maxActiveGrantsPerApprover = 1,
    )

  private val auth =
    ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
        allowedUsers = setOf("alice", "bob", "admin"),
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
    )

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "unused",
        isPublic = true,
        githubAuth = auth,
        agentGrants = grants,
        uiBuilderAdminActors = setOf("github:admin"),
        machineAuthorization =
          ServeMachineAuthorization(operatorToken, auth, grants, isPublic = true),
      )
      .also { it.start() }

  private val client = OkHttpClient.Builder().followRedirects(false).build()
  private val base = "http://127.0.0.1:${server.port}"

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  private fun signIn(login: String): String {
    val (location, stateCookie) =
      client.newCall(Request.Builder().url("$base/auth/github/start").build()).execute().use {
        it.header("Location").orEmpty() to it.header("Set-Cookie").orEmpty()
      }
    val state = location.substringAfter("state=").substringBefore("&")
    return client
      .newCall(
        Request.Builder()
          .url("$base/auth/github/callback?code=$login&state=$state")
          .header("Cookie", stateCookie.substringBefore(";"))
          .build()
      )
      .execute()
      .use { response ->
        response.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") }.substringBefore(";")
      }
  }

  private fun get(path: String, cookie: String? = null): Pair<Int, String> {
    val builder = Request.Builder().url("$base$path")
    if (cookie != null) builder.header("Cookie", cookie)
    return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  private fun postForm(path: String, cookie: String, fields: Map<String, String>): Int {
    val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
    return client
      .newCall(Request.Builder().url("$base$path").header("Cookie", cookie).post(form).build())
      .execute()
      .use { it.code }
  }

  private fun field(html: String, name: String): String =
    Regex("name=\"$name\" value=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
      ?: error("no $name field in the page")

  /** An agent's request, as `POST /agent-access/request` opens one. Returns its id. */
  private fun agentRequest(label: String): String {
    val body =
      client
        .newCall(
          Request.Builder()
            .url("$base${ServeAgentGrants.REQUEST_PATH}")
            .post("""{"label":"$label","scope":"preview"}""".toRequestBody())
            .build()
        )
        .execute()
        .use { it.body.string() }
    return Json.parseToJsonElement(body).jsonObject["requestId"]!!.jsonPrimitive.content
  }

  /** Approve [requestId] as the holder of [cookie]; returns the decision's status code. */
  private fun approve(requestId: String, cookie: String): Int {
    val path = ServeAgentGrants.approvalPath(requestId)
    val (_, page) = get(path, cookie)
    return postForm(
      path,
      cookie,
      mapOf(
        "action" to "approve",
        "csrf" to field(page, "csrf"),
        "scope" to "preview",
        "ttl" to "600",
      ),
    )
  }

  private fun grantApprovedBy(login: String): ServeAgentGrantStore.Grant =
    grants.activeGrants().single { it.approvedByActorId == "github:$login" }

  @Test
  fun `each approver sees only their own grants, and a reader who is none sees only a count`() {
    val alice = signIn("alice")
    val bob = signIn("bob")
    assertEquals(303, approve(agentRequest("alice's agent"), alice))
    assertEquals(303, approve(agentRequest("bob's agent"), bob))
    val alicesGrant = grantApprovedBy("alice")
    val bobsGrant = grantApprovedBy("bob")

    val (_, asAlice) = get("/status", alice)
    assertTrue(asAlice.contains(alicesGrant.fingerprint), asAlice)
    assertFalse(asAlice.contains(bobsGrant.fingerprint), "bob's grant is not alice's to see")
    assertFalse(asAlice.contains("@bob"), "no row names the other approver")
    assertTrue(asAlice.contains("1 more live grant"), asAlice)

    val (_, anonymous) = get("/status")
    assertFalse(anonymous.contains(alicesGrant.fingerprint))
    assertFalse(anonymous.contains(bobsGrant.fingerprint))
    assertFalse(anonymous.contains("@alice"))
    assertFalse(anonymous.contains("Revoke"))
    assertTrue(anonymous.contains("2 more live grants"), anonymous)
  }

  @Test
  fun `the token holder and a UI-builder administrator still see every grant`() {
    val alice = signIn("alice")
    val bob = signIn("bob")
    assertEquals(303, approve(agentRequest("alice's agent"), alice))
    assertEquals(303, approve(agentRequest("bob's agent"), bob))
    val fingerprints = grants.activeGrants().map { it.fingerprint }

    val (_, asAdmin) = get("/status", signIn("admin"))
    fingerprints.forEach { assertTrue(asAdmin.contains(it), asAdmin) }

    val (_, asOperator) = get("/status?token=$operatorToken", bob)
    fingerprints.forEach { assertTrue(asOperator.contains(it), asOperator) }
  }

  @Test
  fun `a signed-in GitHub identity outranks an ambient preview grant cookie`() {
    val request =
      requireNotNull(
        grants.openRequest(
          label = "ambient browser grant",
          client = "test",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 600,
        )
      )
    val grant =
      requireNotNull(
        grants.approve(
          request.id,
          approvedBy = "operator",
          scope = AgentGrantScope.PREVIEW,
          ttlSeconds = 600,
        )
      )
    val credential = requireNotNull(grants.browserCredentialFor(grant))
    val alice = signIn("alice")
    val cookies = "$alice; ${ServeAgentGrantCookie.NAME}=${credential.value}"

    client
      .newCall(
        Request.Builder()
          .url("$base/status?token=${grant.token}")
          .header("Cookie", alice)
          .header("Accept", "text/html")
          .build()
      )
      .execute()
      .use {
        assertEquals(302, it.code)
        assertEquals("/status", it.header("Location"))
        assertTrue(
          it.headers("Set-Cookie").any { value ->
            value.startsWith("${ServeAgentGrantCookie.NAME}=") && value.contains("Max-Age=0")
          }
        )
      }

    client
      .newCall(
        Request.Builder()
          .url("$base/api/presence")
          .header("Cookie", cookies)
          .header("Origin", base)
          .post("{}".toRequestBody())
          .build()
      )
      .execute()
      .use {
        assertEquals(204, it.code, "the ambient preview grant must not block Alice's live access")
        assertTrue(
          it.headers("Set-Cookie").any { value ->
            value.startsWith("${ServeAgentGrantCookie.NAME}=") && value.contains("Max-Age=0")
          }
        )
      }
  }

  @Test
  fun `an approver revokes their own grant, and another approver's revoke is refused`() {
    val alice = signIn("alice")
    val bob = signIn("bob")
    assertEquals(303, approve(agentRequest("alice's agent"), alice))
    val alicesGrant = grantApprovedBy("alice")
    val revokePath = "${ServeAgentGrants.BASE_PATH}/${alicesGrant.id}/revoke"

    // Bob is shown no revoke form for it, and nothing he could post is accepted.
    val (_, asBob) = get("/status", bob)
    assertFalse(asBob.contains(revokePath), asBob)
    assertEquals(404, postForm(revokePath, bob, mapOf("csrf" to "forged")))
    assertNotNull(grants.grant(alicesGrant.id), "bob could not end alice's grant")

    val (_, asAlice) = get("/status", alice)
    assertTrue(asAlice.contains(revokePath), asAlice)
    val seal =
      Regex("$revokePath\"><input type=\"hidden\" name=\"csrf\" value=\"([^\"]*)\"")
        .find(asAlice)
        ?.groupValues
        ?.get(1)
    assertEquals(302, postForm(revokePath, alice, mapOf("csrf" to assertNotNull(seal))))
    assertEquals(null, grants.grant(alicesGrant.id))
  }

  @Test
  fun `an approver over their cap is refused, and nobody else's grant is ended to make room`() {
    val alice = signIn("alice")
    val bob = signIn("bob")
    assertEquals(303, approve(agentRequest("bob's agent"), bob))
    assertEquals(303, approve(agentRequest("alice's first"), alice))
    val second = agentRequest("alice's second")
    assertEquals(409, approve(second, alice))
    assertEquals(ServeAgentGrantStore.Request.State.PENDING, grants.request(second)?.state)
    assertEquals(2, grants.activeGrants().size, "both live grants survived")
    // An administrator is held only to the box-wide cap.
    assertEquals(303, approve(second, signIn("admin")))
  }

  @Test
  fun `waiting agent requests are listed for the token holder, not for every signed-in member`() {
    agentRequest("an agent's ask")
    val (_, asAlice) = get("/status", signIn("alice"))
    assertFalse(asAlice.contains("an agent&#39;s ask") || asAlice.contains("an agent's ask"))
    val (_, asAdmin) = get("/status", signIn("admin"))
    assertTrue(asAdmin.contains("an agent"), asAdmin)
  }
}
