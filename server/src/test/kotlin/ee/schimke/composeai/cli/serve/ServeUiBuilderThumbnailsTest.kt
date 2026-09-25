package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The design listing's cached card pictures: drawn once, kept across a restart, served stale while
 * a redraw runs behind, and never served to a reader the design's own access control refuses.
 */
class ServeUiBuilderThumbnailsTest {
  private val revision = AtomicLong(3)
  private val exports = AtomicInteger()
  private val rendering = AtomicInteger()
  private val mostAtOnce = AtomicInteger()
  private val actor = AuthenticatedUiBuilderActor(OWNER)

  private fun png(revision: Long) = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), revision.toByte())

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.GetDesignActions ->
            if (call.actor.actorId == OWNER && request.designId in DESIGNS)
              UiBuilderServiceResponse.DesignActions(
                request.designId,
                DesignAccessActionV1.entries.toList(),
              )
            else
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "no design")
              )
          is UiBuilderServiceRequest.ListDesigns ->
            UiBuilderServiceResponse.Designs(emptyList(), null)
          is UiBuilderServiceRequest.DeleteDesign ->
            UiBuilderServiceResponse.DesignDeleted(request.designId)
          is UiBuilderServiceRequest.ExportDesign -> {
            exports.incrementAndGet()
            mostAtOnce.accumulateAndGet(rendering.incrementAndGet(), ::maxOf)
            Thread.sleep(50)
            rendering.decrementAndGet()
            val served = revision.get()
            UiBuilderServiceResponse.Export(
              ExportArtifactV1(
                format = ExportFormatV1.PNG,
                mediaType = "image/png",
                encoding = ExportEncodingV1.BASE64,
                content = Base64.getEncoder().encodeToString(png(served)),
                contentDigest = "digest-$served",
                diagnostics =
                  listOf(
                    ExportDiagnosticV1(
                      severity = DiagnosticSeverityV1.INFO,
                      code = "REVISION_PINNED_DAEMON_RENDER",
                      message = "Rendered design ${request.designId} revision $served (hash).",
                    )
                  ),
              )
            )
          }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val directory = Files.createTempDirectory("serve-ui-builder-thumbnails")
  private val thumbnails = ServeUiBuilderThumbnails(directory, generation = "g1", onLog = {})
  private val wrapped = thumbnails.warming(service)

  private val authorization = ServeUiBuilderAuthorization { call, _, _ ->
    when (val actor = call.request.headers["X-Test-Actor"]) {
      null -> UiBuilderAuthorizationDecision.Missing
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }
  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
        uiBuilderThumbnails = thumbnails,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
    thumbnails.close()
  }

  private fun fetch(designId: String, revision: Long?, actor: String? = OWNER) =
    Request.Builder()
      .url(
        "http://127.0.0.1:${server.port}/api/ui-builder/v1/designs/$designId/thumbnail.png" +
          (revision?.let { "?revision=$it" } ?: "")
      )
      .also { builder -> actor?.let { builder.header("X-Test-Actor", it) } }
      .build()
      .let { client.newCall(it).execute() }

  private fun awaitCurrent(revision: Long) {
    val deadline = System.nanoTime() + 10_000_000_000
    while (!thumbnails.isCurrent(DESIGN, revision)) {
      check(System.nanoTime() < deadline) { "the redraw never landed" }
      Thread.sleep(20)
    }
  }

  @Test
  fun `a picture is drawn once, then served from the cache as cacheable`() {
    fetch(DESIGN, 3).use { response ->
      assertEquals(200, response.code)
      assertEquals("image/png", response.header("Content-Type"))
      assertEquals("private, max-age=86400", response.header("Cache-Control"))
      assertContentEquals(png(3), response.body.bytes())
    }
    fetch(DESIGN, 3).use { assertEquals(200, it.code) }
    assertEquals(1, exports.get(), "the second view is the cache, not a render")
  }

  @Test
  fun `an out of date picture is served at once and redrawn behind it`() {
    fetch(DESIGN, 3).use { assertEquals(200, it.code) }
    revision.set(4)

    fetch(DESIGN, 4).use { response ->
      assertEquals(200, response.code)
      assertEquals("no-store", response.header("Cache-Control"), "stale is never cached")
      assertEquals("3", response.header(UI_BUILDER_REVISION_HEADER))
      assertContentEquals(png(3), response.body.bytes())
    }
    awaitCurrent(4)
    fetch(DESIGN, 4).use { response ->
      assertEquals("private, max-age=86400", response.header("Cache-Control"))
      assertContentEquals(png(4), response.body.bytes())
    }
  }

  @Test
  fun `the cache outlives the process, and a new generation redraws in the background`() {
    runBlocking { assertNotNull(thumbnails.render(DESIGN, actor)) }

    val sameServer = ServeUiBuilderThumbnails(directory, generation = "g1", onLog = {})
    assertTrue(sameServer.isCurrent(DESIGN, 3), "read back from disk")
    sameServer.close()

    val redeployed = ServeUiBuilderThumbnails(directory, generation = "g2", onLog = {})
    redeployed.warming(service)
    assertNotNull(redeployed.cached(DESIGN), "the old picture still serves")
    assertEquals(false, redeployed.isCurrent(DESIGN, 3), "but is not current for a new renderer")
    redeployed.warm(DESIGN, actor, knownRevision = 3)
    val deadline = System.nanoTime() + 10_000_000_000
    while (!redeployed.isCurrent(DESIGN, 3)) {
      check(System.nanoTime() < deadline) { "the redraw never landed" }
      Thread.sleep(20)
    }
    redeployed.close()
  }

  @Test
  fun `a reader the design refuses gets nothing, cached or not`() {
    fetch(DESIGN, 3).use { assertEquals(200, it.code) }
    fetch(DESIGN, 3, actor = "someone-else").use { assertEquals(404, it.code) }
    fetch(DESIGN, 3, actor = null).use { assertEquals(401, it.code) }
    assertNull(thumbnails.cached("../escape"))
  }

  @Test
  fun `a deleted design's picture is never what a new design under its id shows`() {
    fetch(DESIGN, 3).use { assertEquals(200, it.code) }
    runBlocking {
      wrapped.execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.DeleteDesign(DESIGN)))
    }
    assertNull(thumbnails.cached(DESIGN), "gone from memory")
    val reopened = ServeUiBuilderThumbnails(directory, generation = "g1", onLog = {})
    assertNull(reopened.cached(DESIGN), "and from disk")
    reopened.close()
  }

  @Test
  fun `cards drawn for the first time all at once still render one at a time`() {
    val pool = java.util.concurrent.Executors.newFixedThreadPool(DESIGNS.size)
    val codes =
      DESIGNS.map { design -> pool.submit<Int> { fetch(design, 3).use { it.code } } }
        .map { it.get() }
    pool.shutdown()
    assertEquals(DESIGNS.map { 200 }, codes, "every card got a picture")
    assertEquals(1, mostAtOnce.get(), "and the renderer was never asked twice at once")
  }

  private companion object {
    const val OWNER = "github:owner"
    const val DESIGN = "morning-player"
    val DESIGNS = listOf(DESIGN, "evening-player", "night-player", "dawn-player")
  }
}
