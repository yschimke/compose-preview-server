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
 * serving command.
 *
 * ## …except with `--local`, which is a compiler
 *
 * The cost above used to be paid rather than fixed: with no server there was nothing to render
 * against, and a design could not be rendered straight from a file — so when the render lane itself
 * misbehaved, the only surface that could reproduce it was the misbehaving server, whose reply is
 * deliberately lossy. `--local` compiles and renders here instead, off a `--document` on disk or a
 * design a server merely *read* out for it, and says why a frame is missing rather than only that
 * it is ([#551](https://github.com/yschimke/compose-preview-server/issues/551), [DesignLocalLane]).
 * It is still a client: nothing is served, and the process exits.
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
   * The verbs `--local` has an answer for.
   *
   * `list` and `get` read a server's design state, and this process holds no copy of it — a local
   * `list` could only ever be empty, and a local `get` would be `cat`. Refused by name rather than
   * quietly ignoring the flag.
   */
  val LOCAL_VERBS: List<String> = listOf(RENDER, EXPORT)

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
    /**
     * Compile and render in this process instead of asking a server.
     *
     * The half of this command
     * [#529](https://github.com/yschimke/compose-preview-server/issues/529) left outstanding: with
     * `--local` the design's pixels are produced by the same generator, compiler and daemon a
     * server would drive, in a process a debugger can attach to and with the reason for a missing
     * frame on stderr rather than in somebody else's log
     * ([#551](https://github.com/yschimke/compose-preview-server/issues/551)).
     */
    val local: Boolean = false,
    /**
     * A design document read straight off disk, instead of from a server.
     *
     * This is what makes a broken host reproducible: `design get` captures the document from it — a
     * read, not the render lane under suspicion — and the file then replays against a known-good
     * tree, or against yesterday's bundle, or under a debugger.
     */
    val document: String? = null,
    /** The catalog bundle a `--local` render compiles against. */
    val catalog: String? = null,
    /** Uploaded asset bytes by `storageKey`, for the widget lane that inlines them. */
    val assets: String? = null,
    /** `<catalog>=<components.json>`, as `serve --ui-builder-components` takes. */
    val components: Map<String, String> = emptyMap(),
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
        when {
          // A `--local` run asks a server for nothing but the document, and reading one is a
          // read. Asking for `ui-builder-export` here would have an approver grant the capability
          // to make the server produce artifacts for a run that never asks it to.
          local -> listOf(AgentGrantCapability.UI_BUILDER_READ)
          verb == RENDER || verb == EXPORT -> listOf(AgentGrantCapability.UI_BUILDER_EXPORT)
          else -> listOf(AgentGrantCapability.UI_BUILDER_READ)
        }

    /**
     * A made-to-order render is not a published preview, so `render` asks for `live`; everything
     * else reads what is already committed and asks for `preview`.
     */
    val scope: AgentGrantScope
      get() = if (verb == RENDER && !local) AgentGrantScope.LIVE else AgentGrantScope.PREVIEW

    /** Where the artifact goes when the caller named no `--out`. */
    val destination: String
      get() = out ?: defaultOut(verb, designId.ifBlank { documentName() }, format)

    /**
     * The name a `--document` file stands in for, so `--out` still has a sensible default.
     *
     * `design render --document broken.json --local` writes `broken.png` beside it, which is what
     * anyone comparing a replay against the original wants — and never the document itself.
     */
    private fun documentName(): String =
      document?.let { java.io.File(it).name.substringBeforeLast('.') }?.takeIf { it.isNotBlank() }
        ?: "design"
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
    var local = false
    var document: String? = null
    var catalog: String? = null
    var assets: String? = null
    val components = linkedMapOf<String, String>()

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
        argument == "--local" -> local = true
        argument == "--document" -> {
          document = value() ?: return missingValue(argument)
          index++
        }
        argument == "--catalog" -> {
          catalog = value() ?: return missingValue(argument)
          index++
        }
        argument == "--assets" -> {
          assets = value() ?: return missingValue(argument)
          index++
        }
        argument == "--components" -> {
          val raw = value() ?: return missingValue(argument)
          val catalogId = raw.substringBefore('=', "")
          val file = raw.substringAfter('=', "")
          if (catalogId.isBlank() || file.isBlank()) {
            return Parsed.Invalid(
              "design: --components takes <catalog>=<components.json>, not '$raw'"
            )
          }
          components[catalogId] = file
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
    // A document read off disk IS the design, so it stands in for the id every other spelling
    // needs — including for `--out`, whose default is derived from one below.
    if (verb != LIST && designId.isNullOrBlank() && document == null) {
      return Parsed.Invalid("design $verb: a design id is required")
    }
    if (designId != null && document != null) {
      return Parsed.Invalid(
        "design $verb: --document names the design, so a design id would name a second one"
      )
    }
    if (local && verb !in LOCAL_VERBS) {
      return Parsed.Invalid(
        "design $verb: --local applies to ${LOCAL_VERBS.joinToString(" and ")} only — the other " +
          "verbs read a server's state, which is not something this process holds a copy of"
      )
    }
    if (document != null && !local) {
      return Parsed.Invalid(
        "design $verb: --document is a local input — a server renders its own copy of a design, " +
          "not one from this disk. Add --local."
      )
    }
    if (local && verb == RENDER && catalog == null) {
      return Parsed.Invalid(
        "design render --local: --catalog <bundle> names the classpath to compile against; " +
          "there is no server here to have one configured"
      )
    }
    if (!local && (catalog != null || assets != null || components.isNotEmpty())) {
      return Parsed.Invalid(
        "design $verb: --catalog, --assets and --components configure the local compile lane. " +
          "Add --local, or drop them and let the server use its own."
      )
    }
    if (document != null && revision != null) {
      return Parsed.Invalid(
        "design $verb: --revision pins which revision a server hands over; a file is already one"
      )
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
    if (local && resolvedFormat == ExportFormatV1.SVG) {
      return Parsed.Invalid(
        "design render --local: only png. SVG is drawn by the server's own exporter rather than " +
          "by the compile-and-render lane this mode reproduces."
      )
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
        local = local,
        document = document,
        catalog = catalog,
        assets = assets,
        components = components,
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
      --local                   Compile and render in this process instead of asking a server.
                                render and export only. Needs --catalog to compile against, and
                                the daemon sidecars of an installed distribution to draw with:
                                without them the compile still runs and the render says so
                                instead of coming back silently empty. Prints the classpath it
                                resolved, the daemon it opened and the reason for a missing frame,
                                which is the whole point of the mode.
      --document <file>         Read the design from this file instead of from a server (--local
                                only). `design get <id> -o doc.json` against the suspect host
                                captures one; this replays it anywhere.
      --catalog <bundle>        --local: the catalog bundle to compile against. A path to a
                                `.bundle`; its manifest picks the daemon (android or desktop).
      --assets <dir>            --local: uploaded asset bytes by storage key, as `serve` keeps
                                them under its `assets/` directory. A widget that inlines a
                                picture needs them; nothing else does.
      --components <c>=<file>   --local: a catalog's components.json, the record the generator
                                proves each call site against. Repeatable. A record-free catalog
                                (wear-m3, remote-m3) needs none.
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

    Examples:
      design render w --local --catalog m3.bundle          Render design `w`, whose document this
                                                           command reads from --server, in this
                                                           process.
      design render --document doc.json --local \
        --catalog m3.bundle --assets ./assets              No server at all: a captured document,
                                                           replayed here.
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
    // Added by contracts 2.17.0. A remote document is `.rc`, and its JSON form is `.json` —
    // the same extension a null format already means here, because both are the design's own
    // JSON.
    ExportFormatV1.JSON -> "json"
    ExportFormatV1.RC -> "rc"
    null -> "json"
  }
