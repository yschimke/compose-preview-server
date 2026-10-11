package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignCommentWebhookCommentV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommentWebhookDesignV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommentWebhookEventV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommentWebhookThreadV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.web.WebEscaping
import java.io.Closeable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Posts a design's comment activity (new thread, reply, resolve, reopen) to one operator-configured
 * webhook, plus any design-activity kinds named in `--ui-builder-webhook-events`. Reactions,
 * acknowledgements and deletes are silent to avoid noise. Only public designs carry title, excerpt
 * and author; others get link-only events.
 *
 * Diffs [ServeUiBuilderCommentStore]'s own before/after boards ([diffCommentBoards]), so it can't
 * announce anything the editor didn't show. Fire-and-forget through a bounded queue that drops the
 * oldest on overflow.
 *
 * One destination per host: `links.thread` is writable by any design writer, so posting to it would
 * be SSRF/exfiltration; it is carried on events ([DesignCommentWebhookDesignV1.thread]) for relays.
 * The URL is a credential: never logged (only its [fingerprint]), `https` or loopback `http` only.
 * Per-design and total token buckets ([CommentWebhookRateLimit]) drop excess events, reporting the
 * first drop per window.
 */
internal class ServeUiBuilderCommentWebhook(
  private val config: CommentWebhookConfig,
  /** Title and catalog for a design id; null where the host cannot name it. */
  private val designs: (String) -> CommentWebhookDesign?,
  /** The browser-facing origin, resolved per event: a `--port 0` port is assigned after wiring. */
  private val baseUrl: () -> String,
  /** Posts one body and answers whether the far end accepted it. Replaced in tests. */
  private val send: suspend (String) -> Boolean = HttpCommentWebhookSender(config)::post,
  private val onLog: (String) -> Unit = { System.err.println(it) },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  private val rateLimit: CommentWebhookRateLimit = CommentWebhookRateLimit(),
  private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {

  /** What the log calls this hook. A digest of the URL, never the URL. */
  val fingerprint: String = fingerprintOf(config.url)

  // Design metadata is resolved on the writer's thread, not later or from a cache: a deleted
  // design's id can be reused, and a late lookup could put another design's title on this comment.
  private val queue = Channel<QueuedWebhookEvent>(QUEUE_CAPACITY)

  private val perDesign =
    ServeRateLimiter(
      permitsPerWindow = rateLimit.perDesignPerMinute,
      windowSeconds = 60,
      maxConcurrent = Int.MAX_VALUE,
      clock = clock,
    )
  private val total =
    ServeRateLimiter(
      permitsPerWindow = rateLimit.totalPerMinute,
      windowSeconds = 60,
      maxConcurrent = Int.MAX_VALUE,
      clock = clock,
    )

  /** Events held back per design since that design last got through; guarded by itself. */
  private val throttled = HashMap<String, Int>()

  private val worker = scope.launch {
    for (queued in queue) {
      // One at a time and never rethrowing: a webhook host that answers with an exception must
      // not take the loop down and leave every later comment undelivered in silence.
      runCatching {
        when (queued) {
          is QueuedCommentChange -> {
            val event = describe(queued)
            deliver(config.format.body(event), event.event)
          }
          is QueuedDesignActivity -> {
            val event = describeDesignActivity(queued.activity, queued.design, baseUrl())
            deliver(config.format.activityBody(event), event.event)
          }
        }
      }
        .onFailure { onLog("serve: comment webhook $fingerprint failed (${it.message})") }
    }
  }

  /** Watch every board on [store] until the returned handle is closed. */
  fun attach(store: ServeUiBuilderCommentStore): Closeable =
    store.subscribeToHost { previous, next ->
      // Never waits on the network: an in-memory diff and a non-blocking offer.
      for (change in diffCommentBoards(previous, next)) {
        if (!admit(change.designId, change.kind.wire)) continue
        val design = runCatching { designs(change.designId) }.getOrNull()
        enqueue(QueuedCommentChange(change, design))
      }
    }

  /**
   * Watch every design's review record on [store] (verdicts, implementing PR opened/merged/matched)
   * until closed, posting only opted-in [kinds] ([DesignActivityKind]).
   */
  fun attachReviews(store: ServeUiBuilderReviewStore, kinds: Set<DesignActivityKind>): Closeable =
    store.subscribeToHost { previous, next ->
      for (activity in diffDesignReviews(previous, next)) {
        if (activity.kind !in kinds) continue
        announce(activity)
      }
    }

  /**
   * Watch every fork recorded on [ancestry] until closed; the event is about the design forked
   * from.
   */
  fun attachForks(ancestry: ServeUiBuilderAncestryStore): Closeable =
    ancestry.subscribeToForks { from, forkId ->
      announce(
        DesignActivity(
          designId = from.designId,
          kind = DesignActivityKind.FORK,
          revision = from.revision,
          forkId = forkId,
        )
      )
    }

  /** Design activity takes the same buckets and the same design lookup as a comment does. */
  private fun announce(activity: DesignActivity) {
    if (!admit(activity.designId, activity.kind.wire)) return
    val design = runCatching { designs(activity.designId) }.getOrNull()
    enqueue(QueuedDesignActivity(activity, design))
  }

  /**
   * Whether [change] fits both buckets; per-design first so an over-limit design doesn't spend the
   * host allowance.
   */
  private fun admit(designId: String, wire: String): Boolean {
    val admitted =
      perDesign.tryAcquire(designId).admittedAndReleased() &&
        total.tryAcquire(TOTAL_KEY).admittedAndReleased()
    synchronized(throttled) {
      if (admitted) {
        throttled.remove(designId)?.let { held ->
          onLog(
            "serve: comment webhook $fingerprint resumed design $designId after holding back " +
              "$held event(s) over the rate limit"
          )
        }
        return true
      }
      val held = (throttled[designId] ?: 0) + 1
      // Bounded: past this many throttled designs the count is a courtesy, not a ledger.
      if (held == 1 && throttled.size >= MAX_THROTTLED_DESIGNS) return false
      throttled[designId] = held
      if (held == 1) {
        onLog(
          "serve: comment webhook $fingerprint is over its rate limit " +
            "(${rateLimit.perDesignPerMinute}/min per design, ${rateLimit.totalPerMinute}/min " +
            "in total); holding back $wire events on design $designId"
        )
      }
      return false
    }
  }

  private fun ServeRateLimiter.Decision.admittedAndReleased(): Boolean =
    when (this) {
      is ServeRateLimiter.Decision.Admitted -> {
        release()
        true
      }
      is ServeRateLimiter.Decision.Throttled -> false
    }

  private fun enqueue(queued: QueuedWebhookEvent) {
    if (queue.trySend(queued).isSuccess) return
    val dropped = queue.tryReceive().getOrNull()
    if (dropped != null) {
      onLog(
        "serve: comment webhook $fingerprint is behind; dropped the oldest queued event " +
          "(${dropped.wire} on design ${dropped.designId})"
      )
    }
    // The freed slot is not reserved; another writer may take it, and a drop must never be silent.
    if (!queue.trySend(queued).isSuccess) {
      onLog(
        "serve: comment webhook $fingerprint is behind; dropped a ${queued.wire} " +
          "event on design ${queued.designId}"
      )
    }
  }

  private suspend fun deliver(body: String, wire: String) {
    if (send(body)) return
    // Exactly one retry: a longer ladder turns one wedged host into a queue that never drains.
    delay(RETRY_DELAY_MILLIS)
    if (!send(body)) {
      onLog("serve: comment webhook $fingerprint did not accept a $wire event")
    }
  }

  /**
   * Full content only for a design anyone may read ([CommentWebhookDesign.readableByAnyone]);
   * otherwise link-only.
   */
  private fun describe(queued: QueuedCommentChange): DesignCommentWebhookEventV1 {
    val change = queued.change
    val design = queued.design
    if (design?.readableByAnyone != true) {
      return DesignCommentWebhookEventV1(
        event = change.kind.wire,
        design =
          DesignCommentWebhookDesignV1(
            id = change.designId,
            title = change.designId,
            catalog = null,
            thread = design?.chatThread,
          ),
        thread =
          DesignCommentWebhookThreadV1(
            id = change.thread.id,
            anchor = null,
            comments = change.thread.comments.size,
            resolved = change.thread.resolved,
          ),
        comment =
          DesignCommentWebhookCommentV1(
            author = null,
            authorId = null,
            authorKind = null,
            excerpt = "",
          ),
        url = threadUrl(baseUrl(), change.designId, change.thread.id),
      )
    }
    return DesignCommentWebhookEventV1(
      event = change.kind.wire,
      design =
        DesignCommentWebhookDesignV1(
          id = change.designId,
          title = design?.title.orEmpty().ifBlank { change.designId },
          catalog = design?.catalogSystemId,
          thread = design?.chatThread,
        ),
      thread =
        DesignCommentWebhookThreadV1(
          id = change.thread.id,
          anchor = change.thread.anchor.summarize(),
          comments = change.thread.comments.size,
          resolved = change.thread.resolved,
        ),
      comment = change.comment,
      url = threadUrl(baseUrl(), change.designId, change.thread.id),
    )
  }

  /**
   * Stop watching, giving queued events a bounded chance to go out without letting a slow receiver
   * hold up shutdown.
   */
  override fun close() {
    queue.close()
    val drained = runBlocking { withTimeoutOrNull(DRAIN_TIMEOUT_MILLIS) { worker.join() } != null }
    if (!drained) {
      onLog(
        "serve: comment webhook $fingerprint still had events queued at shutdown; " +
          "they were not delivered"
      )
    }
    worker.cancel()
    scope.cancel()
  }

  internal companion object {
    /** Sized for a burst of a busy review session, not for an outage. */
    const val QUEUE_CAPACITY: Int = 64

    /** The bucket key every event shares for [CommentWebhookRateLimit.totalPerMinute]. */
    private const val TOTAL_KEY = "*"

    /** Designs whose held-back count is remembered at once; see [admit]. */
    private const val MAX_THROTTLED_DESIGNS = 256

    const val RETRY_DELAY_MILLIS: Long = 500

    /** How long [close] waits for the queue to drain before saying what it is abandoning. */
    const val DRAIN_TIMEOUT_MILLIS: Long = 2_000

    /** A short digest of a URL, for a log line that must not carry the URL. */
    fun fingerprintOf(url: String): String =
      MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * `<origin>/ui-builder/<designId>#thread=<threadId>`; a fragment, so editors that don't
     * understand it still open the design.
     */
    fun threadUrl(origin: String, designId: String, threadId: String): String {
      val base = origin.trimEnd('/')
      val fragment = "#thread=${WebEscaping.urlEncodeSegment(threadId)}"
      return "$base/ui-builder/${WebEscaping.urlEncodeSegment(designId)}$fragment"
    }
  }
}

/**
 * One change with the design as it looked then; [design] null (unnameable) makes the event
 * link-only.
 */
internal data class QueuedCommentChange(
  val change: CommentBoardChange,
  val design: CommentWebhookDesign?,
) : QueuedWebhookEvent {
  override val designId: String
    get() = change.designId

  override val wire: String
    get() = change.kind.wire
}

/** What waits in the webhook's one queue: a comment change, or design activity. */
internal sealed interface QueuedWebhookEvent {
  val designId: String
  val wire: String
}

/** A fork, a verdict or an implementation change, with the design as it was at that moment. */
internal data class QueuedDesignActivity(
  val activity: DesignActivity,
  val design: CommentWebhookDesign?,
) : QueuedWebhookEvent {
  override val designId: String
    get() = activity.designId

  override val wire: String
    get() = activity.kind.wire
}

/**
 * Events per minute, per design and per host. Defaults sit under Slack's ~1 message/second webhook
 * limit with room for bursts.
 */
internal data class CommentWebhookRateLimit(
  val perDesignPerMinute: Int = 12,
  val totalPerMinute: Int = 40,
) {
  init {
    require(perDesignPerMinute > 0 && totalPerMinute > 0) { "rate limits must be positive" }
  }
}

/** Where the notification goes, and in whose dialect. */
internal data class CommentWebhookConfig(
  val url: String,
  val format: CommentWebhookFormat = CommentWebhookFormat.PLAIN,
) {
  /**
   * Why this URL is refused, as a sentence [ServeCommandOptions] prints, or null. Only `https` or
   * loopback `http`, since it is a credential.
   */
  fun rejection(): String? {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return "is not a URL"
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host?.lowercase()?.let(::unbracket)
    // `URI` parses ports the HTTP client rejects; refuse them at startup, not per delivery.
    val port = parsed.port
    if (port != -1 && port !in 1..65535) return "names port $port, which is not a port"
    if (scheme == "https") return if (host.isNullOrEmpty()) "names no host" else null
    if (scheme != "http") return "must be https (it is a credential, and it crosses a network)"
    if (host in LOOPBACK_HOSTS) return null
    return "may only be http:// on ${LOOPBACK_HOSTS.joinToString(" or ")}; everything else is https"
  }

  private companion object {
    val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    /** `URI.getHost` keeps an IPv6 literal's brackets; `[::1]` must still match loopback. */
    fun unbracket(host: String): String = host.removeSurrounding("[", "]")
  }
}

/** The body shape one endpoint accepts; each adapter is a pure function of the event. */
internal enum class CommentWebhookFormat(val wire: String) {
  /** This server's own event, verbatim. What a bespoke receiver or a relay reads. */
  PLAIN("plain"),

  /** `{"text": …}` in Slack mrkdwn — `*bold*` and `<url|label>`. */
  SLACK("slack"),

  /** A minimal Adaptive Card: Teams' Workflows-based webhooks accept only a card. */
  TEAMS("teams"),

  /** `{"text": …}` in Google Chat's markup, which is Slack's for the two things used here. */
  GOOGLE_CHAT("google-chat");

  /** The same dialect for a design-activity event; see [designActivityBody]. */
  fun activityBody(event: DesignActivityWebhookEventV1): String = designActivityBody(this, event)

  fun body(event: DesignCommentWebhookEventV1): String =
    when (this) {
      PLAIN -> WEBHOOK_JSON.encodeToString(DesignCommentWebhookEventV1.serializer(), event)
      SLACK -> WEBHOOK_JSON.encodeToString(JsonObject.serializer(), slackBody(event))
      TEAMS -> WEBHOOK_JSON.encodeToString(JsonObject.serializer(), teamsBody(event))
      GOOGLE_CHAT -> WEBHOOK_JSON.encodeToString(JsonObject.serializer(), googleChatBody(event))
    }

  companion object {
    /** The flag's value to the format, or null for a word nobody defined. */
    fun parse(value: String): CommentWebhookFormat? = entries.firstOrNull {
      it.wire == value.trim().lowercase()
    }

    val WIRE_NAMES: List<String> = entries.map { it.wire }
  }
}

/** Title and catalog for one design, as the service knows them and the comment store does not. */
internal data class CommentWebhookDesign(
  val title: String,
  val catalogSystemId: String?,
  /** The chat thread this design is discussed in, from its `links`. Null where none is set. */
  val chatThread: String? = null,
  /**
   * Whether a signed-out visitor can read the design; only then does an event carry title, excerpt
   * and author.
   */
  val readableByAnyone: Boolean = false,
)

/**
 * What the webhook needs about [designId], via keyed local lookups on the commenting thread.
 * [CommentWebhookDesign.readableByAnyone] needs a `--public` host and an ACL admitting the
 * anonymous actor; a failed lookup answers false (link-only).
 */
internal fun commentWebhookDesign(
  admin: UiBuilderAdminPort,
  service: UiBuilderServicePort,
  links: ServeUiBuilderLinksStore?,
  designId: String,
  hostIsPublic: Boolean,
): CommentWebhookDesign? {
  val summary = runCatching { admin.adminDesignSummary(designId) }.getOrNull() ?: return null
  val chatThread = runCatching { links?.read(designId)?.thread }.getOrNull()
  val readableByAnyone =
    hostIsPublic &&
      runCatching {
          runBlocking {
            service.canRead(
              AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID),
              designId,
            )
          }
        }
        .getOrDefault(false)
  return CommentWebhookDesign(
    summary.title,
    summary.catalogPin.systemId,
    chatThread,
    readableByAnyone = readableByAnyone,
  )
}

