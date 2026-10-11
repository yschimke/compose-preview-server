package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The comment routes: read the discussion, post, resolve a thread, watch for replies. Plain REST
 * plus one socket, since the released `UiBuilderRequestV1` union has no such requests and comments
 * are deliberately not part of the design document (see [ServeUiBuilderCommentStore]).
 *
 * **Authorised twice, on purpose:** the route capability gates UI-builder use, then every request
 * reads the design through the service as that actor, so the design's own access control decides.
 * Without the second check, a write-capable actor could post into designs they can't open and
 * enumerate design ids.
 *
 * [UI_BUILDER_COMMENTS_UPDATES_PATH] is a socket for an open page; [UI_BUILDER_COMMENTS_WATCH_PATH]
 * is a long poll for agents and scripts. Both sit on [ServeUiBuilderCommentStore.subscribe], so
 * they can't disagree.
 */
internal fun Route.installUiBuilderCommentRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderCommentStore,
) {
  get(UI_BUILDER_COMMENTS_PATH) {
    val actor =
      call.authorizedCommentActor(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    // An empty board, not a 404: no discussion yet is not a missing design.
    call.respondBoard(
      actor.shape(withContext(Dispatchers.IO) { store.readOrEmpty(actor.designId) })
    )
  }

  post(UI_BUILDER_COMMENTS_PATH) {
    val actor =
      call.authorizedCommentActor(
        service,
        authorization,
        UiBuilderRouteCapability.WRITE,
        signedInReadersMayTakePart = true,
      ) ?: return@post
    val request = call.receiveCommentBody(CommentPostRequest.serializer()) ?: return@post
    when (
      val result =
        withContext(Dispatchers.IO) { store.post(actor.designId, actor.actorId, request) }
    ) {
      is CommentWriteResult.Refused ->
        // 422 rather than 400: the body parsed and the request was understood; this is a fact
        // about what the caller asked for rather than about how they asked for it.
        call.respondCommentError(HttpStatusCode.UnprocessableEntity, result.reason)
      is CommentWriteResult.Stored ->
        call.respondBoard(actor.shape(result.board), HttpStatusCode.Created)
    }
  }

  post(UI_BUILDER_COMMENT_RESOLUTION_PATH) {
    val actor =
      call.authorizedCommentActor(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@post
    val threadId = call.parameters["threadId"].orEmpty()
    if (threadId.isBlank()) {
      call.respondCommentError(HttpStatusCode.BadRequest, "a thread id is required")
      return@post
    }
    val request = call.receiveCommentBody(CommentResolutionRequest.serializer()) ?: return@post
    when (
      val result =
        withContext(Dispatchers.IO) {
          store.resolve(actor.designId, actor.actorId, threadId, request.resolved)
        }
    ) {
      is CommentWriteResult.Refused ->
        call.respondCommentError(HttpStatusCode.NotFound, result.reason)
      is CommentWriteResult.Stored -> call.respondBoard(actor.shape(result.board))
    }
  }

  /**
   * "I have read this", for one thread or the whole board. Distinct from resolution (see
   * [ServeUiBuilderCommentStore.acknowledge]); per actor, and it clears the notice in an agent's
   * tool replies.
   */
  post(UI_BUILDER_COMMENTS_ACKNOWLEDGEMENT_PATH) {
    val actor =
      call.authorizedCommentActor(
        service,
        authorization,
        UiBuilderRouteCapability.WRITE,
        signedInReadersMayTakePart = true,
      ) ?: return@post
    call.respondAcknowledgement(store, actor, threadId = null)
  }

  post(UI_BUILDER_COMMENT_ACKNOWLEDGEMENT_PATH) {
    val actor =
      call.authorizedCommentActor(
        service,
        authorization,
        UiBuilderRouteCapability.WRITE,
        signedInReadersMayTakePart = true,
      ) ?: return@post
    val threadId = call.parameters["threadId"].orEmpty()
    if (threadId.isBlank()) {
      call.respondCommentError(HttpStatusCode.BadRequest, "a thread id is required")
      return@post
    }
    call.respondAcknowledgement(store, actor, threadId)
  }

  /** An emoji on one comment, added or removed: one route, since the client toggles a chip. */
  post(UI_BUILDER_COMMENT_REACTION_PATH) {
    val actor =
      call.authorizedCommentActor(
        service,
        authorization,
        UiBuilderRouteCapability.WRITE,
        signedInReadersMayTakePart = true,
      ) ?: return@post
    val commentId = call.parameters["commentId"].orEmpty()
    if (commentId.isBlank()) {
      call.respondCommentError(HttpStatusCode.BadRequest, "a comment id is required")
      return@post
    }
    val request = call.receiveCommentBody(CommentReactionRequest.serializer()) ?: return@post
    when (
      val result =
        withContext(Dispatchers.IO) {
          store.react(actor.designId, actor.actorId, commentId, request.reaction, request.on)
        }
    ) {
      is CommentWriteResult.Refused ->
        // 422 for a reaction the store will not keep, 404 for a comment that is not there: the
        // two are different questions and a client retries only one of them.
        call.respondCommentError(
          if (result.reason.startsWith("no such")) HttpStatusCode.NotFound
          else HttpStatusCode.UnprocessableEntity,
          result.reason,
        )
      is CommentWriteResult.Stored -> call.respondBoard(actor.shape(result.board))
    }
  }

  delete(UI_BUILDER_COMMENT_THREAD_PATH) {
    val actor =
      call.authorizedCommentActor(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@delete
    val threadId = call.parameters["threadId"].orEmpty()
    // The thread's opener may remove it; anybody else needs the design's own WRITE action.
    val editor = service.canWrite(actor.identity, actor.designId)
    when (
      val result =
        withContext(Dispatchers.IO) {
          store.deleteThread(actor.designId, actor.actorId, threadId, mayDeleteAnyThread = editor)
        }
    ) {
      is CommentWriteResult.Refused ->
        call.respondCommentError(
          if (result.forbidden) HttpStatusCode.Forbidden else HttpStatusCode.NotFound,
          result.reason,
        )
      is CommentWriteResult.Stored -> call.respondBoard(actor.shape(result.board))
    }
  }

  /**
   * The long poll: answer once the discussion moves past `afterSequence`, else 204 (never an empty
   * board, which would read as "emptied"). Bounded by [MAX_COMMENT_WAIT_SECONDS].
   */
  get(UI_BUILDER_COMMENTS_WATCH_PATH) {
    val actor =
      call.authorizedCommentActor(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    val designId = actor.designId
    val afterSequence = call.request.queryParameters["afterSequence"]?.toLongOrNull() ?: 0
    if (afterSequence < 0) {
      call.respondCommentError(HttpStatusCode.BadRequest, "afterSequence must not be negative")
      return@get
    }
    val waitSeconds =
      (call.request.queryParameters["waitSeconds"]?.toLongOrNull() ?: DEFAULT_COMMENT_WAIT_SECONDS)
        .coerceIn(0, MAX_COMMENT_WAIT_SECONDS)
    val board = store.awaitBoardAfter(designId, afterSequence, waitSeconds * 1000)
    if (board == null) call.respondText("", status = HttpStatusCode.NoContent)
    else call.respondBoard(actor.shape(board))
  }

  /**
   * The socket: every accepted write on this design, as the whole board. Server-push only (replies
   * go over the authenticated HTTP route). The current board is sent on connect when it is past the
   * client's cursor.
   */
  webSocket(UI_BUILDER_COMMENTS_UPDATES_PATH) {
    val designId = call.parameters["designId"].orEmpty()
    val afterSequence = call.request.queryParameters["afterSequence"]?.toLongOrNull()
    if (designId.isBlank() || (afterSequence != null && afterSequence < 0)) {
      close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid design id or sequence cursor"))
      return@webSocket
    }
    val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)
    val actor = (decision as? UiBuilderAuthorizationDecision.Authorized)?.actor
    if (actor == null) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UI-builder read access required"))
      return@webSocket
    }
    if (!service.canRead(actor, designId)) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no such design"))
      return@webSocket
    }
    val view = service.publicReaderView(actor, designId)

    val boards = Channel<StoredCommentBoard>(COMMENT_SOCKET_BUFFER)
    val overflowed = AtomicBoolean(false)
    val subscription =
      store.subscribe(designId) { board ->
        if (boards.trySend(board).isFailure) {
          overflowed.set(true)
          boards.close()
        }
      }
    try {
      coroutineScope {
        val sender = launch {
          for (board in boards) {
            val shaped = view?.board(board) ?: board
            send(
              Frame.Text(COMMENT_ROUTE_JSON.encodeToString(StoredCommentBoard.serializer(), shaped))
            )
          }
          if (overflowed.get()) {
            close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "subscriber is too slow"))
          }
        }
        // Subscribed first, then read: the other order drops a comment posted in between.
        val current = withContext(Dispatchers.IO) { store.readOrEmpty(designId) }
        if (current.sequence > (afterSequence ?: -1)) boards.trySend(current)
        try {
          for (ignored in incoming) {
            // Server-push only; a comment is posted over the authenticated HTTP route.
          }
        } finally {
          boards.close()
          sender.cancelAndJoin()
        }
      }
    } finally {
      subscription.close()
    }
  }
}

