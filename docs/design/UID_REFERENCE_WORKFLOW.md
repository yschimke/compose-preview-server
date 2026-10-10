# Checked-in UI Builder references

Apps without Figma can keep a reviewed `.uid` document in their repository and publish its native render as a provider-neutral design reference. The application preview must be produced independently from app code. Comparing two renders generated from the UID would not check the implementation.

The adaptive inbox pilot in `yschimke/m3-catalog/adaptive-uid-pilot` covers phone, medium and tablet layouts, both pane states and both themes, including 839/840dp around the two-pane breakpoint.

## Publication

`scripts/ui-builder/publish-references.mjs` accepts a `compose-ui-builder-references/v1` plan with `repository` and `captures`. Each capture names `id`, exact served `previewId`, repository-relative `.uid` `file`, `designId`, `widthDp`, `heightDp`, `theme`, `state`, optional `density` (default 2), and optional per-node `properties`. Collections must explicitly select a design; the active editor tab is irrelevant.

The CLI takes `--root`, `--plan`, `--out`, full source `--revision`, installed `--renderer`, native renderer `--catalog`, and optional `--components catalog=path`. Its native-render prerequisite is the same as `design render --local`, including the Kotlin compiler sidecar. Renderer density must match the declared capture density; mismatched dimensions fail publication.

The CLI verifies both the capture plan and source documents against the selected Git commit before rendering. It rejects a malformed existing manifest before starting any captures. It renders to temporary files, verifies dimensions, and writes content-addressed PNG/UID files before atomically replacing `references/index.json`. The existing `compose-preview-references/v1` schema carries provider `ui-builder`, a commit-pinned source URI, document/source hashes, renderer/component hashes, capture dimensions/theme/state and an inert UID artifact. It does not fetch the source URI. Catalog producers should include that directory with their ordinary images and source metadata.

Use a fresh staging directory for each publication. The publisher deliberately retains old content-addressed files so a reader holding the previous manifest can finish reading its assets; it does not garbage-collect a shared live output directory. Retire the old staging directory after its readers are finished.

## Navigation and review

The viewer's spec link and the focused comparison's **Open in UI Builder** link open the digest-checked snapshot using the released editor's existing host bridge. The editor page links back to that exact preview/reference pair and to the committed source UID. It offers a downloaded working copy; opening or editing never mutates the published reference or the shared design store. Catalog code links and bug reporting use the existing source/provenance metadata and reporting workflow.

The existing comparison scorer, highlighted pixel diff, overlay and report-region controls work unchanged. Reports should include viewport/theme/state, source commit, candidate/reference URLs and a selected region where useful. Fix the app or propose a reviewed UID change; do not regenerate the baseline merely to clear a diff.

UID artifact reads are bounded and digest-checked. A missing or damaged UID leaves the raster comparison usable. Editor HTML requires its `sha` fingerprint: a missing/stale fingerprint or historical editor request returns a conflict instead of silently opening today's design. Raw UID downloads may omit `sha`. Historical raster comparison remains unchanged; historical editable snapshots are a follow-up.

The project design library also accepts `.uid` files and explicit design IDs in collections, so a checked-in design can be opened independently of a published comparison. No dependency edge or release seam changes in compose-ui-builder are needed.

## Pilot evidence

All 20 independent reference/candidate pairs were rendered and scored in Chromium using a custom baked bundle with `RenderPilot` file-stem IDs. They are not the pilot's six discovered IDE preview IDs and cannot be attached through normal discovery publication unchanged. The pilot README documents the custom bundle and the extra catalog metadata used for code/report links; production publication remains separate work. The module is excluded from default builds unless `-PadaptiveUidPilot=true` is supplied.

The list captures match exactly at every width and theme. Detail captures retain a visible Back-button appearance difference (0.72–1.69% changed pixels); that is an untriaged result, not an accepted baseline update. Both captures switch to two panes at 840dp. Interaction tests separately verify selection survives resize and Back returns to the phone list.

The browser check also opened UI Builder from the comparison, followed the code context, downloaded the correct UID/state, returned to the same comparison, and inspected the issue draft's repository/source/reference fields. It submitted no issue. UI Builder's editing canvas deliberately unfolds all panes; its **Device view** toggle shows the actual adaptive layout.

Scores and image/document fingerprints are in [results.json](../evidence/uid-reference/results.json). Screenshots: [phone diff](../evidence/uid-reference/inbox-412-detail-light-comparison.png), [840dp two-pane layout](../evidence/uid-reference/inbox-840-list-light-comparison.png), [dark tablet](../evidence/uid-reference/inbox-960-list-dark-comparison.png), [snapshot editor in Device view](../evidence/uid-reference/uid-editor-phone.png).

## Validation

```sh
node --test scripts/ui-builder/publish-references.test.mjs
npm --prefix serve-web run build
scripts/agent-gradle.sh :server:test --tests '*ServeUidReferenceTest*' --tests '*ServeUiBuilderDesignLibraryTest*' --tests '*UiBuilderGeneratedPreviewAdapterTest*' --tests '*ServeFigmaSpecTest*'
```
