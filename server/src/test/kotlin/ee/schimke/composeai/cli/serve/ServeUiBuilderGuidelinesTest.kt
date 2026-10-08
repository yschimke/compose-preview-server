package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFrame
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePicture
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePrompt
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRecord
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineVerdict
import ee.schimke.composeai.uibuilder.guidelines.body
import ee.schimke.composeai.uibuilder.guidelines.prepare
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

class ServeUiBuilderGuidelinesTest {
  @Test
  fun `the rules are compose-ui-builder's bundled set, and m3 is the mobile platform`() {
    val rules = DesignGuidelineRuleSet.Bundled
    assertTrue(rules.forPlatform("wear").isNotEmpty())
    assertTrue(rules.forPlatform("mobile").isNotEmpty())
    assertEquals(rules.rules.size, rules.rules.map { it.id }.toSet().size)
    assertEquals("wear", DesignGuidelinePrompt.platformOf("remote-m3"))
    assertEquals("mobile", DesignGuidelinePrompt.platformOf("m3"))
    assertNull(DesignGuidelinePrompt.platformOf("compose-foundation"))
  }

  @Test
  fun `a request asks the visual rules only with pictures, and describes each picture`() {
    // Which rules a design is asked is compose-ui-builder's to decide (by platform, surface and
    // kind), so this asserts the server asks what the library asks rather than restating the
    // selection: a builder change to it would otherwise turn this red on every builder commit.
    val bare = ServeUiBuilderGuidelines.prepare("d", 3, wearDocument(), emptyList(), null)
    assertEquals(
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "d",
        3,
        wearDocument(),
        emptyList(),
        null,
      ),
      bare,
    )
    val wear = DesignGuidelineRuleSet.Bundled.forPlatform("wear").map { it.id }.toSet()
    assertTrue(bare.rules.asked.isNotEmpty())
    assertTrue(bare.rules.asked.none { it.visual }, bare.rules.asked.toString())
    assertTrue(bare.rules.asked.all { it.id in wear }, bare.rules.asked.toString())
    assertTrue(bare.rules.visualSkipped > 0)
    assertTrue(bare.provenance.any { "No picture is attached" in it }, bare.provenance.toString())

