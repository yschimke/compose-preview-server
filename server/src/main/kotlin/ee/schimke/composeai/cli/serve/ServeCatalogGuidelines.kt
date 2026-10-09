package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.guidelines.CatalogGuidelines
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRule
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineRuleSet
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Each builder catalog's own design guidance: the `ui-builder.guidelines.json` a catalog publishes
 * beside its `ui-builder.json` (format `compose-ui-builder/catalog-guidelines/v1`), keyed by the
 * builder catalog id a design's `catalogPin.systemId` names.
 *
 * Read from the delivery branch when a published catalog is composed, and from a local module's
 * `build/compose-previews/` where compose-ai-tools' discovery writes it. Fail-soft throughout: a
 * catalog that publishes none has none, and a malformed file, or one written for another catalog,
 * is said once and ignored, so the check falls back to the rules compose-ui-builder bundles.
 */
class ServeCatalogGuidelines(private val log: (String) -> Unit = System.err::println) {
  /** A catalog's guidelines, where they were read from, and the bytes as published. */
  class Loaded(val guidelines: CatalogGuidelines, val source: String, val raw: String)

  private val byCatalog = ConcurrentHashMap<String, Loaded>()

  /** The guidelines [catalogId] publishes, or null when it publishes none. */
  fun forCatalog(catalogId: String): Loaded? = byCatalog[catalogId]

  /** The catalog ids that publish guidelines, for diagnostics. */
  fun catalogIds(): Set<String> = byCatalog.keys.toSortedSet()

  /**
   * Keeps [bytes] as [catalogId]'s guidelines when they read as guidelines written for it; returns
   * whether they were kept. Anything else is logged and leaves the catalog's previous guidelines
   * (or none) in place.
   */
  fun accept(catalogId: String, bytes: ByteArray, source: String): Boolean {
    val text = bytes.toString(Charsets.UTF_8)
    val parsed = runCatching {
      CatalogGuidelines.parse(text)
    }
      .getOrElse {
        log("serve: $catalogId's guidelines at $source could not be read (${it.message})")
        return false
      }
    if (!parsed.schema.startsWith(SCHEMA_PREFIX)) {
      log("serve: $catalogId's guidelines at $source are not catalog guidelines (${parsed.schema})")
      return false
    }
    if (parsed.catalog != catalogId) {
      log("serve: $catalogId's guidelines at $source are written for `${parsed.catalog}`; ignored")
      return false
    }
    byCatalog[catalogId] = Loaded(parsed, source, text)
    return true
  }

  /** Forgets [catalogId]'s guidelines, when its republished catalog no longer carries any. */
  fun remove(catalogId: String) {
    byCatalog.remove(catalogId)
  }

  /**
   * Reads the guidelines a local module's discovery wrote at [file], keyed by the catalog id the
   * `ui-builder.json` beside it declares, or by the file's own when there is none.
   */
  fun loadLocal(file: File): Boolean {
    if (!file.isFile) return false
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
    val declared =
      File(file.parentFile, ServeCatalogStore.UI_BUILDER_CATALOG_FILE)
        .takeIf { it.isFile }
        ?.let { catalogIdOf(it.readText()) }
    val own = runCatching {
      CatalogGuidelines.parse(bytes.toString(Charsets.UTF_8)).catalog
    }
      .getOrNull()
    val catalogId = declared ?: own ?: return false
    return accept(catalogId, bytes, file.absolutePath)
  }

  /**
   * The rules a result for a design of [catalogId] may name and is read against: the catalog's own,
   * then compose-ui-builder's bundled set, so a result recorded before the catalog published its
   * guidelines still reads.
   */
  fun ruleSetFor(catalogId: String?): DesignGuidelineRuleSet {
    val own = catalogId?.let(::forCatalog)?.guidelines ?: return DesignGuidelineRuleSet.Bundled
    val ownIds = own.rules.mapTo(mutableSetOf()) { it.id }
    return DesignGuidelineRuleSet(
      schema = own.schema,
      version = own.version,
      about = own.about,
      rules = own.rules + DesignGuidelineRuleSet.Bundled.rules.filterNot { it.id in ownIds },
    )
  }

  /** [ruleSetFor]'s rules. */
  fun rulesFor(catalogId: String?): List<DesignGuidelineRule> = ruleSetFor(catalogId).rules

  private fun catalogIdOf(uiBuilderJson: String): String? = runCatching {
    (ROUTE_JSON.parseToJsonElement(uiBuilderJson) as? kotlinx.serialization.json.JsonObject)
      ?.get("catalog")
      ?.let { it as? kotlinx.serialization.json.JsonObject }
      ?.get("id")
      ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
  }
    .getOrNull()

  companion object {
    private const val SCHEMA_PREFIX = "compose-ui-builder/catalog-guidelines/"
    private val ROUTE_JSON = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** Where the server serves a catalog's guidelines, linked from a prompt's `rules.source`. */
    fun routeFor(catalogId: String): String =
      "/api/ui-builder/v1/catalogs/${java.net.URLEncoder.encode(catalogId, Charsets.UTF_8)}/guidelines"

    /** The branch-relative path of the guidelines published beside [uiBuilderFile]. */
    fun siblingOf(uiBuilderFile: String): String {
      val dir = uiBuilderFile.substringBeforeLast('/', "")
      return if (dir.isEmpty()) CatalogGuidelines.FILE_NAME
      else "$dir/${CatalogGuidelines.FILE_NAME}"
    }
  }
}
