package ee.schimke.composeai.cli.serve

import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffManifest
import ee.schimke.watchface.wff.WffVersion
import ee.schimke.watchface.wff.WffWatchFaceInfo
import ee.schimke.watchface.wff.toXml
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** Packaging failed in a way the design can fix; [diagnostics] are the packager's own words. */
internal class DwfPackagingFailed(val diagnostics: List<String>) :
  DwfRuntimeException(diagnostics.joinToString("\n"))

/**
 * A [DwfSource] as a WFF source bundle, and the call into `:wff-packager` that turns it into the
 * signed APK the runtime loads. Pure aside from the packager call, which is a function so tests can
 * stand in for it (the real one needs an Android SDK).
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
   * The bundle's files keyed by bundle-relative path. `res/xml/watch_face_info.xml` is always the
   * server's default, and a `drawable/preview.png` is supplied unless the design's
   * [DwfSource.assets] carry their own, because the runtime's resource link fails on a face info
   * that names a missing preview.
   */
  fun bundleFiles(source: DwfSource): Map<String, ByteArray> {
    val version =
      WffVersion.fromNumber(source.wffVersion)
        ?: throw DwfPackagingFailed(
          listOf("error[bundle]: no such WFF version ${source.wffVersion}"),
        )
    val manifest =
      WffManifest(
        packageName = packageName(source),
        label = source.label.replace(Regex("[\"<>&]"), ""),
        wffVersion = version,
      )
    val files = LinkedHashMap<String, ByteArray>()
    for ((path, bytes) in source.assets.toSortedMap()) files["res/$path"] = bytes
    // The server's own files go in after the assets, so even an asset that slipped past
    // `DwfSource.problem()` cannot replace the validated document or the manifest.
    files[WffBundleLayout.MANIFEST_PATH] =
      WffBundleLayout.manifestDocument(manifest).toXml().encodeToByteArray()
    files[WffBundleLayout.WATCH_FACE_PATH] = source.wffXml.encodeToByteArray()
    files[WffBundleLayout.WATCH_FACE_INFO_PATH] =
      WffWatchFaceInfo().toDocument().toXml().encodeToByteArray()
    if (files.keys.none { PREVIEW_PRESENT.matches(it.removePrefix("res/")) }) {
      files["res/$PREVIEW_PATH"] = placeholderPreview()
    }
    return files
  }

  /** One package per exact face, so two designs never replace each other's install in a sandbox. */
  fun packageName(source: DwfSource): String =
    "ee.schimke.composeai.dwfpreview.f${source.digest().take(16)}"

  /**
   * Package [source] into [out] (atomically) through [packager], signing with the generated debug
   * key under the packager's own `WFF_PACKAGER_HOME` ([DwfWffPackager.signingDir]): the runtime
   * does not need a publishable signature, and a key kept next to the cache survives a restart
   * without being shared with anything else.
   *
   * @throws DwfPackagingFailed on any packager error, with every diagnostic it collected.
   */
  fun pack(source: DwfSource, out: File, packager: DwfWffPackager) {
    out.parentFile.mkdirs()
    val tmp = File(out.parentFile, out.name + ".tmp")
    try {
      packager.pack(bundleFiles(source), packageName(source), tmp)
      check(tmp.isFile && tmp.length() > 0L) { "the packager wrote no APK" }
      Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } finally {
      tmp.delete()
    }
  }

  private fun placeholderPreview(): ByteArray {
    val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
  }
}

/** The packager as a function: a bundle in, a signed APK at `out`. */
internal fun interface DwfWffPackager {
  /** @throws DwfPackagingFailed when the packager refuses the face. */
  fun pack(files: Map<String, ByteArray>, packageName: String, out: File)
}

