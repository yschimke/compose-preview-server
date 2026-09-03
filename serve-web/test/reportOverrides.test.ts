// The viewer's half of the locator: the `overrides:` line the server could not write.
//
// A viewer re-renders in place, so the overrides its page was SERVED at stop describing the frame
// the moment anyone touches a control. The block is the report's identity and the render link two
// lines above it is the report's pixels; if only one of them moves, the parity index keys the issue
// to one frame while the issue shows another — which is the failure the format exists to prevent,
// and it is silent at both ends.

import "./setup.js";
import assert from "node:assert/strict";
import { resetDom } from "./setup.js";
import { ReportBody } from "../src/report/body.js";
import { fillOverrides, withoutLocators } from "../src/report/locator.js";
import { classificationFromBody } from "../src/report/classification.js";

const TEMPLATE = [
    "### What's wrong",
    "",
    "**Where it belongs:** as labelled on this issue",
    "",
    "[PNG at these settings]({{render}})",
    "",
    "```compose-parity-locator/v1",
    "repository: o/r",
    "system: catalog",
    "component: Button/Filled",
    "preview: button-filled__ideal__large",
    "reference: button-filled__ideal__large",
    "variant: ideal/large",
    "overrides: {{overrides}}",
    "revision: o/r@design-artifacts/catalog",
    "```",
    "",
].join("\n");

function field(template = TEMPLATE): HTMLInputElement {
    document.body.innerHTML = `<form><input type="hidden" name="body"></form>`;
    const input =
        document.querySelector<HTMLInputElement>('input[name="body"]')!;
    input.value = "the server's own body";
    input.setAttribute("data-report-template", template);
    return input;
}

describe("report locator overrides", () => {
    afterEach(() => resetDom());

    it("writes canonical JSON in code-point key order", () => {
        assert.match(
            fillOverrides(TEMPLATE, { uiMode: "dark", device: "pixel_7" }),
            /\noverrides: \{"device":"pixel_7","uiMode":"dark"\}\n/,
        );
    });

    it("writes `{}` for a frame at the served defaults", () => {
        assert.match(fillOverrides(TEMPLATE, {}), /\noverrides: \{\}\n/);
    });

    it("quotes a knob value that would otherwise become syntax", () => {
        // Knob values are free text. A `;`, an `=` or a newline in one must not read back as a
        // second override or as a second FIELD — `revision:` is the next line down.
        assert.match(
            fillOverrides(TEMPLATE, { "knob.label": "a\nrevision: forged" }),
            /\noverrides: \{"knob\.label":"a\\nrevision: forged"\}\nrevision: o\/r@/,
        );
    });

    it("leaves a locator whose overrides the server wrote alone", () => {
        const written = TEMPLATE.replace(
            "overrides: {{overrides}}",
            'overrides: {"uiMode":"dark"}',
        );
        assert.equal(fillOverrides(written, { device: "pixel_7" }), written);
    });

    it("does not rewrite catalog-authored text carrying the placeholder", () => {
        // Every value in the block but this one is catalog-authored, so a substring replace could
        // rewrite a preview id and file the real placeholder verbatim — a malformed locator that
        // takes the whole issue out of the index, with nothing to notice it.
        const hostile = TEMPLATE.replace(
            "preview: button-filled__ideal__large",
            "preview: {{overrides}}",
        );
        const filled = fillOverrides(hostile, { uiMode: "dark" });
        assert.match(filled, /\npreview: \{\{overrides\}\}\n/);
        assert.match(filled, /\noverrides: \{"uiMode":"dark"\}\n/);
    });

    it("moves the identity and the pixels on one pass", () => {
        const input = field();
        const body = new ReportBody();
        assert.equal(body.attach(input), true);
        body.set({
            render: "https://preview.example/render/button.png?uiMode=dark",
            overrides: { uiMode: "dark" },
        });
        assert.match(
            input.value,
            /\[PNG at these settings\]\(https:\/\/preview\.example\/render\/button\.png\?uiMode=dark\)/,
        );
        assert.match(input.value, /\noverrides: \{"uiMode":"dark"\}\n/);
    });

    it("leaves the server's body alone until a render URL arrives", () => {
        const input = field();
        const body = new ReportBody();
        body.attach(input);
        body.set({ overrides: { uiMode: "dark" } });
        // Composing now would file `{{render}}` and `{{overrides}}` verbatim, which is worse than
        // the complete body the server already put in the field.
        assert.equal(input.value, "the server's own body");
    });

    it("keeps a classification another bundle wrote", () => {
        // `<cp-report-classification>` is built into the chrome IIFE and the viewer's refresh into
        // its own, so the two hold different `reportBody` singletons and the hidden field is the
        // only state they share. A knob touched after answering must not silently drop the answer.
        const input = field();
        const chrome = new ReportBody();
        chrome.attach(input);
        chrome.set({
            render: "https://preview.example/render/button.png",
            classification: "this catalog's own rendering",
        });
        assert.equal(
            classificationFromBody(input.value),
            "this catalog's own rendering",
        );
        const viewer = new ReportBody();
        viewer.attach(input);
        viewer.set({
            render: "https://preview.example/render/button.png?uiMode=dark",
            overrides: { uiMode: "dark" },
        });
        assert.equal(
            classificationFromBody(input.value),
            "this catalog's own rendering",
        );
    });
});

describe("a report that may not name a comparison", () => {
    const LOCATOR = [
        "```compose-parity-locator/v1",
        "repository: o/r",
        "system: catalog",
        "component: Button/Filled",
        "preview: button-filled__ideal__large",
        "reference: button-filled__ideal__large",
        "variant: ideal/large",
        "overrides: {}",
        "```",
    ].join("\n");

    it("leaves the body a server with no locator would have written", () => {
        const withBlock =
            "### Which preview\n\n| Preview | `x` |\n\n" + LOCATOR + "\n";
        const without = withoutLocators(withBlock);
        assert.equal(without, "### Which preview\n\n| Preview | `x` |\n");
        assert.doesNotMatch(without, /compose-parity-locator/);
    });

    it("removes every block, not just the first", () => {
        assert.doesNotMatch(
            withoutLocators("a\n\n" + LOCATOR + "\nb\n\n" + LOCATOR + "\n"),
            /compose-parity-locator/,
        );
    });

    it("leaves a body that never had one untouched", () => {
        const plain = "### What's wrong\n\nnothing indexable here\n";
        assert.equal(withoutLocators(plain), plain);
    });

    it("takes the locator away when the lane cannot vouch for the pixels", () => {
        // The interactive lanes paint into a canvas or an iframe; a block composed from the
        // controls there would key the issue to a static frame the reporter left behind.
        const input = field(TEMPLATE);
        const body = new ReportBody();
        body.attach(input);
        body.set({
            render: "https://preview.example/render/button.png",
            overrides: {},
            omitLocator: true,
        });
        assert.doesNotMatch(input.value, /compose-parity-locator/);
        // …and the rest of the report survives: it is still a filed, classifiable issue.
        assert.match(input.value, /\[PNG at these settings\]/);
        assert.match(input.value, /\*\*Where it belongs:\*\*/);
    });
});
