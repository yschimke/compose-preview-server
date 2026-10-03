package ee.schimke.composeai.cli.serve

import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **Telling the person**: the comment board and the review record, reaching somebody whose tab is
 * closed (compose-preview-server#1299).
 *
 * ## One feed, a fourth subscriber
 *
 * The browser socket, `ui_builder_await_comments` and the outbound webhook all hear about a comment
 * from [ServeUiBuilderCommentStore.subscribeToHost]; review verdicts and implementing pull requests
 * come from [ServeUiBuilderReviewStore.subscribeToHost]. This attaches to the same two, so a push
 * can never announce something the board does not show or stay quiet about something it does. There
 * is no second event source to drift.
 *
 * ## Who is told
 *
 * The webhook tells a room; this tells a person, so it has to decide who. Three rules, and they are
 * the whole of it ([commentPushIntents], [reviewPushIntents]):
 * * a **reply** reaches everybody who has already said something in that thread;
 * * an **@mention** (`@login` in the body) reaches that GitHub login, thread or no thread;
 * * a **verdict** or an **implementation** reaches the design's owner.
 *
 * Never the person who did it — a reply is never pushed back to its author, a verdict never to the
 * reviewer who recorded it. And never somebody who cannot read the design: every recipient is asked
 * through the design's own access control at delivery time, so a mention cannot announce a private
 * design to somebody it was never shared with, and a collaborator removed since they last commented
 * stops hearing about it.
 *
 * ## What a push says
 *
 * As little as is useful: `{kind, designId, threadId?, title, url, count}`. The payload is end-to-
 * end encrypted to the browser (RFC 8291), but it still lands on a lock screen, so there is no
 * comment text and no name in it — the title says *what kind of thing* happened on *which design*,
 * and the link takes somebody who may read it to the thread. `count` is how many events a burst
 * collapsed into this one notification.
 *
 * ## Bursts
 *
 * A review conversation produces replies in runs. Each (person, design, thread) waits
 * [debounceMillis] after its first event and then sends once, with the count; the push service is
 * also given a `Topic` per design and thread, so a notification still waiting at the push service
 * for an offline phone is replaced by the newer one rather than queued behind it.
 *
 * ## Delivery
 *
 * The webhook's shape: the listener runs on the writer's thread and does only in-memory work, a
 * bounded queue drops its oldest on overflow (and says so), and one worker does the network. Unlike
 * the webhook's single retry, a push service is a well-behaved HTTP API with documented status
 * codes, so this honours them: `201`/`2xx` is done, `404`/`410` means the browser unsubscribed and
 * the subscription is deleted, `429` pauses that push service for its `Retry-After`, and `5xx` or a
 * network failure retries with backoff, [MAX_ATTEMPTS] times in all.
 *
 * Endpoints and keys are credentials, so a log line names a subscription by a digest of its
 * endpoint and never by the endpoint.
 */
internal class ServePushNotifier(
  private val store: ServePushSubscriptionStore,
  private val keys: ServeVapidKeys,
  /** Title and owner of a design id; null where the host cannot name it. */
  private val designs: (String) -> PushDesign?,
  /** Whether [actorId] may read [designId], by the design's own access control. */
  private val canRead: suspend (actorId: String, designId: String) -> Boolean,
  /** The origin the notification's link opens; lazy for the reason the webhook's is. */
  private val baseUrl: () -> String,
  private val transport: PushTransport = HttpPushTransport(),
  /**
   * The sender's second look at an endpoint, just before every delivery: still a public https host,
   * resolving only to public addresses. Replaced by tests that run a loopback receiver.
   */
  private val endpointAllowed: (String) -> Boolean = ::defaultEndpointAllowed,
  private val onLog: (String) -> Unit = { System.err.println(it) },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  private val clock: () -> Long = System::currentTimeMillis,
  private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
  private val retryBaseMillis: Long = DEFAULT_RETRY_BASE_MILLIS,
) : Closeable {

  private val queue = Channel<PushDelivery>(QUEUE_CAPACITY)

  /**
   * Collapsing bursts: one entry per (recipient, design, thread) — or per (recipient, issue) for a
   * bug report — waiting out its debounce. One map, so [MAX_PENDING] bounds both.
   */
  private val pending = HashMap<Any, Held>()

  /** Push-service origins that answered 429, and when they may be asked again. */
  private val pausedUntil = ConcurrentHashMap<String, Long>()

  private val worker = scope.launch {
    for (delivery in queue) {
      runCatching { deliver(delivery) }
        .onFailure {
          onLog("serve: push to ${delivery.fingerprint} failed (${it.javaClass.simpleName})")
        }
    }
  }

  /** Watch every comment board for replies and mentions until the handle is closed. */
  fun attachComments(comments: ServeUiBuilderCommentStore): Closeable =
    comments.subscribeToHost { previous, next ->
      for (intent in commentPushIntents(previous, next)) offer(intent)
    }

  /** Watch every review record for verdicts and implementations until the handle is closed. */
  fun attachReviews(reviews: ServeUiBuilderReviewStore): Closeable =
    reviews.subscribeToHost { previous, next ->
      for (activity in diffDesignReviews(previous, next)) {
        val owner = runCatching { designs(activity.designId) }.getOrNull()?.ownerActorId
        reviewPushIntent(activity, owner)?.let(::offer)
      }
    }

  /**
   * Hold [intent] for [debounceMillis], folding any others for the same person, design and thread
   * into it. Runs on the writer's thread: a map write and, for the first event, a timer.
   */
  internal fun offer(intent: PushIntent) {
    // Nobody to tell is the common case — most people never subscribe — and costs one read of a
    // cached list rather than a timer.
    if (
      store.forActorIgnoringCase(intent.recipient).none { sub ->
        intent.kinds.any { it.wire in sub.kinds }
      }
    ) {
      return
    }
    val key = PushTopicKey(intent.recipient.lowercase(), intent.designId, intent.threadId)
    hold(key, PendingPush(intent, count = 1), intent.kinds.first())
  }

  /**
   * Hold a bug report's triage event for [debounceMillis], folding any others on the same issue for
   * the same person into it — a triager who labels, assigns and closes in one sitting is one
   * notification, worded for the last thing they did, with the count.
   */
  internal fun offerBugReport(intent: BugReportPushIntent) {
    if (
      store.forActorIgnoringCase(intent.recipient).none { PushKind.BUG_REPORTS.wire in it.kinds }
    ) {
      return
    }
    val key = IssueTopicKey(intent.recipient.lowercase(), intent.issue.key)
    hold(key, PendingIssuePush(intent, count = 1), PushKind.BUG_REPORTS)
  }

  /** The debounce both kinds of intent share: the first event starts the timer, others merge. */
  private fun hold(key: Any, held: Held, kind: PushKind) {
    val first =
      synchronized(pending) {
        val existing = pending[key]
        if (existing != null) {
          pending[key] = existing.merge(held)
          false
        } else {
          if (pending.size >= MAX_PENDING) {
            onLog("serve: push is behind; dropped a ${kind.wire} notification")
            return
          }
          pending[key] = held
          true
        }
      }
    if (!first) return
    scope.launch {
      delay(debounceMillis)
      when (val ready = synchronized(pending) { pending.remove(key) } ?: return@launch) {
        is PendingPush -> fanOut(ready)
        is PendingIssuePush -> fanOutIssue(ready)
      }
    }
  }

  /**
   * One bug report's notification to each of the reporter's browsers that wants the kind. No
   * access check like a design's: the recipient is the person who filed the issue, under their own
   * GitHub identity, and the link opens GitHub, which applies its own.
   */
  private fun fanOutIssue(ready: PendingIssuePush) {
    val intent = ready.intent
    val url = ServeIssueReport.issueRedirectUrl(baseUrl(), intent.issue)
    val topic = topicFor("issue:${intent.issue.key}", null)
    for (subscription in store.forActorIgnoringCase(intent.recipient)) {
      if (PushKind.BUG_REPORTS.wire !in subscription.kinds) continue
      val payload =
        PushPayloadV1(
          kind = PushKind.BUG_REPORTS.wire,
          issue = intent.issue.key,
          title = intent.title,
          url = url,
          count = ready.count,
        )
      enqueue(
        PushDelivery(
          endpoint = subscription.endpoint,
          p256dh = subscription.p256dh,
          auth = subscription.auth,
          body =
            PUSH_JSON.encodeToString(PushPayloadV1.serializer(), payload)
              .toByteArray(StandardCharsets.UTF_8),
          topic = topic,
          attempt = 1,
        )
      )
    }
  }

  private suspend fun fanOut(ready: PendingPush) {
    val intent = ready.intent
    val subscriptions = store.forActorIgnoringCase(intent.recipient)
    // Asked as the identity the subscriber signed in with, not as the recipient was spelled: a
    // mention is lower-cased, and the design's access list holds the login as GitHub cases it.
    val readers =
      subscriptions
        .map { it.actor }
        .distinct()
        .filter { actor -> runCatching { canRead(actor, intent.designId) }.getOrDefault(false) }
        .toSet()
    if (readers.isEmpty()) return
    val design = runCatching { designs(intent.designId) }.getOrNull()
    val origin = baseUrl()
    val url =
      if (intent.threadId != null)
        ServeUiBuilderCommentWebhook.threadUrl(origin, intent.designId, intent.threadId)
      else designUrl(origin, intent.designId)
    val topic = topicFor(intent.designId, intent.threadId)
    for (subscription in subscriptions) {
      if (subscription.actor !in readers) continue
      val kind = intent.kinds.firstOrNull { it.wire in subscription.kinds } ?: continue
      val payload =
        PushPayloadV1(
          kind = kind.wire,
          designId = intent.designId,
          threadId = intent.threadId,
          title = pushTitle(kind, intent, design?.title ?: intent.designId),
          url = url,
          count = ready.count,
        )
      enqueue(
        PushDelivery(
          endpoint = subscription.endpoint,
          p256dh = subscription.p256dh,
          auth = subscription.auth,
          body =
            PUSH_JSON.encodeToString(PushPayloadV1.serializer(), payload)
              .toByteArray(StandardCharsets.UTF_8),
          topic = topic,
          attempt = 1,
        )
      )
    }
  }

  private fun enqueue(delivery: PushDelivery) {
    if (queue.trySend(delivery).isSuccess) return
    queue.tryReceive().getOrNull()?.let {
      onLog("serve: push is behind; dropped the oldest queued notification (${it.fingerprint})")
    }
    if (!queue.trySend(delivery).isSuccess) {
      onLog("serve: push is behind; dropped a notification (${delivery.fingerprint})")
    }
  }

  private suspend fun deliver(delivery: PushDelivery) {
    if (!endpointAllowed(delivery.endpoint)) {
      onLog(
        "serve: push to ${delivery.fingerprint} refused: the endpoint is no longer a public https host"
      )
      return
    }
    val uri = URI(delivery.endpoint)
    val audience = "${uri.scheme}://${uri.rawAuthority}"
    val paused = pausedUntil[audience]
    if (paused != null && paused > clock()) {
      retryLater(delivery, paused - clock())
      return
    }
    val body =
      ServeWebPush.encrypt(
        plaintext = delivery.body,
        uaPublic = requireNotNull(ServeWebPush.fromBase64Url(delivery.p256dh)),
        authSecret = requireNotNull(ServeWebPush.fromBase64Url(delivery.auth)),
      )
    val headers =
      linkedMapOf(
        "TTL" to TTL_SECONDS.toString(),
        "Urgency" to "normal",
        "Topic" to delivery.topic,
        "Content-Encoding" to "aes128gcm",
        "Content-Type" to "application/octet-stream",
        "Authorization" to
          ServeWebPush.vapidAuthorization(
            audience = audience,
            subject = keys.subject,
            keys = keys.keyPair,
            expiresAtEpochSeconds = clock() / 1000 + JWT_LIFETIME_SECONDS,
          ),
      )
    val response =
      try {
        transport.send(delivery.endpoint, headers, body)
      } catch (failure: IOException) {
        retryLater(delivery, backoff(delivery.attempt), "${failure.javaClass.simpleName}")
        return
      }
    when (response.status) {
      in 200..299 -> store.recordSuccess(delivery.endpoint)
      404,
      410 -> {
        // The browser unsubscribed, or the push service expired the subscription. It will never
        // accept anything again, so keeping it would only repeat this request forever.
        if (store.remove(delivery.endpoint)) {
          onLog(
            "serve: push subscription ${delivery.fingerprint} is gone (${response.status}); removed it"
          )
        }
      }
      429 -> {
        val waitMillis =
          (response.retryAfterSeconds ?: DEFAULT_RETRY_AFTER_SECONDS).coerceIn(
            1,
            MAX_RETRY_AFTER_SECONDS,
          ) * 1000
        pausedUntil[audience] = clock() + waitMillis
        retryLater(delivery, waitMillis, "429")
      }
      in 500..599 -> retryLater(delivery, backoff(delivery.attempt), response.status.toString())
      else ->
        // 400, 401, 403, 413: the request is wrong in a way repeating it will not fix — a key the
        // push service does not accept, a payload too large. Said once, not retried.
        onLog("serve: push to ${delivery.fingerprint} was refused (${response.status})")
    }
  }

  private fun retryLater(delivery: PushDelivery, waitMillis: Long, reason: String? = null) {
    if (delivery.attempt >= MAX_ATTEMPTS) {
      onLog(
        "serve: push to ${delivery.fingerprint} was not delivered after ${delivery.attempt} attempts" +
          (reason?.let { " ($it)" } ?: "")
      )
      return
    }
    scope.launch {
      delay(waitMillis)
      enqueue(delivery.copy(attempt = delivery.attempt + 1))
    }
  }

  private fun backoff(attempt: Int): Long = retryBaseMillis shl (2 * (attempt - 1)).coerceAtMost(10)

  /**
   * Stop listening, and give what is already queued a short, bounded chance to go out — the
   * webhook's reasoning: a restart must not discard everything in flight, and must not wait on a
   * push service either. Debounced notifications still waiting are abandoned; they are a few
   * seconds old and the board still holds what they were about.
   */
  override fun close() {
    queue.close()
    val drained = runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MILLIS) { worker.join() } != null }
    if (!drained)
      onLog("serve: push still had notifications queued at shutdown; they were not sent")
    scope.cancel()
  }

  internal companion object {
    const val QUEUE_CAPACITY = 256
    const val MAX_PENDING = 4096
    const val MAX_ATTEMPTS = 4
    const val DEFAULT_DEBOUNCE_MILLIS = 5_000L
    const val DEFAULT_RETRY_BASE_MILLIS = 2_000L
    const val DRAIN_TIMEOUT_MILLIS = 2_000L

    /** A day: a reply nobody saw within a day is not a reason to light up a phone. */
    const val TTL_SECONDS = 86_400L

    /** RFC 8292 caps a VAPID token at 24 hours; half that leaves room for clock skew. */
    const val JWT_LIFETIME_SECONDS = 12 * 3600L
    const val DEFAULT_RETRY_AFTER_SECONDS = 60L
    const val MAX_RETRY_AFTER_SECONDS = 600L

    /**
     * The push service's `Topic`: at most 32 base64url characters, the same for every event on one
     * design's thread, and saying nothing about either id to the push service that reads it.
     */
    fun topicFor(designId: String, threadId: String?): String =
      ServeWebPush.base64Url(
          MessageDigest.getInstance("SHA-256")
            .digest("$designId\n${threadId.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
        )
        .take(32)

    fun defaultEndpointAllowed(endpoint: String): Boolean {
      if (ServePushEndpoints.rejection(endpoint) != null) return false
      val host = runCatching { URI(endpoint).host }.getOrNull() ?: return false
      return ServePushEndpoints.resolvesPublic(host.removeSurrounding("[", "]"))
    }
  }
}

