// `<cp-bg-toggle>` — the Transparent toggle, shared by the catalog grid and the
// single-preview viewer. Flips the page from its default solid surface to a
// checkerboard for inspecting raw alpha, and persists the choice per visitor.
//
// One `aria-pressed` toggle rather than a two-button pair: two states and a
// default. Light DOM, so `serve.css`'s `.cp-bg-btn` rules apply unchanged; the
// element is `display: contents`, so the rendered button stays the toolbar's
// flex item. The control is inert without JS, so this renders the button rather
// than adopting a server-emitted one.

import { h, type VNode } from "../vue.js";
import { customElement } from "../controllerElement.js";
import { VueElement } from "../vueElement.js";
import {
    isTransparent,
    subscribe,
    toggle,
    wirePopstate,
} from "../backgroundChoice.js";

@customElement("cp-bg-toggle")
export class BgToggle extends VueElement {
    /** Tooltip text; the server varies it per surface. */
    private get label(): string {
        return this.getAttribute("label") ?? "";
    }

    private pressed = isTransparent();

    private unsubscribe?: () => void;

    connectedCallback(): void {
        super.connectedCallback();
        wirePopstate();
        this.pressed = isTransparent();
        this.unsubscribe = subscribe(() => {
            this.pressed = isTransparent();
            this.requestUpdate();
        });
        this.requestUpdate();
    }

    disconnectedCallback(): void {
        this.unsubscribe?.();
        this.unsubscribe = undefined;
        super.disconnectedCallback();
    }

    protected renderVue(): VNode {
        return h(
            "button",
            {
                type: "button",
                class: "cp-bg-btn",
                "aria-pressed": this.pressed ? "true" : "false",
                title: this.label,
                onClick: () => toggle(),
            },
            "Transparent",
        );
    }
}

declare global {
    interface HTMLElementTagNameMap {
        "cp-bg-toggle": BgToggle;
    }
}
