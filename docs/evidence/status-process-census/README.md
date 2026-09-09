# Status page — process census

Evidence for `feat(serve): count this container's subprocesses on /status`.

Both frames are `GET /status` on a local `compose-preview-server --public --port 8099
--build-host none --ui-builder-state-dir none --accept-docs`, captured headless at 1180×700, with
only the commit's own source difference between them.

| | |
| --- | --- |
| `status-before.png` | The stat grid ends `Live seats · Known sessions · Uptime`. Nothing on the page — or in `/status.json`, where the `processes` key is absent entirely — can see a subprocess. |
| `status-after.png` | A `Processes` tile joins the grid, reading `3 live JVMs · 621 total · 1 defunct (chrome)`. |

The `after` frame is the useful one, and not because of the counts: the capture happened to catch a
real unreaped child, so the tile is showing `zombieCommands` doing the job it exists for — **naming
the lane the zombie came from**. That is the fact that was unrecoverable on `preview.coo.ee`, where
2099 `[java] <defunct>` children had accumulated against an unbounded PID budget while `/status`
reported `daemons.running: 18` and `status: ok`.

`pidsCurrent` / `pidsMax` are null in both frames and the tile therefore draws no meter: the capture
runs on the host, not inside a PID-limited cgroup. On the deployed container both are populated and
the tile carries the PID-budget meter.
