package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.CatalogSeedTemplates
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffValidator
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffXmlParser
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.coroutines.runBlocking

/**
 * A `wff-watchface` design's `bundle` export is its Watch Face Format source layout
 * (yschimke/watchface-tools#54), and `check_design`'s export gate refuses exactly what that export
 * refuses (yschimke/watchface-tools#30).
 *
 * The catalog and the two templates under `watchface-templates/` are copied verbatim from
 * yschimke/watchface-tools' `ui-builder-catalog/` at 87de7a6.
 */
class WatchFaceBundleExportExecutorTest {
  private val actor = AuthenticatedUiBuilderActor("operator")

  private fun resource(name: String): String =
    checkNotNull(javaClass.getResourceAsStream("/watchface-templates/$name")) { name }
      .bufferedReader()
      .use { it.readText() }

  /** The catalog as [ServeRunner] serves it: composed from its `ui-builder.json`, then bundled. */
  private val catalogs by lazy {
    val composed =
      PublishedUiBuilderCatalog.compose(
        resource("ui-builder.json"),
        null,
        ExportCapabilitiesV1.Builder()
          .also {
            it.composeCode = true
            it.svg = false
            it.png = true
          }
          .build(),
      )
    val catalog =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(composed, composed.toString()).catalog
    benchmarkCatalogExecutor(
      catalogSystemIds = setOf(SYSTEM_ID),
      published = mapOf(SYSTEM_ID to withWatchFaceBundle(catalog)),
    )
  }

  private val catalog by lazy { catalogs.listCatalogs().single() }

  private val templates by lazy {
    val paths = TEMPLATES.map { "ui-builder/designs/$it.json" }
    when (
      val read =
        CatalogSeedTemplates.read(SYSTEM_ID, paths) { resource(it.substringAfterLast('/')) }
    ) {
      is CatalogSeedTemplates.Result.Read -> read.templates
      is CatalogSeedTemplates.Result.Unusable -> fail(read.reason)
    }
  }

  private fun template(id: String): DesignDocumentV1 =
    UiBuilderNewDesignSeed.document(
        designId = id,
        templateId = id,
        catalogPin =
          CatalogReferenceV1(
            SYSTEM_ID,
            catalog.benchmark.catalogRevision,
            "candidate",
            catalog.benchmark.nativeRuntimeId,
          ),
        published = templates,
      )
      .toDesignDocumentV1()

  /** [document] with a `wff/part-image` drawing [resource] added to its scene. */
  private fun withImage(document: DesignDocumentV1, resource: String?): DesignDocumentV1 {
    val root = document.roots.single()
    val scene = document.nodes.getValue(root)
    val image =
      DesignNodeV1(
        id = "logo",
        componentId = WatchFaceXmlExporter.PART_IMAGE,
        properties =
          buildMap {
            put("x", IntegerValueV1(175))
            put("y", IntegerValueV1(100))
            put("width", IntegerValueV1(100))
            put("height", IntegerValueV1(100))
            resource?.let { put("resource", StringValueV1(it)) }
          },
      )
    val content = scene.slots["content"].orEmpty() + image.id
    return document.copy(
      nodes =
        document.nodes +
          (root to scene.copy(slots = scene.slots + ("content" to content))) +
          (image.id to image),
    )
  }

