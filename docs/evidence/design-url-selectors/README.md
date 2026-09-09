# Design URL selectors — before and after

Captured by `preview-harness/ui-builder-design-url-evidence.spec.mjs` against a real server, on the
checked-in Jetcaster Discover fixture with one comment thread pinned to `discover-grid`. Both halves
came from the same spec and the same viewport, run twice around the change:

```shell
./gradlew :ui-builder:wasmFrontendDist :server:installDist
EVIDENCE_SUFFIX=before npm --prefix preview-harness run harness:ui-builder-design-url-evidence
EVIDENCE_SUFFIX=after  npm --prefix preview-harness run harness:ui-builder-design-url-evidence
```

| File | URL opened | Before | After |
| --- | --- | --- | --- |
| `node.*.png` | `?node=discover-grid` | The design opens on its root `Surface`, every dock shut | The grid is selected on the canvas and Properties is open on it |
| `thread.*.png` | `#thread=<id>` | Same page again — the fragment reached nothing | Talk is open, the thread expanded, with Copy link on it |
| `revision.*.png` | `?revision=1` | Same page again — the query was ignored | A read-only banner naming revision 1, with Go to latest |
| `layer-menu.*.png` | the selected layer's menu | Properties, then the clipboard verbs | Copy link sits under Properties |

**`node.before.png`, `thread.before.png` and `revision.before.png` are the same bytes, and that is
the finding rather than a mistake in the capture.** All three selectors were ignored before this
change, so all three URLs opened one page: the design, and nothing about where to look in it.
