# State selection in the actual Wasm editor

These are Chrome screenshots of this repository's `:ui-builder:wasmFrontendDist`, running in its
existing local-storage mode. They show the full production editor, not an HTML mockup or an isolated
inspector preview. The sample is an M3 layout demonstrating the shared state-selection authoring UI;
it does not claim that its Material components already lower to Remote JSON.

The first browser capture (`before.png`) exposed a visibility bug: a Box without click actions could
render its selected child but offered no **Show by state** controls. The full editor had accidentally
nested the selection inspector under the click-action gate. The fix makes those independent.

`after.png` shows the fixed inspector and the Layers dock. The sample also has explicit foreground
colours in this capture, to keep its light canvas legible within the dark editor. These images are
evidence of the real UI, not a pixel-parity comparison of identical editor settings.

`sample.document.json` is the actual authored hierarchy loaded in that browser. Its Box selects the
Preparing child at `deliveryStage = 10`, On-the-way at 20, and Delivered otherwise. The Track delivery
button carries a typed state-write action. Nothing in the authoring tree contains flat Remote Compose
operations.

To open it locally after `./gradlew :ui-builder:wasmFrontendDist`, serve `ui-builder/build/wasmDist`
as an HTTP root. Cache `docs/design/fixtures/ui-builder/m3-catalog-capabilities-v1.json` as a one-element
array under `localStorage["ui-builder.local.catalogs"]`. Store the sample under
`localStorage["ui-builder.local.design.state-layout-shot"]` in a normal local design record:

```json
{
  "schema": "compose-ui-builder-local-design/v1",
  "designId": "state-layout-shot",
  "catalogSystemId": "m3-catalog",
  "seed": "replace this string with the sample document object",
  "seedSequence": 0,
  "log": [],
  "updatedAtEpochMillis": 0
}
```

Open `/index.html?storage=local&designId=state-layout-shot&catalog=m3-catalog&node=delivery-status`
at a 1600 × 1050 viewport and open the Layers dock. Local mode deliberately has no server export or
native preview; the running editor, state reducer and canvas are unchanged.

`StateSelectionInspectorTest` now opens the **full** editor on a Box with no event bindings and
configures its selection through the actual controls. It verifies the committed selection and that
no click action was needed. All three tests in that class pass, and the Wasm app builds successfully.
