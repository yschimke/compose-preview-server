package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.data.render.PreviewBackdrop
import ee.schimke.composeai.data.render.PreviewClip
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * `?bg=` on `/render/<id>.png`: the preview's resolved stage, composited **into the bytes**.
 *
 * Every surface this server draws already puts a preview on a ground — the grid card, the viewer,
 * the compare wall, the reference-compare page — and every one of them does it in CSS, out of
 * [PreviewBackdrop] for the colour and [PreviewClip] for the shape. The pixels never carry any of
 * it. That is right for the archived artefact (a `showBackground = false` sticker is transparent so
 * a designer can drop it onto any canvas, the header's Transparent toggle inspects the raw alpha,
 * and the fidelity scorer masks with the clip) and wrong the moment a PNG **leaves the page**: a
 * GitHub embed, Copy PNG, a paste into a prompt. Then only the alpha travels, and a dark-first
 * catalog's light-on-nothing sticker lands on white as a blank rectangle.
 *
 * Measured on this repo's own hosted catalog, `appcard__ideal__icon-outlined-gallery-2__compact`:
 * 19.6% of its pixels carry any alpha at all and the ink that is there has mean luminance 237/255.
 * Filed as [wear-m3-catalog#284](https://github.com/yschimke/wear-m3-catalog/issues/284), where the
 * embedded render shows as an all-but-empty frame and the finding — an outline card missing its
 * border — cannot be seen at all.
 *
 * So this is the same two resolvers the pages use, applied to bytes rather than to CSS. It changes
 * nothing about what is baked or published: a request without `bg=` is byte-identical to before,
 * and the stage is a post-processing pass over whatever the lane produced.
 *
 * ### The shape is the point
 *
 * A square of stage under a round Wear capture draws the watch as a rectangle — the fault
 * `wear-device-clip` was written to prevent, and reproduced exactly by an early cut of this code. A
 * square under a *component* sticker is legible but says the component is a black rectangle. So the
 * stage takes the shape of what is actually on the canvas:
 *
 * - [Mode.CIRCLE] for a round device, straight off [PreviewClip], stopping at the bezel.
 * - [Mode.PLATES] for everything else: the islands of non-transparent pixels, each padded out to a
 *   rounded plate, overlapping plates merged. A stage, not a component — it paints no border and
 *   fills no shape the render did not draw, so #284's missing outline is still missing, and now
 *   visible as missing.
 * - [Mode.SQUARE] for the whole frame, when a caller wants the old blunt answer.
 */
object ServeRenderMatte {
  /** The query parameter. Shares its name with the pages' stage/checkerboard toggle by design. */
  const val PARAM: String = "bg"

  /**
   * What ground to composite in.
   *
   * `on`/`off` are accepted as aliases of [AUTO]/[OFF] because the viewer and landing pages already
   * spell the page-wide stage toggle `?bg=on|off`, and a URL that picked one up on the way to a
   * render lane should mean the nearest sensible thing rather than 400.
   */
  enum class Mode(val wire: String) {
    /** Decide from the render and its stage — see [decide]. The one a link should carry. */
    AUTO("auto"),
    /** Content-shaped plates, whatever the render is. */
    PLATES("plates"),
    /** The whole frame. */
    SQUARE("square"),
    /** The device circle, or the frame's inscribed circle when no device says otherwise. */
    CIRCLE("circle"),
    /** The published bytes, untouched. The default when the parameter is absent. */
    OFF("off");

    companion object {
      /** Null for an unknown value, so the caller can 400 rather than guess. */
      fun parse(raw: String?): Mode? {
        val value = raw?.trim()?.lowercase() ?: return null
        return when (value) {
          "on" -> AUTO
          "" -> null
          else -> entries.firstOrNull { it.wire == value }
        }
      }

      /** For the 400's message. */
      fun wires(): String = entries.joinToString(", ") { it.wire }
    }
  }

  /**
   * Everything the matte needs about the preview, resolved by the caller through the same two
   * chains the pages use.
   *
   * The device frame's dp arrive alongside the [clip] because a clip is stated in **dp** and the
   * pixels are not: the scale is `imagePx / frameDp`, and a circle scaled by anything else is a
   * circle in the wrong place. Both null together means the render names no frame, which is the
   * ordinary case for a component sticker.
   */
  data class Stage(
    val backdrop: PreviewBackdrop.Backdrop,
    val clip: PreviewClip.Shape? = null,
    val frameWidthDp: Double? = null,
    val frameHeightDp: Double? = null,
  )

  /**
   * [png] with [mode]'s ground composited under it, or the input unchanged.
   *
   * Unchanged rather than failing is deliberate and applies to every way this can decline:
   * [Mode.OFF], a backdrop that resolved no colour, an image ImageIO cannot decode, one larger than
   * [MAX_SIDE_PX], and [AUTO] deciding the render needs nothing. A render that cannot be matted is
   * still a render, and answering 500 because a cosmetic pass failed would take out the lane this
   * is a convenience on.
   */
  fun apply(png: ByteArray, mode: Mode, stage: Stage): ByteArray {
    if (mode == Mode.OFF) return png
    val colour = parseColour(stage.backdrop.color) ?: return png
    // Size is read from the header BEFORE decoding, not from the decoded image. A `?scroll=long`
    // capture is a full-page render and can be tens of thousands of pixels tall; decoding one to
    // find out it is too big to stage would allocate the whole thing first, which is the cost this
    // ceiling exists to refuse.
    val size = dimensions(png) ?: return png
    if (max(size.first, size.second) > MAX_SIDE_PX) return png
    val image = decode(png) ?: return png
    val stats = statsOf(image)
    val resolved = if (mode == Mode.AUTO) decide(stats, stage) else mode
    if (resolved == Mode.OFF) return png
    val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.color = colour
      when (resolved) {
        Mode.SQUARE -> g.fillRect(0, 0, image.width, image.height)
        Mode.CIRCLE -> g.fill(circleOf(image, stage))
        else -> plates(stats.mask, image.width, image.height).forEach { g.fill(it) }
      }
      g.drawImage(image, 0, 0, null)
    } finally {
      g.dispose()
    }
    return encode(out) ?: png
  }

  /**
   * What [Mode.AUTO] resolves to, from four signals measured in one pass over the pixels.
   *
   * The thresholds are not taste. They come from measuring every candidate signal across a spread
   * of the hosted `remote-m3` catalog and picking the cuts with the widest margins:
   * ```
   * preview                                       solid  interior   pale   AUTO
   * button-child__ideal__disabled__compact        0.000     0.360  0.000   plates
   * button-filled__ideal__disabled__compact       0.000     0.360  0.000   plates
   * text-body__ideal__default__compact            0.015     0.003  1.000   plates
   * button-outlined__ideal__default__compact      0.021     0.001  0.340   plates
   * appcard__ideal__icon-outlined-gallery-2       0.168     0.003  0.993   plates
   * theme-systemthemeswatches__ideal__default     0.256     0.000  0.333   plates
   * button-filled__ideal__default__compact        0.366     0.000  0.959   plates
   * appcard__ideal__content-image__compact        0.621     0.000  0.364   off
   * scaffold__ideal__default__compact             0.781     0.001  0.014   off
   * widgetcontainer-gradientbackground__216dp     0.961     0.001  0.013   off
   * shader-lineargradient__ideal__default         1.000     0.000  0.006   off
   * circularprogressindicator__complete__192dp    0.116     0.001  1.000   circle (round)
   * ```
   *
   * Four questions, in order:
   *
   * 1. **Is the stage light?** Then nothing. A light stage means dark ink, and dark ink is already
   *    legible on the white body of a GitHub issue — a white plate under it would be invisible and
   *    pointless. (A deliberately *white* specimen in a light catalog is genuinely invisible on
   *    white and this does not fix it; nothing colour-shaped can, and `bg=square` is the honest
   *    escape.)
   * 2. **Is it a round device?** Then the circle, always — even for a render the rungs below would
   *    leave alone, because a round capture's readability problem is that its bezel has no edge at
   *    all. This is the rung that stops a watch being drawn as a rounded square.
   * 3. **Is it translucent on the inside?** [Stats.interiorPartialFraction] is the share of the
   *    frame that is *partly* covered and not merely an antialiased edge — a real translucent fill,
   *    which composites to a different colour on every ground and is therefore simply WRONG on any
   *    but its own. Wear's disabled states are the case, and they are the reason this rung exists:
   *    `button-*__disabled` is drawn entirely at reduced alpha, so it has **no solid pixels at
   *    all** — it is invisible on white, and an earlier cut of this code that averaged ink
   *    luminance read its zero solid pixels as "dark ink, fine as it is" and left it that way. The
   *    separation is 0.360 against 0.003, so the cut sits two orders of magnitude clear of both.
   * 4. **Does it paint its own legible ground?** Mostly solid AND not mostly pale: a screen
   *    template, a shader fill, a photo card. Those carry their own surface and need no stage — a
   *    plate under `scaffold` does nothing but poke rounded corners out past the watch face.
   *
   * Everything else is a sticker on transparency, and gets plates.
   */
  private fun decide(stats: Stats, stage: Stage): Mode =
    when {
      !stage.backdrop.isDark -> Mode.OFF
      stage.clip is PreviewClip.Shape.Circle -> Mode.CIRCLE
      stats.coverage <= 0.0 -> Mode.OFF
      stats.interiorPartialFraction >= TRANSLUCENT_INTERIOR -> Mode.PLATES
      stats.solidFraction >= SELF_GROUND_SOLID && stats.paleInkFraction <= PALE_INK_LIMIT ->
        Mode.OFF
      else -> Mode.PLATES
    }

  /**
   * The islands of content, each padded out to a rounded plate, overlapping plates merged.
   *
   * 8-connected, over a union-find of the whole frame — the same single pass a connected-component
   * label always is, and the measured cost of the pass plus the fills is 3–18 ms on this catalog's
   * renders, against 5–12 ms to re-encode the PNG afterwards. The matte is not what makes serving a
   * PNG expensive.
   *
   * Islands under [MIN_ISLAND_PX] are dropped. They are antialiasing crumbs — a stray pixel at the
   * end of a glyph's stroke — and each one would otherwise contribute a 20×20 plate somewhere near
   * the text, which reads as dirt.
   *
   * Merging is why this produces three plates for a card rather than fifty-nine: two boxes that
   * overlap once padded are one region of content, and running the union to a fixed point turns a
   * paragraph's per-glyph islands into one plate per line-block. It is quadratic in the number of
   * boxes and that is fine — the input is already down to the tens after the speck filter, and a
   * render dense enough to defeat it is one where the plates would have merged into the frame
   * anyway.
   */
  private fun plates(mask: BooleanArray, w: Int, h: Int): List<RoundRectangle2D.Float> {
    val boxes = boxesOf(mask, w, h)
    var merged = boxes
    var changed = true
    while (changed) {
      changed = false
      val next = mutableListOf<IntArray>()
      for (box in merged) {
        val hit = next.firstOrNull { overlaps(it, box) }
        if (hit == null) {
          next += box.copyOf()
        } else {
          hit[0] = min(hit[0], box[0])
          hit[1] = min(hit[1], box[1])
          hit[2] = max(hit[2], box[2])
          hit[3] = max(hit[3], box[3])
          changed = true
        }
      }
      merged = next
    }
    return merged.map {
      RoundRectangle2D.Float(
        it[0].toFloat(),
        it[1].toFloat(),
        (it[2] - it[0]).toFloat(),
        (it[3] - it[1]).toFloat(),
        PLATE_RADIUS_PX * 2f,
        PLATE_RADIUS_PX * 2f,
      )
    }
  }

  /** One padded `{x0, y0, x1, y1}` per island big enough to keep. */
  private fun boxesOf(mask: BooleanArray, w: Int, h: Int): MutableList<IntArray> {
    val parent = IntArray(mask.size) { if (mask[it]) it else -1 }
    for (y in 0 until h) {
      for (x in 0 until w) {
        val i = y * w + x
        if (parent[i] < 0) continue
        // The causal half of the 8-neighbourhood; the other half is covered when it is reached.
        if (x > 0) union(parent, i, i - 1)
        if (y > 0) union(parent, i, i - w)
        if (y > 0 && x > 0) union(parent, i, i - w - 1)
        if (y > 0 && x < w - 1) union(parent, i, i - w + 1)
      }
    }
    val byRoot = HashMap<Int, IntArray>()
    for (y in 0 until h) {
      for (x in 0 until w) {
        val i = y * w + x
        if (parent[i] < 0) continue
        val box = byRoot.getOrPut(find(parent, i)) { intArrayOf(x, y, x + 1, y + 1, 0) }
        box[0] = min(box[0], x)
        box[1] = min(box[1], y)
        box[2] = max(box[2], x + 1)
        box[3] = max(box[3], y + 1)
        box[4]++
      }
    }
    return byRoot.values
      .filter { it[4] >= MIN_ISLAND_PX }
      .map {
        intArrayOf(
          it[0] - PLATE_PAD_PX,
          it[1] - PLATE_PAD_PX,
          it[2] + PLATE_PAD_PX,
          it[3] + PLATE_PAD_PX,
        )
      }
      .toMutableList()
  }

  private fun overlaps(a: IntArray, b: IntArray): Boolean =
    b[0] < a[2] && a[0] < b[2] && b[1] < a[3] && a[1] < b[3]

  private fun union(parent: IntArray, a: Int, b: Int) {
    if (parent[b] < 0) return
    val rootA = find(parent, a)
    val rootB = find(parent, b)
    if (rootA != rootB) parent[rootB] = rootA
  }

  private fun find(parent: IntArray, start: Int): Int {
    var i = start
    while (parent[i] != i) {
      parent[i] = parent[parent[i]] // Halving; the trees here are shallow and short-lived.
      i = parent[i]
    }
    return i
  }

  /**
   * The device circle in image pixels, or the frame's inscribed circle when the preview names no
   * device.
   *
   * A clip is dp against the *frame*, and the PNG's pixels are that frame at the render's density,
   * so the scale is the ratio of the two. Falling back to the inscribed circle rather than
   * declining keeps an explicit `bg=circle` predictable: it is what a caller asking for a circle on
   * a square capture can only mean.
   */
  private fun circleOf(image: BufferedImage, stage: Stage): Ellipse2D.Float {
    val shape = stage.clip as? PreviewClip.Shape.Circle
    val widthDp = stage.frameWidthDp
    val heightDp = stage.frameHeightDp
    if (shape == null || widthDp == null || heightDp == null || widthDp <= 0 || heightDp <= 0) {
      val d = min(image.width, image.height).toFloat()
      return Ellipse2D.Float((image.width - d) / 2f, (image.height - d) / 2f, d, d)
    }
    val scaleX = image.width / widthDp
    val scaleY = image.height / heightDp
    val r = shape.radiusDp * min(scaleX, scaleY)
    return Ellipse2D.Float(
      (shape.centerXDp * scaleX - r).toFloat(),
      (shape.centerYDp * scaleY - r).toFloat(),
      (r * 2).toFloat(),
      (r * 2).toFloat(),
    )
  }

  /**
   * The alpha mask plus the four signals [decide] reads, from one pass over the pixels.
   *
   * Not a data class: it carries an array, and array identity equality on a generated `equals` is
   * the kind of thing that is only ever wrong.
   */
  private class Stats(
    /** Alpha >= [ALPHA_MIN]: what the plates are cut from. */
    val mask: BooleanArray,
    /** Share of the frame with any coverage at all. */
    val coverage: Double,
    /** Share of the frame that is all but opaque. */
    val solidFraction: Double,
    /**
     * Share of the frame that is partly covered and **not an edge** — every 4-neighbour is covered
     * too. An antialiased fringe always borders transparency, so it is excluded by construction;
     * what is left is a genuine translucent fill.
     */
    val interiorPartialFraction: Double,
    /** Of the solid pixels, the share close enough to white to vanish on a white page. */
    val paleInkFraction: Double,
  )

  private fun statsOf(image: BufferedImage): Stats {
    val w = image.width
    val h = image.height
    val pixels = image.getRGB(0, 0, w, h, null, 0, w)
    val mask = BooleanArray(pixels.size)
    var covered = 0
    var solid = 0
    var pale = 0
    for (i in pixels.indices) {
      val alpha = pixels[i] ushr 24
      if (alpha < ALPHA_MIN) continue
      mask[i] = true
      covered++
      // Only pixels that are all but opaque count as ink: an antialiased fringe is a blend toward
      // whatever is behind it, so counting it would measure the render's edges, not its colours.
      if (alpha < SOLID_ALPHA) continue
      solid++
      if (luminance(pixels[i]) >= PALE_LUMINANCE) pale++
    }
    // The interior test needs the finished mask, so it is a second pass — over the partial pixels
    // only, which are a few percent of the frame on everything but a translucent render.
    var interior = 0
    if (covered > solid) {
      for (y in 0 until h) {
        for (x in 0 until w) {
          val i = y * w + x
          if (!mask[i]) continue
          if ((pixels[i] ushr 24) >= SOLID_ALPHA) continue
          val edge =
            x == 0 ||
              y == 0 ||
              x == w - 1 ||
              y == h - 1 ||
              !mask[i - 1] ||
              !mask[i + 1] ||
              !mask[i - w] ||
              !mask[i + w]
          if (!edge) interior++
        }
      }
    }
    val n = pixels.size.toDouble()
    return Stats(
      mask = mask,
      coverage = if (pixels.isEmpty()) 0.0 else covered / n,
      solidFraction = if (pixels.isEmpty()) 0.0 else solid / n,
      interiorPartialFraction = if (pixels.isEmpty()) 0.0 else interior / n,
      paleInkFraction = if (solid == 0) 0.0 else pale.toDouble() / solid,
    )
  }

  /** Rec. 601 luma, the same weighting every other legibility check in this server uses. */
  private fun luminance(argb: Int): Int =
    (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

  /** `#AARRGGBB`, the wire form [PreviewBackdrop.Backdrop] carries. */
  private fun parseColour(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (hex.length != 8 && hex.length != 6) return null
    val argb = hex.toLongOrNull(16) ?: return null
    return if (hex.length == 6) Color(argb.toInt() or (0xFF shl 24), true)
    else Color(argb.toInt(), true)
  }

  private fun decode(png: ByteArray): BufferedImage? = runCatching {
    ImageIO.read(ByteArrayInputStream(png))
  }
    .getOrNull()

  /** `width to height` from the image header alone, without decoding the pixels. */
  private fun dimensions(png: ByteArray): Pair<Int, Int>? = runCatching {
    ImageIO.createImageInputStream(ByteArrayInputStream(png)).use { input ->
      val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
      try {
        reader.input = input
        reader.getWidth(0) to reader.getHeight(0)
      } finally {
        reader.dispose()
      }
    }
  }
    .getOrNull()

  private fun encode(image: BufferedImage): ByteArray? = runCatching {
    ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) { "png" } }.toByteArray()
  }
    .getOrNull()

  /** Alpha at or above which a pixel is content rather than nothing. */
  private const val ALPHA_MIN = 8

  /** Alpha at or above which a pixel's colour is the render's own rather than a blend. */
  private const val SOLID_ALPHA = 250

  /** Luminance at or above which a pixel would all but vanish on a white page. */
  private const val PALE_LUMINANCE = 200

  /** Pixels an island needs before it earns a plate. Below this it is antialiasing. */
  private const val MIN_ISLAND_PX = 12

  /**
   * How far a plate stands off its content, in image pixels (~5dp at the density Wear renders at).
   */
  private const val PLATE_PAD_PX = 10

  /** A plate's corner radius, in image pixels. */
  private const val PLATE_RADIUS_PX = 14f

  /**
   * Interior translucency above which the render composites wrongly on any ground but its own.
   * Measured: 0.360 on Wear's disabled states, <= 0.003 on everything else. See [decide].
   */
  private const val TRANSLUCENT_INTERIOR = 0.02

  /** Solid coverage at or above which a render may be presumed to paint its own ground. */
  private const val SELF_GROUND_SOLID = 0.5

  /** Pale share above which that ground is not legible on a white page after all. */
  private const val PALE_INK_LIMIT = 0.5

  /**
   * Longest edge this will touch. A full-page `?scroll=long` capture can be tens of thousands of
   * pixels tall, and the pass is linear in area; declining is better than spending a request thread
   * on a stage nobody asked to wait for.
   */
  private const val MAX_SIDE_PX = 4096
}
