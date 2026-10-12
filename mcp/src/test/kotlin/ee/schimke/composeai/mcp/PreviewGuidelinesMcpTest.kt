package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.guidelines.CatalogGuidelinesLoader
import ee.schimke.composeai.guidelines.GuidelineCredentials
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
  private companion object {
    /** Stands in for the fetched packs: one widget rule on the Wear widget profile. */
    const val WIDGET_PACK =
      """
      {"schema":"compose-ui-builder/catalog-guidelines/v1","catalog":"default-wear-widgets",
       "platform":"wear","version":1,
       "rules":[{"id":"wear.widgets.background-on-container","kind":"structure",
         "severity":"warning","surfaces":["widget"],"profiles":["wear-widgets"],
         "guidance":"Set the background on the widget document.",
         "check":"Is the background set on the widget document?",
         "source":"https://developer.android.com/x"}]}
      """
  }

  private val projectRoot: File = Files.createTempDirectory("project").toFile()

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
    key: String?,
    val hierarchyProblem: String? = null,
    /** Where the key came from: the environment unless a credentials file is named. */
    keySource: String = GuidelineCredentials.ENV,
    val trustedRoots: Set<File> = emptySet(),
    /** Keys stored per endpoint origin, as `guidelines key set --for` would. */
    val endpointKeys: Map<String, String> = emptyMap(),
    /** The module build directory; one without a guidelines file asks the default packs. */
    val dir: File = buildDir,
    /** Previews from another module: name to build directory. */
    val otherModules: Map<String, File> = emptyMap(),
  ) : PreviewGuidelinesMcp.Host {
    val model = FakeModel()

    /** The catalog text the default guidelines were asked for; no pack is fetched. */
    var defaultCatalog: String? = null

    override fun loadDefaultGuidelines(text: String): CatalogGuidelinesLoader.Loaded {
      defaultCatalog = text
      return CatalogGuidelinesLoader.parse(WIDGET_PACK)
    }

    /** The (base URL, key) each endpoint client was made for. */
    val endpoints = mutableListOf<Pair<String, String?>>()

    override fun endpointKey(baseUrl: String) =
      endpointKeys[GuidelineCredentials.endpointProvider(baseUrl)]?.let {
        GuidelineCredentials.Resolved(it, "/home/u/.config/compose-preview/c.json")
      }

    override fun endpointModel(baseUrl: String, key: String?): GuidelineModel = model.also {
      endpoints += baseUrl to key
    }

    override val openRouterKey = key?.let { GuidelineCredentials.Resolved(it, keySource) }

    override fun trusted(key: GuidelineCredentials.Resolved, project: File) =
      project in trustedRoots

    override fun resolve(ref: String) =
      if (ref == "Missing") null
      else
        PreviewGuidelinesMcp.Resolved(
          "compose-preview://w/:app/$ref",
          "com.example.$ref",
          ref,
          otherModules[ref] ?: dir,
          projectRoot,
        )

    var renders = 0

    override fun render(preview: PreviewGuidelinesMcp.Resolved): ByteArray =
      png().also { renders++ }

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

  /** A module with a manifest but no guidelines: what a plain app module discovers. */
  private fun moduleWithoutGuidelines(vararg entries: String): File =
    Files.createTempDirectory("no-guidelines").toFile().also { dir ->
      File(dir, "compose-previews").mkdirs()
      File(dir, "compose-previews/previews.json")
        .writeText("""{"module": "app", "previews": [${entries.joinToString()}]}""")
    }

  private fun wearWidget(name: String) =
    """{"id": "com.example.$name", "params": {"device": null,
       "previewParameterProviderClassName": "androidx.glance.wear.preview.RoundAllWidgetPreviewParams"}}"""

  private fun phoneScreen(name: String) =
    """{"id": "com.example.$name", "params": {"device": "id:pixel_9"}}"""

  private fun args(vararg previews: String, extra: Map<String, Double> = emptyMap()) =
    JsonObject(
      mapOf("previews" to JsonArray(previews.map { JsonPrimitive(it) })) +
        extra.mapValues { JsonPrimitive(it.value) }
    )

  private fun endpointArgs(baseUrl: String, model: String? = "llava:13b") =
    JsonObject(
      args("Stop") +
        mapOf("base_url" to JsonPrimitive(baseUrl)) +
        listOfNotNull(model?.let { "model" to JsonPrimitive(it) })
    )

  @Test
  fun `the prompt batches the previews and returns their renders as images, with no key`() {
    val host = FakeHost(key = null)
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
  fun `a preview's surface and profile come from its manifest entry`() {
    // A Wear widget sticker names no device; without its manifest entry it was asked component
    // rules, and a catalog whose rules are all widget rules asked it nothing.
    File(buildDir, "compose-previews/previews.json")
      .writeText(
        """
        {"module": "app", "previews": [
          {"id": "com.example.Widget", "params": {"device": null, "widthDp": 216, "heightDp": 124},
           "widget": {"host": "wear", "profile": "wear-widgets"}}
        ]}
        """
      )
    val host = FakeHost(key = null)
    val widget = host.resolve("Widget")!!
    assertThat(host.manifestEntry(widget)).isNotNull()
    fun provenance(vararg extra: Pair<String, String>): String {
      val call =
        JsonObject(
          mapOf("previews" to JsonArray(listOf(JsonPrimitive("Widget")))) +
            extra.associate { (k, v) -> k to JsonPrimitive(v) }
        )
      val text = (PreviewGuidelinesMcp.prompt(call, host).content.first() as ContentBlock.Text).text
      return kotlinx.serialization.json.Json.parseToJsonElement(text)
        .jsonObject["provenance"]!!
        .toString()
    }
    assertThat(provenance()).contains("surface `widget`")
    assertThat(provenance("surface" to "component")).contains("surface `component`")
    // A preview the manifest does not list is a component, as before.
    assertThat(host.manifestEntry(host.resolve("Stop")!!)).isNull()
  }

  @Test
  fun `a module that publishes no guidelines is asked the default packs for its previews`() {
    val host = FakeHost(key = null, dir = moduleWithoutGuidelines(wearWidget("Weather")))
    val result = PreviewGuidelinesMcp.prompt(args("Weather"), host)
    assertThat(result.isError).isNull()
    // A Glance Wear widget is asked what remote-m3-catalog's Wear widget catalog includes.
    val catalog =
      kotlinx.serialization.json.Json.parseToJsonElement(host.defaultCatalog!!).jsonObject
    assertThat(catalog["platform"]!!.jsonPrimitive.content).isEqualTo("wear")
    assertThat(catalog["includes"]!!.jsonArray.map { it.jsonObject["url"]!!.jsonPrimitive.content })
      .containsExactly(
        DefaultGuidelinePacks.Pack.REMOTE_COMPOSE.url,
        DefaultGuidelinePacks.Pack.WEAR_WIDGETS.url,
      )
      .inOrder()
    assertThat(
        catalog["includes"]!!.jsonArray.map { it.jsonObject["sha256"]!!.jsonPrimitive.content }
      )
      .containsExactly(
        DefaultGuidelinePacks.Pack.REMOTE_COMPOSE.sha256,
        DefaultGuidelinePacks.Pack.WEAR_WIDGETS.sha256,
      )
      .inOrder()
    val texts = result.content.filterIsInstance<ContentBlock.Text>().map { it.text }
    assertThat(texts.last())
      .contains("publishes no ui-builder.guidelines.json, so these are the default wear-widgets")
    assertThat(texts.last()).contains("surface `widget`, profile `wear-widgets`")
  }

  @Test
  fun `the default packs follow the preview's surface and device`() {
    fun choice(entry: String) =
      kotlinx.serialization.json.Json.parseToJsonElement(entry).jsonObject.let {
        DefaultGuidelinePacks.choose(ee.schimke.composeai.guidelines.GuidelineSurfaces.of(it), it)
      }
    assertThat(choice(wearWidget("W")).packs)
      .containsExactly(
        DefaultGuidelinePacks.Pack.REMOTE_COMPOSE,
        DefaultGuidelinePacks.Pack.WEAR_WIDGETS,
      )
    assertThat(choice("""{"id": "a", "params": {"device": "id:wearos_small_round"}}""").packs)
      .containsExactly(
        DefaultGuidelinePacks.Pack.COMPOSE_UI,
        DefaultGuidelinePacks.Pack.WEAR_COMPOSE,
      )
      .inOrder()
    assertThat(choice(phoneScreen("P")).packs)
      .containsExactly(DefaultGuidelinePacks.Pack.COMPOSE_UI)
    assertThat(choice("""{"id": "g", "params": {"kind": "GLANCE_APPWIDGET"}}""").packs)
      .containsExactly(DefaultGuidelinePacks.Pack.LAUNCHER_WIDGETS)
    assertThat(DefaultGuidelinePacks.choose(null, null).packs)
      .containsExactly(DefaultGuidelinePacks.Pack.COMPOSE_UI)
  }

  @Test
  fun `previews needing different default packs are asked in separate calls`() {
    val host =
      FakeHost(
        key = null,
        dir = moduleWithoutGuidelines(wearWidget("Weather"), phoneScreen("Home")),
      )
    val text = errorText(PreviewGuidelinesMcp.prompt(args("Weather", "Home"), host))
    assertThat(text).contains("need different default guidelines")
    assertThat(text).contains("wear-widgets (remote-compose + wear-widgets")
    assertThat(text).contains("compose-ui (compose-ui")
    assertThat(host.defaultCatalog).isNull()
  }

  @Test
  fun `previews from modules with different guidelines are asked in separate calls`() {
    // The first preview's module publishes none; a later one's catalog must not be ignored.
    val host =
      FakeHost(
        key = null,
        dir = moduleWithoutGuidelines(wearWidget("Weather")),
        otherModules = mapOf("Stop" to buildDir),
      )
    val text = errorText(PreviewGuidelinesMcp.prompt(args("Weather", "Stop"), host))
    assertThat(text).contains("modules with different guidelines")
    assertThat(text).contains("none")
    assertThat(host.defaultCatalog).isNull()
  }

  @Test
  fun `a widget the manifest puts on the Wear host is a Wear widget, profile or not`() {
    val entry =
      kotlinx.serialization.json.Json.parseToJsonElement(
          """{"id": "w", "params": {"device": null}, "widget": {"host": "wear"}}"""
        )
        .jsonObject
    val choice =
      DefaultGuidelinePacks.choose(
        ee.schimke.composeai.guidelines.GuidelineSurfaces.of(entry),
        entry,
      )
    assertThat(choice.name).isEqualTo("wear-widgets")
  }

  @Test
  fun `guidelines that ask a preview nothing say which would`() {
    // wear-compose's rules are all screen rules; a Wear widget was asked none of them (#149).
    val screens =
      File(Files.createTempDirectory("screen-rules").toFile(), "ui-builder.guidelines.json").apply {
        writeText(
          """
            {"schema":"compose-ui-builder/catalog-guidelines/v1","catalog":"wear-compose",
             "platform":"wear","version":1,
             "rules":[{"id":"margins","kind":"visual","severity":"warning","surfaces":["screen"],
               "guidance":"Use proportional margins.","check":"Are margins proportional?",
               "source":"https://developer.android.com/x"}]}
            """
        )
      }
    val host = FakeHost(key = "sk-or-test", dir = moduleWithoutGuidelines(wearWidget("Weather")))
    val call =
      JsonObject(
        mapOf(
          "previews" to JsonArray(listOf(JsonPrimitive("Weather"))),
          "guidelines" to JsonPrimitive(screens.path),
        )
      )
    val text = errorText(PreviewGuidelinesMcp.prompt(call, host))
    assertThat(text).contains("applies to surface `widget`, profile `wear-widgets`")
    assertThat(text).contains("wear-widgets (remote-compose + wear-widgets")
    // The check still answers, with the same pointer as a note.
    val check = PreviewGuidelinesMcp.check(call, host)
    assertThat(check.isError).isNull()
    assertThat(check.structuredContent!!["notes"]!!.jsonArray.single().jsonPrimitive.content)
      .contains("wear-widgets (remote-compose + wear-widgets")
  }

  @Test
  fun `the check judges the previews and names the nodes, the model and the cost`() {
    val host = FakeHost(key = "sk-or-test")
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
    val result = PreviewGuidelinesMcp.check(args("Stop"), FakeHost(key = null))
    assertThat(result.isError).isTrue()
    val text = (result.content.single() as ContentBlock.Text).text
    assertThat(text).contains(PreviewGuidelinesMcp.KEY_ENV)
    assertThat(text).contains(PreviewGuidelinesMcp.PROMPT_TOOL)
  }

  @Test
  fun `an unknown preview is an error, not a guess`() {
    val result = PreviewGuidelinesMcp.prompt(args("Missing"), FakeHost(key = null))
    assertThat(result.isError).isTrue()
  }

  @Test
  fun `a preview with no accessibility nodes is said to have none, in both tools`() {
    val host = FakeHost(key = "sk-or-test", hierarchyProblem = "a11y/hierarchy: not enabled")
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

  private fun withGuidelines(location: String) =
    JsonObject(
      mapOf(
        "previews" to JsonArray(listOf(JsonPrimitive("Stop"))),
        "guidelines" to JsonPrimitive(location),
      )
    )

  private fun errorText(result: ee.schimke.composeai.mcp.protocol.CallToolResult): String {
    assertThat(result.isError).isTrue()
    return result.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
  }

  @Test
  fun `a guidelines path must name a ui-builder guidelines file, so no other file is read`() {
    val dir = Files.createTempDirectory("guidelines-path").toFile()
    val dotEnv = File(dir, ".env").apply { writeText("OPENROUTER_KEY=sk-or-not-a-real-key\n") }
    for (tool in listOf(PreviewGuidelinesMcp::prompt, PreviewGuidelinesMcp::check)) {
      val text = errorText(tool(withGuidelines(dotEnv.path), FakeHost(key = "sk-or-test")))
      assertThat(text).contains("ui-builder.guidelines.json")
      assertThat(text).doesNotContain("sk-or-not-a-real-key")
    }
    // A symlink carrying the right name is judged by what it points at.
    val link = File(File(dir, "linked").apply { mkdirs() }, "ui-builder.guidelines.json")
    Files.createSymbolicLink(link.toPath(), dotEnv.toPath())
    val text = errorText(PreviewGuidelinesMcp.prompt(withGuidelines(link.path), FakeHost(null)))
    assertThat(text).contains("must be an http(s) URL")
    assertThat(text).doesNotContain("sk-or-not-a-real-key")
  }

  @Test
  fun `a malformed guidelines file is reported without echoing its contents`() {
    val dir = Files.createTempDirectory("guidelines-bad").toFile()
    val bad =
      File(dir, "ui-builder.guidelines.json").apply { writeText("SECRET=hunter2-not-json\n") }
    val text = errorText(PreviewGuidelinesMcp.prompt(withGuidelines(bad.path), FakeHost(null)))
    assertThat(text).contains("not a readable ui-builder.guidelines.json")
    assertThat(text).doesNotContain("hunter2")
    assertThat(text).doesNotContain("SECRET")
  }

  @Test
  fun `a problem keeps its first line only`() {
    assertThat(
        PreviewGuidelinesMcp.redactedProblem(
          "not a readable ui-builder.guidelines.json: Unexpected JSON token at offset 0: " +
            "Expected start of the object '{', but had 'S' instead at path: \$\n" +
            "JSON input: SECRET=hunter2"
        )
      )
      .isEqualTo("not a readable ui-builder.guidelines.json (invalid at offset 0)")
    assertThat(PreviewGuidelinesMcp.redactedProblem("https://x answered 500\nbody"))
      .isEqualTo("https://x answered 500")
  }

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()

  @Test
  fun `a stored key is refused for an untrusted project before anything is rendered`() {
    val host = FakeHost(key = "sk-or-test", keySource = "/home/u/.config/compose-preview/c.json")
    val result = PreviewGuidelinesMcp.check(args("Stop"), host)
    assertThat(result.isError).isTrue()
    val text = (result.content.single() as ContentBlock.Text).text
    assertThat(text).contains("compose-preview guidelines key trust ${projectRoot.canonicalPath}")
    assertThat(text).doesNotContain("sk-or-test")
    assertThat(host.model.requests).isEqualTo(0)
    assertThat(host.renders).isEqualTo(0)
  }

  @Test
  fun `a stored key is used for a trusted project`() {
    val host =
      FakeHost(
        key = "sk-or-test",
        keySource = "/home/u/.config/compose-preview/c.json",
        trustedRoots = setOf(projectRoot),
      )
    assertThat(PreviewGuidelinesMcp.check(args("Stop"), host).isError).isNull()
  }

  @Test
  fun `a loopback endpoint is asked without a key, and never with OpenRouter's`() {
    val host = FakeHost(key = "sk-or-test")
    val result = PreviewGuidelinesMcp.check(endpointArgs("http://localhost:11434/v1"), host)
    assertThat(result.isError).isNull()
    assertThat(host.endpoints).containsExactly("http://localhost:11434/v1" to null)
  }

  @Test
  fun `a remote endpoint needs a key stored for it, so a call cannot pick where previews go`() {
    val host = FakeHost(key = "sk-or-test")
    val result = PreviewGuidelinesMcp.check(endpointArgs("https://evil.example/v1"), host)
    assertThat(result.isError).isTrue()
    val text = (result.content.single() as ContentBlock.Text).text
    assertThat(text).contains("guidelines key set --for https://evil.example/v1")
    assertThat(text).doesNotContain("sk-or-test")
    assertThat(host.renders).isEqualTo(0)
    assertThat(host.endpoints).isEmpty()
  }

  @Test
  fun `an endpoint's stored key is sent only for a trusted project`() {
    val keys = mapOf("endpoint:https://api.example.com" to "sk-ex-1234")
    val untrusted = FakeHost(key = null, endpointKeys = keys)
    val refused = PreviewGuidelinesMcp.check(endpointArgs("https://api.example.com/v1"), untrusted)
    assertThat(refused.isError).isTrue()
    assertThat((refused.content.single() as ContentBlock.Text).text)
      .contains("compose-preview guidelines key trust ${projectRoot.canonicalPath}")
    assertThat(untrusted.renders).isEqualTo(0)

    val trusted = FakeHost(key = null, endpointKeys = keys, trustedRoots = setOf(projectRoot))
    val result = PreviewGuidelinesMcp.check(endpointArgs("https://api.example.com/v1"), trusted)
    assertThat(result.isError).isNull()
    assertThat(trusted.endpoints).containsExactly("https://api.example.com/v1" to "sk-ex-1234")
  }

  @Test
  fun `an endpoint needs a model and an http URL`() {
    val host = FakeHost(key = null)
    assertThat(
        PreviewGuidelinesMcp.check(endpointArgs("http://localhost:1/v1", model = null), host)
          .isError
      )
      .isTrue()
    assertThat(PreviewGuidelinesMcp.check(endpointArgs("file:///etc/passwd"), host).isError)
      .isTrue()
    assertThat(host.renders).isEqualTo(0)
  }
}
