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
  val designs = File(stateDirectory, UiBuilderDesignStateStore.DESIGNS_DIRECTORY)
  return if (marker.exists()) {
    // Every one of these commands ends by removing or renaming the marker, because the marker is
    // the commit point here as it is in the store: it is what the next start reads to decide what
    // it is looking at. A command that moved it first and then failed — and the thing that could
    // not be moved is a fair candidate for whatever stopped the store opening — would leave the
    // next start writing a fresh marker over the tree the operator was trying to get away from.
    // Ordered this way a half-done recovery leaves the store exactly as it is now.
    //
    // And `designs/` is only named when it is there: a store that has never held a design does not
    // have one, and `mv` on a missing path fails, which `&&` would turn into the marker never
    // moving at all.
    val moveDesigns = if (designs.exists()) "mv ${designs.path} ${designs.path}.broken && " else ""
    preamble +
      "either move the store aside to start empty (${moveDesigns}mv ${marker.path} " +
      "${marker.path}.broken — the marker goes last, so a rename that fails leaves a store that " +
      "still opens" +
      (if (designs.exists()) "; the designs are then lost, so copy them first" else "") +
      ")" +
      (if (migrated.exists()) {
        // The store may hold designs created since the migration, and the next start would migrate
        // the old file straight back into the same tree — so a rollback that left `designs/` in
        // place would not be the state being rolled back to. It goes first, and it is kept. The
        // legacy file is put back before the marker goes, for the reason above: the other order
        // can leave a start with neither a marker nor a state file, which is an empty store and a
        // rollback file nothing will ever read again.
        ", or roll back to the state this store was migrated from (" +
          (if (designs.exists()) {
            "mv ${designs.path} ${designs.path}.v3 && "
          } else {
            ""
          }) +
          "mv ${migrated.path} ${stateFile.path} && rm ${marker.path}" +
          (if (designs.exists()) {
            " — designs created since the migration are in designs.v3 and are not in that file"
          } else {
            ""
          }) +
          ")"
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
    val partial = designs
    val partialNote =
      if (partial.exists()) {
        "; move ${partial.path} aside as well — a migration that did not finish left it, and it is " +
          "part of the old state rather than a store"
      } else {
        ""
      }
    // The rename of the legacy file deliberately precedes the marker, so the migration can be
    // retried from it rather than half-committed. That leaves one window in this branch where the
    // state file is already gone and `.migrated` is the state: recovery here is putting it back,
    // not restoring a backup of a file that is no longer the newest thing on the disk.
    if (migrated.exists() && !stateFile.exists()) {
      return preamble +
        "put the migrated state back and let the migration run again (mv ${migrated.path} " +
        "${stateFile.path}$partialNote), or move it aside to start empty (the designs in it are " +
        "then lost, so copy it first), or pass --ui-builder-state-dir none to run without the " +
        "builder deliberately."
    }
    preamble +
      "either restore the one-generation backup (cp ${backupFile.path} ${stateFile.path}), or " +
      "move ${stateFile.path} aside to start empty (the designs in it are then lost, so copy it " +
      "first" +
      partialNote +
      "), or pass --ui-builder-state-dir none to run without the builder deliberately."
  }
}
