package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * `ui_builder_view` with `frames`, `durationMs`, `times` or `ambient`: a watch face over time, for
 * an agent that cannot otherwise see motion (yschimke/watchface-tools#39).
 *
 * ## How a frame is drawn
 *
 * The editor's renderer reads its clock from `environment.fixedTime` (UI Builder 3.140.0 evaluates
 * `[SECOND_MILLISECOND]`, `[MILLISECOND]`, `[UTC_TIMESTAMP]` and the other time sources against
 * it). So a frame is the design with that one field set to the frame's instant, drawn by the same
 * PNG export the canvas and a plain view use, on a scratch copy that is never stored: no revision
 * moves and nobody watching the design hears about it. Animation and Sequence playback arrive with
 * a later builder release through the same export, with nothing to change here.
 *
 * A design that pins `environment.fixedTime` has frozen its own clock, and every frame honours that
 * pin, so all its frames are the same picture; the reply says so rather than overriding the author.
 *
 * ## Ambient
 *
 * The builder's renderer draws the interactive mode. `ambient` projects each `wff/variant` with
 * `mode: AMBIENT` onto the node that holds it — the WFF meaning of a Variant: in ambient, the
 * parent's `target` attribute takes `value` — before drawing, which is exactly what the watch does.
 * It is a projection of the document's own declarations, never a lookalike, and is replaced by the
 * builder's own ambient rendering once it has one.
 *
 * ## Kept lean
 *
 * At most [MAX_FRAMES] frames, each scaled to fit [MAX_CELL_PX], and the encoded picture under
 * [MAX_OUTPUT_BYTES]; the caller enforces a per-account budget and renders on the builder renderer,
 * never the native runtime and never inside the UI Builder service's lock.
 */
internal object UiBuilderMotionView {
  const val MIN_FRAMES = 2
  const val MAX_FRAMES = 12
  /** Frames drawn when only `durationMs` is given. */
  const val DEFAULT_FRAMES = 6
  /** The window drawn when only `frames` is given: one second, the period of a seconds pulse. */
  const val DEFAULT_DURATION_MS = 1_000L
  /** A day: long enough for an hour hand, short enough that nobody asks for a year of frames. */
  const val MAX_DURATION_MS = 24L * 60 * 60 * 1000

  /** The largest edge one frame is drawn at in the reply; larger renders are scaled down. */
  const val MAX_CELL_PX = 320

  /** Frames per row of the filmstrip. */
  const val MAX_COLUMNS = 4

  /** The largest encoded picture one call returns. */
  const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024

  /** One PNG grid, every frame labelled by its time: the easiest for a model to read. */
  const val FORMAT_FILMSTRIP = "filmstrip"
  /**
   * An animated PNG. Still `image/png`, so every lane that carries a view carries it unchanged, and
   * a viewer that does not animate PNGs shows its first frame.
   */
  const val FORMAT_ANIMATED = "animated"
  val FORMATS = listOf(FORMAT_FILMSTRIP, FORMAT_ANIMATED)

  const val VARIANT_COMPONENT = "wff/variant"
  const val AMBIENT_MODE = "AMBIENT"

  private const val LABEL_BAND_PX = 18
  private const val GAP_PX = 4
  private val BACKGROUND = Color(0x20, 0x20, 0x20)
  private val LABEL_COLOR = Color(0xE8, 0xE8, 0xE8)
  private val CLOCK: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
  private val FIXED_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

  /** A request this surface refuses, in a sentence the caller can act on. */
  class Refused(message: String) : RuntimeException(message)

  /** One frame to draw: the instant its clock reads, and whether it is the ambient mode. */
  data class Shot(val epochMillis: Long, val ambient: Boolean)

