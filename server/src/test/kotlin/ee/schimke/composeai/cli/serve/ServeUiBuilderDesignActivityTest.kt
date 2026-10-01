package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Design activity on the webhook (#1254): forks, review verdicts and implementation changes,
 * through the comment webhook's queue, rate limit and private-design rule.
 */
class ServeUiBuilderDesignActivityTest {

  private val origin = "https://preview.example"
  private val publicDesign =
    CommentWebhookDesign(
      "Checkout",
      "m3-catalog",
      chatThread = "https://acme.slack.com/archives/C0123ABCD/p1700000000000200",
      readableByAnyone = true,
    )
  private val privateDesign = publicDesign.copy(readableByAnyone = false)

  private fun decision(id: String, verdict: String = "approve", revision: Long = 3) =
    StoredDesignDecision(
      decisionId = id,
      sequence = 1,
      revision = revision,
      verdict = verdict,
      note = "Ship it, the spacing is right now.",
      decidedBy = "github:yschimke",
      deciderKind = "human",
      displayName = "Yuri",
      decidedAtEpochMillis = 0,
    )

  private fun implementation(status: String, match: String? = null) =
    StoredImplementation(
      pr = "https://github.com/acme/app/pull/7",
      status = status,
      revision = 3,
      previewMatch = match?.let { StoredPreviewMatch(status = it) },
      updatedBy = "ci",
    )

  // ---- what is news

  @Test
  fun `a new decision is news, and one already seen is not`() {
    val before =
      StoredDesignReview(designId = "checkout", sequence = 1, decisions = listOf(decision("d1")))
    val after = before.copy(sequence = 2, decisions = before.decisions + decision("d2", "reject"))

    val changes = diffDesignReviews(before, after)

    assertEquals(1, changes.size)
    assertEquals(DesignActivityKind.DECISION, changes.single().kind)
    assertEquals("reject", changes.single().verdict)
    assertEquals(emptyList(), diffDesignReviews(after, after))
  }

  @Test
  fun `an implementation is news when its pull request, status or match moves, and not otherwise`() {
    val open = StoredDesignReview(designId = "checkout", implementation = implementation("open"))
    assertEquals(
      DesignActivityKind.IMPLEMENTATION,
      diffDesignReviews(null, open).single().kind,
      "first link is news",
    )
    val merged = open.copy(implementation = implementation("merged"))
    assertEquals("merged", diffDesignReviews(open, merged).single().status)
    val mismatch = merged.copy(implementation = implementation("merged", "mismatch"))
    assertEquals("mismatch", diffDesignReviews(merged, mismatch).single().previewMatch)
    // Only the reporter changed: the store refuses to count that, and so does the diff.
    val sameAgain = mismatch.copy(implementation = mismatch.implementation!!.copy(updatedBy = "x"))
    assertEquals(emptyList(), diffDesignReviews(mismatch, sameAgain))
    // A cleared implementation names nothing anybody stands behind.
    assertEquals(emptyList(), diffDesignReviews(mismatch, mismatch.copy(implementation = null)))
  }

  // ---- what is said, and to whom

  @Test
  fun `a public design's decision names who decided, what, and quotes the note`() {
    val activity =
      diffDesignReviews(
          null,
          StoredDesignReview(designId = "checkout", decisions = listOf(decision("d1"))),
        )
        .single()
    val event = describeDesignActivity(activity, publicDesign, origin)

    assertEquals("decision", event.event)
    assertEquals("Yuri approved revision 3 of “Checkout”", event.summary)
    assertEquals("github:yschimke", event.actor?.id)
    assertEquals("Ship it, the spacing is right now.", event.decision?.excerpt)
    assertEquals("$origin/ui-builder/checkout", event.url)
  }

  @Test
  fun `a private design is announced as a link, with nothing that was said or who said it`() {
    val decided =
      diffDesignReviews(
          null,
          StoredDesignReview(designId = "checkout", decisions = listOf(decision("d1"))),
        )
        .single()
    val event = describeDesignActivity(decided, privateDesign, origin)

    assertEquals("A review decision was recorded on design checkout", event.summary)
    assertEquals("checkout", event.design.title)
    assertNull(event.actor)
    assertEquals("", event.decision?.excerpt)
    assertFalse("Ship it" in designActivityBody(CommentWebhookFormat.SLACK, event))

    val implemented =
      diffDesignReviews(
          null,
          StoredDesignReview(designId = "checkout", implementation = implementation("merged")),
        )
        .single()
    val impl = describeDesignActivity(implemented, privateDesign, origin)
    assertNull(impl.implementation?.pr, "the pull request is content")
    assertEquals("merged", impl.implementation?.status)
    assertFalse("pull/7" in designActivityBody(CommentWebhookFormat.PLAIN, impl))
  }

  @Test
  fun `an unknown design is treated as private`() {
    val activity =
      DesignActivity("checkout", DesignActivityKind.FORK, revision = 2, forkId = "checkout-alt")
    val event = describeDesignActivity(activity, design = null, origin)
    assertEquals("An alternative was proposed for design checkout", event.summary)
    // The fork's permalink is safe to post: it opens only for somebody who may read it.
    assertEquals("$origin/ui-builder/checkout-alt", event.fork?.url)
  }

  @Test
  fun `a merged implementation with mismatched previews says both`() {
    val activity =
      diffDesignReviews(
          null,
          StoredDesignReview(
            designId = "checkout",
            implementation = implementation("merged", "mismatch"),
          ),
        )
        .single()
    assertEquals(
      "The implementation of “Checkout” was merged; its previews do not match the design",
      describeDesignActivity(activity, publicDesign, origin).summary,
    )
  }

  // ---- bodies

  @Test
  fun `the slack body is escaped mrkdwn with the design, fork, pull request and thread links`() {
    val hostile = publicDesign.copy(title = "<https://evil.example|Open the design>")
    val fork =
      DesignActivity("checkout", DesignActivityKind.FORK, revision = 2, forkId = "checkout-alt")
    val text =
      Json.parseToJsonElement(
          designActivityBody(
            CommentWebhookFormat.SLACK,
            describeDesignActivity(fork, hostile, origin),
          )
        )
        .jsonObject["text"]!!
        .jsonPrimitive
        .content

    assertFalse("<https://evil.example|" in text, "a title is characters, not a link: $text")
    assertTrue("&lt;https://evil.example|Open the design&gt;" in text)
    assertTrue("<$origin/ui-builder/checkout|Open the design>" in text)
    assertTrue("<$origin/ui-builder/checkout-alt|Open the alternative>" in text)
    assertTrue("|Slack thread for this design>" in text)
  }

  @Test
  fun `the teams body is a card whose actions open each link`() {
    val activity =
      diffDesignReviews(
          null,
          StoredDesignReview(designId = "checkout", implementation = implementation("open")),
        )
        .single()
    val card =
      Json.parseToJsonElement(
          designActivityBody(
            CommentWebhookFormat.TEAMS,
            describeDesignActivity(activity, publicDesign, origin),
          )
        )
        .jsonObject
    val content = card["attachments"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject
    val titles =
      content["actions"]!!.jsonArray.map { it.jsonObject["title"]!!.jsonPrimitive.content }
    assertEquals(
      listOf("Open the design", "Open the pull request", "Slack thread for this design"),
      titles,
    )
  }

  @Test
  fun `the plain body carries its own schema`() {
    val activity = DesignActivity("checkout", DesignActivityKind.FORK, revision = 2, forkId = "alt")
    val plain =
      Json.parseToJsonElement(
          designActivityBody(
            CommentWebhookFormat.PLAIN,
            describeDesignActivity(activity, publicDesign, origin),
          )
        )
        .jsonObject
    assertEquals(DESIGN_ACTIVITY_WEBHOOK_SCHEMA, plain["schema"]!!.jsonPrimitive.content)
    assertEquals("fork", plain["event"]!!.jsonPrimitive.content)
  }

  // ---- opt-in

  @Test
  fun `events are opt-in by name, comments stay the default, and a typo is refused`() {
    assertEquals(
      WebhookEventSelection(comments = true, activity = emptySet()),
      WebhookEventSelection.DEFAULT,
    )
    assertEquals(
      WebhookEventSelection(comments = false, activity = setOf(DesignActivityKind.DECISION)),
      DesignActivityKind.parseEvents("decision"),
    )
    assertEquals(
      WebhookEventSelection(comments = true, activity = DesignActivityKind.entries.toSet()),
      DesignActivityKind.parseEvents("all"),
    )
    assertNull(DesignActivityKind.parseEvents("comments,decisions"))
    assertNull(DesignActivityKind.parseEvents(" , "))
  }

  // ---- end to end through the stores

  @Test
  fun `a recorded decision and a fork are posted, and only the kinds asked for`() {
    val root = Files.createTempDirectory("design-activity")
    try {
      val reviews = ServeUiBuilderReviewStore(root.resolve("reviews"))
      val ancestry = ServeUiBuilderAncestryStore(root.resolve("ancestry"))
      val delivered = CopyOnWriteArrayList<String>()
      val webhook =
        ServeUiBuilderCommentWebhook(
          config = CommentWebhookConfig("https://hooks.example/hook"),
          designs = { publicDesign },
          baseUrl = { origin },
          send = {
            delivered += it
            true
          },
          onLog = {},
        )
      webhook.use {
        it.attachReviews(reviews, setOf(DesignActivityKind.DECISION)).use { _ ->
          it.attachForks(ancestry).use { _ ->
            reviews.decide("checkout", "github:yschimke", "human", DecisionRequest(3, "approve"))
            // Not asked for: an implementation change is silent on this hook.
            reviews.setImplementation(
              "checkout",
              "ci",
              ImplementationRequest(pr = "https://github.com/acme/app/pull/7"),
            )
            ancestry.recordFork(DesignForkPointV1("checkout", 3, "digest"), "checkout-alt", 0)
            awaitDeliveries(delivered, 2)
            Thread.sleep(100)

            assertEquals(2, delivered.size)
            val events = delivered.map { body ->
              Json.parseToJsonElement(body).jsonObject["event"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("decision", "fork"), events)
          }
        }
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `design activity spends the same rate-limit buckets as comments`() {
    val root = Files.createTempDirectory("design-activity-limit")
    try {
      val reviews = ServeUiBuilderReviewStore(root)
      val delivered = CopyOnWriteArrayList<String>()
      val webhook =
        ServeUiBuilderCommentWebhook(
          config = CommentWebhookConfig("https://hooks.example/hook"),
          designs = { publicDesign },
          baseUrl = { origin },
          send = {
            delivered += it
            true
          },
          onLog = {},
          rateLimit = CommentWebhookRateLimit(perDesignPerMinute = 2, totalPerMinute = 10),
          clock = { 1_000_000L },
        )
      webhook.use {
        it.attachReviews(reviews, DesignActivityKind.entries.toSet()).use { _ ->
          repeat(5) { n ->
            reviews.decide(
              "checkout",
              "github:yschimke",
              "human",
              DecisionRequest(n.toLong(), "approve"),
            )
          }
          awaitDeliveries(delivered, 2)
          Thread.sleep(100)
          assertEquals(2, delivered.size)
        }
      }
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun awaitDeliveries(bodies: CopyOnWriteArrayList<String>, count: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (bodies.size < count && System.nanoTime() < deadline) Thread.sleep(10)
    assertEquals(count, bodies.size, "expected $count deliveries, saw ${bodies.size}")
  }
}
