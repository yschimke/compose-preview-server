package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class RemotePngExportProofTest {
  @TempDir lateinit var work: Path

  @Test
  fun `packaged renderer plays exported RC bytes at the authored density`() {
    val appHome = System.getenv("UI_BUILDER_REAL_RENDER_APP_HOME")
    assumeTrue(appHome != null && System.getenv("VERIFY_REMOTE_PNG_EXPORTS") == "true")
    val old = System.getProperty("composeai.cli.appHome")
    System.setProperty("composeai.cli.appHome", appHome!!)
    try {
      val source =
        Json.decodeFromString<DesignDocumentV1>(
            Files.readString(
              Path.of("../docs/design/evidence/ui-builder-remote-native-preview/document.json")
            )
          )
          .let {
            if (System.getenv("VERIFY_REMOTE_FLOAT_BROWSER") == "true") decimalSelection(it) else it
          }
      ServeUiBuilderRenderPort.open(work.resolve("renderer")).use { renderer ->
        for (density in listOf(1.0, 2.0)) {
          val document = source.copy(environment = source.environment.copy(density = density))
          val catalog =
            CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("remote-m3"))
              .listCatalogs()
              .single()
          val exporter =
            RemotePngExportExecutor(
              RemoteDocumentExportExecutor(
                UiBuilderExportExecutor { error("PNG must play the compiled RC document") }
              ),
              renderer,
            )
          val artifact =
            exporter.export(
              RevisionPinnedUiBuilderExport(
                AuthenticatedUiBuilderActor("operator"),
                document.id,
                document.revision,
                "proof",
                document,
                catalog,
                ExportFormatV1.PNG,
              )
            )
          assertTrue(
            artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
            artifact.toString(),
          )
          assertTrue(artifact.diagnostics.any { it.code == "REMOTE_DOCUMENT_PNG" })
          val png = Base64.getDecoder().decode(artifact.content)
          val size = (360 * density).toInt()
          val output = Path.of("build/remote-png-proof").also(Files::createDirectories)
          Files.write(output.resolve("density-${density.toInt()}.png"), png)
          val frame = assertNotNull(ImageIO.read(png.inputStream()))
          assertEquals(size, frame.width)
          assertEquals(size, frame.height)
          val padding = (24 * density).toInt()
          assertEquals(0x008577, frame.getRGB(padding, size / 2) and 0xffffff)
          assertEquals(0x008577, frame.getRGB(size / 2, size - padding - 1) and 0xffffff)
          assertNotEquals(0x008577, frame.getRGB(padding - 1, size / 2) and 0xffffff)
          assertNotEquals(0x008577, frame.getRGB(size / 2, size - padding) and 0xffffff)
        }
        verifyRoutes(source, renderer)
      }
    } finally {
      if (old == null) System.clearProperty("composeai.cli.appHome")
      else System.setProperty("composeai.cli.appHome", old)
    }
  }

  private fun decimalSelection(source: DesignDocumentV1): DesignDocumentV1 {
    val choice = source.nodes.getValue("choice")
    return source.copy(
      title = "Decimal state selection",
      stateVariables =
        source.stateVariables +
          ("page" to
            source.stateVariables
              .getValue("page")
              .copy(
                valueType = StateValueTypeV1.DECIMAL,
                initialValue = JsonPrimitive(2.5),
              )),
      nodes =
        source.nodes.mapValues { (_, node) ->
          node.copy(
            properties =
              if (node.id == choice.id)
                mapOf(
                  "showByState" to
                    ObjectValueV1(
                      mapOf(
                        "selector" to StateValueV1("page"),
                        "cases" to
                          ObjectValueV1(
                            mapOf("First" to DecimalValueV1(1.25), "Second" to DecimalValueV1(2.5))
                          ),
                        "fallback" to StringValueV1("Fallback"),
                      )
                    )
                )
              else node.properties,
            eventBindings =
              node.eventBindings.mapValues { (_, actions) ->
                actions.map { action ->
                  if (action is SetValueActionV1 && action.variable == "page")
                    action.copy(
                      value =
                        JsonPrimitive(
                          when (action.value.jsonPrimitive.int) {
                            10 -> 1.25
                            20 -> 2.5
                            else -> 3.75
                          }
                        )
                    )
                  else action
                }
              },
          )
        },
    )
  }

  private fun verifyRoutes(source: DesignDocumentV1, renderer: UiBuilderRenderPort) {
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
    }
    val catalogs =
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("remote-m3"),
        exportCapabilities =
          ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport.capabilities(
            ExportCapabilitiesV1(png = true),
            json = true,
            document = true,
          ),
      )
    val catalog = catalogs.listCatalogs().single()
    val doc =
      source.copy(
        catalogPin =
          CatalogReferenceV1(
            "remote-m3",
            catalog.benchmark.catalogRevision,
            "candidate",
            catalog.benchmark.nativeRuntimeId,
          )
      )
    val exporter =
      RemotePngExportExecutor(
        RemoteDocumentExportExecutor(
          UiBuilderExportExecutor { error("Remote PNG must not render the editor tree") }
        ),
        renderer,
      )
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(work.resolve("designs")),
        catalogs = catalogs,
        exporter = exporter,
      )
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "png-proof",
          sessions = registry,
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization("png-proof", null, null),
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity("png-proof", null, null),
          uiBuilderDir = File("../ui-builder/build/wasmDist"),
          uiBuilderCatalogs = setOf("remote-m3"),
        )
        .also(ServeHttpServer::start)
    val client = OkHttpClient.Builder().readTimeout(java.time.Duration.ofMinutes(1)).build()
    fun request(path: String, body: String? = null, expectedStatus: Int = 200): ByteArray {
      val builder =
        Request.Builder()
          .url("http://127.0.0.1:${server.port}$path")
          .header(ServeHttpServer.TOKEN_HEADER, "png-proof")
      if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
      return client.newCall(builder.build()).execute().use {
        val bytes = it.body.bytes()
        assertEquals(expectedStatus, it.code, bytes.decodeToString())
        bytes
      }
    }
    fun mcp(name: String, args: JsonObject): JsonObject {
      val body = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", 1)
        put("method", "tools/call")
        putJsonObject("params") {
          put("name", name)
          put("arguments", args)
        }
      }
      val result =
        json
          .parseToJsonElement(request("/mcp", body.toString()).decodeToString())
          .jsonObject
          .getValue("result")
          .jsonObject
      assertTrue(result["isError"] != JsonPrimitive(true), result.toString())
      return json
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
        .getValue("response")
        .jsonObject
    }
    try {
      val encoded = json.encodeToString(doc)
      val png = request("/api/ui-builder/v1/documents/export.png", encoded)
      val artifact =
        json.decodeFromString<ExportArtifactV1>(
          request("/api/ui-builder/v1/documents/export.png?artifact=true", encoded).decodeToString()
        )
      assertContentEquals(png, Base64.getDecoder().decode(artifact.content))
      val exported =
        mcp(
          "ui_builder_export_document",
          buildJsonObject {
            put("document", json.parseToJsonElement(encoded))
            put("format", "png")
          },
        )
      val mcpArtifact = json.decodeFromJsonElement<ExportArtifactV1>(exported.getValue("artifact"))
      assertEquals(artifact, mcpArtifact)
      val unsupported =
        json.encodeToString(
          doc.copy(environment = doc.environment.copy(layoutDirection = LayoutDirectionV1.RTL))
        )
      val refusal =
        request("/api/ui-builder/v1/documents/export.png", unsupported, 422).decodeToString()
      assertTrue("environment.layoutDirection" in refusal, refusal)
      val refusedArtifact =
        json.decodeFromString<ExportArtifactV1>(
          request("/api/ui-builder/v1/documents/export.png?artifact=true", unsupported)
            .decodeToString()
        )
      assertEquals("", refusedArtifact.content)
      assertTrue(
        refusedArtifact.diagnostics.any {
          it.severity == DiagnosticSeverityV1.ERROR && it.code == "REMOTE_EXPORT_UNSUPPORTED"
        }
      )
      assertTrue(
        mcp("ui_builder_list_designs", buildJsonObject {}).getValue("designs").jsonArray.isEmpty(),
        "Export must not save the supplied design",
      )
      kotlinx.coroutines.runBlocking {
        service.execute(
          UiBuilderServiceCall(
            AuthenticatedUiBuilderActor("operator"),
            UiBuilderServiceRequest.CreateDesign(doc),
          )
        )
      }
      assertContentEquals(
        png,
        request("/api/ui-builder/v1/designs/${doc.id}/export.png?revision=0"),
      )
      val output = Path.of("build/remote-png-proof").also(Files::createDirectories)
      Files.write(output.resolve("http.png"), png)
      Files.writeString(output.resolve("document.json"), encoded)
      Files.writeString(output.resolve("artifact.json"), json.encodeToString(artifact))
      if (System.getenv("VERIFY_REMOTE_PNG_BROWSER") == "true") {
        val process =
          ProcessBuilder(
              "node",
              "../preview-harness/verify-remote-png-export.mjs",
              "http://127.0.0.1:${server.port}",
              output.toAbsolutePath().toString(),
            )
            .apply { environment()["UI_BUILDER_TEST_TOKEN"] = "png-proof" }
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
