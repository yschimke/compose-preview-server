// The one writer of the prefilled report's hidden `body` input.
//
// Three things reach that field and none of them knows about the others: the render URL (derivable
// the moment the page parses), the browser scorer's measurements (when it finishes, if it does),
// and the element or region the reporter selected (whenever they click, which may be before OR
// after the scorer lands). Each used to be — or would have become — a `body.value = …` of its own,
// and the last one to run would win: filing a report after selecting an element would drop the
// scores, and scoring after selecting would drop the selection. Neither loss shows up anywhere
// except in the filed issue.
//
// So the field has exactly one writer, holding the template and the latest of each input, and every
// producer hands it a fragment instead of a whole body.
//
// The value is written to a hidden INPUT and nowhere else — never to an `href` or any other
// navigation sink. That is what keeps the report a GET form rather than a CodeQL finding; see
// `ServeIssueReport.action`.

import { fillReport, needsRender } from "../annotate/report.js";
import {
    classificationFromBody,
    withClassification,
} from "./classification.js";
import {
    fillLocators,
    fillOverrides,
    fillSelection,
    withoutLocators,
    type Selection,
} from "./locator.js";
import { type ReportScope, scopeFromBody, withScope } from "./scope.js";

/** What the page knows so far. Every field is independently optional. */
export interface ReportInputs {
    /** Token-stripped `/render/<id>.png` at the settings on screen. */
    render?: string;
    /** The scorer's sentence, or null while it has not produced one. */
    scores?: string | null;
    selection?: Selection;
    /**
     * The sentence `<cp-report-classification>` last wrote. Routed through here because the body is
     * recomposed from the template on every update, so a direct field write would be undone.
     */
    classification?: string;
    /** Whether the issue follows the whole component or only the preview variant on screen. */
    scope?: ReportScope;
    /**
     * One `compose-parity-locator/v1` block per comparison ticked on the wall; these turn a
     * page-scoped report into an umbrella issue the index can join to rows. Empty reproduces the
     * server's body.
     */
    locators?: string[];
    /**
     * The render lane's normalised override map as the controls stand now. Set alongside [render]
     * because the viewer re-renders in place and the locator's `overrides:` must describe the same
     * frame. Undefined where the server already wrote the value.
     */
    overrides?: Record<string, string>;
    /**
     * Whether the page may name a comparison right now. An interactive viewer paints into a canvas
     * or iframe the landed-frame gate cannot see, so a locator would key the issue to a stale
     * frame. Defaults to false.
     */
    omitLocator?: boolean;
}

export class ReportBody {
    private input: HTMLInputElement | null = null;
    private template = "";
    private state: ReportInputs = { scores: null, selection: {} };

    /**
     * Take over [input], whose `data-report-template` carries the body's placeholders. Returns
     * false and leaves the field alone when there is no template (the server's body is already
     * complete). Attaching a different field resets learned state, since it is a different
     * comparison.
     */
    attach(input: HTMLInputElement | null): boolean {
        const template = input?.getAttribute("data-report-template");
        if (!input || !template) return false;
        // Re-attaching the same field keeps state: both the comparison and the classification
        // control claim it, and the second would otherwise reset the first.
        if (input === this.input) return true;
        this.input = input;
        this.template = template;
        this.state = { scores: null, selection: {} };
        return true;
    }

    /** Merge in what one producer has learned, and rewrite the field. */
    set(next: ReportInputs): void {
        this.state = { ...this.state, ...next };
        this.write(next.scope);
    }

    private write(preferredScope?: ReportScope): void {
        const { input, template } = this;
        if (!input || !template) return;
        // No render URL yet: keep the server's correct body rather than filing `{{render}}`
        // verbatim. Asked of the template because the wall's page-scoped report names no render at
        // all.
        if (needsRender(template) && !this.state.render) return;
        const composed = withClassification(
            withScope(
                fillLocators(
                    fillSelection(
                        fillOverrides(
                            fillReport(
                                template,
                                this.state.render ?? "",
                                this.state.scores ?? null,
                            ),
                            this.state.overrides ?? {},
                        ),
                        this.state.selection ?? {},
                    ),
                    this.state.locators ?? [],
                ),
                // Each entrypoint is its own IIFE with its own `reportBody`, so the hidden field is
                // the shared state: keep the scope another bundle wrote unless a scope control
                // passes [preferredScope].
                preferredScope ??
                    scopeFromBody(input.value) ??
                    this.state.scope ??
                    "component",
            ),
            // Read back from the field for the same reason; this store's own `set` still wins.
            this.state.classification || classificationFromBody(input.value),
        );
        input.value = this.state.omitLocator
            ? withoutLocators(composed)
            : composed;
    }
}

/**
 * The page's report field: a module singleton because there is one report form per page and its
 * producers are separate custom elements.
 */
export const reportBody = new ReportBody();
