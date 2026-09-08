package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The layout a node is emitted inside, which decides the scope modifiers it may write.
 *
 * Remote Compose puts scope modifiers on a receiver exactly as Compose does — `weight` on
 * `RemoteRowScope`/`RemoteColumnScope`, and nothing at all on `RemoteBoxScope`. A generator that
 * did not track the enclosing layout could only either refuse every scope modifier or write ones
 * that do not compile.
 */
internal enum class Parent {
  NONE,
  BOX,
  ROW,
  COLUMN,
}

/**
 * Writes a widget's designed content as Remote Compose source, and its background as a
 * `WearWidgetBrush`.
 *
 * Separate from [WearWidgetCodeExporter] because the two answer different questions: that one owns
 * the widget's *shape* — the class, the document, the preview — and this one owns the vocabulary
 * the body is written in. The split is also where the refusals collect: an unsupported node is
 * appended to [refusals] and emits nothing, so one pass reports every reason rather than the first.
 */
internal class RemoteContentEmitter(
  private val document: UiBuilderDocument,
  private val refusals: MutableList<String>,
) {
  /** True once a colour or type token has been written, which only reads inside a theme. */
  var usesTheme: Boolean = false
    private set

  private var usesMaterialText = false
  private var usesColumn = false
  private var usesRow = false
  private var usesBox = false
  private var usesArrangement = false
  private var usesAlignment = false
  private var usesDp = false
  private var usesTextAlign = false

  /** The `WearWidgetBrush` chain a container's background declares. */
  data class Background(val expression: String, val locals: List<String>)

  fun background(container: UiBuilderNode): Background {
    val locals = mutableListOf<String>()
    val elements = mutableListOf<String>()
    val declared = container.properties["background"]?.stringOrNull().orEmpty()
    if (declared.isNotEmpty()) {
      // A theme token cannot be read here: `provideWidgetData` is not composition. The sample
      // instantiates the scheme for exactly this reason, so the generated code does too.
      if (!declared.startsWith("#")) {
        locals += "val colorScheme = RemoteColorScheme()"
        elements += "color(colorScheme.$declared)"
        usesRemoteColorScheme = true
        usesBrushColor = true
      } else {
        usesColorLiteral = true
        usesBrushColor = true
        elements += "color(${declared.argbLiteral()}.rc)"
      }
    }
    container.slots["background"].orEmpty().forEach { id ->
      val node = document.nodes[id] ?: return@forEach
      when (node.componentId) {
        "shape/linear-gradient" -> {
          val start = node.properties["startColor"]?.stringOrNull().orEmpty()
          val end = node.properties["endColor"]?.stringOrNull().orEmpty()
          if (!start.startsWith("#") || !end.startsWith("#")) {
            refusals +=
              "the gradient background `$id` uses a theme token; a widget background is built " +
                "outside composition, so its colours have to be literals"
            return@forEach
          }
          usesColorLiteral = true
          val stops = "listOf(${start.argbLiteral()}.rc, ${end.argbLiteral()}.rc)"
          val reversed = "listOf(${end.argbLiteral()}.rc, ${start.argbLiteral()}.rc)"
          elements +=
            when (node.properties["direction"]?.stringOrNull()) {
              // `horizontal`/`vertical` name the axis without a sense, which is what the widget
              // templates write. They are read here, and identically by the canvas, because the
              // alternative is the silent one: an unknown direction falling through to `else` drew
              // a side scrim vertically and generated Kotlin that agreed with the wrong picture.
              "leftToRight",
              "horizontal" -> horizontal("horizontalGradient($stops)")
              "rightToLeft" -> horizontal("horizontalGradient($reversed)")
              "bottomToTop" -> vertical("verticalGradient($reversed)")
              "topToBottom",
              "vertical",
              null,
              "" -> vertical("verticalGradient($stops)")
              else -> {
                refusals +=
                  "the gradient `$id` names the direction " +
                    "`${node.properties["direction"]?.stringOrNull()}`, which is not one this " +
                    "generator or the canvas draws"
                return@forEach
              }
            }
        }
        // `WearWidgetBrush.image` takes a `RemoteImageBitmap`, and `RemoteImageBitmap(String)` is
        // the named-bitmap overload — so the asset key IS the name, and the widget supplies the
        // pixels under it in `provideWidgetData`. Generated source can never carry a bitmap; what
        // it can do is name exactly which one to supply, which the file's header then lists.
        "asset/image" -> {
          val key = node.properties["assetKey"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
          if (key == null) {
            refusals +=
              "the image background `$id` names no asset key, so no bitmap can be supplied for it"
          } else {
            usesRemoteImageBitmap = true
            namedBitmaps += key
            val scale =
              node.properties["contentScale"]?.stringOrNull()?.remoteContentScale()
                ?: "ContentScale.Crop"
            usesContentScale = true
            elements += "image(RemoteImageBitmap(\"${key.escaped()}\"), $scale)"
          }
        }
        else -> refusals += "`${node.componentId}` is not a widget background brush"
      }
    }
    // The empty chain is a real value upstream: `WearWidgetBrush` alone is what a widget passes to
    // accept the host's default fill.
    val expression =
      if (elements.isEmpty()) "WearWidgetBrush"
      else elements.joinToString(".", prefix = "WearWidgetBrush.")
    return Background(expression, locals)
  }

  private var usesRemoteColorScheme = false
  private var usesColorLiteral = false
  private var usesBrushColor = false
  private var usesRemoteImage = false
  private var usesRemoteImageBitmap = false
  private var usesContentScale = false
  private var usesRoundedCornerShape = false

  /**
   * The asset keys the emitted source names a `RemoteImageBitmap` for, in first-written order.
   *
   * Read by [WearWidgetCodeExporter] for the file's header: a generated widget that names bitmaps
   * it cannot carry has to say which ones, or the first run is a blank rectangle nobody can
   * explain.
   */
  val namedBitmaps: MutableSet<String> = linkedSetOf()

  /**
   * The node and its subtree, as indented source lines.
   *
   * [parent] is the layout the node is being written inside, which decides the scope modifiers it
   * may carry. The default is [Parent.NONE] because the widget body's own root has no enclosing
   * layout — it is the argument to `WearWidgetDocument`.
   */
  fun emit(nodeId: String, depth: Int, parent: Parent = Parent.NONE): List<String> {
    val node = document.nodes[nodeId] ?: return emptyList()
    val pad = INDENT.repeat(depth)
    return when (node.componentId) {
      "m3/text" -> (pad + text(node, pad, parent)).split("\n")
      "layout/box" -> container(node, depth, "RemoteBox", boxArguments(node, parent), Parent.BOX)
      "layout/column" ->
        container(node, depth, "RemoteColumn", columnArguments(node, parent), Parent.COLUMN)
      "layout/row" -> container(node, depth, "RemoteRow", rowArguments(node, parent), Parent.ROW)
      "asset/image" -> image(node, pad, parent)?.let { (pad + it).split("\n") } ?: emptyList()
      "remote-m3/lottie" -> lottie(node, pad, parent)?.let { (pad + it).split("\n") } ?: emptyList()
      else -> {
        refusals +=
          "`${node.componentId}` has no Remote Compose counterpart this generator can write"
        emptyList()
      }
    }
  }

  /**
   * An `asset/image` as `RemoteImage`, naming its bitmap by the design's asset key.
   *
   * `RemoteImageBitmap(String)` is the named-bitmap overload, and the key is the name: the widget
   * supplies the bitmap under it in `provideWidgetData`, which is the same seam the container
   * background uses. Generated source cannot carry the pixels — it names what to supply, and the
   * file's header says so.
   */
  private fun image(node: UiBuilderNode, pad: String, parent: Parent): String? {
    val key = node.properties["assetKey"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
    if (key == null) {
      refusals += "the image `${node.id}` names no asset key, so no bitmap can be supplied for it"
      return null
    }
    usesRemoteImage = true
    namedBitmaps += key
    val arguments = mutableListOf("RemoteImageBitmap(\"${key.escaped()}\")")
    val description = node.properties["contentDescription"]?.stringOrNull().orEmpty()
    arguments +=
      if (description.isEmpty()) "contentDescription = null"
      else {
        usesMaterialText = true
        "contentDescription = \"${description.escaped()}\".rs"
      }
    node.modifierExpression(parent)?.let { arguments += "modifier = $it" }
    node.properties["contentScale"]?.stringOrNull()?.remoteContentScale()?.let {
      usesContentScale = true
      arguments += "contentScale = $it"
    }
    return call("RemoteImage", arguments, pad)
  }

  private fun container(
    node: UiBuilderNode,
    depth: Int,
    symbol: String,
    arguments: List<String>,
    scope: Parent,
  ): List<String> {
    when (symbol) {
      "RemoteBox" -> usesBox = true
      "RemoteColumn" -> usesColumn = true
      "RemoteRow" -> usesRow = true
    }
    val pad = INDENT.repeat(depth)
    val children = node.slots["children"].orEmpty()
    if (children.isEmpty()) {
      return (pad + if (arguments.isEmpty()) "$symbol()" else call(symbol, arguments, pad)).split(
        "\n"
      )
    }
    val head =
      if (arguments.isEmpty()) "$symbol {"
      // `call` measures the call alone; the ` {` this appends is two more columns, and without
      // counting them a call landing on 99 or 100 columns is emitted one or two over the budget.
      else "${call(symbol, arguments, pad, trailing = OPENING_BRACE.length)}$OPENING_BRACE"
    return (pad + head).split("\n") +
      children.flatMap { emit(it, depth + 1, scope) } +
      listOf("$pad}")
  }

  private fun boxArguments(node: UiBuilderNode, parent: Parent): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(parent)?.let { arguments += "modifier = $it" }
    // `layout/box` aligns each child by that child's own `alignment`, while `RemoteBox` aligns all
    // of them together. One child is the case both samples write and the case the two models agree
    // on; more than one, each wanting a different corner, is a design this cannot write.
    //
    // A child states that alignment either as its `alignment` property or as an `align` modifier —
    // the editor writes whichever the drag produced, and they mean the same thing. Both are read
    // here, which is also what makes `align` legal inside a box: it is hoisted to this argument
    // rather than written on the child, where `RemoteBoxScope` has no member for it.
    val children = node.slots["children"].orEmpty().mapNotNull(document.nodes::get)
    val alignments = children.map { it.declaredAlignment() }.distinct()
    when {
      alignments.size > 1 ->
        refusals +=
          "the box `${node.id}` aligns its children differently from one another, which " +
            "RemoteBox aligns as a group"
      alignments.singleOrNull().isNullOrEmpty() -> Unit
      else -> {
        usesAlignment = true
        arguments += "contentAlignment = RemoteAlignment.${alignments.single().remoteAlignment()}"
      }
    }
    return arguments
  }

  private fun columnArguments(node: UiBuilderNode, parent: Parent): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(parent)?.let { arguments += "modifier = $it" }
    node.properties["verticalSpacingDp"]
      ?.numberOrNull()
      ?.takeIf { it != 0f }
      ?.let {
        usesArrangement = true
        arguments += "verticalArrangement = RemoteArrangement.spacedBy(${it.dpLiteral()})"
      }
    return arguments
  }

  private fun rowArguments(node: UiBuilderNode, parent: Parent): List<String> {
    val arguments = mutableListOf<String>()
    node.modifierExpression(parent)?.let { arguments += "modifier = $it" }
    node.properties["horizontalSpacingDp"]
      ?.numberOrNull()
      ?.takeIf { it != 0f }
      ?.let {
        usesArrangement = true
        arguments += "horizontalArrangement = RemoteArrangement.spacedBy(${it.dpLiteral()})"
      }
    return arguments
  }

  /**
   * `Symbol(a, b)` on one line, or one argument per line once that would run long.
   *
   * Generated or not, this is source somebody reads and pastes into a file their formatter will
   * check. A 150-column call is a diff nobody wants on their first commit after using the builder.
   */
  private fun call(
    symbol: String,
    arguments: List<String>,
    pad: String = "",
    trailing: Int = 0,
  ): String {
    val single = "$symbol(${arguments.joinToString(", ")})"
    if (pad.length + single.length + trailing <= MAX_LINE) return single
    val argumentPad = pad + INDENT
    return buildString {
      appendLine("$symbol(")
      // An argument can be too long all by itself once modifiers chain — `RemoteModifier.size(…)
      // .clip(…).background(…)` is three calls on one line — and putting it on its own line has
      // then bought nothing. A chain is the one shape that breaks cleanly, so it is broken.
      arguments.forEach { appendLine("$argumentPad${it.wrappedChain(argumentPad)},") }
      append("$pad)")
    }
  }

  private fun text(node: UiBuilderNode, pad: String = "", parent: Parent = Parent.NONE): String {
    usesMaterialText = true
    val arguments =
      mutableListOf("text = \"${node.properties["text"]?.stringOrNull().orEmpty().escaped()}\".rs")
    node.modifierExpression(parent)?.let { arguments += "modifier = $it" }
    node.properties["color"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let { color ->
        arguments +=
          if (color.startsWith("#")) {
            usesColorLiteral = true
            "color = ${color.argbLiteral()}.rc"
          } else {
            usesTheme = true
            "color = RemoteMaterialTheme.colorScheme.$color"
          }
      }
    node.properties["fontSizeSp"]
      ?.numberOrNull()
      ?.takeIf { it > 0f }
      ?.let { arguments += "fontSize = ${it.spLiteral()}" }
    node.properties["style"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        usesTheme = true
        arguments += "style = RemoteMaterialTheme.typography.$it"
      }
    node.properties["textAlign"]
      ?.stringOrNull()
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        usesTextAlign = true
        arguments += "textAlign = TextAlign.${it.replaceFirstChar(Char::uppercaseChar)}"
      }
    node.properties["maxLines"]?.intOrNull()?.let { arguments += "maxLines = $it" }
    return call("RemoteText", arguments, pad)
  }

  /**
   * `LottieAnimation(json = …)` — Horologist's Lottie **compiler**, called with the animation this
   * element carries.
   *
   * The animation does not travel beside the widget: `LottieAnimation` is a `@RemoteComposable`
   * that parses the JSON while the document is being built and re-emits it as Remote Compose
   * operations, so what reaches the watch is a document that draws the animation and nothing else.
   * That is also why a URL cannot be written here — the generated widget has no network at the
   * moment it needs the bytes, so the builder resolves the URL into `json` at authoring time and
   * this refuses the element that still carries only one.
   *
   * The JSON goes into a top-level constant rather than inline. A minified animation is a few
   * thousand columns on one line; put in the body it buries the design in a file somebody has to
   * read, and put in a constant it sits at the bottom where a reader can skip it.
   */
  private fun lottie(node: UiBuilderNode, pad: String, parent: Parent): String? {
    val url = node.properties["url"]?.stringOrNull().orEmpty()
    val json = node.properties["json"]?.stringOrNull().orEmpty()
    if (json.isBlank()) {
      refusals +=
        if (url.isNotEmpty()) {
          "the Lottie element `${node.id}` carries only its URL (`$url`): a widget is built with " +
            "no network to fetch it from, so the animation's JSON has to be resolved in the " +
            "builder first"
        } else {
          "the Lottie element `${node.id}` has no animation — give it a URL to fetch, or paste " +
            "the animation's JSON in"
        }
      return null
    }
    // Reparsed rather than pasted through, for two reasons: an animation that is not JSON is
    // caught here instead of by the reader's compiler, and the round trip drops whatever
    // indentation the source had — which is most of the bytes of a pretty-printed Lottie, and all
    // of them wasted in a string literal.
    val compact =
      try {
        Json.parseToJsonElement(json).toString()
      } catch (failure: Exception) {
        refusals +=
          "the Lottie element `${node.id}` does not hold valid JSON: ${failure.message ?: "it could not be parsed"}"
        return null
      }
    val literal = compact.escaped()
    // A Kotlin string literal is a JVM constant, and a JVM constant is capped at 65535 *bytes* of
    // modified UTF-8. Past that the generated file does not compile — which the author would
    // discover after pasting it — so it is refused here, with the route that does work.
    if (literal.encodeToByteArray().size > MAX_STRING_CONSTANT_BYTES) {
      refusals +=
        "the Lottie animation on `${node.id}` is ${compact.encodeToByteArray().size / 1024}KiB, past the 64KiB a " +
          "Kotlin string constant holds — put the JSON in `res/raw` and call " +
          "`LottieAnimation(rawRes = R.raw.…)`, which takes the same animation"
      return null
    }
    usesLottie = true
    val constant =
      if (lottieDeclarations.isEmpty()) LOTTIE_CONSTANT
      else "${LOTTIE_CONSTANT}_${lottieDeclarations.size + 1}"
    lottieDeclarations += "private const val $constant = \"$literal\""
    val arguments = mutableListOf("json = $constant")
    node.modifierExpression(parent)?.let { arguments += "modifier = $it" }
    // Absent means "run": `LottieAnimation` drives the frame off the document's own animation
    // clock when it is given no progress. A value pins the animation to one frame, which is what a
    // widget that must not animate wants — so 0f is emitted and an unset property is not.
    node.properties["progress"]?.numberOrNull()?.let {
      usesRemoteFloat = true
      arguments += "progress = ${if (it % 1f == 0f) "${it.toInt()}.rf" else "${it}f.rf"}"
    }
    return call("LottieAnimation", arguments, pad)
  }

  /**
   * Top-level declarations the body refers to, in emission order.
   *
   * Separate from the body because they belong at the *file's* level, not inside the content
   * function: [WearWidgetCodeExporter] appends them after the preview, which is where a reader
   * expects a wall of generated bytes to be rather than in the middle of the design.
   */
  val declarations: List<String>
    get() = lottieDeclarations.toList()

  private val lottieDeclarations = mutableListOf<String>()

  /**
   * The imports the emitted file needs, sorted the way Kotlin style orders them.
   *
   * Gated on what was actually written rather than emitted wholesale: an unused import is a warning
   * in the reader's IDE the moment they paste this in, and "generated" is not a licence to hand
   * someone code they have to tidy.
   */
  fun imports(previewParamsProvider: String): List<String> {
    val imports = mutableSetOf<String>()
    imports += "android.content.Context"
    if (usesBox) imports += "androidx.compose.remote.creation.compose.layout.RemoteBox"
    if (usesColumn) imports += "androidx.compose.remote.creation.compose.layout.RemoteColumn"
    imports += "androidx.compose.remote.creation.compose.layout.RemoteComposable"
    if (usesRow) imports += "androidx.compose.remote.creation.compose.layout.RemoteRow"
    if (usesLottie) imports += "com.google.android.horologist.remotecompose.lottie.LottieAnimation"
    if (usesRemoteFloat) imports += "androidx.compose.remote.creation.compose.state.rf"
    if (usesAlignment) imports += "androidx.compose.remote.creation.compose.layout.RemoteAlignment"
    if (usesArrangement) {
      imports += "androidx.compose.remote.creation.compose.layout.RemoteArrangement"
    }
    if (usesRemoteImage) imports += "androidx.compose.remote.creation.compose.layout.RemoteImage"
    if (usesRemoteImage || usesRemoteImageBitmap) {
      imports += "androidx.compose.remote.creation.compose.state.RemoteImageBitmap"
    }
    if (usesRoundedCornerShape) {
      imports += "androidx.compose.remote.creation.compose.shapes.RemoteRoundedCornerShape"
    }
    if (usesModifier) imports += "androidx.compose.remote.creation.compose.modifier.RemoteModifier"
    usedModifierImports.forEach {
      imports += "androidx.compose.remote.creation.compose.modifier.$it"
    }
    if (usesDp) imports += "androidx.compose.remote.creation.compose.state.rdp"
    if (usesColorLiteral) imports += "androidx.compose.remote.creation.compose.state.rc"
    if (usesMaterialText) imports += "androidx.compose.remote.creation.compose.state.rs"
    if (usesSp) imports += "androidx.compose.remote.creation.compose.state.rsp"
    imports += "androidx.compose.runtime.Composable"
    if (usesColorLiteral) imports += "androidx.compose.ui.graphics.Color"
    if (usesContentScale) imports += "androidx.compose.ui.layout.ContentScale"
    if (usesTextAlign) imports += "androidx.compose.ui.text.style.TextAlign"
    imports += "androidx.compose.ui.tooling.preview.Preview"
    imports += "androidx.compose.ui.tooling.preview.PreviewParameter"
    imports += "androidx.glance.wear.GlanceWearWidget"
    imports += "androidx.glance.wear.WearWidgetBrush"
    imports += "androidx.glance.wear.WearWidgetData"
    imports += "androidx.glance.wear.WearWidgetDocument"
    if (usesBrushColor) imports += "androidx.glance.wear.color"
    if (usesHorizontalGradient) imports += "androidx.glance.wear.horizontalGradient"
    if (usesVerticalGradient) imports += "androidx.glance.wear.verticalGradient"
    imports += "androidx.glance.wear.core.WearWidgetParams"
    imports += "androidx.glance.wear.tooling.preview.$previewParamsProvider"
    imports += "androidx.glance.wear.tooling.preview.WearWidgetPreview"
    if (usesRemoteColorScheme) {
      imports += "androidx.wear.compose.remote.material3.RemoteColorScheme"
    }
    if (usesTheme) imports += "androidx.wear.compose.remote.material3.RemoteMaterialTheme"
    if (usesMaterialText) imports += "androidx.wear.compose.remote.material3.RemoteText"
    return imports.sorted()
  }

  /**
   * A gradient call, recorded so [imports] names the direction it actually wrote.
   *
   * `horizontalGradient` and `verticalGradient` are separate top-level functions, and importing
   * both because a gradient exists hands the reader an unused import on their first paste — the
   * thing every other import here is gated to avoid.
   */
  private fun horizontal(call: String): String = call.also { usesHorizontalGradient = true }

  private fun vertical(call: String): String = call.also { usesVerticalGradient = true }

  private var usesModifier = false
  private var usesLottie = false
  private var usesRemoteFloat = false
  private var usesSp = false
  private var usesHorizontalGradient = false
  private var usesVerticalGradient = false
  private val usedModifierImports = mutableSetOf<String>()

  /**
   * The `RemoteModifier` chain this node's modifiers become, or null for a bare call.
   *
   * [parent] decides the *scope* modifiers that are legal here, because Remote Compose puts them on
   * a scope receiver exactly as Compose does: `weight` is a member of `RemoteRowScope` and
   * `RemoteColumnScope`, so it can only be written inside one, and a `Box` has no `align` member at
   * all — that one is hoisted onto the parent's `contentAlignment` by [boxArguments] and skipped
   * here. Writing either outside its scope produces a file that does not compile, which is the one
   * outcome a generator must never choose over a refusal.
   *
   * One modifier may become more than one call: Compose's `background(color, shape)` is a single
   * modifier, while `RemoteModifier.background` takes no shape — the shape is a separate `clip`
   * before it, which fills the clipped area and so draws what the design asked for.
   */
  private fun UiBuilderNode.modifierExpression(parent: Parent): String? {
    val parts = modifiers.flatMap { element ->
      val modifier = element as? JsonObject ?: return@flatMap emptyList()
      when (val type = modifier["type"]?.stringValue()) {
        "fillMaxSize" -> {
          usedModifierImports += "fillMaxSize"
          listOf("fillMaxSize()")
        }
        "fillMaxWidth" -> {
          usedModifierImports += "fillMaxWidth"
          listOf("fillMaxWidth()")
        }
        "padding" -> {
          usedModifierImports += "padding"
          val start = modifier["startDp"]?.numberValue() ?: 0f
          val top = modifier["topDp"]?.numberValue() ?: 0f
          val end = modifier["endDp"]?.numberValue() ?: 0f
          val bottom = modifier["bottomDp"]?.numberValue() ?: 0f
          listOf(
            "padding(${start.dpLiteral()}, ${top.dpLiteral()}, ${end.dpLiteral()}, ${bottom.dpLiteral()})"
          )
        }
        "size" -> {
          usedModifierImports += "size"
          val width = modifier["widthDp"]?.numberValue()
          val height = modifier["heightDp"]?.numberValue()
          when {
            width == null && height == null -> {
              refusals += "the `size` modifier on `$id` names neither a width nor a height"
              emptyList()
            }
            // `size(RemoteDp)` is the square overload; a design that gave one side only is asking
            // for `width`/`height`, which are their own modifiers rather than a defaulted `size`.
            width == null || height == null -> {
              refusals +=
                "the `size` modifier on `$id` names one side; Remote Compose sizes a single axis " +
                  "with `width` or `height`, which this design does not use"
              emptyList()
            }
            width == height -> listOf("size(${width.dpLiteral()})")
            else -> listOf("size(${width.dpLiteral()}, ${height.dpLiteral()})")
          }
        }
        "background" -> {
          val color = modifier["color"]?.stringOrNull()
          when {
            color == null -> {
              refusals += "the `background` modifier on `$id` names no colour"
              emptyList()
            }
            // The same rule the container background states: this is written outside composition,
            // so a theme token has nothing to read and only a literal can be emitted.
            !color.startsWith("#") -> {
              refusals +=
                "the `background` modifier on `$id` uses the theme token `$color`; a widget is " +
                  "built outside composition, so its colours have to be literals"
              emptyList()
            }
            else -> {
              usesColorLiteral = true
              usedModifierImports += "background"
              val shape = modifier["shape"]?.stringValue()?.takeIf { it.isNotEmpty() }
              val clip = shape?.let { remoteShape(it, id) ?: return@flatMap emptyList() }
              listOfNotNull(clip, "background(${color.argbLiteral()}.rc)")
            }
          }
        }
        // A scope member, so it needs no import — and no counterpart outside its scope.
        "weight" ->
          if (parent == Parent.ROW || parent == Parent.COLUMN) {
            val weight = modifier["weight"]?.numberValue() ?: 1f
            listOf("weight(${weight.floatLiteral()})")
          } else {
            refusals +=
              "the `weight` modifier on `$id` is a row/column scope member, and `$id` is not " +
                "inside a RemoteRow or RemoteColumn"
            emptyList()
          }
        // Hoisted onto the parent by [boxArguments] rather than written here: `RemoteBoxScope` has
        // no `align`, and RemoteBox aligns its children as a group.
        "align" ->
          if (parent == Parent.BOX) emptyList()
          else {
            refusals +=
              "the `align` modifier on `$id` is a box scope member, and `$id` is not inside a " +
                "RemoteBox"
            emptyList()
          }
        null -> emptyList()
        else -> {
          refusals += "the `$type` modifier on `$id` has no RemoteModifier counterpart here"
          emptyList()
        }
      }
    }
    if (parts.isEmpty()) return null
    usesModifier = true
    return parts.joinToString(".", prefix = "RemoteModifier.")
  }

  /**
   * A `clip` call for the shape a `background` or `border` modifier names, or null once refused.
   *
   * A document names a shape either by a corner radius in dp or by one of the theme's named sizes.
   * Only the first is written: a named size resolves against the theme's corner radius, and a
   * widget's document is built where no theme can be read — the same reason its colours must be
   * literals.
   */
  private fun remoteShape(shape: String, nodeId: String): String? {
    val radius = shape.toFloatOrNull()
    if (radius == null) {
      refusals +=
        "the shape `$shape` on `$nodeId` is a theme size; a widget is built outside composition, " +
          "so a shape has to name its corner radius in dp"
      return null
    }
    usedModifierImports += "clip"
    usesRoundedCornerShape = true
    return "clip(RemoteRoundedCornerShape(${radius.dpLiteral()}))"
  }

  /** Every dp literal needs `rdp`, which is why the flag is set here and not per call site. */
  private fun Float.dpLiteral(): String {
    usesDp = true
    return if (this % 1f == 0f) "${toInt()}.rdp" else "${this}f.rdp"
  }

  /** `1.0` reads as `1f`, which is what `weight` takes. */
  private fun Float.floatLiteral(): String = if (this % 1f == 0f) "${toInt()}f" else "${this}f"

  private fun Float.spLiteral(): String =
    if (this % 1f == 0f) "${toInt()}.rsp".also { usesSp = true }
    else "${this}f.rsp".also { usesSp = true }

  private companion object {
    const val INDENT = "    "

    /** ktfmt's own default, so pasted output survives the formatter unchanged. */
    const val MAX_LINE = 100

    /** What [container] appends after a call that takes children. */
    const val OPENING_BRACE = " {"

    /** The first Lottie animation's constant; a second one is suffixed. */
    const val LOTTIE_CONSTANT = "LOTTIE_ANIMATION"

    /** A JVM string constant's cap, in modified-UTF-8 bytes. */
    const val MAX_STRING_CONSTANT_BYTES = 65535
  }
}

