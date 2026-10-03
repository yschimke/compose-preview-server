# Notifications (Web Push)

The comment board, an agent's `ui_builder_await_comments` and the outbound webhook all reach people
who have the right tab open, or a team channel. Web Push reaches **one person whose tab is closed**:
on Android, on a desktop browser, and on an iPhone or iPad once the site is on the Home Screen. It
was specified in [#1299](https://github.com/yschimke/compose-preview-server/issues/1299).

## What you can be notified about

Each kind is a separate toggle, and all three start on when you first turn notifications on.

| Kind | You are told when… |
| --- | --- |
| **Replies to my comment threads** | somebody replies on a design comment thread you have already written in |
| **@mentions of me** | somebody writes `@your-github-login` in a comment on a design you can read |
| **Reviews and implementations of my designs** | a verdict (approve / send back) is recorded on a design you own, or a pull request is linked as implementing it |

You are never told about your own action, and never about a design you cannot open: every
recipient is checked against the design's own access control when the notification is sent. A
burst of replies on one thread becomes one notification ("3 updates"), and a phone that was offline
receives only the latest one per thread.

Not included: *"an agent is waiting on me"*. The server has no event that says an agent is blocked
on a particular person — an agent waiting for a verdict polls the review record, and an agent access
request can be approved by anybody signed in. An agent that asks you something in a comment thread
is covered by the first two kinds, and its notification says "An agent replied".

## Turning it on

1. Sign in with GitHub. Push is tied to your GitHub identity, so anonymous visitors and browse-token
   or agent-grant callers cannot subscribe.
2. Open **Settings** (⚙ in the header) → **Notifications**, pick the kinds, and press **Turn on
   notifications**. The browser asks for permission then — never on page load.
3. To stop, press **Turn off notifications** in the same place. Changing the kinds applies to every
   browser you have turned notifications on in.

Tapping a notification focuses a window of the site that is already open (matching the manifest's
`launch_handler: focus-existing`) and takes it to the thread, or opens one. The installed app's icon
shows a badge with the number of updates still waiting in the notification tray.

The comment board itself lives in the UI builder's editor, which is built in
[compose-ui-builder](https://github.com/yschimke/compose-ui-builder); a "Notify me about replies"
toggle beside the board belongs there and is a follow-up. Until then, Settings → Notifications on any
server page is the switch.

### iPhone and iPad

Safari exposes Web Push only to a site that was **added to the Home Screen** and opened from there
(iOS / iPadOS 16.4 or later). In an ordinary Safari tab the Notifications group says *Add to Home
Screen first*: tap **Share → Add to Home Screen**, open Compose Preview from the Home Screen, and
turn notifications on there.

### HTTPS only

Browsers allow push only on HTTPS or `localhost`, so a local `serve --lan` on
`http://192.168.x.y` cannot offer it (see [MOBILE.md](MOBILE.md#plain-http-lan-addresses-are-not-a-secure-context));
the group explains that instead of offering the button. In practice push is a hosted-deployment
feature.

## Running a server with push

Push is offered on any server that has a UI builder and GitHub sign-in
(`--github-auth-client-id` …). Nothing is sent until somebody turns it on.

| Flag | Compose env | Default |
| --- | --- | --- |
| `--no-web-push` | `SERVE_WEB_PUSH=0` | push offered |
| `--vapid-subject mailto:…\|https://…` | `SERVE_VAPID_SUBJECT` | the https origin of `--github-auth-callback-base-url`, else the project page |
| `--vapid-public-key` + `--vapid-private-key` (base64url) | `SERVE_VAPID_PUBLIC_KEY` + `SERVE_VAPID_PRIVATE_KEY` | generated on first start |

**VAPID keys.** On first start the server generates a P-256 key pair and keeps it in
`<ui-builder-state>/push/vapid.json` (directory `0700`, file `0600`), beside
`subscriptions.json`. Every subscription a browser holds is bound to the public key it was made
with, so **a new key pair silently strands every subscriber** until they turn notifications on
again. A hosted deployment whose state directory might be rebuilt should pin the pair:

```shell
npx web-push generate-vapid-keys   # any generator that prints base64url P-256 keys works
```

and set both halves (the server refuses halves that are not a pair). The private key is a
credential: whoever holds it can push to every subscriber of the deployment. Keep it in the env
file, never in the compose file or a log.

**Egress.** The server POSTs each notification to the subscribing browser's push service, so the
host needs outbound HTTPS (443) to them — today `fcm.googleapis.com` (Chrome, Edge, Android),
`updates.push.services.mozilla.com` (Firefox), `web.push.apple.com` (Safari) and
`*.notify.windows.com` (some Edge installs). Endpoints are not allow-listed by host, because the
browser chooses its push service; instead an endpoint must be `https` on port 443 with a public
name, it is refused if it names a loopback, private, link-local or `.local` / `.internal` host, and
its addresses are resolved and checked again before every send. That is what stops a subscription
being used to make the server call into its own network.

**Delivery.** Notifications are queued (bounded; the oldest is dropped if the push services fall
behind, and the log says so), retried with backoff on a 5xx or network error, paused for
`Retry-After` on a 429, and the subscription is deleted when the push service answers 404 or 410
(the browser unsubscribed). Each carries `TTL: 86400`, `Urgency: normal` and a `Topic` per design
thread.

## Privacy

- **What is stored:** per browser, the push endpoint and its two keys, your GitHub actor id
  (`github:<login>`), the kinds you chose, when you subscribed and when a push last succeeded.
  Nothing else — not your comments, not which designs you read.
- **Endpoints are secrets.** They are never logged (a log line names a subscription by a short
  digest), never returned by any route — the settings page reads back only your kinds and a device
  count — and never exposed over MCP.
- **What a notification carries:** `{kind, designId, threadId, title, url, count}`, encrypted
  end-to-end to your browser (RFC 8291), so the push service cannot read it. The title names the
  kind of event and the design ("New reply on “Checkout”"); it never contains comment text or who
  wrote it, because it is shown on a lock screen. Open the link to see the rest — it opens only
  for somebody who may read the design.
- **Turning it off** deletes the subscription on the server, unsubscribes the browser and removes
  the push service worker.
- **A shared browser.** A subscription belongs to the browser, not to the session, so it survives
  a change of account. **Signing out** (Settings → Session) turns it off first — the page
  unsubscribes before the sign-out is sent, and the server also drops the subscription this
  browser bound to you, named by a `cp_push_device` cookie (an `HttpOnly` SHA-256 of the
  endpoint, never the endpoint itself) that subscribing set. When somebody else signs in on a
  browser that still holds a subscription, every page load re-posts it, which moves it to them
  before Settings shows notifications as on, so the previous person's notifications stop arriving
  there.

## How it fits together

| Piece | Where |
| --- | --- |
| RFC 8291 encryption + RFC 8292 VAPID, JDK only | `ServeWebPush.kt` (pinned to RFC 8291 §5 in `ServeWebPushTest`) |
| Subscriptions, endpoint validation, VAPID keys | `ServePushSubscriptionStore.kt` |
| Who is told, debounce, sender | `ServePushNotifier.kt`, a subscriber to the comment and review stores' host feeds — the same signals the webhook uses |
| Routes | `ServePushRoutes.kt`: `GET /api/push/key`, `POST`/`DELETE /api/push/subscribe`, `GET`/`PUT /api/push/preferences` |
| Settings group | `ServeWeb.pushNotificationSettings`, enhanced by `serve-web/src/push/settings.ts` |
| Service worker | `/push-sw.js`, scope `/`, from `serve-web/src/push/worker.ts` |

The push worker handles only `push` and `notificationclick`. It has **no `fetch` handler**, so
although its scope is the whole origin it cannot change how any page, API call or socket loads, and
the UI builder's own worker (scope `/ui-builder/`) keeps controlling the editor because the more
specific scope wins. It is registered only when somebody turns notifications on, so the Playwright
lanes — which block service workers, except the sandboxed renderer lane, which serves the renderer's
static dist and never a page that registers one — are unaffected.
