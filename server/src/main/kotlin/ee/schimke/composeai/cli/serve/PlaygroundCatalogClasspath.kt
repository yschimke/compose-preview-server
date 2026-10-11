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
 * Resolves a catalog's packed liveBundle into the classpath a playground snippet compiles against,
 * backing [PlaygroundCompileService]'s `catalogClasspath` seam (`docs/design/PLAYGROUND.md` §8).
 *
 * The compile-time twin of [ServeBundleDaemon.materialize]'s resolution, minus the daemon: extract
 * `classes/app.jar` and resolve `manifest.classpath` coordinates via [CoordinateResolver] (Central,
 * Google Maven, [extraMavenRepos]). Snippets can import the full libraries and whatever catalog
 * composables survived minimization.
 *
 * One flat classpath (catalog classes, embedded libs, resolved deps):
 * [ServeBundleDaemon.bundleDaemonClasspaths]' parent/child split is a render-time classloading
 * concern.
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
   * `android.jar` for an `android`-backend bundle, nothing for another backend, or null when an
   * Android bundle can't be compiled honestly on this host.
   *
   * The framework isn't a Maven coordinate, so nothing above adds it; `androidx.*` arrives via
   * AARs, but a snippet importing `android.util.Base64` or `android.graphics.BitmapFactory` (as the
   * Wear widget lane's `InlineBitmapDeclarations` does) needs the platform. The render side already
   * has it (`ServeRunner.buildPlaygroundAndroidDaemonOpener`), so this closes a compile/render
   * asymmetry.
   *
   * A missing platform fails the Android catalog closed, like a missing Maven dependency, rather
   * than producing misleading `Unresolved reference 'android'` diagnostics; desktop catalogs are
   * unaffected.
   *
   * [AndroidBundleLaunch.resolveAndroidJar] picks the highest installed SDK stub, which matches
   * neither Robolectric's runtime SDK nor the producer's `compileSdk` (not in the manifest). Exact
   * replay would need a bundle-format field.
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
   * the native for them, mirroring compose-ai-tools' `SkikoNativePairing` on the live path.
   *
   * A packed bundle records `skiko-awt:V` but not the platform native jar, and the render path
   * promotes bindings ahead of the desktop sidecar ([ServeBundleDaemon.jarPrecedesDaemonSidecar]),
   * so without this the sidecar's mismatched `libskiko` loads and renders die with
   * `UnsatisfiedLinkError`. Narrow: only bundles with bindings and no host native at their version
   * gain a coordinate (no `sha256`, since the bundle never recorded one).
   *
   * A host native at another version is replaced, not joined: Skiko loads the first `libskiko` it
   * finds, so appending would reproduce the link error. Other hosts' natives are left alone.
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
   * Every declared coordinate must resolve, or the mode is reported unavailable (null). Unlike the
   * live-daemon path, which tolerates gaps and falls back to baked PNGs, a partial compile
   * classpath would surface misleading `unresolved reference` errors. Returns the jars when all
   * resolved.
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
   * Pure classpath assembly: catalog classes, embedded libs, resolved Maven jars, then
   * [platformJars], deduplicated in order. The platform comes last so a catalog jar declaring a
   * type `android.jar` stubs wins, as with a Gradle bootclasspath.
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
