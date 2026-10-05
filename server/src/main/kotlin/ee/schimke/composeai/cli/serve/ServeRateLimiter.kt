package ee.schimke.composeai.cli.serve

/**
 * A **per-caller** budget for an expensive serve lane: a token bucket for the request *rate* plus a
 * counter for *concurrent* work, keyed by whoever is asking. Host-wide caps (compile slots, seats)
 * do not stop one caller from holding every slot; this provides fair sharing.
 *
 * The bucket holds [permitsPerWindow] tokens refilling continuously over [windowSeconds], so a full
 * burst is allowed; refill is computed lazily on read. [maxConcurrent] bounds what one caller holds
 * at once, released through the idempotent [Decision.Admitted.release].
 *
 * Keys may be attacker-chosen (client addresses), so the map is bounded at [maxKeys]: a new key
 * first sweeps entries indistinguishable from fresh ones (idle, full bucket), and is refused if
 * that frees nothing.
 */
class ServeRateLimiter(
  /**
   * Tokens per caller per [windowSeconds], and the bucket's capacity (so a full burst is allowed).
   */
  private val permitsPerWindow: Int,
  private val windowSeconds: Long = 60,
  /** How much work one caller may hold at once. */
  private val maxConcurrent: Int = 1,
  /** Distinct callers tracked before new ones are refused; see the class KDoc. */
  private val maxKeys: Int = DEFAULT_MAX_KEYS,
  private val clock: () -> Long = System::currentTimeMillis,
) {

  /** The outcome of asking for permission to start one unit of work. */
  sealed interface Decision {
    /**
     * Go ahead. [release] MUST be called when the work finishes (including on failure); it is
     * idempotent, so a `finally` that runs twice cannot hand the caller back a permit they no
     * longer hold.
     */
    class Admitted(private val onRelease: () -> Unit) : Decision {
      private var released = false

      fun release() {
        synchronized(this) {
          if (released) return
          released = true
        }
        onRelease()
      }
    }

    /** Refused: [retryAfterSeconds] is exact for the rate bound, a nudge for concurrency. */
    data class Throttled(val retryAfterSeconds: Long, val reason: String) : Decision
  }

  private class Bucket(var tokens: Double, var lastRefillMillis: Long, var inFlight: Int)

  private val buckets = HashMap<String, Bucket>()

  private val refillPerMilli: Double = permitsPerWindow.toDouble() / (windowSeconds * 1000.0)

  /**
   * Take one permit for [key], or say why not. Never blocks: parking turns a rate problem into a
   * thread problem.
   */
  fun tryAcquire(key: String): Decision {
    synchronized(this) {
      val now = clock()
      val bucket =
        buckets[key]
          ?: run {
            if (buckets.size >= maxKeys) {
              sweepIdle(now)
              if (buckets.size >= maxKeys) {
                return Decision.Throttled(
                  retryAfterSeconds = windowSeconds,
                  reason =
                    "the server is tracking $maxKeys active callers already; try again shortly",
                )
              }
            }
            Bucket(tokens = permitsPerWindow.toDouble(), lastRefillMillis = now, inFlight = 0)
              .also { buckets[key] = it }
          }

      refill(bucket, now)

      if (bucket.inFlight >= maxConcurrent) {
        return Decision.Throttled(
          retryAfterSeconds = CONCURRENCY_RETRY_AFTER_SECONDS,
          reason =
            "you already have ${bucket.inFlight} request(s) in flight (limit $maxConcurrent); " +
              "wait for one to finish",
        )
      }
      if (bucket.tokens < 1.0) {
        // When the next whole token lands, rounded up so a "retry after 0" can never bounce.
        val waitMillis = ((1.0 - bucket.tokens) / refillPerMilli).toLong()
        return Decision.Throttled(
          retryAfterSeconds = ((waitMillis + 999) / 1000).coerceAtLeast(1),
          reason = "rate limit: $permitsPerWindow request(s) per ${windowSeconds}s per caller",
        )
      }

      bucket.tokens -= 1.0
      bucket.inFlight++
      return Decision.Admitted { release(key) }
    }
  }

  /** Callers currently holding at least one permit — for `/status.json`. */
  fun activeCallers(): Int = synchronized(this) { buckets.count { it.value.inFlight > 0 } }

  /** Distinct callers tracked right now, against [maxKeys]. */
  fun trackedCallers(): Int = synchronized(this) { buckets.size }

  private fun release(key: String) {
    synchronized(this) {
      val bucket = buckets[key] ?: return
      if (bucket.inFlight > 0) bucket.inFlight--
    }
  }

  private fun refill(bucket: Bucket, now: Long) {
    val elapsed = now - bucket.lastRefillMillis
    if (elapsed <= 0) return
    bucket.lastRefillMillis = now
    bucket.tokens =
      (bucket.tokens + elapsed * refillPerMilli).coerceAtMost(permitsPerWindow.toDouble())
  }

  /**
   * Drop every entry that carries no state a fresh one wouldn't: nothing in flight, bucket full.
   * Recreating such an entry gives its caller exactly what they had, so this is free to do.
   */
  private fun sweepIdle(now: Long) {
    val iterator = buckets.entries.iterator()
    while (iterator.hasNext()) {
      val bucket = iterator.next().value
      refill(bucket, now)
      if (bucket.inFlight == 0 && bucket.tokens >= permitsPerWindow) iterator.remove()
    }
  }

  companion object {
    /** Well above a real audience; a spray of forged keys costs kilobytes. */
    const val DEFAULT_MAX_KEYS = 4096

    /** What a concurrency refusal advises; a nudge, since no exact answer exists. */
    const val CONCURRENCY_RETRY_AFTER_SECONDS = 5L
  }
}
