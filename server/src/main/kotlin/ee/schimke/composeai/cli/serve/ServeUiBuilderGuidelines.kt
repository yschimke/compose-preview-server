package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.CatalogGuidelines
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePicture
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePrompt
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRequest
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRule
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineServed
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineVerdict
import ee.schimke.composeai.uibuilder.guidelines.OPENROUTER_DECISIONS_URL
import ee.schimke.composeai.uibuilder.guidelines.body
import ee.schimke.composeai.uibuilder.guidelines.describe
import ee.schimke.composeai.uibuilder.guidelines.parseServed
import ee.schimke.composeai.uibuilder.guidelines.parseTriage
import ee.schimke.composeai.uibuilder.guidelines.prepare
import ee.schimke.composeai.uibuilder.guidelines.triageBody
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The `guidelines` half of `ui_builder_check_design`: a model judges a design against the Android
 * design guidance through OpenRouter, on the **operator's** key.
 *
 * Because the key is shared and every call costs money, the check is never on by default and never
 * open to everyone: [ServeUiBuilderGuidelineAccess] admits only the GitHub logins and organizations
 * the operator named. Every other caller gets the check reported as skipped, with why, and the rest
 * of `ui_builder_check_design` runs as before.
 *
 * The rules, the prompt and the reading of the reply are compose-ui-builder's
 * (`ee.schimke.composeai.uibuilder.guidelines`, in `ui-builder-export`): the editor's own-key
 * check, this lane and `ui_builder_guidelines_prompt` send the same [DesignGuidelineRequest].
 * [prepare] builds one and needs no key; [check] spends the key on it. Model findings are advisory:
 * a failed rule is a warning or a note, never an error, so `ok` is unchanged by them.
 */
