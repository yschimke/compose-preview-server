// The viewer's Share actions: offered only on a touchscreen with a share sheet, and never sharing
// the operator's token along with the link.

import "./setup.js";
import assert from "node:assert/strict";
import {
    pngFileName,
    shareOffered,
    shareableUrl,
} from "../src/viewer/webShare.js";

describe("the viewer's Share actions", () => {
    it("share the page without its token", () => {
        assert.equal(
            shareableUrl(
                "https://preview.example/m3/p/Button?token=s3cret&theme=dark#x",
            ),
            "https://preview.example/m3/p/Button?theme=dark#x",
        );
    });

    it("name the PNG after the preview", () => {
        assert.equal(
            pngFileName("https://preview.example/m3/p/Button%20filled?x=1"),
            "Button_filled.png",
        );
        assert.equal(pngFileName("https://preview.example/"), "preview.png");
    });

    it("are offered only to a finger with a share sheet", () => {
        const withShare = {
            share: () => Promise.resolve(),
        } as unknown as Navigator;
        const without = {} as Navigator;
        assert.equal(shareOffered(withShare, true), true);
        assert.equal(
            shareOffered(withShare, false),
            false,
            "desktop unchanged",
        );
        assert.equal(shareOffered(without, true), false);
    });
});
