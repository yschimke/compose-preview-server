package ee.schimke.composeai.uibuilder.service

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class UiBuilderPersistenceException(message: String, cause: Throwable? = null) :
  IllegalStateException(message, cause)

/**
 * Atomic opaque-state storage. Implementations must replace the complete value or write nothing.
 */
interface UiBuilderStateStorage {
  fun load(): ByteArray?

  fun replace(value: ByteArray)
}

/**
 * Single-process file-backed storage with a cross-process advisory lock and atomic replacement.
 *
 * The service payload carries its own version and checksum. This class bounds bytes before reading
 * or writing, forces the new file before rename, and ignores orphaned temporary files from a crash.
 * It does not provide multi-replica compare-and-set semantics; deployments requiring concurrent
 * writers must supply a transactional [UiBuilderStateStorage].
 */
class FileUiBuilderStateStorage(
  root: Path,
  private val maximumBytes: Long = 32L * 1024L * 1024L,
) : UiBuilderStateStorage {
  private val directory = root.toAbsolutePath().normalize()
  private val stateFile = directory.resolve(STATE_FILE)
  private val lockFile = directory.resolve(LOCK_FILE)

  init {
    require(maximumBytes > 0) { "maximumBytes must be positive" }
    Files.createDirectories(directory)
    require(Files.isDirectory(directory)) { "UI-builder state root is not a directory: $directory" }
  }

  override fun load(): ByteArray? = locked {
    if (!Files.exists(stateFile)) return@locked null
    val size =
      try {
        Files.size(stateFile)
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException("cannot stat UI-builder state at $stateFile", failure)
      }
    if (size > maximumBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder state at $stateFile is $size bytes; limit is $maximumBytes"
      )
    }
    try {
      Files.readAllBytes(stateFile)
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException("cannot read UI-builder state at $stateFile", failure)
    }
  }

  override fun replace(value: ByteArray) {
    if (value.size.toLong() > maximumBytes) {
      throw UiBuilderPersistenceException(
        "UI-builder state is ${value.size} bytes; limit is $maximumBytes"
      )
    }
    locked {
      val temporary = Files.createTempFile(directory, ".$STATE_FILE.", ".tmp")
      try {
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
          val buffer = ByteBuffer.wrap(value)
          while (buffer.hasRemaining()) channel.write(buffer)
          channel.force(true)
        }
        try {
          Files.move(
            temporary,
            stateFile,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
          )
        } catch (_: AtomicMoveNotSupportedException) {
          Files.move(temporary, stateFile, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDirectory()
      } catch (failure: IOException) {
        throw UiBuilderPersistenceException(
          "cannot replace UI-builder state at $stateFile",
          failure,
        )
      } finally {
        Files.deleteIfExists(temporary)
      }
    }
  }

  private fun <T> locked(block: () -> T): T {
    Files.createDirectories(directory)
    return try {
      FileChannel.open(
          lockFile,
          StandardOpenOption.CREATE,
          StandardOpenOption.READ,
          StandardOpenOption.WRITE,
        )
        .use { channel -> channel.lock().use { block() } }
    } catch (failure: UiBuilderPersistenceException) {
      throw failure
    } catch (failure: IOException) {
      throw UiBuilderPersistenceException("cannot lock UI-builder state at $lockFile", failure)
    }
  }

  private fun forceDirectory() {
    try {
      FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    } catch (_: Exception) {
      // Some file systems cannot open a directory. The state file itself was already forced.
    }
  }

  companion object {
    const val STATE_FILE: String = "ui-builder-service-v1.json"
    private const val LOCK_FILE = ".ui-builder-service.lock"
  }
}
