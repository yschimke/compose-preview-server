package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServeUidReferenceTest {
  @Test
  fun `HTTP snapshot verifies bytes and refuses stale design links`() {
    val dir = Files.createTempDirectory("uid-route").toFile()
    val registry = ServeSessionRegistry(open = { null })
    val png =
      ByteArrayOutputStream()
        .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
        .toByteArray()
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").mkdirs()
    File(dir, "previews/phone.png").writeBytes(png)
    File(dir, "references").mkdirs()
    File(dir, "references/phone.png").writeBytes(png)
    File(dir, "references/phone.uid").writeBytes(document)
    File(dir, "references/index.json")
      .writeText(Json.encodeToString(DesignReferenceManifest(references = listOf(reference))))
    registry.register("app", host = ServeBundleHost(dir, label = "app"), pinned = true)
    val server =
      ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "test",
        sessions = registry,
        defaultSessionId = "app",
        isPublic = true,
      )
    val client = okhttp3.OkHttpClient()
    fun get(path: String): Pair<Int, String> =
      client
        .newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.port}$path").build())
        .execute()
        .use { it.code to it.body.string() }
    try {
      server.start()
      assertEquals(
        200,
        get(
            "/app/reference/phone.html?sha=${reference.source.attributes.getValue("documentSha256")}"
          )
          .first,
      )
      assertEquals(409, get("/app/reference/phone.html").first)
      assertEquals(document.decodeToString(), get("/app/reference/phone.uid").second)
      assertEquals(409, get("/app/reference/phone.html?sha=changed").first)
      assertEquals(409, get("/app/reference/phone.html?at=${"a".repeat(40)}").first)
      assertEquals(404, get("/app/reference/missing.uid").first)
      File(dir, "references/phone.uid").appendText(" ")
      assertEquals(404, get("/app/reference/phone.uid").first)
    } finally {
      server.stop()
      registry.close()
      dir.deleteRecursively()
    }
  }

  private val document =
    """{"schema":"compose-ui-builder-document/v1","id":"inbox","catalogPin":{"systemId":"m3-catalog"},"title":"</script><script>alert(1)</script>"}"""
      .toByteArray()
  private val reference =
    DesignReference(
      id = "phone",
      previewId = "phone",
      raster = DesignReferenceRaster("references/phone.png"),
      artifact = DesignReferenceArtifact("uid", "references/phone.uid"),
      source =
        DesignReferenceSource(
          provider = "ui-builder",
          attributes =
            mapOf(
              "designId" to "inbox",
              "documentSha256" to
                MessageDigest.getInstance("SHA-256").digest(document).joinToString("") {
                  "%02x".format(it)
                },
            ),
        ),
    )

  @Test
  fun `uploaded bundles retain inert reference snapshots`() {
    val dir = Files.createTempDirectory("uid-upload").toFile()
    var host: ServeBundleHost? = null
    val png =
      ByteArrayOutputStream()
        .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
        .toByteArray()
    try {
      val store = ServeBundleStore(dir, register = { _, value -> host = value })
      val result =
        store.add(
          "app",
          ServeBundle.zip(
            linkedMapOf(
              "previews/phone.png" to png,
              "references/phone.png" to png,
              "references/phone.uid" to document,
              "references/index.json" to
                Json.encodeToString(DesignReferenceManifest(references = listOf(reference)))
                  .toByteArray(),
              "references/unsafe.html" to "<script>alert(1)</script>".toByteArray(),
            )
          ),
          isSecurityChecked = true,
        )
      assertTrue(result is ServeBundleStore.Result.Ok)
      assertEquals("phone", host!!.uidReference("phone")!!.first.id)
      assertFalse(File(dir, "app/references/unsafe.html").exists())
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `the reference fingerprint binds the design bytes`() {
    assertTrue(ServeUidReference.valid(reference, document))
    assertFalse(ServeUidReference.valid(reference, document + byteArrayOf(32)))
    assertFalse(
      ServeUidReference.valid(
        reference.copy(artifact = DesignReferenceArtifact("uid", "../secret.uid")),
        document,
      )
    )
  }

  @Test
  fun `snapshot page embeds inert JSON and links back to its exact preview`() {
    val html = ServeUidReference.page(reference, document, "/app")
    assertFalse(html.contains("</script><script>alert"))
    assertTrue(html.contains("/app/compare/phone?reference=phone"))
    assertTrue(html.contains("edits stay in this tab"))
    assertTrue(ServeUidReference.spec(listOf(reference), "/app")!!.url.contains("?sha="))
  }
}
