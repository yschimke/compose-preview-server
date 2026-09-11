# Fill-sized state content in the existing WASM canvas

Both screenshots show the same saved revision of
[the export sample](../ui-builder-document-exports/sample.document.json), served by the local
installed server. The viewport is 1600 × 1050 at device scale 1; the editor fits the 360 × 360 dp
design at 240%. These are unedited Chrome screenshots of the existing application.

| Capture | Selected child | Result |
| --- | --- | --- |
| `before.png` / `before.json` | 0 × 0 px | The root retained only its 48 dp vertical padding. |
| `after.png` / `after.json` | 748.797 × 748.797 px | 312 × 312 dp inside the authored 24 dp padding. |

The editor measures natural content height, then lays out the same composition with a finite height
of at least the frame. Document and interaction-state changes invalidate that measurement, so a
shorter selected branch can shrink the extent again. Long content remains editable; the companion
device pane keeps its normal bounded rendering.

`CanvasFillLayoutTest` exercises this exact saved sample, non-integral design density, a saved state
change from tall to short content, and preview clicks that unroll a 20-item list then return to the
fill-sized branch without changing the document revision. The full UI-builder JVM suite passes
937 tests, including the existing extent and density checks. WASM compilation and required golden
regeneration pass; no committed goldens change.

The older `pinned editor canvas preserves clean 1280x800 geometry and pixels` browser test times out
at its 800 dp readiness condition. An isolated build of pre-fix commit `aafca922` also measures 849 dp,
with the same frame bounds as the fixed build. `jetcaster-comparison.json` records both measurements.
This is an existing mismatch between that test and the expanded-content canvas, not a passing
pixel-parity check. The accompanying Jetcaster screenshots show the expected fill correction:
the main background and bottom-aligned navigation now occupy the full measured extent. The readonly
device pane is unchanged. The obsolete browser expectation still needs a separate harness update.

To repeat the browser measurement against a server containing this sample:

```sh
npm ci --prefix preview-harness
node preview-harness/capture-canvas-fill.mjs after '<saved-design-url>'
```

The URL includes the selected `choice` node and the local actor's authentication when required.
Set `CHROME_PATH` to use an installed Chrome executable. The script waits for completed inspection,
asserts the selected child's dimensions, refuses browser errors, and records the screenshot and
inspection data. Use `before` against the pre-fix build to assert the zero-height failure.

This proves canvas layout fidelity for the sample. Live playback of exported JSON/binary documents
is a separate integration step.
