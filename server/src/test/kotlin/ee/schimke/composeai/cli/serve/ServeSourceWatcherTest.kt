package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The source watcher behind edit-reload: real directories and the JDK's
 * [java.nio.file.WatchService] against a recording rebuild. Waits are bounded polls, generous for a
 * JDK that polls (macOS).
 */
class ServeSourceWatcherTest {
  private val root: File = Files.createTempDirectory("source-watcher").toFile()
  private val watchers = mutableListOf<ServeSourceWatcher>()

  @AfterTest
  fun tearDown() {
    watchers.forEach { it.close() }
    root.deleteRecursively()
  }

  private fun watch(
    roots: Map<String, List<WatchRoot>>,
    rebuild: (String) -> LocalRebuildResult,
  ): ServeSourceWatcher =
    ServeSourceWatcher(roots, rebuild, debounceMillis = 200, retryMillis = 100, onLog = {}).also {
      watchers += it
    }

  private fun eventually(timeoutMillis: Long = 15_000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
      if (condition()) return
      Thread.sleep(50)
    }
    assertTrue(condition(), "timed out")
  }

  /** Long enough for an event to arrive and its debounce to pass, on a native or polling JDK. */
  private fun settle() = Thread.sleep(3_000)

  @Test
  fun `a burst of saves rebuilds once, after the debounce`() {
    val src = File(root, "src/main/kotlin").apply { mkdirs() }
    val rebuilds = CopyOnWriteArrayList<String>()
    watch(mapOf("app" to listOf(WatchRoot(root, recursive = true)))) {
      rebuilds += it
      LocalRebuildResult.Reloaded(1)
    }

    repeat(5) { File(src, "Preview.kt").writeText("// edit $it") }

    eventually { rebuilds.isNotEmpty() }
    settle()
    assertEquals(listOf("app"), rebuilds)
  }

  @Test
  fun `build outputs and editor scratch files never trigger a rebuild`() {
    val build = File(root, "build/classes").apply { mkdirs() }
    val gradle = File(root, ".gradle").apply { mkdirs() }
    val src = File(root, "src").apply { mkdirs() }
    val rebuilds = AtomicInteger()
    watch(mapOf("app" to listOf(WatchRoot(root, recursive = true)))) {
      rebuilds.incrementAndGet()
      LocalRebuildResult.Reloaded(1)
    }

    File(build, "PreviewKt.class").writeText("compiled")
    File(gradle, "state").writeText("x")
    File(src, ".#Preview.kt").writeText("lock")
    File(src, "Preview.kt~").writeText("backup")
    File(root, "bazel-out").mkdirs()

    settle()
    assertEquals(0, rebuilds.get())
  }

  @Test
  fun `a file in a new package directory is seen`() {
    val rebuilds = AtomicInteger()
    watch(mapOf("app" to listOf(WatchRoot(root, recursive = true)))) {
      rebuilds.incrementAndGet()
      LocalRebuildResult.Reloaded(1)
    }

    val pkg = File(root, "src/main/kotlin/com/example").apply { mkdirs() }
    eventually { rebuilds.get() >= 1 }
    val seen = rebuilds.get()
    settle()
    File(pkg, "New.kt").writeText("// new")

    eventually { rebuilds.get() > seen }
  }

  @Test
  fun `an edit while the button's build runs is retried, not dropped`() {
    val attempts = AtomicInteger()
    watch(mapOf("app" to listOf(WatchRoot(root, recursive = true)))) {
      if (attempts.incrementAndGet() == 1) LocalRebuildResult.Busy
      else LocalRebuildResult.Reloaded(1)
    }

    File(root, "Preview.kt").writeText("// edit")

    eventually { attempts.get() == 2 }
  }

  @Test
  fun `each session rebuilds only for its own sources`() {
    val a = File(root, "a").apply { mkdirs() }
    val b = File(root, "b").apply { mkdirs() }
    val rebuilds = CopyOnWriteArrayList<String>()
    watch(
      mapOf(
        "a" to listOf(WatchRoot(a, recursive = true)),
        "b" to listOf(WatchRoot(b, recursive = true)),
      )
    ) {
      rebuilds += it
      LocalRebuildResult.Reloaded(1)
    }

    File(b, "Preview.kt").writeText("// edit")

    eventually { rebuilds.isNotEmpty() }
    settle()
    assertEquals(listOf("b"), rebuilds)
  }

  @Test
  fun `a Gradle module watches its project directory`() {
    assertEquals(
      listOf(WatchRoot(root, recursive = true)),
      ServeSourceWatcher.rootsFor(PreviewModule("app", root)),
    )
  }

  @Test
  fun `a Bazel module watches its sources' directories under the workspace, not the output base`() {
    val workspace = File(root, "ws").apply { mkdirs() }
    File(workspace, "app/src/ui").mkdirs()
    File(workspace, "lib").mkdirs()
    val outputBase = File(root, "ws/.compose-preview/outputs/abc")
    val output = File(outputBase, "build/compose-previews").apply { mkdirs() }
    File(output, "daemon-launch.json")
      .writeText(
        """
        {"schemaVersion":1,"modulePath":"app","variant":"jvm","enabled":true,
         "mainClass":"Main","classpath":[],"jvmArgs":[],"workingDirectory":"${workspace.path}",
         "manifestPath":"previews.json",
         "systemProperties":{"composeai.daemon.workspaceRoot":"${workspace.path}"}}
        """
          .trimIndent()
      )
    File(output, "build-artifacts.json")
      .writeText(
        """
        {"target":"//app:ui","sourceFiles":[
          "app/src/ui/Preview.kt","app/src/ui/Theme.kt","lib/Shared.kt",
          "bazel-out/k8-fastbuild/bin/app/Gen.kt","external/rules/Dep.kt"]}
        """
          .trimIndent()
      )

    assertEquals(
      listOf(
        WatchRoot(File(workspace, "app/src/ui"), recursive = false),
        WatchRoot(File(workspace, "lib"), recursive = false),
      ),
      ServeSourceWatcher.rootsFor(PreviewModule("app", outputBase)),
    )
  }
}
