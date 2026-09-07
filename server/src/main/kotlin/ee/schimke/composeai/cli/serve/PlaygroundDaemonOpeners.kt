package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.AndroidBundleLaunch
import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.render.session.subprocess.SubprocessRenderSessions
import java.io.File

/**
 * The two daemon openers a compiled snippet is rendered on, built once and shared.
 *
 * `serve` has stood these up since the playground's first frame landed, and it was the only caller
 * for as long as a render could only happen inside a running server. `design render --local`
 * ([DesignLocalLane]) is the second: the whole point of that mode is to run **the same** lane off
 * the server, so a lane it reimplemented would be a different lane that happened to look alike —
 * the one shape of bug the local mode exists to rule out
 * ([#551](https://github.com/yschimke/compose-preview-server/issues/551)).
 *
 * Nothing here decides policy. Which sandbox applies, whether the caller is admitted, and what a
 * missing sidecar means for the surface asking are the caller's; this answers only "can this host
 * open that daemon, and with what on its classpath".
 */
internal object PlaygroundDaemonOpeners {

  /**
   * The Android/Robolectric opener — the `lib-daemon-android` sidecar plus `android.jar` on the
   * daemon classpath, the Robolectric jvmArgs/sysprops, and a subprocess `openBundleDaemon`.
   * Mirrors [ServeBundleDaemon]'s `androidBundleDaemonLaunch`. Null (having said why through [log])
   * when the sidecar or `android.jar` is missing: an Android bundle then has no renderer rather
   * than compiling to a dead end.
   */
  fun android(sandbox: PlaygroundSandbox, log: (String) -> Unit): PlaygroundAndroidSessionOpener? {
    val daemonJars = locateBundleSidecarJars("lib-daemon-android")
    if (daemonJars.isEmpty()) {
      log(
        "Android rendering needs the Android daemon sidecar (lib-daemon-android/), which ships " +
          "separately as compose-preview-android-daemon-<version>.zip; unpack it and set " +
          "-Dcomposeai.cli.libDaemonAndroidDir=<dir>/lib-daemon-android."
      )
      return null
    }
    val androidJar =
      AndroidBundleLaunch.resolveAndroidJar(localPropertiesFile = null)
        ?: run {
          log("Android rendering needs android.jar — set ANDROID_HOME / ANDROID_SDK_ROOT.")
          return null
        }
    val launch = AndroidBundleLaunch()
    val daemonClasspath = (daemonJars + listOf(androidJar)).map { it.absolutePath }
    val jvmArgs = launch.jvmArgs()
    val sysprops = sandbox.robolectricSystemProperties(launch.robolectricSystemProperties())
    return PlaygroundAndroidSessionOpener { classesDir, previewsJson, workspaceRoot, userClasspath
      ->
      firstFrameDaemon(
        daemonClasspath,
        jvmArgs,
        sysprops,
        classesDir,
        previewsJson,
        workspaceRoot,
        userClasspath,
        sandbox,
      )
    }
  }

  /**
   * The desktop (CMP/Skiko) opener — `lib-daemon-desktop` + `lib-renderer` on the daemon classpath
   * and the desktop jvmArgs, over the same subprocess `openBundleDaemon`. Mirrors
   * [ServeBundleDaemon]'s `desktopBundleDaemonLaunch`, and is null in the same way [android] is.
   */
  fun desktop(sandbox: PlaygroundSandbox, log: (String) -> Unit): PlaygroundAndroidSessionOpener? {
    val daemonJars = locateBundleSidecarJars("lib-daemon-desktop")
    val rendererJars = locateBundleSidecarJars("lib-renderer")
    if (daemonJars.isEmpty() || rendererJars.isEmpty()) {
      log(
        "desktop rendering needs the desktop daemon sidecar (lib-daemon-desktop/ + lib-renderer/) " +
          "from an installed distribution."
      )
      return null
    }
    val daemonClasspath = (daemonJars + rendererJars).map { it.absolutePath }
    // -Dapple.awt.UIElement=true keeps the desktop JVM a macOS background agent (no Dock/focus
    // steal); mirrors desktopBundleDaemonLaunch. No Robolectric sysprops on the desktop backend.
    val jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Dapple.awt.UIElement=true")
    return PlaygroundAndroidSessionOpener { classesDir, previewsJson, workspaceRoot, userClasspath
      ->
      firstFrameDaemon(
        daemonClasspath,
        jvmArgs,
        emptyMap(),
        classesDir,
        previewsJson,
        workspaceRoot,
        userClasspath,
        sandbox,
      )
    }
  }

  /**
   * Open a bundle-less daemon for a first-frame render, partitioning the snippet's [userClasspath]
   * the way the live path ([ServeBundleDaemon.materializePlaygroundSnippet]) does: jars in the
   * namespaces `UserClassLoaderHolder` delegates to the parent (`androidx.*`, `kotlinx-coroutines`,
   * `kotlinx-io`) must precede the [sidecarClasspath] on the daemon (parent) `-cp`, or the daemon
   * loads its own sidecar versions and a snippet built against the catalog's newer shared ABI fails
   * with `NoSuchMethodError`/`NoSuchFieldError` (and the render service then silently returns no
   * image). The snippet's own classes stay isolated on the child (user) loader.
   */
  private fun firstFrameDaemon(
    sidecarClasspath: List<String>,
    jvmArgs: List<String>,
    extraSystemProperties: Map<String, String>,
    classesDir: File,
    previewsJson: File,
    workspaceRoot: File,
    userClasspath: List<String>,
    sandbox: PlaygroundSandbox,
  ) =
    SubprocessRenderSessions.openBundleDaemon(
      daemonClasspath =
        userClasspath.filter { ServeBundleDaemon.jarPrecedesDaemonSidecar(File(it)) } +
          sidecarClasspath,
      classesDir = classesDir,
      previewsJson = previewsJson,
      workspaceRoot = workspaceRoot,
      modulePath = ":playground",
      // The sandbox's JVM caps come last so they win over the backend defaults.
      jvmArgs = jvmArgs + sandbox.jvmArgs(workspaceRoot),
      extraSystemProperties = extraSystemProperties,
      userClasspath =
        userClasspath.filterNot { ServeBundleDaemon.jarPrecedesDaemonSidecar(File(it)) },
      // Stage-1's first frame and the RC capture run a stranger's snippet exactly as the live lane
      // does, so they are jailed identically — one JVM per snippet, killed at the hard TTL.
      jailCommand =
        sandbox.command(
          PlaygroundSandbox.Paths(
            workDir = workspaceRoot,
            readOnly =
              (sidecarClasspath + userClasspath).map { File(it) }.distinct() +
                classesDir +
                previewsJson,
            javaHome = File(System.getProperty("java.home")),
          )
        ),
      hardTtlSeconds = sandbox.ttlSeconds.takeIf { sandbox.isActive },
    )
}
