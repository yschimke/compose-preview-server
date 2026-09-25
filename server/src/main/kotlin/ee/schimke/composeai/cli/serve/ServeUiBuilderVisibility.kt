package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignAccessControlV1
import ee.schimke.composeai.uibuilder.protocol.DesignAccessRoleV1
import ee.schimke.composeai.uibuilder.protocol.GrantActorAccessMutationV1
import ee.schimke.composeai.uibuilder.protocol.RevokeActorAccessMutationV1
import ee.schimke.composeai.uibuilder.service.UiBuilderPublicAccess
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceCall
import ee.schimke.composeai.uibuilder.service.UiBuilderServicePort
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceRequest
import ee.schimke.composeai.uibuilder.service.UiBuilderServiceResponse

/**
 * Whether a new UI-builder design starts out public — readable by anyone who has its link — or
 * private to its owner and the people they share it with (`--ui-builder-default-visibility`).
 *
 * A default, not a policy: the owner changes it per design from the design's share page, and a
 * public design is still only ever *read* by strangers. The mechanism is the runtime's
 * [UiBuilderPublicAccess] grant, so a design's visibility is part of its access list like every
 * other share.
 */
enum class UiBuilderDefaultVisibility(val wire: String) {
  PRIVATE("private"),
  PUBLIC("public");

  companion object {
    fun parse(value: String?): UiBuilderDefaultVisibility =
      value
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
          entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
            ?: throw IllegalArgumentException(
              "--ui-builder-default-visibility '$raw' is not one of " +
                entries.joinToString(", ") { it.wire }
            )
        } ?: PRIVATE
  }
}

internal object ServeUiBuilderVisibility {
  /**
   * The actor a signed-out visitor reads as on a `--public` box. It can reach exactly the designs
   * whose owner made them public — the runtime answers everything else as it would for a stranger —
   * and is refused as a share target, so nobody can hand "every anonymous visitor" more than a
   * look.
   */
  const val ANONYMOUS_ACTOR_ID = "anonymous:visitor"

  /** Share targets the access form refuses: the two that stand for "everyone". */
  fun isReservedActor(actorId: String): Boolean =
    actorId == UiBuilderPublicAccess.ANYONE_ACTOR_ID ||
      actorId.startsWith("anonymous:") ||
      actorId.startsWith("public:")

  fun isPublic(access: DesignAccessControlV1): Boolean =
    access.actorGrants.any { it.actorId == UiBuilderPublicAccess.ANYONE_ACTOR_ID }

  fun makePublic() =
    GrantActorAccessMutationV1(
      UiBuilderPublicAccess.ANYONE_ACTOR_ID,
      DesignAccessRoleV1.VIEWER,
      UiBuilderPublicAccess.PUBLIC_ACTIONS,
    )

  fun makePrivate() = RevokeActorAccessMutationV1(UiBuilderPublicAccess.ANYONE_ACTOR_ID)

  /**
   * [delegate], with every design it creates made public when [visibility] says so.
   *
   * Applied at the service port rather than in each create route because there are several — the
   * designs page, a copy, the editor's own create, MCP — and a default that one of them forgot
   * would be a default in name only. The grant is a second call, made as the creator (who owns what
   * they just created); should it fail, the design stays private, which is the safe side to land
   * on.
   */
  fun withDefault(
    delegate: UiBuilderServicePort,
    visibility: UiBuilderDefaultVisibility,
  ): UiBuilderServicePort {
    if (visibility == UiBuilderDefaultVisibility.PRIVATE) return delegate
    return object : UiBuilderServicePort by delegate {
      override suspend fun execute(call: UiBuilderServiceCall): UiBuilderServiceResponse {
        val response = delegate.execute(call)
        val request = call.request
        if (
          request is UiBuilderServiceRequest.CreateDesign &&
            response is UiBuilderServiceResponse.Snapshot
        ) {
          val designId = request.document.id
          val accessRevision = response.snapshot.access?.accessRevision ?: 0
          val granted = runCatching {
            delegate.execute(
              UiBuilderServiceCall(
                call.actor,
                UiBuilderServiceRequest.UpdateDesignAccess(
                  designId,
                  accessRevision,
                  listOf(makePublic()),
                ),
              )
            )
          }
            .getOrNull()
          if (granted !is UiBuilderServiceResponse.DesignAccess) {
            System.err.println(
              "serve: ui-builder: $designId could not be made public by default; it stays private"
            )
          }
        }
        return response
      }
    }
  }
}
