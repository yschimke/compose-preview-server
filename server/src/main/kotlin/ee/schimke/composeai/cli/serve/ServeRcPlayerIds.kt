package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * The Remote Compose player ids this server **speaks**: what the viewer's renderer combo offers,
 * what `data-rc-default` / `data-rc-baked-player` report, and what a link this server mints puts in
 * `?rcPlayer=`.
 *
 * Each id names the implementation that draws the pixels:
 *
 * |id                 |implementation                                     |where                                     |
 * |-------------------|---------------------------------------------------|------------------------------------------|
 * |`androidx-view`    |AndroidX `remote-player-view` `RemoteComposePlayer`|daemon, [RemoteComposePlayerKind.VIEW]    |
 * |`androidx-embedded`|vendored AndroidX embedded player                  |daemon, [RemoteComposePlayerKind.EMBEDDED]|
 * |`cmp-android`      |the CMP player (`rc-player-compose`) on Android    |daemon, by `playerId`                     |
 * |`cmp-jvm`          |the CMP player on the desktop JVM                  |this server's `rc-render-jvm` subprocess  |
 * |`cmp-wasm`         |the CMP player compiled to Wasm                    |browser                                   |
 * |`camaelon-js`      |the vendored TypeScript player                     |browser                                   |
 *
 * `cmp-android` used to mean the AndroidX embedded player; it now means the CMP player on Android,
 * and a request naming it reaches the daemon as a registered `playerId` rather than as the embedded
 * built-in. The bare `cmp` is retired.
 *
 * Two vocabularies are deliberately kept apart:
 * * A `?rcPlayer=` **request** ([normalizeRequest]) accepts the older spellings that are not
 *   ambiguous — `java` / `view`, `embedded`, `js`, `rcplayer-jvm`, `rcplayer-wasm` — and never
 *   reinterprets `cmp-android`: in a request it means what it says now.
 * * A **capture record** — a `capturePlayer` sidecar, an older server's baked-player report
 *   ([fromCaptureRecord]) — was written by daemons that spelled the embedded player `cmp-android`
 *   and the view player `java`, so there and only there those map to `androidx-embedded` and
 *   `androidx-view`.
 *
 * compose-ai-tools' [RcPlayerBackend] is the backend universe this server renders through, and on
 * the release this build pins its [RcPlayerBackend.wire] ids are still the old ones (`js`, `java`,
 * `cmp-android` for the EMBEDDED backend). [of] maps a backend onto its canonical id by what it
 * draws (its daemon player kind) rather than by that spelling, so the mapping holds on either side
 * of the compose-ai-tools bump that renames them.
 */
internal object ServeRcPlayerIds {
  const val ANDROIDX_VIEW: String = "androidx-view"
  const val ANDROIDX_EMBEDDED: String = "androidx-embedded"
  const val CMP_ANDROID: String = "cmp-android"
  const val CMP_JVM: String = "cmp-jvm"
  const val CMP_WASM: String = "cmp-wasm"
  const val CAMAELON_JS: String = "camaelon-js"

  /** A player the viewer can offer: its canonical id and the label its combo option carries. */
  data class Player(val id: String, val label: String, val serverSide: Boolean)

  /**
   * Every player the viewer lists, in display order — the browser players, then the daemon ones,
   * then the desktop subprocess. A player the host does not report is still listed, disabled.
   */
  val UNIVERSE: List<Player> =
    listOf(
      Player(CAMAELON_JS, "Camaelon JS", serverSide = false),
      Player(CMP_WASM, "CMP Wasm", serverSide = false),
      Player(ANDROIDX_VIEW, "AndroidX View", serverSide = true),
      Player(ANDROIDX_EMBEDDED, "AndroidX Embedded", serverSide = true),
      Player(CMP_ANDROID, "CMP Android", serverSide = true),
      Player(CMP_JVM, "CMP JVM", serverSide = true),
    )

  private val CANONICAL: Set<String> = UNIVERSE.mapTo(mutableSetOf()) { it.id }

