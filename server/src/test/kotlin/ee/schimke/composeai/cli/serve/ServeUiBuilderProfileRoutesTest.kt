package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignActorAccessV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.DesignsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ErrorResponseV1
import ee.schimke.composeai.uibuilder.protocol.HttpRequestEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.HttpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderResponseV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * A white-labelled builder on one more hostname ([ServeUiBuilderProfile]): on `ha.coo.ee` the
 * builder wears the profile's name, links to nothing of compose-preview's, lists and creates only
 * the profile's catalogs, and a sign-in may come back to it. Every other host is unchanged.
 */
class ServeUiBuilderProfileRoutesTest {
  private val created = CopyOnWriteArrayList<String>()

  private fun capability(systemId: String) =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(systemId, "source", systemId, "candidate", "candidate").build(),
        emptyList(),
      )
      .build()

  private fun listItem(id: String, title: String, catalog: String) =
    DesignListItemV1(
      designId = id,
      title = title,
      revision = 1,
      accessRevision = 1,
      catalogPin = CatalogReferenceV1(catalog, "rev", "rev", "runtime"),
      createdAtEpochMillis = 1_000,
      updatedAtEpochMillis = 2_000,
      ownerActorId = "github:octocat",
      requesterAccess =
        DesignActorAccessV1(
          actorId = "github:octocat",
          role = DesignAccessRoleV1.OWNER,
          allowedActions = DesignAccessActionV1.entries,
        ),
    )

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.ListCatalogs ->
            UiBuilderServiceResponse.Catalogs(
              listOf(capability("m3-catalog"), capability("remote-m3"))
            )
          is UiBuilderServiceRequest.ListDesigns ->
            UiBuilderServiceResponse.Designs(
              listOf(
                listItem("living-room-card", "Living room card", "remote-m3"),
                listItem("phone-settings", "Phone settings", "m3-catalog"),
              ),
              nextCursor = null,
            )
          is UiBuilderServiceRequest.CreateDesign -> {
            created += request.document.id
            UiBuilderServiceResponse.Catalogs(emptyList())
          }
          is UiBuilderServiceRequest.ListTrash -> UiBuilderServiceResponse.Trash(emptyList())
          else ->
            UiBuilderServiceResponse.Error(
              UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "missing")
            )
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val authorization = ServeUiBuilderAuthorization { call, _, _ ->
    when (val actor = call.request.headers["X-Test-Actor"]) {
      null -> UiBuilderAuthorizationDecision.Missing
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }

  private val builderDir =
    Files.createTempDirectory("serve-ui-builder-profile").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html")
        .writeText("<!doctype html><html><head><title>Compose UI builder</title></head></html>")
      File(dir, ServeUiBuilderCreate.NEW_DESIGN_FIXTURE)
        .writeText(
          File("../docs/design/fixtures/ui-builder/${ServeUiBuilderCreate.NEW_DESIGN_FIXTURE}")
            .takeIf { it.isFile }
            ?.readText()
            ?: File("docs/design/fixtures/ui-builder/${ServeUiBuilderCreate.NEW_DESIGN_FIXTURE}")
              .readText()
        )
    }

  private val profile =
    ServeUiBuilderProfile.of(
      host = "ha.coo.ee",
      name = "Home Assistant card designer",
      catalogs = listOf("remote-m3"),
      accent = "#03A9F4",
      homeUrl = "https://ha.coo.ee/",
    )

  private val fakeGitHub =
    OkHttpClient.Builder()
      .addInterceptor { chain ->
        val request = chain.request()
        val body =
          when (request.url.encodedPath) {
            "/login/oauth/access_token" -> """{"access_token":"token"}"""
            "/user" -> """{"login":"octo"}"""
            else -> """{"private":false,"permissions":{"push":true}}"""
          }
        okhttp3.Response.Builder()
          .request(request)
          .protocol(okhttp3.Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(body.toResponseBody("application/json".toMediaType()))
          .build()
      }
      .build()

  private val githubAuth =
    ServeGithubAuth(
      ServeGithubAuthConfig(
        clientId = "client",
        clientSecret = "secret",
        cookieSecret = "x".repeat(32),
        repository = "yschimke/compose-ai-tools",
        callbackBaseUrl = "https://preview.coo.ee",
        cookieDomain = "coo.ee",
      ),
      verifier = GitHubOAuthVerifier(fakeGitHub),
    )

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        canonicalOrigin = "https://preview.coo.ee",
        token = "unused-in-public",
        isPublic = true,
        sessions = registry,
        defaultSessionId = "unused",
        githubAuth = githubAuth,
        uiBuilderDir = builderDir,
        uiBuilderCatalogs = setOf("m3-catalog", "remote-m3"),
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
        uiBuilderProfile = profile,
        uiBuilderStartUrl = BUILDER_GUIDE,
        cliStartUrl = CLI_GUIDE,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun get(
    path: String,
    host: String,
    actor: String? = "github:octocat",
  ): Pair<Int, String> {
    val builder =
      Request.Builder().url(url(path)).header("Host", host).header("Accept", "text/html")
    actor?.let { builder.header("X-Test-Actor", it) }
    return client.newCall(builder.get().build()).execute().use { it.code to it.body.string() }
  }

  private fun location(path: String, host: String): Pair<Int, String?> =
    client
      .newCall(Request.Builder().url(url(path)).header("Host", host).get().build())
      .execute()
      .use { it.code to it.header("Location") }

  /** The first starting point the main host's New design form offers for [catalog]. */
  private fun startFor(catalog: String): String =
    Regex("value=\"(${Regex.escape(catalog)}\\|[^\"]+)\"")
      .find(get("/ui-builder/designs", "preview.coo.ee").second)!!
      .groupValues[1]

  private fun create(catalog: String, host: String): Int =
    client
      .newCall(
        Request.Builder()
          .url(url("/ui-builder/designs"))
          .header("Host", host)
          .header("Origin", "https://$host")
          .header("X-Test-Actor", "github:octocat")
          .post(
            FormBody.Builder()
              .add("start", startFor(catalog))
              .add("designId", "new-$catalog")
              .build()
          )
          .build()
      )
      .execute()
      .use { it.code }

  private fun protocol(request: UiBuilderRequestV1, host: String): Pair<Int, UiBuilderResponseV1> {
    val envelope =
      HttpRequestEnvelopeV1(
        requestId = "r-${System.nanoTime()}",
        actorId = "github:octocat",
        request = request,
      )
    return client
      .newCall(
        Request.Builder()
          .url(url("/api/ui-builder/v1/requests"))
          .header("Host", host)
          .header("X-Test-Actor", "github:octocat")
          .post(
            UI_BUILDER_JSON.encodeToString(HttpRequestEnvelopeV1.serializer(), envelope)
              .toRequestBody("application/json".toMediaType())
          )
          .build()
      )
      .execute()
      .use {
        it.code to
          UI_BUILDER_JSON.decodeFromString(HttpResponseEnvelopeV1.serializer(), it.body.string())
            .response
      }
  }

  private fun document(id: String, catalog: String) =
    UI_BUILDER_JSON.decodeFromString(
      ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1.serializer(),
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"$id","title":"$id","revision":0,
       "catalogPin":{"systemId":"$catalog","catalogRevision":"candidate",
       "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":360,"heightDp":160,"density":2.0,"theme":"light","locale":"en-US",
       "fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":[],"nodes":{}}
      """
        .trimIndent(),
    )

  @Test
  fun `published templates respect the host catalog scope`() {
    assertEquals(404, get("/api/ui-builder/v1/catalogs/m3-catalog/templates", "ha.coo.ee").first)
    assertEquals(200, get("/api/ui-builder/v1/catalogs/remote-m3/templates", "ha.coo.ee").first)
    assertEquals(
      200,
      get("/api/ui-builder/v1/catalogs/m3-catalog/templates", "preview.coo.ee").first,
    )
  }

  @Test
  fun `start on the profile host is the builder's guide, elsewhere the CLI's`() {
    // A profile host is the builder, as its `/` is; it never sends a visitor to the CLI guide.
    assertEquals(302 to BUILDER_GUIDE, location("/start", "ha.coo.ee"))
    assertEquals(302 to CLI_GUIDE, location("/start", "preview.coo.ee"))
  }

  @Test
  fun `a profile host without a builder host or CLI guide still serves the builder's start`() {
    val profileOnly =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          canonicalOrigin = "https://preview.coo.ee",
          token = "unused-in-public",
          isPublic = true,
          sessions = registry,
          defaultSessionId = "unused",
          githubAuth = githubAuth,
          uiBuilderDir = builderDir,
          uiBuilderCatalogs = setOf("m3-catalog", "remote-m3"),
          uiBuilderService = service,
          uiBuilderAuthorization = authorization,
          uiBuilderProfile = profile,
          uiBuilderStartUrl = BUILDER_GUIDE,
        )
        .also(ServeHttpServer::start)
    try {
      val response =
        client
          .newCall(
            Request.Builder()
              .url("http://127.0.0.1:${profileOnly.port}/start")
              .header("Host", "ha.coo.ee")
              .build()
          )
          .execute()
      response.use { assertEquals(302 to BUILDER_GUIDE, it.code to it.header("Location")) }
    } finally {
      profileOnly.stop()
    }
  }

  @Test
  fun `the editor shell carries the profile's name, metas and manifest on its host only`() {
    val (code, shell) = get("/ui-builder/", "ha.coo.ee")
    assertEquals(200, code, shell)
    assertTrue("<title>Home Assistant card designer</title>" in shell, shell)
    assertTrue(
      "<meta name=\"ui-builder-brand-name\" content=\"Home Assistant card designer\">" in shell,
      shell,
    )
    assertTrue("<meta name=\"ui-builder-brand-accent\" content=\"#03a9f4\">" in shell, shell)
    assertTrue(
      "<meta name=\"ui-builder-brand-home\" content=\"https://ha.coo.ee/\">" in shell,
      shell,
    )
    assertTrue("<meta name=\"ui-builder-catalogs\" content=\"remote-m3\">" in shell, shell)
    assertTrue("href=\"/ui-builder/manifest.webmanifest\"" in shell, shell)
    assertFalse("Compose UI builder" in shell, shell)

    val (manifestCode, manifest) = get("/ui-builder/manifest.webmanifest", "ha.coo.ee")
    assertEquals(200, manifestCode, manifest)
    assertTrue("\"name\":\"Home Assistant card designer\"" in manifest, manifest)
    assertTrue("\"start_url\":\"/ui-builder/\"" in manifest, manifest)
    assertFalse("share_target" in manifest, manifest)

    val (_, plain) = get("/ui-builder/", "preview.coo.ee")
    assertTrue("<title>Compose UI builder</title>" in plain, plain)
    assertFalse("ui-builder-brand-name" in plain, plain)
    assertFalse("ui-builder-catalogs" in plain, plain)
    assertTrue("href=\"/manifest.webmanifest\"" in plain, plain)
    // Elsewhere the path is what it always was: not the profile's manifest.
    assertFalse(
      "Home Assistant" in get("/ui-builder/manifest.webmanifest", "preview.coo.ee").second
    )
  }

  @Test
  fun `the designs page is branded, lists only the profile's catalogs, and links nowhere else`() {
    val (code, page) = get("/ui-builder/designs", "ha.coo.ee")
    assertEquals(200, code, page)
    assertTrue("<title>Designs — Home Assistant card designer</title>" in page, page)
    assertTrue("Living room card" in page, page)
    assertFalse("Phone settings" in page, page)
    assertTrue("value=\"remote-m3|" in page, page)
    assertFalse("value=\"m3-catalog|" in page, page)
    assertTrue("href=\"https://ha.coo.ee/\"" in page, page)
    assertFalse("compose-preview" in page, page)
    assertFalse("href=\"/status" in page, page)
    assertFalse("action=\"/report-bug\"" in page, page)
    assertFalse("cp-site-footer" in page, page)
    assertTrue("--md-sys-color-primary: #03a9f4" in page, page)

    val (_, plain) = get("/ui-builder/designs", "preview.coo.ee")
    assertTrue("<title>Designs — compose-preview</title>" in plain, plain)
    assertTrue("Living room card" in plain && "Phone settings" in plain, plain)
    assertTrue("value=\"m3-catalog|" in plain, plain)
    assertTrue("href=\"/status" in plain, plain)
  }

  @Test
  fun `creating into another catalog is refused on the profile host and allowed elsewhere`() {
    assertEquals(403, create("m3-catalog", "ha.coo.ee"))
    assertTrue(created.isEmpty(), "$created")
    assertEquals(303, create("remote-m3", "ha.coo.ee"))
    assertEquals(listOf("new-remote-m3"), created.toList())
    assertEquals(303, create("m3-catalog", "preview.coo.ee"))
    assertEquals(listOf("new-remote-m3", "new-m3-catalog"), created.toList())
  }

  @Test
  fun `the protocol lists and creates only the profile's catalogs on its host`() {
    val (_, catalogs) = protocol(ListCatalogsRequestV1, "ha.coo.ee")
    assertEquals(
      listOf("remote-m3"),
      assertIs<CatalogsResponseV1>(catalogs).catalogs.map { it.benchmark.catalogSystemId },
    )
    val (_, designs) = protocol(ListDesignsRequestV1(null, 50), "ha.coo.ee")
    assertEquals(
      listOf("living-room-card"),
      assertIs<DesignsResponseV1>(designs).designs.map { it.designId },
    )
    val (status, refused) =
      protocol(CreateDesignRequestV1(document("phone", "m3-catalog")), "ha.coo.ee")
    assertEquals(403, status)
    assertEquals(ServiceErrorCodeV1.FORBIDDEN, assertIs<ErrorResponseV1>(refused).error.code)
    assertTrue(created.isEmpty(), "$created")

    val (_, everyCatalog) = protocol(ListCatalogsRequestV1, "preview.coo.ee")
    assertEquals(2, assertIs<CatalogsResponseV1>(everyCatalog).catalogs.size)
    val (_, everyDesign) = protocol(ListDesignsRequestV1(null, 50), "preview.coo.ee")
    assertEquals(2, assertIs<DesignsResponseV1>(everyDesign).designs.size)
  }

  @Test
  fun `a PUT into another catalog is refused on the profile host`() {
    val body =
      UI_BUILDER_JSON.encodeToString(
          ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1.serializer(),
          document("phone", "m3-catalog"),
        )
        .toRequestBody("application/json".toMediaType())
    val code =
      client
        .newCall(
          Request.Builder()
            .url(url("/api/ui-builder/v1/designs/phone"))
            .header("Host", "ha.coo.ee")
            .header("X-Test-Actor", "github:octocat")
            .header("If-None-Match", "*")
            .put(body)
            .build()
        )
        .execute()
        .use { it.code }
    assertEquals(403, code)
    assertTrue(created.isEmpty(), "$created")
  }

  @Test
  fun `the profile host's root opens the builder, never the catalogs`() {
    assertEquals(302 to "/ui-builder/", location("/", "ha.coo.ee"))
    assertFalse(location("/", "preview.coo.ee").second == "/ui-builder/")
  }

  @Test
  fun `a profile host that is also a top-level site keeps the site's root`() {
    val siteRegistry = ServeSessionRegistry(open = { null })
    val shared =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          canonicalOrigin = "https://preview.coo.ee",
          token = "unused-in-public",
          isPublic = true,
          sessions = siteRegistry,
          defaultSessionId = "unused",
          uiBuilderDir = builderDir,
          uiBuilderCatalogs = setOf("m3-catalog", "remote-m3"),
          uiBuilderService = service,
          uiBuilderAuthorization = authorization,
          uiBuilderProfile = profile,
          sites = ServeSiteRegistry.of(listOf("ha.coo.ee" to "remote-m3")),
        )
        .also(ServeHttpServer::start)
    try {
      fun at(path: String) =
        client
          .newCall(
            Request.Builder()
              .url("http://127.0.0.1:${shared.port}$path")
              .header("Host", "ha.coo.ee")
              .get()
              .build()
          )
          .execute()
          .use { it.code to it.header("Location") }
      assertFalse(at("/").second == "/ui-builder/", "the site's landing, not the builder")
      assertEquals(302 to "/ui-builder/", at("/ui-builder"), "the builder is still on the host")
    } finally {
      shared.stop()
      siteRegistry.close()
    }
  }

  @Test
  fun `a sign-in may return to an absolute URL on the profile host, and to no other host`() {
    fun signIn(returnTo: String): String? {
      val started =
        client
          .newCall(
            Request.Builder()
              .url(
                url("/auth/github/start?return=" + java.net.URLEncoder.encode(returnTo, "UTF-8"))
              )
              .header("Host", "preview.coo.ee")
              .get()
              .build()
          )
          .execute()
          .use {
            assertEquals(302, it.code)
            it.header("Location").orEmpty() to
              it.headers("Set-Cookie").first { c ->
                c.startsWith("cp_gh_state=") && c.contains("domain=coo.ee", ignoreCase = true)
              }
          }
      val state = started.first.substringAfter("state=").substringBefore("&")
      return client
        .newCall(
          Request.Builder()
            .url(url("/auth/github/callback?code=ok&state=$state"))
            .header("Host", "preview.coo.ee")
            .header("Cookie", started.second.substringBefore(";"))
            .get()
            .build()
        )
        .execute()
        .use {
          assertEquals(302, it.code)
          it.header("Location")
        }
    }

    assertEquals(
      "https://ha.coo.ee/ui-builder/designs?x=1",
      signIn("https://ha.coo.ee/ui-builder/designs?x=1"),
    )
    // Anything else absolute is the plain `/` it always was.
    assertEquals("/", signIn("https://evil.example/ui-builder/"))
    assertEquals("/", signIn("https://evil.coo.ee/ui-builder/"))
    assertEquals("/ui-builder/x", signIn("/ui-builder/x"))

    assertEquals(
      "ha.coo.ee" to "/ui-builder/designs?x=1",
      githubAuth.absoluteReturn("https://ha.coo.ee/ui-builder/designs?x=1", setOf("ha.coo.ee")),
    )
    assertNull(githubAuth.absoluteReturn("https://evil.example/x", setOf("ha.coo.ee")))
    assertNull(githubAuth.absoluteReturn("http://ha.coo.ee/x", setOf("ha.coo.ee")))
    assertNull(githubAuth.absoluteReturn("https://user@ha.coo.ee/x", setOf("ha.coo.ee")))
    assertNull(githubAuth.absoluteReturn("https://ha.coo.ee:8443/x", setOf("ha.coo.ee")))
    assertNull(githubAuth.absoluteReturn("https://ha.coo.ee//evil.example/x", setOf("ha.coo.ee")))
    assertNull(githubAuth.absoluteReturn("/ui-builder/", setOf("ha.coo.ee")))
  }

  @Test
  fun `a profile's flags are checked like the other serve flags`() {
    assertNull(ServeUiBuilderProfile.of(null, null, emptyList(), null, null))
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderProfile.of(null, "Named", emptyList(), null, null)
    }
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderProfile.of("ha.coo.ee", "", listOf("remote-m3"), null, null)
    }
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderProfile.of("ha.coo.ee", "Named", emptyList(), null, null)
    }
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderProfile.of("ha.coo.ee", "Named", listOf("remote-m3"), "blue", null)
    }
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderProfile.of("ha.coo.ee", "Named", listOf("remote-m3"), null, "http://x")
    }
    assertFailsWith<IllegalArgumentException> {
      ServeUiBuilderProfile.of(
        "ui.coo.ee",
        "Named",
        listOf("remote-m3"),
        null,
        null,
        uiBuilderHost = "ui.coo.ee",
      )
    }
    val options =
      ServeCommandOptions(
        args =
          listOf(
            "--ui-builder-profile-host",
            "HA.coo.ee",
            "--ui-builder-profile-name",
            "Home Assistant card designer",
            "--ui-builder-profile-catalogs",
            "remote-m3, remote-m3",
          ),
        defaultTimeoutSeconds = 600L,
        previewMatcher = { _, _, _, _, _, _ -> true },
      )
    assertEquals(
      ServeUiBuilderProfile("ha.coo.ee", "Home Assistant card designer", listOf("remote-m3")),
      options.uiBuilderProfile,
    )
  }

  private companion object {
    const val BUILDER_GUIDE = "https://yschimke.github.io/compose-ui-builder/"
    const val CLI_GUIDE = "https://yschimke.github.io/compose-ai-tools/"
  }
}
