package poc

import androidx.compose.remote.creation.json.RemoteComposeJsonParser
import java.io.File
import org.json.JSONObject

fun compileRemote(source: String): ByteArray {
  require(JSONObject(source).has("root")) { "root: a document must contain a root" }
  val buffer = RemoteComposeJsonParser.parseToByteBuffer(source)
  return ByteArray(buffer.remaining()).also(buffer::get)
}

fun main(args: Array<String>) {
  val bytes = compileRemote(File(args[0]).readText())
  File(args[1]).apply {
    parentFile.mkdirs()
    writeBytes(bytes)
  }
  println("Compiled ${bytes.size} real Remote Compose bytes")
}
