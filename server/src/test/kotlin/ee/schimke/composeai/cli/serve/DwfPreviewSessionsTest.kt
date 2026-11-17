package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class DwfPreviewSessionsTest {
  private val runtime = FakeDwfRuntime()
  private var clock = 1_000_000L
  private val sessions =
    DwfPreviewSessions(runtime, maxSessions = 2, idleMillis = 60_000, now = { clock })

  private fun open(src: DwfSource = source(), design: String = "d"): DwfPreviewSessions.Session =
    assertIs<DwfPreviewSessions.Started.Ok>(sessions.start(design, 7, "alice", src)).session

  private fun patch(id: String, text: String) =
    assertIs<DwfControls.Patched.Ok>(sessions.patch(id, Json.parseToJsonElement(text).jsonObject))

  private fun ok(id: String) = assertIs<DwfPreviewSessions.Frame.Ok>(sessions.frame(id))

  private fun cmds(from: Int = 0) =
    runtime.commands.drop(from).map { it.cmd to FakeDwfRuntime.op(it) }

  @Test
  fun `a host without the runtime refuses to start and says why`() {
    runtime.availability =
      DwfApkRuntime.Availability.Unavailable(DwfPreviewSurface.RUNTIME_MISSING, "no runtime APK")
    val refused =
      assertIs<DwfPreviewSessions.Started.Refused>(sessions.start("d", 1, "a", source()))
    assertEquals(DwfPreviewSurface.RUNTIME_MISSING, refused.code)
    assertEquals("no runtime APK", refused.reason)
    assertEquals(0, sessions.sessionCount)
    assertTrue(runtime.commands.isEmpty())
  }

  @Test
  fun `an invalid source never reaches packaging`() {
    val refused =
      assertIs<DwfPreviewSessions.Started.Refused>(sessions.start("d", 1, "a", source(version = 9)))
    assertEquals(DwfPreviewSurface.SOURCE_INVALID, refused.code)
    assertTrue(runtime.packaged.isEmpty())
  }

  @Test
  fun `the first frame packages, pushes, applies the full state and draws`() {
    val s = open()
    patch(s.id, """{"batteryPercent":80,"heartRate":70}""")
    val frame = ok(s.id)
    assertEquals(1, frame.seq)
    assertEquals(1, frame.widthPx)
    assertEquals(
      listOf(
        "watchface" to "push",
        "ambient" to null,
        "health" to null,
        "battery" to null,
        "watchface" to "config",
        "screenshot" to null,
        "watchface" to "status",
      ),
      cmds(),
    )
    assertEquals(1, runtime.packaged.size)
    // The screenshot lands in a temp file this server reads and removes.
    assertTrue(frame.png.isNotEmpty())
  }

  @Test
  fun `later frames send only the diff and package once`() {
    val s = open()
    ok(s.id)
    val seen = runtime.commands.size
    patch(s.id, """{"batteryPercent":10}""")
    assertEquals(2, ok(s.id).seq)
    assertEquals(
      listOf("battery" to null, "screenshot" to null, "watchface" to "status"),
      cmds(seen),
    )
    // An unchanged redraw is a screenshot and a status check, nothing else.
    val again = runtime.commands.size
    ok(s.id)
    assertEquals(listOf("screenshot" to null, "watchface" to "status"), cmds(again))
    assertEquals(1, runtime.packaged.size)
    assertEquals(1, runtime.connections.size)
  }

  @Test
  fun `sessions share one daemon and each is replayed when it takes the face back`() {
    val a = open(source("<WatchFace a/>"))
    val b = open(source("<WatchFace b/>"))
    patch(a.id, """{"batteryPercent":11}""")
    patch(b.id, """{"batteryPercent":22}""")
    ok(a.id)
    var seen = runtime.commands.size
    ok(b.id)
    // b takes the daemon: push + its whole state, not a diff from a's.
    assertEquals("push", FakeDwfRuntime.op(runtime.commands[seen]))
    assertTrue(
      runtime.commands.drop(seen).any {
        it.cmd == "battery" && it.payload.toString() == """{"level":22}"""
      },
    )
    seen = runtime.commands.size
    ok(a.id)
    assertEquals("push", FakeDwfRuntime.op(runtime.commands[seen]))
    assertTrue(
      runtime.commands.drop(seen).any {
        it.cmd == "battery" && it.payload.toString() == """{"level":11}"""
      },
    )
    assertEquals(1, runtime.connections.size)
    assertEquals(2, runtime.packaged.size)
  }

  @Test
  fun `a ticking clock advances with the wall clock`() {
    val s = open()
    patch(s.id, """{"timeMillis":5000,"ticking":true}""")
    ok(s.id)
    var seen = runtime.commands.size
    clock += 3_000
    ok(s.id)
    val clockCommand = runtime.commands.drop(seen).single { it.cmd == "clock" }
    assertEquals("""{"timeMillis":8000}""", clockCommand.payload.toString())
    seen = runtime.commands.size
    // Stopping the tick freezes the clock where it is.
    patch(s.id, """{"ticking":false}""")
    clock += 10_000
    ok(s.id)
    assertTrue(
      runtime.commands.drop(seen).none {
        it.cmd == "clock" && it.payload.toString() != """{"timeMillis":8000}"""
      },
    )
  }

  @Test
  fun `ticking with no set time counts from when the tab started it`() {
    val s = open()
    patch(s.id, """{"ticking":true}""")
    ok(s.id)
    clock += 2_000
    ok(s.id)
    val times = runtime.commands.filter { it.cmd == "clock" }.map { it.payload.toString() }
    assertEquals("""{"timeMillis":$clock}""", times.last())
  }

  @Test
  fun `the simulator's built-in face is a failure, not a frame`() {
    val s = open()
    runtime.statusAnswer =
      kotlinx.serialization.json.buildJsonObject {
        put("kind", kotlinx.serialization.json.JsonPrimitive("built-in"))
      }
    val failed = assertIs<DwfPreviewSessions.Frame.Failed>(sessions.frame(s.id))
    assertEquals(DwfPreviewSurface.DAEMON_FAILED, failed.code)
    assertTrue(failed.reason.contains("built-in"))
  }

  @Test
  fun `a watch face that errors reports the runtime's error`() {
    val s = open()
    runtime.statusAnswer = Json.parseToJsonElement("""{"kind":"app","error":"bad tag Foo"}""")
    val failed = assertIs<DwfPreviewSessions.Frame.Failed>(sessions.frame(s.id))
    assertTrue(failed.reason.contains("bad tag Foo"))
  }

  @Test
  fun `a daemon failure drops it and the next frame starts a fresh one with the state replayed`() {
    val s = open()
    patch(s.id, """{"batteryPercent":33}""")
    ok(s.id)
    runtime.failNext = "daemon went away"
    val failed = assertIs<DwfPreviewSessions.Frame.Failed>(sessions.frame(s.id))
    assertTrue(failed.reason.contains("daemon went away"))
    assertTrue(runtime.connections.single().closed)
    // Recovery: a second connection, pushed and replayed in full.
    ok(s.id)
    assertEquals(2, runtime.connections.size)
    val second = runtime.connections[1].log
    assertEquals("push", FakeDwfRuntime.op(second.first()))
    assertTrue(second.any { it.cmd == "battery" && it.payload.toString() == """{"level":33}""" })
  }

  @Test
  fun `a bad patch changes nothing and a missing session is reported`() {
    val s = open()
    val bad = sessions.patch(s.id, Json.parseToJsonElement("""{"batteryPercent":500}""").jsonObject)
    assertIs<DwfControls.Patched.Invalid>(bad)
    assertNull(s.controls.batteryPercent)
    assertIs<DwfControls.Patched.Invalid>(
      sessions.patch("nope", Json.parseToJsonElement("{}").jsonObject),
    )
    assertEquals(
      "DWF_NO_SESSION",
      assertIs<DwfPreviewSessions.Frame.Failed>(sessions.frame("nope")).code,
    )
  }

  @Test
  fun `sessions are bounded and idle ones expire`() {
    open()
    open()
    val full = assertIs<DwfPreviewSessions.Started.Refused>(sessions.start("d", 1, "a", source()))
    assertEquals(DwfPreviewSurface.TOO_MANY_SESSIONS, full.code)
    clock += 61_000
    // Both idle ones are reaped to make room.
    open()
    assertEquals(1, sessions.sessionCount)
  }

  @Test
  fun `an expired session is gone on use`() {
    val s = open()
    clock += 61_000
    assertNull(sessions.get(s.id))
  }

  @Test
  fun `the last session out stops the daemon`() {
    val a = open()
    val b = open(source("<WatchFace b/>"))
    ok(a.id)
    sessions.close(a.id)
    assertFalse(runtime.connections.single().closed)
    sessions.close(b.id)
    assertTrue(runtime.connections.single().closed)
    assertNotNull(sessions.availability())
  }

  @Test
  fun `png size is read from the header`() {
    assertEquals(1 to 1, DwfPreviewSessions.pngSize(FakeDwfRuntime.PNG_1X1))
    assertEquals(null to null, DwfPreviewSessions.pngSize(byteArrayOf(1, 2, 3)))
  }
}
