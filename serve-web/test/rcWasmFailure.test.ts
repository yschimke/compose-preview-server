// The CMP/Wasm lane's failure banner keeps the player's own reason.

import assert from "node:assert/strict";
import {
    RC_WASM_REASON_MAX,
    rcWasmFailureMessage,
} from "../src/viewer/rcWasmFailure.js";

describe("rcWasmFailureMessage", () => {
    it("names what the CMP player could not render", () => {
        assert.equal(
            rcWasmFailureMessage(
                "Document is not renderable by the CMP player: BoxLayout[1]: vertical position 1 is not implemented",
            ),
            "Rendering the Remote Compose document failed in CMP Wasm: Document is not renderable by the CMP player: BoxLayout[1]: vertical position 1 is not implemented",
        );
    });

    it("falls back to the generic sentence when the player gave no reason", () => {
        assert.equal(
            rcWasmFailureMessage("  "),
            "Rendering the Remote Compose document failed in CMP Wasm.",
        );
    });

    it("caps a reason listing many gaps", () => {
        const reason =
            "BoxLayout[3]: vertical position 1 is not implemented, ".repeat(20);
        const shown = rcWasmFailureMessage(reason).split("CMP Wasm: ")[1];
        assert.ok(shown.length <= RC_WASM_REASON_MAX);
        assert.ok(shown.endsWith("…"));
    });
});
