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
 * The **UI-builder editor as a per-instance pin** rather than a server release
 * ([#1035](https://github.com/yschimke/compose-preview-server/issues/1035)).
 *
 * An editor-only fix used to need four steps before a user saw it: a compose-ui-builder release,
 * a version bump here, a server release and a deploy — even when no server code changed. The
 * catalogs had already escaped that: which catalogs a box serves is `catalogs.json` under `/config`,
 * and their runtimes are fetched live. What stayed baked in was the editor itself, unpacked into
 * the distribution by `unpackUiBuilderWeb`.
 *
 * Now the editor is served like a catalog runtime: a versioned, immutable archive named by the
 * instance's own config.
 *
 * ```json
 * { "editor": { "version": "3.48.0", "sha256": "<64 hex>" }, "catalogs": [ … ] }
 * ```
 *
 * - **Publish.** compose-ui-builder attaches `compose-preview-ui-builder-web-<v>.zip` (and its
 *   `.sha256`) to each GitHub release — public, credential-free, and the same asset this build's
 *   ivy repository already resolves.
 * - **Pin.** `editor` in `catalogs.json`, or `PUT /admin/editor` ([ServeUiBuilderEditorAdmin]),
 *   which verifies the archive before it writes the pin. Rolling back is `DELETE /admin/editor` or
 *   deleting the key.
 * - **Fetch and cache.** [ServeUiBuilderEditorStore] downloads the archive once, checks its
 *   SHA-256 against the pin, unpacks it under `/config/ui-builder-editors/`, and serves that
 *   directory in place of the bundled one. Any failure falls back to the bundled editor, which
 *   stays for first boot and offline use.
 * - **Contract.** The archive's [EditorManifest] declares the editor↔server HTTP API it speaks
 *   ([EditorManifest.serverApi]); a pin outside [SUPPORTED_SERVER_API] is refused.
 *
 * A pin takes effect at **startup**. The editor directory is read by several lazily built caches in
 * [ServeHttpServer] (the bundle version, the icon cache, the new-design fixture), and swapping it
 * under them would serve a page from one editor with assets from another. A restart is cheap
 * beside the release-and-deploy chain this replaces, and the admin API says when one is owed.
 *
 * The JVM-side pieces — runtime, export and render-bundle jars — stay build-time dependencies: they
 * run in-process or in the render subprocess. [SUPPORTED_SERVER_API] is what keeps a pinned editor
 * within range of them.
 */
internal object ServeUiBuilderEditor {
  /** The manifest compose-ui-builder writes into the archive root. */
  const val MANIFEST_FILE: String = "ui-builder-web.json"

  const val MANIFEST_SCHEMA: String = "compose-ui-builder-web/v1"

  /**
   * The editor↔server HTTP API versions this server speaks.
   *
   * Bumped — in step with `serverApi` in compose-ui-builder's `ui-builder-web/build.gradle.kts` —
   * only when a change to the routes the editor calls would break an editor that does not know
   * about it. Widen the set while both shapes are served, so an instance can move its pin across
   * the change without a flag day.
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
   * Why an editor carrying [manifest] cannot be served by this server, or null when it can.
   *
   * [pinnedVersion] is what the operator asked for. The digest already pins the bytes; this catches
   * the other mistake — a URL override or a copy-pasted digest that names a different release than
   * the version the operator believes is serving.
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
 * Fetches, verifies and caches pinned editor archives under [cacheRoot].
 *
 * Every archive is content-addressed by its pin (`<version>-<sha256 prefix>`), unpacked into a
 * staging directory and moved into place only once complete, so a crash or a failed check never
 * leaves a half-unpacked editor that a later boot would serve. A cache hit is a directory rename
 * away from the last successful fetch — which is what keeps a restart, and so a rollback, fast.
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
   * [pin]'s editor directory, fetching and verifying it first when it is not cached yet.
   *
   * Synchronized: two admin calls pinning the same version at once would otherwise race each other
   * into the same staging names and the same final rename.
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
   * Delete cached editors other than [keep] and the [retain] most recently installed others.
   *
   * Retaining a few is what makes a rollback a restart rather than a re-download; retaining all of
   * them would let `/config` fill with 40 MB archives nobody pins any more.
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
          if (total > MAX_ARCHIVE_BYTES) throw IOException("editor archive exceeds $MAX_ARCHIVE_BYTES bytes")
          sha.update(buffer, 0, n)
          out.write(buffer, 0, n)
        }
      }
    }
    return sha.digest().joinToString("") { "%02x".format(it) }
  }

  /**
   * Unpack [archive] into [into], refusing anything that could land outside it.
   *
   * The digest makes the archive trusted-by-pin, not trusted-by-construction: a URL override can
   * name any zip whose hash the operator pasted, so path traversal and zip bombs are still checked.
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
        if (
          name.startsWith("/") || name.contains('\\') || name.split('/').any { it == ".." }
        ) {
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
 * Which editor this server is serving, and what `catalogs.json` pins — the answer to
 * `GET /admin/editor`, and the pair [ServeUiBuilderEditorAdmin] compares to say whether a restart
 * is owed.
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
 * `GET`/`PUT`/`DELETE /admin/editor`: change the instance's editor pin.
 *
 * A pin is fetched and verified **before** it is written. A typo'd version or a wrong digest is
 * therefore refused with the reason, rather than written, silently failing at the next boot and
 * leaving the operator believing a fix shipped. Writing a pin that verified also leaves it cached,
 * so the restart that applies it does not download anything.
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

    /** Already pinned exactly so (or nothing to remove) — a 409, which a reconcile reads as done. */
    data class Conflict(val reason: String) : Result

    /** No config file to write — a pin that would not survive the restart that applies it. */
    data class Unavailable(val reason: String) : Result
  }

  /** The pin `catalogs.json` holds now, which is what the next start will serve. */
  fun configuredPin(): ServeCatalogsConfig.EditorPin? =
    runCatching { configFile?.load()?.editor }.getOrNull()

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
    onLog("serve: UI-builder editor pin removed via admin API; the bundled editor serves next start")
    return Result.Ok(null, restartRequired = servingState().servingPin != null)
  }

  private companion object {
    const val NO_FILE = "no catalogs config file is configured; pass --catalogs-file to pin an editor"
  }
}
