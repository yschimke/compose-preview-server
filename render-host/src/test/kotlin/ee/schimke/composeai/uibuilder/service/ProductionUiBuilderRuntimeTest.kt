package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.cli.serve.FakeRenderSession
import ee.schimke.composeai.cli.serve.ServePreview
import ee.schimke.composeai.cli.serve.ServeRenderHost
import ee.schimke.composeai.uibuilder.protocol.*
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

class ProductionUiBuilderRuntimeTest {
  @TempDir lateinit var stateDirectory: Path

  @Test
  fun `packaged current m3 catalog resolves only its exact pin and validates strictly`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    val catalog = catalogs.listCatalogs().single()

    assertEquals("m3-catalog", catalog.benchmark.catalogSystemId)
    assertTrue(catalog.exportCapabilities.composeCode)
    assertEquals(false, catalog.exportCapabilities.svg)
    assertEquals(false, catalog.exportCapabilities.png)
    assertEquals(catalog, catalogs.resolve(document().catalogPin))
    assertNull(
      catalogs.resolve(document().catalogPin.copy(catalogRevision = "moving-main")),
      "catalog resolution must never float to the current revision",
    )
    assertNull(catalogs.validate(document(), catalog))
    val invalid =
      document()
        .copy(
          nodes =
            document().nodes +
              ("text" to document().nodes.getValue("text").copy(componentId = "m3/not-real"))
        )
    assertEquals("UNKNOWN_COMPONENT", catalogs.validate(invalid, catalog)?.code)
  }

  @Test
  fun `compose export is revision pinned deterministic and changes with saved content`() {
    val catalog = CurrentM3UiBuilderCatalogExecutor().listCatalogs().single()
    val exporter = RevisionPinnedComposeExportExecutor()
    val first = exporter.export(pinned(document(), catalog))
    val repeated = exporter.export(pinned(document(), catalog))
    val editedDocument =
      document()
        .copy(
          nodes =
            document().nodes +
              ("text" to
                document()
                  .nodes
                  .getValue("text")
                  .copy(properties = mapOf("text" to StringValueV1("Changed on revision one")))),
          revision = 1,
        )
    val edited = exporter.export(pinned(editedDocument, catalog))

    assertEquals(first, repeated)
    assertEquals(sha256(first.content), first.contentDigest)
    assertTrue(first.content.contains("Text("))
    assertTrue(first.content.contains("Hello from the saved design"))
    assertTrue(
      first.content.contains("Document SHA-256: ${pinned(document(), catalog).documentHash}")
    )
    assertNotEquals(first.contentDigest, edited.contentDigest)
    assertTrue(edited.content.contains("Changed on revision one"))
    assertFailsWith<IllegalArgumentException> {
      exporter.export(pinned(document(), catalog).copy(format = ExportFormatV1.SVG))
    }
  }

  @Test
  fun `unsupported svg and png fail before the exporter and survive service restart`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor()
    var exports = 0
    fun service() =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(stateDirectory),
        catalogs = catalogs,
        exporter =
          UiBuilderExportExecutor {
            exports++
            RevisionPinnedComposeExportExecutor().export(it)
          },
      )

    val owner = AuthenticatedUiBuilderActor("owner")
    assertIs<UiBuilderServiceResponse.Snapshot>(
      executeProduction(service(), owner, UiBuilderServiceRequest.CreateDesign(document()))
    )
    val restarted = service()
    assertIs<UiBuilderServiceResponse.Snapshot>(
      executeProduction(restarted, owner, UiBuilderServiceRequest.OpenDesign("production-design"))
    )
    listOf(ExportFormatV1.SVG, ExportFormatV1.PNG).forEach { format ->
      val error =
        assertIs<UiBuilderServiceResponse.Error>(
            executeProduction(
              restarted,
              owner,
              UiBuilderServiceRequest.ExportDesign("production-design", revision = 0, format),
            )
          )
          .error
      assertEquals(ServiceErrorCodeV1.BAD_REQUEST, error.code)
    }
    assertEquals(0, exports, "unsupported formats must never reach the artifact executor")
    val compose =
      assertIs<UiBuilderServiceResponse.Export>(
        executeProduction(
          restarted,
          owner,
          UiBuilderServiceRequest.ExportDesign(
            "production-design",
            revision = 0,
            ExportFormatV1.COMPOSE,
          ),
        )
      )
    assertEquals(1, exports)
    assertTrue(compose.artifact.content.contains("Hello from the saved design"))
  }

  @Test
  fun `protocol projection preserves 99 node renderer fields and rejects revision overflow`() {
    val base = document()
    val nodes =
      (1..99).associate { index ->
        "text-$index" to
          DesignNodeV1(
            id = "text-$index",
            componentId = "m3/text",
            properties = mapOf("text" to StringValueV1("Node $index")),
          )
      }
    val large = base.copy(revision = 99, roots = nodes.keys.toList(), nodes = nodes)

    val projected =
      kotlinx.serialization.json.Json.parseToJsonElement(projectRendererDocument(large)).jsonObject

    assertEquals(99, projected.getValue("revision").jsonPrimitive.content.toInt())
    assertEquals(99, projected.getValue("nodes").jsonObject.size)
    assertEquals(
      nodes.keys.toList(),
      projected.getValue("roots").jsonArray.map { it.jsonPrimitive.content },
    )
    assertTrue("assets" !in projected)
    assertFailsWith<IllegalArgumentException> {
      projectRendererDocument(base.copy(revision = Int.MAX_VALUE.toLong() + 1))
    }
  }

  @Test
  fun `daemon adapter sends exact saved document to deterministic png and svg renders`() {
    val renderRoot = File(stateDirectory.toFile(), "fake-render")
    val session = FakeRenderSession(renderRoot, includeNamedOverridesInArtifacts = true)
    val host =
      ServeRenderHost(
        session = session,
        previews =
          listOf(
            ServePreview(
              ProductionUiBuilderRenderExecutor.PREVIEW_ID,
              "Production UI builder",
            )
          ),
      )
    ProductionUiBuilderRenderExecutor.forHost(host).use { exporter ->
      val catalog =
        CurrentM3UiBuilderCatalogExecutor(exportCapabilities = exporter.capabilities)
          .listCatalogs()
          .single()
      val initial = pinned(document(), catalog)
      val editedDocument =
        document()
          .copy(
            revision = 1,
            nodes =
              document().nodes +
                ("text" to
                  document()
                    .nodes
                    .getValue("text")
                    .copy(properties = mapOf("text" to StringValueV1("Exact edited document")))),
          )
      val edited = pinned(editedDocument, catalog)

      val png = exporter.export(initial.copy(format = ExportFormatV1.PNG))
      val pngAgain = exporter.export(initial.copy(format = ExportFormatV1.PNG))
      val editedPng = exporter.export(edited.copy(format = ExportFormatV1.PNG))
      val svg = exporter.export(initial.copy(format = ExportFormatV1.SVG))
      val svgAgain = exporter.export(initial.copy(format = ExportFormatV1.SVG))
      val editedSvg = exporter.export(edited.copy(format = ExportFormatV1.SVG))

      assertEquals(png, pngAgain)
      assertEquals(svg, svgAgain)
      assertNotEquals(png.contentDigest, editedPng.contentDigest)
      assertNotEquals(svg.contentDigest, editedSvg.contentDigest)
      assertTrue(
        Base64.getDecoder()
          .decode(editedPng.content)
          .decodeToString()
          .contains("Exact edited document")
      )
      assertTrue(editedSvg.content.contains("Exact edited document"))
      assertEquals(4, session.renderCount.get(), "one render for each distinct PNG/SVG override")
      assertEquals(400, session.lastRenderOverrides?.widthPx)
      assertEquals(800, session.lastRenderOverrides?.heightPx)
      assertEquals(1f, session.lastRenderOverrides?.density)
    }
  }

  @Test
  fun `packaged bundle renders deterministic png and figma svg with real daemon`() {
    val appHome = System.getenv("UI_BUILDER_REAL_RENDER_APP_HOME")?.let(::File)
    assumeTrue(
      appHome?.resolve("lib-daemon-desktop")?.isDirectory == true &&
        appHome.resolve("lib-renderer").isDirectory,
      "Run with UI_BUILDER_REAL_RENDER_APP_HOME pointing at a compose-preview install to " +
        "exercise the real daemon",
    )

    val realAppHome = requireNotNull(appHome)
    val previousAppHome = System.getProperty("composeai.cli.appHome")
    System.setProperty("composeai.cli.appHome", realAppHome.absolutePath)
    try {
      ProductionUiBuilderRenderExecutor.open(stateDirectory.resolve("real-render")).use { exporter
        ->
        assertTrue(exporter.capabilities.png)
        assertTrue(exporter.capabilities.svg)
        val catalog =
          CurrentM3UiBuilderCatalogExecutor(exportCapabilities = exporter.capabilities)
            .listCatalogs()
            .single()
        val renderDocument =
          document()
            .copy(
              environment =
                document().environment.copy(widthDp = 1280, heightDp = 800, density = 1.0)
            )
        val initial = pinned(renderDocument, catalog)
        val edited =
          pinned(
            renderDocument.copy(
              revision = 1,
              nodes =
                renderDocument.nodes +
                  ("text" to
                    renderDocument.nodes
                      .getValue("text")
                      .copy(
                        properties = mapOf("text" to StringValueV1("Rendered by real daemon"))
                      )),
            ),
            catalog,
          )

        val png = exporter.export(initial.copy(format = ExportFormatV1.PNG))
        val repeatedPng = exporter.export(initial.copy(format = ExportFormatV1.PNG))
        val editedPng = exporter.export(edited.copy(format = ExportFormatV1.PNG))
        val svg = exporter.export(initial.copy(format = ExportFormatV1.SVG))
        val repeatedSvg = exporter.export(initial.copy(format = ExportFormatV1.SVG))
        val editedSvg = exporter.export(edited.copy(format = ExportFormatV1.SVG))

        assertEquals(png.contentDigest, repeatedPng.contentDigest)
        assertEquals(svg.contentDigest, repeatedSvg.contentDigest)
        assertNotEquals(png.contentDigest, editedPng.contentDigest)
        assertNotEquals(svg.contentDigest, editedSvg.contentDigest)
        val pngBytes = Base64.getDecoder().decode(png.content)
        assertTrue(pngBytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        assertEquals(1280, pngBytes.pngDimension(16))
        assertEquals(800, pngBytes.pngDimension(20))
        assertTrue(svg.content.startsWith("<svg"))
        assertTrue(editedSvg.content.contains("Rendered by real daemon"))
      }
    } finally {
      if (previousAppHome == null) {
        System.clearProperty("composeai.cli.appHome")
      } else {
        System.setProperty("composeai.cli.appHome", previousAppHome)
      }
    }
  }

  private fun pinned(
    document: DesignDocumentV1,
    catalog: CatalogCapabilityV1,
  ): RevisionPinnedUiBuilderExport =
    RevisionPinnedUiBuilderExport(
      actor = AuthenticatedUiBuilderActor("owner"),
      designId = document.id,
      revision = document.revision,
      documentHash = sha256(PersistentUiBuilderServiceJsonForTest.encode(document)),
      document = document,
      catalog = catalog,
      format = ExportFormatV1.COMPOSE,
    )

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "production-design",
      title = "Production design",
      revision = 0,
      catalogPin =
        CatalogReferenceV1(
          systemId = "m3-catalog",
          catalogRevision = "candidate",
          capabilityDigest = CurrentM3UiBuilderCatalogExecutor.CURRENT_CAPABILITY_DIGEST,
          nativeRuntimeId = "candidate",
        ),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
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
      roots = listOf("surface"),
      nodes =
        linkedMapOf(
          "surface" to
            DesignNodeV1(
              id = "surface",
              componentId = "m3/surface",
              slots = mapOf("content" to listOf("text")),
            ),
          "text" to
            DesignNodeV1(
              id = "text",
              componentId = "m3/text",
              properties = mapOf("text" to StringValueV1("Hello from the saved design")),
            ),
        ),
    )
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

