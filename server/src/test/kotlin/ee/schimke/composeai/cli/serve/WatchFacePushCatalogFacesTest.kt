package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.TrustStore
import ee.schimke.composeai.bundle.TrustedBranch
import ee.schimke.composeai.uibuilder.service.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.io.TempDir

/**
 * Faces from the served catalogs in the Watch Face Push API: the bundle's WFF source kept through
 * the catalog store, the licence allowlist, the listing in the push app's catalog shape, and the
 * APK and picture behind it — against fake catalog bundles, no network.
 */
class WatchFacePushCatalogFacesTest {
  @TempDir lateinit var dir: Path
  private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
  private val registries = mutableListOf<ServeSessionRegistry>()
  private var server: ServeHttpServer? = null

  /** What the fake packager was handed, per face name. */
  private val packed = mutableMapOf<String, Map<String, ByteArray>>()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registries.forEach { it.close() }
  }

  private fun wffZip(xml: String, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      val entries =
        linkedMapOf(
          "res/raw/watchface.xml" to xml.toByteArray(),
          "res/xml/watch_face_info.xml" to "<WatchFaceInfo/>".toByteArray(),
          "res/drawable/hand.png" to byteArrayOf(9, 9, 9),
          "AndroidManifest.xml" to "<manifest package=\"com.upstream.face\"/>".toByteArray(),
          "LICENSE" to "Apache License".toByteArray(),
        ) + extra
      for ((name, bytes) in entries) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun manifest(previewId: String, license: String?, wffVersion: Int? = 2) =
    Json.encodeToString(
      WffSourceManifestV1.serializer(),
      WffSourceManifestV1(
        previewId = previewId,
        bundle = "previews/$previewId.wff.zip",
        wffVersion = wffVersion,
        repoUrl = "https://github.com/android/wear-os-samples",
        commit = "0123456789abcdef0123456789abcdef01234567",
        sourcePath = "WatchFaceFormat/SimpleDigital/src/main/res/raw/watchface.xml",
        sourceUrl =
          "https://github.com/android/wear-os-samples/blob/0123456/WatchFaceFormat/SimpleDigital",
        license = license,
        licenseFile = license?.let { "LICENSE" },
      ),
    )

  /** A catalog bundle directory with one watch-face preview per entry of [faces]. */
  private fun catalog(
    name: String,
    vararg faces: Triple<String, String?, String>,
  ): ServeBundleHost {
    val bundle = dir.resolve("catalogs/$name").toFile()
    val previews = File(bundle, "previews").also { it.mkdirs() }
    for ((id, license, xml) in faces) {
      File(previews, "$id.png").writeBytes("png:$name/$id".toByteArray())
      File(previews, "$id.wff.json").writeText(manifest(id, license))
      File(previews, "$id.wff.zip").writeBytes(wffZip(xml))
    }
    File(previews, "plain.png").writeBytes("png".toByteArray())
    return ServeBundleHost(bundle, label = name, isCatalog = true, title = "$name catalog")
  }

  private fun start(
    listed: Map<String, ServeBundleHost>,
    unlisted: Map<String, ServeBundleHost> = emptyMap(),
  ) {
    val registry = ServeSessionRegistry(open = { null }).also(registries::add)
    (listed + unlisted).forEach { (system, host) ->
      registry.register(system, host = host, pinned = true)
    }
    val packager = WatchFacePushPackager { files, faceName, versionCode, out ->
      if ("REFUSE" in files.getValue("res/raw/watchface.xml").decodeToString()) {
        throw DwfPackagingFailed(listOf("error[validator]: refused for the test"))
      }
      packed[faceName] = files
      out.writeBytes("catalog-apk:$faceName:$versionCode".toByteArray())
      "token-$faceName-$versionCode"
    }
    val push =
      WatchFacePush(
        store = WatchFacePushStore(dir.resolve("watchface-push")),
        packaging = { WatchFacePushPackaging.Ready(packager, "c0ffee") },
        sourceFor = { null },
        isWatchFaceCatalog = { false },
      )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "local-fixture",
          sessions = registry,
          defaultSessionId = "unused",
          catalogSessions = listed.keys.toList(),
          appCatalogSessions = unlisted.keys.toList(),
          machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
          uiBuilderService =
            PersistentUiBuilderService(
              storage = FileUiBuilderStateStorage(dir.resolve("state")),
              limits = UiBuilderServiceLimits(),
              catalogs = benchmarkCatalogExecutor(),
              exporter = UiBuilderExportExecutor { error("no export in this test") },
            ),
          watchFacePush = push,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization { _, _, _ -> UiBuilderAuthorizationDecision.Missing },
        )
        .also { it.start() }
  }

  private data class Reply(val code: Int, val body: String, val bytes: ByteArray)

  private fun get(path: String): Reply =
    client
      .newCall(Request.Builder().url("http://127.0.0.1:${server!!.port}$path").build())
      .execute()
      .use {
        val bytes = it.body.bytes()
        Reply(it.code, bytes.decodeToString(), bytes)
      }

  private fun pathOf(url: String) = "/" + url.substringAfter("://").substringAfter('/')

  /** The push app's model, plus the catalog fields agreed for it (watchface-tools#49). */
  @Serializable private data class Catalog(val system: String, val title: String)

  @Serializable
  private data class Attribution(
    val repoUrl: String? = null,
    val sourceUrl: String? = null,
    val license: String,
  )

  @Serializable
  private data class Design(
    val id: String,
    val title: String,
    val previewUrl: String,
    val version: Long,
    val wffVersion: Int,
    val packageName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long? = null,
    val validationToken: String,
    val source: String? = null,
    val catalog: Catalog? = null,
    val attribution: Attribution? = null,
  )

  @Serializable private data class DesignList(val designs: List<Design>)

  private val json = Json { ignoreUnknownKeys = true }

  private fun designs() =
    json.decodeFromString<DesignList>(get("${WatchFacePush.BASE_PATH}/designs").body).designs

  @Test
  fun `listed catalogs' redistributable faces are public entries with attribution and their own package`() {
    val xml = "<WatchFace width=\"450\" height=\"450\"><Scene/></WatchFace>"
    start(
      listed =
        mapOf(
          "wear-os-samples-watchfaces" to
            catalog("wear-os-samples-watchfaces", Triple("simple_digital", "Apache-2.0", xml)),
          // No top-level licence: recorded as null, so never redistributed.
          "bonstio-casino-gshark" to catalog("bonstio-casino-gshark", Triple("gshark", null, xml)),
          "copyleft" to catalog("copyleft", Triple("gpl_face", "GPL-3.0-only", xml)),
        ),
      unlisted = mapOf("hidden" to catalog("hidden", Triple("secret_face", "MIT", xml))),
    )
    val listed = designs()
    assertEquals(listOf("catalog:wear-os-samples-watchfaces/simple_digital"), listed.map { it.id })
    val face = listed.single()
    assertEquals("catalog", face.source)
    assertEquals(
      Catalog("wear-os-samples-watchfaces", "wear-os-samples-watchfaces catalog"),
      face.catalog,
    )
    assertEquals(
      Attribution(
        "https://github.com/android/wear-os-samples",
        "https://github.com/android/wear-os-samples/blob/0123456/WatchFaceFormat/SimpleDigital",
        "Apache-2.0",
      ),
      face.attribution,
    )
    assertEquals(2, face.wffVersion)
    assertEquals(1, face.version)
    val faceName =
      WatchFacePushCatalogFaces.faceName("wear-os-samples-watchfaces", "simple_digital")
    assertEquals("ee.schimke.watchface.push.watchfacepush.$faceName", face.packageName)
    assertTrue(WatchFacePushNaming.followsRule(face.packageName))
    assertNotEquals("com.upstream.face", face.packageName)
    assertEquals("token-$faceName-1", face.validationToken)

    // The APK is the push build, its bytes the sha256 listed.
    val apk = get(pathOf(face.apkUrl))
    assertEquals(200, apk.code)
    assertEquals(face.sha256, sha256Hex(apk.bytes))
    assertEquals("catalog-apk:$faceName:1", apk.body)
    // Built from the bundle's WFF, with the server's own manifest and face info; no LICENSE file
    // or upstream manifest reaches the APK.
    val files = packed.getValue(faceName)
    assertEquals(xml, files.getValue("res/raw/watchface.xml").decodeToString())
    assertContentEquals(byteArrayOf(9, 9, 9), files.getValue("res/drawable/hand.png"))
    assertFalse(files.keys.any { it == "LICENSE" })
    assertFalse("com.upstream.face" in files.getValue("AndroidManifest.xml").decodeToString())
    assertFalse(
      "<WatchFaceInfo/>" == files.getValue("res/xml/watch_face_info.xml").decodeToString(),
    )

    assertEquals("png:wear-os-samples-watchfaces/simple_digital", get(pathOf(face.previewUrl)).body)

    // Skipped faces are 404 and say why on /status.
    val gshark =
      "${WatchFacePush.BASE_PATH}/catalog-faces/apk?system=bonstio-casino-gshark&preview=gshark"
    assertEquals(404, get(gshark).code)
    assertEquals(
      404,
      get("${WatchFacePush.BASE_PATH}/catalog-faces/apk?system=hidden&preview=secret_face").code,
    )
    val status = Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status").body).jsonObject
    val catalogFaces = status.getValue("catalogFaces").jsonObject
    assertEquals(1, catalogFaces.getValue("listed").jsonPrimitive.content.toInt())
    val skipped =
      catalogFaces.getValue("skipped").jsonArray.associate {
        it.jsonObject.getValue("id").jsonPrimitive.content to
          it.jsonObject.getValue("reason").jsonPrimitive.content
      }
    assertEquals(
      setOf("catalog:bonstio-casino-gshark/gshark", "catalog:copyleft/gpl_face"),
      skipped.keys,
    )
    assertContains(skipped.getValue("catalog:bonstio-casino-gshark/gshark"), "licence unknown")
    assertContains(skipped.getValue("catalog:copyleft/gpl_face"), "GPL-3.0-only")
  }

  @Test
  fun `a face the packager refuses is skipped on status, not counted as listed`() {
    val host =
      catalog(
        "wear",
        Triple("good", "MIT", "<WatchFace/>"),
        Triple("bad", "MIT", "<WatchFace REFUSE=\"1\"/>"),
      )
    start(mapOf("wear" to host))
    // The listing builds both; only the good one is listed.
    assertEquals(listOf("catalog:wear/good"), designs().map { it.id })
    val status = Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status").body).jsonObject
    val catalogFaces = status.getValue("catalogFaces").jsonObject
    assertEquals(1, catalogFaces.getValue("listed").jsonPrimitive.content.toInt())
    val skipped =
      catalogFaces.getValue("skipped").jsonArray.associate {
        it.jsonObject.getValue("id").jsonPrimitive.content to
          it.jsonObject.getValue("reason").jsonPrimitive.content
      }
    assertEquals(setOf("catalog:wear/bad"), skipped.keys)
    assertContains(skipped.getValue("catalog:wear/bad"), "the packager refused it")
    assertContains(skipped.getValue("catalog:wear/bad"), "refused for the test")
  }

  @Test
  fun `a catalog face's version moves when its source does`() {
    val host = catalog("wear", Triple("face", "MIT", "<WatchFace a=\"1\"/>"))
    start(mapOf("wear" to host))
    val first = designs().single()
    assertEquals(1, first.version)
    assertEquals(1, designs().single().version, "unchanged source keeps its version")
    val zip = dir.resolve("catalogs/wear/previews/face.wff.zip").toFile()
    zip.writeBytes(wffZip("<WatchFace a=\"2\"/>"))
    zip.setLastModified(zip.lastModified() + 5_000)
    val second = designs().single()
    assertEquals(2, second.version)
    assertEquals(404, get(pathOf(first.apkUrl)).code, "the old version's URL is gone")
    assertEquals(200, get(pathOf(second.apkUrl)).code)
  }

  @Test
  fun `only redistributable SPDX licences are accepted`() {
    for (ok in
      listOf(
        "Apache-2.0",
        "MIT",
        "BSD-3-Clause",
        "bsd-2-clause",
        "CC0-1.0",
        "Unlicense",
        "CC-BY-4.0",
        "GPL-3.0 OR MIT",
      )) {
      assertTrue(WatchFacePushLicenses.permits(ok), ok)
    }
    for (no in
      listOf(
        null,
        "",
        "BSD-4-Clause",
        "GPL-3.0-only",
        "MPL-2.0",
        "MIT AND GPL-3.0",
        "(MIT OR GPL-3.0)",
        "Apache-2.0 WITH LLVM-exception",
        "NOASSERTION",
      )) {
      assertFalse(WatchFacePushLicenses.permits(no), no.toString())
    }
  }

  @Test
  fun `catalog face names are hashed, valid, and never a design's`() {
    val name =
      WatchFacePushCatalogFaces.faceName("wear-os-samples-watchfaces", "dwf__raw/watchface")
    assertTrue(Regex("c_[A-Za-z0-9_]+_[0-9a-f]{10}").matches(name), name)
    assertNotEquals(
      name,
      WatchFacePushCatalogFaces.faceName("wear-os-samples-watchfaces", "dwf__raw_watchface"),
    )
    // A design literally named like it is hashed again, so it cannot take the catalog face's
    // package.
    assertNotEquals(name, WatchFacePushNaming.faceName(name))
  }

  @Test
  fun `a trusted catalog's live bundle keeps each face's WFF source beside its sidecar`() {
    val catalogJson =
      """
      {"schema":"design-parity-catalog/v1","system":"wear-faces",
       "liveBundle":{"path":"bundle/","file":"wear-faces-bundle.png"},
       "components":[{"componentId":"Faces/Digital","images":[
         {"path":"images/digital/ideal__default__dark.png","theme":"dark","previewId":"DigitalFace"}]}]}
      """
        .trimIndent()
    val bundleZip = ByteArrayOutputStream()
    ZipOutputStream(bundleZip).use { zip ->
      for ((name, bytes) in
        mapOf(
          "previews/DigitalFace.wff.json" to manifest("DigitalFace", "Apache-2.0").toByteArray(),
          "previews/DigitalFace.wff.zip" to wffZip("<WatchFace/>"),
          "previews/Unaliased.wff.zip" to wffZip("<WatchFace/>"),
        )) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    val fetch: (String) -> ByteArray? = { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
        url.endsWith("bundle/wear-faces-bundle.png") -> bundleZip.toByteArray()
        url.endsWith(".png") -> byteArrayOf(1)
        else -> null
      }
    }
    val trust =
      TrustStore(
        branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*")),
      )
    val root = dir.resolve("catalog-store").toFile()
    val registered = mutableMapOf<String, ServeHost>()
    val store =
      ServeCatalogStore(
        root = root,
        register = { n, h -> registered[n] = h },
        trust = { trust },
        fetch = fetch,
        buildTrustedBundle = { _, _, _, _, _, _ -> false },
      )
    assertIs<ServeCatalogStore.Result.Ok>(store.load("wear-faces"))
    val previews =
      Files.walk(root.toPath()).use { paths ->
        paths
          .filter { it.toString().contains("/previews/") }
          .map { it.fileName.toString() }
          .toList()
      }
    val catalogId = "digital__ideal__default__dark"
    assertTrue("$catalogId.wff.zip" in previews, previews.toString())
    assertTrue("$catalogId.wff.json" in previews, previews.toString())
    assertFalse(previews.any { it.startsWith("Unaliased") }, "only aliased previews are kept")
  }
}
