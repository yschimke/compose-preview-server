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

  /** Read envelope for the document. Generous for a config file, tiny for a fetch. */
  const val MAX_BYTES: Long = 256L * 1024

  /**
   * Most entries one registry may contribute. A bound rather than a limit anyone should meet: the
   * startup loader fetches every catalog sequentially, so a registry that grew a thousand entries —
   * by accident or otherwise — would be a box that never finishes booting.
   */
  const val MAX_ENTRIES: Int = 200

  private val REPO_RE = Regex("[A-Za-z0-9._-]{1,64}/[A-Za-z0-9._-]{1,64}")

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
   * Parse `--catalog-registry` (comma-separated `<owner>/<repo>`), reporting anything malformed
   * rather than failing the boot: a typo'd registry should cost that registry's catalogs, not the
   * server.
   */
  fun parseRepos(raw: String?, onProblem: (String) -> Unit = {}): List<String> =
    raw
      ?.split(",")
      ?.map { it.trim() }
      ?.filter { it.isNotEmpty() }
      ?.filter {
        REPO_RE.matches(it).also { ok ->
          if (!ok) onProblem("--catalog-registry '$it' is not an <owner>/<repo>")
        }
      }
      ?.distinct() ?: emptyList()

  /** The raw URL [FILE_PATH] is read from — the project's default branch, whatever it is called. */
  fun documentUrl(repo: String): String = "https://raw.githubusercontent.com/$repo/HEAD/$FILE_PATH"

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
    repo: String,
    fetch: (url: String, maxBytes: Long) -> ByteArray?,
    onProblem: (String) -> Unit = {},
  ): Contribution? {
    val url = documentUrl(repo)
    val bytes =
      runCatching { fetch(url, MAX_BYTES) }
        .getOrElse {
          onProblem("catalog registry $repo: could not read $FILE_PATH (${it.message})")
          null
        } ?: return null
    val parsed = runCatching {
      ServeCatalogsConfig.parse(bytes.toString(Charsets.UTF_8))
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
