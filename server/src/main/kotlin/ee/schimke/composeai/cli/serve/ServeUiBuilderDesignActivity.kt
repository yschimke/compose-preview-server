package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignCommentWebhookDesignV1
import ee.schimke.composeai.web.WebEscaping
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * **Design activity** posted beyond comments: a proposed alternative (fork), a review verdict, and
 * the implementing pull request moving (opened, merged, found to match or not).
 *
 * Posted by [ServeUiBuilderCommentWebhook] through its queue, rate limits, retry and private-design
 * rule, so a channel has one set of limits. Forks come from
 * [ServeUiBuilderAncestryStore.subscribeToForks]; decisions and implementations are a before/after
 * diff of [ServeUiBuilderReviewStore.subscribeToHost] ([diffDesignReviews]), so a repeated CI
 * status announces nothing. Each kind is opt-in via `--ui-builder-webhook-events`
 * ([DesignActivityKind.parseEvents]).
 *
 * There is no "failed render" event: a render's caller already has the failure, and nothing records
 * a design as currently failing. [DesignActivityWebhookEventV1] is this server's own shape (its
 * records are not published), reusing the published [DesignCommentWebhookDesignV1]; it moves to
 * contracts if an outside relay comes to depend on it.
 */
internal enum class DesignActivityKind(val wire: String) {
  FORK("fork"),
  DECISION("decision"),
  IMPLEMENTATION("implementation");

  companion object {
    /**
     * The word `--ui-builder-webhook-events` takes for comment activity, which is not one of these.
     */
    const val COMMENTS: String = "comments"
    const val ALL: String = "all"

    val WIRE_NAMES: List<String> = listOf(COMMENTS) + entries.map { it.wire } + ALL

    /**
     * `--ui-builder-webhook-events`: a comma-separated list of [WIRE_NAMES]. Null for a word nobody
     * defined, so the option can refuse it rather than silently posting less than was asked.
     */
    fun parseEvents(value: String): WebhookEventSelection? {
      val words = value.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
      if (words.isEmpty()) return null
      if (words.any { it !in WIRE_NAMES }) return null
      if (ALL in words) return WebhookEventSelection(comments = true, activity = entries.toSet())
      return WebhookEventSelection(
        comments = COMMENTS in words,
        activity = entries.filterTo(mutableSetOf()) { it.wire in words },
      )
    }
  }
}

/** What a hook posts: comment activity (the default), and which kinds of design activity. */
internal data class WebhookEventSelection(
  val comments: Boolean = true,
  val activity: Set<DesignActivityKind> = emptySet(),
) {
  companion object {
    val DEFAULT = WebhookEventSelection()
  }
}

/** One thing that happened to a design, before the design's own name is attached to it. */
internal data class DesignActivity(
  val designId: String,
  val kind: DesignActivityKind,
  val revision: Long? = null,
  /** Who did it, as authenticated — never a name read from a request body alone. */
  val actorId: String? = null,
  val actorKind: String? = null,
  val displayName: String? = null,
  val verdict: String? = null,
  val note: String? = null,
  val forkId: String? = null,
  val pr: String? = null,
  val status: String? = null,
  val previewMatch: String? = null,
)

/**
 * What changed between two review records, in the order it reads. Pure, and the whole of the "what
 * is news" decision for reviews:
 * * a decision id that is new is a **decision**;
 * * an implementation whose pull request, status or preview-match status moved is an
 *   **implementation** change.
 *
 * A cleared implementation is silent — the news would link to a pull request nobody stands behind
 * any more — and so is a change to only who last reported it, or to a match note.
 */
internal fun diffDesignReviews(
  previous: StoredDesignReview?,
  next: StoredDesignReview,
): List<DesignActivity> {
  val seen = previous?.decisions.orEmpty().mapTo(mutableSetOf()) { it.decisionId }
  val changes = mutableListOf<DesignActivity>()
  for (decision in next.decisions) {
    if (decision.decisionId in seen) continue
    changes +=
      DesignActivity(
        designId = next.designId,
        kind = DesignActivityKind.DECISION,
        revision = decision.revision,
        actorId = decision.decidedBy,
        actorKind = decision.deciderKind,
        displayName = decision.displayName,
        verdict = decision.verdict,
        note = decision.note,
      )
  }
  val before = previous?.implementation
  val after = next.implementation
  if (
    after != null &&
      (before == null ||
        before.pr != after.pr ||
        before.status != after.status ||
        before.previewMatch?.status != after.previewMatch?.status)
  ) {
    changes +=
      DesignActivity(
        designId = next.designId,
        kind = DesignActivityKind.IMPLEMENTATION,
        revision = after.revision,
        actorId = after.updatedBy,
        pr = after.pr,
        status = after.status,
        previewMatch = after.previewMatch?.status,
      )
  }
  return changes
}

