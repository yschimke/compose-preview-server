package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The `android-dwf` native surface: a Wear OS Watch Face Format design drawn by Google's real
 * `DeclarativeWatchFaceRuntime.apk` instead of by a Compose compile. Design:
 * `docs/design/UI_BUILDER_WATCH_FACE_ANDROID_PREVIEW.md`.
 *
 * Everything in this file is pure (no ktor, no process, no IO), so the part that must not drift —
 * which control becomes which daemon command — is unit-tested against a recording fake.
 */
internal object DwfPreviewSurface {
  /**
   * The value of a builder catalog's `statusSemantics.previewSurfaces.native.backend` that selects
   * this surface. A string here rather than a `UiBuilderPreviewSurfaces` constant because the
   * released builder library has no constant for it yet; [UiBuilderNativeTargets] is the one place
   * that compares against it.
   */
  const val BACKEND = "android-dwf"

  /** The refusal code of `native-preview` for a design whose real preview is this surface. */
  const val SEPARATE_SURFACE = "NATIVE_BACKEND_DWF"

  /** The runtime APK is not installed on this host. Host configuration, not a design problem. */
  const val RUNTIME_MISSING = "DWF_RUNTIME_MISSING"

  /** Packaging could not run on this host (a tool failed to start, the disk). Not the design. */
  const val PACKAGING_UNAVAILABLE = "DWF_PACKAGING_UNAVAILABLE"

  /** The packager refused this face; the reason carries its diagnostics. A design problem. */
  const val PACKAGING_FAILED = "DWF_PACKAGING_FAILED"

  /** No usable Android SDK (build-tools aapt2/zipalign, a platform android.jar) on this host. */
  const val ANDROID_SDK_MISSING = "DWF_ANDROID_SDK_MISSING"

  /** The simulator's own prerequisites are missing (its sidecar jars, an android.jar). */
  const val SIMULATOR_UNAVAILABLE = "DWF_SIMULATOR_UNAVAILABLE"

  const val SOURCE_INVALID = "DWF_SOURCE_INVALID"
  const val SOURCE_UNAVAILABLE = "DWF_SOURCE_UNAVAILABLE"
  const val TOO_MANY_SESSIONS = "DWF_TOO_MANY_SESSIONS"
  const val DAEMON_FAILED = "DWF_DAEMON_FAILED"

  /** WFF versions a design may target (the design document's own `1..4`, default 2). */
  val WFF_VERSIONS: IntRange = 1..4
  const val DEFAULT_WFF_VERSION = 2

  /** The daemon's complication types, as `DwfApkDaemonBridge` sends them. */
  val COMPLICATION_TYPES: Set<String> =
    setOf(
      "EMPTY",
      "SHORT_TEXT",
      "LONG_TEXT",
      "RANGED_VALUE",
      "MONOCHROMATIC_IMAGE",
      "SMALL_IMAGE",
      "PHOTO_IMAGE",
      "GOAL_PROGRESS",
      "WEIGHTED_ELEMENTS",
    )

  /** Highest complication slot id accepted; WFF declares a handful, the runtime never > 7. */
  const val MAX_SLOT_ID = 15
}

/**
 * One revision's watch face as the runtime needs it: the `watchface.xml`, its target WFF version,
 * and the files its `res/` carries. [wffXml] is emitted by the editor (or, once the exporter lands,
 * by the server from the design document); [assets] are keyed by the path inside `res/`.
 *
 * Both are caller-influenced text and bytes fed to `aapt2` and a Robolectric sandbox, so [problem]
 * is the gate and is checked before anything is packaged.
 */