    val frames =
      listOf(
        DesignGuidelineFrame(DesignGuidelinePicture.DEVICE, 192, 192, emptyMap()),
        DesignGuidelineFrame(DesignGuidelinePicture.UNROLLED, 192, 768, emptyMap()),
      )
    val pictures = frames.mapIndexed { i, frame ->
      DesignGuidelinePicture.of(frame, i + 1, "data:image/png;base64,AQ==")
    }
    val request =
      ServeUiBuilderGuidelines.prepare("d", 3, wearDocument(), pictures, "@Composable fun X() {}")
    // The server builds exactly the editor's request: same library call, same bytes.
    assertEquals(
      DesignGuidelinePrompt.prepare(
        DesignGuidelineRuleSet.Bundled,
        "d",
        3,
        wearDocument(),
        pictures,
        "@Composable fun X() {}",
      ),
      request,
    )
    // With pictures the visual rules are asked too, on top of the bare request's.
    assertTrue(request.rules.asked.containsAll(bare.rules.asked), request.rules.asked.toString())
    assertTrue(request.rules.asked.any { it.visual }, request.rules.asked.toString())
    assertEquals(0, request.rules.visualSkipped)
    assertTrue("Picture 2 (unrolled picture)" in request.userText, request.userText)
    assertTrue("@Composable fun X() {}" in request.userText)
    assertTrue(request.sourceAttached)
    val body = DesignGuidelinePrompt.body(request, "m")
    assertEquals(3, body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.size)
  }

  @Test
  fun `only confident failures become findings, on the nodes that exist, with their source`():
    Unit = runBlocking {
    var sent: String? = null
    val guidelines =
      guidelines(
        OpenRouterTransport { body, key ->
          assertEquals(KEY, key)
          sent = body
          OpenRouterTransport.Response(
            200,
            completion(
              verdict("wear.layout.responsive-width", "fail", 0.9, "two", "ghost"),
              verdict("wear.touch-target-48dp", "fail", 0.3, "two"),
              verdict("wear.layout.time-text-shown", "pass", 0.9),
            ),
          )
        }
      )
    val request = ServeUiBuilderGuidelines.prepare("d", 3, wearDocument(), emptyList(), null)

    val outcome = guidelines.check(request)

    assertIs<UiBuilderGuidelineOutcome.Checked>(outcome)
    assertEquals(3, outcome.verdicts.size)
    assertEquals(request.rules.asked.map { it.id }, outcome.asked)
    val finding =
      ServeUiBuilderGuidelines.findings(
          outcome.verdicts,
          outcome.asked,
          setOf("screen", "list", "one", "two"),
          outcome.model,
          guidelines.minConfidence,
        )
        .single()
    assertEquals(CHECK_GUIDELINES, finding.check)
    assertEquals("wear.layout.responsive-width", finding.code)
    assertEquals("two", finding.nodeId)
    assertEquals("warning", finding.severity)
    assertTrue("developer.android.com" in finding.message, finding.message)
    request.rules.asked.forEach { assertTrue("ruleId: ${it.id}" in sent!!, it.id) }
    DesignGuidelineRuleSet.Bundled.forPlatform("wear")
      .filter { it.visual }
      .forEach { assertFalse("ruleId: ${it.id}" in sent!!, it.id) }
  }

  @Test
  fun `rules the model skipped are unanswered, and no verdicts at all is a failure`(): Unit =
    runBlocking {
      val request = ServeUiBuilderGuidelines.prepare("d", 3, wearDocument(), emptyList(), null)
      val partial = guidelines { _, _ ->
        OpenRouterTransport.Response(
          200,
          completion(verdict("wear.layout.responsive-width", "pass", 0.9)),
        )
      }
        .check(request)
      assertIs<UiBuilderGuidelineOutcome.Checked>(partial)
      assertEquals(request.rules.asked.size - 1, partial.unanswered.size)

      val empty = guidelines { _, _ ->
        OpenRouterTransport.Response(200, completion())
      }
        .check(request)
      assertIs<UiBuilderGuidelineOutcome.Failed>(empty)
    }

  @Test
  fun `a refusal from OpenRouter or an unreadable answer is a failure, not a finding`(): Unit =
    runBlocking {
      val request = ServeUiBuilderGuidelines.prepare("d", 3, wearDocument(), emptyList(), null)
      val refused = guidelines { _, _ ->
        OpenRouterTransport.Response(402, """{"error":{"message":"Insufficient credits"}}""")
      }
        .check(request)
      assertIs<UiBuilderGuidelineOutcome.Failed>(refused)
      assertTrue("Insufficient credits" in refused.reason, refused.reason)

      val garbled = guidelines { _, _ ->
        OpenRouterTransport.Response(200, completionText("sorry"))
      }
        .check(request)
      assertIs<UiBuilderGuidelineOutcome.Failed>(garbled)
    }

  @Test
  fun `a catalog with no guidelines is skipped without calling the model`(): Unit = runBlocking {
    val request =
      ServeUiBuilderGuidelines.prepare(
        "d",
        3,
        wearDocument(systemId = "compose-foundation"),
        emptyList(),
        null,
      )
    val outcome = guidelines { _, _ -> error("must not be called") }.check(request)
    assertIs<UiBuilderGuidelineOutcome.Skipped>(outcome)
  }

  @Test
  fun `the store keeps one record per design, stamped by the host, and refuses unknown rules`() {
    val root = kotlin.io.path.createTempDirectory("guidelines")
    val store = ServeUiBuilderGuidelineStore(root)
    val ruleId = DesignGuidelineRuleSet.Bundled.forPlatform("wear").first().id
    val body =
      DesignGuidelineRecord(
        designId = "forged",
        revision = 4,
        model = "anthropic/claude-haiku-5.5",
        rulesVersion = DesignGuidelineRuleSet.Bundled.version,
        asked = listOf(ruleId),
        verdicts = listOf(DesignGuidelineVerdict(ruleId, "fail", 2.0, listOf("two"), "Why.")),
        ranBy = "github:somebody-else",
      )
    val stored = store.record("workout", ranBy = "agent:me", body)
    assertIs<ServeUiBuilderGuidelineStore.GuidelineWriteResult.Stored>(stored)
    val read = store.read("workout")!!
    assertEquals("workout", read.designId)
    assertEquals("agent:me", read.ranBy)
    assertEquals(1.0, read.verdicts.single().confidence)
    assertTrue((read.recordedAtEpochMillis ?: 0) > 0)

    assertIs<ServeUiBuilderGuidelineStore.GuidelineWriteResult.Refused>(
      store.record("workout", "agent:me", body.copy(asked = listOf("made.up")))
    )
    assertIs<ServeUiBuilderGuidelineStore.GuidelineWriteResult.Refused>(
      store.record(
        "workout",
        "agent:me",
        body.copy(verdicts = body.verdicts + DesignGuidelineVerdict("other", "pass")),
      )
    )
    assertIs<ServeUiBuilderGuidelineStore.GuidelineWriteResult.Refused>(
      store.record(
        "workout",
        "agent:me",
        body.copy(verdicts = listOf(DesignGuidelineVerdict(ruleId, "maybe"))),
      )
    )
    assertTrue(store.delete("workout"))
    assertNull(store.read("workout"))
  }

  @Test
  fun `access is the named users, members of the named orgs, the operator, and their agents`() {
    var lookups = 0
    var now = 0L
    val clock =
      object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
      }
    val access =
      ServeUiBuilderGuidelineAccess(
        allowedUsers = setOf("Alice"),
        allowedOrgs = setOf("google"),
        isOrgMember = { org, login ->
          lookups++
          org == "google" && login == "bob"
        },
        clock = clock,
        cacheMillis = 1_000,
      )
    assertTrue(access.allows(AuthenticatedUiBuilderActor("github:alice")))
    assertTrue(access.allows(AuthenticatedUiBuilderActor("github:Bob")))
    assertFalse(access.allows(AuthenticatedUiBuilderActor("github:carol")))
    assertFalse(access.allows(AuthenticatedUiBuilderActor("anonymous")))
    assertTrue(access.allows(AuthenticatedUiBuilderActor(ServeAgentGrants.OPERATOR_ACTOR_ID)))
    // An agent acting for an allowed person may run it; one acting for anyone else may not.
    assertTrue(access.allows(AuthenticatedUiBuilderActor("agent:x", "github:alice")))
    assertFalse(access.allows(AuthenticatedUiBuilderActor("agent:y", "github:carol")))

    // Membership answers are remembered for the cache window, then asked again.
    val before = lookups
    assertTrue(access.allows(AuthenticatedUiBuilderActor("github:bob")))
    assertEquals(before, lookups)
    now = 2_000
    assertTrue(access.allows(AuthenticatedUiBuilderActor("github:bob")))
    assertEquals(before + 1, lookups)
  }

  @Test
  fun `a key with nobody allowed to spend it is refused`() {
    assertFailsWith<IllegalArgumentException> { ServeUiBuilderGuidelinesConfig(apiKey = KEY) }
  }

  private fun guidelines(transport: OpenRouterTransport): ServeUiBuilderGuidelines {
    val config = ServeUiBuilderGuidelinesConfig(apiKey = KEY, allowedUsers = setOf("alice"))
    return ServeUiBuilderGuidelines(
      config,
      ServeUiBuilderGuidelineAccess(config.allowedUsers, config.allowedOrgs, { _, _ -> false }),
      transport = transport,
    )
  }

  private fun verdict(
    ruleId: String,
    verdict: String,
    confidence: Double,
    vararg nodeIds: String,
  ): JsonObject = buildJsonObject {
    put("ruleId", ruleId)
    put("verdict", verdict)
    put("confidence", confidence)
    put("nodeIds", buildJsonArray { nodeIds.forEach { add(JsonPrimitive(it)) } })
    put("reason", "Because.")
  }

  private fun completion(vararg verdicts: JsonObject): String =
    completionText(
      buildJsonObject { put("verdicts", buildJsonArray { verdicts.forEach { add(it) } }) }
        .toString()
    )

  private fun completionText(content: String): String = buildJsonObject {
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

  private fun wearDocument(systemId: String = "wear-m3"): JsonObject =
    Json.parseToJsonElement(
        """
        {
          "title": "Workout",
          "catalogPin": {"systemId": "$systemId"},
          "environment": {"widthDp": 192, "heightDp": 192, "theme": "dark"},
          "roots": ["screen"],
          "assets": {"logo": {"source": {"type": "embedded", "base64": "AAAA"}}},
          "nodes": {
            "screen": {"id": "screen", "componentId": "wear-m3/screen-scaffold",
              "properties": {"timeText": {"type": "string", "value": "10:10"}},
              "slots": {"content": ["list"], "edgeButton": []}},
            "list": {"id": "list", "componentId": "wear-m3/transforming-lazy-column",
              "slots": {"items": ["one", "two"]}},
            "one": {"id": "one", "componentId": "wear-m3/button",
              "properties": {"label": {"type": "string", "value": "Start"}},
              "modifiers": [{"type": "fillMaxWidth"}],
              "eventBindings": {"onClick": {"type": "navigate"}}},
            "two": {"id": "two", "componentId": "wear-m3/button",
              "properties": {"label": {"type": "string", "value": "Stop"}},
              "modifiers": [{"type": "width", "value": 80}]}
          }
        }
        """
      )
      .jsonObject

  private companion object {
    const val KEY = "sk-or-test"
  }
}
