package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
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
 * Grants live in memory only: a restart drops every grant and its bearer, which suits a
 * short-lived, revocable credential. Requests are different — a person slow to approve should not
 * have to start over because the server was redeployed — so with a [requestsFile] a request's whole
 * lifecycle lives there ([ServeAgentGrantRequestFile]), and that file, not this process's memory,
 * is authoritative: a deploy runs the old and new processes side by side, and every state change
 * (decide, deny, collect, revoke) is a compare-and-set on the shared row. The row holds the device
 * secret hashed and an approval as the *decision*, never a token. An agent's grant is minted only
 * by the process that wins the row from approved to collected ([poll]), from the stored decision
 * clamped to current ceilings, with its lifetime counted from the approval; the collected row names
 * the grant, so a revocation in any process reaches it. A file that cannot be read or written never
 * falls back to memory. Without a file (tests, a local server) nothing touches disk and a grant is
 * minted at approval, as before.
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
  /**
   * Where requests survive a restart, or null to keep them in memory only. See
   * [defaultRequestsFile].
   */
  requestsFile: Path? = null,
  /** Test seam only: called with a point's name just before each compare-and-set on the file. */
  private val interleaveHook: (String) -> Unit = {},
) {

  /** A request waiting for a human, or already resolved by one. */
  data class Request(
    /** Public handle. Appears in the approval link and nowhere sensitive. */
    val id: String,
    /**
     * The agent's half, handed back once by [openRequest]. Never rendered, logged, put in a URL or
     * written to disk; blank for a request reloaded after a restart, which knows only
     * [deviceSecretHash].
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
    /**
     * SHA-256 of [deviceSecret]; what a poll is compared against (constant-time), and the only form
     * of the secret that is persisted.
     */
    val deviceSecretHash: String = ServeAgentGrantStore.secretHash(deviceSecret),
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
    /**
     * What the approver decided, set with [State.APPROVED]. With a requests file it is all there is
     * until collection, and the grant is minted from it then.
     */
    @Volatile var approval: Approval? = null,
    /** Why this server refused an approval at collection; null for a human's denial. */
    @Volatile var refusal: String? = null,
    /**
     * For a [refusal] made after the request's deadline: kept until the refused approval would have
     * expired, so a retry reads `denied`, not `unknown`.
     */
    @Volatile var retainUntilMillis: Long? = null,
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

  /** An approval decision: the grant it stands for, without the bearer. */
  data class Approval(
    val approvedBy: String,
    val approvedByActorId: String,
    val scope: AgentGrantScope,
    val capabilities: Set<AgentGrantCapability>,
    val designIds: Set<String>,
    val approvedAtMillis: Long,
    /** The grant's expiry, fixed at approval: collecting later never extends it. */
    val expiresAtMillis: Long,
    /** Whether [maxActiveGrantsPerApprover] applied to this approval; re-applied at a late mint. */
    val enforceApproverCap: Boolean = true,
  )

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

  private val disk = requestsFile?.let {
    ServeAgentGrantRequestFile(
      it,
      clock,
      legacyFile = it.resolveSibling(ServeAgentGrantRequestFile.LEGACY_NAME),
    )
  }

  init {
    disk?.importLegacyPending()
    disk?.all()?.values?.forEach { adopt(it) }
  }

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
    // With a file, a request exists only once its row does: no fallback to memory alone.
    if (disk != null && !disk.insert(request.toRecord())) {
      requests.remove(request.id)
      return null
    }
    return request
  }

  /** The live request for [id], or null when unknown, expired, or malformed. */
  fun request(id: String?): Request? {
    val key = id?.takeIf { isWellFormedId(it) } ?: return null
    val now = clock()
    purge(now)
    syncFromDisk(key)
    // An approved, uncollected request stays reachable past its deadline until [purge] decides; a
    // pending one expires exactly on time.
    return requests[key]?.takeIf { isLive(it, now) }
  }

  /** What [decide] did. */
  sealed interface Decision {
    /**
     * This decision is the request's. [grant] is the minted grant when one was minted now: always
     * without a [requestsFile] (today's behaviour) and for a person's own request; with a file an
     * agent's grant is minted only when it is collected ([poll]).
     */
    data class Approved(val approval: Approval, val grant: Grant?) : Decision

    /** Someone decided first — here or in another process; [request] reflects that decision. */
    data class AlreadyDecided(val request: Request) : Decision

    /** Refused for a limit on live grants; the request stays pending. */
    data class NoCapacity(val capacity: Capacity) : Decision

    /** No such live request. */
    data object Gone : Decision

    /** The requests file could not be read or written; nothing was decided. Retry. */
    data object Unavailable : Decision
  }

  /**
   * Approve [id]. [scope] and [ttlSeconds] are clamped to the request and this store's ceiling, so
   * a tampered form field buys nothing.
   *
   * With a [requestsFile] this is a compare-and-set of the shared row from pending to approved: the
   * first decision in any process wins, and a loser answers [Decision.AlreadyDecided] and issues
   * nothing. No grant is minted here then (except for a person's own request, which nobody polls):
   * the process that wins the collection mints it, from the decision as stored.
   *
   * Refused ([Decision.NoCapacity], still pending) when the box holds [maxActiveGrants], or with
   * [enforceApproverCap] when this approver holds [maxActiveGrantsPerApprover]; approvals not yet
   * collected count as held. Nothing live is ended to make room ([capacityFor] says which limit). A
   * request naming designs is approved for those unless [limitToRequestedDesigns] is false.
   */
  fun decide(
    id: String,
    approvedBy: String,
    scope: AgentGrantScope,
    ttlSeconds: Long,
    capabilities: Set<AgentGrantCapability> = emptySet(),
    approvedByActorId: String = "",
    enforceApproverCap: Boolean = true,
    limitToRequestedDesigns: Boolean = true,
  ): Decision {
    synchronized(this) {
      // Lookup, expiry check and state transition all under the lock, so a concurrent `purge()`
      // can't detach the request mid-approval.
      val request = request(id) ?: return Decision.Gone
      if (request.state != Request.State.PENDING) return Decision.AlreadyDecided(request)
      val capacity = capacityFor(approvedBy, approvedByActorId, enforceApproverCap)
      if (capacity != Capacity.OK) return Decision.NoCapacity(capacity)
      val now = clock()
      val granted = minOf(scope, request.requestedScope, maxScope)
      // Intersection of what the approver ticked, what the agent asked for and what this box
      // permits.
      val grantedCapabilities =
        capabilities intersect request.requestedCapabilities intersect maxCapabilities
      val ttl = ttlSeconds.coerceIn(1, minOf(request.requestedTtlSeconds, maxGrantTtlSeconds))
      val approval =
        Approval(
          approvedBy = approvedBy,
          approvedByActorId = approvedByActorId,
          scope = granted,
          capabilities = grantedCapabilities,
          designIds = if (limitToRequestedDesigns) request.designIds else emptySet(),
          approvedAtMillis = now,
          expiresAtMillis = now + ttl * 1000,
          enforceApproverCap = enforceApproverCap,
        )
      val disk = disk
      // A person's own request is carried by their session ([activeGrantForRequester]) and never
      // polled, so it is collected the moment it is approved; an agent's waits for its poll.
      val mintNow = disk == null || request.requesterActorId.isNotBlank()
      val grant = if (mintNow) grantFrom(request, approval) else null
      if (disk != null) {
        interleave("decide")
        val wire = approval.toWire()
        val outcome =
          disk.transition(request.id, setOf(ServeAgentGrantRequestFile.PENDING)) {
            it!!.copy(
              state =
                if (grant != null) ServeAgentGrantRequestFile.COLLECTED
                else ServeAgentGrantRequestFile.APPROVED,
              resolvedBy = approvedBy,
              approval = wire,
              grantId = grant?.id,
              grantFingerprint = grant?.fingerprint,
            )
          }
        when (outcome) {
          is ServeAgentGrantRequestFile.Outcome.Done -> adopt(outcome.record)
          is ServeAgentGrantRequestFile.Outcome.Conflict -> {
            adoptOrForget(request.id, outcome.current)
            return Decision.AlreadyDecided(requests[request.id] ?: request)
          }
          ServeAgentGrantRequestFile.Outcome.Unavailable -> return Decision.Unavailable
        }
      } else {
        request.approval = approval
        request.resolvedBy = approvedBy
      }
      if (grant != null) {
        register(grant)
        // Data before the flag that says it is there; [poll]'s lock is the main guarantee.
        val held = requests[request.id] ?: request
        held.grantId = grant.id
        held.approval = approval
        held.resolvedBy = approvedBy
        if (disk != null) held.collected = true
        held.state = Request.State.APPROVED
        audit(
          "agent-grant: minted ${grant.fingerprint} scope=${granted.wire} " +
            capabilityAuditField(grantedCapabilities) +
            designAuditField(grant.designIds) +
            "ttl=${ttl}s approver=$approvedBy label=\"${grant.label}\""
        )
      } else {
        audit(
          "agent-grant: approved scope=${granted.wire} " +
            capabilityAuditField(grantedCapabilities) +
            designAuditField(approval.designIds) +
            "ttl=${ttl}s approver=$approvedBy label=\"${request.label}\" (minted when collected)"
        )
      }
      return Decision.Approved(approval, grant)
    }
  }

  /**
   * [decide], answering the grant minted now, or null. Without a [requestsFile] that is today's
   * contract — idempotent, a re-approval answers the same grant. With one, an agent's grant is
   * minted at collection, so this answers null even on success: use [decide] there.
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
  ): Grant? =
    when (
      val decision =
        decide(
          id,
          approvedBy,
          scope,
          ttlSeconds,
          capabilities,
          approvedByActorId,
          enforceApproverCap,
          limitToRequestedDesigns,
        )
    ) {
      is Decision.Approved -> decision.grant
      is Decision.AlreadyDecided ->
        decision.request.takeIf { it.state == Request.State.APPROVED }?.grantId?.let { grant(it) }
      else -> null
    }

  /**
   * Deny [id]. Idempotent; a no-op on an already-decided request. With a [requestsFile], a
   * compare-and-set from pending: an approval in another process that got there first wins.
   */
  fun deny(id: String, deniedBy: String): Boolean = denyRequest(id, deniedBy) == Denial.DENIED

  /** What [denyRequest] did. */
  enum class Denial {
    DENIED,
    /** Someone decided first — here or in another process. */
    ALREADY_DECIDED,
    /** No such live request. */
    GONE,
    /** The requests file could not be read or written; nothing was decided. Retry. */
    UNAVAILABLE,
  }

  /**
   * [deny], saying why when it did not: a lost race and a failed write call for different pages.
   */
  fun denyRequest(id: String, deniedBy: String): Denial {
    synchronized(this) {
      // Same reason as [decide]: the lookup belongs inside the lock that purging takes.
      val request = request(id) ?: return Denial.GONE
      if (request.state != Request.State.PENDING) return Denial.ALREADY_DECIDED
      val disk = disk
      if (disk != null) {
        interleave("deny")
        val outcome =
          disk.transition(request.id, setOf(ServeAgentGrantRequestFile.PENDING)) {
            it!!.copy(state = ServeAgentGrantRequestFile.DENIED, resolvedBy = deniedBy)
          }
        when (outcome) {
          is ServeAgentGrantRequestFile.Outcome.Done -> adopt(outcome.record)
          is ServeAgentGrantRequestFile.Outcome.Conflict -> {
            adoptOrForget(request.id, outcome.current)
            return Denial.ALREADY_DECIDED
          }
          ServeAgentGrantRequestFile.Outcome.Unavailable -> return Denial.UNAVAILABLE
        }
      } else {
        request.state = Request.State.DENIED
        request.resolvedBy = deniedBy
      }
      audit("agent-grant: denied request by $deniedBy label=\"${request.label}\"")
      return Denial.DENIED
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
   * Under the lock, so a poll can't interleave between [decide]'s writes, see APPROVED with a null
   * `grantId`, answer `expired`, and make the client discard its secret for a freshly minted grant.
   */
  private fun pollLocked(id: String?, deviceSecret: String?): Poll {
    val request = request(id) ?: return Poll.Unknown
    // Hashes on both sides: a reloaded request knows only the hash.
    if (!ServeUrls.tokensMatch(request.deviceSecretHash, deviceSecret?.let(::secretHash))) {
      return Poll.Unknown
    }
    return when (request.state) {
      Request.State.PENDING -> Poll.Pending(request.secondsUntilExpiry(clock()))
      Request.State.DENIED -> Poll.Denied(request.resolvedBy, request.refusal)
      Request.State.APPROVED ->
        when (val collected = collect(request)) {
          is Collection.Granted -> Poll.Approved(collected.grant)
          is Collection.Refused -> Poll.Denied(collected.by, collected.reason)
          Collection.Gone -> Poll.Expired
          is Collection.Retry -> Poll.Pending(collected.secondsUntilExpiry)
        }
    }
  }

  /**
   * The grant behind approved request [id], collecting it if nobody has — for the MCP OAuth code
   * exchange, which hands the bearer out like a poll does. Null when it is not approved, was
   * collected by another process, or cannot be collected right now.
   */
  fun collectGrant(id: String): Grant? =
    synchronized(this) {
      val request = request(id)?.takeIf { it.state == Request.State.APPROVED } ?: return null
      (collect(request) as? Collection.Granted)?.grant
    }

  /**
   * The approval [request] carries while its grant is, or can still be, live — what the approval
   * page shows. Null when it is not approved, or its grant expired or was revoked.
   */
  fun liveApproval(request: Request): Approval? {
    if (request.state != Request.State.APPROVED) return null
    val approval = request.approval ?: return null
    request.grantId?.let { grantId ->
      if (request.collected && grants[grantId] == null) {
        // Collected by another process: live unless revoked or expired there.
        if (grantId in disk?.revokedGrantIds().orEmpty()) return null
      } else if (grant(grantId) == null) {
        return null
      }
    }
    return approval.takeIf { effectiveExpiry(it) > clock() }
  }

  private sealed interface Collection {
    data class Granted(val grant: Grant) : Collection

    data class Refused(val by: String?, val reason: String?) : Collection

    /** Expired, revoked, or collected by another process. */
    data object Gone : Collection

    /** The requests file could not be read or written: ask again shortly. */
    data class Retry(val secondsUntilExpiry: Long) : Collection
  }

  /**
   * Hand out [request]'s bearer. Without a [requestsFile] it was minted at approval. With one, the
   * bearer goes only to the process that wins the shared row from approved to collected, minted
   * then from the decision as stored and clamped to this server's current ceilings; a loser issues
   * nothing and answers as the row now stands. A collection is never answered from memory alone.
   */
  private fun collect(request: Request): Collection {
    val now = clock()
    val disk =
      disk
        ?: return grant(request.grantId)?.let {
          request.collected = true
          Collection.Granted(it)
        } ?: Collection.Gone
    if (request.collected) {
      // Collected here (a lost response is retriable) or elsewhere (nothing to hand out here).
      return grant(request.grantId)?.let { Collection.Granted(it) } ?: Collection.Gone
    }
    val decided = request.approval ?: return Collection.Gone
    val expiresAt = effectiveExpiry(decided)
    if (expiresAt <= now) {
      // Dead under today's ceiling: stop reserving a place for it, here and in the file.
      disk.transition(request.id, setOf(ServeAgentGrantRequestFile.APPROVED)) {
        it!!.copy(state = ServeAgentGrantRequestFile.EXPIRED)
      }
      requests.remove(request.id)
      return Collection.Gone
    }
    // The ceilings this server runs with now, not the ones the approval was made under.
    val approval =
      decided.copy(
        scope = minOf(decided.scope, maxScope),
        capabilities = decided.capabilities intersect maxCapabilities,
        expiresAtMillis = expiresAt,
      )
    val refusal =
      if (decided.capabilities.isNotEmpty() && approval.capabilities.isEmpty()) {
        val withdrawn = AgentGrantCapability.wireNames(decided.capabilities).joinToString(", ")
        "this server no longer allows $withdrawn, which this approval was for; request access again"
      } else {
        when (
          capacityLocked(
            approval.approvedBy,
            approval.approvedByActorId,
            approval.enforceApproverCap,
            excludingRequestId = request.id,
          )
        ) {
          Capacity.OK -> null
          Capacity.APPROVER_FULL ->
            "${approval.approvedBy} already holds as many live grants as one approver may; " +
              "request access again once one ends"
          Capacity.BOX_FULL ->
            "this server already holds as many live grants as it allows; request access again " +
              "once one ends"
        }
      }
    val retry = Collection.Retry((expiresAt - now) / 1000)
    if (refusal != null) {
      val outcome =
        disk.transition(request.id, setOf(ServeAgentGrantRequestFile.APPROVED)) {
          it!!.copy(
            state = ServeAgentGrantRequestFile.DENIED,
            resolvedBy = REFUSED_BY,
            refusal = refusal,
            retainUntilMillis = expiresAt,
          )
        }
      return when (outcome) {
        is ServeAgentGrantRequestFile.Outcome.Done -> {
          adopt(outcome.record)
          audit("agent-grant: refused an approval label=\"${request.label}\": $refusal")
          Collection.Refused(REFUSED_BY, refusal)
        }
        is ServeAgentGrantRequestFile.Outcome.Conflict -> lostTo(request.id, outcome.current)
        ServeAgentGrantRequestFile.Outcome.Unavailable -> retry
      }
    }
    val grant = grantFrom(request, approval)
    interleave("collect")
    return when (
      val outcome =
        disk.transition(request.id, setOf(ServeAgentGrantRequestFile.APPROVED)) {
          it!!.copy(
            state = ServeAgentGrantRequestFile.COLLECTED,
            grantId = grant.id,
            grantFingerprint = grant.fingerprint,
          )
        }
    ) {
      is ServeAgentGrantRequestFile.Outcome.Done -> {
        register(grant)
        adopt(outcome.record)
        audit(
          "agent-grant: minted ${grant.fingerprint} scope=${grant.scope.wire} " +
            capabilityAuditField(grant.capabilities) +
            designAuditField(grant.designIds) +
            "at collection, expires in ${grant.secondsUntilExpiry(now)}s " +
            "approver=${grant.approvedBy} label=\"${grant.label}\""
        )
        Collection.Granted(grant)
      }
      is ServeAgentGrantRequestFile.Outcome.Conflict -> lostTo(request.id, outcome.current)
      ServeAgentGrantRequestFile.Outcome.Unavailable -> retry
    }
  }

  /** A collection that lost its compare-and-set: answer as the row now stands, issuing nothing. */
  private fun lostTo(id: String, current: ServeAgentGrantRequestFile.Record?): Collection {
    val now = adoptOrForget(id, current)
    return if (now?.state == Request.State.DENIED) Collection.Refused(now.resolvedBy, now.refusal)
    else Collection.Gone
  }

  /** What a poller is told. Mirrors RFC 8628's `authorization_pending` / `access_denied` shape. */
  sealed interface Poll {
    data class Pending(val secondsUntilExpiry: Long) : Poll

    data class Approved(val grant: Grant) : Poll

    /**
     * Declined by [by], or — with a [reason] — refused by this server when an approval that
     * outlived a restart no longer fits its limits or ceilings.
     */
    data class Denied(val by: String?, val reason: String? = null) : Poll

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
    return liveGrant(byToken[token] ?: return null)
  }

  /** The live grant named by an HttpOnly browser credential, or null. */
  fun grantForBrowserCredential(presented: String?): Grant? {
    val credential = presented?.takeIf { isWellFormedBrowserCredential(it) } ?: return null
    return liveGrant(byBrowserCredential[credential] ?: return null)
  }

  /**
   * The non-reversible credential a browser cookie carries for [grant] while it is live; its
   * validity is still decided from the grant per request.
   */
  fun browserCredentialFor(grant: Grant): BrowserCredential? {
    val now = clock()
    val live = liveGrant(grant.id)?.takeIf { it.token == grant.token } ?: return null
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
    val live = liveGrant(grant.id)?.takeIf { it.token == grant.token } ?: return null
    return wasmCredentialValue(live.token)
  }

  /** Resolve a private-Wasm path credential back to its authoritative live grant. */
  fun grantForWasmCredential(presented: String?): Grant? {
    val credential = presented?.takeIf { isWellFormedWasmCredential(it) } ?: return null
    return liveGrant(byWasmCredential[credential] ?: return null)
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
    // Revoked by another process sharing the requests file reads as revoked here: unknown.
    if (disk != null && id in disk.revokedGrantIds()) {
      dropLocal(id)
      return TokenState.UNKNOWN
    }
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
    dropRevokedElsewhere()
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
      dropRevokedElsewhere()
      capacityLocked(approvedBy, approvedByActorId, enforceApproverCap, excludingRequestId = null)
    }

  /**
   * Live grants held here, plus grants another process collected (`collected` rows in the shared
   * file whose grant is not in this process's map), plus *reservations* — approvals in the file not
   * yet collected — each counted once while its grant would still be live under today's ceiling, so
   * the caps hold across a restart and an overlapping deploy. [excludingRequestId] is the request
   * being collected, which must not count against itself. Without a file every approval mints at
   * once: no reservations.
   */
  private fun capacityLocked(
    approvedBy: String,
    approvedByActorId: String,
    enforceApproverCap: Boolean,
    excludingRequestId: String?,
  ): Capacity {
    val now = clock()
    val rows = disk?.all()?.values.orEmpty()
    fun ServeAgentGrantRequestFile.Approval.live() =
      effectiveExpiry(approvedAtMillis, expiresAtMillis) > now
    // Approvals nobody has collected yet, in any process.
    val reserved =
      rows
        .filter { it.state == ServeAgentGrantRequestFile.APPROVED && it.id != excludingRequestId }
        .mapNotNull { row -> row.approval?.takeIf { it.live() } }
    // Grants another process collected: in the file as `collected`, absent from this process's
    // map. A grant held here is counted once, from [grants]; a revoked row is no longer
    // `collected`.
    val elsewhere =
      rows
        .filter {
          it.state == ServeAgentGrantRequestFile.COLLECTED &&
            it.grantId != null &&
            !grants.containsKey(it.grantId)
        }
        .mapNotNull { row -> row.approval?.takeIf { it.live() } }
    val byApprover =
      grants.values.count { it.isApprovedBy(approvedBy, approvedByActorId) } +
        (reserved + elsewhere).count {
          if (it.approvedByActorId.isNotBlank()) it.approvedByActorId == approvedByActorId
          else it.approvedBy == approvedBy
        }
    return when {
      enforceApproverCap && byApprover >= maxActiveGrantsPerApprover -> Capacity.APPROVER_FULL
      grants.size + reserved.size + elsewhere.size >= maxActiveGrants -> Capacity.BOX_FULL
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
    dropRevokedElsewhere()
    val matching =
      grants.values.filter {
        it.approvedByActorId == approvedByActorId && it.holderActorId == holderActorId
      }
    val deciding = matching.filter { it.expiresAtMillis > now }.ifEmpty { matching }
    val named = deciding.flatMapTo(mutableSetOf()) { it.designIds }
    return named.ifEmpty { null }
  }

  /** The live grant with this id, or null when unknown, expired or revoked. */
  fun grant(id: String?): Grant? = liveGrant(id)

  /**
   * Revoke a grant by id. With a [requestsFile] the request row naming it moves from collected to
   * revoked under the file lock, so every process sharing the file refuses its bearer from its next
   * token check ([liveGrant]). True only when something was revoked: a grant held here, or a row
   * this call moved — never for an id nobody knows.
   */
  fun revoke(id: String, by: String): Boolean = revokeGrant(id, by) == Revocation.REVOKED

  /** What [revokeGrant] did. */
  enum class Revocation {
    REVOKED,
    /** No grant here and no row naming it: nothing to revoke. */
    UNKNOWN,
    /**
     * The requests file could not be read or written, so a grant another process may hold is not
     * revoked. Retry; nothing should act as if it were gone.
     */
    UNAVAILABLE,
  }

  /** [revoke], telling a miss from a file it could not record the revocation in. */
  fun revokeGrant(id: String, by: String): Revocation {
    val local = dropLocal(id)
    if (local != null) {
      audit("agent-grant: revoked ${local.fingerprint} by $by label=\"${local.label}\"")
    }
    val disk = disk ?: return if (local != null) Revocation.REVOKED else Revocation.UNKNOWN
    var shared = false
    val requestIds =
      disk.idsForGrant(id, setOf(ServeAgentGrantRequestFile.COLLECTED))
        ?: return Revocation.UNAVAILABLE
    for (requestId in requestIds) {
      when (
        disk.transition(requestId, setOf(ServeAgentGrantRequestFile.COLLECTED)) {
          it!!.copy(state = ServeAgentGrantRequestFile.REVOKED)
        }
      ) {
        is ServeAgentGrantRequestFile.Outcome.Done -> {
          shared = true
          requests.remove(requestId)
        }
        is ServeAgentGrantRequestFile.Outcome.Conflict -> Unit
        ServeAgentGrantRequestFile.Outcome.Unavailable -> return Revocation.UNAVAILABLE
      }
    }
    if (local == null && !shared) return Revocation.UNKNOWN
    if (local == null) audit("agent-grant: revoked grant $id (held by another process) by $by")
    return Revocation.REVOKED
  }

  /**
   * Revoke whatever request [requestId] was approved for, collected or not: with a [requestsFile],
   * a compare-and-set from approved or collected to revoked, which every process honours. True when
   * something was revoked.
   */
  fun revokeRequest(requestId: String, by: String): Boolean =
    synchronized(this) {
      val request =
        request(requestId)?.takeIf { it.state == Request.State.APPROVED } ?: return false
      val disk = disk ?: return request.grantId?.let { revoke(it, by) } == true
      val outcome =
        disk.transition(
          request.id,
          setOf(ServeAgentGrantRequestFile.APPROVED, ServeAgentGrantRequestFile.COLLECTED),
        ) {
          it!!.copy(state = ServeAgentGrantRequestFile.REVOKED)
        }
      when (outcome) {
        is ServeAgentGrantRequestFile.Outcome.Done -> {
          adopt(outcome.record)
          audit("agent-grant: revoked request by $by label=\"${request.label}\"")
          true
        }
        is ServeAgentGrantRequestFile.Outcome.Conflict -> {
          adoptOrForget(request.id, outcome.current)
          false
        }
        ServeAgentGrantRequestFile.Outcome.Unavailable -> false
      }
    }

  /**
   * The decision behind [grantId] when another process sharing the requests file collected it — so
   * a revocation routed here can be checked against who approved it. Null without a file.
   */
  fun approvalForGrant(grantId: String): Approval? {
    val disk = disk ?: return null
    val row =
      disk.all().values.firstOrNull {
        it.grantId == grantId && it.state == ServeAgentGrantRequestFile.COLLECTED
      } ?: return null
    return row.toRequest()?.approval
  }

  /**
   * The key approval and revoke forms are sealed with when this store shares a [requestsFile]: the
   * same in every process on that file ([ServeAgentGrantRequestFile.sharedKey]), so a form one
   * process drew is accepted by the other during a rollout. Null without a file, or when the key
   * cannot be read or made; the server then keeps its own random key, as before.
   */
  fun sharedFormKey(): ByteArray? = formKey

  private val formKey: ByteArray? by lazy { disk?.sharedKey() }

  /** A grant another process sharing the requests file collected and holds. Not a credential. */
  data class HeldElsewhere(
    val grantId: String,
    val fingerprint: String,
    val label: String,
    /** As minted: clamped to this server's ceilings, the lifetime counted from approval. */
    val approval: Approval,
  )

  /**
   * Live grants held by other processes on the requests file — `collected`, unexpired, not revoked
   * and not in this process's map — so `/status` here can list and revoke them. Empty without a
   * file.
   */
  fun grantsHeldElsewhere(): List<HeldElsewhere> {
    val disk = disk ?: return emptyList()
    val now = clock()
    return disk.all().values.mapNotNull { row ->
      val grantId = row.grantId ?: return@mapNotNull null
      if (row.state != ServeAgentGrantRequestFile.COLLECTED || grants.containsKey(grantId)) {
        return@mapNotNull null
      }
      val decided = row.toRequest()?.approval ?: return@mapNotNull null
      val approval =
        decided.copy(
          scope = minOf(decided.scope, maxScope),
          capabilities = decided.capabilities intersect maxCapabilities,
          expiresAtMillis = effectiveExpiry(decided),
        )
      if (approval.expiresAtMillis <= now) return@mapNotNull null
      HeldElsewhere(grantId, row.grantFingerprint ?: "", sanitizeLabel(row.label), approval)
    }
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
    dropRevokedElsewhere()
    return grants.values.sortedBy { it.expiresAtMillis }
  }

  /** Live (unresolved, unexpired) requests, soonest expiry first — the `/status` table. */
  fun pendingRequests(): List<Request> {
    val now = clock()
    // With a file, decisions made by an overlapping process show here too.
    disk?.all()?.values?.forEach { synchronized(this) { adopt(it) } }
    purge(now)
    return requests.values
      .filter { it.state == Request.State.PENDING }
      .sortedBy { it.expiresAtMillis }
  }

  /**
   * True when the grant a request minted is expired or revoked, so the record owes nobody. An
   * approval not yet collected is owed until its grant would expire.
   */
  private fun grantIsGone(request: Request, nowMillis: Long): Boolean {
    val grantId =
      request.grantId ?: return effectiveExpiry(request.approval ?: return true) <= nowMillis
    val grant = grants[grantId] ?: return true
    return grant.expiresAtMillis <= nowMillis
  }

  /**
   * Whether [request] is still owed an answer: before its deadline; approved while its grant lives
   * (a late approval must not strand the token); or a refusal kept past the deadline.
   */
  private fun isLive(request: Request, nowMillis: Long): Boolean =
    request.expiresAtMillis > nowMillis ||
      (request.state == Request.State.APPROVED && !grantIsGone(request, nowMillis)) ||
      (request.retainUntilMillis ?: 0) > nowMillis

  /**
   * When [approval]'s grant ends under the ceiling this server runs with now: the expiry decided at
   * approval, or sooner when the maximum lifetime has been lowered since. Counted from approval.
   */
  private fun effectiveExpiry(approval: Approval): Long =
    effectiveExpiry(approval.approvedAtMillis, approval.expiresAtMillis)

  private fun effectiveExpiry(approvedAtMillis: Long, expiresAtMillis: Long): Long =
    minOf(expiresAtMillis, approvedAtMillis + maxGrantTtlSeconds * 1000)

  private fun grantFrom(request: Request, approval: Approval): Grant =
    Grant(
      id = mintId(),
      token = mintToken(),
      scope = approval.scope,
      capabilities = approval.capabilities,
      label = request.label,
      approvedBy = approval.approvedBy,
      approvedByActorId = approval.approvedByActorId,
      requesterActorId = request.requesterActorId,
      designIds = approval.designIds,
      issuedAtMillis = approval.approvedAtMillis,
      expiresAtMillis = approval.expiresAtMillis,
    )

  private fun register(grant: Grant) {
    grants[grant.id] = grant
    byToken[grant.token] = grant.id
    byBrowserCredential[browserCredentialValue(grant.token)] = grant.id
    byWasmCredential[wasmCredentialValue(grant.token)] = grant.id
  }

  /** Remove a grant from this process's maps. */
  private fun dropLocal(grantId: String): Grant? {
    val grant = grants.remove(grantId) ?: return null
    byToken.remove(grant.token)
    byBrowserCredential.remove(browserCredentialValue(grant.token))
    byWasmCredential.remove(wasmCredentialValue(grant.token))
    return grant
  }

  /**
   * [id]'s grant while it may be used: unexpired, and — with a [requestsFile] — not revoked by any
   * process. A grant revoked elsewhere is dropped here on sight. The revoked set is re-read only
   * when the file changed, so this stays a stat on the hot path.
   */
  private fun liveGrant(id: String?): Grant? {
    val grant = grants[id ?: return null] ?: return null
    if (grant.expiresAtMillis <= clock()) return null
    if (disk != null && grant.id in disk.revokedGrantIds()) {
      dropLocal(grant.id)
      return null
    }
    return grant
  }

  /** Drop every local grant another process revoked, before a question about all of them. */
  private fun dropRevokedElsewhere() {
    disk?.revokedGrantIds()?.forEach { if (grants.containsKey(it)) dropLocal(it) }
  }

  /**
   * Make this process's copy of a request what the shared row says. Revoked and expired rows drop
   * the request (and a revoked grant, if it was minted here); a missing row means there is no such
   * request any more. Returns the request as it now stands.
   */
  private fun adoptOrForget(id: String, record: ServeAgentGrantRequestFile.Record?): Request? {
    if (record == null) {
      requests.remove(id)
      return null
    }
    return adopt(record)
  }

  private fun adopt(record: ServeAgentGrantRequestFile.Record): Request? {
    if (
      record.state == ServeAgentGrantRequestFile.REVOKED ||
        record.state == ServeAgentGrantRequestFile.EXPIRED
    ) {
      requests.remove(record.id)
      record.grantId?.let { dropLocal(it) }
      return null
    }
    val restored =
      record.toRequest()
        ?: run {
          requests.remove(record.id)
          return null
        }
    // The device secret is only ever known to the process that opened the request.
    val merged =
      requests[record.id]?.let { restored.copy(deviceSecret = it.deviceSecret) } ?: restored
    requests[record.id] = merged
    return merged
  }

  /**
   * With a [requestsFile], the file is the request's state: refresh this process's copy from it (a
   * stat, and a re-read only when it changed). The old and new servers overlap during a deploy, and
   * each must see what the other decided.
   */
  private fun syncFromDisk(id: String) {
    val disk = disk ?: return
    adoptOrForget(id, disk.all()[id])
  }

  /** Test seam: called at named points just before a compare-and-set on the requests file. */
  private fun interleave(point: String) = interleaveHook(point)

  private fun Approval.toWire(): ServeAgentGrantRequestFile.Approval =
    ServeAgentGrantRequestFile.Approval(
      approvedBy = approvedBy,
      approvedByActorId = approvedByActorId,
      scope = scope.wire,
      capabilities = AgentGrantCapability.wireNames(capabilities).toList(),
      designIds = designIds.sorted(),
      approvedAtMillis = approvedAtMillis,
      expiresAtMillis = expiresAtMillis,
      enforceApproverCap = enforceApproverCap,
    )

  /** A new request's row. */
  private fun Request.toRecord(): ServeAgentGrantRequestFile.Record =
    ServeAgentGrantRequestFile.Record(
      id = id,
      deviceSecretSha256 = deviceSecretHash,
      userCode = userCode,
      label = label,
      client = client,
      requestedScope = requestedScope.wire,
      requestedTtlSeconds = requestedTtlSeconds,
      requestedCapabilities = AgentGrantCapability.wireNames(requestedCapabilities).toList(),
      requesterActorId = requesterActorId,
      designIds = designIds.sorted(),
      createdAtMillis = createdAtMillis,
      expiresAtMillis = expiresAtMillis,
      state = ServeAgentGrantRequestFile.PENDING,
    )

  /** Null for a row this version cannot read back faithfully; it is then treated as absent. */
  private fun ServeAgentGrantRequestFile.Record.toRequest(): Request? {
    if (!isWellFormedId(id)) return null
    val restoredState =
      when (state) {
        ServeAgentGrantRequestFile.PENDING -> Request.State.PENDING
        ServeAgentGrantRequestFile.DENIED -> Request.State.DENIED
        ServeAgentGrantRequestFile.APPROVED,
        ServeAgentGrantRequestFile.COLLECTED -> Request.State.APPROVED
        else -> return null
      }
    val scope = AgentGrantScope.parse(requestedScope) ?: return null
    val approval = approval?.let {
      Approval(
        approvedBy = it.approvedBy,
        approvedByActorId = it.approvedByActorId,
        scope = AgentGrantScope.parse(it.scope) ?: return null,
        capabilities = it.capabilities.mapNotNull(AgentGrantCapability::parse).toSet(),
        designIds = it.designIds.toSet(),
        approvedAtMillis = it.approvedAtMillis,
        expiresAtMillis = it.expiresAtMillis,
        enforceApproverCap = it.enforceApproverCap,
      )
    }
    if (restoredState == Request.State.APPROVED && approval == null) return null
    return Request(
      id = id,
      deviceSecret = "",
      userCode = userCode,
      label = sanitizeLabel(label),
      client = sanitizeLabel(client),
      requestedScope = scope,
      requestedTtlSeconds = requestedTtlSeconds,
      requestedCapabilities = requestedCapabilities.mapNotNull(AgentGrantCapability::parse).toSet(),
      requesterActorId = requesterActorId,
      designIds = designIds.filter(::isWellFormedDesignId).toSet(),
      createdAtMillis = createdAtMillis,
      expiresAtMillis = expiresAtMillis,
      deviceSecretHash = deviceSecretSha256,
      state = restoredState,
      grantId = grantId,
      resolvedBy = resolvedBy,
      collected = state == ServeAgentGrantRequestFile.COLLECTED,
      approval = approval,
      refusal = refusal,
      retainUntilMillis = retainUntilMillis,
    )
  }

  /**
   * Drop everything held in memory — the tests' reset. The [requestsFile] is left alone: it exists
   * to outlive this process.
   */
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
      (!isLive(r, nowMillis)).also { if (it) dropped++ }
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

    /** Who a refusal at collection is attributed to. */
    const val REFUSED_BY = "this server"

    /** Base64url SHA-256 of a device secret: how it is compared and how it is persisted. */
    internal fun secretHash(secret: String): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
          MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        )

    /**
     * `mcp-oauth/agent-access-requests.v2.json` beside the catalogs file, next to the OAuth client
     * registry ([ServeMcpOAuthClients.defaultFile]) on the deploy's config volume. Null — requests
     * in memory only — without a catalogs file or when its directory is not writable; unlike the
     * client registry there is no home-directory fallback, so a local server writes nothing.
     */
    fun defaultRequestsFile(catalogsFile: Path?): Path? {
      val root = catalogsFile?.toAbsolutePath()?.normalize()?.parent ?: return null
      val candidate = root.resolve("mcp-oauth/${ServeAgentGrantRequestFile.FILE_NAME}")
      val state = candidate.parent
      return candidate.takeIf {
        Files.isDirectory(root) &&
          Files.isWritable(root) &&
          (!Files.exists(state) || (Files.isDirectory(state) && Files.isWritable(state))) &&
          (!Files.exists(candidate) || Files.isWritable(candidate))
      }
    }

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
