package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.BundleReader
import ee.schimke.composeai.bundle.coordinates.CoordinateResolver
import ee.schimke.composeai.bundle.extractBundleClassesAndManifest
import ee.schimke.composeai.io.SystemFileSystem
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Resolves a catalog's packed **liveBundle** into the classpath a playground snippet compiles
 * against — the production backing for [PlaygroundCompileService]'s `catalogClasspath` seam
 * (`docs/design/PLAYGROUND.md` §8).
 *
 * This is the compile-time twin of [ServeBundleDaemon.materialize]'s classpath resolution, minus
 * the daemon launch: extract the bundle's `classes/app.jar` (the catalog's own composables) and
 * resolve its `manifest.classpath` Maven coordinates to jars via [CoordinateResolver] (Central +
 * Google Maven + any [extraMavenRepos]). A snippet compiled against the result can `import` both
 * the resolved library (e.g. `androidx.compose.material3.*`, complete because it comes from the
 * unminimized library jar) and whatever of the catalog's own composables survived bundle
 * minimization.
 *
 * **One flat classpath, no parent/child split.** [ServeBundleDaemon.bundleDaemonClasspaths]
 * partitions jars into a daemon-parent overlay and a user-child loader — but that split is a
 * *render-time* classloader-delegation concern. For *compiling* the snippet, every jar belongs on
 * one classpath, so this resolver keeps it flat (catalog classes first, then embedded libs, then
 * resolved deps).
 */
object PlaygroundCatalogClasspath {

