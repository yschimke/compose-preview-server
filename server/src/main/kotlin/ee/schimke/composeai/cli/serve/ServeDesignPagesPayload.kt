package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageNode
import ee.schimke.composeai.designpages.PageNodeConfidence
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The design-pages join as **data** — `GET /{system}/pages.json` and `GET
 * /{system}/pages/{page}.json`.
 *
 * Every other lane this server publishes already has a machine surface: `/index.json` for the
 * previews, `/parity.json` for coverage and drift, `/parity/known-differences.json` for the
 * acceptances. The pages lane had none, so the one question it answers — *which cells on this kit
 * sheet have code behind them, and which preview draws each* — could only be read by parsing the
 * view's markup, where the interesting rows sit deep in the DOM keyed off a coloured dot. That is
 * the reading task a consumer is most likely to get quietly wrong, and the join is not derivable
 * from the endpoints that do exist: `parity.json` counts *this catalog's components that carry a
 * design reference*, and this counts *the design sheet's nodes that carry code*. The two lanes
 * disagreeing is a finding in its own right, and until now seeing it meant diffing an HTML page
 * against a JSON document.
 *
 * ## The same numbers the view prints, not the manifest read back
 *
 * [DesignPage.linked] / [DesignPage.coverageTotal] are what the card and the header state, and the
 * per-node flags below ([PageNodeDto.component], [PageNodeDto.gap]) are the ones the sheet marks
 * on. A consumer that recomputed them from the raw manifest would have to re-derive four exclusions
 * — private furniture, the kit's base parts, variant-set containers and unlinked placements — and
 * would get a different number the first time any of them moved. Serialising the *derived* view is
 * the point: this is the page's own arithmetic, addressable.
 *
 * [PageNodeDto.renderable] is likewise resolved against what the session actually publishes, the
 * way the view resolves it before drawing a render, so a node mapped to a preview this catalog
 * dropped reads as mapped-but-not-drawable rather than as a render a consumer can fetch.
 *
 * ## What is deliberately not here
 *
 * No URLs. A page's view, export and JSON are `…/pages/{id}`, `…/pages/{id}.svg` and
 * `…/pages/{id}.json` off the same base the caller already used, and the ids are here; minting
 * absolute links would mean baking this request's token and session spelling into a cacheable
 * document for no reading a consumer cannot do itself.
 */
internal object ServeDesignPagesPayload {

  const val SCHEMA: String = "compose-preview-serve/pages/v1"

  private val JSON = Json { encodeDefaults = true }

  fun index(system: String, module: String, pages: List<DesignPage>): String =
    JSON.encodeToString(
      DesignPagesIndexResponse.serializer(),
      DesignPagesIndexResponse(
        system = system,
        module = module,
        pages =
          pages.map { page ->
            DesignPageSummaryDto(
              page = page.id,
              name = page.name,
              nodeId = page.nodeId,
              inventory = page.inventory,
              implemented = page.linked.size,
              total = page.coverageTotal,
              nodes = page.nodes.size,
            )
          },
      ),
    )

  fun page(
    system: String,
    module: String,
    page: DesignPage,
    /** The design ref for a node, filled from the manifest's file key when it wrote none. */
    refFor: (PageNode) -> String,
    /** Preview ids this session can actually render. See [PageNodeDto.renderable]. */
    renderablePreviewIds: Set<String>,
  ): String {
    val gaps = page.coverageGaps.toSet()
    return JSON.encodeToString(
      DesignPageResponse.serializer(),
      DesignPageResponse(
        system = system,
        module = module,
        page = page.id,
        name = page.name,
        nodeId = page.nodeId,
        inventory = page.inventory,
        implemented = page.linked.size,
        total = page.coverageTotal,
        nodes =
          page.nodes.map { node ->
            PageNodeDto(
              nodeId = node.nodeId,
              name = node.name,
              depth = node.depth,
              ref = refFor(node),
              link = node.link.wire,
              code = node.code,
              previewId = node.previewId,
              confidence =
                when (node.confidence) {
                  PageNodeConfidence.HIGH -> "high"
                  PageNodeConfidence.LOW -> "low"
                  null -> null
                },
              type = node.type,
              component = node.isComponent,
              gap = node in gaps,
              container = node.isContainer,
              cell = node.cell,
              renderable = node.renderablePreviewId?.let { it in renderablePreviewIds } ?: false,
            )
          },
      ),
    )
  }
}

