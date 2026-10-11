package ee.schimke.composeai.mcp

import ee.schimke.composeai.daemon.devices.DeviceDimensions
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Compose-preview defaults set once rather than per call. ChatGPT/Codex render them as native
 * controls (`openai/settings`); other hosts use `settings_read`/`settings_update`, and the CLI
 * reads the same file ([PreviewSettingsStore]). Every field needs a value, so "not set" is a
 * sentinel: [PREVIEW_DEVICE], `fontScale = 0`, `locale = ""`, `renderResult = "auto"`,
 * `replicasPerDaemon = -1`. For `render_preview`, an explicit argument beats a setting, which beats
 * the per-client default, then the built-in default (see [applyToRenderPreview]).
 */
data class PreviewSettings(
  /** `@Preview(device=…)` id applied as `overrides.device`, or [PREVIEW_DEVICE] for none. */
  val device: String = PREVIEW_DEVICE,
  /**
   * Force `overrides.uiMode = "dark"`; off leaves each preview's own mode (it never forces light).
   */
  val darkTheme: Boolean = false,
  /** `overrides.fontScale`; `0` leaves each preview's own scale. */
  val fontScale: Double = 0.0,
  /** `overrides.localeTag` (BCP-47); empty leaves each preview's own locale. */
  val locale: String = "",
  /**
   * `render_preview`'s result when a call passes no `inline`: `auto` (per client), `inline`,
   * `file`.
   */
  val renderResult: String = RESULT_AUTO,
  /**
   * Whether an inline `render_preview` hands the model the image. Off defaults `observe` to
   * `semantics`; the pixels stay in the preview resource.
   */
  val imageToModel: Boolean = true,
  /** Sandbox replicas per daemon; `-1` picks from the machine's cores. Read at server start. */
  val replicasPerDaemon: Int = -1,
  /**
   * How `design_open` opens the `.uid` editor: [LAYOUT_FOCUSED] (canvas and file bar) or
   * [LAYOUT_FULL]. Read per editor resource read, so changes apply to the next design opened.
   */
  val uiBuilderMcpAppLayout: String = LAYOUT_FOCUSED,
) {
  /** The effective values, keyed as in [PreviewSettingsSchema.properties]. */
  fun toJson(): JsonObject = buildJsonObject {
    put(DEVICE, device)
    put(DARK_THEME, darkTheme)
    put(FONT_SCALE, fontScale)
    put(LOCALE, locale)
    put(RENDER_RESULT, renderResult)
    put(IMAGE_TO_MODEL, imageToModel)
    put(REPLICAS_PER_DAEMON, replicasPerDaemon)
    put(UI_BUILDER_MCP_APP_LAYOUT, uiBuilderMcpAppLayout)
  }

  /**
   * [args] with these settings filled in where the call is silent; passed keys and sentinel
   * settings add nothing, so `DaemonMcpServer`'s defaults still apply. [clientDefaultsToFile]
   * decides whether the call ends up inline, which is when [imageToModel] matters.
   */
  fun applyToRenderPreview(args: JsonObject, clientDefaultsToFile: Boolean): JsonObject {
    val out = args.toMutableMap()
    fun absent(key: String) = args[key] == null || args[key] is kotlinx.serialization.json.JsonNull
    val card = (args["card"] as? JsonPrimitive)?.booleanOrNull
    // Setting > per-client default. `card` implies the file result and `crop` needs the inline
    // one, so a call that shapes the result that way keeps it.
    if (absent("inline") && card == null) {
      when (renderResult) {
        RESULT_INLINE -> out["inline"] = JsonPrimitive(true)
        RESULT_FILE -> if (absent("crop")) out["inline"] = JsonPrimitive(false)
      }
    }
    val inline =
      (out["inline"] as? JsonPrimitive)?.booleanOrNull
        ?: if (card == true) false
        else !(clientDefaultsToFile && absent("observe") && absent("crop"))
    if (!imageToModel && inline && absent("observe") && absent("crop")) {
      out["observe"] = JsonPrimitive("semantics")
    }
    val overrides = (args["overrides"] as? JsonObject).orEmpty().toMutableMap()
    val before = overrides.size
    if (device != PREVIEW_DEVICE) overrides.putIfAbsent("device", JsonPrimitive(device))
    if (darkTheme) overrides.putIfAbsent("uiMode", JsonPrimitive("dark"))
    if (fontScale > 0.0) overrides.putIfAbsent("fontScale", JsonPrimitive(fontScale))
    if (locale.isNotBlank()) overrides.putIfAbsent("localeTag", JsonPrimitive(locale))
    if (overrides.size != before) out["overrides"] = JsonObject(overrides)
    return JsonObject(out)
  }

  /**
   * Validates [set] (the `settings_update` arguments' `set`) against the schema and returns the
   * settings with it applied, or throws [IllegalArgumentException] naming every bad key.
   */
  fun updated(set: Map<String, JsonElement>): PreviewSettings {
    val problems = mutableListOf<String>()
    var next = this
    for ((key, raw) in set) {
      val value = raw as? JsonPrimitive
      fun bad(want: String) = problems.add("$key: expected $want, got $raw")
      when (key) {
        DEVICE ->
          value
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it in DEVICE_VALUES }
            ?.let { next = next.copy(device = it) }
            ?: bad("one of list_devices' ids as 'id:<id>', or '$PREVIEW_DEVICE'")
        DARK_THEME ->
          value?.takeIf { !it.isString }?.booleanOrNull?.let { next = next.copy(darkTheme = it) }
            ?: bad("a boolean")
        FONT_SCALE ->
          value
            ?.takeIf { !it.isString }
            ?.doubleOrNull
            ?.takeIf { it in 0.0..MAX_FONT_SCALE }
            ?.let { next = next.copy(fontScale = it) } ?: bad("a number from 0 to $MAX_FONT_SCALE")
        LOCALE ->
          value
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { LOCALE_REGEX.matches(it) }
            ?.let { next = next.copy(locale = it) }
            ?: bad("a BCP-47 tag such as 'fr' or 'ja-JP', or empty")
        RENDER_RESULT ->
          value
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it in RESULT_VALUES }
            ?.let { next = next.copy(renderResult = it) }
            ?: bad("one of ${RESULT_VALUES.joinToString()}")
        IMAGE_TO_MODEL ->
          value?.takeIf { !it.isString }?.booleanOrNull?.let { next = next.copy(imageToModel = it) }
            ?: bad("a boolean")
        REPLICAS_PER_DAEMON ->
          value
            ?.takeIf { !it.isString }
            ?.contentOrNull
            ?.let { text ->
              text.toIntOrNull() ?: text.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
            }
            ?.takeIf { it in -1..MAX_REPLICAS }
            ?.let { next = next.copy(replicasPerDaemon = it) }
            ?: bad("an integer from -1 to $MAX_REPLICAS")
        UI_BUILDER_MCP_APP_LAYOUT ->
          value
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it in LAYOUT_VALUES }
            ?.let { next = next.copy(uiBuilderMcpAppLayout = it) }
            ?: bad("one of ${LAYOUT_VALUES.joinToString()}")
        else -> problems.add("$key: unknown setting")
      }
    }
    require(problems.isEmpty()) { problems.joinToString("; ") }
    return next
  }

  companion object {
    const val DEVICE: String = "device"
    const val DARK_THEME: String = "darkTheme"
    const val FONT_SCALE: String = "fontScale"
    const val LOCALE: String = "locale"
    const val RENDER_RESULT: String = "renderResult"
    const val IMAGE_TO_MODEL: String = "imageToModel"
    const val REPLICAS_PER_DAEMON: String = "replicasPerDaemon"
    const val UI_BUILDER_MCP_APP_LAYOUT: String = "uiBuilderMcpAppLayout"

    /** [device] value that keeps each preview's own device. */
    const val PREVIEW_DEVICE: String = "preview"

    const val RESULT_AUTO: String = "auto"
    const val RESULT_INLINE: String = "inline"
    const val RESULT_FILE: String = "file"
    val RESULT_VALUES: List<String> = listOf(RESULT_AUTO, RESULT_INLINE, RESULT_FILE)

    /** [uiBuilderMcpAppLayout]: the canvas and a file bar; the default. */
    const val LAYOUT_FOCUSED: String = "focused"
    /** [uiBuilderMcpAppLayout]: the whole desktop editor. */
    const val LAYOUT_FULL: String = "full"
    val LAYOUT_VALUES: List<String> = listOf(LAYOUT_FOCUSED, LAYOUT_FULL)

    const val MAX_FONT_SCALE: Double = 3.0
    const val MAX_REPLICAS: Int = 8

    /** `id:<device>` for every device the daemon's catalog resolves, sorted. */
    val DEVICE_VALUES: List<String> by lazy {
      listOf(PREVIEW_DEVICE) +
        DeviceDimensions.KNOWN_DEVICE_IDS.map { if (it.startsWith("id:")) it else "id:$it" }
          .sorted()
    }

    /** BCP-47-ish: a 2–3 letter language, then optional `-`subtags; or empty. */
    const val LOCALE_PATTERN: String = "^$|^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$"
    private val LOCALE_REGEX = Regex(LOCALE_PATTERN)

    /**
     * Settings from a stored `values` object: each known key that is valid, the default for the
     * rest. A hand-edited file with one bad value keeps every other setting.
     */
    fun fromStored(values: JsonObject, warn: (String) -> Unit = {}): PreviewSettings =
      values.entries.fold(PreviewSettings()) { acc, (key, value) ->
        runCatching { acc.updated(mapOf(key to value)) }
          .onFailure { warn(it.message ?: "invalid setting $key") }
          .getOrDefault(acc)
      }
  }
}