private fun ByteArray.pngDimension(offset: Int): Int =
  ((this[offset].toInt() and 0xff) shl 24) or
    ((this[offset + 1].toInt() and 0xff) shl 16) or
    ((this[offset + 2].toInt() and 0xff) shl 8) or
    (this[offset + 3].toInt() and 0xff)

private object PersistentUiBuilderServiceJsonForTest {
  private val json = kotlinx.serialization.json.Json { encodeDefaults = true }

  fun encode(document: DesignDocumentV1): String =
    json.encodeToString(DesignDocumentV1.serializer(), document)
}

private fun executeProduction(
  service: PersistentUiBuilderService,
  actor: AuthenticatedUiBuilderActor,
  request: UiBuilderServiceRequest,
): UiBuilderServiceResponse {
  var completion: Result<UiBuilderServiceResponse>? = null
  suspend { service.execute(UiBuilderServiceCall(actor, request)) }
    .startCoroutine(
      object : Continuation<UiBuilderServiceResponse> {
        override val context = EmptyCoroutineContext

        override fun resumeWith(result: Result<UiBuilderServiceResponse>) {
          completion = result
        }
      }
    )
  return completion?.getOrThrow() ?: error("synchronous service call suspended")
}

private fun sha256(value: String): String =
  MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
    "%02x".format(it)
  }
