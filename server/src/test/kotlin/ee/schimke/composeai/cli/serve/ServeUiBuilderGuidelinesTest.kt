package ee.schimke.composeai.cli.serve

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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ServeUiBuilderGuidelinesTest {
  @Test
  fun `the bundled rules parse, and every rule is complete and uniquely named`() {
    val rules = UiBuilderGuidelineRuleSet.bundled()
    assertEquals("compose-ui-builder/design-guidelines/v1", rules.schema)
    assertTrue(rules.rules.size >= 10)
    assertEquals(rules.rules.size, rules.rules.map { it.id }.toSet().size)
    rules.rules.forEach { rule ->
      assertTrue(rule.platforms.isNotEmpty(), rule.id)
      assertTrue(rule.kind in setOf("structure", "visual"), rule.id)
      assertTrue(rule.severity in setOf("warning", "info"), rule.id)
      assertTrue(
        rule.guidance.isNotBlank() && '?' in rule.check,
        rule.id,
      )
      assertTrue(
        rule.source.startsWith("https://developer.android.com/") || rule.source.startsWith("kb://"),
        rule.id,
      )
    }
  }

  @Test
  fun `a catalog's platform is read from its system id`() {
    assertEquals("wear", UiBuilderGuidelinePrompt.platformOf("wear-m3"))
    assertEquals("wear", UiBuilderGuidelinePrompt.platformOf("remote-m3"))
    assertEquals("glasses", UiBuilderGuidelinePrompt.platformOf("glimmer"))
    assertNull(UiBuilderGuidelinePrompt.platformOf("m3-catalog"))
  }

  @Test
  fun `the outline shows the tree, its values and modifiers, and never asset bytes`() {
    val outline = UiBuilderGuidelinePrompt.outline(wearDocument())
    assertEquals(
      """
      - screen: wear-m3/screen-scaffold {timeText="10:10"}
        content:
          - list: wear-m3/transforming-lazy-column
            items:
              - one: wear-m3/button {label="Start"} modifiers[fillMaxWidth] events[onClick]
              - two: wear-m3/button {label="Stop"} modifiers[width(value=80)]

      """
        .trimIndent(),
      outline,
    )
    assertFalse("base64" in outline)
  }

  @Test
  fun `the request carries the rules for the platform, and a picture only when there is one`() {
    val rules = UiBuilderGuidelineRuleSet.bundled().rules.filter { "wear" in it.platforms }
    val bare =
      UiBuilderGuidelinePrompt.requestBody("m", "wear", wearDocument(), rules, pngDataUrl = null)
    val content = bare.userContent()
    assertEquals(1, content.size)
    val text = content.single().jsonObject["text"]!!.jsonPrimitive.content
    assertTrue("Platform: wear" in text, text)
    assertTrue("192×192dp" in text, text)
    rules.forEach { assertTrue("ruleId: ${it.id}" in text, it.id) }
    assertEquals(
      "json_schema",
      bare["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content,
    )

    val pictured =
      UiBuilderGuidelinePrompt.requestBody(
        "m",
        "wear",
        wearDocument(),
        rules,
        "data:image/png;base64,AAAA",
      )
    assertEquals(
      "image_url",
      pictured.userContent().last().jsonObject["type"]!!.jsonPrimitive.content,
    )
  }

  @Test
  fun `verdicts are read from a completion, fenced or not`() {
    val verdicts =
      UiBuilderGuidelinePrompt.parseVerdicts(
        "```json\n{\"verdicts\":[{\"ruleId\":\"a\",\"verdict\":\"fail\",\"confidence\":1.4," +
          "\"nodeIds\":[\"x\"],\"reason\":\"r\"}]}\n```"
      )
    assertEquals(1, verdicts.size)
    assertEquals(1.0, verdicts.single().confidence)
    assertFailsWith<IllegalArgumentException> { UiBuilderGuidelinePrompt.parseVerdicts("no json") }
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
              verdict("wear.dialog.dedicated-task", "not_applicable", 0.9),
              verdict("wear.layout.time-text-shown", "pass", 0.9),
            ),
          )
        }
      )

    val outcome = guidelines.check(wearDocument(), png = null)

    assertIs<UiBuilderGuidelineOutcome.Checked>(outcome)
    val finding = outcome.findings.single()
    assertEquals(CHECK_GUIDELINES, finding.check)
    assertEquals("wear.layout.responsive-width", finding.code)
    assertEquals("two", finding.nodeId)
    assertEquals("warning", finding.severity)
    assertTrue("developer.android.com" in finding.message, finding.message)
    // No picture, so no visual rule was asked about, and the reply says how many were left out.
    val visual =
      UiBuilderGuidelineRuleSet.bundled().rules.filter {
        "wear" in it.platforms && it.kind == "visual"
      }
    assertEquals(visual.size, outcome.visualSkipped)
    visual.forEach { assertFalse("ruleId: ${it.id}" in sent!!, it.id) }
  }

  @Test
  fun `a component's body is outlined once, and each placement names it`() {
    val document =
      Json.parseToJsonElement(
          """
          {
            "roots": ["screen"],
            "components": {"card": {"name": "Card", "root": "card-root"}},
            "nodes": {
              "screen": {"componentId": "layout/column", "slots": {"children": ["a"]}},
              "a": {"componentId": "design/component-instance",
                "component": {"componentKey": "card",
                  "arguments": {"title": {"type": "string", "value": "Hi"}}}},
              "card-root": {"componentId": "wear-m3/card", "slots": {"content": ["t"]}},
              "t": {"componentId": "wear-m3/text",
                "properties": {"text": {"type": "binding", "value": "title"}}}
            }
          }
          """
        )
        .jsonObject
    val outline = UiBuilderGuidelinePrompt.outline(document)
    assertTrue("- a: design/component-instance instance of card (title=\"Hi\")" in outline, outline)
    assertTrue("component card (Card):\n  - card-root: wear-m3/card" in outline, outline)
    assertTrue("- t: wear-m3/text {text={title}}" in outline, outline)
    assertFalse("more nodes not shown" in outline, outline)
  }

  @Test
  fun `rules the model skipped are unanswered, not passed, and no verdicts at all is a failure`():
    Unit = runBlocking {
    val partial = guidelines { _, _ ->
      OpenRouterTransport.Response(
        200,
        completion(
          verdict("wear.layout.responsive-width", "pass", 0.9),
          verdict("wear.layout.responsive-width", "fail", 0.9, "two"),
          verdict("not.a.rule", "fail", 0.9),
        ),
      )
    }
      .check(wearDocument(), null)
    assertIs<UiBuilderGuidelineOutcome.Checked>(partial)
    // The first verdict for a rule stands; a duplicate and an unknown rule are ignored.
    assertTrue(partial.findings.isEmpty())
    assertEquals(1, partial.judged)
    val structural =
      UiBuilderGuidelineRuleSet.bundled().rules.filter {
        "wear" in it.platforms && it.kind != "visual"
      }
    assertEquals(structural.size - 1, partial.unanswered.size)
    assertFalse("wear.layout.responsive-width" in partial.unanswered)

    val empty = guidelines { _, _ ->
      OpenRouterTransport.Response(200, completion())
    }
      .check(wearDocument(), null)
    assertIs<UiBuilderGuidelineOutcome.Failed>(empty)
  }

  @Test
  fun `a refusal from OpenRouter or an unreadable answer is a failure, not a finding`(): Unit =
    runBlocking {
      val refused = guidelines { _, _ ->
        OpenRouterTransport.Response(402, """{"error":{"message":"Insufficient credits"}}""")
      }
        .check(wearDocument(), null)
      assertIs<UiBuilderGuidelineOutcome.Failed>(refused)
      assertTrue("Insufficient credits" in refused.reason, refused.reason)

      val garbled = guidelines { _, _ ->
        OpenRouterTransport.Response(200, completionText("sorry"))
      }
        .check(wearDocument(), null)
      assertIs<UiBuilderGuidelineOutcome.Failed>(garbled)
    }

  @Test
  fun `a catalog with no guidelines is skipped without calling the model`(): Unit = runBlocking {
    val outcome = guidelines { _, _ ->
      error("must not be called")
    }
      .check(wearDocument(systemId = "m3-catalog"), null)
    assertIs<UiBuilderGuidelineOutcome.Skipped>(outcome)
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

  private fun JsonObject.userContent() =
    this["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray

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
