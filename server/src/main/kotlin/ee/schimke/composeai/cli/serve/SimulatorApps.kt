package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A device of a simulator session. The ids are the CLI's own `--pair` device names. */
enum class SimulatorDevice(val id: String) {
  PHONE("phone"),
  WATCH("watch");

  companion object {
    fun of(id: String?): SimulatorDevice? = entries.firstOrNull { it.id == id }
  }
}

/**
 * What one session runs: a phone, a watch, or both paired over the simulated Wearable Data Layer
 * (`android-simulator phone.apk --pair watch.apk`).
 */
enum class SimulatorMode(val id: String, val devices: List<SimulatorDevice>) {
  PHONE("phone", listOf(SimulatorDevice.PHONE)),
  WATCH("watch", listOf(SimulatorDevice.WATCH)),
  PAIRED("paired", listOf(SimulatorDevice.PHONE, SimulatorDevice.WATCH));

  companion object {
    fun of(id: String?): SimulatorMode? = entries.firstOrNull { it.id == id }

    /** The modes an app with these APKs can run, in the order a picker offers them. */
    fun available(devices: Set<SimulatorDevice>): List<SimulatorMode> = entries.filter {
      devices.containsAll(it.devices)
    }
  }
}

/**
 * How an operator lets people run a registered app. Every field has a safe default: only the
 * operator may start a session, the app has no network, and it cannot be embedded.
 */
@Serializable
data class SimulatorAppConfig(
  val label: String? = null,
  val description: String? = null,
  /**
   * Who may start a session on this server's own page and API: [ACCESS_OPERATOR] (the admin token)
   * or [ACCESS_SIGNED_IN] (any signed-in account). Embeds are governed by [embed] alone.
   */
  val access: String = ACCESS_OPERATOR,
  /**
   * The network the app starts on, as `android-simulator --network` takes it. `offline` by default:
   * a simulated app's connections are real sockets from this host.
   */
  val network: String = "offline",
  /** `--launcher`: a home screen (phone) or watch face and system UI (watch) under the app. */
  val launcher: Boolean = false,
  /** The longest a session may live, in minutes, however active. */
  val maxSessionMinutes: Int = 15,
  val embed: SimulatorEmbedPolicy = SimulatorEmbedPolicy(),
) {
  /** Why this config cannot be stored, or null. */
  fun problem(): String? {
    if ((label?.length ?: 0) > 80) return "label is longer than 80 characters"
    if ((description?.length ?: 0) > 500) return "description is longer than 500 characters"
    if (access !in ACCESS_LEVELS) return "access must be one of $ACCESS_LEVELS"
    if (network !in NETWORK_MODES) return "network must be one of $NETWORK_MODES"
    if (maxSessionMinutes !in 1..MAX_SESSION_MINUTES) {
      return "maxSessionMinutes must be 1..$MAX_SESSION_MINUTES"
    }
    return embed.problem()
  }

  companion object {
    const val ACCESS_OPERATOR = "operator"
    const val ACCESS_SIGNED_IN = "signed-in"
    val ACCESS_LEVELS = listOf(ACCESS_OPERATOR, ACCESS_SIGNED_IN)

    /** `android-simulator --network`, without `airplane` (a person can turn that on). */
    val NETWORK_MODES = listOf("offline", "wifi", "cellular", "ethernet", "vpn", "captive")

    const val MAX_SESSION_MINUTES = 120
  }
}

/**
 * Whether, and where, an app may be embedded in another site's page ([SimulatorEmbed]). Off by
 * default; on, only [origins] may frame it (`Content-Security-Policy: frame-ancestors`).
 */
