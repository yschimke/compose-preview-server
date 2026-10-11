package ee.schimke.composeai.cli.serve

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/** What the pool holds and what its reads did — `/status.json` → `catalogCache`. */
@Serializable
data class CatalogBlobPoolSnapshot(
  val blobs: Int,
  val bytes: Long,
  val maxBytes: Long,
  /** Reads answered from disk — the requests this pool did not make. */
  val hits: Long,
  /** Reads that had to produce the blob. */
  val misses: Long,
  val writes: Long,
  val writeFailures: Long,
  /** Blobs reclaimed by [CatalogBlobPool.sweep]. */
  val evicted: Long,
  /** Blobs dropped because their bytes did not hash back to their name. */
  val corrupt: Long,
  /** Cached entries re-checked against the branch by the sampled audit. */
  val audited: Long = 0,
  /**
   * Audited entries whose cached bytes didn't match the branch. Should always be zero; non-zero
   * means a key was filed against the wrong content. Worth an alert.
   */
  val mismatched: Long = 0,
  /**
   * Whether an operator configured `--catalog-cache-dir` rather than the temp-dir fallback. `true`
   * only records the decision (an unmounted path is just as ephemeral); [adopted] is the evidence
   * that storage persisted.
   */
  val persistenceConfigured: Boolean = false,
  /**
   * Blobs already on disk when this process opened the pool: the only evidence anything survived a
   * restart. `0` with [persistenceConfigured] after a roll is the failure.
   */
  val adopted: Int = 0,
  val lastFailure: String? = null,
  /**
   * Pinned reads answered "not found" from a remembered miss ([CatalogBlobPool.knownMissing])
   * instead of the branch.
   */
  val knownMissingHits: Long = 0,
)

/**
 * Content-addressed home for the heavy bytes a catalog load fetches — the executable `liveBundle`,
 * its per-preview splits, and the externalised resource pool — so they survive container recreation
 * and the per-system directory swap every [ServeCatalogStore.load] performs.
 *
 * **Only bytes with an immutable address may be [keyed] here**: commit-pinned
 * `raw.githubusercontent.com/<repo>/<commit>/…` URLs. The un-pinned branch-ref fallback must never
 * reach this pool. [contentAddressed] keys by a digest a trusted manifest declared.
 *
 * ```
 * <root>/content/<sha256-of-bytes>     the blobs themselves
 * <root>/keys/<sha256-of-key>          a pointer: the content sha its key resolves to
 * ```
 *
 * One blob space makes every blob self-verifying (its name is its digest), so a truncated entry is
 * never handed to a classloader. The pointer is a single atomic write whose every value names a
 * verifiable blob, so racing replicas can at worst re-fetch.
 *
 * A rolling update runs two replicas on this volume: writes are staged per writer and moved
 * atomically, reads verify, and [sweep] spares anything younger than [graceMillis].
 */
