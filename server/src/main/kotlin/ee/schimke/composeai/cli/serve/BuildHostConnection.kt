package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.buildhost.BuildHostCodec
import ee.schimke.composeai.buildhost.BuildHostEnvelope
import ee.schimke.composeai.buildhost.BuildHostEvent
import ee.schimke.composeai.buildhost.BuildHostProtocol
import ee.schimke.composeai.buildhost.BuildHostRequest
import ee.schimke.composeai.buildhost.BuildHostResponse
import java.io.BufferedReader
import java.io.Writer

/**
 * Speaks the build-host protocol over a pair of streams.
 *
 * Separated from [BuildHostProcess], which owns the process, so the protocol behaviour that matters
 * — correlation, the handshake, what happens when the far end dies mid-request — can be tested over
 * a pipe instead of by spawning a CLI.
 *
 * **Not thread-safe, deliberately.** One connection serves one server, and the operations mutate a
 * Gradle build; two in flight at once would be a bug rather than throughput, so the type does not
 * pretend otherwise. Callers that need concurrency need a second build host, not a lock here.
 */
internal class BuildHostConnection(
  private val requests: Writer,
  private val responses: BufferedReader,
  /** Where build output goes. The server decides; the host only forwards. */
  private val onLog: (String) -> Unit,
) {

  private var nextId = 1L

  /** Null until [handshake] succeeds, and the reason this connection is unusable after it fails. */
  var unusable: String? = null
    private set

  /**
   * Agrees a protocol version, or marks the connection [unusable].
   *
   * Called once before anything else. A mismatch is not an error to retry: the two ends are
   * released from different repositories and a version that disagrees means the messages mean
   * different things. The server's answer is to serve without a build host, which is a real mode
   * rather than a degraded one.
   */
  fun handshake(): Boolean {
    val response = exchange(BuildHostRequest.Handshake()) ?: return false
    return when (response) {
      is BuildHostResponse.Handshake -> true
      is BuildHostResponse.Failure -> {
        unusable = response.message
        false
      }
      else -> {
        unusable = "handshake answered with ${response::class.simpleName}"
        false
      }
    }
  }

  /**
   * Sends [request] and returns its answer, or null when the connection is or becomes unusable.
   *
   * Null rather than an exception because every caller's fallback is the same — the neutral value
   * the operation would have had without a build host — and a build host dying mid-serve should
   * degrade the server to catalogs and bundles, not take it down. The reason is kept in [unusable]
   * so it can be reported once instead of per call.
   */
  fun exchange(request: BuildHostRequest): BuildHostResponse? {
    unusable?.let {
      return null
    }
    val id = nextId++
    try {
      requests.write(BuildHostCodec.encode(BuildHostEnvelope(id, request = request)))
      requests.write("\n")
      requests.flush()
    } catch (t: Throwable) {
      unusable = "could not write to the build host: ${t.message ?: t.javaClass.name}"
      return null
    }

    // Events for this operation arrive before its response, so read until the response shows up.
    while (true) {
      val line =
        try {
          responses.readLine()
        } catch (t: Throwable) {
          unusable = "could not read from the build host: ${t.message ?: t.javaClass.name}"
          return null
        }
      if (line == null) {
        // The host exited. Every later call short-circuits on `unusable` rather than each one
        // waiting on a stream that will never produce another line.
        unusable = "the build host exited without answering"
        return null
      }
      if (line.isBlank()) continue

      val envelope =
        try {
          BuildHostCodec.decode(line)
        } catch (t: Throwable) {
          // Skipping rather than failing: a line this server cannot parse is one a NEWER host
          // emitted, and the protocol version already gates the changes that alter meaning. Failing
          // here would turn every additive message into a breaking one.
          continue
        }

      envelope.event?.let { event ->
        when (event) {
          is BuildHostEvent.Log -> if (envelope.id == id) onLog(event.line)
        }
        continue
      }

      val response = envelope.response ?: continue
      if (envelope.id != id) continue
      if (response is BuildHostResponse.Failure) unusableIfFatal(request, response)
      return response
    }
  }

  /**
   * A refused handshake poisons the connection; a failed operation does not.
   *
   * The distinction is the difference between "this host cannot talk to me" and "that build did not
   * work" — the first means stop asking, the second is ordinary and the next request may well
   * succeed.
   */
  private fun unusableIfFatal(request: BuildHostRequest, failure: BuildHostResponse.Failure) {
    if (request is BuildHostRequest.Handshake) unusable = failure.message
  }

  companion object {
    /** The version this server speaks, so a skew is reported against a named number. */
    const val PROTOCOL_VERSION: Int = BuildHostProtocol.VERSION
  }
}
