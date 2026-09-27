package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1169: an agent edits a preview's source, calls `notify_file_changed`, then
 * `render_preview`, and gets the old image back.
 *
 * The [FakeDaemon] here behaves like the real one: it renders whatever the module's *compiled
 * classes* say (a file only the compiler writes), and `fileChanged` only swaps its classloader. So
 * a render is fresh only if something recompiled the module before the swap — which is what the
 * server now does through [SourceCompiler].
 */
class SourceRecompileTest {

  @get:Rule val tmp = TemporaryFolder()

  private val json = Json { ignoreUnknownKeys = true }
  private val factory = FakeDaemonClientFactory()
  private val supervisor =
    DaemonSupervisor(descriptorProvider = FakeDescriptorProvider(), clientFactory = factory)
  private lateinit var server: DaemonMcpServer
  private lateinit var client: McpTestClient
  private lateinit var session: McpSession

  /** What the fake compiler returns next; null compiles successfully. */
  @Volatile private var nextFailure: SourceCompileOutcome? = null
  private val compiles = CopyOnWriteArrayList<List<File>>()
  /** `fileChanged` notifications the daemon had already received when each compile ran. */
  private val fileChangesSeenAtCompile = CopyOnWriteArrayList<Int>()
  private lateinit var daemon: FakeDaemon

  private fun start(compiler: SourceCompiler?) {
    server =
      DaemonMcpServer(
        supervisor,
        workingDirectory = null,
        sourcePollIntervalMs = 0,
        samplingIntervalMs = 0,
        sourceCompiler = compiler,
      )
    val (clientToServer, serverFromClient) = pipedPair()
    val (serverToClient, clientFromServer) = pipedPair()
    session = server.newSession(input = serverFromClient, output = serverToClient)
    session.start()
    client = McpTestClient(input = clientFromServer, output = clientToServer)
    client.initialize()
  }

  @After
  fun tearDown() {
    runCatching { client.close() }
    runCatching { session.close() }
    runCatching { supervisor.shutdown() }
  }

  private class Fixture(val workspaceId: WorkspaceId, val source: File, val classes: File)

  /** A module whose "compiled classes" file mirrors the source text only after a compile. */
  private fun fixture(): Fixture {
    val projectDir = tmp.newFolder("workspace")
    val source = File(projectDir, "app/src/main/kotlin/com/example/MainActivity.kt")
    source.parentFile.mkdirs()
    source.writeText("""@Preview fun Header() { Text("Header") }""")
    val classes = tmp.newFile("compiled-render.png")
    classes.writeText(source.readText())
    val workspaceId = registerWorkspace(projectDir)
    supervisor.daemonFor(workspaceId, ":app")
    daemon = factory.daemons.getValue(workspaceId to ":app")
    daemon.emitDiscovery(PREVIEW_ID, sourceFile = "app/src/main/kotlin/com/example/MainActivity.kt")
    client.expectNotification("notifications/resources/list_changed", 2_000)
    daemon.autoRenderPngPath = { id -> if (id == PREVIEW_ID) classes.absolutePath else null }
    return Fixture(workspaceId, source, classes)
  }

  private fun fakeCompiler(classes: () -> File): SourceCompiler =
    SourceCompiler { _, modulePath, sources ->
      assertThat(modulePath).isEqualTo(":app")
      compiles += sources
      fileChangesSeenAtCompile += daemon.fileChanges.size
      nextFailure
        ?: run {
          classes().writeText(sources.single().readText())
          SourceCompileOutcome.Ok(durationMs = 1)
        }
    }

  private fun edit(fixture: Fixture, text: String) {
    fixture.source.writeText(text)
    fixture.source.setLastModified(fixture.source.lastModified() + 2_000)
  }

  private fun notifyChanged(fixture: Fixture): String =
    client
      .callTool(
        "notify_file_changed",
        buildJsonObject {
          put("workspaceId", fixture.workspaceId.value)
          put("path", fixture.source.absolutePath)
        },
        timeoutMs = 10_000,
      )
      .firstTextContent()

  private class Render(val bytes: String, val texts: List<String>)

  private fun render(fixture: Fixture): Render {
    val result =
      client.callTool(
        "render_preview",
        buildJsonObject {
          put("uri", PreviewUri(fixture.workspaceId, ":app", PREVIEW_ID).toUri())
          put("inline", false)
        },
        timeoutMs = 10_000,
      )
    val payload = json.parseToJsonElement(result.firstTextContent()).jsonObject
    val png = File(payload["pngPath"]!!.jsonPrimitive.content)
    return Render(png.readText(), result.textContents())
  }

  @Test
  fun `notify_file_changed recompiles before the daemon swaps so the next render is fresh`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    assertThat(render(fixture).bytes).contains("\"Header\"")

    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val notified = notifyChanged(fixture)

