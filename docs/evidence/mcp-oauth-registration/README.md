# OAuth registration recovery notice

`before.png` and `after.png` show the production `ServeWeb.agentGrantNoticePage`
renderer in Chromium (1100 × 720, dark mode). Before uses the error text from
base commit `b3391c5d`; after uses `ServeMcpOAuth.validateAuthorize`'s compiled
unknown-client rejection. The page renderer and layout are unchanged. These
captures verify the recovery instructions, not a live host OAuth exchange.

The HTTP regression test registers a client, stops that server, starts another
with the same registry file, and authorizes with the cached ID. It also checks
that an unregistered redirect still receives a local error without a redirect.
Persistence tests separately cover session-state loss, idle expiry/renewal,
shared-directory registrations, capacity, corrupt state and file permissions.

The existing deployment keeps `/config` on its `preview_config` volume, so the
runner's `/config/mcp-oauth/clients.json` survives container replacements.
Previously lost registrations cannot be reconstructed: disconnect/remove and
re-add the app once to register again. Approval and token state stays ephemeral.
