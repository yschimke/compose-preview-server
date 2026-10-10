package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import java.net.URI

/**
 * The `Content-Security-Policy` every HTML response from this server carries, written once.
 *
 * The edge proxy deliberately sets none (`deploy/image/Caddyfile`): the policy describes what the
 * markup loads, so it lives next to the markup. [install] adds it to every `text/html` response —
 * the pages [ServeWeb] renders, and the Wasm app shells served from disk — and to nothing else, so
 * JSON, images, SVG exports and scripts are unchanged.
 *
 * What the pages load, and so what each directive allows:
 * * **Scripts** come from this origin only: the serve-web bundles under `/assets/`, the vendored
 *   players (`/rc-player/`, `/doc-player/…`), and the Wasm apps' `.mjs` glue. Pages also carry
 *   inline `<script>` bootstraps, inline import maps and a few inline handlers, so
 *   `'unsafe-inline'` stays; moving them to nonces is a separate, larger change. There is no
 *   `'unsafe-eval'`: nothing served here calls `eval`, `new Function` or string timers (the Lottie
 *   player is lottie-web's light build, which has no expression engine), and the policy keeps it
 *   that way.
 * * **Wasm** needs `'wasm-unsafe-eval'` to compile, and only on the paths in [WASM_APP_PREFIXES],
 *   which serve a Kotlin/Wasm app. `'wasm-unsafe-eval'` permits WebAssembly compilation only; it
 *   does not re-enable JavaScript `eval`.
 * * **Styles**: the page stylesheets plus inline `<style>` and `style=` attributes. The Remote
 *   Compose player adds a Google Fonts stylesheet for a document that names a `google:` family,
 *   whose faces come from `fonts.gstatic.com`.
 * * **Images**: this origin, `data:` and `blob:` (captured screenshots, object URLs for renders)
 *   and `raw.githubusercontent.com`, where the history strip's past renders are published.
 * * **Fetches and sockets**: this origin (which covers the same-host `ws:`/`wss:` live sockets),
 *   `data:`/`blob:` URLs the report capture reads back, and `raw.githubusercontent.com` for the
 *   published history manifest. The UI-builder editor also reaches `openrouter.ai`, for the
 *   guidelines check and browser-owned chat a person runs on their own key. The editor calls the
 *   provider directly; this allowance does not admit provider access from catalog runtime frames.
 * * **Frames**: only this origin's own apps (`/wasm/…`, `/rc-player-wasm/…`,
 *   `/ui-builder/runtime/…`).
 * * **Forms** post to this origin, and the issue-report forms open GitHub's new-issue page. A form
 *   whose answer redirects elsewhere — sign-in continuing to GitHub, an OAuth approval returning to
 *   its client — needs that destination allowed too, since browsers apply `form-action` across the
 *   redirect: see [allowFormAction].
 * * **Framing**: `frame-ancestors 'self'`, matching the proxy's `X-Frame-Options SAMEORIGIN`,
 *   except on the pages that are framed by design ([isFramable]), which carry no `frame-ancestors`
 *   at all — the same exemption the proxy makes.
 * * **Sandbox**: an app shell whose code this server did not write — a catalog's Wasm app, the
 *   Remote Compose Wasm player, a UI-builder renderer runtime — runs with `sandbox allow-scripts`
 *   (and no `allow-same-origin`) wherever it is not deliberately framed, so it gets an opaque
 *   origin even when someone opens its URL top-level. Without it a shared link to `/wasm/<system>/`
 *   would run that catalog's code with this origin, next to the UI-builder editor's `localStorage`
 *   (where a person's OpenRouter key is kept). See [sandboxFor].
 */
internal object ServePagePolicy {
  const val HEADER: String = "Content-Security-Policy"

  private const val RAW_GITHUB = "https://raw.githubusercontent.com"
  private const val OPENROUTER = "https://openrouter.ai"
  private const val GITHUB = "https://github.com"
  private const val GOOGLE_FONTS_CSS = "https://fonts.googleapis.com"
  private const val GOOGLE_FONTS_FILES = "https://fonts.gstatic.com"

  /**
   * Paths whose HTML is the shell of a Kotlin/Wasm app: the per-catalog apps (public and private),
   * the Remote Compose Wasm player, and the UI builder editor and its pinned renderer runtimes.
   */
  private val WASM_APP_PREFIXES =
    listOf("/wasm/", "/wasm-private/", "/rc-player-wasm/", "/ui-builder/")

  /** The directive an app shell runs under when it must not have this origin. */
  const val SANDBOX: String = "sandbox allow-scripts"

  /** The fetch-metadata request header naming what a request is for (a document, a frame, …). */
  const val FETCH_DEST_HEADER: String = "Sec-Fetch-Dest"

  /**
   * Renderer runtimes. The editor only ever mounts them in `sandbox="allow-scripts"` frames and
   * every asset under the prefix already answers with `Access-Control-Allow-Origin: *`, so they are
   * sandboxed unconditionally: an opaque origin is what they always had.
   */
  private val ALWAYS_SANDBOXED_PREFIXES = listOf("/ui-builder/runtime/")

