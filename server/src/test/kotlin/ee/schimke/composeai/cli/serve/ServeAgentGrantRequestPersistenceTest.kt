package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Access requests outlive a restart: a store reloaded from the same file answers a poll the way the
 * one that opened the request would have. What is written is the request and the approval
 * *decision* — never the device secret, never a bearer — so a grant is minted at collection, its
 * lifetime counted from the approval.
 */
class ServeAgentGrantRequestPersistenceTest {

  private var now = 1_000_000L
  private val dir: Path = createTempDirectory("agent-grant-requests")
  private val file: Path = dir.resolve("mcp-oauth/agent-access-requests.json")

  @AfterTest
  fun cleanUp() {
    dir.toFile().deleteRecursively()
  }

  private fun store(file: Path? = this.file) =
    ServeAgentGrantStore(
      maxGrantTtlSeconds = 3600,
      maxScope = AgentGrantScope.PLAYGROUND,
      maxCapabilities = setOf(AgentGrantCapability.IMAGES),
      clock = { now },
      requestsFile = file,
    )

  private fun ServeAgentGrantStore.ask(designIds: Set<String> = emptySet()) =
    openRequest(
      "fix #1",
      "10.0.0.1",
      AgentGrantScope.LIVE,
      1800,
      setOf(AgentGrantCapability.IMAGES),
      designIds = designIds,
    )!!

  @Test
  fun `a pending request survives a restart, still pending, and can then be approved`() {
    val request = store().ask(designIds = setOf("home"))

    now += 60_000
    val reloaded = store()
    val pending =
      assertIs<ServeAgentGrantStore.Poll.Pending>(reloaded.poll(request.id, request.deviceSecret))
    assertEquals(29 * 60L, pending.secondsUntilExpiry)
    val shown = assertNotNull(reloaded.request(request.id))
    assertEquals(request.userCode, shown.userCode)
    assertEquals("fix #1", shown.label)
    assertEquals(AgentGrantScope.LIVE, shown.requestedScope)
    assertEquals(setOf(AgentGrantCapability.IMAGES), shown.requestedCapabilities)
    assertEquals(setOf("home"), shown.designIds)
    // The secret is still what redeems it: a wrong one reads as no request at all.
    assertEquals(ServeAgentGrantStore.Poll.Unknown, reloaded.poll(request.id, "not-the-secret"))
    assertEquals(listOf(request.id), reloaded.pendingRequests().map { it.id })

    val grant =
      assertNotNull(
        reloaded.approve(
          request.id,
          "@yuri",
          AgentGrantScope.LIVE,
          600,
          setOf(AgentGrantCapability.IMAGES),
          "github:yuri",
        )
      )
    val approved =
      assertIs<ServeAgentGrantStore.Poll.Approved>(reloaded.poll(request.id, request.deviceSecret))
    assertEquals(grant.token, approved.grant.token)
    assertEquals(setOf("home"), approved.grant.designIds)
  }

  @Test
  fun `an approved request nobody collected yet mints its grant on the first poll after a restart`() {
    val first = store()
    val request = first.ask()
    now += 10_000
    val approvedAt = now
    val minted =
      assertNotNull(
        first.approve(
          request.id,
          "@yuri",
          AgentGrantScope.LIVE,
          600,
          setOf(AgentGrantCapability.IMAGES),
          "github:yuri",
        )
      )

    // Restart five minutes later: the in-memory grant is gone with the process.
    now += 5 * 60_000
    val reloaded = store()
    assertNull(reloaded.grantForToken(minted.token), "a token is never restored from disk")
    val collected =
      assertIs<ServeAgentGrantStore.Poll.Approved>(reloaded.poll(request.id, request.deviceSecret))
    val grant = collected.grant
    assertEquals(AgentGrantScope.LIVE, grant.scope)
    assertEquals(setOf(AgentGrantCapability.IMAGES), grant.capabilities)
    assertEquals("@yuri", grant.approvedBy)
    assertEquals("github:yuri", grant.approvedByActorId)
    assertEquals("fix #1", grant.label)
    // Counted from the approval, so the restart bought no extra time.
    assertEquals(approvedAt, grant.issuedAtMillis)
    assertEquals(approvedAt + 600_000, grant.expiresAtMillis)
    assertEquals(5 * 60L, grant.secondsUntilExpiry(now))
    assertEquals(grant, reloaded.grantForToken(grant.token))
    // A lost response is retriable in-process: the same grant again, not a second one.
    val again =
      assertIs<ServeAgentGrantStore.Poll.Approved>(reloaded.poll(request.id, request.deviceSecret))
    assertEquals(grant.token, again.grant.token)

    // Collected means gone from disk: a second restart does not mint again.
    val afterCollection = store()
    assertEquals(
      ServeAgentGrantStore.Poll.Unknown,
      afterCollection.poll(request.id, request.deviceSecret),
    )
  }

