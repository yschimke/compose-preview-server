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
  private var push: WatchFacePush? = null

  /** What the fake packager was handed, per face name. */
  private val packed = java.util.concurrent.ConcurrentHashMap<String, Map<String, ByteArray>>()

  /** Every packager run, refused or not, in order; the background worker writes it. */
  private val packagerRuns = java.util.concurrent.CopyOnWriteArrayList<String>()

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
    start(registry, listed.keys.toList(), unlisted.keys.toList())
  }

  private fun start(
    registry: ServeSessionRegistry,
    listed: List<String>,
    unlisted: List<String> = emptyList(),
    catalogBakedBundle: (String) -> ServeCatalogStore.BakedBundle? = { null },
    uiBuilderHost: String? = null,
  ) {
    val packager = WatchFacePushPackager { files, faceName, versionCode, out ->
      packagerRuns += faceName
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
    this.push = push
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "local-fixture",
          sessions = registry,
          defaultSessionId = "unused",
          catalogSessions = listed,
          appCatalogSessions = unlisted,
          catalogBakedBundle = catalogBakedBundle,
          uiBuilderHost = uiBuilderHost,
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

  private fun get(path: String, host: String? = null): Reply =
    client
      .newCall(
        Request.Builder()
          .url("http://127.0.0.1:${server!!.port}$path")
          .also { builder -> host?.let { builder.header("Host", it) } }
          .build(),
      )
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

  /**
   * `/designs` once the background worker has caught up: a listing never builds, so the test runs
   * the reconcile pass the server runs on a timer, and waits for its builds.
   */
  private fun designs(): List<Design> {
    built()
    return listing()
  }

  /** `/designs` exactly as it is now, without letting the background worker run first. */
  private fun listing() =
    json.decodeFromString<DesignList>(get("${WatchFacePush.BASE_PATH}/designs").body).designs

  private fun built() {
    val push = push!!
    push.reconcileNow()
    assertTrue(push.builds.awaitIdle(), "the background builds finish")
  }

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
    // The background worker builds both; only the good one is listed.
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

  /** A trusted `wear-faces`-shaped catalog whose live bundle carries one face's WFF source. */
  private fun liveBundleCatalogFetch(
    system: String,
    xml: String = "<WatchFace/>",
    /** Also declare a live-only (deferred) face, with WFF sidecars but no baked PNG. */
    deferredFace: Boolean = false,
  ): (String) -> ByteArray? {
    val deferred =
      if (!deferredFace) ""
      else
        """,
       "deferred":[{"componentId":"Faces/Digital","reason":"mode",
         "path":"images/digital/ideal__default__light.png","theme":"light",
         "preview":"DigitalFace","previewId":"DigitalFaceLight"}]"""
    val catalogJson =
      """
      {"schema":"design-parity-catalog/v1","system":"$system",
       "liveBundle":{"path":"bundle/","file":"$system-bundle.png"},
       "components":[{"componentId":"Faces/Digital","images":[
         {"path":"images/digital/ideal__default__dark.png","theme":"dark","previewId":"DigitalFace"}]}]$deferred}
      """
        .trimIndent()
    val bundleZip = ByteArrayOutputStream()
    ZipOutputStream(bundleZip).use { zip ->
      for ((name, bytes) in
        mapOf(
          "previews/DigitalFace.wff.json" to manifest("DigitalFace", "Apache-2.0").toByteArray(),
          "previews/DigitalFace.wff.zip" to wffZip(xml),
          "previews/DigitalFaceLight.wff.json" to
            manifest("DigitalFaceLight", "Apache-2.0").toByteArray(),
          "previews/DigitalFaceLight.wff.zip" to wffZip(xml),
          "previews/Unaliased.wff.zip" to wffZip(xml),
        )) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
      }
    }
    return { url ->
      when {
        url.endsWith("/${ServeCatalogStore.CATALOG_FILE}") -> catalogJson.toByteArray()
        url.endsWith("bundle/$system-bundle.png") -> bundleZip.toByteArray()
        url.endsWith(".png") -> byteArrayOf(1)
        else -> null
      }
    }
  }

  private val trust =
    TrustStore(branches = listOf(TrustedBranch("yschimke/compose-ai-tools", "design-artifacts/*")))

  /**
   * A catalog store over [fetch] (read per call, so a test can republish) that registers via
   * [register].
   */
  private fun store(
    fetch: () -> (String) -> ByteArray?,
    register: (String, ServeBundleHost) -> Unit = { _, _ -> },
    buildTrustedBundle: () -> Boolean = { false },
  ) =
    ServeCatalogStore(
      root = dir.resolve("catalog-store").toFile(),
      register = register,
      trust = { trust },
      fetch = { url -> fetch()(url) },
      buildTrustedBundle = { _, _, _, _, _, _ -> buildTrustedBundle() },
    )

  private val storeFaceId = "catalog:wear-faces/digital__ideal__default__dark"

  private fun catalogFacesStatus(): Pair<Int, Set<String>> {
    val status = Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status").body).jsonObject
    val catalogFaces = status.getValue("catalogFaces").jsonObject
    return catalogFaces.getValue("listed").jsonPrimitive.content.toInt() to
      catalogFaces
        .getValue("skipped")
        .jsonArray
        .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        .toSet()
  }

  @Test
  fun `a listed catalog's faces are read from its bundle on disk, not its session`() {
    var fetch = liveBundleCatalogFetch("wear-faces", "<WatchFace a=\"1\"/>")
    val store = store({ fetch })
    assertIs<ServeCatalogStore.Result.Ok>(store.load("wear-faces"))
    // Never registered: no session is resident, so a residency-bound read finds nothing.
    val registry = ServeSessionRegistry(open = { null }).also(registries::add)
    start(registry, listed = listOf("wear-faces"), catalogBakedBundle = store::bakedBundle)

    val face = designs().single()
    assertEquals(storeFaceId, face.id)
    assertEquals(1, face.version)
    assertEquals(1 to emptySet(), catalogFacesStatus(), "/status counts what /designs lists")
    assertEquals(200, get(pathOf(face.apkUrl)).code)
    assertEquals(200, get(pathOf(face.previewUrl)).code)
    assertNull(registry.peekHost("wear-faces"), "listing faces never stands a session up")

    // A refresh publishes a new generation; the list follows it.
    fetch = liveBundleCatalogFetch("wear-faces", "<WatchFace a=\"2\"/>")
    assertIs<ServeCatalogStore.Result.Ok>(store.load("wear-faces"))
    assertEquals(2, designs().single().version)
  }

  @Test
  fun `a live catalog's deferred faces stay listed from its bundle on disk`() {
    val fetch = liveBundleCatalogFetch("wear-faces", deferredFace = true)
    // The live lane stands up, so the catalog serves its live-only previews too.
    val store = store({ fetch }, buildTrustedBundle = { true })
    assertIs<ServeCatalogStore.Result.Ok>(store.load("wear-faces"))
    val registry = ServeSessionRegistry(open = { null }).also(registries::add)
    start(registry, listed = listOf("wear-faces"), catalogBakedBundle = store::bakedBundle)
    assertEquals(
      setOf(storeFaceId, "catalog:wear-faces/digital__ideal__default__light"),
      designs().map { it.id }.toSet(),
    )
  }

  @Test
  fun `a suspended catalog keeps its faces`() {
    var now = 0L
    val registry =
      ServeSessionRegistry(
          open = { null },
          idleTimeoutMillis = 10,
          reaperIntervalMillis = 0,
          clock = { now },
        )
        .also(registries::add)
    // Registered unpinned, the way a live catalog's session can be suspended.
    val fetch = liveBundleCatalogFetch("wear-faces")
    val store = store({ fetch }, register = { id, host -> registry.register(id, host = host) })
    assertIs<ServeCatalogStore.Result.Ok>(store.load("wear-faces"))
    start(registry, listed = listOf("wear-faces"), catalogBakedBundle = store::bakedBundle)
    assertNotNull(registry.peekHost("wear-faces"))
    assertEquals(listOf(storeFaceId), designs().map { it.id })

    now = 100
    assertEquals(1, registry.suspendIdle(), "the catalog session suspends")
    assertNull(registry.peekHost("wear-faces"))

    assertEquals(listOf(storeFaceId), designs().map { it.id })
    assertEquals(1 to emptySet(), catalogFacesStatus())
    assertNull(registry.peekHost("wear-faces"), "listing faces must not resume the session")
  }

  @Test
  fun `a catalog still loading at startup lists its faces once its bundle is on disk`() {
    val reachedLiveLane = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val fetch = liveBundleCatalogFetch("wear-faces")
    val store =
      store(
        { fetch },
        // The daemon stand-up is the slow part of a startup load; hold it there.
        buildTrustedBundle = {
          reachedLiveLane.countDown()
          release.await(30, TimeUnit.SECONDS)
          false
        },
      )
    val registry = ServeSessionRegistry(open = { null }).also(registries::add)
    start(registry, listed = listOf("wear-faces"), catalogBakedBundle = store::bakedBundle)
    assertEquals(emptyList(), designs(), "nothing is on disk before the load starts")

    var result: ServeCatalogStore.Result? = null
    val loader = Thread { result = store.load("wear-faces") }.also { it.start() }
    try {
      assertTrue(reachedLiveLane.await(30, TimeUnit.SECONDS), "the load reached its live lane")
      assertNull(store.liveDir("wear-faces"), "nothing is published yet")
      assertEquals(listOf(storeFaceId), designs().map { it.id })
      assertEquals(1 to emptySet(), catalogFacesStatus())
    } finally {
      release.countDown()
      loader.join(30_000)
    }
    assertIs<ServeCatalogStore.Result.Ok>(result)
    assertEquals(listOf(storeFaceId), designs().map { it.id })
  }

  @Test
  fun `an unlisted catalog's faces are never listed, even from its bundle on disk`() {
    val fetch = liveBundleCatalogFetch("wear-faces")
    val store = store({ fetch })
    assertIs<ServeCatalogStore.Result.Ok>(store.load("wear-faces"))
    assertNotNull(store.bakedBundle("wear-faces"))
    val registry = ServeSessionRegistry(open = { null }).also(registries::add)
    start(
      registry,
      listed = emptyList(),
      unlisted = listOf("wear-faces"),
      catalogBakedBundle = store::bakedBundle,
    )
    assertEquals(emptyList(), designs())
    assertEquals(0 to emptySet(), catalogFacesStatus())
    assertEquals(
      404,
      get(
          "${WatchFacePush.BASE_PATH}/catalog-faces/apk?system=wear-faces" +
            "&preview=digital__ideal__default__dark",
        )
        .code,
    )
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

  @Test
  fun `a listing never packages, and a face is pending on status until the background worker builds it`() {
    start(mapOf("wear" to catalog("wear", Triple("face", "MIT", "<WatchFace/>"))))
    // However often the watch asks, nothing is packaged on the request path.
    repeat(3) { assertEquals(emptyList(), listing()) }
    val status = Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status").body).jsonObject
    val catalogFaces = status.getValue("catalogFaces").jsonObject
    assertEquals(0, catalogFaces.getValue("listed").jsonPrimitive.content.toInt())
    assertEquals(
      listOf("catalog:wear/face"),
      catalogFaces.getValue("pending").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(emptyList(), packagerRuns, "neither /designs nor /status packaged anything")

    built()
    assertEquals(listOf("catalog:wear/face"), listing().map { it.id })
    assertEquals(1, packagerRuns.size)
    // Once built, a reconcile pass and further listings leave it alone.
    built()
    listing()
    assertEquals(1, packagerRuns.size)
  }

  @Test
  fun `a refused face is remembered across a restart and never packaged again`() {
    val faces =
      mapOf(
        "wear" to
          catalog(
            "wear",
            Triple("good", "MIT", "<WatchFace/>"),
            Triple("bad", "MIT", "<WatchFace REFUSE=\"1\"/>"),
          ),
      )
    start(faces)
    assertEquals(listOf("catalog:wear/good"), designs().map { it.id })
    val badName = WatchFacePushCatalogFaces.faceName("wear", "bad")
    assertEquals(1, packagerRuns.count { it == badName })

    // A restart over the same push state: a new push lane, a new server.
    server?.stop()
    start(faces)
    assertEquals(listOf("catalog:wear/good"), designs().map { it.id })
    assertEquals(1, packagerRuns.count { it == badName }, "the refusal was read back from disk")
    val status = Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status").body).jsonObject
    val skipped =
      status.getValue("catalogFaces").jsonObject.getValue("skipped").jsonArray.map {
        it.jsonObject.getValue("id").jsonPrimitive.content
      }
    assertEquals(listOf("catalog:wear/bad"), skipped)
  }

  @Test
  fun `a transient packaging failure backs off instead of re-running on every request`() {
    var now = 1_000L
    val runs = java.util.concurrent.atomic.AtomicInteger()
    var fail = true
    val packager = WatchFacePushPackager { _, faceName, versionCode, out ->
      runs.incrementAndGet()
      if (fail) throw java.io.IOException("timed out")
      out.writeBytes("apk:$faceName:$versionCode".toByteArray())
      "token"
    }
    val store = WatchFacePushStore(dir.resolve("backoff"))
    val builds =
      WatchFacePushBuilds(
        store,
        { WatchFacePushPackaging.Ready(packager, "c0ffee") },
        clock = { now },
        transientBackoffMillis = 1_000L,
        maxTransientBackoffMillis = 4_000L,
        log = {},
      )
    val publication =
      store.candidate(
        "face",
        "github:alice",
        1,
        "Face",
        DwfSource("<WatchFace/>", 2, emptyMap()),
        0L,
      )

    assertIs<WatchFacePushBuilds.Result.Failed>(builds.build(publication))
    assertIs<WatchFacePushBuilds.Result.Failed>(builds.build(publication))
    assertFalse(builds.enqueue(publication, cause = "test"), "nothing is queued during a backoff")
    assertEquals(1, runs.get(), "a retry inside the backoff does not run the packager")
    assertNull(builds.refusal(publication), "a timeout is not a refusal")

    now += 1_000L
    assertIs<WatchFacePushBuilds.Result.Failed>(builds.build(publication))
    assertEquals(2, runs.get())
    now += 1_000L
    assertIs<WatchFacePushBuilds.Result.Failed>(builds.build(publication))
    assertEquals(2, runs.get(), "the backoff doubled")

    // A deliberate caller (an owner's publish) may retry at once.
    fail = false
    assertIs<WatchFacePushBuilds.Result.Built>(builds.build(publication, retryTransient = true))
    assertEquals(3, runs.get())
  }

  @Test
  fun `a packager exit with no diagnostics of its own is backed off, never stored as a refusal`() {
    var now = 1_000L
    val runs = java.util.concurrent.atomic.AtomicInteger()
    val packager = WatchFacePushPackager { _, _, _, _ ->
      runs.incrementAndGet()
      // What `diagnostics()` hands back when a packager JVM is killed: its last line, no `error[`.
      throw DwfPackagingFailed(listOf("Killed"))
    }
    val store = WatchFacePushStore(dir.resolve("killed"))
    val builds =
      WatchFacePushBuilds(
        store,
        { WatchFacePushPackaging.Ready(packager, "c0ffee") },
        clock = { now },
        transientBackoffMillis = 1_000L,
        log = {},
      )
    val publication =
      store.candidate(
        "face",
        "github:alice",
        1,
        "Face",
        DwfSource("<WatchFace/>", 2, emptyMap()),
        0L,
      )
    assertIs<WatchFacePushBuilds.Result.Failed>(builds.build(publication))
    assertNull(builds.refusal(publication))
    assertNull(store.refusal(publication), "nothing was written to disk")
    assertIs<WatchFacePushBuilds.Result.Failed>(builds.build(publication))
    assertEquals(1, runs.get(), "backed off")
    now += 1_000L
    builds.build(publication)
    assertEquals(2, runs.get(), "retried once the backoff passed")
    assertTrue(WatchFacePushBuilds.isSourceRefusal(listOf("error[validator]: bad face")))
    assertFalse(WatchFacePushBuilds.isSourceRefusal(listOf("warning[x]: only a warning")))
  }

  @Test
  fun `closing lets the running build finish uninterrupted and drops what is queued`() {
    val started = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
    val runs = java.util.concurrent.CopyOnWriteArrayList<String>()
    val packager = WatchFacePushPackager { _, faceName, versionCode, out ->
      runs += faceName
      started.countDown()
      try {
        release.await(30, TimeUnit.SECONDS)
      } catch (e: InterruptedException) {
        interrupted.set(true)
        throw e
      }
      out.writeBytes("apk:$faceName:$versionCode".toByteArray())
      "token"
    }
    val store = WatchFacePushStore(dir.resolve("closing"))
    val builds =
      WatchFacePushBuilds(store, { WatchFacePushPackaging.Ready(packager, "c0ffee") }, log = {})
    val source = DwfSource("<WatchFace/>", 2, emptyMap())
    val first = store.candidate("first", "github:alice", 1, "First", source, 0L)
    val second = store.candidate("second", "github:alice", 1, "Second", source, 0L)
    assertTrue(builds.enqueue(first, { source }, cause = "test"))
    assertTrue(started.await(30, TimeUnit.SECONDS))
    assertTrue(builds.enqueue(second, { source }, cause = "test"))

    builds.close()
    release.countDown()
    assertTrue(builds.awaitIdle(30_000))
    // The running build was not interrupted and completed; the queued one never started.
    assertFalse(interrupted.get())
    assertNotNull(builds.cached(first))
    assertEquals(listOf(first.faceName), runs)
    assertNull(builds.cached(second))
  }

  @Test
  fun `with a builder host, catalog faces are listed only on the other sites`() {
    val host = catalog("wear", Triple("face", "MIT", "<WatchFace/>"))
    val registry = ServeSessionRegistry(open = { null }).also(registries::add)
    registry.register("wear", host = host, pinned = true)
    start(registry, listOf("wear"), uiBuilderHost = "ui.example")
    built()
    fun designsOn(site: String) =
      json
        .decodeFromString<DesignList>(get("${WatchFacePush.BASE_PATH}/designs", site).body)
        .designs
    val apk = "${WatchFacePush.BASE_PATH}/catalog-faces/apk?system=wear&preview=face"

    // The catalog site lists, counts and serves its faces.
    assertEquals(listOf("catalog:wear/face"), designsOn("preview.example").map { it.id })
    assertEquals(200, get(apk, "preview.example").code)
    val status =
      Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status", "preview.example").body)
    assertEquals(
      1,
      status.jsonObject
        .getValue("catalogFaces")
        .jsonObject
        .getValue("listed")
        .jsonPrimitive
        .content
        .toInt(),
    )

    // The builder's own host lists only its designs: no catalog face, no count, no APK.
    assertEquals(emptyList(), designsOn("ui.example"))
    assertEquals(404, get(apk, "ui.example").code)
    val builderStatus =
      Json.parseToJsonElement(get("${WatchFacePush.BASE_PATH}/status", "ui.example").body)
    assertNull(
      builderStatus.jsonObject["catalogFaces"]?.takeUnless {
        it is kotlinx.serialization.json.JsonNull
      },
    )
    assertEquals(
      404,
      get(
          "${WatchFacePush.BASE_PATH}/catalog-faces/preview.png?system=wear&preview=face",
          "ui.example",
        )
        .code,
    )
  }
}
