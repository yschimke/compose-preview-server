package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An **imported** catalog — rendered from someone else's project, served from a staging repository
 * — must be attributed to the project it came from, not to the repo that happens to host it.
 *
 * Both halves matter and both were wrong. `preview.coo.ee` serves `joreilly/Confetti`'s catalogs
 * under a derived "joreilly repositories" section, and an import of `joreilly/PeopleInSpace`
 * published through `yschimke/compose-preview-imports` would have landed in a *yschimke* section
 * instead, split from the very catalogs it belongs beside. And nothing on the card said the work
 * was somebody else's: the id, title and provenance line all describe the catalog, never its
 * origin.
 */
class ServeHomeImportedCatalogTest {

  private fun system(
    id: String,
    importedFrom: String? = null,
    sourceRepo: String? = null,
    catalogSourceRepo: String? = null,
  ) =
    ServeWeb.HomeSystem(
      system = id,
      title = id,
      subtitle = null,
      previewCount = 1,
      trust = "trusted",
      sourceRepo = sourceRepo,
      importedFrom = importedFrom,
      catalogSourceRepo = catalogSourceRepo,
      heroPreviewId = null,
    )

  private fun page(vararg systems: ServeWeb.HomeSystem) =
    ServeWeb.homeIndexPage(systems = systems.toList(), token = "t", isPublic = true)

  @Test
  fun `an import is sectioned by the project it came from, not the repo serving it`() {
    val html =
      page(
        system("confetti-wear", sourceRepo = "joreilly/Confetti"),
        system(
          "joreilly-peopleinspace",
          sourceRepo = "yschimke/compose-preview-imports",
          importedFrom = "joreilly/PeopleInSpace",
        ),
      )

    assertTrue(
      html.contains("<h1 class=\"cp-head\">joreilly repositories</h1>"),
      "the upstream owner's section is the one an import joins",
    )
    // The point of the change: no separate section for the staging repo's owner.
    assertTrue(
      !html.contains("<h1 class=\"cp-head\">yschimke repositories</h1>"),
      "an import must not open a section for the repo that merely serves it",
    )
    // And it lands in that section rather than the "Other" bucket.
    assertTrue(
      html.indexOf("<h1 class=\"cp-head\">joreilly repositories</h1>") <
        html.indexOf("href=\"/joreilly-peopleinspace/\""),
      "the imported card sits under the upstream owner's heading",
    )
  }

  @Test
  fun `an import names its origin on the card`() {
    val html =
      page(
        system(
          "joreilly-peopleinspace",
          sourceRepo = "yschimke/compose-preview-imports",
          importedFrom = "joreilly/PeopleInSpace",
        )
      )

    assertTrue(html.contains("cp-sys-imported"), "no imported badge on the card")
    assertTrue(
      html.contains("imported from joreilly/PeopleInSpace"),
      "the badge must name the project, not just say 'imported'",
    )
  }

  @Test
  fun `an ordinary catalog is unchanged`() {
    // The regression guard: every catalog on the box today has no importedFrom, and must keep
    // grouping by its own repo owner with no badge.
    val html = page(system("confetti-wear", sourceRepo = "joreilly/Confetti"))

    assertTrue(html.contains("<h1 class=\"cp-head\">joreilly repositories</h1>"))
    assertTrue(!html.contains("cp-sys-imported"), "a non-imported catalog must carry no badge")
  }

  @Test
  fun `an import declares its own origin when the registration lost importedFrom`() {
    // What actually reached preview.coo.ee (compose-ai-tools#5012): the catalogs were imports of
    // joreilly projects, served from the staging repo, and their registration carried no
    // `importedFrom` — so they sectioned by the delivery branch and sat under `yschimke
    // repositories`, split from the joreilly catalogs they belong beside. The catalog's own
    // `source.repo` says whose Kotlin it is and travels with the bytes, so it answers when the
    // configuration doesn't.
    val html =
      page(
        system("confetti-wear", sourceRepo = "joreilly/Confetti"),
        system(
          "joreilly-bikeshare",
          sourceRepo = "yschimke/compose-preview-imports",
          catalogSourceRepo = "joreilly/BikeShare",
        ),
      )

    assertTrue(
      !html.contains("<h1 class=\"cp-head\">yschimke repositories</h1>"),
      "the staging repo's owner must not collect an import that names its upstream",
    )
    assertTrue(
      html.indexOf("<h1 class=\"cp-head\">joreilly repositories</h1>") <
        html.indexOf("href=\"/joreilly-bikeshare/\""),
      "the import belongs under the upstream owner's heading",
    )
    // Attribution only; the badge stays the operator's statement about the catalog.
    assertTrue(!html.contains("cp-sys-imported"), "a declared source is not an imported badge")
  }

  @Test
  fun `the operator's origin outranks the catalog's own`() {
    // The catalog's `source.repo` is written by whoever may push the delivery branch, so it is a
    // fallback and never a veto: where the operator (or the registry document they nominated) has
    // said where an import came from, that is the answer.
    val html =
      page(
        system(
          "joreilly-peopleinspace",
          sourceRepo = "yschimke/compose-preview-imports",
          importedFrom = "joreilly/PeopleInSpace",
          catalogSourceRepo = "someone-else/fork",
        )
      )

    assertTrue(html.contains("<h1 class=\"cp-head\">joreilly repositories</h1>"))
    assertTrue(
      !html.contains("<h1 class=\"cp-head\">someone-else repositories</h1>"),
      "the catalog's own claim must not override the operator's",
    )
  }

  @Test
  fun `an escaped origin cannot inject markup`() {
    // importedFrom reaches the page from operator config, and every other string on this card is
    // escaped; this one is too.
    val html =
      page(system("x", sourceRepo = "a/b", importedFrom = "<script>alert(1)</script>/repo"))

    assertTrue(!html.contains("<script>alert(1)</script>"), "importedFrom is not escaped")
  }
}
