package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping

/**
 * Historical permalinks: pinning a served catalog page to one delivery-branch commit.
 *
 * A catalog URL names a preview, not a version; the delivery branch is regenerated on every change,
 * so the pixels behind a stable id move (see #3723). A commit sha plus the asset's branch path
 * addresses the published bytes exactly, and `raw.githubusercontent.com` serves any commit, so a
 * permalink is the page URL plus [PARAM]`=<sha>`.
 *
 * Everything here is pure (validation, URL assembly, feed parsing) and unit-testable; fetching
 * stays in [ServeCatalogStore].
 */
object ServeCatalogRevision {

  /** Query parameter that pins a page (and every asset it links) to one delivery-branch commit. */
  const val PARAM: String = "at"

  /** How much of a sha is shown in the UI — enough to be unambiguous, short enough to read. */
  const val SHORT_LENGTH: Int = 8

  /**
   * A commit sha: 7–40 lowercase hex, never a ref. Refusing refs matters: the raw URL accepts
   * branch names too, so a ref would let a visitor choose which tree the server reads, and would
   * reintroduce the moving target a permalink replaces.
   */
  private val COMMIT = Regex("[0-9a-f]{7,40}")

  /** `owner/name`, matching the shape a GitHub repo coordinate can take and nothing else. */
  private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")

  /**
   * A pinned read may only name a PNG at a relative, traversal-free path. A file-kind rule rather
   * than a directory allowlist, since producers choose their own layout; every pinned response is
   * sent as `image/png`, so anything else resolves to no URL.
   */
  private const val PINNABLE_SUFFIX = ".png"

  /**
   * Normalize a request-supplied pin to a canonical lowercase sha, or null. Case-folded rather than
   * rejected, since UIs may upper-case shas.
   */
  fun normalize(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return trimmed.takeIf { COMMIT.matches(it) }
  }

  /** The sha as shown on a pinned page's banner. */
  fun short(commit: String): String = commit.take(SHORT_LENGTH)

  /**
   * `raw.githubusercontent.com/<repo>/<commit>/<path>` for one asset, or null when any part fails
   * validation. [path] must pass [normalizePath]; segments are percent-encoded.
   */
  fun assetUrl(repo: String?, commit: String?, path: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { REPO.matches(it) } ?: return null
    val c = normalize(commit) ?: return null
    val p = normalizePath(path) ?: return null
    val encoded = p.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://raw.githubusercontent.com/$r/$c/$encoded"
  }

  /**
   * Whether [url] addresses one immutable commit rather than a branch ref: the admission rule for
   * [CatalogBlobPool]. Lives here so [COMMIT] is the single definition of a sha; caching a branch
   * ref would be the one wrong answer. Recognises only the exact
   * `https://raw.githubusercontent.com/<owner>/<repo>/<ref>/<path…>` form this server builds;
   * anything else is simply not cached.
   */
  fun isCommitPinned(url: String): Boolean {
    val rest = url.removePrefix(RAW_HOST_PREFIX)
    if (rest.length == url.length) return false
    val segments = rest.split('/')
    // Owner, repo, ref and at least one non-empty path segment.
    if (segments.size < 4 || segments.drop(3).all { it.isEmpty() }) return false
    // Full 40 hex only: server-built URLs carry resolved heads, and abbreviations would split one
    // commit into two cache entries.
    return segments[2].length == FULL_COMMIT_LENGTH && COMMIT.matches(segments[2])
  }

  /** The host and scheme every delivery-branch read goes through. */
  private const val RAW_HOST_PREFIX = "https://raw.githubusercontent.com/"

  /** A resolved commit sha, in full. */
  private const val FULL_COMMIT_LENGTH = 40

  /** A branch path is pinnable when it is a relative, traversal-free path to a PNG. */
  fun normalizePath(path: String?): String? {
    val p = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (p.startsWith("/") || p.contains("://") || !p.endsWith(PINNABLE_SUFFIX)) return null
    val segments = p.split('/')
    if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
    return p
  }

  /** The catalog manifest, as published on a delivery branch. */
  const val CATALOG_FILE: String = "catalog.json"

  /** The design-reference manifest, as published on a delivery branch. */
  const val REFERENCES_FILE: String = "references/index.json"

  /**
   * URL for one of the two manifests a pinned read resolves paths from ([ServePinnedManifest]).
   * Separate from [assetUrl], which treats its path as untrusted; this accepts only the two
   * declared names, so the pinned lane can't be pointed at other JSON.
   */
  fun manifestUrl(repo: String?, commit: String?, file: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { REPO.matches(it) } ?: return null
    val c = normalize(commit) ?: return null
    val f = file?.takeIf { it == CATALOG_FILE || it == REFERENCES_FILE } ?: return null
    return "https://raw.githubusercontent.com/$r/$c/$f"
  }

  /** GitHub's tree view for a pinned revision — where the sha on a banner links to. */
  fun treeUrl(repo: String?, commit: String?): String? {
    val r = repo?.trim()?.trim('/')?.takeIf { REPO.matches(it) } ?: return null
    val c = normalize(commit) ?: return null
    return "https://github.com/$r/tree/$c"
  }

