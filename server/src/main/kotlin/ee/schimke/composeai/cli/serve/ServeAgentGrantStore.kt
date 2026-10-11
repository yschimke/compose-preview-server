package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The state behind `/agent-access/…`: pending grant requests and the live grants they become. See
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * Shaped like [PlaygroundTokenStore] (unguessable ids, TTLs, bounded maps), but with two secrets: a
 * [Request] has a public [Request.id] (the link a human opens) and a private [Request.deviceSecret]
 * only the requesting agent holds. The token goes to whoever presents the secret, never whoever
 * opens the link, so the link is safe to paste in chat.
 *
 * Nothing is persisted: a restart drops every request and grant, which suits a short-lived,
 * revocable credential.
 */
class ServeAgentGrantStore(
  /** How long an unopened link stays approvable. Short — a request is a live conversation. */
  val requestTtlSeconds: Long = DEFAULT_REQUEST_TTL_SECONDS,
  /** The longest grant this box will mint, whatever the agent asked for or the approver chose. */
  val maxGrantTtlSeconds: Long = DEFAULT_MAX_GRANT_TTL_SECONDS,
  /** The most privileged scope any grant here may carry — the operator's ceiling. */
  val maxScope: AgentGrantScope = AgentGrantScope.DEFAULT_MAX,
  /**
   * Capabilities ([AgentGrantCapability]) any grant may carry; empty by default. Separate from
   * [maxScope] because capabilities aren't rungs: granting `live` says nothing about publishing
   * images.
   */
  val maxCapabilities: Set<AgentGrantCapability> = emptySet(),
  private val maxActiveGrants: Int = DEFAULT_MAX_ACTIVE_GRANTS,
  /**
   * Live grants one approver may hold, counted by [Grant.approvedByActorId]. Over it, only that
   * approver is refused; nobody else's grants are ended. Administrative callers pass
   * `enforceApproverCap = false`.
   */
  private val maxActiveGrantsPerApprover: Int = DEFAULT_MAX_ACTIVE_GRANTS_PER_APPROVER,
  private val maxPendingRequests: Int = DEFAULT_MAX_PENDING_REQUESTS,
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ::randomId,
  private val mintSecret: () -> String = ::randomSecret,
  private val mintUserCode: () -> String = ::randomUserCode,
  private val mintToken: () -> String = ::randomToken,
  /**
   * Audit sink, called per mint and revoke with a printable line naming approver, label, scope and
   * fingerprint, never the token. No-op by default.
   */
  private val audit: (String) -> Unit = {},
) {

  /** A request waiting for a human, or already resolved by one. */
  data class Request(
    /** Public handle. Appears in the approval link and nowhere sensitive. */
    val id: String,
    /**
     * The agent's half, presented on `/agent-access/poll` and compared constant-time. Never
     * rendered, logged or put in a URL.
     */
    val deviceSecret: String,
    /**
     * Short code shown by both the agent and the approval page, so a human can match them
     * (anti-phishing, RFC 8628 §3.3).
     */
    val userCode: String,
    /** The agent's stated purpose: untrusted free text, always escaped. */
    val label: String,
    /** Where the request came from, for the approval page's "who is asking". Also untrusted. */
    val client: String,
    /** The most privileged scope the agent asked for; the approver may grant this or less. */
    val requestedScope: AgentGrantScope,
    /** Seconds of access the agent asked for; the approver may grant this or less. */
    val requestedTtlSeconds: Long,
    /** Capabilities the agent asked for; the approver may grant these or fewer, never more. */
    val requestedCapabilities: Set<AgentGrantCapability> = emptySet(),
    /**
     * The signed-in person requesting access for themselves (`github:<login>`), verified from their
     * session, never self-asserted. Blank for an agent's request. See [Grant.requesterActorId].
     */
    val requesterActorId: String = "",
    /**
     * Designs this request is for, when opened from one ([Grant.designIds]); empty means everything
     * the approver can edit.
     */
    val designIds: Set<String> = emptySet(),
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    @Volatile var state: State = State.PENDING,
    /** Set exactly once, when [State.APPROVED] — the token the poller collects. */
    @Volatile var grantId: String? = null,
    /** Who resolved it, for the poll response and the audit line. */
    @Volatile var resolvedBy: String? = null,
    /**
     * True once a poll built an [Poll.Approved] response. Only used to rank overflow eviction, so
     * an attacker can't strand an approved-but-uncollected token by filling the map. Not a deletion
     * trigger: a lost response must be retriable, so expiry follows the grant's life ([purge]).
     */
    @Volatile var collected: Boolean = false,
  ) {
    enum class State {
      PENDING,
      APPROVED,
      DENIED,
    }

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    override fun equals(other: Any?): Boolean = other is Request && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  /** A live grant: a bearer token, what it may do, until when, and who said so. */
  data class Grant(
    /** Stable handle for revocation from `/status`. Not a secret. */
    val id: String,
    /** The bearer. Returned exactly once (the poll response) and never rendered again. */
    val token: String,
    val scope: AgentGrantScope,
    /** The independent permissions this grant carries beside its rung. Usually empty. */
    val capabilities: Set<AgentGrantCapability> = emptySet(),
    /** The label from the request, carried through so `/status` says what this is for. */
    val label: String,
    /** GitHub login, or `operator (token)` — see [ServeAgentGrants]. */
    val approvedBy: String,
    /**
     * The approver as an actor id ([ServeAgentGrants.Approver.actorId]). Blank only for legacy or
     * test grants, read as "no delegation".
     */
    val approvedByActorId: String = "",
    /**
     * Who this grant is for when a person asked themselves (`github:<login>`). Changes attribution,
     * not authority: it still reaches only what [approvedByActorId] can, and the requester's own
     * session carries it ([activeGrantForRequester]).
     */
    val requesterActorId: String = "",
    /**
     * Designs this grant lends its approver's authority on. Empty (legacy, agent grants, widened
     * approvals) means every design the approver can reach; otherwise only these, applied by
     * [ServeUiBuilderGrantScope].
     */
    val designIds: Set<String> = emptySet(),
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    /**
     * How the server names this grant's holder: the requester, or the agent's fingerprinted
     * identity. Used by [ServeMachineAuthorization] as the actor id.
     */
    val holderActorId: String
      get() = requesterActorId.ifBlank { ServeAgentGrants.agentActorId(fingerprint) }

    /** True when this grant lends its approver's authority on [designId] — see [designIds]. */
    fun coversDesign(designId: String): Boolean = designIds.isEmpty() || designId in designIds

    /** Every scope this grant confers, least-privileged first — the poll response's `scopes`. */
    val scopes: List<AgentGrantScope>
      get() = AgentGrantScope.upTo(scope)

    /** True when this grant is at or above [wanted]. The one question every gate asks. */
    fun allows(wanted: AgentGrantScope): Boolean = scope.implies(wanted)

    /** True when this grant carries [wanted]; never implied by a scope. */
    fun allows(wanted: AgentGrantCapability): Boolean = wanted in capabilities

    /**
     * Short non-reversible token handle for `/status` and the audit log: SHA-256 truncated to 12
     * hex chars.
     */
    val fingerprint: String by lazy { AgentGrantProtocol.fingerprintOf(token) }

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)

    /**
     * Whether the named approver approved this: by actor id when recorded, else by display name.
     */
    fun isApprovedBy(name: String, actorId: String): Boolean =
      if (approvedByActorId.isNotBlank()) approvedByActorId == actorId else approvedBy == name

    override fun equals(other: Any?): Boolean = other is Grant && other.id == id

    override fun hashCode(): Int = id.hashCode()
  }

  private val requests = ConcurrentHashMap<String, Request>()
  private val grants = ConcurrentHashMap<String, Grant>()

  /** Token → grant id, so the per-request hot path is one hash lookup. */
  private val byToken = ConcurrentHashMap<String, String>()

  /**
   * Derived browser credential → grant id. The cookie holds a domain-separated HMAC keyed by the
   * `cpat_` bearer, never the bearer itself.
   */
  private val byBrowserCredential = ConcurrentHashMap<String, String>()

  /** Grant-specific path credential for opaque private-Wasm iframe subresources. */
  private val byWasmCredential = ConcurrentHashMap<String, String>()

  // ---------------------------------------------------------------- requests

  /**
   * Open a request, or null when [maxPendingRequests] pending ones are tracked and none can be
   * purged, rather than growing a map an anonymous caller controls.
   */
  fun openRequest(
    label: String,
    client: String,
    requestedScope: AgentGrantScope,
    requestedTtlSeconds: Long,
    requestedCapabilities: Set<AgentGrantCapability> = emptySet(),
    requesterActorId: String = "",
    designIds: Set<String> = emptySet(),
  ): Request? =
    synchronized(this) {
      openRequestLocked(
        label,
        client,
        requestedScope,
        requestedTtlSeconds,
        requestedCapabilities,
        requesterActorId,
        designIds,
      )
    }

  /**
   * Cap check and insert under one lock, otherwise concurrent anonymous callers could all pass the
   * check. The same lock guards [approve] and [deny].
   */
  private fun openRequestLocked(
    label: String,
    client: String,
    requestedScope: AgentGrantScope,
    requestedTtlSeconds: Long,
    requestedCapabilities: Set<AgentGrantCapability>,
    requesterActorId: String,
    designIds: Set<String>,
  ): Request? {
    // Refused rather than filtered: dropping a malformed id could leave the set empty, and an empty
    // set asks for every design. Callers validate first; this is the backstop.
    require(designIds.size <= MAX_REQUESTED_DESIGNS && designIds.all(::isWellFormedDesignId)) {
      "a request names at most $MAX_REQUESTED_DESIGNS well-formed design ids"
    }
    val now = clock()
    purge(now)
    // The cap counts what an anonymous caller can create: pending rows plus denied rows. Approvals
    // aren't counted; they live only while their grant does and are bounded by [maxActiveGrants].
    // Denials are kept (so the agent learns it was denied) and charged, or deny-and-resubmit cycles
    // would grow the map unboundedly. Total size is bounded by maxPendingRequests +
    // maxActiveGrants. Approvals whose grant is gone are reclaimed first.
    requests.entries.removeIf { (_, r) -> r.state == Request.State.APPROVED && grantIsGone(r, now) }
    val charged =
      requests.values.count {
        it.state == Request.State.PENDING || it.state == Request.State.DENIED
      }
    if (charged >= maxPendingRequests) return null
    val request =
      Request(
        id = mintId(),
        deviceSecret = mintSecret(),
        userCode = mintUserCode(),
        label = sanitizeLabel(label),
        client = sanitizeLabel(client),
        requestedScope = minOf(requestedScope, maxScope),
        requestedTtlSeconds = requestedTtlSeconds.coerceIn(1, maxGrantTtlSeconds),
        // Kept whole: the approval page decides what is selectable and shows the rest as withheld
        // with a reason, and [approve] clamps again. Narrowing here lost what the agent asked for.
        requestedCapabilities = requestedCapabilities,
        requesterActorId = requesterActorId,
        designIds = designIds,
        createdAtMillis = now,
        expiresAtMillis = now + requestTtlSeconds * 1000,
      )
    requests[request.id] = request
    return request
  }

  /** The live request for [id], or null when unknown, expired, or malformed. */
  fun request(id: String?): Request? {
    val key = id?.takeIf { isWellFormedId(it) } ?: return null
    val now = clock()
    purge(now)
    // An approved, uncollected request stays reachable past its deadline until [purge] decides; a
    // pending one expires exactly on time.
    return requests[key]?.takeIf {
      it.expiresAtMillis > now || (it.state == Request.State.APPROVED && !grantIsGone(it, now))
    }
  }

  /**
   * Approve [id], minting a grant. [scope] and [ttlSeconds] are clamped to the request and this
   * store's ceiling, so a tampered form field buys nothing. Idempotent: re-approving returns the
   * same grant.
   *
   * Refused (null, still pending) when the box holds [maxActiveGrants], or with
   * [enforceApproverCap] when this approver holds [maxActiveGrantsPerApprover]; nothing live is
   * ended to make room ([capacityFor] says which limit). A request naming designs is approved for
   * those unless [limitToRequestedDesigns] is false.
   */
  fun approve(
    id: String,
    approvedBy: String,
    scope: AgentGrantScope,
    ttlSeconds: Long,
    capabilities: Set<AgentGrantCapability> = emptySet(),
    approvedByActorId: String = "",
    enforceApproverCap: Boolean = true,
    limitToRequestedDesigns: Boolean = true,
  ): Grant? {
    synchronized(this) {
      // Lookup, expiry check and state transition all under the lock, so a concurrent `purge()`
      // can't detach the request mid-approval.
      val request = request(id) ?: return null
      when (request.state) {
        Request.State.APPROVED -> return request.grantId?.let { grants[it] }
        Request.State.DENIED -> return null
        Request.State.PENDING -> Unit
      }
      if (capacityFor(approvedBy, approvedByActorId, enforceApproverCap) != Capacity.OK) {
        return null
      }
      val now = clock()
      val granted = minOf(scope, request.requestedScope, maxScope)
      // Intersection of what the approver ticked, what the agent asked for and what this box
      // permits.
      val grantedCapabilities =
        capabilities intersect request.requestedCapabilities intersect maxCapabilities
      val ttl = ttlSeconds.coerceIn(1, minOf(request.requestedTtlSeconds, maxGrantTtlSeconds))
      val grant =
        Grant(
          id = mintId(),
          token = mintToken(),
          scope = granted,
          capabilities = grantedCapabilities,
          label = request.label,
          approvedBy = approvedBy,
          approvedByActorId = approvedByActorId,
          requesterActorId = request.requesterActorId,
          designIds = if (limitToRequestedDesigns) request.designIds else emptySet(),
          issuedAtMillis = now,
          expiresAtMillis = now + ttl * 1000,
        )
      grants[grant.id] = grant
      byToken[grant.token] = grant.id
      byBrowserCredential[browserCredentialValue(grant.token)] = grant.id
      byWasmCredential[wasmCredentialValue(grant.token)] = grant.id
      // Data before the flag that says it is there; [poll]'s lock is the main guarantee.
      request.grantId = grant.id
      request.resolvedBy = approvedBy
      request.state = Request.State.APPROVED
      audit(
        "agent-grant: minted ${grant.fingerprint} scope=${granted.wire} " +
          capabilityAuditField(grantedCapabilities) +
          designAuditField(grant.designIds) +
          "ttl=${ttl}s approver=$approvedBy label=\"${grant.label}\""
      )
      return grant
    }
  }

  /** Deny [id]. Idempotent; a no-op on an already-approved request (the token is already out). */
  fun deny(id: String, deniedBy: String): Boolean {
    synchronized(this) {
      // Same reason as [approve]: the lookup belongs inside the lock that purging takes.
      val request = request(id) ?: return false
      if (request.state != Request.State.PENDING) return false
      request.state = Request.State.DENIED
      request.resolvedBy = deniedBy
      audit("agent-grant: denied request by $deniedBy label=\"${request.label}\"")
      return true
    }
  }

  /**
   * Collect [id]'s outcome for a poller proving possession of [deviceSecret]. Compared
   * constant-time; a mismatch answers [Poll.Unknown], like a nonexistent id, so a leaked link
   * reveals nothing.
   */
  fun poll(id: String?, deviceSecret: String?): Poll =
    synchronized(this) { pollLocked(id, deviceSecret) }

  /**
   * Under the lock, so a poll can't interleave between [approve]'s writes, see APPROVED with a null
   * `grantId`, answer `expired`, and make the client discard its secret for a freshly minted grant.
   */
  private fun pollLocked(id: String?, deviceSecret: String?): Poll {
    val request = request(id) ?: return Poll.Unknown
    if (!ServeUrls.tokensMatch(request.deviceSecret, deviceSecret)) return Poll.Unknown
    return when (request.state) {
      Request.State.PENDING -> Poll.Pending(request.secondsUntilExpiry(clock()))
      Request.State.DENIED -> Poll.Denied(request.resolvedBy)
      Request.State.APPROVED -> {
        // The grant may have been revoked or expired since approval; say so rather than return a
        // dead token.
        val grant = request.grantId?.let { grant(it) } ?: return Poll.Expired
        request.collected = true
        Poll.Approved(grant)
      }
    }
  }

  /** What a poller is told. Mirrors RFC 8628's `authorization_pending` / `access_denied` shape. */
  sealed interface Poll {
    data class Pending(val secondsUntilExpiry: Long) : Poll

    data class Approved(val grant: Grant) : Poll

    data class Denied(val by: String?) : Poll

    /** The request ran out before anyone approved it, or its grant is already gone. */
    data object Expired : Poll

    /** No such request, or the wrong device secret. Deliberately indistinguishable. */
    data object Unknown : Poll
  }

  // ------------------------------------------------------------------ grants

  /**
   * The live grant a presented bearer names, or null. The hot path: a shape check, one lookup and
   * an expiry comparison; purging is left to slower lanes.
   */
  fun grantForToken(presented: String?): Grant? {
    val token = presented?.takeIf { isWellFormedToken(it) } ?: return null
    val id = byToken[token] ?: return null
    val grant = grants[id] ?: return null
    return grant.takeIf { it.expiresAtMillis > clock() }
  }

  /** The live grant named by an HttpOnly browser credential, or null. */
  fun grantForBrowserCredential(presented: String?): Grant? {
    val credential = presented?.takeIf { isWellFormedBrowserCredential(it) } ?: return null
    val id = byBrowserCredential[credential] ?: return null
    val grant = grants[id] ?: return null
    return grant.takeIf { it.expiresAtMillis > clock() }
  }

  /**
   * The non-reversible credential a browser cookie carries for [grant] while it is live; its
   * validity is still decided from the grant per request.
   */
  fun browserCredentialFor(grant: Grant): BrowserCredential? {
    val now = clock()
    val live =
      grants[grant.id]?.takeIf { it.token == grant.token && it.expiresAtMillis > now }
        ?: return null
    val seconds = ((live.expiresAtMillis - now + 999) / 1000).coerceAtLeast(1)
    return BrowserCredential(
      value = browserCredentialValue(live.token),
      maxAgeSeconds = seconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    )
  }

  /** Whether [presented] is the derived browser credential for [grant], without re-resolving it. */
  fun browserCredentialMatches(presented: String?, grant: Grant): Boolean =
    presented != null &&
      isWellFormedBrowserCredential(presented) &&
      ServeUrls.tokensMatch(browserCredentialValue(grant.token), presented)

  /** A non-cookie, non-cpat path credential for this live grant's private Wasm assets. */
  fun wasmCredentialFor(grant: Grant): String? {
    val live =
      grants[grant.id]?.takeIf { it.token == grant.token && it.expiresAtMillis > clock() }
        ?: return null
    return wasmCredentialValue(live.token)
  }

  /** Resolve a private-Wasm path credential back to its authoritative live grant. */
  fun grantForWasmCredential(presented: String?): Grant? {
    val credential = presented?.takeIf { isWellFormedWasmCredential(it) } ?: return null
    val id = byWasmCredential[credential] ?: return null
    val grant = grants[id] ?: return null
    return grant.takeIf { it.expiresAtMillis > clock() }
  }

  data class BrowserCredential(val value: String, val maxAgeSeconds: Int)

  /**
   * Why a presented token isn't a live grant, so an agent can tell wait / re-approve / stop.
   * [UNKNOWN] covers both revoked and previous-process grants, since keeping a tombstone would
   * retain what revocation discards.
   */
  enum class TokenState(val wire: String) {
    LIVE("live"),
    ABSENT("absent"),
    MALFORMED("malformed"),
    EXPIRED("expired"),
    UNKNOWN("unknown"),
  }

  /** Classify [presented] for the whoami reply. Never echoes the token itself. */
  fun describeToken(presented: String?): TokenState {
    if (presented.isNullOrBlank()) return TokenState.ABSENT
    if (!isWellFormedToken(presented)) return TokenState.MALFORMED
    val id = byToken[presented] ?: return TokenState.UNKNOWN
    val grant = grants[id] ?: return TokenState.UNKNOWN
    // Expired grants linger until [purge]; after that the token honestly reads as UNKNOWN.
    return if (grant.expiresAtMillis > clock()) TokenState.LIVE else TokenState.EXPIRED
  }

  /**
   * The live grant a person requested for themselves ([Grant.requesterActorId]), so their session
   * can carry it without a bearer. With [capability], only a grant carrying it. The longest-lived
   * match wins.
   */
  fun activeGrantForRequester(
    requesterActorId: String?,
    capability: AgentGrantCapability? = null,
  ): Grant? {
    if (requesterActorId.isNullOrBlank()) return null
    val now = clock()
    return grants.values
      .filter { it.requesterActorId == requesterActorId && it.expiresAtMillis > now }
      .filter { capability == null || it.allows(capability) }
      .maxByOrNull { it.expiresAtMillis }
  }

  /**
   * Which limit, if any, stands in the way of [approve] minting another grant for this approver.
   */
  enum class Capacity {
    OK,
    /** This approver already holds [maxActiveGrantsPerApprover] live grants. */
    APPROVER_FULL,
    /** The box already holds [maxActiveGrants] live grants. */
    BOX_FULL,
  }

  /**
   * Whether this approver's approval would fit now; [approve] asks the same under its lock. For
   * explaining a refusal.
   */
  fun capacityFor(
    approvedBy: String,
    approvedByActorId: String,
    enforceApproverCap: Boolean = true,
  ): Capacity =
    synchronized(this) {
      purgeLocked(clock())
      when {
        enforceApproverCap &&
          grants.values.count { it.isApprovedBy(approvedBy, approvedByActorId) } >=
            maxActiveGrantsPerApprover -> Capacity.APPROVER_FULL
        grants.size >= maxActiveGrants -> Capacity.BOX_FULL
        else -> Capacity.OK
      }
    }

  /**
   * Designs [holderActorId] may reach through [approvedByActorId], or null when unrestricted.
   *
   * The union of designs named by every grant this approver gave this holder; null only when none
   * names a design (grants naming none then add nothing). This stops an every-design read grant
   * from turning a one-design edit grant into an every-design edit. Null when no grant matches (not
   * this store's delegation). Live grants decide; an unpurged expired grant answers only when none
   * is live.
   */
  fun designScopeFor(holderActorId: String, approvedByActorId: String): Set<String>? {
    if (holderActorId.isBlank() || approvedByActorId.isBlank()) return null
    val now = clock()
    val matching =
      grants.values.filter {
        it.approvedByActorId == approvedByActorId && it.holderActorId == holderActorId
      }
    val deciding = matching.filter { it.expiresAtMillis > now }.ifEmpty { matching }
    val named = deciding.flatMapTo(mutableSetOf()) { it.designIds }
    return named.ifEmpty { null }
  }

  /** The live grant with this id, or null when unknown/expired. */
  fun grant(id: String?): Grant? {
    val key = id ?: return null
    val now = clock()
    return grants[key]?.takeIf { it.expiresAtMillis > now }
  }

  /** Revoke a grant by id. Returns true when one went. */
  fun revoke(id: String, by: String): Boolean {
    val grant = grants.remove(id) ?: return false
    byToken.remove(grant.token)
    byBrowserCredential.remove(browserCredentialValue(grant.token))
    byWasmCredential.remove(wasmCredentialValue(grant.token))
    audit("agent-grant: revoked ${grant.fingerprint} by $by label=\"${grant.label}\"")
    return true
  }

  /** Revoke by presenting the token itself — how an agent hands its own access back. */
  fun revokeToken(presented: String?, by: String): Boolean {
    val grant = grantForToken(presented) ?: return false
    return revoke(grant.id, by)
  }

  /** Live grants, soonest expiry first — the `/status` table. */
  fun activeGrants(): List<Grant> {
    val now = clock()
    purge(now)
    return grants.values.sortedBy { it.expiresAtMillis }
  }

  /** Live (unresolved, unexpired) requests, soonest expiry first — the `/status` table. */
  fun pendingRequests(): List<Request> {
    val now = clock()
    purge(now)
    return requests.values
      .filter { it.state == Request.State.PENDING }
      .sortedBy { it.expiresAtMillis }
  }

  /** True when the grant a request minted is expired or revoked, so the record owes nobody. */
  private fun grantIsGone(request: Request, nowMillis: Long): Boolean {
    val grant = request.grantId?.let { grants[it] } ?: return true
    return grant.expiresAtMillis <= nowMillis
  }

  /** Drop everything — server shutdown, and the tests' reset. */
  fun clear() {
    requests.clear()
    grants.clear()
    byToken.clear()
    byBrowserCredential.clear()
    byWasmCredential.clear()
  }

  /** Drop every expired request and grant; returns how many went in total. */
  fun purge(nowMillis: Long = clock()): Int = synchronized(this) { purgeLocked(nowMillis) }

  /** Reentrant body of [purge]; the lock is what makes it safe against [approve] and [deny]. */
  private fun purgeLocked(nowMillis: Long): Int {
    var dropped = 0
    requests.entries.removeIf { (_, r) ->
      // An approved request outlives its deadline while its grant lives, so a late approval doesn't
      // strand the token; [grantIsGone] reclaims it. `collected` doesn't cause deletion, since a
      // response can be lost in flight.
      val keepForCollection = r.state == Request.State.APPROVED && !grantIsGone(r, nowMillis)
      (!keepForCollection && r.expiresAtMillis <= nowMillis).also { if (it) dropped++ }
    }
    grants.entries.removeIf { (_, g) ->
      (g.expiresAtMillis <= nowMillis).also {
        if (it) {
          byToken.remove(g.token)
          byBrowserCredential.remove(browserCredentialValue(g.token))
          byWasmCredential.remove(wasmCredentialValue(g.token))
          dropped++
        }
      }
    }
    return dropped
  }

  /**
   * `caps=images `, or nothing when a grant carries none (the common case), so the field stands out
   * where it matters.
   */
  private fun capabilityAuditField(capabilities: Set<AgentGrantCapability>): String =
    if (capabilities.isEmpty()) ""
    else "caps=${AgentGrantCapability.wireNames(capabilities).joinToString(",")} "

  /** `designs=a,b ` for the audit line, or nothing for a grant that names no design. */
  private fun designAuditField(designIds: Set<String>): String =
    if (designIds.isEmpty()) "" else "designs=${designIds.sorted().joinToString(",")} "

  companion object {
    /**
     * Thirty minutes: long enough to sign in (possibly with 2FA) and read what is being approved,
     * short enough that unopened links don't linger.
     */
    const val DEFAULT_REQUEST_TTL_SECONDS = 30 * 60L

    /** Eight hours — a working day's debugging, and gone by morning. */
    const val DEFAULT_MAX_GRANT_TTL_SECONDS = 8 * 60 * 60L

    /** What an agent gets when it names no TTL: long enough for one task. */
    const val DEFAULT_GRANT_TTL_SECONDS = 60 * 60L

    /**
     * Whole-box limit; a full box refuses new approvals rather than ending live grants, so this is
     * well above the per-approver limit.
     */
    const val DEFAULT_MAX_ACTIVE_GRANTS = 64

    /** Per approver — see [maxActiveGrantsPerApprover]. */
    const val DEFAULT_MAX_ACTIVE_GRANTS_PER_APPROVER = 8

    const val DEFAULT_MAX_PENDING_REQUESTS = 32

    /** How often a well-behaved poller should ask. Advertised in the request response. */
    const val POLL_INTERVAL_SECONDS = 3L

    /** Label/client text is display-only; a cap keeps a hostile agent out of the page's layout. */
    const val MAX_LABEL_CHARS = 120

    /**
     * Flatten an unauthenticated caller's free text to printable characters and cap it. The label
     * lands in the operator's audit log at approval time, where newlines could forge log lines and
     * terminal escapes rewrite the display. C0/C1 controls, DEL and Unicode format characters
     * ([isInvisibleFormat]) become spaces; runs collapse; the result is trimmed and capped. HTML
     * escaping doesn't replace this, since these characters survive it.
     */
    fun sanitizeLabel(raw: String): String =
      raw
        .map { if (it.isISOControl() || it == '\u007f' || it.isInvisibleFormat()) ' ' else it }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_LABEL_CHARS)

    /**
     * Unicode format characters (Cf): invisible but layout-changing. `U+202E` survives control
     * filtering and HTML escaping and could visually rewrite the approval page (including scope and
     * lifetime) and the audit line; isolates and zero-widths do the same quietly. Nothing a purpose
     * string needs is in Cf, so the whole category goes.
     */
    private fun Char.isInvisibleFormat(): Boolean =
      Character.getType(this) == Character.FORMAT.toInt()

    /** The bearer's prefix — greppable, and unmistakable for the operator's `--token`. */
    const val TOKEN_PREFIX = "cpat_"

    private val random = SecureRandom()

    private fun random128(): String {
      val bytes = ByteArray(16)
      random.nextBytes(bytes)
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 128 bits — request and grant ids. Public handles, but unguessable all the same. */
    fun randomId(): String = random128()

    /** 256 bits for the agent's half: it is a credential, and it is never seen by a human. */
    fun randomSecret(): String {
      val bytes = ByteArray(32)
      random.nextBytes(bytes)
      return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** 128 bits, prefixed. The bearer. */
    fun randomToken(): String = TOKEN_PREFIX + random128()

    /**
     * The human-checkable code `XXXX-XXXX`, over an alphabet without confusable pairs (`0/O`,
     * `1/I/L`, `5/S`, `2/Z`, `8/B`). ~40 bits; not the security boundary (the device secret is).
     */
    fun randomUserCode(): String {
      val code = CharArray(9)
      for (i in 0 until 9) {
        code[i] = if (i == 4) '-' else USER_CODE_ALPHABET[random.nextInt(USER_CODE_ALPHABET.length)]
      }
      return String(code)
    }

    private const val USER_CODE_ALPHABET = "ACDEFGHJKMNPQRTUVWXY34679"

    /** The most designs one request may name. The approval page lists every one of them. */
    const val MAX_REQUESTED_DESIGNS = 16

    /**
     * True for a design id a request may name: the designs page's alphabet, bounded, checked before
     * storing, display or audit.
     */
    fun isWellFormedDesignId(designId: String): Boolean = designId.matches(DESIGN_ID_SHAPE)

    private val DESIGN_ID_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** Cheap shape check on a public id before a map lookup. */
    fun isWellFormedId(id: String): Boolean = id.matches(ID_SHAPE)

    /** Cheap shape check on a presented bearer, so an ordinary `--token` never reaches the map. */
    fun isWellFormedToken(token: String): Boolean = token.matches(TOKEN_SHAPE)

    /** A browser-only, non-reversible stand-in for one short-lived grant bearer. */
    internal fun browserCredentialValue(token: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
      val digest = mac.doFinal(BROWSER_CREDENTIAL_LABEL.toByteArray(Charsets.UTF_8))
      return BROWSER_CREDENTIAL_PREFIX +
        Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    internal fun isWellFormedBrowserCredential(value: String): Boolean =
      value.matches(BROWSER_CREDENTIAL_SHAPE)

    /** A domain-separated credential safe to expose only in private-Wasm asset paths. */
    internal fun wasmCredentialValue(token: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
      val digest = mac.doFinal(WASM_CREDENTIAL_LABEL.toByteArray(Charsets.UTF_8))
      return WASM_CREDENTIAL_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    internal fun isWellFormedWasmCredential(value: String): Boolean =
      value.matches(WASM_CREDENTIAL_SHAPE)

    private val ID_SHAPE = Regex("[A-Za-z0-9_-]{16,64}")
    private val TOKEN_SHAPE = Regex("${Regex.escape(TOKEN_PREFIX)}[A-Za-z0-9_-]{16,64}")
    private const val BROWSER_CREDENTIAL_PREFIX = "cpag_"
    private const val BROWSER_CREDENTIAL_LABEL = "compose-preview/agent-grant-browser/v1"
    private val BROWSER_CREDENTIAL_SHAPE =
      Regex("${Regex.escape(BROWSER_CREDENTIAL_PREFIX)}[A-Za-z0-9_-]{43}")
    private const val WASM_CREDENTIAL_PREFIX = "cpaw_"
    private const val WASM_CREDENTIAL_LABEL = "compose-preview/agent-grant-wasm-access/v1"
    private val WASM_CREDENTIAL_SHAPE =
      Regex("${Regex.escape(WASM_CREDENTIAL_PREFIX)}[A-Za-z0-9_-]{43}")
  }
}
