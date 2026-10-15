package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json

/** Catalog data served by the Remote and Wear deployments after their Kotlin generators retired. */
internal object PublishedCutoverCatalogs {
  private val json = Json { ignoreUnknownKeys = true }
  private val fixtures = File("../docs/design/fixtures/ui-builder")

  private fun compose(policy: String, record: String): CatalogCapabilityV1 {
    val exports =
      ExportCapabilitiesV1.Builder()
        .also {
          it.composeCode = true
          it.svg = false
          it.png = false
        }
        .build()
    val result =
      PublishedUiBuilderCatalog.compose(
        policy,
        json.decodeFromString<ComponentRecordFile>(record),
        exports,
      )
    check(result is PublishedUiBuilderCatalog.Result.Composed) { "Published catalog: $result" }
    return result.catalog
  }

  val remote: CatalogCapabilityV1 by lazy {
    compose(
      fixtures.resolve("remote-m3-published-v1.json").readText(),
      fixtures.resolve("remote-m3-record-v1.json").readText(),
    )
  }

  val wear: CatalogCapabilityV1 by lazy {
    fun publishedFile(name: String): String =
      checkNotNull(javaClass.getResourceAsStream("/catalog-cutover/wear-m3-catalog/$name")).use {
        GZIPInputStream(it).bufferedReader().readText()
      }
    compose(publishedFile("ui-builder.json.gz"), publishedFile("components.json.gz"))
  }

  val remoteAndWear: Map<String, CatalogCapabilityV1> by lazy {
    mapOf("remote-m3" to remote, "wear-m3" to wear)
  }
}
