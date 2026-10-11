package ee.schimke.composeai.cli.serve

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The UI-builder editor as a per-instance pin rather than part of a server release (#1035), served
 * like a catalog runtime: a versioned, immutable archive named by the instance's own config.
 *
 * ```json
 * { "editor": { "version": "3.48.0", "sha256": "<64 hex>" }, "catalogs": [ … ] }
 * ```
 *
 * - Publish: compose-ui-builder attaches `compose-preview-ui-builder-web-<v>.zip` (and `.sha256`)
 *   to each GitHub release.
 * - Pin: `editor` in `catalogs.json`, or `PUT /admin/editor` ([ServeUiBuilderEditorAdmin]), which
 *   verifies first. Roll back with `DELETE /admin/editor` or by removing the key.
 * - Fetch and cache: [ServeUiBuilderEditorStore] downloads once, checks SHA-256, unpacks under
 *   `/config/ui-builder-editors/` and serves it instead of the bundled editor; any failure falls
 *   back to the bundled one.
 * - Contract: [EditorManifest.serverApi] must be in [SUPPORTED_SERVER_API].
 *
 * A pin takes effect at startup: several lazy caches in [ServeHttpServer] read the editor
 * directory, and swapping it live would mix editors. JVM-side runtime, export and render jars stay
 * build-time dependencies, which [SUPPORTED_SERVER_API] keeps the editor compatible with.
 */
object ServeUiBuilderEditor {
  /** The manifest compose-ui-builder writes into the archive root. */
  const val MANIFEST_FILE: String = "ui-builder-web.json"

  const val MANIFEST_SCHEMA: String = "compose-ui-builder-web/v1"

  /**
   * Editor↔server HTTP API versions this server speaks. Bumped with `serverApi` in
   * compose-ui-builder's `ui-builder-web/build.gradle.kts` only for breaking route changes; widen
   * the set while both shapes are served.
   */
  val SUPPORTED_SERVER_API: Set<Int> = setOf(1)

  /** Where compose-ui-builder publishes the editor archive for [version]. */
  fun releaseUrl(version: String): String =
    "https://github.com/yschimke/compose-ui-builder/releases/download/" +
      "v$version/compose-preview-ui-builder-web-$version.zip"

  /** What the archive at the root of an editor directory says about itself. */
  @Serializable
  data class EditorManifest(
    val schema: String = MANIFEST_SCHEMA,
    val version: String,
    /** The editor↔server HTTP API this editor was built against. See [SUPPORTED_SERVER_API]. */
    val serverApi: Int,
  )

  private val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  /** The manifest in [dir], or null when absent or unreadable (an editor predating it). */
  fun readManifest(dir: File): EditorManifest? {
    val file = File(dir, MANIFEST_FILE)
    if (!file.isFile) return null
    return runCatching { JSON.decodeFromString(EditorManifest.serializer(), file.readText()) }
      .getOrNull()
  }

  /**
   * Why an editor carrying [manifest] can't be served, or null. [pinnedVersion] catches a URL
   * override or pasted digest naming a different release than the operator believes.
   */
  fun contractProblem(manifest: EditorManifest, pinnedVersion: String?): String? =
    when {
      manifest.schema != MANIFEST_SCHEMA ->
        "editor manifest schema '${manifest.schema}' is not '$MANIFEST_SCHEMA'"
      pinnedVersion != null && manifest.version != pinnedVersion ->
        "archive is editor ${manifest.version}, not the pinned $pinnedVersion"
      manifest.serverApi !in SUPPORTED_SERVER_API ->
        "editor ${manifest.version} speaks server API ${manifest.serverApi}; this server supports " +
          SUPPORTED_SERVER_API.sorted().joinToString(", ")
      else -> null
    }
}

/**
 * Fetches, verifies and caches pinned editor archives under [cacheRoot], content-addressed by pin
 * (`<version>-<sha256 prefix>`) and unpacked into staging then moved into place, so a crash never
 * leaves a half-unpacked editor. Cache hits keep restarts and rollbacks fast.
 */
class ServeUiBuilderEditorStore(
  private val cacheRoot: File,
  private val fetch: (url: String) -> InputStream = ::httpFetch,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  sealed interface Result {
    /** The editor is unpacked at [dir]. [warning] notes a non-fatal gap (no manifest). */
    data class Ready(
      val dir: File,
      val manifest: ServeUiBuilderEditor.EditorManifest?,
      val warning: String? = null,
    ) : Result

    /** The pin could not be served; [reason] says why. The bundled editor stays in force. */
    data class Failed(val reason: String) : Result
  }

  /** The directory [pin] unpacks to. Stable across restarts; distinct per version and digest. */
  fun dirFor(pin: ServeCatalogsConfig.EditorPin): File =
    File(cacheRoot, "${pin.version}-${pin.sha256.lowercase().take(16)}")

  /**
   * [pin]'s editor directory, fetched and verified first if not cached. Synchronized so concurrent
   * pins of one version don't race on staging names.
   */
  @Synchronized
  fun resolve(pin: ServeCatalogsConfig.EditorPin): Result {
    ServeCatalogsConfig.validateEditor(pin)?.let {
      return Result.Failed(it)
    }
    val target = dirFor(pin)
    if (File(target, COMPLETE_MARKER).isFile && File(target, "index.html").isFile) {
      return ready(target, pin)
    }
    return runCatching { install(pin, target) }
      .getOrElse { e -> Result.Failed(e.message ?: e.javaClass.simpleName) }
  }

  /**
   * Delete cached editors other than [keep] and the [retain] most recent, so a rollback is a
   * restart without filling `/config`.
   */
  fun prune(keep: File?, retain: Int = 2) {
    val dirs = cacheRoot.listFiles { f -> f.isDirectory } ?: return
    val keepPath = keep?.canonicalFile
    dirs
      .filter { it.canonicalFile != keepPath }
      .sortedByDescending { File(it, COMPLETE_MARKER).lastModified() }
      .drop(retain)
      .forEach { dir ->
        if (dir.deleteRecursively()) onLog("serve: pruned cached UI-builder editor ${dir.name}")
      }
  }

  private fun ready(target: File, pin: ServeCatalogsConfig.EditorPin): Result {
    val manifest = ServeUiBuilderEditor.readManifest(target)
    if (manifest == null) {
      return Result.Ready(
        target,
        null,
        warning =
          "editor ${pin.version} carries no ${ServeUiBuilderEditor.MANIFEST_FILE}; its server API " +
            "is unchecked",
      )
    }
    ServeUiBuilderEditor.contractProblem(manifest, pin.version)?.let {
      return Result.Failed(it)
    }
    return Result.Ready(target, manifest)
  }

  private fun install(pin: ServeCatalogsConfig.EditorPin, target: File): Result {
    cacheRoot.mkdirs()
    if (!cacheRoot.isDirectory) throw IOException("cannot create ${cacheRoot.path}")
    val url = pin.url ?: ServeUiBuilderEditor.releaseUrl(pin.version)
    val archive = File.createTempFile(".editor-", ".zip", cacheRoot)
    val staging = File(cacheRoot, ".staging-${target.name}-${System.nanoTime()}")
    try {
      onLog("serve: fetching UI-builder editor ${pin.version} from $url")
      val digest = download(url, archive)
      if (!digest.equals(pin.sha256, ignoreCase = true)) {
        return Result.Failed(
          "editor ${pin.version} digest mismatch: pinned ${pin.sha256.lowercase()}, got $digest"
        )
      }
      unpack(archive, staging)
      if (!File(staging, "index.html").isFile) {
        return Result.Failed("editor ${pin.version} archive has no index.html")
      }
      ServeUiBuilderEditor.readManifest(staging)?.let { manifest ->
        ServeUiBuilderEditor.contractProblem(manifest, pin.version)?.let {
          return Result.Failed(it)
        }
      }
      File(staging, COMPLETE_MARKER).writeText(pin.sha256.lowercase() + "\n")
      target.deleteRecursively()
      if (!staging.renameTo(target)) throw IOException("could not move editor into ${target.path}")
      onLog("serve: cached UI-builder editor ${pin.version} at ${target.path}")
      return ready(target, pin)
    } finally {
      archive.delete()
      if (staging.exists()) staging.deleteRecursively()
    }
  }

  /** Stream [url] into [into], returning the lowercase SHA-256 of what was written. */
  private fun download(url: String, into: File): String {
    val sha = MessageDigest.getInstance("SHA-256")
    var total = 0L
    fetch(url).use { input ->
      into.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
          val n = input.read(buffer)
          if (n < 0) break
          total += n
          if (total > MAX_ARCHIVE_BYTES)
            throw IOException("editor archive exceeds $MAX_ARCHIVE_BYTES bytes")
          sha.update(buffer, 0, n)
          out.write(buffer, 0, n)
        }
      }
    }
    return sha.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Unpack [archive] into [into], refusing path traversal and zip bombs: the digest pins the bytes
   * but a URL override can name any zip.
   */
  private fun unpack(archive: File, into: File) {
    into.mkdirs()
    val base = into.canonicalFile.toPath()
    var written = 0L
    ZipFile(archive).use { zip ->
      val entries = zip.entries().toList()
      if (entries.size > MAX_ENTRIES) throw IOException("editor archive has too many entries")
      for (entry in entries) {
        val name = entry.name
        if (name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }) {
          throw IOException("editor archive contains unsafe path '$name'")
        }
        val out = File(into, name)
        if (!out.canonicalFile.toPath().startsWith(base)) {
          throw IOException("editor archive contains unsafe path '$name'")
        }
        if (entry.isDirectory) {
          out.mkdirs()
          continue
        }
        out.parentFile.mkdirs()
        zip.getInputStream(entry).use { input ->
          out.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val n = input.read(buffer)
              if (n < 0) break
              written += n
              if (written > MAX_UNPACKED_BYTES) {
                throw IOException("editor archive unpacks past $MAX_UNPACKED_BYTES bytes")
              }
              output.write(buffer, 0, n)
            }
          }
        }
      }
    }
  }

  companion object {
    /** Written last into a staged editor; its absence means "not a finished install". */
    const val COMPLETE_MARKER: String = ".complete"

    /** The archive is ~40 MB today; these leave generous room without allowing a disk fill. */
    private const val MAX_ARCHIVE_BYTES: Long = 256L * 1024 * 1024
    private const val MAX_UNPACKED_BYTES: Long = 1024L * 1024 * 1024
    private const val MAX_ENTRIES: Int = 20_000

    private val client: OkHttpClient by lazy {
      OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    }

    /** GET [url], following GitHub's redirect to its object store. Non-2xx is an error. */
    fun httpFetch(url: String): InputStream {
      val response = client.newCall(Request.Builder().url(url).build()).execute()
      if (!response.isSuccessful) {
        response.close()
        throw IOException("GET $url answered HTTP ${response.code}")
      }
      return response.body.byteStream()
    }
  }
}

