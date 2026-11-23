package ee.schimke.composeai.cli.serve

import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * [delegate] with its answers kept by content, so the same content is compiled once.
 *
 * A compiler here is a third party's service on this server's one credential (Kobento's `convert`),
 * and the same content arrives again and again: every editor that opens a catalog's palette asks
 * for the same per-component thumbnail documents, which are identical for every visitor. Uncached,
 * each palette opened was a call per server-compiled component (21 for Kobento's).
 *
 * - Keyed by the content's SHA-256: a design that changed is a different key, never a stale hit.
 * - Concurrent requests for one key share the one call in flight.
 * - A failure is not kept, so a refused or missing credential is asked again next time.
 * - At most [maxEntries], the least recently used leaving first, each for at most [ttlMillis], so a
 *   change on the service's side (a component library update) is picked up within that time.
 */
internal class CachingDocumentCompiler(
  private val delegate: DocumentCompiler,
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
  private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Runs between a miss and claiming the in-flight slot: where a test lands a competing request.
   */
  private val afterMiss: () -> Unit = {},
) : DocumentCompiler {
  private class Entry(val bytes: ByteArray, val at: Long)

  private val entries =
    object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
        size > maxEntries
    }
  private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray>>()

  override fun compile(content: String): ByteArray {
    val key = sha256(content)
    cached(key)?.let {
      return it
    }
    afterMiss()
    val mine = CompletableFuture<ByteArray>()
    val running = inFlight.putIfAbsent(key, mine)
    if (running != null) return join(running).copyOf()
    try {
      // Again, now the slot is ours: another request may have compiled and cached this key between
      // the miss above and taking the slot, and must not be followed by a second call.
      val bytes =
        cached(key)
          ?: delegate.compile(content).also { compiled ->
            synchronized(entries) { entries[key] = Entry(compiled, clock()) }
          }
      mine.complete(bytes)
      return bytes.copyOf()
    } catch (failure: Throwable) {
      mine.completeExceptionally(failure)
      throw failure
    } finally {
      inFlight.remove(key, mine)
    }
  }

  /** A copy of [key]'s compiled bytes while they are fresh; null on a miss or an expired entry. */
  private fun cached(key: String): ByteArray? {
    val now = clock()
    synchronized(entries) {
      val entry = entries[key] ?: return null
      if (now - entry.at < ttlMillis) return entry.bytes.copyOf()
      entries.remove(key)
      return null
    }
  }

  private fun join(future: CompletableFuture<ByteArray>): ByteArray =
    try {
      future.join()
    } catch (wrapped: CompletionException) {
      throw wrapped.cause ?: wrapped
    }

  private fun sha256(content: String): String =
    MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).joinToString(
      "",
    ) {
      "%02x".format(it)
    }

  companion object {
    const val DEFAULT_MAX_ENTRIES: Int = 512
    const val DEFAULT_TTL_MILLIS: Long = 6 * 60 * 60 * 1000L
  }
}
