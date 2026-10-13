package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.net.URI

/** Deployment opt-in. Installed/local servers never contact an analytics service by default. */
internal object ServeAnalytics {
  fun scriptTag(env: Map<String, String> = System.getenv()): String {
    val (endpoint, website) = configured(env) ?: return ""
    val url = WebEscaping.htmlEscape(endpoint)
    return """<script defer src="${ServeWebAssets.href("analytics.js")}" data-umami-url="$url" data-website-id="$website"></script>"""
  }

  /**
   * The Umami endpoint's origin when [scriptTag] would emit a tag, for the page policy's
   * `script-src` and `connect-src`: a host other than the endpoint's (ha.coo.ee reporting to
   * preview.coo.ee) otherwise refuses both the tracker script and its beacon.
   */
  fun origin(env: Map<String, String> = System.getenv()): String? {
    val uri = URI(configured(env)?.first ?: return null)
    val port = uri.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
    return "https://${uri.host.lowercase()}$port"
  }

  /** The endpoint and website id, or null unless analytics is enabled and both are valid. */
  private fun configured(env: Map<String, String>): Pair<String, String>? {
    if (env["SERVE_UMAMI_ENABLED"] !in setOf("1", "true")) return null
    val endpoint = env["SERVE_UMAMI_URL"]?.trimEnd('/') ?: return null
    val website = env["SERVE_UMAMI_WEBSITE_ID"] ?: return null
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return null
    if (
      uri.scheme != "https" ||
        uri.host == null ||
        uri.userInfo != null ||
        uri.query != null ||
        uri.fragment != null ||
        !Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(website)
    )
      return null
    return endpoint to website
  }
}
