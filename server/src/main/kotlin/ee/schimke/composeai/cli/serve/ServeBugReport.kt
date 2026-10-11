package ee.schimke.composeai.cli.serve

/**
 * Builds the prefilled GitHub new-issue report for a bug in the preview server itself (process,
 * render lanes, web UI), as opposed to [ServeIssueReport], which files a preview bug against the
 * repo declaring it.
 *
 * Separate because they differ in target and facts: this one always targets [REPO] and describes
 * the deployment (build, JVM, OS, posture, catalogs, render lanes). It lives in the site footer on
 * every page, since a server bug has no preview to anchor to. Prefilled link and GET form for the
 * same reasons as [ServeIssueReport], reusing its helpers so token stripping can't diverge.
 */
internal object ServeBugReport {

  /**
   * The repo that ships the preview server: fixed, since the server has one home. Not
   * `compose-ai-tools`, where the CLI stayed.
   */
  const val REPO: String = "yschimke/compose-preview-server"

  /** Labels pre-applied to reports opened by the server UI. */
  const val LABELS: String = "ui-report,bug,daemon"

  /** The report page's path, offered from the site footer on every browser-facing page. */
  const val PATH: String = "/report-bug"

  /** Query parameter naming the in-server page the visitor pressed "report a bug" from. */
  const val FROM_PARAM: String = "from"

  /**
   * Stand-in for the browser facts block (user agent, viewport, colour scheme), which only the
   * client knows; the page script fills it. With JS off the marker is dropped rather than shipped
   * literally.
   */
  const val CLIENT_PLACEHOLDER: String = "{{client}}"

  /** Facts about the running server, independent of which page the visitor came from. */
  data class Server(
    /** `SERVE_VERSION` — the build the bug is in. */
    val version: String?,
    /** True when the host answers without a token (`--public`). */
    val public: Boolean,
    /** Seconds since the process started; a bug that only appears after a long uptime says so. */
    val uptimeSeconds: Long? = null,
    /** `java.version` (`java.vendor`), as the render JVM reports it. */
    val java: String? = null,
    /** `os.name os.version (os.arch)`. */
    val os: String? = null,
    /** Catalogs that are not cleanly loaded right now, as `<system>: <state>` lines. */
    val unhealthyCatalogs: List<String> = emptyList(),
    /** Most recent daemon-startup / render failures, newest first, already one-line each. */
    val recentFailures: List<String> = emptyList(),
  )

  /**
   * What the visitor was looking at. Every field is optional; the front door yields no page section
   * rather than "unknown" rows.
   */
  data class Page(
    /** In-server path, token-stripped (`/m3/view/Button__filled`). */
    val path: String? = null,
    /** Absolute URL of that page, token-stripped, so a triager can open what the reporter saw. */
    val url: String? = null,
    /** Served design system, when the page belonged to one. */
    val system: String? = null,
    /** The preview on screen, when the page was a viewer or a comparison. */
    val previewId: String? = null,
    /** Delivery provenance as `owner/repo@branch`. */
    val catalog: String? = null,
    /** compose-ai-tools version that produced that catalog — often *not* [Server.version]. */
    val catalogToolVersion: String? = null,
    /** Bundle-verification verdict for the served catalog. */
    val trust: String? = null,
    /** How this session renders: a live daemon, baked PNGs, … */
    val renderLane: String? = null,
    /**
     * Which viewer lane/view the page showed ([viewLabel]), e.g. `design spec — triptych`.
     * Browser-composed views have no URL, so the report can only embed the plain render (and
     * reference); naming the view keeps the images from being mistaken for what the reporter saw
     * (#4261).
     */
    val view: String? = null,
    /** Why the session is degraded, when it is — `<code> — <detail>` lines. */
    val degradations: List<String> = emptyList(),
    /** `/render/<id>.png` at the overrides in force, token-stripped. */
    val renderUrl: String? = null,
    /**
     * Token-stripped `/reference/<id>.png` that was on stage beside the render. Set only where the
     * reporter's URL settles which reference it was; never server-picked, which would assert what
     * the reporter saw (#4765).
     */
    val referenceUrl: String? = null,
    /**
     * Whether the render lane answers without a token (`--public`); see
     * [ServeIssueReport.Context.publicRender].
     */
    val publicRender: Boolean = false,
  )

