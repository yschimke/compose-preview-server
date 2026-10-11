package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.BundleSigning
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.bundle.TrustStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Runtime ingestion of client-provided portable bundles for shared/public mode: a client uploads a
 * zip (or names a URL, e.g. a CI artifact) and the store unpacks it and registers a read-only
 * [ServeBundleHost] session via [register], returning a shareable `?session=<name>` link.
 *
 * Only servable `previews/` entries are extracted (baked PNGs, knob sidecars, root
 * `previews.json`), under a per-bundle directory with zip-slip containment and a total size cap.
 * [addFromUrl] is an SSRF surface, gated by the operator's [allowedHosts] (empty refuses every URL)
 * regardless of the injected [fetch].
 */
class ServeBundleStore(
  private val root: File,
  private val register: (name: String, host: ServeBundleHost) -> Unit,
  /**
   * Transport override for tests. Null uses the real transport via
   * [ServeUrlFetch.followingRedirects], which allowlist-checks every redirect hop.
   */
  private val fetch: ((String) -> ByteArray?)? = null,
  private val maxBytes: Long = DEFAULT_MAX_BYTES,
  /**
   * SSRF allowlist for [addFromUrl]: exact, case-insensitive hostnames. Empty refuses every URL
   * (fail closed).
   */
  private val allowedHosts: List<String> = emptyList(),
  /**
   * Producer-trust store an upload is verified against ([BundleVerifier]); the verdict is attached
   * to the host and returned in [Result.Ok]. Data tiers are served regardless; the verdict only
   * gates later re-rendering. Defaults to the empty fail-closed store. Read per upload so admin
   * changes apply immediately.
   */
  private val trust: () -> TrustStore = { TrustStore.EMPTY },
) {

  sealed interface Result {
    /**
     * [trust] is the verdict's [BundleVerifier.summary] (e.g. `signature:ci`); defaults to
     * `unverified`.
     */
    data class Ok(val name: String, val previewCount: Int, val trust: String = "unverified") :
      Result

    data class Failed(val reason: String) : Result
  }

  /**
   * Unpack [zipBytes] under [name] and register it as a bundle session.
   *
   * [isSecurityChecked] is a greppable audit marker (not enforced): the caller passes `true` only
   * after policy (token-gated `POST /bundles`). The unpack is defended in depth (name sanitisation,
   * zip-slip, size cap). [origin] is set only when the server itself fetched the bundle from a
   * known branch (`--bundle <raw URL>` at startup), so a trusted branch badges `Trusted(Branch)`;
   * client uploads pass null.
   */
  fun add(
    name: String,
    zipBytes: ByteArray,
    isSecurityChecked: Boolean,
    origin: BundleVerifier.Origin? = null,
  ): Result {
    val safe = sanitizeName(name) ?: return Result.Failed("invalid bundle name: '$name'")
    val dir = File(root, safe)
    dir.deleteRecursively()
    // Normalize once: an uploaded bundle may be a plain zip or a PNG+ZIP polyglot (a signed bundle
    // is a polyglot). Both the preview extraction and the trust digest operate on the zip portion.
    val zip = BundleSigning.zipBytesOf(zipBytes)
    val count =
      try {
        extractPreviews(zip, dir)
      } catch (e: Exception) {
        dir.deleteRecursively()
        return Result.Failed("could not unpack bundle: ${e.message}")
      }
    if (count == 0) {
      dir.deleteRecursively()
      return Result.Failed("bundle had no previews/*.png or previews/*.error.json entries")
    }
    // Uploads are attributed only by verifiable signature (no origin trust for client uploads). The
    // verdict travels with the host for display and never blocks serving extracted data.
    val verdict = BundleVerifier.verify(zip, trust(), origin)
    val host = ServeBundleHost(dir, safe, verdict)
    register(safe, host)
    return Result.Ok(safe, host.previews.size, BundleVerifier.summary(verdict))
  }

  /**
   * Fetch a bundle zip from [url], then [add] it. SSRF gate: http/https with a host on
   * [allowedHosts] (empty refuses all), re-checked on every redirect hop
   * ([ServeUrlFetch.followingRedirects]) so a `302` to an internal address is refused.
   * [isSecurityChecked] is the same audit marker as [add].
   */
  fun addFromUrl(name: String, url: String, isSecurityChecked: Boolean): Result {
    if (!isAllowedUrl(url)) {
      return Result.Failed(
        "refusing to fetch $url: host is not on the --accept-bundles-from allowlist"
      )
    }
    val bytes =
      try {
        fetchBundle(url)
      } catch (e: Exception) {
        return Result.Failed("could not fetch $url: ${e.message}")
      } ?: return Result.Failed("could not fetch $url")
    return add(name, bytes, isSecurityChecked = isSecurityChecked)
  }

  /** True when [url] is http/https and its host is on the [allowedHosts] SSRF allowlist. */
  private fun isAllowedUrl(url: String): Boolean = ServeUrlFetch.isAllowedUrl(url, allowedHosts)

  /**
   * The injected [fetch] if supplied, else the real transport, which never follows redirects
   * itself.
   */
  private fun fetchBundle(url: String): ByteArray? {
    // An injected fetcher owns the result, null included; it must never fall through to the real
    // network.
    val override = fetch
    if (override != null) return override(url)
    return ServeUrlFetch.followingRedirects(url, ::isAllowedUrl) {
      ServeUrlFetch.sendOnce(it, maxBytes)
    }
  }

  /**
   * Extract the servable `previews/` entries (PNGs, knob sidecars), `ir/<id>.rc` documents,
   * `<id>.error.json` sidecars and the root `previews.json` into [dir], zip-slip safe and
   * size-capped. Returns the number of servable preview records.
   */
  private fun extractPreviews(zipBytes: ByteArray, dir: File): Int {
    val rootPath = dir.canonicalFile.toPath()
    val previewIds = LinkedHashSet<String>()
    var total = 0L
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zin ->
      var entry = zin.nextEntry
      while (entry != null) {
        val name = entry.name.replace('\\', '/')
        val segments = name.split("/")
        // Keep both knob sidecars (`.overrides.json` and `.remotecompose.json`) so uploads present
        // the same knobs as the directory path.
        val underPreviews = name.startsWith("$PREVIEWS_SUBDIR/") && ".." !in segments
        val insideSpatial = segments.dropLast(1).any { it.endsWith(SPATIAL_SUFFIX) }
        val isPng = underPreviews && !insideSpatial && name.endsWith(PNG_SUFFIX)
        val isRenderError = underPreviews && name.endsWith(RENDER_ERROR_SUFFIX)
        val isOverrides = underPreviews && name.endsWith(OVERRIDES_SUFFIX)
        val isRemoteCompose = underPreviews && name.endsWith(REMOTECOMPOSE_SUFFIX)
        // Spatial previews: a scene document plus image textures under `previews/<id>.spatial/`.
        // The allowlist stays closed; nothing executable is needed.
        val spatialLeaf = segments.lastOrNull().orEmpty()
        val isSpatial =
          underPreviews &&
            segments.size >= 3 &&
            segments[segments.lastIndex - 1].endsWith(SPATIAL_SUFFIX) &&
            (spatialLeaf == SPATIAL_SCENE_FILE ||
              SPATIAL_IMAGE_SUFFIXES.any { spatialLeaf.lowercase().endsWith(it) })
        // Keep `ir/<id>.rc` documents for the browser player (`GET /render/<id>.rc`), matching the
        // directory path.
        val underIr = name.startsWith("$IR_SUBDIR/") && ".." !in segments
        val isRc = underIr && name.endsWith(RC_SUFFIX)
        // Keep the root `previews.json` for `@ThemeCatalog` themes, which live only there;
        // top-level, so exempt from the `previews/` prefix but still zip-slip guarded.
        val isPreviewsJson = name == PREVIEWS_JSON
        // Provider-neutral references are inert input for the existing comparison lane. UID
        // snapshots are separately size/digest checked when opened; never extract active HTML.
        val isReferenceData =
          name.startsWith("references/") &&
            ".." !in segments &&
            (name == "references/index.json" || name.endsWith(".png") || name.endsWith(".uid"))
        if (
          !entry.isDirectory &&
            (isPng ||
              isRenderError ||
              isOverrides ||
              isRemoteCompose ||
              isSpatial ||
              isRc ||
              isReferenceData ||
              isPreviewsJson)
        ) {
          val target = File(dir, name)
          // Zip-slip guard: the resolved path must stay under the bundle dir.
          if (target.canonicalFile.toPath().startsWith(rootPath)) {
            target.parentFile?.mkdirs()
            // Copy in bounded chunks so a huge / zip-bomb entry can't be fully allocated before the
            // cap rejects it — abort the moment the running total crosses maxBytes.
            total += copyCapped(zin, target, remaining = maxBytes - total)
            // A structured render failure is intentionally servable without pixels: its card
            // explains why the corresponding PNG is absent.
            when {
              isPng -> previewIds += name.removePrefix("$PREVIEWS_SUBDIR/").removeSuffix(PNG_SUFFIX)
              isRenderError ->
                previewIds +=
                  name.removePrefix("$PREVIEWS_SUBDIR/").removeSuffix(RENDER_ERROR_SUFFIX)
              isSpatial && spatialLeaf == SPATIAL_SCENE_FILE ->
                previewIds +=
                  segments.drop(1).dropLast(1).joinToString("/").removeSuffix(SPATIAL_SUFFIX)
            }
          }
        }
        zin.closeEntry()
        entry = zin.nextEntry
      }
    }
    return previewIds.size
  }

  /** Stream [input] into [target], throwing once more than [remaining] bytes have been written. */
  private fun copyCapped(input: InputStream, target: File, remaining: Long): Long {
    var written = 0L
    val buffer = ByteArray(64 * 1024)
    target.outputStream().use { out ->
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        written += n
        check(written <= remaining) { "bundle exceeds ${maxBytes / (1024 * 1024)}MB" }
        out.write(buffer, 0, n)
      }
    }
    return written
  }

  companion object {
    private const val PREVIEWS_SUBDIR = "previews"
    private const val PNG_SUFFIX = ".png"
    private const val RENDER_ERROR_SUFFIX = ".error.json"
    private const val OVERRIDES_SUFFIX = ".overrides.json"
    private const val REMOTECOMPOSE_SUFFIX = ".remotecompose.json"
    private const val SPATIAL_SUFFIX = ".spatial"
    private const val SPATIAL_SCENE_FILE = "scene.json"
    private val SPATIAL_IMAGE_SUFFIXES = listOf(".png", ".jpg", ".jpeg", ".webp")
    private const val IR_SUBDIR = "ir"
    private const val RC_SUFFIX = ".rc"
    private const val PREVIEWS_JSON = "previews.json"
    private const val DEFAULT_MAX_BYTES = 100L * 1024 * 1024 // 100 MB

    /** A session name safe to use as a path segment + URL value; null if it can't be made safe. */
    fun sanitizeName(name: String): String? {
      val trimmed = name.trim()
      // Reject empty and dot-only names: `.`/`..` resolve to the upload root or its parent, which
      // [add] would `deleteRecursively()`.
      if (trimmed.isEmpty() || trimmed.all { it == '.' }) return null
      // Reject reserved top-level route names: such a session is unreachable anyway, and on a
      // top-level site the interceptor would let `/api/` fall through to `/{system}/` and serve the
      // bundle. Refusing the name upholds "a reserved segment is never a session".
      if (trimmed in ServeSites.RESERVED_SYSTEMS) return null
      return trimmed.takeIf { it.matches(Regex("[A-Za-z0-9._@-]{1,128}")) }
    }
  }
}
