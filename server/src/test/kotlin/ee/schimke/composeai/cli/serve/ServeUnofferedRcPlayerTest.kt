package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A `?rcPlayer=` naming an Android player a live catalog preview does not offer — `cmp-android` on
 * a bundle without `rc-player-compose` (remote-widgets, meshcore-mobile's `:wear`),
 * `androidx-embedded` on one without `third-party-rc-embedded-player` (homeassistant-remotecompose)
 * — is a short `409`, never commissioned into the daemon's linkage failure, whose `500` body was a
 * full stack trace.
 */
class ServeUnofferedRcPlayerTest {

  private val liveRenders = CopyOnWriteArrayList<PreviewOverrides>()

  private val livePng = png(3)

  /** The catalog's resident daemon: honours the player override and records what it was asked. */
  private val live =
    object : ServeHost {
      override val previews = listOf(ServePreview(DAEMON_ID, DAEMON_ID))
      override val label = "live"
      override val remoteComposePlayerSelectable = true

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        liveRenders += overrides
        return RenderOutcome.Ok(livePng, RenderOutcome.Generation.DAEMON)
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

  /** The published catalog: a baked PNG and the captured `.rc` document for [PREVIEW_ID]. */
  private fun baked(): ServeBundleHost {
    val dir = Files.createTempDirectory("unoffered-rc").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    File(dir, "previews/$PREVIEW_ID.png").writeBytes(png(2))
    File(dir, "ir").mkdirs()
    File(dir, "ir/$PREVIEW_ID.rc")
      .writeBytes(ServeDocFixtures.remoteComposeDoc(width = 120, height = 60))
    return ServeBundleHost(dir, label = "catalog", isCatalog = true)
  }

  private var server: ServeHttpServer? = null

  private var registry: ServeSessionRegistry? = null

  private fun serve(embeddedPlayer: Boolean = true, cmpAndroidPlayer: Boolean = false) {
    val host =
      ServeCatalogLiveHost(
        alias = mapOf(PREVIEW_ID to DAEMON_ID),
        live = live,
        baked = baked(),
        cmpAndroidPlayerFor = { cmpAndroidPlayer },
        embeddedPlayerFor = { embeddedPlayer },
      )
    val sessions =
      ServeSessionRegistry(open = { null }).also {
        it.register("catalog", host = host, pinned = true)
        registry = it
      }
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = TOKEN,
          sessions = sessions,
          defaultSessionId = "catalog",
          isPublic = false,
        )
        .also { it.start() }
  }

  @AfterTest
  fun stop() {
    runCatching { server?.stop() }
    runCatching { registry?.close() }
  }

  private val client = OkHttpClient()

  private fun get(path: String): Pair<Int, String> =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server!!.port}$path${if ('?' in path) "&" else "?"}token=$TOKEN")
          .build()
      )
      .execute()
      .use { it.code to it.body.string() }

  @Test
  fun `cmp-android on a bundle without the CMP player is a short 409, not a daemon failure`() {
    serve(cmpAndroidPlayer = false)
    val (code, body) = get("/catalog/render/$PREVIEW_ID.png?rcPlayer=cmp-android")
    assertEquals(409, code, body)
    assertTrue(body.startsWith("rcPlayer=cmp-android is not available for this preview"), body)
    assertTrue("androidx-view" in body, "names what is offered: $body")
    assertFalse("\tat " in body || "Exception" in body, "no stack trace: $body")
    assertTrue(liveRenders.isEmpty(), "the daemon was never asked")
  }

  @Test
  fun `androidx-embedded on a bundle without the embedded player is refused the same way`() {
    serve(embeddedPlayer = false, cmpAndroidPlayer = true)
    val (code, body) = get("/catalog/render/$PREVIEW_ID.png?rcPlayer=androidx-embedded")
    assertEquals(409, code, body)
    assertTrue("cmp-android" in body && "androidx-view" in body, body)
    assertTrue(liveRenders.isEmpty())
    // The players it does carry still reach the daemon.
    assertEquals(200, get("/catalog/render/$PREVIEW_ID.png?rcPlayer=cmp-android").first)
    assertEquals(1, liveRenders.size)
  }

  @Test
  fun `an offered player still renders on the catalog daemon`() {
    serve()
    val (code, _) = get("/catalog/render/$PREVIEW_ID.png?rcPlayer=androidx-view")
    assertEquals(200, code)
    assertEquals(
      RcPlayerBackend.ANDROIDX_VIEW.playerKind,
      liveRenders.single().remoteCompose?.player,
    )
  }

  private fun png(size: Int): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private companion object {
    const val TOKEN = "unoffered-rc"
    const val PREVIEW_ID = "remote-card"
    const val DAEMON_ID = "com.example.CardsKt.RemoteCard"
  }
}
