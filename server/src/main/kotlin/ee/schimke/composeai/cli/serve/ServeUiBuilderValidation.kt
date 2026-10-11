package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.RejectedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.RejectionCodeV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.PersistentUiBuilderService
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderCatalogExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderDesignStateStore
import ee.schimke.composeai.uibuilder.service.UiBuilderExportExecutor
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * "Would this be accepted, and would it export?" — asked without writing anything.
 *
 * ## Why a scratch service rather than a second validator
 *
 * The rules a design has to satisfy live in the runtime and are mostly private to it: the document
 * quota, the environment and topology checks, the catalog pin, the catalog's own semantic
 * validation, and — for a batch — the reducer that decides whether each mutation applies. A
 * validator written here would be a second copy of those, free to disagree with the first, and the
 * disagreement would surface exactly where it costs most: an agent told "valid" whose apply is then
 * refused.
 *
 * So the question is put to the real thing. Each call opens a throwaway
 * [PersistentUiBuilderService] over an empty temporary store, with **the same catalogs and the same
 * exporter** the host's own service uses, creates the document there (and applies the batch there),
 * and asks it for the Compose export. The answer is the runtime's own refusal, or the export gate's
 * own reasons — [ee.schimke.composeai.uibuilder.export.ScreenExportGate], which is also what the
 * editor's problems panel runs. Then the directory is deleted. Nothing reaches the real store, no
 * revision moves, nobody subscribed to the design hears anything, and no asset store is attached,
 * so nothing the scratch service does can touch a real picture.
 *
 * What it costs is one temporary directory and one export per call, which is what an agent was
 * already spending on the apply-and-see loop this replaces — without the revision it moved.
 */
fun interface UiBuilderDraftValidator {
  /**
   * Validates [document] and — when [operations] is non-null — the batch applied to it.
   *
   * [document] has already been read through the host's own service as [actor] when it came from a
   * stored design, so access control has already been decided by the time it reaches here.
   */
  suspend fun validate(
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    operations: List<DesignMutationV1>?,
  ): List<UiBuilderValidationProblemV1>

  /**
   * [validate], and the document it checked: [document] itself, or — when [operations] are given —
   * what the batch made of it, so a caller can ask further questions of an edit nobody has saved.
   *
   * The default can only hand back a document it was given, so a batch's result is null there; the
   * scratch validator, which actually applies the batch, overrides it.
   */
  suspend fun draft(
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    operations: List<DesignMutationV1>?,
  ): UiBuilderDraft =
    UiBuilderDraft(
      validate(actor, document, operations),
      if (operations == null) document else null,
    )

  /**
   * The editor's PNG export of each of [documents] exactly as given — environment included — with
   * nothing stored anywhere, or null where this lane cannot draw one.
   *
   * What `ui_builder_render_design_matrix` draws its cells with: the same design under a different
   * device, theme or font scale is a different environment, and changing a stored design's
   * environment to take its picture would move its revision and tell everybody watching it about a
   * change nobody made.
   */
  suspend fun exportPngs(
    actor: AuthenticatedUiBuilderActor,
    documents: List<DesignDocumentV1>,
  ): List<UiBuilderScratchPng>? = null
}

/** One picture from [UiBuilderDraftValidator.exportPngs], or why there is none. */
class UiBuilderScratchPng(val png: ByteArray?, val problem: String?)

/**
 * What [UiBuilderDraftValidator.draft] found: the problems, and the checked document with the
 * catalog it pins, when there was one to check.
 */
class UiBuilderDraft(
  val problems: List<UiBuilderValidationProblemV1>,
  val document: DesignDocumentV1?,
  val catalog: CatalogCapabilityV1? = null,
)

