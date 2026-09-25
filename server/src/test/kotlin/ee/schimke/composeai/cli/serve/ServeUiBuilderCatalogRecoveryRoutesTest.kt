package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewStatusV1
import ee.schimke.composeai.uibuilder.protocol.CatalogUpgradePreviewV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

class ServeUiBuilderCatalogRecoveryRoutesTest {
  private val client = OkHttpClient()
  private val builderDir =
    Files.createTempDirectory("catalog-recovery-builder").toFile().also {
      it.resolve("index.html").writeText("<!doctype html><title>Recovery builder</title>")
    }
  private val calls = mutableListOf<UiBuilderServiceCall>()
  private var refusal: UiBuilderServiceError? = null
  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        calls += call
        refusal?.let {
          return UiBuilderServiceResponse.Error(it)
        }
        return when (val request = call.request) {
          is UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade ->
            UiBuilderServiceResponse.CatalogUpgradePreview(
              CatalogUpgradePreviewV1(
                designId = request.designId,
                baseRevision = 7,
                sourceCatalogPin = SOURCE,
                targetCatalogPin = TARGET,
                sourceDocumentHash = "source-hash",
                status = CatalogUpgradePreviewStatusV1.BLOCKED,
                previewDigest = "preview-digest",
              )
            )
          // The service's quarantine gate answers every request naming a stranded design this way,
          // the shell's `GetDesignActions` as much as a snapshot.
          is UiBuilderServiceRequest.GetDesignActions,
          is UiBuilderServiceRequest.GetSnapshot ->
            UiBuilderServiceResponse.Error(
              UiBuilderServiceError(
                ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
                "the stored pin is unavailable",
              )
            )
          else -> error("unexpected request $request")
        }
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = error("not used")
    }
  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderDir = builderDir,
        uiBuilderService = service,
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization { call, _, _ ->
            when (call.request.headers[ServeHttpServer.TOKEN_HEADER]) {
              TOKEN -> UiBuilderAuthorizationDecision.Authorized("owner")
              else -> UiBuilderAuthorizationDecision.Missing
            }
          },
      )
      .also(ServeHttpServer::start)

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
    client.dispatcher.executorService.shutdown()
    builderDir.deleteRecursively()
  }

  @Test
  fun `the owner receives a server-selected recovery preview`() {
    val (code, body) = get(TOKEN)

    assertEquals(200, code, body)
    assertTrue(body.contains("compose-preview-serve/ui-builder-catalog-recovery/v1"), body)
    assertTrue(body.contains("preview-digest"), body)
    assertEquals("owner", calls.single().actor.actorId)
    assertEquals(
      "stranded-design",
      assertIs<UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade>(calls.single().request)
        .designId,
    )
  }

  @Test
  fun `missing authentication cannot probe recovery`() {
    assertEquals(401, get(null).first)
    assertTrue(calls.isEmpty())
  }

  @Test
  fun `a service refusal keeps its status and reason`() {
    refusal =
      UiBuilderServiceError(
        ServiceErrorCodeV1.CATALOG_UNAVAILABLE,
        "the current catalog is unavailable",
      )

    val (code, body) = get(TOKEN)

    assertEquals(409, code, body)
    assertTrue(body.contains("the current catalog is unavailable"), body)
  }

  @Test
  fun `a stranded design may load the recovery shell without becoming an existence oracle`() {
    val stranded = getPath("/ui-builder/stranded-design", TOKEN)

    assertEquals(200, stranded.first, stranded.second)
    assertTrue(stranded.second.contains("Recovery builder"), stranded.second)
    assertEquals(
      listOf(
        UiBuilderServiceRequest.GetDesignActions::class,
        UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade::class,
      ),
      calls.map { it.request::class },
    )

    calls.clear()
    refusal = UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "not found")
    assertEquals(404, getPath("/ui-builder/private-design", TOKEN).first)
  }

  private fun get(token: String?): Pair<Int, String> {
    return getPath(
      "/api/ui-builder/v1/designs/stranded-design/catalog-recovery",
      token,
    )
  }

  private fun getPath(path: String, token: String?): Pair<Int, String> {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$path")
        .apply { token?.let { header(ServeHttpServer.TOKEN_HEADER, it) } }
        .build()
    client.newCall(request).execute().use {
      return it.code to it.body.string()
    }
  }

  private companion object {
    const val TOKEN = "catalog-recovery-token"
    val SOURCE = CatalogReferenceV1("remote-m3", "old", "old-digest", "runtime")
    val TARGET = CatalogReferenceV1("remote-m3", "current", "current-digest", "runtime")
  }
}
