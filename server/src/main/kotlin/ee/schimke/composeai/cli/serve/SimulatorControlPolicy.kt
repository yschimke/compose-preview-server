package ee.schimke.composeai.cli.serve

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Which `android-simulator --control stdio` commands a client of this server may send, and with
 * what arguments. The simulator's own channel is an operator's: it writes files where it is told
 * (`screenshot path`, `hierarchy path`, `snapshotData to`), reads host files (`files push`, `pick
 * files`, `update apk`), runs `shell`, and exits on `quit`. None of that may reach a browser.
 *
 * So the policy is an allowlist, not a denylist: a command is forwarded only when this file names
 * it for the caller's [Tier], and then only with the arguments named for it, each type- and
 * range-checked. Anything else in the message is dropped, never passed through. No argument a
 * command keeps is a host path. The `device` a paired session routes by is checked against the
 * session's devices and set by the server, not copied.
 *
 * Results and events go the other way through [redact], which removes host paths.
 */
object SimulatorControlPolicy {
  /** Who is driving. */
  enum class Tier(val id: String) {
    /** A person in the viewer or an embed: input, and reading what is on screen. */
    VIEWER("viewer"),

    /** The operator, or an agent with the admin token: [VIEWER] plus app and device state. */
    OPERATOR("operator"),
  }

  sealed interface Decision {
    /** The command to send, rebuilt from its allowed arguments; [device] routes it. */
    data class Allowed(val cmd: String, val device: SimulatorDevice, val command: JsonObject) :
      Decision

    data class Refused(val reason: String) : Decision
  }

  /** Argument shapes. */
  private sealed interface Arg {
    fun check(value: JsonElement): JsonElement?

    data class Num(val min: Double, val max: Double) : Arg {
      override fun check(value: JsonElement): JsonElement? {
        val d = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return null
        return if (d.isFinite() && d in min..max) JsonPrimitive(d) else null
      }
    }

    data class Whole(val min: Long, val max: Long) : Arg {
      override fun check(value: JsonElement): JsonElement? {
        val p = (value as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
        val l =
          p.longOrNull ?: p.doubleOrNull?.takeIf { it == Math.floor(it) }?.toLong() ?: return null
        return if (l in min..max) JsonPrimitive(l) else null
      }
    }

    data class Text(val maxLength: Int, val oneOf: Set<String>? = null) : Arg {
      override fun check(value: JsonElement): JsonElement? {
        val s = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        if (s.length > maxLength || s.any { it == '\u0000' }) return null
        if (oneOf != null && s !in oneOf) return null
        return JsonPrimitive(s)
      }
    }

    data object Flag : Arg {
      override fun check(value: JsonElement): JsonElement? =
        (value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull?.let(::JsonPrimitive)
    }

    data class Many(val item: Arg, val maxItems: Int) : Arg {
      override fun check(value: JsonElement): JsonElement? {
        val array = value as? JsonArray ?: return null
        if (array.size > maxItems) return null
        return JsonArray(array.map { item.check(it) ?: return null })
      }
    }

    data class OneOrMany(val item: Arg, val maxItems: Int) : Arg {
      override fun check(value: JsonElement): JsonElement? =
        if (value is JsonArray) Many(item, maxItems).check(value) else item.check(value)
    }

    data class Obj(val fields: Map<String, Arg>) : Arg {
      override fun check(value: JsonElement): JsonElement? {
        val obj = value as? JsonObject ?: return null
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) {
          val spec = fields[k] ?: continue
          if (v is JsonNull) continue
          out[k] = spec.check(v) ?: return null
        }
        return JsonObject(out)
      }
    }
  }

  private data class Spec(
    val tier: Tier,
    val args: Map<String, Arg> = emptyMap(),
    /** Only on these devices; null means any. */
    val devices: Set<SimulatorDevice>? = null,
  )

  /** A coordinate in device pixels: generous, since a tablet or rotated display is wide. */
  private val COORD = Arg.Num(-1.0, 8192.0)
  private val DURATION = Arg.Whole(0, 5_000)
  private val SELECTOR =
    mapOf(
      "text" to Arg.Text(200),
      "desc" to Arg.Text(200),
      "resourceId" to Arg.Text(200),
      "index" to Arg.Whole(0, 100),
    )

