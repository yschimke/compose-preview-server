package ee.schimke.composeai.cli.serve

import ee.schimke.watchface.packager.AndroidSdk
import ee.schimke.watchface.packager.DiagnosticSeverity
import ee.schimke.watchface.packager.PackagedWatchFace
import ee.schimke.watchface.packager.PackagerOptions
import ee.schimke.watchface.packager.PackagingDiagnostic
import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffVersion
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
  private val sdk = AndroidSdk(dir, File("aapt2"), File("zipalign"), File("android.jar"))

  private var installed = true
  private var haveSdk = true
  private var simulatorReason: String? = null
  private val packs = mutableListOf<Pair<Map<String, ByteArray>, PackagerOptions>>()
  private var refuse: PackagingException? = null
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
      packagerFor = {
        { files, options ->
          packs += files to options
          refuse?.let { throw it }
          PackagedWatchFace(
            apk = byteArrayOf(1, 2, 3),
            packageName = "p",
            versionCode = 1,
            wffVersion = WffVersion.V2,
            memoryFootprint = null,
            validationToken = null,
            diagnostics = emptyList(),
            signerCertificateSha256 = "00",
          )
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

    val (files, options) = packs.single()
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
      options.target.packageName,
    )
    assertEquals(1, options.versionCode)

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
    refuse =
      PackagingException(
        listOf(
          PackagingDiagnostic(DiagnosticSeverity.ERROR, "wff-validator", "cvc-bad"),
          PackagingDiagnostic(DiagnosticSeverity.ERROR, "aapt2", "no such resource"),
        ),
      )
    val e = assertFailsWith<DwfPackagingFailed> { runtime().packageApk(source()) }
    assertEquals(
      listOf("error[wff-validator]: cvc-bad", "error[aapt2]: no such resource"),
      e.diagnostics,
    )
    assertEquals(0, File(dir, "cache").listFiles { f -> f.name.endsWith(".apk") }.orEmpty().size)
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
}
