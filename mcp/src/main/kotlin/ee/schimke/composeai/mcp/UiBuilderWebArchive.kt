package ee.schimke.composeai.mcp

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The UI-builder editor archive (`compose-preview-ui-builder-web-<v>.zip`, published as a
 * yschimke/compose-ui-builder release asset) as this MCP server reads it: its manifest, its MCP App
 * shell, and its files for the loopback asset origin ([UiBuilderAssetOrigin]).
 *
 * Read either as the ZIP itself, which is what the distribution carries (`ui-builder/` beside
 * `lib/`; ~12 MB rather than the ~45 MB it unpacks to), or as an unpacked directory, which is what
 * a local `wasmDist` or `unpackUiBuilderWeb` produces. Both answer only for files that are in the
 * archive: a name is looked up, never resolved against a filesystem path the caller chose.
 */
internal sealed class UiBuilderWebArchive : Closeable {
  /** A file in the archive: its size and a way to read it. */
  class Entry(val size: Long, val open: () -> InputStream)

  /** Where the archive was read from, for log lines. */
  abstract val source: File

  /** The file at [path] (archive-relative, `/`-separated, no leading `/`), or null. */
  abstract fun entry(path: String): Entry?

  fun readText(path: String): String? =
    entry(path)?.open?.invoke()?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

  /** `ui-builder-web.json`, or null when absent or unreadable. */
  val manifest: Manifest? by lazy {
    readText(MANIFEST_FILE)?.let { text ->
      runCatching { JSON.decodeFromString(Manifest.serializer(), text) }.getOrNull()
    }
  }

  /**
   * What the archive says about itself. `mcpApp` is the contract between the MCP App shell and the
   * server that serves it — the placeholder's name and the shell's path (compose-ui-builder#366);
   * absent in archives that predate the shell.
   */
  @Serializable
  data class Manifest(
    val schema: String = "",
    val version: String,
    val serverApi: Int? = null,
    val hostBridge: Int? = null,
    val mcpApp: Int? = null,
  )

  private class Zip(override val source: File) : UiBuilderWebArchive() {
    private val zip = ZipFile(source)

    override fun entry(path: String): Entry? {
      if (!isSafeArchivePath(path)) return null
      val entry = zip.getEntry(path)?.takeUnless { it.isDirectory } ?: return null
      return Entry(entry.size) { zip.getInputStream(entry) }
    }

    override fun close() = zip.close()
  }

  private class Directory(override val source: File) : UiBuilderWebArchive() {
    private val root = source.canonicalFile

    override fun entry(path: String): Entry? {
      if (!isSafeArchivePath(path)) return null
      val file = File(root, path).canonicalFile
      // Belt and braces after the segment check: a symlink inside an unpacked directory must not
      // take a request outside it either.
      if (!file.path.startsWith(root.path + File.separator) || !file.isFile) return null
      return Entry(file.length()) { file.inputStream() }
    }

    override fun close() = Unit
  }

  companion object {
    const val MANIFEST_FILE: String = "ui-builder-web.json"

    /** The archive name the `:mcp` distribution carries under [DIST_DIR]. */
    const val DIST_FILE: String = "compose-preview-ui-builder-web.zip"
    const val DIST_DIR: String = "ui-builder"

    /** Points at a ZIP or an unpacked archive directory, ahead of the packaged one. */
    const val PATH_ENV: String = "COMPOSE_PREVIEW_UI_BUILDER_WEB"
    const val PATH_PROPERTY: String = "composeai.mcp.uiBuilderWeb"

    private val JSON = Json { ignoreUnknownKeys = true }

    /** [file] opened as an archive, or null when it is neither a ZIP nor a directory. */
    fun open(file: File): UiBuilderWebArchive? =
      when {
        file.isDirectory -> Directory(file)
        file.isFile -> runCatching { Zip(file) }.getOrNull()
        else -> null
      }

    /**
     * The editor archive this process should use, or null when there is none: [PATH_PROPERTY] /
     * [PATH_ENV] first, then `<APP_HOME>/ui-builder/compose-preview-ui-builder-web.zip`, with the
     * app home taken from `composeai.cli.appHome` / `APP_HOME` or inferred from this class's jar
     * (`<APP_HOME>/lib/compose-preview-mcp.jar`) — the order `:server`'s `LocalUiBuilder` uses.
     */
    fun locate(
      environment: Map<String, String> = System.getenv(),
      property: (String) -> String? = System::getProperty,
      codeSource: File? = codeSourceOf(UiBuilderWebArchive::class.java),
    ): File? {
      val explicit = (property(PATH_PROPERTY) ?: environment[PATH_ENV])?.takeIf { it.isNotBlank() }
      if (explicit != null) return File(explicit).takeIf { it.exists() }
      val appHome = property("composeai.cli.appHome") ?: environment["APP_HOME"]
      return listOfNotNull(appHome?.let(::File), codeSource?.parentFile?.parentFile)
        .map { File(File(it, DIST_DIR), DIST_FILE) }
        .firstOrNull { it.isFile }
    }

    private fun codeSourceOf(type: Class<*>): File? = runCatching {
      type.protectionDomain?.codeSource?.location?.toURI()?.let(::File)
    }
      .getOrNull()

    /**
     * True for a plain archive-relative file path: non-empty `/`-separated segments, none of them
     * `.` or `..`, no backslash, no NUL, no drive or scheme colon. Everything else is refused
     * before it reaches a lookup.
     */
    fun isSafeArchivePath(path: String): Boolean {
      if (path.isEmpty() || path.length > 1024) return false
      if (path.any { it == '\\' || it == ':' || it == '\u0000' || it.isISOControl() }) return false
      return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }
  }
}