/** The notification's one line, short and free of anything anybody wrote. */
internal fun pushTitle(kind: PushKind, intent: PushIntent, designTitle: String): String {
  val name = "“${designTitle.trim().ifEmpty { intent.designId }.take(MAX_TITLE_DESIGN_CHARS)}”"
  return when (kind) {
    PushKind.REPLIES -> if (intent.agent) "An agent replied on $name" else "New reply on $name"
    PushKind.MENTIONS -> "You were mentioned on $name"
    PushKind.REVIEWS ->
      when (intent.verdict) {
        "approve" -> "$name was approved"
        "reject" -> "$name was sent back"
        else -> "A pull request implements $name"
      }
    // Worded by the webhook that heard it ([bugReportPushTitle]); never asked of a design.
    PushKind.BUG_REPORTS -> name
  }
}

private const val MAX_TITLE_DESIGN_CHARS = 60

/** What a design is to the notifier: its name and who owns it. */
internal data class PushDesign(val title: String, val ownerActorId: String?)

/**
 * Somebody to tell about something. [kinds] are the subscription kinds that admit it, most specific
 * first: a mention of somebody who is also in the thread is a mention to a person who wants
 * mentions, and a reply to one who only wants replies.
 */
internal data class PushIntent(
  val recipient: String,
  val kinds: List<PushKind>,
  val designId: String,
  val threadId: String?,
  /** Who did it, so it can be checked against the recipient; never sent. */
  val actorId: String?,
  val agent: Boolean = false,
  val verdict: String? = null,
)

