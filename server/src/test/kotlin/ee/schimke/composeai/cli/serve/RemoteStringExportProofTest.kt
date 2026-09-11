package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class RemoteStringExportProofTest {
  @TempDir lateinit var work: Path

  @Test
  fun `text state reaches current document HTTP and hosted MCP export`() {
    assumeTrue(System.getenv("VERIFY_REMOTE_STRING_EXPORTS") == "true")
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
    }
    val catalogs =
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("remote-m3"),
        exportCapabilities =
          RemoteDocumentExportSupport.capabilities(
            ExportCapabilitiesV1(),
            json = true,
            document = true,
          ),
      )
    val catalog = catalogs.listCatalogs().single()
    val source =
      json.decodeFromString<DesignDocumentV1>(
        Files.readString(
          Path.of("../docs/design/evidence/ui-builder-remote-png-export/document.json")
        )
      )
    val text =
      StateVariableV1(
        StateVariableTypeV1.VALUE,
        StateValueTypeV1.STRING,
        initialValue = JsonPrimitive("Ready"),
        persistence = StatePersistenceV1.PREVIEW,
      )
    val root = source.nodes.getValue("choice")
    val document =
      source.copy(
        id = "local-text-proof",
        title = "Independent text state",
        catalogPin =
          CatalogReferenceV1(
            "remote-m3",
            catalog.benchmark.catalogRevision,
            "candidate",
            catalog.benchmark.nativeRuntimeId,
          ),
        stateVariables = source.stateVariables + mapOf("label" to text, "second" to text),
        nodes =
          source.nodes +
            (root.id to
              root.copy(
                eventBindings =
                  mapOf("click" to listOf(SetValueActionV1("label", JsonPrimitive("@second"))))
              )),
      )
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(work.resolve("designs")),
        catalogs = catalogs,
        exporter =
          RemoteDocumentExportExecutor(
            UiBuilderExportExecutor { error("Must use Remote document compiler") }
          ),
      )
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "text-proof",
          sessions = registry,
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization("text-proof", null, null),
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity("text-proof", null, null),
          uiBuilderDir = File("../ui-builder/build/wasmDist"),
          uiBuilderCatalogs = setOf("remote-m3"),
        )
        .also(ServeHttpServer::start)
    val client = OkHttpClient()
    fun post(path: String, body: String): ByteArray =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}$path")
            .header(ServeHttpServer.TOKEN_HEADER, "text-proof")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        )
        .execute()
        .use {
          val bytes = it.body.bytes()
          assertEquals(200, it.code, bytes.decodeToString())
          bytes
        }
    try {
      val encoded = json.encodeToString(document)
      val sourceBytes = post("/api/ui-builder/v1/documents/export.json", encoded)
      assertTrue(sourceBytes.decodeToString().contains("compose-preview-state-v1"))
      val binary = post("/api/ui-builder/v1/documents/export.rc", encoded)
      assertContentEquals(RemoteComposeJson.compile(sourceBytes.decodeToString()), binary)
      for ((format, expected) in listOf("json" to sourceBytes, "rc" to binary)) {
        val call = buildJsonObject {
          put("jsonrpc", "2.0")
          put("id", 1)
          put("method", "tools/call")
          putJsonObject("params") {
            put("name", "ui_builder_export_document")
            putJsonObject("arguments") {
              put("document", json.parseToJsonElement(encoded))
              put("format", format)
            }
          }
        }
        val result =
          json
            .parseToJsonElement(post("/mcp", call.toString()).decodeToString())
            .jsonObject
            .getValue("result")
            .jsonObject
        assertTrue(result["isError"] != JsonPrimitive(true), result.toString())
        val envelope =
          json
            .parseToJsonElement(
              result
                .getValue("content")
                .jsonArray
                .first()
                .jsonObject
                .getValue("text")
                .jsonPrimitive
                .content
            )
            .jsonObject
        val artifact =
          json.decodeFromJsonElement<ExportArtifactV1>(
            envelope.getValue("response").jsonObject.getValue("artifact")
          )
        assertContentEquals(
          expected,
          if (format == "rc") Base64.getDecoder().decode(artifact.content)
          else artifact.content.toByteArray(),
        )
      }
      val output = Path.of("build/remote-string-proof").also(Files::createDirectories)
      Files.writeString(output.resolve("document.json"), encoded)
      Files.write(output.resolve("source.json"), sourceBytes)
      Files.write(output.resolve("document.rc"), binary)
      if (System.getenv("VERIFY_REMOTE_STRING_BROWSER") == "true") {
        val process =
          ProcessBuilder(
              "node",
              "../preview-harness/verify-remote-string-export.mjs",
              "http://127.0.0.1:${server.port}",
              output.toAbsolutePath().toString(),
            )
            .apply { environment()["UI_BUILDER_TEST_TOKEN"] = "text-proof" }
            .inheritIO()
            .start()
        try {
          assertTrue(process.waitFor(3, java.util.concurrent.TimeUnit.MINUTES))
          assertEquals(0, process.exitValue())
        } finally {
          if (process.isAlive) process.destroyForcibly()
        }
      }
    } finally {
      server.stop()
      registry.close()
    }
  }
}
