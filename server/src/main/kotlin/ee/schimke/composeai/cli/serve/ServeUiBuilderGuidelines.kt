package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The `guidelines` half of `ui_builder_check_design`: a model judges a design against the Android
 * design guidance in [UiBuilderGuidelineRuleSet], through OpenRouter, on the **operator's** key.
 *
 * Because the key is shared and every call costs money, the check is never on by default and never
 * open to everyone: [ServeUiBuilderGuidelineAccess] admits only the GitHub logins and organizations
 * the operator named. Every other caller gets the check reported as skipped, with why, and the rest
 * of `ui_builder_check_design` runs as before.
 *
 * The rules are the same file compose-ui-builder ships its in-browser check with
 * (`docs/guidelines/android-design-guidelines.json`); this copy is a resource of the server.
 *
 * What the model sees: an indented outline of the design tree (component, properties, modifiers,
 * slots — never asset bytes), the screen size and theme, the rules for the design's platform, and,
 * when the caller asked for `rendered: true` and the host could render, the native PNG. `visual`
 * rules need that picture and are skipped without it. Model findings are advisory: a failed rule is
 * a warning or a note, never an error, so `ok` is unchanged by them.
 */
class ServeUiBuilderGuidelines
internal constructor(
  private val config: ServeUiBuilderGuidelinesConfig,
  private val access: ServeUiBuilderGuidelineAccess,
  private val rules: UiBuilderGuidelineRuleSet = UiBuilderGuidelineRuleSet.bundled(),
  private val transport: OpenRouterTransport = OkHttpOpenRouterTransport(config.endpoint),
) {
  val model: String
    get() = config.model

  /** May [actor] spend the key? An org check can ask GitHub, so it runs on the I/O pool. */
  suspend fun allows(actor: AuthenticatedUiBuilderActor): Boolean =
    withContext(Dispatchers.IO) { access.allows(actor) }

  /** Who may run the check, for the startup banner. */
  fun describeAccess(): String = access.describe()

  /**
   * Judges [document], with [png] (a render, for the visual rules) and the Compose code the design
   * exports to, which [source] produces only once there are rules to ask about.
   */
  internal suspend fun check(
    document: JsonObject,
    png: ByteArray?,
    source: suspend () -> String? = { null },
  ): UiBuilderGuidelineOutcome {
    val systemId =
      document["catalogPin"]?.let { it as? JsonObject }?.get("systemId")?.stringOrNull().orEmpty()
    val platform =
      UiBuilderGuidelinePrompt.platformOf(systemId)
        ?: return UiBuilderGuidelineOutcome.Skipped(
          "no guidelines are written for catalog `$systemId` yet"
        )
    val applicable = rules.rules.filter { platform in it.platforms }
    val (visual, structural) = applicable.partition { it.kind == KIND_VISUAL }
    val asked = if (png != null) applicable else structural
    if (asked.isEmpty()) {
      return UiBuilderGuidelineOutcome.Skipped(
        "no $platform guideline can be judged without a render"
      )
    }
    val code = source()
    val body =
      UiBuilderGuidelinePrompt.requestBody(
        model = config.model,
        platform = platform,
        document = document,
        rules = asked,
        pngDataUrl = png?.let { "data:image/png;base64," + Base64.getEncoder().encodeToString(it) },
        source = code,
      )
    val response =
      try {
        withContext(Dispatchers.IO) {
          transport.post(
            GUIDELINES_JSON.encodeToString(JsonObject.serializer(), body),
            config.apiKey,
          )
        }
      } catch (e: java.io.IOException) {
        return UiBuilderGuidelineOutcome.Failed("OpenRouter could not be reached: ${e.message}")
      }
    if (response.status !in 200..299) {
      return UiBuilderGuidelineOutcome.Failed(
        "OpenRouter answered ${response.status}: ${UiBuilderGuidelinePrompt.errorMessage(response.body)}"
      )
    }
    val verdicts =
      UiBuilderGuidelinePrompt.parseCompletion(response.body).getOrElse {
        return UiBuilderGuidelineOutcome.Failed(
          "the model's answer was not the verdict list asked for: ${it.message}"
        )
      }
    val byId = asked.associateBy { it.id }
    // One verdict per rule asked: the first for each id, nothing for rules nobody asked about. A
    // rule the model skipped is reported as unanswered, never counted as a pass.
    val answered = verdicts.filter { it.ruleId in byId }.distinctBy { it.ruleId }
    if (answered.isEmpty()) {
      return UiBuilderGuidelineOutcome.Failed(
        "${config.model} returned no verdict for any of the ${asked.size} rules asked"
      )
    }
    val unanswered = asked.map { it.id } - answered.map { it.ruleId }.toSet()
    val nodeIds = (document["nodes"] as? JsonObject)?.keys.orEmpty()
    val findings =
      answered
        .filter { it.verdict == VERDICT_FAIL && it.confidence >= config.minConfidence }
        .flatMap { verdict ->
          val rule = byId[verdict.ruleId] ?: return@flatMap emptyList()
          val nodes = verdict.nodeIds.filter { it in nodeIds }.distinct().take(MAX_NODES_PER_RULE)
          (nodes.ifEmpty { listOf(null) }).map { nodeId ->
            UiBuilderCheckFindingV1(
              severity = if (rule.severity == SEVERITY_WARNING) SEVERITY_WARNING else SEVERITY_INFO,
              check = CHECK_GUIDELINES,
              code = rule.id,
              message =
                buildString {
                  append(verdict.reason.trim().ifEmpty { rule.check })
                  append(" Guideline: \"").append(rule.guidance).append("\" (")
                  append(rule.source).append("). Model ").append(config.model)
                  append(", confidence ")
                    .append(String.format(java.util.Locale.ROOT, "%.2f", verdict.confidence))
                    .append('.')
                },
              nodeId = nodeId,
            )
          }
        }
    return UiBuilderGuidelineOutcome.Checked(
      findings = findings,
      judged = answered.size,
      visualSkipped = if (png == null) visual.size else 0,
      unanswered = unanswered,
      sourceAttached = code != null,
    )
  }

  companion object {
    const val KIND_VISUAL = "visual"
    const val VERDICT_FAIL = "fail"
    private const val MAX_NODES_PER_RULE = 5
  }
}

