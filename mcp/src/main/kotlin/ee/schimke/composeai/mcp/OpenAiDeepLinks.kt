package ee.schimke.composeai.mcp

import java.io.ByteArrayOutputStream

/**
 * OpenAI MCP App deep links (issue #1241; spec "Global Entrypoint → Deep Links"): links that open a
 * global entrypoint tool's app at one app-relative page, for a PR comment, the preview-diff bot or
 * `compose-preview show --link`.
 *
 * ```
 * codex://plugins/{pluginId}@{marketplace}/app/{toolName}?path={encodedAppRelativePath}   desktop
 * chatgpt://plugins/{pluginId}@{marketplace}/app/{toolName}?path=…                        mobile
 * https://chatgpt.com/plugins/{pluginId}/app/{toolName}?path=…                            web
 * ```
 *
 * The plugin id and the marketplace depend on how the compose-preview plugin is published, so they
 * are always supplied by the caller ([OpenAiPlugin]) and never baked in here.
 */
object OpenAiDeepLinks {
  /** Where the link is opened; decides the scheme and whether the marketplace is part of it. */
  enum class Surface {
    /** ChatGPT / Codex desktop: `codex://`. */
    DESKTOP,
    /** ChatGPT mobile: `chatgpt://`. Not supported on Android at DevDay launch. */
    MOBILE,
    /** The ChatGPT web app: `https://chatgpt.com/plugins/…`, which carries no marketplace. */
    WEB,
  }

  /**
   * A deep link to [toolName]'s app at [appPath] (the app-relative URL, query included). [appPath]
   * must begin with `/` and must not contain a fragment; it is percent-encoded whole as the `path`
   * query value, and `/` — the spec's default — is left out.
   */
  fun link(
    plugin: OpenAiPlugin,
    toolName: String,
    appPath: String = "/",
    surface: Surface = Surface.DESKTOP,
  ): String {
    require(toolName.isNotBlank()) { "deep link tool name must not be blank" }
    requireAppPath(appPath)
    val query = if (appPath == "/") "" else "?path=${encode(appPath)}"
    val tool = encode(toolName)
    val id = encode(plugin.pluginId)
    return when (surface) {
      Surface.WEB -> "https://chatgpt.com/plugins/$id/app/$tool$query"
      Surface.DESKTOP,
      Surface.MOBILE -> {
        val scheme = if (surface == Surface.DESKTOP) "codex" else "chatgpt"
        val at = plugin.marketplace?.let { "@${encode(it)}" }.orEmpty()
        "$scheme://plugins/$id$at/app/$tool$query"
      }
    }
  }

  /** [link] for a [LibraryRoute]. */
  fun link(
    plugin: OpenAiPlugin,
    toolName: String,
    route: LibraryRoute,
    surface: Surface = Surface.DESKTOP,
  ): String = link(plugin, toolName, route.path, surface)

  /** The spec's rules for an app-relative URL: begins with `/`, no fragment. */
  fun requireAppPath(appPath: String) {
    require(appPath.startsWith("/")) { "app-relative deep-link path must begin with '/': $appPath" }
    require('#' !in appPath) { "app-relative deep-link path must not contain a fragment: $appPath" }
  }

  /**
   * RFC 3986 percent-encoding of every byte outside the unreserved set (`A-Z a-z 0-9 - . _ ~`), so
   * `/`, `?`, `&`, `=`, `:` and `@` inside a value can never be read as structure. Unlike
   * [java.net.URLEncoder] a space is `%20`, not `+`.
   */
  fun encode(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
      val c = byte.toInt() and 0xff
      if (
        c in 'A'.code..'Z'.code ||
          c in 'a'.code..'z'.code ||
          c in '0'.code..'9'.code ||
          c == '-'.code ||
          c == '.'.code ||
          c == '_'.code ||
          c == '~'.code
      ) {
        append(c.toChar())
      } else {
        append('%')
        append(HEX[c shr 4])
        append(HEX[c and 0xf])
      }
    }
  }

  /** Inverse of [encode]; `+` stays a literal plus. Throws on a malformed escape. */
  fun decode(value: String): String {
    if ('%' !in value) return value
    val out = ByteArrayOutputStream(value.length)
    var i = 0
    while (i < value.length) {
      val c = value[i]
      if (c == '%') {
        require(i + 2 < value.length) { "truncated percent-escape in '$value'" }
        val hi = Character.digit(value[i + 1], 16)
        val lo = Character.digit(value[i + 2], 16)
        require(hi >= 0 && lo >= 0) { "malformed percent-escape in '$value'" }
        out.write((hi shl 4) or lo)
        i += 3
      } else {
        val codePoint = value.codePointAt(i)
        out.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
        i += Character.charCount(codePoint)
      }
    }
    return out.toString(Charsets.UTF_8)
  }

  private const val HEX = "0123456789ABCDEF"
}

