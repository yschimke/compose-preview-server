package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test

class SourceTreeTest {
  @Test
  fun `a test source set created after the first walk is not watched`() {
    val module = createTempDirectory("cp-source-tree").toFile()
    File(module, "build.gradle.kts").writeText("")
    val src = File(module, "src")
    File(src, "main/kotlin").mkdirs()
    File(src, "main/kotlin/A.kt").writeText("fun a() {}")
    val tree = SourceTree(module, budgetMs = 10_000)
    assertThat(tree.refresh().initial).isTrue()

    // #1186: src/test and a new main-side source set appear later.
    File(src, "test/kotlin").mkdirs()
    File(src, "test/kotlin/ATest.kt").writeText("class ATest")
    File(src, "debug/kotlin").mkdirs()
    val debug = File(src, "debug/kotlin/D.kt").apply { writeText("fun d() {}") }
    // Make sure the src/ mtime moves even on a coarse-grained filesystem clock.
    src.setLastModified(src.lastModified() - 10_000)

    assertThat(tree.refresh().changed.map { it.canonicalFile }).containsExactly(debug.canonicalFile)
    File(src, "test/kotlin/ATest.kt").writeText("class ATest { val edited = 1 }")
    assertThat(tree.refresh().changed).isEmpty()
  }
}
