package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessResponseV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.DesignsResponseV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.ExportResponseV1
import ee.schimke.composeai.uibuilder.protocol.InsertNodeMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.NodeLocationV1
import ee.schimke.composeai.uibuilder.protocol.NullValueV1
import ee.schimke.composeai.uibuilder.protocol.OperationOutcomeResponseV1
import ee.schimke.composeai.uibuilder.protocol.ParentSlotV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.SetPropertyMutationV1
import ee.schimke.composeai.uibuilder.protocol.SnapshotResponseV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import java.io.File
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * A design authored, edited and exported entirely over MCP.
 *
 * ## Why an integration test rather than unit ones
 *
 * Every seam this change adds is a wiring seam, and each of them fails silently in a unit test:
 * that the tools appear in `tools/list` only when a service is configured, that a tool name routes
 * to the UI-builder door rather than falling through to the catalog surface, that the capability
 * checked is the one the HTTP routes check, that the actor reaching `UiBuilderProtocolMapper` is
 * the authenticated one rather than a field from the message, and that the reply is the released
 * [McpResponseEnvelopeV1]. A mock of the service port proves the JSON and none of that.
 *
 * So this starts the real server, wired the way `ServeRunner` wires it, and asks it as an agent
 * would: list, create, read, mutate, export — with the export's Kotlin as the last assertion,
 * because that is the whole point of the door existing.
 */
class ServeUiBuilderMcpIntegrationTest {
  @TempDir lateinit var stateDirectory: Path

  private val json = Json {
    encodeDefaults = true
    explicitNulls = false
  }
  private val client = OkHttpClient()
  private var running: RunningServer? = null

  @AfterTest
  fun tearDown() {
    running?.close()
  }

