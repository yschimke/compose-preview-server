package ee.schimke.composeai.cli.serve

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A server without `/mcp` is told apart from a design that does not exist.
 *
 * A stock `ui` server mounts no `/mcp`, and `design get` against it used to fail with "answered
 * HTTP 404 — Not Found", which reads as a missing design (compose-ui-builder#492). The refusal now
 * names the flags that mount the endpoint and the REST route that needs none of them.
 */
class DesignHttpTransportTest {
  private val server =
    HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
      createContext("/") { exchange ->
        exchange.sendResponseHeaders(404, -1)
        exchange.close()
      }
      start()
    }

  @AfterTest fun stop() = server.stop(0)

  private val transport =
    DesignHttpTransport(
      "http://127.0.0.1:${server.address.port}",
      token = { "operator-token" },
      timeout = Duration.ofSeconds(5),
    )

  @Test
  fun `a 404 from mcp names the flags that mount it and the REST read`() {
    val failure =
      assertFailsWith<DesignCommandFailure> {
        transport.call(ServeUiBuilderMcp.GET_DESIGN, buildJsonObject { put("designId", "d") })
      }
    val message = failure.message.orEmpty()
    assertTrue("has no MCP endpoint" in message, message)
    assertTrue("--agent-grants --catalog-mcp" in message, message)
    assertTrue("ui-builder-read,ui-builder-write,ui-builder-export" in message, message)
    assertTrue(
      "GET http://127.0.0.1:${server.address.port}/api/ui-builder/v1/designs/<designId>" in message,
      message,
    )
    assertFalse("Not Found" in message, message)
  }

  @Test
  fun `other tools are pointed at the export routes instead`() {
    val failure =
      assertFailsWith<DesignCommandFailure> {
        transport.call(ServeUiBuilderMcp.LIST_DESIGNS, buildJsonObject {})
      }
    val message = failure.message.orEmpty()
    assertTrue("has no MCP endpoint" in message, message)
    assertTrue("/export.png" in message, message)
  }

  @Test
  fun `a catalog tool is told about catalog MCP, not UI-builder capabilities`() {
    // `a2ui render` shares this transport and calls `catalog_render_preview`, which needs a
    // grant's live scope; the design routes and ui-builder-* capabilities are no help to it.
    val failure =
      assertFailsWith<DesignCommandFailure> {
        transport.call("catalog_render_preview", buildJsonObject {})
      }
    val message = failure.message.orEmpty()
    assertTrue("has no MCP endpoint" in message, message)
    assertTrue("--agent-grants --catalog-mcp" in message, message)
    assertTrue("live" in message, message)
    assertFalse("ui-builder-read" in message, message)
    assertFalse("/api/ui-builder" in message, message)
  }
}
