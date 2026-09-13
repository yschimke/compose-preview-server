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
 * They live inside `m3-catalog-components-v1.json` today, which is why the m3 record is the only
 * one an export can use for a layout node, and why that file has to be passed to a host whose
 * catalog has nothing to do with Material 3. Lifting them into a record of their own is what will
 * let a catalog's own DISCOVERED record be the record — the discovered one is strictly richer than
 * the authored subset it stands in for — without taking `Column` away from the exporter.
 *
 * This step packages the foundation record and unions it; it does NOT yet remove the copies from
 * the m3 record, because sixteen call sites across four modules read that file directly and each
 * would have to learn about the second one. So m3-catalog is unchanged by construction — it already
 * claims every `layout/`, `shape/` and `asset/` id, so every foundation entry is skipped for it —
 * and what changes is every OTHER catalog, which had no record for a layout node at all. The last
 * test here pins the two copies together so they cannot drift while the duplication lasts.
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
  fun `the packaged copies of these eight agree with the m3 record's, field for field`() {
    // The duplication is temporary and this is what stops it rotting. Until the sixteen direct
    // readers of `m3-catalog-components-v1.json` learn about the second file, both carry these
    // eight, and an edit to one of them would change what the server exports without changing what
    // the browser panel judges — a divergence with no symptom until somebody's generated Kotlin
    // stops compiling. Compared on everything but `canonicalId`, which is the one field that is
    // deliberately different: the record they belong to is not m3-catalog's.
    val fixtures = File("../docs/design/fixtures/ui-builder")
    val m3 =
      json.decodeFromString<ComponentRecordFile>(
        File(fixtures, "m3-catalog-components-v1.json").readText()
      )
    val packaged =
      json.decodeFromString<ComponentRecordFile>(
        File(fixtures, "compose-foundation-components-v1.json").readText()
      )
    val byLeaf = m3.components.associateBy { it.canonicalId.substringAfter('/') }
    assertTrue(packaged.components.isNotEmpty(), "the foundation fixture declares nothing")
    for (component in packaged.components) {
      val leaf = component.canonicalId.substringAfter('/')
      val twin =
        assertNotNull(
          byLeaf[leaf],
          "$leaf is in the foundation record and no longer in the m3 one — if the copies have " +
            "been removed, this test has done its job and goes with them",
        )
      assertEquals(
        twin.copy(canonicalId = ""),
        component.copy(canonicalId = ""),
        "the two copies of $leaf have drifted",
      )
    }
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
