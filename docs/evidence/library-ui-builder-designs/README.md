# Designs in the preview library

The library now has Previews and Designs tabs. `local-designs.png` shows the actual library app
running in a controlled MCP Apps test host. The paths and listing are fixtures; no rendered design
is fabricated. Selecting a local design shows its path and **Open in UI Builder**, which delegates
to the host's `openai/files/open` capability, or `ui/open-link` with an encoded file URL.
The host remains responsible for resolving that file into its .uid editor entrypoint.

Local discovery reads file metadata only, never document content. It searches registered projects
and the roots of connected Compose Preview MCP sessions. Each local MCP process publishes its
active roots into a separate record under `~/.cache/composeai/mcp/active-design-roots/`; records
contain process identity and folder paths, not design bytes. Closed sessions remove their roots;
shutdown deletes the process record, and dead/reused process identities are discarded by readers.
The process working directory is registered immediately; client-provided MCP roots replace it
when the session first calls a tool. This does not enumerate unrelated chats or remote machines.

Discovery skips hidden/generated dependency directories and symlinks, never searches from `/`,
and is bounded to 10,000 filesystem entries, 12 directory levels and 200 designs per refresh.
An active root can itself be a hidden worktree folder. Duplicate and overlapping roots produce
one file entry, and deleted files disappear on refresh. Discovery starts no Gradle builds.

Hosted catalog libraries offer the same Designs tab only when their own server has UI Builder.
They reuse `ui_builder_list_designs` and `ui_builder_view`, retaining their existing actor/grant
checks. Local discovery does not connect to or copy hosted designs.

Validation:

```sh
node --test scripts/preview-library.test.mjs
scripts/agent-gradle.sh ktfmtFormat :mcp:test --tests '*PreviewLibraryMcpTest*' --tests '*LocalDesignDiscoveryTest*' :server:test --tests '*ServeLibraryMcpTest*'
```

The browser test checks search by path, opening the original file with spaces and `#` in its
name, both host-opening paths, and surfacing hosted authorization errors. The Kotlin tests cover
non-Gradle session roots, cross-process discovery, cleanup, deduplication, generated directories,
symlink boundaries, deleted files and optional hosted builder capability.
