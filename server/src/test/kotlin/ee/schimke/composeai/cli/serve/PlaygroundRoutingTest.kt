package ee.schimke.composeai.cli.serve

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * The **playground lane** over real HTTP: `POST /api/{v}/compiler/run` returns the Stage-1 result
 * (diagnostics + preview token) when the lane is enabled, and 404s when it isn't. The compile
 * service is driven by fakes (a real compile is `PlaygroundBtaCompiler`'s job, covered elsewhere)
 * so this is purely about the route wiring + JSON contract.
 */
class PlaygroundRoutingTest {

  private val fs = FakeFileSystem()
  private var workN = 0

  // Shared between the compile service (mints tokens) and the redeem service (looks them up), like
  // production — so the token-gated redemption test below exercises a real mint → redeem
  // round-trip.
  private val tokenStore = PlaygroundTokenStore(fileSystem = fs)

  private val playground =
    PlaygroundCompileService(
      catalogClasspath = { mode, _ ->
        if (mode == PlaygroundMode.CMP) {
          PlaygroundCompileService.Classpath("compose-m3", listOf("/cat/app.jar".toPath()))
        } else {
          null
        }
      },
      compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
      discoverer =
        PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("com.example.PScreen") },
      tokenStore = tokenStore,
      newWorkDir = { "/work/run${++workN}".toPath() },
      fileSystem = fs,
    )

  private val editingPlayground =
    PlaygroundCompileService(
      catalogClasspath = { mode, _ ->
        if (mode == PlaygroundMode.CMP) {
          PlaygroundCompileService.Classpath("compose-m3", listOf("/cat/app.jar".toPath()))
        } else {
          null
        }
      },
      compiler = PlaygroundCompileService.Compiler { _, _, _ -> emptyList() },
      discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> listOf("com.example.P") },
      tokenStore = tokenStore,
      newWorkDir = { "/work/edit${++workN}".toPath() },
      fileSystem = fs,
      editLeasesEnabled = true,
    )

  private val registry = ServeSessionRegistry(open = { null })

  // Materialize always succeeds (a real daemon isn't in scope here) so redemption reaches its Live
  // redirect — the path we assert isn't shadowed by the access token on a gated host.
  private val redeem =
    PlaygroundRedeemService(
      tokenStore = tokenStore,
      registry = registry,
      materialize = { snippet ->
        ServeSessionState(
          descriptor = java.io.File("/tmp/pg-none.json"),
          workspaceRoot = java.io.File("/tmp"),
          workspaceName = "pg",
          previews = listOf(ServePreview(id = snippet.previewId, label = snippet.previewId)),
          label = "pg",
        )
      },
    )

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        playgroundService = playground,
      )
      .also { it.start() }
  }

  /** A host with no playground service — the lane must not exist there. */
  private val plainServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "none",
        isPublic = true,
      )
      .also { it.start() }
  }

  /**
   * A **token-gated** host (`isPublic = false`) with the redemption lane wired — the shape a real
   * playground runs as (the lane is refused under `--public`). Here the access token rides as
   * `?token=…`, which is what made the `/pg/{token}` path-param collision surface.
   */
  private val gatedServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "sekret",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = false,
        playgroundService = playground,
        playgroundRedeem = redeem,
      )
      .also { it.start() }
  }

  private val githubNoRepoServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        playgroundService = playground,
        githubAuth = githubAuth(repositoryAccess = false),
      )
      .also { it.start() }
  }

  private val githubRepoServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        playgroundService = editingPlayground,
        githubAuth = githubAuth(repositoryAccess = true),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { plainServer.stop() }
    runCatching { gatedServer.stop() }
    runCatching { githubNoRepoServer.stop() }
    runCatching { githubRepoServer.stop() }
    runCatching { limitedServer.stop() }
    runCatching { registry.close() }
  }

  private fun postRun(body: String, port: Int) =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:$port/api/1/compiler/run")
          .post(body.toRequestBody("application/json".toMediaType()))
          .build()
      )
      .execute()

  private fun post(path: String, body: String, port: Int, cookie: String? = null): Response {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:$port$path")
        .post(body.toRequestBody("application/json".toMediaType()))
        .apply { cookie?.let { header("Cookie", it) } }
        .build()
    return client.newCall(request).execute()
  }

  @Test
  fun `a clean compile returns a preview token over the run route`() {
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview @Composable fun P(){}"}],"confType":"compose-cmp"}"""
    postRun(body, server.port).use { resp ->
      assertEquals(200, resp.code)
      val json = Json.parseToJsonElement(resp.body.string()).jsonObject
      assertTrue(
        json["previewToken"]?.jsonPrimitive?.content?.startsWith("pg_") == true,
        "a clean compile mints a pg_ token: $json",
      )
      assertEquals(
        "/pg/${json["previewToken"]!!.jsonPrimitive.content}",
        json["previewUrl"]!!.jsonPrimitive.content,
      )
    }
  }

  /**
   * A host whose compile lane carries a per-caller budget of two per window (issue #3214). Two is
   * the smallest number that still shows the *bucket* rather than only its floor.
   */
  private val limitedServer: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused-in-public",
        sessions = registry,
        defaultSessionId = "none",
        isPublic = true,
        playgroundService = playground,
        playgroundRateLimiter =
          ServeRateLimiter(permitsPerWindow = 2, windowSeconds = 600, maxConcurrent = 2),
      )
      .also { it.start() }
  }

  @Test
  fun `a caller over their compile budget gets 429 with Retry-After`() {
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview @Composable fun P(){}"}],"confType":"compose-cmp"}"""
    repeat(2) { i ->
      postRun(body, limitedServer.port).use { resp ->
        assertEquals(200, resp.code, "budgeted request ${i + 1} should be admitted")
      }
    }
    postRun(body, limitedServer.port).use { resp ->
      assertEquals(429, resp.code)
      val retryAfter = resp.header("Retry-After")?.toLongOrNull()
      assertTrue(retryAfter != null && retryAfter >= 1, "Retry-After should be set: $retryAfter")
      // Answered in the run route's own JSON shape, so the editor surfaces it as a status line
      // rather than as an unparseable body.
      val json = Json.parseToJsonElement(resp.body.string()).jsonObject
      assertTrue(
        json["exception"]?.jsonPrimitive?.content?.contains("Too many requests") == true,
        "the refusal rides the run response contract: $json",
      )
      // …and it costs the caller nothing: a throttled request never reaches the compiler, so no
      // token is minted and no work dir is allocated for it.
      assertTrue(
        json["previewToken"]?.jsonPrimitive?.contentOrNull?.startsWith("pg_") != true,
        "a throttled request must not mint a token: $json",
      )
    }
  }

  @Test
  fun `an unmetered host still admits every compile`() {
    // The default `server` has no limiter wired — the pre-#3214 behaviour, which must be exactly
    // what a host that opts out of the budget still gets.
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview @Composable fun P(){}"}],"confType":"compose-cmp"}"""
    repeat(4) { i ->
      postRun(body, server.port).use { resp -> assertEquals(200, resp.code, "request ${i + 1}") }
    }
  }

  @Test
  fun `the run route is absent when the playground lane isn't enabled`() {
    val body = """{"files":[{"name":"S.kt","text":"x"}],"confType":"compose-cmp"}"""
    postRun(body, plainServer.port).use { resp -> assertEquals(404, resp.code) }
  }

  private fun get(path: String, port: Int) =
    client.newCall(Request.Builder().url("http://127.0.0.1:$port$path").build()).execute()

  private fun get(path: String, port: Int, cookie: String) =
    client
      .newCall(
        Request.Builder().url("http://127.0.0.1:$port$path").header("Cookie", cookie).build()
      )
      .execute()

  @Test
  fun `the editor page is served when the playground lane is enabled`() {
    get("/playground", server.port).use { resp ->
      assertEquals(200, resp.code)
      assertTrue(
        resp.header("Content-Type")?.contains("text/html") == true,
        "the editor page is served as HTML",
      )
      val html = resp.body.string()
      assertTrue(
        html.contains("id=\"pg-source\"") &&
          html.contains("id=\"pg-run\"") &&
          html.contains("/api/1/compiler/run"),
        "the editor page exposes the source box, Run button, and the compile route",
      )
    }
  }

  @Test
  fun `github login without repo rights cannot open playground`() {
    val cookie = githubSessionCookie(githubNoRepoServer.port)
    get("/playground", githubNoRepoServer.port, cookie).use { resp ->
      assertEquals(403, resp.code)
      assertTrue(
        resp.body.string().contains("Playground requires access to yschimke/compose-ai-tools"),
        "playground explains the repo-rights gate",
      )
    }
  }

  @Test
  fun `github login with repo rights can open playground`() {
    val cookie = githubSessionCookie(githubRepoServer.port)
    get("/playground", githubRepoServer.port, cookie).use { resp ->
      assertEquals(200, resp.code)
      val html = resp.body.string()
      assertTrue(html.contains("id=\"pg-source\""))
      assertTrue(html.contains("id=\"pg-edit-lease\""))
    }
  }

  @Test
  fun `authenticated user acquires the single editing lease and compiles a revision`() {
    val cookie = githubSessionCookie(githubRepoServer.port)
    val lease =
      post(
          "/api/1/compiler/edit-lease",
          """{"client":"tab-a"}""",
          githubRepoServer.port,
          cookie,
        )
        .use { resp ->
          assertEquals(200, resp.code)
          Json.parseToJsonElement(resp.body.string()).jsonObject["lease"]!!.jsonPrimitive.content
        }
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview fun P(){}"}],"confType":"compose-cmp","editLease":"$lease","revision":1}"""

    post("/api/1/compiler/run", body, githubRepoServer.port, cookie).use { resp ->
      assertEquals(200, resp.code)
      val json = Json.parseToJsonElement(resp.body.string()).jsonObject
      assertEquals("1", json["revision"]?.jsonPrimitive?.content)
      assertEquals(lease, json["editLease"]?.jsonPrimitive?.content)
      assertTrue(json["previewToken"]?.jsonPrimitive?.content?.startsWith("pg_") == true)
    }

    post(
        "/api/1/compiler/edit-lease",
        """{"client":"tab-b"}""",
        githubRepoServer.port,
        cookie,
      )
      .use { resp ->
        assertEquals(200, resp.code)
        val json = Json.parseToJsonElement(resp.body.string()).jsonObject
        assertEquals(lease, json["lease"]?.jsonPrimitive?.content)
        assertEquals("1", json["revision"]?.jsonPrimitive?.content)
      }
  }

  private fun postWith(
    path: String,
    port: Int,
    body: String = """{"client":"tab-a"}""",
    mediaType: String = "application/json",
    headers: Map<String, String> = emptyMap(),
  ): Response {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:$port$path")
        .post(body.toRequestBody(mediaType.toMediaType()))
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    return client.newCall(request).execute()
  }

  @Test
  fun `a session-cookie write from another origin is refused`() {
    val cookie = githubSessionCookie(githubRepoServer.port)
    postWith(
        "/api/1/compiler/edit-lease",
        githubRepoServer.port,
        headers = mapOf("Cookie" to cookie, "Origin" to "https://elsewhere.example"),
      )
      .use { resp -> assertEquals(403, resp.code) }
    postWith(
        "/api/1/compiler/edit-lease",
        githubRepoServer.port,
        headers = mapOf("Cookie" to cookie, "Sec-Fetch-Site" to "same-site"),
      )
      .use { resp -> assertEquals(403, resp.code) }
  }

  @Test
  fun `a session-cookie write from this server's own page is accepted`() {
    val cookie = githubSessionCookie(githubRepoServer.port)
    val port = githubRepoServer.port
    val lease =
      postWith(
          "/api/1/compiler/edit-lease",
          port,
          headers = mapOf("Cookie" to cookie, "Origin" to "http://127.0.0.1:$port"),
        )
        .use { resp ->
          assertEquals(200, resp.code)
          Json.parseToJsonElement(resp.body.string()).jsonObject["lease"]!!.jsonPrimitive.content
        }
    postWith(
        "/api/1/compiler/edit-lease/release",
        port,
        body = """{"lease":"$lease","client":"tab-a"}""",
        headers = mapOf("Cookie" to cookie, "Sec-Fetch-Site" to "same-origin"),
      )
      .use { resp -> assertEquals(200, resp.code) }
  }

  @Test
  fun `a session-cookie compile must declare a JSON body`() {
    val cookie = githubSessionCookie(githubRepoServer.port)
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview fun P(){}"}],"confType":"compose-cmp"}"""
    postWith(
        "/api/1/compiler/run",
        githubRepoServer.port,
        body = body,
        mediaType = "text/plain",
        headers = mapOf("Cookie" to cookie),
      )
      .use { resp -> assertEquals(415, resp.code) }
  }

  @Test
  fun `token-authenticated compiles are unaffected by origin`() {
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview @Composable fun P(){}"}],"confType":"compose-cmp"}"""
    // A header credential beside a stray cookie, from a foreign origin, with a non-JSON label: the
    // header is what authenticates it, so none of the session-cookie rules apply.
    postWith(
        "/api/1/compiler/run",
        gatedServer.port,
        body = body,
        mediaType = "text/plain",
        headers =
          mapOf(
            ServeHttpServer.TOKEN_HEADER to "sekret",
            "Cookie" to "${ServeSameOriginRequests.SESSION_COOKIE}=stale",
            "Origin" to "https://elsewhere.example",
          ),
      )
      .use { resp -> assertEquals(200, resp.code) }
    // …and a query token with no Origin at all, the shape a script sends.
    postWith("/api/1/compiler/run?token=sekret", gatedServer.port, body = body).use { resp ->
      assertEquals(200, resp.code)
    }
  }

  /** Opens `/ws/{name}` and answers the upgrade's HTTP status: 101 when it opened. */
  private fun socketStatus(port: Int, query: String, headers: Map<String, String>): Int? {
    val done = CountDownLatch(1)
    // Only the handshake's answer counts. The server closes a socket for an unknown session
    // straight
    // after the upgrade, and OkHttp then reports that as a failure with no response, which must not
    // replace the 101 already recorded.
    val status = AtomicReference<Int?>()
    val answered = AtomicBoolean(false)
    fun answer(code: Int?) {
      if (answered.compareAndSet(false, true)) {
        status.set(code)
        done.countDown()
      }
    }
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url("ws://127.0.0.1:$port/ws/P$query")
          .apply { headers.forEach { (name, value) -> header(name, value) } }
          .build(),
        object : WebSocketListener() {
          override fun onOpen(webSocket: WebSocket, response: Response) = answer(response.code)

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            answer(response?.code)
        },
      )
    assertTrue(done.await(5, TimeUnit.SECONDS), "the upgrade should be answered")
    socket.cancel()
    return status.get()
  }

  @Test
  fun `a session-cookie socket is upgraded only from this server's own page`() {
    val cookie = githubSessionCookie(githubRepoServer.port)
    val port = githubRepoServer.port
    assertEquals(
      403,
      socketStatus(port, "", mapOf("Cookie" to cookie, "Origin" to "https://elsewhere.example")),
    )
    assertEquals(
      101,
      socketStatus(port, "", mapOf("Cookie" to cookie, "Origin" to "http://127.0.0.1:$port")),
    )
    // A token-authenticated socket carries no session cookie and is not judged by its origin.
    assertEquals(
      101,
      socketStatus(
        gatedServer.port,
        "?token=sekret",
        mapOf("Origin" to "https://elsewhere.example"),
      ),
    )
  }

  @Test
  fun `oversized edit lease acquisition is rejected`() {
    val cookie = githubSessionCookie(githubRepoServer.port)

    post(
        "/api/1/compiler/edit-lease",
        "x".repeat(256 * 1024 + 1),
        githubRepoServer.port,
        cookie,
      )
      .use { resp ->
        val responseBody = resp.body.string()
        assertEquals(413, resp.code, "unexpected oversized-body response: $responseBody")
        assertTrue(responseBody.contains("exceeds 256KB"))
      }
  }

  @Test
  fun `the editor page explains when the playground lane isn't enabled`() {
    get("/playground", plainServer.port).use { resp ->
      assertEquals(503, resp.code)
      assertEquals("no-store", resp.header("Cache-Control"))
      assertTrue(
        resp.body.string().contains("Playground unavailable"),
        "the reserved playground path explains the missing server config",
      )
    }
  }

  @Test
  fun `a gated pg redemption redirects to the viewer instead of 404ing on the access token`() {
    // Mint a token on the gated host; the access token rides as ?token=sekret.
    val body =
      """{"files":[{"name":"Snippet.kt","text":"@Preview @Composable fun P(){}"}],"confType":"compose-cmp"}"""
    val previewUrl =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${gatedServer.port}/api/1/compiler/run?token=sekret")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        )
        .execute()
        .use { resp ->
          assertEquals(200, resp.code)
          Json.parseToJsonElement(resp.body.string())
            .jsonObject["previewUrl"]!!
            .jsonPrimitive
            .content
        }

    // Redeem it WITH the access token in the query. The `/pg/{pgToken}` path segment must resolve
    // to
    // the minted id — NOT be shadowed by the same-named `?token=` access token (which would fail
    // the
    // pg_ shape check and 404 as NotFound). Assert the 302 to the viewer, don't follow it.
    val noRedirect = client.newBuilder().followRedirects(false).build()
    noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${gatedServer.port}$previewUrl?token=sekret")
          .build()
      )
      .execute()
      .use { resp ->
        assertEquals(302, resp.code, "redemption redirects to the viewer, not a 404")
        assertTrue(
          resp.header("Location")?.contains("/p/") == true,
          "redirect targets the viewer /p/ route: ${resp.header("Location")}",
        )
      }
  }

  private fun githubAuth(repositoryAccess: Boolean): ServeGithubAuth {
    val fakeGitHub =
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          val request = chain.request()
          val path = request.url.encodedPath
          val body =
            when {
              path == "/login/oauth/access_token" -> """{"access_token":"token"}"""
              path == "/user" -> """{"login":"octo"}"""
              path.endsWith("/permission") ->
                if (repositoryAccess) """{"permission":"write"}""" else """{"permission":"none"}"""
              else -> "{}"
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
    return ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
    )
  }

  private fun githubSessionCookie(port: Int): String {
    val noRedirect = client.newBuilder().followRedirects(false).build()
    val start =
      noRedirect
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:$port/auth/github/start?return=/playground")
            .build()
        )
        .execute()
        .use { resp ->
          assertEquals(302, resp.code)
          resp.header("Location").orEmpty() to
            resp.header("Set-Cookie").orEmpty().substringBefore(";")
        }
    // Sign-in asks for `read:user` and NOT `repo`. `repo` is full control of the visitor's private
    // repositories, and sign-in never needs it; see ServeGithubAuthScopeTest.
    assertTrue(
      start.first.contains("scope=read%3Auser&") || start.first.endsWith("scope=read%3Auser"),
      "sign-in must not ask for the repo scope: ${start.first}",
    )
    assertTrue(
      !start.first.contains("repo", ignoreCase = true) ||
        !start.first.substringAfter("scope=").substringBefore("&").contains("repo"),
      "the requested scope must not include repo: ${start.first}",
    )
    val state = start.first.substringAfter("state=").substringBefore("&")
    return noRedirect
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:$port/auth/github/callback?code=ok&state=$state")
          .header("Cookie", start.second)
          .build()
      )
      .execute()
      .use { resp ->
        assertEquals(302, resp.code)
        resp.headers("Set-Cookie").first { it.startsWith("cp_gh_auth=") }.substringBefore(";")
      }
  }
}
