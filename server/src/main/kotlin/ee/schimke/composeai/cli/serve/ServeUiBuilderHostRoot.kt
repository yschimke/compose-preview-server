package ee.schimke.composeai.cli.serve

import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/**
 * The UI builder served at the ROOT of its own host (`--ui-builder-host-root`, with
 * `--ui-builder-host ui.coo.ee`): `https://ui.coo.ee/` is the editor's home and
 * `https://ui.coo.ee/<design>` is a design, where every other host keeps `/ui-builder/…`.
 *
 * ## Why a route selector, not a rewrite
 *
 * Ktor routes on `call.request.path()`, which nothing upstream of routing can change, so the rooted
 * form is a second registration of the same page routes ([ServeHttpServer.uiBuilderPageRoutes] with
 * an empty base) behind [UiBuilderHostRootSelector]. The handlers read only their route parameters,
 * so `/designs` and `/ui-builder/designs` are the same handler with the same `{designId}`.
 *
 * ## What the builder host keeps
 *
 * Every server route: a first segment in [ServeSites.RESERVED_SYSTEMS] — `/start`, `/api`, `/mcp`,
 * `/agent-access`, `/oauth`, `/.well-known`, `/auth`, `/admin`, `/healthz`, `/readyz`, `/version`,
 * `/status`, `/robots.txt`, `/sitemap.xml`, the icons and manifest, `/assets`, `/wasm`, the Remote
 * Compose players and fonts, the image lane, and `/ui-builder` itself. That list is already the
 * closed, test-enforced allowlist a top-level site uses for the same question ("is this a route of
 * the server's, or mine?"), so a route added later is carved out here the day it is added there.
 * What the builder host gives up is the catalog namespace, `/{system}/…`: it is the builder's host,
 * and catalogs stay on every other host.
 *
 * A design whose id IS a reserved segment (a design named `api`) has no rooted URL; its
 * `/ui-builder/<id>` URL keeps working, and [uiBuilderRootRedirect] leaves it alone.
 */
internal class UiBuilderHostRootSelector(private val isBuilderHost: (ApplicationCall) -> Boolean) :
  RouteSelector() {

  override suspend fun evaluate(
    context: RoutingResolveContext,
    segmentIndex: Int,
  ): RouteSelectorEvaluation {
    // The root itself (`/`) is the existing `get("/")`, which serves the editor's home on this
    // host;
    // matching it here as well would make two routes compete for one page.
    val first = context.segments.getOrNull(segmentIndex)
    if (first.isNullOrEmpty()) return RouteSelectorEvaluation.FailedPath
    if (first in ServeSites.RESERVED_SYSTEMS) return RouteSelectorEvaluation.FailedPath
    if (!isBuilderHost(context.call)) return RouteSelectorEvaluation.FailedPath
    // Constant quality and no segment consumed. The quality is what makes `/my-design` resolve to
    // the builder rather than to the global `/{system}` (a path parameter, 0.8) — the first level
    // decides, and a carved-out first segment never reaches this line, so `/healthz` and `/api/…`
    // are untouched.
    return RouteSelectorEvaluation.Success(RouteSelectorEvaluation.qualityConstant)
  }

  override fun toString(): String = "(ui-builder host root)"
}

/**
 * Where a request for a `/ui-builder/…` PAGE on the rooted builder host should go instead, or null
 * to serve it where it is.
 *
 * Only a browser navigation moves: a `GET`/`HEAD` asking for HTML. Everything else the editor and
 * the server write under `/ui-builder/` — the versioned bundle (`/ui-builder/v/<digest>/…`), the
 * renderer runtimes, the service worker, `fetch`es and form POSTs — is served at its prefixed path
 * on this host too, so an older editor bundle, a POST/303 handoff or a cached asset URL keeps
 * working.
 *
 * Temporary (302), not permanent: root mode is a flag, and a browser that cached a permanent
 * `/ui-builder/x` → `/x` would keep sending a person to `/x` after the operator turned it off,
 * where `/x` is a catalog id again.
 */
