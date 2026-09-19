package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.previewdata.PreviewManifest
import ee.schimke.composeai.previewdata.PreviewModule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `--module`'s server-side half: whatever the build host reports, the run serves the module the
 * flag names and nothing else.
 *
 * The primary scoping happens in the build host (the spawn passes `--module`, and `resolveModules`
 * resolves one module before the render task is configured). This selection is what keeps the
 * flag's promise against a host that predates that argument — and it is what turns "N modules
 * discovered; narrow with --module <path>", naming the flag the caller already passed, into either
 * the one module or a message that says the module was not found.
 */
class ServeDiscoverySelectionTest {

  private fun module(path: String) = PreviewModule(path, File("/tmp/$path"))

  private fun manifest(module: String) = PreviewManifest(module, "debug", emptyList())

  private fun discovery(vararg paths: String) =
    ServeDiscovery(
      buildOk = true,
      manifests = paths.map { module(it) to manifest(it) },
    )

  @Test
  fun `a leading colon matches the bare gradle path`() {
    // `PreviewModule.gradlePath` carries no leading colon (`project.path.removePrefix(":")`), so
    // both spellings a caller may type have to resolve to the same module.
    val selected =
      assertNotNull(selectRequestedModule(discovery("remote-catalog"), ":remote-catalog"))
    assertEquals(listOf("remote-catalog"), selected.manifests.map { it.first.gradlePath })
  }

  @Test
  fun `a bare path matches too`() {
    val selected =
      assertNotNull(selectRequestedModule(discovery(":remote-catalog"), "remote-catalog"))
    assertEquals(listOf(":remote-catalog"), selected.manifests.map { it.first.gradlePath })
  }

  @Test
  fun `a multi-module discovery narrows to the requested one`() {
    // The failure this replaces: a build host that ignored `--module` reports every module, the
    // server counts them, and the run aborts asking for the flag the caller already passed.
    val selected =
      assertNotNull(
        selectRequestedModule(
          discovery("catalog-desktop", "remote-catalog", "wear-m3-catalog"),
          ":remote-catalog",
        )
      )
    assertEquals(listOf("remote-catalog"), selected.manifests.map { it.first.gradlePath })
    assertEquals(true, selected.buildOk, "the build result is carried through, not re-decided")
  }

  @Test
  fun `a nested gradle path matches colon-separated`() {
    val selected =
      assertNotNull(
        selectRequestedModule(discovery("auth:composables", "auth:ui"), "auth:composables")
      )
    assertEquals(listOf("auth:composables"), selected.manifests.map { it.first.gradlePath })
  }

  @Test
  fun `a module the discovery does not contain selects nothing`() {
    // Null, not the whole list: serving a different module than the one asked for is the silent
    // substitution this flag exists to prevent. The caller turns null into a named error.
    assertNull(selectRequestedModule(discovery("catalog-desktop"), ":remote-catalog"))
    assertNull(selectRequestedModule(discovery(), ":remote-catalog"))
  }
}
