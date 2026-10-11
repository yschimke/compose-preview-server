package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping

/**
 * `/robots.txt` and `/sitemap.xml`, for crawlers and link-preview fetchers.
 *
 * The split is cheap published bytes vs. work: catalog landings, viewer pages and baked PNGs (what
 * shared links point at) stay open; anything that costs the box is closed (playground compiles,
 * `/bundle.zip`, the wasm tier, `/history/render`, and render URLs with override query params,
 * which re-render on demand).
 *
 * Per-system lanes use `*` and `$` patterns because the system id is the first path segment;
 * crawlers that matter honour them, and naive prefix parsers fail open by matching nothing. That's
 * why the link-preview group avoids them.
 */
internal object ServeSiteIndex {

  /**
   * Link-preview fetchers: single fetches of a shared URL and its `og:image`. Their own permissive
   * group so they don't inherit `Crawl-delay` (a delayed unfurl is a missing one), written as
   * literal prefixes since their parsers are simple. `Slack-ImgProxy` fetches the image separately
   * from `Slackbot-LinkExpanding`.
   *
   * Only single-fetch preview agents belong here: a crawler obeys only the most specific group
   * naming it, so listing a general indexer (`Googlebot`, `Applebot`, `Bingbot`) would exempt it
   * from the crawl delay and render-lane rules. Those already get everything an unfurl needs from
   * the general group.
   */
  private val PREVIEW_FETCHERS =
    listOf(
      "Slackbot-LinkExpanding",
      "Slackbot",
      "Slack-ImgProxy",
      "Twitterbot",
      "facebookexternalhit",
      "LinkedInBot",
      "Discordbot",
      "TelegramBot",
      "WhatsApp",
      "SkypeUriPreview",
      "Iframely",
    )

  /**
   * Lanes closed to everyone, preview fetchers included: signed-in-only surfaces, expiring
   * capability URLs, or code execution. Literal prefixes so every parser agrees.
   */
  private val CLOSED_TO_ALL =
    listOf(
      // Token-gated admin API — 404s without the header anyway, but naming it keeps it off the
      // crawl budget and out of any "URLs we found" report.
      "/admin/",
      // The GitHub OAuth dance. A crawler following it burns a state nonce and lands on an error.
      "/auth/",
      // Uploaded documents behind expiring capability URLs. Not secret, but not ours to publish,
      // and every one of them is dead by the time a crawler would revisit.
      "/docs",
      "/d/",
      // Compiles Kotlin on demand. The single most expensive thing this server can be asked to do.
      "/playground",
    )

  /** Lanes closed to indexing crawlers: work, machine formats, or operational endpoints. */
  private val CLOSED_TO_CRAWLERS =
    listOf(
      // Machine lanes. Nothing here renders as a page, and `/api/previews` is the whole grid.
      "/api/",
      "/*/api/",
      // Operational. `/status` covers `/status.json`; a crawler polling readiness is pure noise.
      "/status",
      "/healthz",
      "/readyz",
      "/version",
      // The parity dashboard recomputes per request against the live catalog.
      "/parity",
      "/*/parity",
      // Comparison surfaces: the reference/RC lanes decode and diff images, and the compare page
      // itself drops to a dynamic render while its RC lane is still pending.
      "/compare",
      "/*/compare",
      "/reference/",
      "/*/reference/",
      "/rc-compare/",
      "/*/rc-compare/",
      // Reads an old render out of the local git object store, per request.
      "/history/render/",
      "/*/history/render/",
      // Multi-megabyte downloads. A crawler pulling every catalog's zip would saturate the box.
      "/bundle.zip",
      "/*/bundle.zip",
      "/bundle/",
      "/*/bundle/",
      "/wasm/",
      "/rc-player-wasm/",
      "/rc-player/",
      "/doc-player/",
      // Storybook-compatibility surface: a JSON index and a chrome-less render frame. Both exist
      // for screenshot tools that are pointed at them deliberately, and neither is a page.
      "/index.json",
      "/*/index.json",
      "/iframe.html",
      "/*/iframe.html",
      // Non-PNG render products (figma-svg, inspection layers, `.rc` documents) are made on request
      // through daemon-backed producers, and the viewer page names them.
      "/*.svg$",
      "/*.slots$",
      "/*.a11y$",
      "/*.annotations$",
      "/*.rc$",
    )

  /**
   * Render URLs with a query string: `render/<id>.png` without one serves baked bytes, with
   * overrides it re-renders live, and the grid links the override forms. Scoped to the render lane
   * only, because pages with state in their query are exactly what people share, and Googlebot
   * (bound by the general group) must still read their Open Graph block.
   */
  private val NO_QUERY_ON_RENDER = listOf("/render/*?", "/*/render/*?")

