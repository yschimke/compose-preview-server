// Whether the prefilled report may be rewritten from the controls yet.
//
// The viewer's controls and its copyable links move the instant a knob does; the image does not,
// because the replacement frame has to be fetched and decoded first. `viewer.ts` says so where it
// records the landed frame's provenance — *"`#cp-url-png` / `#cp-url-svg` track the current
// controls, which is not the same thing"* — and `specBaseline.ts` relies on the same gap from the
// other side.
//
// For a copyable link that gap is harmless: it is a link, and it will be right by the time anyone
// follows it. For a REPORT it is the D4 defect (`COMPONENT_PARITY_WORKFLOW.md`): a reporter who
// moves a control and submits before the new pixels land — or after that render fails — would file
// a `compose-parity-locator/v1` block, and an embedded render URL, describing a frame they never
// saw. Both halves would agree with each other and with neither the screen nor the reporter, and
// nothing downstream can tell: the body looks complete and the index takes it.
//
// So the report is composed from the frame that LANDED, not from the controls that asked for one.
// The crude form of that is this predicate — recompose only while the two agree, and otherwise
// leave the field holding the body that described the previous frame, which is the frame still on
// screen. Batch 01 calls the crude form "explicitly acceptable and explicitly preferred" over a
// subtly-wrong derivation, and it needs no new UI state: the field is only ever rewritten at the
// moment a frame arrives, so what it says is what is being looked at.

/**
 * Whether [landedRenderUrl] — the `/render` URL whose bytes are decoded and on the stage — is the
 * one the controls are currently asking for.
 *
 * `null` means **nothing has landed yet**: the server-rendered image is on screen and the server's
 * own body already describes it, so the answer is false and the field is left alone. That is the
 * same "before the first client-side fetch" case [specAtPublishedBaseline] handles, decided the
 * other way round because the two want opposite things from it — that predicate asks whether the
 * stage is still the baseline, this one asks whether there is a landed frame to describe.
 *
 * Compared on **path and query**, not on the whole URL. The landed URL is written from the fetch,
 * which is path-relative; the requested one comes from the copyable links, which are absolute. Both
 * are built from `location` by the same page, so the origin is the one thing that cannot differ —
 * and it is the one thing a raw string comparison would trip over, freezing the report at whatever
 * body it last held.
 *
 * Strict on everything else, deliberately: a false negative costs one skipped recomposition and the
 * next landed frame corrects it, while a false positive is the defect this exists to prevent.
 */
export function reportFollowsDisplayedFrame(
    requestedRenderUrl: string,
    landedRenderUrl: string | null,
): boolean {
    if (!landedRenderUrl) return false;
    const frame = (url: string) => {
        const parsed = new URL(url, "http://viewer.invalid");
        return parsed.pathname + parsed.search;
    };
    try {
        return frame(landedRenderUrl) === frame(requestedRenderUrl);
    } catch {
        return false;
    }
}

/**
 * The stages on which the stage's `<img>` — and therefore `data-cp-src` — describes what the
 * visitor is looking at.
 *
 * An **allowlist**, deliberately. Live, Wasm and the Remote Compose players paint into a canvas or
 * an iframe while `#cp-img` still holds the snapshot fetched at page load, and worse, they apply
 * overrides IN PLACE without ever re-pointing `/render` — `viewer.ts` calls that blob "a stale
 * bystander" in exactly these lanes. So the frame gate above can pass on a lane whose pixels it
 * knows nothing about: the controls are unchanged, the landed URL still matches, and the report
 * would name a static frame the reporter stopped looking at several interactions ago.
 *
 * A denylist would make the next interactive lane indexable by default, which is the failure mode
 * this whole block exists to avoid — silent, and visible only in the filed issue.
 *
 * `motion` is out because the stage plays an animated image the still does not describe, and
 * `source` because the visitor is reading code rather than looking at pixels. `spec` is in: it puts
 * the imported reference on the stage but the frame it is COMPARING is the snapshot underneath, the
 * same reading `specBaseline.ts` takes. `svg` is in because that lane is rendered server-side from
 * the same query, so the landed URL describes it.
 */
const FRAME_STAGES = new Set(["snapshot", "svg", "spec"]);

/**
 * Whether a report filed from [stage] may carry a `compose-parity-locator/v1` block at all.
 *
 * The workflow doc requires reporting to stay disabled outright in the interactive lanes, *"not
 * until overrides land but until the exact displayed frame and its interactive state can actually
 * be transferred"*. Withholding the locator is the half of that which the index depends on: without
 * a block the report is an ordinary issue that `buildIssueIndex` skips, which is exactly what it
 * was before the viewer emitted one — rather than a row keyed to pixels nobody saw.
 */
export function reportMayCarryLocator(stage: string): boolean {
    return FRAME_STAGES.has(stage);
}
