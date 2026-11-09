package ee.schimke.composeai.cli.serve

import ee.schimke.livery.server.ComponentPublications
import ee.schimke.livery.server.PublicationStore
import io.ktor.server.routing.Route
import java.nio.file.Path

/** Host configuration adapter; Livery owns publication validation, discovery and public routes. */
class ServeComponentPublications(
  root: Path,
  viewerRoot: Path?,
  publicOrigin: String,
  viewerConfig: String = "{}",
) {
  private val publications =
    ComponentPublications(PublicationStore(root), viewerRoot, publicOrigin, viewerConfig)

  internal fun install(route: Route) = publications.install(route)

  companion object {
    fun fromEnvironment(env: Map<String, String> = System.getenv()): ServeComponentPublications? {
      val root = env["SERVE_COMPONENT_PUBLICATIONS_DIR"]?.takeIf { it.isNotBlank() } ?: return null
      val origin =
        requireNotNull(env["SERVE_COMPONENT_PUBLICATIONS_ORIGIN"]?.takeIf { it.isNotBlank() }) {
          "component publication hosting needs its public origin"
        }
      return ServeComponentPublications(
        Path.of(root),
        env["SERVE_COMPONENT_VIEWER_DIR"]?.takeIf { it.isNotBlank() }?.let(Path::of),
        origin,
        env["SERVE_COMPONENT_VIEWER_CONFIG"]?.takeIf { it.isNotBlank() } ?: "{}",
      )
    }
  }
}