/** Which of the four things happened. */
internal enum class CommentBoardChangeKind(val wire: String) {
  NEW_THREAD("thread"),
  REPLY("reply"),
  RESOLVED("resolved"),
  REOPENED("reopened"),
}

/** One thing worth telling the room about, before the design's own name is attached to it. */
internal data class CommentBoardChange(
  val designId: String,
  val kind: CommentBoardChangeKind,
  val thread: StoredCommentThread,
  val comment: DesignCommentWebhookCommentV1,
)

/**
 * What changed between two boards: a new thread, a new comment in an existing thread (reply), or a
 * flipped [StoredCommentThread.resolved]. Reactions, acknowledgements and deletions produce
 * nothing.
 */
internal fun diffCommentBoards(
  previous: StoredCommentBoard?,
  next: StoredCommentBoard,
): List<CommentBoardChange> {
  val before = previous?.threads.orEmpty().associateBy { it.id }
  val changes = mutableListOf<CommentBoardChange>()
  for (thread in next.threads) {
    val old = before[thread.id]
    if (old == null) {
      val opening = thread.comments.firstOrNull() ?: continue
      changes +=
        CommentBoardChange(
          designId = next.designId,
          kind = CommentBoardChangeKind.NEW_THREAD,
          thread = thread,
          comment = opening.asWebhookComment(),
        )
      continue
    }
    val said = old.comments.mapTo(mutableSetOf()) { it.id }
    for (comment in thread.comments) {
      if (comment.id in said) continue
      changes +=
        CommentBoardChange(
          designId = next.designId,
          kind = CommentBoardChangeKind.REPLY,
          thread = thread,
          comment = comment.asWebhookComment(),
        )
    }
    if (old.resolved != thread.resolved) {
      changes +=
        CommentBoardChange(
          designId = next.designId,
          kind =
            if (thread.resolved) CommentBoardChangeKind.RESOLVED
            else CommentBoardChangeKind.REOPENED,
          thread = thread,
          // Quote the opening question, not the last reply (which is often just "done").
          comment = thread.resolutionComment(),
        )
    }
  }
  return changes
}

