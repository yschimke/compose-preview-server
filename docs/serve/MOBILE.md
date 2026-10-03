# Compose Preview on a phone

The server's pages work in a phone browser, and the site installs as an app. This page says what
that gets you, how to reach a server running on your laptop from a phone, and which features need
a secure context.

## Install

Every page links a web app manifest (`/manifest.webmanifest`), so Chrome, Edge and Samsung Internet
offer **Install app** (Android: menu → *Add to Home screen* / *Install app*; desktop: the install
icon in the address bar). Safari on iOS uses *Share → Add to Home Screen*.

Installed, it opens in its own window from the launcher. A second launch focuses the window that is
already open. Long-press the icon for the UI builder and *My designs* shortcuts, when the server
hosts a UI builder.

Each top-level site (`--sites m3.example.com=m3-catalog`) serves its own manifest, named after its
catalog and coloured by its palette, so each site installs as a separate app.

## Share to Compose Preview

Once installed on Android, **Compose Preview** appears in the system share sheet:

| You share | What happens |
| --- | --- |
| A screenshot (PNG, JPEG or WebP) | The bug report opens with the image attached as a capture, the same as one taken with the page's own capture tool. The image is held on the server for ten minutes at most and is only ever read back by the report page. |
| A link to a page on this server | That page opens. |
| Any other link or text | The bug report opens with the text in its *What went wrong* section. |

A token-gated server accepts the share only from a browser that has already opened it with the
token, since that browser holds the browse cookie. Shares are capped at 10 MB.

## Phones, notches and touch

The page uses the whole screen, including the area under a notch or the gesture bar, and pads its
header, floating button and bottom sheets so nothing sits underneath them. On a touchscreen, buttons
and list rows are at least 44px tall. The hints that used to appear only on hover now also show
on touch. The catalog's "hold for live" hint appears while you press a card.

In the viewer on a phone, the export bar adds **Share link** and **Share PNG** next to *Copy link*
and *Copy PNG*, so a preview goes straight to the system share sheet. A shared link never includes
the server token. Desktop browsers keep the Copy buttons only.

While a live preview is streaming, or a motion capture is playing, the screen stays on. It turns off
normally once the stream or the playback stops, or when you switch away.

## Notifications

Signed in with GitHub on a server that offers them, **Settings → Notifications** turns on Web Push
for this browser: a reply on a comment thread you are in, an `@mention`, or a review of a design you
own reaches the phone with the tab closed, and tapping it opens the thread. On an iPhone or iPad,
add the site to the Home Screen first and turn notifications on from the installed app; Safari
offers push only there. Push needs HTTPS. Setup, the deployment's key and egress requirements, and
what is stored are in [NOTIFICATIONS.md](NOTIFICATIONS.md).

## Open a laptop's server on a phone (`--lan`)

```shell
compose-preview serve --lan
```

`--lan` binds every interface. In an interactive terminal, startup prints each network URL and a
QR code for the first one. Scan it with the phone's camera. The operator also sees the URLs and the
QR code on the landing page, in an **Open on your phone** card. The card appears only in a browser on
the server's own machine that holds the token. It never appears through a reverse proxy.

The token in the URL is the only thing protecting the server, and anyone on the network who has
the URL can use it. Share the URL or the QR code only with people who should see the previews.

### Plain-http LAN addresses are not a secure context

A browser treats `http://192.168.x.y:8723` as an insecure origin. These features need a secure
context, so they are unavailable on a plain-http LAN address:

- installing the app, and the share target that comes with it
- Web Share (*Share link* / *Share PNG*)
- clipboard writes (*Copy PNG*; *Copy link* falls back to selecting the text)
- screen wake lock
- push notifications
- any offline support

Plain browsing, the catalog, the viewer and live previews all work over http.

Two ways to get a secure context:

- **Android over USB:** forward the port, then open `localhost` on the phone. Browsers treat
  `localhost` as secure even over http:

  ```shell
  adb reverse tcp:8723 tcp:8723
  # then open http://localhost:8723/?token=… on the phone
  ```

  8723 is the default port. Use the port that startup prints if it is different.

- **HTTPS:** put the server behind a TLS-terminating reverse proxy, such as Caddy (see
  [`deploy/image/Caddyfile`](../../deploy/image/Caddyfile)), and use the HTTPS URL.

## What works offline

At the moment, nothing is cached for offline use. The catalog, viewer and report pages are served
live from the server, and they need a connection.

The UI builder adds an offline shell from the compose-ui-builder release that ships
`ui-builder-sw.js`. The server already hosts that worker at `/ui-builder/ui-builder-sw.js` with
`Service-Worker-Allowed: /ui-builder/`, so its scope is limited to the editor. The pinned release
does not include the worker yet, and that URL returns 404 until the pin moves. Even with the worker,
designs, exports and live previews still need the server. A cached editor can open without a
connection, but it cannot load or save a design.
