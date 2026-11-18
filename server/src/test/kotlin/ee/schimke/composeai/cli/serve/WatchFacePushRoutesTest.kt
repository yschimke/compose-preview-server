package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.jupiter.api.io.TempDir

/**
 * The Watch Face Push site API end to end over HTTP, spoken with the push app's own models ([App]):
 * linking, the device token and its revocation, which designs a watch is shown, and the APK
 * download through the real subprocess packager driving a fake `java`.
 */
class WatchFacePushRoutesTest {
  @TempDir lateinit var dir: Path
  private val client =
    OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()
  private val registries = mutableListOf<ServeSessionRegistry>()
  private var server: ServeHttpServer? = null
  private lateinit var service: PersistentUiBuilderService
  private lateinit var push: WatchFacePush
  private var now = 1_700_000_000_000L
  private var watchCatalogs = setOf("m3-catalog")

  /** What each design exports, by id; a test changes it to stand in for an edit. */
  private val faces = mutableMapOf<String, String>()

  private val alice = "github:alice"
  private val bob = "github:bob"

  @AfterTest
  fun tearDown() {
    server?.stop()
    registries.forEach { it.close() }
  }

  // ------------------------------------------------------------------ fixture

  private val packagerLog: File
    get() = dir.resolve("packager.log").toFile()

  /** A `java` that is wff-packager's CLI as far as this server can tell. */
  private fun fakeJava(): File =
    dir.resolve("fake-java.sh").toFile().apply {
      writeText(
        "#!/bin/sh\n" +
          "out=''; vc=''; face=''; tok=''; market=''; fromenv=''\n" +
          "while [ \"\$#\" -gt 0 ]; do\n" +
          "  case \"\$1\" in\n" +
          "    --out) out=\"\$2\"; shift;;\n" +
          "    --version-code) vc=\"\$2\"; shift;;\n" +
          "    --face-name) face=\"\$2\"; shift;;\n" +
          "    --token-out) tok=\"\$2\"; shift;;\n" +
          "    --push-marketplace) market=\"\$2\"; shift;;\n" +
          "    --keystore-from-env) fromenv=\"\$2\"; shift;;\n" +
          "    --bundle) bundle=\"\$2\"; shift;;\n" +
          "  esac\n" +
          "  shift\n" +
          "done\n" +
          "if grep -q bad \"\$bundle/res/raw/watchface.xml\"; then\n" +
          "  echo 'error[wff-validator]: bad face' >&2; exit 1\n" +
          "fi\n" +
          "echo \"\$market \$face \$vc \$fromenv \${WFF_SIGNING_KEYSTORE_PASSWORD:-none} " +
          "\${WFF_SIGNING_CERT_SHA256:-none}\" >> ${packagerLog.path}\n" +
          "printf 'fake-apk:%s:%s' \"\$face\" \"\$vc\" > \"\$out\"\n" +
          "printf 'token-%s-%s' \"\$face\" \"\$vc\" > \"\$tok\"\n",
      )
      setExecutable(true)
    }

  private fun readyPackaging(): () -> WatchFacePushPackaging {
    val signing =
      WatchFacePushSigning.Configured(
        environment =
          mapOf(
            WatchFacePushSigning.KEYSTORE_B64 to "AAAA",
            WatchFacePushSigning.KEYSTORE_PASSWORD to "market-pw",
            WatchFacePushSigning.CERT_SHA256 to "c0ffee",
          ),
        certificateSha256 = "c0ffee",
      )
    val packager =
      WatchFacePushSubprocessPackager(
        libDir = dir.resolve("lib-wff-packager").toFile().also { it.mkdirs() },
        sdk = DwfAndroidSdk(dir.toFile()),
        home = dir.resolve("packager-home").toFile(),
        signing = signing,
        java = fakeJava().path,
      )
    return { WatchFacePushPackaging.Ready(packager, "c0ffee") }
  }

