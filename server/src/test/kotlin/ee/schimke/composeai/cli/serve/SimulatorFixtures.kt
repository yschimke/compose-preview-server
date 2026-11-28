package ee.schimke.composeai.cli.serve

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** APKs and a runtime for the simulator's tests: no Android, no CLI, no JVM started. */
internal object SimulatorFixtures {
  /** A one-pixel PNG. */
  val PNG: ByteArray =
    Base64.getDecoder()
      .decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
      )

  fun phoneApk(pkg: String = "com.example.phone") = apk(pkg, watch = false)

  fun watchApk(pkg: String = "com.example.watch") = apk(pkg, watch = true)

  fun apk(pkg: String, watch: Boolean, dex: Boolean = true): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
      zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
      zip.write(
        binaryManifest(
          pkg,
          if (watch) "android.hardware.type.watch" else "android.intent.action.MAIN",
        ),
      )
      zip.closeEntry()
      if (dex) {
        zip.putNextEntry(ZipEntry("classes.dex"))
        zip.write("dex\n035\u0000".toByteArray())
        zip.closeEntry()
      }
    }
    return out.toByteArray()
  }

  /** A binary AXML manifest whose root names [packageName] and whose string pool holds [extra]. */
  fun binaryManifest(packageName: String, extra: String): ByteArray {
    val strings = listOf("manifest", "package", packageName, extra)
    val encoded = strings.map { s ->
      val utf8 = s.toByteArray(Charsets.UTF_8)
      byteArrayOf(s.length.toByte(), utf8.size.toByte()) + utf8 + byteArrayOf(0)
    }
    val headerSize = 28
    val offsetsSize = strings.size * 4
    var dataSize = 0
    val offsets = IntArray(strings.size)
    for (i in encoded.indices) {
      offsets[i] = dataSize
      dataSize += encoded[i].size
    }
    val padded = (dataSize + 3) and 3.inv()
    val poolSize = headerSize + offsetsSize + padded
    val elemSize = 16 + 40
    val total = 8 + poolSize + elemSize
    val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
    buf.putShort(0x0003).putShort(8).putInt(total)
    buf.putShort(0x0001).putShort(headerSize.toShort()).putInt(poolSize)
    buf.putInt(strings.size).putInt(0).putInt(0x100).putInt(headerSize + offsetsSize).putInt(0)
    offsets.forEach { buf.putInt(it) }
    encoded.forEach { buf.put(it) }
    repeat(padded - dataSize) { buf.put(0) }
    buf.putShort(0x0102).putShort(16).putInt(elemSize)
    buf.putInt(1).putInt(-1).putInt(-1).putInt(0)
    buf.putShort(20).putShort(20).putShort(1).putShort(0).putShort(0).putShort(0)
    buf.putInt(-1).putInt(1).putInt(2).putShort(8).put(0).put(0x03).putInt(2)
    return buf.array()
  }
}

/**
 * A runtime that boots instantly: each device reports `ready`, `screenshot` answers a one-pixel PNG
 * of [width]×[height], and every command is recorded in [commands].
 */
internal class FakeSimulatorRuntime(
  var availability: SimulatorRuntime.Availability = SimulatorRuntime.Availability.Ready,
  private val roots: List<String> = listOf("/srv/simulator"),
) : SimulatorRuntime {
  data class Sent(val sessionId: String, val device: SimulatorDevice, val command: JsonObject)

  val launches = CopyOnWriteArrayList<SimulatorLaunch>()
  val commands = CopyOnWriteArrayList<Sent>()
  val processes = CopyOnWriteArrayList<Process>()
  /** An answer to give instead of the default, by command name. */
  val answers = java.util.concurrent.ConcurrentHashMap<String, JsonElement>()
  var bootEvents: Boolean = true

  inner class Process(
    val launch: SimulatorLaunch,
    val onEvent: (SimulatorDevice, JsonObject) -> Unit,
  ) : SimulatorProcess {
    @Volatile var closed = false
    @Volatile var paused = false
    val pauseCount = java.util.concurrent.atomic.AtomicInteger()

    override val alive: Boolean
      get() = !closed

    override fun command(
      device: SimulatorDevice,
      command: JsonObject,
      timeoutMillis: Long,
    ): JsonElement? {
      if (closed) throw SimulatorCommandException("closed")
      check(!paused) { "a command reached a frozen simulator" }
      commands += Sent(launch.sessionId, device, command)
      val cmd = (command["cmd"] as JsonPrimitive).content
      answers[cmd]?.let {
        return it
      }
      return when (cmd) {
        "screenshot" ->
          buildJsonObject {
            put("png", Base64.getEncoder().encodeToString(SimulatorFixtures.PNG))
            put("width", if (device == SimulatorDevice.WATCH) 454 else 540)
            put("height", if (device == SimulatorDevice.WATCH) 454 else 960)
            put("path", "/srv/simulator/state/shot.png")
          }
        "savedState" ->
          buildJsonObject {
            put("activity", "com.example.${device.id}.MainActivity")
            put("savedState", "c3RhdGU=")
          }
        "info" ->
          buildJsonObject {
            put("round", device == SimulatorDevice.WATCH)
            put("package", "com.example.${device.id}")
            put("apk", "/srv/simulator/apps/x.apk")
          }
        else -> buildJsonObject { put("done", cmd) }
      }
    }

    fun emit(device: SimulatorDevice, event: JsonObject) = onEvent(device, event)

    override fun stderrTail(): List<String> = listOf("boom at /srv/simulator/state/log")

    override fun pids(): List<Long> = listOf(4242L)

    override fun pause() {
      paused = true
      pauseCount.incrementAndGet()
    }

    override fun resume() {
      paused = false
    }

    fun ready() =
      launch.mode.devices.forEach { emit(it, buildJsonObject { put("event", "ready") }) }

    override fun close() {
      closed = true
    }
  }

  override fun availability() = availability

  override fun hostRoots(): List<String> = roots

  override fun start(
    launch: SimulatorLaunch,
    onEvent: (SimulatorDevice, JsonObject) -> Unit,
  ): SimulatorProcess {
    launches += launch
    val process = Process(launch, onEvent)
    processes += process
    if (bootEvents) {
      for (device in launch.mode.devices) {
        onEvent(device, buildJsonObject { put("event", "ready") })
      }
    }
    return process
  }
}
