package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.io.Closeable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * **Telling the room**: a comment board that moved, posted out to one URL.
 *
 * ## The gap this closes
 *
 * A design's discussion is event-driven in both directions *inside* the product — the browser holds
 * a socket, an agent holds `ui_builder_await_comments`, and both are woken by the same write. What
 * neither of them reaches is somebody who is not in the editor. A designer's "the gap above the
 * card is wrong", left in Talk on a Friday afternoon, is invisible to the PM in the chat thread and
 * to the engineer on the PR until one of them happens to open the design. That is the one direction
 * [`MULTIPLAYER_WORKFLOW.md`](../../../../../../../../docs/design/MULTIPLAYER_WORKFLOW.md)'s
 * "Reviewing" paragraph names as missing, and this is its item 3.
 *
 * A webhook rather than a chat app per platform, for the reason section 7 gives: a bespoke app is a
 * second identity system and a second thing to install, and the chat agent's own connector already
 * covers the inbound direction. One URL out, three body shapes, nothing to sign in to.
 *
 * ## What fires, and what deliberately does not
 *
 * Four events, and they are all things somebody *said*: a new thread, a reply, a resolve, a reopen.
 *
 * Reactions and acknowledgements are silent, and that is the load-bearing decision rather than an
 * omission. `UI_BUILDER_COMMENTS.md` already draws the line for the board's own cursor — *one actor
 * catching up is not news the others have to catch up with* — and a notification is the same claim
 * made louder. A webhook that fired on every 👀 would post several times for one comment an agent
 * picked up, answered and closed, and a channel that posts noise is a channel people mute. Deleting
 * a thread is silent too: the news is that a question went away, and there is nothing left to link
 * to.
 *
 * ## How it cannot drift from what the editor sees
 *
 * It is a third subscriber to [ServeUiBuilderCommentStore]'s own feed
 * ([ServeUiBuilderCommentStore.subscribeToHost]), announced from the same statement as the socket's
 * and the agent's. A separate polling path would be a second feed with its own bugs — one that
 * could post a comment the page never showed, or stay quiet about one it did.
 *
 * What arrives there is a whole board, twice: as it was, and as it is. So *what changed* is a diff
 * ([diffCommentBoards]) rather than a field the store would have to carry. That is the same trade
 * the board itself makes — replaying a few kilobytes of text costs less than the bookkeeping a
 * per-thread change log would need — and it means a write shape added later cannot forget to
 * announce itself.
 *
 * ## Why delivery is fire-and-forget
 *
 * The write is already accepted and durable when this hears about it. A comment must not wait on
 * somebody's chat platform: a webhook host that is slow, wedged, or answering 503 would otherwise
 * add its latency to the response a designer is watching the spinner for, and a dead one would add
 * a timeout. So the listener does a small in-memory diff and hands the events to a bounded queue,
 * and one background coroutine does the posting.
 *
 * The queue is bounded and drops the **oldest** on overflow. A full queue means the far end is not
 * keeping up, and in that state the newest comment is the one worth delivering; dropping the newest
 * would make the channel lag further and further behind reality until somebody restarted the
 * server. Each drop says so on stderr, because a silently lossy notification is worse than none.
 *
 * ## The URL is a credential
 *
 * A Slack or Teams incoming-webhook URL carries its secret in the path: anybody holding the string
 * can post into that channel as this integration. So it is never logged — not on success, not on
 * failure, not in the startup banner. Everything that needs to name it names [fingerprint], a short
 * digest, which is enough for an operator to tell two configured hooks apart and useless to anybody
 * who reads the log.
 *
 * For the same reason only `https` is accepted, refused at startup by [ServeCommandOptions] rather
 * than at the first delivery. The exception is loopback (`http://127.0.0.1`, `http://localhost`),
 * which is a test receiver and a local relay, and where there is no network to eavesdrop on.
 */
