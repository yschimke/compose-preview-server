package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.web.WebEscaping
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire types, CSRF seal and pure decisions behind `/agent-access/…`, the device-grant flow in
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 * The routes are `handleAgentGrant*` on [ServeHttpServer], which owns the gates and site skin;
 * everything decidable without a live call lives here so it is unit-testable.
 */
object ServeAgentGrants {

  /** Every route under this prefix; a constant first segment, so it outscores `/{system}`. */
  const val BASE_PATH = "/agent-access"

  const val REQUEST_PATH = "$BASE_PATH/request"
  const val POLL_PATH = "$BASE_PATH/poll"
  const val REVOKE_PATH = "$BASE_PATH/revoke"
  const val WHOAMI_PATH = "$BASE_PATH/whoami"
  const val LEAVE_PATH = "$BASE_PATH/leave"

  /**
   * Where the confirmation page posts when a grant link would replace the browser's current grant.
   * POST and same-origin only, so a grant link can never switch a browser's identity without
   * confirmation on a page this server served.
   */
  const val SWITCH_PATH = "$BASE_PATH/switch"

  /**
   * The longest a poll may be held open: well inside common reverse-proxy timeouts (Caddy's
   * included), and short enough that an abandoned client doesn't hold a connection for minutes.
   */
  const val MAX_POLL_WAIT_SECONDS = 30L

  /**
   * The wait when the caller asked to wait without a duration (the MCP tool's default). Kept under
   * OkHttp's 10s default read timeout: a wait outliving the client's timeout turns into a transport
   * error, worse than answering `pending`.
   */
  const val DEFAULT_POLL_WAIT_SECONDS = 8L

  /**
   * How often a held poll re-reads the store: approvals feel instant, at negligible locking cost.
   */
  const val POLL_WAIT_TICK_MILLIS = 250L

  /** The human's page for one request: `/agent-access/{requestId}`. */
  fun approvalPath(requestId: String): String =
    "$BASE_PATH/${WebEscaping.urlEncodeSegment(requestId)}"

  // ------------------------------------------------------------------- wire

  /**
   * `POST /agent-access/request` body. Every field optional: an agent that knows nothing about this
   * server can POST `{}` and get a `preview` grant request with the default TTL.
   */
  @Serializable
  data class OpenRequest(
    /** What the access is for, shown to the approver. Free text; displayed escaped. */
    val label: String = "",
    /** Highest scope wanted, by wire name. Unknown/absent ⇒ [AgentGrantScope.DEFAULT_REQUEST]. */
    val scope: String = "",
    /** Requested lifetime. Clamped to the box's `--agent-grant-max-ttl`. */
    @SerialName("ttlSeconds") val ttlSeconds: Long = 0,
    /**
     * Independent permissions wanted beside [scope], by wire name ([AgentGrantCapability]). Unknown
     * names are ignored, not refused, so a newer client still gets the rest of its request.
     */
    val capabilities: List<String> = emptyList(),
  )

  /**
   * What the agent gets back. [deviceSecret] is the only secret here and it is the one the agent
   * must keep — [approveUrl] is a handle a human is meant to be shown, and carries no authority.
   */
  @Serializable
  data class OpenResponse(
    val requestId: String,
    val deviceSecret: String,
    val userCode: String,
    /** Absolute — the agent prints this and a human opens it. */
    val approveUrl: String,
    /** Absolute — where to POST [PollRequest]. */
    val pollUrl: String,
    val expiresInSeconds: Long,
    val pollIntervalSeconds: Long,
    /** What was actually requested after clamping to this box's ceiling. */
    val requestedScope: String,
    val requestedTtlSeconds: Long,
    /** The most privileged scope this server will grant at all, so an agent can stop asking. */
    val maxScope: String,
    val maxTtlSeconds: Long,
    /** What was requested after clamping, so an agent can see a capability was dropped. */
    val requestedCapabilities: List<String> = emptyList(),
    /** Every capability this server will grant at all — empty on a box that offers none. */
    val maxCapabilities: List<String> = emptyList(),
  )

  @Serializable
  data class PollRequest(
    val requestId: String = "",
    val deviceSecret: String = "",
    /**
     * Hold the request open up to this many seconds, answering the moment a human decides.
     *
     * Zero (the default) is RFC 8628's shape: answer now, retry after
     * [ServeAgentGrantStore.POLL_INTERVAL_SECONDS]. Fine for a shell loop, wasteful for an MCP
     * client where each poll is a model tool call. Clamped to [MAX_POLL_WAIT_SECONDS]; a wait that
     * times out answers `pending`, as an immediate poll would.
     */
    val waitSeconds: Long = 0,
  )

