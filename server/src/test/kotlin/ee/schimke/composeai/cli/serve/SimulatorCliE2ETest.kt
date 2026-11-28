package ee.schimke.composeai.cli.serve

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The server's simulator path against the REAL released CLI: phone, watch and a paired phone and
 * watch, each booted, driven and measured; the idle tiers (pause, hibernate, wake) on real JVMs.
 *
 * Off unless `SIMULATOR_E2E_DIR` names a directory holding `cli/bin/compose-preview` and the APKs
 * `phone.apk`, `watch.apk`, `pairphone.apk`, `pairwatch.apk` (the last two using the Wearable Data
 * Layer: the phone's button messages the watch, which shows "Watch got hi 1"). Needs Java 21 and an
 * Android SDK. `SIMULATOR_E2E_HOLD=1` keeps the server up afterwards, writing its URL and an
 * operator token to `<dir>/server.json`, until `<dir>/done` exists — so a browser can drive the
 * real viewer. Results are written to `<dir>/e2e-results.json`.
 */
class SimulatorCliE2ETest {
  private val dir = System.getenv("SIMULATOR_E2E_DIR")?.let(::File)

  @Test
  fun `phone, watch and paired sessions run on the real CLI`() {
    assumeTrue(
      dir != null && File(dir, "cli/bin/compose-preview").canExecute(),
      "SIMULATOR_E2E_DIR not set",
    )
    val root = File(dir, "e2e-state").apply { mkdirs() }
    val appsDir = File(root, "apps-${System.nanoTime()}")
    val registry = SimulatorAppRegistry(appsDir)
    fun install(app: String, device: SimulatorDevice, apk: String) =
      assertTrue(
        registry.installApk(app, device, File(dir, apk).readBytes())
          is SimulatorAppRegistry.Result.Ok,
        apk,
      )
    install("demo", SimulatorDevice.PHONE, "phone.apk")
    install("demo", SimulatorDevice.WATCH, "watch.apk")
    install("pair", SimulatorDevice.PHONE, "pairphone.apk")
    install("pair", SimulatorDevice.WATCH, "pairwatch.apk")
    registry.putConfig(
      "demo",
      SimulatorAppConfig(
        label = "Simulator demo",
        embed =
          SimulatorEmbedPolicy(
            enabled = true,
            anonymous = true,
            origins = listOf("http://localhost:8765"),
          ),
      ),
    )
    registry.putConfig("pair", SimulatorAppConfig(label = "Paired demo"))
    val runtime =
      CliSimulatorRuntime(
        cli = { File(dir, "cli/bin/compose-preview") },
        javaHome = File(System.getenv("SIMULATOR_E2E_JAVA") ?: System.getProperty("java.home")),
        stateDir = File(root, "state"),
        appsDir = appsDir,
      )
    assertEquals(SimulatorRuntime.Availability.Ready, runtime.availability())
    val pool =
      SimulatorSessions(
        registry,
        runtime,
        capacity =
          SimulatorSessions.Capacity(
            memoryBudgetMb = 8_000,
            maxConcurrentBoots = 2,
            maxPerOwner = 8,
          ),
        pauseHiddenMillis = 2_000,
      )
    val results = mutableMapOf<String, Any?>()
    try {
      for ((app, mode) in
        listOf(
          "demo" to SimulatorMode.PHONE,
          "demo" to SimulatorMode.WATCH,
          "pair" to SimulatorMode.PAIRED,
        )) {
        val t0 = System.currentTimeMillis()
        val s =
          (pool.start(
              app,
              mode,
              SimulatorSessions.Kind.PAGE,
              SimulatorControlPolicy.Tier.OPERATOR,
              "e2e",
            ) as SimulatorSessions.Started.Ok)
            .session
        // Paused is up too: nobody has a socket on it here, and any command wakes it.
        await(
          180_000,
          { "${mode.id} is ${s.stage} ${s.failureReason} ${s.diagnostics.takeLast(5)}" },
        ) {
          s.stage == SimulatorStage.LIVE || s.stage == SimulatorStage.PAUSED
        }
        val readyMs = System.currentTimeMillis() - t0
        System.err.println("e2e: ${mode.id} live after ${readyMs}ms")
        val r = mutableMapOf<String, Any?>("readyMs" to readyMs)
        if (mode == SimulatorMode.PAIRED) {
          // Until the watch's node has joined, the phone app's getConnectedNodes() task is pending,
          // and a tap waits on the app settling: wait for the pair to connect, as a person would
          // see
          // the UI before touching it.
          val c0 = System.currentTimeMillis()
          await(30_000, { "the pair never connected" }) {
            val dl = run(pool, s, """{"cmd":"dataLayer","device":"phone"}""") as JsonObject
            (dl["peers"] as? JsonArray)?.isNotEmpty() == true
          }
          r["pairConnectedAfterLiveMs"] = System.currentTimeMillis() - c0
        }
        for (device in s.devices) {
          val display = s.displayOf(device)!!
          if (device == SimulatorDevice.WATCH) assertTrue(display.round, "a watch is round")
          pool.wake(s)
          val frame = pool.frame(s, device, force = true)!!
          File(dir, "frame-${mode.id}-${device.id}-before.png")
            .writeBytes(Base64.getDecoder().decode(frame.pngBase64))
          // Paired: only the phone taps here; the watch's own tap would overwrite the message.
          if (mode != SimulatorMode.PAIRED || device == SimulatorDevice.PHONE) {
            run(pool, s, """{"cmd":"tap","device":"${device.id}","text":"TAP ME"}""")
          }
          r["${device.id}Display"] =
            "${display.widthPx}x${display.heightPx}${if (display.round) " round" else ""}"
        }
        if (mode == SimulatorMode.PAIRED) {
          // The phone's tap messages every connected node; the watch shows what it got. (`find`
          // matches whole texts, so the screen is read whole and searched.)
          await(20_000, { "the phone never reached the watch: ${texts(pool, s, "phone")}" }) {
            if (texts(pool, s, "phone").any { it.endsWith("sent to 1") }) true
            else {
              run(pool, s, """{"cmd":"tap","device":"phone","text":"TAP ME"}""")
              false
            }
          }
          r["phoneText"] = texts(pool, s, "phone").first { "sent to" in it }
          await(10_000, { "the watch never got the phone's message: ${texts(pool, s, "watch")}" }) {
            texts(pool, s, "watch").any { it.startsWith("Watch got hi") }
          }
          r["watchText"] = texts(pool, s, "watch").first { it.startsWith("Watch got") }
          r["dataLayerMessage"] = "arrived"
          await(15_000, { "the paired watch took no tap: ${texts(pool, s, "watch")}" }) {
            if (texts(pool, s, "watch").any { it.endsWith(" taps") }) true
            else {
              run(pool, s, """{"cmd":"tap","device":"watch","text":"TAP ME"}""")
              false
            }
          }
        } else {
          // The first tap can land before the app's first layout pass; the counter leaving zero is
          // the proof that input reaches the app.
          val label = if (mode == SimulatorMode.WATCH) "Watch" else "Phone"
          await(15_000, { "no tap reached the $label app" }) {
            val zero = run(pool, s, """{"cmd":"find","text":"$label: 0 taps"}""") as JsonObject
            if ((zero["nodes"] as JsonArray).isEmpty()) true
            else {
              run(pool, s, """{"cmd":"tap","text":"TAP ME"}""")
              false
            }
          }
        }
        pool.wake(s)
        for (device in s.devices) {
          val frame = pool.frame(s, device, force = true)!!
          File(dir, "frame-${mode.id}-${device.id}-after.png")
            .writeBytes(Base64.getDecoder().decode(frame.pngBase64))
        }
        // Live cost, as the pool measures it, with a viewer attached so it stays live.
        pool.attach(s)
        Thread.sleep(2_000)
        pool.sweep()
        Thread.sleep(5_000)
        pool.sweep()
        r["liveRssMb"] = s.rssMb
        r["liveIdleCpuPercent"] = s.cpuPercent
        // Hidden: paused by the sweep, and then costs no CPU.
        pool.visibility(s, false)
        Thread.sleep(2_500)
        pool.sweep()
        assertEquals(SimulatorStage.PAUSED, s.stage)
        Thread.sleep(5_000)
        pool.sweep()
        r["pausedCpuPercent"] = s.cpuPercent
        val w0 = System.nanoTime()
        pool.visibility(s, true)
        pool.frame(s, s.devices.first(), force = true)!!
        r["wakeToFrameMs"] = (System.nanoTime() - w0) / 1_000_000
        // Hibernate: the process goes, and waking boots it again with the activity restored.
        pool.pause(s)
        pool.hibernate(s)
        assertEquals(SimulatorStage.HIBERNATED, s.stage)
        assertTrue(s.process == null)
        val h0 = System.currentTimeMillis()
        pool.wake(s)
        await(180_000, { "wake: ${s.stage} ${s.failureReason}" }) {
          s.stage == SimulatorStage.LIVE || s.stage == SimulatorStage.PAUSED
        }
        r["hibernatedWakeMs"] = System.currentTimeMillis() - h0
        val acts = run(pool, s, """{"cmd":"activities"}""") as JsonObject
        assertTrue(acts.toString().contains("\"resumed\""), "restored and resumed: $acts")
        results[mode.id] = r
        pool.detach(s, wasVisible = true)
        if (System.getenv("SIMULATOR_E2E_HOLD") == null) pool.close(s.id)
      }
      File(dir, "e2e-results.json")
        .writeText(
          Json { prettyPrint = true }
            .encodeToString(kotlinx.serialization.json.JsonElement.serializer(), toJson(results)),
        )
      if (System.getenv("SIMULATOR_E2E_HOLD") != null) hold(registry, pool)
    } finally {
      pool.close()
    }
  }

  private fun hold(registry: SimulatorAppRegistry, pool: SimulatorSessions) {
    val sim = ServeSimulator(registry, pool, SimulatorEmbedTokens(ByteArray(32) { 7 }))
    val token = "e2e-operator"
    val engine =
      embeddedServer(CIO, host = "127.0.0.1", port = 8766) {
        install(WebSockets)
        routing {
          installSimulatorRoutes(
            sim,
            isOperator = { it.request.headers[ServeHttpServer.ADMIN_TOKEN_HEADER] == token },
            signedInActor = { null },
          )
        }
      }
    engine.start(wait = false)
    val port = runBlocking { engine.engine.resolvedConnectors().first().port }
    File(dir, "server.json").writeText("""{"url":"http://127.0.0.1:$port","token":"$token"}""")
    val done = File(dir, "done")
    val until = System.currentTimeMillis() + 15 * 60_000
    while (!done.exists() && System.currentTimeMillis() < until) Thread.sleep(500)
    engine.stop(100, 500)
  }

  private fun run(pool: SimulatorSessions, s: SimulatorSessions.Session, text: String): Any? {
    val decision =
      SimulatorControlPolicy.check(Json.parseToJsonElement(text).jsonObject, s.devices, s.tier)
    check(decision is SimulatorControlPolicy.Decision.Allowed) { "$text: $decision" }
    var last: Throwable? = null
    repeat(10) {
      try {
        return pool.command(s, decision)
      } catch (e: SimulatorCommandException) {
        last = e
        Thread.sleep(500)
      }
    }
    throw AssertionError("$text failed: ${last?.message}")
  }

  private fun texts(
    pool: SimulatorSessions,
    s: SimulatorSessions.Session,
    device: String,
  ): List<String> {
    val tree = run(pool, s, """{"cmd":"hierarchy","device":"$device","flat":true}""") as JsonObject
    return (tree["nodes"] as JsonArray).mapNotNull {
      (it as JsonObject)["text"]?.let { t -> (t as JsonPrimitive).content }
    }
  }

  private fun await(ms: Long, why: () -> String, condition: () -> Boolean) {
    val until = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < until) {
      if (condition()) return
      Thread.sleep(200)
    }
    throw AssertionError(why())
  }

  private fun toJson(value: Any?): kotlinx.serialization.json.JsonElement =
    when (value) {
      null -> kotlinx.serialization.json.JsonNull
      is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to toJson(it.value) })
      is Number -> JsonPrimitive(value)
      is Boolean -> JsonPrimitive(value)
      else -> JsonPrimitive(value.toString())
    }
}
