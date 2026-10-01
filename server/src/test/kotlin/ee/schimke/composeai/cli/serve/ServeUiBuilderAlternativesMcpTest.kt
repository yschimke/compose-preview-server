package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * `ui_builder_compare_branches` and `ui_builder_pick_branch` (phase 3 of
 * yschimke/compose-ui-builder#375): one picture plus one summary per alternative, and the three
 * ways of asking — OpenAI's thumbnail picker, a plain MCP form, and a numbered list for chat — each
 * driven by a fake client that answers, declines, rejects the method or never answers. Grants are
 * checked through the same scoped ports the host wires; every reply is held to its declared
 * `outputSchema`.
 */
class ServeUiBuilderAlternativesMcpTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")
  private val stranger = AuthenticatedUiBuilderActor("github:stranger")

  /** Who creates, branches and edits the fixtures. */
  private var author = owner

  private val service by lazy {
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(stateDirectory.resolve("state")),
      catalogs = catalogs(),
      exporter = exporter(),
    )
  }

  private var server: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    server?.stop()
  }

  // ---- compare ---------------------------------------------------------------------------------

  @Test
  fun `compare returns one picture and a summary per open branch, against the fork point`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      // The parent moves after the fork; a branch's diff still shows only what it changes.
      apply(mcp, DESIGN, 0, setText("c", "Parent moved"), operationId = "parent-1")

      val reply =
        call(mcp, ServeUiBuilderAlternativeTools.COMPARE_BRANCHES, """{"designId":"$DESIGN"}""")
      assertEquals(DESIGN, reply.string("designId"))
      assertEquals("1", reply.string("parentRevision"))
      val branches = reply["branches"]!!.jsonArray.map { it.jsonObject }
      assertEquals(listOf("bold", "quiet"), branches.map { it.string("branchId") })
      assertEquals(listOf("1", "2"), branches.map { it.string("number") })
      branches.forEach { branch ->
        val diff = branch["diff"]!!.jsonObject
        assertEquals("0", diff.string("baseRevision"), branch.toString())
        assertEquals("1 changed", diff.string("headline"), branch.toString())
        assertEquals("1", diff["counts"]!!.jsonObject.string("changed"))
        assertEquals("true", branch.string("parentMovedSinceFork"))
        assertEquals("true", branch["tile"]!!.jsonObject.string("rendered"))
      }
      assertEquals("true", reply["parent"]!!.jsonObject.string("rendered"))
      val summary = reply.string("summary")
      assertTrue(summary.contains("1. “Bold”") && summary.contains("2. “Quiet”"), summary)
      assertTrue(summary.contains(ServeUiBuilderAlternativeTools.PICK_BRANCH), summary)

      // ONE picture: parent + two branches on a single sheet.
      val sheet = decode(reply.string("imageBase64"))
      assertEquals(reply["image"]!!.jsonObject.string("widthPx").toInt(), sheet.width)
      assertEquals("2", reply.string("columns"))
      assertEquals("2", reply.string("rows"))

      // On the hosted surface the bytes become one signed https link, never base64 text.
      val catalog =
        ServeCatalogMcp(
          ServeSessionRegistry(open = { null }),
          Semaphore(1),
          publicOrigin = { PUBLIC_ORIGIN },
        )
      val result =
        catalog.uiBuilderViewResult(Json.encodeToString(JsonObject.serializer(), reply), false)
      val content = result["content"]!!.jsonArray.map { it.jsonObject }
      val text = Json.parseToJsonElement(content.first().string("text")).jsonObject
      assertNull(text["imageBase64"], "the bytes never travel as text")
      assertTrue(text["image"]!!.jsonObject.string("url").startsWith(PUBLIC_ORIGIN))
      assertEquals(1, content.count { it.string("type") == "resource_link" })
      assertTrue(content.none { it.string("type") == "image" }, content.toString())
      val errors =
        SchemaCheck(
            ServeUiBuilderAlternativeTools.outputSchema(
              ServeUiBuilderAlternativeTools.COMPARE_BRANCHES
            )!!
          )
          .errors(text)
      assertTrue(errors.isEmpty(), "$errors in $text")
    }

  @Test
  fun `compare takes named branches in order, and refuses a design that is not a branch`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      create(mcp, designId = "unrelated")

      val reply =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.COMPARE_BRANCHES,
          """{"designId":"$DESIGN","branchIds":["quiet","bold"],"device":"phone_small"}""",
        )
      assertEquals(
        listOf("quiet", "bold"),
        reply["branches"]!!.jsonArray.map { it.jsonObject.string("branchId") },
      )
      assertEquals("phone_small", reply.string("device"))
      val refused =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderAlternativeTools.COMPARE_BRANCHES,
            """{"designId":"$DESIGN","branchIds":["unrelated"]}""",
          )
        }
      assertTrue(refused.message!!.contains("not a branch of `$DESIGN`"), refused.message)
      assertFailsWith<McpRequestException> {
        call(
          mcp,
          ServeUiBuilderAlternativeTools.COMPARE_BRANCHES,
          """{"designId":"$DESIGN","device":"pager"}""",
        )
      }
    }

  @Test
  fun `no branches is a clear message from both tools, not an error or an empty picture`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      val compared =
        call(mcp, ServeUiBuilderAlternativeTools.COMPARE_BRANCHES, """{"designId":"$DESIGN"}""")
      assertTrue(compared["branches"]!!.jsonArray.isEmpty())
      assertNull(compared["image"])
      assertNull(compared["imageBase64"])
      assertTrue(compared.string("summary").startsWith("No open branches of $DESIGN"))
      assertTrue(
        compared.string("summary").contains(ServeUiBuilderBranchTools.BRANCH_DESIGN),
        compared.toString(),
      )

      var asked = false
      val picked =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.PICK_BRANCH,
          """{"designId":"$DESIGN"}""",
          interaction =
            FakeInteraction(openAi = true, form = true) {
              asked = true
              null
            },
        )
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_NO_BRANCHES, picked.string("outcome"))
      assertFalse(asked, "nobody is asked to choose between nothing")
    }

  // ---- pick: OpenAI thumbnail picker
  // -------------------------------------------------------------

  @Test
  fun `an OpenAI forms client is asked with a thumbnail picker and the pick is returned`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      val client =
        FakeInteraction(openAi = true, form = true) { form ->
          val options = pickerOptions(form)
          OpenAiFormElicitation.Answered(
            ServeCatalogMcp.FormElicitationResult(
              ServeCatalogMcp.FormElicitationAction.ACCEPT,
              buildJsonObject {
                put(ServeUiBuilderAlternativeTools.FIELD, options[1].string("uri"))
              },
            )
          )
        }
      val reply =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.PICK_BRANCH,
          """{"designId":"$DESIGN"}""",
          interaction = client,
        )
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_CHOSEN, reply.string("outcome"))
      assertEquals(ServeOpenAiForms.EXTENSION_ID, reply.string("via"))
      assertEquals("quiet", reply.string("branchId"))
      assertNull(reply["imageBase64"], "a chosen answer carries no picture")
      assertTrue(reply.string("summary").contains(ServeUiBuilderBranchTools.MERGE_BRANCH))
      assertEquals(0, client.plainForms.size, "never asked twice")

      // The picker: one resource option per branch, with a thumbnail and the branch's view.
      val form = client.openAiForms.single()
      val field = form["properties"]!!.jsonObject[ServeUiBuilderAlternativeTools.FIELD]!!.jsonObject
      assertEquals("string", field.string("type"))
      assertEquals("uri", field.string("format"))
      assertEquals(
        listOf(ServeUiBuilderAlternativeTools.FIELD),
        form["required"]!!.jsonArray.map { it.jsonPrimitive.content },
      )
      val options = pickerOptions(form)
      assertEquals(
        listOf(
          ServeUiBuilderAlternativeTools.optionUri("bold"),
          ServeUiBuilderAlternativeTools.optionUri("quiet"),
        ),
        options.map { it.string("uri") },
      )
      assertEquals(listOf("1. Bold", "2. Quiet"), options.map { it.string("title") })
      options.forEachIndexed { index, option ->
        val meta = option["_meta"]!!.jsonObject
        val thumbnail = meta[ServeOpenAiForms.THUMBNAIL_KEY]!!.jsonObject
        assertTrue(thumbnail.string("src").startsWith("data:image/png;base64,"))
        val png = decode(thumbnail.string("src").substringAfter("base64,"))
        assertTrue(maxOf(png.width, png.height) <= ServeUiBuilderAlternativeTools.THUMBNAIL_EDGE_PX)
        val target = meta[ServeOpenAiForms.PREVIEW_KEY]!!.jsonObject["target"]!!.jsonObject
        assertEquals("mcp_app_tool", target.string("type"))
        assertEquals(ServeUiBuilderMcp.VIEW, target.string("name"))
        assertEquals(
          listOf("bold", "quiet")[index],
          target["arguments"]!!.jsonObject.string("designId"),
        )
      }
    }

  @Test
  fun `an OpenAI picker that is declined, unanswered, rejected or answered wrongly never blocks`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      suspend fun pick(client: FakeInteraction) =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.PICK_BRANCH,
          """{"designId":"$DESIGN"}""",
          interaction = client,
        )

      // Declined: nothing is chosen, and nobody is asked again.
      val declining =
        FakeInteraction(openAi = true, form = true) {
          OpenAiFormElicitation.Answered(
            ServeCatalogMcp.FormElicitationResult(ServeCatalogMcp.FormElicitationAction.DECLINE)
          )
        }
      val declined = pick(declining)
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_DECLINED, declined.string("outcome"))
      assertNull(declined["branchId"])
      assertEquals(0, declining.plainForms.size)

      // No answer in time: the numbered list, not a second form.
      val silent = FakeInteraction(openAi = true, form = true) { OpenAiFormElicitation.NoAnswer }
      val unanswered = pick(silent)
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_ASK_IN_CHAT, unanswered.string("outcome"))
      assertEquals(0, silent.plainForms.size)

      // An answer naming no offered option is no answer.
      val wrong =
        FakeInteraction(openAi = true) {
          OpenAiFormElicitation.Answered(
            ServeCatalogMcp.FormElicitationResult(
              ServeCatalogMcp.FormElicitationAction.ACCEPT,
              buildJsonObject {
                put(ServeUiBuilderAlternativeTools.FIELD, "https://evil.example/")
              },
            )
          )
        }
      assertEquals(
        ServeUiBuilderAlternativeTools.OUTCOME_ASK_IN_CHAT,
        pick(wrong).string("outcome"),
      )

      // The client rejects the method: fall back to the plain form, which answers.
      val rejecting =
        FakeInteraction(
          openAi = true,
          form = true,
          plain = { accept("bold") },
        ) {
          OpenAiFormElicitation.Unsupported
        }
      val fellBack = pick(rejecting)
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_CHOSEN, fellBack.string("outcome"))
      assertEquals(ServeUiBuilderAlternativeTools.VIA_FORM, fellBack.string("via"))
      assertEquals("bold", fellBack.string("branchId"))
      assertEquals(1, rejecting.openAiForms.size)
      assertEquals(1, rejecting.plainForms.size)
    }

  // ---- pick: plain MCP form, then chat
  // -----------------------------------------------------------

  @Test
  fun `a plain form client gets a single-choice enum of the branches`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      val client = FakeInteraction(form = true, plain = { accept("quiet") })
      val reply =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.PICK_BRANCH,
          """{"designId":"$DESIGN","message":"Which one?"}""",
          interaction = client,
        )
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_CHOSEN, reply.string("outcome"))
      assertEquals(ServeUiBuilderAlternativeTools.VIA_FORM, reply.string("via"))
      assertEquals("quiet", reply.string("branchId"))
      assertEquals(0, client.openAiForms.size, "a client without the extension is never sent one")
      val (message, schema) = client.plainForms.single()
      assertEquals("Which one?", message)
      val field =
        schema["properties"]!!.jsonObject[ServeUiBuilderAlternativeTools.FIELD]!!.jsonObject
      assertEquals(
        listOf("bold", "quiet"),
        field["enum"]!!.jsonArray.map { it.jsonPrimitive.content },
      )
      assertEquals(
        listOf("1. Bold", "2. Quiet"),
        field["enumNames"]!!.jsonArray.map { it.jsonPrimitive.content },
      )

      val declined =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.PICK_BRANCH,
          """{"designId":"$DESIGN"}""",
          interaction =
            FakeInteraction(
              form = true,
              plain = {
                ServeCatalogMcp.FormElicitationResult(ServeCatalogMcp.FormElicitationAction.CANCEL)
              },
            ),
        )
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_DECLINED, declined.string("outcome"))

      // An enum answer outside the offered ids, or no answer at all, is the numbered list.
      for (plain in
        listOf<() -> ServeCatalogMcp.FormElicitationResult?>({ accept("unrelated") }, { null })) {
        val reply2 =
          call(
            mcp,
            ServeUiBuilderAlternativeTools.PICK_BRANCH,
            """{"designId":"$DESIGN"}""",
            interaction = FakeInteraction(form = true, plain = plain),
          )
        assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_ASK_IN_CHAT, reply2.string("outcome"))
      }
    }

  @Test
  fun `without any form the agent gets a numbered list and one picture to post in chat`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      val reply =
        call(mcp, ServeUiBuilderAlternativeTools.PICK_BRANCH, """{"designId":"$DESIGN"}""")
      assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_ASK_IN_CHAT, reply.string("outcome"))
      assertEquals(ServeUiBuilderAlternativeTools.VIA_CHAT, reply.string("via"))
      assertNull(reply["branchId"])
      val summary = reply.string("summary")
      assertTrue(summary.contains("reply with the number"), summary)
      assertTrue(summary.contains("\n1. “Bold” (bold"), summary)
      assertTrue(summary.contains("\n2. “Quiet” (quiet"), summary)
      assertEquals(
        listOf(1, 2),
        reply["options"]!!.jsonArray.map { it.jsonObject.string("number").toInt() },
      )
      val sheet = decode(reply.string("imageBase64"))
      assertEquals(reply["image"]!!.jsonObject.string("widthPx").toInt(), sheet.width)
    }

  // ---- access ----------------------------------------------------------------------------------

  @Test
  fun `access is the parent's - a viewer compares and picks, a stranger is told nothing`() =
    runBlocking<Unit> {
      val mcp = mcp()
      twoAlternatives(mcp)
      share(mcp, viewer.actorId, "viewer")

      val compared =
        call(
          mcp,
          ServeUiBuilderAlternativeTools.COMPARE_BRANCHES,
          """{"designId":"$DESIGN"}""",
          viewer,
        )
      assertEquals(2, compared["branches"]!!.jsonArray.size)
      assertEquals(
        ServeUiBuilderAlternativeTools.OUTCOME_ASK_IN_CHAT,
        call(mcp, ServeUiBuilderAlternativeTools.PICK_BRANCH, """{"designId":"$DESIGN"}""", viewer)
          .string("outcome"),
      )
      for (tool in ServeUiBuilderAlternativeTools.TOOL_NAMES) {
        val refused =
          assertFailsWith<McpRequestException> {
            call(mcp, tool, """{"designId":"$DESIGN"}""", stranger)
          }
        assertEquals("no design `$DESIGN` this actor can read", refused.message)
      }

      // Only an open branch can be picked: after a merge the winner and its archived sibling are
      // both refused by name, and the default offers nothing.
      call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"bold"}""")
      for (id in listOf("bold", "quiet")) {
        val refused =
          assertFailsWith<McpRequestException> {
            call(
              mcp,
              ServeUiBuilderAlternativeTools.PICK_BRANCH,
              """{"designId":"$DESIGN","branchIds":["$id"]}""",
            )
          }
        assertTrue(refused.message!!.contains("only an open branch"), refused.message)
      }
      assertEquals(
        ServeUiBuilderAlternativeTools.OUTCOME_NO_BRANCHES,
        call(mcp, ServeUiBuilderAlternativeTools.PICK_BRANCH, """{"designId":"$DESIGN"}""")
          .string("outcome"),
      )
      // Compare still shows a closed branch when it is named.
      assertEquals(
        "merged",
        call(
            mcp,
            ServeUiBuilderAlternativeTools.COMPARE_BRANCHES,
            """{"designId":"$DESIGN","branchIds":["bold"]}""",
          )["branches"]!!
          .jsonArray
          .single()
          .jsonObject
          .string("status"),
      )
    }

  @Test
  fun `a grant scoped to the parent design reaches its branches through the parent and no further`() =
    runBlocking<Unit> {
      twoAlternatives(mcp())
      create(mcp(), designId = "other")
      createBranch(mcp(), "other", "Other's", "others")

      val lookup = ServeUiBuilderGrantScope.Lookup { _, _ -> setOf(DESIGN) }
      val port: UiBuilderServicePort = service
      val branchPort: UiBuilderBranchPort = service
      val scoped =
        ServeUiBuilderMcp(
          ServeUiBuilderGrantScope.limit(
            port,
            lookup,
            ServeUiBuilderGrantScope.parentsOf(branchPort),
          ),
          branches = ServeUiBuilderGrantScope.limit(branchPort, lookup),
        )
      val agent = AuthenticatedUiBuilderActor("agent:holder", onBehalfOfActorId = owner.actorId)

      val compared =
        call(
          scoped,
          ServeUiBuilderAlternativeTools.COMPARE_BRANCHES,
          """{"designId":"$DESIGN"}""",
          agent,
        )
      assertEquals(2, compared["branches"]!!.jsonArray.size)
      assertTrue(
        compared["branches"]!!.jsonArray.all {
          it.jsonObject["tile"]!!.jsonObject.string("rendered") == "true"
        },
        "each branch is rendered through the grant: $compared",
      )
      for (tool in ServeUiBuilderAlternativeTools.TOOL_NAMES) {
        assertFailsWith<McpRequestException> {
          call(scoped, tool, """{"designId":"other"}""", agent)
        }
      }
    }

  @Test
  fun `the tools exist only where branches do, as reads, with closed output schemas`() {
    fun names(branches: Boolean) =
      ServeUiBuilderMcp.declarations(
          { name, description, schema ->
            buildJsonObject {
              put("name", JsonPrimitive(name))
              put("description", JsonPrimitive(description))
              put("inputSchema", Json.parseToJsonElement(schema))
            }
          },
          branches = branches,
        )
        .map { it["name"]!!.jsonPrimitive.content }
    assertTrue(names(true).containsAll(ServeUiBuilderAlternativeTools.TOOL_NAMES))
    assertTrue(names(false).none { it in ServeUiBuilderAlternativeTools.TOOL_NAMES })
    assertTrue(
      ServeUiBuilderMcp.BRANCH_TOOL_NAMES.containsAll(ServeUiBuilderAlternativeTools.TOOL_NAMES)
    )
    val without = ServeUiBuilderMcp(service)
    assertTrue(ServeUiBuilderAlternativeTools.TOOL_NAMES.all { without.capabilityFor(it) == null })
    ServeUiBuilderAlternativeTools.TOOL_NAMES.forEach { name ->
      assertEquals(UiBuilderRouteCapability.READ, mcp().capabilityFor(name))
      val schema = assertNotNull(ServeUiBuilderAlternativeTools.outputSchema(name))
      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
    }
  }

  // ---- the OpenAI wire -------------------------------------------------------------------------

  @Test
  fun `the single-select field is the spec's own example`() {
    val field =
      ServeOpenAiForms.singleResourceField(
        listOf(
          ServeOpenAiForms.ResourceOption(
            "cad://parts/hex-bolt",
            "hex-bolt",
            "M6 hex bolt",
            thumbnailPngBase64 = "AAAA",
            previewTarget =
              ServeOpenAiForms.appToolTarget("cad.open", buildJsonObject { put("x", 1) }),
          ),
          ServeOpenAiForms.ResourceOption("cad://parts/washer", "washer", "M6 washer"),
        ),
        title = "Reference file",
      )
    // The spec's "Request (single selection)" example, minus its userOptions and default.
    assertEquals(
      Json.parseToJsonElement(
        """
        {"type":"string","title":"Reference file","format":"uri",
         "x-openai-input":{"type":"resource","options":[
           {"uri":"cad://parts/hex-bolt","name":"hex-bolt","title":"M6 hex bolt",
            "_meta":{
              "openai/thumbnail":{"src":"data:image/png;base64,AAAA","mimeType":"image/png"},
              "openai/preview":{"target":{"type":"mcp_app_tool","name":"cad.open","arguments":{"x":1}}}}},
           {"uri":"cad://parts/washer","name":"washer","title":"M6 washer"}]}}
        """
      ),
      field,
    )
    assertTrue(
      ServeOpenAiForms.declaredIn(
        Json.parseToJsonElement(
            """{"capabilities":{"extensions":{"openai/elicitation":{"form":{}}}}}"""
          )
          .jsonObject
      )
    )
    for (capabilities in
      listOf(
        """{}""",
        """{"elicitation":{"form":{}}}""",
        """{"extensions":{"openai/elicitation":{}}}""",
      )) {
      assertFalse(
        ServeOpenAiForms.declaredIn(
          Json.parseToJsonElement("""{"capabilities":$capabilities}""").jsonObject
        ),
        capabilities,
      )
    }
  }

  @Test
  fun `a request scope sends openai elicitation create and reads a refusal as unsupported`() =
    runBlocking<Unit> {
      val scopes = ServeMcpRequestScopes()
      val scope =
        assertNotNull(
          scopes.open(
            ServeCatalogMcp.MCP_PROTOCOL_VERSION,
            formElicitationSupported = false,
            openAiFormsSupported = true,
          )
        )
      var sent: JsonObject? = null
      val answering =
        scopes.interaction(scope) { request ->
          sent = request
          scopes.acceptResponse(
            scope.id,
            Json.parseToJsonElement(
                """{"jsonrpc":"2.0","id":"${request["id"]!!.jsonPrimitive.content}","result":{"action":"accept","content":{"branch":"x"}}}"""
              )
              .jsonObject,
          )
        }
      assertTrue(answering.openAiFormsSupported)
      assertFalse(answering.formElicitationSupported)
      assertNull(answering.elicitForm("Pick", JsonObject(emptyMap()), 1_000), "no plain form")
      val answered =
        assertIs<OpenAiFormElicitation.Answered>(
          answering.elicitOpenAiForm("Pick", buildJsonObject { put("type", "object") }, 1_000)
        )
      assertEquals(ServeCatalogMcp.FormElicitationAction.ACCEPT, answered.result.action)
      assertEquals(ServeOpenAiForms.METHOD, sent!!.string("method"))
      val params = sent!!["params"]!!.jsonObject
      assertEquals("form", params.string("mode"))
      assertEquals("Pick", params.string("message"))

      val refusing =
        scopes.interaction(scope) { request ->
          scopes.acceptResponse(
            scope.id,
            Json.parseToJsonElement(
                """{"jsonrpc":"2.0","id":"${request["id"]!!.jsonPrimitive.content}","error":{"code":-32601,"message":"Method not found"}}"""
              )
              .jsonObject,
          )
        }
      assertEquals(
        OpenAiFormElicitation.Unsupported,
        refusing.elicitOpenAiForm("Pick", JsonObject(emptyMap()), 1_000),
      )
      assertEquals(
        OpenAiFormElicitation.NoAnswer,
        scopes.interaction(scope) {}.elicitOpenAiForm("Pick", JsonObject(emptyMap()), 10),
      )
      val plain = assertNotNull(scopes.open(ServeCatalogMcp.MCP_PROTOCOL_VERSION, true))
      assertEquals(
        OpenAiFormElicitation.Unsupported,
        scopes
          .interaction(plain) { error("never sent") }
          .elicitOpenAiForm("Pick", JsonObject(emptyMap()), 1_000),
      )
    }

  @Test
  fun `over HTTP an OpenAI-only client negotiates a scope and picks, a stateless one gets the list`() {
    start()
    runBlocking {
      // The operator token is its own actor, so the operator makes the alternatives here.
      author = AuthenticatedUiBuilderActor(ServeAgentGrants.OPERATOR_ACTOR_ID)
      twoAlternatives(ServeUiBuilderMcp(service, branches = service))
    }
    val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    fun post(body: String, session: String? = null): okhttp3.Response =
      http
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server!!.port}/mcp")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", ServeCatalogMcp.MCP_PROTOCOL_VERSION)
            .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
            .apply { session?.let { header("MCP-Session-Id", it) } }
            .post(body.toRequestBody(JSON))
            .build()
        )
        .execute()

    // Declares only the OpenAI extension — no plain `elicitation` — and still gets a session.
    val session =
      post(
          """{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"${ServeCatalogMcp.MCP_PROTOCOL_VERSION}","capabilities":{"extensions":{"openai/elicitation":{"form":{}}}},"clientInfo":{"name":"openai","version":"1"}}}"""
        )
        .use { assertNotNull(it.header("MCP-Session-Id"), "an OpenAI-forms client needs a scope") }

    val call =
      """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"${ServeUiBuilderAlternativeTools.PICK_BRANCH}","arguments":{"designId":"$DESIGN"}}}"""
    val asked = mutableListOf<JsonObject>()
    val result =
      post(call, session).use { response ->
        assertTrue(response.header("Content-Type").orEmpty().startsWith("text/event-stream"))
        val source = response.body.source()
        var final: JsonObject? = null
        while (final == null) {
          val line = source.readUtf8Line() ?: error("stream ended without a result")
          if (!line.startsWith("data: ")) continue
          val message = Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject
          if (message["method"] != null) {
            asked += message
            val uri =
              pickerOptions(message["params"]!!.jsonObject["requestedSchema"]!!.jsonObject)[0]
                .string("uri")
            val id = message["id"]!!.jsonPrimitive.content
            post(
                """{"jsonrpc":"2.0","id":"$id","result":{"action":"accept","content":{"branch":"$uri"}}}""",
                session,
              )
              .use { assertEquals(202, it.code, it.body.string()) }
            continue
          }
          final = message["result"]!!.jsonObject
        }
        final!!
      }
    assertEquals(ServeOpenAiForms.METHOD, asked.single().string("method"))
    val reply = result["structuredContent"]!!.jsonObject
    assertEquals(
      ServeUiBuilderAlternativeTools.OUTCOME_CHOSEN,
      reply.string("outcome"),
      reply.toString(),
    )
    assertEquals("bold", reply.string("branchId"))

    // Stateless: the numbered list, and one signed https picture — never base64 text.
    val stateless =
      post(call).use { Json.parseToJsonElement(it.body.string()).jsonObject["result"]!!.jsonObject }
    val chat = stateless["structuredContent"]!!.jsonObject
    assertEquals(ServeUiBuilderAlternativeTools.OUTCOME_ASK_IN_CHAT, chat.string("outcome"))
    assertTrue(chat["image"]!!.jsonObject.string("url").startsWith("$PUBLIC_ORIGIN/mcp/render.png"))
    assertNull(chat["imageBase64"])
    assertFalse(stateless.toString().contains("data:image/png;base64"), "no base64 in a chat reply")
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** A client that declared [openAi] and/or [form], answering each with the given script. */
  private class FakeInteraction(
    private val openAi: Boolean = false,
    private val form: Boolean = false,
    private val plain: () -> ServeCatalogMcp.FormElicitationResult? = { null },
    private val picker: (JsonObject) -> OpenAiFormElicitation? = { null },
  ) : ServeCatalogMcp.ClientInteraction {
    val openAiForms = mutableListOf<JsonObject>()
    val plainForms = mutableListOf<Pair<String, JsonObject>>()

    override val formElicitationSupported = form
    override val openAiFormsSupported = openAi

    override suspend fun elicitForm(
      message: String,
      requestedSchema: JsonObject,
      timeoutMillis: Long,
    ): ServeCatalogMcp.FormElicitationResult? {
      assertTrue(form, "a plain form was sent to a client that did not declare one")
      assertTrue(timeoutMillis in 1..ServeUiBuilderAlternativeTools.PICK_TIMEOUT_MILLIS)
      plainForms += message to requestedSchema
      return plain()
    }

    override suspend fun elicitOpenAiForm(
      message: String,
      requestedSchema: JsonObject,
      timeoutMillis: Long,
    ): OpenAiFormElicitation {
      assertTrue(openAi, "an OpenAI form was sent to a client that did not declare one")
      assertTrue(timeoutMillis in 1..ServeUiBuilderAlternativeTools.PICK_TIMEOUT_MILLIS)
      openAiForms += requestedSchema
      return picker(requestedSchema) ?: OpenAiFormElicitation.NoAnswer
    }
  }

  private fun accept(branchId: String) =
    ServeCatalogMcp.FormElicitationResult(
      ServeCatalogMcp.FormElicitationAction.ACCEPT,
      buildJsonObject { put(ServeUiBuilderAlternativeTools.FIELD, branchId) },
    )

  private fun pickerOptions(form: JsonObject): List<JsonObject> =
    form["properties"]!!
      .jsonObject[ServeUiBuilderAlternativeTools.FIELD]!!
      .jsonObject[ServeOpenAiForms.INPUT_KEY]!!
      .jsonObject["options"]!!
      .jsonArray
      .map { it.jsonObject }

  /** A design with two sibling branches, each changing node `b` its own way. */
  private suspend fun twoAlternatives(mcp: ServeUiBuilderMcp) {
    create(mcp)
    createBranch(mcp, DESIGN, "Bold", "bold")
    createBranch(mcp, DESIGN, "Quiet", "quiet")
    apply(mcp, "bold", 0, setText("b", "BOLD"), operationId = "bold-1")
    apply(mcp, "quiet", 0, setText("b", "quiet"), operationId = "quiet-1")
  }

  private fun mcp(): ServeUiBuilderMcp =
    ServeUiBuilderMcp(
      service,
      branches = service,
      validator = ScratchUiBuilderDraftValidator(catalogs(), exporter()),
    )

  private fun catalogs() =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = setOf(CATALOG),
      exportCapabilities =
        ExportCapabilitiesV1.Builder()
          .also {
            it.composeCode = false
            it.svg = false
            it.png = true
          }
          .build(),
    )

  private suspend fun call(
    mcp: ServeUiBuilderMcp,
    tool: String,
    arguments: String,
    actor: AuthenticatedUiBuilderActor = author,
    interaction: ServeCatalogMcp.ClientInteraction = ServeCatalogMcp.ClientInteraction.Unsupported,
  ): JsonObject {
    val text =
      mcp.call(
        tool,
        Json.parseToJsonElement(arguments).jsonObject,
        actor,
        callId = tool,
        clientInteraction = interaction,
      )
    val reply = Json.parseToJsonElement(text).jsonObject
    (ServeUiBuilderAlternativeTools.outputSchema(tool)
        ?: ServeUiBuilderBranchTools.outputSchema(tool))
      ?.let { schema ->
        val errors = SchemaCheck(schema).errors(reply)
        assertTrue(errors.isEmpty(), "$tool reply breaks its output schema: $errors in $reply")
      }
    return reply
  }

  private suspend fun createBranch(
    mcp: ServeUiBuilderMcp,
    designId: String,
    name: String,
    branchId: String,
  ) {
    call(
      mcp,
      ServeUiBuilderBranchTools.BRANCH_DESIGN,
      """{"designId":"$designId","name":"$name","branchId":"$branchId"}""",
    )
  }

  private suspend fun create(mcp: ServeUiBuilderMcp, designId: String = DESIGN) {
    val document = json.encodeToString(DesignDocumentV1.serializer(), fixture().copy(id = designId))
    val text =
      mcp.call(
        ServeUiBuilderMcp.CREATE_DESIGN,
        Json.parseToJsonElement(
            """{"designId":"$designId","includeCatalog":true,"document":$document}"""
          )
          .jsonObject,
        author,
        callId = "create",
      )
    assertIs<SnapshotResponseV1>(
      json.decodeFromString(McpResponseEnvelopeV1.serializer(), text).response,
      text,
    )
  }

  private suspend fun apply(
    mcp: ServeUiBuilderMcp,
    designId: String,
    baseRevision: Long,
    mutation: DesignMutationV1,
    operationId: String,
  ) {
    val operations =
      json.encodeToString(ListSerializer(DesignMutationV1.serializer()), listOf(mutation))
    val text =
      mcp.call(
        ServeUiBuilderMcp.APPLY,
        Json.parseToJsonElement(
            """{"designId":"$designId","operationId":"$operationId","baseRevision":$baseRevision,"operations":$operations}"""
          )
          .jsonObject,
        author,
        callId = "apply",
      )
    val response = json.decodeFromString(McpResponseEnvelopeV1.serializer(), text).response
    assertIs<AcceptedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(response, text).outcome, text)
  }

  private suspend fun share(mcp: ServeUiBuilderMcp, actorId: String, role: String) {
    mcp.call(
      ServeUiBuilderMcp.SHARE_DESIGN,
      Json.parseToJsonElement("""{"designId":"$DESIGN","actorId":"$actorId","role":"$role"}""")
        .jsonObject,
      owner,
      callId = "share",
    )
  }

  private fun start() {
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          canonicalOrigin = PUBLIC_ORIGIN,
          token = OPERATOR_TOKEN,
          sessions = ServeSessionRegistry(open = { null }),
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          catalogMcpInteractionTimeoutMillis = 5_000,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service,
          uiBuilderBranches = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null),
        )
        .also(ServeHttpServer::start)
  }

  /** A PNG export whose size follows the environment and whose colour follows node `b`'s text. */
  private fun exporter(): UiBuilderExportExecutor = UiBuilderExportExecutor { request ->
    check(request.format == ExportFormatV1.PNG) { "only PNG is exported here" }
    val environment = request.document.environment
    val text = (request.document.nodes["b"]?.properties?.get("text") as? StringValueV1)?.value
    val image =
      BufferedImage(environment.widthDp / 4, environment.heightDp / 4, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = java.awt.Color(text.hashCode() and 0xffffff)
    graphics.fillRect(0, 0, image.width, image.height)
    graphics.dispose()
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    ExportArtifactV1(
      ExportFormatV1.PNG,
      "image/png",
      ExportEncodingV1.BASE64,
      Base64.getEncoder().encodeToString(png),
      UiBuilderDesignMatrix.sha256(png),
      emptyList(),
    )
  }

  private fun decode(base64: String): BufferedImage =
    assertNotNull(ImageIO.read(Base64.getDecoder().decode(base64).inputStream()))

  private fun JsonObject.string(name: String): String = this[name]!!.jsonPrimitive.content

  private fun setText(nodeId: String, value: String): DesignMutationV1 =
    SetPropertyMutationV1(nodeId, "text", StringValueV1(value))

  private fun text(id: String, value: String) =
    DesignNodeV1(
      id = id,
      componentId = "m3/text",
      properties = mapOf("text" to StringValueV1(value)),
    )

  private fun fixture(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN,
      title = "Alternatives screen",
      revision = 0,
      catalogPin = CatalogReferenceV1(CATALOG, "candidate", "candidate", "candidate"),
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
              slots = mapOf("children" to listOf("a", "b", "c")),
            ),
          "a" to text("a", "Alpha"),
          "b" to text("b", "Beta"),
          "c" to text("c", "Gamma"),
        ),
    )

  private companion object {
    const val CATALOG = "m3-catalog"
    const val DESIGN = "alternatives-screen"
    const val OPERATOR_TOKEN = "ui-builder-alternatives-operator-token"
    const val PUBLIC_ORIGIN = "https://designs.example"
    val JSON = "application/json".toMediaType()
  }
}
