package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.bundle.WatchFaceComplicationsProduct
import ee.schimke.composeai.guidelines.protocol.GuidelineRecordV1
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The catalog reads the hosted MCP makes in process that no other route serves — the four gaps of
 * compose-preview-contracts' `docs/design/CATALOG_READ_API.md` — as bodies a client out of process
 * can fetch from `GET /{system}/api/capabilities`, `/api/history`, `/api/history/{previewId}` and
 * `/api/guidelines/{previewId}`. Step 4 of `docs/design/SERVER_UNBUNDLING.md` moves the MCP to
 * compose-preview-mcp, where it reads these instead of [ServeSessionRegistry].
 *
 * Every body is built from exactly the [ServeHost] / [ServeBundleHost] calls [ServeCatalogMcp]
 * makes for the same answer (named on each builder), so the MCP gets the same data over HTTP. The
 * DTOs are server-private until compose-preview-contracts types them; their JSON shapes are
 * documented in SERVER_UNBUNDLING.md step 4 and must stay stable.
 */
internal object ServeCatalogRead {

  /**
   * `Cache-Control` for the four bodies here. They carry an `ETag`, so a client keeps its copy and
   * revalidates every time (`no-cache`); `private` keeps a shared cache from holding a gated
   * catalog's data. `no-store` (the dynamic-route default) would forbid the copy the validator is
   * for.
   */
  const val CACHE_CONTROL: String = "private, no-cache"

  /**
   * The served catalog revision: the delivery-branch commit the catalog was loaded from
   * (`provenance.commit`, what the MCP's `history_list` reports as `commit` / `pinnedCommit`), or
   * null for a catalog with none (an uploaded bundle, a local project, a daemon-backed module).
   */
  fun revision(host: ServeHost): String? = bundleHost(host)?.provenance?.commit

  /**
   * An opaque entity tag (without the `W/` prefix) for a body: the first 12 characters of
   * [revision], when there is one, then a SHA-256 prefix of [identity]. The digest is what makes it
   * correct — a body can change without a new commit (a renderer's availability, a degradation) —
   * and the revision makes it readable in a log.
   */
  fun entityTag(revision: String?, identity: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
    val hex = digest.take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    val rev = revision?.let(ServeCatalogRevision::normalize)?.take(12)
    return "\"" + (if (rev != null) "$rev-$hex" else hex) + "\""
  }

  /** The baked bundle behind [host] — [ServeCatalogMcp]'s `bundleHost`, the same three cases. */
  fun bundleHost(host: ServeHost): ServeBundleHost? =
    when (host) {
      is ServeBundleHost -> host
      is ServeCatalogLiveHost -> host.bakedHost as? ServeBundleHost
      is ServePerPreviewLiveHost -> host.bakedHost as? ServeBundleHost
      else -> null
    }

  /**
   * Gap 1. Each preview's capabilities, from the calls the MCP's `previewJson` (`list_previews`)
   * and `dataProductsJson` (`list_data_products`) make: [PreviewCapabilitiesDto.dataProductKinds]
   * is the former's list, [PreviewCapabilitiesDto.productKinds] the latter's derived set.
   */
  fun capabilities(host: ServeHost): CatalogCapabilitiesResponse =
    CatalogCapabilitiesResponse(
      revision = revision(host),
      previews =
        host.previews.map { preview ->
          val a11y = host.hasA11yOverlayFor(preview.id)
          val annotations = host.hasDesignAnnotationsFor(preview.id)
          val typography = host.hasPublishedTypographyFor(preview.id)
          val guidelines = host.guidelineResultFor(preview.id) != null
          val productKinds = buildSet {
            addAll(preview.dataProductKinds)
            if (preview.isWatchFace) add(WatchFaceComplicationsProduct.KIND)
            if (a11y) add("a11y/hierarchy")
            if (guidelines) add(GUIDELINES_RESULT_KIND)
            if (annotations || typography) add("compose/annotations")
          }
          PreviewCapabilitiesDto(
            id = preview.id,
            dataProductKinds = preview.dataProductKinds.sorted(),
            productKinds = productKinds.sorted(),
            svgAvailable = host.hasSvgExportFor(preview.id),
            scrollAvailable = host.hasScrollExportFor(preview.id),
            state = preview.state,
            theme = preview.theme,
            a11yOverlay = a11y,
            designAnnotations = annotations,
            publishedTypography = typography,
            guidelinesResult = guidelines,
          )
        },
    )