internal fun uiBuilderRootRedirect(
  method: String,
  path: String,
  query: String,
  accept: String?,
): String? {
  if (method != "GET" && method != "HEAD") return null
  if (accept == null || "text/html" !in accept) return null
  if (path != "/ui-builder" && !path.startsWith("/ui-builder/")) return null
  // `trimStart` so `/ui-builder//evil.example` cannot become `//evil.example`, a protocol-relative
  // URL to another origin: the target always has exactly one leading slash.
  val rest = path.removePrefix("/ui-builder").trimStart('/')
  val first = rest.substringBefore('/')
  val decodedFirst = runCatching {
    java.net.URLDecoder.decode(first, Charsets.UTF_8)
  }
    .getOrDefault(first)
  // A design (or page) whose rooted spelling would name a server route has no rooted URL.
  if (decodedFirst in ServeSites.RESERVED_SYSTEMS) return null
  // The bundle's own paths are assets, never pages, even when a browser is pointed at one.
  if (decodedFirst == UI_BUILDER_VERSION_PREFIX || decodedFirst == "runtime") return null
  if (decodedFirst == UI_BUILDER_SERVICE_WORKER) return null
  return "/" + rest + (if (query.isEmpty()) "" else "?$query")
}

/** `/ui-builder/v/<digest>/…`, mirroring [ServeHttpServer]'s versioned-bundle segment. */
internal const val UI_BUILDER_VERSION_PREFIX = "v"

/** The builder's service worker, served only at its unversioned `/ui-builder/` path. */
internal const val UI_BUILDER_SERVICE_WORKER = "ui-builder-sw.js"

/**
 * The `<meta>` that tells the editor where its pages live, emitted only when that is not the
 * default `/ui-builder/` — i.e. on the builder host in root mode. An editor bundle that does not
 * read it keeps writing `/ui-builder/<id>` links, which this host still serves (and redirects to
 * the rooted form on navigation).
 */
internal const val UI_BUILDER_BASE_PATH_META = "ui-builder-base-path"

/**
 * The `/ui-builder/…` path a request on the rooted builder host stands for, or null when it is one
 * of the server's own routes ([ServeSites.RESERVED_SYSTEMS]) and keeps its own meaning. Mirrors
 * [UiBuilderHostRootSelector]: on that host, every other path is a builder page, and `/` is the
 * editor's home. Used so the page's headers ([ServePagePolicy.servesAs]) are those of the
 * `/ui-builder/` form, not of whatever a bare root path would otherwise be.
 */
internal fun uiBuilderRootCanonicalPath(path: String): String? {
  if (path == "/" || path.isEmpty()) return "/ui-builder/"
  val first = path.trimStart('/').substringBefore('/')
  val decodedFirst = runCatching {
    java.net.URLDecoder.decode(first, Charsets.UTF_8)
  }
    .getOrDefault(first)
  if (decodedFirst in ServeSites.RESERVED_SYSTEMS) return null
  return "/ui-builder" + path
}

/**
 * The first compose-ui-builder editor that reads [UI_BUILDER_BASE_PATH_META]
 * (compose-ui-builder #659, released in 3.104.0). An older editor hard-codes `/ui-builder/`: served
 * at the root it cannot tell which design to open.
 */
internal const val UI_BUILDER_BASE_PATH_MIN_EDITOR: String = "3.104.0"

/**
 * Why the editor in [dir] cannot be served at the root of its host, or null when it can.
 *
 * Read from the archive's own manifest ([ServeUiBuilderEditor.MANIFEST_FILE]) by version, because
 * the manifest carries no feature list yet. An editor with no manifest predates it, and so predates
 * the base path too. ui.coo.ee turned root mode on while serving editor 3.103.0, and every page
 * failed; refusing here keeps the host on `/ui-builder/` instead.
 */
internal fun uiBuilderRootEditorProblem(dir: java.io.File?): String? {
  dir ?: return "no editor directory is served"
  val manifest =
    ServeUiBuilderEditor.readManifest(dir)
      ?: return "the editor at $dir has no ${ServeUiBuilderEditor.MANIFEST_FILE}, so it predates " +
        "base-path support (compose-ui-builder $UI_BUILDER_BASE_PATH_MIN_EDITOR)"
  return if (editorVersionAtLeast(manifest.version, UI_BUILDER_BASE_PATH_MIN_EDITOR)) null
  else
    "editor ${manifest.version} predates base-path support (compose-ui-builder " +
      "$UI_BUILDER_BASE_PATH_MIN_EDITOR)"
}

/**
 * Whether [version] is at least [minimum], comparing the leading `major.minor.patch` numerically. A
 * pre-release of the minimum (`3.104.0-SNAPSHOT`) counts as the minimum: it is built from that
 * line. An unparsable version is not trusted.
 */
internal fun editorVersionAtLeast(version: String, minimum: String): Boolean {
  fun parts(v: String): List<Int>? =
    v.substringBefore('-').split('.').let { p ->
      if (p.size < 3) null else p.take(3).map { it.toIntOrNull() ?: return null }
    }
  val have = parts(version) ?: return false
  val need = parts(minimum) ?: return false
  for (i in 0 until 3) if (have[i] != need[i]) return have[i] > need[i]
  return true
}
