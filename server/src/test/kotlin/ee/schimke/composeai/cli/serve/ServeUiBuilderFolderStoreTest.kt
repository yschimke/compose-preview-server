package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServeUiBuilderFolderStoreTest {
  private val root = Files.createTempDirectory("folder-store")
  private val store = ServeUiBuilderFolderStore(root)

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `a move is shared state that survives reopening the store`() {
    assertEquals(
      FolderWriteResult.Stored,
      store.move("golden-widget-alarm-large", "golden-widgets"),
    )

    val reopened = ServeUiBuilderFolderStore(root)
    assertEquals(
      mapOf("golden-widget-alarm-large" to "golden-widgets"),
      reopened.readAll(),
    )
  }

  @Test
  fun `moving to no folder removes the persistent record`() {
    store.move("google-gmail-tablet", "google-1p-samples")
    assertTrue(store.readAll().isNotEmpty())

    assertEquals(FolderWriteResult.Stored, store.move("google-gmail-tablet", null))
    assertEquals(emptyMap(), store.readAll())
  }

  @Test
  fun `folder names are trimmed and bounded`() {
    store.move("google-keep-tablet", "  google-1p-samples  ")
    assertEquals("google-1p-samples", store.readAll()["google-keep-tablet"])

    assertIs<FolderWriteResult.Refused>(store.move("google-keep-tablet", "x".repeat(161)))
    assertEquals("google-1p-samples", store.readAll()["google-keep-tablet"])
  }

  @Test
  fun `design ids are digested rather than used as paths`() {
    store.move("../../escape", "folder")

    val files = Files.list(root).use { it.toList() }
    assertEquals(1, files.size)
    assertEquals(root, files.single().parent)
    assertEquals("folder", store.readAll()["../../escape"])
  }

  @Test
  fun `a corrupt sidecar cannot take the design index down`() {
    store.move("one", "folder")
    Files.list(root).use { entries -> entries.forEach { Files.writeString(it, "{ not json") } }

    assertEquals(emptyMap(), store.readAll())
  }
}
