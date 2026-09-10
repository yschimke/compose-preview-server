package ee.schimke.composeai.cli.serve

import java.io.File

/**
 * Reading one file out of a project, whichever of the two places that project lives.
 *
 * The designs a project publishes and the components it publishes sit side by side under
 * `ui-builder/`, are configured by the same coordinates, and reach a host the same two ways — a
 * checkout on this machine during the design phase, or a served catalog's delivery branch once
 * things have settled. Only the directory and the schema differ, so the fetching, the containment
 * check and the size cap live here once rather than being copied per library and drifting apart.
 *
 * Best-effort in the same sense the rest of the catalog machinery means it: null is the answer for
 * a file that is missing, unreachable, outside the project, or larger than the caller will read.
 */
internal object ServeUiBuilderProjectFiles {

  fun read(
    system: String,
    source: ServeUiBuilderDesignLibrary.Source,
    path: String,
    maxBytes: Long,
    fetch: (url: String, maxBytes: Long) -> ByteArray?,
    onLog: (String) -> Unit,
  ): ByteArray? =
    when (source) {
      is ServeUiBuilderDesignLibrary.Source.Directory -> {
        // Resolved against the directory and then checked to be inside it: a published `file` is
        // already refused unless it is one flat name, and this is the second lock on the same door.
        val root = source.dir.canonicalFile
        val file = File(root, path).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) null
        else if (file.length() > maxBytes) {
          onLog("serve: $system's $path is larger than $maxBytes bytes; ignoring it")
          null
        } else file.readBytes()
      }
      is ServeUiBuilderDesignLibrary.Source.Branch ->
        fetch(
          "https://raw.githubusercontent.com/${source.repo}/${source.branch}/$path",
          maxBytes,
        )
    }
}