  /**
   * The frames a call asks for. [times] are explicit instants and exclude [frames] and
   * [durationMs]; otherwise [frames] instants are spread evenly over [durationMs] from
   * [startMillis] with the window's end left out, so an animated loop does not show its first frame
   * twice. [ambient] alone draws the interactive and the ambient mode at the same instant, side by
   * side.
   */
  fun plan(
    frames: Long?,
    durationMs: Long?,
    times: List<Long>?,
    ambient: Boolean,
    startMillis: Long,
  ): List<Shot> {
    if (times != null) {
      if (frames != null || durationMs != null) {
        throw Refused("pass `times`, or `frames` and `durationMs`, not both")
      }
      if (times.size !in MIN_FRAMES..MAX_FRAMES) {
        throw Refused("`times` takes $MIN_FRAMES to $MAX_FRAMES instants, not ${times.size}")
      }
      if (times.any { it < 0 }) throw Refused("`times` are epoch milliseconds, never negative")
      return times.map { Shot(it, ambient) }
    }
    if (frames == null && durationMs == null) {
      return listOf(Shot(startMillis, false), Shot(startMillis, true))
    }
    val count = frames ?: DEFAULT_FRAMES.toLong()
    if (count !in MIN_FRAMES..MAX_FRAMES) {
      throw Refused("`frames` must be from $MIN_FRAMES to $MAX_FRAMES, not $count")
    }
    val window = durationMs ?: DEFAULT_DURATION_MS
    if (window !in 1..MAX_DURATION_MS) {
      throw Refused("`durationMs` must be from 1 to $MAX_DURATION_MS (a day), not $window")
    }
    return (0 until count.toInt()).map { Shot(startMillis + window * it / count, ambient) }
  }

  /** [document] as it is drawn for [shot]; see the class KDoc for the clock and ambient rules. */
  fun documentFor(document: DesignDocumentV1, shot: Shot): DesignDocumentV1 {
    val timed =
      if (document.environment.fixedTime != null) document
      else
        document.copy(
          environment =
            document.environment.copy(
              fixedTime = FIXED_TIME.format(Instant.ofEpochMilli(shot.epochMillis)),
            ),
        )
    return if (shot.ambient) ambient(timed) else timed
  }

  /**
   * How many `wff/variant mode=AMBIENT` overrides [document] declares on a node that holds them.
   */
  fun ambientVariantCount(document: DesignDocumentV1): Int = ambientOverrides(document).size

  /**
   * Every `wff/variant mode=AMBIENT` applied to the node whose slot holds it: the parent's `target`
   * property takes `value`, typed as the property already is, else as the number it reads as.
   */
  fun ambient(document: DesignDocumentV1): DesignDocumentV1 {
    val overrides = ambientOverrides(document)
    if (overrides.isEmpty()) return document
    val nodes = document.nodes.toMutableMap()
    overrides.forEach { (parentId, target, value) ->
      val parent = nodes[parentId] ?: return@forEach
      val typed = typedLike(parent.properties[target], value) ?: return@forEach
      nodes[parentId] = parent.copy(properties = parent.properties + (target to typed))
    }
    return document.copy(nodes = nodes)
  }

  private data class Override(val parentId: String, val target: String, val value: String)

  private fun ambientOverrides(document: DesignDocumentV1): List<Override> {
    val parentOf = buildMap {
      document.nodes.forEach { (id, node) ->
        node.slots.values.forEach { children -> children.forEach { put(it, id) } }
      }
    }
    return document.nodes.values
      .filter { it.componentId == VARIANT_COMPONENT }
      .filter { it.properties.text("mode").equals(AMBIENT_MODE, ignoreCase = true) }
      .mapNotNull { variant ->
        val parent = parentOf[variant.id] ?: return@mapNotNull null
        val target = variant.properties.text("target") ?: return@mapNotNull null
        val value = variant.properties.text("value") ?: return@mapNotNull null
        Override(parent, target, value)
      }
  }