@Serializable
data class SimulatorEmbedPolicy(
  val enabled: Boolean = false,
  /** Exact origins, `https://host[:port]` (or `http://localhost[:port]` for development). */
  val origins: List<String> = emptyList(),
  /**
   * Anyone who loads an allowed embed may start a session, without a token. Off: the embedding site
   * mints a short-lived signed token server-side (`POST /admin/simulator/apps/{id}/embed-tokens`)
   * and puts it in the iframe's URL.
   */
  val anonymous: Boolean = false,
  /** An embedded session's lifetime, in minutes: short, since a stranger holds it. */
  val maxSessionMinutes: Int = 5,
  /** Embedded sessions this app may run at once, across every viewer. */
  val maxConcurrentSessions: Int = 1,
) {
  fun problem(): String? {
    if (!enabled) return null
    if (origins.isEmpty())
      return "embed.origins must list at least one origin when embed is enabled"
    if (origins.size > 20) return "embed.origins lists more than 20 origins"
    origins
      .firstOrNull { !isOrigin(it) }
      ?.let {
        return "embed.origins entry '$it' is not an origin (https://host[:port], or http://localhost)"
      }
    if (maxSessionMinutes !in 1..30) return "embed.maxSessionMinutes must be 1..30"
    if (maxConcurrentSessions !in 1..16) return "embed.maxConcurrentSessions must be 1..16"
    return null
  }

  companion object {
    private val HTTPS_ORIGIN = Regex("""https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?""")
    private val LOCAL_ORIGIN = Regex("""http://(localhost|127\.0\.0\.1)(:[0-9]{1,5})?""")

    /** An origin a CSP `frame-ancestors` source can name exactly: no path, no wildcard. */
    fun isOrigin(value: String): Boolean =
      HTTPS_ORIGIN.matches(value) || LOCAL_ORIGIN.matches(value)
  }
}

/** One installed APK of an app. */
@Serializable
data class SimulatorApkV1(
  val device: String,
  val packageName: String,
  val sha256: String,
  val sizeBytes: Long,
  val installedAtEpochMillis: Long,
)

@Serializable
data class SimulatorAppV1(
  val schema: String = "compose-preview/simulator-app/v1",
  val appId: String,
  val config: SimulatorAppConfig,
  val apks: List<SimulatorApkV1>,
  /** [SimulatorMode] ids this app can start, from which APKs it has. */
  val modes: List<String>,
  /** What will not work as the operator may expect, e.g. a pair the Data Layer cannot join. */
  val warnings: List<String> = emptyList(),
)

/**
 * The operator's registry of APKs the simulator may run. **Nothing else runs**: a session names an
 * app id, never an APK, so the only code a simulator executes is code an operator uploaded with the
 * admin token. Robolectric is not a sandbox — the app's code runs in an ordinary JVM on this host —
 * which is why registration is an operator act and not a visitor's.
 *
 * Layout under [root]: `<appId>/config.json`, and per device `<appId>/<device>.json` naming the
 * content-addressed `<appId>/apks/<sha256>.apk`. A replaced APK's file is kept until the next
 * replacement, so a session that started on it is not pulled out from under.
 */
