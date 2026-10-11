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
 * have to start over because the server was redeployed — so with a [requestsFile] the unresolved
 * and the approved-but-uncollected ones are written there ([ServeAgentGrantRequestFile]): the
 * request with its device secret hashed, and for an approval the *decision*, never a token. A grant
 * whose approval survived a restart is minted when its requester collects it ([Poll.Approved]), its
 * lifetime counted from the approval. Without a file (tests, a local server) nothing touches disk.
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
     * What the approver decided, set with [State.APPROVED]. After a restart it is all that is left
     * of an uncollected approval, and the grant is minted from it at collection.
     */
    @Volatile var approval: Approval? = null,
    /** Why this server refused an approval that outlived a restart; null for a human's denial. */
    @Volatile var refusal: String? = null,
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

  private val disk = requestsFile?.let { ServeAgentGrantRequestFile(it, clock) }

  init {
    disk?.all()?.values?.forEach { record -> record.toRequest()?.let { requests[it.id] = it } }
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
    persist(request)
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
        Request.State.APPROVED -> return grantFor(request, clock())
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
      val grant = grantFrom(request, approval)
      register(grant)
      // Data before the flag that says it is there; [poll]'s lock is the main guarantee.
      request.grantId = grant.id
      request.approval = approval
      request.resolvedBy = approvedBy
      request.state = Request.State.APPROVED
      if (request.requesterActorId.isBlank()) {
        // The decision, not the grant: a restart before collection mints a fresh bearer from it.
        persist(request)
      } else {
        // A person's own request is carried by their session ([activeGrantForRequester]), never
        // polled: it is collected the moment it is approved, so it lives exactly as long as a
        // grant. A tombstone, not a deletion, so an overlapping process cannot approve it again.
        tombstone(request, COLLECTED_WIRE)
      }
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
      persist(request)
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
    // Hashes on both sides: a reloaded request knows only the hash.
    if (!ServeUrls.tokensMatch(request.deviceSecretHash, deviceSecret?.let(::secretHash))) {
      return Poll.Unknown
    }
    return when (request.state) {
      Request.State.PENDING -> Poll.Pending(request.secondsUntilExpiry(clock()))
      Request.State.DENIED -> Poll.Denied(request.resolvedBy, request.refusal)
      Request.State.APPROVED -> {
        // The grant may have been revoked or expired since approval; say so rather than return a
        // dead token. An approval reloaded after a restart mints its grant here.
        val grant =
          grantFor(request, clock())
            ?: return if (request.state == Request.State.DENIED) {
              Poll.Denied(request.resolvedBy, request.refusal)
            } else {
              Poll.Expired
            }
        if (!request.collected) {
          request.collected = true
          // Collected: the bearer is out, and neither a later restart nor an overlapping process
          // may mint a second one from the same approval.
          tombstone(request, COLLECTED_WIRE)
        }
        Poll.Approved(grant)
      }
    }
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
      capacityLocked(approvedBy, approvedByActorId, enforceApproverCap, excluding = null)
    }

  /**
   * Live grants plus *reservations* — approvals restored after a restart that have not minted yet
   * and would still be live — so the caps hold across a restart. [excluding] is the request being
   * minted, which must not count against itself.
   */
  private fun capacityLocked(
    approvedBy: String,
    approvedByActorId: String,
    enforceApproverCap: Boolean,
    excluding: Request?,
  ): Capacity {
    val now = clock()
    val reserved =
      requests.values
        .filter { it !== excluding && it.grantId == null && it.state == Request.State.APPROVED }
        .mapNotNull { it.approval?.takeIf { approval -> approval.expiresAtMillis > now } }
    val byApprover =
      grants.values.count { it.isApprovedBy(approvedBy, approvedByActorId) } +
        reserved.count {
          if (it.approvedByActorId.isNotBlank()) it.approvedByActorId == approvedByActorId
          else it.approvedBy == approvedBy
        }
    return when {
      enforceApproverCap && byApprover >= maxActiveGrantsPerApprover -> Capacity.APPROVER_FULL
      grants.size + reserved.size >= maxActiveGrants -> Capacity.BOX_FULL
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
    // Neither a restart nor an overlapping process may mint the approval behind it again.
    synchronized(this) {
      requests.values.filter { it.grantId == id }.forEach { tombstone(it, REVOKED_WIRE) }
    }
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

  /**
   * True when the grant a request minted is expired or revoked, so the record owes nobody. An
   * approval reloaded after a restart, not yet minted, is owed until its grant would expire.
   */
  private fun grantIsGone(request: Request, nowMillis: Long): Boolean {
    val grantId =
      request.grantId ?: return (request.approval?.expiresAtMillis ?: return true) <= nowMillis
    val grant = grants[grantId] ?: return true
    return grant.expiresAtMillis <= nowMillis
  }

  /**
   * [request]'s grant: the one already minted, or — for an approval that outlived a restart — one
   * minted now from the decision, its lifetime still counted from the approval. Null when the grant
   * is revoked, expired, or there is no approval.
   */
  private fun grantFor(request: Request, nowMillis: Long): Grant? {
    request.grantId?.let {
      return grant(it)
    }
    val decided = request.approval ?: return null
    if (decided.expiresAtMillis <= nowMillis) return null
    // Another process sharing the file may have revoked or collected it since this one loaded it;
    // the file, read now under its lock, is the authority, and a missing row owes nobody.
    disk?.let { disk ->
      if (disk.fresh(request.id)?.state != APPROVED_WIRE) {
        requests.remove(request.id)
        return null
      }
    }
    // The ceilings this server runs with now, not the ones the approval was made under.
    val approval =
      decided.copy(
        scope = minOf(decided.scope, maxScope),
        capabilities = decided.capabilities intersect maxCapabilities,
        expiresAtMillis =
          minOf(decided.expiresAtMillis, decided.approvedAtMillis + maxGrantTtlSeconds * 1000),
      )
    if (approval.expiresAtMillis <= nowMillis) return null
    if (decided.capabilities.isNotEmpty() && approval.capabilities.isEmpty()) {
      val withdrawn = AgentGrantCapability.wireNames(decided.capabilities).joinToString(", ")
      return refuseRestored(
        request,
        "this server no longer allows $withdrawn, which this approval was for; " +
          "request access again",
      )
    }
    val capacity =
      capacityLocked(
        approval.approvedBy,
        approval.approvedByActorId,
        approval.enforceApproverCap,
        excluding = request,
      )
    if (capacity != Capacity.OK) {
      return refuseRestored(
        request,
        if (capacity == Capacity.APPROVER_FULL) {
          "${approval.approvedBy} already holds as many live grants as one approver may; " +
            "request access again once one ends"
        } else {
          "this server already holds as many live grants as it allows; request access again " +
            "once one ends"
        },
      )
    }
    val grant = grantFrom(request, approval)
    register(grant)
    request.grantId = grant.id
    audit(
      "agent-grant: minted ${grant.fingerprint} scope=${grant.scope.wire} " +
        capabilityAuditField(grant.capabilities) +
        designAuditField(grant.designIds) +
        "from an approval made before a restart, expires in " +
        "${grant.secondsUntilExpiry(nowMillis)}s approver=${grant.approvedBy} " +
        "label=\"${grant.label}\""
    )
    return grant
  }

  /** Refuse a restored approval for [reason]: it reads as denied, here and after a restart. */
  private fun refuseRestored(request: Request, reason: String): Grant? {
    request.state = Request.State.DENIED
    request.resolvedBy = "this server"
    request.refusal = reason
    request.approval = null
    persist(request)
    audit("agent-grant: refused a restored approval label=\"${request.label}\": $reason")
    return null
  }

  /**
   * Mark [request]'s row [state] (`collected` or `revoked`) rather than delete it, kept until its
   * approval would expire, so a process holding an older copy can tell "gone" from "never seen".
   */
  private fun tombstone(request: Request, state: String) {
    disk?.upsert(request.toRecord().copy(state = state))
  }

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

  /** Write [request] as it now stands; a no-op without a [requestsFile]. */
  private fun persist(request: Request) {
    disk?.upsert(request.toRecord())
  }

  /**
   * Adopt what another process wrote about [id] — the old and new servers overlap during a deploy,
   * and a request opened or resolved on the old one must not read as unknown on the new. Only an
   * unknown or still-pending request is refreshed; anything this process resolved is its own.
   */
  private fun syncFromDisk(id: String) {
    val disk = disk ?: return
    val memory = requests[id]
    if (memory != null && memory.state != Request.State.PENDING) return
    val record = disk.all()[id] ?: return
    if (memory != null && record.state == PENDING_WIRE) return
    if (record.state == COLLECTED_WIRE || record.state == REVOKED_WIRE) {
      // Resolved and finished elsewhere: a pending copy here must not be approved a second time.
      requests.remove(id)
      return
    }
    record.toRequest()?.let { requests[id] = it }
  }

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
      state = state.name.lowercase(),
      resolvedBy = resolvedBy,
      approval =
        approval?.let {
          ServeAgentGrantRequestFile.Approval(
            approvedBy = it.approvedBy,
            approvedByActorId = it.approvedByActorId,
            scope = it.scope.wire,
            capabilities = AgentGrantCapability.wireNames(it.capabilities).toList(),
            designIds = it.designIds.sorted(),
            approvedAtMillis = it.approvedAtMillis,
            expiresAtMillis = it.expiresAtMillis,
            enforceApproverCap = it.enforceApproverCap,
          )
        },
      refusal = refusal,
    )

  /** Null for a row this version cannot read back faithfully; it is then simply not restored. */
  private fun ServeAgentGrantRequestFile.Record.toRequest(): Request? {
    if (!isWellFormedId(id)) return null
    val restoredState =
      Request.State.entries.firstOrNull { it.name.lowercase() == state } ?: return null
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
    if ((restoredState == Request.State.APPROVED) != (approval != null)) return null
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
      resolvedBy = resolvedBy,
      approval = approval,
      refusal = refusal,
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

    private const val PENDING_WIRE = "pending"
    private const val APPROVED_WIRE = "approved"

    /** Tombstones: never restored, kept so an older copy elsewhere cannot mint. */
    private const val COLLECTED_WIRE = "collected"
    private const val REVOKED_WIRE = "revoked"

    /** Base64url SHA-256 of a device secret: how it is compared and how it is persisted. */
    internal fun secretHash(secret: String): String =
      Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
          MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        )

    /**
     * `mcp-oauth/agent-access-requests.json` beside the catalogs file, next to the OAuth client
     * registry ([ServeMcpOAuthClients.defaultFile]) on the deploy's config volume. Null — requests
     * in memory only — without a catalogs file or when its directory is not writable; unlike the
     * client registry there is no home-directory fallback, so a local server writes nothing.
     */
    fun defaultRequestsFile(catalogsFile: Path?): Path? {
      val root = catalogsFile?.toAbsolutePath()?.normalize()?.parent ?: return null
      val candidate = root.resolve("mcp-oauth/agent-access-requests.json")
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
