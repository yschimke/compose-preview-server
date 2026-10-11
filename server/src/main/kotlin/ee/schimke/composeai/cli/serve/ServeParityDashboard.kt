package ee.schimke.composeai.cli.serve

/**
 * The view model behind the catalog's Design parity page, computed with no HTML or I/O so it is
 * unit-testable.
 *
 * Two sources of different lifetimes: live from the running host (which previews exist and which
 * carry a design reference, recomputed per request so a stale feed can't claim coverage), and
 * snapshotted from [ParityActivity] (commit log, Figma versions and comments, and design-side gaps
 * the server can't see). Nothing here fetches.
 *
 * Both lanes are keyed onto the same component ([ComponentActivity]), so the page can answer
 * whether the two sides moved together, surfacing [Correlation.CODE_ONLY] /
 * [Correlation.DESIGN_ONLY] where render and reference are about to disagree.
 */
object ServeParityDashboard {

  /** How a component's two sides moved within the feed's window. */
  enum class Correlation {
    /** Both sides changed — the interesting case: they may have converged, or diverged. */
    BOTH,

    /** Code moved, design didn't. The render is likely ahead of its reference. */
    CODE_ONLY,

    /** Design moved, code didn't. The reference is likely ahead of the render. */
    DESIGN_ONLY,
  }

  /** Which feed a timeline row came from. Drives the row's badge and the lane filter. */
  enum class Lane {
    CODE,
    FIGMA_VERSION,
    FIGMA_COMMENT,
  }

  /**
   * One row in the merged reverse-chronological feed. [previewIds] are resolved against the live
   * catalog by [build], so a stale row loses its link rather than 404ing.
   */
  data class FeedEntry(
    val lane: Lane,
    /** ISO-8601; the merge key. */
    val at: String,
    /** The headline — a commit subject, a version label, or a comment body. */
    val title: String,
    val author: String? = null,
    /** Secondary line: a version description, or the commit's short sha. */
    val detail: String? = null,
    /**
     * Outbound link (github.com commit, figma.com node/file), already validated. Null ⇒ no link.
     */
    val href: String? = null,
    /** Label for [href] — "commit 4e73ec2", "open in Figma". */
    val hrefLabel: String? = null,
    /** Live preview ids this row touches, for the inbound links. */
    val previewIds: List<String> = emptyList(),
    /** Component ids for display, deduped. */
    val components: List<String> = emptyList(),
    /** Only meaningful for [Lane.FIGMA_COMMENT]: a resolved comment is shown greyed. */
    val resolved: Boolean = false,
  )

  /** A component that moved on at least one side within the window. */
  data class ComponentActivity(
    /** Display name — the catalog's component id when known, else the preview key. */
    val name: String,
    val correlation: Correlation,
    val codeEvents: Int,
    val designEvents: Int,
    /** Most recent timestamp on either side, ISO-8601. */
    val lastAt: String,
    /** A preview to link to, when the catalog still has one. */
    val previewId: String? = null,
    /** Whether that preview carries a design reference (⇒ the compare lane is reachable). */
    val hasReference: Boolean = false,
  )

  /** Preview-side coverage, computed live from the served catalog. */
  data class Coverage(
    /** Distinct components in the catalog (light/dark/state variants folded into one). */
    val components: Int,
    /** Of those, how many have at least one design reference. */
    val mapped: Int,
    /** Component display names with no reference, sorted, capped for display. */
    val unmapped: List<UnmappedComponent>,
    /** How many unmapped components were dropped from [unmapped] by the display cap. */
    val unmappedOverflow: Int,
  ) {
    val unmappedCount: Int
      get() = components - mapped

    /** 0–100, rounded. 100 when the catalog has no components (nothing is unmapped). */
    val percent: Int
      get() = if (components <= 0) 100 else (mapped * 100 + components / 2) / components
  }

  /** One component with no design reference, and a preview to open it by. */
  data class UnmappedComponent(val name: String, val previewId: String)

  /** One row in the exhaustive code ↔ design inventory shown behind the comparison disclosure. */
  data class Comparison(
    val name: String,
    /** Representative preview for opening the render or its reference comparison. */
    val previewId: String,
    val hasReference: Boolean,
    /** Exact reference asset to score in the browser; null when only mapping presence is known. */
    val referenceId: String? = null,
    /**
     * The catalog's component id as the comparison wall spells it
     * (`ServeIssueReport.componentIdFor`), so the row links to `compare?component=<id>`. [name] is
     * prose, not a route slug. See `docs/design/COMPARE_NAVIGATION.md` §3.4.
     */
    val componentId: String = "",
  )

