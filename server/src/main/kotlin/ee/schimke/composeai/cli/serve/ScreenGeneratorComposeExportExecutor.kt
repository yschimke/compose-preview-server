package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.security.MessageDigest

/**
 * Compose-source export driven by the **discovered component record** rather than by a guess.
 *
 * ## What it replaces, and why the replacement is not a refactor
 *
 * The executor this stands in for rendered every export with a `WARNING
 * ALMOST_COMPILING_PROJECTION` attached, whose message read: "Catalog symbols and the complete
 * typed document are preserved; project-specific state and event adapters may require edits." That
 * is an honest description of what it did and a useless artifact to receive. "May require edits" is
 * not a diagnostic a caller can act on: it does not say which node, which property, or whether
 * *this* export is one of the ones that compiles.
 *
 * This one answers that question instead of restating it. `ScreenGenerator` emits a call site only
 * where the component record proves one can be written — the component is public, top-level,
 * importable, not an overload collision, its signature actually recovered — and refuses by name
 * otherwise. [ScreenDocumentProjection] does the same for values. So an export is either source
 * with **no** warning about its own compilability, or an `ERROR` per unexpressible thing, naming
 * each one.
 *
 * That is a deliberate narrowing: a document this refuses is one the old executor would have handed
 * back as almost-Kotlin. The refusal list is the thing worth having, because each line in it is a
 * feature request with a node id attached.
 *
 * ## The record has to come from somewhere
 *
 * [components] is a supplier rather than a value because the record is a *build output* — a
 * bundle's `components.json` — and the server may be serving a catalog that has none. A null supply
 * is a refusal with its own code, not an empty catalog: an empty catalog would refuse every node
 * with "no component in this catalog", which reads like a stale document rather than like a host
 * that was never given the record.
 */
class ScreenGeneratorComposeExportExecutor(
  private val components: (catalogSystemId: String) -> ComponentRecordFile?,
  private val packageName: String = "generated.screen",
) : UiBuilderExportExecutor {

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    require(request.format == ExportFormatV1.COMPOSE) {
      "${request.format} export is unsupported by the Compose source executor"
    }
    // Kept from the executor this replaces. They are not defensive noise: the service pins a
    // revision before calling, so a mismatch here means the pinning is broken and the artifact
    // would be attributed to a revision it was not built from.
    require(request.revision == request.document.revision) { "export revision/document mismatch" }
    require(request.document.id == request.designId) { "export design/document mismatch" }

    val catalogSystemId = request.document.catalogPin.systemId
    val record =
      components(catalogSystemId)
        ?: return refused(
          NO_COMPONENT_RECORD,
          listOf(
            "this host has no discovered component record for catalog `$catalogSystemId`, so no " +
              "call site can be proven; run a preview bundle for that catalog's module and pass " +
              "it as `--ui-builder-components $catalogSystemId=<components.json>`"
          ),
        )
    val projection = ScreenDocumentProjection.project(request.document)
    val document =
      when (projection) {
        is ScreenDocumentProjection.Outcome.Projected -> projection.document
        is ScreenDocumentProjection.Outcome.Refused ->
          return refused(UNEXPRESSIBLE_DOCUMENT, projection.reasons)
      }
    return when (val generated = ScreenGenerator.generate(document, record, packageName)) {
      is ScreenGenerator.Result.Refused -> refused(UNPROVEN_CALL_SITE, generated.reasons)
      is ScreenGenerator.Result.Emitted ->
        ExportArtifactV1(
          format = ExportFormatV1.COMPOSE,
          mediaType = "text/x-kotlin; charset=utf-8",
          encoding = ExportEncodingV1.UTF8,
          content = generated.source,
          contentDigest = generated.source.sha256(),
          // No diagnostic at all on the success path, and that is the whole change. An artifact
          // with an empty diagnostic list says "this is the screen you designed"; the one this
          // replaces could only ever say "this is nearly it".
          diagnostics = emptyList(),
        )
    }
  }

  /**
   * A refusal as an artifact, because the port's return type has no failure case.
   *
   * The content is the reasons rather than empty, so a caller that only shows the body still shows
   * something actionable — and it is a Kotlin comment block, so a caller that pipes an export into
   * a file gets something that at least parses as the language it asked for. The digest still
   * covers the content, so two identical refusals are identical artifacts.
   */
  private fun refused(code: String, reasons: List<String>): ExportArtifactV1 {
    val content = reasons.joinToString("\n") { "// $it" } + "\n"
    return ExportArtifactV1(
      format = ExportFormatV1.COMPOSE,
      mediaType = "text/x-kotlin; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = content,
      contentDigest = content.sha256(),
      diagnostics =
        reasons.map {
          ExportDiagnosticV1(severity = DiagnosticSeverityV1.ERROR, code = code, message = it)
        },
    )
  }

  private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).joinToString("") {
      "%02x".format(it)
    }

  companion object {
    /** The host was never given a component record for this catalog. Not the document's fault. */
    const val NO_COMPONENT_RECORD = "NO_COMPONENT_RECORD"

    /** The document holds something no Kotlin value expresses — state, an event, an asset. */
    const val UNEXPRESSIBLE_DOCUMENT = "UNEXPRESSIBLE_DOCUMENT"

    /** The document is expressible; the catalog cannot prove one of its call sites. */
    const val UNPROVEN_CALL_SITE = "UNPROVEN_CALL_SITE"
  }
}