internal class DwfSource(
  val wffXml: String,
  val wffVersion: Int = DwfPreviewSurface.DEFAULT_WFF_VERSION,
  val assets: Map<String, ByteArray> = emptyMap(),
  val label: String = "watch face",
) {
  /** Why this source must not be packaged, or null when it may be. */
  fun problem(): String? {
    if (wffVersion !in DwfPreviewSurface.WFF_VERSIONS) {
      return "WFF version $wffVersion is not in ${DwfPreviewSurface.WFF_VERSIONS}"
    }
    if (wffXml.isBlank()) return "the WFF XML is empty"
    if (wffXml.length > MAX_XML_CHARS) return "the WFF XML is larger than $MAX_XML_CHARS characters"
    if (!wffXml.trimStart().startsWith("<")) return "the WFF XML does not start with an element"
    // A document type declaration is how an XML parser is asked to fetch or expand something. WFF
    // has no use for one, so it is refused rather than escaped.
    if (DOCTYPE.containsMatchIn(wffXml)) return "the WFF XML must not declare a DOCTYPE or ENTITY"
    if (assets.size > MAX_ASSETS) return "more than $MAX_ASSETS assets"
    var total = 0L
    for ((path, bytes) in assets) {
      if (!SAFE_ASSET_PATH.matches(path) || path.split('/').any { it == ".." || it.isEmpty() }) {
        return "asset path `$path` is not a plain relative path under res/"
      }
      if (normalizedAssetPath(path) in RESERVED_PATHS) {
        return "asset path `$path` would replace a file this server writes itself " +
          "(the watch face, its info, or the manifest)"
      }
      total += bytes.size
      if (total > MAX_ASSET_BYTES) return "assets are larger than $MAX_ASSET_BYTES bytes in all"
    }
    return null
  }

  /**
   * The identity of this exact package: a re-push of an unchanged revision is a cache hit on the
   * packaged APK, and two sessions on the same revision share one.
   */
  fun digest(): String {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(wffVersion.toString().toByteArray())
    md.update(0)
    md.update(label.toByteArray())
    md.update(0)
    md.update(wffXml.toByteArray())
    for (path in assets.keys.sorted()) {
      md.update(0)
      md.update(path.toByteArray())
      md.update(0)
      md.update(assets.getValue(path))
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }

  companion object {
    /**
     * Bundle paths this server writes from validated input. An asset that resolved to one would
     * replace the document [problem] checked with one it did not (the DOCTYPE and size gates), so
     * those names are refused after the same normalisation a filesystem applies: case, `.`
     * segments, and repeated or leading separators.
     */
    private val RESERVED_PATHS =
      setOf("raw/watchface.xml", "xml/watch_face_info.xml", "androidmanifest.xml")

    internal fun normalizedAssetPath(path: String): String =
      path.lowercase().split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    const val MAX_XML_CHARS = 2_000_000
    const val MAX_ASSETS = 256
    const val MAX_ASSET_BYTES = 16L * 1024 * 1024
    private val DOCTYPE = Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE)
    private val SAFE_ASSET_PATH = Regex("[A-Za-z0-9._][A-Za-z0-9._/-]*")
  }
}

/** One complication slot's data, in the daemon's vocabulary. */
internal data class DwfComplication(
  val type: String,
  val text: String? = null,
  val title: String? = null,
  val value: Double? = null,
  val min: Double? = null,
  val max: Double? = null,
  val monochromaticImage: String? = null,
  val smallImage: String? = null,
  val photoImage: String? = null,
  val colors: List<String> = emptyList(),
  val weights: List<Double> = emptyList(),
)

/**
 * Everything the tab can set on the simulated watch. A value object: a session keeps the last one,
 * and [DwfDaemonCommands.diff] turns a change of it into daemon commands — which is also how a
 * session that lost the shared daemon to another one is brought back ([DwfDaemonCommands.full]).
 */