/**
 * A bug report's triage, for the person who filed it: which issue, and the notification's line.
 *
 * [recipient] is `github:<login>` — the issue's author, the same actor id a push subscription is
 * bound to. [title] is already worded ([bugReportPushTitle]), because only the webhook payload
 * knows what happened.
 */
internal data class BugReportPushIntent(
  val recipient: String,
  val issue: GithubIssueRef,
  val title: String,
)

private data class PushTopicKey(val recipient: String, val designId: String, val threadId: String?)

private data class IssueTopicKey(val recipient: String, val issue: String)

/** Something waiting out its debounce. */
private sealed interface Held {
  fun merge(next: Held): Held
}

private data class PendingPush(val intent: PushIntent, val count: Int) : Held {
  /** The newest event's wording, the union of what admits it, and one more in the count. */
  override fun merge(next: Held): Held {
    val newer = (next as PendingPush).intent
    return PendingPush(
      newer.copy(
        kinds = (newer.kinds + intent.kinds).distinct().sortedBy { KIND_PRIORITY.indexOf(it) }
      ),
      count + 1,
    )
  }
}

private data class PendingIssuePush(val intent: BugReportPushIntent, val count: Int) : Held {
  /** The newest event's wording, and one more in the count. */
  override fun merge(next: Held): Held =
    PendingIssuePush((next as PendingIssuePush).intent, count + 1)
}

