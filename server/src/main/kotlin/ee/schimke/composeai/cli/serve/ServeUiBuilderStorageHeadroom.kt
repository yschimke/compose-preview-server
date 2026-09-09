package ee.schimke.composeai.cli.serve

/**
 * How full the UI-builder state file is, and when to say so out loud.
 *
 * `FileUiBuilderStateStorage` refuses a write past its ceiling, and until now nothing reported how
 * close the file was to it: `preview.coo.ee` sat at 24.5 MB of 32 MiB — 73% — for weeks, and the
 * first signal an operator would have got was a designer's save failing
 * (yschimke/compose-preview-server#568). One percentage, computed in one place, so the status row
 * and the startup line cannot drift apart and an alert written against either means the same thing.
 */
internal const val UI_BUILDER_STORAGE_WARNING_PERCENT: Double = 80.0

/** Percent of the ceiling in use, to one decimal. */
internal fun storageUsedPercent(bytes: Long, maximumBytes: Long): Double {
  if (maximumBytes <= 0) return 0.0
  return (bytes.toDouble() / maximumBytes.toDouble() * 1_000).toInt() / 10.0
}

/**
 * The line to print at startup, or null while there is headroom to spare.
 *
 * Deliberately a warning and never a refusal: a host that would not start because its state is
 * large is the failure mode #568 is about. It names the remedy rather than only the number, because
 * "73% full" tells an operator nothing about what to do with it.
 *
 * What the ceiling means changed with the per-design store (#578): the number is a gauge over the
 * whole store, and what actually refuses a save is a design outgrowing `maximumDesignBytes` — so
 * the line says so rather than promising a cliff that is no longer there. One design can no longer
 * stop another from saving, which was the point.
 */
internal fun uiBuilderStorageWarning(bytes: Long, maximumBytes: Long): String? {
  if (maximumBytes <= 0) return null
  val used = storageUsedPercent(bytes, maximumBytes)
  if (used < UI_BUILDER_STORAGE_WARNING_PERCENT) return null
  val megabytes = { value: Long -> String.format("%.1f MB", value / (1024.0 * 1024.0)) }
  return "serve: WARNING UI-builder state is ${megabytes(bytes)} of ${megabytes(maximumBytes)} " +
    "($used% of the ceiling); a save is refused when one design outgrows its own budget, not when " +
    "the store reaches this. Run scripts/ui-builder/state-size-report.mjs against the state " +
    "directory to see where the bytes go, or lower UiBuilderServiceLimits." +
    "retainedRevisionSnapshots and retainedUndoBytes."
}
