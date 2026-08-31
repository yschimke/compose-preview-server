package ee.schimke.composeai.cli.serve

import androidx.compose.runtime.Composable
import java.io.File
import java.nio.file.Path as NioPath
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.jupiter.api.io.TempDir

class UiBuilderGeneratedPreviewAdapterTest {
  @TempDir lateinit var tempDir: NioPath

  @Test
  fun `preview entry is deterministic and calls the capability export`() {
    val first =
      UiBuilderGeneratedPreviewAdapter.previewEntry(
        composableName = "JetcasterDiscoverExpanded",
        widthDp = 1280,
        heightDp = 800,
      )
    val second =
      UiBuilderGeneratedPreviewAdapter.previewEntry(
        composableName = "JetcasterDiscoverExpanded",
        widthDp = 1280,
        heightDp = 800,
      )

    assertEquals(first, second)
    assertEquals(
      """
      package generated.uibuilder.preview

      import androidx.compose.runtime.Composable
      import androidx.compose.ui.tooling.preview.Preview
      import generated.uibuilder.JetcasterDiscoverExpanded as GeneratedUiBuilderScreen

      @Preview(widthDp = 1280, heightDp = 800)
      @Composable
      fun UiBuilderGeneratedPreview() {
        GeneratedUiBuilderScreen()
      }

      """
        .trimIndent(),
      first,
    )
  }

  @Test
  fun `representative generated source compiles discovers and enters first-frame render`() {
    val btaJars = System.getProperty("composeai.libBtaJars").split(File.pathSeparator).map(::File)
    val (composePlugin, btaImpl) =
      btaJars.partition { it.name.startsWith("kotlin-compose-compiler-plugin-embeddable") }
    assertTrue(btaImpl.isNotEmpty(), "the server test task must supply the installed BTA toolchain")
    // This focused fixture uses annotation stand-ins from testClasses and deliberately omits the
    // Compose plugin. Production's injected Playground compiler uses the real catalog runtime and
    // plugin; here we are proving the adapter's staging -> BTA -> bytecode discovery -> render seam
    // without starting a daemon or browser.
    assertTrue(composePlugin.isNotEmpty(), "the production test toolchain must include Compose")
    val testClasses =
      File(Composable::class.java.protectionDomain.codeSource.location.toURI()).toPath()
    val kotlinStdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI()).toPath()
    val compiler =
      PlaygroundBtaCompiler(
        btaImplJars = btaImpl.map(File::toPath),
        compilerPluginJars = emptyList(),
        icWorkingDir = tempDir.resolve("ic").also(NioPath::createDirectories),
        moduleName = "ui-builder-generated-preview-test",
      )
    val tokenStore =
      PlaygroundTokenStore(
        fileSystem = FileSystem.SYSTEM,
        mintId = { "pg_generated" },
      )
    var rendered: PlaygroundTokenStore.PlaygroundSnippet? = null
    val firstFrame = byteArrayOf(0x50, 0x4e, 0x47)
    val playground =
      PlaygroundCompileService(
        catalogClasspath = { mode, catalog ->
          assertEquals(PlaygroundMode.CMP, mode)
          assertEquals("m3-catalog", catalog)
          PlaygroundCompileService.Classpath(
            moduleName = "m3-catalog",
            entries = listOf(testClasses, kotlinStdlib).map { it.toOkioPath() },
          )
        },
        compiler = compiler,
        discoverer = PlaygroundPreviewDiscoverer(),
        tokenStore = tokenStore,
        newWorkDir = { tempDir.resolve("run").also(NioPath::createDirectories).toOkioPath() },
        renderFirstFrame = { snippet ->
          rendered = snippet
          firstFrame
        },
      )
    val adapter = UiBuilderGeneratedPreviewAdapter(playground)

    val result =
      adapter.compile(
        UiBuilderGeneratedCompose(
          source = REPRESENTATIVE_CAPABILITY_EXPORT,
          composableName = "JetcasterDiscoverExpanded",
          catalog = "m3-catalog",
          widthDp = 1280,
          heightDp = 800,
        ),
        isSecurityChecked = true,
      )

    assertEquals(null, result.exception, result.diagnostics.joinToString { it.message })
    assertEquals(UiBuilderGeneratedPreviewAdapter.PREVIEW_ID, result.previewId)
    assertEquals(listOf(UiBuilderGeneratedPreviewAdapter.PREVIEW_ID), result.previews)
    assertEquals("pg_generated", result.previewToken)
    assertTrue(result.image?.startsWith("data:image/png;base64,") == true)
    val snippet = assertNotNull(rendered)
    assertEquals(UiBuilderGeneratedPreviewAdapter.PREVIEW_ID, snippet.previewId)
    assertContentEquals(
      firstFrame,
      java.util.Base64.getDecoder().decode(result.image!!.substringAfter(',')),
    )
    assertTrue(snippet.classesDir in snippet.classpath)
  }

  @Test
  fun `adapter refuses an unpinned catalog or injectable symbol`() {
    val unused =
      PlaygroundCompileService(
        catalogClasspath = { _, _ -> error("must not resolve") },
        compiler = PlaygroundCompileService.Compiler { _, _, _ -> error("must not compile") },
        discoverer = PlaygroundCompileService.PreviewDiscoverer { _, _ -> emptyList() },
        tokenStore = PlaygroundTokenStore(),
        newWorkDir = { error("must not allocate") },
      )
    val adapter = UiBuilderGeneratedPreviewAdapter(unused)

    assertFailsWith<IllegalArgumentException> {
      adapter.compile(
        UiBuilderGeneratedCompose(
          source = REPRESENTATIVE_CAPABILITY_EXPORT,
          composableName = "Screen(); error(\"injected\")",
          catalog = "m3-catalog",
          widthDp = 1280,
          heightDp = 800,
        ),
        isSecurityChecked = true,
      )
    }
    assertFailsWith<IllegalArgumentException> {
      adapter.compile(
        UiBuilderGeneratedCompose(
          source = REPRESENTATIVE_CAPABILITY_EXPORT,
          composableName = "JetcasterDiscoverExpanded",
          catalog = "",
          widthDp = 1280,
          heightDp = 800,
        ),
        isSecurityChecked = true,
      )
    }
  }

  companion object {
    private val REPRESENTATIVE_CAPABILITY_EXPORT =
      """
      package generated.uibuilder

      import androidx.compose.runtime.Composable

      // Representative of CapabilityComposeCodeExporter output. The complete Jetcaster document
      // uses the same package and top-level composable contract; component calls live in its body.
      @Composable
      fun JetcasterDiscoverExpanded() {
        Unit
      }
      """
        .trimIndent() + "\n"
  }
}
