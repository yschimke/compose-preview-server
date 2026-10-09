package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A deployment's **non-secret** settings, as one checked-in, reviewable `settings.json`
 * (`deploy/preview.coo.ee/settings.json`) instead of lines in the box's private `.env`.
 *
 * ## Why the image entrypoint applies them, not this process
 *
 * Every setting here already has a `SERVE_*` variable, and the image entrypoint
 * (`deploy/image/entrypoint.sh`) derives a good deal from those variables before `serve` ever
 * starts: whether the image lane is on follows from the upload repository, the agent-grant
 * capabilities are narrowed by it, the GitHub auth flags are passed only when a client id is. So
 * the entrypoint reads `/config/settings.json` and fills each variable the environment left
 * **empty**, before any of that runs, and every derivation behaves exactly as if the value had been
 * in `.env`. That is also what ends the compose-file drift that bit the guidelines variables: a
 * setting read from the config volume never has to be passed through `docker-compose.yml`, so a box
 * whose checkout lags the image still gets it.
 *
 * The entrypoint learns the variable for each setting from the schema this object generates
 * ([schema], committed as `deploy/image/settings.schema.json` and baked into the image), so the
 * mapping is written exactly once: here.
 *
 * ## Precedence
 *
 * Built-in default, then `settings.json`, then the environment (`.env`). The environment stays on
 * top so an emergency fix and the transition both work: a box keeps everything its `.env` already
 * says until the line is deleted. The entrypoint records where each value came from in
 * [SOURCES_ENV], which this process reports at startup and on `GET /admin/settings` — so a stale
 * `.env` line shadowing a reviewed value is visible rather than a mystery.
 *
 * ## Applying a change
 *
 * `PUT /admin/settings` ([ServeSettingsAdmin]) stores the document beside `catalogs.json`. A
 * [Apply.LIVE] setting is re-read there and then; a [Apply.RESTART] one is bound at startup and the
 * reply says it applies at the next start, like `/admin/ui-builder/config` does.
 *
 * ## What is not here
 *
 * Secrets (tokens, client secrets, API keys) never: the file is public. Nor are facts about the
 * machine — memory limits, seat and slot counts, cache sizes, sandbox mounts, hostnames the edge
 * routes — which are sized against the box's own `PREVIEW_MEM_LIMIT` and have to change with it, so
 * they stay in `.env` beside it. Nor what another file already owns: the UI builder's catalog set
 * is `catalogs.json`'s `uiBuilder` block ([ServeUiBuilderSettings]) and top-level sites are its
 * `sites`.
 */
object ServeSettings {

  /** When a changed value takes effect. */
  enum class Apply(val wire: String) {
    /** Re-read by the running server when `settings.json` is published. */
    LIVE("live"),
    /** Bound at startup; a published change waits for the next start. */
    RESTART("restart"),
  }

  /** Which of the image's containers read a setting (`SERVE_ROLE`). */
  enum class Role(val wire: String) {
    PREVIEW("preview"),
    PLAYGROUND("playground"),
  }

  /** Where the value a process is serving came from. */
  enum class Source(val wire: String) {
    DEFAULT("default"),
    SETTINGS("settings.json"),
    ENVIRONMENT("environment"),
  }

  /** A setting's JSON shape, and how it is spelled as its environment variable. */
  sealed class Type {
    /** Why [value] is not acceptable, or null. */
    abstract fun check(value: JsonElement): String?

    /** [value], already [check]ed, as the entrypoint exports it. */
    abstract fun envValue(value: JsonElement): String

    abstract fun schema(): JsonObject

    /** A string, optionally one of [values] or matching [pattern] ([patternHint] says what). */
    class Text(
      private val values: List<String>? = null,
      private val pattern: Regex? = null,
      private val patternHint: String? = null,
    ) : Type() {
      override fun check(value: JsonElement): String? {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when {
          text == null -> "must be a string"
          text.isBlank() -> "must not be blank; leave the setting out for the default"
          text.any { it.isISOControl() } -> "must not contain control characters"
          values != null && text !in values -> "must be one of ${values.joinToString()}"
          pattern != null && !pattern.matches(text) -> "must be ${patternHint ?: "/$pattern/"}"
          else -> null
        }
      }

      override fun envValue(value: JsonElement): String = (value as JsonPrimitive).content

      override fun schema(): JsonObject = buildJsonObject {
        put("type", "string")
        put("minLength", 1)
        values?.let { put("enum", buildJsonArray { it.forEach { v -> add(JsonPrimitive(v)) } }) }
        pattern?.let { put("pattern", "^(?:${it.pattern})$") }
      }
    }

