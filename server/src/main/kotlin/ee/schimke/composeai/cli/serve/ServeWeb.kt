package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantProtocol
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.bundle.BundleVerifier
import ee.schimke.composeai.daemon.protocol.UiMode
import ee.schimke.composeai.data.overrides.PreviewOverrideOption
import ee.schimke.composeai.data.render.PreviewBackdrop
import ee.schimke.composeai.data.render.PreviewBackground
import ee.schimke.composeai.data.render.PreviewClip
import ee.schimke.composeai.designpages.DesignPage
import ee.schimke.composeai.designpages.PageBlendMode
import ee.schimke.composeai.designpages.PageLayerPlacement
import ee.schimke.composeai.designpages.PageNode
import ee.schimke.composeai.imagecrop.ContentCrop
import ee.schimke.composeai.web.WebEscaping
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Server-rendered HTML for the `compose-preview serve` web surface. Large static CSS/JS lives in
 * classpath assets served by [ServeWebAssets]; this object keeps the dynamic HTML and small
 * value-injected bootstraps that need token, session, or preview data.
 */
object ServeWeb {

  /**
   * Sign-in affordance for a GitHub-protected live stream lane.
   *
   * Deliberately names no repository: live streams gate only on being signed in.
   * [restrictedToAllowedUsers] reflects `--github-auth-users`, which restricts sign-in itself, so
   * the prompt must not promise that any GitHub account works.
   */
  data class LiveAuthPrompt(
    val loginHref: String,
    val restrictedToAllowedUsers: Boolean = false,
  )

  /** Front-door GitHub auth state, shown when the public server protects code-running surfaces. */
  data class GitHubAuthStatus(
    val loginHref: String,
    /**
     * `POST` target of the "Sign out" control ([ServeGithubAuth.logoutPath]), carrying the current
     * page as its `return`. Null offers no sign-out. A one-button form rather than a link, so a
     * prefetcher cannot sign the visitor out.
     */
    val logoutHref: String? = null,
    val login: String? = null,
    val restrictedToAllowedUsers: Boolean = false,
    /**
     * What the sign-in unlocks on this page, which the tooltip describes. Live streams open to any
     * signed-in visitor; the playground also needs [accessRepository], so a catalog whose only
     * gated lane is the playground must not promise Live. [LIVE] is the default for
     * catalog-independent pages (front door, `/status`).
     */
    val lane: GatedLane = GatedLane.LIVE,
    /**
     * `--github-auth-repo`, named only when [lane] is [GatedLane.PLAYGROUND]; deliberately absent
     * from the Live wording.
     */
    val accessRepository: String? = null,
    /**
     * Whether this host sends Web Push ([ServePushNotifier]), and so whether Settings offers the
     * **Notifications** group ([pushNotificationSettings]).
     */
    val notifications: Boolean = false,
  )

  /** The capability a header sign-in control speaks for. See [GitHubAuthStatus.lane]. */
  enum class GatedLane {
    LIVE,
    PLAYGROUND,
  }

  /**
   * What the front door may say about the **UI builder**, and why not when the visitor may not use
   * it.
   *
   * Carries the decision, not the credential: the handler asks the same
   * [ServeUiBuilderAuthorization] the create route asks ([UiBuilderRouteCapability.WRITE]), so the
   * card never offers an action the POST refuses. [deniedReason] exists so a signed-in visitor
   * without access is told why instead of seeing nothing.
   */
  data class UiBuilderInvite(
    /** The catalogs this host actually runs the builder for; every other card offers nothing. */
    val systems: Set<String>,
    /**
     * Whether anybody is signed in. Creating a document is a write, so the action is offered to
     * signed-in visitors only.
     */
    val signedIn: Boolean,
    /** Whether this visitor's credential actually carries the builder's write capability. */
    val permitted: Boolean,
    /** Plain-language explanation, shown in place of the action when [permitted] is false. */
    val deniedReason: String = "",
    /** Editor entry point; absolute when the server configures a dedicated builder hostname. */
    val editorHref: String = "/ui-builder/",
  )

  /**
   * Absolute URLs advertised to link unfurlers for a browser-facing page. [imageUrl] is what the
   * page represents; utility/error pages leave it null for a text-only card. Supplied by the HTTP
   * layer, which alone knows the external scheme/host (e.g. behind Caddy TLS).
   *
   * [imageWidth]/[imageHeight] are the real PNG dimensions. Without them Slack and Google may drop
   * the image rather than measure it; they also pick the card type ([twitterCard]).
   */
  data class UnfurlMetadata(
    val pageUrl: String,
    val imageUrl: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
  )

  /**
   * Minimum edge for a `summary_large_image` card. Slack and Twitter/X fall back to the small card
   * below roughly this size, and Google recommends 512² as a floor.
   */
  private const val LARGE_CARD_MIN_EDGE = 320

  /**
   * Aspect (width ÷ height) band worth claiming a `summary_large_image` for.
   *
   * Consumers crop the large card to roughly 1.91:1, so a picture far from that shape loses most of
   * itself (a 0.45 portrait phone hero keeps a blank horizontal strip). 1.25..2.4 is where about a
   * third of the image starts to disappear; outside it `summary` shows the whole image. The front
   * door and catalog landings use a 1200×630 [ServeSocialCard] and sit inside the band by
   * construction.
   */
  private const val LARGE_CARD_MIN_ASPECT = 1.25

  private const val LARGE_CARD_MAX_ASPECT = 2.4

  /**
   * `twitter:card` for an unfurl: the large-image card only when the image is big enough
   * ([LARGE_CARD_MIN_EDGE]) and close enough to the crop shape
   * ([LARGE_CARD_MIN_ASPECT]..[LARGE_CARD_MAX_ASPECT]). Unknown dimensions keep the large card; the
   * fetcher measures it itself.
   */
  private fun twitterCard(unfurl: UnfurlMetadata): String {
    if (unfurl.imageUrl == null) return "summary"
    val w = unfurl.imageWidth
    val h = unfurl.imageHeight
    if (w == null || h == null) return "summary_large_image"
    if (w < LARGE_CARD_MIN_EDGE || h < LARGE_CARD_MIN_EDGE) return "summary"
    val aspect = w.toDouble() / h
    return if (aspect in LARGE_CARD_MIN_ASPECT..LARGE_CARD_MAX_ASPECT) "summary_large_image"
    else "summary"
  }

  /** Aggregate engagement metrics surfaced by the live server UI/API. */
  data class PreviewEngagement(val views: Long = 0)

  private fun assetHref(name: String): String = ServeWebAssets.href(name)

  /**
   * The verdict band a published match percentage falls in — the chip's colour, and nothing else.
   *
   * Restates `matchBand` in `compose-ai-tools/scripts/design-artifacts/design-reference-score.mjs`,
   * where the number is minted; thresholds come from real catalog distributions. A band never
   * decides whether the number is shown, so drift between the copies only costs a hue.
   */
  /**
   * One variant of the component a viewer page is showing, for the **compare strip** under its
   * render ([comparisonStripHtml]).
   *
   * Identity only: every URL on the strip is built by [comparisonStripHtml] from `basePath`, the
   * link query and the asset generation, so it never loses the token or `?at=` pin.
   */
  data class ComponentVariant(
    val previewId: String,
    /** How this variant differs from its siblings — `ServeIssueReport.variantFor`. */
    val variant: String,
    /** This variant's imported design reference, when it publishes one. */
    val referenceId: String? = null,
    /**
     * The match the delivery branch published for this pair, when it has one. The strip scores
     * nothing itself: the viewer bundle is near its size budget, and the focused comparison scores
     * live.
     */
    val matchPercent: Double? = null,
    /**
     * The paired catalog's render of this variant when the `compareWith` + `parallel` pairing
     * resolves — the same URL the stage uses ([SpecSource.rasterUrl]). Null when the sibling does
     * not draw it; shown as an empty frame, since a missing cell is a finding.
     */
    val parallelRenderUrl: String? = null,
  )

  private fun specMatchBand(percent: Double): String =
    when {
      percent >= 95.0 -> "match"
      percent >= 85.0 -> "close"
      else -> "off"
    }

  /**
   * The **compare strip** under a viewer's render: every variant of the component on stage,
   * measured against the same baseline.
   *
   * It does not score: numbers are the ones the delivery branch published, and a variant with none
   * links to the focused comparison. It does not pick its own baseline: each row carries both the
   * design reference and the paired render, tagged `data-cp-strip-source`; `serve.css` hides the
   * one not on show and `viewer.ts` (`syncSpecStrip`) switches it. The published match is bound to
   * the design reference, so it shows `not scored` opposite anything else.
   *
   * Returns empty for a single-variant component with no reference.
   */
  private fun comparisonStripHtml(
    variants: List<ComponentVariant>,
    currentPreviewId: String,
    componentId: String,
    componentName: String,
    baselineLabel: String,
    catalogName: String,
    basePath: String,
    q: String,
    assetQ: String,
    /** The paired catalog's name, when any variant resolves a parallel render; null otherwise. */
    parallelLabel: String? = null,
    /** Which baseline the strip opens on: the lane's own default source (`kit` / `parallel`). */
    defaultSource: String = "kit",
  ): String {
    val scored = variants.filter { it.referenceId != null }
    val paired = variants.filter { it.parallelRenderUrl != null }
    // The second baseline exists as a column only when the pairing resolves somewhere and the page
    // can name it; otherwise the markup is byte-for-byte the single-baseline strip.
    val hasParallel = parallelLabel != null && paired.isNotEmpty()
    // One variant and nothing to compare it against is not a strip, it is a heading over a single
    // row that restates the picture directly above it.
    if (variants.size < 2 && scored.isEmpty() && !hasParallel) return ""
    // No design reference and no paired catalog: no baseline at all, so drop the baseline half
    // rather than repeat empty frames and `not scored` per variant. The strip stays as navigation
    // between variants.
    val hasBaseline = scored.isNotEmpty() || hasParallel
    fun seg(value: String) = WebEscaping.urlEncodeSegment(value)
    // `data-cp-strip-source` on a cell says which baseline it belongs to. Absent when there is
    // only one, so a catalog without a pairing keeps the strip it had.
    fun sourced(id: String) = if (hasParallel) " data-cp-strip-source=\"$id\"" else ""
    val rows =
      variants.joinToString("\n") { variant ->
        val current = variant.previewId == currentPreviewId
        // The CURRENT row is not a link. It is the frame already on the stage, and a link that
        // reloads the page you are on reads as a control that does nothing.
        val href = if (current) null else "$basePath/p/${seg(variant.previewId)}$q"
        val baselineCell =
          if (!hasBaseline) ""
          else
            variant.referenceId?.let {
              "<span class=\"cp-strip-shot\"${sourced("kit")}><img loading=\"lazy\" alt=\"\" " +
                "src=\"$basePath/reference/${seg(it)}.png$assetQ\"></span>"
            }
              // An empty frame rather than nothing, so the columns line up and the gap reads as a
              // missing mapping.
              ?: "<span class=\"cp-strip-shot cp-strip-shot--empty\"${sourced("kit")} aria-label=\"No design reference\"></span>"
        // The paired catalog's render of the same variant, on the same terms: an empty frame where
        // the sibling draws no such cell, because the pairing refuses to substitute its default.
        val parallelCell =
          if (!hasParallel) ""
          else
            variant.parallelRenderUrl?.let {
              "<span class=\"cp-strip-shot\"${sourced("parallel")}><img loading=\"lazy\" alt=\"\" " +
                "src=\"${WebEscaping.htmlEscape(it)}\"></span>"
            }
              ?: "<span class=\"cp-strip-shot cp-strip-shot--empty\"${sourced("parallel")} aria-label=\"No paired render\"></span>"
        val score =
          if (!hasBaseline) ""
          else
            variant.matchPercent?.let {
              "<span class=\"cp-strip-score\"${sourced("kit")} data-spec-match=\"${specMatchBand(it)}\">" +
                "${WebEscaping.formatPercent(it)}</span>"
            }
              ?: "<span class=\"cp-strip-score cp-strip-score--none\"${sourced("kit")}>not scored</span>"
        // Nothing measures the parallel pair per variant, and the design number must not stand in
        // for it: opposite the sibling's render the column says so.
        val parallelScore =
          if (!hasParallel) ""
          else
            "<span class=\"cp-strip-score cp-strip-score--none\"${sourced("parallel")}>not scored</span>"
        // The way to the instruments, per row: the focused Reference / Diff / Actual page for this
        // exact pair, which is where a delta map, the annotations and the parity findings live.
        val detail =
          variant.referenceId?.let {
            val detailHref =
              "$basePath/compare/${seg(variant.previewId)}?reference=${seg(it)}" +
                (if (q.isEmpty()) "" else "&" + q.removePrefix("?"))
            // Escaped like every other URL: a raw `&` in an attribute starts a character reference.
            "<a class=\"cp-strip-detail\"${sourced("kit")} href=\"${WebEscaping.htmlEscape(detailHref)}\" " +
              "title=\"Reference, diff and render for this variant\">diff &rarr;</a>"
          } ?: ""
        val name =
          if (variant.variant.isBlank()) WebEscaping.htmlEscape(componentName)
          else WebEscaping.htmlEscape(variant.variant)
        val label =
          if (href == null) "<span class=\"cp-strip-name\">$name<em>on the stage</em></span>"
          else "<a class=\"cp-strip-name\" href=\"${WebEscaping.htmlEscape(href)}\">$name</a>"
        "<li class=\"cp-strip-row\"${if (current) " aria-current=\"true\"" else ""}>" +
          baselineCell +
          parallelCell +
          "<span class=\"cp-strip-shot\"><img loading=\"lazy\" alt=\"\" " +
          "src=\"$basePath/render/${seg(variant.previewId)}.png$assetQ\"></span>" +
          label +
          score +
          parallelScore +
          detail +
          "</li>"
      }
    // The way out to the wall, on the same component and the same baseline: `format=reference`
    // rows for the design comparison, `format=parallel` rows for the paired one.
    fun wallHref(format: String) =
      "$basePath/compare?" +
        listOf("format=$format", "component=${seg(componentId)}", q.removePrefix("?"))
          .filter { it.isNotEmpty() }
          .joinToString("&")
    val more =
      if (!hasBaseline) ""
      else
        "<a${sourced("kit")} href=\"${WebEscaping.htmlEscape(wallHref("reference"))}\">every component &rarr;</a>" +
          (if (!hasParallel) ""
          else
            "<a${sourced("parallel")} href=\"${WebEscaping.htmlEscape(wallHref("parallel"))}\">every component &rarr;</a>")
    val counted =
      "${variants.size} ${if (variants.size == 1) "variant" else "variants"} of " +
        WebEscaping.htmlEscape(componentName)
    // What the rows stand opposite, once per baseline. `serve.css` shows one by the section's
    // attribute; it opens on the lane's default source so the strip and stage agree before any
    // script runs.
    fun baselineName(id: String, name: String) =
      "<span${sourced(id)}>${WebEscaping.htmlEscape(name)}</span>"
    val against =
      baselineName("kit", baselineLabel) +
        (if (!hasParallel) "" else baselineName("parallel", parallelLabel!!))
    val sectionSource =
      if (!hasParallel) ""
      else " data-cp-strip-source=\"${if (defaultSource == "parallel") "parallel" else "kit"}\""
    val catalogHead = "<span>${WebEscaping.htmlEscape(catalogName)}</span>"
    // `serve.css` narrows the grid to the render and its name for a strip with no baseline.
    val sectionClass = if (hasBaseline) "cp-strip" else "cp-strip cp-strip--no-baseline"
    val sub = if (hasBaseline) "$counted, against $against" else counted
    val headCells =
      if (hasBaseline) "$against$catalogHead<span></span><span>Match</span><span></span>"
      else "$catalogHead<span></span>"
    val moreHtml = if (more.isEmpty()) "" else "\n        <p class=\"cp-strip-more\">$more</p>"
    return """
      <section class="$sectionClass" id="cp-compare-strip" aria-labelledby="cp-strip-head"$sectionSource>
        <h2 class="cp-strip-head" id="cp-strip-head">Compare<span class="cp-strip-sub">$sub</span></h2>
        <ol class="cp-strip-rows">
          <li class="cp-strip-headrow" aria-hidden="true">$headCells</li>
          $rows
        </ol>$moreHtml
      </section>
      """
      .trimIndent()
  }

  private fun scriptTag(name: String): String = "<script src=\"${assetHref(name)}\"></script>"

  /**
   * The scorer script tag, carrying the URL of its worker half.
   *
   * `format-compare.js` is the comparison API; `compare-scorer.js` is the metric, built for a
   * worker that `scorer/offload.ts` finds via this tag's attribute. Every scoring page uses this
   * instead of `scriptTag("format-compare.js")` so the two cannot drift; consumers that inject the
   * file bare score on the main thread.
   */
  private fun compareScorerTag(): String =
    "<script src=\"${assetHref("format-compare.js")}\" " +
      "data-cp-scorer-worker=\"${WebEscaping.htmlEscape(assetHref("compare-scorer.js"))}\"></script>"

  /** One Vue runtime followed synchronously by the controls for this server surface. */
  private fun componentScriptTags(surface: String): String =
    scriptTag("vue-runtime.js") + "\n" + scriptTag("$surface-components.js")

  private fun viewCountHtml(views: Long): String =
    if (views <= 0) "" else "<div class=\"cp-engage\">${formatViews(views)}</div>"

  /** The viewer's view tally, inline on the title row. */
  private fun viewerViewCountHtml(views: Long): String =
    if (views <= 0) "" else "<span class=\"cp-viewer-engage\">${formatViews(views)}</span>"

  /**
   * The visit tally, wrapped in the marker that keeps it out of a page's `ETag`.
   *
   * It changes on essentially every request, so hashing it would stop any repeat navigation
   * revalidating to a `304`. [ServeHttpServer] elides this element when hashing, so a cached page
   * can show a count up to its `max-age` old.
   */
  private fun formatViews(views: Long): String =
    "<span $VOLATILE_ATTR>${formatCount(views)} ${if (views == 1L) "view" else "views"}</span>"

  private fun formatCount(n: Long): String =
    if (n < 1000) n.toString()
    else String.format(java.util.Locale.ROOT, "%.1f", n / 1000.0).removeSuffix(".0") + "k"

  /**
   * The starter snippet the [playgroundPage] editor opens with — a minimal Material 3 `@Preview`.
   */
  private val PLAYGROUND_SAMPLE =
    """
    import androidx.compose.material3.Button
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.ui.tooling.preview.Preview

    @Preview
    @Composable
    fun Greeting() {
        Button(onClick = {}) {
            Text("Hello, Compose!")
        }
    }
    """
      .trimIndent()

  /**
   * Query string carrying the token and, only for a non-default tenant ([sessionId] non-null), the
   * `session` id. In [isPublic] mode every route is open, so the token is omitted. May return
   * empty; wrap with [querySuffix].
   */
  private fun queryString(token: String, sessionId: String?, isPublic: Boolean): String {
    val parts = buildList {
      if (!isPublic && token.isNotEmpty()) add("token=" + WebEscaping.urlEncodeSegment(token))
      if (sessionId != null) add("session=" + WebEscaping.urlEncodeSegment(sessionId))
    }
    return parts.joinToString("&")
  }

  /**
   * The query string for a same-session link given the page's [basePath]. Under a `/<system>` path
   * the session is in the path, so links are token-only; root-mounted pages fall back to
   * [queryString]. [isPublic] drops the token (may return empty — wrap with [querySuffix]).
   *
   * A top-level site ([ServeSites]) carries its session in the origin, so its pages pass a null
   * session id here.
   */
  private fun linkQuery(
    token: String,
    sessionId: String?,
    basePath: String,
    isPublic: Boolean,
  ): String =
    if (basePath.isEmpty()) queryString(token, sessionId, isPublic)
    else if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)

  /** `?` + [query] when non-empty, else empty. */
  private fun querySuffix(query: String): String = if (query.isEmpty()) "" else "?$query"

  /**
   * The viewer's compact producer warning. Trusted catalogs carry no badge; an unverified one is
   * called out consistently with the home index.
   */
  private fun compactTrustBadge(trust: String?): String {
    if (trust != "unverified") return ""
    return " <span class=\"cp-badge cp-badge--unverified\" " +
      "title=\"producer trust: unverified\">⚠ untrusted</span>"
  }

  /**
   * The front door only flags a negative producer verdict (`untrusted`, orange); trusted catalogs
   * carry no badge. The full verdict is on `/status` and the catalog's pages.
   */
  private fun homeTrustBadge(trust: String?): String {
    if (trust != "unverified") return ""
    return " <span class=\"cp-badge cp-badge--unverified\" " +
      "title=\"producer trust: unverified\">⚠ untrusted</span>"
  }

  /**
   * The session-level **"why snapshot-only" banner** listing each [ServeDegradation.detail]. Empty
   * when [degradations] is empty. Per-control reasons stay in the viewer's `cp-note`.
   */
  private fun degradeBanner(degradations: List<ServeDegradation>): String {
    if (degradations.isEmpty()) return ""
    val items =
      degradations.joinToString("\n        ") {
        "<span class=\"cp-degrade-item\">${WebEscaping.htmlEscape(it.detail)}</span>"
      }
    return """
      <section class="cp-degrade" role="note" aria-label="Why this preview is snapshot-only">
        <span class="cp-degrade-icon" aria-hidden="true">ⓘ</span>
        $items
      </section>
      """
      .trimIndent() + "\n"
  }

  /**
   * What a page knows about the **published revisions** of its catalog: the pin (null ⇒ current),
   * recent history, and the repo the commits live in. Data rather than HTML because each page
   * builds its own destination URLs ([revisionsHtml]).
   */
  data class CatalogRevisions(
    val pinned: String? = null,
    val revisions: List<ServeCatalogRevision.Revision> = emptyList(),
    val repo: String? = null,
    /**
     * The delivery-branch commit this page is assembled from — the generation every frame URL is
     * scoped to ([ServeCacheGeneration]). Null without a delivery branch. Kept beside the pin
     * because both answer "which publish is this page about", and apart they could disagree on one
     * `<img>`.
     */
    val generation: String? = null,
  ) {
    /** Nothing to say: no history to offer and no pin to announce. */
    val isEmpty: Boolean
      get() = pinned == null && revisions.isEmpty()

    companion object {
      val NONE = CatalogRevisions()
    }
  }

  /**
   * The revision control: the pin banner (when showing an older publish) above the list of
   * publishes it can move between. [hrefFor] builds this page at a given pin (null ⇒ live).
   *
   * A revision is labelled by publish date and the source commit it was rendered from when
   * recorded, falling back to the delivery sha — the source sha is what someone going back a
   * version is looking for.
   */
  private fun revisionBannerHtml(
    revisions: CatalogRevisions,
    hrefFor: (String?) -> String,
  ): String {
    val pinned = revisions.pinned ?: return ""
    val entry = revisions.revisions.firstOrNull { it.commit == pinned }
    val shaLink =
      ServeCatalogRevision.treeUrl(revisions.repo, pinned)?.let { url ->
        "<a href=\"${WebEscaping.htmlEscape(url)}\" target=\"_blank\" rel=\"noopener noreferrer\">" +
          "<code>${WebEscaping.htmlEscape(ServeCatalogRevision.short(pinned))}</code></a>"
      } ?: "<code>${WebEscaping.htmlEscape(ServeCatalogRevision.short(pinned))}</code>"
    val published =
      entry
        ?.date
        ?.takeIf { it.isNotBlank() }
        ?.let { ", published ${WebEscaping.htmlEscape(prettyDate(it))}" }
        .orEmpty()
    return """
      <section class="cp-pinned" role="note" aria-label="Pinned revision">
        <span class="cp-pinned-icon" aria-hidden="true">⚓</span>
        <span>Pinned to catalog revision $shaLink$published — these pixels cannot change.</span>
        <a class="cp-pinned-current" href="${WebEscaping.htmlEscape(hrefFor(null))}">view current</a>
      </section>
      """
      .trimIndent()
  }

  internal fun revisionsHtml(
    revisions: CatalogRevisions,
    includeBanner: Boolean = true,
    /**
     * Attributes for `<cp-revision-runs>`, or blank. Passed in because they belong to a preview,
     * and this control also draws on pages with no single preview.
     */
    runsAttrs: String = "",
    /**
     * A `/api/render-runs` payload inlined for the preview-harness fixture. Blank on every served
     * page; the element fetches there.
     */
    runsInlineJson: String = "",
    hrefFor: (String?) -> String,
  ): String {
    if (revisions.isEmpty) return ""
    val pinned = revisions.pinned
    val current = revisions.revisions.firstOrNull()?.commit
    val banner = if (includeBanner) revisionBannerHtml(revisions, hrefFor) else ""
    if (revisions.revisions.isEmpty()) return "$banner\n"
    val rows =
      revisions.revisions.joinToString("\n            ") { revision ->
        val isCurrent = revision.commit == current
        // A pin is what the page URL says; with no pin the page is showing the branch tip, so that
        // is the row marked. One row is marked either way, and never two.
        val selected = if (pinned == null) isCurrent else revision.commit == pinned
        val href = hrefFor(revision.commit.takeUnless { isCurrent })
        val date =
          revision.date.takeIf { it.isNotBlank() }?.let { prettyDate(it) } ?: revision.short
        val label = revision.sourceSha ?: revision.short
        val mark = if (selected) " aria-current=\"true\"" else ""
        val currentTag = if (isCurrent) "<span class=\"cp-revision-tag\">current</span>" else ""
        // `nofollow`: these are near-duplicates of each preview; only the live one is worth
        // indexing.
        // The delivery sha identifies the row to `<cp-revision-runs>`; it is not derivable from the
        // href because the current row carries no `?at=`.
        val stamp = " data-revision=\"${WebEscaping.htmlEscape(revision.commit)}\""
        "<a class=\"cp-revision\" rel=\"nofollow\" href=\"${WebEscaping.htmlEscape(href)}\"$mark" +
          "$stamp>" +
          "<span class=\"cp-revision-date\">${WebEscaping.htmlEscape(date)}</span>" +
          "<code class=\"cp-revision-sha\">${WebEscaping.htmlEscape(label)}</code>$currentTag</a>"
      }
    // The trigger names the revision the page is on (pin or tip). No `aria-label`: it would
    // override the visible date/sha/state. A plain `<details>` disclosure without `role="menu"`,
    // since nothing implements the menu keyboard model.
    val shown = revisions.revisions.firstOrNull { it.commit == (pinned ?: current) }
    val shownDate =
      shown?.date?.takeIf { it.isNotBlank() }?.let { prettyDate(it) }
        ?: pinned?.let { ServeCatalogRevision.short(it) }
        ?: shown?.short
        ?: ""
    val shownSha =
      shown?.sourceSha ?: shown?.short ?: pinned?.let { ServeCatalogRevision.short(it) }
    val shownTag =
      if (pinned == null) "<span class=\"cp-revision-tag\">current</span>"
      else "<span class=\"cp-revision-tag cp-revision-tag--pinned\">pinned</span>"
    val triggerContent =
      if (pinned == null) shownTag
      else
        """<span class="cp-revisions-key">Revision</span>
          <span class="cp-revision-date">${WebEscaping.htmlEscape(shownDate)}</span>
          ${shownSha?.let { "<code class=\"cp-revision-sha\">${WebEscaping.htmlEscape(it)}</code>" }.orEmpty()}
          $shownTag"""
    return banner +
      """
      <details class="cp-revisions">
        <summary class="cp-revisions-btn">
          $triggerContent
          <span class="cp-revisions-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-revisions-menu">
          ${if (runsAttrs.isBlank()) "" else "<cp-revision-runs$runsAttrs></cp-revision-runs>"}
          ${
        // `</script>` inside a JSON payload would end the element early, so the only sequence that
        // can break out is neutralised — the same treatment `cp-history-data` gets, and for the
        // same reason.
        if (runsInlineJson.isBlank()) ""
        else
          "<script type=\"application/json\" id=\"cp-revision-runs-data\">" +
            runsInlineJson.replace("</", "<\\/") +
            "</script>"
      }
          <nav class="cp-revision-list" aria-label="Published revisions">
            $rows
          </nav>
          <p class="cp-revision-note">Every publish of this design system is a commit on its
          delivery branch. Opening one pins this page — and the pixels on it — to that publish for
          good.</p>
        </div>
      </details>
      """
        .trimIndent() +
      "\n"
  }

  /**
   * Add `at=<sha>` to a link, or return it unchanged when the page carries no pin. A pinned page
   * must pin every link it emits.
   *
   * Callers pass a bare query suffix or a whole URL, so the separator is chosen from whether the
   * string already has a `?`: token-free public links have none, and `&at=` there would land in the
   * path and 404.
   */
  private fun withPin(link: String, pinned: String?): String {
    val pin = pinned?.takeIf { it.isNotBlank() } ?: return link
    val param = "${ServeCatalogRevision.PARAM}=${WebEscaping.urlEncodeSegment(pin)}"
    return when {
      !link.contains('?') -> "$link?$param"
      link.endsWith('?') || link.endsWith('&') -> "$link$param"
      else -> "$link&$param"
    }
  }

  /**
   * The query every **published frame URL** on a page carries: the page's query plus the publish it
   * is about. A pinned page's frames take the pin; an unpinned page's take its generation, so a
   * browser cannot pair this page's verdict with the next publish's pixels. Never applied to a page
   * link; see [ServeCacheGeneration.scope].
   */
  private fun assetQuery(query: String, revisions: CatalogRevisions): String =
    if (revisions.pinned != null) withPin(query, revisions.pinned)
    else ServeCacheGeneration.scope(query, revisions.generation)

  /** Canonical source repo, used for the "source" / branch / workflow links. */
  private const val SOURCE_REPO = "yschimke/compose-ai-tools"

  /**
   * Presence ping interval for an open catalog page ([presenceScript]). Well under the session
   * reaper's ten-minute idle window, so one dropped ping doesn't let the session lapse.
   */
  internal const val PRESENCE_INTERVAL_SECONDS = 240

  /**
   * Marks an element whose text changes on essentially every request, so it must not decide
   * revalidation. `ServeHttpServer.pageEntityTag` elides these before hashing. Content marked with
   * it may be up to one `max-age` stale, so use it only for tallies or relative times.
   */
  internal const val VOLATILE_ATTR = "data-cp-volatile"

  /**
   * Cookie holding the Catalog / Dev switch choice, read by the server on every request
   * (`ServeHttpServer.componentBrowserMode`). A cookie rather than `localStorage` because the
   * choice decides what the server renders, and keeps it out of URLs.
   */
  internal const val INTERFACE_MODE_COOKIE = "cp_chrome"

  /** How long the remembered Catalog / Dev choice sticks around. A preference, so: a year. */
  private const val INTERFACE_MODE_COOKIE_MAX_AGE = 31536000

  /**
   * Attributes for [INTERFACE_MODE_COOKIE]: host-wide and `SameSite=Lax`. The script appends
   * `Secure` on https so the markup also works on an http dev server. Deliberately script-readable.
   */
  private const val INTERFACE_MODE_COOKIE_ATTRS =
    "; path=/; max-age=$INTERFACE_MODE_COOKIE_MAX_AGE; samesite=lax"

  /**
   * Theme chips shown inline in the viewer bar before folding. Lower than [AXIS_CHIPS_INLINE]
   * because the bar is a single non-wrapping row.
   */
  private const val THEME_CHIPS_INLINE = 4

  /**
   * The design-spec lane view the page is served with pressed, and so the one `?specView=` omits.
   * Must match `serve-web/src/spec/views.ts`, or the page opens pressing a button the script
   * immediately unpresses.
   */
  internal const val SPEC_DEFAULT_VIEW = "triptych"

  // android.content.res.Configuration values, kept local so the CLI has no Android dependency.
  private const val UI_MODE_NIGHT_MASK = 0x30
  private const val UI_MODE_NIGHT_NO = 0x10
  private const val UI_MODE_NIGHT_YES = 0x20

  /** Inline GitHub mark (Octicons, MIT). Rendered beside source and authentication links. */
  /** Feed glyph for the footer's changelog entry — the shape a reader recognises as a feed. */
  private const val RSS_ICON =
    "<svg class=\"cp-gh\" viewBox=\"0 0 16 16\" aria-hidden=\"true\" fill=\"currentColor\">" +
      "<circle cx=\"3\" cy=\"13\" r=\"2\"/>" +
      "<path d=\"M1 8.5a6.5 6.5 0 016.5 6.5h-2A4.5 4.5 0 001 10.5v-2z\"/>" +
      "<path d=\"M1 3a12 12 0 0112 12h-2A10 10 0 001 5V3z\"/></svg>"

  private const val GITHUB_ICON =
    "<svg class=\"cp-gh\" viewBox=\"0 0 16 16\" aria-hidden=\"true\" fill=\"currentColor\">" +
      "<path d=\"M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 " +
      "0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53." +
      "63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 " +
      "0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 " +
      "1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 " +
      "3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 " +
      "8.01 0 0016 8c0-4.42-3.58-8-8-8z\"/></svg>"

  /**
   * Inline Figma mark in `currentColor`, matching [GITHUB_ICON]. Its own class keeps the 2:3
   * aspect.
   */
  private const val FIGMA_ICON =
    "<svg class=\"cp-gh cp-figma-mark\" viewBox=\"0 0 38 57\" aria-hidden=\"true\" " +
      "fill=\"currentColor\">" +
      "<path d=\"M19 28.5a9.5 9.5 0 1 1 19 0 9.5 9.5 0 0 1-19 0z\"/>" +
      "<path d=\"M0 47.5A9.5 9.5 0 0 1 9.5 38H19v9.5a9.5 9.5 0 0 1-19 0z\"/>" +
      "<path d=\"M19 0v19h9.5a9.5 9.5 0 1 0 0-19H19z\"/>" +
      "<path d=\"M0 9.5A9.5 9.5 0 0 0 9.5 19H19V0H9.5A9.5 9.5 0 0 0 0 9.5z\"/>" +
      "<path d=\"M0 28.5A9.5 9.5 0 0 0 9.5 38H19V19H9.5A9.5 9.5 0 0 0 0 28.5z\"/></svg>"

  /**
   * The report launcher's mark: a speech bubble with an exclamation. Not the GitHub mark, because
   * this button chooses between two destinations rather than filing to one.
   */
  private const val REPORT_ICON =
    "<svg class=\"cp-fab-mark\" viewBox=\"0 0 24 24\" aria-hidden=\"true\" fill=\"none\" " +
      "stroke=\"currentColor\" stroke-width=\"1.9\" stroke-linecap=\"round\" " +
      "stroke-linejoin=\"round\">" +
      "<path d=\"M20.5 12.4a7.7 7.7 0 0 1-8.3 7.6c-.7 0-1.4-.1-2-.3L4 21l1.4-3.9a7.3 7.3 0 0 1-1.9-4.9" +
      " 7.7 7.7 0 0 1 8.5-7.6 7.8 7.8 0 0 1 8.5 7.8z\"/>" +
      "<path d=\"M12 8.4v4\"/><path d=\"M12 15.4h.01\"/></svg>"

  /** GitHub session action shown in the home-page header when OAuth is configured. */
  private fun githubAuthControl(status: GitHubAuthStatus?): String {
    status ?: return ""
    // What this sign-in buys. The allowlist narrows who may sign in at all; the repo is named only
    // on the playground, whose gate it is.
    val repo =
      status.accessRepository?.let { " with access to ${WebEscaping.htmlEscape(it)}" } ?: ""
    val tooltip =
      when {
        status.lane == GatedLane.PLAYGROUND && status.restrictedToAllowedUsers ->
          "Playground access is limited to configured GitHub users$repo"
        status.lane == GatedLane.PLAYGROUND -> "The playground requires a GitHub sign-in$repo"
        status.restrictedToAllowedUsers ->
          "Live preview access is limited to configured GitHub users"
        else -> "Live previews require a GitHub sign-in"
      }
    val tooltipAttr = " title=\"$tooltip\""
    val login = status.login?.takeIf { it.isNotBlank() }
    return if (login == null) {
      "<a class=\"cp-gh-auth\" href=\"${WebEscaping.htmlEscape(status.loginHref)}\"" +
        "$tooltipAttr>$GITHUB_ICON Sign in with GitHub</a>"
    } else {
      // Identity only; sign-out and switch-account live in the Settings menu
      // ([githubSessionSettings]).
      "<span class=\"cp-gh-auth cp-gh-auth--signed\"$tooltipAttr>$GITHUB_ICON " +
        "Signed in as ${WebEscaping.htmlEscape(login)}</span>"
    }
  }

  /**
   * The **Session** group in the Settings menu: who is signed in, and the two ways out.
   *
   * **Sign out** ends the session. **Switch account** re-runs OAuth, the only thing that recomputes
   * the access bits cached at sign-in. Empty for a signed-out visitor or when
   * [GitHubAuthStatus.logoutHref] is absent.
   */
  private fun githubSessionSettings(status: GitHubAuthStatus?): String {
    val login = status?.login?.takeIf { it.isNotBlank() } ?: return ""
    val logoutHref = status.logoutHref?.takeIf { it.isNotBlank() } ?: return ""
    val notifications = if (status.notifications) pushNotificationSettings() + "\n" else ""
    return notifications +
      """
      <fieldset class="cp-settings-group cp-settings-session">
        <legend class="cp-settings-legend">Session</legend>
        <p class="cp-settings-hint">Signed in to GitHub as ${WebEscaping.htmlEscape(login)}.</p>
        <div class="cp-settings-session-actions">
          <form method="post" action="${WebEscaping.htmlEscape(logoutHref)}" data-cp-push-signout>
            <button type="submit" class="cp-settings-tour">Sign out</button>
          </form>
          <a class="cp-settings-session-switch"
            href="${WebEscaping.htmlEscape(status.loginHref)}">Switch account</a>
        </div>
        <p class="cp-settings-hint">Switching account signs in again through GitHub, which is what
          refreshes what this session is allowed to do. Signing out forgets it in this browser.</p>
      </fieldset>
      """
        .trimIndent()
  }

  /**
   * The **Notifications** group: one Web Push toggle per [PushKind] plus a button that asks the
   * browser.
   *
   * Inert without `push-settings.js`, emitted here so only signed-in visits on push hosts load it.
   * The script decides what the button offers (PushManager support, iOS Home Screen, HTTPS, prior
   * permission); permission is requested only from that button's click.
   */
  internal fun pushNotificationSettings(): String {
    val kinds =
      PushKind.entries.joinToString("\n") { kind ->
        """
        <label class="cp-settings-option">
          <input type="checkbox" data-cp-push-kind="${kind.wire}" checked disabled>
          <span>${WebEscaping.htmlEscape(kind.label)}</span>
        </label>
        """
          .trimIndent()
      }
    return """
      <fieldset class="cp-settings-group cp-settings-notifications" data-cp-push-settings
        data-cp-push-key="$PUSH_KEY_PATH" data-cp-push-subscribe="$PUSH_SUBSCRIBE_PATH"
        data-cp-push-preferences="$PUSH_PREFERENCES_PATH" data-cp-push-worker="$PUSH_SERVICE_WORKER_PATH">
        <legend class="cp-settings-legend">Notifications</legend>
        <p class="cp-settings-hint" data-cp-push-status role="status">Get a notification when
          somebody replies to you, mentions you, or reviews your design — even with this tab
          closed.</p>
${kinds.prependIndent("        ")}
        <button type="button" class="cp-settings-tour" data-cp-push-toggle hidden>Turn on
          notifications</button>
        <script src="${assetHref("push-settings.js")}" defer></script>
      </fieldset>
      """
      .trimIndent()
  }

  /**
   * The minimal site footer rendered by [document] on every browser-facing page: GitHub,
   * `/version`, "report a bug", and the build. A null/blank [version] drops only the build span.
   *
   * "GitHub" rather than "source", since [sourceLinkHtml] is the per-preview source link. [note] is
   * the page's own footer block, rendered above the links (e.g. the landing's [provenanceSection]).
   * [bugReport] false drops "report a bug" (on the report page itself). [changelogHref] adds a
   * leading **Changelog** link to the catalog's `/feed.xml`; empty where no feed exists.
   */
  private fun siteFooter(
    version: String?,
    note: String = "",
    bugReport: Boolean = true,
    changelogHref: String = "",
  ): String {
    val ver =
      version
        ?.takeIf { it.isNotBlank() }
        ?.let {
          " · <span class=\"cp-about-ver\" title=\"running preview-server build\">" +
            "server v${WebEscaping.htmlEscape(it)}</span>"
        } ?: ""
    val noteBlock =
      note.takeIf { it.isNotBlank() }?.let { "${it.trimEnd().prependIndent("        ")}\n" } ?: ""
    val report = if (bugReport) "\n${reportBugFormHtml().prependIndent("          ")} ·" else ""
    val changelog =
      changelogHref
        .takeIf { it.isNotBlank() }
        ?.let {
          "<a href=\"${WebEscaping.htmlEscape(it)}\" class=\"cp-changelog-link\"" +
            " title=\"What changed in this design system, newest first (RSS)\">" +
            "$RSS_ICON Changelog</a> ·\n          "
        } ?: ""
    return """
      <footer class="cp-site-footer">
$noteBlock        <div class="cp-site-footer-links">
          $changelog<a href="https://github.com/$SOURCE_REPO">$GITHUB_ICON GitHub</a> ·$report
          <a href="/version">/version</a>$ver
        </div>
      </footer>
      """
      .trimIndent()
  }

  /**
   * The footer's "report a bug" entry point to [ServeBugReport.PATH], which collects server
   * diagnostics and prefills an issue on the server's repo. Lives in the footer because every page
   * has one, unlike [previewLinksHtml]'s per-preview report.
   *
   * A GET form rather than a link (see [ServeIssueReport.action]): the page URL and token are
   * page-derived, so the script only fills input values instead of writing an `href`. Inputs are
   * filled by `serve-chrome.js`; with JS off it still submits without a page section.
   */
  private fun reportBugFormHtml(): String =
    reportBugForm(
      "<button type=\"submit\" class=\"cp-report-bug-link\"" +
        " title=\"Report a bug in the preview server itself — not in a preview\">" +
        "$GITHUB_ICON report a server bug</button>"
    )

  /**
   * The `GET /report-bug` form wrapped around the caller's [submit] control. Emitted once per entry
   * point (footer link and launcher); `fillBugReportLink` fills every copy via `querySelectorAll`.
   */
  private fun reportBugForm(submit: String): String =
    "<form class=\"cp-report-bug\" method=\"get\" action=\"${ServeBugReport.PATH}\">" +
      "<input type=\"hidden\" name=\"${ServeBugReport.FROM_PARAM}\" value=\"\">" +
      "<input type=\"hidden\" name=\"token\" value=\"\">" +
      // The scheme this page is painted in; the report page cannot recover it, since a catalog may
      // pin dark chrome against a light OS.
      "<input type=\"hidden\" name=\"scheme\" value=\"\">" +
      submit +
      "</form>"

  /**
   * The **floating report launcher**: a fixed bottom-right button opening a panel that names both
   * trackers and offers screen capture.
   *
   * It floats so reporting is one click from where the problem was noticed; the footer entry stays
   * for no-JS pages. It offers two destinations because server bugs and preview bugs go to
   * different repos, and the panel names each.
   *
   * The catalog half is server-rendered `hidden` and unhidden by `reportLauncher.ts` on pages
   * carrying `#cp-report` ([reportIssueHtml]). [captureSrc] is the hashed `report-capture.js` URL,
   * fetched only when the panel first opens.
   */
  private fun reportLauncherHtml(captureSrc: String): String =
    """
    <div class="cp-fab" data-cp-capture-src="${WebEscaping.htmlEscape(captureSrc)}">
      <details class="cp-fab-menu">
        <summary class="cp-fab-btn" title="Report a problem" aria-label="Report a problem"
          >$REPORT_ICON</summary>
        <div class="cp-fab-panel">
          <p class="cp-fab-head">Report a problem</p>
          <p class="cp-fab-sub">Two trackers &mdash; pick whichever owns the thing that is
            wrong.</p>
          <a class="cp-fab-choice cp-fab-catalog" href="#cp-report" hidden>
            <span class="cp-fab-what">Something is wrong with this <strong>preview</strong></span>
            <span class="cp-fab-who">wrong colours, wrong spec, a state that is missing</span>
          </a>
${reportBugForm(
      "<button type=\"submit\" class=\"cp-fab-choice\">" +
        "<span class=\"cp-fab-what\">Something is wrong with the <strong>preview " +
        "server</strong></span>" +
        "<span class=\"cp-fab-who\">the page, a control, a render that failed &mdash; goes to " +
        "<code>${WebEscaping.htmlEscape(ServeBugReport.REPO)}</code></span></button>"
    )
      .prependIndent("          ")}
${captureControlsHtml().prependIndent("          ")}
        </div>
      </details>
    </div>
    """
      .trimIndent()

  /**
   * The capture controls, shared by the launcher panel and [bugReportPage].
   *
   * Server-rendered `hidden` and unhidden by `report-capture.js` only once it knows the browser can
   * grab a frame (`getDisplayMedia`, `ClipboardItem`). Modes: *whole view*, *region* (a drag box),
   * and *element* (pick a single node).
   */
  private fun captureControlsHtml(): String =
    """
    <div class="cp-shot" hidden>
      <p class="cp-shot-head">Capture what you can see</p>
      <p class="cp-fab-who">The report embeds the plain render. Anything the browser composes
        &mdash; the spec triptych, a wipe, an overlay, an error &mdash; only reaches the issue as a
        picture you take.</p>
      <div class="cp-shot-modes">
        <button type="button" class="cp-shot-mode" data-cp-capture="view"
          title="The whole browser viewport, as it is now">Whole view</button>
        <button type="button" class="cp-shot-mode" data-cp-capture="region"
          title="Drag a box around the part that is wrong">Region</button>
        <button type="button" class="cp-shot-mode" data-cp-capture="element"
          title="Point at one element — a render, a table, a single cell">Element</button>
      </div>
      <p class="cp-shot-note" role="status"></p>
      <ul class="cp-shot-list"></ul>
    </div>
    """
      .trimIndent()

  /**
   * Shared, compact navigation for every browser-facing page.
   *
   * A fixed three-slot layout — brand left, live status centred, nav right — with all slots always
   * emitted so nothing shifts when the conditional ones (daemon badge, GitHub session) are absent.
   * The status slot starts empty and `hidden`; `presenceScript` fills it when the daemon poll
   * answers.
   *
   * [breadcrumb] rides in the brand slot so navigation doesn't cost a body row. The nav carries
   * only Status, the GitHub session control ([action]) and Settings; the repo link belongs in
   * [siteFooter].
   */
  private fun siteHeader(
    navSuffix: String,
    action: String = "",
    breadcrumb: String = "",
    /**
     * The catalog this page belongs to, named in the pinned bar so it stays visible while scrolling
     * and tells tabs apart. Empty on pages that belong to no catalog.
     */
    siteName: String = "",
    componentBrowser: Boolean = false,
    showInterfaceMode: Boolean = false,
    showPreviewThemeSetting: Boolean = false,
    /** [githubSessionSettings]' output, rendered inside the Settings menu. Empty on most pages. */
    sessionSettings: String = "",
    /**
     * A collapsed search control for the bar ([headerSearchControl]). Empty on every page but the
     * front door, whose grid it filters.
     */
    search: String = "",
    /**
     * Link to the UI builder's **Designs** index, emitted only on builder pages;
     * `/ui-builder/designs` 404s on hosts without a builder.
     */
    designsHref: String = "",
  ): String {
    val actionHtml = action.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    val designsHtml =
      designsHref
        .takeIf { it.isNotBlank() }
        ?.let {
          "\n            <a class=\"cp-site-designs-link\" href=\"${WebEscaping.htmlEscape(it)}\">Designs</a>"
        }
        .orEmpty()
    val crumb = breadcrumb.takeIf { it.isNotBlank() }?.let { "\n          $it" } ?: ""
    val name =
      siteName
        .takeIf { it.isNotBlank() }
        ?.let { "\n          <span class=\"cp-site-catalog\">${WebEscaping.htmlEscape(it)}</span>" }
        ?: ""
    val modeToggle =
      """
      <div class="cp-interface-mode" role="group" aria-label="Interface mode">
        <button type="button" data-cp-interface-mode="catalog" aria-pressed="${componentBrowser}">Catalog</button>
        <button type="button" data-cp-interface-mode="dev" aria-pressed="${!componentBrowser}">Dev</button>
      </div>
      """
        .trimIndent()
    val modeToggleHtml =
      if (showInterfaceMode) "\n" + modeToggle.prependIndent("          ") else ""
    val searchHtml =
      search.takeIf { it.isNotBlank() }?.let { "\n" + it.prependIndent("          ") } ?: ""
    if (componentBrowser) {
      return """
        <header class="cp-site-header">
          <div class="cp-site-lead">
            <a class="cp-site-brand" href="/$navSuffix" aria-label="compose-preview home">
              <span class="cp-site-mark" aria-hidden="true">◇</span>
              <span class="cp-site-wordmark">compose-preview</span>
            </a>$name$crumb
          </div>$searchHtml$modeToggleHtml
        </header>
        """
        .trimIndent()
    }
    return """
      <header class="cp-site-header">
        <div class="cp-site-lead">
          <a class="cp-site-brand" href="/$navSuffix" aria-label="compose-preview home">
            <span class="cp-site-mark" aria-hidden="true">◇</span>
            <span class="cp-site-wordmark">compose-preview</span>
          </a>$name$crumb
        </div>
        <nav class="cp-site-nav" aria-label="Primary navigation">$searchHtml$modeToggleHtml
          <details class="cp-site-menu" id="cp-site-menu">
            <summary class="cp-site-menu-btn" title="Menu" aria-label="Menu"
              aria-controls="cp-site-menu-panel"><span aria-hidden="true">⋮</span></summary>
          </details>
          <div class="cp-site-menu-panel" id="cp-site-menu-panel">
            <a class="cp-site-status-link" id="cp-status-link" href="/status$navSuffix">Status<span
              class="cp-daemon-status" id="cp-daemon-status" aria-hidden="true" hidden></span></a>$designsHtml$actionHtml
            ${settingsMenuHtml(showPreviewThemeSetting, sessionSettings).prependIndent("            ").trimStart()}
          </div>
        </nav>
      </header>
      """
      .trimIndent()
  }

  /**
   * The header's **Settings** menu: standing per-visitor preferences — **Page theme**
   * (`cli/serve-web/src/chrome/pageTheme.ts`) and opt-in **Power-user navigation**
   * (`keyboard-navigation.js`). A plain `<details>` so it works with no JavaScript; scripts only
   * reflect stored values. Last in the nav so it never displaces links.
   */
  /**
   * [session] is [githubSessionSettings]' output, empty when there is no GitHub session. Sorted
   * last because it is the only group whose controls leave the page.
   */
  private fun settingsMenuHtml(showPreviewThemeSetting: Boolean, session: String = ""): String =
    """
    <details class="cp-settings">
      <summary class="cp-settings-btn" title="Settings" aria-label="Settings">
        <span aria-hidden="true">⚙</span><span class="cp-settings-btn-label">Settings</span>
      </summary>
      <div class="cp-settings-panel">${if (!showPreviewThemeSetting) "" else "\n" + """<fieldset class="cp-settings-group">
          <legend class="cp-settings-legend">Page theme</legend>
          <label class="cp-settings-option">
            <input type="radio" name="cp-page-theme" value="match" data-cp-page-theme checked>
            <span>Match the preview theme</span>
          </label>
          <label class="cp-settings-option">
            <input type="radio" name="cp-page-theme" value="system" data-cp-page-theme>
            <span>Follow my system</span>
          </label>
          <p class="cp-settings-hint">Selecting a Light or Dark preview theme paints this page to
            match. Choose Follow my system to keep the page on your operating system's setting.</p>
        </fieldset>""".trimIndent().prependIndent("        ")}
        <fieldset class="cp-settings-group cp-settings-keyboard">
          <legend class="cp-settings-legend">Keyboard</legend>
          <label class="cp-settings-option">
            <input type="checkbox" data-cp-keyboard-navigation>
            <span>Power-user navigation</span>
          </label>
          <p class="cp-settings-hint">Jump between components, variants, modes, and overrides with
            shortcuts and an on-screen command palette.</p>
          <button type="button" class="cp-settings-tour" data-cp-keyboard-tour>
            View keyboard tour
          </button>
        </fieldset>${if (session.isEmpty()) "" else "\n" + session.prependIndent("        ")}
      </div>
    </details>
    """
      .trimIndent()

  /**
   * A breadcrumb trail for [siteHeader]'s brand slot: [parent] as a link, then [current] as inert
   * text when the page is a leaf. Both are escaped here, so callers pass raw text.
   */
  private fun crumbHtml(href: String, parent: String, current: String? = null): String {
    val tail =
      current
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "<span class=\"cp-crumb-sep\" aria-hidden=\"true\">/</span>" +
            "<span class=\"cp-crumb-current\">${WebEscaping.htmlEscape(it)}</span>"
        } ?: ""
    return "<nav class=\"cp-breadcrumb\" aria-label=\"Breadcrumb\">" +
      "<a href=\"${WebEscaping.htmlEscape(href)}\">${WebEscaping.htmlEscape(parent)}</a>$tail</nav>"
  }

  /**
   * The per-preview "source" link to this preview's file on GitHub. [href] is the blob URL
   * ([ServeUrls.githubBlobUrl]); null/blank renders nothing. [path] is the tooltip. Both are
   * attribute-escaped.
   */
  private fun sourceLinkHtml(href: String?, path: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    val title =
      path?.takeIf { it.isNotBlank() }?.let { " title=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
    return "\n      <p class=\"cp-source\">" +
      "<a class=\"cp-source-link\" href=\"${WebEscaping.htmlEscape(url)}\"$title>" +
      "$GITHUB_ICON source</a></p>"
  }

  /**
   * The viewer's "report an issue" affordance: a prefilled GitHub new-issue form assembled by
   * [ServeIssueReport] (see [ServeIssueReport.action] for why a form).
   *
   * [body] is the hidden input filled for the served settings so it works with JS off;
   * [bodyTemplate] holds [ServeIssueReport.RENDER_PLACEHOLDER], which the viewer JS re-substitutes
   * as overrides change. No title: the reporter types it. [repo] names the target and [login] the
   * authoring account, if signed in. [subject] names what the report is about (e.g. page-scoped on
   * the comparison wall).
   */
  data class ReportIssue(
    val action: String,
    val body: String,
    val bodyTemplate: String,
    val repo: String,
    val login: String? = null,
    val subject: String = "this preview",
    /**
     * The design system id a browser-written locator must name. Non-null enables the comparison
     * wall's row pickers: it means the template carries [ServeIssueReport.LOCATORS_PLACEHOLDER] and
     * the page exposes this and [locatorRevision]. Null elsewhere.
     */
    val locatorSystem: String? = null,
    /** Delivery provenance as `owner/repo@branch`, the locator's `revision:` line. */
    val locatorRevision: String? = null,
  )

  /**
   * The Figma node a preview is specified by: a deep [url] built by [ServeFigmaSpec] from a literal
   * origin plus validated ids (so a hostile catalog cannot inject an href), and a [label] naming
   * which spec it is.
   */
  data class FigmaSpec(val url: String, val label: String? = null, val provider: String = "Figma")

  /**
   * A published design page as navigation needs it: its name and URL id. Not the whole
   * [DesignPage], whose node list can be megabytes.
   */
  data class PageLink(
    val id: String,
    val name: String,
    /** The page's own major sections, in the design file's order. Empty ⇒ a leaf row. */
    val sections: List<PageSection> = emptyList(),
  )

  /**
   * One **major section** of a design page — a Figma `COMPONENT_SET`. Grouping nodes only; listing
   * every component would rebuild the wall of rows this navigation avoids.
   */
  data class PageSection(val nodeId: String, val name: String)

  /**
   * The HTML `id` a page's node hotspot carries. Design-tool ids (`1:23`) are untrusted and not
   * selector/fragment-safe, so every character outside the safe set becomes `-`. Collisions are
   * possible but harmless (worst case lands on a sibling), and readable fragments beat
   * percent-encoding.
   */
  fun nodeAnchorId(nodeId: String): String =
    "cp-node-" +
      nodeId
        .map { if (it.isLetterOrDigit() || it == '.' || it == '_') it else '-' }
        .joinToString("")

  /**
   * The row under the viewer's title holding the per-preview provenance links: "source", "report an
   * issue", and "figma spec". Any may be absent; when all are, the row is omitted.
   */
  private fun previewLinksHtml(
    sourceHref: String?,
    sourcePath: String?,
    report: ReportIssue?,
    figmaSpec: FigmaSpec?,
    playgroundHref: String?,
    executableBundleHref: String?,
    parallelLayersHref: String,
    a2uiPlaygroundHref: String? = null,
  ): String {
    val links =
      sourceLinkHtml(sourceHref, sourcePath) +
        playgroundLinkHtml(playgroundHref) +
        a2uiPlaygroundLinkHtml(a2uiPlaygroundHref) +
        executableBundleLinkHtml(executableBundleHref) +
        parallelLayersLinkHtml(parallelLayersHref) +
        reportIssueHtml(report) +
        figmaSpecHtml(figmaSpec)
    if (links.isBlank()) return ""
    return "\n      <div class=\"cp-preview-links\">$links\n      </div>"
  }

  /**
   * "A2UI playground": for a preview declaring the A2UI `document` knob, the playground opened on
   * its document and re-rendered through this preview.
   */
  private fun a2uiPlaygroundLinkHtml(href: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    return "\n      <p class=\"cp-source\">" +
      "<a class=\"cp-source-link\" href=\"${WebEscaping.htmlEscape(url)}\" " +
      "title=\"Edit this A2UI document and render it\">▶ A2UI playground</a></p>"
  }

  private fun executableBundleLinkHtml(href: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    return "\n        <a href=\"${WebEscaping.htmlEscape(url)}\" download>download executable bundle</a>"
  }

  /**
   * "open in playground": opens this preview's source in the editor against its catalog. In the
   * provenance row because it is a developer affordance. Null renders nothing.
   */
  private fun playgroundLinkHtml(href: String?): String {
    val url = href?.takeIf { it.isNotBlank() } ?: return ""
    return "\n      <p class=\"cp-source\">" +
      "<a class=\"cp-source-link\" href=\"${WebEscaping.htmlEscape(url)}\" " +
      "title=\"Open this preview's source in the playground\">▶ playground</a></p>"
  }

  /**
   * "cross-catalog layers": the derived-layer diff against the `compareWith` sibling's render. In
   * the provenance row rather than the spec lane, which only exists for catalogs with design
   * references. Empty (no sibling, or a pinned page) renders nothing.
   */
  private fun parallelLayersLinkHtml(href: String): String {
    val url = href.takeIf { it.isNotBlank() } ?: return ""
    return "\n      <p class=\"cp-source\">" +
      "<a class=\"cp-source-link\" href=\"${WebEscaping.htmlEscape(url)}\" " +
      "title=\"What this catalog and its sibling each resolved for this cell — typography, " +
      "layout and theme\">⇄ cross-catalog layers</a></p>"
  }

  /**
   * Renders [spec] as a link to the Figma node this preview is specified by; null renders nothing.
   */
  private fun figmaSpecHtml(spec: FigmaSpec?): String {
    val s = spec ?: return ""
    val label =
      s.label?.takeIf { it.isNotBlank() }?.let { " — ${WebEscaping.htmlEscape(it)}" } ?: ""
    val tip =
      if (s.provider == "Figma") "Open the Figma node this preview is specified by$label"
      else "Open the ${s.provider} design this preview is specified by$label"
    return "\n      <p class=\"cp-figma\">" +
      "<a class=\"cp-figma-link\" href=\"${WebEscaping.htmlEscape(s.url)}\"" +
      " target=\"_blank\" rel=\"noopener noreferrer\" title=\"${WebEscaping.htmlEscape(tip)}\">" +
      "${if (s.provider == "Figma") "$FIGMA_ICON figma spec" else "UI Builder design"}</a></p>"
  }

  /**
   * Renders [report] as the per-preview "report an issue" affordance: a link-styled disclosure
   * whose panel has one visible control, a **required** Summary.
   *
   * The reporter writes the title because a generated one says nothing about what is wrong; the
   * preview identity is in the body. A script-free `<details>` so it works with JS off, and
   * `required` enforces the title either way. Null renders nothing.
   */
  private fun reportIssueHtml(report: ReportIssue?): String {
    val r = report ?: return ""
    val who =
      r.login?.takeIf { it.isNotBlank() }?.let { " as @${WebEscaping.htmlEscape(it)}" } ?: ""
    val repo = WebEscaping.htmlEscape(r.repo)
    val subject = WebEscaping.htmlEscape(r.subject)
    val tip = "Something wrong with $subject — files against $repo$who"
    // `data-cp-repo` and `data-cp-subject` are read by the floating launcher to name the repo and
    // subject of its catalog half, so rewording the prose cannot change them.
    // The locator facts are emitted only where the template has a `{{locators}}` line
    // ([ReportIssue.locatorSystem]); their presence is what enables the wall's row pickers.
    val locatorFacts =
      (r.locatorSystem
        ?.takeIf { it.isNotBlank() }
        ?.let { " data-cp-locator-system=\"${WebEscaping.htmlEscape(it)}\"" } ?: "") +
        (r.locatorRevision
          ?.takeIf { it.isNotBlank() }
          ?.let { " data-cp-locator-revision=\"${WebEscaping.htmlEscape(it)}\"" } ?: "")
    return "\n      <details class=\"cp-report\" id=\"cp-report\" data-cp-repo=\"$repo\"" +
      " data-cp-subject=\"$subject\"$locatorFacts>" +
      "<summary class=\"cp-report-link\" title=\"$tip\">" +
      "$GITHUB_ICON report a catalog issue</summary>" +
      "\n        <div class=\"cp-report-panel\">" +
      "<form class=\"cp-report-form\" method=\"get\" target=\"_blank\"" +
      " rel=\"noopener\" action=\"${WebEscaping.htmlEscape(r.action)}\">" +
      "<label class=\"cp-report-summary\">Summary" +
      "<input class=\"cp-report-summary-input\" type=\"text\" name=\"title\" required" +
      " autocomplete=\"off\" placeholder=\"Briefly describe what is wrong\"></label>" +
      reportScopeHtml(r.bodyTemplate) +
      reportClassificationHtml() +
      "<input type=\"hidden\" name=\"body\" id=\"cp-report-body\"" +
      " value=\"${WebEscaping.htmlEscape(r.body)}\"" +
      " data-report-template=\"${WebEscaping.htmlEscape(r.bodyTemplate)}\">" +
      "<button type=\"submit\" class=\"cp-report-submit\">" +
      "$GITHUB_ICON Open a prefilled issue</button>" +
      "<span class=\"cp-report-note\">Files against <code>$repo</code>$who — the project whose " +
      "code declares $subject, <em>not</em> the preview server. The rest of the report — " +
      "what you were looking at, which build, the links — is filled in for you on GitHub.</span>" +
      "</form></div></details>"
  }

  /**
   * One `parity:` label the reporter picks: **upstream**, **this catalog**, or not yet known (the
   * default, so an unconsidered report stays unclassified rather than wrongly labelled).
   *
   * A plain `<select name="labels">` because GitHub's new-issue form reads `labels` from the query:
   * no script, works with JS off. Values extend the `parity:` vocabulary read back by
   * [ServeParityIssuesStore]. `<cp-report-classification>` also writes the answer into the body
   * line ([ServeIssueReport.CLASSIFICATION_PREFIX]) for repos without the label.
   */
  private fun reportClassificationHtml(): String =
    "<cp-report-classification class=\"cp-report-class\">" +
      "<label class=\"cp-report-class-label\">Where does it belong?" +
      "<select class=\"cp-report-class-input\" name=\"labels\">" +
      REPORT_CLASSIFICATIONS.joinToString("") { (value, label, sentence) ->
        val selected = if (value == REPORT_CLASSIFICATION_DEFAULT) " selected" else ""
        "<option value=\"${WebEscaping.htmlEscape(value)}\"" +
          " data-cp-sentence=\"${WebEscaping.htmlEscape(sentence)}\"$selected>" +
          WebEscaping.htmlEscape(label) +
          "</option>"
      } +
      "</select></label>" +
      "<span class=\"cp-report-class-note\">Applied as a <code>parity:</code> label, so the " +
      "catalog&rsquo;s issue index can tell a difference that is ours from one that is not. " +
      "Leave it on <em>needs investigating</em> if you are not sure — that is what it is for." +
      "</span></cp-report-classification>"

  /** Controls whether the locator joins back to every variant or only the one being reported. */
  private fun reportScopeHtml(bodyTemplate: String): String {
    if (
      ServeIssueReport.LOCATOR_FENCE !in bodyTemplate &&
        ServeIssueReport.LOCATORS_PLACEHOLDER !in bodyTemplate
    ) {
      return ""
    }
    return "<cp-report-scope class=\"cp-report-class\">" +
      "<label class=\"cp-report-class-label\">Show this issue on" +
      "<select class=\"cp-report-class-input\">" +
      "<option value=\"component\" selected>This component</option>" +
      "<option value=\"variant\" disabled hidden>This component + variant</option>" +
      "</select></label>" +
      "<span class=\"cp-report-class-note\">Component issues appear on every preview variant; " +
      "variant issues appear only on the preview you are reporting.</span>" +
      "</cp-report-scope>"
  }

  /**
   * The three answers as label value → visible text → body sentence. `verification-needed` is the
   * existing `parity:` value; the other two must be known to both
   * `compose-ai-tools/scripts/design-artifacts/parity-issues.mjs` and [ServeParityIssuesStore], or
   * the index drops them.
   */
  private val REPORT_CLASSIFICATIONS =
    listOf(
      Triple(
        "parity:upstream",
        "Upstream — not this catalog",
        "Upstream: the framework or design system this catalog is built on, not the catalog itself.",
      ),
      Triple(
        "parity:catalog",
        "A catalog bug — this repository",
        "A catalog bug: the code in this repository draws it wrongly.",
      ),
      Triple(
        "parity:verification-needed",
        "Needs investigating — not sure yet",
        "Needs investigating: the reporter could not tell whether this is ours or upstream.",
      ),
    )

  private const val REPORT_CLASSIFICATION_DEFAULT = "parity:verification-needed"

  /**
   * The report affordance as a row of its own, for page-scoped reports on catalog surfaces without
   * a per-preview provenance line (landing grid, design page, pages index, motion browser). Without
   * a `#cp-report` the launcher's catalog half stays hidden.
   *
   * Reuses `.cp-preview-links` because `.cp-report`'s panel is anchored to that row (see
   * `serve.css`). [extraClass] is a spacing hook. Null renders nothing.
   */
  private fun pageReportRowHtml(report: ReportIssue?, extraClass: String = ""): String {
    val html = reportIssueHtml(report).takeIf { it.isNotBlank() } ?: return ""
    val cls = if (extraClass.isBlank()) "cp-preview-links" else "cp-preview-links $extraClass"
    return "\n          <div class=\"$cls\">$html\n          </div>"
  }

  /** Render catalog-published GitHub issues. Every href has already been rebuilt by the store. */
  /**
   * The filed-issue list as a **disclosure**: one line of open numbers, the issues behind it — the
   * same trade as [compareBugsCellHtml], so the list never pushes the render below the fold.
   *
   * [label] precedes the numbers (blank drops it). [openByDefault] is for a page that is about the
   * list. The panel ends with the index's [generatedAt] where available, since the rows are a
   * snapshot. No counts, matching the wall.
   */
  private fun parityIssueRowsHtml(
    issues: List<ParityIssue>,
    generatedAt: String? = null,
    label: String = "Issues",
    openByDefault: Boolean = false,
  ): String {
    if (issues.isEmpty()) return ""
    val rows =
      issues.joinToString("\n") { issue ->
        val state = if (issue.state == "closed") " closed" else ""
        val classification =
          listOfNotNull(issue.area?.let { "area:$it" }, issue.parity?.let { "parity:$it" })
            .joinToString(" · ")
        val meta = if (classification.isEmpty()) issue.state else "${issue.state} · $classification"
        "<li class=\"cp-parity-issue$state\"><a href=\"${WebEscaping.htmlEscape(issue.url)}\" " +
          "rel=\"noopener\">#${issue.number} ${WebEscaping.htmlEscape(issue.title)}</a>" +
          "<span>${WebEscaping.htmlEscape(meta)}</span></li>"
      }
    val open = issues.filter { it.state == "open" }
    val numbers =
      open.joinToString("") { "<span class=\"cp-parity-issue-chip\">#${it.number}</span>" }
    // With nothing open, the closed numbers are shown dimmed in place of the marker.
    val closedMark =
      when {
        issues.none { it.state == "closed" } -> ""
        open.isEmpty() ->
          issues.joinToString("") {
            "<span class=\"cp-parity-issue-chip cp-parity-issue-chip--closed\">#${it.number}</span>"
          }
        else -> "<span class=\"cp-parity-issue-chip cp-parity-issue-chip--closed\">closed</span>"
      }
    val asOf =
      generatedAt
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "<p class=\"cp-parity-issues-asof\">index as of " +
            "${WebEscaping.htmlEscape(prettyDate(it))}</p>"
        }
        .orEmpty()
    val heading =
      label
        .takeIf { it.isNotBlank() }
        ?.let { "<strong>${WebEscaping.htmlEscape(it)}</strong>" }
        .orEmpty()
    return "<details class=\"cp-parity-issues\"${if (openByDefault) " open" else ""}>" +
      "<summary class=\"cp-parity-issues-sum\">$heading$numbers$closedMark</summary>" +
      "<ul>$rows</ul>$asOf</details>"
  }

  /** Compact, non-link form safe to place inside a card whose whole body is already an anchor. */
  private fun parityIssueBadgeHtml(issues: List<ParityIssue>): String {
    if (issues.isEmpty()) return ""
    val open = issues.count { it.state == "open" }
    val closed = issues.size - open
    val label = buildList {
      if (open > 0) add("$open open")
      if (closed > 0) add("$closed closed")
    }
      .joinToString(" · ")
    val title = issues.joinToString("; ") { "#${it.number} ${it.title}" }
    return "<span class=\"cp-issue-badge\" title=\"${WebEscaping.htmlEscape(title)}\">${WebEscaping.htmlEscape(label)} issue${if (issues.size == 1) "" else "s"}</span>"
  }

  /**
   * The parity **verdict** panel, grouped as the run reports: accessibility and i18n, tokens,
   * layout, then pixels.
   *
   * Server-rendered so findings are quotable, searchable and readable without script; only anchors
   * travel as data. The anchor payload sits inside the section right after the rows it keys: the
   * ids are minted together, and `<cp-reference-compare>` installs when the parser reaches its tag
   * further down, so a later payload would not exist yet.
   */
  private fun parityVerdictHtml(sets: List<ParityFindingSet>): String {
    val findings = sets.flatMap { it.findings }
    // A run that found nothing still prints "Pass", unlike a catalog nobody ran, so a set survives
    // on its declared status alone.
    if (findings.isEmpty() && sets.none { it.status != null }) return ""
    // Worst declared status wins. A run that declared none at all is read off its own findings,
    // which is the same rule the producing engine applies and keeps a hand-written manifest honest.
    val declared = sets.mapNotNull { it.status }
    val status =
      when {
        "fail" in declared -> "fail"
        "warn" in declared -> "warn"
        declared.isNotEmpty() -> "pass"
        findings.any { it.severity == ParityFindingSeverity.ERROR } -> "fail"
        findings.any { it.severity == ParityFindingSeverity.WARN } -> "warn"
        else -> "pass"
      }
    val tally =
      listOf(
          ParityFindingSeverity.ERROR to "error",
          ParityFindingSeverity.WARN to "warning",
          ParityFindingSeverity.INFO to "note",
        )
        .mapNotNull { (severity, noun) ->
          findings
            .count { it.severity == severity }
            .takeIf { it > 0 }
            ?.let { "$it $noun${if (it == 1) "" else "s"}" }
        }
        .joinToString(" · ")
    val anchors = LinkedHashMap<String, List<ParityAnchor>>()
    val groups =
      ParityFindingGroup.entries.mapNotNull { group ->
        val rows = findings.filter { ParityFindingGroup.of(it.kind) == group }
        if (rows.isEmpty()) return@mapNotNull null
        val items = rows.mapIndexed { index, finding ->
          val id = "${group.id}-$index"
          if (finding.anchors.isNotEmpty()) anchors[id] = finding.anchors
          parityFindingRowHtml(id, finding)
        }
        "    <section class=\"cp-parity-group\" data-cp-parity-group=\"${group.id}\">\n" +
          "      <h3>${WebEscaping.htmlEscape(group.title)}" +
          "<span class=\"cp-parity-count\">${rows.size}</span></h3>\n" +
          "      <ul class=\"cp-parity-list\">\n        " +
          items.joinToString("\n        ") +
          "\n      </ul>\n    </section>"
      }
    // Only invite hovering when some finding has geometry to respond.
    val hint =
      if (anchors.isEmpty()) ""
      else
        "\n  <p class=\"cp-parity-hint\">Hover a finding to light the region it describes on " +
          "both panels; click to keep it lit.</p>"
    val report =
      sets
        .firstNotNullOfOrNull { it.reportUrl }
        ?.let {
          "<a class=\"cp-parity-report\" href=\"${WebEscaping.htmlEscape(it)}\" " +
            "rel=\"noopener\">Full parity report</a>"
        }
        .orEmpty()
    // Carries its own leading newline and skips `trimIndent()`: trimming runs after interpolation,
    // so nested indentation would leak and an empty block would leave a blank line.
    val section =
      "\n<section class=\"cp-parity-verdict\" id=\"cp-parity-verdict\"" +
        " aria-labelledby=\"cp-parity-verdict-title\">\n" +
        "  <div class=\"cp-parity-verdict-head\">\n" +
        "    <h2 id=\"cp-parity-verdict-title\">Design parity</h2>\n" +
        "    <span class=\"cp-parity-status cp-parity-status--$status\">" +
        status.replaceFirstChar { it.uppercase() } +
        "</span>\n" +
        "    <span class=\"cp-parity-tally\">${WebEscaping.htmlEscape(tally)}</span>" +
        report +
        "\n  </div>" +
        hint +
        (if (groups.isEmpty()) "\n  <p class=\"cp-parity-clean\">No findings.</p>"
        else "\n  <div class=\"cp-parity-groups\">\n" + groups.joinToString("\n") + "\n  </div>")
    val payload =
      if (anchors.isEmpty()) ""
      else
        "\n  <script type=\"application/json\" id=\"cp-parity-anchors\">" +
          encodeParityAnchorPayload(ParityAnchorPayload(anchors)) +
          "</script>"
    return section + payload + "\n</section>"
  }

  /**
   * One finding row, carrying its anchor id only when it has somewhere to point.
   * `tabindex`/`role`/`aria-pressed` are added by `<cp-reference-compare>` once it has built the
   * boxes, so with no script no row pretends to be a control.
   */
  private fun parityFindingRowHtml(id: String, finding: ParityFinding): String {
    val expected = finding.detail["expected"]
    val actual = finding.detail["actual"]
    val token = finding.detail["token"] ?: finding.detail["property"]
    // The token is printed whenever the finding names one: a spec token is the finding's subject
    // even with no numeric delta, and `rest` below excludes the key.
    val delta =
      if (expected == null && actual == null && token == null) ""
      else
        "<span class=\"cp-parity-delta\">" +
          (token?.let { "<code>${WebEscaping.htmlEscape(it)}</code>" } ?: "") +
          (expected?.let {
            "<span class=\"cp-parity-expected\">expected ${WebEscaping.htmlEscape(it)}</span>"
          } ?: "") +
          (actual?.let {
            "<span class=\"cp-parity-actual\">actual ${WebEscaping.htmlEscape(it)}</span>"
          } ?: "") +
          "</span>"
    // Every remaining key, as the row's title, so producer-specific measurements stay readable.
    val rest =
      finding.detail
        .filterKeys { it != "expected" && it != "actual" && it != "token" && it != "property" }
        .entries
        .joinToString(" · ") { (key, value) -> "$key $value" }
    val title = if (rest.isEmpty()) "" else " title=\"${WebEscaping.htmlEscape(rest)}\""
    val anchored = finding.anchors.isNotEmpty()
    val interactive = if (!anchored) "" else " data-cp-parity-finding=\"$id\""
    val where =
      if (!anchored) ""
      else
        "<span class=\"cp-parity-where\">${finding.anchors.size} region" +
          "${if (finding.anchors.size == 1) "" else "s"}</span>"
    return "<li class=\"cp-parity-finding cp-parity-finding--${finding.severity}" +
      " cp-parity-finding--kind-${finding.kind}\"$interactive$title>" +
      "<span class=\"cp-parity-sev\">${WebEscaping.htmlEscape(finding.severity)}</span>" +
      "<span class=\"cp-parity-body\"><span class=\"cp-parity-msg\">" +
      WebEscaping.htmlEscape(finding.message) +
      "</span>$delta</span>$where</li>"
  }

  /**
   * The rows of a published index that belong to the catalog serving [system].
   *
   * One repository may declare several design systems and publish the same index onto each delivery
   * branch; component and preview ids collide across them, so scope the index before matching. A
   * row with no system is kept (the field is optional); only a row naming a different system is
   * dropped.
   *
   * Display only: the acceptance lifecycle join resolves issues by URL and may cite a sibling
   * system's issue, so handlers pass the whole index as `acceptanceIssues` and the scoped list as
   * `parityIssues`.
   */
  fun issuesForSystem(issues: List<ParityIssue>, system: String?): List<ParityIssue> {
    if (system.isNullOrBlank()) return issues
    return issues.filter { it.system == null || it.system == system }
  }

  private fun issuesForPreview(
    issues: List<ParityIssue>,
    preview: ServePreview,
  ): List<ParityIssue> = issues.filter { issue ->
    preview.id in issue.previewIds ||
      (issue.scope == "component" && issue.component == ServeIssueReport.componentIdFor(preview))
  }

  /**
   * The issues one **comparison row** carries: component-scoped issues naming any of its preview
   * [ids] or its component, plus variant-scoped issues naming one of [variantIds] exactly. Exact
   * reports are emitted for every theme variant so the browser can swap pills with the pictures;
   * folded-out siblings only alias. Open before closed, then newest first.
   */
  private fun issuesForRow(
    issues: List<ParityIssue>,
    ids: List<String>,
    componentId: String?,
    variantIds: List<String>,
  ): List<ParityIssue> {
    if (issues.isEmpty()) return emptyList()
    val wanted = ids.toSet()
    val exact = variantIds.toSet()
    return issues
      .filter { issue ->
        if (issue.scope == "variant") issue.previewIds.any { it in exact }
        else
          issue.previewIds.any { it in wanted } ||
            (componentId != null && issue.component == componentId)
      }
      .sortedWith(compareBy({ it.state != "open" }, { -it.number }))
  }

  /**
   * The wall's **Bugs** cell: one line of what is already filed against this row, a disclosure with
   * the detail, and a link to file more.
   *
   * Collapsed it is one line — open issue numbers, the closed marker, and "+ file" — to keep the
   * column narrow; titles, closed issues and classification sit behind the disclosure. The panel
   * ends with the index's `generatedAt` because the state is a snapshot; nothing re-checks GitHub
   * on the render path.
   *
   * No counts: `CompareWall` hides variant-scoped rows as the theme swaps ([scopeAttrs]), so a
   * server-printed count could be wrong. "+ file" is always offered, outside the disclosure.
   * [detailHref] is the focused comparison for the served pair; [fallbackHref] the viewer's own
   * report for a row with no reference.
   */
  private fun compareBugsCellHtml(
    issues: List<ParityIssue>,
    activePreviewId: String,
    detailHref: String?,
    fallbackHref: String,
    generatedAt: String?,
  ): String {
    val file =
      "<a class=\"cp-compare-bug-new\" " +
        "href=\"${WebEscaping.htmlEscape(detailHref ?: fallbackHref)}\" " +
        "data-bug-fallback=\"${WebEscaping.htmlEscape(fallbackHref)}\" " +
        "title=\"Report what is wrong with this comparison\">+&#8202;file</a>"
    if (issues.isEmpty()) return "\n            <td class=\"cp-compare-bugs\">$file</td>"

    // The contract `CompareWall` toggles on, carried on both the collapsed number and its panel
    // entry so the browser hides them together.
    fun scopeAttrs(issue: ParityIssue): String =
      if (issue.scope != "variant") " data-bug-scope=\"component\""
      else {
        val previewIds = issue.previewIds.joinToString(" ")
        val hidden = if (activePreviewId in issue.previewIds) "" else " hidden"
        " data-bug-scope=\"variant\" data-bug-preview-ids=\"${WebEscaping.htmlEscape(previewIds)}\"$hidden"
      }

    val open = issues.filter { it.state == "open" }
    val numbers =
      open.joinToString("") { issue ->
        "<span class=\"cp-compare-bug-chip\"${scopeAttrs(issue)}>#${issue.number}</span>"
      }
    // A row whose reports are all closed still says so; with nothing open, the closed numbers
    // replace the marker (same rule as [parityIssueRowsHtml]).
    val closedMark =
      when {
        issues.none { it.state == "closed" } -> ""
        open.isEmpty() ->
          issues.joinToString("") { issue ->
            "<span class=\"cp-compare-bug-chip cp-compare-bug-chip--closed\"" +
              "${scopeAttrs(issue)}>#${issue.number}</span>"
          }
        else -> "<span class=\"cp-compare-bug-chip cp-compare-bug-chip--closed\">closed</span>"
      }
    val entries =
      issues.joinToString("") { issue ->
        val closed = issue.state == "closed"
        val title = issue.title.trim()
        // `parity-issues.mjs` refuses untitled issues, but this is catalog data, so render the
        // number alone if one appears.
        val titleHtml =
          if (title.isEmpty()) ""
          else
            "<span class=\"cp-compare-bug-title\" title=\"${WebEscaping.htmlEscape(title)}\">" +
              "${WebEscaping.htmlEscape(title)}</span>"
        // The reporter's classification, when the issue carries one.
        val tag =
          issue.parity
            ?.takeIf { it.isNotBlank() }
            ?.let { "<span class=\"cp-compare-bug-tag\">${WebEscaping.htmlEscape(it)}</span>" }
            .orEmpty()
        val state = if (closed) "<span class=\"cp-compare-bug-state\">closed</span>" else ""
        "<li class=\"cp-compare-bug-item${if (closed) " cp-compare-bug-item--closed" else ""}\"" +
          "${scopeAttrs(issue)}>" +
          "<a class=\"cp-compare-bug\" href=\"${WebEscaping.htmlEscape(issue.url)}\" " +
          "rel=\"noopener\"><span class=\"cp-compare-bug-num\">#${issue.number}</span>" +
          "$state$tag</a>$titleHtml</li>"
      }
    val asOf =
      generatedAt
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "<p class=\"cp-compare-bug-asof\">index as of " +
            "${WebEscaping.htmlEscape(prettyDate(it))}</p>"
        }
        .orEmpty()
    // The panel is ONE element so it can be lifted out of flow: an open row must not push the
    // wall's picture columns sideways, and `<details>` gives its children no common box.
    return "\n            <td class=\"cp-compare-bugs\">" +
      "<details class=\"cp-compare-bug-disclosure\">" +
      "<summary class=\"cp-compare-bug-summary\" title=\"What is already filed here\">" +
      "$numbers$closedMark</summary>" +
      "<div class=\"cp-compare-bug-panel\">" +
      "<ul class=\"cp-compare-bug-list\">$entries</ul>$asOf</div></details>$file</td>"
  }

  /**
   * Provenance of a served design-system catalog: the trusted GitHub [repo]/[branch], [generatedAt]
   * (ISO-8601), and the [toolVersion] + [designParityVersion] that produced it. Built from
   * [ServeCatalogStore] plus `catalog.json`; null fields are omitted.
   */
  data class CatalogProvenance(
    val repo: String,
    val branch: String,
    /**
     * The delivery-branch commit this catalog was fetched at — the revision permalinks pin to
     * ([ServeCatalogRevision]). Null for an uploaded bundle or an unreadable branch; no permalink
     * is offered then.
     */
    val commit: String? = null,
    val generatedAt: String? = null,
    val toolVersion: String? = null,
    val designParityVersion: String? = null,
  )

  /**
   * The **source** a catalog was built from (`catalog.json`'s `source = {repo, ref, module}`), as
   * opposed to the delivery [CatalogProvenance]. Per-preview "source" links point at
   * `blob/<ref>/<module>/<sourceFile>`. Null for a plain bundle or undeclared source.
   */
  data class CatalogSource(val repo: String, val ref: String, val module: String)

  /**
   * One thing the spec lane can put on the stage beside the render. The lane's views work over any
   * image pair, so a second comparison is a second source rather than a new mode.
   *
   * * `kit` — the imported design reference: a specification fixed at publish time.
   * * `parallel` — the counterpart component in the `compareWith` sibling: another implementation's
   *   render, not a spec.
   *
   * [provenance] states that the sibling's render used its own theme, knobs and overrides, so the
   * comparison is not implied to be symmetric.
   *
   * @property id the value the picker carries (`kit` / `parallel`); also the URL state's token.
   * @property label what the picker button reads, e.g. `Figma` or `wear-m3-catalog`.
   * @property rasterUrl same-origin URL of the image to compare against; the viewer refuses any
   *   other origin ([specRasterSrc]).
   * @property provenance one line naming where this image came from; empty when no caveat is
   *   needed.
   */
  data class SpecSource(
    val id: String,
    val label: String,
    val rasterUrl: String,
    val provenance: String = "",
  )

  /**
   * "2026-07-17T12:34:56.789Z" → "2026-07-17 12:34 UTC"; anything unparseable is shown verbatim.
   */
  private fun prettyDate(iso: String): String {
    val m = Regex("""^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})""").find(iso) ?: return iso
    return "${m.groupValues[1]} ${m.groupValues[2]} UTC"
  }

  /**
   * The catalog-provenance strip on a catalog landing: delivery [branch][CatalogProvenance.branch]
   * link, generation date, tool versions, and a link to re-run the `design-artifacts` workflow.
   * Empty [prov] fields drop their item.
   */
  private fun provenanceSection(prov: CatalogProvenance, refreshUrl: String?): String {
    val repo = WebEscaping.htmlEscape(prov.repo)
    val branch = WebEscaping.htmlEscape(prov.branch)
    // Branch names carry a `/` (`design-artifacts/compose-m3`); it's a valid path in a tree URL.
    val branchUrl = "https://github.com/${prov.repo}/tree/${prov.branch}"
    val actionUrl = "https://github.com/${prov.repo}/actions/workflows/design-artifacts.yml"
    val items = buildList {
      add(
        "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">catalog</span> " +
          "<a href=\"$branchUrl\">$GITHUB_ICON $repo@$branch</a></span>"
      )
      // Which publish is on screen; the branch link alone names a moving target.
      ServeCatalogRevision.treeUrl(prov.repo, prov.commit)?.let { url ->
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">revision</span> " +
            "<a href=\"${WebEscaping.htmlEscape(url)}\"><code>" +
            "${WebEscaping.htmlEscape(ServeCatalogRevision.short(prov.commit!!))}</code></a></span>"
        )
      }
      prov.generatedAt
        ?.takeIf { it.isNotBlank() }
        ?.let {
          add(
            "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">generated</span> " +
              "${WebEscaping.htmlEscape(prettyDate(it))}</span>"
          )
        }
      val tool = prov.toolVersion?.takeIf { it.isNotBlank() }
      val dp = prov.designParityVersion?.takeIf { it.isNotBlank() }
      if (tool != null || dp != null) {
        val parts = buildList {
          if (tool != null) add("compose-ai-tools <code>${WebEscaping.htmlEscape(tool)}</code>")
          if (dp != null) add("design-parity <code>${WebEscaping.htmlEscape(dp)}</code>")
        }
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">rendered by</span> " +
            "${parts.joinToString(" · ")}</span>"
        )
      }
      add("<span class=\"cp-prov-item\"><a href=\"$actionUrl\">regenerate ↗</a></span>")
      refreshUrl?.let {
        add(
          "<span class=\"cp-prov-item\"><button type=\"button\" class=\"cp-prov-refresh\" " +
            "data-refresh-url=\"${WebEscaping.htmlEscape(it)}\">refresh</button>" +
            "<span class=\"cp-prov-refresh-status\" role=\"status\" aria-live=\"polite\"></span></span>"
        )
      }
    }
    return """
      <details class="cp-prov cp-disclosure" open>
        <summary>
          <span class="cp-prov-title">Catalog details</span>
          <span class="cp-disclosure-hint">Source, generation time and tooling</span>
        </summary>
        <div class="cp-prov-body" aria-label="Catalog provenance">
          ${items.joinToString("\n          ")}
        </div>
      </details>
      ${if (refreshUrl == null) "" else provenanceRefreshScript()}
      """
      .trimIndent()
  }

  private fun provenanceRefreshScript(): String =
    """
    <script>
    (() => {
      const button = document.querySelector('.cp-prov-refresh');
      if (!button) return;
      const status = document.querySelector('.cp-prov-refresh-status');
      button.addEventListener('click', async () => {
        button.disabled = true;
        status.textContent = 'checking…';
        try {
          const response = await fetch(button.dataset.refreshUrl, { method: 'POST' });
          const result = await response.json();
          if (result.status === 'updated') {
            status.textContent = 'updated';
            window.location.reload();
            return;
          }
          status.textContent = result.status === 'current' ? 'up to date' :
            result.status === 'checking' ? 'check in progress' : 'check failed';
        } catch (_) {
          status.textContent = 'check failed';
        }
        button.disabled = false;
      });
    })();
    </script>
    """
      .trimIndent()

  /**
   * The theme axis (`light`/`dark`) baked into a flattened catalog id, or null if it carries none.
   */
  private fun cardTheme(id: String): String? =
    id.split("__").drop(1).lastOrNull { it == "light" || it == "dark" }

  /**
   * Whether the served system is dark-first (e.g. Wear OS), so a preview with no explicit
   * light/dark token sits on the dark stage. Keyed off the system name from [basePath] or, for the
   * legacy `?session=` form, the session id, via [SystemDisplay].
   */
  private fun isDarkFirstSystem(
    basePath: String,
    sessionId: String?,
    declaredSurface: String? = null,
  ): Boolean {
    val system = basePath.trim('/').ifBlank { sessionId ?: "" }
    return SystemDisplay.resolveDarkFirst(system, declaredSurface)
  }

  /**
   * The stage / thumbnail **background** theme: the explicit `__light` / `__dark` token, else dark
   * for a dark-first system ([isDarkFirstSystem]), else none. Distinct from [cardTheme], which
   * drives the filter axis and must stay explicit-only so a dark-first catalog doesn't get a dead
   * Light/Dark toggle.
   */
  private fun bgTheme(id: String, darkFirst: Boolean): String? =
    cardTheme(id) ?: if (darkFirst) "dark" else null

  /**
   * An `#AARRGGBB` data-product colour as CSS. CSS 8-digit hex puts alpha last (`#RRGGBBAA`), so
   * the wire form must be reordered; opaque colours drop alpha.
   */
  private fun String.asCssColor(): String {
    val hex = removePrefix("#")
    if (hex.length != 8) return this
    val alpha = hex.take(2)
    val rgb = hex.drop(2)
    return if (alpha.equals("FF", ignoreCase = true)) "#$rgb" else "#$rgb$alpha"
  }

  /**
   * The ground this preview should be shown on, resolved through [PreviewBackdrop]: what the
   * preview states about itself first, the catalog's stage after.
   *
   * A `showBackground = false` sticker wants the catalog stage, while an explicit `backgroundColor`
   * must keep its colour even in a dark-first catalog. The [Backdrop][PreviewBackdrop.Backdrop]
   * carries its `source` so pages can show why a stage was chosen.
   */
  internal fun backdropFor(
    preview: ServePreview,
    darkFirst: Boolean,
    /**
     * The render lane's `uiMode` override (`"light"`/`"dark"`), when this page shows one. It is the
     * effective render state, so it outranks the preview's discovery-time `uiMode`.
     */
    uiModeOverride: String? = null,
  ): PreviewBackdrop.Backdrop {
    val overriddenSurface = PreviewBackdrop.CatalogSurface.parse(uiModeOverride)
    return PreviewBackdrop.withCatalogDefault(
      PreviewBackdrop.resolve(
        showBackground = preview.showBackground,
        backgroundColor = preview.backgroundColor,
        night =
          overriddenSurface?.let { it == PreviewBackdrop.CatalogSurface.DARK }
            ?: PreviewBackground.isNight(preview.uiMode),
        // The variant's own theme: a dark variant in a light-first catalog needs a dark ground too.
        variantSurface = overriddenSurface ?: variantSurfaceOf(preview),
      ),
      if (darkFirst) PreviewBackdrop.CatalogSurface.DARK else PreviewBackdrop.CatalogSurface.LIGHT,
    )
  }

  /**
   * The device-frame clip for a preview as a CSS `clip-path`, or null when the whole capture is
   * screen.
   *
   * The shape half of [backdropFor]: a round Wear capture is a circle in a square PNG, and with
   * black-on-black backdrops the device edge disappears unless clipped. Sized to the device box,
   * which matches the `<img>` box since panels size it `auto`.
   */
  internal fun stageClipFor(
    preview: ServePreview,
    /**
     * The render lane's overrides, when present. The clip must describe the frame actually rendered
     * (e.g. `?device=id:wearos_square`), not the discovered one. Same reason [backdropFor] takes
     * `uiModeOverride`.
     */
    overrides: Map<String, String> = emptyMap(),
  ): String? {
    val frame = effectiveDeviceFrame(preview, overrides) ?: return null
    val shape = PreviewClip.resolve(frame.isRound, frame.widthDp, frame.heightDp) ?: return null
    return PreviewClip.cssClipPath(
      shape,
      frame.widthDp ?: return null,
      frame.heightDp ?: return null,
    )
  }

  /**
   * The device frame this comparison actually rendered at, or null when it cannot be stated.
   *
   * An explicit `device=` override is resolved from the device catalog, shape included. A size
   * override suppresses the clip: pixels without density and orientation rules can't be re-derived
   * here, and a wrong clip hides real pixels. Internal because [ServeRenderMatte] needs the same
   * frame, and one copy keeps stage and clip consistent.
   */
  internal fun effectiveDeviceFrame(
    preview: ServePreview,
    overrides: Map<String, String>,
  ): ServeDeviceFrame? {
    if (SIZE_OVERRIDE_KEYS.any { !overrides[it].isNullOrBlank() }) return null
    val device = overrides["device"]?.takeIf { it.isNotBlank() } ?: return preview.deviceFrame
    return ServeDeviceFrame.from(device, widthDp = null, heightDp = null)
  }

  /**
   * Render overrides that change the frame's shape in a way this page cannot re-derive. A list so a
   * new sizing knob in [ServeOverrides.SUPPORTED_KEYS] is one line here.
   */
  private val SIZE_OVERRIDE_KEYS =
    listOf(
      "widthPx",
      "heightPx",
      "minWidthPx",
      "minHeightPx",
      "maxWidthPx",
      "maxHeightPx",
      "orientation",
    )

  /**
   * The light/dark variant a preview **is**: the catalog's baked `theme`, else its night `uiMode`;
   * null when unthemed. Same signals and order as [previewTheme], kept in step deliberately.
   */
  private fun variantSurfaceOf(preview: ServePreview): PreviewBackdrop.CatalogSurface? =
    PreviewBackdrop.CatalogSurface.parse(preview.theme)
      ?: when (preview.uiMode and UI_MODE_NIGHT_MASK) {
        UI_MODE_NIGHT_YES -> PreviewBackdrop.CatalogSurface.DARK
        UI_MODE_NIGHT_NO -> PreviewBackdrop.CatalogSurface.LIGHT
        else -> null
      }

  /**
   * The preview's baked theme, preferring explicit catalog metadata, then discovery-time uiMode,
   * over id heuristics. Falls through to [backdropFor] so a preview declaring its own ground uses
   * it.
   */
  private fun previewTheme(preview: ServePreview, darkFirst: Boolean): String? =
    preview.theme
      ?: when (preview.uiMode and UI_MODE_NIGHT_MASK) {
        UI_MODE_NIGHT_YES -> "dark"
        UI_MODE_NIGHT_NO -> "light"
        // A preview that states its own ground overrides the catalog stage; otherwise keep
        // [bgTheme]'s answer, whose `null` means "emit no tag", not "light".
        else -> declaredBackdropTheme(preview) ?: bgTheme(preview.id, darkFirst)
      }

  /**
   * `"light"`/`"dark"` when the preview's own `@Preview` params name a ground, else null. Narrower
   * than [backdropFor]: the catalog-default rung is [bgTheme]'s, and answering it here would turn
   * its null into a `light` tag everywhere.
   */
  private fun declaredBackdropTheme(preview: ServePreview): String? =
    PreviewBackdrop.resolve(
        showBackground = preview.showBackground,
        backgroundColor = preview.backgroundColor,
        night = PreviewBackground.isNight(preview.uiMode),
      )
      .takeIf { it.source != PreviewBackdrop.Source.NONE }
      ?.let { if (it.isDark) "dark" else "light" }

  /**
   * Catalog-specific `sessionStorage` key shared by a catalog's landing and viewer pages, so the
   * theme choice is per-tab. See [viewerThemeStickyScript].
   */
  private fun themeStorageKey(sessionId: String?, basePath: String): String {
    val catalog = basePath.trim('/').ifBlank { sessionId ?: "default" }
    return "cp-theme:${WebEscaping.urlEncodeSegment(catalog)}"
  }

  /** Stable, catalog-specific key for the last section selected on that catalog's landing page. */
  private fun tabStorageKey(sessionId: String?, basePath: String): String {
    val catalog = basePath.trim('/').ifBlank { sessionId ?: "default" }
    return "cp-tab:${WebEscaping.urlEncodeSegment(catalog)}"
  }

  /**
   * Catalog-specific prefix for the viewer's remembered disclosures (`viewer-drawers.js`).
   * `localStorage` is per-origin and one host serves many catalogs, so keys must be scoped.
   */
  private fun foldStorageScope(sessionId: String?, basePath: String): String =
    WebEscaping.urlEncodeSegment(basePath.trim('/').ifBlank { sessionId ?: "default" })

  /**
   * The flattened id with its theme token stripped — the key that pairs a component's light and
   * dark variants into one grid card (`…__default__light` / `…__dark` → `…__default`).
   *
   * Strips only the segment [cardTheme] treats as the theme (the last standalone `light`/`dark`
   * after the head): an earlier `light`/`dark` may be a state (`toggle__dark__default__light`), and
   * a slug like `theme-meshcore-light` is one segment.
   */
  private fun baseKey(id: String): String {
    val parts = id.split("__")
    val themeIdx =
      parts.indices.lastOrNull { it >= 1 && (parts[it] == "light" || parts[it] == "dark") }
    return if (themeIdx == null) id
    else parts.filterIndexed { i, _ -> i != themeIdx }.joinToString("__")
  }

  /**
   * The component's identity across every render axis: the slug before the `__ideal` marker
   * ([ServeCatalogStore.previewIdFor]), e.g. `button-filled`. Ids without `__ideal` fall back to
   * [baseKey]. Collapses the viewer's component nav to one entry per component.
   */
  private fun componentKey(p: ServePreview): String {
    val idx = p.id.indexOf("__ideal")
    return if (idx > 0) p.id.substring(0, idx) else baseKey(p.id)
  }

  /**
   * Whether [p] is a non-default component state (`pressed`, `disabled`, …) that the grid folds
   * out, reachable via the viewer's [component subtree][componentSubtreeHtml]. Keyed off the
   * catalog's `state` metadata, not the id; `null` counts as default.
   */
  private fun isNonDefaultState(p: ServePreview): Boolean = p.state != null && p.state != "default"

  /**
   * Whether [p] is a non-default props variant (locale, direction, fontScale, content, …) that the
   * grid folds out, reachable via the viewer's [component subtree][componentSubtreeHtml]. Keyed off
   * the catalog's `props` metadata; empty props count as default.
   */
  private fun hasNonDefaultProps(p: ServePreview): Boolean = !p.props.isNullOrEmpty()

  /**
   * Whether [p] is a render at a non-primary breakpoint, folded onto the component's card like a
   * non-default [state][isNonDefaultState] or [props variant][hasNonDefaultProps]; otherwise each
   * size would be its own card.
   *
   * [primary] comes from [primarySizeByComponent] for [p]'s theme lane. Previews with no declared
   * size, or whose component has no primary in that lane, are never folded, since there would be no
   * switcher to reach them.
   */
  private fun isNonPrimarySize(
    p: ServePreview,
    primary: Map<Pair<String, String>, String>,
    darkFirst: Boolean,
  ): Boolean {
    val size = p.size?.takeIf { it.isNotBlank() } ?: return false
    val componentPrimary = primary[sizeFoldKey(p, darkFirst)] ?: return false
    return size != componentPrimary
  }

  /**
   * A component's identity within one theme lane, for resolving the size fold.
   *
   * Uses [ServePreview.theme] only, not [themeLane]: breakpoint names are arbitrary, and a size
   * named `light` would otherwise be read as a theme and turn the Theme control into a size switch.
   * Unthemed catalogs resolve one lane, which only errs towards keeping more.
   */
  private fun sizeFoldKey(p: ServePreview, darkFirst: Boolean): Pair<String, String> =
    componentKey(p) to (p.theme?.takeIf { it.isNotBlank() } ?: if (darkFirst) "dark" else "light")

  /**
   * Each component's **primary** breakpoint (the size its card is drawn at), keyed by
   * [componentKey] and theme lane.
   *
   * The first size in authored order ([ServePreview.catalogOrder], else list order) — the first
   * declared breakpoint. Per lane because the size switcher is lane-scoped ([componentRenderRows]);
   * a sparse theme × size product would otherwise fold a lane's only render away. The
   * component-wide primary wins in every lane that has it, so lanes enumerating sizes differently
   * still pair into one card; a lane falls back to its own first size only when it lacks that one.
   *
   * Only default renders vote, so a state or props variant cannot pick a primary the default never
   * rendered at.
   */
  private fun primarySizeByComponent(
    previews: List<ServePreview>,
    darkFirst: Boolean,
  ): Map<Pair<String, String>, String> {
    val defaults =
      previews
        .filter { it.size != null && !isNonDefaultState(it) && !hasNonDefaultProps(it) }
        .sortedBy { it.catalogOrder ?: Int.MAX_VALUE }
    // The component's primary across every lane — the size the one card is drawn at when it can be.
    val componentPrimary = LinkedHashMap<String, String>()
    defaults.forEach { componentPrimary.putIfAbsent(componentKey(it), it.size!!) }
    // Which sizes each lane actually published, so a lane can be asked whether it has that primary.
    val sizesByLane = LinkedHashMap<Pair<String, String>, MutableSet<String>>()
    defaults.forEach {
      sizesByLane.getOrPut(sizeFoldKey(it, darkFirst)) { LinkedHashSet() }.add(it.size!!)
    }
    val primary = LinkedHashMap<Pair<String, String>, String>()
    defaults.forEach {
      val key = sizeFoldKey(it, darkFirst)
      val shared = componentPrimary[componentKey(it)]
      primary.putIfAbsent(
        key,
        if (shared != null && shared in sizesByLane.getValue(key)) shared else it.size!!,
      )
    }
    return primary
  }

  /**
   * A preview id with only its **size** segment removed, grouping renders that differ only in
   * breakpoint for the viewer's size switcher.
   *
   * Ids are `<slug>__<variant>__<state>[__theme][__size][__props…]` (`catalog-image-path.mjs`), so
   * [propsCount] trailing segments are skipped before matching the slug of [ServePreview.size].
   * Returns [id] unchanged when no size is declared or found.
   */
  private fun sizeInvariantKey(id: String, size: String?, propsCount: Int): String {
    val token = size?.takeIf { it.isNotBlank() }?.let(::catalogSlug) ?: return id
    val parts = id.split("__")
    val limit = (parts.size - propsCount).coerceAtLeast(0)
    val idx = (1 until limit).lastOrNull { parts[it] == token } ?: return id
    return parts.filterIndexed { i, _ -> i != idx }.joinToString("__")
  }

  /** [sizeInvariantKey] over a render's own id, holding its state and props segments in place. */
  private fun sizeInvariantKey(p: ServePreview): String =
    sizeInvariantKey(p.id, p.size, p.props?.size ?: 0)

  /**
   * The size-switcher grouping key: [sizeInvariantKey] with the theme dropped too, as in
   * [switcherStateKey]; [themeLane] keeps lanes apart.
   */
  private fun switcherSizeKey(p: ServePreview): String =
    themeStrippedKey(sizeInvariantKey(p), p.theme)

  /**
   * The exporter's slug for one id segment: non-`[a-zA-Z0-9._-]` runs collapse to `-`, trimmed and
   * lowercased. Kotlin twin of `catalogSlug` in `catalog-image-path.mjs` (and
   * [ServeBundleHost.heroSlug]).
   */
  private fun catalogSlug(value: String): String =
    value.replace(Regex("[^a-zA-Z0-9._-]+"), "-").trim('-').lowercase()

  /**
   * Human label for a declared breakpoint: the catalog's own name ([ServePreview.size]), else what
   * [previewSizeVariantLabel] recognises in the id.
   */
  private fun sizeLabel(p: ServePreview): String? =
    p.size?.takeIf { it.isNotBlank() } ?: previewSizeVariantLabel(p.id)

  /**
   * Human label for a component [state] token for the state-switcher: "Default", or
   * `keyboard-focus` → "Keyboard focus".
   */
  private fun stateLabel(state: String?): String =
    if (state == null || state == "default") "Default"
    else state.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

  /**
   * A preview id with only its **state** segment (the one after `ideal`, see
   * [ServeCatalogStore.previewIdFor]) removed, grouping renders that differ only in state while
   * every other axis stays fixed. Props segments are kept, so a `content=icon+label` render keys
   * apart. Falls back to the whole id when there is no state.
   */
  private fun stateInvariantKey(id: String, state: String?): String {
    state ?: return id
    val parts = id.split("__")
    val idealIdx = parts.indexOf("ideal")
    val stateIdx =
      if (idealIdx in 0 until parts.lastIndex && parts[idealIdx + 1] == state) idealIdx + 1
      else parts.indexOfFirst { it == state }.takeIf { it >= 1 } ?: return id
    return parts.filterIndexed { i, _ -> i != stateIdx }.joinToString("__")
  }

  private fun stateInvariantKey(p: ServePreview): String = stateInvariantKey(p.id, p.state)

  /**
   * The switcher's grouping key: [stateInvariantKey] with the theme dropped too ([baseKey]),
   * leaving the theme to [themeLane].
   *
   * Catalogs don't always tag both modes (e.g. `@OverrideVariant` cells are `…__xs__dark` but bare
   * `…__xs` in light), so keying on the theme would split light renders from their default and hide
   * the state switcher.
   */
  private fun switcherStateKey(p: ServePreview): String =
    themeStrippedKey(stateInvariantKey(p), p.theme)

  /**
   * The props-family counterpart of [switcherStateKey], normalised the same way so an untagged
   * props sibling groups with a themed default.
   */
  private fun switcherPropsKey(p: ServePreview): String =
    themeStrippedKey(propsFamilyKey(p), p.theme)

  /**
   * [id] with its theme segment dropped ([baseKey]), but only when the render declares a theme —
   * otherwise a state named `dark` would be stripped as if it were a theme.
   */
  private fun themeStrippedKey(id: String, theme: String?): String =
    if (theme == null) id else baseKey(id)

  /**
   * The light/dark **lane** a render belongs to for switcher grouping: [ServePreview.theme], else
   * the id token ([cardTheme]), else the system's primary lane.
   *
   * An untagged render belongs in the catalog's default lane; a resolved string keeps the relation
   * symmetric. The id is read state-stripped so a state named `light`/`dark` can't pick the lane.
   */
  private fun themeLane(p: ServePreview, darkFirst: Boolean): String =
    p.theme ?: cardTheme(stateInvariantKey(p)) ?: if (darkFirst) "dark" else "light"

  /**
   * Canonical JSON for a props value: object keys sorted recursively, array order preserved, JSON
   * types kept distinct (`true` ≠ `"true"`).
   */
  private fun canonicalPropsJson(value: JsonElement): String =
    when (value) {
      is JsonObject ->
        value.entries
          .sortedBy { it.key }
          .joinToString(prefix = "{", postfix = "}") { (key, child) ->
            "${JsonPrimitive(key)}:${canonicalPropsJson(child)}"
          }
      is JsonArray ->
        value.joinToString(prefix = "[", postfix = "]") { child -> canonicalPropsJson(child) }
      else -> value.toString()
    }

  /** Human-readable form of a props value: unquote scalars, retain compact JSON for structures. */
  private fun propsValueLabel(value: JsonElement): String =
    if (value is JsonPrimitive) value.content else canonicalPropsJson(value)

  /** A stable signature for a preview's props axis (sorted `k=value` pairs); `""` for default. */
  private fun propsSignature(props: JsonObject?): String =
    props
      ?.entries
      ?.sortedBy { it.key }
      ?.joinToString(",") { "${it.key}=${canonicalPropsJson(it.value)}" } ?: ""

  /**
   * Human label for a props-variant axis for the variant-switcher: "Default", or per-axis phrasing
   * ("RTL", "Locale ar-XB", "Font 2.0×", "Icon+label"), else `key value`; axes join with " · ".
   */
  private fun propsLabel(props: JsonObject?): String {
    if (props.isNullOrEmpty()) return "Default"
    return props.entries
      .sortedBy { it.key }
      .joinToString(" · ") { (k, rawValue) ->
        val v = propsValueLabel(rawValue)
        when (k) {
          "direction" -> v.uppercase()
          "locale" -> "Locale $v"
          "fontScale" -> "Font ${v}×"
          "content" -> v.replaceFirstChar { it.uppercaseChar() }
          else -> "$k $v"
        }
      }
  }

  /**
   * The preview id with its trailing **props** segments removed, grouping a default render with its
   * props-axis variants. The exporter appends one segment per props entry, so dropping
   * [ServePreview.props]`.size` segments recovers the default's id.
   */
  private fun propsFamilyKey(p: ServePreview): String {
    val n = p.props?.size ?: 0
    if (n == 0) return p.id
    val parts = p.id.split("__")
    return if (parts.size > n) parts.dropLast(n).joinToString("__") else p.id
  }

  /**
   * The comparison-table card family for [p]: fold state, props, size and the light/dark pair,
   * mirroring [groupPreviews] without aliasing every render of the [componentKey]. Folding size
   * lets a deep link to an omitted breakpoint still select its row; a size with its own reference
   * keeps its own row (rows are keyed by [baseKey]).
   */
  private fun comparisonCardKey(p: ServePreview): String =
    baseKey(stateInvariantKey(sizeInvariantKey(propsFamilyKey(p), p.size, propsCount = 0), p.state))

  /**
   * How a comparison row names its variant (`Hovered`, `Xl square`, `RTL · Font 2.0×`), or empty
   * for the plain default. Needed because a component with a reference per state contributes
   * several rows.
   */
  private fun compareVariantLabel(p: ServePreview): String =
    listOf(stateLabel(p.state), propsLabel(p.props)).filter { it != "Default" }.joinToString(" · ")

  /**
   * One grid card: a component with baked `light` and/or `dark` variants the Light/Dark control
   * [swaps][GridCard.swappable] in place, and/or a theme-neutral render. [order] keeps catalog
   * order.
   */
  private class GridCard(val order: Int) {
    var light: ServePreview? = null
    var dark: ServePreview? = null
    var neutral: ServePreview? = null

    /** True when both themes are baked, so the card can swap between them (rather than filter). */
    val swappable: Boolean
      get() = light != null && dark != null

    /** The variant shown by default (server-side): light, else dark, else the neutral render. */
    val default: ServePreview
      get() = light ?: dark ?: neutral!!

    /**
     * The render the grid actually paints: the dark one on a dark-first system ([default] prefers
     * light). Anything describing the card must use this to agree with the pixels.
     */
    fun rendered(darkFirst: Boolean): ServePreview =
      if (darkFirst) (dark ?: light ?: neutral!!) else default
  }

  /**
   * Collapse per-theme previews into grid cards keyed by [baseKey], so `__light`/`__dark` variants
   * become one card the Light/Dark control swaps. Single-theme and theme-neutral renders stay lone
   * cards. Order follows first appearance.
   */
  private fun groupPreviews(previews: List<ServePreview>): List<GridCard> {
    val byKey = LinkedHashMap<String, GridCard>()
    previews.forEachIndexed { i, p ->
      val card = byKey.getOrPut(baseKey(p.id)) { GridCard(i) }
      when (cardTheme(p.id)) {
        "light" -> if (card.light == null) card.light = p
        "dark" -> if (card.dark == null) card.dark = p
        else -> if (card.neutral == null) card.neutral = p
      }
    }
    return byKey.values.sortedBy { it.order }
  }

  /** Fallback tab for section-bearing catalogs whose stray card carries no section of its own. */
  private const val OTHER_SECTION = "Other"

  /**
   * The **All** row's `data-tab`: every section's panel at once, and what a sectioned catalog lands
   * on. Reserved, so [buildSections] gives a section named "All" the slug `all-2`.
   */
  private const val ALL_TAB = "all"

  /** The catalog section whose cards are theme specimens. */
  private const val THEMES_SECTION = "Themes"

  /**
   * Whether [p] is a theme **specimen** — a card rendering a named theme as its subject — so a
   * `themeProvider` override would destroy what it documents.
   *
   * Either signal suffices: the catalog section ([THEMES_SECTION], the authored statement of what
   * the tab is), or [ServePreview.fixedTheme] from `@FixedTheme` / a `@ThemeCatalog` sheet for
   * specimens outside a Themes tab. Theme chips stay; the specimen keeps its baked pixels.
   */
  private fun isThemeSpecimen(p: ServePreview): Boolean =
    p.fixedTheme || p.section?.equals(THEMES_SECTION, ignoreCase = true) == true

  /**
   * One sub-heading group inside a section tab: [name] (null ⇒ ungrouped) and its cards. [slug] is
   * the group half of the `cp-group-<section>-<group>` anchor, unique within its section; empty for
   * a synthesized flat group ([synthesizeGroups]).
   */
  private class LandingGroup(val name: String?, var slug: String = "") {
    val cards = mutableListOf<GridCard>()
  }

  /**
   * One section (tab) of a tabbed landing: display [name], route-safe [slug] (`#cp-panel-<slug>`),
   * and ordered [groups]. [count] totals its cards for the badge.
   */
  private class LandingSection(val name: String, var slug: String) {
    val groups = mutableListOf<LandingGroup>()

    val count: Int
      get() = groups.sumOf { it.cards.size }
  }

  /** Route-safe slug for a section name (`"Screens · Scanner"` → `"screens-scanner"`). */
  private fun sectionSlug(name: String): String {
    val s =
      name
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-+"), "-")
    return s.ifEmpty { "section" }
  }

  /**
   * Bucket [cards] into ordered [LandingSection] tabs (by [ServePreview.section]) with ordered
   * [LandingGroup]s (by [ServePreview.group]).
   *
   * Everything is ordered by authored [ServePreview.catalogOrder] (min per section/group), since
   * [ServeBundleHost] sorts previews by id. Cards without a section go to a trailing **"Other"**
   * tab. Slugs are de-duplicated. Returns empty when no card has a section (flat grid).
   */
  private fun buildSections(cards: List<GridCard>): List<LandingSection> {
    if (cards.none { it.default.section != null }) return emptyList()
    fun ord(c: GridCard) = c.default.catalogOrder ?: Int.MAX_VALUE
    // section name -> (min order, group name -> cards), insertion-ordered as a stable fallback.
    class SectionAcc {
      var minOrder = Int.MAX_VALUE
      val groups = LinkedHashMap<String?, LandingGroup>()
    }
    val bySection = LinkedHashMap<String, SectionAcc>()
    for (card in cards) {
      val secName = card.default.section ?: OTHER_SECTION
      val acc = bySection.getOrPut(secName) { SectionAcc() }
      acc.minOrder = minOf(acc.minOrder, ord(card))
      acc.groups.getOrPut(card.default.group) { LandingGroup(card.default.group) }.cards.add(card)
    }
    // `all` belongs to the All row, so a section actually named "All" takes `all-2` — the same
    // de-duplication two same-slug section names already get.
    val usedSlugs = hashSetOf(ALL_TAB)
    return bySection.entries
      .sortedBy { it.value.minOrder }
      .map { (name, acc) ->
        var slug = sectionSlug(name)
        var n = 2
        while (!usedSlugs.add(slug)) {
          slug = "${sectionSlug(name)}-$n"
          n++
        }
        val section = LandingSection(name, slug)
        // Group slugs are scoped to their section, so a group name reused across sections gets
        // distinct anchors.
        val usedGroupSlugs = HashSet<String>()
        acc.groups.values
          .sortedBy { g -> g.cards.minOf { ord(it) } }
          .forEach { g ->
            var gslug = g.name?.let { sectionSlug(it) } ?: "ungrouped"
            var gn = 2
            while (!usedGroupSlugs.add(gslug)) {
              gslug = "${g.name?.let { sectionSlug(it) } ?: "ungrouped"}-$gn"
              gn++
            }
            val ordered = LandingGroup(g.name, gslug)
            ordered.cards.addAll(g.cards.sortedBy { ord(it) })
            section.groups.add(ordered)
          }
        section
      }
  }

  /**
   * The catalog's **navigation tree**: one row per section, each expanding to its named sub-groups,
   * beside the grid.
   *
   * Section rows keep the tab-bar DOM contract (`.cp-tab[data-tab]`, `#cp-tab-<slug>`,
   * `aria-controls`, `aria-selected`, `href="#cp-panel-<slug>"`) that [catalogFilterScript], the
   * remembered tab and `?tab=` key off. Groups are a `role="group"` list of `.cp-tree-group` links
   * to `#cp-group-<section>-<group>`.
   *
   * A section is expanded exactly when selected; during a search every section with a match
   * expands. `html.cp-js` gates collapsing, so no-JS clients see the full outline. Sections with
   * only unnamed groups are leaves. With more than one section the tree leads with an **All** row
   * ([ALL_TAB]), the landing default, under which every section is expanded.
   */
  private fun catalogTreeHtml(
    sections: List<LandingSection>,
    components: (GridCard) -> TreeComponent,
    /** The design-pages branch ([pagesBranchHtml]), appended after the sections. Empty ⇒ none. */
    pagesBranch: String = "",
  ): String = buildString {
    // With more than one section there is a whole catalog to browse, so the tree leads with it.
    // One section IS the whole catalog, and a row saying so twice is not a choice.
    val hasAll = sections.size > 1
    append("<nav class=\"cp-tree\" id=\"cp-tabs\" aria-label=\"Catalog sections\">\n")
    append("<ul class=\"cp-tree-list\" role=\"tree\" aria-label=\"Catalog sections\">\n")
    if (hasAll) {
      // `#cp-grid` contains every section panel and survives a filter collapsing them, so the All
      // row targets it.
      append("<li class=\"cp-tree-node\" role=\"none\">\n")
      append("  <a class=\"cp-tab\" role=\"treeitem\" id=\"cp-tab-$ALL_TAB\"")
      append(" href=\"#cp-grid\" data-tab=\"$ALL_TAB\"")
      append(" aria-controls=\"cp-grid\" aria-selected=\"true\">")
      append("All<span class=\"cp-tab-count\">${sections.sumOf { it.count }}</span></a>\n")
      append("</li>\n")
    }
    sections.forEachIndexed { i, sec ->
      // Nothing is selected under All, but each section is expanded to outline what is on screen.
      val selected = if (!hasAll && i == 0) "true" else "false"
      val expanded = if (hasAll) "true" else selected
      val named = sec.groups.filter { it.name != null }
      append("<li class=\"cp-tree-node\" role=\"none\">\n")
      val childrenId = "cp-tree-children-${sec.slug}"
      append("  <a class=\"cp-tab\" role=\"treeitem\" id=\"cp-tab-${sec.slug}\"")
      append(" href=\"#cp-panel-${sec.slug}\" data-tab=\"${sec.slug}\"")
      append(" aria-controls=\"cp-panel-${sec.slug}\" aria-selected=\"$selected\"")
      // `aria-owns` because the group can't nest inside the treeitem: the row must be an `<a>` for
      // no-JS, and the containing `<li>` is `role="none"`.
      if (named.isNotEmpty()) append(" aria-expanded=\"$expanded\" aria-owns=\"$childrenId\"")
      // No `tabindex` in served markup: the roving tab stop only makes sense once the script runs,
      // and baked `-1`s would strand no-JS keyboard users. `reflectTabs()` applies them on init.
      append(">")
      append(WebEscaping.htmlEscape(sec.name))
      append("<span class=\"cp-tab-count\">${sec.count}</span></a>\n")
      if (named.isNotEmpty()) {
        append("  <ul class=\"cp-tree-children\" id=\"$childrenId\" role=\"group\">\n")
        named.forEachIndexed { gi, g ->
          // The first group of the first section opens with the page.
          appendGroupRow(
            g,
            sec.slug,
            groupAnchorId(sec.slug, g.slug),
            i == 0 && gi == 0,
            components,
          )
        }
        append("  </ul>\n")
      }
      append("</li>\n")
    }
    append(pagesBranch)
    append("</ul>\n</nav>\n")
  }

  /**
   * The **outline** tree, for a catalog whose previews declare no `section` (most design systems,
   * grouped only via `@CatalogComponent(group = …)`). The groups are the top level, and rows are
   * pure jumps over the flat grid, so they carry no `data-tab`.
   */
  private fun catalogOutlineTreeHtml(
    groups: List<LandingGroup>,
    components: (GridCard) -> TreeComponent,
    /** The design-pages branch ([pagesBranchHtml]), appended after the groups. Empty ⇒ none. */
    pagesBranch: String = "",
  ): String = buildString {
    append("<nav class=\"cp-tree\" id=\"cp-tabs\" aria-label=\"Catalog contents\">\n")
    append("<ul class=\"cp-tree-list\" role=\"tree\" aria-label=\"Catalog contents\">\n")
    groups.forEachIndexed { i, g ->
      // The group row is the top-level node, so it carries `cp-tree-node` itself (the wrapper is
      // what the filter hides).
      appendGroupRow(g, null, flatGroupAnchorId(g.slug), i == 0, components, "cp-tree-node")
    }
    append(pagesBranch)
    append("</ul>\n</nav>\n")
  }

  /**
   * The tree's **Pages** branch: the design file's own pages, listed under one row leading to the
   * index.
   *
   * Unlike other branches: it carries no `data-group` (rows are real navigations, so the click
   * handler leaves them alone), and it is always open — `aria-expanded="true"` is never reflected,
   * since [catalogTreeScript] skips rows naming no target.
   */
  private fun pagesBranchHtml(pages: List<PageLink>, basePath: String, q: String): String {
    if (pages.isEmpty()) return ""
    return buildString {
      append("<li class=\"cp-tree-node cp-tree-pages\" role=\"none\">\n")
      append("  <a class=\"cp-tree-pages-row cp-tree-link\" role=\"treeitem\"")
      append(" href=\"${WebEscaping.htmlEscape("$basePath/pages$q")}\"")
      append(" aria-expanded=\"true\" aria-owns=\"cp-tree-pages-list\">")
      append("Pages<span class=\"cp-tree-count\">${pages.size}</span></a>\n")
      append("  <ul class=\"cp-tree-children cp-tree-components\" id=\"cp-tree-pages-list\"")
      append(" role=\"group\">\n")
      pages.forEach { page ->
        // The page id reaches the URL as one path segment, and the name is free text authored in
        // the design file — so one is encoded and the other escaped.
        val href = "$basePath/pages/${WebEscaping.urlEncodeSegment(page.id)}$q"
        append("    <li role=\"none\"><a class=\"cp-tree-page cp-tree-link\" role=\"treeitem\"")
        append(" href=\"${WebEscaping.htmlEscape(href)}\">")
        append("${WebEscaping.htmlEscape(page.name)}</a></li>\n")
      }
      append("  </ul>\n</li>\n")
    }
  }

  /**
   * The sidebar's two panes — **Components** and **Pages** — and the strip that switches them, so
   * design pages don't sit below the whole inventory.
   *
   * Emitted only when the catalog has design pages; otherwise the bare tree. Both panes share the
   * search box below the strip ([catalogFilterScript]).
   */
  private fun paneTabsHtml(componentCount: Int, pageCount: Int): String =
    """
    <div class="cp-panes" role="tablist" aria-label="Catalog navigation">
      <button type="button" class="cp-pane-tab" role="tab" id="cp-pane-tab-components"
        data-pane="components" aria-controls="cp-pane-components" aria-selected="true">
        Components<span class="cp-tree-count">$componentCount</span>
      </button>
      <button type="button" class="cp-pane-tab" role="tab" id="cp-pane-tab-pages"
        data-pane="pages" aria-controls="cp-pane-pages" aria-selected="false" tabindex="-1">
        Pages<span class="cp-tree-count">$pageCount</span>
      </button>
    </div>
    """
      .trimIndent()

  /**
   * The **Pages** pane: a flat list of the design file's pages. Each row carries `data-search` so
   * the shared filter matches what is decided here, not the rendered text.
   */
  private fun pagesPaneHtml(pages: List<PageLink>, basePath: String, q: String): String =
    buildString {
      append("<div class=\"cp-pane cp-pane-pages\" id=\"cp-pane-pages\" role=\"tabpanel\"")
      append(" aria-labelledby=\"cp-pane-tab-pages\" hidden>\n")
      append("<ul class=\"cp-page-list\">\n")
      pages.forEachIndexed { i, page ->
        // The page id reaches the URL as one path segment, and the name is free text authored in
        // the design file — so one is encoded and the other escaped.
        val pageUrl = "$basePath/pages/${WebEscaping.urlEncodeSegment(page.id)}$q"
        val href = WebEscaping.htmlEscape(pageUrl)
        val name = WebEscaping.htmlEscape(page.name)
        if (page.sections.isEmpty()) {
          append("  <li><a class=\"cp-tree-page cp-tree-link\" href=\"$href\"")
          append(" data-search=\"$name\">$name</a></li>\n")
        } else {
          // A page with sections is a branch: the row opens the sheet, the twisty its sections.
          // Only the first opens.
          val listId = "cp-page-sections-${WebEscaping.htmlEscape(page.id)}"
          val open = i == 0
          append("  <li class=\"cp-page-branch\">\n")
          append("    <a class=\"cp-tree-page cp-tree-link\" href=\"$href\"")
          append(" data-search=\"$name\" aria-expanded=\"${if (open) "true" else "false"}\"")
          append(" aria-controls=\"$listId\">$name")
          append("<span class=\"cp-tree-count\">${page.sections.size}</span></a>\n")
          append("    <ul class=\"cp-tree-children cp-page-sections\" id=\"$listId\">\n")
          page.sections.forEach { section ->
            val sectionName = WebEscaping.htmlEscape(section.name)
            // The row opens the sheet with no fragment, for now. A section is a COMPONENT_SET,
            // which the page view does not anchor, and inferring the next deeper node as a target
            // is wrong (see `PageNode.container`). Linking to the section needs a container anchor
            // first.
            val sectionHref = pageUrl
            append("      <li><a class=\"cp-tree-variant cp-tree-link\"")
            append(" href=\"${WebEscaping.htmlEscape(sectionHref)}\"")
            append(" data-search=\"$sectionName\">$sectionName</a></li>\n")
          }
          append("    </ul>\n  </li>\n")
        }
      }
      append("</ul>\n")
      append("<p class=\"cp-pane-empty\" id=\"cp-pages-empty\" hidden>No pages match.</p>\n")
      append("<a class=\"cp-pane-all\" href=\"${WebEscaping.htmlEscape("$basePath/pages$q")}\">")
      append("All pages</a>\n")
      append("</div>\n")
    }

  /** The anchor on a synthesized flat sub-group divider; no section owns it. */
  private fun flatGroupAnchorId(groupSlug: String) = "cp-group-$groupSlug"

  /**
   * A card's id line, split so it elides from the middle: the distinguishing part is the suffix
   * after the last `__`, so only the head shrinks. CSS has no middle ellipsis, hence two spans.
   * Both are always emitted because the light/dark swap refills them in place.
   */
  private fun cardIdHtml(id: String): String {
    val cut = id.lastIndexOf("__")
    val head = if (cut > 0) id.substring(0, cut) else id
    val tail = if (cut > 0) id.substring(cut) else ""
    return "<div class=\"cp-id cp-id-elide\">" +
      "<span class=\"cp-id-head\">${WebEscaping.htmlEscape(head)}</span>" +
      "<span class=\"cp-id-tail\">${WebEscaping.htmlEscape(tail)}</span></div>"
  }

  /** A component row and the primary-axis variants beneath it. */
  private class TreeComponent(
    val label: String,
    val anchorId: String,
    val variants: List<TreeVariant>,
    val href: String,
    /**
     * The row's prebaked thumbnail ([navThumbSrc]), or null for a tree listing renders (the
     * viewer's axes subtree) or a catalog without locally baked pixels.
     */
    val thumbSrc: String? = null,
  )

  /**
   * One group row plus its component rows (and their variants). A group, like a component, is open
   * exactly when it is the current one, keeping the tree from becoming a wall of rows.
   */
  private fun StringBuilder.appendGroupRow(
    group: LandingGroup,
    tabSlug: String?,
    anchor: String,
    open: Boolean,
    components: (GridCard) -> TreeComponent,
    /** Extra class for the row's `<li>` — the outline tree's groups are its top-level nodes. */
    liClass: String = "",
  ) {
    val childrenId = "cp-tree-of-$anchor"
    val tabAttr = tabSlug?.let { " data-tab=\"$it\"" } ?: ""
    val expanded = if (open) "true" else "false"
    val li =
      if (liClass.isEmpty()) "<li role=\"none\">" else "<li class=\"$liClass\" role=\"none\">"
    append("    $li<a class=\"cp-tree-group cp-tree-link\" role=\"treeitem\"")
    append(" href=\"#$anchor\"$tabAttr data-group=\"$anchor\"")
    if (group.cards.isNotEmpty()) {
      append(" aria-expanded=\"$expanded\" aria-owns=\"$childrenId\"")
    }
    append(">")
    append(WebEscaping.htmlEscape(group.name ?: "Ungrouped"))
    append("<span class=\"cp-tree-count\">${group.cards.size}</span></a>\n")
    if (group.cards.isEmpty()) {
      append("</li>\n")
      return
    }
    append("      <ul class=\"cp-tree-children cp-tree-components\" id=\"$childrenId\"")
    append(" role=\"group\">\n")
    group.cards.forEach { card ->
      val c = components(card)
      appendComponentRow(
        label = c.label,
        // On the landing every row is an in-page jump: the component row and the synthetic Default
        // row both target the card the grid is already showing, which is what `data-group` drives.
        href = "#${c.anchorId}",
        rowAttrs = "$tabAttr data-group=\"${c.anchorId}\"",
        defaultHref = "#${c.anchorId}",
        defaultRowAttrs = "$tabAttr data-group=\"${c.anchorId}\"",
        variants = c.variants,
        variantsId = "cp-tree-of-${c.anchorId}",
        thumbSrc = c.thumbSrc,
        indent = "        ",
      )
    }
    append("      </ul>\n    </li>\n")
  }

  /**
   * One component row plus its variant children, shared by the landing's tree and the viewer's
   * subtree so they cannot drift. On the landing rows are in-page jumps and start collapsed; in the
   * viewer they navigate, the list is open, and [currentHref] marks the current render.
   */
  private fun StringBuilder.appendComponentRow(
    label: String,
    href: String,
    variants: List<TreeVariant>,
    variantsId: String,
    defaultHref: String,
    rowAttrs: String = "",
    defaultRowAttrs: String = "",
    /** Collapsed by default; the viewer's subtree opens, having only one component to show. */
    collapsed: Boolean = true,
    /**
     * Whether to lead the children with a synthetic **Default** row pointing at [defaultHref]. The
     * landing needs it; in the viewer the component row is already the default render.
     */
    syntheticDefaultRow: Boolean = true,
    /** The row whose href matches is `aria-current="page"` — the render on screen. */
    currentHref: String? = null,
    /**
     * A small prebaked thumbnail (the `?thumb=<hash>` lane) left of [label]. Null keeps the row
     * text-only, as the viewer's axes subtree wants.
     */
    thumbSrc: String? = null,
    /**
     * Named groups of destinations (recorded interactions, samples that call it) appended after the
     * variant rows, each its own disclosure. Not counted in the component's render tally; each
     * directory carries its own.
     */
    directories: List<ComponentDirectory> = emptyList(),
    indent: String = "        ",
  ) {
    fun current(target: String) = if (target == currentHref) " aria-current=\"page\"" else ""
    // The component row can itself be current (in the viewer it is the default render). Nothing
    // double-marks: callers folding the default in drop it from [variants], and the landing passes
    // no [currentHref].
    append("$indent<li role=\"none\"><a class=\"cp-tree-component cp-tree-link\"")
    append(" role=\"treeitem\" href=\"${WebEscaping.htmlEscape(href)}\"$rowAttrs")
    val hasChildren = variants.isNotEmpty() || directories.isNotEmpty()
    if (hasChildren) {
      append(" aria-expanded=\"${!collapsed}\" aria-owns=\"$variantsId\"")
    }
    append(current(href))
    append(">")
    // `alt=""`: the name beside it already names the component, so the image is decorative.
    if (thumbSrc != null) {
      append("<img class=\"cp-tree-thumb\" loading=\"lazy\" alt=\"\"")
      append(" src=\"${WebEscaping.htmlEscape(thumbSrc)}\">")
    }
    append("<span class=\"cp-tree-label\">${WebEscaping.htmlEscape(label)}</span>")
    if (variants.isNotEmpty()) {
      // +1 for the default render either way: the landing lists it as the synthetic child row
      // below, the viewer folds it into this row.
      append("<span class=\"cp-tree-count\">${variants.size + 1}</span>")
    }
    append("</a>\n")
    if (hasChildren) {
      append("$indent  <ul class=\"cp-tree-children cp-tree-variants\" id=\"$variantsId\"")
      append(" role=\"group\">\n")
      // The default render leads, so the list reads as "the component, then how else it renders"
      // rather than starting at an exceptional state.
      if (syntheticDefaultRow) {
        append("$indent    <li role=\"none\"><a class=\"cp-tree-variant cp-tree-link\"")
        append(" role=\"treeitem\" href=\"${WebEscaping.htmlEscape(defaultHref)}\"$defaultRowAttrs")
        append("${current(defaultHref)}>Default</a></li>\n")
      }
      variants.forEach { v ->
        // A variant is folded out of the grid, so unlike the rows above it has nowhere on the
        // landing page to jump to — its href is a real navigation, left to the browser.
        append("$indent    <li role=\"none\"><a class=\"cp-tree-variant cp-tree-link\"")
        append(" role=\"treeitem\" href=\"${WebEscaping.htmlEscape(v.href)}\"${current(v.href)}>")
        append(WebEscaping.htmlEscape(v.label))
        append("</a></li>\n")
      }
      directories.forEachIndexed { index, dir ->
        // Open, like the subtree around it, so the rows aren't hidden behind two clicks.
        val groupId = "$variantsId-${dir.kind}-$index"
        append("$indent    <li role=\"none\" class=\"cp-tree-dir cp-tree-dir--${dir.kind}\">\n")
        append("$indent      <span class=\"cp-tree-dir-head\" role=\"treeitem\"")
        append(" aria-expanded=\"true\" aria-owns=\"$groupId\">")
        append("<span class=\"cp-tree-label\">${WebEscaping.htmlEscape(dir.label)}</span>")
        append("<span class=\"cp-tree-count\">${dir.rows.size}</span></span>\n")
        append("$indent      <ul class=\"cp-tree-children\" id=\"$groupId\" role=\"group\">\n")
        dir.rows.forEach { row ->
          val title =
            row.title
              ?.takeIf { it.isNotBlank() }
              ?.let { " title=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
          append("$indent        <li role=\"none\">")
          if (row.live) {
            append("<a class=\"cp-tree-variant cp-tree-link\" role=\"treeitem\"")
            append(" href=\"${WebEscaping.htmlEscape(row.href)}\"$title${current(row.href)}>")
            append("${WebEscaping.htmlEscape(row.label)}</a>")
          } else {
            // Not a link, and said so to a screen reader as well as to the eye: the destination is
            // registered but has no host yet, so following it now would land on a loading page.
            append("<span class=\"cp-tree-variant cp-tree-dead\" role=\"treeitem\"")
            append(" aria-disabled=\"true\"$title>${WebEscaping.htmlEscape(row.label)}</span>")
          }
          append("</li>\n")
        }
        append("$indent      </ul>\n")
        append("$indent    </li>\n")
      }
      append("$indent  </ul>\n")
    }
    append("$indent</li>\n")
  }

  /** The id of the sub-group divider a tree row jumps to — its section's slug, then its own. */
  private fun groupAnchorId(sectionSlug: String, groupSlug: String) =
    "cp-group-$sectionSlug-$groupSlug"

  /**
   * The id of the grid card a component row jumps to. Preview ids are catalog data, so characters
   * outside the HTML-id alphabet fold to `-`.
   */
  private fun cardAnchorId(previewId: String) =
    "cp-card-" +
      previewId
        .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '-' }
        .joinToString("")

  /**
   * One anchor per card, minted once for the whole page so grid and tree agree. Anchors must be
   * injective: [cardAnchorId] folds `/`, `?`, `#`, spaces etc. to `-`, so collisions take a numeric
   * suffix like group slugs do.
   */
  private fun mintCardAnchors(cards: List<GridCard>): Map<String, String> {
    val used = HashSet<String>()
    val out = LinkedHashMap<String, String>()
    cards.forEach { card ->
      val base = cardAnchorId(card.default.id)
      var candidate = base
      var n = 2
      while (!used.add(candidate)) {
        candidate = "$base-$n"
        n++
      }
      out[card.default.id] = candidate
    }
    return out
  }

  /** One **primary-axis** variant of a component: a distinct state or props render. */
  /** [axis] is `"state"` or `"props"` — which of the two primary axes this row varies. */
  private class TreeVariant(val label: String, val href: String, val axis: String = "state")

  /**
   * One destination inside a [ComponentDirectory]: a label and where it goes. [live] false means
   * registered but not yet hosted (cold box, reload); drawn disabled rather than dropped. [title]
   * is the tooltip (the catalog's caption).
   */
  data class ComponentDirectoryRow(
    val label: String,
    val href: String,
    val live: Boolean = true,
    val title: String? = null,
  )

  /**
   * A named group of destinations under a component: recorded interactions, samples that call it,
   * and later lanes. Not variant rows, because these are different kinds of artifact about the
   * component. [kind] is only a CSS hook.
   */
  data class ComponentDirectory(
    val kind: String,
    val label: String,
    val rows: List<ComponentDirectoryRow>,
  )

  /**
   * What kind of catalog a page belongs to, and so what shape the page takes. Declared by the
   * catalog (`display.role`, via [ServeBundleHost.catalogRole]), never inferred from its name.
   * Distinct from Catalog mode ([viewerPage]'s `componentBrowser`), which is the reader's choice.
   */
  enum class PageRole {
    /** A design system's own catalog: the page as it has always been. */
    CATALOG,
    /**
     * A catalog of call sites — samples that use a design system. A sample is code to copy, not a
     * rendition of a reference, so:
     * 1. Every comparison lane is dropped (reference, paired render, layer diff, parity issues,
     *    compare strip).
     * 2. The source stands beside the render rather than behind a chip.
     */
    SAMPLES;

    companion object {
      /**
       * The declared role, or [CATALOG] when none or unknown (a newer producer must degrade to the
       * ordinary page).
       */
      fun of(declared: String?): PageRole =
        when (declared?.trim()?.lowercase()) {
          "samples" -> SAMPLES
          else -> CATALOG
        }
    }
  }

  /**
   * The viewer opened on one recording, with the same `?mode=motion&motion=<id>` shape the Motion
   * index uses. [q] already carries its leading `?` when non-empty.
   */
  private fun motionHref(
    basePath: String,
    q: String,
    previewId: String,
    captureId: String,
  ): String {
    val separator = if (q.isEmpty()) "?" else "&"
    return "$basePath/p/${WebEscaping.urlEncodeSegment(previewId)}$q$separator" +
      "mode=motion&motion=${WebEscaping.urlEncodeSegment(captureId)}"
  }

  /**
   * The viewer's **component subtree**: the catalog's navigation tree filtered to the component on
   * screen — the component, every render under it, the current one marked. Returns "" when the
   * component has no second render.
   */
  /** The component's default render in [current]'s theme lane, or [current] when it has none. */
  private fun componentDefault(
    current: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
  ): ServePreview {
    val key = componentKey(current)
    val lane = themeLane(current, darkFirst)
    return all
      .filter {
        componentKey(it) == key &&
          themeLane(it, darkFirst) == lane &&
          !isNonDefaultState(it) &&
          !hasNonDefaultProps(it)
      }
      // Authored order, not list order: the host sorts by id, so `204dp` would otherwise root the
      // subtree before `92dp`.
      .minByOrNull { it.catalogOrder ?: Int.MAX_VALUE } ?: current
  }

  /**
   * Every render of [current]'s component reachable in one hop.
   *
   * The union of [primaryVariants] from the default (one axis at a time) and the rows relative to
   * [current] (its states with its props fixed, and vice versa), which lead. The second set is
   * needed when state × props is baked as a cross-product, or combinations like `pressed + RTL`
   * would be unreachable. Deduped by href.
   */
  private fun componentRenderRows(
    current: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
    href: (ServePreview) -> String,
  ): List<TreeVariant> {
    val lane = themeLane(current, darkFirst)
    // Collected as previews, not rows: whether a row can be labelled by one axis depends on the
    // whole set ([variantLabel]).
    val rows = LinkedHashMap<String, Pair<ServePreview, String>>()
    // A second-tier cell is never a row, but the render on screen is exempt so arriving on one
    // still shows where it is. `componentSubtreeHtml` re-adds it anyway; this only decides how it
    // is labelled.
    fun listed(p: ServePreview) = !p.secondary || p.id == current.id
    // This render's own state axis, holding its props fixed.
    val stateKey = switcherStateKey(current)
    val byState = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (!listed(p)) continue
      if (switcherStateKey(p) != stateKey || themeLane(p, darkFirst) != lane) continue
      byState.putIfAbsent(p.state ?: "default", p)
    }
    // …and its props axis, holding its state fixed.
    val propsKey = switcherPropsKey(current)
    val curState = current.state ?: "default"
    val byProps = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (!listed(p)) continue
      if (switcherPropsKey(p) != propsKey || themeLane(p, darkFirst) != lane) continue
      if ((p.state ?: "default") != curState) continue
      byProps.putIfAbsent(propsSignature(p.props), p)
    }
    // …and its size axis, holding state and props fixed — [sizeInvariantKey] strips only the size,
    // so a reader on `no-buttons` is offered the other breakpoints OF `no-buttons`.
    val sizeKey = switcherSizeKey(current)
    val bySize = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      // Bound to a local: `ServePreview` lives in `:render-host`, and Kotlin won't smart-cast
      // another module's public property.
      val size = p.size ?: continue
      if (!listed(p)) continue
      if (switcherSizeKey(p) != sizeKey || themeLane(p, darkFirst) != lane) continue
      bySize.putIfAbsent(size, p)
    }
    if (byState.size > 1) {
      byState.entries
        .sortedBy { if (it.key == "default") 0 else 1 }
        .forEach { (_, p) -> rows.putIfAbsent(href(p), p to "state") }
    }
    if (byProps.size > 1) {
      byProps.entries
        .sortedBy { if (it.key == "") 0 else 1 }
        .forEach { (_, p) -> rows.putIfAbsent(href(p), p to "props") }
    }
    // Sizes last, in the catalog's declared `breakpoints` order (first-seen), not re-sorted.
    if (bySize.size > 1) {
      bySize.forEach { (_, p) -> rows.putIfAbsent(href(p), p to "size") }
    }
    // Then the component's canonical set, for everything the two axes above did not already reach.
    primaryVariantPreviews(componentDefault(current, all, darkFirst), all, darkFirst).forEach {
      (p, axis) ->
      rows.putIfAbsent(href(p), p to axis)
    }
    // With both axes in play every row names both coordinates; otherwise two different rows would
    // both read "Default".
    val crossProduct = byState.size > 1 && byProps.size > 1
    return rows.values.map { (p, axis) ->
      TreeVariant(variantLabel(p, axis, crossProduct), href(p), axis)
    }
  }

  /**
   * The component's recordings, as a directory over the renders the tree lists — [default] plus
   * [variants], including the render on screen — not over every render the catalog publishes (which
   * can be a 64-row matrix).
   *
   * Naming: a capture title is only unique within one render's set ([MotionCaptureLabels]).
   * Captures from a single render use their own titles; captures across renders are named by the
   * render, with the title appended where a render contributes more than one.
   */
  private fun motionDirectory(
    preview: ServePreview,
    default: ServePreview,
    variants: List<TreeVariant>,
    byHref: Map<String, ServePreview>,
    href: (ServePreview) -> String,
    basePath: String,
    q: String,
  ): ComponentDirectory? {
    class Take(val renderLabel: String, val render: ServePreview, val capture: ServeMotion)
    // Resolve every render through [byHref], the default included: a caller may pass an enriched
    // record (pinned revision, fixture `copy()`) that holds the captures the sibling list lacks.
    val takes =
      (listOf(previewDisplayName(default) to (byHref[href(default)] ?: default)) +
          variants.mapNotNull { row -> byHref[row.href]?.let { row.label to it } })
        .distinctBy { (_, render) -> render.id }
        .flatMap { (label, render) -> render.motion.map { Take(label, render, it) } }
    if (takes.isEmpty()) return null
    val spansRenders = takes.distinctBy { it.render.id }.size > 1
    val capturesPerRender = takes.groupingBy { it.render.id }.eachCount()
    val titles =
      takes
        .groupBy { it.render.id }
        .mapValues { (_, group) -> MotionCaptureLabels.of(group.map { it.capture }) }
    val indexInRender = HashMap<String, Int>()
    val rows = takes.map { take ->
      val index = indexInRender.merge(take.render.id, 1, Int::plus)!! - 1
      val title = titles.getValue(take.render.id)[index]
      val label =
        when {
          !spansRenders -> title.title
          capturesPerRender.getValue(take.render.id) > 1 -> "${take.renderLabel} · ${title.title}"
          else -> take.renderLabel
        }
      ComponentDirectoryRow(
        label = label,
        href = motionHref(basePath, q, take.render.id, take.capture.id),
        // The caption in full, which the label may have had to shorten or drop entirely.
        title = title.detail.takeIf { it != label },
      )
    }
    return ComponentDirectory("motion", "Motion", rows)
  }

  private fun componentSubtreeHtml(
    preview: ServePreview,
    siblings: List<ServePreview>,
    basePath: String,
    q: String,
    darkFirst: Boolean,
    /**
     * The component's named groups (catalogs about it), resolved by the handler since they need the
     * session registry. The recordings directory is built here over the same render rows
     * ([motionDirectory]).
     */
    directories: List<ComponentDirectory> = emptyList(),
    /** Whether to draw the recordings directory at all — false under a pin and in Catalog mode. */
    includeMotion: Boolean = true,
  ): String {
    fun href(p: ServePreview) = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
    // The subtree hangs off the component's default render whichever render is on screen, held to
    // the current theme lane.
    val default = componentDefault(preview, siblings, darkFirst)
    val rows = componentRenderRows(preview, siblings, darkFirst, ::href)
    // The render on screen is always a row, even when its axis lives only in its id (no `props`
    // metadata), so the tree always contains the page it is drawn on.
    val withCurrent =
      if (rows.any { it.href == href(preview) } || preview.id == default.id) rows
      else rows + TreeVariant(previewDisplayName(preview), href(preview), "props")
    // The default render is the component row itself, not a duplicate "Default" child.
    val variants = withCurrent.filterNot { it.href == href(default) }
    // The preview on screen leads, so an enriched record (pinned revision, fixture `copy()`) has
    // its own captures read.
    val byHref = (listOf(preview) + siblings).distinctBy { it.id }.associateBy { href(it) }
    val allDirectories =
      listOfNotNull(
        if (!includeMotion) null
        else motionDirectory(preview, default, variants, byHref, ::href, basePath, q)
      ) + directories
    // One render and no directories: nothing to show. With a directory the subtree is worth drawing
    // even for a single render.
    if (variants.isEmpty() && allDirectories.isEmpty()) return ""
    return buildString {
      append("<nav class=\"cp-tree cp-axes-tree\" aria-label=\"Component renders\">\n")
      append("  <ul class=\"cp-tree-list\" role=\"tree\">\n")
      appendComponentRow(
        label = previewDisplayName(default),
        href = href(default),
        variants = variants,
        variantsId = "cp-axes-tree-variants",
        defaultHref = href(default),
        // One component, already chosen — a collapsed subtree would be a disclosure inside a
        // disclosure, and the outer one is the control that decides whether any of this shows.
        collapsed = false,
        syntheticDefaultRow = false,
        currentHref = href(preview),
        directories = allDirectories,
        indent = "    ",
      )
      append("  </ul>\n</nav>")
    }
      .trimEnd()
  }

  /**
   * A component's primary-axis variants — the renders the grid folds out, listed so the tree can
   * offer them.
   *
   * Primary axes are `state` and `props` (a different thing to look at). Theme, breakpoint,
   * fontScale and locale are secondary (a different rendering) and stay out. A state cell marked
   * [ServePreview.secondary] (`@OverrideVariant(secondary = true)`) is dropped too; it is still
   * served and addressable. The viewer's subtree ([componentSubtreeHtml]) uses this same function.
   */
  private fun primaryVariants(
    default: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
    href: (ServePreview) -> String,
  ): List<TreeVariant> =
    primaryVariantRows(default, all, darkFirst).map { (p, axis) ->
      TreeVariant(variantLabel(p, axis, crossProduct = false), href(p), axis)
    }

  private fun primaryVariantRows(
    default: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
  ): List<Pair<ServePreview, String>> {
    val lane = themeLane(default, darkFirst)
    val defaultState = default.state ?: "default"
    val rows = mutableListOf<Pair<ServePreview, String>>()
    // States first: the axis a component varies on most, and the one a reviewer looks for.
    val stateKey = switcherStateKey(default)
    val seenStates = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (p.secondary) continue
      if (switcherStateKey(p) != stateKey) continue
      if (themeLane(p, darkFirst) != lane) continue
      if (!hasNonDefaultProps(p) && isNonDefaultState(p)) {
        seenStates.putIfAbsent(p.state!!, p)
      }
    }
    seenStates.forEach { (_, p) -> rows.add(p to "state") }
    // Then the props axis, held at the component's default state so a row never crosses two axes.
    val propsKey = switcherPropsKey(default)
    val seenProps = LinkedHashMap<String, ServePreview>()
    for (p in all) {
      if (p.secondary) continue
      if (switcherPropsKey(p) != propsKey) continue
      if (themeLane(p, darkFirst) != lane) continue
      if ((p.state ?: "default") != defaultState) continue
      if (hasNonDefaultProps(p)) seenProps.putIfAbsent(propsSignature(p.props), p)
    }
    seenProps.forEach { (_, p) -> rows.add(p to "props") }
    return rows
  }

  /**
   * A row's label: normally just the axis the row moves along. When both axes are in play, name
   * both coordinates, or `default + RTL` and `pressed + default` would both read "Default".
   */
  private fun variantLabel(p: ServePreview, axis: String, crossProduct: Boolean): String =
    // A size row always names its breakpoint; it moves along neither axis [crossProduct]
    // disambiguates.
    if (axis == "size") sizeLabel(p) ?: stateLabel(p.state)
    else if (!crossProduct) if (axis == "state") stateLabel(p.state) else propsLabel(p.props)
    else "${stateLabel(p.state)} · ${propsLabel(p.props)}"

  /** [primaryVariants] as previews paired with the axis each varies, before they are labelled. */
  private fun primaryVariantPreviews(
    default: ServePreview,
    all: List<ServePreview>,
    darkFirst: Boolean,
  ): List<Pair<ServePreview, String>> = primaryVariantRows(default, all, darkFirst)

  /** Prettier display names for a few component families whose bare title-case reads badly. */
  private val FAMILY_DISPLAY_NAMES =
    mapOf(
      "fab" to "FAB",
      "textfield" to "Text fields",
      "radiobutton" to "Radio buttons",
      "segmentedbutton" to "Segmented buttons",
    )

  /**
   * The component **family** of a card: the first token of its [componentKey] (`button-filled` →
   * `button`). Only a fallback grouping for catalogs with no [sections][ServePreview.section].
   */
  private fun cardFamily(card: GridCard): String =
    componentKey(card.default).substringBefore("__").substringBefore('-').ifBlank {
      componentKey(card.default)
    }

  /** A human family heading: a curated name, else the token title-cased (`switch` → `Switch`). */
  private fun familyDisplayName(family: String): String =
    FAMILY_DISPLAY_NAMES[family] ?: family.replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

  /**
   * The name the drawer's tree gives a preview, exported so handler-built directory rows match the
   * variant rows (`ServePreview.label` is the route id for generated catalogs).
   */
  fun rowDisplayName(preview: ServePreview): String = previewDisplayName(preview)

  /**
   * Prefer catalog-authored labels; turn generated ids into readable component names as fallback.
   */
  private fun previewDisplayName(preview: ServePreview): String {
    preview.componentId
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return humanizeComponentId(it)
      }
    if (preview.label.isNotBlank() && preview.label != preview.id) return preview.label
    return componentKey(preview).substringBefore("__").replace('-', ' ').replaceFirstChar {
      it.uppercaseChar()
    }
  }

  /** Splits catalog identifiers without changing their stable route-safe preview ids. */
  private fun humanizeComponentId(componentId: String): String =
    componentId
      .replace(Regex("[/_-]+"), " ")
      .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
      .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), " ")
      .trim()
      .replace(Regex("\\s+"), " ")

  /** A compact human label for the size/breakpoint token carried in a flattened catalog id. */
  private fun previewSizeVariantLabel(id: String): String? =
    id.split("__").asReversed().firstNotNullOfOrNull { token ->
      when (token.lowercase()) {
        "compact" -> "Compact"
        "expanded" -> "Expanded"
        "smallround" -> "Small Round"
        "largeround" -> "Large Round"
        "xlround" -> "XL Round"
        else -> null
      }
    }

  /**
   * A synthesized sub-grouping for a section-less catalog: bucket [cards] by [cardFamily] so the
   * flat grid gains labelled dividers. Catalogs with sections use [buildSections] instead.
   *
   * Returns null (plain flat grid) unless there are at least two families and one has more than one
   * card. Families keep first-seen order; cards keep their order.
   */
  private fun synthesizeGroups(cards: List<GridCard>): List<LandingGroup>? {
    if (cards.size < 2) return null
    val byFamily = LinkedHashMap<String, LandingGroup>()
    for (card in cards) {
      byFamily
        .getOrPut(cardFamily(card)) { LandingGroup(familyDisplayName(cardFamily(card))) }
        .cards
        .add(card)
    }
    if (byFamily.size < 2 || byFamily.values.none { it.cards.size > 1 }) return null
    return byFamily.values.toList()
  }

  /**
   * The sticky **Theme** control for the catalog header: every theme the catalog configures, not
   * just light/dark.
   *
   * - the **baked** light/dark pair ([hasBaked]): an instant client-side swap
   *   (`data-theme-choice="light"` / `"dark"`);
   * - each **declared** `@ThemeCatalog` / `@WearThemeCatalog` theme ([declared]):
   *   `data-theme-choice="theme:<providerFqn>"`, re-pointing daemon-twinned thumbnails at
   *   `/render/<id>.png?themeProvider=<fqn>`.
   *
   * Without a baked pair a leading `default` chip is added. Persists to the catalog-scoped per-tab
   * key shared with the viewer. No-JS clients see the baked renders.
   */
  private fun themePickerHtml(hasBaked: Boolean, declared: List<ServeTheme>): String {
    val builtIns =
      if (hasBaked) listOf("light" to "Light", "dark" to "Dark") else listOf("default" to "Default")
    val chips = themeChipsHtml(builtIns, declared, indent = "            ")
    // The same `.cp-theme-menu` + `.cp-theme-menu-panel` control `viewerPage` emits, with chips
    // from [themeChipsHtml], so both pages offer one affordance; `<cp-catalog-toolbar>` closes it
    // on a pick. The pill's label is seeded with the leading built-in and then mirrored from the
    // pressed chip, since the remembered choice changes without a page load.
    val seed = builtIns.first().second
    return """
    <div class="cp-toolbar">
      <details class="cp-theme-menu cp-catalog-theme">
        <summary class="cp-drawer-toggle cp-axis-toggle" aria-controls="cp-catalog-theme-bar">
          <span class="cp-toggle-label">Theme</span>
          <span class="cp-toggle-value" id="cp-catalog-theme-value">$seed</span>
          <span class="cp-theme-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-theme-menu-panel">
          <span class="cp-theme cp-theme-bar" id="cp-catalog-theme-bar" role="group" aria-label="Preview theme">
            $chips
          </span>
        </div>
      </details>
    </div>
    """
      .trimIndent()
  }

  /**
   * The theme chips shared by the landing picker ([themePickerHtml]) and the viewer bar
   * ([viewerThemePickerHtml]).
   *
   * [builtIns] are `(choice value, label)` pairs offered first (the landing's baked pair or
   * `default`; the viewer's Light/Dark or Dark alone on a dark-first system). [declared] follows as
   * one `theme:<providerFqn>` chip each, qualified with its group when the bare name would collide.
   */
  private fun themeChipsHtml(
    builtIns: List<Pair<String, String>>,
    declared: List<ServeTheme>,
    /** Indentation for every chip after the first, so the emitted block reads as written HTML. */
    indent: String = "        ",
    /**
     * Built-in choice value → the viewer page of the sticker published in that mode. Such a chip is
     * navigation: `viewer.ts` follows the link instead of asking the daemon for a `uiMode`
     * override. Empty on the landing and for pages with no published twin.
     */
    twinHrefs: Map<String, String> = emptyMap(),
  ): String {
    val builtInLabels = builtIns.map { it.second.lowercase() }.toSet() + builtIns.map { it.first }
    val declaredNameCounts = declared.groupingBy { it.name.lowercase() }.eachCount()
    return buildString {
      builtIns.forEachIndexed { index, (value, label) ->
        if (index > 0) append("\n$indent")
        val twinHref =
          twinHrefs[value]?.let { " data-theme-href=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
        append("<button type=\"button\" class=\"cp-theme-btn\" data-theme-choice=\"$value\"")
        append("$twinHref>$label</button>")
      }
      declared.forEach { t ->
        val qualified =
          t.name.lowercase() in builtInLabels || declaredNameCounts.getValue(t.name.lowercase()) > 1
        val displayName =
          if (qualified) "${t.group?.takeIf { it.isNotBlank() } ?: "Custom"} · ${t.name}"
          else t.name
        val label = WebEscaping.htmlEscape(displayName)
        val title =
          t.group
            ?.takeIf { !qualified }
            ?.let { " title=\"${WebEscaping.htmlEscape(it)} · $label\"" } ?: ""
        append("\n$indent<button type=\"button\" class=\"cp-theme-btn\"")
        val modeAttr = t.mode?.let { " data-theme-mode=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
        append(
          " data-theme-choice=\"theme:${WebEscaping.htmlEscape(t.providerFqn)}\"$modeAttr$title>"
        )
        append("$label</button>")
      }
    }
  }

  /**
   * The **Transparent** toggle between the solid stage and a checkerboard showing a sticker's real
   * alpha: one `aria-pressed` button, identical on landing and viewer.
   *
   * It drives the `cp-bg-transparent` `<html>` class, restored pre-paint by [document]. The button
   * is rendered by the `<cp-bg-toggle>` element (`cli/serve-web/src/components/BgToggle.ts`);
   * `serve.css` gives it `display: contents` so the button is the toolbar's flex item.
   */
  private fun bgPickerHtml(title: String): String =
    "<cp-bg-toggle label=\"${WebEscaping.htmlEscape(title)}\"></cp-bg-toggle>"

  /**
   * The landing grid's search box, filtering cards by label or id. [catalogFilterScript] does the
   * hiding, so no-JS clients see the full grid.
   */
  private fun searchBoxHtml(usesFilter: Boolean = false): String {
    // Readout for the `uses:` operator, hidden until the operator is typed. `role="status"` because
    // results arrive asynchronously. Needed because the landing has no count line, and an
    // unindexable catalog must not look like zero matches.
    val status =
      if (!usesFilter) ""
      else "\n      <p id=\"cp-uses-status\" class=\"cp-uses-status\" role=\"status\" hidden></p>"
    return """
    <div class="cp-searchbar">
      <input id="cp-search" class="cp-search" type="search" placeholder="Filter previews…"
        autocomplete="off" spellcheck="false" aria-label="Filter previews" aria-controls="cp-grid">$status
    </div>
    """
      .trimIndent()
  }

  /**
   * Landing-grid controls: the search box (label + id, case-insensitive) and, for catalogs with
   * light/dark pairs, the sticky Light/Dark toggle, which swaps each swappable card's render, link,
   * id, label and stage in place. Theme state persists to a catalog-scoped `sessionStorage` key
   * shared with the viewer; search text is ephemeral. No-JS clients see the baked renders.
   *
   * With [hasTabs] the script also drives section tabs: a tab shows only its panel, a search spans
   * every tab, and emptied groups/sections collapse. Tab code is spliced in inline and empty for a
   * flat catalog.
   */
  private fun catalogFilterScript(
    hasThemes: Boolean,
    hasTabs: Boolean,
    hasGroups: Boolean,
    /** Whether a navigation tree is rendered at all — sectioned catalogs AND outline ones. */
    hasTree: Boolean,
    themeStorageKey: String,
    tabStorageKey: String,
    /**
     * Per-card render URLs to re-request under a declared theme, in grid order, as a server-emitted
     * JS array literal (`""` for cards the session can't re-render). Emitted rather than read from
     * the DOM so no `<img src>` originates as DOM text (CodeQL `js/xss-through-dom`). Empty ⇒ no
     * declared themes and none of that machinery.
     */
    themeBaseJs: String = "",
    themeLeaseUrl: String = "",
    /** Presence `POST` URL ([presenceScript]). Empty omits the heartbeat. */
    presenceUrl: String = "",
    /** Whether the sidebar carries the Components/Pages strip ([paneTabsHtml]). */
    hasPanes: Boolean = false,
    /** Whether the tree leads with the **All** row ([ALL_TAB]); false omits every mention of it. */
    hasAllTab: Boolean = false,
    /** Endpoint for the Dev-mode `uses:` operator ([usesFilterScript]). Empty omits it entirely. */
    usesUrl: String = "",
  ): String {
    // Declared before anything that branches on it — the tab predicate and the pane split below
    // both ask whether the operator is wired.
    val usesFilter = usesUrl.isNotEmpty()
    val hasDeclaredThemes = themeBaseJs.isNotEmpty()
    val themeLeaseUrlJs = WebEscaping.jsString(themeLeaseUrl)
    // Spliced one level in, so a page with no presence URL emits the script byte-for-byte as
    // before.
    val presenceWiring =
      presenceScript(presenceUrl).let { script ->
        if (script.isEmpty()) ""
        else script.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n      $it" }
      }
    // The stored choice: `light` / `dark` (baked swap), `default`, or `theme:<providerFqn>`
    // (re-points daemon-twinned thumbnails at a `?themeProvider=` render).
    val themeInit =
      if (hasThemes)
        """
        var stored = null;
        try { stored = sessionStorage.getItem("$themeStorageKey"); } catch (e) {}
        var themeBtns = document.querySelectorAll(".cp-theme-btn");
        function chipOffered(t) {
          var offered = false;
          themeBtns.forEach(function (b) { if (b.getAttribute("data-theme-choice") === t) offered = true; });
          return offered;
        }
        // The GRID always opens on published pixels. A stored app-declared theme
        // (`theme:<providerFqn>`) is deliberately NOT replayed here: restoring it would re-point
        // every card at a `?themeProvider=` render and put the whole grid through the daemon on
        // what is meant to be a default page view — the single most expensive thing an idle box
        // can be made to do, and it happened on every return visit. Only the baked chips, whose
        // pixels are already published, are restored. Stickiness for app-declared themes belongs
        // to the individual preview, which reads this same key for its own Theme select.
        function validTheme(t) {
          if (!t) return false;
          return t === "light" || t === "dark" || t === "default";
        }
        var theme = validTheme(stored) ? stored
          : (window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light");
        // A chip is only offered when the page rendered it, so a remembered choice this catalog no
        // longer configures (a theme the app dropped) falls back to the first chip.
        var known = false;
        themeBtns.forEach(function (b) { if (b.getAttribute("data-theme-choice") === theme) known = true; });
        if (!known && themeBtns.length) theme = themeBtns[0].getAttribute("data-theme-choice");
        // The URL wins over both. `?theme=` is on the address bar only because someone picked that
        // chip (or was handed the link), which makes it the one case where replaying an app-declared
        // theme IS what was asked for — the cost the stored-value rule above avoids is the cost of
        // an *unrequested* grid re-render, not of honouring an explicit link. An unknown value (a
        // theme this catalog no longer publishes) is ignored, exactly like a stale stored one.
        var urlTheme = urlParam("theme");
        if (urlTheme && chipOffered(urlTheme)) theme = urlTheme;
        // What the page falls back to when Back lands on an entry with no `?theme=` — the choice
        // this load resolved to, never the remembered value a later click overwrote.
        var initialTheme = theme;
        var appliedTheme = null;
        """
          .trimIndent()
      else ""
    // Declared-theme renders run serially unless the server grants a short-lived claim on the
    // catalog's shared burst allocation, so many tabs cannot multiply the burst. Each worker
    // advances when its image settles; failures get bounded delayed retries with cache-busting
    // URLs. Baked swaps never queue. A new theme choice bumps themeGen, abandoning workers and
    // releasing the lease.
    val themeRenderInit =
      if (hasDeclaredThemes)
        """
        var themeBase = $themeBaseJs;
        var themeGen = 0;
        var themeLeaseUrl = $themeLeaseUrlJs;
        // Every claim this page currently holds. A list, not one slot: the visible batch and each
        // deferred batch take their own claim on the catalog's allocation, and abandoning a
        // generation (or leaving the page) has to hand back ALL of them — a single slot silently
        // dropped every claim but the last, leaving the catalog's burst width tied up until its
        // TTL ran out.
        var themeLeases = [];
        var themeRenderRetries = 3;
        function releaseThemeLease(lease, beacon) {
          if (!lease || !themeLeaseUrl) return;
          var held = themeLeases.indexOf(lease);
          if (held !== -1) themeLeases.splice(held, 1);
          var queryAt = themeLeaseUrl.indexOf("?");
          var url = queryAt === -1
            ? themeLeaseUrl + "/release"
            : themeLeaseUrl.slice(0, queryAt) + "/release" + themeLeaseUrl.slice(queryAt);
          url += (url.indexOf("?") === -1 ? "?" : "&") + "lease=" + encodeURIComponent(lease);
          if (beacon && navigator.sendBeacon) navigator.sendBeacon(url, "");
          else fetch(url, { method: "POST", credentials: "same-origin", keepalive: true }).catch(function () {});
        }
        function acquireThemeLease(gen, callback) {
          if (!themeLeaseUrl) { callback(null, 1); return; }
          fetch(themeLeaseUrl, { method: "POST", credentials: "same-origin" })
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (grant) {
              var lease = grant && typeof grant.lease === "string" ? grant.lease : null;
              var concurrency = grant && Number.isFinite(grant.concurrency)
                ? Math.max(1, Math.min(5, grant.concurrency)) : 1;
              if (gen !== themeGen) { releaseThemeLease(lease, false); return; }
              if (lease) themeLeases.push(lease);
              callback(lease, concurrency);
            })
            .catch(function () { if (gen === themeGen) callback(null, 1); });
        }
        function releaseAllThemeLeases(beacon) {
          themeLeases.slice().forEach(function (lease) { releaseThemeLease(lease, beacon); });
        }
        // Point a batch's jobs at the claim that admits them. Any earlier token is dropped first:
        // a job re-queued under a fresh claim must not carry two.
        function stampThemeLease(jobs, lease) {
          jobs.forEach(function (job) {
            job.baseSrc = job.baseSrc.replace(/&_themeLease=[^&]*/, "") +
              "&_themeLease=" + encodeURIComponent(lease);
            job.src = job.baseSrc;
            job.retries = 0;
          });
        }
        function finishThemeJob(batch) {
          batch.remaining--;
          if (batch.remaining === 0) releaseThemeLease(batch.lease, false);
        }
        function clearThemeError(card) {
          card.classList.remove("cp-theme-render-error");
          var error = card.querySelector(".cp-theme-error");
          if (error) error.remove();
        }
        function showThemeError(card, terminal) {
          clearThemeError(card);
          card.classList.add("cp-theme-render-error");
          var error = document.createElement("span");
          error.className = "cp-theme-error";
          error.setAttribute("role", "status");
          // Two different facts, and conflating them is misleading: a retryable failure really may
          // resolve on the next attempt, while a terminal one (the server latched this preview as
          // unrenderable) never will. Saying "unavailable" for both left a permanently broken card
          // looking like it was still loading.
          error.textContent = terminal
            ? "This preview can't render live"
            : "Theme preview unavailable";
          var wrap = card.querySelector(".cp-imgwrap");
          if (wrap) wrap.appendChild(error);
        }
        function runThemeWorker(queue, gen, batch) {
          if (gen !== themeGen) return;
          var job = queue.shift();
          if (!job) return;
          // A deferred card is not loading yet: it deliberately has no request until its tab or
          // viewport reaches it. Mark it busy only when a worker actually starts the fetch. Doing
          // this while every job was being classified left hidden-tab cards aria-busy forever,
          // making a completed cold daemon burst look as though it had never woken the page.
          clearThemeError(job.card);
          job.card.classList.add("cp-reloading");
          job.card.setAttribute("aria-busy", "true");
          var img = job.img;
          var settled = false;
          function finish(ok, terminal) {
            if (settled || gen !== themeGen) return;
            settled = true;
            if (!ok && !terminal && job.retries < themeRenderRetries) {
              // Re-request rather than re-assign the identical (failed, uncached) URL. Exponential
              // backoff gives a busy daemon time to finish without stalling the other worker.
              job.retries++;
              job.src = job.baseSrc + "&_retry=" + job.retries;
              setTimeout(function () {
                if (gen !== themeGen) return;
                queue.push(job);
                runThemeWorker(queue, gen, batch);
              }, 1000 * Math.pow(2, job.retries));
              return;
            }
            job.card.classList.remove("cp-reloading");
            job.card.removeAttribute("aria-busy");
            if (!ok) showThemeError(job.card, terminal);
            finishThemeJob(batch);
            runThemeWorker(queue, gen, batch);
          }
          // Fetch the bytes FIRST and only then put them on the card.
          //
          // Assigning `src` on the live <img> dropped the pixels it was showing the instant the
          // request started, so the visitor watched the old theme's render vanish and sat looking
          // at a broken-image glyph under the spinner for the whole ~1s daemon round trip. Holding
          // the previous render until the new one is in hand means the card only ever shows real
          // pixels: the old theme's, then the new theme's, swapped in a single paint.
          //
          // A detached `new Image()` preload would do the same job, except a themed render is
          // `no-store` (it carries overrides), so handing its URL to the visible <img> afterwards
          // is not reliably a cache hit and can cost a second round trip. Fetching to a blob is one
          // request by construction.
          fetch(job.src, { credentials: "same-origin" })
            .then(function (response) {
              // 409 is the server saying it has permanently given up on this preview, not that it
              // is busy. Retrying it three times with backoff only occupies a worker that the rest
              // of the grid needs, so retire the job on the spot.
              if (response.status === 409) {
                var terminal = new Error("render 409");
                terminal.cpTerminal = true;
                throw terminal;
              }
              if (!response.ok) throw new Error("render " + response.status);
              return response.blob();
            })
            .then(function (blob) {
              if (gen !== themeGen) return;
              var url = URL.createObjectURL(blob);
              // Release the blob this card was holding, if any. Without this every theme switch
              // would strand one object URL per card for the life of the page.
              var previous = img.getAttribute("data-cp-blob");
              img.src = url;
              img.setAttribute("data-cp-blob", url);
              if (previous) URL.revokeObjectURL(previous);
              finish(true);
            })
            .catch(function (e) { finish(false, !!(e && e.cpTerminal)); });
        }
        function runThemeQueue(queue, gen, lease, concurrency) {
          var batch = { lease: lease, remaining: queue.length };
          if (!batch.remaining) { releaseThemeLease(lease, false); return; }
          var workers = Math.min(concurrency, queue.length);
          for (var i = 0; i < workers; i++) runThemeWorker(queue, gen, batch);
        }
        // Themed renders for cards that are off-screen (or hidden by search / another tab), held
        // until the viewport reaches them. `rootMargin` starts a card a screenful early so scrolling
        // meets finished pixels rather than a spinner.
        //
        // A deferred batch takes a claim of ITS OWN and hands it back when it drains. It wants none
        // of the burst — it runs one card at a time behind the visitor's scroll — but a render sent
        // without a claim queues on the server's single unleased semaphore, shared with every other
        // page. Riding the *visible* batch's token instead (what this used to do) was the bug: that
        // claim is released the moment the on-screen cards finish — immediately, when none were on
        // screen — and every later card then presented a token the server had already reaped and
        // was refused `429` until its retries ran out. Claims for one catalog join one allocation,
        // so asking again is a bookkeeping call, not extra width.
        var themeObserver = null;
        function stopDeferredTheme() {
          if (themeObserver) themeObserver.disconnect();
          themeObserver = null;
        }
        // Whether a card is close enough to the viewport to be worth rendering NOW. This is
        // geometry, deliberately, not `hidden`: on a flat catalog with no search nothing is hidden,
        // so partitioning on `hidden` alone would put all 80+ cards in the leased batch and defer
        // nothing — exactly the case this is here to fix. A zero-size rect (display:none, e.g. a
        // non-current tab panel) is never near the viewport.
        function nearViewport(c) {
          var r = c.getBoundingClientRect();
          if (!r.width && !r.height) return false;
          var h = window.innerHeight || document.documentElement.clientHeight || 0;
          return r.bottom > -400 && r.top < h + 400;
        }
        function runDeferredThemeBatch(jobs, gen) {
          acquireThemeLease(gen, function (lease) {
            if (gen !== themeGen) { releaseThemeLease(lease, false); return; }
            if (lease) stampThemeLease(jobs, lease);
            // One at a time whatever width was granted, and the batch releases the claim itself
            // once its last card settles.
            runThemeQueue(jobs, gen, lease, 1);
          });
        }
        function deferTheme(jobs, gen) {
          if (!jobs.length) return;
          // No IntersectionObserver (old browser): fall back to rendering them, serially, rather
          // than leaving those cards stuck on the wrong theme forever.
          if (!window.IntersectionObserver) { runDeferredThemeBatch(jobs, gen); return; }
          // Both the observer and its worklist are per-generation locals, never shared globals: a
          // callback already queued when the visitor picks another theme must retire ITSELF and
          // touch nothing else. Clearing the live observer or its pending list from a stale
          // callback would strand every not-yet-scrolled card on the previous theme's pixels.
          var pending = jobs.slice();
          var observer = new IntersectionObserver(function (entries) {
            if (gen !== themeGen) { observer.disconnect(); return; }
            var due = [];
            entries.forEach(function (e) {
              if (!e.isIntersecting) return;
              observer.unobserve(e.target);
              for (var i = 0; i < pending.length; i++) {
                if (pending[i].card === e.target) { due.push(pending.splice(i, 1)[0]); break; }
              }
            });
            if (due.length) runDeferredThemeBatch(due, gen);
          }, { rootMargin: "400px" });
          themeObserver = observer;
          jobs.forEach(function (job) { observer.observe(job.card); });
        }
        window.addEventListener("pagehide", function () { releaseAllThemeLeases(true); });
        """
          .trimIndent()
      else ""
    // Swap every swappable card to the chosen theme's baked render and press its button; cards
    // missing that theme are skipped. For a declared theme cards keep their default variant's
    // metadata (`data-def`) and only the pixels change, via a `themeProvider` param on cards that
    // can re-render (`data-theme-live`).
    // On the All tab every section is on screen, so visibility is decided by geometry alone; the
    // tab comparison is only for tabbed views.
    val themeSectionShowing =
      if (hasAllTab)
        "current === \"$ALL_TAB\" || themeSection.getAttribute(\"data-section\") === current"
      else "themeSection.getAttribute(\"data-section\") === current"
    val correctInitialThemeVisibility =
      if (hasTabs)
        "\n            var themeSection = c.closest(\".cp-section\");" +
          "\n            if (themeVisible && themeSection && (!input || input.value.trim() === \"\")) {" +
          "\n              themeVisible = $themeSectionShowing;" +
          "\n            }"
      else ""
    val applyDeclaredTheme =
      if (hasDeclaredThemes)
        """
        var provider = theme.indexOf("theme:") === 0 ? theme.slice(6) : "";
        releaseAllThemeLeases(false);
        themeGen++;
        stopDeferredTheme();
        var themeQueue = [];
        var themeDeferredQueue = [];
        var themeQueueGen = themeGen;
        cards.forEach(function (c) {
          c.classList.remove("cp-reloading");
          c.removeAttribute("aria-busy");
          clearThemeError(c);
        });
        if (provider) {
          cards.forEach(function (c, i) {
            if (c.getAttribute("data-swap") === "1") applyVariant(c, c.getAttribute("data-def") || "l", false);
            var img = c.querySelector("img");
            var base = themeBase[i];
            if (!img || !base) return;
            if (selectedThemeMode) c.setAttribute("data-bg-theme", selectedThemeMode);
            else {
              var bgDefault = c.getAttribute("data-bg-default") || "";
              if (bgDefault) c.setAttribute("data-bg-theme", bgDefault);
              else c.removeAttribute("data-bg-theme");
            }
            var themedSrc = base + (base.indexOf("?") === -1 ? "?" : "&") + "themeProvider=" + encodeURIComponent(provider);
            var job = {
              card: c,
              img: img,
              baseSrc: themedSrc,
              src: themedSrc,
              retries: 0,
            };
            // A tabbed catalog's hidden cards can take several daemon renders before the visitor
            // sees any response to their click. On initial load c.hidden is not assigned yet, so
            // also compare the card's section with the saved current tab. During a live search the
            // existing hidden state already spans tabs and remains authoritative.
            var themeVisible = !c.hidden && nearViewport(c);$correctInitialThemeVisibility
            if (themeVisible) {
              // Visible work is queued now even when the lease falls back to one worker. Mark the
              // whole on-screen batch busy immediately so cards waiting behind that worker cannot
              // pass their old-theme pixels off as finished.
              c.classList.add("cp-reloading");
              c.setAttribute("aria-busy", "true");
              themeQueue.push(job);
            } else {
              themeDeferredQueue.push(job);
            }
          });
          // Off-screen cards are NOT rendered up front. A catalog is commonly 80+ cards and the
          // shared daemon renders them one at a time (~1s each), so draining the whole grid costs a
          // minute of daemon time — most of it for pixels the visitor never scrolls to, while the
          // cards they ARE looking at wait behind them. They queue against the viewport instead and
          // render as they come into view, which is also what makes an emptied search or a newly
          // opened tab render just its own cards.
          acquireThemeLease(themeQueueGen, function (lease, concurrency) {
            // This claim belongs to the ON-SCREEN batch alone, and dies with it. Deferred cards ask
            // for their own when the viewport reaches them (runDeferredThemeBatch) — stamping them
            // here handed them a token that was already released by the time they ran.
            if (lease) stampThemeLease(themeQueue, lease);
            runThemeQueue(themeQueue, themeQueueGen, lease, concurrency);
            deferTheme(themeDeferredQueue, themeQueueGen);
          });
          return;
        }
        """
          .trimIndent()
      else ""
    // Spliced into applyThemeChoice's body (one level in), so a catalog with no declared themes
    // emits the plain baked swap exactly as before.
    val applyDeclaredThemeIndented =
      applyDeclaredTheme.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n          $it" }
    // Leaving a declared theme has to put a NON-swap card back on its baked pixels (a swap card is
    // restored by applyVariant). Its baked URL is the same themeBase entry, minus the override.
    val restoreBakedSrc =
      if (hasDeclaredThemes)
        "\n            var img = c.querySelector(\"img\");" +
          "\n            var base = themeBase[i];" +
          "\n            if (img && base) setCardSrc(img, base);" +
          "\n            var bgDefault = c.getAttribute(\"data-bg-default\") || \"\";" +
          "\n            if (bgDefault) c.setAttribute(\"data-bg-theme\", bgDefault);" +
          "\n            else c.removeAttribute(\"data-bg-theme\");"
      else ""
    val applyTheme =
      if (hasThemes)
        """
        themeBtns.forEach(function (b) {
          b.setAttribute("aria-pressed", b.getAttribute("data-theme-choice") === theme ? "true" : "false");
        });
        var selectedThemeButton = null;
        themeBtns.forEach(function (b) {
          if (b.getAttribute("data-theme-choice") === theme) selectedThemeButton = b;
        });
        var selectedThemeMode = selectedThemeButton
          ? selectedThemeButton.getAttribute("data-theme-mode") || "" : "";
        // Point a swap card at one of its baked variants ("l"/"d"): pixels (unless the caller is
        // supplying themed ones), alt text, label, id, viewer link and stage backing.
        // Point a card's <img> at a plain URL, releasing whatever blob it was holding first.
        // A themed render is handed over as an object URL (see runThemeWorker); leaving a declared
        // theme for Light / Dark / Default replaces that source, and without this the blob behind
        // it would stay resident until the page unloaded — one full-resolution PNG per card, on
        // catalogs that routinely run to 80+ cards.
        function setCardSrc(img, url) {
          var previous = img.getAttribute("data-cp-blob");
          img.src = url;
          if (previous) {
            img.removeAttribute("data-cp-blob");
            URL.revokeObjectURL(previous);
          }
        }
        // The id line elides from the MIDDLE (`iconbutton-standard__…__light`), so it is two spans
        // and a swap re-fills both rather than overwriting the line with one string — which would
        // delete the spans and take the elision with them. Same split rule as the server's
        // `cardIdHtml`: cut at the last `__`, the tail keeps the mode and the scheme.
        function setCardId(idn, id) {
          var head = idn.querySelector(".cp-id-head");
          var tail = idn.querySelector(".cp-id-tail");
          if (!head || !tail) { idn.textContent = id; return; }
          var cut = id.lastIndexOf("__");
          head.textContent = cut > 0 ? id.slice(0, cut) : id;
          tail.textContent = cut > 0 ? id.slice(cut) : "";
        }
        function applyVariant(c, k, withSrc) {
          var src = c.getAttribute("data-" + k + "-src");
          if (!src) return;
          var img = c.querySelector("img");
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var lbl = c.getAttribute("data-" + k + "-label");
          if (img) { if (withSrc) setCardSrc(img, src); img.setAttribute("alt", lbl); }
          c.setAttribute("href", c.getAttribute("data-" + k + "-href"));
          c.setAttribute("aria-label", lbl);
          if (lab) { lab.textContent = lbl; lab.setAttribute("title", lbl); }
          if (idn) setCardId(idn, c.getAttribute("data-" + k + "-id"));
          c.setAttribute("data-bg-theme", k === "d" ? "dark" : "light");
        }
        cards.forEach(function (c) {
          c.setAttribute("data-bg-default", c.getAttribute("data-bg-theme") || "");
        });
        // apply() also runs on every search keystroke; re-point the cards only when the THEME
        // actually changed, so typing never restarts an in-flight themed-render queue.
        function applyThemeChoice() {
          if (theme === appliedTheme) return;
          appliedTheme = theme;
          // Turn the page over with the previews: with the Page theme setting on, the chrome
          // follows an explicit Light/Dark pick (the Page theme setting decides; a declared theme leaves it
          // alone). Guarded because that file is deferred to the end of the body.
          if (window.cpPageTheme) window.cpPageTheme.follow(theme);$applyDeclaredThemeIndented
          var k = theme === "dark" ? "d" : "l";
          cards.forEach(function (c, i) {
            if (c.getAttribute("data-swap") === "1") { applyVariant(c, k, true); return; }$restoreBakedSrc
          });
        }
        applyThemeChoice();
        """
          .trimIndent()
      else ""
    val themeWiring =
      if (hasThemes)
        """themeBtns.forEach(function (b) {
        b.addEventListener("click", function () {
          theme = b.getAttribute("data-theme-choice");
          try { sessionStorage.setItem("$themeStorageKey", theme); } catch (e) {}
          // A discrete pick gets its own history entry, so Back returns to the previous theme
          // rather than leaving the catalog. No navigation: the grid re-points its own images.
          pushUrl({ theme: theme });
          apply();
        });
      });"""
      else ""
    // Tab pieces, each empty for a section-less catalog and spliced inline so a plain catalog's
    // script is unchanged.
    // `cp-js` on `<html>` hides the redundant per-section `<h2>`.
    // `.cp-subgroup` dividers exist for authored and synthesized groups alike, so their collapse
    // lives under [hasGroups].
    val groupDecls =
      if (hasGroups) "\n      var navGroups = document.querySelectorAll(\".cp-subgroup\");" else ""
    // Declared here, not in the later-spliced tree script: `reflectTabs` runs immediately and
    // touches `treeGroups`.
    // The Components/Pages switch lives inside the filter IIFE so the pane in view and the query
    // are one piece of state.
    val paneDecls =
      if (!hasPanes) ""
      else
        "\n      var paneTabs = document.querySelectorAll(\".cp-pane-tab\");" +
          "\n      var pageRows = document.querySelectorAll(\"#cp-pane-pages .cp-tree-page\");" +
          "\n      var sectionRows = document.querySelectorAll(\"#cp-pane-pages .cp-tree-variant\");" +
          "\n      var pagesEmpty = document.getElementById(\"cp-pages-empty\");" +
          "\n      var pane = urlParam(\"pane\") === \"pages\" ? \"pages\" : \"components\";" +
          // The placeholder says which pane the box will search.
          "\n      function reflectPanes() {" +
          "\n        paneTabs.forEach(function (t) {" +
          "\n          var on = t.getAttribute(\"data-pane\") === pane;" +
          "\n          t.setAttribute(\"aria-selected\", on ? \"true\" : \"false\");" +
          "\n          t.tabIndex = on ? 0 : -1;" +
          "\n          var panel = document.getElementById(t.getAttribute(\"aria-controls\"));" +
          "\n          if (panel) panel.hidden = !on;" +
          "\n        });" +
          "\n        if (input) {" +
          "\n          var what = pane === \"pages\" ? \"pages\" : \"previews\";" +
          "\n          input.placeholder = \"Filter \" + what + \"…\";" +
          "\n          input.setAttribute(\"aria-label\", \"Filter \" + what);" +
          "\n        }" +
          "\n      }"
    val treeDecls =
      if (!hasTree) ""
      else
        "\n      var treeGroups = document.querySelectorAll(\".cp-tree-group\");" +
          "\n      var treeComponents = document.querySelectorAll(\".cp-tree-component\");" +
          "\n      var treeLinks = document.querySelectorAll(\".cp-tree-link\");" +
          // The tree's own roving-tab-stop pass, published so `apply()` can call it from outside
          // the tree script's closure once the filter has changed which rows are on screen.
          "\n      var cpTreeStops = null;"
    // Under All the tree opens every branch and the per-section `<h2>`s come back, as during a
    // search.
    val allTabState =
      if (!hasAllTab) ""
      else
        "\n        var showingAll = current === \"$ALL_TAB\";" +
          "\n        document.documentElement.classList.toggle(" +
          "\n          \"cp-multi-section\"," +
          "\n          showingAll || searching" +
          "\n        );"
    val allExpandExpr = if (hasAllTab) " || showingAll" else ""
    // Under All a card is in the current tab whatever section it sits in — that is what All means.
    val allTabCardClause = if (hasAllTab) " || current === \"$ALL_TAB\"" else ""
    val tabDecls =
      if (hasTabs)
        "\n      var tabBtns = document.querySelectorAll(\".cp-tab\");" +
          "\n      var treeGroups = document.querySelectorAll(\".cp-tree-group\");" +
          "\n      var tabSections = document.querySelectorAll(\".cp-section\");" +
          "\n      var current = tabBtns.length ? tabBtns[0].getAttribute(\"data-tab\") : null;" +
          "\n      try {" +
          "\n        var storedTab = localStorage.getItem(\"$tabStorageKey\");" +
          "\n        tabBtns.forEach(function (t) {" +
          "\n          if (t.getAttribute(\"data-tab\") === storedTab) current = storedTab;" +
          "\n        });" +
          "\n      } catch (e) {}" +
          // `?tab=` outranks the remembered tab for the same reason `?theme=` outranks the
          // remembered chip: it is on the URL because it was chosen, here or by whoever shared it.
          "\n      var urlTab = urlParam(\"tab\");" +
          "\n      tabBtns.forEach(function (t) {" +
          "\n        if (t.getAttribute(\"data-tab\") === urlTab) current = urlTab;" +
          "\n      });" +
          "\n      var initialTab = current;" +
          // The selected section is the open one and holds the tree's tab stop; during a search
          // every branch with a match opens.
          "\n      function reflectTabs() {" +
          "\n        var searching = !!(input && input.value.trim());" +
          allTabState +
          "\n        var stop = null;" +
          "\n        var firstShown = null;" +
          "\n        tabBtns.forEach(function (t) {" +
          "\n          var on = t.getAttribute(\"data-tab\") === current;" +
          "\n          t.setAttribute(\"aria-selected\", on ? \"true\" : \"false\");" +
          "\n          if (t.hasAttribute(\"aria-expanded\"))" +
          "\n            t.setAttribute(" +
          "\n              \"aria-expanded\"," +
          "\n              on || searching$allExpandExpr ? \"true\" : \"false\"" +
          "\n            );" +
          "\n          var node = t.closest(\".cp-tree-node\");" +
          "\n          var shown = !(node && node.hidden);" +
          // The stop belongs to the selected row only while that row is on screen, so a filtered-
          // out section does not keep a claim on it that the fallback below then duplicates.
          "\n          t.tabIndex = on && shown ? 0 : -1;" +
          "\n          if (shown && !firstShown) firstShown = t;" +
          "\n          if (on && shown) stop = t;" +
          "\n        });" +
          "\n        treeGroups.forEach(function (g) { g.tabIndex = -1; });" +
          // If a filter hides the selected section, hand the tree's tab stop to the first visible
          // branch.
          "\n        if (!stop && firstShown) firstShown.tabIndex = 0;" +
          "\n      }" +
          "\n      reflectTabs();" +
          "\n      document.documentElement.classList.add(\"cp-js\");"
      else ""
    // A card is shown when it matches the search and, when not searching, sits in the current tab.
    // `uses:` counts as searching even when `q` is empty after stripping the operator.
    val searchingExpr = if (usesFilter) "(q !== \"\" || usesActive())" else "q !== \"\""
    val tabOkLine =
      if (hasTabs)
        "\n          var sec = c.closest(\".cp-section\");" +
          "\n          var tabOk = $searchingExpr || !sec" +
          allTabCardClause +
          " || sec.getAttribute(\"data-section\") === current;"
      else ""
    val hiddenExpr = if (hasTabs) "!(searchOk && tabOk)" else "!searchOk"
    val shownCond = if (hasTabs) "searchOk && tabOk" else "searchOk"
    // Collapse sub-groups/sections left with no visible card, and re-size survivors: a cluster's
    // width is its visible card count (`--cp-n`, see `.cp-subgroup` in serve.css).
    val groupPost =
      if (hasGroups)
        "\n        navGroups.forEach(function (g) {" +
          "\n          var on = g.querySelectorAll(\".cp-card:not([hidden])\").length;" +
          "\n          g.hidden = !on;" +
          "\n          if (on) g.style.setProperty(\"--cp-n\", on);" +
          "\n        });"
      else ""
    val sectionPost =
      if (hasTabs)
        "\n        tabSections.forEach(function (s) { s.hidden = !s.querySelector(\".cp-card:not([hidden])\"); });"
      else ""
    // Tree rows follow the grid: group rows hide with their sub-group. Section rows hide only while
    // searching, since outside a search non-current sections are empty by construction.
    val treePost =
      if (!hasTree) ""
      else
      // Component rows follow their cards, then group rows their sub-group.
      "\n        treeComponents.forEach(function (c) {" +
          "\n          var card = document.getElementById(c.getAttribute(\"data-group\"));" +
          "\n          if (c.parentElement) c.parentElement.hidden = !!(card && card.hidden);" +
          "\n        });" +
          "\n        treeGroups.forEach(function (g) {" +
          "\n          var sub = document.getElementById(g.getAttribute(\"data-group\"));" +
          "\n          if (g.parentElement) g.parentElement.hidden = !!(sub && sub.hidden);" +
          "\n        });" +
          (if (hasTabs)
            "\n        tabBtns.forEach(function (t) {" +
              "\n          var node = t.closest(\".cp-tree-node\");" +
              "\n          var sec = document.getElementById(t.getAttribute(\"aria-controls\"));" +
              "\n          if (node) node.hidden = q !== \"\" && !!(sec && sec.hidden);" +
              "\n        });" +
              "\n        reflectTabs();"
          else "") +
          // Last word on the roving tab stop: the rows that just appeared or vanished change which
          // one should hold it, and `reflectTabs` only ever knew about sections and groups.
          "\n        if (cpTreeStops) cpTreeStops();"
    // The pages list uses the same query, matched against `data-search` rather than row text.
    val panePost =
      if (!hasPanes) ""
      else
        "\n        var pagesShown = 0;" +
          // Sections first, so a page is kept when its name or any of its sections matches.
          "\n        sectionRows.forEach(function (sec) {" +
          "\n          var hay = (sec.getAttribute(\"data-search\") || \"\").toLowerCase();" +
          "\n          var keep = paneQ === \"\" || hay.indexOf(paneQ) !== -1;" +
          "\n          if (sec.parentElement) sec.parentElement.hidden = !keep;" +
          "\n        });" +
          "\n        pageRows.forEach(function (p) {" +
          "\n          var hay = (p.getAttribute(\"data-search\") || \"\").toLowerCase();" +
          "\n          var own = paneQ === \"\" || hay.indexOf(paneQ) !== -1;" +
          "\n          var list = p.nextElementSibling;" +
          "\n          var kept = list" +
          "\n            ? Array.prototype.some.call(list.children, function (li) { return !li.hidden; })" +
          "\n            : false;" +
          "\n          var keep = own || kept;" +
          // A page kept only by its sections shows just those and opens; one matching by name keeps
          // its whole list.
          "\n          if (own && list) {" +
          "\n            Array.prototype.forEach.call(list.children, function (li) { li.hidden = false; });" +
          "\n          }" +
          "\n          if (p.parentElement) p.parentElement.hidden = !keep;" +
          "\n          if (p.hasAttribute(\"aria-expanded\") && paneQ !== \"\")" +
          "\n            p.setAttribute(\"aria-expanded\", keep ? \"true\" : \"false\");" +
          "\n          if (keep) pagesShown++;" +
          "\n        });" +
          "\n        if (pagesEmpty) pagesEmpty.hidden = pagesShown !== 0;"
    // The query filters only the pane showing: on Pages the grid is left whole.
    // The Pages pane filters on the raw query, since `uses:` cannot match a design sheet and should
    // yield an empty state.
    val paneQExpr = if (usesFilter) "usesRawQuery" else "q"
    val paneSplit =
      if (!hasPanes) ""
      else
        "\n        var paneQ = pane === \"pages\" ? $paneQExpr : \"\";" +
          "\n        if (pane === \"pages\") q = \"\";"
    val paneWiring =
      if (!hasPanes) ""
      else
      // The page row twisty and its keys. The row is both a link and a fold: a pointer click inside
      // the arrow's 14px folds, Right/Left fold from the keyboard, and Enter follows the link.
      "\n      pageRows.forEach(function (p) {" +
          "\n        if (!p.hasAttribute(\"aria-expanded\")) return;" +
          "\n        function fold(open) {" +
          "\n          p.setAttribute(\"aria-expanded\", open ? \"true\" : \"false\");" +
          "\n        }" +
          "\n        p.addEventListener(\"keydown\", function (e) {" +
          "\n          if (e.key !== \"ArrowRight\" && e.key !== \"ArrowLeft\") return;" +
          "\n          var open = e.key === \"ArrowRight\";" +
          "\n          if ((p.getAttribute(\"aria-expanded\") === \"true\") === open) return;" +
          "\n          e.preventDefault();" +
          "\n          fold(open);" +
          "\n        });" +
          "\n        p.addEventListener(\"click\", function (e) {" +
          // Keyboard Enter synthesizes a click with `offsetX` 0 (inside the arrow), so this is
          // pointer-only (`detail` > 0).
          "\n          if (!e.detail) return;" +
          "\n          if (e.offsetX > 14) return;" +
          "\n          e.preventDefault();" +
          "\n          fold(p.getAttribute(\"aria-expanded\") !== \"true\");" +
          "\n        });" +
          "\n      });" +
          "\n      paneTabs.forEach(function (t) {" +
          "\n        t.addEventListener(\"click\", function () {" +
          "\n          pane = t.getAttribute(\"data-pane\");" +
          "\n          reflectPanes();" +
          // Replaces rather than pushes, for the same reason typing does: switching a sidebar pane
          // is not a place you expect Back to undo one step at a time.
          "\n          replaceUrl({ pane: pane === \"pages\" ? \"pages\" : \"\" });" +
          // Re-filter: switching panes changes what the query means.
          "\n          apply();" +
          "\n        });" +
          "\n      });" +
          "\n      reflectPanes();"
    val tabWiring =
      if (hasTabs)
        "\n      tabBtns.forEach(function (t) {" +
          "\n        t.addEventListener(\"click\", function (e) {" +
          "\n          e.preventDefault();" +
          "\n          current = t.getAttribute(\"data-tab\");" +
          "\n          try { localStorage.setItem(\"$tabStorageKey\", current); } catch (e) {}" +
          "\n          reflectTabs();" +
          "\n          pushUrl({ tab: current });" +
          "\n          apply();" +
          "\n        });" +
          "\n      });"
      else ""
    // The tree's behaviour (group rows, keyboard, scroll-spy); empty for a flat catalog. Emitted
    // after [popWiring] so its fragment-precedence `popstate` handler runs last over the shared
    // `?tab=` restore.
    val treeWiring =
      if (!hasTree) ""
      else
        catalogTreeScript(tabStorageKey, hasTabs, hasAllTab).lines().joinToString("") {
          if (it.isEmpty()) "\n" else "\n      $it"
        }
    // Back / Forward: re-apply the selection from the URL in place. A missing param falls back to
    // this page load's value, not the remembered one, or Back out of a theme would land on it
    // again.
    val themePop =
      if (hasThemes)
        "\n          var poppedTheme = urlParam(\"theme\") || initialTheme;" +
          "\n          if (chipOffered(poppedTheme)) theme = poppedTheme;"
      else ""
    val tabPop =
      if (hasTabs)
        "\n          var poppedTab = urlParam(\"tab\") || initialTab;" +
          "\n          tabBtns.forEach(function (t) {" +
          "\n            if (t.getAttribute(\"data-tab\") === poppedTab) current = poppedTab;" +
          "\n          });" +
          "\n          reflectTabs();"
      else ""
    // The Dev-mode `uses:` operator, spliced in rather than a second script so it shares `apply()`
    // and its state.
    val usesDecls = if (usesFilter) usesFilterScript(usesUrl) else ""
    val queryExpr =
      if (usesFilter) "var q = usesSplit(input ? input.value.trim() : \"\");"
      else "var q = input ? input.value.trim().toLowerCase() : \"\";"
    // Parenthesised only when there is a second conjunct, so a Catalog-mode script keeps the exact
    // expression it had rather than gaining redundant brackets in every page fixture.
    val searchOkExpr =
      if (usesFilter) "(q === \"\" || hay.indexOf(q) !== -1) && usesOk(c)"
      else "q === \"\" || hay.indexOf(q) !== -1"
    // Written at the END of `apply()`, so the readout describes the grid as it now stands rather
    // than as it was before this pass narrowed it.
    val usesReport = if (usesFilter) "\n        usesReport(shown, total);" else ""
    val popWiring =
      "\n      if (urlState) {" +
        "\n        urlState.onPop(function () {$themePop$tabPop" +
        "\n          if (input) input.value = urlParam(\"q\");" +
        "\n          apply();" +
        "\n        });" +
        "\n      }"
    return """
    (function () {
      var cards = document.querySelectorAll(".cp-card");
      var input = document.getElementById("cp-search");
      var count = document.getElementById("cp-count");
      var empty = document.getElementById("cp-empty");
      var total = cards.length;
      // Address-bar state (`window.cpUrlState`). Every selection below is reflected into the URL so the
      // page someone is looking at is the page its URL describes — bookmarkable, shareable, and
      // reachable with Back — without ever reloading: the grid re-points its own images.
      var urlState = window.cpUrlState || null;
      function urlParam(n) { return urlState ? urlState.get(n) : ""; }
      function pushUrl(v) { if (urlState) urlState.push(v); }
      function replaceUrl(v) { if (urlState) urlState.replace(v); }
      if (input) { var urlQuery = urlParam("q"); if (urlQuery) input.value = urlQuery; }$usesDecls$groupDecls$treeDecls$tabDecls$paneDecls
      ${listOf(themeInit, themeRenderInit).filter { it.isNotEmpty() }.joinToString("\n")}
      function apply() {
        $applyTheme
        $queryExpr$paneSplit
        var shown = 0;
        cards.forEach(function (c) {
          var lab = c.querySelector(".cp-label");
          var idn = c.querySelector(".cp-id");
          var hay = ((lab ? lab.textContent : "") + " " + (idn ? idn.textContent : "")).toLowerCase();
          var searchOk = $searchOkExpr;$tabOkLine
          c.hidden = $hiddenExpr;
          if ($shownCond) shown++;
        });
        if (count) count.textContent = q === "" ? (total + " preview" + (total === 1 ? "" : "s")) : (shown + " of " + total);$usesReport
        if (empty) empty.hidden = shown !== 0;$groupPost$sectionPost$treePost$panePost
      }
      if (input) input.addEventListener("input", function () {
        // Typing REPLACES rather than pushes: a five-character filter must not bury the page the
        // visitor arrived from under five entries. The URL still carries the query, so the
        // filtered grid is bookmarkable.
        replaceUrl({ q: input.value.trim() });
        apply();
      });
      $themeWiring$tabWiring$paneWiring$popWiring$treeWiring
      apply();$presenceWiring
    })();
    """
      .trimIndent()
  }

  /**
   * The `uses:` operator's state and helpers, spliced into [catalogFilterScript]'s IIFE.
   *
   * `uses:Button` narrows the grid to previews whose declaration calls something matching `Button`,
   * resolved server-side ([ServeHttpServer.handleUsesSearch]); it composes with the text filter. An
   * operator rather than a control because it costs nothing until typed and rides `?q=`.
   *
   * Unresolved is not empty: a pending token says "looking for calls to …" and an unindexable
   * catalog says so, which is why the endpoint returns an `available` flag.
   */
  private fun usesFilterScript(usesUrl: String): String {
    val script =
      """
      // ---- `uses:<composable>` — which previews CALL a thing (Dev mode only)
      var usesEndpoint = ${WebEscaping.jsString(usesUrl)};
      var usesToken = "";
      var usesRawQuery = "";
      var usesCache = {};
      var usesPending = {};
      // Whether a `uses:` filter is running right now. Asked wherever the page would otherwise
      // test `q !== ""` to mean "a filter is on": a query of only `uses:Foo` leaves `q` empty, and
      // every such test would then read as "resting".
      function usesActive() { return usesToken !== ""; }
      // Pulls `uses:<token>` out of the raw query and returns what is left for the text filter.
      // It also notices a CHANGE of token and starts the lookup, which is why it is one function
      // and not a pure split: `apply()` is the only place that reads the box, so it is the only
      // place that can see the token change, and a separate listener would have to re-parse the
      // same string and could disagree with this one about what was typed.
      function usesSplit(raw) {
        var found = "";
        var rest = raw.replace(/(^|\s)uses:(\S*)/i, function (m, lead, t) { found = t; return lead; });
        if (found !== usesToken) { usesToken = found; usesFetch(found); }
        // Kept for the Pages pane, which filters on what was TYPED rather than on the remainder —
        // see `paneQ` in the filter script.
        usesRawQuery = raw.trim().toLowerCase();
        return rest.trim().toLowerCase();
      }
      function usesEntry() { return usesToken ? usesCache[usesToken.toLowerCase()] : null; }
      // A card carries its default preview id; every variant folded onto it shares that declaration.
      function usesOk(c) {
        if (usesToken === "") return true;
        var entry = usesEntry();
        if (!entry || !entry.available) return false;
        var id = c.getAttribute("data-uses-id");
        return !!id && entry.ids.indexOf(id) !== -1;
      }
      // The readout under the search box. Hidden entirely while the operator is not in use, so the
      // page is unchanged for a visitor who never types it.
      function usesReport(shown, total) {
        var el = document.getElementById("cp-uses-status");
        if (!el) return;
        if (usesToken === "") { el.hidden = true; el.textContent = ""; return; }
        var entry = usesEntry();
        var note;
        if (!entry) note = "looking for calls to " + usesToken + "…";
        else if (!entry.available) note = "call index unavailable";
        else
          note =
            shown + " of " + total + " call " + usesToken +
            (entry.truncated ? " (partial index)" : "");
        el.textContent = note;
        el.hidden = false;
      }
      function usesFetch(t) {
        if (t === "") return;
        var key = t.toLowerCase();
        if (usesCache[key] || usesPending[key]) return;
        usesPending[key] = true;
        var sep = usesEndpoint.indexOf("?") === -1 ? "?" : "&";
        // Any failure — 404 in Catalog mode, an offline tab, a body that isn't JSON — lands as
        // "unavailable" rather than as an empty result, so the count line never reports a zero the
        // server did not actually compute.
        var unavailable = { available: false, truncated: false, ids: [] };
        fetch(usesEndpoint + sep + "q=" + encodeURIComponent(key))
          .then(function (r) { return r.ok ? r.json() : unavailable; })
          .catch(function () { return unavailable; })
          .then(function (data) {
            usesCache[key] = {
              available: !!data.available,
              truncated: !!data.truncated,
              ids: (data.ids || []).slice()
            };
            delete usesPending[key];
            apply();
          });
      }
      """
        .trimIndent()
    // Spliced one level in, the way [presenceScript] is, so the emitted script keeps the IIFE's
    // indentation instead of stitching a flush-left block into the middle of it.
    return script.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n      $it" }
  }

  /**
   * The navigation tree's behaviour, spliced into [catalogFilterScript]'s IIFE to share `current`,
   * `reflectTabs`, `apply` and `pushUrl`.
   * * **group rows**: select the section, then scroll its divider into view; the bare `#cp-group-…`
   *   href is the no-JS fallback.
   * * **keyboard**: roving focus (Down/Up visible rows, Right opens, Left climbs, Home/End).
   * * **scroll-spy**: the row for the on-screen sub-group gets `aria-current`; without
   *   `IntersectionObserver` marking follows clicks.
   */
  private fun catalogTreeScript(
    tabStorageKey: String,
    hasTabs: Boolean,
    /** Whether the tree leads with the **All** row ([ALL_TAB]) — see [catalogFilterScript]. */
    hasAllTab: Boolean = false,
  ): String {
    // An outline tree (no sections) has no section switching, so those pieces are spliced out
    // rather than runtime-guarded.
    val selectOwningTab = if (hasTabs) "\n        selectOwningTab(row);" else ""
    val tabRows = if (hasTabs) ".cp-tab, " else ""
    // Fragment rows and section-only operations; an outline tree gets inert stand-ins.
    // A `#cp-group-…` fragment scrolls but does not change the slice: under All it stays in All, so
    // a reload lands on the same page; elsewhere it selects the fragment's section.
    val keepAll = if (hasAllTab) "\n          if (current === \"$ALL_TAB\") return;" else ""
    // Same rule on Back/Forward, but the marking still happens: the entry is a scroll position
    // within All, so the row it names is the one to mark.
    val keepAllPop =
      if (!hasAllTab) ""
      else
        "\n            if (current === \"$ALL_TAB\") {" +
          "\n              if (popped.row) markGroup(popped.row);" +
          "\n              return;" +
          "\n            }"
    val tabHelpers =
      if (hasTabs)
        """
        var tabBtnsForHash = tabBtns;
        function selectTab(slug) {
          if (!slug || slug === current) return;
          current = slug;
          try { localStorage.setItem("$tabStorageKey", current); } catch (e) {}
          reflectTabs();
          pushUrl({ tab: current });
          apply();
        }
        function selectCollapsedTab(row) { selectTab(row.getAttribute("data-tab")); }
        // Jumping to a group from ALL stays in All: the row you clicked says where to scroll, not
        // which slice of the catalog to throw away. From a section it still switches, because a
        // group row can name a section other than the one showing.
        function selectOwningTab(row) {$keepAll
          selectTab(row.getAttribute("data-tab"));
        }
        function applyLandingTab(landing) {
          if (!landing.tab || landing.tab === current) return;$keepAll
          current = landing.tab;
          initialTab = current;
          reflectTabs();
        }
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
      else
        "\n" +
          """
          var tabBtnsForHash = [];
          function selectCollapsedTab() {}
          function applyLandingTab() {}
          """
            .trimIndent()
            .lines()
            .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
            .trimStart()
    val popPrecedence =
      if (!hasTabs) ""
      else
        """

        // Back / Forward has to resolve an entry the same way loading it fresh would. The shared pop
        // handler reads `?tab=` only, so returning to an entry whose fragment and query disagree —
        // `?tab=components#cp-group-themes-foundation`, which a fresh load resolves to Themes —
        // would land on Components with the fragment's target hidden. This runs after that handler
        // (registered later, and `onPop` is a plain listener) and re-applies the precedence.
        if (window.cpUrlState) {
          window.cpUrlState.onPop(function () {
            var popped = hashTarget();
            if (!popped || popped.tab === current) return;$keepAllPop
            current = popped.tab;
            reflectTabs();
            apply();
            if (popped.row) markGroup(popped.row);
          });
        }
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
    val sectionClicks =
      if (!hasTabs) ""
      else
        """

        // Choosing a whole section retires any group fragment: the row you clicked is the statement
        // of where you are, and a leftover `#cp-group-…` from another section would outrank it on
        // the next load. It also has to honour the promise its own `href="#cp-panel-…"` makes — the
        // shared handler prevents the default navigation and only swaps which panel is hidden, so
        // from halfway down a long section the scroll simply stayed where it was. Registered after
        // that handler, so the panel is already showing when this measures it, and it only scrolls
        // when the panel is actually behind the sticky toolbar.
        tabBtns.forEach(function (t) {
          t.addEventListener("click", function () {
            setFragment("");
            var panel = document.getElementById(t.getAttribute("aria-controls"));
            if (!panel) return;
            var clearance = tools ? tools.getBoundingClientRect().height : 0;
            if (panel.getBoundingClientRect().top < clearance) {
              panel.scrollIntoView({ block: "start" });
            }
          });
        });
        """
          .trimIndent()
          .lines()
          .joinToString("\n") { if (it.isEmpty()) "" else "      $it" }
          .trimStart()
    return """
    (function () {
      var tree = document.getElementById("cp-tabs");
      if (!tree) return;
      // The toolbar above pins itself at top:0 over everything, so publish its real height for the
      // sticky menu's offset and for every scroll target's `scroll-margin-top`. Measured rather
      // than assumed: it wraps on a narrow viewport and grows a row with the declared-theme chips,
      // and the static fallback in the stylesheet only covers the unwrapped case.
      // `cp-js` gates every collapse rule in the stylesheet. It used to be set by the section
      // machinery alone, which would have left an outline tree permanently expanded.
      document.documentElement.classList.add("cp-js");
      var tools = document.querySelector(".cp-catalog-tools");
      if (tools) {
        var syncTools = function () {
          var h = Math.round(tools.getBoundingClientRect().height);
          if (h > 0) document.documentElement.style.setProperty("--cp-sticky-tools", h + "px");
        };
        syncTools();
        if (window.ResizeObserver) new ResizeObserver(syncTools).observe(tools);
        else window.addEventListener("resize", syncTools);
      }
      $tabHelpers
      // The row pointing at an id, found by COMPARING attribute values rather than by building a
      // selector out of DOM text (CodeQL `js/xss-through-dom`, and the rule the backdrop viewer
      // already follows).
      function rowFor(id) {
        var found = null;
        treeGroups.forEach(function (g) { if (g.getAttribute("data-group") === id) found = g; });
        return found;
      }
      function markGroup(row) {
        treeGroups.forEach(function (g) {
          if (g === row) g.setAttribute("aria-current", "true");
          else g.removeAttribute("aria-current");
        });
      }
      // Which group and which component are open. One of each: a tree that opened every branch it
      // was ever asked about would end up listing every component in the catalog, which is the
      // wall the grid already is. The server marks the first group open, so the page arrives
      // showing components rather than needing to be prised open first.
      var openGroup = null;
      var openCard = null;
      treeLinks.forEach(function (r) {
        if (r.classList.contains("cp-tree-group") && r.getAttribute("aria-expanded") === "true") {
          openGroup = r.getAttribute("data-group");
        }
      });
      function parentRow(row) {
        var list = row.closest("ul.cp-tree-children");
        return list ? list.previousElementSibling : null;
      }
      function reflectTree() {
        treeLinks.forEach(function (r) {
          if (!r.hasAttribute("aria-expanded")) return;
          var id = r.getAttribute("data-group");
          // A branch that names no in-page target is not one of the two this tracks — the Pages
          // branch owns destinations that are elsewhere, and is written open once and left open.
          if (!id) return;
          var on = r.classList.contains("cp-tree-group") ? id === openGroup : id === openCard;
          r.setAttribute("aria-expanded", on ? "true" : "false");
        });
      }
      function openRow(row) {
        var id = row.getAttribute("data-group");
        if (row.classList.contains("cp-tree-group")) {
          openGroup = id;
          openCard = null;
        } else if (row.classList.contains("cp-tree-component")) {
          openCard = id;
          // And the group that HOLDS it. A click can only reach a component whose group is already
          // open, but a `#cp-card-…` fragment can name one in any group — and leaving `openGroup`
          // on whichever group the server expanded would scroll to the card while keeping its own
          // row, and every variant under it, collapsed out of the tree.
          var owner = parentRow(row);
          if (owner && owner.classList.contains("cp-tree-group")) {
            openGroup = owner.getAttribute("data-group");
          }
        }
        reflectTree();
        syncTabStops();
      }
      // The fragment is part of the address this page describes, and `cpUrlState` deliberately
      // preserves whatever hash is already there when it rewrites the query. So a click that moves
      // you somewhere else has to move the fragment too, or the URL keeps pointing at where you
      // WERE. `replaceState`, not push: a jump inside the page is a scroll, not a place to come
      // Back to.
      function setFragment(id) {
        var url = location.pathname + location.search + (id ? "#" + id : "");
        try { history.replaceState(history.state, "", url); } catch (e) {}
      }
      // Every row that names an in-page destination. A VARIANT row is the exception: the grid
      // folds those renders out, so it has nowhere here to jump to — it carries a plain `/p/<id>`
      // href and is left to the browser.
      treeLinks.forEach(function (row) {
        row.addEventListener("click", function (e) {
          var id = row.getAttribute("data-group");
          if (!id) return;
          var target = document.getElementById(id);
          if (!target) return;
          e.preventDefault();
          openRow(row);$selectOwningTab
          setFragment(id);
          target.scrollIntoView({ behavior: "smooth", block: "start" });
          if (row.classList.contains("cp-tree-group")) markGroup(row);
        });
      });
      // Keyboard: the tree pattern's roving focus. Visibility is read off layout rather than walked
      // by hand — a collapsed branch is `display: none`, so `offsetParent` already answers "can the
      // visitor reach this row", across all four levels and the filter's hiding at once.
      function visibleRows() {
        var rows = [];
        tree.querySelectorAll("$tabRows.cp-tree-link").forEach(function (r) {
          if (r.offsetParent !== null) rows.push(r);
        });
        return rows;
      }
      function focusRow(el) {
        if (!el) return;
        visibleRows().forEach(function (i) { i.tabIndex = i === el ? 0 : -1; });
        el.focus();
      }
      // A `role="tree"` is ONE tab stop: Tab enters it, the arrow keys move within it, Tab leaves.
      // Nothing established that until a first arrow press called `focusRow`, so every visible row
      // sat in the normal tab order until then — the whole point of the pattern, lost on the one
      // pass that matters, and worse the deeper the tree got. Keeps an existing stop if it is still
      // on screen (so a filter does not yank focus) and otherwise hands it to the first row.
      function syncTabStops() {
        var rows = visibleRows();
        if (!rows.length) return;
        var stop = null;
        rows.forEach(function (r) { if (!stop && r.tabIndex === 0) stop = r; });
        if (!stop) stop = rows[0];
        rows.forEach(function (r) { r.tabIndex = r === stop ? 0 : -1; });
      }
      cpTreeStops = syncTabStops;
      tree.addEventListener("keydown", function (e) {
        var items = visibleRows();
        var at = items.indexOf(document.activeElement);
        if (at === -1) return;
        var key = e.key;
        var next = null;
        if (key === "ArrowDown") next = items[Math.min(at + 1, items.length - 1)];
        else if (key === "ArrowUp") next = items[Math.max(at - 1, 0)];
        else if (key === "Home") next = items[0];
        else if (key === "End") next = items[items.length - 1];
        else if (key === "ArrowRight") {
          // Right opens a collapsed parent, steps into an expanded one's first child, and does
          // NOTHING on an end node. Falling through to "next visible row" on a leaf made Right a
          // second Arrow Down, walking the visitor across siblings when they asked to expand
          // something that cannot expand.
          var expanded = items[at].getAttribute("aria-expanded");
          if (expanded === "false") {
            e.preventDefault();
            if (items[at].classList.contains("cp-tree-link")) openRow(items[at]);
            else selectCollapsedTab(items[at]);
            return;
          }
          if (expanded !== "true") return;
          next = items[Math.min(at + 1, items.length - 1)];
        } else if (key === "ArrowLeft") {
          // Left closes an open branch, else climbs to the parent — the tree pattern's own rule,
          // and the only way back up once the levels are four deep. The `data-group` test excludes
          // the always-open Pages branch: closing it is not offered, and without the test Left on
          // that row would clear `openCard` and collapse whichever component IS open.
          if (
            items[at].getAttribute("aria-expanded") === "true" &&
            items[at].getAttribute("data-group")
          ) {
            e.preventDefault();
            if (items[at].classList.contains("cp-tree-group")) openGroup = null;
            else openCard = null;
            reflectTree();
            return;
          }
          next = parentRow(items[at]);
        } else return;
        if (!next) return;
        e.preventDefault();
        focusRow(next);
      });
      // What the URL's fragment names, or null when it names nothing this page has.
      //
      // Percent-DECODED before comparing: a section or group name keeps its non-ASCII letters
      // through `sectionSlug` (Kotlin's `isLetterOrDigit` is Unicode-aware), so the id in the DOM
      // is the raw text while browsers hand back `location.hash` encoded. Undecoded, a shared link
      // to an accented or CJK group would match no row at all and silently do nothing. A malformed
      // escape sequence throws, and is simply not a fragment this page knows.
      function hashTarget() {
        var id = location.hash ? location.hash.slice(1) : "";
        try { id = decodeURIComponent(id); } catch (e) { return null; }
        if (!id) return null;
        var tab = null;
        var row = null;
        treeLinks.forEach(function (g) {
          if (!row && g.getAttribute("data-group") === id) {
            tab = g.getAttribute("data-tab");
            row = g;
          }
        });
        if (!row) {
          tabBtnsForHash.forEach(function (t) {
            if (t.getAttribute("aria-controls") === id) tab = t.getAttribute("data-tab");
          });
        }
        return row || tab ? { tab: tab, row: row, id: id } : null;
      }
      syncTabStops();
      var landing = hashTarget();
      if (landing) {
        if (landing.row) openRow(landing.row);
        applyLandingTab(landing);
        setTimeout(function () {
          var el = document.getElementById(landing.id);
          if (el) el.scrollIntoView({ block: "start" });
          if (landing.row && landing.row.classList.contains("cp-tree-group")) markGroup(landing.row);
        }, 0);
      }$sectionClicks$popPrecedence
      // Scroll-spy: mark the group whose cards are on screen, so the tree says where you are rather
      // than only where you last clicked. Additive — with no `IntersectionObserver` the marking
      // simply follows clicks.
      if (window.IntersectionObserver) {
        var onScreen = [];
        var spy = new IntersectionObserver(function (entries) {
          entries.forEach(function (en) {
            var at = onScreen.indexOf(en.target);
            if (en.isIntersecting) { if (at === -1) onScreen.push(en.target); }
            else if (at !== -1) onScreen.splice(at, 1);
          });
          // The highest sub-group still in the band is the one being read.
          var top = null;
          onScreen.forEach(function (el) {
            if (el.hidden) return;
            if (!top || el.getBoundingClientRect().top < top.getBoundingClientRect().top) top = el;
          });
          if (top) markGroup(rowFor(top.id));
        // A band, not the whole viewport: the top inset clears the sticky header, and the bottom
        // one keeps the LAST sub-group from claiming the mark the moment its first row appears.
        // Deliberately generous at the bottom — a narrow strip near the top would leave nothing
        // marked at all on first paint, since a catalog's first sub-group starts a header, a
        // provenance strip and a toolbar down the page.
        }, { rootMargin: "-64px 0px -20% 0px" });
        document.querySelectorAll(".cp-subgroup[id]").forEach(function (g) { spy.observe(g); });
      }
    })();
    """
      .trimIndent()
  }

  /**
   * A heartbeat telling the server a visitor is still on this catalog's pages.
   *
   * The server reaps idle sessions (and their daemon) after ten minutes measured in requests, but a
   * reader of cached pages makes none. A ping every [PRESENCE_INTERVAL_SECONDS] keeps the session
   * warm; see `handlePresence`.
   * - **Only while visible**, pinging immediately on `visibilitychange` to visible.
   * - **Fires on arrival**, since catalogs aren't warmed at boot.
   * - **Errors ignored**; the next ping retries.
   */
  /**
   * [presenceScript] as a standalone `<script>` tag for pages with no script to splice into (the
   * viewer). Empty when there is no presence URL.
   */
  private fun presenceScriptTag(presenceUrl: String): String {
    val script = presenceScript(presenceUrl)
    if (script.isEmpty()) return ""
    // Emitted with the body's indentation and its own leading newline, interpolated adjacent to the
    // previous tag, so the empty case leaves no blank line and `trimIndent` sees a consistent
    // indent.
    val indented = script.lines().joinToString("") { if (it.isEmpty()) "\n" else "\n        $it" }
    return "\n      <script>(function () {$indented\n      })();</script>"
  }

  private fun presenceScript(presenceUrl: String): String {
    if (presenceUrl.isEmpty()) return ""
    return """

      var presenceUrl = ${WebEscaping.jsString(presenceUrl)};
      function ping() {
        if (document.visibilityState !== "visible") return;
        fetch(presenceUrl, { method: "POST", credentials: "same-origin", keepalive: true })
          .catch(function () {});
      }
      setInterval(ping, ${PRESENCE_INTERVAL_SECONDS} * 1000);
      document.addEventListener("visibilitychange", ping);
      // Fired on arrival, not only every interval. Catalogs are no longer warmed at boot (see
      // ServeCatalogLiveHost.eagerWarmOnOpen), so this ping is what gets a daemon ready for the
      // catalog the visitor actually opened — while they read the grid, rather than when they
      // first click a theme and wait out a cold start.
      ping();

      // Render-server badge. Catalogs open their daemon on first real use, so whether one is up is
      // now a genuine question with a visible answer — a theme switch is instant against a warm
      // daemon and pays a cold start against none. Same URL family as the presence ping, and the
      // endpoint reads through `peekHost`, so polling it never wakes what it is reporting on.
      // The badge lives INSIDE the header's Status link (see ServeWeb.siteHeader) — the count and
      // that link answer the same question, so they are one control: the number is the summary and
      // the link is where the detail is. It is server-rendered and hidden, so filling it never
      // moves the brand or the nav.
      //
      // That slot is also the feature switch. Catalog mode's header carries no nav, so it has
      // neither the Status link nor the slot, and a catalog visitor has no `/status` page to read a
      // count against — a number with nothing to link to is not a summary of anything. This used to
      // create the span and append it to `<header>` when the slot was missing, which made it a third
      // item in that two-column grid: it landed in an implicit second row, stretched across the
      // `1fr` track, and painted the count as a full-width bar under the brand. So absence of the
      // slot now disables the badge outright rather than inventing somewhere to put it — which also
      // stops Catalog mode polling every 20s for an answer it does not display.
      var daemonUrl = presenceUrl.replace("/api/presence", "/api/daemons");
      // Read synchronously rather than on first paint: this script is emitted deep in the body, long
      // after the header, so the slot either exists now or is not on this page at all.
      var daemonBadge = document.getElementById("cp-daemon-status");
      function paintDaemonStatus(state) {
        var el = daemonBadge;
        if (!el) return;
        if (!state) { el.hidden = true; return; }
        el.hidden = false;
        // "not running" is a normal resting state, not a fault — a catalog nobody has rendered on
        // simply has no process yet. Word it so it doesn't read as an error.
        var count = state.instances || 0;
        var catalogDetail = state.running
          ? count + (count === 1 ? " instance" : " instances") +
            ", " + (state.activeStreams || 0) + " live"
          : "not running (starts on demand)";
        if (state.poolCapacity > 0)
          catalogDetail += ", pool " + state.pooled + "/" + state.poolCapacity;
        var overallDetail = (state.overallRunning || 0) + " catalogs running, " +
          (state.overallActiveStreams || 0) + " live streams";
        if (state.liveSeatsTotal > 0)
          overallDetail += ", " + (state.liveSeatsTotal - state.liveSeatsAvailable) + "/" +
            state.liveSeatsTotal + " seats used";
        el.innerHTML = '<span class="cp-daemon-dot" aria-hidden="true"></span>' + count;
        el.setAttribute("data-cp-daemon-running", state.running ? "1" : "0");
        // The badge itself is decoration on a link, so it says nothing to a screen reader: an
        // `aria-label` here would be appended to the LINK's accessible name, renaming "Status" to
        // a sentence about instance counts — and renaming it again on every poll. The name stays
        // "Status"; the detail rides on the link's own title (and behind it, the page it opens),
        // which is the same place a sighted visitor reads it from.
        var host = el.closest("a") || el;
        host.title = "Render servers — this catalog: " + catalogDetail +
          "\nOverall server: " + overallDetail;
      }
      function pollDaemons() {
        if (!daemonUrl || !daemonBadge || document.visibilityState !== "visible") return;
        fetch(daemonUrl, { credentials: "same-origin" })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(paintDaemonStatus)
          .catch(function () {});
      }
      setInterval(pollDaemons, 20000);
      document.addEventListener("visibilitychange", pollDaemons);
      pollDaemons();
      // A theme switch is exactly when the daemon comes up, so refresh the badge shortly after one.
      document.addEventListener("click", function (e) {
        if (e.target && e.target.closest && e.target.closest(".cp-theme-btn")) {
          setTimeout(pollDaemons, 1500);
        }
      });
    """
      .trimIndent()
  }

  /**
   * Viewer half of the catalog-scoped sticky Theme control; values match the landing (`light`,
   * `dark`, `theme:<provider FQN>`).
   *
   * The memory is `sessionStorage` (`chrome/themeMemory.ts`), so it is per tab and applies
   * uniformly to every variant; a tab opened from a shared link remembers nothing and so reproduces
   * the link.
   */
  private fun viewerThemeStickyScript(themeStorageKey: String): String =
    """
    (function () {
      var el = document.getElementById("cp-theme");
      if (!el) return;
      // Runs before viewer.js' initial render.
      var root = document.querySelector(".cp-viewer");
      // The page's own URL outranks the remembered choice: `?themeProvider=` / `?uiMode=` is there
      // because someone picked it (or was handed the link), so a bookmarked viewer opens on the
      // theme it was bookmarked in — including on an explicit __light/__dark preview.
      var params = new URLSearchParams(location.search);
      var provider = params.get("themeProvider");
      var uiMode = params.get("uiMode");
      var urlChoice = provider ? "theme:" + provider
        : (uiMode === "light" || uiMode === "dark" ? uiMode : "");
      var urlOption = null;
      Array.prototype.forEach.call(el.options, function (o) { if (urlChoice && o.value === urlChoice) urlOption = o; });
      // A choice that merely names the preview's baked theme is DISPLAYED but not marked active:
      // it asks for nothing, so it must not read as a pinned override. `?uiMode=light` on a
      // light-baked preview is the case that mattered — the light/dark toggle leaves the parameter
      // behind on the way back to light, and treating it as a pin suppressed the Figma comparison
      // for a visitor who had made no net choice. Mirrors `pinsTheme` in viewer/themeChoice.ts.
      var bakedTheme = el.getAttribute("data-default-theme") || "";
      function pinsTheme(choice) { return !!choice && choice !== bakedTheme; }
      if (urlOption) {
        el.value = urlChoice;
        if (pinsTheme(urlChoice)) el.setAttribute("data-theme-active", "1");
      }
      try {
        // Per-tab (see the KDoc): this reads back only what someone picked in THIS tab, so applying
        // it on every preview of the catalog is continuity rather than a stale global preference.
        var stored = sessionStorage.getItem("$themeStorageKey");
        var declared = stored && stored.indexOf("theme:") === 0;
        var option = null;
        Array.prototype.forEach.call(el.options, function (o) { if (o.value === stored) option = o; });
        // `!el.disabled` as well as `!option.disabled`, and they are not the same test: a static
        // bundle (or a fixed-theme specimen) disables the SELECT while its built-in light/dark
        // options stay enabled, so an option check alone let a remembered choice through onto a
        // page that cannot re-render. Nothing downstream honours it there — `syncBg` and
        // `activeThemeChoice` both fold on `el.disabled` — but the chip read as pressed and the
        // chrome followed it, framing an unchanged light snapshot in dark page colours.
        if (!urlOption && !el.disabled && option && !option.disabled && (declared || stored === "light" || stored === "dark")) {
          el.value = stored;
          if (pinsTheme(stored)) el.setAttribute("data-theme-active", "1");
        }
      } catch (e) {}
      // Publish the design-score baseline before the component bundle upgrades the comparison
      // control. A chosen theme changes the rendered side of the comparison, so the
      // server-baked score is already stale on the first paint. viewer.js keeps this attribute in
      // sync after controls change; this early write makes the element's initial read authoritative.
      if (root) {
        var atSpecBaseline = el.disabled || el.getAttribute("data-theme-active") !== "1";
        root.setAttribute("data-spec-baseline", atSpecBaseline ? "1" : "0");
      }
      // Keep the stage backing colour in step with the CHOSEN theme, so a re-render in the opposite
      // uiMode never lands a transparent sticker on a clashing surface. The server seeds
      // data-bg-theme from the baked variant (or the dark-first default); a light/dark Theme choice
      // overrides it, and clearing it reverts to that default.
      var bgDefault = (root && root.getAttribute("data-bg-theme")) || "";
      function syncBg() {
        if (!root) return;
        // Only let the Theme choice drive the stage backing when the control can actually re-render
        // (daemon or Wasm). On a static bundle the select is disabled but the seeding above may still
        // have copied a remembered value into el.value — honoring it would tint the
        // stage while ServeBundleHost keeps returning the UNCHANGED baked PNG. Keep bgDefault there.
        var selectedOption = el.options[el.selectedIndex];
        var chosen = !el.disabled && selectedOption
          ? selectedOption.getAttribute("data-theme-mode") ||
            (el.value === "light" || el.value === "dark" ? el.value : "") : "";
        var m = chosen || bgDefault;
        if (m) root.setAttribute("data-bg-theme", m);
        else root.removeAttribute("data-bg-theme");
      }
      // Round-trip every unified choice, including `theme:<provider>`, to the catalog page.
      el.addEventListener("change", function () {
        el.setAttribute("data-theme-active", "1");
        try { sessionStorage.setItem("$themeStorageKey", el.value); } catch (e) {}
        syncBg();
        // …and the page around the stage, when the Page theme setting says to follow the choice.
        if (window.cpPageTheme) window.cpPageTheme.follow(el.value);
      });
      syncBg();
    })();
    """
      .trimIndent()

  /**
   * One design system's summary on [homeIndexPage]: [system] id, [title], optional [subtitle]
   * (library coordinate), [previewCount], [trust] verdict, and [heroPreviewId] for the card (null ⇒
   * placeholder).
   */
  data class HomeSystem(
    val system: String,
    val title: String,
    val subtitle: String?,
    val previewCount: Int,
    val trust: String?,
    /** Repository that supplied this catalog; used for publisher attribution on the homepage. */
    val sourceRepo: String? = null,
    /**
     * The upstream project this catalog was rendered from when served elsewhere
     * ([ServeCatalogsConfig.Entry.importedFrom]). Decides the owner section and the "imported"
     * badge.
     */
    val importedFrom: String? = null,
    /**
     * The upstream project named by the catalog's own `catalog.json`
     * ([ServeBundleHost.catalogSource]); backs up [importedFrom] for the owner section when the
     * configuration lacks it, since [sourceRepo] is only the delivery branch.
     *
     * Never outranks [importedFrom]. Both come from the same delivery repository's trust boundary,
     * so preferring it over [sourceRepo] grants no new attribution authority.
     */
    val catalogSourceRepo: String? = null,
    val heroPreviewId: String?,
    /** Content-crop for the hero thumbnail (frames a Wear sticker to its component); null ⇒ raw. */
    val heroCrop: ContentCrop? = null,
    /**
     * The prebaked thumbnail for this card ([ServeHeroImages]): a small cropped PNG on an immutable
     * URL — the normal path. Null falls back to [heroPreviewId] + [heroCrop] (the `/render` lane
     * with a CSS clip window).
     */
    val heroImage: HeroImage? = null,
    /**
     * Whether this system's hero sits on a dark stage ([SystemDisplay.isDarkFirst]); the card then
     * carries `data-bg-theme="dark"`.
     */
    val darkStage: Boolean = false,
    /**
     * The front-page section the operator's config publishes this catalog under
     * ([ServeCatalogsConfig.Group]), or null. Only honoured when [sourceRepo] is one of
     * [HomeGroup.repos] — see [homeSections].
     */
    val group: HomeGroup? = null,
    /** Aggregate visits to this catalog/app landing page. */
    val views: Long = 0,
    /**
     * Whether this catalog publishes design references, so the card can offer
     * `compare?format=reference`. Kept separate from [designToolLabel]: a reference provider may
     * name no design tool and still be comparable.
     *
     * False by default, including catalogs not resident since startup (the front door reads a
     * suspended catalog's snapshot).
     */
    val hasReferenceComparison: Boolean = false,
    /**
     * The design tool label ("Figma", …) from the references' provider ([designToolLabel]). Label
     * only: null keeps the neutral wording. Consulted only when [hasReferenceComparison] is true.
     */
    val designToolLabel: String? = null,
    /**
     * The sibling catalog this one is a parallel rendition of, when the `compareWith` + `parallel`
     * pairing resolves and the sibling is resident with a counterpart — the same condition as the
     * compare wall's `format=parallel` rows. Null renders no chip.
     */
    val parallelComparison: ParallelComparison? = null,
    /**
     * Configured but not loaded yet (startup loads catalogs one at a time). The card holds the
     * catalog's place without a link, since `/<system>/` does not exist yet.
     */
    val loading: Boolean = false,
  )

  /**
   * The paired catalog a card can compare against: [system] is the short id the chip shows; [title]
   * is the accessible name and tooltip.
   */
  data class ParallelComparison(val system: String, val title: String)

  /**
   * One component offered by the home page's cross-catalog command palette. A compact projection
   * kept with suspended-catalog metadata so discovery wakes no daemon.
   */
  data class ComponentSearchEntry(val previewId: String, val label: String, val keywords: String)

  /**
   * Project a catalog's previews to its landing page's component cards. Theme, state, props and
   * breakpoint renders collapse to the default card, as in the grid and drawer. Renders with an
   * untagged size can't be folded and keep the size suffix below.
   */
  fun componentSearchEntries(
    previews: List<ServePreview>,
    darkFirst: Boolean = false,
  ): List<ComponentSearchEntry> {
    val primarySizes = primarySizeByComponent(previews, darkFirst)
    val cards =
      groupPreviews(
        previews.filterNot {
          it.renderFailure == null &&
            (isNonDefaultState(it) ||
              hasNonDefaultProps(it) ||
              isNonPrimarySize(it, primarySizes, darkFirst))
        }
      )
    val duplicateLabels =
      cards
        .groupingBy { previewDisplayName(it.rendered(darkFirst)) }
        .eachCount()
        .filterValues { it > 1 }
        .keys
    return cards.map { card ->
      val preview = card.rendered(darkFirst)
      val baseLabel = previewDisplayName(preview)
      // The catalog's own size name first (`192dp`, `smallRound`, `wide`), then the id token
      // vocabulary, which knows only a fixed set and would otherwise label two rows identically.
      val label =
        if (baseLabel !in duplicateLabels) baseLabel
        else
          (preview.size?.takeIf { it.isNotBlank() } ?: previewSizeVariantLabel(preview.id))?.let {
            "$baseLabel · $it"
          } ?: baseLabel
      ComponentSearchEntry(
        previewId = preview.id,
        label = label,
        keywords =
          listOfNotNull(preview.id, preview.label, preview.componentId, preview.section)
            .joinToString(" "),
      )
    }
  }

  /**
   * A front-page section: [heading], count [noun], the [repos] allowed under it, and [priority]
   * ([ServeCatalogsConfig.Group.priority], highest first).
   */
  data class HomeGroup(
    val heading: String,
    val noun: String = ServeCatalogsConfig.DEFAULT_NOUN,
    val repos: Set<String> = emptySet(),
    val priority: Int = 0,
  )

  /**
   * A prebaked hero thumbnail: its immutable `/hero/<system>/<hash>.png` [path] and CSS-pixel size.
   * The crop is in the pixels; [width]/[height] become `<img>` attributes to avoid layout shift.
   */
  data class HeroImage(val path: String, val width: Int, val height: Int)

  /**
   * A thumbnail `<img>` for [src], optionally framed to its content box ([crop]) in a `.cp-crop`
   * clip window. [extraImgAttrs] carries extra `<img>` attributes (e.g. `loading="lazy"`). All
   * numeric; [alt] is pre-escaped by the caller.
   */
  private fun thumbImg(
    src: String,
    alt: String,
    extraImgAttrs: String,
    crop: ContentCrop?,
  ): String {
    val img = "<img$extraImgAttrs alt=\"$alt\" src=\"$src\">"
    if (crop == null) return img
    // Geometry in percentages of the box, so the window scales when the aspect-ratio box shrinks on
    // a narrow card. `height` stays auto; `left` resolves against box width, `top` against its
    // height.
    val w = cropPct(crop.render.w, crop.window.w)
    val l = cropPct(crop.offset.left, crop.window.w)
    val t = cropPct(crop.offset.top, crop.window.h)
    val cropped =
      "<img$extraImgAttrs alt=\"$alt\" src=\"$src\" style=\"width:${w}%;left:${l}%;top:${t}%\">"
    // A gutter window does not hide its overflow: the pixels outside the box are the component's
    // own shadow, and the window exists to line the box up with its neighbours, not to crop it.
    val cls = if (crop.clip) "cp-crop" else "cp-crop cp-crop--bleed"
    // The window width is published relative to the display cap (`--cp-crop-w-per-cap`, with
    // `--cp-crop-max-w` as the 1x ceiling), so the stylesheet can resolve `min(max-w, w-per-cap *
    // --cp-thumb-cap)` and a narrow viewport lowers it like a plain `<img>`'s `max-height`. Only
    // width is set; `aspect-ratio` derives height. Hand-assembled crops keep a fixed-px window.
    //
    // `--cp-crop-w-per-h` is the same against the box height, for fixed-height wells (the front
    // door's hero row); it is separate because the capping axis differs between `computeGutterCrop`
    // and `computeThumbCrop`. Derived as `natBoxW * boxH / boxW`, leaving `ContentCrop` unchanged.
    val natBoxH =
      if (crop.window.w > 0) (crop.nativeWindowW.toLong() * crop.window.h / crop.window.w).toInt()
      else 0
    val sizing =
      if (crop.nativeWindowW > 0 && crop.nativeCapAxis > 0) {
        "--cp-crop-w-per-cap:${cropRatio(crop.nativeWindowW, crop.nativeCapAxis)};" +
          (if (natBoxH > 0) "--cp-crop-w-per-h:${cropRatio(crop.nativeWindowW, natBoxH)};"
          else "") +
          "--cp-crop-max-w:${crop.nativeWindowW}px"
      } else {
        "width:${crop.window.w}px"
      }
    return "<span class=\"$cls\" style=\"$sizing;aspect-ratio:${crop.window.w}/${crop.window.h}\">$cropped</span>"
  }

  /**
   * A crop dimension as a percentage of its box axis for CSS: up to 4 decimals, locale-independent,
   * trailing zeros trimmed.
   */
  private fun cropPct(numerator: Int, denominator: Int): String {
    val v = numerator * 100.0 / denominator
    val s = String.format(java.util.Locale.ROOT, "%.4f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
  }

  /**
   * A unitless CSS ratio, formatted like [cropPct]; used for the crop window's width-per-cap-pixel.
   */
  private fun cropRatio(numerator: Int, denominator: Int): String {
    val v = numerator.toDouble() / denominator
    val s = String.format(java.util.Locale.ROOT, "%.4f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
  }

  /**
   * The front door's **UI Builder** action in the header bar, linking to the builder's New design
   * chooser.
   *
   * Offered only when the builder runs for a listed catalog and somebody is signed in. A visitor
   * lacking the write capability sees a locked `<details>` explaining why
   * ([UiBuilderInvite.deniedReason]). Component-browser mode never calls this.
   */
  private fun builderHeaderAction(
    invite: UiBuilderInvite?,
    systems: List<HomeSystem>,
    suffix: String,
  ): String {
    invite ?: return ""
    if (!invite.signedIn || systems.none { it.system in invite.systems }) return ""
    if (!invite.permitted) {
      val why =
        WebEscaping.htmlEscape(
          invite.deniedReason.takeIf { it.isNotBlank() }
            ?: "Your account does not carry the access creating a design needs."
        )
      return "<details class=\"cp-action-note cp-site-builder-note\">" +
        "<summary class=\"cp-action-chip cp-action-chip--locked\" " +
        "aria-label=\"UI Builder is unavailable — why\">" +
        "UI Builder<span class=\"cp-action-chip-hint\" aria-hidden=\"true\">why?</span></summary>" +
        "<span class=\"cp-action-note-body\">$why</span></details>"
    }
    val href = WebEscaping.htmlEscape("${invite.editorHref}$suffix")
    return "<a class=\"cp-site-builder-link\" href=\"$href\">" +
      "<span aria-hidden=\"true\">\u270e</span>UI Builder</a>"
  }

  /**
   * The public server's **front door**: an index of published systems, each card with a preview,
   * title + library, trust badge and a link to `/<system>/`. Non-catalog `serve` keeps
   * [landingPage].
   *
   * Card imagery is prebaked ([HeroImage] / [ServeHeroImages]) on immutable URLs, so the page costs
   * only its HTML.
   *
   * [systems] are grouped into Compose design systems, Android's Compose samples (fetched from the
   * `yschimke/compose-samples` fork but representing `android/compose-samples`), the `yschimke`
   * organization, and "Other". `--catalogs-unlisted` catalogs are deliberately not indexed here.
   */
  fun homeIndexPage(
    systems: List<HomeSystem>,
    token: String,
    isPublic: Boolean = false,
    /**
     * Running server version (`SERVE_VERSION`) for the footer. Null omits it; the fixture golden
     * passes a fixed string.
     */
    version: String? = null,
    /** Absolute page + representative hero URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    githubAuth: GitHubAuthStatus? = null,
    /**
     * What this visitor may do with the UI builder ([UiBuilderInvite]). Null when the host runs no
     * builder.
     */
    uiBuilder: UiBuilderInvite? = null,
    componentBrowser: Boolean = false,
  ): String {
    val headerSessionSettings = if (componentBrowser) "" else githubSessionSettings(githubAuth)
    // Public routes are open — no token param on the cards; a token-gated box keeps it.
    val tokenParam =
      if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
    val suffix = querySuffix(tokenParam)
    val headerAction =
      if (componentBrowser) ""
      else
        listOf(builderHeaderAction(uiBuilder, systems, suffix), githubAuthControl(githubAuth))
          .filter { it.isNotEmpty() }
          .joinToString("\n          ")
    /**
     * The card's comparison destinations: the design tool's name, or the sibling's short system id
     * (its title would be too wide). The full title stays in the accessible name and tooltip.
     */
    fun compareChips(s: HomeSystem, sysSeg: String): List<String> {
      fun chip(format: String, text: String, spoken: String): String {
        val query =
          listOf("format=$format", tokenParam).filter { it.isNotEmpty() }.joinToString("&")
        val href = WebEscaping.htmlEscape("/$sysSeg/compare?$query")
        // The visible text appears verbatim in the accessible name (WCAG 2.5.3 Label in Name); both
        // when they differ, one phrase when they don't.
        val named = if (text == spoken) spoken else "$text — $spoken"
        val described = WebEscaping.htmlEscape("${s.title}: compare to $named")
        return "<a class=\"cp-action-chip cp-action-chip--compact\" href=\"$href\" " +
          "aria-label=\"$described\" title=\"$described\">${WebEscaping.htmlEscape(text)}</a>"
      }
      val out = mutableListOf<String>()
      if (s.hasReferenceComparison) {
        val tool = s.designToolLabel?.takeIf { it.isNotBlank() }
        out += chip("reference", tool ?: "design references", tool ?: "design references")
      }
      s.parallelComparison?.let { out += chip("parallel", it.system, it.title) }
      return out
    }

    /**
     * The card's **compare to Figma** action: a chip under the preview count deep-linking the
     * catalog's comparison page in `reference` format.
     *
     * The label names the design tool, falling back to "compare to design references"; whether the
     * action exists is [HomeSystem.hasReferenceComparison], never the label. The accessible name
     * adds the catalog title while keeping the visible text intact (WCAG 2.5.3).
     *
     * The card is a `<div>` whose `.cp-sys-open` link stretches over the tile, because links can't
     * nest; the chip sits above that overlay. Suppressed in component-browser mode.
     */
    fun compareAction(s: HomeSystem, sysSeg: String): String {
      if (componentBrowser) return ""
      val chips = compareChips(s, sysSeg)
      if (chips.isEmpty()) return ""
      // One "Compare to" label for the row, then chips naming only the destination, so two chips
      // fit on one line. A `<span>` rather than a heading; each link's accessible name still
      // carries the full sentence.
      return "<span class=\"cp-sys-compare\">" +
        "<span class=\"cp-sys-compare-label\" aria-hidden=\"true\">Compare to</span>" +
        chips.joinToString("") +
        "</span>"
    }

    /**
     * The card's action row, or nothing when there are no actions. The UI Builder entry is in the
     * header ([builderHeaderAction]). `.cp-sys-actions` passes pointer events to the tile link.
     */
    fun cardActions(s: HomeSystem, sysSeg: String): String {
      val compare = compareAction(s, sysSeg)
      if (compare.isEmpty()) return ""
      return "\n            <div class=\"cp-sys-actions\">$compare</div>"
    }
    fun loadingCard(s: HomeSystem): String {
      val title = WebEscaping.htmlEscape(s.title)
      val sysId = WebEscaping.htmlEscape(s.system)
      val technicalId =
        if (componentBrowser) "" else "\n            <div class=\"cp-id\">$sysId</div>"
      val searchAttr =
        " data-browser-search=\"${WebEscaping.htmlEscape("${s.title} ${s.system} ${s.sourceRepo.orEmpty()}").lowercase()}\""
      val href = "/${WebEscaping.urlEncodeSegment(s.system)}/$suffix"
      val image =
        s.heroImage?.let { hero ->
          "<img loading=\"eager\" decoding=\"async\" width=\"${hero.width}\" height=\"${hero.height}\" alt=\"$title preview\" src=\"${WebEscaping.htmlEscape(hero.path)}$suffix\">"
        } ?: "<span class=\"cp-sys-noimg\">loading…</span>"
      return """
      <a href="$href" class="cp-card cp-sys cp-sys-loading" aria-busy="true"$searchAttr data-cp-system="$sysId">
        <div class="cp-imgwrap">$image</div>
        <div class="cp-meta">
          <div class="cp-sys-title">$title</div>$technicalId
          <div class="cp-sys-foot" role="status">Loading catalog…</div>
        </div>
      </a>
      """
        .trimIndent()
    }
    fun card(s: HomeSystem): String {
      if (s.loading) return loadingCard(s)
      val sysSeg = WebEscaping.urlEncodeSegment(s.system)
      val title = WebEscaping.htmlEscape(s.title)
      val sysId = WebEscaping.htmlEscape(s.system)
      val hero = s.heroImage
      val img =
        if (hero != null) {
          // Prebaked, cropped thumbnail on an immutable URL. `eager` because these images are the
          // page; width/height reserve the box.
          "<img loading=\"eager\" decoding=\"async\" width=\"${hero.width}\" height=\"${hero.height}\"" +
            " alt=\"$title preview\" src=\"${WebEscaping.htmlEscape(hero.path)}$suffix\">"
        } else if (s.heroPreviewId != null) {
          // Fallback: the live `/render` lane with a CSS clip window, for a catalog whose hero
          // couldn't be prebaked.
          val idSeg = WebEscaping.urlEncodeSegment(s.heroPreviewId)
          thumbImg(
            src = "/$sysSeg/render/$idSeg.png$suffix",
            alt = "$title preview",
            extraImgAttrs = " loading=\"lazy\"",
            crop = s.heroCrop,
          )
        } else {
          "<span class=\"cp-sys-noimg\">no preview</span>"
        }
      val desc =
        s.subtitle
          ?.takeIf { it.isNotBlank() }
          ?.let { "\n            <div class=\"cp-sys-desc\">${WebEscaping.htmlEscape(it)}</div>" }
          ?: ""
      val provenance =
        if (!componentBrowser) ""
        else
          s.sourceRepo
            ?.takeIf { it.isNotBlank() }
            ?.let {
              "\n            <div class=\"cp-browser-provenance\">${WebEscaping.htmlEscape(it)}</div>"
            } ?: ""
      // Says whose work this is: an imported catalog is someone else's project served from a
      // staging repo.
      val importedBadge =
        s.importedFrom
          ?.takeIf { it.isNotBlank() }
          ?.let {
            "\n            <div class=\"cp-sys-badge cp-sys-imported\">" +
              "imported from ${WebEscaping.htmlEscape(it)}</div>"
          } ?: ""
      val technicalId =
        if (componentBrowser) "" else "\n            <div class=\"cp-id\">$sysId</div>"
      val totals =
        if (componentBrowser) ""
        else
          "\n            <div class=\"cp-sys-foot\">${counted(s.previewCount, "preview(s)")}" +
            (if (s.views > 0) " · ${formatViews(s.views)}" else "") +
            "</div>"
      // A dark-first system's hero uses the dark stage (same `data-bg-theme` hook as the grid and
      // viewer).
      val bg = if (s.darkStage) " data-bg-theme=\"dark\"" else ""
      val searchAttr =
        " data-browser-search=\"${WebEscaping.htmlEscape("${s.title} ${s.system} ${s.subtitle.orEmpty()} ${s.sourceRepo.orEmpty()}").lowercase()}\""
      // The catalog id as data for the search; `.cp-id` is absent in component-browser mode.
      val systemAttr = " data-cp-system=\"$sysId\""
      return """
      <div class="cp-card cp-sys"$bg$searchAttr$systemAttr>
        <div class="cp-imgwrap">$img</div>
        <div class="cp-meta">
          <div class="cp-sys-title"><a class="cp-sys-open" href="/$sysSeg/$suffix">$title</a>${homeTrustBadge(s.trust)}</div>$technicalId$desc$importedBadge$provenance${cardActions(s, sysSeg)}$totals
        </div>
      </div>
      """
        .trimIndent()
    }
    // Headings and nouns come from operator config (and, for the fallback sections, from a
    // catalog's own provenance), so they're escaped like any other data on the page.
    fun sectionTitle(heading: String, list: List<HomeSystem>, noun: String): String {
      val head = WebEscaping.htmlEscape(heading)
      val count = WebEscaping.htmlEscape(counted(list.size, noun))
      return """
      <div class="cp-section-title">
        <h1 class="cp-head">$head</h1>
        ${if (componentBrowser) "" else "<span class=\"cp-section-count\">$count</span>"}
      </div>
      """
        .trimIndent()
    }
    fun section(heading: String, list: List<HomeSystem>, noun: String, gridId: String): String =
      sectionTitle(heading, list, noun) +
        "\n" +
        """
      <div class="cp-grid cp-syslist" id="$gridId">
      ${list.joinToString("\n") { card(it) }}
      </div>
      """
          .trimIndent()

    /**
     * A run of small ([COMPACT_SECTION_MAX] or fewer) sections side by side in one band. Each unit
     * spans as many columns as it has cards, so cards align with the full-width sections around it.
     */
    fun band(row: List<Pair<HomeSection, String>>): String {
      val units =
        row.joinToString("\n") { (s, gridId) ->
          val span = s.systems.size.coerceAtMost(COMPACT_SECTION_MAX)
          """
      <section class="cp-section-unit" data-span="$span">
      ${sectionTitle(s.heading, s.systems, s.noun)}
      <div class="cp-grid cp-syslist cp-section-unit-grid" id="$gridId" data-cols="$span">
      ${s.systems.joinToString("\n") { card(it) }}
      </div>
      </section>
      """
            .trimIndent()
        }
      return "<div class=\"cp-section-band\">\n$units\n</div>"
    }
    val sections = homeSections(systems)
    /**
     * The front door's search in two halves: a `⌕` button in the header that expands a field
     * ([headerSearchControl] via [siteHeader]), and the results it drives in the body — the
     * empty-state line, component results, and the script tying them together.
     */
    val catalogSearch =
      if (systems.isEmpty()) ""
      else
        """
        <div id="cp-home-components" class="cp-home-components" hidden>
          <h2 class="cp-home-components-head">Components</h2>
          <ul class="cp-home-component-list"></ul>
        </div>
        <p id="cp-browser-catalog-empty" class="cp-empty" hidden>Nothing matches your search.</p>
        """
          .trimIndent() + "\n" + homeSearchScript()
    val body =
      if (systems.isEmpty()) {
        "<h1 class=\"cp-head\">Design Systems</h1>\n" +
          "<p class=\"cp-sub\">No design systems are configured on this server.</p>"
      } else {
        catalogSearch +
          "\n" +
          // Grid ids stay keyed to the section's position in `homeSections` order, so banding two
          // sections onto one row doesn't renumber the anchors of the sections after them.
          homeRows(
              sections.mapIndexed { index, s ->
                s to if (index == 0) "cp-grid" else "cp-grid-$index"
              }
            ) {
              it.first.systems.size
            }
            .joinToString("\n") { row ->
              if (row.size == 1) {
                val (s, gridId) = row.single()
                section(s.heading, s.systems, s.noun, gridId)
              } else {
                band(row)
              }
            }
      }
    val globalComponents =
      if (systems.isEmpty()) ""
      else "<span hidden data-cp-global-components=\"/api/components$suffix\"></span>\n"
    return document(
      title = "$HOME_TITLE — compose-preview",
      unfurlTitle = HOME_TITLE,
      unfurlDescription = homeUnfurlDescription(systems.size),
      unfurl = unfurl,
      navSuffix = suffix,
      headerAction = headerAction,
      headerSessionSettings = headerSessionSettings,
      headerSearch = if (systems.isEmpty()) "" else headerSearchControl(),
      version = version,
      body = body + if (globalComponents.isEmpty()) "" else "\n$globalComponents",
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
    )
  }

  /**
   * The header bar's collapsed search: a `⌕` button and the field it expands. The field is rendered
   * up front and `hidden`, so it exists for the script and find-in-page. `aria-expanded` /
   * `aria-controls` make the disclosure legible; the input keeps the id the script looks up.
   */
  private fun headerSearchControl(): String =
    """
    <div class="cp-site-search">
      <button type="button" class="cp-site-search-btn" id="cp-site-search-toggle"
        aria-expanded="false" aria-controls="cp-site-search-field"
        title="Search catalogs and components"
        aria-label="Search catalogs and components"><span aria-hidden="true">⌕</span></button>
      <div class="cp-site-search-field" id="cp-site-search-field" hidden>
        <input id="cp-browser-catalog-search" class="cp-site-search-input" type="search"
          autocomplete="off" spellcheck="false" placeholder="Search catalogs and components"
          aria-label="Search catalogs and components">
      </div>
    </div>
    """
      .trimIndent()

  /**
   * The front door's filter.
   *
   * **Catalogs** are matched in the DOM against each card's `data-browser-search` blob.
   * **Components** are matched against `/api/components` (the command palette's index,
   * `data-cp-global-components`), fetched once on the first keystroke; the element carrying that
   * URL is emitted at the end of the body, so it is looked up at fetch time, not parse time.
   * Matching components are listed as links, and a card stays visible when one of its components
   * matched.
   *
   * The list is built with `createElement`/`textContent`, never `innerHTML`, because the JSON is
   * catalog-supplied. A failed fetch degrades to catalog-only matching and is not retried.
   */
  private fun homeSearchScript(): String =
    "<script>" +
      """
      (function(){
      var q=document.getElementById("cp-browser-catalog-search"),
      e=document.getElementById("cp-browser-catalog-empty"),
      t=document.getElementById("cp-site-search-toggle"),
      f=document.getElementById("cp-site-search-field"),
      r=document.getElementById("cp-home-components"),
      l=r&&r.querySelector(".cp-home-component-list"),
      index=null,pending=false;
      if(!q)return;
      function expand(open){
      if(!t||!f)return;
      t.setAttribute("aria-expanded",open?"true":"false");
      f.hidden=!open;
      if(open){q.focus();}else{q.value="";apply();}
      }
      if(t&&f){
      t.addEventListener("click",function(){expand(f.hidden);});
      q.addEventListener("keydown",function(ev){if(ev.key==="Escape"){expand(false);t.focus();}});
      }
      function sourceUrl(){
      var s=document.querySelector("[data-cp-global-components]");
      return s&&s.getAttribute("data-cp-global-components");
      }
      function load(){
      if(index||pending)return;
      var url=sourceUrl();
      if(!url)return;
      pending=true;
      fetch(url,{headers:{Accept:"application/json"}}).then(function(res){
      if(!res.ok)throw new Error("HTTP "+res.status);
      return res.json();
      }).then(function(body){index=(body&&body.components)||[];apply();},function(){index=[];})
      .then(function(){pending=false;});
      }
      function hitsFor(n){
      if(!index||!n)return [];
      return index.filter(function(c){
      return ((c.label||"")+" "+(c.keywords||"")).toLowerCase().indexOf(n)>=0;
      });
      }
      function apply(){
      var n=q.value.trim().toLowerCase(),hits=hitsFor(n),owners={},shown=0;
      hits.forEach(function(c){owners[c.catalog]=true;});
      document.querySelectorAll(".cp-sys").forEach(function(c){
      var hit=!n||(c.getAttribute("data-browser-search")||"").indexOf(n)>=0||
      owners[c.getAttribute("data-cp-system")]===true;
      c.hidden=!hit;if(hit)shown++;
      });
      document.querySelectorAll(".cp-section-title").forEach(function(h){
      var g=h.nextElementSibling;
      h.hidden=!!g&&!Array.prototype.some.call(g.children,function(c){return !c.hidden;});
      });
      document.querySelectorAll(".cp-section-unit").forEach(function(u){
      u.hidden=!u.querySelector(".cp-sys:not([hidden])");
      });
      if(r&&l){
      l.textContent="";
      hits.slice(0,24).forEach(function(c){
      var li=document.createElement("li"),a=document.createElement("a"),
      name=document.createElement("span"),from=document.createElement("span");
      a.className="cp-home-component";a.href=c.href;
      name.className="cp-home-component-name";name.textContent=c.label;
      from.className="cp-home-component-catalog";from.textContent=c.catalogTitle||c.catalog;
      a.appendChild(name);a.appendChild(from);li.appendChild(a);l.appendChild(li);
      });
      r.hidden=hits.length===0;
      }
      if(e)e.hidden=!(n&&shown===0&&hits.length===0);
      }
      q.addEventListener("input",function(){load();apply();});
      })();
      """
        .trimIndent()
        .replace("\n", "") +
      "</script>"

  /**
   * What the front door calls itself in `<title>`, `og:title` and its unfurl card headline
   * ([ServeSocialCard]). One constant so they agree; the product name stays in `og:site_name`, the
   * `<title>` suffix and the card wordmark.
   */
  const val HOME_TITLE = "Design systems"

  /** The front door's `og:description`. */
  fun homeUnfurlDescription(systemCount: Int): String =
    "Browse $systemCount published Compose design system and app catalogs."

  /**
   * The line under the headline on the front door's unfurl card — a stat line rather than
   * [homeUnfurlDescription], which clients already show beside the card. Both counts change only on
   * publish, as [ServeSocialCard.Spec] requires of anything in its cache key.
   */
  fun homeCardSubtitle(systems: List<HomeSystem>): String {
    val previews = systems.sumOf { it.previewCount }
    return "${systems.size} ${if (systems.size == 1) "catalog" else "catalogs"} · " +
      "$previews ${if (previews == 1) "preview" else "previews"}"
  }

  /**
   * The line under the headline on a catalog's unfurl card; the heading is already the headline.
   */
  fun catalogCardSubtitle(previewCount: Int): String =
    "$previewCount Compose ${if (previewCount == 1) "preview" else "previews"}"

  /**
   * A catalog's display name, falling back to the module label. Shared by [landingPage] and its
   * unfurl card so the two cannot drift.
   */
  fun catalogHeading(displayTitle: String?, moduleLabel: String): String =
    displayTitle?.takeIf { it.isNotBlank() } ?: moduleLabel

  /** A catalog page's `og:description`, and the text under its card's headline. */
  fun catalogUnfurlDescription(previewCount: Int, heading: String): String =
    "$previewCount Compose previews in $heading"

  /**
   * One publisher-grouped section of the front page: its heading, its cards, and its count noun.
   */
  data class HomeSection(val heading: String, val systems: List<HomeSystem>, val noun: String)

  /** Render legacy operator nouns such as `catalog(s)` as real singular/plural copy. */
  private fun counted(count: Int, noun: String): String {
    val singular = noun.replace("(s)", "")
    val plural = if (noun.contains("(s)")) singular + "s" else noun
    return "$count ${if (count == 1) singular else plural}"
  }

  /**
   * Group the published catalogs by **publisher** for the front-page sections.
   *
   * The section comes from operator config ([ServeCatalogsConfig]), not code. A declared group is a
   * claim checked against provenance: [HomeSystem.sourceRepo] must be one of [HomeGroup.repos]. The
   * catalog id is claimable by anyone, and the trust verdict names the fetch branch (a delivery
   * detail — see [ServeCatalogsConfig.Entry.attributionRepos]), so neither works alone.
   *
   * A failed or missing claim falls back to an **owner** section, read from the first present of
   * [HomeSystem.importedFrom], [HomeSystem.catalogSourceRepo] and [HomeSystem.sourceRepo]; no
   * provenance at all goes to "Other" (never promoted).
   *
   * Sections are ordered by [HomeGroup.priority] (highest first;
   * [ServeCatalogsConfig.Group.priority]), then configured order. Unprioritised and owner sections
   * sit at 0; sections sharing a heading take the highest priority; "Other" is always last.
   */
  internal fun homeSections(systems: List<HomeSystem>): List<HomeSection> {
    val grouped = LinkedHashMap<String, MutableList<HomeSystem>>()
    val nouns = LinkedHashMap<String, String>()
    val priorities = LinkedHashMap<String, Int>()
    for (s in systems) {
      // The claim only holds when the bytes came from a repo the operator named for this entry.
      val claimed = s.group?.takeIf { g -> s.sourceRepo != null && s.sourceRepo in g.repos }
      // An import is grouped by the project it came from, not the staging repo; the catalog's
      // declared source stands in when `importedFrom` is missing.
      val heading =
        claimed?.heading ?: ownerHeading(s.importedFrom ?: s.catalogSourceRepo ?: s.sourceRepo)
      grouped.getOrPut(heading) { mutableListOf() } += s
      nouns.putIfAbsent(heading, claimed?.noun ?: ServeCatalogsConfig.DEFAULT_NOUN)
      // Sections merge on the heading (free operator text), so the merged section takes the highest
      // priority any claim declares, regardless of registration order.
      priorities.merge(heading, claimed?.priority ?: 0, ::maxOf)
    }
    val sections = grouped.map { (heading, list) ->
      HomeSection(heading, list, nouns.getValue(heading))
    }
    // sortedByDescending is stable, so equal priorities keep their first-appearance order.
    val ordered = sections.sortedByDescending { priorities.getValue(it.heading) }
    // "Other" is the unattributed bucket, so it reads last regardless of when it first appeared.
    return ordered.filterNot { it.heading == OTHER_HEADING } +
      ordered.filter { it.heading == OTHER_HEADING }
  }

  /**
   * The most catalogs a section can hold and still share a row: a unit is as many columns wide as
   * it has cards, so at three the band is already full.
   */
  internal const val COMPACT_SECTION_MAX = 2

  /**
   * Groups the front page's sections into rows: a run of small ([COMPACT_SECTION_MAX] or fewer)
   * sections shares a row, everything else keeps its own. Only adjacent sections are banded,
   * preserving [homeSections]' order; a run of one is left alone.
   */
  internal fun <T> homeRows(sections: List<T>, size: (T) -> Int): List<List<T>> {
    val rows = mutableListOf<List<T>>()
    val run = mutableListOf<T>()
    fun flush() {
      when (run.size) {
        0 -> Unit
        1 -> rows += listOf(run.single())
        else -> rows += run.toList()
      }
      run.clear()
    }
    for (s in sections) {
      if (size(s) <= COMPACT_SECTION_MAX) {
        run += s
      } else {
        flush()
        rows += listOf(s)
      }
    }
    flush()
    return rows
  }

  /** The heading an ungrouped catalog falls back to: its repo owner's, else the "Other" bucket. */
  private fun ownerHeading(sourceRepo: String?): String {
    val owner = sourceRepo?.substringBefore('/')?.takeIf { it.isNotBlank() && it != sourceRepo }
    return if (owner == null) OTHER_HEADING else "$owner repositories"
  }

  /** The catch-all section for catalogs carrying no usable provenance. */
  private const val OTHER_HEADING = "Other"

  /**
   * A styled **404** for a browser following a dead link to a catalog or preview page, with a way
   * home. Render/API lanes keep their plain-text 404. The back link is built like [backButton] so
   * it keeps the token on a gated ([isPublic] false) server.
   */
  fun notFoundPage(
    message: String,
    token: String,
    isPublic: Boolean,
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    /**
     * The catalog whose colours and name this page wears on a **top-level site** ([ServeSites]), so
     * `/status` and 404s match the rest of the hostname. Empty on the main host.
     */
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    componentBrowser: Boolean = false,
    githubAuth: GitHubAuthStatus? = null,
  ): String {
    val suffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    return document(
      title = "Not found — compose-preview",
      unfurlDescription = message,
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
      headerSessionSettings = if (componentBrowser) "" else githubSessionSettings(githubAuth),
      body =
        """
        <h1 class="cp-head">Not found</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)}</p>
        <a class="cp-back" href="/$suffix">${
          // On a site there is no index of systems to go back to — `/` is this catalog.
          if (siteName.isBlank()) "← All design systems" else "← Back"
        }</a>
        """
          .trimIndent(),
    )
  }

  /**
   * A styled explanation for a browser that reached a surface its credential does not open (today,
   * `POST /ui-builder/designs` refusing to create). Scripts get `text/plain`; a person following a
   * form gets the same status with chrome, the reason, and what to do.
   *
   * [message] is the reason in the visitor's terms; [signInHref] is offered only when signing in
   * could change the answer.
   */
  fun accessDeniedPage(
    message: String,
    token: String,
    isPublic: Boolean,
    signInHref: String? = null,
    version: String? = null,
    githubAuth: GitHubAuthStatus? = null,
    componentBrowser: Boolean = false,
  ): String {
    val suffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val signIn =
      signInHref
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "\n        <p><a class=\"cp-action-chip cp-action-chip--primary\" " +
            "href=\"${WebEscaping.htmlEscape(it)}\">$GITHUB_ICON Sign in with GitHub</a></p>"
        } ?: ""
    return document(
      title = "Access needed — compose-preview",
      unfurlDescription = message,
      version = version,
      navSuffix = suffix,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
      headerSessionSettings = if (componentBrowser) "" else githubSessionSettings(githubAuth),
      body =
        """
        <h1 class="cp-head">Access needed</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)}</p>$signIn
        <a class="cp-back" href="/$suffix">← All design systems</a>
        """
          .trimIndent(),
    )
  }

  /**
   * `GET /ui-builder/{designId}/access`: who can open one design, and the form that changes it
   * (`UpdateDesignAccessRequestV1`).
   *
   * Server-rendered rather than in the wasm editor because it needs the server's view of identity
   * (token, GitHub session or grant), and a linkable page can be sent to whoever is asking for
   * access. Every actor id is escaped.
   */
  fun uiBuilderAccessPage(
    designId: String,
    designHref: String,
    formAction: String,
    ownerActorId: String,
    /** `actorId`, `role`, `what it may do`, `granted by` — already ordered for display. */
    grants: List<UiBuilderAccessRow>,
    viewerActorId: String,
    notice: String = "",
    /** The Designs index, carried into the header's nav panel. See [siteHeader]. */
    designsHref: String = "",
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    /** Whether anyone with the link may open this design, read-only. */
    isPublic: Boolean = false,
    /** Where the design's history lives, or null when this host shows none. */
    historyHref: String? = null,
  ): String {
    val esc = WebEscaping::htmlEscape
    val noticeBlock =
      if (notice.isBlank()) "" else "<p class=\"cp-grant-withheld\">${esc(notice)}</p>"
    // The public grant is shown as the visibility switch above, not as a row naming a pseudo-actor.
    val people = grants.filterNot { ServeUiBuilderVisibility.isReservedActor(it.actorId) }
    val visibilityBlock =
      """
      <form class="cp-grant-form" method="post" action="${esc(formAction)}">
        <fieldset class="cp-grant-fieldset">
          <legend>Visibility</legend>
          <p class="cp-sub">${
            if (isPublic)
              "<strong>Public (read only).</strong> Anyone with the link can open this design, signed in or " +
                "not; only the people below can change it."
            else
              "<strong>Private.</strong> Only you and the people below can open this design."
          }</p>
        </fieldset>
        <div class="cp-grant-actions">
          <button class="${if (isPublic) "cp-grant-deny" else "cp-grant-approve"}" type="submit"
            name="visibility" value="${if (isPublic) "private" else "public"}">${
            if (isPublic) "Make private" else "Make public"
          }</button>
        </div>
      </form>
      """
        .trimIndent()
    val rows =
      if (people.isEmpty()) "<tr><td colspan=\"4\"><em>No invited collaborators.</em></td></tr>"
      else
        people.joinToString("\n") { row ->
          """
          <tr>
            <td><code>${esc(row.actorId)}</code></td>
            <td>${esc(row.role)}</td>
            <td>${esc(row.allowed)}</td>
            <td>
              <form method="post" action="${esc(formAction)}">
                <input type="hidden" name="actorId" value="${esc(row.actorId)}">
                <button class="cp-grant-deny" type="submit" name="action" value="revoke">Remove</button>
              </form>
            </td>
          </tr>
          """
            .trimIndent()
        }
    return document(
      title = "Share ${esc(designId)} — compose-preview",
      unfurlDescription = "Who can open this UI-builder design.",
      version = version,
      navSuffix = navSuffix,
      headerDesignsHref = designsHref,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">Who can open ${esc(designId)}</h1>
        <p class="cp-sub">Sharing is per design. An actor id is how this server names whoever is asking:
        <code>github:&lt;login&gt;</code> for a signed-in person, <code>operator</code> for the token
        holder, <code>agent:&lt;fingerprint&gt;</code> for an agent's approved grant — an agent can read
        its own from <code>/agent-access/whoami</code>. You are <code>${esc(viewerActorId)}</code>.</p>
        $noticeBlock

        <dl class="cp-grant-facts">
          <dt>Owner</dt><dd><code>${esc(ownerActorId)}</code></dd>
        </dl>

        $visibilityBlock

        <table class="cp-table">
          <thead><tr><th>Shared with</th><th>Role</th><th>May</th><th></th></tr></thead>
          <tbody>
          $rows
          </tbody>
        </table>

        <form class="cp-grant-form" method="post" action="${esc(formAction)}">
          <fieldset class="cp-grant-fieldset">
            <legend>Share with somebody else</legend>
            <label class="cp-grant-ttl">
              <span>Actor id</span>
              <input type="text" name="actorId" placeholder="github:octocat" required>
            </label>
            <label class="cp-grant-scope">
              <input type="radio" name="role" value="viewer" checked>
              <span class="cp-grant-scope-name">viewer</span>
              <span class="cp-grant-scope-what">may open and export this design</span>
            </label>
            <label class="cp-grant-scope">
              <input type="radio" name="role" value="editor">
              <span class="cp-grant-scope-name">editor</span>
              <span class="cp-grant-scope-what">may also change it</span>
            </label>
          </fieldset>
          <div class="cp-grant-actions">
            <button class="cp-grant-approve" type="submit" name="action" value="share">Share</button>
          </div>
        </form>

        <p class="cp-grant-fineprint">Neither role may share this design on: only its owner can, which is
        why granting one is safe to do for somebody who only needs to look.</p>
        ${historyHref?.let { "<p><a href=\"${esc(it)}\">History of this design →</a></p>" } ?: ""}
        <a class="cp-back" href="${esc(designHref)}">← Back to the design</a>
        """
          .trimIndent(),
    )
  }

  /** A design permalink's history page, keeping the permalink's query. */
  private fun historyHref(designHref: String): String {
    val query = designHref.substringAfter("?", "")
    return designHref.substringBefore("?") + "/history" + if (query.isEmpty()) "" else "?$query"
  }

  /** One retained revision on the history page. Null actions are the ones this reader lacks. */
  data class UiBuilderHistoryRow(
    val revision: Long,
    val updatedAt: String?,
    val actorId: String?,
    val thumbnailSrc: String,
    val openHref: String,
    val restoreAction: String?,
    val forkAction: String?,
  )

  /**
   * `/ui-builder/{designId}/history`: the design at each retained revision, newest first, with Open
   * (read-only pinned view), Restore and Fork. Restore and Fork POST and redirect, so a refresh
   * repeats neither.
   */
  fun uiBuilderHistoryPage(
    designId: String,
    title: String,
    currentRevision: Long,
    rows: List<UiBuilderHistoryRow>,
    omitted: Int,
    designHref: String,
    notice: String = "",
    designsHref: String = "",
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
  ): String {
    val esc = WebEscaping::htmlEscape
    val noticeBlock =
      if (notice.isBlank()) "" else "<p class=\"cp-grant-withheld\">${esc(notice)}</p>"
    val cards =
      rows.joinToString("\n") { row ->
        val current = row.revision == currentRevision
        val restore =
          row.restoreAction?.let {
            """
            <form class="cp-design-form" method="post" action="${esc(it)}">
              <input type="hidden" name="baseRevision" value="$currentRevision">
              <button class="cp-grant-approve" type="submit">Restore</button>
            </form>
            """
              .trimIndent()
          } ?: ""
        val fork =
          row.forkAction?.let {
            """
            <form class="cp-design-form" method="post" action="${esc(it)}">
              <button class="cp-action-chip" type="submit">Fork</button>
            </form>
            """
              .trimIndent()
          } ?: ""
        """
        <article class="cp-card cp-design-card">
          <a class="cp-design-thumb" href="${esc(row.openHref)}" tabindex="-1" aria-hidden="true">
            <img src="${esc(row.thumbnailSrc)}" alt="" loading="lazy" decoding="async"
              onerror="this.closest('.cp-design-thumb').classList.add('cp-design-thumb-empty');this.remove();">
          </a>
          <div class="cp-design-body">
            <h2 class="cp-design-title"><a href="${esc(row.openHref)}">Revision ${row.revision}</a>${
              if (current) " <span class=\"cp-design-meta\">(current)</span>" else ""
            }</h2>
            <p class="cp-design-meta">${esc(row.updatedAt ?: "created")}${
              row.actorId?.let { " · <code>${esc(it)}</code>" } ?: ""
            }</p>
            <div class="cp-design-actions">
              <a class="cp-action-chip" href="${esc(row.openHref)}">Open</a>
              $fork
              $restore
            </div>
          </div>
        </article>
        """
          .trimIndent()
      }
    val older =
      if (omitted > 0)
        "<p class=\"cp-grant-fineprint\">$omitted older retained revision" +
          (if (omitted == 1) " is" else "s are") +
          " not shown.</p>"
      else ""
    return document(
      title = "History of ${esc(title)} — compose-preview",
      unfurlDescription = "Revision history of a UI-builder design.",
      version = version,
      navSuffix = navSuffix,
      headerDesignsHref = designsHref,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">History of ${esc(title)}</h1>
        <p class="cp-sub">Every revision this server still keeps, newest first. <strong>Open</strong> shows
        one read-only; <strong>Restore</strong> makes it current again as a new revision, keeping
        everything since so it can be undone the same way; <strong>Fork</strong> starts a new design of
        your own from it.</p>
        $noticeBlock
        <div class="cp-designs-grid">
        $cards
        </div>
        $older
        <a class="cp-back" href="${esc(designHref)}">← Back to the design</a>
        """
          .trimIndent(),
    )
  }

  /**
   * One catalog the **New design** form can create into, with its starting points. Template ids
   * come from `UiBuilderNewDesignSeed`, so the page cannot offer a template the create route would
   * refuse.
   */
  data class UiBuilderNewDesignOption(
    val systemId: String,
    val label: String,
    val templates: List<UiBuilderNewDesignTemplate>,
  )

  /** One starting point inside a [UiBuilderNewDesignOption]. */
  data class UiBuilderNewDesignTemplate(
    val id: String,
    val label: String,
  )

  /**
   * One actor-scoped design on [uiBuilderDesignsPage]. Null [grants] means the caller is not owner.
   */
  data class UiBuilderDesignRow(
    val designId: String,
    val title: String,
    val catalogSystemId: String,
    val revision: Long,
    val updatedAtEpochMillis: Long?,
    val ownerActorId: String,
    val requesterRole: String,
    val requesterAllowed: String,
    val designHref: String,
    val shareAction: String,
    val grants: List<UiBuilderAccessRow>?,
    val unopenableReason: String?,
    val publicRead: Boolean? = null,
    /**
     * The design's live SVG export (`/api/ui-builder/v1/designs/{id}/export.svg`), drawn as the
     * card thumbnail under the same gate and actor. Empty draws the placeholder.
     */
    val previewHref: String = "",
    /** POST target that creates a copy of this design. Empty when the viewer may not create one. */
    val copyAction: String = "",
    /** The id the Duplicate form starts with, so copying is one click. */
    val copySuggestedId: String = "",
    /** POST target that deletes it. Empty unless the viewer owns it. */
    val deleteAction: String = "",
    /** Shared server folder, or null while this design is unfiled. */
    val folder: String? = null,
    /** POST target that changes [folder]. Empty when this viewer may not move the design. */
    val folderAction: String = "",
    /**
     * `/ui-builder/request-access?design=…` for this design where asking for edit access is
     * offered; empty otherwise.
     */
    val requestAccessHref: String = "",
  )

  private fun uiBuilderCreationVisibility(): String =
    """
    <label class="cp-grant-ttl"><span>Visibility</span>
      <select name="visibility" aria-label="Design visibility">
        <option value="private" selected>Private — invited collaborators only</option>
        <option value="public">Public (read only) — anyone with the link</option>
      </select>
    </label>
    """
      .trimIndent()

  /**
   * `GET /ui-builder/designs`: the caller's own designs and the ones shared with them — the
   * builder's file manager.
   *
   * Each card leads with the design's rendering ([UiBuilderDesignRow.previewHref]) and carries
   * open, duplicate, share and delete. Creation lives here too: a blank design from a template
   * ([createAction]) or a copy ([copyAction]). The filter box is the only script and only hides
   * cards.
   */
  fun uiBuilderDesignsPage(
    rows: List<UiBuilderDesignRow>,
    viewerActorId: String,
    /** `POST /ui-builder/designs`. Empty hides the New design form (a reader who may not write). */
    createAction: String = "",
    /** `POST /ui-builder/designs/copy`. Empty hides every Duplicate control for the same reason. */
    copyAction: String = "",
    catalogs: List<UiBuilderNewDesignOption> = emptyList(),
    /** A generated `cheeky-raccoon`, so the New design form can be submitted without typing. */
    suggestedDesignId: String = "",
    /** What the last form submission did, shown once above the grid. */
    notice: String = "",
    /**
     * Catalog-owned catalogs this box cannot serve fully, with why, so a missing New design entry
     * is explained.
     */
    catalogProblems: Map<String, String> = emptyMap(),
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    /**
     * `/ui-builder/request-access`, offered to a signed-in reader who may not create. Empty when
     * this box has no such route.
     */
    requestAccessHref: String = "",
  ): String {
    val esc = WebEscaping::htmlEscape
    val requestAccess =
      if (createAction.isNotBlank() || requestAccessHref.isBlank()) ""
      else
        "<p class=\"cp-designs-notice\">You can open the designs shared with you, read-only. " +
          "<a href=\"${esc(requestAccessHref)}\">Request edit access</a> to get a link to send " +
          "to the owner.</p>"
    val noticeHtml =
      notice
        .takeIf { it.isNotBlank() }
        ?.let { "<p class=\"cp-designs-notice\" role=\"status\">${esc(it)}</p>" }
        .orEmpty()
    val catalogProblemsHtml =
      catalogProblems.entries.joinToString("") { (id, why) ->
        "<p class=\"cp-designs-notice\" role=\"status\">Catalog <code>${esc(id)}</code>: " +
          "${esc(why)}</p>"
      }
    val card: (UiBuilderDesignRow) -> String = { row ->
      val title = if (row.title.isBlank()) row.designId else row.title
      val updated =
        row.updatedAtEpochMillis?.let {
          java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", java.util.Locale.ENGLISH)
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.ofEpochMilli(it))
        } ?: "date unavailable"
      // Lazy and async: a page of designs is many live exports. `onerror` hides a failed export
      // rather than showing a broken-image glyph.
      val thumbnail =
        if (row.previewHref.isBlank())
          """<span class="cp-design-thumb cp-design-thumb-empty" aria-hidden="true">◇</span>"""
        else
          """
            <a class="cp-design-thumb" href="${esc(row.designHref)}" tabindex="-1" aria-hidden="true">
              <img src="${esc(row.previewHref)}" alt="" loading="lazy" decoding="async"
                onerror="this.closest('.cp-design-thumb').classList.add('cp-design-thumb-empty');this.remove();">
            </a>
            """
            .trimIndent()
      val unavailable =
        row.unopenableReason
          ?.let {
            "\n            <p class=\"cp-grant-withheld\"><strong>This design cannot be opened:</strong> ${esc(it)}</p>"
          }
          .orEmpty()
      val duplicate =
        if (row.copyAction.isBlank()) ""
        else
          """
            <details class="cp-design-duplicate">
              <summary class="cp-action-chip">Duplicate</summary>
              <form class="cp-design-form" method="post" action="${esc(row.copyAction)}">
                <input type="hidden" name="sourceDesignId" value="${esc(row.designId)}">
                <label class="cp-grant-ttl"><span>New design id</span><input type="text" name="designId"
                  value="${esc(row.copySuggestedId)}" pattern="[A-Za-z0-9][A-Za-z0-9._-]*" required></label>
                ${uiBuilderCreationVisibility()}
                <button class="cp-grant-approve" type="submit">Create the copy</button>
              </form>
            </details>
            """
            .trimIndent()
      val share =
        if (row.grants == null) ""
        else """<a class="cp-action-chip" href="${esc(row.shareAction)}">Share</a>"""
      // Two steps and no `confirm()`: the summary opens a panel saying what will be lost, and only
      // its button posts.
      val delete =
        if (row.deleteAction.isBlank()) ""
        else
          """
            <details class="cp-design-more cp-design-danger">
              <summary>Delete</summary>
              <form class="cp-design-form" method="post" action="${esc(row.deleteAction)}">
                <p class="cp-grant-fineprint">This removes <code>${esc(row.designId)}</code>, its history,
                its comments and its access list. It cannot be undone.</p>
                <button class="cp-grant-deny" type="submit" name="confirm" value="delete">Delete permanently</button>
              </form>
            </details>
            """
            .trimIndent()
      val folder =
        if (row.folderAction.isBlank()) {
          row.folder?.let { "<p class=\"cp-design-meta\">Folder · ${esc(it)}</p>" }.orEmpty()
        } else {
          """
            <details class="cp-design-more">
              <summary>${if (row.folder == null) "Move to folder" else "Folder · ${esc(row.folder)}"}</summary>
              <form class="cp-design-form" method="post" action="${esc(row.folderAction)}">
                <label class="cp-grant-ttl"><span>Folder</span><input type="text" name="folder"
                  value="${esc(row.folder.orEmpty())}" maxlength="160"
                  placeholder="Leave empty for no folder"></label>
                <button class="cp-grant-approve" type="submit">Move</button>
              </form>
            </details>
            """
            .trimIndent()
        }
      val grants =
        row.grants?.let { all ->
          val isPublic = all.any { ServeUiBuilderVisibility.isReservedActor(it.actorId) }
          val owned = all.filterNot { ServeUiBuilderVisibility.isReservedActor(it.actorId) }
          val visibility =
            """
              <form method="post" action="${esc(row.shareAction)}">
                <input type="hidden" name="returnTo" value="designs">
                ${if (isPublic) "<strong>Public (read only)</strong> — anyone with the link can view."
                  else "<strong>Private</strong>."}
                <button class="${if (isPublic) "cp-grant-deny" else "cp-grant-approve"}" type="submit"
                  name="visibility" value="${if (isPublic) "private" else "public"}">${
                  if (isPublic) "Make private" else "Make public"}</button>
              </form>
              """
              .trimIndent()
          val current =
            visibility +
              if (owned.isEmpty()) "<p>Shared with nobody else.</p>"
              else
                owned.joinToString("\n", prefix = "<p><strong>Shared with:</strong></p>") {
                  """
                  <form method="post" action="${esc(row.shareAction)}">
                    <input type="hidden" name="returnTo" value="designs">
                    <input type="hidden" name="actorId" value="${esc(it.actorId)}">
                    <code>${esc(it.actorId)}</code> (${esc(it.role)})
                    <button class="cp-grant-deny" type="submit" name="action" value="revoke">Remove</button>
                  </form>
                  """
                    .trimIndent()
                }
          """
            <details class="cp-design-more">
              <summary>Sharing</summary>
              $current
              <form class="cp-grant-form" method="post" action="${esc(row.shareAction)}">
                <input type="hidden" name="returnTo" value="designs">
                <label class="cp-grant-ttl"><span>Actor id</span><input type="text" name="actorId" placeholder="github:octocat" required></label>
                <label><input type="radio" name="role" value="viewer" checked> viewer</label>
                <label><input type="radio" name="role" value="editor"> editor</label>
                <button class="cp-grant-approve" type="submit" name="action" value="share">Share</button>
              </form>
            </details>
            """
            .trimIndent()
        }
          ?: ("<p class=\"cp-design-meta\">Shared by <code>${esc(row.ownerActorId)}</code>; the owner manages its grants." +
            (if (row.requestAccessHref.isBlank()) ""
            else
              " <a href=\"${esc(row.requestAccessHref)}\">Request edit access to this design</a>") +
            "</p>")
      // Everything the filter box matches on, in one attribute: the title a person remembers, the
      // id they typed, and the catalog they were working in.
      val haystack =
        listOf(row.title, row.designId, row.catalogSystemId, row.folder.orEmpty())
          .filter(String::isNotBlank)
          .joinToString(" ")
          .lowercase()
      val cardActions =
        listOf(share, folder, grants, delete)
          .filter(String::isNotBlank)
          .joinToString("\n            ")
      """
        <article class="cp-card cp-design-card" data-cp-design="${esc(haystack)}">
          $thumbnail
          <div class="cp-design-body">
            <h2 class="cp-design-title"><a href="${esc(row.designHref)}">${esc(title)}</a></h2>
            <p class="cp-design-meta"><strong>${
              when (row.publicRead ?: row.grants?.any { ServeUiBuilderVisibility.isReservedActor(it.actorId) }) {
                true -> "Public (read only)"
                false -> "Private"
                null -> "Visibility unavailable"
              }
            }</strong></p>
            <p class="cp-design-meta">${esc(when (row.catalogSystemId) {
              "m3-catalog" -> "Mobile app"
              "wear-m3", "wear-m3-catalog" -> "Wear app"
              "remote-m3" -> "Wear widget"
              else -> row.catalogSystemId
            })} · updated ${esc(updated)}</p>
            <p class="cp-design-meta"><code>${esc(row.designId)}</code></p>$unavailable
            <div class="cp-design-actions">
              <a class="cp-action-chip" href="${esc(row.designHref)}">Open</a>
              $duplicate
              <details class="cp-design-menu">
                <summary class="cp-action-chip" aria-label="More actions for ${esc(title)}" title="More actions">⋮</summary>
                <div class="cp-design-menu-panel">
                  <a class="cp-action-chip" href="${esc(historyHref(row.designHref))}">History</a>
                  $cardActions
                  <details class="cp-design-more">
                    <summary>Design details</summary>
                    <p class="cp-design-meta">${esc(row.catalogSystemId)} · revision ${row.revision}</p>
                    <p class="cp-design-meta">${esc(row.requesterRole)} · may ${esc(row.requesterAllowed)}</p>
                  </details>
                </div>
              </details>
            </div>
          </div>
        </article>
        """
        .trimIndent()
    }
    fun gridOf(group: List<UiBuilderDesignRow>): String =
      """
      <div class="cp-designs-grid">
      ${group.joinToString("\n", transform = card).prependIndent("      ").trimStart()}
      </div>
      """
        .trimIndent()
    // Once anything is filed, each folder is its own named section (in name order) with unfiled
    // designs last; until then the page is one grid.
    val folderGroups =
      if (rows.none { it.folder != null }) emptyList()
      else
        rows
          .groupBy { it.folder }
          .entries
          .sortedWith(
            compareBy<Map.Entry<String?, List<UiBuilderDesignRow>>> { it.key == null }
              .thenBy(String.CASE_INSENSITIVE_ORDER) { it.key.orEmpty() }
          )
          .map { it.key to it.value }
    fun designCount(n: Int) = "$n design${if (n == 1) "" else "s"}"
    val sections =
      if (folderGroups.isEmpty()) gridOf(rows)
      else
        folderGroups.withIndex().joinToString("\n") { (index, entry) ->
          val (folder, group) = entry
          val name = folder ?: "No folder"
          val count = designCount(group.size)
          """
            <section class="cp-design-folder" aria-label="${esc(name)}" data-cp-folder-index="$index">
              <h2 class="cp-designs-h2 cp-design-folder-name">${esc(name)} <span class="cp-designs-count cp-design-folder-count">$count</span></h2>
              ${gridOf(group).prependIndent("              ").trimStart()}
            </section>
            """
            .trimIndent()
        }
    val grid =
      if (rows.isEmpty())
        """<p class="cp-sub" id="cp-designs-empty">No designs are owned by or shared with this account yet.</p>"""
      else
        """
        ${sections.prependIndent("        ").trimStart()}
        <p class="cp-sub" id="cp-designs-none" hidden>No design here matches that.</p>
        """
          .trimIndent()
    // The folder picker row: pressing a folder narrows to it, pressing again lets go. Script-only,
    // so it starts hidden. The choice rides in `?folder=` (empty for unfiled) so reloads and links
    // keep it.
    val folderPicker =
      if (folderGroups.isEmpty()) ""
      else {
        val picks = folderGroups.mapIndexed { index, (folder, group) ->
          val name = esc(folder ?: "No folder")
          "<button type=\"button\" class=\"cp-design-folder-pick\" " +
            "data-cp-folder-index=\"$index\" data-cp-folder-name=\"${esc(folder.orEmpty())}\" " +
            "aria-label=\"$name, ${designCount(group.size)}\" aria-pressed=\"false\">" +
            "<span class=\"cp-design-folder-pick-name\">$name</span> " +
            "<span class=\"cp-designs-count\">${group.size}</span></button>"
        }
        """
        <nav class="cp-design-folders" aria-label="Folders" hidden>
          <h2 class="cp-designs-h2">Folders</h2>
          <div class="cp-design-folder-picks">
          ${picks.joinToString("\n          ")}
          </div>
        </nav>
        """
          .trimIndent()
      }
    val startOptions =
      catalogs.joinToString("\n") { catalog ->
        val options =
          catalog.templates.joinToString("\n") { template ->
            """<option value="${esc(catalog.systemId)}|${esc(template.id)}">${esc(template.label)}</option>"""
          }
        """
        <optgroup label="${esc(catalog.label)}">
        ${options.prependIndent("        ").trimStart()}
        </optgroup>
        """
          .trimIndent()
      }
    // One control for catalog + template, since dependent selects need script.
    val newDesign =
      if (createAction.isBlank() || catalogs.isEmpty()) ""
      else
        """
        <form class="cp-designs-new" method="post" action="${esc(createAction)}">
          <h2 class="cp-designs-h2">Start a new design</h2>
          <label class="cp-grant-ttl"><span>Starting point</span>
            <select name="start">
            ${startOptions.prependIndent("            ").trimStart()}
            </select>
          </label>
          <label class="cp-grant-ttl"><span>Design id</span><input type="text" name="designId"
            value="${esc(suggestedDesignId)}" pattern="[A-Za-z0-9][A-Za-z0-9._-]*" required></label>
          ${uiBuilderCreationVisibility()}
          <button class="cp-grant-approve" type="submit">Create</button>
        </form>
        """
          .trimIndent()
    val copyOptions =
      rows
        .filter { it.unopenableReason == null }
        .joinToString("\n") {
          val label = if (it.title.isBlank()) it.designId else "${it.title} (${it.designId})"
          """<option value="${esc(it.designId)}">${esc(label)}</option>"""
        }
    val fromExample =
      if (copyAction.isBlank() || copyOptions.isEmpty()) ""
      else
        """
        <form class="cp-designs-new" method="post" action="${esc(copyAction)}">
          <h2 class="cp-designs-h2">Start from an existing design</h2>
          <p class="cp-designs-hint">A copy at revision zero, with its own history. The design it was
          copied from is left exactly as it is.</p>
          <label class="cp-grant-ttl"><span>Copy</span>
            <select name="sourceDesignId">
            ${copyOptions.prependIndent("            ").trimStart()}
            </select>
          </label>
          <label class="cp-grant-ttl"><span>New design id</span><input type="text" name="designId"
            value="${esc(suggestedDesignId)}" pattern="[A-Za-z0-9][A-Za-z0-9._-]*" required></label>
          ${uiBuilderCreationVisibility()}
          <button class="cp-grant-approve" type="submit">Create the copy</button>
        </form>
        """
          .trimIndent()
    // Creating is the rarer errand here, so it folds away under the list rather than leading it.
    val create =
      if (newDesign.isEmpty() && fromExample.isEmpty()) ""
      else
        """
        <details class="cp-designs-create-more">
        <summary>New design</summary>
        <section class="cp-designs-create">
        ${(newDesign + "\n" + fromExample).trim().prependIndent("        ").trimStart()}
        </section>
        </details>
        """
          .trimIndent()
    // The few designs changed last, as pictures, above the whole list: a way back into today's
    // work without scanning folders. Not `cp-design-card`, so the filter below counts the list.
    val recentRows =
      rows
        .filter { it.unopenableReason == null }
        .sortedByDescending { it.updatedAtEpochMillis ?: 0L }
        .take(RECENT_DESIGNS)
    val recent =
      if (rows.size <= RECENT_DESIGNS) ""
      else
        """
        <section class="cp-designs-recent" aria-label="Recent">
          <h2 class="cp-designs-h2">Recent</h2>
          <div class="cp-designs-grid">
          ${recentRows.joinToString("\n          ") { row ->
            val title = esc(row.title.ifBlank { row.designId })
            val picture =
              if (row.previewHref.isBlank()) ""
              else
                "<img src=\"${esc(row.previewHref)}\" alt=\"\" loading=\"lazy\" decoding=\"async\" " +
                  "onerror=\"this.remove();\">"
            "<a class=\"cp-card cp-design-recent\" href=\"${esc(row.designHref)}\">" +
              "<span class=\"cp-design-thumb\">$picture</span><span class=\"cp-design-title\">$title</span></a>"
          }}
          </div>
        </section>
        """
          .trimIndent()
    val filter =
      if (rows.isEmpty()) ""
      else
        """
        <div class="cp-designs-toolbar">
          <label class="cp-designs-filter"><span class="cp-visually-hidden">Filter designs</span>
            <input type="search" id="cp-design-filter" placeholder="Filter by name, id, catalog or folder"
              autocomplete="off"></label>
          <span class="cp-designs-count" id="cp-design-count">${rows.size} design${if (rows.size == 1) "" else "s"}</span>
        </div>
        <script>
        (function () {
          document.addEventListener("click", function (event) {
            document.querySelectorAll(".cp-design-menu[open], .cp-design-duplicate[open]").forEach(function (menu) {
              if (!menu.contains(event.target)) menu.open = false;
            });
          });
          document.addEventListener("keydown", function (event) {
            if (event.key !== "Escape") return;
            document.querySelectorAll(".cp-design-menu[open], .cp-design-duplicate[open]").forEach(function (menu) {
              menu.open = false;
              menu.querySelector("summary").focus();
            });
          });
          var box = document.getElementById("cp-design-filter");
          var count = document.getElementById("cp-design-count");
          var none = document.getElementById("cp-designs-none");
          if (!box) return;
          // null shows every folder; otherwise the index of the one folder picked above.
          var picked = null;
          function apply() {
            // Looked up here, not when the script runs: the script sits above the grid, so at
            // parse time there were no cards yet and the filter hid nothing and counted zero.
            var q = box.value.trim().toLowerCase();
            var shown = 0;
            Array.prototype.slice.call(document.querySelectorAll(".cp-design-card")).forEach(function (card) {
              var folder = card.closest(".cp-design-folder");
              var inFolder = picked === null || (folder && folder.getAttribute("data-cp-folder-index") === picked);
              var hit = inFolder && (q === "" || (card.getAttribute("data-cp-design") || "").indexOf(q) >= 0);
              card.hidden = !hit;
              if (hit) shown += 1;
            });
            // A folder whose every card is filtered out goes with them, heading and all; one that
            // keeps some counts only what it still shows, like the total does.
            Array.prototype.slice.call(document.querySelectorAll(".cp-design-folder")).forEach(
              function (folder) {
                var left = folder.querySelectorAll(".cp-design-card:not([hidden])").length;
                folder.hidden = left === 0;
                var label = folder.querySelector(".cp-design-folder-count");
                if (label) label.textContent = left + (left === 1 ? " design" : " designs");
              });
            // Recent is across every folder, so it steps aside while one folder is picked.
            var recent = document.querySelector(".cp-designs-recent");
            if (recent) recent.hidden = picked !== null;
            if (count) count.textContent = shown + (shown === 1 ? " design" : " designs");
            if (none) none.hidden = shown !== 0;
          }
          box.addEventListener("input", apply);
          var folders = document.querySelector(".cp-design-folders");
          if (!folders) return;
          var picks = Array.prototype.slice.call(folders.querySelectorAll(".cp-design-folder-pick"));
          // index null shows every folder; there is no "All designs" pick to press for that.
          function pick(index, remember) {
            picked = index;
            var name = null;
            picks.forEach(function (button) {
              var on = button.getAttribute("data-cp-folder-index") === index;
              button.setAttribute("aria-pressed", on ? "true" : "false");
              if (on && picked !== null) name = button.getAttribute("data-cp-folder-name");
            });
            if (remember && window.history && window.URLSearchParams) {
              var params = new URLSearchParams(window.location.search);
              if (name === null) params.delete("folder"); else params.set("folder", name);
              var search = params.toString();
              window.history.replaceState(null, "", window.location.pathname + (search ? "?" + search : "") + window.location.hash);
            }
            apply();
          }
          picks.forEach(function (button) {
            button.addEventListener("click", function () {
              // Pressing the picked folder again lets go of it, back to every folder.
              var index = button.getAttribute("data-cp-folder-index");
              pick(index === picked ? null : index, true);
            });
          });
          var initial = null;
          if (window.URLSearchParams) {
            var wanted = new URLSearchParams(window.location.search).get("folder");
            picks.forEach(function (button) {
              if (wanted !== null && button.getAttribute("data-cp-folder-name") === wanted) {
                initial = button.getAttribute("data-cp-folder-index");
              }
            });
          }
          folders.hidden = false;
          // The cards are below this script, so a folder named in the URL is applied once they exist.
          if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", function () { pick(initial, false); });
          } else {
            pick(initial, false);
          }
        })();
        </script>
        """
          .trimIndent()
    return document(
      title = "Designs — compose-preview",
      unfurlDescription = "UI-builder designs owned by or shared with this account.",
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      wide = true,
      body =
        """
        <h1 class="cp-head">Designs</h1>
        <p><a href="/ui-builder/projects$navSuffix">Projects — app files, resources and sharing</a></p>
        <p class="cp-sub">Every design this server permits <code>${esc(viewerActorId)}</code> to open,
        newest first. Open one to carry on with it, duplicate one to start from it.</p>
        $noticeHtml$catalogProblemsHtml$requestAccess
        $folderPicker
        $recent
        $filter
        $grid
        $create
        <a class="cp-back" href="/ui-builder/$navSuffix">← UI builder</a>
        """
          .trimIndent(),
    )
  }

  /** How many recently changed designs the designs page shows as pictures above the list. */
  private const val RECENT_DESIGNS = 4

  /** One row of [uiBuilderAccessPage]'s table, already flattened for display. */
  data class UiBuilderAccessRow(
    val actorId: String,
    val role: String,
    val allowed: String,
  )

  /** A design an access request names, with the title the approver knows it by (may be blank). */
  data class RequestedDesign(val designId: String, val title: String)

  /** The approval form's `designScope` values. */
  const val DESIGN_SCOPE_REQUESTED = "requested"
  const val DESIGN_SCOPE_ALL = "all"

  /**
   * `GET /agent-access/{requestId}`: the page a human opens because an agent asked them to, and the
   * only place a grant is created. See
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   *
   * It must make the decision legible: the verification code, the agent's label, where the request
   * came from, and what each scope allows. [label] and [client] are attacker-controlled and always
   * escaped. [selectableScopes] is already narrowed to what this approver may give.
   */
  fun agentGrantApprovalPage(
    requestId: String,
    userCode: String,
    label: String,
    client: String,
    requestedScope: AgentGrantScope,
    requestedTtlSeconds: Long,
    expiresInSeconds: Long,
    approver: String,
    selectableScopes: List<AgentGrantScope>,
    maxTtlSeconds: Long,
    /**
     * Independent permissions this approver may tick, narrowed like [selectableScopes]. Usually
     * empty; the feature is opt-in.
     */
    selectableCapabilities: List<AgentGrantCapability> = emptyList(),
    approveCsrf: String,
    denyCsrf: String,
    /** Form target, carrying the access token on a token-gated box. */
    formAction: String,
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    /**
     * Named only when the approver's own rights capped [selectableScopes], so the page says so
     * instead of silently omitting a row.
     */
    withheldScopes: List<AgentGrantScope> = emptyList(),
    /** Capabilities the agent asked for that this approver may not pass on. Same treatment. */
    withheldCapabilities: List<AgentGrantCapability> = emptyList(),
    withheldReason: String = "",
    /**
     * Requested capabilities this box's ceiling excludes — a different cause and remedy from
     * [withheldCapabilities], so both are stated.
     */
    storeNarrowedCapabilities: List<AgentGrantCapability> = emptyList(),
    storeNarrowedReason: String = "",
    /**
     * Set when the request came through the MCP OAuth façade: where approving sends the browser,
     * with the redeeming code. The page leads with that host and drops "Asked from" (it would just
     * be the approver's browser); [label] is shown as the client's self-chosen name.
     */
    oauthReturn: ServeMcpOAuth.RedirectTarget? = null,
    /**
     * The designs the request names, if any. The page offers to limit the grant to them (default)
     * or lend every design the approver can edit.
     */
    requestedDesigns: List<RequestedDesign> = emptyList(),
  ): String {
    val esc = WebEscaping::htmlEscape
    val designNames =
      requestedDesigns.joinToString(", ") { design ->
        if (design.title.isBlank()) "<code>${esc(design.designId)}</code>"
        else "${esc(design.title)} (<code>${esc(design.designId)}</code>)"
      }
    val designFacts =
      if (requestedDesigns.isEmpty()) ""
      else "\n          <dt>Edit access to</dt><dd>$designNames</dd>"
    // Two radios naming both outcomes; the named designs are the default because they were asked
    // for.
    val designFieldset =
      if (requestedDesigns.isEmpty()) ""
      else
        "\n" +
          """
        <fieldset class="cp-grant-fieldset">
          <legend>Which designs</legend>
          <label class="cp-grant-scope">
            <input type="radio" name="designScope" value="$DESIGN_SCOPE_REQUESTED" checked>
            <span class="cp-grant-scope-name">${if (requestedDesigns.size == 1) "This design only" else "These designs only"}</span>
            <span class="cp-grant-scope-what">$designNames. Other designs you own or can edit stay out of reach.</span>
          </label>
          <label class="cp-grant-scope">
            <input type="radio" name="designScope" value="$DESIGN_SCOPE_ALL">
            <span class="cp-grant-scope-name">Every design you can edit</span>
            <span class="cp-grant-scope-what">Lends your edit access on all of your designs, as an approval without a named design does.</span>
          </label>
        </fieldset>
        """
            .trimIndent()
    // Radios, not checkboxes: scopes are cumulative (`playground` implies `live`), so independent
    // boxes could misdescribe the grant. One choice — the rung — with what it carries spelled out.
    // The default is the highest offered rung, not the requested one: when the approver's rights
    // cap the request, `requestedScope` matches no radio and the `required` form could not be
    // submitted.
    val defaultScope = selectableScopes.lastOrNull()
    val scopeRows =
      selectableScopes.joinToString("\n") { scope ->
        val checked = if (scope == defaultScope) " checked" else ""
        val includes =
          AgentGrantScope.upTo(scope).filter { it != scope }.joinToString(", ") { it.wire }
        val alsoIncludes =
          if (includes.isEmpty()) ""
          else "<span class=\"cp-grant-scope-implies\">also includes ${esc(includes)}</span>"
        """
        <label class="cp-grant-scope">
          <input type="radio" name="scope" value="${esc(scope.wire)}"$checked required>
          <span class="cp-grant-scope-name">${esc(scope.wire)}</span>
          <span class="cp-grant-scope-what">${esc(scope.humanDescription)}$alsoIncludes</span>
        </label>
        """
          .trimIndent()
      }
    // Checkboxes here: capabilities are independent yes/no choices, unlike the scope ladder.
    //
    // Every box starts ticked because [selectableCapabilities] is already the intersection of the
    // request with the approver's and box's ceilings (`ServeAgentGrants.selectableCapabilities`).
    // Consent is still the Approve press, and the POST honours exactly what is ticked.
    val capabilityRows =
      selectableCapabilities.joinToString("\n") { capability ->
        """
        <label class="cp-grant-scope">
          <input type="checkbox" name="capability" value="${esc(capability.wire)}" checked>
          <span class="cp-grant-scope-name">${esc(capability.wire)}</span>
          <span class="cp-grant-scope-what">${esc(capability.humanDescription)}</span>
        </label>
        """
          .trimIndent()
      }
    val capabilityFieldset =
      if (selectableCapabilities.isEmpty()) ""
      else
        """
        <fieldset class="cp-grant-fieldset">
          <legend>Anything else the agent may do</legend>
          $capabilityRows
        </fieldset>
        """
          .trimIndent()
    val withheldCapabilityNote =
      if (withheldCapabilities.isEmpty()) ""
      else
        """
        <p class="cp-grant-withheld">Also asked for, not offered: ${
          esc(withheldCapabilities.joinToString(", ") { it.wire })
        } — ${esc(withheldReason)}</p>
        """
          .trimIndent()
    val storeNarrowedNote =
      if (storeNarrowedCapabilities.isEmpty()) ""
      else
        """
        <p class="cp-grant-withheld">Also asked for, not offered: ${
          esc(storeNarrowedCapabilities.joinToString(", ") { it.wire })
        } — ${esc(storeNarrowedReason)}</p>
        """
          .trimIndent()
    val withheld =
      if (withheldScopes.isEmpty()) ""
      else
        """
        <p class="cp-grant-withheld">Not offered: ${
          esc(withheldScopes.joinToString(", ") { it.wire })
        } — ${esc(withheldReason)}</p>
        """
          .trimIndent()
    val ttlOptions =
      ttlChoices(requestedTtlSeconds, maxTtlSeconds).joinToString("\n") { seconds ->
        val selected = if (seconds == requestedTtlSeconds) " selected" else ""
        "<option value=\"$seconds\"$selected>${esc(AgentGrantProtocol.formatDuration(seconds))}</option>"
      }
    val oauthReturnHtml =
      if (oauthReturn == null) ""
      else {
        val (kindClass, kindText) =
          when (oauthReturn.kind) {
            ServeMcpOAuth.RedirectTarget.Kind.EXTERNAL ->
              "cp-grant-return-kind--external" to "External site"
            ServeMcpOAuth.RedirectTarget.Kind.LOOPBACK ->
              "cp-grant-return-kind--local" to "This computer"
            ServeMcpOAuth.RedirectTarget.Kind.APP -> "cp-grant-return-kind--local" to "App link"
          }
        val hint =
          when (oauthReturn.kind) {
            ServeMcpOAuth.RedirectTarget.Kind.EXTERNAL ->
              "Approving sends this access to a site elsewhere on the internet, not to a program on " +
                "your computer. Approve only if you recognise this host and meant to connect it."
            ServeMcpOAuth.RedirectTarget.Kind.LOOPBACK ->
              "Approving sends this access to a program listening on your own computer."
            ServeMcpOAuth.RedirectTarget.Kind.APP ->
              "Approving sends this access to whichever app on this device handles these links."
          }
        // Joined at the body's own indentation, so the page's `trimIndent` below still finds it.
        listOf(
            "<div class=\"cp-grant-return\">",
            "  <span class=\"cp-grant-code-label\">Access goes to</span>",
            "  <span class=\"cp-grant-return-host\"><code>${esc(oauthReturn.display)}</code> " +
              "<span class=\"cp-grant-return-kind $kindClass\">${esc(kindText)}</span></span>",
            "  <span class=\"cp-grant-code-hint\">${esc(hint)}</span>",
            "  <span class=\"cp-grant-return-uri\">${esc(oauthReturn.uri)}</span>",
            "</div>",
            "",
          )
          .joinToString("\n        ")
      }
    val askerFacts =
      if (oauthReturn == null)
        "<dt>Purpose</dt><dd>${if (label.isBlank()) "<em>none given</em>" else esc(label)}</dd>\n" +
          "          <dt>Asked from</dt><dd>${esc(client)}</dd>"
      else
        "<dt>Client calls itself</dt><dd>" +
          (if (label.isBlank()) "<em>no name given</em>" else esc(label)) +
          " <span class=\"cp-grant-self-named\">(chosen by the client)</span></dd>"
    return document(
      title = "Grant agent access — compose-preview",
      unfurlDescription = "An agent is asking for temporary access to this preview server.",
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">Grant temporary access?</h1>
        <p class="cp-sub">An agent has asked for temporary access to this preview server. Approving mints a
        bearer token that expires on its own — nothing here changes this server's configuration, and you can
        revoke it at any time from <a href="/status$navSuffix">/status</a>.</p>

        <div class="cp-grant-code">
          <span class="cp-grant-code-label">Verification code</span>
          <code class="cp-grant-code-value">${esc(userCode)}</code>
          <span class="cp-grant-code-hint">This must match the code the agent printed. If it does not,
          you are looking at someone else's request — close this page.</span>
        </div>

        $oauthReturnHtml<dl class="cp-grant-facts">
          $askerFacts$designFacts
          <dt>Approving as</dt><dd>${esc(approver)}</dd>
          <dt>This request expires in</dt><dd>${esc(AgentGrantProtocol.formatDuration(expiresInSeconds))}</dd>
        </dl>

        <form class="cp-grant-form" method="post" action="${esc(formAction)}">
          <input type="hidden" name="csrf" value="${esc(approveCsrf)}">
          <fieldset class="cp-grant-fieldset">
            <legend>What the agent may do</legend>
            $scopeRows
          </fieldset>
          $capabilityFieldset$designFieldset
          $withheld
          $withheldCapabilityNote
          $storeNarrowedNote
          <label class="cp-grant-ttl">
            <span>Access expires after</span>
            <select name="ttl">
              $ttlOptions
            </select>
          </label>
          <div class="cp-grant-actions">
            <button class="cp-grant-approve" type="submit" name="action" value="approve">Approve</button>
            <button class="cp-grant-deny" type="submit" name="action" value="deny"
              formnovalidate>Deny</button>
          </div>
          <input type="hidden" name="denyCsrf" value="${esc(denyCsrf)}">
        </form>

        <p class="cp-grant-fineprint">The token is delivered to the agent that opened this request, not to
        whoever opens this link — so forwarding the link cannot leak it. Requested id
        <code>${esc(requestId.take(8))}…</code>.</p>
        """
          .trimIndent(),
    )
  }

  /**
   * The terminal page an approve/deny/expire lands on; no link back, since the request has been
   * decided.
   */
  fun agentGrantNoticePage(
    heading: String,
    message: String,
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    /** Shown under the message when a grant was actually minted. */
    detail: String = "",
  ): String =
    document(
      title = "$heading — compose-preview",
      unfurlDescription = message,
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">${WebEscaping.htmlEscape(heading)}</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)}</p>
        ${if (detail.isBlank()) "" else "<p class=\"cp-grant-detail\">${WebEscaping.htmlEscape(detail)}</p>"}
        <a class="cp-back" href="/status$navSuffix">← Server status</a>
        """
          .trimIndent(),
    )

  /**
   * A grant link opened in a browser already acting as a different live grant. Nothing changes
   * unless `Switch` (a same-origin POST) is pressed.
   */
  fun agentGrantSwitchPage(
    currentLabel: String,
    currentFingerprint: String,
    nextLabel: String,
    nextFingerprint: String,
    formAction: String,
    token: String,
    target: String,
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
  ): String =
    document(
      title = "Switch agent access? — compose-preview",
      unfurlDescription = "This link would switch this browser to a different agent grant.",
      version = version,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">Switch agent access?</h1>
        <p class="cp-sub">This browser is using the agent grant
          <strong>${WebEscaping.htmlEscape(currentLabel)}</strong>
          (<code>${WebEscaping.htmlEscape(currentFingerprint)}</code>). The link you opened would
          replace it with <strong>${WebEscaping.htmlEscape(nextLabel)}</strong>
          (<code>${WebEscaping.htmlEscape(nextFingerprint)}</code>), and anything you do next would
          be recorded under that grant. Only switch if you expected this link.</p>
        <form method="post" action="${WebEscaping.htmlEscape(formAction)}">
          <input type="hidden" name="token" value="${WebEscaping.htmlEscape(token)}">
          <input type="hidden" name="next" value="${WebEscaping.htmlEscape(target)}">
          <button type="submit">Switch</button>
          <a class="cp-back" href="${WebEscaping.htmlEscape(target)}">Keep current access</a>
        </form>
        """
          .trimIndent(),
    )

  /** A request this reader just opened, shown so they can send its link to whoever approves it. */
  data class RequestedAccess(
    val approveUrl: String,
    val userCode: String,
    val expiresInSeconds: Long,
    /** The designs the request names; empty when it asks for everything the approver can edit. */
    val designIds: List<String> = emptyList(),
  )

  /**
   * `/ui-builder/request-access`: a signed-in reader asks for UI-builder edit access for themselves
   * and gets a link to send to an approver.
   *
   * Three states: a grant already held (until when), a request just opened (link, code, next
   * steps), or the form. The server opens the request from the reader's session, so the approver
   * sees a verified login.
   */
  fun uiBuilderRequestAccessPage(
    login: String,
    formAction: String,
    csrf: String,
    ttlChoicesSeconds: List<Long>,
    /** When a grant this reader asked for is live, its expiry, already formatted. */
    activeUntil: String? = null,
    requested: RequestedAccess? = null,
    /**
     * The design this page was opened from (`?design=`), if any; the request is then for that
     * design alone.
     */
    designId: String? = null,
    /** The designs the live grant in [activeUntil] names; empty when it names none. */
    activeDesignIds: List<String> = emptyList(),
    navSuffix: String = "",
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
  ): String {
    val esc = WebEscaping::htmlEscape
    val designList: (List<String>) -> String = { ids ->
      ids.joinToString(", ") { "<code>${esc(it)}</code>" }
    }
    val active =
      activeUntil?.let {
        val reach =
          if (activeDesignIds.isEmpty()) "edit access"
          else "edit access to ${designList(activeDesignIds)}"
        "<p class=\"cp-designs-notice\" role=\"status\">You already have $reach, until " +
          "${esc(it)}. Open the UI builder and it applies to your session.</p>"
      } ?: ""
    val body =
      if (requested != null) {
        val minutes = (requested.expiresInSeconds / 60).coerceAtLeast(1)
        """
        ${if (requested.designIds.isEmpty()) "" else
          "<p class=\"cp-sub\">This request is for ${designList(requested.designIds)} only.</p>"}
        <p class="cp-sub">Send this link to the owner of the designs you want to edit. It is safe to
        paste into a chat: it only lets them approve or decline, and whatever they approve is
        applied to <strong>your</strong> signed-in session, never to whoever opens the link.</p>
        <p class="cp-grant-detail"><input type="text" readonly value="${esc(requested.approveUrl)}"
          onclick="this.select()" style="width:100%"></p>
        <p class="cp-sub">They will be asked to confirm the code <strong>${esc(requested.userCode)}</strong>.
        The link can be approved for the next $minutes minutes. Once it is, reload the UI builder:
        you can edit what they can, as <code>github:${esc(login)}</code>, until the access they chose
        runs out.</p>
        """
          .trimIndent()
      } else {
        val options =
          ttlChoicesSeconds.joinToString("\n") { seconds ->
            val hours = seconds / 3600
            val label =
              if (hours >= 1) "$hours hour${if (hours == 1L) "" else "s"}"
              else "${seconds / 60} minutes"
            val selected = if (seconds == ttlChoicesSeconds.last()) " selected" else ""
            "<option value=\"$seconds\"$selected>${esc(label)}</option>"
          }
        """
        <p class="cp-sub">Signed in as <code>github:${esc(login)}</code>, you can open the designs
        shared with you, read-only. To edit, ask someone who can: this makes a link you send them, and
        what they approve applies to your session for as long as they choose.</p>
        ${if (designId == null) "" else
          "<p class=\"cp-sub\">This asks for edit access to the design <code>${esc(designId)}</code> " +
            "only; your access to other designs stays as it is.</p>"}
        <form method="post" action="${esc(formAction)}">
          <input type="hidden" name="csrf" value="${esc(csrf)}">
          ${if (designId == null) "" else
            "<input type=\"hidden\" name=\"design\" value=\"${esc(designId)}\">"}
          <label class="cp-grant-ttl"><span>For</span>
            <select name="ttl">
            $options
            </select>
          </label>
          <button type="submit" class="cp-grant-approve">Request edit access</button>
        </form>
        """
          .trimIndent()
      }
    return document(
      title = "Request edit access — compose-preview",
      unfurlDescription = "Ask for UI-builder edit access",
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      body =
        """
        <h1 class="cp-head">Request edit access</h1>
        $active
        $body
        <a class="cp-back" href="/ui-builder/designs$navSuffix">← Designs</a>
        """
          .trimIndent(),
    )
  }

  /**
   * Durations the approval page offers: a short ladder plus the requested value, clipped to the
   * box's ceiling and de-duplicated.
   */
  internal fun ttlChoices(requestedSeconds: Long, maxSeconds: Long): List<Long> =
    (LADDER + requestedSeconds)
      .filter { it in 1..maxSeconds }
      .distinct()
      .sorted()
      .ifEmpty { listOf(minOf(requestedSeconds.coerceAtLeast(1), maxSeconds)) }

  private val LADDER = listOf(15 * 60L, 60 * 60L, 4 * 60 * 60L, 8 * 60 * 60L, 24 * 60 * 60L)

  /**
   * The landing page for a preview URL pinned to a publish that did not contain that preview.
   *
   * Still a 404 (showing the current render would make the pin a lie), but unlike [notFoundPage] it
   * keeps the revision navigator so the visitor can pick another publish or return to current.
   */
  fun unavailablePreviewRevisionPage(
    previewId: String,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    revisions: CatalogRevisions,
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val idSeg = WebEscaping.urlEncodeSegment(previewId)
    val query = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val hrefFor: (String?) -> String = { pin -> withPin("$basePath/p/$idSeg$query", pin) }
    val revisionMenu = revisionsHtml(revisions, includeBanner = false, hrefFor = hrefFor)
    val pin = revisions.pinned?.let(ServeCatalogRevision::short).orEmpty()
    val message =
      if (pin.isBlank()) "That preview does not exist in this catalog."
      else "This preview was not published in catalog revision $pin."
    val suffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    return document(
      title = "Preview unavailable — compose-preview",
      unfurlDescription = message,
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      changelogHref = changelogHref,
      body =
        """
        <h1 class="cp-head">Preview unavailable</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(message)} Choose another revision or return to the current catalog.</p>
        $revisionMenu
        <a class="cp-back" href="${WebEscaping.htmlEscape(hrefFor(null))}">← View current preview</a>
        """
          .trimIndent(),
    )
  }

  /**
   * `GET /playground`: the Stage-1 Kotlin playground editor (`docs/design/PLAYGROUND.md` §2). A
   * code box, mode selector and Run button POSTing to `/api/{v}/compiler/run`, showing diagnostics,
   * the first frame, and the handoff link (`/pg/<token>` live, or `/d/<id>` Remote Compose).
   *
   * The lane runs user code on the server, so it is only mounted behind a token (refused under
   * `--public`) and links always carry `?token=…`. A plain `<textarea>` is the v1 editor (design §7
   * item 5).
   */
  fun playgroundPage(
    token: String,
    isPublic: Boolean,
    /**
     * Catalog selector entries, first preselected: the host's pinned default (id `""`, only when
     * `--playground-bundle` resolved) then every served catalog. Each entry carries its own mode
     * list, so the Mode control follows the selection.
     *
     * May be empty during startup while catalogs load in the background; the page says so and
     * refreshes from `/api/1/compiler/catalogs`.
     */
    catalogs: List<PlaygroundCatalogInfo>,
    /**
     * True when `--playground` configured a runtime catalog selector, regardless of whether any
     * catalog has loaded. Separate from `catalogs.size` because a pin-only list during startup
     * would otherwise omit the control the script needs to fill in later.
     */
    catalogSelectorEnabled: Boolean = false,
    /**
     * A served preview's source to open with its catalog preselected
     * (`/playground?from=<system>/<previewId>`). Null when opened directly.
     */
    seed: PlaygroundSeed? = null,
    /**
     * Preselect this catalog without seeding source (the catalog landing's handoff). Ignored when
     * [seed] is present.
     */
    preselectCatalog: String? = null,
    /**
     * The served-catalog ids this host's pinned default compiles against
     * ([PlaygroundCompileService.pinnedCatalogSystems]), so a `?from=` handoff to the pinned
     * catalog (reported as id `""`) is recognised.
     */
    pinnedCatalogSystems: Set<String> = emptySet(),
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    /** Show the authenticated, server-enabled single stateful editing lease control. */
    editingLeaseEnabled: Boolean = false,
  ): String {
    val suffix = querySuffix(queryString(token, sessionId = null, isPublic = isPublic))
    val sample = WebEscaping.htmlEscape(seed?.text ?: PLAYGROUND_SAMPLE)
    val fileName = seed?.fileName ?: "Snippet.kt"
    // A seed or catalog link only wins if this host offers that catalog; otherwise fall back to the
    // first entry.
    val handoffCatalog = seed?.catalog ?: preselectCatalog
    // Two ways this host can offer the named catalog: as the selector's own entry for it, or as the
    // pinned default (which the selector reports under the anonymous id `""`).
    val wantedIndex = handoffCatalog?.let { system ->
      catalogs
        .indexOfFirst {
          it.system == system &&
            (seed?.sourceModule.isNullOrBlank() || it.module == seed.sourceModule)
        }
        .takeIf { it >= 0 }
        ?: catalogs
          .indexOfFirst { it.id.isEmpty() }
          .takeIf { it >= 0 && system in pinnedCatalogSystems }
    }
    val selectedIndex = wantedIndex ?: 0
    // A pinned host with no runtime choice keeps the single Mode select. Everything else gets the
    // Catalog control, even when empty or pin-only, so the script's refresh has a control to fill.
    val showCatalogs =
      catalogSelectorEnabled ||
        catalogs.isEmpty() ||
        catalogs.size > 1 ||
        catalogs.first().id.isNotEmpty()
    val catalogOptions =
      if (catalogs.isEmpty())
        """<option value="" disabled selected>No catalogs available yet…</option>"""
      else
        catalogs
          .mapIndexed { i, c ->
            val selected = if (i == selectedIndex) " selected" else ""
            """<option value="${WebEscaping.htmlEscape(c.id)}"$selected>${
              WebEscaping.htmlEscape(c.label)
            }</option>"""
          }
          .joinToString("\n              ")
    val options =
      catalogs
        .getOrNull(selectedIndex)
        ?.modes
        .orEmpty()
        .mapIndexed { i, mode ->
          val (value, label) = playgroundModeChoice(mode)
          val selected = if (i == 0) " selected" else ""
          """<option value="$value"$selected>$label</option>"""
        }
        .joinToString("\n              ")
    // Hand-indented to the interpolation column (12); `trimIndent()` would flush later lines to
    // column 0.
    val catalogRow =
      if (!showCatalogs) ""
      else
        listOf(
            """<label class="cp-pg-modelabel" for="pg-catalog">Catalog</label>""",
            """<select id="pg-catalog" class="cp-pg-mode">""",
            "  $catalogOptions",
            "</select>",
            "",
          )
          .joinToString("\n            ")
    // An empty selector has two causes: catalogs still loading (transient) or none trusted with a
    // liveBundle (configuration). Name both.
    val emptyNote =
      if (catalogs.isNotEmpty()) ""
      else
        """

          <p id="pg-empty" class="cp-sub">No catalog can back a compile here yet. Catalogs are
            fetched in the background after the server starts, so this usually clears on its own —
            but a catalog also has to verify as <strong>trusted</strong> and publish a live bundle
            before the playground will compile against it.</p>"""
    // A handoff naming a catalog this host does not compile against (bookmark, shared or hand-typed
    // URL, or one still loading; [ServeHttpServer.playgroundLinkFor] withholds such links). Say so
    // before the visitor spends a compile. Suppressed while the list is empty, where [emptyNote]
    // explains.
    val unavailableNote =
      if (handoffCatalog == null || wantedIndex != null || catalogs.isEmpty()) ""
      else {
        val target =
          catalogs.getOrNull(selectedIndex)?.let { entry ->
            val label = if (entry.id.isEmpty()) "this server's default catalog" else entry.id
            "<code>${WebEscaping.htmlEscape(label)}</code>"
          } ?: "the selected catalog"
        """

          <p id="pg-catalog-unavailable" class="cp-sub cp-pg-warn"><strong>This server cannot
            compile against <code>${WebEscaping.htmlEscape(handoffCatalog)}</code>.</strong> The
            playground compiles a snippet against one catalog's own classpath and renders it on that
            catalog's backend, and this host offers neither for that design system — most often
            because it serves Android and Wear catalogs for browsing while running only the desktop
            (Skiko) render backend, which is what an Android catalog's previews need. The editor is
            open on $target instead, so anything below that names
            <code>${WebEscaping.htmlEscape(handoffCatalog)}</code>'s own types will not resolve.
            Pick another catalog above, or start from the sample.</p>"""
      }
    // Says whose code is in the buffer, and only claims "opened from": a preview file may reference
    // siblings the bundle never exported.
    val seedNote =
      if (seed == null) ""
      else {
        val where =
          seed.blobUrl?.let {
            """<a class="cp-source-link" href="${WebEscaping.htmlEscape(it)}">${
                WebEscaping.htmlEscape(seed.fileName)
              }</a>"""
          } ?: WebEscaping.htmlEscape(seed.fileName)
        // The three seed kinds promise different things: only a cleaned seed (annotations, frame,
        // tally and knobs resolved away) may say "ready to Run".
        if (seed.cleaned && !seed.scaffoldsDeclared) {
          // Cleaned by the generic rules only: this catalog's own helpers (`Sticker`, `counted`,
          // knobs) are still in the buffer.
          """

          <p id="pg-seed" class="cp-sub">Opened from $where — <code>${
              WebEscaping.htmlEscape(seed.previewId)
            }</code> in <code>${WebEscaping.htmlEscape(seed.catalog)}</code>, with the catalog
            annotations removed. <code>${
              WebEscaping.htmlEscape(seed.catalog)
            }</code> has not declared what its own helpers mean in plain Compose, so the ones this
            preview uses are still here and will not resolve against the published catalog — delete
            them or replace them with your own values.</p>"""
        } else if (seed.cleaned) {
          val caveat =
            if (seed.residue.isEmpty()) ""
            else {
              val names =
                seed.residue.joinToString(", ") { "<code>${WebEscaping.htmlEscape(it)}</code>" }
              " Some of this catalog's own helpers ($names) had no plain-Compose form to rewrite " +
                "to, so they are still here and will not resolve — delete them or replace them " +
                "with your own values."
            }
          """

          <p id="pg-seed" class="cp-sub">Opened from $where — <code>${
              WebEscaping.htmlEscape(seed.previewId)
            }</code> in <code>${WebEscaping.htmlEscape(seed.catalog)}</code>, rewritten as the
            plain Compose that produces this render. The catalog's annotations, sticker frame and
            variant knobs are not code you need in order to use the component, so they are gone.
            Press Run.$caveat</p>"""
        } else if (seed.sliced)
          """

          <p id="pg-seed" class="cp-sub">Opened from $where — the declaration of
            <code>${WebEscaping.htmlEscape(seed.previewId)}</code>, plus that file's imports, from
            <code>${WebEscaping.htmlEscape(seed.catalog)}</code>. Just this one composable, not the
            whole file, but otherwise its source verbatim — so anything it pulls in from elsewhere
            in its own module shows up as an unresolved reference to delete.</p>"""
        else
          """

          <p id="pg-seed" class="cp-sub">Opened $where — the file
            <code>${WebEscaping.htmlEscape(seed.previewId)}</code> is declared in, from
            <code>${WebEscaping.htmlEscape(seed.catalog)}</code>. It is the whole file, not a
            trimmed snippet, and it compiles against that catalog's classpath: anything it pulls in
            from elsewhere in its own module shows up as an unresolved reference to delete.</p>"""
      }
    val catalogData =
      jsString(
        JSON_COMPACT.encodeToString(
          PlaygroundCatalogsResponse.serializer(),
          PlaygroundCatalogsResponse(catalogs),
        )
      )
    val editLeaseButton =
      if (!editingLeaseEnabled) ""
      else
        """
            <button id="pg-edit-lease" class="cp-doc-btn" type="button">Acquire editing lease</button>"""
    val editLeaseNote =
      if (!editingLeaseEnabled) ""
      else
        """
          <p id="pg-edit-lease-note" class="cp-pg-status" hidden></p>"""
    return document(
      title = "Playground — compose-preview",
      unfurlDescription = "Compile a Compose snippet against the live catalog and open a preview.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <link rel="stylesheet" href="${assetHref("codemirror.css")}">
        <link rel="stylesheet" href="${assetHref("playground.css")}">
        <h1 class="cp-head">Playground</h1>
        <p class="cp-sub">Write a Compose snippet, compile it against the live catalog, and open a
          preview. This lane runs your code on the server, so it stays behind your token.</p>
        <div class="cp-pg">$emptyNote$unavailableNote$seedNote
          <div class="cp-pg-bar">
            $catalogRow<label class="cp-pg-modelabel" for="pg-mode">Mode</label>
            <select id="pg-mode" class="cp-pg-mode">
              $options
            </select>
            <button id="pg-run" class="cp-doc-btn cp-pg-run" type="button">Run</button>$editLeaseButton
          </div>$editLeaseNote
          <div id="pg-files" class="cp-pg-files" role="tablist" aria-label="Snippet files">
            <button class="cp-pg-file" type="button" role="tab" aria-current="true"
              data-pg-file="${WebEscaping.htmlEscape(fileName)}">${
                WebEscaping.htmlEscape(fileName)
              }</button>
            <button id="pg-add-file" class="cp-pg-filebtn" type="button">+ file</button>
            <button id="pg-remove-file" class="cp-pg-filebtn" type="button" hidden>Remove file</button>
          </div>
          <textarea id="pg-source" class="cp-pg-source" spellcheck="false"
            aria-label="Kotlin source">$sample</textarea>
          <div id="pg-status" class="cp-pg-status" role="status" hidden></div>
          <ul id="pg-diagnostics" class="cp-pg-diags" aria-live="polite" hidden></ul>
          <div id="pg-result" class="cp-doc-result cp-pg-result" hidden>
            <p id="pg-preview-note" class="cp-pg-status" hidden></p>
            <img id="pg-image" class="cp-pg-image" alt="Rendered first frame" hidden>
            <p id="pg-open-row" hidden>
              <a id="pg-open" class="cp-doc-btn" href="#" rel="noopener">Open preview →</a>
            </p>
            <ul id="pg-previews" class="cp-pg-diags" hidden
              aria-label="Previews declared by this snippet"></ul>
          </div>
        </div>
        ${scriptTag("codemirror.js")}
        <script>${playgroundScript(suffix, catalogData, fileName)}</script>
        """
          .trimIndent(),
    )
  }

  /**
   * Drives the playground editor: POST snippet + mode, render diagnostics/first frame, and surface
   * the `/pg/<token>` or `/d/<id>` handoff link. Dependency-free so the page is self-contained.
   */
  private fun playgroundScript(
    querySuffix: String,
    catalogsJson: String,
    fileName: String,
  ): String =
    """
    (function () {
      var source = document.getElementById("pg-source");
      var mode = document.getElementById("pg-mode");
      var catalog = document.getElementById("pg-catalog");
      var run = document.getElementById("pg-run");
      var editLeaseButton = document.getElementById("pg-edit-lease");
      var editLeaseNote = document.getElementById("pg-edit-lease-note");
      var fileBar = document.getElementById("pg-files");
      var addFile = document.getElementById("pg-add-file");
      var removeFile = document.getElementById("pg-remove-file");
      var statusEl = document.getElementById("pg-status");
      var diags = document.getElementById("pg-diagnostics");
      var result = document.getElementById("pg-result");
      var image = document.getElementById("pg-image");
      var openRow = document.getElementById("pg-open-row");
      var openLink = document.getElementById("pg-open");
      var note = document.getElementById("pg-preview-note");
      var previewList = document.getElementById("pg-previews");
      var suffix = ${jsString(querySuffix)};
      var editLease = null;
      var editRevision = 0;
      // One holder per document/tab: closing one tab must not tear down another tab's shared
      // owner-wide incremental workspace.
      var editClient = (window.crypto && typeof window.crypto.randomUUID === "function")
        ? window.crypto.randomUUID()
        : (Date.now().toString(36) + "-" + Math.random().toString(36).slice(2));
      function releaseEditLeaseOnDiscard() {
        if (!editLease) return;
        var body = JSON.stringify({ lease: editLease, client: editClient });
        var url = "/api/1/compiler/edit-lease/release" + suffix;
        editLease = null;
        if (navigator.sendBeacon) {
          navigator.sendBeacon(url, new Blob([body], { type: "application/json" }));
        } else {
          fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: body,
            credentials: "same-origin",
            keepalive: true
          }).catch(function () {});
        }
      }
      window.addEventListener("pagehide", function (event) {
        // A bfcache page is still alive and may be restored; an actually discarded page should
        // surrender the one host-wide lease immediately. Delivery remains best-effort, with the
        // server TTL as the hard fallback.
        if (!event.persisted) releaseEditLeaseOnDiscard();
      });
      function setEditLease(value, message, expiresAt, revision) {
        editLease = value;
        editRevision = value && Number.isFinite(revision) ? revision : 0;
        if (!editLeaseButton) return;
        editLeaseButton.disabled = false;
        editLeaseButton.textContent = value ? "Release editing lease" : "Acquire editing lease";
        editLeaseButton.setAttribute("aria-pressed", value ? "true" : "false");
        editLeaseNote.hidden = !message;
        editLeaseNote.textContent = message || "";
        if (value && expiresAt) {
          editLeaseButton.title = "Lease expires " + new Date(expiresAt).toLocaleTimeString();
        } else {
          editLeaseButton.removeAttribute("title");
        }
      }
      if (editLeaseButton) {
        editLeaseButton.addEventListener("click", function () {
          editLeaseButton.disabled = true;
          if (!editLease) {
            fetch("/api/1/compiler/edit-lease" + suffix, {
              method: "POST",
              headers: { "Accept": "application/json", "Content-Type": "application/json" },
              body: JSON.stringify({ client: editClient })
            })
              .then(function (r) {
                return r.json().then(function (body) {
                  if (!r.ok) throw new Error(body.message || "The editing lease is busy.");
                  return body;
                });
              })
              .then(function (body) {
                setEditLease(body.lease, body.message, body.expiresAtEpochMs, body.revision);
              })
              .catch(function (e) {
                setEditLease(null, e.message || "Could not acquire editing lease.");
              });
          } else {
            var releasing = editLease;
            fetch("/api/1/compiler/edit-lease/release" + suffix, {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ lease: releasing, client: editClient })
            })
              .then(function (r) {
                if (!r.ok) throw new Error("The editing lease has already expired.");
                setEditLease(null, "Editing lease released.");
              })
              .catch(function (e) { setEditLease(null, e.message); });
          }
        });
      }
      // The catalog selector. Each entry carries its own mode list because a catalog's bundle
      // backend picks the renderer — selecting `compose-m3` (desktop) and selecting an Android
      // catalog are not the same choice with a different classpath, they are different modes.
      var catalogs = JSON.parse($catalogsJson).catalogs || [];
      var modeLabels = {${
      PlaygroundMode.entries.joinToString(", ") { m ->
        val (value, label) = playgroundModeChoice(m)
        "${jsString(value)}: ${jsString(label)}"
      }
    }};
      function selectedCatalog() {
        var id = catalog ? catalog.value : "";
        for (var i = 0; i < catalogs.length; i++) if (catalogs[i].id === id) return catalogs[i];
        return catalogs.length ? catalogs[0] : null;
      }
      // Repopulate Mode from the selected catalog, keeping the current mode when that catalog still
      // offers it — switching between two desktop catalogs must not silently reset the mode.
      function syncModes() {
        var entry = selectedCatalog();
        var wanted = mode.value;
        var offered = entry ? (entry.modes || []) : [];
        mode.innerHTML = "";
        offered.forEach(function (m) {
          var opt = document.createElement("option");
          opt.value = m;
          opt.textContent = modeLabels[m] || m;
          mode.appendChild(opt);
        });
        if (offered.indexOf(wanted) >= 0) mode.value = wanted;
        mode.disabled = offered.length === 0;
        run.disabled = offered.length === 0;
      }
      // Catalogs are fetched in the BACKGROUND after the server starts, so a page opened during
      // startup legitimately renders a short (or empty) list. Re-ask rather than making the visitor
      // guess that a reload would help.
      //
      // ONE fetch is not enough: on a host with nothing pinned the editor commonly loads before the
      // initial catalog loader has published anything, so the single reply is empty too and nothing
      // would ever ask again — a permanently disabled Run on a host that came up fine seconds later.
      // So poll while the answer is still empty, bounded (a host that genuinely serves no compilable
      // catalog must not poll forever), and stop the moment something is offered.
      var emptyPolls = 0;
      var MAX_EMPTY_POLLS = 12;
      var POLL_MS = 2500;
      function refreshCatalogs() {
        fetch("/api/1/compiler/catalogs" + suffix, { headers: { "Accept": "application/json" } })
          .then(function (r) { return r.ok ? r.json() : null; })
          .then(function (res) {
            if (!res || !res.catalogs) return;
            var previous = catalog ? catalog.value : "";
            catalogs = res.catalogs;
            if (catalog) {
              catalog.innerHTML = "";
              catalogs.forEach(function (c) {
                var opt = document.createElement("option");
                opt.value = c.id;
                opt.textContent = c.label;
                catalog.appendChild(opt);
              });
              if (!catalogs.length) {
                var none = document.createElement("option");
                none.value = ""; none.disabled = true; none.selected = true;
                none.textContent = "No catalogs available yet…";
                catalog.appendChild(none);
              } else {
                var keep = false;
                for (var i = 0; i < catalogs.length; i++) {
                  if (catalogs[i].id === previous) keep = true;
                }
                catalog.value = keep ? previous : catalogs[0].id;
              }
            }
            var empty = document.getElementById("pg-empty");
            if (empty) empty.hidden = catalogs.length > 0;
            syncModes();
            if (!catalogs.length && ++emptyPolls < MAX_EMPTY_POLLS) {
              window.setTimeout(refreshCatalogs, POLL_MS);
            }
          })
          .catch(function () { /* the baked-in list still stands */ });
      }
      if (catalog) {
        catalog.addEventListener("change", syncModes);
        // Opening the dropdown is the one moment a stale list actually costs the visitor something,
        // and it's a cheap place to catch catalogs that finished loading after the poll gave up.
        catalog.addEventListener("focus", refreshCatalogs);
      }
      syncModes();
      // Unconditional, not just when there is a selector: a page opened before the host's own pinned
      // bundle finished resolving renders with no modes at all, and the refresh is what recovers it
      // without asking the visitor to reload.
      refreshCatalogs();
      // CodeMirror over the textarea when the vendored bundle loaded, plain textarea when it
      // didn't. Every read/write of the buffer goes through readSource/writeSource, so a failed
      // asset fetch degrades to exactly the pre-editor behaviour instead of a dead page — the
      // editor is a convenience, and the compile lane is the feature.
      var editor = null;
      if (window.CodeMirror) {
        editor = window.CodeMirror.fromTextArea(source, {
          mode: "text/x-kotlin",
          lineNumbers: true,
          // `fromTextArea` hides the original textarea, which takes its aria-label out of the
          // accessibility tree with it. CodeMirror only names its own generated input through
          // this option, so without it a screen reader announces an unlabelled edit box.
          screenReaderLabel: "Kotlin source",
          indentUnit: 4,
          // Kotlin is space-indented; without this Tab inserts a literal tab that the compiler
          // accepts but nobody wants pasted back into a file.
          indentWithTabs: false,
          matchBrackets: true,
          viewportMargin: Infinity,
        });
        // Tab as INDENT, not focus-escape. A code box that swallows Tab is a keyboard trap, so
        // Esc first moves focus out — the standard escape hatch (WCAG 2.1.2).
        editor.setOption("extraKeys", {
          Tab: function (cm) { cm.execCommand("indentMore"); },
          "Shift-Tab": function (cm) { cm.execCommand("indentLess"); },
          Esc: function (cm) { cm.getInputField().blur(); },
          "Ctrl-Enter": function () { run.click(); },
          "Cmd-Enter": function () { run.click(); },
        });
      }
      function readSource() { return editor ? editor.getValue() : source.value; }
      function writeSource(text) {
        if (editor) editor.setValue(text); else source.value = text;
      }
      // CodeMirror diagnostics are deliberately drawn by this page instead of depending on its
      // optional lint addon: the compiler already returns exact 0-based locations, and keeping the
      // tiny renderer here preserves the editor's plain-textarea fallback. The list below the
      // editor remains the accessible summary; these widgets put each message beside the code that
      // caused it, which is where it is useful while fixing a failed compile.
      var editorDiags = [];
      var latestDiags = [];
      function clearEditorDiags() {
        if (!editor) return;
        editorDiags.forEach(function (entry) {
          if (entry.widget) entry.widget.clear();
          if (entry.lineHandle) editor.removeLineClass(entry.lineHandle, "background", entry.lineClass);
          if (entry.mark) entry.mark.clear();
        });
        editorDiags = [];
      }
      function renderEditorDiags() {
        clearEditorDiags();
        if (!editor) return;
        var file = files[active];
        latestDiags.forEach(function (d) {
          if (d.file !== file.name || d.line == null || d.line < 0 || d.line >= editor.lineCount()) return;
          var severity = d.severity || "info";
          var lineClass = "cp-pg-line-" + severity;
          // CodeMirror moves this handle with the line when edits are inserted above it. Keeping
          // the original numeric index would clear whichever line later occupied that position and
          // strand the actual diagnostic highlight after the next compile.
          var lineHandle = editor.addLineClass(d.line, "background", lineClass);
          var message = document.createElement("div");
          message.className = "cp-pg-inline-diag cp-pg-inline-" + severity;
          // The live summary below already announces the same diagnostic. Keep this visual copy
          // out of the accessibility tree so a compile failure is not read twice.
          message.setAttribute("aria-hidden", "true");
          // Only the head line goes inline. A K2 overload failure is a head plus a block per
          // candidate plus a caret excerpt, and pasting all of it into a line widget pushes the
          // code the reader is editing off the screen. The full text stays one glance away, in the
          // summary below and in this widget's tooltip.
          var head = String(d.message).split("\n")[0];
          message.textContent = head === d.message ? d.message : head + " …";
          message.title = d.message;
          var widget = editor.addLineWidget(d.line, message, { coverGutter: false, noHScroll: true });
          var mark = null;
          if (d.ch != null) {
            var lineText = editor.getLine(d.line) || "";
            var start = Math.max(0, Math.min(d.ch, lineText.length));
            var endLine = d.endLine == null ? d.line : Math.max(d.line, d.endLine);
            var endCh = d.endCh == null ? Math.min(lineText.length, start + 1) : d.endCh;
            if (endLine !== d.line || endCh > start) {
              mark = editor.markText(
                { line: d.line, ch: start },
                { line: endLine, ch: endCh },
                { className: "cp-pg-range-" + severity }
              );
            }
          }
          editorDiags.push({ widget: widget, lineHandle: lineHandle, lineClass: lineClass, mark: mark });
        });
      }
      // The snippet is a LIST of files compiled as one module, not one file: `files` holds every
      // buffer, `active` is the one the textarea is showing. A single-file snippet keeps exactly
      // the old shape, so nothing about the common case changes.
      var files = [{ name: ${jsString(fileName)}, text: readSource() }];
      var active = 0;
      function uniqueName(name) {
        var taken = {}; files.forEach(function (f) { taken[f.name.toLowerCase()] = true; });
        if (!taken[name.toLowerCase()]) return name;
        var stem = name.replace(/\.kt${'$'}/, "");
        for (var i = 1; ; i++) {
          var candidate = stem + "_" + i + ".kt";
          if (!taken[candidate.toLowerCase()]) return candidate;
        }
      }
      function renderFiles() {
        // Rebuild the tab strip from `files`; the +/- buttons are kept, not recreated.
        var tabs = fileBar.querySelectorAll("[data-pg-file]");
        for (var i = 0; i < tabs.length; i++) fileBar.removeChild(tabs[i]);
        files.forEach(function (f, i) {
          var tab = document.createElement("button");
          tab.type = "button";
          tab.className = "cp-pg-file";
          tab.setAttribute("role", "tab");
          tab.setAttribute("data-pg-file", f.name);
          tab.setAttribute("aria-current", i === active ? "true" : "false");
          tab.textContent = f.name;
          tab.addEventListener("click", function () { showFile(i); });
          fileBar.insertBefore(tab, addFile);
        });
        removeFile.hidden = files.length < 2;
      }
      addFile.addEventListener("click", function () {
        files[active].text = readSource();
        // Auto-named rather than prompted: Kotlin does not tie declarations to a file name, so the
        // name only ever shows up in diagnostics — not worth a modal dialog on every added file.
        var name = uniqueName("File" + (files.length + 1) + ".kt");
        files.push({ name: name, text: "" });
        active = files.length - 1;
        writeSource("");
        renderFiles();
        if (editor) editor.focus(); else source.focus();
      });
      removeFile.addEventListener("click", function () {
        if (files.length < 2) return;
        files.splice(active, 1);
        active = Math.min(active, files.length - 1);
        writeSource(files[active].text);
        renderFiles();
        // Removing the active file is also a tab transition. Repaint the retained diagnostics for
        // the buffer that just became active instead of leaving its messages hidden until a click
        // or another compile happens to refresh them.
        renderEditorDiags();
      });
      renderFiles();
      function setStatus(text, isError) {
        statusEl.hidden = false;
        statusEl.className = "cp-pg-status" + (isError ? " cp-doc-error" : "");
        statusEl.textContent = text;
      }
      function clearOut() {
        latestDiags = [];
        clearEditorDiags();
        diags.hidden = true; diags.innerHTML = "";
        result.hidden = true; image.hidden = true; image.removeAttribute("src"); openRow.hidden = true;
        note.hidden = true; note.textContent = "";
        previewList.hidden = true; previewList.innerHTML = "";
      }
      function indexOfFile(name) {
        for (var i = 0; i < files.length; i++) if (files[i].name === name) return i;
        return -1;
      }
      function showFile(i) {
        if (i < 0 || i === active) return;
        files[active].text = readSource();
        active = i;
        writeSource(files[active].text);
        renderFiles();
        renderEditorDiags();
      }
      function renderDiags(list) {
        latestDiags = list || [];
        renderEditorDiags();
        if (!latestDiags.length) return;
        diags.hidden = false;
        latestDiags.forEach(function (d) {
          var li = document.createElement("li");
          li.className = "cp-pg-diag cp-pg-" + (d.severity || "info");
          // With several buffers open, "unresolved reference at line 5" is useless without the
          // file — the server keys diagnostics by basename, so name it and, when that file is one
          // of ours, make the entry jump to its tab.
          var owner = indexOfFile(d.file || "");
          var where = (d.file ? d.file : "") + ((d.line != null) ? (":" + (d.line + 1)) : "");
          // The location leads rather than trails: a K2 diagnostic runs to several lines (candidate
          // blocks, a `^^^^` excerpt), and a trailing "(Snippet.kt:31)" landed under the caret at
          // the far end of the message instead of beside the thing it locates.
          li.textContent = (d.severity || "info") + ": " + (where ? where + " — " : "") + d.message;
          if (owner >= 0) {
            li.style.cursor = "pointer";
            li.title = "Show " + d.file + (d.line != null ? ":" + (d.line + 1) : "");
            li.addEventListener("click", function () {
              showFile(owner);
              if (editor && d.line != null) {
                editor.setCursor({ line: d.line, ch: d.ch || 0 });
                editor.scrollIntoView({ line: d.line, ch: d.ch || 0 }, 80);
                editor.focus();
              }
            });
          }
          diags.appendChild(li);
        });
      }
      // A monotonic id fences stale runs: only the newest click updates the DOM, and Run is disabled
      // while a compile is in flight so a burst can't double-submit (each submit mints a token).
      var reqId = 0;
      run.addEventListener("click", function () {
        var myId = ++reqId;
        run.disabled = true;
        clearOut();
        setStatus("Compiling…", false);
        files[active].text = readSource();
        var body = JSON.stringify({
          confType: mode.value,
          catalog: catalog ? catalog.value : "",
          files: files,
          editLease: editLease || "",
          revision: editLease ? ++editRevision : 0
        });
        fetch("/api/1/compiler/run" + suffix, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: body
        })
          .then(function (r) {
            return r.text().then(function (t) {
              if (!r.ok) throw new Error(t || ("run failed (" + r.status + ")"));
              return JSON.parse(t);
            });
          })
          .then(function (res) {
            if (myId !== reqId) return;
            run.disabled = false;
            if (editLeaseButton) editLeaseButton.disabled = false;
            renderDiags(res.diagnostics);
            var hasError = (res.diagnostics || []).some(function (d) { return d.severity === "error"; });
            // A snippet that compiled and whose first frame failed still minted a live session: say
            // why there is no still image, and keep going so its link is offered anyway.
            var frameFailed = !!(res.exception && res.previewToken);
            if (res.exception && !frameFailed) {
              if (res.exception.indexOf("live-edit lease") >= 0) setEditLease(null, res.exception);
              setStatus(res.exception, true); return;
            }
            if (hasError) {
              setStatus(
                res.revision != null
                  ? ("Compilation failed at revision " + res.revision +
                    (res.incremental ? " (incremental)." : " (full fallback)."))
                  : "Compilation failed.",
                true
              );
              return;
            }
            result.hidden = false;
            if (res.image) { image.hidden = false; image.src = res.image; }
            var link = res.documentUrl || res.previewUrl;
            if (link) {
              openRow.hidden = false;
              openLink.href = link + suffix;
              openLink.textContent = res.documentUrl ? "Open document →" : "Open live preview →";
            }
            // A snippet routinely declares more than one @Preview, and only the first drives the
            // still frame. The rest are compiled and live in the same session, so list every one as
            // its own link — `?preview=<id>` opens the session on it — rather than naming the drawn
            // one and leaving the others unreachable. Kept out of the status line, which stays the
            // terminal "Done." the e2e keys on.
            var all = res.previews || [];
            if (res.previewId && all.length > 1) {
              note.hidden = false;
              note.textContent =
                "Rendered " + res.previewId + " — " + all.length + " previews in this snippet.";
            }
            // Only the live-preview lane can open on a chosen preview; a documentUrl addresses a
            // rendered document, which `?preview=` means nothing to. So the per-preview links hang
            // off res.previewUrl specifically rather than the `link` that may be either.
            if (res.previewUrl && all.length > 1) {
              previewList.hidden = false;
              previewList.innerHTML = "";
              all.forEach(function (id) {
                var li = document.createElement("li");
                li.className = "cp-pg-diag cp-pg-info";
                var a = document.createElement("a");
                // Same `/pg/<token>` redemption the main link uses, plus the preview to open on.
                // The token rides in `suffix`, so `?`/`&` depends on whether it is already there.
                a.href = res.previewUrl + suffix + (suffix ? "&" : "?") +
                  "preview=" + encodeURIComponent(id);
                a.rel = "noopener";
                a.textContent = id === res.previewId ? (id + " (shown above)") : id;
                li.appendChild(a);
                previewList.appendChild(li);
              });
            }
            if (frameFailed) setStatus(res.exception, true);
            else setStatus("Done.", false);
            if (res.revision != null && editLeaseNote) {
              editLeaseNote.hidden = false;
              editLeaseNote.textContent = "Revision " + res.revision +
                (res.incremental ? " compiled incrementally." : " used a full compile.");
            }
          })
          .catch(function (e) {
            if (myId !== reqId) return;
            run.disabled = false;
            if (editLeaseButton) editLeaseButton.disabled = false;
            setStatus(e.message || "run failed", true);
          });
      });
    })();
    """
      .trimIndent()

  fun playgroundDisabledPage(
    token: String,
    isPublic: Boolean,
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
  ): String {
    val suffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    return document(
      title = "Playground unavailable — compose-preview",
      unfurlDescription = "The playground is not enabled on this server.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">Playground unavailable</h1>
        <p class="cp-sub">
          This server was started without a playground bundle, so it can browse design systems and
          run live previews but cannot compile playground snippets.
        </p>
        <p class="cp-sub">
          Configure <code>--playground</code> to compile against any catalog this server already
          serves, or pin one with <code>--playground-bundle</code> /
          <code>--playground-android-bundle</code>. On public servers also configure
          <code>--playground-sandbox</code>.
        </p>
        <a class="cp-back" href="/$suffix">← All design systems</a>
        """
          .trimIndent(),
    )
  }

  /** The `<option>` value + label for a playground mode in the editor's selector. */
  private fun playgroundModeChoice(mode: PlaygroundMode): Pair<String, String> =
    when (mode) {
      PlaygroundMode.CMP -> "compose-cmp" to "Compose (Desktop)"
      PlaygroundMode.ANDROID -> "compose-android" to "Compose (Android)"
      PlaygroundMode.REMOTE_COMPOSE -> "remote-compose" to "Remote Compose"
    }

  /**
   * One ingested document's display facts for the permalink page, so the page never touches
   * [ServeDocStore]'s bytes or clock.
   */
  data class DocView(
    val id: String,
    /** Display label (the uploaded filename, sanitised by the store). */
    val name: String,
    /** [ServeDocFormat.id] — picks the player + the mount code. */
    val formatId: String,
    val formatLabel: String,
    /** Where the browser player bundle for this format is served. */
    val playerPath: String,
    /** Where the document bytes are served (`/d/<id>/raw`). */
    val rawPath: String,
    val facts: List<ServeDocFact>,
    val sizeText: String,
    /** Human "in 59m" form for the expiry pill. */
    val expiresInText: String,
    /** Absolute UTC instant the link dies, for the title attribute. */
    val expiresAtText: String,
    /** Declared document size, when the format announces one — sizes the canvas before load. */
    val width: Int? = null,
    val height: Int? = null,
  )

  /**
   * `GET /docs`: the upload surface for known formats — drop a Remote Compose `.rc` or a Lottie
   * JSON (or paste a link, when URL fetches are allowed) and get an expiring permalink.
   *
   * The drop zone is a real `<input type="file">` in a `<form>`; the script turns submit into a
   * `fetch` to show the link in place. Nothing uploads without an explicit pick/drop.
   */
  fun docUploadPage(
    token: String,
    isPublic: Boolean,
    ttlSeconds: Long,
    /** Whether `?url=` fetches are permitted here (the SSRF allowlist is non-empty). */
    urlUploadAllowed: Boolean,
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
  ): String {
    val query = queryString(token, sessionId = null, isPublic = isPublic)
    val suffix = querySuffix(query)
    val formats =
      ServeDocFormats.ALL.joinToString(", ") { "${it.label} (<code>${it.extension}</code>)" }
    val urlRow =
      if (!urlUploadAllowed) ""
      else
        """
        <form class="cp-doc-form" id="cp-doc-urlform">
          <input class="cp-doc-url" id="cp-doc-url" type="url" name="url" placeholder="…or paste a link to a document"
            aria-label="Document URL">
          <button class="cp-doc-btn" type="submit">Fetch</button>
        </form>
        """
          .trimIndent()
    return document(
      title = "Share a document — compose-preview",
      unfurlDescription = "Upload a Remote Compose or Lottie document and get an expiring link.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">Share a document</h1>
        <p class="cp-sub">Upload a generated document and get a link that plays it in the browser and
          expires after ${humanDuration(ttlSeconds)}. Supported: $formats.</p>
        <form id="cp-doc-form" class="cp-drop" tabindex="0">
          <span class="cp-drop-title">Drop a document here, or choose a file</span>
          <span class="cp-drop-hint">Nothing is executed on the server — the document is played back
            by a player running in your own browser.</span>
          <input id="cp-doc-file" type="file" name="file" accept=".rc,.json,application/json">
        </form>
        $urlRow
        <div class="cp-doc-result" id="cp-doc-result" hidden></div>
        <script>${docUploadScript(suffix)}</script>
        """
          .trimIndent(),
    )
  }

  /** The knob key the A2UI playground edits: `previewOverrideString("document", …)`. */
  const val A2UI_DOCUMENT_KNOB: String = "document"

  /** Longer than this, or multi-line, and a string knob is edited in a `<textarea>`. */
  private const val LONG_TEXT_KNOB_CHARS = 120

  private fun isLongTextKnob(default: String): Boolean =
    default.contains('\n') || default.length > LONG_TEXT_KNOB_CHARS

  /**
   * The preview the A2UI playground drives: the first declaring a **string** knob named
   * [A2UI_DOCUMENT_KNOB]. Null makes `/{system}/a2ui` a 404. Keyed on the declaration so catalogs
   * can rename the preview freely.
   */
  fun a2uiDocumentPreview(previews: List<ServePreview>): ServePreview? =
    previews.firstOrNull { preview ->
      a2uiDocumentKnob(preview) != null
    }

  /**
   * [a2uiDocumentPreview], or [requested] when it declares the knob. A requested id that doesn't is
   * null, not a fallback.
   */
  fun a2uiDocumentPreview(previews: List<ServePreview>, requested: String?): ServePreview? =
    if (requested.isNullOrBlank()) a2uiDocumentPreview(previews)
    else previews.firstOrNull { it.id == requested }?.takeIf { a2uiDocumentKnob(it) != null }

  private fun a2uiDocumentKnob(
    preview: ServePreview
  ): ee.schimke.composeai.data.overrides.PreviewOverrideDeclaration? =
    preview.overrides.firstOrNull {
      it.seedKey == A2UI_DOCUMENT_KNOB &&
        it.type == ee.schimke.composeai.data.overrides.PreviewOverrideType.STRING
    }

  /**
   * `GET /{system}/a2ui`: edit an A2UI document and render it through the catalog's renderer.
   *
   * The textarea is prefilled with the declared default and POSTed as `knob.document` to
   * `<base>/render/<id>.png` (too large for a GET). The PNG is shown via a blob URL; refusals are
   * shown in place. Ctrl/Cmd+Enter renders, as does a typing pause with auto-render ticked.
   */
  fun a2uiPlaygroundPage(
    moduleLabel: String,
    preview: ServePreview,
    token: String,
    sessionId: String?,
    basePath: String,
    isPublic: Boolean,
    /** Whether this catalog can render an override at all; a static bundle cannot. */
    liveAvailable: Boolean,
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
  ): String {
    val suffix = querySuffix(linkQuery(token, sessionId, basePath, isPublic))
    val encodedId = WebEscaping.urlEncodeSegment(preview.id)
    val renderUrl = "$basePath/render/$encodedId.png$suffix"
    val viewerUrl = "$basePath/p/$encodedId$suffix"
    val default = a2uiDocumentKnob(preview)?.default?.let(::overrideValueText).orEmpty()
    val esc = WebEscaping::htmlEscape
    val staticNote =
      if (liveAvailable) ""
      else
        """
        <p class="cp-pg-warn">This catalog is a static bundle: it can show the published render but
          cannot render an edited document. Point it at a live catalog to use the playground.</p>"""
    // Built by concatenation around the textarea: the default document is multi-line, and a
    // `trimIndent()` over the interpolated page would re-indent it.
    val head =
      """
      <link rel="stylesheet" href="${assetHref("playground.css")}">
      <h1 class="cp-head">A2UI playground</h1>
      <p class="cp-sub">Edit an A2UI document — JSON Lines of v0.9 messages, a JSON array of them, or
        a <code>{"components":[…]}</code> shorthand — and render it with
        <code>${esc(moduleLabel)}</code>'s own components. Rendering an edited document is a live
        render, so it needs a signed-in session or a <code>live</code> agent grant.
        <a href="${esc(viewerUrl)}">Open the ${esc(preview.label)} preview →</a></p>
      <div class="cp-pg">$staticNote
        <div class="cp-pg-bar">
          <label class="cp-pg-modelabel"><input id="a2ui-auto" type="checkbox"> Auto-render</label>
          <span class="cp-muted">Ctrl/⌘ + Enter renders</span>
          <button id="a2ui-run" class="cp-doc-btn cp-pg-run" type="button">Render</button>
        </div>
      """
        .trimIndent()
    val tail =
      """
        <div id="a2ui-status" class="cp-pg-status" role="status" hidden></div>
        <img id="a2ui-image" class="cp-pg-image" alt="Rendered A2UI document" hidden>
      </div>
      <script>${a2uiPlaygroundScript(renderUrl)}</script>
      """
        .trimIndent()
    return document(
      title = "A2UI playground — ${moduleLabel} — compose-preview",
      unfurlDescription = "Edit an A2UI document and render it with the catalog's components.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        head +
          "\n  <textarea id=\"a2ui-source\" class=\"cp-pg-source\" spellcheck=\"false\"" +
          " aria-label=\"A2UI document\">\n" +
          esc(default) +
          "</textarea>\n" +
          tail,
    )
  }

  private fun a2uiPlaygroundScript(renderUrl: String): String =
    """
    (function () {
      var source = document.getElementById("a2ui-source");
      var run = document.getElementById("a2ui-run");
      var auto = document.getElementById("a2ui-auto");
      var status = document.getElementById("a2ui-status");
      var image = document.getElementById("a2ui-image");
      var url = ${jsString(renderUrl)};
      var seq = 0, timer = null, objectUrl = null;
      function show(text, isError) {
        status.hidden = !text;
        status.className = "cp-pg-status" + (isError ? " cp-doc-error" : "");
        status.textContent = text || "";
      }
      function hint(res, body) {
        if (res.status === 401 || res.status === 403)
          return "Not allowed to render an edited document (HTTP " + res.status + "). Sign in, or " +
            "ask for a live grant: compose-preview auth request --scope live. " + body;
        if (res.status === 413) return "The document is larger than the 1 MiB render limit.";
        if (res.status === 503) {
          var after = res.headers.get("Retry-After");
          return "The renderer is busy" + (after ? "; retry in " + after + "s." : ".") + " " + body;
        }
        return "HTTP " + res.status + ": " + body;
      }
      function render() {
        var mine = ++seq;
        show("Rendering…", false);
        run.disabled = true;
        fetch(url, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ "knob.$A2UI_DOCUMENT_KNOB": source.value }),
          credentials: "same-origin"
        }).then(function (res) {
          if (!res.ok) return res.text().then(function (t) { throw new Error(hint(res, t)); });
          return res.blob();
        }).then(function (blob) {
          if (mine !== seq) return;
          if (objectUrl) URL.revokeObjectURL(objectUrl);
          objectUrl = URL.createObjectURL(blob);
          image.src = objectUrl;
          image.hidden = false;
          show("", false);
        }, function (e) {
          if (mine !== seq) return;
          show(e.message || String(e), true);
        }).then(function () { if (mine === seq) run.disabled = false; });
      }
      run.addEventListener("click", render);
      source.addEventListener("keydown", function (e) {
        if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); render(); }
      });
      source.addEventListener("input", function () {
        if (!auto.checked) return;
        if (timer) clearTimeout(timer);
        timer = setTimeout(render, 800);
      });
    })();
    """
      .trimIndent()

  /**
   * `GET /admin/ui-builder`: the operator's view over every UI-builder design on the host.
   *
   * List and delete go through the JSON routes under `/admin/ui-builder/designs` with the
   * [ee.schimke.composeai.cli.serve.ServeHttpServer.ADMIN_TOKEN_HEADER] header, re-sending the
   * `?token=` the page was opened with. The script strips `token` from the address bar on load and
   * links don't carry it. Without a token every call 404s, which the page says. Delete is
   * confirm-then-DELETE (no undo), naming the design and owner.
   */
  fun uiBuilderAdminPage(
    adminToken: String?,
    readOnly: Boolean = false,
    version: String? = null,
  ): String {
    val warning =
      if (readOnly)
        "\n        <p class=\"cp-grant-withheld\">Read-only diagnosis: document bodies and every change are withheld.</p>"
      else ""
    val library =
      if (readOnly) ""
      else
        """
        <h2 class="cp-head">From the projects</h2>
        <p class="cp-sub">Designs the projects this host reads are working on, kept in
          <code>ui-builder/designs/</code> in their own repository. Opening one copies it here to
          carry on with; the file is untouched, editing here never writes back on its own, and
          opening it again does nothing once it is here.</p>
        <div class="cp-admin-bar">
          <span id="cp-library-count" class="cp-muted"></span>
          <button class="cp-doc-btn" id="cp-library-reload" type="button">Reload</button>
        </div>
        <div class="cp-doc-result" id="cp-library-status" hidden></div>
        <div class="cp-status-scroll"><table class="cp-table cp-admin-table" id="cp-library-table">
          <thead><tr><th>Design</th><th>Catalog</th><th>What it is</th><th></th></tr></thead>
          <tbody id="cp-library-rows"></tbody>
        </table></div>
        <script>${uiBuilderLibraryScript(adminToken)}</script>
        """
          .trimIndent()
    return document(
      title = "UI-builder designs — admin — compose-preview",
      version = version,
      body =
        """
        <h1 class="cp-head">UI-builder designs</h1>
        <p class="cp-sub">Every design this host holds, whoever owns it. Deleting one removes its
          history, its reference overlay and its comments, and closes any editor that has it open.
          There is no undo.</p>$warning
        <div class="cp-admin-bar">
          <span id="cp-admin-count" class="cp-muted"></span>
          <button class="cp-doc-btn" id="cp-admin-reload" type="button">Reload</button>
        </div>
        <div class="cp-doc-result" id="cp-admin-status" hidden></div>
        <div class="cp-status-scroll"><table class="cp-table cp-admin-table" id="cp-admin-table">
          <thead><tr>
            <th>Design</th><th>Catalog</th><th>Owner</th><th>Rev</th><th>Open</th>
            <th>Created</th><th>Updated</th><th></th>
          </tr></thead>
          <tbody id="cp-admin-rows"></tbody>
        </table></div>

        <script>${uiBuilderAdminScript(adminToken, readOnly)}</script>$library
        """
          .trimIndent(),
    )
  }

  /**
   * Drives the second table (the configured projects' designs). A separate `<script>` so the two
   * tables fail independently.
   */
  private fun uiBuilderLibraryScript(adminToken: String?): String =
    """
    (function () {
      var rows = document.getElementById("cp-library-rows");
      var count = document.getElementById("cp-library-count");
      var status = document.getElementById("cp-library-status");
      var reload = document.getElementById("cp-library-reload");
      var token = ${jsString(adminToken.orEmpty())};
      var headers = token ? { "X-Compose-Preview-Admin-Token": token } : {};
      function show(text, isError) {
        status.hidden = false;
        status.className = "cp-doc-result" + (isError ? " cp-doc-error" : "");
        status.textContent = text;
      }
      function cell(row, text, mono) {
        var td = document.createElement("td");
        if (mono) { var c = document.createElement("code"); c.textContent = text; td.appendChild(c); }
        else td.textContent = text;
        row.appendChild(td);
        return td;
      }
      function empty(message) {
        var tr = document.createElement("tr");
        var td = cell(tr, message);
        td.colSpan = 4; td.className = "cp-muted";
        rows.appendChild(tr);
      }
      function render(body) {
        rows.textContent = "";
        var designs = body.designs || [];
        var searched = body.catalogsSearched || [];
        count.textContent = designs.length === 1 ? "1 design" : designs.length + " designs";
        if (!designs.length) {
          // The two empties mean different things and an operator has to be able to tell them
          // apart: nothing to look in, versus looked and found nothing published.
          empty(searched.length
            ? "None of the " + searched.length + " projects here has a design."
            : "No projects are configured here, so there is nothing to look in.");
          return;
        }
        designs.forEach(function (d) {
          var tr = document.createElement("tr");
          cell(tr, d.designId, true);
          cell(tr, d.system, true);
          cell(tr, d.description || "");
          var actions = document.createElement("td");
          var open = document.createElement("button");
          open.type = "button";
          open.className = "cp-doc-btn";
          open.textContent = "Open here";
          open.addEventListener("click", function () { openDesign(d, open); });
          actions.appendChild(open);
          tr.appendChild(actions);
          rows.appendChild(tr);
        });
      }
      function openDesign(design, button) {
        button.disabled = true;
        var url = "/admin/ui-builder/library/" + encodeURIComponent(design.system) +
          "/" + encodeURIComponent(design.designId);
        fetch(url, { method: "POST", headers: headers }).then(function (r) {
          if (!r.ok) return r.text().then(function (t) { throw new Error(t || r.statusText); });
          return r.json();
        }).then(function (result) {
          show(result.status === "opened"
            ? design.designId + " is now a design on this host."
            : design.designId + " was already open here.", false);
          // The host's own list is what changed, so refresh that one rather than this one.
          if (window.cpAdminReload) window.cpAdminReload();
        }).catch(function (e) {
          show("Could not open " + design.designId + ": " + e.message, true);
        }).then(function () { button.disabled = false; });
      }
      function load() {
        fetch("/admin/ui-builder/library", { headers: headers }).then(function (r) {
          if (!r.ok) return r.text().then(function (t) { throw new Error(t || r.statusText); });
          return r.json();
        }).then(render).catch(function (e) {
          rows.textContent = "";
          count.textContent = "";
          show("Could not read the projects' designs: " + e.message, true);
        });
      }
      reload.addEventListener("click", function () { status.hidden = true; load(); });
      load();
    })();
    """
      .trimIndent()

  /**
   * Drives the admin page: fetch the design list, render rows, download a stored document,
   * confirm-then-DELETE.
   */
  private fun uiBuilderAdminScript(adminToken: String?, readOnly: Boolean): String =
    """
    (function () {
      var rows = document.getElementById("cp-admin-rows");
      var count = document.getElementById("cp-admin-count");
      var status = document.getElementById("cp-admin-status");
      var reload = document.getElementById("cp-admin-reload");
      var token = ${jsString(adminToken.orEmpty())};
      var readOnly = $readOnly;
      var headers = token ? { "X-Compose-Preview-Admin-Token": token } : {};
      // The token opened this page and lives on in `headers`; keep it out of the address bar,
      // history and any link copied from here.
      try {
        var here = new URL(window.location.href);
        if (here.searchParams.has("token")) {
          here.searchParams.delete("token");
          window.history.replaceState(null, "", here.pathname + here.search + here.hash);
        }
      } catch (e) {}
      function show(text, isError) {
        status.hidden = false;
        status.className = "cp-doc-result" + (isError ? " cp-doc-error" : "");
        status.textContent = text;
      }
      function hide() { status.hidden = true; }
      function cell(row, text, mono) {
        var td = document.createElement("td");
        if (mono) { var c = document.createElement("code"); c.textContent = text; td.appendChild(c); }
        else td.textContent = text;
        row.appendChild(td);
        return td;
      }
      function when(ms) {
        if (!ms) return "";
        var d = new Date(ms);
        var pad = function (n) { return (n < 10 ? "0" : "") + n; };
        return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate()) +
          " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
      }
      function render(designs) {
        rows.textContent = "";
        count.textContent = designs.length === 1 ? "1 design" : designs.length + " designs";
        if (!designs.length) {
          var empty = document.createElement("tr");
          var td = cell(empty, "No designs on this host.");
          td.colSpan = 8; td.className = "cp-muted";
          rows.appendChild(empty);
          return;
        }
        designs.forEach(function (d) {
          var tr = document.createElement("tr");
          tr.setAttribute("data-design-id", d.designId);
          var title = cell(tr, "");
          var strong = document.createElement("strong");
          strong.textContent = d.title || "(untitled)";
          title.appendChild(strong);
          title.appendChild(document.createElement("br"));
          var id = document.createElement("code");
          id.textContent = d.designId;
          title.appendChild(id);
          // An unusable design is listed rather than hidden — it is still on disk and still the
          // operator's to repair or retire — so the row has to say why it is not being served.
          // Reuses the viewer's warning badge so an operator reads it as the same kind of notice.
          if (d.unusableReason) {
            title.appendChild(document.createElement("br"));
            var unusable = document.createElement("span");
            unusable.className = "cp-badge cp-badge--unverified";
            unusable.title = d.unusableReason;
            unusable.textContent = "\u26A0 unusable: " + d.unusableReason;
            title.appendChild(unusable);
          }
          cell(tr, d.catalogSystemId, true);
          cell(tr, d.ownerActorId + (d.collaborators ? " +" + d.collaborators : ""), true);
          cell(tr, String(d.revision));
          cell(tr, String(d.activeSubscribers));
          cell(tr, when(d.createdAtEpochMillis));
          cell(tr, when(d.updatedAtEpochMillis));
          var actions = cell(tr, "");
          if (readOnly) {
            actions.textContent = "read only";
            rows.appendChild(tr);
            return;
          }
          // Offered on every row, and the only action that works on an unusable one: a design the
          // host cannot serve can still be copied out, repaired against the current rules and
          // created again. Without it, Delete is the operator's only move and the document is lost.
          // Except where there is no document to copy out: a design whose own stored files would
          // not read has none, and a button that cannot work is worse than no button, because
          // retiring it is then the only move and nothing on the row would say so.
          if (d.documentAvailable !== false) {
            var save = document.createElement("button");
            save.type = "button";
            save.className = "cp-doc-btn cp-admin-download";
            save.textContent = "Download JSON";
            save.addEventListener("click", function () { download(d, save); });
            actions.appendChild(save);
          }
          // Only offered where it applies. A design the host serves is edited in the editor, with
          // an actor and a sequence; this is the door that exists because that one is closed to a
          // design the host will not load.
          if (d.unusableReason && d.documentAvailable !== false) {
            var fix = document.createElement("button");
            fix.type = "button";
            fix.className = "cp-doc-btn cp-admin-repair";
            fix.textContent = "Repair from file…";
            fix.addEventListener("click", function () { repair(d, fix); });
            actions.appendChild(fix);
          }
          var del = document.createElement("button");
          del.type = "button";
          del.className = "cp-doc-btn cp-admin-delete";
          del.textContent = "Delete";
          del.addEventListener("click", function () { remove(d, del); });
          actions.appendChild(del);
          rows.appendChild(tr);
        });
      }
      function failure(response) {
        if (response.status === 404 && !token) {
          return "This page needs a configured UI Builder administrator sign-in or admin token.";
        }
        return response.text().then(function (t) {
          return "HTTP " + response.status + (t ? ": " + t : "");
        });
      }
      function load() {
        hide();
        count.textContent = "Loading…";
        fetch("/admin/ui-builder/designs", { headers: headers, cache: "no-store" })
          .then(function (r) {
            if (!r.ok) return Promise.resolve(failure(r)).then(function (m) { throw new Error(m); });
            return r.json();
          })
          .then(function (body) { render(body.designs || []); })
          .catch(function (e) { count.textContent = ""; rows.textContent = ""; show(String(e.message || e), true); });
      }
      // Fetched rather than linked so the admin token travels in the header, as it does for every
      // other call on this page, instead of ending up in a URL the browser keeps in history.
      function download(d, button) {
        button.disabled = true;
        fetch("/admin/ui-builder/designs/" + encodeURIComponent(d.designId) + "/document",
              { headers: headers, cache: "no-store" })
          .then(function (r) {
            if (!r.ok) return Promise.resolve(failure(r)).then(function (m) { throw new Error(m); });
            return r.text();
          })
          .then(function (text) {
            var url = URL.createObjectURL(new Blob([text], { type: "application/json" }));
            var a = document.createElement("a");
            a.href = url;
            a.download = d.designId + ".json";
            a.click();
            URL.revokeObjectURL(url);
            button.disabled = false;
            hide();
          })
          .catch(function (e) { button.disabled = false; show(String(e.message || e), true); });
      }
      // Download, edit until it satisfies the rule that changed, put it back. The server checks the
      // candidate against exactly what held the original back, so a file that does not repair the
      // design comes back with what is still wrong rather than replacing it.
      function repair(d, button) {
        var picker = document.createElement("input");
        picker.type = "file";
        picker.accept = "application/json,.json";
        picker.addEventListener("change", function () {
          var file = picker.files && picker.files[0];
          if (!file) return;
          button.disabled = true;
          file.text().then(function (text) {
            return fetch("/admin/ui-builder/designs/" + encodeURIComponent(d.designId) + "/document",
                         { method: "PUT", headers: headers, body: text });
          })
            .then(function (r) {
              if (!r.ok) return Promise.resolve(failure(r)).then(function (m) { throw new Error(m); });
              return r.json();
            })
            .then(function (body) {
              show("Repaired " + d.designId + " at revision " + body.revision + ".", false);
              button.disabled = false;
              load();
            })
            .catch(function (e) { button.disabled = false; show(String(e.message || e), true); });
        });
        picker.click();
      }
      function remove(d, button) {
        var label = (d.title || "(untitled)") + " (" + d.designId + ", owned by " + d.ownerActorId + ")";
        if (!window.confirm("Delete " + label + "?\n\nThis removes its history, overlay and comments. There is no undo.")) return;
        button.disabled = true;
        fetch("/admin/ui-builder/designs/" + encodeURIComponent(d.designId), { method: "DELETE", headers: headers })
          .then(function (r) {
            if (!r.ok) return Promise.resolve(failure(r)).then(function (m) { throw new Error(m); });
            show("Deleted " + label + ".", false);
            load();
          })
          .catch(function (e) { button.disabled = false; show(String(e.message || e), true); });
      }
      reload.addEventListener("click", load);
      // Opening a design from a catalog's library adds a row to *this* table, so the library's
      // script refreshes it through here rather than reaching into this one's state.
      window.cpAdminReload = load;
      load();
    })();
    """
      .trimIndent()

  /** Drives the upload page: POST the picked/dropped/linked document, then show its permalink. */
  private fun docUploadScript(querySuffix: String): String =
    """
    (function () {
      var form = document.getElementById("cp-doc-form");
      var file = document.getElementById("cp-doc-file");
      var urlForm = document.getElementById("cp-doc-urlform");
      var out = document.getElementById("cp-doc-result");
      var suffix = ${jsString(querySuffix)};
      function show(html, isError) {
        out.hidden = false;
        out.className = "cp-doc-result" + (isError ? " cp-doc-error" : "");
        out.innerHTML = html;
      }
      function esc(s) { var d = document.createElement("span"); d.textContent = s; return d.innerHTML; }
      function post(url, body, label) {
        show("Uploading…", false);
        fetch(url, { method: "POST", body: body })
          .then(function (r) {
            return r.text().then(function (t) {
              if (!r.ok) throw new Error(t || ("upload failed (" + r.status + ")"));
              return JSON.parse(t);
            });
          })
          .then(function (doc) {
            // The API answers with the bare `/d/<id>` path. On a token-gated host that path 404s
            // without the token, so the browser-facing link carries this page's own query suffix
            // (empty in public mode, `?token=…` otherwise).
            var path = doc.url + suffix;
            var link = location.origin + path;
            show(
              "<p><strong>" + esc(label) + "</strong> — " + esc(doc.format) + ", link expires in " +
                esc(doc.expiresIn) + ".</p>" +
                "<p><a href=\"" + esc(path) + "\">" + esc(link) + "</a></p>" +
                "<button type=\"button\" class=\"cp-doc-btn\" id=\"cp-doc-copy\">Copy link</button>",
              false
            );
            var copy = document.getElementById("cp-doc-copy");
            if (copy) copy.addEventListener("click", function () {
              if (navigator.clipboard) navigator.clipboard.writeText(link);
              copy.textContent = "Copied";
            });
          })
          .catch(function (e) { show(esc(e.message || "upload failed"), true); });
      }
      function upload(f) {
        if (!f) return;
        var qs = suffix ? suffix + "&" : "?";
        post("/docs" + qs + "name=" + encodeURIComponent(f.name), f, f.name);
      }
      // The drop zone doubles as the file picker: clicking anywhere in it opens the chooser.
      form.addEventListener("click", function (e) { if (e.target !== file) file.click(); });
      form.addEventListener("submit", function (e) { e.preventDefault(); });
      file.addEventListener("change", function () { upload(file.files && file.files[0]); });
      ["dragenter", "dragover"].forEach(function (t) {
        form.addEventListener(t, function (e) { e.preventDefault(); form.classList.add("cp-drop-over"); });
      });
      ["dragleave", "drop"].forEach(function (t) {
        form.addEventListener(t, function (e) { e.preventDefault(); form.classList.remove("cp-drop-over"); });
      });
      form.addEventListener("drop", function (e) {
        if (e.dataTransfer && e.dataTransfer.files) upload(e.dataTransfer.files[0]);
      });
      if (urlForm) urlForm.addEventListener("submit", function (e) {
        e.preventDefault();
        var value = document.getElementById("cp-doc-url").value.trim();
        if (!value) return;
        var qs = suffix ? suffix + "&" : "?";
        post("/docs" + qs + "url=" + encodeURIComponent(value), null, value);
      });
    })();
    """
      .trimIndent()

  /**
   * `GET /d/<id>`: the expiring permalink page for one ingested document — played client-side by
   * its format's vendored player, with what the server read from it and how long the link has left.
   */
  fun docPage(
    doc: DocView,
    token: String,
    isPublic: Boolean,
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    /**
     * The CMP Wasm player page (`/rc-player-wasm/index.html`) when served (`--rc-player-wasm-dir`),
     * else null. Non-null adds that player to the `.rc` toggle.
     */
    cmpWasmPlayerPath: String? = null,
    /**
     * Server-side players for this document, each at `/d/<id>/render.png?rcPlayer=<id>`; each adds
     * a lane to the `.rc` toggle.
     */
    serverPlayers: List<DocServerPlayer> = emptyList(),
  ): String {
    val suffix = querySuffix(queryString(token, sessionId = null, isPublic = isPublic))
    val facts =
      doc.facts.joinToString("\n") { fact ->
        """
        <div class="cp-stat">
          <div class="cp-stat-key">${WebEscaping.htmlEscape(fact.key)}</div>
          <div class="cp-stat-val">${WebEscaping.htmlEscape(fact.value)}</div>
        </div>
        """
          .trimIndent()
      }
    val rawUrl = doc.rawPath + suffix
    val isRemoteComposeDoc = doc.formatId == ServeDocFormats.REMOTE_COMPOSE.id
    // Only the Remote Compose canvas needs the vendored faces; the Lottie page is unchanged.
    // The font preloader comes before the inline player script, which reads its global on start.
    val rcFontsScript =
      if (isRemoteComposeDoc) scriptTag("remote-compose.js") + "\n        " else ""
    // The player choice appears only for `.rc` documents on a host with a second player. Same shape
    // and ids as the viewer's `rcPlayer=` lanes.
    val lanes =
      if (!isRemoteComposeDoc) emptyList()
      else
        buildList {
          add(DocLane(DOC_PLAYER_JS, "TypeScript", DocLaneKind.CANVAS))
          if (cmpWasmPlayerPath != null) {
            add(DocLane(DOC_PLAYER_CMP_WASM, "CMP (Wasm)", DocLaneKind.FRAME, cmpWasmPlayerPath))
          }
          serverPlayers.forEach { add(DocLane(it.id, it.label, DocLaneKind.IMAGE, it.renderPath)) }
        }
    val playerToggle = lanes.size > 1
    val toggleHtml =
      if (!playerToggle) ""
      else
        "<p class=\"cp-doc-players\">\n" +
          "  <span class=\"cp-theme\" role=\"group\" aria-label=\"Player\">\n" +
          lanes.joinToString("") { lane ->
            "    <button type=\"button\" class=\"cp-theme-btn\" data-doc-player=\"${
              WebEscaping.htmlEscape(lane.id)
            }\" aria-pressed=\"${lane.id == DOC_PLAYER_JS}\">${WebEscaping.htmlEscape(lane.label)}</button>\n"
          } +
          "  </span>\n</p>\n"
    // Every lane past the TypeScript one: its element (loaded on first choice) and its own status.
    val extraLanes = lanes.drop(1)
    val laneElements =
      extraLanes.joinToString("") { lane -> "\n          " + docLaneElement(doc, lane) }
    val laneStatuses =
      extraLanes.joinToString("") { lane ->
        "\n        <p class=\"cp-doc-status\" id=\"cp-doc-status-${
          WebEscaping.htmlEscape(lane.id)
        }\" hidden></p>"
      }
    return document(
      title = "${doc.name} — compose-preview",
      unfurlDescription = "A shared ${doc.formatLabel} document, played back in your browser.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      body =
        """
        <h1 class="cp-head">${WebEscaping.htmlEscape(doc.name)}</h1>
        <p class="cp-sub">${WebEscaping.htmlEscape(doc.formatLabel)} · ${WebEscaping.htmlEscape(doc.sizeText)}
          <span class="cp-doc-expiry" title="${WebEscaping.htmlEscape(doc.expiresAtText)}">expires in ${WebEscaping.htmlEscape(doc.expiresInText)}</span></p>
        $toggleHtml<div class="cp-doc-stage" id="cp-doc-stage" data-format="${WebEscaping.htmlEscape(doc.formatId)}">
          ${docStageElement(doc)}$laneElements
        </div>
        <p class="cp-doc-status" id="cp-doc-status">Loading the ${WebEscaping.htmlEscape(doc.formatLabel)} player…</p>$laneStatuses
        <div class="cp-doc-facts">
        $facts
        </div>
        <p class="cp-sub" style="margin-top:18px">
          <a href="$rawUrl" download="${WebEscaping.htmlEscape(doc.name)}">Download the document</a> ·
          <a href="/docs$suffix">Share another</a>
        </p>
        $rcFontsScript<script>${docPlayerScript(doc, rawUrl)}</script>${
          if (playerToggle) "\n        <script>${docPlayerToggleScript(rawUrl, suffix)}</script>"
          else ""
        }
        """
          .trimIndent(),
      // Same lane as the viewer's `camaelon-js` lane, same reason: a shared `.rc` link must not
      // render in the recipient's own generics.
      rcFonts = isRemoteComposeDoc,
    )
  }

  /** The `rcPlayer=` ids the `.rc` toggle switches between, named as in the viewer. */
  private const val DOC_PLAYER_JS = "camaelon-js"
  private const val DOC_PLAYER_CMP_WASM = "cmp-wasm"

  /** How long the CMP frame may stay silent before the page says it didn't start — the viewer's. */
  private const val DOC_WASM_START_TIMEOUT_MS = 20_000

  /** A server-side player a `/d/<id>` page can offer: its `rcPlayer=` id, label and render path. */
  data class DocServerPlayer(val id: String, val label: String, val renderPath: String)

  private enum class DocLaneKind {
    CANVAS,
    FRAME,
    IMAGE,
  }

  private data class DocLane(
    val id: String,
    val label: String,
    val kind: DocLaneKind,
    /** The frame's player page, or the image's render path; null for the canvas lane. */
    val path: String? = null,
  )

  /**
   * A lane's element beside the canvas, loaded only when first chosen (the CMP Wasm bundle is tens
   * of MB; a server render costs a worker).
   */
  private fun docLaneElement(doc: DocView, lane: DocLane): String {
    val size = "width=\"${doc.width ?: 512}\" height=\"${doc.height ?: 512}\""
    val id = WebEscaping.htmlEscape(lane.id)
    val path = WebEscaping.htmlEscape(lane.path.orEmpty())
    return when (lane.kind) {
      DocLaneKind.FRAME ->
        "<iframe id=\"cp-doc-lane-$id\" data-doc-lane=\"$id\" data-doc-lane-kind=\"frame\"" +
          " data-doc-lane-path=\"$path\" title=\"${WebEscaping.htmlEscape(lane.label)} player\"" +
          " hidden $size style=\"border:0;background:transparent\"></iframe>"
      DocLaneKind.IMAGE ->
        "<img id=\"cp-doc-lane-$id\" data-doc-lane=\"$id\" data-doc-lane-kind=\"image\"" +
          " data-doc-lane-path=\"$path\" alt=\"${WebEscaping.htmlEscape(lane.label)} render\"" +
          " hidden $size>"
      DocLaneKind.CANVAS -> ""
    }
  }

  /**
   * The `.rc` permalink's player toggle. The TypeScript lane is the default; other lanes are
   * elements pointed at their source on first choice — the CMP Wasm page with `?src=` the
   * `/d/<id>/raw` bytes (see docs/design/RC_PLAYER_EMBED.md in rc-players) or
   * `/d/<id>/render.png?rcPlayer=…`. The choice rides the URL as `rcPlayer=`.
   *
   * Each lane has its own status line and only the selected one shows, since the TypeScript lane
   * always starts on load. A CMP frame that never reports times out like the viewer's cmp-wasm
   * lane.
   */
  private fun docPlayerToggleScript(rawUrl: String, querySuffix: String): String =
    """
    (function () {
      var canvas = document.getElementById("cp-doc-mount");
      var jsStatus = document.getElementById("cp-doc-status");
      var buttons = Array.prototype.slice.call(document.querySelectorAll("[data-doc-player]"));
      var raw = new URL(${jsString(rawUrl)}, location.href).href;
      var suffix = ${jsString(querySuffix)};
      var lanes = {};
      Array.prototype.slice.call(document.querySelectorAll("[data-doc-lane]")).forEach(function (el) {
        var id = el.getAttribute("data-doc-lane");
        lanes[id] = {
          el: el,
          kind: el.getAttribute("data-doc-lane-kind"),
          path: el.getAttribute("data-doc-lane-path"),
          status: document.getElementById("cp-doc-status-" + id),
          loaded: false,
          settled: false,
        };
      });
      function theme() {
        return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
      }
      function load(id, lane) {
        lane.loaded = true;
        if (lane.kind === "frame") {
          lane.status.textContent = "Loading the Compose Multiplatform player…";
          lane.el.src = lane.path + "?src=" + encodeURIComponent(raw) + "&theme=" + theme();
          setTimeout(function () {
            if (!lane.settled) lane.status.textContent = "The Compose Multiplatform player didn't start.";
          }, $DOC_WASM_START_TIMEOUT_MS);
        } else {
          lane.status.textContent = "Rendering on the server…";
          lane.el.onload = function () { lane.settled = true; lane.status.textContent = ""; };
          lane.el.onerror = function () {
            lane.settled = true;
            lane.status.textContent = "The server could not render this document with this player.";
          };
          lane.el.src = lane.path + (suffix ? suffix + "&" : "?") + "rcPlayer=" +
            encodeURIComponent(id) + "&uiMode=" + theme();
        }
      }
      function choose(player, remember) {
        buttons.forEach(function (b) {
          b.setAttribute("aria-pressed", String(b.getAttribute("data-doc-player") === player));
        });
        var js = !lanes[player];
        canvas.hidden = !js;
        jsStatus.hidden = !js;
        Object.keys(lanes).forEach(function (id) {
          var lane = lanes[id];
          lane.el.hidden = id !== player;
          lane.status.hidden = id !== player;
          if (id === player && !lane.loaded) load(id, lane);
        });
        if (remember) {
          var url = new URL(location.href);
          if (js) url.searchParams.delete("rcPlayer");
          else url.searchParams.set("rcPlayer", player);
          history.replaceState(null, "", url.pathname + url.search + url.hash);
        }
      }
      window.addEventListener("message", function (e) {
        var lane = lanes[${jsString(DOC_PLAYER_CMP_WASM)}];
        if (!lane || e.source !== lane.el.contentWindow || e.origin !== location.origin) return;
        if (e.data === "cp-rc-wasm-ready") {
          lane.settled = true;
          lane.status.textContent = "";
        } else if (typeof e.data === "string" && e.data.indexOf("cp-rc-wasm-error:") === 0) {
          lane.settled = true;
          lane.status.textContent = "The Compose Multiplatform player could not play this document: " +
            e.data.slice("cp-rc-wasm-error:".length);
        }
      });
      buttons.forEach(function (b) {
        b.addEventListener("click", function () { choose(b.getAttribute("data-doc-player"), true); });
      });
      var initial = new URL(location.href).searchParams.get("rcPlayer");
      if (initial && lanes[initial]) choose(initial, false);
    })();
    """
      .trimIndent()

  /** The element the format's player paints into — a canvas for RC, a container div for Lottie. */
  private fun docStageElement(doc: DocView): String =
    when (doc.formatId) {
      ServeDocFormats.LOTTIE.id -> "<div id=\"cp-doc-mount\"></div>"
      else ->
        "<canvas id=\"cp-doc-mount\" width=\"${doc.width ?: 512}\" height=\"${doc.height ?: 512}\"></canvas>"
    }

  /**
   * Load the format's player bundle (URL from the registry), fetch the document and mount it; only
   * the mount differs per format.
   */
  private fun docPlayerScript(doc: DocView, rawUrl: String): String {
    val mount =
      when (doc.formatId) {
        ServeDocFormats.LOTTIE.id ->
          """
          fetch(raw).then(function (r) { return r.json(); }).then(function (data) {
            window.lottie.loadAnimation({
              container: mount, renderer: "svg", loop: true, autoplay: true, animationData: data
            });
            done();
          }).catch(fail);
          """
            .trimIndent()
        else ->
          // The vendored faces must be loaded before the player paints: canvas falls back for an
          // unloaded face and never repaints (`cli/serve-web/src/rcFonts.ts`). `cpRcFonts` is
          // absent only if the bundle failed, and then the fallback face is used.
          """
          var fonts = window.cpRcFonts ? window.cpRcFonts.ready() : Promise.resolve();
          Promise.all([fonts, fetch(raw).then(function (r) { return r.arrayBuffer(); })]).then(function (r) {
            var buf = r[1];
            var player = new window.RC.RcdPlayer(mount);
            return Promise.resolve(player.loadFromArrayBuffer(buf)).then(function () {
              if (player.repaint) player.repaint();
              done();
            });
          }).catch(fail);
          """
            .trimIndent()
      }
    return """
      (function () {
        var raw = ${jsString(rawUrl)};
        var mount = document.getElementById("cp-doc-mount");
        var status = document.getElementById("cp-doc-status");
        function done() { status.textContent = ""; }
        function fail() { status.textContent = "This document could not be played back in your browser."; }
        var s = document.createElement("script");
        s.src = ${jsString(doc.playerPath)};
        s.onerror = function () { status.textContent = "The player failed to load."; };
        s.onload = function () {
      ${mount.prependIndent("      ")}
        };
        document.head.appendChild(s);
      })();
      """
      .trimIndent()
  }

  /** `3600` → `1h`; used for the upload page's TTL sentence and the permalink's expiry pill. */
  fun humanDuration(seconds: Long): String =
    when {
      // Days matter since a share can outlive one: the image lane's default link is a week, and
      // "168h" is a number a reader has to do arithmetic on to understand.
      seconds >= 86_400 ->
        "${seconds / 86_400}d" + ((seconds % 86_400) / 3600).let { if (it > 0) " ${it}h" else "" }
      seconds >= 3600 ->
        "${seconds / 3600}h" + ((seconds % 3600) / 60).let { if (it > 0) " ${it}m" else "" }
      seconds >= 60 -> "${seconds / 60}m"
      else -> "${seconds}s"
    }

  private fun humanBytes(bytes: Long): String =
    when {
      bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GiB"
      bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MiB"
      bytes >= 1024L -> "${bytes / 1024L} KiB"
      else -> "$bytes B"
    }

  /** A JS string literal for [value] — escaped via the JSON encoder, so quotes/slashes are safe. */
  private fun jsString(value: String): String =
    JsonPrimitive(value)
      .toString()
      // JSON quoting is not enough in an inline `<script>`: the parser ends the element at any
      // literal `</script>`. Escaping `<` is equivalent for `JSON.parse` and can never form a tag.
      .replace("<", "\\u003c")
      .replace(">", "\\u003e")

  /**
   * Encoder for data baked into a page as a JS string literal (read back via [jsString] +
   * `JSON.parse`). Compact and omits defaults, like the API's.
   */
  private val JSON_COMPACT = Json { encodeDefaults = true }

  /** One coloured part of a [Meter]. */
  data class MeterSegment(val label: String, val value: Long, val tone: String)

  /** Part-of-whole data rendered underneath a status stat's human-readable value. */
  data class Meter(val total: Long, val segments: List<MeterSegment>)

  /** A labelled figure on the [statusPage], optionally backed by a capacity/progress meter. */
  data class Stat(val key: String, val value: String, val meter: Meter? = null)

  /** One published catalog's row on the [statusPage] — its trust, size, liveness, provenance. */
  data class StatusCatalog(
    val id: String,
    val title: String,
    val listed: Boolean,
    /** [BundleVerifier.summary] verdict string, or null for a non-catalog session. */
    val trust: String?,
    val previews: Int,
    /** Published render failures included in [previews]. */
    val failedRenders: Int = 0,
    /** Preview ids included in [previews] that have no published pixels yet. */
    val deferredPreviews: Int = 0,
    /** The catalog has a live daemon lane (server-side re-render), even if idle right now. */
    val live: Boolean,
    /** A live daemon for this catalog is up **right now**. */
    val running: Boolean,
    /**
     * Why the catalog is snapshot-only, when it is (a [ServeDegradation] detail); null otherwise.
     */
    val degradation: String?,
    /** Delivery branch and build identity for a fetched catalog; null for a plain bundle. */
    val provenance: CatalogProvenance?,
    /** `pending`, `loaded`, `failed`, or `stale` (last good copy + latest refresh error). */
    val loadState: String = "loaded",
    /** Latest catalog load/refresh error. */
    val loadError: String? = null,
    /** Server-side idle theme-cache fill progress for this catalog generation. */
    val themeOptimization: ThemeOptimizationSnapshot? = null,
    /** Bounded rendered-preview cache occupancy for this catalog generation. */
    val renderCache: CatalogRenderCacheSnapshot? = null,
    /**
     * The row's facts are a last-known snapshot of an idle catalog (`/status` never resumes one),
     * shown as a "last known" qualifier by the trust badge.
     */
    val stale: Boolean = false,
  )

  /** One currently-running render daemon's row on the [statusPage]. */
  data class StatusServer(
    val id: String,
    val label: String,
    /** `desktop` / `android` (derived from the live-seat weight), or `static` for a baked host. */
    val backend: String,
    val activeStreams: Int,
    /** Human "up for" duration, or "—" when unknown. */
    val upForText: String,
  )

  /**
   * One live **agent access grant** on the [statusPage]; see
   * [docs/design/AGENT_ACCESS_GRANTS.md](../../../../../../../../docs/design/AGENT_ACCESS_GRANTS.md).
   * Carries a [fingerprint], never a token, so the page cannot leak credentials.
   */
  data class StatusAgentGrant(
    val id: String,
    val fingerprint: String,
    val scopes: String,
    /**
     * Pre-formatted capability list, or empty. A separate column because capabilities are not rungs
     * of [scopes].
     */
    val capabilities: String = "",
    val label: String,
    val approvedBy: String,
    val expiresInText: String,
    /** The seal the revoke form must carry; empty when this viewer may not revoke. */
    val revokeCsrf: String = "",
  )

  /** One access request still waiting for a human, on the [statusPage]. */
  data class StatusAgentRequest(
    val id: String,
    val userCode: String,
    val label: String,
    val client: String,
    val requestedScope: String,
    val expiresInText: String,
  )

  /** One recent daemon startup failure's row on the [statusPage]. */
  data class StatusFailure(val whenText: String, val session: String, val reason: String)

  /** One recent live render failure (distinct from a daemon failing to start). */
  data class StatusRenderFailure(
    val whenText: String,
    val session: String,
    val durationText: String,
    val reason: String,
  )

  /**
   * The pre-formatted model for [statusPage], so the page is a pure projection and the fixture
   * deterministic. [summary] are stat tiles; [config] the configuration grid; the lists are the
   * catalog, daemon and recent-failure tables.
   */
  data class StatusView(
    val version: String,
    val public: Boolean,
    /** Wall-clock instant used to turn recent catalog generation times into relative labels. */
    val nowMillis: Long,
    /** No catalog-load, daemon-startup, or recent live-render failures. */
    val overallOk: Boolean,
    /** Human explanation for a degraded badge, with an in-page diagnostic target. */
    val healthReason: String? = null,
    val healthHref: String? = null,
    val summary: List<Stat>,
    val config: List<Stat>,
    val catalogs: List<StatusCatalog>,
    val servers: List<StatusServer>,
    val failures: List<StatusFailure>,
    val renderFailures: List<StatusRenderFailure> = emptyList(),
    /** Live agent grants and pending requests. The section is omitted when empty. */
    val agentGrants: List<StatusAgentGrant> = emptyList(),
    val agentGrantRequests: List<StatusAgentRequest> = emptyList(),
    /**
     * Live grants hidden from this reader because they didn't approve them; shown as a count only,
     * since rows name logins.
     */
    val hiddenAgentGrants: Int = 0,
  )

  /**
   * The `/status` agent access section: live grants, pending requests, and a revoke button per row.
   * Omitted when the lane is off and nothing is live or pending.
   */
  private fun agentGrantSectionHtml(
    view: StatusView,
    suffix: String,
    esc: (String) -> String,
  ): String {
    // Empty string, not an empty section: see the call site in [statusPage].
    if (
      view.agentGrants.isEmpty() && view.agentGrantRequests.isEmpty() && view.hiddenAgentGrants == 0
    )
      return ""
    val hiddenNote =
      when (view.hiddenAgentGrants) {
        0 -> ""
        1 ->
          "<p class=\"cp-status-note\">1 more live grant is listed only for whoever approved it.</p>"
        else ->
          "<p class=\"cp-status-note\">${view.hiddenAgentGrants} more live grants are listed only " +
            "for whoever approved them.</p>"
      }
    val liveRows =
      if (view.agentGrants.isEmpty())
        "<tr><td colspan=\"7\" class=\"cp-empty\">" +
          (if (view.hiddenAgentGrants == 0) "No agent currently holds access."
          else "No grant you approved is live.") +
          "</td></tr>"
      else
        view.agentGrants.joinToString("\n") { grant ->
          val revoke =
            if (grant.revokeCsrf.isEmpty()) ""
            else
              "<form method=\"post\" action=\"/agent-access/${esc(grant.id)}/revoke$suffix\">" +
                "<input type=\"hidden\" name=\"csrf\" value=\"${esc(grant.revokeCsrf)}\">" +
                "<button class=\"cp-grant-revoke\" type=\"submit\">Revoke</button></form>"
          "<tr><td><code>${esc(grant.fingerprint)}</code></td>" +
            "<td>${esc(grant.scopes)}</td>" +
            "<td>${if (grant.capabilities.isBlank()) "—" else esc(grant.capabilities)}</td>" +
            "<td>${if (grant.label.isBlank()) "—" else esc(grant.label)}</td>" +
            "<td>${esc(grant.approvedBy)}</td>" +
            "<td>${esc(grant.expiresInText)}</td>" +
            "<td>$revoke</td></tr>"
        }
    val pending =
      if (view.agentGrantRequests.isEmpty()) ""
      else
        """
        <p class="cp-status-sec">Access requests waiting for you</p>
        <div class="cp-status-scroll"><table class="cp-grant-table">
          <thead><tr><th>Code</th><th>Purpose</th><th>From</th><th>Asking for</th><th>Expires in</th><th></th></tr></thead>
          <tbody>
          ${
            view.agentGrantRequests.joinToString("\n") { request ->
              "<tr><td><code>${esc(request.userCode)}</code></td>" +
                "<td>${if (request.label.isBlank()) "—" else esc(request.label)}</td>" +
                "<td>${esc(request.client)}</td>" +
                "<td>${esc(request.requestedScope)}</td>" +
                "<td>${esc(request.expiresInText)}</td>" +
                "<td><a href=\"/agent-access/${esc(request.id)}$suffix\">Review →</a></td></tr>"
            }
          }
          </tbody>
        </table></div>
        """
          .trimIndent()
    return "\n\n" +
      """
      <p class="cp-status-sec" id="agent-grants">Agent access</p>
      <div class="cp-status-scroll"><table class="cp-grant-table">
        <thead><tr><th>Grant</th><th>Scopes</th><th>Also</th><th>Purpose</th><th>Approved by</th><th>Expires in</th><th></th></tr></thead>
        <tbody>
        $liveRows
        </tbody>
      </table></div>
      $hiddenNote$pending
      """
        .trimIndent()
  }

  /**
   * A styled **server status** page (`GET /status`): published catalogs with trust/liveness,
   * running render daemons, effective configuration, and recent daemon startup failures. JSON at
   * `/status.json` (or `/status?format=json`).
   *
   * [token] is threaded like the landing pages: a gated server ([StatusView.public] false) keeps
   * `?token=` on gated links (`/status.json`, `/<system>/`); `--public` drops it. `/version` and
   * `/healthz` are always bare.
   */
  fun statusPage(
    view: StatusView,
    token: String,
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`), shown in the minimal footer. */
    version: String? = null,
    /**
     * The catalog whose colours and name this page wears on a **top-level site** ([ServeSites]), so
     * `/status` and 404s match the hostname. Empty on the main host.
     */
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    componentBrowser: Boolean = false,
    githubAuth: GitHubAuthStatus? = null,
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    // Gated-link suffix: token-gated ⇒ carry the token; public ⇒ nothing (routes are open).
    val suffix =
      if (view.public || token.isEmpty()) "" else "?token=" + WebEscaping.urlEncodeSegment(token)
    fun stat(s: Stat): String {
      val meter =
        s.meter?.let { meter ->
          val total = meter.total.coerceAtLeast(0)
          val segments =
            meter.segments.joinToString("") { segment ->
              val width =
                if (total == 0L) 0.0
                else segment.value.coerceIn(0, total).toDouble() * 100.0 / total
              "<span class=\"cp-meter-segment cp-meter-segment--${esc(segment.tone)}\" " +
                "style=\"width:${"%.3f".format(Locale.ROOT, width)}%\" " +
                "title=\"${esc(segment.label)}: ${segment.value}\"></span>"
            }
          "<div class=\"cp-meter\" role=\"img\" aria-label=\"${esc(s.value)}\">$segments</div>"
        } ?: ""
      return "<div class=\"cp-stat\"><div class=\"cp-stat-key\">${esc(s.key)}</div>" +
        "<div class=\"cp-stat-val\">${esc(s.value)}</div>$meter</div>"
    }

    fun inlineMeter(label: String, value: Long, total: Long, tone: String): String {
      val percent = if (total <= 0L) 0.0 else value.coerceIn(0, total).toDouble() * 100.0 / total
      return "<span class=\"cp-inline-meter\" role=\"img\" aria-label=\"${esc(label)}\">" +
        "<span class=\"cp-inline-meter-fill cp-inline-meter-fill--${esc(tone)}\" " +
        "style=\"width:${"%.3f".format(Locale.ROOT, percent)}%\"></span></span>"
    }

    val healthBadge =
      if (view.overallOk) " <span class=\"cp-badge cp-badge--trusted\">✓ healthy</span>"
      else {
        val reason = view.healthReason?.takeIf { it.isNotBlank() }
        val title = reason?.let { " title=\"${esc(it)}\"" } ?: ""
        val label = "⚠ degraded" + reason?.let { ": ${esc(it)}" }.orEmpty()
        view.healthHref
          ?.takeIf { it.isNotBlank() }
          ?.let { href ->
            " <a class=\"cp-badge cp-badge--unverified\" href=\"${esc(href)}\"$title>$label</a>"
          } ?: " <span class=\"cp-badge cp-badge--unverified\"$title>$label</span>"
      }

    val summaryGrid = view.summary.joinToString("\n") { stat(it) }
    val configGrid =
      view.config.joinToString("\n") {
        "<div class=\"cp-status-config-row\"><dt>${esc(it.key)}</dt><dd>${esc(it.value)}</dd></div>"
      }

    val catalogRows =
      if (view.catalogs.isEmpty())
        "<tr><td colspan=\"4\" class=\"cp-muted\">No catalogs configured on this server.</td></tr>"
      else
        view.catalogs.joinToString("\n") { c ->
          val idSeg = WebEscaping.urlEncodeSegment(c.id)
          val listed = if (c.listed) "" else " <span class=\"cp-muted\">(unlisted)</span>"
          val prov =
            c.provenance?.let { provenance ->
              val repo = esc(provenance.repo)
              val branch = esc(provenance.branch)
              val branchUrl = esc("https://github.com/${provenance.repo}/tree/${provenance.branch}")
              val generated =
                provenance.generatedAt
                  ?.takeIf { it.isNotBlank() }
                  ?.let { iso ->
                    val label = friendlyGeneratedAt(iso, view.nowMillis)
                    " · <span title=\"${esc(iso)}\">${esc(label)}</span>"
                  } ?: ""
              val versions =
                buildList {
                    provenance.toolVersion
                      ?.takeIf { it.isNotBlank() }
                      ?.let { add("compose-ai-tools <code>${esc(it)}</code>") }
                    provenance.designParityVersion
                      ?.takeIf { it.isNotBlank() }
                      ?.let { add("design-parity <code>${esc(it)}</code>") }
                  }
                  .takeIf { it.isNotEmpty() }
                  ?.joinToString(" · ")
                  ?.let { "<div class=\"cp-muted\">$it</div>" } ?: ""
              "<div class=\"cp-muted\"><a href=\"$branchUrl\">$repo@$branch</a>" +
                "$generated</div>$versions"
            } ?: ""
          val stateCell =
            when {
              c.loadState == "failed" ->
                "<span class=\"cp-badge cp-badge--unverified\">failed to load</span>"
              c.loadState == "pending" -> "<span class=\"cp-muted\">loading</span>"
              c.loadState == "stale" ->
                "<span class=\"cp-badge cp-badge--unverified\">stale copy</span>"
              c.running -> "<span class=\"cp-ok\">live · running</span>"
              c.live -> "live · idle"
              else -> "<span class=\"cp-muted\">baked PNG</span>"
            }
          val degrade = c.degradation?.let { "<div class=\"cp-muted\">${esc(it)}</div>" } ?: ""
          val loadError = c.loadError?.let { "<div class=\"cp-muted\">${esc(it)}</div>" } ?: ""
          val themeOptimization =
            c.themeOptimization?.let { optimization ->
              // Dirty renders are cached and served (so `fullyOptimized` is true) but were written
              // by a different build or marked by `regenerate`, and are still queued for
              // re-rendering; report them. Worded as "queued" since the store doesn't track
              // provenance and the queue may be paused.
              val queued =
                if (optimization.dirty > 0) " · ${optimization.dirty} awaiting re-render" else ""
              val failed = if (optimization.failed > 0) " · ${optimization.failed} failed" else ""
              val detail =
                if (optimization.converged) {
                  "themes optimized ${optimization.cached}/${optimization.total}"
                } else if (optimization.fullyOptimized) {
                  // `failed` matters most here: a fully-warm catalog whose dirty re-renders keep
                  // failing shows nothing else moving.
                  "themes optimized ${optimization.cached}/${optimization.total}$failed$queued"
                } else {
                  "theme optimization ${optimization.state} · " +
                    "${optimization.cached}/${optimization.total} cached" +
                    failed +
                    queued
                }
              "<div class=\"cp-muted\">${esc(detail)}</div>" +
                inlineMeter(
                  detail,
                  optimization.cached.toLong(),
                  optimization.total.toLong(),
                  // Queued renders are not a failure — they are serving — but they are not
                  // finished either, so the meter must not read the same as a converged catalog.
                  if (optimization.failed > 0) "warning"
                  else if (optimization.dirty > 0) "secondary" else "primary",
                )
            } ?: ""
          val renderCache =
            c.renderCache?.let { cache ->
              val detail =
                "preview cache ${cache.entries} entries · " +
                  "${humanBytes(cache.bytes)} / ${humanBytes(cache.maxBytes)}" +
                  if (cache.evictions > 0) " · ${cache.evictions} evicted" else ""
              "<div class=\"cp-muted\">${esc(detail)}</div>" +
                inlineMeter(detail, cache.bytes, cache.maxBytes, "secondary")
            } ?: ""
          // An idle catalog's facts are last-known, not live — say so next to the badge rather than
          // leaving the cell blank, which would read as untrusted.
          val staleNote = if (c.stale) "<div class=\"cp-muted\">last known</div>" else ""
          val trustCell =
            compactTrustBadge(c.trust).ifBlank { "<span class=\"cp-muted\">—</span>" } + staleNote
          val title =
            if (c.loadState == "failed" || c.loadState == "pending") esc(c.title)
            else "<a href=\"/$idSeg/$suffix\">${esc(c.title)}</a>"
          val previewCell =
            if (c.failedRenders > 0 || c.deferredPreviews > 0)
              "${c.previews} total<div class=\"cp-muted\">" +
                "${(c.previews - c.failedRenders - c.deferredPreviews).coerceAtLeast(0)} rendered · " +
                "${c.failedRenders} failed · ${c.deferredPreviews} deferred</div>"
            else "${c.previews}"
          "<tr>" +
            "<td>$title$listed" +
            "<div class=\"cp-muted\">${esc(c.id)}</div>$prov</td>" +
            "<td>$trustCell</td>" +
            "<td>$previewCell</td>" +
            "<td>$stateCell$themeOptimization$renderCache$loadError$degrade</td>" +
            "</tr>"
        }

    val serverRows =
      if (view.servers.isEmpty())
        "<tr><td colspan=\"4\" class=\"cp-muted\">No render daemons are running right now — they " +
          "start on demand and suspend when idle.</td></tr>"
      else
        view.servers.joinToString("\n") { s ->
          val id = if (s.id == s.label) "" else "<div class=\"cp-muted\">${esc(s.id)}</div>"
          "<tr>" +
            "<td>${esc(s.label)}$id</td>" +
            "<td><code>${esc(s.backend)}</code></td>" +
            "<td>${s.activeStreams}</td>" +
            "<td>${esc(s.upForText)}</td>" +
            "</tr>"
        }

    val failureSection =
      if (view.failures.isEmpty()) "<p class=\"cp-sub\">No recent daemon startup failures.</p>"
      else
        "<div class=\"cp-status-scroll\"><table class=\"cp-table\">" +
          "<thead><tr><th>When</th><th>Session</th><th>Reason</th></tr></thead><tbody>" +
          view.failures.joinToString("\n") { f ->
            "<tr><td>${esc(f.whenText)}</td><td>${esc(f.session)}</td>" +
              "<td>${esc(f.reason)}</td></tr>"
          } +
          "</tbody></table></div>"

    val renderFailureSection =
      if (view.renderFailures.isEmpty()) "<p class=\"cp-sub\">No recent render failures.</p>"
      else
        "<div class=\"cp-status-scroll\"><table class=\"cp-table\">" +
          "<thead><tr><th>When</th><th>Session</th><th>Duration</th><th>Reason</th></tr></thead><tbody>" +
          view.renderFailures.joinToString("\n") { f ->
            "<tr><td>${esc(f.whenText)}</td><td>${esc(f.session)}</td>" +
              "<td>${esc(f.durationText)}</td><td>${esc(f.reason)}</td></tr>"
          } +
          "</tbody></table></div>"

    val ver = " <span class=\"cp-about-ver\">v${esc(view.version)}</span>"
    val mode = if (view.public) "public (open)" else "token-gated"
    val agentGrantSection = agentGrantSectionHtml(view, suffix, ::esc)

    val body =
      """
      <h1 class="cp-head">Server status$healthBadge</h1>
      <p class="cp-sub">compose-preview serve · $mode$ver</p>
      <details class="cp-about cp-disclosure">
        <summary>
          <span class="cp-about-title">Status &amp; monitoring details</span>
          <span class="cp-disclosure-hint">JSON and health-check endpoints</span>
        </summary>
        <div class="cp-disclosure-body">
          <p class="cp-about-body">Catalog load results, render daemons, configuration and recent
            failures. The same data is available as JSON for monitors and Home Assistant.</p>
          <p class="cp-about-links">
            <a href="/status.json$suffix">/status.json</a> ·
            <a href="/version">/version</a> ·
            <a href="/healthz">/healthz</a>
          </p>
        </div>
      </details>

      <div class="cp-status-grid">
      $summaryGrid
      </div>

      <p class="cp-status-sec" id="catalogs">Catalogs</p>
      <div class="cp-status-scroll"><table class="cp-table">
        <thead><tr><th>Catalog</th><th>Trust</th><th>Previews</th><th>State</th></tr></thead>
        <tbody>
        $catalogRows
        </tbody>
      </table></div>

      <p class="cp-status-sec">Running servers</p>
      <div class="cp-status-scroll"><table class="cp-table">
        <thead><tr><th>Session</th><th>Backend</th><th>Streams</th><th>Up for</th></tr></thead>
        <tbody>
        $serverRows
        </tbody>
      </table></div>

      <p class="cp-status-sec">Configuration</p>
      <dl class="cp-status-config">
      $configGrid
      </dl>

      <p class="cp-status-sec" id="recent-daemon-failures">Recent daemon startup failures</p>
      $failureSection

      <p class="cp-status-sec" id="recent-render-failures">Recent live render failures</p>
      $renderFailureSection
      """
        .trimIndent() +
        // Appended rather than interpolated so a disabled lane leaves no blank line in the
        // committed fixture.
        agentGrantSection

    return document(
      // A site's status is that app's status, so its tab says so rather than naming the box.
      title =
        if (siteName.isBlank()) "Server status — compose-preview"
        else "Status — ${WebEscaping.htmlEscape(siteName)}",
      body = body,
      unfurlDescription =
        "Live catalog, render-daemon, and deployment status for this compose-preview server.",
      unfurl = unfurl,
      version = version,
      navSuffix = suffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
      headerSessionSettings = if (componentBrowser) "" else githubSessionSettings(githubAuth),
    )
  }

  /**
   * What [bugReportPage] draws: the assembled report plus what the page shows the reporter before
   * filing.
   *
   * [body] is [ServeBugReport]'s output for the served settings (works with JS off; the title is
   * typed by the reporter). [bodyTemplate] has [ServeBugReport.CLIENT_PLACEHOLDER] for the browser
   * block, filled by the page script. [renderUrl] is the token-stripped `/render` PNG of what the
   * reporter was viewing, shown as a thumbnail.
   */
  data class BugReport(
    val action: String,
    val body: String,
    val bodyTemplate: String,
    val repo: String,
    val renderUrl: String? = null,
    /**
     * The design reference [renderUrl] was compared against, if any
     * ([ServeBugReport.Page.referenceUrl]); shown because the body embeds it.
     */
    val referenceUrl: String? = null,
    /** Present only when the visitor has a GitHub session on this server. */
    val login: String? = null,
    /** The catalog the reported page belonged to, if any. See [BugReportCatalog]. */
    val catalog: BugReportCatalog? = null,
  )

  /**
   * The catalog the reporter was looking at, so [bugReportPage] can route a catalog bug to the
   * right tracker by name. Essential on a **top-level site** ([ServeSites]), which serves one
   * catalog and whose pages may have no preview to go back to.
   */
  data class BugReportCatalog(
    /** Served system id, e.g. `wear-m3`. */
    val system: String,
    /** The catalog's own display title, e.g. "Wear Material 3" — [system] when it declares none. */
    val title: String,
    /**
     * `owner/repo` the catalog's pixels belong to, from its source else its delivery provenance.
     */
    val repo: String,
    /** The new-issue form for [repo]. */
    val issuesUrl: String,
    /** True when the reporter was on this catalog's own hostname, so the whole site is it. */
    val site: Boolean,
  )

  /**
   * `GET /report-bug`: the server's own bug-report page.
   *
   * A page rather than a direct GitHub link because the report carries server state (JVM, failed
   * catalogs, lane activity) the reporter hasn't seen; the page shows the report before it is
   * filed, and the button files exactly what is on screen.
   *
   * When the reporter came from a viewer, the page and body include that preview's `/render` PNG
   * (embedded or linked per [ServeIssueReport.isEmbeddable]). Page-level bugs need a pasted
   * whole-window screenshot, which the page asks for.
   */
  fun bugReportPage(
    report: BugReport,
    /** The diagnostics as rows, shown verbatim; each entry is a section title to its rows. */
    sections: List<BugReportSection>,
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    siteName: String = "",
    themeCss: String = "",
    themeStorageKey: String = "",
    navSuffix: String = "",
    canUploadCaptures: Boolean = false,
    /**
     * The reported page is not anonymously viewable (UI-builder/admin pages, or any page on a
     * token-gated host), so captures upload only after the reporter ticks the opt-in: the image URL
     * is anonymous-read and the issue public.
     */
    privateCaptures: Boolean = false,
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val who =
      report.login?.takeIf { it.isNotBlank() }?.let { " as @${esc(it)}" }
        ?: " — GitHub will ask you to sign in"
    val sectionHtml =
      sections
        .filter { it.rows.isNotEmpty() }
        .joinToString("\n") { section ->
          val rows =
            section.rows.joinToString("\n") { (key, value) ->
              // A blank key marks a list row (spanning both columns), so a list section doesn't
              // render with an empty key column.
              if (key.isBlank()) "<tr><td colspan=\"2\">${esc(value)}</td></tr>"
              else "<tr><th scope=\"row\">${esc(key)}</th><td>${esc(value)}</td></tr>"
            }
          "<p class=\"cp-status-sec\">${esc(section.title)}</p>\n" +
            "<div class=\"cp-status-scroll\"><table class=\"cp-table cp-report-facts\">" +
            "<tbody>\n$rows\n</tbody></table></div>"
        }
    // Captures from the previous page arrive via `sessionStorage` and are rendered by
    // `report-capture.js` ([captureControlsHtml]). Server-rendered as an empty mount so the section
    // and its "nothing came across" wording have a fixed place.
    val scopeAttr = if (privateCaptures) " data-cp-capture-scope=\"private\"" else ""
    val captures =
      """
      <div class="cp-shots" data-cp-capture-src="${esc(assetHref("report-capture.js"))}"
        data-cp-image-upload="$canUploadCaptures"$scopeAttr>
        <p class="cp-sub cp-shots-empty">No captures came across from the page you reported. Take
          one there with the &ldquo;Report a problem&rdquo; button, or paste an ordinary screenshot
          straight into the issue.</p>
        <ul class="cp-shot-list"></ul>
        <p class="cp-shot-note" role="status"></p>
      </div>
      """
        .trimIndent()
    // Says up front whether this host can embed a capture. Public hosts don't admit visitors to the
    // image lane, so there the picture must be pasted into the issue.
    val screenshotProse =
      if (canUploadCaptures && privateCaptures)
        """
        <p class="cp-sub">The page you reported is only visible to signed-in users, so captures of
          it are <strong>not</strong> uploaded unless you tick the box beside them. An uploaded
          capture can be opened by anyone with its link, and the GitHub issue that links it is
          public. Unticked, pressing the button above puts the newest capture on the clipboard;
          paste it into the Screenshot section if you want it there. Use <strong>Mark up</strong>
          to add boxes, arrows, pen marks, or text first.</p>
        """
          .trimIndent()
          .replace("\n", "\n      ")
      else if (canUploadCaptures)
        """
        <p class="cp-sub">Captured images are uploaded to this preview server and embedded in the
          report automatically. Use <strong>Mark up</strong> to add boxes, arrows, pen marks, or text
          before opening the issue. The hosted link follows this server&rsquo;s image-retention window.
          If this server does not accept the upload, pressing the button above puts the newest
          capture on the clipboard instead; paste it into the Screenshot section.</p>
        """
          .trimIndent()
          .replace("\n", "\n      ")
      else
        """
        <p class="cp-sub">This server does not host captures, so a picture cannot be embedded in the
          report for you. Use <strong>Mark up</strong> to add boxes, arrows, pen marks, or text, then
          open the issue: that puts your newest capture on the clipboard, and the issue&rsquo;s
          <strong>Screenshot</strong> section is where to paste it. Nothing else carries it there.</p>
        """
          .trimIndent()
          .replace("\n", "\n      ")
    val render = report.renderUrl?.takeIf { it.isNotBlank() }
    val reference = report.referenceUrl?.takeIf { it.isNotBlank() }
    val shot =
      when {
        // A comparison's two outer panels, in page order; the prose then names the diff as the only
        // missing panel.
        render != null && reference != null ->
          "\n      <p class=\"cp-status-sec\">The pair you were comparing</p>\n" +
            "      <p class=\"cp-sub\">Both are included in the report, live, so they follow the " +
            "catalog. The diff between them is drawn in your browser and has no address of its " +
            "own — capture the page if that is the panel that is wrong.</p>\n" +
            "      <div class=\"cp-report-pair\">\n" +
            "        <img class=\"cp-report-shot\" src=\"${esc(reference)}\" alt=\"the design " +
            "reference this report is about\" loading=\"lazy\">\n" +
            "        <img class=\"cp-report-shot\" src=\"${esc(render)}\" alt=\"the render this " +
            "report is about\" loading=\"lazy\">\n" +
            "      </div>"
        render != null ->
          "\n      <p class=\"cp-status-sec\">The base render of that preview</p>\n" +
            "      <p class=\"cp-sub\">Included in the report. It is the plain render at your " +
            "settings — not the spec triptych, the wipe, or any other view the browser " +
            "composes — and it is live, so it follows the catalog. Capture the page as well " +
            "if the exact pixels matter.</p>\n" +
            "      <img class=\"cp-report-shot\" src=\"${esc(render)}\" alt=\"the render this " +
            "report is about\" loading=\"lazy\">"
        else -> ""
      }
    // Where a catalog bug belongs: named and linked when the catalog is known (always on a
    // top-level site), else generic advice. See [BugReportCatalog].
    val catalog = report.catalog
    val elsewhere =
      (when {
          catalog == null ->
            """
          <p class="cp-sub cp-report-elsewhere">Wrong <em>pixels</em> rather than a wrong page? A button
            in the wrong colour, a state that is missing, a spec that does not match — that is the
            <strong>catalog&rsquo;s</strong> bug, not this server&rsquo;s. Go back to the preview and use
            its &ldquo;report a catalog issue&rdquo; link, which files against the repository whose
            Kotlin declares that preview. A catalog bug filed here reaches people who cannot fix it.</p>
          """
          catalog.site ->
            """
          <p class="cp-sub cp-report-elsewhere">This site is the
            <strong>${esc(catalog.title)}</strong> catalog and nothing else, so most of what you can
            see here is drawn from it rather than by this server. Wrong <em>pixels</em> — a button
            in the wrong colour, a state that is missing, a spec that does not match — are that
            catalog&rsquo;s bug, and belong in
            <a href="${esc(catalog.issuesUrl)}" rel="noopener">${esc(catalog.repo)}</a>. Filed here
            they reach people who cannot fix them. A preview&rsquo;s own &ldquo;report a catalog
            issue&rdquo; link is better still where you have one: it carries the preview, your
            overrides and the render with it.</p>
          """
          else ->
            """
          <p class="cp-sub cp-report-elsewhere">The page you came from belongs to the
            <strong>${esc(catalog.title)}</strong> catalog. Wrong <em>pixels</em> — a button in the
            wrong colour, a state that is missing, a spec that does not match — are that
            catalog&rsquo;s bug, not this server&rsquo;s, and belong in
            <a href="${esc(catalog.issuesUrl)}" rel="noopener">${esc(catalog.repo)}</a>; a
            preview&rsquo;s own &ldquo;report a catalog issue&rdquo; link files there too and
            carries the preview, your overrides and the render with it. A catalog bug filed here
            reaches people who cannot fix it.</p>
          """
        })
        // Re-indented to the template's level: `trimIndent()` runs after substitution, so a
        // column-0 block would zero the common indent.
        .trimIndent()
        .replace("\n", "\n      ")
    val body =
      """
      <h1 class="cp-head">Report a bug in the preview server</h1>
      <p class="cp-sub">This files against <a href="https://github.com/${esc(report.repo)}"
        >${esc(report.repo)}</a>, the repository that ships <code>compose-preview serve</code>$who
        — the page you were on, its controls, and the render lanes behind them.</p>
      $elsewhere

      <form class="cp-report-bug-form" method="get" target="_blank" rel="noopener"
        action="${esc(report.action)}">
        <label class="cp-bug-summary">Summary
          <input class="cp-bug-summary-input" type="text" name="title" required
            autocomplete="off" placeholder="Briefly describe the problem">
        </label>
        <input type="hidden" name="labels" value="${esc(ServeBugReport.LABELS)}">
        <input type="hidden" name="body" id="cp-bug-body" value="${esc(report.body)}"
          data-report-template="${esc(report.bodyTemplate)}">
        <button type="submit" class="cp-doc-btn cp-bug-submit">$GITHUB_ICON
          Open a prefilled issue</button>
        <span class="cp-muted">Opens GitHub&rsquo;s new-issue form. Nothing is sent from this
          server &mdash; you write the description and press Submit there.</span>
      </form>

      <p class="cp-status-sec">Add a screenshot</p>
      $screenshotProse
      $captures
      $shot

      <p class="cp-status-sec">What gets sent</p>
      <p class="cp-sub">Everything below travels with the report, and nothing else. Session tokens
        are stripped from every link.</p>
      $sectionHtml

      <details class="cp-about cp-disclosure">
        <summary>
          <span class="cp-about-title">The report, as markdown</span>
          <span class="cp-disclosure-hint">exactly what lands in the issue body</span>
        </summary>
        <div class="cp-disclosure-body">
          <pre class="cp-report-preview" id="cp-bug-preview">${esc(report.body)}</pre>
        </div>
      </details>
      """
        .trimIndent()
    return document(
      title = "Report a bug — compose-preview",
      body = body,
      unfurlDescription = "Report a bug in this compose-preview server.",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      siteName = siteName,
      themeCss = themeCss,
      themeStorageKey = themeStorageKey,
      // The footer entry leads here; offering it on this page would be a button back to itself.
      bugReport = false,
    )
  }

  /** One titled group of `key → value` diagnostics on [bugReportPage]. */
  data class BugReportSection(val title: String, val rows: List<Pair<String, String>>)

  /** Generation times use one relative format; the exact ISO instant remains in the title. */
  private fun friendlyGeneratedAt(iso: String, nowMillis: Long): String {
    val generated = runCatching { Instant.parse(iso) }.getOrNull() ?: return prettyDate(iso)
    val ageMillis = nowMillis - generated.toEpochMilli()
    if (ageMillis < 0) {
      val futureSeconds = (-ageMillis + 999) / 1000
      return when {
        futureSeconds < 60 -> "in less than a minute"
        futureSeconds < 3_600 -> relativeTime(futureSeconds / 60, "minute", future = true)
        futureSeconds < 86_400 -> relativeTime(futureSeconds / 3_600, "hour", future = true)
        futureSeconds < 2_592_000 -> relativeTime(futureSeconds / 86_400, "day", future = true)
        futureSeconds < 31_536_000 ->
          relativeTime(futureSeconds / 2_592_000, "month", future = true)
        else -> relativeTime(futureSeconds / 31_536_000, "year", future = true)
      }
    }
    val ageSeconds = ageMillis / 1000
    return when {
      ageSeconds < 60 -> "just now"
      ageSeconds < 3_600 -> relativeTime(ageSeconds / 60, "minute")
      ageSeconds < 86_400 -> relativeTime(ageSeconds / 3_600, "hour")
      ageSeconds < 2_592_000 -> relativeTime(ageSeconds / 86_400, "day")
      ageSeconds < 31_536_000 -> relativeTime(ageSeconds / 2_592_000, "month")
      else -> relativeTime(ageSeconds / 31_536_000, "year")
    }
  }

  private fun relativeTime(amount: Long, unit: String, future: Boolean = false): String {
    val duration = "$amount $unit${if (amount == 1L) "" else "s"}"
    return if (future) "in $duration" else "$duration ago"
  }

  /**
   * Per-system **display policy**: the single answer to "does this system want a dark stage?",
   * keyed by the served system id (e.g. `wear-m3`, `confetti-wear`).
   *
   * Dark-first systems target dark-first platforms like Wear OS. The catalog's declared
   * `display.surface` is authoritative ([resolveDarkFirst]); only without one does [isDarkFirst]'s
   * Wear/watch id heuristic apply.
   */
  object SystemDisplay {
    /**
     * A Wear / watch system id, matched on a `-`/`_` token so `confetti-wear` and `wear-m3` hit.
     */
    private val wearIdPattern = Regex("(^|[-_])(wear|watch)([-_]|$)")

    /**
     * Whether [system] targets Wear OS from its id alone. Drives watch-shaped viewer bits
     * regardless of surface colour: watch device profiles in the size picker and no orientation
     * control.
     */
    fun isWearOs(system: String): Boolean {
      val s = system.trim('/').lowercase()
      if (s.isBlank()) return false
      return wearIdPattern.containsMatchIn(s)
    }

    /**
     * Fallback dark-first guess from the id when no `display.surface` is declared. Separate from
     * [isWearOs] so another dark-first platform could be added here.
     */
    fun isDarkFirst(system: String): Boolean = isWearOs(system)

    /**
     * Wear/watch renders have no day mode; discard a generic UI's accidental light override.
     * Applied to the raw parameter map before [ServeOverrides.parse], so every lane drops it at one
     * point and it never becomes a distinct cache key.
     */
    fun normalizeOverrideParams(
      system: String,
      overrides: Map<String, String>,
    ): Map<String, String> = if (isDarkFirst(system)) overrides - "uiMode" else overrides

    /**
     * Whether [system] draws on a dark stage: the catalog's declared [surface][declaredSurface]
     * (`"light"`/`"dark"`), else the [isDarkFirst] heuristic.
     */
    fun resolveDarkFirst(system: String, declaredSurface: String?): Boolean =
      when (declaredSurface?.trim()?.lowercase()) {
        "dark" -> true
        "light" -> false
        else -> isDarkFirst(system)
      }
  }

  private fun isScreenPreview(preview: ServePreview): Boolean {
    preview.section?.lowercase()?.let {
      return it == "screens" || it == "screen"
    }
    return listOf(preview.id, preview.label).any { value ->
      val lower = value.lowercase()
      "screen" in lower || "conference" in lower
    }
  }

  /**
   * Pick a representative preview from a catalog for the home index. A `Screens`-section preview
   * always beats a component (apps front a screen; component libraries fall through). Then scoring:
   * non-default states down, light over dark, a button/filled hero preferred. Ties break on id for
   * stable goldens. Null when empty.
   */
  fun representativePreviewId(previews: List<ServePreview>): String? {
    val usable = previews.filter { it.renderFailure == null }
    if (usable.isEmpty()) return null
    val demote =
      listOf(
        "disabled",
        "error",
        "pressed",
        "focused",
        "hover",
        "dragged",
        "unchecked",
        "indeterminate",
        "empty",
        "loading",
      )
    // A preview is a "screen" when its catalog section says so (the reliable signal), else when its
    // id/label reads like one — so a screen wins the hero even before section metadata exists.
    val anyScreen = usable.any { isScreenPreview(it) }
    // A screen id that reads like the app's primary view (conference/home/schedule/…), preferred
    // among screens.
    val primaryScreen =
      listOf("conference", "home", "main", "schedule", "sessions", "overview", "start", "today")
    fun score(p: ServePreview): Int {
      val lower = p.id.lowercase()
      var s = 0
      // Prefer a real screen when the catalog has any; a screenless component library is unaffected
      // (every preview gets the same penalty, so the component heuristic below still decides).
      if (anyScreen && !isScreenPreview(p)) s += 100
      if (isScreenPreview(p) && primaryScreen.any { it in lower }) s -= 1
      // A non-default component state (unchecked / pressed / …) is never the hero — trust the
      // catalog's `state` metadata, falling back to the id-substring demote list below.
      if (p.state != null && p.state != "default") s += 8
      if ("dark" in lower) s += 4
      demote.forEach { if (it in lower) s += 8 }
      if ("button" in lower) s -= 3
      if ("filled" in lower) s -= 2
      return s
    }
    return usable.sortedWith(compareBy({ score(it) }, { it.id })).first().id
  }

  /**
   * How long a catalog card must be held to start a live session instead of opening the preview —
   * about Android's own long-press timeout.
   */
  const val LONG_PRESS_HOLD_MS: Int = 500

  /**
   * The grid's **long-press live lane**: hold a card to stream its preview from the session's
   * daemon in place.
   *
   * Emits the configuration and the `<cp-catalog-live>` element. Preview ids are a server-emitted
   * object literal in grid order, not read from `data-` attributes, so no socket URL originates as
   * DOM text. Each entry carries light and dark ids (identical for single-variant cards, empty when
   * unstreamable), so a swapped card streams what's on screen.
   *
   * The tag follows the config (the element also defers to `DOMContentLoaded`). Empty when no card
   * can stream.
   */
  private fun catalogLiveScript(
    basePath: String,
    query: String,
    cards: List<Pair<String, String>>,
    signInHref: String?,
  ): String {
    if (cards.none { (light, dark) -> light.isNotEmpty() || dark.isNotEmpty() }) return ""
    val entries =
      cards.joinToString(",") { (light, dark) ->
        "{l:${WebEscaping.jsString(light)},d:${WebEscaping.jsString(dark)}}"
      }
    val config =
      "window.cpCatalogLive = {base:${WebEscaping.jsString(basePath)}," +
        "query:${WebEscaping.jsString(query)}," +
        "signInHref:${WebEscaping.jsString(signInHref.orEmpty())}," +
        "holdMs:$LONG_PRESS_HOLD_MS,cards:[$entries]};"
    return "\n<script>$config</script>\n<cp-catalog-live></cp-catalog-live>"
  }

  /** Landing page: the module's preview list, each card linking to its viewer. */
  fun landingPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    trust: String? = null,
    isPublic: Boolean = false,
    /**
     * Whether this server has a front-door index (`/`) to link back to — true when it serves any
     * listed or unlisted catalog. Gates the "← All design systems" button.
     */
    hasHomeIndex: Boolean = false,
    /**
     * URL prefix for this session's links (`/<system>`, or empty when root-mounted). Prefixed links
     * drop `&session=`, since the path carries it.
     */
    basePath: String = "",
    /**
     * Whether this session can compare against its SVG export (gates "compare SVG", `svg` format).
     */
    hasSvgComparison: Boolean = false,
    /**
     * Whether this session can compare against Remote Compose output (gates "compare RC players",
     * `rc` format).
     */
    hasRcComparison: Boolean = false,
    /**
     * Whether this session can compare against its design references (gates "compare to Figma",
     * `reference` format), labelled via [designToolLabel].
     */
    hasReferenceComparison: Boolean = false,
    /**
     * Whether this catalog has a parity index to link to: at least one preview mapped to a
     * reference, or a `parity/activity.json` feed. False omits the link.
     */
    hasParityView: Boolean = false,
    /**
     * The paired implementation's name (e.g. "M3 Wear OS Apps Design Kit") when the catalog
     * declares a `compareWith` pairing — the `parallel` baseline. Null or blank omits the chip. See
     * `docs/design/COMPARE_NAVIGATION.md`, §1.
     */
    parallelComparisonLabel: String? = null,
    /**
     * Motion captures this catalog publishes across all previews; zero omits the "motion" action. A
     * count because one and thirty are different offers.
     */
    motionCaptureCount: Int = 0,
    /**
     * The design pages this catalog publishes ([ServeDesignPages]), in publication order, listed in
     * the navigation tree ([pagesBranchHtml]) or, without a tree, as a header chip. Empty offers
     * neither.
     */
    designPages: List<PageLink> = emptyList(),
    /**
     * The design tool this catalog is specified by ("Figma", …), from its references' provider or
     * parity feed, so the action reads "compare to Figma". Null keeps the neutral label. See
     * [designToolLabel].
     */
    designToolLabel: String? = null,
    /**
     * Per-preview thumbnail content-crop lookup, framing a card's render to its component box. Null
     * shows the raw render. The default `{ null }` keeps every card uncropped (plain-module
     * landing, tests).
     */
    thumbCrop: (String) -> ContentCrop? = { null },
    /**
     * Per-preview prebaked thumbnail lookup ([ServeHeroImages.gridThumbFor]) returning the baked
     * bytes' content hash. When present, every URL for that card's pixels carries `?thumb=<hash>`,
     * which the render lane answers from memory with a downscaled image. Null keeps the plain
     * render URL until a later page build.
     *
     * The default `{ null }` keeps full renders (plain-module landing, fixture goldens).
     */
    thumbHash: (String) -> String? = { null },
    /**
     * Presence `POST` URL keeping this catalog's session and daemon alive ([presenceScript]). Empty
     * omits the heartbeat.
     */
    presenceUrl: String = "",
    /**
     * Running server version (`SERVE_VERSION`) for the footer. Null omits it; the fixture passes a
     * fixed string.
     */
    version: String? = null,
    /**
     * Provenance of a served design-system catalog; renders a provenance strip with a regenerate
     * link. Null for a plain bundle or non-catalog module.
     */
    provenance: CatalogProvenance? = null,
    /** POST URL that checks this catalog's delivery branch immediately. Null omits Refresh. */
    refreshUrl: String? = null,
    /**
     * `/playground?catalog=<system>`, opening the playground with this design system preselected.
     * Null when the host has no playground lane.
     */
    playgroundHref: String? = null,
    /**
     * The catalog's declared stage surface (`display.surface`: `"light"`/`"dark"`), deciding
     * whether unthemed cards sit on the dark stage. Null falls back to [isDarkFirstSystem].
     */
    declaredSurface: String? = null,
    /**
     * The catalog's palette as an inline `:root` override, built by [ServeThemeCss] from
     * `tokens.dtcg.json`. Empty keeps the built-in chrome.
     */
    themeCss: String = "",
    /**
     * Why this catalog is snapshot-only, if it is; non-empty shows a banner ([ServeDegradation] /
     * [degradeBanner]).
     */
    degradations: List<ServeDegradation> = emptyList(),
    /**
     * The app's declared `@ThemeCatalog` / `@WearThemeCatalog` themes ([ServeHost.declaredThemes]),
     * joining the baked light/dark pair on the Theme control. Offered only for cards that can
     * re-render ([canRenderThemeFor]) by re-running their composable ([irReplayFor]).
     */
    declaredThemes: List<ServeTheme> = emptyList(),
    /**
     * Whether a preview can re-render under a `themeProvider` override, i.e. has a daemon twin
     * ([ServeHost.canRenderOverridesFor]). Others keep baked pixels; declared-theme chips appear
     * only if some card can. Defaults to `{ false }`.
     */
    canRenderThemeFor: (String) -> Boolean = { false },
    /**
     * Whether a server render of a preview replays a captured document rather than re-running the
     * composable (the same question as `ServeHttpServer.isReplayedPreview`).
     *
     * A declared theme wraps a composition, so a replayed preview can never honour one and its
     * render is refused 409 ([CatalogLiveRouting.irReplayDroppedOverrideNames]). Such cards are not
     * theme-overridable. Defaults to `{ false }`.
     */
    irReplayFor: (String) -> Boolean = { false },
    /**
     * Maximum themed-thumbnail burst for this host. Above one enables the server-issued page lease
     * endpoint; actual concurrency is granted dynamically and clamped by render capacity.
     * Monolithic daemons stay serial.
     */
    themeRenderBurstCapacity: Int = 1,
    /** Per-preview engagement counts; missing or zero entries render no badge. */
    engagement: Map<String, PreviewEngagement> = emptyMap(),
    /**
     * Whether a preview can stream live from the grid: the session has a daemon stream
     * ([ServeHost.hasLiveStream]) and the preview has a daemon twin
     * ([ServeHost.canRenderOverridesFor]). Passing cards get the long-press lane
     * ([catalogLiveScript]). Defaults to `{ false }`.
     */
    canStreamLiveFor: (String) -> Boolean = { false },
    /**
     * GitHub sign-in URL when live lanes are auth-gated and this visitor isn't signed in. Non-null
     * keeps the long-press affordance but answers it with the reason instead of a socket that would
     * close 1008.
     */
    liveSignInHref: String? = null,
    /** Aggregate visits to this app/design-system landing page. */
    systemViews: Long = 0,
    /** Absolute page + representative preview URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    /** Human catalog title from catalog.json; [moduleLabel] remains the stable technical id. */
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    /** Validated catalog-published issues, matched onto each component card. */
    parityIssues: List<ParityIssue> = emptyList(),
    componentBrowser: Boolean = false,
    /**
     * GitHub session state, rendered as the header's sign-in control. Needed on a top-level site
     * ([ServeSites]), where the landing is the whole front door. Null, and Catalog mode (which
     * drops the live lane), render nothing.
     */
    githubAuth: GitHubAuthStatus? = null,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * The page-scoped "report a catalog issue" for this surface, built via [ServeIssueReport];
     * names the page rather than a preview ([pageReportRowHtml]). Null omits it.
     */
    reportIssue: ReportIssue? = null,
  ): String {
    @Suppress("NAME_SHADOWING") val designPages = if (componentBrowser) emptyList() else designPages
    @Suppress("NAME_SHADOWING")
    val parityIssues = if (componentBrowser) emptyList() else parityIssues
    @Suppress("NAME_SHADOWING") val hasSvgComparison = hasSvgComparison && !componentBrowser
    @Suppress("NAME_SHADOWING") val hasRcComparison = hasRcComparison && !componentBrowser
    @Suppress("NAME_SHADOWING")
    val hasReferenceComparison = hasReferenceComparison && !componentBrowser
    val hasParallelComparison = !parallelComparisonLabel.isNullOrBlank() && !componentBrowser
    @Suppress("NAME_SHADOWING") val hasParityView = hasParityView && !componentBrowser
    // Suppressed in Catalog mode with the other destinations: every other `⋯` menu entry is
    // stripped there, so keeping this one would leave a single-item menu. Revisit if the collection
    // view proves wanted in that mode.
    @Suppress("NAME_SHADOWING")
    val motionCaptureCount = if (componentBrowser) 0 else motionCaptureCount
    @Suppress("NAME_SHADOWING") val playgroundHref = playgroundHref?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val degradations = if (componentBrowser) emptyList() else degradations
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    // The Dev-mode `uses:` endpoint (`ServeHttpServer.handleUsesSearch`). Empty in Catalog mode,
    // which removes the operator entirely; the route is gated the same way.
    val usesUrl = if (componentBrowser) "" else "$basePath/api/uses$q"
    val usesFilter = usesUrl.isNotEmpty()
    val themeLeaseUrl =
      if (themeRenderBurstCapacity > 1) "$basePath/api/theme-render-lease$q" else ""
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)
    val catalogId =
      if (componentBrowser || heading == moduleLabel) ""
      else "<p class=\"cp-catalog-id\">${WebEscaping.htmlEscape(moduleLabel)}</p>"
    // Dark-first systems put unthemed cards on the dark stage; explicit variants keep their token.
    // Background only — the filter axis uses explicit-only [cardTheme].
    val darkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    // Collapse per-theme variants into one card each so the Light/Dark control swaps in place.
    // Fold non-default states, props variants and non-primary breakpoints out first, so each
    // component shows one card; the viewer's switchers reach the rest. Plain bundle screens pass
    // through.
    val primarySizes = primarySizeByComponent(previews, darkFirst)
    val groups =
      groupPreviews(
        previews.filterNot {
          (componentBrowser && it.renderFailure != null) ||
            (it.renderFailure == null &&
              (isNonDefaultState(it) ||
                hasNonDefaultProps(it) ||
                isNonPrimarySize(it, primarySizes, darkFirst)))
        }
      )
    val cardAnchors = mintCardAnchors(groups)
    val renderFailureSummary =
      if (componentBrowser) ""
      else
        previews
          .mapNotNull { it.renderFailure }
          .groupBy { it.errorClass to it.message }
          .takeIf { it.isNotEmpty() }
          ?.let { failures ->
            buildString {
              val total = failures.values.sumOf { it.size }
              append("<aside class=\"cp-render-failure-summary\"><strong>$total failed render")
              if (total != 1) append("s")
              append("</strong><ul>")
              failures.forEach { (signature, occurrences) ->
                append("<li><span>")
                append(WebEscaping.htmlEscape(signature.first.substringAfterLast('.')))
                if (signature.second.isNotBlank()) {
                  append(": ")
                  append(WebEscaping.htmlEscape(signature.second))
                }
                append("</span><strong>×${occurrences.size}</strong></li>")
              }
              append("</ul></aside>\n")
            }
          } ?: ""
    // Fallback for catalogs whose sizes live only in the id (not folded above): qualify a label
    // only when it actually collides.
    val duplicateGridLabels =
      groups.groupingBy { previewDisplayName(it.default) }.eachCount().filterValues { it > 1 }.keys
    fun gridDisplayName(preview: ServePreview): String {
      val label = previewDisplayName(preview)
      if (label !in duplicateGridLabels) return label
      val size = sizeLabel(preview) ?: return label
      return "$label · $size"
    }
    // A card's pixel URL. With a prebaked thumbnail it carries `?thumb=<hash>`; the URL is
    // otherwise identical, so anything layered on it (`themeProvider=`, overrides) still gets a
    // full render. One helper feeds `src`, swap targets and the themed-render base.
    fun renderSrc(p: ServePreview): String {
      val base = "$basePath/render/${WebEscaping.urlEncodeSegment(p.id)}.png$q"
      val hash = thumbHash(p.id) ?: return base
      val sep = if (base.contains('?')) "&" else "?"
      return "$base$sep${ServeHeroImages.THUMB_PARAM}=${WebEscaping.urlEncodeSegment(hash)}"
    }
    fun viewerHref(p: ServePreview) = "$basePath/p/${WebEscaping.urlEncodeSegment(p.id)}$q"
    // The app-declared themes join the header's Theme control only when this session can actually
    // re-render a card under one — otherwise the chips would redraw nothing.
    fun themeRenderable(p: ServePreview) = canRenderThemeFor(p.id)
    // Whether a declared theme redraws this preview: it needs a daemon twin, must not be a theme
    // specimen ([isThemeSpecimen]), and must re-run its composable rather than replay
    // ([irReplayFor]; replays are refused 409).
    // One predicate feeds both the chip gate and the per-card URL, so the chips never appear when
    // no card would respond.
    fun themeOverridable(p: ServePreview) =
      themeRenderable(p) && !isThemeSpecimen(p) && !irReplayFor(p.id)
    // The variant a card shows by default (server-side) — the one a declared theme re-renders.
    fun renderedVariant(card: GridCard) =
      if (card.swappable && darkFirst) card.dark!! else card.default
    val declaredThemeChips =
      if (declaredThemes.isEmpty()) emptyList()
      else if (groups.any { themeOverridable(renderedVariant(it)) }) declaredThemes else emptyList()
    // A card's themed-render base URL — "" when a declared theme wouldn't redraw it, so it keeps
    // its baked pixels.
    fun themeBase(card: GridCard) =
      renderedVariant(card).let { if (themeOverridable(it)) renderSrc(it) else "" }
    fun cardViews(card: GridCard): Long =
      listOfNotNull(card.light, card.dark, card.neutral).sumOf { engagement[it.id]?.views ?: 0L }
    fun swapCard(card: GridCard, anchor: String): String {
      val l = card.light!!
      val d = card.dark!!
      // Default to the light render (dark-first systems open dark); the JS re-swaps to the sticky
      // choice. Each theme's src/href/id/label ride as data-* so the browser builds no URLs.
      val def = if (darkFirst) d else l
      val defTheme = if (darkFirst) "dark" else "light"
      val lightLabel = gridDisplayName(l)
      val darkLabel = gridDisplayName(d)
      val defaultLabel = gridDisplayName(def)
      val issueBadge =
        parityIssueBadgeHtml(
          listOfNotNull(card.light, card.dark, card.neutral)
            .flatMap { issuesForPreview(parityIssues, it) }
            .distinctBy { it.repository to it.number }
        )
      // `data-def` is the variant a DECLARED theme re-renders (the server-side default), so picking
      // one doesn't also flip the card's light/dark base.
      return """
        <a class="cp-card"$anchor aria-label="${WebEscaping.htmlEscape(defaultLabel)}" data-swap="1" data-bg-theme="$defTheme" data-def="${if (darkFirst) "d" else "l"}"
          data-l-src="${renderSrc(l)}" data-l-href="${viewerHref(l)}"
          data-l-id="${WebEscaping.htmlEscape(l.id)}" data-l-label="${WebEscaping.htmlEscape(lightLabel)}"
          data-d-src="${renderSrc(d)}" data-d-href="${viewerHref(d)}"
          data-d-id="${WebEscaping.htmlEscape(d.id)}" data-d-label="${WebEscaping.htmlEscape(darkLabel)}"
          href="${viewerHref(def)}">
          <div class="cp-imgwrap">
            <img loading="lazy" alt="${WebEscaping.htmlEscape(defaultLabel)}" src="${renderSrc(def)}">
          </div>
          <div class="cp-meta">
            <div class="cp-label" title="${WebEscaping.htmlEscape(def.id)}">${WebEscaping.htmlEscape(defaultLabel)}</div>
            ${if (componentBrowser) "" else cardIdHtml(def.id)}$issueBadge
            ${if (componentBrowser) "" else viewCountHtml(cardViews(card))}
          </div>
        </a>
        """
        .trimIndent()
    }
    fun singleCard(p: ServePreview, anchor: String): String {
      val idSeg = WebEscaping.urlEncodeSegment(p.id)
      val label = WebEscaping.htmlEscape(gridDisplayName(p))
      val src = renderSrc(p)
      val idText = WebEscaping.htmlEscape(p.id)
      val issueBadge = parityIssueBadgeHtml(issuesForPreview(parityIssues, p))
      p.renderFailure?.let { failure ->
        val errorName = failure.errorClass.substringAfterLast('.').ifBlank { "RenderError" }
        val message = failure.message.takeIf { it.isNotBlank() } ?: "The preview did not render."
        val frame =
          failure.topAppFrame?.let {
            "<div class=\"cp-render-failure-frame\">at ${WebEscaping.htmlEscape(it.file)}:" +
              "${it.line} · ${WebEscaping.htmlEscape(it.function)}</div>"
          } ?: ""
        val stack =
          failure.stackTrace
            ?.takeIf { it.isNotBlank() }
            ?.let {
              "<details class=\"cp-render-stack\"><summary>Stack trace</summary><pre>" +
                WebEscaping.htmlEscape(it) +
                "</pre></details>"
            } ?: ""
        return """
          <details class="cp-card cp-card--render-failed"$anchor>
            <summary>
              <div class="cp-imgwrap cp-render-failure">
                <span class="cp-render-failure-mark">!</span>
                <strong>${WebEscaping.htmlEscape(errorName)}</strong>
                <span>${WebEscaping.htmlEscape(message)}</span>
              </div>
              <div class="cp-meta">
                <div class="cp-label" title="$idText">$label</div>
                <div class="cp-id">render failed · ${WebEscaping.htmlEscape(failure.phase)}</div>
              </div>
            </summary>
            <div class="cp-render-failure-detail">
              <strong>${WebEscaping.htmlEscape(failure.errorClass)}</strong>
              <p>${WebEscaping.htmlEscape(message)}</p>
              $frame
              $stack
            </div>
          </details>
          """
          .trimIndent()
      }
      // data-bg-theme: the preview's declared ground first, then the explicit id token, then the
      // dark-first default — matching the reference page.
      val bgAttr =
        (declaredBackdropTheme(p) ?: bgTheme(p.id, darkFirst))?.let { " data-bg-theme=\"$it\"" }
          ?: ""
      return """
          <a class="cp-card"$anchor$bgAttr href="$basePath/p/$idSeg$q" aria-label="$label">
            <div class="cp-imgwrap">
              ${thumbImg(src, label, " loading=\"lazy\"", thumbCrop(p.id))}
            </div>
            <div class="cp-meta">
              <div class="cp-label" title="$idText">$label</div>
              ${if (componentBrowser) "" else cardIdHtml(p.id)}$issueBadge
              ${if (componentBrowser) "" else viewCountHtml(engagement[p.id]?.views ?: 0L)}
            </div>
          </a>
          """
        .trimIndent()
    }
    // Every card carries its tree row's anchor, derived from the default render's id rather than
    // position.
    fun cardHtml(card: GridCard): String {
      // The `uses:` filter matches the card's default preview id; all its variants share one
      // declaration.
      val usesId =
        if (usesFilter) " data-uses-id=\"${WebEscaping.htmlEscape(card.default.id)}\"" else ""
      val anchor = " id=\"${cardAnchors.getValue(card.default.id)}\"" + usesId
      return if (card.swappable) swapCard(card, anchor) else singleCard(card.default, anchor)
    }
    val cards =
      if (groups.isEmpty()) {
        "<p class=\"cp-sub\">No previews discovered in this module.</p>"
      } else {
        groups.joinToString("\n") { cardHtml(it) }
      }
    // A sectioned catalog renders as tabs over per-section panels with `group` sub-headings. A
    // section-less catalog keeps one flat grid, with synthesized family dividers
    // ([synthesizeGroups]) when that helps.
    val sections = buildSections(groups)
    val hasTabs = sections.isNotEmpty()
    // The tree's All row, and the landing selection ([catalogTreeHtml]). One section is already
    // the whole catalog, so it gets no row and its script never mentions `all`.
    val hasAllTab = sections.size > 1
    val synthGroups = if (hasTabs) null else synthesizeGroups(groups)
    // Any `.cp-subgroup` dividers present (authored tabs OR synthesized flat groups) → the filter
    // script must collapse an emptied sub-group on search, independent of the tab machinery.
    val hasGroups = hasTabs || synthGroups != null
    // Slugs for the synthesized families, so an outline row has an anchor to jump to. Assigned
    // here rather than in [synthesizeGroups] because only the tree needs them.
    if (synthGroups != null) {
      val used = HashSet<String>()
      synthGroups.forEach { g ->
        var slug = g.name?.let { sectionSlug(it) } ?: "ungrouped"
        var n = 2
        val base = slug
        while (!used.add(slug)) {
          slug = "$base-$n"
          n++
        }
        g.slug = slug
      }
    }
    // The component row for a card, built from the render the grid actually paints (dark on a
    // dark-first system).
    fun treeComponent(card: GridCard): TreeComponent {
      val shown = card.rendered(darkFirst)
      return TreeComponent(
        label = gridDisplayName(shown),
        anchorId = cardAnchors.getValue(card.default.id),
        variants = primaryVariants(shown, previews, darkFirst) { viewerHref(it) },
        href = viewerHref(shown),
        // The SAME url the card beside it uses, prebaked thumbnail lane and all, so the row costs
        // one cache hit rather than a second decode of the same pixels.
        thumbSrc = renderSrc(shown),
      )
    }
    // The design file's pages go at the foot of the tree; a catalog with no tree keeps the header
    // chip instead.
    val hasTree = hasTabs || synthGroups != null
    // Pages become a pane beside Components only when the catalog has both; otherwise the tree is
    // emitted unchanged.
    val hasPanes = hasTree && designPages.isNotEmpty()
    val pagesBranch = if (hasTree && !hasPanes) pagesBranchHtml(designPages, basePath, q) else ""
    val tabBar =
      when {
        hasTabs -> catalogTreeHtml(sections, ::treeComponent, pagesBranch)
        synthGroups != null -> catalogOutlineTreeHtml(synthGroups, ::treeComponent, pagesBranch)
        else -> ""
      }
    // The grid body: tabbed section panels (id=cp-grid, targeted by the search box's aria-controls
    // and the filter script) or the plain flat grid. The flat form keeps the original template
    // whitespace so section-less goldens are unchanged.
    val gridBlock =
      if (!hasTabs && synthGroups != null) {
        // Section-less catalog with synthesized family dividers: a flat grid of labelled
        // sub-groups (no tab bar). `#cp-grid` still wraps it for the search box's aria-controls.
        buildString {
          append("<div class=\"cp-grid-groups\" id=\"cp-grid\">\n")
          synthGroups.forEach { g ->
            // `--cp-n` is the card count, so a small family claims only the width its cards need
            // (see `.cp-subgroup` in serve.css). Only the server knows the count.
            append("<div class=\"cp-subgroup\" id=\"${flatGroupAnchorId(g.slug)}\"")
            append(" style=\"--cp-n:${g.cards.size}\">\n")
            if (g.name != null)
              append("<h2 class=\"cp-group-head\">${WebEscaping.htmlEscape(g.name)}</h2>\n")
            append("<div class=\"cp-cards\">\n")
            g.cards.forEach { append(cardHtml(it)).append("\n") }
            append("</div>\n</div>\n")
          }
          append("</div>")
        }
      } else if (!hasTabs) {
        "<div class=\"cp-grid\" id=\"cp-grid\">\n        $cards\n        </div>"
      } else {
        buildString {
          append("<div class=\"cp-sections\" id=\"cp-grid\">\n")
          sections.forEach { sec ->
            // `role="region"`, not `tabpanel`: the navigation above is a tree, and a tabpanel with
            // no tab to own it is a role that describes a relationship the page no longer has.
            append("<section class=\"cp-section\" id=\"cp-panel-${sec.slug}\" role=\"region\"")
            append(" aria-labelledby=\"cp-tab-${sec.slug}\" data-section=\"${sec.slug}\">\n")
            append("<h2 class=\"cp-section-head\">${WebEscaping.htmlEscape(sec.name)}</h2>\n")
            sec.groups.forEach { g ->
              // The tree's group rows jump here, so a named group carries the anchor id the row
              // links to; an unnamed one has no row and needs none.
              val anchor = if (g.name == null) "" else " id=\"${groupAnchorId(sec.slug, g.slug)}\""
              // `--cp-n`: the cluster's width in cards — see the synthesized-groups branch above.
              append("<div class=\"cp-subgroup\"$anchor style=\"--cp-n:${g.cards.size}\">\n")
              if (g.name != null)
                append("<h3 class=\"cp-group-head\">${WebEscaping.htmlEscape(g.name)}</h3>\n")
              append("<div class=\"cp-cards\">\n")
              g.cards.forEach { append(cardHtml(it)).append("\n") }
              append("</div>\n</div>\n")
            }
            append("</section>\n")
          }
          append("</div>")
        }
      }
    // With a tree, the filter shares its sticky sidebar; a small catalog with no tree keeps the
    // filter in the toolbar.
    val sidebarSearch =
      if (tabBar.isEmpty() || previews.isEmpty()) "" else searchBoxHtml(usesFilter) + "\n"
    // With pages too, the tree becomes the Components pane and the pane strip leads, above the
    // shared filter.
    val sidebarBody =
      if (!hasPanes) tabBar
      else
        paneTabsHtml(previews.size, designPages.size) +
          "\n" +
          sidebarSearch +
          "<div class=\"cp-pane\" id=\"cp-pane-components\" role=\"tabpanel\"" +
          " aria-labelledby=\"cp-pane-tab-components\">\n" +
          tabBar +
          "</div>\n" +
          pagesPaneHtml(designPages, basePath, q)
    val sidebarHead = if (hasPanes) "" else sidebarSearch
    val navAndGrid =
      if (tabBar.isEmpty()) "$tabBar$gridBlock"
      else
        "<div class=\"cp-catalog-body\">\n" +
          "<aside class=\"cp-catalog-menu\" aria-label=\"Catalog menu\">\n" +
          "$sidebarHead$sidebarBody</aside>\n$gridBlock\n</div>"
    // A catalog page links home via a single back button (in the header's brand slot) whenever this
    // server has a home index.
    // The brand already links to the front door; an adjacent back button duplicated it.
    val back = ""
    // The provenance strip rides in the site footer, expanded, beside the build and source links.
    val prov = provenance?.let { provenanceSection(it, refreshUrl) } ?: ""
    // Show the Theme control only when there is more than one theme to choose between.
    val hasBakedThemes = groups.any { it.swappable }
    val hasThemes = hasBakedThemes || declaredThemeChips.isNotEmpty()
    val themeToggle =
      if (hasThemes) themePickerHtml(hasBakedThemes, declaredThemeChips) + "\n" else ""
    // Search + empty-state + the combined filter script are shown whenever there are previews to
    // filter, independent of the theme axis.
    val hasPreviews = previews.isNotEmpty()
    val searchBox = if (hasPreviews && tabBar.isEmpty()) searchBoxHtml(usesFilter) + "\n" else ""
    val emptyState =
      if (hasPreviews)
        "\n<p id=\"cp-empty\" class=\"cp-empty\" hidden>No previews match your filter.</p>"
      else ""
    // Themed-render URLs in grid document order (matching `document.querySelectorAll(".cp-card")`).
    // Empty unless declared themes are offered.
    val orderedCards =
      when {
        hasTabs -> sections.flatMap { s -> s.groups.flatMap { it.cards } }
        synthGroups != null -> synthGroups.flatMap { it.cards }
        else -> groups
      }
    val themeBaseJs =
      if (declaredThemeChips.isEmpty()) ""
      else orderedCards.joinToString(", ", "[", "]") { WebEscaping.jsString(themeBase(it)) }
    val filterScript =
      if (hasPreviews)
        "\n${componentScriptTags("catalog")}\n<script>${catalogFilterScript(
          hasThemes,
          hasTabs,
          hasGroups,
          tabBar.isNotEmpty(),
          themeStorageKey(sessionId, basePath),
          tabStorageKey(sessionId, basePath),
          themeBaseJs,
          themeLeaseUrl,
          presenceUrl,
          hasPanes,
          hasAllTab,
          usesUrl,
        )}</script>"
      else ""
    // Live-lane ids in the same document order as the cards (and [themeBaseJs]): a light/dark pair
    // per card, or empty strings when it can't stream.
    val liveScript =
      if (componentBrowser) ""
      else
        catalogLiveScript(
          basePath = basePath,
          query = linkQuery(token, linkSessionId, basePath, isPublic),
          cards =
            orderedCards.map { card ->
              fun streamable(p: ServePreview) = if (canStreamLiveFor(p.id)) p.id else ""
              if (card.swappable) streamable(card.light!!) to streamable(card.dark!!)
              else streamable(card.default).let { it to it }
            },
          signInHref = liveSignInHref,
        )
    // Discoverability for the gesture: the per-card affordance only appears on hover, so the
    // header says once that the lane exists. Shown exactly when a card can actually take it.
    val liveNote =
      if (liveScript.isEmpty()) ""
      else " · <span class=\"cp-live-note\">hold a card for a live session</span>"
    // The catalog's actions: M3 assist chips under the summary line, so each route is visibly a
    // destination.
    fun actionChip(href: String, label: String): String =
      "<a class=\"cp-action-chip\" href=\"${WebEscaping.htmlEscape(href)}\">" +
        "${WebEscaping.htmlEscape(label)}</a>"

    // One action per comparison, each deep-linking the comparison page's `?format=`; only available
    // formats appear.
    fun compareChip(format: String, label: String): String {
      val query =
        listOf("format=$format", linkQuery(token, linkSessionId, basePath, isPublic))
          .filter { it.isNotEmpty() }
          .joinToString("&")
      return actionChip("$basePath/compare?$query", label)
    }
    // The chips in named groups. The first holds every baseline this catalog can compare against,
    // named by what it is — the same words the wall's Baseline group and the viewer's
    // Compare-against group use. See `docs/design/COMPARE_NAVIGATION.md`, §2 and §3.3.
    fun chipGroup(label: String, chips: List<String>): String =
      if (chips.isEmpty()) ""
      else
        "<div class=\"cp-actions-group\">" +
          "<span class=\"cp-actions-group-label\">${WebEscaping.htmlEscape(label)}</span>" +
          chips.joinToString("") +
          "</div>"

    val compareChips =
      listOfNotNull(
        // Named after the design tool it compares against when the catalog identifies one, since
        // "Figma" says what you get where "reference" would name the format slug.
        compareChip("reference", designToolLabel ?: "design references").takeIf {
          hasReferenceComparison
        },
        parallelComparisonLabel
          ?.takeIf { hasParallelComparison }
          ?.let { compareChip("parallel", it) },
        compareChip("svg", "SVG").takeIf { hasSvgComparison },
        compareChip("rc", "Remote Compose players").takeIf { hasRcComparison },
      )
    // The parity index gets its own group: it is not a baseline but the list of which comparisons
    // are worth opening.
    val reportChips =
      listOfNotNull(actionChip("$basePath/parity$q", "design parity").takeIf { hasParityView })
    val exploreChips =
      listOfNotNull(
        // Fallback Pages chip for a catalog too small for a tree, so pages stay reachable. The
        // label carries the count and coverage ("N of M components implemented"; see §3.4 of the
        // design note).
        designPages
          .takeIf { it.isNotEmpty() && !hasTree }
          ?.let {
            actionChip(
              "$basePath/pages$q",
              "${it.size} design ${if (it.size == 1) "page" else "pages"}",
            )
          },
        // The motion browser: the only place to discover the catalog's captures. Not gated on the
        // tree, which has nothing to fall back on.
        motionCaptureCount
          .takeIf { it > 0 }
          ?.let {
            actionChip("$basePath/motion$q", "$it motion ${if (it == 1) "capture" else "captures"}")
          },
        playgroundHref?.takeIf { it.isNotBlank() }?.let { actionChip(it, "try in playground") },
      )
    val actionChips =
      listOf(
          chipGroup("Compare against", compareChips),
          chipGroup("Reports", reportChips),
          chipGroup("Explore", exploreChips),
        )
        .filter { it.isNotBlank() }
        .joinToString("\n          ")
    val transparentAction =
      if (hasPreviews && !componentBrowser)
        bgPickerHtml("Show the transparent checkerboard behind each preview")
      else ""
    val catalogActions =
      listOf(actionChips, transparentAction).filter { it.isNotBlank() }.joinToString("\n          ")
    // …behind one `⋯` menu beside the Theme pill at every width: destinations (comparisons, parity,
    // playground) plus the Transparent toggle.
    val primaryActions =
      catalogActions
        .takeIf { it.isNotBlank() }
        ?.let {
          "<div class=\"cp-catalog-actions\">\n" +
            "          <details class=\"cp-actions-menu\">\n" +
            "            <summary class=\"cp-drawer-toggle cp-axis-toggle\" " +
            "title=\"More for this catalog\" aria-label=\"More for this catalog\" " +
            "aria-controls=\"cp-catalog-actions-panel\">" +
            "<span aria-hidden=\"true\">⋯</span></summary>\n" +
            "          </details>\n" +
            "          <div class=\"cp-actions-panel\" id=\"cp-catalog-actions-panel\">\n" +
            "          $it\n          </div>\n        </div>\n"
        } ?: ""
    val downloadAction =
      if (componentBrowser) ""
      else
        "\n<div class=\"cp-catalog-download\">" +
          actionChip("$basePath/bundle.zip$q", "download all (.zip)") +
          "</div>\n"
    // The landing's identity line: name, trust verdict, id and preview/view tally on one baseline,
    // like `.cp-preview-head` in the viewer.
    val subLine =
      if (componentBrowser) ""
      else
        "<p class=\"cp-sub\">${counted(previews.size, "preview(s)")}" +
          (if (systemViews > 0) " · ${formatViews(systemViews)}" else "") +
          "$liveNote</p>"
    // …and the control row: compact pills at the trailing edge (`.cp-head-toggles`, as in the
    // viewer's title row) with the filter field taking the remaining width, one sticky row on every
    // viewport.
    val headToggles =
      (themeToggle + primaryActions)
        .takeIf { it.isNotBlank() }
        ?.let { "<div class=\"cp-head-toggles\">\n$it</div>\n" } ?: ""
    // The toolbar row exists for the filter. When the filter lives elsewhere (browser mode, or a
    // sectioned catalog's sidebar), the toggles ride on the identity row and no toolbar row is
    // emitted.
    val togglesOnTitleRow = componentBrowser || searchBox.isBlank()
    val titleRow =
      "<div class=\"cp-catalog-head-row\">" +
        "<div class=\"cp-catalog-title\">" +
        "<h1 class=\"cp-head cp-catalog-head\">${WebEscaping.htmlEscape(heading)}" +
        "${compactTrustBadge(trust)}</h1>$catalogId</div>$subLine" +
        (if (togglesOnTitleRow) headToggles else "") +
        "</div>"
    // The landing's page-scoped catalog report under the identity row, giving the floating launcher
    // a catalog half on this page in both modes.
    val reportRow = pageReportRowHtml(reportIssue, "cp-page-links")
    val tools =
      (searchBox + if (togglesOnTitleRow) "" else headToggles)
        .takeIf { it.isNotBlank() }
        ?.let { "<div class=\"cp-catalog-tools\">\n$it</div>\n" } ?: ""
    return document(
      changelogHref = changelogHref,
      title = "$heading — compose-preview",
      unfurlTitle = heading,
      unfurlDescription = catalogUnfurlDescription(previews.size, heading),
      unfurl = unfurl,
      navSuffix = navSuffix,
      headerBreadcrumb = back,
      version = version,
      footerNote = if (componentBrowser) "" else prov,
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      declaredThemes = declaredThemeChips,
      headerAction = if (componentBrowser) "" else githubAuthControl(githubAuth),
      headerSessionSettings = if (componentBrowser) "" else githubSessionSettings(githubAuth),
      body =
        """
        $titleRow$reportRow
        ${degradeBanner(degradations)}$renderFailureSummary$tools$navAndGrid$emptyState$filterScript$liveScript$downloadAction
        <!-- Finishes the phone shape of this page's chrome: the tree sidebar's filter field moves
             into the sticky toolbar beside the Theme and `⋯` menus already there, and back out
             again above 640px, and the summary tally drops below the grid. Renders nothing;
             `serve.css` hides the tag. -->
        <cp-catalog-toolbar></cp-catalog-toolbar>
        """
          .trimIndent(),
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
    )
  }

  /**
   * Display name for a design reference's `source.provider` token (`figma` → `Figma`). Null for
   * providers naming no design tool (`png`, `svg`, `html`, `file`) and for unknown tokens, so
   * callers use neutral wording.
   */
  fun designToolLabel(provider: String?): String? =
    when (provider?.trim()?.lowercase()) {
      "figma" -> "Figma"
      "sketch" -> "Sketch"
      "penpot" -> "Penpot"
      "framer" -> "Framer"
      else -> null
    }

  /** PNG↔native-format and PNG↔design-reference comparison page for one served session. */
  fun comparisonPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    declaredSurface: String? = null,
    /**
     * The catalog's palette as an inline `:root` override, built by [ServeThemeCss] from
     * `tokens.dtcg.json`. Empty keeps the built-in chrome.
     */
    themeCss: String = "",
    hasSvgFor: (String) -> Boolean = { false },
    hasRemoteComposeFor: (String) -> Boolean = { false },
    /**
     * The catalog's published Remote Compose player comparison, if any. Present ⇒ the `rc` format
     * shows every player's published render side by side ([rcLanesSection]) instead of rendering in
     * the browser.
     */
    rcCompare: RcCompareManifest? = null,
    /**
     * Remote Compose players this host can raster on demand (normally
     * [ServeHost.enabledRcPlayersFor]). The wall adds a column for each one the published run
     * lacks, pointing at `/render/<id>.png?rcPlayer=<wire>`.
     *
     * `?rcPlayer=` is served from published bytes when the run drew them and only renders
     * otherwise, so staged lanes cost nothing extra. Empty (every static host) leaves the wall as
     * published.
     */
    liveRcPlayersFor: (String) -> List<RcPlayerBackend> = { emptyList() },
    referencesFor: (String) -> List<DesignReference> = { emptyList() },
    /** A paired catalog's design reference, used only when this preview has no local mapping. */
    pairedDesignSourceFor: (ServePreview) -> SpecSource? = { null },
    /** The paired catalog implementation whose renders form the parallel comparison lane. */
    parallelSourceFor: (ServePreview) -> SpecSource? = { null },
    unfurl: UnfurlMetadata? = null,
    /**
     * The page-scoped report this wall files against the catalog's repo — the launcher's catalog
     * half, hidden on pages without `#cp-report`. Page-scoped because the wall shows every
     * component; a single row's defect is better reported from its focused comparison page. Null
     * renders nothing.
     */
    reportIssue: ReportIssue? = null,
    /**
     * The catalog's published GitHub issues (`parity/issues.json`), joined to rows as the **Bugs**
     * column so a triager sees both the score and whether anyone already knows. Empty drops the
     * column.
     */
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * When the issue index was generated (`ParityIssues.generatedAt`, ISO-8601), printed at the
     * foot of an opened Bugs panel. Null omits the line.
     */
    parityIssuesGeneratedAt: String? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * The delivery-branch commit this wall was assembled from, scoping every published frame it
     * draws ([ServeCacheGeneration]), so its scores and the focused comparison describe the same
     * publish. Null without a delivery branch.
     */
    generation: String? = null,
  ): String {
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    // The frame query: the page query plus the publish this wall is about. This page takes no pin
    // — it is always the current catalog — so the generation is the whole of it.
    val assetQ = ServeCacheGeneration.scope(q, generation)
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)
    // Native-format rows keep the one-default-card presentation, but a design reference names an
    // exact state/props/size, so a referenced variant stays visible as its own row. Size is folded
    // only when that size carries no reference.
    val comparableDarkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    val comparablePrimarySizes = primarySizeByComponent(previews, comparableDarkFirst)
    val pairedDesignSources = previews.associateWith(pairedDesignSourceFor)
    val parallelSources = previews.associateWith(parallelSourceFor)
    fun hasEffectiveReference(preview: ServePreview): Boolean =
      referencesFor(preview.id).isNotEmpty() || pairedDesignSources[preview] != null
    val comparablePreviews = previews.filterNot { preview ->
      (isNonDefaultState(preview) ||
        hasNonDefaultProps(preview) ||
        isNonPrimarySize(preview, comparablePrimarySizes, comparableDarkFirst)) &&
        !hasEffectiveReference(preview) &&
        parallelSources[preview] == null
    }
    val cards = groupPreviews(comparablePreviews)
    val hasSvg = comparablePreviews.any { hasSvgFor(it.id) }
    // The published comparison is a Remote Compose lane in its own right: it may cover previews
    // whose `.rc` sidecar never reached this box, so it turns the format on by itself.
    val hasRc = comparablePreviews.any { hasRemoteComposeFor(it.id) } || rcCompare != null
    val hasReference = comparablePreviews.any(::hasEffectiveReference)
    val hasParallel = comparablePreviews.any { parallelSources[it] != null }
    // Name the design lane after the references' tool ("PNG ↔ Figma"), matching the catalog's
    // action; plain PNG/mock references keep the neutral label.
    val referenceToolLabel =
      comparablePreviews.firstNotNullOfOrNull { preview ->
        referencesFor(preview.id).firstNotNullOfOrNull { designToolLabel(it.source.provider) }
          ?: pairedDesignSources[preview]?.label
      } ?: "Design reference"
    val parallelLabel =
      comparablePreviews.firstNotNullOfOrNull { parallelSources[it]?.label }
        ?: "Parallel implementation"
    // Which baseline the wall opens on: raster pairs first — the design reference, then the paired
    // sibling — and the export lanes last. `svg` is slowest to paint and can show tofu for fonts
    // the browser lacks. See `docs/design/COMPARE_NAVIGATION.md`, §3.2.
    val defaultFormat =
      if (hasReference) "reference"
      else if (hasParallel) "parallel" else if (hasSvg) "svg" else if (hasRc) "rc" else "parallel"
    // One column order for every lane: baseline · diff · ours, matching the viewer's spec lane, so
    // switching baseline never swaps sides. `compare/columns.ts` owns the rule and
    // `<cp-compare-wall>` re-asserts it on arrival (`docs/design/COMPARE_NAVIGATION.md`, F3).
    // `loading="lazy"` applies however late `<cp-compare-wall>` assigns the `src`; the element also
    // defers its scoring fetches to the viewport, so pictures and scorer wait together.
    val renderCell =
      "<td class=\"cp-compare-render-cell\"><div class=\"cp-compare-shot\">" +
        "<img loading=\"lazy\" class=\"cp-compare-png\" alt=\"\"></div>" +
        // Filled by `<cp-compare-wall>` from the decoded raster, since the wall chooses which theme
        // variant is on screen.
        "<span class=\"cp-compare-dim\" data-dim-for=\"png\"></span></td>"
    // Classed because a row holds two canvases (this and the delta map), which `<cp-compare-wall>`
    // must tell apart.
    val targetCell =
      "<td class=\"cp-compare-target-cell\"><div class=\"cp-compare-shot\">" +
        "<img loading=\"lazy\" class=\"cp-compare-vector\" alt=\"\">" +
        "<canvas class=\"cp-compare-rc\" hidden></canvas>" +
        "</div><span class=\"cp-compare-dim\" data-dim-for=\"target\"></span></td>"
    // The delta map sits between the pair. Only the reference lane shows it (`serve.css` keys off
    // `#cp-compare[data-format]`); for vector lanes it would describe the exporter, not the design.
    val diffCell =
      "<td class=\"cp-compare-diff-cell\"><div class=\"cp-compare-shot\">" +
        "<canvas class=\"cp-compare-diff\" aria-label=\"Highlighted pixel difference\"></canvas>" +
        "</div></td>"
    val pictureCells = listOf(targetCell, diffCell, renderCell).joinToString("\n            ")
    val darkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    // A viewer deep link may name a folded-out variant, so sibling ids alias onto the included row.
    val previewIdsByCard =
      previews.groupBy(::comparisonCardKey).mapValues { (_, values) -> values.map { it.id } }

    // Only the raster is generation-scoped; vector and player products are `no-store`, and the
    // render lane ignores `gen=` on them.
    fun path(preview: ServePreview, extension: String): String =
      "$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.$extension" +
        (if (extension == "png") assetQ else q)

    fun attrs(
      kind: String,
      theme: String,
      preview: ServePreview?,
      available: (String) -> Boolean,
    ): String {
      if (preview == null || !available(preview.id)) return ""
      return " data-$kind-$theme=\"${WebEscaping.htmlEscape(path(preview, if (kind == "png") "png" else if (kind == "svg") "svg" else "rc"))}\""
    }

    /**
     * The score the delivery branch already measured for one pair, if any.
     * `design-reference-score.mjs` bakes it into `references/index.json` with the wall's own
     * scorer, and [ServeDesignReferenceStore] drops it unless it names this build's kernel. Null
     * for older catalogs or runs without a browser.
     */
    fun bakedPercent(preview: ServePreview?): Double? = preview?.let {
      referencesFor(it.id).firstOrNull()?.match?.percent
    }

    /**
     * The baked score of the pair this row is served showing (`variantFor` for the catalog's theme,
     * then `neutral`), used for the served order. `<cp-compare-wall>` re-sorts from per-variant
     * attributes when lane or theme changes.
     */
    fun servedReferencePercent(card: GridCard): Double? {
      val themed = if (darkFirst) card.dark else card.light
      val variant =
        listOfNotNull(themed, card.neutral).firstOrNull { referencesFor(it.id).isNotEmpty() }
      return bakedPercent(variant)
    }

    /** The focused Reference / Diff / Actual page for one pair. */
    fun detailHref(preview: ServePreview, reference: DesignReference): String {
      val detailQuery =
        linkQuery(token, linkSessionId, basePath, isPublic).let { query ->
          listOf(query, "reference=${WebEscaping.urlEncodeSegment(reference.id)}")
            .filter { it.isNotEmpty() }
            .joinToString("&")
        }
      return "$basePath/compare/${WebEscaping.urlEncodeSegment(preview.id)}${querySuffix(detailQuery)}"
    }

    fun referenceAttrs(theme: String, preview: ServePreview?): String {
      preview ?: return ""
      val reference = referencesFor(preview.id).firstOrNull()
      if (reference == null) {
        val paired = pairedDesignSources[preview] ?: return ""
        return " data-reference-$theme=\"${WebEscaping.htmlEscape(paired.rasterUrl)}\""
      }
      val raster = "$basePath/reference/${WebEscaping.urlEncodeSegment(reference.id)}.png$assetQ"
      val detail = detailHref(preview, reference)
      // The published score rides per variant with the pair it describes, so switching theme never
      // leaves the other theme's number.
      val match =
        reference.match
          ?.let { " data-match-$theme=\"${String.format(Locale.ROOT, "%.2f", it.percent)}\"" }
          .orEmpty()
      return " data-reference-$theme=\"${WebEscaping.htmlEscape(raster)}\"" +
        " data-reference-detail-$theme=\"${WebEscaping.htmlEscape(detail)}\"" +
        match
    }

    fun parallelAttrs(theme: String, preview: ServePreview?): String {
      val source = preview?.let { parallelSources[it] } ?: return ""
      return " data-parallel-$theme=\"${WebEscaping.htmlEscape(source.rasterUrl)}\""
    }

    val shownCards = cards.filter { card ->
      listOfNotNull(card.light, card.dark, card.neutral).any { p ->
        hasSvgFor(p.id) ||
          hasRemoteComposeFor(p.id) ||
          hasEffectiveReference(p) ||
          parallelSources[p] != null
      }
    }
    // Every id with a row of its own. A referenced variant gets its own row, so it must not also
    // alias onto siblings' rows, or filtering by its id would match them all.
    val rowPreviewIds =
      shownCards
        .flatMap { card -> listOfNotNull(card.light, card.dark, card.neutral).map { it.id } }
        .toSet()
    // The genuinely folded-out siblings still have to select something, so each is aliased onto
    // exactly ONE row — the first row of its comparison card — rather than onto all of them.
    val aliasesClaimed = mutableSetOf<String>()
    // The **Bugs** column appears only when the catalog publishes an issue index, and then on every
    // row, including unfiled ones.
    val showBugs = parityIssues.isNotEmpty()
    // **Served worst-first** using the delivery branch's published scores, so the page arrives in
    // the order the client's measurement would settle into.
    //
    // Reference lane only (`svg` and `rc` publish no score). Unscored rows sort after scored ones;
    // "not scored yet" is not a finding.
    val orderedCards =
      if (defaultFormat != "reference") shownCards
      else shownCards.sortedBy { servedReferencePercent(it) ?: Double.MAX_VALUE }
    val rows =
      orderedCards.joinToString("\n") { card ->
        val variants = listOfNotNull(card.light, card.dark, card.neutral)
        val current = if (darkFirst) card.dark ?: card.default else card.default
        val component = componentKey(current)
        // The variant spelled out, so a component's per-state rows are distinguishable.
        val variant = compareVariantLabel(current)
        val label = if (variant.isEmpty()) component else "$component — $variant"
        val viewer = "$basePath/p/${WebEscaping.urlEncodeSegment(current.id)}$q"
        val cardKey = comparisonCardKey(current)
        // Claimed once per card, so an id with no row of its own selects one row; the list goes
        // into the document's alias table.
        val folded =
          if (aliasesClaimed.add(cardKey))
            previewIdsByCard[cardKey].orEmpty().filterNot { it in rowPreviewIds }
          else emptyList()
        // …and the row points at it by key. Only the row that claimed the card carries the
        // attribute, so the aliases still resolve onto exactly one row.
        val aliasAttr =
          if (folded.isEmpty()) "" else " data-alias-card=\"${WebEscaping.htmlEscape(cardKey)}\""
        // The row's own variants stay on the row; folded aliases go to the document's one alias
        // table ([comparisonAliasTableHtml]) to avoid quadratic page size. See
        // `docs/design/COMPARE_NAVIGATION.md`, F2.
        val ids = variants.map { it.id }.distinct().joinToString(" ")
        val previewAttrs =
          listOf("light" to card.light, "dark" to card.dark, "neutral" to card.neutral)
            .mapNotNull { (variant, preview) ->
              preview?.let { " data-preview-$variant=\"${WebEscaping.htmlEscape(it.id)}\"" }
            }
            .joinToString("")
        // Component issues join against the whole row; exact issues join only against `current`.
        val bugs =
          issuesForRow(
            parityIssues,
            variants.map { it.id } + folded,
            ServeIssueReport.componentIdFor(current),
            variants.map { it.id },
          )
        // Where "+ file" lands at rest: the focused Reference / Diff / Actual page for the served
        // pair. `<cp-compare-wall>` re-points it as lane or theme changes; the viewer's report is
        // the fallback.
        val servedDetail =
          listOfNotNull(if (darkFirst) card.dark else card.light, card.neutral)
            .firstNotNullOfOrNull { preview ->
              referencesFor(preview.id).firstOrNull()?.let { detailHref(preview, it) }
            }
        val bugCell =
          if (showBugs)
            compareBugsCellHtml(
              bugs,
              current.id,
              servedDetail,
              "$viewer#cp-report",
              parityIssuesGeneratedAt,
            )
          else ""
        // The row's component identity for locators, from `ServeIssueReport.componentIdFor`, so the
        // browser doesn't reimplement its fallback.
        val componentIdAttr =
          " data-component-id=\"${WebEscaping.htmlEscape(ServeIssueReport.componentIdFor(current))}\"" +
            // …and its display name, for the scope chip a `?component=` link arrives with.
            " data-component-label=\"${WebEscaping.htmlEscape(component)}\""
        // The multi-row picker, hidden by `serve.css` until `<cp-compare-wall>` marks the wall
        // pickable; rows the lane cannot pair are disabled.
        val pickCell =
          "<label class=\"cp-compare-pick\">" +
            "<input type=\"checkbox\" class=\"cp-compare-pick-input\" " +
            "aria-label=\"Include ${WebEscaping.htmlEscape(label)} in one report\">" +
            "</label>"
        // Issue numbers and titles join the haystack, so `#4624` or a visible phrase narrows the
        // wall.
        // Ids are not repeated here; `keepRow` matches typed ids against the resolved alias list.
        val hay =
          (listOf(label) + bugs.flatMap { listOf("#${it.number}", it.title.trim()) })
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()
        val pngAttrs =
          attrs("png", "light", card.light) { true } +
            attrs("png", "dark", card.dark) { true } +
            attrs("png", "neutral", card.neutral) { true }
        val svgAttrs =
          attrs("svg", "light", card.light, hasSvgFor) +
            attrs("svg", "dark", card.dark, hasSvgFor) +
            attrs("svg", "neutral", card.neutral, hasSvgFor)
        val rcAttrs =
          attrs("rc", "light", card.light, hasRemoteComposeFor) +
            attrs("rc", "dark", card.dark, hasRemoteComposeFor) +
            attrs("rc", "neutral", card.neutral, hasRemoteComposeFor)
        val referenceAttrs =
          referenceAttrs("light", card.light) +
            referenceAttrs("dark", card.dark) +
            referenceAttrs("neutral", card.neutral)
        val parallelDataAttrs =
          parallelAttrs("light", card.light) +
            parallelAttrs("dark", card.dark) +
            parallelAttrs("neutral", card.neutral)
        // The ground each variant declares for itself, if any — only the preview's own rungs; the
        // catalog default is the wall's `data-default-theme`.
        val declaredBgAttrs =
          listOf("light" to card.light, "dark" to card.dark, "neutral" to card.neutral)
            .mapNotNull { (variant, preview) ->
              preview
                ?.let { declaredBackdropTheme(it) }
                ?.let { " data-declared-bg-$variant=\"$it\"" }
            }
            .joinToString("")
        """
          <tr class="cp-compare-row" data-label="${WebEscaping.htmlEscape(label)}"
            data-hay="${WebEscaping.htmlEscape(hay)}" data-preview-ids="${WebEscaping.htmlEscape(ids)}"$aliasAttr$componentIdAttr$previewAttrs$pngAttrs$svgAttrs$rcAttrs$referenceAttrs$parallelDataAttrs$declaredBgAttrs>
            <th scope="row">$pickCell<a href="$viewer">${WebEscaping.htmlEscape(component)}${
            if (variant.isEmpty()) ""
            else "<span class=\"cp-compare-variant\">${WebEscaping.htmlEscape(variant)}</span>"
          }</a></th>
            $pictureCells
            <td class="cp-compare-score">waiting…</td>$bugCell
          </tr>
          """
          .trimIndent()
      }

    val formatControls = buildString {
      if (hasSvg)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"svg\" " +
            "aria-pressed=\"${defaultFormat == "svg"}\">PNG ↔ SVG</button>"
        )
      if (hasRc)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"rc\" " +
            "aria-pressed=\"${defaultFormat == "rc"}\">" +
            (if (rcCompare != null) "Remote Compose players" else "PNG ↔ Remote Compose") +
            "</button>"
        )
      if (hasReference)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"reference\" " +
            // Named in the order the columns stand: the spec leads this lane, so the button that
            // enters it does too. Every other lane keeps the render first and says "PNG ↔ …".
            "aria-pressed=\"${defaultFormat == "reference"}\">" +
            "${WebEscaping.htmlEscape(referenceToolLabel)} ↔ PNG</button>"
        )
      if (hasParallel)
        append(
          "<button type=\"button\" class=\"cp-theme-btn\" data-compare-format=\"parallel\" " +
            "aria-pressed=\"${defaultFormat == "parallel"}\">" +
            "${WebEscaping.htmlEscape(parallelLabel)} ↔ PNG</button>"
        )
    }
    val themeControls =
      if (cards.any { it.swappable })
        // Wrapped so the lane wall can hide it wholesale: those renders were rasterised offline at
        // the run's own theme, so offering a theme switch over them would be a lie.
        """
        <span class="cp-compare-theme-controls">
          <span class="cp-compare-control-label">Theme</span>
          <span class="cp-theme" role="group" aria-label="Comparison theme">
            <button type="button" class="cp-theme-btn" data-compare-theme="light" aria-pressed="${!darkFirst}">Light</button>
            <button type="button" class="cp-theme-btn" data-compare-theme="dark" aria-pressed="$darkFirst">Dark</button>
          </span>
        </span>
        """
          .trimIndent()
      else ""

    // Named for the lane actually showing; `compare/columns.ts` keeps the client's relabelling in
    // step.
    val targetHead =
      when (defaultFormat) {
        "reference" -> referenceToolLabel
        "parallel" -> parallelLabel
        "rc" -> "Remote Compose"
        else -> "SVG"
      }
    // The catalog's title names whose picture this is; the `cp-compare-head-role` lines say which
    // side is the yardstick.
    fun headHtml(cls: String, name: String, role: String): String =
      "<th class=\"$cls\"><span class=\"cp-compare-head-name\">" +
        "${WebEscaping.htmlEscape(name)}</span>" +
        "<span class=\"cp-compare-head-role\">$role</span></th>"
    val renderHeadHtml = headHtml("cp-compare-render-head", heading, "ours")
    val targetHeadHtml = headHtml("cp-compare-target-head", targetHead, "baseline")
    val diffHeadHtml = "<th class=\"cp-compare-diff-head\">Diff</th>"
    val pictureHeads = targetHeadHtml + diffHeadHtml + renderHeadHtml
    val empty =
      if (rows.isEmpty())
        "<p class=\"cp-empty\">No previews in this session carry a comparable format.</p>"
      else
        """
        <div class="cp-compare-table-wrap">
          <table class="cp-compare-table">
            <thead><tr><th>Preview</th>$pictureHeads<th>Match</th>${
          if (showBugs) "<th class=\"cp-compare-bugs-head\">Bugs</th>" else ""
        }</tr></thead>
            <tbody>$rows</tbody>
          </table>
        </div>
        <p id="cp-compare-empty" class="cp-empty" hidden>No comparisons match this filter.</p>
        """
          .trimIndent()
    val rcLanes = rcCompare?.let {
      rcLanesSection(
        it,
        previews,
        previewIdsByCard,
        token,
        linkSessionId,
        basePath,
        isPublic,
        generation,
        liveRcPlayersFor,
      )
    }
    // The wall's page-scoped catalog report, in a provenance row of its own — see
    // [pageReportRowHtml] for why it borrows the viewer's row rather than styling a new one.
    val reportRow = pageReportRowHtml(reportIssue, "cp-compare-links")
    // What the row pickers have selected, as a live region above the report they feed.
    // Server-rendered `hidden` and unhidden by `<cp-compare-wall>`.
    val pickedBar =
      "\n          <p id=\"cp-compare-picked\" class=\"cp-compare-picked\" role=\"status\" hidden>" +
        "<span class=\"cp-compare-picked-text\"></span>" +
        "<button type=\"button\" class=\"cp-compare-picked-clear\">clear</button></p>"
    val rootAttrs =
      "data-default-format=\"$defaultFormat\" data-default-theme=\"${if (darkFirst) "dark" else "light"}\" " +
        "data-theme-key=\"${WebEscaping.htmlEscape(themeStorageKey(sessionId, basePath))}\" " +
        "data-has-svg=\"${if (hasSvg) "1" else "0"}\" data-has-rc=\"${if (hasRc) "1" else "0"}\" " +
        "data-has-reference=\"${if (hasReference) "1" else "0"}\" " +
        "data-has-parallel=\"${if (hasParallel) "1" else "0"}\" " +
        "data-reference-label=\"${WebEscaping.htmlEscape(referenceToolLabel)}\" " +
        "data-parallel-label=\"${WebEscaping.htmlEscape(parallelLabel)}\"" +
        // Tells `serve.css` the Bugs column is present so its width comes out of the panels, not
        // `Match`.
        (if (showBugs) " data-has-bugs=\"1\"" else "") +
        (if (rcLanes != null) " data-rc-lanes=\"1\"" else "")

    return document(
      changelogHref = changelogHref,
      title = "$heading — format comparison",
      unfurlTitle = "$heading format comparison",
      unfurlDescription = "Compare rendered PNG, SVG, and Remote Compose output for $heading",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Compare formats"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      // The RC comparison plays and scores the document on this page, so an unregistered typeface
      // would skew the fidelity number.
      rcFonts = hasRc,
      body =
        """
        <div id="cp-compare" $rootAttrs>
          <h1 class="cp-head">Format comparison${compactTrustBadge(trust)}</h1>
          <p class="cp-sub"><span class="cp-sub-formats">PNG, SVG and Remote Compose fidelity · scores measure the drawn content on a fixed backdrop</span>${
          if (rcLanes != null)
            "<span class=\"cp-sub-rc\">Every Remote Compose player this catalog's parity run published, side by side · pixel diffs from that run</span>"
          else ""
        }</p>
          <div class="cp-compare-controls">
            <span class="cp-theme" role="group" aria-label="Comparison format">$formatControls</span>
            $themeControls
          </div>
          <p id="cp-compare-scope" class="cp-compare-scope" role="status" hidden>
            <span class="cp-compare-scope-text"></span>
            <a class="cp-compare-scope-clear" href="#">compare every component</a></p>
          <div class="cp-searchbar cp-compare-searchbar">
            <input id="cp-compare-search" class="cp-search" type="search" placeholder="Filter comparisons…" aria-label="Filter comparisons">
            <span id="cp-compare-count" class="cp-count" role="status"></span>
          </div>$pickedBar$reportRow
          <div id="cp-compare-formats">$empty</div>
          ${comparisonAliasTableHtml(previewIdsByCard, rowPreviewIds)}
          ${rcLanes.orEmpty()}
        </div>
        <!-- The components bundle is UNCONDITIONAL here now: `<cp-compare-wall>` is the wall
             itself, not just the RC lane's player, so a catalog with no Remote Compose would
             otherwise get a page whose element never upgrades. The tag comes after
             `format-compare.js` for tidiness only — the element reads
             `window.ComposePreviewCompare` when it scores rather than when it upgrades, so no
             script order can silence it. -->
        ${componentScriptTags("compare")}
        ${compareScorerTag()}
        <cp-compare-wall></cp-compare-wall>
        """
          .trimIndent(),
    )
  }

  /**
   * The backends a missing column may be filled from live — not every backend the host can draw.
   *
   * `androidx-embedded` is excluded: baked captures go through the same player, so on an Android
   * daemon it returns the baked bytes and would duplicate the baked column under another name. The
   * offline `embedded` lane is the same player too, and no row records which player baked it —
   * carry that provenance before widening this set. `cmp-jvm` is a distinct rasteriser;
   * `androidx-view` is distinct but maps to no published column; see [LIVE_ONLY_LANES].
   */
  private val LIVE_FILLABLE = setOf(RcPlayerBackend.CMP_JVM, RcPlayerBackend.ANDROIDX_VIEW)

  /**
   * A player with no offline lane id, which the wall names itself. Only
   * [RcPlayerBackend.ANDROIDX_VIEW]: the AOSP view-backed `RemoteComposePlayer` drawing into a
   * framework `Canvas`, a genuinely different renderer (hence its null
   * [RcPlayerBackend.ANDROIDX_VIEW.rcCompareLane]). Kept out of [ServeRcCompare.LANES], which
   * mirrors the offline columns, so the absent-players note never reports it.
   */
  private val LIVE_ONLY_LANES =
    mapOf(
      RcPlayerBackend.ANDROIDX_VIEW to
        RcCompareLane(
          ServeRcPlayerIds.ANDROIDX_VIEW,
          "AndroidX View",
          ServeRcPlayerIds.ANDROIDX_VIEW,
        )
    )

  /**
   * The **Remote Compose players** view: every player's published render of each `ir/<id>.rc`
   * document, one column per player, with the baked capture first.
   *
   * Nothing is diffed until a reference column is picked; then every other column gets a pixel diff
   * and mismatch chip. Replays the delivery branch's published images (mirror of `rc-compare.html`
   * from `render-rc-compare-html.mjs`), so it costs a few `<img>` loads.
   */
  /**
   * The comparison page's **alias table**: every preview id the wall can be narrowed by, written
   * once instead of per row (see `docs/design/COMPARE_NAVIGATION.md`, F2).
   *
   * `cards` is each comparison card's full id list; `rowed` is every id with its own row. The wall
   * subtracts `rowed` (a referenced variant must not alias onto siblings); the Remote Compose lane
   * wall, with one row per preview, does not. Empty ⇒ no element.
   */
  private fun comparisonAliasTableHtml(
    previewIdsByCard: Map<String, List<String>>,
    rowPreviewIds: Set<String>,
  ): String {
    val cards = previewIdsByCard.filterValues { it.size > 1 }
    if (cards.isEmpty()) return ""
    val entries =
      cards.entries.joinToString(",") { (key, ids) ->
        "${WebEscaping.jsString(key)}:${WebEscaping.jsString(ids.joinToString(" "))}"
      }
    // A JSON `<script>` rather than an attribute (tens of KB, no quote escaping). The type is
    // non-executable and [WebEscaping.jsString] escapes `<`, `>` and `&`, so no id can close the
    // element.
    return "<script type=\"application/json\" id=\"cp-compare-aliases\">" +
      "{\"cards\":{$entries}," +
      "\"rowed\":${WebEscaping.jsString(rowPreviewIds.joinToString(" "))}}" +
      "</script>"
  }

  private fun rcLanesSection(
    manifest: RcCompareManifest,
    previews: List<ServePreview>,
    previewIdsByCard: Map<String, List<String>>,
    token: String,
    /** The id links must carry as `?session=`, or null when the URL already implies it. */
    linkSessionId: String?,
    basePath: String,
    isPublic: Boolean,
    /** The wall's cache generation, scoping the staged rasters. See [ServeCacheGeneration]. */
    generation: String?,
    /** Players this host can draw on demand. See [comparisonPage]'s parameter of the same name. */
    liveRcPlayersFor: (String) -> List<RcPlayerBackend>,
  ): String? {
    if (manifest.lanes.isEmpty() || manifest.rows.isEmpty()) return null
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    // Generation-scoped because these rasters are restaged per publish while the mismatch
    // percentages are baked into the HTML.
    val assetQ = ServeCacheGeneration.scope(q, generation)
    val previewsById = previews.associateBy { it.id }
    // Staged names are `<lane>/<slot>.png` and need the catalog's rc-compare prefix; a live lane's
    // cell already carries a resolved render URL, which starts with `/` and is passed through.
    fun asset(name: String): String =
      when {
        name.isEmpty() -> ""
        name.startsWith("/") -> name
        else -> "$basePath/${ServeRcCompare.DIRECTORY}/$name$assetQ"
      }

    // Live columns: players this host can draw that the published run lacks, excluding any that
    // would copy an existing column ([LIVE_FILLABLE]).
    val publishedLaneIds = manifest.lanes.mapTo(mutableSetOf()) { it.id }
    // A backend's column id: the offline lane it corresponds to, or the one this wall names for a
    // player the offline pipeline has none for.
    fun laneIdOf(backend: RcPlayerBackend): String? =
      backend.rcCompareLane ?: LIVE_ONLY_LANES[backend]?.id
    val liveCandidates =
      RcPlayerBackend.UNIVERSE.filter { backend ->
        backend in LIVE_FILLABLE && laneIdOf(backend) !in publishedLaneIds
      }
    // Availability is per preview — a host can carry the document for one and not another — so a
    // column appears when ANY row can draw it, and the rows that cannot say so in their own cell.
    val liveFor: Map<String, Set<String>> =
      if (liveCandidates.isEmpty()) emptyMap()
      else
        manifest.rows.associate { row ->
          val wires = liveRcPlayersFor(row.previewId).mapTo(mutableSetOf()) { it.wire }
          row.previewId to
            liveCandidates.filter { it.wire in wires }.mapTo(mutableSetOf()) { it.wire }
        }
    val liveBackends = liveCandidates.filter { backend ->
      liveFor.values.any { backend.wire in it }
    }
    val liveLaneIds = liveBackends.mapNotNullTo(mutableSetOf()) { laneIdOf(it) }
    val liveWireByLane = liveBackends.associate { laneIdOf(it)!! to it.wire }
    // Every player the host reports for any row, including those [LIVE_FILLABLE] withholds, so the
    // note can distinguish "cannot draw" from "would duplicate".
    val hostWires =
      manifest.rows.flatMapTo(mutableSetOf()) { row ->
        liveRcPlayersFor(row.previewId).map { it.wire }
      }
    val withheldLaneIds =
      RcPlayerBackend.UNIVERSE.filter {
          it !in LIVE_FILLABLE &&
            it.rcCompareLane != null &&
            it.rcCompareLane !in publishedLaneIds &&
            it.wire in hostWires
        }
        .mapTo(mutableSetOf()) { it.rcCompareLane!! }
    // The live render endpoint per preview and player; `?rcPlayer=` serves published bytes when
    // staged, otherwise renders.
    val liveQuery = linkQuery(token, linkSessionId, basePath, isPublic)
    // The URL names the player in this server's vocabulary ([ServeRcPlayerIds]), not by the
    // compose-ai-tools wire spelling the membership sets above are keyed on.
    val playerIdByWire = RcPlayerBackend.UNIVERSE.associate { it.wire to ServeRcPlayerIds.of(it) }
    fun liveRenderUrl(previewId: String, wire: String): String =
      "$basePath/render/${WebEscaping.urlEncodeSegment(previewId)}.png" +
        querySuffix(
          listOf(liveQuery, "rcPlayer=${playerIdByWire[wire] ?: wire}")
            .filter { it.isNotEmpty() }
            .joinToString("&")
        )

    // One cell, built once: the table below and the client model inlined under it must agree about
    // what a lane holds, and they read this rather than each deciding.
    fun liveCell(previewId: String, laneId: String): RcCompareCell? {
      val wire = liveWireByLane[laneId] ?: return null
      return if (wire in liveFor[previewId].orEmpty())
        RcCompareCell(rendered = true, render = liveRenderUrl(previewId, wire))
      else RcCompareCell(note = "this host cannot draw this player for this preview")
    }

    // One vocabulary: a live column borrows the label the offline pipeline gives the same player,
    // so a wall mixing the two does not name one player two ways.
    val lanes =
      (manifest.lanes +
          ServeRcCompare.LANES.filter { it.id in liveLaneIds }
            .map { RcCompareLane(it.id, it.label, it.short) } +
          LIVE_ONLY_LANES.values.filter { it.id in liveLaneIds })
        // Published order first, then this wall's live lanes; `indexOfFirst` would otherwise put a
        // live-only lane (-1) ahead of `baked`.
        .sortedBy { lane ->
          val i = ServeRcCompare.LANES.indexOfFirst { it.id == lane.id }
          if (i >= 0) i else ServeRcCompare.LANES.size
        }

    // Worst-match first on the worst-scoring player, so a preview only one player gets wrong still
    // sorts to the top; rows nothing scored sink, then alphabetical. Mirrors the published page.
    fun worst(row: RcCompareRow): Double? =
      if (row.referenceBlank) null
      else row.lanes.values.filter { it.rendered }.mapNotNull { it.mismatchPct }.maxOrNull()

    val labelled =
      manifest.rows.map { row ->
        val preview = previewsById[row.previewId]
        row to (preview?.let(::componentKey) ?: row.previewId)
      }
    val ordered =
      labelled.sortedWith(
        compareBy<Pair<RcCompareRow, String>>(
          { worst(it.first) == null },
          { -(worst(it.first) ?: 0.0) },
          { it.second },
        )
      )

    val head =
      "<tr><th>Preview</th>" +
        lanes.joinToString("") { "<th>${WebEscaping.htmlEscape(it.label)}</th>" } +
        "</tr>"

    val rows =
      ordered.withIndex().joinToString("\n") { (index, entry) ->
        val (row, label) = entry
        val preview = previewsById[row.previewId]
        // The card this lane row belongs to, by key; ids live once in the shared alias table
        // (`docs/design/COMPARE_NAVIGATION.md`, F2).
        val cardKey = preview?.let(::comparisonCardKey)
        val aliasAttr =
          cardKey
            ?.takeIf { previewIdsByCard[it].orEmpty().isNotEmpty() }
            ?.let { " data-alias-card=\"${WebEscaping.htmlEscape(it)}\"" }
            .orEmpty()
        val hay = label.lowercase()
        val viewer = "$basePath/p/${WebEscaping.urlEncodeSegment(row.previewId)}$q"
        val dims = if (row.width > 0 && row.height > 0) "${row.width}×${row.height}" else ""
        val cells =
          lanes.joinToString("") { lane ->
            // A live column has no build-time diff or score; the client measures it against the
            // picked column.
            val cell = liveCell(row.previewId, lane.id) ?: row.lanes[lane.id] ?: RcCompareCell()
            val live = cell.rendered && lane.id in liveLaneIds
            val body =
              if (cell.render.isNotEmpty())
                "<img loading=\"lazy\" src=\"${WebEscaping.htmlEscape(asset(cell.render))}\" " +
                  "alt=\"${WebEscaping.htmlEscape(label)} — ${WebEscaping.htmlEscape(lane.label)}\">"
              else
                "<div class=\"cp-rc-missing\">${WebEscaping.htmlEscape(cell.note.ifBlank { "—" })}</div>"
            """
            <td><figure class="cp-rc-cell" data-lane="${WebEscaping.htmlEscape(lane.id)}"${if (live) " data-live=\"1\"" else ""}>
              <figcaption>${WebEscaping.htmlEscape(lane.label)}${
              if (live)
                "<span class=\"cp-rc-livebadge\" title=\"rendered by this server on request from the catalog's ir/*.rc document, not replayed from the parity run\">live</span>"
              else ""
            }<span class="cp-rc-refbadge">reference</span></figcaption>
              $body
              <div class="cp-rc-diffslot" hidden></div>
            </figure></td>
            """
              .trimIndent()
          }
        """
        <tr class="cp-rc-row" data-row="$index" data-hay="${WebEscaping.htmlEscape(hay)}"$aliasAttr
          data-preview-ids="${WebEscaping.htmlEscape(row.previewId)}">
          <th scope="row">
            <a href="$viewer">${WebEscaping.htmlEscape(label)}</a>
            ${if (dims.isNotEmpty()) "<div class=\"cp-rc-dims\">$dims</div>" else ""}
            ${if (row.referenceBlank) "<div class=\"cp-rc-blank\">the baked render is fully transparent — nothing to compare against</div>" else ""}
            <div class="cp-rc-scores" data-scores></div>
          </th>$cells
        </tr>
        """
          .trimIndent()
      }

    // Names the players this view knows that the run did not publish, rather than leaving readers
    // to infer them from missing columns. [ServeRcCompare.LANES] is the same vocabulary
    // [ServeRcCompare.plan] filters for `manifest.lanes`. Measured against columns actually on the
    // wall, so on-demand players count as present.
    val shown = lanes.mapTo(mutableSetOf()) { it.id }
    val absent = ServeRcCompare.LANES.filterNot { it.id in shown }
    // Two reasons for a missing column: a withheld player is one this server can draw, so say so.
    val withheld = absent.filter { it.id in withheldLaneIds }
    val absentNote =
      if (absent.isEmpty()) ""
      else
        "\n        <p class=\"cp-rc-absent\">No column for: " +
          absent.joinToString(", ") {
            "<span class=\"cp-rc-absent-lane\">${WebEscaping.htmlEscape(it.label)}</span>"
          } +
          ". A player is here when the catalog's parity run published it, or when this server can " +
          "draw it and that would show something the baked column does not." +
          (if (withheld.isEmpty()) ""
          else
            " This host does draw " +
              withheld.joinToString(", ") { WebEscaping.htmlEscape(it.label) } +
              " — but through the same embedded player the baked column already went through, so " +
              "its column would be a copy rather than a comparison.") +
          "</p>"

    val picker =
      "<button type=\"button\" class=\"cp-theme-btn\" data-rc-ref=\"none\" aria-pressed=\"true\">nothing</button>" +
        lanes.joinToString("") { lane ->
          "<button type=\"button\" class=\"cp-theme-btn\" data-rc-ref=\"${WebEscaping.htmlEscape(lane.id)}\" " +
            "aria-pressed=\"false\">${WebEscaping.htmlEscape(lane.short)}</button>"
        }

    val model =
      ServeRcCompare.ClientModel(
        threshold = manifest.threshold,
        // The columns, not the published lanes: `RcLanes` derives lane ids from this and validates
        // `?ref=` against it, so a live column missing here could never be diffed or shared.
        lanes = lanes,
        rows =
          ordered.map { (row, label) ->
            ServeRcCompare.ClientRow(
              label = label,
              referenceBlank = row.referenceBlank,
              lanes =
                row.lanes.mapValues { (_, cell) ->
                  cell.copy(render = asset(cell.render), diff = asset(cell.diff))
                } + liveLaneIds.mapNotNull { id -> liveCell(row.previewId, id)?.let { id to it } },
            )
          },
      )

    return """
      <section id="cp-rc-lanes" hidden>
        <p class="cp-sub">Pick a column and every other column grows a pixel diff and a mismatch chip.
          The baked lane replays the build-time <code>pixelmatch</code> diffs; another player diffs in your browser,
          which is how you compare two players directly.</p>$absentNote
        <div class="cp-compare-controls">
          <span class="cp-compare-control-label">Diff against</span>
          <span class="cp-theme" role="group" aria-label="Diff reference">$picker</span>
          <span id="cp-rc-status" class="cp-rc-status" role="status"></span>
        </div>
        <div class="cp-compare-table-wrap">
          <table class="cp-compare-table cp-rc-table">
            <thead>$head</thead>
            <tbody>
$rows
            </tbody>
          </table>
        </div>
        <p id="cp-rc-empty" class="cp-empty" hidden>No comparisons match this filter.</p>
        <script type="application/json" id="cp-rc-model">${ServeRcCompare.encodeClientModel(model)}</script>
        <!-- Picks the reference, measures the rows and fills in the chips. Emitted LAST in this
             section, immediately after the model it reads: `format-compare.js` calls
             `window.cpRcLanes.filter()` on its very first pass, so the element has to be able to
             set itself up the moment the tag upgrades rather than one parse later. Renders
             nothing; `serve.css` hides the tag. -->
        <cp-rc-lanes></cp-rc-lanes>
      </section>
      """
      .trimIndent()
  }

  /** Focused design handoff view: independent reference, marked diff, and actual Compose output. */
  fun referenceComparisonPage(
    moduleLabel: String,
    preview: ServePreview,
    reference: DesignReference,
    references: List<DesignReference> = listOf(reference),
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    /**
     * The catalog's declared stage surface (`display.surface`), so a dark-first catalog's panels
     * don't fall through to the checkerboard.
     */
    declaredSurface: String? = null,
    /**
     * The catalog's palette as an inline `:root` override, built by [ServeThemeCss] from
     * `tokens.dtcg.json`. Empty keeps the built-in chrome.
     */
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Typography / layout annotations for the reference raster and the rendered frame. Either may
     * be empty; with none, no toggles or payload are emitted.
     */
    referenceAnnotations: List<DesignAnnotation> = emptyList(),
    actualAnnotations: List<DesignAnnotation> = emptyList(),
    /**
     * The parity run's verdict for this (preview, reference) pair from `parity/findings.json`.
     * Empty renders no verdict.
     */
    parityFindings: List<ParityFindingSet> = emptyList(),
    /**
     * Whether the host can project the **derived** layers (typography, theme, layout from the
     * render's semantics tree), i.e. answer `/render/<id>.annotations`. Separate from the
     * producer-authored [actualAnnotations] in `annotations/index.json`.
     */
    derivedAnnotations: Boolean = false,
    /**
     * Whether the catalog published typography over this preview's baked frame
     * ([ServeHost.hasPublishedTypographyFor]) — the other lane behind the Typography layer, and the
     * only one a static bundle has.
     *
     * A static bundle never re-renders, so its layers and PNG are the same frame — exactly the host
     * [annotationsSelectable] is for; gating on [derivedAnnotations] alone would never offer a
     * selectable box. Only Typography rides this lane; Theme and Layout stay gated on
     * [derivedAnnotations].
     */
    publishedTypography: Boolean = false,
    /**
     * The tag index URL when a tag selection would describe the frame on screen; null hides the
     * picker. Built here via [linkQuery] so it keeps the credential however the page was
     * authorized.
     *
     * Null does not mean "no tags": `ServeHost.tagIndexForPreview` is the static index measured
     * over the baked render, so for an override-bearing or pinned frame its bounds would be
     * persisted as a false baseline. Dragged regions come from displayed pixels and stay offered.
     * The URL carries only the session keys.
     */
    tagIndexAvailable: Boolean = false,
    /**
     * Whether clicking a derived semantics box may select it. Separate from [derivedAnnotations]
     * (drawing): on a host that renders per request, `.annotations` and the decoded PNG may be
     * different frames, so recording a box could persist the wrong baseline.
     */
    annotationsSelectable: Boolean = false,
    /**
     * Why the tag picker is absent, when worth saying ("no tag index" vs "overrides mean the index
     * describes another render").
     */
    tagSelectionNote: String? = null,
    /**
     * The catalog's published revisions and this page's pin, so a comparison URL can name a fixed
     * publish of its preview and reference.
     */
    revisions: CatalogRevisions = CatalogRevisions.NONE,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    /** Normalised render-lane query values that reproduce the compared candidate. */
    overrides: Map<String, String> = emptyMap(),
    /** Prefilled parity report for this exact preview/reference comparison. */
    reportIssue: ReportIssue? = null,
    /**
     * What the browser engine needs to evaluate the catalog's committed acceptances against this
     * comparison, or null to omit the band (as for catalogs that have accepted nothing).
     */
    knownDifferences: KnownDifferenceScope? = null,
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * When the issue index was generated (`ParityIssues.generatedAt`, ISO-8601), printed at the
     * foot of an opened issue panel. Null omits the line.
     */
    parityIssuesGeneratedAt: String? = null,
    /**
     * The complete issue index for resolving acceptance lifecycle state. [parityIssues] is the
     * display-filtered list; the lifecycle join must not inherit that filter, since an acceptance
     * may cite an issue whose locators are stale.
     */
    acceptanceIssues: List<ParityIssue> = parityIssues,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
  ): String {
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val overrideQuery =
      overrides.entries
        .sortedBy { it.key }
        .joinToString("&") { (key, value) ->
          "${WebEscaping.urlEncodeSegment(key)}=${WebEscaping.urlEncodeSegment(value)}"
        }
    val linkQuery =
      listOf(linkQuery(token, linkSessionId, basePath, isPublic), overrideQuery)
        .filter { it.isNotEmpty() }
        .joinToString("&")
    val q = querySuffix(linkQuery)
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)
    // Both panels take the pin or neither does; unpinned, both take the page's generation, so the
    // verdict and the pixels describe the same publish ([ServeCacheGeneration]).
    val assetQuery = assetQuery(q, revisions)
    val actual = "$basePath/render/${WebEscaping.urlEncodeSegment(preview.id)}.png$assetQuery"
    val raster = "$basePath/reference/${WebEscaping.urlEncodeSegment(reference.id)}.png$assetQuery"
    // All three panels share one ground: the diff is only meaningful when both sides are composited
    // onto identical pixels.
    val backdrop =
      backdropFor(
        preview,
        isDarkFirstSystem(basePath, sessionId, declaredSurface),
        // Both panels already take this override through `assetQuery`; the stage has to take it too
        // or the pixels and their ground describe different renders.
        uiModeOverride = overrides["uiMode"],
      )
    // The SHAPE of that ground. A round device's stage has to stop at the bezel, or the three
    // panels agree with each other about a watch that is square.
    val stageClip = stageClipFor(preview, overrides)
    val stageAttrs =
      backdrop.color?.let { color ->
        // The theme word drives the CSS; the exact colour rides as a custom property for stages
        // that match neither plate.
        val clipProperty =
          stageClip?.let { "; --cp-stage-clip: ${WebEscaping.htmlEscape(it)}" } ?: ""
        // A marker attribute as well as the property: the gated rules also move the ground from
        // panel to image, and CSS cannot test whether a custom property is set.
        val clipMarker = if (stageClip != null) " data-cp-stage-clip=\"1\"" else ""
        " data-bg-theme=\"${if (backdrop.isDark) "dark" else "light"}\"" +
          clipMarker +
          " style=\"--cp-stage-backdrop: ${WebEscaping.htmlEscape(color.asCssColor())}$clipProperty\""
      } ?: ""
    // One toggle per kind actually present in some panel. The payload is inline so layers are there
    // on first paint.
    val annotated = referenceAnnotations + actualAnnotations
    val annotationControls =
      if (annotated.isEmpty()) ""
      else {
        // Every kind [AnnotationKind.KNOWN] admits needs an entry here, or its boxes are drawn into
        // a layer that can never be revealed.
        val toggles =
          listOf(
              AnnotationKind.LAYOUT to "Layout",
              AnnotationKind.TYPOGRAPHY to "Typography",
              AnnotationKind.THEME to "Theme",
            )
            .filter { (kind, _) -> annotated.any { it.kind == kind } }
            .joinToString("\n") { (kind, label) ->
              "<label class=\"cp-annotation-toggle\"><input type=\"checkbox\" " +
                "data-cp-annotation-kind=\"$kind\"> ${WebEscaping.htmlEscape(label)}</label>"
            }
        """
        <div class="cp-annotation-controls" role="group" aria-label="Annotation layers">
          <span class="cp-compare-control-label">Annotations</span>
          $toggles
        </div>
        <script type="application/json" id="cp-annotations">${
          encodeAnnotationPayload(
            AnnotationPayload(reference = referenceAnnotations, actual = actualAnnotations)
          )
        }</script>
        """
          .trimIndent()
      }
    // The derived layers, mounted over the Actual panel by the viewer's `<cp-inspect-layers>` (see
    // `inspect/host.ts`), as a separate group: the toggles above are producer-authored redlines for
    // both panels, these are what the render's own semantics tree says.
    // Typography rides either lane; Theme and Layout only the semantics one, as in the viewer's
    // Inspect group.
    val derivedLayers = buildList {
      if (derivedAnnotations || publishedTypography) add("typography" to "Typography")
      if (derivedAnnotations) {
        add("theme" to "Theme")
        add("layout" to "Layout")
      }
    }
    val derivedControls =
      if (derivedLayers.isEmpty()) ""
      else {
        val toggles =
          derivedLayers.joinToString("\n") { (kind, label) ->
            "<label class=\"cp-annotation-toggle\"><input type=\"checkbox\" " +
              "class=\"cp-render-inspect\" data-cp-inspect=\"$kind\"> " +
              WebEscaping.htmlEscape(label) +
              "</label>"
          }
        """
        <div class="cp-annotation-controls cp-render-inspect-controls" role="group"
             aria-label="Render semantics layers">
          <span class="cp-compare-control-label">Render semantics</span>
          $toggles
        </div>
        <div class="cp-inspect-legend cp-render-inspect-legend" id="cp-render-inspect-legend"
             role="region" aria-label="Render semantics legend" hidden></div>
        <cp-inspect-layers
          data-cp-host="#cp-compare-actual"
          data-cp-layer="#cp-render-inspect-layer"
          data-cp-legend="#cp-render-inspect-legend"
          data-cp-toggles=".cp-render-inspect"
${if (annotationsSelectable) "          data-cp-selectable=\"1\"\n" else ""}          data-cp-base="${WebEscaping.htmlEscape(basePath)}"></cp-inspect-layers>
        """
          .trimIndent()
      }
    // The element selector. A dragged region needs nothing from the server, so it is always
    // offered; the tag picker only appears where the index describes the shown frame
    // ([tagIndexUrl]).
    // The index is generation-scoped: its bounds become an acceptance baseline, so the lane must be
    // able to refuse an index from another publish.
    val tagIndexUrl =
      if (!tagIndexAvailable) null
      else
        "$basePath/tags/${WebEscaping.urlEncodeSegment(preview.id)}" +
          assetQuery(querySuffix(linkQuery(token, linkSessionId, basePath, isPublic)), revisions)
    val tagAttr = tagIndexUrl?.let { " data-cp-tags=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
    val tagNote =
      tagSelectionNote
        ?.takeIf { it.isNotBlank() }
        ?.let { "<p class=\"cp-selection-note\">${WebEscaping.htmlEscape(it)}</p>" }
        .orEmpty()
    val selectionControls =
      if (reportIssue == null) ""
      else
        """
        <div class="cp-selection-controls" id="cp-element-selection" role="group"
             aria-label="Report a single element"$tagAttr>
          <span class="cp-compare-control-label">Report</span>
          <select class="cp-selection-tag" aria-label="Tagged element" hidden>
            <option value="">the whole render</option>
          </select>
          <button type="button" class="cp-selection-drag">Drag a region…</button>
          <button type="button" class="cp-selection-clear" hidden>Clear</button>
          <p class="cp-selection-state" role="status">Reporting the whole render.</p>
          $tagNote
        </div>
        <cp-element-selection></cp-element-selection>
        """
          .trimIndent()
    // The acceptance band and its payload are both absent for a catalog that has accepted nothing.
    //
    // The band renders empty and `hidden`: the numbers are computed in the browser from the same
    // rasters the diff uses; the server only decides, via the payload's presence, whether the
    // engine runs.
    //
    // Both strings carry their own leading newline at column zero: `trimIndent()` runs after
    // interpolation, so an indented block would shift the page and an empty one would leave a blank
    // line.
    val acceptanceBand =
      if (knownDifferences == null) ""
      else "\n" + """<div class="cp-acceptance" id="cp-acceptance" role="status" hidden></div>"""
    val acceptanceContext = knownDifferences?.let { scope ->
      encodeKnownDifferenceContext(
        KnownDifferenceContext(
          // Published catalog files, not render output: session keys only, no overrides, no
          // `reference=`, and no pin (historical acceptances aren't published).
          documentUrl =
            "$basePath/parity/known-differences.json" +
              querySuffix(linkQuery(token, linkSessionId, basePath, isPublic)),
          artifactBase = "$basePath/parity/known-differences/",
          artifactQuery = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic)),
          // The two panels' own URLs, so the engine decodes the very frames the diff drew rather
          // than re-deriving a pair from the ids.
          referenceUrl = raster,
          candidateUrl = actual,
          scope = scope,
          issues =
            acceptanceIssues
              .map { issue ->
                KnownDifferenceIssue(
                  repository = issue.repository,
                  number = issue.number,
                  state = issue.state,
                )
              }
              .distinctBy { Triple(it.repository, it.number, it.state) },
        )
      )
    }
    val acceptanceScript =
      if (acceptanceContext == null) ""
      else
        "\n" +
          """<script type="application/json" id="cp-known-differences">$acceptanceContext</script>
${scriptTag("known-differences.js")}
<cp-acceptance></cp-acceptance>"""
    val source = WebEscaping.htmlEscape(reference.source.provider)
    val uidEditor =
      if (revisions.pinned != null) ""
      else
        ServeUidReference.spec(listOf(reference), basePath)
          ?.let { spec ->
            val href = spec.url + if (linkQuery.isEmpty()) "" else "&$linkQuery"
            " · <a href=\"${WebEscaping.htmlEscape(href)}\">Open in UI Builder</a>"
          }
          .orEmpty()
    val revision =
      reference.source.revision
        ?.takeIf { it.isNotBlank() }
        ?.let { " · revision ${WebEscaping.htmlEscape(it)}" }
        .orEmpty()
    val referenceChoices = (references + reference).distinctBy { it.id }
    // This page at a given pin (null ⇒ live), keeping its reference; the revision control and
    // sibling-reference picker both build links through it.
    val pageHref: (String?, String) -> String = { pin, referenceId ->
      val query =
        listOfNotNull(
            linkQuery.takeIf { it.isNotEmpty() },
            "reference=${WebEscaping.urlEncodeSegment(referenceId)}",
          )
          .joinToString("&")
      withPin("$basePath/compare/${WebEscaping.urlEncodeSegment(preview.id)}?$query", pin)
    }
    val revisionsBlock = revisionsHtml(revisions) { pin -> pageHref(pin, reference.id) }
    val issueRows = parityIssueRowsHtml(parityIssues, generatedAt = parityIssuesGeneratedAt)
    val referencePicker =
      if (referenceChoices.size <= 1) ""
      else {
        val links =
          referenceChoices.joinToString("\n") { choice ->
            val href = WebEscaping.htmlEscape(pageHref(revisions.pinned, choice.id))
            val current = if (choice.id == reference.id) " aria-current=\"page\"" else ""
            "<a class=\"cp-reference-choice\" href=\"$href\"$current>${WebEscaping.htmlEscape(choice.label)}</a>"
          }
        """
        <nav class="cp-reference-picker" aria-label="Design references">
          <span>Design references</span>
          $links
        </nav>
        """
          .trimIndent()
      }
    val report = reportIssueHtml(reportIssue)
    val parityVerdict = parityVerdictHtml(parityFindings)
    return document(
      changelogHref = changelogHref,
      title = "${reference.label} — design comparison",
      unfurlTitle = "$heading design comparison",
      unfurlDescription = "Reference, diff, and Compose output for ${preview.id}",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/compare$q", heading, "Design comparison"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <div id="cp-reference-compare" data-reference="$raster" data-actual="$actual"$stageAttrs>
          <h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(reference.label)}${compactTrustBadge(trust)}</h1>
          <p class="cp-sub">${WebEscaping.htmlEscape(previewDisplayName(preview))} · ${WebEscaping.htmlEscape(preview.id)}</p>
          $revisionsBlock
          $referencePicker$issueRows
          <div class="cp-reference-meta"><strong>Source:</strong> $source$revision$uidEditor</div>
          <div class="cp-reference-grid">
            <section><h2>Reference</h2><div class="cp-compare-shot" data-cp-annotated="reference"><img src="$raster" alt="Design reference"></div></section>
            <section><h2>Diff</h2><div class="cp-compare-shot"><canvas class="cp-reference-diff" aria-label="Highlighted pixel difference"></canvas></div></section>
            <section><h2>Actual</h2><div class="cp-compare-shot" data-cp-annotated="actual" id="cp-compare-actual" data-preview-id="${WebEscaping.htmlEscape(preview.id)}"><img src="$actual" alt="Actual Compose preview">${
              if (derivedLayers.isNotEmpty())
                "<div class=\"cp-inspect-layer\" id=\"cp-render-inspect-layer\"></div>"
              else ""
            }<div class="cp-selection-layer" id="cp-selection-layer" hidden></div></div></section>
          </div>
          $annotationControls
          $derivedControls
          <p class="cp-reference-result" role="status">comparing…</p>$acceptanceBand$parityVerdict
          $selectionControls$report
          <label class="cp-overlay-control">Overlay <input class="cp-overlay-range" type="range" min="0" max="100" value="50"><span>50%</span></label>
          <div class="cp-reference-overlay"><img src="$raster" alt=""><img src="$actual" alt=""></div>
        </div>
        <!-- `<cp-reference-compare>` owns everything on this page: the diff, the overlay slider and
             the annotation redline. `format-compare.js` is still here for the comparison
             primitives it publishes on `window.ComposePreviewCompare`, and the element reads that
             handle when it scores rather than when it upgrades, so the two tags may be in either
             order. -->
        ${componentScriptTags("compare")}
        ${compareScorerTag()}
        <cp-reference-compare></cp-reference-compare>$acceptanceScript
        """
          .trimIndent(),
    )
  }

  /**
   * The catalog's **Pages** index: one card per published design page, leading with the drawing and
   * stating how many of the sheet's components this catalog implements. Only rendered when at least
   * one page exists.
   */
  fun designPagesIndexPage(
    moduleLabel: String,
    pages: List<DesignPage>,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * The page-scoped "report a catalog issue" for this surface, built via [ServeIssueReport];
     * names the page rather than a preview ([pageReportRowHtml]). Null omits it.
     */
    reportIssue: ReportIssue? = null,
  ): String {
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)
    val cards =
      pages.joinToString("\n") { page ->
        val id = WebEscaping.urlEncodeSegment(page.id)
        // Counted against implementable components only; private components and variant-set
        // containers are excluded. See `DesignPage.coverageGaps`.
        val linked = page.linked.size
        // A sheet that is not a component inventory (e.g. icons) has no meaningful fraction;
        // describe the sheet instead.
        val count =
          if (!page.inventory) "${page.nodes.size} nodes · not a component inventory"
          else "$linked of ${page.coverageTotal} components implemented"
        """
        <a class="cp-page-card" href="$basePath/pages/$id$q">
          <img loading="lazy" alt="" src="$basePath/pages/$id.svg$q">
          <strong>${WebEscaping.htmlEscape(page.name)}</strong>
          <span class="cp-page-count">${WebEscaping.htmlEscape(count)}</span>
        </a>
        """
          .trimIndent()
      }
    return document(
      changelogHref = changelogHref,
      title = "$heading — pages",
      unfurlTitle = "$heading pages",
      unfurlDescription = "Pages of the design file, with each component linked back to its code",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Pages"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Pages${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">Whole pages of the design file, with each component on them linked back
        to the code that implements it.</p>${pageReportRowHtml(reportIssue, "cp-page-links")}
        <div class="cp-page-cards">
        $cards
        </div>
        """
          .trimIndent(),
    )
  }

  /**
   * The **motion browser**: every recorded capture this catalog publishes, on one page.
   *
   * It answers catalog-wide questions a per-preview Motion lane can't: whether the system moves
   * consistently (side by side, the odd one out is obvious), and what has motion at all.
   *
   * Nothing plays until asked: each card opens on its component's still and swaps to the capture on
   * press or **Play all**, so there is no autoplay for `prefers-reduced-motion` to suppress. The
   * control is a button so it works on touch and keyboard.
   *
   * A capture is published on every render of its component (`variants.json`), so the page groups
   * by [componentKey] and keeps one card per distinct capture id. Each card deep-links
   * `?mode=motion&motion=<id>` on its render. Labels come from [MotionCaptureLabels], as in the
   * viewer; a caption shared by all of a component's recordings is printed once under its name.
   */
  fun motionIndexPage(
    moduleLabel: String,
    previews: List<ServePreview>,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    displayTitle: String? = null,
    /** See [designPagesIndexPage]; a rooted site implies its session by the origin. */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * The page-scoped "report a catalog issue" for this surface, built via [ServeIssueReport];
     * names the page rather than a preview ([pageReportRowHtml]). Null omits it.
     */
    reportIssue: ReportIssue? = null,
  ): String {
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val query = linkQuery(token, linkSessionId, basePath, isPublic)
    val q = querySuffix(query)
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)

    // Authoring order where the catalog published one, so the sections read in the order the
    // landing's tabs do rather than alphabetically by preview id.
    val withMotion =
      previews
        .filter { it.motion.isNotEmpty() }
        .sortedWith(compareBy({ it.catalogOrder ?: Int.MAX_VALUE }, { it.id }))

    /** One capture, and the render that publishes it — its still, its label and its deep link. */
    class Take(val owner: ServePreview, val capture: ServeMotion)

    /**
     * One recording of a component: the gesture, with its per-theme takes on one card. [takes] is
     * keyed by theme (`light` / `dark`) for the Theme control to swap in place; a recording with no
     * theme in its id has one unkeyed take.
     */
    class Recording(val lead: Take, val takes: Map<String, Take>)

    /**
     * One component's block on this page: what to call it, and every recording it publishes once.
     */
    class MotionComponent(val lead: ServePreview, val recordings: List<Recording>)

    val leadTheme = if (isDarkFirstSystem(basePath, sessionId)) "dark" else "light"

    /**
     * Which of a component's renders speaks for a capture, supplying the card's still and deep
     * link. Prefer the render named by the capture id, then the render in the capture's theme lane,
     * then authored order (for hand-named captures).
     */
    fun owner(renders: List<ServePreview>, capture: ServeMotion): ServePreview =
      renders.firstOrNull { it.id == capture.id }
        ?: cardTheme(capture.id)?.let { theme ->
          renders.firstOrNull { (it.theme ?: cardTheme(it.id)) == theme }
        }
        ?: renders.first()

    /**
     * The takes of one component folded into recordings, grouped by capture id minus its theme
     * token ([baseKey]). All-or-nothing per group: it folds only when every take names a theme and
     * no two name the same one; otherwise each take stays its own card.
     */
    fun fold(takes: List<Take>): List<Recording> =
      takes
        .groupBy { baseKey(it.capture.id) }
        .values
        .flatMap { group ->
          val themes = group.map { cardTheme(it.capture.id) }
          if (themes.any { it == null } || themes.distinct().size != themes.size)
            group.map { Recording(it, emptyMap()) }
          else {
            val byTheme = group.associateBy { cardTheme(it.capture.id)!! }
            listOf(Recording(byTheme[leadTheme] ?: group.first(), byTheme))
          }
        }

    // The fold. Every render of a component carries the same manifest entries, so the distinct
    // capture ids ARE the component's takes, however many renders republish them.
    val components =
      withMotion
        .groupBy { componentKey(it) }
        .map { (_, renders) ->
          // The component's own card leads: its default state and default props, in authored
          // order, which is the render the grid draws and the one its name should open.
          val ranked =
            renders.sortedWith(
              compareBy(
                { isNonDefaultState(it) },
                { hasNonDefaultProps(it) },
                // …and in the system's own theme lane, so a light-first catalog is not led by its
                // dark renders purely because `dark` sorts before `light`.
                { (it.theme ?: cardTheme(it.id)) != leadTheme },
                { it.catalogOrder ?: Int.MAX_VALUE },
              )
            )
          val captures = LinkedHashMap<String, ServeMotion>()
          ranked.forEach { render -> render.motion.forEach { captures.putIfAbsent(it.id, it) } }
          MotionComponent(ranked.first(), fold(captures.values.map { Take(owner(ranked, it), it) }))
        }
    val captureCount = components.sumOf { it.recordings.size }
    // Only offered where something can actually be swapped. A catalog that records one theme gets
    // no control, rather than a pair of buttons one of which does nothing.
    val themed = components.any { component -> component.recordings.any { it.takes.size > 1 } }

    /** The viewer opened on this recording; see [ServeMotion] and the viewer's `?motion=`. */
    fun viewerHref(preview: ServePreview, capture: ServeMotion): String {
      val parts =
        listOf(query, "mode=motion", "motion=" + WebEscaping.urlEncodeSegment(capture.id)).filter {
          it.isNotEmpty()
        }
      return "$basePath/p/${WebEscaping.urlEncodeSegment(preview.id)}?" + parts.joinToString("&")
    }

    /** The component itself in the viewer, on its Motion lane but on no recording in particular. */
    fun componentHref(preview: ServePreview): String {
      val parts = listOf(query, "mode=motion").filter { it.isNotEmpty() }
      return "$basePath/p/${WebEscaping.urlEncodeSegment(preview.id)}?" + parts.joinToString("&")
    }

    /**
     * One card: one recording, opening on the still of the render that took it. [detailHoisted]
     * means the caption is already printed above ([componentHtml]). The per-theme
     * `data-motion-*-light` / `-dark` attributes let the Theme control move the recording, still,
     * name and link together.
     */
    fun cardHtml(
      component: MotionComponent,
      recording: Recording,
      label: MotionCaptureLabel,
      detailHoisted: Boolean,
    ): String {
      val lead = recording.lead
      val capture = lead.capture
      fun posterOf(take: Take) =
        "$basePath/render/${WebEscaping.urlEncodeSegment(take.owner.id)}.png$q"
      fun srcOf(take: Take) =
        "$basePath/motion/${WebEscaping.urlEncodeSegment(take.capture.id)}" +
          "${take.capture.extension}$q"
      // The component is named above the cards, so the button says which of ITS recordings this is
      // — and, where the same gesture was recorded in both themes, which take is on the stage.
      fun playLabel(theme: String) =
        "Play the ${label.title} recording of ${previewDisplayName(component.lead)}" +
          if (recording.takes.size > 1 && theme.isNotEmpty())
            " (${theme.replaceFirstChar { it.uppercaseChar() }})"
          else ""
      val leadTakeTheme = recording.takes.entries.firstOrNull { it.value === lead }?.key ?: ""
      // The kind distinguishes a scripted gesture (proves the input plumbing) from a self-running
      // animation.
      val kind =
        when (capture.kind) {
          "interaction" -> "Interaction"
          "animation" -> "Animation"
          else -> "Capture"
        }
      // The full caption, printed under the card. Blank for a capture whose annotation declared
      // none — the title is then the kind, and a second line repeating it would say nothing.
      val detail =
        label.detail
          .takeIf { !detailHoisted && it.isNotBlank() && it != label.title }
          ?.let {
            "\n          <span class=\"cp-motion-card-detail\">${WebEscaping.htmlEscape(it)}</span>"
          } ?: ""
      val perTheme =
        recording.takes
          .takeIf { it.size > 1 }
          ?.entries
          ?.joinToString("") { (theme, take) ->
            "\n            data-motion-src-$theme=\"${WebEscaping.htmlEscape(srcOf(take))}\"" +
              "\n            data-motion-poster-$theme=" +
              "\"${WebEscaping.htmlEscape(posterOf(take))}\"" +
              "\n            data-motion-href-$theme=" +
              "\"${WebEscaping.htmlEscape(viewerHref(take.owner, take.capture))}\"" +
              "\n            data-motion-label-$theme=\"${WebEscaping.htmlEscape(playLabel(theme))}\""
          } ?: ""
      val play = WebEscaping.htmlEscape(playLabel(leadTakeTheme))
      return """
        <figure class="cp-motion-card">
          <button type="button" class="cp-motion-card-stage" aria-pressed="false"
            data-motion-src="${WebEscaping.htmlEscape(srcOf(lead))}"
            data-motion-poster="${WebEscaping.htmlEscape(posterOf(lead))}"$perTheme
            title="$play" aria-label="$play">
            <img class="cp-motion-card-img" loading="lazy" alt=""
              src="${WebEscaping.htmlEscape(posterOf(lead))}">
            <span class="cp-motion-card-cue" aria-hidden="true">▶</span>
          </button>
          <figcaption class="cp-motion-card-meta">
          <a class="cp-motion-card-title" href="${WebEscaping.htmlEscape(viewerHref(lead.owner, capture))}">${WebEscaping.htmlEscape(label.title)}</a>
          <span class="cp-motion-card-kind">$kind</span>$detail
          </figcaption>
        </figure>
      """
        .trimIndent()
    }

    // Grouped by the landing's top-level sections; section-less catalogs render one unlabelled run,
    // with component names taking the section heading level.
    val sections = components.groupBy { it.lead.section }
    val sectioned = sections.keys.any { !it.isNullOrBlank() }
    val componentTag = if (sectioned) "h3" else "h2"

    fun componentHtml(component: MotionComponent): String {
      val labels = MotionCaptureLabels.of(component.recordings.map { it.lead.capture })
      // A caption shared by every recording describes the component, so it is printed once above
      // the cards.
      val shared =
        labels
          .map { it.detail }
          .distinct()
          .singleOrNull()
          ?.takeIf { it.isNotBlank() && it != labels.first().title }
      val note =
        shared?.let {
          "\n            <p class=\"cp-motion-component-note\">${WebEscaping.htmlEscape(it)}</p>"
        } ?: ""
      // Only worth saying when there is more than one: "1 recording" above a single card is a count
      // of the thing the reader is already looking at.
      val count =
        component.recordings.size
          .takeIf { it > 1 }
          ?.let {
            "\n              <span class=\"cp-motion-component-count\">$it recordings</span>"
          } ?: ""
      val cards =
        component.recordings
          .mapIndexed { i, recording -> cardHtml(component, recording, labels[i], shared != null) }
          .joinToString("\n")
      // Component on the left, recordings on the right; stacked on narrow screens.
      return """
        <article class="cp-motion-component">
          <div class="cp-motion-component-about">
            <$componentTag class="cp-motion-component-head">
              <a class="cp-motion-component-name" href="${WebEscaping.htmlEscape(componentHref(component.lead))}">${WebEscaping.htmlEscape(previewDisplayName(component.lead))}</a>$count
            </$componentTag>$note
          </div>
          <div class="cp-motion-cards">
$cards
          </div>
        </article>
      """
        .trimIndent()
    }

    val body =
      sections.entries.joinToString("\n") { (section, group) ->
        val blocks = group.joinToString("\n") { componentHtml(it) }
        val head =
          section
            ?.takeIf { it.isNotBlank() }
            ?.let { "<h2 class=\"cp-section-head\">${WebEscaping.htmlEscape(it)}</h2>\n" } ?: ""
        "<section class=\"cp-motion-section\">\n$head<div class=\"cp-motion-components\">\n$blocks\n</div>\n</section>"
      }

    val componentCount = components.size
    val componentWord = if (componentCount == 1) "component" else "components"
    val captureWord = if (captureCount == 1) "recording" else "recordings"
    // One page-wide Theme axis swapping every card between its light and dark take. Server-rendered
    // on the system's lane; the script re-points it at the remembered theme.
    val themeControl =
      if (!themed) ""
      else
        "\n          <span class=\"cp-motion-theme\">" +
          "\n            <span class=\"cp-motion-theme-label\">Theme</span>" +
          "\n            <span class=\"cp-theme\" role=\"group\" aria-label=\"Recording theme\">" +
          "\n              <button type=\"button\" class=\"cp-theme-btn\" data-motion-theme=\"light\"" +
          "\n                aria-pressed=\"${leadTheme == "light"}\">Light</button>" +
          "\n              <button type=\"button\" class=\"cp-theme-btn\" data-motion-theme=\"dark\"" +
          "\n                aria-pressed=\"${leadTheme == "dark"}\">Dark</button>" +
          "\n            </span>" +
          "\n          </span>"
    return document(
      changelogHref = changelogHref,
      title = "$heading — motion",
      unfurlTitle = "$heading motion",
      unfurlDescription =
        "Every recorded interaction and animation this design system publishes, side by side",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Motion"),
      themeCss = themeCss,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Motion${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">Every recorded interaction and animation this catalog publishes, grouped
        by component and set side by side — so a transition that is shaped differently from its
        neighbours is visible without opening each component in turn.
        $captureCount $captureWord across $componentCount $componentWord.</p>${
          pageReportRowHtml(reportIssue, "cp-page-links")
        }
        <div class="cp-motion-toolbar">
          <button type="button" id="cp-motion-all" class="cp-action-chip cp-motion-all"
            aria-pressed="false" aria-controls="cp-motion-index">Play all</button>$themeControl
          <span class="cp-motion-hint">Nothing plays until you ask it to. Press a card to run one
          recording, or open a component to scrub it frame by frame.</span>
        </div>
        <div class="cp-motion-index" id="cp-motion-index">
        $body
        </div>
        <script>$MOTION_INDEX_SCRIPT</script>
        """
          .trimIndent(),
    )
  }

  /**
   * The motion browser's behaviour: swap a card between still and recording, and every card between
   * light and dark takes.
   *
   * Inline because only this page needs it. Swapping `src` is the whole mechanism: an `<img>` can't
   * pause or seek an APNG/GIF (the viewer's canvas player does that), but restarts on each `src`
   * set, and restoring the poster stops decoding.
   *
   * The Theme control re-points each card's `data-motion-*` pair and re-applies its state, and
   * writes the choice to `?theme=`, this catalog's `localStorage` key and `cpPageTheme`, so the
   * grid and viewer agree and reload resumes.
   */
  private const val MOTION_INDEX_SCRIPT =
    """(function(){var stages=[].slice.call(document.querySelectorAll(".cp-motion-card-stage"));if(!stages.length)return;function attr(el,name){return el.getAttribute(name)||"";}function set(b,on){var img=b.querySelector(".cp-motion-card-img");if(!img)return;var src=attr(b,on?"data-motion-src":"data-motion-poster");if(!src)return;b.setAttribute("aria-pressed",on?"true":"false");if(img.getAttribute("src")!==src||on)img.setAttribute("src",src);}stages.forEach(function(b){b.addEventListener("click",function(){set(b,b.getAttribute("aria-pressed")!=="true");sync();});});var all=document.getElementById("cp-motion-all");function playing(){return stages.filter(function(b){return b.getAttribute("aria-pressed")==="true";}).length;}function sync(){if(!all)return;var on=playing()===stages.length;all.setAttribute("aria-pressed",on?"true":"false");all.textContent=playing()?"Stop all":"Play all";}if(all)all.addEventListener("click",function(){var on=playing()!==stages.length;stages.forEach(function(b){set(b,on);});sync();});var themeBtns=[].slice.call(document.querySelectorAll("[data-motion-theme]"));function applyTheme(theme){stages.forEach(function(b){var src=attr(b,"data-motion-src-"+theme);if(!src)return;b.setAttribute("data-motion-src",src);b.setAttribute("data-motion-poster",attr(b,"data-motion-poster-"+theme));var label=attr(b,"data-motion-label-"+theme);if(label){b.setAttribute("title",label);b.setAttribute("aria-label",label);}var href=attr(b,"data-motion-href-"+theme),link=b.parentNode&&b.parentNode.querySelector(".cp-motion-card-title");if(link&&href)link.setAttribute("href",href);set(b,b.getAttribute("aria-pressed")==="true");});themeBtns.forEach(function(t){t.setAttribute("aria-pressed",attr(t,"data-motion-theme")===theme?"true":"false");});}themeBtns.forEach(function(t){t.addEventListener("click",function(){var theme=attr(t,"data-motion-theme");applyTheme(theme);try{var key=document.documentElement.getAttribute("data-cp-theme-key");if(key)sessionStorage.setItem(key,theme);}catch(e){}if(window.cpUrlState)window.cpUrlState.push({theme:theme});if(window.cpPageTheme)window.cpPageTheme.follow(theme);});});if(themeBtns.length){var opening="";try{var fromUrl=new URLSearchParams(location.search).get("theme");var key=document.documentElement.getAttribute("data-cp-theme-key");var remembered=key?sessionStorage.getItem(key):"";opening=fromUrl||remembered||"";}catch(e){}if(opening==="light"||opening==="dark")applyTheme(opening);}})();"""

  /**
   * One **design page**: the sheet as inlined SVG, an outline over every component node, and
   * optionally this catalog's renders standing in for the design's drawing.
   *
   * The SVG is inlined so elements (named by `data-node-id`) can be reached and replaced. That is
   * why [svg] is interpolated unescaped — the only third-party markup on this server.
   * [ServeDesignPageStore] runs it through [SvgSanitizer] at load (allowlisted elements/attributes,
   * no script, no `foreignObject`, no off-document URL) and refuses pages that don't survive.
   *
   * No geometry is recorded: `<cp-design-page>` measures each `[data-node-id]` element (Figma
   * export boxes include effect bleed).
   *
   * `<cp-page-zoom>` (a Vue component in `cli/serve-web`) makes the stage zoomable — double-click
   * drills one level in, ⌘/Ctrl + wheel zooms, drag pans. `.cp-page-canvas` exists so the export,
   * overlays and renders share one transform; the tip and zoom bar sit outside it.
   *
   * [page] is third-party data, so every interpolation is escaped with [WebEscaping.htmlEscape],
   * and the Figma deep link is rebuilt by [ServeFigmaSpec.url] from a validated key + node id.
   */
  fun designPage(
    moduleLabel: String,
    page: DesignPage,
    /** Sanitized export markup, inlined as-is. See the doc comment — this is deliberate. */
    svg: String,
    /** The file key the manifest declared, already validated. Empty ⇒ no design-tool deep links. */
    fileKey: String = "",
    /**
     * Preview ids this session can render. A node mapped to an unpublished preview keeps its
     * outline but gets no render or link.
     */
    renderablePreviewIds: Set<String> = emptySet(),
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    version: String? = null,
    displayTitle: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * The page-scoped "report a catalog issue" for this surface, built via [ServeIssueReport];
     * names the page rather than a preview ([pageReportRowHtml]). Null omits it.
     */
    reportIssue: ReportIssue? = null,
    /**
     * The `compareWith` sibling's render of each node, by node id. Empty without a pairing and on a
     * top-level site ([ServeSites]), where the sibling's `/render/` is unreachable; empty also
     * hides the third source. URLs are built by the caller from validated ids and carry the page's
     * credential.
     */
    parallelRenders: Map<String, String> = emptyMap(),
    /** What that sibling catalog calls itself — the word its buttons read. */
    parallelLabel: String? = null,
    /** What THIS catalog's button reads. Falls back to a neutral "Ours". */
    ownLabel: String? = null,
    /**
     * The shared backplates painted beneath the export, in paint order, already resolved against
     * the catalog's verified asset table. Resolved by the caller because [ServeDesignPageStore] is
     * what verifies each plate; reading `page.background` here would draw whatever the manifest
     * asked. Empty renders the stage unchanged.
     */
    background: List<PageLayerPlacement> = emptyList(),
    /**
     * URL for a verified plate's bytes by asset id; null drops the placement. A function because
     * the href carries the caller's credential and base path (like [parallelRenders]).
     */
    assetHref: (String) -> String? = { null },
  ): String {
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)

    /** The preview this node can be drawn with on this session, or null. */
    fun renderable(node: PageNode): String? =
      node.renderablePreviewId?.takeIf { it in renderablePreviewIds }

    // `data-cp-gap` marks nodes that are genuinely missing components; `data-link="unlinked"` also
    // includes private furniture and variant-set containers, so the gaps filter keys on this.
    val gaps = page.coverageGaps.toSet()

    // A hit area per node with no resting outline — marks appear on hover or when the layer is
    // turned on. Hovering shows the node's detail under the sheet; clicking navigates, so it is a
    // real `<a>` (middle-click, status bar, no-script navigation all work).
    val components = page.nodes.filter(PageNode::isComponent)

    /**
     * A cell we draw and the sibling does not. On the sibling's lane it falls back to the design's
     * drawing, so it is marked explicitly rather than read as a match (as `ServeParallelPairing`
     * states its fallbacks).
     */
    fun unpaired(node: PageNode): Boolean =
      parallelRenders.isNotEmpty() &&
        node.renderablePreviewId?.takeIf { it in renderablePreviewIds } != null &&
        node.nodeId !in parallelRenders

    val outlines =
      components.joinToString("\n") { node ->
        val label =
          if (node.isUnlinked) "${node.name} — no code behind this"
          else "${node.name} — ${node.code.orEmpty()}"
        // A node with code goes to its preview; one without goes to the design file, which is the
        // only link it has.
        val href =
          renderable(node)?.let { "$basePath/p/${WebEscaping.urlEncodeSegment(it)}$q" }
            ?: ServeFigmaSpec.url(fileKey, node.nodeId)
        val tag = if (href == null) "span" else "a"
        val hrefAttr = href?.let { " href=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
        "<$tag class=\"cp-page-node\" " +
          // The anchor a sidebar section row lands on; every node gets one so `<cp-design-page>`
          // can find a URL's node directly.
          "id=\"${nodeAnchorId(node.nodeId)}\" " +
          "data-link=\"${WebEscaping.htmlEscape(node.link.wire)}\"" +
          (if (node in gaps) " data-cp-gap" else "") +
          (if (unpaired(node)) " data-cp-unpaired" else "") +
          // Separate from `data-link`, because it answers a different question: the link says HOW
          // we know this maps, the cell says WHAT is behind it. See `PageNode.cell`.
          (if (node.cell) " data-cp-cell" else "") +
          hrefAttr +
          " " +
          "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
          "title=\"${WebEscaping.htmlEscape(label)}\"><span class=\"cp-visually-hidden\">" +
          "${WebEscaping.htmlEscape(label)}</span></$tag>"
      }

    // Renders sit in an inert `<template>`, adopted when their lane is entered. The page opens on
    // that lane, so `loading="lazy"` bounds daemon work to what's visible. URLs stay server-built
    // and escaped (reading one from the DOM into `img.src` is CodeQL's `js/xss-through-dom`).
    val renders =
      components
        .mapNotNull { node ->
          val previewId = renderable(node) ?: return@mapNotNull null
          "<img class=\"cp-page-render\" alt=\"\" loading=\"lazy\" " +
            "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
            "src=\"$basePath/render/${WebEscaping.urlEncodeSegment(previewId)}.png$q\">"
        }
        .joinToString("\n")

    // The sibling catalog's renders in their own inert `<template>`, adopted only when that source
    // is chosen; keyed by node id like ours.
    val parallelImages =
      components
        .mapNotNull { node ->
          val url = parallelRenders[node.nodeId] ?: return@mapNotNull null
          "<img class=\"cp-page-parallel\" alt=\"\" loading=\"lazy\" " +
            "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
            "src=\"${WebEscaping.htmlEscape(url)}\">"
        }
        .joinToString("\n")
    val hasParallel = parallelImages.isNotEmpty()
    val siblingNameHtml =
      WebEscaping.htmlEscape(parallelLabel?.takeIf { it.isNotBlank() } ?: "Sibling")
    val ourNameHtml = WebEscaping.htmlEscape(ownLabel?.takeIf { it.isNotBlank() } ?: "Ours")
    // Without a sibling there is no third source option at all, rather than a disabled one.
    val parallelTemplate =
      if (!hasParallel) ""
      else "\n                <template data-cp-page-parallel-source>$parallelImages</template>"
    val showParallel =
      if (!hasParallel) ""
      else
        "\n                <label title=\"$siblingNameHtml's own renders of the same design-kit " +
          "cells, under that catalog's theme and knobs\">" +
          "\n                  <input type=\"radio\" name=\"cp-page-lane\" value=\"parallel\" " +
          "data-cp-page-lane>" +
          "\n                  <span>$siblingNameHtml</span></label>"
    val unpairedLegend =
      if (!hasParallel) ""
      else
        "\n            <span data-cp-unpaired><i class=\"cp-page-swatch\" " +
          "style=\"color:#6e7781;border-style:dotted\"></i> not drawn by $siblingNameHtml</span>"
    val diffParallel =
      if (!hasParallel) ""
      else
        "\n                <label title=\"Score what the sheet shows against $siblingNameHtml's " +
          "render of the same cell — hold to light every node at once\">" +
          "\n                  <input type=\"radio\" name=\"cp-page-baseline\" value=\"parallel\" " +
          "data-cp-page-baseline>" +
          "\n                  <span>$siblingNameHtml</span></label>"

    // The way out of the diff lane: one anchor per scoreable node to the viewer's
    // `?mode=spec&specView=diff`. An anchor the script clicks rather than a URL it assigns to
    // `location`, avoiding the `js/xss-through-dom` taint path.
    val diffLinks =
      components
        .mapNotNull { node ->
          val previewId = renderable(node) ?: return@mapNotNull null
          val sep = if (q.isEmpty()) "?" else "&"
          "<a class=\"cp-page-diff-link\" tabindex=\"-1\" aria-hidden=\"true\" " +
            "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\" " +
            "href=\"$basePath/p/${WebEscaping.urlEncodeSegment(previewId)}$q${sep}mode=spec&amp;specView=diff\"></a>"
        }
        .joinToString("\n")

    // The audit list, also the source the selection strip clones from, so rows are links wherever
    // possible: to the preview if there is code, else to the design file (built from the node id,
    // since `ref` is optional).
    val rows =
      components.joinToString("\n") { node ->
        val previewId = renderable(node)
        val href =
          previewId?.let { "$basePath/p/${WebEscaping.urlEncodeSegment(it)}$q" }
            ?: ServeFigmaSpec.url(fileKey, node.nodeId)
        val tag = if (href == null) "div" else "a"
        val hrefAttr = href?.let { " href=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
        val code = node.code
        val detail = if (code != null) WebEscaping.htmlEscape(code) else "no code behind this"
        "<$tag class=\"cp-page-row\" data-link=\"${WebEscaping.htmlEscape(node.link.wire)}\"" +
          (if (node in gaps) " data-cp-gap" else "") +
          (if (unpaired(node)) " data-cp-unpaired" else "") +
          (if (node.cell) " data-cp-cell" else "") +
          " " +
          "data-cp-node=\"${WebEscaping.htmlEscape(node.nodeId)}\"$hrefAttr>" +
          "<span class=\"cp-page-dot\" aria-hidden=\"true\"></span>" +
          "<span class=\"cp-page-row-name\">${WebEscaping.htmlEscape(node.name)}</span>" +
          "<span class=\"cp-page-row-code\">$detail</span></$tag>"
      }

    val linked = page.linked.size
    // Counted against implementable components only; private components and variant-set containers
    // are excluded. See `DesignPage.coverageGaps`.
    val total = page.coverageTotal
    // See the pages index: a non-inventory sheet says what it is rather than scoring itself.
    val coverageText =
      if (!page.inventory) "${page.nodes.size} nodes · not a component inventory"
      else "$linked of $total components implemented"
    val figmaLink =
      ServeFigmaSpec.url(fileKey, page.nodeId)
        ?.let {
          " · <a href=\"${WebEscaping.htmlEscape(it)}\" rel=\"noreferrer noopener\">Open in Figma</a>"
        }
        .orEmpty()
    // The stage's aspect ratio comes from the export's viewBox. Locale.ROOT, since a comma-decimal
    // locale would emit invalid CSS (`aspect-ratio:1,1843`).
    val aspect = String.format(java.util.Locale.ROOT, "%.4f", page.frame.width / page.frame.height)

    // The scene beneath the sheet: each placement's box becomes a percentage of the stage, which
    // survives column width and zoom. Placements without a URL (failed verification) are dropped.
    val drawablePlates = background.filter { it.isWellFormed && assetHref(it.asset) != null }
    val plates =
      drawablePlates.joinToString("") { layer ->
        val href = assetHref(layer.asset).orEmpty()
        fun pct(value: Double, of: Double) =
          String.format(java.util.Locale.ROOT, "%.4f%%", (value / of) * 100.0)
        val style = buildString {
          append("left:").append(pct(layer.x, page.frame.width)).append(';')
          append("top:").append(pct(layer.y, page.frame.height)).append(';')
          append("width:").append(pct(layer.width, page.frame.width)).append(';')
          append("height:").append(pct(layer.height, page.frame.height)).append(';')
          if (layer.opacity != 1.0) {
            append("opacity:")
              .append(String.format(java.util.Locale.ROOT, "%.3f", layer.opacity))
              .append(';')
          }
          if (layer.radius > 0.0) {
            // In page units like every other dimension here, so a clipped plate keeps its corner
            // through a resize instead of drifting against the shape drawn over it.
            append("border-radius:").append(pct(layer.radius, page.frame.width)).append(';')
          }
          if (layer.clip) append("overflow:hidden;")
        }
        // `alt=""` and `aria-hidden`: backplates are scenery. `loading=eager` so the drawing never
        // paints against nothing first.
        """<img class="cp-page-plate" src="${WebEscaping.htmlEscape(href)}" alt="" aria-hidden="true" """ +
          """loading="eager" decoding="async" data-fit="${WebEscaping.htmlEscape(layer.fit.lowercase())}" """ +
          """data-blend="${layer.blend.wire}" style="${WebEscaping.htmlEscape(style)}">"""
      }

    // Blend modes reach the stylesheet as data attributes, never inline style, so only compositing
    // the stylesheet enumerates can happen. `source-over` is the default and omitted.
    val sceneAttrs = buildString {
      if (drawablePlates.isNotEmpty()) append(" data-has-plates")
      if (page.designBlend != PageBlendMode.SOURCE_OVER) {
        append(" data-design-blend=\"").append(page.designBlend.wire).append('"')
      }
      if (page.renderBlend != PageBlendMode.SOURCE_OVER) {
        append(" data-render-blend=\"").append(page.renderBlend.wire).append('"')
      }
    }

    return document(
      changelogHref = changelogHref,
      title = "${page.name} — page",
      unfurlTitle = "$heading — ${page.name}",
      unfurlDescription =
        if (!page.inventory) "${page.nodes.size} nodes on this page; not a component inventory"
        else "$linked of $total components on this page are implemented",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/pages$q", heading, page.name),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      // The stage takes the viewport width rather than the reading column, since sheets are
      // thousands of pixels wide.
      wide = true,
      body =
        """
        <div id="cp-design-page">
          <h1 class="cp-head cp-catalog-head">${WebEscaping.htmlEscape(page.name)}${compactTrustBadge(trust)}</h1>
          <p class="cp-sub">${WebEscaping.htmlEscape(coverageText)}$figmaLink</p>${
          pageReportRowHtml(reportIssue, "cp-page-links")
        }
          <div class="cp-page-toolbar">
          <details class="cp-page-options">
            <summary>View options</summary>
          <div class="cp-page-controls">
            <div class="cp-page-group">
              <span class="cp-page-group-label" id="cp-page-show-label">Show</span>
              <div class="cp-page-lane" role="radiogroup" aria-labelledby="cp-page-show-label">
                <label title="This catalog's own renders, standing in the design's slots">
                  <input type="radio" name="cp-page-lane" value="code" data-cp-page-lane checked>
                  <span>$ourNameHtml</span></label>$showParallel
                <label title="The design file's own drawing of this sheet">
                  <input type="radio" name="cp-page-lane" value="design" data-cp-page-lane>
                  <span>Design</span></label>
              </div>
            </div>
            <div class="cp-page-group">
              <span class="cp-page-group-label" id="cp-page-diff-label">Diff against</span>
              <div class="cp-page-lane" role="radiogroup" aria-labelledby="cp-page-diff-label">
                <label><input type="radio" name="cp-page-baseline" value="off" data-cp-page-baseline checked>
                  <span>Off</span></label>
                <label hidden title="Score what the sheet shows against this catalog's own renders — hold to light every node at once">
                  <input type="radio" name="cp-page-baseline" value="code" data-cp-page-baseline>
                  <span>$ourNameHtml</span></label>$diffParallel
                <label title="Score what the sheet shows against the design's own drawing — hold to light every node at once">
                  <input type="radio" name="cp-page-baseline" value="design" data-cp-page-baseline>
                  <span>Design</span></label>
              </div>
            </div>
            <div class="cp-page-group">
              <span class="cp-page-group-label">Marks</span>
              <label class="cp-page-opt"><input type="checkbox" data-cp-page-outlines> Outlines</label>
              <label class="cp-page-opt"><input type="checkbox" data-cp-page-unlinked> Gaps only</label>
            </div>
          </div>
          </details>
          <cp-page-zoom hidden></cp-page-zoom>
          </div>
          <p class="cp-page-touch-hint">Swipe across to explore the sheet · + to zoom</p>
          <p class="cp-page-hint">Double-click a section to zoom · ⌘/Ctrl-scroll · drag to pan
            · + / &#8722; / 0 by keyboard · Esc resets</p>
          <div class="cp-page-legend" hidden>
            <span data-link="code-connect"><i class="cp-page-swatch" style="color:#2da44e"></i> Code Connect</span>
            <span data-link="manifest"><i class="cp-page-swatch" style="color:#0969da"></i> design-map</span>
            <span data-link="convention"><i class="cp-page-swatch" style="color:#bf8700"></i> name match</span>
            <span data-cp-cell><i class="cp-page-swatch" style="color:#8250df"></i> override variant</span>
            <span data-link="unlinked"><i class="cp-page-swatch" style="color:#cf222e;border-style:dashed"></i> not implemented</span>$unpairedLegend
          </div>
          <div class="cp-page-layout">
            <div class="cp-page-scroll" tabindex="0" role="region" aria-label="Design sheet — scroll to explore">
            <div class="cp-page-stage" style="--cp-page-aspect:$aspect">
              <div class="cp-page-canvas" data-cp-page-canvas$sceneAttrs>
                $plates$svg
                <template data-cp-page-render-source>$renders</template>$parallelTemplate
                <template data-cp-page-diff-links>$diffLinks</template>
                $outlines
              </div>
              <div class="cp-page-tip" data-cp-page-tip hidden aria-live="polite"></div>
            </div>
            </div>
            <details class="cp-page-nodes">
              <summary>$linked of $total components implemented</summary>
              <div class="cp-page-list">
              $rows
              </div>
            </details>
          </div>
        </div>
        <!-- The sheet's overlays, lanes and per-node scoring, alongside the zoom
             (`<cp-page-zoom>`) — both in the Vue bundle. `<cp-design-page>` reads
             `window.ComposePreviewCompare` when the diff lane is entered rather than when it
             upgrades, so it does not depend on following the script below. -->
        ${componentScriptTags("design")}
        ${compareScorerTag()}
        <cp-design-page></cp-design-page>
        """
          .trimIndent(),
    )
  }

  /**
   * The catalog's **Design parity** view: recent movement on both sides of the code ↔ design pair,
   * how far apart they are, and what isn't mapped.
   *
   * 1. **Where we stand**: coverage (computed live), open Figma comments, and how recently each
   *    side moved.
   * 2. **Activity and issues**: the merged feed plus components whose two sides moved unevenly —
   *    likely drift — each linking to its reference-vs-render comparison. The full inventory is a
   *    collapsed table.
   *
   * Everything textual in [dashboard] (commit subjects, Figma comments) is third-party, so every
   * interpolation is escaped with [WebEscaping.htmlEscape], and outbound hrefs were rebuilt from
   * validated parts by [ServeParityActivityStore].
   */
  fun parityPage(
    moduleLabel: String,
    dashboard: ServeParityDashboard.Dashboard,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    trust: String? = null,
    themeCss: String = "",
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    displayTitle: String? = null,
    /** Whether a preview carries a design reference — decides "compare" vs "open" on a link. */
    hasReferenceFor: (String) -> Boolean = { false },
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * When the issue index was generated (`ParityIssues.generatedAt`, ISO-8601), printed at the
     * foot of an opened issue panel. Null omits the line.
     */
    parityIssuesGeneratedAt: String? = null,
    /**
     * The complete issue index for acceptance lifecycle state, as [referenceComparisonPage] takes
     * it. [parityIssues] is scoped to this system by [issuesForSystem]; the lifecycle join must not
     * be, since an issue filed against a sibling system is still evidence of closure.
     */
    acceptanceIssues: List<ParityIssue> = parityIssues,
    /**
     * The catalog inventory the acceptance walk resolves targets against; null when the catalog
     * publishes no known-difference document, omitting the panel and engine. Identity comes from
     * the handler and URLs are built here through the one query builder ([KnownDifferenceScope]).
     */
    acceptanceAudit: List<KnownDifferenceCatalogPreview>? = null,
    /**
     * The design tool label ("Figma", …) for the whole-catalog compare link; null keeps neutral
     * wording. See [designToolLabel].
     */
    designToolLabel: String? = null,
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * The delivery-branch commit this dashboard was assembled from, scoping each row's
     * render/reference pair for the in-browser acceptance engine ([ServeCacheGeneration]), so a
     * score is about one publish.
     */
    generation: String? = null,
  ): String {
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    // The frame query: page query plus this dashboard's publish. Both halves of every scored pair
    // take it.
    val assetQ = ServeCacheGeneration.scope(q, generation)
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)
    val coverage = dashboard.coverage

    /**
     * The best link for a preview: its reference-vs-render comparison when mapped, else the viewer.
     * The caller has already filtered to served preview ids.
     */
    fun previewHref(previewId: String): String {
      val seg = WebEscaping.urlEncodeSegment(previewId)
      return if (hasReferenceFor(previewId)) "$basePath/compare/$seg$q" else "$basePath/p/$seg$q"
    }

    fun previewLink(previewId: String, label: String): String =
      "<a href=\"${esc(previewHref(previewId))}\">${esc(label)}</a>"

    fun outboundLink(entry: ServeParityDashboard.FeedEntry): String {
      val href = entry.href ?: return ""
      val label = entry.hrefLabel ?: "open"
      return "<a class=\"cp-parity-out\" href=\"${esc(href)}\" rel=\"noopener\">${esc(label)} ↗</a>"
    }

    val laneLabel =
      mapOf(
        ServeParityDashboard.Lane.CODE to "code",
        ServeParityDashboard.Lane.FIGMA_VERSION to "figma",
        ServeParityDashboard.Lane.FIGMA_COMMENT to "comment",
      )

    val lastCode = dashboard.feed.firstOrNull { it.lane == ServeParityDashboard.Lane.CODE }?.at
    val lastDesign = dashboard.feed.firstOrNull { it.lane != ServeParityDashboard.Lane.CODE }?.at
    val stats = buildList {
      add("mapped" to "${coverage.mapped}/${coverage.components}")
      if (dashboard.hasActivity) {
        add("open comments" to dashboard.openComments.toString())
        add("last code change" to (lastCode?.let(::prettyDate) ?: "—"))
        add("last design change" to (lastDesign?.let(::prettyDate) ?: "—"))
      }
      if (dashboard.gaps.isNotEmpty()) add("declared gaps" to dashboard.gaps.size.toString())
    }
      .joinToString("\n") { (key, value) ->
        "<div class=\"cp-stat\"><div class=\"cp-stat-key\">${esc(key)}</div>" +
          "<div class=\"cp-stat-val\">${esc(value)}</div></div>"
      }

    // The coverage meter is a plain bar rather than a chart: one number, and the number is already
    // written beside it. `aria-*` carries the same value for a screen reader.
    val coverageMeter =
      """
      <div class="cp-parity-meter" role="img"
        aria-label="${esc("${coverage.percent}% of components carry a design reference")}">
        <div class="cp-parity-meter-fill" style="width: ${coverage.percent}%"></div>
      </div>
      """
        .trimIndent()

    val driftRows =
      dashboard.components
        .filter { it.correlation != ServeParityDashboard.Correlation.BOTH }
        .take(20)
    val driftBand =
      if (driftRows.isEmpty()) ""
      else {
        val rows =
          driftRows.joinToString("\n") { component ->
            val oneSided = component.correlation == ServeParityDashboard.Correlation.CODE_ONLY
            val badgeClass = if (oneSided) "cp-parity-lane--code" else "cp-parity-lane--figma"
            val badge = if (oneSided) "code only" else "design only"
            val why =
              if (oneSided) "the render moved; its reference did not"
              else "the design moved; the code did not"
            val name =
              component.previewId?.let { previewLink(it, component.name) } ?: esc(component.name)
            "<tr><td>$name</td>" +
              "<td><span class=\"cp-parity-lane $badgeClass\">${esc(badge)}</span></td>" +
              "<td class=\"cp-muted\">${esc(why)}</td>" +
              "<td class=\"cp-muted\">${esc(prettyDate(component.lastAt))}</td></tr>"
          }
        """
        <h3 class="cp-parity-sub">Out-of-sync activity</h3>
        <p class="cp-muted">Components that moved on one side only inside this window — where the
          render and its reference are most likely to have drifted apart.</p>
        <div class="cp-status-scroll">
          <table class="cp-table">
            <thead><tr><th>Component</th><th>Moved</th><th>Why it's here</th><th>Last change</th></tr></thead>
            <tbody>
            $rows
            </tbody>
          </table>
        </div>
        """
          .trimIndent()
      }

    val feedBand =
      if (dashboard.feed.isEmpty()) {
        """
          <h2 class="cp-status-sec">Activity</h2>
        <p class="cp-muted">This catalog publishes no activity feed yet. A producer adds one by
          emitting <code>parity/activity.json</code> beside its catalog — see the
          <a href="https://github.com/$SOURCE_REPO/blob/main/docs/public-preview-server.md">server
          docs</a>. Coverage above is computed live and needs nothing published.</p>
        """
          .trimIndent()
      } else {
        val items =
          dashboard.feed.joinToString("\n") { entry ->
            val lane = laneLabel[entry.lane].orEmpty()
            val laneClass =
              if (entry.lane == ServeParityDashboard.Lane.CODE) "cp-parity-lane--code"
              else "cp-parity-lane--figma"
            val resolved = if (entry.resolved) " cp-parity-entry--resolved" else ""
            val who =
              entry.author?.let { "<span class=\"cp-parity-who\">${esc(it)}</span>" }.orEmpty()
            val detail =
              entry.detail?.let { "<span class=\"cp-parity-detail\">${esc(it)}</span>" }.orEmpty()
            val resolvedBadge =
              if (entry.resolved) "<span class=\"cp-parity-detail\">resolved</span>" else ""
            // Inbound links are what make this a parity feed rather than a changelog: every row
            // that names previews this session serves offers a jump to their comparison.
            val targets =
              entry.previewIds
                .take(6)
                .mapIndexed { index, previewId ->
                  previewLink(previewId, entry.components.getOrNull(index) ?: previewId)
                }
                .joinToString(" · ")
            val targetsHtml =
              if (targets.isEmpty()) "" else "<div class=\"cp-parity-targets\">$targets</div>"
            val componentsHtml =
              if (entry.previewIds.isNotEmpty() || entry.components.isEmpty()) ""
              else
                "<div class=\"cp-parity-targets cp-muted\">" +
                  esc(entry.components.take(6).joinToString(" · ")) +
                  "</div>"
            """
            <li class="cp-parity-entry$resolved" data-lane="${esc(lane)}">
              <div class="cp-parity-when">${esc(prettyDate(entry.at))}</div>
              <div class="cp-parity-body">
                <div class="cp-parity-head">
                  <span class="cp-parity-lane $laneClass">${esc(lane)}</span>
                  <span class="cp-parity-title">${esc(entry.title)}</span>
                </div>
                <div class="cp-parity-meta">$who$detail$resolvedBadge${outboundLink(entry)}</div>
                $targetsHtml$componentsHtml
              </div>
            </li>
            """
              .trimIndent()
          }
        val filters =
          listOf("all" to "All", "code" to "Code", "figma" to "Figma", "comment" to "Comments")
            .joinToString("\n") { (value, label) ->
              val current = if (value == "all") " aria-current=\"page\"" else ""
              "<button type=\"button\" class=\"cp-state-btn\" data-parity-lane=\"$value\"$current>" +
                "${esc(label)}</button>"
            }
        """
        <!-- Behind a disclosure, closed. The feed is a list of commits with dates — a CHANGELOG,
             which this catalog already publishes with an RSS feed — and it was the tallest thing
             on a page whose job is to point at components. Kept rather than dropped because it is
             the one changelog joined to the design file's own history, and folded because a
             reader who wants that asks for it. See `docs/design/COMPARE_NAVIGATION.md`, §3.4. -->
        <details class="cp-parity-activity cp-disclosure">
          <summary>
            <span class="cp-parity-comparisons-title">Activity</span>
            <span class="cp-disclosure-hint">How the code and the design file have moved</span>
          </summary>
          <div class="cp-disclosure-body">
        <div class="cp-states" role="group" aria-label="Filter activity by lane">
        $filters
        </div>
        <ul class="cp-parity-feed" id="cp-parity-feed">
        $items
        </ul>
        <p class="cp-muted" id="cp-parity-feed-empty" hidden>No activity in this lane.</p>
          </div>
        </details>
        <!-- Wires the lane buttons above to the feed. Renders nothing, and the feed is fully
             readable without it; `serve.css` hides the tag. -->
        <cp-parity-lanes></cp-parity-lanes>
        """
          .trimIndent()
      }

    val unmappedBand =
      if (coverage.unmapped.isEmpty() && dashboard.gaps.isEmpty()) {
        if (coverage.components == 0) ""
        else
          """
          <p class="cp-muted">Every component in this catalog carries a design reference.</p>
          """
            .trimIndent()
      } else {
        val unmappedList =
          if (coverage.unmapped.isEmpty()) ""
          else {
            val chips =
              coverage.unmapped.joinToString("\n") { component ->
                val seg = WebEscaping.urlEncodeSegment(component.previewId)
                "<li><a class=\"cp-state-btn\" href=\"$basePath/p/$seg$q\">" +
                  "${esc(component.name)}</a></li>"
              }
            val overflow =
              if (coverage.unmappedOverflow <= 0) ""
              else "<p class=\"cp-muted\">…and ${coverage.unmappedOverflow} more.</p>"
            """
            <h3 class="cp-parity-sub">No design reference (${coverage.unmappedCount})</h3>
            <p class="cp-muted">These render, but nothing in the design file is mapped to them — so
              nothing can score them against a spec.</p>
            <ul class="cp-parity-chips">
            $chips
            </ul>
            $overflow
            """
              .trimIndent()
          }
        val gapRows =
          if (dashboard.gaps.isEmpty()) ""
          else {
            val kindLabel =
              mapOf(
                MappingGap.Kind.DANGLING_MAPPING to "mapping points at a missing preview",
                MappingGap.Kind.UNRENDERED_REFERENCE to "reference could not be published",
                MappingGap.Kind.UNMAPPED_DESIGN_NODE to "design node with no code",
              )
            val rows =
              dashboard.gaps.joinToString("\n") { gap ->
                val subject = gap.component ?: gap.previewId ?: gap.code ?: gap.ref ?: "—"
                "<tr><td class=\"cp-muted\">${esc(kindLabel[gap.kind] ?: gap.kind)}</td>" +
                  "<td><code>${esc(subject)}</code></td>" +
                  "<td>${esc(gap.detail)}</td></tr>"
              }
            """
            <h3 class="cp-parity-sub">Declared by the producer (${dashboard.gaps.size})</h3>
            <p class="cp-muted">Gaps only the publish job can see — it has the design file and the
              checkout; this server has neither.</p>
            <div class="cp-status-scroll">
              <table class="cp-table">
                <thead><tr><th>Kind</th><th>Subject</th><th>Detail</th></tr></thead>
                <tbody>
                $rows
                </tbody>
              </table>
            </div>
            """
              .trimIndent()
          }
        """
        $unmappedList
        $gapRows
        """
          .trimIndent()
      }

    val githubIssueBand =
      if (parityIssues.isEmpty()) ""
      else {
        val open = parityIssues.filter { it.state == "open" }
        val closed = parityIssues.filter { it.state == "closed" }
        val groups = open.groupBy { it.component ?: "Unscoped" }
        // The component's name is the disclosure's label, so the band is one scannable line per
        // component.
        val summary =
          groups.entries.joinToString("\n") { (component, rows) ->
            "<section class=\"cp-parity-issue-group\">" +
              "${parityIssueRowsHtml(rows, generatedAt = parityIssuesGeneratedAt, label = component)}" +
              "</section>"
          }
        // Counts components, not rows: an umbrella issue contributes one row per component it
        // names.
        val openBand =
          "<h2 class=\"cp-status-sec\">Components with open issues (${groups.size})</h2>" +
            if (open.isEmpty()) "<p class=\"cp-muted\">No open issues.</p>" else summary
        // The closed band is flat with no component names, so collapse an umbrella issue's
        // duplicate rows.
        val closedIssues = closed.distinctBy { it.repository to it.number }
        val closedBand =
          if (closedIssues.isEmpty()) ""
          else
            "<h2 class=\"cp-status-sec\">Closed issues (${closedIssues.size})</h2>" +
              parityIssueRowsHtml(
                closedIssues,
                generatedAt = parityIssuesGeneratedAt,
                // No label: the `<h2>` above it already says "Closed issues", and everything in
                // here is closed, so the summary is the numbers themselves.
                label = "",
              )
        openBand + closedBand
      }
    val issueBand =
      if (
        parityIssues.isEmpty() &&
          driftBand.isEmpty() &&
          coverage.unmapped.isEmpty() &&
          dashboard.gaps.isEmpty()
      ) {
        """
        <h2 class="cp-status-sec">Issues</h2>
        <p class="cp-muted">No mapping gaps or one-sided changes were detected.</p>
        """
          .trimIndent()
      } else {
        """
        <h2 class="cp-status-sec">Issues</h2>
        $githubIssueBand
        $driftBand
        $unmappedBand
        """
          .trimIndent()
      }

    // The "Visual differences" band belongs entirely to `<cp-parity-scores>`, which scores every
    // published pair and renders the result; with no script, nothing is shown rather than a
    // perpetual "Checking…".
    val visualIssues =
      if (dashboard.comparisons.none { it.referenceId != null }) ""
      else "<cp-parity-scores></cp-parity-scores>"

    val comparisonBand =
      if (dashboard.comparisons.isEmpty()) ""
      else {
        val rows =
          dashboard.comparisons.joinToString("\n") { component ->
            val render = previewLink(component.previewId, "Open render")
            val design =
              if (component.hasReference) "<span class=\"cp-ok\">Mapped</span>"
              else "<span class=\"cp-parity-missing\">Missing</span>"
            val review =
              if (component.hasReference) previewLink(component.previewId, "Compare") else "—"
            val scoring =
              component.referenceId
                ?.let { referenceId ->
                  val actualUrl =
                    "$basePath/render/${WebEscaping.urlEncodeSegment(component.previewId)}.png$assetQ"
                  val referenceUrl =
                    "$basePath/reference/${WebEscaping.urlEncodeSegment(referenceId)}.png$assetQ"
                  " data-parity-comparison data-reference=\"${esc(referenceUrl)}\"" +
                    " data-actual=\"${esc(actualUrl)}\" data-name=\"${esc(component.name)}\"" +
                    " data-review=\"${esc(previewHref(component.previewId))}\""
                }
                .orEmpty()
            val score =
              if (component.referenceId != null)
                "<span class=\"cp-parity-score cp-muted\">Checking…</span>"
              else "—"
            // The component name links to the wall filtered by `?component=`, showing every variant
            // side by side.
            val scopedCompare =
              if (component.componentId.isEmpty()) esc(component.name)
              else {
                val scopedQuery =
                  listOf(
                      "format=reference",
                      "component=${WebEscaping.urlEncodeSegment(component.componentId)}",
                      linkQuery(token, linkSessionId, basePath, isPublic),
                    )
                    .filter { it.isNotEmpty() }
                    .joinToString("&")
                "<a href=\"$basePath/compare?$scopedQuery\" " +
                  "title=\"Compare every ${esc(component.name)} variant against the design\">" +
                  "${esc(component.name)}</a>"
              }
            "<tr$scoring><td>$scopedCompare</td><td>$render</td><td>$design</td>" +
              "<td>$score</td><td>$review</td></tr>"
          }
        """
        <details class="cp-parity-comparisons cp-disclosure" open>
          <summary>
            <span class="cp-parity-comparisons-title">All comparisons (${dashboard.comparisons.size})</span>
            <span class="cp-disclosure-hint">Open a component to compare every variant of it</span>
          </summary>
          <div class="cp-disclosure-body cp-status-scroll">
            <table class="cp-table">
              <thead><tr><th>Component</th><th>Code</th><th>Design reference</th><th>Structural match</th><th>Review</th></tr></thead>
              <tbody>
              $rows
              </tbody>
            </table>
          </div>
        </details>
        """
          .trimIndent()
      }

    // A way back to the side-by-side table of all mapped components (the comparison page's
    // `reference` format), offered only when something is mapped.
    val compareAllLink =
      if (coverage.mapped == 0) ""
      else {
        val query =
          listOf("format=reference", linkQuery(token, linkSessionId, basePath, isPublic))
            .filter { it.isNotEmpty() }
            .joinToString("&")
        val against = designToolLabel?.let(::esc) ?: "the design references"
        // The same assist chip as the catalog landing's actions.
        "\n        <div class=\"cp-catalog-actions\">" +
          "<a class=\"cp-action-chip\" href=\"$basePath/compare?$query\">" +
          "compare every mapped component against $against</a></div>"
      }

    // The catalog-wide acceptance audit; band and payload are both absent when nothing has been
    // accepted. The band renders empty and `hidden` because the verdicts are computed in the
    // browser.
    val acceptanceAuditBand =
      if (acceptanceAudit == null) ""
      else {
        val context =
          KnownDifferenceAuditContext(
            documentUrl = "$basePath/parity/known-differences.json$q",
            artifactBase = "$basePath/parity/known-differences/",
            artifactQuery = q,
            previews = acceptanceAudit,
            issues =
              acceptanceIssues
                .map { KnownDifferenceIssue(it.repository, it.number, it.state) }
                .distinctBy { Triple(it.repository, it.number, it.state) },
          )
        "\n" +
          """<div class="cp-acceptance-audit" id="cp-acceptance-audit" role="status" hidden></div>
<script type="application/json" id="cp-known-difference-audit">${
            encodeKnownDifferenceAuditContext(context)
          }</script>
${scriptTag("known-differences.js")}
<cp-acceptance-audit></cp-acceptance-audit>"""
      }

    // `format-compare.js` holds the scorer `<cp-parity-scores>` calls
    // (`window.ComposePreviewCompare`), so it must load before the components bundle upgrades the
    // tag.
    val parityScripts = buildString {
      if (dashboard.comparisons.any { it.referenceId != null }) append(compareScorerTag())
      if (dashboard.feed.isNotEmpty() || dashboard.comparisons.any { it.referenceId != null })
        append(componentScriptTags("parity"))
    }

    // Provenance for the page itself: this is snapshotted data, and saying so is the difference
    // between "nothing changed in Figma" and "we last looked a week ago".
    val sources = buildList {
      dashboard.codeRepo?.let { repo ->
        val ref = dashboard.codeRef?.let { " @ ${esc(it)}" }.orEmpty()
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">code</span> " +
            "<a href=\"${esc("https://github.com/$repo")}\">$GITHUB_ICON ${esc(repo)}</a>$ref</span>"
        )
      }
      dashboard.figmaFileHref?.let { href ->
        val name = dashboard.figmaFileName ?: "Figma file"
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">design</span> " +
            "<a href=\"${esc(href)}\" rel=\"noopener\">${esc(name)} ↗</a></span>"
        )
      }
      dashboard.generatedAt?.let {
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">snapshotted</span> " +
            "${esc(prettyDate(it))}</span>"
        )
      }
      dashboard.windowDays?.let {
        add(
          "<span class=\"cp-prov-item\"><span class=\"cp-prov-key\">window</span> " +
            "last $it days</span>"
        )
      }
    }
    val sourcesStrip =
      if (sources.isEmpty()) ""
      else
        """
        <details class="cp-prov cp-disclosure">
          <summary>
            <span class="cp-prov-title">Feed details</span>
            <span class="cp-disclosure-hint">Where this activity was read from, and when</span>
          </summary>
          <div class="cp-prov-body" aria-label="Activity provenance">
            ${sources.joinToString("\n            ")}
          </div>
        </details>
        """
          .trimIndent()

    return document(
      changelogHref = changelogHref,
      title = "Design parity — $heading — compose-preview",
      unfurlTitle = "$heading — design parity",
      unfurlDescription =
        "${coverage.mapped} of ${coverage.components} components in $heading are mapped to a design reference.",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb = crumbHtml("$basePath/$q", heading, "Design parity"),
      themeCss = themeCss,
      // The bar names the catalog you are in, from the same heading the page shows.
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Design parity${compactTrustBadge(trust)}</h1>
        <p class="cp-sub">How this catalog's code and its design file have moved, and how far apart
          they are.</p>$compareAllLink
        $sourcesStrip
        <div class="cp-status-grid">
        $stats
        </div>
        $coverageMeter
        <p class="cp-muted">${coverage.percent}% of ${coverage.components} component(s) carry a
          design reference.</p>
        $feedBand
        $issueBand$acceptanceAuditBand
        $visualIssues
        $comparisonBand
        $parityScripts
        """
          .trimIndent(),
    )
  }

  /**
   * Viewer page for one preview: an `<img>` driven by the override controls.
   *
   * [wasmSrc] (non-null only for a CMP catalog with a Wasm app) adds a "Run in browser (Wasm)"
   * toggle mounting the app in a sandboxed `<iframe>` at the `data-mode="live"` seam. It renders
   * client-side, so it is safe even for an unverified session. Theme / font-scale / locale controls
   * re-point its `?uiMode` / `?fontScale` / `?localeTag`; device and orientation stay server-only.
   */
  fun viewerPage(
    preview: ServePreview,
    token: String,
    sessionId: String? = null,
    /**
     * The catalog this preview belongs to, named in the header bar ([siteHeader]); supplied by the
     * caller, which knows the published title.
     */
    catalogName: String = "",
    canApplyOverrides: Boolean = false,
    /**
     * Whether the "Live (stream)" toggle is offered. Distinct from [canApplyOverrides] (whether
     * snapshots re-render on edits): a trusted-catalog live session ([ServeCatalogLiveHost]) has
     * baked snapshots but Live on demand. Defaults to [canApplyOverrides].
     */
    hasLiveStream: Boolean = canApplyOverrides,
    /**
     * Whether an override-bearing `/render` returns fresh pixels although the default snapshot is
     * baked — true for a trusted-catalog live session ([ServeCatalogLiveHost]). Decides whether
     * declared knob controls are live or disabled. Defaults to [canApplyOverrides].
     */
    canRenderOverrides: Boolean = canApplyOverrides,
    /**
     * The density this preview renders at ([ServeBundleHost.renderDensityFor]); null when unknown,
     * hence [FALLBACK_RENDER_DENSITY]. Per-preview because a device can differ per preview.
     */
    renderDensity: Float? = null,
    /**
     * The override params this request carried (`knob.<key>`, `rc.<name>`), filtered and normalised
     * like the page's links (`requestOverrideParams`). Seeds the declared-knob controls so a deep
     * link opens with its values; `hydrateFromUrl` does the same client-side. Empty for a plain
     * visit.
     */
    requestOverrides: Map<String, String> = emptyMap(),
    /**
     * The override axes this request named that the page withheld from its controls — the
     * complement of [requestOverrides]. Published as `data-unseeded-overrides` so `hydrateFromUrl`
     * (`viewer/overrideSeeds.ts`) doesn't restore them from `location.search`. Usually empty.
     */
    unseededOverrides: Set<String> = emptySet(),
    /**
     * Whether the session can export a `compose/figma-svg`, adding an SVG download URL to the
     * copyable-links panel. Defaults to false.
     */
    hasSvgExport: Boolean = false,
    /** Whether the full-page raster/vector scroll export is available for this preview. */
    hasScrollExport: Boolean = false,
    /** Hydrated self-contained per-preview bundle download, when the server can provide one. */
    executableBundleHref: String? = null,
    /**
     * Whether this session can produce the accessibility data the viewer's **Accessibility
     * inspection layer** draws (`a11y/hierarchy`, plus ATF findings / touch targets where
     * available) — [ServeHost.hasA11yOverlay]. False omits the checkbox.
     */
    hasA11yOverlay: Boolean = false,
    /**
     * Whether this session can derive the **Typography**, **Theme attributes** and **Layout boxes**
     * layers from `compose/semantics` ([ServeHost.hasDesignAnnotations]). A static bundle has no
     * daemon to capture the tree.
     */
    hasDesignAnnotations: Boolean = false,
    /**
     * Whether the catalog published typography over this preview's baked frame
     * ([ServeHost.hasPublishedTypographyFor]) — the other Typography lane, and the only one a
     * static bundle has. Theme and Layout stay gated on the semantics lane.
     */
    hasPublishedTypography: Boolean = false,
    trust: String? = null,
    /**
     * Whether this preview carries a captured Remote Compose document
     * ([ServeHost.hasRemoteComposeDoc]). When true the viewer adds the "RC (browser)" toggle +
     * `#cp-rc-canvas`: it loads `/rc-player/bundle.js`, fetches `/render/<id>.rc`, paints
     * client-side, and applies RC knob edits via `setNamed*Override` + `repaint()`.
     */
    hasRemoteComposeDoc: Boolean = false,
    /**
     * Whether a server render replays a captured document rather than re-running the composable —
     * the same question `ServeHttpServer.droppedOverridesFor` asks. Emitted as `data-ir-replay` so
     * the viewer greys controls the server would answer with 409.
     *
     * Separate from `data-has-rc-doc` (bytes exist for the canvas lane) even though they coincide
     * today. Covers a narrow set (see the `irReplay` block in `viewer.js`): Day/Night and font
     * scale stay live because a document can defer them to paint time.
     */
    irReplay: Boolean = false,
    /**
     * Whether a declared theme can still apply despite [irReplay]: the session publishes theme
     * colours as named values (`ServeHost.themeReplayColors`) the player rewrites without
     * recomposition. Emitted as `data-replay-themes` so `viewer.js` re-enables only provider-theme
     * options.
     */
    replayThemes: Boolean = false,
    /**
     * The Remote Compose backends for this preview's backend selector: the [ServeRcPlayerIds] ids
     * from [ServeHost.enabledRcPlayersFor]. The viewer renders one option per
     * [ServeRcPlayerIds.UNIVERSE] entry and enables these. `camaelon-js` drives the client
     * `<canvas>` lane ([hasRemoteComposeDoc]); `androidx-view` / `androidx-embedded` /
     * `cmp-android` re-render on the Android daemon, `cmp-jvm` in its desktop-player subprocess.
     * Legacy spellings (`js`, `java`, `embedded`) map to their players. Empty ⇒ no selector.
     */
    enabledRcPlayers: List<String> = emptyList(),
    /**
     * The [ServeRcPlayerIds] id of the player this preview's baked artifact was drawn with
     * ([ServeHost.bakedRcPlayer]), or empty. Emitted as `data-rc-baked-player` so the viewer knows
     * which lane a bare `/render` already produces and must not name itself in the query (that
     * would split the cache). Reported rather than assumed (`backendRequiresRenderParam`).
     */
    bakedRcPlayer: String = "",
    /**
     * The operator's preferred default Remote Compose player (`serve --rc-default-player`), or null
     * for the built-in order. Honoured only when in [enabledRcPlayers]; see
     * [ServeRcPlayerIds.defaultPlayer].
     */
    preferredRcPlayer: String? = null,
    wasmSrc: String? = null,
    /**
     * Whether the Wasm iframe may use `allow-same-origin` rather than the opaque-origin
     * `allow-scripts` sandbox. True only for a trusted catalog's app, so unverified Wasm can't
     * reach the parent's tokened URLs or DOM. Defaults to false (fail-closed). See the `wasmFrame`
     * sandbox note.
     */
    wasmSameOrigin: Boolean = false,
    /**
     * URL prefix for this session's links (`/<system>`, or empty). Prefixes the "← previews" link;
     * `/render` and `/ws` derive their prefix from `location.pathname` at runtime.
     */
    basePath: String = "",
    /** Same-origin portable scene document; non-null replaces the flat stage with WebGL/WebXR. */
    spatialSceneUrl: String? = null,
    /**
     * Public mode: drop `token=` from the server-rendered "← previews" link. The viewer's runtime
     * requests read the token from the page URL, so they follow suit.
     */
    isPublic: Boolean = false,
    /**
     * Label for the corner backend badge while showing the baked snapshot (e.g. `Android`). The
     * Wasm tier reads `CMP-WASM`; the live stream reads [liveBackend]. Null ⇒ `Snapshot`.
     */
    snapshotBackend: String? = null,
    /**
     * Label for the badge while the daemon live stream drives the stage — the daemon's platform
     * (desktop/JVM or Android), so it comes from the server. Null ⇒ `Live`.
     */
    liveBackend: String? = null,
    /**
     * The app's declared `@ThemeCatalog` themes. Non-empty adds an "App theme" selector
     * re-rendering under the chosen provider (`themeProvider`), enabled when a knob edit would be
     * (`canApplyOverrides || canRenderOverrides`).
     */
    declaredThemes: List<ServeTheme> = emptyList(),
    /**
     * Whether the daemon can apply the one-handed gesture override (Android only). Gates "Show
     * gesture hints" for `@GestureHintPreview` previews. Defaults false.
     */
    gesturesRenderable: Boolean = false,
    /**
     * The session's other previews for the left-hand component nav drawer, typically including
     * [preview] (marked `aria-current`). The drawer is omitted when there is no other preview.
     */
    siblings: List<ServePreview> = emptyList(),
    /**
     * Every variant of [preview]'s component in catalog order, for the compare strip
     * ([comparisonStripHtml]), including [preview] itself. Resolved by the handler (it needs
     * `ServeIssueReport.componentIdFor` and reference lookups). Empty omits the strip.
     */
    componentVariants: List<ComponentVariant> = emptyList(),
    /**
     * The component's named groups for the drawer subtree (recordings, calling samples, later
     * lanes), resolved by the handler via the session registry. Keyed on the component, not the
     * render, so they stay put across variants. Empty leaves the plain axes list.
     */
    componentDirectories: List<ComponentDirectory> = emptyList(),
    /**
     * What kind of catalog this page belongs to; see [PageRole]. Defaults to [PageRole.CATALOG].
     */
    pageRole: PageRole = PageRole.CATALOG,
    /**
     * The catalog's declared stage surface (`display.surface`), deciding the dark stage and whether
     * day/night is offered (not for declared-dark). Null ⇒ the system-name heuristic.
     */
    declaredSurface: String? = null,
    /**
     * The catalog's palette as an inline `:root` override, built by [ServeThemeCss] from
     * `tokens.dtcg.json`. Empty keeps the built-in chrome.
     */
    themeCss: String = "",
    /**
     * Why this session is snapshot-only, if it is; non-empty shows a banner ([degradeBanner])
     * complementing the per-control `cp-note`.
     */
    degradations: List<ServeDegradation> = emptyList(),
    /** Engagement count for this preview on the running server. */
    engagement: PreviewEngagement = PreviewEngagement(),
    /** Absolute viewer + PNG URLs for Open Graph/Twitter link previews. */
    unfurl: UnfurlMetadata? = null,
    /** Running server version (`SERVE_VERSION`) for the footer. Null omits the build span. */
    version: String? = null,
    /**
     * GitHub link to this preview's source file ([ServeUrls.githubBlobUrl] from the delivery
     * provenance and `sourceFile`). Null renders no "source" link.
     */
    sourceHref: String? = null,
    /**
     * Prefilled GitHub new-issue link for this preview, built via [ServeIssueReport]. Null omits
     * it; see [reportIssueHtml].
     */
    reportIssue: ReportIssue? = null,
    /**
     * The Figma node this preview is specified by, when the catalog publishes one
     * ([ServeFigmaSpec]). Null omits the link.
     */
    figmaSpec: FigmaSpec? = null,
    /**
     * The design reference for this preview from `references/index.json`
     * ([ServeDesignReferenceStore]), if any.
     *
     * Present ⇒ the viewer offers a **Spec lane** in the same chip row as the RC players, plus a
     * route into the focused Reference/Diff/Actual comparison. The raster is the catalog's inert
     * PNG at `/reference/<id>.png`; nothing is fetched from Figma.
     */
    designReference: DesignReference? = null,
    /**
     * The counterpart's render in the `compareWith` sibling, when the pairing is declared, the
     * sibling is served on this host, and the counterpart has a render. A second [SpecSource] for
     * the spec lane, same-origin at `/<sibling>/render/<id>.png`. Null omits the picker.
     */
    parallelSource: SpecSource? = null,
    /**
     * The paired sibling preview's design reference, used when this preview publishes none — e.g. a
     * Remote Compose implementation compared with the Figma node shared by its Wear M3 counterpart.
     */
    pairedDesignSource: SpecSource? = null,
    /**
     * Whether to offer `/parallel/<preview>`, the **cross-catalog layer diff**, beside the lane's
     * spec-diff link. A separate affordance because it compares what each side resolved (family,
     * token, insets), not rasters.
     *
     * Gated on the pairing rather than [parallelSource]: the raster source is withheld on a
     * top-level site, but the layer diff is joined server-side and still works there.
     */
    parallelLayers: Boolean = false,
    /**
     * Typography/layout facts captured from [designReference]'s raster, drawn over the spec image;
     * Diff/Triptych pair them with the render and show only changed typography.
     */
    referenceAnnotations: List<DesignAnnotation> = emptyList(),
    /**
     * `/playground?from=…` for this preview, opening its Kotlin against its catalog. Null without a
     * playground lane or a recorded source path.
     */
    playgroundHref: String? = null,
    /**
     * `/usage/<id>` for this preview — the plain-Compose usage code the **Source** chip shows — or
     * null to omit the chip. A URL so the snippet (a GitHub read on a cold cache) is fetched only
     * when opened.
     *
     * Independent of [playgroundHref]: reading code is useful anywhere, while running it needs a
     * host that compiles that catalog; the panel links to the editor only when there is one.
     */
    usageHref: String? = null,
    /** GitHub sign-in prompt shown when the daemon live stream is present but requires auth. */
    liveAuthPrompt: LiveAuthPrompt? = null,
    /** Human catalog title used in the breadcrumb; falls back to a generic "Previews" label. */
    catalogTitle: String? = null,
    /**
     * Presence `POST` URL keeping this session and its daemon alive while the viewer is open
     * ([presenceScript]). Empty omits the heartbeat.
     */
    presenceUrl: String = "",
    /**
     * `history.json` on the delivery branch, or null without delivery provenance (which omits the
     * timeline). See [ServeUrls.historyManifestUrl].
     */
    historyManifestUrl: String? = null,
    /**
     * `owner/repo` of the delivery branch, for addressing a historical render by sha. Both this and
     * [historyManifestUrl] or neither.
     */
    historyRepo: String? = null,
    /**
     * A manifest payload inlined instead of fetched, so fixtures and offline viewers render the
     * timeline (and the preview-harness capture actually covers it).
     */
    historyInlineJson: String? = null,
    /**
     * A `/api/render-runs` payload inlined into the revision menu instead of fetched, for the same
     * reason as [historyInlineJson].
     */
    revisionRunsInlineJson: String? = null,
    /**
     * Project mode: the timeline was computed locally ([ServeProjectHistory]), so entries link to
     * this server's `/history/render/<blob>.png`, and the strip describes published baselines
     * rather than the working-tree stage. Only honoured with [historyInlineJson].
     */
    historyLocalRenders: Boolean = false,
    /**
     * The catalog's published revisions and this page's pin ([CatalogRevisions]). A pin makes the
     * viewer a reader of one publish: the stage shows that revision's baked pixels and every
     * re-rendering control is refused, since the daemon renders today's code.
     */
    revisions: CatalogRevisions = CatalogRevisions.NONE,
    /**
     * Render overrides already on the viewer URL (no leading `?`), carried across revision links so
     * choosing a revision keeps the selected theme etc. The pin is added separately.
     */
    revisionQuery: String = "",
    /**
     * Whether this page is served as a **top-level site** ([ServeSites]); the origin implies the
     * session, so links must not repeat `?session=`.
     */
    sessionInOrigin: Boolean = false,
    parityIssues: List<ParityIssue> = emptyList(),
    /**
     * When the issue index was generated (`ParityIssues.generatedAt`, ISO-8601), printed at the
     * foot of an opened issue panel. Null omits the line.
     */
    parityIssuesGeneratedAt: String? = null,
    componentBrowser: Boolean = false,
    /**
     * The catalog change feed offered as **Changelog** and declared as the page's RSS alternate.
     * Empty when the feed lane is off. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * Per-preview prebaked thumbnail lookup for the component drawer (the same
     * [ServeHeroImages.gridThumbFor] hash as the grid), so ~40px rows don't load full renders
     * ([navDrawerHtml]). The default `{ null }` keeps plain render URLs for fixture goldens.
     */
    navThumbHash: (String) -> String? = { null },
  ): String {
    // A samples catalog drops every comparison lane, since a sample is not a rendition of a
    // reference ([PageRole.SAMPLES]). Shadowed here so nothing below can read a comparison input.
    val samplesRole = pageRole == PageRole.SAMPLES
    @Suppress("NAME_SHADOWING") val parallelSource = parallelSource?.takeUnless { samplesRole }
    @Suppress("NAME_SHADOWING")
    val pairedDesignSource = pairedDesignSource?.takeUnless { samplesRole }
    @Suppress("NAME_SHADOWING") val parallelLayers = parallelLayers && !samplesRole
    @Suppress("NAME_SHADOWING")
    val referenceAnnotations = if (samplesRole) emptyList() else referenceAnnotations
    // The compare strip goes too; the drawer subtree still lists the variants for navigation.
    @Suppress("NAME_SHADOWING")
    val componentVariants = if (samplesRole) emptyList() else componentVariants
    @Suppress("NAME_SHADOWING")
    val designReference = designReference?.takeUnless { componentBrowser || samplesRole }
    @Suppress("NAME_SHADOWING") val sourceHref = sourceHref?.takeUnless { componentBrowser }
    // Deliberately kept in Catalog mode: design reviewers are exactly who files these, and Catalog
    // mode has no footer or floating launcher, so this is its only reporting affordance.
    @Suppress("NAME_SHADOWING")
    val figmaSpec = figmaSpec?.takeUnless { componentBrowser || samplesRole }
    @Suppress("NAME_SHADOWING") val playgroundHref = playgroundHref?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val historyManifestUrl = historyManifestUrl?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING") val historyRepo = historyRepo?.takeUnless { componentBrowser }
    @Suppress("NAME_SHADOWING")
    val historyInlineJson = historyInlineJson?.takeUnless { componentBrowser }
    // Catalog mode hides the revision control but keeps the page's generation, so the browser-built
    // stage URL is scoped like the server-built OG and report URLs.
    @Suppress("NAME_SHADOWING")
    val revisions =
      if (componentBrowser) CatalogRevisions(generation = revisions.generation) else revisions
    @Suppress("NAME_SHADOWING")
    val parityIssues = if (componentBrowser || samplesRole) emptyList() else parityIssues
    @Suppress("NAME_SHADOWING")
    val degradations = if (componentBrowser) emptyList() else degradations
    // The session id links may carry: null on a rooted site and for the default session.
    // `sessionId` still keys per-catalog storage and the dark-first lookup.
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val idSeg = WebEscaping.urlEncodeSegment(preview.id)
    // A pin turns off every lane that produces output on demand from current code (knobs, declared
    // themes, live stream, Wasm, SVG export, RC players, inspection layers, scroll capture, bundle
    // download), since the URL promises a fixed publish. Published files (baked PNG, design
    // reference) take the pin instead (see `specRasterUrl`).
    //
    // The names are shadowed so no path below reads the unpinned flag.
    val pinned = revisions.pinned
    // Remember capabilities before the pin suppresses them, so a pinned toolbar can show those
    // controls disabled with an explanation.
    val currentHasSvgExport = hasSvgExport
    @Suppress("NAME_SHADOWING") val canApplyOverrides = canApplyOverrides && pinned == null
    @Suppress("NAME_SHADOWING") val canRenderOverrides = canRenderOverrides && pinned == null
    @Suppress("NAME_SHADOWING")
    val hasLiveStream = hasLiveStream && pinned == null && !componentBrowser
    @Suppress("NAME_SHADOWING") val wasmSrc = wasmSrc?.takeIf { pinned == null }
    @Suppress("NAME_SHADOWING") val hasSvgExport = hasSvgExport && pinned == null
    @Suppress("NAME_SHADOWING")
    val hasScrollExport = hasScrollExport && pinned == null && !componentBrowser
    // A component page offers one inspection product: measured content slots — parameters declared
    // as slots, placed via the daemon's `.slots` `PreviewSlot` markers.
    val hasSlotInspection =
      componentBrowser &&
        pinned == null &&
        hasDesignAnnotations &&
        preview.componentParameters.any { it.composableSlot }
    // Catalog mode keeps the whole Remote Compose facet (canvas and every player): which player
    // drew a document is the subject of an RC catalog, and without an owner for `rcPlayer`,
    // `url-state.js` would strip a shared `?rcPlayer=…`.
    @Suppress("NAME_SHADOWING") val hasRemoteComposeDoc = hasRemoteComposeDoc && pinned == null
    @Suppress("NAME_SHADOWING")
    val enabledRcPlayers =
      if (pinned == null) enabledRcPlayers.map(ServeRcPlayerIds::normalizeRequest).distinct()
      else emptyList()
    @Suppress("NAME_SHADOWING")
    val bakedRcPlayer = ServeRcPlayerIds.fromCaptureRecord(bakedRcPlayer).orEmpty()
    @Suppress("NAME_SHADOWING")
    val hasA11yOverlay = hasA11yOverlay && pinned == null && !componentBrowser
    @Suppress("NAME_SHADOWING")
    val hasDesignAnnotations = hasDesignAnnotations && pinned == null && !componentBrowser
    @Suppress("NAME_SHADOWING")
    val hasPublishedTypography = hasPublishedTypography && pinned == null && !componentBrowser
    // Motion captures should take the pin like other published files, but `/motion/<id><ext>` only
    // reads the branch tip, so the lane is dropped on pinned pages rather than playing current
    // bytes. See docs/public-preview-server.md.
    val motionCaptures = if (pinned == null) preview.motion else emptyList()
    @Suppress("NAME_SHADOWING")
    val executableBundleHref = executableBundleHref?.takeIf { pinned == null && !componentBrowser }
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val displayName = previewDisplayName(preview)
    val issueRows =
      if (componentBrowser) ""
      else parityIssueRowsHtml(parityIssues, generatedAt = parityIssuesGeneratedAt)
    val label = WebEscaping.htmlEscape(displayName)
    val idText = WebEscaping.htmlEscape(preview.id)
    val modes = preview.modes.joinToString(",") { it.wire }
    // The baked fallback shown before any override is chosen. The unified Theme selector displays
    // this choice without sending a redundant uiMode override on first load.
    val viewerDarkFirst = isDarkFirstSystem(basePath, sessionId, declaredSurface)
    // A dark-first surface has no day mode, so the day/night override isn't exposed.
    //
    // Either signal makes a catalog dark-only: the Wear/watch id heuristic, or a declared dark
    // `display.surface` (via [isDarkFirstSystem]). It's `||`, not [viewerDarkFirst] alone:
    // [normalizeOverrideParams] drops `uiMode` for Wear ids unconditionally, so the id keeps its
    // veto until normalization can read declarations.
    //
    // The declaration only counts when the session publishes no light render at all, since
    // `display.surface` is a stage colour, not a statement about modes; a catalog baking a
    // light/dark pair keeps it.
    val hasLightRender = (siblings + preview).any { previewTheme(it, darkFirst = false) == "light" }
    val wearAlwaysDark =
      SystemDisplay.isDarkFirst(basePath.trim('/').ifBlank { sessionId ?: "" }) ||
        (viewerDarkFirst && !hasLightRender)
    val alwaysDarkAttr = if (wearAlwaysDark) " data-always-dark=\"1\"" else ""
    val irReplayAttr = if (irReplay) " data-ir-replay=\"1\"" else ""
    val replayThemesAttr = if (replayThemes) " data-replay-themes=\"1\"" else ""
    val viewerTheme = previewTheme(preview, viewerDarkFirst)
    // The Wasm tier is opt-in like "Live (stream)", so the PNG snapshot stays the default. Omitted
    // when no Wasm app backs this session.
    val wasmAttr =
      if (wasmSrc != null) " data-wasm-src=\"${WebEscaping.htmlEscape(wasmSrc)}\"" else ""
    // `allow-same-origin` (with `allow-scripts`) only for a [wasmSameOrigin] (trusted-catalog) app
    // served same-origin from `/wasm/<system>/`: the Kotlin/Wasm + Compose runtime's
    // storage/history/Cache APIs throw `SecurityError` in an opaque origin. Untrusted catalogs'
    // apps stay opaque so they can't reach the parent's tokened URLs/DOM or lift their sandbox.
    // `data-wasm-src` is also same-origin-checked (see wasmBaseSrc).
    val wasmSandbox = if (wasmSameOrigin) "allow-scripts allow-same-origin" else "allow-scripts"
    val wasmFrame =
      if (wasmSrc != null)
        "<iframe id=\"cp-wasm\" hidden sandbox=\"$wasmSandbox\" title=\"$label (Wasm)\"></iframe>"
      else ""
    // The render mode is a single Static⇄Live toggle; the mode radios the transport JS drives
    // (`cp-mode-png`, `cp-live`, `cp-wasm-toggle`) stay in the DOM but visually removed. SVG is an
    // export format, not a mode. The Wasm radio exists only with a Wasm app.
    val wasmModeInput =
      if (wasmSrc != null)
        "<input type=\"radio\" name=\"cp-mode\" value=\"wasm\" id=\"cp-wasm-toggle\" tabindex=\"-1\">"
      else ""
    // The SVG format toggle, swapping the snapshot between PNG and SVG. Gated on [hasSvgExport]
    // like the SVG direct link.
    val svgFmtToggle =
      if (currentHasSvgExport && !componentBrowser) {
        val availability =
          if (pinned == null) " title=\"Show the vector (SVG) render\""
          else
            " disabled aria-describedby=\"cp-pinned-controls-note\"" +
              " title=\"Pinned revision — SVG is generated from the current catalog\""
        val button =
          "<button type=\"button\" id=\"cp-svg-toggle\" class=\"cp-fmt-toggle\" " +
            "aria-pressed=\"false\"$availability>SVG</button>"
        if (pinned == null) button
        else
          "<span class=\"cp-disabled-control\" tabindex=\"0\" " +
            "aria-describedby=\"cp-pinned-controls-note\">$button</span>"
      } else ""
    // The exploded 3D toggle: the layered figma-svg tilted and split into one sheet per drawing
    // level ([ExplodedSvg]). A view of the SVG export, so it shares the [hasSvgExport] gate.
    val explodeToggle =
      if (currentHasSvgExport && !componentBrowser) {
        val availability =
          if (pinned == null) " title=\"Show how the visible drawing layers are composed\""
          else
            " disabled aria-describedby=\"cp-pinned-controls-note\"" +
              " title=\"Pinned revision — 3D is generated from the current catalog\""
        val button =
          "<button type=\"button\" id=\"cp-explode-toggle\" class=\"cp-fmt-toggle\" " +
            "aria-pressed=\"false\"$availability>3D</button>"
        if (pinned == null) button
        else
          "<span class=\"cp-disabled-control\" tabindex=\"0\" " +
            "aria-describedby=\"cp-pinned-controls-note\">$button</span>"
      } else ""
    val svgMatch =
      if (hasSvgExport && !componentBrowser) {
        val compareQuery =
          listOf(
              "format=svg",
              "preview=${WebEscaping.urlEncodeSegment(preview.id)}",
              linkQuery(token, linkSessionId, basePath, isPublic),
            )
            .filter { it.isNotEmpty() }
            .joinToString("&")
        "<span id=\"cp-svg-match\" class=\"cp-match\" role=\"status\" aria-live=\"polite\" hidden></span>" +
          "<a id=\"cp-svg-diff\" class=\"cp-format-link\" href=\"$basePath/compare?$compareQuery\" hidden>view diff →</a>"
      } else ""
    // The in-browser Remote Compose canvas lane (`#cp-rc-canvas`, hidden mode radio, toggle),
    // offered only when the preview has a captured `.rc` ([hasRemoteComposeDoc]). `data-has-rc-doc`
    // tells the transport JS to wire it; doc and player URLs are built at runtime
    // (`/rc-player/bundle.js`). Reuses `.cp-live-toggle` styling.
    val rcAttr = if (hasRemoteComposeDoc) " data-has-rc-doc=\"1\"" else ""
    val rcCanvas = if (hasRemoteComposeDoc) "<canvas id=\"cp-rc-canvas\" hidden></canvas>" else ""
    val hasRcWasm = ServeRcPlayerIds.CMP_WASM in enabledRcPlayers
    val rcWasmFrame =
      if (hasRcWasm)
        "<iframe id=\"cp-rc-wasm\" hidden sandbox=\"allow-scripts allow-same-origin\" " +
          "title=\"$label (Remote Compose CMP Wasm)\"></iframe>"
      else ""
    val rcModeInput =
      if (hasRemoteComposeDoc)
        "<input type=\"radio\" name=\"cp-mode\" value=\"rc\" id=\"cp-rc-toggle\" tabindex=\"-1\">"
      else ""
    val rcWasmModeInput =
      if (hasRcWasm)
        "<input type=\"radio\" name=\"cp-mode\" value=\"rc-wasm\" id=\"cp-rc-wasm-toggle\" tabindex=\"-1\">"
      else ""
    // The **Spec lane**: the imported design reference for this preview (the catalog's PNG from
    // `/reference/<id>.png`, never fetched from Figma) swapped onto the same stage. Rendered only
    // when a reference is published for this id.
    val specLabel = designReference?.let { it.label.takeIf { l -> l.isNotBlank() } ?: it.id }
    val specProviderLabel =
      when (designReference?.source?.provider?.trim()?.lowercase()) {
        "figma" -> "Figma"
        null -> null
        else -> "Spec"
      }
    // Pinned, not dropped: a design reference is a published file with a historical answer.
    val specRasterUrl = designReference?.let {
      "$basePath/reference/${WebEscaping.urlEncodeSegment(it.id)}.png${assetQuery(q, revisions)}"
    }
    // The focused Reference / Diff / Actual page for this exact mapping — the same link the
    // comparison grid offers, so the picker's neighbour steps from "look at the spec" to "diff it".
    val specCompareHref = designReference?.let { reference ->
      val query =
        listOf(
            linkQuery(token, linkSessionId, basePath, isPublic),
            "reference=${WebEscaping.urlEncodeSegment(reference.id)}",
          )
          .filter { it.isNotEmpty() }
          .joinToString("&")
      // Carries the pin, so stepping out to the focused comparison keeps the publish you were
      // reading rather than silently landing on the live one.
      withPin("$basePath/compare/$idSeg${querySuffix(query)}", pinned)
    }
    // The cross-catalog layer diff. Not offered on a pinned page, since the layers are the tip
    // catalog's.
    val parallelLayersHref =
      if (!parallelLayers || pinned != null) ""
      else
        "$basePath/parallel/$idSeg" +
          querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    // Every source the comparison lane can put opposite this render: a design reference, a parallel
    // implementation, or both (a parallel raster alone is a complete pair).
    val specSources =
      listOfNotNull(
        if (specRasterUrl == null || specProviderLabel == null) null
        else
          SpecSource(
            id = "kit",
            label = specProviderLabel,
            rasterUrl = specRasterUrl,
            // No caveat to give: an imported reference is a static specification, and comparing
            // this publish's render against this publish's spec is the comparison the lane is for.
            provenance = "",
          ),
        pairedDesignSource.takeIf { designReference == null },
        parallelSource,
      )
    val primarySpecSource = specSources.firstOrNull()
    val specSurfaceUrl = primarySpecSource?.rasterUrl
    val parallelOnly = designReference == null && primarySpecSource?.id == "parallel"
    // The four ways to view the render/reference pair, on the stage once the lane is up, so the
    // focused comparison's instruments are available without leaving the viewer's overrides.
    // `triptych` is the default; `spec` (reference alone) is one click away.
    val referenceNoun = if (parallelOnly) primarySpecSource.label else "Spec"
    val comparisonAriaLabel = if (parallelOnly) "Render comparison" else "Design comparison"
    val specViews =
      if (parallelOnly)
        listOf(
          "spec" to (referenceNoun to "$referenceNoun on its own"),
          "diff" to
            ("Diff" to "Highlight every pixel where the render and $referenceNoun disagree"),
          "triptych" to ("Triptych" to "$referenceNoun, diff and render side by side"),
          "slider" to ("Slider" to "One frame, wiped between $referenceNoun and the render"),
        )
      else
        listOf(
          "spec" to ("Spec" to "The imported design reference on its own"),
          "diff" to ("Diff" to "Highlight every pixel where the render and the spec disagree"),
          "triptych" to ("Triptych" to "Spec, diff and render side by side"),
          "slider" to ("Slider" to "One frame, wiped between the spec and the render"),
        )
    // The spec lane's carrier, not a control: `data-spec-src` is the raster viewer.js paints on
    // entry; the comparison group chooses how the pair is drawn; the link steps out to the focused
    // comparison. Entering the lane is [specChipHtml]'s job.
    val specSelector =
      if (primarySpecSource == null) ""
      else {
        val tip =
          if (parallelOnly) "Compare this render against ${primarySpecSource.label}"
          else
            "Compare this render against the imported design spec — " +
              (specLabel ?: primarySpecSource.label)
        // Hidden until the lane is entered; `<cp-spec-compare>` reveals it from openSpec().
        // The source picker, emitted only when there is more than one source. Each button carries
        // its own server-built, escaped raster and label, so switching never reads a URL from the
        // DOM into an image (CodeQL `js/xss-through-dom`).
        val sourceButtons =
          if (specSources.size < 2 && !parallelOnly) ""
          else
            "<span class=\"cp-spec-sources\" id=\"cp-spec-sources\" role=\"group\" " +
              "aria-label=\"Compare against\" hidden>" +
              specSources.joinToString("") { source ->
                "<button type=\"button\" class=\"cp-spec-source\" " +
                  "data-cp-spec-source=\"${WebEscaping.htmlEscape(source.id)}\" " +
                  "data-spec-src=\"${WebEscaping.htmlEscape(source.rasterUrl)}\" " +
                  "data-spec-label=\"${WebEscaping.htmlEscape(source.label)}\" " +
                  "data-spec-provenance=\"${WebEscaping.htmlEscape(source.provenance)}\" " +
                  "aria-pressed=\"${source.id == specSources.first().id}\" " +
                  "title=\"Compare this render against ${WebEscaping.htmlEscape(source.label)}\">" +
                  "${WebEscaping.htmlEscape(source.label)}</button>"
              } +
              "</span>"
        val viewButtons =
          specViews.joinToString("") { (value, text) ->
            val (viewLabel, viewTip) = text
            "<button type=\"button\" class=\"cp-spec-view\" data-cp-spec-view=\"$value\" " +
              "aria-pressed=\"${value == SPEC_DEFAULT_VIEW}\" " +
              "title=\"${WebEscaping.htmlEscape(viewTip)}\">${WebEscaping.htmlEscape(viewLabel)}</button>"
          }
        "<span class=\"cp-spec-lane\" id=\"cp-spec-lane\" " +
          // The first source's raster and label stay on these attributes, which single-source lanes
          // and the backend badge read.
          "data-spec-src=\"${WebEscaping.htmlEscape(primarySpecSource.rasterUrl)}\" " +
          "data-spec-label=\"${WebEscaping.htmlEscape(primarySpecSource.label)}\">" +
          sourceButtons +
          "<span class=\"cp-spec-views\" id=\"cp-spec-views\" role=\"group\" " +
          "aria-label=\"$comparisonAriaLabel\" hidden>$viewButtons</span>" +
          "<span class=\"cp-spec-score\" id=\"cp-spec-score\" role=\"status\" " +
          "aria-live=\"polite\" hidden></span>" +
          // The eyedropper readout, last and on its own row (see `serve.css`) so readings don't
          // reflow the controls. Not a live region (rewritten on every pointermove); only a frozen
          // reading is announced, via the hidden region beside it.
          "<span class=\"cp-spec-pick\" id=\"cp-spec-pick\" hidden></span>" +
          "<span class=\"cp-spec-pick-live\" id=\"cp-spec-pick-live\" " +
          "aria-live=\"polite\"></span></span>"
      }
    // The renderer picker: one chip plus one combo box. [laneSelectHtml] chooses the renderer; the
    // `#cp-live-toggle` chip names the chosen one and toggles live, its dot the live indicator.
    // viewer.js drives both from one lane value (`syncLaneSelect`).
    val rcEnabled = enabledRcPlayers.toSet()
    // The lane a Remote Compose preview opens on: the operator's `serve --rc-default-player` when
    // enabled, else `androidx-embedded`, else `androidx-view`, else the client `camaelon-js`
    // canvas. The rationale lives at [ServeRcPlayerIds.defaultPlayer].
    val defaultRcBackend = ServeRcPlayerIds.defaultPlayer(enabledRcPlayers, preferredRcPlayer)
    // Every lane this preview can be drawn by, in display order: RC players (or the plain
    // snapshot), the Wasm app, and the design spec. Players the host doesn't offer are listed
    // disabled.
    data class ViewerLane(val value: String, val label: String, val enabled: Boolean)
    val lanes = buildList {
      if (enabledRcPlayers.isEmpty()) add(ViewerLane("png", "Snapshot", true))
      else
        ServeRcPlayerIds.UNIVERSE.forEach { player ->
          add(ViewerLane("rc:${player.id}", player.label, player.id in rcEnabled))
        }
      if (wasmSrc != null) add(ViewerLane("wasm", "In browser (Wasm)", true))
    }
    // The **design-spec chip**: the imported reference as its own control rather than a renderer
    // option, since it answers a different question. It names the tool ("Figma") and its
    // `aria-pressed` reports whether the spec is on stage; viewer.js keeps it in step with the
    // combo.
    val specChipHtml =
      if (primarySpecSource == null) ""
      else {
        val name =
          if (parallelOnly) primarySpecSource.label
          else if (primarySpecSource.label == "Figma") "Figma" else "Design spec"
        // The verdict on the chip at rest: the one published score for this render/reference pair,
        // always printed. The band only picks the colour.
        val match = designReference?.match
        val band = match?.let { specMatchBand(it.percent) }
        val label = if (match == null) name else "$name ${WebEscaping.formatPercent(match.percent)}"
        val tip =
          if (parallelOnly)
            "Compare this render against the paired ${primarySpecSource.label} implementation"
          else if (match == null)
            "Put the imported ${primarySpecSource.label} spec on the stage instead of the render"
          else
            buildString {
              append("${WebEscaping.formatPercent(match.percent)} match against the imported ")
              append("$specProviderLabel spec")
              match.changedPercent?.let {
                append(" · ${WebEscaping.formatPercent(it, 2)} pixels differ")
              }
              match.geometry?.let {
                append(" · ${WebEscaping.formatPercent(it, 1)} proportion difference")
              }
              append(" — click to see where")
            }
        val bandAttr = band?.let { " data-spec-match=\"$it\"" } ?: ""
        // The chip's label once the render moves off the snapshot the verdict was measured against
        // (e.g. another theme): the published number would describe a frame no longer on stage.
        // viewer.js publishes `data-spec-baseline` and `<cp-spec-compare>` shows this label until
        // it has a live measurement.
        val staleTip =
          "The published match is measured against this catalog's default render — " +
            "click to compare the $specProviderLabel spec against what's on the stage now"
        val staleTipAttr =
          if (match == null) ""
          else " data-spec-chip-stale-tip=\"${WebEscaping.htmlEscape(staleTip)}\""
        "<button type=\"button\" id=\"cp-spec-chip\" class=\"cp-spec-chip\"$bandAttr " +
          "aria-pressed=\"false\" data-spec-chip-label=\"${WebEscaping.htmlEscape(label)}\" " +
          "data-spec-chip-name=\"${WebEscaping.htmlEscape(name)}\" " +
          "data-spec-chip-tip=\"${WebEscaping.htmlEscape(tip)}\"$staleTipAttr " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">${WebEscaping.htmlEscape(label)}</button>"
      }
    // The comparison group: every source this render can be compared against, as peers, each with a
    // way in from the resting bar (`docs/design/COMPARE_NAVIGATION.md` F1). The picker stays for
    // switching once the lane is up.
    //
    // These carry only the source id; `viewer.js` presses the matching picker button, which holds
    // the server-built raster, label and provenance.
    val specPeerChips =
      if (primarySpecSource == null) ""
      else
        specSources.drop(1).joinToString("") { source ->
          "<button type=\"button\" class=\"cp-spec-chip cp-spec-peer\" " +
            "data-cp-spec-open-source=\"${WebEscaping.htmlEscape(source.id)}\" " +
            "aria-pressed=\"false\" " +
            "title=\"Compare this render against ${WebEscaping.htmlEscape(source.label)}\">" +
            "${WebEscaping.htmlEscape(source.label)}</button>"
        }
    // Labelled only when there is more than one source.
    val specGroupHtml =
      if (specChipHtml.isBlank()) ""
      else if (specPeerChips.isEmpty()) specChipHtml
      else
        "<span class=\"cp-compare-group\" role=\"group\" aria-label=\"Compare against\">" +
          "<span class=\"cp-view-group-label\" aria-hidden=\"true\">Compare</span>" +
          specChipHtml +
          specPeerChips +
          "</span>"
    val sourceKnown = !usageHref.isNullOrBlank()
    val usageAvailable = sourceKnown && pinned == null
    // The side source lane: on a samples page the code stands beside the render. An attribute
    // rather than a second panel — the viewer script opens the lane at load and keeps `closeSource`
    // from closing it; the stylesheet lays out two columns. Only when there is source to show.
    val sideSourceLane = samplesRole && usageAvailable
    val sourceLaneAttr = if (sideSourceLane) " data-source-lane=\"side\"" else ""
    // The **Source chip**: the usage code behind this card ("what do I type to get this?"), on the
    // row rather than in the renderer combo. Offered whenever the host can resolve source,
    // regardless of whether the playground can compile the catalog.
    val sourceChipHtml =
      if (!sourceKnown) ""
      else {
        val tip =
          if (pinned == null) "Show the plain Compose that produces this render"
          else "Pinned revision — source is only available from the current catalog"
        val tabClass = if (componentBrowser) " cp-browser-tab" else ""
        val tabAttrs = if (componentBrowser) " role=\"tab\" aria-selected=\"false\"" else ""
        val disabled =
          if (pinned == null) "" else " disabled aria-describedby=\"cp-pinned-controls-note\""
        val usageSrc =
          if (pinned == null) " data-usage-src=\"${WebEscaping.htmlEscape(usageHref)}\"" else ""
        val button =
          "<button type=\"button\" id=\"cp-source-chip\" class=\"cp-spec-chip cp-source-chip$tabClass\"$tabAttrs " +
            "aria-pressed=\"false\" aria-controls=\"cp-source-panel\" " +
            "data-source-chip-tip=\"${WebEscaping.htmlEscape(tip)}\"$usageSrc " +
            "title=\"${WebEscaping.htmlEscape(tip)}\"$disabled>Source</button>"
        if (pinned == null) button
        else
          "<span class=\"cp-disabled-control\" tabindex=\"0\" " +
            "aria-describedby=\"cp-pinned-controls-note\">$button</span>"
      }
    val browserPreviewTab =
      if (!componentBrowser || !usageAvailable) ""
      else
        "<button type=\"button\" id=\"cp-browser-preview-tab\" " +
          "class=\"cp-spec-chip cp-browser-tab\" role=\"tab\" aria-selected=\"true\">Preview</button>"
    // Catalog mode shows the baked snapshot, like Dev mode; this script only syncs the Preview /
    // Source tab pair with the source panel's toggle. Forcing the Wasm lane here would cancel the
    // snapshot load and leave the iframe sized to an empty `<img>`. `?mode=wasm` still pins the
    // Wasm lane.
    val browserTabsScript =
      if (!componentBrowser) ""
      else
        """
        <script>(function(){var p=document.getElementById("cp-browser-preview-tab"),s=document.getElementById("cp-source-chip"),r=document.getElementById("cp-source-toggle");function sync(){if(!p||!s||!r)return;var source=!!r.checked;p.setAttribute("aria-selected",source?"false":"true");s.setAttribute("aria-selected",source?"true":"false");}if(p&&s&&r){p.addEventListener("click",function(){if(r.checked)s.click();setTimeout(sync,0);});s.addEventListener("click",function(){setTimeout(sync,0);});window.addEventListener("popstate",function(){setTimeout(sync,0);});}setTimeout(sync,0);})();</script>
        """
          .trimIndent()
    // The Motion lane: the recorded interaction (`@InteractionPreview` / `@AnimatedPreview`,
    // published under `motion/`) on the stage in place of the still.
    //
    // A chip, never the default frame: most readers want the component at rest, and captures are
    // heavy, so bytes are fetched on first entry only. Not a renderer option, since it is the same
    // render moving.
    // Captions are split by [MotionCaptureLabels]: a brief title for the menu, the full caption for
    // the readout.
    val motionLabels = MotionCaptureLabels.of(motionCaptures)
    // Session-scoped like other asset links, but not pin-carrying: the route reads the session's
    // branch tip and could not honour a pin.
    fun motionSrc(capture: ServeMotion): String =
      "$basePath/motion/${WebEscaping.urlEncodeSegment(capture.id)}${capture.extension}$q"
    // The picker and src holder, rendered even for one capture (one code path) but `hidden` until
    // there is a choice. A closed `<select>` at fixed width rather than a segmented group, and a
    // state field (it is the only thing naming which recording plays). The full caption rides on
    // the option for the readout.
    val motionSelector =
      if (motionCaptures.isEmpty()) ""
      else {
        val options =
          motionCaptures
            .mapIndexed { index, capture ->
              val label = motionLabels[index]
              val detail =
                if (label.detail == label.title) ""
                else " data-motion-detail=\"${WebEscaping.htmlEscape(label.detail)}\""
              "<option value=\"${WebEscaping.htmlEscape(capture.id)}\" " +
                "data-motion-src=\"${WebEscaping.htmlEscape(motionSrc(capture))}\"" +
                "$detail${if (index == 0) " selected" else ""}>" +
                "${WebEscaping.htmlEscape(label.title)}</option>"
            }
            .joinToString("")
        val menuHidden = if (motionCaptures.size < 2) " hidden" else ""
        "<span class=\"cp-motion-lane\" id=\"cp-motion-lane\" hidden>" +
          "<select class=\"cp-motion-select\" id=\"cp-motion-select\" " +
          "aria-label=\"Recorded interaction\" " +
          "title=\"Which recorded interaction to play\"$menuHidden>$options</select>" +
          "<span class=\"cp-motion-caption\" id=\"cp-motion-caption\" role=\"status\" " +
          "aria-live=\"polite\"></span></span>"
      }
    // The chip itself, beside Source: "what does it do?"
    val motionChipHtml =
      if (motionCaptures.isEmpty()) ""
      else {
        val tip =
          if (motionCaptures.size == 1)
            // The full caption, so the tooltip says what the recording shows before starting it.
            "Play this preview's recorded interaction \u2014 " +
              motionLabels[0].detail.ifBlank { motionLabels[0].title }
          else "Play this preview's recorded interactions (${motionCaptures.size})"
        "<button type=\"button\" id=\"cp-motion-chip\" class=\"cp-spec-chip cp-motion-chip\" " +
          // Both, because which one carries the capture depends on whether the browser could
          // decode its frames: the player when it could, the plain image when it could not.
          "aria-pressed=\"false\" aria-controls=\"cp-motion-player cp-motion-img\" " +
          "data-motion-chip-tip=\"${WebEscaping.htmlEscape(tip)}\" " +
          "title=\"${WebEscaping.htmlEscape(tip)}\">Motion</button>"
      }
    // The fallback stage image for the Motion lane: hidden and src-less until entered, so an
    // unopened capture is neither fetched nor played. The canvas below is the primary path (frame
    // control, no seamless loop); this is for browsers that can't decode frames (see `loadMotion`
    // in `viewer.ts`).
    val motionImg =
      if (motionCaptures.isEmpty()) ""
      else
        "<img id=\"cp-motion-img\" class=\"cp-motion-img\" hidden alt=\"" +
          "${WebEscaping.htmlEscape("$displayName \u2014 recorded interaction")}\">"
    // The player: the capture on a canvas with its transport. Play once, playback position,
    // scrubbing and speed all need addressable frames, so `viewer.ts` decodes with `ImageDecoder`
    // and paints frame N here, only once the lane is entered. The transport stays `hidden` until a
    // decode succeeds.
    val motionPlayer =
      if (motionCaptures.isEmpty()) ""
      else {
        val rateOptions =
          listOf("0.25" to "0.25\u00d7", "0.5" to "0.5\u00d7", "1" to "1\u00d7", "2" to "2\u00d7")
            .joinToString("") { (value, label) ->
              "<option value=\"$value\"${if (value == "1") " selected" else ""}>$label</option>"
            }
        "<div class=\"cp-motion-player\" id=\"cp-motion-player\" hidden>" +
          "<canvas id=\"cp-motion-canvas\" class=\"cp-motion-canvas\" role=\"img\" " +
          "aria-label=\"${WebEscaping.htmlEscape("$displayName \u2014 recorded interaction")}\">" +
          "</canvas>" +
          "<div class=\"cp-motion-transport\" id=\"cp-motion-transport\" hidden>" +
          "<button type=\"button\" id=\"cp-motion-play\" class=\"cp-motion-transport-btn\" " +
          "aria-pressed=\"false\" title=\"Play\" aria-label=\"Play\">\u25b6</button>" +
          "<button type=\"button\" id=\"cp-motion-replay\" class=\"cp-motion-transport-btn\" " +
          "title=\"Play again from the start\" aria-label=\"Play again from the start\">" +
          "\u21ba</button>" +
          // A native range input: it brings arrow-key frame stepping, Home/End, focus and
          // screen-reader announcements for free.
          "<input type=\"range\" id=\"cp-motion-scrub\" class=\"cp-motion-scrub\" " +
          "min=\"0\" max=\"0\" value=\"0\" step=\"1\" " +
          "title=\"Scrub to a frame\" aria-label=\"Frame\">" +
          "<span class=\"cp-motion-time\" id=\"cp-motion-time\"></span>" +
          "<select id=\"cp-motion-rate\" class=\"cp-motion-rate\" " +
          "title=\"Playback speed\" aria-label=\"Playback speed\">$rateOptions</select>" +
          "</div></div>"
      }
    // Motion's hidden mode radio, joining the radio group for `?mode=motion`, restore on load,
    // Back/Forward and `currentMode()`.
    val motionModeInput =
      if (motionCaptures.isEmpty()) ""
      else
        "<input type=\"radio\" name=\"cp-mode\" value=\"motion\" id=\"cp-motion-toggle\" " +
          "tabindex=\"-1\">"
    val defaultLane = if (enabledRcPlayers.isEmpty()) "png" else "rc:$defaultRcBackend"
    // Rendered only when there is another lane to switch to. A command menu: the placeholder shows
    // at rest and `syncLaneSelect` returns to it after each pick, since the chip beside it names
    // the current renderer.
    //
    // In Catalog mode the switcher holds only Remote Compose players (Wasm has its own chip;
    // "Snapshot" alone isn't a destination).
    val selectLanes = if (componentBrowser) lanes.filter { it.value.startsWith("rc:") } else lanes
    val laneSelectHtml =
      if (selectLanes.size < 2) ""
      else
        selectLanes.joinToString(
          separator = "",
          prefix =
            "<select id=\"cp-lane-select\" class=\"cp-lane-select\" " +
              "aria-label=\"Switch renderer\" " +
              "title=\"Draw this preview with a different renderer\" " +
              "data-default=\"$defaultLane\" data-rc-default=\"$defaultRcBackend\"" +
              (if (bakedRcPlayer.isNotEmpty()) " data-rc-baked-player=\"$bakedRcPlayer\"" else "") +
              // Catalog mode has no renderer chip, so the menu must hold its selection to show
              // which renderer is drawing.
              (if (componentBrowser) " data-lane-state=\"1\"" else "") +
              ">" +
              "<option value=\"\" selected>Switch renderer…</option>",
          postfix = "</select>",
        ) { lane ->
          val disabledAttr = if (lane.enabled) "" else " disabled"
          // `<option>` carries no tooltip anywhere reliable, so an unavailable lane says so in the
          // label itself rather than in a `title` nobody sees.
          val text = if (lane.enabled) lane.label else "${lane.label} (unavailable)"
          "<option value=\"${lane.value}\"$disabledAttr>" +
            "${WebEscaping.htmlEscape(text)}</option>"
        }
    // A subtle link to the format-comparison page focused on this preview's Remote Compose lane; it
    // navigates away, so it isn't a chip.
    val comparePlayersHref =
      if (enabledRcPlayers.size < 2) ""
      else {
        val compareQuery =
          listOf(
              "format=rc",
              "preview=${WebEscaping.urlEncodeSegment(preview.id)}",
              linkQuery(token, linkSessionId, basePath, isPublic),
            )
            .filter { it.isNotEmpty() }
            .joinToString("&")
        "$basePath/compare?$compareQuery"
      }
    // The step out of the viewer: every full-page comparison surface for this preview, grouped
    // because each one leaves the page (and its overrides). Still a subtle grey affordance, now one
    // instead of several.
    val compareDestinations =
      listOfNotNull(
        specCompareHref?.let {
          Triple(
            "Spec diff",
            it,
            "Open the focused comparison page for this render and its imported design spec",
          )
        },
        parallelLayersHref
          .takeIf { it.isNotEmpty() }
          ?.let {
            // Named for the sibling when known. On a top-level site [parallelSource] is unreachable
            // but the server-joined layer diff survives, so the neutral wording remains. On a
            // viewer with both, this summary is the only resting place that names the sibling.
            val sibling = parallelSource?.label?.takeIf { name -> name.isNotBlank() }
            Triple(
              if (sibling == null) "Layer diff" else "$sibling layers",
              it,
              if (sibling == null) "Compare resolved layers across the paired catalogs"
              else
                "Compare resolved layers against $sibling — the fonts, tokens and insets a " +
                  "pixel comparison cannot report",
            )
          },
        comparePlayersHref
          .takeIf { it.isNotEmpty() }
          ?.let {
            Triple(
              "Compare players",
              it,
              "See every Remote Compose player's render of this screen side by side",
            )
          },
      )
    // A single destination stays an inline link; the menu appears only with several (e.g. a paired
    // RC preview).
    val compareMenuHtml =
      when (compareDestinations.size) {
        0 -> ""
        1 -> {
          val (text, href, title) = compareDestinations.first()
          "<a class=\"cp-format-link\" href=\"${WebEscaping.htmlEscape(href)}\" " +
            "title=\"${WebEscaping.htmlEscape(title)}\">" +
            "${WebEscaping.htmlEscape(text.lowercase())} →</a>"
        }
        else ->
          "<details class=\"cp-detail-menu\">" +
            "<summary class=\"cp-detail-menu-btn\">" +
            "<span class=\"cp-detail-menu-key\">Full comparisons</span>" +
            "<span class=\"cp-detail-caret\" aria-hidden=\"true\">\u25be</span>" +
            "</summary>" +
            "<div class=\"cp-detail-menu-panel\">" +
            "<nav class=\"cp-detail-menu-list\" aria-label=\"Full comparisons\">" +
            compareDestinations.joinToString("") { (text, href, title) ->
              "<a class=\"cp-detail-menu-item\" href=\"${WebEscaping.htmlEscape(href)}\" " +
                "title=\"${WebEscaping.htmlEscape(title)}\">" +
                "${WebEscaping.htmlEscape(text)}</a>"
            } +
            "</nav></div></details>"
      }
    val componentParametersHtml =
      if (!componentBrowser || preview.componentParameters.isEmpty()) ""
      else {
        val parameters =
          preview.componentParameters.joinToString("") { parameter ->
            val slot =
              if (parameter.composableSlot) "<span class=\"cp-source-property-kind\">slot</span>"
              else ""
            val default =
              if (parameter.hasDefault) "<span class=\"cp-source-property-default\">optional</span>"
              else ""
            "<li class=\"cp-source-property${if (parameter.composableSlot) " cp-source-property--slot" else ""}\">" +
              "<code><span class=\"cp-source-property-name\">${WebEscaping.htmlEscape(parameter.name)}</span>: " +
              "${WebEscaping.htmlEscape(parameter.type)}</code>$slot$default</li>"
          }
        "<template id=\"cp-source-properties\"><section class=\"cp-source-properties\" " +
          "aria-label=\"Component properties\"><h2>Properties</h2><ul>$parameters</ul></section></template>"
      }
    // The Spec lane's stage image: hidden and src-less until the lane is entered.
    // The Source panel: empty and `hidden` until the chip is pressed, then fetched from
    // `/usage/<id>` (a GitHub read on a cold cache). Server-rendered empty so the layout doesn't
    // jump on first open.
    val sourcePanelHtml =
      if (!usageAvailable) ""
      else
        "<div class=\"cp-source-panel\" id=\"cp-source-panel\" role=\"region\" " +
          "aria-label=\"Usage source\" hidden>$componentParametersHtml</div>"
    val specImg =
      if (specSurfaceUrl == null) ""
      else
        "<img id=\"cp-spec-img\" class=\"cp-spec-img\" hidden alt=\"" +
          "${WebEscaping.htmlEscape("$displayName — design spec")}\">"
    // The surface the Diff / Triptych / Slider views paint into, `hidden` until picked. Canvases
    // because `<cp-spec-compare>` normalises both frames to one pixel space first. Nothing is
    // fetched until a view is chosen.
    val specCompare =
      if (specSurfaceUrl == null) ""
      else {
        fun panel(kind: String, id: String, caption: String, description: String) =
          "<figure class=\"cp-spec-panel\" data-cp-spec-panel=\"$kind\">" +
            "<canvas id=\"$id\" aria-label=\"${WebEscaping.htmlEscape(description)}\"></canvas>" +
            "<figcaption>${WebEscaping.htmlEscape(caption)}</figcaption></figure>"
        "<div class=\"cp-spec-compare\" id=\"cp-spec-compare\" hidden " +
          "data-view=\"$SPEC_DEFAULT_VIEW\" " +
          "data-reference=\"${WebEscaping.htmlEscape(specSurfaceUrl)}\">" +
          panel("reference", "cp-spec-reference", "Spec", "Imported design spec") +
          panel("diff", "cp-spec-diff", "Diff", "Pixels where the render and the spec disagree") +
          panel("actual", "cp-spec-actual", "Render", "This preview's Compose render") +
          "<div class=\"cp-spec-wipe\">" +
          "<canvas id=\"cp-spec-wipe-canvas\" " +
          "aria-label=\"Spec on the left of the seam, Compose render on the right\"></canvas>" +
          "<label class=\"cp-spec-wipe-control\"><span>Spec</span>" +
          "<input id=\"cp-spec-wipe-range\" class=\"cp-spec-wipe-range\" type=\"range\" " +
          "min=\"0\" max=\"100\" value=\"50\" " +
          "aria-label=\"Wipe between the design spec and the Compose render\">" +
          "<span>Render</span></label></div>" +
          "</div>" +
          "<script type=\"application/json\" id=\"cp-spec-annotations\">" +
          encodeAnnotationPayload(AnnotationPayload(reference = referenceAnnotations)) +
          "</script>" +
          // Drives the panel above (views, surfaces, verdict). Emitted right after it because
          // `viewer.js` calls `window.cpSpecCompare` on lane entry. Renders nothing; `serve.css`
          // hides the tag.
          "<cp-spec-compare></cp-spec-compare>"
      }
    // The Source lane's hidden mode radio, joining the group for `?mode=source`, restore on load,
    // Back/Forward and `currentMode()`.
    val sourceModeInput =
      if (!usageAvailable) ""
      else
        "<input type=\"radio\" name=\"cp-mode\" value=\"source\" id=\"cp-source-toggle\" " +
          "tabindex=\"-1\">"
    val specModeInput =
      if (specSurfaceUrl == null) ""
      else
        "<input type=\"radio\" name=\"cp-mode\" value=\"spec\" id=\"cp-spec-toggle\" tabindex=\"-1\">"
    val isAppScreen = isScreenPreview(preview)
    // A Wear catalog gets no phone/foldable/tablet devices; the same system-id signal as the dark
    // stage decides "Wear".
    val isWearSystem = SystemDisplay.isWearOs(basePath.trim('/').ifBlank { sessionId ?: "" })
    val screenDeviceOptions =
      screenDevicesFor(isWearSystem).joinToString("\n                  ") { device ->
        val value = WebEscaping.htmlEscape(device.id)
        val label = WebEscaping.htmlEscape("${device.name} · ${device.kind} (${device.sizeDp})")
        "<option value=\"$value\">$label</option>"
      }
    // A static bundle replays baked PNGs, so controls that rebuild the /render URL
    // (device/locale/font scale/orientation, live stream) are disabled with a note. Theme stays
    // live when a Wasm app backs the session (it re-points the iframe's ?uiMode). Live daemon
    // sessions (canApplyOverrides) keep everything on.
    val staticSnapshot = !canApplyOverrides
    // Whether the server can produce a fresh overridden render at all: the snapshot lane re-renders
    // ([canApplyOverrides]) or a carried catalog daemon renders overrides on demand
    // ([canRenderOverrides]). When true the server-render controls are live before the Live toggle
    // is flipped.
    val overridesLive = canApplyOverrides || canRenderOverrides
    // Size / device / orientation: enabled whenever [overridesLive]; disabled with the note
    // otherwise.
    val serverDis = if (overridesLive) "" else " disabled"
    // "Live (stream)" keys off [hasLiveStream], not staticSnapshot: a trusted-catalog live session
    // serves baked snapshots yet offers the stream.
    val liveAuthBlocksStream = hasLiveStream && liveAuthPrompt != null
    val liveDis = if (hasLiveStream && !liveAuthBlocksStream) "" else " disabled"
    // Whether the Static⇄Live toggle has a lane to switch to (daemon stream [hasLiveStream] or Wasm
    // [wasmSrc]); disabled with the note otherwise.
    val liveToggleDis =
      if ((hasLiveStream || wasmSrc != null) && !liveAuthBlocksStream) "" else " disabled"
    val liveAuthTitle = liveAuthPrompt?.let { "Sign in with GitHub to enable Live preview." }
    // The tooltip says what pressing the chip does. This is only the opening text;
    // `updateLiveToggle()` re-derives it on every transition, and this matches its initial (static)
    // state.
    // The chip's opening label: the lane it opens on when another control can change lanes;
    // otherwise the stage state — "Snapshot" when there is a lane to enter (reads "Snapshot ▸
    // Live"), else "Live preview" (disabled invitation).
    val primaryLaneLabel =
      if (laneSelectHtml.isEmpty() && specChipHtml.isEmpty())
        if (liveToggleDis.isEmpty()) "Snapshot" else "Live preview"
      else lanes.firstOrNull { it.value == defaultLane }?.label ?: "Live preview"
    val liveToggleTitleAttr =
      " title=\"" +
        WebEscaping.htmlEscape(
          liveAuthTitle
            ?: if (liveToggleDis.isEmpty())
              "Static snapshot — click for the live, interactive preview"
            else "Static snapshot — this session has no live lane to switch to"
        ) +
        "\""
    // The verb half of the chip ("Java ▸ Live"), naming where a click goes so the chip reads as a
    // switch. `aria-hidden` so the accessible name stays the lane name (`aria-pressed` and the
    // tooltip carry the switch semantics). Empty when there is no lane to enter;
    // `updateLiveToggle()` keeps it in step.
    val liveToggleVerb =
      if (liveToggleDis.isEmpty())
        "            <span class=\"cp-live-toggle-verb\" id=\"cp-live-toggle-verb\" " +
          "aria-hidden=\"true\">▸ Live</span>\n"
      else ""
    val liveToggleButton =
      "<button type=\"button\" id=\"cp-live-toggle\" class=\"cp-live-toggle\" " +
        "aria-pressed=\"false\" " +
        // What the chip names when leaving the spec lane on a preview with no renderer combo (where
        // `laneLabelText()` has no options to read).
        "data-default-lane-label=\"${WebEscaping.htmlEscape(primaryLaneLabel)}\"" +
        "$liveToggleTitleAttr$liveToggleDis>\n" +
        "            <span class=\"cp-live-dot\" aria-hidden=\"true\"></span>\n" +
        "            <span id=\"cp-live-toggle-label\">" +
        "${WebEscaping.htmlEscape(primaryLaneLabel)}</span>\n" +
        liveToggleVerb +
        "          </button>"
    // When sign-in is the only thing between the visitor and the daemon lane, offer the sign-in as
    // a real link with the reason in its label, instead of a disabled button. It has no
    // `id="cp-live-toggle"`, so `liveToggle` is null and `updateLiveToggle()` skips it.
    val liveSignInLink = liveAuthPrompt?.let {
      "<a id=\"cp-live-signin\" class=\"cp-live-toggle cp-live-signin\" " +
        "href=\"${WebEscaping.htmlEscape(it.loginHref)}\" " +
        "title=\"Sign in with GitHub to enable Live preview. " +
        (if (it.restrictedToAllowedUsers) "This server allows named GitHub users only."
        else "Any GitHub account works.") +
        "\">\n" +
        "            <span class=\"cp-live-dot\" aria-hidden=\"true\"></span>\n" +
        "            <span>Live preview — sign in</span>\n" +
        "          </a>"
    }
    // Only when auth is what's blocking the stream; a pure static bundle keeps the disabled toggle.
    val liveToggleIsSignIn = liveAuthBlocksStream && liveSignInLink != null
    val liveToggleHtml = if (liveToggleIsSignIn) liveSignInLink!! else liveToggleButton
    // Controls the Wasm app also honours (uiMode, font scale, locale): live whenever the server can
    // render an override or a Wasm app backs the session.
    val wasmDis = if (overridesLive || wasmSrc != null) "" else " disabled"
    // The static-snapshot note shows only when overrides can't re-render server-side
    // ([overridesLive] false): a plain bundle, or a Wasm-only catalog where size/device/orientation
    // need a live server.
    // Watches don't rotate, so Wear screens get no Orientation control and the notes must not
    // mention one.
    val showOrientation = isAppScreen && !isWearSystem
    val serverOnlyOverrideNote =
      when {
        showOrientation -> "Device size &amp; Orientation need the live server. "
        isAppScreen -> "Device size needs the live server. "
        else -> "Size needs the live server. "
      }
    val snapshotOverrideList =
      when {
        showOrientation -> "device size, locale, font scale, orientation"
        isAppScreen -> "device size, locale, font scale"
        else -> "size, locale, font scale"
      }
    val snapshotNote =
      if (componentBrowser || spatialSceneUrl != null) ""
      else
        when {
          overridesLive -> ""
          wasmSrc != null ->
            "<div class=\"cp-note\">Pre-rendered snapshot — turn on <strong>Live preview</strong> to " +
              "interact. Day/Night, Font scale, Locale &amp; declared knob values apply in " +
              "the browser; " +
              serverOnlyOverrideNote +
              "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
          else ->
            "<div class=\"cp-note\">Pre-rendered snapshot — overrides (" +
              snapshotOverrideList +
              ") need the live server, not a published catalog. " +
              "<a href=\"$LOCAL_SERVER_DOCS\">Enable a local preview server.</a></div>"
        }
    // The stage's own invitation into the live lane, reusing the grid's `.cp-live-hint` badge
    // (`CatalogLive.ts`) with click wording. Rendered only when there is a lane to enter, never in
    // the component browser, and hidden until `updateLiveToggle()` reveals it (the click is wired
    // in `viewer.ts`).
    val stageLiveHint =
      if (componentBrowser || liveToggleDis.isNotEmpty()) ""
      else
        "<span class=\"cp-live-hint cp-stage-live-hint\" id=\"cp-stage-live-hint\" " +
          "aria-hidden=\"true\">click for live</span>"
    val backendLabel = WebEscaping.htmlEscape(snapshotBackend ?: "Snapshot")
    val liveLabel = WebEscaping.htmlEscape(liveBackend ?: "Live")
    // One Theme axis replacing separate Day/Night + app-theme controls: the defaults map to uiMode;
    // `theme:<provider>` maps to themeProvider and clears uiMode.
    //
    // A theme specimen withdraws the whole axis, including Day/Night: a uiMode differing from the
    // id's baked segment is routed to a fresh render (`CatalogLiveRouting.overridesAffectRender`),
    // which would redraw the specimen or mislabel it.
    val themeFixed = isThemeSpecimen(preview)
    val viewerDeclaredThemes = if (themeFixed) emptyList() else declaredThemes
    /**
     * Whether a theme choice can reach the pixels: a daemon or Wasm tier to re-render with, and not
     * a fixed-theme specimen. Hoisted because it also decides whether the pre-paint chrome script
     * may follow a remembered choice ([pageThemeScript]).
     */
    val themeChoiceApplies =
      !themeFixed &&
        ((!wearAlwaysDark && (overridesLive || wasmSrc != null)) ||
          (viewerDeclaredThemes.isNotEmpty() && overridesLive))
    /**
     * What the Theme control offers (built-ins, e.g. Dark alone on Wear, plus declared themes while
     * overrides are live), handed to the pre-paint script to validate a remembered choice.
     */
    val offeredThemes =
      (if (wearAlwaysDark) listOf("dark") else listOf("light", "dark")) +
        (if (overridesLive) viewerDeclaredThemes.map { "theme:${it.providerFqn}" } else emptyList())
    val themeSelectorHtml = run {
      val declaredThemes = viewerDeclaredThemes
      val themeDis = if (themeChoiceApplies) "" else " disabled"
      val providerDis = if (overridesLive) "" else " disabled"
      val grouped = declaredThemes.groupBy { it.group }
      val optionsOf: (List<ServeTheme>) -> String = { list ->
        list.joinToString("\n") { t ->
          val modeAttr = t.mode?.let { " data-theme-mode=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
          "<option value=\"theme:${WebEscaping.htmlEscape(t.providerFqn)}\"$modeAttr$providerDis>" +
            "${WebEscaping.htmlEscape(t.name)}</option>"
        }
      }
      val body = buildString {
        // Ungrouped themes first (flat), then one <optgroup> per declared group.
        grouped[null]?.let { append(optionsOf(it)).append('\n') }
        grouped
          .filterKeys { it != null }
          .forEach { (group, list) ->
            append("<optgroup label=\"${WebEscaping.htmlEscape(group!!)}\">")
              .append(optionsOf(list))
              .append("</optgroup>\n")
          }
      }
      val daySelected = if (viewerTheme != "dark") " selected" else ""
      val nightSelected = if (viewerTheme == "dark") " selected" else ""
      val defaults =
        if (wearAlwaysDark) "<option value=\"dark\"$nightSelected>Dark (Default)</option>"
        else
          "<option value=\"light\"$daySelected>Light (Default)</option>\n" +
            "            <option value=\"dark\"$nightSelected>Dark (Default)</option>"
      val providerOptions = body.trimEnd().let { if (it.isEmpty()) "" else "\n            $it" }
      // Visually removed but still the Theme axis's single state holder: viewer.js reads it for
      // every render (`activeThemeChoice`), the sticky script seeds it, and Back/Forward writes to
      // it. Only the chips ([themeBarHtml]) are shown.
      //
      // `data-default-theme` is the theme this preview is baked in, read by `pinsTheme` after the
      // sticky script overwrites `el.value`. Empty only when nothing names a theme; an untagged
      // sticker paired with a `__dark` twin is the light half ([ServeBakedTheme]), so
      // `uiMode=light` isn't written as a pin.
      // `data-theme-storage-key` is the catalog-scoped sticky key ([viewerThemeStickyScript]), so a
      // chip navigating to the twin card can record the pick directly without firing `change`
      // (which would start a render).
      // `tabindex="-1"` keeps the hidden select out of the tab order, which makes the `aria-hidden`
      // wrapper legitimate.
      val bakedThemeName =
        viewerTheme
          ?: ServeBakedTheme.resolve(preview.id, preview.theme) { id ->
              siblings.any { it.id == id }
            }
            ?.name
            ?.lowercase()
      """
        <span class="cp-modes-inputs" aria-hidden="true">
          <select id="cp-theme" class="cp-knob-theme" data-theme-active="0" data-default-theme="${bakedThemeName.orEmpty()}" data-theme-storage-key="${WebEscaping.htmlEscape(themeStorageKey(sessionId, basePath))}" data-has-declared-themes="${declaredThemes.isNotEmpty()}" data-fixed-theme="$themeFixed" tabindex="-1"$themeDis>
            $defaults$providerOptions
          </select>
        </span>
        """
        .trimIndent()
    }
    // The Theme bar: the grid's chips ([themePickerHtml]) on the viewer's toolbar. Values match the
    // select's options (`light` / `dark` / `theme:<providerFqn>`), so a chip click writes the
    // select and fires `change`, keeping every lane working. Day/Night labels; a dark-first system
    // offers Night alone.
    // Folds behind a title-bar toggle beyond [THEME_CHIPS_INLINE] chips, which otherwise ellipsise;
    // the toggle always shows the current theme's full name.
    // A built-in chip whose mode is baked as its own card ([ServeBakedTheme.twinIn]) links there
    // instead of re-rendering under `uiMode`, so the page's overlays describe the frame on screen.
    //
    // Withheld on a pinned revision and on a theme specimen ([themeFixed]).
    val themeTwinHrefs: Map<String, String> =
      if (pinned != null || themeFixed) emptyMap()
      else {
        val siblingIds = siblings.mapTo(HashSet()) { it.id }
        buildMap {
          for (mode in listOf(UiMode.LIGHT, UiMode.DARK)) {
            ServeBakedTheme.twinIn(preview.id, mode) { it in siblingIds }
              ?.let {
                put(mode.name.lowercase(), "$basePath/p/${WebEscaping.urlEncodeSegment(it)}$q")
              }
          }
        }
      }
    val themeBarHtml =
      themeChipsHtml(
          builtIns =
            if (wearAlwaysDark) listOf("dark" to "Dark")
            else listOf("light" to "Light", "dark" to "Dark"),
          declared = viewerDeclaredThemes,
          indent = "          ",
          twinHrefs = themeTwinHrefs,
        )
        .let {
          "<span class=\"cp-theme cp-theme-bar\" id=\"cp-theme-bar\" role=\"group\"" +
            " aria-label=\"Preview theme\">\n" +
            "          $it\n        </span>"
        }
    // Seeded from the baked lane, then kept in sync client-side (viewer-drawers.js mirrors the
    // pressed chip).
    val themeToggle =
      if (pinned != null)
        """
        <span class="cp-disabled-control" tabindex="0" aria-describedby="cp-pinned-controls-note"><button type="button" class="cp-drawer-toggle cp-axis-toggle" id="cp-theme-toggle" disabled aria-describedby="cp-pinned-controls-note"
          title="Pinned revision — theme overrides are not applied to published pixels">
          <span class="cp-toggle-label">Theme</span>
          <span class="cp-toggle-value" id="cp-theme-toggle-value">${if (viewerTheme == "dark") "Dark" else "Light"}</span>
        </button></span>
        """
          .trimIndent()
      else
        """
      <details class="cp-theme-menu">
        <summary class="cp-drawer-toggle cp-axis-toggle" id="cp-theme-toggle" aria-controls="cp-theme-bar">
          <span class="cp-toggle-label">Theme</span>
          <span class="cp-toggle-value" id="cp-theme-toggle-value">${if (viewerTheme == "dark") "Dark" else "Light"}</span>
          <span class="cp-theme-caret" aria-hidden="true">▾</span>
        </summary>
        <div class="cp-theme-menu-panel">$themeBarHtml</div>
      </details>
      """
          .trimIndent()
    // Inspection layers (`<cp-inspect-layers>`): what the frame is made of — accessibility focus
    // map, typography, theme attributes — drawn as boxes with numbered badges plus a legend, rather
    // than composited into the render.
    //
    // Each row is offered only when its host can produce the data. Published reference typography
    // is self-contained, so static bundles can inspect the Figma lane.
    val hasTypographyInspection =
      hasDesignAnnotations ||
        hasPublishedTypography ||
        referenceAnnotations.any { it.kind == AnnotationKind.TYPOGRAPHY }
    val inspectRows = buildString {
      if (hasSlotInspection)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-slots\" " +
            "data-cp-inspect=\"slots\" type=\"checkbox\"> Slots</label>\n"
        )
      if (hasA11yOverlay)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-a11y\" " +
            "data-cp-inspect=\"a11y\" type=\"checkbox\"> Accessibility</label>\n"
        )
      if (hasTypographyInspection) {
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" " +
            "id=\"cp-inspect-typography\" data-cp-inspect=\"typography\" type=\"checkbox\"> " +
            "Typography</label>\n"
        )
      }
      if (hasDesignAnnotations) {
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-theme\" " +
            "data-cp-inspect=\"theme\" type=\"checkbox\"> Theme attributes</label>\n"
        )
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-inspect\" id=\"cp-inspect-layout\" " +
            "data-cp-inspect=\"layout\" type=\"checkbox\"> Layout boxes</label>\n"
        )
      }
    }
    val inspectGroupHtml =
      if (inspectRows.isEmpty()) ""
      else
        """
            <div class="cp-overlays">
              <div class="cp-overlays-head">Inspect</div>
              ${inspectRows.trimEnd().prependIndent("              ").trimStart()}
            </div>
        """
          .trimIndent()
    // The legend panel, populated by `<cp-inspect-layers>` and hidden until a layer is on;
    // server-rendered empty so the stage doesn't jump.
    val inspectLayerHtml =
      if (inspectRows.isEmpty()) ""
      else "<div class=\"cp-inspect-layer\" id=\"cp-inspect-layer\"></div>"
    val inspectLegendHtml =
      if (inspectRows.isEmpty()) ""
      else
        "<div class=\"cp-inspect-legend\" id=\"cp-inspect-legend\" role=\"region\" " +
          "aria-label=\"Inspection legend\" hidden></div>" +
          // Fills the layer and legend from the frame on screen; emitted after both. Renders
          // nothing; `serve.css` hides the tag.
          "<cp-inspect-layers></cp-inspect-layers>"
    // Live overlay toggles (touch visualization), composited by the daemon onto stream frames, so
    // offered only when a Live Compose stream exists. Rendered enabled: ticking one on the static
    // snapshot switches into Live with the overlay in the initial overrides. `$liveDis` greys them
    // when the stream is behind sign-in, matching `syncOverlayToggles()`. `cp-overlay` marks them
    // for the JS collector.
    val liveOverlaysHtml =
      if (hasLiveStream)
        """
            <div class="cp-overlays">
              <div class="cp-overlays-head">Overlays (Live Compose)</div>
              <label class="cp-live-row"><input class="cp-overlay" id="cp-touchOverlay" type="checkbox"$liveDis> Show touches</label>
            </div>
        """
          .trimIndent()
      else ""
    val overlaysHtml =
      if (inspectGroupHtml.isEmpty() && liveOverlaysHtml.isEmpty()) ""
      else
        """
        <details class="cp-group" data-cp-group="overlays">
          <summary>Overlays</summary>
          <div class="cp-group-body">
            $inspectGroupHtml
            $liveOverlaysHtml
          </div>
        </details>
        """
          .trimIndent()
    // Detected-feature controls, shown only for previews that support the feature, routed like
    // knobs via onKnobChanged (`cp-feature`) and disabled unless the host can render an override:
    //  - "Keyboard focus" for `@FocusedPreview` (`focus=0`: focus the first focusable and draw the
    // overlay). Both daemon backends.
    //  - "Show gesture hints" for `@GestureHintPreview` (`gestures=true`), only on Android-backed
    // sessions ([gesturesRenderable]).
    val featureDaemonDis = if (canApplyOverrides || canRenderOverrides) "" else " disabled"
    val showGestureRow = preview.supportsGestures && gesturesRenderable
    val featureRows = buildString {
      if (preview.supportsFocus)
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-feature\" id=\"cp-focus\" " +
            "type=\"checkbox\"$featureDaemonDis> Keyboard focus</label>\n"
        )
      if (showGestureRow) {
        append(
          "<label class=\"cp-live-row\"><input class=\"cp-feature\" id=\"cp-gestures\" " +
            "type=\"checkbox\"$featureDaemonDis> Show gesture hints</label>\n"
        )
        // Firing the gesture, not just hinting: double pinch and wrist turn are sensor events with
        // no pointer equivalent, so the daemon invokes the registered handler
        // (`renderNow.overrides.gestures.invoke`). Labelled with the wearer's gesture names.
        // Buttons because an invocation is a one-shot event; `viewer.ts` reads the hidden input
        // once per render and clears it.
        append(
          // Indented to align once interpolated into the `trimIndent()` block; this line's
          // indentation affects the stripped minimum.
          "              <div class=\"cp-live-row\"><span class=\"cp-feature-label\">Fire a " +
            "gesture" +
            "</span> " +
            // `cp-bg-btn` is the viewer's outlined chip style, including its disabled state.
            "<button type=\"button\" class=\"cp-bg-btn cp-gesture-invoke\" " +
            "data-gesture=\"primary\"$featureDaemonDis>Double pinch</button> " +
            "<button type=\"button\" class=\"cp-bg-btn cp-gesture-invoke\" " +
            "data-gesture=\"dismiss\"$featureDaemonDis>Wrist turn</button>" +
            "<input type=\"hidden\" id=\"cp-gesture-invoke\" value=\"\">" +
            "</div>\n"
        )
      }
    }
    val featureControlsHtml =
      if (componentBrowser || featureRows.isEmpty()) ""
      else
        """
        <details class="cp-group" data-cp-group="features">
          <summary>Detected features</summary>
          <div class="cp-group-body">
            <div class="cp-overlays">
              <div class="cp-overlays-head">Detected features</div>
              $featureRows
            </div>
          </div>
        </details>
        """
          .trimIndent()
    // Catalog screens get a handful of recognisable device profiles (phones/foldable/tablet, or
    // Wear watch shapes) instead of min/max constraints. The select keeps #cp-device, so the
    // override transport and deep links are unchanged.
    val orientationControlHtml =
      if (showOrientation)
        """
        <label>Orientation
          <select id="cp-orientation"$serverDis>
            <option value="">(device default)</option>
            <option value="portrait">Portrait</option>
            <option value="landscape">Landscape</option>
          </select>
        </label>
        """
          .trimIndent()
          .prependIndent("    ") + "\n"
      else ""
    val sizeControlsHtml =
      if (componentBrowser && !isAppScreen) ""
      else if (isAppScreen)
        """
        <details class="cp-group" data-cp-group="size">
          <summary>Size</summary>
          <div class="cp-group-body">
            <label>Device size
              <select id="cp-device"$serverDis>
                <option value="">(preview default)</option>
                SCREEN_DEVICE_OPTIONS_PLACEHOLDER
              </select>
            </label>
        ORIENTATION_CONTROL_PLACEHOLDER
          </div>
        </details>
        """
          .trimIndent()
          .replace("ORIENTATION_CONTROL_PLACEHOLDER\n", orientationControlHtml)
          .replace("\n", "\n          ")
          .replace("SCREEN_DEVICE_OPTIONS_PLACEHOLDER", screenDeviceOptions)
      else
        """
        <details class="cp-group" data-cp-group="size">
          <summary>Size</summary>
          <div class="cp-group-body">
            <div class="cp-size">
              <label>Size mode
                <select id="cp-sizeMode"$serverDis>
                  <option value="">(default)</option>
                  <option value="fixed">Fixed size</option>
                  <option value="max">Max</option>
                  <option value="min">Min</option>
                  <option value="within">Within (min–max)</option>
                </select>
              </label>
              <div class="cp-size-row" id="cp-size-fixed" hidden>
                <label>Width (dp)<input id="cp-fixedW" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
                <label>Height (dp)<input id="cp-fixedH" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
              </div>
              <div class="cp-size-row" id="cp-size-min" hidden>
                <label>Min width (dp)<input id="cp-minW" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
                <label>Min height (dp)<input id="cp-minH" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
              </div>
              <div class="cp-size-row" id="cp-size-max" hidden>
                <label>Max width (dp)<input id="cp-maxW" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
                <label>Max height (dp)<input id="cp-maxH" type="number" min="1" step="1" inputmode="numeric" placeholder="auto" autocomplete="off"$serverDis></label>
              </div>
            </div>
          </div>
        </details>
        """
          .trimIndent()
          .replace("\n", "\n          ")
    // The overrides drawer is opt-in: the preview leads at every width, and the toggle opens the
    // controls only when they are needed.
    val controlsToggle =
      "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-controls-toggle\" " +
        "aria-expanded=\"false\" aria-controls=\"cp-controls\">⚙ ${if (componentBrowser) "Controls" else "Overrides"}</button>"
    // Stage background follows the preview's theme, dark by default for dark-first systems (see
    // `.cp-viewer[data-bg-theme] .cp-stage`). Separate from data-card-theme; the viewer JS re-syncs
    // it on a uiMode change.
    val bgThemeAttr = viewerTheme?.let { " data-bg-theme=\"$it\"" } ?: ""
    // The component's renders as a subtree of the catalog tree filtered to this component, the one
    // on screen marked. Empty for a component with no second render.
    // The component's recordings, as a directory beside the samples one. Built here rather than by
    // the handler ([componentDirectories]) since captures ride on the preview
    // (`ServePreview.motion`). Covers every render of the component, so a capture declared on
    // `Pressed` is discoverable from the default page. The stage's Motion chip is unchanged.
    val axesTree =
      componentSubtreeHtml(
        preview,
        siblings,
        basePath,
        q,
        viewerDarkFirst,
        // Withheld from the component browser for the same reason its comparison chips are: that
        // chrome is a reading surface, and a route into another catalog is one it does not offer.
        directories = if (componentBrowser) emptyList() else componentDirectories,
        // No recordings under a pin (the publish may lack those `?motion=` ids) or in Catalog mode
        // (no motion lane).
        includeMotion = !componentBrowser && pinned == null,
      )
    val navDrawer =
      navDrawerHtml(
        preview,
        siblings,
        basePath,
        q,
        viewerTheme,
        axesTree,
        viewerDarkFirst,
        navThumbHash,
      )
    val navToggle =
      if (navDrawer.isEmpty()) ""
      else
        "<button type=\"button\" class=\"cp-drawer-toggle\" id=\"cp-nav-toggle\" " +
          "aria-expanded=\"false\" aria-controls=\"cp-nav\">☰ Components</button>"
    val browserComponentNav =
      if (!componentBrowser) ""
      else {
        val seeds = LinkedHashMap<String, ServePreview>()
        siblings
          .filter { it.renderFailure == null }
          .forEach { candidate ->
            val key = componentKey(candidate)
            val existing = seeds[key]
            val candidateIsDefault = !isNonDefaultState(candidate) && !hasNonDefaultProps(candidate)
            val existingIsDefault =
              existing != null && !isNonDefaultState(existing) && !hasNonDefaultProps(existing)
            if (existing == null || (candidateIsDefault && !existingIsDefault))
              seeds[key] = candidate
          }
        val components = groupPreviews(seeds.values.toList()).map { it.rendered(viewerDarkFirst) }
        val currentIndex = components.indexOfFirst { componentKey(it) == componentKey(preview) }
        fun linkAt(index: Int, relation: String, arrow: String): String {
          val target = components.getOrNull(index) ?: return ""
          val href = "$basePath/p/${WebEscaping.urlEncodeSegment(target.id)}$q"
          return "<a class=\"cp-browser-sibling cp-browser-sibling-$relation\" href=\"$href\" " +
            "rel=\"$relation\"><span aria-hidden=\"true\">$arrow</span>" +
            "<span>${WebEscaping.htmlEscape(previewDisplayName(target))}</span></a>"
        }
        if (currentIndex < 0) ""
        else
          "<nav class=\"cp-browser-siblings\" aria-label=\"Adjacent components\">" +
            linkAt(currentIndex - 1, "prev", "←") +
            linkAt(currentIndex + 1, "next", "→") +
            "</nav>"
      }
    val browserVariantLabel =
      if (!componentBrowser) ""
      else
        buildList {
            preview.state?.takeUnless { it == "default" }?.let { add(stateLabel(it)) }
            propsLabel(preview.props).takeIf { it.isNotBlank() }?.let { add(it) }
          }
          .joinToString(" · ")
    val browserBreadcrumb =
      if (!componentBrowser) ""
      else {
        val parts = buildList {
          val catalogLabel = catalogTitle?.takeIf { it.isNotBlank() } ?: "Components"
          add("<a href=\"$basePath/$q\">${WebEscaping.htmlEscape(catalogLabel)}</a>")
          preview.section
            ?.takeIf { it.isNotBlank() }
            ?.let { add("<span>${WebEscaping.htmlEscape(it)}</span>") }
          preview.group
            ?.takeIf { it.isNotBlank() }
            ?.let { add("<span>${WebEscaping.htmlEscape(it)}</span>") }
          add(
            "<span${if (browserVariantLabel.isBlank()) " aria-current=\"page\"" else ""}>" +
              "${WebEscaping.htmlEscape(displayName)}</span>"
          )
          if (browserVariantLabel.isNotBlank()) {
            add("<span aria-current=\"page\">${WebEscaping.htmlEscape(browserVariantLabel)}</span>")
          }
        }
        "<nav class=\"cp-browser-breadcrumb\" aria-label=\"Breadcrumb\">" +
          parts.joinToString("<span class=\"cp-browser-separator\" aria-hidden=\"true\">›</span>") +
          "</nav>"
      }
    // Left to right: the renderer chip + alternatives combo, the design-spec chip, the "compare
    // elsewhere" links, then the SVG format toggle.
    // The renderer control: the chip names the renderer in use and toggles live; the combo chooses
    // another. Both are driven from one lane value by `syncLaneSelect`, so they are joined into one
    // segmented pill, with a native `<select>` sitting invisibly over the caret segment (keeping
    // touch pickers, keyboard behaviour, and the ids `viewer.js`, `keyboardNavigation.ts` and the
    // harness use).
    //
    // Not joined in the component browser, which has no chip; the combo keeps its label and width
    // there.
    val rendererControl =
      when {
        componentBrowser -> laneSelectHtml
        // The sign-in variant is an anchor to GitHub, not a renderer control, so it isn't joined to
        // the caret: its dashed outline marks it as an action, and it must not sit inside
        // `role="group" aria-label="Renderer"`.
        liveToggleIsSignIn ->
          listOf(liveToggleHtml, laneSelectHtml).filter { it.isNotBlank() }.joinToString("\n")
        liveToggleHtml.isBlank() || laneSelectHtml.isBlank() ->
          listOf(liveToggleHtml, laneSelectHtml).filter { it.isNotBlank() }.joinToString("\n")
        else ->
          "<span class=\"cp-renderer\" role=\"group\" aria-label=\"Renderer\">" +
            liveToggleHtml +
            "<span class=\"cp-renderer-more\">" +
            "<span class=\"cp-renderer-caret\" aria-hidden=\"true\">\u25be</span>" +
            laneSelectHtml +
            "</span></span>"
      }
    val primaryControls =
      if (spatialSceneUrl != null)
        "<span class=\"cp-spatial-mode\">WebGL spatial · headset mode available over HTTPS</span>"
      else
        listOf(
            browserPreviewTab,
            rendererControl,
            specGroupHtml,
            sourceChipHtml,
            motionChipHtml,
            compareMenuHtml,
            specSelector,
            motionSelector,
            // The View group: controls that change the artefact on stage (vector export, exploded
            // projection, the matched raster), clustered so the row wraps between groups rather
            // than through one and controls never change neighbours
            // (`docs/design/COMPARE_NAVIGATION.md`, F1). `Transparent` and `Fit width` live in the
            // Overrides panel ([stageViewGroupHtml]). Collapses away when empty.
            listOf(svgFmtToggle, explodeToggle, svgMatch)
              .filter { it.isNotBlank() }
              .let {
                if (it.isEmpty()) ""
                else
                  "<span class=\"cp-view-group\" role=\"group\" " +
                    "aria-label=\"How the preview is shown\">" +
                    "<span class=\"cp-view-group-label\" aria-hidden=\"true\">View</span>" +
                    it.joinToString("\n") +
                    "</span>"
              },
          )
          .filter { it.isNotBlank() }
          .joinToString("\n")
    // Stage presentation in the panel: `Transparent` and `Fit width`, which change presentation,
    // not output, and are set rarely. Moved here, not duplicated. Open by default
    // (`<cp-group-memory>` remembers folding).
    // Not on a spatial preview: the opaque WebGL canvas (`alpha: false`) hides the checkerboard,
    // and the zoom handler never sizes `cp-spatial-view`.
    val stageViewGroupHtml =
      if (spatialSceneUrl != null) ""
      else
        "<details class=\"cp-group\" data-cp-group=\"stage-view\" open>" +
          "<summary>View</summary>" +
          "<div class=\"cp-group-body\">" +
          "<div class=\"cp-stage-view-row\">" +
          bgPickerHtml("Show the transparent checkerboard behind the preview") +
          "<button type=\"button\" class=\"cp-bg-btn cp-zoom-toggle\" aria-pressed=\"false\" " +
          "title=\"Show the preview at full width instead of fitting it to the screen\">" +
          "Fit width</button>" +
          "<label class=\"cp-bg-btn cp-backdrop-btn\" " +
          "title=\"Choose a local scene to place behind a transparent additive preview\">" +
          "<span id=\"cp-backdrop-label\">Choose backdrop</span>" +
          "<input id=\"cp-backdrop-file\" type=\"file\" accept=\"image/*\" hidden></label>" +
          "<button type=\"button\" id=\"cp-backdrop-clear\" class=\"cp-bg-btn\" hidden>" +
          "Clear backdrop</button>" +
          "</div></div></details>"
    val pinnedControlsNote =
      if (pinned == null) ""
      else
        "<span class=\"cp-pinned-controls-note\" id=\"cp-pinned-controls-note\" role=\"note\">" +
          "Pinned revision: Theme overrides are not applied; Source, SVG, and 3D use the " +
          "current catalog and are unavailable.</span>"
    // Both or neither: a timeline the visitor cannot click through to an old render is worse than
    // no timeline, so a missing repo suppresses the whole feature rather than half of it.
    val historyAttrs =
      if (!historyManifestUrl.isNullOrBlank() && !historyRepo.isNullOrBlank()) {
        " data-history-url=\"${WebEscaping.htmlEscape(historyManifestUrl)}\"" +
          " data-history-repo=\"${WebEscaping.htmlEscape(historyRepo)}\""
      } else if (historyLocalRenders && !historyInlineJson.isNullOrBlank()) {
        // Project-mode twin of the pair above: entries point back at this server, which reads bytes
        // from the local object store by sha. `{blob}` is substituted client-side.
        " data-history-blob-url=\"" +
          WebEscaping.htmlEscape("$basePath/history/render/{blob}.png$q") +
          "\""
      } else ""
    // The revision control, and the attribute that pins the pixels: `viewer.js` appends `at=<sha>`
    // to every render request it builds.
    val revisionBaseQuery =
      listOf(linkQuery(token, linkSessionId, basePath, isPublic), revisionQuery)
        .filter { it.isNotBlank() }
        .joinToString("&")
    val revisionHref: (String?) -> String = { pin ->
      withPin("$basePath/p/$idSeg${querySuffix(revisionBaseQuery)}", pin)
    }
    // What `<cp-revision-runs>` needs: the runs lane and the render URL per run head, built from
    // the unpinned query since the element appends its own `at=<sha>`.
    val runsAttrs =
      " data-runs-url=\"${WebEscaping.htmlEscape("$basePath/api/render-runs/$idSeg$q")}\"" +
        " data-render-url=\"${WebEscaping.htmlEscape("$basePath/render/$idSeg.png$q")}\""
    val revisionMenu =
      revisionsHtml(
        revisions,
        includeBanner = false,
        runsAttrs = runsAttrs,
        runsInlineJson = revisionRunsInlineJson.orEmpty(),
        hrefFor = revisionHref,
      )
    val revisionBanner = revisionBannerHtml(revisions, revisionHref)
    val pinnedAttr =
      revisions.pinned?.let { " data-pinned-at=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
    // The publish this page was assembled from, passed as data because the viewer builds its own
    // frame URL ([ServeCacheGeneration]). Emitted beside the pin; the script chooses between them
    // like [assetQuery].
    val generationAttr =
      revisions.generation?.let { " data-generation=\"${WebEscaping.htmlEscape(it)}\"" }.orEmpty()
    // The axes the URL named that this page withheld, for `hydrateFromUrl`. Sorted for stable
    // markup; a JSON array because knob keys may contain commas.
    val unseededAttr =
      unseededOverrides
        .takeIf { it.isNotEmpty() }
        ?.let { axes ->
          val json = JsonArray(axes.sorted().map { JsonPrimitive(it) }).toString()
          " data-unseeded-overrides=\"${WebEscaping.htmlEscape(json)}\""
        }
        .orEmpty()
    // `</script>` inside a JSON payload would end the element early, so the only sequence that can
    // break out is neutralised. The payload itself is server-built from the catalog's own manifest.
    val historyInlineHtml =
      historyInlineJson
        ?.takeIf { it.isNotBlank() }
        ?.let {
          "<script type=\"application/json\" id=\"cp-history-data\">" +
            it.replace("</", "<\\/") +
            "</script>"
        }
        .orEmpty()
    val modeInputs =
      listOf(
          "<input type=\"radio\" name=\"cp-mode\" value=\"png\" id=\"cp-mode-png\" tabindex=\"-1\" checked>",
          "<input type=\"radio\" name=\"cp-mode\" value=\"live\" id=\"cp-live\" tabindex=\"-1\"$liveDis>",
          wasmModeInput,
          rcModeInput,
          rcWasmModeInput,
          specModeInput,
          motionModeInput,
          sourceModeInput,
        )
        .filter { it.isNotBlank() }
        .joinToString("\n")
    // `format-compare.js` holds the comparison primitives (content-box normalisation, edge-tolerant
    // score, delta map) used by both the SVG/PNG fidelity toggle and the spec lane's views.
    // `<cp-spec-compare>` publishes `window.cpSpecCompare` for `viewer.js`; the components bundle
    // is emitted above both.
    val compareScriptTags =
      listOfNotNull(
          compareScorerTag().takeIf {
            spatialSceneUrl == null &&
              ((hasSvgExport && !componentBrowser) || specSurfaceUrl != null)
          }
        )
        .joinToString("") { "$it\n      " }
    // CodeMirror (the playground's Kotlin grammar) loads only on pages that can offer source, to
    // avoid its ~114 kB elsewhere. viewer.js paints a plain `<pre><code>` first, so a failed asset
    // still leaves readable source.
    val sourceCodeStylesheet =
      if (usageAvailable && spatialSceneUrl == null)
        "<link rel=\"stylesheet\" href=\"${assetHref("codemirror.css")}\">\n      "
      else ""
    val sourceCodeScriptTag =
      if (usageAvailable && spatialSceneUrl == null) "${scriptTag("codemirror.js")}\n      " else ""
    // The provenance row (source / playground / report an issue / figma spec) sits above the export
    // bar rather than under the title.
    //
    // Emitted in Catalog mode too: the developer entries are null there, leaving the catalog report
    // (the mode's only reporting affordance); the row omits itself when that is null as well.
    // A preview declaring the A2UI `document` knob can be opened in the A2UI playground; not in
    // Catalog mode.
    val a2uiPlaygroundHref =
      if (componentBrowser || a2uiDocumentKnob(preview) == null) null
      else {
        val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
        "$basePath/a2ui?preview=${java.net.URLEncoder.encode(preview.id, "UTF-8")}" +
          q.removePrefix("?").takeIf { it.isNotEmpty() }?.let { "&$it" }.orEmpty()
      }
    val previewLinks =
      previewLinksHtml(
        sourceHref,
        preview.sourceFile,
        reportIssue,
        figmaSpec,
        playgroundHref,
        executableBundleHref,
        parallelLayersHref,
        a2uiPlaygroundHref,
      )
    // Every disclosure the page has, grouped at the end of the identity row and ordered as their
    // surfaces read (component list, axes, theme chips, revision, render history, overrides drawer
    // last). A closed one still names its current value. The `<cp-render-history>` tag is declared
    // here; it draws nothing when the timeline is too short.
    val historyMenu = if (historyAttrs.isEmpty()) "" else "<cp-history-menu></cp-history-menu>"
    val headToggles =
      listOf(
          navToggle,
          themeToggle,
          revisionMenu,
          historyMenu,
          controlsToggle.takeIf { spatialSceneUrl == null }.orEmpty(),
        )
        .filter { it.isNotBlank() }
    val headTogglesHtml =
      if (headToggles.isEmpty()) ""
      else "\n        <div class=\"cp-head-toggles\">${headToggles.joinToString("")}</div>"
    val browserVariant =
      if (!componentBrowser) ""
      else if (browserVariantLabel.isBlank()) ""
      else "<p class=\"cp-browser-variant\">" + WebEscaping.htmlEscape(browserVariantLabel) + "</p>"
    // The component's authored caption (`@CatalogComponent(caption = …)`) under its name. Blank
    // when none.
    val captionHtml =
      preview.caption
        ?.takeIf { it.isNotBlank() }
        ?.let { "<p class=\"cp-preview-caption\">${WebEscaping.htmlEscape(it)}</p>" }
        .orEmpty()
    // Title, trust badge, id and view tally on one baseline-aligned row.
    // The compare strip under the workspace; withheld from the component browser like its
    // comparison chips.
    val comparisonStrip =
      if (componentBrowser || componentVariants.isEmpty()) ""
      else
        comparisonStripHtml(
          variants = componentVariants,
          currentPreviewId = preview.id,
          componentId = ServeIssueReport.componentIdFor(preview),
          // The preview's display label, matching the `<h1>`, not the `componentKey` slug.
          componentName = label,
          // Named for what the rows stand opposite: the per-variant published design comparison.
          baselineLabel = specProviderLabel ?: "Design reference",
          catalogName = catalogName.ifBlank { "This catalog" },
          basePath = basePath,
          q = q,
          assetQ = assetQuery(q, revisions),
          // …and the paired catalog beside it, opened on the lane's default source so the strip
          // follows the source picker.
          parallelLabel = specSources.firstOrNull { it.id == "parallel" }?.label,
          defaultSource = primarySpecSource?.id ?: "kit",
        )
    val body =
      """
      $sourceCodeStylesheet${if (browserBreadcrumb.isBlank()) "" else "$browserBreadcrumb\n      "}<div class="cp-preview-head">
        <h1 class="cp-head cp-preview-title">$label${compactTrustBadge(trust)}</h1>
        ${if (componentBrowser) "" else "<code class=\"cp-preview-id\" title=\"$idText\">$idText</code>"}
        ${if (componentBrowser) "" else viewerViewCountHtml(engagement.views)}$headTogglesHtml
      </div>${if (captionHtml.isBlank()) "" else "\n      $captionHtml"}${if (browserVariant.isBlank()) "" else "\n      $browserVariant"}
      $revisionBanner${degradeBanner(degradations)}$issueRows
      <div class="cp-preview-primary" aria-label="Preview renderer">
      $primaryControls${if (pinnedControlsNote.isBlank()) "" else "\n        $pinnedControlsNote"}
        <span class="cp-mode-hint" id="cp-mode-hint"></span>
        <span class="cp-modes-inputs" aria-hidden="true">
      $modeInputs
        </span>
      </div>
      $historyInlineHtml
      <div class="cp-viewer"$bgThemeAttr$alwaysDarkAttr$irReplayAttr$replayThemesAttr data-preview-id="$idText" data-mode="snapshot" data-modes="$modes" data-static-snapshot="$staticSnapshot" data-can-render-overrides="$canRenderOverrides" data-snapshot-backend="$backendLabel" data-live-backend="$liveLabel" data-render-density="${renderDensityAttr(renderDensity)}" data-fold-scope="${foldStorageScope(sessionId, basePath)}"$sourceLaneAttr$unseededAttr$wasmAttr$rcAttr$historyAttrs$pinnedAttr$generationAttr>
        $navDrawer
        <div class="cp-stage"><cp-backend-badge class="cp-backend" id="cp-backend" role="status" aria-live="polite"></cp-backend-badge><img id="cp-img" alt="$label"><canvas id="cp-canvas" hidden></canvas>${spatialSceneUrl?.let { "<cp-spatial-view scene-url=\"${WebEscaping.htmlEscape(it)}\" label=\"$label\"></cp-spatial-view>" }.orEmpty()}$rcCanvas$wasmFrame$rcWasmFrame$specImg$motionImg$motionPlayer$sourcePanelHtml$specCompare$inspectLayerHtml$stageLiveHint<div class="cp-error" id="cp-error" role="alert" hidden></div></div>
        $inspectLegendHtml
        <div class="cp-controls" id="cp-controls">
          <!-- No "Appearance" group. Its only ever-visible control was a Background select
               offering "(default) / Clear (crisp outline)" — which read as a duplicate of the
               viewer bar's **Transparent** toggle: same word, same apparent job, two places, one
               of them buried behind a drawer. With that gone the group held nothing but the
               visually-hidden Theme state below, so an empty collapsible card would have sat at
               the top of every viewer's panel; the group goes with the control.

               Neither affordance is lost. Transparent still shows a preview's real alpha — from
               the View group at the top of this panel, which is where it now lives — and
               stripping a preview's *authored* background is still `background=clear` on /render
               (and the VS Code extension's own override): the authoring lane, which is where it
               belongs, rather than the reading one. The duplication that removed the select is
               what still keeps it removed; there is one control, and this panel is now where it
               is.

               The Theme select stays in the panel, outside any group: it is `aria-hidden` and out
               of the tab order, but it is the Theme axis's single state holder — viewer.js reads
               it on every render and Back/Forward hydration writes to it — so it has to remain in
               the DOM. The visible Theme control is the chip row on the viewer bar. -->
          $stageViewGroupHtml
          $themeSelectorHtml
          $sizeControlsHtml
          ${if (componentBrowser) "" else exportShapeGroupsHtml(hasScrollExport, hasSvgExport)}
          <details class="cp-group" data-cp-group="locale">
            <summary>Locale &amp; text</summary>
            <div class="cp-group-body">
              <label>Locale
                <input id="cp-localeTag" type="text" list="cp-localeTag-list" placeholder="e.g. en-GB, zh-Hant-TW" autocomplete="off"$wasmDis>
                <!-- A datalist, not a fixed <select>: the presets (pseudolocales, RTL, common
                     tags) drop down for quick picking, but any valid BCP-47 tag the server
                     accepts can still be typed in — so this is the OPEN form of the same value
                     set an author declares with `previewOverrideChoice`, rendered through the
                     same helper rather than hand-written twice. -->
                <datalist id="cp-localeTag-list">
                  ${datalistOptionsHtml(LOCALE_PRESETS, indent = "                  ")}
                </datalist>
              </label>
              <label>Font scale: <span id="cp-fontScale-val">default</span>
                <input id="cp-fontScale" type="range" min="0.5" max="2.0" step="0.1" value="1.0"$wasmDis>
              </label>
            </div>
          </details>
          $overlaysHtml
          $featureControlsHtml
          ${overrideKnobsHtml(preview, canApplyOverrides || canRenderOverrides, wasmSrc != null, requestOverrides)}
          ${if (componentBrowser) "" else remoteComposeKnobsHtml(preview, canApplyOverrides || canRenderOverrides || hasRcWasm, requestOverrides)}
          <div class="cp-status" id="cp-status"></div>
        </div>
      </div>$comparisonStrip
      <!-- Export remains below the workspace; renderer selection is kept beside the preview
           heading so it is visible before a tall stage. The export bar is a SIBLING of the note
           column rather than a child: the note is prose and reads better at `.cp-below`'s measure,
           while the bar has to run the full content width to stay on one line. -->
      <div class="cp-below">
        $snapshotNote
      </div>$previewLinks
      ${if (spatialSceneUrl == null) downloadLinksHtml(hasSvgExport) else ""}${if (browserComponentNav.isBlank()) "" else "\n      $browserComponentNav"}
      <!-- Backdrop shown behind an open drawer on mobile (drawers become bottom sheets there);
           tapping it dismisses the sheet. Inert on desktop. -->
      <div class="cp-scrim" id="cp-scrim" aria-hidden="true"></div>
      <!-- Remembers which control drawers this visitor left open (`cp-grp.<id>` per
           `details.cp-group[data-cp-group]`). Renders nothing; `serve.css` hides the tag. -->
      <cp-group-memory></cp-group-memory>
      <!-- Resolve a deep-linked or remembered theme and publish the design-score baseline before
           the component bundle upgrades the comparison control. -->
      <script>${viewerThemeStickyScript(themeStorageKey(sessionId, basePath))}</script>
      ${componentScriptTags("viewer")}
      <!-- The viewer's drawers, the phone row order, the theme toggle's value and the component
           filter. Renders nothing; `serve.css` hides the tag. -->
      <cp-viewer-drawers></cp-viewer-drawers>
      ${presenceScriptTag(presenceUrl)}
      $compareScriptTags$sourceCodeScriptTag${if (spatialSceneUrl == null) scriptTag("viewer.js") else scriptTag("spatial-view.js")}$browserTabsScript
      """
        .trimIndent()
        .lineSequence()
        .joinToString("\n") { it.trimEnd() }
    return document(
      changelogHref = changelogHref,
      title = "$displayName — compose-preview",
      body = body,
      unfurlTitle = displayName,
      unfurlDescription = "Compose preview for $displayName",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb =
        crumbHtml(
          "$basePath/$q",
          catalogTitle?.takeIf { it.isNotBlank() } ?: "Previews",
          "Component",
        ),
      themeCss = themeCss,
      siteName = catalogName,
      themeStorageKey = themeStorageKey(sessionId, basePath),
      themeChoiceApplies = themeChoiceApplies,
      offeredThemes = offeredThemes,
      declaredThemes = if (overridesLive) viewerDeclaredThemes else emptyList(),
      // Only the `camaelon-js` lane paints in this document's canvas, and it only exists when the
      // preview carries a captured document.
      rcFonts = hasRemoteComposeDoc,
      componentBrowser = componentBrowser,
      interfaceModeControl = true,
    )
  }

  /**
   * A drawer row's pixel URL: the prebaked thumbnail lane when available, else the plain render.
   * The same `?thumb=<hash>` shape as the grid's `renderSrc`, so both address thumbnails
   * identically.
   */
  private fun navThumbSrc(
    basePath: String,
    previewId: String,
    q: String,
    thumbHash: (String) -> String?,
  ): String {
    val base = "$basePath/render/${WebEscaping.urlEncodeSegment(previewId)}.png$q"
    val hash = thumbHash(previewId) ?: return base
    val sep = if (base.contains('?')) "&" else "?"
    return "$base$sep${ServeHeroImages.THUMB_PARAM}=${WebEscaping.urlEncodeSegment(hash)}"
  }

  /**
   * The component drawer's list body: the [previews] under the catalog's section and group
   * headings, or plain rows when there is no outline. Buckets follow authored order
   * ([ServePreview.catalogOrder]) like the landing tree. Section headings appear only when there
   * are several sections, group headings only for named groups.
   */
  private fun navListHtml(
    previews: List<ServePreview>,
    itemHtml: (ServePreview) -> String,
  ): String {
    fun order(p: ServePreview) = p.catalogOrder ?: Int.MAX_VALUE
    val sectionsInPlay = previews.mapNotNull { it.section }.distinct()
    val showSections = sectionsInPlay.size > 1
    val showGroups = previews.any { it.group != null }
    if (!showSections && !showGroups) return previews.joinToString("\n") { itemHtml(it) }
    // (section, group) buckets, each in authored order, ordered among themselves by the earliest
    // card they hold — the same min-order rule [buildSections] sorts the landing's tabs by.
    val buckets = LinkedHashMap<Pair<String?, String?>, MutableList<ServePreview>>()
    previews
      .sortedBy { order(it) }
      .forEach { buckets.getOrPut(it.section to it.group) { mutableListOf() }.add(it) }
    val ordered =
      buckets.entries
        .sortedBy { (_, cards) -> cards.minOf { order(it) } }
        .map { it.key to it.value }
    // A single bucket gets no heading.
    if (ordered.size < 2) return previews.joinToString("\n") { itemHtml(it) }
    fun headRow(kind: String, label: String, count: Int): String =
      "<li class=\"cp-nav-head-row cp-nav-$kind-row\">" +
        "<span class=\"cp-nav-head-name\">${WebEscaping.htmlEscape(label)}</span>" +
        "<span class=\"cp-tree-count\">$count</span></li>"
    return buildString {
      var section: String? = null
      var firstBucket = true
      ordered.forEach { (key, cards) ->
        val (sectionName, groupName) = key
        if (showSections && (firstBucket || sectionName != section)) {
          val total = ordered.filter { it.first.first == sectionName }.sumOf { it.second.size }
          append(headRow("section", sectionName ?: OTHER_SECTION, total)).append("\n")
        }
        section = sectionName
        firstBucket = false
        if (showGroups && groupName != null)
          append(headRow("group", groupName, cards.size)).append("\n")
        cards.forEach { append(itemHtml(it)).append("\n") }
      }
    }
      .trimEnd()
  }

  /**
   * The left-hand component-nav drawer: a filterable list of [siblings] linking to their viewer
   * pages (`$basePath/p/<id>$q`), [preview] marked `aria-current="page"`. Returns "" when there is
   * nothing else to navigate to, so callers can pass the whole `renderHost.previews` list. Starts
   * closed (`cp-nav-open` absent).
   */
  private fun navDrawerHtml(
    preview: ServePreview,
    siblings: List<ServePreview>,
    basePath: String,
    q: String,
    /**
     * The theme the viewer is showing (`"light"`/`"dark"`, or null). Entries link to their
     * component's render in this theme when available, so navigation stays in the current theme.
     */
    theme: String?,
    axesTree: String = "",
    /** The catalog's primary lane, so the size fold resolves per lane as on the grid. */
    darkFirst: Boolean = false,
    /** Per-preview prebaked-thumbnail hash, as on the grid. Null keeps the plain render URL. */
    thumbHash: (String) -> String? = { null },
  ): String {
    // Collapse to one entry per component (folding state/theme/props/size like the grid), linking
    // to the component's render in the current [theme] when it has one. `aria-current` marks the
    // viewed component even when the current preview is a folded variant.
    val navPrimarySizes = primarySizeByComponent(siblings, darkFirst)
    val representatives =
      groupPreviews(
          siblings.filterNot {
            it.renderFailure == null &&
              (isNonDefaultState(it) ||
                hasNonDefaultProps(it) ||
                isNonPrimarySize(it, navPrimarySizes, darkFirst))
          }
        )
        .map {
          when (theme) {
            "dark" -> it.dark ?: it.default
            "light" -> it.light ?: it.default
            else -> it.default
          }
        }
        // One row per component, decided after the lane pick: a sparse theme × size product can
        // leave two survivors [groupPreviews] can't pair. Prefer the one in the viewer's [theme],
        // else the first.
        .let { picked ->
          val byComponent = LinkedHashMap<String, ServePreview>()
          picked.forEach { p ->
            val existing = byComponent[componentKey(p)]
            if (existing == null || (theme != null && existing.theme != theme && p.theme == theme))
              byComponent[componentKey(p)] = p
          }
          byComponent.values.toList()
        }
        .sortedBy { if (componentKey(it) == componentKey(preview)) 0 else 1 }
    // Nothing to navigate to when the collapsed list is empty or holds only the current component.
    val currentKey = componentKey(preview)
    if (axesTree.isBlank() && representatives.none { componentKey(it) != currentKey }) return ""
    val listRepresentatives =
      if (axesTree.isBlank()) representatives
      else representatives.filterNot { componentKey(it) == currentKey }
    fun itemHtml(p: ServePreview): String {
      val segItem = WebEscaping.urlEncodeSegment(p.id)
      val labelItem = WebEscaping.htmlEscape(previewDisplayName(p))
      val idItem = WebEscaping.htmlEscape(p.id)
      // data-search folds label + id for the filter. The aria-current row stays visible under a
      // filter miss.
      val current = if (componentKey(p) == currentKey) " aria-current=\"page\"" else ""
      // The tooltip is the component's caption when it has one, else its preview id.
      val tip = p.caption?.takeIf { it.isNotBlank() } ?: p.id
      // A small thumbnail on the `?thumb=<hash>` lane (immutable, ETagged, served from memory)
      // rather than a full render per row. `alt=""` since the label names the component.
      val thumbSrc = navThumbSrc(basePath, p.id, q, thumbHash)
      return "<li><a class=\"cp-nav-item\" href=\"$basePath/p/$segItem$q\"$current " +
        "title=\"${WebEscaping.htmlEscape(tip)}\" data-search=\"$labelItem $idItem\">" +
        "<img class=\"cp-nav-thumb\" loading=\"lazy\" alt=\"\" src=\"$thumbSrc\">" +
        "<span class=\"cp-nav-name\">$labelItem</span></a></li>"
    }
    // The catalog's outline in the drawer. Headings are flat siblings of the rows, so the filter
    // (`ViewerDrawers`) can hide an emptied heading in the same pass. One section draws no section
    // heading; unnamed groups get none.
    val items = navListHtml(listRepresentatives, ::itemHtml)
    return """
      <aside class="cp-nav" id="cp-nav" aria-label="Components">
        <div class="cp-nav-head"><span>Components</span><button type="button" class="cp-nav-close" id="cp-nav-close" aria-label="Close component navigation">×</button></div>
        <input type="search" class="cp-nav-search" id="cp-nav-search" placeholder="Filter components" autocomplete="off" aria-label="Filter components">
        ${if (axesTree.isBlank()) "" else "<div class=\"cp-nav-current\">$axesTree</div>"}
        <ul class="cp-nav-list" id="cp-nav-list">
        $items
        </ul>
        <p class="cp-nav-empty" id="cp-nav-empty" hidden>No components match.</p>
      </aside>
      """
      .trimIndent()
  }

  /** `1 row` / `4 rows` — the counts this page states in prose rather than in a table header. */
  private fun rowCount(n: Int): String = if (n == 1) "1 row" else "$n rows"

  /**
   * The **cross-catalog layer diff** for one render: what this catalog and its `compareWith`
   * sibling each resolved for the same cell, layer by layer.
   *
   * A table rather than a picture: two rasterisers differ mostly in antialiasing, while a
   * fallen-back font family, a different token value or an extra inset are clear in text. Every
   * number is one the two catalogs already published. One-sided rows are kept and labelled
   * ([ServeParallelLayers]).
   */
  fun parallelLayersPage(
    moduleLabel: String,
    preview: ServePreview,
    /** The counterpart, as the pairing resolved it — its catalog's label and its render's id. */
    siblingLabel: String,
    siblingPreviewId: String,
    /**
     * The sibling render's viewer URL, or empty on a top-level site, where the neighbour's
     * `/{system}/…` 404s; the counterpart is then named in plain text.
     */
    siblingHref: String = "",
    /** One clause naming how the pair was arrived at; see `ResolvedParallel.pairedOn`. */
    pairedOn: String,
    cell: String,
    diff: ServeParallelLayers.Diff,
    token: String,
    sessionId: String? = null,
    basePath: String = "",
    isPublic: Boolean = false,
    themeCss: String = "",
    version: String? = null,
    displayTitle: String? = null,
    sessionInOrigin: Boolean = false,
    changelogHref: String = "",
    reportIssue: ReportIssue? = null,
    unfurl: UnfurlMetadata? = null,
  ): String {
    fun esc(s: String) = WebEscaping.htmlEscape(s)
    val linkSessionId = if (sessionInOrigin) null else sessionId
    val q = querySuffix(linkQuery(token, linkSessionId, basePath, isPublic))
    val navSuffix =
      querySuffix(
        if (isPublic || token.isEmpty()) "" else "token=" + WebEscaping.urlEncodeSegment(token)
      )
    val heading = catalogHeading(displayTitle, moduleLabel)
    val subject = preview.componentId ?: preview.label
    val cellSuffix = if (cell.isEmpty()) "" else " · $cell"
    val layers =
      diff.layers.joinToString("\n") { layer ->
        val counts =
          listOfNotNull(
              "${layer.paired} paired".takeIf { layer.paired > 0 },
              "${layer.differing} differing".takeIf { layer.differing > 0 },
              "${layer.onlyHere} only here".takeIf { layer.onlyHere > 0 },
              "${layer.onlyThere} only there".takeIf { layer.onlyThere > 0 },
            )
            .joinToString(" · ")
        val rows =
          layer.rows.joinToString("\n") { row ->
            // Only differing fields are listed. Agreements are counted, not listed, and a one-sided
            // row lists nothing (the badge already says the other side draws no such node).
            val notes =
              if (row.presence != ServeParallelLayers.Presence.BOTH) ""
              else
                row.fields
                  .filter { it.differs }
                  .joinToString("") { field ->
                    val here = field.here ?: "—"
                    val there = field.there ?: "—"
                    "<div class=\"cp-layer-field\"><code>${esc(field.name)}</code> " +
                      "${esc(here)} <span class=\"cp-muted\">→</span> ${esc(there)}</div>"
                  }
            val badge =
              when (row.presence) {
                ServeParallelLayers.Presence.ONLY_HERE ->
                  "<span class=\"cp-layer-only\">only in $heading</span>"
                ServeParallelLayers.Presence.ONLY_THERE ->
                  "<span class=\"cp-layer-only\">only in ${esc(siblingLabel)}</span>"
                ServeParallelLayers.Presence.BOTH -> ""
              }
            val rowClass = if (row.notable) " class=\"cp-layer-row--notable\"" else ""
            "<tr$rowClass><td>${esc(row.subject)}$badge</td>" +
              "<td class=\"cp-muted\">${esc(row.here ?: "—")}</td>" +
              "<td class=\"cp-muted\">${esc(row.there ?: "—")}</td>" +
              "<td>$notes</td></tr>"
          }
        """
        <h2 class="cp-status-sec">${esc(layer.kind.replaceFirstChar { it.uppercase() })}</h2>
        <p class="cp-muted">${esc(counts)}</p>
        <div class="cp-status-scroll">
          <table class="cp-table">
            <thead><tr><th>Node</th><th>${esc(heading)}</th><th>${esc(siblingLabel)}</th>
              <th>Differences</th></tr></thead>
            <tbody>
            $rows
            </tbody>
          </table>
        </div>
        """
          .trimIndent()
      }
    val siblingLink =
      if (siblingHref.isEmpty()) esc(siblingPreviewId)
      else "<a href=\"${esc(siblingHref)}\">${esc(siblingPreviewId)}</a>"
    val summary =
      when {
        diff.differing == 0 && diff.unpaired == 0 ->
          "Every layer row the two catalogs publish for this cell resolves to the same values."
        else ->
          listOfNotNull(
              "${rowCount(diff.differing)} resolve differently".takeIf { diff.differing > 0 },
              "${rowCount(diff.unpaired)} drawn by one catalog only".takeIf { diff.unpaired > 0 },
            )
            .joinToString(", ") + "."
      }
    return document(
      changelogHref = changelogHref,
      title = "$heading — $subject layers",
      unfurlTitle = "$subject — cross-catalog layers",
      unfurlDescription =
        "What $heading and $siblingLabel each resolved for this cell: typography, layout and theme",
      unfurl = unfurl,
      version = version,
      navSuffix = navSuffix,
      headerBreadcrumb =
        crumbHtml("$basePath/p/${WebEscaping.urlEncodeSegment(preview.id)}$q", subject, "Layers"),
      themeCss = themeCss,
      siteName = heading,
      body =
        """
        <h1 class="cp-head cp-catalog-head">Cross-catalog layers</h1>
        <p class="cp-sub">${esc(subject)}$cellSuffix — what <strong>${esc(heading)}</strong> and
        <strong>${esc(siblingLabel)}</strong> each <em>resolved</em>, rather than what either
        claims. Both sides are read from what the catalogs already publish; nothing is re-rendered
        and no pixels are compared.</p>
        <p class="cp-muted">Against ${esc(siblingLabel)}'s ${siblingLink}${esc(pairedOn)}.
        ${esc(summary)}</p>
        <p class="cp-muted"><a class="cp-format-link" href="?format=json">this page as JSON →</a></p>
        ${pageReportRowHtml(reportIssue, "cp-page-links")}
        $layers
        """
          .trimIndent(),
    )
  }

  /**
   * The Open Graph and Twitter card tags for one page, from unescaped [title] and [description].
   * Separate so pages not laid out by [document] (the UI builder shell) share the same unfurl.
   */
  internal fun unfurlHeadHtml(title: String, description: String, unfurl: UnfurlMetadata): String {
    val metaTitle = WebEscaping.htmlEscape(title)
    val metaDescription = WebEscaping.htmlEscape(description)
    val pageUrl = WebEscaping.htmlEscape(unfurl.pageUrl)
    val imageUrl = unfurl.imageUrl?.let(WebEscaping::htmlEscape)
    // Only when both are known: a card given one axis has to measure the image anyway, and a
    // half-declared size is the one input an unfurler can't sanity-check against the pixels.
    val dimensionsHtml =
      if (unfurl.imageWidth == null || unfurl.imageHeight == null) ""
      else
        """

          <meta property="og:image:width" content="${unfurl.imageWidth}">
          <meta property="og:image:height" content="${unfurl.imageHeight}">"""
          .trimIndent()
    val imageHtml =
      if (imageUrl == null) ""
      else
        """
          <meta property="og:image" content="$imageUrl">
          <meta property="og:image:type" content="image/png">
          <meta property="og:image:alt" content="$metaTitle">$dimensionsHtml
          """
          .trimIndent()
    val twitterImageHtml =
      if (imageUrl == null) ""
      else
        """
          <meta name="twitter:image" content="$imageUrl">
          <meta name="twitter:image:alt" content="$metaTitle">
          """
          .trimIndent()
    return """
      <meta property="og:type" content="website">
      <meta property="og:site_name" content="compose-preview">
      <meta property="og:title" content="$metaTitle">
      <meta property="og:description" content="$metaDescription">
      <meta property="og:url" content="$pageUrl">
      $imageHtml
      <meta name="twitter:card" content="${twitterCard(unfurl)}">
      <meta name="twitter:title" content="$metaTitle">
      <meta name="twitter:description" content="$metaDescription">
      $twitterImageHtml
      """
      .trimIndent()
  }

  private fun document(
    title: String,
    body: String,
    unfurlTitle: String? = null,
    unfurlDescription: String? = null,
    unfurl: UnfurlMetadata? = null,
    navSuffix: String = "",
    headerAction: String = "",
    /**
     * The **Session** group for the Settings menu ([githubSessionSettings]); separate from
     * [headerAction], which shows identity in the bar.
     */
    headerSessionSettings: String = "",
    /**
     * The header's collapsed search control ([headerSearchControl]); empty where there is nothing
     * to search. See [siteHeader]'s `search`.
     */
    headerSearch: String = "",
    /**
     * The breadcrumb / back link, rendered in the header's brand slot by [siteHeader]. Empty on the
     * front door.
     */
    headerBreadcrumb: String = "",
    /** The UI builder's Designs index, linked from the header's nav panel. See [siteHeader]. */
    headerDesignsHref: String = "",
    /**
     * Running server version (`SERVE_VERSION`) for [siteFooter]. Null omits the build span; fixture
     * goldens pass a fixed string.
     */
    version: String? = null,
    /**
     * The page's own block inside [siteFooter], above the links (the landing's provenance
     * disclosure). Empty elsewhere.
     */
    footerNote: String = "",
    /**
     * The catalog's palette from [ServeThemeCss], inlined after `serve.css` so it wins at equal
     * specificity. Empty uses the built-in chrome.
     */
    themeCss: String = "",
    /**
     * The catalog-scoped `sessionStorage` key for this page's theme choice ([themeStorageKey]),
     * published on `<html data-cp-theme-key>` for the pre-paint script and Page theme setting.
     * Empty for pages with no theme control.
     */
    themeStorageKey: String = "",
    /**
     * Whether a remembered theme choice can change what this page shows. False on a viewer whose
     * Theme control is disabled (no daemon/Wasm, or a fixed-theme specimen), so the pre-paint
     * script resolves the chrome from the baked theme instead. Default true.
     */
    themeChoiceApplies: Boolean = true,
    /**
     * The theme values this page's control offers, for the pre-paint script to validate a
     * remembered choice. One key serves a catalog's viewer, grid and wall, so a memory may name
     * something this page lacks (e.g. `light` in a Dark-only Wear viewer). Empty means trust
     * whatever resolves.
     */
    offeredThemes: List<String> = emptyList(),
    /** The catalog this page belongs to, named in the header bar. See [siteHeader]. */
    siteName: String = "",
    /**
     * Declared themes whose resolved mode lets the head script paint correctly before first draw.
     */
    declaredThemes: List<ServeTheme> = emptyList(),
    /**
     * Register the vendored Remote Compose typefaces ([ServeRcFonts]) on pages that play `.rc`
     * documents client-side, so `Roboto, sans-serif` doesn't fall through to the viewer machine's
     * font. Off elsewhere; page chrome is system-font.
     */
    rcFonts: Boolean = false,
    /** Streamlined component-browser chrome; full mode remains the default. */
    componentBrowser: Boolean = false,
    /** Show and persist the Catalog / Dev switch on pages that support both presentations. */
    interfaceModeControl: Boolean = false,
    /**
     * Offer the footer's "report a bug" entry; false only on the report page itself
     * ([reportBugFormHtml]). Independent of [componentBrowser], which drops the footer entirely.
     */
    bugReport: Boolean = true,
    /**
     * The catalog change feed linked as **Changelog** and declared as the page's RSS alternate.
     * Empty on pages belonging to no published catalog. See [siteFooter].
     */
    changelogHref: String = "",
    /**
     * Span the whole viewport instead of the 1440px reading column, for surfaces whose subject is a
     * wide image (a design page's specimen sheet). Opt-in per surface.
     */
    wide: Boolean = false,
  ): String {
    val unfurlHtml =
      if (unfurl == null) ""
      else
        unfurlHeadHtml(
          unfurlTitle ?: title,
          unfurlDescription ?: "Compose preview rendered by compose-preview",
          unfurl,
        )
    val unfurlBlock = if (unfurlHtml.isEmpty()) "" else "\n${unfurlHtml.prependIndent("        ")}"
    val footerBlock =
      if (componentBrowser) ""
      else
        "\n${siteFooter(version, footerNote, bugReport, changelogHref).prependIndent("        ")}"
    // The floating launcher follows the footer entry's conditions: not in component-browser mode,
    // not on the report page.
    val launcherBlock =
      if (componentBrowser || !bugReport) ""
      else "\n${reportLauncherHtml(assetHref("report-capture.js")).prependIndent("        ")}"
    // Feed autodiscovery: the same document the footer's Changelog entry links, declared where a
    // reader's "subscribe to this page" affordance looks for it.
    val feedLink =
      changelogHref
        .takeIf { it.isNotBlank() }
        ?.let {
          "\n        <link rel=\"alternate\" type=\"application/rss+xml\"" +
            " title=\"Catalog changes\" href=\"${WebEscaping.htmlEscape(it)}\">"
        } ?: ""
    // Before `themeCss`, so a catalog palette still wins at equal specificity; the font block
    // declares faces only and collides with nothing in the chrome.
    val rcFontsBlock = if (rcFonts) "\n" + ServeRcFonts.linkTag().prependIndent("        ") else ""
    val themeBlock =
      themeCss
        .takeIf { it.isNotBlank() }
        ?.let { "\n" + ("<style>\n" + it.trimEnd() + "\n</style>").prependIndent("        ") } ?: ""
    val themeKeyAttr =
      themeStorageKey
        .takeIf { it.isNotBlank() }
        ?.let { " data-cp-theme-key=\"${WebEscaping.htmlEscape(it)}\"" } ?: ""
    // Migrate the mode from the pre-cookie `localStorage` key once, then reload so the server
    // renders with the cookie. Safe to delete in a release or two.
    val interfaceModeBoot =
      if (interfaceModeControl)
        "\n        " +
          """<script>try{var s=localStorage.getItem("cp-interface-mode");if(s){localStorage.removeItem("cp-interface-mode");if((s==="catalog"||s==="dev")&&document.cookie.indexOf("$INTERFACE_MODE_COOKIE=")<0){document.cookie="$INTERFACE_MODE_COOKIE="+s+"$INTERFACE_MODE_COOKIE_ATTRS"+(location.protocol==="https:"?"; secure":"");if(document.cookie.indexOf("$INTERFACE_MODE_COOKIE="+s)>=0&&!/[?&]chrome=/.test(location.search))location.reload();}}}catch(e){}</script>"""
      else ""
    // The switch: write the cookie, drop any `?chrome=` (an explicit permalink outranks the
    // cookie), and reload.
    val interfaceModeControls =
      if (interfaceModeControl)
        "\n        " +
          """<script>(function(){var key="$INTERFACE_MODE_COOKIE";document.querySelectorAll("[data-cp-interface-mode]").forEach(function(b){b.addEventListener("click",function(){var mode=b.getAttribute("data-cp-interface-mode");if(mode!=="catalog"&&mode!=="dev")return;try{document.cookie=key+"="+mode+"$INTERFACE_MODE_COOKIE_ATTRS"+(location.protocol==="https:"?"; secure":"");}catch(e){}var u=new URL(location.href);u.searchParams.delete("chrome");if(document.cookie.indexOf(key+"="+mode)<0)u.searchParams.set("chrome",mode);var q=u.searchParams.toString();location.assign(u.pathname+(q?"?"+q:"")+u.hash);});});})();</script>"""
      else ""
    // The body's classes in one `class` attribute, so `contains("class=\"cp-component-browser\"")`
    // assertions keep matching.
    val bodyClasses =
      listOfNotNull("cp-component-browser".takeIf { componentBrowser }, "cp-wide".takeIf { wide })
    val bodyClassAttr =
      if (bodyClasses.isEmpty()) "" else " class=\"${bodyClasses.joinToString(" ")}\""
    // `serve-chrome.js` is first in `<body>` because other scripts read the globals it installs as
    // they upgrade. Unconditional since it also carries the Page theme setting (~1 kB gzipped). Not
    // annotated in the HTML: tests check `html.contains("format-compare.js")` to detect loaded
    // lanes.
    return """
    <!doctype html>
    <html lang="en"$themeKeyAttr>
      <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content">$unfurlBlock
        <title>${WebEscaping.htmlEscape(title)}</title>
${ServeSiteIcon.linkTags(themeCss, siteName.ifBlank { "Compose Preview" }).prependIndent("        ")}
        $SPECULATION_RULES
        <link rel="stylesheet" href="${assetHref("serve.css")}">$feedLink$rcFontsBlock$themeBlock$interfaceModeBoot
        <!-- Apply the Transparent choice before first paint (no checkerboard flash).
             A `?bg=` on the URL is an explicit, shareable choice and outranks the sticky one. -->
        <script>try{var b=new URLSearchParams(location.search).get("bg");if(b?b==="off":localStorage.getItem("cp-bg")==="off")document.documentElement.classList.add("cp-bg-transparent");}catch(e){}</script>
        ${pageThemeScript(themeStorageKey, declaredThemes, themeChoiceApplies, offeredThemes)}
      </head>
      <body${bodyClassAttr}>
        ${scriptTag("serve-chrome.js")}${ServeAnalytics.scriptTag()}
        ${siteHeader(navSuffix, headerAction, headerBreadcrumb, siteName, componentBrowser, interfaceModeControl, themeStorageKey.isNotBlank() && interfaceModeControl, headerSessionSettings, headerSearch, headerDesignsHref)}
        <main class="cp-main">
        $body
        </main>$footerBlock$launcherBlock$interfaceModeControls
        ${if (componentBrowser) "" else scriptTag("keyboard-navigation.js")}
      </body>
    </html>
    """
      .trimIndent() + "\n"
  }

  /**
   * Same-origin links this page may prefetch (document only, never prerender) on `moderate` intent.
   * Prerender would open sockets, wake daemons and count views. Paths that act, authenticate,
   * stream or are heavy are excluded, as are download, new-tab and `nofollow` links.
   */
  internal val SPECULATION_RULES: String =
    """<script type="speculationrules">{"prefetch":[{"source":"document","where":{"and":[""" +
      """{"href_matches":"/*"},{"not":{"href_matches":[""" +
      listOf(
          "/api/*",
          "/auth/*",
          "/admin/*",
          "/oauth/*",
          "/agent-access/*",
          "/report-bug*",
          "/refresh*",
          "/ui-builder*",
          "/playground*",
          "/pg/*",
          "/bundle*",
          "/render/*",
          "/motion/*",
          "/ws/*",
          "/mcp*",
          "/i/*",
          "/d/*",
          "/docs*",
          "/images*",
          "/*/render/*",
          "/*/refresh*",
          "/*/bundle*",
        )
        .joinToString(",") { "\"$it\"" } +
      """]}},{"not":{"selector_matches":"[download], [target=_blank], [rel~=nofollow], """ +
      """[data-cp-no-prefetch]"}}]},"eagerness":"moderate"}]}</script>"""

  /**
   * Pin the page's colour scheme to the selected preview theme before first paint when the Page
   * theme setting is on (see `chrome/pageTheme.ts`). Inline in `<head>` to avoid a full-screen
   * flash; self-contained because the shell bundle hasn't loaded.
   *
   * Order: the URL (`?theme=` on a landing, `?uiMode=` in the viewer), then this tab's remembered
   * choice ([themeStorageKey], `sessionStorage`), then the theme baked into a `…__light` /
   * `…__dark` id. The memory outranks the id because the viewer applies it too. A declared theme
   * moves the chrome only when [ServeTheme.mode] is unambiguous; otherwise the OS decides.
   */
  private fun pageThemeScript(
    themeStorageKey: String,
    declaredThemes: List<ServeTheme>,
    themeChoiceApplies: Boolean = true,
    offeredThemes: List<String> = emptyList(),
  ): String {
    val modeEntries = declaredThemes.mapNotNull { theme ->
      theme.mode?.let { mode ->
        WebEscaping.jsString("theme:${theme.providerFqn}") + ":" + WebEscaping.jsString(mode)
      }
    }
    val modeInit =
      modeEntries.takeIf { it.isNotEmpty() }?.let { "m={${it.joinToString(",")}}," } ?: ""
    val modeResolve = if (modeEntries.isEmpty()) "" else "t=m[t]||t;"
    // The theme the preview id bakes, read off the path — the fallback whenever nothing better is
    // known, and the ONLY candidate on a page whose theme choice cannot reach the pixels.
    val bakedTheme =
      "((decodeURIComponent(location.pathname).split('/').pop()||\"\")" +
        ".match(/(?:^|__)(light|dark)(?:__|$)/)||[])[1]"
    // `r()` resolves the remembered value as a fallback, not a filter: an unusable memory (e.g. a
    // removed theme) yields to the baked theme rather than painting nothing. Where the caller lists
    // [offeredThemes], being offered is the test (a memory may name a choice this page lacks);
    // otherwise resolvability is. An offered theme with no declared mode yields "" for the OS,
    // matching [pageTheme.follow].
    val readsMemory = themeStorageKey.isNotBlank() && themeChoiceApplies
    val offered = offeredThemes.takeIf { readsMemory && it.isNotEmpty() }
    val offerInit =
      offered?.let {
        "o={${it.joinToString(",") { value -> "${WebEscaping.jsString(value)}:1" }}},"
      } ?: ""
    val resolve = if (modeEntries.isEmpty()) "t" else "m[t]||t"
    // With an offer list, being offered is the test (unresolved modes defer to the OS); without
    // one, a value is trusted as far as it resolves.
    val resolveFn =
      when {
        !readsMemory -> ""
        offered != null -> "r=function(t){return t&&o[t]?$resolve:$bakedTheme;},"
        else -> "r=function(t){t=$resolve;return t===\"light\"||t===\"dark\"?t:$bakedTheme;},"
      }
    val storedTheme =
      themeStorageKey
        .takeIf { it.isNotBlank() }
        ?.let {
          if (readsMemory) "||r(sessionStorage.getItem(${WebEscaping.jsString(it)}))"
          else "||($bakedTheme)"
        } ?: ""
    return "<script>try{var p=new URLSearchParams(location.search),$modeInit$offerInit$resolveFn" +
      "t=localStorage.getItem(\"cp-page-theme\")===\"system\"?\"\"" +
      ":(p.get(\"theme\")||(p.get(\"themeProvider\")?\"theme:\"+p.get(\"themeProvider\"):\"\")||p.get(\"uiMode\")$storedTheme);" +
      modeResolve +
      "if(t===\"light\"||t===\"dark\")document.documentElement.classList.add(\"cp-scheme-\"+t);" +
      "}catch(e){}</script>"
  }

  /**
   * The export bar: the `/render/<id>.png` (and `.svg` with [hasSvgExport]) URL with current
   * overrides applied, offered per format as "Copy link", "Copy PNG"/"Copy SVG" (the artefact
   * itself) and "Download". `refreshLinks` keeps them in sync; URLs are absolute, built from
   * `location.origin`, and the `#cp-url-<ext>` fields fill on first render.
   *
   * * One always-visible line rather than a `<details>`, since this is the viewer's primary
   *   hand-off.
   * * The URL lives in an off-flow `tabindex="-1"` field: `refreshLinks`, both copy buttons and the
   *   lane e2e read it.
   *
   * "Full page (scroll)" lives in the drawer's Scroll group ([scrollGroupHtml]).
   */
  private fun downloadLinksHtml(hasSvgExport: Boolean): String {
    fun group(kind: String, ext: String): String =
      """
      <span class="cp-link-group">
        <span class="cp-link-kind">$kind</span>
        <button type="button" class="cp-copyurl" data-copyurl-target="cp-url-$ext"
          title="Copy the $kind URL of the current view (overrides applied)">Copy link</button>
        <button type="button" class="cp-copyimg" data-copyimg-target="cp-url-$ext"
          data-copyimg-ext=".$ext" title="Copy the $kind itself to the clipboard">Copy $kind</button>
        <a id="cp-dl-$ext" class="cp-dl" download title="Save the $kind to a file">Download</a>
        <input id="cp-url-$ext" class="cp-url" type="text" readonly tabindex="-1"
          aria-label="$kind URL">
      </span>
      """
        .trimIndent()
    // The SVG lane is export-only now (no on-screen SVG mode); its shape is controlled by the
    // "Full page (scroll)" toggle over in the overrides drawer's Scroll group.
    val svgGroup = if (hasSvgExport) "\n" + group("SVG", "svg") else ""
    return """
      <div class="cp-export" aria-label="Export the current view">
        <span class="cp-export-head" id="cp-export-head">Export</span>
        ${group("PNG", "png")}$svgGroup
      </div>
      """
      .trimIndent()
  }

  /**
   * The export-shaping drawer groups (Scroll and Exploded 3D) joined in one slot so absent groups
   * leave no blank line.
   */
  private fun exportShapeGroupsHtml(hasScrollExport: Boolean, hasSvgExport: Boolean): String =
    listOf(scrollGroupHtml(hasScrollExport, hasSvgExport), explodeGroupHtml(hasSvgExport))
      .filter { it.isNotBlank() }
      .joinToString("\n          ")

  /**
   * The drawer's "Exploded 3D" group: camera and separation sliders behind the bar's **3D** toggle
   * (defaults are the readable preset). Each knob's `data-cp-default` lets the viewer omit
   * untouched axes from the URL (`?exploded=1`) and reset on Back, so it must equal
   * `ExplodedSvg.Options`' defaults (a fixture test checks). Empty without SVG export.
   */
  private fun explodeGroupHtml(hasSvgExport: Boolean): String {
    if (!hasSvgExport) return ""
    fun slider(
      id: String,
      label: String,
      min: String,
      max: String,
      step: String,
      default: String,
      unit: String,
      hint: String,
    ): String =
      """
      <label class="cp-explode-row" title="$hint">$label
        <input id="cp-explode-$id" class="cp-explode-knob" type="range" min="$min" max="$max"
          step="$step" value="$default" data-cp-default="$default" data-cp-unit="$unit" disabled>
        <output id="cp-explode-$id-value" class="cp-explode-value">$default$unit</output>
      </label>
      """
        .trimIndent()
    return """
      <details class="cp-group" data-cp-group="explode">
        <summary>Exploded 3D</summary>
        <div class="cp-group-body">
          ${slider("tilt", "Lean", "0", "75", "1", "28", "°", "How far the layers lean away from you; 0 is face-on").prependIndent("          ").trimStart()}
          ${slider("spin", "Spin", "-80", "80", "1", "-16", "°", "How far the layers are turned in their own plane").prependIndent("          ").trimStart()}
          ${slider("gap", "Separation", "0", "600", "5", "0", "", "Distance between layers; 0 derives one from the preview's size").prependIndent("          ").trimStart()}
          ${slider("depth", "Layers", "1", "16", "1", "6", "", "Composables nested deeper than this fold into the last layer").prependIndent("          ").trimStart()}
          <div class="cp-knobs-head">One sheet per visible drawing level. Structural-only
            composables are kept in the next sheet's breadcrumb. Rides the SVG link and download.</div>
        </div>
      </details>
      """
      .trimIndent()
  }

  /**
   * The drawer's Scroll group: "Full page (scroll)" points the PNG and SVG exports at the
   * `?scroll=long` render of a scrolling preview. The viewer JS (`withScroll`) folds it into both
   * URLs. Empty without SVG export.
   */
  private fun scrollGroupHtml(hasScrollExport: Boolean, hasSvgExport: Boolean): String =
    if (!hasScrollExport) ""
    else
      """
      <details class="cp-group" data-cp-group="scroll">
        <summary>Scroll</summary>
        <div class="cp-group-body">
          <label class="cp-live-row"><input id="cp-scroll-long" type="checkbox"> Full page (scroll)</label>
          <div class="cp-knobs-head">Exports the whole scrollable page as PNG${if (hasSvgExport) " or SVG" else ""}.</div>
        </div>
      </details>
      """
        .trimIndent()

  /**
   * Renders the preview's author-declared knobs (`previews/<id>.overrides.json`) as labelled
   * controls; indexed knobs are grouped under their base key with a `#<index>` suffix. Live when
   * [canApplyOverrides] or [wasmAvailable] (the Wasm app seeds `catalogOverride*`); otherwise
   * disabled with a note. Empty when none are declared.
   */
  /**
   * fonts.google.com family names for font-knob autocomplete, loaded once from the
   * `google-fonts.txt` resource (regenerated by `scripts/fonts/build-google-fonts-list.mjs`; `#`
   * lines skipped). Empty if absent, leaving only [PreviewOverrideDeclaration.suggestions].
   */
  /**
   * The locale field's value set: tags worth offering, with display names. Open, not exhaustive —
   * the control stays an `<input list>` so any BCP-47 tag is typeable — and rendered via
   * [datalistOptionsHtml]. Pseudolocales first, then RTL languages, then common tags.
   */
  private val LOCALE_PRESETS: List<PreviewOverrideOption> =
    listOf(
      PreviewOverrideOption("en-XA", "Accented (pseudo)"),
      PreviewOverrideOption("ar-XB", "Bidi / RTL (pseudo)"),
      PreviewOverrideOption("ar", "Arabic (RTL)"),
      PreviewOverrideOption("he", "Hebrew (RTL)"),
      PreviewOverrideOption("fa", "Persian (RTL)"),
      PreviewOverrideOption("en-US"),
      PreviewOverrideOption("en-GB"),
      PreviewOverrideOption("de-DE"),
      PreviewOverrideOption("fr-FR"),
      PreviewOverrideOption("es-ES"),
      PreviewOverrideOption("pt-BR"),
      PreviewOverrideOption("ru-RU"),
      PreviewOverrideOption("ja-JP"),
      PreviewOverrideOption("ko-KR"),
      PreviewOverrideOption("zh-CN"),
      PreviewOverrideOption("zh-Hant-TW"),
      PreviewOverrideOption("hi-IN"),
      PreviewOverrideOption("th-TH"),
    )

  internal val googleFontFamilies: List<String> by lazy {
    ServeWeb::class
      .java
      .classLoader
      .getResourceAsStream("ee/schimke/composeai/cli/serve/google-fonts.txt")
      ?.bufferedReader()
      ?.useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
      }
      .orEmpty()
  }

  /**
   * `<option>`s for a font knob's `<datalist>`: declared [suggestions] first, then (with
   * [googleFonts]) the full Google list, de-duplicated, order preserved.
   */
  private fun fontDatalistOptions(suggestions: List<String>, googleFonts: Boolean): String {
    val seen = LinkedHashSet<String>()
    suggestions.forEach { if (it.isNotBlank()) seen.add(it) }
    if (googleFonts) seen.addAll(googleFontFamilies)
    return datalistOptionsHtml(seen.map { PreviewOverrideOption(it) })
  }

  /**
   * `<option>`s for a `<datalist>` (open value set). Values with a distinct label carry `label=`;
   * self-labelling values emit a bare `value=`.
   */
  private fun datalistOptionsHtml(
    options: List<PreviewOverrideOption>,
    /**
     * Indent for lines after the first, since interpolation only indents the first line; the viewer
     * pages are checked-in goldens.
     */
    indent: String = "",
  ): String =
    options.joinToString("\n$indent") { o ->
      val value = WebEscaping.htmlEscape(o.value)
      if (o.label == o.value) "<option value=\"$value\"></option>"
      else "<option value=\"$value\" label=\"${WebEscaping.htmlEscape(o.label)}\"></option>"
    }

  /**
   * `<option>`s for a `<select>` opening on [selected]. A [selected] outside the declared set is
   * emitted as a leading extra option, so the control reflects what is on screen.
   */
  private fun selectOptionsHtml(options: List<PreviewOverrideOption>, selected: String): String {
    val known = options.any { it.value == selected }
    val all = if (known) options else listOf(PreviewOverrideOption(selected)) + options
    return all.joinToString("\n") { o ->
      val active = if (o.value == selected) " selected" else ""
      "<option value=\"${WebEscaping.htmlEscape(o.value)}\"$active>" +
        "${WebEscaping.htmlEscape(o.label)}</option>"
    }
  }

  /**
   * A knob's boolean text, read as [ServeOverrides.parse] does: `1` or `true` in any case, so
   * `?knob.enabled=bool:TRUE` ticks the box.
   */
  private fun boolText(raw: String): String =
    if (raw == "1" || raw.equals("true", ignoreCase = true)) "true" else "false"

  private fun overrideKnobsHtml(
    preview: ServePreview,
    canApplyOverrides: Boolean,
    wasmAvailable: Boolean = false,
    requestOverrides: Map<String, String> = emptyMap(),
  ): String {
    if (preview.overrides.isEmpty()) return ""
    // Editable when the server can re-render (canApplyOverrides) or a Wasm app can honour the edit
    // (wasmAvailable). Otherwise shown disabled. The viewer JS collects `.cp-knob` values into
    // `knob.<key>=<value>`.
    val editable = canApplyOverrides || wasmAvailable
    val dis = if (editable) "" else " disabled"
    val rows =
      preview.overrides.joinToString("\n") { d ->
        val name = if (d.index == null) d.key else "${d.key} #${d.index}"
        val label = WebEscaping.htmlEscape(name)
        // Daemon map key: base key, plus `[index]` for an indexed (per-item) knob.
        val rawWireKey = if (d.index == null) d.key else "${d.key}[${d.index}]"
        val wireKey = WebEscaping.htmlEscape(rawWireKey)
        val kind = knobKind(d.type)
        // What the preview declares (author default, or the `@OverrideVariant` seed); the `data-*`
        // attributes below read this, never the request.
        val declared = overrideValueText(d.current ?: d.default)
        // …and what this request asks for: a deep link's value must seed the control, or the
        // controls (live `setOverrides`, export links, next `/render`) would send the declared
        // value while the snapshot shows the override. `hydrateFromUrl` does the same client-side.
        // Any legacy `<kind>:` wire tag is stripped as the parser does, so `int:3` shows `3`.
        val shown =
          requestOverrides[ServeOverrides.KNOB_PREFIX + rawWireKey]?.let {
            ServeOverrides.knobControlValue(it, kind)
          } ?: declared
        val value = WebEscaping.htmlEscape(shown)
        // `data-knob-initial` stays the declared value: the viewer omits a knob equal to it, so
        // plain visits get the baked PNG while a deep-linked value differs and rides into every
        // render.
        val bool = kind == "bool"
        val initial = if (bool) boolText(declared) else WebEscaping.htmlEscape(declared)
        // `data-knob-default` is the author default, which differs for a seeded `@OverrideVariant`.
        // The Wasm tier has no baked artifact and must be told the seed, so `wasmOverridePatch`
        // compares against this, not `initial`.
        val authorDefault = overrideValueText(d.default)
        val defaultAttr =
          if (bool) boolText(authorDefault) else WebEscaping.htmlEscape(authorDefault)
        val attrs =
          "class=\"cp-knob\" data-knob-key=\"$wireKey\" data-knob-kind=\"$kind\" " +
            "data-knob-initial=\"$initial\" data-knob-default=\"$defaultAttr\""
        if (bool) {
          val checked = if (boolText(shown) == "true") " checked" else ""
          "<label class=\"cp-live-row\"><input type=\"checkbox\" $attrs$checked$dis> $label</label>"
        } else if (d.optionsExhaustive && d.options.isNotEmpty()) {
          // A closed value set (`previewOverrideChoice`) renders as a `<select>`. The viewer JS
          // needs no branch: it reads `.value`/`.disabled` and only special-cases checkboxes.
          """
          <label>${label}
            <select $attrs$dis>
          ${selectOptionsHtml(d.options, shown)}
            </select>
          </label>
          """
            .trimIndent()
        } else {
          val inputType = if (d.type == "int" || d.type == "float") "number" else "text"
          // A knob with discovered options (font knobs via `previewOverrideFont` /
          // `catalogOverrideFont`, open value sets, or declared `suggestions` like `theme.colors`)
          // renders as an `<input list>` combobox: declared names first, then the Google list for
          // fonts. Others stay plain inputs.
          val hasOptions = d.googleFonts || d.suggestions.isNotEmpty() || d.options.isNotEmpty()
          if (hasOptions) {
            val listId = "cp-dl-" + wireKey.replace(Regex("[^A-Za-z0-9_-]"), "-")
            val options =
              if (d.options.isNotEmpty()) datalistOptionsHtml(d.options)
              else fontDatalistOptions(d.suggestions, d.googleFonts)
            """
            <label>${label}
              <input type="$inputType" $attrs value="$value" list="$listId"$dis>
              <datalist id="$listId">
            $options
              </datalist>
            </label>
            """
              .trimIndent()
          } else if (inputType == "text" && isLongTextKnob(authorDefault)) {
            // Multi-line or long string defaults (an A2UI document, copy) get a textarea, still
            // `.cp-knob`. Concatenated rather than `trimIndent()`, which would alter the value; the
            // newline after the open tag is the one the HTML parser drops.
            "<label>$label\n  <textarea $attrs rows=\"6\" spellcheck=\"false\"$dis>\n" +
              value +
              "</textarea>\n</label>"
          } else {
            """
            <label>${label}
              <input type="$inputType" $attrs value="$value"$dis>
            </label>
            """
              .trimIndent()
          }
        }
      }
    val note =
      when {
        canApplyOverrides -> "Declared overrides — edit a value to re-render."
        wasmAvailable -> "Declared overrides — edit a value to apply it in the browser (Wasm)."
        else -> "Declared overrides — static bundle, values are baked in."
      }
    return """
      <details class="cp-group" data-cp-group="overrides">
        <summary>Overrides</summary>
        <div class="cp-group-body">
          <div class="cp-knobs">
            <div class="cp-knobs-head">$note</div>
            $rows
          </div>
        </div>
      </details>
      """
      .trimIndent()
  }

  /** Map a declaration's `type` to the daemon's [PreviewOverrideValue] wire kind. */
  private fun knobKind(type: String): String = ServeOverrides.knobKind(type)

  /** Human text for a [ee.schimke.composeai.daemon.protocol.PreviewOverrideValue] in the viewer. */
  private fun overrideValueText(
    v: ee.schimke.composeai.daemon.protocol.PreviewOverrideValue
  ): String =
    when (v) {
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.StringValue -> v.value
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.IntValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.FloatValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.BooleanValue ->
        v.value.toString()
      is ee.schimke.composeai.daemon.protocol.PreviewOverrideValue.ColorValue -> v.argb
    }

  /**
   * Renders the preview's declared **Remote Compose** named-value knobs
   * (`previews/<id>.remotecompose.json`) — the RC counterpart of [overrideKnobsHtml]. Checkbox for
   * bool, number for int/float/dp, text for string and `#AARRGGBB` colour; edits round-trip via
   * `rc.<name>=<kind>:<value>` ([ServeOverrides] → `PreviewOverrides.remoteCompose.namedValues`).
   * Live with server rendering or the CMP/Wasm host; otherwise disabled with a note. Empty when
   * none. Controls carry `.cp-rc-knob`, `data-rc-name`, `data-rc-kind`, `data-rc-initial`.
   */
  private fun remoteComposeKnobsHtml(
    preview: ServePreview,
    canApplyOverrides: Boolean,
    requestOverrides: Map<String, String> = emptyMap(),
  ): String {
    if (preview.remoteComposeKnobs.isEmpty()) return ""
    // A static bundle without either a server renderer or CMP/Wasm keeps these informational.
    val dis = if (canApplyOverrides) "" else " disabled"
    val rows =
      preview.remoteComposeKnobs.joinToString("\n") { d ->
        val label = WebEscaping.htmlEscape(d.name)
        val wireName = WebEscaping.htmlEscape(d.name)
        val kind = rcKnobKind(d.default)
        val declared = rcKnobValueText(d.default)
        // Seed the request's value like `overrideKnobsHtml`, but under RC's stricter typing: only a
        // seed that parses as this knob's kind is shown (`ServeOverrides.rcControlValue`),
        // otherwise the control keeps what the render used.
        val shown =
          requestOverrides[ServeOverrides.RC_NAMED_PREFIX + d.name]?.let {
            ServeOverrides.rcControlValue(it, kind)
          } ?: declared
        val value = WebEscaping.htmlEscape(shown)
        // `data-rc-initial` is the author default (same reasoning as `data-knob-initial`).
        val attrs =
          "class=\"cp-rc-knob\" data-rc-name=\"$wireName\" data-rc-kind=\"$kind\" " +
            "data-rc-initial=\"${WebEscaping.htmlEscape(declared)}\""
        if (kind == "bool") {
          // `true` or `1`, as the parser and `hydrateFromUrl` read a bool.
          val checked = if (boolText(shown) == "true") " checked" else ""
          "<label class=\"cp-live-row\"><input type=\"checkbox\" $attrs$checked$dis> $label</label>"
        } else {
          val inputType = if (kind == "int" || kind == "float" || kind == "dp") "number" else "text"
          """
          <label>${label}
            <input type="$inputType" $attrs value="$value"$dis>
          </label>
          """
            .trimIndent()
        }
      }
    val note =
      if (canApplyOverrides) "Declared Remote Compose knobs — edit a value to re-render."
      else "Declared Remote Compose knobs — static bundle, values are baked in."
    return """
      <details class="cp-group" data-cp-group="remotecompose">
        <summary>Remote Compose</summary>
        <div class="cp-group-body">
          <div class="cp-knobs">
            <div class="cp-knobs-head">$note</div>
            $rows
          </div>
        </div>
      </details>
      """
      .trimIndent()
  }

  /** The `<kind>` wire tag for a Remote Compose knob's typed default (see `RemoteNamedValue`). */
  private fun rcKnobKind(v: ee.schimke.composeai.daemon.protocol.RemoteNamedValue): String =
    when (v) {
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.FloatValue -> "float"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.DpValue -> "dp"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue -> "int"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.StringValue -> "string"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.BooleanValue -> "bool"
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.ColorValue -> "color"
    }

  /**
   * Human/edit text for a Remote Compose knob's typed default; colour is its `#AARRGGBB` string.
   */
  private fun rcKnobValueText(v: ee.schimke.composeai.daemon.protocol.RemoteNamedValue): String =
    when (v) {
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.FloatValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.DpValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.IntValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.StringValue -> v.value
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.BooleanValue -> v.value.toString()
      is ee.schimke.composeai.daemon.protocol.RemoteNamedValue.ColorValue -> v.argb
    }

  /**
   * A small built-in device menu: `device-token` → display name, using the `@Preview(device=…)`
   * grammar. TODO: source the full list from the daemon's `DeviceDimensions` catalog so the menu
   * always matches the backend.
   */
  /**
   * Where the snapshot note points viewers who want the disabled overrides: the source doc on
   * `main` about running a live `compose-preview serve`, since published catalogs only replay baked
   * PNGs and the docs site has no serve page.
   */
  private const val LOCAL_SERVER_DOCS =
    "https://github.com/yschimke/compose-ai-tools/blob/main/docs/public-preview-server.md#running-one"

  /**
   * The density a page falls back to when nothing says what the preview renders at — the last step
   * of the render lane's chain (`density ?: params.density ?: device density ?: 2.0`).
   *
   * The viewer converts dp size overrides to px with this factor, so it must be right per preview:
   * most `DeviceDimensions` ids are not 2.0. [ServeBundleHost.renderDensityFor] answers when the
   * manifest or device says; this is the fallback.
   */
  private const val FALLBACK_RENDER_DENSITY = 2f

  /**
   * `data-render-density`'s value: the preview's density, else [FALLBACK_RENDER_DENSITY], without a
   * trailing `.0` (`2`, `2.625`). The viewer uses `parseFloat`.
   */
  internal fun renderDensityAttr(density: Float?): String {
    val value = density?.takeIf { it > 0f && it.isFinite() } ?: FALLBACK_RENDER_DENSITY
    return if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
  }

  private data class ScreenDevice(
    val id: String,
    val name: String,
    val kind: String,
    val sizeDp: String,
  )

  /** Phone-family device profiles offered for an ordinary (handheld) catalog's screens. */
  private val SCREEN_DEVICES: List<ScreenDevice> =
    listOf(
      ScreenDevice("id:pixel_5", "Pixel 5", "compact phone", "393 × 851 dp"),
      ScreenDevice("id:pixel_7", "Pixel 7", "standard phone", "411 × 914 dp"),
      ScreenDevice("id:pixel_fold", "Pixel Fold", "foldable", "841 × 701 dp"),
      ScreenDevice("id:pixel_tablet", "Pixel Tablet", "tablet", "1280 × 800 dp"),
    )

  /**
   * Watch profiles for a Wear system's screens, with the ids and dimensions the renderer resolves
   * for `@Preview(device = …)` ([ee.schimke.composeai.daemon.devices.DeviceDimensions]). Round
   * shapes first.
   */
  private val WEAR_SCREEN_DEVICES: List<ScreenDevice> =
    listOf(
      ScreenDevice("id:wearos_small_round", "Small round", "Wear OS watch", "192 × 192 dp"),
      ScreenDevice("id:wearos_large_round", "Large round", "Wear OS watch", "227 × 227 dp"),
      ScreenDevice("id:wearos_xl_round", "Extra large round", "Wear OS watch", "240 × 240 dp"),
      ScreenDevice("id:wearos_square", "Square", "Wear OS watch", "180 × 180 dp"),
      ScreenDevice("id:wearos_rect", "Rectangular", "Wear OS watch", "201 × 238 dp"),
    )

  /**
   * The device profiles a screen's "Device size" picker offers: watch shapes for Wear,
   * phones/foldable/tablet otherwise.
   */
  private fun screenDevicesFor(isWearSystem: Boolean): List<ScreenDevice> =
    if (isWearSystem) WEAR_SCREEN_DEVICES else SCREEN_DEVICES
}

/**
 * The contract's spelling of a compositing mode (`screen`, `plus-lighter`) for the `data-`
 * attribute the stylesheet selects on. Taken from `@SerialName` so CSS and wire cannot drift (as
 * [PageNodeLink.wire] does). Not [PageBlendMode.css], which is the CSS keyword; here the stylesheet
 * maps the identifier to a property, so a manifest string can't become one.
 */
internal val PageBlendMode.wire: String
  get() =
    when (this) {
      PageBlendMode.SOURCE_OVER -> "source-over"
      PageBlendMode.SCREEN -> "screen"
      PageBlendMode.MULTIPLY -> "multiply"
      PageBlendMode.PLUS_LIGHTER -> "plus-lighter"
    }