/** The Android SDK pieces packaging needs; found the way the packager itself finds them. */
internal class DwfAndroidSdk(val root: File) {
  companion object {
    const val INSTALL_HINT =
      "sdkmanager \"build-tools;36.0.0\" \"platforms;android-36\" " +
        "(and point ANDROID_HOME or ANDROID_SDK_ROOT at the SDK)"

    /** The first of [roots] holding build-tools with `aapt2` and `zipalign` and a platform jar. */
    fun locate(
      roots: List<File> =
        listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull {
          System.getenv(it)?.takeIf(String::isNotBlank)?.let(::File)
        },
    ): DwfAndroidSdk? = roots.firstOrNull(::usable)?.let(::DwfAndroidSdk)

    private fun usable(root: File): Boolean {
      val tools =
        root.resolve("build-tools").listFiles { f ->
          f.isDirectory && f.resolve("aapt2").canExecute() && f.resolve("zipalign").canExecute()
        }
      val platform =
        root.resolve("platforms").listFiles { f ->
          f.isDirectory && f.resolve("android.jar").isFile
        }
      return !tools.isNullOrEmpty() && !platform.isNullOrEmpty()
    }
  }
}

/**
 * `wff-packager` run as `java -cp "<lib-wff-packager>/(every jar)"
 * ee.schimke.watchface.packager.MainKt pack` in its own JVM, because its Google validator carries
 * `xercesImpl` and a second JAXP implementation on this server's classpath replaces the JDK's for
 * every DOM user in the process. The CLI is the packager's public entry point; its stderr
 * diagnostics (`error[source]: message`) become [DwfPackagingFailed.diagnostics].
 */
internal class DwfSubprocessPackager(
  private val libDir: File,
  private val sdk: DwfAndroidSdk,
  /** Holds the generated per-installation debug key (`WFF_PACKAGER_HOME`). */
  val signingDir: File,
  private val java: String = File(System.getProperty("java.home"), "bin/java").path,
  private val timeoutSeconds: Long = 180,
) : DwfWffPackager {
  override fun pack(files: Map<String, ByteArray>, packageName: String, out: File) {
    val work = Files.createTempDirectory("dwf-pack").toFile()
    try {
      val bundle = File(work, "bundle")
      val canonical = bundle.canonicalPath + File.separator
      for ((path, bytes) in files) {
        val target = File(bundle, path)
        // The paths are this server's own, but a bundle path is still never trusted to stay inside.
        check(target.canonicalPath.startsWith(canonical)) { "bundle path escapes: $path" }
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
      }
      signingDir.mkdirs()
      val command =
        listOf(
          java,
          "-Djava.awt.headless=true",
          "-cp",
          libDir.resolve("*").path,
          "ee.schimke.watchface.packager.MainKt",
          "pack",
          "--bundle",
          bundle.path,
          "--out",
          out.path,
          "--version-code",
          "1",
          "--package",
          packageName,
        )
      // Output goes to a file, not a pipe: draining a pipe blocks until EOF, so a hung packager (or
      // an aapt2 child holding stdout open) would never reach the timed wait below.
      val log = File(work, "packager.log")
      val builder =
        ProcessBuilder(command)
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.to(log))
      builder.environment().apply {
        put("ANDROID_HOME", sdk.root.path)
        put("ANDROID_SDK_ROOT", sdk.root.path)
        put("WFF_PACKAGER_HOME", signingDir.path)
        // A stale signing secret in this server's environment must not leak into a debug build.
        remove("WFF_SIGNING_KEYSTORE_B64")
      }
      val process = builder.start()
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(10, TimeUnit.SECONDS)
        throw DwfRuntimeException("wff-packager did not finish in ${timeoutSeconds}s")
      }
      val output = if (log.isFile) log.readText() else ""
      if (process.exitValue() != 0) throw DwfPackagingFailed(diagnostics(output))
    } finally {
      work.deleteRecursively()
    }
  }

  companion object {
    private val DIAGNOSTIC = Regex("^(error|warning|info)\\[[^\\]]+\\]: .*")

    /** The packager's error lines, else whatever it said (a usage error has no diagnostic form). */
    internal fun diagnostics(output: String): List<String> {
      val lines = output.lines().map(String::trim).filter(String::isNotEmpty)
      val errors = lines.filter { it.startsWith("error[") && DIAGNOSTIC.matches(it) }
      return errors.ifEmpty { listOf(lines.lastOrNull() ?: "wff-packager failed with no output") }
    }
  }
}
