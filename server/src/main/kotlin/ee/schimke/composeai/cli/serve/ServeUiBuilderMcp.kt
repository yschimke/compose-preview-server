package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.RemoteDocumentExportSupport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignHomeV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.DiagnosticSeverityV1
import ee.schimke.composeai.uibuilder.protocol.ExportArtifactV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.OpenDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.RevokeActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UpdateDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetWrite
import ee.schimke.composeai.uibuilder.service.UiBuilderBranchPort
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionRejectedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The UI builder, reachable by an agent rather than only by a browser.
 *
 * ## Why this exists
 *
 * `ui-builder-protocol` has shipped [ee.schimke.composeai.uibuilder.protocol.McpRequestEnvelopeV1]
 * and [McpResponseEnvelopeV1] since v1, and `UiBuilderProtocolMapper`'s own KDoc names the MCP
 * envelope's `actorId` as one of the untrusted fields it refuses to trust — a contract written for
 * a transport nothing implemented. So the builder was a browser feature: a design could be
 * authored, exported and refused entirely inside a tab, and an agent holding a grant for the box
 * could render previews but could not read, edit or export a single design.
 *
 * ## What it is, deliberately
 *
 * A thin typed door onto [UiBuilderServicePort] — the same port the HTTP routes call, with the same
 * [requiredCapability] mapping, the same [UiBuilderProtocolMapper] and the same authenticated actor
 * rule. One MCP tool per protocol request, and the reply is the released [McpResponseEnvelopeV1]
 * rather than a shape invented here. Nothing about a design's semantics lives in this file: an
 * agent that asks for something the service refuses gets the service's own refusal, and a design
 * the export cannot express gets the generator's own reasons.
 *
 * That is what makes this safe to expose. The gate an agent reaches is the gate a person reaches.
 */
