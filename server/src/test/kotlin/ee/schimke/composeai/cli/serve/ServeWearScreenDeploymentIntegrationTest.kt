package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * Creating a Wear screen the way a deployed box does, over HTTP, end to end.
 *
 * ## Why the catalog list here is a literal
 *
 * `m3-catalog,remote-m3,wear-m3` is the packaged image's default — `SERVE_UI_BUILDER_CATALOGS` in
 * `deploy/image/entrypoint.sh` and `docker-compose.yml`, asserted by
 * `deploy/image/test-preview-ui-default.sh`. Writing it out again here is the point: those scripts
 * check that the entrypoint *passes* the flag, and this checks that a server given that exact flag
 * can actually serve a Wear design. A default that names an adapter the server then refuses would
 * pass both halves separately and fail the only question anybody asks.
 *
 * ## What it walks
 *
 * The New design form's own route — `POST /ui-builder/<catalog>`,
 * `application/x-www-form-urlencoded`, seeded server-side by `UiBuilderNewDesignSeed` — then reads
 * the design back and exports it. That is the path the chooser drives, so a template that only
 * worked from the browser's bootstrap, or a seed that produced a document the service then
 * rejected, fails here.
 */
class ServeWearScreenDeploymentIntegrationTest {
  @TempDir lateinit var stateDirectory: Path
  @TempDir lateinit var builderDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()

  @Test
  fun `the packaged catalog default creates, opens and exports a Wear screen`() {
    val running = startServer()
    try {
      // 1. The adapter is served at all. `wear-m3` reaching the wire is what the deployment
      //    default buys; before it, `templateIds` refused every id and the chooser had no entry.
      val catalogs = assertIs<CatalogsResponseV1>(response(running, ListCatalogsRequestV1))
      assertEquals(
        listOf("m3-catalog", "remote-m3", "wear-m3"),
        catalogs.catalogs.map { it.benchmark.catalogSystemId }.sorted(),
      )

      // 2. Make a screen, through the form the New design chooser submits.
      val created =
        createDesign(running, catalog = "wear-m3", designId = "activity", template = "wear-list")
      assertEquals(303, created.first, created.second)
      // The redirect goes to the permalink a person opens, not to the API path that made it.
      assertEquals("/ui-builder/wear-m3/activity", created.second)

      // 3. Read it back: a scaffold over a list, with more than a screenful in it.
      val snapshot =
        assertIs<SnapshotResponseV1>(response(running, OpenDesignRequestV1("activity")))
      val document = snapshot.snapshot.state.document
      assertEquals("wear-m3", document.catalogPin.systemId)
      assertEquals(listOf("wear-screen"), document.roots)
      assertEquals("wear-m3/screen-scaffold", document.nodes.getValue("wear-screen").componentId)
      val list = document.nodes.getValue("wear-list")
      assertEquals("wear-m3/transforming-lazy-column", list.componentId)
      // A header and six rows: 48 + 4 + 6 * 64 + 5 * 4 = 452dp of content, against a 192dp screen.
      assertEquals(7, list.slots.getValue("items").size)
      assertEquals(192, document.environment.widthDp)

      // 4. Export the code the Code pane shows. Both previews, because they answer different
      //    questions and the LONG one is what a parity render is taken from.
      val exported =
        assertIs<ExportResponseV1>(
          response(running, ExportDesignRequestV1("activity", format = ExportFormatV1.COMPOSE))
        )
      val source = exported.artifact?.content.orEmpty()
      assertTrue("ScreenScaffold(scrollState = listState" in source, source)
      assertTrue("TransformingLazyColumn(" in source, source)
      assertTrue("ListHeader(" in source, source)
      assertTrue("Modifier.transformedHeight(this, spec)" in source, source)
      assertTrue("@WearPreviewDevices" in source, source)
      assertTrue("@ScrollingPreview(modes = [ScrollMode.LONG])" in source, source)
      assertTrue("LocalScrollCaptureInProgress" in source, source)
    } finally {
      running.close()
    }
  }

