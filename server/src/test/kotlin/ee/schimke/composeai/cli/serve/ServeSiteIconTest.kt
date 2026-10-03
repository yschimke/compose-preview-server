package ee.schimke.composeai.cli.serve

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The site icon ([ServeSiteIcon]).
 *
 * The server shipped none at all before this — no icon links on any page and a 404 at
 * `/favicon.ico` — which is why an unfurled link showed a generic globe beside its card. Each form
 * exists for a consumer that accepts no other, so this pins that all three are actually produced
 * and are the format they claim: an SVG for tabs, a PNG for the chat clients that read
 * `apple-touch-icon`, and a real ICO container at the path naive fetchers probe.
 */
class ServeSiteIconTest {

  @Test
  fun `the svg is an svg, drawn in the brand palette`() {
    val icon = ServeSiteIcon.svg
    val text = icon.bytes.decodeToString()

    assertEquals("image/svg+xml", icon.contentType)
    assertTrue(text.startsWith("<svg"), text)
    assertTrue(text.contains("viewBox=\"0 0 32 32\""), text)
    // Generated from ServeBrand rather than hand-committed, so the icon cannot drift from the mark
    // in the site header or on the unfurl card.
    assertTrue(text.contains("#%02x%02x%02x".format(79, 55, 139)), "the mark's tonal container")
    assertTrue(text.contains("#%02x%02x%02x".format(234, 221, 255)), "the diamond")
  }

  @Test
  fun `the apple touch icon is a 180 pixel png`() {
    val icon = ServeSiteIcon.appleTouchIcon
    val image = assertNotNull(ImageIO.read(ByteArrayInputStream(icon.bytes)))

    assertEquals("image/png", icon.contentType)
    assertEquals(180, image.width)
    assertEquals(180, image.height)
  }

  /**
   * The ICO container is hand-written, so its 22 bytes of header are worth pinning: a wrong offset
   * or length field is not a visibly broken image, it is an icon that silently doesn't load.
   */
  @Test
  fun `the ico wraps a 32 pixel png in a well-formed container`() {
    val bytes = ServeSiteIcon.ico.bytes

    assertEquals("image/vnd.microsoft.icon", ServeSiteIcon.ico.contentType)
    // ICONDIR: reserved 0, type 1 (icon), one image.
    assertEquals(0, le16(bytes, 0))
    assertEquals(1, le16(bytes, 2))
    assertEquals(1, le16(bytes, 4))
    // ICONDIRENTRY: 32×32, no palette, one plane, 32bpp.
    assertEquals(32, bytes[6].toInt() and 0xff)
    assertEquals(32, bytes[7].toInt() and 0xff)
    assertEquals(0, bytes[8].toInt() and 0xff)
    assertEquals(1, le16(bytes, 10))
    assertEquals(32, le16(bytes, 12))

    val length = le32(bytes, 14)
    val offset = le32(bytes, 18)
    assertEquals(22, offset, "the payload starts right after the 6+16 byte header")
    assertEquals(bytes.size - offset, length, "the declared length covers the rest of the file")

    val payload = bytes.copyOfRange(offset, offset + length)
    val image = assertNotNull(ImageIO.read(ByteArrayInputStream(payload)), "payload decodes as PNG")
    assertEquals(32, image.width)
    assertEquals(32, image.height)
  }

  /** Every page carries all three, because no single form is understood by every consumer. */
  @Test
  fun `the head links name all three forms`() {
    val tags = ServeSiteIcon.linkTags()

    assertTrue(
      tags.contains("<link rel=\"icon\" href=\"/favicon.svg\" type=\"image/svg+xml\">"),
      tags,
    )
    assertTrue(tags.contains("<link rel=\"icon\" href=\"/favicon.ico\" sizes=\"32x32\">"), tags)
    assertTrue(
      tags.contains("<link rel=\"apple-touch-icon\" href=\"/apple-touch-icon.png\">"),
      tags,
    )
  }

