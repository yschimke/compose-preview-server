package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DeleteNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchCall
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.nio.file.Path
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
import org.junit.jupiter.api.io.TempDir

/**
 * `ui_builder_branch_design`, `ui_builder_list_branches`, `ui_builder_merge_branch` and
 * `ui_builder_archive_branch` (phase 2 of yschimke/compose-ui-builder#375) against a real
 * persistent service, through [ServeUiBuilderMcp] exactly as the MCP surface drives it. Every reply
 * is held to the `outputSchema` its tool declares; the existing tools are driven on a branch id
 * unchanged.
 */
class ServeUiBuilderBranchMcpTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")
  private val stranger = AuthenticatedUiBuilderActor("github:stranger")

  private val service by lazy {
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(stateDirectory.resolve("state")),
      catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
      exporter = { error("this test never exports") },
    )
  }

  @Test
  fun `branch, apply and diff on the branch id, then merge archives the sibling`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      apply(mcp, DESIGN, 0, setText("a", "Parent edit"))

      val bold = branch(mcp, "Bold", branchId = "bold")
      assertEquals("bold", bold["branchId"]!!.jsonPrimitive.content)
      assertEquals(DESIGN, bold["parentDesignId"]!!.jsonPrimitive.content)
      assertEquals("open", bold["status"]!!.jsonPrimitive.content)
      assertEquals("1", bold["forkRevision"]!!.jsonPrimitive.content)
      assertEquals("1", bold["headRevision"]!!.jsonPrimitive.content)
      branch(mcp, "Quiet", branchId = "quiet")

      // The ordinary tools, on the branch's id, unchanged: apply, get_design, diff.
      apply(mcp, "bold", 1, setText("b", "Bold beta"))
      val snapshot =
        envelope(
          mcp.call(
            ServeUiBuilderMcp.GET_DESIGN,
            Json.parseToJsonElement("""{"designId":"bold","includeCatalog":true}""").jsonObject,
            owner,
            callId = "get",
          )
        )
      val document = assertIs<SnapshotResponseV1>(snapshot.response).snapshot.state.document
      assertEquals(2L, document.revision)
      assertEquals(StringValueV1("Bold beta"), document.nodes.getValue("b").properties["text"])
      val diff =
        call(
          mcp,
          ServeUiBuilderHistoryTools.DIFF_DESIGNS,
          """{"a":{"designId":"$DESIGN"},"b":{"designId":"bold"}}""",
        )
      val changed = diff["nodes"]!!.jsonArray.map { it.jsonObject }
      assertEquals(listOf("b"), changed.map { it["nodeId"]!!.jsonPrimitive.content })
      assertEquals("changed", changed.single()["change"]!!.jsonPrimitive.content)

      val listed = call(mcp, ServeUiBuilderBranchTools.LIST_BRANCHES, """{"designId":"$DESIGN"}""")
      assertEquals(
        setOf("bold", "quiet"),
        listed["branches"]!!
          .jsonArray
          .map { it.jsonObject["branchId"]!!.jsonPrimitive.content }
          .toSet(),
      )
      val boldRow =
        listed["branches"]!!.jsonArray.single {
          it.jsonObject["branchId"]!!.jsonPrimitive.content == "bold"
        }
      assertEquals("1", boldRow.jsonObject["commandCount"]!!.jsonPrimitive.content)

      // A dry run reports and writes nothing.
      val dry =
        call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"bold","dryRun":true}""")
      assertEquals("true", dry["dryRun"]!!.jsonPrimitive.content)
      assertEquals("true", dry["merged"]!!.jsonPrimitive.content)
      assertEquals(listOf("quiet"), dry["archivedSiblingIds"]!!.jsonArray.map { it.string() })
      assertTrue(dry["summary"]!!.jsonPrimitive.content.startsWith("Dry run:"), dry.toString())
      assertEquals(1L, currentRevision(DESIGN))

      val merged = call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"bold"}""")
      assertEquals("true", merged["merged"]!!.jsonPrimitive.content)
      assertEquals("1", merged["parentRevisionBefore"]!!.jsonPrimitive.content)
      assertEquals("2", merged["parentRevisionAfter"]!!.jsonPrimitive.content)
      val command = merged["commands"]!!.jsonArray.single().jsonObject
      assertEquals("applied", command["status"]!!.jsonPrimitive.content)
      assertEquals(owner.actorId, command["actorId"]!!.jsonPrimitive.content)
      assertEquals("2", command["committedRevision"]!!.jsonPrimitive.content)
      assertEquals(listOf("quiet"), merged["archivedSiblingIds"]!!.jsonArray.map { it.string() })
      assertEquals(2L, currentRevision(DESIGN))
      assertEquals(
        StringValueV1("Bold beta"),
        document(DESIGN).nodes.getValue("b").properties["text"],
      )

      // The sibling is kept, archived and linked to the merge; status filters the listing.
      val archived =
        call(
            mcp,
            ServeUiBuilderBranchTools.LIST_BRANCHES,
            """{"designId":"$DESIGN","status":"archived"}""",
          )["branches"]!!
          .jsonArray
          .single()
          .jsonObject
      assertEquals("quiet", archived["branchId"]!!.jsonPrimitive.content)
      assertEquals("bold", archived["supersededByBranchId"]!!.jsonPrimitive.content)
      val open =
        call(
          mcp,
          ServeUiBuilderBranchTools.LIST_BRANCHES,
          """{"designId":"$DESIGN","status":"open"}""",
        )
      assertTrue(open["branches"]!!.jsonArray.isEmpty(), open.toString())
      assertFailsWith<McpRequestException> {
        call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"bold"}""")
      }
      assertFailsWith<McpRequestException> {
        call(
          mcp,
          ServeUiBuilderBranchTools.LIST_BRANCHES,
          """{"designId":"$DESIGN","status":"pending"}""",
        )
      }
    }

  @Test
  fun `a refused merge reports the command and code, writes nothing, and a skip resolves it`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      branch(mcp, "Cleanup", branchId = "cleanup")
      apply(mcp, "cleanup", 0, setText("a", "Cleaned"), operationId = "b-1")
      apply(mcp, "cleanup", 1, setText("c", "Edited on the branch"), operationId = "b-2")
      // Somebody deletes the node on the parent after the fork; the branch's edit of it can't land.
      apply(mcp, DESIGN, 0, DeleteNodeMutationV1("c"))

      val refused = call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"cleanup"}""")
      assertEquals("false", refused["merged"]!!.jsonPrimitive.content)
      val commands = refused["commands"]!!.jsonArray.map { it.jsonObject }
      assertEquals(listOf("applied", "refused"), commands.map { it.string("status") })
      val stop = commands.last()
      assertEquals("b-2", stop.string("operationId"))
      assertTrue(stop.string("code") in setOf("DELETED_NODE", "UNKNOWN_NODE"), stop.toString())
      assertEquals("c", stop.string("nodeId"))
      assertEquals("0", refused["remaining"]!!.jsonPrimitive.content)
      assertEquals(refused.string("parentRevisionBefore"), refused.string("parentRevisionAfter"))
      assertTrue(refused.string("summary").contains("skipOperationIds"), refused.toString())
      assertEquals(1L, currentRevision(DESIGN))

      val resolved =
        call(
          mcp,
          ServeUiBuilderBranchTools.MERGE_BRANCH,
          """{"branchId":"cleanup","skipOperationIds":["b-2"]}""",
        )
      assertEquals("true", resolved["merged"]!!.jsonPrimitive.content)
      assertEquals(listOf("b-2"), resolved["skippedOperationIds"]!!.jsonArray.map { it.string() })
      assertEquals(
        listOf("b-1"),
        resolved["commands"]!!.jsonArray.map { it.jsonObject.string("operationId") },
      )
      assertTrue(resolved.string("summary").contains("Skipped: b-2"), resolved.toString())
      assertEquals(
        StringValueV1("Cleaned"),
        document(DESIGN).nodes.getValue("a").properties["text"],
      )
    }

  @Test
  fun `branches inherit the parent's access - a viewer reads them, only a writer branches or merges`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      share(mcp, viewer.actorId, "viewer")
      branch(mcp, "Mine", branchId = "mine")

      // Read: listing the parent's branches, and opening a branch, which the viewer was never
      // shared directly.
      val listed =
        call(mcp, ServeUiBuilderBranchTools.LIST_BRANCHES, """{"designId":"$DESIGN"}""", viewer)
      assertEquals(
        listOf("mine"),
        listed["branches"]!!.jsonArray.map { it.jsonObject.string("branchId") },
      )
      assertTrue(service.canRead(viewer, "mine"))

      // Write: refused for the viewer, named as a write refusal.
      val branchRefused =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderBranchTools.BRANCH_DESIGN,
            """{"designId":"$DESIGN","name":"Theirs"}""",
            viewer,
          )
        }
      assertTrue(branchRefused.message!!.contains("write access"), branchRefused.message)
      val mergeRefused =
        assertFailsWith<McpRequestException> {
          call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"mine"}""", viewer)
        }
      assertTrue(mergeRefused.message!!.contains("write access"), mergeRefused.message)
      assertFailsWith<McpRequestException> {
        call(mcp, ServeUiBuilderBranchTools.ARCHIVE_BRANCH, """{"branchId":"mine"}""", viewer)
      }

      // A stranger is told nothing exists.
      val strangerList =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderBranchTools.LIST_BRANCHES,
            """{"designId":"$DESIGN"}""",
            stranger,
          )
        }
      assertEquals("no design `$DESIGN` this actor can read", strangerList.message)
      assertEquals(
        "no branch `mine` this actor can read Nothing was merged.",
        assertFailsWith<McpRequestException> {
            call(mcp, ServeUiBuilderBranchTools.MERGE_BRANCH, """{"branchId":"mine"}""", stranger)
          }
          .message,
      )

      // The owner archives it; edits to it are then refused, and it stays listed.
      val archived = call(mcp, ServeUiBuilderBranchTools.ARCHIVE_BRANCH, """{"branchId":"mine"}""")
      assertEquals("archived", archived["branch"]!!.jsonObject.string("status"))
      val refusedEdit = applyRaw(mcp, "mine", 0, setText("a", "Too late"))
      assertTrue(
        refusedEdit is RejectedOutcomeV1 || refusedEdit == null,
        "an archived branch takes no edits: $refusedEdit",
      )
      assertEquals(
        "archived",
        call(mcp, ServeUiBuilderBranchTools.LIST_BRANCHES, """{"designId":"$DESIGN"}""")[
            "branches"]!!
          .jsonArray
          .single()
          .jsonObject
          .string("status"),
      )
    }

  @Test
  fun `get_links reports branch parentage from the runtime, both ways`() =
    runBlocking<Unit> {
      val mcp = mcp(links = ServeUiBuilderLinksStore(stateDirectory.resolve("links")))
      create(mcp)
      branch(mcp, "Alt", branchId = "alt")

      val child = call(mcp, ServeUiBuilderMcp.GET_LINKS, """{"designId":"alt"}""")
      val of = assertNotNull(child[BRANCH_OF_KEY], child.toString()).jsonObject
      assertEquals(DESIGN, of.string("parentDesignId"))
      assertEquals("0", of.string("forkRevision"))
      assertEquals("Alt", of.string("name"))
      assertNull(child[FORKED_FROM_KEY], "a branch is not a recorded fork: $child")

      val parent = call(mcp, ServeUiBuilderMcp.GET_LINKS, """{"designId":"$DESIGN"}""")
      assertEquals(
        listOf("alt"),
        parent[BRANCHES_KEY]!!.jsonArray.map { it.jsonObject.string("branchId") },
      )
      assertNull(parent[BRANCH_OF_KEY], parent.toString())
    }

  @Test
  fun `a grant scoped to a design reaches its branches and nothing else`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      create(mcp, designId = "other")
      branch(mcp, "Agent's try", branchId = "try")

      val lookup = ServeUiBuilderGrantScope.Lookup { _, _ -> setOf(DESIGN) }
      // The service is all three ports; name the one each limit is for.
      val port: UiBuilderServicePort = service
      val branchPort: UiBuilderBranchPort = service
      val parents = ServeUiBuilderGrantScope.parentsOf(branchPort)
      val scoped = ServeUiBuilderGrantScope.limit(port, lookup, parents)
      val scopedBranches = ServeUiBuilderGrantScope.limit(branchPort, lookup)
      val agent = AuthenticatedUiBuilderActor("agent:holder", onBehalfOfActorId = owner.actorId)

      // The branch is not named by the grant, but its parent is.
      assertIs<UiBuilderServiceResponse.Snapshot>(
        scoped.execute(UiBuilderServiceCall(agent, UiBuilderServiceRequest.OpenDesign("try")))
      )
      // An unrelated design of the same approver is not reachable.
      assertIs<UiBuilderServiceResponse.Error>(
        scoped.execute(UiBuilderServiceCall(agent, UiBuilderServiceRequest.OpenDesign("other")))
      )
      // The branch lane: branch the named design, merge its branch; not the other design.
      assertIs<UiBuilderBranchResponse.Branch>(
        scopedBranches.executeBranch(
          UiBuilderBranchCall(agent, UiBuilderBranchRequest.CreateBranch(DESIGN, "Second"))
        )
      )
      assertIs<UiBuilderBranchResponse.Merge>(
        scopedBranches.executeBranch(
          UiBuilderBranchCall(agent, UiBuilderBranchRequest.MergeBranch("try", dryRun = true))
        )
      )
      assertIs<UiBuilderBranchResponse.Error>(
        scopedBranches.executeBranch(
          UiBuilderBranchCall(agent, UiBuilderBranchRequest.CreateBranch("other", "Nope"))
        )
      )
      // Without the parent lookup the branch is just another unnamed design.
      val unaware = ServeUiBuilderGrantScope.limit(port, lookup)
      assertIs<UiBuilderServiceResponse.Error>(
        unaware.execute(UiBuilderServiceCall(agent, UiBuilderServiceRequest.OpenDesign("try")))
      )
    }

  @Test
  fun `the branch tools exist only where the host keeps branches, with closed output schemas`() {
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
    assertTrue(names(true).containsAll(ServeUiBuilderBranchTools.TOOL_NAMES))
    assertTrue(names(false).none { it in ServeUiBuilderBranchTools.TOOL_NAMES })
    val without = ServeUiBuilderMcp(service)
    assertFalse(without.supportsBranches)
    assertTrue(ServeUiBuilderBranchTools.TOOL_NAMES.all { without.capabilityFor(it) == null })
    val with = mcp()
    assertTrue(with.supportsBranches)
    assertEquals(
      UiBuilderRouteCapability.READ,
      with.capabilityFor(ServeUiBuilderBranchTools.LIST_BRANCHES),
    )
    listOf(
        ServeUiBuilderBranchTools.BRANCH_DESIGN,
        ServeUiBuilderBranchTools.MERGE_BRANCH,
        ServeUiBuilderBranchTools.ARCHIVE_BRANCH,
      )
      .forEach { assertEquals(UiBuilderRouteCapability.WRITE, with.capabilityFor(it)) }
    ServeUiBuilderBranchTools.TOOL_NAMES.forEach { name ->
      val schema = assertNotNull(ServeUiBuilderBranchTools.outputSchema(name))
      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun mcp(links: ServeUiBuilderLinksStore? = null): ServeUiBuilderMcp =
    ServeUiBuilderMcp(service, links = links, branches = service)

  private suspend fun call(
    mcp: ServeUiBuilderMcp,
    tool: String,
    arguments: String,
    actor: AuthenticatedUiBuilderActor = owner,
  ): JsonObject {
    val text = mcp.call(tool, Json.parseToJsonElement(arguments).jsonObject, actor, callId = tool)
    val reply = Json.parseToJsonElement(text).jsonObject
    (ServeUiBuilderBranchTools.outputSchema(tool) ?: ServeUiBuilderHistoryTools.outputSchema(tool))
      ?.let { schema ->
        val errors = SchemaCheck(schema).errors(reply)
        assertTrue(errors.isEmpty(), "$tool reply breaks its output schema: $errors in $reply")
      }
    return reply
  }

  /** `ui_builder_branch_design` on [DESIGN]; the reply's `branch`. */
  private suspend fun branch(mcp: ServeUiBuilderMcp, name: String, branchId: String): JsonObject {
    val reply =
      call(
        mcp,
        ServeUiBuilderBranchTools.BRANCH_DESIGN,
        """{"designId":"$DESIGN","name":"$name","branchId":"$branchId"}""",
      )
    assertTrue(reply.string("summary").contains("Branched $DESIGN"), reply.toString())
    return reply["branch"]!!.jsonObject
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
        owner,
        callId = "create",
      )
    assertIs<SnapshotResponseV1>(envelope(text).response, text)
  }

  private suspend fun apply(
    mcp: ServeUiBuilderMcp,
    designId: String,
    baseRevision: Long,
    mutation: DesignMutationV1,
    operationId: String = "op-$designId-$baseRevision",
  ): AcceptedOutcomeV1 {
    val outcome = applyRaw(mcp, designId, baseRevision, mutation, operationId)
    return assertIs<AcceptedOutcomeV1>(outcome, outcome.toString())
  }

  /** The apply's outcome, or null when the service answered with an error envelope. */
  private suspend fun applyRaw(
    mcp: ServeUiBuilderMcp,
    designId: String,
    baseRevision: Long,
    mutation: DesignMutationV1,
    operationId: String = "op-$designId-$baseRevision",
  ): Any? {
    val operations =
      json.encodeToString(ListSerializer(DesignMutationV1.serializer()), listOf(mutation))
    val text =
      mcp.call(
        ServeUiBuilderMcp.APPLY,
        Json.parseToJsonElement(
            """{"designId":"$designId","operationId":"$operationId","baseRevision":$baseRevision,"operations":$operations}"""
          )
          .jsonObject,
        owner,
        callId = "apply",
      )
    return when (val response = envelope(text).response) {
      is OperationOutcomeResponseV1 -> response.outcome
      is ErrorResponseV1 -> null
      else -> error("unexpected apply reply: $text")
    }
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

  private suspend fun document(designId: String): DesignDocumentV1 =
    assertIs<UiBuilderServiceResponse.Snapshot>(
        service.execute(
          UiBuilderServiceCall(owner, UiBuilderServiceRequest.GetSnapshot(designId, null))
        )
      )
      .snapshot
      .state
      .document

  private suspend fun currentRevision(designId: String): Long = document(designId).revision

  private fun envelope(text: String) =
    json.decodeFromString(McpResponseEnvelopeV1.serializer(), text)

  private fun kotlinx.serialization.json.JsonElement.string(): String = jsonPrimitive.content

  private fun JsonObject.string(name: String): String = this[name]!!.jsonPrimitive.content

  private fun setText(nodeId: String, value: String): DesignMutationV1 =
    SetPropertyMutationV1(nodeId, "text", StringValueV1(value))

  private fun text(id: String, value: String) =
    DesignNodeV1(
      id = id,
      componentId = "m3/text",
      properties = mapOf("text" to StringValueV1(value)),
    )

  /** A column of three texts, pinned to the packaged M3 catalog. */
  private fun fixture(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN,
      title = "Branch screen",
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
    const val DESIGN = "branch-screen"
  }
}
