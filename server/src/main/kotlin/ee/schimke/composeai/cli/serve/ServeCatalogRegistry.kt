package ee.schimke.composeai.cli.serve

/**
 * **Catalogs discovered from a nominated GitHub project**, so publishing a delivery branch (for
 * example by merging an import PR) is enough to get it served, with no manual `--catalogs` edit.
 *
 * A server may nominate **registry projects** (`--catalog-registry <owner>/<repo>`); each publishes
 * [FILE_PATH] on its default branch, and [ServeCatalogRegistrySync] serves every entry as a
 * `catalogs.json` entry would be, without a restart.
 *
 * A registry delegates which of its catalogs are served and how they are grouped, never where bytes
 * come from:
 * - an entry is served only from the registry project itself; others are dropped, loudly;
 * - group claims resolve against the registry document's own group table, never the box's;
 * - the operator's configuration wins every id collision ([ServeRunner.catalogRefs]).
 *
 * The document is the ordinary [ServeCatalogsConfig] shape; its `sites` are ignored.
 */
object ServeCatalogRegistry {

  /** Where a registry project publishes its served set, on its default branch. */
  const val FILE_PATH: String = ".compose-preview/catalogs.json"

  /**
   * Refs tried, in order, for a nomination that names none: the project's default branch first,
   * then the two names it is almost always called.
   *
   * `raw.githubusercontent.com` exposes a `HEAD` alias for a repository's default branch, which is
   * exactly the question being asked — "whatever that project calls its default branch" — so it
   * goes first and answers in one request.
   *
   * The fallbacks exist for the case that actually bit: `yschimke/compose-preview-imports` had its
   * default branch pointing at something other than `main`, so `HEAD` faithfully served a tree that
   * did not contain the freshly-merged document, and the first box to boot against it would have
   * reported a live registry as absent. (That looked like a stale CDN and was described as one when
   * these candidates were introduced — it was not. `HEAD` was correct about a repository that was
   * misconfigured, and reading `…/HEAD/README.md` returned 200 only because that path exists on the
   * other branch too.)
   *
   * Trying `main` and `master` after it recovers that specific misconfiguration without ever
   * overriding a correctly-set default branch — `HEAD` having answered, the fallbacks are not
   * reached — which is why the order is this way round and not the other. A project whose default
   * branch is genuinely neither, and which needs a ref pinned anyway (a tag, a release branch), can
   * say so: `<owner>/<repo>@<ref>`.
   */
  val DEFAULT_REF_CANDIDATES: List<String> = listOf("HEAD", "main", "master")

  /** Read envelope for the document. Generous for a config file, tiny for a fetch. */
  const val MAX_BYTES: Long = 256L * 1024

  /**
   * Most entries one registry may contribute. A bound rather than a limit anyone should meet: the
   * startup loader fetches every catalog sequentially, so a registry that grew a thousand entries —
   * by accident or otherwise — would be a box that never finishes booting.
   */
  const val MAX_ENTRIES: Int = 200

  private val REPO_RE = Regex("[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,64}")

  /**
   * A ref goes into a URL path unescaped, so it stays in the conservative branch/tag alphabet —
   * slashes allowed (`release/2.x` is an ordinary branch name), `..` and whitespace not.
   */
  private val REF_RE = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")

  /**
   * One nominated registry project: the repository, and optionally the ref its document is read
   * from. A null [ref] means [DEFAULT_REF_CANDIDATES] — see there for why that is not just `HEAD`.
   */
  data class Nomination(val repo: String, val ref: String? = null) {
    /** The refs to try, in order. */
    val refs: List<String>
      get() = ref?.let { listOf(it) } ?: DEFAULT_REF_CANDIDATES

    override fun toString(): String = ref?.let { "$repo@$it" } ?: repo
  }

  /** One registry project's contribution: its validated, repo-normalised entries. */
  data class Contribution(
    val repo: String,
    val entries: List<ServeCatalogsConfig.Entry>,
    val groups: List<ServeCatalogsConfig.Group>,
  ) {
    /** The front-page section [entry] claims, resolved against this registry's own groups. */
    fun homeGroup(entry: ServeCatalogsConfig.Entry): ServeWeb.HomeGroup? =
      ServeCatalogAdmin.homeGroup(entry, repo, groups)
  }