/** A design-activity notification, as `plain` posts it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class DesignActivityWebhookEventV1(
  @EncodeDefault val schema: String = DESIGN_ACTIVITY_WEBHOOK_SCHEMA,
  /** `fork`, `decision` or `implementation`. */
  val event: String,
  val design: DesignCommentWebhookDesignV1,
  /** One plain-text sentence saying what happened; what a chat body leads with. */
  val summary: String,
  val revision: Long? = null,
  val actor: DesignActivityActorV1? = null,
  val decision: DesignActivityDecisionV1? = null,
  val fork: DesignActivityForkV1? = null,
  val implementation: DesignActivityImplementationV1? = null,
  /** The design's permalink. Opens only for somebody who may read the design. */
  val url: String,
)

@Serializable
internal data class DesignActivityActorV1(
  val name: String,
  val id: String,
  /** `human` or `agent`, where known. */
  val kind: String? = null,
)

@Serializable
internal data class DesignActivityDecisionV1(
  /** `approve` or `reject`. */
  val verdict: String,
  /** The note, trimmed to the comment excerpt's length. Empty when there was none. */
  val excerpt: String = "",
)

@Serializable
internal data class DesignActivityForkV1(
  val designId: String,
  /** The fork's own permalink. */
  val url: String,
)

@Serializable
internal data class DesignActivityImplementationV1(
  /** The pull request's URL. Absent on a link-only event. */
  val pr: String? = null,
  /** `draft`, `open`, `merged` or `closed`. */
  val status: String,
  /** `match`, `mismatch` or `unknown`, where reported. */
  val previewMatch: String? = null,
)

internal const val DESIGN_ACTIVITY_WEBHOOK_SCHEMA = "compose-preview/design-activity-webhook/v1"

/** `<origin>/ui-builder/<designId>`, the design's permalink. */
internal fun designUrl(origin: String, designId: String): String =
  "${origin.trimEnd('/')}/ui-builder/${WebEscaping.urlEncodeSegment(designId)}"

/**
 * The activity, plus the design's name and where to click — with the comment webhook's privacy
 * rule. Only a design anybody may read ([CommentWebhookDesign.readableByAnyone]) is posted with its
 * title, who acted, the note, the fork's id and the pull request. Every other design gets a
 * link-only event: the kind, the design id, the revision and the permalink. A fork is announced
 * with its permalink even then, since it opens only for somebody who may read it; an implementation
 * keeps its status word, which says what happened without saying what was built.
 */
