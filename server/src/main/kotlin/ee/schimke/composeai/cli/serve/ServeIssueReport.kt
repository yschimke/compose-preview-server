package ee.schimke.composeai.cli.serve

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Builds the prefilled GitHub new-issue link the viewer offers beside "source", carrying the facts
 * a triager would ask for (system, preview, catalog build, deep link, render PNG).
 *
 * A prefilled link rather than server-side filing: [ServeGithubAuth] discards the OAuth token, and
 * holding issue-write tokens on a public box would be an escalation for nothing; the visitor's
 * browser files under their own identity. Works signed out too.
 *
 * The screenshot is pasted, not just linked (the `/render` link re-renders against later catalog
 * state); "Copy PNG" puts exact bytes on the clipboard. From the comparison, the body carries both
 * the reference and render ([Context.referenceUrl]); the browser-composed diff stays a paste, as in
 * [ServeBugReport].
 */
internal object ServeIssueReport {

  /**
   * Stand-in for the render URL in [body]; the viewer JS swaps in the live `/render` URL on each
   * refresh.
   */
  const val RENDER_PLACEHOLDER: String = "{{render}}"

  /** Filled by the focused comparison once its browser-side scorer has completed. */
  const val RAW_SCORES_PLACEHOLDER: String = "{{rawScores}}"

  /**
   * Stand-in for the locator's `overrides:` value on a page whose controls change after it is
   * served, substituted in the same pass as [RENDER_PLACEHOLDER] so locator and render URL can't
   * disagree. Only the value is a placeholder, so `report/locator.ts` can match `overrides:
   * {{overrides}}` exactly. Template form only; JS-off visitors file the server's body.
   */
  const val OVERRIDES_PLACEHOLDER: String = "{{overrides}}"

  /**
   * Stand-in for the selection's `element:` / `bounds:` lines. A whole line substituted with its
   * newline, so an empty selection reproduces the server's block byte for byte. Template form only.
   */
  const val SELECTION_PLACEHOLDER: String = "{{selection}}"

  /**
   * The classification line's fixed opening, which `<cp-report-classification>` finds and rewrites.
   * The answer reaches GitHub through the `<select>` (its value is the `labels` parameter), so it
   * works without JS; the body line repeats it in prose. Hence [CLASSIFICATION_UNSTATED] points at
   * the label rather than pre-writing a claim JS-off visitors can't update.
   */
  const val CLASSIFICATION_PREFIX: String = "**Where it belongs:** "

  /**
   * What the line says until a browser rewrites it from the control. See [CLASSIFICATION_PREFIX].
   */
  const val CLASSIFICATION_UNSTATED: String = "as labelled on this issue"

  /**
   * Stand-in for locator blocks chosen after serving: the comparison wall's report is page-scoped,
   * but `<cp-compare-wall>` fills this with one block per ticked row (using the writer ported to
   * `report/locator.ts`). A whole line substituted with its newline, so nothing ticked (or JS off)
   * reproduces the page-scoped body byte for byte.
   */
  const val LOCATORS_PLACEHOLDER: String = "{{locators}}"

  const val LOCATOR_FENCE: String = "compose-parity-locator/v1"

  /** The only plane `v1` accepts for [Bounds]; see that type and D1. */
  const val RENDER_PIXELS: String = "render-pixels"

  /** Repo bugs fall back to when a session names no source of its own — the renderer is ours. */
  const val FALLBACK_REPO: String = "yschimke/compose-ai-tools"

