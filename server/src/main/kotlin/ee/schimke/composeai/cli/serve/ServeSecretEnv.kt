package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Reads a secret the Docker-secret way as well as from the environment: `NAME` itself, or the file
 * `NAME_FILE` names (`/run/secrets/…`).
 *
 * A secret in a variable sits in this process's environment block for its whole life, readable by
 * anything that can read `/proc/<pid>/environ` and inherited by every child that is not given a
 * filtered one. A secret in a file is read once, here, and is never in the environment at all, so a
 * deployment that switches to `NAME_FILE` takes it out of both.
 *
 * Nothing here ever prints a value — only the variable names and the file's path.
 */
internal object ServeSecretEnv {
  const val FILE_SUFFIX: String = "_FILE"

  /**
   * The secret for [name], or null when neither form is set. `NAME_FILE` wins when both are, since
   * setting it is how a deployment moves off the variable; [warn] says so. An unreadable or empty
   * file is reported through [warn] and reads as unset, so a broken mount turns the feature off
   * rather than stopping the server.
   */
  fun read(
    name: String,
    env: Map<String, String> = System.getenv(),
    warn: (String) -> Unit = System.err::println,
  ): String? {
    val direct = env[name]?.trim()?.takeIf { it.isNotEmpty() }
    val path = env[name + FILE_SUFFIX]?.trim()?.takeIf { it.isNotEmpty() } ?: return direct
    if (direct != null) warn("serve: both $name and $name$FILE_SUFFIX are set; using the file")
    val value =
      try {
        File(path).readText().trim()
      } catch (e: Exception) {
        warn(
          "serve: $name$FILE_SUFFIX names $path, which could not be read (${e.javaClass.simpleName})"
        )
        return null
      }
    if (value.isEmpty()) {
      warn("serve: $name$FILE_SUFFIX names $path, which is empty")
      return null
    }
    return value
  }
}
