package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.io.composeAiCacheDir
import ee.schimke.watchface.packager.AndroidSdk
import ee.schimke.watchface.packager.PackagedWatchFace
import ee.schimke.watchface.packager.PackagerOptions
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement

/**
 * The simulator launcher compose-ai-tools exports (`DwfSimulatorLauncher`), as two functions so a
 * test can stand in for a Robolectric daemon. [connect] returns a connection over the daemon's
 * stdio channel.
 */
internal class DwfSimulatorLaunch(
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
internal class RenderHostDwfApkRuntime(
  private val runtimeInstalled: () -> Boolean,
  private val sdk: () -> AndroidSdk? = { AndroidSdk.locate() },
  private val launcher: DwfSimulatorLaunch = DwfSimulatorLaunch.Production,
  private val cacheDir: File = composeAiCacheDir("dwf-preview-apks"),
  private val packagerFor:
    (AndroidSdk) -> (Map<String, ByteArray>, PackagerOptions) -> PackagedWatchFace =
    DwfPackaging::packagerFor,
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
          "ANDROID_HOME or ANDROID_SDK_ROOT. Install with: ${AndroidSdk.INSTALL_HINT}. " +
          "The fast Remote Compose preview in the editor is unaffected.",
      )
    }
    launcher.unavailableReason()?.let {
      return DwfApkRuntime.Availability.Unavailable(DwfPreviewSurface.SIMULATOR_UNAVAILABLE, it)
    }
    return DwfApkRuntime.Availability.Ready
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
          sdk() ?: throw DwfRuntimeException("no Android SDK: ${AndroidSdk.INSTALL_HINT}")
        try {
          DwfPackaging.pack(source, out, File(cacheDir, "signing"), packagerFor(located))
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
