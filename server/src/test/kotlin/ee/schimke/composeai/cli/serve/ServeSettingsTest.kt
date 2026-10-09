package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.ServeSettings.Source
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * `settings.json` ([ServeSettings]) and `/admin/settings` ([ServeSettingsAdmin]): the committed
 * file validates, values come from the right source in the right order, a live setting applies on
 * publish and a restart setting waits for the next start.
 */
class ServeSettingsTest {

  private val repoRoot: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .first { File(it, "deploy/image/entrypoint.sh").isFile }

  private fun doc(text: String): JsonObject = ServeSettings.parse(text)

  @Test
  fun `the committed schema and reference are the generated ones`() {
    val schema = File(repoRoot, "deploy/image/settings.schema.json")
    val reference = File(repoRoot, "deploy/image/SETTINGS.md")
    if (System.getenv("UPDATE_SERVE_SETTINGS_REFERENCE") == "true") {
      schema.writeText(ServeSettings.schemaText())
      reference.writeText(ServeSettings.reference())
    }
    val hint = "regenerate with UPDATE_SERVE_SETTINGS_REFERENCE=true on ServeSettingsTest"
    assertEquals(ServeSettings.schemaText(), schema.readText(), hint)
    assertEquals(ServeSettings.reference(), reference.readText(), hint)
  }

  @Test
  fun `the deployment's settings json validates`() {
    // A typo fails here, in the pull request, rather than on the box.
    for (file in File(repoRoot, "deploy").walk().filter { it.name == "settings.json" }) {
      assertEquals(emptyList(), ServeSettings.validate(doc(file.readText())), file.path)
    }
  }

  @Test
  fun `every setting is a variable the image actually reads`() {
    val entrypoint = File(repoRoot, "deploy/image/entrypoint.sh").readText()
    val analytics =
      File(repoRoot, "server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeAnalytics.kt")
        .readText()
    for (setting in ServeSettings.ALL) {
      assertTrue(
        "\${${setting.env}" in entrypoint || "\"${setting.env}\"" in analytics,
        "${setting.env} (${setting.key}) is read by neither the entrypoint nor the server",
      )
    }
  }

  @Test
  fun `no secret is a setting`() {
    for (setting in ServeSettings.ALL) {
      assertFalse(
        listOf("TOKEN", "SECRET", "KEY", "PASSWORD").any { it in setting.env.removeSuffix("_ID") },
        "${setting.env} looks like a secret; secrets stay in .env",
      )
    }
  }

  @Test
  fun `validation names the typo and the setting it meant`() {
    val problems =
      ServeSettings.validate(
        doc("""{"uiBuilder": {"guidelines": {"modle": "x", "users": "yschimke"}}}""")
      )
    assertTrue(
      problems.any { "uiBuilder.guidelines.modle" in it && "uiBuilder.guidelines.model" in it },
      problems.toString(),
    )
    assertTrue(
      problems.any { "uiBuilder.guidelines.users" in it && "list" in it },
      problems.toString(),
    )
    assertEquals(
      listOf("`rendering.compileEngine` must be true or false"),
      ServeSettings.validate(doc("""{"rendering": {"compileEngine": "1"}}""")),
    )
    assertTrue(
      ServeSettings.validate(doc("""{"agents": {"grantCapabilities": ["telepathy"]}}"""))
        .isNotEmpty()
    )
    assertTrue(
      ServeSettings.validate(doc("""{"uploads": {"imageRepository": "not a repo"}}""")).isNotEmpty()
    )
    assertTrue(ServeSettings.validate(doc("""{"uiBuilder": "x"}""")).isNotEmpty())
    // A scope the server would refuse at startup is refused here instead.
    assertTrue(
      ServeSettings.validate(doc("""{"auth": {"github": {"scope": "repo"}}}""")).isNotEmpty()
    )
    assertEquals(
      emptyList(),
      ServeSettings.validate(doc("""{"auth": {"github": {"scope": "read:user read:org"}}}""")),
    )
    assertEquals(
      emptyList(),
      ServeSettings.validate(doc("""{"${'$'}schema": "../image/settings.schema.json"}""")),
    )
  }

  @Test
  fun `values are spelled as the entrypoint reads their variables`() {
    val values =
      ServeSettings.values(
          doc(
            """
            {"uiBuilder": {"adminActors": [], "guidelines": {"users": ["a", "b"], "pictureBudgetSeconds": 30}},
             "catalogs": {"mcp": true}, "uploads": {"acceptImages": false}}
            """
          )
        )
        .mapKeys { it.key.env }
    assertEquals(
      mapOf(
        "SERVE_UI_BUILDER_ADMIN_ACTORS" to "none",
        "SERVE_UI_BUILDER_GUIDELINES_USERS" to "a,b",
        "SERVE_UI_BUILDER_GUIDELINES_PICTURE_BUDGET" to "30",
        "SERVE_CATALOG_MCP" to "1",
        "SERVE_ACCEPT_IMAGES" to "0",
      ),
      values,
    )
  }