class CatalogBlobPool(
  private val root: File,
  /**
   * Ceiling for the whole pool, enforced by [sweep] rather than at write time, so loads never block
   * on a byte census.
   */
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  /** How recently a blob must have been touched to be spared by [sweep] (a rolling update). */
  private val graceMillis: Long = DEFAULT_SWEEP_GRACE_MILLIS,
  /**
   * Whether an operator named a directory for [root]. Deliberately not called "durable": nothing
   * here can tell whether the storage outlives the process; [adopted] reports what actually
   * survived.
   */
  private val persistenceConfigured: Boolean = false,
  /** How long a remembered pinned "not found" is trusted ([knownMissing]); zero disables it. */
  private val missingTtlMillis: Long = DEFAULT_MISSING_TTL_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val hits = AtomicLong()
  private val misses = AtomicLong()
  private val writes = AtomicLong()
  private val writeFailures = AtomicLong()
  private val evicted = AtomicLong()
  private val corrupt = AtomicLong()
  private val audited = AtomicLong()
  private val mismatched = AtomicLong()
  private val knownMissingHits = AtomicLong()
  /**
   * Occupancy as of the last census, adjusted by each write and reclaim. Published rather than
   * measured, so a polled `/status.json` never walks the directory (as [ThemeCacheStore] does);
   * lags by at most one sweep.
   */
  private val knownBlobs = java.util.concurrent.atomic.AtomicInteger()
  private val knownBytes = AtomicLong()
  @Volatile private var lastFailure: String? = null
  private val tempSequence = AtomicLong()

  /** Distinguishes this process's in-flight writes from a concurrently deployed replica's. */
  private val writerId: String =
    ProcessHandle.current().pid().toString(36) + "-" + System.identityHashCode(this).toString(36)

  /**
   * Serialises production per key in-process so concurrent callers produce each blob once;
   * cross-process races are settled by the atomic move.
   */
  private val keyLocks = ConcurrentHashMap<String, Any>()

  private val contentDir = File(root, CONTENT_DIR)
  private val keysDir = File(root, KEYS_DIR)
  private val missingDir = File(root, MISSING_DIR)

  /**
   * Blobs present at open, from one census that also seeds published occupancy. `0` on a configured
   * pool after a roll means the cache silently starts over each time.
   */
  private val adopted: Int = census().blobs

  /**
   * The blob with sha256 [sha256], fetched once when absent, or null. A hit is trusted only after
   * its bytes hash back ([size] checked first because it's free). Null rather than exceptions: the
   * pool is an optimisation, and a read-only or full disk must still load catalogs.
   */
  fun contentAddressed(sha256: String, size: Long, fetch: () -> ByteArray?): File? {
    val sha = sha256.takeIf(::isSha) ?: return null
    val blob = File(contentDir, sha)
    readVerified(blob, sha, size)?.let {
      hits.increment()
      return it
    }
    misses.increment()
    return synchronized(keyLocks.computeIfAbsent(sha) { Any() }) {
      // Double-checked: a queued caller reads what the first landed, counted as a hit.
      readVerified(blob, sha, size)?.also { hits.increment() }
        ?: run {
          val bytes = runCatching(fetch).getOrNull() ?: return@run null
          if (sha256Hex(bytes) != sha) {
            recordFailure("declared sha256 $sha does not match the bytes fetched for it")
            return@run null
          }
          store(bytes, sha)
        }
    }
  }

  /**
   * The blob [key] resolves to, produced once with [produce] when absent, or null. [key] must be an
   * immutable address. [produce] writes a scratch file that is hashed and stored under its digest,
   * so a non-reproducible producer costs at most a duplicate blob, never a wrong read.
   */
  fun keyed(key: String, produce: (dest: File) -> Boolean): File? {
    val pointer = File(keysDir, sha256Hex(key.toByteArray()))
    resolve(pointer)?.let {
      hits.increment()
      return it
    }
    misses.increment()
    return synchronized(keyLocks.computeIfAbsent(key) { Any() }) {
      resolve(pointer)?.also { hits.increment() }
        ?: run {
          val scratch = temp("produce") ?: return@run null
          val produced = runCatching { produce(scratch) }.getOrDefault(false) && scratch.isFile
          if (!produced) {
            scratch.delete()
            return@run null
          }
          val sha = sha256Hex(scratch)
          if (sha == null) {
            scratch.delete()
            recordFailure("could not digest the blob produced for a key")
            return@run null
          }
          val blob = adopt(scratch, sha) ?: return@run null
          // Written last, and only once the blob it names is in place — a pointer is only ever
          // read back through [resolve], which re-verifies, so a stale one degrades to a miss.
          writeAtomically(pointer, sha.toByteArray())
          blob
        }
    }
  }

  /**
   * Whether [key] resolves to a present blob, without verifying its bytes, for cheap availability
   * probes. A later corrupt read just costs one re-produce.
   */
  fun holds(key: String): Boolean =
    resolve(File(keysDir, sha256Hex(key.toByteArray())), verify = false) != null

  /**
   * The bytes cached under [key], or null. Separate from [keyed] (which produces under a lock) so a
   * request-path miss returns immediately and the caller can report why the branch failed.
   */
  fun read(key: String): ByteArray? {
    val blob = resolve(File(keysDir, sha256Hex(key.toByteArray()))) ?: return null
    hits.increment()
    return runCatching { blob.readBytes() }.getOrNull()
  }

  /** Cache [bytes] under [key], best-effort. [key] must be an immutable address. */
  fun write(key: String, bytes: ByteArray) {
    misses.increment()
    val sha = sha256Hex(bytes)
    val blob = File(contentDir, sha)
    if (blob.isFile) {
      // Already held under another key (a republish's unchanged assets at a new commit): re-stamp
      // it so the next sweep doesn't evict current assets.
      stamp(blob)
    } else if (store(bytes, sha) == null) {
      return
    }
    writeAtomically(File(keysDir, sha256Hex(key.toByteArray())), sha.toByteArray())
  }

  /**
   * Whether the branch recently answered "not found" for [key], so the caller can skip the request.
   * Catalogs declare far more assets than they publish (thousands of figma-vector 404s per load),
   * which otherwise compete for the branch host's rate limit.
   *
   * A TTL, unlike hits: a 404 is one CDN response and a just-pushed commit can briefly 404, so a
   * miss is trusted for [missingTtlMillis] and then heals. [key] must be an immutable address.
   */
  fun knownMissing(key: String): Boolean {
    if (missingTtlMillis <= 0) return false
    val marker = File(missingDir, sha256Hex(key.toByteArray()))
    val recordedAt = marker.lastModified()
    if (recordedAt == 0L) return false
    if (clock() - recordedAt >= missingTtlMillis) {
      runCatching { marker.delete() }
      return false
    }
    knownMissingHits.increment()
    return true
  }

  /** Remember that the branch answered "not found" for [key]; see [knownMissing]. Best-effort. */
  fun recordMissing(key: String) {
    if (missingTtlMillis <= 0) return
    val marker = File(missingDir, sha256Hex(key.toByteArray()))
    writeAtomically(marker, ByteArray(0))
    // Stamped from [clock] so expiry uses the same clock as [knownMissing].
    runCatching { marker.setLastModified(clock()) }
  }

  /**
   * Compare what this pool would serve for [key] against [fresh] branch bytes and drop the entry on
   * mismatch.
   *
   * Blobs are verified against their own name on read, so corruption can't be served; but a key
   * filed against the wrong content would pass every check. This is the only thing that would
   * notice. A report, not a gate: sampled, off the request path, and a mismatch makes [mismatched]
   * non-zero.
   */
  fun audit(key: String, fresh: ByteArray): AuditResult {
    val held = read(key) ?: return AuditResult.NOT_CACHED
    audited.increment()
    if (held.contentEquals(fresh)) return AuditResult.MATCHED
    mismatched.increment()
    recordFailure("audit: cached bytes for a key did not match the branch — entry dropped")
    runCatching { File(keysDir, sha256Hex(key.toByteArray())).delete() }
    return AuditResult.MISMATCHED
  }

  /** What [audit] established about one key. */
  enum class AuditResult {
    /** Nothing cached under that key — the audit had nothing to check. */
    NOT_CACHED,
    /** What the pool holds is what the branch serves. */
    MATCHED,
    /** They differ. The entry was dropped; something is wrong upstream of the blob. */
    MISMATCHED,
  }

  /**
   * Drop everything this pool holds, returning what is left. Whole-pool because blobs are
   * deduplicated across systems and have no owner; per-system partitioning would lose that. Safe at
   * any time: everything is re-fetchable and open readers keep their files.
   */
  fun clear(): CatalogBlobPoolSnapshot {
    // Only blobs count as evictions; pointers may share a blob and would inflate the count.
    for (blob in contentDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      if (runCatching { blob.delete() }.getOrDefault(false)) evicted.increment()
    }
    for (pointer in keysDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      runCatching { pointer.delete() }
    }
    for (marker in missingDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      runCatching { marker.delete() }
    }
    // Scratch too: a killed producer leaves bundle-sized files in `tmp/` that no census counts. A
    // live writer losing its scratch just misses once.
    for (scratch in File(root, TEMP_DIR).listFiles()?.filter { it.isFile }.orEmpty()) {
      runCatching { scratch.delete() }
    }
    return census()
  }

  /**
   * Reclaim blobs oldest-touched first until under [maxBytes], sparing anything younger than
   * [graceMillis], then drop dangling pointers. Always safe; run after the catalog pass, where the
   * census is worth paying for.
   */
  fun sweep(): CatalogBlobPoolSnapshot {
    val now = clock()
    val blobs =
      contentDir.listFiles()?.filter { it.isFile }.orEmpty().sortedBy { it.lastModified() }
    var total = blobs.sumOf { it.length() }
    for (blob in blobs) {
      if (total <= maxBytes) break
      if (now - blob.lastModified() < graceMillis) continue
      val size = blob.length()
      if (runCatching { blob.delete() }.getOrDefault(false)) {
        total -= size
        evicted.increment()
      }
    }
    for (pointer in keysDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      if (resolve(pointer, verify = false) == null) runCatching { pointer.delete() }
    }
    // Expired misses. Empty files, so they cost no bytes against [maxBytes], only directory
    // entries — and an expired one is never trusted again anyway.
    for (marker in missingDir.listFiles()?.filter { it.isFile }.orEmpty()) {
      if (now - marker.lastModified() >= missingTtlMillis) runCatching { marker.delete() }
    }
    // Abandoned scratch, but only past the grace window, since writers may be live.
    for (scratch in File(root, TEMP_DIR).listFiles()?.filter { it.isFile }.orEmpty()) {
      if (now - scratch.lastModified() >= graceMillis) runCatching { scratch.delete() }
    }
    return census()
  }

  /**
   * Re-measure occupancy and publish it; only called from paths already walking the directory
   * ([knownBlobs]).
   */
  private fun census(): CatalogBlobPoolSnapshot {
    val blobs = contentDir.listFiles()?.filter { it.isFile }.orEmpty()
    knownBlobs.set(blobs.size)
    knownBytes.set(blobs.sumOf { it.length() })
    return snapshot()
  }

  /** Counters and the last published occupancy. Cheap enough for a polled status endpoint. */
  fun snapshot(): CatalogBlobPoolSnapshot {
    return CatalogBlobPoolSnapshot(
      blobs = knownBlobs.get(),
      bytes = knownBytes.get(),
      maxBytes = maxBytes,
      persistenceConfigured = persistenceConfigured,
      adopted = adopted,
      hits = hits.get(),
      misses = misses.get(),
      writes = writes.get(),
      writeFailures = writeFailures.get(),
      evicted = evicted.get(),
      corrupt = corrupt.get(),
      audited = audited.get(),
      mismatched = mismatched.get(),
      knownMissingHits = knownMissingHits.get(),
      lastFailure = lastFailure,
    )
  }

  /** The file a blob with this digest occupies, whether or not it exists. Visible for tests. */
  fun contentFile(sha256: String): File = File(contentDir, sha256)

  // ---------------------------------------------------------------------------------------------

  /** [blob] if it exists and hashes back to [sha], else null — dropping it when it does not. */
  private fun readVerified(blob: File, sha: String, size: Long = -1): File? {
    if (!blob.isFile) return null
    if (size >= 0 && blob.length() != size) {
      dropCorrupt(blob, "size")
      return null
    }
    if (sha256Hex(blob) != sha) {
      dropCorrupt(blob, "digest")
      return null
    }
    touch(blob)
    return blob
  }

  /** The blob [pointer] names, verified unless the caller only wants to know it is still there. */
  private fun resolve(pointer: File, verify: Boolean = true): File? {
    val sha = runCatching { pointer.readText().trim() }.getOrNull()?.takeIf(::isSha) ?: return null
    val blob = File(contentDir, sha)
    if (!verify) return blob.takeIf { it.isFile }
    return readVerified(blob, sha)
  }

  private fun dropCorrupt(blob: File, why: String) {
    corrupt.increment()
    recordFailure("discarded ${blob.name.take(12)}… on $why mismatch")
    val size = runCatching { blob.length() }.getOrDefault(0L)
    if (runCatching { blob.delete() }.getOrDefault(false)) {
      knownBlobs.updateAndGet { (it - 1).coerceAtLeast(0) }
      knownBytes.updateAndGet { (it - size).coerceAtLeast(0L) }
    }
  }

  /**
   * Put a newly published blob on this pool's clock: its mtime came from the scratch file, so
   * stamping makes sweeps and [touch] compare one clock.
   */
  private fun stamp(blob: File) {
    runCatching { blob.setLastModified(clock()) }
  }

  /**
   * Keep a blob being read away from the sweeper, re-stamping at most once per
   * [TOUCH_INTERVAL_MILLIS] so request-path hits don't each write metadata.
   */
  private fun touch(blob: File) {
    val now = clock()
    val recorded = runCatching { blob.lastModified() }.getOrDefault(0L)
    if (now - recorded in 0 until TOUCH_INTERVAL_MILLIS) return
    runCatching { blob.setLastModified(now) }
  }

  private fun store(bytes: ByteArray, sha: String): File? {
    val scratch = temp("store") ?: return null
    return runCatching { scratch.writeBytes(bytes) }
      .fold(
        onSuccess = { adopt(scratch, sha) },
        onFailure = {
          scratch.delete()
          recordFailure("could not stage a blob: ${it.message}")
          null
        },
      )
  }

  /** Move [scratch] into `content/<sha>`, or drop it when another writer got there first. */
  private fun adopt(scratch: File, sha: String): File? {
    val blob = File(contentDir, sha)
    if (!ensureDir(contentDir)) {
      scratch.delete()
      return null
    }
    // A blob already there is by definition these bytes — the name is their digest — so the race
    // has no loser worth reporting: drop the duplicate and read what is already published.
    if (blob.isFile) {
      scratch.delete()
      stamp(blob)
      return blob
    }
    val moved = runCatching {
      java.nio.file.Files.move(
        scratch.toPath(),
        blob.toPath(),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
      )
      true
    }
      .getOrElse { runCatching { scratch.renameTo(blob) }.getOrDefault(false) }
    if (!moved) {
      scratch.delete()
      // Another writer landing the identical bytes first is a success, not a failure.
      if (blob.isFile) return blob
      writeFailures.increment()
      recordFailure("could not publish a blob into $contentDir")
      return null
    }
    writes.increment()
    // Advance the published occupancy so a write is visible before the next sweep re-measures.
    knownBlobs.incrementAndGet()
    knownBytes.addAndGet(runCatching { blob.length() }.getOrDefault(0L))
    // Stamped from the same clock every hit reads, so "least recently used" is one notion rather
    // than a mix of the pool's clock and whatever mtime the move happened to preserve.
    stamp(blob)
    return blob
  }

  private fun writeAtomically(target: File, bytes: ByteArray) {
    if (!ensureDir(target.parentFile)) return
    val scratch = temp("pointer") ?: return
    runCatching {
      scratch.writeBytes(bytes)
      java.nio.file.Files.move(
        scratch.toPath(),
        target.toPath(),
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      )
    }
      .onFailure {
        scratch.delete()
        recordFailure("could not write a key pointer: ${it.message}")
      }
  }

  /** A scratch file in the pool's own temp dir, so every publish is a same-filesystem move. */
  private fun temp(what: String): File? {
    val dir = File(root, TEMP_DIR)
    if (!ensureDir(dir)) return null
    return File(dir, "$what-$writerId-${tempSequence.incrementAndGet()}")
  }

  private fun ensureDir(dir: File?): Boolean {
    if (dir == null) return false
    if (dir.isDirectory) return true
    if (runCatching { dir.mkdirs() }.getOrDefault(false) || dir.isDirectory) return true
    writeFailures.increment()
    recordFailure("could not create $dir")
    return false
  }

  private fun recordFailure(reason: String) {
    lastFailure = reason.take(MAX_REASON_CHARS)
  }

  private fun AtomicLong.increment() {
    incrementAndGet()
  }

  companion object {
    const val CONTENT_DIR: String = "content"
    const val KEYS_DIR: String = "keys"
    const val TEMP_DIR: String = "tmp"
    const val MISSING_DIR: String = "missing"

    /**
     * A day: restarts and refreshes on the same revision stop re-asking; a wrong 404 self-heals.
     */
    const val DEFAULT_MISSING_TTL_MILLIS: Long = 24L * 60 * 60 * 1000

    /**
     * Ceiling for the whole pool, sized for a couple of dozen catalogs whose ~100 MB bundles (plus
     * previous revisions until swept) dominate.
     */
    const val DEFAULT_MAX_BYTES: Long = 8L * 1024 * 1024 * 1024

    /** Long enough to cover a rollout's readiness window, short enough to reclaim the same day. */
    const val DEFAULT_SWEEP_GRACE_MILLIS: Long = 60L * 60 * 1000

    /**
     * How stale a blob's time must be before a hit re-stamps it ([touch]); well under the grace
     * window, so regularly read blobs never age into eviction.
     */
    const val TOUCH_INTERVAL_MILLIS: Long = 5L * 60 * 1000

    const val MAX_REASON_CHARS: Int = 200

    private const val BUFFER_BYTES = 1 shl 16

    private fun isSha(value: String): Boolean =
      value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).hex()

    /**
     * Streamed rather than `readBytes()` — these blobs run to 100 MB and are hashed on every read.
     */
    fun sha256Hex(file: File): String? = runCatching {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          digest.update(buffer, 0, read)
        }
      }
      digest.digest().hex()
    }
      .getOrNull()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
  }
}