  /**
   * The poll answer. [status] follows RFC 8628's vocabulary closely enough to be unsurprising:
   * `pending`, `approved`, `denied`, `expired`, `unknown`.
   */
  @Serializable
  data class PollResponse(
    val status: String,
    /**
     * Present exactly once in this token's life, on the first `approved` poll and every re-poll.
     */
    val token: String? = null,
    /** Where to put [token] — spelled out so an agent needn't know this server's conventions. */
    val tokenHeader: String? = null,
    val scopes: List<String> = emptyList(),
    /** Independent permissions the human actually ticked. Usually empty. */
    val capabilities: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    /** How long the agent should wait before polling again. */
    val retryAfterSeconds: Long? = null,
    /** Human-readable, safe to print. Never contains the token. */
    val message: String? = null,
  ) {
    companion object {
      const val PENDING = "pending"
      const val APPROVED = "approved"
      const val DENIED = "denied"
      const val EXPIRED = "expired"
      const val UNKNOWN = "unknown"
    }
  }

  /** `GET /agent-access/whoami` with a bearer — what this grant is, without ever echoing it. */
  @Serializable
  data class WhoamiResponse(
    val active: Boolean,
    val scopes: List<String> = emptyList(),
    val capabilities: List<String> = emptyList(),
    val expiresInSeconds: Long? = null,
    val approvedBy: String? = null,
    val label: String? = null,
    /** SHA-256 prefix, so a caller can match its grant to a `/status` row without disclosing it. */
    val fingerprint: String? = null,
    /**
     * How the rest of the server names this grant's holder (`agent:<fingerprint>`), so an agent can
     * say which actor id to share a design with.
     */
    val actorId: String? = null,
    /**
     * The approver's own actor id — the human this grant delegates for, and the identity a design
     * this agent creates is owned by. Absent on a grant minted before the server recorded one.
     */
    val onBehalfOfActorId: String? = null,
    /**
     * The designs this grant lends [onBehalfOfActorId]'s authority on. Empty — and absent from
     * older replies — when it names none and so reaches every design that person can.
     */
    val designIds: List<String> = emptyList(),
    /**
     * Why this is not a live grant ([ServeAgentGrantStore.TokenState] wire name), absent when
     * [active], so an agent can choose between retrying, re-running approval, and stopping.
     */
    val reason: String? = null,
    /** Human-readable expansion of [reason], safe to print. Never contains a token. */
    val message: String? = null,
  )

  @Serializable data class RevokeResponse(val revoked: Boolean, val message: String? = null)

  // --------------------------------------------------------------- approver

  /**
   * Who is approving, and what they may pass on. **An approver may never grant a capability they do
   * not hold**: on a GitHub-gated box, [ceiling] drops to [AgentGrantScope.LIVE] for a visitor
   * without `--github-auth-repo` access, since that is the playground's own gate.
   */
  data class Approver(
    /** Display name, and what the audit line records: a GitHub login, or `operator (token)`. */
    val name: String,
    /**
     * The same person as [name] as an actor id (`github:<login>` or `operator`, as
     * [ServeMachineAuthorization] spells it). Carried onto the grant so designs an agent creates
     * are owned by the approver rather than an unopenable `agent:…` id.
     */
    val actorId: String,
    val ceiling: AgentGrantScope,
    /**
     * The capabilities this approver may pass on: the same rule for the non-rung half of a grant.
     * E.g. [AgentGrantCapability.IMAGES] requires write access to the image gating repository, so a
     * visitor without it must not mint an agent token that has it.
     */
    val capabilityCeiling: Set<AgentGrantCapability> = emptySet(),
    /**
     * True when this approver answers for the whole box: the `--token` holder or a configured
     * UI-builder administrator. They see every pending request and live grant on `/status`, may
     * revoke any grant, and are held only to the box-wide cap.
     *
     * False for an ordinary signed-in visitor on a `--public` box, who sees and revokes only their
     * own grants and requests, within the per-approver cap.
     */
    val administers: Boolean = true,
  ) {
    /** True when this approver may see and revoke [grant]. */
    fun manages(grant: ServeAgentGrantStore.Grant): Boolean =
      administers || grant.isApprovedBy(name, actorId)

    /** True when this approver is shown [request] among the waiting requests on `/status`. */
    fun sees(request: ServeAgentGrantStore.Request): Boolean =
      administers || (request.requesterActorId.isNotBlank() && request.requesterActorId == actorId)

    companion object {
      /** The holder of `--token` on a box with no GitHub auth: the operator, so no narrowing. */
      fun operator(
        storeCeiling: AgentGrantScope,
        storeCapabilities: Set<AgentGrantCapability> = emptySet(),
      ): Approver = Approver("operator (token)", OPERATOR_ACTOR_ID, storeCeiling, storeCapabilities)

      /**
       * @param repositoryAccess access to the sign-in repository (`--github-auth-repo`), behind the
       * scope ceiling and every capability except [AgentGrantCapability.IMAGES].
       * @param imageRepositoryAccess access to the image lane's repository (`--image-upload-repo`,
       * defaulting to the sign-in one). Asked separately so access to the OAuth repo alone can't
       * mint a grant that publishes where the approver has no rights.
       */
      fun github(
        login: String,
        repositoryAccess: Boolean,
        imageRepositoryAccess: Boolean = repositoryAccess,
        storeCeiling: AgentGrantScope,
        storeCapabilities: Set<AgentGrantCapability> = emptySet(),
        administers: Boolean = true,
        /**
         * `--github-auth-open-ui-builder`: this approver holds the UI builder's capabilities
         * without repository access, and so may pass them on. Nothing else.
         */
        opensUiBuilder: Boolean = false,
      ) =
        Approver(
          name = "@$login",
          administers = administers,
          actorId = githubActorId(login),
          ceiling =
            if (repositoryAccess) storeCeiling else minOf(storeCeiling, AgentGrantScope.LIVE),
          capabilityCeiling =
            buildSet {
              if (repositoryAccess) addAll(storeCapabilities)
              if (opensUiBuilder) addAll(storeCapabilities intersect UI_BUILDER_CAPABILITIES)
              // `images` asks about a different repository, so it neither rides in on nor is
              // withheld by the sign-in bit.
              remove(AgentGrantCapability.IMAGES)
              if (imageRepositoryAccess && AgentGrantCapability.IMAGES in storeCapabilities) {
                add(AgentGrantCapability.IMAGES)
              }
            },
        )
    }
  }

