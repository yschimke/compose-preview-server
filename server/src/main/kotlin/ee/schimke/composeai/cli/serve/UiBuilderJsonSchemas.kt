package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The UI-builder document and mutation shapes, as JSON Schema, for agents that author them.
 *
 * ## Why generated rather than written
 *
 * The released `ui-builder-protocol` jar ships no schema and the builder exposes no generator, so
 * an agent writing a `DesignDocumentV1` or a `DesignMutationV1` by hand learned the shape from a
 * refusal — one field per round trip. A hand-authored schema kept in this repository would fix that
 * and then drift at the next catalog-ref bump, silently, because nothing here would notice a field
 * the protocol added. These are generated from the released `kotlinx.serialization` descriptors
 * instead, so the schema is the contract this server actually decodes with, by construction,
 * whichever protocol release the catalog pins.
 *
 * What a descriptor cannot say is not claimed: there are no descriptions, no value ranges and no
 * catalog semantics here. A document can match this schema and still be refused for naming a
 * component the pinned catalog does not declare — that is what `ui_builder_validate` answers.
 *
 * Served as MCP resources ([DOCUMENT_URI], [MUTATION_URI]) beside the viewer, and over plain HTTP
 * at [HTTP_PREFIX]`<name>`, so a tool that is not an MCP client can fetch the same bytes.
 */
internal object UiBuilderJsonSchemas {

  const val DOCUMENT_NAME = "ui-builder-document-v1.json"
  const val MUTATION_NAME = "design-mutation-v1.json"

  const val URI_PREFIX = "compose-preview://schemas/"
  const val DOCUMENT_URI = URI_PREFIX + DOCUMENT_NAME
  const val MUTATION_URI = URI_PREFIX + MUTATION_NAME

  /** The HTTP route prefix; `GET /schemas/<name>` serves the same bytes as the resource. */
  const val HTTP_PREFIX = "/schemas/"

  /** `application/schema+json`, the media type JSON Schema registers for itself. */
  const val MEDIA_TYPE = "application/schema+json"

  /** One served schema: its resource URI, a label for `resources/list`, and its text. */
  class Served(val name: String, val uri: String, val title: String, val description: String) {
    val text: String by lazy { schemaFor(name).toString() }
  }

  val served: List<Served> =
    listOf(
      Served(
        DOCUMENT_NAME,
        DOCUMENT_URI,
        "UI-builder design document (DesignDocumentV1)",
        "JSON Schema for the whole design document `ui_builder_get_design` returns and " +
          "`ui_builder_create_design`, `ui_builder_replace_design_document` and " +
          "`ui_builder_validate` take. Generated from the released protocol descriptors.",
      ),
      Served(
        MUTATION_NAME,
        MUTATION_URI,
        "UI-builder design mutation (DesignMutationV1)",
        "JSON Schema for one element of `ui_builder_apply`'s `operations` array, discriminated by " +
          "`type`. Generated from the released protocol descriptors.",
      ),
    )

  fun byName(name: String): Served? = served.firstOrNull { it.name == name }

  fun byUri(uri: String): Served? = served.firstOrNull { it.uri == uri }

  private fun schemaFor(name: String): JsonObject =
    when (name) {
      DOCUMENT_NAME ->
        SerialDescriptorJsonSchema.document(
          DesignDocumentV1.serializer().descriptor,
          id = DOCUMENT_URI,
          title = "DesignDocumentV1",
          description =
            "A UI-builder design document, as ui-builder-protocol serialises it. Generated from " +
              "the released kotlinx.serialization descriptors; unknown fields are refused, as the " +
              "server's decoder refuses them. Catalog semantics (which components and properties " +
              "exist) are not expressible here: ask ui_builder_validate.",
        )
      MUTATION_NAME ->
        SerialDescriptorJsonSchema.document(
          DesignMutationV1.serializer().descriptor,
          id = MUTATION_URI,
          title = "DesignMutationV1",
          description =
            "One UI-builder design mutation, discriminated by `type`; ui_builder_apply takes an " +
              "array of them as `operations`. Generated from the released kotlinx.serialization " +
              "descriptors.",
        )
      else -> error("no UI-builder schema named $name")
    }
}

