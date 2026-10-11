package ee.schimke.composeai.cli.serve

/**
 * A component's links to the same component as another catalog publishes it. Unlike
 * [ServeParallelPairing]'s single symmetric parity sibling, `related` is any number of directed
 * siblings about the component (samples, a tile rendition, a motion study), produced upstream by
 * `@CatalogComponent(related = …)` into `components[].related`.
 *
 * Pure model and policy; no catalog is named here (`.github/scripts/ui-builder-catalog-literals.sh`
 * enforces it). Public only because [Declared] appears in [ServeBundleHost.relatedByComponentId].
 */
object ServeRelatedCatalogs {

  /**
   * One declared link, as `catalog.json` carries it. [componentId] is null when the other catalog
   * spells the component the same way (the short `"<system>"` form); [label] is null when the
   * reader should see the system's own title instead.
   */
  data class Declared(
    val system: String,
    val componentId: String? = null,
    val label: String? = null,
  )

  /**
   * A link to a system this box serves, with the component id to open there. [live] is carried
   * rather than filtered on: a registered but loading system is worth a disabled affordance
   * (unregistered ones are dropped by [resolve]).
   */
  data class Link(
    val system: String,
    val componentId: String,
    val label: String?,
    val live: Boolean,
  )

  /**
   * The declared links for one component, in order, minus blank systems and links back to
   * [selfSystem]. The self-link check is exact, since catalog ids are case-sensitive everywhere; a
   * miscased self-link is dropped by [resolve] anyway.
   *
   * Deduplicated on the declared system + component, first wins (the short form is collapsed later,
   * in [resolve]). Links to unserved systems are kept: that depends on what is registered now, and
   * this must be stable across reloads.
   */
  fun declaredFor(entries: List<Declared>, selfSystem: String?): List<Declared> {
    val seen = mutableSetOf<Pair<String, String?>>()
    return entries.mapNotNull { entry ->
      val system = entry.system.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      if (system == selfSystem) return@mapNotNull null
      val componentId = entry.componentId?.trim()?.takeIf { it.isNotEmpty() }
      if (!seen.add(system to componentId)) return@mapNotNull null
      Declared(
        system = system,
        componentId = componentId,
        label = entry.label?.trim()?.takeIf { it.isNotEmpty() },
      )
    }
  }

  /**
   * The inverse of one catalog's declared links: which of its components point at each component of
   * [targetSystem]. `related` is declared by the kit (which knows its call sites), so a samples
   * catalog's back-links are derived by asking every other served catalog rather than stamped in at
   * import; served alone, it correctly has none.
   *
   * [entries] is the source's [ServeBundleHost.relatedByComponentId], already normalised by
   * [declaredFor]. Result keys are [targetSystem]'s component ids; values are the source's, in
   * declaration order, deduplicated.
   */
  fun inverse(
    entries: Map<String, List<Declared>>,
    targetSystem: String,
    /**
     * The component id when a link names none (the short form means "the same component, over
     * there"). A function because only entries naming [targetSystem] need it.
     */
    fallbackComponentId: (String) -> String = { it },
  ): Map<String, List<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    entries.forEach { (sourceComponentId, declared) ->
      declared.forEach { link ->
        if (link.system != targetSystem) return@forEach
        val targetComponentId = link.componentId ?: fallbackComponentId(sourceComponentId)
        val sources = out.getOrPut(targetComponentId) { mutableListOf() }
        if (sourceComponentId !in sources) sources.add(sourceComponentId)
      }
    }
    return out
  }

  /**
   * Resolve declared links against the catalogs this box serves. [componentId] fills in the short
   * `"<system>"` form. [registered] includes unlisted systems, since samples catalogs are
   * deliberately unlisted. [isLive] is asked only for registered systems.
   *
   * Links to unregistered systems are dropped rather than rendered dead: a catalog may name systems
   * a given box never serves. Deduplicated again on the resolved pair (the short and explicit forms
   * can name one destination), first wins.
   */
  fun resolve(
    entries: List<Declared>,
    componentId: String,
    selfSystem: String?,
    registered: Set<String>,
    isLive: (String) -> Boolean,
  ): List<Link> =
    declaredFor(entries, selfSystem)
      .filter { it.system in registered }
      .distinctBy { it.system to (it.componentId ?: componentId) }
      .map { declared ->
        Link(
          system = declared.system,
          componentId = declared.componentId ?: componentId,
          label = declared.label,
          live = isLive(declared.system),
        )
      }
}
