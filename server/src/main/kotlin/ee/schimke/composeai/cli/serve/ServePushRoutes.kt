package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the push routes need from the host: the subscriptions, the key browsers subscribe with, and
 * who is asking.
 */
public class ServePushLane
internal constructor(
  internal val store: ServePushSubscriptionStore,
  internal val keys: ServeVapidKeys,
  /**
   * The comment board's actor id for the signed-in GitHub person making [ApplicationCall] —
   * `github:<login>`, guests included, exactly as the board's "signed-in readers may take part"
   * rule admits them — or null for an anonymous or token-only caller, who cannot subscribe: a push
   * has to reach a person, and a browse token or an agent grant is not one.
   */
  internal val actorOf: (ApplicationCall) -> String?,
)

/**
 * The Web Push routes: the public key, a browser's subscription, and which kinds it wants.
 *
 * * `GET /api/push/key` — the VAPID public key a browser subscribes with, and the kinds on offer.
 *   Ungated: the key is public by design, and the page reads it before anybody is asked anything.
 * * `POST` / `DELETE /api/push/subscribe` — store or forget this browser's subscription. `POST` is
 *   idempotent and binds the endpoint to whoever is signed in now, answering that person's kinds;
 *   the settings page re-posts on every load, so a browser that changed hands is rebound before it
 *   is shown as on. Omitting `kinds` keeps the person's choice; sending them sets it everywhere.
 * * `GET` / `PUT /api/push/preferences` — the kinds the signed-in person wants, on every device.
 *
 * Every route but the key wants a GitHub sign-in, and every write is refused from a page this
 * server did not serve and refused unless it is declared JSON — the same two checks
 * [ServeSameOriginRequests] puts on the session-cookie routes, applied here whatever else the
 * request carries, because the only credential these routes ever act on is the session cookie.
 *
 * Nothing these routes answer contains an endpoint or a key: what a subscription is stays between
 * the browser that made it and the sender.
 */
internal fun Route.installPushRoutes(lane: ServePushLane, siteHosts: () -> Set<String>) {
  get(PUSH_KEY_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
    call.respondPush(
      PushKeyResponse(
        publicKey = lane.keys.publicKey,
        kinds = PushKind.entries.map { PushKindV1(it.wire, it.label) },
      )
    )
  }

  post(PUSH_SUBSCRIBE_PATH) {
    val actor = call.pushActor(lane, siteHosts, write = true) ?: return@post
    val request = call.receivePush(PushSubscribeRequest.serializer()) ?: return@post
    val kinds = request.kinds?.let { call.parseKinds(it) ?: return@post }
    val result =
      withContext(Dispatchers.IO) {
        lane.store.subscribe(
          actor = actor,
          endpoint = request.endpoint.trim(),
          p256dh = request.keys.p256dh,
          auth = request.keys.auth,
          kinds = kinds,
        )
      }
    when (result) {
      is PushSubscribeResult.Refused ->
        call.respondPushError(HttpStatusCode.UnprocessableEntity, result.reason)
      is PushSubscribeResult.Stored -> {
        // Names this browser's subscription to the sign-out route, which drops it: see
        // [forgetSignedOutBrowser].
        call.response.cookies.append(
          pushDeviceCookie(ServePushSubscriptionStore.deviceOf(result.subscription.endpoint), call)
        )
        call.respondPush(
          preferencesOf(lane, actor),
          HttpStatusCode.Created,
        )
      }
    }
  }

  delete(PUSH_SUBSCRIBE_PATH) {
    val actor = call.pushActor(lane, siteHosts, write = true) ?: return@delete
    val request = call.receivePush(PushUnsubscribeRequest.serializer()) ?: return@delete
    val removed =
      withContext(Dispatchers.IO) { lane.store.unsubscribe(actor, request.endpoint.trim()) }
    if (!removed) {
      // The same answer for "not subscribed" and "somebody else's": an endpoint is not something
      // a caller may learn the owner of by probing.
      call.respondPushError(HttpStatusCode.NotFound, "this browser is not subscribed")
      return@delete
    }
    call.response.cookies.append(pushDeviceCookie("", call))
    call.respondPush(preferencesOf(lane, actor))
  }

  get(PUSH_PREFERENCES_PATH) {
    val actor = call.pushActor(lane, siteHosts, write = false) ?: return@get
    call.respondPush(preferencesOf(lane, actor))
  }

  put(PUSH_PREFERENCES_PATH) {
    val actor = call.pushActor(lane, siteHosts, write = true) ?: return@put
    val request = call.receivePush(PushPreferencesRequest.serializer()) ?: return@put
    val kinds = call.parseKinds(request.kinds) ?: return@put
    withContext(Dispatchers.IO) { lane.store.setKinds(actor, kinds) }
    call.respondPush(preferencesOf(lane, actor).copy(kinds = kinds.map { it.wire }.sorted()))
  }
}

/**
 * Sign-out's half of push: drop the subscription this browser bound to the person signing out.
 *
 * A push subscription belongs to the browser's origin, not to the session, so it outlives a
 * sign-out — and without this, whoever used the browser next would keep receiving the previous
 * person's replies, mentions and reviews, design titles included. The server learns which
 * subscription is this browser's from [PUSH_DEVICE_COOKIE], which the subscribe response set to a
 * hash of the endpoint; only a subscription of the actor whose session is ending is touched. The
 * settings page also unsubscribes before it submits the sign-out
 * (`serve-web/src/push/settings.ts`), so this is the half that holds when that script did not get
 * to run.
 */