internal sealed interface UiBuilderGuidelineOutcome {
  data class Checked(
    val findings: List<UiBuilderCheckFindingV1>,
    /** How many rules the model judged. */
    val judged: Int,
    /** Visual rules left out because there was no render to show the model. */
    val visualSkipped: Int,
    /** Rules asked about that the model returned no verdict for. */
    val unanswered: List<String> = emptyList(),
    /** Whether the generated Compose source went to the model with the design tree. */
    val sourceAttached: Boolean = false,
  ) : UiBuilderGuidelineOutcome

  data class Skipped(val reason: String) : UiBuilderGuidelineOutcome

  data class Failed(val reason: String) : UiBuilderGuidelineOutcome
}

/**
 * `--ui-builder-guidelines-*`, and the key read from the environment. The key never travels on the
 * command line, where every process listing would show it.
 */
internal data class ServeUiBuilderGuidelinesConfig(
  val apiKey: String,
  val model: String = DEFAULT_MODEL,
  val allowedUsers: Set<String> = emptySet(),
  val allowedOrgs: Set<String> = emptySet(),
  /**
   * A token that can read the named organizations' members, so a **private** membership counts.
   * Without one only public memberships are visible.
   */
  val githubToken: String? = null,
  val endpoint: String = OPENROUTER_CHAT_COMPLETIONS,
  /** Below this, a `fail` verdict is not reported: the model was guessing. */
  val minConfidence: Double = 0.5,
) {
  init {
    require(apiKey.isNotBlank()) { "the guidelines check needs an OpenRouter API key" }
    require(model.isNotBlank()) { "the guidelines check needs a model id" }
    require(allowedUsers.isNotEmpty() || allowedOrgs.isNotEmpty()) {
      "the guidelines check spends the operator's OpenRouter key, so it needs " +
        "--ui-builder-guidelines-users or --ui-builder-guidelines-orgs to say who may run it"
    }
  }

  companion object {
    /** TypeSafe's Jev decision model, as OpenRouter routes it. Any OpenRouter model id works. */
    const val DEFAULT_MODEL = "typesafe/jev-router"
    const val OPENROUTER_CHAT_COMPLETIONS = "https://openrouter.ai/api/v1/chat/completions"
    const val API_KEY_ENV = "SERVE_UI_BUILDER_GUIDELINES_OPENROUTER_KEY"
    const val GITHUB_TOKEN_ENV = "SERVE_UI_BUILDER_GUIDELINES_GITHUB_TOKEN"
  }
}