    /** `true` / `false`, exported as `1` / `0` — both of which the entrypoint reads as set. */
    object Flag : Type() {
      override fun check(value: JsonElement): String? =
        if ((value as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) {
          "must be true or false"
        } else null

      override fun envValue(value: JsonElement): String =
        if ((value as JsonPrimitive).booleanOrNull == true) "1" else "0"

      override fun schema(): JsonObject = buildJsonObject { put("type", "boolean") }
    }

    /** A whole number of at least [min]. */
    class Whole(private val min: Long = 0) : Type() {
      override fun check(value: JsonElement): String? {
        val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        return when {
          number == null -> "must be a whole number"
          number < min -> "must be at least $min"
          else -> null
        }
      }

      override fun envValue(value: JsonElement): String = (value as JsonPrimitive).content

      override fun schema(): JsonObject = buildJsonObject {
        put("type", "integer")
        put("minimum", min)
      }
    }

    /**
     * A list of names, exported comma-separated. An empty list exports [emptySpelling]: for a
     * variable whose entrypoint default is not empty, `none` is how "nobody" is said, and an empty
     * string would read as unset and bring the default back.
     */
    class Names(
      private val item: Regex? = null,
      private val itemHint: String? = null,
      private val emptySpelling: String = "",
      /** A check of the whole list, as the server's own flag parser would make it. */
      private val whole: ((List<String>) -> String?)? = null,
    ) : Type() {
      override fun check(value: JsonElement): String? {
        val array = value as? JsonArray ?: return "must be a list of strings"
        val items = array.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        if (items.any { it == null }) return "must be a list of strings"
        @Suppress("UNCHECKED_CAST") val names = items as List<String>
        names
          .firstOrNull { it.isBlank() || ',' in it || it.any(Char::isISOControl) }
          ?.let {
            return "entries must be non-blank and contain no commas, got `$it`"
          }
        if (item != null) {
          names
            .firstOrNull { !item.matches(it) }
            ?.let {
              return "entry `$it` must be ${itemHint ?: "/$item/"}"
            }
        }
        if (names.map { it.lowercase() }.toSet().size != names.size) return "has a duplicate entry"
        return whole?.invoke(names)
      }

      override fun envValue(value: JsonElement): String =
        (value as JsonArray)
          .joinToString(",") { (it as JsonPrimitive).content }
          .ifEmpty { emptySpelling }

      override fun schema(): JsonObject = buildJsonObject {
        put("type", "array")
        put("uniqueItems", true)
        put(
          "items",
          buildJsonObject {
            put("type", "string")
            put("minLength", 1)
            put("pattern", item?.let { "^(?:${it.pattern})$" } ?: "^[^,]+$")
          },
        )
        if (emptySpelling.isNotEmpty()) put("x-empty", emptySpelling)
      }
    }
  }

  /** One setting: where it sits in `settings.json`, and the variable it stands for. */
  class Setting(
    /** Dotted path in `settings.json`, e.g. `uiBuilder.guidelines.model`. */
    val key: String,
    /** The `SERVE_*` variable the entrypoint fills from it. */
    val env: String,
    val type: Type,
    val apply: Apply,
    val description: String,
    /** The containers that read it. A setting the playground must agree on reads in both. */
    val roles: Set<Role> = setOf(Role.PREVIEW),
  ) {
    val path: List<String> = key.split('.')

    override fun toString(): String = key
  }

  private val GITHUB_LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
  private val GITHUB_REPO = Regex("[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9._-]+")
  private val HTTPS_URL = Regex("https://[^\\s/?#@]+(?:/[^\\s]*)?")
  private val HOSTNAME = Regex("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?")
  private val BOTH = setOf(Role.PREVIEW, Role.PLAYGROUND)