  private fun start(packaging: () -> WatchFacePushPackaging = readyPackaging()): ServeHttpServer {
    service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(dir.resolve("state")),
        limits = UiBuilderServiceLimits(),
        catalogs = benchmarkCatalogExecutor(),
        exporter = UiBuilderExportExecutor { error("no export in this test") },
      )
    push =
      WatchFacePush(
        store = WatchFacePushStore(dir.resolve("watchface-push"), clock = { now }),
        packaging = packaging,
        sourceFor = { document ->
          DwfSource(
            wffXml = faces[document.id] ?: "<WatchFace id=\"${document.id}\"/>",
            wffVersion = 4,
            label = document.id,
          )
        },
        isWatchFaceCatalog = { it in watchCatalogs },
        preview = { _, designId, _ -> "png:$designId".toByteArray() },
        signInPath = { "/auth/github/start?return=$it" },
        clock = { now },
      )
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "local-fixture",
        sessions = ServeSessionRegistry(open = { null }).also(registries::add),
        defaultSessionId = "unused",
        machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
        uiBuilderService = service,
        watchFacePush = push,
        // A signed-in person is named by a test header; without it, nobody is.
        uiBuilderAuthorization =
          ServeUiBuilderAuthorization { call, _, _ ->
            call.request.headers["X-Test-Agent-For"]?.let {
              UiBuilderAuthorizationDecision.Authorized("agent:fingerprint", onBehalfOfActorId = it)
            }
              ?: call.request.headers["X-Test-Actor"]?.let {
                UiBuilderAuthorizationDecision.Authorized(it)
              }
              ?: UiBuilderAuthorizationDecision.Missing
          },
      )
      .also {
        it.start()
        server = it
      }
  }

  private fun createDesign(
    owner: String,
    id: String,
    title: String = id,
    public: Boolean = false,
  ) = runBlocking {
    val created =
      service.execute(
        UiBuilderServiceCall(
          AuthenticatedUiBuilderActor(owner),
          UiBuilderServiceRequest.CreateDesign(document(id, title)),
        ),
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(created, "create $id: $created")
    if (public) {
      val shared =
        service.setDesignVisibility(
          AuthenticatedUiBuilderActor(owner),
          id,
          UiBuilderDefaultVisibility.PUBLIC,
        )
      assertIs<UiBuilderServiceResponse.DesignAccess>(shared, "make $id public: $shared")
    }
  }

  private data class Reply(
    val code: Int,
    val body: String,
    val headers: okhttp3.Headers,
    val bytes: ByteArray,
  )

  private fun send(
    method: String,
    path: String,
    json: String? = null,
    actor: String? = null,
    bearer: String? = null,
    form: Map<String, String>? = null,
    headers: Map<String, String> = emptyMap(),
  ): Reply {
    val body =
      when {
        form != null -> FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        json != null -> json.toRequestBody("application/json".toMediaType())
        method == "POST" || method == "PUT" -> "".toRequestBody("application/json".toMediaType())
        else -> null
      }
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .method(method, body)
        .apply {
          actor?.let { header("X-Test-Actor", it) }
          bearer?.let { header("Authorization", "Bearer $it") }
          headers.forEach { (k, v) -> header(k, v) }
        }
        .build()
    return client.newCall(request).execute().use {
      val bytes = it.body.bytes()
      Reply(it.code, bytes.decodeToString(), it.headers, bytes)
    }
  }

  private val api = WatchFacePush.BASE_PATH

  private fun publish(actor: String, designId: String): Reply =
    send("PUT", "/api/ui-builder/v1/designs/$designId/watch-push", actor = actor)

  /** Link a watch to [account] the way the app and a person do; returns the device token. */
  private fun link(account: String, deviceName: String = "Pixel Watch 4"): String {
    val issued =
      send(
        "POST",
        "$api/device-codes",
        App.json.encodeToString(App.DeviceCodeRequest(deviceName, 36)),
      )
    assertEquals(200, issued.code, issued.body)
    val code = App.json.decodeFromString<App.DeviceCode>(issued.body)
    val page = send("GET", "/link?code=${code.code}", actor = account)
    val seal =
      Regex("""name="action" value="approve">\s*<input type="hidden" name="seal" value="([^"]+)"""")
        .find(page.body)!!
        .groupValues[1]
    val approved =
      send(
        "POST",
        "/link",
        actor = account,
        form = mapOf("subject" to code.code, "action" to "approve", "seal" to seal),
      )
    assertEquals(200, approved.code, approved.body)
    val poll =
      send(
        "POST",
        "$api/device-codes/${code.code}/poll",
        App.json.encodeToString(App.PollRequest(code.pollSecret)),
      )
    val response = App.json.decodeFromString<App.PollResponse>(poll.body)
    assertEquals("approved", response.status, poll.body)
    return response.deviceToken!!
  }

  private fun designs(token: String? = null): List<App.Design> {
    val reply = send("GET", "$api/designs", bearer = token)
    assertEquals(200, reply.code, reply.body)
    return App.json.decodeFromString<App.DesignList>(reply.body).designs
  }

  /** The path of an absolute URL the server handed out. */
  private fun pathOf(url: String) = url.substringAfter("://").let { "/" + it.substringAfter('/') }

  // -------------------------------------------------------------------- tests

  @Test
  fun `the whole linking flow, from code to token, with the app's own request and response shapes`() {
    start()
    val issued =
      send(
        "POST",
        "$api/device-codes",
        App.json.encodeToString(App.DeviceCodeRequest("Pixel Watch 4", 36)),
      )
    assertEquals(200, issued.code, issued.body)
    assertEquals("no-store", issued.headers["Cache-Control"])
    val code = App.json.decodeFromString<App.DeviceCode>(issued.body)
    assertTrue(Regex("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}").matches(code.code), code.code)
    assertTrue(code.code.none { it in "01OI" })
    assertEquals(600, code.expiresInSeconds)
    assertEquals(5, code.pollIntervalSeconds)
    assertTrue(code.verificationUrl.endsWith("/link?code=${code.code}"), code.verificationUrl)
    // ≥ 128 bits of randomness, URL-safe base64.
    assertTrue(code.pollSecret.length >= 22, code.pollSecret)

    fun poll(secret: String = code.pollSecret, at: String = code.code) =
      send("POST", "$api/device-codes/$at/poll", App.json.encodeToString(App.PollRequest(secret)))

    assertEquals("pending", App.json.decodeFromString<App.PollResponse>(poll().body).status)
    // Too soon after the last poll: 429 with Retry-After, and the client keeps going.
    val early = poll()
    assertEquals(429, early.code)
    assertEquals("5", early.headers["Retry-After"])
    now += 5_000
    // The wrong secret is indistinguishable from no such code.
    assertEquals(404, poll(secret = "not-the-secret").code)
    assertEquals(404, poll(at = "ZZZZ-ZZZZ").code)

    // Signed out: the page asks for a sign-in that comes back here.
    val signedOut = send("GET", "/link?code=${code.code}")
    assertEquals(200, signedOut.code)
    assertContains(signedOut.body, "/auth/github/start?return=/link?code=${code.code}")

    val page = send("GET", "/link?code=${code.code.lowercase().replace("-", "")}", actor = alice)
    assertContains(page.body, "Link Pixel Watch 4?")
    assertContains(page.body, code.code)
    val seal =
      Regex("""name="action" value="approve">\s*<input type="hidden" name="seal" value="([^"]+)"""")
        .find(page.body)!!
        .groupValues[1]
    // A seal is for one person and one action.
    val forged =
      send(
        "POST",
        "/link",
        actor = bob,
        form = mapOf("subject" to code.code, "action" to "approve", "seal" to seal),
      )
    assertEquals(403, forged.code)
    assertEquals("pending", App.json.decodeFromString<App.PollResponse>(poll().body).status)
    now += 5_000

    val approved =
      send(
        "POST",
        "/link",
        actor = alice,
        form = mapOf("subject" to code.code, "action" to "approve", "seal" to seal),
      )
    assertEquals(200, approved.code)
    assertContains(approved.body, "Linked")

    val delivered = App.json.decodeFromString<App.PollResponse>(poll().body)
    assertEquals("approved", delivered.status)
    assertEquals("alice", delivered.accountName)
    val token = delivered.deviceToken!!
    assertTrue(token.startsWith(WatchFacePushStore.TOKEN_PREFIX))
    // Returned once: the code is consumed.
    now += 5_000
    assertEquals(404, poll().code)
    // Only a hash is kept.
    val stored = dir.resolve("watchface-push/devices.json").toFile().readText()
    assertFalse(token in stored)
    assertContains(stored, WatchFacePushStore.tokenHash(token))

    // The device list and the page show it.
    val devices = send("GET", "$api/devices", actor = alice)
    assertContains(devices.body, "Pixel Watch 4")
    assertEquals("""{"devices":[]}""", send("GET", "$api/devices", actor = bob).body)
    assertContains(send("GET", "/link", actor = alice).body, "Pixel Watch 4")
  }

  @Test
  fun `a denied code says so once, and an expired one says expired and then is gone`() {
    start()
    val first =
      App.json.decodeFromString<App.DeviceCode>(send("POST", "$api/device-codes", "{}").body)
    val page = send("GET", "/link?code=${first.code}", actor = alice)
    val denySeal =
      Regex("""name="action" value="deny">\s*<input type="hidden" name="seal" value="([^"]+)"""")
        .find(page.body)!!
        .groupValues[1]
    assertContains(page.body, "Link Wear OS watch?")
    send(
      "POST",
      "/link",
      actor = alice,
      form = mapOf("subject" to first.code, "action" to "deny", "seal" to denySeal),
    )
    val poll = { c: App.DeviceCode ->
      send(
        "POST",
        "$api/device-codes/${c.code}/poll",
        App.json.encodeToString(App.PollRequest(c.pollSecret)),
      )
    }
    assertEquals("denied", App.json.decodeFromString<App.PollResponse>(poll(first).body).status)
    now += 5_000
    assertEquals(404, poll(first).code)

    val second =
      App.json.decodeFromString<App.DeviceCode>(send("POST", "$api/device-codes", "{}").body)
    now += 601_000
    assertEquals("expired", App.json.decodeFromString<App.PollResponse>(poll(second).body).status)
    assertEquals(404, poll(second).code)
    assertContains(send("GET", "/link?code=${second.code}", actor = alice).body, "expired")
  }

  @Test
  fun `starting a link is rate limited per address with Retry-After`() {
    start()
    repeat(10) { assertEquals(200, send("POST", "$api/device-codes", "{}").code) }
    val limited = send("POST", "$api/device-codes", "{}")
    assertEquals(429, limited.code)
    assertNotNull(limited.headers["Retry-After"])
  }

  @Test
  fun `a device token reads its account's designs, and a revoked one is a 401`() {
    start()
    createDesign(alice, "aurora", "Aurora")
    assertEquals(200, publish(alice, "aurora").code)
    val token = link(alice)
    assertEquals(listOf("aurora"), designs(token).map { it.id })

    // Any Authorization that is not a live token is 401, so the watch unlinks.
    assertEquals(401, send("GET", "$api/designs", bearer = "wfp_not-a-token").code)
    assertEquals(
      401,
      send("GET", "$api/designs", headers = mapOf("Authorization" to "Basic eA==")).code,
    )
    assertEquals(
      401,
      send("POST", "$api/installs", """{"designId":"aurora","version":1,"result":"installed"}""")
        .code,
    )
    assertEquals(
      204,
      send(
          "POST",
          "$api/installs",
          App.json.encodeToString(App.InstallEvent("aurora", 1, "installed", wearOsApi = 36)),
          bearer = token,
        )
        .code,
    )

    val deviceId =
      Json.parseToJsonElement(send("GET", "$api/devices", actor = alice).body)
        .jsonObject["devices"]!!
        .let {
          (it as kotlinx.serialization.json.JsonArray)
            .single()
            .jsonObject["id"]!!
            .jsonPrimitive
            .content
        }
    // Somebody else cannot unlink it.
    assertEquals(404, send("DELETE", "$api/devices/$deviceId", actor = bob).code)
    assertEquals(204, send("DELETE", "$api/devices/$deviceId", actor = alice).code)
    val revoked = send("GET", "$api/designs", bearer = token)
    assertEquals(401, revoked.code)
    assertEquals("Bearer", revoked.headers["WWW-Authenticate"])
    assertEquals(401, send("GET", "$api/designs/aurora/apk", bearer = token).code)
  }

  @Test
  fun `unlinking from the page revokes the token`() {
    start()
    val token = link(alice)
    val page = send("GET", "/link", actor = alice)
    val match =
      Regex(
          """name="subject" value="([^"]+)">\s*<input type="hidden" name="action" value="revoke">\s*<input type="hidden" name="seal" value="([^"]+)"""",
        )
        .find(page.body)!!
    send(
      "POST",
      "/link",
      actor = alice,
      form =
        mapOf(
          "subject" to match.groupValues[1],
          "action" to "revoke",
          "seal" to match.groupValues[2],
        ),
    )
    assertEquals(401, send("GET", "$api/designs", bearer = token).code)
  }

  @Test
  fun `the list shows public faces to anyone and private ones only to their owner's watch, never drafts`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "secret", "Secret")
    createDesign(alice, "draft", "Draft", public = true)
    createDesign(bob, "pulse", "Pulse", public = true)
    now += 1_000
    assertEquals(200, publish(alice, "secret").code)
    now += 1_000
    assertEquals(200, publish(bob, "pulse").code)
    now += 1_000
    assertEquals(200, publish(alice, "aurora").code)

    // Anonymous: every public published face, across accounts, newest first. No drafts, no
    // private ones.
    val anonymous = designs()
    assertEquals(listOf("aurora", "pulse"), anonymous.map { it.id })
    // Alice's watch: her private one too.
    val aliceToken = link(alice)
    assertEquals(listOf("aurora", "pulse", "secret"), designs(aliceToken).map { it.id })
    // Bob's watch: public faces and his own.
    assertEquals(listOf("aurora", "pulse"), designs(link(bob, "Bob's watch")).map { it.id })

    val secret = designs(aliceToken).single { it.id == "secret" }
    assertEquals("Secret", secret.title)
    assertEquals(4, secret.wffVersion)
    assertEquals(1, secret.version)
    assertEquals("ee.schimke.watchface.push.watchfacepush.secret", secret.packageName)
    assertTrue(secret.packageName.startsWith(App.PACKAGE_PREFIX))
    assertEquals("token-secret-1", secret.validationToken)
    assertEquals(sha256Hex("fake-apk:secret:1".toByteArray()), secret.sha256)
    assertEquals("fake-apk:secret:1".length.toLong(), secret.sizeBytes)
    assertTrue(secret.apkUrl.startsWith("http://127.0.0.1:${server!!.port}$api/designs/secret/apk"))

    // The preview needs no header, only its signed URL.
    val preview = send("GET", pathOf(secret.previewUrl))
    assertEquals(200, preview.code)
    assertEquals("png:secret", preview.body)
    assertEquals(
      404,
      send("GET", pathOf(secret.previewUrl).replace(Regex("sig=[^&]+"), "sig=forged")).code,
    )

    // Unpublishing takes it off watches.
    assertEquals(
      200,
      send("DELETE", "/api/ui-builder/v1/designs/secret/watch-push", actor = alice).code,
    )
    assertEquals(listOf("aurora", "pulse"), designs(aliceToken).map { it.id })
    // Making a public face private takes it off everybody else's.
    runBlocking {
      service.setDesignVisibility(
        AuthenticatedUiBuilderActor(alice),
        "aurora",
        UiBuilderDefaultVisibility.PRIVATE,
      )
    }
    assertEquals(listOf("pulse"), designs().map { it.id })
    assertEquals(listOf("aurora", "pulse"), designs(aliceToken).map { it.id })
  }

  @Test
  fun `apks download through the subprocess packager, anonymously only for public faces`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "secret", "Secret")
    publish(alice, "aurora")
    publish(alice, "secret")
    val aliceToken = link(alice)
    val bobToken = link(bob, "Bob's watch")

    val aurora = designs().single()
    val apk = send("GET", pathOf(aurora.apkUrl))
    assertEquals(200, apk.code)
    assertEquals("application/vnd.android.package-archive", apk.headers["Content-Type"])
    assertEquals(aurora.sha256, sha256Hex(apk.bytes))
    assertEquals("\"${aurora.sha256}\"", apk.headers["ETag"])
    assertContains(apk.headers["Cache-Control"]!!, "private")
    // Revalidation, as a client with the bytes already does.
    assertEquals(
      304,
      send("GET", pathOf(aurora.apkUrl), headers = mapOf("If-None-Match" to "\"${aurora.sha256}\""))
        .code,
    )

    // A private design is never downloadable without its owner's token.
    val secretPath = "$api/designs/secret/apk"
    // No token: 401, so the app shows linking. Another account's live token: 404, not 401.
    val anonymous = send("GET", secretPath)
    assertEquals(401, anonymous.code)
    assertEquals("Bearer", anonymous.headers["WWW-Authenticate"])
    assertEquals(404, send("GET", secretPath, bearer = bobToken).code)
    val secret = send("GET", secretPath, bearer = aliceToken)
    assertEquals(200, secret.code)
    assertEquals("fake-apk:secret:1", secret.body)
    // Unpublished and unknown designs are 404 too.
    assertEquals(404, send("GET", "$api/designs/nope/apk").code)

    // The packager was asked for exactly a push build, signed from the environment it was given.
    val runs = packagerLog.readLines()
    assertEquals(
      listOf(
        "ee.schimke.watchface.push aurora 1 true market-pw c0ffee",
        "ee.schimke.watchface.push secret 1 true market-pw c0ffee",
      ),
      runs,
    )
    // Built once per version, however often it is listed and downloaded.
    designs()
    send("GET", pathOf(aurora.apkUrl))
    assertEquals(2, packagerLog.readLines().size)
  }

  @Test
  fun `version moves when the published face changes, and only then, and survives an unpublish`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    val first =
      WATCHFACE_PUSH_JSON.decodeFromString(
        WatchFacePushPublicationV1.serializer(),
        publish(alice, "aurora").body,
      )
    assertEquals(1, first.version)
    assertEquals("ready", first.apk?.status)
    // Unchanged: same version, no rebuild.
    assertEquals(
      1,
      WATCHFACE_PUSH_JSON.decodeFromString(
          WatchFacePushPublicationV1.serializer(),
          publish(alice, "aurora").body,
        )
        .version,
    )
    faces["aurora"] = "<WatchFace id=\"aurora\" edited=\"1\"/>"
    val second =
      WATCHFACE_PUSH_JSON.decodeFromString(
        WatchFacePushPublicationV1.serializer(),
        publish(alice, "aurora").body,
      )
    assertEquals(2, second.version)
    send("DELETE", "/api/ui-builder/v1/designs/aurora/watch-push", actor = alice)
    faces["aurora"] = "<WatchFace id=\"aurora\" edited=\"2\"/>"
    publish(alice, "aurora")
    val listed = designs().single()
    assertEquals(3, listed.version)
    assertEquals("fake-apk:aurora:3", send("GET", pathOf(listed.apkUrl)).body)
    // The apkUrl names its version; an old one is gone rather than silently another APK.
    assertEquals(404, send("GET", "$api/designs/aurora/apk?v=2").code)
    assertEquals(listOf("1", "2", "3"), packagerLog.readLines().map { it.split(' ')[2] })
  }

  @Test
  fun `only the owner may publish, only a watch-face design, and the status says where it is`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    assertEquals(403, publish(bob, "aurora").code)
    assertEquals(404, publish(bob, "nope").code)
    assertEquals(401, send("PUT", "/api/ui-builder/v1/designs/aurora/watch-push").code)
    val unpublished = send("GET", "/api/ui-builder/v1/designs/aurora/watch-push", actor = alice)
    assertEquals(
      false,
      WATCHFACE_PUSH_JSON.decodeFromString(
          WatchFacePushPublicationV1.serializer(),
          unpublished.body,
        )
        .published,
    )
    watchCatalogs = emptySet()
    val refused = send("PUT", "/api/ui-builder/v1/designs/aurora/watch-push", actor = alice)
    assertEquals(422, refused.code)
    assertContains(refused.body, "not_a_watch_face")
  }

  @Test
  fun `a host with no signing key lists nothing, says why, and serves no APK`() {
    val reason =
      (WatchFacePushSigning.fromEnvironment(emptyMap()) as WatchFacePushSigning.Unavailable).reason
    start(
      packaging =
        WatchFacePushPackaging.production(WatchFacePushSigning.Unavailable(reason), dir.toFile()),
    )
    createDesign(alice, "aurora", "Aurora", public = true)
    val published =
      WATCHFACE_PUSH_JSON.decodeFromString(
        WatchFacePushPublicationV1.serializer(),
        publish(alice, "aurora").body,
      )
    assertTrue(published.published)
    assertEquals("unavailable", published.apk?.status)
    assertEquals(WatchFacePushPackaging.SIGNING_KEY_MISSING, published.apk?.code)

    val status = Json.parseToJsonElement(send("GET", "$api/status").body).jsonObject
    assertEquals("false", status["available"]!!.jsonPrimitive.content)
    assertEquals(WatchFacePushPackaging.SIGNING_KEY_MISSING, status["code"]!!.jsonPrimitive.content)
    assertContains(status["reason"]!!.jsonPrimitive.content, "WFF_SIGNING_KEYSTORE_PATH")

    val list = send("GET", "$api/designs")
    assertEquals(emptyList(), App.json.decodeFromString<App.DesignList>(list.body).designs)
    assertContains(list.body, WatchFacePushPackaging.SIGNING_KEY_MISSING)
    assertEquals(503, send("GET", "$api/designs/aurora/apk").code)
  }

  @Test
  fun `an anonymous reader is rate limited by address`() {
    start()
    repeat(60) { assertEquals(200, send("GET", "$api/designs").code) }
    val limited = send("GET", "$api/designs")
    assertEquals(429, limited.code)
    assertNotNull(limited.headers["Retry-After"])
  }

  @Test
  fun `the MCP tool publishes and unpublishes for the owner only, and exists only with the lane`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    val mcp = ServeUiBuilderMcp(service, watchFacePush = push)
    assertEquals(
      UiBuilderRouteCapability.WRITE,
      mcp.capabilityFor(ServeUiBuilderMcp.PUBLISH_WATCH_FACE),
    )
    assertNull(ServeUiBuilderMcp(service).capabilityFor(ServeUiBuilderMcp.PUBLISH_WATCH_FACE))
    val call = { actor: String, published: Boolean ->
      runBlocking {
        runCatching {
          mcp.call(
            ServeUiBuilderMcp.PUBLISH_WATCH_FACE,
            Json.parseToJsonElement("""{"designId":"aurora","published":$published}""").jsonObject,
            AuthenticatedUiBuilderActor(actor),
            callId = "t",
          )
        }
      }
    }
    val refused = call(bob, true)
    assertContains(
      (refused.exceptionOrNull()?.message ?: refused.getOrNull()).orEmpty(),
      "owner_required",
    )
    val published = call(alice, true).getOrThrow()
    assertContains(published, "\"published\":true")
    assertContains(published, "\"version\":1")
    assertEquals(listOf("aurora"), designs().map { it.id })
    assertContains(call(alice, false).getOrThrow(), "\"published\":false")
    assertEquals(emptyList(), designs())
    val declared =
      ServeUiBuilderMcp.declarations(
        { name, _, _ ->
          kotlinx.serialization.json.JsonObject(
            mapOf("name" to kotlinx.serialization.json.JsonPrimitive(name)),
          )
        },
        watchFacePush = true,
      )
    assertTrue(
      declared.any { it["name"]!!.jsonPrimitive.content == ServeUiBuilderMcp.PUBLISH_WATCH_FACE },
    )
  }

  @Test
  fun `a delegated grant can neither link a watch nor unlink one`() {
    start()
    val token = link(alice)
    val code =
      App.json.decodeFromString<App.DeviceCode>(send("POST", "$api/device-codes", "{}").body)
    val agent = mapOf("X-Test-Agent-For" to alice)
    val page = send("GET", "/link?code=${code.code}", headers = agent)
    assertFalse("Link Wear OS watch?" in page.body, "an agent was offered the confirmation")
    assertFalse("name=\"seal\"" in page.body)
    // A seal minted for alice's own session does not help the agent either.
    val seal =
      Regex("""name="action" value="approve">\s*<input type="hidden" name="seal" value="([^"]+)"""")
        .find(send("GET", "/link?code=${code.code}", actor = alice).body)!!
        .groupValues[1]
    val approved =
      send(
        "POST",
        "/link",
        headers = agent,
        form = mapOf("subject" to code.code, "action" to "approve", "seal" to seal),
      )
    assertEquals(403, approved.code)
    assertEquals(
      "pending",
      App.json
        .decodeFromString<App.PollResponse>(
          send(
              "POST",
              "$api/device-codes/${code.code}/poll",
              App.json.encodeToString(App.PollRequest(code.pollSecret)),
            )
            .body,
        )
        .status,
    )
    assertEquals(401, send("GET", "$api/devices", headers = agent).code)
    val deviceId = push.store.devices(alice).single().id
    assertEquals(401, send("DELETE", "$api/devices/$deviceId", headers = agent).code)
    assertEquals(200, send("GET", "$api/designs", bearer = token).code)
  }

  @Test
  fun `a re-publish the packager refuses leaves the release on watches as it was`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    assertEquals(200, publish(alice, "aurora").code)
    val before = designs().single()
    faces["aurora"] = "<WatchFace id=\"aurora\" state=\"bad\"/>"
    val refused = publish(alice, "aurora")
    assertEquals(422, refused.code)
    assertContains(refused.body, "bad face")
    val after = designs().single()
    assertEquals(before, after)
    assertEquals(200, send("GET", pathOf(after.apkUrl)).code)
    // Fixed, it ships as the next version.
    faces["aurora"] = "<WatchFace id=\"aurora\" state=\"good\"/>"
    assertEquals(200, publish(alice, "aurora").code)
    assertEquals(2, designs().single().version)
  }

  @Test
  fun `a rate-limit permit is held while the request runs and released after`() {
    val drawing = kotlinx.coroutines.CompletableDeferred<Unit>()
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    publish(alice, "aurora")
    val listed = designs().single()
    val slow =
      WatchFacePush(
        store = push.store,
        packaging = push.packaging,
        sourceFor = push.sourceFor,
        isWatchFaceCatalog = push.isWatchFaceCatalog,
        preview = { _, _, _ ->
          drawing.complete(Unit)
          release.await()
          "png".toByteArray()
        },
      )
    server?.stop()
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "local-fixture",
          sessions = ServeSessionRegistry(open = { null }).also(registries::add),
          defaultSessionId = "unused",
          machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
          uiBuilderService = service,
          watchFacePush = slow,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization { _, _, _ -> UiBuilderAuthorizationDecision.Missing },
        )
        .also { it.start() }
    val pending =
      java.util.concurrent.CompletableFuture.supplyAsync { send("GET", pathOf(listed.previewUrl)) }
    runBlocking { kotlinx.coroutines.withTimeout(10_000) { drawing.await() } }
    assertEquals(1, slow.anonymousLimiter.activeCallers())
    release.complete(Unit)
    assertEquals(200, pending.get(10, TimeUnit.SECONDS).code)
    assertEquals(0, slow.anonymousLimiter.activeCallers())
  }

  @Test
  fun `a purged design's publication is never served for a design recreated under its id`() {
    start()
    createDesign(alice, "secret", "Secret")
    publish(alice, "secret")
    val aliceToken = link(alice)
    val old = designs(aliceToken).single()
    val oldApk = send("GET", pathOf(old.apkUrl), bearer = aliceToken)
    assertEquals("fake-apk:secret:1", oldApk.body)

    // Permanently deleted, without the sweep: the binding alone keeps it from the next owner.
    assertTrue(service.adminDeleteDesign("secret"))
    createDesign(bob, "secret", "Bob's", public = true)
    assertEquals(emptyList(), designs())
    assertEquals(emptyList(), designs(aliceToken))
    assertEquals(404, send("GET", pathOf(old.apkUrl)).code)
    assertEquals(404, send("GET", pathOf(old.apkUrl), bearer = aliceToken).code)
    assertEquals(404, send("GET", pathOf(old.previewUrl)).code)

    // The admin delete (and the trash purge) sweeps it: retired, keeping only the version.
    val admin =
      ServeUiBuilderAdmin(service = service, retireWatchPublication = { push.store.retire(it) })
    assertIs<ServeUiBuilderAdmin.Result.Deleted>(admin.delete("secret"))
    val retired = push.store.publication("secret")!!
    assertFalse(retired.published)
    assertEquals("", retired.accountActorId)
    assertEquals("", retired.wffXml)
    assertEquals(1, retired.version)
    // Whoever publishes the id next starts above the version already installed.
    createDesign(bob, "secret", "Bob's", public = true)
    faces["secret"] = "<WatchFace id=\"bobs\"/>"
    assertEquals(200, publish(bob, "secret").code)
    val bobs = designs().single()
    assertEquals(2, bobs.version)
    assertEquals("fake-apk:secret:2", send("GET", pathOf(bobs.apkUrl)).body)
  }

  private fun document(id: String, title: String) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = id,
      title = title,
      revision = 0,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 227,
          heightDp = 227,
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
              properties = mapOf("text" to StringValueV1("12:00")),
            ),
        ),
    )

  /**
   * The push app's wire model, copied from yschimke/watchface-tools
   * `push-app/src/main/kotlin/ee/schimke/watchface/push/data/Models.kt` and `PushApi.kt`
   * (`DefaultJson`), so these tests fail if the server stops speaking it. Keep in sync with the
   * app, not with the server's own classes.
   */
  internal object App {
    const val PACKAGE_PREFIX = "ee.schimke.watchface.push.watchfacepush."

    val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }

    @Serializable
    data class DeviceCodeRequest(val deviceName: String? = null, val wearOsApi: Int? = null)

    @Serializable
    data class DeviceCode(
      val code: String,
      val pollSecret: String,
      val verificationUrl: String,
      val expiresInSeconds: Int,
      val pollIntervalSeconds: Int = 5,
    )

    @Serializable data class PollRequest(val pollSecret: String)

    @Serializable
    data class PollResponse(
      val status: String,
      val deviceToken: String? = null,
      val accountName: String? = null,
    )

    @Serializable data class DesignList(val designs: List<Design>)

    @Serializable
    data class Design(
      val id: String,
      val title: String,
      val description: String? = null,
      val previewUrl: String,
      val version: Long,
      val versionName: String? = null,
      val wffVersion: Int,
      val packageName: String,
      val apkUrl: String,
      val sha256: String,
      val sizeBytes: Long? = null,
      val validationToken: String,
    )

    @Serializable
    data class InstallEvent(
      val designId: String,
      val version: Long,
      val result: String,
      val errorKind: String? = null,
      val wearOsApi: Int? = null,
    )
  }
}

