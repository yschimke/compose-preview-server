package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * `ui_builder_view`: the editor canvas as a person looking at it sees it, for an agent
 * (compose-preview-server#1114, R1).
 *
 * ## What it is made of
 *
 * Nothing new is rendered here. The frame is one of the two renders this server already makes of a
 * design — the PNG export (`ui_builder_export` with `format: "png"`, the editor's own renderer) or
 * the native Compose render (`ui_builder_render_native`) — and the overlays are the state kept
 * beside the design that the editor draws over its canvas: the selection outline, the reference
 * picture, the comment pins and the layout bounds. They are drawn here with `java.awt` onto the
 * frame, and the same facts come back as JSON beside the picture, so an agent can both look at the
 * view and address what it sees by node id.
 *
 * ## Where the rectangles come from, and when there are none
 *
 * A node's box is only ever the box a renderer reported. The PNG export reports none, so a view
 * drawn from it carries no bounds and says so in [UiBuilderViewV1.boundsUnavailable] rather than
 * inventing a layout; the native lane reports the box of every tagged node in its own frame's
 * pixels, which is why a view that needs bounds draws that frame instead of mixing two renders
 * whose pixels need not agree. A selected node or a node-pinned comment with no box is listed as
 * unplaced, never drawn at a guessed position.
 */
internal object ServeUiBuilderView {
  const val INCLUDE_SELECTION = "selection"
  const val INCLUDE_REFERENCE = "reference"
  const val INCLUDE_COMMENTS = "comments"
  const val INCLUDE_BOUNDS = "bounds"

  /** Every overlay a caller may ask for, in the order they are drawn. */
  val INCLUDES = listOf(INCLUDE_REFERENCE, INCLUDE_BOUNDS, INCLUDE_SELECTION, INCLUDE_COMMENTS)

  /**
   * What the editor shows by default: the selection, the reference when one is attached, and the
   * discussion's pins. Layout bounds are a debugging overlay a person turns on, so they are too.
   */
  val DEFAULT_INCLUDES = setOf(INCLUDE_SELECTION, INCLUDE_REFERENCE, INCLUDE_COMMENTS)

  const val RENDERER_EXPORT = "export"
  const val RENDERER_NATIVE = "native"

  /** The largest viewport edge a caller may ask for; bounds the bytes one call can produce. */
  const val MAX_VIEWPORT_PX = 4096

  private val SELECTION_COLOR = Color(0x1A, 0x73, 0xE8)
  private val BOUNDS_COLOR = Color(0xE0, 0x3A, 0xC8, 0xB0)
  private val PIN_OPEN_COLOR = Color(0xF2, 0x99, 0x00)
  private val PIN_RESOLVED_COLOR = Color(0x80, 0x80, 0x80)
  private const val FIRST_COMMENT_PREVIEW = 160

  /** A box in some frame's pixels. */
  data class Box(val x: Int, val y: Int, val width: Int, val height: Int)

  /**
   * The picture overlays are drawn on, and the boxes its renderer reported in its own pixels. Null
   * [bounds] means the renderer reports none — not that it placed nothing.
   */
  class Frame(val png: ByteArray, val renderer: String, val bounds: Map<String, Box>?)

  /** A request this surface refuses, in a sentence the caller can act on. */
  class Refused(message: String) : RuntimeException(message)

  /**
   * Draws the view and describes it.
   *
   * [notes] carries what the caller should know about a lane that could not give everything asked
   * for; it is returned inside the reply, never logged instead.
   */
  fun compose(
    designId: String,
    document: DesignDocumentV1,
    frame: Frame,
    include: Set<String>,
    selection: List<String>,
    reference: StoredReference?,
    board: StoredCommentBoard?,
    viewport: Pair<Int, Int>?,
    notes: List<String> = emptyList(),
  ): UiBuilderViewV1 {
    val base =
      runCatching { ImageIO.read(ByteArrayInputStream(frame.png)) }.getOrNull()
        ?: throw Refused("the $designId render is not a PNG this host can read")
    val renderWidth = base.width
    val renderHeight = base.height
    val environment = document.environment
    val scale =
      viewport?.let { (width, height) ->
        min(width.toDouble() / renderWidth, height.toDouble() / renderHeight)
      } ?: 1.0
    val width = max(1, (renderWidth * scale).roundToInt())
    val height = max(1, (renderHeight * scale).roundToInt())
    val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val remarks = notes.toMutableList()
    val graphics = out.createGraphics()
    try {
      graphics.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_BILINEAR,
      )
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      graphics.drawImage(base, 0, 0, width, height, null)

      fun Box.scaled(): Box =
        Box(
          (x * scale).roundToInt(),
          (y * scale).roundToInt(),
          max(1, (this.width * scale).roundToInt()),
          max(1, (this.height * scale).roundToInt()),
        )

      val boxes = frame.bounds?.mapValues { (_, box) -> box.scaled() }

      val referenceReport =
        if (INCLUDE_REFERENCE !in include) null
        else drawReference(graphics, out, reference, environment.widthDp, width)

      if (INCLUDE_BOUNDS in include && boxes != null) {
        graphics.color = BOUNDS_COLOR
        graphics.stroke = BasicStroke(1f)
        boxes.values.forEach { graphics.drawRect(it.x, it.y, it.width - 1, it.height - 1) }
      }

      if (INCLUDE_SELECTION in include && selection.isNotEmpty()) {
        graphics.color = SELECTION_COLOR
        graphics.stroke = BasicStroke(2f)
        selection.forEach { nodeId ->
          val box = boxes?.get(nodeId)
          if (box == null) {
            remarks +=
              if (nodeId !in document.nodes) "selected node `$nodeId` is not in this design"
              else "selected node `$nodeId` has no box in this render, so it is not outlined"
          } else {
            graphics.drawRect(box.x, box.y, max(1, box.width - 2), max(1, box.height - 2))
          }
        }
      }

      val pins =
        if (INCLUDE_COMMENTS !in include || board == null) emptyList()
        else pinsFor(board, boxes, width, height)
      pins.forEach { pin -> if (pin.x != null && pin.y != null) drawPin(graphics, pin) }

      val nodes =
        boxes
          .orEmpty()
          .filter { (_, box) ->
            box.x < width && box.y < height && box.x + box.width > 0 && box.y + box.height > 0
          }
          .map { (nodeId, box) ->
            UiBuilderViewNodeV1(
              nodeId = nodeId,
              componentId = document.nodes[nodeId]?.componentId,
              x = box.x,
              y = box.y,
              width = box.width,
              height = box.height,
              selected = nodeId in selection,
            )
          }
          .sortedWith(compareBy({ it.y }, { it.x }, { it.nodeId }))

      val png = ByteArrayOutputStream().also { ImageIO.write(out, "png", it) }.toByteArray()
      return UiBuilderViewV1(
        designId = designId,
        revision = document.revision,
        renderer = frame.renderer,
        include = INCLUDES.filter { it in include },
        selection = selection,
        image =
          UiBuilderViewImageV1(
            widthPx = width,
            heightPx = height,
            scale = scale,
            sha256 = sha256(png),
          ),
        frame =
          UiBuilderViewFrameV1(
            widthDp = environment.widthDp,
            heightDp = environment.heightDp,
            density = environment.density,
            renderWidthPx = renderWidth,
            renderHeightPx = renderHeight,
          ),
        nodes = nodes,
        boundsUnavailable =
          if (frame.bounds != null) null
          else
            "the ${frame.renderer} renderer reports no layout bounds; call again with " +
              "`renderer: \"$RENDERER_NATIVE\"` on a host with a native render lane for node boxes",
        comments = pins,
        reference = referenceReport,
        notes = remarks,
        imageBase64 = Base64.getEncoder().encodeToString(png),
      )
    } finally {
      graphics.dispose()
    }
  }

  /**
   * The reference picture, placed the way the editor places it: scaled from the frame's width by
   * `scalePercent`, offset by the stored dp offset, at the stored opacity. `split` shows it on the
   * leading `splitPercent` of the frame and `difference` subtracts it; `boxes` draws no picture.
   */
  private fun drawReference(
    graphics: java.awt.Graphics2D,
    canvas: BufferedImage,
    reference: StoredReference?,
    widthDp: Int,
    width: Int,
  ): UiBuilderViewReferenceV1? {
    if (reference == null) return null
    val settings = reference.settings.sanitized()
    fun report(drawn: Boolean, reason: String? = null) =
      UiBuilderViewReferenceV1(
        drawn = drawn,
        visible = settings.visible,
        mode = settings.mode,
        opacityPercent = settings.opacityPercent,
        reason = reason,
      )
    val image = reference.image ?: return report(false, "the reference has no base picture")
    if (!settings.visible) return report(false, "the reference is hidden in the editor")
    if (settings.mode == "boxes") return report(false, "`boxes` mode draws no picture")
    val picture =
      runCatching { ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.base64))) }
        .getOrNull() ?: return report(false, "${image.mediaType} cannot be decoded on this host")
    val pxPerDp = width.toDouble() / max(1, widthDp)
    val drawWidth = max(1, (width * settings.scalePercent / 100.0).roundToInt())
    val drawHeight = max(1, (picture.height * drawWidth.toDouble() / picture.width).roundToInt())
    val left = (settings.offsetXDp * pxPerDp).roundToInt()
    val top = (settings.offsetYDp * pxPerDp).roundToInt()
    val alpha = settings.opacityPercent / 100f
    if (settings.mode == "difference") {
      val scaled = BufferedImage(drawWidth, drawHeight, BufferedImage.TYPE_INT_ARGB)
      scaled
        .createGraphics()
        .also { it.drawImage(picture, 0, 0, drawWidth, drawHeight, null) }
        .dispose()
      for (y in 0 until drawHeight) {
        val cy = top + y
        if (cy !in 0 until canvas.height) continue
        for (x in 0 until drawWidth) {
          val cx = left + x
          if (cx !in 0 until canvas.width) continue
          val a = canvas.getRGB(cx, cy)
          val b = scaled.getRGB(x, y)
          fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (abs(ca - cb) - ca) * alpha).roundToInt().coerceIn(0, 255)
          }
          canvas.setRGB(
            cx,
            cy,
            (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0),
          )
        }
      }
      return report(true)
    }
    val clip = graphics.clip
    if (settings.mode == "split") {
      graphics.clipRect(
        0,
        0,
        (canvas.width * settings.splitPercent / 100.0).roundToInt(),
        canvas.height,
      )
    }
    val composite = graphics.composite
    graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
    graphics.drawImage(picture, left, top, drawWidth, drawHeight, null)
    graphics.composite = composite
    graphics.clip = clip
    return report(true)
  }

  /**
   * One pin per thread, numbered in the board's order. A point anchor is a frame fraction; a node
   * anchor sits at the node's top-left corner when the render placed the node; a mark anchor is on
   * the reference overlay, whose strokes this view does not redraw, so it is reported unplaced.
   */
  private fun pinsFor(
    board: StoredCommentBoard,
    boxes: Map<String, Box>?,
    width: Int,
    height: Int,
  ): List<UiBuilderViewPinV1> =
    board.threads.mapIndexed { index, thread ->
      val anchor = thread.anchor
      val point =
        when {
          anchor?.x != null && anchor.y != null ->
            Pair(
              (anchor.x!! * width).roundToInt().coerceIn(0, width - 1),
              (anchor.y!! * height).roundToInt().coerceIn(0, height - 1),
            )
          anchor?.nodeId != null -> anchor.nodeId?.let { boxes?.get(it) }?.let { Pair(it.x, it.y) }
          else -> null
        }
      val kind =
        when {
          anchor?.x != null && anchor.y != null -> "point"
          anchor?.nodeId != null -> "node"
          anchor?.markId != null -> "mark"
          else -> "design"
        }
      val first = thread.comments.firstOrNull()
      UiBuilderViewPinV1(
        threadId = thread.id,
        number = index + 1,
        resolved = thread.resolved,
        anchor = kind,
        nodeId = anchor?.nodeId,
        markId = anchor?.markId,
        x = point?.first,
        y = point?.second,
        placed = point != null,
        comments = thread.comments.size,
        author = first?.displayName?.takeIf { it.isNotBlank() } ?: first?.authorId,
        firstComment =
          first?.body?.let {
            if (it.length > FIRST_COMMENT_PREVIEW) it.take(FIRST_COMMENT_PREVIEW) + "…" else it
          },
      )
    }

  private fun drawPin(graphics: java.awt.Graphics2D, pin: UiBuilderViewPinV1) {
    val x = pin.x ?: return
    val y = pin.y ?: return
    val radius = 10
    graphics.color = if (pin.resolved) PIN_RESOLVED_COLOR else PIN_OPEN_COLOR
    graphics.fillOval(x - radius, y - radius, radius * 2, radius * 2)
    graphics.color = Color.WHITE
    graphics.stroke = BasicStroke(2f)
    graphics.drawOval(x - radius, y - radius, radius * 2, radius * 2)
    // A host without fonts still gets the pin; its number is in the JSON either way.
    runCatching {
      graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 11)
      val label = pin.number.toString()
      val metrics = graphics.fontMetrics
      graphics.drawString(
        label,
        x - metrics.stringWidth(label) / 2,
        y + (metrics.ascent - metrics.descent) / 2,
      )
    }
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * What `ui_builder_view` answers with, beside the picture.
 *
 * Its own shape, like a native render's: the released contract defines no request for a view. Every
 * coordinate is in the returned image's pixels, so a client can put [nodes] and [comments] straight
 * over the picture without knowing how it was scaled; [UiBuilderViewImageV1.scale] and [frame] say
 * how to get back to render pixels and dp. [imageBase64] is the picture itself and is never sent as
 * text: the MCP surface moves it into a fetchable link or an image block.
 */