private val KIND_PRIORITY =
  listOf(PushKind.MENTIONS, PushKind.REPLIES, PushKind.REVIEWS, PushKind.BUG_REPORTS)

/**
 * The whole of what reaches a browser. Kept small, and kept free of anything anybody else wrote —
 * a bug report's title is the recipient's own.
 *
 * [designId] for the design kinds; [issue] (`owner/repo#n`) for [PushKind.BUG_REPORTS].
 */
@Serializable
internal data class PushPayloadV1(
  val kind: String,
  val designId: String? = null,
  val issue: String? = null,
  val threadId: String? = null,
  val title: String,
  val url: String,
  val count: Int = 1,
)

private val PUSH_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
}

internal data class PushDelivery(
  val endpoint: String,
  val p256dh: String,
  val auth: String,
  val body: ByteArray,
  val topic: String,
  val attempt: Int,
) {
  val fingerprint: String
    get() = ServeUiBuilderCommentWebhook.fingerprintOf(endpoint)
}

/** `@login`, as GitHub spells a login: alphanumerics and single hyphens, at most 39 characters. */
private val MENTION =
  Regex("(?<![A-Za-z0-9_@/.])@([A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38})(?![A-Za-z0-9-])")

internal fun mentionedActors(body: String): Set<String> =
  MENTION.findAll(body).map { "github:${it.groupValues[1].lowercase()}" }.toSet()

