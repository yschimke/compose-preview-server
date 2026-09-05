package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Why a dead credential is dead — the classification behind `whoami`'s `reason`.
 *
 * An agent whose calls start failing has three very different next moves: wait, re-run the approval
 * flow, or stop because a human revoked it. Before this the store could only answer "not a live
 * grant", so every one of those looked the same from outside. These pin the distinctions the store
 * can honestly draw, including the one it deliberately cannot.
 */
class ServeAgentGrantTokenStateTest {

  private var now = 1_000_000L

  private fun store(maxGrantTtlSeconds: Long = 3600) =
    ServeAgentGrantStore(
      maxGrantTtlSeconds = maxGrantTtlSeconds,
      maxScope = AgentGrantScope.PLAYGROUND,
      clock = { now },
    )

  private fun ServeAgentGrantStore.mintToken(ttl: Long = 1800): String {
    val request = openRequest("fix #1", "10.0.0.1", AgentGrantScope.LIVE, ttl)!!
    return approve(request.id, "@operator", AgentGrantScope.LIVE, ttl)!!.token
  }

  @Test
  fun `a live grant classifies as live`() {
    val store = store()
    val token = store.mintToken()

    assertEquals(ServeAgentGrantStore.TokenState.LIVE, store.describeToken(token))
  }

  @Test
  fun `no credential is absent, not unknown`() {
    val store = store()

    assertEquals(ServeAgentGrantStore.TokenState.ABSENT, store.describeToken(null))
    assertEquals(ServeAgentGrantStore.TokenState.ABSENT, store.describeToken(""))
    assertEquals(ServeAgentGrantStore.TokenState.ABSENT, store.describeToken("   "))
  }

  @Test
  fun `something that is not shaped like a token is malformed`() {
    val store = store()

    assertEquals(ServeAgentGrantStore.TokenState.MALFORMED, store.describeToken("hunter2"))
  }

  @Test
  fun `a well-formed token this server never issued is unknown`() {
    val store = store()
    // Shaped right, minted nowhere — what a token from a *previous run of this process* looks
    // like, since nothing in the store is persisted across a restart.
    val foreign = store().mintToken()

    assertEquals(ServeAgentGrantStore.TokenState.UNKNOWN, store.describeToken(foreign))
  }

  @Test
  fun `a grant past its TTL reads as expired while the record survives`() {
    val store = store()
    val token = store.mintToken(ttl = 60)

    now += 61_000
    assertEquals(ServeAgentGrantStore.TokenState.EXPIRED, store.describeToken(token))
  }

  @Test
  fun `an expired grant degrades to unknown once purged`() {
    // Honest rather than convenient: after the sweep the store genuinely no longer holds the
    // record, so continuing to claim "expired" would be asserting something it cannot know.
    val store = store()
    val token = store.mintToken(ttl = 60)

    now += 61_000
    store.purge(now)

    assertEquals(ServeAgentGrantStore.TokenState.UNKNOWN, store.describeToken(token))
  }

  @Test
  fun `a revoked grant is indistinguishable from an unissued one`() {
    // The documented conflation: telling revocation apart from a restart would mean keeping a
    // tombstone for every revoked token, retaining exactly what revocation is meant to discard.
    val store = store()
    val request = store.openRequest("fix #1", "10.0.0.1", AgentGrantScope.LIVE, 1800)!!
    val grant = store.approve(request.id, "@operator", AgentGrantScope.LIVE, 1800)!!

    assertEquals(ServeAgentGrantStore.TokenState.LIVE, store.describeToken(grant.token))
    store.revoke(grant.id, "@operator")

    assertEquals(ServeAgentGrantStore.TokenState.UNKNOWN, store.describeToken(grant.token))
  }
}
