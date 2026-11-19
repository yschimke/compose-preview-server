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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

  /** The service as the server's routes reach it: its writes tell the automatic listing. */
  private lateinit var feed: UiBuilderServicePort
  private var now = 1_700_000_000_000L
  private var watchCatalogs = setOf("m3-catalog")

  /**
   * Whether public designs are listed on their own ([WatchFacePushAutomatic]). Off for the tests of
   * the explicit path, which then see exactly what they publish.
   */
  private var automaticListing = false

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

  private val drawablesLog: File
    get() = dir.resolve("drawables.log").toFile()

  private val assetStore by lazy { FileUiBuilderAssetStore(dir.resolve("assets")) }

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
          // What the build packaged as drawables: "<versionCode> <file> <bytes>" per file.
          "if [ -d \"\$bundle/res/drawable\" ]; then for f in \"\$bundle\"/res/drawable/*; do " +
          "echo \"\$vc \$(basename \"\$f\") \$(wc -c < \"\$f\" | tr -d ' ')\" >> ${drawablesLog.path}; " +
          "done; fi\n" +
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
        assets = assetStore,
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
        designSummary = { if (automaticListing) service.adminDesignSummary(it) else null },
        // The tests run the scheduler themselves, on their own clock.
        automaticTickMillis = null,
        automaticBuilds = automaticListing,
        storedDesigns = {
          service.adminListDesigns().map {
            WatchFacePushStoredDesigns.Head(it.designId, it.catalogPin.systemId)
          }
        },
        automaticSweepPauseMillis = 0,
        assets = assetStore,
      )
    feed = WatchFacePushChangeFeed(service) { id, access -> push.automatic.noteChanged(id, access) }
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "local-fixture",
        sessions = ServeSessionRegistry(open = { null }).also(registries::add),
        defaultSessionId = "unused",
        machineAuthorization = ServeMachineAuthorization("local-fixture", null, null),
        uiBuilderService = service,
        watchFacePush = push,
        uiBuilderDwfPreview =
          UiBuilderDwfPreview(
            sessions = DwfPreviewSessions(FakeDwfRuntime()),
            isDwfCatalog = { true },
          ),
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
      feed.execute(
        UiBuilderServiceCall(
          AuthenticatedUiBuilderActor(owner),
          UiBuilderServiceRequest.CreateDesign(document(id, title)),
        ),
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(created, "create $id: $created")
    if (public) {
      val shared =
        feed.setDesignVisibility(
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

  private fun put(actor: String, designId: String, visibility: String? = null): Reply =
    send(
      "PUT",
      "/api/ui-builder/v1/designs/$designId/watch-face-publication",
      json = visibility?.let { "{\"visibility\":\"$it\"}" },
      actor = actor,
    )

  /**
   * Publish and wait for the build, as an owner watching the editor does: the `PUT`'s own answer
   * when it refused up front, else the publication once its queued build ended — a refused build
   * read back as the `422` it would have been.
   */
  private fun publish(actor: String, designId: String, visibility: String? = null): Reply {
    val accepted = put(actor, designId, visibility)
    if (accepted.code != 202) return accepted
    runBlocking { push.queue.awaitIdle() }
    val after =
      send("GET", "/api/ui-builder/v1/designs/$designId/watch-face-publication", actor = actor)
    val build =
      WATCHFACE_PUSH_JSON.decodeFromString(WatchFacePushPublicationV1.serializer(), after.body)
        .build
    if (build?.state != WatchFacePushBuildQueue.REFUSED) return after
    val refused =
      WATCHFACE_PUSH_JSON.encodeToString(
        WatchFacePushErrorV1.serializer(),
        WatchFacePushErrorV1(
          "packaging_failed",
          build.diagnostics.orEmpty().joinToString("\n"),
          build.diagnostics,
        ),
      )
    return Reply(422, refused, after.headers, refused.toByteArray())
  }

  private fun publication(designId: String, actor: String = alice): WatchFacePushPublicationV1 {
    val reply =
      send("GET", "/api/ui-builder/v1/designs/$designId/watch-face-publication", actor = actor)
    assertEquals(200, reply.code, reply.body)
    return WATCHFACE_PUSH_JSON.decodeFromString(WatchFacePushPublicationV1.serializer(), reply.body)
  }

  private fun publicationOf(reply: Reply): WatchFacePushPublicationV1 {
    assertEquals(200, reply.code, reply.body)
    return WATCHFACE_PUSH_JSON.decodeFromString(WatchFacePushPublicationV1.serializer(), reply.body)
  }

  /** The `visibility` the list gives [designId] (the app's model does not carry it). */
  private fun visibilityOf(designId: String, token: String? = null): String? =
    Json.parseToJsonElement(send("GET", "$api/designs", bearer = token).body)
      .jsonObject["designs"]!!
      .jsonArray
      .map { it.jsonObject }
      .single { it["id"]!!.jsonPrimitive.content == designId }["visibility"]
      ?.jsonPrimitive
      ?.content

  /**
   * Let every scheduled design settle (the clock moves past the debounce) and run the automatic
   * listing's scheduler once, as its thread would; then list.
   */
  private fun settledDesigns(token: String? = null): List<App.Design> {
    settle()
    return designs(token)
  }

  private fun settle() {
    now += WatchFacePushAutomatic.DEFAULT_SETTLE_MILLIS + 1
    runBlocking { push.automatic.runDue(service) }
  }

  /** An edit: a new revision of the design (its text set to [title]), exporting [face]. */
  private fun edit(owner: String, designId: String, face: String?, title: String = "edited") =
    runBlocking {
      if (face != null) faces[designId] = face
      val actor = AuthenticatedUiBuilderActor(owner)
      val opened =
        assertIs<UiBuilderServiceResponse.Snapshot>(
          service.execute(
            UiBuilderServiceCall(actor, UiBuilderServiceRequest.OpenDesign(designId)),
          ),
        )
      val edited =
        feed.execute(
          UiBuilderServiceCall(
            actor,
            UiBuilderServiceRequest.ApplyOperation(
              UiBuilderSubmission.Batch(
                designId,
                "edit-$title",
                "test",
                opened.snapshot.state.document.revision,
                listOf(SetPropertyMutationV1("text", "text", StringValueV1(title))),
              ),
            ),
          ),
        )
      assertIs<AcceptedOutcomeV1>(
        assertIs<UiBuilderServiceResponse.OperationOutcome>(edited, "edit $designId").outcome,
      )
      feed.execute(
        UiBuilderServiceCall(actor, UiBuilderServiceRequest.RenameDesign(designId, title)),
      )
    }

  private fun setVisibility(owner: String, designId: String, public: Boolean) = runBlocking {
    feed.setDesignVisibility(
      AuthenticatedUiBuilderActor(owner),
      designId,
      if (public) UiBuilderDefaultVisibility.PUBLIC else UiBuilderDefaultVisibility.PRIVATE,
    )
  }

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
  fun `install reports are kept per watch and design, and only the owner reads them`() {
    start()
    createDesign(alice, "aurora", "Aurora")
    assertEquals(200, publish(alice, "aurora").code)
    val wrist = link(alice, "Wrist")
    val spare = link(alice, "Spare")
    val bobs = link(bob, "Bob's")
    fun report(token: String, event: App.InstallEvent) =
      send("POST", "$api/installs", App.json.encodeToString(event), bearer = token).code
    assertEquals(204, report(wrist, App.InstallEvent("aurora", 1, "failed", errorKind = "x")))
    assertEquals(
      204,
      report(
        wrist,
        App.InstallEvent(
          "aurora",
          1,
          "activation",
          wearOsApi = 36,
          packageName = "ee.schimke.watchface.push.watchfacepush.aurora",
          activation = "activated",
          deviceModel = "Pixel Watch 4",
          wearOsVersion = "6",
          watchFacePushApiVersion = "1.0.0",
          appVersion = "1.2.3",
        ),
      ),
    )
    assertEquals(204, report(spare, App.InstallEvent("aurora", 1, "failed", activation = "bogus")))
    // Neither a catalog face nor another account's watch is kept.
    assertEquals(204, report(wrist, App.InstallEvent("c_wear_aurora_1", 1, "installed")))
    assertEquals(204, report(bobs, App.InstallEvent("aurora", 1, "installed")))
    assertEquals(400, report(wrist, App.InstallEvent("", 1, "installed")))

    val path = "/api/ui-builder/v1/designs/aurora/watch-installs"
    assertNotEquals(200, send("GET", path, actor = bob).code)
    val reply = send("GET", path, actor = alice)
    assertEquals(200, reply.code, reply.body)
    val installs =
      Json.parseToJsonElement(reply.body).jsonObject["installs"]!!.jsonArray.map { it.jsonObject }
    assertEquals(
      listOf("Spare", "Wrist"),
      installs.map { it["deviceName"]!!.jsonPrimitive.content },
    )
    val spareRow = installs[0]
    assertEquals("failed", spareRow["result"]!!.jsonPrimitive.content)
    assertNull(spareRow["activation"], "an unknown activation is not kept")
    val wristRow = installs[1]
    assertEquals("activation", wristRow["result"]!!.jsonPrimitive.content, "the latest replaces")
    assertEquals("activated", wristRow["activation"]!!.jsonPrimitive.content)
    assertEquals("Pixel Watch 4", wristRow["deviceModel"]!!.jsonPrimitive.content)
    assertEquals(36, wristRow["wearOsApi"]!!.jsonPrimitive.int)
    assertEquals(1, wristRow["version"]!!.jsonPrimitive.long)
    assertNotNull(wristRow["revisionPublished"])
    assertNotNull(wristRow["atEpochMillis"])
    assertFalse(reply.body.contains("wfp_"), "no device token leaks")

    // An unlinked watch's reports go with it.
    val spareId =
      Json.parseToJsonElement(send("GET", "$api/devices", actor = alice).body)
        .jsonObject["devices"]!!
        .jsonArray
        .first { it.jsonObject["deviceName"]!!.jsonPrimitive.content == "Spare" }
        .jsonObject["id"]!!
        .jsonPrimitive
        .content
    assertEquals(204, send("DELETE", "$api/devices/$spareId", actor = alice).code)
    val after = Json.parseToJsonElement(send("GET", path, actor = alice).body).jsonObject
    assertEquals(1, after["installs"]!!.jsonArray.size)
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
      send("DELETE", "/api/ui-builder/v1/designs/secret/watch-face-publication", actor = alice)
        .code,
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

  /** A face drawing the bitmap `case_steel`. */
  private val imageFace =
    """<WatchFace width="450" height="450"><Scene><PartImage x="0" y="0" width="450" """ +
      """height="450"><Image resource="case_steel"/></PartImage></Scene></WatchFace>"""

  private val png: ByteArray by lazy {
    java.io
      .ByteArrayOutputStream()
      .also {
        javax.imageio.ImageIO.write(
          java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB),
          "png",
          it,
        )
      }
      .toByteArray()
  }

  @Test
  fun `a published face carries the design's pinned drawables, across a restart`() {
    start()
    createDesign(alice, "steel", "Steel", public = true)
    runBlocking {
      assertIs<UiBuilderServiceResponse.OperationOutcome>(
        service.putAsset(
          UiBuilderAssetWrite(AuthenticatedUiBuilderActor(alice), "steel", "case_steel", png),
        ),
      )
    }
    faces["steel"] = imageFace
    val published = publish(alice, "steel")
    assertEquals(200, published.code, published.body)
    assertContains(drawablesLog.readLines(), "1 case_steel.png ${png.size}")

    // A rebuild long after the revision was pruned reads the face back from the push store alone.
    val reopened = WatchFacePushStore(dir.resolve("watchface-push"), clock = { now })
    val source = reopened.source(assertNotNull(reopened.publication("steel")))
    assertContentEquals(png, source.assets["drawable/case_steel.png"])
    assertEquals(imageFace, source.wffXml)
  }

  @Test
  fun `a face naming a bitmap the design does not pin is refused, not left to the packager`() {
    start()
    createDesign(alice, "bare", "Bare", public = true)
    faces["bare"] = imageFace
    val refused = publish(alice, "bare")
    assertEquals(422, refused.code, refused.body)
    assertContains(refused.body, WatchFaceDrawables.MISSING_RESOURCE)
    assertContains(refused.body, "case_steel")
    assertFalse(packagerLog.exists(), "nothing reaches the packager")
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
    send("DELETE", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice)
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
    assertEquals(401, send("PUT", "/api/ui-builder/v1/designs/aurora/watch-face-publication").code)
    val unpublished =
      send("GET", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice)
    assertEquals(
      false,
      WATCHFACE_PUSH_JSON.decodeFromString(
          WatchFacePushPublicationV1.serializer(),
          unpublished.body,
        )
        .published,
    )
    watchCatalogs = emptySet()
    val refused =
      send("PUT", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice)
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

  // ------------------------------------------------- the owner's publication route

  @Test
  fun `the owner publishes, re-publishes and unpublishes, as public or private`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "secret", "Secret")
    val aliceToken = link(alice)

    val before = publication("aurora")
    assertFalse(before.published)
    assertEquals("ee.schimke.watchface.push.watchfacepush.aurora", before.packageName)
    assertNull(before.packaging)

    // Private: only the owner's linked watches, though the design itself is public.
    val private = publicationOf(publish(alice, "aurora", "private"))
    assertTrue(private.published)
    assertEquals("private", private.visibility)
    assertEquals("private", private.requestedVisibility)
    assertEquals(1, private.version)
    assertEquals("ready", private.apk?.status)
    assertEquals(emptyList(), designs())
    assertEquals("private", visibilityOf("aurora", aliceToken))

    // Re-published public, unchanged: every watch, at the same version.
    val public = publicationOf(publish(alice, "aurora", "public"))
    assertEquals("public", public.visibility)
    assertEquals(1, public.version)
    assertEquals(listOf("aurora"), designs().map { it.id })
    assertEquals(public, publication("aurora").copy(refusal = null))

    // An edit ships when it is published again, as the next version.
    faces["aurora"] = "<WatchFace id=\"aurora\" edited=\"1\"/>"
    assertEquals(2, publicationOf(publish(alice, "aurora", "public")).version)
    assertEquals(2, designs().single().version)

    // Unpublished: off every watch, the counter kept.
    val off =
      publicationOf(
        send("DELETE", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice),
      )
    assertFalse(off.published)
    assertEquals(2, off.version)
    assertEquals(emptyList(), designs(aliceToken))

    // A private design is never put on every watch; it is published to the owner's.
    val refused = publish(alice, "secret", "public")
    assertEquals(422, refused.code)
    assertContains(refused.body, "design_not_public")
    assertEquals("private", publicationOf(publish(alice, "secret", "private")).visibility)
    assertEquals(400, publish(alice, "secret", "everyone").code)
    assertEquals(listOf("secret"), designs(aliceToken).map { it.id })
  }

  @Test
  fun `only the owner in their own session may use the publication route`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    val path = "/api/ui-builder/v1/designs/aurora/watch-face-publication"
    val agent = mapOf("X-Test-Agent-For" to alice)
    for (method in listOf("GET", "PUT", "DELETE")) {
      assertEquals(401, send(method, path).code, method)
      val stranger = send(method, path, actor = bob)
      assertEquals(403, stranger.code, "$method by a non-owner: ${stranger.body}")
      assertContains(stranger.body, "owner_required")
      // A delegated grant, even for the owner, and an agent's own identity: refused, as on /link.
      val delegated = send(method, path, headers = agent)
      assertEquals(403, delegated.code, "$method by a delegated grant: ${delegated.body}")
      assertContains(delegated.body, "owner_session_required")
      assertEquals(403, send(method, path, actor = "agent:fingerprint").code, method)
      assertEquals(
        404,
        send(method, "/api/ui-builder/v1/designs/nope/watch-face-publication", actor = alice).code,
      )
    }
    assertFalse(push.store.publication("aurora")?.published ?: false)
    assertEquals(200, publish(alice, "aurora").code)
  }

  @Test
  fun `a refused publish is a 422 with the packager's diagnostics, kept on the status`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    faces["aurora"] = "<WatchFace id=\"aurora\" state=\"bad\"/>"
    val refused = publish(alice, "aurora", "public")
    assertEquals(422, refused.code)
    val error =
      WATCHFACE_PUSH_JSON.decodeFromString(WatchFacePushErrorV1.serializer(), refused.body)
    assertEquals("packaging_failed", error.error)
    assertEquals(
      listOf("error[wff-validator]: bad face"),
      error.diagnostics?.filter { "bad face" in it },
    )
    val status = publication("aurora")
    assertFalse(status.published)
    assertContains(status.refusal.orEmpty().joinToString("\n"), "bad face")
    // Fixed, the refusal is gone.
    faces["aurora"] = "<WatchFace id=\"aurora\"/>"
    assertEquals(200, publish(alice, "aurora", "public").code)
    assertNull(publication("aurora").refusal)
  }

  @Test
  fun `the publication route is rate limited per account`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    repeat(30) {
      assertEquals(
        200,
        send("GET", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice).code,
      )
    }
    val limited =
      send("GET", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice)
    assertEquals(429, limited.code)
    assertNotNull(limited.headers["Retry-After"])
  }

  @Test
  fun `the editor is offered publishing only for a watch-face design, and only its owner may`() {
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    fun capability(actor: String? = null, headers: Map<String, String> = emptyMap()) =
      Json.parseToJsonElement(
          send(
              "GET",
              "/api/ui-builder/v1/designs/aurora/dwf-preview/status",
              actor = actor,
              headers = headers,
            )
            .body,
        )
        .jsonObject["watchFacePush"]
        ?.jsonObject
    val owner = capability(alice)!!
    assertEquals(
      "/api/ui-builder/v1/designs/aurora/watch-face-publication",
      owner["publicationPath"]!!.jsonPrimitive.content,
    )
    assertEquals("true", owner["canPublish"]!!.jsonPrimitive.content)
    assertEquals("false", capability(bob)!!["canPublish"]!!.jsonPrimitive.content)
    assertEquals(
      "false",
      capability(headers = mapOf("X-Test-Agent-For" to alice))!!["canPublish"]!!
        .jsonPrimitive
        .content,
    )
    watchCatalogs = emptySet()
    assertNull(capability(alice))
  }

  @Test
  fun `a host that cannot build offers no publishing`() {
    start(
      packaging = {
        WatchFacePushPackaging.Unavailable(WatchFacePushPackaging.SIGNING_KEY_MISSING, "no key")
      },
    )
    createDesign(alice, "aurora", "Aurora", public = true)
    val status =
      Json.parseToJsonElement(
          send("GET", "/api/ui-builder/v1/designs/aurora/dwf-preview/status", actor = alice).body,
        )
        .jsonObject
    assertNull(status["watchFacePush"])
    assertEquals(WatchFacePushPackaging.SIGNING_KEY_MISSING, publication("aurora").packaging?.code)
  }

  // ------------------------------------------------- public designs, listed on their own

  @Test
  fun `a public watch-face design is listed and downloadable anonymously, a private one is not`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "secret", "Secret")
    val aliceToken = link(alice)

    // A listing never builds: however often a watch asks, nothing runs until the design settles.
    repeat(3) { assertEquals(emptyList(), designs()) }
    assertFalse(packagerLog.exists())
    assertTrue(push.automatic.isPending("aurora"))
    settle()
    val aurora = designs().single()
    assertEquals("aurora", aurora.id)
    assertEquals("public", visibilityOf("aurora"))
    assertEquals(1, aurora.version)
    assertEquals("ee.schimke.watchface.push.watchfacepush.aurora", aurora.packageName)
    assertEquals("fake-apk:aurora:1", send("GET", pathOf(aurora.apkUrl)).body)
    assertEquals(200, send("GET", pathOf(aurora.previewUrl)).code)
    // The private design is on nobody's watch, its owner's included: it was never published.
    assertEquals(listOf("aurora"), settledDesigns(aliceToken).map { it.id })
    assertEquals(404, send("GET", "$api/designs/secret/apk").code)
    assertEquals(404, send("GET", "$api/designs/secret/apk", bearer = aliceToken).code)
    val status = publication("aurora")
    assertTrue(status.published)
    assertTrue(status.automatic)
    assertEquals("public", status.visibility)

    val pushStatus = Json.parseToJsonElement(send("GET", "$api/status").body).jsonObject
    assertEquals("1", pushStatus["designs"]!!.jsonObject["listed"]!!.jsonPrimitive.content)

    // Made private, it leaves every list at once, its owner's watch included.
    setVisibility(alice, "aurora", public = false)
    assertEquals(emptyList(), designs())
    assertEquals(emptyList(), designs(aliceToken))
    assertEquals(404, send("GET", pathOf(aurora.apkUrl)).code)
    assertNull(publication("aurora").visibility)
    // Public again, back as it was, with no rebuild.
    setVisibility(alice, "aurora", public = true)
    assertEquals(listOf("aurora"), settledDesigns().map { it.id })
    assertEquals(1, packagerLog.readLines().size)
  }

  @Test
  fun `an edit to a public design is listed as the next version, only when the face changed`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    assertEquals(1, settledDesigns().single().version)
    // A new revision whose face is the same: same version, no rebuild.
    edit(alice, "aurora", face = null, title = "Aurora two")
    val renamed = settledDesigns().single()
    assertEquals(1, renamed.version)
    // A new revision whose face changed: the next version, never one reused.
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" edited=\"1\"/>", title = "Aurora three")
    val edited = settledDesigns().single()
    assertEquals(2, edited.version)
    assertEquals("Aurora three", edited.title)
    assertEquals("fake-apk:aurora:2", send("GET", pathOf(edited.apkUrl)).body)
    assertEquals(listOf("1", "2"), packagerLog.readLines().map { it.split(' ')[2] })
  }

  @Test
  fun `a public design the packager refuses is skipped on status, and its last version stays`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "broken", "Broken", public = true)
    faces["broken"] = "<WatchFace id=\"broken\" state=\"bad\"/>"
    assertEquals(listOf("aurora"), settledDesigns().map { it.id })
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" state=\"bad\"/>")
    assertEquals(1, settledDesigns().single().version)

    val skipped =
      Json.parseToJsonElement(send("GET", "$api/status").body)
        .jsonObject["designs"]!!
        .jsonObject["skipped"]!!
        .toString()
    assertContains(skipped, "\"aurora\"")
    assertContains(skipped, "\"broken\"")
    assertContains(skipped, "bad face")
    assertContains(publication("broken").refusal.orEmpty().joinToString(), "bad face")
    // Built once each: a refused revision is not re-run on every listing.
    settledDesigns()
    assertEquals(1, packagerLog.readLines().size)
  }

  @Test
  fun `an explicit publication wins over the automatic one, keeping its package and version`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    val aliceToken = link(alice)
    val automatic = settledDesigns().single()

    // Kept to the owner's watch: off everyone else's, though the design is public.
    val private = publicationOf(publish(alice, "aurora", "private"))
    assertFalse(private.automatic)
    assertEquals(automatic.version, private.version)
    assertEquals(automatic.packageName, private.packageName)
    assertEquals(emptyList(), settledDesigns())
    assertEquals("private", visibilityOf("aurora", aliceToken))

    // Pinned: a later edit is not shipped until the owner publishes again.
    publish(alice, "aurora", "public")
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" edited=\"1\"/>")
    assertEquals(1, settledDesigns().single().version)

    // Unpublished by the owner: off every watch, though public.
    send("DELETE", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice)
    assertEquals(emptyList(), settledDesigns())
    assertEquals(emptyList(), designs(aliceToken))
    // Published again, the edit ships above every version already out.
    assertEquals(2, publicationOf(publish(alice, "aurora")).version)
  }

  @Test
  fun `a purged public design is retired, and one recreated under its id starts above it`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    assertEquals(1, settledDesigns().single().version)
    val admin =
      ServeUiBuilderAdmin(service = service, retireWatchPublication = { push.store.retire(it) })
    assertIs<ServeUiBuilderAdmin.Result.Deleted>(admin.delete("aurora"))
    assertEquals(emptyList(), settledDesigns())
    createDesign(bob, "aurora", "Bob's", public = true)
    faces["aurora"] = "<WatchFace id=\"bobs\"/>"
    val bobs = settledDesigns().single()
    assertEquals(2, bobs.version)
    assertEquals("Bob's", bobs.title)
    assertEquals(bob, push.store.publication("aurora")?.accountActorId)
  }

  @Test
  fun `an owner's unpublish before the first automatic build keeps the design off watches`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    val off =
      publicationOf(
        send("DELETE", "/api/ui-builder/v1/designs/aurora/watch-face-publication", actor = alice),
      )
    assertFalse(off.published)
    assertNull(off.version)
    assertEquals(emptyList(), settledDesigns())
    assertFalse(packagerLog.exists(), "nothing was built for an opted-out design")
    // Published later, it starts at version 1.
    assertEquals(1, publicationOf(publish(alice, "aurora")).version)
  }

  @Test
  fun `a public design whose build failed for a passing reason is built again`() {
    automaticListing = true
    val ready = readyPackaging()().let { it as WatchFacePushPackaging.Ready }
    var failures = 1
    val flaky = WatchFacePushPackager { files, faceName, versionCode, out ->
      if (failures-- > 0) throw java.io.IOException("No space left on device")
      ready.packager.pack(files, faceName, versionCode, out)
    }
    start(packaging = { WatchFacePushPackaging.Ready(flaky, "c0ffee") })
    createDesign(alice, "aurora", "Aurora", public = true)
    // A failure around the packager is not a refusal: it is tried again, later.
    assertEquals(emptyList(), settledDesigns())
    assertNull(publication("aurora").refusal)
    assertTrue(push.automatic.isPending("aurora"))
    assertEquals(listOf("aurora"), settledDesigns().map { it.id })
    assertNull(publication("aurora").refusal)
  }

  @Test
  fun `a public design recreated under its id at the same revision is listed without a gap`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    assertEquals(1, settledDesigns().single().version)
    // Purged and recreated with no listing in between: same id, same revision, another design.
    val admin =
      ServeUiBuilderAdmin(service = service, retireWatchPublication = { push.store.retire(it) })
    assertIs<ServeUiBuilderAdmin.Result.Deleted>(admin.delete("aurora"))
    now += 1_000
    createDesign(bob, "aurora", "Bob's", public = true)
    faces["aurora"] = "<WatchFace id=\"bobs\"/>"
    val bobs = settledDesigns().single()
    assertEquals("Bob's", bobs.title)
    assertEquals(2, bobs.version)
  }

  @Test
  fun `a live editing session builds once, when the design settles`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    assertEquals(1, settledDesigns().single().version)
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" edited=\"1\"/>", title = "one")
    now += 5 * 60 * 1000L
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" edited=\"2\"/>", title = "two")
    // Ten minutes after the first edit, but five after the last: not yet.
    now += 6 * 60 * 1000L
    runBlocking { push.automatic.runDue(service) }
    assertEquals(1, designs().single().version)
    now += 5 * 60 * 1000L
    runBlocking { push.automatic.runDue(service) }
    assertEquals(2, designs().single().version)
    assertEquals(listOf("1", "2"), packagerLog.readLines().map { it.split(' ')[2] })
  }

  @Test
  fun `a refusal is kept across a restart and not built again`() {
    automaticListing = true
    start()
    createDesign(alice, "broken", "Broken", public = true)
    faces["broken"] = "<WatchFace id=\"broken\" state=\"bad\"/>"
    settle()
    var packs = 0
    val reopened =
      WatchFacePush(
        store = WatchFacePushStore(dir.resolve("watchface-push"), clock = { now }),
        packaging = {
          val ready = push.packaging() as WatchFacePushPackaging.Ready
          WatchFacePushPackaging.Ready(
            WatchFacePushPackager { files, face, version, out ->
              packs++
              ready.packager.pack(files, face, version, out)
            },
            "c0ffee",
          )
        },
        sourceFor = push.sourceFor,
        isWatchFaceCatalog = push.isWatchFaceCatalog,
        designSummary = { service.adminDesignSummary(it) },
        automaticTickMillis = null,
        automaticBuilds = true,
        clock = { now },
      )
    assertContains(reopened.automatic.refusal("broken").orEmpty().joinToString(), "bad face")
    reopened.automatic.noteChanged("broken", access = true)
    now += WatchFacePushAutomatic.DEFAULT_SETTLE_MILLIS + 1
    runBlocking { reopened.automatic.runDue(service) }
    assertEquals(0, packs, "the refused revision was built again")
    assertFalse(reopened.automatic.isPending("broken"))
  }

  @Test
  fun `with watchFacePushBuilds off, a public design is never built on its own`() {
    automaticListing = false
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" edited=\"1\"/>")
    assertFalse(push.automatic.isPending("aurora"))
    assertEquals(emptyList(), settledDesigns())
    runBlocking { push.automatic.sweep(service) }
    assertEquals(emptyList(), designs())
    assertFalse(packagerLog.exists(), "the packager ran with the flag off")
    // The owner's explicit publish still works.
    assertEquals(200, publish(alice, "aurora", "public").code)
    assertEquals(listOf("aurora"), designs().map { it.id })
  }

  @Test
  fun `the startup sweep builds public watch-face designs that had no event, and only those`() {
    automaticListing = true
    start()
    // Created behind the feed's back, as designs made public before this host built them were.
    fun quietly(owner: String, id: String, public: Boolean) = runBlocking {
      service.execute(
        UiBuilderServiceCall(
          AuthenticatedUiBuilderActor(owner),
          UiBuilderServiceRequest.CreateDesign(document(id, id)),
        ),
      )
      if (public) {
        service.setDesignVisibility(
          AuthenticatedUiBuilderActor(owner),
          id,
          UiBuilderDefaultVisibility.PUBLIC,
        )
      }
    }
    quietly(alice, "aurora", public = true)
    quietly(alice, "secret", public = false)
    quietly(bob, "pulse", public = true)
    assertFalse(push.automatic.isPending("aurora"))
    runBlocking { push.automatic.sweep(service) }
    assertEquals(listOf("aurora", "pulse"), designs().map { it.id }.sorted())
    // Run again (a restart), it builds nothing: every current revision has its APK.
    val built = packagerLog.readLines().size
    runBlocking { push.automatic.sweep(service) }
    assertEquals(built, packagerLog.readLines().size)
  }

  @Test
  fun `the designs on disk are read from the per-design store's headers`() = runBlocking {
    val state = dir.resolve("design-store")
    val store =
      PersistentUiBuilderService(
        designStore = UiBuilderDesignStateStore.open(state),
        catalogs = benchmarkCatalogExecutor(),
        exporter = UiBuilderExportExecutor { error("no export in this test") },
      )
    for (id in listOf("aurora", "my-face/odd")) {
      assertIs<UiBuilderServiceResponse.Snapshot>(
        store.execute(
          UiBuilderServiceCall(
            AuthenticatedUiBuilderActor(alice),
            UiBuilderServiceRequest.CreateDesign(document(id, id)),
          ),
        ),
      )
    }
    assertEquals(
      listOf(
        WatchFacePushStoredDesigns.Head("aurora", "m3-catalog"),
        WatchFacePushStoredDesigns.Head("my-face/odd", "m3-catalog"),
      ),
      WatchFacePushStoredDesigns.read(state),
    )
    assertEquals(emptyList(), WatchFacePushStoredDesigns.read(dir.resolve("nowhere")))
  }

  @Test
  fun `a publish answers 202 at once and its build shows its progress until it is built`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val ready = readyPackaging()() as WatchFacePushPackaging.Ready
    val slow = WatchFacePushPackager { files, face, version, out ->
      release.await(20, TimeUnit.SECONDS)
      ready.packager.pack(files, face, version, out)
    }
    start(packaging = { WatchFacePushPackaging.Ready(slow, "c0ffee") })
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "pulse", "Pulse", public = true)

    val first = put(alice, "aurora", "public")
    assertEquals(202, first.code, first.body)
    val queued = publicationOf(first.copy(code = 200))
    assertFalse(queued.published)
    assertContains(listOf("queued", "building"), queued.build?.state)
    // A second one waits behind it.
    val second = publicationOf(put(alice, "pulse", "public").copy(code = 200))
    assertEquals("queued", second.build?.state)
    assertEquals(1, second.build?.queueAhead)
    val building = publication("aurora").build!!
    assertEquals("building", building.state)
    assertEquals("packaging", building.stage)
    assertNotNull(building.startedAtEpochMillis)
    val summary = Json.parseToJsonElement(send("GET", "$api/status").body).jsonObject["builds"]!!
    assertEquals("1", summary.jsonObject["building"]!!.jsonPrimitive.content)
    assertEquals("1", summary.jsonObject["queued"]!!.jsonPrimitive.content)

    release.countDown()
    runBlocking { push.queue.awaitIdle() }
    val built = publication("aurora")
    assertTrue(built.published)
    assertEquals("built", built.build?.state)
    assertEquals(1, built.build?.version)
    assertEquals(listOf("aurora", "pulse"), designs().map { it.id }.sorted())
  }

  @Test
  fun `an automatic build shows as pending until the design settles`() {
    automaticListing = true
    start()
    createDesign(alice, "aurora", "Aurora", public = true)
    settle()
    edit(alice, "aurora", face = "<WatchFace id=\"aurora\" edited=\"1\"/>")
    val pending = publication("aurora").build!!
    assertEquals("pending", pending.state)
    assertTrue(pending.automatic)
    assertEquals(now + WatchFacePushAutomatic.DEFAULT_SETTLE_MILLIS, pending.scheduledAtEpochMillis)
    settle()
    val built = publication("aurora").build!!
    assertEquals("built", built.state)
    assertEquals(2, built.version)
  }

  @Test
  fun `a refusal is not named on status once its design is private or gone`() {
    automaticListing = true
    start()
    createDesign(alice, "broken", "Broken", public = true)
    faces["broken"] = "<WatchFace id=\"broken\" state=\"bad\"/>"
    settle()
    fun skipped() =
      Json.parseToJsonElement(send("GET", "$api/status").body)
        .jsonObject["designs"]!!
        .jsonObject["skipped"]!!
        .toString()
    assertContains(skipped(), "\"broken\"")
    setVisibility(alice, "broken", public = false)
    assertFalse("broken" in skipped(), "a private design's id and diagnostics are on /status")
    // Its owner still sees why.
    assertContains(publication("broken").refusal.orEmpty().joinToString(), "bad face")
    setVisibility(alice, "broken", public = true)
    assertContains(skipped(), "\"broken\"")
    assertTrue(service.adminDeleteDesign("broken"))
    assertFalse("broken" in skipped(), "a deleted design is on /status")
  }

  @Test
  fun `a startup sweep build that failed for a passing reason is tried again`() {
    automaticListing = true
    val ready = readyPackaging()() as WatchFacePushPackaging.Ready
    var failures = 1
    val flaky = WatchFacePushPackager { files, faceName, versionCode, out ->
      if (failures-- > 0) throw java.io.IOException("No space left on device")
      ready.packager.pack(files, faceName, versionCode, out)
    }
    start(packaging = { WatchFacePushPackaging.Ready(flaky, "c0ffee") })
    runBlocking {
      service.execute(
        UiBuilderServiceCall(
          AuthenticatedUiBuilderActor(alice),
          UiBuilderServiceRequest.CreateDesign(document("aurora", "Aurora")),
        ),
      )
      service.setDesignVisibility(
        AuthenticatedUiBuilderActor(alice),
        "aurora",
        UiBuilderDefaultVisibility.PUBLIC,
      )
      push.automatic.sweep(service)
    }
    assertEquals(emptyList(), designs())
    assertTrue(push.automatic.isPending("aurora"))
    val failed = publication("aurora").build!!
    assertEquals("failed", failed.state)
    assertNotNull(failed.nextRetryAtEpochMillis)
    assertEquals(listOf("aurora"), settledDesigns().map { it.id })
  }

  @Test
  fun `an unpublish cancels a publish still queued or building`() {
    val release = java.util.concurrent.CountDownLatch(1)
    val ready = readyPackaging()() as WatchFacePushPackaging.Ready
    val slow = WatchFacePushPackager { files, face, version, out ->
      release.await(20, TimeUnit.SECONDS)
      ready.packager.pack(files, face, version, out)
    }
    start(packaging = { WatchFacePushPackaging.Ready(slow, "c0ffee") })
    createDesign(alice, "aurora", "Aurora", public = true)
    createDesign(alice, "pulse", "Pulse", public = true)
    assertEquals(202, put(alice, "aurora", "public").code)
    assertEquals(202, put(alice, "pulse", "public").code)
    // aurora is building, pulse is queued behind it; both are unpublished now.
    val path = "/api/ui-builder/v1/designs/%s/watch-face-publication"
    assertEquals(200, send("DELETE", path.format("aurora"), actor = alice).code)
    assertEquals(200, send("DELETE", path.format("pulse"), actor = alice).code)
    release.countDown()
    runBlocking { push.queue.awaitIdle() }
    assertEquals(emptyList(), designs())
    assertFalse(publication("aurora").published)
    assertNull(publication("aurora").build)
    assertFalse(publication("pulse").published)
    // pulse was never built: it was cancelled before its turn.
    assertEquals(1, packagerLog.readLines().size)
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
      val packageName: String? = null,
      val activation: String? = null,
      val activationError: String? = null,
      val deviceModel: String? = null,
      val wearOsVersion: String? = null,
      val watchFacePushApiVersion: String? = null,
      val appVersion: String? = null,
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
