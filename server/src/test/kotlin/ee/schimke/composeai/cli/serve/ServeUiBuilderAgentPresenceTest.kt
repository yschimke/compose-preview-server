package ee.schimke.composeai.cli.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServeUiBuilderAgentPresenceTest {
  @Test
  fun `active waits survive expiry and concurrent calls retain one participant`() {
    var time = 0L
    val presence = ServeUiBuilderAgentPresence { time }
    val first = presence.enter("design", "agent", "Codex", "model")
    val second = presence.enter("design", "agent", null, null)
    time = 90_000
    first()
    assertEquals(1, presence.roster("design").agents.size)
    time += 30_000
    assertEquals("model", presence.roster("design").agents.single().model)
    second()
    time += 29_999
    assertEquals("Codex", presence.roster("design").agents.single().name)
    time++
    assertTrue(presence.roster("design").agents.isEmpty())
  }

  @Test
  fun `presence isolates designs and redacts public identity`() {
    val presence = ServeUiBuilderAgentPresence { 0 }
    presence.enter("private", "actor", "Claude", "reported model")
    assertTrue(presence.roster("other").agents.isEmpty())
    val public = presence.roster("private", redact = true).agents.single()
    assertEquals("MCP agent", public.name)
    assertEquals(null, public.model)
    assertTrue(public.id != "actor")
  }
}