  /**
   * Gap 2, the catalog half: where its history lives and which delivery commit is served, with the
   * MCP's `historyJson` precedence (delivery-branch provenance, else the local checkout, else
   * none).
   */
  fun history(host: ServeHost, projectHistory: ServeProjectHistory?): CatalogHistoryResponse {
    val bundle = bundleHost(host)
    val provenance = bundle?.provenance
    if (provenance != null) {
      return CatalogHistoryResponse(
        mode = MODE_PUBLISHED,
        repo = provenance.repo,
        branch = provenance.branch,
        commit = provenance.commit,
        manifestUrl = ServeUrls.historyManifestUrl(provenance.repo, provenance.branch),
        renderUrlTemplate = renderUrlTemplate(provenance.repo),
        publishesManifest = bundle.publishesHistoryManifest,
      )
    }
    if (projectHistory != null) return CatalogHistoryResponse(mode = MODE_LOCAL)
    return CatalogHistoryResponse(mode = MODE_NONE, reason = NONE_REASON)
  }

  /**
   * Gap 2, one preview: the MCP's `historyJson` for `history_list` (and the timeline `history_diff`
   * and `history_read` resolve through `historyView`), as one typed body in all three modes.
   */
  suspend fun previewHistory(
    host: ServeHost,
    projectHistory: ServeProjectHistory?,
    previewId: String,
  ): PreviewHistoryResponse {
    val bundle = bundleHost(host)
    val provenance = bundle?.provenance
    if (provenance != null) {
      val manifestUrl = ServeUrls.historyManifestUrl(provenance.repo, provenance.branch)
      val timeline = bundle.indexedTimeline(previewId)
      val base =
        PreviewHistoryResponse(
          previewId = previewId,
          mode = MODE_PUBLISHED,
          repo = provenance.repo,
          branch = provenance.branch,
          commit = provenance.commit,
          manifestUrl = manifestUrl,
          renderUrlTemplate = renderUrlTemplate(provenance.repo),
        )
      if (timeline == null) {
        return base.copy(
          reason =
            if (manifestUrl == null)
              "this catalog names a delivery branch but not one a manifest URL can be built " +
                "from, so its timeline is not addressable"
            else
              "this catalog's publisher ships no history.json, or none naming this preview; " +
                "manifestUrl is where one would be if the branch grows one",
        )
      }
      return base.withTimeline(timeline) { version ->
        "https://raw.githubusercontent.com/${provenance.repo}/${version.commit}/${timeline.path}"
      }
    }

    val history =
      projectHistory
        ?: return PreviewHistoryResponse(
          previewId = previewId,
          mode = MODE_NONE,
          reason = NONE_REASON,
        )
    val base = PreviewHistoryResponse(previewId = previewId, mode = MODE_LOCAL)
    // Off the request dispatcher: the first call per refresh window shells out to `git log`.
    val timeline =
      withContext(Dispatchers.IO) { history.timelineJsonFor(previewId) }
        ?.let {
          runCatching { PreviewHistoryManifest.decode(it)?.previews?.get(previewId) }.getOrNull()
        }
        ?: return base.copy(
          versions = emptyList(),
          reason =
            "the local delivery-branch timeline names fewer than two distinct renders for this " +
              "preview, so there is no change to show",
        )
    // Content-addressed through this server's `/history/render/<blob>.png`, as the MCP links it.
    return base.withTimeline(timeline) { version -> "/history/render/${version.blob}.png" }
  }

  /** Gap 3: the MCP's `guidelinesResultJson` (`get_preview_data kind=guidelines/result`). */
  fun guidelines(host: ServeHost, previewId: String): PreviewGuidelinesResponse {
    val record = host.guidelineResultFor(previewId)
    return PreviewGuidelinesResponse(
      revision = revision(host),
      previewId = previewId,
      found = record != null,
      record = record,
    )
  }

  private fun PreviewHistoryResponse.withTimeline(
    timeline: PreviewHistoryManifest.PreviewTimeline,
    renderUrl: (PreviewHistoryManifest.ManifestVersion) -> String,
  ): PreviewHistoryResponse =
    copy(
      path = timeline.path,
      observations = timeline.observations,
      unstable = timeline.unstable,
      flapCount = timeline.flapCount,
      versions =
        timeline.versions.map { version ->
          HistoryVersionDto(
            commit = version.commit,
            date = version.date,
            blob = version.blob,
            commits = version.commits,
            sourceSha = version.sourceSha,
            sinceCommit = version.sinceCommit,
            occurrences = version.occurrences,
            renderUrl = renderUrl(version),
          )
        },
    )

