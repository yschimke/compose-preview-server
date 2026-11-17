package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DwfPreviewModelTest {

  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  private fun patched(controls: DwfControls, text: String): DwfControls =
    assertIs<DwfControls.Patched.Ok>(controls.patch(json(text))).controls

  // ---- source gate ----------------------------------------------------------------------

  @Test
  fun `a plain WFF source is accepted`() {
    assertNull(source().problem())
    assertNull(
      DwfSource("<WatchFace/>", assets = mapOf("drawable/bg.png" to byteArrayOf(1))).problem(),
    )
  }

  @Test
  fun `a source is refused for version, entities, emptiness and unsafe asset paths`() {
    assertTrue(source(version = 0).problem()!!.contains("version"))
    assertTrue(source(version = 5).problem()!!.contains("version"))
    assertTrue(source("   ").problem()!!.contains("empty"))
    assertTrue(source("not xml").problem()!!.contains("element"))
    assertTrue(
      source("<!DOCTYPE x [<!ENTITY a SYSTEM \"file:///etc/passwd\">]><WatchFace/>")
        .problem()!!
        .contains("DOCTYPE"),
    )
    for (bad in listOf("../x.png", "/abs.png", "a/../../b", "a//b", "..", "c:\\x", "a b.png")) {
      assertTrue(
        DwfSource("<WatchFace/>", assets = mapOf(bad to byteArrayOf(0)))
          .problem()!!
          .contains("asset path"),
        bad,
      )
    }
  }

  @Test
  fun `the digest identifies the exact package`() {
    val a = DwfSource("<WatchFace/>", assets = mapOf("a" to byteArrayOf(1)))
    assertEquals(
      a.digest(),
      DwfSource("<WatchFace/>", assets = mapOf("a" to byteArrayOf(1))).digest(),
    )
    assertNotEquals(
      a.digest(),
      DwfSource("<WatchFace/>", assets = mapOf("a" to byteArrayOf(2))).digest(),
    )
    assertNotEquals(a.digest(), DwfSource("<WatchFace/>", 3, mapOf("a" to byteArrayOf(1))).digest())
  }

  // ---- controls patch -------------------------------------------------------------------

  @Test
  fun `a patch sets the typed controls and leaves the rest`() {
    val c =
      patched(
        DwfControls(batteryPercent = 10),
        """{"timeMillis":1700000000000,"ticking":true,"ambient":true,"burnInProtection":true,
           "heartRate":72.5,"steps":4200,"flavor":"dark","config":{"theme":"blue"}}""",
      )
    assertEquals(1_700_000_000_000, c.timeMillis)
    assertTrue(c.ticking && c.ambient)
    assertEquals(true, c.burnInProtection)
    assertNull(c.lowBitAmbient)
    assertEquals(72.5, c.heartRate)
    assertEquals(4200, c.steps)
    assertEquals(10, c.batteryPercent) // untouched
    assertEquals("dark", c.flavorId)
    assertEquals(mapOf("theme" to "blue"), c.config)
  }

  @Test
  fun `JSON null clears a control`() {
    val c =
      patched(
        DwfControls(heartRate = 70.0, flavorId = "x", timeMillis = 5),
        """{"heartRate":null,"flavor":null,"timeMillis":null}""",
      )
    assertNull(c.heartRate)
    assertNull(c.flavorId)
    assertNull(c.timeMillis)
  }

  @Test
  fun `a bad control refuses the whole patch`() {
    val start = DwfControls(batteryPercent = 50)
    for (bad in
      listOf(
        """{"batteryPercent":101}""",
        """{"batteryPercent":-1}""",
        """{"batteryPercent":"high"}""",
        """{"heartRate":301}""",
        """{"steps":-5}""",
        """{"ambient":"yes"}""",
        """{"unknown":1}""",
        """{"config":{"a":{"nested":1}}}""",
        """{"complications":{"99":{"type":"SHORT_TEXT"}}}""",
        """{"complications":{"0":{"type":"NOPE"}}}""",
        // a good key followed by a bad one: nothing applies
        """{"batteryPercent":20,"heartRate":999}""",
      )) {
      val result = start.patch(json(bad))
      assertIs<DwfControls.Patched.Invalid>(result, bad)
    }
    assertEquals(50, start.batteryPercent)
  }

  @Test
  fun `complication slots merge, clear on null and only accept inline images`() {
    val start =
      patched(
        DwfControls(),
        """{"complications":{"0":{"type":"short_text","text":"72","title":"HR"},"1":{"type":"RANGED_VALUE","value":3,"min":0,"max":10}}}""",
      )
    assertEquals("SHORT_TEXT", start.complications.getValue(0).type)
    assertEquals(3.0, start.complications.getValue(1).value)
    val next =
      patched(
        start,
        """{"complications":{"1":null,"2":{"type":"SMALL_IMAGE",
          "smallImage":"data:image/png;base64,AAAA","photoImage":"file:///etc/passwd","monochromaticImage":"https://x.test/i.png"}}}""",
      )
    assertEquals(setOf(0, 2), next.complications.keys)
    val img = next.complications.getValue(2)
    assertEquals("data:image/png;base64,AAAA", img.smallImage)
    // file: and http(s): would have the daemon read the host or fetch for the client.
    assertNull(img.photoImage)
    assertNull(img.monochromaticImage)
  }

  // ---- control → command mapping ---------------------------------------------------------

  @Test
  fun `a fresh face is brought to its full state in the bridge's order`() {
    val controls =
      DwfControls(
        timeMillis = 1_700_000_000_000,
        ambient = true,
        burnInProtection = true,
        lowBitAmbient = false,
        heartRate = 70.0,
        steps = 100,
        batteryPercent = 42,
        flavorId = "dark",
        config = mapOf("theme" to "blue"),
        complications = mapOf(0 to DwfComplication("SHORT_TEXT", text = "72", title = "HR")),
      )
    val commands = DwfDaemonCommands.full(controls)
    assertEquals(
      listOf("ambient", "clock", "health", "battery", "watchface", "watchface"),
      commands.map { it.cmd },
    )
    assertEquals(
      """{"state":"AMBIENT","burnIn":true,"lowBit":false}""",
      commands[0].payload.toString(),
    )
    assertEquals("""{"timeMillis":1700000000000}""", commands[1].payload.toString())
    assertEquals("""{"heartRate":70.0,"steps":100}""", commands[2].payload.toString())
    assertEquals("""{"level":42}""", commands[3].payload.toString())
    assertEquals(
      """{"op":"config","reset":true,"flavor":"dark","values":{"theme":"blue"}}""",
      commands[4].payload.toString(),
    )
    assertEquals(
      """{"op":"complication","slot":0,"type":"SHORT_TEXT","text":"72","title":"HR"}""",
      commands[5].payload.toString(),
    )
  }

  @Test
  fun `a default face still gets its ambient state, and nothing it was never given`() {
    val commands = DwfDaemonCommands.full(DwfControls())
    // Ambient is always stated (the bridge does too), config is reset; no clock/health/battery.
    assertEquals(listOf("ambient", "watchface"), commands.map { it.cmd })
    assertEquals("""{"state":"INTERACTIVE"}""", commands[0].payload.toString())
    assertEquals("""{"op":"config","reset":true}""", commands[1].payload.toString())
  }

  @Test
  fun `a change sends only what changed`() {
    val before = DwfControls(batteryPercent = 40, heartRate = 60.0)
    assertEquals(emptyList(), DwfDaemonCommands.diff(before, before))
    val battery = DwfDaemonCommands.diff(before, before.copy(batteryPercent = 41))
    assertEquals(listOf("battery"), battery.map { it.cmd })
    val ambient = DwfDaemonCommands.diff(before, before.copy(ambient = true))
    assertEquals(listOf("ambient"), ambient.map { it.cmd })
    val time = DwfDaemonCommands.diff(before, before.copy(timeMillis = 9))
    assertEquals(9, time.single().payload["timeMillis"]!!.jsonPrimitive.int)
  }

  @Test
  fun `a slot that lost its data is cleared and EMPTY clears too`() {
    val slot = DwfComplication("SHORT_TEXT", text = "x")
    val before = DwfControls(complications = mapOf(0 to slot, 1 to slot))
    val after = DwfControls(complications = mapOf(1 to slot, 2 to DwfComplication("EMPTY")))
    val commands = DwfDaemonCommands.diff(before, after)
    assertEquals(
      listOf("""{"op":"uncomplication","slot":0}""", """{"op":"uncomplication","slot":2}"""),
      commands.map { it.payload.toString() },
    )
  }

  @Test
  fun `flavor and option changes resend the absolute configuration`() {
    val before = DwfControls(flavorId = "a", config = mapOf("x" to "1"))
    val commands = DwfDaemonCommands.diff(before, before.copy(config = mapOf("x" to "2")))
    assertEquals(
      """{"op":"config","reset":true,"flavor":"a","values":{"x":"2"}}""",
      commands.single().payload.toString(),
    )
  }

  @Test
  fun `weights and colors reach a weighted-elements complication`() {
    val c =
      DwfDaemonCommands.full(
          DwfControls(
            complications =
              mapOf(
                3 to
                  DwfComplication(
                    "WEIGHTED_ELEMENTS",
                    colors = listOf("#ff0000", "#00ff00"),
                    weights = listOf(1.0, 2.0),
                  ),
              ),
          ),
        )
        .last()
    assertEquals(
      """{"op":"complication","slot":3,"type":"WEIGHTED_ELEMENTS","colors":["#ff0000","#00ff00"],"weights":[1.0,2.0]}""",
      c.payload.toString(),
    )
    assertEquals(JsonPrimitive(3), c.payload["slot"])
  }

  @Test
  fun `the stdio verbs are the bridge's`() {
    assertEquals(
      """{"op":"push","apk":"/a.apk","activate":true}""",
      DwfDaemonCommands.push("/a.apk").payload.toString(),
    )
    assertEquals(
      """{"path":"/s.png","square":false}""",
      DwfDaemonCommands.screenshot("/s.png").payload.toString(),
    )
    assertEquals("""{"op":"status"}""", DwfDaemonCommands.status().payload.toString())
  }
}