  /**
   * Every managed setting. Adding one is a line here, then `UPDATE_SERVE_SETTINGS_REFERENCE=true`
   * on `ServeSettingsTest` to regenerate the schema and reference; the entrypoint needs no edit.
   */
  val ALL: List<Setting> =
    listOf(
      Setting(
        "uiBuilder.adminActors",
        "SERVE_UI_BUILDER_ADMIN_ACTORS",
        Type.Names(
          ServeCommandOptions.UI_BUILDER_ADMIN_ACTOR,
          "a GitHub actor id such as github:octocat",
          emptySpelling = "none",
        ),
        Apply.RESTART,
        "GitHub actors who administer every shared UI-builder design and its folder placement. " +
          "They gain no catalog, trust or site authority. An empty list turns off the image's " +
          "default (`github:yschimke`).",
      ),
      Setting(
        "uiBuilder.startUrl",
        "SERVE_UI_BUILDER_START_URL",
        Type.Text(pattern = HTTPS_URL, patternHint = "an HTTPS URL without credentials"),
        Apply.RESTART,
        "Where the UI builder's start page sends a visitor.",
        BOTH,
      ),
      Setting(
        "uiBuilder.guidelines.model",
        "SERVE_UI_BUILDER_GUIDELINES_MODEL",
        Type.Text(),
        Apply.LIVE,
        "The OpenRouter model id the `guidelines` design check runs on, on this box's key. " +
          "Default `${ServeUiBuilderGuidelinesConfig.DEFAULT_MODEL}`.",
      ),
      Setting(
        "uiBuilder.guidelines.users",
        "SERVE_UI_BUILDER_GUIDELINES_USERS",
        Type.Names(GITHUB_LOGIN, "a GitHub login"),
        Apply.LIVE,
        "GitHub logins who may spend this box's OpenRouter key on the `guidelines` check. The " +
          "check stays off unless this or `orgs` names someone.",
      ),
      Setting(
        "uiBuilder.guidelines.orgs",
        "SERVE_UI_BUILDER_GUIDELINES_ORGS",
        Type.Names(GITHUB_LOGIN, "a GitHub organization"),
        Apply.LIVE,
        "GitHub organizations whose members may run the `guidelines` check.",
      ),
      Setting(
        "uiBuilder.guidelines.pictureBudgetSeconds",
        "SERVE_UI_BUILDER_GUIDELINES_PICTURE_BUDGET",
        Type.Whole(min = 0),
        Apply.LIVE,
        "How long a guidelines prompt waits for frames it has no picture of yet. " +
          "Default $DEFAULT_GUIDELINES_PICTURE_BUDGET_SECONDS.",
      ),
      Setting(
        "uiBuilder.guidelines.triage",
        "SERVE_UI_BUILDER_GUIDELINES_TRIAGE",
        Type.Text(values = listOf("on", "off")),
        Apply.LIVE,
        "Before a guidelines check, ask Jev which extra evidence would help (a dark render, a " +
          "large-font render, the accessibility tree). Default `on`.",
      ),
      Setting(
        "catalogs.registry",
        "SERVE_CATALOG_REGISTRY",
        Type.Text(),
        Apply.RESTART,
        "Catalog registries this box serves every catalog of, beyond `catalogs.json`, as " +
          "`--catalog-registry` takes them. `none` turns it off.",
        BOTH,
      ),
      Setting(
        "catalogs.mcp",
        "SERVE_CATALOG_MCP",
        Type.Flag,
        Apply.RESTART,
        "Serve the remote catalog MCP endpoint. Needs agent grants.",
      ),
      Setting(
        "agents.grantCapabilities",
        "SERVE_AGENT_GRANT_CAPABILITIES",
        Type.Names(
          whole = { names ->
            runCatching { AgentGrantCapability.parseAll(names.joinToString(",")) }
              .exceptionOrNull()
              ?.let { it.message ?: "names an unknown capability" }
          }
        ),
        Apply.RESTART,
        "Capabilities an agent grant may be given, beyond the defaults. `images` is dropped " +
          "unless the image lane is on.",
      ),
      Setting(
        "uploads.acceptDocs",
        "SERVE_ACCEPT_DOCS",
        Type.Flag,
        Apply.RESTART,
        "Accept Remote Compose document uploads at `/d/`.",
      ),
      Setting(
        "uploads.acceptImages",
        "SERVE_ACCEPT_IMAGES",
        Type.Flag,
        Apply.RESTART,
        "Accept image uploads. Left out, it is on exactly when `imageRepository` is named.",
      ),
      Setting(
        "uploads.imageRepository",
        "SERVE_IMAGE_UPLOAD_REPO",
        Type.Text(pattern = GITHUB_REPO, patternHint = "a GitHub owner/repo"),
        Apply.RESTART,
        "The repository whose collaborators may upload images. Falls back to the sign-in " +
          "repository.",
        BOTH,
      ),
      Setting(
        "rendering.compileEngine",
        "SERVE_COMPILE_ENGINE",
        Type.Flag,
        Apply.RESTART,
        "Run the playground's compile engine without mounting the playground page.",
      ),
      Setting(
        "rendering.rcDefaultPlayer",
        "SERVE_RC_DEFAULT_PLAYER",
        Type.Text(values = ServeRcPlayerIds.UNIVERSE.map { it.id }),
        Apply.RESTART,
        "The Remote Compose player the viewer opens on, where a preview offers it.",
      ),
      Setting(
        "analytics.umami.enabled",
        "SERVE_UMAMI_ENABLED",
        Type.Flag,
        Apply.RESTART,
        "Add the Umami analytics script to served pages.",
      ),
      Setting(
        "analytics.umami.url",
        "SERVE_UMAMI_URL",
        Type.Text(pattern = HTTPS_URL, patternHint = "an HTTPS URL"),
        Apply.RESTART,
        "The Umami instance's origin.",
      ),
      Setting(
        "analytics.umami.websiteId",
        "SERVE_UMAMI_WEBSITE_ID",
        Type.Text(),
        Apply.RESTART,
        "The Umami website id.",
      ),
      Setting(
        "auth.github.clientId",
        "SERVE_GITHUB_AUTH_CLIENT_ID",
        Type.Text(),
        Apply.RESTART,
        "The GitHub OAuth app's client id. Public by design; its client secret and the cookie " +
          "secret stay in `.env`.",
        BOTH,
      ),
      Setting(
        "auth.github.callbackBaseUrl",
        "SERVE_GITHUB_AUTH_CALLBACK_BASE_URL",
        Type.Text(pattern = HTTPS_URL, patternHint = "an HTTPS URL"),
        Apply.RESTART,
        "The origin GitHub redirects back to after sign-in.",
        BOTH,
      ),
      Setting(
        "auth.github.cookieDomain",
        "SERVE_GITHUB_AUTH_COOKIE_DOMAIN",
        Type.Text(pattern = HOSTNAME, patternHint = "a domain name, or none"),
        Apply.RESTART,
        "The parent domain the sign-in cookies cover, so one sign-in covers every site host. " +
          "`none` keeps them host-only.",
        BOTH,
      ),
      Setting(
        "auth.github.openUiBuilder",
        "SERVE_GITHUB_AUTH_OPEN_UI_BUILDER",
        Type.Flag,
        Apply.RESTART,
        "Let every signed-in GitHub account create, edit and export UI-builder designs.",
      ),
      Setting(
        "auth.github.scope",
        "SERVE_GITHUB_AUTH_SCOPE",
        Type.Text(),
        Apply.RESTART,
        "The OAuth scope asked for. Only read-only identity scopes are accepted.",
      ),
    )

