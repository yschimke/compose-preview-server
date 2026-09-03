package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * The whole export path, over HTTP, wired the way [ServeRunner] wires it.
 *
 * Every other test of this change constructs [ScreenGeneratorComposeExportExecutor] directly, which
 * proves the generator and the projection and proves nothing about the three seams between a
 * request and them: that `--ui-builder-components` reaches [ComponentRecordSource] under the
 * catalog id a document actually pins, that the executor is installed as the service's exporter
 * rather than the one it replaces, and that the advertised `composeCode` capability lets the
 * request through instead of refusing it before either is consulted. A wiring mistake in any of
 * those returns a perfectly good artifact from the wrong code path, or no artifact at all, with
 * every unit test still green.
 *
 * So this asks the running server, as an authenticated operator, and asserts on what comes back.
 */
class ServeUiBuilderScreenExportIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()

  @Test
  fun `an operator exports compiling Compose from the record the host was configured with`() {
    val running = startServer()
    try {
      // The capability gate first, because it is what a builder UI reads to decide whether to
      // offer the action at all — an export that works behind a capability nobody advertises is
      // not reachable from the product.
      val catalogs = assertIs<CatalogsResponseV1>(response(running, ListCatalogsRequestV1))
      assertEquals(
        listOf(true),
        catalogs.catalogs.map { catalog -> catalog.exportCapabilities.composeCode },
        "the configured catalog has to advertise Compose export",
      )

      assertIs<UiBuilderResponseV1>(response(running, CreateDesignRequestV1(document())))
      val exported =
        response(
          running,
          ExportDesignRequestV1(document().id, revision = 0, ExportFormatV1.COMPOSE),
        )
      val artifact = assertIs<ExportResponseV1>(exported).artifact

      // No diagnostic is the claim this whole change exists to make: the executor it replaces
      // attached `ALMOST_COMPILING_PROJECTION` to every artifact, so an empty list here is the
      // difference between "this is the screen you designed" and "this is nearly it".
      assertEquals(emptyList(), artifact.diagnostics)
      // `Text` is imported and called by its simple name here, where the golden in
      // `ScreenGeneratorComposeExportExecutorTest` calls the same component fully qualified. Both
      // are right: a node in a slot with a receiver scope cannot rely on an import, and this
      // document's only node is the root.
      assertEquals(
        """
        package generated.uibuilder

        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable

        @Composable
        fun ServedSchedule() {
            Text(text = "Opening keynote")
        }

        """
          .trimIndent(),
        artifact.content,
      )
    } finally {
      running.close()
    }
  }

  private fun startServer(): RunningServer {
    val registry = ServeSessionRegistry(open = { null })
    // Wired exactly as `ServeRunner` wires it, including the catalog id the record is keyed by.
    // The document below pins `m3-catalog`, so a record filed under any other id is a
    // `NO_COMPONENT_RECORD` refusal rather than a test that quietly passes.
    val records =
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
          ),
        exporter = ScreenGeneratorComposeExportExecutor(records::record),
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
    return RunningServer(server, registry)
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
      val text = it.body.string()
      assertEquals(200, it.code, text)
      json.decodeFromString(HttpResponseEnvelopeV1.serializer(), text).response
    }
  }

  /**
   * One node, on purpose. The golden in `ScreenGeneratorComposeExportExecutorTest` is where the
   * generated shapes are pinned; what this document has to be is *valid against the real packaged
   * M3 catalog*, which the fixture screen is not — it pins a test catalog id and uses components
   * that catalog does not declare.
   */
  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "served-schedule",
      title = "Served schedule",
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
      roots = listOf("session"),
      nodes =
        mapOf(
          "session" to
            DesignNodeV1(
              id = "session",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            )
        ),
    )

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  companion object {
    private const val OPERATOR_TOKEN = "screen-export-operator-token"
    private const val CATALOG_SYSTEM_ID = "m3-catalog"
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
