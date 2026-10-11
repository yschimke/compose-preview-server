package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
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
  private val file: Path = dir.resolve("mcp-oauth/agent-access-requests.v2.json")
  /** The file the release before the v2 format reads and writes. */
  private val legacy: Path = dir.resolve("mcp-oauth/agent-access-requests.json")

  @AfterTest
  fun cleanUp() {
    dir.toFile().deleteRecursively()
  }

  private fun store(
    file: Path? = this.file,
    maxScope: AgentGrantScope = AgentGrantScope.PLAYGROUND,
    maxCapabilities: Set<AgentGrantCapability> = setOf(AgentGrantCapability.IMAGES),
    maxGrantTtlSeconds: Long = 3600,
    maxActiveGrants: Int = 16,
    maxActiveGrantsPerApprover: Int = 8,
    audit: (String) -> Unit = {},
    interleave: (String) -> Unit = {},
  ) =
    ServeAgentGrantStore(
      maxGrantTtlSeconds = maxGrantTtlSeconds,
      maxScope = maxScope,
      maxCapabilities = maxCapabilities,
      maxActiveGrants = maxActiveGrants,
      maxActiveGrantsPerApprover = maxActiveGrantsPerApprover,
      clock = { now },
      audit = audit,
      requestsFile = file,
      interleaveHook = interleave,
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

  /** Approve through [ServeAgentGrantStore.decide], asserting this decision stands. */
  private fun ServeAgentGrantStore.approveOk(
    id: String,
    approvedBy: String,
    scope: AgentGrantScope,
    ttlSeconds: Long,
    capabilities: Set<AgentGrantCapability>,
    approvedByActorId: String,
  ): ServeAgentGrantStore.Approval =
    assertIs<ServeAgentGrantStore.Decision.Approved>(
        decide(id, approvedBy, scope, ttlSeconds, capabilities, approvedByActorId)
      )
      .approval

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

    reloaded.approveOk(
      request.id,
      "@yuri",
      AgentGrantScope.LIVE,
      600,
      setOf(AgentGrantCapability.IMAGES),
      "github:yuri",
    )
    // With a requests file nothing is minted at approval; the poll that collects it mints.
    assertTrue(reloaded.activeGrants().isEmpty())
    val approved =
      assertIs<ServeAgentGrantStore.Poll.Approved>(reloaded.poll(request.id, request.deviceSecret))
    assertEquals(setOf("home"), approved.grant.designIds)
    assertEquals(listOf(approved.grant), reloaded.activeGrants())
  }

  @Test
  fun `an approved request nobody collected yet mints its grant on the first poll after a restart`() {
    val first = store()
    val request = first.ask()
    now += 10_000
    val approvedAt = now
    assertNotNull(
      first.approveOk(
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

    // Collected is a state on disk: a second restart does not mint again.
    assertNotPollable(store().poll(request.id, request.deviceSecret))
  }

  @Test
  fun `an approval whose grant would already have expired is not collected after a restart`() {
    val first = store()
    val request = first.ask()
    assertNotNull(first.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 60, emptySet(), "u"))
    now += 30_000
    val reloaded = store()
    now += 31_000
    // Past the grant's lifetime the row itself is gone, so the request reads as unknown; either
    // way, nothing is handed out.
    assertNotPollable(reloaded.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `a revoked grant is not minted again by a restart`() {
    val first = store()
    val request = first.ask()
    assertNotNull(first.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u"))
    assertTrue(first.revokeRequest(request.id, "@yuri"))
    assertNotPollable(first.poll(request.id, request.deviceSecret))
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
      reloaded.approveOk(request.id, "@yuri", AgentGrantScope.PREVIEW, 600, emptySet(), "u")
    )
    assertNotNull(reloaded.activeGrantForRequester("github:ana"))
    // Nobody polls a session-carried grant, so its approval is not kept to be minted again.
    val afterRestart = store()
    assertTrue(assertNotNull(afterRestart.request(request.id)).collected, "collected at approval")
    assertNull(afterRestart.activeGrantForRequester("github:ana"))
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
    assertNotNull(first.approveOk(approved.id, "@yuri", AgentGrantScope.LIVE, 60, emptySet(), "u"))

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
    first.approveOk(approved.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
    val denied = first.ask().also { first.deny(it.id, "@yuri") }
    val self =
      assertNotNull(
        first.openRequest("mine", "10.0.0.1", AgentGrantScope.PREVIEW, 600, requesterActorId = "a")
      )
    // A person's own request is minted at approval (nobody polls it): its token stays off disk too.
    val grant =
      assertNotNull(
        assertIs<ServeAgentGrantStore.Decision.Approved>(
            first.decide(self.id, "@yuri", AgentGrantScope.PREVIEW, 600, emptySet(), "u")
          )
          .grant
      )

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
      oldProcess.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "")
    )
    assertIs<ServeAgentGrantStore.Poll.Approved>(newProcess.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `the default file lives beside the catalogs file, and nowhere without one`() {
    val catalogs = dir.resolve("catalogs.json")
    assertEquals(
      dir.resolve("mcp-oauth/agent-access-requests.v2.json"),
      ServeAgentGrantStore.defaultRequestsFile(catalogs),
    )
    assertNull(ServeAgentGrantStore.defaultRequestsFile(null))
  }

  // ---- the overlap, capacity and ceilings ------------------------------------------------------

  @Test
  fun `a revocation on the old process during a deploy overlap is not undone by the new one`() {
    val oldProcess = store()
    val request = oldProcess.ask()
    oldProcess.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    // The new process starts while the approval is uncollected, and loads it.
    val newProcess = store()
    assertNotNull(newProcess.request(request.id))
    // The approver revokes on the old process before the agent polls.
    assertTrue(oldProcess.revokeRequest(request.id, "@yuri"))

    val poll = newProcess.poll(request.id, request.deviceSecret)
    assertTrue(poll !is ServeAgentGrantStore.Poll.Approved, "revocation undone: $poll")
    assertTrue(newProcess.activeGrants().isEmpty())
    // And a third process, later, does not resurrect it either.
    assertTrue(
      store().poll(request.id, request.deviceSecret) !is ServeAgentGrantStore.Poll.Approved
    )
  }

  @Test
  fun `an approval collected on the old process is not minted a second time by the new one`() {
    val oldProcess = store()
    val request = oldProcess.ask()
    assertNotNull(
      oldProcess.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    )
    val newProcess = store()
    assertIs<ServeAgentGrantStore.Poll.Approved>(oldProcess.poll(request.id, request.deviceSecret))

    val poll = newProcess.poll(request.id, request.deviceSecret)
    assertTrue(
      poll !is ServeAgentGrantStore.Poll.Approved,
      "a second bearer for one approval: $poll",
    )
  }

  @Test
  fun `restored approvals hold their grant's place, so the box cap still holds after a restart`() {
    val first = store(maxActiveGrants = 2)
    repeat(2) {
      val request = first.ask()
      assertNotNull(
        first.approveOk(request.id, "@ana", AgentGrantScope.LIVE, 600, emptySet(), "a$it")
      )
    }

    val reloaded = store(maxActiveGrants = 2)
    assertEquals(
      ServeAgentGrantStore.Capacity.BOX_FULL,
      reloaded.capacityFor("@yuri", "github:yuri"),
    )
    val third = reloaded.ask()
    assertIs<ServeAgentGrantStore.Decision.NoCapacity>(
      reloaded.decide(third.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri"),
      "two uncollected approvals already hold both places",
    )
  }

  @Test
  fun `restored approvals count against their approver's cap`() {
    val first = store(maxActiveGrantsPerApprover = 1)
    val request = first.ask()
    assertNotNull(
      first.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
    )

    val reloaded = store(maxActiveGrantsPerApprover = 1)
    assertEquals(
      ServeAgentGrantStore.Capacity.APPROVER_FULL,
      reloaded.capacityFor("@yuri", "github:yuri"),
    )
    val second = reloaded.ask()
    assertIs<ServeAgentGrantStore.Decision.NoCapacity>(
      reloaded.decide(second.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
    )
  }

  @Test
  fun `collecting re-enforces the cap, refusing with a reason rather than exceeding it`() {
    // Four approvals under a cap of four; the operator then redeploys with a cap of two.
    val a = store(maxActiveGrants = 4)
    val b = store(maxActiveGrants = 4)
    val requests =
      listOf(a, a, b, b).mapIndexed { i, process ->
        val request = process.ask()
        assertNotNull(
          process.approveOk(request.id, "@p$i", AgentGrantScope.LIVE, 600, emptySet(), "p$i")
        )
        request
      }

    val reloaded = store(maxActiveGrants = 2)
    val polls = requests.map { reloaded.poll(it.id, it.deviceSecret) }
    assertEquals(2, polls.count { it is ServeAgentGrantStore.Poll.Approved }, polls.toString())
    val refused = polls.filterIsInstance<ServeAgentGrantStore.Poll.Denied>()
    assertEquals(2, refused.size, polls.toString())
    refused.forEach { assertNotNull(it.reason, "a refusal says why") }
    assertEquals(2, reloaded.activeGrants().size)
  }

  @Test
  fun `a restored approval is clamped to the ceilings the server runs with now`() {
    val first = store()
    val request = first.ask()
    now += 1_000
    val approvedAt = now
    assertNotNull(first.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 1800, emptySet(), "u"))

    // Redeployed with a lower scope ceiling and a shorter maximum lifetime.
    val reloaded = store(maxScope = AgentGrantScope.PREVIEW, maxGrantTtlSeconds = 60)
    val grant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(reloaded.poll(request.id, request.deviceSecret))
        .grant
    assertEquals(AgentGrantScope.PREVIEW, grant.scope)
    assertEquals(approvedAt + 60_000, grant.expiresAtMillis)
  }

  @Test
  fun `a restored approval whose every capability was withdrawn is refused, not minted bare`() {
    val first = store()
    val request = first.ask()
    assertNotNull(
      first.approveOk(
        request.id,
        "@yuri",
        AgentGrantScope.LIVE,
        600,
        setOf(AgentGrantCapability.IMAGES),
        "u",
      )
    )

    val reloaded = store(maxCapabilities = emptySet())
    val denied =
      assertIs<ServeAgentGrantStore.Poll.Denied>(reloaded.poll(request.id, request.deviceSecret))
    assertNotNull(denied.reason)
    assertTrue(reloaded.activeGrants().isEmpty())
  }

  // ---- claiming, refusal lifetime and the reservation's clamp ----------------------------------

  @Test
  fun `two processes polling one restored approval at once hand out exactly one bearer`() {
    val origin = store()
    val request = origin.ask()
    assertNotNull(origin.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u"))

    // Both processes restored the approval. B is interleaved: after it has read the row as
    // approved and built its grant, just before its compare-and-set, A polls and collects.
    val a = store()
    var interleaved: ServeAgentGrantStore.Poll? = null
    val b =
      store(
        interleave = { point ->
          if (interleaved == null && point == "collect") {
            interleaved = a.poll(request.id, request.deviceSecret)
          }
        }
      )
    val fromB = b.poll(request.id, request.deviceSecret)
    val fromA = assertNotNull(interleaved, "the interleaving hook ran")

    val winners = listOf(fromA, fromB).filterIsInstance<ServeAgentGrantStore.Poll.Approved>()
    assertEquals(1, winners.size, "A=$fromA B=$fromB")
    // The loser holds no live grant for it either.
    assertEquals(1, a.activeGrants().size + b.activeGrants().size)
  }

  @Test
  fun `the approving process mints nothing, and issues nothing once another process collected`() {
    val approver = store()
    val request = approver.ask()
    assertNotNull(
      approver.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    )
    assertTrue(approver.activeGrants().isEmpty(), "no grant exists before collection")
    // A new process restored it and collected first.
    assertIs<ServeAgentGrantStore.Poll.Approved>(store().poll(request.id, request.deviceSecret))

    val late = approver.poll(request.id, request.deviceSecret)
    assertTrue(late !is ServeAgentGrantStore.Poll.Approved, "a second bearer: $late")
    assertTrue(approver.activeGrants().isEmpty(), "and none after")
  }

  @Test
  fun `a refusal after the request deadline is kept until the approval would have expired`() {
    val first = store()
    val request = first.ask()
    now += 10 * 60_000
    assertNotNull(
      first.approveOk(
        request.id,
        "@yuri",
        AgentGrantScope.LIVE,
        1800,
        setOf(AgentGrantCapability.IMAGES),
        "u",
      )
    )
    // Past the request's 30-minute deadline, inside the grant's 40.
    now += 25 * 60_000
    val refusing = store(maxCapabilities = emptySet())
    assertIs<ServeAgentGrantStore.Poll.Denied>(refusing.poll(request.id, request.deviceSecret))
    assertIs<ServeAgentGrantStore.Poll.Denied>(refusing.poll(request.id, request.deviceSecret))

    val denied =
      assertIs<ServeAgentGrantStore.Poll.Denied>(
        store(maxCapabilities = emptySet()).poll(request.id, request.deviceSecret)
      )
    assertNotNull(denied.reason)
  }

  @Test
  fun `an approval past a lowered lifetime ceiling stops holding a place`() {
    val first = store(maxActiveGrants = 1)
    val request = first.ask()
    assertNotNull(first.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 1800, emptySet(), "u"))

    // Redeployed with a one-minute ceiling, two minutes after the approval.
    now += 2 * 60_000
    val reloaded = store(maxActiveGrants = 1, maxGrantTtlSeconds = 60)
    assertEquals(ServeAgentGrantStore.Capacity.OK, reloaded.capacityFor("@ana", "github:ana"))
    assertEquals(ServeAgentGrantStore.Poll.Expired, reloaded.poll(request.id, request.deviceSecret))
    val next = reloaded.ask()
    assertNotNull(reloaded.approveOk(next.id, "@ana", AgentGrantScope.LIVE, 60, emptySet(), "a"))
    // Dropped on disk too: a later process does not restore it as a reservation.
    val later = store(maxActiveGrants = 2, maxGrantTtlSeconds = 3600)
    assertNull(later.request(request.id))
  }

  // ---- the file is the authority during an overlap ---------------------------------------------

  @Test
  fun `two processes approving one request at once - the first decision wins and the loser issues nothing`() {
    val a = store()
    val request = a.ask()
    var inner: ServeAgentGrantStore.Decision? = null
    // B has read the request as pending; just before B's compare-and-set, A approves it wider.
    val b =
      store(
        interleave = { point ->
          if (inner == null && point == "decide") {
            inner = a.decide(request.id, "@ana", AgentGrantScope.LIVE, 600, emptySet(), "ana")
          }
        }
      )
    val outer = b.decide(request.id, "@yuri", AgentGrantScope.PREVIEW, 600, emptySet(), "yuri")

    assertIs<ServeAgentGrantStore.Decision.Approved>(inner)
    val lost = assertIs<ServeAgentGrantStore.Decision.AlreadyDecided>(outer)
    assertEquals(AgentGrantScope.LIVE, lost.request.approval?.scope, "reflects the winner")
    assertTrue(a.activeGrants().isEmpty() && b.activeGrants().isEmpty(), "nothing minted")
    val collected =
      assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(request.id, request.deviceSecret))
    assertEquals(AgentGrantScope.LIVE, collected.grant.scope)
    assertEquals("@ana", collected.grant.approvedBy)
  }

  @Test
  fun `an approval and a denial at once - whichever lands first stands`() {
    // The denial is interleaved before the approval's compare-and-set: the denial stands.
    val a = store()
    val denied = a.ask()
    var deniedFirst: Boolean? = null
    val b =
      store(
        interleave = { point ->
          if (deniedFirst == null && point == "decide") deniedFirst = a.deny(denied.id, "@ana")
        }
      )
    val lost = b.decide(denied.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "yuri")
    assertEquals(true, deniedFirst)
    assertEquals(
      ServeAgentGrantStore.Request.State.DENIED,
      assertIs<ServeAgentGrantStore.Decision.AlreadyDecided>(lost).request.state,
    )
    assertIs<ServeAgentGrantStore.Poll.Denied>(b.poll(denied.id, denied.deviceSecret))

    // The approval is interleaved before the denial's compare-and-set: the approval stands.
    val approved = a.ask()
    var approvedFirst: ServeAgentGrantStore.Decision? = null
    val c =
      store(
        interleave = { point ->
          if (approvedFirst == null && point == "deny") {
            approvedFirst =
              a.decide(approved.id, "@ana", AgentGrantScope.LIVE, 600, emptySet(), "ana")
          }
        }
      )
    assertFalse(c.deny(approved.id, "@yuri"), "the denial lost")
    assertIs<ServeAgentGrantStore.Decision.Approved>(approvedFirst)
    assertIs<ServeAgentGrantStore.Poll.Approved>(c.poll(approved.id, approved.deviceSecret))
  }

  @Test
  fun `a collection the file cannot record hands out no bearer and asks the agent to retry`() {
    val store = store()
    val request = store.ask()
    store.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val good = file.readText()

    // Unreadable: the file is corrupt.
    file.writeText("{not json")
    assertIs<ServeAgentGrantStore.Poll.Pending>(store.poll(request.id, request.deviceSecret))
    assertTrue(store.activeGrants().isEmpty())
    assertNull(store.collectGrant(request.id))

    // Unwritable: the lock cannot be taken.
    file.writeText(good)
    val lock = file.resolveSibling("${file.fileName}.lock")
    Files.deleteIfExists(lock)
    Files.createDirectory(lock)
    assertIs<ServeAgentGrantStore.Poll.Pending>(store.poll(request.id, request.deviceSecret))
    assertTrue(store.activeGrants().isEmpty())

    // Once the file works again the same poll collects.
    Files.delete(lock)
    assertIs<ServeAgentGrantStore.Poll.Approved>(store.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `revoking on one process a bearer collected on another stops it there`() {
    val a = store()
    val request = a.ask()
    a.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val b = store()
    val grant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(request.id, request.deviceSecret)).grant
    assertEquals(grant, b.grantForToken(grant.token))

    assertFalse(a.revoke("not-a-grant-id-anyone-knows", "@yuri"), "nothing to revoke")
    assertEquals("u", a.approvalForGrant(grant.id)?.approvedByActorId)
    assertTrue(a.revoke(grant.id, "@yuri"), "the row naming it was revoked")

    assertNull(b.grantForToken(grant.token))
    assertEquals(ServeAgentGrantStore.TokenState.UNKNOWN, b.describeToken(grant.token))
    assertTrue(b.activeGrants().isEmpty())
    assertNotPollable(b.poll(request.id, request.deviceSecret))
    // And a later process does not bring it back.
    assertNotPollable(store().poll(request.id, request.deviceSecret))
  }

  @Test
  fun `a grant collected on another process counts against the box cap here`() {
    val a = store(maxActiveGrants = 1)
    val b = store(maxActiveGrants = 1)
    val first = a.ask()
    a.approveOk(first.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
    assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(first.id, first.deviceSecret))
    assertTrue(a.activeGrants().isEmpty(), "the grant lives only in B")

    assertEquals(ServeAgentGrantStore.Capacity.BOX_FULL, a.capacityFor("@ana", "github:ana"))
    val second = a.ask()
    assertIs<ServeAgentGrantStore.Decision.NoCapacity>(
      a.decide(second.id, "@ana", AgentGrantScope.LIVE, 600, emptySet(), "github:ana")
    )
    // Counted once in B, which holds it.
    assertEquals(ServeAgentGrantStore.Capacity.BOX_FULL, b.capacityFor("@ana", "github:ana"))

    // Revoked, it holds no place anywhere.
    assertTrue(a.revoke(b.activeGrants().single().id, "@yuri"))
    assertEquals(ServeAgentGrantStore.Capacity.OK, a.capacityFor("@ana", "github:ana"))
  }

  @Test
  fun `a grant collected on another process counts against its approver's cap here`() {
    val a = store(maxActiveGrantsPerApprover = 1)
    val b = store(maxActiveGrantsPerApprover = 1)
    val first = a.ask()
    a.approveOk(first.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
    assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(first.id, first.deviceSecret))

    assertEquals(ServeAgentGrantStore.Capacity.APPROVER_FULL, a.capacityFor("@yuri", "github:yuri"))
    assertEquals(ServeAgentGrantStore.Capacity.OK, a.capacityFor("@ana", "github:ana"))
    val second = a.ask()
    assertIs<ServeAgentGrantStore.Decision.NoCapacity>(
      a.decide(second.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "github:yuri")
    )
  }

  @Test
  fun `collecting counts grants collected on another process`() {
    // Two approvals under a cap of two; B collects one, then the cap is one on A's side.
    val a = store(maxActiveGrants = 2)
    val b = store(maxActiveGrants = 2)
    val first = a.ask()
    val second = a.ask()
    a.approveOk(first.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "y")
    a.approveOk(second.id, "@ana", AgentGrantScope.LIVE, 600, emptySet(), "a")
    assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(first.id, first.deviceSecret))

    val lowered = store(maxActiveGrants = 1)
    val refused =
      assertIs<ServeAgentGrantStore.Poll.Denied>(lowered.poll(second.id, second.deviceSecret))
    assertNotNull(refused.reason)
  }

  // ---- the previous release's file -------------------------------------------------------------

  /** A row as the previous release writes it: no grant id, no fingerprint, its own states. */
  private fun legacyRow(id: String, state: String, secret: String, expiresAtMillis: Long) =
    """{"id":"$id","deviceSecretSha256":"${ServeAgentGrantStore.secretHash(secret)}",""" +
      """"userCode":"ACDE-FGHJ","label":"from the old release","client":"10.0.0.9",""" +
      """"requestedScope":"live","requestedTtlSeconds":600,"createdAtMillis":$now,""" +
      """"expiresAtMillis":$expiresAtMillis,"state":"$state"}"""

  private fun writeLegacy(vararg rows: String) {
    Files.createDirectories(legacy.parent)
    legacy.writeText("""{"version":1,"requests":[${rows.joinToString(",")}]}""")
  }

  @Test
  fun `the default file is one the previous release never opens, and says what format it is`() {
    val catalogs = dir.resolve("catalogs.json")
    val path = assertNotNull(ServeAgentGrantStore.defaultRequestsFile(catalogs))
    assertTrue(path != legacy, "the previous release reads and rewrites $legacy")
    val store = store(file = path)
    store.ask()
    assertTrue(path.readText().contains("\"format\""), path.readText())
    assertFalse(legacy.exists(), "the old file is never written")
  }

  @Test
  fun `an old-format writer on the previous file leaves this store's rows alone`() {
    val store = store()
    val request = store.ask()
    store.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val ours = file.readText()

    // The outgoing release rewrites its own file, rows of its own and all.
    writeLegacy(legacyRow(request.id, "pending", "whatever-secret", now + 600_000))

    assertEquals(ours, file.readText())
    val grant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(store.poll(request.id, request.deviceSecret))
        .grant
    assertTrue(file.readText().contains(grant.id), "the collected row keeps its grant id")
    assertEquals(
      "pending",
      Regex("\"state\":\"(\\w+)\"").find(legacy.readText())?.groupValues?.get(1),
      "and this store never wrote the old file",
    )
  }

  @Test
  fun `pending requests in the previous release's file are carried over at start, and nothing else`() {
    val pendingId = "pendingRequestFromV1x"
    val approvedId = "approvedRequestFromV1"
    val expiredId = "expiredRequestFromV1x"
    writeLegacy(
      legacyRow(pendingId, "pending", "pending-secret", now + 600_000),
      legacyRow(approvedId, "approved", "approved-secret", now + 600_000),
      legacyRow(expiredId, "pending", "expired-secret", now - 1),
    )
    val before = legacy.readText()

    val store = store()
    assertIs<ServeAgentGrantStore.Poll.Pending>(store.poll(pendingId, "pending-secret"))
    assertEquals("from the old release", assertNotNull(store.request(pendingId)).label)
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(approvedId, "approved-secret"))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(expiredId, "expired-secret"))
    assertEquals(before, legacy.readText(), "read, never written")

    // A decision made here is not undone by carrying it over again on the next start.
    store.approveOk(pendingId, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val restarted = store()
    assertIs<ServeAgentGrantStore.Poll.Approved>(restarted.poll(pendingId, "pending-secret"))
  }

  @Test
  fun `a revocation the file cannot record says so rather than claiming success`() {
    val a = store()
    val request = a.ask()
    a.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val b = store()
    val grant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(request.id, request.deviceSecret)).grant
    a.grantsHeldElsewhere()
    val good = file.readText()
    file.writeText("{not json")

    assertEquals(ServeAgentGrantStore.Revocation.UNAVAILABLE, a.revokeGrant(grant.id, "@yuri"))
    file.writeText(good)
    assertEquals(grant, b.grantForToken(grant.token), "nothing was revoked")
    assertEquals(ServeAgentGrantStore.Revocation.REVOKED, a.revokeGrant(grant.id, "@yuri"))
    assertNull(b.grantForToken(grant.token))
    assertEquals(ServeAgentGrantStore.Revocation.UNKNOWN, a.revokeGrant("nobody-knows", "@yuri"))
  }

  // ---- a missing file fails closed; rows live as long as their grant ---------------------------

  @Test
  fun `a revocation is not forgotten when the file goes missing before the holder checks`() {
    val a = store()
    val revoked = a.ask()
    val kept = a.ask()
    a.approveOk(revoked.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    a.approveOk(kept.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val pending = a.ask()
    val revokedGrant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(a.poll(revoked.id, revoked.deviceSecret)).grant
    val keptGrant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(a.poll(kept.id, kept.deviceSecret)).grant
    // B revokes one; the file then disappears before A's next token check.
    val b = store()
    assertEquals(ServeAgentGrantStore.Revocation.REVOKED, b.revokeGrant(revokedGrant.id, "@yuri"))
    val afterRevoke = file.readText()
    Files.delete(file)

    assertNull(a.grantForToken(revokedGrant.token), "a revocation A cannot read is not ignored")
    assertNull(a.grantForToken(keptGrant.token), "nor can A vouch for any grant meanwhile")
    assertIs<ServeAgentGrantStore.Poll.Pending>(a.poll(pending.id, pending.deviceSecret))
    assertEquals(
      ServeAgentGrantStore.Decision.Unavailable,
      a.decide(pending.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u"),
      "a missing file is unavailable, not empty",
    )

    // The file back: the revocation holds and the other grant is good again.
    file.writeText(afterRevoke)
    assertNull(a.grantForToken(revokedGrant.token))
    assertEquals(keptGrant, a.grantForToken(keptGrant.token))
  }

  @Test
  fun `a revocation once seen outlives the row that carried it`() {
    val a = store()
    val request = a.ask()
    a.approveOk(request.id, "@yuri", AgentGrantScope.LIVE, 600, emptySet(), "u")
    val b = store()
    val grant =
      assertIs<ServeAgentGrantStore.Poll.Approved>(b.poll(request.id, request.deviceSecret)).grant
    assertEquals(ServeAgentGrantStore.Revocation.REVOKED, a.revokeGrant(grant.id, "@yuri"))
    assertNull(b.grantForToken(grant.token))
    // Even a file rewritten without the row cannot bring it back.
    val stripped =
      Regex("\\{\"id\":\"${request.id}\"[^{}]*(\\{[^{}]*\\}[^{}]*)*\\},?")
        .replace(file.readText(), "")
        .replace(",]", "]")
    file.writeText(stripped)
    assertFalse(file.readText().contains(request.id))
    assertNull(b.grantForToken(grant.token))
  }

  @Test
  fun `a row with a short approval goes when its grant would, not at the request deadline`() {
    val store = store()
    val short = store.ask()
    store.approveOk(short.id, "@yuri", AgentGrantScope.LIVE, 60, emptySet(), "u")
    val revoked = store.ask()
    store.approveOk(revoked.id, "@yuri", AgentGrantScope.LIVE, 120, emptySet(), "u")
    assertTrue(store.revokeRequest(revoked.id, "@yuri"))
    val pending = store.ask()

    now += 61_000
    store.ask() // any write rewrites the file without what has expired
    var text = file.readText()
    assertFalse(text.contains(short.id), "a one-minute approval is gone at a minute")
    assertTrue(text.contains(revoked.id), "a revoked tombstone lives as long as its grant would")
    assertTrue(text.contains(pending.id), "a pending request keeps its own deadline")

    now += 60_000
    store.ask()
    text = file.readText()
    assertFalse(text.contains(revoked.id))
    assertTrue(text.contains(pending.id))
  }
}