/**
 * Who may spend the operator's key: a GitHub login on [allowedUsers], a member of one of
 * [allowedOrgs], or the operator's own token. An agent working under a grant counts as the person
 * who approved it ([AuthenticatedUiBuilderActor.onBehalfOfActorId]), so it can run the check
 * exactly when that person could.
 *
 * Membership is asked of GitHub ([isOrgMember]) and remembered for [cacheMillis], so a design
 * session does not cost a GitHub round trip per check and somebody who leaves the org loses access
 * within that window.
 */
internal class ServeUiBuilderGuidelineAccess(
  allowedUsers: Set<String>,
  allowedOrgs: Set<String>,
  private val isOrgMember: (org: String, login: String) -> Boolean,
  private val clock: Clock = Clock.systemUTC(),
  private val cacheMillis: Long = TimeUnit.MINUTES.toMillis(10),
) {
  private val users = allowedUsers.map { it.lowercase() }.toSet()
  private val orgs = allowedOrgs.map { it.lowercase() }.toSet()
  private val memberships = ConcurrentHashMap<String, Pair<Boolean, Long>>()

  fun allows(actor: AuthenticatedUiBuilderActor): Boolean {
    if (actor.accessIdentities.any { it == ServeAgentGrants.OPERATOR_ACTOR_ID }) return true
    val logins =
      actor.accessIdentities
        .filter { it.startsWith(GITHUB_PREFIX) }
        .map { it.removePrefix(GITHUB_PREFIX).lowercase() }
        .filter { it.isNotBlank() }
    if (logins.any { it in users }) return true
    return logins.any { login -> orgs.any { org -> member(org, login) } }
  }

  fun describe(): String = buildList {
    if (users.isNotEmpty()) add("users ${users.sorted().joinToString()}")
    if (orgs.isNotEmpty()) add("members of ${orgs.sorted().joinToString()}")
  }
    .joinToString("; ")

  private fun member(org: String, login: String): Boolean {
    val key = "$org/$login"
    val now = clock.millis()
    memberships[key]?.let { (answer, until) -> if (until > now) return answer }
    val answer = runCatching { isOrgMember(org, login) }.getOrDefault(false)
    memberships[key] = answer to now + cacheMillis
    return answer
  }

  companion object {
    private const val GITHUB_PREFIX = "github:"

    /**
     * GitHub's own answer: `GET /orgs/{org}/members/{login}` is 204 for a member. With a token
     * whose owner belongs to the org it sees private memberships; without one, or for a token the
     * org does not trust, it falls back to the public-members list. Any failure is a no.
     */
    fun githubMembership(
      token: String?,
      client: OkHttpClient = OkHttpClient(),
    ): (String, String) -> Boolean = { org, login ->
      fun ask(path: String, withToken: Boolean): Boolean {
        val request =
          Request.Builder()
            .url("https://api.github.com$path")
            .header("Accept", "application/vnd.github+json")
            .apply { if (withToken && token != null) header("Authorization", "Bearer $token") }
            .build()
        return client.newCall(request).execute().use { it.code == 204 }
      }
      val encodedOrg = java.net.URLEncoder.encode(org, Charsets.UTF_8)
      val encodedLogin = java.net.URLEncoder.encode(login, Charsets.UTF_8)
      (token != null && ask("/orgs/$encodedOrg/members/$encodedLogin", withToken = true)) ||
        ask("/orgs/$encodedOrg/public_members/$encodedLogin", withToken = false)
    }
  }
}

internal fun interface OpenRouterTransport {
  /** POST [body] as JSON with [apiKey] as the bearer, returning the status and the raw reply. */
  fun post(body: String, apiKey: String): Response

  data class Response(val status: Int, val body: String)
}

internal class OkHttpOpenRouterTransport(
  private val endpoint: String,
  private val client: OkHttpClient =
    OkHttpClient.Builder()
      .callTimeout(90, TimeUnit.SECONDS)
      .readTimeout(90, TimeUnit.SECONDS)
      .build(),
) : OpenRouterTransport {
  override fun post(body: String, apiKey: String): OpenRouterTransport.Response {
    val request =
      Request.Builder()
        .url(endpoint)
        .header("Authorization", "Bearer $apiKey")
        // OpenRouter's app attribution headers; neither carries anything about the caller.
        .header("X-Title", "Compose UI Builder guidelines check")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    return client.newCall(request).execute().use {
      OpenRouterTransport.Response(it.code, it.body.string())
    }
  }
}

