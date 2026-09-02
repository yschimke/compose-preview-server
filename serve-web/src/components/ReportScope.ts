import { ControllerElement, customElement } from "../controllerElement.js";
import { whenParsed } from "../dom/whenParsed.js";
import { reportBody } from "../report/body.js";
import { type ReportScope, withScope } from "../report/scope.js";

@customElement("cp-report-scope")
export class ReportScopeControl extends ControllerElement {
    private select: HTMLSelectElement | null = null;
    private field: HTMLInputElement | null = null;

    connectedCallback(): void {
        super.connectedCallback();
        if (!this.install()) void whenParsed().then(() => this.install());
    }

    private install(): boolean {
        if (!this.isConnected) return false;
        if (this.select) return true;
        const select = this.querySelector("select");
        const field =
            this.closest("form")?.querySelector<HTMLInputElement>(
                'input[name="body"]',
            ) ?? null;
        if (!select || !field) return false;
        this.select = select;
        this.field = field;
        // The server keeps the script-dependent choice unavailable. Without this successful
        // attachment the hidden body cannot be rewritten, so exposing the option would let a
        // reporter select variant scope while silently submitting component scope.
        const variant = select.querySelector<HTMLOptionElement>(
            'option[value="variant"]',
        );
        if (variant) {
            variant.disabled = false;
            variant.hidden = false;
        }
        this.listen(select, "change", () => this.apply());
        this.apply();
        return true;
    }

    override disconnectedCallback(): void {
        this.select = null;
        this.field = null;
        super.disconnectedCallback();
    }

    private apply(): void {
        const { select, field } = this;
        if (!select || !field) return;
        const scope: ReportScope =
            select.value === "variant" ? "variant" : "component";
        if (reportBody.attach(field)) reportBody.set({ scope });
        field.value = withScope(field.value, scope);
    }
}
