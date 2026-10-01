package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.reference.REFERENCE_FONT_SIZE_PROPERTY
import ee.schimke.composeai.uibuilder.reference.ReferenceAlignment
import ee.schimke.composeai.uibuilder.reference.ReferenceBox
import ee.schimke.composeai.uibuilder.reference.ReferenceComparison
import ee.schimke.composeai.uibuilder.reference.ReferenceComparisonInput
import ee.schimke.composeai.uibuilder.reference.ReferenceFacts
import ee.schimke.composeai.uibuilder.reference.ReferenceFit
import ee.schimke.composeai.uibuilder.reference.ReferenceLayer
import ee.schimke.composeai.uibuilder.reference.ReferencePlacementSpec
import ee.schimke.composeai.uibuilder.reference.ReferenceRaster
import ee.schimke.composeai.uibuilder.reference.compareDifferences
import ee.schimke.composeai.uibuilder.reference.compareLayer
import ee.schimke.composeai.uibuilder.reference.contentBounds
import ee.schimke.composeai.uibuilder.reference.declaredDensityFromName
import ee.schimke.composeai.uibuilder.reference.movedModifierChain
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The reference overlay, for an agent: attach a picture to build against, and measure the design
 * against it — what the editor's **Frame, density and reference** panel does for a person.
 *
 * ## Same arithmetic as the editor
 *
 * Every number here comes from `:ui-builder-export`'s reference engine, the code the browser editor
 * runs over a photograph of its own canvas: [ReferenceFacts] for density and shape,
 * [compareDifferences] for the regions, [compareLayer] for the match, and [movedModifierChain] for
 * the edit. An agent told "move right 12 dp" and a person shown the same thing on the canvas are
 * reading one result. What differs is only the photograph: this host has the design's PNG export
 * (no node boxes) and, where it can compile, the native render (a box for every tagged node).
 *
 * ## The edit is a proposal
 *
 * A comparison never writes. Each matched layer comes back with the `operations` that would make it
 * agree — a `setModifiers` and a `setProperty`, exactly as `ui_builder_apply` takes them — and the
 * agent decides whether to apply them, so the design changes only through the one validated door
 * every other edit goes through.
 */
internal object ServeUiBuilderReferenceTools {

