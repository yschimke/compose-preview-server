package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.BackgroundModifierV1
import ee.schimke.composeai.uibuilder.protocol.BindingValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.ColorValueV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DecimalValueV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DimensionUnitV1
import ee.schimke.composeai.uibuilder.protocol.DimensionValueV1
import ee.schimke.composeai.uibuilder.protocol.EnumValueV1
import ee.schimke.composeai.uibuilder.protocol.HeightModifierV1
import ee.schimke.composeai.uibuilder.protocol.IntegerValueV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.SizeModifierV1
import ee.schimke.composeai.uibuilder.protocol.StateValueV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.TypographyTokenValueV1
import ee.schimke.composeai.uibuilder.protocol.UiValueV1
import ee.schimke.composeai.uibuilder.protocol.WidthModifierV1
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The accessibility half of `ui_builder_check_design` (compose-preview-server#1255).
 *
 * ## Why a design-level check, and why here
 *
 * An agent showing a person a screen that cannot be operated with TalkBack, or whose buttons are
 * 32dp, has spent that person's attention on something it could have caught itself. The preview
 * lane has the accessibility data products for a *rendered preview*; a design has no preview until
 * somebody writes its Kotlin, and the agent needs the answer before that — while the thing it is
 * about to show is still a document it can fix with one `ui_builder_apply`.
 *
 * So these are read off the document, driven by what the pinned catalog says each component is: its
 * traits (`Action`, `SelectionControl`, `IconContent`, `TextContent`) and whether it declares a
 * `contentDescription` property. Nothing here names a Material component, so a Wear or a pack
 * catalog gets the same checks for its own vocabulary, and a component the catalog does not declare
 * is checked only by what its id plainly says.
 *
 * ## What it is not
 *
 * Not a render. A size or a colour the document does not state is not guessed at, and a contrast
 * that depends on a theme role is resolved against the Material 3 baseline scheme and reported as a
 * warning rather than an error, because a dynamic or custom theme can resolve it differently. Where
 * the host has a native render lane and the caller asks for `rendered: true`, touch targets are
 * measured on the real frame instead ([Rendered]), and that measurement replaces the declared one.
 */
internal object UiBuilderAccessibilityCheck {

  /**
   * Node boxes from a real render, and how many render pixels make one dp in it.
   *
   * [pxPerDp] is measured — the frame's width over the design's `widthDp` — rather than taken from
   * the environment's density, because a lane is free to render at a scale of its own.
   */
  class Rendered(val pxPerDp: Double, val boxes: Map<String, ServeUiBuilderView.Box>)

  fun check(
    document: DesignDocumentV1,
    components: Map<String, ComponentCapabilityV1>,
    rendered: Rendered? = null,
  ): List<UiBuilderCheckFindingV1> {
    val tree = Tree(document, components)
    val findings = mutableListOf<UiBuilderCheckFindingV1>()
    for (id in tree.order) {
      val node = document.nodes[id] ?: continue
      findings += labelFindings(tree, node)
      findings += touchTargetFindings(tree, node, rendered)
      findings += contrastFindings(tree, node, document)
      findings += textScalingFindings(tree, node)
    }
    return findings
  }

  // ── Labels ─────────────────────────────────────────────────────────────────────────────────────

  private fun labelFindings(tree: Tree, node: DesignNodeV1): List<UiBuilderCheckFindingV1> {
    if (tree.isInteractive(node)) {
      if (tree.subtreeLabelled(node.id)) return emptyList()
      return listOf(
        finding(
          SEVERITY_ERROR,
          CODE_MISSING_LABEL,
          "`${node.id}` (${node.componentId}) can be activated but announces nothing: give it " +
            "text, a `contentDescription`, or a labelled child, or TalkBack reads it as an " +
            "unlabelled button",
          node.id,
          field = CONTENT_DESCRIPTION.takeIf { tree.declares(node, it) },
        )
      )
    }
    if (!tree.describesContent(node)) return emptyList()
    val value = node.properties[CONTENT_DESCRIPTION]
    // An explicit null is the author saying "decorative", which is a legitimate answer.
    if (value is NullValueV1 || tree.ownDescription(node) != null) return emptyList()
    // An icon inside a control that already says what it does is decorative by construction: the
    // control's label is what is announced, and describing the icon too would read it twice.
    if (tree.ancestors(node.id).any { tree.isInteractive(it) && tree.subtreeHasText(it.id) }) {
      return emptyList()
    }
    return listOf(
      finding(
        SEVERITY_WARNING,
        CODE_MISSING_CONTENT_DESCRIPTION,
        "`${node.id}` (${node.componentId}) has no `contentDescription`: describe what it shows, " +
          "or set it to null if it is decorative",
        node.id,
        field = CONTENT_DESCRIPTION,
      )
    )
  }

  // ── Touch targets ─────────────────────────────────────────────────────────────────────────────

  private fun touchTargetFindings(
    tree: Tree,
    node: DesignNodeV1,
    rendered: Rendered?,
  ): List<UiBuilderCheckFindingV1> {
    if (!tree.isInteractive(node)) return emptyList()
    val box = rendered?.boxes?.get(node.id)
    if (rendered != null && box != null && rendered.pxPerDp > 0) {
      val width = box.width / rendered.pxPerDp
      val height = box.height / rendered.pxPerDp
      // Half a dp of slack: a 48dp target rasterised at a fractional density measures 47.6.
      if (min(width, height) >= MIN_TOUCH_TARGET_DP - 0.5) return emptyList()
      return listOf(
        finding(
          SEVERITY_ERROR,
          CODE_TOUCH_TARGET,
          "`${node.id}` renders at ${width.dp()}×${height.dp()}dp; a touch target needs at least " +
            "${MIN_TOUCH_TARGET_DP.toInt()}×${MIN_TOUCH_TARGET_DP.toInt()}dp",
          node.id,
          measured = UiBuilderCheckMeasurementV1(width = width, height = height, unit = "dp"),
        )
      )
    }
    val declared = declaredSizeDp(node) ?: return emptyList()
    if (declared.second >= MIN_TOUCH_TARGET_DP) return emptyList()
    return listOf(
      finding(
        SEVERITY_WARNING,
        CODE_TOUCH_TARGET,
        "`${node.id}` declares ${declared.first} = ${declared.second.dp()}dp; a touch target " +
          "needs at least ${MIN_TOUCH_TARGET_DP.toInt()}dp in each direction. Material " +
          "components pad a smaller visual up to 48dp only when nothing clamps their size",
        node.id,
        field = declared.first,
        measured = UiBuilderCheckMeasurementV1(width = declared.second, unit = "dp"),
      )
    )
  }

  /** The smallest size the document states for [node], and where it said it. */
  private fun declaredSizeDp(node: DesignNodeV1): Pair<String, Double>? {
    val sizes = buildList {
      node.properties[SIZE_DP]?.number()?.let { add(SIZE_DP to it) }
      node.modifiers.forEach { modifier ->
        when (modifier) {
          is SizeModifierV1 -> {
            modifier.widthDp.dp()?.let { add("size.widthDp" to it) }
            modifier.heightDp.dp()?.let { add("size.heightDp" to it) }
          }
          is WidthModifierV1 -> modifier.widthDp.dp()?.let { add("width.widthDp" to it) }
          is HeightModifierV1 -> modifier.heightDp.dp()?.let { add("height.heightDp" to it) }
          else -> Unit
        }
      }
    }
    return sizes.minByOrNull { it.second }
  }

  // ── Contrast ──────────────────────────────────────────────────────────────────────────────────

  private fun contrastFindings(
    tree: Tree,
    node: DesignNodeV1,
    document: DesignDocumentV1,
  ): List<UiBuilderCheckFindingV1> {
    val isText = tree.isText(node)
    val isIcon = !isText && tree.hasTrait(node, TRAIT_ICON)
    if (!isText && !isIcon) return emptyList()
    val dark = document.environment.theme == ThemeV1.DARK
    val explicitForeground = FOREGROUND_PROPERTIES.firstNotNullOfOrNull { name ->
      node.properties[name]?.let { value -> resolveColor(value, dark)?.let { name to it } }
    }
    val background = backgroundOf(tree, node, document, dark)
    val foreground =
      explicitForeground?.second
        ?: run {
          // A default content colour is only predictable against a plain background modifier: a
          // component with a container colour supplies its own matching content colour, which this
          // check cannot see.
          if (background == null || background.source != BackgroundSource.MODIFIER) return@run null
          baselineScheme(dark).getValue(DEFAULT_CONTENT_ROLE)
        }
        ?: return emptyList()
    if (background == null) return emptyList()
    val ratio = contrastRatio(foreground.argb, background.color.argb)
    val large = isIcon || isLargeText(node)
    val required = if (large) 3.0 else 4.5
    if (ratio >= required) return emptyList()
    val approximate = foreground.fromToken || background.color.fromToken
    return listOf(
      finding(
        if (approximate) SEVERITY_WARNING else SEVERITY_ERROR,
        CODE_CONTRAST,
        "`${node.id}` has a contrast of ${"%.2f".format(ratio)}:1 against `${background.nodeId}`" +
          "; ${if (isIcon) "an icon" else if (large) "large text" else "text"} needs " +
          "${required.toInt().let { if (it.toDouble() == required) "$it" else "$required" }}:1" +
          (if (approximate)
            " (theme roles resolved against the Material 3 baseline" +
              (if (explicitForeground == null) ", text assumed to be `$DEFAULT_CONTENT_ROLE`"
              else "") +
              "; a custom or dynamic theme can differ)"
          else ""),
        node.id,
        field = explicitForeground?.first,
        measured = UiBuilderCheckMeasurementV1(ratio = ratio, required = required),
      )
    )
  }

  private enum class BackgroundSource {
    MODIFIER,
    CONTAINER,
    ENVIRONMENT,
  }

  private class Background(val color: Resolved, val nodeId: String, val source: BackgroundSource)

  private fun backgroundOf(
    tree: Tree,
    node: DesignNodeV1,
    document: DesignDocumentV1,
    dark: Boolean,
  ): Background? {
    for (candidate in listOf(node) + tree.ancestors(node.id)) {
      candidate.modifiers
        .lastOrNull { it is BackgroundModifierV1 }
        ?.let { (it as BackgroundModifierV1).color }
        ?.let { resolveColor(it, dark) }
        ?.takeIf { it.alpha > 0 }
        ?.let {
          return Background(it.overOpaque(dark), candidate.id, BackgroundSource.MODIFIER)
        }
      if (candidate !== node) {
        candidate.properties[CONTAINER_COLOR]
          ?.let { resolveColor(it, dark) }
          ?.takeIf { it.alpha > 0 }
          ?.let {
            return Background(it.overOpaque(dark), candidate.id, BackgroundSource.CONTAINER)
          }
      }
    }
    val environment = document.environment.background?.let { resolveColor(it, dark) }
    return environment?.let {
      Background(it.overOpaque(dark), ENVIRONMENT_NODE, BackgroundSource.ENVIRONMENT)
    }
  }

  private fun isLargeText(node: DesignNodeV1): Boolean {
    val style =
      when (val value = node.properties[STYLE]) {
        is TypographyTokenValueV1 -> value.value
        is EnumValueV1 -> value.value
        is StringValueV1 -> value.value
        else -> null
      } ?: return false
    return LARGE_TEXT_STYLES.any { style.startsWith(it, ignoreCase = true) }
  }

  /** A colour as opaque-or-not ARGB, and whether a theme role had to be resolved to get it. */
  class Resolved(val argb: Long, val fromToken: Boolean) {
    val alpha: Int
      get() = ((argb shr 24) and 0xff).toInt()

    /** This colour composited over the theme's own background, so a ratio has two opaque ends. */
    fun overOpaque(dark: Boolean): Resolved {
      if (alpha == 0xff) return this
      val base = baselineScheme(dark).getValue("background").argb
      return Resolved(composite(argb, base), fromToken)
    }
  }

  fun resolveColor(value: UiValueV1, dark: Boolean): Resolved? =
    when (value) {
      is ColorValueV1 -> parseHex(value.value)?.let { Resolved(it, fromToken = false) }
      is ColorTokenValueV1 -> baselineScheme(dark)[value.value]
      else -> null
    }

  /** `#RRGGBB` or `#AARRGGBB` as ARGB, or null for anything else. */
  fun parseHex(text: String): Long? {
    val hex = text.removePrefix("#")
    val parsed = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
      6 -> 0xff000000L or parsed
      8 -> parsed
      else -> null
    }
  }

  /**
   * WCAG 2.x contrast ratio of two colours; [foreground]'s alpha is composited over [background].
   */
  fun contrastRatio(foreground: Long, background: Long): Double {
    val fg = luminance(composite(foreground, background))
    val bg = luminance(background)
    return (max(fg, bg) + 0.05) / (min(fg, bg) + 0.05)
  }

  private fun composite(top: Long, bottom: Long): Long {
    val a = ((top shr 24) and 0xff) / 255.0
    fun channel(shift: Int): Long {
      val t = (top shr shift) and 0xff
      val b = (bottom shr shift) and 0xff
      return Math.round(t * a + b * (1 - a)).toLong()
    }
    return 0xff000000L or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
  }

  private fun luminance(argb: Long): Double {
    fun linear(shift: Int): Double {
      val c = ((argb shr shift) and 0xff) / 255.0
      return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
  }

  /**
   * The Material 3 baseline scheme for the roles a catalog's `colorTokens` names — the closest
   * thing to the truth a document carries no theme for. Anything resolved here is reported as
   * approximate, never as an error.
   */
  private fun baselineScheme(dark: Boolean): Map<String, Resolved> =
    if (dark) DARK_SCHEME else LIGHT_SCHEME

  private fun scheme(vararg roles: Pair<String, Long>): Map<String, Resolved> =
    roles.associate { (role, rgb) -> role to Resolved(0xff000000L or rgb, fromToken = true) } +
      ("transparent" to Resolved(0L, fromToken = true))

  private val LIGHT_SCHEME =
    scheme(
      "background" to 0xFEF7FF,
      "surface" to 0xFEF7FF,
      "surfaceContainerLowest" to 0xFFFFFF,
      "surfaceContainerLow" to 0xF7F2FA,
      "surfaceContainer" to 0xF3EDF7,
      "surfaceContainerHigh" to 0xECE6F0,
      "surfaceContainerHighest" to 0xE6E0E9,
      "primary" to 0x6750A4,
      "onPrimary" to 0xFFFFFF,
      "primaryContainer" to 0xEADDFF,
      "onPrimaryContainer" to 0x4F378B,
      "secondary" to 0x625B71,
      "onSecondary" to 0xFFFFFF,
      "tertiary" to 0x7D5260,
      "onTertiary" to 0xFFFFFF,
      "error" to 0xB3261E,
      "onError" to 0xFFFFFF,
      "onBackground" to 0x1D1B20,
      "onSurface" to 0x1D1B20,
      "onSurfaceVariant" to 0x49454F,
      "outline" to 0x79747E,
      "outlineVariant" to 0xCAC4D0,
    )

  private val DARK_SCHEME =
    scheme(
      "background" to 0x141218,
      "surface" to 0x141218,
      "surfaceContainerLowest" to 0x0F0D13,
      "surfaceContainerLow" to 0x1D1B20,
      "surfaceContainer" to 0x211F26,
      "surfaceContainerHigh" to 0x2B2930,
      "surfaceContainerHighest" to 0x36343B,
      "primary" to 0xD0BCFF,
      "onPrimary" to 0x381E72,
      "primaryContainer" to 0x4F378B,
      "onPrimaryContainer" to 0xEADDFF,
      "secondary" to 0xCCC2DC,
      "onSecondary" to 0x332D41,
      "tertiary" to 0xEFB8C8,
      "onTertiary" to 0x492532,
      "error" to 0xF2B8B5,
      "onError" to 0x601410,
      "onBackground" to 0xE6E0E9,
      "onSurface" to 0xE6E0E9,
      "onSurfaceVariant" to 0xCAC4D0,
      "outline" to 0x938F99,
      "outlineVariant" to 0x49454F,
    )

  // ── Text scaling ──────────────────────────────────────────────────────────────────────────────

  /**
   * Text inside a box whose height is fixed in dp clips when the person reading it has a larger
   * font: the text grows in sp and the box does not. Checked at 200%, the largest scale Android
   * offers, against the nearest fixed height on the node or the two levels above it — further up, a
   * scrolling or wrapping container usually decides, and a warning there would be noise.
   */
  private fun textScalingFindings(tree: Tree, node: DesignNodeV1): List<UiBuilderCheckFindingV1> {
    if (!tree.isText(node)) return emptyList()
    val chain = listOf(node) + tree.ancestors(node.id).take(2)
    val (holder, height) =
      chain.firstNotNullOfOrNull { candidate -> fixedHeightDp(candidate)?.let { candidate to it } }
        ?: return emptyList()
    val needed = lineHeightSp(node) * MAX_FONT_SCALE
    if (height >= needed) return emptyList()
    return listOf(
      finding(
        SEVERITY_WARNING,
        CODE_TEXT_SCALING,
        "`${node.id}` sits in a fixed ${height.dp()}dp height" +
          (if (holder.id != node.id) " on `${holder.id}`" else "") +
          "; at ${(MAX_FONT_SCALE * 100).toInt()}% font scale one line needs about " +
          "${needed.dp()}dp and will clip. Prefer `heightIn(min = …)`",
        node.id,
        measured = UiBuilderCheckMeasurementV1(height = height, required = needed, unit = "dp"),
      )
    )
  }

  private fun fixedHeightDp(node: DesignNodeV1): Double? =
    node.modifiers.firstNotNullOfOrNull { modifier ->
      when (modifier) {
        is SizeModifierV1 -> modifier.heightDp.dp()
        is HeightModifierV1 -> modifier.heightDp.dp()
        else -> null
      }
    }

  /** Material 3's line height for the node's typography role, body-medium when it names none. */
  private fun lineHeightSp(node: DesignNodeV1): Double {
    val style =
      when (val value = node.properties[STYLE]) {
        is TypographyTokenValueV1 -> value.value
        is EnumValueV1 -> value.value
        is StringValueV1 -> value.value
        else -> null
      }
    return LINE_HEIGHTS_SP.entries.firstOrNull { it.key.equals(style, ignoreCase = true) }?.value
      ?: DEFAULT_LINE_HEIGHT_SP
  }

  private val LINE_HEIGHTS_SP =
    mapOf(
      "displayLarge" to 64.0,
      "displayMedium" to 52.0,
      "displaySmall" to 44.0,
      "headlineLarge" to 40.0,
      "headlineMedium" to 36.0,
      "headlineSmall" to 32.0,
      "titleLarge" to 28.0,
      "titleMedium" to 24.0,
      "titleSmall" to 20.0,
      "bodyLarge" to 24.0,
      "bodyMedium" to 20.0,
      "bodySmall" to 16.0,
      "labelLarge" to 20.0,
      "labelMedium" to 16.0,
      "labelSmall" to 16.0,
    )

  // ── The tree ──────────────────────────────────────────────────────────────────────────────────

  private class Tree(
    val document: DesignDocumentV1,
    val components: Map<String, ComponentCapabilityV1>,
  ) {
    private val parents: Map<String, String> = buildMap {
      document.nodes.values.forEach { node ->
        node.slots.values.flatten().forEach { child -> putIfAbsent(child, node.id) }
      }
    }

    /**
     * Depth-first from the roots, so findings read top to bottom; unreachable nodes are skipped.
     */
    val order: List<String> = buildList {
      val seen = HashSet<String>()
      fun visit(id: String) {
        if (!seen.add(id)) return
        val node = document.nodes[id] ?: return
        add(id)
        node.slots.values.flatten().forEach(::visit)
      }
      document.roots.forEach(::visit)
    }

    fun ancestors(id: String): List<DesignNodeV1> = buildList {
      var current = parents[id]
      val seen = HashSet<String>()
      while (current != null && seen.add(current)) {
        document.nodes[current]?.let(::add)
        current = parents[current]
      }
    }

    fun traits(node: DesignNodeV1): List<String> =
      components[node.componentId]?.traits ?: inferredTraits(node.componentId)

    fun hasTrait(node: DesignNodeV1, trait: String): Boolean = trait in traits(node)

    fun declares(node: DesignNodeV1, property: String): Boolean =
      components[node.componentId]?.properties?.any { it.name == property }
        ?: (property in node.properties)

    fun isInteractive(node: DesignNodeV1): Boolean =
      traits(node).any { it in INTERACTIVE_TRAITS } ||
        node.eventBindings.values.any { it.isNotEmpty() } ||
        node.properties.keys.any { it in ACTION_PROPERTIES }

    fun isText(node: DesignNodeV1): Boolean =
      hasTrait(node, TRAIT_TEXT) && TEXT_PROPERTIES.any { node.properties[it].isLabel() }

    /** Shows something a screen reader should describe: an icon, a picture. */
    fun describesContent(node: DesignNodeV1): Boolean =
      hasTrait(node, TRAIT_ICON) ||
        node.componentId.endsWith("/image") ||
        (declares(node, CONTENT_DESCRIPTION) && !isInteractive(node) && !isText(node))

    fun ownText(node: DesignNodeV1): Boolean = TEXT_PROPERTIES.any { node.properties[it].isLabel() }

    fun ownDescription(node: DesignNodeV1): String? =
      (node.properties[CONTENT_DESCRIPTION] as? StringValueV1)?.value?.takeIf { it.isNotBlank() }
        ?: node.accessibility?.contentDescription?.takeIf { it.isNotBlank() }
        ?: node.accessibility?.label?.takeIf { it.isNotBlank() }
        ?: (node.properties[CONTENT_DESCRIPTION] as? BindingValueV1)?.value
        ?: (node.properties[CONTENT_DESCRIPTION] as? StateValueV1)?.variable

    fun subtreeLabelled(id: String): Boolean =
      walk(id).any { ownText(it) || ownDescription(it) != null }

    fun subtreeHasText(id: String): Boolean = walk(id).any { ownText(it) }

    private fun walk(id: String): Sequence<DesignNodeV1> = sequence {
      val seen = HashSet<String>()
      val stack = ArrayDeque(listOf(id))
      while (stack.isNotEmpty()) {
        val next = stack.removeLast()
        if (!seen.add(next)) continue
        val node = document.nodes[next] ?: continue
        yield(node)
        node.slots.values.flatten().forEach(stack::addLast)
      }
    }
  }

  /** Traits for a component the catalog does not declare, from what its id plainly says. */
  private fun inferredTraits(componentId: String): List<String> {
    val name = componentId.substringAfterLast('/')
    return when {
      name == "text" -> listOf(TRAIT_TEXT)
      name == "icon" -> listOf(TRAIT_ICON)
      INFERRED_ACTIONS.any { name.endsWith(it) } -> listOf(TRAIT_ACTION)
      else -> emptyList()
    }
  }

  private fun UiValueV1?.isLabel(): Boolean =
    when (this) {
      is StringValueV1 -> value.isNotBlank()
      // A value bound to state or data is a label the person will see at run time.
      is BindingValueV1,
      is StateValueV1 -> true
      else -> false
    }

  private fun UiValueV1.number(): Double? =
    when (this) {
      is DecimalValueV1 -> value
      is IntegerValueV1 -> value.toDouble()
      is DimensionValueV1 ->
        if (unit == DimensionUnitV1.DP) (value as? JsonPrimitive)?.doubleOrNull else null
      else -> null
    }

  /** A modifier's dp field, when it is a literal number rather than a binding. */
  private fun JsonElement?.dp(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

  private fun Double.dp(): String =
    if (this == Math.floor(this)) toLong().toString() else "%.1f".format(this)

  private fun finding(
    severity: String,
    code: String,
    message: String,
    nodeId: String,
    field: String? = null,
    measured: UiBuilderCheckMeasurementV1? = null,
  ) =
    UiBuilderCheckFindingV1(
      severity = severity,
      check = CHECK_A11Y,
      code = code,
      message = message,
      nodeId = nodeId,
      field = field,
      measured = measured,
    )

  const val CODE_MISSING_LABEL = "missingLabel"
  const val CODE_MISSING_CONTENT_DESCRIPTION = "missingContentDescription"
  const val CODE_TOUCH_TARGET = "touchTargetTooSmall"
  const val CODE_CONTRAST = "lowContrast"
  const val CODE_TEXT_SCALING = "textMayClip"

  /** Android's and Material's minimum, in both directions. */
  const val MIN_TOUCH_TARGET_DP = 48.0

  /** The largest font scale Android offers in settings. */
  private const val MAX_FONT_SCALE = 2.0
  private const val DEFAULT_LINE_HEIGHT_SP = 20.0
  private const val DEFAULT_CONTENT_ROLE = "onSurface"

  /** What a finding names as the background when only the design environment supplied one. */
  private const val ENVIRONMENT_NODE = "environment.background"

  private const val CONTENT_DESCRIPTION = "contentDescription"
  private const val CONTAINER_COLOR = "containerColor"
  private const val SIZE_DP = "sizeDp"
  private const val STYLE = "style"
  private val FOREGROUND_PROPERTIES = listOf("color", "contentColor", "textColor")
  private val TEXT_PROPERTIES = listOf("text", "label", "title", "headline")
  private val ACTION_PROPERTIES = setOf("onClickAction", "onClick")

  private const val TRAIT_ACTION = "Action"
  private const val TRAIT_TEXT = "TextContent"
  private const val TRAIT_ICON = "IconContent"
  private val INTERACTIVE_TRAITS = setOf(TRAIT_ACTION, "SelectionControl", "TabItem")
  private val INFERRED_ACTIONS =
    listOf("button", "chip", "checkbox", "switch", "radio-button", "toggle")
  private val LARGE_TEXT_STYLES = listOf("display", "headline", "titleLarge")
}

/** The checks `ui_builder_check_design` runs, by the name a caller asks for them with. */
internal const val CHECK_SCHEMA = "schema"
internal const val CHECK_CATALOG = "catalog"
internal const val CHECK_A11Y = "a11y"
internal val DESIGN_CHECKS = listOf(CHECK_SCHEMA, CHECK_CATALOG, CHECK_A11Y)

/**
 * A model's reading of the design against the Android design guides ([ServeUiBuilderGuidelines]).
 * Never run unless asked for by name: it spends the operator's OpenRouter key and is open only to
 * the accounts the operator named.
 */
internal const val CHECK_GUIDELINES = "guidelines"

/** Every check a caller may name; [DESIGN_CHECKS] is what runs when they name none. */
internal val ALL_DESIGN_CHECKS = DESIGN_CHECKS + CHECK_GUIDELINES

internal const val UI_BUILDER_DESIGN_CHECK_SCHEMA = "compose-preview/ui-builder-design-check/v1"

/**
 * `ui_builder_check_design`'s reply: a sentence to read first, the counts, then every finding with
 * the node it is about — so an agent can fix each one with a single `ui_builder_apply` before it
 * shows the design to anybody.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderDesignCheckV1(
  @EncodeDefault val schema: String = UI_BUILDER_DESIGN_CHECK_SCHEMA,
  /** True when no finding is an error. Warnings are worth reading and do not block. */
  val ok: Boolean,
  val summary: String,
  val designId: String? = null,
  /** The revision checked; null for a document that is not stored. */
  val revision: Long? = null,
  /** True when `operations` were applied to a scratch copy and the result was checked. */
  val dryRun: Boolean = false,
  val checks: List<String>,
  val errors: Int,
  val warnings: Int,
  val findings: List<UiBuilderCheckFindingV1>,
  /** How many findings were left out to keep the reply small; the counts include them. */
  val truncated: Int = 0,
  /** Checks that were asked for and could not run here, with why. */
  val skipped: List<UiBuilderCheckSkippedV1> = emptyList(),
  /**
   * The guidelines result this check recorded as the design's latest — what
   * `ui_builder_get_guidelines` and the editor now show. Null when `guidelines` did not run, or ran
   * on a dry run or a loose document, which are not recorded.
   */
  val guidelines: ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRecord? = null,
)

@Serializable
internal data class UiBuilderCheckFindingV1(
  /** `error`, `warning` or `info`. */
  val severity: String,
  /** `schema`, `catalog`, `a11y` or `guidelines`. */
  val check: String,
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val field: String? = null,
  val operationIndex: Int? = null,
  val measured: UiBuilderCheckMeasurementV1? = null,
)

/** The number behind a measured finding, so a fix can be sized rather than guessed. */
@Serializable
internal data class UiBuilderCheckMeasurementV1(
  val width: Double? = null,
  val height: Double? = null,
  val ratio: Double? = null,
  val required: Double? = null,
  val unit: String? = null,
)

@Serializable internal data class UiBuilderCheckSkippedV1(val check: String, val reason: String)