/** The pieces under the routes: naming, versions, signing. */
class WatchFacePushUnitTest {
  @TempDir lateinit var dir: Path

  @Test
  fun `a plain design id is its own face name, anything else is sanitised and made unique`() {
    assertEquals("aurora", WatchFacePushNaming.faceName("aurora"))
    assertEquals("Pulse_2", WatchFacePushNaming.faceName("Pulse_2"))
    val dashed = WatchFacePushNaming.faceName("my-face")
    val underscored = WatchFacePushNaming.faceName("my_face")
    assertEquals("my_face", underscored)
    assertTrue(dashed.startsWith("my_face_") && dashed != underscored, dashed)
    for (id in listOf("my-face", "9lives", "-", "class", "über.face", "a".repeat(80), "x y/z")) {
      val face = WatchFacePushNaming.faceName(id)
      assertTrue(Regex("[A-Za-z][A-Za-z0-9_]*").matches(face), "$id -> $face")
      assertEquals(face, WatchFacePushNaming.faceName(id), "stable")
      assertTrue(WatchFacePushNaming.followsRule(WatchFacePushNaming.packageName(face)))
    }
    assertEquals(
      "ee.schimke.watchface.push.watchfacepush.aurora",
      WatchFacePushNaming.packageName("aurora"),
    )
    assertFalse(WatchFacePushNaming.followsRule("ee.schimke.watchface.push.watchfacepush.a-b"))
    assertFalse(WatchFacePushNaming.followsRule("com.example.aurora"))
  }

