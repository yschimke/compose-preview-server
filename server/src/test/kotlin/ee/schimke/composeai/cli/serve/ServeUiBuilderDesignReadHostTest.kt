package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceLimits
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.io.TempDir

/**
 * Reading a design is a read of the stored document, never a reason to stand up a render host — not
 * even the one for the design's own catalog. Production saw a design GET take 50–95 s cold; this
 * pins that the GET path itself cannot be what boots a host, against the real design service.
 */
class ServeUiBuilderDesignReadHostTest {
  @TempDir lateinit var dir: Path

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val opened = AtomicInteger()
  private val built = AtomicInteger()
  private val bootLog = CopyOnWriteArrayList<String>()
  private var server: ServeHttpServer? = null
  private var registry: ServeSessionRegistry? = null
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry?.close()
  }

  private fun stateFor(label: String) =
    ServeSessionState(
      descriptor = File("daemon-launch.json"),
      workspaceRoot = Files.createTempDirectory(dir, "root").toFile(),
      workspaceName = "w",
      previews = listOf(ServePreview("com.example.Red", "Red")),
      label = label,
    )

  private fun start(): ServeHttpServer {
    val registry =
      ServeSessionRegistry(
          open = {
            opened.incrementAndGet()
            null
          },
          factory = {
            built.incrementAndGet()
            null
          },
          idleTimeoutMillis = 0,
          log = { bootLog += it },
        )
        .also { this.registry = it }
    // The design's own catalog, registered suspended and resumable: any read that reached for its
    // host would open it.
    registry.register(CATALOG, state = stateFor(CATALOG))
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(dir.resolve("state")),
        limits = UiBuilderServiceLimits(),
        catalogs = benchmarkCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter = UiBuilderExportExecutor { error("a read never exports") },
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(
      runBlocking {
        service.execute(
          UiBuilderServiceCall(owner, UiBuilderServiceRequest.CreateDesign(document())),
        )
      },
    )
    val builderDir =
      dir.resolve("builder").toFile().also {
        it.mkdirs()
        File(it, "index.html").writeText("<!doctype html><title>Compose UI builder</title>")
      }
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        catalogSessions = listOf(CATALOG),
        uiBuilderDir = builderDir,
        uiBuilderCatalogs = setOf(CATALOG),
        uiBuilderService = service,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization { _, _, _ ->
            UiBuilderAuthorizationDecision.Authorized(owner.actorId)
          },
      )
      .also {
        it.start()
        server = it
      }
  }

  private fun get(path: String): Pair<Int, String> =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${server!!.port}$path").build())
      .execute()
      .use { it.code to it.body.string() }

  @Test
  fun `a design GET, a retained revision and the designs page never open a render host`() {
    start()
    repeat(3) {
      val (code, body) = get("/api/ui-builder/v1/designs/$DESIGN_ID")
      assertEquals(200, code, body)
      assertContains(body, "\"id\":\"$DESIGN_ID\"")
      assertContains(body, CATALOG)
    }
    assertEquals(200, get("/api/ui-builder/v1/designs/$DESIGN_ID?revision=0").first)
    val (pageCode, page) = get("/ui-builder/designs")
    assertEquals(200, pageCode)
    assertContains(page, DESIGN_ID)

    assertEquals(0, opened.get(), "no read opened the design's catalog host")
    assertEquals(0, built.get(), "no read built a session")
    assertTrue(bootLog.none { "booted" in it || "failed to boot" in it }, bootLog.toString())
  }

  @Test
  fun `a host boot is logged with its cause, and a suspension with its reason`() {
    var now = 0L
    val log = CopyOnWriteArrayList<String>()
    val registry =
      ServeSessionRegistry(
          open = { state ->
            ServeRenderHost(
              session = FakeRenderSession(Files.createTempDirectory(dir, "r").toFile()),
              previews = state.previews,
              label = state.label,
              renderTimeoutSeconds = 30,
            )
          },
          idleTimeoutMillis = 10,
          reaperIntervalMillis = 0,
          clock = { now },
          log = { log += it },
        )
        .also { this.registry = it }
    registry.register("wff-watchface", state = stateFor("wff-watchface"))

    ServeHostBootCause.with("GET /wff-watchface/render") { registry.lease("wff-watchface") }!!
      .close()
    val first = log.single { "booted" in it }
    assertContains(first, "serve: host wff-watchface booted (first open) in ")
    assertContains(first, "cause: GET /wff-watchface/render")

    now = 120_000
    assertEquals(1, registry.suspendIdle())
    assertContains(log.single { "suspended" in it }, "suspended (idle): idle 120 s")

    now = 150_000
    registry.acquire("wff-watchface")
    // No route named itself: the first frame outside the registry stands in for the cause.
    val resumed = log.last { "booted" in it }
    assertContains(resumed, "booted (resumed after 30 s suspended)")
    assertContains(resumed, "cause: ServeUiBuilderDesignReadHostTest")
  }

  @Test
  fun `a slow design-service call is logged with its request type and design`() {
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(dir.resolve("timed")),
        limits = UiBuilderServiceLimits(),
        catalogs = benchmarkCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter = UiBuilderExportExecutor { error("no export") },
      )
    var now = 0L
    val log = CopyOnWriteArrayList<String>()
    // Each read of the clock is 1.5 s later, so a call (two reads) takes 1.5 s: just under the
    // threshold of 2 s until the threshold is lowered.
    val timed =
      ServeUiBuilderServiceTiming(
        service,
        thresholdMillis = 1_500,
        clock = { now.also { now += 1_500 } - 0 },
        log = { log += it },
      )
    runBlocking {
      timed.execute(UiBuilderServiceCall(owner, UiBuilderServiceRequest.CreateDesign(document())))
      timed.execute(
        UiBuilderServiceCall(owner, UiBuilderServiceRequest.GetSnapshot(DESIGN_ID, null)),
      )
    }
    assertEquals(
      listOf(
        "serve: ui-builder CreateDesign $DESIGN_ID took 1500 ms",
        "serve: ui-builder GetSnapshot $DESIGN_ID took 1500 ms",
      ),
      log,
    )
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Read me",
      revision = 0,
      // A candidate pin, as the wff-watchface design that was slow in production carries.
      catalogPin = CatalogReferenceV1(CATALOG, "candidate", "candidate", "candidate"),
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
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Hello")),
            ),
        ),
    )

  private companion object {
    const val CATALOG = "m3-catalog"
    const val DESIGN_ID = "read-me"
  }
}
