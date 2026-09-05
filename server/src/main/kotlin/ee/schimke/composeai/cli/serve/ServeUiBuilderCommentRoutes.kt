package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.GetSnapshotRequestV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import ee.schimke.composeai.uibuilder.service.ProtocolRequestMapping
import ee.schimke.composeai.uibuilder.service.UiBuilderProtocolMapper
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse
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
 * The comment routes: read the discussion, say something, resolve a thread, watch for replies.
 *
 * Plain REST plus one socket, rather than protocol requests, for the reason the reference routes
 * already give: the released `UiBuilderRequestV1` union has no request for any of this, and adding
 * one means releasing `ui-builder-protocol` — to carry something that deliberately is not part of
 * the design document (see [ServeUiBuilderCommentStore]).
 *
 * **Authorised twice, on purpose**, exactly as the reference routes are. The route capability
 * decides whether this caller may use the UI-builder at all, and then every request reads the
 * design *through the service, as that actor*, so the design's own access control decides whether
 * there is a design here to discuss. Without the second check, an actor holding a write capability
 * could post into a design they cannot open and enumerate which design ids exist by watching which
 * writes succeeded.
 *
 * ### Two ways to watch, one feed
 *
 * [UI_BUILDER_COMMENTS_UPDATES_PATH] is a socket, for a page that is open.
 * [UI_BUILDER_COMMENTS_WATCH_PATH] is a long poll, for a client that cannot hold one — an agent
 * between tool calls, a script, a `curl` in a loop. Both are [ServeUiBuilderCommentStore.subscribe]
 * underneath, so neither can learn about a comment the other does not.
 */
