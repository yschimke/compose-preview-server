package ee.schimke.composeai.cli.serve

/**
 * Why an SVG may not be attached to a design as a reference, or null when it may.
 *
 * An SVG is a document, not a picture. This one is stored under an operator's design and handed
 * back, base64-encoded inside a JSON body, to be drawn into a Skia canvas by their editor. Skia
 * runs no script and is given no resource provider, so an external reference would fetch nothing
 * and a handler would never fire — this refuses both regardless, because "it happens not to work
 * today" is not a boundary, and because the bytes outlive today's renderer.
 *
 * **Deliberately cruder than the editor's copy.** `:ui-builder` runs the same rule over a real
 * parse ([`referenceSvgRefusal`][ee.schimke.composeai.uibuilder] there); this side is a textual
 * scan, because `:server` cannot depend on that Compose module and a second XML parser written for
 * a trust boundary is a liability rather than an asset. A textual scan can only err toward refusing
 * — it cannot be fooled into accepting a `<script>` it did not see, since it never has to decide
 * what an element *is* — and an SVG this refuses and a browser would have drawn costs the operator
 * a PNG export instead. That is the trade being made on purpose.
 */
internal fun referenceSvgRefusal(svg: String): String? {
  val lowered = svg.lowercase()
  if (!lowered.contains("<svg")) return "the file does not look like an SVG"
  UNSAFE_SVG_TOKENS.forEach { token ->
    if (lowered.contains(token)) return "the SVG contains `$token`, which a reference may not carry"
  }
  // `on…=` in element position. Matched loosely — an attribute-shaped `onclick=` anywhere in the
  // markup is enough to refuse, including inside a text node this scan cannot tell apart.
  if (EVENT_HANDLER.containsMatchIn(lowered)) return "the SVG contains an event handler"
  EXTERNAL_REFERENCE.findAll(lowered).forEach { match ->
    val target = match.groupValues[1].trim()
    if (!target.startsWith("#") && !target.startsWith("data:image/")) {
      return "the SVG references something outside itself"
    }
  }
  return null
}

/**
 * Element names that make an SVG active or embedding. Lower-cased substrings including the `<`, so
 * a `<script>` in an attribute value or a text node refuses too — see the note above on erring
 * toward refusal.
 */
private val UNSAFE_SVG_TOKENS =
  listOf(
    "<script",
    "<foreignobject",
    "<iframe",
    "<object",
    "<embed",
    "<audio",
    "<video",
    "<handler",
    "<!entity",
    "<!doctype",
    "javascript:",
  )

private val EVENT_HANDLER = Regex("""\son[a-z]+\s*=""")

private val EXTERNAL_REFERENCE = Regex("""(?:xlink:)?href\s*=\s*["']([^"']*)["']""")
