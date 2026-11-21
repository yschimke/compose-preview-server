package ee.schimke.composeai.cli.serve

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchFacePushServerClientTest {
  @Test
  fun clientRoundTripsReadyzValidateSideloadAndPush() {
    val receivedPaths = mutableListOf<String>()
    val fakeApk = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x42)
    val server =
      HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/readyz") { ex ->
          val body =
            """{"ready":true,"sdkAvailable":true,"signingConfigured":true,"signerCertificateSha256":"${"a".repeat(64)}","version":"0.15.0"}"""
              .toByteArray(StandardCharsets.UTF_8)
          ex.responseHeaders.set("Content-Type", "application/json")
          ex.sendResponseHeaders(200, body.size.toLong())
          ex.responseBody.use { it.write(body) }
        }
        createContext("/v1/validate") { ex ->
          ZipInputStream(ex.requestBody).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
              receivedPaths += entry.name
              zis.closeEntry()
              entry = zis.nextEntry
            }
          }
          val body =
            """{"schemaVersion":1,"wffVersion":4,"passed":true,"checks":[]}"""
              .toByteArray(StandardCharsets.UTF_8)
          ex.responseHeaders.set("Content-Type", "application/json")
          ex.sendResponseHeaders(200, body.size.toLong())
          ex.responseBody.use { it.write(body) }
        }
        createContext("/v1/pack") { ex ->
          val query = ex.requestURI.rawQuery ?: ""
          ex.requestBody.readAllBytes()
          if (query.contains("faceName=fail")) {
            val body =
              """{"diagnostics":["error[wff]: bad element"]}""".toByteArray(StandardCharsets.UTF_8)
            ex.responseHeaders.set("Content-Type", "application/json")
            ex.sendResponseHeaders(422, body.size.toLong())
            ex.responseBody.use { it.write(body) }
          } else {
            if (query.contains("signWithEnv=true")) {
              ex.responseHeaders.set("X-Validation-Token", "push-tok-99")
            }
            ex.responseHeaders.set("Content-Type", "application/vnd.android.package-archive")
            ex.sendResponseHeaders(200, fakeApk.size.toLong())
            ex.responseBody.use { it.write(fakeApk) }
          }
        }
      }
    server.start()
    try {
      val base = "http://127.0.0.1:${server.address.port}"
      val client = WatchFacePushServerClient(base)

      val ready = assertNotNull(client.readyz())
      assertTrue(ready.ready)
      assertTrue(ready.signingConfigured)
      assertEquals("a".repeat(64), ready.signerCertificateSha256)

      val bundle =
        mapOf(
          "AndroidManifest.xml" to "<manifest/>".encodeToByteArray(),
          "res/raw/watchface.xml" to "<WatchFace/>".encodeToByteArray(),
        )
      val report = client.validate(bundle)
      assertTrue(report.contains("\"passed\":true"))
      assertEquals(listOf("AndroidManifest.xml", "res/raw/watchface.xml"), receivedPaths)

      val tmpDir = Files.createTempDirectory("push-client-test").toFile()
      val sideloadApk = File(tmpDir, "sideload.apk")
      client.packSideload(bundle, "com.example.preview", sideloadApk)
      assertContentEquals(fakeApk, sideloadApk.readBytes())

      val pushApk = File(tmpDir, "push.apk")
      val token = client.packPush(bundle, "ee.schimke.watchface.push", "aurora", 5, pushApk)
      assertEquals("push-tok-99", token)
      assertContentEquals(fakeApk, pushApk.readBytes())

      val failed =
        assertFailsWith<DwfPackagingFailed> {
          client.packPush(bundle, "ee.schimke.watchface.push", "fail", 5, File(tmpDir, "fail.apk"))
        }
      assertEquals(listOf("error[wff]: bad element"), failed.diagnostics)

      // Verify WatchFaceValidatorAvailability.production uses remoteUrl without local SDK/libDir
      val availability =
        WatchFaceValidatorAvailability.production(
          home = tmpDir,
          enabled = { true },
          sdk = { null },
          libDir = { null },
          remoteUrl = { base },
        )()
      assertIs<WatchFaceValidatorAvailability.Ready>(availability)
      tmpDir.deleteRecursively()
    } finally {
      server.stop(0)
    }
  }
}
