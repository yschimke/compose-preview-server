package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * What is wrong, right now, with each catalog-owned UI-builder catalog — the replacement for the
 * quiet fallback to a built-in definition.
 *
 * An owned catalog is served from nothing but what it publishes, and compose-ui-builder refuses to
 * synthesise one for it. So when its published file is missing or does not compose, the catalog is
 * **withheld** — left out of the builder, with the reason reported — rather than replaced by a
 * stale Kotlin definition, which is how the catalog template regression stayed hidden (#1526). When
 * the file composes but its templates do not read, the catalog is **degraded**: still served, new
 * designs start blank. Both clear the moment a publish composes again; every catalog refresh asks.
 *
 * Catalogs that are not owned are never recorded here: they keep today's behaviour.
 *
 * @param owns whether a catalog id is catalog-owned on this box.
 * @param log where a change of state is said; once per change, so a refresh loop does not repeat
 *   the same error every interval.
 */
class UiBuilderOwnedCatalogHealth(
  private val owns: (String) -> Boolean,
  private val log: (String) -> Unit = System.err::println,
) {
  private val withheld = ConcurrentHashMap<String, String>()
  private val degraded = ConcurrentHashMap<String, String>()

  /** [systemId]'s published definition cannot be used: withhold it, if it is owned. */
  fun withhold(systemId: String, reason: String) {
    if (!owns(systemId)) return
    degraded.remove(systemId)
    if (withheld.put(systemId, reason) != reason) {
      log(
        "serve: ERROR UI-builder catalog $systemId is catalog-owned and UNAVAILABLE — $reason. " +
          "It is not served until a republish composes; there is no built-in fallback."
      )
    }
  }

  /** [systemId] composed, but its own templates do not read. */
  fun degrade(systemId: String, reason: String) {
    if (!owns(systemId)) return
    if (degraded.put(systemId, reason) != reason) {
      log(
        "serve: ERROR UI-builder catalog $systemId templates do not read — $reason; " +
          "new designs start blank until it republishes"
      )
    }
  }

  /** [systemId] composed with readable templates (or needs none): clear whatever was recorded. */
  fun healthy(systemId: String) {
    degraded.remove(systemId)
    if (withheld.remove(systemId) != null) {
      log("serve: UI-builder catalog $systemId is available again")
    }
  }

  /** Only the templates recovered; the catalog itself was served throughout. */
  fun templatesRead(systemId: String) {
    degraded.remove(systemId)
  }

  fun isWithheld(systemId: String): Boolean = withheld.containsKey(systemId)

  /** The catalogs the builder should be built with: [enabled] less every withheld one. */
  fun served(enabled: Set<String>): Set<String> = enabled - withheld.keys

  /** A value that changes whenever [systemId]'s state does, for deciding a catalog swap. */
  fun stateOf(systemId: String): String? = withheld[systemId]?.let { "withheld:$it" }

  /** Every recorded problem, by catalog id, worded for an operator. */
  fun problems(): Map<String, String> =
    (degraded.mapValues { (_, why) ->
        "served, but its templates do not read ($why); new designs start blank"
      } + withheld.mapValues { (_, why) -> "unavailable: $why" })
      .toSortedMap()
}
