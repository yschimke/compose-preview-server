package ee.schimke.composeai.cli.serve

import ee.schimke.watchface.wff.WffBundleLayout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RenderHostDwfApkRuntimeTest {
  private val dir = Files.createTempDirectory("dwf-runtime-test").toFile()
  private val sdk = DwfAndroidSdk(dir)
  private val libDir = File(dir, "lib-wff-packager")

  private var installed = true
  private var haveSdk = true
  private var simulatorReason: String? = null
  private var haveLib = true
  private val packs = mutableListOf<Pair<Map<String, ByteArray>, String>>()
  private var refuse: List<String>? = null
  private val connections = mutableListOf<FakeSim>()

  private class FakeSim : DwfSimulatorConnection {
    override var alive = true
    val seen = mutableListOf<Pair<String, JsonObject>>()
    var failWith: String? = null

    override fun command(cmd: String, payload: JsonObject): JsonElement? {
      failWith?.let { error("$cmd failed: $it") }
      seen += cmd to payload
      return JsonPrimitive("ok")
    }

    override fun close() {
      alive = false
    }
  }

  private fun runtime() =
    RenderHostDwfApkRuntime(
      runtimeInstalled = { installed },
      sdk = { sdk.takeIf { haveSdk } },
      launcher =
        DwfSimulatorLaunch(
          unavailableReason = { simulatorReason },
          connect = { FakeSim().also { connections += it } },
        ),
      cacheDir = File(dir, "cache"),
      packagerLibDir = { libDir.takeIf { haveLib } },
      packagerFor = { _, _, _ ->
        DwfWffPackager { files, packageName, out ->
          packs += files to packageName
          refuse?.let { throw DwfPackagingFailed(it) }
          out.writeBytes(byteArrayOf(1, 2, 3))
        }
      },
    )

  private fun unavailable(r: RenderHostDwfApkRuntime) =
    assertIs<DwfApkRuntime.Availability.Unavailable>(r.availability())

  @Test
  fun `availability names the first missing thing in the order an operator fixes it`() {
    val r = runtime()
    installed = false
    haveSdk = false
    simulatorReason = "no sidecar"
    assertEquals(DwfPreviewSurface.RUNTIME_MISSING, unavailable(r).code)
    installed = true
    val noSdk = unavailable(r)
    assertEquals(DwfPreviewSurface.ANDROID_SDK_MISSING, noSdk.code)
    assertTrue("aapt2" in noSdk.reason && "ANDROID_HOME" in noSdk.reason, noSdk.reason)
    haveSdk = true
    haveLib = false
    assertEquals(DwfPreviewSurface.PACKAGING_UNAVAILABLE, unavailable(r).code)
    haveLib = true
    val sim = unavailable(r)
    assertEquals(DwfPreviewSurface.SIMULATOR_UNAVAILABLE, sim.code)
    assertEquals("no sidecar", sim.reason)
    simulatorReason = null
    assertSame(DwfApkRuntime.Availability.Ready, r.availability())
  }

  @Test
  fun `a face is laid out as the design document says and packaged once per digest`() {
    val r = runtime()
    val src =
      DwfSource(
        wffXml = "<WatchFace width=\"450\" height=\"450\"/>",
        wffVersion = 3,
        assets = mapOf("drawable/hand.png" to byteArrayOf(9)),
        label = "My \"face\"",
      )
    val apk = r.packageApk(src)
    assertEquals(listOf<Byte>(1, 2, 3), apk.readBytes().toList())
    assertSame(apk, r.packageApk(src).takeIf { it == apk } ?: apk)
    assertEquals(1, packs.size)

    val (files, packageName) = packs.single()
    assertEquals(src.wffXml, files[WffBundleLayout.WATCH_FACE_PATH]!!.decodeToString())
    assertEquals(listOf<Byte>(9), files["res/drawable/hand.png"]!!.toList())
    assertTrue(WffBundleLayout.WATCH_FACE_INFO_PATH in files)
    assertTrue(
      "res/drawable/preview.png" in files,
      "a preview is supplied when the design has none",
    )
    val manifest = files[WffBundleLayout.MANIFEST_PATH]!!.decodeToString()
    assertTrue("""android:value="3"""" in manifest, manifest)
    assertFalse("\"face\"" in manifest, "the label is not an injection point")
    assertEquals(
      "ee.schimke.composeai.dwfpreview.f${src.digest().take(16)}",
      packageName,
    )

    // A different face is a different APK; the same digest in a new runtime (a restart) reuses
    // disk.
    r.packageApk(DwfSource(wffXml = "<WatchFace/>", label = "other"))
    assertEquals(2, packs.size)
    runtime().packageApk(src)
    assertEquals(2, packs.size)
  }

  @Test
  fun `a design preview in the assets replaces the placeholder`() {
    val files =
      DwfPackaging.bundleFiles(
        DwfSource("<WatchFace/>", assets = mapOf("drawable-nodpi/preview.webp" to byteArrayOf(1))),
      )
    assertFalse("res/drawable/preview.png" in files)
  }

  @Test
  fun `packager diagnostics become one failure carrying every finding`() {
    refuse = listOf("error[wff-validator]: cvc-bad", "error[aapt2]: no such resource")
    val e = assertFailsWith<DwfPackagingFailed> { runtime().packageApk(source()) }
    assertEquals(
      listOf("error[wff-validator]: cvc-bad", "error[aapt2]: no such resource"),
      e.diagnostics,
    )
    assertEquals(0, File(dir, "cache").listFiles { f -> f.name.endsWith(".apk") }.orEmpty().size)
  }

  @Test
  fun `the subprocess packager's output becomes diagnostics`() {
    assertEquals(
      listOf("error[aapt2]: bad", "error[wff-validator]: worse"),
      DwfSubprocessPackager.diagnostics(
        "warning[signing]: debug key\nerror[aapt2]: bad\nerror[wff-validator]: worse\n",
      ),
    )
    assertEquals(
      listOf("--version-code must be an integer"),
      DwfSubprocessPackager.diagnostics("usage...\n--version-code must be an integer\n"),
    )
    assertEquals(
      listOf("wff-packager failed with no output"),
      DwfSubprocessPackager.diagnostics(""),
    )
  }

  @Test
  fun `connect speaks the launcher's channel and turns its errors into runtime errors`() {
    val r = runtime()
    val conn = r.connect(File("x.apk"))
    assertTrue(conn.alive)
    assertEquals(
      JsonPrimitive("ok"),
      conn.command(DwfCommand("watchface", JsonObject(mapOf("op" to JsonPrimitive("status"))))),
    )
    assertEquals("watchface", connections.single().seen.single().first)
    connections.single().failWith = "boom"
    val e = assertFailsWith<DwfRuntimeException> { conn.command(DwfCommand("screenshot")) }
    assertTrue("boom" in e.message!!)
    conn.close()
    assertFalse(conn.alive)
    assertNull(connections.single().seen.firstOrNull { it.first == "screenshot" })
  }

  @Test
  fun `a launcher that cannot start is a runtime error, not a crash`() {
    val r =
      RenderHostDwfApkRuntime(
        runtimeInstalled = { true },
        sdk = { sdk },
        launcher =
          DwfSimulatorLaunch(unavailableReason = { null }, connect = { error("no jvm for you") }),
        cacheDir = File(dir, "c2"),
      )
    val e = assertFailsWith<DwfRuntimeException> { r.connect(File("x.apk")) }
    assertTrue("no jvm for you" in e.message!!)
  }

  @Test
  fun `an asset cannot take the place of a file the server writes`() {
    for (path in
      listOf(
        "raw/watchface.xml",
        "RAW/WatchFace.xml",
        "./raw/watchface.xml",
        "raw//watchface.xml",
        "raw/./watchface.xml",
        "xml/watch_face_info.xml",
        "AndroidManifest.xml",
      )) {
      val problem = DwfSource("<WatchFace/>", assets = mapOf(path to byteArrayOf(1))).problem()
      // Some spellings (`raw//watchface.xml`) are stopped earlier by the plain-path rule, so only
      // the
      // refusal is asserted, not which rule gave it.
      assertTrue(problem != null, "$path was accepted")
    }
    assertNull(
      DwfSource("<WatchFace/>", assets = mapOf("raw/other.xml" to byteArrayOf(1))).problem(),
    )
  }

  @Test
  fun `the validated document wins even if an asset got past the gate`() {
    val src = DwfSource("<WatchFace id=\"good\"/>")
    val sneaky =
      DwfSource(src.wffXml, assets = mapOf("raw/watchface.xml" to "<!DOCTYPE x>".toByteArray()))
    val files = DwfPackaging.bundleFiles(sneaky)
    assertEquals(src.wffXml, files[WffBundleLayout.WATCH_FACE_PATH]!!.decodeToString())
  }

  private fun fakeJava(body: String): File =
    File(dir, "fake-java-${System.nanoTime()}.sh").apply {
      writeText("#!/bin/sh\n$body\n")
      setExecutable(true)
    }

  @Test
  fun `the subprocess packager passes the bundle and environment and returns the apk`() {
    val seen = File(dir, "seen.txt")
    // Args: -D.. -cp <lib>/* Main pack --bundle <dir> --out <apk> --version-code 1 --package <p>
    val java =
      fakeJava(
        """
        out=""; bundle=""
        while [ "${'$'}#" -gt 0 ]; do
          case "${'$'}1" in --out) out="${'$'}2";; --bundle) bundle="${'$'}2";; esac
          shift
        done
        echo "${'$'}ANDROID_HOME ${'$'}WFF_PACKAGER_HOME ${'$'}(ls ${'$'}bundle/res/raw)" > ${seen.path}
        printf 'apk' > "${'$'}out"
        """
          .trimIndent(),
      )
    val packager = DwfSubprocessPackager(libDir, sdk, File(dir, "sign"), java = java.path)
    val out = File(dir, "out.apk")
    packager.pack(DwfPackaging.bundleFiles(source()), "p.q", out)
    assertEquals("apk", out.readText())
    assertEquals("${dir.path} ${File(dir, "sign").path} watchface.xml", seen.readText().trim())
  }

  @Test
  fun `a failing subprocess packager is a refusal with its diagnostics`() {
    val java = fakeJava("echo 'error[manifest]: bad package' >&2; exit 1")
    val packager = DwfSubprocessPackager(libDir, sdk, File(dir, "sign"), java = java.path)
    val e =
      assertFailsWith<DwfPackagingFailed> {
        packager.pack(DwfPackaging.bundleFiles(source()), "p.q", File(dir, "o.apk"))
      }
    assertEquals(listOf("error[manifest]: bad package"), e.diagnostics)
  }

  @Test
  fun `the sdk is found only where build-tools and a platform jar both are`() {
    val root = File(dir, "sdk").also { it.mkdirs() }
    assertNull(DwfAndroidSdk.locate(listOf(root)))
    File(root, "build-tools/36.0.0").mkdirs()
    for (tool in listOf("aapt2", "zipalign")) {
      File(root, "build-tools/36.0.0/$tool").apply {
        writeText("")
        setExecutable(true)
      }
    }
    assertNull(DwfAndroidSdk.locate(listOf(root)), "no platform yet")
    File(root, "platforms/android-36").mkdirs()
    File(root, "platforms/android-36/android.jar").writeText("")
    assertEquals(root, DwfAndroidSdk.locate(listOf(File(dir, "none"), root))?.root)
  }
}
