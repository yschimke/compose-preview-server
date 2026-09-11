package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.*
import ee.schimke.composeai.uibuilder.service.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir

/** Real local compiler/player proof, using the Android daemon's pinned AndroidX classpath. */
class RemoteNativeRenderProofTest {
  @TempDir lateinit var work: Path

  @Test
  fun `ordinary Remote roots compile and return real PNGs through HTTP and MCP`() {
    val runtime = System.getenv("VERIFY_REMOTE_NATIVE_RUNTIME")
    assumeTrue(
      runtime != null,
      "Set VERIFY_REMOTE_NATIVE_RUNTIME to the staged Android daemon directory",
    )
    val daemonDir = File(runtime!!)
    val old = System.getProperty("composeai.cli.libDaemonAndroidDir")
    System.setProperty("composeai.cli.libDaemonAndroidDir", daemonDir.absolutePath)
    try {
      verify(daemonDir)
    } finally {
      if (old == null) System.clearProperty("composeai.cli.libDaemonAndroidDir")
      else System.setProperty("composeai.cli.libDaemonAndroidDir", old)
    }
  }

  private fun verify(daemonDir: File) {
    val androidJar =
      assertNotNull(ee.schimke.composeai.bundle.AndroidBundleLaunch.resolveAndroidJar(null))
    val (plugins, impl) =
      System.getProperty("composeai.libBtaJars").split(File.pathSeparator).map(::File).partition {
        it.name.startsWith("kotlin-compose-compiler-plugin-embeddable")
      }
    val compiler =
      PlaygroundBtaCompiler(
        impl.map(File::toPath),
        plugins.map(File::toPath),
        work.resolve("ic"),
        "remote-native-proof",
      )
    val opener =
      assertNotNull(
        PlaygroundDaemonOpeners.android(
          PlaygroundSandbox(profile = PlaygroundSandbox.Profile.NONE),
          ::println,
        )
      )
    val sequence = AtomicInteger()
    val renderer =
      PlaygroundAndroidRenderService(
        opener,
        { work.resolve("render-${sequence.incrementAndGet()}").toFile() },
      )
    val compile =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          assertEquals(PlaygroundMode.ANDROID, mode)
          assertEquals("local-remote", catalog)
          PlaygroundCompileService.Classpath(
            "local-remote",
            (daemonDir.listFiles()!!.filter { it.extension == "jar" }.sortedBy { it.name } +
                androidJar)
              .map { it.toOkioPath() },
          )
        },
        compiler = compiler,
        discoverer = PlaygroundPreviewDiscoverer(),
        tokenStore = PlaygroundTokenStore(),
        newWorkDir = {
          Files.createDirectories(work.resolve("snippet-${sequence.incrementAndGet()}"))
            .toFile()
            .toOkioPath()
        },
        renderFirstFrame = renderer::render,
      )
    val adapter = UiBuilderGeneratedPreviewAdapter(compile)
    val source =
      ScreenGeneratorComposeExportExecutor(
        { ComponentRecordSource.Lookup.Unconfigured },
        catalogPlatform = { UiBuilderCatalogPlatform.REMOTE_COMPOSE },
      )
    val lane =
      ServeUiBuilderNativePreview(
        source,
        compile = { adapter.compile(it, isSecurityChecked = true) },
        nativeTarget = {
          UiBuilderNativeTarget("local-remote", UiBuilderGeneratedCompose.COMPOSE_ANDROID)
        },
      )
    val catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("remote-m3"))
    val catalog = catalogs.listCatalogs().single()
    val json = Json {
      encodeDefaults = true
      explicitNulls = false
    }
    val doc =
      json
        .decodeFromString<DesignDocumentV1>(
          Files.readString(
            Path.of("../docs/design/evidence/ui-builder-unsaved-remote-preview/document.json")
          )
        )
        .copy(
          revision = 0,
          catalogPin =
            CatalogReferenceV1(
              "remote-m3",
              catalog.benchmark.catalogRevision,
              "candidate",
              catalog.benchmark.nativeRuntimeId,
            ),
        )
    val service =
      PersistentUiBuilderService(
        storage = FileUiBuilderStateStorage(work.resolve("state")),
        catalogs = catalogs,
        exporter = source,
      )
    assertIs<UiBuilderServiceResponse.Snapshot>(
      kotlinx.coroutines.runBlocking {
        service.execute(
          UiBuilderServiceCall(
            AuthenticatedUiBuilderActor("operator"),
            UiBuilderServiceRequest.CreateDesign(doc),
          )
        )
      }
    )
    val registry = ServeSessionRegistry(open = { null })
    val server =
      ServeHttpServer(
          host = "127.0.0.1",
          requestedPort = 0,
          token = "native-proof",
          sessions = registry,
          defaultSessionId = "unused",
          catalogMcpEnabled = true,
          machineAuthorization = ServeMachineAuthorization("native-proof", null, null),
          uiBuilderService = service,
          uiBuilderAuthorization =
            ServeUiBuilderAuthorization.fromServeIdentity("native-proof", null, null),
          uiBuilderNativePreview = lane,
          uiBuilderDir = File("../ui-builder/build/wasmDist"),
          uiBuilderCatalogs = setOf("remote-m3"),
        )
        .also(ServeHttpServer::start)
    val client = OkHttpClient.Builder().readTimeout(java.time.Duration.ofMinutes(5)).build()
    fun post(path: String, body: String) =
      client
        .newCall(
          Request.Builder()
            .url("http://127.0.0.1:${server.port}$path")
            .header(ServeHttpServer.TOKEN_HEADER, "native-proof")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        )
        .execute()
        .use {
          val text = it.body.string()
          assertEquals(200, it.code, text)
          text
        }
    val output = Path.of("build/remote-native-proof").also(Files::createDirectories)
    try {
      val http = post("/api/ui-builder/v1/designs/${doc.id}/native-preview?revision=0", "{}")
      val native = json.decodeFromString<NativePreviewResultV1>(http)
      assertNull(native.compileError, http)
      val png = Base64.getDecoder().decode(assertNotNull(native.imageBase64).substringAfter(','))
      Files.write(output.resolve("http.png"), png)
      val frame = assertNotNull(ImageIO.read(png.inputStream()))
      assertEquals(
        frame.width,
        frame.height,
        "The authored square must not become the default phone frame",
      )
      val padding = frame.width * 24 / 360
      assertEquals(0, frame.getRGB(padding - 1, frame.height / 2) and 0xffffff)
      assertEquals(0x008577, frame.getRGB(padding, frame.height / 2) and 0xffffff)
      assertEquals(0x008577, frame.getRGB(frame.width - padding - 1, frame.height / 2) and 0xffffff)
      assertEquals(0, frame.getRGB(frame.width - padding, frame.height / 2) and 0xffffff)
      assertEquals(0, frame.getRGB(frame.width / 2, padding - 1) and 0xffffff)
      assertEquals(0x008577, frame.getRGB(frame.width / 2, padding) and 0xffffff)
      assertEquals(0x008577, frame.getRGB(frame.width / 2, frame.height - padding - 1) and 0xffffff)
      assertEquals(0, frame.getRGB(frame.width / 2, frame.height - padding) and 0xffffff)
      val color = java.awt.Color(frame.getRGB(frame.width / 2, frame.height / 2), true)
      assertTrue(
        kotlin.math.abs(color.green - 133) < 16 &&
          color.red < 16 &&
          kotlin.math.abs(color.blue - 119) < 16,
        "Expected the authored green state, got $color",
      )
      Files.write(output.resolve("http.png"), png)
      Files.writeString(output.resolve("document.json"), json.encodeToString(doc))
      val generated =
        assertIs<ScreenGeneratorComposeExportExecutor.Generated.Emitted>(source.generate(doc))
      Files.writeString(output.resolve("source.kt.txt"), generated.source)
      Files.writeString(
        output.resolve("preview.kt.txt"),
        UiBuilderGeneratedPreviewAdapter.previewEntry(
          generated.screenName,
          360,
          360,
          remoteCapture = true,
        ),
      )
      val response =
        json
          .parseToJsonElement(
            post(
              "/mcp",
              """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ui_builder_render_native","arguments":{"designId":"${doc.id}","revision":0}}}""",
            )
          )
          .jsonObject
          .getValue("result")
          .jsonObject
      assertTrue(response["isError"] != JsonPrimitive(true), response.toString())
      val result =
        json.decodeFromString<NativePreviewResultV1>(
          response
            .getValue("content")
            .jsonArray
            .first()
            .jsonObject
            .getValue("text")
            .jsonPrimitive
            .content
        )
      assertNull(result.compileError)
      val mcpPng = Base64.getDecoder().decode(assertNotNull(result.imageBase64).substringAfter(','))
      assertContentEquals(png, mcpPng)
      Files.write(output.resolve("mcp.png"), mcpPng)
      Files.writeString(
        output.resolve("verification.json"),
        """{"width":${frame.width},"height":${frame.height},"httpMatchesMcp":true,"taggedNodes":${native.taggedNodeIds.size},"revision":${native.revision}}""",
      )
      if (System.getenv("VERIFY_REMOTE_NATIVE_BROWSER") == "true") {
        val browser =
          ProcessBuilder(
              "node",
              "../preview-harness/verify-remote-native-preview.mjs",
              "http://127.0.0.1:${server.port}",
              doc.id,
              output.toAbsolutePath().toString(),
            )
            .apply { environment()["UI_BUILDER_TEST_TOKEN"] = "native-proof" }
            .inheritIO()
            .start()
        try {
          assertTrue(
            browser.waitFor(3, java.util.concurrent.TimeUnit.MINUTES),
            "Browser proof timed out",
          )
          assertEquals(0, browser.exitValue(), "Browser proof failed")
        } finally {
          if (browser.isAlive) browser.destroyForcibly()
        }
      }
    } finally {
      server.stop()
      registry.close()
    }
  }
}
