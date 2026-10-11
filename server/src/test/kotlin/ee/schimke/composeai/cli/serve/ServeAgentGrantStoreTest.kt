package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The grant state machine: what a request becomes, who may collect it, and what a grant may do.
 * Each security claim in
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md)
 * gets a test: tokens go to the device secret not the link, approvals only narrow, everything
 * expires.
 */
class ServeAgentGrantStoreTest {

  private var now = 1_000_000L

  private fun store(
    maxScope: AgentGrantScope = AgentGrantScope.PLAYGROUND,
    maxGrantTtlSeconds: Long = 3600,
    maxActiveGrants: Int = 16,
    maxPendingRequests: Int = 32,
    maxCapabilities: Set<AgentGrantCapability> = emptySet(),
  ) =
    ServeAgentGrantStore(
      maxGrantTtlSeconds = maxGrantTtlSeconds,
      maxScope = maxScope,
      maxActiveGrants = maxActiveGrants,
      maxPendingRequests = maxPendingRequests,
      maxCapabilities = maxCapabilities,
      clock = { now },
    )

  private fun ServeAgentGrantStore.ask(
    scope: AgentGrantScope = AgentGrantScope.LIVE,
    ttl: Long = 1800,
  ) = openRequest("fix #1", "10.0.0.1", scope, ttl)!!

  @Test
  fun `a grant records who approved it, as an actor id the rest of the server understands`() {
    val store = store()
    val request = store.ask()
    val approver = ServeAgentGrants.Approver.github("yuri", true, true, AgentGrantScope.PLAYGROUND)
    val grant =
      assertNotNull(
        store.approve(
          request.id,
          approver.name,
          AgentGrantScope.LIVE,
          600,
          emptySet(),
          approver.actorId,
        )
      )

    // The display name is for a page and a log line; the actor id is what a design is owned by,
    // and the UI builder needs the second to let the approver open what their agent created.
    assertEquals("@yuri", grant.approvedBy)
    assertEquals("github:yuri", grant.approvedByActorId)
    assertEquals(
      "operator",
      ServeAgentGrants.Approver.operator(AgentGrantScope.PLAYGROUND).actorId,
      "the token holder is the same person in a browser as they are here",
    )

    // A grant nobody named an approver for carries no delegation rather than a blank one.
    val anonymous = store.ask()
    assertEquals(
      "",
      assertNotNull(store.approve(anonymous.id, "?", AgentGrantScope.LIVE, 600)).approvedByActorId,
    )
  }

  @Test
  fun `the token goes to the device secret, not to the link`() {
    val store = store()
    val request = store.ask()
    store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)

