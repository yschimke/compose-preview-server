package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * What a live daemon answered about its optional surfaces: the flags a preview page reads to decide
 * which controls to draw.
 */
internal data class ServeLiveCapabilities(
  val svgExport: Boolean,
  val scrollExport: Boolean,
  val a11yOverlay: Boolean,
  val gestures: Boolean,
  val remoteComposePlayerSelectable: Boolean,
)

/**
 * The last [ServeLiveCapabilities] a daemon of each backend reported, shared by every catalog on
 * this server, so a page can be drawn without waking its own catalog's daemon.
 *
 * Every one of those flags is a question to the daemon (an `extensions/enable` handshake, or the
 * `initialize` capabilities), so answering it opens the session. On preview.coo.ee that made a
 * crawler's single GET of `/{system}/p/{name}` boot a ~1.5 GB Android daemon group and wait 8–21 s
 * for it, for each of a dozen catalogs, and kept each resident for the idle window. What they
 * answer depends on the daemon sidecar and the backend, both the same for every catalog this
 * process serves, so one daemon's answer stands in for its backend's until a catalog's own daemon
 * is up.
 */
class ServeLiveCapabilityMemo {
  private val byBackend = ConcurrentHashMap<String, ServeLiveCapabilities>()

  internal fun get(backend: String): ServeLiveCapabilities? = byBackend[backend]

  internal fun record(backend: String, capabilities: ServeLiveCapabilities) {
    byBackend[backend] = capabilities
  }

  companion object {
    /** The key for a session's backend, from its live-seat weight (an Android daemon weighs 2). */
    internal fun backendFor(liveSeatWeight: Int): String =
      if (liveSeatWeight >= 2) "android" else "desktop"
  }
}
