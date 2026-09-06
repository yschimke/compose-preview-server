package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * The asset routes beside the design API: raw bytes in with a `PUT`, the same bytes out of a `GET`,
 * and the design's `assets` map pinned in between — yschimke/compose-preview-server#478's "one way
 * to fill it", over HTTP.
 */
class ServeUiBuilderAssetRoutesTest {
  @TempDir lateinit var stateDirectory: Path

  private lateinit var service: PersistentUiBuilderService
  private lateinit var server: ServeHttpServer
  private val registry = ServeSessionRegistry(open = { null })
  private val client = OkHttpClient()

  @BeforeTest
  fun start() {
    service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory.resolve("state")),
        catalogs =
          CurrentM3UiBuilderCatalogExecutor(
            catalogSystemIds = setOf(CATALOG_SYSTEM_ID),
            exportCapabilities =
              ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1(
                composeCode = false,
                svg = false,
                png = false,
              ),
          ),
        exporter = { error("no export in this test") },
        assets = FileUiBuilderAssetStore(stateDirectory.resolve("assets")),
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(
      runBlocking {
        service.execute(
          UiBuilderServiceCall(OPERATOR, UiBuilderServiceRequest.CreateDesign(document()))
        )
      }
    )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service,
          uiBuilderAssets = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
  }

  @AfterTest
  fun stop() {
    server.stop()
    registry.close()
  }

  @Test
  fun `a put stores the picture, pins the binding, and a get hands the bytes back`() {
    val put = put("hero", PNG, OPERATOR_TOKEN)
    assertEquals(200, put.code, put.body)
    val stored = Json.parseToJsonElement(put.body).jsonObject
    assertEquals("hero", stored.getValue("assetKey").jsonPrimitive.content)
    val outcome = stored.getValue("outcome").jsonObject
    assertEquals("accepted", outcome.getValue("type").jsonPrimitive.content)
    assertEquals(1, outcome.getValue("committedRevision").jsonPrimitive.content.toLong())

    val document = currentDocument()
    val binding = document.assets.getValue("hero")
    assertEquals("image/png", binding.mediaType)
    assertIs<UploadedAssetSourceV1>(binding.source)
    assertEquals(1, document.revision)

    val get = get("hero", OPERATOR_TOKEN)
    assertEquals(200, get.code)
    assertEquals("image/png", get.contentType)
    assertContentEquals(PNG, get.bytes)
    assertEquals("\"${binding.contentDigest}\"", get.etag)

    val replay = put("hero", PNG, OPERATOR_TOKEN)
    assertEquals(200, replay.code, replay.body)
    assertTrue(replay.body.contains("\"idempotentReplay\":true"), replay.body)
    assertEquals(1, currentDocument().revision)
  }

  @Test
  fun `bytes that are not an image are refused as the design's fault, not the host's`() {
    val response = put("hero", "<svg/>".encodeToByteArray(), OPERATOR_TOKEN)
    assertEquals(422, response.code, response.body)
    assertTrue(response.body.contains("PNG, JPEG, GIF or WebP"), response.body)
    assertTrue(currentDocument().assets.isEmpty())
  }

  @Test
  fun `no credential is a 401, a missing design a 404, and a missing key a 404`() {
    assertEquals(401, put("hero", PNG, token = null).code)
    assertEquals(401, get("hero", token = null).code)
    assertEquals(404, put("hero", PNG, OPERATOR_TOKEN, designId = "nope").code)
    assertEquals(404, get("hero", OPERATOR_TOKEN).code)
  }

  private class Response(
    val code: Int,
    val body: String,
    val bytes: ByteArray,
    val contentType: String?,
    val etag: String?,
  )

  private fun put(
    assetKey: String,
    bytes: ByteArray,
    token: String?,
    designId: String = DESIGN_ID,
  ): Response =
    exchange(
      Request.Builder()
        .url(url(designId, assetKey))
        .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
        .also { if (token != null) it.header(ServeHttpServer.TOKEN_HEADER, token) }
        .build()
    )

  private fun get(assetKey: String, token: String?, designId: String = DESIGN_ID): Response =
    exchange(
      Request.Builder()
        .url(url(designId, assetKey))
        .get()
        .also { if (token != null) it.header(ServeHttpServer.TOKEN_HEADER, token) }
        .build()
    )

  private fun exchange(request: Request): Response =
    client.newCall(request).execute().use {
      val bytes = it.body.bytes()
      Response(
        it.code,
        bytes.toString(Charsets.UTF_8),
        bytes,
        it.header("Content-Type")?.substringBefore(';'),
        it.header("ETag"),
      )
    }

  private fun url(designId: String, assetKey: String) =
    "http://127.0.0.1:${server.port}/api/ui-builder/v1/designs/$designId/assets/$assetKey"

  private fun currentDocument(): DesignDocumentV1 =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        runBlocking {
          service.execute(
            UiBuilderServiceCall(OPERATOR, UiBuilderServiceRequest.OpenDesign(DESIGN_ID))
          )
        }
      )
      .snapshot
      .state
      .document

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Chat",
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
        ),
      roots = emptyList(),
      nodes = emptyMap(),
    )

  private companion object {
    const val OPERATOR_TOKEN = "asset-routes-operator-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val DESIGN_ID = "chat"
    val OPERATOR = AuthenticatedUiBuilderActor("operator")

    /** A PNG signature and an IHDR chunk: what the service sniffs, and all it needs. */
    val PNG: ByteArray =
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 0x0D) +
        "IHDR".encodeToByteArray() +
        byteArrayOf(0, 0, 0, 40, 0, 0, 0, 40) +
        byteArrayOf(8, 6, 0, 0, 0) +
        ByteArray(4)
  }
}
