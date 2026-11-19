package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.WatchFaceXmlExporter
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.watchface.wff.WffAssets
import ee.schimke.watchface.wff.WffBundle
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffManifest
import ee.schimke.watchface.wff.WffResourceFile
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffWatchFaceInfo
import ee.schimke.watchface.wff.WffXmlParser
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * The `bundle` export of a `watchface` design: a zip in the standard Watch Face Format source
 * layout (`WffBundleLayout`), the same files the Watch Face Push and preview builds package
 * ([DwfPackaging]), so it can be dropped into a WFF project or handed to `wff-packager`.
 * - `res/raw/watchface.xml`, exactly what [WatchFaceXmlExporter] writes;
 * - `res/xml/watch_face_info.xml`, whose preview is `@drawable/preview`;
 * - `res/drawable/preview.png`, the design's own PNG export (unless it pins a `preview` asset);
 * - `AndroidManifest.xml`, carrying the design's WFF version as
 *   `com.google.wear.watchface.format.version`;
 * - every pinned asset (`ui_builder_put_asset`) as `res/drawable/<assetKey>.<ext>`, which is the
 *   name a `resource` attribute refers to it by.
 *
 * A design that cannot be bundled gets no partial archive: empty content and one ERROR diagnostic
 * per reason, with the node each names. [refusals] is that list, and `check_design` reports the
 * same one, so a design the check passes is a design this export packages.
 *
 * Every other catalog and format passes through to [delegate]. The PNG preview is asked of
 * [delegate] too, so it is drawn by the same renderer the `png` export uses.
 */
