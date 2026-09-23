package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.service.AuthenticatedUiBuilderActor

/** Identities trusted to administer every shared UI-builder design on this host. */
internal class ServeUiBuilderAdministrators(actorIds: Set<String>) {
  private val actorIds = actorIds.map(::canonicalActorId).toSet()

  val configured: Boolean
    get() = actorIds.isNotEmpty()

  fun contains(actor: AuthenticatedUiBuilderActor): Boolean =
    actor.accessIdentities.any { canonicalActorId(it) in actorIds }

  fun containsGithubLogin(login: String?): Boolean =
    login?.let { canonicalActorId(ServeAgentGrants.githubActorId(it)) in actorIds } == true

  private fun canonicalActorId(actorId: String): String = actorId.trim().lowercase()
}
