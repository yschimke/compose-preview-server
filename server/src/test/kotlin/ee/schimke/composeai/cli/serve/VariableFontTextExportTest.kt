package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.VariableFontExportMode
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * A variable font text's call names a declaration flexpress writes at export, so the file this host
 * hands out has to carry it: without it, `VariableFontText…(…)` calls a function nothing declares.
 *
 * Both lanes that write one: m3, which this executor generates through `ScreenGenerator` itself and
 * so joins the declaration in after, and a Wear screen, which the record-free emitter writes. The
 * export draws through `flexpress-compose`; the lanes that compile the source against a catalog
 * bundle ask for the standalone form, because no bundle carries flexpress.
 */
class VariableFontTextExportTest {

  private val executor =
    ScreenGeneratorComposeExportExecutor(
      { ComponentRecordSource.Lookup.Found(ExportRecords.m3Catalog()) },
      ScreenGeneratorScreenFixture.PACKAGE_NAME,
      catalogPlatform = { systemId ->
        if (systemId == "wear-m3") UiBuilderCatalogPlatform.WEAR
        else UiBuilderCatalogPlatform.DEFAULT
      },
    )

  @Test
  fun `an m3 export carries the declaration its call names, through flexpress`() {
    val source = emitted(executor.generate(m3Design()))

    assertTrue("VariableFontTextRobotoFlexFlex(" in source, source)
    assertTrue("fun VariableFontTextRobotoFlexFlex(" in source, source)
    assertTrue("ee.schimke.flexpress:flexpress-compose:" in source, source)
    assertTrue(
      source.lines().count { it.startsWith("package ") } == 1,
      "the declaration joins the screen's file rather than bringing its own package\n$source",
    )
  }

  @Test
  fun `a compiled m3 render asks for the standalone declaration`() {
    val source =
      emitted(executor.generate(m3Design(), variableFontMode = VariableFontExportMode.STANDALONE))

    assertTrue("fun VariableFontTextRobotoFlexFlex(" in source, source)
    assertTrue("flexpress" !in source.lines().filter { it.startsWith("import ") }.joinToString())
  }

  @Test
  fun `a Wear screen export carries the declaration too`() {
    val source = emitted(executor.generate(wearDesign()))

    assertTrue("VariableFontTextRobotoFlexFlex(" in source, source)
    assertTrue("fun VariableFontTextRobotoFlexFlex(" in source, source)
  }

  @Test
  fun `text the font cannot draw refuses by name`() {
    val refused =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Refused>(
        executor.generate(m3Design(text = "日本"))
      )
    assertTrue(refused.reasons.any { "no glyph" in it }, refused.reasons.toString())
  }

  private fun emitted(generated: ScreenGeneratorComposeExportExecutor.Generated): String =
    assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(
        generated,
        generated.toString(),
      )
      .source

  private fun m3Design(text: String = "Flex"): DesignDocumentV1 =
    design(
      "m3-catalog",
      """
      "title": {"id": "title", "componentId": "m3/variable-font-text",
        "properties": ${properties(text)}, "slots": {}, "modifiers": []}
      """,
      roots = "title",
    )

  private fun wearDesign(): DesignDocumentV1 =
    design(
      "wear-m3",
      """
      "screen": {"id": "screen", "componentId": "wear-m3/screen-scaffold", "properties": {},
        "slots": {"content": ["list"]}, "modifiers": []},
      "list": {"id": "list", "componentId": "wear-m3/transforming-lazy-column",
        "properties": {}, "slots": {"items": ["title"]}, "modifiers": []},
      "title": {"id": "title", "componentId": "wear-m3/variable-font-text",
        "properties": ${properties("Flex")}, "slots": {}, "modifiers": []}
      """,
      roots = "screen",
    )

  private fun properties(text: String) =
    """{"text": {"type": "string", "value": "$text"},
      "font": {"type": "enum", "value": "robotoFlex"},
      "wght": {"type": "float", "value": 700}}"""

  private fun design(systemId: String, nodes: String, roots: String): DesignDocumentV1 =
    json.decodeFromString(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "flex", "title": "Flex", "revision": 0,
        "catalogPin": {"systemId": "$systemId", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 360, "heightDp": 240, "density": 2.0, "theme": "light",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr"},
        "stateVariables": {},
        "roots": ["$roots"],
        "nodes": { $nodes }
      }
      """
    )

  private companion object {
    val json = Json { ignoreUnknownKeys = true }
  }
}
