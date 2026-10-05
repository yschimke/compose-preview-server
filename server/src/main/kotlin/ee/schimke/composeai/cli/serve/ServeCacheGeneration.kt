package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.web.WebEscaping

/**
 * **One cache generation** for a catalog page and the frames it draws.
 *
 * A page's HTML and the renders it points at have independent cache lifetimes, so after an in-place
 * refresh a browser can pair one generation's HTML (including a parity **verdict**) with another's
 * pixels. Every frame URL a generation-scoped page writes therefore carries [PARAM]`=<sha>`, the
 * delivery-branch commit the page was assembled from:
 * - the generation being served ⇒ the bytes on disk, served `immutable`;
 * - any other ⇒ that publish's bytes, resolved exactly as a [ServeCatalogRevision] pin is.
 *
 * Validated by the same rules as a pin, but kept a distinct parameter because the meaning differs:
 * `at=` is a reader's request for an older publish (announced, with redline and verdict withheld),
 * while `gen=` is an invisible coherence claim that withholds nothing.
 */
object ServeCacheGeneration {

  /** Query parameter naming the catalog generation a frame URL belongs to; short, machine-only. */
  const val PARAM: String = "gen"

  /**
   * Normalize a request-supplied generation to a canonical sha, or null when it isn't one.
   *
   * Delegated to [ServeCatalogRevision.normalize] rather than re-implemented: a generation resolves
   * through the pinned-asset lane whenever it names a publish other than the one on disk, so it
   * reaches the same `raw.githubusercontent.com/<repo>/<sha>/<path>` fetch and must be admitted by
   * the same rule. A looser one here would let a ref name the tree that lane reads.
   */
  fun normalize(raw: String?): String? = ServeCatalogRevision.normalize(raw)

  /** The generation as it appears in a refusal message — the same short form a pin banner shows. */
  fun short(commit: String): String = ServeCatalogRevision.short(commit)

  /**
   * Append [PARAM] to an already-built asset [link], or return it untouched.
   *
   * Untouched for a host with no delivery-branch generation to name — an uploaded bundle, a local
   * project, a daemon-backed module. Those have no published-per-generation bytes to reconcile
   * against and no branch to read an older generation from, so scoping their frames would only mint
   * a parameter nothing can answer.
   *
   * Deliberately applied to the **asset** query and never to the page query it is derived from.
   * `gen=` on a `/compare/<id>` or `/p/<id>` link would put the server's internal coherence claim
   * into a URL people copy, share and pin, where it reads as a permalink that isn't one — and it
   * would survive into a later publish as a stale parameter on a page that is perfectly current.
   */
  fun scope(link: String, generation: String?): String {
    val gen = normalize(generation) ?: return link
    val param = "$PARAM=${WebEscaping.urlEncodeSegment(gen)}"
    return when {
      !link.contains('?') -> "$link?$param"
      link.endsWith('?') || link.endsWith('&') -> "$link$param"
      else -> "$link&$param"
    }
  }
}
