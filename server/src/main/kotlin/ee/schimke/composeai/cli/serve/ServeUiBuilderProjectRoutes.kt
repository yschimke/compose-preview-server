package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal fun Route.installUiBuilderProjectRoutes(
  projects: ServeUiBuilderProjects,
  authorization: ServeUiBuilderAuthorization,
  grants: ServeUiBuilderGrantScope.Lookup? = null,
) {
  get("/ui-builder/projects") {
    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
    call.respondText(uiBuilderProjectsPage(), ContentType.Text.Html)
  }
  route("/api/ui-builder/v1/projects") {
    get {
      call.projectAction(authorization, UiBuilderRouteCapability.READ, grants) { actor ->
        JsonArray(
          projects.store
            .all()
            .filter { it.role(actor) != null }
            .map { PROJECT_JSON.encodeToJsonElement(it) }
        )
      }
    }
    post {
      call.projectAction(authorization, UiBuilderRouteCapability.WRITE, grants) { actor ->
        val request = call.projectBody<ProjectCreate>()
        PROJECT_JSON.encodeToJsonElement(projects.create(actor, request, request.githubToken))
      }
    }
    route("/{projectId}") {
      get {
        call.projectAction(authorization, UiBuilderRouteCapability.READ, grants) { actor ->
          PROJECT_JSON.encodeToJsonElement(
            projects.permitted(actor, call.parameters["projectId"].orEmpty())
          )
        }
      }
      put("/files") {
        call.projectAction(authorization, UiBuilderRouteCapability.WRITE, grants) { actor ->
          PROJECT_JSON.encodeToJsonElement(
            projects.putFile(
              actor,
              call.parameters["projectId"].orEmpty(),
              call.projectBody<ProjectPutFile>(),
            )
          )
        }
      }
      put("/members") {
        call.projectAction(authorization, UiBuilderRouteCapability.WRITE, grants) { actor ->
          PROJECT_JSON.encodeToJsonElement(
            projects.members(
              actor,
              call.parameters["projectId"].orEmpty(),
              call.projectBody<ProjectMembers>(),
            )
          )
        }
      }
      get("/review") {
        call.projectAction(authorization, UiBuilderRouteCapability.READ, grants) { actor ->
          PROJECT_JSON.encodeToJsonElement(
            projects.review(actor, call.parameters["projectId"].orEmpty())
          )
        }
      }
      post("/publish") {
        call.projectAction(authorization, UiBuilderRouteCapability.WRITE, grants) { actor ->
          val request = call.projectBody<ProjectPublish>()
          PROJECT_JSON.encodeToJsonElement(
            projects.publish(
              actor,
              call.parameters["projectId"].orEmpty(),
              request,
              request.githubToken.orEmpty(),
            )
          )
        }
      }
    }
  }
}

private suspend fun ApplicationCall.projectAction(
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
  grants: ServeUiBuilderGrantScope.Lookup?,
  action: suspend (AuthenticatedUiBuilderActor) -> JsonElement,
) {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actor =
    when (val decision = authorization.authorize(this, capability)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        projectError(HttpStatusCode.Unauthorized, "sign in to open your projects")
        return
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        projectError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return
      }
    }
  if (
    actor.onBehalfOfActorId != null &&
      grants?.designsFor(actor.actorId, actor.onBehalfOfActorId!!) != null
  ) {
    projectError(
      HttpStatusCode.Forbidden,
      "a design-scoped grant cannot access an entire project; use the design API",
    )
    return
  }
  try {
    val result = withContext(Dispatchers.IO) { action(actor) }
    respondText(
      PROJECT_JSON.encodeToString(JsonElement.serializer(), result),
      ContentType.Application.Json,
    )
  } catch (_: ProjectNotFound) {
    projectError(HttpStatusCode.NotFound, "project not found")
  } catch (failure: ProjectAccessDenied) {
    projectError(HttpStatusCode.Forbidden, failure.message ?: "project access denied")
  } catch (_: SerializationException) {
    projectError(HttpStatusCode.BadRequest, "project JSON could not be read")
  } catch (failure: IllegalArgumentException) {
    projectError(HttpStatusCode.UnprocessableEntity, failure.message ?: "invalid project request")
  } catch (failure: IllegalStateException) {
    projectError(HttpStatusCode.Conflict, failure.message ?: "project changed; reload")
  } catch (_: java.io.IOException) {
    projectError(
      HttpStatusCode.ServiceUnavailable,
      "project storage or GitHub is unavailable; your working copy is retained",
    )
  }
}

private suspend inline fun <reified T> ApplicationCall.projectBody(): T {
  val length = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
  require(length == null || length <= MAX_PROJECT_FILE_BYTES + 65536) {
    "project request too large"
  }
  val channel = receiveChannel()
  val bytes = java.io.ByteArrayOutputStream()
  val buffer = ByteArray(8192)
  while (true) {
    val count = channel.readAvailable(buffer, 0, buffer.size)
    if (count == -1) break
    require(bytes.size().toLong() + count <= MAX_PROJECT_FILE_BYTES + 65536) {
      "project request too large"
    }
    bytes.write(buffer, 0, count)
  }
  val text = bytes.toString(Charsets.UTF_8)
  return PROJECT_JSON.decodeFromString(text)
}

private suspend fun ApplicationCall.projectError(status: HttpStatusCode, message: String) =
  respondText(
    buildJsonObject { put("error", message) }.toString(),
    ContentType.Application.Json,
    status,
  )
