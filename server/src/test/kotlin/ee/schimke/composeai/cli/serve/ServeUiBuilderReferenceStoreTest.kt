package ee.schimke.composeai.cli.serve

import java.nio.file.Files
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServeUiBuilderReferenceStoreTest {
  private val root = Files.createTempDirectory("reference-store")
  private val store = ServeUiBuilderReferenceStore(root)

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
  }

  private fun pngImage(width: Int = 4, height: Int = 3) =
    StoredReferenceImage(
      name = "mock.png",
      mediaType = "image/png",
      base64 = Base64.getEncoder().encodeToString(ServeImageFixtures.png(width, height)),
    )

  private fun stored(result: ReferenceWriteResult): StoredReference =
    assertIs<ReferenceWriteResult.Stored>(result).reference

  @Test
  fun `a design with nothing attached reads as nothing`() {
    assertNull(store.read("design-1"))
  }

  @Test
  fun `a stored reference comes back on the next open`() {
    val written =
      stored(
        store.replace(
          "design-1",
          ReferenceUploadRequest(
            image = pngImage(),
            settings = StoredReferenceSettings(mode = "difference", opacityPercent = 30),
          ),
        )
      )
    val reopened = requireNotNull(store.read("design-1"))
    assertEquals(written.image?.id, reopened.image?.id)
    assertEquals("difference", reopened.settings.mode)
    assertEquals(30, reopened.settings.opacityPercent)
  }

  @Test
  fun `the host assigns the identity and the size, not the client`() {
    val written =
      stored(
        store.replace(
          "design-1",
          ReferenceUploadRequest(
            image = pngImage(width = 7, height = 5).copy(id = "whatever-i-said", widthPx = 999)
          ),
        )
      )
    assertNotEquals("whatever-i-said", written.image?.id)
    assertEquals(7, written.image?.widthPx)
    assertEquals(5, written.image?.heightPx)
  }

  @Test
  fun `identical bytes get one identity, so a re-import is not a re-decode`() {
    val first = stored(store.replace("design-1", ReferenceUploadRequest(image = pngImage())))
    val second = stored(store.replace("design-2", ReferenceUploadRequest(image = pngImage())))
    assertEquals(first.image?.id, second.image?.id)
  }

  @Test
  fun `bytes that are not the declared picture are refused`() {
    val refusal =
      store.replace(
        "design-1",
        ReferenceUploadRequest(
          image =
            StoredReferenceImage(
              mediaType = "image/png",
              base64 = Base64.getEncoder().encodeToString("not a picture".toByteArray()),
            )
        ),
      )
    assertIs<ReferenceWriteResult.Refused>(refusal)
    assertNull(store.read("design-1"))
  }

  @Test
  fun `an SVG carrying active content is refused`() {
    val refusal =
      store.replace(
        "design-1",
        ReferenceUploadRequest(
          image =
            StoredReferenceImage(
              mediaType = "image/svg+xml",
              base64 =
                Base64.getEncoder()
                  .encodeToString(
                    "<svg viewBox=\"0 0 1 1\"><script>alert(1)</script></svg>".toByteArray()
                  ),
            )
        ),
      )
    assertIs<ReferenceWriteResult.Refused>(refusal)
  }

  @Test
  fun `a plain SVG is kept`() {
    val written =
      stored(
        store.replace(
          "design-1",
          ReferenceUploadRequest(
            image =
              StoredReferenceImage(
                mediaType = "image/svg+xml",
                base64 =
                  Base64.getEncoder()
                    .encodeToString(
                      "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 4 4\"/>"
                        .toByteArray()
                    ),
              )
          ),
        )
      )
    assertEquals("image/svg+xml", written.image?.mediaType)
  }

  @Test
  fun `the settings route re-aims without carrying the picture`() {
    store.replace("design-1", ReferenceUploadRequest(image = pngImage()))
    val reaimed =
      stored(
        requireNotNull(
          store.replaceSettings(
            "design-1",
            ReferenceSettingsRequest(StoredReferenceSettings(scalePercent = 150)),
          )
        )
      )
    assertEquals(150, reaimed.settings.scalePercent)
    // The bytes are still there: re-aiming must never be a way to lose the picture.
    assertTrue(reaimed.image?.base64?.isNotEmpty() == true)
  }

  @Test
  fun `re-aiming a design with nothing attached is a miss rather than an empty record`() {
    assertNull(
      store.replaceSettings("design-1", ReferenceSettingsRequest(StoredReferenceSettings()))
    )
  }

  @Test
  fun `absurd settings are clamped rather than stored`() {
    store.replace("design-1", ReferenceUploadRequest(image = pngImage()))
    val clamped =
      stored(
        requireNotNull(
          store.replaceSettings(
            "design-1",
            ReferenceSettingsRequest(
              StoredReferenceSettings(
                mode = "not-a-mode",
                opacityPercent = 4000,
                scalePercent = -5,
                offsetXDp = Float.NaN,
              )
            ),
          )
        )
      )
    assertEquals("overlay", clamped.settings.mode)
    assertEquals(100, clamped.settings.opacityPercent)
    assertEquals(StoredReferenceSettings.MIN_SCALE_PERCENT, clamped.settings.scalePercent)
    assertEquals(0f, clamped.settings.offsetXDp)
  }

  @Test
  fun `marks and pieces round-trip, and undrawable marks are dropped`() {
    val written =
      stored(
        store.replace(
          "design-1",
          ReferenceUploadRequest(
            image = pngImage(),
            pieces =
              listOf(
                StoredReferencePiece(
                  id = "piece-1",
                  image = pngImage(),
                  left = 0.1f,
                  top = 0.1f,
                  right = 0.5f,
                  bottom = 0.4f,
                  componentId = "m3/button",
                )
              ),
            marks =
              listOf(
                StoredReferenceMark("m1", "arrow", listOf(0f, 0f, 1f, 1f), 0xFFFF5252),
                StoredReferenceMark("m2", "pen", listOf(0f, 0f, 1f), 0xFFFF5252),
                StoredReferenceMark("m3", "not-a-kind", listOf(0f, 0f, 1f, 1f), 0xFFFF5252),
              ),
          ),
        )
      )
    assertEquals(listOf("m1"), written.marks.map { it.id })
    assertEquals("m3/button", written.pieces.single().componentId)
    assertEquals(written.image?.id, written.pieces.single().image.id)
  }

  @Test
  fun `the settings route cannot introduce a piece it carries no bytes for`() {
    store.replace("design-1", ReferenceUploadRequest(image = pngImage()))
    val reaimed =
      stored(
        requireNotNull(
          store.replaceSettings(
            "design-1",
            ReferenceSettingsRequest(
              settings = StoredReferenceSettings(),
              pieces =
                listOf(
                  StoredReferencePiece(
                    id = "never-uploaded",
                    image = pngImage(),
                    left = 0f,
                    top = 0f,
                    right = 1f,
                    bottom = 1f,
                  )
                ),
            ),
          )
        )
      )
    assertTrue(reaimed.pieces.isEmpty())
  }

  @Test
  fun `a markup label is bounded`() {
    val written =
      stored(
        store.replace(
          "design-1",
          ReferenceUploadRequest(
            image = pngImage(),
            marks =
              listOf(
                StoredReferenceMark(
                  "m1",
                  "text",
                  listOf(0f, 0f, 1f, 1f),
                  0xFFFF5252,
                  text = "x".repeat(MAX_MARK_TEXT * 3),
                )
              ),
          ),
        )
      )
    assertEquals(MAX_MARK_TEXT, written.marks.single().text?.length)
  }

  @Test
  fun `removing everything removes the file rather than storing an empty record`() {
    store.replace("design-1", ReferenceUploadRequest(image = pngImage()))
    store.replace("design-1", ReferenceUploadRequest())
    assertNull(store.read("design-1"))
  }

  @Test
  fun `a design id is never a path segment`() {
    val hostile = "../../escape"
    store.replace(hostile, ReferenceUploadRequest(image = pngImage()))
    assertEquals(hostile, store.read(hostile)?.designId)
    // Everything this store wrote is inside its own root, whatever the id looked like.
    val written = Files.list(root).use { it.toList() }
    assertEquals(1, written.size)
    assertTrue(written.single().fileName.toString().endsWith(".json"))
  }

  @Test
  fun `an oversized picture is refused`() {
    val tiny = ServeUiBuilderReferenceStore(Files.createTempDirectory("small"), maximumBytes = 64)
    val refusal = tiny.replace("design-1", ReferenceUploadRequest(image = pngImage(64, 64)))
    assertIs<ReferenceWriteResult.Refused>(refusal)
  }

  @Test
  fun `unreadable stored bytes read as nothing rather than failing the open`() {
    store.replace("design-1", ReferenceUploadRequest(image = pngImage()))
    Files.list(root).use { entries -> entries.forEach { Files.writeString(it, "{ not json") } }
    assertNull(store.read("design-1"))
  }
}