@Serializable
internal data class UiBuilderGuidelineRule(
  val id: String,
  val platforms: List<String>,
  /** `structure` (the tree is enough) or `visual` (needs a picture). */
  val kind: String,
  /** `warning` or `info`. */
  val severity: String,
  /** The guidance as written at [source]. */
  val guidance: String,
  /** A yes/no question; YES means the design follows the rule. */
  val check: String,
  val source: String,
)

@Serializable
internal data class UiBuilderGuidelineRuleSet(
  val schema: String,
  val version: Int,
  val about: String = "",
  val rules: List<UiBuilderGuidelineRule>,
) {
  companion object {
    private const val RESOURCE = "guidelines/android-design-guidelines.json"

    fun bundled(): UiBuilderGuidelineRuleSet {
      val text =
        UiBuilderGuidelineRuleSet::class.java.getResourceAsStream(RESOURCE)?.use {
          it.readBytes().decodeToString()
        } ?: error("missing resource $RESOURCE")
      return GUIDELINES_JSON.decodeFromString(serializer(), text)
    }
  }
}

/** One rule's answer, as the model returns it. */
@Serializable
internal data class UiBuilderGuidelineVerdict(
  val ruleId: String,
  /** `pass`, `fail` or `not_applicable`. */
  val verdict: String,
  val confidence: Double = 0.0,
  val nodeIds: List<String> = emptyList(),
  val reason: String = "",
)

@Serializable
private data class UiBuilderGuidelineVerdicts(val verdicts: List<UiBuilderGuidelineVerdict>)

/**
 * The request the model is sent and the reading of what comes back — no I/O, so it is tested on its
 * own. The browser check in compose-ui-builder builds the same request from the same rules.
 */
internal object UiBuilderGuidelinePrompt {
  /** Which guides apply to a catalog, read from its system id; null for one with none yet. */
  fun platformOf(systemId: String): String? {
    val id = systemId.lowercase()
    return when {
      "glimmer" in id || "glasses" in id -> "glasses"
      "wear" in id || id.startsWith("remote") -> "wear"
      else -> null
    }
  }

  /** How much generated source a request carries; a screen's export is well under this. */
  const val MAX_SOURCE_CHARS: Int = 16_000

  const val SYSTEM_PROMPT: String =
    "You review UI designs against Android design guidelines. For every rule you are given, " +
      "answer the rule's yes/no `check` for this design: verdict `pass` when the answer is yes, " +
      "`fail` when it is no, `not_applicable` when the rule does not apply to this design or the " +
      "evidence cannot decide it. Judge only from what is provided: the design tree, and when " +
      "given the generated Jetpack Compose source (the code this design exports to; use it for " +
      "questions about code) and a rendered picture. Do not " +
      "assume content that is not there. Answer `fail` only when the design clearly breaks the " +
      "rule. `confidence` is your probability (0 to 1) that the verdict is right. `nodeIds` names " +
      "the design-tree node ids a `fail` is about (empty otherwise). `reason` is one short " +
      "sentence a designer can act on. Reply with JSON only: " +
      "{\"verdicts\":[{\"ruleId\":…,\"verdict\":…,\"confidence\":…,\"nodeIds\":[…],\"reason\":…}]}, " +
      "one entry per rule."

  fun requestBody(
    model: String,
    platform: String,
    document: JsonObject,
    rules: List<UiBuilderGuidelineRule>,
    pngDataUrl: String?,
    source: String? = null,
  ): JsonObject = buildJsonObject {
    put("model", model)
    put("temperature", 0)
    putJsonArray("messages") {
      add(
        buildJsonObject {
          put("role", "system")
          put("content", SYSTEM_PROMPT)
        }
      )
      add(
        buildJsonObject {
          put("role", "user")
          putJsonArray("content") {
            add(
              buildJsonObject {
                put("type", "text")
                put("text", userText(platform, document, rules, pngDataUrl != null, source))
              }
            )
            if (pngDataUrl != null) {
              add(
                buildJsonObject {
                  put("type", "image_url")
                  putJsonObject("image_url") { put("url", pngDataUrl) }
                }
              )
            }
          }
        }
      )
    }
    putJsonObject("response_format") {
      put("type", "json_schema")
      putJsonObject("json_schema") {
        put("name", "guideline_verdicts")
        put("strict", true)
        put("schema", VERDICTS_SCHEMA)
      }
    }
  }