class ServeUiBuilderMcp(
  private val service: UiBuilderServicePort,
  /**
   * The configured public origin documents created here are homed at, or null when the operator
   * stated none — then nothing is stamped, rather than a made-up address like `http://localhost`.
   */
  private val serverOrigin: () -> String? = { null },
  /**
   * The native render lane, on a box that has one.
   *
   * Null on a host without a playground bundle — compiling a design needs a Kotlin compiler and the
   * catalog's own classpath, which not every deployment carries. The tool remains discoverable and
   * returns [NATIVE_RENDER_UNAVAILABLE], so a client gets the same stable operation and can report
   * the gap instead of guessing why a tool is absent.
   */
  private val nativePreview: UiBuilderNativePreviewLane? = null,
  /**
   * The design's discussion, on a host that keeps one.
   *
   * Null on a host with no durable UI-builder state, where the comment routes are absent too — the
   * tools then do not appear in `tools/list` rather than appearing and failing, which is the rule
   * the whole surface follows.
   *
   * This is what makes an agent a participant rather than a tool: it can read what a designer
   * asked, answer in the same thread, and — through [AWAIT_COMMENTS] — *wait* for the next reply
   * instead of polling. The browser panel and this share one feed, so a person watching the page
   * and an agent waiting on a tool call learn about a comment at the same moment.
   */
  private val comments: ServeUiBuilderCommentStore? = null,
  /**
   * The reference overlays kept beside designs, on a host that keeps them.
   *
   * Read by nothing here; held so that [DELETE_DESIGN] removes what the operator's own delete
   * removes. A design's overlay and its discussion are stored beside the design rather than in it,
   * so the service deleting the design leaves them behind unless somebody sweeps — the admin page
   * does, and this door must not do less.
   */
  private val references: ServeUiBuilderReferenceStore? = null,
  /**
   * What each design is for, on a host that records it.
   *
   * Null on a host with no durable UI-builder state, where the links routes are absent too — the
   * tools then do not appear in `tools/list` rather than appearing and failing, which is the rule
   * the whole surface follows.
   *
   * This is what turns "here is a design" into "here is what this design is for": an agent opening
   * one is told the issue behind it, the frame it reproduces and the pull request that implemented
   * it, on the reply it was already reading, and can record the same for a design it creates.
   */
  private val links: ServeUiBuilderLinksStore? = null,
  private val onLog: (String) -> Unit = { System.err.println(it) },
  /**
   * The bytes behind a design's `assets` map, on a host that keeps them.
   *
   * Null on a host with no durable UI-builder state; the tool is then absent rather than present
   * and refusing, which is the rule the whole surface follows. This is what lets an agent put a
   * photograph in a design: `asset/image` names an `assetKey`, and until this tool existed nothing
   * on this surface could put bytes behind one.
   */
  private val assets: UiBuilderAssetPort? = null,
  /**
   * Answers "would this be accepted, and would it export?" without writing anything, on a host that
   * can open a scratch service over its own catalogs and exporter.
   *
   * Null where the host did not wire one; [VALIDATE] is then absent rather than present and
   * refusing, which is the rule the whole surface follows. See [UiBuilderDraftValidator].
   */
  private val validator: UiBuilderDraftValidator? = null,
  /**
   * Review verdicts and the implementing pull request, on a host that keeps them.
   *
   * Null on a host with no durable UI-builder state; the decision and implementation tools are then
   * absent rather than present and refusing, which is the rule the whole surface follows. See
   * [ServeUiBuilderReviewStore] for why this is beside the design rather than in it.
   */
  private val reviews: ServeUiBuilderReviewStore? = null,
  /**
   * Design branches — fork, list, merge, archive — on a host whose service keeps them
   * (yschimke/compose-ui-builder#377). Null leaves the branch tools absent rather than present and
   * refusing, which is the rule the whole surface follows. See [ServeUiBuilderBranchTools].
   */
  private val branches: UiBuilderBranchPort? = null,
) {

  /** Revisions, restore, fork and diff; see [ServeUiBuilderHistoryTools]. */
  private val history = ServeUiBuilderHistoryTools(service, serverOrigin, links)

  /** Branch, list, merge and archive; see [ServeUiBuilderBranchTools]. */
  private val branchTools = branches?.let(::ServeUiBuilderBranchTools)

  /** Whether this host keeps design branches, and so whether the branch tools exist. */
  val supportsBranches: Boolean
    get() = branches != null

  /** Whether this host keeps design discussions, and so whether the comment tools exist. */
  val supportsComments: Boolean
    get() = comments != null

  /** Whether this host keeps design assets, and so whether [PUT_ASSET] exists. */
  val supportsAssets: Boolean
    get() = assets != null

  /** Whether this host records what a design is for, and so whether the links tools exist. */
  val supportsLinks: Boolean
    get() = links != null

  /** Whether this host can check a design without saving it, and so whether [VALIDATE] exists. */
  val supportsValidation: Boolean
    get() = validator != null

  /** Whether this host records review decisions and implementations, and so whether they exist. */
  val supportsReviews: Boolean
    get() = reviews != null

  /** What a tool needs from the caller before it may run. Null when the name is not ours. */
  fun capabilityFor(tool: String): UiBuilderRouteCapability? =
    when (tool) {
      LIST_CATALOGS,
      SEARCH_COMPONENTS,
      LIST_DESIGNS,
      GET_DESIGN,
      PREVIEW_CATALOG_RECOVERY,
      // Reading a design's access list is gated at the door like any other read, and by the
      // service on top of that: only the owner is told who else holds a grant.
      DESIGN_ACCESS -> UiBuilderRouteCapability.READ
      AWAIT_DESIGN -> UiBuilderRouteCapability.READ
      // History: looking and comparing are reads; a restore writes the design (and the service
      // also takes the design's own WRITE action), and a fork creates a design, as the history
      // page's fork form does.
      ServeUiBuilderHistoryTools.LIST_REVISIONS,
      ServeUiBuilderHistoryTools.DIFF_DESIGNS -> UiBuilderRouteCapability.READ
      ServeUiBuilderHistoryTools.RESTORE_REVISION,
      ServeUiBuilderHistoryTools.FORK_DESIGN -> UiBuilderRouteCapability.WRITE
      // Branches: listing is a read of the parent; branching, merging and archiving write it (and
      // the service takes the parent's own WRITE action on top, or the branch's ownership for an
      // archive).
      ServeUiBuilderBranchTools.LIST_BRANCHES ->
        if (branches == null) null else UiBuilderRouteCapability.READ
      ServeUiBuilderBranchTools.BRANCH_DESIGN,
      ServeUiBuilderBranchTools.MERGE_BRANCH,
      ServeUiBuilderBranchTools.ARCHIVE_BRANCH ->
        if (branches == null) null else UiBuilderRouteCapability.WRITE
      LIST_COMMENTS,
      AWAIT_COMMENTS -> if (comments == null) null else UiBuilderRouteCapability.READ
      POST_COMMENT,
      RESOLVE_COMMENT_THREAD,
      // Acknowledging and reacting write to the board, so they are gated as writes — and an
      // acknowledgement is the thing that clears the notice an agent is being shown, which only
      // an actor that may write the discussion should be able to do on its own behalf.
      ACKNOWLEDGE_COMMENT,
      REACT_TO_COMMENT -> if (comments == null) null else UiBuilderRouteCapability.WRITE
      CREATE_DESIGN,
      APPLY,
      MOVE_DESIGN_HOME,
      REPLACE_DESIGN_DOCUMENT,
      RENAME_DESIGN,
      // Sharing writes to the design's access control, and the service admits only its owner.
      SHARE_DESIGN,
      // Gated at the door as a write; the service then admits only the design's owner. There is
      // no `delete` capability to hand out on purpose — see [DELETE_DESIGN].
      DELETE_DESIGN -> UiBuilderRouteCapability.WRITE
      EXPORT -> UiBuilderRouteCapability.EXPORT
      EXPORT_DOCUMENT ->
        if (RemoteDocumentExportSupport.formats.isEmpty()) null else UiBuilderRouteCapability.EXPORT
      // A write to the design — the registry is part of the document and moves its revision —
      // gated as one, and absent where the host has nowhere to keep the bytes.
      PUT_ASSET -> if (assets == null) null else UiBuilderRouteCapability.WRITE
      // Reading and writing what a design is for are gated as the design's own read and write,
      // even though neither touches the document: a link names an issue and a pull request, which
      // is exactly as much as the design it is beside says about the work it belongs to.
      GET_LINKS -> if (links == null) null else UiBuilderRouteCapability.READ
      SET_LINKS -> if (links == null) null else UiBuilderRouteCapability.WRITE
      // The same capability as an export, and for the same reason: a native render compiles and
      // runs the Kotlin an export hands back, so an actor who may not read that source may not
      // run it. It stays discoverable on a host that cannot compile: the call then returns the
      // stable NATIVE_RENDER_UNAVAILABLE refusal below instead of making clients infer capability
      // from a tool disappearing between otherwise equivalent hosts.
      RENDER_NATIVE -> UiBuilderRouteCapability.EXPORT
      // A read: nothing is written, and the document checked is either the caller's own or one the
      // service has just opened for them as a read.
      VALIDATE -> if (validator == null) null else UiBuilderRouteCapability.READ
      // Looking at a design is reading it: the default frame is the same PNG export a viewer of
      // the design is shown. The native frame compiles Kotlin, so [additionalCapabilityFor] asks
      // for the export capability on top when a call chooses it.
      VIEW -> UiBuilderRouteCapability.READ
      // Both read the design and write nothing: a check runs on a scratch copy, and a matrix draws
      // the PNG export a viewer of the design is already shown, at other sizes. `rendered: true`
      // compiles Kotlin, so [additionalCapabilityFor] asks for the export capability then.
      CHECK_DESIGN -> UiBuilderRouteCapability.READ
      RENDER_DESIGN_MATRIX -> if (validator == null) null else UiBuilderRouteCapability.READ
      // Recording a verdict or the implementing pull request is saying something about the design,
      // gated as the comment tools are: a write at the door, the design's own read inside.
      RECORD_DECISION,
      SET_IMPLEMENTATION -> if (reviews == null) null else UiBuilderRouteCapability.WRITE
      AWAIT_DECISION,
      IMPLEMENTATION_STATUS -> if (reviews == null) null else UiBuilderRouteCapability.READ
      FIND_DESIGN_FOR_PR ->
        if (reviews == null && links == null) null else UiBuilderRouteCapability.READ
      else -> null
    }

  /**
   * A second capability this particular call needs beyond [capabilityFor], or null.
   *
   * Only [VIEW] has one: `renderer: "native"` compiles and runs the design's generated Kotlin,
   * which [RENDER_NATIVE] gates as an export, and choosing the same lane through a read tool must
   * not be a way around that.
   */
  fun additionalCapabilityFor(tool: String, args: JsonObject): UiBuilderRouteCapability? =
    when {
      tool == VIEW && args.text(RENDERER_ARGUMENT) == ServeUiBuilderView.RENDERER_NATIVE ->
        UiBuilderRouteCapability.EXPORT
      // Measuring touch targets on a real render compiles the design's Kotlin, as [RENDER_NATIVE]
      // does, and the same grant gates it.
      tool == CHECK_DESIGN && args[RENDERED_ARGUMENT]?.jsonPrimitive?.booleanOrNull == true ->
        UiBuilderRouteCapability.EXPORT
      // The status hands over the Compose export unless asked not to, which is an export.
      tool == IMPLEMENTATION_STATUS &&
        args[INCLUDE_EXPORT_ARGUMENT]?.jsonPrimitive?.booleanOrNull != false ->
        UiBuilderRouteCapability.EXPORT
      else -> null
    }

  /**
   * Runs one tool as [actor], as the released MCP envelope.
   *
   * [callId] is the client's own JSON-RPC id, echoed back in the envelope: an agent batching calls
   * needs to tell two replies apart, and inventing an id here would defeat that.
   */
  suspend fun call(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
    /** Optional 2025 request-scoped interaction; every operation must retain a text fallback. */
    clientInteraction: ServeCatalogMcp.ClientInteraction =
      ServeCatalogMcp.ClientInteraction.Unsupported,
  ): String =
    withCommentNotice(
      tool,
      args,
      actor,
      withLinks(tool, args, run(tool, args, actor, callId, clientInteraction)),
    )

  private suspend fun run(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
    clientInteraction: ServeCatalogMcp.ClientInteraction,
  ): String {
    val request =
      when (tool) {
        LIST_CATALOGS -> return listCatalogs(args, actor, callId)
        SEARCH_COMPONENTS -> return searchComponents(args, actor, callId)
        LIST_DESIGNS ->
          ListDesignsRequestV1(
            cursor = args.text("cursor"),
            limit = args.number("limit")?.toInt() ?: DEFAULT_DESIGN_PAGE,
          )
        GET_DESIGN ->
          GetSnapshotRequestV1(
            designId = args.requiredText("designId"),
            revision = args.number("revision"),
          )
        PREVIEW_CATALOG_RECOVERY ->
          return envelope(
            callId,
            service.execute(
              UiBuilderServiceCall(
                actor,
                UiBuilderServiceRequest.PreviewCurrentCatalogUpgrade(args.requiredText("designId")),
              )
            ),
          )
        CREATE_DESIGN ->
          when (val plan = createDesign(args, actor, clientInteraction)) {
            is CreatePlan.Create -> plan.request
            is CreatePlan.Answered -> return plan.text
          }
        MOVE_DESIGN_HOME,
        REPLACE_DESIGN_DOCUMENT ->
          return authoritativeDocumentMutation(tool, args, actor, callId, clientInteraction)
        RENAME_DESIGN,
        DELETE_DESIGN -> return manageDesign(tool, args, actor, callId)
        DESIGN_ACCESS -> GetDesignAccessRequestV1(designId = args.requiredText("designId"))
        SHARE_DESIGN -> share(args, actor)
        APPLY -> apply(args, actor)
        EXPORT_DOCUMENT ->
          return envelope(
            callId,
            service.execute(
              UiBuilderServiceCall(
                actor,
                UiBuilderServiceRequest.ExportDocument(
                  args.requiredDocument(),
                  args.exportFormat(),
                ),
              )
            ),
          )
        EXPORT ->
          ExportDesignRequestV1(
            designId = args.requiredText("designId"),
            revision = args.number("revision"),
            format = args.exportFormat(),
          )
        RENDER_NATIVE -> return renderNative(args, actor)
        VIEW -> return view(args, actor)
        PUT_ASSET -> return envelope(callId, putAsset(args, actor))
        AWAIT_DESIGN -> return awaitDesign(args, actor)
        LIST_COMMENTS,
        AWAIT_COMMENTS,
        POST_COMMENT,
        RESOLVE_COMMENT_THREAD,
        ACKNOWLEDGE_COMMENT,
        REACT_TO_COMMENT -> return commentTool(tool, args, actor)
        GET_LINKS,
        SET_LINKS -> return linksTool(tool, args, actor)
        VALIDATE -> return validate(args, actor)
        CHECK_DESIGN -> return checkDesign(args, actor)
        RENDER_DESIGN_MATRIX -> return renderDesignMatrix(args, actor)
        RECORD_DECISION,
        AWAIT_DECISION,
        SET_IMPLEMENTATION,
        IMPLEMENTATION_STATUS -> return reviewTool(tool, args, actor)
        FIND_DESIGN_FOR_PR -> return findDesignForPr(args, actor)
        in ServeUiBuilderHistoryTools.TOOL_NAMES -> return history.call(tool, args, actor)
        in ServeUiBuilderBranchTools.TOOL_NAMES ->
          return (branchTools
              ?: throw McpRequestException("this host does not keep design branches"))
            .call(tool, args, actor)
        else -> throw McpRequestException("unknown UI-builder tool '$tool'")
      }
    return envelope(callId, execute(request, actor), includeCatalog = args.includeCatalog())
  }

  /**
   * The catalogs a design may pin to, as a summary unless the whole capability is asked for.
   *
   * The released [CatalogsResponseV1] is the entire `CatalogCapabilityV1` of every catalog — each
   * component's Wasm adapter status, SVG parity, export notes and menu shelving beside the
   * parameters — and on the hosted deployment that is about 72 KB, spent from an agent's context on
   * every call to the tool whose description says "start here". Authoring needs a fraction of it:
   * which components exist, what slots they have, which properties they take and which of those are
   * required — and the pin, which the capability does not even spell, so a client used to guess the
   * digest. That is [CatalogSummaryReplyV1], a few KB, and it is the default. `full: true` returns
   * the released envelope for the export lane and anybody comparing parity, and `componentIds`
   * narrows either to the components a call is about.
   */
  private suspend fun listCatalogs(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String {
    val full = args[FULL_ARGUMENT]?.jsonPrimitive?.booleanOrNull == true
    val componentIds =
      (args[COMPONENT_IDS_ARGUMENT] as? JsonArray)
        ?.map {
          it.jsonPrimitive.contentOrNull
            ?: throw McpRequestException("`$COMPONENT_IDS_ARGUMENT` must hold component ids")
        }
        ?.toSet()
    val listed =
      when (val response = execute(ListCatalogsRequestV1, actor)) {
        is UiBuilderServiceResponse.Catalogs -> response
        else -> return envelope(callId, response)
      }
    val catalogs =
      listed.catalogs.map { catalog ->
        if (componentIds == null) catalog
        else
          catalog
            .newBuilder()
            .also { it.components = catalog.components.filter { it.componentId in componentIds } }
            .build()
      }
    if (full) {
      return envelope(callId, UiBuilderServiceResponse.Catalogs(catalogs, listed.pins))
    }
    return SUMMARY_JSON.encodeToString(
      CatalogSummaryReplyV1.serializer(),
      CatalogSummaryReplyV1(
        callId = callId,
        catalogs =
          catalogs.map { catalog ->
            val systemId = catalog.benchmark.catalogSystemId
            CatalogSummaryV1(
              systemId = systemId,
              platform = catalog.statusSemantics[PLATFORM_KEY]?.jsonPrimitive?.contentOrNull,
              catalogPin = listed.pins[systemId],
              exportCapabilities = catalog.exportCapabilities,
              modifiers = catalog.components.flatMap { it.modifierCapabilities }.distinct(),
              components = catalog.components.map(::summarize),
            )
          },
      ),
    )
  }

  /**
   * The components whose id, role or traits contain `query` (case-insensitive), in the summary
   * shape of [listCatalogs]. A whole catalog summary is still thousands of tokens; an agent that
   * wants `TextField` or `Button` should not pay for the rest.
   */
  private suspend fun searchComponents(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String {
    val query =
      args[QUERY_ARGUMENT]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw McpRequestException("`$QUERY_ARGUMENT` is required")
    val catalogFilter = args[CATALOG_ARGUMENT]?.jsonPrimitive?.contentOrNull
    val listed =
      when (val response = execute(ListCatalogsRequestV1, actor)) {
        is UiBuilderServiceResponse.Catalogs -> response
        else -> return envelope(callId, response)
      }
    return SUMMARY_JSON.encodeToString(
      CatalogSummaryReplyV1.serializer(),
      CatalogSummaryReplyV1(
        callId = callId,
        catalogs =
          listed.catalogs
            .filter { catalogFilter == null || it.benchmark.catalogSystemId == catalogFilter }
            .mapNotNull { catalog ->
              val systemId = catalog.benchmark.catalogSystemId
              val matches =
                catalog.components.filter { component ->
                  component.componentId.contains(query, ignoreCase = true) ||
                    component.role.contains(query, ignoreCase = true) ||
                    component.traits.any { it.contains(query, ignoreCase = true) }
                }
              if (matches.isEmpty()) null
              else
                CatalogSummaryV1(
                  systemId = systemId,
                  platform = catalog.statusSemantics[PLATFORM_KEY]?.jsonPrimitive?.contentOrNull,
                  catalogPin = listed.pins[systemId],
                  components = matches.map(::summarize),
                )
            },
      ),
    )
  }

  /**
   * One component as a few short strings. Measured on the packaged M3 catalog: the same facts as
   * JSON objects came to 29 KB against the capability's 58, which is not the difference the summary
   * exists to make; as strings the whole summary comes to about 12.
   */
  private fun summarize(component: ComponentCapabilityV1): ComponentSummaryV1 =
    ComponentSummaryV1(
      id = component.componentId,
      role = component.role,
      traits = component.traits,
      slots = component.slots.map(::summarize),
      properties = component.properties.map(::summarize),
    )

  /**
   * The slot's name, its cardinality in square brackets as `min..max`, then `:` and the roles and
   * traits it accepts, `|`-separated.
   */
  private fun summarize(slot: SlotCapabilityV1): String {
    val accepted = (slot.acceptedRoles + slot.acceptedTraits).joinToString("|")
    val cardinality = "${slot.cardinality.min}..${slot.cardinality.max ?: "*"}"
    return "${slot.name}[$cardinality]" + if (accepted.isEmpty()) "" else ":$accepted"
  }

  /**
   * `name:type`, `!` when required, then `=` and the allowed values `|`-separated — or, past
   * [SUMMARY_ALLOWED_VALUES] of them, how many there are instead of what they are.
   */
  private fun summarize(property: PropertyCapabilityV1): String {
    val type =
      when (val jsonType = property.jsonType) {
        is JsonArray -> jsonType.joinToString("|") { it.jsonPrimitive.content }
        else -> jsonType.jsonPrimitive.content
      }
    // `allowedValues` is a `List<JsonElement>` and a catalog may legitimately put a non-primitive
    // in one. This is a human-readable summary, so an object renders as its JSON rather than
    // taking the whole `ui_builder_list_catalogs` response down with an exception.
    val allowed =
      if (property.allowedValues.size > SUMMARY_ALLOWED_VALUES)
        "<${property.allowedValues.size} values; ask for full>"
      else
        property.allowedValues.joinToString("|") { value ->
          (value as? JsonPrimitive)?.content ?: value.toString()
        }
    return "${property.name}:$type" +
      (if (property.required) "!" else "") +
      (if (allowed.isEmpty()) "" else "=$allowed")
  }

  /**
   * Rename or delete a design: the two things a session could not do to its own work.
   *
   * Neither has a request type in the released contract, so — like a native render and the comment
   * tools — the reply is a shape of this surface's own rather than an [McpResponseEnvelopeV1]
   * pretending to be one. A refusal is still the service's own, in the released error envelope, so
   * "not the owner" reads the same here as everywhere else.
   *
   * Deleting sweeps the overlay and the discussion kept beside the design, as the operator's admin
   * page does; a failure there is logged rather than reported, because the design is already gone
   * and telling the caller otherwise would invite a retry of something that cannot be retried.
   */
  private suspend fun manageDesign(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String {
    val designId = args.requiredText("designId")
    val request =
      when (tool) {
        RENAME_DESIGN -> UiBuilderServiceRequest.RenameDesign(designId, args.requiredText("title"))
        else -> UiBuilderServiceRequest.DeleteDesign(designId)
      }
    val response =
      try {
        service.execute(UiBuilderServiceCall(actor, request))
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        UiBuilderServiceResponse.Error(
          ee.schimke.composeai.uibuilder.service.UiBuilderServiceError(
            ServiceErrorCodeV1.INTERNAL,
            "UI-builder service failed",
            retryable = true,
          )
        )
      }
    return when (response) {
      is UiBuilderServiceResponse.DesignRenamed ->
        UI_BUILDER_JSON.encodeToString(
          DesignRenamedV1.serializer(),
          DesignRenamedV1(callId = callId, design = response.design),
        )
      is UiBuilderServiceResponse.DesignDeleted -> {
        runCatching { references?.delete(designId) }
          .onFailure { onLog("serve: reference overlay for $designId not removed (${it.message})") }
        runCatching { comments?.delete(designId) }
          .onFailure { onLog("serve: comment board for $designId not removed (${it.message})") }
        runCatching {
          // The store answers with an enum rather than throwing, so a failure reaches this
          // `runCatching` as an ordinary value: the check has to be on the result, or a record
          // left on disk is inherited by whatever takes the id next.
          if (links?.delete(designId) == LinksDeleteResult.FAILED) {
            onLog("serve: links record for $designId not removed")
          }
        }
          .onFailure { onLog("serve: links record for $designId not removed (${it.message})") }
        runCatching {
          if (reviews?.delete(designId) == false) {
            onLog("serve: review record for $designId not removed")
          }
        }
          .onFailure { onLog("serve: review record for $designId not removed (${it.message})") }
        UI_BUILDER_JSON.encodeToString(
          DesignDeletedV1.serializer(),
          DesignDeletedV1(callId = callId, designId = designId),
        )
      }
      else -> envelope(callId, response)
    }
  }

  /**
   * The two complete-document writes that deliberately do not pretend to be v1 design mutations.
   *
   * Their requests are typed at the service seam and their replies are the released operation
   * outcome envelope. The host only translates JSON and authenticated identity; exact revisions,
   * idempotency, authorization, validation, retention and broadcast stay authoritative in the
   * runtime.
   */
  private suspend fun authoritativeDocumentMutation(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
    clientInteraction: ServeCatalogMcp.ClientInteraction,
  ): String {
    val designId = args.requiredText("designId")
    args.requiredText("operationId")
    val baseRevision = args.requiredNumber("baseRevision")
    if ((args[DRY_RUN_ARGUMENT] as? JsonPrimitive)?.booleanOrNull == true) {
      val decision = homeDecision(tool, designId, baseRevision, args)
      val answer =
        elicitDecision(clientInteraction, decision, offersNewDesign = tool != MOVE_DESIGN_HOME)
          ?: return decision.toString()
      return when (answer.choice) {
        // The two writes the person can choose here are exactly the call this dry run stands in
        // for, with the same arguments, operation id and actor — so the same idempotency, revision
        // check and authorization — and never anything the non-dry-run call could not have done.
        "move",
        "save-back" -> documentMutation(tool, designId, baseRevision, args, actor, callId)
        "create-new" ->
          createNewInstead(args, answer.newDesignId, actor, callId) ?: decision.toString()
        // `discard`, `keep` and `cancel`: the person said no, and there is nothing to write.
        else -> answered(decision, answer.choice)
      }
    }
    return documentMutation(tool, designId, baseRevision, args, actor, callId)
  }

  private suspend fun documentMutation(
    tool: String,
    designId: String,
    baseRevision: Long,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String {
    val operationId = args.requiredText("operationId")
    val request =
      when (tool) {
        MOVE_DESIGN_HOME ->
          UiBuilderServiceRequest.MoveDesignHome(
            designId = designId,
            sourceHome = args.requiredNullableHome("sourceHome"),
            targetHome = args.requiredHome("targetHome"),
            baseRevision = baseRevision,
            operationId = operationId,
          )
        else ->
          UiBuilderServiceRequest.ReplaceDesignDocument(
            designId = designId,
            document = args.requiredDocument(),
            baseRevision = baseRevision,
            operationId = operationId,
          )
      }
    return envelope(callId, service.execute(UiBuilderServiceCall(actor, request)))
  }

  /**
   * The R3 decision behind a home move or a save-back / re-import, as a complete text result.
   *
   * A client that negotiated a request scope and declared form elicitation is asked these options
   * as an `elicitation/create` form instead ([elicitDecision]). Everyone else — and anyone who
   * declines, cancels or lets the form time out — gets this text, unchanged: the same validation as
   * the real call, nothing written, and the options for the agent to put to the person in chat
   * before it repeats the call without `dryRun`.
   */
  private fun homeDecision(
    tool: String,
    designId: String,
    baseRevision: Long,
    args: JsonObject,
  ): JsonObject {
    fun option(id: String, label: String) =
      JsonObject(mapOf("id" to JsonPrimitive(id), "label" to JsonPrimitive(label)))
    val fields = linkedMapOf<String, JsonElement>()
    fields["schema"] = JsonPrimitive(DECISION_SCHEMA)
    fields["designId"] = JsonPrimitive(designId)
    fields["baseRevision"] = JsonPrimitive(baseRevision)
    if (tool == MOVE_DESIGN_HOME) {
      args.requiredNullableHome("sourceHome")
      args.requiredHome("targetHome")
      fields["decision"] = JsonPrimitive("move-design-home")
      fields["sourceHome"] = args["sourceHome"] ?: JsonNull
      fields["targetHome"] = args.getValue("targetHome")
      fields["question"] =
        JsonPrimitive("Move the canonical home of design `$designId` to the target home?")
      fields["options"] =
        JsonArray(
          listOf(
            option("move", "Move the home; the old record stays as a copy pointing to it"),
            option("cancel", "Keep the current home"),
          )
        )
    } else {
      val document = args.requiredDocument()
      fields["decision"] = JsonPrimitive("save-back-or-reimport")
      fields["home"] = (args["document"] as? JsonObject)?.get("home") ?: JsonNull
      fields["title"] = JsonPrimitive(document.title)
      fields["question"] =
        JsonPrimitive(
          "This document's home is design `$designId` on this server. What should happen to it?"
        )
      fields["options"] =
        JsonArray(
          listOf(
            option("save-back", "Save back onto the original (replaces revision $baseRevision)"),
            option("create-new", "Create a new design instead, with a new id"),
            option("discard", "Discard the copy; the original stays as it is"),
            option("keep", "Keep the copy for now and change nothing"),
          )
        )
    }
    fields["elicitation"] =
      JsonPrimitive(
        "No form: this endpoint is stateless. Ask the person to pick one option in chat. " +
          "Repeat the call without dryRun only for `move` or `save-back`."
      )
    return JsonObject(fields)
  }

  /**
   * The R3 decision behind importing a document whose `home` is already this server (#1114), as the
   * same `compose-preview-decision/v1` choices [homeDecision] offers.
   *
   * It is a refusal (the tool result stays `isError`, nothing is written) that carries the options
   * rather than one hint, so an agent can put them to the person and continue with the chosen call.
   * [reason] is the refusal sentence older clients matched on, kept verbatim.
   */
  private fun importOntoHomeDecision(
    designId: String,
    revision: Long,
    incoming: DesignDocumentV1,
    args: JsonObject,
    reason: String,
  ): JsonObject {
    fun option(id: String, label: String) =
      JsonObject(mapOf("id" to JsonPrimitive(id), "label" to JsonPrimitive(label)))
    return JsonObject(
      linkedMapOf(
        "schema" to JsonPrimitive(DECISION_SCHEMA),
        "decision" to JsonPrimitive("import-onto-existing-home"),
        "designId" to JsonPrimitive(designId),
        "baseRevision" to JsonPrimitive(revision),
        "home" to ((args["document"] as? JsonObject)?.get("home") ?: JsonNull),
        "title" to JsonPrimitive(incoming.title),
        "reason" to JsonPrimitive(reason),
        "question" to
          JsonPrimitive(
            "This document's home is design `$designId` on this server, which already exists. " +
              "What should happen to it?"
          ),
        "options" to
          JsonArray(
            listOf(
              option(
                "apply-operations",
                "Apply the changes as operations to the original (revision $revision)",
              ),
              option("create-new", "Create a new design instead, with a new id"),
              option("cancel", "Cancel; the original stays as it is"),
            )
          ),
        "elicitation" to
          JsonPrimitive(
            "No form: this endpoint is stateless. Ask the person to pick one option in chat. " +
              "For `apply-operations`, read the original with $GET_DESIGN and send the " +
              "differences through $APPLY at baseRevision $revision. For `create-new`, repeat " +
              "$CREATE_DESIGN with a new designId and the document's `home` removed. For " +
              "`cancel`, call nothing."
          ),
      )
    )
  }

  /** What [createDesign] resolved to: a create to execute, or a finished answer to return. */
  private sealed interface CreatePlan {
    data class Create(val request: UiBuilderRequestV1) : CreatePlan

    data class Answered(val text: String) : CreatePlan
  }

  /** The person's pick from a decision form, already checked against the offered options. */
  private data class DecisionAnswer(val choice: String, val newDesignId: String?)

  /**
   * Puts [decision]'s options to the person as an `elicitation/create` form, when the calling
   * client negotiated a request scope and declared form support; null otherwise, and null for a
   * decline, a cancel, a timeout, a malformed answer or a choice that was not offered. Null always
   * means the same thing to the caller: write nothing and return the text decision.
   *
   * The form carries a closed `choice` enum (the decision's own option ids) and, where creating a
   * new design is offered, an optional `newDesignId` — the only free text, and only ever used as a
   * new design's id through the same create path, and the same checks, as [CREATE_DESIGN]. The
   * answer selects an action; it never widens one. The write it selects runs as the actor the
   * original call authenticated, and the transport drops an accepted answer whose credential no
   * longer authorizes that actor.
   */
  private suspend fun elicitDecision(
    interaction: ServeCatalogMcp.ClientInteraction,
    decision: JsonObject,
    offersNewDesign: Boolean,
  ): DecisionAnswer? {
    if (!interaction.formElicitationSupported) return null
    val options = (decision["options"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    val ids = options.mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
    val labels = options.map { it["label"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    val question = decision["question"]?.jsonPrimitive?.contentOrNull ?: return null
    if (ids.isEmpty() || ids.size != labels.size) return null
    val properties = linkedMapOf<String, JsonElement>()
    properties[DECISION_CHOICE_FIELD] =
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("string"),
          "title" to JsonPrimitive("Choice"),
          "description" to JsonPrimitive(question),
          "enum" to JsonArray(ids.map(::JsonPrimitive)),
          "enumNames" to JsonArray(labels.map(::JsonPrimitive)),
        )
      )
    if (offersNewDesign && "create-new" in ids) {
      properties[DECISION_NEW_DESIGN_ID_FIELD] =
        JsonObject(
          mapOf(
            "type" to JsonPrimitive("string"),
            "title" to JsonPrimitive("New design id"),
            "description" to JsonPrimitive("Only for create-new: the id the new design gets."),
            "minLength" to JsonPrimitive(1),
            "maxLength" to JsonPrimitive(MAX_ELICITED_DESIGN_ID),
          )
        )
    }
    val schema =
      JsonObject(
        mapOf(
          "type" to JsonPrimitive("object"),
          "properties" to JsonObject(properties),
          "required" to JsonArray(listOf(JsonPrimitive(DECISION_CHOICE_FIELD))),
        )
      )
    val result =
      interaction.elicitForm(question, schema, DECISION_ELICITATION_TIMEOUT_MILLIS) ?: return null
    if (result.action != ServeCatalogMcp.FormElicitationAction.ACCEPT) return null
    val content = result.content ?: return null
    val choice =
      (content[DECISION_CHOICE_FIELD] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.takeIf { it in ids } ?: return null
    val newDesignId =
      (content[DECISION_NEW_DESIGN_ID_FIELD] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_ELICITED_DESIGN_ID }
    return DecisionAnswer(choice, newDesignId)
  }

  /**
   * A decision the person answered with an option that writes nothing, as the decision itself plus
   * what they chose. Not an error: the call did what it was asked, which was to ask.
   */
  private fun answered(decision: JsonObject, choice: String): String {
    val next =
      if (choice == "apply-operations")
        "The person chose this in a form. Nothing was written: read the original with " +
          "$GET_DESIGN and send the differences through $APPLY at baseRevision " +
          "${decision["baseRevision"]}."
      else "The person chose this in a form. Nothing was written; call nothing."
    return JsonObject(
        decision - "elicitation" +
          mapOf(
            "chosen" to JsonPrimitive(choice),
            "written" to JsonPrimitive(false),
            "elicitation" to JsonPrimitive(next),
          )
      )
      .toString()
  }

  /**
   * `create-new` from a save-back or re-import decision: the supplied document as a NEW design
   * under the id the person typed, its `home` removed so it is homed here under that id instead. It
   * goes through [createDesign] with no client interaction, so every check a direct [CREATE_DESIGN]
   * makes still applies — an id already taken is refused as a tool error, never overwritten — and a
   * second form is never stacked on the first. Null (the text decision) when no id was given.
   */
  private suspend fun createNewInstead(
    args: JsonObject,
    newDesignId: String?,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String? {
    newDesignId ?: return null
    val document = args["document"] as? JsonObject ?: return null
    val createArgs =
      JsonObject(
        buildMap {
          put("designId", JsonPrimitive(newDesignId))
          put("document", JsonObject(document - "home"))
          args["title"]?.let { put("title", it) }
          args[INCLUDE_CATALOG_ARGUMENT]?.let { put(INCLUDE_CATALOG_ARGUMENT, it) }
        }
      )
    val plan =
      createDesign(createArgs, actor, ServeCatalogMcp.ClientInteraction.Unsupported)
        as? CreatePlan.Create ?: return null
    return envelope(
      callId,
      execute(plan.request, actor),
      includeCatalog = createArgs.includeCatalog(),
    )
  }

  /**
   * Grant or revoke one actor's access to a design.
   *
   * The access revision is read here rather than demanded from the caller. The service takes one to
   * refuse a change written against a stale access list, which is the right contract for a
   * long-lived editor holding the list on screen; an agent that just called this tool has no such
   * screen, and making it fetch a number only to hand the same number straight back would be
   * ceremony, not safety. The read is inside the same actor's authority, so nothing is skipped: a
   * caller who may not manage this design is refused by the read exactly as by the write.
   */
  private suspend fun share(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): UpdateDesignAccessRequestV1 {
    val designId = args.requiredText("designId")
    val actorId = args.requiredText("actorId")
    val current =
      execute(GetDesignAccessRequestV1(designId), actor) as? UiBuilderServiceResponse.DesignAccess
        ?: throw McpRequestException(
          "no design `$designId` whose sharing this actor may manage; only its owner can, and " +
            "$DESIGN_ACCESS says who that is"
        )
    val mutation =
      if (args[REVOKE_ARGUMENT]?.jsonPrimitive?.booleanOrNull == true) {
        RevokeActorAccessMutationV1(actorId)
      } else {
        val role = args.accessRole()
        GrantActorAccessMutationV1(actorId, role, role.defaultActions())
      }
    return UpdateDesignAccessRequestV1(
      designId = designId,
      baseAccessRevision = current.access.accessRevision,
      mutations = listOf(mutation),
    )
  }

  /**
   * A design compiled and rendered by real Compose on the host.
   *
   * The one reply here that is **not** an [McpResponseEnvelopeV1], and deliberately so: a native
   * render is not a `UiBuilderRequestV1`, the released contract defines no request type for one,
   * and inventing an envelope shape for a request the contract does not define would be worse than
   * being plainly outside it. The tool description says as much.
   *
   * The design is read back through the service as this actor, so a design's own access control
   * decides whether there is anything to render — a lane that took the document from anywhere else
   * would be a way to render a design you cannot open.
   */
  private suspend fun renderNative(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val designId = args.requiredText("designId")
    // Read through the service before reporting host capability. Besides keeping missing and
    // private designs indistinguishable, this establishes the access check that
    // withCommentNotice relies on before it may inspect this design's discussion.
    val snapshot =
      execute(GetSnapshotRequestV1(designId = designId, revision = args.number("revision")), actor)
        as? UiBuilderServiceResponse.Snapshot
        ?: throw McpRequestException("no design `$designId` this actor can read")
    val lane =
      nativePreview
        ?: return UI_BUILDER_JSON.encodeToString(
          NativePreviewRefusalV1.serializer(),
          NativePreviewRefusalV1(
            code = NATIVE_RENDER_UNAVAILABLE,
            reasons =
              listOf(
                "this host has no native render lane; configure a UI-builder native catalog " +
                  "and compiler to render this design with Compose"
              ),
          ),
        )
    val document = snapshot.snapshot.state.document
    return when (val outcome = lane.render(document)) {
      is UiBuilderNativePreviewOutcome.Refused ->
        UI_BUILDER_JSON.encodeToString(
          NativePreviewRefusalV1.serializer(),
          NativePreviewRefusalV1(code = outcome.code, reasons = outcome.reasons),
        )
      is UiBuilderNativePreviewOutcome.Rendered ->
        UI_BUILDER_JSON.encodeToString(
          NativePreviewResultV1.serializer(),
          NativePreviewResultV1(
            designId = designId,
            revision = document.revision,
            previewId = outcome.response.previewId,
            previewToken = outcome.response.previewToken,
            previewUrl = outcome.response.previewUrl,
            imageBase64 = outcome.response.image,
            taggedNodeIds = outcome.taggedNodeIds,
            nodeBounds =
              outcome.nodeBounds.mapValues { (_, box) ->
                NativePreviewNodeBoundsV1(
                  x = box.x,
                  y = box.y,
                  width = box.width,
                  height = box.height,
                )
              },
            compileError = outcome.failure,
          ),
        )
    }
  }

  /**
   * The editor canvas as a person sees it: a frame of the design with the selection, the reference,
   * the comment pins and — when asked — the layout bounds drawn over it, and the same facts as JSON
   * (compose-preview-server#1114). See [ServeUiBuilderView].
   *
   * Not an [McpResponseEnvelopeV1], for the reason a native render is not. The design is read
   * through the service as this actor first, so its own access control decides whether there is
   * anything to look at, and the frame is one of the renders the design's other tools already make
   * — nothing here draws a design a second way.
   */
  private suspend fun view(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val designId = args.requiredText("designId")
    val include = args.viewIncludes()
    val selection = args.stringList(SELECTION_ARGUMENT)
    val viewport = args.viewport()
    val renderer = args.text(RENDERER_ARGUMENT) ?: ServeUiBuilderView.RENDERER_EXPORT
    if (
      renderer != ServeUiBuilderView.RENDERER_EXPORT &&
        renderer != ServeUiBuilderView.RENDERER_NATIVE
    ) {
      throw McpRequestException(
        "`$RENDERER_ARGUMENT` must be `${ServeUiBuilderView.RENDERER_EXPORT}` or " +
          "`${ServeUiBuilderView.RENDERER_NATIVE}`"
      )
    }
    val snapshot =
      execute(GetSnapshotRequestV1(designId = designId, revision = args.number("revision")), actor)
        as? UiBuilderServiceResponse.Snapshot
        ?: throw McpRequestException("no design `$designId` this actor can read")
    val document = snapshot.snapshot.state.document
    val notes = mutableListOf<String>()
    val frame =
      if (renderer == ServeUiBuilderView.RENDERER_NATIVE) nativeViewFrame(designId, document)
      else exportViewFrame(designId, document.revision, actor)
    val board =
      if (ServeUiBuilderView.INCLUDE_COMMENTS !in include) null
      else if (comments == null) {
        notes += "this host keeps no design discussions, so there are no comment pins"
        null
      } else {
        val board = comments.readOrEmpty(designId)
        service.publicReaderView(actor, designId)?.board(board) ?: board
      }
    val reference =
      if (ServeUiBuilderView.INCLUDE_REFERENCE !in include) null
      else runCatching { references?.read(designId) }.getOrNull()
    val view =
      try {
        ServeUiBuilderView.compose(
          designId = designId,
          document = document,
          frame = frame,
          include = include,
          selection = selection,
          reference = reference,
          board = board,
          viewport = viewport,
          notes = notes,
        )
      } catch (refused: ServeUiBuilderView.Refused) {
        throw McpRequestException(refused.message ?: "the view could not be drawn")
      }
    return UI_BUILDER_JSON.encodeToString(UiBuilderViewV1.serializer(), view)
  }

  /** The editor renderer's PNG of [revision], exactly as `ui_builder_export` hands it over. */
  private suspend fun exportViewFrame(
    designId: String,
    revision: Long,
    actor: AuthenticatedUiBuilderActor,
  ): ServeUiBuilderView.Frame {
    val response =
      execute(
        ExportDesignRequestV1(
          designId = designId,
          revision = revision,
          format = ExportFormatV1.PNG,
        ),
        actor,
      )
    val artifact =
      when (response) {
        is UiBuilderServiceResponse.Export -> response.artifact
        is UiBuilderServiceResponse.Error -> throw McpRequestException(response.error.message)
        else -> throw McpRequestException("the PNG export of `$designId` answered no artifact")
      }
    val errors = artifact.diagnostics.filter { it.severity == DiagnosticSeverityV1.ERROR }
    val bytes =
      runCatching { java.util.Base64.getDecoder().decode(artifact.content) }.getOrNull()
        ?: ByteArray(0)
    if (errors.isNotEmpty() || bytes.isEmpty()) {
      throw McpRequestException(
        "`$designId` has no PNG to view: " +
          (errors
            .joinToString("; ") { "${it.code}: ${it.message}" }
            .ifEmpty { "the export produced no image" })
      )
    }
    return ServeUiBuilderView.Frame(bytes, ServeUiBuilderView.RENDERER_EXPORT, bounds = null)
  }

  /** The native lane's frame and the boxes it reported, in that frame's pixels. */
  private fun nativeViewFrame(
    designId: String,
    document: DesignDocumentV1,
  ): ServeUiBuilderView.Frame {
    val lane =
      nativePreview
        ?: throw McpRequestException(
          "$NATIVE_RENDER_UNAVAILABLE: this host has no native render lane; view with " +
            "`$RENDERER_ARGUMENT: \"${ServeUiBuilderView.RENDERER_EXPORT}\"` instead"
        )
    return when (val outcome = lane.render(document)) {
      is UiBuilderNativePreviewOutcome.Refused ->
        throw McpRequestException("${outcome.code}: ${outcome.reasons.joinToString("; ")}")
      is UiBuilderNativePreviewOutcome.Rendered -> {
        val encoded =
          outcome.response.image?.removePrefix("data:image/png;base64,")?.takeIf { it.isNotBlank() }
            ?: throw McpRequestException(
              "the native render of `$designId` produced no frame: " +
                (outcome.failure ?: "no reason given")
            )
        val bytes =
          runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull()
            ?: throw McpRequestException("the native render of `$designId` is not base64")
        ServeUiBuilderView.Frame(
          bytes,
          ServeUiBuilderView.RENDERER_NATIVE,
          bounds =
            outcome.nodeBounds.mapValues { (_, box) ->
              ServeUiBuilderView.Box(box.x, box.y, box.width, box.height)
            },
        )
      }
    }
  }

  private fun JsonObject.viewIncludes(): Set<String> {
    if (this[INCLUDE_ARGUMENT] == null || this[INCLUDE_ARGUMENT] is JsonNull) {
      return ServeUiBuilderView.DEFAULT_INCLUDES
    }
    val requested = stringList(INCLUDE_ARGUMENT).toSet()
    val unknown = requested - ServeUiBuilderView.INCLUDES.toSet()
    if (unknown.isNotEmpty()) {
      throw McpRequestException(
        "`$INCLUDE_ARGUMENT` names ${unknown.sorted().joinToString(", ")}; this server draws " +
          ServeUiBuilderView.INCLUDES.joinToString(", ")
      )
    }
    return requested
  }

  private fun JsonObject.stringList(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    if (value is JsonNull) return emptyList()
    return (value as? JsonArray)?.map {
      (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content
        ?: throw McpRequestException("`$name` must be an array of strings")
    } ?: throw McpRequestException("`$name` must be an array of strings")
  }

  private fun JsonObject.viewport(): Pair<Int, Int>? {
    val value = this[VIEWPORT_ARGUMENT] ?: return null
    if (value is JsonNull) return null
    val box =
      value as? JsonObject ?: throw McpRequestException("`$VIEWPORT_ARGUMENT` must be an object")
    fun edge(name: String): Int =
      box[name]
        ?.jsonPrimitive
        ?.longOrNull
        ?.takeIf { it in 1..ServeUiBuilderView.MAX_VIEWPORT_PX }
        ?.toInt()
        ?: throw McpRequestException(
          "`$VIEWPORT_ARGUMENT.$name` must be an integer from 1 to " +
            "${ServeUiBuilderView.MAX_VIEWPORT_PX}"
        )
    return Pair(edge("width"), edge("height"))
  }

  /**
   * Wait for somebody to change a design, rather than asking again whether they have.
   *
   * ## Why a tool and not an MCP notification
   *
   * MCP does have server-to-client notifications, but this surface deliberately does not advertise
   * resource subscriptions: `GET /mcp` — the long-lived Streamable-HTTP listening stream a
   * notification would travel on — answers 405, and `initialize` advertises `resources:
   * {"subscribe": false}`. The bounded request-scoped POST stream used for elicitation lives only
   * until that call's final response; it has no resumable notification cursor. A call that blocks
   * needs neither, and it is the shape the grant flow's `poll_access` and [AWAIT_COMMENTS] already
   * use here.
   *
   * ## What it is
   *
   * The same [UiBuilderServicePort.subscribe] the browser's `/updates` socket is built on, held for
   * one call. The reply is the released [DesignUpdateEnvelopeV1] — the identical frame that socket
   * delivers — so an agent and a designer are told the same thing in the same words, and neither
   * can learn about an edit the other does not.
   *
   * The cursor follows the service's own rule rather than a second one invented here: a
   * `afterSequence` inside the retained window is answered with the operations after it, and one
   * that is null, too far behind, or ahead of the design is answered with a whole snapshot.
   */
  private suspend fun awaitDesign(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val designId = args.requiredText("designId")
    val afterSequence =
      args.number("afterSequence")
        ?: throw McpRequestException(
          "`afterSequence` is required: it is the `lastSequence` you last saw"
        )
    if (afterSequence < 0) throw McpRequestException("`afterSequence` must not be negative")
    val waitSeconds =
      (args.number("waitSeconds") ?: DEFAULT_DESIGN_WAIT_SECONDS).coerceIn(
        0,
        MAX_DESIGN_WAIT_SECONDS,
      )

    val waiter = CompletableDeferred<UiBuilderServiceUpdate>()
    val subscription =
      try {
        service.subscribe(
          UiBuilderSubscriptionCall(
            actor = actor,
            designId = designId,
            afterSequence = afterSequence,
          )
        ) { update ->
          // Presence is chrome — who is looking and what they have selected — and is excluded from
          // the document, the revision and the sequence. Waking an agent for it would turn a
          // colleague moving their cursor into a tool call that returns nothing to act on.
          if (update.movesTheDocument(afterSequence)) waiter.complete(update)
        }
      } catch (rejected: UiBuilderSubscriptionRejectedException) {
        // The service's own refusal, verbatim: no such design, no read access, or too many
        // subscribers. Inventing a sentence here would describe a decision made elsewhere.
        throw McpRequestException(rejected.error.message)
      }
    val update =
      try {
        // The subscription's catch-up update is delivered before `subscribe` returns, so a design
        // that has already moved past the cursor is answered from here without entering the wait.
        //
        // Checked rather than left to `withTimeout`, because `withTimeout(0)` throws without ever
        // running its body: a caller asking `waitSeconds: 0` — the non-blocking "has anything
        // changed since my cursor", and the shape a polling loop wants — would be told nothing
        // happened while the edit it asked about sat completed in this deferred.
        if (waiter.isCompleted) waiter.await()
        else
          try {
            withTimeout(waitSeconds * 1000) { waiter.await() }
          } catch (_: TimeoutCancellationException) {
            return UI_BUILDER_JSON.encodeToString(
              DesignWaitTimeoutV1.serializer(),
              DesignWaitTimeoutV1(designId = designId, afterSequence = afterSequence),
            )
          }
      } finally {
        subscription.close()
      }
    val envelope =
      UI_BUILDER_JSON.encodeToJsonElement(
        DesignUpdateEnvelopeV1.serializer(),
        UiBuilderProtocolMapper.toProtocolUpdate(
          designId,
          service.publicReaderView(actor, designId)?.update(update) ?: update,
        ),
      )
    // A resync answers with a whole snapshot, catalog included; the same argument as on
    // [GET_DESIGN] keeps it to the document.
    return (if (args.includeCatalog()) envelope else envelope.withoutCatalog()).toString()
  }

  /**
   * The discussion around a design: read it, join it, close a thread, or wait for the next reply.
   *
   * Not an [McpResponseEnvelopeV1], for the same reason a native render is not: the released
   * contract defines no request type for a comment, and inventing an envelope shape for a request
   * the contract does not define would be worse than being plainly outside it.
   *
   * The design is read through the service as this actor before anything is read or written, so the
   * design's own access control decides whether there is a discussion here to join — the identical
   * check the HTTP comment routes make, for the identical reason.
   */
  private suspend fun commentTool(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val store = comments ?: throw McpRequestException("this host keeps no design discussions")
    val designId = args.requiredText("designId")
    if (
      execute(GetSnapshotRequestV1(designId = designId, revision = null), actor)
        !is UiBuilderServiceResponse.Snapshot
    ) {
      throw McpRequestException("no design `$designId` this actor can read")
    }
    val board =
      when (tool) {
        LIST_COMMENTS -> store.readOrEmpty(designId)
        AWAIT_COMMENTS -> {
          val after = args.number("afterSequence") ?: 0
          val wait =
            (args.number("waitSeconds") ?: DEFAULT_COMMENT_WAIT_SECONDS).coerceIn(
              0,
              MAX_COMMENT_WAIT_SECONDS,
            )
          // Null is a timeout rather than a failure: the caller asked whether anything was said
          // and the answer is "not yet", which it acts on by asking again with the same cursor.
          store.awaitBoardAfter(designId, after, wait * 1000)
            ?: return UI_BUILDER_JSON.encodeToString(
              CommentWaitTimeoutV1.serializer(),
              CommentWaitTimeoutV1(designId = designId, afterSequence = after),
            )
        }
        POST_COMMENT ->
          store
            .post(
              designId,
              actor.actorId,
              CommentPostRequest(
                threadId = args.text("threadId"),
                anchor =
                  StoredCommentAnchor(
                      markId = args.text("markId"),
                      nodeId = args.text("nodeId"),
                      x = args.decimal("x"),
                      y = args.decimal("y"),
                    )
                    .takeIf { !it.isEmpty },
                body = args.requiredText("body"),
                displayName = args.text("displayName") ?: actor.actorId,
              ),
              // Set by the server, never by an argument: everything reaching this class arrived
              // over MCP, so the caller is an agent whichever credential it holds. A tool that
              // could post as a person would be posting somebody else's words under its grant.
              authorKind = StoredComment.AUTHOR_KIND_AGENT,
            )
            .orThrow()
        RESOLVE_COMMENT_THREAD ->
          store
            .resolve(
              designId,
              actor.actorId,
              args.requiredText("threadId"),
              args["resolved"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            .orThrow()
        // An omitted `threadId` acknowledges the whole board, which is what an agent that has just
        // read the discussion means and what stops catching up costing a call per thread.
        ACKNOWLEDGE_COMMENT ->
          store.acknowledge(designId, actor.actorId, args.text("threadId")).orThrow()
        REACT_TO_COMMENT ->
          store
            .react(
              designId,
              actor.actorId,
              args.requiredText("commentId"),
              args.requiredText("reaction"),
              args["on"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            .orThrow()
        else -> throw McpRequestException("unknown UI-builder comment tool '$tool'")
      }
    val shaped = service.publicReaderView(actor, designId)?.board(board) ?: board
    return UI_BUILDER_JSON.encodeToString(StoredCommentBoard.serializer(), shaped)
  }

  /**
   * Read or replace what a design is for.
   *
   * Authorised twice, exactly as the links routes are: the tool's capability decides whether this
   * caller may use the builder, and then the design is read *through the service, as this actor*,
   * so a design they cannot open is "no such design" rather than a record they may write against,
   * and [SET_LINKS] additionally takes the design's own WRITE action.
   *
   * [SET_LINKS] replaces the whole record rather than merging into it, for the reason the route
   * gives: a partial write is how a design ends up citing the issue it used to be for, and clearing
   * one link has to be expressible.
   */
  private suspend fun linksTool(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val store = links ?: throw McpRequestException("this host does not record what designs are for")
    val designId = args.requiredText("designId")
    val actions =
      service.designActions(actor, designId)
        ?: throw McpRequestException("no design `$designId` this actor can read")
    // The tool capability got this call through the door; the design decides the rest. Setting a
    // design's links is authoring it, so it takes that design's own WRITE action and not merely an
    // agent grant wide enough to reach the tool.
    if (tool == SET_LINKS && !actions.contains(DesignAccessActionV1.WRITE)) {
      throw McpRequestException("design `$designId` does not grant this actor write access")
    }
    val stored =
      when (tool) {
        GET_LINKS ->
          return withBranches(
            branches,
            actor,
            designId,
            withAncestry(
              service,
              actor,
              store.ancestry,
              designId,
              UI_BUILDER_JSON.encodeToString(
                StoredLinks.serializer(),
                store.read(designId) ?: StoredLinks(designId = designId),
              ),
            ),
          )
        SET_LINKS ->
          when (val result = store.replace(designId, args.linksArgument())) {
            is LinksWriteResult.Refused -> throw McpRequestException(result.reason)
            is LinksWriteResult.Failed -> throw McpRequestException(result.reason)
            is LinksWriteResult.Stored -> result.links
          }
        else -> throw McpRequestException("unknown UI-builder links tool '$tool'")
      }
    val record = UI_BUILDER_JSON.encodeToJsonElement(StoredLinks.serializer(), stored).jsonObject
    // The chat platform the thread lives on, read off its permalink; see [ServeChatThreadLinks].
    return JsonObject(record + ServeChatThreadLinks.describe(stored.thread)).toString()
  }

  /**
   * The five links out of a tool call's arguments; an omitted one is unset, not unchanged.
   *
   * A call naming none of them clears the record, which is a thing an agent may legitimately ask
   * for — but only by asking for it. A call that names *other* things and no link got there by
   * misspelling a field, and reading that as a clear is how one typo deletes the issue and the pull
   * request behind a design.
   */
  private fun JsonObject.linksArgument(): StoredLinks {
    val links =
      StoredLinks(
        issue = text("issue"),
        reference = text("reference"),
        pr = text("pr"),
        thread = text("thread"),
        previous = text("previous"),
      )
    if (links.isEmpty && keys.any { it !in LINKS_CALL_KEYS }) {
      throw McpRequestException(
        "this call names no link this host knows; pass only `designId` to clear the record"
      )
    }
    return links
  }

  /**
   * The reply, plus what the design is for, when anybody has said.
   *
   * ## Why it is spliced onto the reply rather than left to the agent to ask for
   *
   * The same reason the comment notice is: an agent handed a design reads the document and starts
   * work, and the question it does not think to ask is what the screen is *for*. One extra call
   * would answer it, and an agent mid-task does not make that call — so the issue, the frame and
   * the pull request arrive on the reply it is already reading, and an agent asked to change a
   * screen can see the brief behind it without being told one exists.
   *
   * Only [GET_DESIGN], only where the reply is a *successful snapshot*, and only where a record
   * exists: a design nobody has linked pays one stat call and hands the original string back
   * untouched. A reply that is not a JSON object is handed back as it is — a link is worth having,
   * and never worth mangling the answer the agent asked for.
   *
   * ## Why the snapshot check is load-bearing
   *
   * A refused read is not an exception here. [run] hands a `GetSnapshotRequestV1` to the service
   * and serialises whatever comes back, so a design this actor may not open returns a perfectly
   * ordinary JSON envelope carrying an error response. Splicing onto that would hand the issue, the
   * frame, the pull request and the thread of a private design to anyone holding a read capability
   * who can guess its id — the links would be the answer the access check just refused. So the
   * record is attached to a snapshot and to nothing else.
   */
  private fun withLinks(tool: String, args: JsonObject, reply: String): String {
    val store = links ?: return reply
    if (tool != GET_DESIGN) return reply
    val designId = args.text("designId") ?: return reply
    val parsed =
      try {
        UI_BUILDER_JSON.parseToJsonElement(reply) as? JsonObject ?: return reply
      } catch (_: SerializationException) {
        return reply
      }
    // The access check is this line. Only a snapshot means the service opened the design as this
    // actor; an error envelope means it refused, and a refused read must not come back carrying
    // the links of the design it refused.
    if (!parsed.isSnapshotReply()) return reply
    val stored =
      try {
        store.read(designId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A record this host cannot read must never cost the agent the answer it asked for.
        null
      } ?: return reply
    return JsonObject(
        parsed +
          (LINKS_KEY to UI_BUILDER_JSON.encodeToJsonElement(StoredLinks.serializer(), stored))
      )
      .toString()
  }

  /**
   * Whether this envelope carries a design the service actually handed over.
   *
   * Read off the response's own discriminator rather than by decoding it: the envelope a default
   * agent call produces has had the catalog dropped ([envelope]), so the released serialiser would
   * reject the very reply this needs to recognise.
   */
  private fun JsonObject.isSnapshotReply(): Boolean =
    (this[RESPONSE_KEY] as? JsonObject)?.get(RESPONSE_TYPE_KEY)?.jsonPrimitive?.contentOrNull ==
      SNAPSHOT_RESPONSE_TYPE

  /**
   * The reply, plus the pending-discussion count and what this actor has not been told.
   *
   * ## Why it is spliced onto the reply rather than left to the agent to ask for
   *
   * Because the agent does not ask. The tools to find a comment have existed since the discussion
   * did, and the failure they were built for still happened: an agent kept applying mutations and
   * exporting while a designer's "The play icon looks like a cross" sat unread, because noticing
   * was opt-in and nothing an agent already read said a word about it. This converts "the agent
   * must think to ask" into "the agent cannot help but see" — see [CommentNoticeV1].
   *
   * ## What it costs
   *
   * One board read per reply on the tools in [COMMENT_NOTICE_TOOLS], and nothing at all on a host
   * that keeps no discussions. A successful [GET_DESIGN] is always parsed once to attach the exact
   * [UNACKNOWLEDGED_COMMENTS_KEY] count, including zero; the other replies are re-parsed only when
   * there is a notice to add, so a native render's base64 frame is not walked without a reason.
   *
   * A reply that is not a JSON object is handed back as it is: a notice is worth having, and never
   * worth mangling the answer the agent asked for.
   */
  private suspend fun withCommentNotice(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    reply: String,
  ): String {
    val store = comments ?: return reply
    if (tool !in COMMENT_NOTICE_TOOLS) return reply
    val designId = args.text("designId") ?: return reply
    // The count on GET_DESIGN must only accompany a successful snapshot. An error envelope means
    // the service refused to open the design; reading or attaching its discussion metadata there
    // would leak that a guessed private design has activity.
    val parsedSnapshot =
      if (tool != GET_DESIGN) null
      else
        try {
          (UI_BUILDER_JSON.parseToJsonElement(reply) as? JsonObject)?.takeIf {
            it.isSnapshotReply()
          } ?: return reply
        } catch (_: SerializationException) {
          return reply
        }
    // The design was read as this actor by the call that produced `reply`, so the access check has
    // already happened; a reply that never reached the design carries no notice because the board
    // of a design nobody may read is never consulted here — the tool refused before this point.
    val notice =
      try {
        val board = store.readOrEmpty(designId)
        // A reader who reaches a public design only through its public grant sees other people
        // under pseudonyms here too, exactly as the board itself is shaped for them.
        (service.publicReaderView(actor, designId)?.board(board) ?: board).noticeFor(actor.actorId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A discussion this host cannot read must never cost the agent the answer it asked for or
        // be misreported as an authoritative zero.
        return reply
      }
    if (notice == null && tool != GET_DESIGN) return reply
    val parsed =
      parsedSnapshot
        ?: try {
          UI_BUILDER_JSON.parseToJsonElement(reply) as? JsonObject ?: return reply
        } catch (_: SerializationException) {
          return reply
        }
    return JsonObject(
        buildMap {
          putAll(parsed)
          if (tool == GET_DESIGN) {
            put(UNACKNOWLEDGED_COMMENTS_KEY, JsonPrimitive(notice?.unacknowledged ?: 0))
          }
          if (notice != null) {
            put(
              COMMENTS_NOTICE_KEY,
              UI_BUILDER_JSON.encodeToJsonElement(CommentNoticeV1.serializer(), notice),
            )
          }
        }
      )
      .toString()
  }

  private fun CommentWriteResult.orThrow(): StoredCommentBoard =
    when (this) {
      is CommentWriteResult.Stored -> board
      is CommentWriteResult.Refused -> throw McpRequestException(reason)
    }

  /**
   * A new design, either from a document the caller wrote or copied from one this box already has.
   *
   * There is no third option, and the absence is deliberate. A design's `catalogPin` names a
   * catalog revision and a capability digest that the service checks against the live catalog, so a
   * starter document assembled here would either carry values invented in this file — which the
   * service would reject — or reach into the browser's own bootstrap, which fetches the checked-in
   * fixture and patches three fields from the catalog it just listed. Copying an existing design
   * gives an agent a pin that is real by construction.
   */
  private suspend fun createDesign(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    clientInteraction: ServeCatalogMcp.ClientInteraction,
  ): CreatePlan {
    val designId = args.requiredText("designId")
    val explicit = args["document"] as? JsonObject
    val source = args.text("fromDesignId")
    if ((explicit == null) == (source == null)) {
      throw McpRequestException("pass exactly one of `document` or `fromDesignId`")
    }
    val document =
      if (explicit != null) {
        try {
          UI_BUILDER_JSON.decodeFromJsonElement(DesignDocumentV1.serializer(), explicit)
        } catch (e: SerializationException) {
          throw McpRequestException("`document` is not a DesignDocumentV1: ${e.message}")
        }
      } else {
        val snapshot =
          execute(GetSnapshotRequestV1(designId = source!!, revision = null), actor)
            as? UiBuilderServiceResponse.Snapshot
            ?: throw McpRequestException("`fromDesignId` names no design this actor can read")
        snapshot.snapshot.state.document
      }
    val incoming =
      document.copy(
        id = designId,
        revision = 0,
        title = args.text("title") ?: document.title,
        // `fromDesignId` explicitly creates a new copy. It does not move the source's canonical
        // home; an explicit supplied document, by contrast, must not be adopted silently.
        home = if (source != null) null else document.home,
      )
    when (val existing = execute(OpenDesignRequestV1(designId), actor)) {
      is UiBuilderServiceResponse.Snapshot -> {
        val outcome = existingDesignOutcome(designId, incoming, serverOrigin())
        if (outcome is ServeUiBuilderCreate.Outcome.Refused) {
          val decision =
            importOntoHomeDecision(
              designId,
              existing.snapshot.state.document.revision,
              incoming,
              args,
              outcome.reason,
            )
          // Declined, cancelled, timed out or not negotiated: the refusal exactly as before.
          val answer =
            elicitDecision(clientInteraction, decision, offersNewDesign = true)
              ?: throw McpRequestException(decision.toString())
          return when (answer.choice) {
            "create-new" -> {
              val newDesignId =
                answer.newDesignId?.takeIf { it != designId }
                  ?: throw McpRequestException(decision.toString())
              val document =
                args["document"] as? JsonObject ?: throw McpRequestException(decision.toString())
              createDesign(
                JsonObject(
                  args +
                    mapOf("designId" to JsonPrimitive(newDesignId)) +
                    mapOf("document" to JsonObject(document - "home"))
                ),
                actor,
                ServeCatalogMcp.ClientInteraction.Unsupported,
              )
            }
            // `apply-operations` needs operations this server cannot derive from two documents on
            // the person's behalf, and `cancel` needs nothing: neither writes.
            else -> CreatePlan.Answered(answered(decision, answer.choice))
          }
        }
      }
      is UiBuilderServiceResponse.Error ->
        if (existing.error.code != ServiceErrorCodeV1.NOT_FOUND) {
          throw McpRequestException(existing.error.message)
        }
      else -> Unit
    }
    incomingHomeRefusal(incoming, serverOrigin())?.let { throw McpRequestException(it) }
    return CreatePlan.Create(CreateDesignRequestV1(incoming.withServerHome(serverOrigin())))
  }

  /**
   * Put bytes behind an `assetKey`, so an `asset/image` naming it draws a photograph.
   *
   * The bytes arrive base64-encoded because an MCP argument is JSON text; the service sniffs them
   * (PNG, JPEG, GIF or WebP), stores them by digest, pins the binding into the design's `assets`
   * and answers the same accepted outcome an `$APPLY` does, with the new revision to quote next.
   * Idempotent by content: the same bytes under the same key answer `idempotentReplay` and move
   * nothing.
   */
  private suspend fun putAsset(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): UiBuilderServiceResponse {
    val lane = assets ?: throw McpRequestException("this host keeps no design assets")
    val encoded = args.requiredText(ASSET_BYTES_ARGUMENT)
    val bytes =
      try {
        java.util.Base64.getDecoder().decode(encoded.trim())
      } catch (_: IllegalArgumentException) {
        throw McpRequestException("`$ASSET_BYTES_ARGUMENT` is not base64")
      }
    return try {
      lane.putAsset(
        UiBuilderAssetWrite(
          actor = actor,
          designId = args.requiredText("designId"),
          assetKey = args.requiredText("assetKey"),
          bytes = bytes,
        )
      )
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (_: Exception) {
      UiBuilderServiceResponse.Error(
        ee.schimke.composeai.uibuilder.service.UiBuilderServiceError(
          ServiceErrorCodeV1.INTERNAL,
          "UI-builder asset store failed",
          retryable = true,
        )
      )
    }
  }

  private fun apply(args: JsonObject, actor: AuthenticatedUiBuilderActor): UiBuilderRequestV1 {
    val operations =
      (args["operations"] as? JsonArray)
        ?: throw McpRequestException("`operations` must be an array of design mutations")
    val mutations =
      try {
        operations.map { UI_BUILDER_JSON.decodeFromJsonElement(DesignMutationV1.serializer(), it) }
      } catch (e: SerializationException) {
        throw McpRequestException(
          "`operations` holds something that is not a mutation: ${e.message}"
        )
      }
    return ApplyOperationRequestV1(
      DesignCommandV1(
        designId = args.requiredText("designId"),
        operationId = args.requiredText("operationId"),
        // Not read from the arguments. `UiBuilderProtocolMapper` rejects a command whose nested
        // actor is not the authenticated one, and the whole point of that check is that a caller
        // does not get to choose. Filling it from the grant means the check passes because the
        // claim is true, rather than because nobody made one.
        actorId = actor.actorId,
        clientId = args.text("clientId") ?: MCP_CLIENT_ID,
        baseRevision =
          args.number("baseRevision")
            ?: throw McpRequestException(
              "`baseRevision` is required: it is how a concurrent edit is detected"
            ),
        operations = mutations,
      )
    )
  }

  /**
   * Check a whole `document`, a stored design, or a batch of `operations` against a stored design,
   * and save nothing.
   *
   * A shape that does not decode is reported as a problem rather than thrown as a tool error: the
   * caller asked "is this valid?", and "no, and here is why" is the answer, not a failure of the
   * question. A design the actor cannot read is still refused outright, exactly as [GET_DESIGN]
   * refuses it, so this is not a way to learn that a private design exists.
   */
  private suspend fun validate(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val lane = validator ?: throw McpRequestException("this host cannot validate designs")
    val explicit = args["document"]
    val designId = args.text("designId")
    val rawOperations = args["operations"]
    if ((explicit == null) == (designId == null)) {
      throw McpRequestException(
        "pass exactly one of `document` (a whole design to check) or `designId` (a stored " +
          "design, optionally with `operations` to check against it)"
      )
    }
    if (explicit != null && rawOperations != null) {
      throw McpRequestException(
        "`operations` are checked against a stored design: pass `designId` with them, or check " +
          "the whole edited `document` instead"
      )
    }
    fun reply(
      problems: List<UiBuilderValidationProblemV1>,
      revision: Long? = null,
    ): String =
      UI_BUILDER_JSON.encodeToString(
        UiBuilderValidationV1.serializer(),
        UiBuilderValidationV1(
          valid = problems.none { it.severity == SEVERITY_ERROR },
          designId = designId,
          revision = revision,
          problems = problems,
        ),
      )
    fun shape(code: String, message: String, operationIndex: Int? = null) =
      UiBuilderValidationProblemV1(
        source = SOURCE_SHAPE,
        code = code,
        message = message,
        operationIndex = operationIndex,
      )

    if (explicit != null) {
      val document =
        try {
          UI_BUILDER_JSON.decodeFromJsonElement(DesignDocumentV1.serializer(), explicit)
        } catch (e: IllegalArgumentException) {
          // SerializationException is an IllegalArgumentException, and so is a `require` in a
          // protocol constructor; both mean the same thing to the caller.
          return reply(
            listOf(shape("invalidDocument", "`document` is not a DesignDocumentV1: ${e.message}"))
          )
        }
      return reply(lane.validate(actor, document, operations = null))
    }

    val snapshot =
      execute(GetSnapshotRequestV1(designId = designId!!, revision = null), actor)
        as? UiBuilderServiceResponse.Snapshot
        ?: throw McpRequestException("no design `$designId` this actor can read")
    val document = snapshot.snapshot.state.document
    val operations =
      when (rawOperations) {
        null -> null
        is JsonArray ->
          rawOperations.mapIndexed { index, element ->
            try {
              UI_BUILDER_JSON.decodeFromJsonElement(DesignMutationV1.serializer(), element)
            } catch (e: IllegalArgumentException) {
              return reply(
                listOf(
                  shape(
                    "invalidMutation",
                    "`operations[$index]` is not a DesignMutationV1: ${e.message}",
                    operationIndex = index,
                  )
                ),
                document.revision,
              )
            }
          }
        else ->
          return reply(
            listOf(shape("invalidMutation", "`operations` must be an array of design mutations")),
            document.revision,
          )
      }
    // Checked against the current document, because that is what an apply lands on. A batch
    // written against an older revision is not refused for it — the reducer rebases what does not
    // conflict — so a mismatch is a warning, not an error.
    val baseRevision = args.number("baseRevision")
    val notes =
      if (operations != null && baseRevision != null && baseRevision != document.revision)
        listOf(
          UiBuilderValidationProblemV1(
            severity = SEVERITY_WARNING,
            source = SOURCE_MUTATIONS,
            code = "revisionMismatch",
            message =
              "checked against the current revision ${document.revision}, not baseRevision " +
                "$baseRevision; an apply quoting $baseRevision is also checked for conflicts " +
                "with the edits in between",
          )
        )
      else emptyList()
    return reply(notes + lane.validate(actor, document, operations), document.revision)
  }

  /**
   * Everything worth knowing before a design is shown to a person, in one call
   * (compose-preview-server#1255): the shape and catalog checks [VALIDATE] runs, and the
   * accessibility checks of [UiBuilderAccessibilityCheck] — on a stored design, a past revision, a
   * whole document, or a batch of operations applied to a scratch copy and never saved.
   *
   * The reply leads with a sentence and the counts, then the findings with the node each is about,
   * so an agent can act on it without reading the rest.
   */
  private suspend fun checkDesign(args: JsonObject, actor: AuthenticatedUiBuilderActor): String {
    val checks = args.checksArgument()
    val explicit = args["document"]
    val designId = args.text("designId")
    val rawOperations = args["operations"]
    val revision = args.number("revision")
    val rendered = args[RENDERED_ARGUMENT]?.jsonPrimitive?.booleanOrNull == true
    if ((explicit == null) == (designId == null)) {
      throw McpRequestException(
        "pass exactly one of `document` (a whole design to check) or `designId` (a stored " +
          "design, optionally with `revision`, or with `operations` to check unsaved)"
      )
    }
    if (explicit != null && (rawOperations != null || revision != null)) {
      throw McpRequestException(
        "`operations` and `revision` are about a stored design: pass `designId` with them"
      )
    }
    if (rawOperations != null && revision != null) {
      throw McpRequestException(
        "`operations` are checked against the current revision, as $APPLY lands them; omit " +
          "`revision`, or quote the revision you read as `baseRevision`"
      )
    }
    val findings = mutableListOf<UiBuilderCheckFindingV1>()
    val skipped = mutableListOf<UiBuilderCheckSkippedV1>()
    fun shapeFinding(code: String, message: String, operationIndex: Int? = null) =
      UiBuilderCheckFindingV1(
        severity = SEVERITY_ERROR,
        check = CHECK_SCHEMA,
        code = code,
        message = message,
        operationIndex = operationIndex,
      )

    var document: DesignDocumentV1? = null
    var catalog: ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1? = null
    var checkedRevision: Long? = null
    if (explicit != null) {
      document =
        try {
          UI_BUILDER_JSON.decodeFromJsonElement(DesignDocumentV1.serializer(), explicit)
        } catch (e: IllegalArgumentException) {
          findings +=
            shapeFinding("invalidDocument", "`document` is not a DesignDocumentV1: ${e.message}")
          null
        }
    } else {
      val snapshot =
        when (val response = execute(GetSnapshotRequestV1(designId!!, revision), actor)) {
          is UiBuilderServiceResponse.Snapshot -> response.snapshot
          is UiBuilderServiceResponse.Error -> throw McpRequestException(response.error.message)
          else -> throw McpRequestException("no design `$designId` this actor can read")
        }
      document = snapshot.state.document
      catalog = snapshot.catalog
      checkedRevision = document.revision
    }

    val operations =
      when (rawOperations) {
        null -> null
        is JsonArray ->
          rawOperations.mapIndexedNotNull { index, element ->
            try {
              UI_BUILDER_JSON.decodeFromJsonElement(DesignMutationV1.serializer(), element)
            } catch (e: IllegalArgumentException) {
              findings +=
                shapeFinding(
                  "invalidMutation",
                  "`operations[$index]` is not a DesignMutationV1: ${e.message}",
                  index,
                )
              null
            }
          }
        else -> {
          findings += shapeFinding("invalidMutation", "`operations` must be an array of mutations")
          emptyList()
        }
      }
    // A batch that did not decode cannot be applied, so there is no edited document to look at.
    if (operations != null && findings.isNotEmpty()) document = null
    val baseRevision = args.number("baseRevision")
    if (
      operations != null &&
        baseRevision != null &&
        checkedRevision != null &&
        baseRevision != checkedRevision
    ) {
      findings +=
        UiBuilderCheckFindingV1(
          severity = SEVERITY_WARNING,
          check = CHECK_CATALOG,
          code = "revisionMismatch",
          message =
            "checked against the current revision $checkedRevision, not baseRevision " +
              "$baseRevision; an apply quoting $baseRevision is also checked for conflicts with " +
              "the edits in between",
        )
    }

    val validating = CHECK_SCHEMA in checks || CHECK_CATALOG in checks
    if (document != null && (validating || operations != null)) {
      val lane = validator
      if (lane == null) {
        if (validating) {
          checks
            .filter { it != CHECK_A11Y }
            .forEach { skipped += UiBuilderCheckSkippedV1(it, "this host cannot validate designs") }
        }
        if (operations != null) {
          // The operations cannot be applied without a scratch service, so what they would make is
          // unknown; checking the document as it stands would answer a question nobody asked.
          document = null
          if (CHECK_A11Y in checks) {
            skipped +=
              UiBuilderCheckSkippedV1(
                CHECK_A11Y,
                "this host cannot apply operations to a scratch copy; apply them, then check",
              )
          }
        }
      } else {
        val draft = lane.draft(actor, document, operations)
        if (validating) {
          findings +=
            draft.problems
              .map { problem ->
                UiBuilderCheckFindingV1(
                  severity = problem.severity,
                  check = if (problem.source == SOURCE_SHAPE) CHECK_SCHEMA else CHECK_CATALOG,
                  code = problem.code,
                  message = problem.message,
                  nodeId = problem.nodeId,
                  field = problem.field,
                  operationIndex = problem.operationIndex,
                )
              }
              .filter { it.check in checks }
        }
        if (operations != null) document = draft.document
        catalog = draft.catalog ?: catalog
      }
    }

    if (CHECK_A11Y in checks && skipped.none { it.check == CHECK_A11Y }) {
      val checked = document
      if (checked == null) {
        skipped +=
          UiBuilderCheckSkippedV1(
            CHECK_A11Y,
            "there is no document to check until the errors above are fixed",
          )
      } else {
        val components =
          (catalog ?: pinnedCatalog(checked, actor))
            ?.components
            ?.associateBy { it.componentId }
            .orEmpty()
        val bounds =
          if (!rendered) null
          else
            renderedBounds(checked).also {
              if (it == null) {
                skipped +=
                  UiBuilderCheckSkippedV1(
                    "$CHECK_A11Y.$RENDERED_ARGUMENT",
                    "no native render was available, so touch targets were checked from the " +
                      "sizes the document declares",
                  )
              }
            }
        findings += UiBuilderAccessibilityCheck.check(checked, components, bounds)
      }
    }

    val ordered = findings.sortedBy { SEVERITY_ORDER.indexOf(it.severity) }
    val errors = ordered.count { it.severity == SEVERITY_ERROR }
    val warnings = ordered.count { it.severity == SEVERITY_WARNING }
    val shown = ordered.take(MAX_CHECK_FINDINGS)
    return UI_BUILDER_JSON.encodeToString(
      UiBuilderDesignCheckV1.serializer(),
      UiBuilderDesignCheckV1(
        ok = errors == 0,
        summary = checkSummary(checks, errors, warnings, ordered, skipped),
        designId = designId ?: document?.id,
        revision = checkedRevision,
        dryRun = operations != null,
        checks = checks,
        errors = errors,
        warnings = warnings,
        findings = shown,
        truncated = ordered.size - shown.size,
        skipped = skipped,
      ),
    )
  }

  private fun checkSummary(
    checks: List<String>,
    errors: Int,
    warnings: Int,
    findings: List<UiBuilderCheckFindingV1>,
    skipped: List<UiBuilderCheckSkippedV1>,
  ): String {
    val ran = checks.filter { check -> skipped.none { it.check == check } }
    val skippedNote =
      if (skipped.isEmpty()) ""
      else " Not checked: ${skipped.joinToString("; ") { "${it.check} (${it.reason})" }}."
    if (findings.isEmpty()) {
      return "No problems found (${ran.joinToString(", ").ifEmpty { "nothing ran" }}).$skippedNote"
    }
    val headline = buildList {
      if (errors > 0) add("$errors error${if (errors == 1) "" else "s"}")
      if (warnings > 0) add("$warnings warning${if (warnings == 1) "" else "s"}")
      val infos = findings.size - errors - warnings
      if (infos > 0) add("$infos note${if (infos == 1) "" else "s"}")
    }
      .joinToString(", ")
    val examples =
      findings
        .groupBy { it.code }
        .entries
        .take(3)
        .joinToString("; ") { (code, group) ->
          val nodes = group.mapNotNull { it.nodeId }.distinct()
          code +
            (if (nodes.isEmpty()) "" else " on ${nodes.take(3).joinToString(", ") { "`$it`" }}") +
            (if (nodes.size > 3) " and ${nodes.size - 3} more" else "")
        }
    val verdict = if (errors > 0) "Fix before showing it" else "Shippable, with warnings"
    return "$verdict: $headline — $examples.$skippedNote"
  }

  private fun JsonObject.checksArgument(): List<String> {
    if (this[CHECKS_ARGUMENT] == null || this[CHECKS_ARGUMENT] is JsonNull) return DESIGN_CHECKS
    val asked = stringList(CHECKS_ARGUMENT)
    val unknown = asked.filter { it !in DESIGN_CHECKS }
    if (unknown.isNotEmpty() || asked.isEmpty()) {
      throw McpRequestException(
        "`$CHECKS_ARGUMENT` takes one or more of ${DESIGN_CHECKS.joinToString(", ")}" +
          (if (unknown.isEmpty()) "" else "; not ${unknown.joinToString(", ")}")
      )
    }
    return DESIGN_CHECKS.filter { it in asked }
  }

  /**
   * The catalog [document] pins, as this host serves it, or null when it serves no such catalog.
   */
  private suspend fun pinnedCatalog(
    document: DesignDocumentV1,
    actor: AuthenticatedUiBuilderActor,
  ): ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1? =
    (execute(ListCatalogsRequestV1, actor) as? UiBuilderServiceResponse.Catalogs)
      ?.catalogs
      ?.firstOrNull { it.benchmark.catalogSystemId == document.catalogPin.systemId }

  /** Node boxes from a native render of [document], in dp-convertible form, or null without one. */
  private fun renderedBounds(document: DesignDocumentV1): UiBuilderAccessibilityCheck.Rendered? {
    val lane = nativePreview ?: return null
    val outcome =
      try {
        lane.render(document)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        return null
      }
    val rendered = outcome as? UiBuilderNativePreviewOutcome.Rendered ?: return null
    if (rendered.nodeBounds.isEmpty()) return null
    val bytes =
      rendered.response.image?.removePrefix("data:image/png;base64,")?.let {
        runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
      } ?: return null
    val width = pngWidth(bytes) ?: return null
    val widthDp = document.environment.widthDp.takeIf { it > 0 } ?: return null
    return UiBuilderAccessibilityCheck.Rendered(
      pxPerDp = width / widthDp.toDouble(),
      boxes =
        rendered.nodeBounds.mapValues { (_, box) ->
          ServeUiBuilderView.Box(box.x, box.y, box.width, box.height)
        },
    )
  }

  /** A PNG's width from its IHDR, without decoding the picture. */
  private fun pngWidth(bytes: ByteArray): Int? {
    if (bytes.size < 24) return null
    return ((bytes[16].toInt() and 0xff) shl 24) or
      ((bytes[17].toInt() and 0xff) shl 16) or
      ((bytes[18].toInt() and 0xff) shl 8) or
      (bytes[19].toInt() and 0xff)
  }

  /**
   * One design on several devices, themes and font scales, as one contact sheet
   * (compose-preview-server#1255). See [UiBuilderDesignMatrix].
   *
   * Each cell is the design's own document with its environment swapped, exported to PNG through
   * the scratch lane — the stored design is read as this actor first and never written to.
   */
  private suspend fun renderDesignMatrix(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val designId = args.requiredText("designId")
    val snapshot =
      when (
        val response = execute(GetSnapshotRequestV1(designId, args.number("revision")), actor)
      ) {
        is UiBuilderServiceResponse.Snapshot -> response.snapshot
        is UiBuilderServiceResponse.Error -> throw McpRequestException(response.error.message)
        else -> throw McpRequestException("no design `$designId` this actor can read")
      }
    val document = snapshot.state.document
    val platform = snapshot.catalog.statusSemantics[PLATFORM_KEY]?.jsonPrimitive?.contentOrNull
    val devices = matrixDevices(args, platform)
    val themes =
      args.stringList(THEMES_ARGUMENT).ifEmpty {
        listOf(
          if (document.environment.theme == ThemeV1.DARK) UiBuilderDesignMatrix.THEME_DARK
          else UiBuilderDesignMatrix.THEME_LIGHT
        )
      }
    themes
      .firstOrNull {
        it != UiBuilderDesignMatrix.THEME_LIGHT && it != UiBuilderDesignMatrix.THEME_DARK
      }
      ?.let { throw McpRequestException("`$THEMES_ARGUMENT` takes `light` and `dark`, not `$it`") }
    val fontScales =
      when (val value = args[FONT_SCALES_ARGUMENT]) {
        null,
        is JsonNull -> listOf(document.environment.fontScale)
        is JsonArray ->
          value.map {
            (it as? JsonPrimitive)
              ?.takeIf { p -> !p.isString }
              ?.content
              ?.toDoubleOrNull()
              ?.takeIf { scale -> scale in MIN_FONT_SCALE..MAX_FONT_SCALE }
              ?: throw McpRequestException(
                "`$FONT_SCALES_ARGUMENT` takes numbers from $MIN_FONT_SCALE to $MAX_FONT_SCALE"
              )
          }
        else -> throw McpRequestException("`$FONT_SCALES_ARGUMENT` must be an array of numbers")
      }.ifEmpty { listOf(document.environment.fontScale) }
    val combinations = devices.flatMap { device ->
      themes.distinct().flatMap { theme -> fontScales.distinct().map { Triple(device, theme, it) } }
    }
    if (combinations.size > UiBuilderDesignMatrix.MAX_CELLS) {
      throw McpRequestException(
        "${combinations.size} cells (${devices.size} devices × ${themes.distinct().size} themes × " +
          "${fontScales.distinct().size} font scales) is more than " +
          "${UiBuilderDesignMatrix.MAX_CELLS}; ask for fewer"
      )
    }
    val variants = combinations.map { (device, theme, scale) ->
      document.copy(
        environment =
          document.environment.copy(
            widthDp = device.widthDp,
            heightDp = device.heightDp,
            theme = if (theme == UiBuilderDesignMatrix.THEME_DARK) ThemeV1.DARK else ThemeV1.LIGHT,
            fontScale = scale,
          )
      )
    }
    val pictures =
      validator?.exportPngs(actor, variants)
        ?: throw McpRequestException(
          "this host cannot render a design under another environment without saving it; use " +
            "$VIEW for the design as it is"
        )
    val cells = combinations.mapIndexed { index, (device, theme, scale) ->
      UiBuilderDesignMatrix.Cell(
        device,
        theme,
        scale,
        pictures.getOrNull(index)?.png,
        pictures.getOrNull(index)?.problem ?: "not rendered",
      )
    }
    val sheet = UiBuilderDesignMatrix.compose(cells)
    val failed = cells.count { it.png == null }
    val summary =
      "${cells.size} cells of `$designId` r${document.revision}: " +
        devices.joinToString(", ") { it.label } +
        (if (themes.distinct().size > 1) "; light and dark" else "") +
        (if (fontScales.distinct().size > 1)
          "; font ${fontScales.distinct().joinToString("/") { "${(it * 100).toInt()}%" }}"
        else "") +
        (if (failed > 0) ". $failed could not be rendered; see each cell's `problem`." else ".")
    return UI_BUILDER_JSON.encodeToString(
      UiBuilderDesignMatrixV1.serializer(),
      UiBuilderDesignMatrixV1(
        summary = summary,
        designId = designId,
        revision = document.revision,
        columns = sheet.columns,
        rows = sheet.rows,
        image =
          UiBuilderViewImageV1(
            widthPx = sheet.width,
            heightPx = sheet.height,
            scale = 1.0,
            sha256 = UiBuilderDesignMatrix.sha256(sheet.png),
          ),
        cells =
          cells.mapIndexed { index, cell ->
            val placed = sheet.placed[index]
            UiBuilderDesignMatrixCellV1(
              index = index,
              device = cell.device.id,
              label = cell.device.label,
              formFactor = cell.device.formFactor,
              widthDp = cell.device.widthDp,
              heightDp = cell.device.heightDp,
              round = cell.device.round,
              theme = cell.theme,
              fontScale = cell.fontScale,
              x = placed.x,
              y = placed.y,
              width = placed.width,
              height = placed.height,
              rendered = cell.png != null,
              problem = if (cell.png == null) cell.problem else null,
            )
          },
        imageBase64 = UiBuilderDesignMatrix.base64(sheet.png),
      ),
    )
  }

  /** The devices a matrix call asked for: by preset id, by size, by form factor, or by default. */
  private fun matrixDevices(
    args: JsonObject,
    platform: String?,
  ): List<UiBuilderDesignMatrix.Device> {
    val formFactor = args.text(FORM_FACTOR_ARGUMENT)
    val listed = args[DEVICES_ARGUMENT]?.takeIf { it !is JsonNull }
    if (listed != null && formFactor != null) {
      throw McpRequestException("pass `$DEVICES_ARGUMENT` or `$FORM_FACTOR_ARGUMENT`, not both")
    }
    if (listed == null) {
      val chosen = formFactor ?: UiBuilderDesignMatrix.defaultFormFactor(platform)
      val ids =
        UiBuilderDesignMatrix.FORM_FACTOR_DEFAULTS[chosen]
          ?: throw McpRequestException(
            "`$FORM_FACTOR_ARGUMENT` is one of ${UiBuilderDesignMatrix.FORM_FACTORS.joinToString(", ")}"
          )
      return ids.mapNotNull(UiBuilderDesignMatrix::preset)
    }
    val array =
      listed as? JsonArray
        ?: throw McpRequestException("`$DEVICES_ARGUMENT` must be an array of preset ids or sizes")
    if (array.isEmpty()) throw McpRequestException("`$DEVICES_ARGUMENT` names no device")
    return array.mapIndexed { index, element ->
      when {
        element is JsonPrimitive && element.isString ->
          UiBuilderDesignMatrix.preset(element.content)
            ?: throw McpRequestException(
              "`${element.content}` is not a device preset; presets are " +
                UiBuilderDesignMatrix.PRESETS.joinToString(", ") { it.id } +
                ", or pass {\"widthDp\":…,\"heightDp\":…}"
            )
        element is JsonObject -> {
          fun edge(name: String): Int =
            element[name]
              ?.jsonPrimitive
              ?.longOrNull
              ?.takeIf { it in 1..UiBuilderDesignMatrix.MAX_DEVICE_DP }
              ?.toInt()
              ?: throw McpRequestException(
                "`$DEVICES_ARGUMENT[$index].$name` must be an integer from 1 to " +
                  "${UiBuilderDesignMatrix.MAX_DEVICE_DP}"
              )
          val width = edge("widthDp")
          val height = edge("heightDp")
          val label = element.text("label") ?: "${width}×$height"
          UiBuilderDesignMatrix.Device(
            id = element.text("id") ?: "custom-$index",
            label = label,
            formFactor = element.text("formFactor") ?: "custom",
            widthDp = width,
            heightDp = height,
            round = element["round"]?.jsonPrimitive?.booleanOrNull == true,
          )
        }
        else ->
          throw McpRequestException("`$DEVICES_ARGUMENT[$index]` must be a preset id or a size")
      }
    }
  }

  /**
   * Review verdicts and the implementing pull request (compose-preview-server#1255). See
   * [ServeUiBuilderReviewStore].
   *
   * The design is read through the service as this actor first, so its own access control decides
   * whether there is a review here to see — the identical rule the comment tools follow — and
   * [SET_IMPLEMENTATION] additionally takes the design's own WRITE action, as [SET_LINKS] does.
   */
  private suspend fun reviewTool(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val store = reviews ?: throw McpRequestException("this host keeps no design reviews")
    val designId = args.requiredText("designId")
    val actions =
      service.designActions(actor, designId)
        ?: throw McpRequestException("no design `$designId` this actor can read")
    return when (tool) {
      RECORD_DECISION -> {
        val revision =
          args.number("revision")
            ?: throw McpRequestException(
              "`revision` is required: a verdict is about the revision that was looked at"
            )
        val result =
          store.decide(
            designId,
            actor.actorId,
            // Set by the server, never by an argument, for the reason a comment's author kind is:
            // everything reaching this class arrived over MCP, so the caller is an agent, and an
            // agent must not be able to record a person's approval.
            deciderKind = StoredComment.AUTHOR_KIND_AGENT,
            DecisionRequest(
              revision = revision,
              verdict = args.requiredText("verdict"),
              note = args.text("note"),
              decisionId = args.text("decisionId"),
              displayName = args.text("displayName"),
            ),
          )
        val stored = result.storedOrThrow()
        decisionReply(
          designId,
          stored.review,
          after = null,
          revision = null,
          kind = null,
          timedOut = false,
          recorded = stored.decision,
          replay = stored.replay,
        )
      }
      AWAIT_DECISION -> {
        val after = args.number("afterSequence") ?: 0
        if (after < 0) throw McpRequestException("`afterSequence` must not be negative")
        val revision = args.number("revision")
        val kind =
          when (val from = args.text(FROM_ARGUMENT) ?: FROM_HUMAN) {
            FROM_HUMAN -> StoredComment.AUTHOR_KIND_HUMAN
            FROM_ANYONE -> null
            else ->
              throw McpRequestException(
                "`$FROM_ARGUMENT` is `$FROM_HUMAN` or `$FROM_ANYONE`, not `$from`"
              )
          }
        val wait =
          (args.number("waitSeconds") ?: DEFAULT_DECISION_WAIT_SECONDS).coerceIn(
            0,
            MAX_DECISION_WAIT_SECONDS,
          )
        val review = store.awaitDecisionsAfter(designId, after, revision, kind, wait * 1000)
        decisionReply(
          designId,
          review ?: store.readOrEmpty(designId),
          after = after,
          revision = revision,
          kind = kind,
          timedOut = review == null,
          recorded = null,
          replay = false,
        )
      }
      SET_IMPLEMENTATION -> {
        if (!actions.contains(DesignAccessActionV1.WRITE)) {
          throw McpRequestException("design `$designId` does not grant this actor write access")
        }
        val previewMatch =
          (args[PREVIEW_MATCH_ARGUMENT] as? JsonObject)?.let {
            try {
              UI_BUILDER_JSON.decodeFromJsonElement(StoredPreviewMatch.serializer(), it)
            } catch (e: IllegalArgumentException) {
              throw McpRequestException(
                "`$PREVIEW_MATCH_ARGUMENT` is not {status, evidence?, note?}: ${e.message}"
              )
            }
          }
        val pr = args.text("pr")
        if (pr == null && args.keys.any { it != "designId" }) {
          throw McpRequestException("`pr` is required; pass only `designId` to clear the record")
        }
        val result =
          store.setImplementation(
            designId,
            actor.actorId,
            pr?.let {
              ImplementationRequest(
                pr = it,
                status = args.text("status"),
                revision = args.number("revision"),
                previewMatch = previewMatch,
              )
            },
          )
        val stored = result.storedOrThrow()
        UI_BUILDER_JSON.encodeToString(
          StoredDesignReview.serializer(),
          stored.review.copy(decisions = stored.review.decisions.takeLast(1)),
        )
      }
      IMPLEMENTATION_STATUS -> implementationStatus(store, designId, args, actor)
      else -> throw McpRequestException("unknown UI-builder review tool '$tool'")
    }
  }

  private fun ReviewWriteResult.storedOrThrow(): ReviewWriteResult.Stored =
    when (this) {
      is ReviewWriteResult.Stored -> this
      is ReviewWriteResult.Refused -> throw McpRequestException(reason)
      is ReviewWriteResult.Failed -> throw McpRequestException(reason)
    }

  /**
   * The decision reply: the cursor to quote next, the latest verdict that matches — whether or not
   * it is new, so a poll is answered without walking the log — and what arrived after the cursor.
   */
  private fun decisionReply(
    designId: String,
    review: StoredDesignReview,
    after: Long?,
    revision: Long?,
    kind: String?,
    timedOut: Boolean,
    recorded: StoredDesignDecision?,
    replay: Boolean,
  ): String {
    val matching =
      review.decisions.filter {
        (revision == null || it.revision == revision) && (kind == null || it.deciderKind == kind)
      }
    val fresh = if (after == null) emptyList() else matching.filter { it.sequence > after }
    val latest = recorded ?: matching.lastOrNull()
    val summary =
      when {
        recorded != null ->
          "${if (replay) "Already recorded" else "Recorded"}: ${recorded.verdict} of revision " +
            "${recorded.revision} by ${recorded.decidedBy}."
        timedOut ->
          "No new decision" +
            (if (revision != null) " on revision $revision" else "") +
            (if (kind != null) " from a person" else "") +
            " yet; call again with afterSequence ${after ?: review.sequence}." +
            (latest?.let { " The latest is ${it.verdict} of revision ${it.revision}." } ?: "")
        else ->
          "${fresh.size} new decision${if (fresh.size == 1) "" else "s"}; latest: " +
            "${latest?.verdict} of revision ${latest?.revision} by ${latest?.decidedBy}" +
            (latest?.note?.let { " — \"${it.take(120)}\"" } ?: "") +
            "."
      }
    return UI_BUILDER_JSON.encodeToString(
      UiBuilderDecisionReplyV1.serializer(),
      UiBuilderDecisionReplyV1(
        summary = summary,
        designId = designId,
        timedOut = timedOut,
        // Past every decision this reply reports, so quoting it never returns the same one twice.
        sequence =
          maxOf(after ?: 0, fresh.maxOfOrNull { it.sequence } ?: 0, recorded?.sequence ?: 0),
        latest = latest,
        decisions = fresh.takeLast(MAX_DECISIONS_IN_REPLY),
        approved = latest?.verdict == ServeUiBuilderReviewStore.VERDICT_APPROVE,
      ),
    )
  }

  /**
   * What the code side needs to implement a design, in one call: the revision, the links, the
   * implementation record and its preview match, the latest verdict, and — unless asked not to —
   * the Compose export (compose-preview-server#1255).
   */
  private suspend fun implementationStatus(
    store: ServeUiBuilderReviewStore,
    designId: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val snapshot =
      when (
        val response = execute(GetSnapshotRequestV1(designId, args.number("revision")), actor)
      ) {
        is UiBuilderServiceResponse.Snapshot -> response.snapshot
        is UiBuilderServiceResponse.Error -> throw McpRequestException(response.error.message)
        else -> throw McpRequestException("no design `$designId` this actor can read")
      }
    val revision = snapshot.state.document.revision
    val review = store.readOrEmpty(designId)
    val stored = runCatching { links?.read(designId) }.getOrNull()
    val includeExport = args[INCLUDE_EXPORT_ARGUMENT]?.jsonPrimitive?.booleanOrNull != false
    val export =
      if (!includeExport) null
      else
        when (
          val response =
            execute(ExportDesignRequestV1(designId, revision, ExportFormatV1.COMPOSE), actor)
        ) {
          is UiBuilderServiceResponse.Export ->
            UiBuilderImplementationExportV1(
              format = "compose",
              ok = response.artifact.diagnostics.none { it.severity == DiagnosticSeverityV1.ERROR },
              diagnostics =
                response.artifact.diagnostics
                  .map { "${it.severity.name.lowercase()} ${it.code}: ${it.message}" }
                  .take(MAX_CHECK_FINDINGS),
              source = response.artifact.text(),
            )
          is UiBuilderServiceResponse.Error ->
            UiBuilderImplementationExportV1(
              format = "compose",
              ok = false,
              diagnostics = listOf(response.error.message),
            )
          else -> null
        }
    val implementation = review.implementation
    val latest = review.decisions.lastOrNull { it.revision == revision }
    val pr = implementation?.pr ?: stored?.pr
    val stale = implementation?.revision?.let { it != revision } == true
    val summary = buildString {
      append("`$designId` r$revision")
      append(
        when (latest?.verdict) {
          ServeUiBuilderReviewStore.VERDICT_APPROVE -> ", approved by ${latest.decidedBy}"
          ServeUiBuilderReviewStore.VERDICT_REJECT -> ", rejected by ${latest.decidedBy}"
          else -> ", no decision on this revision"
        }
      )
      if (pr == null) append("; no implementation PR recorded")
      else {
        append("; PR $pr")
        implementation?.let { append(" (${it.status})") }
        if (stale) append(" implements r${implementation.revision}, not this revision")
        append(
          when (implementation?.previewMatch?.status) {
            "match" -> "; previews match the design"
            "mismatch" -> "; previews do NOT match the design"
            else -> "; preview match unknown"
          }
        )
      }
      export?.let {
        append(if (it.ok) "; the Compose export is clean" else "; the Compose export has errors")
      }
      append(".")
    }
    return UI_BUILDER_JSON.encodeToString(
      UiBuilderImplementationStatusV1.serializer(),
      UiBuilderImplementationStatusV1(
        summary = summary,
        designId = designId,
        revision = revision,
        links = stored,
        implementation = implementation,
        implementsRevision = !stale && implementation != null,
        latestDecision = latest,
        export = export,
      ),
    )
  }

  /**
   * From a pull request back to the designs it implements — through the implementation record and
   * the links record both — with every id read as this actor before it is named, so the answer
   * never says a design exists to somebody who cannot open it.
   */
  private suspend fun findDesignForPr(
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
  ): String {
    val pr = args.requiredText("pr").trim()
    val candidates =
      (runCatching { reviews?.implementedBy(pr) }.getOrNull().orEmpty() +
          runCatching { links?.citingPr(pr) }.getOrNull().orEmpty())
        .distinct()
        .sorted()
    val readable = candidates.filter { service.canRead(actor, it) }
    val designs = readable.map { designId ->
      val implementation = runCatching { reviews?.read(designId)?.implementation }.getOrNull()
      UiBuilderPrDesignV1(
        designId = designId,
        status = implementation?.takeIf { it.pr == pr }?.status,
        revision = implementation?.takeIf { it.pr == pr }?.revision,
      )
    }
    return UI_BUILDER_JSON.encodeToString(
      UiBuilderPrLookupV1.serializer(),
      UiBuilderPrLookupV1(
        summary =
          if (designs.isEmpty()) "No design you can read names $pr."
          else
            "${designs.size} design${if (designs.size == 1) "" else "s"} for $pr: " +
              designs.joinToString(", ") { "`${it.designId}`" } +
              ".",
        pr = pr,
        designs = designs,
      ),
    )
  }

  private suspend fun execute(
    request: UiBuilderRequestV1,
    actor: AuthenticatedUiBuilderActor,
  ): UiBuilderServiceResponse =
    when (val mapping = UiBuilderProtocolMapper.toServiceCall(actor, request)) {
      is ProtocolRequestMapping.Mapped ->
        try {
          service.shapeForReader(actor, service.execute(mapping.call))
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (_: Exception) {
          UiBuilderServiceResponse.Error(
            ee.schimke.composeai.uibuilder.service.UiBuilderServiceError(
              ServiceErrorCodeV1.INTERNAL,
              "UI-builder service failed",
              retryable = true,
            )
          )
        }
      is ProtocolRequestMapping.Rejected -> UiBuilderServiceResponse.Error(mapping.error)
    }

  /**
   * The released reply envelope, and — unless the caller asked otherwise — without the catalog a
   * snapshot embeds.
   *
   * A `ServiceSnapshotV1` carries the whole `CatalogCapabilityV1` of the catalog the design pins,
   * which is right for a browser (one fetch, kept for the session, and the palette needs it) and
   * wrong for an agent, for whom every byte is conversation context spent per call: on the hosted
   * deployment a 600-byte document came back as 60 KB. The document's `catalogPin` names the
   * catalog exactly and [LIST_CATALOGS] serves it, so nothing is lost by leaving it out; the field
   * is dropped from the JSON rather than blanked, so a reader sees an absence and not an empty
   * catalog. `includeCatalog: true` restores the released shape byte for byte.
   */
  private fun envelope(
    callId: String,
    response: UiBuilderServiceResponse,
    includeCatalog: Boolean = true,
  ): String {
    val envelope =
      UI_BUILDER_JSON.encodeToJsonElement(
        McpResponseEnvelopeV1.serializer(),
        McpResponseEnvelopeV1(
          callId = callId,
          response = UiBuilderProtocolMapper.toProtocolResponse(response),
        ),
      )
    return (if (includeCatalog) envelope else envelope.withoutCatalog()).toString()
  }

  private fun JsonObject.includeCatalog(): Boolean =
    this[INCLUDE_CATALOG_ARGUMENT]?.jsonPrimitive?.booleanOrNull == true

  private fun JsonObject.text(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.requiredText(name: String): String =
    text(name) ?: throw McpRequestException("`$name` is required")

  private fun JsonObject.number(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

  private fun JsonObject.requiredNumber(name: String): Long =
    number(name) ?: throw McpRequestException("`$name` is required and must be an integer")

  private fun JsonObject.requiredDocument(): DesignDocumentV1 =
    decodeRequired("document", DesignDocumentV1.serializer(), "DesignDocumentV1")

  private fun JsonObject.requiredHome(name: String): DesignHomeV1 =
    decodeRequired(name, DesignHomeV1.serializer(), "DesignHomeV1")

  private fun JsonObject.requiredNullableHome(name: String): DesignHomeV1? {
    val value =
      this[name] ?: throw McpRequestException("`$name` is required (use null for no home)")
    if (value is JsonNull) return null
    return decodeRequired(name, DesignHomeV1.serializer(), "DesignHomeV1")
  }

  private fun <T> JsonObject.decodeRequired(
    name: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    typeName: String,
  ): T =
    try {
      UI_BUILDER_JSON.decodeFromJsonElement(
        serializer,
        this[name] ?: throw McpRequestException("`$name` is required"),
      )
    } catch (failure: SerializationException) {
      throw McpRequestException("`$name` is not a $typeName: ${failure.message}")
    }

  /** A frame fraction; `0.5` never survives [number], and an anchor is written in fractions. */
  private fun JsonObject.decimal(name: String): Float? = this[name]?.jsonPrimitive?.floatOrNull

  private fun JsonObject.exportFormat(): ExportFormatV1 {
    val requested = text("format") ?: return ExportFormatV1.COMPOSE
    return ExportFormatV1.entries.firstOrNull { it.name.equals(requested, ignoreCase = true) }
      ?: throw McpRequestException(
        "unknown export format '$requested'; this server knows " +
          ExportFormatV1.entries.joinToString(", ") { it.name.lowercase() }
      )
  }

  /** `editor` or `viewer`; an omitted role shares the design read-only, the safer default. */
  private fun JsonObject.accessRole(): DesignAccessRoleV1 {
    val requested = text("role") ?: return DesignAccessRoleV1.VIEWER
    return when (requested.lowercase()) {
      "editor" -> DesignAccessRoleV1.EDITOR
      "viewer" -> DesignAccessRoleV1.VIEWER
      // Deliberately not offered: ownership is a transfer, not a share, and the service says so
      // too — a grant naming the owner role is refused there rather than quietly downgraded.
      "owner" ->
        throw McpRequestException(
          "a design has one owner and sharing does not change it; share as 'editor' or 'viewer'"
        )
      else -> throw McpRequestException("unknown role '$requested'; use 'editor' or 'viewer'")
    }
  }

  companion object {
    /**
     * How many allowed values a summary row spells out before it reports a count instead.
     *
     * The summary exists to be small — "the released envelope is about 58 KB and this about 12", as
     * `ServeUiBuilderMcpIntegrationTest` puts it — and a `|`-separated enumeration is the one field
     * on it whose length the catalog, not this surface, decides. #710 exposed the complete Material
     * icon inventory and `m3/icon`.`iconKey` became 11,431 names, which took the *summary* to 241
     * KB: four times the full envelope it exists to be an alternative to, on every
     * `ui_builder_list_catalogs` call.
     *
     * Twenty-four is chosen to be past every enumeration a person authored — the longest of those
     * on the packaged m3 catalog is `m3/text`.`style`'s fifteen typography roles — and far short of
     * a generated inventory. A row over it says how many values there are and where to get them,
     * which is what the summary/full split is for: an agent that needs all 11,431 asks for `full`,
     * and the other ninety-nine calls do not pay for them.
     */
    private const val SUMMARY_ALLOWED_VALUES = 24

    const val LIST_CATALOGS = "ui_builder_list_catalogs"
    const val SEARCH_COMPONENTS = "ui_builder_search_components"
    const val LIST_DESIGNS = "ui_builder_list_designs"
    const val GET_DESIGN = "ui_builder_get_design"
    const val PREVIEW_CATALOG_RECOVERY = "ui_builder_preview_catalog_recovery"
    const val CREATE_DESIGN = "ui_builder_create_design"
    const val APPLY = "ui_builder_apply"
    const val MOVE_DESIGN_HOME = "ui_builder_move_design_home"
    const val DRY_RUN_ARGUMENT = "dryRun"
    const val DECISION_SCHEMA = "compose-preview-decision/v1"
    /** The form field an elicited R3 decision's option id comes back in. */
    const val DECISION_CHOICE_FIELD = "choice"
    /** The optional form field naming a new design, where `create-new` is offered. */
    const val DECISION_NEW_DESIGN_ID_FIELD = "newDesignId"
    /** How long a decision form may wait; the request scope caps it further. */
    private const val DECISION_ELICITATION_TIMEOUT_MILLIS = 2 * 60 * 1000L
    private const val MAX_ELICITED_DESIGN_ID = 200
    private const val DRY_RUN_SCHEMA =
      """{"type":"boolean","description":"Validate and return the person's choices without changing anything."}"""
    const val REPLACE_DESIGN_DOCUMENT = "ui_builder_replace_design_document"
    const val EXPORT = "ui_builder_export"
    const val EXPORT_DOCUMENT = "ui_builder_export_document"
    const val RENDER_NATIVE = "ui_builder_render_native"
    const val PUT_ASSET = "ui_builder_put_asset"
    const val VIEW = "ui_builder_view"

    /** The argument [PUT_ASSET] carries the picture in. */
    const val ASSET_BYTES_ARGUMENT = "imageBase64"
    const val LIST_COMMENTS = "ui_builder_list_comments"
    const val POST_COMMENT = "ui_builder_post_comment"
    const val RESOLVE_COMMENT_THREAD = "ui_builder_resolve_comment_thread"
    const val AWAIT_COMMENTS = "ui_builder_await_comments"
    const val ACKNOWLEDGE_COMMENT = "ui_builder_acknowledge_comment"
    const val REACT_TO_COMMENT = "ui_builder_react_to_comment"
    const val AWAIT_DESIGN = "ui_builder_await_design"
    const val DESIGN_ACCESS = "ui_builder_design_access"
    const val GET_LINKS = "ui_builder_get_links"
    const val SET_LINKS = "ui_builder_set_links"
    const val SHARE_DESIGN = "ui_builder_share_design"
    const val RENAME_DESIGN = "ui_builder_rename_design"
    const val DELETE_DESIGN = "ui_builder_delete_design"
    const val VALIDATE = "ui_builder_validate"

    const val CHECK_DESIGN = "ui_builder_check_design"
    const val RENDER_DESIGN_MATRIX = "ui_builder_render_design_matrix"
    const val RECORD_DECISION = "ui_builder_record_decision"
    const val AWAIT_DECISION = "ui_builder_await_decision"
    const val SET_IMPLEMENTATION = "ui_builder_set_implementation"
    const val IMPLEMENTATION_STATUS = "ui_builder_implementation_status"
    const val FIND_DESIGN_FOR_PR = "ui_builder_find_design_for_pr"

    private const val RENDERED_ARGUMENT = "rendered"
    private const val INCLUDE_EXPORT_ARGUMENT = "includeExport"
    private const val CHECKS_ARGUMENT = "checks"
    private const val DEVICES_ARGUMENT = "devices"
    private const val FORM_FACTOR_ARGUMENT = "formFactor"
    private const val THEMES_ARGUMENT = "themes"
    private const val FONT_SCALES_ARGUMENT = "fontScales"
    private const val PREVIEW_MATCH_ARGUMENT = "previewMatch"
    private const val FROM_ARGUMENT = "from"
    private const val FROM_HUMAN = "human"
    private const val FROM_ANYONE = "anyone"
    private const val MIN_FONT_SCALE = 0.5
    private const val MAX_FONT_SCALE = 3.0

    /** How long a decision watcher waits by default, and the most it may ask for. */
    private const val DEFAULT_DECISION_WAIT_SECONDS = 25L
    private const val MAX_DECISION_WAIT_SECONDS = 120L
    private const val MAX_DECISIONS_IN_REPLY = 10

    /** Enough findings to fix in one pass; the counts still include the rest. */
    private const val MAX_CHECK_FINDINGS = 40
    private val SEVERITY_ORDER = listOf(SEVERITY_ERROR, SEVERITY_WARNING, SEVERITY_INFO)

    private const val REVOKE_ARGUMENT = "revoke"
    private const val INCLUDE_CATALOG_ARGUMENT = "includeCatalog"
    private const val FULL_ARGUMENT = "full"
    private const val QUERY_ARGUMENT = "query"
    private const val CATALOG_ARGUMENT = "catalog"
    private const val COMPONENT_IDS_ARGUMENT = "componentIds"
    private const val INCLUDE_ARGUMENT = "include"
    private const val SELECTION_ARGUMENT = "selection"
    private const val VIEWPORT_ARGUMENT = "viewport"
    private const val RENDERER_ARGUMENT = "renderer"

    /** The argument that asks [VIEW] for the picture's bytes in the reply instead of a link. */
    const val INLINE_ARGUMENT = "inline"

    /** Closed, discriminated DesignHomeV1 schema shared by both mutation arguments. */
    private const val DESIGN_HOME_SCHEMA =
      """{"oneOf":[{"type":"object","properties":{"kind":{"const":"server"},"url":{"type":"string","minLength":1},"designId":{"type":"string","minLength":1}},"required":["kind","url","designId"],"additionalProperties":false},{"type":"object","properties":{"kind":{"const":"repo"},"path":{"type":"string","minLength":1}},"required":["kind","path"],"additionalProperties":false}]}"""

    /** The `statusSemantics` key a catalog declares its platform under; the runtime's own. */
    private const val PLATFORM_KEY = "platform"

    /**
     * Compact on purpose: an absent list and an absent pin are left out rather than written as `[]`
     * and `null`, since the summary exists to be small. `schema` is kept so a reader can tell the
     * shape apart from the released envelope.
     */
    private val SUMMARY_JSON = Json {
      encodeDefaults = false
      explicitNulls = false
    }

    /**
     * What a shared role may do.
     *
     * An editor gets the three actions a person editing a design uses; a viewer gets the two that
     * only read — `export` included, because a design's exported Kotlin is a rendering of what the
     * viewer is already looking at, and withholding it would make a shared design unusable to the
     * one audience it was shared with. Neither carries `manageAccess` or `delete`: those stay with
     * the owner, so being shared with never becomes the power to share on.
     */
    private fun DesignAccessRoleV1.defaultActions(): List<DesignAccessActionV1> =
      when (this) {
        DesignAccessRoleV1.EDITOR ->
          listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE, DesignAccessActionV1.EXPORT)
        DesignAccessRoleV1.VIEWER -> listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT)
        DesignAccessRoleV1.OWNER -> DesignAccessActionV1.entries
      }

    /** Every tool this class answers to, in the order a session naturally uses them. */
    val TOOL_NAMES =
      listOfNotNull(
        LIST_CATALOGS,
        SEARCH_COMPONENTS,
        LIST_DESIGNS,
        GET_DESIGN,
        PREVIEW_CATALOG_RECOVERY,
        AWAIT_DESIGN,
        CREATE_DESIGN,
        APPLY,
        MOVE_DESIGN_HOME,
        REPLACE_DESIGN_DOCUMENT,
        EXPORT,
        EXPORT_DOCUMENT.takeIf { RemoteDocumentExportSupport.formats.isNotEmpty() },
        VIEW,
        CHECK_DESIGN,
        DESIGN_ACCESS,
        SHARE_DESIGN,
        RENAME_DESIGN,
        DELETE_DESIGN,
      ) + ServeUiBuilderHistoryTools.TOOL_NAMES

    /** Kept separate for callers that group native-render capabilities. */
    val NATIVE_TOOL_NAMES = listOf(RENDER_NATIVE)

    /** Stable refusal code returned when the host advertises the tool but cannot compile. */
    const val NATIVE_RENDER_UNAVAILABLE = "NATIVE_RENDER_UNAVAILABLE"

    /** Separate because they exist only where the host's service keeps design branches. */
    val BRANCH_TOOL_NAMES = ServeUiBuilderBranchTools.TOOL_NAMES

    /** Separate because it exists only where the host keeps design assets. */
    val ASSET_TOOL_NAMES = listOf(PUT_ASSET)

    /** Separate because they exist only where the host records what a design is for. */
    val LINKS_TOOL_NAMES = listOf(GET_LINKS, SET_LINKS)

    /** Separate because it exists only where the host can open a scratch service. */
    val VALIDATE_TOOL_NAMES = listOf(VALIDATE, RENDER_DESIGN_MATRIX)

    /** Separate because they exist only where the host records review decisions. */
    val REVIEW_TOOL_NAMES =
      listOf(
        RECORD_DECISION,
        AWAIT_DECISION,
        SET_IMPLEMENTATION,
        IMPLEMENTATION_STATUS,
        FIND_DESIGN_FOR_PR,
      )

    /** Separate because they exist only where the host keeps a discussion. */
    val COMMENT_TOOL_NAMES =
      listOf(
        LIST_COMMENTS,
        POST_COMMENT,
        ACKNOWLEDGE_COMMENT,
        REACT_TO_COMMENT,
        RESOLVE_COMMENT_THREAD,
        AWAIT_COMMENTS,
      )

    /**
     * The replies that carry [CommentNoticeV1] when the actor has a thread waiting on them.
     *
     * Every one of them is a moment where an agent is demonstrably reading or writing this design's
     * state, which is exactly when a comment about it is worth knowing. The comment tools
     * themselves are left out: they answer with the board, so a notice beside it would be the same
     * news twice.
     */
    val COMMENT_NOTICE_TOOLS =
      setOf(
        GET_DESIGN,
        APPLY,
        MOVE_DESIGN_HOME,
        REPLACE_DESIGN_DOCUMENT,
        EXPORT,
        RENDER_NATIVE,
        PUT_ASSET,
        AWAIT_DESIGN,
      )

    private const val DEFAULT_DESIGN_PAGE = 50

    /** How long a design watcher waits by default, and the most it may ask for. */
    private const val DEFAULT_DESIGN_WAIT_SECONDS = 25L

    private const val MAX_DESIGN_WAIT_SECONDS = 120L
    private const val MCP_CLIENT_ID = "mcp"
    /** What a links call may carry besides a link: the design it is about, and nothing else. */
    private val LINKS_CALL_KEYS = setOf("designId")

    /** The envelope field carrying the protocol response, and the response's own type tag. */
    private const val RESPONSE_KEY = "response"

    private const val RESPONSE_TYPE_KEY = "type"

    /** `SnapshotResponseV1`'s `@SerialName`: the one response that means "here is the design". */
    private const val SNAPSHOT_RESPONSE_TYPE = "snapshot"

    /** The key [CommentNoticeV1] is spliced onto a reply under. */
    internal const val COMMENTS_NOTICE_KEY = "comments"

    /** The exact pending-discussion count attached to successful [GET_DESIGN] replies. */
    internal const val UNACKNOWLEDGED_COMMENTS_KEY = "unacknowledgedComments"

    /** The key [StoredLinks] is spliced onto a $GET_DESIGN reply under. */
    internal const val LINKS_KEY = "links"

    /**
     * Tool declarations, built with the caller's own `tool` helper so this list has the same shape
     * as every other tool on the surface rather than a second one that drifts.
     */
    fun declarations(
      tool: (String, String, String) -> JsonObject,
      native: Boolean = false,
      comments: Boolean = false,
      assets: Boolean = false,
      links: Boolean = false,
      validate: Boolean = false,
      reviews: Boolean = false,
      branches: Boolean = false,
    ): List<JsonObject> =
      listOfNotNull(
        tool(
          LIST_CATALOGS,
          "List the component catalogs a UI-builder design can pin to. Start here: a design's " +
            "`catalogPin` must name a revision this server actually serves, and each catalog's " +
            "`catalogPin` here is exactly that. By default a summary of what authoring needs, a " +
            "few KB rather than the whole capability: per component its id, role, traits, " +
            "`slots` as `name[min..max]:accepted|roles` and `properties` as `name:type`, with " +
            "`!` when required and `=a|b` listing the allowed values; per catalog its export " +
            "formats and the modifier vocabulary. `full: true` returns the released " +
            "CatalogsResponseV1 envelope with adapter status, parity and export notes per " +
            "component; `$COMPONENT_IDS_ARGUMENT` narrows either to the components you are " +
            "about to use.",
          """
          {"type":"object","properties":{
            "$FULL_ARGUMENT":{"type":"boolean","description":"The whole CatalogCapabilityV1 per catalog, as the released envelope. Defaults to false."},
            "$COMPONENT_IDS_ARGUMENT":{"type":"array","items":{"type":"string"},"description":"Only these components. Omit for all of them."}
          },"additionalProperties":false}
          """,
        ),
        tool(
          SEARCH_COMPONENTS,
          "Find catalog components by name, role or trait (case-insensitive substring), e.g. " +
            "`TextField`, `Button`, `Card`. Returns the same summary as $LIST_CATALOGS " +
            "(id, role, traits, slots, properties, and the catalog pin) for only the matches, " +
            "so you don't pay for the whole catalog.",
          """
          {"type":"object","properties":{
            "$QUERY_ARGUMENT":{"type":"string","description":"Text to look for in a component's id, role or traits."},
            "$CATALOG_ARGUMENT":{"type":"string","description":"Only this catalog system id. Omit to search every catalog."}
          },"required":["$QUERY_ARGUMENT"],"additionalProperties":false}
          """,
        ),
        tool(
          LIST_DESIGNS,
          "List the UI-builder designs on this server, newest first, with the cursor to continue.",
          """
          {"type":"object","properties":{
            "cursor":{"type":"string","description":"Continue a previous page."},
            "limit":{"type":"integer","description":"Designs per page. Defaults to $DEFAULT_DESIGN_PAGE."}
          },"additionalProperties":false}
          """,
        ),
        tool(
          GET_DESIGN,
          "Read one design: its whole document — nodes, slots, properties, modifiers, state " +
            "variables and catalog pin — plus the revision to quote as `baseRevision` when " +
            "editing it. The catalog the design pins is left out unless `$INCLUDE_CATALOG_ARGUMENT` " +
            "is true: it is the same for every design on the pin, $LIST_CATALOGS serves it, and " +
            "it is most of the bytes. On hosts with design discussions, " +
            "`$UNACKNOWLEDGED_COMMENTS_KEY` is the number of comment threads this actor has not " +
            "acknowledged; a nonzero count also carries the bounded `comments` notice.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer","description":"A past revision. Omit for the current one."},
            "$INCLUDE_CATALOG_ARGUMENT":{"type":"boolean","description":"Embed the pinned catalog's whole CatalogCapabilityV1 in the snapshot, as the released shape does. Defaults to false."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          PREVIEW_CATALOG_RECOVERY,
          "Dry-run recovery of a design whose stored catalog pin no longer resolves. The server " +
            "selects the exact currently served pin for the same catalog system and returns a " +
            "CatalogUpgradePreviewV1 with changes, issues and the hashes required by a later " +
            "`upgradeCatalog` mutation through $APPLY. This call never writes.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          AWAIT_DESIGN,
          "Wait for somebody else to change a design and return what they changed, rather than " +
            "asking again whether they have. Returns the moment a designer in the browser or " +
            "another agent commits an edit, as the same update frame the browser's own live " +
            "socket receives; returns a `timedOut` reply if nothing happens within `waitSeconds`, " +
            "which you answer by calling again with the same cursor. Quote as `afterSequence` the " +
            "`throughSequence` of the delta you last received, or the `lastSequence` of the last " +
            "snapshot; a cursor the server no longer retains is answered with a whole snapshot " +
            "instead of the operations you missed. `waitSeconds: 0` checks without blocking. " +
            "Nothing is lost between calls — the cursor is replayed when you call again — so " +
            "somebody merely looking at the design does not wake you, and only a committed " +
            "change does.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "afterSequence":{"type":"integer","description":"The `lastSequence` you last saw, from $GET_DESIGN or a previous wait."},
            "waitSeconds":{"type":"integer","description":"Up to $MAX_DESIGN_WAIT_SECONDS. Defaults to $DEFAULT_DESIGN_WAIT_SECONDS."},
            "$INCLUDE_CATALOG_ARGUMENT":{"type":"boolean","description":"When the reply is a whole snapshot, embed the pinned catalog in it. Defaults to false."}
          },"required":["designId","afterSequence"],"additionalProperties":false}
          """,
        ),
        tool(
          CREATE_DESIGN,
          "Create a design, either from a whole `document` you supply or by copying an existing " +
            "design named by `fromDesignId`. Copying is usually right: a document's `catalogPin` " +
            "must match a catalog revision this server serves, and a copy carries one that does. " +
            "A `document` whose `home` is an existing design on this server is refused with a " +
            "`$DECISION_SCHEMA` choice to put to the person — or, when your client supports " +
            "form elicitation on this connection, the person is asked in a form and the call " +
            "acts on their answer.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"The id for the new design."},
            "title":{"type":"string"},
            "document":{"type":"object","description":"A whole DesignDocumentV1."},
            "fromDesignId":{"type":"string","description":"Copy this design's document instead."},
            "$INCLUDE_CATALOG_ARGUMENT":{"type":"boolean","description":"Embed the pinned catalog in the returned snapshot. Defaults to false."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          APPLY,
          "Apply design mutations — insertNode, setProperty, deleteNode, moveNode and the rest of " +
            "DesignMutationV1 — as one operation. `baseRevision` is the revision you read; " +
            "the outcome reports conflicts or rejected edits. This " +
            "is how an agent adds a scaffold, fills its slots and sets modifiers. " +
            "Use `setStateVariable` with `name` and `declaration` to add or edit state, " +
            "`removeStateVariable` with `name` to remove unused state, and `setEventBinding` " +
            "with `nodeId`, `event` and an ordered `actions` array to edit behavior. " +
            "An empty actions array removes the event handler. " +
            (if (UiBuilderBuildFeatures.remoteCompose)
              "These are the same edits as Screen > State and Properties > Actions in the browser. "
            else "") +
            "`removeNodeProperty` (or a setProperty whose value is `{\"type\":\"null\"}`) " +
            "unsets the property — the way back after trying one — and is refused, naming the " +
            "node and the field, when the catalog requires it. When somebody has commented on " +
            "the design and you have not acknowledged it, the outcome carries a `comments` " +
            "block naming the threads waiting on you; read it, because it is somebody talking " +
            "about what you are editing.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "operationId":{"type":"string","description":"Your id for this operation; makes a retry idempotent."},
            "baseRevision":{"type":"integer","description":"The revision these mutations were written against."},
            "clientId":{"type":"string"},
            "operations":{"type":"array","items":{"type":"object"},"description":"DesignMutationV1 objects. State example: {\"type\":\"setStateVariable\",\"name\":\"expanded\",\"declaration\":{\"type\":\"value\",\"valueType\":\"bool\",\"initialValue\":false,\"nullable\":false,\"persistence\":\"preview\"}}. Event example: {\"type\":\"setEventBinding\",\"nodeId\":\"button\",\"event\":\"click\",\"actions\":[{\"type\":\"toggle\",\"variable\":\"expanded\"}]}. Declare state before binding it in the batch."}
          },"required":["designId","operationId","baseRevision","operations"],"additionalProperties":false}
          """,
        ),
        tool(
          MOVE_DESIGN_HOME,
          "Move a design's canonical home between this server and a repository checkout. This " +
            "changes real authoritative state: quote the exact `baseRevision` and current " +
            "`sourceHome` from $GET_DESIGN, provide a stable `operationId`, and name the new " +
            "`targetHome`. The old server record remains as a retained copy pointing at the new " +
            "home. The reply is an idempotent operation outcome with the new revision; a stale " +
            "revision or changed source home is refused rather than overwriting a concurrent move. " +
            "Unless the person already chose this move, call first with `dryRun: true`: it " +
            "changes nothing and returns the choices to put to them — or, when your client " +
            "supports form elicitation on this connection, asks them in a form and, if they " +
            "choose to move, returns this move's outcome.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "operationId":{"type":"string","description":"Your stable id; makes a retry idempotent."},
            "baseRevision":{"type":"integer","description":"The exact current revision read from the design."},
            "sourceHome":{"description":"The exact current DesignHomeV1, or null when the design is unhomed.","anyOf":[{"type":"null"},$DESIGN_HOME_SCHEMA]},
            "targetHome":$DESIGN_HOME_SCHEMA,
            "dryRun":$DRY_RUN_SCHEMA
          },"required":["designId","operationId","baseRevision","sourceHome","targetHome"],"additionalProperties":false}
          """,
        ),
        tool(
          REPLACE_DESIGN_DOCUMENT,
          "Replace one stored design from a complete DesignDocumentV1 copy — the authoritative " +
            "save-back and re-import operation. The document must name the same design and the " +
            "same canonical home you read from $GET_DESIGN; `baseRevision` must still be current. " +
            "The runtime validates the complete document and quotas, preserves server-owned " +
            "identity, access and creation time, retains the old revision, and broadcasts a " +
            "whole snapshot. Retry with the same `operationId`; never invent a new id after a " +
            "lost response. Unless the person already chose to save back or re-import onto " +
            "this home, call first with `dryRun: true`: it changes nothing and returns the " +
            "choices to put to them — or, when your client supports form elicitation on this " +
            "connection, asks them in a form and returns the outcome of the write they chose.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "operationId":{"type":"string","description":"Your stable id; makes a retry idempotent."},
            "baseRevision":{"type":"integer","description":"The exact current revision being replaced."},
            "document":{"type":"object","description":"The complete replacement DesignDocumentV1, including the existing home."},
            "dryRun":$DRY_RUN_SCHEMA
          },"required":["designId","operationId","baseRevision","document"],"additionalProperties":false}
          """,
        ),
        tool(
          EXPORT,
          "Export a design. `compose` returns the Kotlin the generator writes, or — when the " +
            "design holds something it cannot express — diagnostics naming each reason. This is " +
            "the same gate the browser's code pane shows, so an agent and a designer get the " +
            "same answer about the same design.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer"},
            "format":{"type":"string","description":"Defaults to compose. Available formats: ${ExportFormatV1.entries.filter { UiBuilderBuildFeatures.remoteCompose || it.name !in setOf("JSON", "RC") }.joinToString(", ") { it.name.lowercase() }}. Check the catalog exportCapabilities."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        if (RemoteDocumentExportSupport.formats.isEmpty()) null
        else
          tool(
            EXPORT_DOCUMENT,
            "Compile supplied DesignDocumentV1 content without saving it or reading an existing design. " +
              "Use an exact catalog pin from ui_builder_list_catalogs. Supports PNG, Remote JSON and RC; " +
              "returns the same artifact and located diagnostics as saved-document export.",
            """
          {"type":"object","properties":{
            "document":{"type":"object","description":"Complete DesignDocumentV1 content, including its catalog pin."},
            "format":{"type":"string","enum":["png","json","rc"]}
          },"required":["document","format"],"additionalProperties":false}
          """,
          ),
        if (!validate) null
        else
          tool(
            VALIDATE,
            "Check a design without saving it: a whole `document`, a stored design by " +
              "`designId`, or `operations` against a stored design exactly as $APPLY would apply " +
              "them. Runs the same checks the real call does — document shape, catalog pin, the " +
              "catalog's own validation, the mutation reducer — and then the Compose export " +
              "gate, which is the list the editor's problems panel shows. Returns " +
              "`{valid, problems:[{severity, source, code, message, nodeId?, field?, " +
              "operationIndex?}]}`; `valid` is false exactly when a problem is an error. Nothing " +
              "is written, no revision moves and nobody watching the design is notified. The " +
              "shapes are published as the resources ${UiBuilderJsonSchemas.DOCUMENT_URI} and " +
              "${UiBuilderJsonSchemas.MUTATION_URI}.",
            """
            {"type":"object","properties":{
              "document":{"type":"object","description":"A whole DesignDocumentV1 to check."},
              "designId":{"type":"string","description":"A stored design to check, or to check `operations` against."},
              "operations":{"type":"array","items":{"type":"object"},"description":"DesignMutationV1 objects to check against `designId`'s current document, as ui_builder_apply takes them."},
              "baseRevision":{"type":"integer","description":"The revision the operations were written against; a stale one is reported as a warning."}
            },"additionalProperties":false}
            """,
          ),
        if (!assets) null
        else
          tool(
            PUT_ASSET,
            "Put a picture behind an `assetKey`, so an `asset/image` node naming that key draws " +
              "it instead of a placeholder. Send the PNG, JPEG, GIF or WebP bytes base64-encoded " +
              "in `$ASSET_BYTES_ARGUMENT`; the server stores them by content digest and pins " +
              "{mediaType, contentDigest, source: uploaded} into the design's `assets` map under " +
              "the key. This moves the design's revision, and the reply carries the new one to " +
              "quote as `baseRevision`. Idempotent by content — the same bytes under the same " +
              "key change nothing. Put the picture first, then insert the `asset/image` node " +
              "with $APPLY: the reducer refuses a key that is neither pinned in the design nor " +
              "in the catalog's own registry. A pinned key whose bytes a lane cannot show " +
              "renders as a visible placeholder, never as an error.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "assetKey":{"type":"string","description":"1 to 64 characters of letters, digits, '.', '_' or '-'; what the node's assetKey property names."},
              "$ASSET_BYTES_ARGUMENT":{"type":"string","description":"The image bytes, base64. At most 1 MiB decoded."}
            },"required":["designId","assetKey","$ASSET_BYTES_ARGUMENT"],"additionalProperties":false}
            """,
          ),
        tool(
          DESIGN_ACCESS,
          "Read who can open a design: its owner, and every actor it has been shared with, each " +
            "with the role and the actions that grant carries. Only the owner may ask — this is " +
            "the answer to \"who else is in here\" and to \"what is the id I must name when " +
            "sharing\".",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          SHARE_DESIGN,
          "Share a design with somebody else, or take that sharing back. `actorId` is the other " +
            "party's actor id as this server spells it — `github:<login>` for a signed-in " +
            "person, `operator` for the token holder, `agent:<fingerprint>` for another agent's " +
            "grant; $DESIGN_ACCESS lists the ones a design already carries. A `viewer` may read " +
            "and export, an `editor` may also change the design, and neither may share it on. " +
            "Only the design's owner may share it, and an agent acting under an approved grant " +
            "shares as the person who approved that grant.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "actorId":{"type":"string","description":"Who to share with, e.g. github:octocat."},
            "role":{"type":"string","description":"editor or viewer. Defaults to viewer."},
            "$REVOKE_ARGUMENT":{"type":"boolean","description":"Take this actor's access away instead."}
          },"required":["designId","actorId"],"additionalProperties":false}
          """,
        ),
        if (!links) null
        else
          tool(
            GET_LINKS,
            "Read what a design is **for**: the `issue` it was drawn for, the `reference` frame in " +
              "the design tool it reproduces, the `pr` that implemented it, the `thread` it is " +
              "being discussed in, and the `previous` design it continues. Start a session on " +
              "somebody else's design here — it is the brief, and it is what $GET_DESIGN cannot " +
              "tell you. Every field is optional; a reply with none of them means nobody has said " +
              "yet. The record is kept beside the design and is never part of it: no node holds " +
              "it, no export sees it, and writing one does not move the revision. Ancestry rides " +
              "along where there is some: `forkedFrom` / `forks` for a fork made with " +
              "${ServeUiBuilderHistoryTools.FORK_DESIGN}, and `branchOf` / `branches` for a " +
              "design branch and its parent.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!links) null
        else
          tool(
            SET_LINKS,
            "Say what a design is for. **Replaces the whole record**: send every link you want " +
              "kept, and omit one to clear it — so read $GET_LINKS first if you are adding to " +
              "what is already there. `issue`, `reference`, `pr` and `thread` are absolute http " +
              "or https URLs, at most 2 KB each; `previous` is a design id on this host, not a " +
              "URL. Anything else is refused with the reason. Sending an empty record clears it. " +
              "Record the `pr` when you open one for a design you built here: it is what lets " +
              "the next session, and the person who filed the issue, find one from the other.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "issue":{"type":"string","description":"The tracker issue this design is for."},
              "reference":{"type":"string","description":"The frame in the design tool it reproduces."},
              "pr":{"type":"string","description":"The pull request that implemented it."},
              "thread":{"type":"string","description":"A permalink to a discussion held elsewhere, such as a chat thread. It does not replace this server's comments for a server-homed design: keep that discussion on the design."},
              "previous":{"type":"string","description":"The design id on this host that this one continues."}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        tool(
          RENAME_DESIGN,
          "Give a design a new title. The title is the one thing about a design nothing else " +
            "could change: it is set at creation, shown in every listing and the editor, and not " +
            "part of any mutation. Anybody who may write the design may rename it. The revision " +
            "does not move — a title is not design content — so `baseRevision` is not needed and " +
            "an edit in flight is unaffected. Not the released envelope: the contract has no " +
            "rename, so the reply is the design's listing entry with its new title.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "title":{"type":"string"}
          },"required":["designId","title"],"additionalProperties":false}
          """,
        ),
        tool(
          DELETE_DESIGN,
          "Delete a design you own, with its history, access list, overlay and discussion. Only " +
            "the owner may — not an editor, not a viewer, and not anybody merely holding a write " +
            "grant on this server — so a session can clean up the designs it made and cannot " +
            "reach anybody else's; an agent acting under an approved grant owns what it created " +
            "as the person who approved it. There is no undo. Not the released envelope: the " +
            "contract has no delete, so the reply names the design that is gone.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        if (!comments) null
        else
          tool(
            LIST_COMMENTS,
            "Read the discussion on a design: every thread, where each is pinned — a markup " +
              "stroke, a design node, or a point on the frame — whether it is resolved, and every " +
              "reply under it. `sequence` rises on each change and is the cursor to quote to " +
              "$AWAIT_COMMENTS. Comments are kept beside the design and are never part of it: no " +
              "node holds them and no export sees them. Each thread carries `acknowledgedBy`, so " +
              "you can see what you have already caught up with; say you have read the rest with " +
              "$ACKNOWLEDGE_COMMENT.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            POST_COMMENT,
            "Say something on a design — a reply into `threadId`, or a new thread when it is " +
              "omitted. Pin a new thread with `markId` (a stroke on the reference overlay), " +
              "`nodeId` (a node in the design), or `x`/`y` in frame fractions, so the person " +
              "reading it can see what you meant. The comment is attributed to your own grant; " +
              "you cannot post as somebody else.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "threadId":{"type":"string","description":"Reply into this thread. Omit to start one."},
              "body":{"type":"string"},
              "displayName":{"type":"string","description":"The name to show beside your actor id."},
              "markId":{"type":"string","description":"Pin to a markup stroke on the reference."},
              "nodeId":{"type":"string","description":"Pin to a design node."},
              "x":{"type":"number","description":"Pin to a point on the frame, 0..1 across."},
              "y":{"type":"number","description":"Pin to a point on the frame, 0..1 down."}
            },"required":["designId","body"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            RESOLVE_COMMENT_THREAD,
            "Close a comment thread once it is answered, or reopen one by passing " +
              "`resolved: false`. The resolution is attributed to you and is reversible.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "threadId":{"type":"string"},
              "resolved":{"type":"boolean","description":"Defaults to true."}
            },"required":["designId","threadId"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            ACKNOWLEDGE_COMMENT,
            "Say that you have read a comment thread — which is **not** the same as resolving it. " +
              "Resolving claims the question is settled; acknowledging claims only that you have " +
              "seen it, which is the honest thing to say while you are still working on what it " +
              "asked for. Omit `threadId` to acknowledge the whole discussion, which is what you " +
              "mean after reading it with $LIST_COMMENTS. Acknowledgement is per actor, so a " +
              "thread you have read is still waiting for the other people in the design, and it " +
              "is what clears the `comments` block the server puts on your $APPLY, $GET_DESIGN, " +
              "$EXPORT and $PUT_ASSET replies.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "threadId":{"type":"string","description":"One thread. Omit for every thread on the design."}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            REACT_TO_COMMENT,
            "React to one comment with an emoji, or take the reaction back with `on: false`. The " +
              "lightest thing you can say: 👀 on a comment you have just picked up, 👍 on a fix " +
              "somebody made, where a reply would be noise in a thread a person has to read. " +
              "`commentId` is the `id` of a comment inside a thread, from $LIST_COMMENTS. " +
              "Reacting also acknowledges that thread for you, so it counts as the lightest " +
              "acknowledgement; it says nothing about whether the question is settled.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "commentId":{"type":"string","description":"The comment to react to, from its thread's `comments`."},
              "reaction":{"type":"string","description":"One emoji, at most $MAX_COMMENT_REACTION characters."},
              "on":{"type":"boolean","description":"False takes your reaction back. Defaults to true."}
            },"required":["designId","commentId","reaction"],"additionalProperties":false}
            """,
          ),
        if (!comments) null
        else
          tool(
            AWAIT_COMMENTS,
            "Wait for the discussion to move past `afterSequence` and return it, rather than " +
              "polling for it. Returns as soon as anybody — a designer in the browser or another " +
              "agent — posts, resolves or deletes; returns a `timedOut` reply if nothing happens " +
              "within `waitSeconds`, which you answer by calling again with the same cursor. This " +
              "is how you hold a conversation about a design: post, wait, read, act.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "afterSequence":{"type":"integer","description":"The `sequence` you last saw. 0 for anything at all."},
              "waitSeconds":{"type":"integer","description":"Up to $MAX_COMMENT_WAIT_SECONDS. Defaults to $DEFAULT_COMMENT_WAIT_SECONDS."}
            },"required":["designId","afterSequence"],"additionalProperties":false}
            """,
          ),
        tool(
          RENDER_NATIVE,
          "Compile a design and render it with real Compose on this host, rather than in the " +
            "browser's Wasm canvas — the way to see what a design looks like on Android. " +
            "Returns the first frame, the token the live frame stream is opened with, and the " +
            "design node ids the render is tagged with, so `catalog_get_preview_data` can report each " +
            "node's bounds and a client can put selectable regions over the image. " +
            (if (native) "This host has a native render lane. "
            else
              "This host currently has no native render lane, so calls return a refusal with " +
                "code `$NATIVE_RENDER_UNAVAILABLE` until one is configured. ") +
            "The reply is not an McpResponseEnvelopeV1: the released contract defines no " +
            "request type for a native render.",
          """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "revision":{"type":"integer","description":"A past revision. Omit for the current one."}
            },"required":["designId"],"additionalProperties":false}
            """,
        ),
        tool(
          VIEW,
          "See a design the way a person in the editor sees it: a PNG of the canvas with the " +
            "overlays drawn on — the `selection` outline, the `reference` picture when one is " +
            "attached, the discussion's `comments` pins, and the layout `bounds` — and beside it " +
            "JSON with the revision, each visible node's id and box, each pin's thread and " +
            "position, all in the returned image's pixels. The picture is a short-lived signed " +
            "https link by default; `$INLINE_ARGUMENT: true` puts the bytes in the reply as well. " +
            "The frame is the PNG export (`$RENDERER_ARGUMENT: \"export\"`, the editor's own " +
            "renderer), which reports no node boxes; `$RENDERER_ARGUMENT: \"native\"` draws " +
            "real Compose on a host with a native render lane, reports every node's box so the " +
            "selection can be outlined, and needs the ui-builder-export capability. A node with " +
            "no box is reported, never drawn at a guessed position. Not an McpResponseEnvelopeV1.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer","description":"A past revision. Omit for the current one."},
            "$VIEWPORT_ARGUMENT":{"type":"object","properties":{"width":{"type":"integer","minimum":1,"maximum":${ServeUiBuilderView.MAX_VIEWPORT_PX}},"height":{"type":"integer","minimum":1,"maximum":${ServeUiBuilderView.MAX_VIEWPORT_PX}}},"required":["width","height"],"additionalProperties":false,"description":"Fit the picture inside this many pixels, keeping its aspect ratio. Omit for the render's own size."},
            "$INCLUDE_ARGUMENT":{"type":"array","items":{"type":"string","enum":[${ServeUiBuilderView.INCLUDES.joinToString(",") { "\"$it\"" }}]},"description":"Overlays to draw. Defaults to ${ServeUiBuilderView.DEFAULT_INCLUDES.sorted().joinToString(", ")}; [] draws the bare frame."},
            "$SELECTION_ARGUMENT":{"type":"array","items":{"type":"string"},"description":"Node ids to show as selected."},
            "$RENDERER_ARGUMENT":{"type":"string","enum":["${ServeUiBuilderView.RENDERER_EXPORT}","${ServeUiBuilderView.RENDERER_NATIVE}"],"description":"Defaults to ${ServeUiBuilderView.RENDERER_EXPORT}."},
            "$INLINE_ARGUMENT":{"type":"boolean","description":"Also return the PNG as an image block. Defaults to false; a host with no public origin always does."}
          },"required":["designId"],"additionalProperties":false}
          """,
        ),
        tool(
          CHECK_DESIGN,
          "Check a design before you show it to anybody — one call instead of validate, view and " +
            "an accessibility pass. Runs `$CHECK_SCHEMA` (document shape), `$CHECK_CATALOG` (the " +
            "pinned catalog's validation, the mutation reducer and the Compose export gate — what " +
            "$VALIDATE runs) and `$CHECK_A11Y`: controls with no label, icons and pictures with no " +
            "`contentDescription`, touch targets under 48dp, text and icon contrast under WCAG " +
            "4.5:1 / 3:1, and text in a fixed height that clips at 200% font scale. Check a stored " +
            "design (`designId`, optionally a past `revision`), a whole `document`, or `operations` " +
            "applied to a scratch copy of `designId` exactly as $APPLY would — nothing is saved. " +
            "Returns `summary` first, then `ok`, the counts, and `findings` with the `nodeId` each " +
            "is about, so you can fix them with $APPLY and check again. `$RENDERED_ARGUMENT: true` " +
            "measures touch targets on a native render where the host has one (needs the " +
            "ui-builder-export capability). Contrast against theme roles is resolved with the " +
            "Material 3 baseline scheme and reported as a warning, never an error.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string","description":"A stored design to check, or to check `operations` against."},
            "revision":{"type":"integer","description":"A past revision of `designId`. Omit for the current one."},
            "document":{"type":"object","description":"A whole DesignDocumentV1 to check instead of a stored design."},
            "operations":{"type":"array","items":{"type":"object"},"description":"DesignMutationV1 objects to apply to a scratch copy of `designId` and check — a dry run."},
            "baseRevision":{"type":"integer","description":"The revision `operations` were written against; a stale one is a warning."},
            "$CHECKS_ARGUMENT":{"type":"array","items":{"type":"string","enum":[${DESIGN_CHECKS.joinToString(",") { "\"$it\"" }}]},"description":"Which checks to run. Defaults to all three."},
            "$RENDERED_ARGUMENT":{"type":"boolean","description":"Measure touch targets on a native render. Defaults to false."}
          },"additionalProperties":false}
          """,
        ),
        if (!validate) null
        else
          tool(
            RENDER_DESIGN_MATRIX,
            "See one design on several devices in ONE picture: a contact sheet with a captioned " +
              "cell per device × theme × font scale, plus each cell's device, size and box on the " +
              "sheet. Nothing is saved — the design's environment is swapped on a scratch copy for " +
              "each cell. Name `$DEVICES_ARGUMENT` by preset " +
              "(${UiBuilderDesignMatrix.PRESETS.joinToString(", ") { it.id }}) or as " +
              "{widthDp, heightDp, label?, round?}; or a `$FORM_FACTOR_ARGUMENT` " +
              "(${UiBuilderDesignMatrix.FORM_FACTORS.joinToString(", ")}) for its default set; " +
              "with neither you get the design's own form factor — phone, foldable and tablet for a " +
              "mobile catalog, the round watches for Wear. Add `$THEMES_ARGUMENT: [\"light\",\"dark\"]` " +
              "and `$FONT_SCALES_ARGUMENT: [1, 2]` to cross them; at most " +
              "${UiBuilderDesignMatrix.MAX_CELLS} cells. The sheet is a short-lived signed https " +
              "link kept under 3.5 MB, so a chat surface can show it inline; `$INLINE_ARGUMENT: " +
              "true` puts the bytes in the reply as well.",
            """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer","description":"A past revision. Omit for the current one."},
            "$DEVICES_ARGUMENT":{"type":"array","items":{"anyOf":[{"type":"string","enum":[${UiBuilderDesignMatrix.PRESETS.joinToString(",") { "\"${it.id}\"" }}]},{"type":"object","properties":{"widthDp":{"type":"integer","minimum":1,"maximum":${UiBuilderDesignMatrix.MAX_DEVICE_DP}},"heightDp":{"type":"integer","minimum":1,"maximum":${UiBuilderDesignMatrix.MAX_DEVICE_DP}},"label":{"type":"string"},"id":{"type":"string"},"formFactor":{"type":"string"},"round":{"type":"boolean"}},"required":["widthDp","heightDp"],"additionalProperties":false}]},"description":"Devices to draw. Omit for the form factor's defaults."},
            "$FORM_FACTOR_ARGUMENT":{"type":"string","enum":[${UiBuilderDesignMatrix.FORM_FACTORS.joinToString(",") { "\"$it\"" }}],"description":"A default device set, instead of `$DEVICES_ARGUMENT`."},
            "$THEMES_ARGUMENT":{"type":"array","items":{"type":"string","enum":["light","dark"]},"description":"Defaults to the design's own theme."},
            "$FONT_SCALES_ARGUMENT":{"type":"array","items":{"type":"number","minimum":$MIN_FONT_SCALE,"maximum":$MAX_FONT_SCALE},"description":"Defaults to the design's own font scale."},
            "$INLINE_ARGUMENT":{"type":"boolean","description":"Also return the PNG as an image block. Defaults to false; a host with no public origin always does."}
          },"required":["designId"],"additionalProperties":false}
          """,
          ),
        if (!reviews) null
        else
          tool(
            RECORD_DECISION,
            "Record a review verdict — `approve` or `reject` — on one revision of a design, with " +
              "an optional note. Kept beside the design, like comments: it never moves the " +
              "revision. Recorded as an agent's decision, so a person waiting with " +
              "$AWAIT_DECISION's default `$FROM_ARGUMENT: \"$FROM_HUMAN\"` is not answered by it. " +
              "Pass your own `decisionId` to make a retry idempotent.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "revision":{"type":"integer","description":"The revision the verdict is about."},
              "verdict":{"type":"string","enum":["approve","reject"]},
              "note":{"type":"string","description":"Why, in a sentence or two."},
              "decisionId":{"type":"string","description":"Your id for this decision; makes a retry idempotent."},
              "displayName":{"type":"string"}
            },"required":["designId","revision","verdict"],"additionalProperties":false}
            """,
          ),
        if (!reviews) null
        else
          tool(
            AWAIT_DECISION,
            "Wait for somebody to approve or reject a design, rather than asking again whether " +
              "they have. Returns as soon as a decision lands after `afterSequence` — by default " +
              "only a person's (`$FROM_ARGUMENT: \"$FROM_HUMAN\"`), on any revision unless you " +
              "name one — or a `timedOut` reply after `waitSeconds`. Every reply carries " +
              "`latest` (the newest matching verdict, new or not) and `approved`, so " +
              "`waitSeconds: 0` is a cheap, idempotent poll for a routine that can only check " +
              "back later; quote the reply's `sequence` as the next `afterSequence`. People " +
              "record theirs over the design's `decisions` HTTP route.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "afterSequence":{"type":"integer","description":"The `sequence` you last saw. 0 for anything at all."},
              "revision":{"type":"integer","description":"Only decisions on this revision."},
              "$FROM_ARGUMENT":{"type":"string","enum":["$FROM_HUMAN","$FROM_ANYONE"],"description":"Whose decisions count. Defaults to $FROM_HUMAN."},
              "waitSeconds":{"type":"integer","description":"Up to $MAX_DECISION_WAIT_SECONDS. Defaults to $DEFAULT_DECISION_WAIT_SECONDS; 0 checks without blocking."}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!reviews) null
        else
          tool(
            SET_IMPLEMENTATION,
            "Record the pull request that implements a design: its URL, its `status` " +
              "(draft, open, merged, closed), the design `revision` it implements, and — once you " +
              "have compared the PR's rendered previews with the design — `$PREVIEW_MATCH_ARGUMENT` " +
              "{status: match|mismatch|unknown, evidence: a URL to the comparison, note}. Replaces " +
              "the whole record; pass only `designId` to clear it. Writing the same record again " +
              "changes nothing and wakes nobody. Needs write access to the design.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "pr":{"type":"string","description":"The pull request URL."},
              "status":{"type":"string","enum":[${ServeUiBuilderReviewStore.IMPLEMENTATION_STATUSES.joinToString(",") { "\"$it\"" }}],"description":"Defaults to open."},
              "revision":{"type":"integer","description":"The design revision the PR implements."},
              "$PREVIEW_MATCH_ARGUMENT":{"type":"object","properties":{"status":{"type":"string","enum":[${ServeUiBuilderReviewStore.PREVIEW_MATCH_STATUSES.joinToString(",") { "\"$it\"" }}]},"evidence":{"type":"string"},"note":{"type":"string"}},"required":["status"],"additionalProperties":false}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!reviews) null
        else
          tool(
            IMPLEMENTATION_STATUS,
            "Everything the code side needs to implement a design, in one call: the revision, its " +
              "links (issue, reference, PR, thread), the implementation PR with its status and " +
              "whether its previews were found to match, the latest verdict on this revision, and " +
              "the Compose export with the generator's diagnostics. `summary` says in one line " +
              "whether the PR implements this revision and whether it was approved. " +
              "`$INCLUDE_EXPORT_ARGUMENT: false` leaves the Kotlin out and needs only read access; " +
              "with it the call needs the ui-builder-export capability.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "revision":{"type":"integer","description":"A past revision. Omit for the current one."},
              "$INCLUDE_EXPORT_ARGUMENT":{"type":"boolean","description":"Include the Compose export. Defaults to true."}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
        if (!reviews && !links) null
        else
          tool(
            FIND_DESIGN_FOR_PR,
            "From a pull request back to the design(s) it implements: every design you can read " +
              "whose implementation record or links name `pr`, with the implementation status " +
              "and revision. Then call $IMPLEMENTATION_STATUS for what the design expects.",
            """
            {"type":"object","properties":{
              "pr":{"type":"string","description":"The pull request URL, exactly as recorded."}
            },"required":["pr"],"additionalProperties":false}
            """,
          ),
      ) +
        ServeUiBuilderHistoryTools.declarations(tool) +
        (if (branches) ServeUiBuilderBranchTools.declarations(tool) else emptyList())
  }
}

/**
 * A design's title, changed. The listing entry rather than a snapshot: it carries the new title,
 * the unmoved revision and what the caller may do here, and is a few hundred bytes.
 */
@kotlinx.serialization.Serializable
internal data class DesignRenamedV1(
  val schema: String = "compose-preview/ui-builder-design-renamed/v1",
  val callId: String,
  val design: DesignListItemV1,
)

/** A design, gone: its id and nothing else, because there is nothing else left to say about it. */
@kotlinx.serialization.Serializable
internal data class DesignDeletedV1(
  val schema: String = "compose-preview/ui-builder-design-deleted/v1",
  val callId: String,
  val designId: String,
  val deleted: Boolean = true,
)

/**
 * What authoring needs to know about the catalogs on this host, and no more.
 *
 * The projection [ServeUiBuilderMcp.LIST_CATALOGS] answers with by default. Everything here is read
 * off the released `CatalogCapabilityV1`; what is left out — adapter status, SVG parity, export
 * notes, menu shelving — matters to the export lane and to nobody composing a screen, and is one
 * `full: true` away.
 */
@OptIn(ExperimentalSerializationApi::class)
@kotlinx.serialization.Serializable
internal data class CatalogSummaryReplyV1(
  @EncodeDefault val schema: String = "compose-preview/ui-builder-catalog-summary/v1",
  val callId: String,
  val catalogs: List<CatalogSummaryV1>,
)

@kotlinx.serialization.Serializable
internal data class CatalogSummaryV1(
  val systemId: String,
  val platform: String? = null,
  /**
   * The exact pin a document must carry to resolve to this catalog. Absent if the host cannot say.
   */
  val catalogPin: CatalogReferenceV1? = null,
  val exportCapabilities: ExportCapabilitiesV1 = ExportCapabilitiesV1.Builder().build(),
  /**
   * Every modifier type some component here accepts, once. Which component accepts which is in the
   * whole capability; nearly every component accepts nearly all of them, listing the set per
   * component was most of the summary's bytes, and a modifier a component does not accept is
   * refused by name when applied.
   */
  val modifiers: List<String> = emptyList(),
  val components: List<ComponentSummaryV1> = emptyList(),
)

/**
 * A component in the space of a table row. [properties] are `name:type`, with `!` appended when the
 * property is required and `=a|b|c` when the catalog restricts its values; [slots] are the slot's
 * name, its cardinality in square brackets as `min..max` with `*` for unbounded, then
 * `:role|trait|…` naming what the slot accepts.
 */
@kotlinx.serialization.Serializable
internal data class ComponentSummaryV1(
  val id: String,
  val role: String,
  val traits: List<String> = emptyList(),
  val slots: List<String> = emptyList(),
  val properties: List<String> = emptyList(),
)

/**
 * The same JSON with every embedded `ServiceSnapshotV1`'s `catalog` removed.
 *
 * A snapshot is recognised by the three fields it always has beside the catalog — `designId`,
 * `state` and `retainedFromSequence` — so the walk removes the field from a snapshot wherever the
 * envelope puts one (a response, a pushed update) and from nothing else: a node property that
 * happens to be called `catalog` is not a snapshot's.
 */
/** An export artifact's content as text: UTF-8 as it is, base64 decoded. Null when empty. */
private fun ExportArtifactV1.text(): String? =
  when (encoding) {
    ExportEncodingV1.UTF8 -> content
    ExportEncodingV1.BASE64 ->
      runCatching { String(java.util.Base64.getDecoder().decode(content), Charsets.UTF_8) }
        .getOrNull()
  }?.takeIf { it.isNotBlank() }

private fun JsonElement.withoutCatalog(): JsonElement =
  when (this) {
    is JsonObject -> {
      val isSnapshot =
        "catalog" in this && "designId" in this && "state" in this && "retainedFromSequence" in this
      JsonObject(
        entries
          .filterNot { (key, _) -> isSnapshot && key == "catalog" }
          .associate { (key, value) -> key to value.withoutCatalog() }
      )
    }
    is JsonArray -> JsonArray(map { it.withoutCatalog() })
    else -> this
  }

/**
 * Nothing was said within the wait.
 *
 * Its own shape rather than an empty board, so a caller cannot read "no news" as "the discussion
 * was emptied" — the same distinction the HTTP watch route draws with a 204.
 */
@kotlinx.serialization.Serializable
internal data class CommentWaitTimeoutV1(
  val schema: String = "compose-preview/ui-builder-comment-wait/v1",
  val designId: String,
  val timedOut: Boolean = true,
  /** Echoed back, so a caller that loops can pass the same cursor without tracking it itself. */
  val afterSequence: Long,
)

/**
 * Whether this update is a change to the design, past the cursor the caller quoted.
 *
 * A snapshot always is: the service sends one when the cursor cannot be served from the retained
 * window, and the caller has to resync whether or not it names an edit they care about. A delta is
 * one only when it carries something they have not seen — subscribing at the design's current
 * sequence produces an empty catch-up delta, which is "nothing has happened", not news.
 *
 * Presence never is. It is ephemeral chrome, excluded by design from the document, the revision and
 * the durable sequence, and waking an agent because a colleague moved their cursor would spend a
 * tool call on something with nothing to act on. An outcome is an acknowledgement addressed to
 * whoever submitted it, and the edit it acknowledges arrives as its own delta.
 */
private fun UiBuilderServiceUpdate.movesTheDocument(afterSequence: Long): Boolean =
  when (this) {
    is UiBuilderServiceUpdate.Snapshot -> true
    is UiBuilderServiceUpdate.Delta -> delta.throughSequence > afterSequence
    is UiBuilderServiceUpdate.Presence,
    is UiBuilderServiceUpdate.Outcome -> false
  }

/**
 * Nobody changed the design within the wait.
 *
 * Its own shape rather than an empty delta, so a caller cannot read "no news" as "the design was
 * emptied" — the same distinction [CommentWaitTimeoutV1] draws, and the HTTP comment watch draws
 * with a 204.
 */
@kotlinx.serialization.Serializable
internal data class DesignWaitTimeoutV1(
  val schema: String = "compose-preview/ui-builder-design-wait/v1",
  val designId: String,
  val timedOut: Boolean = true,
  /** Echoed back, so a caller that loops can pass the same cursor without tracking it itself. */
  val afterSequence: Long,
)
