package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BundleGuidelineResultsTest {
  private fun report(vararg previewIds: String, reason: String = "Fixed width.") =
    previewIds.joinToString(
      separator = ",\n",
      prefix =
        """{"module":"catalog","catalog":"wear-m3","model":"deepseek/deepseek-v4.1-flash","results":[""",
      postfix = "]}",
    ) { previewId ->
      """
      {"previewId":"$previewId","record":{
        "schema":"compose-ui-builder/guidelines-result/v1","revision":0,
        "model":"deepseek/deepseek-v4.1-flash","rulesVersion":3,
        "asked":["wear.layout.responsive-width"],
        "verdicts":[{"ruleId":"wear.layout.responsive-width","verdict":"fail","confidence":0.9,
          "nodeIds":[],"reason":"$reason"}]}}
      """
        .trimIndent()
    }

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

  @Test
  fun `a safe id two raw ids share answers nothing rather than the wrong preview`() {
    val twoResults = report("p.Card:dark", "p.Card;dark")
    val results = BundleGuidelineResults(ServeGuidelineResultsStore.parse(twoResults))
    assertNull(results.forPreview("p.Card_dark"))
    assertEquals(
      "wear.layout.responsive-width",
      results.forPreview("p.Card:dark")!!.verdicts.single().ruleId,
    )
  }

  @Test
  fun `a hosted catalog lifts the results out of its live bundle`() {
    val dir = Files.createTempDirectory("guidelines-lift").toFile()
    try {
      fun bundle(name: String, entry: String?): File =
        File(dir, name).also { file ->
          ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            if (entry != null) {
              zip.putNextEntry(ZipEntry(ServeGuidelineResultsStore.FILE))
              zip.write(entry.toByteArray())
              zip.closeEntry()
            }
          }
        }

      val staged = File(dir, "staged").apply { mkdirs() }
      liftGuidelineResults(bundle("bad.zip", "{not json"), staged, 1024 * 1024)
      assertFalse(File(staged, ServeGuidelineResultsStore.FILE).exists(), "malformed is skipped")

      liftGuidelineResults(bundle("none.zip", null), staged, 1024 * 1024)
      assertFalse(File(staged, ServeGuidelineResultsStore.FILE).exists(), "absent is skipped")

      liftGuidelineResults(bundle("good.zip", report("p.Card")), staged, 1024 * 1024)
      assertTrue(File(staged, ServeGuidelineResultsStore.FILE).isFile)
      assertEquals(
        "Fixed width.",
        BundleGuidelineResults(ServeGuidelineResultsStore.load(staged))
          .forPreview("p.Card")!!
          .verdicts
          .single()
          .reason,
      )
    } finally {
      dir.deleteRecursively()
    }
  }
}