  private val BY_KEY: Map<String, Setting> = ALL.associateBy { it.key }

  val BY_ENV: Map<String, Setting> = ALL.associateBy { it.env }

  /** `$schema` is the only key a document may carry that is not a setting. */
  private const val SCHEMA_KEY = "\$schema"

  /**
   * The entrypoint's record of where each managed variable came from: `ENV=source,…`, with source
   * one of [Source.wire]. A variable it does not list was left at its default.
   */
  const val SOURCES_ENV = "SERVE_SETTINGS_SOURCES"

  private val JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
  }

  /** Parse [text] as a settings document. Throws on malformed JSON or a non-object root. */
  fun parse(text: String): JsonObject =
    Json.parseToJsonElement(text) as? JsonObject
      ?: throw IllegalArgumentException("settings must be a JSON object")

  /** Every problem with [document]: unknown keys (with the likely intended one), wrong types. */
  fun validate(document: JsonObject): List<String> {
    val problems = mutableListOf<String>()
    fun walk(node: JsonObject, prefix: List<String>) {
      for ((name, value) in node) {
        if (prefix.isEmpty() && name == SCHEMA_KEY) continue
        val path = prefix + name
        val key = path.joinToString(".")
        val setting = BY_KEY[key]
        when {
          setting != null -> setting.type.check(value)?.let { problems += "`$key` $it" }
          ALL.any { it.path.size > path.size && it.path.subList(0, path.size) == path } ->
            (value as? JsonObject)?.let { walk(it, path) }
              ?: run { problems += "`$key` must be an object" }
          else -> problems += "`$key` is not a setting${suggestion(key)}"
        }
      }
    }
    walk(document, emptyList())
    return problems
  }

  /** The settings [document] gives a value, as their variables would hold it. */
  fun values(document: JsonObject): Map<Setting, String> =
    ALL.mapNotNull { setting ->
        lookup(document, setting)?.let { setting to setting.type.envValue(it) }
      }
      .toMap()

  private fun lookup(document: JsonObject, setting: Setting): JsonElement? {
    var node: JsonElement = document
    for (part in setting.path) {
      node = (node as? JsonObject)?.get(part) ?: return null
    }
    return node.takeUnless { it is kotlinx.serialization.json.JsonNull }
  }

