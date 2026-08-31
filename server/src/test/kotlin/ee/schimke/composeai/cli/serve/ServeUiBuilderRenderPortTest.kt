package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.PackagedUiBuilderRenderBundle
import ee.schimke.composeai.uibuilder.service.UiBuilderRenderRequest
import ee.schimke.composeai.uibuilder.service.projectRendererDocument
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class ServeUiBuilderRenderPortTest {
  @TempDir lateinit var temporaryDirectory: Path

  @Test
  fun `adapter maps the renderer-neutral request onto the exact preview override`() {
    val session =
      FakeRenderSession(
        File(temporaryDirectory.toFile(), "fake-render"),
        includeNamedOverridesInArtifacts = true,
      )
    val host =
      ServeRenderHost(
        session = session,
        previews =
          listOf(ServePreview(PackagedUiBuilderRenderBundle.PREVIEW_ID, "Production UI builder")),
      )
    val request =
      UiBuilderRenderRequest(
        designId = "design",
        revision = 7,
        documentHash = "digest",
        widthPx = 400,
        heightPx = 800,
        density = 1f,
        localeTag = "en-US",
        fontScale = 1f,
        encodedDocument = "exact saved document",
      )

    ServeUiBuilderRenderPort.forHost(host).use { port ->
      assertTrue(port.renderPng(request).decodeToString().contains("exact saved document"))
      assertTrue(port.renderSvg(request).decodeToString().contains("exact saved document"))
    }
    assertEquals(2, session.renderCount.get())
    assertEquals(400, session.lastRenderOverrides?.widthPx)
    assertEquals(800, session.lastRenderOverrides?.heightPx)
    assertEquals(1f, session.lastRenderOverrides?.density)
  }

  @Test
  fun `packaged bundle renders deterministic png and figma svg with real daemon`() {
    val appHome = System.getenv("UI_BUILDER_REAL_RENDER_APP_HOME")?.let(::File)
    assumeTrue(
      appHome?.resolve("lib-daemon-desktop")?.isDirectory == true &&
        appHome.resolve("lib-renderer").isDirectory,
      "Set UI_BUILDER_REAL_RENDER_APP_HOME to a compose-preview install for the real lane",
    )
    val previousAppHome = System.getProperty("composeai.cli.appHome")
    System.setProperty("composeai.cli.appHome", requireNotNull(appHome).absolutePath)
    try {
      ServeUiBuilderRenderPort.open(temporaryDirectory.resolve("real-render")).use { port ->
        val document = document()
        val request =
          UiBuilderRenderRequest(
            designId = document.id,
            revision = document.revision,
            documentHash = "real-render",
            widthPx = 1280,
            heightPx = 800,
            density = 1f,
            localeTag = "en-US",
            fontScale = 1f,
            encodedDocument = projectRendererDocument(document),
          )
        val png = port.renderPng(request)
        val repeatedPng = port.renderPng(request)
        val svg = port.renderSvg(request)
        val repeatedSvg = port.renderSvg(request)

        assertTrue(png.contentEquals(repeatedPng))
        assertTrue(svg.contentEquals(repeatedSvg))
        assertTrue(png.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        assertEquals(1280, png.pngDimension(16))
        assertEquals(800, png.pngDimension(20))
        assertTrue(svg.decodeToString().startsWith("<svg"))
      }
    } finally {
      if (previousAppHome == null) System.clearProperty("composeai.cli.appHome")
      else System.setProperty("composeai.cli.appHome", previousAppHome)
    }
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "real-render-design",
      title = "Real render design",
      revision = 0,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 1280,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.DARK,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("text"),
      nodes =
        mapOf(
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Rendered by real daemon")),
            )
        ),
    )
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

private fun ByteArray.pngDimension(offset: Int): Int =
  ((this[offset].toInt() and 0xff) shl 24) or
    ((this[offset + 1].toInt() and 0xff) shl 16) or
    ((this[offset + 2].toInt() and 0xff) shl 8) or
    (this[offset + 3].toInt() and 0xff)
