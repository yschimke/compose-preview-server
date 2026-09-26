package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import ee.schimke.composeai.agentgrants.AgentGrantScope
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignActorAccessV1
import ee.schimke.composeai.uibuilder.protocol.DesignListItemV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubmission
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * A grant that names its designs lends its approver's authority on those designs only; one that
 * names none behaves exactly as grants always have.
 */
class ServeUiBuilderGrantScopeTest {

  /**
   * A design service that answers the one question this is about: an actor reaches a design when
   * any of its access identities owns it. Delegation is modelled exactly as the real service does,
   * through [AuthenticatedUiBuilderActor.accessIdentities].
   */
  private class OwnedDesigns(private val owners: Map<String, String>) : UiBuilderServicePort {
    val calls = mutableListOf<UiBuilderServiceCall>()
    val subscriptions = mutableListOf<UiBuilderSubscriptionCall>()

    override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
      calls += call
      val identities = call.actor.accessIdentities
      return when (val request = call.request) {
        is UiBuilderServiceRequest.ListDesigns ->
          UiBuilderServiceResponse.Designs(
            owners.filterValues { it in identities }.keys.sorted().map { item(it, owners[it]!!) },
            nextCursor = null,
          )
        is UiBuilderServiceRequest.CreateDesign ->
          UiBuilderServiceResponse.DesignDeleted(call.actor.onBehalfOfActorId ?: call.actor.actorId)
        else -> {
          val designId = with(ServeUiBuilderGrantScope) { request.designId() }
          if (owners[designId] in identities)
            UiBuilderServiceResponse.DesignActions(designId!!, DesignAccessActionV1.entries)
          else
            UiBuilderServiceResponse.Error(
              UiBuilderServiceError(ServiceErrorCodeV1.NOT_FOUND, "no such design")
            )
        }
      }
    }