class SimulatorAppRegistry(
  private val root: File,
  private val clock: () -> Long = System::currentTimeMillis,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  sealed interface Result {
    data class Ok(val app: SimulatorAppV1) : Result

    /** The request cannot be stored: a 400. */
    data class Invalid(val reason: String) : Result

    data object NotFound : Result
  }

  private val lock = Any()

  fun list(): List<SimulatorAppV1> =
    synchronized(lock) {
      root
        .listFiles { f -> f.isDirectory && isAppId(f.name) }
        .orEmpty()
        .sortedBy { it.name }
        .mapNotNull { read(it.name) }
    }

  fun get(appId: String): SimulatorAppV1? =
    if (!isAppId(appId)) null else synchronized(lock) { read(appId) }

  /** The APK a session of [appId] runs on [device], or null when none is installed. */
  fun apkFile(appId: String, device: SimulatorDevice): File? =
    if (!isAppId(appId)) null
    else
      synchronized(lock) {
        val meta = readApk(appId, device) ?: return null
        blob(appId, meta.sha256).takeIf { it.isFile }
      }

  /** Create the app, or replace its config; its APKs are untouched. */
  fun putConfig(appId: String, config: SimulatorAppConfig): Result {
    if (!isAppId(appId)) return Result.Invalid(APP_ID_RULE)
    config.problem()?.let {
      return Result.Invalid(it)
    }
    return synchronized(lock) {
      val dir = appDir(appId).apply { mkdirs() }
      writeAtomically(
        File(dir, CONFIG_FILE),
        JSON.encodeToString(SimulatorAppConfig.serializer(), config),
      )
      Result.Ok(read(appId)!!)
    }
  }

  /**
   * Validate and install [bytes] as [appId]'s [device] APK, creating the app with the default
   * config when it has none. A phone slot refuses a watch-only APK and the watch slot refuses one
   * that does not require `android.hardware.type.watch`: the CLI picks the form factor from the
   * manifest, so a mismatch would boot the wrong device.
   */
  fun installApk(appId: String, device: SimulatorDevice, bytes: ByteArray): Result {
    if (!isAppId(appId)) return Result.Invalid(APP_ID_RULE)
    if (bytes.isEmpty()) return Result.Invalid("request body is empty; expected an APK")
    return synchronized(lock) {
      val dir = appDir(appId).apply { mkdirs() }
      val blobs = File(dir, APK_DIR).apply { mkdirs() }
      val temp = Files.createTempFile(blobs.toPath(), ".upload-", ".apk.tmp").toFile()
      try {
        temp.writeBytes(bytes)
        val info =
          inspectApk(temp)
            ?: return Result.Invalid(
              "not an APK: expected a zip with AndroidManifest.xml and classes*.dex",
            )
        if (device == SimulatorDevice.WATCH && !info.watch) {
          return Result.Invalid(
            "${info.packageName} does not declare android.hardware.type.watch; install it as the phone app",
          )
        }
        if (device == SimulatorDevice.PHONE && info.watch) {
          return Result.Invalid("${info.packageName} is a Wear OS app; install it as the watch app")
        }
        val sha = sha256(temp)
        val previous = readApk(appId, device)
        move(temp, blob(appId, sha))
        val meta =
          SimulatorApkV1(
            device = device.id,
            packageName = info.packageName,
            sha256 = sha,
            sizeBytes = bytes.size.toLong(),
            installedAtEpochMillis = clock(),
          )
        writeAtomically(
          apkMeta(appId, device),
          JSON.encodeToString(SimulatorApkV1.serializer(), meta),
        )
        if (!File(dir, CONFIG_FILE).isFile) {
          writeAtomically(
            File(dir, CONFIG_FILE),
            JSON.encodeToString(SimulatorAppConfig.serializer(), SimulatorAppConfig()),
          )
        }
        prune(appId, keep = setOfNotNull(sha, previous?.sha256) + otherDeviceShas(appId, device))
        onLog(
          "serve: simulator app $appId: installed ${device.id} APK ${info.packageName} sha256=$sha",
        )
        Result.Ok(read(appId)!!)
      } finally {
        temp.delete()
      }
    }
  }

  fun removeApk(appId: String, device: SimulatorDevice): Result {
    if (!isAppId(appId)) return Result.NotFound
    return synchronized(lock) {
      val meta = apkMeta(appId, device)
      if (!meta.isFile) return Result.NotFound
      meta.delete()
      Result.Ok(read(appId) ?: return Result.NotFound)
    }
  }

  fun delete(appId: String): Result {
    if (!isAppId(appId)) return Result.NotFound
    return synchronized(lock) {
      val app = read(appId) ?: return Result.NotFound
      appDir(appId).deleteRecursively()
      onLog("serve: simulator app $appId deleted")
      Result.Ok(app)
    }
  }

  private fun read(appId: String): SimulatorAppV1? {
    val configFile = File(appDir(appId), CONFIG_FILE)
    if (!configFile.isFile) return null
    val config = runCatching {
      JSON.decodeFromString(SimulatorAppConfig.serializer(), configFile.readText())
    }
      .getOrElse {
        return null
      }
    val apks = SimulatorDevice.entries.mapNotNull { readApk(appId, it) }
    val packages = apks.map { it.packageName }.distinct()
    val warnings =
      if (apks.size == 2 && packages.size == 2)
        listOf(
          "the phone (${packages[0]}) and watch (${packages[1]}) apps have different packages: " +
            "the Wearable Data Layer delivers messages and data only between apps of the same " +
            "package, on a device and in the simulator, so a paired session's apps will not hear " +
            "each other",
        )
      else emptyList()
    return SimulatorAppV1(
      warnings = warnings,
      appId = appId,
      config = config,
      apks = apks,
      modes =
        SimulatorMode.available(apks.mapNotNull { SimulatorDevice.of(it.device) }.toSet()).map {
          it.id
        },
    )
  }

  private fun readApk(appId: String, device: SimulatorDevice): SimulatorApkV1? {
    val file = apkMeta(appId, device)
    if (!file.isFile) return null
    return runCatching { JSON.decodeFromString(SimulatorApkV1.serializer(), file.readText()) }
      .getOrNull()
      ?.takeIf { blob(appId, it.sha256).isFile }
  }

  private fun otherDeviceShas(appId: String, device: SimulatorDevice): Set<String> =
    SimulatorDevice.entries
      .filter { it != device }
      .mapNotNull { readApk(appId, it)?.sha256 }
      .toSet()

  private fun prune(appId: String, keep: Set<String>) {
    File(appDir(appId), APK_DIR)
      .listFiles { f -> f.name.endsWith(".apk") && f.name.removeSuffix(".apk") !in keep }
      .orEmpty()
      .forEach { it.delete() }
  }

  private fun appDir(appId: String) = File(root, appId)

  private fun apkMeta(appId: String, device: SimulatorDevice) =
    File(appDir(appId), "${device.id}.json")

  private fun blob(appId: String, sha: String) = File(File(appDir(appId), APK_DIR), "$sha.apk")

  companion object {
    const val MAX_APK_BYTES: Long = 512L * 1024 * 1024
    private const val CONFIG_FILE = "config.json"
    private const val APK_DIR = "apks"
    private const val WATCH_FEATURE = "android.hardware.type.watch"
    private val APP_ID = Regex("[a-z0-9][a-z0-9-]{0,62}")
    const val APP_ID_RULE =
      "an app id is 1-63 lowercase letters, digits and dashes, starting with a letter or digit"

    private val JSON = Json {
      prettyPrint = true
      encodeDefaults = true
      ignoreUnknownKeys = true
    }

    fun isAppId(value: String): Boolean = APP_ID.matches(value)

    internal data class ApkInfo(val packageName: String, val watch: Boolean)

    /**
     * The facts the registry needs from an APK, or null when it is not one. `watch` is the
     * manifest's string pool naming `android.hardware.type.watch`, which a Wear app's required
     * `<uses-feature>` puts there.
     */
    internal fun inspectApk(apk: File): ApkInfo? = runCatching {
      ZipFile(apk).use { zip ->
        val manifest = zip.getEntry("AndroidManifest.xml") ?: return null
        val hasDex =
          zip.entries().asSequence().any {
            !it.isDirectory && it.name.startsWith("classes") && it.name.endsWith(".dex")
          }
        if (!hasDex) return null
        val bytes = zip.getInputStream(manifest).readBytes()
        if (!ServeDwfRuntimeAdmin.isBinaryXml(bytes)) return null
        val pkg = ServeDwfRuntimeAdmin.readBinaryXmlPackageName(bytes)?.takeIf { it.isNotBlank() }
        ApkInfo(
          packageName = pkg ?: return null,
          watch = ServeDwfRuntimeAdmin.binaryXmlContainsString(bytes, WATCH_FEATURE),
        )
      }
    }
      .getOrNull()

    private fun sha256(file: File): String {
      val md = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
          val r = input.read(buf)
          if (r <= 0) break
          md.update(buf, 0, r)
        }
      }
      return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeAtomically(target: File, text: String) {
      val temp = Files.createTempFile(target.parentFile.toPath(), ".write-", ".tmp").toFile()
      temp.writeText(text)
      move(temp, target)
    }

    private fun move(source: File, target: File) {
      try {
        Files.move(
          source.toPath(),
          target.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }
}