  @Test
  fun `a plain id shaped like a hashed name cannot take another design's package or record`() {
    val hashed = WatchFacePushNaming.faceName("my-face")
    assertTrue(Regex("my_face_[0-9a-f]{10}").matches(hashed), hashed)
    val literal = WatchFacePushNaming.faceName(hashed)
    assertNotEquals(hashed, literal)
    assertTrue(Regex("[A-Za-z][A-Za-z0-9_]*").matches(literal), literal)

    val root = dir.resolve("push")
    val store = WatchFacePushStore(root)
    store.publish("my-face", "github:alice", 1, "Dashed", DwfSource("<WatchFace a=\"1\"/>"))
    store.publish(hashed, "github:bob", 1, "Literal", DwfSource("<WatchFace b=\"1\"/>"))
    val reopened = WatchFacePushStore(root)
    val dashed = reopened.publication("my-face")!!
    val other = reopened.publication(hashed)!!
    assertEquals("github:alice", dashed.accountActorId)
    assertEquals("github:bob", other.accountActorId)
    assertNotEquals(
      WatchFacePushNaming.packageName(dashed.faceName),
      WatchFacePushNaming.packageName(other.faceName),
    )
    assertEquals(2, reopened.published().size)
  }

  @Test
  fun `racing commits never lower or reuse a version`() {
    val store = WatchFacePushStore(dir.resolve("race"))
    val a = DwfSource("<WatchFace a=\"1\"/>")
    val b = DwfSource("<WatchFace b=\"1\"/>")
    val c = DwfSource("<WatchFace c=\"1\"/>")
    assertEquals(1, store.publish("f", "github:alice", 1, "F", a).version)
    // Codex's interleaving: three candidates taken at v1, committed C, B, B.
    val cAt2 = store.candidate("f", "github:alice", 2, "F", c)
    val bAt2 = store.candidate("f", "github:alice", 3, "F", b)
    val bAgain = store.candidate("f", "github:alice", 3, "F", b)
    assertEquals(2, store.commit(cAt2).version)
    assertEquals(3, store.commit(bAt2).version)
    assertEquals(3, store.commit(bAgain).version, "a late duplicate must not roll v3 back to v2")
    // The build-then-commit path refuses a candidate whose version moved, so it is rebuilt.
    assertNull(
      store.commitIfBuiltVersion(store.candidate("f", "github:alice", 4, "F", a).copy(version = 2)),
    )
    assertEquals(3, store.publication("f")!!.version)

    // Many threads, alternating faces: every version anyone observes only ever grows.
    val faces = listOf(a, b, c)
    val errors = java.util.concurrent.ConcurrentLinkedQueue<String>()
    val threads =
      (0 until 8).map { t ->
        Thread {
          var seen = 0L
          repeat(100) { i ->
            val candidate =
              store.candidate("f", "github:alice", i.toLong(), "F", faces[(t + i) % 3])
            val committed = store.commit(candidate).version
            val stored = store.publication("f")!!.version
            if (committed < seen || stored < seen)
              errors += "thread $t saw $committed/$stored after $seen"
            seen = maxOf(seen, committed, stored)
          }
        }
      }
    threads.forEach(Thread::start)
    threads.forEach(Thread::join)
    assertTrue(errors.isEmpty(), errors.take(5).joinToString())
    assertEquals(
      store.publication("f")!!.version,
      WatchFacePushStore(dir.resolve("race")).publication("f")!!.version,
    )
  }