  private fun le16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8)

  private fun le32(bytes: ByteArray, at: Int): Int = le16(bytes, at) or (le16(bytes, at + 2) shl 16)

  /**
   * What Chrome's installability check reads: a name, a start URL inside the scope, a standalone
   * display, and PNG icons at 192 and 512 — plus a maskable one so a launcher's mask does not crop
   * the mark.
   */
  @Test
  fun `the manifest makes the site installable`() {
    val manifest =
      ServeSiteIcon.manifest(
        name = "Compose Preview",
        shortName = "Compose Preview",
        startUrl = "/",
        shortcuts = listOf(ServeSiteIcon.Shortcut("UI builder", "/ui-builder/", "Start")),
      )
    assertEquals("application/manifest+json", manifest.contentType)
    val json = kotlinx.serialization.json.Json.parseToJsonElement(manifest.bytes.decodeToString())
    val root = json.jsonObject
    assertEquals("standalone", root.getValue("display").jsonPrimitive.content)
    assertEquals("/", root.getValue("start_url").jsonPrimitive.content)
    assertEquals("/", root.getValue("scope").jsonPrimitive.content)
    val icons = root.getValue("icons").jsonArray.map { it.jsonObject }
    val sizes = icons.map { it.getValue("sizes").jsonPrimitive.content }
    assertTrue("192x192" in sizes && "512x512" in sizes, "$sizes")
    assertTrue(icons.any { it.getValue("purpose").jsonPrimitive.content == "maskable" })
    assertEquals(
      "/ui-builder/",
      root
        .getValue("shortcuts")
        .jsonArray
        .single()
        .jsonObject
        .getValue("url")
        .jsonPrimitive
        .content,
    )
    assertTrue(ServeSiteIcon.linkTags().contains("rel=\"manifest\""))
  }

  @Test
  fun `the app icons are the sizes the manifest declares`() {
    for ((icon, size) in
      listOf(
        ServeSiteIcon.appIcon192 to 192,
        ServeSiteIcon.appIcon512 to 512,
        ServeSiteIcon.maskableIcon to 512,
      )) {
      val image = assertNotNull(ImageIO.read(ByteArrayInputStream(icon.bytes)))
      assertEquals(size, image.width)
      assertEquals(size, image.height)
    }
    // Full bleed: a maskable icon's corner is the background, never transparency.
    val maskable = ImageIO.read(ByteArrayInputStream(ServeSiteIcon.maskableIcon.bytes))
    assertEquals(0xff, maskable.getRGB(0, 0) ushr 24)
  }

  /**
   * The notification badge is an alpha mask on Android's status bar: whatever is opaque is tinted
   * and the colour is thrown away. So it has to be a glyph on transparency — a full-colour round
   * icon (what the push worker used before) shows as a solid white disc — and it has to be neither
   * blank nor solid, which is what the coverage bounds pin.
   */
  @Test
  fun `the badge is a white glyph on transparency at 96 pixels`() {
    val icon = ServeSiteIcon.badgeIcon
    assertEquals("image/png", icon.contentType)
    val image = assertNotNull(ImageIO.read(ByteArrayInputStream(icon.bytes)))
    assertEquals(96, image.width)
    assertEquals(96, image.height)
    assertTrue(image.colorModel.hasAlpha(), "the badge carries an alpha channel")

    var opaque = 0.0
    for (y in 0 until image.height) {
      for (x in 0 until image.width) {
        val argb = image.getRGB(x, y)
        val alpha = argb ushr 24
        if (alpha == 0) continue
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        assertTrue(
          r >= 0xf0 && g >= 0xf0 && b >= 0xf0,
          "pixel ($x,$y) is #%06x at alpha $alpha — a badge pixel is white or nothing"
            .format(argb and 0xffffff),
        )
        opaque += alpha / 255.0
      }
    }
    val coverage = opaque / (image.width * image.height)
    assertTrue(coverage in 0.15..0.6, "coverage $coverage: neither blank nor a solid block")

    // A transparent margin all round (Android's 2dp of 24) and a transparent centre: the glyph is
    // the diamond's outline, so the badge reads as the mark rather than as a filled blob.
    for (i in 0 until image.width) {
      for (edge in listOf(0, 1, image.width - 2, image.width - 1)) {
        assertEquals(0, image.getRGB(i, edge) ushr 24, "edge pixel ($i,$edge)")
        assertEquals(0, image.getRGB(edge, i) ushr 24, "edge pixel ($edge,$i)")
      }
    }
    assertEquals(0, image.getRGB(48, 48) ushr 24, "the diamond is hollow")
    // …and the stroke itself is solid at the vertices' midline, not a hairline.
    assertEquals(0xff, image.getRGB(48, 14) ushr 24, "the top of the diamond is opaque")
  }

  /**
   * The manifest's `monochrome` icon is the badge: the same alpha-only contract, the same bytes.
   */
  @Test
  fun `the manifest declares the badge as its monochrome icon`() {
    val manifest =
      ServeSiteIcon.manifest(
        name = "Compose Preview",
        shortName = "Compose Preview",
        startUrl = "/",
        shortcuts = emptyList(),
      )
    val icons =
      kotlinx.serialization.json.Json.parseToJsonElement(manifest.bytes.decodeToString())
        .jsonObject
        .getValue("icons")
        .jsonArray
        .map { it.jsonObject }
    val mono = icons.single { it.getValue("purpose").jsonPrimitive.content == "monochrome" }
    assertEquals(ServeSiteIcon.BADGE_PATH, mono.getValue("src").jsonPrimitive.content)
    assertEquals("96x96", mono.getValue("sizes").jsonPrimitive.content)
    assertEquals("image/png", mono.getValue("type").jsonPrimitive.content)
  }

  /**
   * The installed app's richer install UI and launch behaviour: a second launch focuses the window
   * already open, the install dialog has a phone and a desktop screenshot that are actually served,
   * and the display override never asks for a window-controls overlay the header cannot host.
   */
  @Test
  fun `the manifest carries launch handling, categories and screenshots`() {
    val manifest =
      ServeSiteIcon.manifest(
        name = "Compose Preview",
        shortName = "Compose Preview",
        startUrl = "/",
        shortcuts = emptyList(),
      )
    val root =
      kotlinx.serialization.json.Json.parseToJsonElement(manifest.bytes.decodeToString()).jsonObject
    assertEquals(
      listOf("standalone", "minimal-ui"),
      root.getValue("display_override").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      listOf("focus-existing", "auto"),
      root.getValue("launch_handler").jsonObject.getValue("client_mode").jsonArray.map {
        it.jsonPrimitive.content
      },
    )
    assertTrue(root.getValue("categories").jsonArray.isNotEmpty())
    val shots = root.getValue("screenshots").jsonArray.map { it.jsonObject }
    assertEquals(
      setOf("narrow", "wide"),
      shots.map { it.getValue("form_factor").jsonPrimitive.content }.toSet(),
    )
    for (shot in shots) {
      val src = shot.getValue("src").jsonPrimitive.content
      val bytes = assertNotNull(ServeSiteIcon.screenshot(src), "$src is packaged").bytes
      val image = assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)))
      assertEquals(shot.getValue("sizes").jsonPrimitive.content, "${image.width}x${image.height}")
    }
  }

  /** The browser chrome follows the page's surface in each scheme, including a catalog palette. */
  @Test
  fun `theme-color is declared once per colour scheme`() {
    val tags = ServeSiteIcon.linkTags()
    assertTrue(
      tags.contains(
        """<meta name="theme-color" media="(prefers-color-scheme: light)" content="#fef7ff">"""
      ),
      tags,
    )
    assertTrue(
      tags.contains(
        """<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#141218">"""
      ),
      tags,
    )
    assertTrue(tags.contains("""<meta name="apple-mobile-web-app-capable" content="yes">"""), tags)

    val themed =
      ServeSiteIcon.linkTags(
        ":root {\n  --md-sys-color-surface: light-dark(#F8F4F8, #202124);\n}\n",
        appTitle = "Wear <M3>",
      )
    assertTrue(
      themed.contains("""media="(prefers-color-scheme: light)" content="#f8f4f8""""),
      themed,
    )
    assertTrue(
      themed.contains("""media="(prefers-color-scheme: dark)" content="#202124""""),
      themed,
    )
    assertTrue(themed.contains("""content="Wear &lt;M3&gt;""""), themed)
  }
}
