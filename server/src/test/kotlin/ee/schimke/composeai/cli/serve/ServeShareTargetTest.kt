package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The installed app's share target ([ServeShareTarget]): a shared screenshot becomes a bug-report
 * capture, a shared link to this server opens it, and anything else prefills the report.
 */
class ServeShareTargetTest {

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private val self = URI("http://127.0.0.1:8080")

  private fun sameOrigin(uri: URI) = uri.host == self.host && uri.port == self.port

  @Test
  fun `an image is parked for the report, whatever its part claimed to be`() {
    val image = png()
    val outcome =
      ServeShareTarget.outcome(
        ServeShareTarget.Fields(image = image, text = "button is the wrong colour"),
        now = 0,
        ::sameOrigin,
      )
    val report = assertIs<ServeShareTarget.Outcome.Report>(outcome)
    assertEquals("image/png", report.shared.imageType)
    assertEquals("button is the wrong colour", report.shared.text)

    // Not an image by its bytes: treated as a text-only share, never served back as an image.
    val junk =
      ServeShareTarget.outcome(
        ServeShareTarget.Fields(image = "<svg/>".toByteArray(), text = "hi"),
        now = 0,
        ::sameOrigin,
      )
    assertNull(assertIs<ServeShareTarget.Outcome.Report>(junk).shared.image)
  }

  @Test
  fun `a link to this server opens it, and a link anywhere else prefills the report`() {
    val local =
      ServeShareTarget.outcome(
        ServeShareTarget.Fields(text = "look at http://127.0.0.1:8080/p/Button?theme=dark please"),
        now = 0,
        ::sameOrigin,
      )
    assertEquals(ServeShareTarget.Outcome.Open("/p/Button?theme=dark"), local)

    val foreign =
      ServeShareTarget.outcome(
        ServeShareTarget.Fields(url = "https://evil.example/p/Button"),
        now = 0,
        ::sameOrigin,
      )
    assertEquals(
      "https://evil.example/p/Button",
      assertIs<ServeShareTarget.Outcome.Report>(foreign).shared.text,
    )
    assertEquals(
      ServeShareTarget.Outcome.Empty,
      ServeShareTarget.outcome(ServeShareTarget.Fields(), now = 0, ::sameOrigin),
    )
    // Never a scheme-relative redirect, and never back into the share route.
    assertNull(ServeShareTarget.localPath("http://127.0.0.1:8080//evil.example/", ::sameOrigin))
    assertNull(ServeShareTarget.localPath("http://127.0.0.1:8080/report-bug/share", ::sameOrigin))
    assertNull(ServeShareTarget.localPath("javascript:alert(1)", ::sameOrigin))
  }

  @Test
  fun `multipart bodies are parsed field by field`() {
    val image = png()
    val body =
      MultipartBody.Builder("XyZ")
        .setType(MultipartBody.FORM)
        .addFormDataPart("title", "Bug")
        .addFormDataPart("text", "the chip clips")
        .addFormDataPart("image", "shot.png", image.toRequestBody("image/png".toMediaType()))
        .build()
    val buffer = okio.Buffer().also { body.writeTo(it) }
    val fields =
      assertNotNull(
        ServeShareTarget.parseMultipart(
          buffer.readByteArray(),
          "multipart/form-data; boundary=XyZ",
        )
      )
    assertEquals("Bug", fields.title)
    assertEquals("the chip clips", fields.text)
    assertContentEquals(image, fields.image)
    assertNull(ServeShareTarget.parseMultipart(ByteArray(0), "multipart/form-data"))
  }

  @Test
  fun `the parking lot is bounded and expires`() {
    var now = 0L
    val store = ServeShareTarget.Store { now }
    val ids =
      (0 until ServeShareTarget.MAX_ENTRIES + 2).map {
        now += 1
        store.put(ServeShareTarget.Shared(null, null, "t$it", now))
      }
    assertNull(store.get(ids.first()), "the oldest is evicted past the cap")
    assertNotNull(store.get(ids.last()))
    assertNull(store.get("../../etc/passwd"))
    now += ServeShareTarget.TTL_MILLIS + 1
    assertNull(store.get(ids.last()), "expired")
  }

  // --- the routes, against a live server ---

  private var registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client =
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

  private fun newServer(public: Boolean = true, token: String = "unused"): ServeHttpServer {
    val dir = Files.createTempDirectory("share-target").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/Red.png").writeBytes(png())
    registry.register(
      "default-mod",
      host = ServeBundleHost(dir, label = "default-mod", declaredBaked = listOf("Red")),
      pinned = true,
    )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "default-mod",
        isPublic = public,
      )
      .also { it.start() }
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  private fun share(
    parts: MultipartBody.Builder.() -> Unit,
    headers: Map<String, String> = emptyMap(),
    query: String = "",
  ): okhttp3.Response {
    val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply(parts).build()
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}/report-bug/share$query")
        .post(body)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .build()
    return client.newCall(req).execute()
  }

  private fun get(path: String): Pair<Int, ByteArray> =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${server!!.port}$path").build())
      .execute()
      .use { it.code to it.body.bytes() }

  @Test
  fun `a shared screenshot lands on the report page, which can fetch it back`() {
    server = newServer()
    val image = png()
    val location =
      share({
          addFormDataPart("text", "the switch thumb is clipped")
          addFormDataPart("image", "s.png", image.toRequestBody("image/png".toMediaType()))
        })
        .use {
          assertEquals(303, it.code)
          assertNotNull(it.header("Location"))
        }
    assertTrue(location.startsWith("/report-bug?shared="), location)
    val id = location.substringAfter("shared=")

    val (imageCode, bytes) = get("/report-bug/shared/$id")
    assertEquals(200, imageCode)
    assertContentEquals(image, bytes)

    val (pageCode, page) = get(location)
    assertEquals(200, pageCode)
    assertTrue(page.decodeToString().contains("the switch thumb is clipped"), "prefilled body")

    assertEquals(404, get("/report-bug/shared/${"0".repeat(32)}").first)
  }

  @Test
  fun `a shared link to this server opens it`() {
    server = newServer()
    val origin = "http://127.0.0.1:${server!!.port}"
    share({ addFormDataPart("url", "$origin/p/Red") }).use {
      assertEquals(303, it.code)
      assertEquals("/p/Red", it.header("Location"))
    }
  }

  @Test
  fun `a cross-site post and a token-less share on a gated box are refused`() {
    server = newServer()
    share({ addFormDataPart("text", "x") }, headers = mapOf("Sec-Fetch-Site" to "cross-site")).use {
      assertEquals(403, it.code)
    }
    server!!.stop()
    registry.close()
    registry = ServeSessionRegistry(open = { null })

    server = newServer(public = false, token = "secret")
    share({ addFormDataPart("text", "x") }).use { assertEquals(404, it.code) }
    share({ addFormDataPart("text", "x") }, query = "?token=secret").use {
      assertEquals(303, it.code)
      assertTrue(it.header("Location")!!.contains("token=secret"), "the redirect stays navigable")
    }
  }
}
