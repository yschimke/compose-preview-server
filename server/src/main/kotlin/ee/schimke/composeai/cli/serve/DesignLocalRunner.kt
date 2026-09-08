package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1

/**
 * `design render|export --local`: what a locally compiled design costs when it fails.
 *
 * The mirror of [DesignCommandRunner], and the same rule about refusals — nothing is written when
 * there is nothing honest to write — with one difference that is the reason the mode exists. The
 * wire reply cannot say *why* a render produced no frame, so this one always prints what the lane
 * was actually built out of ([DesignLocalLane.describe]) beside the reason. Those lines are cheap
 * to ignore and were, for [#481](https://github.com/yschimke/compose-preview-server/issues/481),
 * impossible to obtain.
 *
 * [document] is a supplier because a `--local` design comes from either of two places — a file, or
 * a server that merely read it out — and neither is this class's business. [emit] and [write] are
 * seams for the reason [DesignCommandRunner]'s are: every decision here is observable in a test
 * that opens no socket, touches no disk, and stands up no compiler.
 */
internal class DesignLocalRunner(
  private val options: DesignCommand.Options,
  private val document: () -> DesignDocumentV1,
  private val lane: DesignLocalLane,
  private val emit: (String) -> Unit,
  private val write: (destination: String, bytes: ByteArray) -> Unit,
) {

  fun run(): Int =
    try {
      when (options.verb) {
        DesignCommand.EXPORT -> export()
        DesignCommand.RENDER -> render()
        else -> {
          // Unreachable through `parse`, which refuses `--local` on the other verbs by name.
          emit("design ${options.verb}: --local has no answer for this verb")
          DesignCommandRunner.EXIT_USAGE
        }
      }
    } catch (e: DesignCommandFailure) {
      emit(e.message ?: "design: failed")
      DesignCommandRunner.EXIT_FAILURE
    }

  private fun export(): Int =
    when (val source = lane.generate(document())) {
      is DesignLocalLane.Source.Refused -> refused("export", source.code, source.reasons)
      is DesignLocalLane.Source.Emitted -> {
        val bytes = source.source.toByteArray()
        write(options.destination, bytes)
        note("design export --local: ${bytes.size} bytes of Kotlin (${source.screenName})")
        DesignCommandRunner.EXIT_OK
      }
    }

  private fun render(): Int {
    val outcome = lane.render(document())
    // After the attempt, never before: asked earlier these lines would report what the lane meant
    // to build rather than what it did. On the way to a picture they are noise a `2>/dev/null`
    // silences; on the way to a missing frame they are the answer.
    lane.describe().forEach { emit("  $it") }
    return when (outcome) {
      is DesignLocalLane.Frame.Refused -> refused("render", outcome.code, outcome.reasons)
      is DesignLocalLane.Frame.NoFrame -> {
        emit("design render --local: no frame — ${outcome.reason}")
        DesignCommandRunner.EXIT_FAILURE
      }
      is DesignLocalLane.Frame.Rendered -> {
        write(options.destination, outcome.png)
        note("design render --local: ${outcome.png.size} bytes of image/png")
        DesignCommandRunner.EXIT_OK
      }
    }
  }

  /**
   * The generator's refusal, whole.
   *
   * These are the same codes and sentences a server would answer with, printed rather than
   * summarised: each line names a node and the thing about it that cannot be expressed, which is
   * the only actionable half of the outcome.
   */
  private fun refused(verb: String, code: String, reasons: List<String>): Int {
    reasons.forEach { emit("  $code: $it") }
    // The generator writes for an operator configuring a server, and names the server's flag. It
    // is the same file either way, so the fix is translated rather than the refusal duplicated.
    if (code == ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD) {
      emit(
        "design $verb --local: that advice names `serve`'s flag; here it is " +
          "`--components <catalog>=<components.json>`."
      )
    }
    emit("design $verb --local: nothing was written.")
    return DesignCommandRunner.EXIT_FAILURE
  }

  /** A one-line summary, but only when it is not competing with the artifact for the terminal. */
  private fun note(line: String) {
    if (options.destination != DesignCommand.STDOUT) emit("$line -> ${options.destination}")
  }
}
