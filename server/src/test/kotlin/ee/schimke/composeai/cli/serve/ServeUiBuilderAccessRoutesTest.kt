package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessActionV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.DesignActorGrantV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.RevokeActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.ServiceErrorCodeV1
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceError
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceUpdate
import ee.schimke.composeai.uibuilder.service.UiBuilderSubscriptionCall
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The sharing page: who may see it, what it says, and what a submission actually changes.
 *
 * A design's access control has been in the protocol since v1 with no interface at all, so the
 * properties worth pinning are the ones that make this a door rather than a page: only the owner is
 * shown the list (the service decides that, and the route must not paper over the refusal), a
 * submission reaches the service as the mutation it claims to be, and a cross-site form POST is
 * refused — this is a mutating form on a server whose credentials ride on the request.
 */
class ServeUiBuilderAccessRoutesTest {
  private val mutations = mutableListOf<UiBuilderServiceRequest.UpdateDesignAccess>()

  private var access =
    DesignAccessControlV1(
      accessRevision = 3,
      ownerActorId = "github:owner",
      actorGrants =
        listOf(
          DesignActorGrantV1(
            actorId = "agent:abc123",
            role = DesignAccessRoleV1.EDITOR,
            allowedActions = listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE),
            grantedByActorId = "github:owner",
            grantedAtEpochMillis = 1_000,
          )
        ),
    )

  private val service =
    object : UiBuilderServicePort {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse =
        when (val request = call.request) {
          is UiBuilderServiceRequest.GetDesignAccess ->
            // The real service answers only the owner, and a delegate of the owner. Reproduced
            // here rather than stubbed open, because the route's refusal path is what is on test.
            if (access.ownerActorId in call.actor.accessIdentities)
              UiBuilderServiceResponse.DesignAccess(request.designId, access)
            else
              UiBuilderServiceResponse.Error(
                UiBuilderServiceError(ServiceErrorCodeV1.FORBIDDEN, "not yours")
              )
          is UiBuilderServiceRequest.UpdateDesignAccess -> {
            mutations += request
            access =
              when (val mutation = request.mutations.single()) {
                is GrantActorAccessMutationV1 ->
                  access.copy(
                    accessRevision = access.accessRevision + 1,
                    actorGrants =
                      access.actorGrants.filterNot { it.actorId == mutation.actorId } +
                        DesignActorGrantV1(
                          mutation.actorId,
                          mutation.role,
                          mutation.allowedActions,
                          access.ownerActorId,
                          2_000,
                        ),
                  )
                is RevokeActorAccessMutationV1 ->
                  access.copy(
                    accessRevision = access.accessRevision + 1,
                    actorGrants = access.actorGrants.filterNot { it.actorId == mutation.actorId },
                  )
                else -> access
              }
            UiBuilderServiceResponse.DesignAccess(request.designId, access)
          }
          else -> UiBuilderServiceResponse.Catalogs(emptyList())
        }

      override fun subscribe(
        call: UiBuilderSubscriptionCall,
        listener: (UiBuilderServiceUpdate) -> Unit,
      ): Closeable = Closeable {}
    }

  private val authorization = ServeUiBuilderAuthorization { call, _, _ ->
    when (val actor = call.request.headers["X-Test-Actor"]) {
      null -> UiBuilderAuthorizationDecision.Missing
      "forbidden" -> UiBuilderAuthorizationDecision.Forbidden
      // An agent presenting a grant its owner approved arrives as both halves of one identity,
      // exactly as `ServeMachineAuthorization` builds it.
      "agent" -> UiBuilderAuthorizationDecision.Authorized("agent:abc123", "github:owner")
      else -> UiBuilderAuthorizationDecision.Authorized(actor)
    }
  }

  private val builderDir =
    Files.createTempDirectory("serve-ui-builder-access").toFile().also { dir ->
      dir.deleteOnExit()
      File(dir, "index.html").writeText("<!doctype html><title>Compose UI builder</title>")
    }

  private val registry = ServeSessionRegistry(open = { null })
  private val server =
    ServeHttpServer(
        host = "127.0.0.1",
        requestedPort = 0,
        token = "operator-token",
        sessions = registry,
        defaultSessionId = "unused",
        uiBuilderDir = builderDir,
        uiBuilderCatalogs = setOf("m3-catalog"),
        uiBuilderService = service,
        uiBuilderAuthorization = authorization,
      )
      .also(ServeHttpServer::start)
  private val client = OkHttpClient.Builder().followRedirects(false).build()

  @AfterTest
  fun tearDown() {
    server.stop()
    registry.close()
  }

  private fun url(path: String) = "http://127.0.0.1:${server.port}$path"

  private fun get(path: String, actor: String?): Pair<Int, String> {
    val builder = Request.Builder().url(url(path)).header("Accept", "text/html")
    if (actor != null) builder.header("X-Test-Actor", actor)
    return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  private fun post(
    path: String,
    form: FormBody,
    actor: String? = "github:owner",
    origin: String? = "http://127.0.0.1:${server.port}",
  ): Pair<Int, String> {
    val builder = Request.Builder().url(url(path)).post(form).header("Accept", "text/html")
    if (actor != null) builder.header("X-Test-Actor", actor)
    if (origin != null) builder.header("Origin", origin)
    return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
  }

  @Test
  fun `the owner sees who else can open the design, and nobody else does`() {
    val (code, page) = get("/ui-builder/m3-catalog/screen/access", actor = "github:owner")
    assertEquals(200, code)
    assertTrue(page.contains("agent:abc123"), page)
    assertTrue(page.contains("github:owner"), page)
    assertTrue(page.contains("name=\"actorId\""), "the page has to offer the form: $page")

    // A visitor who can open the design but does not own it is refused the list, because the
    // service refuses it — the route must not answer a question it did not get an answer to.
    val (otherCode, otherPage) = get("/ui-builder/m3-catalog/screen/access", actor = "github:other")
    assertEquals(403, otherCode)
    assertFalse(otherPage.contains("agent:abc123"), otherPage)

    assertEquals(401, get("/ui-builder/m3-catalog/screen/access", actor = null).first)
    assertEquals(403, get("/ui-builder/m3-catalog/screen/access", actor = "forbidden").first)
    assertEquals(404, get("/ui-builder/not-served/screen/access", actor = "github:owner").first)
  }

  @Test
  fun `an agent acting for the owner manages sharing as the owner does`() {
    val (code, page) = get("/ui-builder/m3-catalog/screen/access", actor = "agent")
    assertEquals(200, code)
    assertTrue(page.contains("agent:abc123"), page)
  }

  @Test
  fun `sharing and revoking reach the service as the mutations they claim to be`() {
    val (code, page) =
      post(
        "/ui-builder/m3-catalog/screen/access",
        FormBody.Builder().add("actorId", "github:colleague").add("role", "editor").build(),
      )
    assertEquals(200, code)
    val shared = mutations.single()
    assertEquals(3, shared.baseAccessRevision, "the revision comes from the read, not the form")
    val grant = shared.mutations.single() as GrantActorAccessMutationV1
    assertEquals("github:colleague", grant.actorId)
    assertEquals(DesignAccessRoleV1.EDITOR, grant.role)
    assertEquals(
      listOf(DesignAccessActionV1.READ, DesignAccessActionV1.WRITE, DesignAccessActionV1.EXPORT),
      grant.allowedActions,
      "neither shared role may manage access or delete",
    )
    assertTrue(page.contains("github:colleague"), page)

    mutations.clear()
    val (revokedCode, revokedPage) =
      post(
        "/ui-builder/m3-catalog/screen/access",
        FormBody.Builder().add("actorId", "github:colleague").add("action", "revoke").build(),
      )
    assertEquals(200, revokedCode)
    assertEquals(
      "github:colleague",
      (mutations.single().mutations.single() as RevokeActorAccessMutationV1).actorId,
    )
    assertFalse(revokedPage.contains(">github:colleague<"), revokedPage)
  }

  @Test
  fun `a viewer is shared read and export and nothing else`() {
    post(
      "/ui-builder/m3-catalog/screen/access",
      FormBody.Builder().add("actorId", "github:colleague").build(),
    )
    val grant = mutations.single().mutations.single() as GrantActorAccessMutationV1
    assertEquals(DesignAccessRoleV1.VIEWER, grant.role, "an unnamed role shares read-only")
    assertEquals(
      listOf(DesignAccessActionV1.READ, DesignAccessActionV1.EXPORT),
      grant.allowedActions,
    )
  }

  @Test
  fun `nothing is shared by a cross-site form, by a stranger, or by an empty field`() {
    val form = FormBody.Builder().add("actorId", "github:colleague").build()
    assertEquals(
      403,
      post("/ui-builder/m3-catalog/screen/access", form, origin = "https://evil.example").first,
    )
    assertEquals(403, post("/ui-builder/m3-catalog/screen/access", form, actor = "forbidden").first)
    assertEquals(403, post("/ui-builder/m3-catalog/screen/access", form, actor = "github:x").first)
    // The owner's own id is not a grant to add: an owner is not in the grant list at all, so this
    // would be a change that could only ever be undone by a revoke that removes nothing.
    post(
      "/ui-builder/m3-catalog/screen/access",
      FormBody.Builder().add("actorId", "github:owner").build(),
    )
    post("/ui-builder/m3-catalog/screen/access", FormBody.Builder().add("actorId", " ").build())
    assertTrue(mutations.isEmpty(), "no refusal may have changed access: $mutations")
  }
}
