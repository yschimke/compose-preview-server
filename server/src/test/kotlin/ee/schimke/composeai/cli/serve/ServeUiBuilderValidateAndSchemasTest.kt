package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DeleteNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.io.File
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * `ui_builder_validate`, the published document and mutation schemas, and the smaller R3/R4
 * promises of compose-preview-server#1114, against the real server wired as `ServeRunner` wires it.
 *
 * The schemas are held to real documents — one written here, one the service itself hands back
 * after an edit — by a small validator for the keywords the generator emits. It is proved not to be
 * vacuous below: it refuses a misspelt field and a wrong type.
 */
class ServeUiBuilderValidateAndSchemasTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private var running: Running? = null

  @AfterTest
  fun tearDown() {
    running?.close()
  }

  // ---- ui_builder_validate ---------------------------------------------------------------------

  @Test
  fun `validate is advertised only where the host wires a validator, as a read`() {
    assertTrue(ServeUiBuilderMcp.VALIDATE in tools(start()))
    running?.close()
    running = null
    assertFalse(
      ServeUiBuilderMcp.VALIDATE in
        tools(start(withValidator = false, directory = stateDirectory.resolve("bare")))
    )

    val unit = service(recordFile = null, directory = stateDirectory.resolve("unit"))
    val mcp = ServeUiBuilderMcp(unit, validator = { _, _, _ -> emptyList() })
    assertEquals(UiBuilderRouteCapability.READ, mcp.capabilityFor(ServeUiBuilderMcp.VALIDATE))
    assertEquals(null, ServeUiBuilderMcp(unit).capabilityFor(ServeUiBuilderMcp.VALIDATE))
  }

  @Test
  fun `a valid document is valid, and nothing is created`() {
    val server = start()

    val reply = validate(server, """{"document":${documentJson(document())}}""")

    assertEquals(true, reply["valid"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    assertEquals(JsonArray(emptyList()), reply["problems"])
    assertEquals(
      "compose-preview/ui-builder-validation/v1",
      reply["schema"]!!.jsonPrimitive.content,
    )
    // Checking a document is not creating it.
    val listed = envelope(server, ServeUiBuilderMcp.LIST_DESIGNS)
    assertFalse(listed.contains("agent-screen"), listed)
  }

  @Test
  fun `an unknown component is a document problem`() {
    val server = start()
    val broken =
      document()
        .copy(
          nodes =
            document().nodes +
              ("session" to DesignNodeV1(id = "session", componentId = "m3/no-such-component"))
        )

    val reply = validate(server, """{"document":${documentJson(broken)}}""")

    assertEquals(false, reply["valid"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    val problem = reply["problems"]!!.jsonArray.single().jsonObject
    assertEquals(SOURCE_DOCUMENT, problem["source"]!!.jsonPrimitive.content)
    assertEquals(SEVERITY_ERROR, problem["severity"]!!.jsonPrimitive.content)
    assertTrue(problem["message"]!!.jsonPrimitive.content.isNotBlank(), problem.toString())
  }

  @Test
  fun `a document of the wrong shape is a shape problem rather than a tool error`() {
    val server = start()
    val missingRoots =
      Json.parseToJsonElement(documentJson(document())).jsonObject.let { JsonObject(it - "roots") }

    val reply = validate(server, """{"document":$missingRoots}""")

    assertEquals(false, reply["valid"]!!.jsonPrimitive.booleanOrNull)
    val problem = reply["problems"]!!.jsonArray.single().jsonObject
    assertEquals(SOURCE_SHAPE, problem["source"]!!.jsonPrimitive.content)
    assertEquals("invalidDocument", problem["code"]!!.jsonPrimitive.content)
  }

  @Test
  fun `the export gate is checked, and is the problems panel's list`() {
    // No component record: the document is well-formed and the catalog accepts it, but the
    // Compose export cannot prove a single call site — which only the export gate knows.
    val server = start(recordFile = null)

    val reply = validate(server, """{"document":${documentJson(document())}}""")

    assertEquals(false, reply["valid"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    val problems = reply["problems"]!!.jsonArray.map { it.jsonObject }
    assertTrue(problems.isNotEmpty())
    assertTrue(problems.all { it["source"]!!.jsonPrimitive.content == SOURCE_EXPORT }, "$problems")
  }

  @Test
  fun `operations are checked against a stored design without moving it`() {
    val server = start()
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"agent-screen","document":${documentJson(document())}}""",
    )
    val before = revisionOf(server)

    val good =
      operations(
        InsertNodeMutationV1(
          node =
            DesignNodeV1(
              id = "subtitle",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Two sessions today")),
            ),
          location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
        )
      )
    val accepted =
      validate(
        server,
        """{"designId":"agent-screen","baseRevision":$before,"operations":$good}""",
      )
    assertEquals(true, accepted["valid"]!!.jsonPrimitive.booleanOrNull, accepted.toString())
    assertEquals(before, accepted["revision"]!!.jsonPrimitive.longOrNull)
    assertEquals("agent-screen", accepted["designId"]!!.jsonPrimitive.content)

    val bad = operations(DeleteNodeMutationV1(nodeId = "no-such-node"))
    val refused = validate(server, """{"designId":"agent-screen","operations":$bad}""")
    assertEquals(false, refused["valid"]!!.jsonPrimitive.booleanOrNull, refused.toString())
    val problem = refused["problems"]!!.jsonArray.single().jsonObject
    assertEquals(SOURCE_MUTATIONS, problem["source"]!!.jsonPrimitive.content)

    val misshapen =
      validate(server, """{"designId":"agent-screen","operations":[{"type":"nope"}]}""")
    assertEquals(false, misshapen["valid"]!!.jsonPrimitive.booleanOrNull)
    val shape = misshapen["problems"]!!.jsonArray.single().jsonObject
    assertEquals(SOURCE_SHAPE, shape["source"]!!.jsonPrimitive.content)
    assertEquals(0L, shape["operationIndex"]!!.jsonPrimitive.longOrNull)

    // The design is exactly as it was: no revision moved, and the subtitle was never inserted.
    assertEquals(before, revisionOf(server))
    val read = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")
    assertFalse(read.contains("Two sessions today"), read)
  }

  @Test
  fun `a stale baseRevision is a warning, not an error`() {
    val server = start()
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"agent-screen","document":${documentJson(document())}}""",
    )
    val edit =
      operations(SetPropertyMutationV1("session", "text", StringValueV1("Closing keynote")))

    val reply =
      validate(server, """{"designId":"agent-screen","baseRevision":99,"operations":$edit}""")

    assertEquals(true, reply["valid"]!!.jsonPrimitive.booleanOrNull, reply.toString())
    val warning = reply["problems"]!!.jsonArray.single().jsonObject
    assertEquals(SEVERITY_WARNING, warning["severity"]!!.jsonPrimitive.content)
    assertEquals("revisionMismatch", warning["code"]!!.jsonPrimitive.content)
  }

  @Test
  fun `validate refuses a design the caller cannot read and an ambiguous call`() {
    val server = start()
    val missing = call(server, ServeUiBuilderMcp.VALIDATE, """{"designId":"nobody-here"}""")
    assertEquals("true", missing["isError"]?.jsonPrimitive?.content)

    val both =
      call(
        server,
        ServeUiBuilderMcp.VALIDATE,
        """{"designId":"x","document":${documentJson(document())}}""",
      )
    assertEquals("true", both["isError"]?.jsonPrimitive?.content)
  }

  // ---- the schemas -----------------------------------------------------------------------------

  @Test
  fun `validate declares its reply as a closed output schema`() {
    val server = start()
    val schema = outputSchema(server, ServeUiBuilderMcp.VALIDATE)
    val reply = validate(server, """{"document":${documentJson(document())}}""")

    assertEquals(
      UI_BUILDER_VALIDATION_SCHEMA,
      schema["properties"]!!.jsonObject["schema"]!!.jsonObject["const"]!!.jsonPrimitive.content,
    )
    assertTrue(
      schema["required"]!!
        .jsonArray
        .map { it.jsonPrimitive.content }
        .containsAll(listOf("schema", "valid", "problems")),
      schema.toString(),
    )
    // Self-contained: a host's validator need not know the 2020-12 dialect or resolve `$defs`.
    assertFalse(schema.toString().contains("\$ref"), schema.toString())
    assertFalse("\$schema" in schema, schema.toString())
    // Not vacuous: a stray field, a wrong type and another reply's schema id are all refused.
    val check = SchemaCheck(schema)
    assertFalse(check.errors(JsonObject(reply + ("stray" to JsonPrimitive(1)))).isEmpty())
    assertFalse(check.errors(JsonObject(reply + ("valid" to JsonPrimitive("yes")))).isEmpty())
    assertFalse(
      check
        .errors(JsonObject(reply + ("schema" to JsonPrimitive(UI_BUILDER_VIEW_SCHEMA))))
        .isEmpty()
    )
  }

  @Test
  fun `the schemas are MCP resources beside the viewer and readable without a grant`() {
    val server = start()

    val listed =
      post(server, """{"jsonrpc":"2.0","id":1,"method":"resources/list"}""")["result"]!!
        .jsonObject["resources"]!!
        .jsonArray
        .map { it.jsonObject["uri"]!!.jsonPrimitive.content }
    assertTrue(UiBuilderJsonSchemas.DOCUMENT_URI in listed, "$listed")
    assertTrue(UiBuilderJsonSchemas.MUTATION_URI in listed, "$listed")

    for (uri in listOf(UiBuilderJsonSchemas.DOCUMENT_URI, UiBuilderJsonSchemas.MUTATION_URI)) {
      val read = """{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"$uri"}}"""
      assertFalse(ServeCatalogMcp.requiresGrant(Json.parseToJsonElement(read).jsonObject), uri)
      val contents =
        post(server, read)["result"]!!.jsonObject["contents"]!!.jsonArray.single().jsonObject
      assertEquals(UiBuilderJsonSchemas.MEDIA_TYPE, contents["mimeType"]!!.jsonPrimitive.content)
      val schema = Json.parseToJsonElement(contents["text"]!!.jsonPrimitive.content).jsonObject
      assertEquals(uri, schema["\$id"]!!.jsonPrimitive.content)
    }
  }

  @Test
  fun `the schemas are served over plain HTTP with the same bytes`() {
    val server = start()
    for (served in UiBuilderJsonSchemas.served) {
      client
        .newCall(
          Request.Builder()
            .url(
              "http://127.0.0.1:${server.server.port}${UiBuilderJsonSchemas.HTTP_PREFIX}${served.name}"
            )
            .build()
        )
        .execute()
        .use {
          assertEquals(200, it.code)
          assertTrue(it.header("Content-Type")!!.startsWith(UiBuilderJsonSchemas.MEDIA_TYPE))
          assertEquals(served.text, it.body.string())
        }
    }
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}${UiBuilderJsonSchemas.HTTP_PREFIX}nope.json")
          .build()
      )
      .execute()
      .use { assertEquals(404, it.code) }
  }

  @Test
  fun `real documents and mutations validate against the published schemas`() {
    val server = start()
    val documentSchema = schema(UiBuilderJsonSchemas.DOCUMENT_NAME)
    val mutationSchema = schema(UiBuilderJsonSchemas.MUTATION_NAME)

    // One written here, and one the service itself hands back after an edit — with a server home
    // on it, which exercises the `kind`-discriminated DesignHomeV1.
    val written = Json.parseToJsonElement(documentJson(document()))
    assertEquals(emptyList(), SchemaCheck(documentSchema).errors(written))

    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"agent-screen","document":${documentJson(document())}}""",
    )
    val insert =
      InsertNodeMutationV1(
        node =
          DesignNodeV1(
            id = "subtitle",
            componentId = "m3/text",
            properties = mapOf("text" to StringValueV1("Two sessions today")),
          ),
        location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
      )
    val encoded = Json.parseToJsonElement(operations(insert)).jsonArray.single()
    assertEquals(emptyList(), SchemaCheck(mutationSchema).errors(encoded))
    envelope(
      server,
      ServeUiBuilderMcp.APPLY,
      """{"designId":"agent-screen","operationId":"op-1","baseRevision":${revisionOf(server)},"operations":[${encoded}]}""",
    )
    val stored = storedDocument(server)
    assertNotNull(stored["home"], "the stored document carries its server home")
    assertEquals("server", stored["home"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
    assertEquals(emptyList(), SchemaCheck(documentSchema).errors(stored))

    // And the check is not vacuous: a misspelt field, a wrong type and a wrong discriminator fail.
    val misspelt = JsonObject(stored + ("titel" to JsonPrimitive("x")))
    assertTrue(SchemaCheck(documentSchema).errors(misspelt).isNotEmpty())
    val wrongType = JsonObject(stored + ("revision" to JsonPrimitive("two")))
    assertTrue(SchemaCheck(documentSchema).errors(wrongType).isNotEmpty())
    val wrongHome =
      JsonObject(stored + ("home" to JsonObject(mapOf("kind" to JsonPrimitive("elsewhere")))))
    assertTrue(SchemaCheck(documentSchema).errors(wrongHome).isNotEmpty())
    val unknownMutation = JsonObject(mapOf("type" to JsonPrimitive("teleportNode")))
    assertTrue(SchemaCheck(mutationSchema).errors(unknownMutation).isNotEmpty())
  }

  // ---- R3/R4 ------------------------------------------------------------------------------------

  @Test
  fun `set_links says a thread supplements the server's comments and does not replace them`() {
    val declarations =
      ServeUiBuilderMcp.declarations(
        { name, description, schema ->
          JsonObject(
            mapOf(
              "name" to JsonPrimitive(name),
              "description" to JsonPrimitive(description),
              "inputSchema" to Json.parseToJsonElement(schema),
            )
          )
        },
        links = true,
      )
    val setLinks = declarations.single {
      it["name"]!!.jsonPrimitive.content == ServeUiBuilderMcp.SET_LINKS
    }
    val thread =
      setLinks["inputSchema"]!!
        .jsonObject["properties"]!!
        .jsonObject["thread"]!!
        .jsonObject["description"]!!
        .jsonPrimitive
        .content
    assertTrue(thread.contains("discussion held elsewhere"), thread)
    assertTrue(thread.contains("does not replace this server's comments"), thread)
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun schema(name: String): JsonObject =
    Json.parseToJsonElement(UiBuilderJsonSchemas.byName(name)!!.text).jsonObject

  private fun validate(server: Running, arguments: String): JsonObject {
    val result = call(server, ServeUiBuilderMcp.VALIDATE, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(null, result["isError"], text)
    val reply = Json.parseToJsonElement(text).jsonObject
    // The typed reply is the same object, and it satisfies the schema the tool declares.
    assertEquals(reply, result["structuredContent"], text)
    val errors = SchemaCheck(outputSchema(server, ServeUiBuilderMcp.VALIDATE)).errors(reply)
    assertTrue(errors.isEmpty(), "$errors in $reply")
    return reply
  }

  private fun outputSchema(server: Running, tool: String): JsonObject =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject }
      .single { it["name"]!!.jsonPrimitive.content == tool }["outputSchema"]!!
      .jsonObject

  private fun storedDocument(server: Running): JsonObject =
    Json.parseToJsonElement(
        envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")
      )
      .jsonObject["response"]!!
      .jsonObject["snapshot"]!!
      .jsonObject["state"]!!
      .jsonObject["document"]!!
      .jsonObject

  private fun revisionOf(server: Running): Long =
    storedDocument(server)["revision"]!!.jsonPrimitive.longOrNull!!

  private fun operations(vararg mutations: DesignMutationV1): String =
    json.encodeToString(ListSerializer(DesignMutationV1.serializer()), mutations.toList())

  private fun documentJson(document: DesignDocumentV1): String =
    json.encodeToString(DesignDocumentV1.serializer(), document)

  private fun call(server: Running, tool: String, arguments: String = "{}") =
    post(
        server,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
      )["result"]!!
      .jsonObject

  private fun envelope(server: Running, tool: String, arguments: String = "{}"): String {
    val result = call(server, tool, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(null, result["isError"], text)
    return text
  }

  private fun tools(server: Running): List<String> =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }

  private fun post(server: Running, body: String): JsonObject =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}/mcp")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(body.toRequestBody("application/json".toMediaType()))
          .build()
      )
      .execute()
      .use {
        val text = it.body.string()
        assertEquals(200, it.code, text)
        Json.parseToJsonElement(text).jsonObject
      }

  private fun catalogs() =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = setOf(CATALOG_SYSTEM_ID),
      exportCapabilities =
        ExportCapabilitiesV1.Builder()
          .also {
            it.composeCode = true
            it.svg = false
            it.png = false
          }
          .build(),
    )

  private fun exporter(recordFile: File?) =
    ScreenGeneratorComposeExportExecutor(
      ComponentRecordSource(recordFile?.let { mapOf(CATALOG_SYSTEM_ID to it) }.orEmpty())::record
    )

  private fun service(recordFile: File?, directory: Path = stateDirectory) =
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(directory),
      catalogs = catalogs(),
      exporter = exporter(recordFile),
    )

  private fun start(
    withValidator: Boolean = true,
    recordFile: File? = ScreenGeneratorScreenFixture.componentsFile(),
    directory: Path = stateDirectory,
  ): Running {
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          canonicalOrigin = PUBLIC_ORIGIN,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service(recordFile, directory),
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
          // The same catalogs and exporter the service has, exactly as ServeRunner wires it.
          uiBuilderValidator =
            if (withValidator) ScratchUiBuilderDraftValidator(catalogs(), exporter(recordFile))
            else null,
        )
        .also(ServeHttpServer::start)
    return Running(server, registry).also { running = it }
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "agent-screen",
      title = "Agent screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG_SYSTEM_ID, "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("column"),
      nodes =
        mapOf(
          "column" to
            DesignNodeV1(
              id = "column",
              componentId = "layout/column",
              slots = mapOf("children" to listOf("session")),
            ),
          "session" to
            DesignNodeV1(
              id = "session",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Opening keynote")),
            ),
        ),
    )

  private data class Running(val server: ServeHttpServer, val registry: ServeSessionRegistry) :
    AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    const val OPERATOR_TOKEN = "ui-builder-validate-operator-token"
    const val PUBLIC_ORIGIN = "https://designs.example"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
  }
}

/**
 * The JSON Schema keywords [SerialDescriptorJsonSchema] emits, checked: `$ref` into `$defs`,
 * `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`, `oneOf` and
 * `anyOf`. Deliberately small — the schema's own generator is the thing under test, and a keyword
 * this does not know fails the check rather than passing silently.
 */
internal class SchemaCheck(private val root: JsonObject) {
  private val known =
    setOf(
      "\$schema",
      "\$id",
      "title",
      "description",
      "\$defs",
      "\$ref",
      "type",
      "properties",
      "required",
      "additionalProperties",
      "items",
      "enum",
      "const",
      "oneOf",
      "anyOf",
    )

  fun errors(value: JsonElement): List<String> = check(root, value, "$")

  private fun check(schema: JsonObject, value: JsonElement, path: String): List<String> {
    val unknown = schema.keys - known
    if (unknown.isNotEmpty()) return listOf("$path: schema uses unchecked keywords $unknown")
    schema["\$ref"]?.let { ref ->
      val key = ref.jsonPrimitive.content.removePrefix("#/\$defs/")
      val target =
        root["\$defs"]?.jsonObject?.get(key)?.jsonObject ?: return listOf("$path: dangling $ref")
      return check(target, value, path)
    }
    val errors = mutableListOf<String>()
    schema["type"]?.let { type ->
      val allowed =
        (type as? JsonArray)?.map { it.jsonPrimitive.content } ?: listOf(type.jsonPrimitive.content)
      if (allowed.none { typeMatches(it, value) }) return listOf("$path: not ${allowed}: $value")
    }
    schema["const"]?.let { if (it != value) errors += "$path: expected const $it, got $value" }
    schema["enum"]?.let { if (value !in it.jsonArray) errors += "$path: $value not in $it" }
    schema["oneOf"]?.let { variants ->
      val matching = variants.jsonArray.count { check(it.jsonObject, value, path).isEmpty() }
      if (matching != 1) errors += "$path: matches $matching oneOf variants, not exactly one"
    }
    schema["anyOf"]?.let { variants ->
      if (variants.jsonArray.none { check(it.jsonObject, value, path).isEmpty() }) {
        errors += "$path: matches no anyOf variant"
      }
    }
    if (value is JsonObject) {
      val properties = schema["properties"]?.jsonObject.orEmpty()
      schema["required"]?.jsonArray?.forEach { required ->
        if (required.jsonPrimitive.content !in value) errors += "$path: missing $required"
      }
      value.forEach { (key, child) ->
        val declared = properties[key]?.jsonObject
        when {
          declared != null -> errors += check(declared, child, "$path.$key")
          schema["additionalProperties"] is JsonObject ->
            errors += check(schema["additionalProperties"]!!.jsonObject, child, "$path.$key")
          schema["additionalProperties"]?.jsonPrimitive?.booleanOrNull == false ->
            errors += "$path: unexpected property $key"
        }
      }
    }
    if (value is JsonArray) {
      schema["items"]?.jsonObject?.let { items ->
        value.forEachIndexed { index, child -> errors += check(items, child, "$path[$index]") }
      }
    }
    return errors
  }

  private fun typeMatches(type: String, value: JsonElement): Boolean =
    when (type) {
      "object" -> value is JsonObject
      "array" -> value is JsonArray
      "null" -> value is JsonNull
      "string" -> value is JsonPrimitive && value.isString
      "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
      "integer" -> value is JsonPrimitive && !value.isString && value.longOrNull != null
      "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
      else -> false
    }
}
