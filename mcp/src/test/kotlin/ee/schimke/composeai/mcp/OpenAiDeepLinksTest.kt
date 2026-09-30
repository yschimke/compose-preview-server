package ee.schimke.composeai.mcp

import com.google.common.truth.Truth.assertThat
import java.net.URI
import org.junit.Assert.assertThrows
import org.junit.Test

/** Deep links and library routes (#1241) against the spec's "Deep Links" rules. */
class OpenAiDeepLinksTest {
  private val marketplacePlugin = OpenAiPlugin("compose-preview", marketplace = "yschimke")
  private val directPlugin = OpenAiPlugin("plugin_abc123")

  @Test
  fun `spec example round-trips`() {
    // The spec's own example: `codex://plugins/bits-and-bolts/app/cad.library?path=…`.
    assertThat(
        OpenAiDeepLinks.link(
          OpenAiPlugin("bits-and-bolts"),
          "cad.library",
          "/parts?tag=bolt&sort=asc",
        )
      )
      .isEqualTo(
        "codex://plugins/bits-and-bolts/app/cad.library?path=%2Fparts%3Ftag%3Dbolt%26sort%3Dasc"
      )
  }

  @Test
  fun `desktop, mobile and web forms`() {
    val route = LibraryRoute.Project("ws-1")
    assertThat(OpenAiDeepLinks.link(marketplacePlugin, PreviewLibrary.TOOL, route))
      .isEqualTo(
        "codex://plugins/compose-preview@yschimke/app/previews_library?path=%2Fproject%2Fws-1"
      )
    assertThat(
        OpenAiDeepLinks.link(
          marketplacePlugin,
          PreviewLibrary.TOOL,
          route,
          OpenAiDeepLinks.Surface.MOBILE,
        )
      )
      .isEqualTo(
        "chatgpt://plugins/compose-preview@yschimke/app/previews_library?path=%2Fproject%2Fws-1"
      )
    // The web form carries no marketplace.
    assertThat(
        OpenAiDeepLinks.link(
          marketplacePlugin,
          PreviewLibrary.TOOL,
          route,
          OpenAiDeepLinks.Surface.WEB,
        )
      )
      .isEqualTo(
        "https://chatgpt.com/plugins/compose-preview/app/previews_library?path=%2Fproject%2Fws-1"
      )
    // A plugin published directly to ChatGPT omits `@{marketplace}`.
    assertThat(OpenAiDeepLinks.link(directPlugin, "catalog_library"))
      .isEqualTo("codex://plugins/plugin_abc123/app/catalog_library")
  }

  @Test
  fun `the path begins with a slash, has no fragment, and root is omitted`() {
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiDeepLinks.link(directPlugin, "t", "preview/x")
    }
    assertThrows(IllegalArgumentException::class.java) {
      OpenAiDeepLinks.link(directPlugin, "t", "/preview/x#frag")
    }
    assertThat(OpenAiDeepLinks.link(directPlugin, "t", "/")).doesNotContain("?path=")
  }

  @Test
  fun `a preview URI is encoded twice and decodes back to the same route`() {
    val uri = "compose-preview://ws-1/_app/com.example.HomeKt.HomePreview?config=dark&x=a b"
    val route = LibraryRoute.Preview(uri)
    assertThat(route.path).startsWith("/preview/compose-preview%3A%2F%2Fws-1%2F_app%2F")
    assertThat(route.path.removePrefix("/preview/")).doesNotContain("/")
    val link = OpenAiDeepLinks.link(marketplacePlugin, PreviewLibrary.TOOL, route)

    // What the host does: take the `path` query value and percent-decode it once.
    val query = URI(link).rawQuery
    assertThat(query).startsWith("path=")
    val appPath = OpenAiDeepLinks.decode(query.removePrefix("path="))
    assertThat(appPath).isEqualTo(route.path)
    assertThat(appPath).startsWith("/")
    assertThat(appPath).doesNotContain("#")
    // Then the app parses its own route.
    assertThat(LibraryRoute.parse(appPath)).isEqualTo(route)
  }

  @Test
  fun `the link is a valid URI with the plugin id and tool name as single segments`() {
    val odd = OpenAiPlugin("my plugin/1", marketplace = "team@corp")
    val link = OpenAiDeepLinks.link(odd, "ui builder/open", LibraryRoute.Design("d 1"))
    assertThat(link)
      .isEqualTo(
        "codex://plugins/my%20plugin%2F1@team%40corp/app/ui%20builder%2Fopen?path=%2Fdesign%2Fd%25201"
      )
    val parsed = URI(link)
    assertThat(parsed.scheme).isEqualTo("codex")
    assertThat(parsed.rawPath.split('/').filter { it.isNotEmpty() })
      .containsExactly("my%20plugin%2F1@team%40corp", "app", "ui%20builder%2Fopen")
      .inOrder()
  }

  @Test
  fun `encode leaves only unreserved characters and decode inverts it`() {
    val raw = "/a b?c=d&e=f/g:h@i+j~k_l.m-n/ü😀"
    val encoded = OpenAiDeepLinks.encode(raw)
    assertThat(encoded).matches("[A-Za-z0-9._~%-]*")
    assertThat(encoded).contains("%20")
    assertThat(encoded).contains("%2B")
    assertThat(OpenAiDeepLinks.decode(encoded)).isEqualTo(raw)
    assertThrows(IllegalArgumentException::class.java) { OpenAiDeepLinks.decode("%2") }
    assertThrows(IllegalArgumentException::class.java) { OpenAiDeepLinks.decode("%zz") }
  }

  @Test
  fun `route parsing`() {
    assertThat(LibraryRoute.parse("/")).isEqualTo(LibraryRoute.Home)
    assertThat(LibraryRoute.parse("/project/ws-1/")).isEqualTo(LibraryRoute.Project("ws-1"))
    assertThat(LibraryRoute.parse("/project/ws-1?tab=modules"))
      .isEqualTo(LibraryRoute.Project("ws-1"))
    assertThat(LibraryRoute.parse("/design/abc%2F1")).isEqualTo(LibraryRoute.Design("abc/1"))
    assertThat(LibraryRoute.parse("/preview/compose-preview%3A%2F%2Fw%2F_m%2Fa.B"))
      .isEqualTo(LibraryRoute.Preview("compose-preview://w/_m/a.B"))
    assertThat(LibraryRoute.parse("project/ws-1")).isNull()
    assertThat(LibraryRoute.parse("/project/ws-1#x")).isNull()
    assertThat(LibraryRoute.parse("/unknown/x")).isNull()
    assertThat(LibraryRoute.parse("/preview/a/b")).isNull()
    assertThat(LibraryRoute.parse("/preview/%zz")).isNull()
  }

  @Test
  fun `plugin comes from the environment and is never guessed`() {
    assertThat(OpenAiPlugin.fromEnvironment(emptyMap())).isNull()
    assertThat(OpenAiPlugin.fromEnvironment(mapOf(OpenAiPlugin.PLUGIN_ID_ENV to " "))).isNull()
    assertThat(
        OpenAiPlugin.fromEnvironment(
          mapOf(OpenAiPlugin.PLUGIN_ID_ENV to "cp", OpenAiPlugin.MARKETPLACE_ENV to "mk")
        )
      )
      .isEqualTo(OpenAiPlugin("cp", "mk"))
    assertThat(OpenAiPlugin.fromEnvironment(mapOf(OpenAiPlugin.PLUGIN_ID_ENV to "cp")))
      .isEqualTo(OpenAiPlugin("cp"))
  }
}