    override fun subscribe(
      call: UiBuilderSubscriptionCall,
      listener: (UiBuilderServiceUpdate) -> Unit,
    ): Closeable {
      subscriptions += call
      return Closeable {}
    }
  }

  private val service =
    OwnedDesigns(mapOf("asked-for" to OWNER, "other" to OWNER, "mine" to REQUESTER))

  private val grants =
    ServeAgentGrantStore(
      maxCapabilities =
        setOf(AgentGrantCapability.UI_BUILDER_READ, AgentGrantCapability.UI_BUILDER_WRITE)
    )

  private val port =
    ServeUiBuilderGrantScope.limit(service, ServeUiBuilderGrantScope.lookupOf(grants))

  private val holder = AuthenticatedUiBuilderActor(REQUESTER, onBehalfOfActorId = OWNER)

  private fun grant(designIds: Set<String>, limit: Boolean = true): ServeAgentGrantStore.Grant {
    val request =
      checkNotNull(
        grants.openRequest(
          label = "edit",
          client = "@requester",
          requestedScope = AgentGrantScope.PREVIEW,
          requestedTtlSeconds = 3600,
          requestedCapabilities =
            setOf(AgentGrantCapability.UI_BUILDER_READ, AgentGrantCapability.UI_BUILDER_WRITE),
          requesterActorId = REQUESTER,
          designIds = designIds,
        )
      )
    return checkNotNull(
      grants.approve(
        request.id,
        approvedBy = "@owner",
        scope = AgentGrantScope.PREVIEW,
        ttlSeconds = 3600,
        capabilities = request.requestedCapabilities,
        approvedByActorId = OWNER,
        limitToRequestedDesigns = limit,
      )
    )
  }

  private fun reads(designId: String): Boolean = runBlocking {
    port.execute(
      UiBuilderServiceCall(holder, UiBuilderServiceRequest.GetSnapshot(designId, revision = null))
    ) is UiBuilderServiceResponse.DesignActions
  }

  private fun writes(designId: String): Boolean = runBlocking {
    port.execute(
      UiBuilderServiceCall(
        holder,
        UiBuilderServiceRequest.ApplyOperation(
          UiBuilderSubmission.Batch(designId, "op", "client", baseRevision = 1, emptyList())
        ),
      )
    ) is UiBuilderServiceResponse.DesignActions
  }

  private fun listed(): List<String> = runBlocking {
    val response =
      port.execute(UiBuilderServiceCall(holder, UiBuilderServiceRequest.ListDesigns(null, 50)))
    (response as UiBuilderServiceResponse.Designs).designs.map { it.designId }.sorted()
  }

  @Test
  fun `a grant for one design edits that design and cannot read or edit the approver's others`() {
    val grant = grant(setOf("asked-for"))
    assertEquals(setOf("asked-for"), grant.designIds)

    assertTrue(reads("asked-for"))
    assertTrue(writes("asked-for"))
    assertFalse(reads("other"), "another design of the approver is out of reach")
    assertFalse(writes("other"))
    // What the holder may do as themselves is untouched.
    assertTrue(reads("mine"))
    assertEquals(listOf("asked-for", "mine"), listed())
  }

  @Test
  fun `outside its design the holder reaches the service as itself`() {
    grant(setOf("asked-for"))
    reads("other")
    val actor = service.calls.last().actor
    assertEquals(REQUESTER, actor.actorId)
    assertNull(actor.onBehalfOfActorId)

    reads("asked-for")
    assertEquals(OWNER, service.calls.last().actor.onBehalfOfActorId)
  }

  @Test
  fun `a request about no design, such as a create, is made as the holder alone`() {
    grant(setOf("asked-for"))
    // `CreateDesign` names no existing design, so it resolves exactly like this: the new design
    // belongs to the holder rather than to the approver who lent one design.
    assertNull(
      ServeUiBuilderGrantScope.actorFor(holder, designId = null, grantsLookup()).onBehalfOfActorId
    )
    runBlocking { port.execute(UiBuilderServiceCall(holder, UiBuilderServiceRequest.ListCatalogs)) }
    assertNull(service.calls.last().actor.onBehalfOfActorId)
  }

  @Test
  fun `a subscription to another design is made as the holder alone`() {
    grant(setOf("asked-for"))
    port.subscribe(UiBuilderSubscriptionCall(holder, "other", afterSequence = null)) {}.close()
    port.subscribe(UiBuilderSubscriptionCall(holder, "asked-for", afterSequence = null)) {}.close()
    assertNull(service.subscriptions[0].actor.onBehalfOfActorId)
    assertEquals(OWNER, service.subscriptions[1].actor.onBehalfOfActorId)
  }

  @Test
  fun `a grant that names no design reaches every design its approver can, as before`() {
    grant(emptySet())
    assertTrue(reads("asked-for"))
    assertTrue(writes("other"))
    assertEquals(listOf("asked-for", "mine", "other"), listed())
  }

  @Test
  fun `the approver may widen a request for one design to every design`() {
    val grant = grant(setOf("asked-for"), limit = false)
    assertTrue(grant.designIds.isEmpty())
    assertTrue(writes("other"))
  }

  @Test
  fun `two grants for two designs reach both`() {
    grant(setOf("asked-for"))
    assertFalse(reads("other"))
    grant(setOf("other"))
    assertTrue(reads("other"))
    assertEquals(setOf("asked-for", "other"), grants.designScopeFor(REQUESTER, OWNER))
  }

  /**
   * The service call carries the approver, not the grant that authorised it, so an every-design
   * grant beside a one-design grant (say a read-only one beside an edit one) must not stretch the
   * one-design grant to every design.
   */
  @Test
  fun `a grant without designs does not widen one that names a design`() {
    grant(setOf("asked-for"))
    grant(emptySet())
    assertEquals(setOf("asked-for"), grants.designScopeFor(REQUESTER, OWNER))
    assertTrue(writes("asked-for"))
    assertFalse(writes("other"))
    assertFalse(reads("other"))
  }

  @Test
  fun `grants that all name no design reach every design`() {
    grant(emptySet())
    grant(emptySet())
    assertNull(grants.designScopeFor(REQUESTER, OWNER))
    assertTrue(writes("other"))
  }

  @Test
  fun `a delegation this store did not mint is left alone`() {
    val recovery = AuthenticatedUiBuilderActor("system:catalog-recovery", OWNER)
    assertEquals(recovery, ServeUiBuilderGrantScope.actorFor(recovery, "other", grantsLookup()))
  }

  @Test
  fun `a grant made before designs could be named reads as naming none`() {
    // The shape every grant had before the field existed: constructed without it.
    val old =
      ServeAgentGrantStore.Grant(
        id = "id",
        token = "cpat_0123456789abcdef",
        scope = AgentGrantScope.PREVIEW,
        label = "edit",
        approvedBy = "@owner",
        approvedByActorId = OWNER,
        issuedAtMillis = 0,
        expiresAtMillis = Long.MAX_VALUE,
      )
    assertTrue(old.designIds.isEmpty())
    assertTrue(old.coversDesign("anything"))
  }

  @Test
  fun `a whoami reply written before designs could be named still decodes`() {
    val json = Json { ignoreUnknownKeys = true }
    val old =
      json.decodeFromString(
        ServeAgentGrants.WhoamiResponse.serializer(),
        """{"active":true,"scopes":["preview"],"onBehalfOfActorId":"github:owner"}""",
      )
    assertTrue(old.designIds.isEmpty())
    val current =
      json.decodeFromString(
        ServeAgentGrants.WhoamiResponse.serializer(),
        """{"active":true,"designIds":["asked-for"]}""",
      )
    assertEquals(listOf("asked-for"), current.designIds)
  }

  @Test
  fun `a request cannot name a malformed design id`() {
    val refused = runCatching {
      grants.openRequest(
        label = "edit",
        client = "x",
        requestedScope = AgentGrantScope.PREVIEW,
        requestedTtlSeconds = 60,
        designIds = setOf("../etc"),
      )
    }
    assertTrue(refused.isFailure)
    assertTrue(grants.pendingRequests().isEmpty())
    assertNotNull(
      grants.openRequest(
        label = "edit",
        client = "x",
        requestedScope = AgentGrantScope.PREVIEW,
        requestedTtlSeconds = 60,
        designIds = setOf("cheeky-raccoon.v2"),
      )
    )
  }

  @Test
  fun `a design shared with a reader offers a request for that design`() {
    val href = "/ui-builder/request-access?design=asked-for"
    val page =
      ServeWeb.uiBuilderDesignsPage(
        rows =
          listOf(
            ServeWeb.UiBuilderDesignRow(
              designId = "asked-for",
              title = "Checkout",
              catalogSystemId = "remote-m3",
              revision = 1,
              updatedAtEpochMillis = null,
              ownerActorId = OWNER,
              requesterRole = "viewer",
              requesterAllowed = "read",
              designHref = "/ui-builder/asked-for",
              shareAction = "/ui-builder/asked-for/access",
              grants = null,
              unopenableReason = null,
              requestAccessHref = href,
            )
          ),
        viewerActorId = REQUESTER,
        requestAccessHref = "/ui-builder/request-access",
      )
    assertTrue(page.contains("<a href=\"$href\">Request edit access to this design</a>"), page)
  }

  private fun grantsLookup() = ServeUiBuilderGrantScope.lookupOf(grants)

  private companion object {
    const val OWNER = "github:owner"
    const val REQUESTER = "github:requester"

    fun item(id: String, owner: String) =
      DesignListItemV1(
        designId = id,
        title = id,
        revision = 1,
        accessRevision = 1,
        catalogPin = CatalogReferenceV1("remote-m3", "v", "v", "v"),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        ownerActorId = owner,
        requesterAccess =
          DesignActorAccessV1(owner, DesignAccessRoleV1.OWNER, DesignAccessActionV1.entries),
      )
  }
}