/**
 * Carries the authenticated `authorId` beside the client-supplied `displayName`, so a relay can
 * verify the name.
 */
private fun StoredComment.asWebhookComment(): DesignCommentWebhookCommentV1 =
  DesignCommentWebhookCommentV1(
    author = displayName.ifBlank { authorId },
    authorId = authorId,
    authorKind = authorKind,
    excerpt = body.commentExcerpt(),
  )

/** Who closed it and what was asked. A reopen names no author: the store clears `resolvedBy`. */
private fun StoredCommentThread.resolutionComment(): DesignCommentWebhookCommentV1 {
  val actor = resolvedBy?.takeIf { it.isNotBlank() }
  val byActor = comments.lastOrNull { it.authorId == actor }
  return DesignCommentWebhookCommentV1(
    author = byActor?.displayName?.ifBlank { null } ?: actor,
    // The resolver, as the store recorded it — null on a reopen, where nothing is known.
    authorId = actor,
    authorKind = byActor?.authorKind,
    excerpt = comments.firstOrNull()?.body.orEmpty().commentExcerpt(),
  )
}

/** Where a thread is pinned, as a person would say it. */
private fun StoredCommentAnchor?.summarize(): String? {
  if (this == null) return null
  nodeId
    ?.takeIf { it.isNotBlank() }
    ?.let {
      return "node $it"
    }
  markId
    ?.takeIf { it.isNotBlank() }
    ?.let {
      return "a mark"
    }
  // Locals: the type is from another module, so its properties do not smart-cast.
  val pinX = x
  val pinY = y
  if (pinX != null && pinY != null) {
    return "a point at ${(pinX * 100).roundToInt()}%, ${(pinY * 100).roundToInt()}%"
  }
  return null
}

