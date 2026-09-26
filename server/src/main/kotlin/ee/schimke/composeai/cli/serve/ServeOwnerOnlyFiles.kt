package ee.schimke.composeai.cli.serve

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/**
 * Directories that only the server's own user can list or enter.
 *
 * The UI-builder's stores — the design state, comments, links, references, folders and thumbnails —
 * hold what people wrote and who they are, as plain JSON. Created with the default umask they are
 * typically world-readable, so any other account on the host could read every design and every
 * comment. Each store's root is therefore `0700`; the files inside are already `0600`, because
 * every store writes through [Files.createTempFile] and moves the result into place, and a
 * temporary file is created owner-only.
 *
 * Tightening happens on every start rather than only on creation, so a directory an earlier release
 * created with the umask is corrected the next time the server opens it.
 *
 * Best-effort, deliberately. On a filesystem with no POSIX permissions (Windows, some mounted
 * volumes) there is nothing to set and the directory is used as it is; a directory this user may
 * not change the mode of is still usable if it was usable before. Neither is worth refusing to
 * start over, and the store's own `require(isDirectory)` still decides whether it can be used.
 */
internal object ServeOwnerOnlyFiles {
  private val OWNER_ONLY_DIRECTORY = PosixFilePermissions.fromString("rwx------")

  /** [Files.createDirectories], then [restrictDirectory] on [directory] itself. */
  fun createDirectories(directory: Path): Path {
    Files.createDirectories(directory)
    restrictDirectory(directory)
    return directory
  }

  /** Make [directory] `0700` where the filesystem has POSIX permissions; true when it now is. */
  fun restrictDirectory(directory: Path): Boolean {
    val view =
      Files.getFileAttributeView(directory, PosixFileAttributeView::class.java) ?: return false
    return try {
      view.setPermissions(OWNER_ONLY_DIRECTORY)
      true
    } catch (_: IOException) {
      false
    } catch (_: UnsupportedOperationException) {
      false
    } catch (_: SecurityException) {
      false
    }
  }
}
