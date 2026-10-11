package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The on-disk half of [ServeAgentGrantStore]'s requests, so a person slow to approve does not lose
 * the request to a redeploy. See
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * Holds requests and approval *decisions* only: the device secret as a SHA-256, and no bearer, no
 * browser or Wasm credential, ever — a grant is minted from the decision when its requester
 * collects it. Every change is a read-modify-write under a JVM lock and an OS file lock with an
 * atomic replace, the [ServeMcpOAuthClients] pattern, so two overlapping processes during a deploy
 * add to the file rather than overwrite each other. Expired rows are dropped on every read.
 */
internal class ServeAgentGrantRequestFile(file: Path, private val clock: () -> Long) {
  private val file: Path = file.toAbsolutePath().normalize()

  @Serializable
  internal data class Snapshot(val version: Int = 1, val requests: List<Record> = emptyList())

  /** One request, as persisted. Every field but [deviceSecretSha256] is what the page shows. */
  @Serializable
  internal data class Record(
    val id: String,
    /** Base64url SHA-256 of the device secret; the secret itself is never written. */
    val deviceSecretSha256: String,
    val userCode: String,
    val label: String,
    val client: String,
    /** [ee.schimke.composeai.agentgrants.AgentGrantScope.wire]. */
    val requestedScope: String,
    val requestedTtlSeconds: Long,
    val requestedCapabilities: List<String> = emptyList(),
    val requesterActorId: String = "",
    val designIds: List<String> = emptyList(),
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    /**
     * `pending`, `approved` or `denied`; or the tombstones `collected` and `revoked`, which are
     * never restored and are kept only so a process holding an older copy cannot mint from it.
     */
    val state: String,
    val resolvedBy: String? = null,
    /** Set when [state] is `approved`, and kept on a tombstone so it lives as long. */
    val approval: Approval? = null,
    /** Why the server refused a restored approval, for a `denied` row it refused. */
    val refusal: String? = null,
    /** A refusal's own lifetime, when it outlives the request's deadline. */
    val retainUntilMillis: Long? = null,
  ) {
    /**
     * Past which nobody is owed anything: an approval's grant's expiry, or the request's deadline
     * (later, for a refusal kept until its approval would have expired).
     */
    val keepUntilMillis: Long
      get() = approval?.expiresAtMillis ?: maxOf(expiresAtMillis, retainUntilMillis ?: 0)
  }

  /** How a [claim] went. */
  enum class Claim {
    /** The row was `approved` and is now `collected` by this caller. */
    WON,
    /** The row had already moved on: collected, revoked or expired elsewhere. */
    TAKEN,
    /** No such live row. */
    MISSING,
    /** The file could not be read or written. */
    UNAVAILABLE,
  }

  /** What an approver decided — enough to mint the grant later, with no credential in it. */
  @Serializable
  internal data class Approval(
    val approvedBy: String,
    val approvedByActorId: String = "",
    val scope: String,
    val capabilities: List<String> = emptyList(),
    val designIds: List<String> = emptyList(),
    val approvedAtMillis: Long,
    /** The grant's expiry, counted from [approvedAtMillis], so collecting late buys no time. */
    val expiresAtMillis: Long,
    val enforceApproverCap: Boolean = true,
  )

  private var cachedStamp: Pair<Instant, Long>? = null
  private var cached: Map<String, Record> = emptyMap()

  /** Every live row. Unreadable state is reported and read as empty rather than failing a poll. */
  @Synchronized
  fun all(): Map<String, Record> {
    val stamp = stamp() ?: return emptyMap<String, Record>().also { cached = it }
    if (stamp != cachedStamp) {
      cached = locked { read() }
      cachedStamp = stamp
    }
    val now = clock()
    return cached.filterValues { it.keepUntilMillis > now }
  }

  /** [id]'s row read now under the file lock, past the cache: for a decision that must not race. */
  @Synchronized
  fun fresh(id: String): Record? =
    try {
      locked { read() }[id]?.takeIf { it.keepUntilMillis > clock() }
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not read access requests from $file: $e")
      null
    }

  /**
   * Compare-and-set [id] from `approved` to [collected] under one hold of the file lock, so of two
   * processes collecting one approval exactly one wins.
   */
  @Synchronized
  fun claim(id: String, collected: Record): Claim =
    try {
      val outcome = locked {
        val rows = read().toMutableMap()
        rows.values.removeIf { it.keepUntilMillis <= clock() }
        val row = rows[id]
        when {
          row == null -> Claim.MISSING
          row.state != APPROVED -> Claim.TAKEN
          else -> {
            rows[id] = collected
            write(rows.values.toList())
            Claim.WON
          }
        }
      }
      cachedStamp = null
      outcome
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not claim an access request in $file: $e")
      Claim.UNAVAILABLE
    }

  fun upsert(record: Record) = change { it[record.id] = record }

  @Synchronized
  private fun change(action: (MutableMap<String, Record>) -> Unit) {
    try {
      locked {
        val rows = read().toMutableMap()
        val before = rows.toMap()
        rows.values.removeIf { it.keepUntilMillis <= clock() }
        action(rows)
        if (rows != before || !Files.exists(file)) write(rows.values.toList())
      }
      cachedStamp = null
    } catch (e: IOException) {
      // The in-memory store still works; only restart survival is lost for this change.
      System.err.println("serve: agent-grant: could not persist access requests to $file: $e")
    }
  }

  private fun <T> locked(action: () -> T): T =
    synchronized(locks.computeIfAbsent(file) { Any() }) {
      ServeOwnerOnlyFiles.createDirectories(file.parent)
      val lockFile = file.resolveSibling("${file.fileName}.lock")
      FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel
        ->
        channel.lock().use { action() }
      }
    }

  /** Modification time at the filesystem's full resolution, and size: when to re-read. */
  private fun stamp(): Pair<Instant, Long>? =
    try {
      if (!Files.exists(file)) null
      else Files.getLastModifiedTime(file).toInstant() to Files.size(file)
    } catch (_: IOException) {
      null
    }

  private fun read(): Map<String, Record> {
    if (!Files.exists(file)) return emptyMap()
    return try {
      require(Files.size(file) <= MAX_BYTES) { "too large" }
      val snapshot = JSON.decodeFromString<Snapshot>(Files.readString(file))
      require(snapshot.version == 1) { "unsupported version ${snapshot.version}" }
      snapshot.requests.filter { it.id.isNotBlank() }.associateBy { it.id }
    } catch (e: Exception) {
      if (e !is IOException && e !is SerializationException && e !is IllegalArgumentException) {
        throw e
      }
      // A corrupt file costs the pending requests in it, never startup; the next write replaces it.
      System.err.println("serve: agent-grant: ignoring unreadable access requests in $file: $e")
      emptyMap()
    }
  }

  private fun write(rows: List<Record>) {
    val json = JSON.encodeToString(Snapshot(requests = rows))
    require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "access requests too large" }
    val temporary = Files.createTempFile(file.parent, "agent-access-requests-", ".json")
    try {
      Files.writeString(temporary, json)
      try {
        Files.move(
          temporary,
          file,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  companion object {
    /** Bounded by the store's own caps (pending + denied, plus live grants); this is a backstop. */
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private const val APPROVED = "approved"
    private val JSON = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<Path, Any>()
  }
}
