package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A wear-os-samples-shaped checkout: the git root is not a build, each sample one level below is,
 * and a Claude Code worktree under `.claude/worktrees/` holds its own copy of a sample.
 */
class ProjectDiscoveryTest {

  @get:Rule val tmp = TemporaryFolder()

  private fun build(dir: File, settings: String = "settings.gradle.kts"): File = dir.apply {
    mkdirs()
    File(this, settings).writeText("")
  }

  private fun fixture(): File {
    val repo = tmp.newFolder("wear-os-samples")
    File(repo, ".git").mkdirs()
    build(File(repo, "ComposeStarter"))
    build(File(repo, "ComposeAdvanced"), settings = "settings.gradle")
    // Never searched: build outputs, node_modules, hidden dirs, and anything deeper than 2 levels.
    build(File(repo, "build/generated"))
    build(File(repo, "node_modules/pkg"))
    build(File(repo, ".hidden/sample"))
    build(File(repo, "nested/deeper/too-deep"))
    // A worktree-style nested build: a copy of the checkout, samples one level below it.
    build(File(repo, ".claude/worktrees/fix-ui/ComposeStarter"))
    File(repo, "ComposeStarter/app/src/main").mkdirs()
    File(repo, ".claude/worktrees/fix-ui/ComposeStarter/app/src").mkdirs()
    return repo.canonicalFile
  }

  @Test
  fun `a git root that is not a build finds the builds up to two levels below it`() {
    val repo = fixture()
    assertThat(ProjectDiscovery.buildsFor(repo))
      .containsExactly(File(repo, "ComposeAdvanced"), File(repo, "ComposeStarter"))
      .inOrder()
  }

  @Test
  fun `a root or cwd inside a build uses the nearest ancestor with settings gradle`() {
    val repo = fixture()
    assertThat(ProjectDiscovery.buildsFor(File(repo, "ComposeStarter/app/src/main")))
      .containsExactly(File(repo, "ComposeStarter"))
    assertThat(ProjectDiscovery.buildsFor(File(repo, "ComposeStarter")))
      .containsExactly(File(repo, "ComposeStarter"))
  }

  @Test
  fun `a root inside a claude worktree is its own candidate and never climbs to the checkout`() {
    val repo = fixture()
    val worktree = File(repo, ".claude/worktrees/fix-ui")
    assertThat(ProjectDiscovery.buildsFor(worktree))
      .containsExactly(File(worktree, "ComposeStarter"))
    assertThat(ProjectDiscovery.buildsFor(File(worktree, "ComposeStarter/app/src")))
      .containsExactly(File(worktree, "ComposeStarter"))
    // Even when the checkout itself is a build, a worktree without one stays empty.
    build(repo)
    File(worktree, "docs").mkdirs()
    assertThat(ProjectDiscovery.buildsFor(File(worktree, "docs"))).isEmpty()
  }

  @Test
  fun `a directory with no build anywhere near it finds nothing`() {
    val plain = tmp.newFolder("plain", "a", "b")
    assertThat(ProjectDiscovery.buildsFor(plain)).isEmpty()
  }
}