  @Test
  fun `an approval whose grant would already have expired answers expired after a restart`() {
    val first = store()
    val request = first.ask()
    assertNotNull(first.approve(request.id, "@yuri", AgentGrantScope.LIVE, 60, emptySet(), "u"))
    now += 30_000
    val reloaded = store()
    now += 31_000
    assertEquals(ServeAgentGrantStore.Poll.Expired, reloaded.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `a revoked grant is not minted again by a restart`() {
    val first = store()
    val request = first.ask()
    val grant =
      assertNotNull(first.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u"))
    assertTrue(first.revoke(grant.id, "@yuri"))
    assertNotPollable(store().poll(request.id, request.deviceSecret))
  }

  private fun assertNotPollable(poll: ServeAgentGrantStore.Poll) =
    assertTrue(poll !is ServeAgentGrantStore.Poll.Approved, poll.toString())

  @Test
  fun `a person's own request survives while pending, and its approval is a grant like any other`() {
    val first = store()
    val request =
      assertNotNull(
        first.openRequest(
          "edit my design",
          "10.0.0.1",
          AgentGrantScope.PREVIEW,
          600,
          setOf(AgentGrantCapability.UI_BUILDER_WRITE),
          requesterActorId = "github:ana",
        )
      )
    val reloaded = store()
    assertEquals("github:ana", assertNotNull(reloaded.request(request.id)).requesterActorId)
    assertNotNull(
      reloaded.approve(request.id, "@yuri", AgentGrantScope.PREVIEW, 600, emptySet(), "u")
    )
    assertNotNull(reloaded.activeGrantForRequester("github:ana"))
    // Nobody polls a session-carried grant, so its approval is not kept to be minted again.
    assertFalse(file.readText().contains(request.id))
    assertNull(store().activeGrantForRequester("github:ana"))
  }

  @Test
  fun `a denial survives a restart, as denied rather than unknown`() {
    val first = store()
    val request = first.ask()
    assertTrue(first.deny(request.id, "@yuri"))

    val denied =
      assertIs<ServeAgentGrantStore.Poll.Denied>(store().poll(request.id, request.deviceSecret))
    assertEquals("@yuri", denied.by)
  }

  @Test
  fun `expired entries are dropped when the file is loaded`() {
    val first = store()
    val pending = first.ask()
    val denied = first.ask().also { first.deny(it.id, "@yuri") }
    val approved = first.ask()
    assertNotNull(first.approve(approved.id, "@yuri", AgentGrantScope.LIVE, 60, emptySet(), "u"))

    // Past both the request deadline (30 minutes) and the grant's (one minute).
    now += 31 * 60_000
    val reloaded = store()
    for (request in listOf(pending, denied, approved)) {
      assertNull(reloaded.request(request.id))
    }
    assertTrue(reloaded.pendingRequests().isEmpty())
    // Something written now rewrites the file without them.
    reloaded.ask()
    val text = file.readText()
    for (request in listOf(pending, denied, approved)) {
      assertFalse(text.contains(request.id), text)
    }
  }

  @Test
  fun `the file never holds a device secret or a token`() {
    val first = store()
    val pending = first.ask()
    val approved = first.ask()
    val grant =
      assertNotNull(
        first.approve(approved.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
      )
    val denied = first.ask().also { first.deny(it.id, "@yuri") }

    val text = file.readText()
    for (request in listOf(pending, approved, denied)) {
      assertTrue(text.contains(request.id), "every live request is on disk")
      assertFalse(text.contains(request.deviceSecret), "the device secret is stored hashed")
    }
    assertFalse(text.contains(grant.token), "no bearer on disk")
    assertFalse(text.contains(ServeAgentGrantStore.TOKEN_PREFIX), "no bearer on disk")
    assertFalse(text.contains(ServeAgentGrantStore.browserCredentialValue(grant.token)))
    assertFalse(text.contains(ServeAgentGrantStore.wasmCredentialValue(grant.token)))

    val collected =
      assertIs<ServeAgentGrantStore.Poll.Approved>(store().poll(approved.id, approved.deviceSecret))
    assertFalse(file.readText().contains(collected.grant.token), "nor the lazily minted one")
  }

  @Test
  fun `a store without a file writes nothing and behaves as before`() {
    val memoryOnly = store(file = null)
    val request = memoryOnly.ask()
    assertNotNull(
      memoryOnly.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "")
    )
    assertIs<ServeAgentGrantStore.Poll.Approved>(memoryOnly.poll(request.id, request.deviceSecret))
    assertFalse(file.exists())
    assertTrue(Files.list(dir).use { it.count() } == 0L, "nothing at all under the data directory")
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store(file = null).poll(request.id, ""))
  }

  @Test
  fun `a request opened by an overlapping process is found on the shared file`() {
    val oldProcess = store()
    val newProcess = store()
    // The old process is still serving during the deploy's overlap and opens a request.
    val request = oldProcess.ask()
    assertIs<ServeAgentGrantStore.Poll.Pending>(newProcess.poll(request.id, request.deviceSecret))
    // ...and a human approves it on the old process before it stops.
    assertNotNull(
      oldProcess.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "")
    )
    assertIs<ServeAgentGrantStore.Poll.Approved>(newProcess.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `the default file lives beside the catalogs file, and nowhere without one`() {
    val catalogs = dir.resolve("catalogs.json")
    assertEquals(
      dir.resolve("mcp-oauth/agent-access-requests.json"),
      ServeAgentGrantStore.defaultRequestsFile(catalogs),
    )
    assertNull(ServeAgentGrantStore.defaultRequestsFile(null))
  }
}
