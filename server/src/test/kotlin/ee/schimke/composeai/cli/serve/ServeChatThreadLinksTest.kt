package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ServeChatThreadLinksTest {

  @Test
  fun `a slack message permalink is a slack thread rooted at that message`() {
    val ref =
      ServeChatThreadLinks.slack("https://acme.slack.com/archives/C0123ABCD/p1700000000000200")

    assertEquals(
      ServeChatThreadLinks.SlackThread(
        workspace = "acme",
        channel = "C0123ABCD",
        messageTs = "1700000000.000200",
        threadTs = "1700000000.000200",
      ),
      ref,
    )
  }

  @Test
  fun `a reply's permalink names the thread root from its thread_ts`() {
    val ref =
      ServeChatThreadLinks.slack(
        "https://acme.slack.com/archives/C0123ABCD/p1700000000000900" +
          "?thread_ts=1700000000.000200&cid=C0123ABCD"
      )

    assertEquals("1700000000.000900", ref?.messageTs)
    assertEquals("1700000000.000200", ref?.threadTs)
  }

  @Test
  fun `anything that is not a slack message permalink is not parsed as one`() {
    assertNull(ServeChatThreadLinks.slack("https://acme.slack.com/archives/C0123ABCD"))
    assertNull(
      ServeChatThreadLinks.slack("http://acme.slack.com/archives/C0123ABCD/p1700000000000200")
    )
    assertNull(
      ServeChatThreadLinks.slack(
        "https://slack.com.evil.example/archives/C0123ABCD/p1700000000000200"
      )
    )
    assertNull(
      ServeChatThreadLinks.slack("https://github.com/yschimke/compose-preview-server/issues/1254")
    )
    assertNull(ServeChatThreadLinks.slack("not a url"))
  }

  @Test
  fun `the platform is read off the host`() {
    fun kind(url: String) = ServeChatThreadLinks.kind(url).wire
    assertEquals("slack", kind("https://acme.slack.com/archives/C0123ABCD/p1700000000000200"))
    assertEquals("teams", kind("https://teams.microsoft.com/l/message/19:abc/1700000000000"))
    assertEquals("discord", kind("https://discord.com/channels/1/2/3"))
    assertEquals("google-chat", kind("https://chat.google.com/room/AAAA/BBBB"))
    assertEquals("google-chat", kind("https://mail.google.com/chat/u/0/#chat/space/AAAA"))
    assertEquals("other", kind("https://mail.google.com/mail/u/0"))
    assertEquals("other", kind("https://slack.com.evil.example/archives/C1/p1"))
  }

  @Test
  fun `get_links describes a slack thread beside the record, and nothing when unset`() {
    assertEquals(JsonObject(emptyMap()), ServeChatThreadLinks.describe(null))
    val described =
      ServeChatThreadLinks.describe("https://acme.slack.com/archives/C0123ABCD/p1700000000000200")
    assertEquals("slack", described["threadKind"]!!.jsonPrimitive.content)
    assertEquals(
      "C0123ABCD",
      described["slackThread"]!!.jsonObject["channel"]!!.jsonPrimitive.content,
    )
    val other = ServeChatThreadLinks.describe("https://discord.com/channels/1/2/3")
    assertEquals("discord", other["threadKind"]!!.jsonPrimitive.content)
    assertNull(other["slackThread"])
  }

  @Test
  fun `a chat message names the platform of the design's thread`() {
    assertEquals(
      "Slack thread for this design",
      ServeChatThreadLinks.label("https://acme.slack.com/archives/C0123ABCD/p1700000000000200"),
    )
    assertEquals(
      "Discussion for this design",
      ServeChatThreadLinks.label("https://forum.example/t/42"),
    )
  }
}