  /**
   * The canonical id of a `?rcPlayer=` value: a canonical id as itself, an unambiguous legacy
   * spelling as the player it always named, anything else (a registered daemon player id, or
   * nothing this server knows) lower-cased and otherwise untouched. Never remaps `cmp-android`.
   */
  fun normalizeRequest(raw: String): String =
    when (val v = raw.trim().lowercase()) {
      "java",
      "view" -> ANDROIDX_VIEW
      "embedded" -> ANDROIDX_EMBEDDED
      "js" -> CAMAELON_JS
      "rcplayer-jvm" -> CMP_JVM
      "rcplayer-wasm" -> CMP_WASM
      else -> v
    }

  /**
   * The canonical id of a capture record's player — a `capturePlayer` sidecar or an older server's
   * baked-player report — or null when it names nothing this server knows. Older daemons wrote
   * `cmp-android` for the embedded player and `java` for the view player.
   */
  fun fromCaptureRecord(raw: String?): String? {
    val v = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return when (v) {
      "cmp-android" -> ANDROIDX_EMBEDDED
      else -> normalizeRequest(v).takeIf { it in CANONICAL }
    }
  }

  /** The daemon built-in a canonical id renders through, or null for every other lane. */
  fun playerKindOf(id: String?): RemoteComposePlayerKind? =
    when (id) {
      ANDROIDX_VIEW -> RemoteComposePlayerKind.VIEW
      ANDROIDX_EMBEDDED -> RemoteComposePlayerKind.EMBEDDED
      else -> null
    }

  /** The canonical id of a daemon built-in. */
  fun ofKind(kind: RemoteComposePlayerKind): String =
    when (kind) {
      RemoteComposePlayerKind.VIEW -> ANDROIDX_VIEW
      RemoteComposePlayerKind.EMBEDDED -> ANDROIDX_EMBEDDED
    }

  /**
   * The canonical id of a compose-ai-tools [RcPlayerBackend]. Keyed on the daemon player kind
   * first, because the pinned release still spells the EMBEDDED backend `cmp-android`.
   */
  fun of(backend: RcPlayerBackend): String =
    backend.playerKind?.let(::ofKind) ?: normalizeRequest(backend.wire)

  /** Whether a `?rcPlayer=` value selects the desktop-JVM subprocess lane. */
  fun isCmpJvm(raw: String?): Boolean = raw != null && normalizeRequest(raw) == CMP_JVM

  /**
   * [ServeOverrides.parse], with `rcPlayer` read in this server's vocabulary.
   *
   * The value is normalised first ([normalizeRequest]), so a legacy `java` or `embedded` link still
   * selects the player it always did. `cmp-android` is then forwarded to the daemon as a `playerId`
   * — the CMP player on Android, registered by the daemon — rather than as the EMBEDDED built-in
   * the pinned compose-ai-tools release still maps that spelling to.
   */
  fun parseOverrides(
    params: Map<String, String>,
    knobKinds: Map<String, String> = emptyMap(),
    declaredThemeFqns: Set<String>? = null,
  ): OverrideParse {
    val raw = params["rcPlayer"]?.takeIf { it.isNotBlank() }
    val player = raw?.let { normalizeRequest(it) }
    val normalized =
      if (player == null || player == raw) params else params + ("rcPlayer" to player)
    val parsed = ServeOverrides.parse(normalized, knobKinds, declaredThemeFqns)
    if (player != CMP_ANDROID || parsed !is OverrideParse.Ok) return parsed
    return OverrideParse.Ok(asCmpAndroid(parsed.overrides))
  }

  private fun asCmpAndroid(o: PreviewOverrides): PreviewOverrides {
    val rc = o.remoteCompose ?: return o
    if (rc.playerId == CMP_ANDROID && rc.player == null) return o
    return o.copy(
      remoteCompose =
        rc
          .newBuilder()
          .also {
            it.player = null
            it.playerId = CMP_ANDROID
          }
          .build()
    )
  }
}
