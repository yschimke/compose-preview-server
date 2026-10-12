# Live lane for document-only previews, and the renderer menu's colours

`remote-compose-examples/p/androidx-demos__count-down`: a preview that is only a published `.rc`
document, with no Android bundle of its own (`#animation`).

**Before** is the deployed 3.128.0 server at preview.coo.ee. **After** is this branch running
locally. It loads the same catalog from its delivery branch, uses deploy's `producers.json` trust
store and `--allow-render-trusted`, and has the daemon 3.15.3 Android sidecar plus the staged
`lib-rc-players-android/`. The dropdown "after" images are the deployed page with this branch's
`serve.css` swapped in through Playwright's `page.route()`. The popups were captured from headful
Chromium under Xvfb, because a headless screenshot does not include a native `<select>` popup.

| file | what it shows |
| --- | --- |
| `live-before-androidx-view.png` | Before: AndroidX View is picked. The live chip is disabled and the hint reads "static snapshot (no live lane)". |
| `live-after-androidx-view-frame1.png`, `-frame2.png` | After: the same pick, with Live switched on. The lane streams the catalog's document replayed on the server's player daemon, 2.5 s apart; the hue label and discs move. The hint reads "live replay — animates, but input isn't forwarded". |
| `live-stream-frames-androidx-view.png` | Six frames taken off the live socket at roughly 4.5 s intervals. Each one is the document at a later stream time (`captureAdvanceMs`). |
| `dropdown-before-{light,dark}.png` | Before: in dark mode every option is pale on white and looks disabled, although each one works. |
| `dropdown-after-{light,dark}.png` | After: the options have a real surface and foreground in both themes. Only a genuinely unavailable option is dimmed (`CMP Android (unavailable)`, which the deployed server marked disabled at capture time). |
