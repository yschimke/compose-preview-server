package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.FileUiBuilderStateStorage
import ee.schimke.composeai.uibuilder.service.UiBuilderPersistenceException
import java.io.File
import java.io.IOException

/**
 * An operator's configuration is wrong, and no amount of degrading will fix it.
 *
 * The distinction the lane guard turns on. A state file that cannot be read is the host's problem
 * and the lane degrades around it; a component record the operator *named* and that cannot be
 * loaded is a typo only they can correct, and starting without the pack they asked for would hide
 * it. Thrown past [uiBuilderDisabledWarning] deliberately, so it still stops `serve`.
 */
internal class UiBuilderConfigurationException(message: String) : IllegalStateException(message)

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
 * So the lane is disabled and this is printed. It names the failure, and — **only** when the
 * failure is actually about the stored bytes — the three things an operator can do about it.
 * Offering "restore the backup" for a failure that has nothing to do with the state file would send
 * someone to overwrite a perfectly good one while the real cause went unfixed, which is worse than
 * saying less.
 */
internal fun uiBuilderDisabledWarning(stateDirectory: File, failure: Throwable): String {
  val reason = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName ?: "unknown"
  val opening =
    "serve: WARNING the UI builder is disabled — it could not be opened: $reason. " +
      "Everything else on this host is unaffected and serving."
  if (failure !is UiBuilderPersistenceException && failure !is IOException) {
    return "$opening This is not a failure of the stored state, which was left untouched at " +
      "${shellQuote(absolutePath(stateDirectory))}."
  }
  val stateFile = absolutePath(File(stateDirectory, FileUiBuilderStateStorage.STATE_FILE))
  val backupFile = absolutePath(File(stateDirectory, FileUiBuilderStateStorage.BACKUP_FILE))
  return "$opening To recover, either restore the one-generation backup " +
    "(cp -- ${shellQuote(backupFile)} ${shellQuote(stateFile)}), or move ${shellQuote(stateFile)} " +
    "aside to start empty (the designs in it are then lost, so copy it first), or pass " +
    "--ui-builder-state-dir none to run without the builder deliberately."
}

/**
 * An absolute, normalized path, because a relative one resolves against whoever reads the log.
 *
 * `canonicalFile` follows symlinks and can touch the filesystem, so it falls back to `absoluteFile`
 * rather than letting a path lookup fail the warning that exists because something already failed.
 */
private fun absolutePath(file: File): String = runCatching {
  file.canonicalPath
}
  .getOrElse { file.absolutePath }

/**
 * A path the operator can paste into a shell, whatever the deployment called its directories.
 *
 * Single quotes with the standard `'\''` escape: inside single quotes a shell expands nothing, so
 * spaces, `$`, backticks and globs in a `--ui-builder-state-dir` all survive being copied out of a
 * deploy log. Paired with `cp --` so a path that begins with `-` is not read as an option.
 */
private fun shellQuote(path: String): String = "'" + path.replace("'", "'\\''") + "'"
