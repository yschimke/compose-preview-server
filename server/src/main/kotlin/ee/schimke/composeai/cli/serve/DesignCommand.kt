package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1

/**
 * `compose-preview-server design …` — getting a design's pixels or its source onto disk.
 *
 * ## Why a command
 *
 * Everything this command does was already reachable, and every session re-invented the reaching. A
 * render came out of `curl`ing `/mcp`, unwrapping the JSON-RPC envelope with `jq`, and piping the
 * artifact through `base64 -d`; the generated Kotlin came out of a throwaway JVM test that
 * deserialised a document saved by hand. None of that is hard and all of it is per-session, which
 * is the shape of a command rather than of a note in a README
 * ([#529](https://github.com/yschimke/compose-preview-server/issues/529)).
 *
 * ## A client, not a server
 *
 * Every other command this binary has — `serve`, `ui`, `playground` — starts a server and stays up.
 * This one runs against a server that is already up (`--server`) and exits, which is how anyone
 * would use it against `preview.coo.ee` and keeps every *serving* command in [ServerCommands] a
 * serving command. The cost is named rather than hidden: with no server there is nothing to render
 * against, and a design cannot be rendered straight from a file.
 *
 * ## Parsing is separated from doing
 *
 * As in [ServerCommands], and for the same reason: the whole surface is a pure function of argv and
 * the environment, so it is pinned by tests that open no socket. [DesignCommandRunner] is the half
 * that talks.
 */
internal object DesignCommand {

  const val NAME: String = "design"

  const val LIST: String = "list"
  const val GET: String = "get"
  const val RENDER: String = "render"
  const val EXPORT: String = "export"

  /** Every verb, in the order [usage] lists them. */
  val VERBS: List<String> = listOf(LIST, GET, RENDER, EXPORT)

  /**
   * The credential, from the environment and never from a flag.
   *
   * `--token` would undo the export routes' own rule — a shared link is an address, not a
   * credential — at the first `--verbose`, and would land in a shell history and a CI log besides.
   * [`design-sync.mjs`](../../../../../../../scripts/ui-builder/design-sync.mjs) reached the same
   * conclusion first and says so in the same words.
   *
   * **Which name wins.** Two existed: `COMPOSE_PREVIEW_TOKEN`, which `.mcp.json` sends as
   * `X-Compose-Preview-Token`, and `COMPOSE_PREVIEW_UI_BUILDER_TOKEN`, which `design-sync.mjs`
   * reads. They name the same grant against the same server, so this command reconciles them rather
   * than inventing a third: the header's own name is primary, the script's is still read, and the
   * script now reads both too. A shell that exports one works with either tool.
   */
  const val TOKEN_ENV: String = "COMPOSE_PREVIEW_TOKEN"

  /** The older spelling of [TOKEN_ENV], still honoured. See there. */
  const val LEGACY_TOKEN_ENV: String = "COMPOSE_PREVIEW_UI_BUILDER_TOKEN"

  /** Saves typing `--server` against the same host all session. */
  const val SERVER_ENV: String = "COMPOSE_PREVIEW_SERVER"

  /** `-` as `--out`: write the artifact to stdout, so this composes into a pipe. */
  const val STDOUT: String = "-"

  private const val DEFAULT_LIMIT = 50
  private const val DEFAULT_TIMEOUT_SECONDS = 120L

  /** What one invocation asks for, once argv and the environment have both been read. */
  data class Options(
    val verb: String,
    /** Empty for [LIST], which names no design. */
    val designId: String,
    /** Where the artifact goes; null means the default for [verb]. [STDOUT] means stdout. */
    val out: String?,
    /** The export format, for the two verbs that have one. */
    val format: ExportFormatV1?,
    /** Pins; null renders the current committed revision, as the GET routes do. */
    val revision: Long?,
    val limit: Int,
    val server: String,
    /** Whether a missing or expired credential may start the device-code flow. */
    val authorize: Boolean,
    val timeoutSeconds: Long,
  ) {

    /**
     * The least this verb needs, so an approver is asked for that and not for everything.
     *
     * Reading a design and exporting one are separate capabilities on this server precisely so that
     * they can be granted separately; a command that asked for both every time would make the
     * distinction decorative.
     */
    val capabilities: List<AgentGrantCapability>
      get() =
        when (verb) {
          RENDER,
          EXPORT -> listOf(AgentGrantCapability.UI_BUILDER_EXPORT)
          else -> listOf(AgentGrantCapability.UI_BUILDER_READ)
        }

    /**
     * A made-to-order render is not a published preview, so `render` asks for `live`; everything
     * else reads what is already committed and asks for `preview`.
     */
    val scope: AgentGrantScope
      get() = if (verb == RENDER) AgentGrantScope.LIVE else AgentGrantScope.PREVIEW

    /** Where the artifact goes when the caller named no `--out`. */
    val destination: String
      get() = out ?: defaultOut(verb, designId, format)
  }