  /**
   * Resolve [bundleFile] into a compile classpath, extracting into [destDir]. Returns null (logging
   * why) when the bundle can't be read or extracted — the caller then reports the mode as
   * unavailable rather than compiling against an incomplete classpath.
   */
  fun resolve(
    bundleFile: File,
    destDir: File,
    system: String,
    extraMavenRepos: List<String> = emptyList(),
    offline: Boolean = false,
    fileSystem: FileSystem = SystemFileSystem,
    resolveAndroidJar: () -> File? = {
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = null)
    },
    onLog: (String) -> Unit = {},
  ): PlaygroundCompileService.Classpath? {
    val manifest =
      try {
        BundleReader.readMetadata(bundleFile).manifest
      } catch (e: Exception) {
        onLog("playground $system: could not read bundle metadata (${e.message})")
        return null
      }

    val zipBytes =
      try {
        BundleReader.extractZipBytes(bundleFile, fileSystem)
      } catch (e: Exception) {
        onLog("playground $system: could not read bundle zip (${e.message})")
        return null
      }

    destDir.mkdirs()
    val classesDir = File(destDir, "classes").apply { mkdirs() }
    val libsDir = File(destDir, "libs").apply { mkdirs() }
    val previewsJson = File(destDir, "previews.json")
    // A fully IR-backed bundle carries no classes/app.jar; a mixed/class-backed one must — mirrors
    // ServeBundleDaemon.materialize's gate.
    val irPreviewIds = manifest.intermediateRepresentations.mapTo(mutableSetOf()) { it.previewId }
    val requireAppJar = manifest.previewIds.any { it !in irPreviewIds }
    try {
      extractBundleClassesAndManifest(
        zipBytes,
        classesDir,
        previewsJson,
        bundleFile,
        requireAppJar,
        fileSystem,
      )
    } catch (e: Exception) {
      onLog("playground $system: bundle extraction failed (${e.message})")
      return null
    }

    val libJars = BundleReader.extractEmbeddedLibs(zipBytes, libsDir, fileSystem)
    val recordedCoords = manifest.classpath.filterIsInstance<BundleReader.ClasspathEntry.Maven>()
    val mavenCoords = withHostSkikoNative(recordedCoords)
    (recordedCoords - mavenCoords.toSet()).forEach {
      onLog(
        "playground $system: bundle's Skiko native ${it.version} does not match its bindings — " +
          "dropping ${it.group}:${it.artifact}:${it.version}"
      )
    }
    (mavenCoords - recordedCoords.toSet()).forEach {
      onLog(
        "playground $system: bundle carries Skiko bindings ${it.version} with no native for " +
          "this host at that version — adding ${it.group}:${it.artifact}:${it.version}"
      )
    }
    val resolutions =
      CoordinateResolver(
          warn = { onLog("playground $system: $it") },
          networkEnabled = if (offline) false else CoordinateResolver.defaultNetworkEnabled(),
          remoteRepositories =
            CoordinateResolver.DEFAULT_REMOTE_REPOSITORIES +
              extraMavenRepos.filter { it.isNotBlank() },
        )
        .resolveAll(mavenCoords)
    val resolvedJars = requireAllResolved(system, resolutions, onLog) ?: return null

    val platformJars =
      requiredAndroidPlatformJars(system, manifest.backend, resolveAndroidJar, onLog) ?: return null

    return assemble(
      system,
      classesDir,
      libJars,
      resolvedJars,
      platformJars = platformJars,
    )
  }

  /**
   * `android.jar` for an `android`-backend bundle, no platform jars for another backend, or null
   * when an Android bundle cannot be compiled honestly on this host.
   *
   * The framework is not a Maven coordinate and never appears in `manifest.classpath`, so nothing
   * above puts it on the compile classpath. Every `androidx.*` class does arrive — an AAR's
   * `classes.jar` is a resolved dependency like any other — which is why this was invisible for as
   * long as generated Kotlin named only `androidx.*`: a snippet that imports `android.util.Base64`
   * or `android.graphics.BitmapFactory` is the first one to need the platform itself, and the Wear
   * widget native-preview lane emits exactly those two to decode an inlined picture
   * (`InlineBitmapDeclarations`). Without this the compile fails with `Unresolved reference
   * 'graphics'` on the import line — a message that reads like a defect in the design rather than a
   * hole in the host's classpath.
   *
   * The render half was never missing it: `ServeRunner.buildPlaygroundAndroidDaemonOpener` puts
   * `android.jar` on the daemon classpath and disables the Android modes when it cannot find one.
   * So this closes a compile/render asymmetry rather than adding a new requirement.
   *
   * A missing platform fails this Android catalog closed, just like a missing Maven dependency.
   * Returning a partial classpath would merely turn the host configuration error into misleading
   * `Unresolved reference 'android'` diagnostics. The decision is scoped by [backend], so a host
   * without an SDK still resolves every desktop catalog exactly as before.
   *
   * [AndroidBundleLaunch.resolveAndroidJar] selects the highest installed SDK stub. That is not the
   * same jar Robolectric executes: the renderer separately selects an `android-all` runtime SDK (35
   * by default, overrideable and clamped to Robolectric's supported range). Nor does it reproduce
   * the catalog producer's `compileSdk`, because the bundle manifest does not carry that value. The
   * policy here is consequently only "supply an installed Android API surface"; exact producer-SDK
   * replay needs an additive bundle-format field and coordinated producer/consumer support rather
   * than an inference in this server.
   */
  internal fun requiredAndroidPlatformJars(
    system: String,
    backend: String?,
    resolveAndroidJar: () -> File?,
    onLog: (String) -> Unit,
  ): List<File>? {
    if (backend != ANDROID_BACKEND) return emptyList()
    val androidJar = resolveAndroidJar()
    if (androidJar == null) {
      onLog(
        "playground $system: this is an android bundle and no android.jar was found; mode " +
          "unavailable — set ANDROID_HOME / ANDROID_SDK_ROOT"
      )
      return null
    }
    return listOf(androidJar)
  }

  /** `manifest.backend` for a bundle whose previews are drawn by Robolectric-backed Android. */
  private const val ANDROID_BACKEND = "android"

  /**
   * [coords], plus this host's `skiko-awt-runtime` when the bundle records Skiko bindings without
   * the native for them.
   *
   * The repair compose-ai-tools' `SkikoNativePairing` applies on the live path
   * (`ServeBundleDaemon.materialize`), which this twin skipped. A packed bundle records
   * `skiko-awt:V` but not the platform jar carrying `libskiko` — a Gradle constraint, not a
   * classpath entry — and the render path promotes those bindings ahead of the desktop sidecar
   * ([ServeBundleDaemon.jarPrecedesDaemonSidecar]). So the only native left was the sidecar's own:
   * m3-catalog's bundle on Skiko 0.150.1 linked against the image's 0.144.6 and every native render
   * died on `UnsatisfiedLinkError: ParagraphKt._nGetUnresolvedCodepointsCount`, then waited out the
   * render budget. The resolved native lands in the same Maven layout as its bindings, so it is
   * promoted beside them.
   *
   * Narrow on purpose, as the original is: only a bundle that carries bindings and no native for
   * this host at their version gains a coordinate. No `sha256`, because the bundle never recorded
   * the artifact; the version comes from the bindings it did record.
   *
   * A native for this host at **another** version is replaced, not joined. Both would be promoted
   * together, in manifest order, and Skiko loads the first `libskiko` its resource lookup finds —
   * the stale one — so appending the right jar behind it would reproduce the very link error this
   * exists to prevent. Other hosts' natives are left alone: they are never loaded here.
   */
  internal fun withHostSkikoNative(
    coords: List<BundleReader.ClasspathEntry.Maven>,
    osName: String = System.getProperty("os.name").orEmpty(),
    osArch: String = System.getProperty("os.arch").orEmpty(),
  ): List<BundleReader.ClasspathEntry.Maven> {
    val bindings =
      coords.firstOrNull { it.group == SKIKO_GROUP && it.artifact in SKIKO_BINDINGS }
        ?: return coords
    val host = skikoHostRuntime(osName, osArch) ?: return coords
    fun isHostNative(it: BundleReader.ClasspathEntry.Maven) =
      it.group == SKIKO_GROUP && it.artifact == host
    if (coords.any { isHostNative(it) && it.version == bindings.version }) {
      return coords.filterNot { isHostNative(it) && it.version != bindings.version }
    }
    return coords.filterNot(::isHostNative) +
      BundleReader.ClasspathEntry.Maven(
        group = SKIKO_GROUP,
        artifact = host,
        version = bindings.version,
        type = "jar",
        sha256 = null,
      )
  }

  /** `skiko-awt-runtime-<os>-<arch>` for this host, or null where Skiko publishes no native. */
  internal fun skikoHostRuntime(osName: String, osArch: String): String? {
    val name = osName.lowercase()
    val os =
      when {
        "mac" in name || "darwin" in name -> "macos"
        "win" in name -> "windows"
        "linux" in name -> "linux"
        else -> return null
      }
    val arch =
      when (osArch.lowercase()) {
        "aarch64",
        "arm64" -> "arm64"
        "x86_64",
        "amd64",
        "x64" -> "x64"
        else -> return null
      }
    return "$SKIKO_RUNTIME_PREFIX$os-$arch"
  }

  private const val SKIKO_GROUP = "org.jetbrains.skiko"
  private val SKIKO_BINDINGS = setOf("skiko", "skiko-awt")
  private const val SKIKO_RUNTIME_PREFIX = "skiko-awt-runtime-"

  /**
   * Every declared coordinate must resolve, or the compile classpath is **incomplete** and the mode
   * is reported unavailable (return null). Unlike the live-daemon path — which tolerates a partial
   * classpath and falls back to baked PNGs — a playground compile against a missing catalog library
   * would surface a misleading `unresolved reference` to the user instead of the honest
   * mode-unavailable response. So fail closed: log the misses and refuse the whole classpath rather
   * than assembling a partial one. Returns the resolved jars when every coordinate resolved.
   */
  internal fun requireAllResolved(
    system: String,
    resolutions: List<CoordinateResolver.Resolution>,
    onLog: (String) -> Unit,
  ): List<File>? {
    val unresolved = resolutions.filter { it.file == null }
    if (unresolved.isNotEmpty()) {
      onLog(
        "playground $system: ${unresolved.size} unresolved dependency coordinate(s), mode " +
          "unavailable: ${unresolved.joinToString { it.coordinate.toString() }}"
      )
      return null
    }
    return resolutions.mapNotNull { it.file }
  }

  /**
   * Pure classpath assembly: catalog classes first, then embedded libs, then every resolved Maven
   * jar, then [platformJars], deduplicated and order-preserving. Separated from [resolve]'s IO so
   * the ordering/dedup can be unit-tested without a real bundle.
   *
   * The platform comes last deliberately. `android.jar` carries stubbed method bodies and a few
   * types the support libraries also ship, so a catalog jar that declares one of them must win —
   * the same precedence a Gradle Android compilation gives its bootclasspath.
   */
  internal fun assemble(
    system: String,
    classesDir: File,
    libJars: List<File>,
    resolvedJars: List<File>,
    platformJars: List<File> = emptyList(),
  ): PlaygroundCompileService.Classpath {
    val entries =
      (listOf(classesDir) + libJars + resolvedJars + platformJars)
        .map { it.absolutePath }
        .distinct()
        .map { it.toPath() }
    return PlaygroundCompileService.Classpath(moduleName = "playground-$system", entries = entries)
  }
}