internal fun describeDesignActivity(
  activity: DesignActivity,
  design: CommentWebhookDesign?,
  origin: String,
): DesignActivityWebhookEventV1 {
  val public = design?.readableByAnyone == true
  val title =
    if (public) design?.title.orEmpty().ifBlank { activity.designId } else activity.designId
  val wireDesign =
    DesignCommentWebhookDesignV1(
      id = activity.designId,
      title = title,
      catalog = if (public) design?.catalogSystemId else null,
      thread = design?.chatThread,
    )
  val actor =
    if (public) {
      activity.actorId
        ?.takeIf { it.isNotBlank() }
        ?.let { id ->
          DesignActivityActorV1(
            activity.displayName?.ifBlank { null } ?: id,
            id,
            activity.actorKind,
          )
        }
    } else null
  val revisionWords = activity.revision?.let { " revision $it of" }.orEmpty()
  val who = actor?.let {
    if (it.kind == StoredComment.AUTHOR_KIND_AGENT) "${it.name} (agent)" else it.name
  }
  val summary =
    when (activity.kind) {
      DesignActivityKind.FORK ->
        if (public) {
          "An alternative to “$title” was proposed: ${activity.forkId}" +
            activity.revision?.let { " (from revision $it)" }.orEmpty()
        } else "An alternative was proposed for design ${activity.designId}"
      DesignActivityKind.DECISION -> {
        val verb = if (activity.verdict == "approve") "approved" else "rejected"
        if (public) "${who ?: "Somebody"} $verb$revisionWords “$title”"
        else "A review decision was recorded on design ${activity.designId}"
      }
      DesignActivityKind.IMPLEMENTATION -> {
        val status = activity.status.orEmpty()
        val match =
          when (activity.previewMatch) {
            "match" -> "; its previews match the design"
            "mismatch" -> "; its previews do not match the design"
            else -> ""
          }
        if (public) {
          when (status) {
            "merged" -> "The implementation of “$title” was merged$match"
            "closed" -> "The implementation of “$title” was closed unmerged$match"
            else -> "The implementation of “$title” is $status$match"
          }
        } else "The implementation of design ${activity.designId} is now $status$match"
      }
    }
  return DesignActivityWebhookEventV1(
    event = activity.kind.wire,
    design = wireDesign,
    summary = summary,
    revision = activity.revision,
    actor = actor,
    decision =
      activity.verdict?.let { verdict ->
        DesignActivityDecisionV1(
          verdict = verdict,
          excerpt = if (public) activity.note.orEmpty().commentExcerpt() else "",
        )
      },
    fork = activity.forkId?.let { DesignActivityForkV1(it, designUrl(origin, it)) },
    implementation =
      activity.status?.let {
        DesignActivityImplementationV1(
          pr = if (public) activity.pr else null,
          status = it,
          previewMatch = activity.previewMatch,
        )
      },
    url = designUrl(origin, activity.designId),
  )
}

/**
 * A design-activity event in [format]'s dialect. Pure, like the comment bodies, so what Slack
 * receives is a unit test.
 */
internal fun designActivityBody(
  format: CommentWebhookFormat,
  event: DesignActivityWebhookEventV1,
): String =
  when (format) {
    CommentWebhookFormat.PLAIN ->
      WEBHOOK_JSON.encodeToString(DesignActivityWebhookEventV1.serializer(), event)
    CommentWebhookFormat.SLACK,
    CommentWebhookFormat.GOOGLE_CHAT ->
      WEBHOOK_JSON.encodeToString(
        JsonObject.serializer(),
        buildJsonObject { put("text", event.chatText()) },
      )
    CommentWebhookFormat.TEAMS ->
      WEBHOOK_JSON.encodeToString(JsonObject.serializer(), event.teamsCard())
  }

/**
 * The links a reader may follow, in the order they read: the design, the fork, the pull request.
 */
private fun DesignActivityWebhookEventV1.links(): List<Pair<String, String>> = buildList {
  add(url to "Open the design")
  fork?.let { add(it.url to "Open the alternative") }
  implementation?.pr?.let { add(it to "Open the pull request") }
  design.thread?.let { add(it to ServeChatThreadLinks.label(it)) }
}

/**
 * Slack mrkdwn (Google Chat reads the same two constructs): the sentence, the note as a quote, and
 * the links. Everything a person wrote — the title, the note, the fork id — is escaped, so a title
 * of `<https://evil|Open the design>` arrives as those characters rather than as a link.
 */
private fun DesignActivityWebhookEventV1.chatText(): String = buildString {
  append(slackEscape(summary))
  decision?.excerpt?.takeIf { it.isNotBlank() }?.let { append("\n> ").append(slackEscape(it)) }
  for ((target, label) in links()) {
    append("\n<").append(target).append('|').append(slackEscape(label)).append('>')
  }
}

/**
 * An Adaptive Card with the sentence as a run of text, never markdown, and the links as buttons.
 */
private fun DesignActivityWebhookEventV1.teamsCard(): JsonObject = buildJsonObject {
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
            add(textBlock(summary, bold = true))
            decision
              ?.excerpt
              ?.takeIf { it.isNotBlank() }
              ?.let { add(textBlock("“$it”", bold = false)) }
          }
          putJsonArray("actions") {
            for ((target, label) in links()) {
              add(
                buildJsonObject {
                  put("type", "Action.OpenUrl")
                  put("title", label)
                  put("url", target)
                }
              )
            }
          }
        }
      }
    )
  }
}
