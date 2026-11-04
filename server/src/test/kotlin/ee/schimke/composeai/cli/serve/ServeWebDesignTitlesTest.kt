package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The designs page lets a person choose a design's title when creating it and change it later,
 * without the id — which is the URL — moving.
 */
class ServeWebDesignTitlesTest {
  @Test
  fun `the new design and copy forms offer an optional title beside the id`() {
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows = listOf(row("sunny-otter", renameAction = "")),
        viewerActorId = "github:octocat",
        createAction = "/ui-builder/designs",
        copyAction = "/ui-builder/designs/copy",
        catalogs =
          listOf(
            ServeWeb.UiBuilderNewDesignOption(
              "m3-catalog",
              "Mobile app",
              listOf(ServeWeb.UiBuilderNewDesignTemplate("blank", "Blank screen")),
            )
          ),
        suggestedDesignId = "brave-heron",
      )

    val create = page.substringAfter("Start a new design").substringBefore("</form>")
    assertTrue("""name="title"""" in create, create)
    assertFalse(Regex("""name="title"[^>]*required""").containsMatchIn(create), create)
    val copy = page.substringAfter("Start from an existing design").substringBefore("</form>")
    assertTrue("""name="title"""" in copy, copy)
  }

  @Test
  fun `a writer's card offers Rename, prefilled with the current title, and a reader's does not`() {
    val writer =
      ServeWeb.uiBuilderDesignsPage(
        rows = listOf(row("sunny-otter", renameAction = "/ui-builder/sunny-otter/rename")),
        viewerActorId = "github:octocat",
      )
    val form = writer.substringAfter("<summary>Rename</summary>").substringBefore("</form>")
    assertTrue("""action="/ui-builder/sunny-otter/rename"""" in form, form)
    assertTrue("""value="Checkout &amp; pay"""" in form, form)

    val reader =
      ServeWeb.uiBuilderDesignsPage(
        rows = listOf(row("sunny-otter", renameAction = "")),
        viewerActorId = "github:octocat",
      )
    assertFalse("<summary>Rename</summary>" in reader, reader)
  }

  private fun row(designId: String, renameAction: String) =
    ServeWeb.UiBuilderDesignRow(
      designId = designId,
      title = "Checkout & pay",
      catalogSystemId = "m3-catalog",
      revision = 1,
      updatedAtEpochMillis = null,
      ownerActorId = "github:octocat",
      requesterRole = "owner",
      requesterAllowed = "read, write",
      designHref = "/ui-builder/$designId",
      shareAction = "/ui-builder/$designId/access",
      grants = emptyList(),
      unopenableReason = null,
      renameAction = renameAction,
    )
}
