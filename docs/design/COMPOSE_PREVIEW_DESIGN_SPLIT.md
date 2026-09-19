# Splitting `design` out: a renderer that is not a server

**Status: proposal, and deliberately not started.** Nothing here is enforced. The question it answers
is a good one and the direction is right; what this document adds is the measurement, which says the
job is not the one it looks like and is not the next one to do.

## The case for it, in the command's own words

`compose-preview-server design …` already says it is the odd one out:

> **A client, not a server.** Every other command this binary has — `serve`, `ui`, `playground` —
> starts a server and stays up. This one runs against a server that is already up (`--server`) and
> exits.

So `design` is a **tool**, and it ships inside a **server distribution**. A CI lane that wants to
render one design downloads a server. That is the smell, and it is real:

- **A consumer pays for the whole server.** The render lane in
  [`ui-builder-designs-reusable.yml`](../../.github/workflows/ui-builder-designs-reusable.yml) runs
  in `compose-preview-host` — an image built to *serve* preview.coo.ee, carrying an Android SDK, a
  Caddy front end and a web bundle — to invoke one client command.
- **The layer rule already has a shape for this.** `compose-ui-builder` was extracted as a leaf that
  depends down; `compose-preview-design` would be another.
- **`design --local` is the only lane whose value is being a second implementation.** It exists so
  that when the server's render misbehaves there is something else to reproduce it with
  ([#551](https://github.com/yschimke/compose-preview-server/issues/551)). A second implementation
  that ships in the same artifact as the first is a weaker second implementation.

## The measurement, which is the reason this is not started

`design --local` does not own the things it renders with. It borrows the **server's own** compile and
render engine:

| | |
| --- | --- |
| The `design` command | ~1,970 lines: `DesignCommand`, `DesignCommandRunner`, `DesignCommandClient`, `DesignCommandEntry`, `DesignLocalLane`, `DesignLocalRunner`, `LocalUiBuilder` |
| What `--local` renders with | `PlaygroundCompileService`, `PlaygroundSandbox`, `PlaygroundDaemonOpeners`, `PlaygroundJailedCompiler`, `PlaygroundBtaCompiler`, `PlaygroundCatalogClasspath`, … |
| That engine | **6,093 lines across 16 files** |
| Its other consumers in `:server` | **10**, among them `ServeHttpServer`, `ServeWeb`, `ServeUiBuilderNativePreview`, `ServeUiBuilderInlineCapture`, `PlaygroundAndroidRenderService` |
| And the credential half | `ServeAgentGrants`, referenced a dozen times by the client verbs |

So "move `design` to its own repository" is not a move. It is **extract the render engine**, and then
have the server and the design tool both depend on it. The engine is the asset; the command is a
thin client of it. A split that took the command and left the engine would either duplicate 6,000
lines or reduce `--local` to the HTTP client it exists to not be.

## Three questions a split has to answer, and the tarball hides all three

1. **What carries the sidecars?** A local render needs `lib-bta/` (the Kotlin compiler),
   `lib-daemon-desktop/` + `lib-renderer/`, and the Skiko **native** for the host. The released
   `compose-preview-server-<version>.tar.gz` deliberately carries **neither the first nor the last**
   — `deploy/image/Dockerfile` assembles them from the compose-ai-tools CLI tarball and a
   `lib-skiko/` of its own, and
   [`UI_BUILDER_GETTING_STARTED.md`](../UI_BUILDER_GETTING_STARTED.md) says a render from a bare
   `installDist` needs them pointed at. A `compose-preview-design` tarball that renders standing
   alone must therefore bundle what the server tarball pointedly does not — a platform-specific
   native per published artifact — or keep depending on an image, in which case the tarball has not
   bought the independence it was for.
2. **Where does the generator come from?** The Kotlin a design becomes is
   `ScreenDocumentProjection` + `ScreenGenerator` + `RecordFreeExport`, which are
   **compose-ui-builder's** `:ui-builder-export`. A design repository consumes them as published
   coordinates, which is fine — a leaf depends down — but it means the split adds a *third*
   repository to the version matrix a render already spans.
3. **Who owns the grants?** `list` and `get` talk to a live server with a credential and an
   `AgentGrantCapability`. Either the split takes `ServeAgentGrants` (server code, in a client
   repository) or the client verbs stay behind and only `--local` moves, which cuts the command in
   half along a seam nobody asked for.

## Sequencing, which is the other reason

`compose-ui-builder` was extracted first and has since published, so its seams are coordinates:
three jars and a BOM on Maven Central and the editor archive as a GitHub release asset, with a
composite build opt-in through `-PcomposeUiBuilderDir`
([`UI_BUILDER_EXTRACTION_AND_DESKTOP.md`](UI_BUILDER_EXTRACTION_AND_DESKTOP.md)'s status note has
the details). What remains ahead of a second extraction is the cost below, not resolvability.
Starting it out of the same repository is how three repositories end up half-finished at once, and
the cost is paid in the place that is hardest to see: a change spanning the seam becomes two pull
requests, as [`UI_BUILDER_PROJECT_BOUNDARY.md`](UI_BUILDER_PROJECT_BOUNDARY.md) predicted and the
seed-template plan is now paying.

**The order that works:**

1. **Extract the render engine** — the `Playground*` compile and daemon services — as its own module
   inside this repository first, consumed by `:server` through an interface. That is the whole
   substance of the split and it is worth doing on its own merits: ten consumers reaching into a
   6,000-line engine by same-package visibility is the coupling that made this measurement necessary.
2. Only then does `compose-preview-design` become a move rather than a rewrite: the command, the
   engine module, and a tarball that names its sidecars.

Step 2 is the one to want. It is testable, reversible, and it makes the question in step 3 small.

## What happens to the workflow meanwhile

[`ui-builder-designs-reusable.yml`](../../.github/workflows/ui-builder-designs-reusable.yml) and
[`design-renders.py`](../../.github/scripts/design-renders.py) live **here**, beside the CLI they
drive and the image they run in, so the workflow, the binary and the sidecars release together and
no version-skew seam exists between them. They were written in compose-ai-tools first — next to
`apply` and the other reusable workflows — which read as the natural home for a reusable workflow and
was the wrong one: it put the workflow in one repository and both artefacts it drives in another.

If `design` is ever split out, the workflow moves with it, and the caller's one-line `uses:` is the
whole migration.
