package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class ServeOwnerOnlyFilesTest {
  @TempDir lateinit var root: Path

  private fun mode(path: Path): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

  private fun assumePosix() =
    assumeTrue(Files.getFileAttributeView(root, PosixFileAttributeView::class.java) != null)

  @Test
  fun `every UI-builder store root is owner-only, and so is what it writes`() {
    assumePosix()
    val comments = ServeUiBuilderCommentStore(root.resolve("comments"))
    ServeUiBuilderLinksStore(root.resolve("links"))
    ServeUiBuilderReferenceStore(root.resolve("references"))
    ServeUiBuilderFolderStore(root.resolve("folders"))
    for (name in listOf("comments", "links", "references", "folders")) {
      assertEquals("rwx------", mode(root.resolve(name)), name)
    }
    comments.post("design", "github:someone", CommentPostRequest(body = "hello"))
    val written = Files.list(root.resolve("comments")).use { it.toList() }.single()
    assertEquals("rw-------", mode(written))
  }

  @Test
  fun `a directory an earlier release left open is tightened when it is opened again`() {
    assumePosix()
    val existing = Files.createDirectories(root.resolve("comments"))
    Files.setPosixFilePermissions(existing, PosixFilePermissions.fromString("rwxr-xr-x"))

    ServeUiBuilderCommentStore(existing)

    assertEquals("rwx------", mode(existing))
  }
}
