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

    val folders = Regex("""<section class="cp-design-folder" aria-label="([^"]+)" """)
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
    val apply =
      page
        .substringAfter("function apply() {")
        .substringBefore("""box.addEventListener("input", apply)""")
    assertTrue("""document.querySelectorAll(".cp-design-card")""" in apply, apply)
    // The folder's own count follows the filter, not only the page total.
    assertTrue("""folder.querySelector(".cp-design-folder-count")""" in page, page)
    assertTrue("""<span class="cp-designs-count cp-design-folder-count">1 design</span>""" in page)
  }

  @Test
  fun `folders are listed first, and picking one narrows the list to it`() {
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

    val picker =
      page
        .substringAfter("""<nav class="cp-design-folders" aria-label="Folders" hidden>""")
        .substringBefore("</nav>")
    val picks =
      Regex(
          """<span class="cp-design-folder-pick-name">([^<]+)</span> <span class="cp-designs-count">([^<]+)</span>"""
        )
        .findAll(picker)
        .map { "${it.groupValues[1]} · ${it.groupValues[2]}" }
        .toList()
    assertEquals(
      listOf(
        "All designs · 4 designs",
        "Tiles · 1 design",
        "wear · 2 designs",
        "No folder · 1 design",
      ),
      picks,
    )
    // The list of folders leads the page, above the filter and every folder's cards.
    assertTrue(page.indexOf("cp-design-folders") < page.indexOf("cp-design-filter"))
    // Each pick names the section it selects, and the unfiled designs travel as an empty name.
    assertTrue("""data-cp-folder-index="1" data-cp-folder-name="wear"""" in picker, picker)
    assertTrue("""data-cp-folder-index="2" data-cp-folder-name=""""" in picker, picker)
    assertTrue("""aria-label="wear" data-cp-folder-index="1">""" in page, page)
    assertTrue("var inFolder = picked === null" in page, page)
  }

  @Test
  fun `nothing filed offers no folder list`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(rows = listOf(row("a")), viewerActorId = "github:octocat")

    assertFalse("cp-design-folders" in page.substringBefore("<script>"), page)
  }

  @Test
  fun `creating a design is folded below the list rather than leading the page`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows = listOf(row("a", folder = "x")),
        viewerActorId = "github:octocat",
        createAction = "/ui-builder/designs",
        catalogs =
          listOf(
            ServeWeb.UiBuilderNewDesignOption(
              systemId = "m3-catalog",
              label = "m3-catalog",
              templates = listOf(ServeWeb.UiBuilderNewDesignTemplate("blank", "Blank screen")),
            )
          ),
      )

    assertFalse("cp-designs-quick" in page, page)
    assertTrue("""<details class="cp-designs-create-more">""" in page, page)
    assertTrue(page.indexOf("cp-designs-create-more") > page.indexOf("cp-design-card"))
  }

  @Test
  fun `cards offer open and duplicate with secondary controls in an accessible more menu`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows =
          listOf(
            row("watch")
              .copy(
                copyAction = "/copy",
                copySuggestedId = "watch-copy",
                deleteAction = "/delete",
                folderAction = "/folder",
              )
          ),
        viewerActorId = "github:octocat",
      )
    val actions = page.substringAfter("""<div class="cp-design-actions">""")
    assertTrue(
      actions.substringBefore("""<details class="cp-design-menu">""").contains(">Open</a>")
    )
    assertTrue(
      actions
        .substringBefore("""<details class="cp-design-menu">""")
        .contains(">Duplicate</summary>")
    )
    val more = actions.substringAfter("""<div class="cp-design-menu-panel">""")
    assertTrue("More actions for watch" in actions)
    assertTrue(">History</a>" in more)
    assertTrue(">Share</a>" in more)
    assertTrue(">Move to folder</summary>" in more)
    assertTrue(">Delete</summary>" in more)
    assertTrue("name=\"confirm\" value=\"delete\"" in more)
  }

  private fun String.sectionFor(name: String): String =
    substringAfter("""<section class="cp-design-folder" aria-label="$name" """)
      .substringBefore("</section>")

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