  /** A picture an agent attached: the stored image, with its size and type read from the bytes. */
  fun attachedImage(
    bytes: ByteArray,
    name: String,
    density: Double?,
    sourceUrl: String?,
  ): Pair<StoredReferenceImage?, String?> {
    val mediaType =
      sniffMediaType(bytes) ?: return null to "the bytes are not a PNG, JPEG, WebP or SVG picture"
    // A density stated by the caller travels in the name, the one place the stored record and the
    // editor both already read it from (`card@2x.png`).
    val named =
      name
        .trim()
        .ifEmpty { "Reference" }
        .let { base ->
          if (density == null || declaredDensityFromName(base) != null) base
          else {
            val stem = base.substringBeforeLast('.')
            val extension =
              base.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it != base }
            "$stem@${density.densityText()}x" + (extension?.let { ".$it" } ?: "")
          }
        }
    val decoded =
      if (mediaType == SVG) null
      else runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    return StoredReferenceImage(
      id = "",
      name = named,
      mediaType = mediaType,
      base64 = Base64.getEncoder().encodeToString(bytes),
      widthPx = decoded?.width ?: 0,
      heightPx = decoded?.height ?: 0,
      sourceUrl = sourceUrl,
    ) to null
  }

  /** The base picture's facts against [document]'s frame. */
  fun facts(document: DesignDocumentV1, image: StoredReferenceImage): ReferenceFacts =
    ReferenceFacts(
      widthPx = image.widthPx,
      heightPx = image.heightPx,
      frameWidthDp = document.environment.widthDp.toFloat(),
      frameHeightDp = document.environment.heightDp.toFloat(),
      declaredDensity = if (image.mediaType == SVG) null else declaredDensityFromName(image.name),
      designDensity = document.environment.density.toFloat(),
    )

  fun factsWire(facts: ReferenceFacts, fit: ReferenceFit): UiBuilderReferenceFactsV1 =
    UiBuilderReferenceFactsV1(
      widthPx = facts.widthPx,
      heightPx = facts.heightPx,
      density = facts.density.toDouble(),
      densitySource = facts.densitySource,
      densityBucket = facts.densityBucket,
      widthDp = facts.widthDp.roundToInt(),
      heightDp = facts.heightDp.roundToInt(),
      frameWidthDp = facts.frameWidthDp.roundToInt(),
      frameHeightDp = facts.frameHeightDp.roundToInt(),
      designDensity = facts.designDensity.toDouble(),
      kind = facts.kind.name.replaceFirstChar { it.lowercaseChar() },
      recommendedFit = facts.recommendedFit?.wireValue,
      fit = fit.wireValue,
      pixelComparable = facts.pixelComparable(fit),
      advice = facts.advice(fit),
    )

  /**
   * Measure [document], drawn as [frame], against [reference].
   *
   * [differences] asks for the whole-frame diff; [nodeIds] for a match per layer. A layer match
   * needs boxes, which only the native frame has; on the export frame each requested layer comes
   * back with a message saying so rather than a guessed box.
   */
  fun compare(
    designId: String,
    document: DesignDocumentV1,
    frame: ServeUiBuilderView.Frame,
    reference: StoredReference,
    fit: ReferenceFit?,
    differences: Boolean,
    nodeIds: List<String>,
  ): UiBuilderReferenceComparisonV1 {
    val image =
      reference.image ?: throw ServeUiBuilderView.Refused("`$designId` has no reference picture")
    val picture =
      decode(image)
        ?: throw ServeUiBuilderView.Refused(
          "the reference (${image.mediaType}) cannot be decoded on this host"
        )
    val render =
      runCatching { ImageIO.read(ByteArrayInputStream(frame.png)) }.getOrNull()
        ?: throw ServeUiBuilderView.Refused("the $designId render is not a PNG this host can read")
    val widthDp = document.environment.widthDp.toFloat()
    val perDp = render.width / widthDp.coerceAtLeast(1f)
    val heightDp = render.height / perDp
    val facts = facts(document, image.copy(widthPx = picture.width, heightPx = picture.height))
    val chosenFit = fit ?: facts.recommendedFit ?: ReferenceFit.Contain
    val settings = reference.settings.sanitized()
    val uiDocument = document.toUiBuilderDocument()
    val layers =
      frame.bounds.orEmpty().mapNotNull { (nodeId, box) ->
        val node = uiDocument.nodes[nodeId] ?: return@mapNotNull null
        val bounds =
          ReferenceBox(
            box.x / perDp,
            box.y / perDp,
            (box.x + box.width) / perDp,
            (box.y + box.height) / perDp,
          )
        ReferenceLayer(
          nodeId = nodeId,
          bounds = bounds,
          text = node.componentId.isTextComponent(),
          content = node.contentBounds(bounds),
        )
      }
    val input =
      ReferenceComparisonInput(
        design = render.toRaster(),
        reference = picture.toRaster(),
        frameWidthDp = widthDp,
        frameHeightDp = heightDp,
        placement =
          ReferencePlacementSpec(
            fit = chosenFit,
            scale = settings.scalePercent / 100f,
            offsetXDp = settings.offsetXDp,
            offsetYDp = settings.offsetYDp,
          ),
        facts = facts,
        layers = layers,
        boxMarks =
          reference.marks
            .filter { it.kind == "rectangle" || it.kind == "roundedRectangle" }
            .filter { it.points.size >= 4 }
            .map { mark ->
              val x0 = mark.points[0]
              val y0 = mark.points[1]
              val x1 = mark.points[mark.points.size - 2]
              val y1 = mark.points[mark.points.size - 1]
              ReferenceBox(
                minOf(x0, x1) * widthDp,
                minOf(y0, y1) * heightDp,
                maxOf(x0, x1) * widthDp,
                maxOf(y0, y1) * heightDp,
              )
            },
      )
    val diff = if (differences) compareDifferences(input) else null
    val matches = nodeIds.map { nodeId ->
      if (frame.bounds == null) {
        UiBuilderReferenceLayerV1(
          nodeId = nodeId,
          message =
            "the ${frame.renderer} render reports no node boxes; compare with `renderer: " +
              "\"${ServeUiBuilderView.RENDERER_NATIVE}\"` to match layers",
        )
      } else {
        layerWire(nodeId, compareLayer(input, nodeId, uiDocument.nodes[nodeId]), uiDocument)
      }
    }
    return UiBuilderReferenceComparisonV1(
      designId = designId,
      revision = document.revision,
      renderer = frame.renderer,
      reference =
        UiBuilderReferenceImageSummaryV1(
          name = image.name,
          mediaType = image.mediaType,
          widthPx = picture.width,
          heightPx = picture.height,
          sourceUrl = image.sourceUrl,
        ),
      facts = factsWire(facts, chosenFit),
      differences =
        diff?.diff?.let { summary ->
          UiBuilderReferenceDifferencesV1(
            mismatch = summary.mismatch.toDouble(),
            coverage = summary.coverage.toDouble(),
            regions =
              summary.regions.map {
                UiBuilderReferenceRegionV1(
                  xDp = it.rect.left.roundToInt(),
                  yDp = it.rect.top.roundToInt(),
                  widthDp = it.rect.width.roundToInt(),
                  heightDp = it.rect.height.roundToInt(),
                  mismatch = it.mismatch.toDouble(),
                  nodeId = it.nodeId,
                )
              },
          )
        },
      differencesMessage = diff?.message,
      layers = matches,
      notes =
        buildList {
          if (frame.bounds == null && differences) {
            add(
              "regions are not attributed to layers: the ${frame.renderer} render reports no " +
                "node boxes"
            )
          }
          if (fit == null && chosenFit != ReferenceFit.Contain) {
            add(
              "measured with the `${chosenFit.wireValue}` fit this picture calls for; pass " +
                "`fit` to measure under another"
            )
          }
        },
    )
  }

  private fun layerWire(
    nodeId: String,
    result: ReferenceComparison,
    document: UiBuilderDocument,
  ): UiBuilderReferenceLayerV1 {
    val match = result.match
    val alignment = result.alignment
    return UiBuilderReferenceLayerV1(
      nodeId = nodeId,
      source = match?.source?.name?.replaceFirstChar { it.lowercaseChar() },
      confident = match?.confident,
      textScale = match?.takeIf { it.text }?.scale?.toDouble(),
      current = match?.current?.wire(),
      target = match?.target?.wire(),
      fontSizeSource = result.fontSizeSource,
      alignment =
        alignment
          ?.takeIf { !it.empty }
          ?.let {
            UiBuilderReferenceAlignmentV1(
              moveXDp = it.moveXDp,
              moveYDp = it.moveYDp,
              fontSizeSp = it.fontSizeSp?.toDouble(),
              widthDp = it.widthDp,
              heightDp = it.heightDp,
              summary = it.describe().joinToString(", "),
            )
          },
      alreadyAligned = match != null && (alignment == null || alignment.empty),
      operations = alignment?.let { operations(it, document) }.orEmpty(),
      message = result.message,
    )
  }

  /**
   * The `ui_builder_apply` operations that would make a layer agree, or empty when it already does
   * or the node is gone.
   *
   * The move is written by [movedModifierChain] — padding first, an offset only for what padding
   * cannot say — offered both modifiers; the catalog then accepts or refuses the chain on apply,
   * naming the modifier, which is the same validation an agent's own edit would meet. A size sets
   * that axis's `width`/`height` (or the fixed `size` when the node already has one) and drops a
   * fill on the same axis, which would otherwise win.
   */
  fun operations(alignment: ReferenceAlignment, document: UiBuilderDocument): List<JsonObject> {
    if (alignment.empty) return emptyList()
    val node = document.nodes[alignment.nodeId] ?: return emptyList()
    var chain: List<JsonElement> = node.modifiers.toList()
    if (alignment.moveXDp != 0 || alignment.moveYDp != 0) {
      chain =
        movedModifierChain(chain, alignment.moveXDp, alignment.moveYDp, setOf("padding", "offset"))
          ?: chain
    }
    alignment.widthDp?.let { chain = sized(chain, "width", "widthDp", "fillMaxWidth", it) }
    alignment.heightDp?.let { chain = sized(chain, "height", "heightDp", "fillMaxHeight", it) }
    val operations = mutableListOf<JsonObject>()
    if (chain != node.modifiers.toList()) {
      operations += buildJsonObject {
        put("type", "setModifiers")
        put("nodeId", node.id)
        put("modifiers", JsonArray(chain))
      }
    }
    alignment.fontSizeSp?.let { size ->
      val existing =
        ((node.properties[REFERENCE_FONT_SIZE_PROPERTY] as? JsonObject)?.get("type")
            as? JsonPrimitive)
          ?.contentOrNull
      operations += buildJsonObject {
        put("type", "setProperty")
        put("nodeId", node.id)
        put("property", REFERENCE_FONT_SIZE_PROPERTY)
        put(
          "value",
          buildJsonObject {
            put("type", existing ?: "float")
            put("value", size)
          },
        )
      }
    }
    return operations
  }

  private fun sized(
    chain: List<JsonElement>,
    type: String,
    field: String,
    fill: String,
    dp: Int,
  ): List<JsonElement> {
    fun JsonElement.type() = ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
    val otherFill = if (fill == "fillMaxWidth") "fillMaxHeight" else "fillMaxWidth"
    // A fill on this axis would win over the size; a `fillMaxSize` keeps filling the other one.
    val unfilled = chain.flatMap {
      when (it.type()) {
        fill -> emptyList()
        "fillMaxSize" -> listOf(buildJsonObject { put("type", otherFill) })
        else -> listOf(it)
      }
    }
    val sizeIndex = unfilled.indexOfFirst { it.type() == "size" }
    if (sizeIndex >= 0) {
      return unfilled.toMutableList().also {
        it[sizeIndex] = JsonObject((it[sizeIndex] as JsonObject) + (field to JsonPrimitive(dp)))
      }
    }
    val axis = buildJsonObject {
      put("type", type)
      put(field, dp)
    }
    val index = unfilled.indexOfFirst { it.type() == type }
    return if (index >= 0) unfilled.toMutableList().also { it[index] = axis } else unfilled + axis
  }

  private fun decode(image: StoredReferenceImage): BufferedImage? = runCatching {
    ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(image.base64)))
  }
    .getOrNull()

  private fun BufferedImage.toRaster(): ReferenceRaster {
    val pixels = IntArray(width * height)
    getRGB(0, 0, width, height, pixels, 0, width)
    return ReferenceRaster(width, height, pixels)
  }

  private fun ReferenceBox.wire() =
    UiBuilderReferenceBoxV1(
      xDp = (left * 10).roundToInt() / 10.0,
      yDp = (top * 10).roundToInt() / 10.0,
      widthDp = (width * 10).roundToInt() / 10.0,
      heightDp = (height * 10).roundToInt() / 10.0,
    )

  private fun String.isTextComponent(): Boolean =
    this == "m3/text" || this == "material3/Text" || this == "wear-m3/text" || endsWith("/text")

  private fun Double.densityText(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()

  /** The media type the bytes declare by their first bytes, or null for none a reference may be. */
  fun sniffMediaType(bytes: ByteArray): String? {
    ServeImageFormats.detect(bytes)?.let { format ->
      val type = ServeImageFormats.contentTypeOf(format, bytes)
      return if (type == "image/apng") "image/png" else type.takeIf { it in RASTER_TYPES }
    }
    val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8).trimStart('﻿').trimStart()
    return if (head.startsWith("<svg") || (head.startsWith("<?xml") && "<svg" in head)) SVG
    else null
  }

  private const val SVG = "image/svg+xml"
  private val RASTER_TYPES = setOf("image/png", "image/jpeg", "image/webp")
}

