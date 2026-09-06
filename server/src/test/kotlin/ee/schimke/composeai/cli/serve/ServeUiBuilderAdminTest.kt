package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminDesignSummary
import ee.schimke.composeai.uibuilder.service.UiBuilderAdminPort
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServeUiBuilderAdminTest {
  private val root = Files.createTempDirectory("ui-builder-admin")
  private val references = ServeUiBuilderReferenceStore(root.resolve("references"))
  private val comments = ServeUiBuilderCommentStore(root.resolve("comments"))
  private val designs =
    linkedMapOf("design-1" to summary("design-1"), "design-2" to summary("design-2"))
  private val service =
    object : UiBuilderAdminPort {
      override fun adminListDesigns() = designs.values.toList()

      override fun adminDeleteDesign(designId: String) = designs.remove(designId) != null
    }
  private val logs = mutableListOf<String>()
  private val admin = ServeUiBuilderAdmin(service, references, comments, onLog = logs::add)

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  @Test
  fun `lists what the service holds`() {
    assertEquals(listOf("design-1", "design-2"), admin.list().map { it.designId })
  }

  @Test
  fun `a delete removes the design and its sidecars`() {
    comments.post("design-1", "designer", CommentPostRequest(null, null, "Why a card?"))
    assertNotNull(comments.read("design-1"))

    assertEquals(ServeUiBuilderAdmin.Result.Deleted("design-1"), admin.delete(" design-1 "))

    assertEquals(listOf("design-2"), admin.list().map { it.designId })
    assertNull(comments.read("design-1"), "the discussion does not outlive the design")
    assertNull(references.read("design-1"))
    assertEquals(listOf("serve: admin deleted UI-builder design design-1"), logs)
  }

  @Test
  fun `a missing design and a blank id are refused by kind`() {
    assertEquals(ServeUiBuilderAdmin.Result.NotFound("nope"), admin.delete("nope"))
    assertIs<ServeUiBuilderAdmin.Result.Invalid>(admin.delete("   "))
    assertEquals(2, admin.list().size)
  }

  private fun summary(id: String) =
    UiBuilderAdminDesignSummary(
      designId = id,
      title = "Design $id",
      revision = 3,
      catalogPin = CatalogReferenceV1("m3-catalog", "rev", "rev", "runtime"),
      ownerActorId = "github:someone",
      collaborators = 1,
      createdAtEpochMillis = 1_000,
      updatedAtEpochMillis = 2_000,
      activeSubscribers = 0,
    )
}