  /** The [SOURCES_ENV] record, as the entrypoint wrote it. Unknown entries are ignored. */
  fun parseSources(raw: String?): Map<Setting, Source> =
    raw
      .orEmpty()
      .split(',')
      .mapNotNull { entry ->
        val env = entry.substringBefore('=').trim()
        val source = Source.entries.firstOrNull { it.wire == entry.substringAfter('=', "").trim() }
        val setting = BY_ENV[env]
        if (setting != null && source != null) setting to source else null
      }
      .toMap()

  private fun suggestion(key: String): String {
    val leaf = key.substringAfterLast('.').lowercase()
    val near =
      ALL.minByOrNull { distance(it.key.lowercase(), key.lowercase()) }
        ?.takeIf {
          distance(it.key.lowercase(), key.lowercase()) <= 3 ||
            it.key.substringAfterLast('.').lowercase() == leaf
        }
    return near?.let { "; did you mean `${it.key}`?" } ?: ""
  }

  private fun distance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in a.indices) {
      val current = IntArray(b.length + 1)
      current[0] = i + 1
      for (j in b.indices) {
        current[j + 1] =
          minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + if (a[i] == b[j]) 0 else 1)
      }
      previous = current
    }
    return previous[b.length]
  }

  /**
   * The JSON Schema for `settings.json`, committed as `deploy/image/settings.schema.json`. Each
   * leaf carries `x-env`, `x-apply` and `x-roles`: the entrypoint reads the variable mapping from
   * it.
   */
  fun schema(): JsonObject {
    fun group(prefix: List<String>): JsonObject {
      val children =
        ALL.filter { it.path.size > prefix.size && it.path.subList(0, prefix.size) == prefix }
          .map { it.path[prefix.size] }
          .distinct()
      return buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put(
          "properties",
          buildJsonObject {
            if (prefix.isEmpty()) {
              put(SCHEMA_KEY, buildJsonObject { put("type", "string") })
            }
            for (child in children) {
              val path = prefix + child
              val setting = BY_KEY[path.joinToString(".")]
              if (setting == null) {
                put(child, group(path))
              } else {
                put(
                  child,
                  buildJsonObject {
                    put("description", setting.description)
                    setting.type.schema().forEach { (k, v) -> put(k, v) }
                    put("x-env", setting.env)
                    put("x-apply", setting.apply.wire)
                    put(
                      "x-roles",
                      buildJsonArray {
                        setting.roles.sorted().forEach { add(JsonPrimitive(it.wire)) }
                      },
                    )
                  },
                )
              }
            }
          },
        )
      }
    }
    val root = group(emptyList())
    return buildJsonObject {
      put("\$schema", "https://json-schema.org/draft/2020-12/schema")
      put(
        "\$id",
        "https://github.com/yschimke/compose-preview-server/deploy/image/settings.schema.json",
      )
      put("title", "compose-preview-server deployment settings")
      put(
        "description",
        "Generated from ServeSettings.kt; regenerate with UPDATE_SERVE_SETTINGS_REFERENCE=true. " +
          "Non-secret settings only: secrets and machine sizing stay in .env.",
      )
      root.forEach { (k, v) -> put(k, v) }
    }
  }

  fun schemaText(): String = JSON.encodeToString(JsonObject.serializer(), schema()) + "\n"

  /** The settings reference, committed as `deploy/image/SETTINGS.md`. */
  fun reference(): String = buildString {
    appendLine("# Deployment settings reference")
    appendLine()
    appendLine(
      "<!-- Generated from server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeSettings.kt. " +
        "Regenerate with UPDATE_SERVE_SETTINGS_REFERENCE=true on ServeSettingsTest. -->"
    )
    appendLine()
    appendLine(
      "Non-secret settings live in a deployment's `settings.json` " +
        "(preview.coo.ee: [`deploy/preview.coo.ee/settings.json`](../preview.coo.ee/settings.json)), " +
        "validated against [`settings.schema.json`](settings.schema.json) and published to the box " +
        "through `PUT /admin/settings`. Precedence is built-in default, then `settings.json`, " +
        "then the environment (`.env`): an `.env` line still wins, and `GET /admin/settings` says " +
        "which source each value came from."
    )
    appendLine()
    appendLine(
      "**live** settings take effect when published; **restart** settings are stored and apply " +
        "at the next start. Secrets and machine sizing stay in `.env` (see README.md)."
    )
    appendLine()
    appendLine("| Setting | Variable | Applies | Read by | Description |")
    appendLine("|---|---|---|---|---|")
    for (setting in ALL) {
      append("| `").append(setting.key).append("` | `").append(setting.env).append("` | ")
      append(setting.apply.wire).append(" | ")
      append(setting.roles.sorted().joinToString(", ") { it.wire }).append(" | ")
      append(setting.description.replace("|", "\\|")).appendLine(" |")
    }
  }
}
