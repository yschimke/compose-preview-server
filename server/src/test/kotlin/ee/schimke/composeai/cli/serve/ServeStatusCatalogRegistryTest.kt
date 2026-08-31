package ee.schimke.composeai.cli.serve

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `--catalog-registry` must be visible on the status surface.
 *
 * The gap this covers: the nomination decides whether a whole project's catalogs are served, and it
 * appeared in no diagnostic the server offered. A box running WITHOUT the flag and a box whose
 * registry document was unreachable produced byte-identical `/status.json` — both simply missing
 * the catalogs — so "is the flag even set?" could only be answered with shell access to the host,
 * by grepping the boot log. That cost real time on preview.coo.ee, where three imported catalogs
 * were absent and there was no way to tell which of the two causes it was.
 */
class ServeStatusCatalogRegistryTest {

  private val client = OkHttpClient()
  private var server: ServeHttpServer? = null

  @AfterTest fun tearDown() = server?.stop().let {}

  private fun start(registries: List<CatalogRegistryStatus>): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = ServeSessionRegistry(open = { null }),
        defaultSessionId = "",
        isPublic = true,
        catalogRegistries = registries,
      )
      .also {
        it.start()
        server = it
      }

  private fun statusJson(s: ServeHttpServer): String =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${s.port}/status.json").build())
      .execute()
      .use { it.body!!.string() }

  private fun statusPage(s: ServeHttpServer): String =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${s.port}/status").build())
      .execute()
      .use { it.body!!.string() }

  @Test
  fun `a nomination that contributed catalogs is reported with its systems`() {
    val s =
      start(
        listOf(
          CatalogRegistryStatus(
            repo = "yschimke/compose-preview-imports",
            catalogs = 2,
            systems = listOf("joreilly-peopleinspace", "joreilly-bikeshare"),
          )
        )
      )

    val json = statusJson(s)
    assertTrue(json.contains("yschimke/compose-preview-imports"), json)
    // The system ids matter as much as the count: they say WHICH catalogs the registry is
    // responsible for, which is what an operator needs to attribute a missing one.
    assertTrue(json.contains("joreilly-peopleinspace"), json)
    assertTrue(json.contains("joreilly-bikeshare"), json)

    assertTrue(statusPage(s).contains("Catalog registry"), "status page has no registry row")
  }

  @Test
  fun `no nomination reads as none rather than as absent`() {
    val s = start(emptyList())

    // The distinction the whole change exists for. "none" is an answer; a missing field is not.
    assertTrue(statusPage(s).contains("none"), "status page should say the registry is 'none'")
    assertEquals(
      true,
      statusJson(s).contains("catalogRegistries"),
      "field missing from status.json",
    )
  }

  @Test
  fun `an unreadable nomination is distinguishable from one that contributed nothing`() {
    val s =
      start(
        listOf(
          CatalogRegistryStatus(
            repo = "yschimke/compose-preview-imports",
            catalogs = 0,
            error = "catalog registry yschimke/compose-preview-imports: document not found",
          )
        )
      )

    // Nominated-but-broken and nominated-but-empty both contribute 0 catalogs and need opposite
    // fixes, so the error string has to survive to the status surface.
    assertTrue(statusJson(s).contains("document not found"), "error dropped from status.json")
    assertTrue(statusPage(s).contains("unreadable"), "status page does not flag the failure")
  }
}
