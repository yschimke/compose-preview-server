package ee.schimke.composeai.cli.serve

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * End-to-end check for top-level sites ([ServeSites]) on a real [ServeHttpServer]: three catalogs,
 * with `m3.example.test` published as a site for `compose-m3`. The site host must look like its own
 * server while the main host is untouched, so assertions pair site-host and main-host requests.
 */
class ServeTopLevelSiteTest {

  private val siteHost = "m3.example.test"

  private fun png(): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun bundle(
    label: String,
    previewIds: List<String>,
    title: String,
    /** The catalog's Kotlin source, when the test needs a tracker distinct from the server's. */
    source: ServeWeb.CatalogSource? = null,
  ): ServeBundleHost {
    val dir = Files.createTempDirectory("site-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    previewIds.forEach { File(dir, "previews/$it.png").writeBytes(png()) }
    return ServeBundleHost(
      dir,
      label = label,
      title = title,
      provenance =
        ServeWeb.CatalogProvenance(
          repo = "yschimke/compose-ai-tools",
          branch = "design-artifacts/$label",
          generatedAt = Instant.parse("2026-05-01T00:00:00Z").toString(),
        ),
      catalogSource = source,
      declaredBaked = previewIds,
    )
  }

  /** Bytes a fixture capture serves: readable text, so assertion messages show which 404 it was. */
  private val captureMarker = "fixture-capture-bytes"

  /**
   * [bundle] plus one published capture, wired like [ServeCatalogStore]: declared id, branch path,
   * bytes fetched on first request.
   */
  private fun motionBundle(label: String, previewId: String, motionId: String): ServeBundleHost {
    val dir = Files.createTempDirectory("site-motion-$label").toFile().also { it.deleteOnExit() }
    File(dir, "index.html").writeText("<html></html>")
    File(dir, "previews").apply { mkdirs() }
    File(dir, "previews/$previewId.png").writeBytes(png())
    return ServeBundleHost(
      dir,
      label = label,
      title = label,
      declaredBaked = listOf(previewId),
      declaredMotion = listOf(motionId),
      fetchMotion = { id ->
        if (id == motionId) BranchFetch.Ok(captureMarker.toByteArray()) else BranchFetch.NotFound
      },
      motionBranchPaths = mapOf(motionId to "motion/switch-on/ideal__default__light.apng"),
    )
  }

  private val registry = ServeSessionRegistry(open = { null })
  private var server: ServeHttpServer? = null
  private val client =
    OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

  private fun newServer(trustForwardedFor: Boolean = false): ServeHttpServer {
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled", "switch-on"), "Compose Material 3"),
      pinned = true,
    )
    registry.register("wear-m3", host = bundle("wear-m3", listOf("chip"), "Wear M3"), pinned = true)
    registry.register("cadence", host = bundle("cadence", listOf("beat"), "Cadence"), pinned = true)
    return ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "unused",
        sessions = registry,
        defaultSessionId = "",
        isPublic = true,
        catalogSessions = listOf("compose-m3", "wear-m3"),
        appCatalogSessions = listOf("cadence"),
        sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
        trustForwardedFor = trustForwardedFor,
      )
      .also { it.start() }
  }

  private fun getForwarded(path: String, headers: Map<String, String>): String {
    val req =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    client.newCall(req).execute().use {
      return it.body.string()
    }
  }

  /** A request whose `Host` header is [host] — how a vhost actually reaches this listener. */
  private fun request(path: String, host: String? = null, cookie: String? = null) =
    Request.Builder()
      .url("http://127.0.0.1:${server!!.port}$path")
      .apply {
        if (host != null) header("Host", host)
        if (cookie != null) header("Cookie", cookie)
      }
      .build()

  private fun get(
    path: String,
    host: String? = null,
    cookie: String? = null,
  ): Triple<Int, String, String?> {
    client.newCall(request(path, host, cookie)).execute().use { r ->
      return Triple(r.code, r.body.string(), r.header("Location"))
    }
  }

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  @Test
  fun `the site root is its catalog's landing, not the front door`() {
    server = newServer()
    val (code, body, _) = get("/", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("Compose Material 3"), "the site opens on its own catalog: $body")
    // The neighbours this box also serves are nowhere on the page.
    assertFalse(body.contains("Wear M3"), "a site must not index its neighbours: $body")
    assertFalse(body.contains("Cadence"), "a site must not index its neighbours: $body")
    // …and there is no way back to a front door that doesn't exist on this hostname.
    assertFalse(body.contains("All design systems"), "no back button on a site: $body")

    // The main host is untouched: `/` is still the index of everything.
    val (mainCode, mainBody, _) = get("/")
    assertEquals(200, mainCode)
    assertTrue(mainBody.contains("Wear M3"), "the main front door still lists every system")
  }

  /**
   * A site host installs as its own app (manifest named for its catalog); the main host keeps the
   * box's name. Both ungated, like the icons.
   */
  @Test
  fun `each site host serves a manifest named for its own catalog`() {
    server = newServer()
    val (siteCode, site, _) = get("/manifest.webmanifest", host = siteHost)
    assertEquals(200, siteCode)
    val siteJson = kotlinx.serialization.json.Json.parseToJsonElement(site).jsonObject
    assertEquals("Compose Material 3", siteJson.getValue("name").jsonPrimitive.content)
    assertEquals("compose-m3", siteJson.getValue("short_name").jsonPrimitive.content)
    assertEquals("/", siteJson.getValue("start_url").jsonPrimitive.content)
    assertEquals("/", siteJson.getValue("scope").jsonPrimitive.content)
    assertEquals("/", siteJson.getValue("id").jsonPrimitive.content)

    val (mainCode, main, _) = get("/manifest.webmanifest")
    assertEquals(200, mainCode)
    val mainJson = kotlinx.serialization.json.Json.parseToJsonElement(main).jsonObject
    assertEquals("Compose Preview", mainJson.getValue("name").jsonPrimitive.content)

    // The manifest's screenshots and icons (including the monochrome badge the push worker
    // requests) are served on the site host too.
    for (path in
      listOf(
        "/icons/screenshot-narrow.png",
        "/icons/screenshot-wide.png",
        ServeSiteIcon.APP_ICON_192_PATH,
        ServeSiteIcon.BADGE_PATH,
      )) {
      assertEquals(200, get(path, host = siteHost).first, path)
    }
  }

  @Test
  fun `X-Forwarded-Host picks a site only when the proxy is trusted`() {
    server = newServer()
    val headers = mapOf("Host" to "preview.example.test", "X-Forwarded-Host" to siteHost)
    // A direct caller chooses its own forwarded headers, so without the proxy switch they are
    // ignored and `Host` decides: this is the main host's front door.
    assertTrue(getForwarded("/", headers).contains("Wear M3"), "untrusted: the main front door")
    server!!.stop()

    server = newServer(trustForwardedFor = true)
    val body = getForwarded("/", headers)
    assertTrue(body.contains("Compose Material 3"), "trusted: the site's own landing: $body")
    assertFalse(body.contains("Wear M3"), "trusted: the site's own landing: $body")
  }

  @Test
  fun `absolute links take the forwarded host and scheme only from a trusted proxy`() {
    val headers =
      mapOf(
        "Host" to "preview.example.test",
        "X-Forwarded-Host" to "elsewhere.example.test",
        "X-Forwarded-Proto" to "https",
      )
    server = newServer()
    val direct = getForwarded("/sitemap.xml", headers)
    assertTrue(direct.contains("<loc>http://preview.example.test/</loc>"), direct)
    assertFalse(direct.contains("elsewhere.example.test"), direct)
    server!!.stop()

    server = newServer(trustForwardedFor = true)
    val proxied = getForwarded("/sitemap.xml", headers)
    assertTrue(proxied.contains("<loc>https://elsewhere.example.test/</loc>"), proxied)
  }

  @Test
  fun `site links stay on the custom domain`() {
    server = newServer()
    val (_, body, _) = get("/", host = siteHost)
    assertTrue(body.contains("\"/p/button-filled\""), "viewer links are rooted: $body")
    assertFalse(
      body.contains("/compose-m3/p/"),
      "a site link must never walk back to the canonical path: $body",
    )

    // Same page on the main host keeps the canonical prefixed form.
    val (_, mainBody, _) = get("/compose-m3/")
    assertTrue(mainBody.contains("\"/compose-m3/p/button-filled\""), mainBody)
  }

  @Test
  fun `the canonical path redirects to the rooted URL on a site host`() {
    server = newServer()
    val (code, _, location) = get("/compose-m3/p/button-filled?theme=dark", host = siteHost)
    assertEquals(308, code)
    assertEquals("/p/button-filled?theme=dark", location)

    // The bare catalog path collapses to the site root.
    assertEquals("/" to 308, get("/compose-m3", host = siteHost).let { it.third to it.first })

    // …and on the main host the same URL is served, not redirected.
    assertEquals(200, get("/compose-m3/p/button-filled").first)
  }

  @Test
  fun `a neighbouring catalog is not reachable through a site host`() {
    server = newServer()
    assertEquals(404, get("/wear-m3/", host = siteHost).first)
    assertEquals(404, get("/cadence/", host = siteHost).first)
    // Both still serve on the main host.
    assertEquals(200, get("/wear-m3/").first)
    assertEquals(200, get("/cadence/").first)
  }

  @Test
  fun `an explicit session query cannot reach past the site host`() {
    server = newServer()
    // The isolation the path 404 gives has to hold for the older `?session=` spelling of the same
    // request, or `/api/previews?session=wear-m3` serves the neighbour `/wear-m3/` refuses.
    val (code, body, _) = get("/api/previews?session=wear-m3", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("button-filled"), "the site's own catalog answers: $body")
    assertFalse(body.contains("\"chip\""), "a query param must not re-point the session: $body")
    // The landing is the site's catalog too, not the one named in the query.
    val (_, landing, _) = get("/?session=cadence", host = siteHost)
    assertTrue(landing.contains("Compose Material 3"), landing)
    assertFalse(landing.contains("Cadence"), landing)
    // On the main host `?session=` still selects, exactly as it always did.
    val (_, mainBody, _) = get("/api/previews?session=wear-m3")
    assertTrue(mainBody.contains("chip"), mainBody)
  }

  @Test
  fun `the canonical redirect is same-origin and method-preserving`() {
    server = newServer()
    // An extra slash after the system would otherwise build `//evil.example` — read by browsers as
    // a protocol-relative URL to another origin, i.e. an open redirect on every site host.
    val (code, _, location) = get("/compose-m3//evil.example", host = siteHost)
    assertEquals(308, code)
    assertEquals("/evil.example", location)
    assertTrue(
      location!!.startsWith("/") && !location.startsWith("//"),
      "the redirect target must be same-origin: $location",
    )
    // 308, not 301: the canonical prefix also carries POST routes, which 301 turns into GET.
    assertEquals(308, get("/compose-m3/p/button-filled", host = siteHost).first)
  }

  @Test
  fun `constant routes are untouched by the site rewrite`() {
    server = newServer()
    assertEquals(200, get("/healthz", host = siteHost).first)
    // A root-mounted session route resolves to the site's catalog rather than 404ing on no session.
    val (code, body, _) = get("/api/previews", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("button-filled"), body)
    assertFalse(body.contains("\"beat\""), "the site's API answers for the site's catalog: $body")
  }

  @Test
  fun `the multi-catalog component index is unavailable on a top-level site`() {
    server = newServer()
    assertEquals(404, get("/api/components", host = siteHost).first)

    val (mainCode, mainBody, _) = get("/api/components")
    assertEquals(200, mainCode)
    assertTrue(mainBody.contains("\"catalog\":\"compose-m3\""), mainBody)
    assertTrue(mainBody.contains("\"catalog\":\"wear-m3\""), mainBody)
  }

  @Test
  fun `the header bar names the catalog on every page`() {
    server = newServer()
    // The header names the design system on every page, not just in the page's <h1>.
    for (path in listOf("/", "/p/button-filled")) {
      val (code, body, _) = get(path, host = siteHost)
      assertEquals(200, code, path)
      assertTrue(
        body.contains("<span class=\"cp-site-catalog\">Compose Material 3</span>"),
        "the header names the catalog on $path: $body",
      )
    }
    // …and on the canonical path too — this is not a site-only affordance.
    val (_, mainBody, _) = get("/wear-m3/")
    assertTrue(mainBody.contains("<span class=\"cp-site-catalog\">Wear M3</span>"), mainBody)
    // The front door belongs to no catalog, so it keeps the bare brand.
    val (_, home, _) = get("/")
    assertFalse(home.contains("cp-site-catalog"), home)
  }

  @Test
  fun `a site's chrome wears its catalog's skin on every page`() {
    server = newServer()
    // A hostname that publishes one design system should not render its /status and its 404 in the
    // built-in chrome beside a themed landing — one hostname, one skin.
    for (path in listOf("/status", "/no-such-page-here")) {
      val (_, body, _) = get(path, host = siteHost)
      assertTrue(
        body.contains("data-cp-theme-key=\"cp-theme:compose-m3\""),
        "the theme choice is shared across the hostname on $path: $body",
      )
      assertTrue(
        body.contains("<span class=\"cp-site-catalog\">Compose Material 3</span>"),
        "the bar names the catalog on $path: $body",
      )
    }
    // The main host's /status belongs to no catalog and is unchanged.
    val (_, mainStatus, _) = get("/status")
    assertFalse(mainStatus.contains("cp-theme:compose-m3"), mainStatus)
    assertFalse(mainStatus.contains("cp-site-catalog"), mainStatus)
  }

  @Test
  fun `a site's styled 404 resolves interface mode from its request`() {
    server = newServer()
    val cookie = "${ServeWeb.INTERFACE_MODE_COOKIE}=catalog"

    val (_, rememberedCatalog, _) = get("/missing", host = siteHost, cookie = cookie)
    assertTrue(rememberedCatalog.contains("class=\"cp-component-browser\""), rememberedCatalog)
    assertTrue(
      rememberedCatalog.contains("data-cp-interface-mode=\"catalog\" aria-pressed=\"true\""),
      rememberedCatalog,
    )

    val (_, pinnedDev, _) = get("/missing?chrome=dev", host = siteHost, cookie = cookie)
    assertFalse(pinnedDev.contains("class=\"cp-component-browser\""), pinnedDev)
    assertTrue(
      pinnedDev.contains("data-cp-interface-mode=\"dev\" aria-pressed=\"true\""),
      pinnedDev,
    )
  }

  @Test
  fun `status reports on the site's app only`() {
    server = newServer()
    val (code, body, _) = get("/status.json", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("\"id\":\"compose-m3\""), body)
    assertFalse(body.contains("\"id\":\"wear-m3\""), "a site's status is its own: $body")
    assertFalse(body.contains("\"id\":\"cadence\""), "a site's status is its own: $body")

    // The main host still reports the whole box.
    val (_, mainBody, _) = get("/status.json")
    assertTrue(mainBody.contains("\"id\":\"wear-m3\""), mainBody)
  }

  @Test
  fun `the sitemap is scoped and rooted`() {
    server = newServer()
    val (code, body, _) = get("/sitemap.xml", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("<loc>http://$siteHost/</loc>"), body)
    assertTrue(body.contains("<loc>http://$siteHost/p/button-filled</loc>"), body)
    assertFalse(body.contains("/compose-m3/"), "a site's URLs carry no system segment: $body")
    assertFalse(body.contains("wear-m3"), "a site's sitemap is its own: $body")

    // The main host's sitemap keeps the front door and every listed catalog.
    val (_, mainBody, _) = get("/sitemap.xml")
    assertTrue(mainBody.contains("/compose-m3/p/button-filled"), mainBody)
    assertTrue(mainBody.contains("/wear-m3/"), mainBody)
  }

  @Test
  fun `a host header with a port or different case still selects the site`() {
    server = newServer()
    for (header in listOf("$siteHost:8080", siteHost.uppercase(), "$siteHost.")) {
      val (code, body, _) = get("/", host = header)
      assertEquals(200, code, "Host: $header")
      assertTrue(body.contains("Compose Material 3"), "Host: $header did not select the site")
    }
  }

  @Test
  fun `an unknown host is the main server`() {
    server = newServer()
    val (code, body, _) = get("/", host = "preview.example.test")
    assertEquals(200, code)
    assertTrue(body.contains("Wear M3"), "an unconfigured host gets the front door: $body")
  }

  @Test
  fun `an uploaded bundle session is not reachable through a site host`() {
    // A catalog row is not the only thing `/{system}/…` can acquire: an uploaded bundle registers
    // a session under its own name, and a catalog-only check let it serve through a site hostname.
    registry.register(
      "some-upload",
      host = bundle("some-upload", listOf("uploaded"), "Some Upload"),
      pinned = true,
    )
    server = newServer()
    assertEquals(404, get("/some-upload/", host = siteHost).first)
    assertEquals(404, get("/some-upload/p/uploaded", host = siteHost).first)
    // …and a revision of a neighbouring catalog, which is addressed as `<system>@<rev>`.
    assertEquals(404, get("/wear-m3@abc123/", host = siteHost).first)
    // The main host still serves the upload.
    assertEquals(200, get("/some-upload/").first)
  }

  @Test
  fun `a suspended session still counts as known to its site`() {
    // `peekHost` is null for suspended sessions; the status count and the foreign-session gate read
    // membership, not residency.
    server = newServer()
    // Re-register the site's catalog as known-but-not-resident: the shape a suspended session has.
    registry.register(
      "compose-m3",
      state =
        ServeSessionState(
          descriptor = File("daemon-launch.json"),
          workspaceRoot = File("."),
          workspaceName = "w",
          previews = emptyList(),
          label = "compose-m3",
        ),
    )
    val (code, body, _) = get("/status.json", host = siteHost)
    assertEquals(200, code)
    assertTrue(body.contains("\"known\":1"), "a suspended catalog has not disappeared: $body")
  }

  @Test
  fun `real routes reach their handlers on a site host`() {
    // Written independently of ServeSites.RESERVED_SYSTEMS, asserting each path reaches its
    // handler: iterating the allowlist can't catch an omission, and "not a 308" passes on the
    // interceptor's own 404.
    server = newServer()
    val routes =
      mapOf(
        "/healthz" to "ok",
        "/version" to "\"public\"",
        "/robots.txt" to "User-agent",
        "/sitemap.xml" to "<urlset",
        "/status.json" to "compose-preview-serve/status",
        "/api/previews" to "button-filled",
        "/p/button-filled" to "<!doctype html>",
        "/render/button-filled.png" to "",
        "/assets/serve/serve.css" to "",
        // The footer's link on every site page, built from `ServeBugReport.PATH`; it must not hit
        // the site's 404.
        "/report-bug" to "Report a bug in the preview server",
      )
    for ((path, marker) in routes) {
      val (code, body, location) = get(path, host = siteHost)
      assertEquals(200, code, "'$path' should reach its handler (Location: $location)")
      if (marker.isNotEmpty()) {
        assertTrue(body.contains(marker), "'$path' answered something else: ${body.take(200)}")
      }
    }
  }

  @Test
  fun `the report page on a site host sends pixel bugs to that catalog's tracker`() {
    // A site host serves exactly one catalog, so the bug report can route to that catalog directly,
    // even from a page with no preview.
    registry.register(
      "compose-m3",
      host =
        bundle(
          "compose-m3",
          listOf("button-filled"),
          "Compose Material 3",
          source = ServeWeb.CatalogSource("yschimke/m3-catalog", "main", "catalog"),
        ),
      pinned = true,
    )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "",
          isPublic = true,
          catalogSessions = listOf("compose-m3"),
          sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
        )
        .also { it.start() }

    val (code, body, _) = get("/report-bug?from=%2Fpages%2Fbuttons", host = siteHost)
    assertEquals(200, code)
    assertTrue(
      body.contains("<strong>Compose Material 3</strong> catalog and nothing else"),
      "the site's own catalog is named: $body",
    )
    assertTrue(
      body.contains("href=\"https://github.com/yschimke/m3-catalog/issues/new\""),
      "a pixel bug is one click from the catalog's tracker: $body",
    )
    // The form itself is unchanged: a SERVER bug still goes to the repo that ships the server.
    assertTrue(
      body.contains("action=\"https://github.com/yschimke/compose-preview-server/issues/new\""),
      body,
    )

    // On the main host's front door there is no catalog to name, so the generic advice stands.
    val (mainCode, mainBody, _) = get("/report-bug")
    assertEquals(200, mainCode)
    assertTrue(mainBody.contains("Go back to the"), mainBody)
    assertFalse(mainBody.contains("yschimke/m3-catalog"), mainBody)
  }

  @Test
  fun `a published capture is reachable on a site host`() {
    // `motion` must be reserved, or the interceptor (which runs before routing) treats `/motion/…`
    // as a neighbour catalog. Asserted on the bytes, since both 404s look alike.
    val motionId = "switch-on__ideal__default__light"
    registry.register(
      "compose-m3",
      host = motionBundle("compose-m3", "switch-on", motionId),
      pinned = true,
    )
    registry.register("wear-m3", host = bundle("wear-m3", listOf("chip"), "Wear M3"), pinned = true)
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "",
          isPublic = true,
          catalogSessions = listOf("compose-m3", "wear-m3"),
          sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
        )
        .also { it.start() }

    val (code, body, location) = get("/motion/$motionId.apng", host = siteHost)
    assertEquals(200, code, "the capture must reach handleMotion (Location: $location)")
    assertEquals(captureMarker, body, "the handler served the declared capture")

    // The canonical spelling on the main host is unchanged — the site is an additional door onto
    // the same bytes, never a replacement for them.
    assertEquals(200, get("/compose-m3/motion/$motionId.apng").first)
    // A neighbour's capture is still not reachable through this hostname.
    assertEquals(404, get("/wear-m3/motion/$motionId.apng", host = siteHost).first)
  }

  @Test
  fun `a capture is behind the token gate on a private server`() {
    // `handleMotion` opens with `rejectBadToken` like its sibling asset lanes, so both spellings
    // are gated.
    val motionId = "switch-on__ideal__default__light"
    val secret = "s3cret-token"
    registry.register(
      "compose-m3",
      host = motionBundle("compose-m3", "switch-on", motionId),
      pinned = true,
    )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = secret,
          sessions = registry,
          defaultSessionId = "",
          isPublic = false,
          catalogSessions = listOf("compose-m3"),
          sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
        )
        .also { it.start() }

    // 404 rather than 401, so the gate doesn't confirm the server to scanners; the body is what's
    // tested.
    for (path in listOf("/motion/$motionId.apng", "/compose-m3/motion/$motionId.apng")) {
      val host = if (path.startsWith("/motion")) siteHost else null
      val (code, body, _) = get(path, host = host)
      assertEquals(404, code, "'$path' must not serve capture bytes unauthenticated")
      assertFalse(body.contains(captureMarker), "'$path' leaked the capture: $body")
    }
    // With the token both spellings serve.
    assertEquals(200, get("/motion/$motionId.apng?token=$secret", host = siteHost).first)
    assertEquals(200, get("/compose-m3/motion/$motionId.apng?token=$secret").first)
  }

  @Test
  fun `a site host does not report the box's branch-read counters`() {
    // `/status` on a site reports one app. Branch-read counters are box-wide, so including them
    // would trip this site's monitor on a neighbour's throttle and disclose the neighbour.
    val stats = BranchFetchStats(clock = { 5L })
    stats.record(BranchFetch.Throttled(3))
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled"), "Compose Material 3"),
      pinned = true,
    )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "",
          isPublic = true,
          catalogSessions = listOf("compose-m3"),
          sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
          branchFetchStats = { stats.snapshot() },
        )
        .also { it.start() }

    val (siteCode, siteBody, _) = get("/status.json", host = siteHost)
    assertEquals(200, siteCode)
    assertFalse(
      siteBody.contains("\"throttled\":1"),
      "a site must not surface the box's branch counters: $siteBody",
    )

    // The main host still reports them — this scopes the field, it does not remove it.
    val (mainCode, mainBody, _) = get("/status.json")
    assertEquals(200, mainCode)
    assertTrue(mainBody.contains("\"throttled\":1"), "the box's own status still counts: $mainBody")
  }

  @Test
  fun `a site host does not report the box's subprocess census`() {
    // Likewise the process census, which can't attribute processes to catalogs.
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled"), "Compose Material 3"),
      pinned = true,
    )
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "",
          isPublic = true,
          catalogSessions = listOf("compose-m3"),
          sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
        )
        .also { it.start() }

    val (siteCode, siteBody, _) = get("/status.json", host = siteHost)
    assertEquals(200, siteCode)
    // The key is always present — the field is nullable and the encoder is explicit about nulls —
    // so the assertion is that it carries no census, not that the name is absent.
    assertTrue(
      siteBody.contains("\"processes\":null"),
      "a site must not surface the box's process census: $siteBody",
    )

    // The main host still reports it; asserted only where `/proc` exists (null on macOS).
    if (ServeProcessCensusSnapshot.read() != null) {
      val (mainCode, mainBody, _) = get("/status.json")
      assertEquals(200, mainCode)
      assertFalse(
        mainBody.contains("\"processes\":null"),
        "the box's own status still censuses its processes: $mainBody",
      )
    }
  }

  @Test
  fun `an unauthenticated refusal never carries the access token`() {
    // The interceptor runs before the routes' token gate, and the styled 404 threads the token
    // through its links, so it must not leak the secret to unauthenticated callers.
    registry.register(
      "compose-m3",
      host = bundle("compose-m3", listOf("button-filled"), "Compose Material 3"),
      pinned = true,
    )
    val secret = "s3cret-token"
    server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = secret,
          sessions = registry,
          defaultSessionId = "",
          isPublic = false,
          catalogSessions = listOf("compose-m3"),
          sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
        )
        .also { it.start() }
    val (code, body, _) = get("/not-a-catalog/", host = siteHost)
    assertEquals(404, code)
    assertFalse(body.contains(secret), "the refusal must not disclose the access token: $body")
    // With the token it is the site's own styled 404 again.
    val (okCode, okBody, _) = get("/not-a-catalog/?token=$secret", host = siteHost)
    assertEquals(404, okCode)
    assertTrue(okBody.contains("<!doctype html>"), okBody.take(200))
  }

  @Test
  fun `no ingestion path can name a session after a route`() {
    // Every way of naming a session converges on the registry, so the invariant is enforced there
    // (`register` and the on-demand fork in `entryFor`, covered by `ServeSessionRegistryTest.a
    // reserved route name is never bound to a session`).
    server = newServer()
    registry.register("api", host = bundle("api", listOf("sneaky"), "Sneaky"), pinned = true)
    assertFalse(registry.isKnownSession("api"), "the registry refuses a route's name")
    assertEquals(404, get("/api/", host = siteHost).first)
    // ...and the real /api/ routes still answer (the main host here needs an explicit session, as
    // before).
    assertEquals(200, get("/api/previews", host = siteHost).first)
    assertEquals(200, get("/api/previews?session=wear-m3").first)
  }

  @Test
  fun `an uploaded bundle may not take a route's name`() {
    // A session called `api` would be served by `/{system}/` on a site host; no session may be
    // named after a route.
    for (reserved in listOf("api", "render", "p", "status", "rc-fonts")) {
      assertNull(ServeBundleStore.sanitizeName(reserved), "'$reserved' must be refused as a name")
    }
    assertEquals("my-bundle", ServeBundleStore.sanitizeName("my-bundle"))
  }

  @Test
  fun `an unknown first segment is refused rather than resolved`() {
    // With --revisions a raw ref isn't a session until the generic route leases and builds it, so
    // the gate is an allowlist: anything not this site's system or a server route is refused before
    // creation.
    server = newServer()
    for (unknown in listOf("main", "some-ref", "not-a-catalog", "wear-m3@abc123")) {
      assertEquals(404, get("/$unknown/", host = siteHost).first, "'/$unknown/' must be refused")
    }
  }

  @Test
  fun `a neighbour's social card is not served through a site host`() {
    server = newServer()
    // `/social/` is ungated (unfurlers never send tokens), so ownership is what stops a site
    // hostname from serving another catalog's card by hash.
    val (_, wearLanding, _) = get("/wear-m3/")
    val hash =
      Regex("/social/([a-z0-9]+\\.png)").find(wearLanding)?.groupValues?.get(1)
        ?: error("no social card on the neighbour's landing: $wearLanding")
    assertEquals(200, get("/social/$hash").first, "it serves on the main host")
    assertEquals(404, get("/social/$hash", host = siteHost).first, "but never through the site")
  }

  @Test
  fun `status aggregates are scoped, not just the catalog list`() {
    server = newServer()
    val (_, body, _) = get("/status.json", host = siteHost)
    // `daemons.known` was a box-wide session count, so a per-app monitor was reading the box.
    // Three sessions are registered; the site knows its own.
    assertTrue(body.contains("\"known\":1"), "session count is site-scoped: $body")
    val (_, mainBody, _) = get("/status.json")
    assertTrue(mainBody.contains("\"known\":3"), "the main host still counts the box: $mainBody")
  }

  @Test
  fun `a site cannot claim any constant route as its system`() {
    // `pg` was missing from the reserved set, so a catalog named `pg` could be a site and swallow
    // `/pg/<token>` — every playground redemption on that hostname redirecting to `/<token>`.
    for (reserved in listOf("pg", "render", "p", "api", "wasm", "playground", "status")) {
      val problems = mutableListOf<String>()
      val sites =
        ServeSites.of(
          listOf("x.example.test" to reserved),
          knownSystems = setOf(reserved),
          onProblem = problems::add,
        )
      assertTrue(sites.isEmpty, "'$reserved' must be refused as a site system")
      assertTrue(problems.single().contains("built-in route"), problems.toString())
    }
  }

  @Test
  fun `retiring a catalog a site is published as is refused`() {
    // Retiring it would strand the hostname (its root 404s, and after a restart it falls through to
    // the global front door).
    val tracker =
      CatalogLoadTracker(
        listOf(
          CatalogLoadTracker.Config(
            system = "compose-m3",
            listed = true,
            repo = "yschimke/compose-ai-tools",
            branch = "design-artifacts/compose-m3",
          )
        )
      )
    tracker.recordSuccess("compose-m3")
    val admin =
      ServeCatalogAdmin(
        tracker = tracker,
        defaultRepo = "yschimke/compose-ai-tools",
        branchPrefix = "design-artifacts/",
        configFile = null,
        load = { _, _ -> null },
        unload = {},
        sites = ServeSiteRegistry.of(listOf(siteHost to "compose-m3")),
      )
    val result = admin.unregister("compose-m3")
    assertTrue(result is ServeCatalogAdmin.Result.Conflict, "$result")
    assertTrue(
      result.reason.contains(siteHost),
      result.reason,
    )
    // A catalog no site names retires as before.
    assertTrue(admin.unregister("not-a-site") is ServeCatalogAdmin.Result.Conflict)
  }

  @Test
  fun `a site cannot claim a built-in route as its system`() {
    // `/render/<id>.png` on a site mapped to `render` would redirect and break images; such an id
    // is already unreachable at its canonical path, so it's refused.
    val problems = mutableListOf<String>()
    val sites =
      ServeSites.of(
        listOf("render.example.test" to "render", "ok.example.test" to "compose-m3"),
        knownSystems = setOf("render", "compose-m3"),
        onProblem = problems::add,
      )
    assertNull(sites.systemFor("render.example.test"))
    assertEquals("compose-m3", sites.systemFor("ok.example.test"))
    assertTrue(problems.single().contains("collides with a built-in route"), problems.toString())
  }

  @Test
  fun `an empty known-system set means nothing is known, not skip the check`() {
    // A module-backed server serves no catalogs at all. A site naming one is a typo to report, not
    // a mapping to keep — keeping it 404s every route on that hostname instead.
    val problems = mutableListOf<String>()
    val sites =
      ServeSites.of(
        listOf("app.example.test" to "typo"),
        knownSystems = emptySet(),
        onProblem = problems::add,
      )
    assertTrue(sites.isEmpty, "an unserved system is dropped")
    assertTrue(problems.single().contains("does not serve"), problems.toString())
    // Null still means "don't check" — the tests and callers that validate elsewhere.
    assertEquals(
      "typo",
      ServeSites.of(listOf("app.example.test" to "typo")).systemFor("app.example.test"),
    )
  }

  @Test
  fun `parsing drops malformed and unknown-system entries`() {
    val problems = mutableListOf<String>()
    val sites =
      ServeSites.parse(
        "m3.preview.coo.ee=m3-catalog, not a host=x, nosystem, other.coo.ee=nope",
        knownSystems = setOf("m3-catalog"),
        onProblem = problems::add,
      )
    assertEquals(
      mapOf("m3.preview.coo.ee" to "m3-catalog"),
      sites.hosts.associateWith { sites.systemFor(it)!! },
    )
    assertEquals(3, problems.size, problems.toString())
    assertNull(sites.systemFor("unknown.coo.ee"))
    assertEquals("m3.preview.coo.ee", sites.hostFor("m3-catalog"))
    assertTrue(ServeSites.parse(null).isEmpty)
    // `--sites` parses before the served set is known (re-validated later), so an unchecked parse
    // must keep its entries.
    assertEquals(
      "m3-catalog",
      ServeSites.parse("m3.preview.coo.ee=m3-catalog").systemFor("m3.preview.coo.ee"),
    )
  }

  @Test
  fun `a config file's sites compose with the catalog set`() {
    val config =
      ServeCatalogsConfig.parse(
        """
        {
          "catalogs": [{ "system": "m3-catalog", "repo": "yschimke/m3-catalog" }],
          "sites": [{ "host": "m3.preview.coo.ee", "system": "m3-catalog" }]
        }
        """
          .trimIndent()
      )
    assertEquals(emptyList(), config.problems())
    assertEquals("m3-catalog", config.siteMap().systemFor("m3.preview.coo.ee"))

    val orphan =
      ServeCatalogsConfig(sites = listOf(ServeCatalogsConfig.Site("m3.preview.coo.ee", "nope")))
    assertTrue(
      orphan.problems().any { it.contains("which no catalog entry serves") },
      orphan.problems().toString(),
    )
  }
}
