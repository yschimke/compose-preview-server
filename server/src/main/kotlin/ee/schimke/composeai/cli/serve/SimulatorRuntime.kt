package ee.schimke.composeai.cli.serve

import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

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

  /** Every OS process of this session (the CLI and its simulator JVMs), for measuring it. */
  fun pids(): List<Long> = emptyList()

  /**
   * Freeze the whole process tree (`SIGSTOP`): an idle session then costs no CPU at all, keeps its
   * memory, and [resume]s to a frame in about 40 ms (measured; `docs/design/SIMULATOR.md`).
   */
  fun pause() {}

  fun resume() {}
}

/**
 * How the server starts simulators. The production one is [CliSimulatorRuntime]; tests use a fake.
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
    const val UNAVAILABLE_CLI = "simulator_cli_missing"
    const val UNAVAILABLE_ANDROID_SDK = "simulator_android_sdk_missing"
    const val UNAVAILABLE_JAVA = "simulator_java_unsupported"
  }
}

/**
 * Runs compose-ai-tools' released `compose-preview android-simulator` with `--control stdio
 * --headless`, one process per session. The CLI is the simulator's public, documented interface
 * (`docs/SIMULATOR.md` there): it converts and caches the APK by content, picks the form factor
 * from the manifest, provisions Robolectric's `android-all`, and with `--pair` runs the phone and
 * the watch as two JVMs over a Data Layer hub, routing each command by its `"device"` and tagging
 * every line it prints with one. Nothing of that is restated here.
 *
 * The process gets a **clean environment** — the server's holds operator secrets, and the app can
 * read its own — and, when [sandbox] is active, runs inside the same jail the playground uses.
 * Under a jail the one writable path is [stateDir], which is also the CLI's `HOME` and cache, so a
 * converted APK stays converted across sessions.
 */
