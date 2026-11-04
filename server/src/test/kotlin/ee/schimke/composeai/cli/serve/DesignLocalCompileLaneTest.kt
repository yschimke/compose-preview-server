package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * What `design export --local` writes for the bundle it was handed. A dark design with export
 * devices used to carry `uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES` onto a
 * desktop bundle, where it fails with `Unresolved reference 'android'`.
 */
class DesignLocalCompileLaneTest {

  private val executor =
    ScreenGeneratorComposeExportExecutor({
      ComponentRecordSource.Lookup.Found(ExportRecords.m3Catalog())
    })

  private fun source(backend: String?): String =
    assertIs<DesignLocalLane.Source.Emitted>(
        DesignLocalCompileLane.generateFor(executor, darkDesignWithDevices(), backend)
      )
      .source

  @Test
  fun `a desktop bundle gets no export device previews, so a dark design compiles there`() {
    for (backend in listOf("desktop", null)) {
      val source = source(backend)
      assertTrue("android.content.res" !in source, source)
      assertTrue("@Preview" !in source, source)
      assertTrue("fun Dark()" in source, source)
    }
  }

  @Test
  fun `an android bundle keeps the export device previews, which compile there`() {
    val source = source(DesignLocalCompileLane.ANDROID_BACKEND)
    assertTrue("uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES" in source, source)
    assertTrue("device = \"id:pixel_9\"" in source, source)
  }

  private fun darkDesignWithDevices(): DesignDocumentV1 =
    Json.decodeFromString<DesignDocumentV1>(
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "dark", "title": "Dark", "revision": 0,
        "catalogPin": {"systemId": "m3-catalog", "catalogRevision": "candidate",
          "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {"widthDp": 411, "heightDp": 914, "density": 2.625, "theme": "dark",
          "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
          "exportDevices": ["id:pixel_9"]},
        "roots": ["label"],
        "nodes": {
          "label": {"id": "label", "componentId": "m3/text",
            "properties": {"text": {"type": "string", "value": "Hello"}},
            "slots": {}, "modifiers": []}
        }
      }
      """
    )
}