/**
 * Who to tell about the comments that are new in [next], and as what. Pure: the rule that a reply
 * reaches the thread's earlier participants, that a mention reaches its login, and that nobody is
 * told about their own comment is a unit test, not a claim about the listener.
 */
internal fun commentPushIntents(
  previous: StoredCommentBoard?,
  next: StoredCommentBoard,
): List<PushIntent> {
  val before = previous?.threads.orEmpty().associateBy { it.id }
  val intents = mutableListOf<PushIntent>()
  for (thread in next.threads) {
    val said = before[thread.id]?.comments.orEmpty().mapTo(mutableSetOf()) { it.id }
    thread.comments.forEachIndexed { index, comment ->
      if (comment.id in said) return@forEachIndexed
      val author = comment.authorId.lowercase()
      val participants =
        thread.comments
          .take(index)
          .map { it.authorId.lowercase() }
          .filter { it.startsWith("github:") }
          .toSet()
      val mentioned = mentionedActors(comment.body).filter { it.startsWith("github:") }.toSet()
      for (recipient in (mentioned + participants) - author) {
        val kinds = buildList {
          if (recipient in mentioned) add(PushKind.MENTIONS)
          if (recipient in participants) add(PushKind.REPLIES)
        }
        intents +=
          PushIntent(
            recipient = recipient,
            kinds = kinds,
            designId = next.designId,
            threadId = thread.id,
            actorId = comment.authorId,
            agent = comment.authorKind == StoredComment.AUTHOR_KIND_AGENT,
          )
      }
    }
  }
  return intents
}

