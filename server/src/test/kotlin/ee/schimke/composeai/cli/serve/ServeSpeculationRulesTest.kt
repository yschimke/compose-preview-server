package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The speculation rules every page carries ([ServeWeb.SPECULATION_RULES]): well-formed JSON a
 * browser will accept, prefetch only, at moderate eagerness, and never towards a route that acts,
 * signs in or out, streams or renders.
 */
class ServeSpeculationRulesTest {

  @Test
  fun `the rules are prefetch-only JSON that exclude the acting routes`() {
    val tag = ServeWeb.SPECULATION_RULES
    assertTrue(tag.startsWith("<script type=\"speculationrules\">"), tag)
    val json = Json.parseToJsonElement(tag.substringAfter('>').substringBeforeLast("</script>"))
    val root = json.jsonObject
    assertEquals(setOf("prefetch"), root.keys, "never prerender: a viewer that ran would act")
    val rule = root.getValue("prefetch").jsonArray.single().jsonObject
    assertEquals("moderate", rule.getValue("eagerness").jsonPrimitive.content)
    val excluded =
      rule
        .getValue("where")
        .jsonObject
        .getValue("and")
        .jsonArray
        .map { it.jsonObject }
        .mapNotNull { it["not"]?.jsonObject?.get("href_matches")?.jsonArray }
        .single()
        .map { it.jsonPrimitive.content }
    for (path in listOf("/api/*", "/auth/*", "/admin/*", "/report-bug*", "/ws/*", "/render/*")) {
      assertTrue(path in excluded, "$path is never prefetched: $excluded")
    }
  }
}
