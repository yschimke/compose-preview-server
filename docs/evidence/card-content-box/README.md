# A card's content is a box, in every lane

A 360×240dp frame, light theme, rendered by `UiBuilderSurface` on the JVM. One `m3/card`
(`fillMaxWidth`, `height 180`) holding three children: a `shape/linear-gradient` with
`matchParentSize`, a "+ Follow" text aligned `topStart`, and a title aligned `bottomStart` — the
shape of the Jetcaster podcast cards.

| the card, as the canvas draws it and as the export now composes it | the same children stacked in a column — the structure the record-driven export used to write |
| --- | --- |
| ![box](card-box.png) | ![column re-enactment](card-column-reenactment.png) |

**Left:** what the canvas has always drawn, what the code pane has always written
(`Card { Box { … } }`), and — with this change — what the record-driven projection writes for the
native render too (`ScreenDocumentProjection.cardContentBox`). The three children overlap in a
`Box` sized by `cardContentFill`, the same rule that stops a card with no height from filling its
column (#483).

**Right:** a canvas re-enactment of the old projection's structure, which composed a card's
children straight under `Card`'s own `ColumnScope`. The children stack top to bottom, and a
`matchParentSize` backdrop was not a picture at all: the projection refused it as out of scope, so
the design did not export. The native render — the lane whose whole claim is fidelity — was the one
drawing a different card from the two it exists to check.

The generated Kotlin for the export fixture's card, before and after, is in the pull request; the
Jetcaster fixture's four cards are unchanged on the canvas because the canvas was already right.