  private fun Map<String, UiValueV1>.text(name: String): String? =
    when (val value = this[name]) {
        is StringValueV1 -> value.value
        is IntegerValueV1 -> value.value.toString()
        is DecimalValueV1 -> value.value.toString()
        else -> null
      }
      ?.trim()
      ?.takeIf { it.isNotEmpty() }

  private fun typedLike(existing: UiValueV1?, value: String): UiValueV1? =
    when (existing) {
      is StringValueV1 -> StringValueV1(value)
      is IntegerValueV1 ->
        value.toLongOrNull()?.let(::IntegerValueV1)
          ?: value.toDoubleOrNull()?.let { IntegerValueV1(it.roundToInt().toLong()) }
      is DecimalValueV1 -> value.toDoubleOrNull()?.let(::DecimalValueV1)
      else ->
        value.toLongOrNull()?.let(::IntegerValueV1)
          ?: value.toDoubleOrNull()?.let(::DecimalValueV1)
          ?: StringValueV1(value)
    }

  /** One drawn frame: its shot and the export's PNG. */
  class Frame(val shot: Shot, val png: ByteArray)

  /** What [compose] made: the picture and where each frame sits in it. */
  class Picture(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val placed: List<ServeUiBuilderView.Box>,
    val renderWidth: Int,
    val renderHeight: Int,
    val cellWidth: Int,
    val cellHeight: Int,
  )

  /**
   * The frames as one picture in [format]. Each frame is scaled to fit [cellBox] (at most
   * [MAX_CELL_PX] a side) and labelled with its UTC clock time and offset from the first frame.
   */
  fun compose(frames: List<Frame>, format: String, cellBox: Pair<Int, Int>?): Picture {
    require(frames.isNotEmpty())
    val images = frames.map { frame ->
      runCatching { ImageIO.read(ByteArrayInputStream(frame.png)) }.getOrNull()
        ?: throw Refused("a frame's render is not a PNG this host can read")
    }
    val renderWidth = images.maxOf { it.width }
    val renderHeight = images.maxOf { it.height }
    val (boxWidth, boxHeight) =
      cellBox?.let { (w, h) -> min(w, MAX_CELL_PX) to min(h, MAX_CELL_PX) }
        ?: (MAX_CELL_PX to MAX_CELL_PX)
    val scale =
      min(1.0, min(boxWidth.toDouble() / renderWidth, boxHeight.toDouble() / renderHeight))
    val cellWidth = max(1, (renderWidth * scale).roundToInt())
    val cellHeight = max(1, (renderHeight * scale).roundToInt())
    val start = frames.first().shot.epochMillis
    val labels = frames.map { label(it.shot, start) }
    val picture =
      if (format == FORMAT_ANIMATED) animated(frames, images, labels, cellWidth, cellHeight)
      else filmstrip(images, labels, cellWidth, cellHeight)
    if (picture.png.size > MAX_OUTPUT_BYTES) {
      throw Refused(
        "${frames.size} frames encode to ${picture.png.size} bytes, over the " +
          "$MAX_OUTPUT_BYTES-byte cap; ask for fewer frames or a smaller `viewport`",
      )
    }
    return Picture(
      picture.png,
      picture.width,
      picture.height,
      picture.placed,
      renderWidth,
      renderHeight,
      cellWidth,
      cellHeight,
    )
  }

  private class Drawn(
    val png: ByteArray,
    val width: Int,
    val height: Int,
    val placed: List<ServeUiBuilderView.Box>,
  )

  fun label(shot: Shot, start: Long): String =
    CLOCK.format(Instant.ofEpochMilli(shot.epochMillis)) +
      " +${shot.epochMillis - start}ms" +
      if (shot.ambient) " ambient" else ""