  /**
   * App shells sandboxed whenever they are NOT loaded as a frame. When framed, the embedding
   * `<iframe sandbox>` decides, and two embeddings deliberately keep the real origin: a TRUSTED
   * catalog's Wasm app (`allow-scripts allow-same-origin`, for the Cache API and history APIs the
   * Compose runtime touches) and the Remote Compose Wasm player (whose ready/error messages the
   * viewer and the `.rc` permalink accept only from this origin; the permalink frames it with no
   * sandbox at all). A response-level `sandbox` would override both, so it is applied to top-level
   * loads only.
   */
  private val CATALOG_APP_PREFIXES = listOf("/wasm/", "/wasm-private/")
  private val RC_PLAYER_PREFIX = "/rc-player-wasm/"

  /** `Sec-Fetch-Dest` values of a request loading a page into a frame. */
  private val FRAME_DESTINATIONS = setOf("iframe", "frame")

  /**
   * Whether the policy at [path] depends on the request's [FETCH_DEST_HEADER], in which case the
   * response must say `Vary: Sec-Fetch-Dest` so a cache never answers a top-level load with the
   * copy fetched for a frame.
   */
  fun variesByFetchDest(path: String): Boolean =
    CATALOG_APP_PREFIXES.any { path.startsWith(it) } || path.startsWith(RC_PLAYER_PREFIX)

  /**
   * Whether the HTML at [path], requested with `Sec-Fetch-Dest: [fetchDest]`, runs sandboxed.
   *
   * A framed load is identified only by the header a browser sets itself, which page script cannot
   * forge — and a frame from another site gets partitioned storage, not this origin's. Browsers
   * omit fetch metadata on a plain-HTTP origin other than `localhost` (`serve --lan`), and there
   * the two kinds of shell part ways:
   * * a **catalog's app** fails closed — sandboxed. Code a catalog producer built is the thing this
   *   guards against, and the app already runs opaque in every untrusted catalog's frame, so a
   *   trusted one only loses its Cache API there;
   * * the **Remote Compose player** is this server's own player, not catalog code, and its frames
   *   stop working without their origin; so only a load the browser *says* is not a frame is
   *   sandboxed.
   *
   * [serverOwned] exempts a shell this server's own distribution ships at a catalog-app path — the
   * packaged Wasm frontend `/wasm/<system>/` falls back to for a catalog with no app of its own
   * ([markServerOwned]). It is a top-level app by design (history navigation, live sockets) and
   * runs no code a catalog producer wrote, so it has the trust of the editor itself.
   */
  fun sandboxFor(path: String, fetchDest: String?, serverOwned: Boolean = false): Boolean {
    if (ALWAYS_SANDBOXED_PREFIXES.any { path.startsWith(it) }) return true
    if (serverOwned) return false
    val dest = fetchDest?.trim()?.lowercase()
    return when {
      CATALOG_APP_PREFIXES.any { path.startsWith(it) } -> dest !in FRAME_DESTINATIONS
      path.startsWith(RC_PLAYER_PREFIX) -> !dest.isNullOrEmpty() && dest !in FRAME_DESTINATIONS
      else -> false
    }
  }

  private val EXTRA_FORM_ACTIONS = AttributeKey<MutableSet<String>>("ServePagePolicy.formAction")

  private val SERVER_OWNED = AttributeKey<Unit>("ServePagePolicy.serverOwned")

  /**
   * Mark this response as a shell from the server's own distribution rather than a catalog's build,
   * so it keeps this origin when opened top-level — see [sandboxFor]. Only the route that chose the
   * directory knows which it served, so it is the one that says.
   */
  fun markServerOwned(call: ApplicationCall) {
    call.attributes.put(SERVER_OWNED, Unit)
  }

  private val PHASE = PipelinePhase("PageContentPolicy")

  /**
   * Pages another frame may embed, mirroring the proxy's `X-Frame-Options` exemption: the Storybook
   * story render (`/iframe.html`, `/<system>/iframe.html`), and the Wasm apps the viewer and the UI
   * builder mount in sandboxed, opaque-origin frames.
   */
  fun isFramable(path: String): Boolean =
    path.endsWith("/iframe.html") ||
      path.startsWith("/wasm/") ||
      path.startsWith("/ui-builder/runtime/")

  fun hostsWasmApp(path: String): Boolean =
    WASM_APP_PREFIXES.any { path.startsWith(it) } ||
      path == "/ui-builder" ||
      // The reference snapshot embeds the released editor in srcdoc, which inherits this CSP.
      Regex("/(?:[^/]+/)?reference/[^/]+\\.html").matches(path)

