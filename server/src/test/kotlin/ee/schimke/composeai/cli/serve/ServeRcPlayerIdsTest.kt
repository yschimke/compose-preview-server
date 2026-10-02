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
}
