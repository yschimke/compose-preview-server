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
 * A catalog this build has no built-in definition for (one compose-ui-builder never had, or
 * deleted, as it did `a2ui-catalog` in 3.111.0) is withheld the same way, owned or not:
 * compose-ui-builder refuses to serve it from nothing, and that refusal would otherwise take the
 * whole builder down with it. Templates are read only for owned catalogs, so only those are ever
 * degraded.
 *
 * Any other catalog is never recorded here: it falls back to its built-in definition.
 *
 * @param owns whether a catalog id is catalog-owned on this box.
 * @param builtIn whether this build can still define a catalog id itself.
 * @param log where a change of state is said; once per change, so a refresh loop does not repeat
 *   the same error every interval.
 */
class UiBuilderOwnedCatalogHealth(
  private val owns: (String) -> Boolean,
  private val log: (String) -> Unit = System.err::println,
  private val builtIn: (String) -> Boolean = { true },
) {
  private val withheld = ConcurrentHashMap<String, String>()
  private val degraded = ConcurrentHashMap<String, String>()

  /**
   * [systemId]'s published definition cannot be used: withhold it, if it is owned or this build
   * cannot define it itself.
   */
  fun withhold(systemId: String, reason: String) {
    val owned = owns(systemId)
    if (!owned && builtIn(systemId)) return
    degraded.remove(systemId)
    if (withheld.put(systemId, reason) != reason) {
      val what = if (owned) "is catalog-owned" else "has no built-in definition in this build"
      log(
        "serve: ERROR UI-builder catalog $systemId $what and is UNAVAILABLE — $reason. " +
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

  /**
   * [systemId]'s published definition composed again: lift the withhold. Whether its templates read
   * is a separate fact, recorded by [degrade] or cleared by [templatesRead] in the same refresh, so
   * a degradation recorded just before this call survives it.
   */
  fun healthy(systemId: String) {
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
