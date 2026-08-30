package ee.schimke.composeai.cli.serve

/**
 * **Catalogs discovered from a nominated GitHub project**, instead of named one at a time.
 *
 * ### Why this exists
 *
 * Publishing a catalog and *serving* it were two unrelated acts with nothing joining them. A
 * project's CI force-pushes its `design-artifacts/<system>` branch and stops there; the branch is
 * complete, verifiable and reachable, and the box serves a 404 for it — permanently, not until the
 * next refresh — because the served set is enumerated by hand in `--catalogs` / `catalogs.json`.
 * Every publisher therefore needed a second, manual, out-of-band step against the box before any of
 * its work was visible.
 *
 * That is tolerable while the publishers are a handful of first-party repos. It stops being
 * tolerable for `yschimke/compose-preview-imports`, whose entire purpose is to onboard third-party
 * projects *by pull request*: merging the PR builds the upstream project and force-pushes a
 * delivery branch, and the reviewer's mental model — reasonably — is that merging is the import.
 * With the served set enumerated elsewhere, it wasn't: `joreilly-peopleinspace` published cleanly
 * and 404'd for as long as nobody edited the box.
 *
 * So a server may nominate one or more **registry projects** (`--catalog-registry
 * yschimke/compose-preview-imports`). Each publishes [FILE_PATH] on its default branch, and every
 * catalog listed there is served exactly as a `catalogs.json` entry would be. Landing the PR is
 * then genuinely the whole import, and [ServeCatalogRegistrySync] picks it up without a restart.
 *
 * ### What nominating a registry delegates, and what it does not
 *
 * It delegates **which catalogs from that project are served, and how they are grouped on the front
 * page**. It deliberately does not delegate *where bytes may come from*:
 * - an entry may only be served from the registry project itself, so a compromised or careless
 *   registry can publish its own branches and nothing else. An entry naming another repo is
 *   dropped, loudly. (Serving a catalog out of somebody else's repository stays an operator
 *   decision — `--catalogs <system>@<owner>/<repo>`, or an admin `POST`.)
 * - group claims resolve against the registry document's **own** group table, never the box's, so a
 *   registry cannot file its catalogs under a heading the operator reserved for first-party design
 *   systems.
 * - the operator's own configuration wins on any id collision ([ServeRunner.catalogRefs]
 *   de-duplicates first-wins with the registry last), so nominating a registry can never
 *   re-attribute a catalog the box already names.
 *
 * The document is the ordinary [ServeCatalogsConfig] shape, so a registry project's file is
 * readable, diffable and validated by exactly the code the box's own config goes through — and a
 * project can move from "the operator lists my catalogs" to "I list them myself" by copying its
 * entries across unchanged. `sites` in a registry document are ignored: a hostname is the box's to
 * hand out.
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
    // First ref that answers wins — one request for a project on `main`, and no dependence on the
    // `HEAD` alias being current. See [DEFAULT_REF_CANDIDATES].
    var bytes: ByteArray? = null
    for (ref in nomination.refs) {
      bytes =
        runCatching { fetch(documentUrl(repo, ref), MAX_BYTES) }
          .getOrElse {
            onProblem("catalog registry $repo: could not read $FILE_PATH at $ref (${it.message})")
            null
          }
      if (bytes != null) break
    }
    val body = bytes ?: return null
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
