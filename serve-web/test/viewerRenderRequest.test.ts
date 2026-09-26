import assert from "node:assert/strict";
import { MAX_GET_URL, renderRequest } from "../src/viewer/renderRequest.js";

describe("renderRequest", () => {
    it("keeps an ordinary render a GET with everything in the query", () => {
        const r = renderRequest(
            "/a2ui-catalog/render/p.png",
            "session=s&knob.label=Hi",
        );
        assert.equal(
            r.url,
            "/a2ui-catalog/render/p.png?session=s&knob.label=Hi",
        );
        assert.equal(r.init.method, undefined);
    });

    it("moves only the overrides into a POST body once the URL is too long", () => {
        const doc = encodeURIComponent("x".repeat(MAX_GET_URL));
        const r = renderRequest(
            "/a2ui-catalog/render/p.png",
            `token=t&session=s&at=abc&fontScale=1.5&knob.document=${doc}&rc.mode=dark`,
        );
        assert.equal(
            r.url,
            "/a2ui-catalog/render/p.png?token=t&session=s&at=abc&fontScale=1.5",
        );
        assert.equal(r.init.method, "POST");
        assert.equal(r.init.body, `knob.document=${doc}&rc.mode=dark`);
        assert.deepEqual(r.init.headers, {
            "Content-Type": "application/x-www-form-urlencoded",
        });
    });
});
