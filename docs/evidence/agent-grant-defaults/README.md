# Agent access grants: the requested capability opens ticked

`GET /agent-access/{requestId}` on a box that offers the `images` capability, rendered from the
committed golden `preview-harness/fixtures/pages/serve-agent-access-capabilities.html` at 900 CSS px
with the server's own `serve.css`.

| | |
| --- | --- |
| `grant-page-before.png` | "Anything else the agent may do" opens **unticked** — the approver had to re-enter the agent's own ask before pressing Approve. |
| `grant-page-after.png` | The same row opens **ticked**, like the scope radio already opened on the highest offered rung. |

A row only appears here once the request has been narrowed by the approver's ceiling and the box's
(`ServeAgentGrants.selectableCapabilities`), so every box on the page is something the agent asked
for and this approver may give. Unticking one is still one click, and an approval with the row
unticked still confers nothing — see [`docs/design/AGENT_ACCESS_GRANTS.md`](../../design/AGENT_ACCESS_GRANTS.md).