/**
 * How the compose-preview plugin is published: its ChatGPT [pluginId] (from
 * `https://chatgpt.com/plugins/{pluginId}`) and, for a plugin installed from a custom
 * `marketplace.json`, that marketplace's `name`. Null [marketplace] is a plugin published directly
 * to ChatGPT, whose links omit `@{marketplace}`.
 */
data class OpenAiPlugin(val pluginId: String, val marketplace: String? = null) {
  init {
    require(pluginId.isNotBlank()) { "pluginId must not be blank" }
    require(marketplace == null || marketplace.isNotBlank()) { "marketplace must not be blank" }
  }

  companion object {
    const val PLUGIN_ID_ENV: String = "COMPOSE_PREVIEW_OPENAI_PLUGIN_ID"
    const val MARKETPLACE_ENV: String = "COMPOSE_PREVIEW_OPENAI_MARKETPLACE"

    /**
     * The plugin named by [PLUGIN_ID_ENV] (and optionally [MARKETPLACE_ENV]); null when unset, so a
     * caller that cannot name the plugin prints no link rather than a guessed one.
     */
    fun fromEnvironment(env: Map<String, String> = System.getenv()): OpenAiPlugin? =
      env[PLUGIN_ID_ENV]
        ?.takeIf { it.isNotBlank() }
        ?.let { OpenAiPlugin(it, env[MARKETPLACE_ENV]?.takeIf { m -> m.isNotBlank() }) }
  }
}

/**
 * The app-relative routes the library app understands, as the host hands them over in
 * `hostContext["openai/deepLink"].url`. `mcp-app/preview-library.html` parses the same shapes.
 */
sealed interface LibraryRoute {
  /** The app-relative URL, beginning with `/`. */
  val path: String

  /** `/`: the project list. */
  data object Home : LibraryRoute {
    override val path: String = "/"
  }

  /** `/project/<id>`: one registered project (local) or catalog (hosted). */
  data class Project(val id: String) : LibraryRoute {
    override val path: String
      get() = "/project/${OpenAiDeepLinks.encode(id)}"
  }

  /** `/preview/<uri-encoded compose-preview URI>`: one preview, rendered on open. */
  data class Preview(val uri: String) : LibraryRoute {
    override val path: String
      get() = "/preview/${OpenAiDeepLinks.encode(uri)}"
  }

  /** `/design/<id>`: one UI Builder design (hosted `ui_builder_open`). */
  data class Design(val id: String) : LibraryRoute {
    override val path: String
      get() = "/design/${OpenAiDeepLinks.encode(id)}"
  }

  companion object {
    /**
     * Parses an app-relative URL; null when it is not one of the routes above or is malformed. A
     * query string is ignored, and a trailing `/` is tolerated.
     */
    fun parse(url: String): LibraryRoute? {
      if (!url.startsWith("/") || '#' in url) return null
      val path = url.substringBefore('?').trimEnd('/')
      if (path.isEmpty()) return Home
      val segments = path.removePrefix("/").split('/')
      if (segments.size != 2 || segments[1].isEmpty()) return null
      val value = runCatching { OpenAiDeepLinks.decode(segments[1]) }.getOrNull() ?: return null
      return when (segments[0]) {
        "project" -> Project(value)
        "preview" -> Preview(value)
        "design" -> Design(value)
        else -> null
      }
    }
  }
}
