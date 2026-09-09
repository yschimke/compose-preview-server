// What a Back/Forward restore still has to DO, once `hydrateFromUrl` has put the entry on the
// controls.
//
// The viewer's pop handler ends in `onControlsChanged()`, which exists to push the restored
// overrides at whatever transport is up. That is the right answer for a device, a knob or a
// locale — and the wrong one for the axes hydration finishes by itself, because
// `onControlsChanged` is not a no-op when there is nothing to re-render: on a fully static
// published catalog whose only interactive tier is the in-browser app, its last resort is
// `setMode("wasm")`. Undoing Fit width would then leave the lane, mount the Wasm app and push a
// history entry of its own — a Back that navigates FORWARD, out of a parameter that never
// described a render in the first place.

import { ownsUrlParam } from "./ownedParams.js";

/**
 * The parameters a restore applies in full, inside `hydrateFromUrl`, with no request and no
 * message to a transport.
 *
 * Each one names a *presentation* of pixels that already exist rather than a property of the
 * render that produced them: `zoom` sizes the stage in CSS, `specSource` re-enters the spec lane
 * on the restored pair (`pickSpecSource` owns that request), and `specView` hands the restored
 * view to the comparison element. Nothing else belongs here. An axis that changes what the server
 * would draw — a knob, a device, the theme — must keep going through the render controls, and the
 * `svg` / `exploded` pair is answered one branch earlier by the format check.
 */
export const RESTORED_IN_PLACE: readonly string[] = [
    "zoom",
    "specSource",
    "specView",
];

/** Every parameter whose value differs between two query strings. */
function movedParams(before: string, after: string): string[] {
    const a = new URLSearchParams(before);
    const b = new URLSearchParams(after);
    const names = new Set([...a.keys(), ...b.keys()]);
    // `getAll`, not `get`: an author knob is named by the preview and nothing stops a page from
    // carrying two of them, and comparing only the first would call a real change no change.
    //
    // Compared element by element rather than joined into one string. Any join has a delimiter, and
    // a delimiter that can appear INSIDE a decoded value is not injective: `?knob.tag=a%26%3Db`
    // decodes to the single value `a&=b`, which serialises identically to the two values `a` and
    // `b`. Two entries that differ would then compare equal, and the restore between them would be
    // skipped — the failure this whole function exists to avoid, arriving through its own
    // comparison.
    //
    // Length first, which is also what separates "absent" from "present and empty": `?focus` and
    // `?gestures` are read back as presence (`q.get(f) !== null`), so turning one off has to count
    // as a change even though neither entry carries a value.
    const same = (name: string) => {
        const left = a.getAll(name);
        const right = b.getAll(name);
        return (
            left.length === right.length &&
            left.every((value, at) => value === right[at])
        );
    };
    return [...names].filter((name) => !same(name));
}

/**
 * Whether the restore from [before] to [after] is one hydration has already completed.
 *
 * Scoped to the parameters the viewer owns, because those are the only ones the render controls
 * read: a `token`, an `inspect` layer or an `annotate` selection moving across the entry says
 * nothing about what the stage should draw, and dispatching the controls for it would reach the
 * same dead fallback for the same non-reason.
 *
 * An entry identical to the one on screen answers true — there is nothing left to apply — which is
 * the degenerate case of the same rule rather than a special one.
 */
export function restoredInPlace(before: string, after: string): boolean {
    return movedParams(before, after)
        .filter(ownsUrlParam)
        .every((name) => RESTORED_IN_PLACE.includes(name));
}
