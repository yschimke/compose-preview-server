package ee.schimke.composeai.cli.serve

import java.util.concurrent.ConcurrentHashMap

/**
 * Publication and template failures for every UI-builder catalog. Missing definitions are withheld;
 * unreadable templates degrade the catalog to a generic blank. A refresh clears either failure as
 * soon as the publication recovers.
 *
 * @param log where a state change is reported; repeated identical failures are logged once.
 */
class UiBuilderOwnedCatalogHealth(private val log: (String) -> Unit = System.err::println) {
  private val withheld = ConcurrentHashMap<String, String>()
  private val degraded = ConcurrentHashMap<String, String>()

  /** Withhold a catalog whose published definition cannot be used. */
  fun withhold(systemId: String, reason: String): Boolean {
    degraded.remove(systemId)
    if (withheld.put(systemId, reason) != reason) {
      log(
        "serve: ERROR UI-builder catalog $systemId is UNAVAILABLE — $reason. " +
          "It is not served until a republish composes; there is no built-in fallback."
      )
    }
    return true
  }

  /** [systemId] composed, but its own templates do not read. */
  fun degrade(systemId: String, reason: String) {
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
