package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind

/**
 * The Remote Compose player ids this server speaks: what the viewer's renderer combo offers, what
 * `data-rc-default` / `data-rc-baked-player` report, and what minted links put in `?rcPlayer=`.
 *
 * |id                 |implementation                                     |where
 * |
 * |-------------------|---------------------------------------------------|------------------------------------------|
 * |`androidx-view`    |AndroidX `remote-player-view` `RemoteComposePlayer`|daemon,
 * [RemoteComposePlayerKind.VIEW]    |
 * |`androidx-embedded`|vendored AndroidX embedded player                  |daemon,
 * [RemoteComposePlayerKind.EMBEDDED]|
 * |`cmp-android`      |the CMP player (`rc-player-compose`) on Android    |daemon, by `playerId`
 * |
 * |`cmp-jvm`          |the CMP player on the desktop JVM                  |this server's
 * `rc-render-jvm` subprocess  |
 * |`cmp-wasm`         |the CMP player compiled to Wasm                    |browser
 * |
 * |`camaelon-js`      |the vendored TypeScript player                     |browser
 * |
 *
 * Two vocabularies are kept apart. A `?rcPlayer=` request ([normalizeRequest]) accepts unambiguous
 * legacy spellings (`java`/`view`, `embedded`, `js`, `rcplayer-jvm`, `rcplayer-wasm`) and never
 * reinterprets `cmp-android`. A capture record ([fromCaptureRecord]) was written by daemons that
 * spelled the embedded player `cmp-android` and the view player `java`, so only there are those
 * remapped.
 *
 * [of] maps compose-ai-tools' [RcPlayerBackend] by daemon player kind rather than its
 * [RcPlayerBackend.wire] spelling, which changed in 2.32.
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
   * Every player the viewer lists, in display order (browser, daemon, desktop subprocess);
   * unreported ones are listed disabled.
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
   * Canonical id of a `?rcPlayer=` value: canonical ids as-is, unambiguous legacy spellings mapped,
   * anything else lower-cased. Never remaps `cmp-android`.
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
   * Canonical id of a capture record's player, or null when unknown. Older daemons wrote
   * `cmp-android` for embedded and `java` for view.
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
   * Canonical id of an [RcPlayerBackend], keyed on daemon player kind first, since up to 2.31 the
   * EMBEDDED backend was spelled `cmp-android`.
   */
  fun of(backend: RcPlayerBackend): String =
    backend.playerKind?.let(::ofKind) ?: normalizeRequest(backend.wire)

  /**
   * Default order without a configured preference: `androidx-embedded`, `androidx-view`, then
   * `camaelon-js` (see [defaultPlayer]).
   */
  val DEFAULT_ORDER: List<String> = listOf(ANDROIDX_EMBEDDED, ANDROIDX_VIEW, CAMAELON_JS)

  /**
   * The player the viewer opens on (`data-rc-default`) among this preview's [enabled] players.
   * [preferred] (`serve --rc-default-player`, normalised by [parsePreferredPlayer]) wins only when
   * enabled for this preview; otherwise [DEFAULT_ORDER] applies, so a preference never opens a
   * disabled option.
   *
   * Embedded leads by default for the data tier (#3936): `androidx-view` reaches Compose as a
   * single interop leaf (one raster in SVG export, an opaque semantics node), while embedded emits
   * real Compose nodes. Pixel differences between the two are mostly text rasterization.
   * `?rcPlayer=androidx-view` still selects the old lane. Making `cmp-android` the default is just
   * a preference setting; previews whose catalog can't run it ([carriesCmpAndroidPlayer]) keep this
   * order.
   */
  fun defaultPlayer(enabled: Collection<String>, preferred: String? = null): String {
    if (preferred != null && preferred in enabled) return preferred
    return DEFAULT_ORDER.firstOrNull { it in enabled } ?: enabled.firstOrNull().orEmpty()
  }

  /**
   * Canonical id of a configured default player, or null for none: blank, or unknown (reported via
   * [onInvalid] rather than failing the server).
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
   * The CMP player's artifact; as a KMP library an Android classpath names
   * `rc-player-compose-android`, and the root coordinate is accepted too.
   */
  const val CMP_PLAYER_ARTIFACT: String = "rc-player-compose"

  private val CMP_PLAYER_ARTIFACTS: Set<String> =
    setOf(CMP_PLAYER_ARTIFACT, "$CMP_PLAYER_ARTIFACT-android")

  /**
   * Whether a bundle's resolved [classpath] carries the CMP player. The daemon always registers
   * `cmp-android`, but without these classes a render fails with `RemoteComposeLinkageException`
   * rather than declining.
   */
  fun carriesCmpAndroidPlayer(classpath: List<BundleReader.ClasspathEntry>): Boolean =
    classpath.any {
      it is BundleReader.ClasspathEntry.Maven &&
        it.group == CMP_PLAYER_GROUP &&
        it.artifact in CMP_PLAYER_ARTIFACTS
    }

  /**
   * [carriesCmpAndroidPlayer] for a bundle on disk; unreadable or manifest-less answers false
   * (offered only on positive evidence).
   */
  fun bundleCarriesCmpAndroidPlayer(bundleFile: java.io.File): Boolean = runCatching {
    carriesCmpAndroidPlayer(BundleReader.readMetadata(bundleFile).manifest.classpath)
  }
    .getOrDefault(false)

  /** Whether a `?rcPlayer=` value selects the desktop-JVM subprocess lane. */
  fun isCmpJvm(raw: String?): Boolean = raw != null && normalizeRequest(raw) == CMP_JVM

  /**
   * [ServeOverrides.parse] with `rcPlayer` normalised first ([normalizeRequest]), so legacy links
   * keep working, and `cmp-android` forwarded to the daemon as a `playerId` rather than the
   * embedded built-in (a no-op from compose-ai-tools 2.32).
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
