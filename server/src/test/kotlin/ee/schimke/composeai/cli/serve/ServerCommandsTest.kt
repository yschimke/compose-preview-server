package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ServerCommandsTest {

  /**
   * The contract 3.0.0 shipped. Flags with no command in front of them are `serve`, and a command
   * surface that broke this would be a second breaking change on the heels of that release.
   */
  @Test
  fun `bare flags still mean serve`() {
    val args = listOf("--module", "app", "--port", "8080")
    assertEquals(ServerCommands.Invocation.Run(ServerCommands.SERVE, args), parse(args))
  }

  @Test
  fun `no arguments at all is serve`() {
    assertEquals(ServerCommands.Invocation.Run(ServerCommands.SERVE, emptyList()), parse())
  }

  @Test
  fun `the serve alias is still accepted and consumed`() {
    assertEquals(
      ServerCommands.Invocation.Run(ServerCommands.SERVE, listOf("--module", "app")),
      parse("serve", "--module", "app"),
    )
  }

  @Test
  fun `playground and ui are commands`() {
    assertEquals(
      ServerCommands.Invocation.Run(ServerCommands.PLAYGROUND, listOf("--public")),
      parse("playground", "--public"),
    )
    assertEquals(
      ServerCommands.Invocation.Run(ServerCommands.UI, listOf("--module", "app")),
      parse("ui", "--module", "app"),
    )
  }

  @Test
  fun `help takes an optional command`() {
    assertEquals(ServerCommands.Invocation.Help(null), parse("help"))
    assertEquals(ServerCommands.Invocation.Help("ui"), parse("help", "ui"))
    assertEquals(ServerCommands.Invocation.Unknown("uid"), parse("help", "uid"))
  }

  @Test
  fun `an unknown command names every command rather than one alias`() {
    assertEquals(ServerCommands.Invocation.Unknown("srve"), parse("srve", "--module", "app"))
    val message = ServerCommands.unknownCommandMessage("srve")
    assertTrue(message.startsWith("Unknown command 'srve'."), message)
    ServerCommands.NAMES.forEach { assertTrue(it in message, "$it missing from: $message") }
  }

  @Test
  fun `the command listing names every command`() {
    val listing = ServerCommands.commandListing()
    ServerCommands.NAMES.forEach { assertTrue("  $it" in listing, "$it missing from listing") }
  }

  @Test
  fun `playground admits the compile lane`() {
    assertEquals(
      listOf("--public", "--playground"),
      ServerCommands.playgroundArgs(listOf("--public")),
    )
  }

  @Test
  fun `playground leaves an explicit pin alone`() {
    val pinned = listOf("--playground-bundle", "compose-m3")
    assertEquals(pinned, ServerCommands.playgroundArgs(pinned))
    val android = listOf("--playground-android-bundle", "compose-m3")
    assertEquals(android, ServerCommands.playgroundArgs(android))
    val explicit = listOf("--playground")
    assertEquals(explicit, ServerCommands.playgroundArgs(explicit))
  }

  @Test
  fun `serve passes its argv through untouched`() {
    val args = listOf("--module", "app")
    assertEquals(args, ServerCommands.serveArgs(ServerCommands.SERVE, args))
  }

  private fun parse(vararg args: String) = ServerCommands.parse(args.toList())

  private fun parse(args: List<String>) = ServerCommands.parse(args)
}

class LocalUiBuilderTest {

  @Test
  fun `ui builds the project and opens the builder`() {
    val serve =
      LocalUiBuilder.serveArgs(
        args = listOf("--module", "app"),
        catalog = LocalUiBuilder.DEFAULT_CATALOG,
        componentRecord = File("/tmp/record/components.json"),
        builderDir = File("/opt/compose-preview-server/ui-builder"),
      )
    assertEquals(
      listOf(
        "--module",
        "app",
        "--ui-builder-dir",
        "/opt/compose-preview-server/ui-builder",
        "--ui-builder-components",
        "m3-catalog=/tmp/record/components.json",
        // `--open-path` first, and set whether or not a browser is opened: it names the page this
        // command is about, which the banner prints and a failed desktop browse quotes.
        "--open-path",
        "/ui-builder/m3-catalog/",
        "--open-browser",
      ),
      serve,
    )
  }

  /** No `--module` means every module in the build, which still has to be discovered. */
  @Test
  fun `ui without a module opts into discovery`() {
    val serve = serveArgs(emptyList())
    assertTrue("--discover" in serve, serve.toString())
  }

  @Test
  fun `ui adds nothing the caller already chose`() {
    val args =
      listOf(
        "--discover",
        "--ui-builder-dir",
        "/custom/builder",
        "--ui-builder-components",
        "m3-catalog=/custom/components.json",
        "--open-path",
        "/ui-builder/remote-m3/",
      )
    val serve = serveArgs(args)
    assertEquals(args + "--open-browser", serve)
  }

