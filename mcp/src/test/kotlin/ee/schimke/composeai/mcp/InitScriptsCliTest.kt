package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * On a machine where `compose-preview mcp install` never ran, nothing has written the init script,
 * and the first render failed with "run `compose-preview mcp install` once"
 * (yschimke/compose-ag-plugin#87). The server now asks the CLI to write it.
 */
class InitScriptsCliTest {
  @get:Rule val tmp = TemporaryFolder()

  /** A fake CLI that writes the script where the real one does: the init cache under [home]. */
  private fun writingCli(home: File, calls: AtomicInteger) = CliInitScript { _ ->
    calls.incrementAndGet()
    File(home, ".cache/composeai/init/2.29.0/${InitScripts.FILE_NAME}").apply {
      parentFile.mkdirs()
      writeText("// init script")
    }
  }

  @Test
  fun `with nothing cached the CLI writes the script, and is asked once`() {
    val home = tmp.newFolder("home")
    val root = tmp.newFolder("plain").apply { File(this, "settings.gradle.kts").writeText("") }
    val calls = AtomicInteger()
    val scripts = InitScripts(emptyMap(), home, cli = writingCli(home, calls))

    val script = scripts.forProject(root)
    assertThat(script).isNotNull()
    assertThat(script!!.name).isEqualTo(InitScripts.FILE_NAME)
    assertThat(scripts.forProject(root)).isEqualTo(script)
    assertThat(calls.get()).isEqualTo(1)
  }

  @Test
  fun `a build that applies the plugin, or has opted out, never asks the CLI`() {
    val home = tmp.newFolder("home")
    val calls = AtomicInteger()
    val applies =
      tmp.newFolder("applies").apply {
        File(this, "build.gradle.kts").writeText("plugins { id(\"ee.schimke.composeai.preview\") }")
      }
    assertThat(InitScripts(emptyMap(), home, cli = writingCli(home, calls)).forProject(applies))
      .isNull()
    val plain = tmp.newFolder("plain")
    assertThat(
        InitScripts(
            mapOf("COMPOSE_PREVIEW_NO_AUTO_INJECT" to "1"),
            home,
            cli = writingCli(home, calls),
          )
          .forProject(plain)
      )
      .isNull()
    assertThat(calls.get()).isEqualTo(0)
  }

  @Test
  fun `a CLI that writes nothing leaves the old hint in place`() {
    val home = tmp.newFolder("home")
    val root = tmp.newFolder("plain")
    assertThat(InitScripts(emptyMap(), home, cli = { null }).forProject(root)).isNull()
  }

  @Test
  fun `the CLI is found from COMPOSE_PREVIEW_CLI, then PATH, then the installer's bin`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val home = tmp.newFolder("home")
    fun launcher(dir: File) =
      File(dir, "compose-preview").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n")
        setExecutable(true)
      }

    assertThat(CliInitScript.locate(emptyMap(), home)).isNull()
    val installed = launcher(File(home, ".local/bin"))
    assertThat(CliInitScript.locate(emptyMap(), home)).isEqualTo(installed)
    val onPath = launcher(tmp.newFolder("path-bin"))
    assertThat(CliInitScript.locate(mapOf("PATH" to onPath.parent), home)).isEqualTo(onPath)
    val named = launcher(tmp.newFolder("named"))
    assertThat(
        CliInitScript.locate(
          mapOf("COMPOSE_PREVIEW_CLI" to named.absolutePath, "PATH" to onPath.parent),
          home,
        )
      )
      .isEqualTo(named)
  }

  @Test
  fun `the subprocess runs init-script --path in the build and reads the printed path`() {
    assumeFalse(System.getProperty("os.name").orEmpty().startsWith("Windows"))
    val home = tmp.newFolder("home")
    val root = tmp.newFolder("plain")
    val script = File(home, ".cache/composeai/init/2.29.0/${InitScripts.FILE_NAME}")
    val args = File(tmp.root, "args.txt")
    File(home, ".local/bin/compose-preview").apply {
      parentFile.mkdirs()
      writeText(
        """
        |#!/bin/sh
        |echo "${'$'}PWD ${'$'}*" > ${args.absolutePath}
        |mkdir -p ${script.parent}
        |echo "// init" > ${script.absolutePath}
        |echo "Picked up JAVA_TOOL_OPTIONS: noise" >&2
        |echo ${script.absolutePath}
        """
          .trimMargin()
      )
      setExecutable(true)
    }

    val written = CliInitScript.subprocess(emptyMap(), home).materialize(root)
    assertThat(written).isEqualTo(script)
    assertThat(args.readText().trim()).isEqualTo("${root.canonicalPath} init-script --path")
  }
}
