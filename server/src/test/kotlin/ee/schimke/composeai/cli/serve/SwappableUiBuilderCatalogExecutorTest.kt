package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A republished catalog replaces the service's catalogs in place (#1054): the pin the delivery
 * branch now names resolves, and the one it replaced no longer does, without a restart.
 */
class SwappableUiBuilderCatalogExecutorTest {
  private fun catalog(runtime: String) =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder("remote", "source", "remote-m3", "candidate", "candidate")
          .build(),
        emptyList(),
      )
      .build() to CatalogReferenceV1("remote-m3", "sha256:policy", "candidate", runtime)

  private fun executor(entry: Pair<CatalogCapabilityV1, CatalogReferenceV1>) =
    object : UiBuilderCatalogExecutor {
      override fun listCatalogs(): List<CatalogCapabilityV1> = listOf(entry.first)

      override fun resolve(reference: CatalogReferenceV1): CatalogCapabilityV1? =
        entry.first.takeIf { reference == entry.second }

      override fun validate(
        document: DesignDocumentV1,
        catalog: CatalogCapabilityV1,
      ): UiBuilderCatalogIssue? = null
    }

  @Test
  fun `a swap is what every later call sees`() {
    val old = catalog("remote-m3-p3-old")
    val new = catalog("remote-m3-p3-new")
    val catalogs = SwappableUiBuilderCatalogExecutor(executor(old))
    assertSame(old.first, catalogs.resolve(old.second))
    assertNull(catalogs.resolve(new.second))

    catalogs.swap(executor(new))

    assertSame(new.first, catalogs.resolve(new.second))
    assertNull(catalogs.resolve(old.second), "the retired runtime is no longer served")
    assertEquals(listOf(new.first), catalogs.listCatalogs())
  }
}
