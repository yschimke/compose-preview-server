package ee.schimke.composeai.cli.serve

import java.nio.file.Path
import java.security.interfaces.ECPublicKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.io.TempDir

/**
 * The push routes on a real server, signed in through the real OAuth round trip against a fake
 * GitHub: who may subscribe, from where, with what — and that nothing a route answers carries an
 * endpoint or a key.
 */
class ServePushRoutesTest {
  @TempDir lateinit var root: Path

  /** Who the fake GitHub says signed in; changed between sign-ins to hand the browser over. */
  @Volatile private var login = "octo"

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

  private val auth =
    ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
    )

  private val registry = ServeSessionRegistry(open = { null })

  private val store by lazy { ServePushSubscriptionStore(root.resolve("push")) }
  private val keys by lazy {
    ServeVapidKeys.loadOrCreate(root.resolve("push"), ServeVapidKeys.DEFAULT_SUBJECT)
  }

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "browse-token",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        githubAuth = auth,
        push =
          ServePushLane(
            store = store,
            keys = keys,
            actorOf = { call ->
              auth.currentSignedInLogin(call)?.let(ServeAgentGrants::githubActorId)
            },
          ),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()
  private val noRedirect by lazy { client.newBuilder().followRedirects(false).build() }

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  /** The OAuth round trip; answers `cp_gh_auth=<value>`. */
  private fun signIn(): String {
    val start =
      noRedirect.newCall(Request.Builder().url(url("/auth/github/start")).build()).execute().use {
        it.header("Location").orEmpty() to it.header("Set-Cookie").orEmpty().substringBefore(";")
      }
    val state = start.first.substringAfter("state=").substringBefore("&")
    return noRedirect
      .newCall(
        Request.Builder()
          .url(url("/auth/github/callback?code=ok&state=$state"))
          .header("Cookie", start.second)
          .build()
      )
      .execute()
      .use { resp ->
        resp.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") }.substringBefore(";")
      }
  }

  private val browserKey =
    ServeWebPush.base64Url(
      ServeWebPush.rawPublicKey(ServeWebPush.generateKeyPair().public as ECPublicKey)
    )
  private val authSecret = ServeWebPush.base64Url(ByteArray(16) { 9 })

  private fun subscription(
    endpoint: String = ENDPOINT,
    p256dh: String = browserKey,
    kinds: String = "",
  ) = """{"endpoint":"$endpoint","keys":{"p256dh":"$p256dh","auth":"$authSecret"}$kinds}"""

  private fun call(
    method: String,
    path: String,
    body: String? = null,
    cookie: String? = null,
    origin: String? = "same",
    contentType: String = "application/json",
    headers: Map<String, String> = emptyMap(),
  ): Pair<Int, String> {
    val builder = Request.Builder().url(url(path))
    cookie?.let { builder.header("Cookie", it) }
    when (origin) {
      null -> {}
      "same" -> builder.header("Origin", "http://127.0.0.1:${server.port}")
      else -> builder.header("Origin", origin)
    }
    headers.forEach { (k, v) -> builder.header(k, v) }
    builder.method(method, body?.toRequestBody(contentType.toMediaType()))
    return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  @Test
  fun `the public key is public and names the kinds`() {
    val (status, body) = call("GET", PUSH_KEY_PATH, origin = null)
    assertEquals(200, status)
    val json = Json.parseToJsonElement(body).jsonObject
    assertEquals(keys.publicKey, json["publicKey"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("replies", "mentions", "reviews"),
      json["kinds"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content },
    )
  }

  @Test
  fun `anonymous and token-only callers cannot subscribe`() {
    assertEquals(401, call("POST", PUSH_SUBSCRIBE_PATH, subscription()).first)
    assertEquals(
      401,
      call(
          "POST",
          PUSH_SUBSCRIBE_PATH,
          subscription(),
          headers = mapOf(ServeHttpServer.TOKEN_HEADER to "browse-token"),
        )
        .first,
    )
    assertEquals(401, call("GET", PUSH_PREFERENCES_PATH).first)
    assertTrue(store.all().isEmpty())
  }

  @Test
  fun `a write from another origin, or not declared JSON, is refused`() {
    val cookie = signIn()
    assertEquals(
      403,
      call("POST", PUSH_SUBSCRIBE_PATH, subscription(), cookie, origin = "https://evil.example")
        .first,
    )
    assertEquals(
      403,
      call(
          "PUT",
          PUSH_PREFERENCES_PATH,
          """{"kinds":[]}""",
          cookie,
          origin = null,
          headers = mapOf("Sec-Fetch-Site" to "cross-site"),
        )
        .first,
    )
    assertEquals(
      415,
      call("POST", PUSH_SUBSCRIBE_PATH, subscription(), cookie, contentType = "text/plain").first,
    )
    assertTrue(store.all().isEmpty())
  }

  @Test
  fun `endpoints and keys are validated`() {
    val cookie = signIn()
    for (bad in
      listOf("http://fcm.googleapis.com/x", "https://169.254.169.254/x", "https://localhost/x")) {
      val (status, body) = call("POST", PUSH_SUBSCRIBE_PATH, subscription(endpoint = bad), cookie)
      assertEquals(422, status, bad)
      assertFalse(bad in body, "the refusal echoed the endpoint")
    }
    assertEquals(
      422,
      call("POST", PUSH_SUBSCRIBE_PATH, subscription(p256dh = "AAAA"), cookie).first,
    )
    assertEquals(
      422,
      call("POST", PUSH_SUBSCRIBE_PATH, subscription(kinds = ""","kinds":["everything"]"""), cookie)
        .first,
    )
    assertEquals(400, call("POST", PUSH_SUBSCRIBE_PATH, "{", cookie).first)
    assertTrue(store.all().isEmpty())
  }

  @Test
  fun `subscribe, choose kinds, unsubscribe — keyed by the GitHub identity`() {
    val cookie = signIn()
    val (created, body) =
      call("POST", PUSH_SUBSCRIBE_PATH, subscription(kinds = ""","kinds":["replies"]"""), cookie)
    assertEquals(201, created, body)
    assertFalse(ENDPOINT in body || browserKey in body, "a route answered with the subscription")
    val stored = store.all().single()
    assertEquals("github:octo", stored.actor)
    assertEquals(setOf("replies"), stored.kinds)

    val (status, preferences) = call("GET", PUSH_PREFERENCES_PATH, cookie = cookie, origin = null)
    assertEquals(200, status)
    val json = Json.parseToJsonElement(preferences).jsonObject
    assertEquals(listOf("replies"), json["kinds"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals(1, json["devices"]!!.jsonPrimitive.int)

    assertEquals(
      200,
      call("PUT", PUSH_PREFERENCES_PATH, """{"kinds":["mentions","reviews"]}""", cookie).first,
    )
    assertEquals(setOf("mentions", "reviews"), store.all().single().kinds)

    assertEquals(
      404,
      call("DELETE", PUSH_SUBSCRIBE_PATH, """{"endpoint":"$ENDPOINT/other"}""", cookie).first,
    )
    assertEquals(
      200,
      call("DELETE", PUSH_SUBSCRIBE_PATH, """{"endpoint":"$ENDPOINT"}""", cookie).first,
    )
    assertTrue(store.all().isEmpty())
  }

  /** [method] [path], answering the status, the body and the `Set-Cookie` lines. */
  private fun exchange(
    method: String,
    path: String,
    body: String,
    cookie: String,
  ): Triple<Int, String, List<String>> {
    val request =
      Request.Builder()
        .url(url(path))
        .header("Cookie", cookie)
        .header("Origin", "http://127.0.0.1:${server.port}")
        .method(method, body.toRequestBody("application/json".toMediaType()))
        .build()
    return noRedirect.newCall(request).execute().use {
      Triple(it.code, it.body.string(), it.headers("Set-Cookie"))
    }
  }

  private fun kindsOf(body: String): List<String> =
    Json.parseToJsonElement(body).jsonObject["kinds"]!!.jsonArray.map { it.jsonPrimitive.content }

  private fun deviceCookie(setCookies: List<String>): String =
    setCookies.first { it.startsWith("$PUSH_DEVICE_COOKIE=") }.substringBefore(";")

  @Test
  fun `a browser that changes hands is rebound to whoever signs in, and re-posting is idempotent`() {
    login = "alice"
    val alice = signIn()
    val (created, body, cookies) =
      exchange("POST", PUSH_SUBSCRIBE_PATH, subscription(kinds = ""","kinds":["replies"]"""), alice)
    assertEquals(201, created, body)
    assertEquals(
      "$PUSH_DEVICE_COOKIE=${ServePushSubscriptionStore.deviceOf(ENDPOINT)}",
      deviceCookie(cookies),
    )
    assertFalse(cookies.any { ENDPOINT in it }, "the device cookie carried the endpoint")
    val stored = store.all().single()

    // What the settings page does on every load: re-post, without kinds. Nothing changes, and the
    // answer is alice's choice.
    val (again, againBody, _) = exchange("POST", PUSH_SUBSCRIBE_PATH, subscription(), alice)
    assertEquals(201, again)
    assertEquals(listOf("replies"), kindsOf(againBody))
    assertEquals(listOf(stored), store.all())

    // Bob signs in on the same browser. The page's re-post binds the endpoint to him, so alice's
    // replies stop arriving here, and what he is shown is his own choice, not hers.
    login = "bob"
    val bob = signIn()
    val (rebound, reboundBody, _) = exchange("POST", PUSH_SUBSCRIBE_PATH, subscription(), bob)
    assertEquals(201, rebound)
    assertEquals(listOf("mentions", "replies", "reviews"), kindsOf(reboundBody))
    assertTrue(store.forActor("github:alice").isEmpty())
    assertEquals(ENDPOINT, store.forActor("github:bob").single().endpoint)
  }

  @Test
  fun `signing out drops the subscription this browser bound to the person signing out`() {
    login = "alice"
    val alice = signIn()
    val (_, _, cookies) = exchange("POST", PUSH_SUBSCRIBE_PATH, subscription(), alice)
    val device = deviceCookie(cookies)
    exchange("POST", PUSH_SUBSCRIBE_PATH, subscription(endpoint = "$ENDPOINT-phone"), alice)
    assertEquals(2, store.forActor("github:alice").size)

    // A sign-out without the device cookie (a browser that never subscribed) touches nothing.
    assertEquals(302, exchange("POST", ServeGithubAuth.LOGOUT_PATH, "", alice).first)
    assertEquals(2, store.forActor("github:alice").size)

    // Somebody else's session cannot use the cookie to drop alice's subscription.
    login = "bob"
    val bob = signIn()
    exchange("POST", ServeGithubAuth.LOGOUT_PATH, "", "$bob; $device")
    assertEquals(2, store.forActor("github:alice").size)

    // Alice signing out of this browser drops this browser's subscription and keeps her phone's.
    val (status, _, cleared) = exchange("POST", ServeGithubAuth.LOGOUT_PATH, "", "$alice; $device")
    assertEquals(302, status)
    assertEquals(listOf("$ENDPOINT-phone"), store.forActor("github:alice").map { it.endpoint })
    assertTrue(
      cleared.any { it.startsWith("$PUSH_DEVICE_COOKIE=;") && "Max-Age=0" in it },
      cleared.toString(),
    )
  }

  @Test
  fun `the push worker is served uncached at the root with a root scope`() {
    val response =
      client.newCall(Request.Builder().url(url(PUSH_SERVICE_WORKER_PATH)).build()).execute()
    response.use {
      assertEquals(200, it.code)
      assertEquals("no-cache", it.header("Cache-Control"))
      assertEquals("/", it.header("Service-Worker-Allowed"))
      assertTrue(it.header("Content-Type").orEmpty().startsWith("text/javascript"))
      val script = it.body.string()
      assertTrue("notificationclick" in script)
      assertFalse(
        Regex("""addEventListener\(\s*["']fetch["']""").containsMatchIn(script),
        "the push worker must not handle fetch",
      )
      assertTrue(ServeSiteIcon.APP_ICON_192_PATH in script)
    }
  }

  @Test
  fun `a signed-in page offers Notifications in Settings, and a signed-out one does not`() {
    val cookie = signIn()
    val signedIn = call("GET", "/status", cookie = cookie, origin = null)
    assertEquals(200, signedIn.first)
    assertTrue("data-cp-push-settings" in signedIn.second)
    assertTrue("push-settings.js" in signedIn.second)
    val signedOut = call("GET", "/status", origin = null)
    assertFalse("data-cp-push-settings" in signedOut.second)
  }

  private companion object {
    const val ENDPOINT = "https://fcm.googleapis.com/fcm/send/abc"
  }
}
