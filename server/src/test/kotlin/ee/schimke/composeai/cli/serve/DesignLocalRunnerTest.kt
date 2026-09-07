package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.AnimationStateV1
import ee.schimke.composeai.uibuilder.protocol.CatalogReferenceV1
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.DesignEnvironmentV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import ee.schimke.composeai.uibuilder.protocol.LayoutDirectionV1
import ee.schimke.composeai.uibuilder.protocol.ThemeV1
import ee.schimke.composeai.uibuilder.protocol.WindowPostureV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a local run says when there is no picture — which is the reason the mode exists.
 *
 * `exception: null` + `image: null` + a valid preview id is the identical observable for a missing
 * sidecar, a render that timed out and a render that threw, and on the wire that is all a caller
 * gets ([#481](https://github.com/yschimke/compose-preview-server/issues/481)). So the behaviour
 * pinned here is that the reason and the lane's own shape both reach stderr, and that nothing lands
 * on disk looking like a render when neither did.
 *
 * The lane is a recording fake for the reason [DesignCommandRunnerTest]'s transport is one: every
 * decision in the runner is a decision about an outcome, and standing up a Kotlin compiler and a
 * Robolectric daemon to produce one would test the compiler instead.
 */
class DesignLocalRunnerTest {

  private val logged = mutableListOf<String>()
  private val written = mutableMapOf<String, ByteArray>()
  private var documentsRead = 0

  private fun options(verb: String, out: String? = null) =
    DesignCommand.Options(
      verb = verb,
      designId = "spotify-wear-widget",
      out = out,
      format = if (verb == DesignCommand.RENDER) ExportFormatV1.PNG else ExportFormatV1.COMPOSE,
      revision = null,
      limit = 50,
      server = DesignCommand.defaultServer(),
      authorize = true,
      timeoutSeconds = 30,
      local = true,
      catalog = "wear.bundle",
    )

  private fun runner(options: DesignCommand.Options, lane: DesignLocalLane) =
    DesignLocalRunner(
      options = options,
      document = {
        documentsRead++
        document()
      },
      lane = lane,
      emit = { logged += it },
      write = { destination, bytes -> written[destination] = bytes },
    )

  @Test
  fun `a rendered frame lands on disk with its size reported`() {
    val png = byteArrayOf(1, 2, 3, 4)
    val code =
      runner(
          options(DesignCommand.RENDER, out = "cover.png"),
          lane(frame = DesignLocalLane.Frame.Rendered(png)),
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_OK, code)
    assertContentEquals(png, written.getValue("cover.png"))
    assertEquals(1, documentsRead)
    assertTrue(logged.any { it.contains("4 bytes of image/png") && it.contains("cover.png") })
  }

  /** The one reading the wire cannot carry, and the one this mode was built to print. */
  @Test
  fun `a frameless render prints the reason and the lane it was produced by`() {
    val code =
      runner(
          options(DesignCommand.RENDER, out = "cover.png"),
          lane(
            frame =
              DesignLocalLane.Frame.NoFrame(
                "the design compiled, but this host's renderer produced no frame for it"
              )
          ),
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertFalse(written.containsKey("cover.png"), "a missing frame must not leave a file behind")
    assertTrue(logged.any { it.contains("produced no frame") }, "$logged")
    // The three facts a diagnosis needs beside that sentence.
    assertTrue(logged.any { it.contains("compile classpath: 117 entries") }, "$logged")
    assertTrue(logged.any { it.contains("daemon opener: none") }, "$logged")
    assertTrue(logged.any { it.contains("backend android") }, "$logged")
  }

  @Test
  fun `a compiler error reaches stderr as the compiler wrote it`() {
    val code =
      runner(
          options(DesignCommand.RENDER),
          lane(
            frame =
              DesignLocalLane.Frame.NoFrame(
                "UiBuilderGeneratedScreen.kt:7:8: Unresolved reference 'graphics'"
              )
          ),
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertTrue(logged.any { it.contains("Unresolved reference 'graphics'") }, "$logged")
  }

  @Test
  fun `a refused design names every reason and writes nothing`() {
    val code =
      runner(
          options(DesignCommand.RENDER, out = "cover.png"),
          lane(
            frame =
              DesignLocalLane.Frame.Refused(
                ServeUiBuilderNativePreview.MIXED_PACKS,
                listOf("this design uses components from 2 packs"),
              )
          ),
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertTrue(written.isEmpty())
    assertTrue(logged.any { it.contains("MIXED_PACKS") && it.contains("2 packs") }, "$logged")
    assertTrue(logged.any { it.contains("nothing was written") }, "$logged")
  }

  @Test
  fun `an export writes the generated Kotlin without compiling it`() {
    val lane = lane(source = DesignLocalLane.Source.Emitted("fun Widget() {}\n", "Widget"))
    val code = runner(options(DesignCommand.EXPORT, out = "Widget.kt"), lane).run()

    assertEquals(DesignCommandRunner.EXIT_OK, code)
    assertEquals("fun Widget() {}\n", written.getValue("Widget.kt").decodeToString())
    assertEquals(0, (lane as RecordingLane).renders, "an export must not open a compiler")
  }

  @Test
  fun `a refused export is a failure, not an almost-Kotlin file`() {
    val code =
      runner(
          options(DesignCommand.EXPORT, out = "Widget.kt"),
          lane(
            source =
              DesignLocalLane.Source.Refused(
                "UNEXPRESSIBLE_DOCUMENT",
                listOf("`asset/image` has no Remote Compose counterpart"),
              )
          ),
        )
        .run()

    assertEquals(DesignCommandRunner.EXIT_FAILURE, code)
    assertTrue(written.isEmpty())
    assertTrue(logged.any { it.contains("no Remote Compose counterpart") }, "$logged")
  }

  private fun assertContentEquals(expected: ByteArray, actual: ByteArray) =
    assertEquals(expected.toList(), actual.toList())

  private fun lane(
    frame: DesignLocalLane.Frame = DesignLocalLane.Frame.NoFrame("unused"),
    source: DesignLocalLane.Source = DesignLocalLane.Source.Emitted("fun Unused() {}\n", "Unused"),
  ): DesignLocalLane = RecordingLane(frame, source)

  private class RecordingLane(
    private val frame: DesignLocalLane.Frame,
    private val source: DesignLocalLane.Source,
  ) : DesignLocalLane {
    var renders = 0

    override fun describe(): List<String> =
      listOf(
        "bundle: wear.bundle (backend android, mode ANDROID)",
        "compile classpath: 117 entries",
        "compiler: bta",
        "daemon opener: none — this render has no still frame to produce",
        "component record: none named (--components)",
      )

    override fun generate(document: DesignDocumentV1): DesignLocalLane.Source = source

    override fun render(document: DesignDocumentV1): DesignLocalLane.Frame {
      renders++
      return frame
    }
  }

  private fun document(): DesignDocumentV1 =
    DesignDocumentV1(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "spotify-wear-widget",
      title = "Spotify",
      revision = 4,
      catalogPin = CatalogReferenceV1("wear-m3", "candidate", "candidate", "candidate"),
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
      roots = emptyList(),
      nodes = emptyMap(),
    )
}
