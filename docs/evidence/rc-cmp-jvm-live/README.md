# Interactive live sessions on `cmp-jvm`

`remote-compose-examples/p/render__material-toggle-button` exists only as a published `.rc`
document. Its `cmp-jvm` lane was the one-shot desktop subprocess (`RcJvmServerRenderer`), which has
no held composition, so `?rcPlayer=cmp-jvm` had no live lane at all: the socket refused the player.

These captures are this branch running locally against the real catalog (delivery branch, deploy's
`producers.json` trust store, `--allow-render-trusted`). The desktop player daemon is
`lib-daemon-desktop/` + `lib-renderer/` staged from compose-preview-daemon PR #255
(`:distribution:stageDaemonDesktopLibs`, `3.15.5-SNAPSHOT`), behind this branch's
`lib-rc-player-desktop/` (`:server:stageRcPlayerDesktopLibs`: rc-players 2.9.0's
`rc-player-compose-jvm` and the Compose Multiplatform 1.12.1 line, 14 MB) plus the
`skiko-awt-runtime-linux-x64-0.150.1` native for that line's `skiko-awt`. That daemon advertises
`remoteCompose.documentBase64.live`, so the lane holds a `stream/start` session playing the document
in the CMP player, and viewer input reaches it.

| file | what it shows |
| --- | --- |
| `live-cmp-jvm-before-tap.png` | Headless Chromium. CMP JVM is picked and Live is on. The hint reads "interactive — click / scroll the preview", and the document shows `STATUS: OFF`. |
| `live-cmp-jvm-after-tap.png` | The same page 2.5 s after a Playwright mouse click on `TOGGLE STATE` in the live canvas: `STATUS: ON`. |
| `socket-cmpjvm-toggle-before-click.png`, `socket-cmpjvm-toggle-after-click.png` | The same exchange straight off the socket (`?rcPlayer=cmp-jvm`): the held session's first frame, then the frame after one `{"type":"input","kind":"click","pixelX":200,"pixelY":293}`. |

Socket log for a cold daemon: `lane input:false` at 0.5 s, a one-shot replay frame at 4.0 s, then
`lane input:true` at 8.6 s once the held session opens. A second socket on the warm daemon reports
`lane input:true` at 0.1 s, and two clicks toggle the document ON and back OFF.

Fallback check: the same server with the released daemon-desktop (3.15.2, from this branch's own
`stageDaemonDesktopLibs`) logs `this daemon-desktop cannot play a carried Remote Compose document
(it predates compose-preview-daemon#255); cmp-jvm stays on its one-shot lane`, starts no desktop
daemon, and the socket refuses `cmp-jvm` exactly as before; the Android players' lane is unchanged.

Memory: the desktop player daemon is one extra JVM, about 320 MB RSS warm and 340 MB holding a
session.
