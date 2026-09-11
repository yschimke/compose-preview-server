package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.uibuilder.protocol.*
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test

class UiBuilderSuppliedDocumentTest {
  @Test
  fun `real HTTP transport sends unsaved content and preserves all compiler diagnostics`() {
    assumeTrue(formats.isNotEmpty())
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val received = mutableListOf<String>()
    var expected = artifact(formats.first())
    server.createContext("/prefix/api/ui-builder/v1/documents/") { call ->
      received += call.requestURI.toString()
      assertThat(call.requestMethod).isEqualTo("POST")
      assertThat(call.requestHeaders.getFirst("Authorization")).isEqualTo("Bearer test-token")
      assertThat(
          json.decodeFromString<DesignDocumentV1>(call.requestBody.readAllBytes().decodeToString())
        )
        .isEqualTo(document)
      call.responseHeaders.add("Content-Type", "application/json")
      call.responseHeaders.add("ETag", "\"${expected.contentDigest}\"")
      call.responseHeaders.add("X-UI-Builder-Revision", document.revision.toString())
      val bytes = json.encodeToString(expected).toByteArray()
      call.sendResponseHeaders(200, bytes.size.toLong())
      call.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val adapter =
        UiBuilderMcpAdapter(
          UiBuilderDesignApiClient.remote(
            "http://127.0.0.1:${server.address.port}/prefix",
            "test-token",
          )
        )
      for (format in formats) {
        for (refused in listOf(false, true)) {
          expected = artifact(format, refused)
          val result = requireNotNull(adapter.handle("export_document", args(format)))
          assertThat(result.isError).isEqualTo(refused)
          val actual =
            json.decodeFromString<ExportArtifactV1>(
              (result.content.single() as ContentBlock.Text).text
            )
          assertThat(actual).isEqualTo(expected)
          assertThat(received.last())
            .isEqualTo(
              "/prefix/api/ui-builder/v1/documents/export.${format.name.lowercase()}?artifact=true"
            )
        }
      }
      assertThat(received).hasSize(formats.size * 2)
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `rejects corrupt mismatched and unauthenticated export responses`() {
    assumeTrue(formats.isNotEmpty())
    val format = formats.first()
    val valid = artifact(format)
    fun response(
      value: ExportArtifactV1 = valid,
      revision: String = document.revision.toString(),
      etag: String = "\"${value.contentDigest}\"",
    ) = UiBuilderHttpResponse(200, json.encodeToString(value), etag, revision)
    val responses =
      listOf(
        response(valid.copy(contentDigest = "bad")),
        response(etag = "bad"),
        response(revision = "999"),
        response(valid.copy(format = ExportFormatV1.PNG)),
        UiBuilderHttpResponse(200, "not an artifact"),
        UiBuilderHttpResponse(403, "private-error-body"),
      )
    for (reply in responses) {
      val transport =
        object : UiBuilderHttpTransport {
          override fun post(body: String): UiBuilderHttpResponse =
            error("must not save or look up a design")

          override fun exportDocument(body: String, format: ExportFormatV1) = reply
        }
      val adapter = UiBuilderMcpAdapter(UiBuilderDesignApiClient("test-actor", transport))
      val result = requireNotNull(adapter.handle("export_document", args(format)))
      assertThat(result.isError).isTrue()
      assertThat(result.content.toString()).doesNotContain("private-error-body")
    }
  }

  @Test
  fun `invalid supplied document and unsupported format never reach transport`() {
    assumeTrue(formats.isNotEmpty())
    val transport = UiBuilderHttpTransport { error("must not reach transport") }
    val adapter = UiBuilderMcpAdapter(UiBuilderDesignApiClient("test-actor", transport))
    val invalid =
      listOf(
        buildJsonObject {
          put("document", JsonObject(emptyMap()))
          put("format", "rc")
        },
        JsonObject(args(formats.first()) + ("format" to JsonPrimitive("svg"))),
      )
    for (arguments in invalid) {
      assertThat(requireNotNull(adapter.handle("export_document", arguments)).isError).isTrue()
    }
  }

  /** Opt-in proof against the real server distribution, not the stub HTTP endpoint above. */
  @Test
  fun `standalone adapter matches actual server JSON and RC without saving`() {
    val url = System.getenv("VERIFY_SUPPLIED_DOCUMENT_SERVER")
    assumeTrue(url != null && formats.isNotEmpty())
    val token = requireNotNull(System.getenv("UI_BUILDER_TEST_TOKEN"))
    val client = UiBuilderDesignApiClient.remote(url!!, token)
    val adapter = UiBuilderMcpAdapter(client)
    for (format in formats.filter { it != ExportFormatV1.PNG }) {
      val result = requireNotNull(adapter.handle("export_document", args(format)))
      assertThat(result.isError).isFalse()
      val actual =
        json.decodeFromString<ExportArtifactV1>((result.content.single() as ContentBlock.Text).text)
      val expected = Files.readAllBytes(evidence.resolve("local.${format.name.lowercase()}"))
      val bytes =
        if (actual.encoding == ExportEncodingV1.BASE64) Base64.getDecoder().decode(actual.content)
        else actual.content.toByteArray()
      assertThat(bytes).isEqualTo(expected)
    }
    val missing =
      assertThrows(UiBuilderApiException::class.java) {
        client.execute(OpenDesignRequestV1(document.id))
      }
    assertThat(missing).hasMessageThat().isEqualTo("Design API returned HTTP 404")
  }

  private fun args(format: ExportFormatV1) = buildJsonObject {
    put("document", json.encodeToJsonElement(document))
    put("format", format.name.lowercase())
  }

  private fun artifact(format: ExportFormatV1, refused: Boolean = false): ExportArtifactV1 {
    val bytes =
      if (refused) byteArrayOf()
      else if (format.name == "RC" || format == ExportFormatV1.PNG) byteArrayOf(0, -1, -128, 42)
      else "{\"text\":\"Résumé\"}".toByteArray()
    return ExportArtifactV1(
      format = format,
      mediaType =
        when (format.name) {
          "RC" -> "application/octet-stream"
          "PNG" -> "image/png"
          else -> "application/json"
        },
      encoding =
        if (format.name == "RC" || format == ExportFormatV1.PNG) ExportEncodingV1.BASE64
        else ExportEncodingV1.UTF8,
      content =
        if (format.name == "RC" || format == ExportFormatV1.PNG)
          Base64.getEncoder().encodeToString(bytes)
        else bytes.decodeToString(),
      contentDigest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      diagnostics =
        listOf(
          ExportDiagnosticV1(
            if (refused) DiagnosticSeverityV1.ERROR else DiagnosticSeverityV1.WARNING,
            "COMPILER_DETAIL",
            "nodes.choice: compiler detail",
          ),
          ExportDiagnosticV1(
            DiagnosticSeverityV1.INFO,
            "REVISION_PINNED_REMOTE_EXPORT",
            "Exported design ${document.id} revision ${document.revision} (hash).",
          ),
        ),
    )
  }

  private companion object {
    val formats =
      ExportFormatV1.entries
        .filter { it.name == "JSON" || it.name == "RC" }
        .let { if (it.isEmpty()) it else it + ExportFormatV1.PNG }
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
    }
    val evidence = Path.of("../docs/design/evidence/ui-builder-unsaved-remote-preview")
    val document =
      json.decodeFromString<DesignDocumentV1>(Files.readString(evidence.resolve("document.json")))
  }
}
