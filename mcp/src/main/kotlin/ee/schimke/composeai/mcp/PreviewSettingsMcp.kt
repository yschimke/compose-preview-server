package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `openai/settings` tools (issue #1242; spec "Structured Settings"): [READ_TOOL] returns a
 * `SettingsReadResult` (schema, values, layout), [UPDATE_TOOL] persists the changed keys, and the
 * layout's buttons run [DOCTOR_TOOL] (a regular tool; ChatGPT shows its text) and
 * [REGISTER_PROJECT_TOOL] (an MCP App tool; ChatGPT opens it in a modal).
 *
 * [capability] goes into `initialize`'s `capabilities.extensions` and `.experimental`. This server
 * speaks MCP up to `2025-11-25` through Kotlin SDK 0.15, which has no `server/discover`; when the
 * SDK grows one the same capability object belongs in it.
 */
class PreviewSettingsMcp(
  val store: PreviewSettingsStore,
  /** The `doctor` checks that need server state: registered projects, daemons, the catalog. */
  private val doctorReport: () -> List<DoctorCheck>,
) {
  /** One `doctor` line. */
  data class DoctorCheck(val name: String, val ok: Boolean, val detail: String)

  fun toolDefs(): List<ToolDef> =
    listOf(
      ToolDef(
        name = READ_TOOL,
        title = "Compose Preview settings",
        description =
          "Read the compose-preview defaults (device, dark theme, font scale, locale, " +
            "render_preview's result and image, daemon replicas): their schema, current values " +
            "and layout. Takes no arguments; read-only. The same values live in " +
            "~/.compose-preview/settings.json, which the CLI reads too.",
        inputSchema = OpenAiUi.EMPTY_INPUT_SCHEMA,
        outputSchema = READ_OUTPUT_SCHEMA,
        readOnlyHint = true,
      ),
      ToolDef(
        name = UPDATE_TOOL,
        title = "Update Compose Preview settings",
        description =
          "Change compose-preview defaults: pass only the changed keys under `set` (see " +
            "$READ_TOOL for the schema). An explicit render_preview argument still wins over a " +
            "setting. Returns every effective value.",
        inputSchema = UPDATE_INPUT_SCHEMA,
        outputSchema = UPDATE_OUTPUT_SCHEMA,
      ),
      ToolDef(
        name = DOCTOR_TOOL,
        title = "Run doctor",
        description =
          "Check this compose-preview MCP server's setup: Java, the Android SDK, the settings " +
            "file, and each registered project (prepared, daemons, previews). Takes no " +
            "arguments; read-only.",
        inputSchema = OpenAiUi.EMPTY_INPUT_SCHEMA,
        readOnlyHint = true,
      ),
      ToolDef(
        name = REGISTER_PROJECT_TOOL,
        title = "Register project…",
        description =
          "Open a small form to register a Gradle project by path (it calls register_project). " +
            "For the settings page; an agent should call register_project directly.",
        inputSchema = OpenAiUi.EMPTY_INPUT_SCHEMA,
        meta = PreviewLibrary.appToolMeta(),
      ),
    )

  /** Serves a settings tool; null when [name] is not one. */
  fun handle(name: String, args: JsonObject): CallToolResult? =
    when (name) {
      READ_TOOL -> read()
      UPDATE_TOOL -> update(args)
      DOCTOR_TOOL -> doctor()
      else -> null
    }

  fun readResult(): JsonObject = buildJsonObject {
    put("schema", SETTINGS_SCHEMA)
    put("values", store.read().toJson())
    put("layout", LAYOUT)
  }

  private fun read(): CallToolResult {
    val result = readResult()
    return CallToolResult(
      content = listOf(ContentBlock.Text(result["values"].toString())),
      structuredContent = result,
    )
  }

  private fun update(args: JsonObject): CallToolResult {
    val set =
      args["set"] as? JsonObject
        ?: return errorCallToolResult("$UPDATE_TOOL: 'set' must be an object of changed settings")
    if (set.isEmpty()) return errorCallToolResult("$UPDATE_TOOL: set at least one setting")
    val updated = runCatching {
      store.update(set)
    }
      .getOrElse {
        return errorCallToolResult("$UPDATE_TOOL: ${it.message}")
      }
    val values = updated.toJson()
    return CallToolResult(
      content = listOf(ContentBlock.Text(values.toString())),
      structuredContent = buildJsonObject { put("values", values) },
    )
  }

  private fun doctor(): CallToolResult {
    val checks = buildList {
      add(
        DoctorCheck(
          "java",
          true,
          "${System.getProperty("java.version")} at ${System.getProperty("java.home")}",
        )
      )
      val problem = store.problem()
      add(
        DoctorCheck(
          "settings",
          problem == null,
          if (problem != null) "${store.file}: $problem"
          else if (store.file.isFile) "${store.file}"
          else "${store.file} (not written yet; defaults apply)",
        )
      )
      addAll(doctorReport())
    }
    val failing = checks.count { !it.ok }
    val text = buildString {
      appendLine(if (failing == 0) "All checks passed." else "$failing check(s) need attention.")
      checks.forEach { appendLine("${if (it.ok) "ok  " else "FAIL"} ${it.name}: ${it.detail}") }
    }
    return CallToolResult(
      content = listOf(ContentBlock.Text(text.trimEnd())),
      structuredContent =
        buildJsonObject {
          put("ok", failing == 0)
          putJsonArray("checks") {
            checks.forEach {
              addJsonObject {
                put("name", it.name)
                put("ok", it.ok)
                put("detail", it.detail)
              }
            }
          }
        },
    )
  }

  companion object {
    const val CAPABILITY_KEY: String = "openai/settings"
    const val READ_TOOL: String = "settings_read"
    const val UPDATE_TOOL: String = "settings_update"
    const val DOCTOR_TOOL: String = "doctor"
    const val REGISTER_PROJECT_TOOL: String = "settings_register_project"

    val TOOL_NAMES: Set<String> = setOf(READ_TOOL, UPDATE_TOOL, DOCTOR_TOOL, REGISTER_PROJECT_TOOL)

    /** `SettingsCapability`: `{ readTool, updateTool }`. */
    val capability: Map<String, JsonObject> =
      mapOf(
        CAPABILITY_KEY to
          buildJsonObject {
            put("readTool", READ_TOOL)
            put("updateTool", UPDATE_TOOL)
          }
      )

    /** The `SettingsReadResult.schema`: primitives only, a title each, no `default`s. */
    val SETTINGS_SCHEMA: JsonObject = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject(PreviewSettings.DEVICE) {
          put("type", "string")
          put("title", "Default device")
          put(
            "description",
            "Device every render uses unless a call names one. 'preview' keeps each preview's own.",
          )
          putJsonArray("enum") { PreviewSettings.DEVICE_VALUES.forEach { add(it) } }
        }
        putJsonObject(PreviewSettings.DARK_THEME) {
          put("type", "boolean")
          put("title", "Dark theme")
          put("description", "Render in dark mode. Off keeps each preview's own mode.")
        }
        putJsonObject(PreviewSettings.FONT_SCALE) {
          put("type", "number")
          put("title", "Font scale")
          put("description", "Font scale multiplier, such as 1.3. 0 keeps each preview's own.")
          put("minimum", 0)
          put("maximum", PreviewSettings.MAX_FONT_SCALE)
        }
        putJsonObject(PreviewSettings.LOCALE) {
          put("type", "string")
          put("title", "Locale")
          put("description", "BCP-47 tag such as 'fr' or 'ja-JP'. Empty keeps each preview's own.")
          put("maxLength", 35)
          put("pattern", PreviewSettings.LOCALE_PATTERN)
        }
        putJsonObject(PreviewSettings.RENDER_RESULT) {
          put("type", "string")
          put("title", "render_preview result")
          put(
            "description",
            "'inline' returns the observation, 'file' the PNG's path. 'auto' lets the client " +
              "decide: agent harnesses that read files get the path.",
          )
          putJsonArray("enum") { PreviewSettings.RESULT_VALUES.forEach { add(it) } }
        }
        putJsonObject(PreviewSettings.IMAGE_TO_MODEL) {
          put("type", "boolean")
          put("title", "Send the image to the model")
          put(
            "description",
            "Off: an inline render gives the model the semantics tree instead of pixels; the " +
              "viewer and the preview resource keep the image.",
          )
        }
        putJsonObject(PreviewSettings.REPLICAS_PER_DAEMON) {
          put("type", "integer")
          put("title", "Replicas per daemon")
          put(
            "description",
            "Extra render sandboxes per module daemon. -1 picks from the CPU count. Takes effect " +
              "when the server restarts; --replicas-per-daemon still wins.",
          )
          put("minimum", -1)
          put("maximum", PreviewSettings.MAX_REPLICAS)
        }
      }
      putJsonArray("required") { PreviewSettings().toJson().keys.forEach { add(it) } }
    }

    val LAYOUT: JsonArray =
      kotlinx.serialization.json.buildJsonArray {
        fun group(title: String, vararg items: JsonObject) = addJsonObject {
          put("kind", "group")
          put("title", title)
          put("items", JsonArray(items.toList()))
        }
        fun property(key: String) = buildJsonObject {
          put("kind", "property")
          put("property", key)
        }
        fun tool(name: String, title: String, description: String) = buildJsonObject {
          put("kind", "tool")
          put("tool", name)
          put("title", title)
          put("description", description)
        }
        group(
          "Rendering",
          property(PreviewSettings.DEVICE),
          property(PreviewSettings.DARK_THEME),
          property(PreviewSettings.FONT_SCALE),
          property(PreviewSettings.LOCALE),
        )
        group(
          "Results",
          property(PreviewSettings.RENDER_RESULT),
          property(PreviewSettings.IMAGE_TO_MODEL),
        )
        group(
          "Daemon",
          property(PreviewSettings.REPLICAS_PER_DAEMON),
          tool(DOCTOR_TOOL, "Run doctor", "Check Java, the Android SDK and each project."),
          tool(REGISTER_PROJECT_TOOL, "Register project…", "Add a Gradle project by path."),
        )
      }

    /** The values schema: [SETTINGS_SCHEMA] with every property required. */
    private val VALUES_SCHEMA: JsonObject = SETTINGS_SCHEMA

    /** `outputSchema` of [READ_TOOL]: `SettingsReadResult`. */
    val READ_OUTPUT_SCHEMA: JsonObject = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("schema") { put("type", "object") }
        put("values", VALUES_SCHEMA)
        putJsonObject("layout") {
          put("type", "array")
          putJsonObject("items") { put("type", "object") }
        }
      }
      putJsonArray("required") {
        add("schema")
        add("values")
      }
    }

    val UPDATE_INPUT_SCHEMA: JsonObject = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") {
        putJsonObject("set") {
          put("type", "object")
          put("properties", SETTINGS_SCHEMA["properties"]!!)
          put("minProperties", 1)
          put("additionalProperties", false)
        }
      }
      putJsonArray("required") { add("set") }
      put("additionalProperties", false)
    }

    val UPDATE_OUTPUT_SCHEMA: JsonObject = buildJsonObject {
      put("type", "object")
      putJsonObject("properties") { put("values", VALUES_SCHEMA) }
      putJsonArray("required") { add("values") }
    }
  }
}