/** `ui_builder_compare_reference`'s reply. Every length is in the design's dp. */
@Serializable
internal data class UiBuilderReferenceComparisonV1(
  val schema: String = UI_BUILDER_REFERENCE_COMPARISON_SCHEMA,
  val designId: String,
  val revision: Long,
  /** `export` or `native`: which render of the design was measured. */
  val renderer: String,
  val reference: UiBuilderReferenceImageSummaryV1,
  val facts: UiBuilderReferenceFactsV1,
  /** Null when not asked for, or when [differencesMessage] says why it could not be measured. */
  val differences: UiBuilderReferenceDifferencesV1? = null,
  val differencesMessage: String? = null,
  val layers: List<UiBuilderReferenceLayerV1> = emptyList(),
  val notes: List<String> = emptyList(),
)

@Serializable
internal data class UiBuilderReferenceImageSummaryV1(
  val name: String,
  val mediaType: String,
  val widthPx: Int,
  val heightPx: Int,
  val sourceUrl: String? = null,
)

/** What the picture is, against the design's frame. See the editor's `ReferenceFacts`. */
@Serializable
internal data class UiBuilderReferenceFactsV1(
  val widthPx: Int,
  val heightPx: Int,
  val density: Double,
  /** `declared` (an `@2x` name), `inferred from the frame width`, or `assumed the design's`. */
  val densitySource: String,
  val densityBucket: String? = null,
  val widthDp: Int,
  val heightDp: Int,
  val frameWidthDp: Int,
  val frameHeightDp: Int,
  val designDensity: Double,
  /** `screen`, `tallScreen`, `region`, `mismatched` or `unknown`. */
  val kind: String,
  val recommendedFit: String? = null,
  /** The fit this reply was measured under: `contain`, `width` or `actual`. */
  val fit: String,
  /** Whether a pixel comparison means anything under [fit]. */
  val pixelComparable: Boolean,
  val advice: String,
)

