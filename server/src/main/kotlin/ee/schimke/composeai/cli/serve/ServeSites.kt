package ee.schimke.composeai.cli.serve

/**
 * Top-level sites: a catalog already published at `/<system>/` also reachable on its own hostname,
 * where it looks like the only thing on the box (e.g. `m3.preview.coo.ee` serving
 * `preview.coo.ee/m3-catalog/`).
 *
 * A view, not a second deployment: same session, pixels, daemon and caches; a site costs one map
 * lookup. It changes only what the request resolves to and what pages say:
 * - the site's system is the session, so root-mounted routes (`/`, `/p/<id>`, `/render/<id>`, …)
 *   answer for it;
 * - links use an empty base path, staying on the custom domain;
 * - the front door, back button and cross-system nav are suppressed;
 * - `/status`, `/robots.txt` and `/sitemap.xml` are scoped to this system.
 *
 * On a site host the canonical `/<system>/` form redirects to the root form, and other systems'
 * paths 404. Grants no access: a site can only name an already-served system, behind the same
 * gates.
 */
data class ServeSites(private val byHost: Map<String, String>) {

  /** True when no site hosts are configured — the default, and the fast path on every request. */
  val isEmpty: Boolean
    get() = byHost.isEmpty()

  /** The configured site hosts, normalised (lowercase, no port). */
  val hosts: Set<String>
    get() = byHost.keys

  /** The systems reachable as top-level sites, deduplicated. */
  val systems: Set<String>
    get() = byHost.values.toSet()

  /**
   * Configured `host to system` pairs in order, as [of] takes them; what [ServeSiteAdmin] rebuilds
   * from and writes to `catalogs.json`, so runtime changes get the same validation as a restart.
   */
  val pairs: List<Pair<String, String>>
    get() = byHost.entries.map { it.key to it.value }

  /**
   * The system [rawHost] is a site for, or null (main host, IP/localhost, unknown vhost). [rawHost]
   * comes straight from `X-Forwarded-Host` / `Host`; see [normalizeHost].
   */
  fun systemFor(rawHost: String?): String? {
    if (byHost.isEmpty() || rawHost == null) return null
    return byHost[normalizeHost(rawHost) ?: return null]
  }

  /** The host this [system] is published on as a top-level site, or null when it isn't. */
  fun hostFor(system: String): String? = byHost.entries.firstOrNull { it.value == system }?.key

