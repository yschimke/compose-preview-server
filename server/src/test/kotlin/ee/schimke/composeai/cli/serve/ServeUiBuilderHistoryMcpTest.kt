package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceLimits
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

/**
 * `ui_builder_list_revisions`, `ui_builder_restore_revision`, `ui_builder_fork_design` and
 * `ui_builder_diff_designs` (compose-preview-server#1256) against a real persistent service, driven
 * through [ServeUiBuilderMcp] exactly as the MCP surface drives it — every reply is also held to
 * the `outputSchema` the tool declares.
 */
class ServeUiBuilderHistoryMcpTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")
  private val stranger = AuthenticatedUiBuilderActor("github:stranger")

  // ---- list_revisions --------------------------------------------------------------------------

  @Test
  fun `list revisions names each revision's actor, operation and the service's own digest`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      val first = apply(mcp, 0, setText("a", "Welcome"))
      val second = apply(mcp, 1, insert("extra", "Later"))

      val reply = call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN"}""")

      assertEquals(2L, reply["currentRevision"]!!.jsonPrimitive.content.toLong())
      val rows = reply["revisions"]!!.jsonArray.map { it.jsonObject }
      assertEquals(listOf(2L, 1L, 0L), rows.map { it["revision"]!!.jsonPrimitive.content.toLong() })
      assertEquals("true", rows[0]["current"]!!.jsonPrimitive.content)
      assertEquals("github:owner", rows[0]["actorId"]!!.jsonPrimitive.content)
      assertEquals(
        "insertNode",
        rows[0]["operation"]!!.jsonObject["mutations"]!!.jsonArray.single().jsonPrimitive.content,
      )
      assertEquals(
        "setProperty",
        rows[1]["operation"]!!.jsonObject["summary"]!!.jsonPrimitive.content,
      )
      assertNull(rows[2]["operation"], "creation has no operation: ${rows[2]}")
      // The digest is the hash the service itself committed, not a second opinion of one.
      assertEquals(second.documentHash, rows[0]["documentDigest"]!!.jsonPrimitive.content)
      assertEquals(first.documentHash, rows[1]["documentDigest"]!!.jsonPrimitive.content)
      val retention = reply["retention"]!!.jsonObject
      assertEquals("0", retention["oldestRetainedRevision"]!!.jsonPrimitive.content)
      assertEquals("128", retention["maximumRevisions"]!!.jsonPrimitive.content)
      assertEquals("2097152", retention["maximumRevisionBytes"]!!.jsonPrimitive.content)
      assertEquals("1024", retention["retainedOperations"]!!.jsonPrimitive.content)
      assertTrue(reply["summary"]!!.jsonPrimitive.content.contains("r1 by github:owner"))

      // Paging: one at a time, then the next older one.
      val page =
        call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN","limit":1}""")
      assertEquals("2", page["nextBefore"]!!.jsonPrimitive.content)
      val older =
        call(
          mcp,
          ServeUiBuilderHistoryTools.LIST_REVISIONS,
          """{"designId":"$DESIGN","limit":1,"before":2}""",
        )
      assertEquals(
        "1",
        older["revisions"]!!.jsonArray.single().jsonObject["revision"]!!.jsonPrimitive.content,
      )
    }

  @Test
  fun `list revisions refuses a stranger as if the design did not exist`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      val refused =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.LIST_REVISIONS,
            """{"designId":"$DESIGN"}""",
            stranger,
          )
        }
      assertEquals("no design `$DESIGN` this actor can read", refused.message)
      assertFailsWith<McpRequestException> {
        call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN","limit":0}""")
      }
    }

  @Test
  fun `a revision past retention is a clear error naming the floor, and a future one says so`() =
    runBlocking<Unit> {
      val mcp =
        mcp(
          limits =
            UiBuilderServiceLimits(
              retainedRevisionSnapshots = 2,
              minimumRetainedRevisionSnapshots = 2,
            )
        )
      create(mcp)
      apply(mcp, 0, setText("a", "One"))
      apply(mcp, 1, setText("a", "Two"))
      apply(mcp, 2, setText("a", "Three"))

      val listed =
        call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN"}""")
      val floor = listed["retention"]!!.jsonObject["oldestRetainedRevision"]!!.jsonPrimitive.content
      assertTrue(floor.toLong() > 0, listed.toString())

      val gone =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.DIFF_DESIGNS,
            """{"a":{"designId":"$DESIGN","revision":0}}""",
          )
        }
      assertTrue(gone.message!!.contains("past the retention floor"), gone.message)
      assertTrue(gone.message!!.contains("oldest retained revision is $floor"), gone.message)

      val restore =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.RESTORE_REVISION,
            """{"designId":"$DESIGN","revision":0,"baseRevision":3}""",
          )
        }
      assertTrue(restore.message!!.contains("past the retention floor"), restore.message)
      val fork =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.FORK_DESIGN,
            """{"designId":"$DESIGN","revision":0}""",
          )
        }
      assertTrue(fork.message!!.contains("no longer retained"), fork.message)

      val future =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.DIFF_DESIGNS,
            """{"a":{"designId":"$DESIGN","revision":99}}""",
          )
        }
      assertEquals(
        "revision 99 of `$DESIGN` does not exist: the current revision is 3",
        future.message,
      )
      val negative =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.RESTORE_REVISION,
            """{"designId":"$DESIGN","revision":-1,"baseRevision":3}""",
          )
        }
      assertTrue(negative.message!!.contains("does not exist"), negative.message)
    }

  // ---- restore ---------------------------------------------------------------------------------

  @Test
  fun `a dry run returns the diff and writes nothing, and the restore goes forward`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      apply(mcp, 0, setText("a", "Changed"))
      apply(mcp, 1, insert("extra", "Added"))

      val dry =
        call(
          mcp,
          ServeUiBuilderHistoryTools.RESTORE_REVISION,
          """{"designId":"$DESIGN","revision":0,"baseRevision":2,"dryRun":true}""",
        )
      assertEquals("false", dry["applied"]!!.jsonPrimitive.content)
      assertEquals("false", dry["stale"]!!.jsonPrimitive.content)
      val diff = dry["diff"]!!.jsonObject
      assertEquals(
        listOf("extra" to "removed", "a" to "changed"),
        diff["nodes"]!!.jsonArray.map {
          it.jsonObject["nodeId"]!!.jsonPrimitive.content to
            it.jsonObject["change"]!!.jsonPrimitive.content
        },
      )
      assertEquals(2L, currentRevision(mcp), "a dry run writes nothing")

      val staleDry =
        call(
          mcp,
          ServeUiBuilderHistoryTools.RESTORE_REVISION,
          """{"designId":"$DESIGN","revision":0,"baseRevision":1,"dryRun":true}""",
        )
      assertEquals("true", staleDry["stale"]!!.jsonPrimitive.content)

      val stale =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.RESTORE_REVISION,
            """{"designId":"$DESIGN","revision":0,"baseRevision":1}""",
          )
        }
      assertTrue(stale.message!!.startsWith("stale:"), stale.message)
      assertEquals(2L, currentRevision(mcp))

      val restored =
        call(
          mcp,
          ServeUiBuilderHistoryTools.RESTORE_REVISION,
          """{"designId":"$DESIGN","revision":0,"baseRevision":2}""",
        )
      assertEquals("true", restored["applied"]!!.jsonPrimitive.content)
      assertEquals("3", restored["committedRevision"]!!.jsonPrimitive.content)
      // Forward: the restored document is revision 0's content, under a new revision.
      val listed =
        call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN"}""")
      val rows = listed["revisions"]!!.jsonArray.map { it.jsonObject }
      assertEquals(
        "restore",
        rows.first()["operation"]!!.jsonObject["kind"]!!.jsonPrimitive.content,
      )
      assertEquals(restored["documentDigest"], rows.first()["documentDigest"])
      val again =
        call(
          mcp,
          ServeUiBuilderHistoryTools.DIFF_DESIGNS,
          """{"a":{"designId":"$DESIGN","revision":0},"b":{"designId":"$DESIGN"}}""",
        )
      assertEquals("true", again["identical"]!!.jsonPrimitive.content)
    }

  @Test
  fun `restore takes the design's own write action, dry run included`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      apply(mcp, 0, setText("a", "Changed"))
      share(mcp, viewer.actorId, "viewer")

      // The viewer can see the history…
      call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN"}""", viewer)
      // …and restore none of it, not even as a dry run.
      listOf(true, false).forEach { dryRun ->
        val refused =
          assertFailsWith<McpRequestException> {
            call(
              mcp,
              ServeUiBuilderHistoryTools.RESTORE_REVISION,
              """{"designId":"$DESIGN","revision":0,"baseRevision":1,"dryRun":$dryRun}""",
              viewer,
            )
          }
        assertEquals("design `$DESIGN` does not grant this actor write access", refused.message)
      }
      assertFailsWith<McpRequestException> {
        call(
          mcp,
          ServeUiBuilderHistoryTools.RESTORE_REVISION,
          """{"designId":"$DESIGN","revision":0,"baseRevision":1}""",
          stranger,
        )
      }
      assertEquals(
        UiBuilderRouteCapability.WRITE,
        mcp.capabilityFor(ServeUiBuilderHistoryTools.RESTORE_REVISION),
      )
      assertEquals(
        UiBuilderRouteCapability.READ,
        mcp.capabilityFor(ServeUiBuilderHistoryTools.LIST_REVISIONS),
      )
    }

  // ---- fork ------------------------------------------------------------------------------------

  @Test
  fun `a fork records its parent, and the parent lists the forks its reader may open`() =
    runBlocking<Unit> {
      val links = ServeUiBuilderLinksStore(stateDirectory.resolve("links"))
      val mcp = mcp(links = links)
      create(mcp)
      val committed = apply(mcp, 0, setText("a", "Forked here"))
      apply(mcp, 1, setText("a", "Moved on"))

      val forked =
        call(
          mcp,
          ServeUiBuilderHistoryTools.FORK_DESIGN,
          """{"designId":"$DESIGN","revision":1,"title":"Alternative","newDesignId":"alt"}""",
        )
      assertEquals("alt", forked["designId"]!!.jsonPrimitive.content)
      assertEquals("Alternative", forked["title"]!!.jsonPrimitive.content)
      assertEquals("true", forked["ancestryRecorded"]!!.jsonPrimitive.content)
      assertNull(forked["home"], "a fork never takes its parent's home: $forked")
      val point = forked["forkedFrom"]!!.jsonObject
      assertEquals(DESIGN, point["designId"]!!.jsonPrimitive.content)
      assertEquals("1", point["revision"]!!.jsonPrimitive.content)
      assertEquals(committed.documentHash, point["documentDigest"]!!.jsonPrimitive.content)

      // The round trip through get_links, both ways.
      val childLinks = linksOf(mcp, "alt")
      assertEquals(point, childLinks[FORKED_FROM_KEY])
      val parentLinks = linksOf(mcp, DESIGN)
      assertEquals(
        listOf("alt"),
        parentLinks[FORKS_KEY]!!.jsonArray.map {
          it.jsonObject["designId"]!!.jsonPrimitive.content
        },
      )
      // The fork is the parent's content at that revision, and a design of its own.
      val diff =
        call(
          mcp,
          ServeUiBuilderHistoryTools.DIFF_DESIGNS,
          """{"a":{"designId":"$DESIGN","revision":1},"b":{"designId":"alt"}}""",
        )
      assertEquals(
        listOf("title"),
        diff["document"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content },
      )
      assertTrue(diff["nodes"]!!.jsonArray.isEmpty(), diff.toString())

      // A viewer may fork what they can read; the fork is theirs. Each reader of the parent is
      // told only of the forks they may open themselves.
      share(mcp, viewer.actorId, "viewer")
      val viewerFork =
        call(mcp, ServeUiBuilderHistoryTools.FORK_DESIGN, """{"designId":"$DESIGN"}""", viewer)
      val viewerForkId = viewerFork["designId"]!!.jsonPrimitive.content
      assertTrue(viewerForkId.startsWith("$DESIGN-r2-"), viewerForkId)
      assertEquals(
        listOf("alt"),
        linksOf(mcp, DESIGN)[FORKS_KEY]!!.jsonArray.map {
          it.jsonObject["designId"]!!.jsonPrimitive.content
        },
      )
      assertEquals(
        listOf(viewerForkId),
        linksOf(mcp, DESIGN, viewer)[FORKS_KEY]!!.jsonArray.map {
          it.jsonObject["designId"]!!.jsonPrimitive.content
        },
      )

      // An id that is taken is refused, and a stranger cannot fork what they cannot read.
      val taken =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.FORK_DESIGN,
            """{"designId":"$DESIGN","newDesignId":"alt"}""",
          )
        }
      assertTrue(taken.message!!.contains("already taken"), taken.message)
      val strangerFork =
        assertFailsWith<McpRequestException> {
          call(mcp, ServeUiBuilderHistoryTools.FORK_DESIGN, """{"designId":"$DESIGN"}""", stranger)
        }
      assertEquals("no design `$DESIGN` this actor can read", strangerFork.message)

      // Deleting the fork takes it off its parent's list.
      call(mcp, ServeUiBuilderMcp.DELETE_DESIGN, """{"designId":"alt"}""", raw = true)
      assertNull(links.ancestry.read("alt"))
      assertNull(linksOf(mcp, DESIGN)[FORKS_KEY])
    }

  @Test
  fun `a fork on a host without links says its ancestry was not kept`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      val forked = call(mcp, ServeUiBuilderHistoryTools.FORK_DESIGN, """{"designId":"$DESIGN"}""")
      assertEquals("false", forked["ancestryRecorded"]!!.jsonPrimitive.content)
      assertEquals(DESIGN, forked["forkedFrom"]!!.jsonObject["designId"]!!.jsonPrimitive.content)
    }

  // ---- diff ------------------------------------------------------------------------------------

  @Test
  fun `diff finds added, removed, moved and changed nodes with their paths`() {
    val base = fixture()
    val after =
      base.copy(
        title = "Renamed",
        nodes =
          base.nodes - "c" +
            mapOf(
              // `a` changes a property; `b` moves into the row; `d` is new.
              "a" to text("a", "Changed"),
              "column" to
                DesignNodeV1(
                  id = "column",
                  componentId = "layout/column",
                  slots = mapOf("children" to listOf("d", "a", "row")),
                ),
              "row" to
                DesignNodeV1(
                  id = "row",
                  componentId = "layout/row",
                  slots = mapOf("children" to listOf("b")),
                ),
              "d" to text("d", "New"),
            ),
      )

    val diff = UiBuilderDocumentDiff.diff(base, after)

    assertFalse(diff.identical)
    assertEquals(
      DesignDiffCountsV1(added = 1, removed = 1, moved = 1, changed = 1, document = 1),
      diff.counts,
    )
    val byId = diff.nodes.associateBy { it.nodeId }
    assertEquals("added", byId.getValue("d").change)
    assertEquals("column/children[0]/d", byId.getValue("d").path)
    assertEquals("removed", byId.getValue("c").change)
    assertEquals("column/children[2]/c", byId.getValue("c").path)
    val moved = byId.getValue("b")
    assertEquals("moved", moved.change)
    assertEquals("column/children[2]/row/children[0]/b", moved.path)
    assertEquals("column/children[1]/b", moved.beforePath)
    assertEquals(DesignNodeLocationV1("column", "children", 1), moved.from)
    assertEquals(DesignNodeLocationV1("row", "children", 0), moved.to)
    val changed = byId.getValue("a")
    assertEquals("changed", changed.change)
    assertEquals(
      listOf("text"),
      changed.properties.map { it.name },
    )
    assertEquals(
      """{"type":"string","value":"Changed"}""",
      changed.properties.single().after.toString(),
    )
    // `a` shifted from index 0 to 1 because `d` was inserted before it: not a move.
    assertFalse("a" in diff.nodes.filter { it.change == "moved" }.map { it.nodeId })
    // Neither the column nor the row is reported: a slot list changing is its children's news.
    assertNull(byId["column"])
    assertNull(byId["row"])
    assertEquals(listOf("title"), diff.document.map { it.name })
    assertTrue(diff.summary.contains("1 added; 1 removed; 1 moved; 1 changed"), diff.summary)
    assertTrue(diff.summary.contains("+ d (m3/text) at column/children[0]/d"), diff.summary)
  }

  @Test
  fun `diff reports a reorder as a move only for the node that moved`() {
    val base = fixture()
    val reordered =
      base.copy(
        nodes =
          base.nodes +
            ("column" to
              DesignNodeV1(
                id = "column",
                componentId = "layout/column",
                slots = mapOf("children" to listOf("c", "a", "b", "row")),
              ))
      )
    val diff = UiBuilderDocumentDiff.diff(base, reordered)
    assertEquals(listOf("c"), diff.nodes.map { it.nodeId })
    assertEquals("moved", diff.nodes.single().change)
    assertEquals(DesignNodeLocationV1("column", "children", 2), diff.nodes.single().from)
    assertEquals(DesignNodeLocationV1("column", "children", 0), diff.nodes.single().to)
  }

  @Test
  fun `identical content is identical whatever the ids and revisions`() {
    val base = fixture()
    val copy = base.copy(id = "other", revision = 9, updatedAtEpochMillis = 1L)
    val diff = UiBuilderDocumentDiff.diff(base, copy)
    assertTrue(diff.identical, diff.toString())
    // The digest is the service's: it covers the id and revision, so the two still differ.
    assertFalse(diff.a.documentDigest == diff.b.documentDigest)
    assertTrue(diff.summary.endsWith("identical content."), diff.summary)
  }

  @Test
  fun `diff of two designs needs read on both`() =
    runBlocking<Unit> {
      val mcp = mcp()
      create(mcp)
      create(mcp, actor = stranger, designId = "theirs")
      val refused =
        assertFailsWith<McpRequestException> {
          call(
            mcp,
            ServeUiBuilderHistoryTools.DIFF_DESIGNS,
            """{"a":{"designId":"$DESIGN"},"b":{"designId":"theirs"}}""",
          )
        }
      assertEquals("no design `theirs` this actor can read", refused.message)
      assertEquals(
        UiBuilderRouteCapability.READ,
        mcp.capabilityFor(ServeUiBuilderHistoryTools.DIFF_DESIGNS),
      )
      assertEquals(
        UiBuilderRouteCapability.WRITE,
        mcp.capabilityFor(ServeUiBuilderHistoryTools.FORK_DESIGN),
      )
    }

  @Test
  fun `the four tools are always declared, with closed output schemas`() {
    val declared =
      ServeUiBuilderMcp.declarations({ name, description, schema ->
        kotlinx.serialization.json.buildJsonObject {
          put("name", kotlinx.serialization.json.JsonPrimitive(name))
          put("description", kotlinx.serialization.json.JsonPrimitive(description))
          put("inputSchema", Json.parseToJsonElement(schema))
        }
      })
    val names = declared.map { it["name"]!!.jsonPrimitive.content }
    assertTrue(names.containsAll(ServeUiBuilderHistoryTools.TOOL_NAMES), names.toString())
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.containsAll(ServeUiBuilderHistoryTools.TOOL_NAMES))
    ServeUiBuilderHistoryTools.TOOL_NAMES.forEach { name ->
      val schema = assertNotNull(ServeUiBuilderHistoryTools.outputSchema(name))
      assertEquals("object", schema["type"]!!.jsonPrimitive.content)
      assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private fun mcp(
    links: ServeUiBuilderLinksStore? = null,
    limits: UiBuilderServiceLimits = UiBuilderServiceLimits(),
  ): ServeUiBuilderMcp =
    ServeUiBuilderMcp(
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory.resolve("state")),
        catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter = { error("this test never exports") },
        limits = limits,
      ),
      links = links,
    )

  /**
   * One tool call as [actor], decoded; a history reply is also checked against the `outputSchema`
   * its tool declares.
   */
  private suspend fun call(
    mcp: ServeUiBuilderMcp,
    tool: String,
    arguments: String,
    actor: AuthenticatedUiBuilderActor = owner,
    raw: Boolean = false,
  ): JsonObject {
    val text = mcp.call(tool, Json.parseToJsonElement(arguments).jsonObject, actor, callId = tool)
    val reply = Json.parseToJsonElement(text).jsonObject
    if (!raw) {
      ServeUiBuilderHistoryTools.outputSchema(tool)?.let { schema ->
        val errors = SchemaCheck(schema).errors(reply)
        assertTrue(errors.isEmpty(), "$tool reply breaks its output schema: $errors in $reply")
      }
    }
    return reply
  }

  private suspend fun create(
    mcp: ServeUiBuilderMcp,
    actor: AuthenticatedUiBuilderActor = owner,
    designId: String = DESIGN,
  ) {
    val document = json.encodeToString(DesignDocumentV1.serializer(), fixture().copy(id = designId))
    val text =
      mcp.call(
        ServeUiBuilderMcp.CREATE_DESIGN,
        Json.parseToJsonElement(
            """{"designId":"$designId","includeCatalog":true,"document":$document}"""
          )
          .jsonObject,
        actor,
        callId = "create",
      )
    assertIs<SnapshotResponseV1>(envelope(text).response, text)
  }

  private suspend fun apply(
    mcp: ServeUiBuilderMcp,
    baseRevision: Long,
    mutation: DesignMutationV1,
  ): AcceptedOutcomeV1 {
    val operations =
      json.encodeToString(ListSerializer(DesignMutationV1.serializer()), listOf(mutation))
    val text =
      mcp.call(
        ServeUiBuilderMcp.APPLY,
        Json.parseToJsonElement(
            """{"designId":"$DESIGN","operationId":"op-$baseRevision","baseRevision":$baseRevision,"operations":$operations}"""
          )
          .jsonObject,
        owner,
        callId = "apply",
      )
    val outcome = assertIs<OperationOutcomeResponseV1>(envelope(text).response, text)
    return assertIs<AcceptedOutcomeV1>(outcome.outcome, text)
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

  private suspend fun linksOf(
    mcp: ServeUiBuilderMcp,
    designId: String,
    actor: AuthenticatedUiBuilderActor = owner,
  ): JsonObject = call(mcp, ServeUiBuilderMcp.GET_LINKS, """{"designId":"$designId"}""", actor)

  private suspend fun currentRevision(mcp: ServeUiBuilderMcp): Long =
    call(mcp, ServeUiBuilderHistoryTools.LIST_REVISIONS, """{"designId":"$DESIGN"}""")[
        "currentRevision"]!!
      .jsonPrimitive
      .content
      .toLong()

  private fun envelope(text: String) =
    json.decodeFromString(McpResponseEnvelopeV1.serializer(), text)

  private fun setText(nodeId: String, value: String): DesignMutationV1 =
    SetPropertyMutationV1(nodeId, "text", StringValueV1(value))

  private fun insert(nodeId: String, value: String): DesignMutationV1 =
    InsertNodeMutationV1(
      node = text(nodeId, value),
      location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
    )

  private fun text(id: String, value: String) =
    DesignNodeV1(
      id = id,
      componentId = "m3/text",
      properties = mapOf("text" to StringValueV1(value)),
    )

  /** A column of three texts and an empty row, pinned to the packaged M3 catalog. */
  private fun fixture(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN,
      title = "History screen",
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
              slots = mapOf("children" to listOf("a", "b", "c", "row")),
            ),
          "a" to text("a", "Alpha"),
          "b" to text("b", "Beta"),
          "c" to text("c", "Gamma"),
          "row" to DesignNodeV1(id = "row", componentId = "layout/row"),
        ),
    )

  private companion object {
    const val CATALOG = "m3-catalog"
    const val DESIGN = "history-screen"
  }
}