/**
 * One caller admitted to one design's discussion.
 *
 * [identity] is the whole authenticated actor, delegation included, for the questions only the
 * design's access control can answer (may this actor delete somebody else's thread).
 *
 * [view] is set when the caller reads the design only because it is public; see [PublicReaderView].
 */
private data class CommentActor(
  val actorId: String,
  val designId: String,
  val identity: AuthenticatedUiBuilderActor,
  val view: PublicReaderView? = null,
) {
  /** [board] as this caller may see it. */
  fun shape(board: StoredCommentBoard): StoredCommentBoard = view?.board(board) ?: board
}

/** The board once [threadId] — or all of it — is marked as read by this actor. */
private suspend fun ApplicationCall.respondAcknowledgement(
  store: ServeUiBuilderCommentStore,
  actor: CommentActor,
  threadId: String?,
) {
  when (
    val result =
      withContext(Dispatchers.IO) { store.acknowledge(actor.designId, actor.actorId, threadId) }
  ) {
    is CommentWriteResult.Refused -> respondCommentError(HttpStatusCode.NotFound, result.reason)
    is CommentWriteResult.Stored -> respondBoard(actor.shape(result.board))
  }
}

/** The caller and the design they may act on, or null once the refusal has been written. */
private suspend fun ApplicationCall.authorizedCommentActor(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
  /**
   * Whether a GitHub-signed-in, read-only viewer may still post, react and mark as read: commenting
   * isn't changing the design. Never the anonymous reader of a public box, nor a read-only agent
   * grant: a comment speaks for someone.
   */
  signedInReadersMayTakePart: Boolean = false,
): CommentActor? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val decision =
    authorization.authorize(this, capability).let { asked ->
      if (asked is UiBuilderAuthorizationDecision.Authorized || !signedInReadersMayTakePart) {
        asked
      } else {
        val read = authorization.authorize(this, UiBuilderRouteCapability.READ)
        if (
          read is UiBuilderAuthorizationDecision.Authorized &&
            read.actorId.startsWith(GITHUB_ACTOR_PREFIX) &&
            read.onBehalfOfActorId == null
        ) {
          read
        } else {
          asked
        }
      }
    }
  val actor =
    when (decision) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actor
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondCommentError(
          HttpStatusCode.Unauthorized,
          if (signedInReadersMayTakePart) "Sign in with GitHub to comment."
          else "authentication is required",
        )
        return null
      }
      UiBuilderAuthorizationDecision.Forbidden -> {
        respondCommentError(HttpStatusCode.Forbidden, "UI-builder access is required")
        return null
      }
    }
  val designId = parameters["designId"].orEmpty()
  if (designId.isBlank()) {
    respondCommentError(HttpStatusCode.BadRequest, "a design id is required")
    return null
  }
  if (!service.canRead(actor, designId)) {
    respondCommentError(HttpStatusCode.NotFound, "no such design")
    return null
  }
  // Authored under the agent's own id even when its authority came from the human who approved its
  // grant: a comment says who wrote it, and delegation decides what may be read, never who spoke.
  return CommentActor(
    actorId = actor.actorId,
    designId = designId,
    identity = actor,
    view = service.publicReaderView(actor, designId),
  )
}

