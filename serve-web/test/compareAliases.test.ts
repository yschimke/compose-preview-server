// The page's alias table: every preview id written once, and the rule each wall applies to it.
//
// The failure this guards is silent both ways. Resolve too few ids and a `?preview=` deep link from
// the viewer selects nothing, on a page that looks like it simply has no such preview; resolve too
// many and filtering by one variant's id matches every variant of its component — which is the
// exact bug the per-row attribute was fixed for before it was made shared.

import assert from "node:assert/strict";
import { aliasesFor, readAliasTable } from "../src/compare/aliases.js";

const table = () => {
    document.body.innerHTML = `
      <script type="application/json" id="cp-compare-aliases">
      {"cards":{"button":"button-light button-dark button-icon button-long"},
       "rowed":"button-light button-dark"}
      </script>`;
    return readAliasTable(document);
};

describe("the comparison page's alias table", () => {
    it("gives the wall the folded ids, and never one that has its own row", () => {
        // A design reference names one exact state/props mapping, so that variant gets a row of its
        // own — and must not also alias onto its siblings', or filtering by it matches the lot.
        const ids = aliasesFor(table(), "button-light", "button", true);
        assert.deepEqual(ids.sort(), [
            "button-icon",
            "button-light",
            "button-long",
        ]);
        assert.ok(
            !ids.includes("button-dark"),
            "an id with its own row is not an alias here",
        );
    });

    it("gives the lane wall every id on the card", () => {
        // Its rows are one per preview rather than one per mapping, so there is no row to lose a
        // deep link to.
        const ids = aliasesFor(table(), "button-light", "button", false);
        assert.deepEqual(ids.sort(), [
            "button-dark",
            "button-icon",
            "button-light",
            "button-long",
        ]);
    });

    it("leaves a row that claimed no card with its own ids", () => {
        // The fold aliases a card onto exactly ONE row; the rest carry no key.
        assert.deepEqual(
            aliasesFor(table(), "card-light card-dark", null, true),
            ["card-light", "card-dark"],
        );
    });

    it("degrades to the row's own ids when the table cannot be read", () => {
        // The table is an optimisation of a filter, not a source of truth for what exists: a page
        // cached before it was published, or one whose JSON is malformed, narrows slightly less
        // rather than failing to draw.
        document.body.innerHTML = `<script type="application/json" id="cp-compare-aliases">{oops</script>`;
        const broken = readAliasTable(document);
        assert.deepEqual(broken.cards.size, 0);
        assert.deepEqual(aliasesFor(broken, "button-light", "button", true), [
            "button-light",
        ]);
        document.body.innerHTML = "";
        assert.deepEqual(readAliasTable(document).rowed.size, 0);
    });
});