@Serializable
internal data class UiBuilderReferenceDifferencesV1(
  /** Share of compared pixels that differ, 0..1. */
  val mismatch: Double,
  /** Share of the frame the reference covers, 0..1. */
  val coverage: Double,
  /** Largest first, at most eight. */
  val regions: List<UiBuilderReferenceRegionV1>,
)

@Serializable
internal data class UiBuilderReferenceRegionV1(
  val xDp: Int,
  val yDp: Int,
  val widthDp: Int,
  val heightDp: Int,
  val mismatch: Double,
  /** The smallest layer containing the region's centre, on a render that reports boxes. */
  val nodeId: String? = null,
)

@Serializable
internal data class UiBuilderReferenceLayerV1(
  val nodeId: String,
  /** `boxMark` (a box drawn in the editor), `layoutBox`, or `pixels`. */
  val source: String? = null,
  val confident: Boolean? = null,
  /** For text: the reference's type size over the design's. */
  val textScale: Double? = null,
  val current: UiBuilderReferenceBoxV1? = null,
  val target: UiBuilderReferenceBoxV1? = null,
  val fontSizeSource: String? = null,
  val alignment: UiBuilderReferenceAlignmentV1? = null,
  val alreadyAligned: Boolean = false,
  /** DesignMutationV1 objects for `ui_builder_apply`; empty when nothing should change. */
  val operations: List<JsonObject> = emptyList(),
  val message: String? = null,
)

@Serializable
internal data class UiBuilderReferenceBoxV1(
  val xDp: Double,
  val yDp: Double,
  val widthDp: Double,
  val heightDp: Double,
)

@Serializable
internal data class UiBuilderReferenceAlignmentV1(
  val moveXDp: Int = 0,
  val moveYDp: Int = 0,
  val fontSizeSp: Double? = null,
  val widthDp: Int? = null,
  val heightDp: Int? = null,
  val summary: String,
)

/** `ui_builder_set_reference`'s reply. */
@Serializable
internal data class UiBuilderReferenceAttachedV1(
  val schema: String = UI_BUILDER_REFERENCE_ATTACHED_SCHEMA,
  val designId: String,
  /** False when the call cleared the reference. */
  val attached: Boolean,
  val reference: UiBuilderReferenceImageSummaryV1? = null,
  val facts: UiBuilderReferenceFactsV1? = null,
  val notes: List<String> = emptyList(),
)

internal const val UI_BUILDER_REFERENCE_COMPARISON_SCHEMA =
  "compose-ui-builder-reference-comparison/v1"

internal const val UI_BUILDER_REFERENCE_ATTACHED_SCHEMA = "compose-ui-builder-reference-attached/v1"
