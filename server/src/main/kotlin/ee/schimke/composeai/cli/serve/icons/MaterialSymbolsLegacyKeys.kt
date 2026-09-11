package ee.schimke.composeai.cli.serve.icons

/** What a stored `iconKey` becomes once icons are named rather than enumerated. */
internal sealed interface LegacyIconMigration {

  /**
   * The key resolves: one Material Symbols name at one axis position.
   *
   * [note] is set only where the migration changes something a person would notice, so an editor
   * can say so on the node rather than leaving them to spot it.
   */
  data class Migrated(
    val name: String,
    val style: String,
    val fill: Float,
    val autoMirror: Boolean,
    val note: String? = null,
  ) : LegacyIconMigration

  /** The key was a real Material Icons member that Material Symbols does not carry. */
  data class NoEquivalent(val legacyName: String) : LegacyIconMigration

  /** Not a key this catalog ever issued — a typo, or a design written by hand. */
  data class Unrecognised(val key: String) : LegacyIconMigration
}

/**
 * Migrates the `m3/icon`.`iconKey` values designs already hold to a Material Symbols name and axes.
 *
 * The stored spellings are lower-camel, because `MaterialIconCatalogTasks` builds them from the
 * Kotlin member: `Icons.AutoMirrored.Filled.ArrowBack` is `autoMirrored/filled/arrowBack`, never
 * `arrow_back`. So every stored key has to be re-spelled, and 11,431 of them exist.
 *
 * **It is a rule plus twenty-seven exceptions, not a table of 11,431 rows.** Re-casing the member
 * name resolves 2,106 of the 2,133 distinct icons against the pinned name list; the rest are
 * upstream renames (`crop169` → `crop_16_9`, the `outbond` typo upstream later fixed) or icons
 * Material Symbols dropped (`facebook`, `pix`, `whatsapp`). A generated 11,431-row table would be a
 * large generated asset for a problem that is mostly arithmetic, and it would have to be
 * regenerated on every pin bump; the exceptions below change only when upstream renames something,
 * and `MaterialSymbolsLegacyKeysTest` holds all 2,133 against the real name list so a rule that
 * stops covering one fails loudly.
 *
 * Resolution takes the names the *pinned face actually carries* rather than trusting the rule: a
 * candidate spelling is only accepted when the catalog has it, so a bad guess is a `NoEquivalent`
 * that somebody sees, never a silently wrong picture.
 */
internal object MaterialSymbolsLegacyKeys {

  /**
   * The legacy style, as a Symbols face and a fill.
   *
   * Material Icons' five styles are not five faces. `Filled`, `Rounded` and `Sharp` are the same
   * drawing with different corner treatments, all of them *filled*; `Outlined` is the stroked one.
   * Material Symbols splits that into three faces and a `FILL` axis, which is exactly the
   * decomposition this lane is built on — so `filled` is the Outlined face at `FILL 1`, and
   * `rounded` and `sharp` are their own faces at `FILL 1`.
   */
  private val STYLES =
    mapOf(
      "filled" to ("outlined" to 1f),
      "outlined" to ("outlined" to 0f),
      "rounded" to ("rounded" to 1f),
      "sharp" to ("sharp" to 1f),
      "twoTone" to ("outlined" to 0f),
    )

  /**
   * Where upstream renamed an icon between Material Icons and Material Symbols.
   *
   * Every entry here is the same drawing under a different spelling — an aspect ratio written out
   * (`crop169` → `crop_16_9`), a typo corrected (`outbond` → `outbound`), or a fill baked into the
   * old name that is now the `FILL` axis (`playCircleFilled` and `playCircleOutline` are both
   * `play_circle`). Anything that would merge two distinct legacy icons onto one name is *not*
   * here: `personAddAlt1` is dropped rather than pointed at `person_add_alt`, which is a different
   * icon that has its own key.
   */
  private val RENAMED =
    mapOf(
      "crop169" to Renamed("crop_16_9"),
      "crop32" to Renamed("crop_3_2"),
      "crop54" to Renamed("crop_5_4"),
      "crop75" to Renamed("crop_7_5"),
      "fireHydrantAlt" to Renamed("fire_hydrant"),
      "grid3x3" to Renamed("grid_3x3"),
      "grid4x4" to Renamed("grid_4x4"),
      "outbond" to Renamed("outbound"),
      // The one place a legacy *name* outranks the legacy *style*. `playCircleFilled` and
      // `playCircleOutline` were two icons, each drawn in all five styles; Material Symbols has one
      // `play_circle` and expresses the difference as `FILL`. Taking fill from the style instead
      // would collapse the pair — `filled/playCircleOutline` would come back filled — so the name's
      // baked fill wins and only the face follows the style.
      "playCircleFilled" to Renamed("play_circle", fill = 1f),
      "playCircleOutline" to Renamed("play_circle", fill = 0f),
      "wifiTetheringErrorRounded" to Renamed("wifi_tethering_error"),
    )

  /** An upstream rename, and the fill it carries when the old *name* said one. */
  private data class Renamed(val name: String, val fill: Float? = null)

