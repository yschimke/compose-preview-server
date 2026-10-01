package ee.schimke.composeai.cli.serve

import java.net.URI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * **Which chat a design's `thread` link points into**, read off the permalink and nothing else.
 *
 * A design's `links.thread` ([ServeUiBuilderLinksStore]) is the "copy link to message" permalink of
 * the conversation it is discussed in. The record's shape is a published contract (`DesignLinksV1`)
 * and stays one free-form URL, so a Slack thread is not a new field: it is a `thread` whose URL
 * this recognises, and `ui_builder_get_links` reports the kind and the parsed Slack reference
 * beside the record. An agent in Slack can then tell that the thread it was tagged in *is* the
 * design's thread (same channel, same root `ts`) without a second lookup.
 *
 * Parsing only. Nothing here is fetched, and the host holds no Slack credential — the same contract
 * the links store states. The design's own comment board stays canonical (rule R4); see
 * `docs/design/CHAT_SURFACES.md` for why replies in the thread are not imported.
 */
internal object ServeChatThreadLinks {

  enum class Kind(val wire: String) {
    SLACK("slack"),
    TEAMS("teams"),
    DISCORD("discord"),
    GOOGLE_CHAT("google-chat"),
    OTHER("other"),
  }

  /**
   * A Slack message permalink, taken apart:
   * `https://<workspace>.slack.com/archives/<channel>/p<ts>` with an optional `thread_ts` query
   * naming the thread root when the message is a reply.
   *
   * [threadTs] is the root of the conversation: the `thread_ts` query when present, otherwise the
   * message itself (a link to a thread's first message is a link to the thread).
   */
  data class SlackThread(
    val workspace: String,
    val channel: String,
    val messageTs: String,
    val threadTs: String,
  )

  private val SLACK_PATH = Regex("^/archives/([A-Z0-9]{6,20})/p(\\d{10})(\\d{6})/?$")
  private val SLACK_TS = Regex("^\\d{10}\\.\\d{6}$")

  fun kind(url: String): Kind {
    val host = hostOf(url) ?: return Kind.OTHER
    return when {
      host == "slack.com" || host.endsWith(".slack.com") -> Kind.SLACK
      host == "teams.microsoft.com" || host == "teams.live.com" -> Kind.TEAMS
      host == "discord.com" || host.endsWith(".discord.com") || host == "discordapp.com" ->
        Kind.DISCORD
      host == "chat.google.com" -> Kind.GOOGLE_CHAT
      host == "mail.google.com" && pathOf(url).startsWith("/chat") -> Kind.GOOGLE_CHAT
      else -> Kind.OTHER
    }
  }

  /** The Slack reference in [url], or null when it is not a Slack message permalink. */
  fun slack(url: String): SlackThread? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (!host.endsWith(".slack.com")) return null
    val workspace = host.removeSuffix(".slack.com").takeIf { it.isNotEmpty() && '.' !in it }
    val match = SLACK_PATH.matchEntire(uri.rawPath ?: return null) ?: return null
    val (channel, seconds, micros) = match.destructured
    val messageTs = "$seconds.$micros"
    val threadTs =
      uri.rawQuery
        ?.split('&')
        ?.firstOrNull { it.startsWith("thread_ts=") }
        ?.substringAfter('=')
        ?.takeIf { SLACK_TS.matches(it) } ?: messageTs
    return SlackThread(workspace ?: return null, channel, messageTs, threadTs)
  }

  /**
   * What `ui_builder_get_links` adds beside the record when a `thread` is set: `threadKind`, and
   * for Slack the parsed `slackThread`. Empty when there is no thread.
   */
  fun describe(thread: String?): JsonObject {
    if (thread.isNullOrBlank()) return JsonObject(emptyMap())
    return buildJsonObject {
      put("threadKind", kind(thread).wire)
      slack(thread)?.let { ref ->
        putJsonObject("slackThread") {
          put("workspace", ref.workspace)
          put("channel", ref.channel)
          put("messageTs", ref.messageTs)
          put("threadTs", ref.threadTs)
        }
      }
    }
  }

  /** How a chat message labels the design's thread link, naming the platform where it is known. */
  fun label(thread: String): String =
    when (kind(thread)) {
      Kind.SLACK -> "Slack thread for this design"
      Kind.TEAMS -> "Teams thread for this design"
      Kind.DISCORD -> "Discord thread for this design"
      Kind.GOOGLE_CHAT -> "Google Chat thread for this design"
      Kind.OTHER -> "Discussion for this design"
    }

  private fun hostOf(url: String): String? = runCatching {
    URI(url.trim()).host?.lowercase()
  }.getOrNull()

  private fun pathOf(url: String): String = runCatching {
    URI(url.trim()).rawPath
  }.getOrNull().orEmpty()
}