internal class ServeUiBuilderCommentWebhook(
  private val config: CommentWebhookConfig,
  /** Title and catalog for a design id; null where the host cannot name it. */
  private val designs: (String) -> CommentWebhookDesign?,
  /**
   * The origin a person's browser reaches this server at, resolved per event rather than captured.
   *
   * Lazy because it is not knowable when this is constructed: on a `--port 0` host the port is
   * assigned by [ServeHttpServer.start], which happens after the lane is wired.
   */
  private val baseUrl: () -> String,
  /** Posts one body and answers whether the far end accepted it. Replaced in tests. */
  private val send: suspend (String) -> Boolean = HttpCommentWebhookSender(config)::post,
  private val onLog: (String) -> Unit = { System.err.println(it) },
  private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : Closeable {

  /** What the log calls this hook. A digest of the URL, never the URL. */
  val fingerprint: String = fingerprintOf(config.url)

  // What the queue carries is the raw change plus the design as it was *when the comment was
  // written*, not the finished body.
  //
  // Both halves of that matter. Turning a change into a body needs the design's title and catalog,
  // and reading those from the service takes the service-wide lock and scans every persisted
  // design — far too much to do on the thread accepting a comment, which is the one thing
  // fire-and-forget delivery exists to protect. But resolving it later, on the worker, would
  // describe the design as it is at *delivery* time, and behind a slow endpoint those are not the
  // same design: ids are supplied by the client and are free again once a design is deleted, so a
  // delete and re-create while the queue drains would point the permalink at an unrelated design
  // that happens to hold the id now.
  //
  // So the writer thread reads a cache ([knownDesigns]) and nothing else — one concurrent-map get,
  // no lock, no scan — and the worker keeps that cache current.
  private val queue = Channel<QueuedCommentChange>(QUEUE_CAPACITY)

  /**
   * The last thing the worker learned about each design, and how long ago.
   *
   * Read on the comment writer's thread and written only by the worker. Refreshed lazily rather
   * than on every event: within [DESIGN_CACHE_MILLIS] an entry is reused, which bounds how stale a
   * title can be while costing one lookup per design per window rather than one per comment.
   */
  private val knownDesigns = ConcurrentHashMap<String, TimedDesign>()

  private val worker = scope.launch {
    for (queued in queue) {
      // One at a time and never rethrowing: a webhook host that answers with an exception must
      // not take the loop down and leave every later comment undelivered in silence.
      runCatching { deliver(describe(queued)) }
        .onFailure { onLog("serve: comment webhook $fingerprint failed (${it.message})") }
    }
  }

  /** Watch every board on [store] until the returned handle is closed. */
  fun attach(store: ServeUiBuilderCommentStore): Closeable =
    store.subscribeToHost { previous, next ->
      // On the writer's thread, so nothing here waits: a diff of two small in-memory boards, a map
      // lookup, and an offer to a queue that never blocks.
      for (change in diffCommentBoards(previous, next)) {
        enqueue(QueuedCommentChange(change, knownDesigns[change.designId]?.design))
      }
    }

  private fun enqueue(queued: QueuedCommentChange) {
    if (queue.trySend(queued).isSuccess) return
    val dropped = queue.tryReceive().getOrNull()
    if (dropped != null) {
      onLog(
        "serve: comment webhook $fingerprint is behind; dropped the oldest queued event " +
          "(${dropped.change.kind.wire} on design ${dropped.change.designId})"
      )
    }
    // The slot freed above is not reserved, so another writer's event can take it first. Losing
    // this one silently is the one outcome not allowed: a lossy notification is tolerable, a
    // notification that is lossy without saying so is not.
    if (!queue.trySend(queued).isSuccess) {
      onLog(
        "serve: comment webhook $fingerprint is behind; dropped a ${queued.change.kind.wire} " +
          "event on design ${queued.change.designId}"
      )
    }
  }

  private suspend fun deliver(event: CommentWebhookEventV1) {
    val body = config.format.body(event)
    if (send(body)) return
    // Exactly one retry, and only one. A chat platform's incoming webhook is either up or it is
    // not; a longer ladder turns one wedged host into a queue that never drains and a comment
    // posted twice when the far end was slow rather than broken.
    delay(RETRY_DELAY_MILLIS)
    if (!send(body)) {
      onLog("serve: comment webhook $fingerprint did not accept a ${event.event} event")
    }
  }

  /** The change, plus everything the store does not know: the design's name and where to click. */
  private fun describe(queued: QueuedCommentChange): CommentWebhookEventV1 {
    val change = queued.change
    val design = queued.design ?: resolveDesign(change.designId)
    return CommentWebhookEventV1(
      event = change.kind.wire,
      design =
        CommentWebhookDesignV1(
          id = change.designId,
          title = design?.title.orEmpty().ifBlank { change.designId },
          catalog = design?.catalogSystemId,
        ),
      thread =
        CommentWebhookThreadV1(
          id = change.thread.id,
          anchor = change.thread.anchor.summarize(),
          comments = change.thread.comments.size,
          resolved = change.thread.resolved,
        ),
      comment = change.comment,
      url = threadUrl(baseUrl(), design?.catalogSystemId, change.designId, change.thread.id),
    )
  }

  /**
   * What the design is called now, remembered for the next comment on it.
   *
   * Only reached for a design the cache has never seen or has not seen recently — the first comment
   * on a design, or the first in a while. Everything else is described from the snapshot the writer
   * took, which is both cheaper and the metadata the comment was actually written against.
   */
  private fun resolveDesign(designId: String): CommentWebhookDesign? {
    val cached = knownDesigns[designId]
    if (cached != null && System.currentTimeMillis() - cached.atEpochMillis < DESIGN_CACHE_MILLIS) {
      return cached.design
    }
    val resolved = designs(designId)
    knownDesigns[designId] = TimedDesign(resolved, System.currentTimeMillis())
    return resolved
  }

  /**
   * Stop watching, giving what is already queued a bounded chance to go out.
   *
   * Closing the channel lets the worker finish the events it has; cancelling immediately would
   * throw them away on every restart and rolling deployment, and those comments are durable in the
   * board but will never be announced again — nothing replays them. So this waits, briefly, and
   * says out loud what it is abandoning if the far end is too slow to take them in that window.
   * Bounded because shutdown cannot be held hostage by somebody's chat platform either.
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
    /**
     * How many events wait for a slow webhook.
     *
     * Sized for a burst rather than for an outage: a review session in which several people are
     * typing at once produces events in tens, and past that the far end is not slow, it is gone.
     */
    const val QUEUE_CAPACITY: Int = 64

    const val RETRY_DELAY_MILLIS: Long = 500

    /** How long a cached design title and catalog are reused before the worker looks again. */
    const val DESIGN_CACHE_MILLIS: Long = 30_000

    /** How long [close] waits for the queue to drain before saying what it is abandoning. */
    const val DRAIN_TIMEOUT_MILLIS: Long = 2_000

    /** A short digest of a URL, for a log line that must not carry the URL. */
    fun fingerprintOf(url: String): String =
      MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .take(6)
        .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    /**
     * The permalink a notification links to:
     * `<origin>/ui-builder/<catalog>/<designId>#thread=<threadId>`.
     *
     * The `#thread=` selector is item 1 of the same build list and may not have landed on the host
     * reading this link. That is deliberately fine and is why the fragment rather than a query
     * carries it: an editor that does not understand the selector opens the design and ignores the
     * fragment, which is the right degraded behaviour — the reader still lands on the thing being
     * discussed, one scroll from the thread, rather than on a 404.
     *
     * A design whose catalog this host cannot name links to the builder's own entry instead of
     * inventing a catalog segment that would not resolve.
     */
    fun threadUrl(origin: String, catalog: String?, designId: String, threadId: String): String {
      val base = origin.trimEnd('/')
      val fragment = "#thread=${WebEscaping.urlEncodeSegment(threadId)}"
      val catalogSegment = catalog?.trim()?.takeIf { it.isNotEmpty() } ?: return "$base/ui-builder/"
      return "$base/ui-builder/${WebEscaping.urlEncodeSegment(catalogSegment)}/" +
        "${WebEscaping.urlEncodeSegment(designId)}$fragment"
    }
  }
}