/**
 * A JSON Schema (2020-12) for a `kotlinx.serialization` descriptor.
 *
 * Covers what the UI-builder protocol uses and nothing speculative: classes and objects, lists,
 * string-keyed maps, enums by serial name, primitives, value classes, `JsonElement` fields as "any
 * JSON", and sealed hierarchies as a `oneOf` discriminated by the class discriminator — `type`
 * unless the sealed class names another with [JsonClassDiscriminator], as `DesignHomeV1` does with
 * `kind`.
 *
 * Named types are shared through `$defs`, and every object is closed with `additionalProperties:
 * false`, because that is what the server's decoder (`ignoreUnknownKeys = false`) enforces: a
 * misspelt field is refused there, so the schema says so here.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object SerialDescriptorJsonSchema {

  private const val DIALECT = "https://json-schema.org/draft/2020-12/schema"

  fun document(
    root: SerialDescriptor,
    id: String,
    title: String,
    description: String,
  ): JsonObject {
    val builder = Builder()
    val rootSchema = builder.schemaOf(root)
    return JsonObject(
      buildMap {
        put("\$schema", JsonPrimitive(DIALECT))
        put("\$id", JsonPrimitive(id))
        put("title", JsonPrimitive(title))
        put("description", JsonPrimitive(description))
        putAll(rootSchema)
        put("\$defs", JsonObject(builder.defs))
      }
    )
  }

  private class Builder {
    val defs = linkedMapOf<String, JsonElement>()
    private val keyBySerialName = mutableMapOf<String, String>()
    private val inProgress = mutableSetOf<String>()

    fun schemaOf(descriptor: SerialDescriptor): JsonObject {
      val base = nonNullSchemaOf(descriptor)
      return if (descriptor.isNullable)
        obj("anyOf" to JsonArray(listOf(base, obj("type" to str("null")))))
      else base
    }

    private fun nonNullSchemaOf(descriptor: SerialDescriptor): JsonObject {
      val name = descriptor.serialName.removeSuffix("?")
      if (name.startsWith("kotlinx.serialization.json.")) return jsonElementSchema(name)
      if (descriptor.isInline) return schemaOf(descriptor.getElementDescriptor(0))
      return when (val kind = descriptor.kind) {
        PrimitiveKind.STRING,
        PrimitiveKind.CHAR -> obj("type" to str("string"))
        PrimitiveKind.BOOLEAN -> obj("type" to str("boolean"))
        PrimitiveKind.BYTE,
        PrimitiveKind.SHORT,
        PrimitiveKind.INT,
        PrimitiveKind.LONG -> obj("type" to str("integer"))
        PrimitiveKind.FLOAT,
        PrimitiveKind.DOUBLE -> obj("type" to str("number"))
        SerialKind.ENUM ->
          obj(
            "type" to str("string"),
            "enum" to JsonArray(descriptor.elementNames.map(::str)),
          )
        StructureKind.LIST ->
          obj("type" to str("array"), "items" to schemaOf(descriptor.getElementDescriptor(0)))
        // Every map in the protocol is keyed by a string id; JSON objects cannot say otherwise.
        StructureKind.MAP ->
          obj(
            "type" to str("object"),
            "additionalProperties" to schemaOf(descriptor.getElementDescriptor(1)),
          )
        StructureKind.CLASS,
        StructureKind.OBJECT -> named(name) { classSchema(descriptor, discriminator = null) }
        PolymorphicKind.SEALED -> named(name) { sealedSchema(descriptor) }
        PolymorphicKind.OPEN -> obj("type" to str("object"))
        SerialKind.CONTEXTUAL -> obj()
        else -> error("no JSON Schema mapping for descriptor kind $kind ($name)")
      }
    }

    /**
     * A named type, as a `$ref` into `$defs`. A type that refers back to itself gets the reference
     * while its own definition is still being built, rather than looping.
     */
    private fun named(serialName: String, build: () -> JsonObject): JsonObject {
      val key = keyFor(serialName)
      if (key !in defs && inProgress.add(key)) {
        try {
          defs[key] = build()
        } finally {
          inProgress.remove(key)
        }
      }
      return obj("\$ref" to str("#/\$defs/$key"))
    }

    /** A readable, unique `$defs` key: the simple name, disambiguated only if two collide. */
    private fun keyFor(serialName: String): String =
      keyBySerialName.getOrPut(serialName) {
        val base = serialName.substringAfterLast('.').replace(Regex("[^A-Za-z0-9_]"), "_")
        var candidate = base
        var suffix = 2
        while (candidate in keyBySerialName.values) candidate = "${base}_${suffix++}"
        candidate
      }

    private fun classSchema(
      descriptor: SerialDescriptor,
      discriminator: Pair<String, String>?,
    ): JsonObject {
      val properties = linkedMapOf<String, JsonElement>()
      val required = mutableListOf<String>()
      discriminator?.let { (key, value) ->
        properties[key] = obj("const" to str(value))
        required += key
      }
      for (index in 0 until descriptor.elementsCount) {
        val element = descriptor.getElementName(index)
        properties[element] = schemaOf(descriptor.getElementDescriptor(index))
        if (!descriptor.isElementOptional(index)) required += element
      }
      return JsonObject(
        buildMap {
          put("type", str("object"))
          put("properties", JsonObject(properties))
          if (required.isNotEmpty()) put("required", JsonArray(required.map(::str)))
          put("additionalProperties", JsonPrimitive(false))
        }
      )
    }

    private fun sealedSchema(descriptor: SerialDescriptor): JsonObject {
      val key =
        descriptor.annotations
          .filterIsInstance<JsonClassDiscriminator>()
          .firstOrNull()
          ?.discriminator ?: DEFAULT_DISCRIMINATOR
      val variants =
        descriptor.getElementDescriptor(1).elementDescriptors.map { variant ->
          when (variant.kind) {
            StructureKind.CLASS,
            StructureKind.OBJECT -> classSchema(variant, discriminator = key to variant.serialName)
            else -> schemaOf(variant)
          }
        }
      return obj("oneOf" to JsonArray(variants))
    }

    private fun jsonElementSchema(name: String): JsonObject =
      when (name.substringAfterLast('.')) {
        "JsonObject" -> obj("type" to str("object"))
        "JsonArray" -> obj("type" to str("array"))
        "JsonPrimitive",
        "JsonLiteral" ->
          obj("type" to JsonArray(listOf(str("string"), str("number"), str("boolean"))))
        "JsonNull" -> obj("type" to str("null"))
        else -> obj()
      }
  }

  /** The discriminator kotlinx.serialization writes when a sealed class does not name one. */
  private const val DEFAULT_DISCRIMINATOR = "type"

  private fun str(value: String) = JsonPrimitive(value)

  private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries))
}