class ServeUiBuilderGuidelines
internal constructor(
  config: ServeUiBuilderGuidelinesConfig,
  access: ServeUiBuilderGuidelineAccess,
  private val transport: OpenRouterTransport = OkHttpOpenRouterTransport(config.endpoint),
  /** OpenRouter's decisions endpoint, which Jev answers the evidence triage on. */
  private val decisions: OpenRouterTransport =
    OkHttpOpenRouterTransport(
      config.decisionsEndpoint,
      OkHttpClient.Builder()
        .callTimeout(TRIAGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .readTimeout(TRIAGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build(),
    ),
) {
  /**
   * The model and allow-list in force, swapped as one by [reconfigure] when `settings.json` is
   * published ([ServeSettings]), so a check never reads a new model with an old allow-list.
   */
  private class State(
    val config: ServeUiBuilderGuidelinesConfig,
    val access: ServeUiBuilderGuidelineAccess,
  )

  @Volatile private var state = State(config, access)

  private val config: ServeUiBuilderGuidelinesConfig
    get() = state.config

  private val access: ServeUiBuilderGuidelineAccess
    get() = state.access

  val model: String
    get() = config.model

  /**
   * Run on [model] for [users] and [orgs], with or without the evidence [triage], from now on; a
   * null [model] is the default. The key and the endpoints stay as they started. Throws, changing
   * nothing, when nobody would be allowed: the check spends the operator's key, so it is never open
   * to everyone, and turning it off is a restart (it is built only when someone is named).
   */
  internal fun reconfigure(
    model: String?,
    users: Set<String>,
    orgs: Set<String>,
    triage: Boolean = state.config.triage,
  ) {
    val current = state
    val next =
      current.config.copy(
        model = model ?: ServeUiBuilderGuidelinesConfig.DEFAULT_MODEL,
        allowedUsers = users,
        allowedOrgs = orgs,
        triage = triage,
      )
    state = State(next, current.access.allowing(users, orgs))
  }

  /** May [actor] spend the key? An org check can ask GitHub, so it runs on the I/O pool. */
  suspend fun allows(actor: AuthenticatedUiBuilderActor): Boolean =
    withContext(Dispatchers.IO) { access.allows(actor) }

  /** Who may run the check, for the startup banner. */
  fun describeAccess(): String = access.describe()

  /** Whether a check asks Jev which extra evidence would help before it draws anything. */
  val triageEnabled: Boolean
    get() = config.triage

  /**
   * Jev's probability, per evidence offer, that it would help judge [request]'s rules — a dark
   * render, a large-font render, the accessibility tree — or null when triage is off, the call
   * fails or it takes longer than [TRIAGE_TIMEOUT_MILLIS]. It never blocks a check: a null answer
   * just means nothing extra is drawn.
   */
  internal suspend fun triage(request: DesignGuidelineRequest): Map<String, Double>? {
    if (!config.triage) return null
    return withTimeoutOrNull(TRIAGE_TIMEOUT_MILLIS) {
      runCatching {
        withContext(Dispatchers.IO) {
          decisions.post(DesignGuidelinePrompt.triageBody(request).toString(), config.apiKey)
        }
      }
        .getOrNull()
        ?.takeIf { it.status in 200..299 }
        ?.let { DesignGuidelinePrompt.parseTriage(it.body) }
        ?.takeIf { it.isNotEmpty() }
    }
  }

  /**
   * Sends [request] to the model on the operator's key and reads the verdicts. The caller has
   * already decided [allows]; the request is the one [prepare] built, unchanged, so what was shown
   * is what was asked.
   */
  internal suspend fun check(request: DesignGuidelineRequest): UiBuilderGuidelineOutcome {
    // One snapshot for the whole check, so the model named in the result is the one asked.
    val config = state.config
    val platform =
      request.platform
        ?: return UiBuilderGuidelineOutcome.Skipped(
          "no guidelines are written for this catalog yet"
        )
    val asked = request.rules.asked
    if (asked.isEmpty()) {
      return UiBuilderGuidelineOutcome.Skipped(
        "no $platform guideline can be judged without a render"
      )
    }
    val response =
      try {
        withContext(Dispatchers.IO) {
          transport.post(
            DesignGuidelinePrompt.body(request, config.model).toString(),
            config.apiKey,
          )
        }
      } catch (e: java.io.IOException) {
        return UiBuilderGuidelineOutcome.Failed("OpenRouter could not be reached: ${e.message}")
      }
    if (response.status !in 200..299) {
      return UiBuilderGuidelineOutcome.Failed(
        "OpenRouter answered ${response.status}: ${DesignGuidelinePrompt.errorMessage(response.body)}"
      )
    }
    val verdicts =
      DesignGuidelinePrompt.parseCompletion(response.body).getOrElse {
        return UiBuilderGuidelineOutcome.Failed(
          "the model's answer was not the verdict list asked for: ${it.message}"
        )
      }
    // One verdict per rule asked. A rule the model skipped is unanswered, never counted as a pass.
    val answered = DesignGuidelinePrompt.answered(verdicts, asked)
    // The model that wrote the verdicts, which a router chose and the asked model does not name.
    val served = DesignGuidelinePrompt.parseServed(response.body)
    if (answered.isEmpty()) {
      return UiBuilderGuidelineOutcome.Failed(
        "${config.model} returned no verdict for any of the ${asked.size} rules asked"
      )
    }
    return UiBuilderGuidelineOutcome.Checked(
      model = config.model,
      rulesVersion = request.rules.version,
      asked = asked.map { it.id },
      verdicts = answered,
      visualSkipped = request.rules.visualSkipped,
      sourceAttached = request.sourceAttached,
      askedRules = asked,
      served = served,
    )
  }

  companion object {
    private const val MAX_NODES_PER_RULE = 5

    /** How long a check waits for Jev's evidence triage before going on without it. */
    const val TRIAGE_TIMEOUT_MILLIS: Long = 5_000

    /**
     * The request built from [guidelines] — the design's own catalog's rules — rather than the
     * bundled set. [rulesSource] is where the catalog's file is served; [profile] the Remote
     * Compose profile the design targets, narrowing rules written for one.
     */
    fun prepare(
      guidelines: CatalogGuidelines,
      designId: String?,
      revision: Int,
      document: JsonObject,
      pictures: List<DesignGuidelinePicture>,
      source: String?,
      profile: String?,
      rulesSource: String,
    ): DesignGuidelineRequest =
      DesignGuidelinePrompt.prepare(
        guidelines,
        designId,
        revision,
        document,
        pictures,
        source,
        profile,
        rulesSource,
      )

    /**
     * The request for [document] with [pictures] and [source], built from the bundled rules by the
     * library's own `prepare`, so this host and the editor build byte-identical requests. Pure and
     * keyless: `ui_builder_guidelines_prompt`, the prompt route and [check] all start here.
     */
    fun prepare(
      designId: String?,
      revision: Int,
      document: JsonObject,
      pictures: List<DesignGuidelinePicture>,
      source: String?,
      rules: DesignGuidelineRuleSet = DesignGuidelineRuleSet.Bundled,
    ): DesignGuidelineRequest =
      DesignGuidelinePrompt.prepare(rules, designId, revision, document, pictures, source)

    /**
     * [verdicts] as `ui_builder_check_design` findings: one per node a confident `fail` names (at
     * most a few), or one for the whole design, each quoting the guideline and its source.
     */
    internal fun findings(
      verdicts: List<DesignGuidelineVerdict>,
      asked: List<String>,
      nodeIds: Set<String>,
      model: String,
      minConfidence: Double,
      /**
       * The rules [asked] names: the catalog's own and the bundled set; see
       * [ServeCatalogGuidelines].
       */
      known: List<DesignGuidelineRule> = DesignGuidelineRuleSet.Bundled.rules,
      /** Who answered, when known: a router's served model, not the router. */
      served: DesignGuidelineServed? = null,
    ): List<UiBuilderCheckFindingV1> {
      val rules = known.filter { it.id in asked }.distinctBy { it.id }
      return DesignGuidelinePrompt.findings(verdicts, rules, nodeIds, minConfidence).flatMap {
        finding ->
        val rule = finding.rule
        (finding.nodeIds.take(MAX_NODES_PER_RULE).ifEmpty { listOf(null) }).map { nodeId ->
          UiBuilderCheckFindingV1(
            severity = if (rule.severity == SEVERITY_WARNING) SEVERITY_WARNING else SEVERITY_INFO,
            check = CHECK_GUIDELINES,
            code = rule.id,
            message =
              buildString {
                append(finding.reason)
                append(" Guideline: \"").append(rule.guidance).append("\" (")
                append(rule.source).append("). ")
                append(
                  served
                    ?.takeIf { it.model != null }
                    ?.describe(model)
                    ?.replaceFirstChar { it.uppercase() } ?: "Model $model"
                )
                append(", confidence ")
                  .append(String.format(java.util.Locale.ROOT, "%.2f", finding.confidence))
                  .append('.')
              },
            nodeId = nodeId,
          )
        }
      }
    }
  }

  /** Below this, a `fail` verdict is not reported: the model was guessing. */
  val minConfidence: Double
    get() = config.minConfidence
}

internal sealed interface UiBuilderGuidelineOutcome {
  /** The model's answers to the rules asked, ready to record and to turn into findings. */
  data class Checked(
    val model: String,
    val rulesVersion: Int,
    /** The rule ids asked about. */
    val asked: List<String>,
    /** One verdict per answered rule; a rule asked and missing here is unanswered. */
    val verdicts: List<DesignGuidelineVerdict>,
    /** Visual rules left out because there was no picture to show the model. */
    val visualSkipped: Int,
    /** Whether the generated Compose source went to the model with the design tree. */
    val sourceAttached: Boolean = false,
    /** The rules [asked] names, as the request carried them. */
    val askedRules: List<DesignGuidelineRule> = emptyList(),
    /** The model that actually answered, and how a router chose it. */
    val served: DesignGuidelineServed = DesignGuidelineServed(),
  ) : UiBuilderGuidelineOutcome {
    val unanswered: List<String>
      get() = asked - verdicts.map { it.ruleId }.toSet()
  }

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
  /** OpenRouter's decisions endpoint, for the Jev evidence triage. */
  val decisionsEndpoint: String = OPENROUTER_DECISIONS_URL,
  /**
   * Ask Jev (`typesafe/jev-1.13`, a fraction of a cent) which extra evidence would help before a
   * check: a dark render, a large-font render, the accessibility tree. Off with
   * `--ui-builder-guidelines-triage off`.
   */
  val triage: Boolean = true,
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

  /**
   * The generated `toString()` would print [apiKey] and [githubToken]: one `println(config)` or
   * `"$config"` in a log line or a `require` message away from a leak. Both are redacted here, and
   * only whether each is present is said.
   */
  override fun toString(): String =
    "ServeUiBuilderGuidelinesConfig(apiKey=$REDACTED, model=$model, " +
      "allowedUsers=$allowedUsers, allowedOrgs=$allowedOrgs, " +
      "githubToken=${if (githubToken == null) "null" else REDACTED}, endpoint=$endpoint, " +
      "decisionsEndpoint=$decisionsEndpoint, triage=$triage, minConfidence=$minConfidence)"

  companion object {
    private const val REDACTED = "<redacted>"

    /**
     * Called directly. `typesafe/jev-router` routes this check to the same model most of the time,
     * but picks at random, through dearer providers, and reads only the text, so it never chooses a
     * model for the pictures the visual rules are judged on. Any OpenRouter model id works.
     */
    const val DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash"
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
  private val memberships: ConcurrentHashMap<String, Pair<Boolean, Long>> = ConcurrentHashMap(),
) {
  private val users = allowedUsers.map { it.lowercase() }.toSet()
  private val orgs = allowedOrgs.map { it.lowercase() }.toSet()

  /**
   * The same membership lookup for a new allow-list. The cache is kept: an answer is about a person
   * and an org, whichever list asked for it.
   */
  fun allowing(allowedUsers: Set<String>, allowedOrgs: Set<String>): ServeUiBuilderGuidelineAccess =
    ServeUiBuilderGuidelineAccess(
      allowedUsers,
      allowedOrgs,
      isOrgMember,
      clock,
      cacheMillis,
      memberships,
    )

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
        // Routing metadata in the body when the model is a router, so the record can name the
        // model that answered rather than the router it was sent to.
        .header("X-OpenRouter-Metadata", "enabled")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    return client.newCall(request).execute().use {
      OpenRouterTransport.Response(it.code, it.body.string())
    }
  }
}

/**
 * The guidelines model, allow-list and evidence triage as live settings ([ServeSettings]):
 * publishing `settings.json` swaps them on the running check, together. Refused when the new
 * allow-list names nobody, since the check is never open to everyone and turning it off needs a
 * restart.
 */
internal class ServeGuidelinesLiveSettings(private val guidelines: ServeUiBuilderGuidelines) :
  ServeLiveSettings {
  override val envs = setOf(MODEL, USERS, ORGS, TRIAGE)

  override fun check(values: Map<String, String?>): String? =
    if (names(values[USERS]).isEmpty() && names(values[ORGS]).isEmpty()) {
      "uiBuilder.guidelines needs users or orgs while the check is on: it spends this box's " +
        "OpenRouter key, so it is never open to everyone (remove the key from .env to turn it off)"
    } else null

  override fun apply(values: Map<String, String?>) {
    guidelines.reconfigure(
      values[MODEL],
      names(values[USERS]),
      names(values[ORGS]),
      // As `--ui-builder-guidelines-triage` reads it: anything but `off` is on.
      triage = values[TRIAGE]?.trim()?.lowercase() != "off",
    )
    System.err.println(
      "serve: ui-builder guidelines check now on ${guidelines.model} for " +
        guidelines.describeAccess()
    )
  }

  private fun names(value: String?): Set<String> =
    value.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()

  private companion object {
    const val MODEL = "SERVE_UI_BUILDER_GUIDELINES_MODEL"
    const val USERS = "SERVE_UI_BUILDER_GUIDELINES_USERS"
    const val ORGS = "SERVE_UI_BUILDER_GUIDELINES_ORGS"
    const val TRIAGE = "SERVE_UI_BUILDER_GUIDELINES_TRIAGE"
  }
}
