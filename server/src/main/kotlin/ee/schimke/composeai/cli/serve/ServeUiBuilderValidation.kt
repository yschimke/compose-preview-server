package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AcceptedOutcomeV1
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
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
}

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
  ): List<UiBuilderValidationProblemV1> =
    withContext(Dispatchers.IO) {
      val directory = scratchRoot()
      try {
        validateIn(directory, actor, document, operations)
      } finally {
        runCatching { directory.toFile().deleteRecursively() }
      }
    }

  private suspend fun validateIn(
    directory: Path,
    actor: AuthenticatedUiBuilderActor,
    document: DesignDocumentV1,
    operations: List<DesignMutationV1>?,
  ): List<UiBuilderValidationProblemV1> {
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
        ?: return listOf(problemOf(created, SOURCE_DOCUMENT))
    var revision = snapshot.snapshot.state.document.revision

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
        is AcceptedOutcomeV1 -> revision = result.committedRevision
        is RejectedOutcomeV1 ->
          return listOf(
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
          )
        else -> return listOf(problemOf(outcome, SOURCE_MUTATIONS))
      }
    }

    // The export gate: only where the pinned catalog exports Compose at all. A catalog that does
    // not has no gate to fail, and saying "valid" there is the truth rather than an omission.
    if (!snapshot.snapshot.catalog.exportCapabilities.composeCode) {
      return listOf(
        UiBuilderValidationProblemV1(
          severity = SEVERITY_INFO,
          source = SOURCE_EXPORT,
          code = "composeExportUnavailable",
          message =
            "the pinned catalog does not export Compose, so there is no export gate to check",
        )
      )
    }
    return when (
      val exported =
        scratch.run(actor, ExportDesignRequestV1(designId, revision, ExportFormatV1.COMPOSE))
    ) {
      is UiBuilderServiceResponse.Export ->
        exported.artifact.diagnostics.map {
          UiBuilderValidationProblemV1(
            severity =
              when (it.severity) {
                DiagnosticSeverityV1.ERROR -> SEVERITY_ERROR
                DiagnosticSeverityV1.WARNING -> SEVERITY_WARNING
                DiagnosticSeverityV1.INFO -> SEVERITY_INFO
              },
            source = SOURCE_EXPORT,
            code = it.code,
            message = it.message,
            nodeId = it.nodeId,
          )
        }
      else -> listOf(problemOf(exported, SOURCE_EXPORT))
    }
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
