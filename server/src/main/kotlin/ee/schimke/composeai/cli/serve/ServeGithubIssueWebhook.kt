package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * **Telling a reporter their bug was triaged** (compose-preview-server#1299, item 4): an opt-in
 * GitHub webhook receiver that turns `issues` events on reports filed from this server into a
 * [PushKind.BUG_REPORTS] push for the person who filed them.
 *
 * ## Why a webhook, and why only some repositories
 *
 * The server never files an issue. [ServeIssueReport] and [ServeBugReport] hand the visitor a
 * prefilled `issues/new` link, and the reporter files it under their own GitHub identity against
 * whichever repository owns the code ([ServeIssueReport.repoFor]) — often not one this server's
 * operator controls. So the server does not know an issue exists until GitHub says so, and GitHub
 * says so only to a webhook the repository's owner installed. Reports against any other repository
 * stay silent; that limitation is the design, not a gap in it.
 *
 * ## What is trusted
 *
 * Nothing until the signature checks out. Every delivery must carry `X-Hub-Signature-256` — an
 * HMAC-SHA256 of the exact body bytes under the shared secret — and is compared in constant time
 * ([signatureMatches]) **before** the body is parsed; anything else is a bare 401. The body is
 * bounded ([MAX_BODY_BYTES]) before it is read in full.
 *
 * Even a signed delivery is about an issue whose body anybody can write, so the event is matched
 * conservatively ([triage]): the body must carry this server's [ServeIssueReport.reportMarker] for
 * one of this server's own hosts, the recipient is only ever the issue's **author**, and an event
 * the author caused themselves is nobody's news. The worst a forged marker can do is notify its own
 * author about their own issue.
 *
 * ## What is logged
 *
 * The delivery id (`X-GitHub-Delivery`), when something goes wrong — never the payload, which
 * carries issue text and logins.
 */
internal class ServeGithubIssueWebhook(
  secret: String,
  private val onTriage: (BugReportPushIntent) -> Unit,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  init {
    require(secret.isNotBlank()) { "the GitHub webhook secret is empty" }
  }

  private val key: ByteArray = secret.toByteArray(StandardCharsets.UTF_8)

  /**
   * One verified-or-not delivery, start to finish: the status to answer with. [ownHosts] are the
   * normalised hosts a [ServeIssueReport.reportMarker] must name to count as this server's.
   */
  fun receive(
    signature: String?,
    event: String?,
    deliveryId: String?,
    json: Boolean,
    body: ByteArray,
    ownHosts: Set<String>,
  ): HttpStatusCode {
    if (!signatureMatches(key, body, signature)) return HttpStatusCode.Unauthorized
    return when (event?.trim()?.lowercase()) {
      "ping" -> HttpStatusCode.NoContent
      "issues" -> {
        if (!json) return HttpStatusCode.UnsupportedMediaType
        val payload =
          runCatching {
              WEBHOOK_JSON.parseToJsonElement(body.toString(StandardCharsets.UTF_8)) as? JsonObject
            }
            .getOrNull()
        if (payload == null) {
          onLog("serve: GitHub webhook delivery ${safeDeliveryId(deliveryId)} could not be read")
          return HttpStatusCode.BadRequest
        }
        triage(payload, ownHosts)?.let { intent ->
          runCatching { onTriage(intent) }
            .onFailure {
              onLog(
                "serve: GitHub webhook delivery ${safeDeliveryId(deliveryId)} was not " +
                  "delivered (${it.javaClass.simpleName})"
              )
            }
        }
        HttpStatusCode.NoContent
      }
      // Only `issues` is acted on; anything else the hook was subscribed to is acknowledged and
      // dropped, so a broad subscription costs nothing but the request.
      else -> HttpStatusCode.NoContent
    }
  }

  internal companion object {
    /**
     * Bigger than any `issues` delivery: an issue body is capped at 65,536 characters, and the
     * rest of the payload is a few kilobytes of repository and user objects.
     */
    const val MAX_BODY_BYTES = 1024 * 1024

    const val SIGNATURE_HEADER = "X-Hub-Signature-256"
    const val EVENT_HEADER = "X-GitHub-Event"
    const val DELIVERY_HEADER = "X-GitHub-Delivery"

    private const val SIGNATURE_PREFIX = "sha256="

    /** Labels a report form can apply itself, which are therefore nobody's triage. */
    val PRE_APPLIED_LABELS: Set<String> =
      (ServeBugReport.LABELS.split(',') + ServeWeb.REPORT_CLASSIFICATION_LABELS)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    private const val MAX_TITLE_CHARS = 50
    private const val MAX_LABEL_CHARS = 40

    /**
     * Whether [header] is `sha256=<hex HMAC-SHA256 of body under key>`. Constant time in the
     * comparison; anything missing, malformed or under another algorithm is simply false.
     */
    fun signatureMatches(key: ByteArray, body: ByteArray, header: String?): Boolean {
      val value = header?.trim() ?: return false
      if (!value.startsWith(SIGNATURE_PREFIX)) return false
      val given = hexBytes(value.substring(SIGNATURE_PREFIX.length)) ?: return false
      val expected =
        Mac.getInstance("HmacSHA256").run {
          init(SecretKeySpec(key, "HmacSHA256"))
          doFinal(body)
        }
      return MessageDigest.isEqual(expected, given)
    }

    /** The header GitHub would send for [body] under [secret]; what a test signs with. */
    fun signatureFor(secret: String, body: ByteArray): String =
      SIGNATURE_PREFIX +
        Mac.getInstance("HmacSHA256")
          .run {
            init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            doFinal(body)
          }
          .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun hexBytes(hex: String): ByteArray? {
      if (hex.length != 64) return null
      val out = ByteArray(32)
      for (i in out.indices) {
        val hi = Character.digit(hex[2 * i], 16)
        val lo = Character.digit(hex[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
      }
      return out
    }

    /** A delivery id fit for a log line: GitHub's are GUIDs, anything else is cut down to that. */
    fun safeDeliveryId(id: String?): String =
      id?.filter { it.isLetterOrDigit() || it == '-' }?.take(64)?.takeIf { it.isNotEmpty() }
        ?: "(no id)"

    /**
     * Who to tell about one `issues` event, and what — or null when it is nobody's news. Pure, so
     * every rule below is a unit test rather than a claim about the route:
     * * the action is `closed`, `reopened`, `labeled` or `assigned` — not `opened`, `edited` or
     *   anything else;
     * * the issue is on `github.com` and its body carries a [ServeIssueReport.reportMarker] naming
     *   one of [ownHosts];
     * * the person who acted (`sender`) is not the reporter (`issue.user`);
     * * a label is one the report form could not have applied itself ([PRE_APPLIED_LABELS]);
     * * a label or an assignment was made by a person, not a bot — a labeller workflow reacting to
     *   the new issue is not triage. A bot closing or reopening still is.
     */
    fun triage(payload: JsonObject, ownHosts: Set<String>): BugReportPushIntent? {
      val action = payload.string("action") ?: return null
      if (action !in ACTIONS) return null
      val issue = payload.obj("issue") ?: return null
      // An `issues` event is never about a pull request, but the object GitHub shares between the
      // two would carry one; refuse it rather than reason about it.
      if (issue["pull_request"] != null) return null
      val ref =
        ServeIssueReport.githubIssue(issue.string("html_url"))
          ?: ServeIssueReport.githubIssue(
            payload.obj("repository")?.string("full_name")?.let { full ->
              issue.int("number")?.let { "https://github.com/$full/issues/$it" }
            }
          )
          ?: return null
      val hosts = ownHosts.map { it.lowercase() }.toSet()
      if (ServeIssueReport.reportMarkerHosts(issue.string("body")).none { it in hosts }) {
        return null
      }
      val reporter = issue.obj("user")?.string("login")?.takeIf { LOGIN.matches(it) } ?: return null
      val sender = payload.obj("sender")
      val senderLogin = sender?.string("login")
      if (senderLogin != null && senderLogin.equals(reporter, ignoreCase = true)) return null
      val bot = sender?.string("type").equals("Bot", ignoreCase = true)
      val what =
        when (action) {
          "closed" ->
            when (issue.string("state_reason")) {
              "completed" -> "closed as completed"
              "not_planned" -> "closed as not planned"
              "duplicate" -> "closed as a duplicate"
              else -> "closed"
            }
          "reopened" -> "reopened"
          "labeled" -> {
            if (bot) return null
            val label = payload.obj("label")?.string("name")?.trim()?.takeIf { it.isNotEmpty() }
            if (label == null || label.lowercase() in PRE_APPLIED_LABELS) return null
            "labelled “${shorten(label, MAX_LABEL_CHARS)}”"
          }
          "assigned" -> {
            if (bot) return null
            val assignee = payload.obj("assignee")?.string("login")
            if (assignee != null && assignee.equals(reporter, ignoreCase = true))
              "assigned to you"
            else "assigned"
          }
          else -> return null
        }
      return BugReportPushIntent(
        recipient = ServeAgentGrants.githubActorId(reporter),
        issue = ref,
        title = bugReportPushTitle(ref, issue.string("title"), what),
      )
    }

    private val ACTIONS = setOf("closed", "reopened", "labeled", "assigned")

    /** A GitHub login: alphanumerics and single hyphens, at most 39 characters. */
    private val LOGIN = Regex("[A-Za-z0-9](?:[A-Za-z0-9]|-(?=[A-Za-z0-9])){0,38}")

    private fun shorten(text: String, max: Int): String {
      val flat = text.replace(Regex("\\s+"), " ").trim()
      return if (flat.length <= max) flat else flat.take(max - 1).trimEnd() + "…"
    }

    private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

    private fun JsonObject.string(name: String): String? =
      (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

    private val WEBHOOK_JSON = Json { ignoreUnknownKeys = true }

    /**
     * `owner/repo#12: Button draws no border — closed as completed`. The issue's title is the
     * recipient's own words, so it may be on their lock screen; it is cut short all the same.
     */
    fun bugReportPushTitle(issue: GithubIssueRef, title: String?, what: String): String {
      val short = title?.let { shorten(it, MAX_TITLE_CHARS) }?.takeIf { it.isNotEmpty() }
      return if (short == null) "${issue.key} — $what" else "${issue.key}: $short — $what"
    }
  }
}

/** Where GitHub delivers: one path, `POST` only, and only on a host with a secret configured. */
internal const val GITHUB_WEBHOOK_PATH = "/api/github/webhook"

/**
 * `POST /api/github/webhook`. Ungated by the token and the same-origin rules — GitHub has neither —
 * because the signature is the credential; see [ServeGithubIssueWebhook].
 */
internal fun Route.installGithubWebhookRoute(
  webhook: ServeGithubIssueWebhook,
  ownHosts: RoutingContext.() -> Set<String>,
) {
  post(GITHUB_WEBHOOK_PATH) {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > ServeGithubIssueWebhook.MAX_BODY_BYTES) {
      call.respond(HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val body =
      withContext(Dispatchers.IO) {
        call.receiveStream().use { it.readNBytes(ServeGithubIssueWebhook.MAX_BODY_BYTES + 1) }
      }
    if (body.size > ServeGithubIssueWebhook.MAX_BODY_BYTES) {
      call.respond(HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val json =
      runCatching { call.request.contentType().match(ContentType.Application.Json) }
        .getOrDefault(false)
    val status =
      webhook.receive(
        signature = call.request.headers[ServeGithubIssueWebhook.SIGNATURE_HEADER],
        event = call.request.headers[ServeGithubIssueWebhook.EVENT_HEADER],
        deliveryId = call.request.headers[ServeGithubIssueWebhook.DELIVERY_HEADER],
        json = json,
        body = body,
        ownHosts = ownHosts(),
      )
    when (status) {
      // Said once, to whoever holds the secret: a form-encoded hook is a setup mistake.
      HttpStatusCode.UnsupportedMediaType ->
        call.respondText("set the webhook's content type to application/json", status = status)
      else -> call.respond(status)
    }
  }
}