  fun userText(
    platform: String,
    document: JsonObject,
    rules: List<UiBuilderGuidelineRule>,
    hasPicture: Boolean,
    source: String? = null,
  ): String = buildString {
    val environment = document["environment"] as? JsonObject
    val width = environment?.get("widthDp")?.numberOrNull()
    val height = environment?.get("heightDp")?.numberOrNull()
    val theme = environment?.get("theme")?.stringOrNull()
    append("Platform: ").append(platform).append('\n')
    append("Design: ").append(document["title"]?.stringOrNull() ?: "untitled")
    if (width != null && height != null) {
      append(", ").append(width.toInt()).append('×').append(height.toInt()).append("dp")
    }
    if (theme != null) append(", ").append(theme).append(" theme")
    append('\n')
    append(if (hasPicture) "A rendered picture of the design is attached.\n" else "")
    append("\nDesign tree (node id, component, properties, modifiers; children by slot):\n")
    append(outline(document))
    if (source != null) {
      append("\nGenerated Jetpack Compose source (what this design exports to):\n```kotlin\n")
      if (source.length > MAX_SOURCE_CHARS) {
        append(source.take(MAX_SOURCE_CHARS)).append("\n// … ")
        append(source.length - MAX_SOURCE_CHARS).append(" more characters not shown\n")
      } else {
        append(source.trimEnd()).append('\n')
      }
      append("```\n")
    }
    append("\nRules:\n")
    rules.forEach { rule ->
      append("- ruleId: ").append(rule.id).append('\n')
      append("  guidance: ").append(rule.guidance).append('\n')
      append("  check: ").append(rule.check).append('\n')
    }
  }

  /**
   * The design as an indented tree a model can read: one line per node with its scalar properties
   * and modifiers, children under the slot that holds them. Asset bytes and bindings never appear.
   */
  fun outline(document: JsonObject, maxNodes: Int = 250): String {
    val nodes = document["nodes"] as? JsonObject ?: return "(no nodes)\n"
    val roots =
      (document["roots"] as? JsonArray)
        ?.mapNotNull { it.stringOrNull() }
        .orEmpty()
        .ifEmpty { nodes.keys.toList() }
    val seen = mutableSetOf<String>()
    val out = StringBuilder()
    fun visit(id: String, depth: Int) {
      if (seen.size >= maxNodes || !seen.add(id)) return
      val node = nodes[id] as? JsonObject ?: return
      out.append("  ".repeat(depth)).append("- ").append(id).append(": ")
      out.append(node["componentId"]?.stringOrNull() ?: "?")
      val properties = (node["properties"] as? JsonObject).orEmpty()
      val described =
        properties.entries.mapNotNull { (name, value) -> describeValue(value)?.let { "$name=$it" } }
      if (described.isNotEmpty()) out.append(" {").append(described.joinToString(", ")).append('}')
      val modifiers =
        (node["modifiers"] as? JsonArray).orEmpty().mapNotNull { describeModifier(it) }
      if (modifiers.isNotEmpty())
        out.append(" modifiers[").append(modifiers.joinToString(", ")).append(']')
      val events = (node["eventBindings"] as? JsonObject)?.keys.orEmpty()
      if (events.isNotEmpty()) out.append(" events[").append(events.joinToString(", ")).append(']')
      (node["component"] as? JsonObject)?.let { instance ->
        out.append(" instance of ").append(instance["componentKey"]?.stringOrNull() ?: "?")
        val arguments =
          (instance["arguments"] as? JsonObject).orEmpty().entries.mapNotNull { (name, value) ->
            describeValue(value)?.let { "$name=$it" }
          }
        if (arguments.isNotEmpty())
          out.append(" (").append(arguments.joinToString(", ")).append(')')
      }
      out.append('\n')
      (node["slots"] as? JsonObject).orEmpty().forEach { (slot, children) ->
        val ids = (children as? JsonArray).orEmpty().mapNotNull { it.stringOrNull() }
        if (ids.isEmpty()) return@forEach
        out.append("  ".repeat(depth + 1)).append(slot).append(":\n")
        ids.forEach { visit(it, depth + 2) }
      }
    }
    roots.forEach { visit(it, 0) }
    // A component's body hangs off `components[key].root`, not off the slots of the nodes that
    // place it, so it is outlined once here and each placement names it with "instance of".
    (document["components"] as? JsonObject).orEmpty().forEach { (key, component) ->
      val root = (component as? JsonObject)?.get("root")?.stringOrNull() ?: return@forEach
      if (root in seen) return@forEach
      val name = (component as JsonObject)["name"]?.stringOrNull()
      out.append("component ").append(key).append(name?.let { " ($it)" }.orEmpty()).append(":\n")
      visit(root, 1)
    }
    if (seen.size < nodes.size) out.append("(${nodes.size - seen.size} more nodes not shown)\n")
    return out.toString()
  }

