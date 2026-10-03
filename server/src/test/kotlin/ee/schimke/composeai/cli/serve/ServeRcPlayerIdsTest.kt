package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.protocol.RemoteComposePlayerKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeRcPlayerIdsTest {
  @Test
  fun `a request keeps canonical ids and reads legacy spellings as the player they named`() {
    for (player in ServeRcPlayerIds.UNIVERSE) {
      assertEquals(player.id, ServeRcPlayerIds.normalizeRequest(player.id))
    }
    assertEquals("androidx-view", ServeRcPlayerIds.normalizeRequest("java"))
    assertEquals("androidx-view", ServeRcPlayerIds.normalizeRequest("VIEW"))
    assertEquals("androidx-embedded", ServeRcPlayerIds.normalizeRequest("embedded"))
    assertEquals("camaelon-js", ServeRcPlayerIds.normalizeRequest("js"))
    assertEquals("cmp-jvm", ServeRcPlayerIds.normalizeRequest("rcplayer-jvm"))
    assertEquals("cmp-wasm", ServeRcPlayerIds.normalizeRequest("rcplayer-wasm"))
    // A registered daemon player id passes through untouched.
    assertEquals("my-player", ServeRcPlayerIds.normalizeRequest(" My-Player "))
  }

  @Test
  fun `a request never reinterprets cmp-android, which is the CMP player on Android`() {
    assertEquals("cmp-android", ServeRcPlayerIds.normalizeRequest("cmp-android"))
    assertNull(ServeRcPlayerIds.playerKindOf("cmp-android"))
  }

  @Test
  fun `a capture record maps an older daemon's spellings back to the AndroidX players`() {
    assertEquals("androidx-embedded", ServeRcPlayerIds.fromCaptureRecord("cmp-android"))
    assertEquals("androidx-view", ServeRcPlayerIds.fromCaptureRecord("java"))
    assertEquals("androidx-embedded", ServeRcPlayerIds.fromCaptureRecord("androidx-embedded"))
    assertEquals("androidx-view", ServeRcPlayerIds.fromCaptureRecord("androidx-view"))
    assertNull(ServeRcPlayerIds.fromCaptureRecord(null))
    assertNull(ServeRcPlayerIds.fromCaptureRecord(""))
    assertNull(ServeRcPlayerIds.fromCaptureRecord("something-else"))
  }

  @Test
  fun `every compose-ai-tools backend has a canonical id, keyed on what it draws`() {
    val ids = RcPlayerBackend.UNIVERSE.map(ServeRcPlayerIds::of)
    assertEquals(ids.distinct(), ids, "no two backends share an id")
    for (backend in RcPlayerBackend.UNIVERSE) {
      val id = ServeRcPlayerIds.of(backend)
      assertTrue(ServeRcPlayerIds.UNIVERSE.any { it.id == id }, "$backend → $id is offered")
      assertEquals(backend.playerKind, ServeRcPlayerIds.playerKindOf(id), "$backend → $id")
    }
    // The pinned release spells the EMBEDDED backend `cmp-android`; this server never does.
    val embedded =
      RcPlayerBackend.UNIVERSE.single { it.playerKind == RemoteComposePlayerKind.EMBEDDED }
    assertEquals("androidx-embedded", ServeRcPlayerIds.of(embedded))
  }

  @Test
  fun `rcPlayer reaches the daemon as the built-in it names, or cmp-android as a player id`() {
    fun rc(value: String) =
      (ServeRcPlayerIds.parseOverrides(mapOf("rcPlayer" to value)) as OverrideParse.Ok)
        .overrides
        .remoteCompose!!

    for (v in listOf("androidx-view", "java", "view")) {
      assertEquals(RemoteComposePlayerKind.VIEW, rc(v).player, v)
      assertNull(rc(v).playerId, v)
    }
    for (v in listOf("androidx-embedded", "embedded")) {
      assertEquals(RemoteComposePlayerKind.EMBEDDED, rc(v).player, v)
      assertNull(rc(v).playerId, v)
    }
    assertNull(rc("cmp-android").player, "cmp-android is not the embedded built-in any more")
    assertEquals("cmp-android", rc("cmp-android").playerId)
    assertEquals("some-registered-player", rc("some-registered-player").playerId)
  }

  @Test
  fun `a browser or subprocess lane is still refused as a daemon render`() {
    for (v in listOf("camaelon-js", "js", "cmp-wasm", "cmp-jvm", "rcplayer-jvm")) {
      assertIs<OverrideParse.Invalid>(ServeRcPlayerIds.parseOverrides(mapOf("rcPlayer" to v)), v)
    }
  }

  @Test
  fun `the desktop subprocess lane is recognised by either spelling`() {
    assertTrue(ServeRcPlayerIds.isCmpJvm("cmp-jvm"))
    assertTrue(ServeRcPlayerIds.isCmpJvm("rcplayer-jvm"))
    assertFalse(ServeRcPlayerIds.isCmpJvm("cmp-android"))
    assertFalse(ServeRcPlayerIds.isCmpJvm(null))
  }

  // ---- cmp-android capability: read from the bundle manifest's classpath ----------------------

  private fun bundleWith(vararg maven: Pair<String, String>): java.io.File {
    val classpath =
      (listOf("""{"kind":"module","path":"classes/app.jar"}""") +
          maven.map { (group, artifact) ->
            """{"kind":"maven","group":"$group","artifact":"$artifact","version":"0.1.0","type":"aar","sha256":"00"}"""
          })
        .joinToString(",")
    val manifest =
      """{"schemaVersion":8,"backend":"android","previewIds":["a"],"coverPreviewId":"a",""" +
        """"classpath":[$classpath],"modulePath":":remote-catalog","producedBy":"test"}"""
    val zip =
      java.io
        .ByteArrayOutputStream()
        .also { baos ->
          java.util.zip.ZipOutputStream(baos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("bundle.json"))
            z.write(manifest.toByteArray())
            z.closeEntry()
          }
        }
        .toByteArray()
    val cover =
      java.io
        .ByteArrayOutputStream()
        .also {
          javax.imageio.ImageIO.write(
            java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB),
            "png",
            it,
          )
        }
        .toByteArray()
    return kotlin.io.path.createTempFile("bundle", ".png").toFile().apply {
      deleteOnExit()
      writeBytes(cover + zip)
    }
  }

  @Test
  fun `a bundle whose classpath carries rc-player-compose can run cmp-android`() {
    assertTrue(
      ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(
        bundleWith(
          "androidx.compose.remote" to "remote-player-view",
          "ee.schimke.composeai" to "rc-player-compose",
        )
      )
    )
    // A KMP library resolves to its Android variant on an Android bundle's classpath.
    assertTrue(
      ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(
        bundleWith("ee.schimke.composeai" to "rc-player-compose-android")
      )
    )
  }

  @Test
  fun `a bundle without rc-player-compose, or with no readable manifest, cannot`() {
    assertFalse(
      ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(
        bundleWith(
          "androidx.compose.remote" to "remote-player-view",
          "ee.schimke.composeai" to "data-remotecompose-connector",
          // Same artifact name under another group is not the player.
          "com.example" to "rc-player-compose",
        )
      )
    )
    val notABundle =
      kotlin.io.path.createTempFile("bundle", ".png").toFile().apply {
        deleteOnExit()
        writeBytes(byteArrayOf(1, 2, 3))
      }
    assertFalse(ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(notABundle))
    assertFalse(ServeRcPlayerIds.bundleCarriesCmpAndroidPlayer(java.io.File("/no/such/bundle.png")))
  }

  // ---- the configured default player ---------------------------------------------------------

  private val allDaemonLanes =
    listOf("camaelon-js", "androidx-view", "androidx-embedded", "cmp-android", "cmp-jvm")

  @Test
  fun `with no preference the default is unchanged - embedded, then view, then the JS canvas`() {
    assertEquals("androidx-embedded", ServeRcPlayerIds.defaultPlayer(allDaemonLanes))
    assertEquals(
      "androidx-view",
      ServeRcPlayerIds.defaultPlayer(listOf("camaelon-js", "androidx-view", "cmp-jvm")),
    )
    assertEquals("camaelon-js", ServeRcPlayerIds.defaultPlayer(listOf("camaelon-js", "cmp-jvm")))
    assertEquals("cmp-jvm", ServeRcPlayerIds.defaultPlayer(listOf("cmp-jvm")))
    assertEquals("", ServeRcPlayerIds.defaultPlayer(emptyList()))
  }

  @Test
  fun `a preferred cmp-android opens the viewer on it where the preview enables it`() {
    assertEquals("cmp-android", ServeRcPlayerIds.defaultPlayer(allDaemonLanes, "cmp-android"))
  }

  @Test
  fun `a preferred player the preview does not enable falls back through the built-in order`() {
    val noCmpAndroid = allDaemonLanes - "cmp-android"
    assertEquals("androidx-embedded", ServeRcPlayerIds.defaultPlayer(noCmpAndroid, "cmp-android"))
    // No daemon at all: still the JS canvas, never a disabled option.
    assertEquals(
      "camaelon-js",
      ServeRcPlayerIds.defaultPlayer(listOf("camaelon-js"), "cmp-android"),
    )
  }

  @Test
  fun `the configured preference is read in canonical ids`() {
    assertEquals("cmp-android", ServeRcPlayerIds.parsePreferredPlayer(" CMP-Android "))
    assertEquals("androidx-view", ServeRcPlayerIds.parsePreferredPlayer("java"))
    assertEquals("camaelon-js", ServeRcPlayerIds.parsePreferredPlayer("js"))
    assertNull(ServeRcPlayerIds.parsePreferredPlayer(null))
    assertNull(ServeRcPlayerIds.parsePreferredPlayer("  "))
    val rejected = mutableListOf<String>()
    assertNull(ServeRcPlayerIds.parsePreferredPlayer("my-player", rejected::add))
    assertEquals(listOf("my-player"), rejected)
  }
}