  /**
   * The capabilities a UI-builder route asks for, and all `--github-auth-open-ui-builder` opens.
   */
  val UI_BUILDER_CAPABILITIES: Set<AgentGrantCapability> =
    setOf(
      AgentGrantCapability.UI_BUILDER_READ,
      AgentGrantCapability.UI_BUILDER_WRITE,
      AgentGrantCapability.UI_BUILDER_EXPORT,
    )

  /** How [ServeMachineAuthorization] names the holder of `--token`. */
  const val OPERATOR_ACTOR_ID: String = "operator"

  /** How [ServeMachineAuthorization] names a signed-in GitHub visitor. */
  fun githubActorId(login: String): String = "github:$login"

  /** How [ServeMachineAuthorization] names the holder of a minted grant. */
  fun agentActorId(fingerprint: String): String = "agent:$fingerprint"

  // ------------------------------------------------------------------- CSRF

  /**
   * A per-process seal over `(requestId, approver, action)`, embedded in the approval form and
   * required on the POST. Beyond `SameSite=Lax` and the `?token=` gate, this lock doesn't depend on
   * browser behaviour: a seal minted for another approver, request or action is refused. The key is
   * random per process and never persisted; grant requests don't survive restarts either.
   */
  class Csrf(private val key: ByteArray = randomKey()) {

    fun seal(requestId: String, approver: String, action: String): String =
      mac("$requestId|$approver|$action")

    /** Constant-time. False for anything that wasn't minted here, for this exact triple. */
    fun verify(requestId: String, approver: String, action: String, presented: String?): Boolean =
      ServeUrls.tokensMatch(seal(requestId, approver, action), presented)

    private fun mac(payload: String): String {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(SecretKeySpec(key, "HmacSHA256"))
      return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)))
    }

    companion object {
      const val ACTION_APPROVE = "approve"
      const val ACTION_DENY = "deny"

      private fun randomKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
  }

  /**
   * The capabilities an approver may actually tick: the same three-way narrowing the scopes get —
   * what the agent asked for, what this approver holds, and what the box permits — so the form can
   * never offer something the POST would then refuse.
   */
  fun selectableCapabilities(
    requested: Set<AgentGrantCapability>,
    approver: Approver,
    storeCeiling: Set<AgentGrantCapability>,
  ): Set<AgentGrantCapability> =
    requested intersect approver.capabilityCeiling intersect storeCeiling

  /**
   * The scopes an approver may tick: up to the lowest of the store's ceiling, the approver's
   * ceiling and what the agent asked for. The page never offers to widen a request.
   */
  fun selectableScopes(
    requested: AgentGrantScope,
    approver: Approver,
    storeCeiling: AgentGrantScope,
  ): List<AgentGrantScope> = AgentGrantScope.upTo(minOf(requested, approver.ceiling, storeCeiling))
}
