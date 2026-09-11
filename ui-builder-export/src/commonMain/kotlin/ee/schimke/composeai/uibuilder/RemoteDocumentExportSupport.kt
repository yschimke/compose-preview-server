package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * The optional Remote document formats in the locally staged contracts revision.
 *
 * Keep the released contracts floor usable during the prototype. This probes the actual enum and
 * uses its strict serializer. A build against older contracts does not advertise the new formats.
 * Once the contracts release is pinned, replace this bridge with direct typed field access.
 */
object RemoteDocumentExportSupport {
  val jsonFormat: ExportFormatV1? = ExportFormatV1.entries.firstOrNull { it.name == "JSON" }
  val documentFormat: ExportFormatV1? = ExportFormatV1.entries.firstOrNull { it.name == "RC" }
  val formats: List<ExportFormatV1> = listOfNotNull(jsonFormat, documentFormat)

  fun supports(capabilities: ExportCapabilitiesV1, format: ExportFormatV1): Boolean =
    when (format) {
      jsonFormat -> flag(capabilities, "remoteJson")
      documentFormat -> flag(capabilities, "remoteDocument")
      else -> false
    }

  fun capabilities(
    base: ExportCapabilitiesV1,
    json: Boolean,
    document: Boolean,
  ): ExportCapabilitiesV1 {
    if (jsonFormat == null || documentFormat == null) return base
    return Json.decodeFromJsonElement(
      JsonObject(
        Json.encodeToJsonElement(base).jsonObject +
          mapOf("remoteJson" to JsonPrimitive(json), "remoteDocument" to JsonPrimitive(document))
      )
    )
  }

  private fun flag(capabilities: ExportCapabilitiesV1, name: String): Boolean =
    (Json.encodeToJsonElement(capabilities).jsonObject[name] as? JsonPrimitive)?.booleanOrNull ==
      true
}