  sealed interface Parsed {
    data class Run(val options: Options) : Parsed

    data object Help : Parsed

    /** Something argv asked for that cannot be done; [message] says what, for stderr. */
    data class Invalid(val message: String) : Parsed
  }

  /**
   * Read argv and the environment into an [Options], or say why not.
   *
   * [env] is a parameter so the whole surface — including which token name wins and what `--server`
   * defaults to — is testable without touching the process environment.
   */
  fun parse(args: List<String>, env: (String) -> String? = System::getenv): Parsed {
    if (args.isEmpty()) return Parsed.Help
    val verb = args.first()
    if (verb == "help" || verb == "--help" || verb == "-h") return Parsed.Help
    if (verb !in VERBS) {
      return Parsed.Invalid("design: unknown verb '$verb'. Verbs: ${VERBS.joinToString(", ")}.")
    }
    val rest = args.drop(1)
    if (rest.any { it == "--help" || it == "-h" }) return Parsed.Help

    var designId: String? = null
    var out: String? = null
    var format: String? = null
    var revision: Long? = null
    var limit = DEFAULT_LIMIT
    var server: String? = null
    var authorize = true
    var timeout = DEFAULT_TIMEOUT_SECONDS

    var index = 0
    while (index < rest.size) {
      val argument = rest[index]
      // A flag's value is the next word; saying so once here is what keeps the loop readable.
      fun value(): String? =
        rest.getOrNull(index + 1)?.takeUnless { it.startsWith("-") && it != STDOUT }
      when {
        argument == "--server" -> {
          server = value() ?: return missingValue(argument)
          index++
        }
        argument == "--out" || argument == "-o" -> {
          out = value() ?: return missingValue(argument)
          index++
        }
        argument == "--format" -> {
          format = value() ?: return missingValue(argument)
          index++
        }
        argument == "--revision" -> {
          val raw = value() ?: return missingValue(argument)
          revision =
            raw.toLongOrNull()?.takeIf { it >= 0 }
              ?: return Parsed.Invalid(
                "design: --revision must be a non-negative integer, not '$raw'"
              )
          index++
        }
        argument == "--limit" -> {
          val raw = value() ?: return missingValue(argument)
          limit =
            raw.toIntOrNull()?.takeIf { it in 1..1000 }
              ?: return Parsed.Invalid("design: --limit must be between 1 and 1000, not '$raw'")
          index++
        }
        argument == "--timeout" -> {
          val raw = value() ?: return missingValue(argument)
          timeout =
            raw.toLongOrNull()?.takeIf { it > 0 }
              ?: return Parsed.Invalid(
                "design: --timeout must be a positive number of seconds, not '$raw'"
              )
          index++
        }
        argument == "--no-authorize" -> authorize = false
        argument == "--token" ->
          // Named explicitly rather than falling through to "unknown flag", because the reason it
          // is absent is the interesting part and someone reaching for it deserves to read it.
          return Parsed.Invalid(
            "design: there is no --token flag on purpose — a credential on a command line lands " +
              "in a shell history and a CI log. Set \$$TOKEN_ENV, or let this command ask a human " +
              "for a grant."
          )
        argument.startsWith("-") && argument != STDOUT ->
          return Parsed.Invalid("design: unknown option '$argument'")
        designId == null -> designId = argument
        else -> return Parsed.Invalid("design $verb: unexpected argument '$argument'")
      }
      index++
    }

    if (verb == LIST && designId != null) {
      return Parsed.Invalid("design list: takes no design id")
    }
    if (verb != LIST && designId.isNullOrBlank()) {
      return Parsed.Invalid("design $verb: a design id is required")
    }

    val resolvedFormat =
      when (verb) {
        RENDER -> parseFormat(format, RENDER_FORMATS, ExportFormatV1.PNG)
        EXPORT -> parseFormat(format, EXPORT_FORMATS, ExportFormatV1.COMPOSE)
        else ->
          if (format != null) {
            return Parsed.Invalid("design $verb: --format applies to render and export only")
          } else null
      }
    if (resolvedFormat == null && verb in setOf(RENDER, EXPORT)) {
      val allowed = if (verb == RENDER) RENDER_FORMATS else EXPORT_FORMATS
      return Parsed.Invalid(
        "design $verb: --format must be one of ${allowed.joinToString(", ") { it.wire() }}, not '$format'"
      )
    }

    return Parsed.Run(
      Options(
        verb = verb,
        designId = designId.orEmpty(),
        out = out,
        format = resolvedFormat,
        revision = revision,
        limit = limit,
        server = server ?: env(SERVER_ENV)?.takeIf { it.isNotBlank() } ?: defaultServer(),
        authorize = authorize,
        timeoutSeconds = timeout,
      )
    )
  }

