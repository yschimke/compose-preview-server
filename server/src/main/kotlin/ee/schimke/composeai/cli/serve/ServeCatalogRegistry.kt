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
   * Refs tried, in order, for a nomination naming none. `HEAD` (raw.githubusercontent.com's
   * default-branch alias) goes first; `main` and `master` recover a project whose default branch is
   * misconfigured, without ever overriding a correct one. Pin anything else with
   * `<owner>/<repo>@<ref>`.
   */
  val DEFAULT_REF_CANDIDATES: List<String> = listOf("HEAD", "main", "master")

  /** Read envelope for the document. Generous for a config file, tiny for a fetch. */
  const val MAX_BYTES: Long = 256L * 1024

  /**
   * Most entries one registry may contribute. A runaway bound: startup fetches catalogs
   * sequentially, so thousands of entries would never finish booting.
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
   * Fetch and normalise one registry's document, or null when absent, unreadable or unparseable.
   * Best-effort: this runs on a timer, so a transient failure must leave the box serving what it
   * already serves.
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
          // Best-effort still owes the operator a reason, or a missing registry is
          // indistinguishable from the flag never arriving.
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
        // A claim on an undeclared group falls back to the owner heading, as for an unattributed
        // catalog, rather than dropping the catalog.
        val group = pinned.group?.takeIf { id -> groups.any { it.id == id } }
        add(pinned.copy(group = group))
      }
    }
    return Contribution(repo = repo, entries = entries, groups = groups)
  }
}
