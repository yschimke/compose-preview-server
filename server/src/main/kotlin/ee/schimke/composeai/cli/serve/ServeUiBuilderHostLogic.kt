package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicSimulator
import ee.schimke.composeai.uibuilder.hostlogic.HostLogicStep
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A design's host logic on this server (compose-ui-builder's `UI_BUILDER_HOST_LOGIC.md`, "On the
 * server"): the program that stands in for the host feeding the design, checked for
 * `ui_builder_check_design` and run headless for `ui_builder_simulate_action`.
 *
 * Both are `HostLogicSimulator` from `:ui-builder-runtime`, which drives the program exactly as the
 * editor's canvas does, on QuickJS compiled to JVM bytecode. This file only shapes its answers.
 */
internal object ServeUiBuilderHostLogic {
  /**
   * What `check` says about [document]'s host logic, as findings: none for a design without host
   * logic, so the check costs nothing there.
   */
  fun findings(document: DesignDocumentV1): List<UiBuilderCheckFindingV1> {
    if (document.hostLogic == null) return emptyList()
    return HostLogicSimulator.check(document.toUiBuilderDocument()).diagnostics.map {
      UiBuilderCheckFindingV1(
        severity = SEVERITY_ERROR,
        check = CHECK_HOST_LOGIC,
        code = CODE_HOST_LOGIC,
        message = it.message,
        field = it.field,
      )
    }
  }

  /**
   * [document]'s host logic run from `init` through [actions], each `{"name", "value"?}` as a host
   * action carries it. Throws [IllegalArgumentException] for an action a host action could not
   * carry, or more of them than a simulation sends.
   */
  fun simulate(
    designId: String,
    document: DesignDocumentV1,
    actions: List<JsonObject>,
  ): UiBuilderHostLogicSimulationV1 {
    val design = document.toUiBuilderDocument()
    val run = HostLogicSimulator.simulate(design, actions)
    val steps = run.steps.map(::step)
    val failed = steps.count { it.error != null }
    val last = steps.lastOrNull()
    val summary =
      when {
        run.stopped != null && steps.isEmpty() -> "Nothing ran: ${run.stopped}."
        run.stopped != null ->
          "Stopped after ${steps.size - 1} of ${actions.size} action(s): ${run.stopped}."
        failed > 0 ->
          "Ran init and ${actions.size} action(s); $failed step(s) failed, each keeping the " +
            "values before it. Last values: ${last!!.values.describe()}."
        else -> "Ran init and ${actions.size} action(s). Last values: ${last!!.values.describe()}."
      }
    return UiBuilderHostLogicSimulationV1(
      ok = run.stopped == null && failed == 0,
      summary = summary,
      designId = designId,
      revision = document.revision,
      digest = design.hostLogic?.let(HostLogicSimulator::digest),
      steps = steps,
      stopped = run.stopped,
    )
  }

  private fun step(step: HostLogicStep) =
    UiBuilderHostLogicStepV1(
      call = if (step.action == null) "init" else "onAction",
      action = step.action,
      value = step.value,
      state = step.state,
      values = step.values,
      set = step.set,
      error = step.error,
    )

  private fun Map<String, String?>.describe(): String =
    if (isEmpty()) "the design has no host inputs"
    else entries.joinToString(", ") { (name, value) -> "`$name` = ${value?.let { "\"$it\"" }}" }

  const val CODE_HOST_LOGIC = "hostLogicUnrunnable"
}

/** `ui_builder_check_design`'s check of a design's host logic, by the name a caller asks for. */
internal const val CHECK_HOST_LOGIC = "hostLogic"

internal const val UI_BUILDER_HOST_LOGIC_SIMULATION_SCHEMA =
  "compose-preview/ui-builder-host-logic-simulation/v1"

/** `ui_builder_simulate_action`'s reply: a sentence, then every step in order. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderHostLogicSimulationV1(
  @EncodeDefault val schema: String = UI_BUILDER_HOST_LOGIC_SIMULATION_SCHEMA,
  /** True when every step's answer was used and the run was not stopped. */
  val ok: Boolean,
  val summary: String,
  val designId: String,
  /** The revision simulated. */
  val revision: Long,
  /** `sha256:…` of the program simulated; null when the design has none. */
  val digest: String? = null,
  /** `init`, then one step per action. */
  val steps: List<UiBuilderHostLogicStepV1>,
  /** Why the run stopped before the last action (it overran its budget), or why nothing ran. */
  val stopped: String? = null,
)

/** One call of a simulated run, and where it left the run. */
@Serializable
internal data class UiBuilderHostLogicStepV1(
  /** `init` or `onAction`. */
  val call: String,
  /** The host action answered; absent for `init`. */
  val action: String? = null,
  val value: JsonPrimitive? = null,
  /** The program's private state after the step: the last good one when the step failed. */
  val state: JsonElement,
  /** Every host input's value after the step, as the canvas would draw it. */
  val values: Map<String, String?>,
  /** The host inputs this step's answer set. */
  val set: Map<String, String?>,
  /** Why the step's answer could not be used; absent when it was. */
  val error: String? = null,
)
