package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

class ServeUiBuilderRuntimeAssetsTest {
  @Test
  fun `exact runtime route serves a startup snapshot with immutable caching`() {
    val directory = runtimeDirectory("m3-2026.09", "window.renderer = 'retained'")
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "private-token",
          sessions = registry,
          defaultSessionId = "none",
          uiBuilderRuntimeDirs = mapOf("m3-2026.09" to directory),
        )
        .also { it.start() }
    File(directory, "renderer.mjs").writeText("window.renderer = 'changed-after-start'")
    val client = OkHttpClient()
    fun get(path: String, etag: String? = null) =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}$path")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        )
        .execute()
    try {
      get("/ui-builder/runtime/m3-2026.09/renderer.mjs").use { response ->
        assertEquals(200, response.code)
        assertEquals("window.renderer = 'retained'", response.body.string())
        assertEquals("text/javascript", response.header("Content-Type"))
        assertEquals("*", response.header("Access-Control-Allow-Origin"))
        assertEquals(
          "public, max-age=31536000, immutable",
          response.header("Cache-Control"),
        )
        val etag = requireNotNull(response.header("ETag"))
        get("/ui-builder/runtime/m3-2026.09/renderer.mjs", etag).use { cached ->
          assertEquals(304, cached.code)
        }
      }
      get("/ui-builder/runtime/m3-2026.09/").use { response ->
        assertEquals(200, response.code)
        assertTrue(response.body.string().contains("\"protocolVersion\":1"))
      }
      get("/ui-builder/runtime/latest/renderer.mjs").use { assertEquals(404, it.code) }
      get("/ui-builder/runtime/missing/renderer.mjs").use { assertEquals(404, it.code) }
      get("/ui-builder/runtime/m3-2026.09/../renderer.mjs").use { assertEquals(404, it.code) }
    } finally {
      server.stop()
      registry.close()
    }
  }

  @Test
  fun `runtime inputs fail closed on integrity identity and links`() {
    val badDigest = runtimeDirectory("runtime-one", "renderer")
    File(badDigest, "renderer.mjs").writeText("tampered")
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.load(mapOf("runtime-one" to badDigest))
    }

    val wrongIdentity = runtimeDirectory("runtime-one", "renderer")
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.load(mapOf("runtime-two" to wrongIdentity))
    }

    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.load(mapOf("latest" to wrongIdentity))
    }

    val safe = ServeUiBuilderRuntimeAssets.load(mapOf("runtime-one" to wrongIdentity))
    assertNull(safe.asset("runtime-one", listOf("..", "renderer.mjs")))
    assertNull(safe.asset("runtime-one", listOf("nested\\renderer.mjs")))

    val linked = runtimeDirectory("runtime-linked", "renderer")
    val outside = Files.createTempFile("ui-builder-runtime-outside", ".mjs")
    Files.createSymbolicLink(linked.toPath().resolve("linked.mjs"), outside)
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.load(mapOf("runtime-linked" to linked))
    }
  }

  private fun runtimeDirectory(runtimeId: String, renderer: String): File {
    val directory =
      Files.createTempDirectory("serve-ui-builder-runtime").toFile().also { it.deleteOnExit() }
    val assets =
      mapOf(
        "index.html" to
          "<!doctype html><script type=module src=renderer.mjs></script>".encodeToByteArray(),
        "renderer.mjs" to renderer.encodeToByteArray(),
      )
    assets.forEach { (path, bytes) -> File(directory, path).writeBytes(bytes) }
    val integrity = ServeUiBuilderRuntimeAssets.treeIntegrity(assets)
    File(directory, ServeUiBuilderRuntimeAssets.RUNTIME_MANIFEST_NAME)
      .writeText(
        """{"schema":"${ServeUiBuilderRuntimeAssets.MANIFEST_SCHEMA}","runtimeId":"$runtimeId","protocolVersion":1,"entrypoint":"index.html","integritySha256":"$integrity"}"""
      )
    return directory
  }
}
