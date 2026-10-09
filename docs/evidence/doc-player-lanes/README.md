# `/d/<id>` server-side player lanes

A shared `.rc` permalink on a host with the CMP/Wasm player and the embedded desktop player
(cmp-jvm), captured from a locally packaged server (`:server:installDist` with the compose-ai-tools
2.35.1 CLI's `lib-rcjvm/` beside it, as the image ships it; `serve --public --accept-docs`) and
rc-players' `ImageBackgroundRemoteButton-454x200.rc` uploaded through `POST /docs`.

| file | what it shows |
| --- | --- |
| `cmp-jvm.png` | **CMP (JVM)** selected: `GET /d/<id>/render.png?rcPlayer=cmp-jvm`, the uploaded bytes drawn by the server's desktop player (454×200, sized from the document header). |
| `cmp-wasm.png` | **CMP (Wasm)** selected, for comparison: the same bytes in the browser. |

Local note: this sandbox's JVM is a Nix build whose loader does not search `/lib/x86_64-linux-gnu`,
and the CLI's `lib-rcjvm/` carries no `skiko-awt-runtime-linux-x64` jar, so the capture ran with
that jar added and Skiko's system libraries on `LD_LIBRARY_PATH`. The image already renders its
catalog cmp-jvm lane, and this lane runs the same renderer.
