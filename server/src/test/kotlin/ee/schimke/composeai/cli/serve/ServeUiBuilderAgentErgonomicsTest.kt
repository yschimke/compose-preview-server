package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.HeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * compose-preview-server#1255 against the real server, wired as `ServeRunner` wires it: check a
 * design before proposing it, see it on several devices in one picture, wait for a person's
 * verdict, and join a design to the pull request implementing it.
 */
class ServeUiBuilderAgentErgonomicsTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private var running: Running? = null

  @AfterTest
  fun tearDown() {
    running?.close()
  }

  // ---- ui_builder_check_design ------------------------------------------------------------------

  @Test
  fun `a clean design checks clean, with a one-line summary first`() {
    val server = start()
    create(server, cleanDocument())

    val reply = check(server, """{"designId":"agent-screen"}""")

    assertEquals(true, reply["ok"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    assertEquals(listOf("schema", "catalog", "a11y"), reply.strings("checks"))
    assertEquals(0, reply["errors"]!!.jsonPrimitive.int)
    assertTrue(reply["findings"]!!.jsonArray.isEmpty(), reply.toString())
    assertTrue(reply.text("summary").startsWith("No problems found"), reply.toString())
    // The summary is the first thing in the reply, so a reader truncating it still has the verdict.
    assertEquals("schema", reply.keys.first())
    assertEquals("summary", reply.keys.elementAt(2))
  }

  @Test
  fun `accessibility problems are reported by node, with the number behind each`() {
    val server = start()

    val reply =
      check(server, """{"document":${documentJson(inaccessibleDocument())},"checks":["a11y"]}""")

    assertEquals(false, reply["ok"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    assertEquals(listOf("a11y"), reply.strings("checks"))
    val byCode = reply["findings"]!!.jsonArray.map { it.jsonObject }.groupBy { it.text("code") }
    // An icon button with nothing to announce, and the icon inside it.
    assertEquals("play", byCode.getValue("missingLabel").single().text("nodeId"))
    assertEquals("error", byCode.getValue("missingLabel").single().text("severity"))
    assertEquals(
      "playIcon",
      byCode.getValue("missingContentDescription").single().text("nodeId"),
    )
    // Declared 32dp, under the 48dp a touch target needs.
    val touch = byCode.getValue("touchTargetTooSmall").single()
    assertEquals("labelled", touch.text("nodeId"))
    assertEquals(32.0, touch["measured"]!!.jsonObject["width"]!!.jsonPrimitive.content.toDouble())
    // Grey on grey, both literal colours: an error, with the ratio.
    val contrast = byCode.getValue("lowContrast").single()
    assertEquals("faint", contrast.text("nodeId"))
    assertEquals("error", contrast.text("severity"))
    assertTrue(contrast["measured"]!!.jsonObject["ratio"]!!.jsonPrimitive.content.toDouble() < 4.5)
    // Body text in a 20dp box clips at 200%.
    assertEquals("clipped", byCode.getValue("textMayClip").single().text("nodeId"))
    // The decorative icon (contentDescription: null) and the labelled button raise nothing else.
    assertTrue(byCode.values.flatten().none { it.text("nodeId") == "decorative" })
    assertTrue(reply.text("summary").startsWith("Fix before showing it"), reply.toString())
    assertTrue(reply.text("summary").contains("missingLabel on `play`"), reply.toString())
  }

  @Test
  fun `operations are checked on a scratch copy, and nothing is saved`() {
    val server = start()
    create(server, cleanDocument())
    val before = revisionOf(server)
    val insert =
      operations(
        InsertNodeMutationV1(
          node =
            DesignNodeV1(
              id = "newIcon",
              componentId = "m3/icon",
              properties = mapOf("iconKey" to StringValueV1("add")),
            ),
          location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
        )
      )

    val reply =
      check(
        server,
        // The fixture's component records stop short of `m3/icon`, so the export gate would refuse
        // the icon; this test is about the dry run, so it asks for the accessibility pass only.
        """{"designId":"agent-screen","baseRevision":$before,"operations":$insert,"checks":["a11y"]}""",
      )

    assertEquals(true, reply["dryRun"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    assertEquals(1, reply["findings"]!!.jsonArray.size, reply.toString())
    val finding = reply["findings"]!!.jsonArray.single().jsonObject
    assertEquals("missingContentDescription", finding.text("code"))
    assertEquals("newIcon", finding.text("nodeId"))
    // Warnings do not block: the batch would be accepted.
    assertEquals(true, reply["ok"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(before, revisionOf(server))
    assertFalse(
      envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")
        .contains("newIcon")
    )
  }

  @Test
  fun `a past revision is checked as it was`() {
    val server = start()
    create(server, cleanDocument())
    val clean = revisionOf(server)
    envelope(
      server,
      ServeUiBuilderMcp.APPLY,
      """{"designId":"agent-screen","operationId":"op-1","baseRevision":$clean,"operations":${operations(
        InsertNodeMutationV1(
          node = DesignNodeV1(id = "newIcon", componentId = "m3/icon", properties = mapOf("iconKey" to StringValueV1("add"))),
          location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
        )
      )}}""",
    )

    val then = check(server, """{"designId":"agent-screen","revision":$clean,"checks":["a11y"]}""")
    val now = check(server, """{"designId":"agent-screen","checks":["a11y"]}""")

    assertEquals(clean, then["revision"]!!.jsonPrimitive.long)
    assertTrue(then["findings"]!!.jsonArray.isEmpty(), then.toString())
    assertEquals("newIcon", now["findings"]!!.jsonArray.single().jsonObject.text("nodeId"))
  }

  @Test
  fun `a malformed check is refused with a sentence, and a schema error is a finding`() {
    val server = start()
    create(server, cleanDocument())
    assertError(server, ServeUiBuilderMcp.CHECK_DESIGN, "{}", "exactly one of")
    assertError(
      server,
      ServeUiBuilderMcp.CHECK_DESIGN,
      """{"designId":"agent-screen","checks":["spelling"]}""",
      "spelling",
    )
    assertError(
      server,
      ServeUiBuilderMcp.CHECK_DESIGN,
      """{"designId":"agent-screen","revision":1,"operations":[]}""",
      "baseRevision",
    )
    assertError(server, ServeUiBuilderMcp.CHECK_DESIGN, """{"designId":"nobody-made-this"}""", "")

    val reply = check(server, """{"document":{"id":"x"}}""")
    assertEquals(false, reply["ok"]!!.jsonPrimitive.booleanOrNull)
    assertEquals("schema", reply["findings"]!!.jsonArray.first().jsonObject.text("check"))
    // With no document there is nothing for the accessibility pass to read, and it says so.
    assertEquals("a11y", reply["skipped"]!!.jsonArray.single().jsonObject.text("check"))
  }

  @Test
  fun `check is a read, and measuring on a render asks for the export grant`() {
    val unit = service(stateDirectory.resolve("unit"))
    val mcp = ServeUiBuilderMcp(unit)
    assertEquals(UiBuilderRouteCapability.READ, mcp.capabilityFor(ServeUiBuilderMcp.CHECK_DESIGN))
    assertNull(mcp.additionalCapabilityFor(ServeUiBuilderMcp.CHECK_DESIGN, JsonObject(emptyMap())))
    assertEquals(
      UiBuilderRouteCapability.EXPORT,
      mcp.additionalCapabilityFor(
        ServeUiBuilderMcp.CHECK_DESIGN,
        JsonObject(mapOf("rendered" to JsonPrimitive(true))),
      ),
    )
    // No native lane here: the check still runs, from declared sizes, and says what it skipped.
    val server = start()
    create(server, cleanDocument())
    val reply = check(server, """{"designId":"agent-screen","rendered":true}""")
    assertEquals("a11y.rendered", reply["skipped"]!!.jsonArray.single().jsonObject.text("check"))
  }

  // ---- ui_builder_render_design_matrix ----------------------------------------------------------

  @Test
  fun `one call draws every device and theme on one sheet, linked and typed`() {
    val server = start()
    create(server, cleanDocument())
    val revision = revisionOf(server)

    val result =
      call(
        server,
        ServeUiBuilderMcp.RENDER_DESIGN_MATRIX,
        """{"designId":"agent-screen","devices":["phone",{"widthDp":800,"heightDp":400,"label":"Wide"}],"themes":["light","dark"],"inline":true}""",
      )

    assertNull(result["isError"], result.toString())
    val content = result["content"]!!.jsonArray.map { it.jsonObject }
    val reply = Json.parseToJsonElement(content.first().text("text")).jsonObject
    assertEquals(reply, result["structuredContent"])
    val errors =
      SchemaCheck(outputSchema(server, ServeUiBuilderMcp.RENDER_DESIGN_MATRIX)).errors(reply)
    assertTrue(errors.isEmpty(), "$errors in $reply")
    assertNull(reply["imageBase64"], "the bytes never travel as text")
    assertTrue(reply["image"]!!.jsonObject.text("url").startsWith(PUBLIC_ORIGIN))
    assertTrue(content.any { it.text("type") == "resource_link" })

    val cells = reply["cells"]!!.jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("phone/light", "phone/dark", "custom-1/light", "custom-1/dark"),
      cells.map { "${it.text("device")}/${it.text("theme")}" },
    )
    assertTrue(cells.all { it["rendered"]!!.jsonPrimitive.booleanOrNull == true }, cells.toString())
    assertEquals(2, reply["columns"]!!.jsonPrimitive.int)

    // Each cell really was drawn under its own environment: the stand-in exporter paints dark
    // themes dark and sizes the frame from the device, and the sheet keeps both.
    val sheet =
      ImageIO.read(
        Base64.getDecoder()
          .decode(content.single { it.text("type") == "image" }.text("data"))
          .inputStream()
      )
    assertEquals(reply["image"]!!.jsonObject["widthPx"]!!.jsonPrimitive.int, sheet.width)
    fun centre(cell: JsonObject): Int {
      val x = cell["x"]!!.jsonPrimitive.int + cell["width"]!!.jsonPrimitive.int / 2
      val y = cell["y"]!!.jsonPrimitive.int + cell["height"]!!.jsonPrimitive.int / 2
      return sheet.getRGB(x, y) and 0xffffff
    }
    assertEquals(LIGHT_FILL, centre(cells[0]))
    assertEquals(DARK_FILL, centre(cells[1]))
    // The phone is portrait and the custom device landscape, as their boxes on the sheet say.
    assertTrue(cells[0]["height"]!!.jsonPrimitive.int > cells[0]["width"]!!.jsonPrimitive.int)
    assertTrue(cells[2]["width"]!!.jsonPrimitive.int > cells[2]["height"]!!.jsonPrimitive.int)
    // Drawing the matrix wrote nothing to the design.
    assertEquals(revision, revisionOf(server))
  }

  @Test
  fun `the default devices follow the design's form factor, and a bad matrix is refused`() {
    val server = start()
    create(server, cleanDocument())

    val defaults = matrix(server, """{"designId":"agent-screen"}""")
    assertEquals(
      listOf("phone", "foldable_unfolded", "tablet_landscape"),
      defaults["cells"]!!.jsonArray.map { it.jsonObject.text("device") },
    )
    val wear = matrix(server, """{"designId":"agent-screen","formFactor":"wear"}""")
    assertTrue(
      wear["cells"]!!.jsonArray.all { it.jsonObject["round"]!!.jsonPrimitive.booleanOrNull == true }
    )

    val tool = ServeUiBuilderMcp.RENDER_DESIGN_MATRIX
    assertError(server, tool, """{"designId":"agent-screen","devices":["pager"]}""", "phone_small")
    assertError(
      server,
      tool,
      """{"designId":"agent-screen","devices":["phone"],"formFactor":"tablet"}""",
      "not both",
    )
    assertError(
      server,
      tool,
      """{"designId":"agent-screen","formFactor":"adaptive","themes":["light","dark"],"fontScales":[1,1.5,2]}""",
      "more than 16",
    )
    assertError(server, tool, """{"designId":"agent-screen","fontScales":[9]}""", "fontScales")
    assertError(server, tool, """{"designId":"agent-screen","themes":["sepia"]}""", "sepia")
    assertError(server, tool, """{"designId":"nobody-made-this"}""", "")
  }

  @Test
  fun `the matrix is advertised only where the host can render on a scratch copy`() {
    assertTrue(ServeUiBuilderMcp.RENDER_DESIGN_MATRIX in tools(start()))
    running?.close()
    running = null
    val bare = start(withValidator = false, directory = stateDirectory.resolve("bare"))
    assertFalse(ServeUiBuilderMcp.RENDER_DESIGN_MATRIX in tools(bare))
    assertTrue(ServeUiBuilderMcp.CHECK_DESIGN in tools(bare))
  }

  // ---- decisions
  // ---------------------------------------------------------------------------------

  @Test
  fun `an agent's verdict is recorded as an agent's, and is idempotent by id`() {
    val server = start()
    create(server, cleanDocument())

    val first =
      decide(
        server,
        """{"designId":"agent-screen","revision":1,"verdict":"approve","decisionId":"d-1","note":"looks right"}""",
      )
    val again =
      decide(
        server,
        """{"designId":"agent-screen","revision":1,"verdict":"approve","decisionId":"d-1","note":"looks right"}""",
      )

    assertEquals("agent", first["latest"]!!.jsonObject.text("deciderKind"))
    assertEquals(first["sequence"], again["sequence"])
    assertTrue(again.text("summary").startsWith("Already recorded"), again.toString())
    assertError(
      server,
      ServeUiBuilderMcp.RECORD_DECISION,
      """{"designId":"agent-screen","revision":1,"verdict":"reject","decisionId":"d-1"}""",
      "different decision",
    )
    assertError(
      server,
      ServeUiBuilderMcp.RECORD_DECISION,
      """{"designId":"agent-screen","revision":1,"verdict":"maybe"}""",
      "approve, reject",
    )
    assertError(
      server,
      ServeUiBuilderMcp.RECORD_DECISION,
      """{"designId":"agent-screen","verdict":"approve"}""",
      "revision",
    )

    // A person's wait is not answered by an agent's verdict...
    val forPerson = awaitDecision(server, """{"designId":"agent-screen","waitSeconds":0}""")
    assertEquals(true, forPerson["timedOut"]!!.jsonPrimitive.booleanOrNull)
    assertNull(forPerson["latest"])
    // ...and an agent that asks for anyone's sees it.
    val anyone =
      awaitDecision(server, """{"designId":"agent-screen","waitSeconds":0,"from":"anyone"}""")
    assertEquals(false, anyone["timedOut"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(true, anyone["approved"]!!.jsonPrimitive.booleanOrNull)
  }

  @Test
  fun `a waiting agent wakes when a person decides, and polling is idempotent`() {
    val server = start()
    create(server, cleanDocument())

    val idle = awaitDecision(server, """{"designId":"agent-screen","waitSeconds":0}""")
    assertEquals(idle, awaitDecision(server, """{"designId":"agent-screen","waitSeconds":0}"""))
    val cursor = idle["sequence"]!!.jsonPrimitive.long

    val waiting = CompletableFuture.supplyAsync {
      awaitDecision(
        server,
        """{"designId":"agent-screen","afterSequence":$cursor,"revision":1,"waitSeconds":20}""",
      )
    }
    Thread.sleep(300)
    val posted =
      http(
        server,
        "POST",
        "/api/ui-builder/v1/designs/agent-screen/decisions",
        """{"revision":1,"verdict":"reject","note":"the play icon reads as a cross"}""",
      )
    assertEquals(200, posted.first, posted.second)

    val woken = waiting.get(20, java.util.concurrent.TimeUnit.SECONDS)
    assertEquals(false, woken["timedOut"]!!.jsonPrimitive.booleanOrNull, woken.toString())
    assertEquals(false, woken["approved"]!!.jsonPrimitive.booleanOrNull)
    val latest = woken["latest"]!!.jsonObject
    assertEquals("human", latest.text("deciderKind"))
    assertEquals("reject", latest.text("verdict"))
    assertTrue(woken.text("summary").contains("the play icon reads as a cross"), woken.toString())

    // The cursor it hands back is past that decision, so the next wait does not return it again —
    // but `latest` still says what the verdict is, so a routine checking back later needs one call.
    val next = woken["sequence"]!!.jsonPrimitive.long
    val after =
      awaitDecision(server, """{"designId":"agent-screen","afterSequence":$next,"waitSeconds":0}""")
    assertEquals(true, after["timedOut"]!!.jsonPrimitive.booleanOrNull)
    assertEquals("reject", after["latest"]!!.jsonObject.text("verdict"))
    // A wait for another revision is not answered by this one.
    val other =
      awaitDecision(server, """{"designId":"agent-screen","revision":7,"waitSeconds":0}""")
    assertEquals(true, other["timedOut"]!!.jsonPrimitive.booleanOrNull)
  }

  @Test
  fun `the decision routes need a credential and a design the caller can read`() {
    val server = start()
    create(server, cleanDocument())
    val anonymous =
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.server.port}/api/ui-builder/v1/designs/agent-screen/decisions"
            )
            .post(
              """{"revision":1,"verdict":"approve"}"""
                .toRequestBody("application/json".toMediaType())
            )
            .build()
        )
        .execute()
        .use { it.code }
    assertEquals(401, anonymous)
    assertEquals(
      404,
      http(
          server,
          "POST",
          "/api/ui-builder/v1/designs/nobody/decisions",
          """{"revision":1,"verdict":"approve"}""",
        )
        .first,
    )
    assertEquals(
      422,
      http(
          server,
          "POST",
          "/api/ui-builder/v1/designs/agent-screen/decisions",
          """{"revision":1,"verdict":"meh"}""",
        )
        .first,
    )
    assertError(server, ServeUiBuilderMcp.AWAIT_DECISION, """{"designId":"nobody"}""", "no design")
  }

  // ---- implementation ---------------------------------------------------------------------------

  @Test
  fun `the implementation PR is recorded, reported with the export, and found from the PR`() {
    val server = start()
    create(server, cleanDocument())
    val revision = revisionOf(server)
    val pr = "https://github.com/example/app/pull/42"

    val set =
      reply(
        server,
        ServeUiBuilderMcp.SET_IMPLEMENTATION,
        """{"designId":"agent-screen","pr":"$pr","status":"open","revision":$revision,"previewMatch":{"status":"match","evidence":"https://github.com/example/app/pull/42#issuecomment-1"}}""",
      )
    val sequence = set["sequence"]!!.jsonPrimitive.long
    // The same record again changes nothing and wakes nobody.
    val same =
      reply(
        server,
        ServeUiBuilderMcp.SET_IMPLEMENTATION,
        """{"designId":"agent-screen","pr":"$pr","status":"open","revision":$revision,"previewMatch":{"status":"match","evidence":"https://github.com/example/app/pull/42#issuecomment-1"}}""",
      )
    assertEquals(sequence, same["sequence"]!!.jsonPrimitive.long)
    http(
      server,
      "POST",
      "/api/ui-builder/v1/designs/agent-screen/decisions",
      """{"revision":$revision,"verdict":"approve"}""",
    )

    val status =
      typed(server, ServeUiBuilderMcp.IMPLEMENTATION_STATUS, """{"designId":"agent-screen"}""")
    assertEquals(pr, status["implementation"]!!.jsonObject.text("pr"))
    assertEquals(true, status["implementsRevision"]!!.jsonPrimitive.booleanOrNull)
    assertEquals("approve", status["latestDecision"]!!.jsonObject.text("verdict"))
    val export = status["export"]!!.jsonObject
    assertEquals(true, export["ok"]!!.jsonPrimitive.booleanOrNull, export.toString())
    assertTrue(export.text("source").contains("Opening keynote"), export.toString())
    val summary = status.text("summary")
    assertTrue(summary.contains("approved") && summary.contains("previews match"), summary)

    val lean =
      typed(
        server,
        ServeUiBuilderMcp.IMPLEMENTATION_STATUS,
        """{"designId":"agent-screen","includeExport":false}""",
      )
    assertNull(lean["export"])

    val found = typed(server, ServeUiBuilderMcp.FIND_DESIGN_FOR_PR, """{"pr":"$pr"}""")
    assertEquals("agent-screen", found["designs"]!!.jsonArray.single().jsonObject.text("designId"))
    assertEquals("open", found["designs"]!!.jsonArray.single().jsonObject.text("status"))
    val none =
      typed(
        server,
        ServeUiBuilderMcp.FIND_DESIGN_FOR_PR,
        """{"pr":"https://github.com/x/y/pull/1"}""",
      )
    assertTrue(none["designs"]!!.jsonArray.isEmpty())
    val lookup =
      http(
        server,
        "GET",
        "/api/ui-builder/v1/implementations?pr=${java.net.URLEncoder.encode(pr, "UTF-8")}",
        null,
      )
    assertTrue(lookup.second.contains("agent-screen"), lookup.second)

    // A newer revision makes the PR stale, and the summary says so.
    envelope(
      server,
      ServeUiBuilderMcp.APPLY,
      """{"designId":"agent-screen","operationId":"op-2","baseRevision":$revision,"operations":${operations(
        InsertNodeMutationV1(
          node = DesignNodeV1(id = "more", componentId = "m3/text", properties = mapOf("text" to StringValueV1("More"))),
          location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
        )
      )}}""",
    )
    val stale =
      typed(
        server,
        ServeUiBuilderMcp.IMPLEMENTATION_STATUS,
        """{"designId":"agent-screen","includeExport":false}""",
      )
    assertEquals(false, stale["implementsRevision"]!!.jsonPrimitive.booleanOrNull)
    assertTrue(stale.text("summary").contains("not this revision"), stale.toString())

    // Cleared by naming only the design.
    reply(server, ServeUiBuilderMcp.SET_IMPLEMENTATION, """{"designId":"agent-screen"}""")
    assertNull(
      typed(
        server,
        ServeUiBuilderMcp.IMPLEMENTATION_STATUS,
        """{"designId":"agent-screen","includeExport":false}""",
      )["implementation"]
    )
  }

  @Test
  fun `a bad implementation record is refused, and the export needs its grant`() {
    val server = start()
    create(server, cleanDocument())
    val tool = ServeUiBuilderMcp.SET_IMPLEMENTATION
    assertError(server, tool, """{"designId":"agent-screen","pr":"javascript:alert(1)"}""", "http")
    assertError(
      server,
      tool,
      """{"designId":"agent-screen","pr":"https://github.com/x/y/pull/1","status":"shipped"}""",
      "status",
    )
    assertError(
      server,
      tool,
      """{"designId":"agent-screen","pr":"https://github.com/x/y/pull/1","previewMatch":{"status":"close enough"}}""",
      "previewMatch",
    )
    assertError(server, tool, """{"designId":"agent-screen","status":"open"}""", "`pr` is required")

    val mcp =
      ServeUiBuilderMcp(
        service(stateDirectory.resolve("unit")),
        reviews = ServeUiBuilderReviewStore(stateDirectory.resolve("unit-reviews")),
      )
    assertEquals(
      UiBuilderRouteCapability.WRITE,
      mcp.capabilityFor(ServeUiBuilderMcp.SET_IMPLEMENTATION),
    )
    assertEquals(
      UiBuilderRouteCapability.WRITE,
      mcp.capabilityFor(ServeUiBuilderMcp.RECORD_DECISION),
    )
    assertEquals(UiBuilderRouteCapability.READ, mcp.capabilityFor(ServeUiBuilderMcp.AWAIT_DECISION))
    assertEquals(
      UiBuilderRouteCapability.EXPORT,
      mcp.additionalCapabilityFor(ServeUiBuilderMcp.IMPLEMENTATION_STATUS, JsonObject(emptyMap())),
    )
    assertNull(
      mcp.additionalCapabilityFor(
        ServeUiBuilderMcp.IMPLEMENTATION_STATUS,
        JsonObject(mapOf("includeExport" to JsonPrimitive(false))),
      )
    )
    // Without a review store the tools are absent, not refusing.
    val bare = ServeUiBuilderMcp(service(stateDirectory.resolve("unit2")))
    assertTrue(ServeUiBuilderMcp.REVIEW_TOOL_NAMES.all { bare.capabilityFor(it) == null })
  }

  @Test
  fun `the review tools are advertised only with a review store, and deleting the design forgets it`() {
    val server = start()
    assertTrue(
      ServeUiBuilderMcp.REVIEW_TOOL_NAMES.all { it in tools(server) },
      tools(server).toString(),
    )
    create(server, cleanDocument())
    decide(server, """{"designId":"agent-screen","revision":1,"verdict":"approve"}""")
    reply(
      server,
      ServeUiBuilderMcp.SET_IMPLEMENTATION,
      """{"designId":"agent-screen","pr":"https://github.com/x/y/pull/9"}""",
    )
    envelope(server, ServeUiBuilderMcp.DELETE_DESIGN, """{"designId":"agent-screen"}""")
    assertNull(server.reviews.read("agent-screen"))

    running?.close()
    running = null
    val bare = start(withReviews = false, directory = stateDirectory.resolve("bare"))
    // The reverse lookup stays: the links record alone can answer it.
    assertEquals(
      listOf(ServeUiBuilderMcp.FIND_DESIGN_FOR_PR),
      ServeUiBuilderMcp.REVIEW_TOOL_NAMES.filter { it in tools(bare) },
    )
  }

  // ---- fixtures and plumbing
  // ----------------------------------------------------------------------

  private fun cleanDocument(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "agent-screen",
      title = "Agent screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG_SYSTEM_ID, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("column"),
      nodes =
        mapOf(
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("session")),
            ),
          "session" to
            DesignNodeV1(
              id = "session",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            ),
        ),
    )

  /** One of each problem the accessibility pass knows, and two things it must leave alone. */
  private fun inaccessibleDocument(): DesignDocumentV1 {
    val base = cleanDocument()
    return base.copy(
      nodes =
        mapOf(
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("play", "labelled", "decorative", "panel", "box")),
            ),
          "play" to
            DesignNodeV1(
              id = "play",
              componentId = "m3/icon-button",
              slots = mapOf("content" to listOf("playIcon")),
            ),
          "playIcon" to
            DesignNodeV1(
              id = "playIcon",
              componentId = "m3/icon",
              properties = mapOf("iconKey" to StringValueV1("playCircle")),
            ),
          "labelled" to
            DesignNodeV1(
              id = "labelled",
              componentId = "m3/icon-button",
              properties = mapOf("contentDescription" to StringValueV1("Share")),
              modifiers = listOf(SizeModifierV1(JsonPrimitive(32), JsonPrimitive(32))),
              slots = mapOf("content" to listOf("shareIcon")),
            ),
          "shareIcon" to
            DesignNodeV1(
              id = "shareIcon",
              componentId = "m3/icon",
              properties =
                mapOf("iconKey" to StringValueV1("share"), "contentDescription" to NullValueV1),
            ),
          "decorative" to
            DesignNodeV1(
              id = "decorative",
              componentId = "m3/icon",
              properties =
                mapOf("iconKey" to StringValueV1("star"), "contentDescription" to NullValueV1),
            ),
          "panel" to
            DesignNodeV1(
              id = "panel",
              componentId = "layout/box",
              modifiers = listOf(BackgroundModifierV1(ColorValueV1("#888888"), null)),
              slots = mapOf("content" to listOf("faint")),
            ),
          "faint" to
            DesignNodeV1(
              id = "faint",
              componentId = "m3/text",
              properties =
                mapOf(
                  "text" to StringValueV1("Hard to read"),
                  "color" to ColorValueV1("#777777"),
                ),
            ),
          "box" to
            DesignNodeV1(
              id = "box",
              componentId = "layout/box",
              modifiers = listOf(HeightModifierV1(JsonPrimitive(20))),
              slots = mapOf("content" to listOf("clipped")),
            ),
          "clipped" to
            DesignNodeV1(
              id = "clipped",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Squeezed")),
            ),
        )
    )
  }

  private fun create(server: Running, document: DesignDocumentV1) {
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"${document.id}","document":${documentJson(document)}}""",
    )
  }

  private fun check(server: Running, arguments: String): JsonObject =
    typed(server, ServeUiBuilderMcp.CHECK_DESIGN, arguments)

  private fun matrix(server: Running, arguments: String): JsonObject =
    typed(server, ServeUiBuilderMcp.RENDER_DESIGN_MATRIX, arguments)

  private fun decide(server: Running, arguments: String): JsonObject =
    typed(server, ServeUiBuilderMcp.RECORD_DECISION, arguments)

  private fun awaitDecision(server: Running, arguments: String): JsonObject =
    typed(server, ServeUiBuilderMcp.AWAIT_DECISION, arguments)

  /** A reply that declares an output schema: the text, the typed copy, and the schema agree. */
  private fun typed(server: Running, tool: String, arguments: String): JsonObject {
    val reply = reply(server, tool, arguments)
    val errors = SchemaCheck(outputSchema(server, tool)).errors(reply)
    assertTrue(errors.isEmpty(), "$errors in $reply")
    return reply
  }

  private fun reply(server: Running, tool: String, arguments: String): JsonObject {
    val result = call(server, tool, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject.text("text")
    assertNull(result["isError"], text)
    val reply = Json.parseToJsonElement(text).jsonObject
    assertEquals(reply, result["structuredContent"], text)
    return reply
  }

  private fun assertError(server: Running, tool: String, arguments: String, contains: String) {
    val result = call(server, tool, arguments)
    assertEquals(true, result["isError"]?.jsonPrimitive?.booleanOrNull, result.toString())
    val text = result["content"]!!.jsonArray.first().jsonObject.text("text")
    assertTrue(text.contains(contains), "expected `$contains` in: $text")
  }

  private fun outputSchema(server: Running, tool: String): JsonObject =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject }
      .single { it.text("name") == tool }["outputSchema"]!!
      .jsonObject

  private fun revisionOf(server: Running): Long =
    Json.parseToJsonElement(
        envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")
      )
      .jsonObject["response"]!!
      .jsonObject["snapshot"]!!
      .jsonObject["state"]!!
      .jsonObject["document"]!!
      .jsonObject["revision"]!!
      .jsonPrimitive
      .longOrNull!!

  private fun operations(vararg mutations: DesignMutationV1): String =
    json.encodeToString(ListSerializer(DesignMutationV1.serializer()), mutations.toList())

  private fun documentJson(document: DesignDocumentV1): String =
    json.encodeToString(DesignDocumentV1.serializer(), document)

  private fun call(server: Running, tool: String, arguments: String = "{}") =
    post(
        server,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
      )["result"]!!
      .jsonObject

  private fun envelope(server: Running, tool: String, arguments: String = "{}"): String {
    val result = call(server, tool, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject.text("text")
    assertNull(result["isError"], text)
    return text
  }

  private fun tools(server: Running): List<String> =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject.text("name") }

  private fun post(server: Running, body: String): JsonObject =
    http(server, "POST", "/mcp", body).let { (code, text) ->
      assertEquals(200, code, text)
      Json.parseToJsonElement(text).jsonObject
    }

  private fun http(
    server: Running,
    method: String,
    path: String,
    body: String?,
  ): Pair<Int, String> =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}$path")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .method(method, body?.toRequestBody("application/json".toMediaType()))
          .build()
      )
      .execute()
      .use { it.code to it.body.string() }

  private fun JsonObject.text(name: String): String = this[name]!!.jsonPrimitive.content

  private fun JsonObject.strings(name: String): List<String> =
    this[name]!!.jsonArray.map { (it as JsonElement).jsonPrimitive.content }

  private fun catalogs() =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = setOf(CATALOG_SYSTEM_ID),
      exportCapabilities =
        ExportCapabilitiesV1.Builder()
          .also {
            it.composeCode = true
            it.svg = false
            it.png = true
          }
          .build(),
    )

  /**
   * The Compose generator for Kotlin, and a stand-in for the packaged PNG renderer that draws the
   * frame at a quarter of the environment's size, dark for a dark theme and light otherwise — so a
   * test can see which environment each matrix cell was drawn under.
   */
  private fun exporter(): UiBuilderExportExecutor {
    val compose =
      ScreenGeneratorComposeExportExecutor(
        ComponentRecordSource(
          mapOf(CATALOG_SYSTEM_ID to ScreenGeneratorScreenFixture.componentsFile())
        )::record
      )
    return UiBuilderExportExecutor { request ->
      if (request.format != ExportFormatV1.PNG) compose.export(request)
      else {
        val environment = request.document.environment
        val image =
          BufferedImage(
            environment.widthDp / 4,
            environment.heightDp / 4,
            BufferedImage.TYPE_INT_RGB,
          )
        val graphics = image.createGraphics()
        graphics.color =
          java.awt.Color(if (environment.theme == ThemeV1.DARK) DARK_FILL else LIGHT_FILL)
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.dispose()
        val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        ExportArtifactV1(
          ExportFormatV1.PNG,
          "image/png",
          ExportEncodingV1.BASE64,
          Base64.getEncoder().encodeToString(png),
          UiBuilderDesignMatrix.sha256(png),
          emptyList(),
        )
      }
    }
  }

  private fun service(directory: Path = stateDirectory) =
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(directory),
      catalogs = catalogs(),
      exporter = exporter(),
    )

  private fun start(
    withValidator: Boolean = true,
    withReviews: Boolean = true,
    directory: Path = stateDirectory,
  ): Running {
    val registry = ServeSessionRegistry(open = { null })
    val reviews = ServeUiBuilderReviewStore(directory.resolve("reviews"))
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          canonicalOrigin = PUBLIC_ORIGIN,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service(directory.resolve("state")),
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
          uiBuilderLinksStore = ServeUiBuilderLinksStore(directory.resolve("links")),
          uiBuilderReviewStore = if (withReviews) reviews else null,
          uiBuilderValidator =
            if (withValidator) ScratchUiBuilderDraftValidator(catalogs(), exporter()) else null,
        )
        .also(ServeHttpServer::start)
    return Running(server, registry, reviews).also { running = it }
  }

  private data class Running(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
    val reviews: ServeUiBuilderReviewStore,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-ergonomics-operator-token"
    const val PUBLIC_ORIGIN = "https://designs.example"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val LIGHT_FILL = 0xF0F0F0
    const val DARK_FILL = 0x202020
  }
}
