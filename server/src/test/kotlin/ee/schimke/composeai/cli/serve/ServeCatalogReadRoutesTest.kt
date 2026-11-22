package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import ee.schimke.composeai.guidelines.protocol.GuidelineVerdictV1
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Semaphore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The catalog-read routes the hosted MCP will call once it moves out of process ([ServeCatalogRead]
 * and SERVER_UNBUNDLING.md step 4), on a real [ServeHttpServer]. Each body is checked against what
 * [ServeCatalogMcp] answers in process for the same catalog, since the point of a route is that the
 * MCP gets the same answer over HTTP; then the shared token gate and the `ETag` / `304` behaviour.
 */
class ServeCatalogReadRoutesTest {

  private val token = "s3cret-token"
  private val previewId = "ProfilePreview"
  private val served = "4".repeat(40)

  private class ReadHost(private val guidelines: Map<String, GuidelineRecordV1>) : ServeHost {
    override val label: String = "tools"
    override val previews: List<ServePreview> =
      listOf(
        ServePreview(
          id = "card",
          label = "Card",
          dataProductKinds = setOf("compose/theme", "a11y/atf"),
          state = "pressed",
          theme = "dark",
        ),
        ServePreview(id = "other", label = "Other"),
      )

    override fun guidelineResultFor(previewId: String) = guidelines[previewId]

    override fun hasSvgExportFor(previewId: String) = previewId == "card"

    override fun hasScrollExportFor(previewId: String) = previewId == "other"

    override fun hasA11yOverlayFor(previewId: String) = previewId == "card"

    override fun hasDesignAnnotationsFor(previewId: String) = false

    override fun hasPublishedTypographyFor(previewId: String) = previewId == "other"

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome =
      RenderOutcome.NotFound

    override fun subscribeStream(
      previewId: String,
      overrides: PreviewOverrides,
      codec: StreamCodec?,
      maxFps: Int?,
      onUnavailable: ((String) -> Unit)?,
      onFrame: (StreamFrameParams) -> Unit,
    ): StreamHandle? = null

    override fun activeStreamCount(): Int = 0

    override fun close() {}
  }

  private val record =
    GuidelineRecordV1.Builder(
        revision = 0,
        model = "deepseek/deepseek-v4.1-flash",
        rulesVersion = 3,
        asked = listOf("wear.layout.responsive-width"),
        verdicts =
          listOf(
            GuidelineVerdictV1.Builder("wear.layout.responsive-width", GuidelineVerdictV1.FAIL)
              .apply { reason = "Fixed width." }
              .build(),
          ),
      )
      .build()

  private val readHost = ReadHost(mapOf("card" to record))

  private val newest =
    PreviewHistoryManifest.ManifestVersion(
      blob = "a".repeat(40),
      commit = "1".repeat(40),
      date = "2026-08-27T10:58:22Z",
      sourceSha = "2ef7b877",
      commits = 1,
    )

  private val older =
    PreviewHistoryManifest.ManifestVersion(
      blob = "b".repeat(40),
      commit = "2".repeat(40),
      date = "2026-08-27T07:18:15Z",
      commits = 2,
      occurrences = 2,
    )

