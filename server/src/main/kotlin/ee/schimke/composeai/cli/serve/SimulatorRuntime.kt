package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.simulator.launch.AppSimulatorDevice
import ee.schimke.composeai.simulator.launch.AppSimulatorHost
import ee.schimke.composeai.simulator.launch.AppSimulatorLaunch
import ee.schimke.composeai.simulator.launch.AppSimulatorLauncher
import ee.schimke.composeai.simulator.launch.AppSimulatorSession
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What one session asks the runtime to start. */
data class SimulatorLaunch(
  val sessionId: String,
  val mode: SimulatorMode,
  /** The registered APK for each of [mode]'s devices. */
  val apks: Map<SimulatorDevice, File>,
  /** Each device's app package, for restoring a hibernated session's activity. */
  val packages: Map<SimulatorDevice, String> = emptyMap(),
  val network: String,
  val launcher: Boolean,
)

/** A command the simulator answered with `ok: false`, or could not answer. */
class SimulatorCommandException(message: String) : RuntimeException(message)

/** One running simulator (or paired pair): a JSON-lines control channel. Thread-safe. */
interface SimulatorProcess : AutoCloseable {
  val alive: Boolean

  /**
   * Send [command] (`{"cmd":…, …}`) to [device] and wait for its answer.
   *
   * @return the response's `result`, or null when it has none.
   * @throws SimulatorCommandException on `ok: false`, a timeout, or the process ending.
   */
  fun command(
    device: SimulatorDevice,
    command: JsonObject,
    timeoutMillis: Long = 30_000,
  ): JsonElement?

  /** The last lines the process wrote to stderr, for an operator's diagnosis. */
  fun stderrTail(): List<String>

  /** Every OS process of this session (its simulator JVMs), for measuring it. */
  fun pids(): List<Long> = emptyList()

  /**
   * Freeze the whole process tree (`SIGSTOP`): an idle session then costs no CPU at all, keeps its
   * memory, and [resume]s to a frame in about 40 ms (measured; `docs/design/SIMULATOR.md`).
   */
  fun pause() {}

  fun resume() {}
}

/**
 * How the server starts simulators. The production one is [LauncherSimulatorRuntime]; tests use a
 * fake.
 */
interface SimulatorRuntime {
  sealed interface Availability {
    data object Ready : Availability

    data class Unavailable(val code: String, val reason: String) : Availability
  }

  fun availability(): Availability

  /**
   * Start [launch]'s simulators and return at once; [onEvent] receives each unasked line (`ready`,
   * `activity`, …) with the device it came from, on a reader thread.
   */
  fun start(
    launch: SimulatorLaunch,
    onEvent: (SimulatorDevice, JsonObject) -> Unit,
  ): SimulatorProcess

  /** Host directories whose paths must never reach a client ([SimulatorControlPolicy.redact]). */
  fun hostRoots(): List<String> = emptyList()

  companion object {
    const val UNAVAILABLE_SIMULATOR = "simulator_missing"
    const val UNAVAILABLE_ANDROID_SDK = "simulator_android_sdk_missing"
    const val UNAVAILABLE_JAVA = "simulator_java_unsupported"
  }
}

/**
 * Starts simulators in this server's own children through `android-simulator`'s
 * [AppSimulatorLauncher] (`ee.schimke.composeai:simulator-launch`): one simulator JVM per device,
 * and no CLI JVM between them, which costs about 85 MB a session. The launcher converts and caches
 * the APK by content, runs the phone and the watch of a pair over a Data Layer hub, and routes each
 * command by device; nothing of that is restated here.
 *
 * It runs from an `android-simulator` distribution ([home]: `simulator.home`, else
 * `ANDROID_SIMULATOR_HOME`, else the image's [DEFAULT_HOME]). Every child JVM — the APK converter
 * and each simulator — gets a **clean environment**, since the server's holds operator secrets and
 * the app can read its own, and when [sandbox] is active runs inside the same jail the playground
 * uses. Under a jail the one writable path is [stateDir], which also holds the converted-APK cache,
 * so a converted APK stays converted across sessions.
 */
