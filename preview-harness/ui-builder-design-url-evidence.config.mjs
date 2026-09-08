import base from "./ui-builder-design-url.config.mjs";

// The evidence capture rides the design-URL lane's own viewport and server boot, so a committed
// picture is the same pixels the assertions run against rather than a second, drifting setup.
export default {
    ...base,
    testMatch: /ui-builder-design-url-evidence\.spec\.mjs/,
    outputDir: "test-results/ui-builder-design-url-evidence",
};