  @Test
  fun `no-project points the export at the record the distribution ships`(@TempDir temp: File) {
    // The documented stock-distribution command, on a distribution laid out as one. Without this
    // the packaged record sat unread beside the binary: `uiBuilderComponents` came up empty,
    // `composeExportFor` answered false for the default catalog, and the builder withdrew its
    // Compose export action on exactly the mode that has nothing else to offer.
    val home = File(temp, "app-home").also { it.mkdirs() }
    val record = File(home, "ui-builder-components/m3-catalog-components-v1.json")
    record.parentFile.mkdirs()
    record.writeText("{}")
    val previous = System.getProperty("composeai.cli.appHome")
    System.setProperty("composeai.cli.appHome", home.path)
    try {
      val serve = serveArgs(listOf(LocalUiBuilder.NO_PROJECT))

      val index = serve.indexOf("--ui-builder-components")
      assertTrue(index >= 0, serve.toString())
      assertEquals("${LocalUiBuilder.DEFAULT_CATALOG}=${record.path}", serve[index + 1])
    } finally {
      if (previous == null) System.clearProperty("composeai.cli.appHome")
      else System.setProperty("composeai.cli.appHome", previous)
    }
  }

  @Test
  fun `no-project without a packaged record names no components file`(@TempDir temp: File) {
    // A source checkout or an unpacked jar has no distribution beside it. Naming a path that is not
    // there would make every export refuse against a file that cannot appear, which is the reason
    // the projectless branch declined to name one at all.
    val previous = System.getProperty("composeai.cli.appHome")
    System.setProperty("composeai.cli.appHome", File(temp, "empty").path)
    try {
      assertTrue("--ui-builder-components" !in serveArgs(listOf(LocalUiBuilder.NO_PROJECT)))
    } finally {
      if (previous == null) System.clearProperty("composeai.cli.appHome")
      else System.setProperty("composeai.cli.appHome", previous)
    }
  }

  @Test
  fun `no-project refuses the options that need a project`() {
    assertEquals(
      listOf("--module"),
      LocalUiBuilder.conflictingProjectFlags(listOf(LocalUiBuilder.NO_PROJECT, "--module", "app")),
    )
    assertEquals(
      listOf("--discover", "--export"),
      LocalUiBuilder.conflictingProjectFlags(
        listOf("--export", "out", LocalUiBuilder.NO_PROJECT, "--discover")
      ),
      "reported in the order the help lists them, not the order they were typed",
    )
    assertEquals(
      emptyList(),
      LocalUiBuilder.conflictingProjectFlags(listOf(LocalUiBuilder.NO_PROJECT, "--port", "9000")),
      "flags that mean the same thing with or without a project are not a conflict",
    )
    assertEquals(
      emptyList(),
      LocalUiBuilder.conflictingProjectFlags(listOf("--module", "app")),
      "and there is no conflict at all without --no-project",
    )
  }

  @Test
  fun `no-open is this lane's flag and never reaches the server`() {
    val serve = serveArgs(listOf("--module", "app", LocalUiBuilder.NO_OPEN))
    assertTrue(LocalUiBuilder.NO_OPEN !in serve, serve.toString())
    assertTrue("--open-browser" !in serve, serve.toString())
    // `--open-path` DOES survive, which it did not before. It is what the banner prints, so
    // withholding it left `--no-open` — the headless case — with only the root landing URL, and on
    // a projectless server that page has no session behind it.
    assertTrue("--open-path" in serve, serve.toString())
    assertTrue("/ui-builder/${LocalUiBuilder.DEFAULT_CATALOG}/" in serve, serve.toString())
  }

  @Test
  fun `the opened catalog is the caller's first, else the packaged default`() {
    assertEquals(LocalUiBuilder.DEFAULT_CATALOG, LocalUiBuilder.catalog(listOf("--module", "app")))
    assertEquals(
      "remote-m3",
      LocalUiBuilder.catalog(listOf("--ui-builder-catalogs", "remote-m3,m3-catalog")),
    )
    assertEquals("remote-m3", LocalUiBuilder.catalog(listOf("--ui-builder-catalogs=remote-m3")))
  }

  @Test
  fun `the opened page follows the chosen catalog`() {
    val serve =
      LocalUiBuilder.serveArgs(
        args = listOf("--ui-builder-catalogs", "remote-m3"),
        catalog = "remote-m3",
        componentRecord = File("components.json"),
        builderDir = null,
      )
    assertEquals("/ui-builder/remote-m3/", serve[serve.indexOf("--open-path") + 1])
  }