  private fun renderUrlTemplate(repo: String): String =
    "https://raw.githubusercontent.com/$repo/{commit}/{path}"

  const val MODE_PUBLISHED: String = "published"
  const val MODE_LOCAL: String = "local"
  const val MODE_NONE: String = "none"

  private const val NONE_REASON =
    "this catalog has no delivery-branch provenance and this server has no checkout to derive a " +
      "timeline from; an uploaded bundle carries no history"
}

/** `GET /{system}/api/capabilities`: every preview's capabilities, in the catalog's order. */
@Serializable
internal data class CatalogCapabilitiesResponse(
  val schema: String = "compose-preview-serve/capabilities/v1",
  /** [ServeCatalogRead.revision]: the delivery commit these answers describe, or null. */
  val revision: String? = null,
  val previews: List<PreviewCapabilitiesDto>,
)

@Serializable
internal data class PreviewCapabilitiesDto(
  val id: String,
  /** The kinds the preview declares (`ServePreview.dataProductKinds`), sorted. */
  val dataProductKinds: List<String>,
  /**
   * Every kind `list_data_products` reports: [dataProductKinds] plus `watchface/complications`,
   * `a11y/hierarchy`, `guidelines/result` and `compose/annotations` where they apply. Sorted.
   */
  val productKinds: List<String>,
  /** `ServeHost.hasSvgExportFor`: `observe=svg` can answer. */
  val svgAvailable: Boolean,
  /** `ServeHost.hasScrollExportFor`: `observe=scroll-png` / `scroll-svg` can answer. */
  val scrollAvailable: Boolean,
  val state: String? = null,
  val theme: String? = null,
  /** `ServeHost.hasA11yOverlayFor`. */
  val a11yOverlay: Boolean,
  /** `ServeHost.hasDesignAnnotationsFor`. */
  val designAnnotations: Boolean,
  /** `ServeHost.hasPublishedTypographyFor`. */
  val publishedTypography: Boolean,
  /** Whether `GET /{system}/api/guidelines/{previewId}` has a record (`found: true`). */
  val guidelinesResult: Boolean,
)

/** `GET /{system}/api/history`: where the catalog's history lives, and the commit served. */
@Serializable
internal data class CatalogHistoryResponse(
  val schema: String = "compose-preview-serve/history/v1",
  /** `published` (delivery branch), `local` (this server's checkout) or `none`. */
  val mode: String,
  val repo: String? = null,
  val branch: String? = null,
  /** The served delivery commit (`provenance.commit`); null when the catalog names none. */
  val commit: String? = null,
  val manifestUrl: String? = null,
  val renderUrlTemplate: String? = null,
  /** Whether the served tree carried a `history.json` (per-preview timelines are answerable). */
  val publishesManifest: Boolean = false,
  val reason: String? = null,
)

/** `GET /{system}/api/history/{previewId}`: one preview's indexed timeline. */
@Serializable
internal data class PreviewHistoryResponse(
  val schema: String = "compose-preview-serve/preview-history/v1",
  val previewId: String,
  val mode: String,
  val repo: String? = null,
  val branch: String? = null,
  /** The served delivery commit: the MCP's `commit`, and its `pinnedCommit` when [path] is set. */
  val commit: String? = null,
  val manifestUrl: String? = null,
  val renderUrlTemplate: String? = null,
  /** Why there is no timeline; null when [versions] is one. */
  val reason: String? = null,
  val path: String? = null,
  val observations: Int? = null,
  val unstable: Boolean? = null,
  val flapCount: Int? = null,
  /** Newest first. Null when there is no timeline to list (never "empty, so unchanged"). */
  val versions: List<HistoryVersionDto>? = null,
)

@Serializable
internal data class HistoryVersionDto(
  val commit: String,
  val date: String,
  /** The render's git blob sha: two versions with the same blob have the same bytes. */
  val blob: String,
  val commits: Int,
  val sourceSha: String? = null,
  val sinceCommit: String? = null,
  val occurrences: Int? = null,
  /** Where these exact bytes are served: `raw.githubusercontent.com` or `/history/render/`. */
  val renderUrl: String,
)

/** `GET /{system}/api/guidelines/{previewId}`: the preview's published guidelines result. */
@Serializable
internal data class PreviewGuidelinesResponse(
  val schema: String = "compose-preview-serve/guidelines/v1",
  val revision: String? = null,
  val kind: String = GUIDELINES_RESULT_KIND,
  val previewId: String,
  /** False when the catalog publishes no result for this preview; [record] is then null. */
  val found: Boolean,
  val record: GuidelineRecordV1? = null,
)
