package ee.schimke.composeai.cli.serve

/**
 * A component's links to the SAME component as another catalog publishes it.
 *
 * ## Why this is not `compareWith` + `parallel`
 *
 * The server already reaches into a sibling catalog: `compareWith` names one system, `parallel`
 * names the counterpart component in it, and [ServeParallelPairing] picks which of its renders is
 * the counterpart of this one. That pairing answers a **parity** question — two catalogs drawing
 * one design system, is this cell the same on both — and it is deliberately a single scalar,
 * because a parity comparison has two sides.
 *
 * `related` answers a different question, and the difference is why it cannot ride on the same
 * field. A catalog has exactly one rendition to be compared against, but any number of catalogs
 * that are ABOUT it. The case that forced this: the AndroidX samples are imported into a samples
 * catalog published beside each kit catalog, and the kit catalog that most wants to point at it had
 * already spent its single `compareWith` on a second rendition of its own components. Overloading
 * `compareWith` would mean choosing between the parity lane and the samples lane; a list means
 * neither has to lose (yschimke/compose-ai-tools#5398).
 *
 * No catalog is named anywhere in this file, deliberately: which catalogs exist is the deployment's
 * `catalogs.json` and each catalog's own published `catalog.json` to say, never this module's
 * Kotlin (`.github/scripts/ui-builder-catalog-literals.sh` enforces it).
 *
 * So: `compareWith` is one sibling, symmetric, about sameness. `related` is many siblings,
 * directed, about aboutness — the call sites for a component, a tile rendition of it, a motion
 * study. The producer side has been merged in compose-ai-tools since #5398
 * (`@CatalogComponent(related = …)` through discovery, `apply-related.mjs` stamping
 * `components[].related` onto `catalog.json`); this is the reading half.
 *
 * ## What this file is, and is not
 *
 * It is the **model and the policy**: what a link is, how the declared entries become resolvable
 * links, and what happens to the ones that cannot resolve. Deliberately pure and free of the
 * session registry, so the rules are testable without standing a catalog up — the same division
 * [ServeParallelPairing] draws, where the object decides and the server does the lookup.
 *
 * PUBLIC where [ServeParallelPairing] is internal, and only for that reason: [Declared] is part of
 * [ServeBundleHost]'s own public surface (`relatedByComponentId`), so an internal object here would
 * be a public function exposing an internal type. Nothing outside this package should reach for it.
 *
 * It is NOT a surface. Nothing here decides where a related link appears in the viewer, or what it
 * looks like; that is still open, and this layer exists so the answer can change without moving any
 * of this. In particular a related link is not a motion capture: the motion lane is same-system by
 * construction (a capture is fetched from the leased host's own branch), and a related link points
 * at a catalog that is a separate session with its own routes.
 */
object ServeRelatedCatalogs {

  /**
   * One declared link, as `catalog.json` carries it.
   *
   * [componentId] is null when the other catalog spells the component the same way — the common
   * case, and the reason the producer allows the short `"<system>"` form. [label] is null when the
   * catalog authored no wording and the reader should be shown the system's own title instead,
   * which only the serve layer knows.
   */
  data class Declared(
    val system: String,
    val componentId: String? = null,
    val label: String? = null,
  )

  /**
   * A link that names a system this box actually serves, with the component id to open there.
   *
   * [live] is whether that catalog had a host when this was resolved. It is carried rather than
   * filtered on, because the two cases want different treatment and only a surface can choose: a
   * system that is registered but still loading is worth a disabled affordance, while one that is
   * not registered at all is worth nothing at all and is dropped by [resolve] before this point.
   */
  data class Link(
    val system: String,
    val componentId: String,
    val label: String?,
    val live: Boolean,
  )

  /**
   * The declared links for one component, in declaration order, with the obviously-unusable
   * dropped.
   *
   * Dropped: a blank system (a producer slip, and there is nothing to point at), and a link back to
   * [selfSystem] (a catalog is not related to itself; the export cannot always tell, because a spec
   * is written against a system name rather than the catalog it will be published as). The
   * self-link comparison is EXACT, not case-insensitive: a catalog id is a case-sensitive map key
   * everywhere else it is used — the registry's session map, `catalogs.json`'s duplicate check, the
   * URL path segment — so `Kit` and `kit` are two catalogs here too, and folding them would drop a
   * real link between them. A merely miscased self-link costs nothing: it is not registered under
   * that spelling, so [resolve] drops it anyway.
   *
   * Deduplicated on system + component, first declaration winning, so a component that inherits a
   * link from a `@CatalogGroup` and restates it does not show it twice. Only the pair as DECLARED
   * can be deduplicated here, because the short `"<system>"` form does not name its component until
   * [resolve] fills one in — a mixed `("samples")` + `("samples", "Button")` pair is collapsed
   * there instead.
   *
   * NOT dropped: a link to a system this box does not serve. That is [resolve]'s call, because it
   * depends on what is registered right now and this function has to be stable across a catalog
   * reload.
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
   * The INVERSE of one catalog's declared links: which of its components point at each component of
   * [targetSystem].
   *
   * `related` is directed — a kit catalog declares which samples explain its components, and the
   * samples catalog declares nothing. That is the right way round for the producer: the kit knows
   * its own call sites, and a samples catalog imported from upstream cannot be made to know what it
   * is a sample OF without maintaining the same mapping twice, in a file that is regenerated on
   * every import.
   *
   * So the back-link is derived rather than declared. A samples page asks this of every OTHER
   * catalog the box serves: "does anything in you point at me?" — and the answer is the component
   * to link back to.
   *
   * A samples catalog served ALONE therefore has no back-links, which is correct rather than a gap:
   * there is no kit catalog on that box to link back to. This is the whole reason the inverse is
   * computed here instead of being stamped into the samples catalog at import time.
   *
   * [entries] is the source catalog's [ServeBundleHost.relatedByComponentId] — already normalised
   * by [declaredFor], so nothing here re-checks blanks or self-links. Keys of the result are
   * [targetSystem]'s component ids; values are the source catalog's, in the order they were
   * declared, deduplicated.
   */
  fun inverse(
    entries: Map<String, List<Declared>>,
    targetSystem: String,
    /**
     * This component id when a link names none — the short `"<system>"` form means "the same
     * component, over there", so the inverse of `Button → samples` is `samples/Button → Button`.
     * Passed as a function rather than resolved by the caller because only the entries that
     * actually name [targetSystem] need it.
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
   * Resolve declared links against the catalogs this box serves.
   *
   * [componentId] is this component's own id, used when a link names no counterpart — the short
   * `"<system>"` form means "the same component, over there". [registered] is every system the box
   * knows, listed or not; a samples catalog is deliberately unlisted, so filtering on the
   * front-page set would drop exactly the links this exists for. [isLive] says which of those
   * currently has a host, and is asked only for systems that survived the registration check, so a
   * resolve never costs a lookup per declared link on a box serving none of them.
   *
   * A link to an unregistered system is DROPPED rather than rendered dead. The alternative was
   * considered and rejected: a catalog declares `related` from its own source tree, so it can name
   * a system this particular box has never heard of — a fork serving one catalog would otherwise
   * show a column of links to nothing, and be right to think the server was broken.
   *
   * Deduplicated again, on the RESOLVED pair. [declaredFor] can only see what was declared, and the
   * short `"<system>"` form does not name a component until the fallback above fills one in — so a
   * component that inherits `("samples")` from its `@CatalogGroup` and restates it as `("samples",
   * "Button")` arrives here as two entries naming one destination. First wins, as it does there,
   * which keeps the inherited link's position and the restated one's label out of a tie-break
   * nobody would be able to predict.
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
