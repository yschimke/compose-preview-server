package ee.schimke.composeai.uibuilder

import java.io.File
import kotlin.test.*
import kotlinx.serialization.json.JsonPrimitive

/** The player proof reads exact output from the shared production exporter. */
class RemoteDocumentJsonPlayerFixturesTest {
  @Test
  fun `write mutable string JSON for the real player proof`() {
    val output = File("build/remote-json-strings").apply { mkdirs() }
    listOf("Ready", "Changed", "@second", "$" + "second", "", "Résumé 👋").forEachIndexed {
      index,
      value ->
      val result =
        assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
          RemoteDocumentJsonExporter.export(remoteJsonStringFixture(value))
        )
      File(output, "$index.json").writeText(result.source)
      File(output, "$index.expected.txt").writeText(value)
    }
  }

  @Test
  fun `write production JSON for the real player proof`() {
    val scenarios =
      mapOf(
        "Integer" to listOf(JsonPrimitive(10), JsonPrimitive(20)),
        "AdjacentInteger" to listOf(JsonPrimitive(16777216), JsonPrimitive(16777217)),
        "Extremes" to listOf(JsonPrimitive(Int.MIN_VALUE), JsonPrimitive(Int.MAX_VALUE)),
        "Boolean" to listOf(JsonPrimitive(false), JsonPrimitive(true)),
        "ManyCases" to (10..130 step 10).map(::JsonPrimitive),
      )
    val output = File("build/remote-json-selection").apply { mkdirs() }
    scenarios.forEach { (name, values) ->
      for (density in listOf(1, 2)) {
        val result =
          assertIs<RemoteDocumentJsonExporter.Result.Emitted>(
            RemoteDocumentJsonExporter.export(remoteJsonSelectionFixture(values, density))
          )
        File(output, "$name-$density.json").writeText(result.source)
      }
    }
  }
}
