package ee.schimke.composeai.cli.serve

/**
 * The **derived-layer diff** between one render and its counterpart in the `compareWith` sibling:
 * what each side's typography, layout and theme layers say about the same cell (issue #4838).
 *
 * A pixel diff of two rasterisers is mostly antialiasing. Two Compose runtimes drawing one design
 * system disagree in ways that never survive to a 227dp raster comparison as anything a reader can
 * act on — but that *are* stated outright one layer up. A Remote Compose document falling back to a
 * different font family than its Wear twin is invisible in the pixels and obvious in a typography
 * row; two catalogs claiming one token and resolving different values is the finding, not the noise
 * around it.
 *
 * Everything here is read out of artifacts both systems already publish. A catalog's
 * `annotations/index.json` carries a [AnnotationKind.TYPOGRAPHY] row per text node measured over
 * the very frame it publishes, and [AnnotationKind.LAYOUT] boxes beside them; a host with a live
 * capture derives the same shapes — and [AnnotationKind.THEME] too — from the render's own
 * semantics ([ServeDesignAnnotations]). So this is a join, not a new render lane, and the two sides
 * are compared as *resolved facts* rather than as published claims.
 *
 * **An absence is a finding.** A row one side draws and the other does not is kept and labelled
 * ([Presence]), never dropped: dropping it would make two catalogs look more aligned the further
 * they have diverged, which is backwards for a parity surface.
 *
 * Public rather than internal only because [ServeWeb] and [ServeParallelLayersPayload] take these
 * shapes as parameters; the projection itself is not API and carries no compatibility promise.
 */
object ServeParallelLayers {

  /** Which side of the pair a row exists on. */
  enum class Presence {
    /** Both catalogs draw this node; the fields say whether they agree about it. */
    BOTH,
    /** Only the page's own catalog draws it — the sibling's layer has no counterpart row. */
    ONLY_HERE,
    /** Only the sibling draws it. Kept for the reason [ONLY_HERE] is. */
    ONLY_THERE,
  }

  /** One resolved property of a node, as each side resolved it. */
  data class Field(val name: String, val here: String?, val there: String?) {
    /** Both sides resolved it, to the same value. */
    val agrees: Boolean = here != null && here == there

    /** Both sides resolved it, differently — the finding this surface exists to state. */
    val differs: Boolean = here != null && there != null && here != there
  }

  /** One node of one layer, and what the two catalogs resolved for it. */
  data class Row(
    val kind: String,
    /**
     * How a reader names this node: its role (the text it draws, the component), else its label.
     */
    val subject: String,
    val here: String?,
    val there: String?,
    val presence: Presence,
    val fields: List<Field>,
  ) {
    /** Whether this row is worth a reader's attention: a real disagreement, or a one-sided node. */
    val notable: Boolean = presence != Presence.BOTH || fields.any { it.differs }
  }

  /** One layer's rows, plus the counts a heading states without anyone counting rows. */
  data class Layer(val kind: String, val rows: List<Row>) {
    val paired: Int = rows.count { it.presence == Presence.BOTH }
    val differing: Int = rows.count {
      it.presence == Presence.BOTH && it.fields.any { f -> f.differs }
    }
    val onlyHere: Int = rows.count { it.presence == Presence.ONLY_HERE }
    val onlyThere: Int = rows.count { it.presence == Presence.ONLY_THERE }
  }

  data class Diff(val layers: List<Layer>) {
    val isEmpty: Boolean = layers.all { it.rows.isEmpty() }
    val differing: Int = layers.sumOf { it.differing }
    val unpaired: Int = layers.sumOf { it.onlyHere + it.onlyThere }
  }

  /**
   * The per-layer diff of [here] against [there], layers in [KIND_ORDER] and rows in the order the
   * producing walk emitted them (which is the frame's own reading order).
   *
   * A kind neither side carries contributes no layer at all rather than an empty one: "this catalog
   * publishes no theme layer" is a fact about the publishing lane, not about the two designs, and a
   * heading over nothing reads as the second.
   */
  fun diff(here: List<DesignAnnotation>, there: List<DesignAnnotation>): Diff =
    Diff(
      KIND_ORDER.mapNotNull { kind ->
        val ours = here.filter { it.kind == kind }
        val theirs = there.filter { it.kind == kind }
        if (ours.isEmpty() && theirs.isEmpty()) null else Layer(kind, rows(kind, ours, theirs))
      }
    )

