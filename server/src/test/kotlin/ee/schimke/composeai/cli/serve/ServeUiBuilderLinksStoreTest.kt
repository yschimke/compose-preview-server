package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeUiBuilderLinksStoreTest {
  private val root = Files.createTempDirectory("links-store")
  private val store = ServeUiBuilderLinksStore(root)

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  private fun stored(result: LinksWriteResult): StoredLinks =
    assertIs<LinksWriteResult.Stored>(result).links

  private fun refusal(result: LinksWriteResult): String =
    assertIs<LinksWriteResult.Refused>(result).reason

  @Test
  fun `a design nobody has linked reads as nothing`() {
    assertNull(store.read("design-1"))
  }

  @Test
  fun `a stored record comes back on the next open`() {
    val written =
      stored(
        store.replace(
          "design-1",
          StoredLinks(
            issue = "https://github.com/yschimke/compose-preview-server/issues/12",
            reference = "https://www.figma.com/design/abc/Checkout?node-id=1-2",
            pr = "https://github.com/yschimke/compose-preview-server/pull/34",
            thread = "https://example.slack.com/archives/C1/p1700000000",
            previous = "checkout-v1",
          ),
        )
      )
    val reopened = requireNotNull(store.read("design-1"))
    assertEquals("design-1", reopened.designId, "the host names the design, not the caller")
    assertEquals(written.issue, reopened.issue)
    assertEquals(written.reference, reopened.reference)
    assertEquals(written.pr, reopened.pr)
    assertEquals(written.thread, reopened.thread)
    assertEquals("checkout-v1", reopened.previous)
  }

  @Test
  fun `a link that is not an http URL is refused with a reason`() {
    assertEquals(
      "`issue` must be an absolute http or https URL",
      refusal(store.replace("design-1", StoredLinks(issue = "javascript:alert(1)"))),
    )
    assertEquals(
      "`reference` must be an absolute http or https URL",
      refusal(store.replace("design-1", StoredLinks(reference = "figma.com/design/abc"))),
    )
    // And nothing was written on the way to the refusal: a design whose links were refused has the
    // links it had before, which here is none.
    assertNull(store.read("design-1"))
  }

  @Test
  fun `previous names a design on this host rather than a URL`() {
    assertEquals(
      "`previous` must be a design id on this host, not a URL",
      refusal(
        store.replace("design-1", StoredLinks(previous = "https://example.com/designs/checkout"))
      ),
    )
  }

  @Test
  fun `a link too long to be a link is refused`() {
    val enormous = "https://example.com/" + "x".repeat(ServeUiBuilderLinksStore.MAX_VALUE_BYTES)
    assertEquals(
      "`pr` must be under ${ServeUiBuilderLinksStore.MAX_VALUE_BYTES} bytes",
      refusal(store.replace("design-1", StoredLinks(pr = enormous))),
    )
  }

  @Test
  fun `a record with nothing in it is stored as no record at all`() {
    store.replace("design-1", StoredLinks(issue = "https://example.com/issues/1"))
    assertTrue(store.read("design-1") != null)

    val cleared = stored(store.replace("design-1", StoredLinks()))
    assertTrue(cleared.isEmpty)
    assertNull(store.read("design-1"), "an all-empty record deletes the file")
    // Blank strings are how a form says "unset", and they clear the record the same way.
    store.replace("design-1", StoredLinks(issue = "https://example.com/issues/1"))
    store.replace("design-1", StoredLinks(issue = "   ", pr = ""))
    assertNull(store.read("design-1"))
  }

  @Test
  fun `deleting says whether there was anything to delete`() {
    assertEquals(LinksDeleteResult.ABSENT, store.delete("design-1"))
    store.replace("design-1", StoredLinks(issue = "https://example.com/issues/1"))
    assertEquals(LinksDeleteResult.REMOVED, store.delete("design-1"))
    assertNull(store.read("design-1"))
  }

  @Test
  fun `a clear that could not happen is refused rather than reported as stored`() {
    store.replace("design-1", StoredLinks(issue = "https://example.com/issues/1"))
    // A non-empty directory where the record file belongs is the portable form of "the delete
    // failed" — `deleteIfExists` throws on it for root as well, which a read-only parent does not.
    val record = Files.list(root).use { it.toList() }.single()
    Files.delete(record)
    Files.createDirectory(record)
    Files.writeString(record.resolve("occupied"), "x")

    assertEquals(LinksDeleteResult.FAILED, store.delete("design-1"))
    // And the caller is told, rather than handed a 200 over a record that is still on disk.
    assertIs<LinksWriteResult.Refused>(store.replace("design-1", StoredLinks()))
  }

  @Test
  fun `a corrupt record reads as nothing rather than failing the design's open`() {
    store.replace("design-1", StoredLinks(issue = "https://example.com/issues/1"))
    Files.list(root).use { entries -> entries.forEach { Files.writeString(it, "{ not json") } }
    assertNull(store.read("design-1"))
  }

  @Test
  fun `the reverse lookup finds every design citing one issue`() {
    val issue = "https://github.com/yschimke/compose-preview-server/issues/12"
    store.replace("checkout", StoredLinks(issue = issue))
    store.replace("basket", StoredLinks(issue = issue))
    store.replace("unrelated", StoredLinks(issue = "https://example.com/issues/99"))

    assertEquals(listOf("basket", "checkout"), store.citing(issue))
    assertEquals(emptyList(), store.citing("https://example.com/issues/1"))
    assertEquals(emptyList(), store.citing("  "))
  }

  @Test
  fun `a design id never becomes a path segment`() {
    store.replace("../../escape", StoredLinks(issue = "https://example.com/issues/1"))

    val files = Files.list(root).use { it.toList() }
    assertEquals(1, files.size, files.toString())
    assertTrue(files.single().parent == root, files.toString())
    assertEquals("https://example.com/issues/1", store.read("../../escape")?.issue)
  }
}
