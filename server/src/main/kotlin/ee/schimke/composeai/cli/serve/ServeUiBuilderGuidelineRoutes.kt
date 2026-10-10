package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePrompt
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRecord
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRequest
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRule
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import ee.schimke.composeai.uibuilder.guidelines.describe
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A design's latest guidelines result as `ui_builder_get_guidelines` answers it: the record, the
 * findings it implies against this host's rules, and whether the design has moved on since it was
 * checked.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderGuidelinesV1(
  @EncodeDefault val schema: String = UI_BUILDER_GUIDELINES_SCHEMA,
  val summary: String,
  val designId: String,
  /** The design's current revision. */
  val currentRevision: Long,
  /** True when [record] judged an earlier revision than [currentRevision]. */
  val stale: Boolean = false,
  /** Null when nobody has recorded a result for this design. */
  val record: DesignGuidelineRecord? = null,
  /** The rules [record] judged broken, as `ui_builder_check_design` reports them. */
  val findings: List<UiBuilderCheckFindingV1> = emptyList(),
  /** Rules asked and not answered: unchecked, not passed. */
  val unanswered: List<String> = emptyList(),
) {
  companion object {
    fun of(
      designId: String,
      currentRevision: Long,
      nodeIds: Set<String>,
      record: DesignGuidelineRecord?,
      /** The rules the record may name: its catalog's own, then the bundled set. */
      known: List<DesignGuidelineRule> = DesignGuidelineRuleSet.Bundled.rules,
      /**
       * The version of the rules this design is checked against now. A record against another
       * version is stale even at the current revision: its verdicts answer rules that changed.
       */
      rulesVersion: Int? = null,
    ): UiBuilderGuidelinesV1 {
      if (record == null) {
        return UiBuilderGuidelinesV1(
          summary =
            "No guidelines result is recorded for this design. Read the prompt with " +
              "${ServeUiBuilderMcp.GUIDELINES_PROMPT}, judge it, and record the verdicts with " +
              "${ServeUiBuilderMcp.RECORD_GUIDELINES}.",
          designId = designId,
          currentRevision = currentRevision,
        )
      }
      val rulesChanged = rulesVersion != null && record.rulesVersion != rulesVersion
      val stale = record.revision < currentRevision || rulesChanged
      val findings =
        ServeUiBuilderGuidelines.findings(
          record.verdicts,
          record.asked,
          nodeIds,
          record.model,
          DesignGuidelinePrompt.DEFAULT_MIN_CONFIDENCE,
          known = known,
          served = record.served,
        )
      val unanswered = record.asked - record.verdicts.map { it.ruleId }.toSet()
      val broken = findings.map { it.code }.distinct().size
      return UiBuilderGuidelinesV1(
        summary =
          buildString {
            append("Revision ").append(record.revision).append(", ")
            append(
              record.served.takeIf { it.model != null }?.describe(record.model) ?: record.model
            )
            record.ranBy?.let { append(", run by ").append(it) }
            append(": ")
            append(
              if (broken == 0) "${record.verdicts.size} guideline(s) answered, none broken."
              else "$broken of ${record.verdicts.size} guideline(s) look broken."
            )
            if (unanswered.isNotEmpty()) append(" ${unanswered.size} unanswered.")
            if (record.revision < currentRevision) {
              append(" The design is now at revision ").append(currentRevision)
              append("; check it again to update.")
            } else if (rulesChanged) {
              append(" The guidelines have changed since (version ").append(record.rulesVersion)
              append(" to ").append(rulesVersion).append("); check it again to update.")
            }
          },
        designId = designId,
        currentRevision = currentRevision,
        stale = stale,
        record = record,
        findings = findings,
        unanswered = unanswered,
      )
    }
  }
}

internal const val UI_BUILDER_GUIDELINES_SCHEMA = "compose-preview/ui-builder-guidelines/v1"

