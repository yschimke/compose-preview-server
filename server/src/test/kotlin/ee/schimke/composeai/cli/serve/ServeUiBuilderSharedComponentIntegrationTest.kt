package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ComponentSourceV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DESIGN_COMPONENT_INSTANCE_COMPONENT_ID
import ee.schimke.composeai.uibuilder.protocol.DesignComponentInstanceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * A published component, from the file a project shares to the export that refuses it.
 *
 * ## Why the whole path, when every hop already has tests
 *
 * The hops are covered and the seam between them was not, and every defect this feature has had
 * lived in that seam rather than in a hop. The library read a symbol nothing could place, because
 * `ProductionUiBuilderRuntime.validate` looked `design/component-instance` up in a catalog that
 * cannot declare it. The reducer accepted a declaration whose body then could not export, because
 * `ExportValidation.validateGraph` counted the component's own root as a second parent of that
 * body. Each unit test stayed green through both.
 *
 * So this starts at the file a project actually publishes, reads it with the real
 * [ServeUiBuilderComponentLibrary], assembles the design an import would write — body, declaration
 * carrying [ComponentSourceV1], and a placement of it — and asks a running server to create and
 * export it. Nothing here is hand-shaped to pass: the symbol is parsed from JSON on disk, and the
 * design is built from what the library returned.
 */
class ServeUiBuilderSharedComponentIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private val project = createTempDirectory("shared-component").toFile()
  private val components =
    File(project, ServeUiBuilderComponentLibrary.COMPONENTS_DIR).apply { mkdirs() }
  private var running: RunningServer? = null

  @AfterTest
  fun tearDown() {
    running?.close()
    project.deleteRecursively()
    client.dispatcher.executorService.shutdown()
  }

  @Test
  fun `a published component reaches a design the service accepts, and stops at the served export`() {
    publish()
    val library = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })
    val catalog =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = CATALOG_SYSTEM_ID,
        source = ServeUiBuilderDesignLibrary.Source.Directory(project),
      )

    // The read an editor's palette makes.
    val entry = library.index(catalog).single { it.componentId == COMPONENT_ID }
    val symbol = assertNotNull(library.symbol(catalog, entry), "the published symbol must resolve")
    assertTrue(symbol.digest.startsWith("sha256:"), symbol.digest)

    val server = start()
    assertIs<UiBuilderResponseV1>(response(server, CreateDesignRequestV1(designImporting(symbol))))

    val artifact =
      assertIs<ExportResponseV1>(
          response(server, ExportDesignRequestV1(DESIGN_ID, revision = 0, ExportFormatV1.COMPOSE))
        )
        .artifact

    // Storage accepts the published symbol's human-readable name. Source generation must still
    // explain why it cannot export it: the released generator lacks reusable functions, the
    // default build disables them, or an enabled generator rejects "Contribution cell" as Kotlin.
    val diagnostic = artifact.diagnostics.single()
    assertTrue(
      diagnostic.code in setOf("UNEXPRESSIBLE_DOCUMENT", "UNPROVEN_CALL_SITE"),
      artifact.diagnostics.toString(),
    )
    assertTrue(
      listOf("shared generator support", "disabled in this build", "Contribution cell").any {
        it in diagnostic.message
      },
      artifact.diagnostics.toString(),
    )
  }

  /**
   * The import records where the body came from, and that is what a later drift read compares.
   *
   * A design holds the body, so nothing about drawing or exporting needs the library again — which
   * is exactly why the recorded digest has to survive the round trip through create and reload. If
   * it did not, the component-drift route would report every imported component as unchanged
   * forever, which is the failure mode hardest to notice.
   */
  @Test
  fun `the imported source survives the round trip the drift report reads it from`() {
    publish()
    val library = ServeUiBuilderComponentLibrary(fetch = { _, _ -> null })
    val catalog =
      ServeUiBuilderDesignLibrary.Coordinate(
        system = CATALOG_SYSTEM_ID,
        source = ServeUiBuilderDesignLibrary.Source.Directory(project),
      )
    val entry = library.index(catalog).single { it.componentId == COMPONENT_ID }
    val symbol = assertNotNull(library.symbol(catalog, entry))

    val server = start()
    assertIs<UiBuilderResponseV1>(response(server, CreateDesignRequestV1(designImporting(symbol))))

    val drift = ServeUiBuilderComponentDrift(library)
    val stored = designImporting(symbol).components
    val findings = drift.check(stored, listOf(catalog))

    assertEquals(1, findings.size, findings.toString())
    assertEquals(ServeUiBuilderComponentDrift.State.UNCHANGED, findings.single().state)
    assertEquals(symbol.digest, findings.single().source.digest)

    // And the other half of the bargain: edit what the project publishes and the same design now
    // reports drift rather than silently redrawing.
    publish(text = "Contributions this year")
    val moved =
      ServeUiBuilderComponentDrift(ServeUiBuilderComponentLibrary(fetch = { _, _ -> null }))
    assertEquals(
      ServeUiBuilderComponentDrift.State.DRIFTED,
      moved.check(stored, listOf(catalog)).single().state,
    )
  }

  /** The design an import writes: the symbol's body, its declaration, and one placement. */
  private fun designImporting(symbol: ServeUiBuilderComponentLibrary.Symbol): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Home",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG_SYSTEM_ID, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("screen"),
      nodes =
        // The body's nodes as published, plus a screen holding the body and one placement of it.
        symbol.nodes +
          mapOf(
            "screen" to
              DesignNodeV1(
                id = "screen",
                componentId = "layout/column",
                slots = mapOf("children" to listOf(symbol.component.root, "placed")),
              ),
            "placed" to
              DesignNodeV1(
                id = "placed",
                componentId = DESIGN_COMPONENT_INSTANCE_COMPONENT_ID,
                component = DesignComponentInstanceV1(COMPONENT_ID),
              ),
          ),
      components =
        mapOf(
          COMPONENT_ID to
            symbol.component.copy(
              source =
                ComponentSourceV1(
                  system = CATALOG_SYSTEM_ID,
                  componentId = COMPONENT_ID,
                  digest = symbol.digest,
                )
            )
        ),
    )

  private fun publish(text: String = "Contributions") {
    File(components, "index.json")
      .writeText(
        """{"schema":"${ServeUiBuilderComponentLibrary.INDEX_SCHEMA}",
           "components":[{"id":"$COMPONENT_ID","title":"Contribution cell"}]}"""
      )
    File(components, "$COMPONENT_ID.json")
      .writeText(
        """
        {
          "schema": "compose-ui-builder-document/v1-candidate",
          "id": "$COMPONENT_ID",
          "title": "Contribution cell",
          "revision": 0,
          "catalogPin": {
            "systemId": "$CATALOG_SYSTEM_ID", "catalogRevision": "candidate",
            "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"
          },
          "environment": {
            "widthDp": 412, "heightDp": 915, "density": 1.0, "theme": "dark",
            "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
            "layoutDirection": "ltr", "windowPosture": "flat", "browserZoomPercent": 100,
            "fixedTime": "2024-05-16T12:00:00Z", "animations": "settled", "networkAccess": false
          },
          "stateVariables": {},
          "roots": ["cell"],
          "nodes": {
            "cell": {
              "id": "cell", "componentId": "m3/text",
              "properties": {"text": {"type": "string", "value": "$text"}},
              "modifiers": [], "slots": {}, "eventBindings": {}
            }
          },
          "components": {
            "$COMPONENT_ID": {"name": "Contribution cell", "root": "cell"}
          }
        }
        """
          .trimIndent()
      )
  }

  private fun start(): RunningServer {
    val registry = ServeSessionRegistry(open = { null })
    val source =
      ComponentRecordSource(
        mapOf(CATALOG_SYSTEM_ID to ScreenGeneratorScreenFixture.componentsFile())
      )
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs =
          CurrentM3UiBuilderCatalogExecutor(
            catalogSystemIds = setOf(CATALOG_SYSTEM_ID),
            exportCapabilities =
              ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1(
                composeCode = true,
                svg = false,
                png = false,
              ),
            composeExportFor = { it == CATALOG_SYSTEM_ID },
          ),
        exporter = ScreenGeneratorComposeExportExecutor(source::record),
      )
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry).also { running = it }
  }

  private fun response(running: RunningServer, request: UiBuilderRequestV1): UiBuilderResponseV1 {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "request-${System.nanoTime()}",
        actorId = "operator",
        request = request,
      )
    val http =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${running.server.port}/api/ui-builder/v1/requests")
            .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
            .post(
              json
                .encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
                .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()
        )
        .execute()
    return http.use {
      val text = it.body?.string().orEmpty()
      assertEquals(200, it.code, text)
      json.decodeFromString(HttpResponseEnvelopeV1.serializer(), text).response
    }
  }

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "shared-component-operator-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val COMPONENT_ID = "contribution-cell"
    const val DESIGN_ID = "home"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
