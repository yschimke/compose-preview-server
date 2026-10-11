package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Two servers sharing one requests file, as the old and new processes do during a rollout: a form
 * drawn by one must be accepted by the other, and either must be able to show and revoke a grant
 * the other holds.
 */
class ServeAgentGrantSharedFileRoutingTest {

  private val operatorToken = "operator-secret-token"
  private val dir: Path = Files.createTempDirectory("grants-shared")
  private val requestsFile: Path = dir.resolve("mcp-oauth/agent-access-requests.v2.json")

  private fun store() =
    ServeAgentGrantStore(
      maxScope = AgentGrantScope.PLAYGROUND,
      maxGrantTtlSeconds = 3600,
      requestsFile = requestsFile,
    )

  private val storeA = store()
  private val storeB = store()
  private val registries = mutableListOf<ServeSessionRegistry>()

  private fun server(grants: ServeAgentGrantStore): ServeHttpServer {
    val registry = ServeSessionRegistry(open = { null }).also { registries += it }
    val bundle = Files.createTempDirectory("grants-shared-bundle").toFile()
    File(bundle, "index.html").writeText("<html></html>")
    File(bundle, "previews").mkdirs()
    registry.register("demo", host = ServeBundleHost(bundle, label = "demo"), pinned = true)
    return ServeHttpServer(
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

  private val serverA by lazy { server(storeA) }
  private val serverB by lazy { server(storeB) }

  @AfterTest
  fun tearDown() {
    serverA.stop()
    serverB.stop()
    registries.forEach { it.close() }
    dir.toFile().deleteRecursively()
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun call(
    server: ServeHttpServer,
    path: String,
    body: String? = null,
    contentType: String = "application/json",
    token: String? = null,
  ): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$path")
        .apply { body?.let { post(it.toRequestBody(contentType.toMediaType())) } }
        .apply { token?.let { header(ServeHttpServer.TOKEN_HEADER, it) } }
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  private fun form(server: ServeHttpServer, path: String, body: String) =
    call(server, path, body, contentType = "application/x-www-form-urlencoded")

  private fun field(html: String, name: String): String =
    Regex("name=\"$name\" value=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
      ?: error("no $name field in the page")

  private fun str(text: String, key: String) =
    Json.parseToJsonElement(text).jsonObject[key]!!.jsonPrimitive.content

  /** Ask and approve on [server], and collect the bearer there. */
  private fun grantedOn(server: ServeHttpServer): String {
    val (_, opened) = call(server, "/agent-access/request", """{"scope":"live","label":"fix #1"}""")
    val requestId = str(opened, "requestId")
    val (_, page) = call(server, "/agent-access/$requestId?token=$operatorToken")
    val approve =
      form(
        server,
        "/agent-access/$requestId?token=$operatorToken",
        "action=approve&csrf=${field(page, "csrf")}&scope=live&ttl=1800",
      )
    assertEquals(303, approve.first)
    val (_, polled) =
      call(
        server,
        "/agent-access/poll",
        """{"requestId":"$requestId","deviceSecret":"${str(opened, "deviceSecret")}"}""",
      )
    assertEquals("approved", str(polled, "status"))
    return str(polled, "token")
  }

  private fun revokeForm(page: String): Pair<String, String> {
    val match =
      Regex(
          "action=\"(/agent-access/[^/\"]+/revoke)[^\"]*\">" +
            "<input type=\"hidden\" name=\"csrf\" value=\"([^\"]*)\""
        )
        .find(page) ?: error("no revoke form in the page")
    return match.groupValues[1] to match.groupValues[2]
  }

  @Test
  fun `a revoke form drawn by one process is accepted by the other`() {
    val token = grantedOn(serverA)
    assertEquals(200, call(serverA, "/status.json", token = token).first)
    val (_, status) = call(serverA, "/status?token=$operatorToken")
    val (path, csrf) = revokeForm(status)

    // The POST lands on B, which never held the grant.
    val (code, _) = form(serverB, "$path?token=$operatorToken", "csrf=$csrf")
    assertEquals(302, code)

    assertNull(storeA.grantForToken(token), "revoked on A's next token check")
    assertEquals(404, call(serverA, "/status.json", token = token).first)
  }

  @Test
  fun `either process lists and revokes a grant the other holds`() {
    val token = grantedOn(serverA)
    val fingerprint = storeA.activeGrants().single().fingerprint
    val (_, status) = call(serverB, "/status?token=$operatorToken")
    assertTrue(status.contains(fingerprint), "B lists A's grant")
    assertFalse(status.contains(token))
    val (path, csrf) = revokeForm(status)

    assertEquals(302, form(serverA, "$path?token=$operatorToken", "csrf=$csrf").first)
    assertNull(storeA.grantForToken(token))
    val (_, after) = call(serverB, "/status?token=$operatorToken")
    assertFalse(after.contains(fingerprint), "gone from B's list too")
  }

  @Test
  fun `a denial the file cannot record says try again, not already decided`() {
    val (_, opened) = call(serverA, "/agent-access/request", """{"label":"fix #2"}""")
    val requestId = str(opened, "requestId")
    val (_, page) = call(serverA, "/agent-access/$requestId?token=$operatorToken")
    requestsFile.toFile().writeText("{not json")

    val (code, body) =
      form(
        serverA,
        "/agent-access/$requestId?token=$operatorToken",
        "action=deny&denyCsrf=${field(page, "denyCsrf")}",
      )
    assertEquals(503, code)
    assertTrue(body.contains("Try again in a moment"), body)
    assertFalse(body.contains("Already decided"))
  }

  @Test
  fun `processes on one requests file share one form key, made once`() {
    val keyFile = requestsFile.resolveSibling("agent-grant-csrf.key")
    val key = assertNotNull(storeA.sharedFormKey())
    assertEquals(32, key.size)
    assertTrue(Files.exists(keyFile))
    assertTrue(key.contentEquals(assertNotNull(storeB.sharedFormKey())), "B reads A's key")
    assertTrue(key.contentEquals(assertNotNull(store().sharedFormKey())), "and a restart too")
    assertTrue(key.contentEquals(Files.readAllBytes(keyFile)))
    val permissions = Files.getPosixFilePermissions(keyFile).map { it.name }.toSet()
    assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), permissions)
    // Without a file there is no shared key: each process keeps its own random one.
    assertNull(ServeAgentGrantStore().sharedFormKey())
  }

  @Test
  fun `a revoke the file cannot record answers try again and revokes nothing`() {
    val token = grantedOn(serverA)
    val (_, status) = call(serverB, "/status?token=$operatorToken")
    val (path, csrf) = revokeForm(status)
    val good = requestsFile.toFile().readText()
    requestsFile.toFile().writeText("{not json")

    val (code, body) = form(serverB, "$path?token=$operatorToken", "csrf=$csrf")
    assertEquals(503, code)
    assertTrue(body.contains("Try again in a moment"), body)

    requestsFile.toFile().writeText(good)
    assertNotNull(storeA.grantForToken(token), "still live")
    assertEquals(302, form(serverB, "$path?token=$operatorToken", "csrf=$csrf").first)
    assertNull(storeA.grantForToken(token))
  }
}