  /** The comment [body] leaves under "What went wrong" for the reporter to replace. */
  const val WHAT_WENT_WRONG_PROMPT: String =
    "<!-- What were you doing, what did you expect, and what happened instead? -->"

  /**
   * [body] with shared text ([ServeShareTarget]) replacing the "What went wrong" prompt; unchanged
   * when nothing was shared.
   */
  fun withSharedText(body: String, shared: String?): String {
    val text = shared?.trim()?.takeIf { it.isNotEmpty() } ?: return body
    return body.replaceFirst(WHAT_WENT_WRONG_PROMPT, text)
  }

  /** The GitHub new-issue form for [REPO]. A literal — see [ServeIssueReport.action]. */
  fun action(): String = "https://github.com/$REPO/issues/new"

  /**
   * Issue body in markdown. [clientPlaceholder] leaves [CLIENT_PLACEHOLDER] for the page script in
   * the hidden form input; the visible preview passes false.
   */
  fun body(server: Server, page: Page, clientPlaceholder: Boolean = false): String {
    val render = ServeIssueReport.withoutToken(page.renderUrl)?.takeIf { it.isNotBlank() }
    // Same two independent conditions the per-preview report checks: GitHub's camo proxy has to
    // reach the URL, and the lane has to answer it without the token this body strips.
    val embed = render != null && page.publicRender && ServeIssueReport.isEmbeddable(page.renderUrl)
    val reference = ServeIssueReport.withoutToken(page.referenceUrl)?.takeIf { it.isNotBlank() }
    // Both halves or neither, and for the reason the per-preview report gives: one panel of a
    // comparison read as "the render" is worse evidence than the render admitting it is one.
    val embedPair =
      embed &&
        reference != null &&
        ServeIssueReport.isEmbeddable(page.referenceUrl) &&
        // A `|` in either URL would shear the two-cell table it goes in; see the same guard on
        // `ServeIssueReport.body`.
        listOfNotNull(page.renderUrl, page.referenceUrl).none { it.contains('|') }
    return buildString {
      append("### What went wrong\n\n")
      append(WHAT_WENT_WRONG_PROMPT).append("\n\n\n")
      append("### Screenshot\n\n")
      append("<!-- Paste your capture of the page here. -->\n\n\n")
      // The base render goes below the paste slot, labelled for what it is: good evidence for a
      // wrong colour, not for a browser-composed view the server has no URL for (#4261).
      val onView = view(page)
      if (embedPair) {
        // The reference/render pair the page URL identified (#4765), below the paste slot; their
        // diff is browser-composed, so the capture carries it.
        append("### Reference and render").append(onView).append("\n\n")
        append("| Design reference | Render |\n| --- | --- |\n")
        append("| ![reference](").append(reference).append(") | ")
        append("![render](").append(render).append(") |\n\n")
        append(
          "<!-- Those are the comparison's two outer panels, fetched live: they re-render if " +
            "the catalog changes, so they may stop showing what you saw. The diff between them " +
            "is drawn in your browser and has no URL — a pasted capture is the only way it " +
            "reaches this issue, and it stays put because GitHub hosts those pixels itself. " +
            "-->\n\n\n"
        )
      } else if (embed) {
        append("### Base render").append(onView).append("\n\n")
        append("![render](").append(render).append(")\n\n")
        // A reference the pair form refused is still linked.
        reference?.let { append("[Design reference PNG](").append(it).append(")\n\n") }
        append(
          "<!-- That image is a LIVE render: it re-renders if the catalog changes, so it may " +
            "stop showing what you saw. GitHub displays it through Camo, but Camo proxies the " +
            "source URL; it does not make a versioned snapshot. A pasted screenshot of the " +
            "page stays put because GitHub hosts those pixels itself. -->\n\n\n"
        )
      } else if (render != null) {
        append("### Base render").append(onView).append("\n\n")
        append("[PNG at these settings](").append(render).append(")")
        // A comparison on a box GitHub cannot reach still says where both panels live, so a
        // triager who *can* reach it opens the pair rather than half of it.
        reference?.let { append(" · [Design reference PNG](").append(it).append(")") }
        append("\n\n\n")
      }
      append("### Server\n\n")
      append(table(serverRows(server)))
      pageRows(page)
        .takeIf { it.isNotEmpty() }
        ?.let {
          append("\n### Page\n\n")
          append(table(it))
        }
      if (clientPlaceholder) append("\n").append(CLIENT_PLACEHOLDER).append("\n")
      server.unhealthyCatalogs
        .takeIf { it.isNotEmpty() }
        ?.let { append("\n### Catalogs not loaded\n\n").append(fence(it)) }
      server.recentFailures
        .takeIf { it.isNotEmpty() }
        ?.let { append("\n### Recent failures\n\n").append(fence(it)) }
    }
  }

