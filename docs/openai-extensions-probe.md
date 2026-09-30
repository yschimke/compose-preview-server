# OpenAI MCP Extensions probe

How to run the opt-in probe from
[#1236](https://github.com/yschimke/compose-preview-server/issues/1236) in ChatGPT or Codex
desktop, and what to record. The probe checks whether the
[OpenAI MCP Extensions](https://github.com/openai/mcp-extensions/blob/main/docs/spec.md) work for
a **local stdio MCP server installed from a marketplace plugin**, which is how the `compose-preview`
plugin ships. The spec's examples are all remote plugins. Every feature issue under
[#1235](https://github.com/yschimke/compose-preview-server/issues/1235) depends on the answers.

## What the probe adds

The probe tools are registered only when `COMPOSE_PREVIEW_MCP_OPENAI_PROBE=1` is set in the MCP
server's environment. Any other value, or no value, leaves every tool list unchanged.

| Tool | Entrypoint | Arguments | What it does |
| --- | --- | --- | --- |
| `probe_global` ("Compose Preview Probe") | `global` (sidebar) | `{}` | Opens the probe panel. |
| `probe_thread` ("Probe Tab") | `thread` (thread tab) | `{}` | Opens the probe panel. |
| `probe_file` ("Compose Probe Viewer") | `file`, `[".rc", ".uid"]` | `FileInput` | Opens the probe panel with the four file checks. |
| `probe_file_echo` | none; visible to the app only | `{touch?}` | Called by the panel. Echoes `_meta["openai/resource"].path`. With `touch`, it rewrites that file with its own bytes. |
| `probe_mentions` | `mentions/search`; visible to the app only | `{query}` | Returns three fixed `resource_link` items. Logs the query to stderr. |

Each entrypoint tool has a `title` and a monochrome SVG icon (20×20, `currentColor`). The panel is
the MCP App `ui://compose-preview/openai-probe` (`mcp-app/openai-probe.html`). Its resource
declares `availableDisplayModes: ["inline", "fullscreen"]` and `preferredDisplayMode: "fullscreen"`.
The panel shows:

- the `ui/initialize` result: `hostInfo`, `hostCapabilities` and `hostContext`, with
  `openai/deepLink` and `openai/modelContext` pulled out;
- every `host-context-changed`, `tool-input` and `tool-result` notification it receives; and
- a JSON **Report** block, which you copy into the evidence file.

The server writes one stderr line per probe call, starting with `compose-preview-mcp: openai probe`,
and one `compose-preview-mcp: client <name> <version>` line at `initialize`.

## Setup

You need macOS with Codex desktop (or ChatGPT desktop with plugins), a JDK 21, and
checkouts of this repository and
[compose-ag-plugin](https://github.com/yschimke/compose-ag-plugin).

1. **Build the MCP server from this branch.**

   ```sh
   ./gradlew :mcp:installDist
   ls mcp/build/install/compose-preview-mcp/bin/compose-preview-mcp
   ```

2. **Point a local copy of the plugin at that build, with the probe on.** In your
   compose-ag-plugin checkout, edit the `mcpServers` entry in
   `plugins/compose-preview/.codex-plugin/plugin.json`. Don't commit this change.

   ```json
   "mcpServers": {
     "compose-preview-mcp": {
       "command": "compose-preview",
       "args": ["mcp", "serve", "--mcp-binary",
                "/ABSOLUTE/PATH/compose-preview-server/mcp/build/install/compose-preview-mcp/bin/compose-preview-mcp"],
       "env": { "COMPOSE_PREVIEW_MCP_OPENAI_PROBE": "1" }
     }
   }
   ```

   `--mcp-binary` (or the `COMPOSE_PREVIEW_MCP` environment variable) tells the `compose-preview`
   CLI to run that binary instead of the released one. If `compose-preview` is not on the desktop
   app's `PATH`, set `command` to its absolute path. Also record whether the host honoured `env`:
   if the probe tools are missing, that is the first thing to check (see step 4).

3. **Install the plugin from the local marketplace.**

   ```sh
   codex plugin marketplace add /ABSOLUTE/PATH/compose-ag-plugin
   codex plugin add compose-preview@compose-ag-plugin
   codex plugin list
   ```

   Restart the desktop app so it starts the server again.

4. **Confirm the probe is live.** Ask in a thread: "list the compose-preview-mcp tools whose
   names start with probe_". All five should be listed. If none are, the `env` block did not
   reach the server: set `COMPOSE_PREVIEW_MCP_OPENAI_PROBE=1` for the desktop app instead
   (`launchctl setenv COMPOSE_PREVIEW_MCP_OPENAI_PROBE 1`, then restart the app), and record that.

5. **Prepare the test files.** In a project folder that the desktop app can see, create one file
   of each type, in a scratch directory. Step 3 of the file checks writes to them.

   ```sh
   mkdir -p ~/probe && printf 'probe rc\n' > ~/probe/hello.rc && printf '{"probe":"uid"}\n' > ~/probe/hello.uid
   ```

## Run

Record every result, including failures. For each question, answer "no" or "not listed" when that
is what happens, and quote any error you see.

1. **Global entrypoint.** Look in the sidebar for "Compose Preview Probe" with the probe icon. Open
   it. Copy the **Host** block.
2. **Thread entrypoint.** In a thread, open the tab picker and look for "Probe Tab". Open it and
   copy the **Host** block. Note whether each thread gets its own instance.
3. **File entrypoint.** Reference `~/probe/hello.rc` in a thread (for example, ask the agent to open
   it), then open the file. Check whether the probe viewer replaces the default viewer. Do the same
   for `hello.uid`. In the panel, press the buttons in this order:
   1. **resources/read**: this records the representation, the byte size and
      `_meta["openai/resource"]` (`writable`, `etag`);
   2. **resources/subscribe**, then **Touch the file on disk**. Also edit the file in an editor
      and save it. Record whether a `notifications/resources/updated` line appears under
      `notifications`, and how long it takes;
   3. **Echo openai/resource.path**: this records whether the host added the path to the tool
      call, and what the path was;
   4. **Write back…**, then **Confirm**. This button is enabled only when the read said
      `writable: true`. It writes the same bytes back with `ifMatch` set to the etag. Record the
      outcome.
   5. Optionally, **Size-limit probe…**, then **Confirm**. This sends a 32 MiB write with a stale
      `ifMatch`, so a host that checks `ifMatch` won't save it. A `too-large` result gives
      `maxBytes`. A `conflict` result means the host checks the etag first, or that the limit is
      above 32 MiB.

   Press **Copy report** and save the JSON.
4. **Mentions.** In the composer, type `@`, choose the compose-preview plugin, then type `hex`.
   Record whether `probe-mention-1`…`3` appear. Then check the server's stderr for the
   `mentions/search … query=` lines, and record each query string.
5. **Deep link.** Open
   `codex://plugins/compose-preview@compose-ag-plugin/app/probe_global?path=%2Fprobe%3Fx%3D1`.
   Record whether it opens the global probe, and the `openai/deepLink` value in the **Host** block
   or under `hostContextChanges`.

The server's stderr is in the desktop app's MCP log. Where that log lives depends on the host
version, so record where you found it.

## The seven questions

Record the answers for this host and version (a local stdio server installed from a marketplace
plugin):

1. **Entrypoints:** are the global, thread and file entrypoints listed, and do they open? Is the
   icon shown? Which title does the host use?
2. **Path injection:** is `_meta["openai/resource"].path` added to app→server tool calls inside a
   file entrypoint (from the `echo` record)? What does it contain: an absolute path, a symlink, or a
   sandbox path?
3. **Writes:** is the file resource `writable`? What is the outcome of the `ifMatch` write, and
   what is the size limit (`maxBytes`)?
4. **Subscriptions:** does `notifications/resources/updated` arrive after a change on disk, for
   both the touch and an editor save?
5. **Mentions:** does the host call mention search? With which queries: the empty string first,
   then one per keystroke? Does it show the resource links?
6. **Client:** what `clientInfo.name` does the server see (the `clientName` in each probe result,
   and the stderr `client` line)? What are the host's `hostInfo.name` and version?
7. **Deep link:** what form does `openai/deepLink` take (for example `{ "url": "/probe?x=1" }`)?
   Does it arrive at `ui/initialize`, or later as `host-context-changed`?

## Recording the evidence

Add a row for the OpenAI extensions to compose-ag-plugin's `docs/harness-matrix.md`. Add an
evidence file next to the others, named for example
`docs/evidence/<date>-codex-desktop-openai-extensions.md`. The evidence file holds the host
version, the plugin commit, this repository's commit, the copied **Host** blocks and **Report**
JSON, and the stderr lines. Then update #1235 with what holds and what doesn't, so each feature
issue either proceeds as written or is adjusted.
