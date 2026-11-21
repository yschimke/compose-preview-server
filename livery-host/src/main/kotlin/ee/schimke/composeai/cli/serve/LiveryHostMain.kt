package ee.schimke.composeai.cli.serve

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Standalone HTTP server for Livery public component publications (`/p/<slug>/r/<rev>/…`),
 * configured from the same `SERVE_COMPONENT_PUBLICATIONS_*` environment variables as `:server` plus
 * `HOST` (default `0.0.0.0`) and `PORT` (default `8080`).
 */
object LiveryHostServer {
  fun create(
    publications: ServeComponentPublications,
    host: String = "0.0.0.0",
    port: Int = 8080,
  ): EmbeddedServer<*, *> =
    embeddedServer(CIO, host = host, port = port) {
      install(AutoHeadResponse)
      routing {
        get("/healthz") { call.respondText("ok\n", ContentType.Text.Plain, HttpStatusCode.OK) }
        get("/readyz") { call.respondText("ok\n", ContentType.Text.Plain, HttpStatusCode.OK) }
        publications.install(this)
      }
    }
}

fun main() {
  val env = System.getenv()
  val publications =
    requireNotNull(ServeComponentPublications.fromEnvironment(env)) {
      "SERVE_COMPONENT_PUBLICATIONS_DIR and SERVE_COMPONENT_PUBLICATIONS_ORIGIN must be set"
    }
  val host = env["HOST"]?.takeIf { it.isNotBlank() } ?: "0.0.0.0"
  val port = env["PORT"]?.toIntOrNull() ?: 8080
  LiveryHostServer.create(publications, host = host, port = port).start(wait = true)
}
