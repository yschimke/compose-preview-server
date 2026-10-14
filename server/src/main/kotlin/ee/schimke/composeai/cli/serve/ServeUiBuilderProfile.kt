package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import io.ktor.server.application.ApplicationCall
import java.net.URI

/**
 * A white-labelled UI builder on one more hostname: `--ui-builder-profile-host` and its siblings
 * (settings `uiBuilder.profile.*`). Off unless a host is set.
 *
 * The Home Assistant card designer is the case it was made for: `ha.coo.ee/` is that project's own
 * server, and its edge sends only the builder's paths (`/ui-builder`, `/api/ui-builder`, `/auth`,
 * `/mcp`, the assets, …) here, unchanged, with the catalog's own `/<system>/` pages and the routes
 * they draw from. A request whose host ([requestHost]) is [host] then sees a builder that looks like
 * that product rather than like compose-preview:
 * - every builder page, and the editor shell, carries [name] in its `<title>`, headings and web app
 *   manifest; the shell also carries the brand `<meta>`s ([shellMetaTags]) a new enough editor
 *   reads;
 * - nothing links to the catalogs, the front door or other design systems — what [ServeSites]
 *   already withholds on a top-level catalog site;
 * - only [catalogs] are offered and listed, and creating, copying, forking or importing into any
 *   other catalog is refused on this host;
 * - the builder keeps its `/ui-builder/…` paths. It is not the rooted builder host
 *   (`--ui-builder-host-root`), and may not be the same host as `--ui-builder-host`.
 *
 * A view, like a top-level site: it grants nothing. Sign-in, authorization and visibility are the
 * box's own, unchanged. What it adds to sign-in is only a return address: [host] joins the hosts a
 * GitHub sign-in may come back to ([ServeGithubAuth.handleStart]), which still requires it to be
 * inside the auth cookie's domain.
 */
data class ServeUiBuilderProfile(
  /** Normalised as [ServeSites.normalizeHost] normalises a `Host` header. */
  val host: String,
  /** What the product is called, e.g. `Home Assistant card designer`. */
  val name: String,
  /** The UI-builder catalogs offered on [host], in the order given. */
  val catalogs: List<String>,
  /** A `#rrggbb` colour for the product's chrome, or null for the default palette. */
  val accent: String? = null,
  /** Where the logo and "back" links go: an HTTPS URL, or null for the builder's own home. */
  val homeUrl: String? = null,
) {
  /** Whether [call] arrived for this profile's host. */
  fun matches(call: ApplicationCall, trustForwardedFor: Boolean): Boolean =
    requestHost(call, trustForwardedFor) == host

  fun allows(catalog: String): Boolean = catalog in catalogs

  /** The brand `<meta>`s for the editor shell, beside `ui-builder-base-path`. */
  fun shellMetaTags(offeredCatalogs: List<String>): String = buildString {
    fun meta(name: String, content: String) {
      append("<meta name=\"").append(name).append("\" content=\"")
      append(WebEscaping.htmlEscape(content)).append("\">")
    }
    meta(BRAND_NAME_META, name)
    accent?.let { meta(BRAND_ACCENT_META, it) }
    meta(BRAND_HOME_META, homeUrl ?: "/ui-builder/")
    meta(CATALOGS_META, offeredCatalogs.joinToString(","))
  }

  /** The chrome [ServeWeb] draws a builder page in on [host]. */
  fun pageBrand(): ServeWeb.PageBrand =
    ServeWeb.PageBrand(
      name = name,
      homeHref = homeUrl ?: "/ui-builder/",
      accent = accent,
      manifestPath = MANIFEST_PATH,
    )

  companion object {
    const val BRAND_NAME_META = "ui-builder-brand-name"
    const val BRAND_ACCENT_META = "ui-builder-brand-accent"
    const val BRAND_HOME_META = "ui-builder-brand-home"
    const val CATALOGS_META = "ui-builder-catalogs"

    /**
     * The profile's own web app manifest. Under `/ui-builder/` because that is all of the profile's
     * host the edge sends here: `/manifest.webmanifest` there is the other product's.
     */
    const val MANIFEST_PATH = "/ui-builder/manifest.webmanifest"

    val ACCENT = Regex("#[0-9a-fA-F]{6}")

    /** A catalog id, as `catalogs.json` spells one. */
    val CATALOG = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    const val NAME_MAX = 80

    /**
     * The profile the flags describe, or null when [host] is unset. Throws
     * [IllegalArgumentException] with a message naming the flag for anything else that is wrong,
     * like the other `serve` flags.
     */
    fun of(
      host: String?,
      name: String?,
      catalogs: List<String>,
      accent: String?,
      homeUrl: String?,
      uiBuilderHost: String? = null,
    ): ServeUiBuilderProfile? {
      if (host.isNullOrBlank()) {
        require(name.isNullOrBlank() && catalogs.isEmpty() && accent == null && homeUrl == null) {
          "--ui-builder-profile-name, -catalogs, -accent and -home-url need " +
            "--ui-builder-profile-host: a profile is a host's"
        }
        return null
      }
      val normalized =
        requireNotNull(ServeSites.normalizeHost(host)) {
          "--ui-builder-profile-host must be a hostname, got `$host`"
        }
      require(normalized != uiBuilderHost) {
        "--ui-builder-profile-host must differ from --ui-builder-host ($uiBuilderHost): the " +
          "builder host is compose-preview's own"
      }
      val label = name?.trim().orEmpty()
      require(label.isNotEmpty() && label.length <= NAME_MAX && label.none(Char::isISOControl)) {
        "--ui-builder-profile-name must be 1-$NAME_MAX characters with no control characters"
      }
      val offered = catalogs.map(String::trim).filter(String::isNotEmpty).distinct()
      require(offered.isNotEmpty()) {
        "--ui-builder-profile-catalogs must name at least one UI-builder catalog"
      }
      offered
        .firstOrNull { !CATALOG.matches(it) }
        ?.let { throw IllegalArgumentException("`$it` is not a catalog id") }
      require(accent == null || ACCENT.matches(accent)) {
        "--ui-builder-profile-accent must be a #rrggbb colour, got `$accent`"
      }
      require(homeUrl == null || isHttpsUrl(homeUrl)) {
        "--ui-builder-profile-home-url must be an HTTPS URL without credentials, got `$homeUrl`"
      }
      return ServeUiBuilderProfile(normalized, label, offered, accent?.lowercase(), homeUrl)
    }

    private fun isHttpsUrl(raw: String): Boolean {
      val uri = runCatching { URI(raw) }.getOrNull() ?: return false
      return uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }
  }
}
