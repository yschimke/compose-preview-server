package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Which MCP messages may be sent without a grant.
 *
 * The rule lives beside the tools rather than in the transport, and it is asked per MESSAGE rather
 * than per endpoint — which is the whole change: the gate used to stand in front of `/mcp`, so a
 * client with no credential could not complete `initialize`, and therefore could not reach the tool
 * that asks a human for one. Tested here rather than only over HTTP because the failure that
 * matters is a name quietly falling on the open side of the list.
 */
class ServeCatalogMcpGateTest {

  private fun gated(body: String): Boolean =
    ServeCatalogMcp.requiresGrant(Json.parseToJsonElement(body).jsonObject)

  @Test
  fun `discovery is open`() {
    assertFalse(gated("""{"jsonrpc":"2.0","id":1,"method":"initialize"}"""))
    assertFalse(gated("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
    assertFalse(gated("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
  }

  @Test
  fun `the two tools that obtain a credential are open`() {
    assertFalse(
      gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"request_access"}}""")
    )
    assertFalse(
      gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"poll_access"}}""")
    )
  }

  @Test
  fun `everything that reads a catalog is gated`() {
    assertTrue(gated("""{"jsonrpc":"2.0","id":1,"method":"resources/list"}"""))
    assertTrue(gated("""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"x"}}"""))
    assertTrue(
      gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_projects"}}""")
    )
    assertTrue(
      gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"render_preview"}}""")
    )
  }

  /**
   * Fail closed on anything this version does not recognise. A tool added later must be gated until
   * someone deliberately opens it — the opposite default would make "we forgot to update the list"
   * into an authentication bypass.
   */
  @Test
  fun `an unrecognised method or tool is gated`() {
    assertTrue(gated("""{"jsonrpc":"2.0","id":1,"method":"resources/subscribe"}"""))
    assertTrue(gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"new"}}"""))
    // A malformed `tools/call` — no params, or no name — is gated rather than dispatched openly.
    assertTrue(gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call"}"""))
    assertTrue(gated("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{}}"""))
    assertTrue(gated("""{"jsonrpc":"2.0","id":1}"""))
  }

  /**
   * A notification carries no id and is accepted-and-dropped without being handled, so gating it
   * would answer 401 to a message the server never reads. It cannot return data either way.
   */
  @Test
  fun `a notification is not gated`() {
    assertFalse(gated("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
  }
}
