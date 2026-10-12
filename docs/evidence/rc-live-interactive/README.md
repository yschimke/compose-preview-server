# Interactive live sessions for document-only previews

`remote-compose-examples/p/render__material-toggle-button` and `render__touchprobe` are previews
that exist only as a published `.rc` document. Their Android player lanes used to stream a replay
of that document (#1608), which animates but takes no input.

These captures are this branch running locally against the real catalog: the catalog is loaded from
its delivery branch, with deploy's `producers.json` trust store and `--allow-render-trusted`. The
player daemon is the Android sidecar built from compose-preview-daemon PR #254 (commit `091a2b6`,
`:distribution:stageDaemonAndroidLibs`, which reports `3.15.4-SNAPSHOT`), plus this branch's staged
`lib-rc-players-android/`. That daemon advertises `remoteCompose.documentBase64.live`, so the lane
holds a `stream/start` session that carries the document, and viewer input reaches it.

| file | what it shows |
| --- | --- |
| `live-androidx-view-before-tap.png` | Headless Chromium. AndroidX View is picked and Live is on. The hint reads "interactive — click / scroll the preview", and the document shows `STATUS: OFF`. |
| `live-androidx-view-after-tap.png` | The same page 2.5 s after a Playwright mouse click on `TOGGLE STATE` in the live canvas. The held session's next frames show `STATUS: ON`. |
| `socket-toggle-before-click.png`, `socket-toggle-after-click.png` | The same exchange, taken straight off the live socket: the first frame, then the frame after one `{"type":"input","kind":"click","pixelX":200,"pixelY":293}`. |
| `socket-touchprobe-strip.png` | `render__touchprobe` on the socket: the first frame, then three frames after a pointer down/up at (200, 250). The document's own `touchX` / `touchY` read `200.0` / `250.0`, so the input arrived in document pixels. Its `animationTime` keeps advancing on the session's frame clock. |

Socket log for a cold daemon (`render__counter`): `lane input:false` at 0.2 s, while the replay
warms the daemon, then `lane input:true` at 19.3 s once the held session opens, then frames.

Fallback check: the same server with the released daemon 3.15.3 sidecar, which does not advertise
the token. The lane reported `input:false` throughout, painted the replay, and a click changed
nothing. The server log shows no `stream/start` was sent to that daemon.

The player daemon now boots two sandboxes, because a held session needs a second one. With the
3.15.3 sidecar warm, its two JVMs measured about 376 MB and 639 MB RSS.
