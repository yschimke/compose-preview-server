package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePicture
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePrompt
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRequest
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineVerdict
import ee.schimke.composeai.uibuilder.guidelines.body
import ee.schimke.composeai.uibuilder.guidelines.prepare
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
  private val config: ServeUiBuilderGuidelinesConfig,
  private val access: ServeUiBuilderGuidelineAccess,
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
   * Sends [request] to the model on the operator's key and reads the verdicts. The caller has
   * already decided [allows]; the request is the one [prepare] built, unchanged, so what was shown
   * is what was asked.
   */
  internal suspend fun check(request: DesignGuidelineRequest): UiBuilderGuidelineOutcome {
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
    )
  }

  companion object {
    private const val MAX_NODES_PER_RULE = 5

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
    ): List<UiBuilderCheckFindingV1> {
      val rules = DesignGuidelineRuleSet.Bundled.rules.filter { it.id in asked }
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
                append(rule.source).append("). Model ").append(model)
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
