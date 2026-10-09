# `/d/<id>` player toggle

A shared `.rc` permalink on a host serving the CMP/Wasm player (`--rc-player-wasm-dir`), captured
from a locally packaged server (`:server:installDist`, `serve --public --accept-docs`) with
rc-players' `ImageBackgroundRemoteButton-454x200.rc` fixture uploaded through `POST /docs`.

| file | what it shows |
| --- | --- |
| `typescript.png` | The default, **TypeScript** selected: the page as it always was. The vendored bundle stops at opcode 171 (`Unknown operation opcode: 171, skipping rest of buffer` in the console) and paints nothing — a pre-existing gap in that bundle, not this change. |
| `cmp-wasm.png` | **CMP (Wasm)** selected: the same `/d/<id>/raw` bytes played by the Compose Multiplatform player in its frame. The URL now carries `?rcPlayer=cmp-wasm`, and a reload opens on it. |