  /** A typed protocol value (`{"type":"string","value":"OK"}`) as a short literal, or null. */
  private fun describeValue(value: JsonElement): String? {
    val obj = value as? JsonObject ?: return (value as? JsonPrimitive)?.contentOrNull?.take(60)
    val type = obj["type"]?.stringOrNull()
    return when (type) {
      "string" -> obj["value"]?.stringOrNull()?.let { "\"${it.take(60)}\"" }
      "bool",
      "int",
      "float",
      "double",
      "enum",
      "dp",
      "sp" -> obj["value"]?.let { primitiveText(it) }
      "color" -> obj["value"]?.let { primitiveText(it) } ?: obj["role"]?.stringOrNull()
      "null" -> null
      "binding" -> obj["value"]?.stringOrNull()?.let { "{$it}" }
      "list" -> (obj["values"] as? JsonArray)?.let { "[${it.size} items]" }
      else ->
        obj["value"]?.let { primitiveText(it) }
          ?: obj["role"]?.stringOrNull()
          ?: obj["iconName"]?.stringOrNull()
          ?: type
    }
  }

  private fun describeModifier(value: JsonElement): String? {
    val obj = value as? JsonObject ?: return null
    val type = obj["type"]?.stringOrNull() ?: return null
    val args =
      obj.entries
        .filter { it.key != "type" }
        .mapNotNull { (name, arg) -> primitiveText(arg)?.let { "$name=$it" } }
    return if (args.isEmpty()) type else "$type(${args.joinToString(", ")})"
  }

  private fun primitiveText(value: JsonElement): String? =
    (value as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.take(40)

  /**
   * The verdicts out of an OpenAI-style chat completion: `choices[0].message.content`, which may be
   * fenced or carry text around the JSON when a model ignores `response_format`.
   */
  fun parseCompletion(body: String): Result<List<UiBuilderGuidelineVerdict>> = runCatching {
    val completion = GUIDELINES_JSON.parseToJsonElement(body).jsonObject
    val content =
      completion["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.stringOrNull() ?: error("no message content in the completion")
    parseVerdicts(content)
  }

  fun parseVerdicts(content: String): List<UiBuilderGuidelineVerdict> {
    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    require(start >= 0 && end > start) { "no JSON object in: ${content.take(200)}" }
    return GUIDELINES_JSON.decodeFromString(
        UiBuilderGuidelineVerdicts.serializer(),
        content.substring(start, end + 1),
      )
      .verdicts
      .map { it.copy(confidence = it.confidence.coerceIn(0.0, 1.0)) }
  }

  /** OpenRouter's `{"error":{"message":…}}`, or the start of whatever came back. */
  fun errorMessage(body: String): String =
    runCatching {
      GUIDELINES_JSON.parseToJsonElement(body)
        .jsonObject["error"]
        ?.jsonObject
        ?.get("message")
        ?.stringOrNull()
    }
      .getOrNull() ?: body.take(200)

  private val VERDICTS_SCHEMA: JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    putJsonArray("required") { add(JsonPrimitive("verdicts")) }
    putJsonObject("properties") {
      putJsonObject("verdicts") {
        put("type", "array")
        putJsonObject("items") {
          put("type", "object")
          put("additionalProperties", false)
          putJsonArray("required") {
            listOf("ruleId", "verdict", "confidence", "nodeIds", "reason").forEach {
              add(JsonPrimitive(it))
            }
          }
          putJsonObject("properties") {
            putJsonObject("ruleId") { put("type", "string") }
            putJsonObject("verdict") {
              put("type", "string")
              put(
                "enum",
                buildJsonArray {
                  add(JsonPrimitive("pass"))
                  add(JsonPrimitive("fail"))
                  add(JsonPrimitive("not_applicable"))
                },
              )
            }
            putJsonObject("confidence") { put("type", "number") }
            putJsonObject("nodeIds") {
              put("type", "array")
              putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("reason") { put("type", "string") }
          }
        }
      }
    }
  }
}

private val GUIDELINES_JSON = Json {
  ignoreUnknownKeys = true
  explicitNulls = false
}

private fun JsonElement.stringOrNull(): String? =
  (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement.numberOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
