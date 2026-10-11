// The floating "Report a problem" launcher, which states up front whether a problem belongs to the
// preview server's tracker or the catalog's own repository, and names each destination. In the
// shell bundle because it is on every page; the capture machinery is a separate bundle fetched on
// first use.

import { whenReady } from "../dom/whenReady.js";

/** Load the capture bundle named by a `data-cp-capture-src` on the page, at most once. */
function loadCapture(): void {
    const host = document.querySelector<HTMLElement>("[data-cp-capture-src]");
    const src = host?.getAttribute("data-cp-capture-src");
    if (!src || document.querySelector(`script[data-cp-capture]`)) return;
    const script = document.createElement("script");
    // Resolved against the page's origin and refused otherwise, rather than trusting DOM text.
    const url = new URL(src, location.href);
    if (url.origin !== location.origin) return;
    script.src = url.href;
    script.defer = true;
    script.setAttribute("data-cp-capture", "1");
    document.body.appendChild(script);
}

/**
 * Offer the catalog half on pages that have one: the launcher points at the existing per-preview
 * `<details id="cp-report">` (which publishes `data-cp-repo`) rather than building a second report.
 * Hidden on pages with no preview.
 */
function wireCatalogChoice(): void {
    const choice = document.querySelector<HTMLAnchorElement>(".cp-fab-catalog");
    const report = document.querySelector<HTMLElement>("#cp-report");
    if (!choice || !report) return;
    // Completed here because only the page knows the per-catalog repo; the generic wording is a
    // fallback for an affordance with no repo.
    const repo = report.getAttribute("data-cp-repo") || "";
    // …and its subject, published by the affordance: a single preview on the viewer, a page-scoped
    // report on the comparison wall.
    const subject = report.getAttribute("data-cp-subject") || "";
    const what = choice.querySelector<HTMLElement>(".cp-fab-what");
    if (what && subject) {
        const strong = document.createElement("strong");
        strong.textContent = subject;
        // `replaceChildren` + `append`, not innerHTML: the subject is server-rendered text like
        // every other value this file puts on the page.
        what.replaceChildren("Something is wrong with ", strong);
    }
    const who = choice.querySelector<HTMLElement>(".cp-fab-who");
    if (who && repo) {
        const code = document.createElement("code");
        code.textContent = repo;
        // `append`, not an HTML string: the repo is derived from a catalog's own manifest.
        who.append(" — goes to ", code);
    } else if (who) {
        who.append(" — goes to the catalog's own repository");
    }
    choice.hidden = false;
    choice.addEventListener("click", (event) => {
        event.preventDefault();
        const menu = document.querySelector<HTMLDetailsElement>(".cp-fab-menu");
        if (menu) menu.open = false;
        if (report instanceof HTMLDetailsElement) report.open = true;
        report.scrollIntoView({ block: "center", behavior: "smooth" });
        // Focus the field the reporter has to fill in — the Summary is `required`, so landing
        // anywhere else means a second click before they can start typing.
        report
            .querySelector<HTMLInputElement>(".cp-report-summary-input")
            ?.focus({ preventScroll: true });
    });
}

/**
 * Close the report panels once a report is filed: both forms open GitHub in a new tab, so nothing
 * navigates this page and the panel would keep covering the render. Delegated from the document
 * because `#cp-report` is emitted by surface bundles and rebuilt by the wall. Only `<details>`
 * close; fields are kept in case the reporter returns. A `required` Summary blocks empty submits
 * before this fires.
 */
function wireDismissOnSubmit(): void {
    document.addEventListener("submit", (event) => {
        const form = event.target;
        if (!(form instanceof HTMLElement)) return;
        if (!form.matches(".cp-report-form, .cp-report-bug")) return;
        form.closest("details")?.removeAttribute("open");
    });
}

export function installReportLauncher(): void {
    // Deferred until parse: this bundle is first in `<body>`, before the launcher exists.
    whenReady(install);
}

function install(): void {
    wireDismissOnSubmit();
    const fab = document.querySelector<HTMLElement>(".cp-fab");
    if (fab) {
        wireCatalogChoice();
        const menu = fab.querySelector<HTMLDetailsElement>(".cp-fab-menu");
        // Fetched when the panel first opens. `toggle` also fires on close, hence the guard;
        // `loadCapture` is idempotent.
        menu?.addEventListener("toggle", () => {
            if (menu.open) loadCapture();
        });
    }
    // `/report-bug` has no launcher — it IS where the launcher leads — but it renders the captures
    // that came across from the page being reported, so it needs the bundle immediately.
    if (document.querySelector(".cp-shots")) loadCapture();
}