internal fun Route.installUiBuilderCommentRoutes(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  store: ServeUiBuilderCommentStore,
) {
  get(UI_BUILDER_COMMENTS_PATH) {
    val designId =
      call.authorizedCommentDesign(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
    // An empty board rather than a 404: "nobody has commented yet" is the answer, and a design
    // with no discussion is not a design that is missing. The reference route says the opposite
    // because there an empty record and no record are genuinely different states.
    call.respondBoard(withContext(Dispatchers.IO) { store.readOrEmpty(designId) })
  }

  post(UI_BUILDER_COMMENTS_PATH) {
    val actor =
      call.authorizedCommentActor(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@post
    val request = call.receiveCommentBody(CommentPostRequest.serializer()) ?: return@post
    when (
      val result =
        withContext(Dispatchers.IO) { store.post(actor.designId, actor.actorId, request) }
    ) {
      is CommentWriteResult.Refused ->
        // 422 rather than 400: the body parsed and the request was understood; this is a fact
        // about what the caller asked for rather than about how they asked for it.
        call.respondCommentError(HttpStatusCode.UnprocessableEntity, result.reason)
      is CommentWriteResult.Stored -> call.respondBoard(result.board, HttpStatusCode.Created)
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
      is CommentWriteResult.Stored -> call.respondBoard(result.board)
    }
  }

  delete(UI_BUILDER_COMMENT_THREAD_PATH) {
    val actor =
      call.authorizedCommentActor(service, authorization, UiBuilderRouteCapability.WRITE)
        ?: return@delete
    val threadId = call.parameters["threadId"].orEmpty()
    when (
      val result = withContext(Dispatchers.IO) { store.deleteThread(actor.designId, threadId) }
    ) {
      is CommentWriteResult.Refused ->
        call.respondCommentError(HttpStatusCode.NotFound, result.reason)
      is CommentWriteResult.Stored -> call.respondBoard(result.board)
    }
  }

  /**
   * The long poll: answer once the discussion has moved past `afterSequence`, or say nothing.
   *
   * 204 on timeout rather than an empty board, so a caller that loops on this cannot mistake "no
   * news" for "the discussion was emptied". The wait is bounded by [MAX_COMMENT_WAIT_SECONDS] so a
   * client cannot pin a request thread here indefinitely.
   */
  get(UI_BUILDER_COMMENTS_WATCH_PATH) {
    val designId =
      call.authorizedCommentDesign(service, authorization, UiBuilderRouteCapability.READ)
        ?: return@get
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
    else call.respondBoard(board)
  }

  /**
   * The socket: every accepted write on this design, as the whole board.
   *
   * Server-push only, like the design's own updates socket — a reply is posted over the
   * authenticated HTTP route, so nothing a client sends here needs reading. The current board is
   * sent on connect when it is already past the client's cursor, so a page that opens does not have
   * to fetch and subscribe and reconcile the two.
   */
  webSocket(UI_BUILDER_COMMENTS_UPDATES_PATH) {
    val designId = call.parameters["designId"].orEmpty()
    val afterSequence = call.request.queryParameters["afterSequence"]?.toLongOrNull()
    if (designId.isBlank() || (afterSequence != null && afterSequence < 0)) {
      close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "invalid design id or sequence cursor"))
      return@webSocket
    }
    val decision = authorization.authorize(call, UiBuilderRouteCapability.READ)
    val actorId = (decision as? UiBuilderAuthorizationDecision.Authorized)?.actorId
    if (actorId == null) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "UI-builder read access required"))
      return@webSocket
    }
    if (!service.canRead(designId, actorId)) {
      close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "no such design"))
      return@webSocket
    }

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
            send(
              Frame.Text(COMMENT_ROUTE_JSON.encodeToString(StoredCommentBoard.serializer(), board))
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

/** One caller admitted to one design's discussion. */
private data class CommentActor(val actorId: String, val designId: String)

/**
 * Whether this actor can read this design, asked of the service rather than assumed.
 *
 * The socket cannot use [authorizedCommentDesign] — that one writes an HTTP refusal — so the check
 * is here in the shape both can use.
 */
private suspend fun UiBuilderServicePort.canRead(designId: String, actorId: String): Boolean {
  if (designId.isBlank()) return false
  val mapping =
    UiBuilderProtocolMapper.toServiceCall(
      AuthenticatedUiBuilderActor(actorId),
      GetSnapshotRequestV1(designId = designId, revision = null),
    )
  val response = (mapping as? ProtocolRequestMapping.Mapped)?.let { execute(it.call) }
  return response is UiBuilderServiceResponse.Snapshot
}

private suspend fun ApplicationCall.authorizedCommentDesign(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): String? = authorizedCommentActor(service, authorization, capability)?.designId

/** The caller and the design they may act on, or null once the refusal has been written. */
private suspend fun ApplicationCall.authorizedCommentActor(
  service: UiBuilderServicePort,
  authorization: ServeUiBuilderAuthorization,
  capability: UiBuilderRouteCapability,
): CommentActor? {
  response.headers.append(HttpHeaders.CacheControl, "no-store")
  val actorId =
    when (val decision = authorization.authorize(this, capability)) {
      is UiBuilderAuthorizationDecision.Authorized -> decision.actorId
      UiBuilderAuthorizationDecision.Missing -> {
        response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
        respondCommentError(HttpStatusCode.Unauthorized, "authentication is required")
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
  if (!service.canRead(designId, actorId)) {
    respondCommentError(HttpStatusCode.NotFound, "no such design")
    return null
  }
  return CommentActor(actorId = actorId, designId = designId)
}

private suspend fun <T> ApplicationCall.receiveCommentBody(
  serializer: kotlinx.serialization.DeserializationStrategy<T>
): T? {
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

/** A comment is text. The bound is the body ceiling with room for the envelope around it. */
private const val MAX_COMMENT_BODY_BYTES = 64 * 1024

/** How long a watcher waits by default, and the most it may ask for. */
internal const val DEFAULT_COMMENT_WAIT_SECONDS: Long = 25

internal const val MAX_COMMENT_WAIT_SECONDS: Long = 120

/**
 * Enough room for a burst of replies, and small because the payload is the whole board.
 *
 * A subscriber that falls this far behind is closed rather than buffered: it can reconnect and be
 * told the current state in one frame, which is cheaper than replaying a queue it no longer needs.
 */
private const val COMMENT_SOCKET_BUFFER = 32

private val COMMENT_ROUTE_JSON = Json {
  encodeDefaults = true
  explicitNulls = false
  // Tolerant on the way in, so a client from a newer release that sends a field this host has not
  // learned yet still gets its comment stored rather than a 400.
  ignoreUnknownKeys = true
}
