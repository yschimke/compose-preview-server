package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Decides which build host, if any, this invocation should use.
 *
 * Three sources, most explicit first, because the failure this ordering prevents is a server
 * silently using a `compose-preview` from `PATH` that is not the one the operator meant:
 *
 * 1. `--build-host <path>` — say exactly which binary.
 * 2. `COMPOSE_PREVIEW_BUILD_HOST` — the same, for a container that sets environment rather than
 *    argv.
 * 3. `compose-preview` on `PATH` — the convenience case, and the only one that can pick something
 *    unintended, which is why it is last and why choosing it is announced.
 *
 * None found is not an error. A server hosting published catalogs and prebuilt bundles has no use
 * for a Gradle build, and that is the common deployed case.
 */
internal object BuildHostDiscovery {

  const val FLAG: String = "--build-host"
  const val ENV: String = "COMPOSE_PREVIEW_BUILD_HOST"
  private const val DEFAULT_BINARY = "compose-preview"

  /** `--build-host none` turns the search off for an operator who wants it definitely unused. */
  private const val DISABLED = "none"

  /**
   * The binary to run, and where the decision came from.
   *
   * The binary alone, not a command line: [ProcessBuildHost.spawn] builds the argv, so the
   * subcommand and the transport flag live in exactly one place.
   */
  data class Choice(val binary: String, val source: String)

  fun choose(
    args: List<String>,
    env: (String) -> String? = System::getenv,
    pathLookup: (String) -> File? = ::onPath,
  ): Choice? {
    val explicit = flagValue(args) ?: env(ENV)?.takeIf { it.isNotBlank() }
    if (explicit != null) {
      if (explicit.trim().equals(DISABLED, ignoreCase = true)) return null
      val from = if (flagValue(args) != null) FLAG else ENV
      return Choice(explicit.trim(), from)
    }
    val found = pathLookup(DEFAULT_BINARY) ?: return null
    return Choice(found.path, "PATH")
  }

  private fun flagValue(args: List<String>): String? {
    val index = args.indexOf(FLAG)
    if (index < 0 || index + 1 >= args.size) return null
    return args[index + 1].takeIf { it.isNotBlank() }
  }

  /**
   * The first executable named [binary] on `PATH`.
   *
   * Deliberately does not consult the working directory: a build host resolved from `.` would let a
   * checked-out repository decide what a server executes, which is a different and much worse thing
   * than a missing build host.
   */
  private fun onPath(binary: String): File? =
    System.getenv("PATH")
      ?.split(File.pathSeparator)
      ?.asSequence()
      ?.filter { it.isNotBlank() }
      ?.map { File(it, binary) }
      ?.firstOrNull { it.isFile && it.canExecute() }
}
