package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import java.io.File
import kotlin.test.assertIs

/**
 * The component record a host actually exports `m3-catalog` from.
 *
 * ## Why this is not `Json.decodeFromString(File(RECORD).readText())`
 *
 * It used to be, in eleven places, and that stopped being the whole record when the builder's own
 * `layout/`, `shape/` and `asset/` components moved into `compose-foundation-components-v1.json`. A
 * design is mostly `layout/column` and `asset/image`; `m3-catalog-components-v1.json` no longer
 * names one, and a test decoding it alone would exercise an export that refuses every layout node —
 * green or red for reasons no host shares.
 *
 * ## Why through `ComponentRecordSource` rather than a second merge here
 *
 * Because the merge is production's and has one rule: the catalog's own entry wins, by canonical id
 * AND by component id. A test-local `plus` would be a second opinion about that, and the day the
 * two disagreed the tests would be the ones saying the wrong thing. This asks the same class the
 * server asks, with the same packaged foundation record on the classpath, so "the record the export
 * reads" means one thing in this repository.
 *
 * It also means these tests cover the union itself: a foundation record that stopped being packaged
 * would fail here as well as in `ComponentRecordSourceFoundationTest`.
 */
internal object ExportRecords {

  private const val RELATIVE = "docs/design/fixtures/ui-builder/m3-catalog-components-v1.json"

  private const val CATALOG = "m3-catalog"

  /**
   * The authored `m3-catalog` record's file, found by walking up rather than by a fixed `../`.
   *
   * Both working directories are in use in this suite — most tests resolve `../docs/…` from the
   * module directory and `ScreenGeneratorComposeExportExecutorTest` walks up because it "is found
   * whether the test runs from the module directory or the root". Walking up satisfies both, so
   * which one Gradle happens to hand a test stops being something each one has to know.
   */
  fun m3CatalogFile(): File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, RELATIVE) }
      .first { it.isFile }

  /** `m3-catalog`'s record with the foundation vocabulary unioned in, exactly as a host has it. */
  fun m3Catalog(): ComponentRecordFile {
    val file = m3CatalogFile()
    val lookup = ComponentRecordSource(mapOf(CATALOG to file)).record(CATALOG)
    return assertIs<ComponentRecordSource.Lookup.Found>(
        lookup,
        "the m3-catalog record did not load from $file",
      )
      .record
  }
}
