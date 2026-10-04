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
  fun `directory entry budget stops lazy iteration and closes the stream`() {
    val root = tmp.newFolder("large")
    var visited = 0
    var closed = false
    val results =
      LocalDesignDiscovery.discover(listOf(root)) {
        object : java.nio.file.DirectoryStream<java.nio.file.Path> {
          override fun iterator(): MutableIterator<java.nio.file.Path> =
            object : MutableIterator<java.nio.file.Path> {
              override fun hasNext(): Boolean = true

              override fun next(): java.nio.file.Path {
                visited++
                return root.toPath().resolve("entry-$visited.txt")
              }

              override fun remove() = error("unused")
            }

          override fun close() {
            closed = true
          }
        }
      }
    assertThat(results).isEmpty()
    assertThat(visited).isEqualTo(10_000)
    assertThat(closed).isTrue()
  }

  @Test
  fun `sharing requires an explicit matching scope and ignores legacy records`() {
    val directory = tmp.newFolder("scoped-registry")
    val root = tmp.newFolder("private-chat")
    val writer = ActiveDesignRoots(directory, scope = "host-one")
    val sameHost = ActiveDesignRoots(directory, scope = "host-one")
    val otherHost = ActiveDesignRoots(directory, scope = "host-two")
    val unscoped = ActiveDesignRoots(directory)
    try {
      writer.register("session", listOf(root))
      assertThat(sameHost.all()).containsExactly(root.canonicalFile)
      assertThat(otherHost.all()).isEmpty()
      assertThat(unscoped.all()).isEmpty()
      unscoped.register("local-session", listOf(root))
      assertThat(unscoped.all()).containsExactly(root.canonicalFile)
      val anotherUnscoped = ActiveDesignRoots(directory)
      assertThat(anotherUnscoped.all()).isEmpty()
      anotherUnscoped.close()
      File(directory, "legacy.json")
        .writeText(
          """{"pid":${ProcessHandle.current().pid()},"start":0,"roots":["/private/legacy"]}"""
        )
      assertThat(sameHost.all()).containsExactly(root.canonicalFile)
      writer.close()
      assertThat(sameHost.all()).isEmpty()
    } finally {
      writer.close()
      sameHost.close()
      otherHost.close()
      unscoped.close()
    }
  }

  @Test
  fun `active roots are shared across live processes and disappear when sessions close`() {
    val directory = tmp.newFolder("registry")
    val root = tmp.newFolder("chat")
    val chat =
      ActiveDesignRoots(directory, 1L, 10L, scope = "host-one") { pid, start ->
        pid == 1L && start == 10L
      }
    val sidebar =
      ActiveDesignRoots(directory, 2L, 20L, scope = "host-one") { pid, start ->
        pid == 1L && start == 10L
      }
    try {
      chat.register("session", listOf(root))
      assertThat(sidebar.all()).containsExactly(root.canonicalFile)
      chat.remove("session")
      chat.update("session", listOf(root))
      assertThat(sidebar.all()).isEmpty()
      chat.register("session", listOf(root))
      val restarted = ActiveDesignRoots(directory, 3L, 30L, scope = "host-one") { _, _ -> false }
      assertThat(restarted.all()).isEmpty()
      restarted.close()
    } finally {
      chat.close()
      sidebar.close()
    }
  }
}