  /** One published revision of a catalog — a commit on its delivery branch. */
  data class Revision(
    /** Delivery-branch commit sha, full length. */
    val commit: String,
    /** When it was published, ISO-8601 as the feed states it. */
    val date: String,
    /**
     * Source commit the catalog was regenerated from, parsed from the publish subject (`… catalog
     * (<date>, <sha>)`); null when absent.
     */
    val sourceSha: String? = null,
  ) {
    val short: String
      get() = short(commit)
  }

  /**
   * The branch's commit feed (Atom at `commits/<branch>.atom`), newest first. Unauthenticated and
   * unmetered, unlike `api.github.com`'s 60 calls/hour per IP, which twenty catalogs refreshing
   * hourly would exhaust. Branch names with `/` go in verbatim.
   */
  fun commitsFeedUrl(repo: String, branch: String): String =
    "https://github.com/$repo/commits/$branch.atom"

  /**
   * [commitsFeedUrl] narrowed to one path: only the publishes that changed those bytes, the basis
   * of the render-run markers. Same unmetered Atom surface and shape, parsed by [parseCommitsFeed];
   * the API alternative would spend the rate budget per preview. [path] comes from a published
   * manifest, so it goes through [normalizePath] and is re-encoded; invalid paths yield no URL.
   */
  fun pathCommitsFeedUrl(repo: String, branch: String, path: String?): String? {
    val r = repo.trim().trim('/').takeIf { REPO.matches(it) } ?: return null
    val b = branch.trim().trim('/').takeIf { it.isNotEmpty() && !it.contains("..") } ?: return null
    val p = normalizePath(path) ?: return null
    val encoded = p.split('/').joinToString("/") { WebEscaping.urlEncodeSegment(it) }
    return "https://github.com/$r/commits/$b/$encoded.atom"
  }

  /**
   * Path-scoped publishes kept: beyond the page's [MAX_REVISIONS] window, so a run whose boundary
   * sits just outside is still recognised as closed.
   */
  const val MAX_PATH_REVISIONS: Int = 40

  /**
   * A stretch of consecutive publishes with identical render bytes; the viewer draws one thumbnail
   * per run.
   */
  data class RenderRun(
    /**
     * The newest publish carrying these bytes (the run's top row), not the introducing one, so the
     * thumbnail anchors the rows beneath it.
     */
    val head: String,
    /** Publishes in this run, within the window it was computed over. */
    val commits: Int,
    /**
     * True when the run was cut off by the window rather than a real change, so it doesn't claim a
     * count it can't support.
     */
    val open: Boolean = false,
  )

  /**
   * Collapse [revisions] (newest first) into [RenderRun]s given the publishes where the render
   * changed. A commit in [changedAt] is where the bytes became what they are, so it ends its run
   * and the next row starts another (reading it as "starts a run" puts every thumbnail one row
   * low). [revisions] must be the list the menu renders.
   *
   * Known limitation: a preview removed and later restored byte-identically appears as two runs,
   * since the re-addition is a touch. Fixing it would need the image reads this approach avoids;
   * the result is visibly two matching thumbnails and an overstated count.
   */
  fun renderRuns(
    revisions: List<Revision>,
    changedAt: Set<String>,
  ): List<RenderRun> {
    if (revisions.isEmpty()) return emptyList()
    val runs = mutableListOf<RenderRun>()
    var head = 0
    revisions.forEachIndexed { index, revision ->
      val boundary = revision.commit in changedAt
      val last = index == revisions.lastIndex
      if (!boundary && !last) return@forEachIndexed
      runs +=
        RenderRun(
          head = revisions[head].commit,
          commits = index - head + 1,
          // Closed by running out of rows rather than by a change: the look predates the window.
          open = last && !boundary,
        )
      head = index + 1
    }
    return runs
  }

  /**
   * Parse [commitsFeedUrl]'s response into revisions, newest first, by shape match rather than XML
   * parsing: `Grit::Commit/<sha>` and `<updated>` are unambiguous per entry. Unmatched entries are
   * skipped, so a changed feed degrades to fewer revisions, never a broken catalog. [limit] caps
   * the count.
   */
  fun parseCommitsFeed(xml: String, limit: Int = MAX_REVISIONS): List<Revision> =
    ENTRY.findAll(xml)
      .mapNotNull { entry ->
        val body = entry.value
        val commit = COMMIT_ID.find(body)?.groupValues?.get(1) ?: return@mapNotNull null
        val date = UPDATED.find(body)?.groupValues?.get(1)?.trim().orEmpty()
        Revision(
          commit = commit,
          date = date,
          // The source commit from the publish subject, more useful to humans than the delivery
          // sha; absent when the subject lacks one.
          sourceSha = SOURCE_SHA.find(body)?.groupValues?.get(1),
        )
      }
      .take(limit)
      .toList()

  /**
   * Revisions a page offers: roughly the last week of publishes, enough to go back before a recent
   * change without becoming a changelog.
   */
  const val MAX_REVISIONS: Int = 12

  private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
  private val COMMIT_ID = Regex("Grit::Commit/([0-9a-f]{40})")
  private val UPDATED = Regex("<updated>([^<]{1,64})</updated>")

  /** The `(<date>, <sha>)` tail of a regenerate subject. */
  private val SOURCE_SHA = Regex("catalog \\([^)]*?,\\s*([0-9a-f]{7,40})\\)")
}
