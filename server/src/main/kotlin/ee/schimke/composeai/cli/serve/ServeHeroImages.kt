package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.imagecrop.computeThumbCrop
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Optional
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Prebaked thumbnails for the front door's hero cards ([heroFor]) and the catalog grid
 * ([gridThumbFor]), so a landing page doesn't lease a session and read a full render per card.
 *
 * Heroes are cropped ([ContentCrop]) and downscaled to [DISPLAY_CAP] × [PIXEL_SCALE] (never
 * upscaled), named by content hash so `/hero/` is `immutable`, held in memory and persisted when a
 * cache dir is configured. Baked off the request path when a catalog host is first seen
 * ([ServeHttpServer.rememberCatalogMeta]); a refresh re-bakes.
 *
 * Grid thumbnails differ: the crop isn't baked in (cards switch to full renders on theme change, so
 * the CSS clip remains, [ServeWeb.thumbImg]), and they are served through the render lane as
 * `/render/<id>.png?thumb=<hash>` ([ServeHttpServer.handleRender]).
 */
class ServeHeroImages(private val cacheDir: java.io.File? = null) {

  /** One baked hero: the bytes to serve, how to name/validate them, and the size to lay out. */
  data class Hero(
    /** Baked PNG bytes — cropped, downscaled, ready to write to the socket. */
    val bytes: ByteArray,
    /** Content hash of [bytes]; the `/hero/<system>/<fileName>` segment and the ETag. */
    val fileName: String,
    /** Strong ETag (the quoted hash) for conditional requests. */
    val etag: String,
    /** CSS-pixel width the card lays the hero out at (the baked width ÷ [PIXEL_SCALE]). */
    val cssWidth: Int,
    /** CSS-pixel height the card lays the hero out at. */
    val cssHeight: Int,
  ) {
    // Data class over a ByteArray: identity equality is what callers want (heroes are compared by
    // hash, never by content), and the generated array-identity equals/hashCode would be a trap.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * One baked grid thumbnail: no layout size (CSS sizes it like the full render) and no file name
   * (served under its preview id with [hash] as cache-buster).
   */
  data class Thumb(val bytes: ByteArray, val hash: String, val etag: String) {
    // See [Hero]: identity equality, for the same reason.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * Every hero baked in this process by [Hero.fileName]. `/hero/` resolves only through this map,
   * so URLs from before a refresh keep serving their exact bytes. Small and bounded, so never
   * evicted.
   */
  private val byFileName = ConcurrentHashMap<String, Hero>()

  /**
   * Bake memo per host object, then per preview id, so each catalog bakes once and a refreshed host
   * re-bakes; failures are cached too. A [java.util.WeakHashMap] keyed by the host itself:
   * `identityHashCode` isn't unique and could let a republished catalog inherit stale heroes, while
   * weak keys free retired catalogs' memos. Locked because `WeakHashMap` isn't thread-safe (only
   * around resolving the per-host map).
   */
  private val baked = WeakHashMap<ServeHost, ConcurrentHashMap<String, Optional<Hero>>>()

  private val bakedLock = Any()

  /**
   * Grid-thumbnail counterpart of [baked], kept separate because the lanes bake differently. Never
   * added to [byFileName]: grid URLs name their preview and re-resolve against the current host, so
   * retired catalogs' many thumbnails can be collected.
   */
  private val gridBaked = WeakHashMap<ServeHost, ConcurrentHashMap<String, Optional<Thumb>>>()

  private val gridLock = Any()

  /**
   * The hero for [previewId] on [host], baked on first sight with [crop] in the pixels. Null when
   * there's no render or it won't decode; the caller falls back to `/render/`.
   */
  fun heroFor(host: ServeHost, previewId: String, crop: ContentCrop?): Hero? {
    val perHost = synchronized(bakedLock) { baked.getOrPut(host) { ConcurrentHashMap() } }
    // The bake runs outside the lock; racing callers produce identical content-hashed results.
    perHost[previewId]?.let {
      return it.orElse(null)
    }
    val png = (host.render(previewId, EMPTY_OVERRIDES) as? RenderOutcome.Ok)?.png
    val hero = png?.let { bake(it, crop) }
    perHost[previewId] = Optional.ofNullable(hero)
    return hero
  }

  private val cachedHeroes = ConcurrentHashMap<String, Hero>()

  /**
   * Per cache key, the [Hero.fileName] last persisted to [cacheDir], kept apart from [cachedHeroes]
   * so a failed write is retried.
   */
  private val persistedFileNames = ConcurrentHashMap<String, String>()

  private fun cacheKey(config: CatalogLoadTracker.Config): String =
    sha256Hex("${config.system}\n${config.repo}\n${config.branch}".toByteArray())

  /** Last successful thumbnail for the configured source, restorable without a catalog host. */
  fun cached(config: CatalogLoadTracker.Config): Hero? {
    val key = cacheKey(config)
    cachedHeroes[key]?.let {
      return it
    }
    val dir = cacheDir ?: return null
    val hero =
      runCatching {
        val file = java.io.File(dir, "$key.hero")
        if (file.length() !in 1..(4L * 1024 * 1024)) return null
        java.io.DataInputStream(file.inputStream()).use { input ->
          val width = input.readInt()
          val height = input.readInt()
          if (width !in 1..DISPLAY_CAP || height !in 1..DISPLAY_CAP) return null
          val bytes = input.readBytes()
          val hash = sha256Hex(bytes).take(HASH_CHARS)
          Hero(bytes, "$hash.png", "\"$hash\"", width, height)
        }
      }
        .getOrNull() ?: return null
    cachedHeroes[key] = hero
    persistedFileNames[key] = hero.fileName
    byFileName[hero.fileName] = hero
    return hero
  }

  /** Atomic replacement keeps a restart from reading a partially written thumbnail. */
  fun remember(config: CatalogLoadTracker.Config, hero: Hero) {
    val key = cacheKey(config)
    cachedHeroes[key] = hero
    val dir = cacheDir ?: return
    if (persistedFileNames[key] == hero.fileName) return
    runCatching {
      dir.mkdirs()
      val temp = java.io.File.createTempFile(key, ".tmp", dir)
      try {
        java.io.DataOutputStream(temp.outputStream()).use {
          it.writeInt(hero.cssWidth)
          it.writeInt(hero.cssHeight)
          it.write(hero.bytes)
        }
        java.nio.file.Files.move(
          temp.toPath(),
          java.io.File(dir, "$key.hero").toPath(),
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
      } finally {
        temp.delete()
      }
      persistedFileNames[key] = hero.fileName
    }
      .onFailure {
        System.err.println("serve: could not cache hero for ${config.system}: ${it.message}")
      }
  }

  /** The baked hero a `/hero/<system>/<fileName>` request names, or null when unknown. */
  fun byFileName(fileName: String): Hero? = byFileName[fileName]

  /**
   * The prebaked **catalog-grid** thumbnail for [previewId] on [host] — downscaled so that the
   * region [crop] frames (the whole render when there's no crop) lands at the card's cap. Null when
   * the host has no locally-baked pixels for [previewId] yet, or they can't be decoded; the card
   * then points at the plain `/render/` lane and picks the thumbnail up on a later page build.
   *
   * Sourced from [ServeHost.bakedRender] — **never** [ServeHost.render]. This is called once per
   * card while the catalog page's HTML is being built, on the request thread: `render` fetches a
   * cold preview over the network (images are fetched lazily), so routing it through here would
   * turn one page build into dozens of serial round-trips. `bakedRender` answers from local pixels
   * or not at all, which is exactly the contract this needs.
   *
   * A *missing* PNG is deliberately not memoised (only a decode failure is): a catalog fills its
   * images in after it loads, so a card that had no pixels on the first page build must be able to
   * gain a thumbnail on the next one rather than staying full-resolution until the catalog is
   * republished.
   */
  fun gridThumbFor(host: ServeHost, previewId: String, crop: ContentCrop?): Thumb? {
    val perHost = synchronized(gridLock) { gridBaked.getOrPut(host) { ConcurrentHashMap() } }
    perHost[previewId]?.let {
      return it.orElse(null)
    }
    val png = host.bakedRender(previewId, EMPTY_OVERRIDES)?.png ?: return null
    val thumb = bakeGridThumb(png, crop)
    perHost[previewId] = Optional.ofNullable(thumb)
    return thumb
  }

  /**
   * Bake [png] into a grid thumbnail: scale the **whole** render (the [crop] window stays in CSS,
   * see the class doc) down by the factor that puts the cropped region at [DISPLAY_CAP] ×
   * [PIXEL_SCALE], and content-hash the result. Null when the bytes aren't a decodable image.
   *
   * Keeps whichever of the two encodings is smaller. A render that's already small enough needs no
   * scaling, and even one that is scaled can re-encode larger than the tightly-packed PNG the
   * renderer wrote — this lane exists to cut bytes, so it never ships more of them than it started
   * with. The hash covers the bytes actually served either way.
   *
   * Visible only for tests.
   */
  internal fun bakeGridThumb(png: ByteArray, crop: ContentCrop?): Thumb? {
    val src = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
    if (src.width <= 0 || src.height <= 0) return null
    val region = sourceRegion(src.width, src.height, crop)
    val fit = min(1.0, DISPLAY_CAP * PIXEL_SCALE / max(region.w, region.h).toDouble())
    val scaled =
      if (fit >= 1.0) null
      else
        encodePng(
          drawRegion(
            src,
            Region(0, 0, src.width, src.height),
            max(1, (src.width * fit).roundToInt()),
            max(1, (src.height * fit).roundToInt()),
          )
        )
    val bytes = if (scaled != null && scaled.size < png.size) scaled else png
    val hash = sha256Hex(bytes).take(HASH_CHARS)
    return Thumb(bytes = bytes, hash = hash, etag = "\"$hash\"")
  }

  /**
   * Bake [png] into a hero: apply [crop], scale down to at most [DISPLAY_CAP] × [PIXEL_SCALE],
   * re-encode and register by content hash. Null when undecodable. Visible for tests.
   */
  internal fun bake(png: ByteArray, crop: ContentCrop?): Hero? {
    val src = runCatching { ImageIO.read(ByteArrayInputStream(png)) }.getOrNull() ?: return null
    if (src.width <= 0 || src.height <= 0) return null
    // A capture-gutter crop is never baked into a hero: the pixels outside its box are the
    // component's shadow ([ContentCrop.clip]), and a hero keeps its whole canvas.
    val region = sourceRegion(src.width, src.height, crop?.takeIf { it.clip })
    // The CSS size is the region fitted into the card's cap (never upscaled) — the same size the
    // browser used to compute for itself. The baked raster is PIXEL_SCALE times that, so a 2×
    // display still gets crisp pixels, but never more than the region actually has.
    val fit = min(1.0, DISPLAY_CAP / max(region.w, region.h).toDouble())
    val cssW = max(1, (region.w * fit).roundToInt())
    val cssH = max(1, (region.h * fit).roundToInt())
    val bakedW = max(1, min(region.w, cssW * PIXEL_SCALE))
    val bakedH = max(1, min(region.h, cssH * PIXEL_SCALE))
    val bytes = encodePng(drawRegion(src, region, bakedW, bakedH)) ?: return null
    val hash = sha256Hex(bytes).take(HASH_CHARS)
    val hero =
      Hero(
        bytes = bytes,
        fileName = "$hash.png",
        etag = "\"$hash\"",
        cssWidth = cssW,
        cssHeight = cssH,
      )
    // putIfAbsent, not put: two catalogs whose heroes bake to identical bytes share the URL, and
    // the first registration is already the right answer.
    return byFileName.putIfAbsent(hero.fileName, hero) ?: hero
  }

  /**
   * The rectangle of the source render a hero shows. Without a [crop] that's the whole image; with
   * one it's the component box the card's CSS clip window used to frame.
   *
   * [ContentCrop] is expressed in *display* pixels: the render is drawn at `imgW` wide and shifted
   * by `(left, top)` under a `boxW`×`boxH` window. Dividing back through by the display scale
   * (`imgW / renderW`) recovers the region in the render's own pixels. Clamped to the image so a
   * rounded or stale crop can't ask for pixels that aren't there.
   */
  private fun sourceRegion(renderW: Int, renderH: Int, crop: ContentCrop?): Region {
    if (crop == null || crop.render.w <= 0) return Region(0, 0, renderW, renderH)
    val scale = crop.render.w.toDouble() / renderW
    if (scale <= 0.0) return Region(0, 0, renderW, renderH)
    val x = (-crop.offset.left / scale).roundToInt().coerceIn(0, renderW - 1)
    val y = (-crop.offset.top / scale).roundToInt().coerceIn(0, renderH - 1)
    val w = (crop.window.w / scale).roundToInt().coerceIn(1, renderW - x)
    val h = (crop.window.h / scale).roundToInt().coerceIn(1, renderH - y)
    return Region(x, y, w, h)
  }

  /** Draw [region] of [src] into a fresh [w]×[h] image, smoothly (this is always a downscale). */
  private fun drawRegion(src: BufferedImage, region: Region, w: Int, h: Int): BufferedImage {
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.drawImage(
        src,
        0,
        0,
        w,
        h,
        region.x,
        region.y,
        region.x + region.w,
        region.y + region.h,
        null,
      )
    } finally {
      g.dispose()
    }
    return out
  }

  /** [image] as PNG bytes, or null if the encoder refuses it. */
  private fun encodePng(image: BufferedImage): ByteArray? {
    val buffer = ByteArrayOutputStream()
    if (!runCatching { ImageIO.write(image, "png", buffer) }.getOrDefault(false)) return null
    return buffer.toByteArray()
  }

  private data class Region(val x: Int, val y: Int, val w: Int, val h: Int)

  companion object {
    /**
     * Largest CSS edge a hero is laid out at (the card's `max-height: 240px`, and the
     * [computeThumbCrop] cap).
     */
    const val DISPLAY_CAP = 240

    /** Raster oversampling, so a 2× (retina) display gets real pixels rather than a blur. */
    const val PIXEL_SCALE = 2

    /** Hex characters of the content hash kept in the file name — collision-proof at this scale. */
    private const val HASH_CHARS = 16

    /** The URL prefix the baked heroes are served under. See [ServeHttpServer]'s `/hero/` route. */
    const val PATH_PREFIX = "/hero"

    /**
     * Render-lane query parameter carrying a grid thumbnail's [Thumb.hash]; the hash changes with
     * the pixels, so responses can be `immutable`.
     */
    const val THUMB_PARAM = "thumb"

    private val EMPTY_OVERRIDES = PreviewOverrides()

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
  }
}