  /** The empty template is reachable too, and generates a screen rather than a refusal. */
  @Test
  fun `the empty Wear template creates a scaffold over an empty list`() {
    val running = startServer()
    try {
      assertEquals(303, createDesign(running, "wear-m3", "blank-watch", "wear-screen").first)

      val snapshot =
        assertIs<SnapshotResponseV1>(response(running, OpenDesignRequestV1("blank-watch")))
      val document = snapshot.snapshot.state.document
      assertTrue(document.nodes.getValue("wear-list").slots.getValue("items").isEmpty())

      val exported =
        assertIs<ExportResponseV1>(
          response(running, ExportDesignRequestV1("blank-watch", format = ExportFormatV1.COMPOSE))
        )
      assertTrue("TransformingLazyColumn(" in exported.artifact?.content.orEmpty())
    } finally {
      running.close()
    }
  }

  /** A template the catalog does not offer is refused by name rather than silently defaulted. */
  @Test
  fun `a template wear-m3 does not offer is refused`() {
    val running = startServer()
    try {
      val (status, body) = createDesign(running, "wear-m3", "nope", "jetcaster")

      assertEquals(400, status)
      assertTrue("jetcaster" in body, body)
    } finally {
      running.close()
    }
  }

  /** `POST /ui-builder/<catalog>`, form-encoded, following no redirect: status and Location. */
  private fun createDesign(
    running: RunningServer,
    catalog: String,
    designId: String,
    template: String,
  ): Pair<Int, String> {
    val form = "designId=$designId&template=$template"
    val http =
      client
        .newBuilder()
        .followRedirects(false)
        .build()
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${running.server.port}/ui-builder/$catalog")
            .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        )
        .execute()
    return http.use { it.code to (it.header("Location") ?: it.body.string()) }
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
                .toRequestBody("application/json".toMediaType())
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

  private fun startServer(): RunningServer {
    // The builder route is gated on a directory being present, so the New design form 404s without
    // one. An index.html is all the create path reads; the Wasm bundle is the browser's business.
    builderDirectory.createDirectories()
    builderDirectory.resolve("index.html").writeText("<!doctype html><title>builder</title>")
    // Every template reads its environment from the Jetcaster operations fixture the builder
    // distribution ships beside its Wasm bundle, so a directory without it is a 500 rather than a
    // created design. The packaged image has it; this stages the same file.
    builderDirectory
      .resolve("jetcaster-discover-operations-v1.json")
      .writeText(
        File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
          .takeIf { it.isFile }
          ?.readText()
          ?: File("docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .readText()
      )
    val registry = ServeSessionRegistry(open = { null })
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs =
          CurrentM3UiBuilderCatalogExecutor(
            catalogSystemIds = PACKAGED_DEFAULT,
            exportCapabilities =
              ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1(
                composeCode = true,
                svg = false,
                png = false,
              ),
          ),
        // No component record, deliberately: a Wear screen is written by `RecordFreeExport`'s
        // emitter rather than from a recovered signature, and the packaged image passes a record
        // for `m3-catalog` only. If wiring ever routed a `wear-m3` design at the record-driven
        // generator, this is where it would surface as NO_COMPONENT_RECORD.
        exporter = ScreenGeneratorComposeExportExecutor(ComponentRecordSource(emptyMap())::record),
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
          uiBuilderDir = File(builderDirectory.toUri()),
          // The route serves a catalog only if the server was told to serve it, separately from
          // the service knowing the adapter. Passing the packaged default to both is the wiring
          // under test: an operator setting `SERVE_UI_BUILDER_CATALOGS` sets exactly these two.
          uiBuilderCatalogs = PACKAGED_DEFAULT,
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry)
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
    const val OPERATOR_TOKEN = "operator-token"

    /** `SERVE_UI_BUILDER_CATALOGS`' default in `deploy/image/entrypoint.sh`. */
    val PACKAGED_DEFAULT = setOf("m3-catalog", "remote-m3", "wear-m3")
  }
}