internal class WatchFaceBundleExportExecutor(
  private val delegate: UiBuilderExportExecutor,
  private val assets: UiBuilderAssetStore? = null,
) : UiBuilderExportExecutor {
  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    if (request.format != ExportFormatV1.BUNDLE || !isWatchFace(request.catalog))
      return delegate.export(request)
    return when (val bundle = bundle(request.document, assets) { preview(request) }) {
      is Bundle.Refused -> artifact(byteArrayOf(), bundle.diagnostics)
      is Bundle.Built ->
        artifact(
          zip(bundle.files),
          bundle.diagnostics +
            ExportDiagnosticV1(
              DiagnosticSeverityV1.INFO,
              "REVISION_PINNED_WFF_BUNDLE_EXPORT",
              "Exported design ${request.designId} revision ${request.revision} " +
                "(${request.documentHash}) as a WFF source bundle: " +
                bundle.files.keys.joinToString(", ") +
                ".",
            ),
        )
    }
  }

  /** The design's PNG export, or why there is none (the bundle then carries a placeholder). */
  private fun preview(request: RevisionPinnedUiBuilderExport): Pair<ByteArray?, String?> {
    val png = runCatching {
      delegate.export(request.copy(format = ExportFormatV1.PNG))
    }
      .getOrElse {
        return null to (it.message ?: it::class.simpleName ?: "the PNG export failed")
      }
    val bytes =
      png
        .takeIf { it.encoding == ExportEncodingV1.BASE64 && it.content.isNotEmpty() }
        ?.let { runCatching { Base64.getDecoder().decode(it.content) }.getOrNull() }
    val error = png.diagnostics.firstOrNull { it.severity == DiagnosticSeverityV1.ERROR }
    return when {
      error != null -> null to error.message
      bytes == null -> null to "the PNG export returned no image"
      else -> bytes to null
    }
  }

  private fun artifact(bytes: ByteArray, diagnostics: List<ExportDiagnosticV1>) =
    ExportArtifactV1(
      format = ExportFormatV1.BUNDLE,
      mediaType = MEDIA_TYPE,
      encoding = ExportEncodingV1.BASE64,
      content = Base64.getEncoder().encodeToString(bytes),
      contentDigest =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
      diagnostics = diagnostics,
    )

  internal sealed interface Bundle {
    data class Built(
      val files: Map<String, ByteArray>,
      val diagnostics: List<ExportDiagnosticV1>,
    ) : Bundle

    data class Refused(val diagnostics: List<ExportDiagnosticV1>) : Bundle
  }

  /** One reason a design cannot be bundled, with the node it names when it names one. */
  internal data class Refusal(val code: String, val message: String, val nodeId: String? = null)

  companion object {
    const val MEDIA_TYPE = "application/zip"

    /** The exporter's refusals. */
    const val EXPORT_REFUSED = "WFF_EXPORT_REFUSED"

    /** A `resource` attribute naming a bitmap the design does not pin. */
    const val MISSING_RESOURCE = WatchFaceDrawables.MISSING_RESOURCE

    /** A pinned asset whose bytes this host cannot read, or whose key is no drawable name. */
    const val UNUSABLE_ASSET = WatchFaceDrawables.UNUSABLE_ASSET

    private val NODE_REASON = Regex("^nodes\\.([^ .(:]+)")

    /** Zip entries are stamped with one fixed time, so the same revision zips to the same bytes. */
    private const val ENTRY_TIME = 315_532_800_000L // 1980-01-01, the zip format's epoch

    fun isWatchFace(catalog: CatalogCapabilityV1): Boolean =
      UiBuilderCatalogPlatform.from(catalog.statusSemantics) == UiBuilderCatalogPlatform.WATCH_FACE

    /**
     * Every reason [document] cannot be bundled, as the export would report it; empty when it can.
     * The exporter's own refusals, then the bitmaps its `resource` attributes name that the design
     * does not pin. Byte availability is not judged here: that is a fact about a host's store.
     */
    fun refusals(document: DesignDocumentV1): List<Refusal> =
      when (val exported = exportXml(document)) {
        is WatchFaceXmlExporter.Result.Refused ->
          exported.reasons.map { reason ->
            Refusal(
              EXPORT_REFUSED,
              reason,
              NODE_REASON.find(reason)?.groupValues?.get(1)?.takeIf { it in document.nodes },
            )
          }
        is WatchFaceXmlExporter.Result.Emitted -> missingResources(document, exported.xml)
      }

    private fun exportXml(document: DesignDocumentV1): WatchFaceXmlExporter.Result = runCatching {
      WatchFaceXmlExporter.export(document)
    }
      .getOrElse {
        WatchFaceXmlExporter.Result.Refused(
          listOf("document: the Watch Face Format export could not read the design: ${it.message}"),
        )
      }

    private fun missingResources(document: DesignDocumentV1, xml: String): List<Refusal> =
      WatchFaceDrawables.missing(xml, WatchFaceDrawables.pinnedNames(document), document).map {
        Refusal(it.code, it.message, it.nodeId)
      }

    /**
     * [document] as bundle files, or every reason it cannot be one. [preview] answers the PNG
     * export, or why there is none; it is not called when the design pins its own `preview`.
     */
    internal fun bundle(
      document: DesignDocumentV1,
      assets: UiBuilderAssetStore?,
      preview: () -> Pair<ByteArray?, String?>,
    ): Bundle {
      val exported =
        when (val result = exportXml(document)) {
          is WatchFaceXmlExporter.Result.Refused ->
            return Bundle.Refused(refusals(document).map { it.diagnostic() })
          is WatchFaceXmlExporter.Result.Emitted -> result
        }
      val diagnostics = mutableListOf<ExportDiagnosticV1>()
      val errors = missingResources(document, exported.xml).map { it.diagnostic() }.toMutableList()
      exported.notes.forEach {
        diagnostics += ExportDiagnosticV1(DiagnosticSeverityV1.INFO, "WFF_EXPORT_NOTE", it)
      }
      val pinned =
        WatchFaceDrawables.pinned(
          document,
          assets,
          WatchFaceDrawables.referenced(exported.xml).toSet(),
        )
      for (problem in pinned.problems) {
        val diagnostic =
          ExportDiagnosticV1(
            if (problem.required) DiagnosticSeverityV1.ERROR else DiagnosticSeverityV1.WARNING,
            problem.code,
            problem.message,
            problem.nodeId,
          )
        if (problem.required) errors += diagnostic else diagnostics += diagnostic
      }
      val files =
        pinned.files
          .map { (path, bytes) ->
            WffResourceFile(path.substringBefore('/'), path.substringAfter('/'), bytes)
          }
          .toMutableList()
      if (errors.isNotEmpty()) return Bundle.Refused(errors + diagnostics)

      val version = WffVersion.fromNumber(exported.wffVersion)
      if (version == null) {
        return Bundle.Refused(
          listOf(
            ExportDiagnosticV1(
              DiagnosticSeverityV1.ERROR,
              EXPORT_REFUSED,
              "the exporter wrote WFF version ${exported.wffVersion}, which wff-core does not know",
            ),
          ),
        )
      }
      if (files.none { it.fileName.substringBeforeLast('.') == WatchFaceDrawables.PREVIEW }) {
        val (png, why) = preview()
        files +=
          WffResourceFile(
            "drawable",
            "${WatchFaceDrawables.PREVIEW}.png",
            png
              ?: placeholderPreview().also {
                diagnostics +=
                  ExportDiagnosticV1(
                    DiagnosticSeverityV1.WARNING,
                    "WFF_BUNDLE_PLACEHOLDER_PREVIEW",
                    "the preview drawable is a blank placeholder: the design's PNG export " +
                      "failed ($why)",
                  )
              },
          )
      }
      val watchFace =
        WffXmlParser.parseOrNull(exported.xml)
          ?: return Bundle.Refused(
            listOf(
              ExportDiagnosticV1(
                DiagnosticSeverityV1.ERROR,
                EXPORT_REFUSED,
                "the exported watchface.xml does not parse",
              ),
            ),
          )
      val manifest =
        WffManifest(
          packageName = packageName(document),
          label = document.title.ifBlank { document.id }.replace(Regex("[\"<>&]"), ""),
          wffVersion = version,
        )
      val laidOut =
        WffBundleLayout.sourceFiles(
          WffBundle(manifest, watchFace, WffWatchFaceInfo(), WffAssets(files)),
        )
      // The exporter's exact text, not wff-core's re-serialisation of it, so the bundle's face is
      // byte for byte the `watchface.xml` the other lanes (and dwf-preview) are handed.
      val out = LinkedHashMap(laidOut)
      out[WffBundleLayout.WATCH_FACE_PATH] = exported.xml.encodeToByteArray()
      return Bundle.Built(out, diagnostics)
    }

    /** A stable package per design; a project taking the bundle sets its own. */
    internal fun packageName(document: DesignDocumentV1): String =
      "ee.schimke.composeai.watchface.d" +
        MessageDigest.getInstance("SHA-256")
          .digest(document.id.encodeToByteArray())
          .joinToString("") { "%02x".format(it) }
          .take(16)

    private fun Refusal.diagnostic() =
      ExportDiagnosticV1(DiagnosticSeverityV1.ERROR, code, message, nodeId)

    private fun placeholderPreview(): ByteArray {
      val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
      return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    internal fun zip(files: Map<String, ByteArray>): ByteArray =
      ByteArrayOutputStream()
        .also { bytes ->
          ZipOutputStream(bytes).use { zip ->
            for ((path, content) in files) {
              zip.putNextEntry(ZipEntry(path).also { it.time = ENTRY_TIME })
              zip.write(content)
              zip.closeEntry()
            }
          }
        }
        .toByteArray()
  }
}

/**
 * [catalog] advertising `bundle` when it is a `watchface` catalog, which
 * [WatchFaceBundleExportExecutor] writes. Derived here, from the platform, rather than declared by
 * the catalog's `ui-builder.json`: whether a host can package a format is a fact about the host,
 * the way `png` is the renderer's.
 */
internal fun withWatchFaceBundle(catalog: CatalogCapabilityV1): CatalogCapabilityV1 =
  if (!WatchFaceBundleExportExecutor.isWatchFace(catalog) || catalog.exportCapabilities.bundle)
    catalog
  else
    catalog
      .newBuilder()
      .also {
        it.exportCapabilities =
          catalog.exportCapabilities.newBuilder().also { exports -> exports.bundle = true }.build()
      }
      .build()
