package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDesignDiscoveryTest {
  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun `discovers designs in non Gradle roots and skips generated directories and symlinks`() {
    val root = tmp.newFolder("session")
    val design =
      File(root, "designs/Watch.uid").apply {
        parentFile.mkdirs()
        writeText("not read by discovery")
      }
    File(root, "build/hidden.uid").apply {
      parentFile.mkdirs()
      writeText("ignored")
    }
    File(root, ".git/hidden.uid").apply {
      parentFile.mkdirs()
      writeText("ignored")
    }
    val outside = tmp.newFolder("outside")
    File(outside, "unrelated.uid").writeText("ignored")
    Files.createSymbolicLink(File(root, "linked").toPath(), outside.toPath())
    val results = LocalDesignDiscovery.discover(listOf(root, design.parentFile))
    assertThat(results.map { it.path }).containsExactly(design.canonicalPath)
    assertThat(results.single().name).isEqualTo("Watch")
    design.delete()
    assertThat(LocalDesignDiscovery.discover(listOf(root))).isEmpty()
  }

  @Test
  fun `active roots are shared across live processes and disappear when sessions close`() {
    val directory = tmp.newFolder("registry")
    val root = tmp.newFolder("chat")
    val chat = ActiveDesignRoots(directory, 1L, 10L) { pid, start -> pid == 1L && start == 10L }
    val sidebar = ActiveDesignRoots(directory, 2L, 20L) { pid, start -> pid == 1L && start == 10L }
    try {
      chat.register("session", listOf(root))
      assertThat(sidebar.all()).containsExactly(root.canonicalFile)
      chat.remove("session")
      chat.update("session", listOf(root))
      assertThat(sidebar.all()).isEmpty()
      chat.register("session", listOf(root))
      val restarted = ActiveDesignRoots(directory, 3L, 30L) { _, _ -> false }
      assertThat(restarted.all()).isEmpty()
      restarted.close()
    } finally {
      chat.close()
      sidebar.close()
    }
  }
}
