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
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.CurrentM3UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/**
 * `DELETE /admin/ui-builder/actors/{actorId}`: one person out of the access lists and the boards.
 */
class ServeUiBuilderActorErasureTest {
  @TempDir lateinit var stateDirectory: Path

  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val alice = AuthenticatedUiBuilderActor("github:alice")
  private val bob = AuthenticatedUiBuilderActor("github:bob")

  private val service by lazy {
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(stateDirectory.resolve("state")),
      catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf(CATALOG)),
      exporter =
        ScreenGeneratorComposeExportExecutor(
          ComponentRecordSource(mapOf(CATALOG to ScreenGeneratorScreenFixture.componentsFile()))::
            record
        ),
    )
  }
  private val comments by lazy { ServeUiBuilderCommentStore(stateDirectory.resolve("comments")) }
  private val logs = mutableListOf<String>()
  private val admin by lazy {
    ServeUiBuilderAdmin(service, comments = comments, designs = service, onLog = logs::add)
  }

  private fun run(actor: AuthenticatedUiBuilderActor, request: UiBuilderServiceRequest) =
    runBlocking {
      service.execute(UiBuilderServiceCall(actor, request))
    }

  private fun create(by: AuthenticatedUiBuilderActor, id: String, sharedWith: List<String>) {
    val created =
      assertIs<UiBuilderServiceResponse.Snapshot>(
        run(by, UiBuilderServiceRequest.CreateDesign(document(id)))
      )
    if (sharedWith.isEmpty()) return
    assertIs<UiBuilderServiceResponse.DesignAccess>(
      run(
        by,
        UiBuilderServiceRequest.UpdateDesignAccess(
          id,
          assertNotNull(created.snapshot.access).accessRevision,
          sharedWith.map {
            GrantActorAccessMutationV1(
              it,
              DesignAccessRoleV1.EDITOR,
              listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE),
            )
          },
        ),
      )
    )
  }

  private fun grantees(id: String): List<String> =
    assertIs<UiBuilderServiceResponse.DesignAccess>(
        run(owner, UiBuilderServiceRequest.GetDesignAccess(id))
      )
      .access
      .actorGrants
      .map { it.actorId }

  @Test
  fun `revokes the actor's grants, anonymises the boards, and leaves designs they own alone`() {
    create(owner, "shared", sharedWith = listOf(alice.actorId, bob.actorId))
    create(alice, "alices", sharedWith = emptyList())

    val opened =
      assertIs<CommentWriteResult.Stored>(
        comments.post("shared", alice.actorId, CommentPostRequest(body = "Hi", displayName = "Al"))
      )
    val thread = opened.board.threads.single()
    comments.post(
      "shared",
      bob.actorId,
      CommentPostRequest(threadId = thread.id, body = "Hello", displayName = "Bob"),
    )
    comments.react("shared", alice.actorId, thread.comments.single().id, "+1", on = true)
    comments.react("shared", bob.actorId, thread.comments.single().id, "+1", on = true)
    comments.resolve("shared", alice.actorId, thread.id, resolved = true)

    val erased = assertNotNull(runBlocking { admin.eraseActor(" GitHub:Alice ") })

    assertEquals(listOf("shared"), erased.revokedFrom)
    assertEquals(listOf("alices"), erased.ownedDesigns, "a design keeps its owner")
    assertEquals(1, erased.commentBoards)
    assertEquals(listOf(bob.actorId), grantees("shared"))
    assertIs<UiBuilderServiceResponse.Error>(
      run(alice, UiBuilderServiceRequest.OpenDesign("shared")),
      "alice can no longer open the design she was shared",
    )

    val board = comments.readOrEmpty("shared").threads.single()
    val (first, second) = board.comments
    assertEquals(ERASED_ACTOR_ID, first.authorId)
    assertEquals(ERASED_ACTOR_ID, first.displayName)
    assertEquals("Hi", first.body, "what was said stays")
    assertEquals(bob.actorId, second.authorId, "nobody else is touched")
    assertEquals("Bob", second.displayName)
    assertEquals(listOf(ERASED_ACTOR_ID, bob.actorId), first.reactions.getValue("+1"))
    assertEquals(ERASED_ACTOR_ID, board.resolvedBy)
    assertTrue(board.acknowledgedBy.keys.none { it.equals(alice.actorId, ignoreCase = true) })
    assertTrue(ERASED_ACTOR_ID in board.acknowledgedBy)
    assertTrue(alice.actorId !in comments.readOrEmpty("shared").toString())
    assertTrue(logs.single().contains("revision history keeps the id"), logs.toString())

    // Running it again finds nothing left to do.
    val again = assertNotNull(runBlocking { admin.eraseActor(alice.actorId) })
    assertEquals(emptyList(), again.revokedFrom)
    assertEquals(0, again.commentBoards)
  }

  @Test
  fun `a blank id and the placeholder itself are refused`() {
    assertNull(runBlocking { admin.eraseActor("  ") })
    assertNull(runBlocking { admin.eraseActor(ERASED_ACTOR_ID) })
  }

  @Test
  fun `the comment store rewrites only the boards that name the actor`() {
    comments.post("one", alice.actorId, CommentPostRequest(body = "a"))
    comments.post("two", bob.actorId, CommentPostRequest(body = "b"))
    val before = comments.readOrEmpty("two")

    assertEquals(1, comments.eraseActor(alice.actorId, ERASED_ACTOR_ID))

    assertEquals(before, comments.readOrEmpty("two"), "a board without the actor is not rewritten")
    assertEquals(
      ERASED_ACTOR_ID,
      comments.readOrEmpty("one").threads.single().comments.single().authorId,
    )
  }

  private fun document(id: String): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = "Screen $id",
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

  private companion object {
    const val CATALOG = "m3-catalog"
  }
}
