import base from "./ui-builder-editor.config.mjs";

// The evidence capture rides the editor harness's own server and viewport, so a committed render
// is the same pixels the editor spec asserts against rather than a second, drifting setup.
export default {
    ...base,
    testMatch: /capture-evidence\.spec\.mjs/,
    outputDir: "test-results/capture-evidence",
};
