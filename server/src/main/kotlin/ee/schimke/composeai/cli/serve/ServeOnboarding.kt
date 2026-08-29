package ee.schimke.composeai.cli.serve

import java.util.concurrent.TimeUnit

/**
 * **Onboarding a GitHub project in one step**: hand the server a repository URL and it publishes
 * every catalog that repository already delivers.
 *
 * ### Why this exists
 *
 * Publishing a catalog on a running box is possible today ([ServeCatalogAdmin]) but only for
 * someone who already knows the shape of the delivery contract: that a catalog is a
 * `design-artifacts/<system>` branch, that `<system>` is simultaneously the branch suffix, the
 * `/<system>/` route and the id in `catalogs.json`, and therefore that publishing `compose-m3` from
 * `yschimke/compose-ai-tools` means posting
 * `{"system":"compose-m3","repo":"yschimke/compose-ai-tools"}`. A project that has run
 * `compose-preview publish` has all of that written down already — in its refs. Asking a newcomer
 * to restate it, exactly, as JSON is the whole of the onboarding friction (issue #4789).
 *
 * So this reads it instead. One `git ls-remote --heads` over the repository
 * ([listDeliveryBranches]) enumerates the branches whose names start with the server's
 * [branchPrefix]; each suffix is a catalog id, and each one is published through
 * [ServeCatalogAdmin.register] — the *same* path a hand-written `POST /admin/catalogs` takes, so an
 * onboarded catalog is in every way an ordinary one, persisted to `catalogs.json` and back after a
 * restart.
 *
 * ### What it deliberately does not do
 *
 * It discovers nothing that isn't already published as a delivery branch. A repository that has
 * never run `compose-preview publish` has no `design-artifacts/` delivery refs, and the honest
 * answer is that there is nothing here to serve yet ([Result.Empty]) — not a build, not a clone of
 * the project's sources. Onboarding stays a *registration* step; producing the artifacts remains
 * the producer's job.
 *
 * It also grants no trust. A newly onboarded catalog serves and badges `unverified` exactly as one
 * published by hand does, until its producer is trusted ([ServeTrustAdmin]). Discovery reading a
 * repository's public refs is not an assertion about that repository.
 *
 * ### Partial success is the normal case
 *
 * A repository can deliver several catalogs, and they fail independently: one branch may be a
 * newly-pushed catalog, another may already be published here, a third may not fetch. So the result
 * is per-catalog ([Result.Ok.catalogs]) rather than one verdict — the caller sees which ids landed
 * and why the rest didn't, instead of a single 502 that hides the two that worked.
 */
