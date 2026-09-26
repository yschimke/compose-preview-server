package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.PresenceLeaveV1
import ee.schimke.composeai.uibuilder.protocol.PresenceUpsertV1
import ee.schimke.composeai.uibuilder.protocol.PresenceV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderRevisionSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * Who a reader of a public design is told about: themselves as they are, everyone else as a
 * pseudonym — unless the design is theirs or is shared with them by name. Against the real design
 * service, because "reaches it only through the public grant" is the runtime's answer, not ours.
 */
class ServeUiBuilderPublicReadersTest {
  @TempDir lateinit var stateDirectory: Path

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")
  private val editor = AuthenticatedUiBuilderActor("github:editor")
  private val stranger = AuthenticatedUiBuilderActor("github:stranger")
  private val anonymous = AuthenticatedUiBuilderActor(ServeUiBuilderVisibility.ANONYMOUS_ACTOR_ID)

  private val service by lazy {
    ServeUiBuilderVisibility.withDefault(
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
        exporter =
          ScreenGeneratorComposeExportExecutor(
            ComponentRecordSource(mapOf(CATALOG to ScreenGeneratorScreenFixture.componentsFile()))::
              record
          ),
      ),
      UiBuilderDefaultVisibility.PUBLIC,
    )
  }

  private fun UiBuilderServicePort.run(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderServiceRequest,
  ): UiBuilderServiceResponse = runBlocking { execute(UiBuilderServiceCall(actor, request)) }

  private fun createShared() {
    val created =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        service.run(owner, UiBuilderServiceRequest.CreateDesign(document()))
      )
    val revision = assertNotNull(created.snapshot.access).accessRevision
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      service.run(
        owner,
        UiBuilderServiceRequest.UpdateDesignAccess(
          DESIGN_ID,
          revision,
          listOf(
            GrantActorAccessMutationV1(
              viewer.actorId,
              DesignAccessRoleV1.VIEWER,
              listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT),
            ),
            GrantActorAccessMutationV1(
              editor.actorId,
              DesignAccessRoleV1.EDITOR,
              listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE),
            ),
          ),
        ),
      )
    )
  }

  @Test
  fun `only a reader who reaches the design through its public grant gets the reduced view`() {
    createShared()
    runBlocking {
      assertNull(service.publicReaderView(owner, DESIGN_ID), "the owner")
      assertNull(service.publicReaderView(viewer, DESIGN_ID), "a viewer named on the design")
      assertNull(service.publicReaderView(editor, DESIGN_ID), "an editor named on the design")
      assertNull(
        service.publicReaderView(
          AuthenticatedUiBuilderActor("agent:helper", onBehalfOfActorId = viewer.actorId),
          DESIGN_ID,
        ),
        "an agent acting for a named viewer",
      )
      assertNotNull(service.publicReaderView(stranger, DESIGN_ID), "a signed-in stranger")
      assertNotNull(service.publicReaderView(anonymous, DESIGN_ID), "a signed-out visitor")
      assertNull(service.publicReaderView(stranger, "no-such-design"), "nothing to shape")
    }
  }

  @Test
  fun `a public reader sees other people on the board as pseudonyms and themselves as they are`() {
    val comments = ServeUiBuilderCommentStore(stateDirectory.resolve("comments"))
    val posted =
      comments.post(DESIGN_ID, owner.actorId, CommentPostRequest(body = "Card?", displayName = "O"))
    val threadId = assertIs<CommentWriteResult.Stored>(posted).board.threads.single().id
    comments.post(
      DESIGN_ID,
      stranger.actorId,
      CommentPostRequest(threadId = threadId, body = "Yes", displayName = "Stranger"),
    )
    val first = comments.readOrEmpty(DESIGN_ID).threads.single().comments.first().id
    comments.react(DESIGN_ID, editor.actorId, first, "+1", on = true)
    comments.react(DESIGN_ID, stranger.actorId, first, "+1", on = true)
    comments.resolve(DESIGN_ID, editor.actorId, threadId, resolved = true)

    val view = PublicReaderView(DESIGN_ID, setOf(stranger.actorId))
    val shaped = view.board(comments.readOrEmpty(DESIGN_ID)).threads.single()

    val (ownerComment, strangerComment) = shaped.comments
    assertTrue(ownerComment.authorId.startsWith(PublicReaderView.COLLABORATOR_PREFIX))
    assertEquals(PublicReaderView.COLLABORATOR_DISPLAY_NAME, ownerComment.displayName)
    assertEquals(stranger.actorId, strangerComment.authorId, "a reader sees their own comment")
    assertEquals("Stranger", strangerComment.displayName)
    val reactors = ownerComment.reactions.getValue("+1")
    assertEquals(2, reactors.size, "the count survives")
    assertTrue(stranger.actorId in reactors, "and so does the reader's own reaction")
    assertTrue(editor.actorId !in reactors)
    assertEquals(setOf(stranger.actorId), shaped.acknowledgedBy.keys)
    assertTrue(assertNotNull(shaped.resolvedBy).startsWith(PublicReaderView.COLLABORATOR_PREFIX))
    val text = shaped.toString()
    for (hidden in listOf(owner.actorId, editor.actorId, "\"O\"")) {
      assertTrue(hidden !in text, "$hidden is not in $text")
    }
  }

  @Test
  fun `the MCP unread-comment notice names nobody else to a public reader`() {
    createShared()
    val comments = ServeUiBuilderCommentStore(stateDirectory.resolve("comments"))
    comments.post(
      DESIGN_ID,
      owner.actorId,
      CommentPostRequest(body = "Card?", displayName = "Owner Name"),
    )
    val mcp = ServeUiBuilderMcp(service, comments = comments)

    val reply = runBlocking {
      mcp.call(
        ServeUiBuilderMcp.GET_DESIGN,
        kotlinx.serialization.json.buildJsonObject {
          put("designId", kotlinx.serialization.json.JsonPrimitive(DESIGN_ID))
        },
        stranger,
        "1",
      )
    }

    assertTrue(ServeUiBuilderMcp.COMMENTS_NOTICE_KEY in reply, "precondition: a notice: $reply")
    for (hidden in listOf(owner.actorId, "Owner Name")) {
      assertTrue(hidden !in reply, "$hidden is not in the reply")
    }
    assertTrue(PublicReaderView.COLLABORATOR_DISPLAY_NAME in reply || "collaborator-" in reply)
  }

  @Test
  fun `a pseudonym is stable on one design and differs between designs`() {
    val here = PublicReaderView(DESIGN_ID, emptySet())
    assertEquals(here.actor("github:owner"), here.actor("github:Owner"))
    assertNotEquals(here.actor("github:owner"), here.actor("github:editor"))
    assertNotEquals(
      here.actor("github:owner"),
      PublicReaderView("other", emptySet()).actor("github:owner"),
    )
  }

  @Test
  fun `revisions, presence and the update stream name nobody else`() {
    val view = PublicReaderView(DESIGN_ID, setOf(anonymous.actorId))
    val revisions =
      assertIs<UiBuilderServiceResponse.Revisions>(
        view.response(
          UiBuilderServiceResponse.Revisions(
            DESIGN_ID,
            2,
            listOf(
              UiBuilderRevisionSummary(2, 2, 2_000, editor.actorId),
              UiBuilderRevisionSummary(1, 1, 1_000, null),
            ),
          )
        )
      )
    assertEquals(view.actor(editor.actorId), revisions.revisions.first().actorId)
    assertNull(revisions.revisions.last().actorId)

    val cursor = PresenceV1(editor.actorId, "client-1", "Ed Itor", "#FF0000", emptyList(), null, 1)
    val upsert =
      assertIs<PresenceUpsertV1>(
        assertIs<UiBuilderServiceUpdate.Presence>(
            view.update(UiBuilderServiceUpdate.Presence(PresenceUpsertV1(cursor)))
          )
          .update
      )
    assertEquals(view.actor(editor.actorId), upsert.presence.actorId)
    assertEquals(PublicReaderView.COLLABORATOR_DISPLAY_NAME, upsert.presence.displayName)
    assertEquals("client-1", upsert.presence.clientId)
    val leave =
      assertIs<PresenceLeaveV1>(
        assertIs<UiBuilderServiceUpdate.Presence>(
            view.update(UiBuilderServiceUpdate.Presence(PresenceLeaveV1(editor.actorId)))
          )
          .update
      )
    assertEquals(upsert.presence.actorId, leave.actorId, "a leave matches the cursor it removes")
  }

  @Test
  fun `a snapshot served to a public reader carries no one else's identity`() {
    createShared()
    val opened = runBlocking {
      service.shapeForReader(
        stranger,
        service.execute(
          UiBuilderServiceCall(stranger, UiBuilderServiceRequest.OpenDesign(DESIGN_ID))
        ),
      )
    }
    val snapshot = assertIs<UiBuilderServiceResponse.Snapshot>(opened).snapshot
    assertNull(snapshot.access)
    // The owner reading the same design is told everything, access list included.
    val own = runBlocking {
      service.shapeForReader(
        owner,
        service.execute(UiBuilderServiceCall(owner, UiBuilderServiceRequest.OpenDesign(DESIGN_ID))),
      )
    }
    assertNotNull(assertIs<UiBuilderServiceResponse.Snapshot>(own).snapshot.access)
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = DESIGN_ID,
      title = "Public screen",
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
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Hello")),
            )
        ),
    )

  internal companion object {
    const val CATALOG = "m3-catalog"
    const val DESIGN_ID = "public-screen"
  }
}