    assertThat(notified).contains("recompiled :app")
    assertThat(compiles).containsExactly(listOf(fixture.source))
    // The swap has to come after the compile, or it reloads the old classes.
    assertThat(fileChangesSeenAtCompile).containsExactly(0)
    val swap = daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)
    assertThat(swap?.get("kind")?.jsonPrimitive?.contentOrNull).isEqualTo("source")

    val fresh = render(fixture)
    assertThat(fresh.bytes).contains("Hello Android")
    assertThat(fresh.texts.none { it.startsWith("stale:") }).isTrue()
    // notify_file_changed already compiled this edit; the render must not compile it again.
    assertThat(compiles).hasSize(1)
  }

  @Test
  fun `an edit only the background poller saw is recompiled by the next render`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    assertThat(render(fixture).bytes).contains("\"Header\"")

    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    // The poller records the edit as seen; before #1169 that swallowed it for the render probe.
    server.runSourceFreshnessPoll()
    assertThat(compiles).isEmpty()

    assertThat(render(fixture).bytes).contains("Hello Android")
    assertThat(compiles).hasSize(1)
  }

  @Test
  fun `a failed recompile is reported as one stale line on the render`() {
    lateinit var fixture: Fixture
    start(fakeCompiler { fixture.classes })
    fixture = fixture()
    render(fixture)

    nextFailure =
      SourceCompileOutcome.Failed(
        ":app:composePreviewCompile failed: MainActivity.kt:1:30 Unresolved reference 'Txt'"
      )
    edit(fixture, """@Preview fun Header() { Txt("Hello Android") }""")
    assertThat(notifyChanged(fixture)).contains("Unresolved reference 'Txt'")

    val stale = render(fixture)
    assertThat(stale.bytes).contains("\"Header\"")
    val lines = stale.texts.filter { it.startsWith("stale:") }
    assertThat(lines).hasSize(1)
    assertThat(lines.single()).contains("MainActivity.kt")
    assertThat(lines.single()).contains("Unresolved reference 'Txt'")

    // Fixing the edit clears the note.
    nextFailure = null
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    notifyChanged(fixture)
    val fixed = render(fixture)
    assertThat(fixed.bytes).contains("Hello Android")
    assertThat(fixed.texts.none { it.startsWith("stale:") }).isTrue()
  }

  @Test
  fun `a render says so when the module cannot be recompiled at all`() {
    lateinit var fixture: Fixture
    start(GradleSourceCompiler())
    fixture = fixture()
    render(fixture)

    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    val stale = render(fixture)

    assertThat(stale.bytes).contains("\"Header\"")
    val line = stale.texts.single { it.startsWith("stale:") }
    assertThat(line).contains("could not recompile :app")
    assertThat(line).contains("no Gradle wrapper")
    // The source change is still forwarded, so classes built elsewhere are picked up.
    assertThat(daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)).isNotNull()
  }

  @Test
  fun `without a compiler the server keeps forwarding fileChanged straight away`() {
    start(compiler = null)
    val fixture = fixture()
    render(fixture)
    edit(fixture, """@Preview fun Header() { Text("Hello Android") }""")
    notifyChanged(fixture)
    assertThat(daemon.fileChanges.poll(2_000, TimeUnit.MILLISECONDS)).isNotNull()
    assertThat(render(fixture).texts.none { it.startsWith("stale:") }).isTrue()
  }

  @Test
  fun `gradle compiler runs composePreviewCompile through the project's wrapper`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val root = tmp.newFolder("gradle-root")
    val args = File(root, "args.txt")
    val wrapper = File(root, "gradlew")
    wrapper.writeText("#!/bin/sh\necho \"$@\" > '${args.absolutePath}'\nexit 0\n")
    wrapper.setExecutable(true)

    val ok = GradleSourceCompiler().compile(root, ":app", emptyList())

    assertThat(ok).isInstanceOf(SourceCompileOutcome.Ok::class.java)
    assertThat(args.readText()).contains(":app:composePreviewCompile")

    wrapper.writeText(
      "#!/bin/sh\necho 'e: file:///x/MainActivity.kt:3:5 Unresolved reference Txt'\nexit 1\n"
    )
    val failed = GradleSourceCompiler().compile(root, ":app", emptyList())
    assertThat(failed).isInstanceOf(SourceCompileOutcome.Failed::class.java)
    assertThat((failed as SourceCompileOutcome.Failed).reason)
      .isEqualTo(
        ":app:composePreviewCompile failed: file:///x/MainActivity.kt:3:5 Unresolved reference Txt"
      )
  }

  @Test
  fun `gradle failure summary falls back to what went wrong`() {
    assertThat(
        GradleSourceCompiler.summarizeGradleFailure(
          "\nFAILURE: Build failed.\n\n* What went wrong:\nTask 'composePreviewCompile' not found.\n"
        )
      )
      .isEqualTo("Task 'composePreviewCompile' not found.")
  }

  private fun registerWorkspace(projectDir: File): WorkspaceId {
    val text =
      client
        .callTool(
          "register_project",
          buildJsonObject {
            put("path", projectDir.absolutePath)
            put("rootProjectName", "starter")
          },
        )
        .firstTextContent()
    client.expectNotification("notifications/resources/list_changed", 2_000)
    return WorkspaceId(
      json.parseToJsonElement(text).jsonObject["workspaceId"]!!.jsonPrimitive.content
    )
  }

  private fun pipedPair(): Pair<OutputStream, InputStream> {
    val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val client = java.net.Socket(server.inetAddress, server.localPort)
    val accepted = server.accept()
    server.close()
    return client.getOutputStream() to accepted.getInputStream()
  }

  private companion object {
    const val PREVIEW_ID = "com.example.HeaderPreview"
  }
}
