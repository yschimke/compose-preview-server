package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Read-only hosting of explicitly installed public artifacts, independent of editor design ACLs.
 * The operator installs complete bundles atomically; this class never exports or publishes a
 * design. The inventory is a local installation marker, not a new HTTP protocol or a source of
 * trust keys.
 */
class ServeComponentPublications(
  private val root: Path,
  private val viewerRoot: Path?,
  private val publicOrigin: String,
  private val viewerConfig: String = "{}",
) {
  init {
    val origin = URI(publicOrigin)
    require(
      origin.scheme == "https" ||
        (origin.scheme == "http" && origin.host in setOf("localhost", "127.0.0.1", "[::1]")),
    )
    require(
      origin.host != null &&
        origin.rawUserInfo == null &&
        origin.rawQuery == null &&
        origin.rawFragment == null &&
        origin.rawPath.orEmpty() in setOf("", "/"),
    )
    require(Json.parseToJsonElement(viewerConfig) is JsonObject) {
      "preview viewer config must be an object"
    }
  }

  internal fun install(route: Route) {
    route.get("/p/{publication}/r/{revision}/{file...}") {
      val id = call.parameters["publication"].orEmpty()
      val revision = call.parameters["revision"].orEmpty()
      if (!NAME.matches(id) || !NAME.matches(revision)) {
        call.unavailable()
        return@get
      }
      val directory = "$id/r/$revision"
      // Tombstones win over validators and remain after withdrawing an installed revision.
      val withdrawn = withContext(Dispatchers.IO) { safeFile(root, "$directory/withdrawn") != null }
      if (withdrawn) {
        call.unavailable(HttpStatusCode.Gone)
        return@get
      }
      val bundle =
        withContext(Dispatchers.IO) {
          runCatching { readBundle(directory, id, revision) }.getOrNull()
        }
      if (bundle == null) {
        call.unavailable()
        return@get
      }
      val requested = call.parameters.getAll("file").orEmpty().joinToString("/").trimEnd('/')
      if (requested.isEmpty() || requested == "embed") {
        if (!call.request.path().endsWith('/')) {
          call.response.headers.append(HttpHeaders.Location, call.request.path() + "/")
          call.response.headers.append(HttpHeaders.CacheControl, "no-store")
          call.respond(HttpStatusCode.PermanentRedirect)
          return@get
        }
        val pagePath = "/p/$id/r/$revision/"
        val canonical = publicOrigin.trimEnd('/') + pagePath
        call.response.headers.append(
          HttpHeaders.Link,
          "<${pagePath}preview.json>; rel=\"alternate\"; type=\"application/vnd.livery.preview+json\"",
        )
        call.response.headers.append(HttpHeaders.CacheControl, CACHE)
        call.response.headers.append("X-Content-Type-Options", "nosniff")
        call.respondText(
          html(bundle, canonical, pagePath, requested == "embed"),
          ContentType.Text.Html,
        )
      } else {
        val bytes = bundle.files[requested]
        val type = publicationType(requested)
        if (bytes == null || type == null) call.unavailable() else call.bytes(bytes, type)
      }
    }
    if (viewerRoot != null)
      route.get("/assets/livery-preview/{file...}") {
        val name = call.parameters.getAll("file").orEmpty().joinToString("/")
        val type = viewerType(name)
        val bytes =
          if (type == null) null
          else
            withContext(Dispatchers.IO) {
              runCatching { safeFile(viewerRoot, name)?.let { boundedRead(it, MAX_BUNDLE_BYTES) } }
                .getOrNull()
            }
        if (bytes == null || type == null) call.unavailable() else call.bytes(bytes, type)
      }
  }

  private data class Bundle(
    val title: String,
    val description: String,
    val files: Map<String, ByteArray>,
  )

  private fun readBundle(directory: String, id: String, revision: String): Bundle? {
    val marker = safeFile(root, "$directory/publication-files.json") ?: return null
    val inventory =
      Json.parseToJsonElement(boundedRead(marker, 64 * 1024).decodeToString()) as? JsonObject
        ?: return null
    require(inventory.size in 3..128)
    require(
      inventory.keys.containsAll(listOf("preview.json", "catalog/manifest.json", "thumbnail.png")),
    )
    var total = 0
    val files = inventory.mapValues { (name, digest) ->
      require(name.length <= 240)
      require(publicationType(name) != null)
      val expected =
        (digest as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("digest missing")
      require(SHA.matches(expected))
      val file = safeFile(root, "$directory/$name") ?: error("missing artifact")
      val limit = if (name.endsWith(".json")) 1024 * 1024 else 16 * 1024 * 1024
      val bytes = boundedRead(file, minOf(limit, MAX_BUNDLE_BYTES - total))
      total += bytes.size
      require(sha256(bytes) == expected) { "artifact digest mismatch" }
      bytes
    }
    val descriptor =
      Json.parseToJsonElement(files.getValue("preview.json").decodeToString()) as? JsonObject
        ?: return null
    require(
      Json.parseToJsonElement(files.getValue("catalog/manifest.json").decodeToString())
        is JsonObject,
    )
    require(descriptor["schemaVersion"] == JsonPrimitive(1) && descriptor.text("mode") == "preview")
    require(descriptor.text("publicationId") == id && descriptor.text("revision") == revision)
    require(descriptor.text("canonicalUrl") == "./")
    require(
      (descriptor["catalog"] as? JsonObject)?.text("manifestUrl") == "./catalog/manifest.json",
    )
    require(descriptor["data"] is JsonObject)
    val template = descriptor["template"] as? JsonObject ?: return null
    require(SHA.matches(template.text("sha256")))
    require(template.text("contract").isNotBlank() && template.text("designId").isNotBlank())
    // Publication readiness only. Livery's publisher and client own catalog trust, eligibility,
    // binding and action gates; the server does not duplicate those algorithms.
    require(inventory.values.any { (it as? JsonPrimitive)?.content == template.text("sha256") })
    val fallback = descriptor["fallback"] as? JsonObject ?: return null
    val title = fallback.text("title")
    val description = fallback.text("description")
    require(title.length in 1..200 && description.length in 1..2000)
    require(files.getValue("thumbnail.png").take(8).toByteArray().contentEquals(PNG_SIGNATURE))
    return Bundle(title, description, files)
  }

  private fun html(bundle: Bundle, canonical: String, pagePath: String, embed: Boolean): String {
    val escape = WebEscaping::htmlEscape
    val title = escape(bundle.title)
    val description = escape(bundle.description)
    val url = escape(canonical)
    val viewer =
      if (viewerRoot == null) ""
      else
        """
        <link rel="stylesheet" href="/assets/livery-preview/viewer.css">
        <script type="module" src="/assets/livery-preview/viewer.js"></script>
        """
          .trimIndent()
    val mount =
      if (viewerRoot == null) ""
      else
        """
      <div data-livery-preview data-descriptor="${pagePath}preview.json" data-canonical="$url"
        data-mode="${if (embed) "embed" else "page"}" data-config="${escape(viewerConfig)}"></div>
    """
          .trimIndent()
    return """<!doctype html>
      <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
      <title>$title</title><meta name="description" content="$description">
      <link rel="canonical" href="$url">
      <link rel="alternate" type="application/vnd.livery.preview+json" href="${pagePath}preview.json">
      <meta property="og:type" content="website"><meta property="og:title" content="$title">
      <meta property="og:description" content="$description"><meta property="og:url" content="$url">
      <meta property="og:image" content="${url}thumbnail.png"><meta name="twitter:card" content="summary_large_image">
      $viewer</head><body><main class="livery-publication-page"><section data-livery-fallback aria-label="Component preview fallback">
      <h1>$title</h1><p>$description</p><img src="${pagePath}thumbnail.png" alt="$title" style="max-width:100%;height:auto">
      <p><a href="$url">Open component preview</a></p></section>$mount</main></body></html>
    """
      .trimIndent()
  }

  private suspend fun ApplicationCall.bytes(bytes: ByteArray, type: ContentType) {
    val etag = "\"${sha256(bytes)}\""
    response.headers.append(HttpHeaders.CacheControl, CACHE)
    response.headers.append(HttpHeaders.ETag, etag)
    response.headers.append("X-Content-Type-Options", "nosniff")
    // Anonymous read-only resources; browser clients need no cookies or authorization headers.
    response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    if (
      request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any {
        it.trim().removePrefix("W/") in setOf(etag, "*")
      } == true
    )
      respond(HttpStatusCode.NotModified)
    else respondBytes(bytes, type)
  }

  private suspend fun ApplicationCall.unavailable(
    status: HttpStatusCode = HttpStatusCode.NotFound,
  ) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondText(
      if (status == HttpStatusCode.Gone) "This component preview has been withdrawn."
      else "Component preview unavailable.",
      ContentType.Text.Plain,
      status,
    )
  }

  companion object {
    private val NAME = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val SHA = Regex("[a-f0-9]{64}")
    private val SEGMENT = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")
    private const val MAX_BUNDLE_BYTES = 32 * 1024 * 1024
    private const val CACHE = "public, max-age=0, must-revalidate"
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun fromEnvironment(env: Map<String, String> = System.getenv()): ServeComponentPublications? {
      val root = env["SERVE_COMPONENT_PUBLICATIONS_DIR"]?.takeIf { it.isNotBlank() } ?: return null
      val origin =
        requireNotNull(env["SERVE_COMPONENT_PUBLICATIONS_ORIGIN"]?.takeIf { it.isNotBlank() }) {
          "component publication hosting needs its public origin"
        }
      return ServeComponentPublications(
        Path.of(root),
        env["SERVE_COMPONENT_VIEWER_DIR"]?.takeIf { it.isNotBlank() }?.let(Path::of),
        origin,
        env["SERVE_COMPONENT_VIEWER_CONFIG"]?.takeIf { it.isNotBlank() } ?: "{}",
      )
    }

    private fun JsonObject.text(name: String) =
      (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()

    /** Check every component without following links, including the configured root's parents. */
    private fun safeFile(root: Path, relative: String): Path? {
      val parts = relative.split('/')
      if (
        relative.length > 512 ||
          parts.isEmpty() ||
          parts.any { !SEGMENT.matches(it) || it == "." || it == ".." }
      )
        return null
      val base = root.toAbsolutePath().normalize()
      var checked = base.root
      for (part in base) {
        checked = checked.resolve(part)
        if (Files.isSymbolicLink(checked)) return null
      }
      var file = base
      for (part in parts) {
        file = file.resolve(part)
        if (Files.isSymbolicLink(file)) return null
      }
      return file.takeIf { it.startsWith(base) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
    }

    private fun boundedRead(path: Path, limit: Int): ByteArray {
      require(limit >= 0 && Files.size(path) <= limit)
      return Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
        input.readNBytes(limit + 1).also { require(it.size <= limit) }
      }
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun publicationType(name: String): ContentType? =
      when {
        name == "preview.json" -> ContentType.parse("application/vnd.livery.preview+json")
        name == "catalog/manifest.json" -> ContentType.Application.Json
        name == "thumbnail.png" -> ContentType.Image.PNG
        !name.startsWith("catalog/") -> null
        else ->
          when (name.substringAfterLast('.')) {
            "json" -> ContentType.Application.Json
            "rc",
            "bin" -> ContentType.Application.OctetStream
            "png" -> ContentType.Image.PNG
            "jpg",
            "jpeg" -> ContentType.Image.JPEG
            "webp" -> ContentType.parse("image/webp")
            "ttf",
            "otf",
            "woff2" -> ContentType.Application.OctetStream
            else -> null
          }
      }

    private fun viewerType(name: String): ContentType? =
      when (name.substringAfterLast('.')) {
        "js",
        "mjs" -> ContentType.Application.JavaScript
        "css" -> ContentType.Text.CSS
        "wasm" -> ContentType.parse("application/wasm")
        else -> null
      }
  }
}