  @Test
  fun `versions only grow, across unpublish, republish and a restart`() {
    val root = dir.resolve("push")
    val store = WatchFacePushStore(root)
    val a = DwfSource("<WatchFace a=\"1\"/>", label = "aurora")
    val b = DwfSource("<WatchFace a=\"2\"/>", label = "aurora")
    assertEquals(1, store.publish("aurora", "github:alice", 1, "Aurora", a).version)
    assertEquals(1, store.publish("aurora", "github:alice", 2, "Aurora", a).version)
    assertEquals(2, store.publish("aurora", "github:alice", 3, "Aurora", b).version)
    assertEquals(false, store.unpublish("aurora")?.published)
    assertEquals(emptyList(), store.published())
    val reopened = WatchFacePushStore(root)
    assertEquals(2, reopened.publication("aurora")?.version)
    assertEquals(3, reopened.publish("aurora", "github:alice", 4, "Aurora", a).version)
    assertEquals(store.previewSignature("aurora", 3), reopened.previewSignature("aurora", 3))
  }

  @Test
  fun `device codes normalise what a person types`() {
    assertEquals("AB7K-92QX", WatchFacePushDeviceCodes.normalize("ab7k 92qx"))
    assertEquals("AB7K-92QX", WatchFacePushDeviceCodes.normalize("AB7K92QX"))
    assertNull(WatchFacePushDeviceCodes.normalize("AB0K-92QX"))
    assertNull(WatchFacePushDeviceCodes.normalize("AB7K-92Q"))
  }