/**
 * The guidelines routes the editor's Issues panel reads and writes:
 *
 * - `GET …/guidelines/prompt` — the request a model is asked ([DesignGuidelineRequest]), with
 *   native pictures and the Compose source when the caller also holds the export capability, and
 *   without them (and without the visual rules) when not. It spends no key.
 * - `GET …/guidelines` — the design's latest recorded result ([DesignGuidelineRecord]), or 404.
 * - `POST …/guidelines` — record a result: a person's run on their own OpenRouter key. `ranBy`,
 *   `recordedAtEpochMillis` and `designId` come from the credential, the clock and the path, never
 *   from the body.
 * - `GET …/guidelines/access` — whether this account may run the check on this host's key
 *   (`serverCheck`), the model it would use, and why not. Never the key or who else may.
 * - `POST …/guidelines/check?revision=` — run the check on this host's key, as
 *   `ui_builder_check_design` runs it with `rendered: true`, record it as this account's run and
 *   answer with the record. 403 for an account the operator did not enable, 409 when [revision] is
 *   not the current one, 502 when the model call fails.
 *
 * Authorised as the review routes are: the route capability, then the design read as this actor.
 */
internal fun Route.installUiBuilderGuidelineRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  /**
   * Where results are kept; null when the host could not open it. The prompt and access routes are
   * stateless and stay mounted without it; reading, recording and running a check need it.
   */
  store: ServeUiBuilderGuidelineStore?,
  prompt:
    suspend (
      designId: String,
      revision: Long?,
      withRenders: Boolean,
      actor: AuthenticatedUiBuilderActor,
    ) -> DesignGuidelineRequest?,
  access: suspend (actor: AuthenticatedUiBuilderActor) -> ServeUiBuilderMcp.GuidelinesAccess,
  check:
    suspend (
      designId: String,
      revision: Long?,
      withRenders: Boolean,
      actor: AuthenticatedUiBuilderActor,
    ) -> ServeUiBuilderMcp.GuidelinesCheckResult,
  /** The rules a recorded result for this design may name: its catalog's own and the bundled. */
  rules: suspend (designId: String, actor: AuthenticatedUiBuilderActor) -> DesignGuidelineRuleSet =
    { _, _ ->
      DesignGuidelineRuleSet.Bundled
    },
  /** The design's current revision, which a recorded result may not be newer than. */
  currentRevision: suspend (designId: String, actor: AuthenticatedUiBuilderActor) -> Long? =
    { _, _ ->
      null
    },
) {
  get(UI_BUILDER_GUIDELINES_ACCESS_PATH) {
    val (actor, _) =
      call.authorizedGuidelinesDesign(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    val answer = access(actor)
    // The check route is mounted only with a store (below), so without one a server check is not
    // on offer, whatever the model and the actor allow.
    val serverCheck = answer.serverCheck && store != null
    val reason =
      if (answer.serverCheck && store == null) GUIDELINES_NO_RESULT_STORE else answer.reason
    call.respondGuidelines(
      GuidelinesAccessV1.serializer(),
      GuidelinesAccessV1(serverCheck, answer.model, reason),
    )
  }

  get(UI_BUILDER_GUIDELINES_PROMPT_PATH) {
    val (actor, designId) =
      call.authorizedGuidelinesDesign(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    val revision = call.request.queryParameters["revision"]?.toLongOrNull()
    val wantsRenders = call.request.queryParameters["rendered"] != "false"
    val mayRender =
      wantsRenders &&
        authorization.authorize(call, UiBuilderRouteCapability.EXPORT) is
          UiBuilderAuthorizationDecision.Authorized
    val request = prompt(designId, revision, mayRender, actor)
    if (request == null) {
      call.respondGuidelinesError(HttpStatusCode.NotFound, "no such design")
      return@get
    }
    call.respondGuidelines(DesignGuidelineRequest.serializer(), request)
  }

  if (store != null)
    installGuidelineResultRoutes(service, authorization, store, check, rules, currentRevision)
}

/** The routes that keep results: running a check, reading the latest, recording one. */
private fun Route.installGuidelineResultRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderGuidelineStore,
  check:
    suspend (
      designId: String,
      revision: Long?,
      withRenders: Boolean,
      actor: AuthenticatedUiBuilderActor,
    ) -> ServeUiBuilderMcp.GuidelinesCheckResult,
  rules: suspend (designId: String, actor: AuthenticatedUiBuilderActor) -> DesignGuidelineRuleSet,
  currentRevision: suspend (designId: String, actor: AuthenticatedUiBuilderActor) -> Long?,
) {
  post(UI_BUILDER_GUIDELINES_CHECK_PATH) {
    val (actor, designId) =
      call.authorizedGuidelinesDesign(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@post
    val revision = call.request.queryParameters["revision"]?.toLongOrNull()
    // The pictures are native renders, the export capability's to grant; without it the check runs
    // on the design tree and source alone, as the prompt route's would.
    val mayRender =
      authorization.authorize(call, UiBuilderRouteCapability.EXPORT) is
        UiBuilderAuthorizationDecision.Authorized
    when (val result = check(designId, revision, mayRender, actor)) {
      is ServeUiBuilderMcp.GuidelinesCheckResult.Recorded ->
        call.respondGuidelines(DesignGuidelineRecord.serializer(), result.record)
      is ServeUiBuilderMcp.GuidelinesCheckResult.Refused ->
        call.respondGuidelinesError(HttpStatusCode.Forbidden, result.reason)
      is ServeUiBuilderMcp.GuidelinesCheckResult.Stale ->
        call.respondGuidelinesError(
          HttpStatusCode.Conflict,
          "revision $revision is not the current one (${result.current}); check that one",
        )
      ServeUiBuilderMcp.GuidelinesCheckResult.NotFound ->
        call.respondGuidelinesError(HttpStatusCode.NotFound, "no such design")
      is ServeUiBuilderMcp.GuidelinesCheckResult.Unchecked ->
        call.respondGuidelinesError(HttpStatusCode.UnprocessableEntity, result.reason)
      is ServeUiBuilderMcp.GuidelinesCheckResult.Failed ->
        call.respondGuidelinesError(HttpStatusCode.BadGateway, result.reason)
    }
  }

  get(UI_BUILDER_GUIDELINES_PATH) {
    val (_, designId) =
      call.authorizedGuidelinesDesign(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    val record = withContext(Dispatchers.IO) { store.read(designId) }
    if (record == null) {
      call.respondGuidelinesError(HttpStatusCode.NotFound, "no guidelines result is recorded")
      return@get
    }
    call.respondGuidelines(DesignGuidelineRecord.serializer(), record)
  }

  post(UI_BUILDER_GUIDELINES_PATH) {
    val (actor, designId) =
      call.authorizedGuidelinesDesign(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@post
    val record = call.receiveGuidelinesBody(DesignGuidelineRecord.serializer()) ?: return@post
    val known = rules(designId, actor)
    val current = currentRevision(designId, actor)
    val result =
      withContext(Dispatchers.IO) {
        store.record(designId, ranBy = actor.actorId, record, known, currentRevision = current)
      }
    when (result) {
      is ServeUiBuilderGuidelineStore.GuidelineWriteResult.Refused ->
        call.respondGuidelinesError(HttpStatusCode.UnprocessableEntity, result.reason)
      is ServeUiBuilderGuidelineStore.GuidelineWriteResult.Failed ->
        call.respondGuidelinesError(HttpStatusCode.InternalServerError, result.reason)
      is ServeUiBuilderGuidelineStore.GuidelineWriteResult.Stored ->
        call.respondGuidelines(DesignGuidelineRecord.serializer(), result.record)
    }
  }
}

/**
 * `GET /api/ui-builder/v1/catalogs/{catalogId}/guidelines`: a builder catalog's own guidelines, as
 * it published them (`ui-builder.guidelines.json`), or 404 for a catalog that publishes none. A
 * prompt's `rules.source` links here. Read access only: the rules are the catalog's public
 * guidance.
 */
internal fun Route.installUiBuilderCatalogGuidelinesRoute(
  authorization: ServeUiBuilderAuthorization,
  guidelines: ServeCatalogGuidelines,
) {
  get(UI_BUILDER_CATALOG_GUIDELINES_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    when (authorization.authorize(call, UiBuilderRouteCapability.READ)) {
      is UiBuilderAuthorizationDecision.Authorized -> Unit
      UiBuilderAuthorizationDecision.Missing -> {
        call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        call.respondGuidelinesError(HttpStatusCode.Unauthorized, "authentication is required")
        return@get
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        call.respondGuidelinesError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return@get
      }
    }
    val catalogId = call.parameters["catalogId"].orEmpty()
    val loaded = guidelines.forCatalog(catalogId)
    if (loaded == null) {
      call.respondGuidelinesError(
        HttpStatusCode.NotFound,
        "catalog `$catalogId` publishes no guidelines",
      )
      return@get
    }
    call.respondText(loaded.raw, ContentType.Application.Json, HttpStatusCode.OK)
  }
}

private suspend fun ApplicationCall.authorizedGuidelinesDesign(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): Pair<AuthenticatedUiBuilderActor, String>? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor =
    when (val decision = authorization.authorize(this, capability)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondGuidelinesError(HttpStatusCode.Unauthorized, "authentication is required")
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondGuidelinesError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return null
      }
    }
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondGuidelinesError(HttpStatusCode.BadRequest, "a design id is required")
    return null
  }
  // Recording a result says something about the design without changing it, so a reader may do it
  // — as a reviewer may record a verdict. The route capability above is the write gate.
  if (service.designActions(actor, designId) == null) {
    respondGuidelinesError(HttpStatusCode.NotFound, "no such design")
    return null
  }
  return actor to designId
}

private suspend fun <T> ApplicationCall.receiveGuidelinesBody(serializer: KSerializer<T>): T? {
  val bytes =
    withContext(Dispatchers.IO) {
      receiveStream().use { it.readNBytes(MAX_GUIDELINES_BODY_BYTES + 1) }
    }
  if (bytes.size > MAX_GUIDELINES_BODY_BYTES) {
    respondGuidelinesError(HttpStatusCode.PayloadTooLarge, "the guidelines result is too large")
    return null
  }
  return try {
    GUIDELINES_ROUTE_JSON.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
  } catch (_: SerializationException) {
    respondGuidelinesError(HttpStatusCode.BadRequest, "the guidelines result could not be read")
    null
  } catch (_: IllegalArgumentException) {
    respondGuidelinesError(HttpStatusCode.BadRequest, "the guidelines result could not be read")
    null
  }
}

private suspend fun <T> ApplicationCall.respondGuidelines(serializer: KSerializer<T>, value: T) {
  respondText(
    GUIDELINES_ROUTE_JSON.encodeToString(serializer, value),
    ContentType.Application.Json,
    HttpStatusCode.OK,
  )
}

private suspend fun ApplicationCall.respondGuidelinesError(
  status: HttpStatusCode,
  message: String,
) {
  respondText(
    GUIDELINES_ROUTE_JSON.encodeToString(
      LinksErrorResponse.serializer(),
      LinksErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

internal const val UI_BUILDER_GUIDELINES_PATH = "/api/ui-builder/v1/designs/{designId}/guidelines"
internal const val UI_BUILDER_CATALOG_GUIDELINES_PATH =
  "/api/ui-builder/v1/catalogs/{catalogId}/guidelines"
internal const val UI_BUILDER_GUIDELINES_PROMPT_PATH =
  "/api/ui-builder/v1/designs/{designId}/guidelines/prompt"
internal const val UI_BUILDER_GUIDELINES_ACCESS_PATH =
  "/api/ui-builder/v1/designs/{designId}/guidelines/access"
internal const val UI_BUILDER_GUIDELINES_CHECK_PATH =
  "/api/ui-builder/v1/designs/{designId}/guidelines/check"

/** `GET …/guidelines/access`: may this account run the check on this host's key. */
@kotlinx.serialization.Serializable
internal data class GuidelinesAccessV1(
  val serverCheck: Boolean,
  val model: String? = null,
  val reason: String? = null,
)

// Larger than the review routes' 16 KiB: a whole rule set's verdicts, each with a sentence of
// reason, runs to 20 KB on the bigger platforms. The store caps the record again before it is kept.
private const val MAX_GUIDELINES_BODY_BYTES = 64 * 1024

/** Why the access route offers no server check on a host whose result store did not open. */
internal const val GUIDELINES_NO_RESULT_STORE =
  "this host could not open its guidelines result store, so it cannot run the check on its key; " +
    "run it in the editor with your own OpenRouter key"

private val GUIDELINES_ROUTE_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}
