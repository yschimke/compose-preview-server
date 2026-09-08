package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import java.io.File

/**
 * What `serve` prints when the UI-builder lane cannot be opened, instead of exiting.
 *
 * The UI builder is one optional lane on a host that also serves previews, catalogs, renders and a
 * site. A design document the current catalog cannot serve already degrades to a quarantined design
 * rather than a dead host, but a failure in the **state file itself** — unreadable, oversize, bad
 * UTF-8, bad JSON, a checksum mismatch, an unsupported format — happens before any per-design
 * quarantine can apply, and used to propagate out of `main`. In production that meant the container
 * never bound 8080, the healthcheck never passed, and `docker rollout` rolled the deploy back while
 * the previous container kept serving: the deployed version silently stayed a release behind, twice
 * (yschimke/compose-preview-server#568).
 *
 * So the lane is disabled and this is printed. It names the failure, the file, and the three things
 * an operator can actually do about it, because "UI-builder state failed to load" on its own sends
 * them reading source at the worst possible moment.
 */
internal fun uiBuilderDisabledWarning(stateDirectory: File, failure: Throwable): String {
  val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "unknown"
  val stateFile = File(stateDirectory, FileUiBuilderStateStorage.STATE_FILE)
  val backupFile = File(stateDirectory, FileUiBuilderStateStorage.BACKUP_FILE)
  return "serve: WARNING the UI builder is disabled — its state could not be opened: $reason. " +
    "Everything else on this host is unaffected and serving. To recover, either restore the " +
    "one-generation backup (cp ${backupFile.path} ${stateFile.path}), or move ${stateFile.path} " +
    "aside to start empty (the designs in it are then lost, so copy it first), or pass " +
    "--ui-builder-state-dir none to run without the builder deliberately."
}
