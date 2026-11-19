package ee.schimke.composeai.cli.serve

import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The way to a session's first frame ([DwfStage]), the waits that make that frame the app's face
 * rather than the simulator's, the queue behind the four runtime slots, and the release of sessions
 * nobody is showing any more.
 */
class DwfPreviewStagesTest {
  private val runtime = FakeDwfRuntime()
  private var clock = 1_000_000L
  private val sleeps = mutableListOf<Long>()

  /** Runnables [DwfPreviewSessions.startAsync] queued, run by the test when it chooses. */
  private val pending = ArrayDeque<Runnable>()
  private val deferred = Executor { pending += it }
  private val direct = Executor { it.run() }

  private fun sessions(executor: Executor = direct, max: Int = 2) =
    DwfPreviewSessions(
      runtime,
      maxSessions = max,
      idleMillis = 15 * 60_000,
      now = { clock },
      detachedGraceMillis = 60_000,
      executor = executor,
      sleep = { sleeps += it },
    )

  private fun DwfPreviewSessions.async(
    exports: Boolean = false,
    owner: String = "alice",
    design: String = "d",
    resolve: () -> DwfPreviewSessions.Resolution = { DwfPreviewSessions.Resolution.Ok(source()) },
  ) =
    assertIs<DwfPreviewSessions.Started.Ok>(
        startAsync(design, 7, owner, 2, exports, LIVE_CONTROLS, resolve),
      )
      .session

  private fun waiting(frames: Int = 0) = buildJsonObject {
    put("kind", "app")
    put("frames", frames)
  }

  @Test
  fun `an async start walks every stage to live and keeps the frame it drew`() {
    val s = sessions(deferred)
    val seen = mutableListOf<String>()
    lateinit var session: DwfPreviewSessions.Session
    fun record() {
      val stage = s.progress(session).stage.wire
      if (seen.lastOrNull() != stage) seen += stage
    }
    runtime.onConnect = { record() }
    runtime.onCommand = { record() }
    session =
      s.async(exports = true) {
        record()
        DwfPreviewSessions.Resolution.Ok(source())
      }
    assertEquals(DwfStage.EXPORTING, s.progress(session).stage)
    assertTrue(session.preparing)
    pending.removeFirst().run()
    assertEquals(
      listOf("exporting", "starting-runtime", "installing", "loading-face"),
      seen,
    )
    val live = s.progress(session)
    assertEquals(DwfStage.LIVE, live.stage)
    assertEquals(5, live.step)
    assertEquals(5, live.steps)
    assertEquals("Live", live.label)
    assertNotNull(session.warmFrame)
    assertTrue(!session.preparing)
  }

  @Test
  fun `a cached package is reported on an async start`() {
    runtime.cached = true
    val s = sessions()
    val session = s.async()
    assertTrue(s.progress(session).cachedPackage)
    assertEquals(DwfStage.LIVE, s.progress(session).stage)
  }

  @Test
  fun `the cached label is the packaging stage's while it is current`() {
    runtime.cached = true
    val s = sessions()
    val session = assertIs<DwfPreviewSessions.Started.Ok>(s.start("d", 1, "a", source())).session
    val p = s.progress(session)
    assertEquals(DwfStage.PACKAGING, p.stage)
    assertEquals("Using the cached package", p.label)
    assertEquals(2, p.step)
  }

  @Test
  fun `a packager refusal fails the session with its diagnostics and never touches the daemon`() {
    runtime.packagingRefusal = listOf("error[wff]: line 3: unknown element <Bogus>")
    val s = sessions()
    val session = s.async()
    val p = s.progress(session)
    assertEquals(DwfStage.FAILED, p.stage)
    assertEquals(DwfPreviewSurface.PACKAGING_FAILED, p.code)
    assertTrue(p.reason!!.contains("<Bogus>"), p.reason)
    assertEquals(2, p.step, "failed at the packaging step")
    // Asking again is the same answer, and no daemon is started for it.
    assertEquals(
      DwfPreviewSurface.PACKAGING_FAILED,
      assertIs<DwfPreviewSessions.Frame.Failed>(
          s.frame(session.id),
        )
        .code,
    )
    assertTrue(runtime.connections.isEmpty())
    // A failed session holds no runtime slot.
    assertEquals(0, s.sessionCount)
  }

  @Test
  fun `an export refusal is the session's failure`() {
    val s = sessions()
    val session =
      s.async(exports = true) {
        DwfPreviewSessions.Resolution.Refused(DwfPreviewSurface.SOURCE_UNAVAILABLE, "no WFF")
      }
    val p = s.progress(session)
    assertEquals(DwfStage.FAILED, p.stage)
    assertEquals("no WFF", p.reason)
    assertEquals(1, p.step)
  }

  @Test
  fun `when every slot is taken a start waits in line and says how many are ahead`() {
    val s = sessions(deferred, max = 2)
    val a = s.async(owner = "a")
    val b = s.async(owner = "b")
    pending.removeFirst().run()
    pending.removeFirst().run()
    val c = s.async(owner = "c")
    val d = s.async(owner = "d")
    assertEquals(DwfStage.QUEUED, s.progress(c).stage)
    assertEquals(0, s.progress(c).queueAhead)
    assertEquals("Waiting for a free runtime (1 ahead)", s.progress(d).label)
    assertEquals(0, s.progress(d).step)
    assertEquals(2, s.queuedCount)
    s.close(a.id)
    pending.removeFirst().run()
    assertEquals(DwfStage.LIVE, s.progress(c).stage)
    assertEquals(0, s.progress(d).queueAhead)
    assertEquals(DwfStage.LIVE, s.progress(b).stage)
  }

