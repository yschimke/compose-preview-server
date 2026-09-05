# UI builder — slider and progress indicator

`slider-progress.after.png` is `CatalogSliderProgressPreview`, built by the reducer: a slider as
inserted, a linear progress indicator as inserted, the same component with `variant` set to
`circular`, and an indeterminate one.

Every value in it is the design's own. The slider sits at the `0.5` its starter content seeds and the
two determinate indicators show `0.6` — a slider at zero is a track with the thumb jammed against
the left end and an indicator at zero is an empty line, and both read as broken rather than as new.

## The last cell, and why it is faint

The indeterminate indicator is drawn at its **first frame**. The document environment freezes
animation — that is what makes a render of a moving thing diffable — so what you get is an honest
still. Material's linear indeterminate form has not moved its bar at frame zero, which draws as an
empty track and reads as a broken component, so the cell uses the circular form, whose first frame is
a short arc. The distinction is real either way: an indeterminate indicator is a second Material
overload, the one you call *without* a progress lambda, and `SliderAndProgressTest` asserts the
export picks that call rather than passing a value.

There is no before image; neither component existed in the catalog.