// Adapters: event in, body out, no IO or clock, so they're testable without a channel.

private fun slackBody(event: DesignCommentWebhookEventV1): JsonObject = buildJsonObject {
  put("text", event.chatText(::slackEscape) { url, label -> "<$url|${slackEscape(label)}>" })
}

private fun googleChatBody(event: DesignCommentWebhookEventV1): JsonObject = buildJsonObject {
  // Same markup as Slack for what is used here; kept separate so the two can diverge.
  put("text", event.chatText(::slackEscape) { url, label -> "<$url|${slackEscape(label)}>" })
}

private fun teamsBody(event: DesignCommentWebhookEventV1): JsonObject = buildJsonObject {
  put("type", "message")
  putJsonArray("attachments") {
    add(
      buildJsonObject {
        put("contentType", "application/vnd.microsoft.card.adaptive")
        putJsonObject("content") {
          put("\$schema", "http://adaptivecards.io/schemas/adaptive-card.json")
          put("type", "AdaptiveCard")
          put("version", "1.4")
          putJsonArray("body") {
            add(textBlock(event.headline(plain = true), bold = true))
            if (event.comment.excerpt.isNotBlank()) {
              add(textBlock("“${event.comment.excerpt}”", bold = false))
            }
            event.contextLine()?.let { add(textBlock(it, bold = false, subtle = true)) }
          }
          putJsonArray("actions") {
            add(
              buildJsonObject {
                put("type", "Action.OpenUrl")
                put("title", "Open the thread")
                put("url", event.url)
              }
            )
            event.design.thread?.let { thread ->
              add(
                buildJsonObject {
                  put("type", "Action.OpenUrl")
                  put("title", ServeChatThreadLinks.label(thread))
                  put("url", thread)
                }
              )
            }
          }
        }
      }
    )
  }
}