/**
 * The alignment a child states, however it states it.
 *
 * The `alignment` property and the `align` modifier are the same intent written two ways — which
 * one a node carries depends on how it was placed in the editor — so a generator that read only the
 * property would drop the other and centre a design that asked for a corner.
 */
private fun UiBuilderNode.declaredAlignment(): String =
  properties["alignment"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
    ?: modifiers
      .asSequence()
      .mapNotNull { it as? JsonObject }
      .firstOrNull {
        it["type"]?.let { type -> (type as? JsonPrimitive)?.contentOrNull } == "align"
      }
      ?.get("alignment")
      ?.let { (it as? JsonPrimitive)?.contentOrNull }
      .orEmpty()

/** The `ContentScale` a document's scale name maps to, or null when it names none this writes. */
private fun String.remoteContentScale(): String? =
  when (this) {
    "crop" -> "ContentScale.Crop"
    "fit" -> "ContentScale.Fit"
    "fillBounds" -> "ContentScale.FillBounds"
    "fillWidth" -> "ContentScale.FillWidth"
    "fillHeight" -> "ContentScale.FillHeight"
    "inside" -> "ContentScale.Inside"
    "none" -> "ContentScale.None"
    else -> null
  }

/**
 * A dotted call chain broken across lines when it does not fit, or the string unchanged.
 *
 * Split points are the dots that follow a closing parenthesis **at the top level** — the boundary
 * between one call in the chain and the next. Both halves of that rule are load-bearing. Following
 * a `)` is what leaves `RemoteModifier.size` and `999.rdp` attached, since those dots follow a
 * letter and a digit. Being at depth zero is what stops the split landing inside an argument:
 * `background(Color(0xFF1DB954).rc)` ends a nested call right before its `.rc`, and breaking there
 * writes a line that is not Kotlin.
 *
 * Nothing here parses Kotlin beyond counting brackets, and nothing needs to: every string this
 * receives was written a few lines above by this same file.
 */
internal fun String.wrappedChain(pad: String): String {
  if (pad.length + length + 1 <= MAX_LINE) return this
  var depth = 0
  var inString = false
  val breaks = mutableListOf<Int>()
  forEachIndexed { index, character ->
    when {
      // A generated string literal never contains an unescaped quote — `escaped()` saw to that —
      // so tracking the toggle is enough to keep brackets inside text out of the count.
      character == '"' -> inString = !inString
      inString -> Unit
      character == '(' -> depth++
      character == ')' -> depth--
      character == '.' && depth == 0 && index > 0 && this[index - 1] == ')' -> breaks += index
    }
  }
  if (breaks.isEmpty()) return this
  val parts = mutableListOf<String>()
  var start = 0
  breaks.forEach {
    parts += substring(start, it)
    start = it
  }
  parts += substring(start)
  return parts.joinToString("\n$pad$INDENT")
}

private const val INDENT = "    "

/** ktfmt's own default, so pasted output survives the formatter unchanged. */
private const val MAX_LINE = 100

private fun String.remoteAlignment(): String =
  when (this) {
    "topCenter" -> "TopCenter"
    "topEnd" -> "TopEnd"
    "centerStart" -> "CenterStart"
    "center" -> "Center"
    "centerEnd" -> "CenterEnd"
    "bottomStart" -> "BottomStart"
    "bottomCenter" -> "BottomCenter"
    "bottomEnd" -> "BottomEnd"
    else -> "TopStart"
  }

/**
 * `#FF2196F3` becomes `Color(0xFF2196F3)`, and `#2196F3` becomes `Color(0xFF2196F3)` too.
 *
 * The opaque default is the whole point. A document may write a colour with or without its alpha
 * pair — both are ordinary CSS-style hex — and `Color(0x2196F3)` is not "blue", it is blue at
 * **zero alpha**: an invisible widget that the canvas, which parses the same string through its own
 * colour reader, draws correctly. Padding here is what keeps the generated file and the preview
 * showing the same design.
 */
private fun String.argbLiteral(): String {
  val digits = removePrefix("#").uppercase()
  return "Color(0x${if (digits.length == 6) "FF$digits" else digits})"
}

/**
 * Escaped for a Kotlin `"…"` literal.
 *
 * `$` is in here because of the Lottie path: a template expansion is not something a text property
 * ever contained by accident, but an animation's JSON is arbitrary text somebody else wrote, and a
 * layer named `$1` would otherwise generate a file that does not compile.
 */
private fun String.escaped(): String =
  replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

private fun kotlinx.serialization.json.JsonElement.stringValue(): String? =
  (this as? JsonPrimitive)?.contentOrNull

private fun kotlinx.serialization.json.JsonElement.numberValue(): Float? =
  (this as? JsonPrimitive)?.floatOrNull

internal fun kotlinx.serialization.json.JsonElement.stringOrNull(): String? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull

internal fun kotlinx.serialization.json.JsonElement.numberOrNull(): Float? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.floatOrNull

internal fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
  (this as? JsonObject)?.get("value")?.jsonPrimitive?.intOrNull
