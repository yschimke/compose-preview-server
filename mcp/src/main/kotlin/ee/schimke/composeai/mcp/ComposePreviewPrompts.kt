package ee.schimke.composeai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.types.Prompt
import io.modelcontextprotocol.kotlin.sdk.types.PromptArgument
import io.modelcontextprotocol.kotlin.sdk.types.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/** Slash-command prompts for the local Compose Preview MCP surface. */
internal object ComposePreviewPrompts {
  private const val PREVIEW_FILE = "preview-file"
  private const val MIGRATE_WEAR_M3 = "migrate-wear-m3"

  fun list(): List<Prompt> =
    listOf(
      Prompt(
        name = PREVIEW_FILE,
        description = "Find every Compose preview declared by a source file and render it.",
        arguments =
          listOf(
            PromptArgument(
              name = "path",
              description = "Absolute or workspace-relative source-file path.",
              required = true,
            )
          ),
      ),
      Prompt(
        name = MIGRATE_WEAR_M3,
        description = "Render-aware Wear OS Material 2.5 / Horologist to Material 3 migration.",
      ),
    )

  fun get(name: String, arguments: Map<String, String>): GetPromptResult =
    when (name) {
      PREVIEW_FILE -> previewFile(arguments["path"])
      MIGRATE_WEAR_M3 -> migrateWearM3()
      else -> throw IllegalArgumentException("unknown prompt: $name")
    }

  private fun previewFile(path: String?): GetPromptResult {
    require(!path.isNullOrBlank()) { "preview-file requires a path" }
    return result(
      """
      Render the Compose previews declared by `$path`.

      1. Call `find_previews_for_file` with `path: "$path"`.
      2. Render each returned URI with `render_preview`. Start with `observe: "hash"`; request
         `observe: "png"` for screens that need visual inspection.
      3. Report a file with no previews plainly. Do not guess a preview URI from the Kotlin name.
      4. If the source changed outside the daemon watcher, call `notify_file_changed` before
         rendering and then repeat the lookup.
      """
        .trimIndent()
    )
  }

  private fun migrateWearM3(): GetPromptResult =
    result(
      """
      Migrate a Wear OS Compose Material 2.5 / Horologist screen to Wear Compose Material 3.

      Follow the official Wear Compose M3 skill for dependency versions, API choices and component
      mappings. This server is for visual evidence and verification; it must not substitute a
      lookalike implementation for Wear components.

      1. Before changing each source file, use `find_previews_for_file` and render its previews
         with `render_preview` using `observe: "hash"`. Keep PNGs only for key screens.
      2. Apply the official migration. Hash and pixel differences are expected migration evidence,
         not regressions to restore.
      3. Re-render. Treat render failures and new `get_preview_data kind: "a11y/atf"` findings as
         blockers; report other visual changes as information.
      4. Use `render_matrix` to check a small round device and the largest supported font scale.
         Ask for a contact sheet only when it is needed for visual review.
      5. Show before/after evidence for one to three key screens, and never change the migration
         merely to reproduce the old screenshot.
      """
        .trimIndent()
    )

  private fun result(text: String): GetPromptResult =
    GetPromptResult(
      description = "Compose Preview workflow",
      messages = listOf(PromptMessage(role = Role.User, content = TextContent(text = text))),
    )
}
