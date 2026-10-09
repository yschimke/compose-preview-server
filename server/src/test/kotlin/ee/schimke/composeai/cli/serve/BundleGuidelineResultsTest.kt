package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BundleGuidelineResultsTest {
  private fun report(previewId: String, reason: String = "Fixed width.") =
    """
    {"module":"catalog","catalog":"wear-m3","model":"deepseek/deepseek-v4.1-flash","results":[
      {"previewId":"$previewId","record":{
        "schema":"compose-ui-builder/guidelines-result/v1","revision":0,
        "model":"deepseek/deepseek-v4.1-flash","rulesVersion":3,
        "asked":["wear.layout.responsive-width"],
        "verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"fail","confidence":0.9,
          "nodeIds":[],"reason":"$reason"}]}}
    ]}
    """
      .trimIndent()

  @Test
  fun `a result is found by the bundle id it was written under`() {
    val results =
      BundleGuidelineResults(
        ServeGuidelineResultsStore.parse(report("ee.x.ListsKt.WearList_192dp"))
      )
    assertEquals(
      "Fixed width.",
      results.forPreview("ee.x.ListsKt.WearList_192dp")!!.verdicts.single().reason,
    )
    assertNull(results.forPreview("ee.x.ListsKt.Other_192dp"))
  }

  @Test
  fun `a report written under raw preview ids still answers for the bundle's safe ids`() {
    val results =
      BundleGuidelineResults(
        ServeGuidelineResultsStore.parse(report("com.example.CardKt.Card:dark mode"))
      )
    // The bundle stored this preview as `com.example.CardKt.Card_dark_mode`.
    assertEquals(
      "wear.layout.responsive-width",
      results.forPreview("com.example.CardKt.Card_dark_mode")!!.verdicts.single().ruleId,
    )
  }

  @Test
  fun `a malformed or absent file is no results, not an error`() {
    assertNull(
      BundleGuidelineResults(ServeGuidelineResultsStore.parse("{not json")).forPreview("a")
    )
    assertNull(BundleGuidelineResults(null).forPreview("a"))
  }
}
