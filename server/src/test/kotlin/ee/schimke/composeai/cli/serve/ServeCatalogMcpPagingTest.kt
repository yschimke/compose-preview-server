package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.Base64
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Two hosted-catalog answers from the Claude Code evals on yschimke/compose-ag-plugin#64:
 * `catalog_list_previews` returned all of m3-catalog at once (2.7 M characters, refused by the
 * client), and right after a restart a configured catalog that had not loaded yet read "no such
 * catalog" while listings looked complete.
 */
class ServeCatalogMcpPagingTest {

  private val registry = ServeSessionRegistry(open = { null })
  private var pending = listOf("wear-m3")
  private val mcp =
    ServeCatalogMcp(registry, Semaphore(1), pendingCatalogs = { pending }).also {
      registry.register("m3", host = ServeBundleHost(bundle(PREVIEWS), label = "m3"), pinned = true)
    }

  @AfterTest fun tearDown() = registry.close()

  private fun bundle(count: Int): File {
    val dir = Files.createTempDirectory("paging").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    val previews = File(dir, "previews").apply { mkdirs() }
    val png = Base64.getDecoder().decode(PNG)
    repeat(count) { File(previews, "button-${"%03d".format(it)}.png").writeBytes(png) }
    File(previews, "edgebutton-large.png").writeBytes(png)
    File(previews, "edgebutton-small.png").writeBytes(png)
    return dir
  }

  private fun tool(name: String, arguments: String = "{}"): JsonObject {
    val reply = runBlocking {
      mcp.handle(
        Json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}"""
          )
          .jsonObject
      ) {
        ServeMachineAuthorization.Decision.Authorized("agent:test")
      }
    }
    return requireNotNull(reply.body)["result"]!!.jsonObject
  }

  private fun JsonObject.isError() = this["isError"]?.jsonPrimitive?.content == "true"

  private fun JsonObject.text() =
    this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content

  private fun page(arguments: String): JsonObject =
    tool("catalog_list_previews", arguments).let {
      assertFalse(it.isError(), it.toString())
      Json.parseToJsonElement(it.text()).jsonObject["catalogs"]!!.jsonArray.single().jsonObject
    }

  @Test
  fun `a large catalog comes back one page at a time`() {
    val first = page("""{"catalog":"m3"}""")
    assertEquals(PREVIEWS + 2, first["total"]!!.jsonPrimitive.int)
    assertEquals(100, first["previews"]!!.jsonArray.size)
    assertEquals(100, first["nextOffset"]!!.jsonPrimitive.int)

    val last = page("""{"catalog":"m3","offset":200,"limit":500}""")
    assertEquals(PREVIEWS + 2 - 200, last["previews"]!!.jsonArray.size)
    assertNull(last["nextOffset"])
  }

  @Test
  fun `a query narrows by id before paging`() {
    val found = page("""{"catalog":"m3","query":"EdgeButton"}""")
    assertEquals(2, found["total"]!!.jsonPrimitive.int)
    assertEquals(
      listOf("edgebutton-large", "edgebutton-small"),
      found["previews"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted(),
    )
  }

  @Test
  fun `an out-of-range limit is refused`() {
    val result = tool("catalog_list_previews", """{"catalog":"m3","limit":0}""")
    assertTrue(result.isError(), result.toString())
    assertTrue("'limit'" in result.text(), result.text())
  }

  @Test
  fun `a configured catalog that has not loaded yet says so instead of no such catalog`() {
    val result = tool("catalog_list_previews", """{"catalog":"wear-m3"}""")
    assertTrue(result.isError(), result.toString())
    assertTrue("still loading" in result.text(), result.text())

    val unknown = tool("catalog_list_previews", """{"catalog":"nope"}""")
    assertTrue("no such catalog 'nope'" in unknown.text(), unknown.text())
  }

  @Test
  fun `listings taken while catalogs load are marked incomplete`() {
    val projects = Json.parseToJsonElement(tool("catalog_list_projects").text()).jsonObject
    assertFalse(projects["complete"]!!.jsonPrimitive.boolean)
    assertEquals(
      listOf("wear-m3"),
      projects["loading"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    val status = Json.parseToJsonElement(tool("status").text()).jsonObject
    assertFalse(status["ready"]!!.jsonPrimitive.boolean)

    pending = emptyList()
    val loaded = Json.parseToJsonElement(tool("catalog_list_projects").text()).jsonObject
    assertNull(loaded["complete"])
    assertNull(loaded["loading"])
    assertTrue(
      Json.parseToJsonElement(tool("status").text()).jsonObject["ready"]!!.jsonPrimitive.boolean
    )
  }

  private companion object {
    const val PREVIEWS = 250
    const val PNG =
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
  }
}
