// The report page's half of the installed app's share target: reading the parked share's id off the
// redirect, fetching it with the page's token, and dropping `?shared=` so a reload imports nothing.

import "./setup.js";
import assert from "node:assert/strict";
import {
    sharedId,
    sharedImageUrl,
    withoutShared,
} from "../src/report/shared.js";

describe("a screenshot shared into the installed app", () => {
    const id = "0123456789abcdef0123456789abcdef";

    it("reads only a well-formed share id", () => {
        assert.equal(sharedId(`?shared=${id}`), id);
        assert.equal(sharedId("?shared=../../etc"), null);
        assert.equal(sharedId("?from=/p/x"), null);
    });

    it("fetches the parked image with the page's token", () => {
        assert.equal(
            sharedImageUrl(id, `?shared=${id}&token=a b`),
            `/report-bug/shared/${id}?token=a%20b`,
        );
        assert.equal(sharedImageUrl(id, ""), `/report-bug/shared/${id}`);
    });

    it("drops only the share id from the page URL", () => {
        assert.equal(
            withoutShared(
                `https://preview.example/report-bug?shared=${id}&token=t#x`,
            ),
            "/report-bug?token=t#x",
        );
    });
});
