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
 * The `Content-Security-Policy` every HTML response from this server carries, written once. The
 * edge proxy sets none (`deploy/image/Caddyfile`) because the policy describes the markup.
 * [install] applies it to `text/html` only.
 *
 * What each directive allows:
 * * Scripts: this origin only (serve-web bundles, vendored players, Wasm `.mjs` glue).
 *   `'unsafe-inline'` remains for inline bootstraps, import maps and handlers; there is no
 *   `'unsafe-eval'`, and nothing served needs it.
 * * Wasm: `'wasm-unsafe-eval'` only on [WASM_APP_PREFIXES]; it permits WebAssembly compilation, not
 *   JS `eval`.
 * * Styles: page stylesheets, inline styles, and Google Fonts for Remote Compose documents naming a
 *   `google:` family (faces from `fonts.gstatic.com`).
 * * Images: this origin, `data:`, `blob:`, and `raw.githubusercontent.com` (published history
 *   renders).
 * * Fetches and sockets: this origin (same-host `ws:`/`wss:`), `data:`/`blob:`,
 *   `raw.githubusercontent.com` (history manifest), and `openrouter.ai` for the UI-builder editor
 *   shell only, never framed catalog runtimes.
 * * Frames: only this origin's own apps (`/wasm/…`, `/rc-player-wasm/…`, `/ui-builder/runtime/…`).
 * * Forms: this origin plus GitHub's new-issue page; forms that redirect elsewhere (sign-in, OAuth
 *   approval) need [allowFormAction], since `form-action` applies across redirects.
 * * Framing: `frame-ancestors 'self'` (matching the proxy's `X-Frame-Options`), except [isFramable]
 *   pages.
 * * Sandbox: app shells running code this server didn't write (catalog Wasm apps, the RC Wasm
 *   player, renderer runtimes) get `sandbox allow-scripts` without `allow-same-origin` wherever
 *   they aren't deliberately framed, so a top-level link to `/wasm/<system>/` can't run catalog
 *   code with this origin beside the editor's `localStorage` (which holds a person's OpenRouter
 *   key). See [sandboxFor].
 */
internal object ServePagePolicy {
  const val HEADER: String = "Content-Security-Policy"

  private const val RAW_GITHUB = "https://raw.githubusercontent.com"
  private const val OPENROUTER = "https://openrouter.ai"
  private const val GITHUB = "https://github.com"
  private const val GOOGLE_FONTS_CSS = "https://fonts.googleapis.com"
  private const val GOOGLE_FONTS_FILES = "https://fonts.gstatic.com"

  /**
   * Kotlin/Wasm app shell paths: per-catalog apps (public and private), the RC Wasm player, and the
   * UI builder editor and its runtimes.
   */
  private val WASM_APP_PREFIXES =
    listOf("/wasm/", "/wasm-private/", "/rc-player-wasm/", "/ui-builder/")

  /** The directive an app shell runs under when it must not have this origin. */
  const val SANDBOX: String = "sandbox allow-scripts"

  /** The fetch-metadata request header naming what a request is for (a document, a frame, …). */
  const val FETCH_DEST_HEADER: String = "Sec-Fetch-Dest"

  /**
   * Renderer runtimes are always sandboxed: the editor only frames them sandboxed and their assets
   * are already CORS-open, so an opaque origin is what they always had.
   */
  private val ALWAYS_SANDBOXED_PREFIXES = listOf("/ui-builder/runtime/")

  /**
   * Shells sandboxed when not loaded as a frame; when framed, the embedding `<iframe sandbox>`
   * decides. Two embeddings keep the real origin on purpose (a Trusted catalog's app needs the
   * Cache and history APIs; the RC player's messages are only accepted from this origin), which a
   * response-level `sandbox` would override, so it applies to top-level loads only.
   */
  private val CATALOG_APP_PREFIXES = listOf("/wasm/", "/wasm-private/")
  private val RC_PLAYER_PREFIX = "/rc-player-wasm/"

  /** `Sec-Fetch-Dest` values of a request loading a page into a frame. */
  private val FRAME_DESTINATIONS = setOf("iframe", "frame")

