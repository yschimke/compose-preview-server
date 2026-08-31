package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.agentgrants.AgentGrantCapability
import kotlin.test.Test
import kotlin.test.assertEquals

class UiBuilderAgentGrantCapabilityTest {
  @Test
  fun `released contracts expose independent UI builder capabilities`() {
    assertEquals(
      AgentGrantCapability.UI_BUILDER_READ,
      AgentGrantCapability.parse("ui-builder-read"),
    )
    assertEquals(
      AgentGrantCapability.UI_BUILDER_WRITE,
      AgentGrantCapability.parse("ui-builder-write"),
    )
    assertEquals(
      AgentGrantCapability.UI_BUILDER_EXPORT,
      AgentGrantCapability.parse("ui-builder-export"),
    )
    assertEquals(
      listOf("ui-builder-read", "ui-builder-write", "ui-builder-export"),
      AgentGrantCapability.wireNames(
        setOf(
          AgentGrantCapability.UI_BUILDER_READ,
          AgentGrantCapability.UI_BUILDER_WRITE,
          AgentGrantCapability.UI_BUILDER_EXPORT,
        )
      ),
    )
  }
}
