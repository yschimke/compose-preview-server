package ee.schimke.composeai.uibuilder

/**
 * Deterministic, deliberately conservative exporter for the first supported native screen slice.
 */
object ComposeCodeExporter {
  fun export(document: UiBuilderDocument): String {
    require(document.roots.size == 1) { "code export currently requires one root" }
    val root = document.nodes.getValue(document.roots.single())
    require(root.componentId == "layout/scaffold") { "code export currently requires a Scaffold" }
    val title = root.slot(document, "topBar").single().slot(document, "title").single().text()
    val row = root.slot(document, "content").single().slot(document, "children").single()
    val chips = row.slot(document, "items")

    return buildString {
      appendLine("@Composable")
      appendLine("fun ConfettiScheduleHeader() {")
      appendLine("  var selectedTrack by remember { mutableStateOf<String?>(null) }")
      appendLine("  Scaffold(")
      appendLine("    topBar = {")
      appendLine("      CenterAlignedTopAppBar(title = { Text(\"${title.escape()}\") })")
      appendLine("    },")
      appendLine("  ) { contentPadding ->")
      appendLine("    Column(Modifier.padding(contentPadding).fillMaxSize()) {")
      appendLine("      LazyRow(")
      appendLine("        horizontalArrangement = Arrangement.spacedBy(8.dp),")
      appendLine("        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),")
      appendLine("      ) {")
      chips.forEach { chip ->
        val value = chip.selectionValue()
        val condition =
          value?.let { "selectedTrack == \"${it.escape()}\"" } ?: "selectedTrack == null"
        val assignment = value?.let { "if ($condition) null else \"${it.escape()}\"" } ?: "null"
        appendLine("        item {")
        appendLine("          FilterChip(")
        appendLine("            selected = $condition,")
        appendLine("            onClick = { selectedTrack = $assignment },")
        appendLine(
          "            label = { Text(\"${chip.slot(document, "label").single().text().escape()}\") },"
        )
        val leading = chip.slot(document, "leadingIcon")
        if (leading.isNotEmpty()) {
          val dot = leading.single()
          appendLine("            leadingIcon = {")
          appendLine(
            "              Box(Modifier.size(8.dp).background(Color(${dot.colorLiteral()})))"
          )
          appendLine("            },")
        }
        appendLine("          )")
        appendLine("        }")
      }
      appendLine("      }")
      appendLine("    }")
      appendLine("  }")
      appendLine("}")
    }
      .trimEnd() + "\n"
  }
}

private fun UiBuilderNode.slot(document: UiBuilderDocument, name: String): List<UiBuilderNode> =
  slots[name].orEmpty().map(document.nodes::getValue)

private fun UiBuilderNode.text(): String = propertyValue("text")

private fun UiBuilderNode.selectionValue(): String? =
  properties["selected"]?.jsonObject?.get("value")?.let {
    if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content
  }

private fun UiBuilderNode.colorLiteral(): String = "0x" + propertyValue("color").removePrefix("#")

private fun UiBuilderNode.propertyValue(name: String): String =
  properties.getValue(name).jsonObject.getValue("value").jsonPrimitive.content

private fun String.escape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private val kotlinx.serialization.json.JsonElement.jsonObject
  get() = this as kotlinx.serialization.json.JsonObject

private val kotlinx.serialization.json.JsonElement.jsonPrimitive
  get() = this as kotlinx.serialization.json.JsonPrimitive