private suspend fun <T> ApplicationCall.receiveCommentBody(
  serializer: kotlinx.serialization.DeserializationStrategy<T>
): T? {
  // The editor's own requests label their JSON; a session-cookie request that does not is not one
  // of them. Header-credential clients are left to the parser as before.
  if (ServeSameOriginRequests.isNonJsonSessionRequest(this)) {
    respondCommentError(
      HttpStatusCode.UnsupportedMediaType,
      "the comment request must be application/json",
    )
    return null
  }
  val bytes =
    withContext(Dispatchers.IO) {
      receiveStream().use { it.readNBytes(MAX_COMMENT_BODY_BYTES + 1) }
    }
  if (bytes.size > MAX_COMMENT_BODY_BYTES) {
    respondCommentError(HttpStatusCode.PayloadTooLarge, "the comment request is too large")
    return null
  }
  return try {
    COMMENT_ROUTE_JSON.decodeFromString(serializer, bytes.toString(StandardCharsets.UTF_8))
  } catch (_: SerializationException) {
    respondCommentError(HttpStatusCode.BadRequest, "the comment request could not be read")
    null
  }
}

private suspend fun ApplicationCall.respondBoard(
  board: StoredCommentBoard,
  status: HttpStatusCode = HttpStatusCode.OK,
) {
  respondText(
    COMMENT_ROUTE_JSON.encodeToString(StoredCommentBoard.serializer(), board),
    ContentType.Application.Json,
    status,
  )
}

