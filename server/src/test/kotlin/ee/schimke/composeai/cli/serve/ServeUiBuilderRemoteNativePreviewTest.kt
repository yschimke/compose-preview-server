package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.Json

class ServeUiBuilderRemoteNativePreviewTest {
  private val document =
    Json.decodeFromString<DesignDocumentV1>(
        Files.readString(
          Path.of("../docs/design/evidence/ui-builder-unsaved-remote-preview/document.json")
        )
      )
      .let { it.copy(catalogPin = it.catalogPin.copy(systemId = "custom-remote-catalog")) }
  private val executor =
    ScreenGeneratorComposeExportExecutor(
      { ComponentRecordSource.Lookup.Unconfigured },
      catalogPlatform = {
        if (it == "custom-remote-catalog") UiBuilderCatalogPlatform.REMOTE_COMPOSE
        else UiBuilderCatalogPlatform.MOBILE
      },
    )

  @Test
  fun `ordinary Remote roots use the capture wrapper and claim no Compose node bounds`() {
    var submitted: UiBuilderGeneratedCompose? = null
    val lane =
      ServeUiBuilderNativePreview(
        executor,
        compile = {
          submitted = it
          PlaygroundRunResponse(previewId = "generated", previewToken = "token", image = "png")
        },
        nativeTarget = {
          UiBuilderNativeTarget("served-remote", UiBuilderGeneratedCompose.COMPOSE_ANDROID)
        },
        captureNodeBounds = { error("Remote operations do not carry Compose test tags") },
      )
    val result = assertIs<UiBuilderNativePreviewOutcome.Rendered>(lane.render(document))
    val request = assertNotNull(submitted)
    assertEquals("served-remote", request.catalog)
    assertEquals(360, request.widthDp)
    assertEquals(360, request.heightDp)
    assertTrue(request.remoteCapture)
    assertFalse(request.wearWidget)
    assertTrue("RemoteStateLayout" in request.source)
    assertFalse("WearWidgetPreview" in request.source)
    val entry =
      UiBuilderGeneratedPreviewAdapter.previewEntry(
        request.composableName,
        request.widthDp,
        request.heightDp,
        remoteCapture = request.remoteCapture,
      )
    assertTrue("profile = RcPlatformProfiles.ANDROIDX" in entry)
    assertEquals(emptyList(), result.taggedNodeIds)
    assertEquals(emptyMap(), result.nodeBounds)
  }

  @Test
  fun `Remote capture refuses a desktop target before compilation`() {
    val lane =
      ServeUiBuilderNativePreview(
        executor,
        compile = { error("must not compile Android source on desktop") },
      )
    val refusal = assertIs<UiBuilderNativePreviewOutcome.Refused>(lane.render(document))
    assertEquals(ServeUiBuilderNativePreview.NO_NATIVE_CATALOG, refusal.code)
    assertTrue(refusal.reasons.single().contains("Android capture/player bundle"))
  }

  @Test
  fun `catalog metadata selects Remote generation without repurposing regular Compose layouts`() {
    val generated =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(executor.generate(document))
    assertTrue(generated.remoteContent)
    assertNull(generated.widgetFrame)
    val mobile = document.copy(catalogPin = document.catalogPin.copy(systemId = "mobile"))
    val refusal =
      assertIs<ScreenGeneratorComposeExportExecutor.Generated.Refused>(executor.generate(mobile))
    assertEquals(ScreenGeneratorComposeExportExecutor.NO_COMPONENT_RECORD, refusal.code)
  }
}
