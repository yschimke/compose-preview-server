package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.SimulatorControlPolicy.Tier
import ee.schimke.composeai.cli.serve.SimulatorSessions.Kind
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir

/** The pool's cost controls: boot queueing, memory admission, the idle tiers and fairness. */
class SimulatorSessionsTest {
  @TempDir lateinit var dir: Path
  private val now = AtomicLong(1_000_000)
  private val runtime = FakeSimulatorRuntime().apply { bootEvents = false }
  private var rssMb = 500L
  private lateinit var pool: SimulatorSessions

  @AfterTest fun tearDown() = pool.close()

  private fun pool(capacity: SimulatorSessions.Capacity): SimulatorSessions {
    val registry = SimulatorAppRegistry(File(dir.toFile(), "apps"), onLog = {})
    registry.installApk("demo", SimulatorDevice.PHONE, SimulatorFixtures.phoneApk())
    registry.installApk("demo", SimulatorDevice.WATCH, SimulatorFixtures.watchApk())
    pool =
      SimulatorSessions(
        registry,
        runtime,
        clock = now::get,
        capacity = capacity,
        sweepMillis = 3_600_000,
        meter = { ProcessMeter.Sample(rssMb, 1.0) },
        onLog = {},
      )
    return pool
  }

  private fun start(owner: String? = null, mode: SimulatorMode = SimulatorMode.PHONE) =
    assertIs<SimulatorSessions.Started.Ok>(
        pool.start("demo", mode, Kind.PAGE, Tier.OPERATOR, owner),
      )
      .session

  private fun awaitStage(s: SimulatorSessions.Session, stage: SimulatorStage) {
    repeat(200) {
      if (s.stage == stage) return
      Thread.sleep(10)
    }
    error("${s.id} is ${s.stage}, never $stage")
  }

  private fun processOf(s: SimulatorSessions.Session): FakeSimulatorRuntime.Process {
    repeat(200) {
      runtime.processes
        .firstOrNull { it.launch.sessionId == s.id && !it.closed }
        ?.let {
          return it
        }
      Thread.sleep(10)
    }
    error("no process for ${s.id}")
  }

  private fun boot(s: SimulatorSessions.Session) {
    processOf(s).ready()
    awaitStage(s, SimulatorStage.LIVE)
  }

  @Test
  fun `boots wait their turn`() {
    pool(SimulatorSessions.Capacity(maxConcurrentBoots = 1, memoryBudgetMb = 100_000))
    val a = start("a")
    val b = start("b")
    awaitStage(a, SimulatorStage.STARTING)
    assertEquals(SimulatorStage.QUEUED, b.stage)
    assertEquals(0, pool.queuePosition(b))
    assertTrue(b.status(now.get(), 0).label.contains("next in line"))
    boot(a)
    boot(b)
    assertNull(pool.queuePosition(b))
  }

