# The cmp-jvm column draws again

Real captures from the deployment and from the released sidecars it runs, not mocks.

`before.png` is Playwright over the live
`preview.coo.ee/remote-m3/compare?format=rc&preview=appcard__ideal__default__compact`, clipped to
the player table. Four players draw; `RC · cmp-jvm player` is a broken image in every row, because
`/remote-m3/render/<preview>.png?rcPlayer=cmp-jvm` answered

    500 cmp-jvm render failed: ExceptionInInitializerError: null

for every document in every catalog on that box.

`after.png` is the same preview drawn by the same player, off the classpath this change stages: the
released `compose-preview-2.4.1` tarball's `lib-rcjvm/` + `lib-daemon-desktop/` — exactly what
`RcJvmServerRenderer` joins — plus `skiko-awt-runtime-linux-x64-0.144.6.jar`, the one file the
image was missing.

| File | |
| --- | --- |
| `before.png` | The live wall. The cmp-jvm cell is the browser's broken-image placeholder; the baked, JS, cmp-wasm and AOSP columns beside it are unaffected, which is what made this read as one broken player rather than as a missing file. |
| `after.png` | `appcard__ideal__default__compact` at 454×400, density 2, rendered by `RcJvmRenderMainKt` with the native on the classpath. Without that jar the same command exits 2 with `ExceptionInInitializerError: null`. |

Reproduce the failure without the image at all:

```
tar -xzf compose-preview-2.4.1.tar.gz --wildcards '*/lib-rcjvm' '*/lib-daemon-desktop'
java -cp 'lib-rcjvm/*:lib-daemon-desktop/*' \
  ee.schimke.composeai.rcembedded.jvm.RcJvmRenderMainKt \
  --input appcard.rc --output out.png --width 454 --height 400 --density 2
```

The cause the null message hides is visible by loading Skiko directly on that classpath:

    org.jetbrains.skiko.LibraryLoadException: Cannot find libskiko-linux-x64.so.sha256,
    proper native dependency missing
