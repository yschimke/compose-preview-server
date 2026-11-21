package ee.schimke.composeai.cli.serve

import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class LiveryHostMainTest {

  @Test
  fun `fromEnvironment returns null when publications dir is unset`() {
    assertNull(ServeComponentPublications.fromEnvironment(emptyMap()))
  }

  @Test
  fun `standalone server serves healthz, readyz and publication routes`() {
    val dir = Files.createTempDirectory("livery-host-test")
    val publications =
      ServeComponentPublications(
        root = dir,
        viewerRoot = null,
        publicOrigin = "https://preview.example.com",
      )
    val server = LiveryHostServer.create(publications, host = "127.0.0.1", port = 0)
    server.start(wait = false)
    try {
      val port = runBlocking { server.engine.resolvedConnectors().first().port }

      val health = get("http://127.0.0.1:$port/healthz")
      assertEquals(200, health.first)
      assertEquals("ok\n", health.second)

      val ready = get("http://127.0.0.1:$port/readyz")
      assertEquals(200, ready.first)
      assertEquals("ok\n", ready.second)

      val missing = get("http://127.0.0.1:$port/p/missing/r/rev1/")
      assertEquals(404, missing.first)
    } finally {
      server.stop(100, 500)
      dir.toFile().deleteRecursively()
    }
  }

  private fun get(url: String): Pair<Int, String> {
    val conn = URI(url).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    val status = conn.responseCode
    val stream = if (status < 400) conn.inputStream else conn.errorStream
    val body = stream?.bufferedReader()?.readText().orEmpty()
    conn.disconnect()
    return status to body
  }
}
