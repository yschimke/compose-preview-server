import "./setup.js";
import assert from "node:assert/strict";
import { flush, resetDom } from "./setup.js";
import { reportBody } from "../src/report/body.js";
import { withScope } from "../src/report/scope.js";
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
          <option value="variant">This component + variant</option>
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

    it("changes the metadata to the exact variant", async () => {
        const field = form();
        await flush();
        const select = document.querySelector("select")!;
        select.value = "variant";
        select.dispatchEvent(new Event("change"));
        assert.match(field.value, /scope: variant/);
        assert.doesNotMatch(field.value, /scope: component/);
    });

    it("survives later body recomposition", async () => {
        const field = form(locator + "\n[PNG]({{render}})");
        await flush();
        const select = document.querySelector("select")!;
        select.value = "variant";
        select.dispatchEvent(new Event("change"));
        reportBody.set({ render: "https://preview.example/render/button.png" });
        assert.match(field.value, /scope: variant/);
        assert.match(
            field.value,
            /https:\/\/preview\.example\/render\/button\.png/,
        );
    });
});