internal data class DwfControls(
  /** The watch's wall clock in epoch millis; null leaves the simulator's own clock. */
  val timeMillis: Long? = null,
  /**
   * Whether the clock advances in real time from [timeMillis]. A tab-side behaviour (it sends a
   * fresh `clock` command each second and asks for a frame); the daemon has no ticking mode.
   */
  val ticking: Boolean = false,
  val ambient: Boolean = false,
  val burnInProtection: Boolean? = null,
  val lowBitAmbient: Boolean? = null,
  val heartRate: Double? = null,
  val steps: Int? = null,
  val batteryPercent: Int? = null,
  val flavorId: String? = null,
  /** User-configuration option id → chosen value id (or the option's own value text). */
  val config: Map<String, String> = emptyMap(),
  val complications: Map<Int, DwfComplication> = emptyMap(),
) {
  /** A parsed patch: the new controls, or why the patch was refused (nothing is applied). */
  sealed interface Patched {
    data class Ok(val controls: DwfControls) : Patched

    data class Invalid(val reason: String) : Patched
  }

  /**
   * Apply a client's partial update. Absent keys keep their value; JSON `null` clears one. The
   * whole patch is refused if any key is bad, so a half-applied control never reaches the daemon.
   *
   * ```
   * {"timeMillis": 1700000000000, "ticking": true, "ambient": false,
   *  "heartRate": 72, "steps": 4200, "batteryPercent": 80,
   *  "flavor": "dark", "config": {"theme": "blue"},
   *  "complications": {"0": {"type": "SHORT_TEXT", "text": "72", "title": "HR"}, "1": null}}
   * ```
   */
  fun patch(patch: JsonObject): Patched {
    var next = this
    for ((key, value) in patch) {
      next =
        when (key) {
          "timeMillis" ->
            next.copy(
              timeMillis =
                read(value) { it.longOrNull }
                  .valueOr {
                    return bad(key)
                  },
            )
          "ticking" -> next.copy(ticking = bool(value) ?: return bad(key))
          "ambient" -> next.copy(ambient = bool(value) ?: return bad(key))
          "burnInProtection" ->
            next.copy(
              burnInProtection =
                read(value) { it.booleanOrNull }
                  .valueOr {
                    return bad(key)
                  },
            )
          "lowBitAmbient" ->
            next.copy(
              lowBitAmbient =
                read(value) { it.booleanOrNull }
                  .valueOr {
                    return bad(key)
                  },
            )
          "heartRate" -> {
            val v =
              read(value) { it.doubleOrNull }
                .valueOr {
                  return bad(key)
                }
            if (v != null && v !in 0.0..HEART_RATE_MAX) return bad(key, "0..300")
            next.copy(heartRate = v)
          }
          "steps" -> {
            val v =
              read(value) { it.longOrNull }
                .valueOr {
                  return bad(key)
                }
            if (v != null && v !in 0L..STEPS_MAX) return bad(key, "0..$STEPS_MAX")
            next.copy(steps = v?.toInt())
          }
          "batteryPercent" -> {
            val v =
              read(value) { it.longOrNull }
                .valueOr {
                  return bad(key)
                }
            if (v != null && v !in 0L..100L) return bad(key, "0..100")
            next.copy(batteryPercent = v?.toInt())
          }
          "flavor" ->
            next.copy(
              flavorId =
                read(value) { it.contentOrNull }
                  .valueOr {
                    return bad(key)
                  }
                  ?.ifBlank { null },
            )
          "config" ->
            next.copy(config = parseConfig(value) ?: return bad(key, "an object of strings"))
          "complications" ->
            next.copy(
              complications =
                parseComplications(complications, value)
                  ?: return bad(key, "slot id → complication object or null"),
            )
          else -> return Patched.Invalid("unknown control `$key`")
        }
    }
    return Patched.Ok(next)
  }

  private fun bad(key: String, expected: String? = null): Patched.Invalid =
    Patched.Invalid("control `$key` is not valid" + (expected?.let { " (expected $it)" } ?: ""))

  /** A JSON scalar read: a value (null for JSON `null`, which clears), or the wrong type. */
  private sealed interface Read<out T> {
    data class Value<T>(val value: T?) : Read<T>

    data object Wrong : Read<Nothing>
  }

  private inline fun <T> Read<T>.valueOr(wrong: () -> Nothing): T? =
    when (this) {
      is Read.Value -> value
      Read.Wrong -> wrong()
    }

  private fun <T> read(element: JsonElement, convert: (JsonPrimitive) -> T?): Read<T> {
    if (element is JsonNull) return Read.Value(null)
    val primitive = element as? JsonPrimitive ?: return Read.Wrong
    val converted = convert(primitive) ?: return Read.Wrong
    return Read.Value(converted)
  }

  private fun bool(element: JsonElement): Boolean? = (element as? JsonPrimitive)?.booleanOrNull

  private fun parseConfig(element: JsonElement): Map<String, String>? {
    if (element is JsonNull) return emptyMap()
    val obj = element as? JsonObject ?: return null
    if (obj.size > MAX_CONFIG_ENTRIES) return null
    return obj.entries.associate { (k, v) ->
      val s = (v as? JsonPrimitive)?.contentOrNull ?: return null
      if (k.isBlank() || k.length > MAX_TEXT || s.length > MAX_TEXT) return null
      k to s
    }
  }

  private fun parseComplications(
    current: Map<Int, DwfComplication>,
    element: JsonElement,
  ): Map<Int, DwfComplication>? {
    if (element is JsonNull) return emptyMap()
    val obj = element as? JsonObject ?: return null
    val next = LinkedHashMap(current)
    for ((slotKey, slotValue) in obj) {
      val slot =
        slotKey.toIntOrNull()?.takeIf { it in 0..DwfPreviewSurface.MAX_SLOT_ID } ?: return null
      if (slotValue is JsonNull) {
        next.remove(slot)
        continue
      }
      next[slot] = parseComplication(slotValue as? JsonObject ?: return null) ?: return null
    }
    return next
  }

  private fun parseComplication(obj: JsonObject): DwfComplication? {
    fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.contentOrNull
    fun num(key: String): Double? = (obj[key] as? JsonPrimitive)?.doubleOrNull
    val type = str("type")?.trim()?.uppercase(Locale.ROOT) ?: "SHORT_TEXT"
    if (type !in DwfPreviewSurface.COMPLICATION_TYPES) return null
    // Only an inline `data:image/` payload: `file:` would let a client name a file on the host and
    // `http(s):` would make the daemon fetch for the client. The daemon accepts both; this server
    // does not pass them on.
    fun image(key: String): String? =
      str(key)?.takeIf { it.startsWith("data:image/", ignoreCase = true) && it.length <= MAX_IMAGE }
    val texts = listOf(str("text"), str("title"))
    if (texts.any { it != null && it.length > MAX_TEXT }) return null
    val colors =
      (obj["colors"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
    val weights =
      (obj["weights"] as? JsonArray)?.map { (it as? JsonPrimitive)?.doubleOrNull ?: return null }
    if ((colors?.size ?: 0) > MAX_ELEMENTS || (weights?.size ?: 0) > MAX_ELEMENTS) return null
    return DwfComplication(
      type = type,
      text = str("text"),
      title = str("title"),
      value = num("value"),
      min = num("min"),
      max = num("max"),
      monochromaticImage = image("monochromaticImage"),
      smallImage = image("smallImage"),
      photoImage = image("photoImage"),
      colors = colors.orEmpty(),
      weights = weights.orEmpty(),
    )
  }

  private companion object {
    const val HEART_RATE_MAX = 300.0
    const val STEPS_MAX = 1_000_000L
    const val MAX_CONFIG_ENTRIES = 64
    const val MAX_TEXT = 256
    const val MAX_ELEMENTS = 16
    const val MAX_IMAGE = 256 * 1024
  }
}

/** One daemon command: the `cmd` name and its payload, before the transport adds an `id`. */
internal data class DwfCommand(val cmd: String, val payload: JsonObject = JsonObject(emptyMap()))

/**
 * The control → daemon command mapping. The vocabulary is the one `DwfApkDaemonBridge` speaks over
 * the `:simulator-host` `LaunchMain` stdio channel (`watchface {op}`, `ambient`, `clock`, `health`,
 * `battery`, `screenshot`); it is restated here, deliberately small and tested, because the bridge
 * exposes it only through an `internal` builder keyed on a bundle's `ServePreview`. When
 * compose-ai-tools exports that builder this object shrinks to a call (see the design doc, "What
 * compose-ai-tools needs to export").
 */
internal object DwfDaemonCommands {

  /** Every command that brings a freshly (re)pushed face to [controls]. Order is significant. */
  fun full(controls: DwfControls): List<DwfCommand> = diff(DwfControls(), controls, force = true)

  /**
   * The commands that take a daemon showing [before] to [after]. Nothing when they are equal; with
   * [force] each group is emitted regardless (the face was just pushed, so the daemon's state is
   * the simulator's defaults, not [before]).
   */
  fun diff(before: DwfControls, after: DwfControls, force: Boolean = false): List<DwfCommand> {
    val out = ArrayList<DwfCommand>()
    if (
      force ||
        before.ambient != after.ambient ||
        before.burnInProtection != after.burnInProtection ||
        before.lowBitAmbient != after.lowBitAmbient
    ) {
      out +=
        DwfCommand(
          "ambient",
          buildJsonObject {
            put("state", if (after.ambient) "AMBIENT" else "INTERACTIVE")
            after.burnInProtection?.let { put("burnIn", it) }
            after.lowBitAmbient?.let { put("lowBit", it) }
          },
        )
    }
    val time = after.timeMillis
    if (time != null && (force || before.timeMillis != time)) {
      out += DwfCommand("clock", buildJsonObject { put("timeMillis", time) })
    }
    if (
      (after.heartRate != null || after.steps != null) &&
        (force || before.heartRate != after.heartRate || before.steps != after.steps)
    ) {
      out +=
        DwfCommand(
          "health",
          buildJsonObject {
            after.heartRate?.let { put("heartRate", it) }
            after.steps?.let { put("steps", it) }
          },
        )
    }
    val battery = after.batteryPercent
    if (battery != null && (force || before.batteryPercent != battery)) {
      out += DwfCommand("battery", buildJsonObject { put("level", battery) })
    }
    // Slots first cleared (a slot that had data and no longer does), then configuration, then the
    // slots with data — the bridge's order, so a flavor switch cannot repaint over fresh data.
    for (slot in before.complications.keys - after.complications.keys) {
      out += uncomplication(slot)
    }
    if (force || before.flavorId != after.flavorId || before.config != after.config) {
      out +=
        DwfCommand(
          "watchface",
          buildJsonObject {
            put("op", "config")
            // The daemon's config is absolute, not incremental: a reset clears whatever an earlier
            // flavor or option left, then the current choice is applied.
            put("reset", true)
            after.flavorId?.let { put("flavor", it) }
            if (after.config.isNotEmpty()) {
              put("values", buildJsonObject { after.config.forEach { (k, v) -> put(k, v) } })
            }
          },
        )
    }
    for ((slot, data) in after.complications) {
      if (force || before.complications[slot] != data) out += complication(slot, data)
    }
    return out
  }

  /**
   * Whether going from [before] to [after] clears a value the daemon was given (clock, health,
   * battery). The daemon has no "restore the default" command for those, so the only way back to
   * the simulator's own value is a fresh daemon; the session restarts it and replays [full].
   */
  fun clearsDaemonState(before: DwfControls, after: DwfControls): Boolean =
    (before.timeMillis != null && after.timeMillis == null) ||
      (before.heartRate != null && after.heartRate == null) ||
      (before.steps != null && after.steps == null) ||
      (before.batteryPercent != null && after.batteryPercent == null)

  fun screenshot(path: String): DwfCommand =
    DwfCommand(
      "screenshot",
      buildJsonObject {
        put("path", path)
        put("square", false)
      },
    )

  fun status(): DwfCommand = DwfCommand("watchface", buildJsonObject { put("op", "status") })

  fun push(apkPath: String): DwfCommand =
    DwfCommand(
      "watchface",
      buildJsonObject {
        put("op", "push")
        put("apk", apkPath)
        put("activate", true)
      },
    )

  private fun uncomplication(slot: Int) =
    DwfCommand(
      "watchface",
      buildJsonObject {
        put("op", "uncomplication")
        put("slot", slot)
      },
    )

  private fun complication(slot: Int, data: DwfComplication): DwfCommand {
    if (data.type == "EMPTY") return uncomplication(slot)
    return DwfCommand(
      "watchface",
      buildJsonObject {
        put("op", "complication")
        put("slot", slot)
        put("type", data.type)
        data.text?.let { put("text", it) }
        data.title?.let { put("title", it) }
        data.value?.let { put("value", it) }
        data.min?.let { put("min", it) }
        data.max?.let { put("max", it) }
        data.monochromaticImage?.let { put("monochromaticImage", it) }
        data.smallImage?.let { put("smallImage", it) }
        data.photoImage?.let { put("photoImage", it) }
        if (data.colors.isNotEmpty()) {
          put("colors", buildJsonArray { data.colors.forEach { add(JsonPrimitive(it)) } })
        }
        if (data.weights.isNotEmpty()) {
          put("weights", buildJsonArray { data.weights.forEach { add(JsonPrimitive(it)) } })
        }
      },
    )
  }
}

/**
 * The WFF a design revision exports, as a [DwfSource], so the watch-face tab can start a session
 * with an empty body. Null when the design does not export to Watch Face Format (the exporter's
 * reasons are the code pane's). Bitmap assets are not carried: a face that names `res/` drawables
 * needs the editor to send them in the start request.
 */
internal fun deriveDwfSource(
  document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1,
): DwfSource? =
  when (val result = ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.export(document)) {
    is ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.Result.Emitted ->
      DwfSource(wffXml = result.xml, wffVersion = result.wffVersion, label = document.id)
    is ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter.Result.Refused -> null
  }
