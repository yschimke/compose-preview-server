// What a design sheet's URL says, and what it restores.
//
// The four controls under a specimen sheet — which drawing is shown, what it is scored against,
// and the two filters — were held in memory alone, so a refresh of `/pages/<name>` put the reader
// back on the code lane with no baseline and no outlines, however deep into a comparison they
// were. These are the rules the address bar now carries, as a table: they are pure, and the part
// that can actually be wrong is which states go unsaid and which stale ones are refused.

import assert from "node:assert/strict";

import { pageParams, pageStateFrom } from "../src/design/lanes.js";

describe("design sheet URL state", () => {
    it("says nothing about the state the sheet opens on", () => {
        // The clean URL a visitor arrived with, not four parameters spelling out the defaults.
        assert.deepEqual(
            pageParams({
                lane: "code",
                baseline: "off",
                outlines: false,
                unlinked: false,
            }),
            { lane: null, baseline: null, outlines: null, unlinked: null },
        );
    });

    it("names every departure from it", () => {
        assert.deepEqual(
            pageParams({
                lane: "parallel",
                baseline: "design",
                outlines: true,
                unlinked: true,
            }),
            {
                lane: "parallel",
                baseline: "design",
                outlines: "1",
                unlinked: "1",
            },
        );
    });

    it("restores the pairing a link names", () => {
        assert.deepEqual(
            pageStateFrom(
                {
                    lane: "parallel",
                    baseline: "design",
                    outlines: null,
                    unlinked: null,
                },
                true,
            ),
            {
                lane: "parallel",
                baseline: "design",
                outlines: false,
                unlinked: false,
            },
        );
    });

    it("refuses a baseline the lane is already showing", () => {
        // "Diff ours against ours" is 0.0% in every slot by construction, so a stale link asking
        // for it opens unscored rather than on a comparison that cannot say anything.
        assert.equal(
            pageStateFrom(
                {
                    lane: "design",
                    baseline: "design",
                    outlines: null,
                    unlinked: null,
                },
                true,
            ).baseline,
            "off",
        );
    });

    it("refuses a sibling baseline on a sheet that carries no sibling renders", () => {
        assert.equal(
            pageStateFrom(
                {
                    lane: "code",
                    baseline: "parallel",
                    outlines: null,
                    unlinked: null,
                },
                false,
            ).baseline,
            "off",
        );
    });

    it("refuses a sibling LANE on that sheet too, and the self-baseline it would license", () => {
        // The other side of the same claim, and the one that used to get through. `laneOf` only
        // parses, so `?lane=parallel` survived onto an unpaired sheet — and `allowsBaseline` then
        // read `code` as legal, because it is legal *opposite `parallel`*. Hydration cannot check a
        // radio the page never rendered, so the sheet settled back on `code` while holding the
        // `code` baseline: every render scored against itself, a wall of `0.0%` from a stale link.
        assert.deepEqual(
            pageStateFrom(
                {
                    lane: "parallel",
                    baseline: "code",
                    outlines: null,
                    unlinked: null,
                },
                false,
            ),
            {
                lane: "code",
                baseline: "off",
                outlines: false,
                unlinked: false,
            },
        );
    });

    it("keeps a baseline the fallback lane still allows", () => {
        // Normalising the lane must not cost the reader a pairing that is still real: `design` is
        // not the lane it falls back to, so it survives the fallback rather than being swept up
        // with it.
        assert.deepEqual(
            pageStateFrom(
                {
                    lane: "parallel",
                    baseline: "design",
                    outlines: null,
                    unlinked: null,
                },
                false,
            ),
            {
                lane: "code",
                baseline: "design",
                outlines: false,
                unlinked: false,
            },
        );
    });

    it("honours the sibling lane on a sheet that DOES carry the renders", () => {
        // The fallback is about the pairing, not about the parameter — a paired sheet still opens
        // where the link says.
        assert.deepEqual(
            pageStateFrom(
                {
                    lane: "parallel",
                    baseline: "code",
                    outlines: null,
                    unlinked: null,
                },
                true,
            ),
            {
                lane: "parallel",
                baseline: "code",
                outlines: false,
                unlinked: false,
            },
        );
    });

    it("turns the outlines on with the filter that needs them", () => {
        // Pressing the unlinked filter does this, so restoring it has to as well — otherwise a
        // Back landed on a filter with nothing visible to filter.
        assert.deepEqual(
            pageStateFrom(
                {
                    lane: "code",
                    baseline: null,
                    outlines: null,
                    unlinked: "1",
                },
                false,
            ),
            {
                lane: "code",
                baseline: "off",
                outlines: true,
                unlinked: true,
            },
        );
    });

    it("falls back to the sheet's own defaults for anything it cannot read", () => {
        assert.deepEqual(
            pageStateFrom(
                {
                    lane: "invented",
                    baseline: "invented",
                    outlines: "yes",
                    unlinked: "yes",
                },
                true,
            ),
            {
                lane: "code",
                baseline: "off",
                outlines: false,
                unlinked: false,
            },
        );
    });

    it("round-trips every state the sheet can be in", () => {
        // The property the address bar needs: what a press writes is what a reload restores.
        for (const lane of ["code", "parallel", "design"] as const) {
            for (const baseline of [
                "off",
                "code",
                "parallel",
                "design",
            ] as const) {
                if (lane === baseline) continue;
                for (const outlines of [false, true]) {
                    const written = pageParams({
                        lane,
                        baseline,
                        outlines,
                        unlinked: false,
                    });
                    assert.deepEqual(
                        pageStateFrom(
                            {
                                lane: written.lane,
                                baseline: written.baseline,
                                outlines: written.outlines,
                                unlinked: written.unlinked,
                            },
                            true,
                        ),
                        { lane, baseline, outlines, unlinked: false },
                    );
                }
            }
        }
    });
});