private suspend fun ApplicationCall.respondCommentError(
  status: HttpStatusCode,
  message: String,
) {
  respondText(
    COMMENT_ROUTE_JSON.encodeToString(
      CommentErrorResponse.serializer(),
      CommentErrorResponse(message),
    ),
    ContentType.Application.Json,
    status,
  )
}

internal const val UI_BUILDER_COMMENTS_PATH = "/api/ui-builder/v1/designs/{designId}/comments"

internal const val UI_BUILDER_COMMENTS_WATCH_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/watch"

internal const val UI_BUILDER_COMMENTS_UPDATES_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/updates"

internal const val UI_BUILDER_COMMENT_THREAD_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/{threadId}"

internal const val UI_BUILDER_COMMENT_RESOLUTION_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/{threadId}/resolution"

internal const val UI_BUILDER_COMMENTS_ACKNOWLEDGEMENT_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/acknowledgement"

internal const val UI_BUILDER_COMMENT_ACKNOWLEDGEMENT_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/{threadId}/acknowledgement"

internal const val UI_BUILDER_COMMENT_REACTION_PATH =
  "/api/ui-builder/v1/designs/{designId}/comments/{threadId}/{commentId}/reactions"

/** A comment is text. The bound is the body ceiling with room for the envelope around it. */
private const val MAX_COMMENT_BODY_BYTES = 64 * 1024

/** How long a watcher waits by default, and the most it may ask for. */
internal const val DEFAULT_COMMENT_WAIT_SECONDS: Long = 25

internal const val MAX_COMMENT_WAIT_SECONDS: Long = 120

/**
 * Room for a burst of replies; small because each payload is the whole board. A subscriber this far
 * behind is closed and can reconnect for the current state in one frame.
 */
private const val COMMENT_SOCKET_BUFFER = 32

private val COMMENT_ROUTE_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  // Tolerant on the way in, so a client from a newer release that sends a field this host has not
  // learned yet still gets its comment stored rather than a 400.
  ignoreUnknownKeys = true
}

/** The actor-id prefix a GitHub-signed-in person carries; see [ServeAgentGrants.githubActorId]. */
private const val GITHUB_ACTOR_PREFIX = "github:"