/**
 * The settings file shared by the MCP server and the CLI: `~/.compose-preview/settings.json`, or
 * [FILE_ENV] when set.
 *
 * ```json
 * {
 *   "schema": "compose-preview-settings/v1",
 *   "values": { "darkTheme": true, "renderResult": "file" }
 * }
 * ```
 *
 * `values` holds only keys someone set (defaults fill the rest); unknown keys are kept on save.
 * Writes are atomic via a temp file; reads are cached by modification time.
 */
class PreviewSettingsStore(
  val file: File,
  private val log: (String) -> Unit = { System.err.println("compose-preview-mcp: $it") },
) {
  private val json = Json { prettyPrint = true }
  private val lock = Any()
  @Volatile private var cached: Pair<Long, PreviewSettings>? = null

  /** The effective settings; defaults when the file is missing or unreadable. */
  fun read(): PreviewSettings {
    val stamp = if (file.isFile) file.lastModified() else MISSING
    cached?.let { (at, settings) -> if (at == stamp) return settings }
    val settings =
      if (stamp == MISSING) PreviewSettings()
      else PreviewSettings.fromStored(storedValues(), warn = { log("${file.path}: $it") })
    cached = stamp to settings
    return settings
  }

  /**
   * Validates and persists [set], keeping every other stored key, and returns the effective
   * settings. Throws [IllegalArgumentException] for an invalid value, before anything is written.
   */
  fun update(set: Map<String, JsonElement>): PreviewSettings =
    synchronized(lock) {
      val next = read().updated(set)
      val stored = storedValues().toMutableMap()
      val effective = next.toJson()
      set.keys.forEach { key -> stored[key] = effective.getValue(key) }
      write(
        buildJsonObject {
          put("schema", SCHEMA)
          put("values", JsonObject(stored))
        }
      )
      cached = null
      read()
    }

  /** A problem reading the file, for `doctor`; null when it is absent or parses. */
  fun problem(): String? {
    if (!file.exists()) return null
    return runCatching {
      val root = json.parseToJsonElement(file.readText()) as? JsonObject
      requireNotNull(root) { "not a JSON object" }
      require(root["values"] == null || root["values"] is JsonObject) {
        "'values' is not an object"
      }
    }
      .exceptionOrNull()
      ?.let { it.message ?: it.toString() }
  }

  private fun storedValues(): JsonObject {
    if (!file.isFile) return JsonObject(emptyMap())
    return runCatching {
      (json.parseToJsonElement(file.readText()) as? JsonObject)?.get("values") as? JsonObject
    }
      .onFailure { log("${file.path}: unreadable settings (${it.message}); using defaults") }
      .getOrNull() ?: JsonObject(emptyMap())
  }

  private fun write(root: JsonObject) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.${ProcessHandle.current().pid()}.tmp")
    temp.writeText(json.encodeToString(JsonObject.serializer(), root) + "\n")
    try {
      Files.move(
        temp.toPath(),
        file.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
  }

  companion object {
    const val SCHEMA: String = "compose-preview-settings/v1"
    const val FILE_ENV: String = "COMPOSE_PREVIEW_SETTINGS_FILE"
    private const val MISSING = -1L

    /** [FILE_ENV], else `~/.compose-preview/settings.json`. */
    fun defaultFile(
      environment: Map<String, String> = System.getenv(),
      userHome: File = File(System.getProperty("user.home") ?: "."),
    ): File =
      environment[FILE_ENV]?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(userHome, ".compose-preview/settings.json")
  }
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
