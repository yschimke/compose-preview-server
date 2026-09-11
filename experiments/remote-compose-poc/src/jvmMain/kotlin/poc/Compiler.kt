package poc

import androidx.compose.remote.creation.json.RemoteComposeJsonParser
import java.io.File
import org.json.JSONObject

fun compileRemote(source: String): ByteArray {
  require(JSONObject(source).has("root")) { "root: a document must contain a root" }
  val buffer = RemoteComposeJsonParser.parseToByteBuffer(source)
  return ByteArray(buffer.remaining()).also(buffer::get)
}

/** The expression probe deliberately requires an extension; stock alpha19 must reject it. */
fun compileRemoteWithIntegerExpressions(source: String): ByteArray {
  if (java.lang.Boolean.getBoolean("verifySharedJsonCompiler")) {
    return ee.schimke.composeai.remotecompose.json.RemoteComposeJson.compile(
      JSONObject(source).put("compilerProfile", "compose-preview-integer-expressions-v1").toString()
    )
  }
  val writer =
    androidx.compose.remote.creation.RemoteComposeWriter(
      RemoteComposeJsonParser.DEFAULT_PLATFORM,
      RemoteComposeJsonParser.parseApiLevel(source),
      *RemoteComposeJsonParser.parseHeaderOnly(source).sortedBy { it.tag }.toTypedArray(),
    )
  val parser = RemoteComposeJsonParser(writer)
  androidx.compose.remote.creation.json.IntegerExpressions.install(parser)
  parser.parse(source)
  return writer.encodeToByteArray()
}

fun main(args: Array<String>) {
  val bytes = compileRemote(File(args[0]).readText())
  File(args[1]).apply {
    parentFile.mkdirs()
    writeBytes(bytes)
  }
  println("Compiled ${bytes.size} real Remote Compose bytes")
}
