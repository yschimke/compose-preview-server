package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ComponentSourceV1
import ee.schimke.composeai.uibuilder.protocol.DesignComponentV1

/**
 * Whether a design's imported components still match the library they came from.
 *
 * This is the half of "referenced, not copied" that does the reporting. A design holds the body of
 * every component it uses, so it draws and exports without asking anything — and that is exactly
 * what makes it a copy unless something notices when the original moves. An import records
 * [ComponentSourceV1]: the project, the id, and the symbol's content digest **as it was read**.
 * Computing that digest again later and finding a different one is the whole mechanism.
 *
 * Reporting, never repairing. A design that has drifted is not silently redrawn — the person who
 * owns it decides whether to take the new version, and until they do the design keeps drawing what
 * it has always drawn. That is the same bargain a catalog pin strikes: a design must never quietly
 * become a different design.
 *
 * A component with no [DesignComponentV1.source] is not checked and not reported. It was authored
 * in this design, there is nothing for it to drift against, and listing it as "unchanged" would pad
 * the answer with rows that can never say anything else.
 */
internal class ServeUiBuilderComponentDrift(private val library: ServeUiBuilderComponentLibrary) {

  /** What a later read of the library found. */
  enum class State {
    /** The library still publishes this symbol and its content digest is the recorded one. */
    UNCHANGED,
    /** Still published, and its content has changed since the import. */
    DRIFTED,
    /** The project no longer publishes a symbol under this id. */
    WITHDRAWN,
    /**
     * Published, but the library refuses it — malformed, cyclic, placing another symbol, or a
     * source this host cannot reach right now.
     *
     * Deliberately not folded into [WITHDRAWN]. "The project removed this" and "the project's copy
     * is broken today" call for different actions from the person reading the report, and a branch
     * that is briefly unreachable must not read as a deletion.
     */
    UNUSABLE,
  }

  /**
   * One component's verdict.
   *
   * [currentDigest] is what the library computes now, present only for [State.DRIFTED] — it is the
   * evidence for the claim, and there is nothing to show when nothing changed or nothing was found.
   */
  data class Finding(
    val componentKey: String,
    val source: ComponentSourceV1,
    val state: State,
    val currentDigest: String? = null,
  )

  /**
   * Every imported component in [components], in key order.
   *
   * Key order rather than the order drift was found, so two reads of an unchanged design produce
   * the same report and a diff between them means something.
   */
  fun check(
    components: Map<String, DesignComponentV1>,
    catalogs: List<ServeUiBuilderDesignLibrary.Coordinate>,
  ): List<Finding> =
    components.entries
      .sortedBy { it.key }
      .mapNotNull { (key, declaration) ->
        val source = declaration.source ?: return@mapNotNull null
        Finding(componentKey = key, source = source, state = State.UNCHANGED)
          .withCurrentState(catalogs)
      }

  private fun Finding.withCurrentState(
    catalogs: List<ServeUiBuilderDesignLibrary.Coordinate>
  ): Finding {
    // Every coordinate for the recorded system, in configured order — the rule the library's own
    // routes follow, because a system can have both a local checkout and a served branch.
    val matching = catalogs.filter { it.system == source.system }
    if (matching.isEmpty()) return copy(state = State.UNUSABLE)

    var published = false
    matching.forEach { catalog ->
      val entry =
        library.index(catalog).firstOrNull { it.componentId == source.componentId }
          ?: return@forEach
      published = true
      val symbol = library.symbol(catalog, entry) ?: return@forEach
      return if (symbol.digest == source.digest) copy(state = State.UNCHANGED)
      else copy(state = State.DRIFTED, currentDigest = symbol.digest)
    }
    // Named by the project but unreadable, versus not named at all. The first is a broken publish
    // or an unreachable branch; the second is a removal.
    return copy(state = if (published) State.UNUSABLE else State.WITHDRAWN)
  }
}
