package ee.schimke.composeai.mcp

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.decodeURLPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import java.io.Closeable
import java.net.URI
import kotlinx.coroutines.runBlocking

/**
 * The loopback HTTP origin the UI Builder MCP App loads its editor from:
 * `http://127.0.0.1:<ephemeral>/ui-builder/v/<version>/`. The ~45 MB editor is too large for an
 * inline `resources/read`, so the resource is a small shell whose `<base href>` and CSP point here.
 * The listener starts on the first [base] call, so a session that never opens a design opens no
 * port.
 *
 * - Only archive files, by exact name; unsafe segments are refused before lookup
 *   ([UiBuilderWebArchive.isSafeArchivePath]).
 * - GET, HEAD and OPTIONS only; anything else is 405 with `Allow`.
 * - `Access-Control-Allow-Origin: *`, never credentials: the sandboxed frame's origin is often
 *   `null`, and echoing that grants exactly what `*` does. The bytes are public and read-only.
 * - Private Network Access preflights get `Access-Control-Allow-Private-Network: true`.
 * - `application/wasm` for `.wasm` (streaming compilation requires it), `nosniff` throughout.
 * - Immutable for a year: the path carries the editor version.
 */
internal class UiBuilderAssetOrigin(
  private val archive: UiBuilderWebArchive,
  version: String,
  private val host: String = LOOPBACK_HOST,
  private val onLog: (String) -> Unit = { System.err.println(it) },
) : Closeable {
  /** `/ui-builder/v/<version>/`; the version is reduced to URL-safe characters. */
  val pathPrefix: String = "/ui-builder/v/${version.replace(UNSAFE_VERSION_CHARS, "_")}/"

  private class Started(val server: EmbeddedServer<*, *>, val base: URI)

  @Volatile private var started: Started? = null

  /** The asset base URL (absolute, ending in `/`), starting the listener on the first call. */
  fun base(): URI =
    started?.base ?: synchronized(this) { started?.base ?: start().also { started = it }.base }

  /** The origin of [base], `http://127.0.0.1:<port>`, for the resource's CSP. */
  fun origin(): String = base().let { "${it.scheme}://${it.authority}" }

  private fun start(): Started {
    val server =
      embeddedServer(CIO, host = host, port = 0) { installUiBuilderAssets(archive, pathPrefix) }
    server.start(wait = false)
    val port = runBlocking { server.engine.resolvedConnectors().first().port }
    val base = URI("http://$host:$port$pathPrefix")
    onLog("compose-preview-mcp: UI Builder editor assets at $base (from ${archive.source.name})")
    return Started(server, base)
  }

  override fun close() {
    synchronized(this) {
      started?.server?.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
      started = null
    }
  }

  companion object {
    const val LOOPBACK_HOST: String = "127.0.0.1"
    private val UNSAFE_VERSION_CHARS = Regex("[^A-Za-z0-9._-]")
  }
}

/** The asset handler, as a Ktor module so tests drive it with `testApplication`. */
internal fun Application.installUiBuilderAssets(archive: UiBuilderWebArchive, pathPrefix: String) {
  intercept(ApplicationCallPipeline.Call) {
    call.respondUiBuilderAsset(archive, pathPrefix)
    finish()
  }
}

private suspend fun ApplicationCall.respondUiBuilderAsset(
  archive: UiBuilderWebArchive,
  pathPrefix: String,
) {
  // Every answer, errors included, is readable from the sandbox frame; none carries credentials.
  response.header(HttpHeaders.AccessControlAllowOrigin, "*")
  when (request.httpMethod) {
    HttpMethod.Options -> {
      response.header(HttpHeaders.AccessControlAllowMethods, ALLOWED_METHODS)
      response.header(HttpHeaders.AccessControlMaxAge, "600")
      if (request.header(REQUEST_PRIVATE_NETWORK).equals("true", ignoreCase = true)) {
        response.header(ALLOW_PRIVATE_NETWORK, "true")
      }
      response.header(HttpHeaders.Allow, ALLOWED_METHODS)
      respond(HttpStatusCode.NoContent)
    }
    HttpMethod.Get,
    HttpMethod.Head -> {
      val raw = request.path()
      val entryPath =
        if (raw.startsWith(pathPrefix)) {
          runCatching { raw.removePrefix(pathPrefix).decodeURLPart() }.getOrNull()
        } else null
      val entry = entryPath?.let(archive::entry)
      if (entryPath == null || entry == null) {
        respondText("Not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
        return
      }
      val type = uiBuilderAssetContentType(entryPath)
      response.header(HttpHeaders.CacheControl, "public, max-age=31536000, immutable")
      response.header("X-Content-Type-Options", "nosniff")
      // A sandbox page that is cross-origin isolated (COEP) can still embed these.
      response.header("Cross-Origin-Resource-Policy", "cross-origin")
      if (request.httpMethod == HttpMethod.Head) {
        respond(
          object : OutgoingContent.NoContent() {
            override val contentType: ContentType = type
            override val contentLength: Long = entry.size
          }
        )
      } else {
        respond(
          object : OutgoingContent.ReadChannelContent() {
            override val contentType: ContentType = type
            override val contentLength: Long = entry.size

            override fun readFrom(): ByteReadChannel = entry.open().toByteReadChannel()
          }
        )
      }
    }
    else -> {
      response.header(HttpHeaders.Allow, ALLOWED_METHODS)
      respondText("Method not allowed", ContentType.Text.Plain, HttpStatusCode.MethodNotAllowed)
    }
  }
}

private const val ALLOWED_METHODS = "GET, HEAD, OPTIONS"
internal const val REQUEST_PRIVATE_NETWORK = "Access-Control-Request-Private-Network"
internal const val ALLOW_PRIVATE_NETWORK = "Access-Control-Allow-Private-Network"

/** The `Content-Type` for an archive file, by extension. */
internal fun uiBuilderAssetContentType(path: String): ContentType =
  ContentType.parse(
    when (path.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
      "wasm" -> "application/wasm"
      "js",
      "mjs" -> "text/javascript; charset=utf-8"
      "css" -> "text/css; charset=utf-8"
      "json",
      "map" -> "application/json"
      "html",
      "htm" -> "text/html; charset=utf-8"
      "svg" -> "image/svg+xml"
      "png" -> "image/png"
      "jpg",
      "jpeg" -> "image/jpeg"
      "webp" -> "image/webp"
      "gif" -> "image/gif"
      "ico" -> "image/x-icon"
      "ttf" -> "font/ttf"
      "otf" -> "font/otf"
      "woff" -> "font/woff"
      "woff2" -> "font/woff2"
      "txt" -> "text/plain; charset=utf-8"
      else -> "application/octet-stream"
    }
  )
