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
 * The loopback HTTP origin the UI Builder MCP App loads its editor from
 * (compose-ui-builder#364, #366): `http://127.0.0.1:<ephemeral>/ui-builder/v/<version>/`.
 *
 * The editor is ~45 MB unpacked (`uiBuilder.wasm` alone is ~29 MB), which no host takes inline in a
 * `resources/read`, so the MCP App resource is a ~5 KB shell whose `<base href>` points here and
 * whose CSP `resourceDomains`/`connectDomains` name this origin. The listener starts on the first
 * [base] call — the first read of the editor resource — and never before, so a session that never
 * opens a design never opens a port.
 *
 * What it serves, and nothing else:
 * - **Only files from the archive**, by exact name under the versioned prefix. A path that decodes
 *   to an empty, `.` or `..` segment, a backslash or a colon is refused before any lookup
 *   ([UiBuilderWebArchive.isSafeArchivePath]).
 * - **GET, HEAD and OPTIONS only.** Anything else is 405 with `Allow`.
 * - **`Access-Control-Allow-Origin: *`.** The app runs in the host's sandboxed frame, whose origin
 *   is the host's sandbox domain or opaque (`null`); module scripts and the Wasm fetch from it are
 *   cross-origin. Echoing the request's `Origin` instead would mean echoing `null`, which grants
 *   every sandboxed document on the machine exactly what `*` does while reading as if it were
 *   narrower. `*` is the honest form for what this is: public, read-only, credential-free bytes
 *   (the same archive is a public GitHub release asset). No `Allow-Credentials` is ever sent.
 * - **Private Network Access.** A loopback origin fetched from an `https` sandbox page is a PNA
 *   request: Chromium preflights it with `Access-Control-Request-Private-Network: true`, which is
 *   answered with `Access-Control-Allow-Private-Network: true`.
 * - **Types**: `application/wasm` for `.wasm` (streaming compilation refuses anything else),
 *   `text/javascript` for `.js`/`.mjs`, and the right types for CSS, JSON, HTML, SVG, images and
 *   fonts, with `X-Content-Type-Options: nosniff`.
 * - **Caching**: immutable for a year, because the path carries the editor version.
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
