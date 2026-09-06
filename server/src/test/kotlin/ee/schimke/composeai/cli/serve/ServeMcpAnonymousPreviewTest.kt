package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Whether presenting nothing is enough, over real HTTP, on a public box and on a token-gated one.
 *
 * The bug this pins down was not a refused tool call — it was a refused *handshake*. A client
 * issues `resources/list` before it calls anything, so a `401` there made a fresh client report the
 * whole server as needing authorization and stop, including the two tools whose only purpose is to
 * obtain a grant. On a `--public` box that refusal also protected nothing: `GET /api/previews`
 * already answers an anonymous caller with the very same listing.
 *
 * So the rung moved, not the method — only the bottom one, and only where the box already
 * publishes. The token-gated case here is the half that matters most: it must still say no.
 */
class ServeMcpAnonymousPreviewTest {

  private val operatorToken = "operator-secret-token"

  private val servers = mutableListOf<ServeHttpServer>()
  private val registries = mutableListOf<ServeSessionRegistry>()

  private fun start(isPublic: Boolean): ServeHttpServer {
    val registry = ServeSessionRegistry(open = { null }).also { registries += it }
    val dir = Files.createTempDirectory("anon").toFile().also { it.deleteOnExit() }
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
    val grants = ServeAgentGrantStore(maxScope = AgentGrantScope.PLAYGROUND)
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "demo",
        isPublic = isPublic,
        agentGrants = grants,
        catalogMcpEnabled = true,
        machineAuthorization =
          ServeMachineAuthorization(operatorToken, null, grants, isPublic = isPublic),
      )
      .also {
        it.start()
        servers += it
      }
  }

  private val client = OkHttpClient.Builder().followRedirects(false).build()

  private fun mcp(server: ServeHttpServer, body: String): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}/mcp")
        .post(body.toRequestBody("application/json".toMediaType()))
        .header("Accept", "application/json")
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  private val resourcesList = """{"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}"""

  private val liveCall =
    """{"jsonrpc":"2.0","id":1,"method":"tools/call",""" +
      """"params":{"name":"render_preview","arguments":{}}}"""

  @AfterTest
  fun tearDown() {
    servers.forEach { it.stop() }
    registries.forEach { it.close() }
  }

  @Test
  fun `a public box completes the handshake with no credential at all`() {
    val (code, body) = mcp(start(isPublic = true), resourcesList)
    assertEquals(200, code, body)
    // …and it is the real listing, not an empty stub standing in for one.
    assertTrue(body.contains("\"resources\""), body)
  }

  @Test
  fun `a token-gated box still refuses the same handshake`() {
    // The half that must not regress: a private box's catalogs are not published, so this listing
    // is content, and no handshake convenience buys it.
    val (code, body) = mcp(start(isPublic = false), resourcesList)
    assertEquals(401, code, body)
  }

  @Test
  fun `a public box still refuses what costs it something`() {
    // Reading what is already published is one thing; spending the machine is another. A live
    // render is above the rung anonymity satisfies, so it is refused even here.
    //
    // The refusal arrives as a JSON-RPC *tool* error rather than a transport 401, and that is the
    // shape it should have: the request was understood, authenticated as far as it goes, and
    // declined on scope. A 401 here would restart the client's whole authorization dance for a
    // call that will keep being declined until a human grants a higher rung.
    val (code, body) = mcp(start(isPublic = true), liveCall)
    assertEquals(200, code, body)
    assertTrue(body.contains("\"isError\":true"), body)
    assertTrue(body.contains("live grant scope is required"), body)
  }
}
