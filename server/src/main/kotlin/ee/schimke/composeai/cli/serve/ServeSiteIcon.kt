package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * The site icon in the three forms the web asks for. Unfurlers (Slack, iMessage, Discord, Google)
 * show it beside link cards, found via the page's icon links or a `/favicon.ico` probe.
 * * [svg]: a vector for browser tabs; no unfurler accepts it.
 * * [appleTouchIcon]: a 180×180 PNG, which most chat clients and link fetchers read first.
 * * [ico]: a 32×32 PNG in an ICO container at `/favicon.ico`, for fetchers that only probe that
 *   path. All drawn from [ServeBrand] so the mark matches the header and unfurl card; rasters baked
 *   once per process and served with a content ETag.
 */
internal object ServeSiteIcon {

  /** Well-known path for [svg]. */
  const val SVG_PATH = "/favicon.svg"

  /** Well-known path for [ico] — the one naive fetchers probe without reading any markup. */
  const val ICO_PATH = "/favicon.ico"

  /** Well-known path for [appleTouchIcon]. */
  const val APPLE_TOUCH_PATH = "/apple-touch-icon.png"

  private const val APPLE_TOUCH_SIZE = 180

  /**
   * The installed app's icons ([appIcon]), at the two sizes Chrome's installability check reads.
   */
  const val APP_ICON_192_PATH = "/icons/app-192.png"

  const val APP_ICON_512_PATH = "/icons/app-512.png"

  /** The same mark inside a launcher's safe zone ([maskableIcon]). */
  const val MASKABLE_ICON_PATH = "/icons/app-maskable-512.png"

  /** The notification badge ([badgeIcon]): the glyph alone, white on transparency. */
  const val BADGE_PATH = "/icons/badge-96.png"

  /** Android's recommended badge size: 24dp at xxxhdpi, and still crisp scaled to 72 at xxhdpi. */
  const val BADGE_SIZE = 96

  /** The manifest's install-dialog [screenshots], one per form factor. */
  const val SCREENSHOT_NARROW_PATH = "/icons/screenshot-narrow.png"

  const val SCREENSHOT_WIDE_PATH = "/icons/screenshot-wide.png"

  /** The web app manifest ([manifest]): what makes the site installable as an app. */
  const val MANIFEST_PATH = "/manifest.webmanifest"

  /** ICO carries a single 32×32 entry: the size every browser picks for a tab anyway. */
  private const val ICO_SIZE = 32

  /** One baked icon: bytes, content type, and the ETag that validates them. */
  data class Icon(val bytes: ByteArray, val contentType: String, val etag: String) {
    // See [ServeHeroImages.Hero]: identity equality, for the same reason.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)
  }

  /**
   * The mark as SVG, generated from [ServeBrand]'s colours so it can't drift from the palette. The
   * diamond is a path, not `◇`, which depends on font coverage.
   */
  val svg: Icon by lazy {
    val text =
      """
      <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="32" height="32">
        <circle cx="16" cy="16" r="16" fill="${hex(ServeBrand.MARK_BG)}"/>
        <path d="M16 7 L25 16 L16 25 L7 16 Z" fill="none" stroke="${hex(ServeBrand.MARK_FG)}"
              stroke-width="2.4" stroke-linejoin="round"/>
      </svg>
      """
        .trimIndent()
    val bytes = text.toByteArray()
    Icon(bytes, "image/svg+xml", etagOf(bytes))
  }

  /** The 180×180 PNG most chat clients and link fetchers read first. */
  val appleTouchIcon: Icon by lazy { pngIcon(APPLE_TOUCH_SIZE) }

  /** The 32×32 raster, as a PNG — the payload [ico] wraps, and useful on its own for tests. */
  private val png32: Icon by lazy { pngIcon(ICO_SIZE) }

