package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Starting a design from a seed another site prepared (`--ui-builder-import-origins`): only from a
 * listed origin, only as a signed-in person confirming on a page this server served, and always as
 * that person's own private design.
 */
class ServeUiBuilderImportRoutesTest {
  private val created = CopyOnWriteArrayList<String>()
  private val visibility = ConcurrentHashMap<String, UiBuilderDefaultVisibility>()
  private val owners = ConcurrentHashMap<String, String>()

  private val catalog =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("m3", "source", "remote-m3", "candidate", "candidate").build(),
        emptyList(),
      )
      .build()

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.OpenDesign ->
            UiBuilderServiceResponse.Error(
              UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "missing")
            )
          is UiBuilderServiceRequest.ListCatalogs ->
            UiBuilderServiceResponse.Catalogs(listOf(catalog))
          is UiBuilderServiceRequest.CreateDesign -> {
            visibility[request.document.id] =
              coroutineContext[CreationVisibility]?.value ?: UiBuilderDefaultVisibility.PUBLIC
            owners[request.document.id] = call.actor.actorId
            created += request.document.id
            UiBuilderServiceResponse.Catalogs(emptyList())
          }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val authorization = ServeUiBuilderAuthorization { call, _, _ ->
    when (val actor = call.request.headers["X-Test-Actor"]) {
      null -> UiBuilderAuthorizationDecision.Missing
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }

  private fun seed(id: String, extra: String = "") =
    """
    {"schema":"compose-ui-builder-document/v1-candidate","id":"$id","title":"Living room",
     "revision":0,$extra
     "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
     "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
     "environment":{"widthDp":360,"heightDp":160,"density":2.0,"theme":"light","locale":"en-US",
     "fontScale":1.0,"layoutDirection":"ltr"},
     "stateVariables":{},"roots":[],"nodes":{}}
    """
      .trimIndent()

  private val seeds =
    mapOf(
      "https://ha.example/seeds/card-tile-1.json" to seed("card-tile-1"),
      "https://ha.example/seeds/homed.json" to
        seed(
          "homed",
          "\"home\":{\"kind\":\"server\",\"url\":\"https://other.example\",\"designId\":\"homed\"},",
        ),
    )
  private val fetched = CopyOnWriteArrayList<String>()

  private val builderDir =
    Files.createTempDirectory("serve-ui-builder-import").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html").writeText("<!doctype html><title>Compose UI builder</title>")
    }

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        canonicalOrigin = "https://preview.example",
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderDir = builderDir,
        uiBuilderCatalogs = setOf("remote-m3"),
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
        uiBuilderImportOrigins = listOf("https://ha.example"),
        uiBuilderImportFetch = { uri ->
          fetched += uri.toString()
          seeds[uri.toString()]?.let(ServeUiBuilderImport::decode)
            ?: ServeUiBuilderImport.Fetched.Refused("the seed document answered 404")
        },
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun confirm(seedUrl: String, actor: String? = "github:octocat"): Pair<Int, String> {
    val builder = Request.Builder().url(url("/ui-builder/designs/import?url=$seedUrl"))
    actor?.let { builder.header("X-Test-Actor", it) }
    return client.newCall(builder.get().build()).execute().use { it.code to it.body.string() }
  }

  private fun import(
    seedUrl: String,
    actor: String? = "github:octocat",
    origin: String = "http://127.0.0.1:${server.port}",
  ): Pair<Int, String?> {
    val builder =
      Request.Builder()
        .url(url("/ui-builder/designs/import"))
        .header("Origin", origin)
        .post(FormBody.Builder().add("url", seedUrl).build())
    actor?.let { builder.header("X-Test-Actor", it) }
    return client.newCall(builder.build()).execute().use { it.code to it.header("Location") }
  }

  @Test
  fun `a signed-in person confirms, and the seed becomes their own private design`() {
    val (code, page) = confirm("https://ha.example/seeds/card-tile-1.json")
    assertEquals(200, code, page)
    assertTrue("Living room" in page && "https://ha.example" in page, page)
    assertTrue("action=\"/ui-builder/designs/import\"" in page, page)
    assertTrue(created.isEmpty(), "looking at the confirmation creates nothing")

    assertEquals(
      303 to "/ui-builder/card-tile-1",
      import("https://ha.example/seeds/card-tile-1.json"),
    )
    assertEquals(listOf("card-tile-1"), created)
    assertEquals("github:octocat", owners["card-tile-1"])
    assertEquals(UiBuilderDefaultVisibility.PRIVATE, visibility["card-tile-1"])
  }

  @Test
  fun `a design whose id is import still opens in the editor`() {
    val (code, page) =
      client.newCall(Request.Builder().url(url("/ui-builder/import")).build()).execute().use {
        it.code to it.body.string()
      }
    assertEquals(200, code, page)
    assertTrue("Compose UI builder" in page, page)
    assertTrue(fetched.isEmpty())
  }

  @Test
  fun `a seed that stalls or overflows is given up on`() {
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
      val stalled = thread {
        socket.accept().use { conn ->
          conn.getInputStream().read(ByteArray(4096))
          // Headers, then a body that never arrives.
          conn.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n{".toByteArray())
            flush()
          }
          Thread.sleep(5_000)
        }
      }
      val started = System.nanoTime()
      val result =
        ServeUiBuilderImport.fetch(
          URI("http://127.0.0.1:${socket.localPort}/seed.json"),
          Duration.ofMillis(500),
        )
      assertIs<ServeUiBuilderImport.Fetched.Refused>(result)
      assertTrue(System.nanoTime() - started < 4_000_000_000L, "the deadline covers the body")
      stalled.interrupt()
    }
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
      thread {
        socket.accept().use { conn ->
          conn.getInputStream().read(ByteArray(4096))
          val size = ServeUiBuilderImport.MAX_BYTES + 1
          conn.getOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\n\r\n".toByteArray())
            runCatching { write(ByteArray(size) { ' '.code.toByte() }) }
            flush()
          }
        }
      }
      val result = ServeUiBuilderImport.fetch(URI("http://127.0.0.1:${socket.localPort}/seed.json"))
      assertIs<ServeUiBuilderImport.Fetched.Refused>(result)
      assertTrue("larger" in result.reason, result.reason)
    }
  }

  @Test
  fun `only listed origins are fetched`() {
    assertEquals(400, confirm("https://evil.example/seeds/card-tile-1.json").first)
    assertEquals(400, confirm("http://ha.example/seeds/card-tile-1.json").first)
    assertEquals(400, import("https://evil.example/seeds/card-tile-1.json").first)
    assertTrue(fetched.isEmpty(), "nothing off the list may be fetched: $fetched")
    assertTrue(created.isEmpty())
  }

  @Test
  fun `importing needs a signed-in person and a form this server served`() {
    assertEquals(401, confirm("https://ha.example/seeds/card-tile-1.json", actor = null).first)
    assertEquals(401, import("https://ha.example/seeds/card-tile-1.json", actor = null).first)
    assertEquals(
      403,
      import("https://ha.example/seeds/card-tile-1.json", origin = "https://ha.example").first,
    )
    assertTrue(created.isEmpty())
  }

  @Test
  fun `a seed that is missing or already belongs somewhere is refused`() {
    assertEquals(502, import("https://ha.example/seeds/missing.json").first)
    assertEquals(502, import("https://ha.example/seeds/homed.json").first)
    assertTrue(created.isEmpty())
  }

  @Test
  fun `origins are https and nothing more`() {
    assertEquals("https://ha.coo.ee", ServeUiBuilderImport.normalizeOrigin("https://ha.coo.ee/"))
    assertEquals("https://ha.coo.ee", ServeUiBuilderImport.normalizeOrigin("https://HA.coo.ee:443"))
    assertEquals(
      "https://ha.coo.ee:8443",
      ServeUiBuilderImport.normalizeOrigin("https://ha.coo.ee:8443"),
    )
    assertNull(ServeUiBuilderImport.normalizeOrigin("http://ha.coo.ee"))
    assertNull(ServeUiBuilderImport.normalizeOrigin("https://ha.coo.ee/seeds"))
    assertNull(ServeUiBuilderImport.normalizeOrigin("https://user@ha.coo.ee"))
    assertIs<URI>(
      ServeUiBuilderImport.allowed("https://ha.coo.ee/s/x.json", listOf("https://ha.coo.ee"))
    )
    assertNull(
      ServeUiBuilderImport.allowed("https://ha.coo.ee.evil/x", listOf("https://ha.coo.ee"))
    )
    assertNull(ServeUiBuilderImport.allowed("https://ha.coo.ee/x", emptyList()))
  }

  @Test
  fun `a seed is a design document`() {
    assertIs<ServeUiBuilderImport.Fetched.Document>(ServeUiBuilderImport.decode(seed("ok")))
    assertIs<ServeUiBuilderImport.Fetched.Refused>(
      ServeUiBuilderImport.decode("{\"schema\":\"x\"}")
    )
    assertIs<ServeUiBuilderImport.Fetched.Refused>(ServeUiBuilderImport.decode("not json"))
    assertIs<DesignDocumentV1>(
      (ServeUiBuilderImport.decode(seed("ok")) as ServeUiBuilderImport.Fetched.Document).document
    )
  }
}
