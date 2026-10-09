package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRecord
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineVerdict
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Review findings on the guidelines lane: coalescing, record revisions, staleness, refreshes. */
class ServeUiBuilderGuidelineReviewFixesTest {
  private val rule = DesignGuidelineRuleSet.Bundled.rules.first()

  private fun record(revision: Int, rulesVersion: Int = DesignGuidelineRuleSet.Bundled.version) =
    DesignGuidelineRecord(
      revision = revision,
      model = "deepseek/deepseek-v4.1-flash",
      rulesVersion = rulesVersion,
      asked = listOf(rule.id),
      verdicts = listOf(DesignGuidelineVerdict(rule.id, "pass", 0.9, emptyList(), "ok")),
    )

  @Test
  fun `only identical checks share a model call`(): Unit = runBlocking {
    val coalescer = GuidelinesCheckCoalescer<String>()
    val calls = AtomicInteger()
    suspend fun check(key: GuidelinesCheckKey) =
      coalescer.run(key) {
        calls.incrementAndGet()
        delay(200)
        key.actorId
      }
    val a = GuidelinesCheckKey("design", 3, withRenders = true, actorId = "github:a")
    val b = a.copy(actorId = "github:b")
    val treeOnly = a.copy(withRenders = false)

    val same = listOf(async { check(a) }, async { check(a) }).awaitAll()
    assertEquals(listOf("github:a", "github:a"), same)
    assertEquals(1, calls.get(), "the same caller asking twice spends the key once")

    val different = listOf(async { check(a) }, async { check(b) }, async { check(treeOnly) })
    assertEquals(listOf("github:a", "github:b", "github:a"), different.awaitAll())
    assertEquals(4, calls.get(), "another caller or render mode runs its own check")
  }

  @Test
  fun `a record for a revision the design has not reached is refused`() {
    val store = ServeUiBuilderGuidelineStore(Files.createTempDirectory("guideline-records"))
    val future = store.record("design", "github:a", record(revision = 9), currentRevision = 4)
    assertIs<ServeUiBuilderGuidelineStore.GuidelineWriteResult.Refused>(future)
    assertTrue("does not exist yet" in future.reason, future.reason)
    assertIs<ServeUiBuilderGuidelineStore.GuidelineWriteResult.Stored>(
      store.record("design", "github:a", record(revision = 4), currentRevision = 4)
    )
  }

  @Test
  fun `a record against other rules is stale even at the current revision`() {
    val current = DesignGuidelineRuleSet.Bundled.version
    val older = record(revision = 4, rulesVersion = current - 1)
    val reply = UiBuilderGuidelinesV1.of("design", 4, emptySet(), older, rulesVersion = current)
    assertTrue(reply.stale)
    assertTrue("guidelines have changed" in reply.summary, reply.summary)
    assertFalse(
      UiBuilderGuidelinesV1.of("design", 4, emptySet(), record(4), rulesVersion = current).stale
    )
  }

  @Test
  fun `a refresh with a broken file drops the catalog's earlier guidelines`() {
    val logged = mutableListOf<String>()
    val store = ServeCatalogGuidelines(log = { logged += it })
    val valid =
      """
      {"schema":"compose-ui-builder/catalog-guidelines/v1","catalog":"wear-m3","platform":"wear",
       "version":2,"frames":[{"kind":"device"}],
       "rules":[{"id":"own.rule","kind":"structure","severity":"info","guidance":"g",
                 "check":"ok?","source":"https://developer.android.com/x"}]}
      """
        .trimIndent()
    assertTrue(store.accept("wear-m3", valid.toByteArray(), "url"))
    assertFalse(store.accept("wear-m3", "{truncated".toByteArray(), "url"))
    assertNull(store.forCatalog("wear-m3"), "the bundled rules apply again")
    assertTrue(logged.any { "dropped" in it }, logged.toString())
  }
}
