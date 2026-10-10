package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.service.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** All project writes use revision checks; GitHub publication additionally binds reviewed bytes. */
internal class ServeUiBuilderProjects(
  val store: ServeUiBuilderProjectStore,
  private val service: UiBuilderServicePort,
  private val github: ServeUiBuilderProjectGithub = ServeUiBuilderProjectGithub(),
  private val serverOrigin: () -> String?,
) {
  private val writes = Mutex()

  suspend fun create(
    actor: AuthenticatedUiBuilderActor,
    request: ProjectCreate,
    token: String?,
  ): BuilderProject = writes.withLock {
    require(PROJECT_ID.matches(request.id)) { "invalid project id" }
    require(store.read(request.id) == null) { "project id already exists" }
    val owner = actor.onBehalfOfActorId ?: actor.actorId
    var project = BuilderProject(id = request.id, name = request.name, owner = owner)
    if (request.repository.isNotBlank()) {
      val (source, files) =
        github.connect(
          request.id,
          request.name,
          request.repository,
          request.branch,
          request.root,
          token,
        )
      project = project.copy(source = source)
      project = importFiles(project, files)
    } else store.save(project, null)
    project
  }

  suspend fun putFile(
    actor: AuthenticatedUiBuilderActor,
    id: String,
    request: ProjectPutFile,
  ): BuilderProject = writes.withLock {
    val project = permitted(actor, id, write = true)
    check(project.revision == request.baseRevision) { "project changed; reload before saving" }
    validateProjectPath(request.path)
    require(request.path != "project.json") { "project.json is the project manifest" }
    require(PROJECT_ID.matches(request.fileId)) { "invalid file id" }
    val existing = project.files.firstOrNull { it.id == request.fileId }
    if (existing == null) {
      require(request.kind in setOf("design", "components", "tokens", "themes")) {
        "invalid file kind"
      }
      require(project.files.none { it.path == request.path }) { "file path already exists" }
      importFiles(
        project,
        listOf(ProjectFile(request.fileId, request.path, request.content, kind = request.kind)),
      )
    } else {
      // Visual designs are saved by the editor's reducer, never by replacing source bytes here.
      require(existing.kind != "design" && existing.kind != "manifest") {
        "open the design in the editor to change it"
      }
      require(existing.path == request.path && existing.kind == request.kind) {
        "file identity cannot change"
      }
      validateResource(request.content)
      val next =
        project.copy(
          revision = project.revision + 1,
          files =
            project.files.map {
              if (it.id == existing.id) it.copy(original = request.content) else it
            },
        )
      // Original GitHub bytes must remain the comparison base. Resource edits live in a separate
      // field.
      val stored =
        next.copy(
          files =
            next.files.map {
              if (it.id == existing.id)
                it.copy(original = existing.original, working = request.content)
              else it
            }
        )
      store.save(stored, project.revision)
      stored
    }
  }

  suspend fun members(
    actor: AuthenticatedUiBuilderActor,
    id: String,
    request: ProjectMembers,
  ): BuilderProject = writes.withLock {
    val project = permitted(actor, id)
    if (project.role(actor) != ProjectRole.OWNER)
      throw ProjectAccessDenied("only the project owner can manage membership")
    require(
      request.members.size <= 100 && request.members.values.none { it == ProjectRole.OWNER }
    ) {
      "grant viewer or editor access"
    }
    require(
      request.members.keys.all {
        it.isNotBlank() &&
          it.length <= 256 &&
          !ServeUiBuilderVisibility.isReservedActor(it) &&
          it != project.owner
      }
    ) {
      "invalid project member"
    }
    val next = project.copy(revision = project.revision + 1, members = request.members)
    store.save(next, request.baseRevision)
    next
  }

  fun permitted(
    actor: AuthenticatedUiBuilderActor,
    id: String,
    write: Boolean = false,
  ): BuilderProject {
    val project = store.read(id)?.takeIf { it.role(actor) != null } ?: throw ProjectNotFound()
    if (write && project.role(actor) == ProjectRole.VIEWER)
      throw ProjectAccessDenied("project edit access required")
    return project
  }

  suspend fun review(actor: AuthenticatedUiBuilderActor, id: String): ProjectReview {
    val project = permitted(actor, id)
    val files = linkedMapOf<String, String>()
    for (file in project.files) {
      if (file.kind != "design") {
        files[file.path] = file.working ?: file.original
        continue
      }
      val documents = linkedMapOf<String, DesignDocumentV1>()
      for ((sourceId, serviceId) in file.designs) documents[sourceId] = snapshot(actor, serviceId)
      val unchanged = documents.all { (sourceId, document) ->
        file.seedDigests[sourceId] == documentDigest(document)
      }
      files[file.path] =
        if (unchanged) file.original
        else
          ServeUiBuilderUidProjectFiles.write(
            file,
            documents,
            project.files.flatMap { it.designs.entries }.associate { it.value to it.key },
          )
    }
    val manifest = project.files.firstOrNull { it.kind == "manifest" }
    if (manifest == null || project.files.any { it.blob == null && it.kind != "manifest" }) {
      val previous =
        manifest?.let { PROJECT_JSON.parseToJsonElement(it.original).jsonObject }
          ?: JsonObject(emptyMap())
      val generated =
        PROJECT_JSON.encodeToJsonElement(
            ProjectManifest(
              id = previous["id"]?.jsonPrimitive?.content ?: project.id,
              name = previous["name"]?.jsonPrimitive?.content ?: project.name,
              files =
                project.files
                  .filter { it.kind == "design" }
                  .map { ProjectManifestFile(it.id, it.path) },
              resources =
                project.files
                  .filter { it.kind in setOf("components", "tokens", "themes") }
                  .groupBy { it.kind }
                  .mapValues { (_, entries) -> entries.map { it.path } },
            )
          )
          .jsonObject
      files["project.json"] =
        PROJECT_JSON.encodeToString(JsonObject.serializer(), JsonObject(previous + generated)) +
          "\n"
    }
    val digest =
      projectDigest(
        files.toSortedMap().entries.joinToString("\u0000") {
          it.key + "\u0000" + projectDigest(it.value)
        }
      )
    return ProjectReview(
      project.revision,
      digest,
      files,
      files.keys.filter { path ->
        project.files.firstOrNull { it.path == path }?.original != files[path] ||
          project.files.firstOrNull { it.path == path }?.blob == null
      },
    )
  }

  suspend fun publish(
    actor: AuthenticatedUiBuilderActor,
    id: String,
    request: ProjectPublish,
    token: String,
  ): BuilderProject = writes.withLock {
    val project = permitted(actor, id, write = true)
    check(project.revision == request.baseRevision) { "project changed; review again" }
    val reviewed = review(actor, id)
    check(reviewed.digest == request.reviewDigest) {
      "design changed after review; review again before publishing"
    }
    val manifest = project.files.firstOrNull { it.path == "project.json" }
    val candidate =
      if (manifest == null)
        project.copy(
          files =
            project.files +
              ProjectFile(
                "project-manifest",
                "project.json",
                reviewed.files.getValue("project.json"),
                kind = "manifest",
              )
        )
      else project
    val publication = github.publish(candidate, reviewed.files, token)
    val next = candidate.copy(revision = project.revision + 1, publication = publication)
    store.save(next, project.revision)
    next
  }

  private suspend fun importFiles(
    project: BuilderProject,
    additions: List<ProjectFile>,
  ): BuilderProject {
    require(project.files.size + additions.size <= 100) { "project file limit exceeded" }
    val parsed =
      additions
        .filter { it.kind == "design" }
        .associate { it.id to ServeUiBuilderUidProjectFiles.designs(it.original) }
    val mappings =
      project.files.flatMap { it.designs.entries.map { e -> e.key to e.value } } +
        parsed.flatMap { (file, docs) ->
          docs.map { it.id to projectDesignId(project.id, file, it.id) }
        }
    val navigation =
      mappings
        .groupBy { it.first }
        .filterValues { it.size == 1 }
        .mapValues { it.value.single().second }
    val created = mutableListOf<String>()
    val owner = AuthenticatedUiBuilderActor(project.owner)
    try {
      val files = additions.map { file ->
        require(file.original.toByteArray().size <= MAX_PROJECT_FILE_BYTES) {
          "project file too large"
        }
        if (file.kind != "design") {
          validateResource(file.original)
          file
        } else {
          require(file.path.endsWith(".uid")) { "design path must end in .uid" }
          val documents = parsed.getValue(file.id)
          val mapping = documents.associate { it.id to projectDesignId(project.id, file.id, it.id) }
          val seeds = linkedMapOf<String, String>()
          val homes = linkedMapOf<String, DesignHomeV1?>()
          for (document in documents) {
            val id = mapping.getValue(document.id)
            val home =
              document.home
                ?: if (project.source != null)
                  DesignHomeV1.Repo(
                    (project.source.root.takeIf { it.isNotEmpty() }?.plus('/') ?: "") + file.path
                  )
                else serverOrigin()?.let { DesignHomeV1.Server(it, id) }
            val candidate =
              document.copy(
                id = id,
                revision = 0,
                home = home,
                createdAtEpochMillis = null,
                updatedAtEpochMillis = null,
              )
            val response =
              withDesignCreationVisibility(UiBuilderDefaultVisibility.PRIVATE) {
                service.execute(
                  UiBuilderServiceCall(
                    owner,
                    UiBuilderServiceRequest.CreateDesign(
                      remapProjectNavigation(candidate, navigation)
                    ),
                  )
                )
              }
            require(response is UiBuilderServiceResponse.Snapshot) { serviceFailure(response) }
            created += id
            seeds[document.id] = documentDigest(response.snapshot.state.document)
            homes[document.id] = response.snapshot.state.document.home
          }
          file.copy(designs = mapping, seedDigests = seeds, homes = homes)
        }
      }
      val existing = store.read(project.id)
      val next =
        project.copy(revision = (existing?.revision ?: -1) + 1, files = project.files + files)
      store.save(next, existing?.revision)
      return next
    } catch (failure: Exception) {
      // A rejected import leaves no half-project and never touches pre-existing designs.
      created.forEach {
        service.execute(UiBuilderServiceCall(owner, UiBuilderServiceRequest.DeleteDesign(it)))
      }
      throw failure
    }
  }

  private suspend fun snapshot(actor: AuthenticatedUiBuilderActor, id: String): DesignDocumentV1 {
    val response =
      service.execute(UiBuilderServiceCall(actor, UiBuilderServiceRequest.GetSnapshot(id, null)))
    require(response is UiBuilderServiceResponse.Snapshot) { serviceFailure(response) }
    return response.snapshot.state.document
  }
}

