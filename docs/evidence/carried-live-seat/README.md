# A carried document's held session gets a seat on a busy box

Every PNG is `remote-compose-examples/p/render__material-toggle-button` driven in headless Chromium:
pick the player, press **Live**, wait for the socket's `{"type":"lane","input":true}` (or 60 s), click
**TOGGLE STATE**, screenshot. The server ran from this branch (or `origin/main` for "before") against
the real `yschimke/RemoteCompose-Examples` catalog with preview.coo.ee's flags (`--public
--allow-render-trusted --rc-default-player cmp-android`) and `--live-seats 3`. Three seats with one
carved out for the per-preview slice leave the general lane holding exactly the stream reserve
(two permits) and nothing for background work, which is what preview.coo.ee's `/status.json` showed
at rest: 8 seats, 3 available, the rest held by resident catalogs and spare sandboxes.

| File | Player | What it shows |
| --- | --- | --- |
| `before-cmp-jvm-after-click.png` | CMP JVM | `origin/main`: badge green, "live replay — input isn't forwarded", still `STATUS: OFF` after the click. `/status.json`: desktop player daemon `idle`, `renders: 0`, `lastError: null`. |
| `before-androidx-view-after-click.png` | AndroidX View | `origin/main`: the same, and with no resident catalog to lend a daemon the replay itself cannot draw. |
| `after-cmp-jvm-before-click.png`, `after-cmp-jvm-after-click.png` | CMP JVM | This branch: the lane upgrades to the held session ~4 s after Live; the click turns `OFF` into `ON`. |
| `after-androidx-view-before-click.png`, `after-androidx-view-after-click.png` | AndroidX View | This branch, a reload onto the warm Android daemon: held at once; `OFF` into `ON`. |

The cause: a held session's player daemon was started through `LiveSeatLimiter.acquireBackground`,
which demands the stream reserve stay free *besides* its own weight. On a box whose background
remainder is spent that is refused every time; `openLive` answered `Busy`, the lane kept its replay
and asked again every five seconds, and nothing was logged or reported. The held session is a
viewer's interactive stream, so it now takes a foreground seat (and the socket's own stream seat
when it needs that to fit); the seat is repriced to background, or returned a minute after the last
viewer leaves.
