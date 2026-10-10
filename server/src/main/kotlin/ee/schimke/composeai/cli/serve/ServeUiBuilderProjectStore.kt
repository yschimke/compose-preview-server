package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Project metadata and source bytes are committed together, independently of design revisions. */
class ServeUiBuilderProjectStore(private val root: Path) {
  @Volatile private var records: Map<String, BuilderProject> = emptyMap()

  init {
    ServeOwnerOnlyFiles.createDirectories(root)
    records =
      Files.list(root).use { paths ->
        paths
          .filter { it.fileName.toString().endsWith(".json") }
          .map { path ->
            val id = path.fileName.toString().removeSuffix(".json")
            require(PROJECT_ID.matches(id)) { "invalid stored project id" }
            require(Files.size(path) <= MAX_PROJECT_BYTES) {
              "project record exceeds its storage budget"
            }
            PROJECT_JSON.decodeFromString<BuilderProject>(Files.readString(path)).also {
              require(it.id == id && it.schema == "compose-ui-builder-project-state/v1") {
                "invalid project record"
              }
            }
          }
          .toList()
          .associateBy { it.id }
      }
  }

  internal fun all(): List<BuilderProject> = records.values.toList()

  internal fun read(id: String): BuilderProject? = records[id]

  @Synchronized
  internal fun save(project: BuilderProject, expectedRevision: Long?) {
    require(PROJECT_ID.matches(project.id)) { "invalid project id" }
    require(project.name.isNotBlank() && project.name.length <= 160) {
      "project name must be 1–160 characters"
    }
    require(project.members.size <= 100 && project.files.size <= 100) {
      "project member or file limit exceeded"
    }
    require(project.files.map { it.id }.distinct().size == project.files.size) {
      "duplicate file id"
    }
    require(project.files.map { it.path }.distinct().size == project.files.size) {
      "duplicate file path"
    }
    project.files.forEach { validateProjectPath(it.path) }
    val previous = read(project.id)
    check(previous?.revision == expectedRevision) { "project changed; reload before saving" }
    require(project.revision == (expectedRevision ?: -1) + 1) { "invalid project revision" }
    val encoded = PROJECT_JSON.encodeToString(BuilderProject.serializer(), project).toByteArray()
    require(encoded.size <= MAX_PROJECT_BYTES) { "project exceeds its storage budget" }
    val temporary = Files.createTempFile(root, "project-", ".tmp")
    try {
      FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
        val buffer = java.nio.ByteBuffer.wrap(encoded)
        while (buffer.hasRemaining()) channel.write(buffer)
        channel.force(true)
      }
      Files.move(
        temporary,
        root.resolve("${project.id}.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
      records = records + (project.id to project)
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  internal fun projectForDesign(id: String): BuilderProject? =
    all().firstOrNull { p -> p.files.any { id in it.designs.values } }
}

@Serializable
internal data class BuilderProject(
  val schema: String = "compose-ui-builder-project-state/v1",
  val id: String,
  val name: String,
  val owner: String,
  val revision: Long = 0,
  val members: Map<String, ProjectRole> = emptyMap(),
  val source: ProjectRepository? = null,
  val files: List<ProjectFile> = emptyList(),
  val publication: ProjectPublication? = null,
) {
  fun role(actor: AuthenticatedUiBuilderActor): ProjectRole? =
    if (owner in actor.accessIdentities) ProjectRole.OWNER
    else actor.accessIdentities.mapNotNull { members[it] }.maxByOrNull { it.ordinal }
}

@Serializable
internal enum class ProjectRole {
  VIEWER,
  EDITOR,
  OWNER,
}

@Serializable
internal data class ProjectRepository(
  val repository: String,
  val branch: String,
  val root: String = "ui-builder",
  val baseCommit: String,
  val repositoryId: Long,
)

@Serializable
internal data class ProjectFile(
  val id: String,
  val path: String,
  /** Whole source file, including collection siblings and production declarations. */
  val original: String,
  /** Source id to service id. These namespaces are deliberately independent. */
  val designs: Map<String, String> = emptyMap(),
  val blob: String? = null,
  val seedDigests: Map<String, String> = emptyMap(),
  val homes: Map<String, ee.schimke.composeai.uibuilder.protocol.DesignHomeV1?> = emptyMap(),
  val kind: String = "design",
  val working: String? = null,
)

@Serializable
internal data class ProjectPublication(
  val url: String,
  val branch: String,
  val commit: String,
  /** Hashes of the exact bytes proposed; a later edit is still unpublished. */
  val fileDigests: Map<String, String>,
)

@Serializable
internal data class ProjectManifest(
  val schema: String = "compose-ui-builder-project/v1",
  val id: String,
  val name: String,
  val files: List<ProjectManifestFile>,
  val resources: Map<String, List<String>> = emptyMap(),
)

@Serializable internal data class ProjectManifestFile(val id: String, val path: String)

internal val PROJECT_JSON = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
  encodeDefaults = true
  classDiscriminator = "type"
}
internal val PROJECT_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
internal const val MAX_PROJECT_BYTES = 16 * 1024 * 1024L
internal const val MAX_PROJECT_FILE_BYTES = 2 * 1024 * 1024L

internal fun validateProjectPath(path: String) {
  require(
    path.isNotEmpty() && path.length <= 512 && !path.startsWith('/') && !path.contains('\\')
  ) {
    "a project path must be relative"
  }
  require(
    path.split('/').all {
      it.isNotBlank() && it != "." && it != ".." && !it.any(Char::isISOControl)
    }
  ) {
    "invalid project path"
  }
}

internal fun projectDigest(text: String): String =
  MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
    "%02x".format(it)
  }

internal fun projectDesignId(project: String, file: String, design: String): String =
  "p-" + projectDigest("$project\u0000$file\u0000$design").take(40)
