package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.GuidelineModel
import ee.schimke.composeai.guidelines.ModelResponse
import ee.schimke.composeai.guidelines.protocol.GuidelineRequestV1
import ee.schimke.composeai.mcp.protocol.ContentBlock
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Test

class PreviewGuidelinesMcpTest {
  private val buildDir: File =
    Files.createTempDirectory("guidelines").toFile().also { dir ->
      File(dir, "compose-previews").mkdirs()
      File(dir, "compose-previews/ui-builder.guidelines.json")
        .writeText(
          """
          {"schema":"compose-ui-builder/catalog-guidelines/v1","catalog":"wear-m3",
           "platform":"wear","version":2,
           "rules":[{"id":"touch","kind":"visual","severity":"warning",
             "guidance":"Tap targets are at least 48dp.","check":"Are tap targets 48dp?",
             "source":"https://developer.android.com/x"}]}
          """
        )
    }

  private class FakeModel : GuidelineModel {
    var requests = 0

    override fun complete(request: GuidelineRequestV1, model: String): ModelResponse {
      requests++
      val alias = request.subjects.firstOrNull()?.id ?: "s1"
      val content =
        """{"verdicts":[{"ruleId":"touch","verdict":"fail","confidence":0.9,""" +
          """"nodeIds":["stop"],"reason":"36dp button","subjectId":"$alias","needs":[],""" +
          """"regions":[]}]}"""
      val body = buildJsonObject {
        put("id", "gen-1")
        put("model", "deepseek/deepseek-v4.1-flash")
        put("provider", "DeepInfra")
        put("usage", buildJsonObject { put("cost", 0.0021) })
        put(
          "choices",
          buildJsonArray {
            add(buildJsonObject { put("message", buildJsonObject { put("content", content) }) })
          },
        )
      }
      return ModelResponse(200, body.toString())
    }
  }

  private inner class FakeHost(
    override val openRouterKey: String?,
    val hierarchyProblem: String? = null,
  ) : PreviewGuidelinesMcp.Host {
    val model = FakeModel()

    override fun resolve(ref: String) =
      if (ref == "Missing") null
      else
        PreviewGuidelinesMcp.Resolved(
          "compose-preview://w/:app/$ref",
          "com.example.$ref",
          ref,
          buildDir,
        )

    override fun render(preview: PreviewGuidelinesMcp.Resolved): ByteArray = png()

    override fun a11yHierarchy(
      preview: PreviewGuidelinesMcp.Resolved
    ): PreviewGuidelinesMcp.Hierarchy =
      if (hierarchyProblem != null) PreviewGuidelinesMcp.Hierarchy(null, hierarchyProblem)
      else PreviewGuidelinesMcp.Hierarchy(hierarchyJson())

    private fun hierarchyJson(): JsonElement = buildJsonObject {
      put(
        "nodes",
        buildJsonArray {
          add(
            buildJsonObject {
              put("ref", "stop")
              put("role", "Button")
              put("label", "Stop")
              put("boundsInScreen", "2,2,20,20")
            }
          )
        },
      )
    }

    override fun source(preview: PreviewGuidelinesMcp.Resolved) = "@Composable fun Stop() {}"

    override fun model(key: String): GuidelineModel = model
  }

  private fun args(vararg previews: String, extra: Map<String, Double> = emptyMap()) =
    JsonObject(
      mapOf("previews" to JsonArray(previews.map { JsonPrimitive(it) })) +
        extra.mapValues { JsonPrimitive(it.value) }
    )

  @Test
  fun `the prompt batches the previews and returns their renders as images, with no key`() {
    val host = FakeHost(openRouterKey = null)
    val result = PreviewGuidelinesMcp.prompt(args("Stop", "Go"), host)
    assertThat(result.isError).isNull()
    val request =
      kotlinx.serialization.json.Json.parseToJsonElement(
          (result.content.first() as ContentBlock.Text).text
        )
        .jsonObject
    assertThat(request["subjects"]!!.jsonArray).hasSize(2)
    assertThat(request.toString()).contains("stop")
    assertThat(result.content.filterIsInstance<ContentBlock.Image>()).hasSize(2)
    assertThat(host.model.requests).isEqualTo(0)
  }

  @Test
  fun `the check judges the previews and names the nodes, the model and the cost`() {
    val host = FakeHost(openRouterKey = "sk-or-test")
    val result = PreviewGuidelinesMcp.check(args("Stop"), host)
    assertThat(result.isError).isNull()
    val previews = result.structuredContent!!["previews"]!!.jsonArray
    val record = previews.single().jsonObject["record"]!!.jsonObject
    val verdict = record["verdicts"]!!.jsonArray.single().jsonObject
    assertThat(verdict["nodeIds"]!!.jsonArray.map { it.jsonPrimitive.content })
      .containsExactly("stop")
    assertThat(record["servedModel"]!!.jsonPrimitive.content)
      .isEqualTo("deepseek/deepseek-v4.1-flash")
    assertThat((result.content.first() as ContentBlock.Text).text)
      .contains("touch (stop): 36dp button")
  }

  @Test
  fun `without a key the check says how to set one`() {
    val result = PreviewGuidelinesMcp.check(args("Stop"), FakeHost(openRouterKey = null))
    assertThat(result.isError).isTrue()
    val text = (result.content.single() as ContentBlock.Text).text
    assertThat(text).contains(PreviewGuidelinesMcp.KEY_ENV)
    assertThat(text).contains(PreviewGuidelinesMcp.PROMPT_TOOL)
  }

  @Test
  fun `an unknown preview is an error, not a guess`() {
    val result = PreviewGuidelinesMcp.prompt(args("Missing"), FakeHost(openRouterKey = null))
    assertThat(result.isError).isTrue()
  }

  @Test
  fun `a preview with no accessibility nodes is said to have none, in both tools`() {
    val host =
      FakeHost(openRouterKey = "sk-or-test", hierarchyProblem = "a11y/hierarchy: not enabled")
    val prompt = PreviewGuidelinesMcp.prompt(args("Stop"), host)
    assertThat(prompt.content.filterIsInstance<ContentBlock.Text>().map { it.text })
      .contains(
        "Note: com.example.Stop: no accessibility nodes (a11y/hierarchy: not enabled), so its findings cannot name nodes"
      )
    val check = PreviewGuidelinesMcp.check(args("Stop"), host)
    assertThat(check.structuredContent!!["notes"]!!.jsonArray.single().jsonPrimitive.content)
      .contains("no accessibility nodes")
    assertThat((check.content.first() as ContentBlock.Text).text).contains("Note: com.example.Stop")
  }

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()
}