  /**
   * The `/favicon.ico` ICO wrapping [png32]'s PNG: PNG-in-ICO is smaller and simpler than
   * BMP-in-ICO and universally supported. The container is a 6-byte header plus one 16-byte entry.
   */
  val ico: Icon by lazy {
    val payload = png32.bytes
    val out = ByteArrayOutputStream()
    // ICONDIR: reserved, type (1 = icon), image count.
    out.writeLe16(0)
    out.writeLe16(1)
    out.writeLe16(1)
    // ICONDIRENTRY: width, height (a byte each — 0 would mean 256), palette size, reserved,
    // colour planes, bits per pixel, payload length, payload offset.
    out.write(ICO_SIZE)
    out.write(ICO_SIZE)
    out.write(0)
    out.write(0)
    out.writeLe16(1)
    out.writeLe16(32)
    out.writeLe32(payload.size)
    out.writeLe32(ICO_HEADER_BYTES)
    out.write(payload)
    val bytes = out.toByteArray()
    Icon(bytes, "image/vnd.microsoft.icon", etagOf(bytes))
  }

  /** 192×192, the smaller of the two sizes an installable app has to declare. */
  val appIcon192: Icon by lazy { pngIcon(192) }

  /** 512×512, the splash-screen and store size. */
  val appIcon512: Icon by lazy { pngIcon(512) }

