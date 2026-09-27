package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The browser side of an agent grant: a `?token=cpat_…` link is exchanged for an HttpOnly cookie,
 * but never in a way that lets someone else's link take over an identity this browser already
 * holds, and never with a redirect that leaves this origin.
 */
class ServeAgentGrantCookieTest {

  private val operatorToken = "operator-token"
  private val grants = ServeAgentGrantStore(maxScope = AgentGrantScope.LIVE, maxActiveGrants = 10)
  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "unused",
        agentGrants = grants,
      )
      .also { it.start() }
  private val client = OkHttpClient.Builder().followRedirects(false).build()
  private val base = "http://127.0.0.1:${server.port}"

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  private fun grant(label: String): ServeAgentGrantStore.Grant {
    val request =
      requireNotNull(
        grants.openRequest(
          label = label,
          client = "test",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 600,
        )
      )
    return requireNotNull(
      grants.approve(
        request.id,
        approvedBy = "operator",
        scope = AgentGrantScope.PREVIEW,
        ttlSeconds = 600,
      )
    )
  }

  private fun cookieOf(grant: ServeAgentGrantStore.Grant): String =
    "${ServeAgentGrantCookie.NAME}=${requireNotNull(grants.browserCredentialFor(grant)).value}"

  private fun <T> browse(path: String, cookie: String? = null, use: (Response) -> T): T {
    val builder =
      Request.Builder().url("$base$path").header("Accept", "text/html,application/xhtml+xml")
    if (cookie != null) builder.header("Cookie", cookie)
    return client.newCall(builder.build()).execute().use(use)
  }

  private fun Response.grantCookieSet(): String? =
    headers("Set-Cookie").firstOrNull {
      it.startsWith("${ServeAgentGrantCookie.NAME}=") && !it.contains("Max-Age=0")
    }

  private fun post(
    path: String,
    cookie: String?,
    origin: String?,
    fields: Map<String, String> = emptyMap(),
  ): Response {
    val builder =
      Request.Builder()
        .url("$base$path")
        .post(FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())
    if (cookie != null) builder.header("Cookie", cookie)
    if (origin != null) builder.header("Origin", origin)
    return client.newCall(builder.build()).execute()
  }

  @Test
  fun `a grant link on a protocol-relative path redirects within this origin`() {
    val grant = grant("redirect")
    browse("//attacker.example/?token=${grant.token}") {
      assertEquals(302, it.code)
      assertEquals("/attacker.example/", it.header("Location"))
      assertTrue(it.grantCookieSet() != null)
    }
    browse("/%5C/attacker.example/?token=${grant.token}") {
      assertEquals(302, it.code)
      assertFalse(it.header("Location").orEmpty().startsWith("//"), it.header("Location"))
    }
    assertEquals("/attacker.example/", ServeAgentGrantCookie.safeLocalTarget("//attacker.example/"))
    assertEquals("/attacker.example", ServeAgentGrantCookie.safeLocalTarget("/\\attacker.example"))
    assertEquals("/", ServeAgentGrantCookie.safeLocalTarget("/a\r\nSet-Cookie: x=y"))
    assertEquals("/status?b=1", ServeAgentGrantCookie.safeLocalTarget("/status?token=x&b=1"))
  }

  @Test
  fun `the operator browse cookie outranks a grant link`() {
    val attacker = grant("attacker")
    val operatorCookie = "${ServeBrowseCookie.NAME}=${ServeBrowseCookie.value(operatorToken)}"
    browse("/status?token=${attacker.token}", operatorCookie) {
      assertEquals(302, it.code)
      assertEquals("/status", it.header("Location"))
      assertEquals(null, it.grantCookieSet(), "the operator's browser must not become the grant")
    }
  }

  @Test
  fun `another grant's link asks before replacing this browser's grant`() {
    val mine = grant("mine")
    val attacker = grant("attacker")
    val html =
      browse("/status?token=${attacker.token}", cookieOf(mine)) {
        assertEquals(200, it.code)
        assertEquals(null, it.grantCookieSet(), "a link alone must not switch the identity")
        assertEquals("no-store", it.header("Cache-Control"))
        it.body.string()
      }
    assertTrue(html.contains("Switch agent access?"), html)
    assertTrue(html.contains(mine.fingerprint), html)
    assertTrue(html.contains(attacker.fingerprint), html)
    assertTrue(html.contains("action=\"${ServeAgentGrants.SWITCH_PATH}\""), html)

    val fields = mapOf("token" to attacker.token, "next" to "/status")
    // A foreign page, or a request that says nothing about where it came from, cannot confirm it.
    post(ServeAgentGrants.SWITCH_PATH, cookieOf(mine), "https://attacker.example", fields).use {
      assertEquals(403, it.code)
      assertEquals(null, it.grantCookieSet())
    }
    post(ServeAgentGrants.SWITCH_PATH, cookieOf(mine), origin = null, fields).use {
      assertEquals(403, it.code)
      assertEquals(null, it.grantCookieSet())
    }
    // The person confirming on this server's page does switch, and lands back on this origin.
    post(
        ServeAgentGrants.SWITCH_PATH,
        cookieOf(mine),
        base,
        fields + ("next" to "//attacker.example/"),
      )
      .use {
        assertEquals(303, it.code)
        assertEquals("/attacker.example/", it.header("Location"))
        assertEquals(cookieOf(attacker), it.grantCookieSet()?.substringBefore(';'))
      }
  }

  @Test
  fun `the same grant's link and a stale cookie still exchange without asking`() {
    val mine = grant("mine")
    browse("/status?token=${mine.token}", cookieOf(mine)) {
      assertEquals(302, it.code)
      assertEquals("/status", it.header("Location"))
    }
    browse("/status?token=${mine.token}", "${ServeAgentGrantCookie.NAME}=cpab_stale") {
      assertEquals(302, it.code)
      assertEquals(cookieOf(mine), it.grantCookieSet()?.substringBefore(';'))
    }
  }

  @Test
  fun `leaving a grant is refused from a foreign page and clears it from this one`() {
    val mine = grant("mine")
    post(ServeAgentGrants.LEAVE_PATH, cookieOf(mine), "https://attacker.example").use {
      assertEquals(403, it.code)
      assertTrue(it.headers("Set-Cookie").none { c -> c.startsWith(ServeAgentGrantCookie.NAME) })
    }
    post(ServeAgentGrants.LEAVE_PATH, cookieOf(mine), base).use {
      assertEquals(204, it.code)
      val cleared =
        it.headers("Set-Cookie").single { c -> c.startsWith(ServeAgentGrantCookie.NAME) }
      assertTrue(cleared.contains("Max-Age=0"), cleared)
    }
  }

  @Test
  fun `a grant cookie is not accepted for a state-changing request from another origin`() {
    val mine = grant("mine")
    val request =
      Request.Builder()
        .url("$base/api/presence")
        .header("Cookie", cookieOf(mine))
        .header("Origin", "https://attacker.example")
        .post("{}".toRequestBody())
        .build()
    client.newCall(request).execute().use {
      assertTrue(it.code == 401 || it.code == 403 || it.code == 404, "got ${it.code}")
    }
  }
}
