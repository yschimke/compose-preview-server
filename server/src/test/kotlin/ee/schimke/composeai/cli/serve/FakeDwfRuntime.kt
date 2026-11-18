package ee.schimke.composeai.cli.serve

import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * A [DwfApkRuntime] with no process behind it: it records every command the sessions send and
 * answers `screenshot` by writing a 1×1 PNG to the requested path and `watchface status` with a
 * healthy app face — the daemon's contract as `DwfApkDaemonBridge` relies on it.
 */
internal class FakeDwfRuntime(
  private val dir: File = Files.createTempDirectory("fake-dwf").toFile(),
  var availability: DwfApkRuntime.Availability = DwfApkRuntime.Availability.Ready,
) : DwfApkRuntime {
  val packaged = mutableListOf<DwfSource>()
  val connections = mutableListOf<FakeConnection>()

  /** Commands across every connection, in order. */
  val commands: List<DwfCommand>
    get() = connections.flatMap { it.log }

  var statusAnswer: JsonElement? = healthyStatus()
  var failNext: String? = null

  /** When set, [packageApk] fails the way the packager does: a refusal with these diagnostics. */
  var packagingRefusal: List<String>? = null

  override fun availability(): DwfApkRuntime.Availability = availability

  override fun packageApk(source: DwfSource): File {
    packagingRefusal?.let { throw DwfPackagingFailed(it) }
    packaged += source
    return File(dir, "wff-${source.digest().take(12)}.apk").also { it.writeBytes(byteArrayOf(1)) }
  }

  override fun connect(initialApk: File): DwfDaemonConnection =
    FakeConnection().also { connections += it }

  inner class FakeConnection : DwfDaemonConnection {
    val log = mutableListOf<DwfCommand>()
    override var alive: Boolean = true
    var closed = false

    override fun command(command: DwfCommand): JsonElement? {
      failNext?.let {
        failNext = null
        throw DwfRuntimeException(it)
      }
      log += command
      return when {
        command.cmd == "screenshot" -> {
          File(command.payload["path"]!!.let { (it as JsonPrimitive).content }).writeBytes(PNG_1X1)
          null
        }
        command.cmd == "watchface" && op(command) == "status" -> statusAnswer
        else -> null
      }
    }

    override fun close() {
      closed = true
      alive = false
    }
  }

  companion object {
    fun op(command: DwfCommand): String? = (command.payload["op"] as? JsonPrimitive)?.contentOrNull

    fun healthyStatus(): JsonObject = buildJsonObject {
      put("kind", "app")
      put("frames", 3)
    }

    /** A valid 1×1 PNG. */
    val PNG_1X1: ByteArray =
      java.util.Base64.getDecoder()
        .decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
        )
  }
}

internal fun source(xml: String = "<WatchFace/>", version: Int = 2) =
  DwfSource(wffXml = xml, wffVersion = version, label = "test face")
