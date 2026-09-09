package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.UiBuilderDesignStateStore
import java.io.File

/**
 * What `serve` prints when the UI-builder lane cannot be opened, instead of exiting.
 *
 * The UI builder is one optional lane on a host that also serves previews, catalogs, renders and a
 * site. A design document the current catalog cannot serve already degrades to a quarantined design
 * rather than a dead host, but a failure in the **state itself** — unreadable, oversize, bad UTF-8,
 * bad JSON, a checksum mismatch, an unsupported format — happened before any per-design quarantine
 * could apply, and used to propagate out of `main`. In production that meant the container never
 * bound 8080, the healthcheck never passed, and `docker rollout` rolled the deploy back while the
 * previous container kept serving: the deployed version silently stayed a release behind, twice
 * (yschimke/compose-preview-server#568).
 *
 * So the lane is disabled and this is printed. It names the failure, the files, and the things an
 * operator can actually do about it, because "UI-builder state failed to load" on its own sends
 * them reading source at the worst possible moment.
 *
 * The remedies differ by store. With the per-design store (#578) a design that cannot be read is
 * one quarantined design and the host serves the rest, so reaching this warning at all means the
 * store marker or the directory itself — and the recovery is the pre-migration file, which the
 * migration renamed rather than deleted.
 */
internal fun uiBuilderDisabledWarning(stateDirectory: File, failure: Throwable): String {
  val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "unknown"
  val marker = File(stateDirectory, UiBuilderDesignStateStore.STORE_FILE)
  val stateFile = File(stateDirectory, FileUiBuilderStateStorage.STATE_FILE)
  val migrated = File(stateDirectory, FileUiBuilderStateStorage.STATE_FILE + ".migrated")
  val preamble =
    "serve: WARNING the UI builder is disabled — its state could not be opened: $reason. " +
      "Everything else on this host is unaffected and serving. To recover, "
  return if (marker.exists()) {
    preamble +
      "either move the store aside to start empty (mv ${marker.path} ${marker.path}.broken && mv " +
      "${stateDirectory.path}/designs ${stateDirectory.path}/designs.broken — the marker goes too, " +
      "or the next start reads the same one back; the designs are then lost, so copy them first)" +
      (if (migrated.exists()) {
        // The store may hold designs created since the migration, and the next start would migrate
        // the old file straight back into the same tree — so a rollback that left `designs/` in
        // place would not be the state being rolled back to. It goes first, and it is kept.
        ", or roll back to the state this store was migrated from (mv ${stateDirectory.path}" +
          "/designs ${stateDirectory.path}/designs.v3 && rm ${marker.path} && mv " +
          "${migrated.path} ${stateFile.path} — designs created since the migration are in " +
          "designs.v3 and are not in that file)"
      } else {
        ""
      }) +
      ", or pass --ui-builder-state-dir none to run without the builder deliberately."
  } else {
    val backupFile = File(stateDirectory, FileUiBuilderStateStorage.BACKUP_FILE)
    // A migration that failed partway leaves design directories with no marker to commit them, and
    // this is the branch that failure lands in: telling an operator to move only the legacy file
    // aside would start them not on an empty store but on whichever designs the migration had got
    // to. So the partial output is named too, and only when it is actually there.
    val partial = File(stateDirectory, "designs")
    preamble +
      "either restore the one-generation backup (cp ${backupFile.path} ${stateFile.path}), or " +
      "move ${stateFile.path} aside to start empty (the designs in it are then lost, so copy it " +
      "first" +
      (if (partial.exists()) {
        "; move ${partial.path} aside as well — a migration that did not finish left it, and it is " +
          "part of the old state rather than a store"
      } else {
        ""
      }) +
      "), or pass --ui-builder-state-dir none to run without the builder deliberately."
  }
}