  companion object {
    /** No sites configured. Every request then behaves exactly as it did before this existed. */
    val EMPTY: ServeSites = ServeSites(emptyMap())

    /**
     * A config hostname: letter/digit/hyphen labels separated by dots. Narrow on purpose, since it
     * is compared against an attacker-supplied `Host` header.
     */
    private val HOST_RE =
      Regex(
        "[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+"
      )

    /**
     * Comparable form of a `Host` value: lowercased, port dropped, IPv6 brackets and trailing root
     * dot stripped, so direct hits and proxied requests land on the same key. Null when the rest
     * isn't a hostname we'd accept as config.
     */
    fun normalizeHost(raw: String): String? {
      var host = raw.trim().lowercase()
      if (host.startsWith("[")) {
        // IPv6 literal: never a site host, but strip it cleanly rather than mangling the port cut.
        host = host.substringAfter('[').substringBefore(']')
      } else {
        host = host.substringBefore(':')
      }
      host = host.trimEnd('.')
      return host.takeIf { HOST_RE.matches(it) }
    }

    /**
     * Build a site map from `host=system` pairs, dropping malformed ones and ones naming an
     * unserved system; [onProblem] gets one line per drop. First host wins on duplicates, as in the
     * catalog config.
     */
    fun of(
      pairs: List<Pair<String, String>>,
      /**
       * The served systems, or null to skip the check. Nullable rather than "empty means skip",
       * since a server with no catalogs must still drop sites naming anything.
       */
      knownSystems: Set<String>? = null,
      onProblem: (String) -> Unit = {},
    ): ServeSites {
      val byHost = LinkedHashMap<String, String>()
      for ((rawHost, system) in pairs) {
        val host = normalizeHost(rawHost)
        if (host == null) {
          onProblem("site host '$rawHost' is not a hostname")
          continue
        }
        if (!SYSTEM_RE.matches(system)) {
          onProblem("site '$host' names an invalid system '$system'")
          continue
        }
        if (knownSystems != null && system !in knownSystems) {
          onProblem("site '$host' names '$system', which this server does not serve")
          continue
        }
        // A site's system id is also its canonical first path segment, which the redirect keys off;
        // an id colliding with a rooted route (`render`, `p`, `api`) would break every image on the
        // site, and is already unreachable on the main host. Refused.
        if (system in RESERVED_SYSTEMS) {
          onProblem("site '$host' names '$system', which collides with a built-in route")
          continue
        }
        if (byHost.putIfAbsent(host, system) != null) {
          onProblem("duplicate site host '$host' (keeping '${byHost[host]}')")
        }
      }
      return if (byHost.isEmpty()) EMPTY else ServeSites(byHost)
    }

    /**
     * Parse `--sites` (`m3.preview.coo.ee=m3-catalog,…`); blank ⇒ [EMPTY]. Unreadable entries are
     * reported through [onProblem] and skipped, like `--catalogs`.
     */
    fun parse(
      spec: String?,
      /** As [of]: null skips the served-system check, an empty set fails every entry. */
      knownSystems: Set<String>? = null,
      onProblem: (String) -> Unit = {},
    ): ServeSites {
      if (spec.isNullOrBlank()) return EMPTY
      val pairs =
        spec.split(',').mapNotNull { raw ->
          val entry = raw.trim()
          if (entry.isEmpty()) return@mapNotNull null
          val host = entry.substringBefore('=').trim()
          val system = entry.substringAfter('=', "").trim()
          if (system.isEmpty()) {
            onProblem("site entry '$entry' is not <host>=<system>")
            return@mapNotNull null
          }
          host to system
        }
      return of(pairs, knownSystems, onProblem)
    }

    /** Same alphabet [ServeCatalogsConfig] accepts for a catalog id — a site can only name one. */
    private val SYSTEM_RE = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    /**
     * First path segments the server routes itself, which a site system id may not be (Ktor scores
     * constant segments above `/{system}`, so such a catalog is unreachable anyway). Enumerated
     * from [ServeHttpServer]'s routing, including routes built from constants
     * (`ServeRcFonts.URL_BASE`, `/hero`, `/auth/…`).
     *
     * Keep it complete when adding a top-level route: a missing entry lets a site swallow the
     * route, and since the site interceptor uses this as its "not a session" allowlist, the route
     * 404s on every site host. Every routed segment is reserved unconditionally, even for opt-in
     * lanes, since a flag can be turned on later. `ServeSitesReservedRoutesTest` checks for
     * omissions against the routing block; `ServeTopLevelSiteTest` drives real routes on a site
     * host.
     */
    internal val RESERVED_SYSTEMS =
      setOf(
        "healthz",
        "readyz",
        "version",
        "status",
        "status.json",
        // Registered from `ServeBugReport.PATH`, so a text search for the literal misses it (see
        // #4319).
        "report-bug",
        "robots.txt",
        "sitemap.xml",
        "favicon.svg",
        "favicon.ico",
        "apple-touch-icon.png",
        // App icons and the web manifest, fetched from the site's own origin.
        "icons",
        "manifest.webmanifest",
        // The push service worker (`PUSH_SERVICE_WORKER_PATH`). Its scope is bounded by its path,
        // so it has to live at the root of whichever host a person turns notifications on from.
        "push-sw.js",
        "assets",
        "ui-builder",
        // Optional builder guide redirect. Reserve it even when the guide URL is unset.
        "start",
        // The root-mounted A2UI playground (`GET /a2ui`), which a viewer served at `/p/{name}` on
        // a site host links to. A catalog named `a2ui` would otherwise shadow it.
        "a2ui",
        "wasm",
        // `/wasm-private/<access>/<system>/…`, the token-in-path twin of `/wasm/…`.
        "wasm-private",
        "rc-player",
        "rc-player-wasm",
        // Registered dynamically from `ServeRcFonts.URL_BASE` — the vendored Remote Compose
        // typefaces. Easy to miss precisely because the path is built from a constant.
        "rc-fonts",
        "doc-player",
        "hero",
        "social",
        "admin",
        "auth",
        // `/agent-access/…`: the grant flow, whose approval page a human opens.
        "agent-access",
        // `/oauth/…` and `/.well-known/…`: the OAuth façade MCP clients discover; a 404 here breaks
        // the bootstrap request with no hint why.
        "oauth",
        ".well-known",
        // `POST /mcp` — the aggregate catalog MCP endpoint. Reserved unconditionally so a site
        // host cannot intercept this stable machine route with its styled 404.
        "mcp",
        "bundles",
        "bundle",
        "bundle.zip",
        "docs",
        "d",
        // `POST /images` and `GET /i/<id>.png`: the image lane.
        "images",
        "i",
        "playground",
        // `GET /pg/<token>` — Stage-2 playground redemption.
        "pg",
        "api",
        "ws",
        "p",
        // `GET /parallel/<previewId>`: the cross-catalog layer diff, which works on a site (see
        // #4838).
        "parallel",
        // `GET /usage/<previewId>` — the viewer's Source panel.
        "usage",
        "render",
        // `GET /motion/<previewId>.apng`: the Motion lane.
        "motion",
        // `GET /spatial/<previewId>/…`: scene documents and textures.
        "spatial",
        "history",
        "compare",
        "reference",
        "pages",
        // `GET /pages.json`: the design-pages index as data.
        "pages.json",
        // `GET /tags/<previewId>`: the published element tag index ([ServeTagIndex]).
        "tags",
        // `GET /schemas/<name>.json` — the UI-builder document and mutation JSON Schemas (#1114).
        "schemas",
        "rc-compare",
        "parity",
        "parity.json",
        "refresh",
        // `GET /feed.xml`: the catalog change feed (only registered when configured).
        "feed.xml",
        "index.json",
        "iframe.html",
      )
  }
}
