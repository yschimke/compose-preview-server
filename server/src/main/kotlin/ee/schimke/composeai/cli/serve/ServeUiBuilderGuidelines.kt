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
 * The `guidelines` half of `ui_builder_check_design`: a model judges a design against Android
 * design guidance through OpenRouter on the operator's key. Since the key is shared and costs
 * money, it is never on by default and only [ServeUiBuilderGuidelineAccess]-admitted logins and
 * orgs may run it; others get it reported as skipped.
 *
 * Rules, prompt and reply parsing are compose-ui-builder's
 * (`ee.schimke.composeai.uibuilder.guidelines`); the editor's own-key check, this lane and
 * `ui_builder_guidelines_prompt` send the same [DesignGuidelineRequest]. [prepare] needs no key;
 * [check] spends it. Findings are advisory (warnings or notes), so `ok` is unaffected.
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
   * Model and allow-list in force, swapped together by [reconfigure] when `settings.json` is
   * published ([ServeSettings]).
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
   * From now on, run on [model] (null = default) for [users] and [orgs], with or without [triage].
   * Key and endpoints are fixed. Throws, changing nothing, when nobody would be allowed: the check
   * is never open to everyone, and disabling it needs a restart.
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
   * A triage model's probability, per evidence offer (dark render, large-font render, accessibility
   * tree), that it would help judge [request]; null when off, failed or slower than
   * [TRIAGE_TIMEOUT_MILLIS]. Never blocks a check.
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
   * Send [request] (as [prepare] built it, so what was shown is what was asked) to the model on the
   * operator's key and read the verdicts. The caller has already checked [allows].
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
     * The request built from the design's own catalog [guidelines] rather than the bundled set.
     * [rulesSource] is where the catalog's file is served; [profile] is the targeted Remote Compose
     * profile.
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
     * The request for [document] from the bundled rules via the library's own `prepare`, so host
     * and editor build byte-identical requests. Pure and keyless.
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
     * [verdicts] as findings: one per node a confident `fail` names (at most a few), or one for the
     * whole design, each quoting the guideline and source.
     */
    internal fun findings(
      verdicts: List<DesignGuidelineVerdict>,
      asked: List<String>,
      nodeIds: Set<String>,
      model: String,
      minConfidence: Double,
      /** The rules [asked] names: the catalog's and the bundled set ([ServeCatalogGuidelines]). */
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
 * `--ui-builder-guidelines-*` plus the key from the environment, never the command line (visible in
 * process listings).
 */
internal data class ServeUiBuilderGuidelinesConfig(
  val apiKey: String,
  val model: String = DEFAULT_MODEL,
  val allowedUsers: Set<String> = emptySet(),
  val allowedOrgs: Set<String> = emptySet(),
  /**
   * A token able to read the named orgs' members, so private memberships count; without one only
   * public memberships are visible.
   */
  val githubToken: String? = null,
  val endpoint: String = OPENROUTER_CHAT_COMPLETIONS,
  /** OpenRouter's decisions endpoint, for the Jev evidence triage. */
  val decisionsEndpoint: String = OPENROUTER_DECISIONS_URL,
  /**
   * Ask a cheap triage model which extra evidence would help before a check. Off with
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
   * Redacts [apiKey] and [githubToken], which a generated `toString()` would leak into any log
   * line.
   */
  override fun toString(): String =
    "ServeUiBuilderGuidelinesConfig(apiKey=$REDACTED, model=$model, " +
      "allowedUsers=$allowedUsers, allowedOrgs=$allowedOrgs, " +
      "githubToken=${if (githubToken == null) "null" else REDACTED}, endpoint=$endpoint, " +
      "decisionsEndpoint=$decisionsEndpoint, triage=$triage, minConfidence=$minConfidence)"

  companion object {
    private const val REDACTED = "<redacted>"

    /**
     * Called directly rather than via a router, which picks models at random and ignores pictures.
     * Any OpenRouter model id works.
     */
    const val DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash"
    const val OPENROUTER_CHAT_COMPLETIONS = "https://openrouter.ai/api/v1/chat/completions"
    const val API_KEY_ENV = "SERVE_UI_BUILDER_GUIDELINES_OPENROUTER_KEY"
    const val GITHUB_TOKEN_ENV = "SERVE_UI_BUILDER_GUIDELINES_GITHUB_TOKEN"
  }
}

/**
 * Who may spend the operator's key: a login on [allowedUsers], a member of [allowedOrgs], or the
 * operator token. An agent under a grant counts as its approver
 * ([AuthenticatedUiBuilderActor.onBehalfOfActorId]). Membership ([isOrgMember]) is cached for
 * [cacheMillis].
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

  /** The same lookup for a new allow-list, keeping the cache (answers are per person and org). */
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
     * GitHub's answer: `GET /orgs/{org}/members/{login}` is 204 for a member (private memberships
     * visible with a member's token), falling back to the public-members list. Any failure is a no.
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
 * Guidelines model, allow-list and triage as live settings ([ServeSettings]), swapped together.
 * Refused when the new allow-list names nobody.
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