  private fun filmstrip(
    images: List<BufferedImage>,
    labels: List<String>,
    cellWidth: Int,
    cellHeight: Int,
  ): Drawn {
    val columns = min(MAX_COLUMNS, images.size)
    val rows = (images.size + columns - 1) / columns
    val pitchX = cellWidth + GAP_PX
    val pitchY = cellHeight + LABEL_BAND_PX + GAP_PX
    val width = columns * pitchX + GAP_PX
    val height = rows * pitchY + GAP_PX
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val placed = mutableListOf<ServeUiBuilderView.Box>()
    val graphics = out.createGraphics()
    try {
      graphics.hints()
      graphics.color = BACKGROUND
      graphics.fillRect(0, 0, width, height)
      images.forEachIndexed { index, image ->
        val x = GAP_PX + (index % columns) * pitchX
        val y = GAP_PX + (index / columns) * pitchY
        graphics.drawImage(image, x, y, cellWidth, cellHeight, null)
        placed += ServeUiBuilderView.Box(x, y, cellWidth, cellHeight)
        drawLabel(graphics, labels[index], x, y + cellHeight, cellWidth)
      }
    } finally {
      graphics.dispose()
    }
    return Drawn(encode(out), width, height, placed)
  }

  private fun animated(
    frames: List<Frame>,
    images: List<BufferedImage>,
    labels: List<String>,
    cellWidth: Int,
    cellHeight: Int,
  ): Drawn {
    val height = cellHeight + LABEL_BAND_PX
    val pngs = images.mapIndexed { index, image ->
      val out = BufferedImage(cellWidth, height, BufferedImage.TYPE_INT_RGB)
      val graphics = out.createGraphics()
      try {
        graphics.hints()
        graphics.color = BACKGROUND
        graphics.fillRect(0, 0, cellWidth, height)
        graphics.drawImage(image, 0, 0, cellWidth, cellHeight, null)
        drawLabel(graphics, labels[index], 0, cellHeight, cellWidth)
      } finally {
        graphics.dispose()
      }
      encode(out)
    }
    // Each frame shows until the next one's instant; the last for the median step, so the loop
    // keeps the cadence it was sampled at.
    val steps =
      frames
        .zipWithNext { a, b -> b.shot.epochMillis - a.shot.epochMillis }
        .map { it.coerceAtLeast(0) }
    val last = steps.sorted().getOrNull(steps.size / 2) ?: DEFAULT_DURATION_MS
    val delays = (steps + last).map { it.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS).toInt() }
    val placed = List(frames.size) { ServeUiBuilderView.Box(0, 0, cellWidth, cellHeight) }
    return Drawn(Apng.assemble(pngs, cellWidth, height, delays), cellWidth, height, placed)
  }

  private const val MIN_DELAY_MS = 20L
  private const val MAX_DELAY_MS = 10_000L

  private fun java.awt.Graphics2D.hints() {
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
  }

  /** A host without fonts still gets the frames; each label is in the JSON either way. */
  private fun drawLabel(graphics: java.awt.Graphics2D, text: String, x: Int, y: Int, width: Int) {
    runCatching {
      graphics.color = LABEL_COLOR
      graphics.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
      val metrics = graphics.fontMetrics
      val clipped =
        if (metrics.stringWidth(text) <= width) text
        else text.substringBefore(" +").takeIf { metrics.stringWidth(it) <= width } ?: ""
      graphics.drawString(clipped, x + 2, y + LABEL_BAND_PX - 5)
    }
  }

  private fun encode(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

  /**
   * An animated PNG from same-sized PNG frames: the first frame's IDAT is the default image, the
   * rest are `fdAT` chunks, each behind its own `fcTL`, looping forever.
   */
  internal object Apng {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun assemble(frames: List<ByteArray>, width: Int, height: Int, delaysMs: List<Int>): ByteArray {
      require(frames.isNotEmpty() && frames.size == delaysMs.size)
      val parsed = frames.map(::chunks)
      val header = parsed.first().first { it.first == "IHDR" }.second
      val out = ByteArrayOutputStream()
      out.write(SIGNATURE)
      chunk(out, "IHDR", header)
      chunk(out, "acTL", ints(frames.size, 0))
      var sequence = 0
      parsed.forEachIndexed { index, chunks ->
        chunk(out, "fcTL", frameControl(sequence++, width, height, delaysMs[index]))
        chunks
          .filter { it.first == "IDAT" }
          .forEach { (_, data) ->
            if (index == 0) chunk(out, "IDAT", data)
            else chunk(out, "fdAT", ints(sequence++) + data)
          }
      }
      chunk(out, "IEND", ByteArray(0))
      return out.toByteArray()
    }

    /** Every chunk of [png] as (type, data). */
    fun chunks(png: ByteArray): List<Pair<String, ByteArray>> {
      require(png.size >= 8 && png.copyOfRange(0, 8).contentEquals(SIGNATURE)) { "not a PNG" }
      val result = mutableListOf<Pair<String, ByteArray>>()
      var offset = 8
      while (offset + 8 <= png.size) {
        val length =
          ((png[offset].toInt() and 0xFF) shl 24) or
            ((png[offset + 1].toInt() and 0xFF) shl 16) or
            ((png[offset + 2].toInt() and 0xFF) shl 8) or
            (png[offset + 3].toInt() and 0xFF)
        val type = String(png, offset + 4, 4, Charsets.US_ASCII)
        result += type to png.copyOfRange(offset + 8, offset + 8 + length)
        offset += 12 + length
      }
      return result
    }

    private fun frameControl(sequence: Int, width: Int, height: Int, delayMs: Int): ByteArray {
      val bytes = ByteArrayOutputStream()
      DataOutputStream(bytes).apply {
        writeInt(sequence)
        writeInt(width)
        writeInt(height)
        writeInt(0)
        writeInt(0)
        writeShort(delayMs)
        writeShort(1000)
        writeByte(0) // dispose: none
        writeByte(0) // blend: source
      }
      return bytes.toByteArray()
    }

    private fun ints(vararg values: Int): ByteArray {
      val bytes = ByteArrayOutputStream()
      DataOutputStream(bytes).apply { values.forEach { writeInt(it) } }
      return bytes.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
      val typeBytes = type.toByteArray(Charsets.US_ASCII)
      DataOutputStream(out).writeInt(data.size)
      out.write(typeBytes)
      out.write(data)
      val crc =
        CRC32().apply {
          update(typeBytes)
          update(data)
        }
      DataOutputStream(out).writeInt(crc.value.toInt())
    }
  }
}

