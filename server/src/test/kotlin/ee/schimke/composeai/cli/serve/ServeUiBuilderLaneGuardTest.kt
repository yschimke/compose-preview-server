package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.UiBuilderDesignStateStore
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
  fun `a migration that did not finish is named on the recovery path it fails on`() {
    val directory = stateDirectory()
    writeState(directory, "{ this is not json")
    // What a migration that wrote designs and never reached its marker leaves behind.
    File(directory, "designs").mkdirs()

    val warning = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("checksum"))

    assertTrue(warning.contains("${directory.path}/designs"), warning)
    assertTrue(warning.contains("did not finish"), warning)
  }

  @Test
  fun `a migration that renamed the state and never reached its marker points at the rename`() {
    val directory = stateDirectory()
    // The window the migration's own ordering creates: the legacy file is renamed before the marker
    // is written, so that a rename that failed can be retried rather than half-committed. If the
    // marker write is what fails, there is no marker, no state file, and the state is the
    // `.migrated` file — which the backup-and-move-aside advice would send an operator straight
    // past.
    File(directory, FileUiBuilderStateStorage.STATE_FILE + ".migrated").writeText("{}")
    File(directory, "designs").mkdirs()

    val warning = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("disk full"))

    assertTrue(
      warning.contains("mv ${directory.path}/ui-builder-service-v1.json.migrated"),
      warning,
    )
    assertFalse(warning.contains("cp "), "there is no backup of a file that has been renamed away")
    assertTrue(warning.contains("${directory.path}/designs"), warning)
    // And starting empty has to move the partial tree too: left in place, the next start finds
    // neither a marker nor a state file, writes a fresh marker, and serves whichever subset of the
    // designs the migration had written.
    assertTrue(warning.contains("${directory.path}/designs.aside"), warning)
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

  @Test
  fun `on the per-design store the remedies are the store's, not the old file's`() {
    val directory = stateDirectory()
    UiBuilderDesignStateStore.open(directory.toPath())
    File(directory, "designs").mkdirs()

    val warning = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("marker"))

    assertTrue(warning.contains("designs.broken"), warning)
    // The marker is one of the things that can fail here, so a recovery that left it in place
    // would send the operator round the same failure on the next start.
    assertTrue(warning.contains("store.json.broken"), warning)
    // And the marker goes last: a recovery that removed it first and then failed to move the
    // designs would leave the next start writing a fresh marker over the tree it was told to
    // start without.
    assertTrue(
      warning.indexOf("designs.broken") < warning.indexOf("store.json.broken"),
      warning,
    )
    assertTrue(warning.contains("--ui-builder-state-dir none"), warning)
    assertFalse(
      warning.contains(FileUiBuilderStateStorage.BACKUP_FILE),
      "there is no one-generation backup to restore: retained revisions are the generations",
    )
  }

  @Test
  fun `a migrated store offers the file it was migrated from as the rollback`() {
    val directory = stateDirectory()
    UiBuilderDesignStateStore.open(directory.toPath())
    File(directory, "designs").mkdirs()
    // What the migration leaves behind: the v2 file, renamed rather than deleted.
    File(directory, FileUiBuilderStateStorage.STATE_FILE + ".migrated").writeText("{}")

    val warning = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("marker"))

    assertTrue(warning.contains(FileUiBuilderStateStorage.STATE_FILE + ".migrated"), warning)
    // The store may hold designs created since the migration; rolling back without moving them
    // aside would migrate the old file into the same tree and keep them.
    assertTrue(warning.contains("designs.v3"), warning)
    // And the marker is removed last here too: the other order can leave a start with neither a
    // marker nor a state file, which is an empty store beside a rollback file nothing reads again.
    assertTrue(
      warning.indexOf(FileUiBuilderStateStorage.STATE_FILE + ".migrated ") <
        warning.indexOf("rm ${File(directory, UiBuilderDesignStateStore.STORE_FILE).path}"),
      warning,
    )
  }

  @Test
  fun `a store that has never held a design still says how to start empty`() {
    val directory = stateDirectory()
    // `designs/` is not created until the first commit, and `mv` on a path that is not there fails
    // — which, chained with `&&`, would stop the marker being moved at all and send the operator
    // round the same failure.
    UiBuilderDesignStateStore.open(directory.toPath())
    assertFalse(File(directory, "designs").exists(), "the store has never held a design")

    val warning = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("marker"))

    assertFalse(warning.contains("designs.broken"), warning)
    assertTrue(warning.contains("mv ${File(directory, "store.json").path} "), warning)

    // The same holds for the rollback, which would otherwise offer designs.v3 as the place designs
    // created since the migration are — a directory this recovery would never create.
    File(directory, FileUiBuilderStateStorage.STATE_FILE + ".migrated").writeText("{}")
    val rollback = uiBuilderDisabledWarning(directory, UiBuilderPersistenceException("marker"))
    assertTrue(rollback.contains(".migrated"), rollback)
    assertFalse(rollback.contains("designs.v3"), rollback)
  }
}
