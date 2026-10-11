package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.discovery.COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA
import ee.schimke.composeai.discovery.COMPONENT_RECORD_SCHEMA_VERSION
import ee.schimke.composeai.discovery.ComponentRecord
import ee.schimke.composeai.discovery.ComponentRecordFile
import ee.schimke.composeai.discovery.ScreenGenerator
import ee.schimke.composeai.uibuilder.export.CatalogComposeSourceExportAdapter
import ee.schimke.composeai.uibuilder.export.CatalogComposeSourceExportAdapters
import ee.schimke.composeai.uibuilder.export.FlexpressVariableFontSources
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.ScreenDocumentProjection
import ee.schimke.composeai.uibuilder.export.ScreenExportGate
import ee.schimke.composeai.uibuilder.export.TypefaceTarget
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.VariableFontExport
import ee.schimke.composeai.uibuilder.export.VariableFontExportMode
import ee.schimke.composeai.uibuilder.export.VariableFontSourceGenerator
import ee.schimke.composeai.uibuilder.export.WidgetAssetBytes
import ee.schimke.composeai.uibuilder.export.callableAliases
import ee.schimke.composeai.uibuilder.export.description
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocumentHome
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
 * Compose-source export driven by the discovered component record rather than a guess.
 *
 * `ScreenGenerator` emits a call site only where the record proves one can be written (public,
 * top-level, importable, no overload collision, signature recovered) and [ScreenDocumentProjection]
 * does the same for values. So an export is either source with no compilability warning, or an
 * `ERROR` per unexpressible thing, naming each.
 *
 * [components] is a supplier because the record is a build output (a bundle's `components.json`) a
 * served catalog may lack; a missing record is its own refusal code, not an empty catalog that
 * would refuse every node.
 */
internal class ScreenGeneratorComposeExportExecutor(
  private val components: (catalogSystemId: String) -> ComponentRecordSource.Lookup,
  /**
   * `generated.uibuilder`, the package `UiBuilderGeneratedPreviewAdapter` imports from; any other
   * default would break that lane (the golden test passes a package explicitly, so wouldn't
   * notice).
   */
  private val packageName: String = ScreenExportGate.PACKAGE_NAME,
  /**
   * Uploaded asset bytes, for the one lane that must inline them: a Wear widget's background is
   * drawn by the system host, so pixels travel inside the generated source. Null makes such a
   * widget refuse by name.
   */
  private val assetStore: UiBuilderAssetStore? = null,
  /**
   * Component packs this host admits. A pack node's call site is proven by the pack's record, so a
   * document is generated from its catalog's record plus, for each pack it uses, that pack's record
   * aliased with pack ids ([ComponentRecordPacks.aliasedRecord]).
   */
  private val packs: Set<String> = emptySet(),
  /**
   * A catalog's own components by builder id, for the record-free emitters
   * (`PublishedUiBuilderCatalog.Result.Composed.records`), so the Remote emitter's record fallback
   * is reachable for non-pack catalogs. A function because published catalogs are composed after
   * construction. Defaults to none.
   */
  private val publishedComponents: (catalogSystemId: String) -> Map<String, ComponentRecord> = {
    emptyMap()
  },
  private val catalogPlatform: (String) -> UiBuilderCatalogPlatform = {
    UiBuilderCatalogPlatform.DEFAULT
  },
  /**
   * Writes each variable-font text's declaration (via flexpress on the export classpath); the
   * generated call names it, so it is on by default.
   */
  private val variableFontSources: VariableFontSourceGenerator =
    FlexpressVariableFontSources.fromClasspath(),
) : UiBuilderExportExecutor {

  override fun export(request: RevisionPinnedUiBuilderExport): ExportArtifactV1 {
    require(request.format == ExportFormatV1.COMPOSE) {
      "${request.format} export is unsupported by the Compose source executor"
    }
    // Kept on purpose: the service pins a revision first, so a mismatch means broken pinning and a
    // misattributed artifact.
    require(request.revision == request.document.revision) { "export revision/document mismatch" }
    require(request.document.id == request.designId) { "export design/document mismatch" }

    // Record-free designs (Wear widgets and screens) go through their own emitter first, since
    // `remote-m3` and `wear-m3` have no record and the generator below could only refuse them. Same
    // `RecordFreeExport` entry as the Code pane, so they can't disagree. With [packageName], since
    // an exported file needs one. Packs are resolved only when the design is record-free and only
    // those it names.
    val platform = UiBuilderCatalogPlatform.from(request.catalog.statusSemantics)
    if (RecordFreeExport.applies(request.document, platform)) {
      val packRecords =
        when (val packs = packRecordsFor(request.document)) {
          is PackRecords.Refused -> return refused(packs.code, packs.reasons)
          is PackRecords.Found -> packs.records
        }
      RecordFreeExport.generate(
          request.document,
          platform,
          packageName,
          packComponents =
            when (val resolved = recordFreeComponents(request.document, packRecords)) {
              is RecordFreeComponents.Refused -> return refused(resolved.code, resolved.reasons)
              is RecordFreeComponents.Found -> resolved.components
            },
          assets = request.document.widgetAssetBytes(),
          variableFonts = VariableFontExport(variableFontSources),
        )
        ?.let { recordFree ->
          return when (recordFree) {
            is RecordFreeExport.Generated.Emitted ->
              emitted(provenance(request) + recordFree.source)
            // The document's own fault, named node by node: the emitter reached a node it cannot
            // write.
            is RecordFreeExport.Generated.Refused ->
              refused(UNEXPRESSIBLE_DOCUMENT, recordFree.reasons)
          }
        }
    }

    // Published catalogs declare a source adapter in their capability pin; resolve it before the
    // generic record projection. Never guessed from a catalog id, and catalog Kotlin snippets are
    // never executed. No declaration keeps the legacy path.
    when (val adapter = CatalogComposeSourceExportAdapters.resolve(request.catalog)) {
      CatalogComposeSourceExportAdapters.Resolution.NotDeclared -> Unit
      is CatalogComposeSourceExportAdapters.Resolution.Unsupported ->
        return refused(
          UNSUPPORTED_SOURCE_ADAPTER,
          listOf("${adapter.adapter}/v${adapter.version} is not shipped by this Preview Server"),
        )
      is CatalogComposeSourceExportAdapters.Resolution.Supported ->
        when (adapter.adapter.strategy) {
          CatalogComposeSourceExportAdapter.Strategy.COMPONENT_RECORDS -> Unit
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
   * A generated file as an artifact, with no compilability diagnostic on success. The one warning,
   * [ASSET_PLACEHOLDER], is about the design: a picture the source couldn't bundle stands in as a
   * coloured painter.
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
   * Header lines saying which `Image(...)` stands in for which picture (the generator's value tree
   * carries no comments). Values are document-supplied and folded per physical line like [refused].
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
       * Theme typefaces written as desktop `SystemFont` lookups (only for
       * [TypefaceTarget.DESKTOP]); a warning, since they fall back to the default face where the
       * family isn't installed.
       */
      val systemFontFamilies: List<String> = emptyList(),
      /**
       * The container this source is a Wear widget for, or null for a screen. Explicit because the
       * synthesized preview entry switches on it: a widget declares a body, brush and container
       * spec drawn inside the Glance Wear container. [screenName] is their base identifier.
       */
      val widgetFrame: WidgetFrame? = null,
      /**
       * A plain Remote body must be recorded and played; it draws nothing when composed directly.
       */
      val remoteContent: Boolean = false,
    ) : Generated

    data class Refused(val code: String, val reasons: List<String>) : Generated

    /**
     * A widget container's whole frame (content box plus authored padding) in dp. Not the design
     * `environment`, which is a screen's size and would letterbox or crop the container.
     */
    data class WidgetFrame(val widthDp: Int, val heightDp: Int)
  }

  /**
   * The generator run without the provenance header. Split from [export] so the native preview lane
   * gets the same refusals with [tagNodes] on.
   */
  internal fun generate(
    document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1,
    tagNodes: Boolean = false,
    /**
     * Which host container frames a widget design; ignored otherwise. Comes from the requester (the
     * editor), not the document, since the launcher draws the frame.
     */
    widgetHostShape: ee.schimke.composeai.uibuilder.export.WearWidgetHostShape =
      ee.schimke.composeai.uibuilder.export.WearWidgetHostShape.Default,
    /**
     * How theme typefaces are written: Android-only `GoogleFont` (the export default) or desktop
     * `SystemFont`. Lanes compiling against a catalog bundle pass its backend
     * ([TypefaceTarget.forNativeBackend]), since desktop bundles have no `GoogleFont`.
     */
    typefaces: TypefaceTarget = TypefaceTarget.DEFAULT,
    /**
     * How variable-font text draws: the export uses [LIBRARY][VariableFontExportMode.LIBRARY] (app
     * depends on `flexpress-compose`); bundle-compiling lanes use
     * [STANDALONE][VariableFontExportMode.STANDALONE].
     */
    variableFontMode: VariableFontExportMode = VariableFontExportMode.LIBRARY,
  ): Generated {
    // Record-free designs answer first here too, but this lane compiles, so the two emitters
    // diverge. A Wear screen is ordinary Wear Compose and compiles against a bundle carrying
    // `compose-material3` on the Robolectric daemon; it is the only honest picture since the
    // browser's Wasm canvas can't link an Android AAR (`docs/design/UI_BUILDER_WEAR_SCREEN.md`). A
    // Wear widget is Remote Compose: `RecordFreeExport.nativePreview` writes body, brush and
    // params, drawn in the Glance Wear container (unlike the exported file; see #522).
    if (RecordFreeExport.isWearWidget(document)) {
      return when (
        val preview =
          RecordFreeExport.nativePreview(
            document,
            packageName,
            document.widgetAssetBytes(),
            widgetHostShape,
            // A widget uses no packs. Resolved via the same helper as `export` so render and file
            // agree on vocabulary and record version.
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
    val platform = catalogPlatform(document.catalogPin.systemId)
    if (
      UiBuilderBuildFeatures.remoteCompose &&
        !RecordFreeExport.applies(document) &&
        platform == UiBuilderCatalogPlatform.REMOTE_COMPOSE
    ) {
      val packs =
        when (val resolved = packRecordsFor(document)) {
          is PackRecords.Refused -> return Generated.Refused(resolved.code, resolved.reasons)
          is PackRecords.Found -> resolved.records
        }
      val records =
        when (val resolved = recordFreeComponents(document, packs)) {
          is RecordFreeComponents.Refused ->
            return Generated.Refused(resolved.code, resolved.reasons)
          is RecordFreeComponents.Found -> resolved.components
        }
      return when (
        val source =
          RecordFreeExport.generate(
            document,
            platform,
            packageName,
            records,
            document.widgetAssetBytes(),
          )
      ) {
        is RecordFreeExport.Generated.Emitted ->
          Generated.Emitted(
            source.source,
            requireNotNull(source.composableName),
            remoteContent = true,
          )
        is RecordFreeExport.Generated.Refused ->
          Generated.Refused(UNEXPRESSIBLE_DOCUMENT, source.reasons)
        null ->
          Generated.Refused(RECORD_FREE_DESIGN, listOf("no Remote emitter claimed this design"))
      }
    }
    // A Wear-catalog design rooted in neither screen scaffold nor widget container is refused once
    // with "wrap the content in a screen" rather than per component. Widened for WEAR only, since
    // two-argument `applies` also claims A2UI designs.
    if (
      RecordFreeExport.applies(document) ||
        (platform == UiBuilderCatalogPlatform.WEAR && RecordFreeExport.applies(document, platform))
    ) {
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
            platform = platform,
            variableFonts = VariableFontExport(variableFontSources, variableFontMode),
          )
      ) {
        // Unreachable (`applies` was true), but reported as a refusal so a null can't fall through
        // to a misleading `NO_COMPONENT_RECORD`.
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
            // Not defaulted: `composeCompilable` emitters always name a composable, so null means
            // drift.
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
        // Unconfigured and unusable records need different messages; the latter names the path and
        // the problem.
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
      // Version-checked here, where the version can be named; `ScreenGenerator` would report it as
      // an unproven call site.
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
    // The catalog's own record, also answering to the ids the published file gave its components
    // (`<prefix><slug>`, which the record lacks). Added rather than replaced, since designs pinned
    // before the swap may use the old ids. See [resolvableRecord].
    val aliased = resolvableRecord(record, catalogSystemId)
    val merged =
      if (packRecords.isEmpty()) aliased
      else
        aliased
          .newBuilder()
          .apply { components = aliased.components + packRecords.flatMap { it.components } }
          .build()
    val screenName = ScreenDocumentProjection.screenNameFor(document)
    val projection =
      when (
        val outcome =
          ScreenDocumentProjection.project(document, screenName, tagNodes, typefaces = typefaces)
      ) {
        is ScreenDocumentProjection.Outcome.Projected -> outcome
        is ScreenDocumentProjection.Outcome.Refused ->
          return Generated.Refused(UNEXPRESSIBLE_DOCUMENT, outcome.reasons)
      }
    return when (
      val generated =
        ScreenGenerator.generate(
          projection.document,
          // A themed design's root is wrapped in `MaterialTheme`, which no catalog records; the
          // projection adds that one call's record, and only when it wrote it.
          projection.resolvable(merged),
          packageName,
          EXPRESSION_PACKAGES,
          previewFor(document.environment),
        )
    ) {
      is ScreenGenerator.Result.Refused ->
        Generated.Refused(
          UNPROVEN_CALL_SITE,
          summarizeUnproven(
            generated.reasons,
            catalogSystemId,
            designComponentIds = document.nodes.values.map { it.componentId }.toSet(),
            recordComponentIds = merged.components.flatMapTo(mutableSetOf()) { it.componentIds },
          ),
        )
      // The calls a variable font text projects to name a declaration flexpress writes, which
      // `ScreenGenerator` knows nothing of; joined into this file after the screen.
      is ScreenGenerator.Result.Emitted ->
        when (
          val joined =
            VariableFontExport(variableFontSources, variableFontMode)
              .join(generated.source, projection.variableFontTexts, packageName)
        ) {
          is VariableFontExport.Joined.Refused ->
            Generated.Refused(UNEXPRESSIBLE_DOCUMENT, joined.reasons)
          is VariableFontExport.Joined.Emitted ->
            Generated.Emitted(
              joined.source,
              screenName,
              projection.assetPlaceholders,
              systemFontFamilies = projection.systemFontFamilies,
            )
        }
    }
  }

  /**
   * The `@Preview`s a generated screen carries, or null when the design names no export devices (a
   * preview claims how a screen should be viewed). With devices, the design's own frame comes too,
   * as the signed-off size. `density` and `layoutDirection` aren't carried: `@Preview` has no
   * parameter for them.
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
   * Every pack [document] uses, as that pack's record aliased with pack ids
   * ([ComponentRecordPacks.aliasedRecord]). Only packs used, so a pack-free Wear screen touches no
   * record. A missing, unreadable or unsupported pack record refuses naming the pack.
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
   * Every component a record-free emitter may resolve: this catalog's own plus the design's packs.
   * Keys can't collide (`<packId>/…` vs `<catalogSystemId>/…`); packs merge last. The catalog half
   * is version-checked because `ComponentRecordSource` ignores unknown fields, so a newer schema
   * would otherwise pass silently (raised on #691).
   */
  private fun recordFreeComponents(
    document: ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1,
    packRecords: List<ComponentRecordFile>,
  ): RecordFreeComponents {
    val catalogSystemId = document.catalogPin.systemId
    val published = publishedComponents(catalogSystemId)
    if (published.isNotEmpty()) {
      // Only when a record exists: a catalog with none (today's `remote-m3`) uses the emitter's
      // hand-written cases.
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
   * The record a [catalogSystemId] design is generated from, or null when this host can't generate
   * for it. Shared with the browser's `ScreenExportGate` (code pane, problems panel) via
   * [installUiBuilderCatalogRecordRoutes][ee.schimke.composeai.cli.serve.installUiBuilderCatalogRecordRoutes],
   * so the two exporters read one record. Null covers [generate]'s three refusal cases; the
   * explanation belongs to the export. Packs are not included (per-document; see [packRecordsFor]).
   */
  fun exportRecord(catalogSystemId: String): ComponentRecordFile? {
    val record = (components(catalogSystemId) as? ComponentRecordSource.Lookup.Found)?.record
    if (record == null || !generatesFrom(record)) return null
    return resolvableRecord(record, catalogSystemId)
  }

  /**
   * [record] under every id a node may name it by: `callableAliases` for the variant table's
   * substitutions, [aliasPublished] for the published file's ids.
   */
  private fun resolvableRecord(
    record: ComponentRecordFile,
    catalogSystemId: String,
  ): ComponentRecordFile =
    aliasPublished(record.callableAliases(), publishedComponents(catalogSystemId))

  /**
   * [record] with each published builder id added to the component the published file says it
   * describes (`PublishedUiBuilderCatalog.Result.Composed.records`). Matched by `canonicalId`,
   * since the two are separate decodes. A no-op without a published catalog.
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
    return record
      .newBuilder()
      .apply {
        components =
          record.components.map { component ->
            val added = aliases[component.canonicalId]?.filterNot { it in component.componentIds }
            if (added.isNullOrEmpty()) component
            else
              component.newBuilder().apply { componentIds = component.componentIds + added }.build()
          }
      }
      .build()
  }

  /** Each pack component under the id the design refers to it by, for the record-free emitter. */
  private fun List<ComponentRecordFile>.byComponentId(): Map<String, ComponentRecord> =
    flatMap { file ->
      file.components.flatMap { record -> record.componentIds.map { it to record } }
    }
    .toMap()

  /**
   * Where this artifact came from, as comments: design, revision, document hash and catalog pin, so
   * identical Kotlin from different revisions stays traceable. The full typed document is no longer
   * embedded. Every wire-supplied value is folded per physical line like [refused], so a newline
   * can't escape the comment.
   */
  private fun provenance(request: RevisionPinnedUiBuilderExport): String {
    val pin = request.document.catalogPin
    return listOfNotNull(
        "Generated by compose-preview serve from a UI-builder design.",
        "Design ${request.designId} revision ${request.revision}",
        // The same line the editor's own emitter writes, so an export names where to edit the
        // design (agent rule R3) whichever generator produced it.
        request.document.home?.let {
          "Canonical home: ${it.toUiBuilderDocumentHome().description()}."
        },
        // Beside the home: an agent editing the design needs both where the original is and which
        // revision to quote as `baseRevision`.
        request.document.home?.let {
          "Edit the original there at revision ${request.revision} (quote it as baseRevision)."
        },
        "Document SHA-256: ${request.documentHash}",
        "Catalog ${pin.systemId}@${pin.catalogRevision}; capability ${pin.capabilityDigest}",
      )
      .commented() + "\n\n"
  }

  /**
   * A refusal as an artifact, since the port has no failure case: the reasons as a Kotlin comment
   * block, so piping it to a file still parses. The digest covers the content.
   */
  private fun refused(code: String, reasons: List<String>): ExportArtifactV1 {
    // Split on physical lines: reasons quote document-supplied text that may contain newlines
    // (including ` `/` `, which Kotlin treats as terminators), which would otherwise escape the
    // comment.
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
   * Every physical line prefixed with `// `. ` ` and ` ` are folded first since `lines()` doesn't
   * split on them but the Kotlin lexer does.
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
     * The generator's refusal, readable when long: repeats (one per node) collapse to one line with
     * a count, in first-seen order. When the record names none of the design's catalog components,
     * the problem is the file (e.g. a discovery `components.json` with taxonomy ids like
     * `Button/Filled` instead of `m3/button`), said first with an example of each.
     */
    internal fun summarizeUnproven(
      reasons: List<String>,
      catalogSystemId: String,
      designComponentIds: Set<String>,
      recordComponentIds: Set<String>,
    ): List<String> {
      val counts = LinkedHashMap<String, Int>()
      reasons.forEach { counts.merge(it, 1, Int::plus) }
      val collapsed = counts.map { (reason, n) -> if (n > 1) "$reason (×$n)" else reason }
      val missing =
        counts.keys.mapNotNull { MISSING_COMPONENT.matchEntire(it)?.groupValues?.get(1) }.toSet()
      // The design's own components the record could have proven: everything but the builder's
      // foundation, which every record carries whatever file it came from.
      val own = designComponentIds.filterNot { it.substringBefore('/') in FOUNDATION_PREFIXES }
      val wrongFile =
        missing.size > 1 &&
          own.isNotEmpty() &&
          own.all { it in missing } &&
          own.none { it in recordComponentIds }
      if (!wrongFile) return collapsed
      val example = recordComponentIds.filterNot { it.substringBefore('/') in FOUNDATION_PREFIXES }
      return listOf(
        "the component record for catalog `$catalogSystemId` proves none of this design's " +
          "${own.size} components, so it is probably not the record this catalog's designs are " +
          "written against" +
          (example.minOrNull()?.let { " — it names ids like `$it`" } ?: "") +
          ", and the design uses ids like `${own.min()}`; a catalog bundle's own discovery " +
          "`components.json` is not that record"
      ) + collapsed
    }

    /** The generator's words for a node whose id no record component claims. */
    private val MISSING_COMPONENT = Regex("no component `([^`]+)` in this catalog")

    /** The builder's own vocabulary, unioned onto every record by `ComponentRecordSource`. */
    private val FOUNDATION_PREFIXES = setOf("layout", "shape", "asset")

    /**
     * The packs [document] uses, in stable order, by id prefix (`<pack>/<component>`); only ids in
     * [packs] count.
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
     * The only packages a generated screen may call (`ScreenGenerator` refuses any other
     * projection-supplied callable). Documents arrive over the HTTP API, and generated source is
     * compiled and rendered, so this is a security boundary: widening it widens what a document can
     * make this server execute.
     */
    /** Taken from the shared gate so the browser's problems panel and this guard can't diverge. */
    private val EXPRESSION_PACKAGES = ScreenExportGate.EXPRESSION_PACKAGES

    /**
     * Whether `ScreenGenerator` will generate from [record]'s schema
     * ([COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA]..[COMPONENT_RECORD_SCHEMA_VERSION]).
     * Deserializing isn't enough, since unknown keys are ignored; otherwise the host would
     * advertise `composeCode = true` for a record every export refuses.
     */
    fun generatesFrom(record: ComponentRecordFile): Boolean =
      record.schemaVersion in
        COMPONENT_RECORD_OPT_IN_MECHANISM_SCHEMA..COMPONENT_RECORD_SCHEMA_VERSION

    /**
     * No usable record for the pinned catalog (none configured, unreadable, or unsupported schema);
     * never the document's fault.
     */
    const val NO_COMPONENT_RECORD = "NO_COMPONENT_RECORD"

    /** The document holds something no Kotlin value expresses — state, an event, an asset. */
    const val UNEXPRESSIBLE_DOCUMENT = "UNEXPRESSIBLE_DOCUMENT"

    /** The pinned catalog selected an adapter this server does not ship. */
    const val UNSUPPORTED_SOURCE_ADAPTER = "UNSUPPORTED_CATALOG_SOURCE_EXPORT_ADAPTER"

    /** The document is expressible; the catalog cannot prove one of its call sites. */
    const val UNPROVEN_CALL_SITE = "UNPROVEN_CALL_SITE"

    /**
     * A warning: an `asset/image` became `Image(painter = ColorPainter(…))` because its bytes live
     * in the asset store. The message names the node, key and digest.
     */
    const val ASSET_PLACEHOLDER = "ASSET_PLACEHOLDER"

    /**
     * The design needs a record-free emitter the asking lane can't use; only [generate] produces
     * it.
     */
    const val RECORD_FREE_DESIGN = "RECORD_FREE_DESIGN"
  }

  /**
   * The design's asset registry as inlinable bytes: embedded bindings pass through as base64,
   * uploaded ones are read from the store here (the emitter has no filesystem). Catalog bindings
   * answer null, which the export turns into a refusal naming the node.
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
