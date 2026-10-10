package ee.schimke.composeai.cli.serve

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import kotlinx.serialization.json.*

internal data class ProjectGithubResponse(val status: Int, val body: JsonElement)

internal fun interface ProjectGithubTransport {
  fun request(
    method: String,
    path: String,
    body: JsonObject?,
    token: String?,
  ): ProjectGithubResponse
}

/** Fixed GitHub origin, bounded bodies, no credential persistence, no raw GitHub errors in logs. */
internal class ServeUiBuilderProjectGithub(
  private val transport: ProjectGithubTransport = defaultTransport()
) {
  fun connect(
    id: String,
    name: String,
    repository: String,
    requestedBranch: String,
    root: String,
    token: String?,
  ): Pair<ProjectRepository, List<ProjectFile>> {
    validateRepository(repository)
    if (root.isNotEmpty()) validateProjectPath(root)
    val repo = get("/repos/$repository", token)
    val branch = requestedBranch.ifBlank { repo.string("default_branch") }
    require(branch.isNotBlank() && branch.length <= 256 && !branch.any(Char::isISOControl)) {
      "invalid branch"
    }
    val commit = get("/repos/$repository/commits/${segment(branch)}", token).string("sha")
    val tree = tree(repository, commit, token)
    val prefix = if (root.isEmpty()) "" else "$root/"
    val manifestPath = prefix + "project.json"
    val manifestBlob = tree[manifestPath]
    val manifestText = manifestBlob?.let { blob(repository, it, token) }
    val manifest = manifestText?.let { PROJECT_JSON.decodeFromString<ProjectManifest>(it) }
    require(manifest == null || manifest.schema == "compose-ui-builder-project/v1") {
      "unsupported project manifest"
    }
    val designPaths =
      manifest?.files
        ?: tree.keys
          .filter { it.startsWith(prefix) && it.endsWith(".uid") }
          .sorted()
          .map { ProjectManifestFile(projectDigest(it).take(16), it.removePrefix(prefix)) }
    require(designPaths.isNotEmpty() && designPaths.size <= 100) {
      "repository project must have 1–100 design files"
    }
    require(designPaths.map { it.id }.distinct().size == designPaths.size) {
      "duplicate manifest file id"
    }
    val resources =
      manifest?.resources.orEmpty().flatMap { (kind, paths) ->
        require(kind in setOf("components", "tokens", "themes")) {
          "unsupported project resource kind"
        }
        paths.map { ProjectManifestFile(projectDigest("$kind:$it").take(16), it) to kind }
      }
    val entries = designPaths.map { it to "design" } + resources
    require(entries.size <= 100 && entries.map { it.first.path }.distinct().size == entries.size) {
      "project file limit or duplicate path"
    }
    val files =
      entries.map { (entry, kind) ->
        validateProjectPath(entry.path)
        require(PROJECT_ID.matches(entry.id)) { "invalid file id" }
        if (kind == "design")
          require(entry.path.endsWith(".uid")) { "design files must end in .uid" }
        val sha =
          requireNotNull(tree[prefix + entry.path]) { "manifest file is missing: ${entry.path}" }
        ProjectFile(entry.id, entry.path, blob(repository, sha, token), blob = sha, kind = kind)
      } +
        ProjectFile(
          "project-manifest",
          "project.json",
          manifestText
            ?: PROJECT_JSON.encodeToString(
              ProjectManifest.serializer(),
              ProjectManifest(id = id, name = name, files = designPaths),
            ),
          blob = manifestBlob,
          kind = "manifest",
        )
    require(files.sumOf { it.original.toByteArray().size.toLong() } <= MAX_PROJECT_BYTES / 2) {
      "repository project exceeds its import budget"
    }
    return ProjectRepository(
      repository,
      branch,
      root,
      commit,
      repo.getValue("id").jsonPrimitive.long,
    ) to files
  }

  /** Compare every proposed path, then create blobs, one tree, one commit and an explicit PR. */
  fun publish(
    project: BuilderProject,
    contents: Map<String, String>,
    token: String,
  ): ProjectPublication {
    require(token.isNotBlank()) { "a GitHub write credential is required for this action" }
    val source = requireNotNull(project.source) { "this project is hosted here" }
    check(
      get("/repos/${source.repository}", token).getValue("id").jsonPrimitive.long ==
        source.repositoryId
    ) {
      "repository identity changed; reconnect before publishing"
    }
    val prefix = if (source.root.isEmpty()) "" else "${source.root}/"
    val head = get("/repos/${source.repository}/commits/${segment(source.branch)}", token)
    val currentCommit = head.string("sha")
    val currentTree = tree(source.repository, currentCommit, token)
    project.files.forEach { file ->
      check(currentTree[prefix + file.path] == file.blob) {
        "${file.path} changed upstream; open a fresh working copy before publishing"
      }
    }
    val digests = contents.mapValues { projectDigest(it.value) }
    project.publication
      ?.takeIf { it.fileDigests == digests }
      ?.let {
        return it
      }
    val changed =
      project.files.filter { contents.getValue(it.path) != it.original || it.blob == null }
    require(changed.isNotEmpty()) { "no file changes to propose" }
    val entries = changed.map { file ->
      val created =
        post(
          "/repos/${source.repository}/git/blobs",
          buildJsonObject {
            put("content", contents.getValue(file.path))
            put("encoding", "utf-8")
          },
          token,
        )
      buildJsonObject {
        put("path", prefix + file.path)
        put("mode", "100644")
        put("type", "blob")
        put("sha", created.string("sha"))
      }
    }
    val newTree =
      post(
          "/repos/${source.repository}/git/trees",
          buildJsonObject {
            put(
              "base_tree",
              head.getValue("commit").jsonObject.getValue("tree").jsonObject.string("sha"),
            )
            put("tree", JsonArray(entries))
          },
          token,
        )
        .string("sha")
    val branch = "ui-builder/${project.id}-${projectDigest(currentCommit + newTree).take(16)}"
    val existing =
      transport.request(
        "GET",
        "/repos/${source.repository}/git/ref/heads/${segment(branch)}",
        null,
        token,
      )
    val commit =
      if (existing.status == 404) {
        val created =
          post(
              "/repos/${source.repository}/git/commits",
              buildJsonObject {
                put("message", "design: update ${project.name}")
                put("tree", newTree)
                put("parents", JsonArray(listOf(JsonPrimitive(currentCommit))))
              },
              token,
            )
            .string("sha")
        post(
          "/repos/${source.repository}/git/refs",
          buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", created)
          },
          token,
        )
        created
      } else {
        require(existing.status == 200) { "GitHub branch read failed (${existing.status})" }
        val sha = existing.body.jsonObject.getValue("object").jsonObject.string("sha")
        val existingCommit = get("/repos/${source.repository}/git/commits/$sha", token)
        check(existingCommit.getValue("tree").jsonObject.string("sha") == newTree) {
          "publication branch changed; refusing to replace it"
        }
        sha
      }
    val prs =
      transport.request(
        "GET",
        "/repos/${source.repository}/pulls?state=open&head=${segment(source.repository.substringBefore('/') + ":" + branch)}&base=${segment(source.branch)}",
        null,
        token,
      )
    require(prs.status == 200) { "GitHub pull-request lookup failed (${prs.status})" }
    val pr =
      prs.body.jsonArray.firstOrNull()?.jsonObject
        ?: post(
          "/repos/${source.repository}/pulls",
          buildJsonObject {
            put("title", "design: update ${project.name}")
            put("head", branch)
            put("base", source.branch)
            put(
              "body",
              "UI Builder working copy of `${project.id}`, based on `${source.baseCommit}`.\n\nReview the complete .uid files and project resources together. The canonical repository is unchanged until this pull request merges.",
            )
          },
          token,
        )
    return ProjectPublication(pr.string("html_url"), branch, commit, digests)
  }

  private fun tree(repository: String, commit: String, token: String?): Map<String, String> {
    val response = get("/repos/$repository/git/trees/$commit?recursive=1", token)
    require(response["truncated"]?.jsonPrimitive?.boolean != true) {
      "repository tree is truncated; use a smaller repository"
    }
    return response
      .getValue("tree")
      .jsonArray
      .map { it.jsonObject }
      .filter {
        it["type"]?.jsonPrimitive?.content == "blob" &&
          it["mode"]?.jsonPrimitive?.content in setOf("100644", "100755")
      }
      .associate { it.string("path") to it.string("sha") }
  }

  private fun blob(repository: String, sha: String, token: String?): String {
    val result = get("/repos/$repository/git/blobs/$sha", token)
    require(result.getValue("size").jsonPrimitive.long <= MAX_PROJECT_FILE_BYTES) {
      "repository file too large"
    }
    require(result.string("encoding") == "base64") { "unsupported GitHub blob encoding" }
    return Base64.getMimeDecoder().decode(result.string("content")).toString(Charsets.UTF_8)
  }

  private fun get(path: String, token: String?): JsonObject = request("GET", path, null, token)

  private fun post(path: String, body: JsonObject, token: String): JsonObject =
    request("POST", path, body, token)

  private fun request(method: String, path: String, body: JsonObject?, token: String?): JsonObject {
    val response = transport.request(method, path, body, token)
    check(response.status in 200..299) {
      "GitHub request failed (${response.status}); check repository access and retry"
    }
    return response.body.jsonObject
  }

  companion object {
    private fun defaultTransport(): ProjectGithubTransport {
      val client =
        HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build()
      return ProjectGithubTransport { method, path, body, token ->
        require(path.startsWith("/repos/"))
        val builder =
          HttpRequest.newBuilder(URI("https://api.github.com$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (token != null) builder.header("Authorization", "Bearer $token")
        val publisher =
          body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
            ?: HttpRequest.BodyPublishers.noBody()
        val response =
          client.send(
            builder.method(method, publisher).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
          )
        val bytes = response.body().use { it.readNBytes((MAX_PROJECT_FILE_BYTES * 2 + 1).toInt()) }
        require(bytes.size <= MAX_PROJECT_FILE_BYTES * 2) { "GitHub response too large" }
        ProjectGithubResponse(
          response.statusCode(),
          runCatching { PROJECT_JSON.parseToJsonElement(bytes.toString(Charsets.UTF_8)) }
            .getOrDefault(JsonNull),
        )
      }
    }
  }
}

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

private fun segment(value: String): String =
  URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

private fun validateRepository(repository: String) {
  require(
    Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repository) &&
      repository.split('/').none { it == "." || it == ".." }
  ) {
    "repository must be owner/name"
  }
}
