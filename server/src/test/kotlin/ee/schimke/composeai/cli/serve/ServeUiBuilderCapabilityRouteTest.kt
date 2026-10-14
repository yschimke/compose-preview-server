package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogListing
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

class ServeUiBuilderCapabilityRouteTest {
  private val registry = ServeSessionRegistry(open = { null })
  @Volatile private var current = listOf(capability("wear-m3"))
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderCatalogs = setOf("wear-m3", "remote-m3"),
        uiBuilderCapabilities = { current },
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  @Test
  fun `sandboxed clients read the current published capability`() {
    request("wear-m3").use {
      assertEquals(200, it.code)
      assertEquals("*", it.header("Access-Control-Allow-Origin"))
      assertTrue(it.body.string().contains("\"wear-m3\""))
    }
    // Unlisted catalogs stay in the executor's list with a marker; only chooser readers filter it.
    current = listOf(CatalogListing.markUnlisted(capability("remote-m3")))
    request("wear-m3").use {
      assertEquals(404, it.code)
      assertEquals("*", it.header("Access-Control-Allow-Origin"))
    }
    request("remote-m3").use {
      assertEquals(200, it.code)
      assertTrue(it.body.string().contains("\"remote-m3\""))
    }
  }

  private fun request(system: String) =
    client
      .newCall(
        Request.Builder()
          .url(
            "http://127.0.0.1:${server.port}/api/ui-builder/v1/catalog-capabilities/" +
              "$system-capabilities-v1.json"
          )
          .header("Origin", "https://sandbox.example")
          .build()
      )
      .execute()

  private fun capability(system: String) =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(system, "source", system, "candidate", "candidate").build(),
        emptyList(),
      )
      .build()
}