  /** The facts a report carries. All but [repo] are optional; missing rows are dropped. */
  data class Context(
    /** `owner/name` the issue is filed against — see [repoFor]. */
    val repo: String,
    /**
     * The preview's flattened id. Null on a page-scoped report (the comparison wall singles out no
     * preview), which drops the `| Preview |` row and makes [locator] null.
     */
    val previewId: String? = null,
    /** Human label, when the manifest recorded one; the title falls back to [previewId]. */
    val previewLabel: String? = null,
    /** The served design system (`wear-m3`), when this session is a catalog. */
    val system: String? = null,
    /** Stable catalog component identity. */
    val componentId: String? = null,
    /** Design reference compared with this exact preview. */
    val referenceId: String? = null,
    /** Preview-id axes only; live controls belong exclusively to [overrides]. */
    val variant: String = "",
    /**
     * The selected element ([Locator.element]). Usually null server-side since selection happens
     * after serving; the page JS fills [SELECTION_PLACEHOLDER] instead.
     */
    val element: String? = null,
    /** The selected region, when the reporter dragged one. See [Bounds] and [element]. */
    val bounds: Bounds? = null,
    /** The complete, normalised query map consumed by the render lane. */
    val overrides: Map<String, String> = emptyMap(),
    /** GitHub blob URL of the preview's source file (from [ServeUrls.githubBlobUrl]). */
    val sourceUrl: String? = null,
    /**
     * Delivery provenance as `owner/repo@branch` — which catalog build the visitor was looking at.
     */
    val catalog: String? = null,
    /** compose-ai-tools version that rendered the catalog, from its `catalog.json`. */
    val toolVersion: String? = null,
    /** Absolute viewer URL for this preview. Token-bearing URLs are stripped by [withoutToken]. */
    val viewerUrl: String? = null,
    /** Absolute focused comparison URL for this preview/reference pair. */
    val comparisonUrl: String? = null,
    /**
     * Absolute URL of the page a preview-less report came from, including its query (e.g. the
     * wall's lane).
     */
    val pageUrl: String? = null,
    /** Absolute `/render/<id>.png` URL at the overrides in force when the page was served. */
    val renderUrl: String? = null,
    /**
     * Absolute `/reference/<id>.png` URL of the reference shown beside the render, same pin and
     * generation as [renderUrl]. Set only by the focused comparison, so the issue shows both sides
     * (#4765). Null elsewhere: other lanes have no reference on stage or keep it out of the URL.
     */
    val referenceUrl: String? = null,
    /**
     * Whether the render lane answers without a session token (`--public`). [withoutToken] strips
     * tokens from issue bodies, so on a token-gated box an embedded render would always 404.
     * Defaults to false (link form).
     */
    val publicRender: Boolean = false,
    /** Browser-computed parity measurements; absent until the focused comparison finishes. */
    val rawScores: RawScores? = null,
  )

  data class RawScores(
    val structuralMatch: Double,
    val pixelsChanged: Double,
    val proportionDifference: Double? = null,
  )

  data class Locator(
    val repository: String,
    val system: String,
    val componentId: String,
    val previewId: String,
    val referenceId: String,
    val variant: String,
    val overrides: Map<String, String>,
    /**
     * The element a selection named, and the region it covered.
     *
     * Reserved ahead of a selector existing, because both parsers ignore unknown keys: adding the
     * selection to `v1` after the format froze would have produced reports that indexed cleanly
     * with the selection silently dropped — no strict-parser rejection to notice, no error
     * anywhere. The focused comparison fills them now (`<cp-element-selection>`), through
     * [SELECTION_PLACEHOLDER] rather than through this writer, since the choice is made after the
     * page is served.
     *
     * [element] is the `testTag` **verbatim** and is only a usable identity while exactly one node
     * carries it; [bounds] may be absent for a tag whose every carrying node had a zero-area box,
     * and is absent for every selection made before a region was dragged.
     */
    val element: String? = null,
    val bounds: Bounds? = null,
    val revision: String? = null,
  )