class ServeOnboarding(
  /** Publishes each discovered catalog. The same administrator `POST /admin/catalogs` uses. */
  private val admin: ServeCatalogAdmin,
  /** The server's `--catalog-branch-prefix` (`design-artifacts/`). */
  private val branchPrefix: String,
  /**
   * Branch names in a repository, or null when the repository couldn't be read at all (no such
   * repo, private, git absent, network down). Null and empty are different answers — see
   * [Result.Unreachable] vs [Result.Empty] — which is why this isn't just a list.
   */
  private val listDeliveryBranches: (repo: String) -> List<String>? = { gitLsRemoteBranches(it) },
  private val onLog: (String) -> Unit = { System.err.println(it) },
) {

  /** What became of one discovered branch. */
  data class Catalog(
    /** The catalog id: the branch name with [branchPrefix] removed. */
    val system: String,
    /** `published`, `already-published`, `invalid`, or `failed`. */
    val status: String,
    /** Why, for the two statuses that are a refusal. Null when there's nothing to explain. */
    val detail: String? = null,
  ) {
    /** Whether this catalog is being served now — either newly published or already here. */
    val served: Boolean
      get() = status == PUBLISHED || status == ALREADY_PUBLISHED
  }

  /** The outcome of an onboarding request, mapped to an HTTP status by the caller. */
  sealed interface Result {
    /**
     * The repository was read and its delivery branches processed. [catalogs] is per-branch and may
     * contain failures; [served] says whether anything is actually serving as a result.
     */
    data class Ok(val repo: String, val catalogs: List<Catalog>) : Result {
      val served: List<Catalog>
        get() = catalogs.filter { it.served }
    }

    /** Not a GitHub project URL — a 400. */
    data class Invalid(val reason: String) : Result

    /** The repository couldn't be read: missing, private, or git/network trouble — a 502. */
    data class Unreachable(val repo: String, val reason: String) : Result

    /**
     * The repository is readable but publishes no delivery branches — a 404. Carries the
     * [branchPrefix] it was looked for under, so the caller's message can name what is missing
     * rather than a contract the reader has to already know.
     */
    data class Empty(val repo: String, val branchPrefix: String) : Result
  }

  /**
   * Onboard [rawUrl]: discover its delivery branches and publish each one.
   *
   * [group] and [listed] are applied to every catalog discovered, because they are properties of
   * *this box's* presentation rather than of the project — an operator onboarding a repository is
   * making one decision about where its cards belong, not one per branch.
   */
  fun onboard(
    rawUrl: String,
    group: String? = null,
    listed: Boolean = true,
  ): Result {
    val project =
      GithubProject.parse(rawUrl) ?: return Result.Invalid("'$rawUrl' is not a GitHub project URL")
    val repo = project.slug
    val branches =
      runCatching { listDeliveryBranches(repo) }
        .getOrElse {
          return Result.Unreachable(repo, it.message ?: "could not list branches")
        }
        ?: return Result.Unreachable(
          repo,
          "could not read $repo — is it public, and does it exist?",
        )
    // Sorted and de-duplicated so a repository with several catalogs onboards in a stable order:
    // the response lists them the same way twice running, and so does the log.
    val systems =
      branches
        .filter { it.startsWith(branchPrefix) }
        .map { it.removePrefix(branchPrefix) }
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
    if (systems.isEmpty()) return Result.Empty(repo, branchPrefix)
    val catalogs = systems.map { publish(it, repo, group, listed) }
    onLog(
      "serve: onboarded $repo — ${catalogs.count { it.served }}/${catalogs.size} catalog(s) serving"
    )
    return Result.Ok(repo, catalogs)
  }

  /** Publish one discovered catalog, translating the administrator's verdict into a [Catalog]. */
  private fun publish(
    system: String,
    repo: String,
    group: String?,
    listed: Boolean,
  ): Catalog {
    val entry =
      ServeCatalogsConfig.Entry(system = system, repo = repo, listed = listed, group = group)
    // Validated here as well as inside `register`, so a branch this server could never route
    // (`design-artifacts/status.json`, say) is reported against its own id rather than aborting
    // the sibling catalogs that are perfectly publishable.
    ServeCatalogsConfig.validateEntry(entry)?.let {
      return Catalog(system, INVALID, it)
    }
    return when (val result = admin.register(entry)) {
      is ServeCatalogAdmin.Result.Ok -> Catalog(system, PUBLISHED, result.warning)
      // The administrator answers Conflict for "already exactly this", which for onboarding is
      // success: the caller asked for the repository to be served here, and it is. Re-onboarding a
      // project is therefore idempotent, the property that makes it safe to paste a URL twice.
      is ServeCatalogAdmin.Result.Conflict -> Catalog(system, ALREADY_PUBLISHED, result.reason)
      is ServeCatalogAdmin.Result.Invalid -> Catalog(system, INVALID, result.reason)
      is ServeCatalogAdmin.Result.Failed -> Catalog(system, FAILED, result.reason)
    }
  }

  companion object {
    const val PUBLISHED = "published"
    const val ALREADY_PUBLISHED = "already-published"
    const val INVALID = "invalid"
    const val FAILED = "failed"
  }
}

/**
 * A GitHub repository named by whatever a person had on their clipboard.
 *
 * The point of the onboarding flow is that the operator pastes *a URL*, so every spelling of one
 * that a browser address bar, a clone dialog or a README badge produces has to resolve to the same
 * `<owner>/<repo>`: the deep link they were reading when they decided to onboard
 * (`.../tree/main/ui`), the SSH remote, the `.git` suffix, a trailing slash, a `?tab=` query.
 */