/**
 * One change, with the design as it looked when the change happened.
 *
 * [design] is null when the writer's cache had never seen the design; the worker resolves it then,
 * which is the first comment on a design and the one case where delivery-time metadata is the best
 * available.
 */
internal data class QueuedCommentChange(
  val change: CommentBoardChange,
  val design: CommentWebhookDesign?,
)

/** A cached design lookup and when it was made. */
internal data class TimedDesign(val design: CommentWebhookDesign?, val atEpochMillis: Long)

/** Where the notification goes, and in whose dialect. */
internal data class CommentWebhookConfig(
  val url: String,
  val format: CommentWebhookFormat = CommentWebhookFormat.PLAIN,
) {
  /**
   * What an operator may point this at.
   *
   * `https` or loopback, and nothing else. The URL is a credential (see
   * [ServeUiBuilderCommentWebhook]), so posting a design's discussion over cleartext to another
   * machine would hand both the secret and the conversation to anything on the path. Loopback is
   * exempt because there is no path: it is the test receiver and the local relay in front of a real
   * endpoint.
   *
   * Returns the reason it is refused, or null when it is fine — a sentence, because
   * [ServeCommandOptions] prints it as one.
   */
  fun rejection(): String? {
    val parsed = runCatching { URI(url) }.getOrNull() ?: return "is not a URL"
    val scheme = parsed.scheme?.lowercase()
    val host = parsed.host?.lowercase()?.let(::unbracket)
    // A port `URI` will parse but the HTTP client will not. Caught here rather than at the first
    // delivery: the promise of validating at startup is that a hook which cannot work says so on
    // the day it is configured, and `HttpClient.send` throwing per event would instead look like
    // an ordinary delivery failure and retry forever against a URL that can never answer.
    val port = parsed.port
    if (port != -1 && port !in 1..65535) return "names port $port, which is not a port"
    if (scheme == "https") return if (host.isNullOrEmpty()) "names no host" else null
    if (scheme != "http") return "must be https (it is a credential, and it crosses a network)"
    if (host in LOOPBACK_HOSTS) return null
    return "may only be http:// on ${LOOPBACK_HOSTS.joinToString(" or ")}; everything else is https"
  }

  private companion object {
    val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")

    /**
     * `[::1]` as `::1`.
     *
     * `URI.getHost` hands back an IPv6 literal with the brackets it was written with, so the
     * loopback exception would otherwise refuse the one spelling of loopback that a v6-only box
     * has.
     */
    fun unbracket(host: String): String = host.removeSurrounding("[", "]")
  }
}

