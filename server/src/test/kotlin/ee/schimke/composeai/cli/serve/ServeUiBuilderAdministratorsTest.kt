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
  fun `only the actor's own id makes it an administrator`() {
    assertTrue(administrators.contains(AuthenticatedUiBuilderActor("github:yschimke")))
    assertFalse(
      administrators.contains(
        AuthenticatedUiBuilderActor(
          actorId = "agent-grant:temporary",
          onBehalfOfActorId = "github:yschimke",
        )
      ),
      "a grant approved by an administrator does not administer the host",
    )
    assertFalse(
      administrators.contains(
        AuthenticatedUiBuilderActor("github:guest", onBehalfOfActorId = "github:yschimke")
      )
    )
    assertFalse(administrators.contains(AuthenticatedUiBuilderActor("github:someone-else")))
  }
}
