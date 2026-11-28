package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The simulator surface a host wires in ([installSimulatorRoutes]). */
class ServeSimulator(
  val registry: SimulatorAppRegistry,
  val sessions: SimulatorSessions,
  val embedTokens: SimulatorEmbedTokens,
  /** Embed starts each client address may make per [embedWindowMillis]. */
  internal val embedStartsPerWindow: Int = 6,
  internal val embedWindowMillis: Long = 10 * 60_000L,
  private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
  private val embedStarts = ConcurrentHashMap<String, ArrayDeque<Long>>()
  @Volatile private var prunedAtMillis = 0L

  /** Addresses tracked now: only those with a start inside the window, once pruned. */
  internal val trackedAddresses: Int
    get() = embedStarts.size

  /**
   * Whether [address] may start another embedded session now; counts it when it may. Each address's
   * starts change atomically ([ConcurrentHashMap.compute]); at most once a minute every address
   * with no start left inside the window is dropped, so memory and the pruning work are bounded by
   * the addresses active within the window, however many have come and gone.
   */
  internal fun admitEmbedStart(address: String): Boolean {
    val now = clock()
    var admitted = false
    embedStarts.compute(address) { _, old ->
      val starts = old ?: ArrayDeque()
      while (starts.isNotEmpty() && now - starts.first() > embedWindowMillis) starts.removeFirst()
      if (starts.size < embedStartsPerWindow) {
        starts.addLast(now)
        admitted = true
      }
      starts
    }
    if (now - prunedAtMillis > PRUNE_INTERVAL_MILLIS) {
      prunedAtMillis = now
      for (key in embedStarts.keys) {
        embedStarts.computeIfPresent(key) { _, starts ->
          while (starts.isNotEmpty() && now - starts.first() > embedWindowMillis) starts
            .removeFirst()
          starts.takeIf { it.isNotEmpty() }
        }
      }
    }
    return admitted
  }

  override fun close() = sessions.close()

  private companion object {
    const val PRUNE_INTERVAL_MILLIS = 60_000L
  }
}

internal const val SIMULATOR_API = "/api/simulator/v1"
internal const val SIMULATOR_SECRET_HEADER = "X-Simulator-Session-Secret"

@Serializable
internal data class SimulatorStartRequestV1(
  val mode: String? = null,
  /** An embed's signed token ([SimulatorEmbedTokens]); absent for this server's own page. */
  val embedToken: String? = null,
  /** True when the request comes from `/simulator/embed/…`, so an anonymous embed is admitted. */
  val embed: Boolean = false,
)

@Serializable
internal data class SimulatorStartedV1(
  val schema: String = "compose-preview/simulator-session-started/v1",
  val sessionId: String,
  /**
   * The session's capability: send it as `?secret=` on the socket, or [SIMULATOR_SECRET_HEADER].
   */
  val secret: String,
  val appId: String,
  val mode: String,
  val devices: List<String>,
  val tier: String,
  val socketPath: String,
  val statusPath: String,
  val commandPath: String,
  val screenshotPath: String,
  val expiresAtEpochMillis: Long,
)

/** What a visitor may know about an app: nothing about its files. */
@Serializable
internal data class SimulatorAppSummaryV1(
  val appId: String,
  val label: String,
  val description: String? = null,
  val modes: List<String>,
  val packages: Map<String, String>,
  val embeddable: Boolean,
)

@Serializable
internal data class SimulatorServiceStatusV1(
  val schema: String = "compose-preview/simulator-status/v1",
  val enabled: Boolean,
  val available: Boolean,
  val code: String? = null,
  val reason: String? = null,
  val maxSessions: Int,
  val activeSessions: Int,
)

@Serializable internal data class SimulatorEmbedTokenRequestV1(val ttlSeconds: Long = 900)

@Serializable
internal data class SimulatorEmbedTokenV1(
  val token: String,
  val appId: String,
  val expiresAtEpochMillis: Long,
  /** The iframe's `src`, relative to this server's origin. */
  val embedPath: String,
)

private val SIM_JSON = Json {
  ignoreUnknownKeys = true
  encodeDefaults = true
  explicitNulls = false
}

private const val MAX_CONFIG_BYTES = 64 * 1024
private const val MAX_COMMAND_BYTES = 6 * 1024 * 1024

