package ee.schimke.composeai.cli.serve

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1

/**
 * What `ui_builder_check_design` with `rendered: true` makes of a native render that drew nothing
 * (#1666).
 *
 * A render that failed because the exported Kotlin does not compile, or compiled and threw, is a
 * fact about the design: the same source fails in a project with the same dependencies. Reporting
 * it as "no native render was available" and then "No problems found" told an agent the design was
 * clean when its code could not be built, so it is an error finding here. A host that produced no
 * frame for a design that compiled is the host's problem and stays a skip, with its reason.
 */
internal object ServeUiBuilderNativeCheck {

  /** The finding for a render without a frame, or null when the failure is the host's. */
  fun finding(
    document: DesignDocumentV1,
    response: PlaygroundRunResponse,
    reason: String,
  ): UiBuilderCheckFindingV1? {
    if (!response.exception.isNullOrBlank()) {
      return UiBuilderCheckFindingV1(
        severity = SEVERITY_ERROR,
        check = CHECK_NATIVE,
        code = CODE_NATIVE_RENDER_FAILED,
        message =
          "the exported Kotlin compiled but threw when this host drew it, so the rendered checks " +
            "did not run: $reason",
      )
    }
    if (response.diagnostics.none { it.severity == PlaygroundSeverity.ERROR }) return null
    val icon = missingIcon(document, reason)
    val message = buildString {
      append(
        "the exported Kotlin does not compile on this host's native render lane, so the " +
          "rendered checks did not run and a project with the same dependencies cannot build it"
      )
      if (icon != null) {
        append(
          ". `${icon.field}` = \"${icon.key}\" on `${icon.nodeId}` exports `Icons.*.${icon.member}`, " +
            "which is not on this bundle's classpath; it ships in " +
            "`$MATERIAL_ICONS_EXTENDED`, not `material-icons-core`. Pick an icon key the bundle " +
            "carries, or add that dependency to the project the code is for"
        )
      }
      append(": ")
      append(reason)
    }
    return UiBuilderCheckFindingV1(
      severity = SEVERITY_ERROR,
      check = CHECK_NATIVE,
      code = CODE_NATIVE_COMPILE_FAILED,
      message = message,
      nodeId = icon?.nodeId,
      field = icon?.field,
    )
  }

  /** An icon key whose `Icons` member the compiler could not resolve, with the node naming it. */
  internal data class MissingIcon(
    val nodeId: String,
    val field: String,
    val key: String,
    val member: String,
  )

  /**
   * The first `Unresolved reference 'X'` that a node's icon key exports to. Keys are camel case
   * (`pauseCircle`) or style qualified (`filled/pauseCircle`); the member is the last segment with
   * its first letter raised, which is how the inventory names it.
   */
  internal fun missingIcon(document: DesignDocumentV1, reason: String): MissingIcon? {
    val unresolved = UNRESOLVED.findAll(reason).map { it.groupValues[1] }.distinct().toList()
    if (unresolved.isEmpty()) return null
    for (member in unresolved) {
      for ((nodeId, node) in document.nodes.toSortedMap()) {
        for ((field, value) in node.properties) {
          val key = (value as? StringValueV1)?.value ?: continue
          val last = key.substringAfterLast('/')
          if (last.isNotEmpty() && last.replaceFirstChar { it.uppercase() } == member) {
            return MissingIcon(nodeId, field, key, member)
          }
        }
      }
    }
    return null
  }

  private val UNRESOLVED = Regex("Unresolved reference:? '?([A-Z][A-Za-z0-9_]*)'?")
  private const val MATERIAL_ICONS_EXTENDED = "androidx.compose.material:material-icons-extended"
}

/** Findings about the exported Kotlin itself, from the native render `rendered: true` asks for. */
internal const val CHECK_NATIVE = "native"
internal const val CODE_NATIVE_COMPILE_FAILED = "nativeCompileFailed"
internal const val CODE_NATIVE_RENDER_FAILED = "nativeRenderFailed"
