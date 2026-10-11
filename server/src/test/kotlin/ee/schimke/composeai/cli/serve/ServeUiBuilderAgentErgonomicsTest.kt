package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
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
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * Agent ergonomics against the real server, wired as `ServeRunner` wires it: check a design before
 * proposing it, see it on several devices in one picture, wait for a person's verdict, and join a
 * design to its implementing pull request.
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

  @Test
  fun `authorized MCP activity appears on its design and presence requires read access`() {
    val server = start()
    create(server, cleanDocument())
    envelope(
      server,
      ServeUiBuilderMcp.GET_DESIGN,
      """{"designId":"agent-screen","agentName":"Codex","agentModel":"reported-model"}""",
    )
    val (status, body) = http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/agents", null)
    assertEquals(200, status, body)
    val agent = Json.parseToJsonElement(body).jsonObject["agents"]!!.jsonArray.single().jsonObject
    assertEquals("Codex", agent.text("name"))
    assertEquals("reported-model", agent.text("model"))
    assertTrue(!body.contains(OPERATOR_TOKEN))
    val refused =
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.server.port}/api/ui-builder/v1/designs/agent-screen/agents"
            )
            .build()
        )
        .execute()
        .use { it.code }
    assertEquals(404, refused)
    val missing = http(server, "GET", "/api/ui-builder/v1/designs/unknown/agents", null)
    assertEquals(404, missing.first)
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
  fun `the guidelines check runs only when named, and says why when it cannot run`() {
    val bare = start()
    create(bare, cleanDocument())
    val unconfigured = check(bare, """{"designId":"agent-screen","checks":["guidelines"]}""")
    assertEquals(listOf("guidelines"), unconfigured.strings("checks"))
    val skipped = unconfigured["skipped"]!!.jsonArray.single().jsonObject
    assertEquals("guidelines", skipped.text("check"))
    assertTrue("no guidelines model" in skipped.text("reason"), skipped.toString())
    bare.close()

    // Configured, and the operator may spend the key: an m3 design is judged against the mobile
    // (adaptive) rules, and the result becomes the design's recorded one, run by the caller.
    var asked = 0
    val config = ServeUiBuilderGuidelinesConfig(apiKey = "sk-or-test", allowedUsers = setOf("a"))
    val lane =
      ServeUiBuilderGuidelines(
        config,
        ServeUiBuilderGuidelineAccess(config.allowedUsers, emptySet(), { _, _ -> false }),
        transport = { body, _ ->
          asked++
          assertTrue("mobile.touch-target-48dp" in body, body.take(500))
          OpenRouterTransport.Response(200, completion("mobile.touch-target-48dp", "fail"))
        },
      )
    val server = start(directory = stateDirectory.resolve("guided"), guidelines = lane)
    create(server, cleanDocument())
    val defaults = check(server, """{"designId":"agent-screen"}""")
    assertEquals(listOf("schema", "catalog", "a11y"), defaults.strings("checks"))
    assertEquals(0, asked)
    val named = check(server, """{"designId":"agent-screen","checks":["a11y","guidelines"]}""")
    assertEquals(1, asked)
    assertTrue(
      named["findings"]!!.jsonArray.any {
        it.jsonObject.text("code") == "mobile.touch-target-48dp"
      },
      named.toString(),
    )
    val recorded = named["guidelines"]!!.jsonObject
    assertEquals(OPERATOR_ACTOR, recorded.text("ranBy"))
    assertEquals(config.model, recorded.text("model"))
    assertEquals(OPERATOR_ACTOR, server.guidelineRecords.read("agent-screen")!!.ranBy)
    // A dry run is checked and not recorded.
    server.guidelineRecords.delete("agent-screen")
    check(server, """{"designId":"agent-screen","checks":["guidelines"],"operations":[]}""")
    assertNull(server.guidelineRecords.read("agent-screen"))
  }

  // ---- a catalog's own guidelines, the served model, the evidence triage ------------------------

  /** `m3-catalog`'s own guidelines: one rule of its own and two sized frames. */
  private val catalogGuidelinesJson =
    """
    {
      "schema": "compose-ui-builder/catalog-guidelines/v1",
      "catalog": "$CATALOG_SYSTEM_ID", "platform": "mobile", "version": 7,
      "frames": [
        {"kind": "sized", "label": "2x1", "widthDp": 130, "heightDp": 102},
        {"kind": "sized", "label": "4x2", "widthDp": 276, "heightDp": 220}
      ],
      "rules": [
        {"id": "catalog.own-rule", "kind": "structure", "severity": "warning",
         "guidance": "Own guidance.", "check": "Does it follow the catalog's own rule?",
         "source": "https://developer.android.com/own"}
      ]
    }
    """
      .trimIndent()

  @Test
  fun `a catalog's own guidelines decide the rules and the pictures, and are served`() {
    val drawn = mutableListOf<Pair<Int, Int>>()
    val own = ServeCatalogGuidelines(log = {})
    assertTrue(own.accept(CATALOG_SYSTEM_ID, catalogGuidelinesJson.toByteArray(), "test:file"))
    val server =
      start(
        nativePreview =
          UiBuilderNativePreviewLane { document, _ ->
            drawn += document.environment.widthDp to document.environment.heightDp
            nativeFrame(document.environment.widthDp / 2, document.environment.heightDp / 2)
          },
        catalogGuidelines = own,
      )
    create(server, cleanDocument())

    val prompt =
      reply(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")
    assertEquals("mobile", prompt.text("platform"))
    val rules = prompt["rules"]!!.jsonObject
    assertEquals(7, rules["version"]!!.jsonPrimitive.int)
    assertEquals(
      listOf("catalog.own-rule"),
      rules["asked"]!!.jsonArray.map { it.jsonObject.text("id") },
    )
    assertEquals(ServeCatalogGuidelines.routeFor(CATALOG_SYSTEM_ID), rules.text("source"))
    // The catalog's frames, not the built-in phone and tablet.
    assertEquals(listOf(130 to 102, 276 to 220), drawn)
    assertEquals(
      listOf("2x1", "4x2"),
      prompt["pictures"]!!.jsonArray.map { it.jsonObject.text("kind") },
    )

    val (status, body) =
      http(server, "GET", "/api/ui-builder/v1/catalogs/$CATALOG_SYSTEM_ID/guidelines", null)
    assertEquals(200, status, body)
    assertEquals(catalogGuidelinesJson, body)
    val (missing, _) = http(server, "GET", "/api/ui-builder/v1/catalogs/remote-m3/guidelines", null)
    assertEquals(404, missing)
  }

  @Test
  fun `a check records the model that answered, and a catalog rule's finding reads`() {
    val own = ServeCatalogGuidelines(log = {})
    own.accept(CATALOG_SYSTEM_ID, catalogGuidelinesJson.toByteArray(), "test:file")
    val config = ServeUiBuilderGuidelinesConfig(apiKey = "sk-or-test", allowedUsers = setOf("a"))
    val lane =
      ServeUiBuilderGuidelines(
        config,
        ServeUiBuilderGuidelineAccess(config.allowedUsers, emptySet(), { _, _ -> false }),
        transport = { _, _ ->
          OpenRouterTransport.Response(200, routedCompletion("catalog.own-rule", "fail"))
        },
        decisions = { _, _ -> throw java.io.IOException("no triage in this test") },
      )
    val server =
      start(
        directory = stateDirectory.resolve("served"),
        guidelines = lane,
        catalogGuidelines = own,
      )
    create(server, cleanDocument())

    val checked = check(server, """{"designId":"agent-screen","checks":["guidelines"]}""")
    val finding =
      checked["findings"]!!
        .jsonArray
        .map { it.jsonObject }
        .single { it.text("code") == "catalog.own-rule" }
    assertTrue(
      "Checked by deepseek/deepseek-v4.1-flash on CoreWeave" in finding.text("message"),
      finding.toString(),
    )
    val record = server.guidelineRecords.read("agent-screen")!!
    assertEquals(config.model, record.model)
    assertEquals("deepseek/deepseek-v4.1-flash", record.servedModel)
    assertEquals("CoreWeave", record.provider)
    assertEquals(0.0034, record.costUsd!!, 1e-9)
    assertEquals("gen-test", record.generationId)
    assertEquals("initial", record.routing!!.reason)
    assertEquals(0.9, record.routing!!.probability!!, 1e-9)
    assertEquals(listOf("catalog.own-rule"), record.asked)
  }

  @Test
  fun `the evidence triage adds a dark render when Jev wants one, and nothing when it fails`() {
    val themes = mutableListOf<ThemeV1>()
    fun laneAnswering(decisions: OpenRouterTransport): ServeUiBuilderGuidelines {
      val config = ServeUiBuilderGuidelinesConfig(apiKey = "sk-or-test", allowedUsers = setOf("a"))
      return ServeUiBuilderGuidelines(
        config,
        ServeUiBuilderGuidelineAccess(config.allowedUsers, emptySet(), { _, _ -> false }),
        transport = { _, _ ->
          OpenRouterTransport.Response(200, completion("mobile.touch-target-48dp", "pass"))
        },
        decisions = decisions,
      )
    }
    val native = UiBuilderNativePreviewLane { document, _ ->
      themes += document.environment.theme
      nativeFrame(document.environment.widthDp / 8, document.environment.heightDp / 8)
    }

    val wanting =
      start(
        directory = stateDirectory.resolve("triage-yes"),
        guidelines =
          laneAnswering { body, _ ->
            assertTrue("typesafe/jev-1.13" in body, body.take(300))
            OpenRouterTransport.Response(
              200,
              """{"answers":{"dark_theme":{"type":"noul","noul":0.9},""" +
                """"large_font":{"type":"noul","noul":0.1},""" +
                """"a11y_hierarchy":{"type":"noul","noul":0.2}}}""",
            )
          },
        nativePreview = native,
      )
    create(wanting, cleanDocument())
    check(wanting, """{"designId":"agent-screen","checks":["guidelines"],"rendered":true}""")
    assertTrue(ThemeV1.DARK in themes, themes.toString())
    wanting.close()

    themes.clear()
    val failing =
      start(
        directory = stateDirectory.resolve("triage-no"),
        guidelines = laneAnswering { _, _ -> throw java.io.IOException("decisions are down") },
        nativePreview = native,
      )
    create(failing, cleanDocument())
    val checked =
      check(failing, """{"designId":"agent-screen","checks":["guidelines"],"rendered":true}""")
    assertTrue(themes.isNotEmpty())
    assertTrue(ThemeV1.DARK !in themes, themes.toString())
    assertNull(checked["skipped"]?.jsonArray?.firstOrNull { "triage" in it.toString() })
  }

  /** A routed completion: the answer plus OpenRouter's served model, provider, cost and routing. */
  private fun routedCompletion(ruleId: String, verdict: String): String {
    val base = Json.parseToJsonElement(completion(ruleId, verdict)).jsonObject
    return JsonObject(
        base +
          mapOf(
            "id" to JsonPrimitive("gen-test"),
            "model" to JsonPrimitive("deepseek/deepseek-v4.1-flash"),
            "provider" to JsonPrimitive("CoreWeave"),
            "usage" to buildJsonObject { put("cost", 0.0034) },
            "openrouter_metadata" to
              Json.parseToJsonElement(
                """{"pipeline":[{"name":"jev-router","data":{"version":"1","reason":"initial",""" +
                  """"selection_probabilities":[{"model":"deepseek/deepseek-v4.1-flash-20260910",""" +
                  """"probability":0.9}],"answers":{"big_model_gain":{"type":"noul","noul":0.26}}}}]}"""
              ),
          )
      )
      .toString()
  }

  // ---- guidelines prompt and shared result -----------------------------------------------------

  @Test
  fun `the guidelines prompt is the request, with each planned frame as a picture, and no key`() {
    val drawn = mutableListOf<Pair<Int, Int>>()
    val server =
      start(
        nativePreview =
          UiBuilderNativePreviewLane { document, _ ->
            drawn += document.environment.widthDp to document.environment.heightDp
            nativeFrame(document.environment.widthDp / 8, document.environment.heightDp / 8)
          }
      )
    create(server, cleanDocument())

    val result =
      call(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")

    assertNull(result["isError"], result.toString())
    val blocks = result["content"]!!.jsonArray.map { it.jsonObject }
    val prompt = Json.parseToJsonElement(blocks.first().text("text")).jsonObject
    assertEquals(prompt, result["structuredContent"])
    assertTrue(
      SchemaCheck(outputSchema(server, ServeUiBuilderMcp.GUIDELINES_PROMPT))
        .errors(prompt)
        .isEmpty()
    )
    assertEquals("compose-ui-builder/guidelines-prompt/v1", prompt.text("schema"))
    assertEquals("mobile", prompt.text("platform"))
    // The phone and the tablet, drawn natively in that order, attached as image blocks with their
    // bytes kept out of the text.
    assertEquals(listOf(412 to 915, 1280 to 800), drawn)
    val pictures = prompt["pictures"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("phone", "tablet"), pictures.map { it.text("kind") })
    assertTrue(pictures.none { "dataUrl" in it }, pictures.toString())
    assertEquals(listOf("image", "image"), blocks.drop(1).map { it.text("type") })
    assertTrue("Picture 2 (tablet picture)" in prompt.text("userText"))
    // The source goes along, exported as `GET …/export.compose` exports the stored design.
    assertEquals(true, prompt["sourceAttached"]!!.jsonPrimitive.booleanOrNull, prompt.toString())
    assertTrue("```kotlin" in prompt.text("userText"))
    val (_, routeSource) =
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/export.compose", null)
    assertTrue(routeSource.trim().lines().first() in prompt.text("userText"), routeSource)
    assertTrue(prompt["provenance"]!!.jsonArray.isNotEmpty())

    // Without renders: a read alone, no pictures, no source, and the visual rules left out.
    drawn.clear()
    val bare =
      reply(
        server,
        ServeUiBuilderMcp.GUIDELINES_PROMPT,
        """{"designId":"agent-screen","rendered":false}""",
      )
    assertTrue(bare["pictures"]!!.jsonArray.isEmpty())
    assertEquals(false, bare["sourceAttached"]!!.jsonPrimitive.booleanOrNull)
    assertTrue(drawn.isEmpty())
    assertTrue(bare["rules"]!!.jsonObject["visualSkipped"]!!.jsonPrimitive.int > 0)
  }

  @Test
  fun `a second ask of the same revision takes its pictures from the cache, at each frame's size`() {
    val drawn = mutableListOf<Pair<Int, Int>>()
    val thumbnails = ServeUiBuilderThumbnails(stateDirectory.resolve("thumbs"), "test-generation")
    val server =
      start(
        thumbnails = thumbnails,
        nativePreview =
          UiBuilderNativePreviewLane { document, _ ->
            synchronized(drawn) {
              drawn += document.environment.widthDp to document.environment.heightDp
            }
            nativeFrame(document.environment.widthDp / 8, document.environment.heightDp / 8)
          },
      )
    create(server, cleanDocument())

    val first =
      reply(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")
    // The lane is given each frame's own size: a phone and a tablet are two renders.
    assertEquals(listOf(412 to 915, 1280 to 800), drawn)
    assertEquals(listOf("phone", "tablet"), first.pictureKinds())

    val second =
      reply(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")
    assertEquals(listOf("phone", "tablet"), second.pictureKinds())
    assertEquals(2, drawn.size, "nothing is drawn twice: $drawn")
    thumbnails.close()
  }

  @Test
  fun `a frame still drawing when the budget runs out is left out, said, and kept for next time`() {
    val drawn = java.util.concurrent.atomic.AtomicInteger()
    val finished = java.util.concurrent.atomic.AtomicInteger()
    val thumbnails = ServeUiBuilderThumbnails(stateDirectory.resolve("thumbs"), "test-generation")
    val server =
      start(
        thumbnails = thumbnails,
        pictureBudgetSeconds = 1,
        nativePreview =
          UiBuilderNativePreviewLane { document, _ ->
            drawn.incrementAndGet()
            if (document.environment.widthDp == 1280) Thread.sleep(2_500)
            nativeFrame(document.environment.widthDp / 8, document.environment.heightDp / 8).also {
              finished.incrementAndGet()
            }
          },
      )
    create(server, cleanDocument())

    val started = System.nanoTime()
    val result =
      call(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")
    val tookMillis = (System.nanoTime() - started) / 1_000_000
    val blocks = result["content"]!!.jsonArray.map { it.jsonObject }
    val prompt = result["structuredContent"]!!.jsonObject
    assertEquals(listOf("phone"), prompt.pictureKinds())
    assertTrue(
      tookMillis < 2_400,
      "answered within the budget, not after the slow frame: $tookMillis",
    )
    val note = "The tablet picture (1280×800dp) is still being drawn"
    assertTrue(
      prompt["provenance"]!!.jsonArray.any { note in it.jsonPrimitive.content },
      prompt.toString(),
    )
    // Said in a text block of its own, after the request's JSON, so an agent asks again rather
    // than judging without it.
    assertTrue(note in blocks[1].text("text"), blocks.toString())

    // The slow frame finishes into the cache; the next ask attaches it without drawing again.
    val deadline = System.currentTimeMillis() + 10_000
    while (finished.get() < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
    Thread.sleep(200)
    val again =
      reply(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")
    assertEquals(listOf("phone", "tablet"), again.pictureKinds())
    assertEquals(2, drawn.get())
    thumbnails.close()
  }

  @Test
  fun `a picture drawn at some other size than its frame is left out, not mislabelled`() {
    // The desktop lane once drew every m3 design at its 400×800dp sandbox, so the phone and the
    // tablet picture came back byte-identical.
    val server =
      start(nativePreview = UiBuilderNativePreviewLane { _, _ -> nativeFrame(800, 1600) })
    create(server, cleanDocument())

    val prompt =
      reply(server, ServeUiBuilderMcp.GUIDELINES_PROMPT, """{"designId":"agent-screen"}""")
    assertEquals(emptyList(), prompt.pictureKinds())
    val provenance = prompt["provenance"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertTrue(
      provenance.any { "The phone picture (412×915dp) is left out" in it },
      provenance.toString(),
    )
    assertTrue(provenance.any { "The tablet picture (1280×800dp) is left out" in it })
  }

  @Test
  fun `an agent records its own verdicts, which read back with who ran them and when stale`() {
    val server = start()
    create(server, cleanDocument())
    val empty = typed(server, ServeUiBuilderMcp.GET_GUIDELINES, """{"designId":"agent-screen"}""")
    assertNull(empty["record"], empty.toString())
    val revision = revisionOf(server)

    val recorded =
      typed(
        server,
        ServeUiBuilderMcp.RECORD_GUIDELINES,
        """{"designId":"agent-screen","revision":$revision,"model":"anthropic/claude-haiku-5.5",
          "asked":["mobile.touch-target-48dp","mobile.layout.no-stretched-content"],
          "verdicts":[{"ruleId":"mobile.touch-target-48dp","verdict":"fail","confidence":0.9,
            "nodeIds":["session"],"reason":"Too small."}]}""",
      )
    val record = recorded["record"]!!.jsonObject
    assertEquals(OPERATOR_ACTOR, record.text("ranBy"))
    assertEquals(false, recorded["stale"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(listOf("mobile.layout.no-stretched-content"), recorded.strings("unanswered"))
    assertEquals("session", recorded["findings"]!!.jsonArray.single().jsonObject.text("nodeId"))

    // A rule this host never wrote, or a verdict on a rule not asked, is refused.
    assertError(
      server,
      ServeUiBuilderMcp.RECORD_GUIDELINES,
      """{"designId":"agent-screen","revision":$revision,"model":"m","asked":["made.up"],"verdicts":[]}""",
      "does not know",
    )

    // The design moves on; the record says so.
    envelope(
      server,
      ServeUiBuilderMcp.APPLY,
      """{"designId":"agent-screen","operationId":"move-1","baseRevision":$revision,"operations":${operations(
        SetPropertyMutationV1("session", "text", StringValueV1("Moved"))
      )}}""",
    )
    val stale = typed(server, ServeUiBuilderMcp.GET_GUIDELINES, """{"designId":"agent-screen"}""")
    assertEquals(true, stale["stale"]!!.jsonPrimitive.booleanOrNull, stale.toString())

    // Deleting the design forgets its record.
    envelope(server, ServeUiBuilderMcp.DELETE_DESIGN, """{"designId":"agent-screen"}""")
    assertNull(server.guidelineRecords.read("agent-screen"))
  }

  @Test
  fun `the editor's guidelines routes serve the prompt and the shared result, authorised`() {
    val server = start()
    create(server, cleanDocument())
    val revision = revisionOf(server)
    val (promptStatus, promptBody) =
      http(
        server,
        "GET",
        "/api/ui-builder/v1/designs/agent-screen/guidelines/prompt?rendered=false",
        null,
      )
    assertEquals(200, promptStatus, promptBody)
    assertEquals("mobile", Json.parseToJsonElement(promptBody).jsonObject.text("platform"))
    assertEquals(
      404,
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/guidelines", null).first,
    )

    val body =
      """{"revision":$revision,"model":"openai/gpt-x","rulesVersion":1,
        "asked":["mobile.touch-target-48dp"],"ranBy":"github:forged",
        "verdicts":[{"ruleId":"mobile.touch-target-48dp","verdict":"pass","confidence":0.8}]}"""
    val (posted, stored) =
      http(server, "POST", "/api/ui-builder/v1/designs/agent-screen/guidelines", body)
    assertEquals(200, posted, stored)
    assertEquals(OPERATOR_ACTOR, Json.parseToJsonElement(stored).jsonObject.text("ranBy"))
    val (read, readBody) =
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/guidelines", null)
    assertEquals(200, read, readBody)
    assertEquals("openai/gpt-x", Json.parseToJsonElement(readBody).jsonObject.text("model"))

    assertEquals(
      422,
      http(
          server,
          "POST",
          "/api/ui-builder/v1/designs/agent-screen/guidelines",
          body.replace("\"pass\"", "\"maybe\""),
        )
        .first,
    )
    assertEquals(
      404,
      http(server, "GET", "/api/ui-builder/v1/designs/nobody/guidelines/prompt", null).first,
    )
    val anonymous =
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.server.port}/api/ui-builder/v1/designs/agent-screen/guidelines"
            )
            .build()
        )
        .execute()
        .use { it.code }
    assertEquals(401, anonymous)
  }

  @Test
  fun `the editor can run the check on this host's key, once per revision, for allowed accounts`() {
    // No lane: the editor is told to use its own key, and a check is refused with the reason.
    val bare = start()
    create(bare, cleanDocument())
    val (bareAccess, bareAccessBody) =
      http(bare, "GET", "/api/ui-builder/v1/designs/agent-screen/guidelines/access", null)
    assertEquals(200, bareAccess, bareAccessBody)
    val bareAnswer = Json.parseToJsonElement(bareAccessBody).jsonObject
    assertEquals("false", bareAnswer["serverCheck"].toString())
    assertTrue("no guidelines model" in bareAnswer.text("reason"), bareAccessBody)
    val (refused, refusedBody) =
      http(bare, "POST", "/api/ui-builder/v1/designs/agent-screen/guidelines/check", "")
    assertEquals(403, refused, refusedBody)
    bare.close()

    // A lane the caller may spend: one model call however many ask at once, recorded as theirs.
    val asked = java.util.concurrent.atomic.AtomicInteger()
    val config = ServeUiBuilderGuidelinesConfig(apiKey = "sk-or-test", allowedUsers = setOf("a"))
    val lane =
      ServeUiBuilderGuidelines(
        config,
        ServeUiBuilderGuidelineAccess(config.allowedUsers, emptySet(), { _, _ -> false }),
        transport = { _, _ ->
          asked.incrementAndGet()
          Thread.sleep(400)
          OpenRouterTransport.Response(200, completion("mobile.touch-target-48dp", "fail"))
        },
      )
    val server = start(directory = stateDirectory.resolve("server-check"), guidelines = lane)
    create(server, cleanDocument())
    val revision = revisionOf(server)
    val (access, accessBody) =
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/guidelines/access", null)
    assertEquals(200, access, accessBody)
    val answer = Json.parseToJsonElement(accessBody).jsonObject
    assertEquals("true", answer["serverCheck"].toString())
    assertEquals(config.model, answer.text("model"))
    assertFalse("sk-or" in accessBody)

    val path = "/api/ui-builder/v1/designs/agent-screen/guidelines/check?revision=$revision"
    val results =
      java.util.concurrent.Executors.newFixedThreadPool(2).let { pool ->
        try {
          listOf(
              pool.submit<Pair<Int, String>> { http(server, "POST", path, "") },
              pool.submit<Pair<Int, String>> { http(server, "POST", path, "") },
            )
            .map { it.get() }
        } finally {
          pool.shutdown()
        }
      }
    results.forEach { (status, body) -> assertEquals(200, status, body) }
    assertEquals(1, asked.get(), "two editors asking at once spend the key once")
    val record = Json.parseToJsonElement(results.first().second).jsonObject
    assertEquals(OPERATOR_ACTOR, record.text("ranBy"))
    assertEquals(OPERATOR_ACTOR, server.guidelineRecords.read("agent-screen")!!.ranBy)
    val (read, readBody) =
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/guidelines", null)
    assertEquals(200, read, readBody)
    assertEquals(
      revision.toString(),
      Json.parseToJsonElement(readBody).jsonObject["revision"].toString(),
    )

    val (stale, staleBody) =
      http(
        server,
        "POST",
        "/api/ui-builder/v1/designs/agent-screen/guidelines/check?revision=${revision - 1}",
        "",
      )
    assertEquals(409, stale, staleBody)
  }

  @Test
  fun `the prompt route stays without a result store, and the result routes do not`() {
    val server = start(withGuidelineRecords = false)
    create(server, cleanDocument())
    val (prompt, promptBody) =
      http(
        server,
        "GET",
        "/api/ui-builder/v1/designs/agent-screen/guidelines/prompt?rendered=false",
        null,
      )
    assertEquals(200, prompt, promptBody)
    val (read, readBody) =
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/guidelines", null)
    assertEquals(404, read, readBody)
  }

  @Test
  fun `the guidelines record tools exist only with a store, and the prompt tool always`() {
    val server = start(withGuidelineRecords = false)
    val names = tools(server)
    assertTrue(ServeUiBuilderMcp.GUIDELINES_PROMPT in names, names.toString())
    assertTrue(ServeUiBuilderMcp.GUIDELINE_RECORD_TOOL_NAMES.none { it in names }, names.toString())
    running?.close()
    running = null
    val kept = start(directory = stateDirectory.resolve("kept"))
    assertTrue(ServeUiBuilderMcp.GUIDELINE_RECORD_TOOL_NAMES.all { it in tools(kept) })
  }

  @Test
  fun `a widget is drawn in the Samsung and Pixel Watch containers, each at its own size`() {
    val widget =
      cleanDocument()
        .copy(
          catalogPin = CatalogReferenceV1("remote-m3", "candidate", "candidate", "candidate"),
          roots = listOf("widget"),
          nodes =
            mapOf(
              "widget" to
                DesignNodeV1(id = "widget", componentId = "remote-m3/widget-container-large")
            ),
        )
    val frames =
      ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFrames.plan(
        widget.toUiBuilderDocument(),
        "wear",
      )
    val shapes = mutableListOf<Triple<WearWidgetHostShape, Int, Int>>()
    val pictures =
      ServeUiBuilderMcp.drawGuidelineFrames(widget, frames, devicePng = null) { framed, shape ->
        shapes += Triple(shape, framed.environment.widthDp, framed.environment.heightDp)
        byteArrayOf(1)
      }
    assertEquals(
      listOf(
        Triple(WearWidgetHostShape.Round, 230, 168),
        Triple(WearWidgetHostShape.Squircle, 216, 124),
      ),
      shapes,
    )
    assertEquals(listOf("widget-samsung", "widget-pixel-watch"), pictures.map { it.kind })
    assertTrue(pictures[0].description.startsWith("Picture 1 (Samsung widget picture)"))

    // A frame the renderer cannot draw is left out, and the rest are renumbered.
    val one =
      ServeUiBuilderMcp.drawGuidelineFrames(widget, frames, devicePng = null) { _, shape ->
        if (shape == WearWidgetHostShape.Round) null else byteArrayOf(1)
      }
    assertTrue(one.single().description.startsWith("Picture 1 (Pixel Watch widget picture)"))
  }

  @Test
  fun `a design's Compose source is served as text for the editor's guidelines check`() {
    val server = start()
    create(server, cleanDocument())
    val (status, body) =
      http(server, "GET", "/api/ui-builder/v1/designs/agent-screen/export.compose", null)
    assertEquals(200, status, body)
    assertTrue("@Composable" in body, body)
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

  /** A native lane's answer: a plain PNG of the given size. */
  private fun JsonObject.pictureKinds(): List<String> =
    this["pictures"]!!.jsonArray.map { it.jsonObject.text("kind") }

  private fun nativeFrame(width: Int, height: Int): UiBuilderNativePreviewOutcome {
    val image = BufferedImage(maxOf(width, 1), maxOf(height, 1), BufferedImage.TYPE_INT_RGB)
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    return UiBuilderNativePreviewOutcome.Rendered(
      response =
        PlaygroundRunResponse(
          previewId = "generated",
          previewToken = "token",
          image = Base64.getEncoder().encodeToString(png),
        ),
      taggedNodeIds = emptyList(),
      nodeBounds = emptyMap(),
    )
  }

  /** An OpenRouter completion answering [ruleId] with [verdict]. */
  private fun completion(ruleId: String, verdict: String): String {
    val content =
      """{"verdicts":[{"ruleId":"$ruleId","verdict":"$verdict","confidence":0.9,""" +
        """"nodeIds":["session"],"reason":"Because."}]}"""
    return buildJsonObject {
      put(
        "choices",
        buildJsonArray {
          add(
            buildJsonObject {
              put(
                "message",
                buildJsonObject {
                  put("role", "assistant")
                  put("content", content)
                },
              )
            }
          )
        },
      )
    }
      .toString()
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
    guidelines: ServeUiBuilderGuidelines? = null,
    withGuidelineRecords: Boolean = true,
    nativePreview: UiBuilderNativePreviewLane? = null,
    thumbnails: ServeUiBuilderThumbnails? = null,
    pictureBudgetSeconds: Long = DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS,
    catalogGuidelines: ServeCatalogGuidelines? = null,
  ): Running {
    val registry = ServeSessionRegistry(open = { null })
    val reviews = ServeUiBuilderReviewStore(directory.resolve("reviews"))
    val guidelineRecords = ServeUiBuilderGuidelineStore(directory.resolve("guidelines"))
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
          uiBuilderGuidelines = guidelines,
          uiBuilderGuidelineStore = if (withGuidelineRecords) guidelineRecords else null,
          uiBuilderNativePreview = nativePreview,
          uiBuilderThumbnails = thumbnails,
          uiBuilderGuidelinesPictureBudgetSeconds = pictureBudgetSeconds,
          uiBuilderCatalogGuidelines = catalogGuidelines,
        )
        .also(ServeHttpServer::start)
    return Running(server, registry, reviews, guidelineRecords).also { running = it }
  }

  private data class Running(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
    val reviews: ServeUiBuilderReviewStore,
    val guidelineRecords: ServeUiBuilderGuidelineStore,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-ergonomics-operator-token"
    /** Who the operator token acts as, which is who a record says ran it. */
    val OPERATOR_ACTOR: String = ServeAgentGrants.OPERATOR_ACTOR_ID
    const val PUBLIC_ORIGIN = "https://designs.example"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    const val LIGHT_FILL = 0xF0F0F0
    const val DARK_FILL = 0x202020
  }
}
