package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Keeps a served bundle's Remote Compose artifacts on **one line** — and says which line, when the
 * bundle and the daemon sidecar each hold half of one.
 *
 * ## The failure this exists to name
 *
 * `androidx.compose.remote:*` is a family whose members are compiled against each other:
 * `remote-player-core`'s `RemoteDocument` reads `RemoteClock.SYSTEM` out of `remote-core`, the
 * `remote-creation*` artifacts write the documents the players read. Two of them from two builds on
 * one classpath is a linkage error waiting for the first render — exactly the shape
 * [SkikoNativePairing] guards for Skiko's bindings/native pair.
 *
 * The serve path mixes lines structurally. A bundle records its own Remote Compose coordinates and
 * [ServeBundleDaemon.shouldPrecedeDaemonSidecar] promotes them ahead of the daemon sidecar (they
 * are `androidx.`), so the artifacts the bundle **carries** win. The sidecar keeps its own pin for
 * everything else — and the sidecar's IR replay connector
 * ([ee.schimke.composeai.daemon.RemoteComposeIrReplay]) links against whatever answers. So a bundle
 * that carries part of the family leaves the rest at the sidecar's version, and the two halves meet
 * inside the first IR replay:
 * ```
 * java.lang.NoSuchFieldError: Class androidx.compose.remote.core.RemoteClock
 *   does not have member field 'androidx.compose.remote.core.RemoteClock SYSTEM'
 *     at androidx.compose.remote.player.core.RemoteDocument.<clinit>(RemoteDocument.java:44)
 * ```
 *
 * — `meshcore-mobile`, whose bundle pins the family at `1.0.0-SNAPSHOT` from an androidx.dev build
 * against a sidecar on released alphas. The [RenderCircuitBreaker] classifies that as fatal (a
 * linkage error no retry can clear, correctly), latches the whole catalog's live lane behind it,
 * and shows the reason as-is: a field name and no cause at all (compose-preview-server#187).
 *
 * ## What this adds
 *
 * [BundleClasspathGaps] explains a coordinate that went **missing** or came back as the **wrong
 * bytes**. This is the third shape: everything resolved, every hash matched, and the classpath
 * still carries two Remote Compose lines because the bundle and the sidecar each supplied part of
 * the family. Nothing in the resolution record can see that — it is a property of the two lists
 * side by side, which is where [skew] reads it.
 *
 * Deliberately narrow, matching [SkikoNativePairing.classpathSkew]'s "leading half-pair" rule. A
 * bundle that carries **every** family artifact the sidecar has shadows the sidecar's copies whole
 * and is coherent whatever its version; a bundle that carries none of them runs entirely on the
 * sidecar's line and is coherent too. Only a bundle carrying *some* of the family at a version that
 * is not the sidecar's mixes the two, and only that is reported.
 *
 * ## Which side wins
 *
 * In that one state [ServeBundleDaemon] also stops promoting the bundle's copies ahead of the
 * sidecar ([isFamilyMember] drops them out of the parent overlay), because the **sidecar** is what
 * links against the family: `RemoteComposeIrReplay` is sidecar code, and a document has no consumer
 * class of its own to keep a version priority for. That is a deliberate exception to
 * [ServeBundleDaemon.shouldPrecedeDaemonSidecar]'s "the catalog's framework versions win" rule, and
 * it costs nothing that rule was protecting: a half-promoted family wins nothing — it links its own
 * half against the sidecar's other half and every render dies. The worst case after demotion is a
 * document the sidecar's player is too old to replay, which fails that render and leaves the lane,
 * the rest of the catalog and the background optimizer pass alive.
 */
internal object RemoteComposePairing {

  /**
   * What every Remote Compose group and package has in common — `androidx.compose.remote`,
   * `androidx.wear.compose.remote` — so one marker identifies both a coordinate's group and the
   * package a linkage error names (the internal `androidx/compose/remote/…` form is dotted first).
   */
  private const val FAMILY_MARKER = "compose.remote"

  /** File name written beside the launch descriptor, mirroring [BundleClasspathGaps]. */
  private const val FILE_NAME = "remote-compose-line.json"

  private val json = Json { ignoreUnknownKeys = true }

  /** One Remote Compose artifact and the version the classpath will load it at. */
  @Serializable data class Member(val artifact: String, val version: String)

  /**
   * What each side of the daemon `-cp` contributes to the family: the artifacts the bundle carries
   * (promoted ahead of the sidecar) and the ones the sidecar ships. Persisted so a trip can be
   * diagnosed without re-deriving a classpath the daemon assembled at materialization.
   */
  @Serializable
  data class Line(
    val bundle: List<Member> = emptyList(),
    val sidecar: List<Member> = emptyList(),
    /**
     * Whether [ServeBundleDaemon] demoted the bundle's copies behind the sidecar to keep the family
     * on one line. Defaulted so a record written before the demotion existed still reads back.
     */
    val demoted: Boolean = false,
  )

  /** Whether [coordinate] belongs to the Remote Compose family at all. */
  fun isFamilyMember(coordinate: BundleReader.ClasspathEntry.Maven): Boolean =
    coordinate.group.contains(FAMILY_MARKER)

  /** The family members among the bundle's **resolved** coordinates. */
  fun bundleMembers(coords: List<BundleReader.ClasspathEntry.Maven>): List<Member> =
    coords.filter { isFamilyMember(it) }.map { Member(it.artifact, it.version) }.distinct()

  /**
   * The family members on the daemon sidecar's own classpath, read off jar filenames.
   *
   * The sidecar is a `:cli:installDist` layout — `lib/<artifact>-<version>.jar` — so the artifact
   * and version are both in the name. Bundle-side artifacts are NOT read this way: a resolved
   * `.aar` reaches the classpath as `extracted/<sha256>/classes.jar`, which carries neither, which
   * is why [bundleMembers] reads the coordinates instead.
   */
  fun sidecarMembers(sidecarClasspath: List<String>): List<Member> =
    sidecarClasspath
      .mapNotNull { path ->
        val filename = path.replace('\\', '/').substringAfterLast('/')
        REMOTE_ARTIFACT.matchEntire(filename)?.let { Member(it.groupValues[1], it.groupValues[2]) }
      }
      .distinct()

  /**
   * The sentence for a classpath that will load two Remote Compose lines, or null when the family
   * is coherent — one side supplies all of it, or both sides agree on the version.
   *
   * "Coherent" is judged per artifact and not per version count: the bundle's copies precede the
   * sidecar's, so a family artifact the bundle carries is the bundle's whatever else is behind it,
   * and only an artifact the bundle does **not** carry falls through to the sidecar's version.
   */
  fun skew(line: Line): String? {
    val bundle = line.bundle
    val sidecar = line.sidecar
    if (bundle.isEmpty() || sidecar.isEmpty()) return null
    val carried = bundle.mapTo(mutableSetOf()) { it.artifact }
    val fallthrough = sidecar.filter { it.artifact !in carried }
    if (fallthrough.isEmpty()) return null
    val bundleVersions = bundle.map { it.version }.distinct()
    val fallthroughVersions = fallthrough.map { it.version }.distinct()
    if (bundleVersions.size == 1 && bundleVersions == fallthroughVersions) return null
    val remedy =
      if (line.demoted)
        "The server therefore let the sidecar's line win the whole family: the bundle's copies " +
          "stay on the classpath but behind the sidecar's, so the daemon's own IR replay links " +
          "against one coherent set. A document authored against a newer player may still fail to " +
          "replay — as a per-render error, not a dead lane."
      else
        "These artifacts are compiled against each other, so a render that crosses the seam fails " +
          "with NoSuchFieldError / NoSuchMethodError and no retry can clear it."
    return "This catalog's bundle carries ${bundle.size} Remote Compose artifact(s) at " +
      "${bundleVersions.joinToString()}, but ${fallthrough.size} more that the render needs are " +
      "not in the bundle and come from the daemon sidecar instead, at " +
      "${fallthroughVersions.joinToString()} — ${fallthrough.joinToString { it.artifact }}. " +
      "$remedy Republish the catalog against the Remote Compose version this server ships, or " +
      "against one whose whole family the bundle records."
  }

  /**
   * Persist the two sides beside the launch descriptor in [destDir] and log the skew, if any.
   *
   * The record is written whenever the bundle names the family at all — a healthy catalog costs one
   * small file and lets a later trip say "the line was coherent" by finding nothing to report,
   * rather than by finding nothing at all.
   */
  fun record(
    destDir: File,
    bundle: List<Member>,
    sidecar: List<Member>,
    system: String,
    onLog: (String) -> Unit,
    demoted: Boolean = false,
    fileSystem: FileSystem = SystemFileSystem,
  ) {
    if (bundle.isEmpty()) return
    val line = Line(bundle = bundle, sidecar = sidecar, demoted = demoted)
    skew(line)?.let { onLog("catalog $system: $it") }
    runCatching {
      fileSystem.write(File(destDir, FILE_NAME).path.toPath()) {
        writeUtf8(json.encodeToString(Line.serializer(), line))
      }
    }
  }

  /**
   * One sentence attributing a **fatal** linkage [reason] inside the Remote Compose packages to a
   * classpath that mixes two of its lines — or null when the failure is not Remote Compose's, the
   * record is unreadable, or the family was coherent and the failure has some other cause.
   *
   * Read at trip time from [descriptorPath]'s directory, mirroring
   * [SkikoNativePairing.linkageDiagnosis] and [BundleClasspathGaps.linkageDiagnosis]: the open
   * breaker's reason is the only report anyone outside the box reads.
   */
  fun linkageDiagnosis(
    reason: String,
    descriptorPath: File,
    fileSystem: FileSystem = SystemFileSystem,
  ): String? {
    if (LINKAGE_MARKERS.none { it in reason }) return null
    if (FAMILY_MARKER !in reason.replace('/', '.')) return null
    val file = File(descriptorPath.parentFile ?: return null, FILE_NAME)
    val line =
      runCatching {
        fileSystem
          .read(file.path.toPath()) { readUtf8() }
          .let { json.decodeFromString(Line.serializer(), it) }
      }
        .getOrNull() ?: return null
    return skew(line)
  }

  /**
   * `remote-core-1.0.0-alpha18.jar`, `remote-player-view-1.0.0-SNAPSHOT.jar`,
   * `remote-material3-1.0.0-alpha10.jar` — the family's published artifacts, named from a flat
   * `lib/` directory where the group is not in the path.
   *
   * The second segment is enumerated rather than left open (`remote-\w+`) so an unrelated
   * `remote-…` jar from some other group cannot be read as a Remote Compose member and reported as
   * a skew that isn't one. Every published family artifact starts with one of these: `remote-core`,
   * `remote-player-*`, `remote-creation-*`, `remote-tooling-preview`, `remote-foundation`,
   * `remote-material3`.
   */
  private val REMOTE_ARTIFACT =
    Regex(
      """^(remote-(?:core|player|creation|tooling|foundation|material3)(?:-[a-z0-9]+)*)-""" +
        """(\d[\w.\-]*)\.jar$"""
    )

  /**
   * Linkage markers a mixed family explains. The same set [BundleClasspathGaps] uses and for the
   * same reason: a `VerifyError` or an `UnsatisfiedLinkError` is a different fault, and two Remote
   * Compose lines say nothing useful about it.
   */
  private val LINKAGE_MARKERS =
    listOf(
      "NoSuchFieldError",
      "NoSuchMethodError",
      "NoClassDefFoundError",
      "ClassNotFoundException",
    )
}