  /**
   * Parse `--catalog-registry` (comma-separated `<owner>/<repo>`, each optionally `@<ref>`),
   * reporting anything malformed rather than failing the boot: a typo'd registry should cost that
   * registry's catalogs, not the server.
   */
  fun parseRepos(raw: String?, onProblem: (String) -> Unit = {}): List<Nomination> =
    raw
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.mapNotNull { entry ->
        val at = entry.indexOf('@')
        val repo = if (at < 0) entry else entry.substring(0, at).trim()
        val ref = if (at < 0) null else entry.substring(at + 1).trim().ifEmpty { null }
        when {
          !REPO_RE.matches(repo) -> {
            onProblem("--catalog-registry '$entry' is not an <owner>/<repo>")
            null
          }
          ref != null && !REF_RE.matches(ref) -> {
            onProblem("--catalog-registry '$entry' does not name a usable ref")
            null
          }
          else -> Nomination(repo, ref)
        }
      }
      ?.distinct() ?: emptyList()

  /** The raw URL [FILE_PATH] is read from on one ref. */
  fun documentUrl(repo: String, ref: String): String =
    "https://raw.githubusercontent.com/$repo/$ref/$FILE_PATH"

  /**
   * Fetch and normalise one registry project's document, or null when it has none / it could not be
   * read or parsed.
   *
   * Best-effort by construction: a registry that is unreachable, absent or malformed leaves the box
   * serving exactly what it already serves. That is the same failure posture the rest of the
   * catalog machinery has — a branch that can't be fetched is skipped, not fatal — and it matters
   * more here, because the document is fetched again on a timer: a transient failure that took
   * catalogs away would take them away every time GitHub hiccuped.
   */
  fun fetch(
    nomination: Nomination,
    fetch: (url: String, maxBytes: Long) -> ByteArray?,
    onProblem: (String) -> Unit = {},
  ): Contribution? {
    val repo = nomination.repo
    // First ref that answers wins — one request for a project on `HEAD`, and no dependence on any
    // one ref name. See [DEFAULT_REF_CANDIDATES].
    var bytes: ByteArray? = null
    val tried = mutableListOf<String>()
    for (ref in nomination.refs) {
      tried += ref
      bytes =
        runCatching { fetch(documentUrl(repo, ref), MAX_BYTES) }
          .getOrElse {
            onProblem("catalog registry $repo: could not read $FILE_PATH at $ref (${it.message})")
            null
          }
      if (bytes != null) break
    }
    val body =
      bytes
        ?: run {
          // **Say so.** This return used to be silent, on the reasoning that a best-effort read
          // leaves the box serving what it already serves. That is the right BEHAVIOUR and was the
          // wrong SILENCE: on preview.coo.ee the boot read of a live, reachable registry returned
          // nothing, and because it said nothing the logs held no trace of a registry at all —
          // indistinguishable from the flag never arriving, which is where the debugging went.
          // A best-effort read still owes an operator the reason it gave up.
          onProblem(
            "catalog registry $repo: no $FILE_PATH at ${tried.joinToString("/")} — " +
              "contributing no catalogs this pass"
          )
          return null
        }
    val parsed = runCatching {
      ServeCatalogsConfig.parse(body.toString(Charsets.UTF_8))
    }
      .getOrElse {
        onProblem("catalog registry $repo: $FILE_PATH is not readable (${it.message})")
        return null
      }
    return normalize(repo, parsed, onProblem)
  }

  /**
   * The usable half of [document] as this registry's contribution: entries validated, their repo
   * pinned to [repo], and anything the registry may not ask for dropped with a reason.
   */
  fun normalize(
    repo: String,
    document: ServeCatalogsConfig,
    onProblem: (String) -> Unit = {},
  ): Contribution {
    val groups = document.groups.filter { ServeCatalogsConfig.validateGroup(it) == null }
    val seen = linkedSetOf<String>()
    val entries = buildList {
      for (entry in document.catalogs) {
        if (size >= MAX_ENTRIES) {
          onProblem("catalog registry $repo: more than $MAX_ENTRIES entries — ignoring the rest")
          break
        }
        // A registry serves its OWN branches. Anything else is a redirection of the box at a
        // third party, which is the operator's call to make and not a publisher's.
        val named = entry.repo?.takeIf { it.isNotBlank() }
        if (named != null && named != repo) {
          onProblem("catalog registry $repo: entry '${entry.system}' names $named — ignored")
          continue
        }
        val pinned = entry.copy(repo = repo, attributionRepos = emptyList())
        val problem = ServeCatalogsConfig.validateEntry(pinned)
        if (problem != null) {
          onProblem("catalog registry $repo: $problem")
          continue
        }
        if (!seen.add(pinned.system)) {
          onProblem("catalog registry $repo: duplicate entry '${pinned.system}' — ignored")
          continue
        }
        // A claim on a group this document doesn't declare falls back to the owner heading, the
        // same way an unattributed catalog does. Dropping the catalog over its placement would
        // trade a misfiled card for a missing one.
        val group = pinned.group?.takeIf { id -> groups.any { it.id == id } }
        add(pinned.copy(group = group))
      }
    }
    return Contribution(repo = repo, entries = entries, groups = groups)
  }
}