  /** The credential this invocation starts with, or null when it has none yet. */
  fun token(env: (String) -> String? = System::getenv): String? =
    env(TOKEN_ENV)?.takeIf { it.isNotBlank() } ?: env(LEGACY_TOKEN_ENV)?.takeIf { it.isNotBlank() }

  fun defaultServer(): String = "http://127.0.0.1:${ServeDefaults.DEFAULT_PORT}"

  /** The filename a verb writes when the caller named none. */
  fun defaultOut(verb: String, designId: String, format: ExportFormatV1?): String =
    when (verb) {
      // Both of these are text, and a pipe is the obvious thing to do with them.
      LIST,
      GET -> STDOUT
      EXPORT -> if (format == ExportFormatV1.COMPOSE) STDOUT else "$designId.${format.extension()}"
      // Bytes are not something to spray at a terminal unless asked for by name.
      else -> "$designId.${format.extension()}"
    }

  fun usage(): String =
    """
    compose-preview-server design <verb> [options]

    Get a UI-builder design's pixels, its generated source, or its document out of a running
    server, without a bespoke curl-and-jq pipeline per session.

    Verbs:
      list                      Designs this credential can see, one per line.
      get <designId>            The design document, as JSON.
      render <designId>         The design as a picture: PNG, or SVG with --format svg.
      export <designId>         The generated source (Kotlin), with its diagnostics.

    Options:
      --server <url>            The server to ask (default ${defaultServer()}, or ${'$'}$SERVER_ENV).
      --out, -o <path>          Where to write it; `-` is stdout. Text verbs default to stdout,
                                a render defaults to <designId>.<png|svg>.
      --format <format>         render: png (default) or svg. export: compose (default).
      --revision <n>            Pin a revision. Omitted means the current committed one, which is
                                what the export URLs serve.
      --limit <n>               list: how many designs to ask for (default $DEFAULT_LIMIT).
      --no-authorize            Fail on a missing or expired grant instead of asking a human for
                                one. For CI, where nobody is there to approve.
      --timeout <seconds>       Give up on one call after this long (default $DEFAULT_TIMEOUT_SECONDS).

    Credentials come from ${'$'}$TOKEN_ENV (or the older ${'$'}$LEGACY_TOKEN_ENV), never from a
    flag. With neither set — or when a server restart has dropped the grant — this command asks the
    server for one and prints a link and a code for a human to approve, unless --no-authorize.

    Diagnostics from a refused export are printed to stderr and the exit code is non-zero, so a
    refusal fails a pipeline instead of writing an empty file into it.
    """
      .trimIndent()

  private fun missingValue(flag: String): Parsed = Parsed.Invalid("design: $flag needs a value")

  private fun parseFormat(
    raw: String?,
    allowed: List<ExportFormatV1>,
    fallback: ExportFormatV1,
  ): ExportFormatV1? {
    if (raw == null) return fallback
    return allowed.firstOrNull { it.wire().equals(raw, ignoreCase = true) }
  }

  private val RENDER_FORMATS = listOf(ExportFormatV1.PNG, ExportFormatV1.SVG)
  private val EXPORT_FORMATS = listOf(ExportFormatV1.COMPOSE)
}

/** The format's wire name — what `--format` takes and what the protocol serialises. */
internal fun ExportFormatV1.wire(): String = name.lowercase()

/** The file extension an artifact of this format is saved under. */
internal fun ExportFormatV1?.extension(): String =
  when (this) {
    ExportFormatV1.SVG -> "svg"
    ExportFormatV1.PNG -> "png"
    ExportFormatV1.COMPOSE -> "kt"
    // An archive: source plus the picture bytes as files. `application/zip` per the contract.
    ExportFormatV1.BUNDLE -> "zip"
    null -> "json"
  }