/** `GET /{system}/pages.json` — the published sheets and what each one claims. */
@Serializable
internal data class DesignPagesIndexResponse(
  val schema: String = ServeDesignPagesPayload.SCHEMA,
  /** The session this catalog is served under — the `{system}` segment. */
  val system: String,
  /** The catalog's own label, as the pages' chrome prints it. */
  val module: String,
  val pages: List<DesignPageSummaryDto> = emptyList(),
)

@Serializable
internal data class DesignPageSummaryDto(
  /** The page's id — the `{page}` segment of `…/pages/{page}.json`. */
  val page: String,
  val name: String,
  val nodeId: String,
  /**
   * Whether this sheet is a component inventory a catalog is measured against. `false` is the kit's
   * icon page, and there [implemented] and [total] are both `0` — no fraction to state, which is a
   * different fact from a sheet with nothing implemented.
   */
  val inventory: Boolean = true,
  /** Nodes with code behind them, counted as [DesignPage.linked] — the card's numerator. */
  val implemented: Int = 0,
  /** Components a catalog could implement, [DesignPage.coverageTotal] — NOT [nodes]. */
  val total: Int = 0,
  /** Every addressable node on the sheet, furniture and containers included. */
  val nodes: Int = 0,
)

/** `GET /{system}/pages/{page}.json` — one sheet's node → code join. */
@Serializable
internal data class DesignPageResponse(
  val schema: String = ServeDesignPagesPayload.SCHEMA,
  val system: String,
  val module: String,
  val page: String,
  val name: String,
  val nodeId: String,
  val inventory: Boolean = true,
  val implemented: Int = 0,
  val total: Int = 0,
  /** In the design file's own order, exactly as the view lists them. */
  val nodes: List<PageNodeDto> = emptyList(),
)

/** One node on the sheet: the manifest's own fields, plus the marks the view draws. */
@Serializable
internal data class PageNodeDto(
  val nodeId: String,
  val name: String = "",
  val depth: Int = 0,
  /** `figma:<fileKey>/<nodeId>` — always present, so an unlinked node still deep-links. */
  val ref: String = "",
  /** How the join was made: `code-connect`, `manifest`, `convention` or `unlinked`. */
  val link: String,
  val code: String? = null,
  val previewId: String? = null,
  val confidence: String? = null,
  /** The design tool's node type, e.g. `COMPONENT`, `COMPONENT_SET`, `INSTANCE`. */
  val type: String? = null,
  /**
   * Whether this node counts — a concrete, public component, not the sheet's private furniture, a
   * base part, a variant set or an unclaimed placement. The four exclusions are why [component] is
   * stated rather than left for a consumer to re-derive.
   */
  val component: Boolean = false,
  /** An unlinked [component]: the thing a reader means by *not implemented yet*. */
  val gap: Boolean = false,
  /** A `COMPONENT_SET` (or a producer-stated grouping): the box its variants came in. */
  val container: Boolean = false,
  /** Drawn by an override cell — a `_VARIANT_` capture of a neighbour, not a preview of its own. */
  val cell: Boolean = false,
  /**
   * Whether **this session** can draw the node: it names a preview, the link claims it, and the
   * catalog publishes that render. A mapped node whose preview this catalog dropped is `false` here
   * while keeping its [previewId] — the mapping is still true, the picture is not fetchable.
   */
  val renderable: Boolean = false,
)