/** [UiBuilderDraftValidator] over a throwaway service; see the interface for why. */
class ScratchUiBuilderDraftValidator(
  private val catalogs: UiBuilderCatalogExecutor,
  private val exporter: UiBuilderExportExecutor,
  private val scratchRoot: () -> Path = { Files.createTempDirectory("ui-builder-validate") },
) : UiBuilderDraftValidator {

  override suspend fun validate(
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    operations: List<DesignMutationV1>?,
  ): List<UiBuilderValidationProblemV1> = draft(actor, document, operations).problems

  override suspend fun draft(
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    operations: List<DesignMutationV1>?,
  ): UiBuilderDraft =
    withContext(Dispatchers.IO) {
      val directory = scratchRoot()
      try {
        validateIn(directory, actor, document, operations)
      } finally {
        runCatching { directory.toFile().deleteRecursively() }
      }
    }

  override suspend fun exportPngs(
    actor: AuthenticatedUiBuilderActor,
    documents: List<DesignDocumentV1>,
  ): List<UiBuilderScratchPng> =
    withContext(Dispatchers.IO) {
      val directory = scratchRoot()
      try {
        val scratch =
          PersistentUiBuilderService(
            designStore = UiBuilderDesignStateStore.open(directory),
            catalogs = catalogs,
            exporter = exporter,
          )
        documents.mapIndexed { index, document ->
          val designId = "$SCRATCH_DESIGN_ID-$index"
          val created =
            scratch.run(
              actor,
              CreateDesignRequestV1(document.copy(id = designId, revision = 0, home = null)),
            )
          val snapshot =
            created as? UiBuilderServiceResponse.Snapshot
              ?: return@mapIndexed UiBuilderScratchPng(
                null,
                problemOf(created, SOURCE_DOCUMENT).message,
              )
          val exported =
            scratch.run(
              actor,
              ExportDesignRequestV1(
                designId,
                snapshot.snapshot.state.document.revision,
                ExportFormatV1.PNG,
              ),
            )
          val artifact =
            (exported as? UiBuilderServiceResponse.Export)?.artifact
              ?: return@mapIndexed UiBuilderScratchPng(
                null,
                problemOf(exported, SOURCE_EXPORT).message,
              )
          val errors = artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
          val bytes = runCatching {
            java.util.Base64.getDecoder().decode(artifact.content)
          }
            .getOrNull()
          if (errors.isNotEmpty() || bytes == null || bytes.isEmpty()) {
            UiBuilderScratchPng(
              null,
              errors
                .joinToString("; ") { "${it.code}: ${it.message}" }
                .ifEmpty { "the export produced no image" },
            )
          } else UiBuilderScratchPng(bytes, null)
        }
      } finally {
        runCatching { directory.toFile().deleteRecursively() }
      }
    }

  private suspend fun validateIn(
    directory: Path,
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    operations: List<DesignMutationV1>?,
  ): UiBuilderDraft {
    val scratch =
      PersistentUiBuilderService(
        designStore = UiBuilderDesignStateStore.open(directory),
        catalogs = catalogs,
        exporter = exporter,
      )
    // A scratch design always starts at revision 0 — the runtime's rule for a create — and has no
    // home: where a design lives is policy about importing it, not a property of whether it is
    // well-formed, and `ui_builder_create_design` states that policy itself.
    val designId = document.id.ifBlank { SCRATCH_DESIGN_ID }
    val seeded = document.copy(id = designId, revision = 0, home = null)
    val created = scratch.run(actor, CreateDesignRequestV1(seeded))
    val snapshot =
      created as? UiBuilderServiceResponse.Snapshot
        ?: return UiBuilderDraft(listOf(problemOf(created, SOURCE_DOCUMENT)), null)
    val catalog = snapshot.snapshot.catalog
    var revision = snapshot.snapshot.state.document.revision
    var checked = snapshot.snapshot.state.document

    if (operations != null) {
      val outcome =
        scratch.run(
          actor,
          ApplyOperationRequestV1(
            DesignCommandV1(
              designId = designId,
              operationId = SCRATCH_OPERATION_ID,
              actorId = actor.actorId,
              clientId = SCRATCH_CLIENT_ID,
              baseRevision = revision,
              operations = operations,
            )
          ),
        )
      when (val result = (outcome as? UiBuilderServiceResponse.OperationOutcome)?.outcome) {
        is AcceptedOutcomeV1 -> {
          revision = result.committedRevision
          checked =
            (scratch.run(actor, GetSnapshotRequestV1(designId = designId, revision = revision))
                as? UiBuilderServiceResponse.Snapshot)
              ?.snapshot
              ?.state
              ?.document ?: checked
        }
        is RejectedOutcomeV1 ->
          return UiBuilderDraft(
            listOf(
              UiBuilderValidationProblemV1(
                source = SOURCE_MUTATIONS,
                code =
                  UI_BUILDER_JSON.encodeToJsonElement(
                      RejectionCodeV1.serializer(),
                      result.code,
                    )
                    .let { (it as JsonPrimitive).content },
                message = result.message,
                nodeId = result.nodeId,
                field = result.field,
                operationIndex = result.operationIndex,
              )
            ),
            null,
            catalog,
          )
        else -> return UiBuilderDraft(listOf(problemOf(outcome, SOURCE_MUTATIONS)), null, catalog)
      }
    }

    // The scratch copy's id and revision are its own; the caller asked about theirs.
    checked = checked.copy(id = document.id, revision = document.revision)
    // The export gate: only where the pinned catalog exports Compose at all. A catalog that does
    // not has no gate to fail, and saying "valid" there is the truth rather than an omission.
    if (!snapshot.snapshot.catalog.exportCapabilities.composeCode) {
      return UiBuilderDraft(
        listOf(
          UiBuilderValidationProblemV1(
            severity = SEVERITY_INFO,
            source = SOURCE_EXPORT,
            code = "composeExportUnavailable",
            message =
              "the pinned catalog does not export Compose, so there is no export gate to check",
          )
        ),
        checked,
        catalog,
      )
    }
    val problems =
      when (
        val exported =
          scratch.run(actor, ExportDesignRequestV1(designId, revision, ExportFormatV1.COMPOSE))
      ) {
        is UiBuilderServiceResponse.Export ->
          exported.artifact.diagnostics.flatMap { diagnostic ->
            val severity =
              when (diagnostic.severity) {
                DiagnosticSeverityV1.ERROR -> SEVERITY_ERROR
                DiagnosticSeverityV1.WARNING -> SEVERITY_WARNING
                DiagnosticSeverityV1.INFO -> SEVERITY_INFO
              }
            UiBuilderExportDiagnosticLocation.locate(
                diagnostic.message,
                diagnostic.nodeId,
                checked,
                catalog.components,
              )
              .map { place ->
                UiBuilderValidationProblemV1(
                  severity = severity,
                  source = SOURCE_EXPORT,
                  code = diagnostic.code,
                  message = place.message,
                  nodeId = place.nodeId,
                  field = place.field,
                )
              }
          }
        else -> listOf(problemOf(exported, SOURCE_EXPORT))
      }
    return UiBuilderDraft(problems, checked, catalog)
  }

  private suspend fun PersistentUiBuilderService.run(
    actor: AuthenticatedUiBuilderActor,
    request: UiBuilderRequestV1,
  ): UiBuilderServiceResponse =
    when (val mapping = UiBuilderProtocolMapper.toServiceCall(actor, request)) {
      is ProtocolRequestMapping.Mapped ->
        try {
          execute(mapping.call)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: Exception) {
          UiBuilderServiceResponse.Error(
            UiBuilderServiceError(
              ServiceErrorCodeV1.INTERNAL,
              "validation failed: ${failure.message ?: failure::class.simpleName}",
            )
          )
        }
      is ProtocolRequestMapping.Rejected -> UiBuilderServiceResponse.Error(mapping.error)
    }

  private fun problemOf(response: UiBuilderServiceResponse, source: String) =
    when (response) {
      is UiBuilderServiceResponse.Error ->
        UiBuilderValidationProblemV1(
          source = source,
          code =
            UI_BUILDER_JSON.encodeToJsonElement(
                ServiceErrorCodeV1.serializer(),
                response.error.code,
              )
              .let { (it as JsonPrimitive).content },
          message = response.error.message,
        )
      else ->
        UiBuilderValidationProblemV1(
          source = source,
          code = "unexpectedResponse",
          message = "the UI-builder service answered ${response::class.simpleName}",
        )
    }

  private companion object {
    const val SCRATCH_DESIGN_ID = "validate"
    const val SCRATCH_OPERATION_ID = "validate"
    const val SCRATCH_CLIENT_ID = "mcp-validate"
  }
}

