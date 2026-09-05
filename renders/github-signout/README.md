# Signing out of a GitHub-gated box

Committed evidence for the **Session** group in the header's Settings menu
([`ServeWeb.githubSessionSettings`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeWeb.kt)).

Before this change the header named who you were and offered nothing to do about it. Nothing
anywhere cleared `cp_gh_auth`, so ending a session meant deleting the cookie by hand in DevTools,
and re-authenticating — the only thing that refreshes the access bits the cookie cached at
sign-in — meant knowing to visit `/auth/github/start` directly
([#280](https://github.com/yschimke/compose-preview-server/issues/280)).

| file | what it is |
| --- | --- |
| `settings-menu.before.light.png` | the Settings menu as it shipped: display and keyboard preferences, and nothing about the session |
| `settings-menu.after.light.png` | the same menu with the **Session** group — who is signed in, **Sign out**, and **Switch account** |
| `settings-menu.after.dark.png` | the same menu in the dark scheme |

All three are headless-Chromium captures of the committed harness fixture
`preview-harness/fixtures/pages/serve-home-index.html`, which `ServeWebFixtureTest` generates from
the real page function — so every future change to the menu moves this evidence along with the
goldens. The fixture's `GitHubAuthStatus` now carries a `logoutHref`, which is what puts the group
in the shot; the `before` capture is that fixture at `main`. The menu is opened before the shutter
because it is a `<details>`, which the page fixture serves closed.

The exits live here rather than in the header bar because they are standing per-visitor state,
which is what this menu is for, and because a bar already carrying a mode switch, Status, an
identity and Settings does not need two more chips to say what a visitor does roughly once.

**Sign out** is a one-button `POST` form and there is no `GET` route behind it: a sign-out a
prefetcher or a link-unfurler can fire by looking at a URL is not one. See
[`ServeGithubAuth.handleLogout`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeGithubAuth.kt).
