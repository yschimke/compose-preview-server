package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
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
 * compose-ai-tools' [RcPlayerBackend] is the backend universe this server renders through. Since
 * compose-ai-tools 2.32 its [RcPlayerBackend.wire] ids are these canonical ones; up to 2.31 they
 * were the old spellings (`js`, `java`, `cmp-android` for the EMBEDDED backend). [of] maps a
 * backend onto its canonical id by what it draws (its daemon player kind) rather than by that
 * spelling, so the mapping does not depend on which side of that rename a build is on.
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
   * first, because compose-ai-tools up to 2.31 spelled the EMBEDDED backend `cmp-android`.
   */
  fun of(backend: RcPlayerBackend): String =
    backend.playerKind?.let(::ofKind) ?: normalizeRequest(backend.wire)

  /**
   * The order the viewer opens a Remote Compose preview on when no preference is configured:
   * `androidx-embedded`, else `androidx-view`, else the client `camaelon-js` canvas. Why embedded
   * leads is written at [defaultPlayer].
   */
  val DEFAULT_ORDER: List<String> = listOf(ANDROIDX_EMBEDDED, ANDROIDX_VIEW, CAMAELON_JS)

  /**
   * The player the viewer opens a Remote Compose preview on (`data-rc-default`), out of the
   * [enabled] players this preview offers.
   *
   * [preferred] is the operator's choice (`serve --rc-default-player`), already normalised by
   * [parsePreferredPlayer]. It wins **only when this preview enables it**: a preferred player the
   * preview cannot run — `cmp-android` on a catalog whose bundle does not carry the CMP player, or
   * any daemon lane while the daemon is absent — falls back through [DEFAULT_ORDER] exactly as an
   * unset preference does, so naming a player can never open a page on a disabled option.
   *
   * Why the unconfigured default is the server-side `androidx-embedded` player: the payoff is the
   * data tier rather than the pixels (#3936). `androidx-view` is `AndroidView { RemoteComposePlayer
   * }`, so a whole document reaches Compose as one interop leaf: `compose/figma-svg` exports it as
   * a single raster wearing an `.svg` extension, and the semantics tree describes a black box. The
   * embedded player emits real Compose nodes, so the same document exports editable geometry and
   * describes the card. The two lanes were measured over all 164 documents of the homeassistant
   * catalog before this moved (`renders/rc-embedded-lane-ab/`): 34 byte-identical, and the residual
   * is overwhelmingly text rasterization — Skia and the Android canvas hint glyphs differently,
   * which no amount of player work removes. `?rcPlayer=androidx-view` still selects the old lane
   * for anything that needs it.
   *
   * `cmp-android` (the CMP player, `rc-player-compose`, run by the daemon) is the intended next
   * default, and making it one is a configuration change rather than a code change: set the
   * preference, and every preview whose catalog can run it ([carriesCmpAndroidPlayer]) opens on it
   * while the rest keep this order.
   */
  fun defaultPlayer(enabled: Collection<String>, preferred: String? = null): String {
    if (preferred != null && preferred in enabled) return preferred
    return DEFAULT_ORDER.firstOrNull { it in enabled } ?: enabled.firstOrNull().orEmpty()
  }

  /**
   * The canonical id of a configured default player, or null for "no preference" — blank, or a
   * value naming no player in [UNIVERSE] (reported through [onInvalid] rather than failing the
   * server: an unknown preference costs only the configured default, never a page).
   */
  fun parsePreferredPlayer(raw: String?, onInvalid: (String) -> Unit = {}): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = normalizeRequest(value)
    if (id in CANONICAL) return id
    onInvalid(value)
    return null
  }

  /** The Maven group of the CMP Remote Compose player (yschimke/rc-players). */
  const val CMP_PLAYER_GROUP: String = "ee.schimke.composeai"

  /**
   * The CMP player's artifact. A Kotlin Multiplatform library, so an Android bundle's resolved
   * classpath names its Android variant (`rc-player-compose-android`, as `material3-android` and
   * every other KMP dependency appear there); the root coordinate is accepted too.
   */
  const val CMP_PLAYER_ARTIFACT: String = "rc-player-compose"

  private val CMP_PLAYER_ARTIFACTS: Set<String> =
    setOf(CMP_PLAYER_ARTIFACT, "$CMP_PLAYER_ARTIFACT-android")

  /**
   * Whether a bundle manifest's resolved [classpath] carries the CMP player, any version — the half
   * of `cmp-android`'s capability the daemon cannot answer. The daemon registers the player id
   * whatever it was launched with; whether the player's classes are actually loadable is a fact
   * about the catalog's bundle, and without them a `cmp-android` render fails inside the daemon
   * with a `RemoteComposeLinkageException` (`RcComposePlayerKt` class not found) rather than
   * declining up front.
   */
  fun carriesCmpAndroidPlayer(classpath: List<BundleReader.ClasspathEntry>): Boolean =
    classpath.any {
      it is BundleReader.ClasspathEntry.Maven &&
        it.group == CMP_PLAYER_GROUP &&
        it.artifact in CMP_PLAYER_ARTIFACTS
    }

  /**
   * [carriesCmpAndroidPlayer] for a bundle on disk. An unreadable bundle — or no manifest at all —
   * answers false: the lane is offered only on positive evidence, never guessed.
   */
  fun bundleCarriesCmpAndroidPlayer(bundleFile: java.io.File): Boolean = runCatching {
    carriesCmpAndroidPlayer(BundleReader.readMetadata(bundleFile).manifest.classpath)
  }
    .getOrDefault(false)

  /** Whether a `?rcPlayer=` value selects the desktop-JVM subprocess lane. */
  fun isCmpJvm(raw: String?): Boolean = raw != null && normalizeRequest(raw) == CMP_JVM

  /**
   * [ServeOverrides.parse], with `rcPlayer` read in this server's vocabulary.
   *
   * The value is normalised first ([normalizeRequest]), so a legacy `java` or `embedded` link still
   * selects the player it always did. `cmp-android` is then forwarded to the daemon as a `playerId`
   * — the CMP player on Android, registered by the daemon — rather than as the EMBEDDED built-in
   * compose-ai-tools up to 2.31 mapped that spelling to. Since 2.32 its own parser forwards it the
   * same way, so this rewrite is a no-op there and stays as the guarantee.
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
