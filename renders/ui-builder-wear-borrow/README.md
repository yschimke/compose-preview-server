# UI builder — the wear catalog borrows foundation only

`wear-screen.after.png` is `WearScreenSamplePreview` on this change: the Wear screen sample, whose
rows are now `wear-m3/card`, `wear-m3/text` and `wear-m3/button` rather than the mobile `m3/*` ids
the catalog used to borrow.

**The same render on `main` is byte-identical** — same md5 — which is the point. The canvas has no
Wear Compose to draw with (`androidx.wear.compose:compose-material3` is an Android AAR the Wasm build
cannot link), so it draws the same Material 3 lookalikes it always did. What changed is what the
design *says* it holds, and what the generated Kotlin has always written: `TitleCard`, `Text` and
`Button` from `androidx.wear.compose.material3`.

That is the whole argument for the rename. **Material 3 and Wear Material 3 are not used together** —
different libraries, theme systems, sizes and colour roles — so a Wear design holding a component
named `m3/card` claimed something no watch screen can mean, while the exporter quietly translated it.
The export was right and the palette was lying.

What may still be borrowed is foundation: `layout/box`, `layout/column`, `layout/row` and
`asset/image` are `androidx.compose.foundation` and `androidx.compose.ui`, one declaration shared by
both platforms, so borrowing one claims nothing about Material at all. `WearM3ScreenCatalogTest` pins
that list.