  /**
   * Parenthetical keeping the "Base render" heading from over-claiming when a composed view was
   * showing.
   */
  private fun view(page: Page): String =
    page.view?.trim()?.takeIf { it.isNotEmpty() }?.let { " — you were on the ${text(it)}" } ?: ""

  /** The browser half of the report, filled client-side and spliced over [CLIENT_PLACEHOLDER]. */
  fun clientBlock(rows: List<Pair<String, String>>): String =
    if (rows.isEmpty()) "" else "### Browser\n\n" + table(rows)

  private fun serverRows(server: Server): List<Pair<String, String>> = buildList {
    server.version?.takeIf { it.isNotBlank() }?.let { add("compose-preview" to code(it)) }
    add("Mode" to (if (server.public) "public (open)" else "token-gated"))
    server.uptimeSeconds?.takeIf { it >= 0 }?.let { add("Uptime" to duration(it)) }
    // "Server JVM", not "Java": a project's `javaLauncher` may render on a different JDK.
    server.java?.takeIf { it.isNotBlank() }?.let { add("Server JVM" to code(it)) }
    server.os?.takeIf { it.isNotBlank() }?.let { add("Server OS" to code(it)) }
  }

  private fun pageRows(page: Page): List<Pair<String, String>> = buildList {
    val url = ServeIssueReport.withoutToken(page.url)?.takeIf { it.isNotBlank() }
    val path = page.path?.trim()?.takeIf { it.isNotEmpty() }
    when {
      // The path is the readable identity and the URL is the openable one, so when both are known
      // the row is a link *labelled* by the path rather than a bare URL or a dead code span.
      url != null && path != null -> add("Page" to "[${code(path)}](${cell(url)})")
      url != null -> add("Page" to text(url))
      path != null -> add("Page" to code(path))
    }
    page.system?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Design system" to code(it)) }
    page.previewId?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Preview" to code(it)) }
    page.catalog?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Catalog" to code(it)) }
    page.catalogToolVersion
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.let { add("Catalog rendered by" to "compose-ai-tools ${text(it)}") }
    page.trust?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Trust" to text(it)) }
    page.renderLane?.trim()?.takeIf { it.isNotEmpty() }?.let { add("Render lane" to text(it)) }
    page.view?.trim()?.takeIf { it.isNotEmpty() }?.let { add("View" to text(it)) }
    page.degradations
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .takeIf { it.isNotEmpty() }
      ?.let { add("Degraded" to text(it.joinToString("; "))) }
  }

  /**
   * Two-column table shell. Values arrive composed with raw parts already escaped by [code] /
   * [text]; escaping here would mangle intended markdown.
   */
  private fun table(rows: List<Pair<String, String>>): String = buildString {
    append("| | |\n| --- | --- |\n")
    rows.forEach { (key, value) ->
      append("| ").append(key).append(" | ").append(value).append(" |\n")
    }
  }

  /**
   * Make arbitrary text safe in a markdown table cell: `|` would shear the row and a backtick would
   * close the code span. Backslash first, or it doubles later escapes (same as `bugReport.ts`).
   */
  private fun cell(value: String): String =
    value.replace("\\", "\\\\").replace("|", "\\|").replace("`", "\\`")

  /** A value shown as a code span, with its content escaped. */
  private fun code(value: String): String = "`${cell(value)}`"

  /** A value shown as plain text, with its content escaped. */
  private fun text(value: String): String = cell(value)

  /** Failure text goes in a fence, with inner fence markers neutralised so it can't close early. */
  private fun fence(lines: List<String>): String =
    "```\n" + lines.joinToString("\n") { it.replace("```", "'''") } + "\n```\n"

