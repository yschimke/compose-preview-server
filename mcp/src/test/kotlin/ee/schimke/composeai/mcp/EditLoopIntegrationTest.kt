package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.SubprocessDaemonClientFactory
import ee.schimke.composeai.daemon.client.WorkspaceId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The edit→compile→render loop on a real Android project (issue #1174), end to end: the fixture
 * under `mcp/src/editLoopFixture` does not apply the Compose Preview plugin, so the server's
 * recompile has to inject the compose-preview CLI's init script exactly as the CLI does.
 *
 * A cold render, then [CYCLES] edits of the preview's `Text`, each followed by
 * `notify_file_changed` and `render_preview`. Every warm render must be fresh (its hash moved and
 * its pixels differ), and every recompile's work record must stay inside the task set the save loop
 * needs. Those are the structural checks; [WARM_CYCLE_BUDGET_MS] is only a backstop.
 *
 * Timings and work records always go to [reportFile], which CI uploads, so trends are visible even
 * when the test passes.
 *
 * **Opt-in** with `-Pmcp.editLoop=true`. It needs an Android SDK (`ANDROID_HOME`) and the CLI's
 * init script (`COMPOSE_PREVIEW_INIT_SCRIPT`, or wherever `compose-preview` materialised it), and
 * it downloads the Android daemon and Robolectric's runtime on first use.
 */
class EditLoopIntegrationTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val repoRoot = File(System.getProperty("composeai.mcp.repoRoot") ?: "..").absoluteFile
  private val reportFile =
    File(
      System.getProperty("composeai.mcp.editLoopReport") ?: "build/edit-loop/edit-loop-report.json"
    )
  private val cycles = mutableListOf<JsonObject>()

  private lateinit var workDir: File
  private lateinit var supervisor: DaemonSupervisor
  private lateinit var server: DaemonMcpServer
  private lateinit var session: McpSession
  private lateinit var client: McpTestClient

  @After
  fun tearDown() {
    if (!::workDir.isInitialized) return
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { server.shutdown() }
    runCatching { supervisor.shutdown() }
    runCatching { workDir.deleteRecursively() }
  }

  @Test
  fun `every warm edit renders fresh and recompiles only the module's compile tasks`() {
    assumeTrue(
      "Skipping EditLoopIntegrationTest; pass -Pmcp.editLoop=true to run it.",
      System.getProperty("composeai.mcp.editLoop") == "true",
    )
    val initScript = InitScripts().forProject(File(repoRoot, FIXTURE))
    assumeTrue(
      "Skipping EditLoopIntegrationTest; no compose-preview CLI init script. Install the CLI, or " +
        "set COMPOSE_PREVIEW_INIT_SCRIPT.",
      initScript != null,
    )
    val sdk =
      System.getProperty("composeai.mcp.androidSdk")
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
    assumeTrue("Skipping EditLoopIntegrationTest; ANDROID_HOME is not set.", sdk != null)

    workDir = createFixture(sdk!!)
    val source = File(workDir, "app/src/main/kotlin/com/example/editloop/Greeting.kt")
    var bootstrapMs = -1L
    try {
      bootstrapMs =
        measure {
          gradle(
            workDir,
            listOf(
              "--init-script",
              initScript!!.absolutePath,
              "-Dorg.gradle.unsafe.isolated-projects=false",
              "-Dorg.gradle.isolated-projects=false",
              ":app:composePreviewDaemonStart",
            ),
            timeoutMinutes = 20,
          )
        }
          .ms
      startServer()
      val workspaceId = register(workDir)
      // Spawn the module's daemon now, as `watch` or a first render would; its discovery fills
      // the resource list the URI comes from.
      supervisor.daemonFor(workspaceId, ":app")
      val uri = awaitPreviewUri("GreetingPreview")

      val cold = measure { render(uri) }
      cycles += cycleJson("cold", cold.ms, null, cold.value)
      var previous = cold.value

      for (cycle in 1..CYCLES) {
        val text = "Edit loop $cycle"
        source.writeText(source.readText().replace(Regex("""text = "[^"]*""""), "text = \"$text\""))
        source.setLastModified(System.currentTimeMillis() + cycle * 2_000L)
        val notified = measure { notify(workspaceId, source) }
        val rendered = measure { render(uri) }
        val cycleMs = notified.ms + rendered.ms
        val record = cycleJson("warm-$cycle", cycleMs, notified.ms, rendered.value)
        cycles += record

        val current = rendered.value
        assertThat(current.stale).isEmpty()
        assertThat(current.sha256).isNotEqualTo(previous.sha256)
        assertThat(current.changed).isTrue()
        assertThat(pixelsDiffer(previous.png, current.png)).isTrue()

        // The recompile the edit caused: notify_file_changed ran it, the render reports it.
        val compile = notifiedCompile(notified.value)
        assertThat(compile).isNotNull()
        assertThat(compile!!.initScript).isTrue()
        assertThat(compile.taskPaths).contains(":app:${GradleSourceCompiler.TASK}")
        assertThat(compile.disallowedTasks(allowedModules = setOf(":app"))).isEmpty()
        assertThat(current.work?.get("compile")).isNotNull()

        // TODO(#1181): once the daemon sends `renderFinished.workTrace` (a trace of the
        //  post-capture processors and data kinds it ran), require it here and assert that no
        //  unrequested kind such as `compose/figma-svg` ran. Until then this only checks a trace
        //  that happens to be present.
        current.work?.get("daemonTrace")?.let { trace ->
          assertThat(trace.toString()).doesNotContain("compose/figma-svg")
        }

        assertThat(cycleMs).isLessThan(WARM_CYCLE_BUDGET_MS)
        previous = current
      }

      // An unchanged render compiles nothing.
      val unchanged = render(uri)
      assertThat(unchanged.work?.containsKey("compile")).isFalse()
    } finally {
      writeReport(bootstrapMs)
    }
  }

  private class Rendered(
    val png: File,
    val sha256: String,
    val changed: Boolean,
    val stale: List<String>,
    val work: JsonObject?,
  )

  private class Timed<T>(val value: T, val ms: Long)

  private fun <T> measure(block: () -> T): Timed<T> {
    val startedAt = System.nanoTime()
    val value = block()
    return Timed(value, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
  }

  private fun createFixture(sdk: String): File {
    val dir = File(System.getProperty("java.io.tmpdir"), "edit-loop-${System.nanoTime()}")
    File(repoRoot, FIXTURE).copyRecursively(dir)
    File(repoRoot, "gradlew").copyTo(File(dir, "gradlew")).setExecutable(true)
    File(repoRoot, "gradle/wrapper").copyRecursively(File(dir, "gradle/wrapper"))
    File(dir, "local.properties").writeText("sdk.dir=$sdk\n")
    return dir
  }

  private fun gradle(dir: File, args: List<String>, timeoutMinutes: Long) {
    val log = File(dir, "gradle-${System.nanoTime()}.log")
    val process =
      ProcessBuilder(listOf(File(dir, "gradlew").absolutePath, "--console=plain") + args)
        .directory(dir)
        .redirectErrorStream(true)
        .redirectOutput(log)
        .start()
    check(process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
      process.destroyForcibly()
      "gradle ${args.last()} timed out"
    }
    check(process.exitValue() == 0) {
      "gradle ${args.last()} failed:\n${log.readText().takeLast(4_000)}"
    }
  }

  private fun startServer() {
    supervisor =
      DaemonSupervisor(
        descriptorProvider = DescriptorProvider.readingFromDisk(),
        clientFactory = SubprocessDaemonClientFactory(),
      )
    server =
      DaemonMcpServer(
        supervisor,
        workingDirectory = null,
        renderTimeoutMs = TimeUnit.MINUTES.toMillis(10),
        sourceCompiler = GradleSourceCompiler(),
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    client = McpTestClient(input = clientFromServer, output = clientToServer)
    client.initialize()
  }

  private fun register(dir: File): WorkspaceId {
    val text =
      client
        .callTool(
          "register_project",
          buildJsonObject {
            put("path", dir.absolutePath)
            put("rootProjectName", "edit-loop-fixture")
            putJsonArray("modules") { add(JsonPrimitive(":app")) }
          },
          timeoutMs = 60_000,
        )
        .firstTextContent()
    return WorkspaceId(
      json.parseToJsonElement(text).jsonObject["workspaceId"]!!.jsonPrimitive.content
    )
  }

  /** The preview's URI once the daemon's discovery has put it in the resource list. */
  private fun awaitPreviewUri(function: String): String {
    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
    while (System.nanoTime() < deadline) {
      val resources = runCatching {
        client.request("resources/list", timeoutMs = 30_000)
      }
        .getOrNull()
      resources
        ?.get("resources")
        ?.jsonArray
        ?.map { it.jsonObject["uri"]!!.jsonPrimitive.content }
        ?.firstOrNull { it.contains(function) }
        ?.let {
          return it
        }
      Thread.sleep(1_000)
    }
    error("no preview matching $function was discovered")
  }

  private fun notify(workspaceId: WorkspaceId, file: File): McpToolResult =
    client.callTool(
      "notify_file_changed",
      buildJsonObject {
        put("workspaceId", workspaceId.value)
        put("path", file.absolutePath)
      },
      timeoutMs = TimeUnit.MINUTES.toMillis(10),
    )

  private fun render(uri: String): Rendered {
    val result =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", uri)
          put("inline", false)
        },
        timeoutMs = TimeUnit.MINUTES.toMillis(15),
      )
    val payload = json.parseToJsonElement(result.firstTextContent()).jsonObject
    return Rendered(
      png = File(payload["pngPath"]!!.jsonPrimitive.content),
      sha256 = payload["sha256"]!!.jsonPrimitive.content,
      changed = payload["changed"]!!.jsonPrimitive.content.toBoolean(),
      stale = result.textContents().filter { it.startsWith("stale:") },
      work = result.raw["_meta"]?.jsonObject?.get("work")?.jsonObject,
    )
  }

  private fun notifiedCompile(result: McpToolResult): CompileWork? {
    val compile =
      result.raw["_meta"]
        ?.jsonObject
        ?.get("work")
        ?.jsonObject
        ?.get("compile")
        ?.jsonObject
        ?.get(":app")
        ?.jsonObject ?: return null
    return CompileWork(
      task = compile["task"]!!.jsonPrimitive.content,
      ms = compile["ms"]!!.jsonPrimitive.content.toLong(),
      initScript = compile["initScript"]!!.jsonPrimitive.content.toBoolean(),
      tasks = compile["tasks"]!!.jsonArray.map { it.jsonPrimitive.content },
    )
  }

  private fun pixelsDiffer(a: File, b: File): Boolean {
    val left = ImageIO.read(a)
    val right = ImageIO.read(b)
    if (left.width != right.width || left.height != right.height) return true
    for (y in 0 until left.height) for (x in 0 until left.width) {
      if (left.getRGB(x, y) != right.getRGB(x, y)) return true
    }
    return false
  }

  private fun cycleJson(name: String, ms: Long, notifyMs: Long?, rendered: Rendered): JsonObject =
    buildJsonObject {
      put("cycle", name)
      put("ms", ms)
      notifyMs?.let { put("notifyMs", it) }
      put("sha256", rendered.sha256)
      put("stale", rendered.stale.isNotEmpty())
      rendered.work?.let { put("work", it) }
    }

  private fun writeReport(bootstrapMs: Long) {
    reportFile.absoluteFile.parentFile.mkdirs()
    val report = buildJsonObject {
      put("warmCycleBudgetMs", WARM_CYCLE_BUDGET_MS)
      put("bootstrapMs", bootstrapMs)
      put("cycles", kotlinx.serialization.json.JsonArray(cycles.map { it as JsonElement }))
    }
    reportFile.writeText(PRETTY.encodeToString(JsonObject.serializer(), report))
    System.err.println("edit-loop report: ${reportFile.absolutePath}\n$report")
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
  }

  private companion object {
    const val FIXTURE = "mcp/src/editLoopFixture"
    const val CYCLES = 3
    val PRETTY = Json { prettyPrint = true }

    /**
     * A backstop, not the check: freshness and the task set above are what catch a regression. 30 s
     * is generous for a hosted runner's warm `notify_file_changed` + `render_preview`; tighten it
     * toward #1174's 3 s target once compose-preview-server#1174 lands (in-process compile, no
     * unrequested `compose/figma-svg`).
     */
    const val WARM_CYCLE_BUDGET_MS = 30_000L
  }
}