  /**
   * Material Icons members Material Symbols does not carry, at all, under any spelling.
   *
   * Mostly third-party brands upstream stopped shipping (`facebook`, `fitbit`, `pix`, `whatsapp`)
   * and variants that were folded away (`panorama*Select`,
   * `signalWifiStatusbarConnectedNoInternet4`). They are listed rather than left to fail the lookup
   * so the difference between "we cannot spell this" and "this icon is gone" survives into what the
   * editor shows.
   */
  private val DROPPED =
    setOf(
      "catchingPokemon",
      "discount",
      "facebook",
      "fitbit",
      "leaveBagsAtHome",
      "miscellaneousServices",
      "noCell",
      "panoramaHorizontalSelect",
      "panoramaPhotosphereSelect",
      "panoramaVerticalSelect",
      "panoramaWideAngleSelect",
      "personAddAlt1",
      "personRemoveAlt1",
      "pix",
      "signalWifiStatusbarConnectedNoInternet4",
      "whatsapp",
    )

  /**
   * The unqualified compatibility keys that do not mean `filled/<the same name>`.
   *
   * The catalog issued 46 keys with no style before it carried styles, and it is tempting to read
   * them as "the filled member of the same name". Five of them are not: `genres` was always
   * `Icons.Filled.Category` — and `genres` is *also* a real Material Symbols icon, a different
   * picture, so the tempting reading silently swaps the glyph rather than failing. `arrowBack`,
   * `arrowForward` and `playlistAdd` were auto-mirrored members, which the same reading would stop
   * flipping in RTL, and `bookmarkBorder` was the outlined one.
   *
   * Each is written as the qualified key it stands for and migrated through the ordinary path, so
   * there is one set of rules rather than two, and all 46 are pinned in
   * `MaterialSymbolsLegacyKeysTest` against the inventory column that issued them.
   */
  private val ALIASES =
    mapOf(
      "arrowBack" to "autoMirrored/filled/arrowBack",
      "arrowForward" to "autoMirrored/filled/arrowForward",
      "bookmarkBorder" to "outlined/bookmarkBorder",
      "genres" to "filled/category",
      "playlistAdd" to "autoMirrored/filled/playlistAdd",
    )

  /**
   * What a two-tone key is told, which depends on where its fill came from.
   *
   * Two-tone has no Symbols equivalent and lands in the outlined face at `FILL 0` — except for the
   * one key whose *name* carries a fill, `twoTone/playCircleFilled`, which stays filled. Saying
   * "unfilled" there would be a sentence contradicting the picture beside it.
   */
  internal const val TWO_TONE_NOTE =
    "Material Symbols has no two-tone style; this is drawn unfilled in the outlined face."

  internal const val TWO_TONE_FILLED_NOTE =
    "Material Symbols has no two-tone style; this is drawn in the outlined face, keeping the fill " +
      "the old name carried."

  /**
   * Migrates one stored key, accepting a spelling only when [known] has it.
   *
   * [known] is the pinned face's own name list — `MaterialSymbolsSource.names` — so this cannot
   * invent a name, and a pin that drops an icon turns into a visible `NoEquivalent` rather than a
   * 404 at draw time.
   */
  fun migrate(key: String, known: (String) -> Boolean): LegacyIconMigration {
    // An unqualified key is a compatibility alias for a qualified one. Most stand for the filled
    // member of the same name; five do not, and are resolved here rather than assumed.
    val qualified =
      if ('/' in key) key else ALIASES[key] ?: "$DEFAULT_STYLE/$key".takeIf { key.isNotEmpty() }
    if (qualified == null) return LegacyIconMigration.Unrecognised(key)
    val autoMirror = qualified.startsWith(AUTO_MIRRORED_PREFIX)
    val parts = qualified.removePrefix(AUTO_MIRRORED_PREFIX).split('/')
    if (parts.size != 2 || parts.any(String::isEmpty)) return LegacyIconMigration.Unrecognised(key)
    val (styleKey, legacyName) = parts
    val (style, styleFill) = STYLES[styleKey] ?: return LegacyIconMigration.Unrecognised(key)
    if (legacyName in DROPPED) return LegacyIconMigration.NoEquivalent(legacyName)
    val renamed = RENAMED[legacyName]
    val spellings = renamed?.name?.let(::listOf) ?: candidates(legacyName)
    val name = spellings.firstOrNull(known) ?: return LegacyIconMigration.NoEquivalent(legacyName)
    return LegacyIconMigration.Migrated(
      name = name,
      style = style,
      fill = renamed?.fill ?: styleFill,
      autoMirror = autoMirror,
      note =
        when {
          styleKey != "twoTone" -> null
          (renamed?.fill ?: styleFill) == 0f -> TWO_TONE_NOTE
          else -> TWO_TONE_FILLED_NOTE
        },
    )
  }

  /** The style the 46 unqualified keys were issued against, bar the five in [ALIASES]. */
  internal const val DEFAULT_STYLE = "filled"

  /**
   * The spellings a legacy member name could have upstream, best first.
   *
   * Two, because the digit boundary is the one place Kotlin's member names and upstream's file
   * names genuinely disagree and neither convention wins: `co2` stays `co2` while `filter1` is
   * `filter_1`. Trying both is safe rather than lucky — no legacy name has both spellings present
   * in the pinned face, which `MaterialSymbolsLegacyKeysTest` asserts over all 2,133.
   */
  internal fun candidates(legacyName: String): List<String> {
    val separated =
      legacyName
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
        .replace(Regex("(?<=[A-Z])(?=[A-Z][a-z])"), "_")
    return listOf(
        separated.lowercase(),
        separated.replace(Regex("(?<=[A-Za-z])(?=[0-9])"), "_").lowercase(),
      )
      .distinct()
  }

  private const val AUTO_MIRRORED_PREFIX = "autoMirrored/"
}
