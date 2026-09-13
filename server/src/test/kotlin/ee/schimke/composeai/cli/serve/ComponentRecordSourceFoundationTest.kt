package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The builder's own vocabulary reaches every record, and belongs to no catalog.
 *
 * ## What this is about
 *
 * A design is mostly `layout/column`, `layout/box` and `asset/image`. No catalog's record carries
 * them — `androidx.compose.foundation` publishes one of each rather than one per design system, and
 * m3-catalog declares no builtins at all, on the stated grounds that doing so "would be this
 * catalog claiming to own the builder's own vocabulary". `composeFoundationCatalog` answers that on
 * the CAPABILITY side; this is the record side of the same ownership.
 *
 * They lived inside `m3-catalog-components-v1.json`, which is why the m3 record was the only one an
 * export could use for a layout node, and why that file had to be passed to a host whose catalog
 * has nothing to do with Material 3. They are their own record now and the m3 one carries the
 * twenty-six Material 3 components alone — which is what lets a catalog's own DISCOVERED record
 * become the record it exports from, the discovered one being strictly richer than the authored
 * subset it stands in for, without taking `Column` away from the exporter.
 *
 * ## What is asserted, and why each arm is here
 *
 * The packaged arm pins the staging: the resource is copied into the jar by `stageFoundationRecord`
 * and read back by the same path here, so dropping or renaming either end fails rather than
 * silently leaving every layout node without a record. It also pins the SPLIT RULE — every
 * component in that file answers to a `layout/`, `shape/` or `asset/` id — which is what keeps the
 * file from quietly becoming a second place to put Material 3 components.
 *
 * The union arms use a record injected by the test rather than the packaged one, so they are about
 * the merge and not about today's contents of either file.
 */
class ComponentRecordSourceFoundationTest {

  private val json = Json { ignoreUnknownKeys = true }
  private val root =
    Files.createTempDirectory("foundation-records").toFile().also { it.deleteOnExit() }

  private fun file(name: String, body: String): File =
    File(root, "$name/components.json").also {
      it.parentFile.mkdirs()
      it.writeText(body)
    }

  private fun component(canonicalId: String, componentId: String, name: String) =
    """
    {"canonicalId":"$canonicalId","componentIds":["$componentId"],
     "symbol":{"jvmOwner":"o.Kt","callable":"o.$name","name":"$name","origin":"LIBRARY"},
     "parameters":[],"slots":[],"code":{"imports":[]}}
    """
      .trimIndent()

  private fun recordFile(module: String, vararg components: String) =
    """{"schemaVersion":2,"module":"$module","variant":"main","components":[${components.joinToString(",")}]}"""

  private fun foundation(vararg components: String): ComponentRecordFile =
    json.decodeFromString(recordFile("compose-foundation", *components))

  @Test
  fun `the foundation record is packaged, and carries only the builder's own namespaces`() {
    val text =
      ComponentRecordSource::class
        .java
        .getResourceAsStream("/ui-builder/compose-foundation-components-v1.json")
        ?.use { it.readBytes().decodeToString() }
    assertNotNull(
      text,
      "the foundation record is not on the classpath — `stageFoundationRecord` no longer stages " +
        "it, or the resource path moved, and every layout node would export with no record",
    )
    val record = json.decodeFromString<ComponentRecordFile>(text)
    assertTrue(record.components.isNotEmpty(), "the packaged foundation record declares nothing")
    val foreign =
      record.components
        .filterNot { component ->
          component.componentIds.isNotEmpty() &&
            component.componentIds.all { id ->
              id.startsWith("layout/") || id.startsWith("shape/") || id.startsWith("asset/")
            }
        }
        .map { it.canonicalId }
    assertEquals(
      emptyList(),
      foreign,
      "a component answering to something outside layout/, shape/ and asset/ is in the " +
        "foundation record — it belongs to a catalog, and putting it here hands it to every " +
        "catalog the host serves",
    )
  }

  @Test
  fun `a catalog's record gains the foundation components it does not carry`() {
    val named =
      file("named", recordFile("m3-catalog", component("m3/x.Kt.Button", "m3/button", "Button")))
    val source =
      ComponentRecordSource(
        mapOf("m3-catalog" to named),
        foundation = foundation(component("cf/l.Kt.Column", "layout/column", "Column")),
      )

    val found = assertIs<ComponentRecordSource.Lookup.Found>(source.record("m3-catalog"))
    assertEquals("m3-catalog", found.record.module, "the header stays the catalog's")
    assertEquals(
      listOf("m3/x.Kt.Button", "cf/l.Kt.Column"),
      found.record.components.map { it.canonicalId },
      "the catalog's own components come first, with the foundation's appended",
    )
  }

  @Test
  fun `a catalog that claims a foundation id keeps its own component, under any canonical id`() {
    // The rule that matters, and the reason it is asked of the component ID rather than only the
    // canonical one: two entries claiming `layout/column` are two components competing for one
    // builder id, which `PublishedUiBuilderCatalog` resolves by record order and reports as a
    // collision. The packaged m3-catalog record carries its own copies of these eight under
    // `m3-catalog/` canonical ids, so a canonical-id-only rule would add all eight again and
    // collide every one of them.
    val mine = component("m3-catalog/l.Kt.Column", "layout/column", "CatalogColumn")
    val named = file("named", recordFile("m3-catalog", mine))
    val source =
      ComponentRecordSource(
        mapOf("m3-catalog" to named),
        foundation =
          foundation(component("compose-foundation/l.Kt.Column", "layout/column", "Foundation")),
      )

    val found = assertIs<ComponentRecordSource.Lookup.Found>(source.record("m3-catalog"))
    assertEquals(1, found.record.components.size, "the foundation entry was added alongside")
    assertEquals("CatalogColumn", found.record.components.single().symbol.name)
  }

  @Test
  fun `a catalog with no record does not acquire one`() {
    // `remote-m3` is the case. It has no record deliberately — Remote Compose is kept out of the
    // Compose exporter by design, and the capability is advertised per catalog — so a union that
    // manufactured one from the foundation alone would advertise an export that cannot work.
    val source =
      ComponentRecordSource(
        emptyMap(),
        foundation = foundation(component("cf/l.Kt.Column", "layout/column", "Column")),
      )

    assertEquals(ComponentRecordSource.Lookup.Unconfigured, source.record("remote-m3"))
  }
}
