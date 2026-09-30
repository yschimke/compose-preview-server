package ee.schimke.composeai.mcp

import ee.schimke.composeai.mcp.protocol.CallToolResult
import ee.schimke.composeai.mcp.protocol.ContentBlock
import ee.schimke.composeai.mcp.protocol.ReadResourceResult
import ee.schimke.composeai.mcp.protocol.ResourceContents
import ee.schimke.composeai.mcp.protocol.ResourceDescriptor
import ee.schimke.composeai.mcp.protocol.ToolDef
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The `.rc` Remote Compose file viewer (issue #1237): the `rc_open` tool and the
 * `ui://compose-preview/rc-viewer` MCP App that plays the document.
 *
 * Two ways in, one viewer:
 * - **A host file entrypoint** (ChatGPT / Codex desktop, OpenAI MCP Extensions "File Extension
 *   Entrypoint"): `rc_open` gets `FileInput` `{file: {name, resourceUri}}`. The URI is the host's
 *   opaque `host-resource://…`; the app reads it with `resources/read` (asking for the `blob`
 *   representation) and subscribes to it, and the host answers both. The server only acknowledges.
 * - **A model call** in any other MCP Apps host (Claude Code, Antigravity): `rc_open {path}`. The
 *   server validates and reads the file and returns the bytes in `_meta` (for the app, not the
 *   model) plus a server-minted `compose-preview-rc://document/<token>/<name>` URI the app re-reads
 *   and subscribes to. A poller turns a change on disk into `notifications/resources/updated`, so
 *   the viewer reloads when an agent regenerates the file.
 *
 * **No filesystem path reaches the app.** Results name the file by its base name only, and the
 * server URI is a random token that resolves only to documents a tool call opened.
 *
 * The player is the vendored TypeScript Remote Compose player (`rc-player/bundle.js`, ~0.7 MB),
 * inlined into the HTML when the resource is served, so the app needs no CDN and no CSP
 * `resourceDomains` for itself. The Wasm Compose Multiplatform player
 * (`@yschimke/remote-compose-player-cmp`) was the alternative: identical pixels to Android/iOS, but
 * ~23 MB, narrower operation coverage today, and a remote load. RC_PLAYER_EMBED.md in rc-players
 * draws the same line.
 */
class RcViewerMcp(
  /** Sessions subscribed to a URI; [DaemonMcpServer] passes its `Subscriptions`. */
  private val subscribers: (String) -> Set<Session>,
  private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
) {
  private class OpenDocument(val file: File, val name: String, @Volatile var stamp: Stamp)

  private data class Stamp(val lastModified: Long, val length: Long, val sha256: String?)

  /** Server document URI → the file a tool call opened. */
  private val documents = ConcurrentHashMap<String, OpenDocument>()

  /** Canonical path → its URI, so reopening a file keeps the URI a viewer subscribed to. */
  private val uriByPath = ConcurrentHashMap<String, String>()

  private val random = SecureRandom()

  @Volatile private var poller: ScheduledExecutorService? = null

  fun toolDefs(): List<ToolDef> = listOf(toolDef)

  fun resourceDescriptors(): List<ResourceDescriptor> =
    listOf(
      ResourceDescriptor(
        uri = VIEWER_URI,
        name = "Remote Compose viewer",
        description = "Plays a .rc Remote Compose document, live-reloading when the file changes.",
        mimeType = MCP_APP_MIME_TYPE,
        meta = resourceMeta(),
      )
    )

  /** `rc_open`'s result, or null when [name] is not this viewer's tool. */
  suspend fun handle(name: String, args: JsonObject): CallToolResult? {
    if (name != TOOL_NAME) return null
    val file = args["file"]
    val path = (args["path"] as? JsonPrimitive)?.contentOrNull
    return when {
      file is JsonObject -> openHostFile(file)
      file != null -> error(INVALID_ARGUMENTS, "rc_open: `file` must be {name, resourceUri}.")
      path != null -> openPath(path)
      else ->
        error(
          INVALID_ARGUMENTS,
          "rc_open: pass `path` (an absolute path to a .rc file) or a host `file` (FileInput).",
        )
    }
  }

  /** The viewer HTML or an opened document's bytes; null for a URI this viewer does not own. */
  fun readResource(uri: String): ReadResourceResult? {
    if (uri == VIEWER_URI) {
      return ReadResourceResult(
        contents =
          listOf(
            ResourceContents.Text(
              uri = uri,
              mimeType = MCP_APP_MIME_TYPE,
              text = viewerHtml,
              meta = resourceMeta(),
            )
          )
      )
    }
    if (!uri.startsWith(DOCUMENT_URI_PREFIX)) return null
    val document =
      documents[uri]
        ?: throw IllegalArgumentException(
          "Unknown Remote Compose document: open it with rc_open first."
        )
    val bytes =
      when (val read = readDocument(document.file)) {
        is Read.Ok -> read.bytes
        is Read.Failed -> throw IllegalStateException(read.message)
      }
    return ReadResourceResult(
      contents =
        listOf(
          ResourceContents.Blob(
            uri = uri,
            mimeType = RC_MIME_TYPE,
            blob = Base64.getEncoder().encodeToString(bytes),
          )
        )
    )
  }

  fun shutdown() {
    poller?.shutdownNow()
  }

  private suspend fun openHostFile(file: JsonObject): CallToolResult {
    val name = (file["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    val resourceUri = (file["resourceUri"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    if (name.isEmpty() || resourceUri.isEmpty()) {
      return error(INVALID_ARGUMENTS, "rc_open: `file` needs a non-blank name and resourceUri.")
    }
    val baseName = baseName(name)
    if (!baseName.lowercase().endsWith(EXTENSION)) {
      return error(UNSUPPORTED_EXTENSION, "rc_open: $baseName is not a $EXTENSION file.")
    }
    // Inside a file entrypoint the host adds the real path to an app→server call. The server may
    // use it (here: size, digest, and a server-side URI the viewer can fall back to), but it never
    // goes back to the app.
    val injected =
      OpenAiUi.currentResourcePath()?.let { raw ->
        val candidate = File(raw)
        val read = if (candidate.isAbsolute) readDocument(candidate) else null
        if (read is Read.Ok) register(candidate, baseName, read.bytes) else null
      }
    val structured = buildJsonObject {
      put("schema", RESULT_SCHEMA)
      put("source", "host")
      put("name", baseName)
      put("resourceUri", resourceUri)
      if (injected != null) {
        put("fallbackResourceUri", injected.uri)
        put("sizeBytes", injected.bytes.size)
        put("sha256", injected.sha256)
      }
    }
    return CallToolResult(
      content =
        listOf(
          ContentBlock.Text(
            "Opened $baseName in the Remote Compose viewer. The viewer reads it through the host " +
              "and reloads when it changes."
          )
        ),
      structuredContent = structured,
    )
  }

  private fun openPath(raw: String): CallToolResult {
    if (raw.isBlank()) return error(INVALID_ARGUMENTS, "rc_open: `path` is blank.")
    val file = File(raw)
    val name = file.name
    if (!file.isAbsolute) {
      return error(PATH_NOT_ABSOLUTE, "rc_open: `path` must be absolute; got a relative path.")
    }
    if (!name.lowercase().endsWith(EXTENSION)) {
      return error(UNSUPPORTED_EXTENSION, "rc_open: $name is not a $EXTENSION file.")
    }
    if (!file.exists()) return error(NOT_FOUND, "rc_open: no such file: $name.")
    if (!file.isFile) return error(NOT_A_FILE, "rc_open: $name is not a regular file.")
    val bytes =
      when (val read = readDocument(file)) {
        is Read.Failed -> return error(read.code, read.message)
        is Read.Ok -> read.bytes
      }
    val opened = register(file, name, bytes)
    val structured = buildJsonObject {
      put("schema", RESULT_SCHEMA)
      put("source", "server")
      put("name", name)
      put("resourceUri", opened.uri)
      put("mimeType", RC_MIME_TYPE)
      put("sizeBytes", opened.bytes.size)
      put("sha256", opened.sha256)
    }
    return CallToolResult(
      content =
        listOf(
          ContentBlock.Text(
            "Opened $name (${opened.bytes.size} bytes, sha256 ${opened.sha256.take(12)}) in the " +
              "Remote Compose viewer. It reloads when the file changes."
          )
        ),
      structuredContent = structured,
      // The bytes are for the app, so it can play without a second round trip; `_meta` stays out
      // of what the model reads.
      meta =
        buildJsonObject {
          putJsonObject(RESULT_META_KEY) {
            put("documentBase64", Base64.getEncoder().encodeToString(opened.bytes))
          }
        },
    )
  }

  private class Opened(val uri: String, val bytes: ByteArray, val sha256: String)

  /** Records [file] (read as [bytes]) under a document URI that is stable per canonical path. */
  private fun register(file: File, name: String, bytes: ByteArray): Opened {
    val canonical = file.canonicalFile
    val sha256 = sha256(bytes)
    val uri =
      uriByPath.computeIfAbsent(canonical.path) {
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        DOCUMENT_URI_PREFIX + token + "/" + URLEncoder.encode(name, Charsets.UTF_8)
      }
    documents[uri] = OpenDocument(canonical, name, stampOf(canonical))
    ensurePolling()
    return Opened(uri, bytes, sha256)
  }

  private sealed interface Read {
    class Ok(val bytes: ByteArray) : Read

    class Failed(val code: String, val message: String) : Read
  }

  private fun readDocument(file: File): Read {
    if (!file.isFile) return Read.Failed(NOT_FOUND, "rc_open: no such file: ${file.name}.")
    val length = file.length()
    if (length > MAX_DOCUMENT_BYTES) {
      return Read.Failed(
        TOO_LARGE,
        "rc_open: ${file.name} is $length bytes; the viewer opens documents up to " +
          "$MAX_DOCUMENT_BYTES bytes.",
      )
    }
    return runCatching { Read.Ok(file.readBytes()) }
      .getOrElse { Read.Failed(UNREADABLE, "rc_open: ${file.name} could not be read.") }
  }

  private fun ensurePolling() {
    if (pollIntervalMs <= 0 || poller != null) return
    synchronized(this) {
      if (poller != null) return
      poller =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "rc-viewer-file-poll").apply { isDaemon = true }
          }
          .also {
            it.scheduleWithFixedDelay(
              { runCatching { pollOnce() } },
              pollIntervalMs,
              pollIntervalMs,
              TimeUnit.MILLISECONDS,
            )
          }
    }
  }

  /**
   * One pass of the change poller: a subscribed document whose file changed on disk sends
   * `notifications/resources/updated` to its subscribers. Unsubscribed documents are not stat'd.
   */
  internal fun pollOnce() {
    for ((uri, document) in documents) {
      val sessions = subscribers(uri)
      if (sessions.isEmpty()) continue
      val next = stampOf(document.file)
      if (next == document.stamp) continue
      document.stamp = next
      sessions.forEach { runCatching { it.notifyResourceUpdated(uri) } }
    }
  }

  /**
   * The file's stamp. mtime and length catch almost every rewrite; a small file is also hashed, so
   * a same-second, same-size regeneration (coarse mtime filesystems) is still seen.
   */
  private fun stampOf(file: File): Stamp {
    if (!file.isFile) return Stamp(0L, -1L, null)
    val length = file.length()
    val sha256 =
      if (length <= HASHED_POLL_MAX_BYTES) runCatching { sha256(file.readBytes()) }.getOrNull()
      else null
    return Stamp(file.lastModified(), length, sha256)
  }

  private val toolDef: ToolDef by lazy {
    OpenAiUi.entrypointTool(
      ToolDef(
        name = TOOL_NAME,
        description =
          "Open a Remote Compose document (.rc) in the Remote Compose viewer, which plays it, " +
            "follows the host's light/dark theme, lets the person edit its named values, and " +
            "reloads when the file changes on disk. Pass `path`, an absolute path to a .rc file. " +
            "Hosts with a file entrypoint call it with `file` (FileInput) themselves.",
        inputSchema = inputSchema,
        meta = OpenAiUi.appToolMeta(VIEWER_URI, entrypoints = emptyList()),
      ),
      entrypoints = listOf(OpenAiEntrypoint.File(listOf(EXTENSION))),
      title = "Remote Compose viewer",
      icon = OpenAiUi.svgIcon(RC_ICON_SVG),
    )
  }

  /**
   * [OpenAiUi.FILE_INPUT_SCHEMA]'s `file`, plus `path` for a model call. Neither is required by the
   * schema — a host sends one, the model the other — and the tool rejects a call with neither.
   */
  private val inputSchema: JsonObject by lazy {
    val fileInput = OpenAiUi.FILE_INPUT_SCHEMA
    val properties = fileInput["properties"] as JsonObject
    buildJsonObject {
      put("type", "object")
      put(
        "properties",
        JsonObject(
          properties +
            ("path" to
              buildJsonObject {
                put("type", "string")
                put("description", "Absolute path to a .rc Remote Compose document.")
              })
        ),
      )
    }
  }

  private fun resourceMeta(): JsonObject =
    OpenAiUi.withDisplayModes(
      buildJsonObject {
        putJsonObject("ui") {
          put("prefersBorder", true)
          // Documents may name Google Fonts families; the player fetches those faces itself.
          putJsonObject("csp") {
            putJsonArray("resourceDomains") {
              add(JsonPrimitive("https://fonts.googleapis.com"))
              add(JsonPrimitive("https://fonts.gstatic.com"))
            }
          }
        }
      }
    )

  private fun error(code: String, message: String): CallToolResult =
    errorCallToolResult(
      message,
      buildJsonObject {
        put("schema", RESULT_SCHEMA)
        putJsonObject("error") {
          put("code", code)
          put("message", message)
        }
      },
    )

  companion object {
    const val TOOL_NAME: String = "rc_open"
    const val VIEWER_URI: String = "ui://compose-preview/rc-viewer"
    const val MCP_APP_MIME_TYPE: String = "text/html;profile=mcp-app"
    const val RC_MIME_TYPE: String = "application/vnd.remote-compose"
    const val DOCUMENT_URI_PREFIX: String = "compose-preview-rc://document/"
    const val RESULT_SCHEMA: String = "compose-preview/rc-document/v1"

    /** `_meta` key on a `path` result carrying `documentBase64` for the app. */
    const val RESULT_META_KEY: String = "compose-preview/rc"
    const val EXTENSION: String = ".rc"

    /** Largest document the viewer opens; far above any real `.rc`, well under a host's limit. */
    const val MAX_DOCUMENT_BYTES: Long = 16L * 1024 * 1024
    const val DEFAULT_POLL_INTERVAL_MS: Long = 1_000
    private const val HASHED_POLL_MAX_BYTES: Long = 1024 * 1024

    const val INVALID_ARGUMENTS: String = "invalid_arguments"
    const val PATH_NOT_ABSOLUTE: String = "path_not_absolute"
    const val UNSUPPORTED_EXTENSION: String = "unsupported_extension"
    const val NOT_FOUND: String = "not_found"
    const val NOT_A_FILE: String = "not_a_file"
    const val TOO_LARGE: String = "too_large"
    const val UNREADABLE: String = "unreadable"

    internal const val VIEWER_ASSET: String = "rc-viewer.html"
    internal const val PLAYER_ASSET: String = "rc-viewer/rc-player-bundle.js"
    internal const val PLAYER_PLACEHOLDER: String = "/*RC_PLAYER_BUNDLE*/"

    /** A play triangle in a document frame; the spec's 20x20 monochrome stroke icon. */
    private const val RC_ICON_SVG: String =
      """<svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.33" stroke-linecap="round" stroke-linejoin="round"><path d="M5 2.5h6.5L15 6v11a.5.5 0 0 1-.5.5h-9.5a.5.5 0 0 1-.5-.5V3a.5.5 0 0 1 .5-.5z"/><path d="M11.5 2.5V6H15"/><path d="M8.25 9.5v5l4-2.5z"/></svg>"""

    /** The viewer with the player inlined, built once. */
    internal val viewerHtml: String by lazy {
      assembleViewerHtml(readAsset(VIEWER_ASSET), readAsset(PLAYER_ASSET))
    }

    /**
     * [html] with [bundle] in its player script element. The bundle goes inside a `<script>`, so it
     * must not contain anything that closes or re-enters script data; checked, not assumed, because
     * the bundle is regenerated from a vendored source.
     */
    internal fun assembleViewerHtml(html: String, bundle: String): String {
      check(html.split(PLAYER_PLACEHOLDER).size == 2) {
        "$VIEWER_ASSET must contain $PLAYER_PLACEHOLDER exactly once"
      }
      val lower = bundle.lowercase()
      check("</script" !in lower && "<!--" !in lower && "<script" !in lower) {
        "the Remote Compose player bundle cannot be inlined into a <script> element"
      }
      return html.replace(PLAYER_PLACEHOLDER, bundle)
    }

    private fun readAsset(name: String): String =
      checkNotNull(RcViewerMcp::class.java.classLoader.getResourceAsStream(name)) {
          "missing bundled Remote Compose viewer asset: $name"
        }
        .bufferedReader()
        .use { it.readText() }

    private fun baseName(name: String): String =
      name.substringAfterLast('/').substringAfterLast('\\')

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
