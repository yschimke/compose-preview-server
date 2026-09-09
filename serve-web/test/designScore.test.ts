// What a node's diff badge says, as a table.
//
// Both mistakes pinned here have been made in this code before, and neither looks like a bug on
// screen: one prints a confident green number for a total mismatch, the other prints the same wrong
// number on every node.

import assert from "node:assert/strict";
import { badgeFor, bandFor, roundPercent } from "../src/design/score.js";

describe("badgeFor", () => {
    it("reports DRIFT, inverting the scorer's match", () => {
        // `scoreImages` answers with a match percentage — identical images score 100. Getting the
        // inversion backwards prints "100.0%" in red for a perfect match and green for a total
        // mismatch: a readout that lies rather than one that is merely wrong.
        assert.equal(badgeFor({ percent: 100, geometry: 0 }).text, "0%");
        assert.equal(badgeFor({ percent: 0, geometry: 0 }).text, "100%");
        assert.equal(badgeFor({ percent: 91.4, geometry: 0 }).text, "9%");
    });

    it("keeps the headline as drift alone, never the worse of the two", () => {
        // The first attempt took `max(drift, geometry)` as the number, and every badge on this
        // fixture read 52.4% — the aspect difference wearing the label of a pixel difference. Two
        // measures conflated into one lie.
        const badge = badgeFor({ percent: 99, geometry: 52.4 });
        assert.equal(badge.text, "1% ⇲", "the number is the drift");
        assert.equal(badge.value, 52.4, "the BAND still sees the geometry");
        assert.equal(badge.band, "far");
    });

    it("marks a render that is the wrong SHAPE", () => {
        // Proportion difference is held out of the match number by the scorer, which normalises both
        // boxes onto one size first. Unmarked, a component rendered at the wrong aspect reads as a
        // near-perfect match.
        assert.ok(
            badgeFor({ percent: 100, geometry: 2.01 }).text.endsWith("⇲"),
        );
        assert.ok(!badgeFor({ percent: 100, geometry: 2 }).text.endsWith("⇲"));
    });

    it("names the geometry in the tooltip once it is more than dust", () => {
        assert.equal(
            badgeFor({ percent: 95, geometry: 3.24 }).title,
            "5.0% different · 3.2% proportion difference",
        );
        assert.equal(
            badgeFor({ percent: 95, geometry: 0.05 }).title,
            "5.0% different",
            "0.05 rounds to 0.1% and would read as a finding",
        );
    });

    it("survives a lane that reports no geometry at all", () => {
        const badge = badgeFor({ percent: 98 });
        assert.equal(badge.text, "2%");
        assert.equal(badge.title, "2.0% different");
        assert.equal(badge.band, "drifting");
    });

    it("never reports a negative difference", () => {
        // Floating-point dust just over 100, which would otherwise print "-0.0%".
        assert.equal(
            badgeFor({ percent: 100.0001, geometry: -0.2 }).text,
            "0%",
        );
    });
});

describe("bandFor", () => {
    it("triages into three, on the boundaries it claims", () => {
        // A decision, not a measurement — so the edges are worth pinning.
        assert.equal(bandFor(0), "close");
        assert.equal(bandFor(1.99), "close");
        assert.equal(bandFor(2), "drifting");
        assert.equal(bandFor(9.99), "drifting");
        assert.equal(bandFor(10), "far");
        assert.equal(bandFor(100), "far");
    });
});

describe("roundPercent", () => {
    it("writes a whole percent on the badge, and the tenths in the tooltip", () => {
        // The badge sits in a node's corner on a drawing being judged. `22.9%` spends four glyphs
        // to say what `23%` says in three, and the tenth is never what decides whether a node is
        // worth opening — the band it is drawn in has already answered that.
        assert.equal(roundPercent(22.9), "23%");
        assert.equal(roundPercent(22.4), "22%");
        assert.equal(badgeFor({ percent: 77.1, geometry: 0 }).text, "23%");
        assert.equal(
            badgeFor({ percent: 77.1, geometry: 0 }).title,
            "22.9% different",
            "the precision is still there, one hover away",
        );
    });

    it("never rounds a real difference away to nothing", () => {
        // `0%` on a node that differs is the one reading this badge must not give: the lane exists
        // to mark what is apart, and reporting a difference as none is worse than no badge at all.
        assert.equal(roundPercent(0.4), "<1%");
        assert.equal(roundPercent(0.001), "<1%");
        // An exact match is the one case that gets to say zero.
        assert.equal(roundPercent(0), "0%");
        assert.equal(roundPercent(-0.2), "0%");
    });
});
