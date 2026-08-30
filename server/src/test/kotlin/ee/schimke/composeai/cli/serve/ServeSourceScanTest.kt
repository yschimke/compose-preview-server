package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reading a foreign checkout for what previews are in it — the pass that has to work before
 * anything is built, and the one that must never run the project it is reading.
 *
 * The fixtures deliberately copy the shape of the multiplatform sample apps the feature was written
 * for: a version-catalog `alias(libs.plugins.…)` rather than a literal plugin id, a `shared` module
 * with the previews and an `androidApp` module without, and a settings file that names both.
 */
class ServeSourceScanTest {

  private val root = Files.createTempDirectory("scan").toFile().also { it.deleteOnExit() }

  private fun write(path: String, text: String) {
    File(root, path).apply {
      parentFile.mkdirs()
      writeText(text.trimIndent())
    }
  }

  @Test
  fun `a module with previews and a catalog-aliased Compose plugin is buildable`() {
    write(
      "settings.gradle.kts",
      """
      rootProject.name = "sample"
      include(":shared", ":androidApp")
      """,
    )
    write(
      "gradle/libs.versions.toml",
      """
      [versions]
      agp = "8.5.0"

      [plugins]
      android-application = { id = "com.android.application", version.ref = "agp" }
      composeMultiplatform = { id = "org.jetbrains.compose", version = "1.6.11" }
      """,
    )
    write(
      "shared/build.gradle.kts",
      """
      plugins {
        alias(libs.plugins.composeMultiplatform)
      }
      """,
    )
    write(
      "shared/src/commonMain/kotlin/Ui.kt",
      """
      @Composable fun Card() {}

      @Preview
      @Composable
      fun CardPreview() {}

      @Preview(showBackground = true)
      @Composable
      fun CardDarkPreview() {}
      """,
    )
    write(
      "androidApp/build.gradle.kts",
      """
      plugins {
        alias(libs.plugins.android.application)
      }
      """,
    )
    write("androidApp/src/main/kotlin/Main.kt", "fun main() {}")

    val result = ServeSourceScan.scan(root)
    val shared = result.modules.single { it.gradlePath == "shared" }

    // The alias resolved through the version catalog: without that this module reads as plugin-less
    // and the whole repository reports as "nothing to preview here".
    assertEquals(listOf("org.jetbrains.compose"), shared.hostPlugins)
    assertEquals(2, shared.previewCount)
    assertEquals(listOf("CardPreview", "CardDarkPreview"), shared.previewFunctions)
    assertTrue(shared.buildable)

    // Previews are what make a module worth building — an Android app module with none is reported,
    // with the reason, rather than dropped or attempted.
    val app = result.modules.single { it.gradlePath == "androidApp" }
    assertEquals(listOf("com.android.application"), app.hostPlugins)
    assertFalse(app.buildable)
    assertEquals("no @Preview functions under src/", app.skipReason)
  }

  @Test
  fun `previews without any plugin to inject beside are reported honestly`() {
    write("settings.gradle.kts", """include(":core")""")
    write("core/build.gradle.kts", """plugins { kotlin("jvm") }""")
    write(
      "core/src/main/kotlin/Ui.kt",
      """
      @Preview
      @Composable
      fun Thing() {}
      """,
    )

    val result = ServeSourceScan.scan(root)
    val core = result.modules.single { it.gradlePath == "core" }

    assertEquals(1, core.previewCount)
    assertFalse(core.buildable)
    // The distinction that matters to whoever pasted the URL: previews exist, the plugin has
    // nowhere to attach. "No previews found" would send them looking for the wrong thing.
    assertTrue(
      result.notes.single().contains("no module applies an Android or Compose"),
      "${result.notes}",
    )
  }

  @Test
  fun `a module that already applies the preview plugin needs nothing injected`() {
    write("settings.gradle.kts", """include(":ui")""")
    write(
      "ui/build.gradle.kts",
      """
      plugins {
        id("ee.schimke.composeai.preview")
      }
      """,
    )
    write("ui/src/main/kotlin/Ui.kt", "@Preview @Composable fun P() {}")

    val ui = ServeSourceScan.scan(root).modules.single { it.gradlePath == "ui" }
    assertTrue(ui.pluginPreApplied)
    assertTrue(ui.buildable)
    assertTrue(ui.hostPlugins.isEmpty())
  }

  @Test
  fun `a single-module repository with no settings file still scans`() {
    write("build.gradle.kts", """plugins { id("com.android.library") }""")
    write("src/main/kotlin/Ui.kt", "@Preview @Composable fun P() {}")

    val result = ServeSourceScan.scan(root)
    val rootModule = result.modules.single()
    // The root project is the module here, and its Gradle path is the empty one — which is what
    // `:composePreviewDiscover` on an unqualified path means.
    assertEquals("", rootModule.gradlePath)
    assertTrue(rootModule.buildable)
    assertTrue(result.notes.any { it.contains("no settings.gradle") }, "${result.notes}")
  }

  @Test
  fun `a PreviewParameter annotation is not a preview`() {
    write("build.gradle.kts", """plugins { id("org.jetbrains.compose") }""")
    write(
      "src/main/kotlin/Ui.kt",
      """
      @Composable
      fun Thing(@PreviewParameter(Provider::class) value: String) {}
      """,
    )

    // Counting it would advertise a preview that doesn't exist, and then fail to render it.
    assertEquals(0, ServeSourceScan.scan(root).modules.single().previewCount)
  }

  @Test
  fun `both settings DSLs and both include spellings are understood`() {
    // A Groovy settings file the reader didn't understand would report a real project as empty.
    assertEquals(
      listOf("app", "features:home"),
      ServeSourceScan.includedProjects("include ':app'\ninclude \":features:home\""),
    )
    assertEquals(
      listOf("a", "b"),
      ServeSourceScan.includedProjects("""include(":a", ":b")"""),
    )
    // A composite build's modules are not paths in this build.
    assertTrue(ServeSourceScan.includedProjects("""includeBuild("build-logic")""").isEmpty())
  }

  @Test
  fun `catalog aliases match however the two files spell them`() {
    write(
      "gradle/libs.versions.toml",
      """
      [plugins]
      android-application = { id = "com.android.application", version = "8.5.0" }
      """,
    )
    val aliases = ServeSourceScan.pluginAliases(root)
    // `android-application` in the TOML is `libs.plugins.android.application` in the build file;
    // comparing the letters and digits of each is what makes those the same alias.
    assertEquals(
      setOf("com.android.application"),
      ServeSourceScan.declaredPluginIds("alias(libs.plugins.android.application)", aliases),
    )
    assertEquals(
      setOf("com.android.application"),
      ServeSourceScan.declaredPluginIds("alias(libs.plugins.androidApplication)", aliases),
    )
  }
}
