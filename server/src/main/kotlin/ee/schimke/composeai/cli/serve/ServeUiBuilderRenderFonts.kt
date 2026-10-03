package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The family names a design draws in, for [ServeGoogleFonts.warm] before the UI-builder renderer
 * draws it: its environment's `typeface` and every theme host's [ThemeTypefaces] properties — the
 * same names the canvas looks up.
 */
internal object DesignTypefaces {
  fun of(document: DesignDocumentV1): Set<String> = buildSet {
    document.environment.typeface.orEmpty().takeIf(String::isNotBlank)?.let(::add)
    document.nodes.values.forEach { node ->
      ThemeTypefaces.PROPERTIES.forEach { property ->
        (node.properties[property] as? StringValueV1)?.value?.takeIf(String::isNotBlank)?.let(::add)
      }
    }
  }

  /** The same, from the renderer's own JSON document; empty for anything it cannot read. */
  fun ofRendererDocument(encoded: String): Set<String> = runCatching {
    val document = Json.parseToJsonElement(encoded).jsonObject
    buildSet {
      (document["environment"] as? JsonObject)
        ?.get("typeface")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.takeIf(String::isNotBlank)
        ?.let(::add)
      (document["nodes"] as? JsonObject)?.values?.forEach { node ->
        val properties = (node as? JsonObject)?.get("properties") as? JsonObject
        ThemeTypefaces.PROPERTIES.forEach { property ->
          ((properties?.get(property) as? JsonObject)?.get("value"))
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        }
      }
    }
  }
    .getOrDefault(emptySet())
}