/**
 * Which editor is serving and what `catalogs.json` pins: `GET /admin/editor`'s answer, compared to
 * say whether a restart is owed.
 */
data class ServeUiBuilderEditorState(
  /** The bundled editor's version, from its manifest, or null when unknown / not packaged. */
  val bundledVersion: String?,
  /** The pin in force when this server started, or null when it serves the bundled editor. */
  val servingPin: ServeCatalogsConfig.EditorPin?,
  /** The version actually serving (the pin's, or the bundled one). */
  val servingVersion: String?,
)

/**
 * `GET`/`PUT`/`DELETE /admin/editor`: change the editor pin. A pin is fetched and verified before
 * it's written, so a bad version or digest is refused rather than failing at next boot, and the
 * restart that applies it needs no download.
 */
class ServeUiBuilderEditorAdmin(
  private val store: ServeUiBuilderEditorStore,
  private val configFile: ServeCatalogsConfigFile?,
  /** Read per call: the runner fills it in once startup has resolved the editor. */
  private val servingState: () -> ServeUiBuilderEditorState,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {
  sealed interface Result {
    /** Written. [restartRequired] when the new pin is not what this process is serving. */
    data class Ok(
      val pin: ServeCatalogsConfig.EditorPin?,
      val restartRequired: Boolean,
      val warning: String? = null,
    ) : Result

    /** A malformed pin, or one whose archive failed its digest or contract check — a 400. */
    data class Invalid(val reason: String) : Result

    /**
     * Already pinned exactly so (or nothing to remove): a 409, which a reconcile treats as done.
     */
    data class Conflict(val reason: String) : Result

    /** No config file to write — a pin that would not survive the restart that applies it. */
    data class Unavailable(val reason: String) : Result
  }

  /** The pin `catalogs.json` holds now, which is what the next start will serve. */
  fun configuredPin(): ServeCatalogsConfig.EditorPin? = runCatching {
    configFile?.load()?.editor
  }
    .getOrNull()

  fun state(): ServeUiBuilderEditorState = servingState()

  fun set(pin: ServeCatalogsConfig.EditorPin): Result {
    val file = configFile ?: return Result.Unavailable(NO_FILE)
    ServeCatalogsConfig.validateEditor(pin)?.let {
      return Result.Invalid(it)
    }
    val normalized = pin.copy(sha256 = pin.sha256.lowercase())
    if (configuredPin() == normalized) {
      return Result.Conflict("editor ${pin.version} is already pinned")
    }
    val warning =
      when (val resolved = store.resolve(normalized)) {
        is ServeUiBuilderEditorStore.Result.Failed -> return Result.Invalid(resolved.reason)
        is ServeUiBuilderEditorStore.Result.Ready -> resolved.warning
      }
    file.update { it.copy(editor = normalized) }
    onLog("serve: UI-builder editor pinned to ${pin.version} via admin API")
    return Result.Ok(normalized, restartRequired = normalized != servingState().servingPin, warning)
  }

  fun clear(): Result {
    val file = configFile ?: return Result.Unavailable(NO_FILE)
    if (configuredPin() == null) return Result.Conflict("an editor pin is not configured")
    file.update { it.copy(editor = null) }
    onLog(
      "serve: UI-builder editor pin removed via admin API; the bundled editor serves next start"
    )
    return Result.Ok(null, restartRequired = servingState().servingPin != null)
  }

  private companion object {
    const val NO_FILE =
      "no catalogs config file is configured; pass --catalogs-file to pin an editor"
  }
}
