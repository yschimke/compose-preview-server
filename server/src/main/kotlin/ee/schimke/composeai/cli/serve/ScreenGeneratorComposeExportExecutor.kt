package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA
import ee.schimke.composeai.discovery.COMPONENT_RECORD_SCHEMA_VERSION
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.uibuilder.RecordFreeExport
import ee.schimke.composeai.uibuilder.WidgetAssetBytes
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.callableAliases
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportDiagnosticV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.service.RevisionPinnedUiBuilderExport
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetStore
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import java.security.MessageDigest
import java.util.Base64

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
  /**
   * Where a design's uploaded asset bytes are, for the one lane that has to **inline** them.
   *
   * A Wear widget's background is drawn by the system host, out of the app's process and without
   * its resources, so a picture there cannot be a name the drawing side resolves — the pixels
   * travel inside the document and therefore inside the generated source. Null leaves a widget with
   * an image background refusing by name, which is what a host with no asset store can honestly
   * say.
   */
  private val assetStore: UiBuilderAssetStore? = null,
  /**
   * The component packs this host admits, by id.
   *
   * A design pinned to `m3-catalog` may hold `confetti-mobile/session-card`, and that node's call
   * site is proven by `confetti-mobile`'s record, not `m3-catalog`'s. So the record a document is
   * generated from is its catalog's plus, for every pack the document actually uses, that pack's
   * record with the pack's ids written onto it as aliases ([ComponentRecordPacks.aliasedRecord]).
   * Only the packs used, so a document that draws on none is generated from exactly the record it
   * always was.
   */
  private val packs: Set<String> = emptySet(),
  /**
   * A catalog's OWN components, by the builder id a design names them with, for the record-free
   * emitters — `PublishedUiBuilderCatalog.Result.Composed.records`, per catalog system id.
   *
   * The Remote emitter has hand-written cases for the components a widget is usually made of and
   * falls back to the record for the rest, which is most of what a Remote catalog publishes. That
   * fallback had no way to be reached in production: [packs] is the only component map this
   * executor built, a **pack**'s, and `remote-m3` is not a pack of itself — so an ordinary widget
   * design still refused every component the emitter had no case for.
   *
   * A function rather than a map because the published catalogs are composed after this executor is
   * constructed, and a value read at startup would be the empty map forever. Defaults to no
   * components, which is the honest answer for a host serving nothing published: the emitter then
   * refuses by name exactly as it did before.
   */
  private val publishedComponents: (catalogSystemId: String) -> Map<String, ComponentRecord> = {
    emptyMap()
  },
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
    //
    // And with the packs the design uses: a Wear screen may hold a `confetti-wear/…` node, whose
    // call the Wear emitter writes from that pack's record. Resolved only when the design is
    // record-free and only for the packs it names, so a plain Wear screen still touches no record.
    if (RecordFreeExport.applies(request.document)) {
      val packRecords =
        when (val packs = packRecordsFor(request.document)) {
          is PackRecords.Refused -> return refused(packs.code, packs.reasons)
          is PackRecords.Found -> packs.records
        }
      RecordFreeExport.generate(
          request.document,
          packageName,
          packComponents =
            when (val resolved = recordFreeComponents(request.document, packRecords)) {
              is RecordFreeComponents.Refused -> return refused(resolved.code, resolved.reasons)
              is RecordFreeComponents.Found -> resolved.components
            },
          assets = request.document.widgetAssetBytes(),
        )
        ?.let { recordFree ->
          return when (recordFree) {
            is RecordFreeExport.Generated.Emitted ->
              emitted(provenance(request) + recordFree.source)
            // The document's own fault and named node by node, which is what this code means.
            // There is no record involved to blame and no call site left unproven — the emitter
            // reached a node it cannot write.
            is RecordFreeExport.Generated.Refused ->
              refused(UNEXPRESSIBLE_DOCUMENT, recordFree.reasons)
          }
        }
    }

    return when (val generated = generate(request.document)) {
      is Generated.Refused -> refused(generated.code, generated.reasons)
      is Generated.Emitted ->
        emitted(
          provenance(request) + generated.assetPlaceholders.commentedNotes() + generated.source,
          generated.assetPlaceholders.map { it.warning() },
        )
    }
  }

  /**
   * A generated file as an artifact.
   *
   * No diagnostic about its own compilability on the success path, and that is the whole point of
   * this executor. An artifact with an empty diagnostic list says "this is the screen you
   * designed"; the one it replaced could only ever say "this is nearly it". The one warning it does
   * carry, [ASSET_PLACEHOLDER], is about the design rather than the generator: a picture the source
   * could not bundle stands in the frame as a coloured painter, and the person pasting the file
   * needs to know which line to replace and with which bytes.
   */
  private fun emitted(
    source: String,
    warnings: List<ExportDiagnosticV1> = emptyList(),
  ): ExportArtifactV1 =
    ExportArtifactV1(
      format = ExportFormatV1.COMPOSE,
      mediaType = "text/x-kotlin; charset=utf-8",
      encoding = ExportEncodingV1.UTF8,
      content = source,
      contentDigest = source.sha256(),
      diagnostics = warnings,
    )

  /**
   * The asset lines of the header: which `Image(...)` stands in for which picture.
   *
   * Beside the provenance rather than inline at the call, because the generator emits from a typed
   * value tree that carries no comments — and a header the reader sees first is where a "replace
   * this" belongs anyway. Every value here is document-supplied and folded per physical line by the
   * rule [refused] uses.
   */
  private fun List<ScreenDocumentProjection.AssetPlaceholder>.commentedNotes(): String {
    if (isEmpty()) return ""
    return map { it.note() }.commented() + "\n\n"
  }

  private fun ScreenDocumentProjection.AssetPlaceholder.note(): String =
    "Asset placeholder: node ${nodeId} draws asset '$assetKey'" +
      (if (contentDigest != null) " (${mediaType ?: "image"}, $contentDigest)"
      else " (not in this design's assets)") +
      " as a ColorPainter; bundle the picture as a resource and pass painterResource(...) there."

  private fun ScreenDocumentProjection.AssetPlaceholder.warning(): ExportDiagnosticV1 =
    ExportDiagnosticV1(
      severity = DiagnosticSeverityV1.WARNING,
      code = ASSET_PLACEHOLDER,
      message = note(),
    )

  /** The Kotlin for a document, or why there is none. */
  internal sealed interface Generated {
    data class Emitted(
      val source: String,
      val screenName: String,
      /**
       * The pictures the source stands in for; see [ScreenDocumentProjection.Outcome.Projected].
       */
      val assetPlaceholders: List<ScreenDocumentProjection.AssetPlaceholder> = emptyList(),
      /**
       * The container this source is a **Wear widget** for, or null for a screen.
       *
       * Present rather than inferred from the catalog id, because it is what the preview entry the
       * server synthesizes has to switch on: a widget's source declares no screen to call, it
       * declares a body, a brush and a container spec, and it is drawn inside the Glance Wear
       * container rather than composed at the design's own frame. [screenName] is then the base
       * identifier the three are declared under.
       */
      val widgetFrame: WidgetFrame? = null,
    ) : Generated

    data class Refused(val code: String, val reasons: List<String>) : Generated

    /**
     * A widget container's whole frame — its content box plus the padding the design authored — in
     * dp.
     *
     * The design's `environment` is not this. A widget design is authored at a container footprint
     * and the environment is a screen's, so rendering at it would letterbox the container inside a
     * watch face or crop it, and the frame the canvas draws beside it would be a different size.
     */
    data class WidgetFrame(val widthDp: Int, val heightDp: Int)
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
    /**
     * Which host container to frame a **widget** design in; ignored by every other design.
     *
     * Not read from the document, because the shape is not the design's — the launcher draws the
     * frame from `WearWidgetParams`. It arrives from whoever asked for the render, which is the
     * editor, whose canvas is drawing the same shape beside this render.
     */
    widgetHostShape: ee.schimke.composeai.uibuilder.WearWidgetHostShape =
      ee.schimke.composeai.uibuilder.WearWidgetHostShape.Default,
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
    // A **Wear widget** is Remote Compose — recorded into a `WearWidgetDocument` and played by the
    // host, never composed — so there is no screen here for this lane to call. It reaches the same
    // compiler by a third road: `RecordFreeExport.nativePreview` writes the body, the widget's own
    // `WearWidgetBrush` and the `WearWidgetParams` its scaffold describes, and the preview entry
    // draws them inside the Glance Wear container. Not the file `export` hands a designer, and
    // deliberately — that one asks for its pictures as parameters nothing here could pass, and may
    // only name the container specs upstream publishes, where this host builds the design's own
    // (yschimke/compose-preview-server#522).
    if (RecordFreeExport.isWearWidget(document)) {
      return when (
        val preview =
          RecordFreeExport.nativePreview(
            document,
            packageName,
            document.widgetAssetBytes(),
            widgetHostShape,
            // A widget draws no pack component, so this lane's vocabulary is the catalog's own
            // and nothing else. Resolved through the same helper as `export` because the render
            // and the file are two views of one design: a component the file can write and the
            // picture cannot is a hole in the canvas nobody can explain, and a record version
            // one lane refuses and the other reads is the same disagreement one layer down.
            when (val resolved = recordFreeComponents(document, emptyList())) {
              is RecordFreeComponents.Refused ->
                return Generated.Refused(resolved.code, resolved.reasons)
              is RecordFreeComponents.Found -> resolved.components
            },
          )
      ) {
        // Unreachable: `isWearWidget` was true, so the widget emitter owns this document. Reported
        // rather than asserted, for the reason the screen branch below reports its own null.
        null ->
          Generated.Refused(
            RECORD_FREE_DESIGN,
            listOf("no record-free emitter claimed this design"),
          )
        is RecordFreeExport.NativePreview.Refused ->
          Generated.Refused(UNEXPRESSIBLE_DOCUMENT, preview.reasons)
        is RecordFreeExport.NativePreview.Emitted ->
          Generated.Emitted(
            preview.source,
            preview.name,
            widgetFrame = Generated.WidgetFrame(preview.widthDp, preview.heightDp),
          )
      }
    }
    if (RecordFreeExport.applies(document)) {
      val packRecords =
        when (val packs = packRecordsFor(document)) {
          is PackRecords.Refused -> return Generated.Refused(packs.code, packs.reasons)
          is PackRecords.Found -> packs.records
        }
      return when (
        val recordFree =
          RecordFreeExport.generate(
            document,
            packageName,
            tagNodes,
            packComponents =
              when (val resolved = recordFreeComponents(document, packRecords)) {
                is RecordFreeComponents.Refused ->
                  return Generated.Refused(resolved.code, resolved.reasons)
                is RecordFreeComponents.Found -> resolved.components
              },
          )
      ) {
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
    val packRecords =
      when (val packs = packRecordsFor(document)) {
        is PackRecords.Refused -> return Generated.Refused(packs.code, packs.reasons)
        is PackRecords.Found -> packs.records
      }
    // The catalog's own record, wearing the ids the published file gave its components.
    //
    // `ScreenGenerator` resolves a node by matching `componentId` against a record component's
    // `componentIds`, and a published catalog names its components `<prefix><slug>` — ids the
    // record does not carry. m3-catalog's record carries its own taxonomy (`Dialog/Basic`,
    // `TopAppBar/Small`), so before this every node of a published m3 design refused with "no
    // component `m3/…` in this catalog" before its call site was ever considered.
    //
    // Added rather than replaced, which is where this differs from
    // `ComponentRecordPacks.aliasedRecord`: a pack component is only ever named by its pack id,
    // while a catalog's own component may still be named by a design pinned before the swap. Both
    // ids resolve to one record entry.
    // Two aliasings, for two different reasons. `callableAliases` lets the generator resolve what
    // `ScreenDocumentProjection`'s variant table substituted; `aliasPublished` lets it resolve the
    // ids the published file gave this catalog's components.
    val aliased = aliasPublished(record.callableAliases(), publishedComponents(catalogSystemId))
    val merged =
      if (packRecords.isEmpty()) aliased
      else aliased.copy(components = aliased.components + packRecords.flatMap { it.components })
    val screenName = ScreenDocumentProjection.screenNameFor(document)
    val projection =
      when (val outcome = ScreenDocumentProjection.project(document, screenName, tagNodes)) {
        is ScreenDocumentProjection.Outcome.Projected -> outcome
        is ScreenDocumentProjection.Outcome.Refused ->
          return Generated.Refused(UNEXPRESSIBLE_DOCUMENT, outcome.reasons)
      }
    return when (
      val generated =
        ScreenGenerator.generate(
          projection.document,
          merged,
          packageName,
          EXPRESSION_PACKAGES,
          previewFor(document.environment),
        )
    ) {
      is ScreenGenerator.Result.Refused -> Generated.Refused(UNPROVEN_CALL_SITE, generated.reasons)
      is ScreenGenerator.Result.Emitted ->
        Generated.Emitted(generated.source, screenName, projection.assetPlaceholders)
    }
  }

  /**
   * The previews a generated screen carries, or null for none — which is what an export emitted
   * before this and what a design naming no devices still gets.
   *
   * Gated on [DesignEnvironmentV1.exportDevices] rather than emitted always, deliberately. A
   * `@Preview` is a claim about how a screen should be looked at, and until a design named devices
   * nothing in the document made that claim: turning it on for every export would put a preview
   * into files whose authors never asked for one, and the frame would be the only picture — which
   * is the picture the builder canvas already is.
   *
   * When a design *has* named devices, the frame comes along. The design's own size is the canvas
   * its author approved, and a file that draws a screen on a Pixel Fold but not on the size it was
   * designed at has dropped the one picture that was signed off. The generator puts the frame on
   * its own wrapper and the devices on another, so the two do not contend.
   *
   * `density` and `layoutDirection` are deliberately not carried: `@Preview` has no parameter for
   * either. Naming that here beats leaving the next reader to wonder whether their omission was an
   * oversight.
   */
  private fun previewFor(environment: DesignEnvironmentV1): ScreenGenerator.Preview? =
    environment.exportDevices
      .takeIf { it.isNotEmpty() }
      ?.let { devices ->
        ScreenGenerator.Preview(
          widthDp = environment.widthDp,
          heightDp = environment.heightDp,
          fontScale = environment.fontScale,
          locale = environment.locale,
          darkMode = environment.theme == ThemeV1.DARK,
          devices = devices,
        )
      }

  /** The records of the packs a design uses, aliased to the pack's ids, or why there are none. */
  private sealed interface PackRecords {
    data class Found(val records: List<ComponentRecordFile>) : PackRecords

    data class Refused(val code: String, val reasons: List<String>) : PackRecords
  }

  /**
   * Every pack [document] draws on, as that pack's record with the pack's ids written onto it as
   * aliases ([ComponentRecordPacks.aliasedRecord]) — the shape both generators resolve a node by.
   *
   * Only the packs used, so a document that draws on none looks nothing up: a Wear screen that
   * holds no pack node must not touch a record, because `wear-m3` deliberately has none. A pack
   * whose record this host lacks, or will not load, or is a schema this build does not generate
   * from, refuses with the same two sentences the catalog's own record gets — naming the pack.
   */
  private fun packRecordsFor(
    document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
  ): PackRecords {
    val records =
      packsUsedBy(document, packs).map { pack ->
        when (val lookup = components(pack)) {
          is ComponentRecordSource.Lookup.Found -> {
            val aliased = ComponentRecordPacks.aliasedRecord(pack, lookup.record)
            if (!generatesFrom(aliased)) {
              return PackRecords.Refused(
                NO_COMPONENT_RECORD,
                listOf(
                  "the component record for pack `$pack` is schema ${aliased.schemaVersion}, and " +
                    "this build generates from $COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA to " +
                    "$COMPONENT_RECORD_SCHEMA_VERSION; re-run discovery against a matching " +
                    "plugin version"
                ),
              )
            }
            aliased
          }
          // This design holds a component of `$pack`'s, and this host cannot prove how to call it.
          ComponentRecordSource.Lookup.Unconfigured ->
            return PackRecords.Refused(
              NO_COMPONENT_RECORD,
              listOf(
                "this design uses components from the `$pack` pack, and this host has no " +
                  "discovered component record for it; run a preview bundle for that catalog's " +
                  "module and pass it as `--ui-builder-components $pack=<components.json>`"
              ),
            )
          is ComponentRecordSource.Lookup.Unusable ->
            return PackRecords.Refused(
              NO_COMPONENT_RECORD,
              listOf(
                "the component record configured for the `$pack` pack could not be loaded: " +
                  lookup.reason
              ),
            )
        }
      }
    return PackRecords.Found(records)
  }

  private sealed interface RecordFreeComponents {
    data class Found(val components: Map<String, ComponentRecord>) : RecordFreeComponents

    data class Refused(val code: String, val reasons: List<String>) : RecordFreeComponents
  }

  /**
   * Every component a record-free emitter may resolve for [document]: this catalog's own, plus the
   * packs the design draws on.
   *
   * The two are keyed the same way and cannot collide — a pack component's id is `<packId>/<name>`
   * and a catalog component's is `<catalogSystemId>/<name>`, and a catalog is not admitted as a
   * pack of itself — so the union is the whole vocabulary the emitter can prove a call for. Packs
   * are merged last so that if that ever stops being true, the entry naming the pack the design
   * explicitly draws on wins.
   *
   * The catalog's own half is version-checked here, the way the record-DRIVEN lane checks it in
   * [generate] and [packRecordsFor] checks each pack. It has to be: `ComponentRecordSource`
   * deserialises a newer schema with unknown fields ignored, so a record this build does not
   * generate from arrives looking like one it does. Passing its components straight to the emitter
   * would write Kotlin from a shape nobody promised, where the record-driven lane says which
   * version it read and what to re-run. Raised in review on #691.
   */
  private fun recordFreeComponents(
    document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1,
    packRecords: List<ComponentRecordFile>,
  ): RecordFreeComponents {
    val catalogSystemId = document.catalogPin.systemId
    val published = publishedComponents(catalogSystemId)
    if (published.isNotEmpty()) {
      // Only when there is something to gate. A catalog serving no published composition — the
      // deployment `remote-m3` runs in today — reaches the emitter with its hand-written cases and
      // no record at all, and refusing it for the version of a record it never had would be a
      // diagnostic about nothing.
      val lookup = components(catalogSystemId)
      if (lookup is ComponentRecordSource.Lookup.Found && !generatesFrom(lookup.record)) {
        return RecordFreeComponents.Refused(
          NO_COMPONENT_RECORD,
          listOf(
            "the component record for catalog `$catalogSystemId` is schema " +
              "${lookup.record.schemaVersion}, and this build generates from " +
              "$COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA to $COMPONENT_RECORD_SCHEMA_VERSION; " +
              "re-run discovery against a matching plugin version"
          ),
        )
      }
    }
    return RecordFreeComponents.Found(published + packRecords.byComponentId())
  }

  /**
   * [record] with every published builder id added to the component the published file says it
   * describes.
   *
   * The join comes from `PublishedUiBuilderCatalog.Result.Composed.records`, which is the only
   * place it exists: the published file states each component's `record` canonical id, and derives
   * an id for the ones it does not name. Matched here by `canonicalId` rather than by object
   * identity, because the composed map and this record are separate decodes of the same file and
   * nothing guarantees they share instances.
   *
   * A no-op for a catalog serving nothing published, which is every catalog today.
   */
  private fun aliasPublished(
    record: ComponentRecordFile,
    published: Map<String, ComponentRecord>,
  ): ComponentRecordFile {
    if (published.isEmpty()) return record
    val aliases = mutableMapOf<String, MutableList<String>>()
    for ((builderId, component) in published) {
      aliases.getOrPut(component.canonicalId) { mutableListOf() }.add(builderId)
    }
    return record.copy(
      components =
        record.components.map { component ->
          val added = aliases[component.canonicalId]?.filterNot { it in component.componentIds }
          if (added.isNullOrEmpty()) component
          else component.copy(componentIds = component.componentIds + added)
        }
    )
  }

  /** Each pack component under the id the design refers to it by, for the record-free emitter. */
  private fun List<ComponentRecordFile>.byComponentId(): Map<String, ComponentRecord> =
    flatMap { file ->
      file.components.flatMap { record -> record.componentIds.map { it to record } }
    }
    .toMap()

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
     * The packs [document] draws on, in a stable order.
     *
     * By id prefix, because that is what a pack component id is: `<pack>/<component>`, the same
     * shape `RecordFreeExport.CATALOG_SYSTEM_IDS` reads a catalog off. Only ids in [packs] count —
     * `m3/text` has a prefix too, and it is not a pack.
     */
    internal fun packsUsedBy(
      document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1,
      packs: Set<String>,
    ): List<String> =
      document.nodes.values
        .map { it.componentId.substringBefore('/') }
        .filter { it in packs }
        .distinct()
        .sorted()

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
     * A warning, not a refusal: an `asset/image` was written as `Image(painter = ColorPainter(…))`
     * because its picture is bytes in the design's asset store, which no generated Kotlin can
     * carry. The message names the node, the key and the digest to bundle.
     */
    const val ASSET_PLACEHOLDER = "ASSET_PLACEHOLDER"

    /**
     * The design generates through a record-free emitter, which the asking lane cannot use. Only
     * [generate] produces it — [export] serves these designs rather than refusing them.
     */
    const val RECORD_FREE_DESIGN = "RECORD_FREE_DESIGN"
  }

  /**
   * The design's own asset registry, as the bytes an inlining lane can carry.
   *
   * An **embedded** binding already is base64 and is handed over unchanged; an **uploaded** one is
   * content-addressed, so its bytes are joined here from the store beside the design state rather
   * than in the emitter, which has no filesystem and no business acquiring one. A catalog binding
   * has no bytes on this host and answers null, which the export turns into a refusal naming the
   * node instead of a picture it does not have.
   */
  private fun ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1.widgetAssetBytes():
    WidgetAssetBytes {
    val bindings = assets
    return WidgetAssetBytes { key ->
      when (val source = bindings[key]?.source) {
        is ee.schimke.composeai.uibuilder.protocol.EmbeddedAssetSourceV1 -> source.base64
        is ee.schimke.composeai.uibuilder.protocol.UploadedAssetSourceV1 ->
          assetStore?.read(source.storageKey)?.let(Base64.getEncoder()::encodeToString)
        else -> null
      }
    }
  }
}
