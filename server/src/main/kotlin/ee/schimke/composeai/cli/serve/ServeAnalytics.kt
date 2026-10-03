package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.net.URI

/** Deployment opt-in. Installed/local servers never contact an analytics service by default. */
internal object ServeAnalytics {
  fun scriptTag(env: Map<String, String> = System.getenv()): String {
    if (env["SERVE_UMAMI_ENABLED"] !in setOf("1", "true")) return ""
    val endpoint = env["SERVE_UMAMI_URL"]?.trimEnd('/') ?: return ""
    val website = env["SERVE_UMAMI_WEBSITE_ID"] ?: return ""
    val uri = runCatching { URI(endpoint) }.getOrNull() ?: return ""
    if (
      uri.scheme != "https" ||
        uri.host == null ||
        uri.userInfo != null ||
        uri.query != null ||
        uri.fragment != null ||
        !Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}").matches(website)
    )
      return ""
    val url = WebEscaping.htmlEscape(endpoint)
    return """<script defer src="${ServeWebAssets.href("analytics.js")}" data-umami-url="$url" data-website-id="$website"></script>"""
  }
}
