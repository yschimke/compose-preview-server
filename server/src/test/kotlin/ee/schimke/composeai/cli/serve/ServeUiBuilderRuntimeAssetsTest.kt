package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.UiBuilderRuntimeArtifactV1
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

  @Test
  fun `catalog archive is fully verified before staging`() {
    val source = runtimeDirectory("wear-m3-p2-revision", "export const wear = true")
    val manifest = File(source, ServeUiBuilderRuntimeAssets.RUNTIME_MANIFEST_NAME).readText()
    val integrity = Regex("\"integritySha256\":\"([a-f0-9]{64})\"").find(manifest)!!.groupValues[1]
    val descriptor =
      UiBuilderRuntimeArtifactV1(
        runtimeId = "wear-m3-p2-revision",
        protocolVersion = 1,
        integritySha256 = integrity,
      )
    val staging = Files.createTempDirectory("serve-catalog-runtime").toFile()

    ServeUiBuilderRuntimeAssets.stageArchive(descriptor, zipDirectory(source), staging)

    val asset =
      requireNotNull(
        ServeUiBuilderRuntimeAssets.assetFromDirectory(
          staging,
          descriptor.runtimeId,
          listOf("renderer.mjs"),
        )
      )
    assertEquals("export const wear = true", asset.bytes.decodeToString())
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.stageArchive(
        descriptor.copy(integritySha256 = "b".repeat(64)),
        zipDirectory(source),
        Files.createTempDirectory("serve-bad-catalog-runtime").toFile(),
      )
    }
  }

  @Test
  fun `catalog archive rejects traversal before writing`() {
    val archive =
      ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
          zip.putNextEntry(ZipEntry("../outside.mjs"))
          zip.write("escape".encodeToByteArray())
          zip.closeEntry()
        }
        output.toByteArray()
      }
    val staging = Files.createTempDirectory("serve-unsafe-catalog-runtime").toFile()

    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.stageArchive(
        UiBuilderRuntimeArtifactV1("ui-builder/runtime.zip", "wear-m3-p2-bad", 2, "a".repeat(64)),
        archive,
        staging,
      )
    }
    assertTrue(staging.walkTopDown().filter(File::isFile).none())
  }

  @Test
  fun `catalog archive rejects a protocol the bundled editor cannot speak`() {
    val source = runtimeDirectory("wear-m3-p3-future", "export const future = true")
    val manifest = File(source, ServeUiBuilderRuntimeAssets.RUNTIME_MANIFEST_NAME).readText()
    val integrity = Regex("\"integritySha256\":\"([a-f0-9]{64})\"").find(manifest)!!.groupValues[1]

    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderRuntimeAssets.stageArchive(
        UiBuilderRuntimeArtifactV1(
          runtimeId = "wear-m3-p3-future",
          protocolVersion = 3,
          integritySha256 = integrity,
        ),
        zipDirectory(source),
        Files.createTempDirectory("serve-future-catalog-runtime").toFile(),
      )
    }
  }

  @Test
  fun `runtime route serves an atomically activated catalog archive`() {
    val runtimeId = "wear-m3-p2-revision"
    val source = runtimeDirectory(runtimeId, "export const catalog = true")
    val manifest = File(source, ServeUiBuilderRuntimeAssets.RUNTIME_MANIFEST_NAME).readText()
    val integrity = Regex("\"integritySha256\":\"([a-f0-9]{64})\"").find(manifest)!!.groupValues[1]
    val descriptor =
      UiBuilderRuntimeArtifactV1(
        runtimeId = runtimeId,
        protocolVersion = 1,
        integritySha256 = integrity,
      )
    val staging = Files.createTempDirectory("serve-active-catalog-runtime").toFile()
    ServeUiBuilderRuntimeAssets.stageArchive(descriptor, zipDirectory(source), staging)
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "private-token",
          sessions = registry,
          defaultSessionId = "none",
          catalogUiBuilderRuntimeAsset = { requestedId, segments ->
            ServeUiBuilderRuntimeAssets.assetFromDirectory(staging, requestedId, segments)?.let {
              it.bytes to it.etag
            }
          },
        )
        .also { it.start() }
    try {
      OkHttpClient()
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}/ui-builder/runtime/$runtimeId/renderer.mjs")
            .build()
        )
        .execute()
        .use { response ->
          assertEquals(200, response.code)
          assertEquals("export const catalog = true", response.body.string())
          assertEquals("public, max-age=31536000, immutable", response.header("Cache-Control"))
        }
    } finally {
      server.stop()
      registry.close()
    }
  }

  @Test
  fun `tree integrity matches the shared non-ASCII contract vector`() {
    val assets =
      mapOf(
        "index.html" to "<!doctype html>".encodeToByteArray(),
        "renderer.mjs" to "export const renderer = true".encodeToByteArray(),
        "z.txt" to "z".encodeToByteArray(),
        "é.txt" to "accent".encodeToByteArray(),
      )

    assertEquals(
      "717fa7ba410f2bc0e7e28b0f7184815a02373a5f4127e0308c4636f421f54590",
      ServeUiBuilderRuntimeAssets.treeIntegrity(assets),
    )
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

  private fun zipDirectory(directory: File): ByteArray =
    ByteArrayOutputStream().use { output ->
      ZipOutputStream(output).use { zip ->
        directory
          .walkTopDown()
          .filter(File::isFile)
          .sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
          .forEach { file ->
            zip.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
            zip.write(file.readBytes())
            zip.closeEntry()
          }
      }
      output.toByteArray()
    }
}
