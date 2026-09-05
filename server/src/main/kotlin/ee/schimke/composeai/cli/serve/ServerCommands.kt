package ee.schimke.composeai.cli.serve

/**
 * The server binary's front door: which command was asked for, and what argv it implies.
 *
 * Until now `compose-preview-server` had exactly one command path — flags, optionally behind a
 * `serve` alias — and told anyone who typed anything else that the alias existed
 * ([#301](https://github.com/yschimke/compose-preview-server/issues/301),
 * [#9](https://github.com/yschimke/compose-preview-server/issues/9)). The playground lane and the
 * UI builder were reachable only by knowing which flags to combine, which is not a surface anyone
 * can discover.
 *
 * **Parsing is separated from doing** so the whole surface is a pure function of argv and can be
 * pinned by tests without starting a server. In particular the bare-flags invocation
 * (`compose-preview-server --module app`) is a *contract*, not an accident: breaking it would be a
 * second breaking change on the heels of 3.0.0, so it has a test of its own.
 *
 * Every command is still `serve` underneath — [Invocation.Run] carries the argv the server should
 * run with, and the commands differ only in the flags they add. That is deliberate: a command that
 * was its own code path would be a second server to keep working.
 */
internal object ServerCommands {

  const val SERVE: String = "serve"
  const val PLAYGROUND: String = "playground"
  const val UI: String = "ui"
  const val HELP: String = "help"

  /** Every command name, in the order `help` lists them. */
  val NAMES: List<String> = listOf(SERVE, UI, PLAYGROUND, HELP)

  /** The commands that run a server; [HELP] prints instead. */
  private val RUNNABLE = setOf(SERVE, PLAYGROUND, UI)

  sealed interface Invocation {
    /** Run a server: [command] chooses which flags are implied, [args] is everything after it. */
    data class Run(val command: String, val args: List<String>) : Invocation

    /** Print usage; [topic] is a command name, or null for the command list. */
    data class Help(val topic: String?) : Invocation

    /** A first word that is neither a flag nor a command. */
    data class Unknown(val command: String) : Invocation
  }

  fun parse(rawArgs: List<String>): Invocation {
    val first = rawArgs.firstOrNull() ?: return Invocation.Run(SERVE, rawArgs)
    // A leading flag is the pre-command invocation the 3.0.0 binary took, and it keeps working
    // exactly as it did: flags mean `serve`. Nothing about the command surface may require an
    // existing caller to learn a word.
    if (first.startsWith("-")) return Invocation.Run(SERVE, rawArgs)
    val rest = rawArgs.drop(1)
    return when {
      first in RUNNABLE -> Invocation.Run(first, rest)
      first == HELP -> {
        val topic = rest.firstOrNull()?.takeUnless { it.startsWith("-") }
        when {
          topic == null || topic in NAMES -> Invocation.Help(topic)
          else -> Invocation.Unknown(topic)
        }
      }
      else -> Invocation.Unknown(first)
    }
  }

  /**
   * The argv [command] runs with, given the argv the user typed.
   *
   * `ui` is absent here because its extra flags name files that do not exist until the caller has
   * found the packaged builder and chosen a record path — see [LocalUiBuilder.serveArgs].
   */
  fun serveArgs(command: String, args: List<String>): List<String> =
    when (command) {
      PLAYGROUND -> playgroundArgs(args)
      else -> args
    }

  /**
   * `playground` is `serve` with the compile lane admitted.
   *
   * `--playground` (runtime-selected catalogs) is added only when the caller pinned nothing
   * themselves: `--playground-bundle` / `--playground-android-bundle` pin one catalog per mode for
   * the life of the process, and adding the unpinned lane on top of an explicit pin would quietly
   * widen what the operator asked for.
   */
  fun playgroundArgs(args: List<String>): List<String> {
    val alreadyRequested =
      "--playground" in args ||
        "--playground-bundle" in args ||
        "--playground-android-bundle" in args
    return if (alreadyRequested) args else args + "--playground"
  }

  /** The command list, which is what `help` with no topic answers. */
  fun commandListing(): String =
    """
    compose-preview-server <command> [options]

    Commands:
      serve             Host previews: fetched bundles, published catalogs, or a local module's
                        @Preview functions (with --module / --discover and a build host).
      ui                Build this project's previews and open the Compose UI builder against
                        them. Needs a `compose-preview` build host.
      playground        serve with the snippet compile lane admitted (POST /api/{v}/compiler/run).
      help [command]    Show this list, or one command's options.

    Flags may also be passed with no command at all — `compose-preview-server --module app` is
    exactly `compose-preview-server serve --module app`, and stays supported.

    `help serve` lists every server flag; `help ui` explains the builder lane.
    """
      .trimIndent()

  /** What an unrecognised first word is told. Names the commands rather than one alias. */
  fun unknownCommandMessage(command: String): String =
    "Unknown command '$command'. Commands: ${NAMES.joinToString(", ")}. " +
      "Server flags may also be passed directly (`compose-preview-server --module app`)."
}
