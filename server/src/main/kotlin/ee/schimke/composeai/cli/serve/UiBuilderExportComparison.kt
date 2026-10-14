package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.uibuilder.export.CatalogExportRouting
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.PublishedUiBuilderCatalog
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Exports the same designs through two published catalogs and reports where the Kotlin differs: the
 * A/B check for a pilot (say `m3-catalog-typed-pilot`, exporting through typed projections) against
 * the production catalog it would replace (`m3-catalog`).
 *
 * Each side is a delivery branch checkout (`catalog.json`, `ui-builder.json`, `components.json`),
 * composed and exported exactly as a box serving it would: [PublishedUiBuilderCatalog.compose] over
 * the record read through [ComponentRecordSource] (so the builder's foundation is joined the same
 * way), the catalog owned so its own `composeSourceExport` routes it, and
 * [ScreenGeneratorComposeExportExecutor] writing the source. A design is re-pinned to each side's
 * catalog id, since a pilot serves the same screens under another id.
 *
 * The provenance header names the catalog pin and differs on every export, so it is dropped before
 * comparing; everything after it is compared line for line.
 */
internal object UiBuilderExportComparison {

  /** One catalog, ready to export through. */
  class Side
  private constructor(
    val catalogId: String,
    val catalog: CatalogCapabilityV1,
    private val executor: ScreenGeneratorComposeExportExecutor,
  ) {

    /** [document] exported through this side, re-pinned to [catalogId]. */
    fun export(document: DesignDocumentV1): Outcome {
      val pinned = document.copy(catalogPin = document.catalogPin.copy(systemId = catalogId))
      val artifact =
        executor.export(
          RevisionPinnedUiBuilderExport(
            actor = AuthenticatedUiBuilderActor(ACTOR),
            designId = pinned.id,
            revision = pinned.revision,
            documentHash = "export-comparison",
            document = pinned,
            catalog = catalog,
            format = ExportFormatV1.COMPOSE,
          )
        )
      val errors = artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
      return if (errors.isEmpty()) Outcome.Emitted(withoutProvenance(artifact.content))
      else Outcome.Refused(errors.map { "${it.code}: ${it.message}" })
    }

    companion object {
      /**
       * The catalog a delivery branch checkout at [branch] serves, or why it cannot be read. The
       * file names come from its `catalog.json`, as the server reads them.
       */
      fun load(branch: File): Side {
        val manifest =
          File(branch, "catalog.json")
            .takeIf { it.isFile }
            ?.let { JSON.parseToJsonElement(it.readText()).jsonObject }
        fun named(key: String, default: String): File =
          File(branch, (manifest?.get(key) as? JsonPrimitive)?.contentOrNull ?: default)
        val policy = named("uiBuilderFile", "ui-builder.json")
        require(policy.isFile) { "${branch.path} publishes no ${policy.name}" }
        val recordFile = named("componentsFile", "components.json")
        val header = JSON.parseToJsonElement(policy.readText()).jsonObject["catalog"] as? JsonObject
        val catalogId = (header?.get("id") as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        require(catalogId.isNotEmpty()) { "${policy.path} names no catalog id" }
        val records =
          ComponentRecordSource(
            if (recordFile.isFile) mapOf(catalogId to recordFile) else emptyMap()
          )
        val record = (records.record(catalogId) as? ComponentRecordSource.Lookup.Found)?.record
        val composed =
          when (
            val result = PublishedUiBuilderCatalog.compose(policy.readText(), record, EXPORTS)
          ) {
            is PublishedUiBuilderCatalog.Result.Composed -> result
            is PublishedUiBuilderCatalog.Result.Unusable ->
              error("${policy.path} does not compose: ${result.reason}")
          }
        // Owned, as preview.coo.ee serves every catalog this compares: the catalog's own
        // declaration routes it, never its id.
        val platform =
          CatalogExportRouting.recordFreePlatform(
            CatalogExportRouting.route(composed.catalog, CatalogOwnership.of(setOf(catalogId)))
          ) ?: UiBuilderCatalogPlatform.from(composed.catalog.statusSemantics)
        val published: Map<String, ComponentRecord> = composed.records
        val executor =
          ScreenGeneratorComposeExportExecutor(
            records::record,
            publishedComponents = { if (it == catalogId) published else emptyMap() },
            catalogPlatform = {
              if (it == catalogId) platform else UiBuilderCatalogPlatform.DEFAULT
            },
          )
        return Side(catalogId, composed.catalog, executor)
      }
    }
  }

  /** What one side wrote for one design. */
  sealed interface Outcome {
    data class Emitted(val source: String) : Outcome

    data class Refused(val reasons: List<String>) : Outcome
  }

  /** How the two sides compare on one design. */
  enum class Verdict(val label: String) {
    IDENTICAL("identical"),
    DIFFERS("differs"),
    ONLY_A("only A exports"),
    ONLY_B("only B exports"),
    BOTH_REFUSED("both refuse"),
  }

  data class Row(val design: String, val a: Outcome, val b: Outcome) {
    val verdict: Verdict
      get() =
        when {
          a is Outcome.Emitted && b is Outcome.Emitted ->
            if (a.source == b.source) Verdict.IDENTICAL else Verdict.DIFFERS
          a is Outcome.Emitted -> Verdict.ONLY_A
          b is Outcome.Emitted -> Verdict.ONLY_B
          else -> Verdict.BOTH_REFUSED
        }
  }

  /** Every design in [designs] (`.json` or `.uid` documents) through [a] and [b]. */
  fun compare(a: Side, b: Side, designs: List<File>): List<Row> = designs.map { file ->
    val document = JSON.decodeFromString(DesignDocumentV1.serializer(), file.readText())
    Row(file.name, a.export(document), b.export(document))
  }

  /** Whether [file] is a UI-builder design document, by its `schema`. */
  fun isDesignDocument(file: File): Boolean = runCatching {
    ((JSON.parseToJsonElement(file.readText()) as? JsonObject)?.get("schema") as? JsonPrimitive)
      ?.contentOrNull
      ?.startsWith(DOCUMENT_SCHEMA_PREFIX) == true
  }
    .getOrDefault(false)

  /** The provenance comment block the executor prepends, which names the pin and always differs. */
  fun withoutProvenance(source: String): String {
    val lines = source.lines()
    val header = lines.takeWhile { it.startsWith("//") }
    return if (header.isNotEmpty() && lines.getOrNull(header.size)?.isBlank() == true)
      lines.drop(header.size + 1).joinToString("\n")
    else source
  }

  /** A unified-style line diff of [a] against [b] (longest common subsequence; files are small). */
  fun lineDiff(a: String, b: String, context: Int = 3): String {
    val x = a.lines()
    val y = b.lines()
    val lcs = Array(x.size + 1) { IntArray(y.size + 1) }
    for (i in x.indices.reversed()) for (j in y.indices.reversed()) {
      lcs[i][j] = if (x[i] == y[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
    }
    val ops = mutableListOf<Pair<Char, String>>()
    var i = 0
    var j = 0
    while (i < x.size || j < y.size) {
      when {
        i < x.size && j < y.size && x[i] == y[j] -> ops += ' ' to x[i++].also { j++ }
        // Removals before additions, so a changed line reads as `-old` then `+new`.
        i < x.size && (j == y.size || lcs[i + 1][j] >= lcs[i][j + 1]) -> ops += '-' to x[i++]
        else -> ops += '+' to y[j++]
      }
    }
    val keep = BooleanArray(ops.size)
    ops.forEachIndexed { k, (op, _) ->
      if (op != ' ')
        for (n in maxOf(0, k - context)..minOf(ops.lastIndex, k + context)) keep[n] = true
    }
    return buildString {
      var skipped = false
      ops.forEachIndexed { k, (op, line) ->
        if (keep[k]) {
          if (skipped) appendLine("@@")
          skipped = false
          append(op).append(line).append('\n')
        } else skipped = true
      }
    }
  }

  /** The Markdown report: a summary, then each design that is not identical. */
  fun report(title: String, a: Side, b: Side, rows: List<Row>): String = buildString {
    appendLine("# $title")
    appendLine()
    appendLine("A = `${a.catalogId}`, B = `${b.catalogId}`; ${rows.size} design(s).")
    appendLine()
    appendLine("| Verdict | Designs |")
    appendLine("| --- | ---: |")
    for (verdict in Verdict.entries) {
      appendLine("| ${verdict.label} | ${rows.count { it.verdict == verdict }} |")
    }
    for ((side, label) in listOf("A" to a.catalogId, "B" to b.catalogId)) {
      val reasons = refusals(rows, side)
      if (reasons.isEmpty()) continue
      appendLine()
      appendLine("Why $side (`$label`) refuses, by design count:")
      appendLine()
      appendLine("| Designs | Reason |")
      appendLine("| ---: | --- |")
      reasons.forEach { (reason, count) ->
        appendLine("| $count | ${reason.replace("|", "\\|")} |")
      }
    }
    appendLine()
    appendLine("| Design | Verdict |")
    appendLine("| --- | --- |")
    for (row in rows) appendLine("| `${row.design}` | ${row.verdict.label} |")
    for (row in rows.filter { it.verdict != Verdict.IDENTICAL }) {
      appendLine()
      appendLine("## `${row.design}`: ${row.verdict.label}")
      appendLine()
      val a = row.a
      val b = row.b
      if (a is Outcome.Emitted && b is Outcome.Emitted) {
        appendLine("```diff")
        append(lineDiff(a.source, b.source))
        appendLine("```")
      } else {
        for ((side, outcome) in listOf("A" to a, "B" to b)) {
          if (outcome is Outcome.Refused) {
            appendLine("$side refuses:")
            appendLine()
            outcome.reasons.forEach { appendLine("- ${it.lines().joinToString(" ")}") }
            appendLine()
          }
        }
      }
    }
  }

  /**
   * Each refusal reason [side] (`A` or `B`) gives, with node ids elided so one cause across many
   * designs reads as one line, and the number of designs it refuses; most common first.
   */
  fun refusals(rows: List<Row>, side: String): List<Pair<String, Int>> =
    rows
      .flatMap { row ->
        val outcome = if (side == "A") row.a else row.b
        (outcome as? Outcome.Refused)
          ?.reasons
          ?.map { it.lines().joinToString(" ").replace(NODE_ID, "node `…`") }
          ?.distinct()
          .orEmpty()
      }
      .groupingBy { it }
      .eachCount()
      .entries
      .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
      .map { it.key to it.value }

  /** Writes the report and each side's source under [out]. */
  fun write(out: File, title: String, a: Side, b: Side, rows: List<Row>) {
    out.mkdirs()
    for ((name, side) in listOf("a" to { r: Row -> r.a }, "b" to { r: Row -> r.b })) {
      val dir = File(out, name).apply { mkdirs() }
      for (row in rows) {
        val text =
          when (val outcome = side(row)) {
            is Outcome.Emitted -> outcome.source
            is Outcome.Refused -> outcome.reasons.joinToString("\n") { "// $it" } + "\n"
          }
        File(dir, row.design.substringBeforeLast('.') + ".kt").writeText(text)
      }
    }
    File(out, "report.md").writeText(report(title, a, b, rows))
    File(out, "summary.json")
      .writeText(
        buildString {
          append("{\"a\":\"${a.catalogId}\",\"b\":\"${b.catalogId}\",\"designs\":{")
          append(rows.joinToString(",") { "\"${it.design}\":\"${it.verdict.name}\"" })
          append("}}\n")
        }
      )
  }

  /**
   * `--a <branch> --b <branch> --designs <dir>[,<dir>…] --out <dir> [--title <title>]`; exits
   * nonzero only when an input cannot be read, never because the sides differ.
   */
  @JvmStatic
  fun main(args: Array<String>) {
    fun flag(name: String): String? =
      args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val usage =
      "usage: --a <branch> --b <branch> --designs <dir>[,<dir>...] --out <dir> [--title <title>]"
    val a = Side.load(File(requireNotNull(flag("--a")) { usage }))
    val b = Side.load(File(requireNotNull(flag("--b")) { usage }))
    val designs =
      requireNotNull(flag("--designs")) { usage }
        .split(',')
        .filter(String::isNotBlank)
        .flatMap { dir ->
          File(dir).listFiles { f -> f.isFile && f.extension in setOf("json", "uid") }?.toList()
            ?: error("$dir is not a directory of designs")
        }
        // A designs directory may carry other JSON beside the documents (an app's `index.json`
        // manifest); only a design document is compared.
        .filter(::isDesignDocument)
        .sortedBy { it.name }
    val out = File(requireNotNull(flag("--out")) { usage })
    val title = flag("--title") ?: "UI-builder export A/B: ${a.catalogId} vs ${b.catalogId}"
    val rows = compare(a, b, designs)
    write(out, title, a, b, rows)
    println(
      "$title: " +
        Verdict.entries.joinToString(", ") { v -> "${rows.count { it.verdict == v }} ${v.label}" }
    )
    // The reasons, in the log too: the report's artifact is not always where a reader is.
    for ((side, id) in listOf("A" to a.catalogId, "B" to b.catalogId)) {
      refusals(rows, side).take(LOGGED_REASONS).forEach { (reason, count) ->
        println("  $side ($id) refuses $count design(s): $reason")
      }
    }
  }

  private const val ACTOR = "export-comparison"

  private const val DOCUMENT_SCHEMA_PREFIX = "compose-ui-builder-document/"

  private const val LOGGED_REASONS = 25

  private val NODE_ID = Regex("node `[^`]*`")

  private val JSON = Json { ignoreUnknownKeys = true }

  private val EXPORTS: ExportCapabilitiesV1 =
    ExportCapabilitiesV1.Builder().also { it.composeCode = true }.build()
}
