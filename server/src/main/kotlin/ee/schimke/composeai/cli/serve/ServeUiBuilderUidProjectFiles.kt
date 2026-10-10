@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.production.ProductionUidFiles
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.json.*

/** Preserve the file envelope and unknown fields; editing a design never flattens a .uid file. */
internal object ServeUiBuilderUidProjectFiles {
  fun designs(text: String): List<DesignDocumentV1> {
    require(text.toByteArray().size <= MAX_PROJECT_FILE_BYTES) { "design file too large" }
    val raw = PROJECT_JSON.parseToJsonElement(text).jsonObject
    return when (val schema = raw["schema"]?.jsonPrimitive?.content) {
      "compose-ui-builder-designs/v1" -> {
        val documents = raw.getValue("designs").jsonArray.map { decode(it.jsonObject) }
        require(documents.isNotEmpty() && documents.size <= 100) {
          "invalid design collection size"
        }
        require(documents.map { it.id }.distinct().size == documents.size) { "duplicate design id" }
        require(raw["active"]?.jsonPrimitive?.content in documents.map { it.id }) {
          "unknown active design"
        }
        require(documents.map { it.catalogPin.systemId }.distinct().size == 1) {
          "collection mixes design systems"
        }
        documents
      }
      "compose-ui-builder-production/v1" -> {
        ProductionUidFiles.decode(text)
        listOf(decode(raw.getValue("design").jsonObject))
      }
      "compose-ui-builder-document/v1",
      "compose-ui-builder-document/v1-candidate" -> listOf(decode(raw))
      else -> error("unsupported .uid schema '$schema'")
    }
  }

  private fun decode(raw: JsonObject): DesignDocumentV1 = PROJECT_JSON.decodeFromJsonElement(raw)

  fun write(
    file: ProjectFile,
    documents: Map<String, DesignDocumentV1>,
    navigation: Map<String, String> = file.designs.entries.associate { it.value to it.key },
  ): String {
    require(documents.keys == file.designs.keys) { "a file save must contain every design" }
    val original = PROJECT_JSON.parseToJsonElement(file.original).jsonObject
    fun replace(raw: JsonObject): JsonObject {
      val sourceId = raw.getValue("id").jsonPrimitive.content
      val document = documents.getValue(sourceId)
      require(document.id == file.designs.getValue(sourceId)) { "design identity changed" }
      val encoded =
        JsonObject(
          UID_WRITE_JSON.encodeToJsonElement(
              remapProjectNavigation(document.copy(id = sourceId), navigation, strict = false)
            )
            .jsonObject +
            ("stateVariables" to PROJECT_JSON.encodeToJsonElement(document.stateVariables))
        )
      val nodeKeys =
        descriptorKeys(ee.schimke.composeai.uibuilder.protocol.DesignNodeV1.serializer().descriptor)
      val originalNodes = raw["nodes"]?.jsonObject.orEmpty()
      val nodes =
        encoded.getValue("nodes").jsonObject.mapValues { (id, node) ->
          JsonObject(
            originalNodes[id]?.jsonObject.orEmpty().filterKeys { it !in nodeKeys } + node.jsonObject
          )
        }
      // Remove modelled keys before merging so clearing a default does not restore its old value.
      val keys = descriptorKeys(DesignDocumentV1.serializer().descriptor)
      return JsonObject(raw.filterKeys { it !in keys } + encoded + ("nodes" to JsonObject(nodes)))
    }
    val result =
      when (original["schema"]?.jsonPrimitive?.content) {
        "compose-ui-builder-designs/v1" ->
          JsonObject(
            original +
              ("designs" to
                JsonArray(original.getValue("designs").jsonArray.map { replace(it.jsonObject) }))
          )
        "compose-ui-builder-production/v1" ->
          JsonObject(original + ("design" to replace(original.getValue("design").jsonObject)))
        else -> replace(original)
      }
    val text = PROJECT_JSON.encodeToString(JsonObject.serializer(), result) + "\n"
    designs(text) // Validate the production API and collection before returning publishable bytes.
    if (original["schema"]?.jsonPrimitive?.content == "compose-ui-builder-production/v1") {
      val source = ProductionUidFiles.decode(file.original)
      val current = ProductionUidFiles.decode(text)
      val entry = requireNotNull(source.entryPoint) { "production file has no visual entry point" }
      val design = requireNotNull(current.design)
      require(design.roots == listOf(entry.root) && entry.root in design.nodes) {
        "the production root must remain"
      }
      require(
        (entry.bindings.map { it.nodeId } +
            entry.eventBindings.map { it.nodeId } +
            entry.components.map { it.nodeId })
          .all { it in design.nodes }
      ) {
        "production contract references a removed node"
      }
      require(
        design.stateVariables.isEmpty() && design.nodes.values.all { it.eventBindings.isEmpty() }
      ) {
        "production state and callbacks must come from its declared API"
      }
    }
    return text
  }
}

private val UID_WRITE_JSON =
  Json(PROJECT_JSON) {
    encodeDefaults = false
    explicitNulls = false
  }

private fun descriptorKeys(
  descriptor: kotlinx.serialization.descriptors.SerialDescriptor
): Set<String> = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }.toSet()

internal fun remapProjectNavigation(
  document: DesignDocumentV1,
  mapping: Map<String, String>,
  strict: Boolean = true,
): DesignDocumentV1 =
  PROJECT_JSON.decodeFromJsonElement(
    rewriteProjectNavigation(PROJECT_JSON.encodeToJsonElement(document), mapping, strict)
  )

private fun rewriteProjectNavigation(
  value: JsonElement,
  mapping: Map<String, String>,
  strict: Boolean,
): JsonElement =
  when (value) {
    is JsonArray -> JsonArray(value.map { rewriteProjectNavigation(it, mapping, strict) })
    is JsonObject -> {
      val fields = value.mapValues { rewriteProjectNavigation(it.value, mapping, strict) }
      if (value["type"]?.jsonPrimitive?.contentOrNull == "navigatePage") {
        val target = value.getValue("pageKey").jsonPrimitive.content
        val mapped = mapping[target]
        require(!strict || mapped != null) {
          "navigation target '$target' must uniquely identify a design in this project"
        }
        JsonObject(fields + ("pageKey" to JsonPrimitive(mapped ?: target)))
      } else JsonObject(fields)
    }
    else -> value
  }