  @Test
  fun `the entrypoint's source record is read back, and junk in it ignored`() {
    val sources =
      ServeSettings.parseSources(
        "SERVE_UI_BUILDER_GUIDELINES_MODEL=settings.json,SERVE_CATALOG_MCP=environment," +
          "SERVE_TOKEN=environment,garbage"
      )
    assertEquals(
      mapOf(
        "SERVE_UI_BUILDER_GUIDELINES_MODEL" to Source.SETTINGS,
        "SERVE_CATALOG_MCP" to Source.ENVIRONMENT,
      ),
      sources.mapKeys { it.key.env },
    )
  }

  // ---- ServeSettingsAdmin ----

  private val fs = FakeFileSystem()
  private val path = "/config/settings.json".toPath()

  /** A model + users group that records what it was given, like the guidelines check. */
  private class Recorder : ServeLiveSettings {
    override val envs =
      setOf("SERVE_UI_BUILDER_GUIDELINES_MODEL", "SERVE_UI_BUILDER_GUIDELINES_USERS")
    val applied = mutableListOf<Map<String, String?>>()

    override fun check(values: Map<String, String?>): String? =
      if (values["SERVE_UI_BUILDER_GUIDELINES_USERS"] == null) "nobody named" else null

    override fun apply(values: Map<String, String?>) {
      applied += values
    }
  }

  /** As the entrypoint leaves a box: the model and users came from settings.json. */
  private fun admin(environment: Map<String, String>, live: List<ServeLiveSettings> = emptyList()) =
    ServeSettingsAdmin(path, environment, live, fs)

  private val boxEnvironment =
    mapOf(
      "SERVE_UI_BUILDER_GUIDELINES_MODEL" to "typesafe/jev-router",
      "SERVE_UI_BUILDER_GUIDELINES_USERS" to "yschimke",
      "SERVE_CATALOG_MCP" to "1",
      ServeSettings.SOURCES_ENV to
        "SERVE_UI_BUILDER_GUIDELINES_MODEL=settings.json,SERVE_UI_BUILDER_GUIDELINES_USERS=settings.json," +
          "SERVE_CATALOG_MCP=environment",
    )

  @Test
  fun `each serving value reports where it came from`() {
    val entries = admin(boxEnvironment).entries(null).associateBy { it.key }
    assertEquals("settings.json", entries.getValue("uiBuilder.guidelines.model").source)
    assertEquals("typesafe/jev-router", entries.getValue("uiBuilder.guidelines.model").serving)
    assertEquals("environment", entries.getValue("catalogs.mcp").source)
    assertEquals("default", entries.getValue("uploads.acceptDocs").source)
    assertNull(entries.getValue("uploads.acceptDocs").serving)
  }

  @Test
  fun `a process started without the entrypoint reports its environment as the source`() {
    val entries =
      admin(mapOf("SERVE_CATALOG_MCP" to "1", "SERVE_ACCEPT_DOCS" to ""))
        .entries(null)
        .associateBy { it.key }
    assertEquals("environment", entries.getValue("catalogs.mcp").source)
    assertEquals("default", entries.getValue("uploads.acceptDocs").source)
  }

  @Test
  fun `publishing a new guidelines model applies it live, without a restart`() {
    val recorder = Recorder()
    val admin = admin(boxEnvironment, listOf(recorder))
    val result =
      admin.set(
        doc(
          """{"uiBuilder": {"guidelines": {"model": "deepseek/deepseek-v4.1-flash", "users": ["yschimke"]}}}"""
        )
      ) as ServeSettingsAdmin.Result.Ok
    assertEquals(listOf("uiBuilder.guidelines.model"), result.applied)
    assertEquals(
      listOf<Map<String, String?>>(
        mapOf(
          "SERVE_UI_BUILDER_GUIDELINES_MODEL" to "deepseek/deepseek-v4.1-flash",
          "SERVE_UI_BUILDER_GUIDELINES_USERS" to "yschimke",
        )
      ),
      recorder.applied,
    )
    val model = result.entries.single { it.key == "uiBuilder.guidelines.model" }
    assertEquals("deepseek/deepseek-v4.1-flash", model.serving)
    assertFalse(model.pending)
    // And it was written, so the next start serves it too.
    assertTrue("deepseek/deepseek-v4.1-flash" in fs.read(path) { readUtf8() })
  }