internal suspend fun ServePushLane.forgetSignedOutBrowser(call: ApplicationCall) {
  val device = call.request.cookies[PUSH_DEVICE_COOKIE]?.takeIf { it.isNotBlank() } ?: return
  actorOf(call)?.let { actor ->
    withContext(Dispatchers.IO) { store.unsubscribeDevice(actor, device) }
  }
  call.response.cookies.append(pushDeviceCookie("", call))
}

/**
 * [PUSH_DEVICE_COOKIE], set to [device] or, empty, cleared. Host-only like the subscription it
 * names, which belongs to this origin; `HttpOnly`, because nothing in a page needs to read it.
 */
private fun pushDeviceCookie(device: String, call: ApplicationCall): Cookie =
  Cookie(
    name = PUSH_DEVICE_COOKIE,
    value = device,
    path = "/",
    maxAge = if (device.isEmpty()) 0 else PUSH_DEVICE_COOKIE_MAX_AGE_SECONDS,
    secure = isSecure(call),
    httpOnly = true,
    encoding = CookieEncoding.RAW,
    extensions = mapOf("SameSite" to "Lax"),
  )

private fun preferencesOf(lane: ServePushLane, actor: String): PushPreferencesResponse =
  PushPreferencesResponse(
    kinds = lane.store.kinds(actor).map { it.wire }.sorted(),
    devices = lane.store.forActor(actor).size,
  )

/** The signed-in actor, or null once the refusal has been written. */
private suspend fun ApplicationCall.pushActor(
  lane: ServePushLane,
  siteHosts: () -> Set<String>,
  write: Boolean,
): String? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  if (write && !ServeSameOriginRequests.isSameOrigin(this, siteHosts())) {
    respondPushError(HttpStatusCode.Forbidden, "request origin not accepted")
    return null
  }
  val actor = lane.actorOf(this)
  if (actor == null) {
    respondPushError(HttpStatusCode.Unauthorized, "Sign in with GitHub to turn on notifications.")
    return null
  }
  return actor
}

private suspend fun ApplicationCall.parseKinds(words: List<String>): Set<PushKind>? {
  val kinds = words.map { word -> PushKind.parse(word) }
  if (kinds.any { it == null }) {
    respondPushError(
      HttpStatusCode.UnprocessableEntity,
      "`kinds` takes ${PushKind.entries.joinToString(", ") { it.wire }}",
    )
    return null
  }
  return kinds.filterNotNull().toSet()
}

private suspend fun <T> ApplicationCall.receivePush(
  serializer: kotlinx.serialization.DeserializationStrategy<T>
): T? {
  // Always JSON, whatever the credential: a form or a no-CORS fetch cannot send it, which is what
  // keeps a page elsewhere from driving these routes even where `Origin` is missing.
  val json = runCatching {
    request.contentType().match(ContentType.Application.Json)
  }
    .getOrDefault(false)
  if (!json) {
    respondPushError(HttpStatusCode.UnsupportedMediaType, "the request must be application/json")
    return null
  }
  val bytes =
    withContext(Dispatchers.IO) { receiveStream().use { it.readNBytes(MAX_PUSH_BODY_BYTES + 1) } }
  if (bytes.size > MAX_PUSH_BODY_BYTES) {
    respondPushError(HttpStatusCode.PayloadTooLarge, "the request is too large")
    return null
  }
  return try {
    PUSH_ROUTE_JSON.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
  } catch (_: SerializationException) {
    respondPushError(HttpStatusCode.BadRequest, "the request could not be read")
    null
  } catch (_: IllegalArgumentException) {
    respondPushError(HttpStatusCode.BadRequest, "the request could not be read")
    null
  }
}

private suspend inline fun <reified T> ApplicationCall.respondPush(
  body: T,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  respondText(PUSH_ROUTE_JSON.encodeToString(body), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondPushError(status: HttpStatusCode, message: String) {
  respondPush(PushErrorResponse(message), status)
}

@Serializable internal data class PushKindV1(val id: String, val label: String)

@Serializable
internal data class PushKeyResponse(val publicKey: String, val kinds: List<PushKindV1>)

@Serializable internal data class PushSubscriptionKeys(val p256dh: String, val auth: String)

/** `PushSubscription.toJSON()`, plus the kinds this device should start with. */
@Serializable
internal data class PushSubscribeRequest(
  val endpoint: String,
  val keys: PushSubscriptionKeys,
  val kinds: List<String>? = null,
)

@Serializable internal data class PushUnsubscribeRequest(val endpoint: String)

@Serializable internal data class PushPreferencesRequest(val kinds: List<String>)

@Serializable
internal data class PushPreferencesResponse(
  val kinds: List<String>,
  /** How many browsers this person has subscribed; a count, never which. */
  val devices: Int,
)

@Serializable internal data class PushErrorResponse(val error: String)

internal const val PUSH_KEY_PATH = "/api/push/key"
internal const val PUSH_SUBSCRIBE_PATH = "/api/push/subscribe"
internal const val PUSH_PREFERENCES_PATH = "/api/push/preferences"

/** Which of the signed-in person's subscriptions is this browser's: [forgetSignedOutBrowser]. */
internal const val PUSH_DEVICE_COOKIE = "cp_push_device"

/** About a year: the cookie only has to live as long as the subscription it names. */
private const val PUSH_DEVICE_COOKIE_MAX_AGE_SECONDS = 400 * 24 * 60 * 60

/** Where the push service worker lives: the root, so its scope can be `/`. */
internal const val PUSH_SERVICE_WORKER_PATH = "/push-sw.js"

private const val MAX_PUSH_BODY_BYTES = 8 * 1024

private val PUSH_ROUTE_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  ignoreUnknownKeys = true
}