@Serializable
internal data class UiBuilderViewV1(
  val schema: String = "compose-preview/ui-builder-view/v1",
  val designId: String,
  val revision: Long,
  /** `export` (the editor's own renderer) or `native` (real Compose on this host). */
  val renderer: String,
  val include: List<String>,
  val selection: List<String>,
  val image: UiBuilderViewImageV1,
  val frame: UiBuilderViewFrameV1,
  /** Every node the render placed inside the picture, top to bottom. */
  val nodes: List<UiBuilderViewNodeV1> = emptyList(),
  /** Why [nodes] is empty although the design has nodes, or null when the renderer reported. */
  val boundsUnavailable: String? = null,
  val comments: List<UiBuilderViewPinV1> = emptyList(),
  /** Null when the caller did not ask for the reference or the design has none attached. */
  val reference: UiBuilderViewReferenceV1? = null,
  val notes: List<String> = emptyList(),
  val imageBase64: String? = null,
)

@Serializable
internal data class UiBuilderViewImageV1(
  val mediaType: String = "image/png",
  val widthPx: Int,
  val heightPx: Int,
  /** Image pixels per render pixel: 1 unless a viewport scaled the frame. */
  val scale: Double,
  val sha256: String,
  /** A short-lived signed https URL of this picture, on a host with a public origin. */
  val url: String? = null,
  val expiresAtEpochSeconds: Long? = null,
)

@Serializable
internal data class UiBuilderViewFrameV1(
  val widthDp: Int,
  val heightDp: Int,
  val density: Double,
  val renderWidthPx: Int,
  val renderHeightPx: Int,
)

@Serializable
internal data class UiBuilderViewNodeV1(
  val nodeId: String,
  val componentId: String? = null,
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val selected: Boolean = false,
)

@Serializable
internal data class UiBuilderViewPinV1(
  val threadId: String,
  /** The number drawn on the pin, in board order. */
  val number: Int,
  val resolved: Boolean,
  /** `point`, `node`, `mark` or `design` (pinned to nothing in particular). */
  val anchor: String,
  val nodeId: String? = null,
  val markId: String? = null,
  val x: Int? = null,
  val y: Int? = null,
  /** Whether the pin is drawn; a node the render did not place, or a mark, is not. */
  val placed: Boolean,
  val comments: Int,
  val author: String? = null,
  val firstComment: String? = null,
)

@Serializable
internal data class UiBuilderViewReferenceV1(
  val drawn: Boolean,
  val visible: Boolean,
  val mode: String,
  val opacityPercent: Int,
  val reason: String? = null,
)
