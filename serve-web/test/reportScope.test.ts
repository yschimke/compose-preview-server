import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import { ReportBody, reportBody } from "../src/report/body.js";
import { scopeFromBody, withScope } from "../src/report/scope.js";
import "../src/components/ReportScope.js";

const locator = [
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

function form(template = locator): HTMLInputElement {
    document.body.innerHTML = `
      <form>
        <cp-report-scope><select>
          <option value="component" selected>This component</option>
          <option value="variant" disabled hidden>This component + variant</option>
        </select></cp-report-scope>
        <input type="hidden" name="body" value="${locator}" data-report-template="${template}">
      </form>`;
    return document.querySelector<HTMLInputElement>('input[name="body"]')!;
}

describe("report locator scope", () => {
    afterEach(() => resetDom());

    it("adds the backward-compatible component default", () => {
        assert.match(
            withScope(locator, "component"),
            /component: Button\/Filled\nscope: component\npreview:/,
        );
    });

    it("reads scope only from a valid locator fence", () => {
        assert.equal(scopeFromBody(withScope(locator, "variant")), "variant");
        assert.equal(scopeFromBody("scope: variant\n" + locator), null);
    });

    it("enables the variant choice only after the body writer attaches", async () => {
        form();
        const variant = document.querySelector<HTMLOptionElement>(
            'option[value="variant"]',
        )!;
        await flush();
        assert.equal(variant.disabled, false);
        assert.equal(variant.hidden, false);
    });

    it("changes the metadata to the exact variant", async () => {
        const field = form();
        await flush();
        const select = document.querySelector("select")!;
        select.value = "variant";
        select.dispatchEvent(new Event("change"));
        assert.match(field.value, /scope: variant/);
        assert.doesNotMatch(field.value, /scope: component/);
    });

    it("survives recomposition by an independently bundled body writer", async () => {
        const field = form(locator + "\n[PNG]({{render}})");
        await flush();
        const select = document.querySelector("select")!;
        select.value = "variant";
        select.dispatchEvent(new Event("change"));
        // Browser entrypoints are separate IIFEs, so a later scorer does not share the scope
        // control's module singleton. Reproduce that boundary with a second writer instance.
        const scorerBody = new ReportBody();
        scorerBody.attach(field);
        scorerBody.set({
            render: "https://preview.example/render/button.png",
        });
        assert.match(field.value, /scope: variant/);
        assert.match(
            field.value,
            /https:\/\/preview\.example\/render\/button\.png/,
        );
    });
});
