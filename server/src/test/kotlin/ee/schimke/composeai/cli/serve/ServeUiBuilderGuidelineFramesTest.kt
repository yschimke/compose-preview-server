package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelineFrame
import ee.schimke.composeai.uibuilder.guidelines.DesignGuidelinePicture
import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class ServeUiBuilderGuidelineFramesTest {
  private val directory: Path = Files.createTempDirectory("guideline-frames")
  private val closing = mutableListOf<AutoCloseable>()

  @AfterTest
  fun cleanUp() {
    closing.forEach { it.close() }
    directory.toFile().deleteRecursively()
  }

  @Test
  fun `edits inside the quiet period warm the design once, at its latest revision`() {
    val planned = AtomicInteger()
    val drawn = CopyOnWriteArrayList<Pair<Long, Int>>()
    val frames = frames(quietPeriodMillis = 150)
    frames.render = { document, _ ->
      drawn += document.revision to document.environment.widthDp
      png(document.environment.widthDp, document.environment.heightDp)
    }
    frames.planner = { _, _ ->
      planned.incrementAndGet()
      document(revision = 7) to listOf(PHONE, TABLET)
    }

    // Nobody capable has asked about this design yet: an edit warms nothing.
    frames.edited("design")
    Thread.sleep(400)
    assertEquals(0, planned.get())

    frames.rememberWarmable("design", ACTOR)
    repeat(3) {
      frames.edited("design")
      Thread.sleep(40)
    }
    awaitUntil { drawn.size >= 2 }
    Thread.sleep(300)
    assertEquals(1, planned.get(), "three edits inside the quiet period plan one warm")
    assertEquals(listOf(7L to 412, 7L to 1280), drawn.toList())

    // Warmed frames are what the reader then gets, without drawing again.
    val reader = runBlocking {
      frames.pictures("design", document(revision = 7), listOf(PHONE, TABLET), 1_000)
    }
    assertEquals(listOf("phone", "tablet"), reader.pictures.map { it.first.kind })
    assertEquals(2, drawn.size)
  }

  @Test
  fun `a seed answers its frame, and phone and tablet are kept apart by size`() {
    val drawn = CopyOnWriteArrayList<Int>()
    val frames = frames()
    frames.render = { document, _ ->
      drawn += document.environment.widthDp
      png(document.environment.widthDp, document.environment.heightDp)
    }
    val thumbnail = png(216, 124)
    val result = runBlocking {
      frames.pictures(
        "widget",
        document(revision = 3),
        listOf(SAMSUNG, PIXEL_WATCH),
        5_000,
        seeds = mapOf(DesignGuidelinePicture.WIDGET_PIXEL_WATCH to thumbnail),
      )
    }
    // Only the Samsung container is drawn; the Pixel Watch frame is the widget's thumbnail.
    assertEquals(listOf(230), drawn.toList())
    assertContentEquals(thumbnail, result.pictures.single { it.first == PIXEL_WATCH }.second)

    val phone = frames.keyOf("screen", document(revision = 1), PHONE)
    val tablet = frames.keyOf("screen", document(revision = 1), TABLET)
    frames.store(phone, png(412, 915))
    assertNull(frames.cached(tablet), "a tablet frame is never answered by the phone's picture")
  }

  @Test
  fun `deleting a design forgets its frames, through the thumbnails that own them`() {
    val thumbnails = ServeUiBuilderThumbnails(directory.resolve("thumbs"), "generation")
    closing += thumbnails
    val frames = assertNotNull(thumbnails.guidelineFrames)
    val key = frames.keyOf("design", document(revision = 2), PHONE)
    frames.store(key, png(412, 915))
    assertNotNull(frames.cached(key))

    thumbnails.evict("design")

    assertNull(frames.cached(key))
    val stored =
      directory.resolve("thumbs").resolve(ServeUiBuilderThumbnails.GUIDELINE_FRAMES_DIRECTORY)
    assertFalse(Files.list(stored).use { it.findAny().isPresent }, "nothing left on disk")
  }

  @Test
  fun `a new revision replaces the old one's frames on disk`() {
    val frames = frames()
    val old = frames.keyOf("design", document(revision = 1), PHONE)
    frames.store(old, png(412, 915))
    frames.store(frames.keyOf("design", document(revision = 2), PHONE), png(412, 915))
    val reopened = frames()
    assertNull(reopened.cached(old))
  }

  @Test
  fun `a frame drawing when its design is deleted is not written back`() {
    val frames = frames()
    val drawing = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    frames.render = { document, _ ->
      drawing.countDown()
      release.await()
      png(document.environment.widthDp, document.environment.heightDp)
    }
    val key = frames.keyOf("design", document(revision = 3), PHONE)
    val inFlight = frames.submit(key, document(revision = 3), PHONE, background = false)
    drawing.await()
    // A second frame of the same design is still queued behind it.
    val queued =
      frames.submit(
        frames.keyOf("design", document(revision = 3), TABLET),
        document(revision = 3),
        TABLET,
        background = true,
      )

    frames.evict("design")
    release.countDown()

    assertNull(inFlight.get(5, java.util.concurrent.TimeUnit.SECONDS))
    assertNull(queued.get(5, java.util.concurrent.TimeUnit.SECONDS))
    assertNull(frames.cached(key), "the deleted design's picture is not written back")
  }

  @Test
  fun `a new renderer generation removes the previous one's frames`() {
    val old =
      ServeUiBuilderGuidelineFrames(directory.resolve("frames"), "generation-1").also {
        closing += it
      }
    old.store(old.keyOf("design", document(revision = 4), PHONE), png(412, 915))
    old.close()
    val next =
      ServeUiBuilderGuidelineFrames(directory.resolve("frames"), "generation-2").also {
        closing += it
      }
    next.store(next.keyOf("design", document(revision = 4), TABLET), png(1280, 800))
    val files =
      Files.walk(directory.resolve("frames")).use { paths ->
        paths.filter { it.toString().endsWith(".png") }.toList()
      }
    assertEquals(1, files.size, "only generation-2's frame stays: $files")
  }

  @Test
  fun `frames that differ only in theme or font scale are kept apart`() {
    val frames = frames()
    val dark =
      DesignGuidelineFrame(
        DesignGuidelinePicture.PHONE,
        412,
        915,
        PHONE.environment + ("theme" to JsonPrimitive("dark")),
      )
    val light = frames.keyOf("design", document(revision = 5), PHONE)
    frames.store(light, png(412, 915))
    assertNull(frames.cached(frames.keyOf("design", document(revision = 5), dark)))
  }

  @Test
  fun `only a picture in its frame's aspect counts as that frame`() {
    assertTrue(ServeUiBuilderGuidelineFrames.matchesFrame(png(824, 1830), PHONE))
    assertTrue(ServeUiBuilderGuidelineFrames.matchesFrame(png(2560, 1600), TABLET))
    // The desktop sandbox's 400×800dp default, which phone and tablet both came back as.
    assertFalse(ServeUiBuilderGuidelineFrames.matchesFrame(png(800, 1600), PHONE))
    assertFalse(ServeUiBuilderGuidelineFrames.matchesFrame(png(800, 1600), TABLET))
    assertFalse(ServeUiBuilderGuidelineFrames.matchesFrame(byteArrayOf(1, 2, 3), PHONE))
  }

  private fun frames(quietPeriodMillis: Long = 60_000) =
    ServeUiBuilderGuidelineFrames(
        directory.resolve("frames"),
        "generation",
        quietPeriodMillis = quietPeriodMillis,
      )
      .also { closing += it }

  private fun awaitUntil(condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + 10_000
    while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    assertTrue(condition(), "timed out")
  }

  private fun png(width: Int, height: Int): ByteArray =
    ByteArrayOutputStream()
      .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
      .toByteArray()

  private fun document(revision: Long) =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "design",
      title = "Design",
      revision = revision,
      catalogPin = CatalogReferenceV1("m3-catalog", "candidate", "candidate", "candidate"),
      environment =
        DesignEnvironmentV1(
          widthDp = 400,
          heightDp = 800,
          density = 1.0,
          theme = ThemeV1.LIGHT,
          locale = "en-US",
          fontScale = 1.0,
          layoutDirection = LayoutDirectionV1.LTR,
          windowPosture = WindowPostureV1.FLAT,
          animations = AnimationStateV1.SETTLED,
          networkAccess = false,
        ),
      roots = listOf("root"),
      nodes = mapOf("root" to DesignNodeV1(id = "root", componentId = "m3/scaffold")),
    )

  private companion object {
    val ACTOR = AuthenticatedUiBuilderActor("agent:test")
    val PHONE =
      DesignGuidelineFrame(
        DesignGuidelinePicture.PHONE,
        412,
        915,
        mapOf("widthDp" to JsonPrimitive(412), "heightDp" to JsonPrimitive(915)),
      )
    val TABLET =
      DesignGuidelineFrame(
        DesignGuidelinePicture.TABLET,
        1280,
        800,
        mapOf("widthDp" to JsonPrimitive(1280), "heightDp" to JsonPrimitive(800)),
      )
    val SAMSUNG =
      DesignGuidelineFrame(
        DesignGuidelinePicture.WIDGET_SAMSUNG,
        230,
        168,
        mapOf(WearWidgetHostShape.ENVIRONMENT_KEY to JsonPrimitive(WearWidgetHostShape.Round.id)),
      )
    val PIXEL_WATCH =
      DesignGuidelineFrame(
        DesignGuidelinePicture.WIDGET_PIXEL_WATCH,
        216,
        124,
        mapOf(
          WearWidgetHostShape.ENVIRONMENT_KEY to JsonPrimitive(WearWidgetHostShape.Squircle.id)
        ),
      )
  }
}
