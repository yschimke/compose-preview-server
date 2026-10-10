package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir

/** Project ACLs at the host seams, exercised against the released persistent runtime. */
class ServeUiBuilderProjectCollaborationTest {
  @TempDir lateinit var directory: Path
  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val editor = AuthenticatedUiBuilderActor("github:editor")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")
  private val stranger = AuthenticatedUiBuilderActor("github:stranger")
  private val store by lazy { ServeUiBuilderProjectStore(directory.resolve("projects")) }
  private val raw by lazy {
    PersistentUiBuilderService(
      storage = FileUiBuilderStateStorage(directory.resolve("designs")),
      catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("m3-catalog")),
      exporter = { error("this test never exports") },
    )
  }
  private val service by lazy { ServeUiBuilderProjectService(raw, store, raw) }
  private val branches by lazy { ServeUiBuilderProjectBranches(raw, store) }
  private val projects by lazy {
    ServeUiBuilderProjects(store, service, serverOrigin = { "https://ui.example.test" })
  }

  private suspend fun seed(): Pair<BuilderProject, String> {
    var project = projects.create(owner, ProjectCreate("app", "App"), null)
    val document =
      PROJECT_JSON.decodeFromString<DesignDocumentV1>(documentText("screen"))
        .copy(
          roots = listOf("text"),
          nodes =
            mapOf(
              "text" to
                DesignNodeV1(
                  "text",
                  "m3/text",
                  properties = mapOf("text" to StringValueV1("Initial")),
                )
            ),
        )
    project =
      projects.putFile(
        owner,
        project.id,
        ProjectPutFile(
          project.revision,
          "screen",
          "screen.uid",
          PROJECT_JSON.encodeToString(DesignDocumentV1.serializer(), document),
        ),
      )
    project =
      projects.members(
        owner,
        project.id,
        ProjectMembers(
          project.revision,
          mapOf(editor.actorId to ProjectRole.EDITOR, viewer.actorId to ProjectRole.VIEWER),
        ),
      )
    return project to project.files.single().designs.getValue("screen")
  }

  private suspend fun list(
    actor: AuthenticatedUiBuilderActor,
    cursor: String? = null,
    limit: Int = 200,
  ) =
    assertIs<UiBuilderServiceResponse.Designs>(
      service.execute(
        UiBuilderServiceCall(actor, UiBuilderServiceRequest.ListDesigns(cursor, limit))
      )
    )

  private suspend fun branch(actor: AuthenticatedUiBuilderActor, request: UiBuilderBranchRequest) =
    branches.executeBranch(UiBuilderBranchCall(actor, request))

  @Test
  fun `collaborator listing adds project designs once and preserves own pagination and permissions`() =
    runBlocking<Unit> {
      val (_, id) = seed()
      assertTrue(
        assertIs<UiBuilderServiceResponse.Designs>(
            raw.execute(
              UiBuilderServiceCall(viewer, UiBuilderServiceRequest.ListDesigns(null, 200))
            )
          )
          .designs
          .isEmpty()
      )
      for (n in 1..3) {
        assertIs<UiBuilderServiceResponse.Snapshot>(
          raw.execute(
            UiBuilderServiceCall(
              viewer,
              UiBuilderServiceRequest.CreateDesign(
                PROJECT_JSON.decodeFromString(documentText("own-$n"))
              ),
            )
          )
        )
      }
      assertIs<UiBuilderServiceResponse.Snapshot>(
        raw.execute(
          UiBuilderServiceCall(
            owner,
            UiBuilderServiceRequest.CreateDesign(
              PROJECT_JSON.decodeFromString(documentText("unrelated-owner"))
            ),
          )
        )
      )
      val first = list(viewer, limit = 1)
      val shared = first.designs.single { it.designId == id }
      assertEquals(viewer.actorId, shared.requesterAccess.actorId)
      assertEquals(DesignAccessRoleV1.VIEWER, shared.requesterAccess.role)
      assertEquals(
        listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT),
        shared.requesterAccess.allowedActions,
      )
      assertNotNull(first.nextCursor)
      val second = list(viewer, first.nextCursor, 1)
      assertTrue(second.designs.none { it.designId == id })
      assertTrue((first.designs + second.designs).none { it.designId == "unrelated-owner" })
      assertTrue(list(stranger).designs.isEmpty())
      val editable = list(editor).designs.single { it.designId == id }
      assertTrue(DesignAccessActionV1.WRITE in editable.requesterAccess.allowedActions)
      assertFalse(
        DesignAccessActionV1.MANAGE_ACCESS in
          list(owner).designs.single { it.designId == id }.requesterAccess.allowedActions
      )
    }

  @Test
  fun `editors create edit merge and archive project branches while viewers can only read`() =
    runBlocking<Unit> {
      val (_, id) = seed()
      val created =
        assertIs<UiBuilderBranchResponse.Branch>(
            branch(
              editor,
              UiBuilderBranchRequest.CreateBranch(id, "Alternative", branchId = "alternative"),
            )
          )
          .branch
      assertEquals(owner.actorId, created.ownerActorId)
      assertIs<UiBuilderBranchResponse.Branches>(
        branch(viewer, UiBuilderBranchRequest.ListBranches(id))
      )
      assertIs<UiBuilderBranchResponse.Branch>(
        branch(viewer, UiBuilderBranchRequest.GetBranch(created.branchId))
      )
      for (request in
        listOf(
          UiBuilderBranchRequest.CreateBranch(id, "Denied"),
          UiBuilderBranchRequest.MergeBranch(created.branchId),
          UiBuilderBranchRequest.ArchiveBranch(created.branchId),
        )) {
        assertEquals(
          ServiceErrorCodeV1.FORBIDDEN,
          assertIs<UiBuilderBranchResponse.Error>(branch(viewer, request)).error.code,
        )
      }
      assertEquals(
        ServiceErrorCodeV1.NOT_FOUND,
        assertIs<UiBuilderBranchResponse.Error>(
            branch(stranger, UiBuilderBranchRequest.GetBranch(created.branchId))
          )
          .error
          .code,
      )
      val opened =
        assertIs<UiBuilderServiceResponse.Snapshot>(
          service.execute(
            UiBuilderServiceCall(editor, UiBuilderServiceRequest.OpenDesign(created.branchId))
          )
        )
      val edited =
        service.execute(
          UiBuilderServiceCall(
            editor,
            UiBuilderServiceRequest.ApplyOperation(
              UiBuilderSubmission.Batch(
                created.branchId,
                "edit",
                "editor",
                opened.snapshot.state.document.revision,
                listOf(SetPropertyMutationV1("text", "text", StringValueV1("Edited"))),
              )
            ),
          )
        )
      assertIs<AcceptedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(edited).outcome
      )
      assertTrue(
        assertIs<UiBuilderBranchResponse.Merge>(
            branch(editor, UiBuilderBranchRequest.MergeBranch(created.branchId))
          )
          .report
          .merged
      )
      val parent =
        assertIs<UiBuilderServiceResponse.Snapshot>(
          service.execute(UiBuilderServiceCall(viewer, UiBuilderServiceRequest.OpenDesign(id)))
        )
      assertEquals(
        StringValueV1("Edited"),
        parent.snapshot.state.document.nodes.getValue("text").properties["text"],
      )
      val archived =
        assertIs<UiBuilderBranchResponse.Branch>(
            branch(editor, UiBuilderBranchRequest.CreateBranch(id, "Discard", branchId = "discard"))
          )
          .branch
      assertIs<UiBuilderBranchResponse.Branch>(
        branch(editor, UiBuilderBranchRequest.ArchiveBranch(archived.branchId))
      )
    }

  @Test
  fun `revoking project membership removes branch reads writes and listing`() =
    runBlocking<Unit> {
      val (project, id) = seed()
      val created =
        assertIs<UiBuilderBranchResponse.Branch>(
            branch(
              editor,
              UiBuilderBranchRequest.CreateBranch(id, "Alternative", branchId = "alternative"),
            )
          )
          .branch
      projects.members(owner, project.id, ProjectMembers(project.revision, emptyMap()))
      assertTrue(list(editor).designs.isEmpty())
      assertEquals(
        ServiceErrorCodeV1.NOT_FOUND,
        assertIs<UiBuilderBranchResponse.Error>(
            branch(editor, UiBuilderBranchRequest.MergeBranch(created.branchId))
          )
          .error
          .code,
      )
      assertEquals(
        ServiceErrorCodeV1.NOT_FOUND,
        assertIs<UiBuilderServiceResponse.Error>(
            service.execute(
              UiBuilderServiceCall(
                editor,
                UiBuilderServiceRequest.OpenDesign(created.branchId),
              )
            )
          )
          .error
          .code,
      )
    }

  @Test
  fun `ordinary listing does not scan owners and indexed opens need at most one branch read`() =
    runBlocking<Unit> {
      seed()
      repeat(20) { n ->
        store.save(
          BuilderProject(id = "other-$n", name = "Other $n", owner = "github:other-$n"),
          null,
        )
      }
      assertIs<UiBuilderServiceResponse.Snapshot>(
        raw.execute(
          UiBuilderServiceCall(
            owner,
            UiBuilderServiceRequest.CreateDesign(
              PROJECT_JSON.decodeFromString(documentText("ordinary"))
            ),
          )
        )
      )
      var branchReads = 0
      val indexed =
        object : UiBuilderBranchPort, UiBuilderAdminPort by raw {
          override suspend fun executeBranch(call: UiBuilderBranchCall): UiBuilderBranchResponse {
            branchReads++
            return raw.executeBranch(call)
          }
        }
      val guarded = ServeUiBuilderProjectService(raw, store, indexed)
      val listing =
        assertIs<UiBuilderServiceResponse.Designs>(
          guarded.execute(
            UiBuilderServiceCall(
              owner,
              UiBuilderServiceRequest.ListDesigns(null, 200),
            )
          )
        )
      assertTrue(listing.designs.any { it.designId == "ordinary" })
      assertEquals(0, branchReads, "ordinary listing rows never request branch ancestry")
      assertIs<UiBuilderServiceResponse.Snapshot>(
        guarded.execute(
          UiBuilderServiceCall(
            owner,
            UiBuilderServiceRequest.OpenDesign("ordinary"),
          )
        )
      )
      assertEquals(1, branchReads, "the direct owner index avoids scanning 21 project owners")
      assertIs<UiBuilderServiceResponse.Error>(
        guarded.execute(
          UiBuilderServiceCall(
            owner,
            UiBuilderServiceRequest.OpenDesign("missing"),
          )
        )
      )
      assertEquals(1, branchReads, "missing IDs need no branch read")
    }

  @Test
  fun `HTTP wiring exposes project listings and branch documents to collaborators`() =
    runBlocking<Unit> {
      val (_, id) = seed()
      val created =
        assertIs<UiBuilderBranchResponse.Branch>(
            branch(
              editor,
              UiBuilderBranchRequest.CreateBranch(id, "Alternative", branchId = "alternative"),
            )
          )
          .branch
      val registry = ServeSessionRegistry(open = { null })
      val editorDirectory = directory.resolve("editor").toFile().apply { mkdirs() }
      java.io
        .File(editorDirectory, "index.html")
        .writeText("<!doctype html><title>Test editor</title>")
      val server =
        ServeHttpServer(
            host = "127.0.0.1",
            requestedPort = 0,
            token = "test-token",
            sessions = registry,
            defaultSessionId = "unused",
            uiBuilderService = raw,
            uiBuilderDir = editorDirectory,
            uiBuilderCatalogs = setOf("m3-catalog"),
            uiBuilderBranches = raw,
            uiBuilderProjectStore = store,
            uiBuilderAuthorization =
              ServeUiBuilderAuthorization { call, _, _ ->
                call.request.headers["X-Test-Actor"]?.let {
                  UiBuilderAuthorizationDecision.Authorized(it)
                } ?: UiBuilderAuthorizationDecision.Missing
              },
          )
          .also(ServeHttpServer::start)
      val client = okhttp3.OkHttpClient()
      fun get(path: String, actor: AuthenticatedUiBuilderActor): Pair<Int, String> =
        client
          .newCall(
            okhttp3.Request.Builder()
              .url("http://127.0.0.1:${server.port}$path")
              .header("X-Test-Actor", actor.actorId)
              .build()
          )
          .execute()
          .use { it.code to it.body!!.string() }
      try {
        val listing = get("/ui-builder/designs", viewer)
        assertEquals(200, listing.first)
        assertTrue(listing.second.contains(id), "shared project design is discoverable over HTTP")
        assertEquals(200, get("/api/ui-builder/v1/designs/${created.branchId}", viewer).first)
        assertEquals(404, get("/api/ui-builder/v1/designs/${created.branchId}", stranger).first)
      } finally {
        server.stop()
        registry.close()
      }
    }

  @Test
  fun `design scoped grants on a project parent reach branches without widening access`() =
    runBlocking<Unit> {
      val (_, id) = seed()
      val created =
        assertIs<UiBuilderBranchResponse.Branch>(
            branch(
              editor,
              UiBuilderBranchRequest.CreateBranch(id, "Alternative", branchId = "alternative"),
            )
          )
          .branch
      val agent = AuthenticatedUiBuilderActor("agent:writer", editor.actorId)
      val lookup = ServeUiBuilderGrantScope.Lookup { actor, principal ->
        if (actor == agent.actorId && principal == editor.actorId) setOf(id) else null
      }
      val parents = ServeUiBuilderGrantScope.parentsOf(branches)
      val scopedBranches = ServeUiBuilderGrantScope.limit(branches, lookup, parents)
      val scopedService = ServeUiBuilderGrantScope.limit(service, lookup, parents)
      assertIs<UiBuilderBranchResponse.Branch>(
        scopedBranches.executeBranch(
          UiBuilderBranchCall(agent, UiBuilderBranchRequest.GetBranch(created.branchId))
        )
      )
      assertIs<UiBuilderServiceResponse.Snapshot>(
        scopedService.execute(
          UiBuilderServiceCall(agent, UiBuilderServiceRequest.OpenDesign(created.branchId))
        )
      )
      assertEquals(
        listOf(id),
        assertIs<UiBuilderServiceResponse.Designs>(
            scopedService.execute(
              UiBuilderServiceCall(agent, UiBuilderServiceRequest.ListDesigns(null, 200))
            )
          )
          .designs
          .map { it.designId },
      )
      assertIs<UiBuilderServiceResponse.Snapshot>(
        raw.execute(
          UiBuilderServiceCall(
            editor,
            UiBuilderServiceRequest.CreateDesign(
              PROJECT_JSON.decodeFromString(documentText("unrelated"))
            ),
          )
        )
      )
      val unrelated =
        assertIs<UiBuilderBranchResponse.Branch>(
            raw.executeBranch(
              UiBuilderBranchCall(
                editor,
                UiBuilderBranchRequest.CreateBranch(
                  "unrelated",
                  "Outside grant",
                  branchId = "outside",
                ),
              )
            )
          )
          .branch
      assertIs<UiBuilderServiceResponse.Error>(
        scopedService.execute(
          UiBuilderServiceCall(agent, UiBuilderServiceRequest.OpenDesign("unrelated"))
        )
      )
      assertIs<UiBuilderServiceResponse.Error>(
        scopedService.execute(
          UiBuilderServiceCall(
            agent,
            UiBuilderServiceRequest.OpenDesign(unrelated.branchId),
          )
        )
      )
      assertIs<UiBuilderBranchResponse.Error>(
        scopedBranches.executeBranch(
          UiBuilderBranchCall(
            agent,
            UiBuilderBranchRequest.GetBranch(unrelated.branchId),
          )
        )
      )
    }
}
