package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.daemon.protocol.UiMode
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Android players of a baked-only catalog preview (a bundle with `.rc` documents and baked
 * PNGs, no daemon of its own): drawn on another catalog's resident daemon, which replays the
 * preview's captured document (`overrides.remoteCompose.documentBase64`) exactly as for a shared
 * `/d/<id>` document ([ServeDocDaemonLaneTest]).
 *
 * Both hosts are fakes that record what they were asked to render, so the test is about what the
 * server routes where and what the viewer offers, not about a real daemon.
 */
class ServeDonorRcPlayerLaneTest {

  private val donorRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private val ownRenders = CopyOnWriteArrayList<PreviewOverrides>()

  @Volatile private var daemonRunning = true

  private val donorPng = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte())

  private val bakedPng = byteArrayOf(0x89.toByte(), 'B'.code.toByte(), 'K'.code.toByte())

  private val catalogDoc = ServeDocFixtures.remoteComposeDoc(width = 120, height = 60)

  /** A catalog with one Remote Compose preview whose daemon has every Android player enabled. */
  private val donorCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(DONOR_ID, "Remote button"))
      override val label = "Remote catalog"
      override val daemonStarted: Boolean
        get() = daemonRunning

      override fun hasRemoteComposeDoc(previewId: String) = previewId == DONOR_ID

      override fun canRenderOverridesFor(previewId: String) = previewId == DONOR_ID

      override fun enabledRcPlayersFor(previewId: String) =
        listOf(
          RcPlayerBackend.ANDROIDX_EMBEDDED,
          RcPlayerBackend.ANDROIDX_VIEW,
          RcPlayerBackend.CMP_ANDROID,
        )

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        donorRenders += overrides
        return RenderOutcome.Ok(donorPng, RenderOutcome.Generation.DAEMON)
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

  /** A published bundle: the captured document, its render spec and a baked PNG, no daemon. */
  private val bakedOnlyCatalog =
    object : ServeHost {
      override val previews = listOf(ServePreview(EXAMPLE_ID, "Remote example"))
      override val label = "Remote Compose examples"

      override fun remoteComposeDoc(previewId: String) = catalogDoc.takeIf {
        previewId == EXAMPLE_ID
      }

      override fun remoteComposeRenderSpec(previewId: String) =
        RcJvmRenderSpec(widthPx = 300, heightPx = 150, density = 2.5f).takeIf {
          previewId == EXAMPLE_ID
        }

      override fun bakedRender(previewId: String, overrides: PreviewOverrides) =
        RenderOutcome.Ok(bakedPng, RenderOutcome.Generation.BAKED)

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        ownRenders += overrides
        return RenderOutcome.Ok(bakedPng, RenderOutcome.Generation.BAKED)
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
      it.register("remote-catalog", host = donorCatalog, pinned = true)
      it.register("examples", host = bakedOnlyCatalog, pinned = true)
    }

  private val server: ServeHttpServer by lazy {
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = TOKEN,
        sessions = registry,
        defaultSessionId = "examples",
        isPublic = false,
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

  @Test
  fun `a baked-only preview's android player replays its document on a resident daemon`() {
    get("/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-embedded").use { response ->
      assertEquals(200, response.code)
      assertEquals(donorPng.toList(), response.body.bytes().toList())
      assertEquals(
        RenderOutcome.Generation.DAEMON.wire,
        response.header(ServeHttpServer.GENERATION_HEADER),
      )
    }
    val embedded = donorRenders.single()
    val rc = embedded.remoteCompose!!
    // The catalog's own document rides to the donor, not the donor preview's content.
    assertEquals(Base64.getEncoder().encodeToString(catalogDoc), rc.documentBase64)
    assertEquals(RcPlayerBackend.ANDROIDX_EMBEDDED.playerKind, rc.player)
    // Sized from the catalog's render spec, not the document header.
    assertEquals(300, embedded.widthPx)
    assertEquals(150, embedded.heightPx)
    assertEquals(2.5f, embedded.density)
    // The preview's own mode (light) unless the viewer asks for another.
    assertEquals(UiMode.LIGHT, embedded.uiMode)
    assertTrue(ownRenders.isEmpty(), "the baked-only host was never asked to render")

    get("/examples/render/$EXAMPLE_ID.png?rcPlayer=cmp-android&uiMode=dark").use {
      assertEquals(200, it.code)
    }
    val cmp = donorRenders.last()
    assertEquals("cmp-android", cmp.remoteCompose!!.playerId)
    assertEquals(UiMode.DARK, cmp.uiMode)
  }

  @Test
  fun `with no resident donor the existing lane answers`() {
    daemonRunning = false
    get("/examples/render/$EXAMPLE_ID.png?rcPlayer=androidx-view").use { response ->
      assertNotEquals(200, response.code)
      assertNotEquals(
        RenderOutcome.Generation.DAEMON.wire,
        response.header(ServeHttpServer.GENERATION_HEADER),
      )
    }
    assertTrue(donorRenders.isEmpty(), "nothing reached the donor's render path")
  }

  @Test
  fun `the viewer enables the android players only while a donor is resident`() {
    get("/examples/p/$EXAMPLE_ID").use { response ->
      val html = response.body.string()
      for (id in listOf("androidx-embedded", "androidx-view", "cmp-android")) {
        assertTrue(html.contains("<option value=\"rc:$id\">"), "$id is offered")
      }
      // Offered, but never opened on: a page view must not commission a borrowed render.
      assertFalse(html.contains("data-rc-default=\"androidx-embedded\""))
    }

    daemonRunning = false
    get("/examples/p/$EXAMPLE_ID").use { response ->
      val html = response.body.string()
      for (id in listOf("androidx-embedded", "androidx-view", "cmp-android")) {
        assertTrue(html.contains("<option value=\"rc:$id\" disabled>"), "$id is greyed out")
      }
    }
    assertTrue(donorRenders.isEmpty() && ownRenders.isEmpty())
  }

  private companion object {
    const val TOKEN = "donor-rc-lane"
    const val DONOR_ID = "com.example.RemoteButtonKt.RemoteButton"
    const val EXAMPLE_ID = "com.example.ExamplesKt.RemoteExample"
  }
}