  /**
   * `robots.txt` for this server. A token-gated server disallows everything (crawled URLs would all
   * 404). [sitemapUrl] is advertised only when there is a sitemap, as the absolute URL the standard
   * requires.
   */
  fun robotsTxt(isPublic: Boolean, sitemapUrl: String?): String = buildString {
    appendLine("# compose-preview — https://github.com/yschimke/compose-ai-tools")
    if (!isPublic) {
      appendLine("# Token-gated server: every URL needs a token this crawler does not have.")
      appendLine()
      appendLine("User-agent: *")
      appendLine("Disallow: /")
      return@buildString
    }
    appendLine("# Catalog landings, preview viewers and their baked PNGs are open to crawl.")
    appendLine("# Render-on-demand, code-running and bulk-download lanes are not.")
    appendLine()

    appendLine("User-agent: *")
    CLOSED_TO_ALL.forEach { appendLine("Disallow: $it") }
    CLOSED_TO_CRAWLERS.forEach { appendLine("Disallow: $it") }
    appendLine("# Override params re-render a preview; the bare path serves the bake.")
    NO_QUERY_ON_RENDER.forEach { appendLine("Disallow: $it") }
    appendLine("Crawl-delay: 10")
    appendLine()

    appendLine("# Link unfurlers: one fetch per shared URL, and they need the og:image too.")
    PREVIEW_FETCHERS.forEach { appendLine("User-agent: $it") }
    CLOSED_TO_ALL.forEach { appendLine("Disallow: $it") }
    sitemapUrl?.let {
      appendLine()
      appendLine("Sitemap: $it")
    }
  }

  /** One crawlable catalog: its system id, its preview ids, and when it was generated. */
  data class CatalogEntry(
    val system: String,
    val previewIds: List<String>,
    /** The catalog's `generatedAt` provenance, already ISO-8601. Null when it declared none. */
    val lastModified: String?,
  )

  /**
   * The sitemap URL ceiling (the standard's 50,000 per file). Truncating beats an oversized file a
   * crawler rejects; [sitemapXml] notes it in a comment.
   */
  private const val MAX_URLS = 50_000

  /**
   * `sitemap.xml` for the published catalogs: the front door, catalog landings and preview viewers,
   * not images (found via `og:image`).
   *
   * `<lastmod>` comes from the catalog's `generatedAt`, not the refresh clock, which touches every
   * catalog hourly and would teach crawlers to ignore the field. `changefreq`/`priority` are
   * omitted since Google ignores them. On a top-level site ([ServeSites]) [rootedSystem] is mounted
   * at `/`, its URLs drop `/<system>` and there is no front door entry, avoiding duplicate URLs.
   */
  fun sitemapXml(
    origin: String,
    entries: List<CatalogEntry>,
    rootedSystem: String? = null,
  ): String {
    val urls = buildList {
      // The front door changes whenever any catalog it indexes does. A rooted site has none; its
      // landing is emitted below as `<origin>/`.
      if (rootedSystem == null) {
        add(origin + "/" to entries.mapNotNull { it.lastModified }.maxOrNull())
      }
      for (entry in entries) {
        val base =
          if (entry.system == rootedSystem) origin
          else origin + "/" + WebEscaping.urlEncodeSegment(entry.system)
        add("$base/" to entry.lastModified)
        for (id in entry.previewIds) {
          add("$base/p/${WebEscaping.urlEncodeSegment(id)}" to entry.lastModified)
        }
      }
    }
    return buildString {
      appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
      if (urls.size > MAX_URLS) {
        appendLine("<!-- truncated to $MAX_URLS of ${urls.size} URLs (sitemap size limit) -->")
      }
      appendLine("""<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">""")
      for ((loc, lastmod) in urls.take(MAX_URLS)) {
        appendLine("  <url>")
        appendLine("    <loc>${WebEscaping.htmlEscape(loc)}</loc>")
        lastmod?.let(::w3cDateTime)?.let {
          appendLine("    <lastmod>${WebEscaping.htmlEscape(it)}</lastmod>")
        }
        appendLine("  </url>")
      }
      appendLine("</urlset>")
    }
  }

  /**
   * [raw] if it is a valid W3C datetime, else null (no `<lastmod>`). Validated because it comes
   * from a third-party `catalog.json`, and one malformed date makes a crawler discard the whole
   * sitemap.
   */
  private fun w3cDateTime(raw: String): String? {
    // Shape first: `java.time` accepts spellings the sitemap profile does not (a local date-time
    // with no offset, say), so the regex fixes which forms are allowed at all.
    if (!W3C_DATETIME.matches(raw)) return null
    // Then meaning: the regex alone accepts impossible values like `2026-13-40T25:99:99+99:99`.
    val parsed = runCatching {
      if (raw.length == 10) java.time.LocalDate.parse(raw) else java.time.OffsetDateTime.parse(raw)
    }
      .isSuccess
    return raw.takeIf { parsed }
  }

  /**
   * The sitemap datetime shapes (`2026-07-17`, `2026-07-17T12:34Z`, `…T12:34:56.789+02:00`); shape
   * only, [w3cDateTime] also requires a real instant.
   */
  private val W3C_DATETIME =
    Regex("""^\d{4}-\d{2}-\d{2}(T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2}))?$""")
}