/**
 * The body shape one endpoint accepts.
 *
 * Adapters rather than a template, and pure functions from the plain event rather than a step in
 * the delivery path: each is a string in and a string out, so what Slack receives is checked by a
 * unit test and not by an operator with a real channel and a real comment.
 */
internal enum class CommentWebhookFormat(val wire: String) {
  /** This server's own event, verbatim. What a bespoke receiver or a relay reads. */
  PLAIN("plain"),

  /** `{"text": …}` in Slack mrkdwn — `*bold*` and `<url|label>`. */
  SLACK("slack"),

  /**
   * A minimal Adaptive Card in the `message` envelope Teams' incoming webhooks accept.
   *
   * A card rather than `{"text": …}` because the Workflows-based webhooks that replaced Office 365
   * connectors take only the card, and a card is what makes the permalink a button rather than a
   * URL somebody has to select.
   */
  TEAMS("teams"),

  /** `{"text": …}` in Google Chat's markup, which is Slack's for the two things used here. */
  GOOGLE_CHAT("google-chat");

  fun body(event: CommentWebhookEventV1): String =
    when (this) {
      PLAIN -> WEBHOOK_JSON.encodeToString(CommentWebhookEventV1.serializer(), event)
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

/** The plain body, and the thing every adapter is a pure function of. */
@Serializable
internal data class CommentWebhookEventV1(
  val schema: String = "compose-preview/ui-builder-comment-event/v1",
  /** `thread`, `reply`, `resolved` or `reopened`. */
  val event: String,
  val design: CommentWebhookDesignV1,
  val thread: CommentWebhookThreadV1,
  val comment: CommentWebhookCommentV1,
  /** The thread permalink. The one field a person in a chat window actually uses. */
  val url: String,
)

@Serializable
internal data class CommentWebhookDesignV1(
  val id: String,
  /** The design's own title, falling back to its id on a host that cannot name it. */
  val title: String,
  /** The catalog it is pinned to — the `<catalog>` segment of [CommentWebhookEventV1.url]. */
  val catalog: String? = null,
)

@Serializable
internal data class CommentWebhookThreadV1(
  val id: String,
  /**
   * Where the thread is pinned, in words: the node id, `a mark`, or a point on the frame.
   *
   * A sentence rather than the anchor's three fields because the reader is a person in a chat
   * window, and "on node play-button" tells them what is being discussed while `{"markId": "m-4"}`
   * does not. Null for a thread about the design as a whole.
   */
  val anchor: String? = null,
  val comments: Int = 1,
  val resolved: Boolean = false,
)

@Serializable
internal data class CommentWebhookCommentV1(
  /** The author's display name, else their actor id. Absent where the act has no named actor. */
  val author: String? = null,
  /** `human` or `agent`, as declared. Cosmetic here exactly as it is on the board. */
  @SerialName("authorKind") val authorKind: String? = null,
  /** What was said, trimmed by [commentExcerpt] — the same rule the `comments` notice uses. */
  val excerpt: String,
)

/** Title and catalog for one design, as the service knows them and the comment store does not. */
internal data class CommentWebhookDesign(val title: String, val catalogSystemId: String?)

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
  val comment: CommentWebhookCommentV1,
)

/**
 * What changed between two boards, in the order it reads.
 *
 * Pure, and the whole of the "what is news" decision — so the rule that a reaction is not news is a
 * unit test rather than a claim about the delivery path.
 *
 * Three comparisons per thread and nothing else:
 * * a thread id that is new is a **new thread**;
 * * a comment id that is new inside a thread that is not is a **reply**;
 * * [StoredCommentThread.resolved] that flipped is a **resolve** or a **reopen**.
 *
 * Everything else a write can do to a board — a reaction added or taken back, an acknowledgement,
 * the board's own sequence rising — moves none of those three and so produces nothing. A deleted
 * thread produces nothing either: the news would link to a thread that is gone.
 *
 * A resolve and a reply can land in one write only if a caller did both, which no route does; when
 * they do, both are reported, because a thread that gained an answer *and* was closed is two
 * different things a reader may care about.
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
          // What was settled is the question, so a resolution quotes the thread's OPENING comment
          // rather than the last thing said in it. "Yuri resolved: the play icon looks like a
          // cross" is the line a reader can act on; the last reply is often "done".
          comment = thread.resolutionComment(),
        )
    }
  }
  return changes
}

private fun StoredComment.asWebhookComment(): CommentWebhookCommentV1 =
  CommentWebhookCommentV1(
    author = displayName.ifBlank { authorId },
    authorKind = authorKind,
    excerpt = body.commentExcerpt(),
  )

/**
 * Who closed it and what was asked.
 *
 * [StoredCommentThread.resolvedBy] is the actor, and it is null on a reopen by construction — the
 * store clears it, and inferring the reopener from the acknowledgement map would be a guess dressed
 * as a fact. So a reopen names no author and the sentence reads "a thread was reopened", which is
 * what is actually known.
 */
private fun StoredCommentThread.resolutionComment(): CommentWebhookCommentV1 {
  val actor = resolvedBy?.takeIf { it.isNotBlank() }
  val byActor = comments.lastOrNull { it.authorId == actor }
  return CommentWebhookCommentV1(
    author = byActor?.displayName?.ifBlank { null } ?: actor,
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
  if (x != null && y != null) {
    return "a point at ${(x * 100).roundToInt()}%, ${(y * 100).roundToInt()}%"
  }
  return null
}

// ---------------------------------------------------------------------------------------------
// Adapters. Each is `event in, body out`, with no IO and no clock, which is what makes them
// testable without a channel to post into.
// ---------------------------------------------------------------------------------------------

private fun slackBody(event: CommentWebhookEventV1): JsonObject = buildJsonObject {
  put("text", event.chatText(::slackEscape) { url, label -> "<$url|${slackEscape(label)}>" })
}

private fun googleChatBody(event: CommentWebhookEventV1): JsonObject = buildJsonObject {
  // Google Chat's own markup: the same `*bold*` and the same `<url|label>` anchor, and the same
  // three characters to escape. Kept as its own function rather than aliased to Slack's so a
  // divergence between them is one edit here rather than a shared helper nobody may change.
  put("text", event.chatText(::slackEscape) { url, label -> "<$url|${slackEscape(label)}>" })
}

private fun teamsBody(event: CommentWebhookEventV1): JsonObject = buildJsonObject {
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
            add(textBlock("“${event.comment.excerpt}”", bold = false))
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
          }
        }
      }
    )
  }
}

