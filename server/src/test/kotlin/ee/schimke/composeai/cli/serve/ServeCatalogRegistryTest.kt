package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Catalogs discovered from a nominated GitHub project ([ServeCatalogRegistry]) — the parse, and the
 * three delegations the normaliser refuses to make. No network: the document arrives as bytes.
 */
class ServeCatalogRegistryTest {

  private fun bytesOf(json: String): (String, Long) -> ByteArray? = { _, _ -> json.toByteArray() }

  private fun nomination(repo: String) = ServeCatalogRegistry.Nomination(repo)

  @Test
  fun `a registry contributes its listed catalogs, pinned to its own repo`() {
    val problems = mutableListOf<String>()
    val contribution =
      ServeCatalogRegistry.fetch(
        nomination("yschimke/compose-preview-imports"),
        bytesOf(
          """
          {
            "catalogs": [
              { "system": "joreilly-peopleinspace" },
              { "system": "joreilly-bikeshare", "listed": false }
            ]
          }
          """
            .trimIndent()
        ),
        problems::add,
      )!!

    assertEquals(
      listOf("joreilly-peopleinspace", "joreilly-bikeshare"),
      contribution.entries.map { it.system },
    )
    // Every entry serves from the registry project itself, whether or not it said so.
    assertTrue(contribution.entries.all { it.repo == "yschimke/compose-preview-imports" })
    assertEquals(listOf(true, false), contribution.entries.map { it.listed })
    assertEquals(emptyList(), problems)
  }

  @Test
  fun `an entry naming another repo is dropped, not served`() {
    val problems = mutableListOf<String>()
    val contribution =
      ServeCatalogRegistry.normalize(
        "yschimke/compose-preview-imports",
        ServeCatalogsConfig(
          catalogs =
            listOf(
              ServeCatalogsConfig.Entry(system = "mine"),
              ServeCatalogsConfig.Entry(system = "theirs", repo = "someone/else"),
            )
        ),
        problems::add,
      )

    // Nominating a registry delegates WHICH of its catalogs are served, never where bytes may come
    // from. Pointing the box at a third party stays an operator decision.
    assertEquals(listOf("mine"), contribution.entries.map { it.system })
    assertTrue(problems.single().contains("names someone/else"))
  }

  @Test
  fun `attribution repos are stripped, so a group claim can only cover the registry itself`() {
    val contribution =
      ServeCatalogRegistry.normalize(
        "yschimke/compose-preview-imports",
        ServeCatalogsConfig(
          groups = listOf(ServeCatalogsConfig.Group(id = "imports", heading = "Imported apps")),
          catalogs =
            listOf(
              ServeCatalogsConfig.Entry(
                system = "mine",
                group = "imports",
                attributionRepos = listOf("google/android-samples"),
              )
            ),
        ),
      )

    val entry = contribution.entries.single()
    assertEquals(emptyList(), entry.attributionRepos)
    assertEquals(setOf("yschimke/compose-preview-imports"), contribution.homeGroup(entry)!!.repos)
    assertEquals("Imported apps", contribution.homeGroup(entry)!!.heading)
  }

  @Test
  fun `a claim on a group the registry does not declare falls back to the owner heading`() {
    val contribution =
      ServeCatalogRegistry.normalize(
        "yschimke/compose-preview-imports",
        ServeCatalogsConfig(
          catalogs = listOf(ServeCatalogsConfig.Entry(system = "mine", group = "design-systems"))
        ),
      )

    // Dropped claim, kept catalog: a misfiled card beats a missing one — and a registry must not be
    // able to file itself under a heading the BOX reserved by declaring it in catalogs.json.
    val entry = contribution.entries.single()
    assertNull(entry.group)
    assertNull(contribution.homeGroup(entry))
  }

  @Test
  fun `a malformed entry is dropped with a reason and the rest still serve`() {
    val problems = mutableListOf<String>()
    val contribution =
      ServeCatalogRegistry.normalize(
        "yschimke/compose-preview-imports",
        ServeCatalogsConfig(
          catalogs =
            listOf(
              ServeCatalogsConfig.Entry(system = "../etc/passwd"),
              ServeCatalogsConfig.Entry(system = "ok"),
              ServeCatalogsConfig.Entry(system = "ok"),
            )
        ),
        problems::add,
      )

    assertEquals(listOf("ok"), contribution.entries.map { it.system })
    assertEquals(2, problems.size)
  }

  @Test
  fun `an unreadable or absent document contributes nothing rather than failing the boot`() {
    val problems = mutableListOf<String>()
    assertNull(ServeCatalogRegistry.fetch(nomination("a/b"), { _, _ -> null }, problems::add))
    assertEquals(emptyList(), problems)

    assertNull(
      ServeCatalogRegistry.fetch(nomination("a/b"), bytesOf("not json at all"), problems::add)
    )
    assertTrue(problems.single().contains("not readable"))
  }

  @Test
  fun `only well-formed owner slash repo values are nominated`() {
    val problems = mutableListOf<String>()
    assertEquals(
      listOf(nomination("yschimke/compose-preview-imports"), nomination("a/b")),
      ServeCatalogRegistry.parseRepos(
        " yschimke/compose-preview-imports , a/b , not-a-repo , a/b ",
        problems::add,
      ),
    )
    assertTrue(problems.single().contains("not-a-repo"))
  }

  @Test
  fun `a nomination may name its own ref, and a traversal is not a ref`() {
    val problems = mutableListOf<String>()
    assertEquals(
      listOf(ServeCatalogRegistry.Nomination("a/b", "release/2.x")),
      ServeCatalogRegistry.parseRepos("a/b@release/2.x , a/b@../../etc", problems::add),
    )
    assertTrue(problems.single().contains("usable ref"))
  }

  @Test
  fun `the document is read from the first ref that answers, HEAD first`() {
    // HEAD is raw's alias for the default branch, which is the question being asked, so it goes
    // first. main/master follow for the case that bit once: a registry repo whose default branch
    // pointed elsewhere, so HEAD faithfully served a tree without the freshly-merged document and
    // the box reported a live registry as absent. They are never reached when HEAD answers.
    assertEquals(listOf("HEAD", "main", "master"), nomination("a/b").refs)
    assertEquals(listOf("v1"), ServeCatalogRegistry.Nomination("a/b", "v1").refs)

    val asked = mutableListOf<String>()
    val contribution =
      ServeCatalogRegistry.fetch(
        nomination("a/b"),
        fetch = { url, _ ->
          asked += url
          if (url.contains("/master/")) """{ "catalogs": [ { "system": "ok" } ] }""".toByteArray()
          else null
        },
      )!!

    assertEquals(
      listOf(
        "https://raw.githubusercontent.com/a/b/HEAD/${ServeCatalogRegistry.FILE_PATH}",
        "https://raw.githubusercontent.com/a/b/main/${ServeCatalogRegistry.FILE_PATH}",
        "https://raw.githubusercontent.com/a/b/master/${ServeCatalogRegistry.FILE_PATH}",
      ),
      asked,
    )
    assertEquals(listOf("ok"), contribution.entries.map { it.system })
  }
}
