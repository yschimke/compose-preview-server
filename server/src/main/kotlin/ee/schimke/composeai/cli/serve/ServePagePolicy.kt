package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
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
 *   published history manifest.
 * * **Frames**: only this origin's own apps (`/wasm/…`, `/rc-player-wasm/…`,
 *   `/ui-builder/runtime/…`).
 * * **Forms** post to this origin, and the issue-report forms open GitHub's new-issue page. A form
 *   whose answer redirects elsewhere — sign-in continuing to GitHub, an OAuth approval returning to
 *   its client — needs that destination allowed too, since browsers apply `form-action` across the
 *   redirect: see [allowFormAction].
 * * **Framing**: `frame-ancestors 'self'`, matching the proxy's `X-Frame-Options SAMEORIGIN`,
 *   except on the pages that are framed by design ([isFramable]), which carry no `frame-ancestors`
 *   at all — the same exemption the proxy makes.
 */
internal object ServePagePolicy {
  const val HEADER: String = "Content-Security-Policy"

  private const val RAW_GITHUB = "https://raw.githubusercontent.com"
  private const val GITHUB = "https://github.com"
  private const val GOOGLE_FONTS_CSS = "https://fonts.googleapis.com"
  private const val GOOGLE_FONTS_FILES = "https://fonts.gstatic.com"

  /**
   * Paths whose HTML is the shell of a Kotlin/Wasm app: the per-catalog apps (public and private),
   * the Remote Compose Wasm player, and the UI builder editor and its pinned renderer runtimes.
   */
  private val WASM_APP_PREFIXES =
    listOf("/wasm/", "/wasm-private/", "/rc-player-wasm/", "/ui-builder/")

  private val EXTRA_FORM_ACTIONS = AttributeKey<MutableSet<String>>("ServePagePolicy.formAction")

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
    WASM_APP_PREFIXES.any { path.startsWith(it) } || path == "/ui-builder"

  /** The policy for an HTML response at [path], with [formActions] added to `form-action`. */
  fun forPath(path: String, formActions: Collection<String> = emptyList()): String {
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
      add("connect-src 'self' data: blob: $RAW_GITHUB")
      add("worker-src 'self'")
      add("frame-src 'self'")
      add("object-src 'none'")
      add("base-uri 'self'")
      add("form-action ${formAction.joinToString(" ")}")
      if (!isFramable(path)) add("frame-ancestors 'self'")
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
      call.response.headers.append(HEADER, forPath(call.request.path(), extras))
    }
  }
}