/**
 * The simulator's routes. Who may do what:
 *
 * |Route                                                                                    |Who                                                                                                                                    |
 * |-----------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------|
 * |`/admin/simulator/…` (register APKs, configure, mint embed tokens, list and end sessions)|the operator token                                                                                                                     |
 * |`GET /api/simulator/v1/status`, `GET …/apps`                                             |anyone; apps listed are the ones the caller could start                                                                                |
 * |`POST …/apps/{appId}/sessions`                                                           |the operator; a signed-in account for an app whose `access` is `signed-in`; an embed with a valid token, or of an `anonymous` embed app|
 * |`…/sessions/{id}` status, commands, screenshot, socket, `DELETE`                         |the session's secret, which only its starter holds                                                                                     |
 *
 * [isOperator] checks the admin token; [signedInActor] names the signed-in account, or null;
 * [clientAddress] keys embed rate limits; [enabled] is `features.simulator`.
 */
internal fun Route.installSimulatorRoutes(
  simulator: ServeSimulator,
  isOperator: (ApplicationCall) -> Boolean,
  signedInActor: suspend (ApplicationCall) -> String?,
  clientAddress: (ApplicationCall) -> String = { it.request.local.remoteAddress },
  enabled: Boolean = true,
  page: (title: String, body: String) -> String = ::simulatorPlainPage,
) {
  val registry = simulator.registry
  val sessions = simulator.sessions

  suspend fun ApplicationCall.json(status: HttpStatusCode, text: String) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(text, ContentType.Application.Json, status)
  }

  suspend fun ApplicationCall.problem(status: HttpStatusCode, code: String, reason: String) =
    json(
      status,
      buildJsonObject {
        put("code", code)
        put("reason", reason)
      }
        .toString(),
    )

  suspend fun ApplicationCall.registryResult(result: SimulatorAppRegistry.Result) =
    when (result) {
      is SimulatorAppRegistry.Result.Ok ->
        json(HttpStatusCode.OK, SIM_JSON.encodeToString(SimulatorAppV1.serializer(), result.app))
      is SimulatorAppRegistry.Result.Invalid ->
        problem(HttpStatusCode.BadRequest, "simulator_app_invalid", result.reason)
      SimulatorAppRegistry.Result.NotFound ->
        problem(HttpStatusCode.NotFound, "simulator_app_unknown", "no such app")
    }

  /** Operator routes answer 404 to anyone else, as every `/admin` surface here does. */
  suspend fun ApplicationCall.rejectNonOperator(): Boolean {
    if (isOperator(this)) return false
    respondText("not found", status = HttpStatusCode.NotFound)
    return true
  }

  fun ApplicationCall.device(): SimulatorDevice? = SimulatorDevice.of(parameters["device"])

  // --- Operator: the registry ---------------------------------------------------------------

  get("/admin/simulator/apps") {
    if (call.rejectNonOperator()) return@get
    val apps = withContext(Dispatchers.IO) { registry.list() }
    call.json(
      HttpStatusCode.OK,
      SIM_JSON.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(SimulatorAppV1.serializer()),
        apps,
      ),
    )
  }
  get("/admin/simulator/apps/{appId}") {
    if (call.rejectNonOperator()) return@get
    val app = withContext(Dispatchers.IO) { registry.get(call.parameters["appId"].orEmpty()) }
    if (app == null) call.problem(HttpStatusCode.NotFound, "simulator_app_unknown", "no such app")
    else call.json(HttpStatusCode.OK, SIM_JSON.encodeToString(SimulatorAppV1.serializer(), app))
  }
  put("/admin/simulator/apps/{appId}") {
    if (call.rejectNonOperator()) return@put
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_CONFIG_BYTES.toLong()) }
      }
        ?: return@put call.problem(
          HttpStatusCode.PayloadTooLarge,
          "simulator_config_too_large",
          "config too large",
        )
    val config = runCatching {
      SIM_JSON.decodeFromString(
        SimulatorAppConfig.serializer(),
        body.decodeToString().ifBlank { "{}" },
      )
    }
      .getOrElse {
        return@put call.problem(
          HttpStatusCode.BadRequest,
          "simulator_app_invalid",
          "invalid config: ${it.message}",
        )
      }
    call.registryResult(
      withContext(Dispatchers.IO) {
        registry.putConfig(call.parameters["appId"].orEmpty(), config)
      },
    )
  }
  delete("/admin/simulator/apps/{appId}") {
    if (call.rejectNonOperator()) return@delete
    val appId = call.parameters["appId"].orEmpty()
    sessions.all().filter { it.appId == appId }.forEach { sessions.close(it.id, "app deleted") }
    call.registryResult(withContext(Dispatchers.IO) { registry.delete(appId) })
  }
  put("/admin/simulator/apps/{appId}/apks/{device}") {
    if (call.rejectNonOperator()) return@put
    val device =
      call.device()
        ?: return@put call.problem(
          HttpStatusCode.BadRequest,
          "simulator_device_unknown",
          "device is phone or watch",
        )
    val bytes =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, SimulatorAppRegistry.MAX_APK_BYTES) }
      }
        ?: return@put call.problem(
          HttpStatusCode.PayloadTooLarge,
          "simulator_apk_too_large",
          "an APK may be at most ${SimulatorAppRegistry.MAX_APK_BYTES / (1024 * 1024)} MB",
        )
    call.registryResult(
      withContext(Dispatchers.IO) {
        registry.installApk(call.parameters["appId"].orEmpty(), device, bytes)
      },
    )
  }
  delete("/admin/simulator/apps/{appId}/apks/{device}") {
    if (call.rejectNonOperator()) return@delete
    val device =
      call.device()
        ?: return@delete call.problem(
          HttpStatusCode.BadRequest,
          "simulator_device_unknown",
          "device is phone or watch",
        )
    call.registryResult(
      withContext(Dispatchers.IO) {
        registry.removeApk(call.parameters["appId"].orEmpty(), device)
      },
    )
  }
  post("/admin/simulator/apps/{appId}/embed-tokens") {
    if (call.rejectNonOperator()) return@post
    val appId = call.parameters["appId"].orEmpty()
    val app =
      registry.get(appId)
        ?: return@post call.problem(HttpStatusCode.NotFound, "simulator_app_unknown", "no such app")
    if (!app.config.embed.enabled) {
      return@post call.problem(
        HttpStatusCode.Conflict,
        "simulator_embed_disabled",
        "$appId is not embeddable; set embed.enabled and embed.origins first",
      )
    }
    val body =
      withContext(Dispatchers.IO) { call.receiveStream().use { readCapped(it, 4096) } }
        ?: ByteArray(0)
    val request = runCatching {
      SIM_JSON.decodeFromString(
        SimulatorEmbedTokenRequestV1.serializer(),
        body.decodeToString().ifBlank { "{}" },
      )
    }
      .getOrElse {
        return@post call.problem(
          HttpStatusCode.BadRequest,
          "simulator_token_invalid",
          "${it.message}",
        )
      }
    val ttl = request.ttlSeconds.coerceIn(60, SimulatorEmbedTokens.MAX_TTL_SECONDS)
    val token = simulator.embedTokens.mint(appId, ttl)
    call.json(
      HttpStatusCode.Created,
      SIM_JSON.encodeToString(
        SimulatorEmbedTokenV1.serializer(),
        SimulatorEmbedTokenV1(
          token = token,
          appId = appId,
          expiresAtEpochMillis = System.currentTimeMillis() + ttl * 1000,
          embedPath = "/simulator/embed/$appId?token=$token",
        ),
      ),
    )
  }
  get("/admin/simulator/sessions") {
    if (call.rejectNonOperator()) return@get
    val now = System.currentTimeMillis()
    call.json(
      HttpStatusCode.OK,
      SIM_JSON.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(SimulatorSessionStatusV1.serializer()),
        sessions.all().map { it.status(now, sessions.queuePosition(it)) },
      ),
    )
  }
  get("/admin/simulator/capacity") {
    if (call.rejectNonOperator()) return@get
    call.json(
      HttpStatusCode.OK,
      SIM_JSON.encodeToString(SimulatorCapacityV1.serializer(), sessions.capacityStatus()),
    )
  }
  delete("/admin/simulator/sessions/{sessionId}") {
    if (call.rejectNonOperator()) return@delete
    val id = call.parameters["sessionId"].orEmpty()
    if (sessions.get(id) == null)
      return@delete call.problem(
        HttpStatusCode.NotFound,
        "simulator_session_unknown",
        "no such session",
      )
    sessions.close(id, "ended by the operator")
    call.respondText("", status = HttpStatusCode.NoContent)
  }

  // --- Anyone: what is offered ---------------------------------------------------------------

  get("$SIMULATOR_API/status") {
    val availability = if (enabled) sessions.runtime.availability() else null
    val unavailable = availability as? SimulatorRuntime.Availability.Unavailable
    call.json(
      HttpStatusCode.OK,
      SIM_JSON.encodeToString(
        SimulatorServiceStatusV1.serializer(),
        SimulatorServiceStatusV1(
          enabled = enabled,
          available = availability == SimulatorRuntime.Availability.Ready,
          code = if (!enabled) ServeFeatureFlags.SIMULATOR_DISABLED else unavailable?.code,
          reason = if (!enabled) "this server does not run the simulator" else unavailable?.reason,
          maxSessions = sessions.maxSessions,
          activeSessions = sessions.all().count { it.stage.holdsMemory },
        ),
      ),
    )
  }

  get("$SIMULATOR_API/apps") {
    val operator = isOperator(call)
    val signedIn = !operator && signedInActor(call) != null
    val apps =
      withContext(Dispatchers.IO) { registry.list() }
        .filter { it.modes.isNotEmpty() }
        .filter {
          operator || (signedIn && it.config.access == SimulatorAppConfig.ACCESS_SIGNED_IN)
        }
    call.json(
      HttpStatusCode.OK,
      SIM_JSON.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(SimulatorAppSummaryV1.serializer()),
        apps.map(::summary),
      ),
    )
  }

  // --- Starting a session ----------------------------------------------------------------------

  post("$SIMULATOR_API/apps/{appId}/sessions") {
    if (!enabled) {
      return@post call.problem(
        HttpStatusCode.ServiceUnavailable,
        ServeFeatureFlags.SIMULATOR_DISABLED,
        "this server does not run the simulator",
      )
    }
    val appId = call.parameters["appId"].orEmpty()
    val body =
      withContext(Dispatchers.IO) { call.receiveStream().use { readCapped(it, 8192) } }
        ?: return@post call.problem(
          HttpStatusCode.PayloadTooLarge,
          "simulator_request_too_large",
          "request too large",
        )
    val request = runCatching {
      SIM_JSON.decodeFromString(
        SimulatorStartRequestV1.serializer(),
        body.decodeToString().ifBlank { "{}" },
      )
    }
      .getOrElse {
        return@post call.problem(
          HttpStatusCode.BadRequest,
          "simulator_request_invalid",
          "${it.message}",
        )
      }
    val app = withContext(Dispatchers.IO) { registry.get(appId) }
    // Not revealing which apps exist: an app the caller may not start is "unknown".
    val unknown: suspend () -> Unit = {
      call.problem(HttpStatusCode.NotFound, "simulator_app_unknown", "no app $appId you can start")
    }
    if (app == null) return@post unknown()
    val embedded = request.embed || request.embedToken != null
    val (kind, tier, owner) =
      when {
        embedded -> {
          val policy = app.config.embed
          val tokenOk = simulator.embedTokens.verify(request.embedToken, appId)
          if (!policy.enabled || !(tokenOk || policy.anonymous)) return@post unknown()
          if (!simulator.admitEmbedStart(clientAddress(call))) {
            call.response.headers.append(HttpHeaders.RetryAfter, "600")
            return@post call.problem(
              HttpStatusCode.TooManyRequests,
              "simulator_rate_limited",
              "too many simulator sessions started from here; try again later",
            )
          }
          Triple(SimulatorSessions.Kind.EMBED, SimulatorControlPolicy.Tier.VIEWER, null)
        }
        isOperator(call) ->
          Triple(SimulatorSessions.Kind.PAGE, SimulatorControlPolicy.Tier.OPERATOR, "operator")
        app.config.access == SimulatorAppConfig.ACCESS_SIGNED_IN ->
          when (val actor = signedInActor(call)) {
            null ->
              return@post call.problem(
                HttpStatusCode.Unauthorized,
                "simulator_sign_in_required",
                "sign in to run $appId",
              )
            else -> Triple(SimulatorSessions.Kind.PAGE, SimulatorControlPolicy.Tier.VIEWER, actor)
          }
        else -> return@post unknown()
      }
    val mode =
      (request.mode?.let {
        SimulatorMode.of(it)
          ?: return@post call.problem(
            HttpStatusCode.BadRequest,
            "simulator_mode_unknown",
            "mode is one of ${SimulatorMode.entries.map { m -> m.id }}",
          )
      })
        ?: app.modes.firstNotNullOfOrNull { SimulatorMode.of(it) }
        ?: return@post call.problem(
          HttpStatusCode.UnprocessableEntity,
          "simulator_mode_unavailable",
          "$appId has no APK",
        )
    when (
      val started = withContext(Dispatchers.IO) { sessions.start(appId, mode, kind, tier, owner) }
    ) {
      is SimulatorSessions.Started.Ok -> {
        val s = started.session
        call.json(
          HttpStatusCode.Created,
          SIM_JSON.encodeToString(
            SimulatorStartedV1.serializer(),
            SimulatorStartedV1(
              sessionId = s.id,
              secret = s.secret,
              appId = s.appId,
              mode = s.mode.id,
              devices = s.devices.map { it.id },
              tier = s.tier.id,
              socketPath = "$SIMULATOR_API/sessions/${s.id}/ws",
              statusPath = "$SIMULATOR_API/sessions/${s.id}",
              commandPath = "$SIMULATOR_API/sessions/${s.id}/commands",
              screenshotPath = "$SIMULATOR_API/sessions/${s.id}/screenshot",
              expiresAtEpochMillis = s.deadlineMillis,
            ),
          ),
        )
      }
      is SimulatorSessions.Started.Invalid ->
        call.problem(HttpStatusCode.fromValue(started.status), started.code, started.reason)
      is SimulatorSessions.Started.Busy -> {
        call.response.headers.append(HttpHeaders.RetryAfter, "60")
        call.problem(HttpStatusCode.TooManyRequests, "simulator_busy", started.reason)
      }
      is SimulatorSessions.Started.Unavailable ->
        call.problem(HttpStatusCode.ServiceUnavailable, started.code, started.reason)
    }
  }

  // --- A session, by its secret ----------------------------------------------------------------

  fun ApplicationCall.session(): SimulatorSessions.Session? =
    sessions.authorized(
      parameters["sessionId"].orEmpty(),
      request.headers[SIMULATOR_SECRET_HEADER] ?: request.queryParameters["secret"],
    )

  get("$SIMULATOR_API/sessions/{sessionId}") {
    val session =
      call.session()
        ?: return@get call.problem(
          HttpStatusCode.NotFound,
          "simulator_session_unknown",
          "no such session",
        )
    sessions.touch(session)
    call.json(
      HttpStatusCode.OK,
      SIM_JSON.encodeToString(
        SimulatorSessionStatusV1.serializer(),
        session.status(System.currentTimeMillis(), sessions.queuePosition(session)),
      ),
    )
  }
  delete("$SIMULATOR_API/sessions/{sessionId}") {
    val session =
      call.session()
        ?: return@delete call.problem(
          HttpStatusCode.NotFound,
          "simulator_session_unknown",
          "no such session",
        )
    withContext(Dispatchers.IO) { sessions.close(session.id, "ended by its viewer") }
    call.respondText("", status = HttpStatusCode.NoContent)
  }
  post("$SIMULATOR_API/sessions/{sessionId}/commands") {
    val session =
      call.session()
        ?: return@post call.problem(
          HttpStatusCode.NotFound,
          "simulator_session_unknown",
          "no such session",
        )
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { readCapped(it, MAX_COMMAND_BYTES.toLong()) }
      }
        ?: return@post call.problem(
          HttpStatusCode.PayloadTooLarge,
          "simulator_command_too_large",
          "command too large",
        )
    val message = runCatching {
      SIM_JSON.parseToJsonElement(body.decodeToString()).jsonObject
    }
      .getOrElse {
        return@post call.problem(
          HttpStatusCode.BadRequest,
          "simulator_command_invalid",
          "a command is a JSON object",
        )
      }
    val reply = withContext(Dispatchers.IO) { runCommand(simulator, session, message) }
    call.json(
      if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull == true) HttpStatusCode.OK
      else HttpStatusCode.UnprocessableEntity,
      reply.toString(),
    )
  }
  get("$SIMULATOR_API/sessions/{sessionId}/screenshot") {
    val session =
      call.session()
        ?: return@get call.problem(
          HttpStatusCode.NotFound,
          "simulator_session_unknown",
          "no such session",
        )
    val device =
      call.request.queryParameters["device"]?.let(SimulatorDevice::of) ?: session.devices.first()
    if (device in session.devices) withContext(Dispatchers.IO) { sessions.wake(session) }
    if (device !in session.devices || session.stage != SimulatorStage.LIVE) {
      return@get call.problem(
        HttpStatusCode.Conflict,
        "simulator_not_live",
        "the session is ${session.stage.id}",
      )
    }
    sessions.touch(session)
    val frame =
      withContext(Dispatchers.IO) { runCatching { sessions.frame(session, device) }.getOrNull() }
        ?: return@get call.problem(
          HttpStatusCode.BadGateway,
          "simulator_no_frame",
          "the simulator returned no screenshot",
        )
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondBytes(Base64.getDecoder().decode(frame.pngBase64), ContentType.Image.PNG)
  }

  webSocket("$SIMULATOR_API/sessions/{sessionId}/ws") {
    val session = call.session()
    if (session == null) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no such session"))
      return@webSocket
    }
    serveSimulatorSocket(simulator, session)
  }

  // --- Pages -----------------------------------------------------------------------------------

  get("/simulator/{appId}") {
    val appId = call.parameters["appId"].orEmpty()
    if (!SimulatorAppRegistry.isAppId(appId))
      return@get call.respondText("not found", status = HttpStatusCode.NotFound)
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    // A static shell: it learns nothing about the app until its start request is authorized, so
    // the page itself reveals nothing, and is the same for every caller.
    call.respondText(
      page("Simulator", simulatorViewerBody(appId, embed = false, allowedParents = emptyList())),
      ContentType.Text.Html,
    )
  }

  get("/simulator/embed/{appId}") {
    val appId = call.parameters["appId"].orEmpty()
    val app = if (SimulatorAppRegistry.isAppId(appId)) registry.get(appId) else null
    val policy = app?.config?.embed
    if (app == null || policy == null || !policy.enabled || !enabled) {
      return@get call.respondText("not found", status = HttpStatusCode.NotFound)
    }
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.response.headers.append("Referrer-Policy", "no-referrer")
    // Set here so the server-wide policy (frame-ancestors 'self') is not applied: only the app's
    // listed origins may frame it. The edge proxy's X-Frame-Options skips this path.
    call.response.headers.append(ServePagePolicy.HEADER, embedPolicy(policy.origins))
    call.respondText(
      page(
        app.config.label ?: appId,
        simulatorViewerBody(
          appId,
          embed = true,
          allowedParents = policy.origins,
          label = app.config.label ?: appId,
          modes = app.modes,
        ),
      ),
      ContentType.Text.Html,
    )
  }
}

