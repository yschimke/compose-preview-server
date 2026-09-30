package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Structured settings (#1242): the read result's shape, the store, and the precedence order. */
class PreviewSettingsTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun store(file: File = File(tmp.root, "settings.json")) = PreviewSettingsStore(file) {}

  private fun mcp(store: PreviewSettingsStore = store()) = PreviewSettingsMcp(store) { emptyList() }

  @Test
  fun `read result validates against its own outputSchema`() {
    val tools = mcp().toolDefs().associateBy { it.name }
    val read = tools.getValue(PreviewSettingsMcp.READ_TOOL)
    assertThat(read.readOnlyHint).isTrue()
    val result = mcp().handle(PreviewSettingsMcp.READ_TOOL, JsonObject(emptyMap()))!!
    val structured = result.structuredContent!!
    assertThat(validate(read.outputSchema!!, structured)).isEmpty()

    // And the SettingsReadResult rules the schema alone cannot say.
    val schema = structured["schema"]!!.jsonObject
    assertThat(schema["type"]!!.jsonPrimitive.content).isEqualTo("object")
    val properties = schema["properties"]!!.jsonObject
    val values = structured["values"]!!.jsonObject
    assertThat(values.keys).containsExactlyElementsIn(properties.keys)
    for ((key, property) in properties) {
      val p = property.jsonObject
      assertThat(p["type"]!!.jsonPrimitive.content)
        .isIn(setOf("boolean", "string", "number", "integer"))
      assertThat(p["title"]!!.jsonPrimitive.content).isNotEmpty()
      assertThat(p).doesNotContainKey("default")
      if ("enum" in p) assertThat(p["type"]!!.jsonPrimitive.content).isEqualTo("string")
      assertThat(validate(p, values.getValue(key))).isEmpty()
    }
    val layout = structured["layout"]!!.jsonArray
    val referenced = mutableListOf<String>()
    val toolNames = tools.keys
    for (group in layout) {
      val g = group.jsonObject
      assertThat(g["kind"]!!.jsonPrimitive.content).isEqualTo("group")
      assertThat(g["title"]!!.jsonPrimitive.content).isNotEmpty()
      for (item in g["items"]!!.jsonArray) {
        val i = item.jsonObject
        when (i["kind"]!!.jsonPrimitive.content) {
          "property" -> referenced += i["property"]!!.jsonPrimitive.content
          "tool" -> {
            assertThat(i["tool"]!!.jsonPrimitive.content).isIn(toolNames)
            assertThat(i["title"]!!.jsonPrimitive.content).isNotEmpty()
          }
          else -> error("unexpected layout item $i")
        }
      }
    }
    assertThat(referenced).containsNoDuplicates()
    assertThat(referenced).containsExactlyElementsIn(properties.keys)
    // Layout buttons call tools that accept {}.
    for (name in listOf(PreviewSettingsMcp.DOCTOR_TOOL, PreviewSettingsMcp.REGISTER_PROJECT_TOOL)) {
      assertThat(tools.getValue(name).inputSchema.jsonObject["required"]).isNull()
    }
  }

  @Test
  fun `update round-trips through the file and keeps other keys`() {
    val file = File(tmp.root, "nested/settings.json")
    val first = mcp(store(file))
    val set = buildJsonObject {
      putJsonObject("set") {
        put(PreviewSettings.DARK_THEME, true)
        put(PreviewSettings.FONT_SCALE, 1.5)
        put(PreviewSettings.RENDER_RESULT, "file")
      }
    }
    val updated = first.handle(PreviewSettingsMcp.UPDATE_TOOL, set)!!
    assertThat(updated.isError).isNotEqualTo(true)
    val tools = first.toolDefs().associateBy { it.name }
    assertThat(
        validate(
          tools.getValue(PreviewSettingsMcp.UPDATE_TOOL).outputSchema!!,
          updated.structuredContent!!,
        )
      )
      .isEmpty()
    val values = updated.structuredContent!!["values"]!!.jsonObject
    assertThat(values[PreviewSettings.DARK_THEME]).isEqualTo(JsonPrimitive(true))
    assertThat(values[PreviewSettings.DEVICE])
      .isEqualTo(JsonPrimitive(PreviewSettings.PREVIEW_DEVICE))

    // Only the changed keys are stored, under the documented envelope.
    val stored = Json.parseToJsonElement(file.readText()).jsonObject
    assertThat(stored["schema"]!!.jsonPrimitive.content).isEqualTo(PreviewSettingsStore.SCHEMA)
    assertThat(stored["values"]!!.jsonObject.keys)
      .containsExactly(
        PreviewSettings.DARK_THEME,
        PreviewSettings.FONT_SCALE,
        PreviewSettings.RENDER_RESULT,
      )

    // A second process (the CLI) sees it; a later update keeps the earlier keys and unknown ones.
    file.writeText(file.readText().replace("\"values\": {", "\"values\": {\n    \"futureKey\": 7,"))
    val second = store(file)
    assertThat(second.read())
      .isEqualTo(PreviewSettings(darkTheme = true, fontScale = 1.5, renderResult = "file"))
    second.update(mapOf(PreviewSettings.LOCALE to JsonPrimitive("ja-JP")))
    val again = Json.parseToJsonElement(file.readText()).jsonObject["values"]!!.jsonObject
    assertThat(again.keys)
      .containsExactly(
        "futureKey",
        PreviewSettings.DARK_THEME,
        PreviewSettings.FONT_SCALE,
        PreviewSettings.RENDER_RESULT,
        PreviewSettings.LOCALE,
      )
    val read = mcp(store(file)).handle(PreviewSettingsMcp.READ_TOOL, JsonObject(emptyMap()))!!
    assertThat(read.structuredContent!!["values"]!!.jsonObject[PreviewSettings.LOCALE])
      .isEqualTo(JsonPrimitive("ja-JP"))
  }

  @Test
  fun `invalid updates are refused before anything is written`() {
    val file = File(tmp.root, "settings.json")
    val tool = mcp(store(file))
    fun update(vararg pairs: Pair<String, JsonElement>) =
      tool.handle(
        PreviewSettingsMcp.UPDATE_TOOL,
        buildJsonObject { put("set", JsonObject(pairs.toMap())) },
      )!!
    assertThat(update(PreviewSettings.FONT_SCALE to JsonPrimitive(9)).isError).isTrue()
    assertThat(update(PreviewSettings.DEVICE to JsonPrimitive("id:no_such_device")).isError)
      .isTrue()
    assertThat(update(PreviewSettings.DARK_THEME to JsonPrimitive("true")).isError).isTrue()
    assertThat(update(PreviewSettings.LOCALE to JsonPrimitive("not a locale")).isError).isTrue()
    assertThat(update(PreviewSettings.REPLICAS_PER_DAEMON to JsonPrimitive(1.5)).isError).isTrue()
    assertThat(update("nope" to JsonPrimitive(1)).isError).isTrue()
    assertThat(update().isError).isTrue()
    // One bad key rejects the whole update.
    assertThat(
        update(
            PreviewSettings.DARK_THEME to JsonPrimitive(true),
            PreviewSettings.FONT_SCALE to JsonPrimitive(-1),
          )
          .isError
      )
      .isTrue()
    assertThat(file.exists()).isFalse()
    assertThat(update(PreviewSettings.REPLICAS_PER_DAEMON to JsonPrimitive(2)).isError)
      .isNotEqualTo(true)
    assertThat(store(file).read().replicasPerDaemon).isEqualTo(2)
  }

  @Test
  fun `a hand-edited file with one bad value keeps the rest`() {
    val file = File(tmp.root, "settings.json")
    file.writeText(
      """{"schema":"compose-preview-settings/v1","values":{"darkTheme":true,"fontScale":"big"}}"""
    )
    assertThat(store(file).read()).isEqualTo(PreviewSettings(darkTheme = true))
    file.writeText("not json")
    assertThat(store(file).read()).isEqualTo(PreviewSettings())
    assertThat(store(file).problem()).isNotNull()
  }

  // ---- precedence: explicit argument > setting > per-client default > built-in
  // -------------------

  private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  @Test
  fun `defaults leave a call untouched`() {
    val call = args("""{"uri":"compose-preview://w/_m/a.B"}""")
    assertThat(PreviewSettings().applyToRenderPreview(call, clientDefaultsToFile = true))
      .isEqualTo(call)
    assertThat(PreviewSettings().applyToRenderPreview(call, clientDefaultsToFile = false))
      .isEqualTo(call)
  }

  @Test
  fun `a setting beats the per-client default`() {
    val call = args("""{"uri":"u"}""")
    // Codex gets the file result by default; the inline setting overrides that.
    assertThat(
        PreviewSettings(renderResult = "inline")
          .applyToRenderPreview(call, clientDefaultsToFile = true)["inline"]
      )
      .isEqualTo(JsonPrimitive(true))
    // A plain client gets inline by default; the file setting overrides that.
    assertThat(
        PreviewSettings(renderResult = "file")
          .applyToRenderPreview(call, clientDefaultsToFile = false)["inline"]
      )
      .isEqualTo(JsonPrimitive(false))
    // "auto" leaves the per-client default in charge.
    assertThat(PreviewSettings(renderResult = "auto").applyToRenderPreview(call, true))
      .doesNotContainKey("inline")
    // imageToModel=false beats the MCP Apps client's observe=png default on an inline result...
    assertThat(PreviewSettings(imageToModel = false).applyToRenderPreview(call, false)["observe"])
      .isEqualTo(JsonPrimitive("semantics"))
    // ...but does not add an observe (which would cancel the file default) to a file result.
    assertThat(PreviewSettings(imageToModel = false).applyToRenderPreview(call, true))
      .doesNotContainKey("observe")
  }

  @Test
  fun `an explicit argument beats a setting`() {
    val settings =
      PreviewSettings(
        device = PreviewSettings.DEVICE_VALUES[1],
        darkTheme = true,
        fontScale = 1.3,
        locale = "fr",
        renderResult = "file",
        imageToModel = false,
      )
    val call =
      args(
        """{"uri":"u","inline":true,"observe":"png","overrides":{"device":"id:pixel_5","uiMode":"light","fontScale":2.0,"localeTag":"ar"}}"""
      )
    assertThat(settings.applyToRenderPreview(call, clientDefaultsToFile = true)).isEqualTo(call)

    // A silent call gets every setting; a partial override keeps its own keys.
    val partial =
      settings.applyToRenderPreview(args("""{"uri":"u","overrides":{"localeTag":"ar"}}"""), false)
    assertThat(partial["inline"]).isEqualTo(JsonPrimitive(false))
    assertThat(partial["overrides"])
      .isEqualTo(
        buildJsonObject {
          put("localeTag", "ar")
          put("device", PreviewSettings.DEVICE_VALUES[1])
          put("uiMode", "dark")
          put("fontScale", 1.3)
        }
      )
    // card implies the file result and crop needs the inline one: the setting stays out of both.
    assertThat(settings.applyToRenderPreview(args("""{"uri":"u","card":true}"""), false))
      .doesNotContainKey("inline")
    assertThat(settings.applyToRenderPreview(args("""{"uri":"u","crop":{"testTag":"x"}}"""), false))
      .doesNotContainKey("inline")
  }

  @Test
  fun `replicas precedence is flag, then property, then setting, then default`() {
    val setting = { PreviewSettings(replicasPerDaemon = 2) }
    val key = "composeai.mcp.replicasPerDaemon"
    val saved = System.getProperty(key)
    try {
      System.clearProperty(key)
      assertThat(
          DaemonMcpMain.parseReplicasPerDaemon(arrayOf("--replicas-per-daemon", "3"), setting)
        )
        .isEqualTo(3)
      assertThat(DaemonMcpMain.parseReplicasPerDaemon(emptyArray(), setting)).isEqualTo(2)
      System.setProperty(key, "1")
      assertThat(DaemonMcpMain.parseReplicasPerDaemon(emptyArray(), setting)).isEqualTo(1)
      System.clearProperty(key)
      assertThat(DaemonMcpMain.parseReplicasPerDaemon(emptyArray()) { PreviewSettings() })
        .isEqualTo(DaemonSupervisor.defaultReplicasFor(Runtime.getRuntime().availableProcessors()))
    } finally {
      if (saved == null) System.clearProperty(key) else System.setProperty(key, saved)
    }
  }

  /**
   * The JSON Schema subset these schemas use: type, properties, required, items, enum, minimum,
   * maximum, minLength/maxLength, pattern, additionalProperties=false. Returns the violations.
   */
  private fun validate(schema: JsonObject, value: JsonElement, at: String = "$"): List<String> {
    val problems = mutableListOf<String>()
    val prim = value as? JsonPrimitive
    when (schema["type"]?.jsonPrimitive?.contentOrNull) {
      "object" -> if (value !is JsonObject) return listOf("$at: not an object")
      "array" -> if (value !is JsonArray) return listOf("$at: not an array")
      "string" -> if (prim == null || !prim.isString) return listOf("$at: not a string")
      "boolean" ->
        if (prim == null || prim.isString || prim.booleanOrNull == null)
          return listOf("$at: not a boolean")
      "number" ->
        if (prim == null || prim.isString || prim.doubleOrNull == null)
          return listOf("$at: not a number")
      "integer" ->
        if (prim == null || prim.isString || prim.content.toLongOrNull() == null)
          return listOf("$at: not an integer")
      null -> {}
      else -> error("unsupported type in $schema")
    }
    if (value is JsonObject) {
      val properties = schema["properties"]?.jsonObject.orEmpty()
      schema["required"]?.jsonArray?.forEach {
        if (it.jsonPrimitive.content !in value)
          problems += "$at: missing ${it.jsonPrimitive.content}"
      }
      for ((key, child) in value) {
        val childSchema = properties[key]?.jsonObject
        if (childSchema != null) problems += validate(childSchema, child, "$at.$key")
        else if (schema["additionalProperties"] == JsonPrimitive(false))
          problems += "$at: unexpected $key"
      }
    }
    if (value is JsonArray) {
      schema["items"]?.jsonObject?.let { items ->
        value.forEachIndexed { i, child -> problems += validate(items, child, "$at[$i]") }
      }
    }
    if (prim != null && prim !is JsonNull) {
      schema["enum"]?.jsonArray?.let { if (prim !in it) problems += "$at: $prim not in enum" }
      prim.doubleOrNull
        ?.takeIf { !prim.isString }
        ?.let { d ->
          schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let {
            if (d < it) problems += "$at: below minimum"
          }
          schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let {
            if (d > it) problems += "$at: above maximum"
          }
        }
      if (prim.isString) {
        schema["maxLength"]?.jsonPrimitive?.content?.toInt()?.let {
          if (prim.content.length > it) problems += "$at: too long"
        }
        schema["pattern"]?.jsonPrimitive?.content?.let {
          if (!Regex(it).containsMatchIn(prim.content)) problems += "$at: pattern"
        }
      }
    }
    return problems
  }
}