  /**
   * Whether [path]'s policy depends on [FETCH_DEST_HEADER], requiring `Vary: Sec-Fetch-Dest` so
   * caches never serve a frame's copy to a top-level load.
   */
  fun variesByFetchDest(path: String): Boolean =
    CATALOG_APP_PREFIXES.any { path.startsWith(it) } || path.startsWith(RC_PLAYER_PREFIX)

  /**
   * Whether the HTML at [path] with `Sec-Fetch-Dest: [fetchDest]` runs sandboxed. Framing is
   * detected only by the browser-set header, which page script can't forge. Browsers omit it on
   * plain-HTTP non-localhost origins (`serve --lan`): a catalog app then fails closed (sandboxed),
   * while the server's own RC player is sandboxed only when the browser says it isn't a frame.
   *
   * [serverOwned] exempts the server's own packaged Wasm frontend served at a catalog-app path
   * ([markServerOwned]), which runs no catalog code.
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
   * Mark this response as a shell from the server's own distribution, so it keeps this origin
   * top-level ([sandboxFor]); only the route that chose the directory knows.
   */
  fun markServerOwned(call: ApplicationCall) {
    call.attributes.put(SERVER_OWNED, Unit)
  }

  private val POLICY_PATH = AttributeKey<String>("ServePagePolicy.policyPath")

  /**
   * Decide this response's policy as if it had been requested at [canonicalPath]. The rooted UI
   * builder host serves the editor's pages at `/` and `/<design>` (`ServeUiBuilderHostRoot.kt`),
   * where the path no longer starts with `/ui-builder/`; every path rule here —
   * `'wasm-unsafe-eval'`, the editor's OpenRouter `connect-src`, framing and sandboxing — must
   * still see the page it is. Without it the rooted editor ran under a plain page's `script-src`
   * and could not compile its Wasm (ui.coo.ee, 3.120.0).
   */
  fun servesAs(call: ApplicationCall, canonicalPath: String) {
    call.attributes.put(POLICY_PATH, canonicalPath)
  }

  private val PHASE = PipelinePhase("PageContentPolicy")

  /**
   * Pages another frame may embed, mirroring the proxy's exemption: Storybook `iframe.html` renders
   * and the Wasm apps the viewer and UI builder mount in sandboxed frames.
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
   * Where a page may fetch from. The UI-builder editor shell (never its framed runtimes) also
   * reaches OpenRouter for the guidelines check and PKCE key exchange.
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
   * The policy for an HTML response at [path], with [formActions] added, for a request whose
   * `Sec-Fetch-Dest` was [fetchDest], for a shell that is or isn't [serverOwned].
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
   * The `form-action` source admitting a form that redirects to [uri]: its origin, or its bare
   * scheme for a private-use scheme (`vscode:`, `cursor:`); null otherwise.
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
   * Let this page submit a form ending at [uri] (an OAuth client's redirect URI); no-op without a
   * [formActionSource].
   */
  fun allowFormAction(call: ApplicationCall, uri: String) {
    val source = formActionSource(uri) ?: return
    call.attributes.computeIfAbsent(EXTRA_FORM_ACTIONS) { linkedSetOf() }.add(source)
  }

  /**
   * Set [HEADER] on every `text/html` response lacking one. [formActions] is read per response
   * since sign-in destinations can change at runtime. Runs just before `ContentEncoding`, where the
   * content still has its declared type.
   */
  fun install(application: Application, formActions: () -> Collection<String>) {
    application.sendPipeline.insertPhaseBefore(ApplicationSendPipeline.ContentEncoding, PHASE)
    application.sendPipeline.intercept(PHASE) { message ->
      val content = message as? OutgoingContent ?: return@intercept
      val type = content.contentType ?: return@intercept
      if (!type.match(ContentType.Text.Html)) return@intercept
      if (call.response.headers[HEADER] != null) return@intercept
      val extras = formActions() + call.attributes.getOrNull(EXTRA_FORM_ACTIONS).orEmpty()
      val path = call.attributes.getOrNull(POLICY_PATH) ?: call.request.path()
      val fetchDest = call.request.headers[FETCH_DEST_HEADER]
      if (variesByFetchDest(path)) call.response.headers.append(HttpHeaders.Vary, FETCH_DEST_HEADER)
      val serverOwned = call.attributes.contains(SERVER_OWNED)
      call.response.headers.append(HEADER, forPath(path, extras, fetchDest, serverOwned))
    }
  }
}
