package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WatchFaceActiveComplicationDto
import ee.schimke.composeai.bundle.WatchFaceComplicationSlotDto
import ee.schimke.composeai.bundle.WatchFaceComplicationsPayload
import ee.schimke.composeai.bundle.WatchFaceComplicationsProduct
import ee.schimke.composeai.bundle.WatchFaceSlotBoundsDto
import ee.schimke.composeai.bundle.WatchFaceUserConfigDto
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class ServeWatchFaceTest {

  private val wfPreviewId = "dwf__raw_watchface"
  private val plainPreviewId = "com.example.PlainCard"

  private fun solidPng(rgb: Int): ByteArray {
    val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
    val c = Color(rgb, true).rgb
    for (y in 0 until 4) {
      for (x in 0 until 4) {
        img.setRGB(x, y, c)
      }
    }
    return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
  }

  private val bakedPngBytes = solidPng(0xFF102030.toInt())
  private val apkPngBytes = solidPng(0xFFAA44CC.toInt())

  private val watchFacePayload =
    WatchFaceComplicationsPayload(
      previewId = wfPreviewId,
      dwfAssetPath = "raw/watchface.xml",
      widthPx = 450,
      heightPx = 450,
      clipShape = "CIRCLE",
      previewTime = "10:08:32",
      isAmbient = false,
      userConfigurations =
        listOf(
          WatchFaceUserConfigDto(
            id = "color_style_setting",
            kind = "COLOR_CONFIGURATION",
            displayName = "Color Style",
            defaultValue = "ambient_style",
            activeValue = "ambient_style",
            options = listOf("ambient_style", "sunset_style"),
            optionColors =
              mapOf(
                "ambient_style" to listOf("#FF80CBC4"),
                "sunset_style" to listOf("#FFFF8A65"),
              ),
          ),
        ),
      slots =
        listOf(
          WatchFaceComplicationSlotDto(
            slotId = 1,
            bounds = WatchFaceSlotBoundsDto(x = 120f, y = 210f, width = 90f, height = 90f),
            boundingShape = "CIRCLE",
            isCustomizable = true,
            supportedTypes = listOf("SHORT_TEXT", "RANGED_VALUE", "EMPTY"),
            authoredTypes = listOf("SHORT_TEXT", "RANGED_VALUE"),
            defaultSystemProvider = "STEP_COUNT",
            defaultSystemProviderType = "SHORT_TEXT",
            activeState =
              WatchFaceActiveComplicationDto(
                type = "SHORT_TEXT",
                typeCode = 3,
                text = "4521",
                title = "Steps",
                value = 0f,
                min = 0f,
                max = 100f,
                hasMonochromaticImage = false,
                hasSmallImage = false,
                hasPhotoImage = false,
                imageColor = "#FFFFFFFF",
                accentColor = "#FF80CBC4",
                hasRangedValueColors = false,
                rangedValueColorsInterpolate = false,
                hasGoalProgressColors = false,
                goalProgressColorsInterpolate = false,
                hasWeightedElements = false,
                colorCount = 0,
              ),
          ),
        ),
    )

  private class LiveWatchFaceHost(
    private val wfPreview: ServePreview,
    private val plainPreview: ServePreview,
    private val bakedPng: ByteArray,
  ) : ServeHost {
    override val label: String = "wf-live"
    override val canRenderOverrides: Boolean = true
    override val previews: List<ServePreview> = listOf(wfPreview, plainPreview)

    override fun bakedRender(previewId: String, overrides: PreviewOverrides): RenderOutcome.Ok? =
      if (
        overrides == PreviewOverrides() &&
          (previewId == wfPreview.id || previewId == plainPreview.id)
      ) {
        RenderOutcome.Ok(bakedPng, RenderOutcome.Generation.BAKED)
      } else {
        null
      }

    override fun render(previewId: String, overrides: PreviewOverrides): RenderOutcome {
      if (previewId != wfPreview.id && previewId != plainPreview.id) return RenderOutcome.NotFound
      val hash = overrides.hashCode()
      val r = (hash ushr 16) and 0xFF
      val g = (hash ushr 8) and 0xFF
      val b = hash and 0xFF
      val img = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
      val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
      for (y in 0 until 4) for (x in 0 until 4) img.setRGB(x, y, argb)
      val bytes = ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
      return RenderOutcome.Ok(bytes, RenderOutcome.Generation.DAEMON)
    }

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

  private val client = OkHttpClient()
  private var activeServer: ServeHttpServer? = null

  @AfterTest
  fun tearDown() {
    WatchFaceRendererRegistry.resetDefaults()
    runCatching { activeServer?.stop() }
    activeServer = null
  }

  private fun startServer(): Pair<ServeHttpServer, LiveWatchFaceHost> {
    val wfPreview =
      ServePreview(
        id = wfPreviewId,
        label = "watchface",
        watchFace = watchFacePayload,
        dwfAssetPath = watchFacePayload.dwfAssetPath,
        dataProductKinds = setOf(WatchFaceComplicationsProduct.KIND),
      )
    val plainPreview = ServePreview(id = plainPreviewId, label = "PlainCard")
    val host = LiveWatchFaceHost(wfPreview, plainPreview, bakedPngBytes)
    val registry = ServeSessionRegistry(open = { null })
    registry.register("wear-wf", host = host, pinned = true)
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "unused",
          sessions = registry,
          defaultSessionId = "wear-wf",
          isPublic = true,
          catalogSessions = listOf("wear-wf"),
        )
        .also { it.start() }
    activeServer = server
    return server to host
  }

  @Test
  fun `bundle store extracts watchface sidecars and bundle host withholds generic rc players`() {
    val storeRoot = Files.createTempDirectory("wf-bundle-store").toFile().also { it.deleteOnExit() }
    var registeredHost: ServeBundleHost? = null
    val store = ServeBundleStore(storeRoot, register = { _, host -> registeredHost = host })
    val zipBytes =
      ByteArrayOutputStream()
        .also { baos ->
          ZipOutputStream(baos).use { zout ->
            zout.putNextEntry(ZipEntry("previews/$wfPreviewId.png"))
            zout.write(bakedPngBytes)
            zout.closeEntry()
            zout.putNextEntry(ZipEntry("previews/$wfPreviewId.watchface.json"))
            zout.write(
              Json.encodeToString(WatchFaceComplicationsPayload.serializer(), watchFacePayload)
                .encodeToByteArray(),
            )
            zout.closeEntry()
            zout.putNextEntry(ZipEntry("ir/$wfPreviewId.rc"))
            zout.write(byteArrayOf(0x01, 0x02, 0x03))
            zout.closeEntry()
          }
        }
        .toByteArray()

    val res = store.add("uploaded-wf", zipBytes, isSecurityChecked = true)
    assertTrue(res is ServeBundleStore.Result.Ok, "expected Ok but got $res")
    val bundleHost = requireNotNull(registeredHost)
    val preview = bundleHost.previews.single()
    assertTrue(preview.isWatchFace)
    assertEquals(watchFacePayload, preview.watchFace)
    assertTrue(WatchFaceComplicationsProduct.KIND in preview.dataProductKinds)
    assertTrue(
      bundleHost.enabledRcPlayersFor(wfPreviewId).isEmpty(),
      "watch-face previews route through WatchFaceRendererRegistry instead of generic RC players",
    )
  }

  @Test
  fun `http api, render, complications and viewer routes drive pluggable watchface renderers`() {
    val (server, _) = startServer()
    val base = "http://127.0.0.1:${server.port}/wear-wf"

    // 1. /api/previews advertises watchFace metadata and both renderer backends (dwf-rc default,
    // dwf-apk unavailable until a launch bridge registers).
    client.newCall(Request.Builder().url("$base/api/previews").build()).execute().use { resp ->
      assertEquals(200, resp.code)
      val arr =
        Json.parseToJsonElement(resp.body.string()).jsonObject.getValue("previews").jsonArray
      val wfJson =
        arr.map { it.jsonObject }.first { it.getValue("id").jsonPrimitive.content == wfPreviewId }
      assertEquals(
        "CIRCLE",
        wfJson.getValue("watchFace").jsonObject.getValue("clipShape").jsonPrimitive.content,
      )
      val renderers = wfJson.getValue("watchFaceRenderers").jsonArray.map { it.jsonObject }
      assertEquals(
        listOf("dwf-rc", "dwf-apk"),
        renderers.map { it.getValue("id").jsonPrimitive.content },
      )
      assertTrue(renderers[0].getValue("available").jsonPrimitive.boolean)
      assertTrue(renderers[0].getValue("isDefault").jsonPrimitive.boolean)
      assertFalse(renderers[1].getValue("available").jsonPrimitive.boolean)
    }

    // 2. Default baked render vs ambient & complication slot override renders.
    val defaultBytes =
      client.newCall(Request.Builder().url("$base/render/$wfPreviewId.png").build()).execute().use {
        assertEquals(200, it.code)
        it.body.bytes()
      }
    assertContentEquals(bakedPngBytes, defaultBytes)

    val ambientBytes =
      client
        .newCall(Request.Builder().url("$base/render/$wfPreviewId.png?ambient=true").build())
        .execute()
        .use {
          assertEquals(200, it.code)
          it.body.bytes()
        }
    assertFalse(ambientBytes.contentEquals(defaultBytes))

    val slotBytes =
      client
        .newCall(
          Request.Builder()
            .url("$base/render/$wfPreviewId.png?wf.slot.1.type=SHORT_TEXT&wf.slot.1.text=72")
            .build(),
        )
        .execute()
        .use {
          assertEquals(200, it.code)
          it.body.bytes()
        }
    assertFalse(slotBytes.contentEquals(defaultBytes))
    assertFalse(slotBytes.contentEquals(ambientBytes))

    // 3. Complications endpoint reflects overrides and gates unavailable/unknown renderers.
    client
      .newCall(
        Request.Builder()
          .url(
            "$base/render/$wfPreviewId.complications?ambient=true&wf.slot.1.type=RANGED_VALUE&wf.slot.1.value=68&wf.slot.1.max=100&wf.config.color_style_setting=sunset_style",
          )
          .build(),
      )
      .execute()
      .use { resp ->
        assertEquals(200, resp.code)
        val body =
          Json.decodeFromString(WatchFaceComplicationsPayload.serializer(), resp.body.string())
        assertTrue(body.isAmbient)
        assertEquals("sunset_style", body.userConfigurations.single().activeValue)
        val slot = body.slots.single()
        assertEquals("RANGED_VALUE", slot.activeState.type)
        assertEquals(68f, slot.activeState.value)
        assertEquals(100f, slot.activeState.max)
      }

    // Unavailable dwf-apk -> 503 on both .png and .complications; unknown wfRenderer -> 400;
    // wfRenderer on non-watchface -> 400.
    client
      .newCall(Request.Builder().url("$base/render/$wfPreviewId.png?wfRenderer=dwf-apk").build())
      .execute()
      .use { assertEquals(503, it.code) }
    client
      .newCall(
        Request.Builder().url("$base/render/$wfPreviewId.complications?wfRenderer=dwf-apk").build(),
      )
      .execute()
      .use { assertEquals(503, it.code) }
    client
      .newCall(Request.Builder().url("$base/render/$wfPreviewId.png?wfRenderer=bogus").build())
      .execute()
      .use { assertEquals(400, it.code) }
    client
      .newCall(Request.Builder().url("$base/render/$plainPreviewId.png?wfRenderer=dwf-rc").build())
      .execute()
      .use { assertEquals(400, it.code) }

    // 4. Registering a live launch-backed dwf-apk backend makes ?wfRenderer=dwf-apk succeed on the
    // exact same route without changing server code.
    WatchFaceRendererRegistry.register(
      object : WatchFaceRendererBackend {
        override val id: String = WatchFaceRendererIds.DWF_APK
        override val label: String = "DWF Renderer APK (Launch)"
        override val badgeLabel: String = "dwf · apk"

        override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo =
          WatchFaceRendererInfo(
            id = id,
            label = label,
            badgeLabel = badgeLabel,
            available = true,
            supportsLiveOverrides = true,
            isDefault = false,
          )

        override fun render(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): RenderOutcome = RenderOutcome.Ok(apkPngBytes, RenderOutcome.Generation.DAEMON)

        override fun complications(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): WatchFaceComplicationsOutcome =
          WatchFaceOverrides.projectComplicationsOutcome(
            preview.watchFace!!,
            overrides,
            id,
          )
      },
    )

    val apkRendered =
      client
        .newCall(Request.Builder().url("$base/render/$wfPreviewId.png?wfRenderer=dwf-apk").build())
        .execute()
        .use {
          assertEquals(200, it.code)
          it.body.bytes()
        }
    assertContentEquals(apkPngBytes, apkRendered)

    // 5. Viewer HTML renders watch-face lane options and complication controls, without generic
    // rc:*
    // player lanes, and hydrates ambientBurnIn / ambientLowBit URL flags.
    client
      .newCall(
        Request.Builder()
          .url("$base/p/$wfPreviewId?ambientBurnIn=true&ambientLowBit=false")
          .build(),
      )
      .execute()
      .use { resp ->
        assertEquals(200, resp.code)
        val html = resp.body.string()
        assertTrue(html.contains("""data-wf-default="dwf-rc""""))
        assertTrue(html.contains("""value="wf:dwf-rc""""))
        assertTrue(html.contains("""value="wf:dwf-apk""""))
        assertTrue(html.contains("""data-cp-group="watchface""""))
        assertTrue(html.contains("""data-wf-key="ambient""""))
        assertTrue(html.contains("""data-wf-key="ambientBurnIn""""))
        assertTrue(html.contains("""data-wf-key="ambientLowBit""""))
        assertTrue(html.contains("""<option value="true" selected>true</option>"""))
        assertTrue(html.contains("""<option value="false" selected>false</option>"""))
        assertTrue(html.contains("""data-wf-key="wf.slot.1.type""""))
        assertTrue(html.contains("""data-wf-key="wf.config.color_style_setting""""))
        assertFalse(html.contains("""value="rc:camaelon-js""""))
      }
  }

  @Test
  fun `stream session applies watchface overrides and routes through WatchFaceRendererRegistry`() {
    val wfPreview =
      ServePreview(
        id = wfPreviewId,
        label = "watchface",
        watchFace = watchFacePayload,
        dwfAssetPath = watchFacePayload.dwfAssetPath,
        dataProductKinds = setOf(WatchFaceComplicationsProduct.KIND),
      )
    val host = LiveWatchFaceHost(wfPreview, ServePreview(plainPreviewId, "Plain"), bakedPngBytes)
    val sent = CopyOnWriteArrayList<String>()
    val session = ServeStreamSession(host, wfPreviewId, emptyMap(), sent::add, system = "wear-wf")

    session.onOpen()
    assertEquals(1, sent.size)
    val openFrame =
      Base64.getDecoder()
        .decode(
          Json.parseToJsonElement(sent[0]).jsonObject.getValue("dataBase64").jsonPrimitive.content,
        )
    assertContentEquals(bakedPngBytes, openFrame)

    session.onClientMessage(
      """{"type":"setOverrides","overrides":{"ambient":"true","wf.slot.1.type":"SHORT_TEXT","wf.slot.1.text":"98"}}""",
    )
    assertEquals(2, sent.size)
    val updatedFrame =
      Base64.getDecoder()
        .decode(
          Json.parseToJsonElement(sent[1]).jsonObject.getValue("dataBase64").jsonPrimitive.content,
        )
    assertFalse(updatedFrame.contentEquals(openFrame))

    // Unavailable dwf-apk reports a structured stream error message instead of silently serving
    // dwf-rc.
    session.onClientMessage("""{"type":"setOverrides","overrides":{"wfRenderer":"dwf-apk"}}""")
    assertEquals(3, sent.size)
    assertEquals(
      "error",
      Json.parseToJsonElement(sent[2]).jsonObject.getValue("type").jsonPrimitive.content,
    )
  }

  @Test
  fun `catalog mcp lists watchface metadata, serves complications data product, and renders scenarios`() {
    val wfPreview =
      ServePreview(
        id = wfPreviewId,
        label = "watchface",
        watchFace = watchFacePayload,
        dwfAssetPath = watchFacePayload.dwfAssetPath,
        dataProductKinds = setOf(WatchFaceComplicationsProduct.KIND),
      )
    val host = LiveWatchFaceHost(wfPreview, ServePreview(plainPreviewId, "Plain"), bakedPngBytes)
    val registry = ServeSessionRegistry(open = { null })
    registry.register("wear-wf", host = host, pinned = true)
    val mcp =
      ServeCatalogMcp(
        registry,
        Semaphore(2),
        publicOrigin = { "https://preview.example.test" },
      )

    fun callMcp(tool: String, argsJson: String): JsonObject {
      val req =
        Json.parseToJsonElement(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
          )
          .jsonObject
      return requireNotNull(
        runBlocking {
          mcp.handle(req) { ServeMachineAuthorization.Decision.Authorized("agent:test") }
        }
          .body,
      )
    }

    // 1. catalog_list_previews
    val listBody =
      callMcp("catalog_list_previews", """{"catalog":"wear-wf"}""")["result"]!!
        .jsonObject["content"]!!
        .jsonArray
        .first()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    val listedPreviews =
      Json.parseToJsonElement(listBody)
        .jsonObject
        .getValue("catalogs")
        .jsonArray
        .single()
        .jsonObject
        .getValue("previews")
        .jsonArray
    val listedWf =
      listedPreviews
        .map { it.jsonObject }
        .first { it.getValue("id").jsonPrimitive.content == wfPreviewId }
    assertTrue(listedWf.getValue("watchFace").jsonPrimitive.boolean)
    assertEquals(1, listedWf.getValue("complicationSlotCount").jsonPrimitive.int)
    assertEquals(1, listedWf.getValue("userConfigurationCount").jsonPrimitive.int)
    assertEquals(2, listedWf.getValue("watchFaceRenderers").jsonArray.size)

    // 2. catalog_list_data_products
    val productsBody =
      callMcp(
          "catalog_list_data_products",
          """{"catalog":"wear-wf","previewId":"$wfPreviewId"}""",
        )["result"]!!
        .jsonObject["content"]!!
        .jsonArray
        .first()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    assertTrue(productsBody.contains(WatchFaceComplicationsProduct.KIND))

    // 3. catalog_get_preview_data with structured complications + watchFace + ambient
    val dataBody =
      callMcp(
          "catalog_get_preview_data",
          """{
            "catalog":"wear-wf",
            "previewId":"$wfPreviewId",
            "kind":"watchface/complications",
            "ambient":true,
            "watchFace":{"config":{"color_style_setting":"sunset_style"}},
            "complications":[{"slotId":1,"type":"SHORT_TEXT","text":"72","title":"HR"}]
          }""",
        )["result"]!!
        .jsonObject["content"]!!
        .jsonArray
        .first()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    val dataPayload = Json.decodeFromString(WatchFaceComplicationsPayload.serializer(), dataBody)
    assertTrue(dataPayload.isAmbient)
    assertEquals("sunset_style", dataPayload.userConfigurations.single().activeValue)
    assertEquals("72", dataPayload.slots.single().activeState.text)
    assertEquals("HR", dataPayload.slots.single().activeState.title)

    // 4. catalog_render_preview + catalog_render_matrix with scenarios
    val defaultHashResp =
      callMcp(
        "catalog_render_preview",
        """{"catalog":"wear-wf","previewId":"$wfPreviewId","observe":"hash"}""",
      )
    val defaultHashText =
      defaultHashResp["result"]!!
        .jsonObject["content"]!!
        .jsonArray
        .first()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    val overrideHashResp =
      callMcp(
        "catalog_render_preview",
        """{
          "catalog":"wear-wf",
          "previewId":"$wfPreviewId",
          "observe":"hash",
          "ambient":true,
          "previewTime":"22:15:00",
          "complications":[{"slotId":1,"type":"RANGED_VALUE","value":55,"max":100}]
        }""",
      )
    val overrideHashText =
      overrideHashResp["result"]!!
        .jsonObject["content"]!!
        .jsonArray
        .first()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    assertNotEquals(defaultHashText, overrideHashText)

    val matrixResp =
      callMcp(
        "catalog_render_matrix",
        """{
          "catalog":"wear-wf",
          "previewId":"$wfPreviewId",
          "observe":"hash",
          "scenarios":[
            {"label":"interactive-steps","complications":[{"slotId":1,"type":"SHORT_TEXT","text":"8000"}]},
            {"label":"ambient-battery","ambient":true,"complications":[{"slotId":1,"type":"RANGED_VALUE","value":25,"max":100}]}
          ]
        }""",
      )
    val matrixJson =
      Json.parseToJsonElement(
          matrixResp["result"]!!
            .jsonObject["content"]!!
            .jsonArray
            .first()
            .jsonObject["text"]!!
            .jsonPrimitive
            .content,
        )
        .jsonObject
    val cells = matrixJson.getValue("cells").jsonArray
    assertEquals(2, cells.size)
    val h0 = cells[0].jsonObject.getValue("sha256").jsonPrimitive.content
    val h1 = cells[1].jsonObject.getValue("sha256").jsonPrimitive.content
    assertNotNull(h0)
    assertNotEquals(h0, h1)

    // 5. Returned signed image URLs and resource_link URIs preserve structured watch-face
    // arguments and renderer selection (dwf-apk) when replayed.
    WatchFaceRendererRegistry.register(
      object : WatchFaceRendererBackend {
        override val id: String = WatchFaceRendererIds.DWF_APK
        override val label: String = "DWF Renderer APK (Launch)"
        override val badgeLabel: String = "dwf · apk"

        override fun infoFor(host: ServeHost, preview: ServePreview): WatchFaceRendererInfo =
          WatchFaceRendererInfo(
            id = id,
            label = label,
            badgeLabel = badgeLabel,
            available = true,
            supportsLiveOverrides = true,
            isDefault = false,
          )

        override fun render(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): RenderOutcome {
          val keyHash = ServeOverrides.cacheKey(preview.id, overrides).take(6).toInt(16)
          return RenderOutcome.Ok(
            solidPng((0xFF220000.toInt()) or keyHash),
            RenderOutcome.Generation.DAEMON,
          )
        }

        override fun complications(
          host: ServeHost,
          preview: ServePreview,
          overrides: PreviewOverrides,
        ): WatchFaceComplicationsOutcome =
          WatchFaceOverrides.projectComplicationsOutcome(preview.watchFace!!, overrides, id)
      },
    )

    val pngResult =
      callMcp(
          "catalog_render_preview",
          """{
          "token":"test-token",
          "catalog":"wear-wf",
          "previewId":"$wfPreviewId",
          "observe":"png",
          "wfRenderer":"dwf-apk",
          "ambient":true,
          "complications":[{"slotId":1,"type":"SHORT_TEXT","text":"84"}]
        }""",
        )["result"]!!
        .jsonObject
    val pngContent = pngResult.getValue("content").jsonArray.map { it.jsonObject }
    val inlinePng =
      Base64.getDecoder().decode(pngContent.first().getValue("data").jsonPrimitive.content)
    assertFalse(inlinePng.contentEquals(bakedPngBytes))

    val signedImageUrl =
      pngResult.getValue("structuredContent").jsonObject.getValue("imageUrl").jsonPrimitive.content
    val queryParams =
      URI(signedImageUrl).rawQuery.split('&').associate {
        val idx = it.indexOf('=')
        it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), StandardCharsets.UTF_8)
      }
    val signedBytes = runBlocking {
      mcp.signedImagePng(
        queryParams.getValue("uri"),
        queryParams.getValue("exp").toLong(),
        queryParams.getValue("sig"),
      )
    }
    assertNotNull(signedBytes)
    assertContentEquals(inlinePng, signedBytes)

    val resourceLinkUri =
      pngContent
        .first {
          it["type"]?.jsonPrimitive?.content == "resource_link" &&
            it.getValue("uri").jsonPrimitive.content.startsWith("compose-preview://")
        }
        .getValue("uri")
        .jsonPrimitive
        .content
    val readReq =
      Json.parseToJsonElement(
          """{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"$resourceLinkUri"}}""",
        )
        .jsonObject
    val readBody =
      requireNotNull(
        runBlocking { mcp.handle(readReq) { ServeMachineAuthorization.Decision.Missing } }.body,
      )
    val resourceBytes =
      Base64.getDecoder()
        .decode(
          readBody
            .getValue("result")
            .jsonObject
            .getValue("contents")
            .jsonArray
            .single()
            .jsonObject
            .getValue("blob")
            .jsonPrimitive
            .content,
        )
    assertContentEquals(inlinePng, resourceBytes)

    val replayedData =
      callMcp(
          "catalog_get_preview_data",
          """{"uri":"$resourceLinkUri","kind":"watchface/complications"}""",
        )["result"]!!
        .jsonObject["content"]!!
        .jsonArray
        .first()
        .jsonObject["text"]!!
        .jsonPrimitive
        .content
    val replayedPayload =
      Json.decodeFromString(WatchFaceComplicationsPayload.serializer(), replayedData)
    assertTrue(replayedPayload.isAmbient)
    assertEquals("84", replayedPayload.slots.single().activeState.text)
  }
}