/** `motion` in a [UiBuilderViewV1]: the frames a motion view drew, in the picture's order. */
@Serializable
internal data class UiBuilderViewMotionV1(
  /** `filmstrip` (one labelled grid) or `animated` (an animated PNG). */
  val format: String,
  val frames: List<UiBuilderViewMotionFrameV1>,
  /** How many different pictures the frames are: 1 means nothing moved over these instants. */
  val distinctFrames: Int,
  /** The design's own `environment.fixedTime`, which every frame honours, or null. */
  val pinnedFixedTime: String? = null,
  /** How many `wff/variant mode=AMBIENT` overrides an ambient frame applied. */
  val ambientVariants: Int = 0,
  /** Each frame's edge in the picture, after scaling to fit the cell. */
  val cellWidthPx: Int,
  val cellHeightPx: Int,
)

@Serializable
internal data class UiBuilderViewMotionFrameV1(
  val index: Int,
  /** The instant the frame's clock reads, in epoch milliseconds. */
  val epochMillis: Long,
  /** Milliseconds after the first frame. */
  val offsetMs: Long,
  /** The label drawn under the frame: its UTC clock time. */
  val label: String,
  val ambient: Boolean,
  /** Where the frame sits in the picture (the whole picture, for `animated`). */
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  /** The frame's render, before scaling and labelling; equal hashes are identical renders. */
  val sha256: String,
  /** Whether this frame's render differs from the one before it. Always true for the first. */
  val changed: Boolean,
)
