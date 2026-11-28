package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.SimulatorControlPolicy.Decision
import ee.schimke.composeai.cli.serve.SimulatorControlPolicy.Tier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class SimulatorControlPolicyTest {
  private val phone = listOf(SimulatorDevice.PHONE)
  private val watch = listOf(SimulatorDevice.WATCH)
  private val paired = listOf(SimulatorDevice.PHONE, SimulatorDevice.WATCH)

  private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun check(
    text: String,
    devices: List<SimulatorDevice> = phone,
    tier: Tier = Tier.VIEWER,
  ) = SimulatorControlPolicy.check(obj(text), devices, tier)

  @Test
  fun `input commands pass with only their own arguments`() {
    val d =
      assertIs<Decision.Allowed>(
        check("""{"cmd":"touch","action":"down","x":10,"y":20.5,"display":1}"""),
      )
    assertEquals(obj("""{"cmd":"touch","action":"down","x":10.0,"y":20.5}"""), d.command)
    assertEquals(SimulatorDevice.PHONE, d.device)
  }

  @Test
  fun `host path arguments never reach the simulator`() {
    // screenshot is the server's to take, not the client's
    assertIs<Decision.Refused>(check("""{"cmd":"screenshot","path":"/etc/passwd"}"""))
    val h =
      assertIs<Decision.Allowed>(check("""{"cmd":"hierarchy","path":"/tmp/x.json","flat":true}"""))
    assertEquals(obj("""{"cmd":"hierarchy","flat":true}"""), h.command)
    for (cmd in
      listOf(
        "files",
        "shell",
        "quit",
        "update",
        "restart",
        "snapshotData",
        "pick",
        "zoom",
        "reload",
      )) {
      assertIs<Decision.Refused>(check("""{"cmd":"$cmd"}""", tier = Tier.OPERATOR), cmd)
    }
  }

  @Test
  fun `a known argument with a bad value refuses the command`() {
    assertIs<Decision.Refused>(check("""{"cmd":"touch","action":"explode","x":1,"y":1}"""))
    assertIs<Decision.Refused>(check("""{"cmd":"tap","x":"10","y":1}"""))
    assertIs<Decision.Refused>(check("""{"cmd":"tap","x":1e9,"y":1}"""))
    assertIs<Decision.Refused>(
      check("""{"cmd":"swipe","x1":0,"y1":0,"x2":1,"y2":1,"durationMs":60000}"""),
    )
    assertIs<Decision.Refused>(check("""{"cmd":"text","text":"${"a".repeat(2001)}"}"""))
  }

  @Test
  fun `keys are an allowlist and are normalised`() {
    val d = assertIs<Decision.Allowed>(check("""{"cmd":"key","key":"keycode_back"}"""))
    assertEquals(obj("""{"cmd":"key","key":"BACK"}"""), d.command)
    assertIs<Decision.Allowed>(check("""{"cmd":"key","key":["DPAD_DOWN","ENTER"]}"""))
    assertIs<Decision.Refused>(check("""{"cmd":"key","key":"CALL"}"""))
  }

  @Test
  fun `operator commands need the operator tier`() {
    val text = """{"cmd":"permissions","grant":["android.permission.CAMERA"]}"""
    assertIs<Decision.Refused>(check(text, tier = Tier.VIEWER))
    assertIs<Decision.Allowed>(check(text, tier = Tier.OPERATOR))
    assertTrue("permissions" !in SimulatorControlPolicy.commands(Tier.VIEWER))
    assertTrue("permissions" in SimulatorControlPolicy.commands(Tier.OPERATOR))
  }

  @Test
  fun `a paired session routes by device and refuses one it does not run`() {
    val w =
      assertIs<Decision.Allowed>(check("""{"cmd":"rotary","device":"watch","delta":-1}""", paired))
    assertEquals(SimulatorDevice.WATCH, w.device)
    assertFalse("device" in w.command, "the server routes; the device is not passed as an argument")
    val p = assertIs<Decision.Allowed>(check("""{"cmd":"back"}""", paired))
    assertEquals(SimulatorDevice.PHONE, p.device)
    assertIs<Decision.Refused>(check("""{"cmd":"back","device":"watch"}""", phone))
    assertIs<Decision.Refused>(check("""{"cmd":"back","device":"tv"}""", paired))
  }

  @Test
  fun `device specific commands stay on their device`() {
    assertIs<Decision.Refused>(check("""{"cmd":"rotary","delta":1}""", phone))
    assertIs<Decision.Allowed>(check("""{"cmd":"rotary","delta":1}""", watch))
    assertIs<Decision.Refused>(check("""{"cmd":"rotate"}""", watch))
    assertIs<Decision.Allowed>(check("""{"cmd":"rotate","value":"landscape"}""", phone))
  }

  @Test
  fun `start activity keeps only the intent's plain fields`() {
    val d =
      assertIs<Decision.Allowed>(
        check(
          """{"cmd":"startActivity","intent":{"action":"android.intent.action.VIEW","data":"app://x","extras":{"f":"/etc"}}}""",
          tier = Tier.OPERATOR,
        ),
      )
    assertEquals(
      obj(
        """{"cmd":"startActivity","intent":{"action":"android.intent.action.VIEW","data":"app://x"}}""",
      ),
      d.command,
    )
  }

  @Test
  fun `redaction removes host paths but keeps device paths`() {
    val redacted =
      SimulatorControlPolicy.redact(
        obj(
          """{"apk":"/srv/a.apk","note":"loaded /srv/simulator/state/x","nested":[{"hostPath":"/srv"}],"file":"/data/x","uri":"/sdcard/Download/a.txt"}""",
        ),
        listOf("/srv/simulator"),
      )
    assertEquals(obj("""{"note":"…","nested":[{}],"uri":"/sdcard/Download/a.txt"}"""), redacted)
  }

  @Test
  fun `viewers see only listed events and fields`() {
    val roots = listOf("/srv")
    assertNull(
      SimulatorControlPolicy.event(
        obj("""{"event":"conversion","classesJar":"/srv/x.jar"}"""),
        Tier.VIEWER,
        roots,
      ),
    )
    assertNull(
      SimulatorControlPolicy.event(
        obj("""{"event":"log","lines":["secret"]}"""),
        Tier.VIEWER,
        roots,
      ),
    )
    assertEquals(
      obj("""{"event":"activity","state":"resumed","activity":"a.Main"}"""),
      SimulatorControlPolicy.event(
        obj("""{"event":"activity","state":"resumed","activity":"a.Main","task":7}"""),
        Tier.VIEWER,
        roots,
      ),
    )
    // A Data Layer path is the app's message path, not a file.
    assertEquals(
      obj("""{"event":"datalayer","kind":"message","path":"/start"}"""),
      SimulatorControlPolicy.event(
        obj("""{"event":"datalayer","kind":"message","path":"/start","data":"x"}"""),
        Tier.VIEWER,
        roots,
      ),
    )
    // The operator sees every event, redacted.
    assertEquals(
      obj("""{"event":"conversion","ms":3}"""),
      SimulatorControlPolicy.event(
        obj("""{"event":"conversion","classesJar":"/srv/x.jar","ms":3}"""),
        Tier.OPERATOR,
        roots,
      ),
    )
  }
}
