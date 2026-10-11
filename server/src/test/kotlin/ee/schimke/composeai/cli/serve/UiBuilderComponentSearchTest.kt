package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.cli.serve.UiBuilderComponentSearch.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiBuilderComponentSearchTest {
  private val textField =
    Candidate(
      id = "m3/text-field",
      displayName = "Text field",
      symbol = "TextField",
      role = "Container",
      traits = listOf("TextInput", "FormField"),
    )
  private val outlined =
    Candidate(
      id = "m3/outlined-text-field",
      displayName = "Outlined text field",
      symbol = "OutlinedTextField",
      role = "Container",
      traits = listOf("TextInput", "FormField"),
    )
  private val button = Candidate(id = "m3/button", displayName = "Button", symbol = "Button")
  private val column = Candidate(id = "layout/column", role = "Container")
  private val all = listOf(button, column, outlined, textField)

  private fun ids(query: String) = UiBuilderComponentSearch.search(query, all) { it }.map { it.id }

  @Test
  fun `the eval's spellings of a text field all find both, the named one first`() {
    for (query in
      listOf("text field", "textfield", "TextField", "text-field", "text_field", "m3/text-field")) {
      assertEquals(listOf("m3/text-field", "m3/outlined-text-field"), ids(query), query)
    }
  }

  @Test
  fun `the Compose symbol and a prefix rank above a match in the middle of a name`() {
    assertEquals(listOf("m3/outlined-text-field"), ids("OutlinedTextField"))
    assertEquals(listOf("m3/outlined-text-field"), ids("outlined"))
    assertEquals(
      UiBuilderComponentSearch.EXACT,
      UiBuilderComponentSearch.rank("OUTLINED TEXT FIELD", outlined),
    )
    assertEquals(UiBuilderComponentSearch.PREFIX, UiBuilderComponentSearch.rank("text", textField))
    assertEquals(UiBuilderComponentSearch.NAME, UiBuilderComponentSearch.rank("text", outlined))
  }

  @Test
  fun `role, traits and scattered words still match, below the names`() {
    assertEquals(
      UiBuilderComponentSearch.DETAIL,
      UiBuilderComponentSearch.rank("form field", textField),
    )
    assertEquals(
      UiBuilderComponentSearch.WORDS,
      UiBuilderComponentSearch.rank("outlined field", outlined),
    )
    assertEquals(
      listOf("layout/column", "m3/outlined-text-field", "m3/text-field"),
      ids("container"),
    )
  }

  @Test
  fun `nothing matches a query of separators or an unrelated word`() {
    assertNull(UiBuilderComponentSearch.rank(" - / ", textField))
    assertEquals(emptyList(), ids("slider"))
  }

  @Test
  fun `words split at separators and camelCase boundaries`() {
    assertEquals(
      listOf("outlined", "text", "field"),
      UiBuilderComponentSearch.words("OutlinedTextField"),
    )
    assertEquals(listOf("m3", "text", "field"), UiBuilderComponentSearch.words("m3/text-field"))
    assertEquals(listOf("html", "view"), UiBuilderComponentSearch.words("HTMLView"))
  }
}
