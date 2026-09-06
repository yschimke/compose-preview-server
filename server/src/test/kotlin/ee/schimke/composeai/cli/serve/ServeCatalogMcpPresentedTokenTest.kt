package ee.schimke.composeai.cli.serve

import java.util.concurrent.Semaphore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The credential an MCP client cannot put on its own transport.
 *
 * `request_access` and `poll_access` let an agent holding nothing obtain a grant without leaving
 * the protocol, and then stranded it: a client fixes its request headers when it connects, so a
 * token that arrives *as a tool result* has nowhere to go. Every gated tool went on refusing an
 * agent that was, by then, holding a token a human had just approved.
 *
 * So a gated tool takes the token as an argument. What is tested here is the part that is easy to
 * get quietly wrong: that a model can see the argument (it is in the schema, or it will never be
 * sent), that the two access tools do not offer it (there is no token to carry yet), and that the
 * credential is consumed by the authorization rather than handed on to the tool as an input.
 */
class ServeCatalogMcpPresentedTokenTest {

  private val mcp = ServeCatalogMcp(ServeSessionRegistry(open = { null }), Semaphore(1))

  // ------------------------------------------------------------------ parsing

  @Test
  fun `a token argument is read off a tool call`() {
    assertEquals(
      "cpat_abc",
      presented("""{"name":"list_projects","arguments":{"token":"cpat_abc"}}"""),
    )
  }

  /**
   * A client templating an unset environment variable sends `""`. That is nothing presented, not a
   * bad credential — and the difference decides whether an anonymous read on a public box works.
   */
  @Test
  fun `blank and absent are both nothing presented`() {
    assertNull(presented("""{"name":"list_projects","arguments":{"token":"  "}}"""))
    assertNull(presented("""{"name":"list_projects","arguments":{}}"""))
    assertNull(presented("""{"name":"list_projects"}"""))
  }

  @Test
  fun `a message with no params presents nothing`() {
    assertNull(ServeCatalogMcp.presentedToken(json("""{"jsonrpc":"2.0","id":1,"method":"ping"}""")))
  }

  private fun presented(params: String): String? =
    ServeCatalogMcp.presentedToken(
      json("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":$params}""")
    )

  // ------------------------------------------------------------------ schema

  @Test
  fun `every gated tool declares the token argument`() {
    val undeclared =
      tools().filter {
        it.name !in ACCESS_TOOLS && ServeCatalogMcp.TOKEN_ARGUMENT !in it.properties
      }
    assertTrue(
      undeclared.isEmpty(),
      "gated tools without a token argument: ${undeclared.map { it.name }}",
    )
  }

  /**
   * The two tools a caller reaches *without* a credential do not offer to carry one: at that point
   * there is no token to pass, and an argument that can only be filled in wrongly is a trap.
   */
  @Test
  fun `the access tools do not offer to carry a token`() {
    val access = tools().filter { it.name in ACCESS_TOOLS }
    assertEquals(ACCESS_TOOLS, access.map { it.name }.toSet())
    access.forEach { assertTrue(ServeCatalogMcp.TOKEN_ARGUMENT !in it.properties, it.name) }
  }

  private data class Tool(val name: String, val properties: Set<String>)

  /**
   * The access tools are listed only where the grant flow exists, so this stub makes them exist.
   */
  private val access =
    object : ServeCatalogMcp.AgentAccess {
      override suspend fun open(
        label: String,
        scope: String,
        ttlSeconds: Long,
        capabilities: List<String>,
      ): String = "{}"

      override suspend fun poll(
        requestId: String,
        deviceSecret: String,
        waitSeconds: Long,
      ): String = "{}"
    }

  private fun tools(): List<Tool> =
    runBlocking {
        mcp.handle(json("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), access = access) {
          ServeMachineAuthorization.Decision.Missing
        }
      }
      .body!!["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { tool ->
        Tool(
          name = tool.jsonObject["name"]!!.jsonPrimitive.content,
          properties =
            tool.jsonObject["inputSchema"]!!.jsonObject["properties"]?.jsonObject?.keys.orEmpty(),
        )
      }

  // ------------------------------------------------------------------ dispatch

  /**
   * The token authorizes the call and is not an input to it. A tool that forwarded its arguments
   * onward — the UI-builder door forwards all of them — would otherwise forward a live credential
   * with them, into state a design outlives.
   */
  @Test
  fun `the token is stripped before the tool sees its arguments`() {
    val reply = runBlocking {
      mcp.handle(
        json(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_previews",
             "arguments":{"catalog":"nope","token":"cpat_secret"}}}"""
        )
      ) {
        ServeMachineAuthorization.Decision.Authorized("agent:test")
      }
    }
    assertTrue("cpat_secret" !in reply.body.toString(), reply.body.toString())
  }

  /** What the tool is authorized against is what the message presented. */
  @Test
  fun `a live check is asked about the presented token`() {
    var asked: String? = "not asked"
    runBlocking {
      mcp.handle(
        json(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"render_preview",
             "arguments":{"catalog":"m3","previewId":"card","token":"cpat_abc"}}}"""
        )
      ) { token ->
        asked = token
        ServeMachineAuthorization.Decision.Missing
      }
    }
    assertEquals("cpat_abc", asked)
  }

  private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

  private companion object {
    val ACCESS_TOOLS = setOf("request_access", "poll_access")
  }
}