  /**
   * Pair the two sides' rows, then diff each pair field by field.
   *
   * Pairing goes by **subject** first — the text a typography row draws, the component a layout box
   * belongs to, or the Material token it resolved. That is the one name the two catalogs share when
   * they draw the same cell, and it survives them ordering their trees differently.
   *
   * Whatever is left over is paired **by reading order, and only when both sides have the same
   * number of leftovers**. Equal counts say the two renders have the same shape and the *n*-th text
   * on one is the *n*-th on the other, which is exactly the case a Wear/Remote pair of one cell is
   * in when the two spell their strings differently. Unequal counts say they do not, and there
   * pairing by position would state a confident correspondence nobody established — so those rows
   * stay one-sided instead, which is a finding in its own right.
   */
  private fun rows(
    kind: String,
    here: List<DesignAnnotation>,
    there: List<DesignAnnotation>,
  ): List<Row> {
    // Index-keyed rather than annotation-keyed: two rows of one layer may be equal in every field
    // this type carries (one label, one box), and a map would silently fold them into one.
    val claimed = BooleanArray(there.size)
    val mates = arrayOfNulls<DesignAnnotation>(here.size)
    here.forEachIndexed { index, annotation ->
      val subject = subjectOf(annotation) ?: return@forEachIndexed
      val mate = there.indices.firstOrNull { !claimed[it] && subjectOf(there[it]) == subject }
      if (mate != null) {
        claimed[mate] = true
        mates[index] = there[mate]
      }
    }
    val leftoverHere = here.indices.filter { mates[it] == null }
    val leftoverThere = there.indices.filter { !claimed[it] }
    if (leftoverHere.size == leftoverThere.size) {
      leftoverHere.forEachIndexed { position, index ->
        val mate = leftoverThere[position]
        claimed[mate] = true
        mates[index] = there[mate]
      }
    }
    val out = here.mapIndexed { index, ours ->
      val theirs = mates[index]
      Row(
        kind = kind,
        subject = displaySubject(ours, theirs),
        here = ours.label,
        there = theirs?.label,
        presence = if (theirs == null) Presence.ONLY_HERE else Presence.BOTH,
        fields = fields(kind, ours.detail, theirs?.detail.orEmpty()),
      )
    }
    // The sibling's own leftovers, in its manifest's order, after everything that paired.
    return out +
      there.indices
        .filterNot { claimed[it] }
        .map { index ->
          val theirs = there[index]
          Row(
            kind = kind,
            subject = displaySubject(null, theirs),
            here = null,
            there = theirs.label,
            presence = Presence.ONLY_THERE,
            fields = fields(kind, emptyMap(), theirs.detail),
          )
        }
  }

  /**
   * The union of both sides' resolved properties, preferred keys first and the rest alphabetically.
   *
   * A union rather than a fixed list: the detail map is what the producer measured, and a key this
   * file had not heard of is exactly the kind of thing worth showing rather than silently dropping.
   * The preferred order puts the fields a type or layout argument is actually had in — family,
   * size, weight — above the long tail.
   */
  private fun fields(
    kind: String,
    here: Map<String, String>,
    there: Map<String, String>,
  ): List<Field> {
    val preferred = PREFERRED_FIELDS[kind].orEmpty()
    val keys =
      (here.keys + there.keys).sortedWith(
        compareBy({ preferred.indexOf(it).takeIf { i -> i >= 0 } ?: preferred.size }, { it })
      )
    return keys.map { Field(it, here[it], there[it]) }
  }

  /** The key two sides pair on, or null when this row names nothing they could share. */
  private fun subjectOf(annotation: DesignAnnotation): String? =
    annotation.role?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
      ?: annotation.detail["token"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

  /** What the row is called on screen: this side's name for the node, else the sibling's. */
  private fun displaySubject(here: DesignAnnotation?, there: DesignAnnotation?): String =
    listOfNotNull(here, there).firstNotNullOfOrNull {
      it.role?.trim()?.takeIf { role -> role.isNotEmpty() }
    }
      ?: listOfNotNull(here, there).firstNotNullOfOrNull {
        it.detail["token"]?.trim()?.takeIf { token -> token.isNotEmpty() }
      }
      ?: "—"

  /**
   * Typography leads: it is the layer with live evidence behind it and the one a pixel diff hides
   * most completely. Layout follows, and theme last — a published catalog carries no theme layer
   * today, so it appears only where both sides derive one from a live capture.
   */
  private val KIND_ORDER =
    listOf(AnnotationKind.TYPOGRAPHY, AnnotationKind.LAYOUT, AnnotationKind.THEME)

  private val PREFERRED_FIELDS =
    mapOf(
      AnnotationKind.TYPOGRAPHY to
        listOf(
          "token",
          "fontFamily",
          "fontSize",
          "lineHeight",
          "fontWeight",
          "letterSpacing",
          "fontStyle",
          "textAlign",
          "color",
        ),
      AnnotationKind.LAYOUT to
        listOf("width", "height", "padding", "gap", "defaultMinSize", "arrangement"),
      AnnotationKind.THEME to listOf("token", "fill", "border", "cornerRadius", "shape"),
    )
}