  @Test
  fun `an agent creates, edits and exports a design without touching a browser`() {
    val server = start()

    // 1. What can be pinned. An agent that skipped this would be guessing a catalog revision, and
    // the service checks it.
    val catalogs = envelope(server, ServeUiBuilderMcp.LIST_CATALOGS)
    assertTrue(catalogs.contains(CATALOG_SYSTEM_ID), catalogs)

    // 2. A design. `includeCatalog` because this test decodes the reply as the released shape,
    // whose snapshot carries the catalog; the default leaves it out, and the test below is about
    // that.
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"agent-screen","includeCatalog":true,"document":${json.encodeToString(DesignDocumentV1.serializer(), document())}}""",
      )
    assertIs<SnapshotResponseV1>(response(created))

    // 3. What revision to quote. Read back rather than assumed: `baseRevision` is how a concurrent
    // edit is detected, and an agent that guessed it would be the concurrent edit.
    val snapshot =
      assertIs<SnapshotResponseV1>(
        response(
          envelope(
            server,
            ServeUiBuilderMcp.GET_DESIGN,
            """{"designId":"agent-screen","includeCatalog":true}""",
          )
        )
      )
    assertEquals("agent-screen", snapshot.snapshot.designId)
    val revision = snapshot.snapshot.state.document.revision

    // 4. The edit: a second text in the column, which is "add a component to a container" — the
    // thing the browser builder does by dragging.
    val operations =
      json.encodeToString(
        ListSerializer(DesignMutationV1.serializer()),
        listOf(
          InsertNodeMutationV1(
            node =
              DesignNodeV1(
                id = "subtitle",
                componentId = "m3/text",
                properties = mapOf("text" to StringValueV1("Two sessions today")),
              ),
            location = NodeLocationV1(parent = ParentSlotV1("column", "children")),
          )
        ),
      )
    val applied =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{"designId":"agent-screen","operationId":"agent-op-1","baseRevision":$revision,"operations":$operations}""",
      )
    val outcome = assertIs<OperationOutcomeResponseV1>(response(applied))
    // Accepted, not merely answered: a rejection also comes back as an outcome, so asserting the
    // response type alone would pass on a refused edit.
    val accepted = assertIs<AcceptedOutcomeV1>(outcome.outcome)
    assertEquals(revision + 1, accepted.committedRevision, applied)

    // 5. The Kotlin. Both texts, from the real generator against the configured record — the same
    // answer the browser's code pane shows for the same design.
    val exported =
      envelope(
        server,
        ServeUiBuilderMcp.EXPORT,
        """{"designId":"agent-screen","format":"compose"}""",
      )
    val artifact = assertIs<ExportResponseV1>(response(exported)).artifact
    assertEquals(emptyList(), artifact.diagnostics, artifact.content)
    assertTrue(artifact.content.contains("""Text(text = "Opening keynote""""), artifact.content)
    assertTrue(artifact.content.contains("""Text(text = "Two sessions today""""), artifact.content)
    assertTrue(artifact.content.contains("Column("), artifact.content)
  }

  @Test
  fun `MCP authors and reads the state and ordered actions edited by the browser`() {
    val server = start()
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"agent-screen","document":${json.encodeToString(DesignDocumentV1.serializer(), document())}}""",
    )
    val applied =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{
      "designId":"agent-screen","operationId":"wire-behavior","baseRevision":0,
      "operations":[
        {"type":"setStateVariable","name":"expanded","declaration":{"type":"value","valueType":"bool","initialValue":false,"nullable":false,"persistence":"preview"}},
        {"type":"setEventBinding","nodeId":"session","event":"click","actions":[{"type":"toggle","variable":"expanded"},{"type":"set","variable":"expanded","value":true}]}
      ]
    }""",
      )
    assertIs<AcceptedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(response(applied)).outcome)
    val snapshot =
      assertIs<SnapshotResponseV1>(
          response(
            envelope(
              server,
              ServeUiBuilderMcp.GET_DESIGN,
              """{"designId":"agent-screen","includeCatalog":true}""",
            )
          )
        )
        .snapshot
        .state
        .document
    assertEquals(
      false,
      snapshot.stateVariables
        .getValue("expanded")
        .initialValue
        .jsonPrimitive
        .content
        .toBooleanStrict(),
    )
    val actions = snapshot.nodes.getValue("session").eventBindings.getValue("click")
    assertEquals(2, actions.size)
    assertIs<ee.schimke.composeai.uibuilder.protocol.ToggleActionV1>(actions.first())
    assertIs<ee.schimke.composeai.uibuilder.protocol.SetValueActionV1>(actions.last())
    val refused =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{
      "designId":"agent-screen","operationId":"remove-used","baseRevision":1,
      "operations":[{"type":"removeStateVariable","name":"expanded"}]
    }""",
      )
    assertIs<RejectedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(response(refused)).outcome)
    val removed =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{
      "designId":"agent-screen","operationId":"clear-behavior","baseRevision":1,
      "operations":[{"type":"setEventBinding","nodeId":"session","event":"click","actions":[]},{"type":"removeStateVariable","name":"expanded"}]
    }""",
      )
    assertIs<AcceptedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(response(removed)).outcome)
  }

  @Test
  fun `an agent wires a button and exports its state and ordered handler over MCP`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
    val server =
      start(recordFile = File("../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"))
    val initial = document()
    val doc =
      initial.copy(
        nodes =
          initial.nodes +
            mapOf(
              "column" to
                initial.nodes
                  .getValue("column")
                  .copy(slots = mapOf("children" to listOf("button"))),
              "button" to
                DesignNodeV1(
                  "button",
                  "m3/button",
                  properties = mapOf("style" to StringValueV1("filled")),
                  slots = mapOf("content" to listOf("session")),
                ),
            )
      )
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"agent-screen","includeCatalog":true,"document":${json.encodeToString(DesignDocumentV1.serializer(), doc)}}""",
      )
    assertIs<SnapshotResponseV1>(response(created), created)
    val applied =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{
      "designId":"agent-screen","operationId":"wire-exportable-behavior","baseRevision":0,
      "operations":[
        {"type":"setStateVariable","name":"label","declaration":{"type":"value","valueType":"string","initialValue":"Ready","nullable":false,"persistence":"preview"}},
        {"type":"setProperty","nodeId":"session","property":"text","value":{"type":"state","variable":"label"}},
        {"type":"setEventBinding","nodeId":"button","event":"click","actions":[{"type":"set","variable":"label","value":"First"},{"type":"set","variable":"label","value":"Done"}]}
      ]
    }""",
      )
    assertIs<AcceptedOutcomeV1>(
      assertIs<OperationOutcomeResponseV1>(response(applied), applied).outcome,
      applied,
    )
    val exported =
      envelope(
        server,
        ServeUiBuilderMcp.EXPORT,
        """{"designId":"agent-screen","format":"compose"}""",
      )
    val artifact = assertIs<ExportResponseV1>(response(exported)).artifact
    assertEquals(emptyList(), artifact.diagnostics, artifact.content)
    assertTrue("mutableStateOf<kotlin.String>(\"Ready\")" in artifact.content, artifact.content)
    assertTrue("Text(text = label.value)" in artifact.content, artifact.content)
    assertTrue(
      "onClick = { label.value = \"First\"; label.value = \"Done\" }" in artifact.content,
      artifact.content,
    )
  }

  @Test
  fun `MCP exports stateful layout clicks as ordinary Compose modifiers`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
    val server =
      start(recordFile = File("../docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"))
    val doc =
      json
        .decodeFromString<DesignDocumentV1>(
          File("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json")
            .readText()
        )
        .copy(
          id = "agent-screen",
          title = "Clickable state layout",
          catalogPin = document().catalogPin,
        )
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"agent-screen","includeCatalog":true,"document":${json.encodeToString(DesignDocumentV1.serializer(), doc)}}""",
      )
    assertIs<SnapshotResponseV1>(response(created), created)
    val exported =
      envelope(
        server,
        ServeUiBuilderMcp.EXPORT,
        """{"designId":"agent-screen","revision":0,"format":"compose"}""",
      )
    val artifact = assertIs<ExportResponseV1>(response(exported)).artifact
    // Emitting is the released behaviour now that the shared generator carries action-lambda
    // support; the `VERIFY_LOCAL_LAYOUT_CLICKS` branch existed to prove this path against a local
    // generator publication before that release, and the diagnostics it fell back to asserted a
    // limitation that has lifted.
    assertEquals(emptyList(), artifact.diagnostics, artifact.content)
    assertTrue(
      artifact.content.endsWith(
        File("../docs/design/fixtures/ui-builder/clickable-state-layout.kt.txt").readText()
      ),
      artifact.content,
    )
  }

  @Test
  fun `MCP compiles unsaved document content without creating a design`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
    val format = ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport.documentFormat
    if (System.getenv("VERIFY_REMOTE_DOCUMENT_EXPORTS") == "true") assertNotNull(format)
    org.junit.jupiter.api.Assumptions.assumeTrue(format != null)
    val server = start(recordFile = null, catalogSystemId = "remote-m3", withRemoteExports = true)
    val doc =
      json
        .decodeFromString<DesignDocumentV1>(
          File("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json")
            .readText()
        )
        .copy(revision = 19)
    val result =
      response(
        envelope(
          server,
          ServeUiBuilderMcp.EXPORT_DOCUMENT,
          """{"document":${json.encodeToString(DesignDocumentV1.serializer(), doc)},"format":"rc"}""",
        )
      )
    val artifact = assertIs<ExportResponseV1>(result).artifact
    assertTrue(
      artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
      artifact.toString(),
    )
    assertEquals(format, artifact.format)
    assertTrue(artifact.content.isNotBlank())
    assertTrue(
      assertIs<DesignsResponseV1>(response(envelope(server, ServeUiBuilderMcp.LIST_DESIGNS)))
        .designs
        .isEmpty()
    )
  }

  @Test
  fun `MCP exports ordinary Remote roots without a component record or widget wrapper`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
    val server = start(recordFile = null, catalogSystemId = "remote-m3")
    val doc =
      json.decodeFromString<DesignDocumentV1>(
        File("../docs/design/evidence/ui-builder-live-document-preview/sample.document.json")
          .readText()
      )
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"${doc.id}","includeCatalog":true,"document":${json.encodeToString(DesignDocumentV1.serializer(), doc)}}""",
      )
    assertIs<SnapshotResponseV1>(response(created), created)
    val exported =
      envelope(
        server,
        ServeUiBuilderMcp.EXPORT,
        """{"designId":"${doc.id}","revision":0,"format":"compose"}""",
      )
    val artifact = assertIs<ExportResponseV1>(response(exported)).artifact
    assertEquals(emptyList(), artifact.diagnostics, artifact.content)
    assertTrue(
      artifact.content.endsWith(
        File("../docs/design/fixtures/ui-builder/remote-root.kt.txt").readText()
      ),
      artifact.content,
    )
    val changed =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{"designId":"${doc.id}","baseRevision":0,"operationId":"change-page","operations":[{"type":"setStateVariable","name":"page","declaration":{"type":"value","valueType":"int","initialValue":20,"persistence":"preview"}}]}""",
      )
    assertIs<AcceptedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(response(changed)).outcome)
    val next =
      assertIs<ExportResponseV1>(
          response(
            envelope(
              server,
              ServeUiBuilderMcp.EXPORT,
              """{"designId":"${doc.id}","revision":1,"format":"compose"}""",
            )
          )
        )
        .artifact
    assertEquals(emptyList(), next.diagnostics, next.content)
    assertTrue("val page = rememberMutableRemoteInt(20)" in next.content, next.content)
  }

  @Test
  fun `MCP discovers and authors the same state selection as the inspector`() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
      UiBuilderBuildFeatures.remoteCompose,
      "Enable with -PuiBuilderRemoteCompose=true",
    )
    val server = start()
    val original = document()
    val doc =
      original.copy(
        nodes =
          original.nodes +
            ("column" to original.nodes.getValue("column").copy(componentId = "layout/box"))
      )
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"agent-screen","includeCatalog":true,"document":${json.encodeToString(DesignDocumentV1.serializer(), doc)}}""",
      )
    assertIs<SnapshotResponseV1>(response(created), created)
    assertTrue("showByState" in created, created)
    val applied =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{
      "designId":"agent-screen","operationId":"select-child","baseRevision":0,
      "operations":[
        {"type":"setStateVariable","name":"page","declaration":{"type":"value","valueType":"int","initialValue":10,"nullable":false,"persistence":"preview"}},
        {"type":"setProperty","nodeId":"column","property":"showByState","value":{"type":"object","fields":{
          "selector":{"type":"state","variable":"page"},
          "cases":{"type":"object","fields":{"session":{"type":"int","value":10}}}
        }}}
      ]
    }""",
      )
    assertIs<AcceptedOutcomeV1>(
      assertIs<OperationOutcomeResponseV1>(response(applied), applied).outcome,
      applied,
    )
    val snapshot =
      assertIs<SnapshotResponseV1>(
          response(
            envelope(
              server,
              ServeUiBuilderMcp.GET_DESIGN,
              """{"designId":"agent-screen","includeCatalog":true}""",
            )
          )
        )
        .snapshot
        .state
        .document
    val selection =
      assertIs<ee.schimke.composeai.uibuilder.protocol.ObjectValueV1>(
        snapshot.nodes.getValue("column").properties.getValue("showByState")
      )
    assertEquals(
      "page",
      assertIs<ee.schimke.composeai.uibuilder.protocol.StateValueV1>(
          selection.fields.getValue("selector")
        )
        .variable,
    )
    val refused =
      envelope(
        server,
        ServeUiBuilderMcp.APPLY,
        """{
      "designId":"agent-screen","operationId":"remove-selector","baseRevision":1,
      "operations":[{"type":"removeStateVariable","name":"page"}]
    }""",
      )
    assertIs<RejectedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(response(refused)).outcome)
    assertTrue("showByState" in refused, refused)
  }

  @Test
  fun `an agent shares a design with somebody else and takes it back`() {
    val server = start()
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"shared-screen","document":${json.encodeToString(DesignDocumentV1.serializer(), document().copy(id = "shared-screen"))}}""",
    )

    // Nobody but the owner, to begin with. The owner here is `operator`, because that is the
    // identity the token this test presents resolves to.
    val before =
      assertIs<DesignAccessResponseV1>(
        response(
          envelope(server, ServeUiBuilderMcp.DESIGN_ACCESS, """{"designId":"shared-screen"}""")
        )
      )
    assertEquals("operator", before.access.ownerActorId)
    assertEquals(emptyList(), before.access.actorGrants)

    // The access revision is deliberately NOT an argument: the tool reads it, so an agent asked to
    // "share this with @colleague" can do exactly that in one call.
    val shared =
      assertIs<DesignAccessResponseV1>(
        response(
          envelope(
            server,
            ServeUiBuilderMcp.SHARE_DESIGN,
            """{"designId":"shared-screen","actorId":"github:colleague","role":"editor"}""",
          )
        )
      )
    val grant = shared.access.actorGrants.single()
    assertEquals("github:colleague", grant.actorId)
    assertEquals(DesignAccessRoleV1.EDITOR, grant.role)
    assertEquals(
      listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE, DesignAccessActionV1.EXPORT),
      grant.allowedActions,
      "an editor may not manage access: being shared with is not the power to share on",
    )

    val revoked =
      assertIs<DesignAccessResponseV1>(
        response(
          envelope(
            server,
            ServeUiBuilderMcp.SHARE_DESIGN,
            """{"designId":"shared-screen","actorId":"github:colleague","revoke":true}""",
          )
        )
      )
    assertEquals(emptyList(), revoked.access.actorGrants)
  }

  @Test
  fun `a snapshot leaves the catalog out unless asked, and is small`() {
    val server = start()
    val created =
      envelope(
        server,
        ServeUiBuilderMcp.CREATE_DESIGN,
        """{"designId":"agent-screen","document":${json.encodeToString(DesignDocumentV1.serializer(), document())}}""",
      )
    val read = envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")
    for (reply in listOf(created, read)) {
      val snapshot =
        Json.parseToJsonElement(reply).jsonObject["response"]!!.jsonObject["snapshot"]!!.jsonObject
      // Dropped, not blanked: an agent reading the reply sees an absence, and the document's own
      // `catalogPin` still names the catalog exactly.
      assertNull(snapshot["catalog"], reply)
      assertEquals(
        CATALOG_SYSTEM_ID,
        snapshot["state"]!!
          .jsonObject["document"]!!
          .jsonObject["catalogPin"]!!
          .jsonObject["systemId"]!!
          .jsonPrimitive
          .content,
      )
      // The whole point: a two-node design is a couple of KB, not sixty.
      assertTrue(reply.length < 4_000, "${reply.length} bytes: $reply")
    }

    // The released shape, byte for byte, when the caller wants it.
    val whole =
      envelope(
        server,
        ServeUiBuilderMcp.GET_DESIGN,
        """{"designId":"agent-screen","includeCatalog":true}""",
      )
    val snapshot = assertIs<SnapshotResponseV1>(response(whole)).snapshot
    assertEquals(CATALOG_SYSTEM_ID, snapshot.catalog.benchmark.catalogSystemId)
    assertTrue(whole.length > read.length * 5, "${whole.length} vs ${read.length}")
  }

  @Test
  fun `the catalog list is a summary carrying the pin, with the whole capability on request`() {
    val server = start()
    val summary =
      Json.parseToJsonElement(envelope(server, ServeUiBuilderMcp.LIST_CATALOGS)).jsonObject
    assertEquals(
      "compose-preview/ui-builder-catalog-summary/v1",
      summary["schema"]!!.jsonPrimitive.content,
    )
    val catalog = summary["catalogs"]!!.jsonArray.single().jsonObject
    assertEquals(CATALOG_SYSTEM_ID, catalog["systemId"]!!.jsonPrimitive.content)
    // The pin a document must carry — which the capability itself never spelled, so an agent
    // used to guess the digest. This is the one the test document below pins, and creating with
    // it succeeds.
    assertEquals(
      CatalogReferenceV1(CATALOG_SYSTEM_ID, "candidate", "candidate", "candidate"),
      json.decodeFromJsonElement(CatalogReferenceV1.serializer(), catalog["catalogPin"]!!),
    )
    val components = catalog["components"]!!.jsonArray.map { it.jsonObject }
    val text = components.single { it["id"]!!.jsonPrimitive.content == "m3/text" }
    val properties = text["properties"]!!.jsonArray.map { it.jsonPrimitive.content }
    // Required, and restricted, read off the row: what a mutation has to get right.
    assertTrue("text:string!" in properties, properties.toString())
    assertTrue(
      properties.any { it.startsWith("style:string=displayLarge|") },
      properties.toString(),
    )
    val column = components.single { it["id"]!!.jsonPrimitive.content == "layout/column" }
    val slots = column["slots"]!!.jsonArray.map { it.jsonPrimitive.content }
    assertTrue(slots.any { it.startsWith("children[0..*]") }, slots.toString())
    assertTrue("padding" in catalog["modifiers"]!!.jsonArray.map { it.jsonPrimitive.content })
    // What authoring does not need is not there.
    assertNull(text["wasm"])
    assertNull(text["svg"])
    assertNull(text["code"])
    // A generated inventory does not get spelled out here. `m3/icon`.`iconKey` is the complete
    // Material icon set since #710, and inlining it took this summary to 241 KB — four times the
    // full envelope it exists to be a cheap alternative to. It reports its size and where to get
    // the values; an agent that needs them asks for `full`, which is checked below.
    val icon = components.single { it["id"]!!.jsonPrimitive.content == "m3/icon" }
    val iconProperties = icon["properties"]!!.jsonArray.map { it.jsonPrimitive.content }
    val iconKey = iconProperties.single { it.startsWith("iconKey:") }
    assertTrue(
      Regex("""^iconKey:string!?=<\d{3,} values; ask for full>$""").matches(iconKey),
      iconKey,
    )
    // And a list a person authored is still spelled out — the cap is for inventories, not for
    // every enumeration. `m3/text`.`style` is fifteen typography roles and stays legible.
    assertTrue(
      properties.any { it.startsWith("style:string=displayLarge|") && it.endsWith("|labelSmall") },
      properties.toString(),
    )

    // The whole point: on this catalog the released envelope is about 58 KB and this about 12.
    val summaryBytes = summary.toString().length
    assertTrue(summaryBytes < 16_000, "$summaryBytes bytes: $summary")

    // `full` is the released envelope, with everything.
    val whole = envelope(server, ServeUiBuilderMcp.LIST_CATALOGS, """{"full":true}""")
    val listed = assertIs<CatalogsResponseV1>(response(whole))
    assertEquals(CATALOG_SYSTEM_ID, listed.catalogs.single().benchmark.catalogSystemId)
    assertTrue(whole.length > summaryBytes * 4, "${whole.length} vs $summaryBytes")

    // And either can be narrowed to the components a call is about.
    val narrowed =
      envelope(
        server,
        ServeUiBuilderMcp.LIST_CATALOGS,
        """{"full":true,"componentIds":["m3/text","layout/column"]}""",
      )
    assertEquals(
      listOf("layout/column", "m3/text"),
      assertIs<CatalogsResponseV1>(response(narrowed))
        .catalogs
        .single()
        .components
        .map { it.componentId }
        .sorted(),
    )
  }

  @Test
  fun `an optional property can be unset with null, and a required one cannot`() {
    val server = start()
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"agent-screen","document":${json.encodeToString(DesignDocumentV1.serializer(), document())}}""",
    )
    fun set(operationId: String, baseRevision: Long, mutation: DesignMutationV1) =
      response(
        envelope(
          server,
          ServeUiBuilderMcp.APPLY,
          """{"designId":"agent-screen","operationId":"$operationId","baseRevision":$baseRevision,"operations":${json.encodeToString(ListSerializer(DesignMutationV1.serializer()), listOf(mutation))}}""",
        )
      )

    // Try a property, then take it back — the shape of an edit an export diagnostic prompts.
    assertIs<AcceptedOutcomeV1>(
      assertIs<OperationOutcomeResponseV1>(
          set(
            "try",
            0,
            SetPropertyMutationV1("column", "verticalArrangement", StringValueV1("center")),
          )
        )
        .outcome
    )
    assertIs<AcceptedOutcomeV1>(
      assertIs<OperationOutcomeResponseV1>(
          set("unset", 1, SetPropertyMutationV1("column", "verticalArrangement", NullValueV1))
        )
        .outcome
    )
    val column =
      Json.parseToJsonElement(
          envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")
        )
        .jsonObject["response"]!!
        .jsonObject["snapshot"]!!
        .jsonObject["state"]!!
        .jsonObject["document"]!!
        .jsonObject["nodes"]!!
        .jsonObject["column"]!!
        .jsonObject
    // Back as it was: the node is there, and the property is gone rather than null.
    assertFalse(
      "verticalArrangement" in (column["properties"]?.jsonObject ?: emptyMap()),
      column.toString(),
    )

    // A required property stays required, and the refusal names the node and the field.
    val refused =
      assertIs<RejectedOutcomeV1>(
        assertIs<OperationOutcomeResponseV1>(
            set("unset-text", 2, SetPropertyMutationV1("session", "text", NullValueV1))
          )
          .outcome
      )
    assertEquals(RejectionCodeV1.INVALID_DOCUMENT, refused.code)
    assertEquals("required property text is missing", refused.message)
    assertEquals("session", refused.nodeId)
    assertEquals("text", refused.field)
  }

  @Test
  fun `an agent renames its design and deletes it when it is done`() {
    val server = start()
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      """{"designId":"probe","document":${json.encodeToString(DesignDocumentV1.serializer(), document().copy(id = "probe", title = "Delegation works"))}}""",
    )

    val renamed =
      Json.parseToJsonElement(
          envelope(
            server,
            ServeUiBuilderMcp.RENAME_DESIGN,
            """{"designId":"probe","title":"Discord client"}""",
          )
        )
        .jsonObject
    assertEquals(
      "compose-preview/ui-builder-design-renamed/v1",
      renamed["schema"]!!.jsonPrimitive.content,
    )
    val entry = renamed["design"]!!.jsonObject
    assertEquals("Discord client", entry["title"]!!.jsonPrimitive.content)
    // The revision did not move: a title is not design content, and an edit in flight against
    // revision 0 is still against revision 0.
    assertEquals("0", entry["revision"]!!.jsonPrimitive.content)
    val listed =
      assertIs<DesignsResponseV1>(
        response(envelope(server, ServeUiBuilderMcp.LIST_DESIGNS, """{"limit":10}"""))
      )
    assertEquals("Discord client", listed.designs.single { it.designId == "probe" }.title)

    val deleted =
      Json.parseToJsonElement(
          envelope(server, ServeUiBuilderMcp.DELETE_DESIGN, """{"designId":"probe"}""")
        )
        .jsonObject
    assertEquals(
      "compose-preview/ui-builder-design-deleted/v1",
      deleted["schema"]!!.jsonPrimitive.content,
    )
    assertEquals("probe", deleted["designId"]!!.jsonPrimitive.content)
    // Gone from every angle, and a second delete is the service's own "not found" rather than a
    // silent yes.
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      assertIs<ErrorResponseV1>(
          response(envelope(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"probe"}"""))
        )
        .error
        .code,
    )
    assertEquals(
      ServiceErrorCodeV1.NOT_FOUND,
      assertIs<ErrorResponseV1>(
          response(envelope(server, ServeUiBuilderMcp.DELETE_DESIGN, """{"designId":"probe"}"""))
        )
        .error
        .code,
    )
    assertTrue(
      assertIs<DesignsResponseV1>(
          response(envelope(server, ServeUiBuilderMcp.LIST_DESIGNS, """{"limit":10}"""))
        )
        .designs
        .none { it.designId == "probe" }
    )
  }

  @Test
  fun `the tools are listed only where a UI builder is actually served`() {
    val withBuilder = tools(start())
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.all { it in withBuilder }, withBuilder.toString())

    running?.close()
    running = null
    // A box that serves no builder does not advertise the door. Listed-and-failing would tell an
    // agent this server can do something it cannot, which is worse than silence.
    val without = tools(start(withUiBuilder = false))
    assertTrue(ServeUiBuilderMcp.TOOL_NAMES.none { it in without }, without.toString())
    assertTrue("render_preview" in without, without.toString())
  }

  @Test
  fun `the native render tool appears only where the host can compile`() {
    // Two absences, not one: a box with no builder has no UI-builder tools at all, and a box with
    // a builder but no compiler has the six that need no compiler and not the seventh. A client
    // reads which of the three it is talking to off `tools/list` rather than off a failed call.
    val withoutCompiler = tools(start())
    assertTrue(
      ServeUiBuilderMcp.TOOL_NAMES.all { it in withoutCompiler },
      withoutCompiler.toString(),
    )
    assertTrue(
      ServeUiBuilderMcp.NATIVE_TOOL_NAMES.none { it in withoutCompiler },
      withoutCompiler.toString(),
    )
  }

  @Test
  fun `the asset tool appears only where the host keeps design assets, and puts a picture`() {
    val withoutStore = tools(start())
    assertTrue(
      ServeUiBuilderMcp.ASSET_TOOL_NAMES.none { it in withoutStore },
      withoutStore.toString(),
    )
    running?.close()
    running = null

    val server = start(withAssets = true)
    assertTrue(ServeUiBuilderMcp.ASSET_TOOL_NAMES.all { it in tools(server) })
    val designId = document().id
    val documentJson = json.encodeToString(DesignDocumentV1.serializer(), document())
    envelope(
      server,
      ServeUiBuilderMcp.CREATE_DESIGN,
      "{\"designId\":\"$designId\",\"document\":$documentJson}",
    )

    // The whole reason the tool exists (#478): bytes behind a key, then a node naming the key.
    val stored =
      response(
        envelope(
          server,
          ServeUiBuilderMcp.PUT_ASSET,
          putAssetArguments(designId, "avatar-lain", PNG_HEADER),
        )
      )
    val accepted = assertIs<AcceptedOutcomeV1>(assertIs<OperationOutcomeResponseV1>(stored).outcome)
    assertEquals(1, accepted.committedRevision)

    val snapshot =
      assertIs<SnapshotResponseV1>(
        response(
          envelope(
            server,
            ServeUiBuilderMcp.GET_DESIGN,
            "{\"designId\":\"$designId\",\"includeCatalog\":true}",
          )
        )
      )
    val binding = snapshot.snapshot.state.document.assets.getValue("avatar-lain")
    assertEquals("image/png", binding.mediaType)
    assertTrue(binding.contentDigest.startsWith("sha256:"), binding.contentDigest)

    // Not an image: the service's refusal, in the envelope, rather than a tool error.
    val refused =
      response(
        envelope(
          server,
          ServeUiBuilderMcp.PUT_ASSET,
          putAssetArguments(designId, "junk", "nope".encodeToByteArray()),
        )
      )
    assertEquals(ServiceErrorCodeV1.BAD_REQUEST, assertIs<ErrorResponseV1>(refused).error.code)

    // The node that names the key. #497 refuses an `assetKey` nothing resolves at commit, and the
    // pinned key is resolved — so the picture's node commits, and a guessed key's node does not.
    fun insertPhoto(operationId: String, baseRevision: Long, key: String) =
      response(
        envelope(
          server,
          ServeUiBuilderMcp.APPLY,
          "{\"designId\":\"$designId\",\"operationId\":\"$operationId\",\"baseRevision\":" +
            "$baseRevision,\"operations\":[{\"type\":\"insertNode\",\"node\":{\"id\":" +
            "\"$operationId\",\"componentId\":\"asset/image\",\"properties\":{\"assetKey\":" +
            "{\"type\":\"assetKey\",\"value\":\"$key\"}},\"modifiers\":[{\"type\":\"size\"," +
            "\"widthDp\":40,\"heightDp\":40}]},\"location\":{\"parent\":{\"nodeId\":" +
            "\"column\",\"slot\":\"children\"}}}]}",
        )
      )
    val committed =
      assertIs<OperationOutcomeResponseV1>(
        insertPhoto("photo", accepted.committedRevision, "avatar-lain")
      )
    assertIs<AcceptedOutcomeV1>(committed.outcome, committed.toString())
    val guessed =
      assertIs<OperationOutcomeResponseV1>(
        insertPhoto("guess", accepted.committedRevision + 1, "avatar-nobody")
      )
    val rejection = assertIs<RejectedOutcomeV1>(guessed.outcome)
    assertEquals(RejectionCodeV1.INVALID_PROPERTY, rejection.code)
    assertTrue(rejection.message.contains("avatar-nobody"), rejection.message)
  }

  private fun putAssetArguments(designId: String, assetKey: String, bytes: ByteArray): String {
    val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
    return "{\"designId\":\"$designId\",\"assetKey\":\"$assetKey\"," +
      "\"${ServeUiBuilderMcp.ASSET_BYTES_ARGUMENT}\":\"$encoded\"}"
  }

  @Test
  fun `a caller without the capability is refused by name rather than served`() {
    // The service is configured; the authorization is not. The refusal has to say which grant is
    // missing, because "unauthorized" on a surface with three capabilities is not actionable.
    val server = start(withAuthorization = false)
    val result = call(server, ServeUiBuilderMcp.GET_DESIGN, """{"designId":"agent-screen"}""")

    assertEquals(true, result["isError"]?.jsonPrimitive?.content?.toBoolean())
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertTrue(text.contains("read grant"), text)
  }

  private fun start(
    withUiBuilder: Boolean = true,
    withAuthorization: Boolean = true,
    withAssets: Boolean = false,
    recordFile: File? = ScreenGeneratorScreenFixture.componentsFile(),
    catalogSystemId: String = CATALOG_SYSTEM_ID,
    withRemoteExports: Boolean = false,
  ): RunningServer {
    val registry = ServeSessionRegistry(open = { null })
    val service =
      if (!withUiBuilder) null
      else
        PersistentUiBuilderService(
          storage = FileUiBuilderStateStorage(stateDirectory),
          catalogs =
            CurrentM3UiBuilderCatalogExecutor(
              catalogSystemIds = setOf(catalogSystemId),
              exportCapabilities =
                ee.schimke.composeai.uibuilder.protocol
                  .ExportCapabilitiesV1(
                    composeCode = true,
                    svg = false,
                    png = false,
                  )
                  .let {
                    ee.schimke.composeai.uibuilder.RemoteDocumentExportSupport.capabilities(
                      it,
                      json = withRemoteExports,
                      document = withRemoteExports,
                    )
                  },
            ),
          exporter =
            ScreenGeneratorComposeExportExecutor(
                ComponentRecordSource(recordFile?.let { mapOf(catalogSystemId to it) }.orEmpty())::
                  record
              )
              .let { if (withRemoteExports) RemoteDocumentExportExecutor(it) else it },
          assets =
            if (withAssets) FileUiBuilderAssetStore(stateDirectory.resolve("assets")) else null,
        )
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = OPERATOR_TOKEN,
          sessions = registry,
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization(OPERATOR_TOKEN, null, null),
          uiBuilderService = service,
          uiBuilderAssets = if (withAssets) service else null,
          uiBuilderAuthorization =
            if (withAuthorization)
              ServeUiBuilderAuthorization.fromServeIdentity(OPERATOR_TOKEN, null, null)
            else null,
        )
        .also(ServeHttpServer::start)
    return RunningServer(server, registry).also { running = it }
  }

  /** One `tools/call`, as its raw MCP result object. */
  private fun call(server: RunningServer, tool: String, arguments: String = "{}") =
    post(
        server,
        """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""",
      )["result"]!!
      .jsonObject

  /** The UI-builder envelope a tool replied with, as text. */
  private fun envelope(server: RunningServer, tool: String, arguments: String = "{}"): String {
    val result = call(server, tool, arguments)
    val text = result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    assertEquals(null, result["isError"], text)
    return text
  }

  private fun response(envelope: String) =
    json.decodeFromString(McpResponseEnvelopeV1.serializer(), envelope).response

  private fun tools(server: RunningServer): List<String> =
    post(server, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["result"]!!
      .jsonObject["tools"]!!
      .jsonArray
      .map { it.jsonObject["name"]!!.jsonPrimitive.content }

  private fun post(server: RunningServer, body: String) =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server.server.port}/mcp")
          .header(ServeHttpServer.TOKEN_HEADER, OPERATOR_TOKEN)
          .post(body.toRequestBody(JSON_MEDIA_TYPE))
          .build()
      )
      .execute()
      .use {
        val text = it.body.string()
        assertEquals(200, it.code, text)
        Json.parseToJsonElement(text).jsonObject
      }

  /**
   * A column holding one text, so the mutation above has a slot to insert into and the export has
   * something to nest. Pinned to the packaged M3 catalog, like the HTTP export test's document.
   */
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

  private data class RunningServer(
    val server: ServeHttpServer,
    val registry: ServeSessionRegistry,
  ) : AutoCloseable {
    override fun close() {
      server.stop()
      registry.close()
    }
  }

  private companion object {
    /** A PNG signature and an IHDR chunk, which is what the asset lane sniffs. */
    val PNG_HEADER: ByteArray =
      byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
        byteArrayOf(0, 0, 0, 0x0D) +
        "IHDR".encodeToByteArray() +
        byteArrayOf(0, 0, 0, 40, 0, 0, 0, 40) +
        byteArrayOf(8, 6, 0, 0, 0) +
        ByteArray(4)
    const val OPERATOR_TOKEN = "ui-builder-mcp-operator-token"
    const val CATALOG_SYSTEM_ID = "m3-catalog"
    val JSON_MEDIA_TYPE = "application/json".toMediaType()
  }
}
