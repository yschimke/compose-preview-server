package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime ingestion of rendered preview images, held for a bounded time and handed back as a direct
 * embeddable URL. For an agent preparing a pull request without GitHub CLI or capture-branch push
 * rights: it POSTs the bytes, gets `https://<host>/i/<id>.png`, and writes `![before](…)`.
 *
 * A sibling of [ServeDocStore], not a format inside it, because policy differs:
 * - **Who may write.** Documents play in the viewer's browser, so anonymous upload is cheap. Images
 *   are served back by this origin (small-scale hosting), so this lane is gated on GitHub access to
 *   the operator's repository ([ServeImageUploadAuth]) and never open, even on `--public`.
 * - **How long.** PR bodies outlive reviews, so the TTL is days ([DEFAULT_TTL_SECONDS]).
 *
 * Kept from [ServeDocStore]: content sniffing ([ServeImageFormats]), hard per-image/count/memory
 * caps with eviction, and an unguessable id as the capability. No `?url=` leg: the caller already
 * has the bytes, so a fetcher would only add SSRF surface.
 */
class ServeImageStore(
  /** How long an uploaded image stays reachable. */
  val ttlSeconds: Long = DEFAULT_TTL_SECONDS,
  private val maxImages: Int = DEFAULT_MAX_IMAGES,
  private val maxBytes: Int = DEFAULT_MAX_IMAGE_BYTES,
  private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
  /** Injected so tests can drive expiry without sleeping. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val mintId: () -> String = ServeCapabilityId::mint,
) {

  /** One stored image and its link's lifetime. */
  class Image(
    val id: String,
    /**
     * Display label (the uploaded filename, sanitised), or the format label when none was given.
     */
    val name: String,
    val format: ServeImageFormat,
    val bytes: ByteArray,
    /** The GitHub login verified at the gate, shown on `/status.json` as an audit trail. */
    val uploadedBy: String,
    val uploadedAtMillis: Long,
    val expiresAtMillis: Long,
  ) {
    val sizeBytes: Int
      get() = bytes.size

    /**
     * The permalink path, ending in the format's real extension so suffix-based consumers agree
     * with the served content type.
     */
    val path: String
      get() = "/i/$id${format.extension}"

    /** Pixel dimensions when the header declared them; null otherwise. */
    val dimensions: ServeDocSize?
      get() = format.size(bytes)

    fun secondsUntilExpiry(nowMillis: Long): Long =
      ((expiresAtMillis - nowMillis) / 1000).coerceAtLeast(0)
  }

  sealed interface Result {
    data class Ok(val image: Image) : Result

    data class Failed(val reason: String) : Result
  }

  private val images = ConcurrentHashMap<String, Image>()

  /**
   * Store [bytes] as an image and mint its expiring link. [isSecurityChecked] is the same greppable
   * audit marker as [ServeDocStore.add]: pass `true` only after the route's identity gate. [name]
   * is only ever a sanitised label, never a path or the format decision.
   */
  fun add(
    name: String?,
    bytes: ByteArray,
    uploadedBy: String,
    isSecurityChecked: Boolean,
  ): Result {
    if (bytes.isEmpty()) return Result.Failed("empty image")
    if (bytes.size > maxBytes) {
      return Result.Failed("image exceeds ${maxBytes / (1024 * 1024)}MB")
    }
    val format =
      ServeImageFormats.detect(bytes)
        ?: return Result.Failed(
          "unrecognised image format — this host accepts ${ServeImageFormats.knownSummary()}"
        )
    val now = clock()
    purgeExpired(now)
    val image =
      Image(
        id = mintId(),
        name = displayName(name, format),
        format = format,
        bytes = bytes,
        uploadedBy = uploadedBy,
        uploadedAtMillis = now,
        expiresAtMillis = now + ttlSeconds * 1000,
      )
    images[image.id] = image
    evictOverflow()
    return Result.Ok(image)
  }

  /**
   * The live image for [id], or null when unknown or expired (expired ⇒ dropped). [extension], when
   * given, must be the format's own (`/i/<id>.jpg` for a PNG is a miss); a bare id still resolves.
   */
  fun get(id: String, extension: String? = null): Image? {
    val now = clock()
    purgeExpired(now)
    val image = images[id]?.takeIf { it.expiresAtMillis > now } ?: return null
    if (extension != null && !extension.equals(image.format.extension, ignoreCase = true)) {
      return null
    }
    return image
  }

  /**
   * Seconds left on [image]'s link, on the store's clock, which decides expiry; callers must not
   * use the wall clock.
   */
  fun remainingSeconds(image: Image): Long = image.secondsUntilExpiry(clock())

  /** Drop every image whose TTL has run out; returns how many went. */
  fun purgeExpired(nowMillis: Long = clock()): Int {
    var dropped = 0
    images.entries.removeIf { (_, image) ->
      (image.expiresAtMillis <= nowMillis).also { if (it) dropped++ }
    }
    return dropped
  }

  /** Live images, soonest expiry first — for the status page. */
  fun snapshot(): List<Image> {
    val now = clock()
    purgeExpired(now)
    return images.values.sortedBy { it.expiresAtMillis }
  }

  /** What the store currently holds, for `/status.json`. */
  fun occupancy(): Occupancy {
    val live = snapshot()
    return Occupancy(
      count = live.size,
      maxCount = maxImages,
      totalBytes = live.sumOf { it.sizeBytes.toLong() },
      maxTotalBytes = maxTotalBytes,
      uploaders = live.map { it.uploadedBy }.distinct().size,
    )
  }

  data class Occupancy(
    val count: Int,
    val maxCount: Int,
    val totalBytes: Long,
    val maxTotalBytes: Long,
    val uploaders: Int,
  )

  /**
   * Enforce the count and memory caps by evicting the images closest to expiry (with a days-long
   * TTL, the oldest uploads), keeping the heap bounded instead of refusing uploads.
   */
  private fun evictOverflow() {
    while (
      images.size > maxImages || images.values.sumOf { it.sizeBytes.toLong() } > maxTotalBytes
    ) {
      val oldest = images.values.minByOrNull { it.expiresAtMillis } ?: return
      images.remove(oldest.id)
    }
  }

  /** A safe display label: the filename's own last segment, trimmed to printable characters. */
  private fun displayName(raw: String?, format: ServeImageFormat): String {
    val candidate =
      raw
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.trim()
        ?.filter { it.isLetterOrDigit() || it in "._- " }
        ?.take(80)
        ?.trim()
    return candidate?.takeIf { it.isNotEmpty() } ?: "${format.label} image"
  }

  companion object {
    /**
     * Seven days: a PR link must outlive its review. Still a TTL: GitHub's camo cache usually keeps
     * a body painting after expiry, but for permanent evidence commit the PNG to a capture branch
     * (`compose-preview share-preview`). `--image-ttl` raises it, within the memory caps.
     */
    const val DEFAULT_TTL_SECONDS = 7L * 24 * 60 * 60

    /** Roughly a large PR's worth of before/after renders, live at once. */
    const val DEFAULT_MAX_IMAGES = 256

    /** Per image. A render PNG is tens of kB; this is a ceiling on a mistake, not a target. */
    const val DEFAULT_MAX_IMAGE_BYTES = 8 * 1024 * 1024

    /** Across the whole lane — these are heap, so this number is a memory decision. */
    const val DEFAULT_MAX_TOTAL_BYTES = 128L * 1024 * 1024
  }
}
