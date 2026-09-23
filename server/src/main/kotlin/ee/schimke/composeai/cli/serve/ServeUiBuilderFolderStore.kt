package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Shared folders for the designs on this host.
 *
 * A folder is file-manager state, not design content: moving a design must not advance its
 * revision. The record is stored beside the design state and is shared by every collaborator who
 * can open that design; changing it therefore requires the design's WRITE action.
 */
class ServeUiBuilderFolderStore(private val root: Path) {
  init {
    Files.createDirectories(root)
    require(Files.isDirectory(root)) { "UI-builder folders root is not a directory: $root" }
  }

  internal fun readAll(): Map<String, String> {
    val files =
      try {
        Files.list(root).use { entries ->
          entries.filter { it.toString().endsWith(".json") }.toList()
        }
      } catch (_: IOException) {
        return emptyMap()
      }
    return files.mapNotNull(::readRecord).associate { it.designId to it.folder }
  }

  internal fun move(designId: String, folder: String?): FolderWriteResult {
    val normalized = folder?.trim()?.takeIf { it.isNotEmpty() }
    if (
      normalized != null && normalized.toByteArray(StandardCharsets.UTF_8).size > MAX_FOLDER_BYTES
    ) {
      return FolderWriteResult.Refused("folder names must be at most $MAX_FOLDER_BYTES bytes")
    }
    if (normalized == null) {
      return try {
        Files.deleteIfExists(fileFor(designId))
        FolderWriteResult.Stored
      } catch (_: IOException) {
        FolderWriteResult.Failed("the folder record could not be cleared from disk")
      }
    }
    return write(FolderRecord(designId = designId, folder = normalized))
  }

  private fun readRecord(file: Path): FolderRecord? {
    if (!Files.exists(file)) return null
    return try {
      if (Files.size(file) > MAX_FILE_BYTES) null
      else
        FOLDER_JSON.decodeFromString(
          FolderRecord.serializer(),
          Files.readString(file, StandardCharsets.UTF_8),
        )
    } catch (_: IOException) {
      null
    } catch (_: SerializationException) {
      null
    }
  }

  private fun write(record: FolderRecord): FolderWriteResult {
    val file = fileFor(record.designId)
    val encoded = FOLDER_JSON.encodeToString(FolderRecord.serializer(), record)
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_FILE_BYTES) {
      return FolderWriteResult.Refused("the folder record is too large")
    }
    return try {
      val temporary = Files.createTempFile(root, "folders", ".tmp")
      try {
        Files.writeString(temporary, encoded, StandardCharsets.UTF_8)
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
      } catch (failure: IOException) {
        Files.deleteIfExists(temporary)
        throw failure
      }
      FolderWriteResult.Stored
    } catch (_: IOException) {
      FolderWriteResult.Failed("the folder record could not be written to disk")
    }
  }

  private fun fileFor(designId: String): Path =
    root.resolve(sha256Hex(designId.toByteArray(StandardCharsets.UTF_8)) + ".json")

  private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private companion object {
    const val MAX_FOLDER_BYTES = 160
    const val MAX_FILE_BYTES = 256 * 1024L
    val FOLDER_JSON = Json { ignoreUnknownKeys = true }
  }
}

@Serializable
internal data class FolderRecord(
  val schema: String = "compose-preview/ui-builder-folders/v1",
  val designId: String,
  val folder: String,
)

internal sealed interface FolderWriteResult {
  data object Stored : FolderWriteResult

  data class Refused(val reason: String) : FolderWriteResult

  data class Failed(val reason: String) : FolderWriteResult
}
