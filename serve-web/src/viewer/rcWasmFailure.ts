// The CMP/Wasm player reports a failed document as `cp-rc-wasm-error:<reason>`, and the reason names
// what it could not render (`BoxLayout[3]: vertical position 1 is not implemented`). The viewer
// shows it, since the generic sentence alone gives a visitor nothing to act on or report. A document
// with many gaps lists them all, so the shown reason is capped.
export const RC_WASM_REASON_MAX = 300;

export function rcWasmFailureMessage(reason: string): string {
    let r = reason.trim();
    if (!r) return "Rendering the Remote Compose document failed in CMP Wasm.";
    if (r.length > RC_WASM_REASON_MAX)
        r = r.slice(0, RC_WASM_REASON_MAX - 1).trimEnd() + "…";
    return "Rendering the Remote Compose document failed in CMP Wasm: " + r;
}