/**
 * One card line as plain text. `TextBlock` renders Markdown, so a commenter's
 * `[link](https://attacker.example)` would appear as a clickable link under a colleague's name;
 * `TextRun`s interpret no markup, which can't be got wrong the way escaping can.
 */
internal fun textBlock(text: String, bold: Boolean, subtle: Boolean = false): JsonObject =
  buildJsonObject {
    put("type", "RichTextBlock")
    putJsonArray("inlines") {
      add(
        buildJsonObject {
          put("type", "TextRun")
          put("text", text)
          if (bold) put("weight", "Bolder")
          if (subtle) put("isSubtle", true)
        }
      )
    }
  }

/**
 * The sentence, the quoted excerpt and the link, for both `{"text": …}` platforms. The quote is
 * kept since it tells the reader whether it concerns them.
 */
private fun DesignCommentWebhookEventV1.chatText(
  escape: (String) -> String,
  link: (String, String) -> String,
): String = buildString {
  append(headline(plain = false, escape = escape, link = link))
  // Blank only on a link-only event for a design that is not public, which has no quote to show.
  if (comment.excerpt.isNotBlank()) append("\n> ").append(escape(comment.excerpt))
  contextLine()?.let { append("\n").append(escape(it)) }
  // Link to wherever the design is already being discussed, joining the two conversations.
  design.thread?.let { append("\n").append(link(it, ServeChatThreadLinks.label(it))) }
}