private fun textBlock(text: String, bold: Boolean, subtle: Boolean = false): JsonObject =
  buildJsonObject {
    put("type", "TextBlock")
    put("text", text)
    put("wrap", true)
    if (bold) put("weight", "Bolder")
    if (subtle) put("isSubtle", true)
  }

/**
 * The one sentence, the quote under it, and the link — the shape both `{"text": …}` platforms use.
 *
 * The quote is never dropped, for the reason the agent notice gives about its own excerpt: a line
 * saying somebody commented is one more notification among many, and the sentence itself is what
 * tells a reader whether it is about them.
 */
private fun CommentWebhookEventV1.chatText(
  escape: (String) -> String,
  link: (String, String) -> String,
): String = buildString {
  append(headline(plain = false, escape = escape, link = link))
  append("\n> ").append(escape(comment.excerpt))
  contextLine()?.let { append("\n").append(escape(it)) }
}

private fun CommentWebhookEventV1.headline(
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
private fun CommentWebhookEventV1.contextLine(): String? {
  val parts = buildList {
    thread.anchor?.let { add("on $it") }
    if (thread.comments > 1) add("${thread.comments} comments")
  }
  return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Slack and Google Chat both read `&`, `<` and `>` as markup, and both want exactly these three
 * replaced and nothing else — escaping quotes or ampersand-entities as well is what turns a comment
 * containing `&amp;` into `&amp;amp;` in the channel.
 */
private fun slackEscape(text: String): String =
  text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The default sender: `java.net.http`, which is already how this module talks outward
 * ([DesignHttpTransport]) and adds no dependency to a classpath `checkServeModuleBoundary` guards.
 *
 * Short timeouts on purpose. Nothing waits on this, so a generous one buys nothing and costs a
 * wedged host holding the queue's single worker for as long as it likes.
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
   * True when the far end took it, which means 2xx and nothing else.
   *
   * A redirect is a failure here rather than a success. Redirects are not followed — a webhook URL
   * is a credential and the target of a 302 is chosen by whatever answered, not by the operator —
   * so a 3xx means the body was never delivered anywhere. Counting it as delivered would retire the
   * retry and swallow the log line for a hook that is quietly posting nothing.
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
private val WEBHOOK_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
}