  /**
   * A selected region **and the plane it is measured in**, which is the half a bare rectangle
   * leaves out.
   *
   * Three spaces are in play and they disagree: a tag selection comes from the index in render
   * pixels, a drag selection is in display pixels, and an acceptance wants the canonical plane. D1
   * settles that both tag-index producers publish `render-pixels` and that the canonical-plane
   * transform is a step of the *comparison* — a plane is a property of a comparison, the index is a
   * property of a render — so `v1` carries only that space, and an element that never moved cannot
   * report as moved because two ends of the wire assumed different planes.
   */
  data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val space: String = RENDER_PIXELS,
  ) {
    /**
     * Both parsers' invariants, checked at construction: a rectangle the producer refuses would
     * silently drop the whole issue from the index on the next workflow run.
     */
    init {
      require(space == RENDER_PIXELS) { "bounds space must be $RENDER_PIXELS, was $space" }
      // The origin may be negative: tagged nodes can extend past the render root, and both
      // tag-index producers emit signed coordinates. Clipping belongs to the comparison's plane
      // transform.
      require(width >= 1 && height >= 1) {
        "bounds must have a positive extent, was ${width}x$height"
      }
    }
  }

  /**
   * Which repo a preview's bug belongs to: the catalog's source repo (where the preview code lives,
   * possibly a fork), else its delivery repo, else [FALLBACK_REPO].
   */
  fun repoFor(source: ServeWeb.CatalogSource?, provenance: ServeWeb.CatalogProvenance?): String =
    source?.repo?.trim()?.takeIf { it.isNotEmpty() }
      ?: provenance?.repo?.trim()?.takeIf { it.isNotEmpty() }
      ?: FALLBACK_REPO

  /**
   * Issue body in markdown. [renderPlaceholder] and [overridesPlaceholder] swap the render URL and
   * locator `overrides:` value for placeholders the viewer JS fills (together, since they describe
   * one frame); JS-off visitors get the real values. [rawScoresPlaceholder] is for the one page
   * that measures ([RAW_SCORES_PLACEHOLDER]).
   */
  fun body(
    ctx: Context,
    renderPlaceholder: Boolean = false,
    selectionPlaceholder: Boolean = false,
    locatorsPlaceholder: Boolean = false,
    overridesPlaceholder: Boolean = false,
    rawScoresPlaceholder: Boolean = false,
  ): String {
    val rows = buildList {
      ctx.system?.trim()?.takeIf { it.isNotEmpty() }?.let { add("| Design system | `$it` |") }
      ctx.previewId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("| Preview | `$it` |") }
      withoutToken(ctx.sourceUrl)?.takeIf { it.isNotBlank() }?.let { add("| Source | $it |") }
      ctx.catalog?.takeIf { it.isNotBlank() }?.let { add("| Catalog | `$it` |") }
      ctx.toolVersion
        ?.takeIf { it.isNotBlank() }
        ?.let { add("| Rendered by | compose-ai-tools $it |") }
      // Asked of the caller rather than inferred: only the focused comparison scores, and the
      // viewer now names a reference too (#5000) but has no matching number.
      val scores =
        if (rawScoresPlaceholder && !ctx.referenceId.isNullOrBlank()) RAW_SCORES_PLACEHOLDER
        else ctx.rawScores?.let(::formatRawScores)
      scores?.let { add("| Raw comparison | `$it` |") }
    }
    // Only a body that has a render gets `{{render}}`; nothing would substitute it on a page-scoped
    // report.
    val hasRender = !withoutToken(ctx.renderUrl).isNullOrBlank()
    val render =
      if (renderPlaceholder) RENDER_PLACEHOLDER.takeIf { hasRender }
      else withStage(withoutToken(ctx.renderUrl))?.takeIf { it.isNotBlank() }
    // Embeddability is decided from the real URL in both body forms: GitHub's proxy must reach it,
    // and the lane must answer without the stripped token.
    val embed = render != null && ctx.publicRender && isEmbeddable(ctx.renderUrl)
    val reference = withoutToken(ctx.referenceUrl)?.takeIf { it.isNotBlank() }
    // The pair is embedded only when both halves can be; half a comparison misleads.
    val embedPair =
      embed &&
        reference != null &&
        ctx.publicRender &&
        isEmbeddable(ctx.referenceUrl) &&
        // A `|` in either URL would break the two-cell table, so fall back to the single-image
        // form.
        listOfNotNull(ctx.renderUrl, ctx.referenceUrl).none { it.contains('|') }
    val links = buildList {
      withoutToken(ctx.pageUrl)?.takeIf { it.isNotBlank() }?.let { add("[Open this page]($it)") }
      withoutToken(ctx.viewerUrl)
        ?.takeIf { it.isNotBlank() }
        ?.let { add("[Open this preview]($it)") }
      withoutToken(ctx.comparisonUrl)
        ?.takeIf { it.isNotBlank() }
        ?.let { add("[Open comparison]($it)") }
      // Only worth its own line when the image isn't already showing it.
      if (!embed) render?.let { add("[PNG at these settings]($it)") }
      // Without the embedded pair, still link the reference so both sides are reachable.
      if (!embedPair) reference?.let { add("[Design reference PNG]($it)") }
    }
    return buildString {
      append("### What's wrong\n\n")
      append("<!-- What did you expect to see, and what did you get? -->\n\n\n")
      append("$CLASSIFICATION_PREFIX$CLASSIFICATION_UNSTATED\n\n")
      append("### Screenshot\n\n")
      if (embedPair) {
        // Both outer panels side by side as a two-cell table (#4765); stacked images read as
        // unrelated.
        append("| Design reference | Render |\n| --- | --- |\n")
        append("| ![reference](").append(reference).append(") | ")
        append("![${altText(ctx)}]($render) |\n\n")
        append(
          "<!-- The DIFF between those two panels is composed in your browser out of these very " +
            "pixels, so it has no URL to embed. Paste it here: the \"Report a problem\" launcher " +
            "on the comparison has a capture control that copies the whole view, a region, or " +
            "one element to your clipboard. -->\n\n\n"
        )
        append(
          "<!-- Both images above are LIVE lanes: they re-render if the catalog changes, so " +
            "they may stop showing what you saw. GitHub displays them through Camo, but Camo " +
            "proxies the source URL; it does not make a versioned snapshot. A pasted capture " +
            "stays put because GitHub hosts those pixels itself. -->\n\n\n"
        )
      } else if (embed) {
        append("![${altText(ctx)}]($render)\n\n")
        append(
          "<!-- That image is a LIVE render: it re-renders if the catalog changes, so it may " +
            "stop showing what you saw. GitHub displays it through Camo, but Camo proxies the " +
            "source URL; it does not make a versioned snapshot. For a copy that stays put, use " +
            "Copy PNG in the viewer's \"Export & direct links\" panel and paste it here — " +
            "GitHub then hosts the pixels itself. -->\n\n\n"
        )
      } else if (ctx.previewId.isNullOrBlank()) {
        // A page-scoped report has no export panel; point at the launcher's capture control
        // instead.
        append(
          "<!-- Paste it here. The \"Report a problem\" launcher on the page has a capture " +
            "control that copies the whole view, a region, or one element to your clipboard, so " +
            "Ctrl-V / Cmd-V lands it in this issue. -->\n\n\n"
        )
      } else {
        append(
          "<!-- Paste it here. The viewer's \"Export & direct links\" panel has a Copy PNG " +
            "button that puts the image itself on your clipboard, so Ctrl-V / Cmd-V lands the " +
            "exact render in this issue. -->\n\n\n"
        )
      }
      append(if (ctx.previewId.isNullOrBlank()) "### Which page\n\n" else "### Which preview\n\n")
      append("| | |\n| --- | --- |\n")
      append(rows.joinToString("\n"))
      if (links.isNotEmpty()) append("\n\n").append(links.joinToString(" · "))
      append("\n")
      locator(ctx)?.let {
        append("\n").append(locatorBlock(it, selectionPlaceholder, overridesPlaceholder))
      }
      // No leading blank line, so deleting the placeholder leaves exactly the body written without
      // it.
      if (locatorsPlaceholder) append(LOCATORS_PLACEHOLDER).append("\n")
    }
  }

  private fun formatRawScores(scores: RawScores): String = buildString {
    append(
      "%.1f%% structural match; %.2f%% pixels changed"
        .format(Locale.ROOT, scores.structuralMatch, scores.pixelsChanged)
    )
    scores.proportionDifference?.let {
      append("; %.1f%% proportion difference".format(Locale.ROOT, it))
    }
  }

  fun locator(ctx: Context): Locator? {
    val preview = ctx.previewId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val system = ctx.system?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val component = ctx.componentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val reference = ctx.referenceId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return Locator(
      repository = ctx.repo,
      system = system,
      componentId = component,
      previewId = preview,
      referenceId = reference,
      variant = ctx.variant,
      overrides = ctx.overrides,
      // Verbatim: the tag is JSON-quoted on the wire and edge whitespace is part of its identity.
      // Only an empty tag is dropped.
      element = ctx.element?.takeIf { it.isNotEmpty() },
      bounds = ctx.bounds,
      revision = ctx.catalog?.trim()?.takeIf { it.isNotEmpty() },
    )
  }

  /** Catalog-authored component id, with the parity dashboard's stable route-id fallback. */
  fun componentIdFor(preview: ServePreview): String =
    preview.componentId?.takeIf { it.isNotBlank() }
      ?: run {
        val ideal = preview.id.indexOf("__ideal")
        if (ideal > 0) preview.id.substring(0, ideal)
        else {
          val parts = preview.id.split("__")
          val theme = parts.indices.lastOrNull { it >= 1 && parts[it] in setOf("light", "dark") }
          if (theme == null) preview.id
          else parts.filterIndexed { index, _ -> index != theme }.joinToString("__")
        }
      }

  /** Axis segments already encoded by the served preview id, never live overrides. */
  fun variantFor(preview: ServePreview): String =
    preview.id.substringAfter("__", missingDelimiterValue = "").replace("__", "/")

  /**
   * The locator block as markdown. [selectionPlaceholder] and [overridesPlaceholder] swap in
   * placeholders the page JS fills ([OVERRIDES_PLACEHOLDER]); the server body uses real values.
   */
  fun locatorBlock(
    locator: Locator,
    selectionPlaceholder: Boolean = false,
    overridesPlaceholder: Boolean = false,
  ): String = buildString {
    append("```$LOCATOR_FENCE\n")
    append("repository: ${locator.repository}\n")
    append("system: ${locator.system}\n")
    append("component: ${locator.componentId}\n")
    append("preview: ${locator.previewId}\n")
    append("reference: ${locator.referenceId}\n")
    append("variant: ${locator.variant}\n")
    if (overridesPlaceholder) append("overrides: $OVERRIDES_PLACEHOLDER\n")
    else append("overrides: ${canonicalOverrides(locator.overrides)}\n")
    if (selectionPlaceholder) append("$SELECTION_PLACEHOLDER\n")
    else {
      locator.element?.let { append("element: ${canonicalElement(it)}\n") }
      locator.bounds?.let { append("bounds: ${canonicalBounds(it)}\n") }
    }
    locator.revision?.let { append("revision: $it\n") }
    append("```\n")
  }

  /**
   * Every locator block in a body, in order (one issue may name several components). Contradictory
   * blocks are for the producer to reject.
   */
  fun locatorsFromBody(body: String): List<Locator> =
    body.split("```$LOCATOR_FENCE\n").drop(1).mapNotNull { rest ->
      val content = rest.substringBefore("\n```", missingDelimiterValue = "")
      content.takeIf { it.isNotEmpty() }?.let { locatorFromContent(it) }
    }

  fun locatorFromBody(body: String): Locator? {
    val fenced = body.substringAfter("```$LOCATOR_FENCE\n", missingDelimiterValue = "")
    if (fenced.isEmpty()) return null
    val content = fenced.substringBefore("\n```", missingDelimiterValue = "")
    if (content.isEmpty()) return null
    return locatorFromContent(content)
  }

  private fun locatorFromContent(content: String): Locator? {
    val fields =
      content
        .lineSequence()
        .mapNotNull { line ->
          val separator = line.indexOf(':')
          if (separator <= 0) null
          // Trim both ends, as the producer does, so the two parsers agree.
          else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        .toMap()
    val overrides =
      runCatching { parseOverrides(fields["overrides"] ?: return null) }.getOrNull() ?: return null
    return Locator(
      repository = fields["repository"]?.takeIf { it.isNotBlank() } ?: return null,
      system = fields["system"]?.takeIf { it.isNotBlank() } ?: return null,
      componentId = fields["component"]?.takeIf { it.isNotBlank() } ?: return null,
      previewId = fields["preview"]?.takeIf { it.isNotBlank() } ?: return null,
      referenceId = fields["reference"]?.takeIf { it.isNotBlank() } ?: return null,
      variant = fields["variant"] ?: return null,
      overrides = overrides,
      element =
        fields["element"]?.let { runCatching { parseElement(it) }.getOrNull() ?: return null },
      bounds = fields["bounds"]?.let { runCatching { parseBounds(it) }.getOrNull() ?: return null },
      revision = fields["revision"]?.takeIf { it.isNotBlank() },
    )
  }

  fun canonicalOverrides(overrides: Map<String, String>): String {
    val sorted = overrides.entries.sortedWith { a, b -> compareCodePoints(a.key, b.key) }
    return Json.encodeToString(
      JsonObject.serializer(),
      JsonObject(sorted.associate { it.key to JsonPrimitive(it.value) }),
    )
  }

  /**
   * `element` as a JSON string, so a tag can't become syntax: a newline would inject fields and a
   * fence could end the block. Quoting also preserves edge whitespace.
   */
  fun canonicalElement(element: String): String =
    Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(element))

  /** Canonical bounds JSON with code-point key order, comparable byte for byte. */
  fun canonicalBounds(bounds: Bounds): String =
    Json.encodeToString(
      JsonObject.serializer(),
      JsonObject(
        // Code point order: height < space < width < x < y.
        linkedMapOf(
          "height" to JsonPrimitive(bounds.height),
          "space" to JsonPrimitive(bounds.space),
          "width" to JsonPrimitive(bounds.width),
          "x" to JsonPrimitive(bounds.x),
          "y" to JsonPrimitive(bounds.y),
        )
      ),
    )

  private fun parseElement(value: String): String {
    val element =
      Json.parseToJsonElement(value).jsonPrimitive.takeIf { it.isString }?.contentOrNull
        ?: error("element must be a JSON string")
    require(element.isNotEmpty()) { "element must not be empty" }
    require(canonicalElement(element) == value) { "element is not canonical JSON" }
    return element
  }

  private fun parseBounds(value: String): Bounds {
    val json = Json.parseToJsonElement(value).jsonObject
    val space = json["space"]?.jsonPrimitive?.contentOrNull ?: error("bounds names no space")
    // `v1` accepts only the plane both tag-index producers publish; see [Bounds] and D1.
    require(space == RENDER_PIXELS) { "bounds space must be $RENDER_PIXELS" }
    fun extent(key: String): Int =
      json[key]?.jsonPrimitive?.content?.toIntOrNull() ?: error("bounds $key must be an integer")
    // [Bounds] enforces the origin, extent and space invariants itself, so a rectangle that fails
    // them throws here and the caller's `runCatching` turns it into a refused locator.
    val bounds =
      Bounds(x = extent("x"), y = extent("y"), width = extent("width"), height = extent("height"))
    require(json.keys.size == 5) { "bounds carries unknown keys" }
    require(canonicalBounds(bounds) == value) { "bounds are not canonical JSON" }
    return bounds
  }

  private fun parseOverrides(value: String): Map<String, String> =
    Json.parseToJsonElement(value).jsonObject.mapValues { (_, element) ->
      element.jsonPrimitive.takeIf { it.isString }?.contentOrNull
        ?: error("override values must be strings")
    }

  private fun compareCodePoints(a: String, b: String): Int {
    var ai = 0
    var bi = 0
    while (ai < a.length && bi < b.length) {
      val ac = a.codePointAt(ai)
      val bc = b.codePointAt(bi)
      if (ac != bc) return ac.compareTo(bc)
      ai += Character.charCount(ac)
      bi += Character.charCount(bc)
    }
    return (a.length - ai).compareTo(b.length - bi)
  }

  /**
   * Markdown-safe alt text: label or id with `]`, `[` and `|` stripped (the alt span and the
   * two-cell table).
   */
  private fun altText(ctx: Context): String {
    val what =
      ctx.previewLabel?.trim()?.takeIf { it.isNotEmpty() }
        ?: ctx.previewId?.trim()?.takeIf { it.isNotEmpty() }
        ?: "render"
    return what.replace('[', ' ').replace(']', ' ').replace('|', ' ').trim()
  }

  /**
   * Whether GitHub can render [url] inline: camo fetches it, so it must be public HTTPS.
   * Conservative: localhost, LAN and plain HTTP keep the link form. Reachability only; see
   * [Context.publicRender].
   */
  /**
   * A render URL with `?bg=auto` ([ServeRenderMatte]). Renders are transparent and GitHub shows raw
   * alpha on white, so dark-first catalogs would file blank rectangles. `auto` picks a ground per
   * render (bezel kept, self-painting screens left alone, invisible stickers get plates); `&bg=off`
   * gets raw alpha. Unchanged when `bg` is already present or the URL isn't ours.
   */
  internal fun withStage(url: String?): String? {
    val value = url?.trim()?.takeIf { it.isNotEmpty() } ?: return url
    if (!value.contains("/render/")) return value
    val query = value.substringAfter('?', "")
    if (query.split('&').any { it.substringBefore('=') == ServeRenderMatte.PARAM }) return value
    return value +
      (if (query.isEmpty()) "?" else "&") +
      ServeRenderMatte.PARAM +
      "=" +
      ServeRenderMatte.Mode.AUTO.wire
  }

  fun isEmbeddable(url: String?): Boolean {
    val u = url?.trim() ?: return false
    if (!u.startsWith("https://")) return false
    val host = u.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
    val name = host.substringBeforeLast(':').trim('[', ']')
    if (name.isEmpty()) return false
    // A public host is a dotted name. Single-label intranet names, `.local`, and raw loopback /
    // private addresses are all unreachable from camo.
    if (!name.contains('.') || name.endsWith(".local") || name.endsWith(".internal")) return false
    if (name == "localhost" || name.endsWith(".localhost")) return false
    return !isPrivateIpv4(name)
  }

  /** Loopback and RFC 1918 literals, which are dotted but still unreachable from outside. */
  private fun isPrivateIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return false
    val (a, b) = parts[0].toInt() to parts[1].toInt()
    return a == 127 || a == 10 || a == 0 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
  }

  /**
   * GitHub's new-issue form for [repo], used as a GET `<form action>`: the live body goes into an
   * input value, never an anchor `href` (a navigation sink CodeQL rightly flags), and the browser
   * encodes the query.
   */
  fun action(repo: String): String = "https://github.com/$repo/issues/new"

  /**
   * [url] without `token=`: the session token is the capability to drive the server and must never
   * reach a public issue. The override query is kept so the link reproduces what the reporter saw.
   */
  fun withoutToken(url: String?): String? {
    val u = url?.takeIf { it.isNotBlank() } ?: return null
    val cut = u.indexOf('?')
    if (cut < 0) return u
    val kept =
      u.substring(cut + 1).split('&').filter { it.isNotEmpty() && !it.startsWith("token=") }
    return if (kept.isEmpty()) u.substring(0, cut)
    else u.substring(0, cut) + "?" + kept.joinToString("&")
  }
}