class LauncherSimulatorRuntime(
  private val home: () -> File? = ::locateHome,
  private val androidSdk: () -> File? = ::locateAndroidSdk,
  private val javaHome: File = File(System.getProperty("java.home")),
  /** The simulators' state: their home when jailed, and the converted-APK cache. */
  private val stateDir: File,
  /** The registry's root, bound read-only into a jail. */
  private val appsDir: File,
  private val sandbox: PlaygroundSandbox = PlaygroundSandbox.NONE,
  /** Each simulator JVM's `-Xmx`; null lets the launcher choose (an eighth of the machine). */
  private val maxHeap: String? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : SimulatorRuntime {

  /** The converted-APK cache: under [stateDir], the one path a jailed converter can write. */
  internal val cacheDir: File
    get() = File(stateDir, "converted")

  override fun availability(): SimulatorRuntime.Availability {
    val homeDir = home()
    if (homeDir == null || !homeDir.isDirectory) {
      return SimulatorRuntime.Availability.Unavailable(
        SimulatorRuntime.UNAVAILABLE_SIMULATOR,
        "the android-simulator distribution is not installed on this server (set simulator.home " +
          "to it, or \$${AppSimulatorLauncher.HOME_ENV})",
      )
    }
    if (androidSdk() == null) {
      return SimulatorRuntime.Availability.Unavailable(
        SimulatorRuntime.UNAVAILABLE_ANDROID_SDK,
        "the simulator needs an Android SDK platform (ANDROID_HOME or ANDROID_SDK_ROOT)",
      )
    }
    val feature = javaFeatureVersion(javaHome)
    if (feature != null && feature < MIN_JAVA) {
      return SimulatorRuntime.Availability.Unavailable(
        SimulatorRuntime.UNAVAILABLE_JAVA,
        "the simulator needs Java $MIN_JAVA+; ${javaHome.absolutePath} is Java $feature",
      )
    }
    AppSimulatorLauncher.unavailableReason(host(homeDir))?.let {
      return SimulatorRuntime.Availability.Unavailable(SimulatorRuntime.UNAVAILABLE_SIMULATOR, it)
    }
    return SimulatorRuntime.Availability.Ready
  }

  override fun hostRoots(): List<String> =
    listOfNotNull(
      stateDir.absolutePath,
      appsDir.absolutePath,
      home()?.absolutePath,
      androidSdk()?.absolutePath,
      javaHome.absolutePath,
      System.getProperty("user.home"),
      System.getProperty("java.io.tmpdir"),
    )

  override fun start(
    launch: SimulatorLaunch,
    onEvent: (SimulatorDevice, JsonObject) -> Unit,
  ): SimulatorProcess {
    val homeDir = checkNotNull(home()) { "android-simulator not found" }
    stateDir.mkdirs()
    val host = host(homeDir)
    val apks = launch.apks.mapKeys { (device, _) -> device.launcherDevice() }
    // The pool shows "converting" while this runs: a first conversion of a build takes up to a
    // minute; a converted one returns at once from the cache.
    for ((device, apk) in launch.apks) {
      onEvent(device, CONVERSION_EVENT)
      AppSimulatorLauncher.prepare(apk, host)
    }
    onLog("serve: simulator ${launch.sessionId}: starting ${launch.mode.id}")
    val session =
      AppSimulatorLauncher.start(
        AppSimulatorLaunch(
          apks = apks,
          network = launch.network,
          launcher = launch.launcher,
          maxHeap = maxHeap,
        ),
        host,
      ) { device, event ->
        runCatching { onEvent(device.serverDevice(), event) }
          .onFailure { onLog("serve: simulator event: $it") }
      }
    return LauncherProcess(session)
  }

  /** How every child JVM starts: [stateDir], a clean environment, and the jail when active. */
  internal fun host(homeDir: File): AppSimulatorHost {
    val sdk = androidSdk()
    return AppSimulatorHost(
      home = homeDir,
      java = javaHome,
      cacheDir = cacheDir,
      processBuilder = { command ->
        ProcessBuilder(jailPrefix(homeDir, sdk) + command).directory(stateDir).also {
          val env = it.environment()
          env.clear()
          env.putAll(environment(sdk))
        }
      },
    )
  }

  private fun jailPrefix(homeDir: File, sdk: File?): List<String> {
    if (!sandbox.isActive) return emptyList()
    val readOnly =
      listOfNotNull(
        homeDir,
        sdk,
        appsDir,
        File(System.getProperty("user.home"), ".m2/repository").takeIf { it.isDirectory },
        ee.schimke.composeai.io.composeAiCacheDir("robolectric-deps").takeIf { it.isDirectory },
      )
    return sandbox.command(
      PlaygroundSandbox.Paths(workDir = stateDir, readOnly = readOnly, javaHome = javaHome),
    )
  }

  /** Everything a simulator's JVM sees of an environment: no tokens, no secrets. */
  internal fun environment(sdk: File?): Map<String, String> {
    val userHome = System.getProperty("user.home")
    val env =
      linkedMapOf(
        "PATH" to "${File(javaHome, "bin").absolutePath}:/usr/local/bin:/usr/bin:/bin",
        "HOME" to if (sandbox.isActive) stateDir.absolutePath else userHome,
        "LANG" to "C.UTF-8",
        "TZ" to (System.getenv("TZ") ?: "UTC"),
        "JAVA_HOME" to javaHome.absolutePath,
      )
    sdk?.let {
      env["ANDROID_HOME"] = it.absolutePath
      env["ANDROID_SDK_ROOT"] = it.absolutePath
    }
    if (sandbox.isActive) {
      // A JVM's user.home comes from the passwd entry, not HOME, and a jail leaves only the state
      // dir writable: point every JVM there, and at the server user's Maven repository, bound
      // read-only, for the prefetched android-all.
      env["JAVA_TOOL_OPTIONS"] =
        "-Duser.home=${stateDir.absolutePath} " +
          "-Dmaven.repo.local=${File(userHome, ".m2/repository").absolutePath}"
    }
    return env
  }

  /** An [AppSimulatorSession] as the pool's [SimulatorProcess]. */
  private class LauncherProcess(private val session: AppSimulatorSession) : SimulatorProcess {
    override val alive: Boolean
      get() = session.alive

    override fun command(
      device: SimulatorDevice,
      command: JsonObject,
      timeoutMillis: Long,
    ): JsonElement? =
      try {
        session.command(device.launcherDevice(), command, timeoutMillis)
      } catch (e: IllegalStateException) {
        throw SimulatorCommandException(e.message ?: "$e")
      } catch (e: IllegalArgumentException) {
        throw SimulatorCommandException(e.message ?: "$e")
      }

    override fun stderrTail(): List<String> =
      session.devices.flatMap { device ->
        session.stderrTail(device).let { lines ->
          if (session.devices.size > 1) lines.map { "[${device.id}] $it" } else lines
        }
      }

    override fun pids(): List<Long> = session.pids()

    override fun pause() = session.pause()

    override fun resume() = session.resume()

    override fun close() = session.close()
  }

  companion object {
    const val MIN_JAVA = AppSimulatorLauncher.MIN_JAVA

    private val CONVERSION_EVENT = JsonObject(mapOf("event" to JsonPrimitive("conversion")))

    /** Where the image installs the `android-simulator` distribution. */
    val DEFAULT_HOME = File("/opt/android-simulator")

    /**
     * `-Dcomposeai.serve.simulatorHome`, else `$ANDROID_SIMULATOR_HOME`, else the directory over
     * `$COMPOSE_PREVIEW_SIMULATOR`'s `bin/`, else [DEFAULT_HOME] when installed.
     */
    fun locateHome(
      env: (String) -> String? = System::getenv,
      sysProp: (String) -> String? = System::getProperty,
    ): File? {
      sysProp("composeai.serve.simulatorHome")
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      env(AppSimulatorLauncher.HOME_ENV)
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      env(AppSimulatorLauncher.BINARY_ENV)
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it).absoluteFile.parentFile?.parentFile
        }
      return DEFAULT_HOME.takeIf { it.isDirectory }
    }

    /** The feature version a JDK's `release` file names (`JAVA_VERSION="21.0.2"`), or null. */
    internal fun javaFeatureVersion(javaHome: File): Int? = runCatching {
      File(javaHome, "release")
        .readLines()
        .firstOrNull { it.startsWith("JAVA_VERSION=") }
        ?.substringAfter('=')
        ?.trim('"')
        ?.split('.', '-', '+')
        ?.first()
        ?.toInt()
    }
      .getOrNull()

    fun locateAndroidSdk(env: (String) -> String? = System::getenv): File? =
      listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { env(it)?.takeIf(String::isNotBlank)?.let(::File) }
        .firstOrNull { File(it, "platforms").isDirectory }
  }
}

internal fun SimulatorDevice.launcherDevice(): AppSimulatorDevice =
  when (this) {
    SimulatorDevice.PHONE -> AppSimulatorDevice.PHONE
    SimulatorDevice.WATCH -> AppSimulatorDevice.WATCH
  }

internal fun AppSimulatorDevice.serverDevice(): SimulatorDevice =
  when (this) {
    AppSimulatorDevice.PHONE -> SimulatorDevice.PHONE
    AppSimulatorDevice.WATCH -> SimulatorDevice.WATCH
  }