  /**
   * Where a page may fetch from. The UI-builder editor also reaches OpenRouter, where a person's
   * own key runs the guidelines check and the PKCE sign-in exchanges its code for that key — the
   * editor's shell only, never the runtimes it frames.
   */
  private fun connectSrc(path: String): List<String> = buildList {
    add("'self'")
    add("data:")
    add("blob:")
    add(RAW_GITHUB)
    if (isUiBuilderEditor(path)) add(OPENROUTER)
  }

  private fun isUiBuilderEditor(path: String): Boolean =
    (path == "/ui-builder" || path.startsWith("/ui-builder/")) &&
      !path.startsWith("/ui-builder/runtime/")

  /**
   * The policy for an HTML response at [path], with [formActions] added to `form-action`, for a
   * request whose `Sec-Fetch-Dest` was [fetchDest] (null when absent) for a shell that is or is not
   * [serverOwned] — see [sandboxFor].
   */
  fun forPath(
    path: String,
    formActions: Collection<String> = emptyList(),
    fetchDest: String? = null,
    serverOwned: Boolean = false,
  ): String {
    val scriptSrc = buildList {
      add("'self'")
      add("'unsafe-inline'")
      if (hostsWasmApp(path)) add("'wasm-unsafe-eval'")
    }
    val formAction = (listOf("'self'", GITHUB) + formActions).distinct()
    val directives = buildList {
      add("default-src 'self'")
      add("script-src ${scriptSrc.joinToString(" ")}")
      add("style-src 'self' 'unsafe-inline' $GOOGLE_FONTS_CSS")
      add("font-src 'self' data: $GOOGLE_FONTS_FILES")
      add("img-src 'self' data: blob: $RAW_GITHUB")
      add("media-src 'self' data: blob:")
      add("connect-src ${connectSrc(path).joinToString(" ")}")
      add("worker-src 'self'")
      add("frame-src 'self'")
      add("object-src 'none'")
      add("base-uri 'self'")
      add("form-action ${formAction.joinToString(" ")}")
      if (!isFramable(path)) add("frame-ancestors 'self'")
      if (sandboxFor(path, fetchDest, serverOwned)) add(SANDBOX)
    }
    return directives.joinToString("; ")
  }

  /**
   * The `form-action` source that admits a form whose answer redirects to [uri]: its origin for a
   * network URL, or its bare scheme for an app's private-use scheme (`vscode:`, `cursor:`). `null`
   * when [uri] names neither, in which case nothing is added.
   */
  fun formActionSource(uri: String): String? {
    val parsed = runCatching { URI(uri) }.getOrNull() ?: return null
    val scheme = parsed.scheme?.lowercase()?.takeIf { SCHEME.matches(it) } ?: return null
    val host = parsed.host?.takeIf { it.isNotBlank() }
    if (scheme != "http" && scheme != "https") {
      // A private-use scheme is opened by the app that registered it; the scheme is the whole
      // destination as far as the browser is concerned.
      return if (scheme == "javascript" || scheme == "data" || scheme == "blob") null
      else "$scheme:"
    }
    host ?: return null
    val port = parsed.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
    return "$scheme://${host.lowercase()}$port"
  }

  private val SCHEME = Regex("[a-z][a-z0-9+.-]*")

  /**
   * Let this response's page submit a form that ends up at [uri] — the approval page an OAuth
   * client's redirect URI answers from. A no-op when [uri] yields no [formActionSource].
   */
  fun allowFormAction(call: ApplicationCall, uri: String) {
    val source = formActionSource(uri) ?: return
    call.attributes.computeIfAbsent(EXTRA_FORM_ACTIONS) { linkedSetOf() }.add(source)
  }

  /**
   * Sets [HEADER] on every `text/html` response that has not set one itself. [formActions] is read
   * per response: the destinations a sign-in redirect may continue to (the GitHub callback host and
   * the top-level site hosts), which can change while the server runs.
   *
   * Runs as its own phase just before `ContentEncoding`, where the message is the page's own
   * [OutgoingContent] with its declared type rather than a compressed wrapper.
   */
  fun install(application: Application, formActions: () -> Collection<String>) {
    application.sendPipeline.insertPhaseBefore(ApplicationSendPipeline.ContentEncoding, PHASE)
    application.sendPipeline.intercept(PHASE) { message ->
      val content = message as? OutgoingContent ?: return@intercept
      val type = content.contentType ?: return@intercept
      if (!type.match(ContentType.Text.Html)) return@intercept
      if (call.response.headers[HEADER] != null) return@intercept
      val extras = formActions() + call.attributes.getOrNull(EXTRA_FORM_ACTIONS).orEmpty()
      val path = call.request.path()
      val fetchDest = call.request.headers[FETCH_DEST_HEADER]
      if (variesByFetchDest(path)) call.response.headers.append(HttpHeaders.Vary, FETCH_DEST_HEADER)
      val serverOwned = call.attributes.contains(SERVER_OWNED)
      call.response.headers.append(HEADER, forPath(path, extras, fetchDest, serverOwned))
    }
  }
}
