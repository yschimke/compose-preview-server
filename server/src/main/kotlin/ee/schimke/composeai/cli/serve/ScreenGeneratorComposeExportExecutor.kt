package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA
import ee.schimke.composeai.discovery.COMPONENT_RECORD_SCHEMA_VERSION
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.uibuilder.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
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
internal class ScreenGeneratorComposeExportExecutor(
  private val components: (catalogSystemId: String) -> ComponentRecordSource.Lookup,
  /**
   * `generated.uibuilder`, matching the exporter this replaces and the package
   * `UiBuilderGeneratedPreviewAdapter` imports its composable from. A different default would
   * compile on its own and fail the moment a production artifact was handed to that lane, and the
   * golden test would not have caught it — it passes a package explicitly.
   */
  private val packageName: String = ScreenExportGate.PACKAGE_NAME,
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

    // A record-free design — a Wear widget, a Wear screen — generates through its own emitter, and
    // is asked first because the record-driven generator below can only ever refuse it: `remote-m3`
    // and `wear-m3` deliberately have no component record. The Code pane already made this call;
    // until now the export did not, so a widget's source could be read in the browser and never
    // saved. Same `RecordFreeExport` entry point as the pane, so the two cannot disagree.
    //
    // With [packageName], where the pane passes none. A pane is a snippet to paste into a file that
    // already has one; an artifact somebody writes to disk is that file.
    RecordFreeExport.generate(request.document, packageName)?.let { recordFree ->
      return when (recordFree) {
        is RecordFreeExport.Generated.Emitted -> emitted(provenance(request) + recordFree.source)
        // The document's own fault and named node by node, which is what this code means. There is
        // no record involved to blame and no call site left unproven — the emitter reached a node
        // it cannot write.
        is RecordFreeExport.Generated.Refused -> refused(UNEXPRESSIBLE_DOCUMENT, recordFree.reasons)
      }
    }

    return when (val generated = generate(request.document)) {
      is Generated.Refused -> refused(generated.code, generated.reasons)
      is Generated.Emitted -> emitted(provenance(request) + generated.source)
    }
  }

  /**
   * A generated file as an artifact.
   *
   * No diagnostic at all on the success path, and that is the whole point of this executor. An
   * artifact with an empty diagnostic list says "this is the screen you designed"; the one it
   * replaced could only ever say "this is nearly it".
   */
  private fun emitted(source: String): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.COMPOSE,
      mediaType = "text/x-kotlin; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = source,
      contentDigest = source.sha256(),
      diagnostics = emptyList(),
    )

  /** The Kotlin for a document, or why there is none. */
  internal sealed interface Generated {
    data class Emitted(val source: String, val screenName: String) : Generated

    data class Refused(val code: String, val reasons: List<String>) : Generated
  }

  /**
   * The generator run itself, without the provenance header an export artifact wants.
   *
   * Split out of [export] so the **native preview** lane can ask the same question with [tagNodes]
   * on and get the same refusals: a design that cannot be exported cannot be rendered natively
   * either, and hearing about it twice in two vocabularies is how two surfaces start disagreeing
   * about one document.
   */
  internal fun generate(
    document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1,
    tagNodes: Boolean = false,
  ): Generated {
    // A record-free design never reaches `ScreenGenerator` below — `remote-m3` and `wear-m3` have
    // no component record and the record-driven generator can only refuse them — so the emitter
    // answers first, exactly as it does in `export`. What differs is that this lane then *compiles*
    // the source, so the two record-free emitters part company here.
    //
    // A **Wear screen** is ordinary Wear Compose: `ScreenScaffold`, `TitleCard`, `Text`. Given a
    // catalog bundle carrying `androidx.wear.compose:compose-material3` it compiles and renders on
    // the Android/Robolectric daemon, and that render is not a nicety — the browser's Wasm canvas
    // cannot link an Android AAR, so it is the *only* honest picture a Wear design has
    // (`docs/design/UI_BUILDER_WEAR_SCREEN.md`). This lane used to refuse it along with the widget,
    // which left the one catalog that most needs a native render as the one catalog that could not
    // ask for one.
    //
    // A **Wear widget** still refuses. Its source declares a `WearWidgetDocument` of Remote
    // Compose — played by a player, not composed — so there is no `@Preview` for this lane to
    // discover and no frame at the end of compiling it.
    if (RecordFreeExport.applies(document)) {
      if (!RecordFreeExport.composeCompilable(document)) {
        return Generated.Refused(
          RECORD_FREE_DESIGN,
          listOf(
            "this design generates a Remote Compose document rather than Jetpack Compose, so " +
              "there is no `@Preview` for the native preview lane to compile and render; export " +
              "it instead, and preview it on the canvas"
          ),
        )
      }
      return when (val recordFree = RecordFreeExport.generate(document, packageName, tagNodes)) {
        // Unreachable: `applies` was true, so the emitter owns this document. Reported as a
        // refusal rather than asserted, because a null here would otherwise fall through to the
        // record-driven generator and come back as `NO_COMPONENT_RECORD` — advice about a
        // `--ui-builder-components` flag that would not have helped.
        null ->
          Generated.Refused(
            RECORD_FREE_DESIGN,
            listOf("no record-free emitter claimed this design"),
          )
        is RecordFreeExport.Generated.Refused ->
          Generated.Refused(UNEXPRESSIBLE_DOCUMENT, recordFree.reasons)
        is RecordFreeExport.Generated.Emitted ->
          Generated.Emitted(
            recordFree.source,
            // Not defaulted. `composeCompilable` is exactly the emitters that declare a composable,
            // so a null here is that pair having drifted apart, and inventing a name would be a
            // compile failure attributed to the design.
            requireNotNull(recordFree.composableName) {
              "a compose-compilable record-free design must name its composable"
            },
          )
      }
    }
    val catalogSystemId = document.catalogPin.systemId
    val record =
      when (val lookup = components(catalogSystemId)) {
        is ComponentRecordSource.Lookup.Found -> lookup.record
        // Two ways to have no record, and they need different sentences. Telling an operator who
        // already passed `--ui-builder-components` to pass it is advice they cannot act on; what
        // they need is the path and what went wrong with it, which only the source knows.
        ComponentRecordSource.Lookup.Unconfigured ->
          return Generated.Refused(
            NO_COMPONENT_RECORD,
            listOf(
              "this host has no discovered component record for catalog `$catalogSystemId`, so " +
                "no call site can be proven; run a preview bundle for that catalog's module and " +
                "pass it as `--ui-builder-components $catalogSystemId=<components.json>`"
            ),
          )
        is ComponentRecordSource.Lookup.Unusable ->
          return Generated.Refused(
            NO_COMPONENT_RECORD,
            listOf(
              "the component record configured for catalog `$catalogSystemId` could not be " +
                "loaded: ${lookup.reason}"
            ),
          )
      }
    if (!generatesFrom(record)) {
      // The capability advertised for this catalog is a configuration fact and cannot know today's
      // file, so the version check lives here, where it can name the version. `ScreenGenerator`
      // would refuse this too, but as an unproven call site — which reads like a stale document
      // rather than like a record this build will not read.
      return Generated.Refused(
        NO_COMPONENT_RECORD,
        listOf(
          "the component record for catalog `$catalogSystemId` is schema " +
            "${record.schemaVersion}, and this build generates from " +
            "$COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA to $COMPONENT_RECORD_SCHEMA_VERSION; " +
            "re-run discovery against a matching plugin version"
        ),
      )
    }
    val screenName = ScreenDocumentProjection.screenNameFor(document)
    val projected =
      when (val projection = ScreenDocumentProjection.project(document, screenName, tagNodes)) {
        is ScreenDocumentProjection.Outcome.Projected -> projection.document
        is ScreenDocumentProjection.Outcome.Refused ->
          return Generated.Refused(UNEXPRESSIBLE_DOCUMENT, projection.reasons)
      }
    return when (
      val generated = ScreenGenerator.generate(projected, record, packageName, EXPRESSION_PACKAGES)
    ) {
      is ScreenGenerator.Result.Refused -> Generated.Refused(UNPROVEN_CALL_SITE, generated.reasons)
      is ScreenGenerator.Result.Emitted -> Generated.Emitted(generated.source, screenName)
    }
  }

  /**
   * Where this artifact came from, as comments the compiler ignores.
   *
   * The exporter this replaces carried the design, the revision, the document hash and the catalog
   * pin, and dropping them cost something the new generator's precision does not replace: two
   * retained revisions that happen to project to the same Kotlin produced byte-identical artifacts,
   * so a file on somebody's disk could not be traced back to the revision or the catalog that
   * produced it.
   *
   * It does **not** carry the old header's canonical typed document. That existed because the
   * projection could not promise the source matched the design, so it shipped the design too; this
   * one can, and a whole JSON document in a comment is a large thing to attach to every export for
   * a claim already made by the source.
   *
   * Every value here except the revision is wire data — a design id and the three catalog pin
   * fields arrive over the HTTP API — so each is folded and commented per physical line by the same
   * rule [refused] uses. A newline in a design id would otherwise close the comment and put
   * document-supplied text into a file this server hands back as source.
   */
  private fun provenance(request: RevisionPinnedUiBuilderExport): String {
    val pin = request.document.catalogPin
    return listOf(
        "Generated by compose-preview serve from a UI-builder design.",
        "Design ${request.designId} revision ${request.revision}",
        "Document SHA-256: ${request.documentHash}",
        "Catalog ${pin.systemId}@${pin.catalogRevision}; capability ${pin.capabilityDigest}",
      )
      .commented() + "\n\n"
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
    // Split on physical lines, not on reasons. A refusal quotes document-supplied text — a colour
    // string, a token name, a node id — and catalog validation admits arbitrary strings there, so a
    // value carrying a newline would have left everything after it uncommented in an artifact this
    // executor calls a harmless parseable refusal. `\u2028` and `\u2029` are line terminators to
    // the Kotlin lexer too, so they are folded here rather than trusted to `lines()`.
    val content = reasons.commented() + "\n"
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

  /**
   * Every physical line of [this] prefixed with `// `, joined by newlines.
   *
   * Physical lines, not elements: both callers quote document-supplied text, and catalog validation
   * admits arbitrary strings in it, so an element carrying a newline would leave everything after
   * it uncommented in a file this executor hands back as Kotlin. `\u2028` and `\u2029` terminate a
   * line for the Kotlin lexer too and `lines()` does not split on them, so they are folded first.
   */
  private fun List<String>.commented(): String = flatMap {
    it.replace('\u2028', '\n').replace('\u2029', '\n').lines()
  }
    .joinToString("\n") { "// $it" }

  private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8)).joinToString("") {
      "%02x".format(it)
    }

  companion object {
    /**
     * The only packages a generated screen may call.
     *
     * `ScreenGenerator` refuses every projection-supplied callable outside this set, and its
     * default is empty — a caller that declares nothing generates nothing. That is not a formality
     * here: a `DesignDocumentV1` arrives over the authenticated HTTP API, and without a boundary a
     * construct naming `java.nio.file.Files.readString` would be emitted into source this server
     * hands back and `UiBuilderGeneratedPreviewAdapter` exists to compile and render.
     *
     * One prefix, because [ScreenDocumentProjection] emits one vocabulary: Material 3's theme
     * accessors, `Color`, `Dp`/`TextUnit`, `PaddingValues`, the layout and draw modifier
     * extensions, and the two shape constants are all under `androidx.compose`. Widening this set
     * is widening what a document can make this server execute, so it is a decision rather than a
     * list to keep topped up.
     */
    /**
     * The generator's security guard, taken from the shared gate rather than declared here.
     *
     * The browser editor's problems panel judges a design against the same set. A copy on either
     * side is a copy that can be widened without the other's review, and widening this one lets a
     * document name a package nobody vetted.
     */
    private val EXPRESSION_PACKAGES = ScreenExportGate.EXPRESSION_PACKAGES

    /**
     * Whether [record] is a schema `ScreenGenerator` will actually generate from.
     *
     * Parsing is not the same question. `ComponentRecordSource` ignores unknown keys on purpose so
     * a record from a newer producer still deserializes, leaving the version judgement to the
     * generator — which refuses anything below [COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA] or above
     * [COMPONENT_RECORD_SCHEMA_VERSION]. A host that only checked deserialization would advertise
     * `composeCode = true` for a record every export then refuses, which is the export action that
     * cannot succeed this capability exists to avoid.
     */
    fun generatesFrom(record: ComponentRecordFile): Boolean =
      record.schemaVersion in
        COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA..COMPONENT_RECORD_SCHEMA_VERSION

    /**
     * This host has no **usable** record for the design's pinned catalog — none configured, none
     * readable, or one on a schema this build will not generate from. Not the document's fault in
     * any of those cases, which is why they share a code.
     */
    const val NO_COMPONENT_RECORD = "NO_COMPONENT_RECORD"

    /** The document holds something no Kotlin value expresses — state, an event, an asset. */
    const val UNEXPRESSIBLE_DOCUMENT = "UNEXPRESSIBLE_DOCUMENT"

    /** The document is expressible; the catalog cannot prove one of its call sites. */
    const val UNPROVEN_CALL_SITE = "UNPROVEN_CALL_SITE"

    /**
     * The design generates through a record-free emitter, which the asking lane cannot use. Only
     * [generate] produces it — [export] serves these designs rather than refusing them.
     */
    const val RECORD_FREE_DESIGN = "RECORD_FREE_DESIGN"
  }
}