  private val png: ByteArray by lazy {
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB), "png", it) }
      .toByteArray()
  }

  /** Stands in for the renderer: every PNG export is [png]; nothing else may reach it. */
  private val renderer = UiBuilderExportExecutor { request ->
    assertEquals(ExportFormatV1.PNG, request.format, "only the preview may reach the delegate")
    ExportArtifactV1(
      ExportFormatV1.PNG,
      "image/png",
      ExportEncodingV1.BASE64,
      Base64.getEncoder().encodeToString(png),
      "",
    )
  }

  private class MemoryAssets(val bytes: Map<String, ByteArray>) : UiBuilderAssetStore {
    override fun read(storageKey: String): ByteArray? = bytes[storageKey]

    override fun write(storageKey: String, bytes: ByteArray) = error("read only")
  }

  private fun request(document: DesignDocumentV1) =
    RevisionPinnedUiBuilderExport(
      actor,
      document.id,
      document.revision,
      "pinned-hash",
      document,
      catalog,
      ExportFormatV1.BUNDLE,
    )

  private fun unzip(artifact: ExportArtifactV1): Map<String, ByteArray> {
    assertEquals(ExportFormatV1.BUNDLE, artifact.format)
    assertEquals("application/zip", artifact.mediaType)
    assertEquals(ExportEncodingV1.BASE64, artifact.encoding)
    val out = LinkedHashMap<String, ByteArray>()
    ZipInputStream(Base64.getDecoder().decode(artifact.content).inputStream()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        out[entry.name] = zip.readBytes()
      }
    }
    return out
  }

  private fun errors(artifact: ExportArtifactV1) =
    artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }

  @Test
  fun `a watchface catalog advertises bundle export and others do not`() {
    assertTrue(catalog.exportCapabilities.bundle)
    assertTrue(catalog.exportCapabilities.png)
    assertFalse(catalog.exportCapabilities.composeCode)
    val m3 =
      benchmarkCatalogExecutor(catalogSystemIds = setOf("m3-catalog")).listCatalogs().single()
    assertSame(m3, withWatchFaceBundle(m3))
  }

  @Test
  fun `each template bundles to the WFF source layout and passes WffValidator`() {
    for (id in TEMPLATES) {
      val document = template(id)
      val artifact = WatchFaceBundleExportExecutor(renderer).export(request(document))
      assertEquals(emptyList(), errors(artifact), id)
      val files = unzip(artifact)
      assertEquals(
        setOf(
          WffBundleLayout.MANIFEST_PATH,
          WffBundleLayout.WATCH_FACE_PATH,
          WffBundleLayout.WATCH_FACE_INFO_PATH,
          "res/drawable/preview.png",
        ),
        files.keys,
        id,
      )

      val emitted =
        assertIs<WatchFaceXmlExporter.Result.Emitted>(WatchFaceXmlExporter.export(document))
      val xml = files.getValue(WffBundleLayout.WATCH_FACE_PATH).decodeToString()
      assertEquals(emitted.xml, xml, "$id: the face is the exporter's own text")
      val version = assertNotNull(WffVersion.fromNumber(emitted.wffVersion))
      assertEquals(emptyList(), WffValidator.errors(WffXmlParser.parse(xml), version), id)

      val manifest = files.getValue(WffBundleLayout.MANIFEST_PATH).decodeToString()
      assertContains(manifest, WffBundleLayout.WFF_VERSION_PROPERTY)
      assertContains(manifest, "android:value=\"${emitted.wffVersion}\"")
      assertContains(
        files.getValue(WffBundleLayout.WATCH_FACE_INFO_PATH).decodeToString(),
        "@drawable/preview",
      )
      assertContentEquals(png, files.getValue("res/drawable/preview.png"), id)
    }
  }

  @Test
  fun `the same revision zips to the same bytes`() {
    val document = template("analog-watch-face")
    val executor = WatchFaceBundleExportExecutor(renderer)
    assertEquals(
      executor.export(request(document)).contentDigest,
      executor.export(request(document)).contentDigest,
    )
  }

  @Test
  fun `a design's pinned assets are its drawables`() {
    val logo = byteArrayOf(1, 2, 3)
    val document =
      withImage(template("digital-watch-face"), "logo")
        .copy(
          assets =
            mapOf(
              "logo" to AssetBindingV1("image/png", "d1", UploadedAssetSourceV1("d1")),
              "spare" to
                AssetBindingV1(
                  "image/webp",
                  "d2",
                  EmbeddedAssetSourceV1(Base64.getEncoder().encodeToString(byteArrayOf(4))),
                ),
              "preview" to AssetBindingV1("image/png", "d3", UploadedAssetSourceV1("d3")),
            ),
        )
    assertEquals(emptyList(), WatchFaceBundleExportExecutor.refusals(document))
    val artifact =
      WatchFaceBundleExportExecutor(
          { fail("a design that pins its own preview is not rendered") },
          MemoryAssets(mapOf("d1" to logo, "d3" to png)),
        )
        .export(request(document))
    assertEquals(emptyList(), errors(artifact))
    val files = unzip(artifact)
    assertContentEquals(logo, files.getValue("res/drawable/logo.png"))
    assertContentEquals(byteArrayOf(4), files.getValue("res/drawable/spare.webp"))
    assertContentEquals(png, files.getValue("res/drawable/preview.png"))
    assertContains(
      files.getValue(WffBundleLayout.WATCH_FACE_PATH).decodeToString(),
      "resource=\"logo\"",
    )
  }

  @Test
  fun `a face naming a bitmap it does not pin is refused with the node`() {
    val document = withImage(template("digital-watch-face"), "logo")
    val refusal = WatchFaceBundleExportExecutor.refusals(document).single()
    assertEquals(WatchFaceBundleExportExecutor.MISSING_RESOURCE, refusal.code)
    assertEquals("logo", refusal.nodeId)
    val artifact = WatchFaceBundleExportExecutor(renderer).export(request(document))
    assertEquals("", artifact.content)
    assertEquals(listOf(refusal.message), errors(artifact).map { it.message })
  }

  @Test
  fun `a design the exporter refuses gets its reasons and no archive`() {
    val document = withImage(template("digital-watch-face"), resource = null)
    val reasons =
      assertIs<WatchFaceXmlExporter.Result.Refused>(WatchFaceXmlExporter.export(document)).reasons
    val artifact =
      WatchFaceBundleExportExecutor(delegate = { fail("a refused face is not rendered") })
        .export(request(document))
    assertEquals("", artifact.content)
    assertEquals(reasons, errors(artifact).map { it.message })
    assertTrue(errors(artifact).all { it.code == WatchFaceBundleExportExecutor.EXPORT_REFUSED })
    assertTrue(errors(artifact).any { it.nodeId == "logo" }, "the refusal names the node")
  }

  @Test
  fun `other formats and catalogs pass through`() {
    val passed = ExportArtifactV1(ExportFormatV1.PNG, "image/png", ExportEncodingV1.BASE64, "", "")
    val executor = WatchFaceBundleExportExecutor(delegate = { passed })
    val document = template("analog-watch-face")
    assertSame(passed, executor.export(request(document).copy(format = ExportFormatV1.PNG)))
    val m3 =
      benchmarkCatalogExecutor(catalogSystemIds = setOf("m3-catalog")).listCatalogs().single()
    assertSame(passed, executor.export(request(document).copy(catalog = m3)))
  }

  private suspend fun PersistentUiBuilderService.run(request: UiBuilderRequestV1) =
    when (val mapping = UiBuilderProtocolMapper.toServiceCall(actor, request)) {
      is ProtocolRequestMapping.Mapped -> execute(mapping.call)
      else -> fail("unmapped request: $mapping")
    }

  @Test
  fun `the service exports a stored watch face as a bundle`() = runBlocking {
    val directory = Files.createTempDirectory("wff-bundle")
    try {
      val service =
        PersistentUiBuilderService(
          designStore = UiBuilderDesignStateStore.open(directory),
          catalogs = catalogs,
          exporter = WatchFaceBundleExportExecutor(renderer),
        )
      val document = template("analog-watch-face")
      val created =
        assertIs<UiBuilderServiceResponse.Snapshot>(
          service.run(CreateDesignRequestV1(document)),
        )
      val exported =
        service.run(
          ExportDesignRequestV1(
            document.id,
            created.snapshot.state.document.revision,
            ExportFormatV1.BUNDLE,
          ),
        )
      val artifact =
        assertIs<UiBuilderServiceResponse.Export>(exported, exported.toString()).artifact
      assertEquals(emptyList(), errors(artifact))
      assertContains(unzip(artifact).keys, WffBundleLayout.WATCH_FACE_PATH)
      // And the agent-facing listing carries the face as text.
      val listed = bundleEntries(Base64.getDecoder().decode(artifact.content))
      assertTrue(
        listed.any {
          it.toString().contains(WffBundleLayout.WATCH_FACE_PATH) &&
            it.toString().contains("\"text\"")
        },
      )
    } finally {
      directory.toFile().deleteRecursively()
    }
  }

  @Test
  fun `the design check reports the bundle export's refusals as errors`() = runBlocking {
    val validator =
      ScratchUiBuilderDraftValidator(catalogs, WatchFaceBundleExportExecutor(renderer))
    val shippable = validator.draft(actor, template("analog-watch-face"), null)
    assertEquals(emptyList(), shippable.problems.filter { it.severity == SEVERITY_ERROR })

    val refused = withImage(template("digital-watch-face"), resource = null)
    val problems =
      validator.draft(actor, refused, null).problems.filter { it.severity == SEVERITY_ERROR }
    val expected = WatchFaceBundleExportExecutor.refusals(refused)
    assertTrue(expected.isNotEmpty())
    assertEquals(expected.map { it.message }, problems.map { it.message })
    assertTrue(problems.all { it.source == SOURCE_EXPORT })
    assertTrue(problems.any { it.nodeId == "logo" }, "the problem names the node")

    val missing = withImage(template("digital-watch-face"), "logo")
    assertEquals(
      listOf(WatchFaceBundleExportExecutor.MISSING_RESOURCE),
      validator.draft(actor, missing, null).problems.map { it.code },
    )
  }

  private companion object {
    const val SYSTEM_ID = "wff-watchface"
    val TEMPLATES = listOf("analog-watch-face", "digital-watch-face")
  }
}
