package ee.schimke.composeai.cli.serve

import java.io.File
import java.time.Duration

/**
 * `design`, wired to a real process: argv in, files and exit codes out.
 *
 * The one behaviour that lives here rather than in [DesignCommandRunner] is the **retry after
 * authorising**, because it is the only thing that needs both halves. A grant is dropped by every
 * server restart, and until now that presented as whatever the first unauthorised call happened to
 * look like — famously as a 404 from the catalog-less redirect, which is indistinguishable from a
 * design that does not exist because the server deliberately refuses to say which
 * ([#509](https://github.com/yschimke/compose-preview-server/issues/509)). So an expired grant is
 * handled here as an ordinary event: say so, offer the flow, and re-run the same call once with the
 * token it produced.
 */
internal object DesignCommandEntry {

  /**
   * How long a human gets to open the link and approve, independent of `--timeout`.
   *
   * `--timeout` bounds a call to a server; this bounds a person walking to another window. Tying
   * the two would make the useful default for one an insulting deadline for the other.
   */
  private const val APPROVAL_DEADLINE_SECONDS = 600L

  fun run(
    args: List<String>,
    env: (String) -> String? = System::getenv,
    out: java.io.PrintStream = System.out,
    err: java.io.PrintStream = System.err,
  ): Int {
    val options =
      when (val parsed = DesignCommand.parse(args, env)) {
        is DesignCommand.Parsed.Help -> {
          out.println(DesignCommand.usage())
          return DesignCommandRunner.EXIT_OK
        }
        is DesignCommand.Parsed.Invalid -> {
          err.println(parsed.message)
          err.println("Try `compose-preview-server design help`.")
          return DesignCommandRunner.EXIT_USAGE
        }
        is DesignCommand.Parsed.Run -> parsed.options
      }

    var token = DesignCommand.token(env)
    val transport =
      DesignHttpTransport(
        server = options.server,
        token = { token },
        timeout = Duration.ofSeconds(options.timeoutSeconds),
      )
    val runner =
      DesignCommandRunner(
        options = options,
        transport = transport,
        emit = err::println,
        write = { destination, bytes -> destination.receive(bytes, out) },
      )

    return try {
      try {
        runner.run()
      } catch (refused: DesignAuthorizationRequired) {
        if (!options.authorize) {
          err.println(refused.message)
          err.println(
            "design: --no-authorize, so no grant was requested. Set \$${DesignCommand.TOKEN_ENV} " +
              "to a token this server still honours."
          )
          return DesignCommandRunner.EXIT_NO_PERMISSION
        }
        // Naming which of the two it is matters: "expired" tells the caller their setup is right
        // and the server restarted, which is a different afternoon from "you never had access".
        err.println(
          if (token == null) "design: no credential, and this server requires one."
          else "design: this server no longer honours that token — a restart drops every grant."
        )
        token =
          DesignAuthorizer(
              server = options.server,
              timeout = Duration.ofSeconds(ServeAgentGrants.MAX_POLL_WAIT_SECONDS + 15),
              log = err::println,
            )
            .authorize(
              scope = options.scope.wire,
              capabilities = options.capabilities.map { it.wire },
              label = "compose-preview-server design ${options.verb}",
              deadlineSeconds = APPROVAL_DEADLINE_SECONDS,
            )
        // The token is deliberately not printed: stderr is where a CI log looks, and a grant read
        // out of one is a grant anybody who can read the log holds. It lasts for this invocation;
        // a shell that wants to keep it sets $COMPOSE_PREVIEW_TOKEN from the approval page.
        err.println("design: the grant is held for this run only, and is not printed.")
        runner.run()
      }
    } catch (refused: DesignAuthorizationRequired) {
      // A second refusal after a fresh grant is not a credential problem: the approver ticked less
      // than the verb needs, or the design belongs to somebody who has not shared it.
      err.println(refused.message)
      err.println(
        "design: the new grant still cannot do this. It needs " +
          "${options.capabilities.joinToString(", ") { it.wire }}, and designs are private to " +
          "their owner and collaborators."
      )
      DesignCommandRunner.EXIT_NO_PERMISSION
    } catch (failure: DesignCommandFailure) {
      err.println(failure.message ?: "design: failed")
      DesignCommandRunner.EXIT_FAILURE
    }
  }

  /** `-` is stdout; anything else is a file, with its parent directory made if it is missing. */
  private fun String.receive(bytes: ByteArray, out: java.io.PrintStream) {
    if (this == DesignCommand.STDOUT) {
      out.write(bytes)
      out.flush()
      return
    }
    val file = File(this)
    file.absoluteFile.parentFile?.mkdirs()
    file.writeBytes(bytes)
  }
}
