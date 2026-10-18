// Manual loopback-only fixture for scripts/export-load-test.py; see
// docs/development/EXPORT_LOAD_TEST.md.
package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

fun main(args: Array<String>) {
  require(args.size == 1) { "pass an isolated state/telemetry directory" }
  val root = Path.of(args[0])
  val catalog =
    Json.decodeFromString(
      CatalogCapabilityV1.serializer(),
      File("docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json").readText(),
    )
  val caps =
    ExportCapabilitiesV1.Builder()
      .apply {
        png = true
        svg = true
        composeCode = true
      }
      .build()
  val catalogs =
    CurrentM3UiBuilderCatalogExecutor.Builder()
      .apply {
        catalogSystemIds = setOf("m3-catalog")
        published =
          mapOf("m3-catalog" to catalog.newBuilder().apply { exportCapabilities = caps }.build())
        exportCapabilities = caps
        composeExportFor = { true }
      }
      .build()
  val renderer = ServeUiBuilderRenderPort.open(root.resolve("renderer"))
  val source =
    ComponentRecordSource(
      mapOf("m3-catalog" to File("docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"))
    )
  val exporter =
    ProductionUiBuilderExportExecutor(
      renderer,
      ScreenGeneratorComposeExportExecutor(source::record),
    )
  val service =
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(root.resolve("state")),
      catalogs = catalogs,
      exporter = exporter,
    )
  val actor = AuthenticatedUiBuilderActor("local-load-fixture")
  val doc =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "load-design",
      title = "Load fixture",
      revision = 0,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 200,
          heightDp = 100,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Local export load fixture")),
            )
        ),
    )
  runBlocking {
    service.execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.CreateDesign(doc)))
  }
  val thumbnails = ServeUiBuilderThumbnails(root.resolve("thumbnails"), "load-fixture")
  val registry = ServeSessionRegistry(open = { null })
  val bundle = root.resolve("catalog").toFile().apply { mkdirs() }
  File(bundle, "index.html").writeText("<html>Local load fixture</html>")
  File(bundle, "previews").mkdirs()
  File(bundle, "previews/load-preview.png")
    .writeBytes(
      java.util.Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
    )
  registry.register(
    "load-fixture",
    host = ServeBundleHost(bundle, label = "Local load fixture"),
    pinned = true,
  )

  val server =
    ServeHttpServer(
      host = "127.0.0.1",
      requestedPort = 18081,
      token = "local-fixture-only",
      sessions = registry,
      defaultSessionId = "unused",
      isPublic = true,
      catalogMcpEnabled = true,
      machineAuthorization = ServeMachineAuthorization("local-fixture-only", null, null),
      uiBuilderService = thumbnails.warming(service),
      uiBuilderThumbnails = thumbnails,
      uiBuilderAuthorization =
        ServeUiBuilderAuthorization { _, _, _ ->
          UiBuilderAuthorizationDecision.Authorized(actor.actorId)
        },
    )
  server.start()
  val samples = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
  val telemetry = root.resolve("resources.jsonl").toFile()
  samples.scheduleAtFixedRate(
    {
      val stats = service.diagnostics()
      val heap = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
      val workers =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("ui-builder-export-") }
      val children = ProcessHandle.current().descendants().use { it.count() }
      telemetry.appendText(
        "{\"atMillis\":${System.currentTimeMillis()},\"heapUsed\":${heap.used},\"activeExports\":${stats.activeExports},\"peakExports\":${stats.peakExports},\"exportWorkers\":$workers,\"childProcesses\":$children}\n"
      )
    },
    0,
    50,
    java.util.concurrent.TimeUnit.MILLISECONDS,
  )
  Runtime.getRuntime()
    .addShutdownHook(
      Thread {
        samples.shutdownNow()
        thumbnails.close()
        server.stop()
        registry.close()
        renderer.close()
      }
    )
  println("LOCAL_EXPORT_READY port=${server.port}")
  Thread.currentThread().join()
}