  /**
   * 512×512 with the mark inside the central 80% on a full-bleed background, the safe zone launcher
   * masks won't crop.
   */
  val maskableIcon: Icon by lazy {
    val size = 512
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      ServeBrand.quality(g)
      g.color = ServeBrand.MARK_BG
      g.fillRect(0, 0, size, size)
      val inset = size * 0.1
      ServeBrand.drawMark(g, inset, inset, size - inset * 2)
    } finally {
      g.dispose()
    }
    val bytes = ServeBrand.encodePng(image) ?: ByteArray(0)
    Icon(bytes, "image/png", etagOf(bytes))
  }

  /**
   * The notification badge: [ServeBrand.drawMonochromeGlyph], [BADGE_SIZE] square, white on
   * transparent. Android uses the badge as an alpha mask, so a full-colour icon showed as a solid
   * white disc. Also the manifest's `monochrome` icon (same contract).
   */
  val badgeIcon: Icon by lazy {
    val image = BufferedImage(BADGE_SIZE, BADGE_SIZE, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      ServeBrand.quality(g)
      ServeBrand.drawMonochromeGlyph(g, 0.0, 0.0, BADGE_SIZE.toDouble())
    } finally {
      g.dispose()
    }
    val bytes = ServeBrand.encodePng(image) ?: ByteArray(0)
    Icon(bytes, "image/png", etagOf(bytes))
  }

  /** One launcher shortcut — the long-press / right-click menu of the installed app. */
  data class Shortcut(val name: String, val url: String, val description: String)

  /**
   * The web app manifest, enough for Chrome to offer Install: name, start URL, standalone display,
   * 192 and 512 icons, and [shortcuts] where a UI builder exists. `id` is fixed at `/` so the app
   * keeps its identity if [startUrl] moves.
   */
  fun manifest(
    name: String,
    shortName: String,
    startUrl: String,
    shortcuts: List<Shortcut>,
    /**
     * The installed app's identity, resolved against the manifest's origin, so each top-level site
     * installs as its own app.
     */
    id: String = "/",
    scope: String = "/",
    description: String = "Compose previews, catalogs and the UI builder.",
    /** The title bar colour of the installed window; defaults to the brand's tonal container. */
    themeColor: String = hex(ServeBrand.MARK_BG),
  ): Icon {
    fun str(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
    val json =
      kotlinx.serialization.json.buildJsonObject {
        put("id", str(id))
        put("name", str(name))
        put("short_name", str(shortName))
        put("description", str(description))
        put("start_url", str(startUrl))
        put("scope", str(scope))
        put("display", str("standalone"))
        // Tried before `display`. Not `window-controls-overlay` (the sticky header ignores
        // `env(titlebar-area-*)`); `minimal-ui` keeps a back button where standalone isn't
        // available.
        put(
          "display_override",
          kotlinx.serialization.json.buildJsonArray {
            add(str("standalone"))
            add(str("minimal-ui"))
          },
        )
        // A second launch focuses the window already open rather than stacking another — the
        // share target below relies on it to land a shared screenshot in the running app.
        put(
          "launch_handler",
          kotlinx.serialization.json.buildJsonObject {
            put(
              "client_mode",
              kotlinx.serialization.json.buildJsonArray {
                add(str("focus-existing"))
                add(str("auto"))
              },
            )
          },
        )
        put(
          "categories",
          kotlinx.serialization.json.buildJsonArray {
            add(str("developer"))
            add(str("productivity"))
            add(str("design"))
          },
        )
        put("background_color", str(hex(ServeBrand.MARK_BG)))
        put("theme_color", str(themeColor))
        put(
          "icons",
          kotlinx.serialization.json.buildJsonArray {
            fun icon(src: String, sizes: String, type: String, purpose: String) =
              add(
                kotlinx.serialization.json.buildJsonObject {
                  put("src", str(src))
                  put("sizes", str(sizes))
                  put("type", str(type))
                  put("purpose", str(purpose))
                }
              )
            icon(APP_ICON_192_PATH, "192x192", "image/png", "any")
            icon(APP_ICON_512_PATH, "512x512", "image/png", "any")
            icon(MASKABLE_ICON_PATH, "512x512", "image/png", "maskable")
            icon(SVG_PATH, "any", "image/svg+xml", "any")
            // Alpha only, per the spec's `monochrome` purpose — which is exactly what the badge is.
            icon(BADGE_PATH, "${BADGE_SIZE}x$BADGE_SIZE", "image/png", "monochrome")
          },
        )
        // What the install dialog shows beside the name. Committed captures of the catalog page,
        // one per form factor, so the richer install UI has something true to show at either size.
        put(
          "screenshots",
          kotlinx.serialization.json.buildJsonArray {
            screenshots.forEach { shot ->
              add(
                kotlinx.serialization.json.buildJsonObject {
                  put("src", str(shot.path))
                  put("sizes", str("${shot.width}x${shot.height}"))
                  put("type", str("image/png"))
                  put("form_factor", str(shot.formFactor))
                  put("label", str(shot.label))
                }
              )
            }
          },
        )
        // Installed, the app appears in the OS share sheet: shared screenshots land in the
        // bug-report flow and shared links open if they're this server's. See [ServeShareTarget].
        put(
          "share_target",
          kotlinx.serialization.json.buildJsonObject {
            put("action", str(ServeShareTarget.ACTION_PATH))
            put("method", str("POST"))
            put("enctype", str("multipart/form-data"))
            put(
              "params",
              kotlinx.serialization.json.buildJsonObject {
                put("title", str(ServeShareTarget.TITLE_FIELD))
                put("text", str(ServeShareTarget.TEXT_FIELD))
                put("url", str(ServeShareTarget.URL_FIELD))
                put(
                  "files",
                  kotlinx.serialization.json.buildJsonArray {
                    add(
                      kotlinx.serialization.json.buildJsonObject {
                        put("name", str(ServeShareTarget.FILE_FIELD))
                        put(
                          "accept",
                          kotlinx.serialization.json.buildJsonArray {
                            ServeShareTarget.ACCEPTED_IMAGE_TYPES.forEach { add(str(it)) }
                          },
                        )
                      }
                    )
                  },
                )
              },
            )
          },
        )
        if (shortcuts.isNotEmpty()) {
          put(
            "shortcuts",
            kotlinx.serialization.json.buildJsonArray {
              shortcuts.forEach { shortcut ->
                add(
                  kotlinx.serialization.json.buildJsonObject {
                    put("name", str(shortcut.name))
                    put("url", str(shortcut.url))
                    put("description", str(shortcut.description))
                    put(
                      "icons",
                      kotlinx.serialization.json.buildJsonArray {
                        add(
                          kotlinx.serialization.json.buildJsonObject {
                            put("src", str(APP_ICON_192_PATH))
                            put("sizes", str("192x192"))
                          }
                        )
                      },
                    )
                  }
                )
              }
            },
          )
        }
      }
    val bytes = json.toString().toByteArray()
    return Icon(bytes, "application/manifest+json", etagOf(bytes))
  }

  /**
   * The `<head>` icon links every page carries. Fixed well-known paths rather than content-hashed,
   * since icon fetchers guess these and a day's cache costs little.
   */
  fun linkTags(themeCss: String = "", appTitle: String = "Compose Preview"): String {
    val (light, dark) = themeColors(themeCss)
    val title = WebEscaping.htmlEscape(appTitle.ifBlank { "Compose Preview" })
    return """
    <link rel="icon" href="$SVG_PATH" type="image/svg+xml">
    <link rel="icon" href="$ICO_PATH" sizes="32x32">
    <link rel="apple-touch-icon" href="$APPLE_TOUCH_PATH">
    <link rel="manifest" href="$MANIFEST_PATH">
    <meta name="theme-color" media="(prefers-color-scheme: light)" content="$light">
    <meta name="theme-color" media="(prefers-color-scheme: dark)" content="$dark">
    <meta name="mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-title" content="$title">
    <meta name="apple-mobile-web-app-status-bar-style" content="default">
    """
      .trimIndent()
  }

  /**
   * Browser chrome colour per scheme: the page surface the sticky header paints (`--cp-bg`), so
   * status bar and header read as one. Follows a catalog palette ([ServeThemeCss]), else the M3
   * baseline surface.
   */
  fun themeColors(themeCss: String = ""): Pair<String, String> {
    val match = SURFACE_PAIR.find(themeCss)
    return if (match != null) match.groupValues[1].lowercase() to match.groupValues[2].lowercase()
    else BASELINE_SURFACE_LIGHT to BASELINE_SURFACE_DARK
  }

  /** `serve.css`'s baseline `--md-sys-color-surface`, light then dark. */
  const val BASELINE_SURFACE_LIGHT = "#fef7ff"
  const val BASELINE_SURFACE_DARK = "#141218"

  private val SURFACE_PAIR =
    Regex(
      "--md-sys-color-surface:\\s*light-dark\\(\\s*(#[0-9a-fA-F]{6})\\s*,\\s*(#[0-9a-fA-F]{6})\\s*\\)"
    )

  /** One manifest screenshot, served from the classpath at [path]. */
  data class Screenshot(
    val path: String,
    val resource: String,
    val width: Int,
    val height: Int,
    val formFactor: String,
    val label: String,
  )

  /** Manifest screenshots: small committed captures (phone and desktop) for the install dialog. */
  val screenshots: List<Screenshot> =
    listOf(
      Screenshot(
        SCREENSHOT_NARROW_PATH,
        "screenshot-narrow.png",
        412,
        800,
        "narrow",
        "A catalog of Compose previews on a phone",
      ),
      Screenshot(
        SCREENSHOT_WIDE_PATH,
        "screenshot-wide.png",
        1280,
        800,
        "wide",
        "A catalog of Compose previews on a desktop",
      ),
    )

  /** A [screenshots] entry's bytes, read once from the classpath; null when not packaged. */
  fun screenshot(path: String): Icon? = screenshotIcons[path]

  private val screenshotIcons: Map<String, Icon> by lazy {
    screenshots
      .mapNotNull { shot ->
        val bytes =
          ServeSiteIcon::class.java.getResourceAsStream("pwa/${shot.resource}")?.use {
            it.readBytes()
          } ?: return@mapNotNull null
        shot.path to Icon(bytes, "image/png", etagOf(bytes))
      }
      .toMap()
  }

  private fun pngIcon(size: Int): Icon {
    val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      ServeBrand.quality(g)
      ServeBrand.drawMark(g, 0.0, 0.0, size.toDouble())
    } finally {
      g.dispose()
    }
    // An icon the encoder refused would be a broken `<link>` on every page, so fall back to no
    // bytes rather than throwing during page render; the routes 404 and the tab keeps its globe.
    val bytes = ServeBrand.encodePng(image) ?: ByteArray(0)
    return Icon(bytes, "image/png", etagOf(bytes))
  }

  internal fun hex(color: java.awt.Color): String =
    "#%02x%02x%02x".format(color.red, color.green, color.blue)

  private fun etagOf(bytes: ByteArray): String =
    "\"" +
      MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
        .take(16) +
      "\""

  /** ICONDIR (6) + one ICONDIRENTRY (16): where the wrapped PNG starts. */
  private const val ICO_HEADER_BYTES = 22

  private fun ByteArrayOutputStream.writeLe16(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
  }

  private fun ByteArrayOutputStream.writeLe32(value: Int) {
    writeLe16(value and 0xffff)
    writeLe16((value ushr 16) and 0xffff)
  }
}
