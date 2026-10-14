package ee.schimke.composeai.cli.serve

import java.util.concurrent.Semaphore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ui.coo.ee` and `preview.coo.ee` are one server (yschimke/skills#156): `status` and the
 * handshake's instructions name the canonical origin designs are homed at and the other hostnames
 * that reach the same server, so an agent shown both does not take them for two hosts.
 */
class ServeCatalogMcpOriginTest {
  private val registry = ServeSessionRegistry(open = { null })

  @AfterTest fun tearDown() = registry.close()

  private fun mcp(origin: String?, aliases: List<String> = emptyList()) =
    ServeCatalogMcp(
      registry,
      Semaphore(1),
      publicOrigin = { origin },
      publicOriginAliases = { aliases },
    )

  private fun ServeCatalogMcp.rpc(method: String, params: String = "{}"): JsonObject {
    val reply = runBlocking {
      handle(
        Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
          .jsonObject
      ) {
        ServeMachineAuthorization.Decision.Authorized("agent:test")
      }
    }
    return requireNotNull(reply.body)["result"]!!.jsonObject
  }

  private fun ServeCatalogMcp.status(): JsonObject =
    Json.parseToJsonElement(
        rpc("tools/call", """{"name":"status","arguments":{}}""")["content"]!!
          .jsonArray
          .first()
          .jsonObject["text"]!!
          .jsonPrimitive
          .content
      )
      .jsonObject

  private fun ServeCatalogMcp.instructions(): String =
    rpc("initialize", """{"protocolVersion":"2025-06-18","capabilities":{}}""")["instructions"]!!
      .jsonPrimitive
      .content

  @Test
  fun `status names the canonical origin and its aliases, once each`() {
    val status =
      mcp(
          "https://preview.coo.ee",
          listOf("https://ui.coo.ee", "https://UI.coo.ee/", "https://preview.coo.ee"),
        )
        .status()

    assertEquals("https://preview.coo.ee", status["origin"]!!.jsonPrimitive.content)
    assertEquals(
      listOf("https://ui.coo.ee"),
      status["originAliases"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
  }

  @Test
  fun `the instructions say the aliases are the same server`() {
    val instructions = mcp("https://preview.coo.ee", listOf("https://ui.coo.ee")).instructions()

    assertTrue(
      "canonical origin is https://preview.coo.ee; https://ui.coo.ee is the same server" in
        instructions,
      instructions,
    )
  }

  @Test
  fun `a host with no public origin claims none`() {
    val bare = mcp(null, listOf("https://ui.coo.ee"))

    val status = bare.status()
    assertNull(status["origin"])
    assertNull(status["originAliases"])
    assertFalse("canonical origin" in bare.instructions())

    val alone = mcp("https://preview.coo.ee").status()
    assertEquals("https://preview.coo.ee", alone["origin"]!!.jsonPrimitive.content)
    assertNull(alone["originAliases"])
  }
}