  /** A delivery-branch catalog carrying the publisher's `history.json`, as the history test has. */
  private fun publishedHost(): ServeBundleHost {
    val dir = Files.createTempDirectory("catalog-read").toFile().also { it.deleteOnExit() }
    File(dir, "previews").mkdirs()
    for (id in listOf(previewId, "Unindexed")) {
      File(dir, "previews/$id.png").writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte()))
    }
    File(dir, "previews.json")
      .writeText(
        """{"module":":m","variant":"debug","previews":[
             {"id":"$previewId","functionName":"$previewId","className":"ProfileKt"},
             {"id":"Unindexed","functionName":"Unindexed","className":"ProfileKt"}]}""",
      )
    return ServeBundleHost(
      dir,
      label = "m3",
      provenance =
        ServeWeb.CatalogProvenance(repo = "yschimke/design", branch = "design/m3", commit = served),
      indexedPreviewHistory =
        PreviewHistoryManifest.Manifest(
          generatedFrom = "3".repeat(40),
          previews =
            mapOf(
              previewId to
                PreviewHistoryManifest.PreviewTimeline(
                  path = "images/profile/ideal__default__dark.png",
                  versions = listOf(newest, older),
                  observations = 2,
                  unstable = true,
                  flapCount = 3,
                ),
            ),
        ),
    )
  }

  private val publishedHost = publishedHost()

  private val registry =
    ServeSessionRegistry(open = { null }).apply {
      register("tools", host = readHost, pinned = true)
      register("m3", host = publishedHost, pinned = true)
    }

  private var server: ServeHttpServer? = null
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun tearDown() {
    server?.stop()
    registry.close()
  }

  private fun start(isPublic: Boolean = true): ServeHttpServer =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = token,
        sessions = registry,
        defaultSessionId = "tools",
        isPublic = isPublic,
      )
      .also {
        it.start()
        server = it
      }

  private data class Reply(val code: Int, val body: String, val etag: String?, val cache: String?)

  private fun get(path: String, headers: Map<String, String> = emptyMap()): Reply {
    val request =
      Request.Builder()
        .url("http://127.0.0.1:${server!!.port}$path")
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .build()
    client.newCall(request).execute().use {
      return Reply(it.code, it.body.string(), it.header("ETag"), it.header("Cache-Control"))
    }
  }

  private fun json(path: String): JsonObject {
    val reply = get(path)
    assertEquals(200, reply.code, "$path: ${reply.body}")
    return Json.parseToJsonElement(reply.body).jsonObject
  }

  /** The text payload of one in-process [ServeCatalogMcp] tool call. */
  private fun mcp(tool: String, arguments: String): JsonElement {
    val mcp = ServeCatalogMcp(registry, Semaphore(1))
    val request =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":1,"method":"tools/call",
              "params":{"name":"$tool","arguments":$arguments}}""",
        )
        .jsonObject
    val body =
      requireNotNull(
        runBlocking {
          mcp.handle(request) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
        }
          .body,
      )
    val text =
      body["result"]!!
        .jsonObject["content"]!!
        .jsonArray[0]
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    return Json.parseToJsonElement(text)
  }

  private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

  private fun JsonObject.strings(key: String): List<String> =
    this[key]!!.jsonArray.map { it.jsonPrimitive.content }

  // ---- gap 1: capabilities ----------------------------------------------------------------------

  @Test
  fun `capabilities carry what list_previews and list_data_products compute in process`() {
    start()
    val body = json("/tools/api/capabilities")
    assertEquals("compose-preview-serve/capabilities/v1", body.string("schema"))
    val routed = body["previews"]!!.jsonArray.associateBy { it.jsonObject.string("id")!! }

    val listed =
      (mcp("catalog_list_previews", """{"catalog":"tools"}""") as JsonObject)["catalogs"]!!
        .jsonArray
        .single()
        .jsonObject["previews"]!!
        .jsonArray
    val products =
      mcp("catalog_list_data_products", """{"catalog":"tools"}""").jsonArray.associate {
        it.jsonObject.string("previewId")!! to it.jsonObject.strings("kinds")
      }
    assertEquals(listed.size, routed.size)

    listed.forEach { element ->
      val inProcess = element.jsonObject
      val id = inProcess.string("id")!!
      val route = routed.getValue(id).jsonObject
      assertEquals(inProcess.strings("dataProductKinds"), route.strings("dataProductKinds"), id)
      for (key in listOf("svgAvailable", "scrollAvailable")) {
        assertEquals(inProcess[key], route[key], "$id $key")
      }
      for (key in listOf("state", "theme")) {
        assertEquals(inProcess.string(key), route.string(key), "$id $key")
      }
      assertEquals(products.getValue(id), route.strings("productKinds"), id)
    }

    val card = routed.getValue("card").jsonObject
    assertEquals(listOf("a11y/atf", "compose/theme"), card.strings("dataProductKinds"))
    assertEquals(
      listOf("a11y/atf", "a11y/hierarchy", "compose/theme", GUIDELINES_RESULT_KIND),
      card.strings("productKinds"),
    )
    assertEquals(true, card["a11yOverlay"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(true, card["guidelinesResult"]!!.jsonPrimitive.booleanOrNull)
    val other = routed.getValue("other").jsonObject
    assertEquals(true, other["publishedTypography"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(listOf("compose/annotations"), other.strings("productKinds"))
    assertEquals(JsonNull, body["revision"], "a catalog with no delivery commit has no revision")
  }

  // ---- gap 2: history ---------------------------------------------------------------------------

  @Test
  fun `a preview's timeline is the slice history_list answers, with blobs and dates`() {
    start()
    val route = json("/m3/api/history/$previewId")
    val inProcess =
      mcp("catalog_history_list", """{"catalog":"m3","previewId":"$previewId"}""").jsonObject

    assertEquals("published", route.string("mode"))
    for (key in listOf("mode", "repo", "branch", "commit", "manifestUrl", "renderUrlTemplate")) {
      assertEquals(inProcess.string(key), route.string(key), key)
    }
    assertEquals(inProcess.string("pinnedCommit"), route.string("commit"))
    for (key in listOf("path", "observations", "unstable", "flapCount")) {
      assertEquals(inProcess[key], route[key], key)
    }
    assertEquals(inProcess["versions"], route["versions"]!!.withoutNulls(), "versions")

    val versions = route["versions"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf(newest.blob, older.blob), versions.map { it.string("blob") })
    assertEquals(listOf(newest.date, older.date), versions.map { it.string("date") })
    assertEquals(2, versions[1]["occurrences"]!!.jsonPrimitive.intOrNull)
  }

  @Test
  fun `a preview the manifest does not name says why rather than listing nothing`() {
    start()
    val route = json("/m3/api/history/Unindexed")
    val inProcess =
      mcp("catalog_history_list", """{"catalog":"m3","previewId":"Unindexed"}""").jsonObject
    assertEquals(inProcess.string("reason"), route.string("reason"))
    assertEquals(JsonNull, route["versions"], "an empty list would read as never changed")
    assertEquals(404, get("/m3/api/history/NoSuchPreview").code)
  }

  @Test
  fun `the catalog's history names the served delivery commit`() {
    start()
    val body = json("/m3/api/history")
    assertEquals("published", body.string("mode"))
    assertEquals(served, body.string("commit"))
    assertEquals("yschimke/design", body.string("repo"))
    assertEquals("design/m3", body.string("branch"))
    assertEquals(true, body["publishesManifest"]!!.jsonPrimitive.booleanOrNull)

    val none = json("/tools/api/history")
    assertEquals("none", none.string("mode"))
    assertNotNull(none.string("reason"))
  }

  // ---- gap 3: guidelines ------------------------------------------------------------------------

  @Test
  fun `the guidelines result is the record get_preview_data returns`() {
    start()
    val route = json("/tools/api/guidelines/card")
    val inProcess =
      mcp(
          "catalog_get_preview_data",
          """{"catalog":"tools","previewId":"card","kind":"guidelines/result"}""",
        )
        .jsonObject
    assertEquals(true, route["found"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(GUIDELINES_RESULT_KIND, route.string("kind"))
    val lenient = Json { ignoreUnknownKeys = true }
    val decoded = lenient.decodeFromJsonElement(GuidelineRecordV1.serializer(), route["record"]!!)
    assertEquals(record, decoded)
    assertEquals(
      lenient.decodeFromJsonElement(GuidelineRecordV1.serializer(), inProcess["record"]!!),
      decoded,
    )

    val missing = json("/tools/api/guidelines/other")
    assertEquals(false, missing["found"]!!.jsonPrimitive.booleanOrNull)
    assertEquals(JsonNull, missing["record"])
    assertEquals(404, get("/tools/api/guidelines/nope").code)
  }

  // ---- gate -------------------------------------------------------------------------------------

  @Test
  fun `every read refuses without a credential exactly as api previews does`() {
    start(isPublic = false)
    val paths =
      listOf(
        "/m3/api/previews",
        "/m3/api/capabilities",
        "/m3/api/history",
        "/m3/api/history/$previewId",
        "/tools/api/guidelines/card",
        "/api/capabilities?session=m3",
      )
    for (path in paths) {
      val refused = get(path)
      assertEquals(404, refused.code, path)
      assertEquals("not found", refused.body, path)
      assertNull(refused.etag, "a refusal must not leak a validator: $path")
      assertEquals(200, get(path, mapOf("X-Compose-Preview-Token" to token)).code, path)
      val separator = if ('?' in path) '&' else '?'
      assertEquals(200, get("$path${separator}token=$token").code, path)
    }
  }

  // ---- caching ----------------------------------------------------------------------------------

  @Test
  fun `api previews carries a revision and answers a matching If-None-Match with 304`() {
    start()
    val first = get("/m3/api/previews")
    assertEquals(200, first.code)
    val etag = assertNotNull(first.etag)
    assertTrue(etag.startsWith("W/\"${served.take(12)}-"), etag)
    assertNull(first.cache, "api/previews keeps the Cache-Control it always had (none)")
    val body = Json.parseToJsonElement(first.body).jsonObject
    assertEquals(served, body.string("revision"))

    val revalidated = get("/m3/api/previews", mapOf("If-None-Match" to etag))
    assertEquals(304, revalidated.code)
    assertEquals("", revalidated.body)
    assertEquals(etag, revalidated.etag)

    // A page open moves the engagement counter, which is not catalog identity: still a 304.
    get("/m3/")
    assertEquals(304, get("/m3/api/previews", mapOf("If-None-Match" to etag)).code)

    assertEquals(200, get("/m3/api/previews", mapOf("If-None-Match" to "W/\"stale\"")).code)
    assertNotEquals(etag, get("/tools/api/previews").etag, "different catalogs, different tags")
  }

  @Test
  fun `the new reads validate the same way and say how to cache`() {
    start()
    for (path in
      listOf(
        "/m3/api/capabilities",
        "/m3/api/history",
        "/m3/api/history/$previewId",
        "/tools/api/guidelines/card",
      )) {
      val first = get(path)
      assertEquals(200, first.code, path)
      assertEquals(ServeCatalogRead.CACHE_CONTROL, first.cache, path)
      val etag = assertNotNull(first.etag, path)
      val again = get(path, mapOf("If-None-Match" to etag))
      assertEquals(304, again.code, path)
      assertEquals(etag, again.etag, path)
    }
  }

  @Test
  fun `the tag follows the body, not only the revision`() {
    val a = ServeCatalogRead.entityTag(served, "{}")
    assertEquals(a, ServeCatalogRead.entityTag(served, "{}"))
    assertNotEquals(a, ServeCatalogRead.entityTag(served, "{\"x\":1}"))
    assertNotEquals(a, ServeCatalogRead.entityTag("5".repeat(40), "{}"))
    assertTrue(ServeCatalogRead.entityTag(null, "{}").matches(Regex("\"[0-9a-f]+\"")))
  }

  /** The server writes absent optionals as `null` (`encodeDefaults`); the MCP leaves them out. */
  private fun JsonElement.withoutNulls(): JsonElement =
    when (this) {
      is JsonObject ->
        JsonObject(filterValues { it != JsonNull }.mapValues { it.value.withoutNulls() })
      is JsonArray -> JsonArray(map { it.withoutNulls() })
      is JsonPrimitive -> this
    }
}