  private fun keytool(vararg args: String) {
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val process = ProcessBuilder(listOf(keytool) + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  @Test
  fun `the signing key is read from the environment, checked, and pinned, never generated`() {
    val p12 = dir.resolve("market.p12").toFile()
    keytool(
      "-genkeypair",
      "-keystore",
      p12.path,
      "-storetype",
      "PKCS12",
      "-storepass",
      "store-pw",
      "-alias",
      "market",
      "-keyalg",
      "EC",
      "-groupname",
      "secp256r1",
      "-validity",
      "3650",
      "-dname",
      "CN=test marketplace",
    )
    val env =
      mapOf(
        WatchFacePushSigning.KEYSTORE_PATH to p12.path,
        WatchFacePushSigning.KEYSTORE_PASSWORD to "store-pw",
      )
    val configured =
      assertIs<WatchFacePushSigning.Configured>(WatchFacePushSigning.fromEnvironment(env) {})
    assertEquals(64, configured.certificateSha256.length)
    assertEquals("market", configured.environment[WatchFacePushSigning.KEY_ALIAS])
    assertEquals(
      configured.certificateSha256,
      configured.environment[WatchFacePushSigning.CERT_SHA256],
    )
    assertEquals(
      p12.readBytes().toList(),
      java.util.Base64.getDecoder()
        .decode(configured.environment[WatchFacePushSigning.KEYSTORE_B64])
        .toList(),
    )

    // The same key as base64, and pinned (colons and case ignored).
    val pin = configured.certificateSha256.uppercase().chunked(2).joinToString(":")
    assertIs<WatchFacePushSigning.Configured>(
      WatchFacePushSigning.fromEnvironment(
        mapOf(
          WatchFacePushSigning.KEYSTORE_B64 to
            java.util.Base64.getEncoder().encodeToString(p12.readBytes()),
          WatchFacePushSigning.KEYSTORE_PASSWORD to "store-pw",
          WatchFacePushSigning.CERT_SHA256 to pin,
        ),
      ) {},
    )
    val wrongPin =
      WatchFacePushSigning.fromEnvironment(
        env + (WatchFacePushSigning.CERT_SHA256 to "00".repeat(32)),
      ) {}
    assertContains(assertIs<WatchFacePushSigning.Unavailable>(wrongPin).reason, "not the pinned")
    val wrongPassword =
      WatchFacePushSigning.fromEnvironment(
        env + (WatchFacePushSigning.KEYSTORE_PASSWORD to "nope"),
      ) {}
    assertIs<WatchFacePushSigning.Unavailable>(wrongPassword)
    assertIs<WatchFacePushSigning.Unavailable>(
      WatchFacePushSigning.fromEnvironment(
        mapOf(WatchFacePushSigning.KEYSTORE_PATH to p12.path),
      ) {},
    )
    // With nothing configured there is no key at all: no debug key is made for push builds.
    val none = WatchFacePushSigning.fromEnvironment(emptyMap()) {}
    assertIs<WatchFacePushSigning.Unavailable>(none)
    assertIs<WatchFacePushPackaging.Unavailable>(
      WatchFacePushPackaging.production(
        none,
        dir.toFile(),
        sdk = { DwfAndroidSdk(dir.toFile()) },
        libDir = { dir.toFile() },
      )(),
    )
  }

  @Test
  fun `a push build that issues no validation token is a refusal`() {
    val java =
      dir.resolve("java.sh").toFile().apply {
        writeText(
          "#!/bin/sh\nwhile [ \"\$#\" -gt 0 ]; do [ \"\$1\" = --out ] && printf apk > \"\$2\"; shift; done\n",
        )
        setExecutable(true)
      }
    val packager =
      WatchFacePushSubprocessPackager(
        dir.toFile(),
        DwfAndroidSdk(dir.toFile()),
        dir.resolve("home").toFile(),
        WatchFacePushSigning.Configured(emptyMap(), "x"),
        java = java.path,
      )
    val failure =
      assertFailsWith<DwfPackagingFailed> {
        packager.pack(
          DwfPackaging.bundleFiles(DwfSource("<WatchFace/>")),
          "aurora",
          1,
          dir.resolve("o.apk").toFile(),
        )
      }
    assertContains(failure.diagnostics.single(), "validation token")
  }

  @Test
  fun `the push build's child environment carries the configured key and no other signing variable`() {
    val seen = dir.resolve("env.txt").toFile()
    val java =
      dir.resolve("java.sh").toFile().apply {
        writeText(
          "#!/bin/sh\n" +
            "env | grep '^WFF_SIGNING_' | sort > ${seen.path}\n" +
            "while [ \"\$#\" -gt 0 ]; do\n" +
            "  [ \"\$1\" = --out ] && printf apk > \"\$2\"\n" +
            "  [ \"\$1\" = --token-out ] && printf tok > \"\$2\"\n" +
            "  shift\n" +
            "done\n",
        )
        setExecutable(true)
      }
    val packager =
      WatchFacePushSubprocessPackager(
        dir.toFile(),
        DwfAndroidSdk(dir.toFile()),
        dir.resolve("home").toFile(),
        WatchFacePushSigning.Configured(mapOf("WFF_SIGNING_KEYSTORE_PASSWORD" to "pw"), "x"),
        java = java.path,
      )
    assertEquals(
      "tok",
      packager.pack(
        DwfPackaging.bundleFiles(DwfSource("<WatchFace/>")),
        "aurora",
        7,
        dir.resolve("o.apk").toFile(),
      ),
    )
    assertEquals("WFF_SIGNING_KEYSTORE_PASSWORD=pw", seen.readText().trim())
  }
}
