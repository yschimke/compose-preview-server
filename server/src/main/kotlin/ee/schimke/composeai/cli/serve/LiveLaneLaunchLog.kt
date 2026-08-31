package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers **why** a catalog's live render lane failed to come up, so the reason reaches
 * `/status.json` and the viewer's banner instead of only the server's stderr.
 *
 * Why this exists. [ServeBundleDaemon.materialize] already explains every way a live lane can fail
 * — a sidecar directory that isn't there, a bundle whose zip won't extract, a backend no daemon
 * serves, a `previews.json` carrying no previews — but it explains it to an `onLog` sink that
 * defaults to `System.err`. What a visitor and an operator see is
 * [ServeDegradation.liveBundleUnavailable]'s one fixed sentence: *the live bundle daemon could not
 * be started*. Every distinct cause collapses into that sentence, so the catalog looks identically
 * broken whichever thing is wrong, and finding out which needs a shell on the box.
 *
 * That gap is not hypothetical. preview.coo.ee served every `backend: "desktop"` catalog as baked
 * PNGs because the image carried no `lib-daemon-desktop/`; the daemon launch logged exactly that,
 * and the degradation said only "could not be started". Diagnosing it took a registry manifest diff
 * and two local server runs against the released artifacts — all to recover a line the server had
 * already written and thrown away.
 *
 * The shape is deliberately small. [sink] wraps the launch's `onLog` (still printing, so nothing
 * that read the log loses anything) and keeps the **last** line of the attempt; a materialize
 * failure logs its cause immediately before returning null, so the last line at that moment IS the
 * cause. A launch that gets past materialize and then fails to open records its own reason through
 * [record]. A launch that succeeds calls [clear], so a stale info line — the Skiko pairing repair
 * notice, say — can never be reported later as a failure.
 *
 * Bounded by [maxSystems] against a box serving an unbounded catalog registry: once full it keeps
 * updating the systems it already tracks and stops taking new ones, which is the right trade for a
 * diagnostic (the catalogs that keep failing are the ones already in the map).
 *
 * Thread-safe: catalog loads run concurrently at startup, each with its own system key.
 */
class LiveLaneLaunchLog(private val maxSystems: Int = DEFAULT_MAX_SYSTEMS) {
  private val lastFailure = ConcurrentHashMap<String, String>()

  /**
   * The `onLog` sink for a live-lane launch attempt on [system]. Prints exactly as the default sink
   * does and remembers the line, so the caller needs no second call on the failure path.
   *
   * [system] is the catalog id the store looks the reason up by; the messages themselves carry the
   * daemon's own label (`catalog <system>:<module>: …` for a module bundle), so a multi-module
   * failure still names the module that failed.
   */
  fun sink(system: String): (String) -> Unit = { line ->
    System.err.println("[serve bundle] $line")
    remember(system, line)
  }

  /**
   * Record a failure the launch itself didn't log — the daemon materialized but the render host
   * refused to open it, which is a distinct outcome from anything `materialize` reports.
   */
  fun record(system: String, reason: String) = remember(system, reason)

  /**
   * Forget [system]'s last line. Called when its lane comes up, so success leaves nothing behind.
   */
  fun clear(system: String) {
    lastFailure.remove(system)
  }

  /** The last line [system]'s failed launch produced, or null when it has none recorded. */
  fun lastReason(system: String): String? = lastFailure[system]

  private fun remember(system: String, line: String) {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return
    // `computeIfPresent` first so a tracked system keeps updating after the map fills; only a NEW
    // system is turned away at the cap.
    if (lastFailure.computeIfPresent(system) { _, _ -> trimmed } != null) return
    if (lastFailure.size < maxSystems) lastFailure[system] = trimmed
  }

  companion object {
    /** Comfortably above any real catalog set (preview.coo.ee serves 23). */
    const val DEFAULT_MAX_SYSTEMS: Int = 128
  }
}