private fun DesignCommentWebhookEventV1.headline(
  plain: Boolean,
  escape: (String) -> String = { it },
  link: (String, String) -> String = { _, label -> label },
): String {
  val who = comment.author?.takeIf { it.isNotBlank() }?.let(escape)
  val agent = comment.authorKind == StoredComment.AUTHOR_KIND_AGENT
  val actor = if (who == null) null else if (plain) who else "*$who*"
  val suffix = if (who != null && agent) " (agent)" else ""
  val verb =
    when (event) {
      "thread" -> if (actor != null) "$actor$suffix started a thread on" else "A new thread on"
      "reply" -> if (actor != null) "$actor$suffix replied on" else "A reply on"
      "resolved" ->
        if (actor != null) "$actor$suffix resolved a thread on" else "A thread resolved on"
      else -> if (actor != null) "$actor$suffix reopened a thread on" else "A thread reopened on"
    }
  val title = design.title
  val named = if (plain) title else link(url, title)
  return "$verb $named"
}

/** Where it is pinned and how long the thread is, when either is worth a line. */
private fun DesignCommentWebhookEventV1.contextLine(): String? {
  val parts = buildList {
    thread.anchor?.let { add("on $it") }
    if (thread.comments > 1) add("${thread.comments} comments")
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Slack and Google Chat treat exactly `&`, `<`, `>` as markup; escaping anything more would
 * double-encode.
 */
internal fun slackEscape(text: String): String =
  text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Default sender on `java.net.http` (already used here, [DesignHttpTransport]), adding no
 * dependency to the guarded classpath. Short timeouts so a wedged receiver can't hold the single
 * worker.
 */
internal class HttpCommentWebhookSender(
  private val config: CommentWebhookConfig,
  private val http: HttpClient =
    HttpClient.newBuilder()
      .connectTimeout(CONNECT_TIMEOUT)
      .followRedirects(HttpClient.Redirect.NEVER)
      .build(),
) {
  /**
   * True only for 2xx. Redirects aren't followed (the URL is a credential and the target would be
   * chosen by the responder), so a 3xx means nothing was delivered.
   */
  fun post(body: String): Boolean = runCatching {
    val request =
      HttpRequest.newBuilder(URI(config.url))
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        .build()
    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
  }
    .getOrDefault(false)

  private companion object {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
    val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
  }
}

/** `explicitNulls = false` so an absent author or anchor is an absent key, not `"author": null`. */
internal val WEBHOOK_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
}
