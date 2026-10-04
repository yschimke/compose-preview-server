package ee.schimke.composeai.cli.serve

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * This MCP endpoint is stateless. Presence means a tool is running, or ran in the last 30 seconds,
 * rather than claiming to know whether an idle client is connected. No credentials are retained.
 */
class ServeUiBuilderAgentPresence(private val now: () -> Long = System::currentTimeMillis) {
  @Serializable data class Participant(val id: String, val name: String, val model: String? = null)

  @Serializable data class Roster(val agents: List<Participant>)

  private data class Entry(val participant: Participant, val running: Int, val seen: Long)

  private val entries = mutableMapOf<Pair<String, String>, Entry>()

  @Synchronized
  fun enter(designId: String, actorId: String, name: String?, model: String?): () -> Unit {
    prune()
    val key = designId to actorId
    val previous = entries[key]
    val participant =
      Participant(
        previous?.participant?.id ?: UUID.randomUUID().toString(),
        label(name) ?: previous?.participant?.name ?: "MCP agent",
        label(model) ?: previous?.participant?.model,
      )
    entries[key] = Entry(participant, (previous?.running ?: 0) + 1, now())
    return { leave(key) }
  }

  @Synchronized
  private fun leave(key: Pair<String, String>) {
    val entry = entries[key] ?: return
    entries[key] = entry.copy(running = (entry.running - 1).coerceAtLeast(0), seen = now())
  }

  @Synchronized
  fun roster(designId: String, redact: Boolean = false): Roster {
    prune()
    return Roster(
      entries
        .filterKeys { it.first == designId }
        .values
        .map { entry ->
          if (redact) entry.participant.copy(name = "MCP agent", model = null)
          else entry.participant
        }
        .sortedWith(compareBy(Participant::name, Participant::id))
    )
  }

  private fun prune() {
    val time = now()
    entries.entries.removeAll { (_, entry) -> entry.running == 0 && time - entry.seen >= 30_000L }
  }

  private fun label(value: String?): String? =
    value?.filter { !it.isISOControl() }?.trim()?.take(80)?.takeIf { it.isNotBlank() }
}