  @Test
  fun `a hidden session pauses, and input wakes it`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 100_000))
    val s = start()
    boot(s)
    pool.attach(s)
    pool.visibility(s, false)
    now.addAndGet(11_000)
    pool.sweep()
    assertEquals(SimulatorStage.PAUSED, s.stage)
    assertTrue(processOf(s).paused, "frozen: no CPU while nobody looks")
    // A paused session serves its last frame rather than waking for a screenshot.
    pool.frame(s, SimulatorDevice.PHONE)
    assertTrue(processOf(s).paused)
    val tap =
      SimulatorControlPolicy.check(
        kotlinx.serialization.json.Json.parseToJsonElement("""{"cmd":"tap","x":1,"y":1}""")
          .jsonObject(),
        s.devices,
        Tier.VIEWER,
      )
    pool.command(s, assertIs(tap))
    assertEquals(SimulatorStage.LIVE, s.stage)
    assertTrue(!processOf(s).paused)
  }

  @Test
  fun `a session is never frozen in the middle of a command`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 100_000))
    val s = start()
    boot(s)
    val release = java.util.concurrent.CountDownLatch(1)
    runtime.answers["wait"] = JsonPrimitive("waited")
    val slow =
      object : SimulatorProcess by processOf(s) {
        override fun command(
          device: SimulatorDevice,
          command: kotlinx.serialization.json.JsonObject,
          timeoutMillis: Long,
        ): kotlinx.serialization.json.JsonElement? {
          release.await()
          return JsonPrimitive("done")
        }
      }
    s.process = slow
    val wait =
      assertIs<SimulatorControlPolicy.Decision.Allowed>(
        SimulatorControlPolicy.check(
          kotlinx.serialization.json.Json.parseToJsonElement("""{"cmd":"wait","ms":10}""")
            .jsonObject(),
          s.devices,
          Tier.VIEWER,
        ),
      )
    val running = Thread { pool.command(s, wait) }.apply { start() }
    repeat(100) { if (s.inFlight.get() == 0) Thread.sleep(10) }
    now.addAndGet(10 * 60_000 - 1)
    pool.pause(s)
    pool.sweep()
    assertEquals(SimulatorStage.LIVE, s.stage, "a command is running")
    release.countDown()
    running.join(5_000)
    pool.pause(s)
    assertEquals(SimulatorStage.PAUSED, s.stage)
  }

  @Test
  fun `a visible session left alone pauses later, and one left long enough ends`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 100_000))
    val s = start()
    boot(s)
    pool.attach(s)
    now.addAndGet(60_000)
    pool.sweep()
    assertEquals(SimulatorStage.LIVE, s.stage, "someone may be reading it")
    now.addAndGet(70_000)
    pool.sweep()
    assertEquals(SimulatorStage.PAUSED, s.stage)
    now.addAndGet(10 * 60_000)
    pool.sweep()
    assertEquals(SimulatorStage.CLOSED, s.stage)
    assertTrue(runtime.processes.single().closed)
  }

  @Test
  fun `memory is made by hibernating the least recently used paused session, and restored on wake`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 2_000, maxConcurrentBoots = 4))
    val a = start("a")
    boot(a)
    now.addAndGet(1_000)
    val b = start("b")
    boot(b)
    // Measured: 900 MB each, so a third does not fit beside them.
    rssMb = 900
    pool.sweep()
    pool.pause(a)
    pool.pause(b)
    val c = start("c")
    boot(c)
    assertEquals(SimulatorStage.HIBERNATED, a.stage, "a was idle longest")
    assertEquals(SimulatorStage.PAUSED, b.stage)
    assertEquals(1, a.hibernations.get())

    // Waking a: room is made from b, and a's activity comes back with its saved state.
    pool.wake(a)
    boot(a)
    assertEquals(SimulatorStage.HIBERNATED, b.stage)
    val restore =
      runtime.commands.last {
        it.sessionId == a.id && it.command["cmd"] == JsonPrimitive("startActivity")
      }
    assertEquals("c3RhdGU=", (restore.command["savedState"] as JsonPrimitive).content)
    assertEquals(
      "com.example.phone/com.example.phone.MainActivity",
      (restore.command["intent"] as kotlinx.serialization.json.JsonObject)["component"]!!.let {
        (it as JsonPrimitive).content
      },
    )
  }

  @Test
  fun `a session that does not fit and cannot make room waits`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 2_000, maxConcurrentBoots = 4))
    val a = start("a")
    boot(a)
    val b = start("b")
    boot(b)
    rssMb = 900
    pool.sweep()
    pool.attach(a)
    val c = start("c")
    assertEquals(SimulatorStage.QUEUED, c.stage, "a and b are live: nothing to reclaim")
    pool.close(b.id)
    awaitStage(c, SimulatorStage.STARTING)
  }

  @Test
  fun `a slow boot is not abandoned, but a live session nobody holds is`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 100_000))
    val s = start()
    processOf(s)
    now.addAndGet(5 * 60_000)
    pool.sweep()
    assertEquals(SimulatorStage.STARTING, s.stage, "a cold conversion takes minutes")
    boot(s)
    now.addAndGet(61_000)
    pool.sweep()
    assertEquals(SimulatorStage.CLOSED, s.stage)
  }

  @Test
  fun `an owner's new session replaces their oldest`() {
    pool(
      SimulatorSessions.Capacity(memoryBudgetMb = 100_000, maxPerOwner = 1, maxConcurrentBoots = 4),
    )
    val first = start("someone")
    val second = start("someone")
    assertEquals(SimulatorStage.CLOSED, first.stage)
    assertTrue(second.stage != SimulatorStage.CLOSED)
    val other = start("someone-else")
    assertTrue(other.stage != SimulatorStage.CLOSED)
  }

  @Test
  fun `measurement replaces the estimate and is reported`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 100_000))
    assertEquals(900, pool.estimateMb("demo", SimulatorMode.PHONE))
    val s = start()
    boot(s)
    rssMb = 610
    pool.sweep()
    assertEquals(610, s.rssMb)
    assertEquals(610, pool.estimateMb("demo", SimulatorMode.PHONE))
    assertEquals(1_700, pool.estimateMb("demo", SimulatorMode.PAIRED))
    val capacity = pool.capacityStatus()
    assertEquals(1, capacity.live)
    assertEquals(610, capacity.memoryChargedMb)
  }

  @Test
  fun `an app bigger than the whole budget is refused outright`() {
    pool(SimulatorSessions.Capacity(memoryBudgetMb = 1_000))
    assertIs<SimulatorSessions.Started.Unavailable>(
      pool.start("demo", SimulatorMode.PAIRED, Kind.PAGE, Tier.OPERATOR, "x"),
    )
  }

  private fun kotlinx.serialization.json.JsonElement.jsonObject() =
    this as kotlinx.serialization.json.JsonObject
}
