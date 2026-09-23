package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServeUiBuilderAdministratorsTest {
  private val administrators = ServeUiBuilderAdministrators(setOf("github:YSchimke"))

  @Test
  fun `github login matching is case insensitive`() {
    assertTrue(administrators.containsGithubLogin("yschimke"))
    assertFalse(administrators.containsGithubLogin("someone-else"))
  }

  @Test
  fun `an approved agent inherits its human administrator identity`() {
    assertTrue(
      administrators.contains(
        AuthenticatedUiBuilderActor(
          actorId = "agent-grant:temporary",
          onBehalfOfActorId = "github:yschimke",
        )
      )
    )
    assertFalse(administrators.contains(AuthenticatedUiBuilderActor("github:someone-else")))
  }
}
