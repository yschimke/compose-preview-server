# Status page — the Processes tile, now under a golden

Evidence for `fix(serve): scope the process census, read cgroup v1, and sample it`.

The tile itself shipped in
[#604](https://github.com/yschimke/compose-preview-server/pull/604); what that change did not do was
put it in a fixture, so `ServeWebFixtureTest` built its summary without a Processes stat and the
committed `serve-status.html` golden never contained one. The layout, the four-clause value and the
warning segment were therefore invisible to the visual harness — a regression in any of them would
have moved no picture.

Both frames are the `.cp-status-grid` of `preview-harness/fixtures/pages/serve-status.html`,
captured headless at 1180 CSS px and 2× through the harness's own static server, so the production
stylesheet is routed in. The only difference between them is this commit's fixture change.

| | |
| --- | --- |
| `summary-before-light.png` / `summary-before-dark.png` | The grid runs `Live seats · Known sessions · Uptime`. No Processes tile — the golden could not see one. |
| `summary-after-light.png` / `summary-after-dark.png` | A `Processes` tile sits beside `Known sessions`, reading `18 live JVMs · 2140 total · 2099 defunct (java) · 2140/4096 pids`, with its PID meter mostly `defunct`. |

The figures are the measured `preview.coo.ee` incident — 2,099 `[java] <defunct>` children against
18 live JVMs — put against a 4096-PID ceiling rather than the unbounded budget that box actually
had. Unbounded draws no meter at all, so the real numbers would have captured strictly less: this
way the golden holds the warning segment, the three-segment split and the longest value the row can
produce.

The other three fixes in this commit are not visual. The site-scoping change is asserted in
`ServeTopLevelSiteTest` against `/status.json` (`"processes":null` on a site host), and the cgroup
v1 fallback and the sampling window in `ServeProcessCensusTest`.
