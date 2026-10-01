package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a hosted tool result must carry for a chat surface (#1254): a person in a Slack thread sees
 * text and links, never an MCP image block, so a result that shows somebody a picture carries a
 * fetchable https link to it — as a `resource_link` and as a line of text — and none of the text
 * the agent reads carries the pixels as base64 or points at a local `file://`.
 */
internal object ChatFallbackAssertions {

  /** Every text the model reads: text blocks and the structured content. */
  fun modelText(result: JsonObject): String = buildString {
    result["content"]?.jsonArray?.forEach { block ->
      val obj = block.jsonObject
      if (obj["type"]?.jsonPrimitive?.contentOrNull == "text") {
        append(obj["text"]!!.jsonPrimitive.content).append('\n')
      }
    }
    result["structuredContent"]?.let { append(it.toString()) }
  }

  fun httpsImageLinks(result: JsonObject, origin: String): List<String> =
    result["content"]!!
      .jsonArray
      .map { it.jsonObject }
      .filter { it["type"]?.jsonPrimitive?.contentOrNull == "resource_link" }
      .mapNotNull { it["uri"]?.jsonPrimitive?.contentOrNull }
      .filter { it.startsWith("$origin/") }

  fun assertNoInlinePixels(result: JsonObject, vararg pngs: ByteArray) {
    val text = modelText(result)
    assertFalse("file://" in text, "a hosted result must not point at a local file: $text")
    assertFalse("data:image" in text, "a hosted result must not inline a data: URI")
    for (png in pngs) {
      val encoded = Base64.getEncoder().encodeToString(png)
      assertFalse(encoded in text, "the PNG must not travel as base64 in the model's text")
    }
  }

  /** The full contract: an https link the person can open, said in text, and no inline pixels. */
  fun assertChatReadable(result: JsonObject, origin: String, vararg pngs: ByteArray) {
    val links = httpsImageLinks(result, origin)
    assertTrue(links.isNotEmpty(), "expected an https image link on $origin in $result")
    val text = modelText(result)
    assertTrue(
      links.any { "Image: $it" in text },
      "the https link must also be said in text, for a host that shows only text: $text",
    )
    assertNoInlinePixels(result, *pngs)
  }
}
