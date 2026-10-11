package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The authoritative lifecycle of [ServeAgentGrantStore]'s requests when persistence is configured.
 * See
 * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
 *
 * A deploy overlaps the old and new processes (`docker-rollout` scales up before it scales down),
 * so both serve the same requests at once. Each process's memory is a cache of this file; every
 * change of a request's state is a compare-and-set ([transition]) under a JVM lock and an OS file
 * lock, with an atomic replace — the [ServeMcpOAuthClients] pattern — so of two processes acting on
 * one request exactly one wins, and the loser reads the winner's row.
 *
 * Holds requests and approval *decisions* only: the device secret as a SHA-256, and no bearer, no
 * browser or Wasm credential, ever. A `collected` row names the grant id the winning process
 * minted, so a revocation in any process ([REVOKED]) reaches it ([revokedGrantIds]). Expired rows
 * are dropped on every write. A file that cannot be read or written fails the transition
 * ([Outcome.Unavailable]) — never silently read as empty, which would let a stale copy act.
 */
internal class ServeAgentGrantRequestFile(
  file: Path,
  private val clock: () -> Long,
  /**
   * The previous release's file ([LEGACY_NAME]), read once at start for its pending requests
   * ([importLegacyPending]) and never written: that release rewrites it with its own row shape,
   * which would strip this format's fields.
   */
  legacyFile: Path? = null,
) {
  private val file: Path = file.toAbsolutePath().normalize()
  private val legacyFile: Path? =
    legacyFile?.toAbsolutePath()?.normalize()?.takeIf { it != this.file }

  /**
   * This format's file: [format] and [version] are written explicitly (no defaults), so a file of
   * another shape — the previous release's, or a future one — is refused rather than half-read.
   */
  @Serializable
  internal data class Snapshot(val format: String, val version: Int, val requests: List<Record>)

  /** The previous release's file shape: `{"version":1,"requests":[…]}`. */
  @Serializable
  private data class LegacySnapshot(val version: Int = 1, val requests: List<Record> = emptyList())

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
    /** [PENDING], [APPROVED], [DENIED], [COLLECTED], [REVOKED] or [EXPIRED]. */
    val state: String,
    val resolvedBy: String? = null,
    /** The decision, from [APPROVED] on; kept on later states so they live as long as it would. */
    val approval: Approval? = null,
    /** The grant the winning collector minted, from [COLLECTED] on. Not a credential. */
    val grantId: String? = null,
    /**
     * That grant's fingerprint — a truncated SHA-256 of the bearer, already what `/status` and the
     * audit log show — so another process can list it. Not a credential.
     */
    val grantFingerprint: String? = null,
    /** Why the server refused an approval at collection, for a [DENIED] row it refused. */
    val refusal: String? = null,
    /** A refusal's own lifetime, when it outlives the request's deadline. */
    val retainUntilMillis: Long? = null,
  ) {
    /**
     * Past which nobody is owed anything: an approval's grant's expiry, or the request's deadline
     * (later, for a refusal kept until its approval would have expired).
     */
    val keepUntilMillis: Long
      get() = maxOf(approval?.expiresAtMillis ?: 0, expiresAtMillis, retainUntilMillis ?: 0)
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

  /** How a [transition] went. */
  sealed interface Outcome {
    /** This caller's change is now the row. */
    data class Done(val record: Record) : Outcome

    /** The row was not in an expected state (or is gone); [current] is what it is now. */
    data class Conflict(val current: Record?) : Outcome

    /** The file could not be read or written: nothing changed, retry. */
    data object Unavailable : Outcome
  }

  private var cachedStamp: Pair<Instant, Long>? = null
  private var cached: Map<String, Record> = emptyMap()
  private var cachedRevoked: Set<String> = emptySet()

  /**
   * Every live row, from a cache refreshed when the file's modification time or size changes. An
   * unreadable file reads as the last good view, and is reported.
   */
  @Synchronized
  fun all(): Map<String, Record> {
    refresh()
    val now = clock()
    return cached.filterValues { it.keepUntilMillis > now }
  }

  /**
   * Grant ids whose rows are [REVOKED], by any process: what every token lookup checks. Cheap — a
   * stat of the file, and a re-read only when it changed.
   */
  @Synchronized
  fun revokedGrantIds(): Set<String> {
    refresh()
    return cachedRevoked
  }

  private fun refresh() {
    val stamp = stamp()
    if (stamp == null) {
      cached = emptyMap()
      cachedRevoked = emptySet()
      cachedStamp = null
      return
    }
    if (stamp == cachedStamp) return
    try {
      remember(locked { read() })
      cachedStamp = stamp
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not read access requests from $file: $e")
    }
  }

  private fun remember(rows: Map<String, Record>) {
    cached = rows
    cachedRevoked =
      rows.values.filter { it.state == REVOKED }.mapNotNullTo(HashSet()) { it.grantId }
  }

  /** Add a new row; false when the file could not be written. */
  fun insert(record: Record): Boolean =
    transition(record.id, expected = null) { record } is Outcome.Done

  /**
   * Compare-and-set [id]: when its live row's state is in [expected] (or, with null, when there is
   * no row), replace it with [change]'s result, all under one hold of the file lock.
   */
  @Synchronized
  fun transition(id: String, expected: Set<String>?, change: (Record?) -> Record): Outcome =
    try {
      val outcome = locked {
        val rows = read().toMutableMap()
        val now = clock()
        rows.values.removeIf { it.keepUntilMillis <= now }
        val current = rows[id]
        val matches = if (expected == null) current == null else current?.state in expected
        if (!matches) {
          Outcome.Conflict(current)
        } else {
          val next = change(current)
          rows[id] = next
          write(rows.values.toList())
          Outcome.Done(next)
        }
      }
      cachedStamp = null
      outcome
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not update access requests in $file: $e")
      Outcome.Unavailable
    }

  /**
   * The key every process sharing this file seals its approval and revoke forms with, so a form
   * drawn by one is accepted by the other: 32 random bytes in [FORM_KEY_NAME] beside the file,
   * owner-only, made once under the file lock and read by everyone after. Null, reported, when it
   * cannot be read or made — the caller then keeps a per-process key.
   */
  fun sharedKey(): ByteArray? =
    try {
      locked {
        val keyFile = file.resolveSibling(FORM_KEY_NAME)
        Files.exists(keyFile)
          .takeIf { it }
          ?.let { Files.readAllBytes(keyFile) }
          ?.takeIf { it.size == FORM_KEY_BYTES }
          ?: ByteArray(FORM_KEY_BYTES).also { key ->
            SecureRandom().nextBytes(key)
            val temporary = ownerOnlyTempFile(file.parent)
            try {
              Files.write(temporary, key)
              try {
                Files.move(
                  temporary,
                  keyFile,
                  StandardCopyOption.ATOMIC_MOVE,
                  StandardCopyOption.REPLACE_EXISTING,
                )
              } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING)
              }
            } finally {
              Files.deleteIfExists(temporary)
            }
          }
      }
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not read or make the shared form key: $e")
      null
    }

  private fun ownerOnlyTempFile(directory: Path): Path =
    if (directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
      Files.createTempFile(
        directory,
        "agent-grant-csrf-",
        ".key",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
      )
    } else {
      Files.createTempFile(directory, "agent-grant-csrf-", ".key")
    }

  /** Ids of live rows whose state is in [states] and that name [grantId]. */
  @Synchronized
  fun idsForGrant(grantId: String, states: Set<String>): List<String>? =
    try {
      locked { read() }.values.filter { it.grantId == grantId && it.state in states }.map { it.id }
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not read access requests from $file: $e")
      null
    }

  /**
   * Carry the previous release's still-pending requests into this file, at start: each one not
   * already here (so a decision made here is never overwritten). Approved and denied rows are left
   * to expire there — they were decided under the old release, which holds what they need. The old
   * file is only read. A request the old release opens after this process started is not seen here
   * until the agent asks again.
   */
  @Synchronized
  fun importLegacyPending() {
    val legacy = legacyFile ?: return
    if (!Files.exists(legacy)) return
    val rows =
      try {
        if (Files.size(legacy) > MAX_BYTES) throw IOException("too large")
        JSON.decodeFromString<LegacySnapshot>(Files.readString(legacy)).requests
      } catch (e: Exception) {
        if (e !is IOException && e !is SerializationException && e !is IllegalArgumentException) {
          throw e
        }
        System.err.println("serve: agent-grant: not carrying over requests from $legacy: $e")
        return
      }
    val now = clock()
    val pending = rows.filter {
      it.state == PENDING && it.expiresAtMillis > now && it.id.isNotBlank()
    }
    if (pending.isEmpty()) return
    try {
      locked {
        val current = read().toMutableMap()
        val added = pending.filter { it.id !in current }
        if (added.isNotEmpty()) {
          added.forEach { current[it.id] = it.copy(approval = null, grantId = null) }
          current.values.removeIf { it.keepUntilMillis <= now }
          write(current.values.toList())
        }
      }
      cachedStamp = null
    } catch (e: IOException) {
      System.err.println("serve: agent-grant: could not carry over requests from $legacy: $e")
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

  /** Strict: an unreadable or malformed file is an [IOException], never an empty map. */
  private fun read(): Map<String, Record> {
    if (!Files.exists(file)) return emptyMap()
    if (Files.size(file) > MAX_BYTES) throw IOException("access requests file is too large")
    val snapshot =
      try {
        JSON.decodeFromString<Snapshot>(Files.readString(file))
      } catch (e: SerializationException) {
        throw IOException("malformed access requests file", e)
      } catch (e: IllegalArgumentException) {
        throw IOException("malformed access requests file", e)
      }
    if (snapshot.format != FORMAT || snapshot.version != VERSION) {
      throw IOException(
        "not an access requests file of this format: ${snapshot.format} v${snapshot.version}"
      )
    }
    return snapshot.requests.filter { it.id.isNotBlank() }.associateBy { it.id }
  }

  private fun write(rows: List<Record>) {
    val json = JSON.encodeToString(Snapshot(format = FORMAT, version = VERSION, requests = rows))
    if (json.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
      throw IOException("access requests too large")
    }
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
    const val PENDING = "pending"
    const val APPROVED = "approved"
    const val DENIED = "denied"
    /** Approved and handed out — to exactly one process, named by [Record.grantId]. */
    const val COLLECTED = "collected"
    /** Ended by a person; its [Record.grantId], if any, is refused by every process. */
    const val REVOKED = "revoked"
    /** An approval that ran out before anyone collected it. */
    const val EXPIRED = "expired"

    /** This format's file name. The previous release never opens it. */
    const val FILE_NAME = "agent-access-requests.v2.json"
    /** The previous release's file, which it still reads and rewrites during the first rollout. */
    const val LEGACY_NAME = "agent-access-requests.json"
    const val FORMAT = "compose-preview/agent-access-requests"
    const val VERSION = 2

    const val FORM_KEY_NAME = "agent-grant-csrf.key"
    private const val FORM_KEY_BYTES = 32

    /** Bounded by the store's own caps (pending + denied, plus live grants); this is a backstop. */
    private const val MAX_BYTES = 2 * 1024 * 1024L
    private val JSON = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<Path, Any>()
  }
}