/**
 * The signed-in account a UI-builder authorization names, or null. Authorized is not signed in: on
 * a `--public` box a signed-out visitor is authorized to read as
 * [ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID], and the reserved "everyone" actors stand for
 * nobody in particular. Neither may start an app whose access is `signed-in`; a visitor runs apps
 * only through an embed and its limits.
 */
internal fun simulatorSignedInActor(decision: UiBuilderAuthorizationDecision?): String? {
  val authorized = decision as? UiBuilderAuthorizationDecision.Authorized ?: return null
  if (ServeUiBuilderVisibility.isReservedActor(authorized.actorId)) return null
  return authorized.actor.actorId.takeUnless { ServeUiBuilderVisibility.isReservedActor(it) }
}

/** The embed page's whole CSP: this origin's script and socket, framed only by [origins]. */
internal fun embedPolicy(origins: List<String>): String =
  listOf(
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob:",
      "connect-src 'self'",
      "frame-src 'none'",
      "object-src 'none'",
      "base-uri 'none'",
      "form-action 'none'",
      "frame-ancestors ${origins.joinToString(" ")}",
    )
    .joinToString("; ")

private fun summary(app: SimulatorAppV1) =
  SimulatorAppSummaryV1(
    appId = app.appId,
    label = app.config.label ?: app.appId,
    description = app.config.description,
    modes = app.modes,
    packages = app.apks.associate { it.device to it.packageName },
    embeddable = app.config.embed.enabled,
  )