  @Test
  fun `a restart setting is stored and reported as applying at the next start`() {
    val admin = admin(boxEnvironment, listOf(Recorder()))
    val result =
      admin.set(
        doc(
          """{"uploads": {"acceptDocs": true}, "uiBuilder": {"guidelines": {"model": "typesafe/jev-router", "users": ["yschimke"]}}}"""
        )
      ) as ServeSettingsAdmin.Result.Ok
    val docs = result.entries.single { it.key == "uploads.acceptDocs" }
    assertTrue(docs.pending)
    assertNull(docs.serving)
    assertEquals("1", docs.configured)
    assertEquals(emptyList(), result.applied)
  }

  @Test
  fun `an environment override wins, is not applied over, and is reported`() {
    val recorder = Recorder()
    val environment =
      boxEnvironment +
        (ServeSettings.SOURCES_ENV to
          "SERVE_UI_BUILDER_GUIDELINES_MODEL=environment,SERVE_UI_BUILDER_GUIDELINES_USERS=settings.json")
    val admin = admin(environment, listOf(recorder))
    val result =
      admin.set(
        doc("""{"uiBuilder": {"guidelines": {"model": "other/model", "users": ["yschimke"]}}}""")
      ) as ServeSettingsAdmin.Result.Ok
    assertEquals(emptyList(), recorder.applied)
    val model = result.entries.single { it.key == "uiBuilder.guidelines.model" }
    assertTrue(model.overridden)
    assertFalse(model.pending)
    assertEquals("typesafe/jev-router", model.serving)
    assertTrue(
      admin.describe().any { "SERVE_UI_BUILDER_GUIDELINES_MODEL" in it && "overrides" in it }
    )
  }

  @Test
  fun `a live change the setting refuses is refused before anything is written`() {
    val admin = admin(boxEnvironment, listOf(Recorder()))
    val result = admin.set(doc("""{"uiBuilder": {"guidelines": {"model": "x/y"}}}"""))
    assertTrue(result is ServeSettingsAdmin.Result.Invalid, result.toString())
    assertFalse(fs.exists(path))
  }

  @Test
  fun `an invalid document is refused and the file is left alone`() {
    val admin = admin(boxEnvironment)
    val result = admin.set(doc("""{"rendering": {"compileEngin": true}}"""))
    assertTrue(result is ServeSettingsAdmin.Result.Invalid, result.toString())
    assertFalse(fs.exists(path))
  }

  @Test
  fun `clearing the file puts settings json values back to their default at next start`() {
    val admin = admin(boxEnvironment, listOf(Recorder()))
    val result = admin.clear() as ServeSettingsAdmin.Result.Ok
    assertTrue(result.entries.single { it.key == "uiBuilder.guidelines.model" }.pending)
    assertFalse(result.entries.single { it.key == "catalogs.mcp" }.pending)
  }

  @Test
  fun `the guidelines check is reconfigured live and refuses to open to everyone`() {
    val guidelines =
      ServeUiBuilderGuidelines(
        ServeUiBuilderGuidelinesConfig(apiKey = "k", allowedUsers = setOf("yschimke")),
        ServeUiBuilderGuidelineAccess(setOf("yschimke"), emptySet(), { _, _ -> false }),
        transport = { _, _ -> OpenRouterTransport.Response(500, "") },
      )
    val live = ServeGuidelinesLiveSettings(guidelines)
    live.apply(
      mapOf(
        "SERVE_UI_BUILDER_GUIDELINES_MODEL" to "deepseek/deepseek-v4.1-flash",
        "SERVE_UI_BUILDER_GUIDELINES_USERS" to "someone",
        "SERVE_UI_BUILDER_GUIDELINES_ORGS" to null,
      )
    )
    assertEquals("deepseek/deepseek-v4.1-flash", guidelines.model)
    assertEquals("users someone", guidelines.describeAccess())
    assertTrue(guidelines.triageEnabled)
    live.apply(
      mapOf(
        "SERVE_UI_BUILDER_GUIDELINES_USERS" to "someone",
        "SERVE_UI_BUILDER_GUIDELINES_TRIAGE" to "off",
      )
    )
    assertFalse(guidelines.triageEnabled)
    // Back to the default model when the setting is removed.
    live.apply(mapOf("SERVE_UI_BUILDER_GUIDELINES_USERS" to "someone"))
    assertEquals(ServeUiBuilderGuidelinesConfig.DEFAULT_MODEL, guidelines.model)
    assertTrue(live.check(mapOf("SERVE_UI_BUILDER_GUIDELINES_MODEL" to "x")) != null)
  }
}
