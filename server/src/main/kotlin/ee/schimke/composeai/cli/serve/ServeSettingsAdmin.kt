package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.ServeSettings.Setting
import ee.schimke.composeai.cli.serve.ServeSettings.Source
import ee.schimke.composeai.io.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * A setting this process can change while it runs: the [envs] it answers for, and how to apply new
 * values of all of them at once (a guidelines change has to swap the model and the allow-list
 * together). A null value means "back to the default".
 */
interface ServeLiveSettings {
  val envs: Set<String>

  /** Why these values cannot be applied, or null. Called before anything is written. */
  fun check(values: Map<String, String?>): String? = null

  fun apply(values: Map<String, String?>)
}

/**
 * `settings.json` on the box ([ServeSettings]): what it holds, what this process is serving and
 * from where, and `PUT /admin/settings`.
 *
 * What is serving is what the image entrypoint resolved before `serve` started ([environment], this
 * process's own environment), and where each value came from is the entrypoint's
 * [ServeSettings.SOURCES_ENV]. A process started without the entrypoint has no such record, so a
 * value in its environment is reported as coming from there.
 */
class ServeSettingsAdmin(
  /** `settings.json`; null when the box has no config directory to keep it in. */
  private val path: Path?,
  private val environment: Map<String, String> = System.getenv(),
  private val live: List<ServeLiveSettings> = emptyList(),
  private val fileSystem: FileSystem = SystemFileSystem,
) {
  /** One setting's state, as `GET /admin/settings` reports it. */
  @Serializable
  data class Entry(
    val key: String,
    val env: String,
    val apply: String,
    /** Where [serving] came from: `default`, `settings.json` or `environment`. */
    val source: String,
    /** The value in force, as its variable spells it; null ⇒ the built-in default. */
    val serving: String?,
    /** The value `settings.json` holds now, as its variable spells it. */
    val configured: String?,
    /**
     * `.env` sets this variable, so [configured] does not apply while that line stays — the
     * stale-override case worth seeing.
     */
    val overridden: Boolean,
    /** The next start serves something other than [serving]. */
    val pending: Boolean,
  )

  sealed interface Result {
    data class Ok(val entries: List<Entry>, val applied: List<String>, val problems: List<String>) :
      Result

    data class Invalid(val problems: List<String>) : Result

    data class Unavailable(val reason: String) : Result
  }

  private val sources: Map<Setting, Source> =
    environment[ServeSettings.SOURCES_ENV]?.let(ServeSettings::parseSources)
      ?: ServeSettings.ALL.filter { !environment[it.env].isNullOrEmpty() }
        .associateWith { Source.ENVIRONMENT }

  /** What each setting is serving now. A live change moves it; nothing else does. */
  private val serving: MutableMap<Setting, String?> =
    ServeSettings.ALL.associateWithTo(mutableMapOf()) { setting ->
      environment[setting.env]?.takeIf { it.isNotEmpty() && sourceOf(setting) != Source.DEFAULT }
    }

  /** Where each serving value came from; a live change makes it `settings.json`. */
  private val servingSource: MutableMap<Setting, Source> =
    ServeSettings.ALL.associateWithTo(mutableMapOf(), ::sourceOf)

  private fun sourceOf(setting: Setting): Source = sources[setting] ?: Source.DEFAULT

  val displayPath: String?
    get() = path?.toString()

  /** The document on disk, or null when there is none. Throws on a file that does not parse. */
  fun configured(): JsonObject? {
    val file = path ?: return null
    if (!fileSystem.exists(file)) return null
    return ServeSettings.parse(fileSystem.read(file) { readUtf8() })
  }

  /** Every setting's state against [document] (default: the file as it stands). */
  @Synchronized
  fun entries(document: JsonObject? = runCatching { configured() }.getOrNull()): List<Entry> {
    val configured = document?.let(ServeSettings::values).orEmpty()
    return ServeSettings.ALL.map { setting ->
      val value = configured[setting]
      val fromEnvironment = servingSource[setting] == Source.ENVIRONMENT
      // `.env` stays on top until the line goes, so the next start serves what it serves now.
      val next = if (fromEnvironment) serving[setting] else value
      Entry(
        key = setting.key,
        env = setting.env,
        apply = setting.apply.wire,
        source = servingSource.getValue(setting).wire,
        serving = serving[setting],
        configured = value,
        overridden = fromEnvironment && value != null && value != serving[setting],
        pending = next != serving[setting],
      )
    }
  }

  /** Lines for the startup log: each value not at its default, and each stale `.env` override. */
  fun describe(): List<String> {
    val entries = runCatching {
      entries(configured())
    }
      .getOrElse {
        return listOf(
          "serve: settings: ${displayPath ?: "settings.json"} could not be read (${it.message}); " +
            "serving the environment's values"
        )
      }
    val lines = mutableListOf<String>()
    val set = entries.filter { it.source != Source.DEFAULT.wire }
    if (set.isNotEmpty()) {
      lines +=
        "serve: settings: " +
          set.joinToString(", ") { "${it.key} from ${it.source}" } +
          "; everything else at its default"
    }
    entries
      .filter { it.overridden }
      .forEach {
        lines +=
          "serve: settings: ${it.env} in the environment overrides settings.json's ${it.key}; " +
            "delete the .env line to let the reviewed value apply"
      }
    entries
      .filter { it.pending && !it.overridden }
      .forEach {
        lines += "serve: settings: ${it.key} changed in settings.json; applies at the next start"
      }
    return lines
  }

  /**
   * Replace `settings.json` with [document]: validated, written, and applied to every live setting
   * the environment does not override. Everything else applies at the next start.
   */
  @Synchronized
  fun set(document: JsonObject): Result {
    val file = path ?: return Result.Unavailable(NO_FILE)
    ServeSettings.validate(document)
      .takeIf { it.isNotEmpty() }
      ?.let {
        return Result.Invalid(it)
      }
    val values = ServeSettings.values(document)
    // Each live group's new values, leaving what `.env` overrides as it is.
    val changes = live.mapNotNull { group ->
      val next =
        group.envs.associateWith { env ->
          val setting = ServeSettings.BY_ENV.getValue(env)
          if (servingSource[setting] == Source.ENVIRONMENT) serving[setting] else values[setting]
        }
      val current = group.envs.associateWith { serving[ServeSettings.BY_ENV.getValue(it)] }
      if (next == current) null else group to next
    }
    val refused = changes.mapNotNull { (group, next) -> group.check(next) }
    if (refused.isNotEmpty()) return Result.Invalid(refused)

    write(file, document)
    val applied = mutableListOf<String>()
    val problems = mutableListOf<String>()
    for ((group, next) in changes) {
      runCatching { group.apply(next) }
        .onSuccess {
          for ((env, value) in next) {
            val setting = ServeSettings.BY_ENV.getValue(env)
            if (serving[setting] == value) continue
            serving[setting] = value
            servingSource[setting] = if (value == null) Source.DEFAULT else Source.SETTINGS
            applied += setting.key
          }
        }
        .onFailure {
          problems +=
            "${group.envs.sorted().joinToString()}: not applied (${it.message}); " +
              "applies at the next start"
        }
    }
    return Result.Ok(entries(document), applied, problems)
  }

  /**
   * Remove `settings.json`: every setting goes back to its environment or default at next start.
   */
  @Synchronized
  fun clear(): Result {
    val file = path ?: return Result.Unavailable(NO_FILE)
    fileSystem.delete(file, mustExist = false)
    return Result.Ok(entries(null), emptyList(), emptyList())
  }

  private fun write(file: Path, document: JsonObject) {
    val parent = file.parent
    parent?.let { fileSystem.createDirectories(it) }
    val tmp = if (parent != null) parent / "${file.name}.tmp" else "${file.name}.tmp".toPath()
    fileSystem.write(tmp) {
      writeUtf8(PRETTY.encodeToString(JsonObject.serializer(), document) + "\n")
    }
    fileSystem.atomicMove(tmp, file)
  }

  private companion object {
    const val NO_FILE =
      "this server has no --settings-file, so settings would not survive the restart most need"

    val PRETTY = Json {
      prettyPrint = true
      prettyPrintIndent = "  "
    }
  }
}