/**
 * Which node, and which of its properties, an export-gate diagnostic is about.
 *
 * The gate reports a refusal as a sentence: `ScreenExportGate` and `ScreenGenerator` keep their
 * reasons as strings, and the server's executor turns each into an `ExportDiagnosticV1` with no
 * `nodeId`. So a finding said *what* was wrong and left the caller to guess *where*, which on a
 * screen of a hundred nodes is the whole problem. This reads the place back out of the sentence,
 * against the document it was about, in the three shapes the reasons take:
 * - ``node `email`.singleLine: …`` — the projection's, naming the node and the property;
 * - a backticked token that is one of the document's node ids;
 * - ``…`OutlinedTextField` has no parameter `singleLine` `` — the generator's, naming the callable
 *   and the parameter but no node: every node whose component the catalog says calls that callable,
 *   and which sets that property, is the place (one problem each); and ``no component `m3/icon` in
 *   this catalog``, whose place is every node of that component.
 *
 * When the property is one the pinned catalog declares for that component, the gate and the catalog
 * disagree — the catalog offered something its own export cannot write — and the message says so,
 * so an agent stops editing a design that is not wrong.
 */
internal object UiBuilderExportDiagnosticLocation {
  class Place(val message: String, val nodeId: String?, val field: String?)

  fun locate(
    message: String,
    nodeId: String?,
    document: DesignDocumentV1,
    components: List<ComponentCapabilityV1>,
  ): List<Place> {
    val places = places(message, nodeId, document, components)
    if (places.isEmpty()) return listOf(Place(message, nodeId, null))
    return places.map { (node, field) ->
      val declared =
        field != null &&
          document.nodes[node]?.componentId?.let { componentId ->
            components
              .firstOrNull { it.componentId == componentId }
              ?.properties
              ?.any { it.name == field }
          } == true
      val componentId = document.nodes[node]?.componentId
      Place(
        if (!declared) message
        else
          "$message — the pinned catalog declares `$field` on `$componentId`, so the Compose " +
            "export gate and the catalog disagree: the design is not wrong, this host's export " +
            "cannot write that property yet. Remove `$field` from `$node` to export now",
        node,
        field,
      )
    }
  }