  /**
   * `key` names a person can press: navigation, editing, volume, power and a watch's buttons.
   * Letters are typed with `text`. `KEYCODE_` prefixes and lower case are accepted and normalised.
   */
  val KEYS: Set<String> =
    setOf(
      "BACK",
      "HOME",
      "ENTER",
      "DEL",
      "FORWARD_DEL",
      "TAB",
      "SPACE",
      "ESCAPE",
      "MENU",
      "DPAD_UP",
      "DPAD_DOWN",
      "DPAD_LEFT",
      "DPAD_RIGHT",
      "DPAD_CENTER",
      "MOVE_HOME",
      "MOVE_END",
      "PAGE_UP",
      "PAGE_DOWN",
      "VOLUME_UP",
      "VOLUME_DOWN",
      "VOLUME_MUTE",
      "POWER",
      "SLEEP",
      "WAKEUP",
      "STEM_PRIMARY",
      "STEM_1",
      "STEM_2",
      "STEM_3",
      "APP_SWITCH",
    )

  private val KEY = Arg.Text(32, KEYS)

  private val SPECS: Map<String, Spec> =
    mapOf(
      // Input, as a person gives it.
      "touch" to
        Spec(
          Tier.VIEWER,
          mapOf("action" to Arg.Text(4, setOf("down", "move", "up")), "x" to COORD, "y" to COORD),
        ),
      "tap" to Spec(Tier.VIEWER, mapOf("x" to COORD, "y" to COORD) + SELECTOR),
      "longPress" to
        Spec(Tier.VIEWER, mapOf("x" to COORD, "y" to COORD, "durationMs" to DURATION) + SELECTOR),
      "swipe" to
        Spec(
          Tier.VIEWER,
          mapOf(
            "x1" to COORD,
            "y1" to COORD,
            "x2" to COORD,
            "y2" to COORD,
            "durationMs" to DURATION,
            "steps" to Arg.Whole(1, 200),
            "holdMs" to DURATION,
          ),
        ),
      "key" to Spec(Tier.VIEWER, mapOf("key" to Arg.OneOrMany(KEY, 8), "longPress" to Arg.Flag)),
      "back" to Spec(Tier.VIEWER),
      "home" to Spec(Tier.VIEWER),
      "text" to Spec(Tier.VIEWER, mapOf("text" to Arg.Text(2_000))),
      "rotary" to
        Spec(
          Tier.VIEWER,
          mapOf(
            "delta" to Arg.Num(-20.0, 20.0),
            "count" to Arg.Whole(1, 40),
            "intervalMs" to Arg.Whole(0, 200),
          ),
          devices = setOf(SimulatorDevice.WATCH),
        ),
      "rotate" to
        Spec(
          Tier.VIEWER,
          mapOf("value" to Arg.Text(9, setOf("0", "90", "180", "270", "portrait", "landscape"))),
          devices = setOf(SimulatorDevice.PHONE),
        ),
      "launch" to Spec(Tier.VIEWER),
      // Reading what is on screen: nothing a person cannot already see.
      "info" to Spec(Tier.VIEWER),
      "ping" to Spec(Tier.VIEWER),
      "hierarchy" to Spec(Tier.VIEWER, mapOf("flat" to Arg.Flag)),
      "find" to Spec(Tier.VIEWER, SELECTOR),
      "activities" to Spec(Tier.VIEWER),
      "wait" to
        Spec(
          Tier.VIEWER,
          mapOf(
            "ms" to Arg.Whole(0, 3_000),
            "until" to Arg.Text(5, setOf("still")),
            "quietMs" to Arg.Whole(50, 2_000),
          ),
        ),
      "notifications" to Spec(Tier.VIEWER, mapOf("op" to Arg.Text(5, setOf("open", "close")))),
      "drawer" to
        Spec(
          Tier.VIEWER,
          mapOf("action" to Arg.Text(5, setOf("open", "close"))),
          devices = setOf(SimulatorDevice.PHONE),
        ),
      "launcher" to
        Spec(
          Tier.VIEWER,
          mapOf("index" to Arg.Whole(0, 200), "label" to Arg.Text(100)),
          devices = setOf(SimulatorDevice.WATCH),
        ),
      "tiles" to
        Spec(
          Tier.VIEWER,
          mapOf(
            "op" to Arg.Text(6, setOf("list", "show", "next", "prev", "refresh")),
            "index" to Arg.Whole(0, 50),
          ),
          devices = setOf(SimulatorDevice.WATCH),
        ),
      // App and device state: an operator's, or an agent's testing the app.
      "notification" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "key" to Arg.Text(300),
            "op" to
              Arg.Text(
                8,
                setOf("tap", "action", "reply", "dismiss", "snooze", "expand", "collapse"),
              ),
            "index" to Arg.Whole(0, 10),
            "text" to Arg.Text(2_000),
            "ms" to Arg.Whole(0, 86_400_000),
          ),
        ),
      "permissions" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "policy" to Arg.Text(5, setOf("grant", "deny", "ask")),
            "grant" to Arg.Many(Arg.Text(200), 50),
            "deny" to Arg.Many(Arg.Text(200), 50),
            "answer" to Arg.Text(5, setOf("grant", "deny")),
            "requestId" to Arg.Whole(0, Long.MAX_VALUE),
            "reset" to Arg.Flag,
          ),
        ),
      "network" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "mode" to Arg.Text(8, SimulatorAppConfig.NETWORK_MODES.toSet()),
            "wifi" to Arg.Flag,
            "data" to Arg.Flag,
            "airplane" to Arg.Flag,
            "metered" to Arg.Flag,
          ),
        ),
      "startActivity" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "intent" to
              Arg.Obj(
                mapOf(
                  "action" to Arg.Text(200),
                  "data" to Arg.Text(2_000),
                  "component" to Arg.Text(300),
                  "type" to Arg.Text(100),
                  "categories" to Arg.Many(Arg.Text(200), 10),
                ),
              ),
            "forceStop" to Arg.Flag,
            "savedState" to Arg.Text(4 * 1024 * 1024),
          ),
        ),
      "logcat" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "op" to Arg.Text(5, setOf("dump", "clear")),
            "tail" to Arg.Whole(1, 2_000),
            "default" to Arg.Text(1, setOf("V", "D", "I", "W", "E")),
            "regex" to Arg.Text(200),
          ),
        ),
      "forceStop" to Spec(Tier.OPERATOR),
      "clearData" to Spec(Tier.OPERATOR),
      "recreate" to Spec(Tier.OPERATOR),
      "kill" to Spec(Tier.OPERATOR),
      "savedState" to Spec(Tier.OPERATOR),
      "dataLayer" to
        Spec(
          Tier.OPERATOR,
          mapOf("addCapability" to Arg.Text(200), "removeCapability" to Arg.Text(200)),
        ),
      "battery" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "level" to Arg.Whole(0, 100),
            "plugged" to Arg.Text(8, setOf("ac", "usb", "wireless", "none")),
            "powerSave" to Arg.Flag,
          ),
        ),
      "location" to
        Spec(
          Tier.OPERATOR,
          mapOf(
            "lat" to Arg.Num(-90.0, 90.0),
            "lng" to Arg.Num(-180.0, 180.0),
            "accuracy" to Arg.Num(0.0, 100_000.0),
          ),
        ),
      "timezone" to Spec(Tier.OPERATOR, mapOf("value" to Arg.Text(64))),
      "fontScale" to Spec(Tier.OPERATOR, mapOf("value" to Arg.Num(0.5, 3.0))),
      "qualifiers" to Spec(Tier.OPERATOR, mapOf("value" to Arg.Text(64))),
      "clipboard" to Spec(Tier.OPERATOR, mapOf("text" to Arg.Text(10_000), "clear" to Arg.Flag)),
    )

  /** Command names [tier] may send, for the lane message and the API's discovery. */
  fun commands(tier: Tier): List<String> =
    SPECS.filterValues { allows(tier, it.tier) }.keys.sorted()

  private fun allows(caller: Tier, needed: Tier) = caller == Tier.OPERATOR || needed == Tier.VIEWER

  /**
   * Check [message] (`{"cmd":…, "device"?:…, …args}`) for a session running [devices] by a caller
   * of [tier]. Unknown keys are dropped; a known key with a bad value refuses the whole command, so
   * a typo is reported rather than silently sent without it.
   */
  fun check(message: JsonObject, devices: List<SimulatorDevice>, tier: Tier): Decision {
    val cmd =
      (message["cmd"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: return Decision.Refused("a command needs a \"cmd\"")
    val spec = SPECS[cmd] ?: return Decision.Refused("\"$cmd\" is not a command this server offers")
    if (!allows(tier, spec.tier)) {
      return Decision.Refused("\"$cmd\" needs operator access")
    }
    val named = (message["device"] as? JsonPrimitive)?.contentOrNull
    val device =
      if (named == null) devices.first()
      else
        SimulatorDevice.of(named)?.takeIf { it in devices }
          ?: return Decision.Refused(
            "this session has no device \"$named\"; it runs ${devices.map { it.id }}",
          )
    if (spec.devices != null && device !in spec.devices) {
      return Decision.Refused("\"$cmd\" is for ${spec.devices.map { it.id }}, not ${device.id}")
    }
    val out = LinkedHashMap<String, JsonElement>()
    out["cmd"] = JsonPrimitive(cmd)
    for ((key, value) in message) {
      if (key == "cmd" || key == "device" || key == "id" || key == "type") continue
      val arg = spec.args[key] ?: continue
      if (value is JsonNull) continue
      val normalised = if (cmd == "key" && key == "key") normaliseKeys(value) else value
      out[key] =
        arg.check(normalised) ?: return Decision.Refused("\"$cmd\": bad value for \"$key\"")
    }
    return Decision.Allowed(cmd, device, JsonObject(out))
  }

  private fun normaliseKeys(value: JsonElement): JsonElement {
    fun one(e: JsonElement): JsonElement {
      val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return e
      return JsonPrimitive(s.trim().uppercase().removePrefix("KEYCODE_"))
    }
    return if (value is JsonArray) JsonArray(value.map(::one)) else one(value)
  }

  /** Keys whose values name files on this host, wherever they appear in a result or event. */
  private val HOST_PATH_KEYS =
    setOf(
      "path",
      "hostPath",
      "apk",
      "apkPath",
      "classesJar",
      "classes",
      "dataDir",
      "cacheDir",
      "sourceDir",
      "jar",
      "jars",
      "file",
      "files",
      "dir",
      "directory",
      "entry",
      "cache",
      "command",
    )

  /**
   * [element] without the host's file system in it: keys in [HOST_PATH_KEYS] are removed and any
   * string naming one of [hostRoots] becomes `"…"`. Device paths (`/data/…`, `/sdcard/…`) stay.
   */
  fun redact(element: JsonElement, hostRoots: List<String>): JsonElement =
    when (element) {
      is JsonObject ->
        JsonObject(
          element.filterKeys { it !in HOST_PATH_KEYS }.mapValues { redact(it.value, hostRoots) },
        )
      is JsonArray -> JsonArray(element.map { redact(it, hostRoots) })
      is JsonPrimitive ->
        if (element.isString && hostRoots.any { it.length > 1 && element.content.contains(it) })
          JsonPrimitive("…")
        else element
      JsonNull -> element
    }

  /**
   * Events a viewer is shown, and the fields of each: enough to follow the app (what activity is
   * up, a notification arriving, an intent nothing handles), nothing of the host.
   */
  private val VIEWER_EVENTS: Map<String, Set<String>> =
    mapOf(
      "ready" to emptySet(),
      "activity" to setOf("state", "activity", "display"),
      "notification" to setOf("state", "key", "title", "text", "channel"),
      "error" to setOf("message", "error", "intent"),
      "permissions" to setOf("state", "permission", "permissions", "requestId"),
      "home" to setOf("surface"),
      "surface" to setOf("surface"),
      "tile" to setOf("state", "component"),
      "service" to setOf("state", "service"),
      "datalayer" to setOf("kind", "path", "type"),
    )

  /** [event] as [tier] may see it, or null when it is not shown. */
  fun event(event: JsonObject, tier: Tier, hostRoots: List<String>): JsonObject? {
    val name = (event["event"] as? JsonPrimitive)?.contentOrNull ?: return null
    if (tier == Tier.OPERATOR) return redact(event, hostRoots) as JsonObject
    val fields = VIEWER_EVENTS[name] ?: return null
    // A Data Layer `path` is the app's message path, not a file: keep it, by name, before
    // redaction drops every `path`.
    val kept = event.filterKeys { it == "event" || it in fields }
    val redacted = redact(JsonObject(kept - "path"), hostRoots) as JsonObject
    val path = kept["path"]?.takeIf { name == "datalayer" }
    return if (path == null) redacted else JsonObject(redacted + ("path" to path))
  }
}
