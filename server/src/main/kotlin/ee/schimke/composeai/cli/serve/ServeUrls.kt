package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure helpers for the `compose-preview serve` link surface: session token minting, shareable URLs,
 * constant-time token comparison and LAN IPv4 discovery for the banner. Free of ktor/IO types; the
 * only environment touch is [siteLocalIpv4Addresses].
 */
object ServeUrls {

  /** A host bound to all interfaces — the value we treat as "exposed to the LAN". */
  const val ALL_INTERFACES: String = "0.0.0.0"

  /** Loopback host; the safe default bind. */
  const val LOOPBACK: String = "127.0.0.1"

  /**
   * Mint a URL-safe, unguessable session token: 32 bytes from [SecureRandom], base64url-encoded
   * without padding. This is the only gate on the served endpoints, so it must be high-entropy and
   * never derived from anything predictable.
   */
  fun generateToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }

  /** True when [host] means "bound to every interface", i.e. reachable from other machines. */
  fun isExposed(host: String): Boolean = host == ALL_INTERFACES || host == "::"

  /**
   * Base origin (`http://host:port`). For a wildcard bind, callers substitute a reachable address
   * (loopback, or a [siteLocalIpv4Addresses] entry).
   */
  fun origin(host: String, port: Int): String = "http://${urlHost(host)}:$port"

  /**
   * [host] as a URL authority: an IPv6 literal (e.g. `--host ::1`) in brackets, anything else
   * unchanged. Idempotent.
   */
  fun urlHost(host: String): String =
    if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

  /** Landing-page URL (preview list) carrying the token. */
  fun landingUrl(origin: String, token: String): String = pathUrl(origin, "/", token)

  /**
   * Any absolute path on this server, carrying the token as a query.
   *
   * [path] is trusted to be an absolute path with no query of its own — `--open-path` rejects
   * anything else at parse time, and every other caller passes a literal.
   */
  fun pathUrl(origin: String, path: String, token: String): String =
    "$origin$path?token=${WebEscaping.urlEncodeSegment(token)}"

  /** Viewer-page URL for one preview, id percent-encoded as a path segment, token in the query. */
  fun viewerUrl(origin: String, previewId: String, token: String): String =
    "$origin/p/${WebEscaping.urlEncodeSegment(previewId)}?token=${WebEscaping.urlEncodeSegment(token)}"

  /**
   * Relative src for the in-browser Wasm app backing [previewId] in [system]
   * (`/wasm/<system>/?id=<component>[&uiMode=<theme>]`). The app keys components by slug, so the
   * variant is stripped for `id`, but a `light`/`dark` theme axis is forwarded as `uiMode` so a
   * `…__dark` deep link doesn't flip to light when handed to the in-browser tier. The viewer's
   * Theme control still overrides it.
   */
  fun wasmAppSrc(system: String, previewId: String): String {
    return buildWasmAppSrc("/wasm/${WebEscaping.urlEncodeSegment(system)}/", previewId)
  }

  /**
   * Token-in-path twin of [wasmAppSrc] for automatically discovered local applications. Keeping the
   * credential in the directory prefix means the app's relative JavaScript and `.wasm` requests
   * inherit it; a query token on `index.html` would be lost by those sub-resource URLs.
   */
  fun privateWasmAppSrc(system: String, previewId: String, token: String): String =
    buildWasmAppSrc(
      "/wasm-private/${WebEscaping.urlEncodeSegment(token)}/${WebEscaping.urlEncodeSegment(system)}/",
      previewId,
    )

  private fun buildWasmAppSrc(base: String, previewId: String): String {
    val component = previewId.substringBefore("__")
    val theme = previewId.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }
    return buildString {
      append(base).append("?id=")
      append(WebEscaping.urlEncodeSegment(component))
      if (theme != null) append("&uiMode=").append(theme)
    }
  }

  /**
   * Render (PNG) URL for one preview at the given overrides. [overrides] is an already-validated
   * map of `ServeOverrides.SUPPORTED_KEYS` → value; the token and each value are percent-encoded.
   */
  fun renderUrl(
    origin: String,
    previewId: String,
    token: String,
    overrides: Map<String, String> = emptyMap(),
  ): String {
    val query = buildString {
      append("token=").append(WebEscaping.urlEncodeSegment(token))
      for ((k, v) in overrides) {
        if (v.isBlank()) continue
        append('&').append(k).append('=').append(WebEscaping.urlEncodeSegment(v))
      }
    }
    return "$origin/render/${WebEscaping.urlEncodeSegment(previewId)}.png?$query"
  }

  /**
   * GitHub blob URL for a preview's source:
   * `https://github.com/<repo>/blob/<ref>/<module>/<sourceFile>`. [ref] is the source ref the
   * catalog was built from (`source.ref`), not the `design-artifacts/<system>` delivery branch.
   * [module] is a Gradle project path (`:samples:foo`, converted to `samples/foo`) or subdirectory,
   * and is optional.
   *
   * Null when [repo], [ref] or [sourceFile] is blank. The path is percent-encoded per segment;
   * [repo] and [ref] pass through verbatim.
   */
  fun githubBlobUrl(repo: String?, ref: String?, module: String?, sourceFile: String?): String? {
    val r = repo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val f = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val rel =
      sourceFile?.replace('\\', '/')?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: return null
    val rawModule = module?.replace('\\', '/')?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    val mod =
      rawModule
        ?.let { if (it.startsWith(':')) it.trim(':').replace(':', '/') else it }
        ?.takeIf { it.isNotEmpty() }
    val path = if (mod != null) "$mod/$rel" else rel
    val encoded = path.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://github.com/$r/blob/$f/$encoded"
  }

  /**
   * The raw-file twin of [githubBlobUrl] (`raw.githubusercontent.com`), used to seed the playground
   * editor (`/playground?from=…`). Inputs come only from the catalog's trusted metadata, never a
   * request, so a visitor can't steer the fetch.
   */
  fun githubRawUrl(repo: String?, ref: String?, module: String?, sourceFile: String?): String? {
    val blob = githubBlobUrl(repo, ref, module, sourceFile) ?: return null
    return blob
      .replaceFirst("https://github.com/", "https://raw.githubusercontent.com/")
      .replaceFirst("/blob/", "/")
  }

  /**
   * `history.json` on a delivery branch, the precomputed render timeline. Null without delivery
   * provenance, which also tells the viewer to omit the timeline.
   */
  fun historyManifestUrl(repo: String?, branch: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 }
    val b = branch?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
    if (r == null || b == null) return null
    val encodedBranch = b.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://raw.githubusercontent.com/$r/$encodedBranch/${PreviewHistoryManifest.FILE_NAME}"
  }

  /**
   * A render as it existed at [commit]: `raw.githubusercontent.com/<repo>/<sha>/<path>`, so every
   * historical render is addressable without a server round-trip. [commit] must be a hex sha, not a
   * ref, and [path] must stay inside the renders tree, so a malformed manifest can't steer fetches.
   */
  fun historicalRenderUrl(repo: String?, commit: String?, path: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { it.isNotEmpty() && it.count { c -> c == '/' } == 1 }
    val c = commit?.trim()?.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }
    val p = path?.trim()?.takeIf { it.startsWith("renders/") && !it.contains("..") }
    if (r == null || c == null || p == null) return null
    val encoded = p.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://raw.githubusercontent.com/$r/$c/$encoded"
  }

  /** Constant-time token comparison, as UTF-8 bytes via [MessageDigest.isEqual]. */
  fun tokensMatch(expected: String, provided: String?): Boolean {
    if (provided == null) return false
    return MessageDigest.isEqual(
      expected.toByteArray(Charsets.UTF_8),
      provided.toByteArray(Charsets.UTF_8),
    )
  }

  /**
   * Site-local IPv4 addresses on up, non-loopback interfaces, for the "Network:" banner line. Best
   * effort: returns empty when enumeration fails or nothing qualifies (e.g. loopback-only host).
   */
  fun siteLocalIpv4Addresses(): List<String> =
    try {
      NetworkInterface.getNetworkInterfaces()
        .asSequence()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.asSequence() }
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress }
        .map { it.hostAddress }
        .distinct()
        .toList()
    } catch (_: Exception) {
      emptyList()
    }
}
