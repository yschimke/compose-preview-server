package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.Closeable
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class ServeUiBuilderProjectsTest {
  private val root = Files.createTempDirectory("builder-projects")
  private val store = ServeUiBuilderProjectStore(root)
  private val owner = AuthenticatedUiBuilderActor("github:owner")
  private val viewer = AuthenticatedUiBuilderActor("github:viewer")
  private val editor = AuthenticatedUiBuilderActor("github:editor")
  private val calls = mutableListOf<UiBuilderServiceCall>()
  private val documents = mutableMapOf<String, DesignDocumentV1>()
  private val catalog =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("m3", "source", "m3-catalog", "candidate", "candidate").build(),
        emptyList(),
      )
      .build()
  private var listener: ((UiBuilderServiceUpdate) -> Unit)? = null
  private var closed = false
  private val raw =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        calls += call
        return when (val request = call.request) {
          is UiBuilderServiceRequest.CreateDesign -> {
            if (request.document.title == "refuse")
              return UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.BAD_REQUEST, "invalid design")
              )
            check(documents.putIfAbsent(request.document.id, request.document) == null)
            snapshot(request.document)
          }
          is UiBuilderServiceRequest.GetSnapshot,
          is UiBuilderServiceRequest.OpenDesign -> {
            val id =
              if (request is UiBuilderServiceRequest.GetSnapshot) request.designId
              else (request as UiBuilderServiceRequest.OpenDesign).designId
            documents[id]?.let(::snapshot)
              ?: UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "missing")
              )
          }
          is UiBuilderServiceRequest.DeleteDesign -> {
            documents.remove(request.designId)
            UiBuilderServiceResponse.Catalogs(emptyList())
          }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }
      }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable {
        this@ServeUiBuilderProjectsTest.listener = listener
        return Closeable { closed = true }
      }
    }
  private val guarded = ServeUiBuilderProjectService(raw, store)
  private val projects =
    ServeUiBuilderProjects(store, guarded, serverOrigin = { "https://ui.coo.ee" })

  @AfterTest
  fun cleanup() {
    root.toFile().deleteRecursively()
  }

  private fun snapshot(doc: DesignDocumentV1) =
    UiBuilderServiceResponse.Snapshot(
      ServiceSnapshotV1(
        designId = doc.id,
        state = DesignStateV1(lastSequence = 0, document = doc),
        catalog = catalog,
        retainedFromSequence = 0,
      )
    )

  @Test
  fun `project files survive reopening and ids are isolated from other projects`() =
    runBlocking<Unit> {
      val one = projects.create(owner, ProjectCreate("one", "One"), null)
      val two = projects.create(owner, ProjectCreate("two", "Two"), null)
      val a =
        projects.putFile(
          owner,
          one.id,
          ProjectPutFile(one.revision, "login", "screens/login.uid", documentText("login")),
        )
      val b =
        projects.putFile(
          owner,
          two.id,
          ProjectPutFile(two.revision, "login", "screens/login.uid", documentText("login")),
        )
      assertNotEquals(
        a.files.single().designs.getValue("login"),
        b.files.single().designs.getValue("login"),
      )
      assertEquals(a, ServeUiBuilderProjectStore(root).read("one"))
      assertEquals(
        documentText("login"),
        projects.review(owner, "one").files["screens/login.uid"],
        "an unchanged file stays byte-for-byte intact",
      )
      val doc = documents.getValue(a.files.single().designs.getValue("login"))
      assertIs<DesignHomeV1.Server>(doc.home)
    }

  @Test
  fun `project membership also guards asset bytes and uploads`() =
    runBlocking<Unit> {
      store.save(
        BuilderProject(
          id = "app",
          name = "App",
          owner = owner.actorId,
          members =
            mapOf(viewer.actorId to ProjectRole.VIEWER, editor.actorId to ProjectRole.EDITOR),
          files = listOf(ProjectFile("screen", "screen.uid", "{}", mapOf("source" to "stored"))),
        ),
        null,
      )
      val delegated = mutableListOf<AuthenticatedUiBuilderActor>()
      val assets =
        ServeUiBuilderProjectAssets(
          object : UiBuilderAssetPort {
            override suspend fun putAsset(write: UiBuilderAssetWrite): UiBuilderServiceResponse {
              delegated += write.actor
              return UiBuilderServiceResponse.Catalogs(emptyList())
            }

            override suspend fun readAsset(read: UiBuilderAssetRead): UiBuilderAssetReadResult {
              delegated += read.actor
              return UiBuilderAssetReadResult.Failed(
                UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "fixture has no image")
              )
            }
          },
          store,
        )
      assets.readAsset(UiBuilderAssetRead(viewer, "stored", "image"))
      assertEquals(AuthenticatedUiBuilderActor(viewer.actorId, owner.actorId), delegated.single())
      val denied = assets.putAsset(UiBuilderAssetWrite(viewer, "stored", "image", byteArrayOf()))
      assertEquals(
        ServiceErrorCodeV1.FORBIDDEN,
        assertIs<UiBuilderServiceResponse.Error>(denied).error.code,
      )
      assets.putAsset(UiBuilderAssetWrite(editor, "stored", "image", byteArrayOf()))
      assertEquals(AuthenticatedUiBuilderActor(editor.actorId, owner.actorId), delegated.last())
      val outsider =
        assets.readAsset(
          UiBuilderAssetRead(AuthenticatedUiBuilderActor("outsider"), "stored", "image")
        )
      assertEquals(
        ServiceErrorCodeV1.NOT_FOUND,
        assertIs<UiBuilderAssetReadResult.Failed>(outsider).error.code,
      )
      assertEquals(2, delegated.size)
    }

  @Test
  fun `adding files keeps the canonical manifest identity instead of the copy identity`() =
    runBlocking<Unit> {
      val copy = projects.create(owner, ProjectCreate("hosted-copy", "Hosted copy"), null)
      val withFile =
        projects.putFile(
          owner,
          copy.id,
          ProjectPutFile(copy.revision, "screen", "screen.uid", documentText("login")),
        )
      store.save(
        withFile.copy(
          revision = withFile.revision + 1,
          files =
            withFile.files +
              ProjectFile(
                "project-manifest",
                "project.json",
                """{"schema":"compose-ui-builder-project/v1","id":"canonical-app","name":"Canonical app","files":[],"future":"kept"}""",
                kind = "manifest",
              ),
        ),
        withFile.revision,
      )
      val manifest =
        PROJECT_JSON.parseToJsonElement(
            projects.review(owner, copy.id).files.getValue("project.json")
          )
          .jsonObject
      assertEquals("canonical-app", manifest["id"]!!.jsonPrimitive.content)
      assertEquals("Canonical app", manifest["name"]!!.jsonPrimitive.content)
      assertEquals("kept", manifest["future"]!!.jsonPrimitive.content)
      assertEquals(1, manifest["files"]!!.jsonArray.size)
    }

  @Test
  fun `project roles apply to direct design requests and revocation closes updates`() =
    runBlocking {
      var project = projects.create(owner, ProjectCreate("app", "App"), null)
      project =
        projects.putFile(
          owner,
          project.id,
          ProjectPutFile(project.revision, "screen", "screen.uid", documentText("login")),
        )
      project =
        projects.members(
          owner,
          project.id,
          ProjectMembers(
            project.revision,
            mapOf(viewer.actorId to ProjectRole.VIEWER, editor.actorId to ProjectRole.EDITOR),
          ),
        )
      val id = project.files.single().designs.getValue("login")
      assertIs<UiBuilderServiceResponse.Snapshot>(
        guarded.execute(UiBuilderServiceCall(viewer, UiBuilderServiceRequest.OpenDesign(id)))
      )
      assertEquals(viewer.actorId, calls.last().actor.actorId)
      assertEquals(owner.actorId, calls.last().actor.onBehalfOfActorId)
      assertIs<UiBuilderServiceResponse.Error>(
        guarded.execute(
          UiBuilderServiceCall(viewer, UiBuilderServiceRequest.RenameDesign(id, "changed"))
        )
      )
      val stranger = AuthenticatedUiBuilderActor("github:stranger")
      assertEquals(
        ServiceErrorCodeV1.NOT_FOUND,
        assertIs<UiBuilderServiceResponse.Error>(
            guarded.execute(UiBuilderServiceCall(stranger, UiBuilderServiceRequest.OpenDesign(id)))
          )
          .error
          .code,
      )
      assertIs<UiBuilderServiceResponse.Error>(
        guarded.execute(UiBuilderServiceCall(editor, UiBuilderServiceRequest.DeleteDesign(id)))
      )
      var updates = 0
      guarded.subscribe(UiBuilderSubscriptionCall(viewer, id, null)) { updates++ }
      listener!!(UiBuilderServiceUpdate.Snapshot(snapshot(documents.getValue(id)).snapshot))
      assertEquals(1, updates)
      projects.members(owner, project.id, ProjectMembers(project.revision, emptyMap()))
      listener!!(UiBuilderServiceUpdate.Snapshot(snapshot(documents.getValue(id)).snapshot))
      assertEquals(1, updates)
      assertTrue(closed)
    }

  @Test
  fun `an invalid collection import rolls back created designs`() =
    runBlocking<Unit> {
      val project = projects.create(owner, ProjectCreate("app", "App"), null)
      val collection = buildJsonObject {
        put("schema", "compose-ui-builder-designs/v1")
        put("active", "first")
        put(
          "designs",
          JsonArray(
            listOf(
              PROJECT_JSON.parseToJsonElement(documentText("first")),
              PROJECT_JSON.parseToJsonElement(documentText("bad", "refuse")),
            )
          ),
        )
      }
      assertFailsWith<IllegalArgumentException> {
        projects.putFile(
          owner,
          project.id,
          ProjectPutFile(project.revision, "screens", "screens.uid", collection.toString()),
        )
      }
      assertTrue(documents.isEmpty())
      assertTrue(store.read(project.id)!!.files.isEmpty())
    }

  @Test
  fun `concurrent sharing writes do not overwrite a newer project`() =
    runBlocking<Unit> {
      val project = projects.create(owner, ProjectCreate("app", "App"), null)
      projects.members(
        owner,
        project.id,
        ProjectMembers(project.revision, mapOf(editor.actorId to ProjectRole.EDITOR)),
      )
      assertFailsWith<IllegalStateException> {
        projects.members(owner, project.id, ProjectMembers(project.revision, emptyMap()))
      }
      assertEquals(ProjectRole.EDITOR, store.read(project.id)!!.members[editor.actorId])
      assertFailsWith<IllegalArgumentException> {
        projects.members(editor, project.id, ProjectMembers(1, emptyMap()))
      }
    }

  @Test
  fun `collection writes preserve siblings future fields and clear modelled defaults`() {
    val first = PROJECT_JSON.parseToJsonElement(documentText("first")).jsonObject
    val second =
      JsonObject(
        PROJECT_JSON.parseToJsonElement(documentText("second")).jsonObject +
          ("future" to JsonPrimitive("keep"))
      )
    val original = buildJsonObject {
      put("schema", "compose-ui-builder-designs/v1")
      put("active", "first")
      put("futureEnvelope", 123)
      put("designs", JsonArray(listOf(first, second)))
    }
      .toString()
    val docs = ServeUiBuilderUidProjectFiles.designs(original)
    val file =
      ProjectFile(
        "file",
        "screens.uid",
        original,
        mapOf("first" to "s-first", "second" to "s-second"),
      )
    val output =
      ServeUiBuilderUidProjectFiles.write(
        file,
        mapOf(
          "first" to docs[0].copy(id = "s-first", title = "Changed"),
          "second" to docs[1].copy(id = "s-second"),
        ),
      )
    val result = PROJECT_JSON.parseToJsonElement(output).jsonObject
    assertEquals(JsonPrimitive(123), result["futureEnvelope"])
    assertEquals(
      JsonPrimitive("keep"),
      result.getValue("designs").jsonArray[1].jsonObject["future"],
    )
    assertEquals(
      listOf("Changed", "second"),
      ServeUiBuilderUidProjectFiles.designs(output).map { it.title },
    )
  }

  @Test
  fun `sibling navigation is mapped into the project and restored in the uid file`() =
    runBlocking<Unit> {
      val rawFirst = PROJECT_JSON.parseToJsonElement(documentText("first")).jsonObject
      val node = buildJsonObject {
        put("id", "button")
        put("componentId", "material3/Button")
        put(
          "eventBindings",
          buildJsonObject {
            put(
              "onClick",
              JsonArray(
                listOf(
                  buildJsonObject {
                    put("type", "navigatePage")
                    put("pageKey", "second")
                  }
                )
              ),
            )
          },
        )
      }
      val first = JsonObject(rawFirst + ("nodes" to buildJsonObject { put("button", node) }))
      val collection = buildJsonObject {
        put("schema", "compose-ui-builder-designs/v1")
        put("active", "first")
        put(
          "designs",
          JsonArray(listOf(first, PROJECT_JSON.parseToJsonElement(documentText("second")))),
        )
      }
      var project = projects.create(owner, ProjectCreate("app", "App"), null)
      project =
        projects.putFile(
          owner,
          "app",
          ProjectPutFile(project.revision, "screens", "screens.uid", collection.toString()),
        )
      val file = project.files.single()
      val id = file.designs.getValue("first")
      val stored = documents.getValue(id)
      val target =
        assertIs<NavigatePageActionV1>(
          stored.nodes.getValue("button").eventBindings.getValue("onClick").single()
        )
      assertEquals(file.designs.getValue("second"), target.pageKey)
      documents[id] = stored.copy(title = "Edited", revision = 1)
      val downloaded =
        ServeUiBuilderUidProjectFiles.designs(
          projects.review(owner, "app").files.getValue("screens.uid")
        )
      assertEquals(
        "second",
        assertIs<NavigatePageActionV1>(
            downloaded.first().nodes.getValue("button").eventBindings.getValue("onClick").single()
          )
          .pageKey,
      )
      val request =
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Batch(
            id,
            "op",
            "client",
            1,
            listOf(
              SetEventBindingMutationV1(
                "button",
                "onClick",
                listOf(NavigatePageActionV1("other-project")),
              )
            ),
          )
        )
      assertEquals(
        ServiceErrorCodeV1.FORBIDDEN,
        assertIs<UiBuilderServiceResponse.Error>(
            guarded.execute(UiBuilderServiceCall(owner, request))
          )
          .error
          .code,
      )
    }

  @Test
  fun `a project path cannot escape its root`() {
    listOf("../outside.uid", "/absolute.uid", "a/../b.uid", "a\\b.uid", "a//b.uid").forEach { path
      ->
      assertFailsWith<IllegalArgumentException> { validateProjectPath(path) }
    }
  }
}

internal fun documentText(id: String, title: String = id): String =
  """{"schema":"compose-ui-builder-document/v1","id":"$id","title":"$title","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":360,"heightDp":800,"density":1.0,"theme":"light","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},"stateVariables":{},"roots":[],"nodes":{}}"""