/**
 * One command from a client — the socket's or `POST …/commands` — checked against the policy for
 * the session's tier and sent. The reply is `{"ok":true,"result":…}` or `{"ok":false,"error":…}`.
 */
internal fun runCommand(
  simulator: ServeSimulator,
  session: SimulatorSessions.Session,
  message: JsonObject,
): JsonObject {
  val id = message["id"]?.takeIf { it is JsonPrimitive && (it.longOrNull != null) }
  fun reply(ok: Boolean, body: Pair<String, JsonElement>) =
    JsonObject(
      buildMap {
        put("type", JsonPrimitive("result"))
        id?.let { put("id", it) }
        put("ok", JsonPrimitive(ok))
        put(body.first, body.second)
      },
    )
  return when (
    val decision = SimulatorControlPolicy.check(message, session.devices, session.tier)
  ) {
    is SimulatorControlPolicy.Decision.Refused ->
      reply(false, "error" to JsonPrimitive(decision.reason))
    is SimulatorControlPolicy.Decision.Allowed ->
      try {
        reply(true, "result" to (simulator.sessions.command(session, decision) ?: JsonNull))
      } catch (e: SimulatorCommandException) {
        val text =
          SimulatorControlPolicy.redact(
            JsonPrimitive(e.message ?: "failed"),
            simulator.sessions.runtime.hostRoots(),
          )
        reply(false, "error" to text)
      }
  }
}

