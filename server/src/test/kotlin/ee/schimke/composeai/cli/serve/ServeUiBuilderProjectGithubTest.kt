package ee.schimke.composeai.cli.serve

import java.util.Base64
import kotlin.test.*
import kotlinx.serialization.json.*

class ServeUiBuilderProjectGithubTest {
  private val requests = mutableListOf<Pair<String, String>>()
  private var upstreamBlob = "old-blob"
  private val github =
    ServeUiBuilderProjectGithub(
      ProjectGithubTransport { method, path, body, _ ->
        requests += method to path
        val response =
          when {
            path == "/repos/org/app" -> """{"id":123,"default_branch":"main"}"""
            path == "/repos/org/app/commits/main" ->
              """{"sha":"head-commit","commit":{"tree":{"sha":"base-tree"}}}"""
            path.startsWith("/repos/org/app/git/trees/") ->
              """{"truncated":false,"tree":[{"path":"ui-builder/screens/login.uid","sha":"$upstreamBlob","type":"blob","mode":"100644"}]}"""
            path == "/repos/org/app/git/blobs/old-blob" -> buildJsonObject {
                put("size", documentText("login").length)
                put("encoding", "base64")
                put(
                  "content",
                  Base64.getEncoder().encodeToString(documentText("login").toByteArray()),
                )
              }
                .toString()
            path == "/repos/org/app/git/blobs" -> """{"sha":"new-blob"}"""
            path == "/repos/org/app/git/trees" -> {
              assertEquals("base-tree", body!!["base_tree"]!!.jsonPrimitive.content)
              """{"sha":"new-tree"}"""
            }
            path == "/repos/org/app/git/commits" -> {
              assertEquals(
                "head-commit",
                body!!["parents"]!!.jsonArray.single().jsonPrimitive.content,
              )
              """{"sha":"proposed-commit"}"""
            }
            path.startsWith("/repos/org/app/git/ref/heads/") ->
              return@ProjectGithubTransport ProjectGithubResponse(404, JsonNull)
            path == "/repos/org/app/git/refs" -> """{"ref":"created"}"""
            path.startsWith("/repos/org/app/pulls?") -> "[]"
            path == "/repos/org/app/pulls" ->
              """{"html_url":"https://github.com/org/app/pull/42"}"""
            else -> error("unexpected request: $method $path")
          }
        ProjectGithubResponse(
          if (method == "POST") 201 else 200,
          PROJECT_JSON.parseToJsonElement(response),
        )
      }
    )

  @Test
  fun `repository reads pin every file to the resolved commit and discover uid files`() {
    val (source, files) = github.connect("app", "App", "org/app", "", "ui-builder", null)
    assertEquals(123L, source.repositoryId)
    assertEquals("head-commit", source.baseCommit)
    assertEquals("screens/login.uid", files.first().path)
    assertEquals(documentText("login"), files.first().original)
    assertTrue(requests.any { it.second == "/repos/org/app/git/trees/head-commit?recursive=1" })
  }

  @Test
  fun `upstream conflicts refuse publication before any remote write`() {
    upstreamBlob = "other-blob"
    assertFailsWith<IllegalStateException> {
      github.publish(
        project(),
        mapOf("screens/login.uid" to documentText("login", "Edited")),
        "credential",
      )
    }
    assertTrue(requests.none { it.first == "POST" })
  }

  @Test
  fun `publication commits all proposed files together on the current branch head`() {
    val result =
      github.publish(
        project(),
        mapOf("screens/login.uid" to documentText("login", "Edited")),
        "credential",
      )
    assertEquals("https://github.com/org/app/pull/42", result.url)
    assertEquals("proposed-commit", result.commit)
    assertTrue(result.branch.startsWith("ui-builder/app-"))
    assertEquals(1, requests.count { it.second == "/repos/org/app/git/commits" })
    assertTrue(requests.none { it.first == "PATCH" }, "canonical branch is never moved")
  }

  private fun project() =
    BuilderProject(
      id = "app",
      name = "App",
      owner = "github:owner",
      source = ProjectRepository("org/app", "main", "ui-builder", "older-commit", 123),
      files =
        listOf(ProjectFile("login", "screens/login.uid", documentText("login"), blob = "old-blob")),
    )
}
