package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.InteractiveInputKind
import ee.schimke.composeai.daemon.protocol.PreviewOverrides
import ee.schimke.composeai.daemon.protocol.StreamCodec
import ee.schimke.composeai.daemon.protocol.StreamFrameParams

/**
 * One live streamed-frame connection over the daemon's `stream/start` + `interactive/input`
 * protocol (tier-2). Frames are pushed by the daemon and decoded inline, unlike the
 * [ServeStreamSession] snapshot fallback.
 *
 * [tryStart] returns null when the backend can't stream, so the WebSocket route can fall back.
 * Transport-agnostic: output goes through [send], so it's testable without a socket.
 */
class ServeLiveSession
private constructor(
  private val renderHost: ServeHost,
  private var previewId: String,
  private var overrides: Map<String, String>,
  private val codec: StreamCodec?,
  private val maxFps: Int?,
  private val send: (String) -> Unit,
  private val system: String,
  /**
   * Frame telemetry for `/status.json`, or null when nobody is collecting (tests, and the fallback
   * lanes). One recorder for the socket's whole life — see [LiveFramePerfStats.Socket].
   */
  private val frameStats: LiveFramePerfStats.Socket? = null,
) {
  @Volatile private var handle: StreamHandle? = null

  /**
   * The last client-reported visibility, re-applied to every stream this socket opens: streams
   * start visible daemon-side, so a hidden socket that restarts its stream must say so again.
   */
  @Volatile private var visible: Boolean = true

  @Volatile private var visibilityFps: Int? = null

  /**
   * Monotonic frame counter for the life of this socket. See [onFrame] for why it isn't the
   * daemon's.
   */
  private val seq = java.util.concurrent.atomic.AtomicLong(0)

  /**
   * This catalog's override policy (e.g. always-dark), applied to every client override map before
   * parsing. Resolved from [system] so no socket lane can skip it; see [ServeWeb.SystemDisplay].
   */
  private fun normalize(overrides: Map<String, String>): Map<String, String> =
    ServeWeb.SystemDisplay.normalizeOverrideParams(system, overrides)

  /** Handle one client text message: forward input, restart the stream on new overrides, etc. */
  fun onClientMessage(text: String) {
    when (val message = ServeStreamProtocol.parseClient(text)) {
      is ServeStreamProtocol.ClientMessage.SetOverrides -> {
        val normalized = normalize(message.overrides)
        when (val parsed = parseFor(previewId, normalized)) {
          is OverrideParse.Invalid -> send(ServeStreamProtocol.errorMessage(parsed.message))
          is OverrideParse.Ok -> {
            // stream/start fixes overrides for the held session, so an override change restarts it.
            overrides = normalized
            restart(parsed.overrides)
          }
        }
      }
      is ServeStreamProtocol.ClientMessage.Input -> dispatchInput(message)
      is ServeStreamProtocol.ClientMessage.Switch -> switchTo(message)
      is ServeStreamProtocol.ClientMessage.Visibility -> {
        // Remembered so a later `setOverrides` or `switch` while hidden doesn't come back at full
        // rate.
        visible = message.visible
        visibilityFps = message.fps
        handle?.visibility(message.visible, message.fps)
      }
      // Frames are pushed by the daemon; an explicit refresh is a no-op on the live lane.
      ServeStreamProtocol.ClientMessage.RequestFrame -> Unit
      is ServeStreamProtocol.ClientMessage.Unsupported ->
        send(ServeStreamProtocol.errorMessage(message.reason))
    }
  }

  /** Tear down the daemon stream. Idempotent. */
  fun close() {
    handle?.close()
    handle = null
    frameStats?.close()
  }

  private fun dispatchInput(input: ServeStreamProtocol.ClientMessage.Input) {
    val kind = parseKind(input.kind)
    if (kind == null) {
      send(ServeStreamProtocol.errorMessage("unknown input kind: ${input.kind}"))
      return
    }
    handle?.input(
      kind = kind,
      pixelX = input.pixelX,
      pixelY = input.pixelY,
      pointerId = input.pointerId,
      scrollDeltaY = input.scrollDeltaY,
      keyCode = input.keyCode,
      text = input.text,
      pointerType = input.pointerType,
    )
  }

  private fun restart(parsed: PreviewOverrides) {
    handle?.close()
    handle =
      renderHost.subscribeStream(previewId, parsed, codec, maxFps, onFrame = ::onFrame)?.also {
        applyVisibility(it)
      }
        ?: run {
          send(ServeStreamProtocol.errorMessage("live stream ended"))
          null
        }
  }

  /** Carry the client's last-reported visibility onto a freshly-opened stream. */
  private fun applyVisibility(handle: StreamHandle) {
    if (!visible) handle.visibility(false, visibilityFps)
  }

  /**
   * Move to a different preview without reconnecting. The new stream opens before the old one
   * drops, so a failed switch reports an error and leaves the current view intact.
   */
  private fun switchTo(message: ServeStreamProtocol.ClientMessage.Switch) {
    val nextOverrides = message.overrides?.let(::normalize) ?: overrides
    val parsed =
      when (val p = parseFor(message.previewId, nextOverrides)) {
        is OverrideParse.Invalid -> {
          send(ServeStreamProtocol.errorMessage(p.message))
          return
        }
        is OverrideParse.Ok -> p.overrides
      }
    val next =
      renderHost.subscribeStream(message.previewId, parsed, codec, maxFps, onFrame = ::onFrame)
    if (next == null) {
      send(ServeStreamProtocol.errorMessage("cannot switch to preview: ${message.previewId}"))
      return
    }
    handle?.close()
    handle = next
    applyVisibility(next)
    previewId = message.previewId
    overrides = nextOverrides
    frameStats?.watching(message.previewId)
  }

  /**
   * Parse [params] as [id]'s overrides, first expanding a `themeProvider` into the colour seeds
   * that apply it to a replayed document ([ServeThemeReplay]); a replayed preview has no
   * composition to wrap. Every override-carrying message and [tryStart]'s initial query go through
   * here. Callers store [params] unexpanded, since seeds are per preview and a `switch` must
   * re-derive them.
   */
  private fun parseFor(id: String, params: Map<String, String>): OverrideParse =
    ServeRcPlayerIds.parseOverrides(
      ServeThemeReplay.expand(renderHost, id, params).params,
      knobKindsFor(id),
      declaredThemeFqns(),
    )

  /**
   * Declared knob kinds for [id], so a bare `knob.<key>=<value>` message is typed from the preview.
   */
  private fun knobKindsFor(id: String): Map<String, String> =
    ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == id })

  /**
   * The session's declared `@ThemeCatalog` provider FQNs, so a `themeProvider` this catalog never
   * declared is reported as an error rather than silently rendering the default theme.
   */
  private fun declaredThemeFqns(): Set<String> =
    renderHost.declaredThemes.map { it.providerFqn }.toSet()

  private fun onFrame(frame: StreamFrameParams) {
    // `unchanged` heartbeats carry no payload. Counted before returning: the painted/heartbeat
    // split tells a slow render loop from idle backoff.
    val payload =
      frame.payloadBase64
        ?: run {
          frameStats?.recordHeartbeat()
          return
        }
    frameStats?.recordFrame(payload.length)
    val codec = frame.codec?.name?.lowercase() ?: "png"
    // This connection's own sequence, not the daemon's `frame.seq`: the daemon numbers per stream
    // from zero, and one socket outlives several streams (each `setOverrides` [restart] and
    // [switchTo]). Relaying them would jump backwards, and a client ordering paints by `seq` would
    // reject every later frame as stale. [ServeStreamSession] numbers its snapshot lane the same
    // way.
    send(
      ServeStreamProtocol.frameMessage(
        seq.getAndIncrement(),
        frame.widthPx,
        frame.heightPx,
        payload,
        codec,
      )
    )
  }

  companion object {
    /** Wire spellings of the input kinds a browser can produce. */
    private fun parseKind(wire: String): InteractiveInputKind? =
      when (wire) {
        "click" -> InteractiveInputKind.CLICK
        "pointerDown" -> InteractiveInputKind.POINTER_DOWN
        "pointerMove" -> InteractiveInputKind.POINTER_MOVE
        "pointerUp" -> InteractiveInputKind.POINTER_UP
        "rotaryScroll" -> InteractiveInputKind.ROTARY_SCROLL
        "keyDown" -> InteractiveInputKind.KEY_DOWN
        "keyUp" -> InteractiveInputKind.KEY_UP
        else -> null
      }

    /**
     * Try to open a daemon-backed live stream. Returns null when streaming is unsupported or the
     * initial overrides are invalid; either way the caller falls back to the snapshot lane, which
     * re-parses and reports the reason once.
     */
    fun tryStart(
      renderHost: ServeHost,
      previewId: String,
      overrides: Map<String, String>,
      codec: StreamCodec? = null,
      maxFps: Int? = null,
      send: (String) -> Unit,
      /** Catalog id, for the per-system override policy. Required: there is no "no policy" case. */
      system: String,
      /** Where this socket's frame telemetry lands; null when nothing is collecting it. */
      frameStats: LiveFramePerfStats? = null,
      onUnavailable: ((String) -> Unit)? = null,
    ): ServeLiveSession? {
      val knobKinds =
        ServeOverrides.declaredKnobKinds(renderHost.previews.firstOrNull { it.id == previewId })
      val normalizedOverrides = ServeWeb.SystemDisplay.normalizeOverrideParams(system, overrides)
      // Validate the initial query too. Degrading to `PreviewOverrides()` would stream the default
      // theme to a client that asked for another, and a later frame would clear the viewer's error
      // overlay. Refuse instead and let [ServeStreamSession] report the reason once.
      val initial =
        when (
          val parsed =
            ServeRcPlayerIds.parseOverrides(
              ServeThemeReplay.expand(renderHost, previewId, normalizedOverrides).params,
              knobKinds,
              renderHost.declaredThemes.map { it.providerFqn }.toSet(),
            )
        ) {
          is OverrideParse.Invalid -> {
            onUnavailable?.invoke(parsed.message)
            return null
          }
          is OverrideParse.Ok -> parsed.overrides
        }
      // Opened before the subscribe because the broadcast hub replays its last frame into `onFrame`
      // synchronously for a late joiner. Closed below if the open fails, so a stream that never
      // started isn't counted.
      val recorder = frameStats?.openSocket(system, previewId)
      val session =
        ServeLiveSession(
          renderHost,
          previewId,
          normalizedOverrides,
          codec,
          maxFps,
          send,
          system,
          recorder,
        )
      session.handle =
        renderHost.subscribeStream(
          previewId,
          initial,
          codec,
          maxFps,
          onUnavailable = onUnavailable,
          onFrame = session::onFrame,
        )
          ?: run {
            recorder?.close()
            return null
          }
      return session
    }
  }
}