  /** Compact uptime — `3d 4h`, `12m`, `45s`. Two units is as much as a bug report needs. */
  fun duration(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    if (hours < 24) return if (minutes % 60 == 0L) "${hours}h" else "${hours}h ${minutes % 60}m"
    val days = hours / 24
    return if (hours % 24 == 0L) "${days}d" else "${days}d ${hours % 24}h"
  }

  /**
   * Route prefixes anonymous visitors can never open (UI builder, admin). Mirrored by
   * `serve-web/src/report/visibility.ts`.
   */
  private val PRIVATE_PREFIXES = listOf("/ui-builder", "/admin", "/api/ui-builder", "/agent-access")

  /**
   * Whether [path] ([sanitizeFrom] result) is signed-in only; captures there aren't uploaded to the
   * anonymous-read image lane without opt-in, since the issue is public.
   */
  fun isPrivatePath(path: String?): Boolean {
    val bare = path?.substringBefore('?')?.substringBefore('#') ?: return false
    return PRIVATE_PREFIXES.any { bare == it || bare.startsWith("$it/") }
  }

  /**
   * The visitor's page as a path safe to echo into HTML, links and an issue body. Untrusted browser
   * input, accepted only as a short same-origin absolute path: single leading `/` (not `//`), no
   * scheme, fragment or control characters. Otherwise null and no page section. The token is
   * stripped, since the issue is public.
   */
  fun sanitizeFrom(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.length > MAX_FROM_LENGTH) return null
    if (!value.startsWith("/") || value.startsWith("//")) return null
    if (value.any { it.isISOControl() }) return null
    if (value.contains('#') || value.contains('\\')) return null
    return ServeIssueReport.withoutToken(value)
  }

  /** Long enough for any real viewer link, short enough that a hostile one can't pad the body. */
  private const val MAX_FROM_LENGTH = 2048

  /**
   * What a served path says about itself: system and preview. [previewSegment] stays
   * percent-encoded; decoding would turn `%2B` into a space and `%2F` into a separator, so the
   * caller compares against ids re-encoded the same way.
   */
  data class PageRef(
    val system: String? = null,
    val previewSegment: String? = null,
    /**
     * Which preview route the path was (`p` or `compare`), or null. The comparison report embeds
     * both panels ([Page.referenceUrl]), and only the route tells them apart (#4765).
     */
    val previewRoute: String? = null,
  )

  /**
   * Split a sanitised path into system and preview, mirroring the routes `/p/{name}` and
   * `/compare/{name}` (optionally under `/{system}`) and a bare `/{system}/`. Anything else yields
   * an empty ref.
   */
  fun parsePath(path: String?): PageRef {
    val clean = path?.substringBefore('?')?.trim()?.takeIf { it.isNotEmpty() } ?: return PageRef()
    val segments = clean.split('/').filter { it.isNotEmpty() }
    return when {
      segments.isEmpty() -> PageRef()
      // `/p/<preview>` · `/compare/<preview>` — the rooted single-session form.
      segments.size == 2 && segments[0] in PREVIEW_SEGMENTS ->
        PageRef(previewSegment = segments[1], previewRoute = segments[0])
      // `/<system>/p/<preview>` · `/<system>/compare/<preview>`.
      segments.size == 3 && segments[1] in PREVIEW_SEGMENTS ->
        PageRef(system = segments[0], previewSegment = segments[2], previewRoute = segments[1])
      // A catalog landing. Only when the single segment isn't one of the server's own top-level
      // routes, which are pages of the box rather than of a system.
      segments.size == 1 && segments[0] !in SERVER_SEGMENTS -> PageRef(system = segments[0])
      // `/<system>/<anything-else>` — a design page, the parity dashboard, a format comparison.
      // The system still holds; the preview does not.
      segments.size >= 2 && segments[0] !in SERVER_SEGMENTS -> PageRef(system = segments[0])
      else -> PageRef()
    }
  }

  /**
   * What the viewer was showing (`design spec (triptych)`, `motion`, …), from the reporter's query;
   * those views are browser-composed with no URL of their own (#4261). Strictly an allowlist of
   * values the viewer writes, since the query is untrusted and lands in a public issue. Null for
   * the plain lane and non-viewer pages.
   */
  fun viewLabel(from: String?): String? {
    val params = queryParams(from)
    val explode = params["exploded"]?.lowercase()?.let { it in EXPLODE_ON } == true
    val lane = LANES[params["mode"]?.lowercase()]
    // The spec lane's view qualifies the lane. A spec URL naming no view is on the default
    // ([ServeWeb.SPEC_DEFAULT_VIEW], now the triptych), so resolve it rather than drop it.
    val spec =
      if (params["mode"]?.lowercase() != "spec") null
      else
        SPEC_VIEW_LABELS[
          params["specView"]?.lowercase()?.takeIf { it in SPEC_VIEW_LABELS }
            ?: ServeWeb.SPEC_DEFAULT_VIEW]
    val laneLabel = lane?.let { if (spec == null) it else "$it ($spec)" }
    return when {
      laneLabel != null && explode -> "$laneLabel, exploded layers"
      laneLabel != null -> laneLabel
      explode -> "exploded layers"
      else -> null
    }
  }

  /**
   * The design reference the comparison URL named (`?reference=`), or null for the first. Left
   * percent-encoded like [PageRef.previewSegment].
   */
  fun referenceSegment(from: String?): String? =
    queryParams(from)["reference"]?.takeIf { it.isNotEmpty() }

  /** Whether `?mode=` says the design-spec lane was up, read exactly as [viewLabel] does. */
  fun onSpecLane(from: String?): Boolean = queryParams(from)["mode"]?.lowercase() == "spec"

  /**
   * The reporter's query as a map, last value winning (like `URLSearchParams.get`). Values stay
   * percent-encoded: they are matched against an ASCII allowlist, and decoding would only add the
   * `+`-to-space bug.
   */
  private fun queryParams(from: String?): Map<String, String> {
    val query = from?.substringAfter('?', missingDelimiterValue = "").orEmpty()
    if (query.isEmpty()) return emptyMap()
    return query
      .split('&')
      .filter { it.isNotEmpty() }
      .associate { pair ->
        pair.substringBefore('=') to pair.substringAfter('=', missingDelimiterValue = "")
      }
  }

  /** `?mode=` values the viewer writes, as a reader of the issue would say them. */
  private val LANES =
    mapOf(
      // `png` is the static render lane — the default, and what the embedded PNG already is.
      "spec" to "design spec",
      "source" to "source view",
      "motion" to "motion playback",
      "rc" to "Remote Compose (canvas player)",
      "rc-wasm" to "Remote Compose (wasm player)",
      "wasm" to "wasm lane",
      "live" to "live daemon lane",
    )

  /**
   * Spec-lane view labels; mirrors `spec/views.ts` (default `triptych`). Only `spec` is renamed, to
   * avoid "design spec (spec)".
   */
  private val SPEC_VIEW_LABELS =
    mapOf(
      "spec" to "reference only",
      "diff" to "diff",
      "triptych" to "triptych",
      "slider" to "slider",
    )

  /** Truthy `?exploded=` spellings — mirrors `explodeParamOn` in `viewer/renderQuery.ts`. */
  private val EXPLODE_ON = setOf("", "1", "true", "on", "yes")

  /** The viewer's route segment, as [PageRef.previewRoute] reports it. */
  const val VIEWER_ROUTE: String = "p"

  /** The focused comparison's route segment, as [PageRef.previewRoute] reports it. */
  const val COMPARE_ROUTE: String = "compare"

  /** Route prefixes whose next segment is a preview id. */
  private val PREVIEW_SEGMENTS = setOf(VIEWER_ROUTE, COMPARE_ROUTE)

  /**
   * Top-level paths belonging to the server rather than a design system, so they never name a
   * catalog.
   */
  private val SERVER_SEGMENTS =
    setOf(
      "status",
      "status.json",
      "version",
      "healthz",
      "readyz",
      "assets",
      "docs",
      "playground",
      "report-bug",
      "p",
      "compare",
      // Query-mode catalog pages: the catalog comes from `?session=`, not the first segment.
      "pages",
      "parity",
      // Same for the motion browser.
      "motion",
      "usage",
      "render",
      "reference",
      "hero",
      "api",
      "admin",
      "wasm",
    )
}
