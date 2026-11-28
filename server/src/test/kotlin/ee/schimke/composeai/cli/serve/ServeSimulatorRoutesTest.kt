package ee.schimke.composeai.cli.serve

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.io.File
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.jupiter.api.io.TempDir

/**
 * The simulator's routes over real HTTP and a real socket, against [FakeSimulatorRuntime]: who may
 * register and start what, phone / watch / paired sessions, the command policy as a client meets
 * it, embeds, and frames for each device of a pair.
 */
class ServeSimulatorRoutesTest {
  @TempDir lateinit var dir: Path
  private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
  private val runtime = FakeSimulatorRuntime()
  private var server: EmbeddedServer<*, *>? = null
  private var simulator: ServeSimulator? = null
  private var port = 0
  private var signedIn: String? = null

  @AfterTest
  fun tearDown() {
    simulator?.close()
    server?.stop(100, 500)
  }

  private fun start(enabled: Boolean = true, maxSessions: Int = 4, maxQueued: Int = 8) {
    val registry = SimulatorAppRegistry(File(dir.toFile(), "apps"), onLog = {})
    val sim =
      ServeSimulator(
        registry = registry,
        sessions =
          SimulatorSessions(
            registry,
            runtime,
            capacity =
              SimulatorSessions.Capacity(
                maxSessions = maxSessions,
                memoryBudgetMb = 100_000,
                maxConcurrentBoots = 4,
                maxQueued = maxQueued,
                maxPerOwner = 8,
              ),
            frameIntervalMillis = 20,
            onLog = {},
          ),
        embedTokens = SimulatorEmbedTokens("k".repeat(32).toByteArray()),
      )
    simulator = sim
    val engine =
      embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        install(WebSockets)
        routing {
          installSimulatorRoutes(
            sim,
            isOperator = { it.request.headers[ServeHttpServer.ADMIN_TOKEN_HEADER] == TOKEN },
            signedInActor = { signedIn },
            enabled = enabled,
          )
        }
      }
    engine.start(wait = false)
    server = engine
    port = runBlocking { engine.engine.resolvedConnectors().first().port }
  }

  private fun url(path: String) = "http://127.0.0.1:$port$path"

  private fun call(
    path: String,
    method: String = "GET",
    body: ByteArray? = null,
    admin: Boolean = false,
    headers: Map<String, String> = emptyMap(),
  ): Pair<Int, String> {
    val builder = Request.Builder().url(url(path))
    if (admin) builder.header(ServeHttpServer.ADMIN_TOKEN_HEADER, TOKEN)
    headers.forEach { (k, v) -> builder.header(k, v) }
    val requestBody = body?.toRequestBody("application/octet-stream".toMediaType())
    builder.method(
      method,
      requestBody ?: if (method in setOf("POST", "PUT")) ByteArray(0).toRequestBody() else null,
    )
    client.newCall(builder.build()).execute().use {
      return it.code to it.body.string()
    }
  }

  private fun response(path: String): Response =
    client.newCall(Request.Builder().url(url(path)).build()).execute()

  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun registerPair(config: String? = null) {
    assertEquals(
      200,
      call(
          "/admin/simulator/apps/demo/apks/phone",
          "PUT",
          SimulatorFixtures.phoneApk(),
          admin = true,
        )
        .first,
    )
    assertEquals(
      200,
      call(
          "/admin/simulator/apps/demo/apks/watch",
          "PUT",
          SimulatorFixtures.watchApk(),
          admin = true,
        )
        .first,
    )
    if (config != null) {
      val (code, text) =
        call("/admin/simulator/apps/demo", "PUT", config.toByteArray(), admin = true)
      assertEquals(200, code, text)
    }
  }

  private fun startSession(
    mode: String,
    admin: Boolean = true,
    extra: String = "",
  ): Pair<Int, JsonObject> {
    val (code, text) =
      call(
        "$SIMULATOR_API/apps/demo/sessions",
        "POST",
        """{"mode":"$mode"$extra}""".toByteArray(),
        admin = admin,
      )
    return code to json(text)
  }

  private fun awaitLive(started: JsonObject): JsonObject {
    val path =
      started["statusPath"]!!.jsonPrimitive.content +
        "?secret=" +
        started["secret"]!!.jsonPrimitive.content
    repeat(200) {
      val status = json(call(path).second)
      if (status["stage"]?.jsonPrimitive?.content == "live") return status
      Thread.sleep(25)
    }
    error("never live")
  }

  private fun command(started: JsonObject, body: String): Pair<Int, JsonObject> {
    val (code, text) =
      call(
        started["commandPath"]!!.jsonPrimitive.content,
        "POST",
        body.toByteArray(),
        headers = mapOf(SIMULATOR_SECRET_HEADER to started["secret"]!!.jsonPrimitive.content),
      )
    return code to json(text)
  }

  @Test
  fun `the registry is the operator's alone`() {
    start()
    assertEquals(404, call("/admin/simulator/apps").first)
    assertEquals(
      404,
      call("/admin/simulator/apps/demo/apks/phone", "PUT", SimulatorFixtures.phoneApk()).first,
    )
    registerPair()
    val app = json(call("/admin/simulator/apps/demo", admin = true).second)
    assertEquals(
      listOf("phone", "watch", "paired"),
      app["modes"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      400,
      call(
          "/admin/simulator/apps/demo/apks/phone",
          "PUT",
          SimulatorFixtures.watchApk(),
          admin = true,
        )
        .first,
    )
    // A visitor is not told the app exists.
    assertEquals("[]", call("$SIMULATOR_API/apps").second)
    assertEquals(404, startSession("phone", admin = false).first)
  }

  @Test
  fun `phone, watch and paired sessions each boot their devices`() {
    start()
    registerPair()
    for ((mode, devices) in
      listOf(
        "phone" to listOf("phone"),
        "watch" to listOf("watch"),
        "paired" to listOf("phone", "watch"),
      )) {
      val (code, started) = startSession(mode)
      assertEquals(201, code, started.toString())
      assertEquals(devices, started["devices"]!!.jsonArray.map { it.jsonPrimitive.content })
      val live = awaitLive(started)
      assertEquals(devices.toSet(), live["displays"]!!.jsonObject.keys)
      if ("watch" in devices) {
        assertEquals(
          JsonPrimitive(true),
          live["displays"]!!.jsonObject["watch"]!!.jsonObject["round"],
        )
      }
    }
    val paired = runtime.launches.single { it.mode == SimulatorMode.PAIRED }
    assertEquals(setOf(SimulatorDevice.PHONE, SimulatorDevice.WATCH), paired.apks.keys)
    assertEquals(
      "offline",
      paired.network,
      "an app has no network unless its operator gives it one",
    )
  }

  @Test
  fun `commands go through the policy and to the device they name`() {
    start()
    registerPair()
    val (_, started) = startSession("paired")
    awaitLive(started)
    val (ok, reply) =
      command(started, """{"cmd":"rotary","device":"watch","delta":-1,"path":"/etc/x"}""")
    assertEquals(200, ok, reply.toString())
    val sent = runtime.commands.last()
    assertEquals(SimulatorDevice.WATCH, sent.device)
    assertEquals(json("""{"cmd":"rotary","delta":-1.0}"""), sent.command)
    assertEquals(422, command(started, """{"cmd":"shell","command":"id"}""").first)
    assertEquals(422, command(started, """{"cmd":"rotary","device":"phone","delta":1}""").first)
    // Operator tier: app state commands too; results never carry host paths.
    val (infoCode, info) = command(started, """{"cmd":"info","device":"watch"}""")
    assertEquals(200, infoCode)
    assertTrue("apk" !in info["result"]!!.jsonObject, info.toString())
    assertEquals(
      200,
      command(started, """{"cmd":"permissions","grant":["android.permission.CAMERA"]}""").first,
    )
  }

  @Test
  fun `a session is reached only with its secret`() {
    start()
    registerPair()
    val (_, started) = startSession("phone")
    val path = started["statusPath"]!!.jsonPrimitive.content
    assertEquals(404, call(path).first)
    assertEquals(404, call("$path?secret=wrong").first)
    assertEquals(200, call("$path?secret=${started["secret"]!!.jsonPrimitive.content}").first)
    assertEquals(404, call(path, "DELETE").first)
  }

  @Test
  fun `signed in visitors get viewer access to apps that allow it`() {
    start()
    registerPair("""{"access":"signed-in"}""")
    assertEquals(401, startSession("phone", admin = false).first, "signed out: told to sign in")
    signedIn = "someone"
    val (code, started) = startSession("phone", admin = false)
    assertEquals(201, code)
    assertEquals("viewer", started["tier"]!!.jsonPrimitive.content)
    awaitLive(started)
    assertEquals(422, command(started, """{"cmd":"permissions","grant":["x"]}""").first)
    assertEquals(200, command(started, """{"cmd":"tap","x":5,"y":5}""").first)
    assertEquals(
      listOf("demo"),
      Json.parseToJsonElement(call("$SIMULATOR_API/apps").second).jsonArray.map {
        it.jsonObject["appId"]!!.jsonPrimitive.content
      },
    )
  }

  @Test
  fun `a full host queues new sessions, and a full queue refuses them`() {
    start(maxSessions = 1, maxQueued = 1)
    registerPair()
    val (_, first) = startSession("phone")
    awaitLive(first)
    val (code, second) = startSession("watch")
    assertEquals(201, code)
    val status =
      json(
        call(
            second["statusPath"]!!.jsonPrimitive.content +
              "?secret=" +
              second["secret"]!!.jsonPrimitive.content,
          )
          .second,
      )
    assertEquals("queued", status["stage"]!!.jsonPrimitive.content)
    assertEquals(0, status["queueAhead"]!!.jsonPrimitive.content.toInt())
    assertEquals(429, startSession("paired").first)
    val capacity = json(call("/admin/simulator/capacity", admin = true).second)
    assertEquals(1, capacity["queued"]!!.jsonPrimitive.content.toInt())
    // The first ending lets the second boot.
    call(
      first["statusPath"]!!.jsonPrimitive.content,
      "DELETE",
      headers = mapOf(SIMULATOR_SECRET_HEADER to first["secret"]!!.jsonPrimitive.content),
    )
    awaitLive(second)
  }

  @Test
  fun `the feature flag gates starts`() {
    start(enabled = false)
    registerPair()
    assertEquals(503, startSession("phone").first)
    assertEquals(
      false,
      json(call("$SIMULATOR_API/status").second)["enabled"]!!.jsonPrimitive.content.toBoolean(),
    )
  }

  @Test
  fun `embeds need the app's consent, its origins and a token`() {
    start()
    registerPair()
    assertEquals(404, response("/simulator/embed/demo").use { it.code }, "not embeddable")
    assertEquals(409, call("/admin/simulator/apps/demo/embed-tokens", "POST", admin = true).first)
    registerPair(
      """{"embed":{"enabled":true,"origins":["https://docs.example.com"],"maxConcurrentSessions":1}}""",
    )
    response("/simulator/embed/demo").use {
      assertEquals(200, it.code)
      val csp = assertNotNull(it.header(ServePagePolicy.HEADER))
      assertTrue("frame-ancestors https://docs.example.com" in csp, csp)
      assertTrue(it.body.string().contains("\"parents\":[\"https://docs.example.com\"]"))
    }
    assertEquals(
      404,
      startSession("phone", admin = false, extra = ""","embed":true""").first,
      "no token",
    )
    assertEquals(
      404,
      startSession("phone", admin = false, extra = ""","embedToken":"forged.token"""").first,
    )
    val minted =
      json(
        call(
            "/admin/simulator/apps/demo/embed-tokens",
            "POST",
            """{"ttlSeconds":600}""".toByteArray(),
            admin = true,
          )
          .second,
      )
    val token = minted["token"]!!.jsonPrimitive.content
    assertTrue(
      minted["embedPath"]!!.jsonPrimitive.content.startsWith("/simulator/embed/demo?token="),
    )
    val (code, started) = startSession("watch", admin = false, extra = ""","embedToken":"$token"""")
    assertEquals(201, code, started.toString())
    assertEquals("viewer", started["tier"]!!.jsonPrimitive.content)
    assertEquals(
      429,
      startSession("phone", admin = false, extra = ""","embedToken":"$token"""").first,
      "one embedded session at a time",
    )
  }

  @Test
  fun `anonymous embeds start without a token`() {
    start()
    registerPair(
      """{"embed":{"enabled":true,"anonymous":true,"origins":["https://docs.example.com"]}}""",
    )
    assertEquals(201, startSession("phone", admin = false, extra = ""","embed":true""").first)
  }

  @Test
  fun `the socket streams stages, the lane and frames for both paired devices`() {
    start()
    registerPair()
    val (_, started) = startSession("paired")
    val messages = LinkedBlockingQueue<JsonObject>()
    val socket =
      client.newWebSocket(
        Request.Builder()
          .url(
            "ws://127.0.0.1:$port${started["socketPath"]!!.jsonPrimitive.content}?secret=${started["secret"]!!.jsonPrimitive.content}",
          )
          .build(),
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            messages += json(text)
          }
        },
      )
    try {
      val seen = mutableListOf<JsonObject>()
      fun next(type: String, device: String? = null): JsonObject {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
          val m = messages.poll(100, TimeUnit.MILLISECONDS) ?: continue
          seen += m
          if (
            m["type"]!!.jsonPrimitive.content == type &&
              (device == null || m["device"]?.jsonPrimitive?.content == device)
          )
            return m
        }
        error("no $type ${device.orEmpty()} in ${seen.map { it["type"] }}")
      }
      val lane = next("lane")
      assertEquals(
        listOf("phone", "watch"),
        lane["devices"]!!.jsonArray.map { it.jsonObject["device"]!!.jsonPrimitive.content },
      )
      assertTrue(
        "permissions" in lane["commands"]!!.jsonArray.map { it.jsonPrimitive.content },
        "operator lane",
      )
      next("frame", "phone")
      next("frame", "watch")
      assertTrue(seen.any { it["type"]!!.jsonPrimitive.content == "stage" })

      // A command over the socket, with an id, is answered; one the policy refuses says why.
      socket.send("""{"type":"command","id":7,"cmd":"key","device":"watch","key":"stem_primary"}""")
      val result = next("result")
      assertEquals(7, result["id"]!!.jsonPrimitive.content.toInt())
      assertEquals(
        json("""{"cmd":"key","key":"STEM_PRIMARY"}"""),
        runtime.commands.last { it.command["cmd"] == JsonPrimitive("key") }.command,
      )
      socket.send("""{"type":"command","cmd":"quit"}""")
      assertEquals("false", next("result")["ok"]!!.jsonPrimitive.content)

      // Events reach the viewer filtered and tagged with their device.
      runtime.processes
        .last()
        .emit(
          SimulatorDevice.WATCH,
          json("""{"event":"activity","state":"resumed","activity":"w.Main","apk":"/srv/x"}"""),
        )
      val event = next("event")
      assertEquals("watch", event["device"]!!.jsonPrimitive.content)
      assertTrue("apk" !in event["event"]!!.jsonObject)

      // Ending the session tells the socket.
      call(
        started["statusPath"]!!.jsonPrimitive.content,
        "DELETE",
        headers = mapOf(SIMULATOR_SECRET_HEADER to started["secret"]!!.jsonPrimitive.content),
      )
      next("ended")
      assertTrue(runtime.processes.last().closed)
    } finally {
      socket.cancel()
    }
  }

  @Test
  fun `the CLI command line runs a pair with --pair and never a window`() {
    val launch =
      SimulatorLaunch(
        "s",
        SimulatorMode.PAIRED,
        mapOf(
          SimulatorDevice.PHONE to File("/a/phone.apk"),
          SimulatorDevice.WATCH to File("/a/watch.apk"),
        ),
        network = "wifi",
        launcher = true,
      )
    val argv =
      CliSimulatorRuntime.command(File("/cli/bin/compose-preview"), launch, File("/jdk"), "1g")
    assertEquals(
      listOf(
        "/cli/bin/compose-preview",
        "android-simulator",
        "/a/phone.apk",
        "--pair",
        "/a/watch.apk",
        "--control",
        "stdio",
        "--headless",
        "--hide-agent",
        "--network",
        "wifi",
        "--launcher",
        "--max-heap",
        "1g",
        "--java",
        "/jdk",
      ),
      argv,
    )
    val watch =
      CliSimulatorRuntime.command(
        File("/c"),
        launch.copy(mode = SimulatorMode.WATCH, launcher = false),
        File("/jdk"),
        null,
      )
    assertEquals(listOf("/c", "android-simulator", "/a/watch.apk"), watch.take(3))
    assertTrue("--pair" !in watch)
  }

  @Test
  fun `embed tokens are bound to their app and expire`() {
    var now = 1_000_000L
    val tokens = SimulatorEmbedTokens("x".repeat(32).toByteArray(), clock = { now })
    val token = tokens.mint("demo", ttlSeconds = 120)
    assertTrue(tokens.verify(token, "demo"))
    assertTrue(!tokens.verify(token, "other"))
    assertTrue(!tokens.verify(token.dropLast(2) + "AA", "demo"))
    assertTrue(
      !SimulatorEmbedTokens("y".repeat(32).toByteArray(), clock = { now }).verify(token, "demo"),
    )
    now += 121_000
    assertTrue(!tokens.verify(token, "demo"))
  }

  private companion object {
    const val TOKEN = "operator-token"
  }
}
