package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the native-render payload says about the live lane.
 *
 * The three ways there is no live session are one answer to the editor — "still only" — and that is
 * the thing worth pinning: a host with no redemption, a compile that minted no token, and a
 * redemption that found no daemon for the design's mode must not be distinguishable, because the
 * editor does the same thing with all three and a sentence about any of them would be noise over a
 * pane that already has a picture.
 */
class UiBuilderNativePreviewLiveTest {
  private val compiled =
    PlaygroundRunResponse(previewId = "generated", previewToken = "tok", image = "png")

  @Test
  fun `a host with no redemption is still-only`() {
    assertNull(nativePreviewLiveOf(compiled, liveNativeSession = null))
  }

  @Test
  fun `a compile that minted no token is still-only`() {
    var asked = false
    val redeem: (String, String) -> UiBuilderNativeLiveSession? = { _, _ ->
      asked = true
      UiBuilderNativeLiveSession("s", "p")
    }
    assertNull(nativePreviewLiveOf(PlaygroundRunResponse(previewId = "generated"), redeem))
    assertNull(nativePreviewLiveOf(PlaygroundRunResponse(previewToken = "tok"), redeem))
    // Not merely null: a redemption is a daemon boot and a live seat, so a response with nothing to
    // redeem must not reach it at all.
    assertEquals(false, asked)
  }

  @Test
  fun `a redemption with no backend for this mode is still-only`() {
    assertNull(nativePreviewLiveOf(compiled) { _, _ -> null })
  }

  @Test
  fun `a redeemed session is reported as the session and the preview inside it`() {
    // The editor builds `/{sessionId}/ws/{previewId}` from exactly these two, so the preview the
    // redemption settled on is the one reported — never the one that was asked for.
    val live =
      nativePreviewLiveOf(compiled) { token, preview ->
        assertEquals("tok", token)
        assertEquals("generated", preview)
        UiBuilderNativeLiveSession(sessionId = "tok", previewId = "generated-0")
      }
    assertEquals(NativePreviewLiveV1(sessionId = "tok", previewId = "generated-0"), live)
  }

  @Test
  fun `a blank token or preview id is not redeemed`() {
    val redeem: (String, String) -> UiBuilderNativeLiveSession? = { _, _ ->
      UiBuilderNativeLiveSession("s", "p")
    }
    assertNull(nativePreviewLiveOf(compiled.copy(previewToken = ""), redeem))
    assertNull(nativePreviewLiveOf(compiled.copy(previewId = " "), redeem))
  }
}