/**
 * The `doctor` checks about the Android SDK and each registered project. [previewCount] is the
 * number of discovered previews in one project.
 */
internal fun projectDoctorChecks(
  projects: List<RegisteredProject>,
  environment: Map<String, String>,
  previewCount: (RegisteredProject) -> Int,
): List<PreviewSettingsMcp.DoctorCheck> = buildList {
  val sdkVar =
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").firstOrNull { !environment[it].isNullOrBlank() }
  if (sdkVar != null) {
    val dir = java.io.File(environment.getValue(sdkVar))
    add(
      PreviewSettingsMcp.DoctorCheck(
        "android-sdk",
        dir.isDirectory,
        "$sdkVar=${dir.path}" + if (dir.isDirectory) "" else " is not a directory",
      )
    )
  } else {
    val found = projects.firstNotNullOfOrNull { AndroidSdks(environment).forBuild(it.path) }
    add(
      PreviewSettingsMcp.DoctorCheck(
        "android-sdk",
        true,
        found?.let { "no ANDROID_HOME; a recompile will use $it" }
          ?: "no ANDROID_HOME; each build's local.properties sdk.dir applies (needed only for Android builds)",
      )
    )
  }
  if (projects.isEmpty()) {
    add(
      PreviewSettingsMcp.DoctorCheck(
        "projects",
        false,
        "none registered; call register_project with a Gradle project path",
      )
    )
  }
  projects.forEach { project ->
    val name = "project ${project.rootProjectName}"
    if (!project.path.isDirectory) {
      add(
        PreviewSettingsMcp.DoctorCheck(
          name,
          false,
          "${project.path} no longer exists; unregister_project it",
        )
      )
      return@forEach
    }
    val prepared = runCatching {
      DescriptorProvider.indexDescriptorsByModulePath(project.path).size
    }
      .getOrDefault(0)
    add(
      PreviewSettingsMcp.DoctorCheck(
        name,
        true,
        "${project.path}: " +
          (if (prepared == 0) "not prepared yet (the first render runs the Gradle bootstrap)"
          else "$prepared module(s) prepared") +
          ", ${project.daemons.size} daemon(s), ${previewCount(project)} preview(s)",
      )
    )
  }
}
