package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
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
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * `--github-auth-guests`: a GitHub account outside the allowlist signs in as a guest, may read the
 * UI builder as itself — which designs it sees is the design service's per-design answer — and is
 * signed out for every other purpose.
 */
class ServeGithubGuestTest {
  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  /** Who the fake GitHub says the visitor is, and whether it grants repository rights. */
  @Volatile private var login = "stranger"
  @Volatile private var pushAccess = true

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> """{"access_token":"token"}"""
            "/user" -> """{"login":"$login"}"""
            else -> """{"private":false,"permissions":{"push":$pushAccess}}"""
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

  private val registry = ServeSessionRegistry(open = { null })
  private val servers = mutableListOf<ServeHttpServer>()
  private val noRedirect = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun stop() {
    servers.forEach { runCatching { it.stop() } }
    runCatching { registry.close() }
  }

  private fun server(
    allowGuests: Boolean,
    allowedUsers: Set<String> = setOf("octo"),
  ): ServeHttpServer {
    val auth =
      ServeGithubAuth(
        ServeGithubAuthConfig(
          clientId = "client",
          clientSecret = "secret",
          cookieSecret = "x".repeat(32),
          repository = "yschimke/compose-ai-tools",
          allowedUsers = allowedUsers,
          allowGuests = allowGuests,
        ),
        verifier = GitHubOAuthVerifier(fakeGitHub),
        anonymousClient = fakeGitHub,
      )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        githubAuth = auth,
        uiBuilderService = service,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization.fromMachineAuthorization(
            ServeMachineAuthorization("operator-token", auth, agentGrants = null)
          ),
      )
      .also { it.start() }
      .also { servers += it }
  }

  /** The OAuth round trip; the session cookie on success, or the callback's status on refusal. */
  private fun signIn(server: ServeHttpServer): Pair<Int, String?> {
    val (location, stateCookie) =
      noRedirect
        .newCall(Request.Builder().url("http://127.0.0.1:${server.port}/auth/github/start").build())
        .execute()
        .use { it.header("Location").orEmpty() to it.header("Set-Cookie").orEmpty() }
    val state = location.substringAfter("state=").substringBefore("&")
    return noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}/auth/github/callback?code=ok&state=$state")
          .header("Cookie", stateCookie.substringBefore(";"))
          .build()
      )
      .execute()
      .use { response ->
        response.code to
          response
            .headers("Set-Cookie")
            .firstOrNull { it.startsWith("cp_gh_auth=") }
            ?.substringBefore(";")
      }
  }

  private fun post(
    server: ServeHttpServer,
    cookie: String,
    actorId: String,
    request: UiBuilderRequestV1,
  ): Int {
    val body =
      json.encodeToString(
        HttpRequestEnvelopeV1.serializer(),
        HttpRequestEnvelopeV1(requestId = "r", actorId = actorId, request = request),
      )
    return noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.port}$UI_BUILDER_REQUEST_PATH")
          .header("Cookie", cookie)
          .post(body.toRequestBody())
          .build()
      )
      .execute()
      .use { it.code }
  }

  @Test
  fun `an account outside the allowlist is still refused unless guests are on`() {
    val (status, cookie) = signIn(server(allowGuests = false))

    assertEquals(403, status)
    assertEquals(null, cookie)
  }

  @Test
  fun `a guest reads the UI builder as itself`() {
    val server = server(allowGuests = true)
    val (status, cookie) = signIn(server)
    assertEquals(302, status)

    assertEquals(200, post(server, cookie!!, "github:stranger", ListDesignsRequestV1()))
    // As itself, with nobody behind it: which designs it sees is the design service's question,
    // asked of exactly this identity.
    val actor = calls.single().actor
    assertEquals("github:stranger", actor.actorId)
    assertEquals(null, actor.onBehalfOfActorId)
  }

  @Test
  fun `a guest gets no further than read`() {
    val server = server(allowGuests = true)
    val cookie = signIn(server).second!!

    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(401, post(server, cookie, "github:stranger", export))
    assertTrue(calls.isEmpty(), "a refused request must not reach the service")
  }

  @Test
  fun `a guest is never lent the repository access GitHub would have given it`() {
    // The fake grants push to everyone; a guest must not be asked, let alone carry the answer.
    pushAccess = true
    val server = server(allowGuests = true)
    val cookie = signIn(server).second!!

    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(401, post(server, cookie, "github:stranger", export))
  }

  @Test
  fun `a named member without repository access reads and writes the UI builder`() {
    // The operator named this login, so being a member is the vouching: `--github-auth-users` and
    // `--github-auth-orgs` admit people to edit designs without also lending them the repository.
    login = "octo"
    pushAccess = false
    val server = server(allowGuests = false)
    val cookie = signIn(server).second!!

    assertEquals(200, post(server, cookie, "github:octo", ListDesignsRequestV1()))
    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(200, post(server, cookie, "github:octo", export))
  }

  @Test
  fun `on a box that names nobody, being signed in is not enough to write`() {
    // Without an allowlist or orgs every GitHub account is a member, so repository access stays
    // the bar — the UI builder does not become an open book.
    login = "octo"
    pushAccess = false
    val server = server(allowGuests = false, allowedUsers = emptySet())
    val cookie = signIn(server).second!!

    assertEquals(200, post(server, cookie, "github:octo", ListDesignsRequestV1()))
    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(401, post(server, cookie, "github:octo", export))
  }

  @Test
  fun `a member with repository access keeps everything`() {
    login = "octo"
    pushAccess = true
    val server = server(allowGuests = true)
    val cookie = signIn(server).second!!

    assertEquals(200, post(server, cookie, "github:octo", ListDesignsRequestV1()))
    val export = ExportDesignRequestV1("design", format = ExportFormatV1.SVG)
    assertEquals(200, post(server, cookie, "github:octo", export))
  }
}