  /** Everything [ServeWeb.parityPage] renders. */
  data class Dashboard(
    val coverage: Coverage,
    val feed: List<FeedEntry>,
    val components: List<ComponentActivity>,
    val gaps: List<MappingGap>,
    val comparisons: List<Comparison> = emptyList(),
    val generatedAt: String? = null,
    val windowDays: Int? = null,
    /** Figma file this catalog is specified by, when the feed named one. */
    val figmaFileName: String? = null,
    val figmaFileHref: String? = null,
    val codeRepo: String? = null,
    val codeRef: String? = null,
  ) {
    /** Whether a producer feed backed this page at all (vs. coverage-only). */
    val hasActivity: Boolean
      get() = feed.isNotEmpty() || gaps.isNotEmpty()

    val openComments: Int
      get() = feed.count { it.lane == Lane.FIGMA_COMMENT && !it.resolved }
  }

  /** Unmapped components listed inline before the list collapses to a count. */
  private const val UNMAPPED_DISPLAY_CAP = 24

  /** Rows in the merged feed. Beyond this the page stops being scannable. */
  private const val FEED_CAP = 60

  /**
   * Build the dashboard for one session. [previews] is the live catalog; [hasReference] answers
   * whether a preview carries a design reference; [activity] is the published feed, or null for a
   * coverage-only page.
   */
  fun build(
    previews: List<ServePreview>,
    hasReference: (String) -> Boolean,
    activity: ParityActivity?,
    referenceIdFor: (String) -> String? = { null },
  ): Dashboard {
    val components = componentsOf(previews)
    val coverage = coverageOf(components, hasReference)
    val live = previews.map { it.id }.toSet()
    val previewToComponent = components.flatMap { c -> c.previewIds.map { it to c } }.toMap()

    val codeEvents = activity?.code?.events.orEmpty()
    val figmaLane = activity?.figma

    val feed = buildList {
      for (event in codeEvents) {
        val ids = event.previewIds.filter { it in live }
        add(
          FeedEntry(
            lane = Lane.CODE,
            at = event.at,
            title = event.subject,
            author = event.author,
            detail = "commit ${event.sha.take(7)}",
            href = ServeParityActivityStore.commitUrl(activity?.code?.repo, event.sha),
            hrefLabel = "view commit",
            previewIds = ids,
            components = displayComponents(event.components, ids, previewToComponent),
          )
        )
      }
      for (version in figmaLane?.versions.orEmpty()) {
        add(
          FeedEntry(
            lane = Lane.FIGMA_VERSION,
            at = version.at,
            title = version.label?.takeIf { it.isNotBlank() } ?: "Autosaved version",
            author = version.author,
            detail = version.description,
            href = ServeParityActivityStore.fileUrl(figmaLane?.fileKey),
            hrefLabel = "open file",
          )
        )
      }
      for (comment in figmaLane?.comments.orEmpty()) {
        val ids = comment.previewIds.filter { it in live }
        add(
          FeedEntry(
            lane = Lane.FIGMA_COMMENT,
            at = comment.at,
            title = comment.message,
            author = comment.author,
            detail = comment.nodeId?.let { "node $it" },
            href =
              ServeParityActivityStore.nodeUrl(figmaLane?.fileKey, comment.nodeId)
                ?: ServeParityActivityStore.fileUrl(figmaLane?.fileKey),
            hrefLabel = if (comment.nodeId != null) "open node" else "open file",
            previewIds = ids,
            components = displayComponents(comment.components, ids, previewToComponent),
            resolved = comment.resolved,
          )
        )
      }
    }
      // Ties broken by lane so a code commit and a Figma save stamped to the same minute keep a
      // stable order across rebuilds — the fixture goldens depend on it.
      .sortedWith(compareByDescending<FeedEntry> { it.at }.thenBy { it.lane.ordinal })
      .take(FEED_CAP)

    return Dashboard(
      coverage = coverage,
      feed = feed,
      components = correlate(feed, components, hasReference),
      gaps = activity?.gaps.orEmpty(),
      comparisons =
        components.map { component ->
          // Score a concrete mapped variant when one exists. A component's default/light card is
          // usually mapped, but catalogs may intentionally attach the reference to another state.
          val mappedPreviewId = component.previewIds.firstOrNull(hasReference)
          val comparisonPreviewId = mappedPreviewId ?: component.representative
          Comparison(
            name = component.name,
            previewId = comparisonPreviewId,
            hasReference = mappedPreviewId != null,
            referenceId = mappedPreviewId?.let(referenceIdFor),
            componentId =
              previews
                .firstOrNull { it.id == comparisonPreviewId }
                ?.let(ServeIssueReport::componentIdFor)
                .orEmpty(),
          )
        },
      generatedAt = activity?.generatedAt,
      windowDays = activity?.windowDays,
      figmaFileName = figmaLane?.fileName,
      figmaFileHref = ServeParityActivityStore.fileUrl(figmaLane?.fileKey),
      codeRepo = activity?.code?.repo,
      codeRef = activity?.code?.ref,
    )
  }