/**
 * The session's socket. Server → client: `stage` (the [SimulatorSessionStatusV1] fields) whenever
 * the stage changes and once a second while it is on its way, `lane` each time the session becomes
 * live (devices, displays, whether it has a launcher, the commands this tier may send), `frame` per
 * device (`composeai-stream/1`'s frame plus `device`, only when the picture changed), `event`
 * (filtered by [SimulatorControlPolicy.event]), `result` for a command with an `id` or one refused,
 * and `ended`. Client → server: `{"type":"command", "cmd":…, "device"?:…, "id"?:…, …}`,
 * `{"type":"visibility","visible":false}`, `{"type":"requestFrame"}`, `{"type":"wake"}`.
 *
 * Its loop is what keeps an open-but-unused page cheap: frames are pulled only while the session is
 * live and this page visible, at [SimulatorSessions.frameIntervalFor]'s rate (fast after input, a
 * frame a second when left alone); a hidden page reports it, and the pool pauses the session; any
 * input, or the page showing again, wakes it.
 */
private suspend fun DefaultWebSocketServerSession.serveSimulatorSocket(
  simulator: ServeSimulator,
  session: SimulatorSessions.Session,
) {
  val sessions = simulator.sessions
  val roots = sessions.runtime.hostRoots()
  val events = Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val listener: (SimulatorDevice, JsonObject) -> Unit = { device, event ->
    SimulatorControlPolicy.event(event, session.tier, roots)?.let {
      events.trySend(
        buildJsonObject {
          put("type", "event")
          put("device", device.id)
          put("event", it)
        }
          .toString(),
      )
    }
  }
  session.listeners += listener
  sessions.attach(session)
  val visible = java.util.concurrent.atomic.AtomicBoolean(true)
  val wantFrame = java.util.concurrent.atomic.AtomicBoolean(true)
  try {
    coroutineScope {
      launch { for (e in events) send(Frame.Text(e)) }
      launch {
        var lastStage: SimulatorStage? = null
        var sentAt = 0L
        val sent = HashMap<SimulatorDevice, Long>()
        while (isActive) {
          val now = System.currentTimeMillis()
          val stage = session.stage
          if (
            stage != lastStage || (!stage.holdsMemory || stage.booting) && now - sentAt >= 1_000
          ) {
            send(Frame.Text(stageMessage(sessions, session, now)))
            if (stage == SimulatorStage.LIVE && lastStage != SimulatorStage.PAUSED) {
              send(Frame.Text(laneMessage(session)))
              wantFrame.set(true)
            }
            lastStage = stage
            sentAt = now
          }
          if (stage.ended) break
          if (stage == SimulatorStage.LIVE && visible.get()) {
            for (device in session.devices) {
              val frame =
                withContext(Dispatchers.IO) {
                  runCatching { sessions.frame(session, device) }.getOrNull()
                } ?: continue
              if (wantFrame.get() || sent[device] != frame.seq) {
                send(Frame.Text(frameMessage(frame)))
                sent[device] = frame.seq
              }
            }
            wantFrame.set(false)
            delay(sessions.frameIntervalFor(session))
          } else {
            delay(200)
          }
        }
        send(Frame.Text(endedMessage(session)))
        close(CloseReason(CloseReason.Codes.NORMAL, session.stage.id))
      }
      for (incoming in incoming) {
        val text = (incoming as? Frame.Text)?.readText() ?: continue
        if (text.length > MAX_COMMAND_BYTES) continue
        val message =
          runCatching { SIM_JSON.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
        when ((message["type"] as? JsonPrimitive)?.contentOrNull) {
          "command" -> {
            val reply = withContext(Dispatchers.IO) { runCommand(simulator, session, message) }
            // A command without an id is fire-and-forget (a drag's moves); only failures answer.
            if (message["id"] != null || (reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
              send(Frame.Text(reply.toString()))
            }
          }
          "visibility" -> {
            val now = (message["visible"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (visible.getAndSet(now) != now) {
              withContext(Dispatchers.IO) { sessions.visibility(session, now) }
              if (now) wantFrame.set(true)
            }
          }
          "wake" -> withContext(Dispatchers.IO) { sessions.wake(session) }
          "requestFrame" -> wantFrame.set(true)
        }
      }
      // The client went away: stop the loops with it.
      coroutineContext[kotlinx.coroutines.Job]?.cancelChildren()
    }
  } catch (_: CancellationException) {
    // Closed; the session lives on for its reconnect grace, paused once nobody sees it.
  } finally {
    session.listeners -= listener
    events.close()
    sessions.detach(session, wasVisible = visible.get())
  }
}

private fun kotlinx.coroutines.Job.cancelChildren() = children.forEach { it.cancel() }

private fun stageMessage(
  sessions: SimulatorSessions,
  session: SimulatorSessions.Session,
  now: Long,
): String {
  val status =
    SIM_JSON.encodeToJsonElement(
        SimulatorSessionStatusV1.serializer(),
        session.status(now, sessions.queuePosition(session)),
      )
      .jsonObject
  return JsonObject(mapOf("type" to JsonPrimitive("stage")) + status).toString()
}

private fun laneMessage(session: SimulatorSessions.Session): String = buildJsonObject {
  put("type", "lane")
  put("protocol", "composeai-simulator/1")
  put("mode", session.mode.id)
  put("tier", session.tier.id)
  put("launcher", session.launch.launcher)
  put("remainingMillis", (session.deadlineMillis - System.currentTimeMillis()).coerceAtLeast(0))
  put(
    "devices",
    kotlinx.serialization.json.JsonArray(
      session.devices.map { d ->
        val display = session.displayOf(d)
        buildJsonObject {
          put("device", d.id)
          display?.let {
            put("widthPx", it.widthPx)
            put("heightPx", it.heightPx)
            put("round", it.round)
          }
        }
      },
    ),
  )
  put(
    "commands",
    kotlinx.serialization.json.JsonArray(
      SimulatorControlPolicy.commands(session.tier).map(::JsonPrimitive),
    ),
  )
}
  .toString()

private fun frameMessage(frame: SimulatorSessions.Frame): String = buildJsonObject {
  put("type", "frame")
  put("device", frame.device.id)
  put("seq", frame.seq)
  put("codec", "png")
  put("widthPx", frame.widthPx)
  put("heightPx", frame.heightPx)
  put("dataBase64", frame.pngBase64)
}
  .toString()

private fun endedMessage(session: SimulatorSessions.Session): String = buildJsonObject {
  put("type", "ended")
  put("stage", session.stage.id)
  session.failureCode?.let { put("code", it) }
  session.failureReason?.let { put("reason", it) }
}
  .toString()

private fun readCapped(input: InputStream, max: Long): ByteArray? {
  val out = java.io.ByteArrayOutputStream()
  val buf = ByteArray(64 * 1024)
  var total = 0L
  while (true) {
    val n = input.read(buf)
    if (n < 0) break
    total += n
    if (total > max) return null
    out.write(buf, 0, n)
  }
  return out.toByteArray()
}
