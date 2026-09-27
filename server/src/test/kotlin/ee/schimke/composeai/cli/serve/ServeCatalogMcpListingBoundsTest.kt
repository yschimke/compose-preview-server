package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * #1162: an agent called the hosted `list_previews` with `{}` and waited out its client's three
 * minute timeout, because the call resumed and serialised every catalog in turn.
 *
 * Every catalog here is registered suspended, and resuming one takes longer than the whole bound
 * these tests allow, so any call that still wakes a catalog fails on time rather than passing
 * slowly.
 */
class ServeCatalogMcpListingBoundsTest {

  private val opened = AtomicInteger()

  private val registry =
    ServeSessionRegistry(
      open = {
        opened.incrementAndGet()
        Thread.sleep(RESUME_MILLIS)
        null
      }
    )

  private val mcp =
    ServeCatalogMcp(registry, Semaphore(1)).also {
      CATALOGS.forEach { id -> registry.register(id, stateFor(id)) }
    }

  private fun stateFor(id: String): ServeSessionState =
    ServeSessionState(
      descriptor = File("daemon-launch.json"),
      workspaceRoot =
        Files.createTempDirectory("catalog-mcp-bounds").toFile().also { it.deleteOnExit() },
      workspaceName = id,
      previews = listOf(ServePreview("$id-a", "A"), ServePreview("$id-b", "B")),
      label = "$id label",
    )

  private fun request(method: String, params: String): JsonObject {
    val started = System.nanoTime()
    val reply = runBlocking {
      mcp.handle(
        Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""")
          .jsonObject
      ) {
        ServeMachineAuthorization.Decision.Authorized("agent:test")
      }
    }
    val elapsedMillis = (System.nanoTime() - started) / 1_000_000
    assertTrue(elapsedMillis < BOUND_MILLIS, "$method $params took ${elapsedMillis}ms")
    assertEquals(0, opened.get(), "$method $params resumed a catalog")
    return requireNotNull(reply.body)
  }

  private fun tool(name: String, arguments: String = "{}"): JsonObject =
    request("tools/call", """{"name":"$name","arguments":$arguments}""")["result"]!!.jsonObject

  private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"

  private fun JsonObject.text() =
    this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

  @Test
  fun `list_previews without a catalog refuses at once, naming the catalogs and the local server`() {
    val result = tool("list_previews")

    assertTrue(result.isError(), result.toString())
    val text = result.text()
    assertTrue("'catalog'" in text, text)
    CATALOGS.forEach { assertTrue(it in text, "missing $it: $text") }
    assertTrue("list_projects" in text, text)
    assertTrue("compose-preview-mcp" in text, text)
  }

  @Test
  fun `list_previews with an unknown catalog refuses at once`() {
    val result = tool("list_previews", """{"catalog":"nope"}""")

    assertTrue(result.isError(), result.toString())
    assertTrue("no such catalog 'nope'" in result.text(), result.text())
  }

  @Test
  fun `list_data_products without a catalog refuses at once`() {
    val result = tool("list_data_products")

    assertTrue(result.isError(), result.toString())
    assertTrue("compose-preview-mcp" in result.text(), result.text())
  }

  @Test
  fun `list_projects reports every catalog without resuming any`() {
    val result = tool("list_projects")

    assertFalse(result.isError(), result.toString())
    val projects = Json.parseToJsonElement(result.text()).jsonObject["projects"]!!.jsonArray
    assertEquals(CATALOGS, projects.map { it.jsonObject["catalog"]!!.jsonPrimitive.content })
    projects.forEach {
      assertEquals(2, it.jsonObject["previewCount"]!!.jsonPrimitive.int)
      assertTrue(it.jsonObject["label"]!!.jsonPrimitive.content.endsWith(" label"))
    }
  }

  @Test
  fun `status reports every catalog without resuming any`() {
    val result = tool("status")

    assertFalse(result.isError(), result.toString())
    val projects = Json.parseToJsonElement(result.text()).jsonObject["projects"]!!.jsonArray
    assertEquals(CATALOGS.size, projects.size)
  }

  @Test
  fun `list-all-documentation lists every catalog's stories without resuming any`() {
    val result = tool("list-all-documentation")

    assertFalse(result.isError(), result.toString())
    assertEquals(
      CATALOGS.size * 2,
      Json.parseToJsonElement(result.text()).jsonObject["count"]!!.jsonPrimitive.int,
    )
  }

  @Test
  fun `resources list names every preview without resuming any`() {
    val resources = request("resources/list", "{}")["result"]!!.jsonObject["resources"]!!.jsonArray

    val uris = resources.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
    CATALOGS.forEach { id -> assertTrue(uris.any { "/$id/" in it }, "missing $id: $uris") }
  }

  private companion object {
    val CATALOGS = listOf("compose-m3", "m3-catalog", "wear-m3")
    const val RESUME_MILLIS = 3_000L
    const val BOUND_MILLIS = 2_000L
  }
}