  /** One catalog component, folded across its theme / state / props renders. */
  internal data class Component(
    val key: String,
    val name: String,
    val previewIds: List<String>,
    /** The preview a link should open — the default render when the catalog marks one. */
    val representative: String,
  )

  /**
   * Fold [previews] onto one entry per component, keyed like the grid's cards
   * ([ServeWeb.componentKey]'s rule, restated to keep this HTML-free): the slug head before
   * `__ideal`, else the theme-stripped id.
   */
  internal fun componentsOf(previews: List<ServePreview>): List<Component> =
    previews
      .groupBy { componentKey(it) }
      .map { (key, group) ->
        // Prefer the catalog's own component id for display, and prefer a default light render as
        // the thing a link opens — that's the card the grid shows.
        val representative =
          group.firstOrNull { it.state.isDefault() && it.theme != "dark" }
            ?: group.firstOrNull { it.state.isDefault() }
            ?: group.first()
        Component(
          key = key,
          name = group.firstNotNullOfOrNull { it.componentId } ?: humanize(key),
          previewIds = group.map { it.id },
          representative = representative.id,
        )
      }
      .sortedBy { it.name.lowercase() }

  private fun String?.isDefault(): Boolean = this == null || this == "default"

  private fun componentKey(p: ServePreview): String {
    val idx = p.id.indexOf("__ideal")
    if (idx > 0) return p.id.substring(0, idx)
    val parts = p.id.split("__")
    val themeIdx =
      parts.indices.lastOrNull { it >= 1 && (parts[it] == "light" || parts[it] == "dark") }
    return if (themeIdx == null) p.id
    else parts.filterIndexed { i, _ -> i != themeIdx }.joinToString("__")
  }

  /** `button-filled` → `Button filled`; a fully-qualified preview id keeps its last segment. */
  private fun humanize(key: String): String {
    val tail = key.substringAfterLast('.').substringAfterLast('/')
    return tail.replace('-', ' ').replace('_', ' ').trim().replaceFirstChar { it.uppercaseChar() }
  }

  private fun coverageOf(components: List<Component>, hasReference: (String) -> Boolean): Coverage {
    val (mapped, unmapped) = components.partition { c -> c.previewIds.any(hasReference) }
    return Coverage(
      components = components.size,
      mapped = mapped.size,
      unmapped =
        unmapped.take(UNMAPPED_DISPLAY_CAP).map { UnmappedComponent(it.name, it.representative) },
      unmappedOverflow = (unmapped.size - UNMAPPED_DISPLAY_CAP).coerceAtLeast(0),
    )
  }

  /**
   * Key both lanes onto components and classify how they moved. Rows naming no component (e.g. a
   * shared utility commit) contribute nothing.
   */
  private fun correlate(
    feed: List<FeedEntry>,
    components: List<Component>,
    hasReference: (String) -> Boolean,
  ): List<ComponentActivity> {
    val byName = components.associateBy { it.name }
    val code = mutableMapOf<String, Int>()
    val design = mutableMapOf<String, Int>()
    val last = mutableMapOf<String, String>()
    for (entry in feed) {
      for (name in entry.components) {
        val bucket = if (entry.lane == Lane.CODE) code else design
        bucket[name] = (bucket[name] ?: 0) + 1
        val previous = last[name]
        if (previous == null || entry.at > previous) last[name] = entry.at
      }
    }
    return (code.keys + design.keys)
      .map { name ->
        val codeCount = code[name] ?: 0
        val designCount = design[name] ?: 0
        val component = byName[name]
        ComponentActivity(
          name = name,
          correlation =
            when {
              codeCount > 0 && designCount > 0 -> Correlation.BOTH
              codeCount > 0 -> Correlation.CODE_ONLY
              else -> Correlation.DESIGN_ONLY
            },
          codeEvents = codeCount,
          designEvents = designCount,
          lastAt = last[name].orEmpty(),
          previewId = component?.representative,
          hasReference = component?.previewIds?.any(hasReference) == true,
        )
      }
      // One-sided movement first — that's the actionable half — then most recent.
      .sortedWith(
        compareBy<ComponentActivity> { if (it.correlation == Correlation.BOTH) 1 else 0 }
          .thenByDescending { it.lastAt }
          .thenBy { it.name.lowercase() }
      )
  }

  /**
   * Component names for a feed row: the live catalog's when the preview ids resolve (so differently
   * spelled names don't split one component), else the producer's own.
   */
  private fun displayComponents(
    declared: List<String>,
    previewIds: List<String>,
    previewToComponent: Map<String, Component>,
  ): List<String> {
    val live = previewIds.mapNotNull { previewToComponent[it]?.name }.distinct()
    return live.ifEmpty { declared.distinct() }
  }
}
