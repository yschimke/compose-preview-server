package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Where a catalog's record comes from: the operator's file first, the served catalog's second. */
class ComponentRecordSourceTest {
  private val root = Files.createTempDirectory("records").toFile().also { it.deleteOnExit() }

  private fun record(dir: String, module: String): File =
    File(root, "$dir/components.json").also {
      it.parentFile.mkdirs()
      it.writeText("""{"schemaVersion":2,"module":"$module","variant":"main","components":[]}""")
    }

  @Test
  fun `a served catalog's record is read where the operator named none`() {
    val served = record("g1", "served")
    val source = ComponentRecordSource(emptyMap()) { if (it == "confetti-mobile") served else null }

    val found = assertIs<ComponentRecordSource.Lookup.Found>(source.record("confetti-mobile"))
    assertEquals("served", found.record.module)
    assertEquals(ComponentRecordSource.Lookup.Unconfigured, source.record("jetnews"))
    assertEquals(false, source.isConfigured("confetti-mobile"))
  }

  @Test
  fun `the operator's file wins over the served catalog's`() {
    val named = record("named", "named")
    val served = record("g1", "served")
    val source = ComponentRecordSource(mapOf("confetti-mobile" to named)) { served }

    val found = assertIs<ComponentRecordSource.Lookup.Found>(source.record("confetti-mobile"))
    assertEquals("named", found.record.module)
    assertEquals(true, source.isConfigured("confetti-mobile"))
  }

  @Test
  fun `a served record is resolved per call, so a refreshed generation is the one read`() {
    var current = record("g1", "first")
    val source = ComponentRecordSource(emptyMap()) { current }

    assertEquals(
      "first",
      assertIs<ComponentRecordSource.Lookup.Found>(source.record("m3-catalog")).record.module,
    )
    current = record("g2", "second")
    assertEquals(
      "second",
      assertIs<ComponentRecordSource.Lookup.Found>(source.record("m3-catalog")).record.module,
    )
  }
}
