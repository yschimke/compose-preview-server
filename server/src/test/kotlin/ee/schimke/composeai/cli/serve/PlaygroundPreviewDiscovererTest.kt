package ee.schimke.composeai.cli.serve

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/** A stand-in preview annotation so the scan can run against real compiled bytecode in the test. */
annotation class FakePreviewMarker

/** Compiled into the test output dir; the scan below finds `Shown` and ignores `Hidden`. */
@Suppress("unused")
class DiscoveryFixture {
  @FakePreviewMarker fun Shown() {}

  fun Hidden() {}
}

/** Stands in for a `@Preview` from a toolkit this host does not render (Wear tiles, Glance, …). */
annotation class ForeignPreview

/** Annotated with a preview-shaped annotation that is not in the accepted set. */
@Suppress("unused")
class ForeignPreviewFixture {
  @ForeignPreview fun Elsewhere() {}
}

class PlaygroundPreviewDiscovererTest {

  /** The test's own compiled-classes root — where [DiscoveryFixture] landed. */
  private fun testClassesDir(): File =
    File(DiscoveryFixture::class.java.protectionDomain.codeSource.location.toURI())

  @Test
  fun `scans a classes dir for @Preview-annotated methods, ignoring the rest`() {
    val discoverer =
      PlaygroundPreviewDiscoverer(
        previewAnnotationFqns = setOf("ee.schimke.composeai.cli.serve.FakePreviewMarker")
      )

    val ids = discoverer.discover(testClassesDir().absolutePath.toPath(), classpath = emptyList())

    assertTrue(
      ids.any { it == "ee.schimke.composeai.cli.serve.DiscoveryFixture.Shown" },
      "the annotated method is discovered with a <class>.<method> id: $ids",
    )
    assertFalse(ids.any { it.contains("Hidden") }, "unannotated methods are not previews")
  }

  @Test
  fun `no matching annotation yields no previews`() {
    val discoverer =
      PlaygroundPreviewDiscoverer(previewAnnotationFqns = setOf("com.example.NotHere"))
    assertTrue(discoverer.discover(testClassesDir().absolutePath.toPath(), emptyList()).isEmpty())
  }

  @Test
  fun `the desktop Preview FQN is accepted by default`() {
    assertTrue(
      "androidx.compose.desktop.ui.tooling.preview.Preview" in
        PlaygroundPreviewDiscoverer.DEFAULT_PREVIEW_ANNOTATION_FQNS,
      "a compose-cmp snippet imports the desktop @Preview; it compiles, so it must also render",
    )
  }

  @Test
  fun `preview-shaped annotations outside the accepted set are reported, not the rest`() {
    val discoverer =
      PlaygroundPreviewDiscoverer(
        previewAnnotationFqns = setOf("ee.schimke.composeai.cli.serve.FakePreviewMarker")
      )

    val found = discoverer.unrecognisedPreviewAnnotations(testClassesDir().absolutePath.toPath())

    assertTrue(
      found.contains("ee.schimke.composeai.cli.serve.ForeignPreview"),
      "the unaccepted preview-shaped annotation is named: $found",
    )
    assertFalse(
      found.contains("ee.schimke.composeai.cli.serve.FakePreviewMarker"),
      "an accepted annotation is never reported as unrecognised: $found",
    )
  }

  @Test
  fun `looksLikePreview matches preview-shaped names only`() {
    assertTrue(PlaygroundPreviewDiscoverer.looksLikePreview("androidx.glance.preview.Preview"))
    assertTrue(PlaygroundPreviewDiscoverer.looksLikePreview("com.example.PreviewLightDark"))
    assertFalse(PlaygroundPreviewDiscoverer.looksLikePreview("kotlin.jvm.JvmStatic"))
    assertFalse(
      PlaygroundPreviewDiscoverer.looksLikePreview("com.preview.Composable"),
      "the package is not the annotation's name",
    )
  }

  @Test
  fun `id is class dot method`() {
    assertEquals(
      "com.example.Foo.Bar",
      PlaygroundPreviewDiscoverer.previewId("com.example.Foo", "Bar"),
    )
  }
}