private fun validateResource(text: String) {
  require(text.toByteArray().size <= MAX_PROJECT_FILE_BYTES) { "resource file too large" }
  require(PROJECT_JSON.parseToJsonElement(text) is JsonObject) {
    "project resource must be a JSON object"
  }
}

private fun documentDigest(document: DesignDocumentV1) =
  projectDigest(PROJECT_JSON.encodeToString(DesignDocumentV1.serializer(), document))

private fun serviceFailure(response: UiBuilderServiceResponse): String =
  (response as? UiBuilderServiceResponse.Error)?.error?.message
    ?: "design service did not return a snapshot"

internal class ProjectNotFound : IllegalArgumentException("project not found")

@Serializable
internal data class ProjectCreate(
  val id: String,
  val name: String,
  val repository: String = "",
  val branch: String = "",
  val root: String = "ui-builder",
  val githubToken: String? = null,
)

@Serializable
internal data class ProjectPutFile(
  val baseRevision: Long,
  val fileId: String,
  val path: String,
  val content: String,
  val kind: String = "design",
)

@Serializable
internal data class ProjectMembers(val baseRevision: Long, val members: Map<String, ProjectRole>)

@Serializable
internal data class ProjectPublish(
  val baseRevision: Long,
  val reviewDigest: String,
  val githubToken: String? = null,
)

@Serializable
internal data class ProjectReview(
  val revision: Long,
  val digest: String,
  val files: Map<String, String>,
  val changed: List<String>,
)

internal class ProjectAccessDenied(message: String) : IllegalArgumentException(message)