/** A verdict or an implementation, for the design's owner — unless the owner did it. */
internal fun reviewPushIntent(activity: DesignActivity, ownerActorId: String?): PushIntent? {
  if (
    activity.kind != DesignActivityKind.DECISION &&
      activity.kind != DesignActivityKind.IMPLEMENTATION
  ) {
    return null
  }
  val owner = ownerActorId?.takeIf { it.startsWith("github:") } ?: return null
  if (owner.equals(activity.actorId, ignoreCase = true)) return null
  return PushIntent(
    recipient = owner,
    kinds = listOf(PushKind.REVIEWS),
    designId = activity.designId,
    threadId = null,
    actorId = activity.actorId,
    verdict = if (activity.kind == DesignActivityKind.DECISION) activity.verdict else null,
  )
}

internal fun ServePushSubscriptionStore.forActorIgnoringCase(
  actor: String
): List<StoredPushSubscription> = all().filter { it.actor.equals(actor, ignoreCase = true) }

/** A push service's answer: the status, and `Retry-After` in seconds where it sent one. */
internal data class PushResponse(val status: Int, val retryAfterSeconds: Long? = null)

/** How one encrypted message leaves this host. Replaced in tests; [HttpPushTransport] otherwise. */
internal fun interface PushTransport {
  /** The push service's answer; throws [IOException] when there was none. */
  suspend fun send(endpoint: String, headers: Map<String, String>, body: ByteArray): PushResponse
}

/**
 * `java.net.http`, as the webhook uses: no new dependency, short timeouts, and **no redirects** —
 * the endpoint was validated, and a 3xx would hand the choice of destination to whoever answered.
 */
internal class HttpPushTransport(
  private val http: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(5))
      .followRedirects(HttpClient.Redirect.NEVER)
      .build()
) : PushTransport {
  override suspend fun send(
    endpoint: String,
    headers: Map<String, String>,
    body: ByteArray,
  ): PushResponse {
    val request =
      HttpRequest.newBuilder(URI(endpoint))
        .timeout(Duration.ofSeconds(10))
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build()
    val response = http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await()
    return PushResponse(
      status = response.statusCode(),
      retryAfterSeconds =
        response.headers().firstValue("Retry-After").orElse(null)?.let(::parseRetryAfter),
    )
  }
}

/** `Retry-After` as delta-seconds or an HTTP-date, in seconds from now; null when unreadable. */
internal fun parseRetryAfter(value: String, now: () -> Long = System::currentTimeMillis): Long? {
  value.trim().toLongOrNull()?.let {
    return it.coerceAtLeast(0)
  }
  return runCatching {
    val at =
      ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant()
        .toEpochMilli()
    ((at - now()) / 1000).coerceAtLeast(0)
  }
    .getOrNull()
}
