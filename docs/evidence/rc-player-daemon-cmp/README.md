# `cmp-android` on the player-only Android daemon

Document-only previews on `remote-compose-examples` are drawn by the server's own player daemon
(`ServeRcPlayerDaemon`). That daemon offers the players whose libraries `lib-rc-players-android/`
carries. The directory used to hold only the AndroidX players and the embedded player, so the
renderer menu listed `CMP Android (unavailable)`. It now also stages rc-players'
`rc-player-compose`, plus the part of its runtime graph that the Android sidecar lacks.

These captures are this branch running locally. The real catalog is loaded from its delivery
branch, using deploy's `producers.json` and `--allow-render-trusted`. The daemon classpath is the
released daemon 3.15.3 Android sidecar plus this branch's `:server:stageRcPlayersAndroidLibs`. The
server log reads `rc player daemon started (androidx-embedded, androidx-view, cmp-android)`, and
the page's menu now has `<option value="rc:cmp-android">CMP Android`, with no `disabled`.

| file | what it shows |
| --- | --- |
| `render__material-toggle-button-cmp-android.png` | `/remote-compose-examples/render/render__material-toggle-button.png?rcPlayer=cmp-android`: `200` and `X-Compose-Preview-Generation: daemon`. |
| `render__material-toggle-button-androidx-view.png` | The same document with `?rcPlayer=androidx-view`, which wraps the status text differently. |
| `strip-render__material-toggle-button.png` | From left to right: `cmp-android`, `androidx-view`, `androidx-embedded`. |
| `androidx-demos__c-image-cmp-android.png`, `-androidx-view.png` | A document with a bitmap. The CMP player decodes bitmaps through Coil, which is part of the newly staged tail. |

`cmp-android` and `androidx-embedded` produce byte-identical PNGs for the toggle button. A negative
control confirms that the CMP player really drew it. On a second server, `rc-player-compose.jar` was
replaced by an empty `RcComposePlayerKt.class`. The same request then failed with `500 render
failed`, while the real staging returns `200`.

`androidx-demos__count-down` also returns `200` on all three players. It is animated, so its frames
differ by capture time. One document, `androidx-demos__demo-bitmap-drawing-bit-draw1`, is drawn by
both AndroidX players but fails on `cmp-android` with `IllegalStateException: Unknown AndroidX box
alignment horizontal=1 vertical=1`. That error comes from the CMP player, not from the classpath.
