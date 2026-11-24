package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import java.io.Closeable
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * `uiBuilder.anonymousDocumentExport`: who may compile a document they supply to `.rc`, and what
 * stays behind the export capability either way.
 */
class AnonymousDocumentExportTest {
  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val actors = CopyOnWriteArrayList<String>()
  private val closeables = CopyOnWriteArrayList<Closeable>()
  private val client = OkHttpClient()

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        val request = call.request as UiBuilderServiceRequest.ExportDocument
        actors += call.actor.actorId
        return UiBuilderServiceResponse.Export(
          ExportArtifactV1(
            format = request.format,
            mediaType = "application/octet-stream",
            encoding = ExportEncodingV1.BASE64,
            content = Base64.getEncoder().encodeToString(RC),
            contentDigest = "rc-digest",
          ),
        )
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  /** No header: no credential. `reader`: read only. `agent`: a grant presented without export. */
  private val authorization = ServeUiBuilderAuthorization { call, capability, _ ->
    when (call.request.headers[ACTOR_HEADER]) {
      null ->
        if (capability == UiBuilderRouteCapability.READ) {
          UiBuilderAuthorizationDecision.Authorized(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID)
        } else {
          UiBuilderAuthorizationDecision.Missing
        }
      "reader" ->
        if (capability == UiBuilderRouteCapability.READ) {
          UiBuilderAuthorizationDecision.Authorized("github:reader")
        } else {
          UiBuilderAuthorizationDecision.Missing
        }
      "agent" ->
        if (capability == UiBuilderRouteCapability.READ) {
          UiBuilderAuthorizationDecision.Authorized("agent:reader")
        } else {
          UiBuilderAuthorizationDecision.Forbidden
        }
      else -> UiBuilderAuthorizationDecision.Authorized("github:exporter")
    }
  }

  @AfterTest
  fun close() {
    closeables.forEach(Closeable::close)
  }

  @Test
  fun `the setting reads as the entrypoint exports a flag`() {
    assertTrue(AnonymousDocumentExport.enabled(mapOf(AnonymousDocumentExport.ENV to "1")))
    assertTrue(AnonymousDocumentExport.enabled(mapOf(AnonymousDocumentExport.ENV to "true")))
    assertFalse(AnonymousDocumentExport.enabled(mapOf(AnonymousDocumentExport.ENV to "0")))
    assertFalse(AnonymousDocumentExport.enabled(emptyMap()))
  }

  @Test
  fun `off, a caller without export is refused`() {
    assumeTrue(ExportFormatV1.RC in RemoteDocumentExportSupport.formats)
    val port = serve(anonymous = false)
    assertEquals(401, post(port, "rc", actor = null))
    assertEquals(401, post(port, "rc", actor = "reader"))
    assertEquals(200, post(port, "rc", actor = "exporter"))
    assertEquals(listOf("github:exporter"), actors)
  }

  @Test
  fun `on, a reader or a signed-out visitor compiles a supplied document to rc`() {
    assumeTrue(ExportFormatV1.RC in RemoteDocumentExportSupport.formats)
    val port = serve(anonymous = true)
    assertEquals(200, post(port, "rc", actor = null))
    assertEquals(200, post(port, "rc", actor = "reader"))
    assertEquals(200, post(port, "rc", actor = "exporter"))
    assertEquals(
      listOf(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID, "github:reader", "github:exporter"),
      actors,
    )
  }

  @Test
  fun `on, a refused credential stays refused and other formats keep the export capability`() {
    assumeTrue(ExportFormatV1.RC in RemoteDocumentExportSupport.formats)
    val port = serve(anonymous = true)
    assertEquals(403, post(port, "rc", actor = "agent"))
    assertEquals(401, post(port, "json", actor = null))
    assertEquals(401, post(port, "png", actor = null))
    assertTrue(actors.isEmpty())
  }

  private fun serve(anonymous: Boolean): Int {
    val engine =
      embeddedServer(CIO, host = "127.0.0.1", port = 0) {
          routing {
            installUiBuilderLiveExportRoutes(
              service,
              authorization,
              anonymousDocumentExport = anonymous,
            )
          }
        }
        .also { it.start(wait = false) }
    closeables += Closeable { engine.stop(0, 0) }
    return runBlocking { engine.engine.resolvedConnectors().first().port }
  }

  private fun post(port: Int, format: String, actor: String?): Int =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:$port/api/ui-builder/v1/documents/export.$format")
          .apply { actor?.let { header(ACTOR_HEADER, it) } }
          .post(
            json
              .encodeToString(
                DesignDocumentV1.serializer(),
                ScreenGeneratorScreenFixture.document(),
              )
              .toRequestBody(),
          )
          .build(),
      )
      .execute()
      .use { it.code }

  private companion object {
    const val ACTOR_HEADER = "X-Test-Actor"
    val RC = byteArrayOf(1, 2, 3)
  }
}
