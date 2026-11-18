package ee.schimke.composeai.cli.serve

import ee.schimke.watchface.packager.AndroidSdk
import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.packager.PackagedWatchFace
import ee.schimke.watchface.packager.PackagerOptions
import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.packager.SigningConfig
import ee.schimke.watchface.packager.WatchFacePackager
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffManifest
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffWatchFaceInfo
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/** Packaging failed in a way the design can fix; [diagnostics] are the packager's own words. */
internal class DwfPackagingFailed(val diagnostics: List<String>) :
  DwfRuntimeException(diagnostics.joinToString("\n"))

/**
 * A [DwfSource] as a WFF source bundle, and the call into `:wff-packager` that turns it into the
 * signed APK the runtime loads. Pure aside from the packager call, which is a function so tests
 * can stand in for it (the real one needs an Android SDK).
 *
 * The layout is the design doc's: `xml` → `res/raw/watchface.xml`, `assets` under `res/`, and the
 * WFF version in the manifest's `com.google.wear.watchface.format.version`, which is where the
 * packager reads it back from to check the face against it.
 */
internal object DwfPackaging {
  /** A drawable a face's `watch_face_info.xml` points at when the design supplies none. */
  private const val PREVIEW_PATH = "drawable/preview.png"

  private val PREVIEW_PRESENT = Regex("drawable[^/]*/preview\\.(png|webp|jpe?g)")

  /**
   * The bundle's files keyed by bundle-relative path. `res/xml/watch_face_info.xml` and a
   * `drawable/preview.png` are supplied unless the design's [DwfSource.assets] carry their own,
   * because the runtime's resource link fails on a face info that names a missing preview.
   */
  fun bundleFiles(source: DwfSource): Map<String, ByteArray> {
    val version =
      WffVersion.fromNumber(source.wffVersion)
        ?: throw DwfPackagingFailed(listOf("error[bundle]: no such WFF version ${source.wffVersion}"))
    val manifest =
      WffManifest(
        packageName = packageName(source),
        label = source.label.replace(Regex("[\"<>&]"), ""),
        wffVersion = version,
      )
    val files = LinkedHashMap<String, ByteArray>()
    files[WffBundleLayout.MANIFEST_PATH] =
      WffBundleLayout.manifestDocument(manifest).toXml().encodeToByteArray()
    files[WffBundleLayout.WATCH_FACE_PATH] = source.wffXml.encodeToByteArray()
    for ((path, bytes) in source.assets.toSortedMap()) files["res/$path"] = bytes
    if (WffBundleLayout.WATCH_FACE_INFO_PATH !in files) {
      files[WffBundleLayout.WATCH_FACE_INFO_PATH] =
        WffWatchFaceInfo().toDocument().toXml().encodeToByteArray()
    }
    if (files.keys.none { PREVIEW_PRESENT.matches(it.removePrefix("res/")) }) {
      files["res/$PREVIEW_PATH"] = placeholderPreview()
    }
    return files
  }

  /** One package per exact face, so two designs never replace each other's install in a sandbox. */
  fun packageName(source: DwfSource): String =
    "ee.schimke.composeai.dwfpreview.f${source.digest().take(16)}"

  /**
   * Package [source] into [out] (atomically), signing with the generated debug key under
   * [signingDir]: the runtime does not need a publishable signature, and a key kept next to the
   * cache survives a restart without being shared with anything else.
   *
   * @throws DwfPackagingFailed on any packager error, with every diagnostic it collected.
   */
  fun pack(
    source: DwfSource,
    out: File,
    signingDir: File,
    pack: (Map<String, ByteArray>, PackagerOptions) -> PackagedWatchFace,
  ) {
    val options =
      PackagerOptions(
        target = PackageTarget.Sideload(packageName(source)),
        versionCode = 1,
        signing = SigningConfig.GeneratedDebugKey(signingDir),
      )
    val packaged =
      try {
        pack(bundleFiles(source), options)
      } catch (e: PackagingException) {
        throw DwfPackagingFailed(e.diagnostics.map { it.toString() })
      }
    out.parentFile.mkdirs()
    val tmp = File(out.parentFile, out.name + ".tmp")
    tmp.writeBytes(packaged.apk)
    Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }

  /** The production packager call, against [sdk]. */
  fun packagerFor(sdk: AndroidSdk): (Map<String, ByteArray>, PackagerOptions) -> PackagedWatchFace {
    val packager = WatchFacePackager(sdk)
    return { files, options -> packager.pack(files, options) }
  }

  private fun placeholderPreview(): ByteArray {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }
}
