package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.UiBuilderPersistenceException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The state-file failures that used to abort `serve`, and what an operator is told instead.
 *
 * Each case builds a **real** broken state file and provokes the real exception rather than a
 * synthetic one, because the thing under test is the claim that these failures are survivable — a
 * hand-made `RuntimeException` would prove nothing about the storage that produced the outage in
 * yschimke/compose-preview-server#568.
 */
class ServeUiBuilderLaneGuardTest {
  private fun stateDirectory(): File = Files.createTempDirectory("ui-builder-guard").toFile()

  private fun writeState(directory: File, content: String) {
    File(directory, FileUiBuilderStateStorage.STATE_FILE).writeText(content)
  }

  /** What the lane does today: opening the storage and reading it is what throws. */
  private fun open(directory: File): ByteArray? =
    FileUiBuilderStateStorage(directory.toPath()).load()

  @Test
  fun `an oversize state file is a warning, not an exit`() {
    val directory = stateDirectory()
    writeState(directory, "x".repeat(4_096))

    val failure =
      assertFailsWith<UiBuilderPersistenceException> {
        FileUiBuilderStateStorage(directory.toPath(), maximumBytes = 64).load()
      }
    val warning = uiBuilderDisabledWarning(directory, failure)

    assertTrue(warning.startsWith("serve: WARNING the UI builder is disabled"), warning)
    assertTrue(warning.contains("4096 bytes"), warning)
    assertTrue(warning.contains("Everything else on this host is unaffected"), warning)
  }

  @Test
  fun `the warning names all three recovery paths and the files they act on`() {
    val directory = stateDirectory()

    val warning = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("checksum"))

    assertTrue(warning.contains(FileUiBuilderStateStorage.STATE_FILE), warning)
    assertTrue(warning.contains(FileUiBuilderStateStorage.BACKUP_FILE), warning)
    assertTrue(warning.contains("--ui-builder-state-dir none"), warning)
    // Moving the file aside loses the designs in it; saying so is the difference between a remedy
    // and a trap.
    assertTrue(warning.contains("copy it first"), warning)
  }

  @Test
  fun `a failure that is not about the stored bytes offers no state-file remedy`() {
    // Advising "restore the backup" for a failure the state file had nothing to do with sends an
    // operator to overwrite a good file while the real cause goes unfixed.
    val warning = uiBuilderDisabledWarning(stateDirectory(), IllegalStateException("renderer gone"))

    assertTrue(warning.contains("renderer gone"), warning)
    assertTrue(warning.contains("not a failure of the stored state"), warning)
    assertFalse(warning.contains("cp --"), warning)
    assertFalse(warning.contains("--ui-builder-state-dir none"), warning)
  }

  @Test
  fun `the printed command survives a directory a shell would otherwise mangle`() {
    val awkward = File(stateDirectory(), "state dir with spaces & \$vars")
    awkward.mkdirs()

    val warning = uiBuilderDisabledWarning(awkward, UiBuilderPersistenceException("bad json"))

    // Single-quoted, so the shell expands none of it, and `--` so a leading dash is not an option.
    assertTrue(warning.contains("cp -- '"), warning)
    assertTrue(warning.contains("state dir with spaces & \$vars"), warning)
    assertFalse(warning.contains("cp -- $awkward"), "an unquoted path would split on the spaces")
  }

  @Test
  fun `a relative state directory is reported as an absolute path`() {
    val warning =
      uiBuilderDisabledWarning(File("ui-builder-state"), UiBuilderPersistenceException("x"))

    // A relative path resolves against whoever reads the log, not against the server.
    assertTrue(warning.contains(File("ui-builder-state").absolutePath), warning)
  }

  @Test
  fun `a single quote in the path does not break out of the quoting`() {
    val warning =
      uiBuilderDisabledWarning(File("/srv/it's here"), UiBuilderPersistenceException("x"))

    assertTrue(warning.contains("it'\\''s here"), warning)
  }

  @Test
  fun `a failure with no message still names something an operator can search for`() {
    val warning = uiBuilderDisabledWarning(stateDirectory(), UiBuilderPersistenceException(""))

    assertTrue(warning.contains("UiBuilderPersistenceException"), warning)
  }

  @Test
  fun `a corrupt state file throws where the lane opens it, and reads back for diagnosis`() {
    val directory = stateDirectory()
    writeState(directory, "{ this is not json")

    // The state is unreadable to the service, and the bytes are still on disk afterwards: the
    // recovery path the warning describes depends on the host not having tidied them away.
    assertTrue(open(directory)!!.isNotEmpty())
    assertEquals(
      "{ this is not json",
      File(directory, FileUiBuilderStateStorage.STATE_FILE).readText(),
    )
  }

  @Test
  fun `an empty state directory opens to nothing rather than failing`() {
    assertEquals(null, open(stateDirectory()), "a first start has no state and is not a failure")
  }
}
