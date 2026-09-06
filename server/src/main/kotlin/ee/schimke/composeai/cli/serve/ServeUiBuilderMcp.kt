package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.ApplyOperationRequestV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.CatalogsResponseV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.CreateDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignCommandV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.DesignMutationV1
import ee.schimke.composeai.uibuilder.protocol.DesignUpdateEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportDesignRequestV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.GetDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.ListCatalogsRequestV1
import ee.schimke.composeai.uibuilder.protocol.ListDesignsRequestV1
import ee.schimke.composeai.uibuilder.protocol.McpResponseEnvelopeV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.RevokeActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRequestV1
import ee.schimke.composeai.uibuilder.protocol.UpdateDesignAccessRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetPort
import ee.schimke.composeai.uibuilder.service.UiBuilderAssetWrite
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
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
   * The native render lane, on a box that has one.
   *
   * Null on a host without a playground bundle — compiling a design needs a Kotlin compiler and the
   * catalog's own classpath, which not every deployment carries. The tool is then absent rather
   * than present and failing, exactly as the whole surface is on a box with no builder.
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
) {

  /** Whether this host keeps design discussions, and so whether the comment tools exist. */
  val supportsComments: Boolean
    get() = comments != null

  /** Whether this host keeps design assets, and so whether [PUT_ASSET] exists. */
  val supportsAssets: Boolean
    get() = assets != null

  /** What a tool needs from the caller before it may run. Null when the name is not ours. */
  fun capabilityFor(tool: String): UiBuilderRouteCapability? =
    when (tool) {
      LIST_CATALOGS,
      LIST_DESIGNS,
      GET_DESIGN,
      // Reading a design's access list is gated at the door like any other read, and by the
      // service on top of that: only the owner is told who else holds a grant.
      DESIGN_ACCESS -> UiBuilderRouteCapability.READ
      AWAIT_DESIGN -> UiBuilderRouteCapability.READ
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
      RENAME_DESIGN,
      // Sharing writes to the design's access control, and the service admits only its owner.
      SHARE_DESIGN,
      // Gated at the door as a write; the service then admits only the design's owner. There is
      // no `delete` capability to hand out on purpose — see [DELETE_DESIGN].
      DELETE_DESIGN -> UiBuilderRouteCapability.WRITE
      EXPORT -> UiBuilderRouteCapability.EXPORT
      // A write to the design — the registry is part of the document and moves its revision —
      // gated as one, and absent where the host has nowhere to keep the bytes.
      PUT_ASSET -> if (assets == null) null else UiBuilderRouteCapability.WRITE
      // The same capability as an export, and for the same reason: a native render compiles and
      // runs the Kotlin an export hands back, so an actor who may not read that source may not
      // run it. Absent entirely on a host that cannot compile.
      RENDER_NATIVE -> if (nativePreview == null) null else UiBuilderRouteCapability.EXPORT
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
  ): String = withCommentNotice(tool, args, actor, run(tool, args, actor, callId))

  private suspend fun run(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    callId: String,
  ): String {
    val request =
      when (tool) {
        LIST_CATALOGS -> return listCatalogs(args, actor, callId)
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
        CREATE_DESIGN -> createDesign(args, actor)
        RENAME_DESIGN,
        DELETE_DESIGN -> return manageDesign(tool, args, actor, callId)
        DESIGN_ACCESS -> GetDesignAccessRequestV1(designId = args.requiredText("designId"))
        SHARE_DESIGN -> share(args, actor)
        APPLY -> apply(args, actor)
        EXPORT ->
          ExportDesignRequestV1(
            designId = args.requiredText("designId"),
            revision = args.number("revision"),
            format = args.exportFormat(),
          )
        RENDER_NATIVE -> return renderNative(args, actor)
        PUT_ASSET -> return envelope(callId, putAsset(args, actor))
        AWAIT_DESIGN -> return awaitDesign(args, actor)
        LIST_COMMENTS,
        AWAIT_COMMENTS,
        POST_COMMENT,
        RESOLVE_COMMENT_THREAD,
        ACKNOWLEDGE_COMMENT,
        REACT_TO_COMMENT -> return commentTool(tool, args, actor)
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
        else catalog.copy(components = catalog.components.filter { it.componentId in componentIds })
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

  /** `name:type`, `!` when required, then `=` and the allowed values `|`-separated. */
  private fun summarize(property: PropertyCapabilityV1): String {
    val type =
      when (val jsonType = property.jsonType) {
        is JsonArray -> jsonType.joinToString("|") { it.jsonPrimitive.content }
        else -> jsonType.jsonPrimitive.content
      }
    val allowed = property.allowedValues.joinToString("|") { it.jsonPrimitive.content }
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
        UI_BUILDER_JSON.encodeToString(
          DesignDeletedV1.serializer(),
          DesignDeletedV1(callId = callId, designId = designId),
        )
      }
      else -> envelope(callId, response)
    }
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
    val lane = nativePreview ?: throw McpRequestException("this host has no native render lane")
    val designId = args.requiredText("designId")
    val snapshot =
      execute(GetSnapshotRequestV1(designId = designId, revision = args.number("revision")), actor)
        as? UiBuilderServiceResponse.Snapshot
        ?: throw McpRequestException("no design `$designId` this actor can read")
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
   * Wait for somebody to change a design, rather than asking again whether they have.
   *
   * ## Why a tool and not an MCP notification
   *
   * MCP does have server-to-client notifications, and this surface deliberately cannot send one:
   * `/mcp` is a stateless JSON-RPC endpoint, `GET /mcp` — the Streamable-HTTP listening stream a
   * notification would travel on — answers 405, and `initialize` says as much by advertising
   * `resources: {"subscribe": false}`. Honouring `resources/subscribe` would mean session ids, a
   * per-session SSE stream, resumability and server-held subscription state: a stateful transport,
   * which is the property this endpoint is built not to have. A call that blocks needs none of
   * that, and it is the shape the grant flow's `poll_access` and [AWAIT_COMMENTS] already use here.
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
        UiBuilderProtocolMapper.toProtocolUpdate(designId, update),
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
                // Declared rather than inferred, but defaulted to `agent` here: everything
                // reaching this class arrived over MCP. A tool that wanted to post as a person
                // would be posting somebody else's words under their own grant.
                authorKind = StoredComment.AUTHOR_KIND_AGENT,
              ),
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
    return UI_BUILDER_JSON.encodeToString(StoredCommentBoard.serializer(), board)
  }

  /**
   * The reply, plus what this actor has not been told, when there is any.
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
   * that keeps no discussions. The reply is re-parsed only when there is something to add, so a
   * design nobody has commented on — the common case, and the one where a native render's base64
   * frame would be expensive to walk — pays a stat call and hands the original string back
   * untouched.
   *
   * A reply that is not a JSON object is handed back as it is: a notice is worth having, and never
   * worth mangling the answer the agent asked for.
   */
  private fun withCommentNotice(
    tool: String,
    args: JsonObject,
    actor: AuthenticatedUiBuilderActor,
    reply: String,
  ): String {
    val store = comments ?: return reply
    if (tool !in COMMENT_NOTICE_TOOLS) return reply
    val designId = args.text("designId") ?: return reply
    // The design was read as this actor by the call that produced `reply`, so the access check has
    // already happened; a reply that never reached the design carries no notice because the board
    // of a design nobody may read is never consulted here — the tool refused before this point.
    val notice =
      try {
        store.readOrEmpty(designId).noticeFor(actor.actorId)
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (_: Exception) {
        // A discussion this host cannot read must never cost the agent the answer it asked for.
        null
      } ?: return reply
    val parsed =
      try {
        UI_BUILDER_JSON.parseToJsonElement(reply) as? JsonObject ?: return reply
      } catch (_: SerializationException) {
        return reply
      }
    return JsonObject(
        parsed +
          (COMMENTS_NOTICE_KEY to
            UI_BUILDER_JSON.encodeToJsonElement(CommentNoticeV1.serializer(), notice))
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
  ): UiBuilderRequestV1 {
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
    return CreateDesignRequestV1(
      document.copy(id = designId, revision = 0, title = args.text("title") ?: document.title)
    )
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

  private suspend fun execute(
    request: UiBuilderRequestV1,
    actor: AuthenticatedUiBuilderActor,
  ): UiBuilderServiceResponse =
    when (val mapping = UiBuilderProtocolMapper.toServiceCall(actor, request)) {
      is ProtocolRequestMapping.Mapped ->
        try {
          service.execute(mapping.call)
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
    const val LIST_CATALOGS = "ui_builder_list_catalogs"
    const val LIST_DESIGNS = "ui_builder_list_designs"
    const val GET_DESIGN = "ui_builder_get_design"
    const val CREATE_DESIGN = "ui_builder_create_design"
    const val APPLY = "ui_builder_apply"
    const val EXPORT = "ui_builder_export"
    const val RENDER_NATIVE = "ui_builder_render_native"
    const val PUT_ASSET = "ui_builder_put_asset"

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
    const val SHARE_DESIGN = "ui_builder_share_design"
    const val RENAME_DESIGN = "ui_builder_rename_design"
    const val DELETE_DESIGN = "ui_builder_delete_design"

    private const val REVOKE_ARGUMENT = "revoke"
    private const val INCLUDE_CATALOG_ARGUMENT = "includeCatalog"
    private const val FULL_ARGUMENT = "full"
    private const val COMPONENT_IDS_ARGUMENT = "componentIds"

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
      listOf(
        LIST_CATALOGS,
        LIST_DESIGNS,
        GET_DESIGN,
        AWAIT_DESIGN,
        CREATE_DESIGN,
        APPLY,
        EXPORT,
        DESIGN_ACCESS,
        SHARE_DESIGN,
        RENAME_DESIGN,
        DELETE_DESIGN,
      )

    /** Separate because it exists only where the host can compile. */
    val NATIVE_TOOL_NAMES = listOf(RENDER_NATIVE)

    /** Separate because it exists only where the host keeps design assets. */
    val ASSET_TOOL_NAMES = listOf(PUT_ASSET)

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
      setOf(GET_DESIGN, APPLY, EXPORT, RENDER_NATIVE, PUT_ASSET, AWAIT_DESIGN)

    private const val DEFAULT_DESIGN_PAGE = 50

    /** How long a design watcher waits by default, and the most it may ask for. */
    private const val DEFAULT_DESIGN_WAIT_SECONDS = 25L

    private const val MAX_DESIGN_WAIT_SECONDS = 120L
    private const val MCP_CLIENT_ID = "mcp"

    /** The key [CommentNoticeV1] is spliced onto a reply under. */
    internal const val COMMENTS_NOTICE_KEY = "comments"

    /**
     * Tool declarations, built with the caller's own `tool` helper so this list has the same shape
     * as every other tool on the surface rather than a second one that drifts.
     */
    fun declarations(
      tool: (String, String, String) -> JsonObject,
      native: Boolean = false,
      comments: Boolean = false,
      assets: Boolean = false,
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
            "it is most of the bytes.",
          """
          {"type":"object","properties":{
            "designId":{"type":"string"},
            "revision":{"type":"integer","description":"A past revision. Omit for the current one."},
            "$INCLUDE_CATALOG_ARGUMENT":{"type":"boolean","description":"Embed the pinned catalog's whole CatalogCapabilityV1 in the snapshot, as the released shape does. Defaults to false."}
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
            "must match a catalog revision this server serves, and a copy carries one that does.",
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
            "DesignMutationV1 — as one operation. `baseRevision` is the revision you read, and a " +
            "mismatch is reported rather than merged, so a concurrent edit cannot be lost. This " +
            "is how an agent adds a scaffold, fills its slots and sets modifiers. " +
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
            "operations":{"type":"array","items":{"type":"object"},"description":"DesignMutationV1 objects."}
          },"required":["designId","operationId","baseRevision","operations"],"additionalProperties":false}
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
            "format":{"type":"string","description":"compose, svg or png. Defaults to compose."}
          },"required":["designId"],"additionalProperties":false}
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
        if (!native) null
        else
          tool(
            RENDER_NATIVE,
            "Compile a design and render it with real Compose on this host, rather than in the " +
              "browser's Wasm canvas — the way to see what a design looks like on Android. " +
              "Returns the first frame, the token the live frame stream is opened with, and the " +
              "design node ids the render is tagged with, so `get_preview_data` can report each " +
              "node's bounds and a client can put selectable regions over the image. The reply " +
              "is not an McpResponseEnvelopeV1: the released contract defines no request type " +
              "for a native render.",
            """
            {"type":"object","properties":{
              "designId":{"type":"string"},
              "revision":{"type":"integer","description":"A past revision. Omit for the current one."}
            },"required":["designId"],"additionalProperties":false}
            """,
          ),
      )
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
  val exportCapabilities: ExportCapabilitiesV1 = ExportCapabilitiesV1(),
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