  /**
   * The record is the module's real build output — written by preview discovery beside
   * `previews.json` — not a projection this command invents.
   */
  @Test
  fun `the published record is the module's discovered one`(@TempDir temp: File) {
    val projectDir = File(temp, "app").apply { mkdirs() }
    val discovered =
      File(projectDir, "build/compose-previews/components.json").apply {
        parentFile.mkdirs()
        writeText("""{"schemaVersion":2,"module":"app","variant":"debug","components":[]}""")
      }
    val destination = File(temp, "record/components.json")

    val message = LocalUiBuilder.publishRecord(discovery(projectDir), destination)

    assertEquals(discovered.readText(), destination.readText())
    assertNotNull(message)
    assertTrue(":app" in message, message)
  }

  @Test
  fun `a module with no record is reported rather than fabricated`(@TempDir temp: File) {
    val projectDir = File(temp, "app").apply { mkdirs() }
    val destination = File(temp, "record/components.json")

    val message = LocalUiBuilder.publishRecord(discovery(projectDir), destination)

    assertTrue(!destination.exists(), "no record must be published")
    assertNotNull(message)
    assertTrue("components.json" in message, message)
  }

  /** `serve` refuses to host several modules and says which; guessing one here would hide that. */
  @Test
  fun `a multi-module discovery publishes nothing`(@TempDir temp: File) {
    val one = File(temp, "one").apply { mkdirs() }
    val two = File(temp, "two").apply { mkdirs() }
    val destination = File(temp, "record/components.json")

    val message =
      LocalUiBuilder.publishRecord(
        ServeDiscovery(
          buildOk = true,
          manifests =
            listOf(one, two).map { dir ->
              PreviewModule(":${dir.name}", dir) to manifest(dir.name)
            },
        ),
        destination,
      )

    assertNull(message)
    assertTrue(!destination.exists())
  }

  /**
   * The whole projectless mode, which is the one command this lane exists to make possible.
   *
   * Everything a project needs is absent — no `--discover`, no `--ui-builder-components` naming a
   * record no build is going to write — and the packaged design systems are offered instead of only
   * the one being opened, so trying the other does not mean relaunching.
   */
  @Test
  fun `no-project opens the packaged design systems and asks for no build`() {
    val serve =
      LocalUiBuilder.serveArgs(
        args = listOf(LocalUiBuilder.NO_PROJECT),
        catalog = LocalUiBuilder.DEFAULT_CATALOG,
        componentRecord = File("/tmp/record/components.json"),
        builderDir = File("/opt/compose-preview-server/ui-builder"),
      )
    assertEquals(
      listOf(
        "--ui-builder-dir",
        "/opt/compose-preview-server/ui-builder",
        "--ui-builder-catalogs",
        "m3-catalog,remote-m3",
        "--open-path",
        "/ui-builder/m3-catalog/",
        "--open-browser",
      ),
      serve,
    )
  }

  /** This lane's flag, like `--no-open`: the server would not know what to do with it. */
  @Test
  fun `no-project never reaches the server`() {
    val serve = serveArgs(listOf(LocalUiBuilder.NO_PROJECT))
    assertTrue(LocalUiBuilder.NO_PROJECT !in serve, serve.toString())
    assertTrue(LocalUiBuilder.isProjectless(listOf(LocalUiBuilder.NO_PROJECT)))
    assertTrue(!LocalUiBuilder.isProjectless(listOf("--module", "app")))
  }

  /** A caller who names catalogs themselves gets those, and the one they named first is opened. */
  @Test
  fun `no-project leaves a chosen catalog set alone`() {
    val serve =
      LocalUiBuilder.serveArgs(
        args = listOf(LocalUiBuilder.NO_PROJECT, "--ui-builder-catalogs", "remote-m3"),
        catalog = LocalUiBuilder.catalog(listOf("--ui-builder-catalogs", "remote-m3")),
        componentRecord = File("components.json"),
        builderDir = null,
      )
    assertEquals(1, serve.count { it == "--ui-builder-catalogs" }, serve.toString())
    assertTrue("--open-path" in serve && "/ui-builder/remote-m3/" in serve, serve.toString())
  }

  private fun serveArgs(args: List<String>) =
    LocalUiBuilder.serveArgs(
      args = args,
      catalog = LocalUiBuilder.DEFAULT_CATALOG,
      componentRecord = File("components.json"),
      builderDir = null,
    )

  private fun discovery(projectDir: File) =
    ServeDiscovery(
      buildOk = true,
      manifests = listOf(PreviewModule(":app", projectDir) to manifest("app")),
    )

  private fun manifest(module: String) =
    PreviewManifest(module = module, variant = "debug", previews = emptyList())
}
