package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The consumer half of the cross-repository pin for the preview-selector rule
 * ([compose-ai-tools#5185](https://github.com/yschimke/compose-ai-tools/issues/5185)).
 *
 * `--id`, `--filter` and `--preview` are answered twice. This server answers them with
 * [previewIdMatchesStandaloneRequest], because `compose-preview serve` is a launcher now
 * ([#180](https://github.com/yschimke/compose-preview-server/issues/180) step 4) and the CLI no
 * longer builds [ServeCommandOptions] with its own matcher bound in. The other answer is
 * `previewIdMatchesRequest` in compose-ai-tools, which every non-`serve` command still uses. They
 * agreed by inspection and nothing checked it, and the failure mode is silent: a preview that stops
 * matching produces no error anywhere, it just is not there.
 *
 * [FIXTURES] is the shared table. compose-ai-tools owns it, this repository vendors the copy that
 * `scripts/sync-preview-selector-fixtures.sh` writes, and both suites run every case. Re-sync when
 * adopting a release — the same obligation the daemon protocol fixtures carry.
 */
class PreviewSelectorFixturesTest {

  @Test
  fun `every fixture case answers the same way here`() {
    for (case in cases()) {
      val preview = case.getValue("preview").jsonObject
      val selectors = case.getValue("selectors").jsonObject
      val expected = case.getValue("expected").jsonPrimitive.content.toBoolean()
      val name = case.getValue("name").jsonPrimitive.content
      assertEquals(
        expected,
        previewIdMatchesStandaloneRequest(
          id = preview.string("id")!!,
          exactId = selectors.string("exactId"),
          filter = selectors.string("filter"),
          previewRef = selectors.string("previewRef"),
          className = preview.string("className"),
          functionName = preview.string("functionName"),
        ),
        "$FIXTURES case: $name",
      )
    }
  }

  /**
   * Every combination of the three selectors is exercised, both ways.
   *
   * Without this a rule change could add a branch — a fourth selector, a precedence between two of
   * them — and land green against a table that never reaches it. The `false` half matters as much
   * as the `true` half: a rule that accepted everything would satisfy a `true`-only table. Stated
   * on both sides so a vendored copy that lost cases is caught here rather than upstream.
   */
  @Test
  fun `the table covers each selector combination in both outcomes`() {
    val seen = mutableMapOf<Set<String>, MutableSet<Boolean>>()
    for (case in cases()) {
      val selectors = case.getValue("selectors").jsonObject
      val combination = SELECTORS.filter { selectors.string(it) != null }.toSet()
      seen
        .getOrPut(combination) { mutableSetOf() }
        .add(case.getValue("expected").jsonPrimitive.content.toBoolean())
    }
    for (combination in selectorCombinations()) {
      val outcomes = seen[combination] ?: emptySet<Boolean>()
      val label = if (combination.isEmpty()) "(no selectors)" else combination.sorted().toString()
      // No selectors can only ever keep the preview, so only `true` is required of it.
      val required = if (combination.isEmpty()) setOf(true) else setOf(true, false)
      assertTrue(
        outcomes.containsAll(required),
        "$FIXTURES covers $label with $outcomes; expected $required",
      )
    }
  }

  private fun cases(): List<JsonObject> =
    Json.parseToJsonElement(fixtureFile().readText()).jsonObject.getValue("cases").jsonArray.map {
      it.jsonObject
    }

  /** `null` for an absent selector, which is not the same request as an empty one. */
  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content

  private fun selectorCombinations(): List<Set<String>> =
    (0 until (1 shl SELECTORS.size)).map { mask ->
      SELECTORS.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }.toSet()
    }

  private fun fixtureFile(): File = File(repoRoot(), FIXTURES).also { assertTrue(it.isFile, "$it") }

  private fun repoRoot(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      if (File(dir, "settings.gradle.kts").isFile) return dir
      dir = dir.parentFile
    }
    error("could not locate repo root from ${System.getProperty("user.dir")}")
  }

  private companion object {
    const val FIXTURES = "docs/serve/preview-selector-fixtures.json"
    val SELECTORS = listOf("exactId", "filter", "previewRef")
  }
}
