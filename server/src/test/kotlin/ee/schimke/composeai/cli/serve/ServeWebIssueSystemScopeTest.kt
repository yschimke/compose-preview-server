package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The index is published per delivery branch, but it is written per **repository** — one issue set
 * feeds every catalog that repository declares. `yschimke/wear-m3-catalog` declares two, and the
 * rows of one were reaching the pages of the other because the display join matches on component id
 * and preview id, both of which two catalogs built from one repository share.
 */
class ServeWebIssueSystemScopeTest {

  private fun issue(number: Int, system: String?, component: String = "Button/Filled") =
    ParityIssue(
      repository = "yschimke/wear-m3-catalog",
      number = number,
      title = "Issue $number",
      url = "https://github.com/yschimke/wear-m3-catalog/issues/$number",
      state = "open",
      system = system,
      component = component,
      previewIds = listOf("button-filled__ideal__default"),
    )

  @Test
  fun `a row filed against a sibling system is not this catalog's`() {
    val mine = issue(10, "wear-m3-catalog")
    val theirs = issue(20, "remote-m3")
    assertEquals(
      listOf(mine),
      ServeWeb.issuesForSystem(listOf(mine, theirs), "wear-m3-catalog"),
    )
  }

  @Test
  fun `a row naming no system is kept`() {
    // Positive evidence only. The field is optional on the wire, and an index published before the
    // producer emitted one carries badges that are still this catalog's best guess — dropping them
    // would blank the column on exactly the catalogs the feature already served.
    val unscoped = issue(30, null)
    assertEquals(listOf(unscoped), ServeWeb.issuesForSystem(listOf(unscoped), "wear-m3-catalog"))
  }

  @Test
  fun `a session with no system of its own filters nothing`() {
    val rows = listOf(issue(10, "wear-m3-catalog"), issue(20, "remote-m3"))
    assertEquals(rows, ServeWeb.issuesForSystem(rows, null))
    assertEquals(rows, ServeWeb.issuesForSystem(rows, ""))
  }

  @Test
  fun `the parity dashboard's lifecycle join keeps the rows its bands do not draw`() {
    // Mirrors [ServeWebKnownDifferenceContextTest] for the catalog-wide walk: an acceptance
    // committed here may cite an issue filed against the sibling system, and the join reads state
    // by URL. Scoping it would answer `unknown` where the index says `closed`.
    val mine = issue(41, "wear-m3-catalog")
    val theirs = issue(99, "remote-m3")
    val html =
      ServeWeb.parityPage(
        moduleLabel = "wear-m3-catalog",
        dashboard =
          ServeParityDashboard.Dashboard(
            coverage =
              ServeParityDashboard.Coverage(
                components = 1,
                mapped = 1,
                unmapped = emptyList(),
                unmappedOverflow = 0,
              ),
            feed = emptyList(),
            components = emptyList(),
            gaps = emptyList(),
          ),
        token = "t",
        parityIssues = listOf(mine),
        acceptanceIssues = listOf(mine, theirs),
        acceptanceAudit =
          listOf(
            KnownDifferenceCatalogPreview(
              system = "wear-m3-catalog",
              id = "button-filled__ideal__default",
              component = "Button/Filled",
              variant = "default",
              referenceIds = listOf("button-figma"),
            )
          ),
      )
    assertTrue("\"number\":99" in html, "full issue evidence must reach the lifecycle payload")
    assertTrue("#41 Issue 41" in html, "this catalog's own issue must remain visible in the band")
    assertFalse("#99 Issue 99" in html, "a sibling system's issue must not reach the bands")
  }
}
