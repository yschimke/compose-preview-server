package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Android lanes of a shared `/d/<id>` Remote Compose document: drawn on a resident catalog's
 * daemon, which replays the uploaded bytes (`overrides.remoteCompose.documentBase64`) through the
 * chosen player in place of the donor preview's own content.
 *
 * The donor here is a fake catalog host that records what it was asked to render, so the test is
 * about what the server sends and what it refuses to serve, not about a real daemon.
 */
class ServeDocDaemonLaneTest {

  private val docStore = ServeDocStore(ttlSeconds = 60, allowedHosts = emptyList())

  private val renders = CopyOnWriteArrayList<PreviewOverrides>()

  @Volatile private var generation = RenderOutcome.Generation.DAEMON

  private val donorPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())

  /** A catalog with one Remote Compose preview whose daemon has every Android player enabled. */
  private val rcCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(DONOR_ID, "Remote button"))
      override val label = "Remote catalog"

      override fun hasRemoteComposeDoc(previewId: String) = previewId == DONOR_ID

      override fun canRenderOverridesFor(previewId: String) = previewId == DONOR_ID

      override fun enabledRcPlayersFor(previewId: String) =
        listOf(
          RcPlayerBackend.ANDROIDX_EMBEDDED,
          RcPlayerBackend.ANDROIDX_VIEW,
          RcPlayerBackend.CMP_ANDROID,
        )

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        renders += overrides
        return RenderOutcome.Ok(donorPng, generation)
      }

      override fun subscribeStream(
        previewId: String,
        overrides: PreviewOverrides,
        codec: StreamCodec?,
        maxFps: Int?,
        onUnavailable: ((String) -> Unit)?,
        onFrame: (StreamFrameParams) -> Unit,
      ): StreamHandle? = null

      override fun activeStreamCount(): Int = 0

      override fun close() {}
    }

  private val registry =
    ServeSessionRegistry(open = { null }).also {
      it.register("remote-catalog", host = rcCatalog, pinned = true)
    }

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "remote-catalog",
        isPublic = false,
        docStore = docStore,
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun stop() {
    runCatching { server.stop() }
    runCatching { registry.close() }
  }

  private fun url(path: String) =
    "http://127.0.0.1:${server.port}$path${if ('?' in path) "&" else "?"}token=$TOKEN"

  private fun get(path: String) = client.newCall(Request.Builder().url(url(path)).build()).execute()

  private fun upload(bytes: ByteArray): String =
    client
      .newCall(
        Request.Builder()
          .url(url("/docs?name=button.rc"))
          .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
          .build()
      )
      .execute()
      .use { response ->
        assertEquals(201, response.code)
        Json.parseToJsonElement(response.body.string()).jsonObject["url"]!!.jsonPrimitive.content
      }

  @Test
  fun `each android player draws the uploaded bytes on the resident catalog's daemon`() {
    val bytes = ServeDocFixtures.remoteComposeDoc(width = 320, height = 200)
    val path = upload(bytes)

    get(path).use { response ->
      val html = response.body.string()
      for (id in listOf("androidx-embedded", "androidx-view", "cmp-android")) {
        assertTrue(html.contains("data-doc-lane=\"$id\""), "the page offers the $id lane")
      }
    }

    get("$path/render.png?rcPlayer=androidx-view").use { response ->
      assertEquals(200, response.code)
      assertEquals("image/png", response.body.contentType().toString())
      assertEquals(donorPng.toList(), response.body.bytes().toList())
      assertEquals("private, no-store", response.header("Cache-Control"))
    }
    val view = renders.last()
    val rc = view.remoteCompose!!
    // The document itself rides to the daemon, and the donor's own content is never what it draws.
    assertEquals(Base64.getEncoder().encodeToString(bytes), rc.documentBase64)
    assertEquals(RemoteComposePlayerKind.VIEW, rc.player)
    assertEquals(null, rc.playerId)
    // Sized from the document's own header, at density 1 by default.
    assertEquals(320, view.widthPx)
    assertEquals(200, view.heightPx)

    get("$path/render.png?rcPlayer=cmp-android&density=2").use { assertEquals(200, it.code) }
    val cmp = renders.last()
    assertEquals("cmp-android", cmp.remoteCompose!!.playerId)
    assertEquals(640, cmp.widthPx)
    assertEquals(2f, cmp.density)
  }

  @Test
  fun `baked pixels are the donor's, so they are refused rather than served`() {
    val path = upload(ServeDocFixtures.remoteComposeDoc(width = 320, height = 200))
    generation = RenderOutcome.Generation.BAKED
    get("$path/render.png?rcPlayer=androidx-embedded").use { response ->
      assertEquals(503, response.code)
      assertFalse(response.body.bytes().toList() == donorPng.toList())
    }
  }

  @Test
  fun `with no resident catalog the android lanes are neither offered nor drawn`() {
    val path = upload(ServeDocFixtures.remoteComposeDoc(width = 320, height = 200))
    registry.unregister("remote-catalog")
    get(path).use { response ->
      assertFalse(response.body.string().contains("data-doc-lane=\"androidx-view\""))
    }
    get("$path/render.png?rcPlayer=androidx-view").use { assertEquals(503, it.code) }
    assertTrue(renders.isEmpty())
  }

  private companion object {
    const val TOKEN = "doc-daemon-lane"
    const val DONOR_ID = "com.example.RemoteButtonKt.RemoteButton"
  }
}
