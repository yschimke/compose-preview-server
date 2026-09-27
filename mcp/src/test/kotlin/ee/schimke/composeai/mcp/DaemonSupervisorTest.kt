package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import ee.schimke.composeai.daemon.client.WorkspaceId
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class DaemonSupervisorTest {
  @Test
  fun `registerProject tolerates root directory without explicit project name`() {
    val supervisor =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
      )

    val project = supervisor.registerProject(File("/"))

    assertThat(project.rootProjectName).isEqualTo("workspace")
    assertThat(project.workspaceId.value).startsWith("workspace-")
  }

  @Test
  fun `a restarted supervisor re-registers a stored workspace id from workspaces json`() {
    val root = createTempDirectory("cp-supervisor-store").toFile()
    val project = File(root, "ComposeStarter").apply { mkdirs() }
    val storeFile = File(root, "cache/composeai/mcp/workspaces.json")
    val first =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
        workspaceStore = WorkspaceStore(storeFile),
      )
    val id = first.registerProject(project).workspaceId
    first.shutdown()
    assertThat(storeFile.readText()).contains(project.canonicalPath)

    // A new process: nothing registered in memory, but the id still resolves, lazily.
    val restarted =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
        workspaceStore = WorkspaceStore(storeFile),
      )
    assertThat(restarted.listProjects()).isEmpty()
    assertThat(restarted.daemonFor(id, ":app").workspaceId).isEqualTo(id)
    assertThat(restarted.project(id)?.path).isEqualTo(project.canonicalFile)
    assertThat(restarted.registerProject(project).workspaceId).isEqualTo(id)
    restarted.shutdown()

    // Roots or the cwd inside a stored build bring it back too.
    val third =
      DaemonSupervisor(
        descriptorProvider = FakeDescriptorProvider(),
        clientFactory = FakeDaemonClientFactory(),
        workspaceStore = WorkspaceStore(storeFile),
      )
    assertThat(third.restoreMatching(listOf(File(project, "app/src"))).map { it.workspaceId })
      .containsExactly(id)

    // Only unregister_project forgets an id.
    third.unregisterProject(id)
    assertThat(WorkspaceStore(storeFile).get(id.value)).isNull()
    third.shutdown()
  }

  @Test
  fun `readingFromDisk resolves descriptor for a projectDir-remapped module`() {
    val root = createTempDirectory("cp-supervisor-test").toFile()
    // `:featureTasks` can be remapped to shared/features/tasks in settings.gradle.kts, so the
    // Gradle
    // path does NOT mirror the on-disk layout. The layout fast path (<root>/featureTasks) must
    // miss,
    // and the fallback scan must locate the descriptor by the modulePath recorded inside it.
    writeRemappedDescriptor(root)

    val project =
      RegisteredProject(
        workspaceId = WorkspaceId("ws-test"),
        rootProjectName = "client",
        path = root,
        knownModules = mutableListOf(":featureTasks"),
      )

    val descriptor = DescriptorProvider.readingFromDisk().descriptorFor(project, ":featureTasks")

    assertThat(descriptor.modulePath).isEqualTo(":featureTasks")
    assertThat(descriptor.variant).isEqualTo("desktop")
  }

  @Test
  fun `readingFromDisk rescans when a remapped descriptor appears after a miss`() {
    val root = createTempDirectory("cp-supervisor-test").toFile()
    val project =
      RegisteredProject(
        workspaceId = WorkspaceId("ws-test"),
        rootProjectName = "client",
        path = root,
        knownModules = mutableListOf(":featureTasks"),
      )
    // A single long-lived provider: the first lookup misses (no descriptor yet, so the scanned
    // index is empty), the descriptor is then generated (as the error tells the user to do), and a
    // second lookup on the SAME provider must resolve it — i.e. the empty scan is not cached as a
    // permanent negative for the remapped module.
    val provider = DescriptorProvider.readingFromDisk()

    val firstAttempt = runCatching { provider.descriptorFor(project, ":featureTasks") }
    assertThat(firstAttempt.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)

    writeRemappedDescriptor(root)

    val descriptor = provider.descriptorFor(project, ":featureTasks")
    assertThat(descriptor.modulePath).isEqualTo(":featureTasks")
    assertThat(descriptor.variant).isEqualTo("desktop")
  }

  /**
   * Writes a `:featureTasks` descriptor at the remapped `shared/features/tasks` path under [root].
   */
  private fun writeRemappedDescriptor(root: File) {
    val previewsDir = File(root, "shared/features/tasks/build/compose-previews")
    previewsDir.mkdirs()
    File(previewsDir, "daemon-launch.json")
      .writeText(
        """
        {
          "schemaVersion": 2,
          "modulePath": ":featureTasks",
          "variant": "desktop",
          "enabled": true,
          "mainClass": "ee.schimke.composeai.daemon.DaemonMain",
          "classpath": [],
          "jvmArgs": [],
          "systemProperties": {},
          "workingDirectory": "${previewsDir.parentFile.parent}",
          "manifestPath": "manifest.json"
        }
        """
          .trimIndent()
      )
  }
}