    // Someone holding the link but not the secret gets the same answer as someone holding neither.
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(request.id, "not-the-secret"))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(request.id, null))

    val collected = store.poll(request.id, request.deviceSecret)
    assertTrue(collected is ServeAgentGrantStore.Poll.Approved)
    assertTrue(collected.grant.token.startsWith(ServeAgentGrantStore.TOKEN_PREFIX))
  }

  @Test
  fun `an unknown id is indistinguishable from a wrong secret`() {
    val store = store()
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll("Zm9vYmFyYmF6cXV1eA", "whatever"))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll("!!not-well-formed!!", "whatever"))
  }

  @Test
  fun `approval may narrow a request but never widen it`() {
    val store = store(maxScope = AgentGrantScope.PLAYGROUND)
    val request = store.ask(scope = AgentGrantScope.LIVE, ttl = 900)

    val grant = store.approve(request.id, "@yuri", AgentGrantScope.PLAYGROUND, 99_999)!!
    assertEquals(AgentGrantScope.LIVE, grant.scope)
    assertEquals(900, (grant.expiresAtMillis - grant.issuedAtMillis) / 1000)
  }

  @Test
  fun `the operator ceiling clamps a request at the door`() {
    val store = store(maxScope = AgentGrantScope.PREVIEW)
    val request = store.ask(scope = AgentGrantScope.PLAYGROUND)
    assertEquals(AgentGrantScope.PREVIEW, request.requestedScope)

    val grant = store.approve(request.id, "@yuri", AgentGrantScope.PLAYGROUND, 600)!!
    assertEquals(AgentGrantScope.PREVIEW, grant.scope)
    assertFalse(grant.allows(AgentGrantScope.LIVE))
  }

  @Test
  fun `a capability the ceiling excludes stays in the request and out of the grant`() {
    // The request remembers the ask so the approval page can render it, but the mint enforces the
    // box's ceiling.
    val store =
      store(
        maxScope = AgentGrantScope.PLAYGROUND,
        maxCapabilities = setOf(AgentGrantCapability.IMAGES),
      )
    val request =
      store.openRequest(
        label = "builder access",
        client = "10.0.0.1",
        requestedScope = AgentGrantScope.PREVIEW,
        requestedTtlSeconds = 600,
        requestedCapabilities =
          setOf(
            AgentGrantCapability.IMAGES,
            AgentGrantCapability.UI_BUILDER_READ,
            AgentGrantCapability.UI_BUILDER_WRITE,
          ),
      )!!
    assertEquals(
      setOf(
        AgentGrantCapability.IMAGES,
        AgentGrantCapability.UI_BUILDER_READ,
        AgentGrantCapability.UI_BUILDER_WRITE,
      ),
      request.requestedCapabilities,
      "the ask survives registration — the page, not the store, narrows what is offered",
    )

    // The approver ticks everything the page showed them as granted-able; the ceiling still keeps
    // the ui-builder capability out of the mint.
    val grant =
      store.approve(
        request.id,
        "@yuri",
        AgentGrantScope.PREVIEW,
        600,
        setOf(AgentGrantCapability.IMAGES, AgentGrantCapability.UI_BUILDER_READ),
      )!!
    assertEquals(setOf(AgentGrantCapability.IMAGES), grant.capabilities)
  }

  @Test
  fun `scopes are cumulative`() {
    val store = store()
    val request = store.ask(scope = AgentGrantScope.PLAYGROUND)
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.PLAYGROUND, 600)!!
    assertTrue(grant.allows(AgentGrantScope.PREVIEW))
    assertTrue(grant.allows(AgentGrantScope.LIVE))
    assertTrue(grant.allows(AgentGrantScope.PLAYGROUND))
    assertEquals(listOf("preview", "live", "playground"), grant.scopes.map { it.wire })
  }

  @Test
  fun `approving twice mints one grant, not two`() {
    val store = store()
    val request = store.ask()
    val first = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)!!
    val second = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)!!
    assertEquals(first.id, second.id)
    assertEquals(1, store.activeGrants().size)
  }

  @Test
  fun `a denied request cannot then be approved`() {
    val store = store()
    val request = store.ask()
    assertTrue(store.deny(request.id, "@yuri"))
    assertNull(store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600))
    val polled = store.poll(request.id, request.deviceSecret)
    assertTrue(polled is ServeAgentGrantStore.Poll.Denied)
  }

  @Test
  fun `an approved request cannot then be denied`() {
    val store = store()
    val request = store.ask()
    store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)
    assertFalse(store.deny(request.id, "@someone-else"))
  }

  @Test
  fun `a request expires, and its poll says so`() {
    val store = store()
    val request = store.ask()
    now += (store.requestTtlSeconds + 1) * 1000
    assertNull(store.request(request.id))
    assertEquals(ServeAgentGrantStore.Poll.Unknown, store.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `a grant expires and stops authorising`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 60)!!
    assertNotNull(store.grantForToken(grant.token))
    now += 61_000
    assertNull(store.grantForToken(grant.token))
    assertTrue(store.activeGrants().isEmpty())
  }

  @Test
  fun `a browser credential is derived and shares grant expiry and revocation`() {
    val store = store()
    val expiring = store.approve(store.ask().id, "@yuri", AgentGrantScope.LIVE, 60)!!
    val expiringCredential = requireNotNull(store.browserCredentialFor(expiring))
    assertFalse(expiringCredential.value.contains(expiring.token))
    assertNotEquals(expiring.token, expiringCredential.value)
    assertEquals(60, expiringCredential.maxAgeSeconds)
    assertEquals(expiring, store.grantForBrowserCredential(expiringCredential.value))
    assertNull(store.grantForBrowserCredential(expiring.token))
    val wasmCredential = requireNotNull(store.wasmCredentialFor(expiring))
    assertNotEquals(expiring.token, wasmCredential)
    assertNotEquals(expiringCredential.value, wasmCredential)
    assertEquals(expiring, store.grantForWasmCredential(wasmCredential))

    now += 61_000
    assertNull(store.grantForBrowserCredential(expiringCredential.value))
    assertNull(store.grantForWasmCredential(wasmCredential))

    val revoked = store.approve(store.ask().id, "@yuri", AgentGrantScope.LIVE, 60)!!
    val revokedCredential = requireNotNull(store.browserCredentialFor(revoked)).value
    val revokedWasmCredential = requireNotNull(store.wasmCredentialFor(revoked))
    assertTrue(store.revoke(revoked.id, "@yuri"))
    assertNull(store.grantForBrowserCredential(revokedCredential))
    assertNull(store.grantForWasmCredential(revokedWasmCredential))
  }

  @Test
  fun `a revoked grant stops authorising immediately`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 3600)!!
    assertTrue(store.revoke(grant.id, "@yuri"))
    assertNull(store.grantForToken(grant.token))
    assertFalse(store.revoke(grant.id, "@yuri"))
  }

  @Test
  fun `revoking by token is the agent handing its own access back`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 3600)!!
    assertTrue(store.revokeToken(grant.token, "the agent itself"))
    assertNull(store.grantForToken(grant.token))
  }

  @Test
  fun `polling after a revoke reports expired rather than handing back a dead token`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 3600)!!
    store.revoke(grant.id, "@yuri")
    assertEquals(ServeAgentGrantStore.Poll.Expired, store.poll(request.id, request.deviceSecret))
  }

  @Test
  fun `a full box refuses a new approval rather than ending a live grant`() {
    val store = store(maxActiveGrants = 2)
    fun mint(approver: String, ttl: Long): ServeAgentGrantStore.Grant? {
      val request = store.ask(ttl = ttl)
      return store.approve(
        request.id,
        "@$approver",
        AgentGrantScope.LIVE,
        ttl,
        emptySet(),
        "github:$approver",
      )
    }
    val first = assertNotNull(mint("alice", 60))
    val second = assertNotNull(mint("bob", 600))
    assertEquals(
      ServeAgentGrantStore.Capacity.BOX_FULL,
      store.capacityFor("@carol", "github:carol"),
    )
    assertNull(mint("carol", 3600), "a full box refuses")
    assertNotNull(store.grantForToken(first.token), "nothing live was ended to make room")
    assertNotNull(store.grantForToken(second.token))
  }

  @Test
  fun `a refused approval leaves the request waiting, so it can be approved once there is room`() {
    val store = store(maxActiveGrants = 1)
    val live = assertNotNull(store.approve(store.ask().id, "@yuri", AgentGrantScope.LIVE, 600))
    val waiting = store.ask()
    assertNull(store.approve(waiting.id, "@yuri", AgentGrantScope.LIVE, 600))
    assertEquals(ServeAgentGrantStore.Request.State.PENDING, store.request(waiting.id)?.state)
    store.revoke(live.id, "@yuri")
    assertNotNull(store.approve(waiting.id, "@yuri", AgentGrantScope.LIVE, 600))
  }

  @Test
  fun `the per-approver cap refuses that approver only`() {
    val store =
      ServeAgentGrantStore(
        maxScope = AgentGrantScope.PLAYGROUND,
        maxActiveGrants = 10,
        maxActiveGrantsPerApprover = 2,
        clock = { now },
      )
    fun mint(approver: String, enforce: Boolean = true): ServeAgentGrantStore.Grant? =
      store.approve(
        store.ask().id,
        "@$approver",
        AgentGrantScope.LIVE,
        600,
        emptySet(),
        "github:$approver",
        enforceApproverCap = enforce,
      )
    val mine = listOf(assertNotNull(mint("alice")), assertNotNull(mint("alice")))
    assertEquals(
      ServeAgentGrantStore.Capacity.APPROVER_FULL,
      store.capacityFor("@alice", "github:alice"),
    )
    assertNull(mint("alice"), "a third grant for the same approver is refused")
    assertNotNull(mint("bob"), "another approver is unaffected")
    assertNotNull(
      mint("alice", enforce = false),
      "an administering approver is held to the box cap",
    )
    mine.forEach { assertNotNull(store.grantForToken(it.token)) }
  }

  @Test
  fun `a grant knows who approved it, by actor id when it has one`() {
    val store = store()
    val grant =
      assertNotNull(
        store.approve(
          store.ask().id,
          "@alice",
          AgentGrantScope.LIVE,
          600,
          emptySet(),
          "github:alice",
        )
      )
    assertTrue(grant.isApprovedBy("@alice", "github:alice"))
    assertFalse(
      grant.isApprovedBy("@alice", "github:mallory"),
      "the actor id decides, not the name",
    )
    val legacy =
      assertNotNull(store.approve(store.ask().id, "operator (token)", AgentGrantScope.LIVE, 600))
    assertTrue(legacy.isApprovedBy("operator (token)", "operator"))
    assertFalse(legacy.isApprovedBy("@alice", "github:alice"))
  }

  @Test
  fun `the pending-request cap refuses rather than growing without bound`() {
    val store = store(maxPendingRequests = 2)
    assertNotNull(store.openRequest("a", "ip", AgentGrantScope.PREVIEW, 600))
    assertNotNull(store.openRequest("b", "ip", AgentGrantScope.PREVIEW, 600))
    assertNull(store.openRequest("c", "ip", AgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `the pending cap holds under concurrent anonymous callers`() {
    // Check and insert must be atomic, or a burst could exceed the cap this ungated route relies
    // on.
    val store = store(maxPendingRequests = 8)
    val threads =
      (1..32).map { i ->
        Thread { store.openRequest("burst-$i", "ip-$i", AgentGrantScope.PREVIEW, 600) }
      }
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    assertTrue(
      store.pendingRequests().size <= 8,
      "the cap was exceeded: ${store.pendingRequests().size}",
    )
  }

  @Test
  fun `a denial does not make room for a new one`() {
    // Denying does not free capacity: denials are kept (the owner must learn of them) and charged
    // to the requester until they expire; otherwise each denial hands a hostile sender a fresh
    // slot.
    val store = store(maxPendingRequests = 2)
    val first = store.openRequest("a", "ip", AgentGrantScope.PREVIEW, 600)!!
    store.openRequest("b", "ip", AgentGrantScope.PREVIEW, 600)
    store.deny(first.id, "@yuri")
    assertNull(store.openRequest("c", "ip", AgentGrantScope.PREVIEW, 600))
    // The denial is still readable by its owner while it holds that slot.
    assertTrue(store.poll(first.id, first.deviceSecret) is ServeAgentGrantStore.Poll.Denied)
    now += (store.requestTtlSeconds + 5) * 1000
    assertNotNull(store.openRequest("c", "ip", AgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `a token is never mistaken for the operator token, and vice versa`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)!!
    assertTrue(ServeAgentGrantStore.isWellFormedToken(grant.token))
    // An ordinary `--token` (no prefix) never reaches the map at all.
    assertFalse(ServeAgentGrantStore.isWellFormedToken("plain-operator-token-value"))
    assertNull(store.grantForToken("plain-operator-token-value"))
  }

  @Test
  fun `the fingerprint identifies a grant without disclosing it`() {
    val store = store()
    val request = store.ask()
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)!!
    assertEquals(12, grant.fingerprint.length)
    assertFalse(grant.token.contains(grant.fingerprint))
    assertEquals(grant.fingerprint, AgentGrantProtocol.fingerprintOf(grant.token))
  }

  @Test
  fun `the audit trail names the approver and the fingerprint, never the token`() {
    val lines = mutableListOf<String>()
    val store =
      ServeAgentGrantStore(
        clock = { now },
        maxScope = AgentGrantScope.PLAYGROUND,
        audit = { lines += it },
      )
    val request = store.openRequest("fix #1", "10.0.0.1", AgentGrantScope.LIVE, 600)!!
    val grant = store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)!!
    store.revoke(grant.id, "@yuri")
    assertEquals(2, lines.size)
    assertTrue(lines.all { it.contains(grant.fingerprint) })
    assertTrue(lines.all { !it.contains(grant.token) })
    assertTrue(lines[0].contains("@yuri"))
  }

  @Test
  fun `a user code avoids the characters a human confuses`() {
    repeat(200) {
      val code = ServeAgentGrantStore.randomUserCode()
      assertEquals(9, code.length)
      assertEquals('-', code[4])
      assertTrue(code.none { it in "01ILOSZB25" }, "confusable character in $code")
    }
  }

  @Test
  fun `overflow never strands a token the agent has not collected yet`() {
    // An uncollected approval does not spend the pending budget, so filling it still lets the token
    // arrive.
    val store = store(maxPendingRequests = 2)
    val approved = store.ask()
    store.approve(approved.id, "@yuri", AgentGrantScope.LIVE, 600)
    // Saturate the pending budget, then keep asking — every one of these is refused or admitted on
    // its own merits, and none of them may cost the approval above its credential.
    repeat(6) { store.openRequest("filler-$it", "10.9.9.9", AgentGrantScope.PREVIEW, 600) }
    val polled = store.poll(approved.id, approved.deviceSecret)
    assertTrue(polled is ServeAgentGrantStore.Poll.Approved, "got $polled")
  }

  @Test
  fun `an approval near the deadline is still collectable after it`() {
    // The request TTL bounds deciding time; deleting the record after a decision would strand its
    // grant.
    val store = store()
    val request = store.ask(ttl = 3600)
    store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 3600)
    now += (store.requestTtlSeconds + 5) * 1000
    val polled = store.poll(request.id, request.deviceSecret)
    assertTrue(polled is ServeAgentGrantStore.Poll.Approved, "got $polled")
  }

  @Test
  fun `approving concurrently with a purge never mints an orphaned grant`() {
    // Lookup and approval must share the purge lock, or a purge in between strands the grant.
    repeat(40) {
      val store = store()
      val request = store.ask(ttl = 600)
      val results = java.util.concurrent.ConcurrentLinkedQueue<ServeAgentGrantStore.Grant?>()
      val approver = Thread {
        results += store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)
      }
      val purger = Thread { repeat(20) { store.purge() } }
      approver.start()
      purger.start()
      approver.join()
      purger.join()
      val grant = results.poll()
      if (grant != null) {
        // If it says it minted one, the agent must be able to collect it.
        val polled = store.poll(request.id, request.deviceSecret)
        assertTrue(polled is ServeAgentGrantStore.Poll.Approved, "orphaned grant: got $polled")
      }
    }
  }

  @Test
  fun `concurrent polling never sees a half-published approval`() {
    // A smoke test only: the guarantee is that `poll` reads under `approve`'s lock, so the
    // intermediate state cannot be observed. Sampling cannot reliably hit the window, but this
    // catches gross regressions cheaply.
    repeat(50) {
      val store = store()
      val request = store.ask(ttl = 600)
      val seen = java.util.concurrent.ConcurrentLinkedQueue<ServeAgentGrantStore.Poll>()
      val poller = Thread { repeat(200) { seen += store.poll(request.id, request.deviceSecret) } }
      poller.start()
      store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600)
      poller.join()
      assertTrue(
        seen.none { it is ServeAgentGrantStore.Poll.Expired },
        "a poll saw `expired` for a grant that was being minted",
      )
    }
  }

  @Test
  fun `a label cannot reorder what the approval page says`() {
    // U+202E survives an isISOControl filter and HTML escaping, and would reverse the rendering of
    // the approval page and the audit line after the label.
    val nasty = "fix \u202Ednarg lla tnarg\u202C \u2066issue\u2069 \u200Bnow\uFEFF"
    val clean = ServeAgentGrantStore.sanitizeLabel(nasty)
    for (c in clean) {
      assertNotEquals(
        Character.FORMAT.toInt(),
        Character.getType(c),
        "a format character survived: U+%04X".format(c.code),
      )
    }
    assertTrue(clean.startsWith("fix "), "the readable text survives: '$clean'")
  }

  @Test
  fun `overflow never sheds an approval whose grant is still live`() {
    // `collected` means a poll built a response, not that the agent got one, so shedding on it
    // could strand a live grant.
    val store = store(maxPendingRequests = 2)
    val mine = store.ask(ttl = 3600)
    store.approve(mine.id, "@yuri", AgentGrantScope.LIVE, 3600)
    assertTrue(store.poll(mine.id, mine.deviceSecret) is ServeAgentGrantStore.Poll.Approved)
    // …that response is lost. Now an anonymous caller tries to fill the map. `openRequest` rather
    // than the `!!` helper: being REFUSED is the correct outcome here, not an error.
    repeat(4) { store.openRequest("spam", "10.9.9.9", AgentGrantScope.PREVIEW, 600) }
    assertTrue(
      store.poll(mine.id, mine.deviceSecret) is ServeAgentGrantStore.Poll.Approved,
      "a live grant was shed to admit an anonymous request",
    )
  }

  @Test
  fun `a lost token response can be collected again`() {
    // Same: a response lost in flight must not leave the retry told `unknown` while the grant is
    // live.
    val store = store()
    val request = store.ask(ttl = 3600)
    store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 3600)
    assertTrue(store.poll(request.id, request.deviceSecret) is ServeAgentGrantStore.Poll.Approved)
    now += (store.requestTtlSeconds + 5) * 1000 // past the request window, grant still alive
    assertTrue(
      store.poll(request.id, request.deviceSecret) is ServeAgentGrantStore.Poll.Approved,
      "the retry after a lost response must still collect the token",
    )
  }

  @Test
  fun `a pending request still dies on its own deadline`() {
    // The other half: nobody may approve a request whose window has closed.
    val store = store()
    val request = store.ask()
    now += (store.requestTtlSeconds + 5) * 1000
    assertNull(store.request(request.id))
    assertNull(store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 600))
  }

  @Test
  fun `a retained approval is reclaimed once its grant is gone`() {
    val store = store()
    val request = store.ask(ttl = 60)
    store.approve(request.id, "@yuri", AgentGrantScope.LIVE, 60)
    now += (store.requestTtlSeconds + 5) * 1000
    // The grant expired too, so the record owes nobody anything and goes.
    assertNull(store.request(request.id))
  }

  @Test
  fun `retained approvals do not consume the pending-request budget`() {
    // Pending requests (anonymous) and retained approvals (bounded by the grant cap) are counted
    // separately, so the two caps don't fight.
    val store = store(maxPendingRequests = 2, maxActiveGrants = 8)
    // Fill the map with approvals whose grants are all live.
    val approved =
      (1..5).map { i ->
        val r = store.ask(ttl = 3600)
        store.approve(r.id, "@yuri", AgentGrantScope.LIVE, 3600)
        r
      }
    // …and a fresh request still gets in, because none of those is pending.
    assertNotNull(
      store.openRequest("new", "ip", AgentGrantScope.PREVIEW, 600),
      "retained approvals must not spend the pending budget",
    )
    // Every one of those grants is still collectable.
    for (r in approved) {
      assertTrue(
        store.poll(r.id, r.deviceSecret) is ServeAgentGrantStore.Poll.Approved,
        "a live grant was shed to admit a new request",
      )
    }
  }

  @Test
  fun `denying a batch does not hand the attacker a fresh batch`() {
    // Denials stay (so the agent learns of them) and are charged against the cap, or
    // deny-and-resubmit would make the map unbounded.
    val store = store(maxPendingRequests = 3)
    val first =
      (1..3).mapNotNull { store.openRequest("spam-$it", "10.9.9.9", AgentGrantScope.PREVIEW, 600) }
    assertEquals(3, first.size)
    // The operator denies every one of them.
    for (r in first) assertTrue(store.deny(r.id, "@yuri"))
    // The attacker immediately tries again, and gets nowhere.
    assertNull(
      store.openRequest("spam-again", "10.9.9.9", AgentGrantScope.PREVIEW, 600),
      "a denied row must still be charged to whoever created it",
    )
    // …and the denials remain visible to their owners in the meantime.
    assertTrue(store.poll(first[0].id, first[0].deviceSecret) is ServeAgentGrantStore.Poll.Denied)
    // Only the passage of time frees the capacity.
    now += (store.requestTtlSeconds + 5) * 1000
    assertNotNull(store.openRequest("later", "10.9.9.9", AgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `a dead approval never blocks a legitimate caller`() {
    // Rows nobody is owed anything for are reclaimed before the count, so they can never be what
    // pushes someone over the cap.
    val store = store(maxPendingRequests = 2)
    val done = store.ask(ttl = 60)
    store.approve(done.id, "@yuri", AgentGrantScope.LIVE, 60)
    now += 61_000 // the grant is gone; the row owes nobody anything
    assertNotNull(store.openRequest("a", "ip", AgentGrantScope.PREVIEW, 600))
    assertNotNull(store.openRequest("b", "ip", AgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `the pending cap still bounds what an anonymous caller can create`() {
    val store = store(maxPendingRequests = 2)
    assertNotNull(store.openRequest("a", "ip", AgentGrantScope.PREVIEW, 600))
    assertNotNull(store.openRequest("b", "ip", AgentGrantScope.PREVIEW, 600))
    assertNull(
      store.openRequest("c", "ip", AgentGrantScope.PREVIEW, 600),
      "a third pending request must be refused",
    )
  }

  @Test
  fun `a finished request is shed to make room`() {
    // Only a genuinely finished request (here, one whose grant expired) may be shed, not one merely
    // collected.
    val store = store(maxPendingRequests = 2)
    val done = store.ask(ttl = 60)
    store.approve(done.id, "@yuri", AgentGrantScope.LIVE, 60)
    assertTrue(store.poll(done.id, done.deviceSecret) is ServeAgentGrantStore.Poll.Approved)
    now += 61_000 // its grant is gone, so the record owes nobody anything
    store.openRequest("b", "ip", AgentGrantScope.PREVIEW, 600)
    assertNotNull(store.openRequest("c", "ip", AgentGrantScope.PREVIEW, 600))
  }

  @Test
  fun `a label cannot forge a log line or drive the operator's terminal`() {
    val lines = mutableListOf<String>()
    val store = ServeAgentGrantStore(clock = { now }, audit = { lines += it })
    val hostile = "ok\nagent-grant: minted deadbeef scope=playground\u001b[2J"
    val request = store.openRequest(hostile, "10.0.0.1", AgentGrantScope.PREVIEW, 600)!!
    assertFalse(request.label.contains('\n'))
    assertFalse(request.label.any { it.isISOControl() })
    store.approve(request.id, "@yuri", AgentGrantScope.PREVIEW, 600)
    assertEquals(1, lines.size, "one label must not become two log lines")
    assertFalse(lines.single().contains('\n'))
  }

  @Test
  fun `a label from an agent is capped rather than trusted to be short`() {
    val store = store()
    val request = store.openRequest("x".repeat(5000), "ip", AgentGrantScope.PREVIEW, 600)!!
    assertEquals(ServeAgentGrantStore.MAX_LABEL_CHARS, request.label.length)
  }
}