class CliSimulatorRuntime(
  private val cli: () -> File? = ::locateCli,
  private val androidSdk: () -> File? = ::locateAndroidSdk,
  private val javaHome: File = File(System.getProperty("java.home")),
  /** The CLI's state when jailed: its home, its conversion cache. */
  private val stateDir: File,
  /** The registry's root, bound read-only into a jail. */
  private val appsDir: File,
  private val sandbox: ee.schimke.composeai.cli.serve.PlaygroundSandbox = PlaygroundSandbox.NONE,
  /** `--max-heap` for each simulator JVM; null lets the CLI choose (an eighth of the machine). */
  private val maxHeap: String? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : SimulatorRuntime {

  override fun availability(): SimulatorRuntime.Availability {
    val cliFile = cli()
    if (cliFile == null || !cliFile.canExecute()) {
      return SimulatorRuntime.Availability.Unavailable(
        SimulatorRuntime.UNAVAILABLE_CLI,
        "the compose-preview CLI is not installed on this server (set simulator.cli to its " +
          "bin/compose-preview, or \$$CLI_ENV)",
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
    return SimulatorRuntime.Availability.Ready
  }

  override fun hostRoots(): List<String> =
    listOfNotNull(
      stateDir.absolutePath,
      appsDir.absolutePath,
      cli()?.absoluteFile?.parentFile?.parentFile?.absolutePath,
      androidSdk()?.absolutePath,
      javaHome.absolutePath,
      System.getProperty("user.home"),
      System.getProperty("java.io.tmpdir"),
    )

  override fun start(
    launch: SimulatorLaunch,
    onEvent: (SimulatorDevice, JsonObject) -> Unit,
  ): SimulatorProcess {
    val cliFile = checkNotNull(cli()) { "compose-preview CLI not found" }
    val sdk = checkNotNull(androidSdk()) { "Android SDK not found" }
    stateDir.mkdirs()
    val argv =
      jailPrefix(cliFile, sdk) +
        environment(sdk).map { (k, v) -> "$k=$v" }.let { listOf("/usr/bin/env", "-i") + it } +
        command(cliFile, launch, javaHome, maxHeap)
    onLog("serve: simulator ${launch.sessionId}: starting ${launch.mode.id} (${argv.size} args)")
    val process =
      ProcessBuilder(argv)
        .directory(stateDir)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .also { it.environment().keys.retainAll(setOf("PATH")) }
        .start()
    return CliProcess(launch, process, onEvent, onLog)
  }

  private fun jailPrefix(cliFile: File, sdk: File): List<String> {
    if (!sandbox.isActive) return emptyList()
    val cliHome = cliFile.absoluteFile.parentFile.parentFile
    val readOnly =
      listOfNotNull(
        cliHome,
        sdk,
        appsDir,
        File(System.getProperty("user.home"), ".m2/repository").takeIf { it.isDirectory },
      )
    return sandbox.command(
      PlaygroundSandbox.Paths(workDir = stateDir, readOnly = readOnly, javaHome = javaHome),
    )
  }

  /** Everything the CLI's process tree sees of an environment: no tokens, no secrets. */
  internal fun environment(sdk: File): Map<String, String> {
    val userHome = System.getProperty("user.home")
    val env =
      linkedMapOf(
        "PATH" to "${File(javaHome, "bin").absolutePath}:/usr/local/bin:/usr/bin:/bin",
        "HOME" to if (sandbox.isActive) stateDir.absolutePath else userHome,
        "LANG" to "C.UTF-8",
        "TZ" to (System.getenv("TZ") ?: "UTC"),
        "JAVA_HOME" to javaHome.absolutePath,
        "ANDROID_HOME" to sdk.absolutePath,
        "ANDROID_SDK_ROOT" to sdk.absolutePath,
        "COMPOSE_PREVIEW_SIMULATOR_JAVA" to javaHome.absolutePath,
      )
    if (sandbox.isActive) {
      // A JVM's user.home comes from the passwd entry, not HOME, and a jail leaves only the state
      // dir writable: point every JVM of the tree there (the CLI, its converter, the simulators),
      // and at the server user's Maven repository, bound read-only, for the prefetched android-all.
      env["JAVA_TOOL_OPTIONS"] =
        "-Duser.home=${stateDir.absolutePath} " +
          "-Dmaven.repo.local=${File(userHome, ".m2/repository").absolutePath}"
    }
    return env
  }

  private class CliProcess(
    private val launch: SimulatorLaunch,
    private val process: Process,
    private val onEvent: (SimulatorDevice, JsonObject) -> Unit,
    private val onLog: (String) -> Unit,
  ) : SimulatorProcess {
    private val writer =
      BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val ids = AtomicLong(1)
    private val stderr = ArrayDeque<String>()
    private val paired = launch.mode == SimulatorMode.PAIRED

    init {
      daemon("simulator-${launch.sessionId}-out") {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach(::onLine)
        }
        val ended = SimulatorCommandException("the simulator exited (${exitDescription()})")
        pending.values.forEach { it.completeExceptionally(ended) }
        pending.clear()
      }
      daemon("simulator-${launch.sessionId}-err") {
        process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
          lines.forEach { line ->
            synchronized(stderr) {
              if (stderr.size == STDERR_LINES) stderr.removeFirst()
              stderr.addLast(line.take(2_000))
            }
          }
        }
      }
    }

    private fun exitDescription(): String =
      if (process.isAlive) "its output closed" else "exit ${process.waitFor()}"

    private fun onLine(line: String) {
      val trimmed = line.trim()
      if (!trimmed.startsWith("{")) return
      val obj = runCatching { JSON.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return
      val device =
        if (paired) SimulatorDevice.of((obj["device"] as? JsonPrimitive)?.contentOrNull)
        else launch.mode.devices.single()
      val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
      if (id != null && obj.containsKey("ok")) {
        pending.remove(id)?.complete(obj)
        return
      }
      if (obj.containsKey("event") && device != null) {
        runCatching { onEvent(device, obj) }.onFailure { onLog("serve: simulator event: $it") }
      }
    }

    override val alive: Boolean
      get() = process.isAlive

    override fun command(
      device: SimulatorDevice,
      command: JsonObject,
      timeoutMillis: Long,
    ): JsonElement? {
      if (!process.isAlive) throw SimulatorCommandException("the simulator is not running")
      val id = ids.getAndIncrement()
      val future = CompletableFuture<JsonObject>()
      pending[id] = future
      val line =
        JsonObject(
            buildMap {
              put("id", JsonPrimitive(id))
              if (paired) put("device", JsonPrimitive(device.id))
              putAll(command)
            },
          )
          .toString()
      try {
        synchronized(writer) {
          writer.write(line)
          writer.newLine()
          writer.flush()
        }
      } catch (e: java.io.IOException) {
        pending.remove(id)
        throw SimulatorCommandException("the simulator is not accepting commands: ${e.message}")
      }
      val reply =
        try {
          future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
          pending.remove(id)
          throw SimulatorCommandException(
            "${commandName(command)} timed out after ${timeoutMillis}ms",
          )
        } catch (e: java.util.concurrent.ExecutionException) {
          throw (e.cause as? SimulatorCommandException) ?: SimulatorCommandException("${e.cause}")
        }
      if ((reply["ok"] as? JsonPrimitive)?.booleanOrNull != true) {
        val error = (reply["error"] as? JsonPrimitive)?.contentOrNull ?: "unknown error"
        throw SimulatorCommandException("${commandName(command)}: $error")
      }
      return reply["result"]
    }

    override fun stderrTail(): List<String> = synchronized(stderr) { stderr.toList() }

    override fun pids(): List<Long> =
      listOf(process.pid()) + process.descendants().map { it.pid() }.toList()

    @Volatile private var paused = false

    override fun pause() = signal("STOP").also { paused = true }

    override fun resume() {
      if (paused) signal("CONT")
      paused = false
    }

    /** `kill -<sig>` to the tree: the JDK sends only TERM and KILL itself. */
    private fun signal(sig: String) {
      val pids = pids()
      if (pids.isEmpty() || !process.isAlive) return
      runCatching {
        ProcessBuilder(listOf("kill", "-$sig") + pids.map { it.toString() })
          .redirectErrorStream(true)
          .start()
          .waitFor(5, TimeUnit.SECONDS)
      }
    }

    override fun close() {
      // A frozen tree cannot read the closed stdin and exit: thaw it first.
      resume()
      runCatching { synchronized(writer) { writer.close() } }
      // Closing stdin ends the CLI and both simulators; whatever survives a grace is killed, the
      // whole tree, since the CLI's children are JVMs of their own.
      if (
        !runCatching { process.waitFor(CLOSE_GRACE_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
      ) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
      }
      process.descendants().forEach { it.destroyForcibly() }
    }

    private fun commandName(command: JsonObject) =
      (command["cmd"] as? JsonPrimitive)?.contentOrNull ?: "command"
  }

  companion object {
    const val CLI_ENV = "COMPOSE_PREVIEW_CLI"
    const val MIN_JAVA = 21
    private const val STDERR_LINES = 60
    private const val CLOSE_GRACE_SECONDS = 5L
    private val JSON = Json { ignoreUnknownKeys = true }

    /** Where the image installs the CLI. */
    val DEFAULT_CLI = File("/opt/compose-preview-cli/bin/compose-preview")

    /** The CLI's command line for [launch]. Pure, for tests. */
    fun command(
      cli: File,
      launch: SimulatorLaunch,
      javaHome: File,
      maxHeap: String?,
    ): List<String> = buildList {
      add(cli.absolutePath)
      add("android-simulator")
      when (launch.mode) {
        SimulatorMode.PHONE -> add(launch.apks.getValue(SimulatorDevice.PHONE).absolutePath)
        SimulatorMode.WATCH -> add(launch.apks.getValue(SimulatorDevice.WATCH).absolutePath)
        SimulatorMode.PAIRED -> {
          add(launch.apks.getValue(SimulatorDevice.PHONE).absolutePath)
          add("--pair")
          add(launch.apks.getValue(SimulatorDevice.WATCH).absolutePath)
        }
      }
      add("--control")
      add("stdio")
      add("--headless")
      add("--hide-agent")
      add("--network")
      add(launch.network)
      if (launch.launcher) add("--launcher")
      maxHeap?.let {
        add("--max-heap")
        add(it)
      }
      add("--java")
      add(javaHome.absolutePath)
    }

    fun locateCli(
      env: (String) -> String? = System::getenv,
      sysProp: (String) -> String? = System::getProperty,
    ): File? {
      sysProp("composeai.serve.simulatorCli")
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      env(CLI_ENV)
        ?.takeIf { it.isNotBlank() }
        ?.let {
          return File(it)
        }
      if (DEFAULT_CLI.canExecute()) return DEFAULT_CLI
      return env("PATH")
        ?.split(File.pathSeparator)
        ?.map { File(it, "compose-preview") }
        ?.firstOrNull { it.canExecute() }
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

    private fun daemon(name: String, body: () -> Unit) =
      Thread({ runCatching(body) }, name).apply {
        isDaemon = true
        start()
      }
  }
}
