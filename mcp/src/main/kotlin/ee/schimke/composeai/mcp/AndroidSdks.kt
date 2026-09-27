package ee.schimke.composeai.mcp

import java.io.File
import java.util.Properties

/**
 * Finds an Android SDK for a recompile whose build cannot see one: no `ANDROID_HOME` or
 * `ANDROID_SDK_ROOT` in this server's environment and no `sdk.dir` in the build's
 * `local.properties`. Without either, AGP fails every compile with "Could not determine the
 * dependencies of task ':app:compileDebugKotlin'", which is what a Claude Code worktree does: it
 * has no copy of the untracked `local.properties`.
 *
 * It never writes `local.properties`; [GradleSourceCompiler] passes the SDK to its Gradle child as
 * `ANDROID_HOME`. Tried in order:
 * 1. the main checkout's `local.properties`, when the build is in a git worktree;
 * 2. `~/Android/Sdk`, `~/Library/Android/sdk`, `%LOCALAPPDATA%\Android\Sdk`.
 */
class AndroidSdks(
  private val environment: Map<String, String> = System.getenv(),
  private val userHome: File = File(System.getProperty("user.home") ?: "."),
) {

  /** The SDK to pass as `ANDROID_HOME`, or null when the build already has one or none exists. */
  fun forBuild(projectRoot: File): File? {
    if (listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { !environment[it].isNullOrBlank() }) {
      return null
    }
    if (sdkDir(File(projectRoot, "local.properties")) != null) return null
    val candidates = buildList {
      mainCheckoutOf(projectRoot)?.let { main ->
        sdkDir(File(main, "local.properties"))?.let(::add)
      }
      add(File(userHome, "Android/Sdk"))
      add(File(userHome, "Library/Android/sdk"))
      environment["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let { add(File(it, "Android/Sdk")) }
    }
    return candidates.firstOrNull(File::isDirectory)
  }

  private fun sdkDir(localProperties: File): File? {
    if (!localProperties.isFile) return null
    val properties = Properties()
    runCatching { localProperties.reader().use(properties::load) }.getOrElse { return null }
    return properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let(::File)
  }

  companion object {
    /**
     * For a build inside a git worktree, the same directory in the main checkout. A worktree's
     * `.git` is a file (`gitdir: <main>/.git/worktrees/<name>`), and that directory's `commondir`
     * leads back to the main `.git` (what `git rev-parse --git-common-dir` prints). This reads the
     * two files rather than running git.
     */
    fun mainCheckoutOf(projectRoot: File): File? {
      val start = projectRoot.absoluteFile
      var top: File? = start
      while (top != null && !File(top, ".git").exists()) top = top.parentFile
      val worktreeRoot = top ?: return null
      val dotGit = File(worktreeRoot, ".git").takeIf(File::isFile) ?: return null
      val gitDir =
        dotGit
          .readLines()
          .firstOrNull { it.startsWith("gitdir:") }
          ?.removePrefix("gitdir:")
          ?.trim()
          ?.let { resolve(worktreeRoot, it) } ?: return null
      val commonDir =
        File(gitDir, "commondir").takeIf(File::isFile)?.readText()?.trim()?.let {
          resolve(gitDir, it)
        } ?: return null
      val mainRoot = commonDir.canonicalFile.parentFile ?: return null
      val relative = start.relativeTo(worktreeRoot).path
      return if (relative.isEmpty()) mainRoot else File(mainRoot, relative)
    }

    private fun resolve(base: File, path: String): File =
      File(path).let { if (it.isAbsolute) it else File(base, path) }
  }
}