  @Test
  fun `the queue itself is bounded`() {
    val s =
      DwfPreviewSessions(
        runtime,
        maxSessions = 1,
        maxQueued = 1,
        now = { clock },
        executor = deferred,
      )
    s.startAsync("d", 1, "a", 2, false) { DwfPreviewSessions.Resolution.Ok(source()) }
    pending.removeFirst().run()
    assertIs<DwfPreviewSessions.Started.Ok>(
      s.startAsync("d", 1, "b", 2, false) { DwfPreviewSessions.Resolution.Ok(source()) },
    )
    val refused =
      assertIs<DwfPreviewSessions.Started.Refused>(
        s.startAsync("d", 1, "c", 2, false) { DwfPreviewSessions.Resolution.Ok(source()) },
      )
    assertEquals(DwfPreviewSurface.TOO_MANY_SESSIONS, refused.code)
  }

  @Test
  fun `a failed session retries only once it can take a runtime slot back`() {
    val s = sessions(deferred, max = 1)
    runtime.failNext = "daemon crashed"
    val a = s.async(owner = "a")
    pending.removeFirst().run()
    assertEquals(DwfStage.FAILED, s.progress(a).stage)
    // Its slot went to the next start, which is live.
    val b = s.async(owner = "b")
    pending.removeFirst().run()
    assertEquals(DwfStage.LIVE, s.progress(b).stage)
    val before = runtime.commands.size
    assertIs<DwfPreviewSessions.Frame.Failed>(s.frame(a.id))
    assertEquals(before, runtime.commands.size, "a retry without a slot never reaches the daemon")
    assertEquals(1, s.sessionCount)
    s.close(b.id)
    assertIs<DwfPreviewSessions.Frame.Ok>(s.frame(a.id))
    assertEquals(DwfStage.LIVE, s.progress(a).stage)
    assertEquals(1, s.sessionCount)
  }

  @Test
  fun `a pushed face is screenshot only once it has drawn`() {
    val s = sessions()
    runtime.statusScript.addAll(listOf(waiting(0), waiting(0), waiting(1)))
    val session = assertIs<DwfPreviewSessions.Started.Ok>(s.start("d", 1, "a", source())).session
    assertIs<DwfPreviewSessions.Frame.Ok>(s.frame(session.id))
    val log = runtime.commands.map { it.cmd to FakeDwfRuntime.op(it) }
    val shot = log.indexOf("screenshot" to null)
    // Three first-frame polls (two waits between them), then the settle poll, then the shot.
    assertEquals(4, log.subList(0, shot).count { it == ("watchface" to "status") })
    assertEquals(listOf(200L, 200L), sleeps)
  }

  @Test
  fun `an override settles before the screenshot`() {
    val s = sessions()
    val session = assertIs<DwfPreviewSessions.Started.Ok>(s.start("d", 1, "a", source())).session
    s.frame(session.id)
    s.patch(session.id, buildJsonObject { put("batteryPercent", 50) })
    runtime.statusScript.add(
      buildJsonObject {
        put("kind", "app")
        put("frames", 9)
        put("rendering", true)
      },
    )
    sleeps.clear()
    assertIs<DwfPreviewSessions.Frame.Ok>(s.frame(session.id))
    assertEquals(listOf(100L), sleeps)
  }

  @Test
  fun `a session whose socket went away is released after the grace, not before`() {
    val s = sessions()
    val session = s.async()
    s.attach(session)
    clock += 10 * 60_000
    session.lastUsed = clock
    assertEquals(1, s.sessionCount, "an attached session is never abandoned")
    s.detach(session)
    clock += 30_000
    assertEquals(1, s.sessionCount, "a reload reattaches inside the grace")
    clock += 31_000
    assertEquals(0, s.sessionCount)
    assertNull(s.get(session.id))
  }

  @Test
  fun `a reload replaces the owner's detached session on the same design at once`() {
    val s = sessions(max = 4)
    val old = s.async(owner = "alice", design = "d")
    s.attach(old)
    val otherTab = s.async(owner = "alice", design = "d")
    s.attach(otherTab)
    val elsewhere = s.async(owner = "alice", design = "e")
    s.attach(elsewhere)
    s.detach(elsewhere)
    val someoneElse = s.async(owner = "bob", design = "d")
    s.attach(someoneElse)
    s.detach(someoneElse)
    s.detach(old)
    s.async(owner = "alice", design = "d")
    assertNull(s.get(old.id), "the reloaded pane's old session is released")
    assertNotNull(s.get(otherTab.id), "a session still showing in a tab is kept")
    assertNotNull(s.get(elsewhere.id), "another design's session is not this reload's")
    assertNotNull(s.get(someoneElse.id), "another owner's session is never touched")
  }

  @Test
  fun `faces that move between seconds are recognised`() {
    assertTrue(
      source("""<Transform target="alpha" value="sin([SECOND_MILLISECOND])"/>""").animates,
    )
    assertTrue(source("<WatchFace><Animation duration=\"1\"/></WatchFace>").animates)
    assertTrue(source("<SecondHand><Sweep frequency=\"15\"/></SecondHand>").animates)
    assertTrue(!source("<WatchFace><DigitalClock/></WatchFace>").animates)
    assertTrue(!source("<Text>[SECOND]</Text>").animates)
  }
}
