package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.locateBundleSidecarJars
import ee.schimke.composeai.io.composeAiCacheDir
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement

/**
 * The simulator launcher compose-ai-tools exports (`DwfSimulatorLauncher`), as two functions so a
 * test can stand in for a Robolectric daemon. [connect] returns a connection over the daemon's
 * stdio channel.
 */
class DwfSimulatorLaunch(
  /** Why a daemon cannot start here, or null. */
  val unavailableReason: () -> String?,
  val connect: (File) -> ee.schimke.composeai.cli.serve.DwfSimulatorConnection,
) {
  companion object {
    val Production =
      DwfSimulatorLaunch(
        unavailableReason = DwfSimulatorLauncher::unavailableReason,
        connect = DwfSimulatorLauncher::connect,
      )
  }
}

/**
 * The production [DwfApkRuntime]: `:wff-packager` for packaging and compose-ai-tools'
 * `DwfSimulatorLauncher` for the daemon.
 *
 * [availability] answers the first thing that is missing, in the order an operator fixes them:
 * Google's proprietary `DeclarativeWatchFaceRuntime.apk` ([DwfPreviewSurface.RUNTIME_MISSING], the
 * common hosted case), then the Android SDK the packager needs
 * ([DwfPreviewSurface.ANDROID_SDK_MISSING]: `aapt2`, `zipalign`, a platform `android.jar`), then
 * the simulator's own prerequisites ([DwfPreviewSurface.SIMULATOR_UNAVAILABLE]).
 *
 * Packaged APKs are cached by [DwfSource.digest] under [cacheDir], so a re-push of an unchanged
 * revision, a second session on it, and a server restart all reuse the same file.
 */
class RenderHostDwfApkRuntime(
  private val runtimeInstalled: () -> Boolean,
  private val sdk: () -> DwfAndroidSdk? = { DwfAndroidSdk.locate() },
  /** The `lib-wff-packager/` directory, or null when this install does not carry it. */
  private val packagerLibDir: () -> File? = { locateWffPackagerDir() },
  private val launcher: DwfSimulatorLaunch = DwfSimulatorLaunch.Production,
  private val cacheDir: File = composeAiCacheDir("dwf-preview-apks"),
  private val packagerFor: (File, DwfAndroidSdk, File) -> DwfWffPackager =
    { lib, located, signing ->
      DwfSubprocessPackager(lib, located, signing)
    },
) : DwfApkRuntime {
  private val packLock = Any()
  private val packaged = ConcurrentHashMap<String, File>()

  override fun availability(): DwfApkRuntime.Availability {
    if (!runtimeInstalled()) {
      return DwfApkRuntime.Availability.Unavailable(
        DwfPreviewSurface.RUNTIME_MISSING,
        "The Wear OS watch-face runtime (DeclarativeWatchFaceRuntime.apk) is not installed on " +
          "this server. It is Google's proprietary runtime and is never redistributed; install " +
          "it with `compose-preview dwf-runtime pull` on the host, or PUT /admin/dwf-runtime. " +
          "The fast Remote Compose preview in the editor is unaffected.",
      )
    }
    if (sdk() == null) {
      return DwfApkRuntime.Availability.Unavailable(
        DwfPreviewSurface.ANDROID_SDK_MISSING,
        "Drawing a watch face with the real runtime needs an Android SDK on this server: " +
          "build-tools (aapt2 and zipalign) and a platform android.jar, found through " +
          "ANDROID_HOME or ANDROID_SDK_ROOT. Install with: ${DwfAndroidSdk.INSTALL_HINT}. " +
          "The fast Remote Compose preview in the editor is unaffected.",
      )
    }
    if (packagerLibDir() == null) {
      return DwfApkRuntime.Availability.Unavailable(
        DwfPreviewSurface.PACKAGING_UNAVAILABLE,
        "This install has no lib-wff-packager/ (the watch-face packager and its validator). It " +
          "ships in the server distribution; set -D$PACKAGER_DIR_PROPERTY to its directory if " +
          "the server was started some other way.",
      )
    }
    launcher.unavailableReason()?.let {
      return DwfApkRuntime.Availability.Unavailable(DwfPreviewSurface.SIMULATOR_UNAVAILABLE, it)
    }
    return DwfApkRuntime.Availability.Ready
  }

  override fun isPackaged(source: DwfSource): Boolean {
    val digest = source.digest()
    if (packaged[digest]?.isFile == true) return true
    val out = File(cacheDir, "wff-$digest.apk")
    return out.isFile && out.length() > 0L
  }

  override fun packageApk(source: DwfSource): File {
    val digest = source.digest()
    packaged[digest]
      ?.takeIf { it.isFile }
      ?.let {
        return it
      }
    synchronized(packLock) {
      packaged[digest]
        ?.takeIf { it.isFile }
        ?.let {
          return it
        }
      val out = File(cacheDir, "wff-$digest.apk")
      if (!out.isFile || out.length() == 0L) {
        val located =
          sdk() ?: throw DwfRuntimeException("no Android SDK: ${DwfAndroidSdk.INSTALL_HINT}")
        val lib = packagerLibDir() ?: throw DwfRuntimeException("no lib-wff-packager/ installed")
        try {
          DwfPackaging.pack(source, out, packagerFor(lib, located, File(cacheDir, "signing")))
        } catch (e: DwfRuntimeException) {
          throw e
        } catch (e: Exception) {
          throw DwfRuntimeException("packaging failed: ${e.message ?: e.javaClass.simpleName}", e)
        }
      }
      packaged[digest] = out
      return out
    }
  }

  override fun connect(initialApk: File): DwfDaemonConnection {
    val connection =
      try {
        launcher.connect(initialApk)
      } catch (e: Exception) {
        throw DwfRuntimeException(
          "the simulator did not start: ${e.message ?: e.javaClass.simpleName}",
          e,
        )
      }
    return object : DwfDaemonConnection {
      override val alive: Boolean
        get() = connection.alive

      override fun command(command: DwfCommand): JsonElement? =
        try {
          connection.command(command.cmd, command.payload)
        } catch (e: IllegalStateException) {
          throw DwfRuntimeException(e.message ?: "the simulator rejected ${command.cmd}", e)
        }

      override fun close() = connection.close()
    }
  }
}

private const val PACKAGER_DIR_PROPERTY = "composeai.cli.lib.wff.packagerDir"

/**
 * The `lib-wff-packager/` directory, found like every other sidecar ([locateBundleSidecarJars]:
 * `-D$PACKAGER_DIR_PROPERTY`, `$APP_HOME/lib-wff-packager`, or next to the server's own jars); null
 * unless it holds jars.
 */
fun locateWffPackagerDir(): File? =
  locateBundleSidecarJars("lib-wff-packager").firstOrNull()?.parentFile
