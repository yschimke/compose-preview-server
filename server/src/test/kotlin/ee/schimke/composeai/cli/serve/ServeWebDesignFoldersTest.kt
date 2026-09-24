package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The file manager lists designs under their shared folder, so a person who files their designs can
 * find a folder rather than read every card for a "Folder ·" line.
 */
class ServeWebDesignFoldersTest {
  @Test
  fun `filed designs are grouped under their folder, in name order, with unfiled designs last`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows =
          listOf(
            row("loose-sketch", folder = null),
            row("watch-face", folder = "wear"),
            row("tile-draft", folder = "Tiles"),
            row("widget-draft", folder = "wear"),
          ),
        viewerActorId = "github:octocat",
      )

    val folders = Regex("""<section class="cp-design-folder" aria-label="([^"]+)">""")
    assertEquals(
      listOf("Tiles", "wear", "No folder"),
      folders.findAll(page).map { it.groupValues[1] }.toList(),
    )
    // Each design is under its own folder, in the order the rows came in: newest first.
    val wear = page.sectionFor("wear")
    assertTrue(wear.indexOf("watch-face") in 0 until wear.indexOf("widget-draft"), wear)
    assertFalse("tile-draft" in wear, wear)
    assertTrue("loose-sketch" in page.sectionFor("No folder"))
    assertTrue(">2 designs</span>" in wear, wear)
  }

  @Test
  fun `a page with nothing filed stays one grid with no folder headings`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows = listOf(row("a"), row("b")),
        viewerActorId = "github:octocat",
      )

    assertFalse("""<section class="cp-design-folder"""" in page, page)
    assertEquals(1, Regex("""<div class="cp-designs-grid">""").findAll(page).count())
  }

  @Test
  fun `the filter hides an emptied folder and recounts the rest`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows = listOf(row("a", folder = "x")),
        viewerActorId = "github:octocat",
      )

    assertTrue("folder.hidden = left === 0;" in page, page)
    // The script sits above the grid, so the cards are found when the filter runs, not at parse.
    val listener = page.substringAfter("""box.addEventListener("input"""")
    assertTrue("""document.querySelectorAll(".cp-design-card")""" in listener, listener)
    // The folder's own count follows the filter, not only the page total.
    assertTrue("""folder.querySelector(".cp-design-folder-count")""" in page, page)
    assertTrue("""<span class="cp-designs-count cp-design-folder-count">1 design</span>""" in page)
  }

  private fun String.sectionFor(name: String): String =
    substringAfter("""aria-label="$name">""").substringBefore("</section>")

  private fun row(designId: String, folder: String? = null) =
    ServeWeb.UiBuilderDesignRow(
      designId = designId,
      title = designId,
      catalogSystemId = "wear-m3-catalog",
      revision = 1,
      updatedAtEpochMillis = null,
      ownerActorId = "github:octocat",
      requesterRole = "owner",
      requesterAllowed = "read, write",
      designHref = "/ui-builder/$designId",
      shareAction = "/ui-builder/$designId/access",
      grants = emptyList(),
      unopenableReason = null,
      folder = folder,
    )
}