  private fun places(
    message: String,
    nodeId: String?,
    document: DesignDocumentV1,
    components: List<ComponentCapabilityV1>,
  ): List<Pair<String, String?>> {
    NODE_PROPERTY.find(message)?.let { match ->
      val node = match.groupValues[1]
      if (node in document.nodes) {
        val field = match.groupValues[2].ifEmpty { match.groupValues[3] }.ifEmpty { null }
        return listOf(node to field)
      }
    }
    if (nodeId != null && nodeId in document.nodes) return listOf(nodeId to null)
    val named = TICKED.findAll(message).map { it.groupValues[1] }.toList()
    named
      .filter { it in document.nodes }
      .distinct()
      .singleOrNull()
      ?.let { node ->
        return listOf(
          node to named.firstOrNull { it in (document.nodes[node]?.properties ?: emptyMap()) }
        )
      }
    NO_COMPONENT.find(message)?.let { match ->
      val componentId = match.groupValues[1]
      val using = document.nodes.values.filter { it.componentId == componentId }
      if (using.isNotEmpty()) return using.map { it.id to null }
    }
    NO_PARAMETER.find(message)?.let { match ->
      val callable = match.groupValues[1].substringAfterLast('.')
      val parameter = match.groupValues[2]
      val calling =
        components
          .filter { it.code?.symbol?.substringAfterLast('.') == callable }
          .mapTo(HashSet()) { it.componentId }
      return document.nodes.values
        .filter { it.componentId in calling && parameter in it.properties }
        .map { it.id to parameter }
    }
    return emptyList()
  }

  /** ``node `id`.property`` or ``node `id`.`property` ``, as the projection words a reason. */
  private val NODE_PROPERTY =
    Regex("""node `([^`]+)`(?:\.(?:`([^`]+)`|([A-Za-z_][A-Za-z0-9_]*)))?""")

  /** ``no component `m3/icon` in this catalog``, as the generator words a component it lacks. */
  private val NO_COMPONENT = Regex("""no component `([^`]+)`""")

  /** ``…`Callable` has no parameter `name` ``, as the generator words an unproven argument. */
  private val NO_PARAMETER = Regex("""`([^`]+)` has no parameter `([^`]+)`""")

  private val TICKED = Regex("""`([^`]+)`""")
}

internal const val SEVERITY_ERROR = "error"
internal const val SEVERITY_WARNING = "warning"
internal const val SEVERITY_INFO = "info"

/** The document's shape did not decode — the check that runs before anything else. */
internal const val SOURCE_SHAPE = "shape"

/** The runtime refused the document: quota, environment, topology, catalog pin or semantics. */
internal const val SOURCE_DOCUMENT = "document"

/** The reducer refused the batch. */
internal const val SOURCE_MUTATIONS = "mutations"

/** The Compose export gate — the editor's problems panel. */
internal const val SOURCE_EXPORT = "export"

/**
 * One reason a design would be refused, or a note about it.
 *
 * [source] says which check produced it — [SOURCE_SHAPE], [SOURCE_DOCUMENT], [SOURCE_MUTATIONS] or
 * [SOURCE_EXPORT] — because the remedies differ: a shape problem is a malformed call, a document or
 * mutation problem is refused before anything is stored, and an export problem is stored happily
 * and refused only when somebody asks for the Kotlin.
 */
@Serializable
data class UiBuilderValidationProblemV1(
  val severity: String = SEVERITY_ERROR,
  val source: String,
  val code: String,
  val message: String,
  val nodeId: String? = null,
  val field: String? = null,
  val operationIndex: Int? = null,
)

/** The `schema` of every [UiBuilderValidationV1]. */
internal const val UI_BUILDER_VALIDATION_SCHEMA = "compose-preview/ui-builder-validation/v1"

/** The reply of `ui_builder_validate`. [valid] is false exactly when a problem is an error. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class UiBuilderValidationV1(
  @EncodeDefault val schema: String = UI_BUILDER_VALIDATION_SCHEMA,
  val valid: Boolean,
  /** The stored design the batch was checked against, when the call named one. */
  val designId: String? = null,
  /** That design's revision at the time of the check. */
  val revision: Long? = null,
  val problems: List<UiBuilderValidationProblemV1>,
)
