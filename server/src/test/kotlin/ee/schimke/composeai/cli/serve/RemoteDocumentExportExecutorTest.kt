package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.remotecompose.json.RemoteComposeJson
import ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.SHOW_BY_STATE
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class RemoteDocumentExportExecutorTest {
  @TempDir lateinit var stateDirectory: Path

  @Test
  fun `persistent service exports the saved revision through protocol and download routes`() {
    val formats = formats()
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
    val document =
      document()
        .copy(
          revision = 0,
          catalogPin =
            CatalogReferenceV1(
              "remote-m3",
              catalog.benchmark.catalogRevision,
              "candidate",
              catalog.benchmark.nativeRuntimeId,
            ),
        )
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = catalogs,
        exporter = RemoteDocumentExportExecutor(delegate),
      )
    val registry = ServeSessionRegistry(open = { null })
    val token = "remote-document-export-test"
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = token,
          sessions = registry,
          defaultSessionId = "unused",
          uiBuilderService = service,
          uiBuilderAuthorization = ServeUiBuilderAuthorization.fromServeIdentity(token, null, null),
        )
        .also(ServeHttpServer::start)
    val client = OkHttpClient()
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
    }
    fun request(request: UiBuilderRequestV1): UiBuilderResponseV1 {
      val envelope =
        HttpRequestEnvelopeV1(
          requestId = "request-${System.nanoTime()}",
          actorId = "operator",
          request = request,
        )
      return client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, token)
            .post(
              json
                .encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
                .toRequestBody("application/json".toMediaType())
            )
            .build()
        )
        .execute()
        .use {
          val body = it.body.string()
          assertEquals(200, it.code, body)
          json.decodeFromString(HttpResponseEnvelopeV1.serializer(), body).response
        }
    }
    try {
      assertIs<SnapshotResponseV1>(request(CreateDesignRequestV1(document)))
      val source =
        assertIs<ExportResponseV1>(request(ExportDesignRequestV1(document.id, 0, formats.first())))
          .artifact
      val binary =
        assertIs<ExportResponseV1>(request(ExportDesignRequestV1(document.id, 0, formats.last())))
          .artifact
      val bytes = Base64.getDecoder().decode(binary.content)
      assertContentEquals(RemoteComposeJson.compile(source.content), bytes)
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.port}/api/ui-builder/v1/designs/${document.id}/export.rc?revision=0&download=1"
            )
            .header(ServeHttpServer.TOKEN_HEADER, token)
            .build()
        )
        .execute()
        .use {
          assertEquals(200, it.code)
          assertEquals("0", it.header(UI_BUILDER_REVISION_HEADER))
          assertEquals("\"${binary.contentDigest}\"", it.header("ETag"))
          assertContentEquals(bytes, it.body.bytes())
        }
    } finally {
      server.stop()
      registry.close()
    }
  }

  private fun formats(): List<ExportFormatV1> {
    val formats = RemoteDocumentExportSupport.formats
    if (System.getenv("VERIFY_REMOTE_DOCUMENT_EXPORTS") == "true") assertEquals(2, formats.size)
    assumeTrue(formats.size == 2, "Requires the staged export contracts")
    return formats
  }

  private fun document(): DesignDocumentV1 =
    ScreenGeneratorScreenFixture.document()
      .copy(
        revision = 7,
        stateVariables =
          mapOf(
            "page" to
              StateVariableV1(
                StateVariableTypeV1.VALUE,
                StateValueTypeV1.INTEGER,
                initialValue = JsonPrimitive(16777217),
                persistence = StatePersistenceV1.PREVIEW,
              )
          ),
        roots = listOf("choice"),
        nodes =
          mapOf(
            "choice" to
              DesignNodeV1(
                "choice",
                "layout/box",
                properties =
                  mapOf(
                    SHOW_BY_STATE to
                      ObjectValueV1(
                        mapOf(
                          "selector" to StateValueV1("page"),
                          "cases" to
                            ObjectValueV1(
                              mapOf(
                                "first" to IntegerValueV1(16777216),
                                "second" to IntegerValueV1(16777217),
                              )
                            ),
                        )
                      )
                  ),
                slots = mapOf("children" to listOf("first", "second")),
              ),
            "first" to DesignNodeV1("first", "layout/box"),
            "second" to DesignNodeV1("second", "layout/box"),
          ),
      )

  private fun request(format: ExportFormatV1, document: DesignDocumentV1 = document()) =
    RevisionPinnedUiBuilderExport(
      AuthenticatedUiBuilderActor("tester"),
      document.id,
      document.revision,
      "document-hash",
      document,
      CurrentM3UiBuilderCatalogExecutor().listCatalogs().single(),
      format,
    )

  private val delegate = UiBuilderExportExecutor {
    error("Remote format reached the picture exporter")
  }

  @Test
  fun `source and compiled binary come from the same pinned authored hierarchy`() {
    val formats = formats()
    assertTrue(RemoteDocumentExportExecutor.compilerAvailable, "Stage the profile compiler too")
    val executor = RemoteDocumentExportExecutor(delegate)
    val source = executor.export(request(formats[0]))
    val binary = executor.export(request(formats[1]))
    assertEquals(ExportEncodingV1.UTF8, source.encoding)
    assertEquals(ExportEncodingV1.BASE64, binary.encoding)
    assertTrue("stateLayout" in source.content)
    assertTrue("integerExpression" in source.content)
    assertTrue(
      source.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      source.toString(),
    )
    assertTrue(
      binary.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      binary.toString(),
    )
    val bytes = Base64.getDecoder().decode(binary.content)
    assertContentEquals(RemoteComposeJson.compile(source.content), bytes)
    assertEquals(
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      binary.contentDigest,
    )
    assertTrue(binary.diagnostics.any { "revision 7 (document-hash)" in it.message })
    assertEquals(binary, executor.export(request(formats[1])))
  }

  @Test
  fun `unsupported content refuses before compilation with its node location`() {
    val format = formats().last()
    var compiled = false
    val executor =
      RemoteDocumentExportExecutor(delegate) {
        compiled = true
        byteArrayOf()
      }
    val document =
      document().let {
        it.copy(nodes = it.nodes + ("first" to DesignNodeV1("first", "unmapped/component")))
      }
    val result = executor.export(request(format, document))
    assertFalse(compiled)
    assertEquals("", result.content)
    assertTrue(
      result.diagnostics.any { it.severity == DiagnosticSeverityV1.ERROR && "first" in it.message }
    )
  }

  @Test
  fun `host capabilities are only advertised for declared Remote catalogs`() {
    val formats = formats()
    val catalogs =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = setOf("m3-catalog", "remote-m3"),
          exportCapabilities =
            RemoteDocumentExportSupport.capabilities(
              ExportCapabilitiesV1(),
              json = true,
              document = true,
            ),
        )
        .listCatalogs()
        .associateBy { it.benchmark.catalogSystemId }
    for (format in formats) {
      assertTrue(
        RemoteDocumentExportSupport.supports(
          catalogs.getValue("remote-m3").exportCapabilities,
          format,
        )
      )
      assertFalse(
        RemoteDocumentExportSupport.supports(
          catalogs.getValue("m3-catalog").exportCapabilities,
          format,
        )
      )
    }
  }
}
