package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.daemon.client.ChildEnvironment

/**
 * The environment a Gradle build this server starts runs with — a revision worktree's `./gradlew`
 * ([GradleRevisionBuilder]) and the `compose-preview build-host` that drives a local build
 * ([ProcessBuildHost]).
 *
 * Both run code the server did not write: a revision's build scripts and their whole plugin and
 * dependency supply chain. By default a child receives this process's entire environment, and on a
 * deployment that is the operator's `SERVE_TOKEN`, OAuth client secrets, GitHub tokens and the
 * guidelines OpenRouter key. So builds start from the same allowlist render daemons already do
 * ([ChildEnvironment.Default], compose-preview-daemon), plus what a Gradle build itself reads:
 * - `GRADLE_OPTS` / `JAVA_OPTS`, which the `gradlew` script passes to the wrapper's JVM, and
 *   `COMPOSE_PREVIEW_OPTS`, the CLI launcher's own;
 * - `COMPOSE_PREVIEW_OFFLINE`, which the build host honours;
 * - `ANDROID_*` (the SDK and NDK locations an Android build resolves) and `ORG_GRADLE_PROJECT_*`,
 *   Gradle's documented way to hand a property to a build — set on purpose, for the build;
 * - proxy and trust-store settings, without which dependency resolution fails behind a proxy.
 *
 * What is NOT kept is every other `SERVE_*` and `COMPOSE_PREVIEW_*` variable — notably
 * `COMPOSE_PREVIEW_OPENROUTER_KEY` — and any credential a build has no business seeing.
 */
internal object BuildChildEnvironment {
  val POLICY: ChildEnvironment.Allowlist =
    ChildEnvironment.Allowlist(
      names =
        ChildEnvironment.DEFAULT_NAMES +
          setOf(
            "GRADLE_OPTS",
            "JAVA_OPTS",
            "COMPOSE_PREVIEW_OPTS",
            "COMPOSE_PREVIEW_OFFLINE",
            "HTTP_PROXY",
            "HTTPS_PROXY",
            "NO_PROXY",
            "ALL_PROXY",
            "SSL_CERT_FILE",
            "SSL_CERT_DIR",
          ),
      prefixes = ChildEnvironment.DEFAULT_PREFIXES + setOf("ANDROID_", "ORG_GRADLE_PROJECT_"),
    )

  /** Replace [builder]'s inherited environment with [POLICY]'s view of it. */
  fun applyTo(builder: ProcessBuilder): ProcessBuilder {
    apply(builder.environment())
    return builder
  }

  /** Narrow [environment] in place; pure enough to test on a plain map. */
  fun apply(environment: MutableMap<String, String>) {
    val resolved = POLICY.resolve(HashMap(environment))
    environment.clear()
    environment.putAll(resolved)
  }
}
