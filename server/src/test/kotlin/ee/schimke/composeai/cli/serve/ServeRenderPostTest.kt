package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration
import ee.schimke.composeai.data.overrides.PreviewOverrideType
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `POST /{system}/render/{name}` — the render route with its parameters in the body, for a knob
 * value no URL can carry — and the A2UI playground page that drives it.
 *
 * The property that matters is that a POST is the GET, not a second render path: same bytes for the
 * same parameters, same LIVE refusal, and only the body cap is its own.
 */
class ServeRenderPostTest {

  private val operatorToken = "operator-token"
  private val previewId = "ee.schimke.a2uicatalog.playground.PlaygroundKt.A2uiDocumentPreview"
  private val renders = AtomicInteger()

  private val documentKnob =
    PreviewOverrideDeclaration(
      key = "document",
      type = PreviewOverrideType.STRING,
      label = "Document",
      default =
        PreviewOverrideValue.StringValue(
          "{\"createSurface\":{\"surfaceId\":\"main\"}}\n" +
            "{\"updateComponents\":{\"surfaceId\":\"main\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Text\",\"text\":\"<Hi & bye>\"}]}}"
        ),
    )

  /** Echoes the overrides it was asked for, so equal bytes mean equal parsed parameters. */
  private fun host(label: String, overrides: List<PreviewOverrideDeclaration>) =
    object : ServeHost {
      override val previews =
        listOf(ServePreview(previewId, "A2UI document", overrides = overrides))
      override val label = label
      override val canRenderOverrides = true

      override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
        renders.incrementAndGet()
        return RenderOutcome.Ok(overrides.toString().encodeToByteArray())
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

  private val registry = ServeSessionRegistry(open = { null })
  private val grants =
    ServeAgentGrantStore(maxScope = AgentGrantScope.PLAYGROUND, maxGrantTtlSeconds = 3600)

  private val server: ServeHttpServer by lazy {
    registry.register("a2ui-catalog", host = host("A2UI", listOf(documentKnob)), pinned = true)
    registry.register("plain", host = host("Plain", emptyList()), pinned = true)
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = operatorToken,
        sessions = registry,
        defaultSessionId = "a2ui-catalog",
        isPublic = false,
        agentGrants = grants,
        machineAuthorization =
          ServeMachineAuthorization(operatorToken, githubAuth = null, agentGrants = grants),
      )
      .also { it.start() }
  }

  private val client = OkHttpClient()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private data class Reply(val code: Int, val bytes: ByteArray, val headers: okhttp3.Headers) {
    val text: String
      get() = bytes.decodeToString()
  }

  private fun call(
    path: String,
    body: String? = null,
    contentType: String = "application/json",
    token: String? = operatorToken,
  ): Reply {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server.port}$path")
        .apply {
          token?.let { header(ServeHttpServer.TOKEN_HEADER, it) }
          body?.let { post(it.toRequestBody(contentType.toMediaType())) }
        }
        .build()
    client.newCall(request).execute().use {
      return Reply(it.code, it.body.bytes(), it.headers)
    }
  }

  private fun previewGrant(): String {
    val request = grants.openRequest("post test", "127.0.0.1", AgentGrantScope.PREVIEW, 600)!!
    return grants.approve(request.id, "operator", AgentGrantScope.PREVIEW, 600)!!.token
  }

  private val document =
    """
    {"createSurface":{"surfaceId":"main","catalogId":"basic"}}
    {"updateComponents":{"surfaceId":"main","components":[{"id":"root","component":"Text","text":"a&b=c"}]}}
    """
      .trimIndent()

  private val encoded = URLEncoder.encode(document, Charsets.UTF_8)

  @Test
  fun `a knob body renders the same bytes as the same knob in the query`() {
    val get = call("/a2ui-catalog/render/$previewId.png?knob.document=$encoded")
    assertEquals(200, get.code, get.text)
    assertTrue(get.text.contains("a&b=c"), "the document reached the renderer: ${get.text}")

    val json =
      call(
        "/a2ui-catalog/render/$previewId.png",
        buildJsonObject { put("knob.document", JsonPrimitive(document)) }.toString(),
      )
    assertEquals(200, json.code, json.text)
    assertContentEquals(get.bytes, json.bytes)
    assertEquals(get.headers["Content-Type"], json.headers["Content-Type"])

    val form =
      call(
        "/a2ui-catalog/render/$previewId.png",
        "knob.document=$encoded",
        contentType = "application/x-www-form-urlencoded",
      )
    assertEquals(200, form.code, form.text)
    assertContentEquals(get.bytes, form.bytes)
  }

  @Test
  fun `a number or boolean in the body is spelled as the query would spell it`() {
    val get = call("/a2ui-catalog/render/$previewId.png?fontScale=1.5")
    val post = call("/a2ui-catalog/render/$previewId.png", """{"fontScale":1.5}""")
    assertEquals(200, post.code, post.text)
    assertContentEquals(get.bytes, post.bytes)
    assertEquals(400, call("/a2ui-catalog/render/$previewId.png", """{"a":[1]}""").code)
    assertEquals(400, call("/a2ui-catalog/render/$previewId.png", "not json").code)
    assertEquals(
      415,
      call("/a2ui-catalog/render/$previewId.png", "x", contentType = "text/plain").code,
    )
  }

  @Test
  fun `a body over the cap is refused before anything renders`() {
    val before = renders.get()
    val padding = "x".repeat((ServeHttpServer.MAX_RENDER_BODY_BYTES + 1).toInt())
    val reply = call("/a2ui-catalog/render/$previewId.png", """{"knob.document":"$padding"}""")
    assertEquals(413, reply.code)
    assertEquals(before, renders.get())
  }

  @Test
  fun `a grant below live is refused exactly as the GET refuses it`() {
    val preview = previewGrant()
    val get = call("/a2ui-catalog/render/$previewId.png?knob.document=$encoded", token = preview)
    assertEquals(403, get.code)
    val post =
      call(
        "/a2ui-catalog/render/$previewId.png",
        """{"knob.document":"x"}""",
        token = preview,
      )
    assertEquals(403, post.code)
    assertEquals(get.text, post.text)
    // …and no credential at all is the token gate's 404, before any body is read.
    assertEquals(404, call("/a2ui-catalog/render/$previewId.png", "{}", token = null).code)
  }

  @Test
  fun `the playground page edits the declared default and posts to the render route`() {
    val page = call("/a2ui-catalog/a2ui")
    assertEquals(200, page.code, page.text)
    val html = page.text
    assertTrue(html.contains("<textarea id=\"a2ui-source\""), html)
    // The default document, escaped once, verbatim inside the textarea.
    assertTrue(html.contains("&lt;Hi &amp; bye&gt;"), html)
    assertTrue(html.contains("/a2ui-catalog/render/$previewId.png"), html)
    assertTrue(html.contains("href=\"/a2ui-catalog/p/$previewId"), html)
    assertTrue(html.contains("\"knob.document\""), html)
  }

  @Test
  fun `a catalog without a document knob has no playground`() {
    assertEquals(404, call("/plain/a2ui").code)
  }

  @Test
  fun `the viewer edits a long string knob in a textarea`() {
    val viewer = call("/a2ui-catalog/p/$previewId").text
    assertTrue(viewer.contains("<textarea class=\"cp-knob\" data-knob-key=\"document\""), viewer)
    assertFalse(
      viewer.contains("<input type=\"text\" class=\"cp-knob\" data-knob-key=\"document\"")
    )
  }
}
