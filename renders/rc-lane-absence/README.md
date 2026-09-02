# Remote Compose player wall — absent lanes

Committed evidence for the compare page's **Remote Compose players** wall
(`/{system}/compare?format=rc`) when a catalog's parity run covered only *some* of the players.

The wall draws one column per lane the published `rc-compare-summary.json` recorded a verdict for,
so a catalog that opted into the CMP/Wasm lane and not the two Android embedded lanes or the
desktop one gets three columns and no explanation — which reads as a page that lost its players
rather than as a run that never included them
([compose-ai-tools#4998](https://github.com/yschimke/compose-ai-tools/issues/4998) asked exactly
that of the live `remote-m3` wall). The page now names them.

The wall is [`ServeWeb.rcLanesSection`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt);
the lane vocabulary it subtracts the published lanes from is
[`ServeRcCompare.LANES`](../../render-host/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeRcCompare.kt).

| file | what it is |
| --- | --- |
| `rc-lanes-partial.before.light.png` | the three-column wall as it shipped: nothing says the other three players exist |
| `rc-lanes-partial.after.light.png` | the same wall naming the players the run did not include |
| `rc-lanes-partial.after.dark.png` | the same page in the dark scheme |

All three are headless-Chromium captures of the committed harness fixture
`preview-harness/fixtures/pages/serve-rc-lanes-partial.html`, which `ServeWebFixtureTest` generates
from the real page function — so a change to the page moves this evidence along with the golden.
The `before` capture is that same fixture with the new paragraph stripped, which is the only
rendered difference.
