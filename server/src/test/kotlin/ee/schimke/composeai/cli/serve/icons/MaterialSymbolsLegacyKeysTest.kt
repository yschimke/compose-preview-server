package ee.schimke.composeai.cli.serve.icons

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holds the legacy migration against every name the shipped catalog ever issued.
 *
 * A migration that quietly resolves nothing is the expensive failure here: a design saved last year
 * opens with blank nodes, or worse, the wrong picture. So the rule is not trusted — all 2,133
 * distinct Material Icons member names are checked against the names the pinned Material Symbols
 * face actually carries, and the expected answer for each is committed.
 *
 * The two fixtures are *test* copies of pinned data and say so in their headers. The server reads
 * the real code point list from its cache; nothing here is shipped, and a pin bump that moves a
 * name shows up as a diff in `legacy-icon-names.tsv` rather than as a blank icon in somebody's
 * design.
 */
class MaterialSymbolsLegacyKeysTest {

  private fun lines(name: String) =
    checkNotNull(javaClass.getResourceAsStream("/material-symbols/$name"))
      .use { it.readBytes() }
      .decodeToString()
      .lineSequence()
      .filter { it.isNotBlank() && !it.startsWith("#") }
      .toList()

  private val names: Set<String> = lines("symbol-names.txt").toSet()

  private val expected: Map<String, String> =
    lines("legacy-icon-names.tsv").associate {
      val fields = it.split('\t')
      fields[0] to fields.getOrElse(1) { "" }
    }

  private val known: (String) -> Boolean = { it in names }

  @Test
  fun `every stored icon name migrates to the name the fixture records`() {
    assertEquals(2_133, expected.size, "the legacy inventory changed size")
    val wrong =
      expected.entries.mapNotNull { (legacy, target) ->
        val actual =
          when (val result = MaterialSymbolsLegacyKeys.migrate("filled/$legacy", known)) {
            is LegacyIconMigration.Migrated -> result.name
            is LegacyIconMigration.NoEquivalent -> ""
            is LegacyIconMigration.Unrecognised -> "<unrecognised>"
          }
        if (actual == target) null else "$legacy -> $actual, expected '$target'"
      }
    assertTrue(wrong.isEmpty(), "${wrong.size} names migrate wrongly: ${wrong.take(20)}")
  }

  @Test
  fun `every migrated name is one the pinned face carries`() {
    val absent = expected.values.filter { it.isNotEmpty() && it !in names }
    assertTrue(absent.isEmpty(), "targets the face does not carry: $absent")
    assertEquals(16, expected.values.count(String::isEmpty), "icons with no Symbols equivalent")
  }

  @Test
  fun `no legacy name has two spellings the face carries`() {
    // The rule tries a second spelling for the digit boundary, which is only safe while no name is
    // ambiguous. If upstream ever adds both `co2` and `co_2`, this fails rather than the picker
    // quietly drawing whichever came first.
    val ambiguous =
      expected.keys.filter { legacy ->
        MaterialSymbolsLegacyKeys.candidates(legacy).count(known) > 1
      }
    assertTrue(ambiguous.isEmpty(), "ambiguous spellings: $ambiguous")
  }

  @Test
  fun `no override contradicts a spelling the face already carries`() {
    // The two exception tables only earn their place where the rule fails. An override pointing
    // somewhere else while the derived spelling exists would be a silent redraw — and a row nobody
    // could later tell was still needed.
    val contradicted =
      expected.entries.mapNotNull { (legacy, target) ->
        val byRule = MaterialSymbolsLegacyKeys.candidates(legacy).firstOrNull(known)
        if (byRule == null || byRule == target) null
        else "$legacy: rule says $byRule, table $target"
      }
    assertTrue(contradicted.isEmpty(), "overrides fighting the rule: $contradicted")

    // And the tables are exactly as large as the failures require.
    val overridden = expected.keys.count { MaterialSymbolsLegacyKeys.candidates(it).none(known) }
    assertEquals(27, overridden, "the hand-maintained exceptions changed size")
  }

  @Test
  fun `a style is a face and a fill, and mirroring survives`() {
    // `Filled`, `Rounded` and `Sharp` are all filled drawings; only `Outlined` is stroked. Getting
    // this backwards would redraw every icon in every existing design.
    fun migrated(key: String) =
      MaterialSymbolsLegacyKeys.migrate(key, known) as LegacyIconMigration.Migrated

    assertEquals("outlined" to 1f, migrated("filled/search").let { it.style to it.fill })
    assertEquals("outlined" to 0f, migrated("outlined/search").let { it.style to it.fill })
    assertEquals("rounded" to 1f, migrated("rounded/search").let { it.style to it.fill })
    assertEquals("sharp" to 1f, migrated("sharp/search").let { it.style to it.fill })

    // The forty-six unqualified compatibility keys named `Icons.Filled`.
    assertEquals(migrated("filled/search"), migrated("search"))

    val back = migrated("autoMirrored/filled/arrowBack")
    assertEquals("arrow_back", back.name)
    assertTrue(back.autoMirror, "a directional icon must keep flipping in RTL")
    assertEquals(1f, back.fill)
    assertTrue(!migrated("filled/search").autoMirror)
  }

  @Test
  fun `two-tone is answered, and says what it cost`() {
    val twoTone = migrated("twoTone/star")
    assertEquals("star", twoTone.name)
    assertEquals(0f, twoTone.fill)
    assertEquals(MaterialSymbolsLegacyKeys.TWO_TONE_NOTE, twoTone.note)
    assertEquals(null, migrated("filled/star").note)
  }

  @Test
  fun `a key this catalog never issued is not guessed at`() {
    assertTrue(
      MaterialSymbolsLegacyKeys.migrate("engraved/search", known)
        is LegacyIconMigration.Unrecognised
    )
    assertTrue(
      MaterialSymbolsLegacyKeys.migrate("filled/", known) is LegacyIconMigration.Unrecognised
    )
    assertTrue(
      MaterialSymbolsLegacyKeys.migrate("a/b/c", known) is LegacyIconMigration.Unrecognised
    )
    // A well-formed key naming an icon nothing carries is a different answer: the spelling is fine,
    // the icon is not there.
    assertTrue(
      MaterialSymbolsLegacyKeys.migrate("filled/noSuchIcon", known)
        is LegacyIconMigration.NoEquivalent
    )
  }

  private fun migrated(key: String) =
    MaterialSymbolsLegacyKeys.migrate(key, known) as LegacyIconMigration.Migrated
}