data class GithubProject(val owner: String, val repo: String) {
  /** The `<owner>/<repo>` slug the rest of the server identifies a source repository by. */
  val slug: String
    get() = "$owner/$repo"

  companion object {
    /**
     * Deliberately the same conservative alphabet [ServeCatalogsConfig] validates a configured repo
     * against — an onboarded entry is written to `catalogs.json` and has to survive being read back
     * by a server that never heard of onboarding.
     */
    private val SEGMENT = Regex("[A-Za-z0-9._-]{1,64}")

    /** The owner/repo in [raw], or null when it isn't a GitHub project reference at all. */
    fun parse(raw: String): GithubProject? {
      var text = raw.trim()
      if (text.isEmpty()) return null
      // Strip anything after the path: `?tab=readme`, `#readme`.
      text = text.substringBefore('?').substringBefore('#')
      // scp-style SSH remotes (`git@github.com:owner/repo.git`) aren't URLs and won't parse as
      // one, so normalise them into the https spelling first.
      text = text.removePrefix("git@github.com:").removePrefix("ssh://git@github.com/")
      for (prefix in listOf("https://", "http://", "git://", "www.")) text =
        text.removePrefix(prefix)
      text = text.removePrefix("www.")
      if (text.startsWith("github.com/")) {
        text = text.removePrefix("github.com/")
      } else if (text.contains("://") || text.contains(".com/") || text.contains(".org/")) {
        // Some other host entirely. Refused rather than half-accepted: this server fetches
        // delivery branches from github.com, so a GitLab URL that parsed would be onboarded as a
        // GitHub repo of the same name — a different project, quietly.
        return null
      }
      val segments = text.trim('/').split('/').filter { it.isNotEmpty() }
      if (segments.size < 2) return null
      val owner = segments[0]
      // `.git` only ever terminates the repository segment; a repo may legitimately contain dots.
      val repo = segments[1].removeSuffix(".git")
      // A deeper path (`/tree/main/...`, `/issues/12`) is where the person happened to be reading,
      // not part of the project's identity, so it's dropped rather than rejected.
      if (!SEGMENT.matches(owner) || !SEGMENT.matches(repo)) return null
      return GithubProject(owner, repo)
    }
  }
}

/**
 * Branch names in a public GitHub repository via `git ls-remote --heads`, or null when the
 * repository couldn't be read.
 *
 * `ls-remote` rather than the GitHub branches API for the reason [gitLsRemoteHead] uses it: it is
 * unauthenticated and unrated, where the API would spend one of 60 calls an hour on what is meant
 * to be a paste-a-URL interaction. Hardened the same way, too — stdout is drained on a daemon
 * thread so a remote that stalls without closing the pipe can't wedge the calling request thread
 * past the bounded [WAIT_SECONDS] wait.
 */
fun gitLsRemoteBranches(repo: String): List<String>? = runCatching {
  val proc =
    ProcessBuilder("git", "ls-remote", "--heads", "https://github.com/$repo.git")
      .redirectErrorStream(true)
      .start()
  proc.outputStream.close()
  val captured = StringBuilder()
  val reader = Thread {
    runCatching { proc.inputStream.bufferedReader().use { r -> captured.append(r.readText()) } }
  }
    .apply {
      isDaemon = true
      start()
    }
  if (!proc.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
    proc.destroyForcibly()
    return null
  }
  reader.join(2_000)
  // A missing or private repository exits non-zero (git prompts for no credentials in this
  // environment); an empty repository exits zero with no refs. Only the first is unreadable.
  if (proc.exitValue() != 0) return null
  captured
    .toString()
    .lineSequence()
    .mapNotNull { line -> line.substringAfter("refs/heads/", "").trim().takeIf { it.isNotEmpty() } }
    .toList()
}
  .getOrNull()

/** Longest a `ls-remote` may take before the onboarding request gives up on the repository. */
private const val WAIT_SECONDS = 20L
